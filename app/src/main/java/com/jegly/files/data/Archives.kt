package com.jegly.files.data

import android.content.Context
import com.jegly.files.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * Reads a zip as if it were a directory tree, so archives can be browsed in place rather than
 * only extracted wholesale.
 *
 * AOSP does this with an ArchivesProvider — a real DocumentsProvider that publishes archive
 * contents as documents. That route is closed to us (it needs MANAGE_DOCUMENTS to be useful),
 * so this synthesises the same experience directly: entries inside an archive are represented as
 * ordinary [FileEntry] values carrying a composite path, `/real/path/to.zip!/inner/path`, using
 * the `!/` separator from the JAR URL convention. That keeps them unique and stable as list keys
 * without needing a parallel entry type threaded through the whole UI.
 *
 * The catch, and the reason mutation is blocked while inside an archive: a [FileEntry] built this
 * way has a `path` that is not a real filesystem path, so its `file` property is meaningless.
 * Everything that would touch the filesystem has to be gated on "are we inside an archive" —
 * see BrowserViewModel.
 */
object Archives {

    /** Separator between the archive's real path and a path inside it. */
    const val SEPARATOR = "!/"

    fun syntheticPath(archive: File, entryPath: String): String =
        "${archive.absolutePath}$SEPARATOR$entryPath"

    /** The path inside the archive for an entry produced by [list]. */
    fun innerPath(entry: FileEntry): String = entry.path.substringAfter(SEPARATOR, "")

