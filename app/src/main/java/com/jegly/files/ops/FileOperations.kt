package com.jegly.files.ops

import android.annotation.SuppressLint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import com.jegly.files.security.ArchiveCrypto
import com.jegly.files.security.SecureErase
import com.jegly.files.security.Vault
import com.jegly.files.security.VaultSession
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class OpKind { Copy, Move, Delete, Compress, Extract }

enum class ConflictPolicy { Skip, Overwrite, KeepBoth }

sealed interface OpProgress {
    data class Running(
        val kind: OpKind,
        val currentName: String,
        val filesDone: Int,
        val filesTotal: Int,
        val bytesDone: Long,
        val bytesTotal: Long,
    ) : OpProgress {
        val fraction: Float
            get() = if (bytesTotal <= 0L) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
    }

    data class Finished(
        val kind: OpKind,
        val succeeded: Int,
        val skipped: Int,
        val failures: List<Failure>,
        /** Stopped by the user rather than run to completion; whatever finished still stands. */
        val cancelled: Boolean = false,
    ) : OpProgress

    data class Failure(val path: String, val reason: String)
}

/**
 * Copy / move / delete over a set of roots.
 *
 * Two properties worth preserving if you refactor this:
 *  - The whole tree is measured before any byte is written, so progress is truthful rather
 *    than a spinner that jumps to 100%.
 *  - A failure on one entry never aborts the batch. Failures are collected and reported at
 *    the end; a partial copy is deleted so you never leave a truncated file behind.
 */
class FileOperations {

