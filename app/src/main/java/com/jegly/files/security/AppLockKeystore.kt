package com.jegly.files.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The crypto half of the app lock, ported from www's BiometricAuthManager but with the
 * Tink/EncryptedSharedPreferences layer dropped — this app stores no secrets of its own, so
 * the lock only needs to prove that a real authentication happened.
 *
 * The proof is structural rather than a boolean: the AES key lives in the AndroidKeyStore with
 * setUserAuthenticationRequired(true), so a Cipher initialised from it is unusable until
 * BiometricPrompt unlocks it inside a CryptoObject. Decrypting the stored sentinel therefore
 * cannot be bypassed by patching a `return true` — the key material is in the TEE/StrongBox and
 * the OS refuses to release it without the auth event.
 *
 * setInvalidatedByBiometricEnrollment(true) means enrolling a new fingerprint destroys the key,
 * so an attacker who adds their own biometric to an unlocked device gets a lock that fails
 * closed rather than one that opens for them.
 *
 * The key accepts a strong biometric OR the device credential — see [createKey]. A lock that
 * only a fingerprint could open was unusable on a device with no strong sensor, and refused the
 * PIN or pattern that such a device is actually secured with.
 */
object AppLockKeystore {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "files_app_lock"
    private const val TRANSFORMATION =
        "${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_GCM}/${KeyProperties.ENCRYPTION_PADDING_NONE}"
    private const val SENTINEL = "files-unlocked"
    private const val GCM_TAG_BITS = 128

    class KeyPermanentlyInvalidated : Exception("Biometric enrollment changed; lock must be re-armed")

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    fun hasKey(): Boolean = runCatching { keyStore().containsAlias(KEY_ALIAS) }.getOrDefault(false)

    fun deleteKey() = runCatching { keyStore().deleteEntry(KEY_ALIAS) }.let { }

    /** Creates the auth-bound key. StrongBox is requested and silently downgraded to TEE. */
    private fun createKey(useStrongBox: Boolean): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            /*
             * Timeout 0 = every single use needs its own auth event, which is what makes the
             * CryptoObject meaningful.
             *
             * BIOMETRIC_STRONG *or* DEVICE_CREDENTIAL. Biometrics-only was a needless exclusion:
             * plenty of devices have no strong sensor at all, plenty of people don't enrol one,
             * and the PIN, pattern or password is what those devices are secured with. Both
             * authenticator classes can gate a Keystore key with a per-use auth event, so the
             * structural property this lock rests on — the OS refusing to release the key
             * without a real auth — is identical either way. What is still excluded is class-2
             * (weak) biometrics, which cannot back a CryptoObject at all and would silently
             * degrade the lock into a prompt that proves nothing.
             */
            .setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
            .setInvalidatedByBiometricEnrollment(true)
            .apply { if (useStrongBox) setIsStrongBoxBacked(true) }
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun secretKey(): SecretKey =
        (keyStore().getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey

    /**
     * Cipher for arming the lock. Feed it to BiometricPrompt, then call [sealSentinel] with the
     * unlocked cipher and persist the result.
     *
     * Arming always mints a fresh key. Reusing an existing alias would silently inherit whatever
     * state it was in — possibly already invalidated by a re-enrollment — and produce a lock that
     * looks armed but can never open. Re-arming is the user's explicit reset, so treat it as one.
     */
    fun encryptCipher(strongBoxAvailable: Boolean): Cipher {
        deleteKey()
        runCatching { createKey(strongBoxAvailable) }.onFailure {
            // StrongBox refuses some specs on some devices; fall back to the TEE rather than
            // leaving a half-created alias behind.
            deleteKey()
            createKey(useStrongBox = false)
        }
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
    }

    /**
     * Cipher for unlocking, bound to the IV captured when the lock was armed.
     * Throws [KeyPermanentlyInvalidated] when biometrics were re-enrolled.
     */
    fun decryptCipher(blob: String): Cipher {
        val iv = Base64.decode(blob.substringBefore(':'), Base64.NO_WRAP)
        return try {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            }
        } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
            deleteKey()
            throw KeyPermanentlyInvalidated()
        }
    }

    /** Called with the cipher returned by a successful prompt. */
    fun sealSentinel(cipher: Cipher): String {
        val ct = cipher.doFinal(SENTINEL.toByteArray())
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    /** True only if the auth-bound key actually decrypted the sentinel. */
    fun openSentinel(cipher: Cipher, blob: String): Boolean = runCatching {
        val ct = Base64.decode(blob.substringAfter(':'), Base64.NO_WRAP)
        String(cipher.doFinal(ct)) == SENTINEL
    }.getOrDefault(false)
}
