package com.jegly.files.security

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher

/**
 * Ported from www, with Hilt injection replaced by a plain constructor — this app has one
 * consumer and no DI graph to justify.
 *
 * www used androidx.biometric because its minSdk was 33 and it needed the compat shims. This
 * app uses the platform android.hardware.biometrics.BiometricPrompt instead: at minSdk 37 the
 * whole AndroidX surface is a shim over an API that has been present since 28, and dropping it
 * also drops the requirement that the host be a FragmentActivity — the platform prompt binds to
 * any Activity, so MainActivity stays a plain ComponentActivity and the fragment dependency
 * disappears entirely.
 *
 * BIOMETRIC_STRONG only. Class 2 (weak) biometrics cannot back a Keystore CryptoObject, so
 * allowing them would mean the crypto lock silently degrades into a UI-only prompt.
 */
class BiometricAuthManager(private val context: Context) {

    fun isBiometricAvailable(): Boolean = unavailableReason() == null

    /**
     * Human-readable reason the lock can't be armed, or null when it can.
     *
     * Wrapped in runCatching deliberately. canAuthenticate() is a binder call into AuthService
     * that throws SecurityException when USE_BIOMETRIC isn't held — which is exactly how this
     * crashed the app once already, since the caller in Settings invokes this outside its own
     * try/catch. A capability probe reporting "unavailable" is always a better outcome than
     * taking the process down, whatever the underlying cause.
     */
    fun unavailableReason(): String? {
        val manager = context.getSystemService(BiometricManager::class.java)
            ?: return "This device has no biometric hardware"

        val status = runCatching {
            manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        }.getOrElse { return "Biometrics couldn't be checked on this device" }

        return when (status) {
            BiometricManager.BIOMETRIC_SUCCESS -> null
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                "This device has no strong biometric sensor"
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                "The biometric sensor is unavailable right now"
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                "Enroll a fingerprint or face in Settings first"
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
                "A security update is required before biometrics can be used"
            else -> "Strong biometrics aren't available on this device"
        }
    }

    fun hasStrongBox(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    /**
     * Crypto-bound prompt. [cipher] must already be initialised in ENCRYPT_MODE (arming) or
     * DECRYPT_MODE (unlocking). The same cipher comes back on success and can perform exactly
     * one doFinal() while the auth window is open.
     *
     * Returns the [CancellationSignal] so a caller that navigates away can tear the prompt down;
     * dropping it on the floor is fine, the prompt just lives until the user dismisses it.
     */
    fun showCryptoPrompt(
        activity: Activity,
        cipher: Cipher,
        title: String,
        subtitle: String,
        onSuccess: (Cipher) -> Unit,
        onError: (String) -> Unit,
    ): CancellationSignal {
        val executor = activity.mainExecutor

        // The negative button listener and onAuthenticationError(ERROR_NEGATIVE_BUTTON) can both
        // fire for a single dismissal. Callers here flip UI state, so delivery must be once-only.
        val delivered = AtomicBoolean(false)
        fun succeed(unlocked: Cipher) { if (delivered.compareAndSet(false, true)) onSuccess(unlocked) }
        fun fail(message: String) { if (delivered.compareAndSet(false, true)) onError(message) }

        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setConfirmationRequired(false)
            // BIOMETRIC_STRONG without DEVICE_CREDENTIAL, so a negative button is mandatory.
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButton("Cancel", executor) { _, _ -> fail("Cancelled") }
            .build()

        val cancellation = CancellationSignal()
        cancellation.setOnCancelListener { fail("Cancelled") }

        prompt.authenticate(
            BiometricPrompt.CryptoObject(cipher),
            cancellation,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val unlocked = result.cryptoObject?.cipher
                    if (unlocked != null) succeed(unlocked) else fail("Cipher missing")
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    fail(errString.toString())
                }
            },
        )
        return cancellation
    }
}