    /**
     * [password] seals a Compress and opens an encrypted Extract. Null means an ordinary zip.
     * It is never written anywhere and never leaves this process — see FileOperationService for
     * why it is not an Intent extra.
     */
    fun run(
        kind: OpKind,
        sources: List<File>,
        destination: File?,
        policy: ConflictPolicy = ConflictPolicy.KeepBoth,
        password: CharArray? = null,
    ): Flow<OpProgress> = channelFlow {
        val plan = buildPlan(kind, sources)
        var filesDone = 0
        var bytesDone = 0L
        var skipped = 0
        val failures = mutableListOf<OpProgress.Failure>()

        suspend fun emitRunning(name: String) {
            send(
                OpProgress.Running(
                    kind = kind,
                    currentName = name,
                    filesDone = filesDone,
                    filesTotal = plan.fileCount,
                    bytesDone = bytesDone,
                    bytesTotal = plan.byteCount,
                )
            )
        }

        // Compress is the one kind that consumes the whole source set at once rather than
        // per-entry, so it runs outside the per-source loop instead of inside it.
        if (kind == OpKind.Compress) {
            val archive = requireNotNull(destination) { "Compress needs a target archive" }
            emitRunning(archive.name)
            try {
                compress(sources, archive, password) { delta, name ->
                    bytesDone += delta
                    emitRunning(name)
                }
                filesDone = plan.fileCount
            } catch (ce: CancellationException) {
                // A half-written archive is never a useful artifact.
                archive.delete()
                throw ce
            } catch (t: Throwable) {
                archive.delete()
                failures += OpProgress.Failure(archive.absolutePath, t.message ?: "Unknown error")
            }
            send(OpProgress.Finished(kind, filesDone, skipped, failures))
            return@channelFlow
        }

        for (source in sources) {
            currentCoroutineContext().ensureActive()
            emitRunning(source.name)
            try {
                // Ported from AOSP CopyJob.start(), which guards the same way immediately before
                // dispatching each source and reports a per-source failure rather than aborting
                // the batch. Applies to Move too: upstream's MoveJob extends CopyJob and
                // overrides processDocument(), not start(), so it inherits this check.
                if (kind == OpKind.Copy || kind == OpKind.Move) {
                    recursionRefusal(kind, source, requireNotNull(destination))?.let { reason ->
                        failures += OpProgress.Failure(source.absolutePath, reason)
                        continue
                    }
                }
                when (kind) {
                    OpKind.Delete -> {
                        bytesDone += deleteAnywhere(source)
                        filesDone++
                    }

                    /*
                     * A transfer with a vault on either end is a different operation wearing the
                     * same name, so it is checked before the ordinary filesystem paths. The user
                     * asked to copy; whether that means encrypt, decrypt or re-encrypt is decided
                     * by where the bytes are going, not by a separate menu item they have to find.
                     */
                    OpKind.Copy, OpKind.Move -> {
                        val destDir = requireNotNull(destination)
                        val move = kind == OpKind.Move
                        if (touchesVault(source, destDir)) {
                            transferVault(source, destDir, policy, move) { delta, name ->
                                bytesDone += delta
                                emitRunning(name)
                            }
                            filesDone++
                        } else {
                            val target = resolveTarget(source, destDir, policy)
                            if (target == null) {
                                skipped++
                            } else if (move && source.renameTo(target)) {
                                // Same-volume rename: instant, no bytes moved.
                                bytesDone += sizeOf(source.takeIf { it.exists() } ?: target)
                                filesDone++
                            } else {
                                copyTree(source, target) { delta, name ->
                                    bytesDone += delta
                                    emitRunning(name)
                                }
                                if (move) deleteRecursively(source)
                                filesDone++
                            }
                        }
                    }

                    OpKind.Extract -> {
                        // Counted per entry written, not per archive: buildPlan measures the
                        // archive's entries, so incrementing once per source made a 400-file
                        // archive report "1/400" for its whole run.
                        filesDone += extract(
                            source,
                            requireNotNull(destination),
                            policy,
                            password,
                        ) { delta, name ->
                            bytesDone += delta
                            emitRunning(name)
                        }
                    }

                    OpKind.Compress -> error("Handled above")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                failures += OpProgress.Failure(source.absolutePath, t.message ?: "Unknown error")
            }
        }

        send(OpProgress.Finished(kind, filesDone, skipped, failures))
    }.flowOn(Dispatchers.IO)

    // --- planning ---------------------------------------------------------------

    private data class Plan(val fileCount: Int, val byteCount: Long)

    private fun buildPlan(kind: OpKind, sources: List<File>): Plan {
        // Extract measures the archive's declared uncompressed sizes, not the file on disk —
        // otherwise a 40MB zip holding 400MB of data reports 1000% progress.
        if (kind == OpKind.Extract) {
            var files = 0
            var bytes = 0L
            for (archive in sources) {
                // An encrypted archive cannot be measured without the password, and asking the
                // key derivation to run twice just for a progress bar is a poor trade. Its own
                // size is a floor for the plaintext, so the bar advances honestly and simply
                // finishes early rather than reporting a fictional total.
                if (ArchiveCrypto.isContainer(archive)) {
                    files++
                    bytes += archive.length()
                    continue
                }
                runCatching {
                    ZipFile(archive).use { zip ->
                        for (entry in zip.entries()) {
                            if (entry.isDirectory) continue
                            files++
                            // size is -1 when the entry has no declared length.
                            if (entry.size > 0) bytes += entry.size
                        }
                    }
                }
            }
            return Plan(files.coerceAtLeast(sources.size), bytes)
        }
        return buildPlan(sources)
    }

    private fun buildPlan(sources: List<File>): Plan {
        var files = 0
        var bytes = 0L
        val stack = ArrayDeque(sources)
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            if (f.isWalkableDirectory()) {
                f.listFiles()?.forEach { stack.addLast(it) }
            } else if (!f.isDirectory) {
                files++
                bytes += f.length()
            }
        }
        return Plan(files.coerceAtLeast(sources.size), bytes)
    }

    private fun sizeOf(f: File): Long =
        if (f.isDirectory) buildPlan(listOf(f)).byteCount else f.length()

    // --- vaults -----------------------------------------------------------------

    /**
     * The vault [file] is *inside*, or null — the vault root itself counts as outside, so copying
     * a whole vault folder stays an ordinary directory copy of its ciphertext. See
     * [Vault.isInsideVault], which the UI shares for the same distinction.
     */
    private fun insideVault(file: File): File? =
        Vault.rootOf(file)?.takeIf { it.absolutePath != file.absolutePath }

