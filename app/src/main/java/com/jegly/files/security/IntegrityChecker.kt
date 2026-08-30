package com.jegly.files.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * Compares the running APK's signing certificate against a hardcoded list of acceptable SHA-256
 * digests, to detect that the app has been re-signed (repackaged, modded).
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
        /** A digest was declared and the running APK matches it. */
        Verified,

        /** A digest was declared and the running APK does not match. Re-signed or repackaged. */
        Mismatch,

        /**
         * No opinion: either this is a debug build, or no digest has been declared to check
         * against. Deliberately distinct from [Verified] — see the note on [EXPECTED_SHA256].
         */
        NotChecked,
    }

    /**
     * SHA-256 digests of acceptable signing certs (hex, lowercase, no separators).
     *
     * This is the jegly release key, the same one every app in this family is signed with — the
     * value is identical to the one in `jegly/www`, which is where this file was ported from.
     * It is *not* a placeholder, and it is not the debug key (`d8ba2d64…`): debug builds are
     * signed per-machine with `~/.android/debug.keystore` and short-circuit to
     * [Status.NotChecked] before this set is consulted.
     *
     * Releases are signed through Android Studio rather than a Gradle `signingConfig`, so nothing
     * in this repo pins the key and there is no keystore here to check against. Confirm against
     * an actual signed APK if this ever comes into question:
     *
     *     apksigner verify --print-certs app-release.apk
     *
     * Empty would mean [Status.NotChecked], NOT [Status.Verified] — see the enum.
     */
    private val EXPECTED_SHA256: Set<String> = setOf(
        "98d324d4106a368c62729a0a24d9ac9a6b47f8ac4c6585348531f0ee4eb6a04c",
    )

    fun verifySignature(context: Context): Status {
        // Debug builds are signed with ~/.android/debug.keystore (per-machine, per-developer),
        // so there is nothing stable to pin. The check only means anything for a public release.
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            return Status.NotChecked
        }
        if (EXPECTED_SHA256.isEmpty()) return Status.NotChecked
        // A failure to read our own signing info is treated as a mismatch rather than as unknown:
        // once a digest has been declared, "couldn't tell" is not a reassuring answer.
        val actual = runCatching { currentSigningDigests(context) }
            .getOrElse { return Status.Mismatch }
        return if (actual.any { it in EXPECTED_SHA256 }) Status.Verified else Status.Mismatch
    }

    private fun currentSigningDigests(context: Context): List<String> {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info.signingInfo ?: return emptyList()
        val sigs = if (signers.hasMultipleSigners()) {
            signers.apkContentsSigners
        } else {
            signers.signingCertificateHistory
        }
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { sig ->
            md.reset()
            md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
