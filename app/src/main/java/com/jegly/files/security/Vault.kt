package com.jegly.files.security

import com.jegly.files.security.ArchiveCrypto.wipe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * An encrypted folder you work inside, rather than an archive you unseal and re-seal.
 *
 * WHY IT LOOKS LIKE THIS. The obvious model — LUKS, VeraCrypt, gocryptfs — is a block device or a
 * FUSE mount, and an unprivileged Android app has neither. Nothing here can "mount" anything. So
 * this is the shape Cryptomator settled on for the same reason: every file encrypted separately,
 * names encrypted too, decrypted one at a time on demand. Listing a directory reads a small index
 * and touches no file contents at all; opening one file decrypts exactly that file.
 *
 * The practical consequence, and the reason it beats a sealed archive for everyday storage: at no
 * point does the whole vault exist in plaintext. A sealed archive has to be fully decrypted before
 * you can read one thing out of it, and stays that way until you remember to re-seal.
 *
 * ON-DISK LAYOUT. A vault is an ordinary directory. Nothing outside it is touched, and an app that
 * knows nothing about vaults sees a folder of opaque names:
 *
 *     MyVault/
 *       vault.jfvault        header: KDF parameters and the wrapped master key
 *       d/                   the encrypted tree's root directory
 *         .idx               this directory's encrypted name index
 *         a3f9c1…            an encrypted file, or a real subdirectory containing its own .idx
 *
 * KEY HIERARCHY. A random master key is generated once at creation and wrapped with a key derived
 * from the password. Everything in the vault is encrypted under the master key, never under the
 * password directly. Two reasons, both of which matter later: changing the password rewraps 32
 * bytes instead of re-encrypting every file, and per-file work costs no PBKDF2 — deriving the key
 * again for each file would mean 210,000 hash iterations to list a folder.
 *
 * WHAT THIS DOES NOT HIDE. Directory structure, file sizes (to within a frame), and modification
 * times are all visible to anyone who can read the folder. Hiding those needs padding and a flat
 * blob store, which trades away the ability to sync or back the vault up sensibly. Names and
 * contents are hidden; shape is not.
 *
 * WHAT THIS DETECTS, AND WHAT IT STILL DOES NOT. A vault is meant to live in shared storage,
 * which is precisely where a hostile party can write to it without holding any Android
 * permission at all — a PC over MTP, an SD card read elsewhere, a sync client, a restored
 * backup. Blobs and indexes are therefore bound to their identity via [blobContext], so
 * rearranged ciphertext fails to open instead of silently decrypting into the wrong answer.
 *
 * Rollback is still undetected: replacing the whole vault with an older copy of itself is
 * indistinguishable from never having changed it, since every part of that copy is authentic.
 * Catching it needs a counter kept somewhere the attacker cannot roll back too, which a folder
 * the user is free to copy, sync and restore cannot provide.
 */
object Vault {

    /** Marks a directory as a vault. Present at the vault root, nowhere else. */
    const val HEADER_NAME = "vault.jfvault"

    private const val TREE_DIR = "d"
    private const val INDEX_NAME = ".idx"

    private val MAGIC = byteArrayOf(
        'J'.code.toByte(), 'F'.code.toByte(), 'V'.code.toByte(), 'A'.code.toByte(),
        'U'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 0,
    )
    /**
     * 2 binds blobs and indexes to their identity — see [blobContext]. A v1 vault's ciphertext
     * would fail to authenticate under v2 with no useful explanation, so the header version is
     * what produces the honest error instead. Nothing has ever run on a device, so no v1 vault
     * exists to migrate; if that stops being true this needs a real migration, which means
     * re-encrypting every blob and index rather than a header rewrite.
     */
    private const val VERSION = 2
    private const val ITERATIONS = 210_000
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BYTES = 32
    private const val STORAGE_NAME_BYTES = 16

    /** See [reclaimOrphans]. An hour is far longer than any import, and this is not urgent work. */
    private const val ORPHAN_GRACE_MS = 60 * 60 * 1000L