    private fun touchesVault(source: File, destDir: File): Boolean =
        insideVault(source) != null || insideVault(destDir) != null || Vault.isVault(destDir)

    private fun keyFor(vaultRoot: File): ByteArray =
        VaultSession.keyFor(vaultRoot)
            ?: throw IOException("\"${vaultRoot.name}\" is locked")

    /**
     * Copies or moves across a vault boundary, in whichever direction the paths imply.
     *
     * Keys are read from [VaultSession] rather than passed in, so key material never travels
     * through an Intent, a parameter chain, or this class's own state.
     */
    private suspend fun transferVault(
        source: File,
        destDir: File,
        policy: ConflictPolicy,
        move: Boolean,
        onProgress: suspend (deltaBytes: Long, currentName: String) -> Unit,
    ) {
        val fromVault = insideVault(source)
        val toVault = insideVault(destDir) ?: destDir.takeIf { Vault.isVault(it) }

        /*
         * A vault root is a destination the user can name — "copy into MyVault" — but it is not
         * a directory the encrypted tree lives in: the tree starts one level down, beside the
         * header. Handing the root itself to importTree wrote blobs and an index next to
         * vault.jfvault, where nothing that lists the vault ever looks, so the files were
         * encrypted correctly and then invisible.
         */
        val destTree = if (toVault != null && Vault.isVault(destDir)) {
            Vault.treeRoot(destDir)
        } else {
            destDir
        }

        when {
            fromVault == null && toVault != null ->
                importTree(source, destTree, keyFor(toVault), policy, onProgress)

            fromVault != null && toVault == null ->
                exportTree(source, destDir, keyFor(fromVault), policy, onProgress)

            fromVault != null && toVault != null ->
                // Both ends encrypted. Going through plaintext in memory per file is the only
                // option when the two vaults have different master keys, and re-encrypting under
                // the destination's key is required even when they are the same vault.
                relocateInVault(
                    source, destTree, keyFor(fromVault), keyFor(toVault), policy, onProgress,
                )

            else -> throw IOException("Not a vault transfer")
        }

        if (move) {
            if (fromVault != null) {
                val parent = source.parentFile ?: throw IOException("No parent folder")
                Vault.delete(parent, source.name, keyFor(fromVault))
            } else {
                deleteRecursively(source)
            }
        }
    }

    /** Real files in, encrypted. Directories recurse, creating matching vault directories. */
    private suspend fun importTree(
        source: File,
        destVaultDir: File,
        key: ByteArray,
        policy: ConflictPolicy,
        onProgress: suspend (deltaBytes: Long, currentName: String) -> Unit,
    ) {
        currentCoroutineContext().ensureActive()
        val existing = Vault.entryFor(destVaultDir, source.name, key)
        if (source.isWalkableDirectory()) {
            val sub = Vault.list(destVaultDir, key)
                .firstOrNull { it.name == source.name && it.isDirectory }
                ?.let { File(destVaultDir, it.storageName) }
                ?: Vault.createDirectory(destVaultDir, source.name, key)
            source.listFiles()?.forEach { importTree(it, sub, key, policy, onProgress) }
            return
        }
        if (source.isDirectory) return

        val name = when {
            existing == null -> source.name
            policy == ConflictPolicy.Skip -> return
            policy == ConflictPolicy.Overwrite -> {
                Vault.delete(destVaultDir, existing.storageName, key)
                source.name
            }
            else -> Vault.uniqueName(destVaultDir, source.name, key)
        }
        Vault.importFile(destVaultDir, source, name, key) { onProgress(it, source.name) }
    }

