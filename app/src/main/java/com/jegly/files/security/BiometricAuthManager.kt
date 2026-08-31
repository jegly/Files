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
 * BIOMETRIC_STRONG or DEVICE_CREDENTIAL — a fingerprint, a face, or the device's own PIN,
 * pattern or password. Both can gate a Keystore key with a per-use auth event, which is the
 * property the lock actually rests on; restricting it to biometrics only meant a device with no
 * strong sensor, or a user who has enrolled none, could not arm the lock at all.
 *
 * Class 2 (weak) biometrics stay excluded: they cannot back a Keystore CryptoObject, so allowing
 * them would mean the crypto lock silently degrades into a UI-only prompt that proves nothing.
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
            ?: return "This device can't authenticate you"

        // Either class satisfies the lock, so ask about both at once: a device with no sensor
        // but a PIN set answers SUCCESS here, and used to be told it was unsupported.
        val status = runCatching {
            manager.canAuthenticate(ALLOWED)
        }.getOrElse { return "Authentication couldn't be checked on this device" }

        return when (status) {
            BiometricManager.BIOMETRIC_SUCCESS -> null
            // With DEVICE_CREDENTIAL in the mask, NONE_ENROLLED means there is no screen lock
            // either — so the fix is a screen lock, not a fingerprint.
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                "Set a screen lock — PIN, pattern or password — in Android Settings first"
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                "This device can't authenticate you"
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                "Authentication is unavailable right now"
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
                "A security update is required before this can be used"
            else -> "This device can't authenticate you"
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

        // The cancellation listener and onAuthenticationError can both fire for a single
        // dismissal. Callers here flip UI state, so delivery must be once-only.
        val delivered = AtomicBoolean(false)
        fun succeed(unlocked: Cipher) { if (delivered.compareAndSet(false, true)) onSuccess(unlocked) }
        fun fail(message: String) { if (delivered.compareAndSet(false, true)) onError(message) }

        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setConfirmationRequired(false)
            // No negative button, and that is required rather than a choice: the platform
            // refuses to build a prompt that has both a negative button and DEVICE_CREDENTIAL,
            // because the credential fallback IS the second button. Cancelling now arrives
            // through onAuthenticationError as ERROR_USER_CANCELED / ERROR_NEGATIVE_BUTTON,
            // which fail() already handles.
            .setAllowedAuthenticators(ALLOWED)
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

    private companion object {
        /**
         * What the prompt offers, and what the Keystore key is bound to in [AppLockKeystore].
         * The two must agree: a prompt that accepts something the key does not would succeed and
         * then hand back a cipher the OS refuses to let you use.
         */
        val ALLOWED = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
}