    /**
     * Binds a blob to the entry it is filed under, and an index to the directory it describes.
     *
     * Every blob and every index in a vault is sealed under the one master key, and nothing in a
     * container names what it is. Without this, the AEAD proves "someone with the master key
     * wrote this" and stops there — so an attacker who can write to the vault folder, but knows
     * no password, can shuffle ciphertext freely and every piece still opens: swap two blobs and
     * a file opens as its neighbour, drop a subdirectory's .idx over the root's and the root
     * lists the subdirectory. All of it silent, because each piece really was written by the key.
     *
     * The fix is Cryptomator's: mix the identity into the AAD. It is never stored, so a reader
     * has to supply it from where the entry was *found*, and ciphertext that has been moved
     * fails to authenticate rather than decrypting into the wrong answer.
     *
     * A blob's identity is its storage name and an index's is its directory's own name. Both are
     * already random, unique within their parent, and — crucially — never rewritten in place:
     * rename touches only the index, and a vault-to-vault move re-encrypts under a fresh storage
     * name. The domain tag keeps the two namespaces apart, so a directory's index can never be
     * passed off as a file blob that happens to share its name.
     *
     * This does NOT stop rollback: restoring a wholesale older copy of a vault is still
     * undetectable, because every part of it is genuinely authentic. Catching that needs a
     * version counter anchored outside the vault, which a folder you can freely copy cannot have.
     */
    private fun blobContext(storageName: String): ByteArray = "F:$storageName".toByteArray()

    private fun indexContext(dir: File): ByteArray = "D:${dir.name}".toByteArray()

    private val random = SecureRandom()

    class WrongPasswordException : IOException("Wrong password for this vault")

    /** One entry in a vault directory, as the user thinks of it. */
    data class Entry(
        val name: String,
        val isDirectory: Boolean,
        /** The opaque on-disk name, relative to its parent directory. */
        val storageName: String,
        val size: Long,
        val lastModified: Long,
    )

    /** True if [dir] is a vault root. Cheap: one existence check. */
    fun isVault(dir: File): Boolean = dir.isDirectory && File(dir, HEADER_NAME).isFile

    // --- lifecycle --------------------------------------------------------------

    /**
     * Creates a vault in [dir], which must be empty or not yet exist.
     *
     * Refusing a non-empty directory is deliberate rather than fussy: the vault owns the layout
     * inside it, and pre-existing files would sit there unencrypted, looking to the user as
     * though putting something in the folder had protected it.
     */
    fun create(dir: File, password: CharArray) {
        if (dir.exists()) {
            // Each of these was one branch before, written as `listFiles()?.isNotEmpty() == true`.
            // That reads as "refuse a non-empty directory" but silently *permits* the two cases
            // where listFiles() returns null — a path that is a file rather than a directory, and
            // a directory that cannot be read. Both then failed further in with a message about
            // something else entirely. The emptiness guard exists so a user cannot be left
            // thinking pre-existing plaintext got protected by having a vault built around it;
            // "I could not check" has to fail it, not pass it.
            if (!dir.isDirectory) throw IOException("\"${dir.name}\" isn't a folder")
            val existing = dir.listFiles() ?: throw IOException("Can't read \"${dir.name}\"")
            if (existing.isNotEmpty()) throw IOException("\"${dir.name}\" isn't empty")
        }
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Couldn't create \"${dir.name}\"")

        val masterKey = ByteArray(KEY_BYTES).also(random::nextBytes)
        try {
            writeHeader(dir, masterKey, password)
            val tree = File(dir, TREE_DIR)
            if (!tree.mkdirs()) throw IOException("Couldn't prepare the vault")
            writeIndex(tree, emptyList(), masterKey)
        } finally {
            masterKey.zero()
        }
    }