    /**
     * Ported from AOSP's Archive.getEntryPath() (packages/apps/DocumentsUI,
     * src/com/android/documentsui/archives/Archive.java): decompose the entry name, collapse
     * repeated separators, drop "." segments and pop the parent on "..".
     *
     * Zip entry names are arbitrary attacker-controlled strings, not paths the format validates.
     * Without this, an archive containing "docs/../../evil.txt" renders a directory literally
     * named ".." that you can walk into, and a listing full of segments that don't correspond to
     * anything. Not a security hole in this app — both write paths are already bounded, by the
     * canonical-path check in FileOperations.extract and by extractToCache using the leaf name
     * only — but visibly wrong, which is what §4b of the handoff called out.
     *
     * Backslashes are folded first: some Windows zip writers emit them as separators, and a name
     * like "docs\report.pdf" would otherwise be one flat filename containing a backslash.
     *
     * AOSP additionally substitutes "?" as a filename when a decomposed path looks like a
     * directory but the entry claims to be a file. That exists to keep their document IDs unique
     * and has no analogue here — our synthetic entries carry isDirectory on the FileEntry itself.
     */
    fun normalizeEntryName(raw: String): String {
        val segments = ArrayList<String>()
        for (segment in raw.replace('\\', '/').split('/')) {
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> segments.removeLastOrNull()
                else -> segments.add(segment)
            }
        }
        return segments.joinToString("/")
    }

    /**
     * Direct children of [inside] within [archive]. Pass "" for the archive's root.
     *
     * Zips are a flat list of entries, not a tree, and directory entries are optional — plenty of
     * archives contain `docs/report.pdf` with no `docs/` entry at all. Implicit parents are
     * therefore synthesised from the paths of their children, which is why a directory can appear
     * here that has no corresponding entry in the file.
     */
    suspend fun list(archive: File, inside: String): Result<List<FileEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val prefix = if (inside.isEmpty()) "" else "$inside/"
                val children = LinkedHashMap<String, FileEntry>()

                ZipFile(archive).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        currentCoroutineContext().ensureActive()
                        val zipEntry = entries.nextElement()
                        val full = normalizeEntryName(zipEntry.name)
                        if (!full.startsWith(prefix)) continue

                        val rest = full.removePrefix(prefix).trim('/')
                        if (rest.isEmpty()) continue

                        val slash = rest.indexOf('/')
                        val stamp = zipEntry.time.takeIf { it > 0L } ?: archive.lastModified()

                        if (slash >= 0) {
                            // A descendant, so its first segment is an implicit directory here.
                            val dirName = rest.substring(0, slash)
                            children.getOrPut(dirName) {
                                entryOf(archive, prefix + dirName, dirName, true, 0L, stamp)
                            }
                        } else {
                            val isDirectory = zipEntry.isDirectory
                            children[rest] = entryOf(
                                archive = archive,
                                entryPath = prefix + rest,
                                name = rest,
                                isDirectory = isDirectory,
                                size = if (isDirectory) 0L else zipEntry.size.coerceAtLeast(0L),
                                lastModified = stamp,
                            )
                        }
                    }
                }

                children.values.toList()
            }
        }

    /**
     * Unpacks one entry to the cache so it can be handed to another app for viewing.
     *
     * Output is named from the entry's leaf only and written into a directory keyed by the
     * archive and entry, so a hostile path inside the zip cannot escape the cache — the Zip Slip
     * guard in FileOperations.extract exists for the same reason on the write side.
     */
    suspend fun extractToCache(context: Context, archive: File, entryPath: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val leaf = entryPath.substringAfterLast('/')
                require(leaf.isNotEmpty()) { "That isn't a file" }
                // substringAfterLast('/') can't contain a separator, so the leaf can't traverse
                // more than one level — but "." and ".." still resolve to the bucket and its
                // parent. Both are existing directories, so opening them for writing would throw
                // anyway; rejecting them outright makes that safety deliberate instead of
                // incidental on a filesystem quirk.
                require(leaf != "." && leaf != "..") { "Unsafe entry name" }

                // The archive's own identity is part of the key, not just its path: replacing a
                // zip with a different one at the same path previously served the old contents
                // out of the cache forever, since the reuse check below only asks whether some
                // non-empty file is already sitting there.
                val bucket = File(
                    File(context.cacheDir, CACHE_DIR),
                    buildString {
                        append(archive.absolutePath.hashCode())
                        append('_').append(archive.lastModified())
                        append('_').append(archive.length())
                        append('_').append(entryPath.hashCode())
                    },
                )
                if (!bucket.exists() && !bucket.mkdirs()) error("Couldn't prepare the cache")

                val out = File(bucket, leaf)
                // Re-extracting an unchanged entry every tap would be wasteful; the bucket name
                // already encodes which entry of which archive this is.
                if (out.isFile && out.length() > 0L) return@runCatching out

                ZipFile(archive).use { zip ->
                    val entry = findEntry(zip, entryPath) ?: error("Not found in this archive")
                    zip.getInputStream(entry).use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                out
            }
        }

    /**
     * Resolves a normalised path from [list] back to the real entry it came from.
     *
     * The fast path is the direct lookup, which hits whenever the archive's own name for the
     * entry was already normal. It misses for anything [normalizeEntryName] rewrote — a Windows
     * writer's "docs\report.pdf", a "./" prefix, a doubled separator — and before this fallback
     * existed those files listed correctly and then failed to open with "Not found in this
     * archive", because the name being looked up was the rewritten one. The scan costs a pass
     * over the central directory and only ever runs in that case.
     */
    private fun findEntry(zip: ZipFile, entryPath: String): java.util.zip.ZipEntry? {
        zip.getEntry(entryPath)?.let { return it }
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val candidate = entries.nextElement()
            if (normalizeEntryName(candidate.name) == entryPath) return candidate
        }
        return null
    }

    /** Drops everything unpacked for previewing. Safe at any time; these are all re-creatable. */
    fun clearCache(context: Context) {
        runCatching { File(context.cacheDir, CACHE_DIR).deleteRecursively() }
    }

    private const val CACHE_DIR = "archive-preview"

    private fun entryOf(
        archive: File,
        entryPath: String,
        name: String,
        isDirectory: Boolean,
        size: Long,
        lastModified: Long,
    ) = FileEntry(
        path = syntheticPath(archive, entryPath),
        name = name,
        isDirectory = isDirectory,
        size = size,
        lastModified = lastModified,
        isHidden = name.startsWith('.'),
        // Counting a directory's children means a second pass over every entry in the archive;
        // the list already renders "—" for an unknown count.
        childCount = -1,
    )
}