    /** Encrypted files out, decrypted, preserving their real names. */
    private suspend fun exportTree(
        blob: File,
        destDir: File,
        key: ByteArray,
        policy: ConflictPolicy,
        onProgress: suspend (deltaBytes: Long, currentName: String) -> Unit,
    ) {
        currentCoroutineContext().ensureActive()
        val parent = blob.parentFile ?: throw IOException("No parent folder")
        val entry = Vault.entryFor(parent, blob.name, key)
            ?: throw IOException("That entry is no longer in the vault")

        if (entry.isDirectory) {
            val sub = File(destDir, entry.name)
            if (!sub.exists() && !sub.mkdirs()) throw IOException("Couldn't create ${entry.name}")
            Vault.list(blob, key).forEach { child ->
                exportTree(File(blob, child.storageName), sub, key, policy, onProgress)
            }
            return
        }

        val target = resolveTarget(entry.name, destDir, policy) ?: return
        try {
            Vault.exportFile(parent, blob.name, target, key) { onProgress(it, entry.name) }
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
    }

    /** Vault to vault. Decrypts under one key and re-encrypts under the other, per file. */
    private suspend fun relocateInVault(
        blob: File,
        destVaultDir: File,
        fromKey: ByteArray,
        toKey: ByteArray,
        policy: ConflictPolicy,
        onProgress: suspend (deltaBytes: Long, currentName: String) -> Unit,
    ) {
        currentCoroutineContext().ensureActive()
        val parent = blob.parentFile ?: throw IOException("No parent folder")
        val entry = Vault.entryFor(parent, blob.name, fromKey)
            ?: throw IOException("That entry is no longer in the vault")

        if (entry.isDirectory) {
            val sub = Vault.list(destVaultDir, toKey)
                .firstOrNull { it.name == entry.name && it.isDirectory }
                ?.let { File(destVaultDir, it.storageName) }
                ?: Vault.createDirectory(destVaultDir, entry.name, toKey)
            Vault.list(blob, fromKey).forEach { child ->
                relocateInVault(
                    File(blob, child.storageName), sub, fromKey, toKey, policy, onProgress,
                )
            }
            return
        }

        val existing = Vault.entryFor(destVaultDir, entry.name, toKey)
        val name = when {
            existing == null -> entry.name
            policy == ConflictPolicy.Skip -> return
            policy == ConflictPolicy.Overwrite -> {
                Vault.delete(destVaultDir, existing.storageName, toKey)
                entry.name
            }
            else -> Vault.uniqueName(destVaultDir, entry.name, toKey)
        }

        // Staged through a scratch file rather than held in memory: a vault can hold something
        // far larger than the heap, and the plaintext is app-private and deleted immediately.
        val scratch = File.createTempFile("vault-relocate", null)
        try {
            Vault.exportFile(parent, blob.name, scratch, fromKey) { onProgress(it, entry.name) }
            Vault.importFile(destVaultDir, scratch, name, toKey)
        } finally {
            scratch.delete()
        }
    }

    /** Delete that knows which side of a vault boundary it is on. Returns bytes freed. */
    private fun deleteAnywhere(source: File): Long {
        val vaultRoot = insideVault(source) ?: return deleteRecursively(source)
        val parent = source.parentFile ?: throw IOException("No parent folder")
        val freed = sizeOf(source)
        Vault.delete(parent, source.name, keyFor(vaultRoot))
        return freed
    }

    // --- recursion and symlink guards -------------------------------------------

    /**
     * Refuses a copy/move of a directory into itself or into one of its own descendants,
     * returning the reason to record as a per-source failure, or null when the transfer is fine.
     *
     * Ported from AOSP's CopyJob.start(): "Copying recursively to itself or one of descendants
     * is not allowed." Upstream needs three separate checks there — an equality test on the
     * document, isDescendantOf() within one authority, and isRecursiveCopy() comparing st_dev
     * and st_ino through Os.fstat() across authorities, because the same directory can carry two
     * different URIs. Everything here is one raw-path namespace, so canonical paths collapse all
     * three into a single comparison: canonicalFile resolves symlinks, which is precisely what
     * upstream's inode walk buys them.
     *
     * Left unguarded, copyTree descends into the directory it is itself creating: each level's
     * listFiles() sees the child made one level up, so it recurses until PATH_MAX or a
     * StackOverflowError stops it, having littered the destination with hundreds of nested
     * directories. The operation then fails with whatever exception happened to end the
     * recursion, which tells the user nothing about what they actually did wrong.
     *
     * Measured, not assumed: with this guard removed, Move's source does survive, because the
     * throw short-circuits the deleteRecursively(source) that follows copyTree in the Move
     * branch. So this is a mess-and-confusing-failure bug rather than a data-loss one — see
     * FileOperationsTest, which pins both halves of that behaviour.
     */
    private fun recursionRefusal(kind: OpKind, source: File, destDir: File): String? {
        val src = runCatching { source.canonicalFile }.getOrNull() ?: return null
        val dst = runCatching { destDir.canonicalFile }.getOrNull() ?: return null
        if (!src.isDirectory) return null
        val verb = if (kind == OpKind.Move) "Can't move" else "Can't copy"
        return when {
            dst == src -> "$verb \"${source.name}\" into itself"
            dst.path.startsWith(src.path + File.separator) ->
                "$verb \"${source.name}\" into a folder inside itself"
            else -> null
        }
    }

    /**
     * Symlinks report isDirectory() from their target, so every unguarded tree walk here would
     * follow one out of the tree it was given. That matters most for delete: recursing through a
     * symlinked folder empties the directory it points at and then unlinks the link, which is not
     * what "delete this folder" means to anyone. A symlink loop also makes the walk non-
     * terminating. Shared storage is FUSE-backed and effectively symlink-free, but an ext4 USB
     * drive is not, and this process holds MANAGE_EXTERNAL_STORAGE.
     *
     * Treated as leaves everywhere: delete unlinks the link itself, and copy recreates the
     * directory without descending — the closest a plain File API can get to `cp -r`'s behaviour,
     * since there is no way to recreate the link.
     */
    private fun File.isSymlink(): Boolean =
        runCatching { Files.isSymbolicLink(toPath()) }.getOrDefault(false)

    /** True for a directory we should walk into — i.e. a real one, not a link to one. */
    private fun File.isWalkableDirectory(): Boolean = isDirectory && !isSymlink()

    // --- conflict resolution ----------------------------------------------------

    private fun resolveTarget(source: File, destDir: File, policy: ConflictPolicy): File? =
        resolveTarget(source.name, destDir, policy)

    private fun resolveTarget(name: String, destDir: File, policy: ConflictPolicy): File? {
        val naive = File(destDir, name)
        if (!naive.exists()) return naive
        return when (policy) {
            ConflictPolicy.Skip -> null
            ConflictPolicy.Overwrite -> naive
            ConflictPolicy.KeepBoth -> uniqueName(destDir, name)
        }
    }

    private fun uniqueName(dir: File, name: String): File {
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var n = 2
        while (true) {
            val candidate = if (ext.isEmpty()) "$stem ($n)" else "$stem ($n).$ext"
            val f = File(dir, candidate)
            if (!f.exists()) return f
            n++
        }
    }

    // --- primitives -------------------------------------------------------------

    private suspend fun copyTree(
        source: File,
        target: File,
        onProgress: suspend (deltaBytes: Long, currentName: String) -> Unit,
    ) {
        if (source.isDirectory) {
            if (!target.exists() && !target.mkdirs()) {
                throw IOException("Couldn't create ${target.name}")
            }
            // A symlinked directory is recreated empty rather than followed — see isSymlink().
            if (!source.isSymlink()) {
                source.listFiles()?.forEach { child ->
                    currentCoroutineContext().ensureActive()
                    copyTree(child, File(target, child.name), onProgress)
                }
            }
            target.setLastModified(source.lastModified())
        } else {
            copyFile(source, target, onProgress)
        }
    }

    private suspend fun copyFile(
        source: File,
        target: File,
        onProgress: suspend (deltaBytes: Long, currentName: String) -> Unit,
    ) {
        target.parentFile?.mkdirs()
        val buffer = ByteArray(DEFAULT_BUFFER)
        try {
            source.inputStream().use { input ->
                target.outputStream().use { output ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        onProgress(read.toLong(), source.name)
                    }
                    output.flush()
                }
            }
            target.setLastModified(source.lastModified())
        } catch (t: Throwable) {
            // Never leave a half-written file on disk.
            target.delete()
            throw t
        }
    }