    /**
     * Returns the master key for [dir], or throws [WrongPasswordException].
     *
     * The caller owns the returned key and must zero it — see VaultSession, which is what should
     * normally be holding it.
     */
    fun unlock(dir: File, password: CharArray): ByteArray {
        val header = File(dir, HEADER_NAME)
        if (!header.isFile) throw IOException("\"${dir.name}\" isn't a vault")

        val bytes = header.readBytes()
        val input = DataInputStream(ByteArrayInputStream(bytes))

        val magic = ByteArray(MAGIC.size).also(input::readFully)
        if (!magic.contentEquals(MAGIC)) throw IOException("\"${dir.name}\" isn't a vault")
        val version = input.readInt()
        if (version != VERSION) throw IOException("Unsupported vault version $version")
        val iterations = input.readInt()
        if (iterations !in 1..10_000_000) throw IOException("Implausible key derivation cost")
        val salt = ByteArray(SALT_BYTES).also(input::readFully)
        val nonce = ByteArray(NONCE_BYTES).also(input::readFully)
        val wrapped = ByteArray(input.readInt().also {
            if (it !in 1..1024) throw IOException("Malformed vault header")
        }).also(input::readFully)

        val kek = derive(password, salt, iterations)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                // The header's own bytes authenticate the parameters that produced this key, so
                // an attacker cannot lower the iteration count to make a brute force cheap.
                updateAAD(bytes, 0, bytes.size - wrapped.size - 4)
            }
            return cipher.doFinal(wrapped)
        } catch (t: Throwable) {
            throw WrongPasswordException()
        } finally {
            kek.zero()
        }
    }

    /** Re-wraps the master key under a new password. No file contents are touched. */
    fun changePassword(dir: File, current: CharArray, replacement: CharArray) {
        val masterKey = unlock(dir, current)
        try {
            writeHeader(dir, masterKey, replacement)
        } finally {
            masterKey.zero()
        }
    }

    private fun writeHeader(dir: File, masterKey: ByteArray, password: CharArray) {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)

        val prefix = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply {
                write(MAGIC)
                writeInt(VERSION)
                writeInt(ITERATIONS)
                write(salt)
                write(nonce)
            }
        }.toByteArray()

        val kek = derive(password, salt, ITERATIONS)
        val wrapped = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(prefix)
            }.doFinal(masterKey)
        } finally {
            kek.zero()
        }

        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            write(prefix)
            writeInt(wrapped.size)
            write(wrapped)
        }
        // Written via a temporary and renamed: a header truncated by a crash mid-write would make
        // every file in the vault permanently unreachable, master key and all.
        val target = File(dir, HEADER_NAME)
        val temp = File(dir, "$HEADER_NAME.new")
        temp.writeBytes(out.toByteArray())
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("Couldn't write the vault header")
        }
    }

    // --- directories ------------------------------------------------------------

    /** The encrypted tree's root, which is what a caller navigates from. */
    fun treeRoot(vault: File): File = File(vault, TREE_DIR)

    /**
     * Lists one vault directory. [dir] is a real directory inside the tree.
     *
     * Only the index is read and decrypted — a few hundred bytes — so listing never touches file
     * contents. Sizes come from the encrypted blobs on disk, so they overstate by the container
     * header and per-frame tags; close enough to display, and no reason to decrypt to improve it.
     */
    fun list(dir: File, masterKey: ByteArray): List<Entry> =
        readIndex(dir, masterKey).map { record ->
            val onDisk = File(dir, record.storageName)
            Entry(
                name = record.name,
                isDirectory = record.isDirectory,
                storageName = record.storageName,
                size = if (record.isDirectory) 0L else onDisk.length(),
                lastModified = onDisk.lastModified(),
            )
        }

    /** The entry for one opaque storage name, or null if the index no longer lists it. */
    fun entryFor(dir: File, storageName: String, masterKey: ByteArray): Entry? =
        list(dir, masterKey).firstOrNull { it.storageName == storageName }

    /** A name not already taken in [dir], suffixed the way the filesystem paths suffix theirs. */
    fun uniqueName(dir: File, name: String, masterKey: ByteArray): String {
        val taken = list(dir, masterKey).map { it.name }.toSet()
        if (name !in taken) return name
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var n = 2
        while (true) {
            val candidate = if (ext.isEmpty()) "$stem ($n)" else "$stem ($n).$ext"
            if (candidate !in taken) return candidate
            n++
        }
    }

    /** Creates a subdirectory named [name] inside [dir]. Returns its on-disk directory. */
    fun createDirectory(dir: File, name: String, masterKey: ByteArray): File {
        requireSafeName(name)
        val records = readIndex(dir, masterKey).toMutableList()
        if (records.any { it.name == name }) throw IOException("\"$name\" already exists")
        val storageName = freshStorageName(dir)
        val target = File(dir, storageName)
        if (!target.mkdirs()) throw IOException("Couldn't create \"$name\"")
        writeIndex(target, emptyList(), masterKey)
        records += Record(storageName, name, isDirectory = true)
        writeIndex(dir, records, masterKey)
        return target
    }

    /**
     * Adds [source] to [dir] under [name], encrypting it. Returns the entry written.
     *
     * The index is updated only after the ciphertext is fully written, so an interrupted import
     * leaves an orphaned blob rather than an index entry pointing at a truncated file.
     */
    suspend fun importFile(
        dir: File,
        source: File,
        name: String,
        masterKey: ByteArray,
        onProgress: suspend (deltaBytes: Long) -> Unit = {},
    ): Entry {
        requireSafeName(name)
        val records = readIndex(dir, masterKey).toMutableList()
        if (records.any { it.name == name }) throw IOException("\"$name\" already exists")

        val storageName = freshStorageName(dir)
        val target = File(dir, storageName)
        try {
            source.inputStream().buffered().use { input ->
                target.outputStream().buffered().use { raw ->
                    ArchiveCrypto.encryptingStream(
                        raw, masterKey, blobContext(storageName),
                    ).use { sealed ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            sealed.write(buffer, 0, read)
                            onProgress(read.toLong())
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            target.delete()
            throw t
        }

        records += Record(storageName, name, isDirectory = false)
        writeIndex(dir, records, masterKey)
        return Entry(name, false, storageName, target.length(), target.lastModified())
    }

    /** Decrypts one vault file to [dest]. */
    suspend fun exportFile(
        dir: File,
        storageName: String,
        dest: File,
        masterKey: ByteArray,
        onProgress: suspend (deltaBytes: Long) -> Unit = {},
    ) {
        val source = File(dir, storageName)
        if (!source.isFile) throw IOException("That file is no longer in the vault")
        try {
            source.inputStream().buffered().use { raw ->
                ArchiveCrypto.decryptingStream(
                    raw, masterKey, blobContext(storageName),
                ).use { plain ->
                    dest.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = plain.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            onProgress(read.toLong())
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            dest.delete()
            throw t
        }
    }

    /**
     * Deletes ciphertext that no index refers to, returning the bytes freed.
     *
     * [importFile] writes the blob first and updates the index only once it is whole, so that an
     * interrupted import leaves an unreferenced blob rather than an index entry pointing at a
     * truncated file. That is the right way round — but nothing ever collected the leftovers, so
     * a hard process kill mid-import leaked space permanently and invisibly: the blob is not in
     * any index, so no listing shows it and no delete can reach it.
     *
     * THE HAZARD THIS HAS TO AVOID. Because the blob lands before the index entry, an
     * in-flight import is byte-for-byte indistinguishable from an abandoned one. A collector that
     * ran concurrently with an import would delete the file the user is importing right now —
     * turning a wasted-space bug into a data-loss one, which is far worse than leaving it alone.
     *
     * Two things prevent that, either of which would do:
     *
     *  - **Call it only while the vault is locked to everyone else.** Every write path needs a
     *    key from VaultSession, so running this after [unlock] succeeds but *before* the key is
     *    published means no operation on this vault can be in progress. That is what
     *    VaultSession.unlock does, and it is the real guarantee.
     *  - **[graceMillis]**, belt to that braces: an entry is only collected once it has gone
     *    untouched for an hour, which no live import ever has.
     *
     * Dangling index entries — a record whose blob has been deleted from underneath the vault —
     * are deliberately NOT repaired here. Removing bytes nothing points at is safe; removing a
     * user's *name* for something is not, and a missing blob is already reported by failing to
     * open. Losing a filename should require the user to say so.
     */
    fun reclaimOrphans(
        vaultRoot: File,
        masterKey: ByteArray,
        graceMillis: Long = ORPHAN_GRACE_MS,
    ): Long = reclaimIn(treeRoot(vaultRoot), masterKey, System.currentTimeMillis() - graceMillis)

    private fun reclaimIn(dir: File, masterKey: ByteArray, cutoff: Long): Long {
        val known = readIndex(dir, masterKey).associateBy { it.storageName }
        var freed = 0L
        for (child in dir.listFiles() ?: return 0L) {
            val record = known[child.name]
            when {
                // The directory's own index. Note ".idx.new" is deliberately not exempt: a
                // staged index left behind by a kill mid-write is exactly this function's job.
                child.name == INDEX_NAME -> Unit
                record == null -> if (child.lastModified() <= cutoff) freed += deleteTree(child)
                record.isDirectory && child.isDirectory -> freed += reclaimIn(child, masterKey, cutoff)
            }
        }
        return freed
    }

    private fun deleteTree(f: File): Long {
        var bytes = 0L
        if (f.isDirectory) f.listFiles()?.forEach { bytes += deleteTree(it) } else bytes += f.length()
        f.delete()
        return bytes
    }

    /** Removes an entry, and everything under it if it is a directory. */
    fun delete(dir: File, storageName: String, masterKey: ByteArray) {
        val records = readIndex(dir, masterKey).toMutableList()
        val record = records.firstOrNull { it.storageName == storageName }
            ?: throw IOException("That entry is no longer in the vault")
        File(dir, storageName).deleteRecursively()
        records.remove(record)
        writeIndex(dir, records, masterKey)
    }

    /** Renames an entry. Only the index changes; the ciphertext is untouched. */
    fun rename(dir: File, storageName: String, newName: String, masterKey: ByteArray) {
        requireSafeName(newName)
        val records = readIndex(dir, masterKey).toMutableList()
        val at = records.indexOfFirst { it.storageName == storageName }
        if (at < 0) throw IOException("That entry is no longer in the vault")
        if (records.any { it.name == newName && it.storageName != storageName }) {
            throw IOException("\"$newName\" already exists")
        }
        records[at] = records[at].copy(name = newName)
        writeIndex(dir, records, masterKey)
    }

    // --- index ------------------------------------------------------------------

    private data class Record(
        val storageName: String,
        val name: String,
        val isDirectory: Boolean,
    )

    /**
     * The name index is itself an encrypted container, so filenames never appear in the clear.
     * It is small — one record per entry — so it is read and rewritten whole.
     */
    private fun readIndex(dir: File, masterKey: ByteArray): List<Record> {
        val index = File(dir, INDEX_NAME)
        if (!index.isFile) throw IOException("This vault folder is missing its index")
        val plain = ByteArrayOutputStream()
        index.inputStream().buffered().use { raw ->
            ArchiveCrypto.decryptingStream(raw, masterKey, indexContext(dir))
                .use { it.copyTo(plain) }
        }
        val input = DataInputStream(ByteArrayInputStream(plain.toByteArray()))
        val count = input.readInt()
        if (count < 0 || count > 1_000_000) throw IOException("Malformed vault index")
        return buildList {
            repeat(count) {
                add(
                    Record(
                        storageName = input.readUTF(),
                        name = input.readUTF(),
                        isDirectory = input.readBoolean(),
                    )
                )
            }
        }
    }

    private fun writeIndex(dir: File, records: List<Record>, masterKey: ByteArray) {
        val plain = ByteArrayOutputStream()
        DataOutputStream(plain).apply {
            writeInt(records.size)
            records.forEach {
                writeUTF(it.storageName)
                writeUTF(it.name)
                writeBoolean(it.isDirectory)
            }
        }
        // Same reasoning as the header: a half-written index loses every name in the directory,
        // so it is staged and renamed rather than truncating the live one.
        val target = File(dir, INDEX_NAME)
        val temp = File(dir, "$INDEX_NAME.new")
        try {
            temp.outputStream().buffered().use { raw ->
                ArchiveCrypto.encryptingStream(raw, masterKey, indexContext(dir))
                    .use { it.write(plain.toByteArray()) }
            }
            if (!temp.renameTo(target)) throw IOException("Couldn't update the vault index")
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    /**
     * Rejects a name that could not be a single path segment, before it can enter an index.
     *
     * Defence in depth, not a live hole: every caller today routes user input through
     * FileRepository.sanitizedName first, and the index is authenticated under the master key, so
     * nobody without the password can inject one of these. What makes it worth enforcing here is
     * where a stored name ends up. FileOperations.exportTree does `File(destDir, entry.name)` with
     * no containment check, so a record reading "../../evil" would write outside the destination
     * the user chose — while this process holds MANAGE_EXTERNAL_STORAGE. That is the same shape as
     * Zip Slip, which Archives already defends against precisely because entry names are arbitrary
     * strings the container format does not validate.
     *
     * The difference is who is responsible. Today the guarantee rests on four separate call sites
     * each remembering to sanitise, and one more gets added every time the vault grows a feature.
     * Vault owns the index, so the invariant belongs here, where it holds no matter who calls.
     *
     * Rejects rather than rewrites, deliberately: silently repairing a name would leave the index
     * disagreeing with what the caller believes it stored.
     */
    private fun requireSafeName(name: String) {
        require(name.isNotEmpty()) { "Name can't be empty" }
        require(!name.contains('/')) { "Name can't contain a path separator" }
        require(!name.contains('\u0000')) { "Name can't contain a null byte" }
        require(name != "." && name != "..") { "Invalid name" }
    }

    /** An opaque name unused in [dir]. Random rather than derived, so names leak nothing. */
    private fun freshStorageName(dir: File): String {
        while (true) {
            val bytes = ByteArray(STORAGE_NAME_BYTES).also(random::nextBytes)
            val name = bytes.joinToString("") { "%02x".format(it) }
            if (!File(dir, name).exists()) return name
        }
    }

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.zero() = Arrays.fill(this, 0)
}
