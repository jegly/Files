# Files

Files is the file manager built into Android itself — AOSP's `com.android.documentsui`, the file browser and document picker present on effectively every Android device, even where an OEM app hides it — rewritten from scratch in Kotlin and Jetpack Compose as a standalone, hardened app.

DocumentsUI itself can't be installed as a regular app: it's a privileged system component signed with `MANAGE_DOCUMENTS`, unobtainable by any third-party APK, and on stock Pixel/GMS builds its own launcher icon is hidden in favor of Files by Google. This is the same UI and interaction model, rebuilt as a sideloadable app using `MANAGE_EXTERNAL_STORAGE` instead — so it runs on any Android device, not only a custom ROM — with no network access at all and a biometric app lock DocumentsUI itself doesn't have.

## Notable

- 100% Kotlin, 100% Compose — no XML layouts, no Java
- No `INTERNET` permission, actively stripped at the manifest level so no dependency can reintroduce it
- Biometric app lock backed by an AndroidKeyStore key, not a stored PIN
- Copy / move / delete / compress / extract, with byte-accurate progress and cancellation
- Browse inside a `.zip` as though it were a folder, the way DocumentsUI's `ArchivesProvider` does
- Password-protected archives and encrypted vaults, on `javax.crypto` alone — no third-party
  crypto dependency
- minSdk / targetSdk 37

## Encryption

Two features over one AES-256-GCM core, framed rather than whole-file so nothing is decrypted
into memory or onto disk in one piece.

**Sealed archives** (`.jfsec`) — compress with a password. Unlike a WinZip-AES zip, which leaves
the central directory in plaintext for anyone to list, the entire zip is inside the ciphertext,
so filenames are hidden too. The trade is interoperability: only this app can open one.

**Vaults** — a folder you work inside, with per-file encryption and encrypted names, decrypted
one file at a time on demand. Copy, move and delete work across the boundary in either direction,
so an ordinary Copy becomes encrypt, decrypt or re-encrypt depending on where the bytes are going.

A vault is meant to live in shared storage, where you can back it up or sync it — and therefore
where something other than this app can write to it. Blobs and indexes are bound to their
identity, so ciphertext that has been rearranged fails to open rather than quietly decrypting
into the wrong file. What a vault does *not* hide is shape: directory structure, file sizes to
within a frame, and modification times. Restoring a wholesale older copy of a vault is also not
detectable — both are deliberate trade-offs, not oversights.

## Building

Needs a JDK 25 — Android Studio's bundled JBR works. The Gradle wrapper is checked in, and pins
the SHA-256 of the distribution it downloads.

```bash
JAVA_HOME=/path/to/jdk25 ./gradlew assembleDebug testDebugUnitTest lintDebug
```

## Release signing

Release APKs use hybrid signing (APK Signature Scheme v3.2): the original app key plus an EC P-384 key and an ML-DSA-87 post-quantum key. Android 17 and newer verify the hybrid block, where the ML-DSA-87 key is the signer; earlier Android versions verify the original key as before. The app's tamper check follows the same rule (minSdk is 37, so it accepts the ML-DSA-87 key).

| File | SHA-256 |
| --- | --- |
| `Files-1.2.3-PQC-signed.apk` | `d2ce7e2d78509fec8f67a6c890979883a6786232a66e1eddc92ae5a47ba0b7b6` |

Verify a download:

```bash
sha256sum Files-1.2.3-PQC-signed.apk
apksigner verify -v --print-certs Files-1.2.3-PQC-signed.apk   # build-tools 37.0.0 on JDK 25 shows all three keys
```

Signing certificate SHA-256:

- ML-DSA-87 (post-quantum): `5f99de5b3195753d4fe8ddabebd2fb27356b2109d6fbb2471fcbbdf21a9e76a6`
- EC P-384 (hybrid classical): `fa8f4a07e6a4f2b7dc6e5ab31fc05592b1414ab9436d1e2e39b35ce3bb2e33a2`
- Original key: `98d324d4106a368c62729a0a24d9ac9a6b47f8ac4c6585348531f0ee4eb6a04c`

## License

Apache License 2.0 — see [LICENSE](LICENSE). Same license family as the AOSP project this is
modeled on.