    // --- archives ---------------------------------------------------------------

    /**
     * Zip only, deliberately. java.util.zip is in the platform, so this costs no dependency;
     * 7z/rar/tar would each need a third-party library, and pulling one into an app whose whole
     * pitch is a minimal attack surface is a poor trade for a format most users never hit.
     */
    private suspend fun compress(
        sources: List<File>,
        archive: File,
        password: CharArray?,
        onProgress: suspend (deltaBytes: Long, currentName: String) -> Unit,
    ) {
        archive.parentFile?.mkdirs()
        // With a password the zip is written straight into the encryptor, so no plaintext copy
        // of the archive ever exists on disk — not even briefly in cache.
        val sink: OutputStream = BufferedOutputStream(archive.outputStream()).let { raw ->
            if (password == null) raw else ArchiveCrypto.encryptingStream(raw, password)
        }
        ZipOutputStream(sink).use { zip ->
            for (source in sources) {
                currentCoroutineContext().ensureActive()
                addToZip(zip, source, source.name, onProgress)
            }
        }
    }

    private suspend fun addToZip(
        zip: ZipOutputStream,
        file: File,
        entryPath: String,
        onProgress: suspend (deltaBytes: Long, currentName: String) -> Unit,
    ) {
        currentCoroutineContext().ensureActive()
        if (file.isDirectory) {
            // The trailing slash is what marks an entry as a directory in the zip format.
            zip.putNextEntry(ZipEntry("$entryPath/").apply { time = file.lastModified() })
            zip.closeEntry()
            // Not followed, for the same reason as copyTree — and here a symlink loop would
            // otherwise produce an archive that grows until the volume is full.
            if (!file.isSymlink()) {
                file.listFiles()?.forEach { addToZip(zip, it, "$entryPath/${it.name}", onProgress) }
            }
            return
        }
        zip.putNextEntry(ZipEntry(entryPath).apply { time = file.lastModified() })
        val buffer = ByteArray(DEFAULT_BUFFER)
        file.inputStream().use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read <= 0) break
                zip.write(buffer, 0, read)
                onProgress(read.toLong(), file.name)
            }
        }
        zip.closeEntry()
    }

    /**
     * Returns the number of files actually written, for truthful progress counting.
     *
     * Two iteration strategies, one set of rules. A plain zip is read through ZipFile, which uses
     * the central directory and can seek. An encrypted one cannot: the plaintext only exists as a
     * stream, so it is read sequentially through ZipInputStream. Both funnel every entry through
     * [writeEntry], so the Zip Slip check, the free-space ceiling and the conflict policy cannot
     * drift apart between the two.
     */
    private suspend fun extract(
        archive: File,
        destDir: File,
        policy: ConflictPolicy,
        password: CharArray?,
        onProgress: suspend (deltaBytes: Long, currentName: String) -> Unit,
    ): Int {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw IOException("Couldn't create ${destDir.name}")
        }
        // Resolve once, up front: every entry is validated against this exact prefix.
        val root = destDir.canonicalFile
        var written = 0

        if (ArchiveCrypto.isContainer(archive)) {
            val key = password ?: throw IOException("${archive.name} needs a password")
            archive.inputStream().buffered().use { raw ->
                ArchiveCrypto.decryptingStream(raw, key).use { plain ->
                    ZipInputStream(plain).use { zip ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val entry = zip.nextEntry ?: break
                            // Not closed here: the entry stream IS the zip stream, and closing it
                            // would end the whole archive rather than the current entry.
                            if (writeEntry(destDir, root, entry, zip, policy, onProgress)) {
                                written++
                            }
                            zip.closeEntry()
                        }
                    }
                }
            }
            return written
        }

        ZipFile(archive).use { zip ->
            for (entry in zip.entries()) {
                currentCoroutineContext().ensureActive()
                if (entry.isDirectory) {
                    if (writeEntry(destDir, root, entry, null, policy, onProgress)) written++
                    continue
                }
                zip.getInputStream(entry).use { input ->
                    if (writeEntry(destDir, root, entry, input, policy, onProgress)) written++
                }
            }
        }
        return written
    }

    /**
     * Writes one archive entry, or refuses it. Returns true if a file was actually created.
     * [input] may be null only for directory entries. The caller owns the stream's lifetime.
     */
    // usableSpace, not StorageManager.getAllocatableBytes as lint suggests. getAllocatableBytes
    // reports what we could obtain *after* the system evicts other apps' cached data on our
    // behalf, which is the opposite of what a zip-bomb ceiling wants: it would let a malicious
    // archive keep extracting by clearing everyone else's caches first. The question here is
    // "is the device genuinely nearly full", and usableSpace is that number.
    @SuppressLint("UsableSpace")
    private suspend fun writeEntry(
        destDir: File,
        root: File,
        entry: ZipEntry,
        input: InputStream?,
        policy: ConflictPolicy,
        onProgress: suspend (deltaBytes: Long, currentName: String) -> Unit,
    ): Boolean {
        val target = File(destDir, entry.name).canonicalFile

        /*
         * Zip bombs. A few hundred KB of archive can declare terabytes of output, and nothing
         * above bounds what gets written — the Zip Slip check constrains *where* an entry lands,
         * not how much of it there is. Bounding by the volume's own free space rather than a
         * compression ratio catches the actual failure mode (filling the user's storage) without
         * guessing at a threshold, and costs one statfs per entry.
         */
        if (root.usableSpace in 1 until MIN_FREE_BYTES) {
            throw IOException("Not enough free space to finish extracting")
        }

        /*
         * Zip Slip. An entry named "../../../data/data/com.jegly.files/x" resolves outside
         * destDir, and this process holds MANAGE_EXTERNAL_STORAGE — so an unchecked extract turns
         * any downloaded archive into an arbitrary-write primitive across the whole of shared
         * storage. Compare canonical paths, since the traversal may also come through a symlink
         * already present in the tree.
         */
        if (target != root && !target.path.startsWith(root.path + File.separator)) {
            throw IOException("Unsafe path in archive: ${entry.name}")
        }

        if (entry.isDirectory) {
            target.mkdirs()
            return false
        }

        if (target.exists() && policy == ConflictPolicy.Skip) return false
        target.parentFile?.mkdirs()
        val source = input ?: throw IOException("No data for ${entry.name}")

        try {
            target.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    onProgress(read.toLong(), entry.name.substringAfterLast('/'))
                }
                output.flush()
            }
            if (entry.time > 0) target.setLastModified(entry.time)
            return true
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
    }

    /** Returns bytes freed. */
    private fun deleteRecursively(f: File): Long {
        var freed = 0L
        if (f.isWalkableDirectory()) {
            f.listFiles()?.forEach { freed += deleteRecursively(it) }
        } else if (!f.isDirectory) {
            // A symlink to a directory falls here and is simply unlinked below, which is what
            // deleting the link should do — its target is somebody else's data.
            freed += f.length()
            // Overwritten before it is unlinked — see [SecureErase]. A symlink never is: writing
            // "through" one destroys the file it points at, which is not the file being deleted.
            if (!f.isSymlink()) SecureErase.overwrite(f)
        }
        if (!f.delete() && f.exists()) throw IOException("Couldn't delete ${f.name}")
        return freed
    }


    private companion object {
        const val DEFAULT_BUFFER = 256 * 1024

        /** Headroom left on the destination volume before an extract gives up. */
        const val MIN_FREE_BYTES = 64L * 1024 * 1024
    }
}
