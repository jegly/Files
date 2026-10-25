package com.jegly.files.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * Compares the running APK's current signing certificate against the expected SHA-256 digest, to
 * detect that the app has been re-signed (repackaged, modded).
 *
 * Catch: trivially defeated by an attacker who can also patch this file, so it's defense-in-depth,
 * not a security boundary. Useful for detecting "casual" tampering by users running modded APKs.
 *
 * Three outcomes rather than a boolean, because "matches" and "nothing to compare against" are
 * different facts and Settings reports this straight to the user.
 */
object IntegrityChecker {

    /** What [verifySignature] was actually able to establish. */
    enum class Status {
        /** The running APK's current signer matches the expected cert. */
        Verified,

        /** The running APK's current signer does not match. Re-signed or repackaged. */
        Mismatch,

        /** No opinion: this is a debug build, signed per-machine, so there is nothing to pin. */
        NotChecked,
    }

    // Expected signing-cert SHA-256, stored XOR-masked (never as greppable hex — string literals
    // survive R8) and compared as raw bytes. P = ML-DSA-87 PQC key (5f99de5b…).
    // minSdk is 37, and API 37 verifies the APK Signature Scheme v3.2 hybrid block, so the current
    // signer is always the PQC key. The old jegly key0 (98d324d4…) is deliberately NOT accepted:
    // an APK re-signed with only key0 would otherwise pass. Confirm against a signed APK with
    // `apksigner verify --print-certs` ("V3.2 Hybrid PQC Signer").
    private val K = intArrayOf(0x32, 0xE1, 0xF7, 0xF5, 0xEF, 0x01, 0xDD, 0x9C, 0x16)
    private val P = intArrayOf(
        0x6D, 0x78, 0x29, 0xAE, 0xDE, 0x94, 0xA8, 0xA1, 0x59, 0xDA, 0x3C, 0x5C, 0x1E, 0x3D, 0xFA, 0xFA,
        0xA9, 0x7D, 0x13, 0xE8, 0x21, 0x0E, 0x5D, 0x46, 0xC2, 0x57, 0xAB, 0xC0, 0xFB, 0x69, 0x83, 0x49,
    )

    /** Reconstruct the expected 32-byte cert digest from the masked form (never materialized as hex). */
    private fun expected(): ByteArray = ByteArray(P.size) { (P[it] xor K[it % K.size]).toByte() }

    fun verifySignature(context: Context): Status {
        // Debug builds are signed with ~/.android/debug.keystore (per-machine, per-developer),
        // so there is nothing stable to pin. The check only means anything for a public release.
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            return Status.NotChecked
        }
        // A failure to read our own signing info is treated as a mismatch rather than as unknown:
        // "couldn't tell" is not a reassuring answer.
        val matches = runCatching { currentSignerMatches(context) }
            .getOrElse { return Status.Mismatch }
        return if (matches) Status.Verified else Status.Mismatch
    }

    private fun currentSignerMatches(context: Context): Boolean {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        // Current signer only — never signingCertificateHistory, which still contains key0.
        val signers = info.signingInfo?.apkContentsSigners ?: return false
        val md = MessageDigest.getInstance("SHA-256")
        val want = expected()
        return signers.any { sig ->
            md.reset()
            md.digest(sig.toByteArray()).contentEquals(want)
        }
    }
}
