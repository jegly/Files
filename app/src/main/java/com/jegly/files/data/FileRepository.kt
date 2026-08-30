package com.jegly.files.data

import android.os.Environment
import com.jegly.files.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Direct filesystem access, gated by MANAGE_EXTERNAL_STORAGE.
 *
 * Everything here is suspending and pinned to Dispatchers.IO — a directory listing on a
 * cold page cache with a few thousand entries is comfortably long enough to drop frames
 * if you let it run on the main thread.
 */
class FileRepository {

    suspend fun list(dir: File): Result<List<FileEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            if (!dir.exists()) error("${dir.name} no longer exists")
            if (!dir.isDirectory) error("${dir.name} is not a folder")
            val children = dir.listFiles() ?: error("Can't read ${dir.name}")
            children.map(FileEntry::from)
        }
    }

    /**
     * Recursive search under [root]. Emits nothing until done; for very large trees prefer
     * wiring this to a Flow and streaming partial results.
     */
    suspend fun search(root: File, query: String, limit: Int = 500): List<FileEntry> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val needle = query.trim().lowercase()
            val out = ArrayList<FileEntry>()
            val stack = ArrayDeque<File>().apply { add(root) }
            while (stack.isNotEmpty() && out.size < limit) {
                // Typing another character cancels the previous search job; without this the
                // abandoned walk keeps churning the disk behind the one the user is waiting on.
                currentCoroutineContext().ensureActive()
                val current = stack.removeLast()
                val children = current.listFiles() ?: continue
                for (child in children) {
                    if (child.name.lowercase().contains(needle)) out += FileEntry.from(child)
                    if (child.isDirectory) stack.addLast(child)
                    if (out.size >= limit) break
                }
            }
            out
        }

    /** Recursive size and contents of a tree, for the properties sheet. */
    data class TreeStats(val bytes: Long, val files: Int, val folders: Int)

    /**
     * Walks the whole tree, so it can take seconds on something like /sdcard. Callers should
     * treat the result as arriving late rather than blocking their UI on it. Checks for
     * cancellation each level down so dismissing the sheet actually stops the walk.
     */
    suspend fun treeStats(f: File): TreeStats = withContext(Dispatchers.IO) {
        if (!f.isDirectory) return@withContext TreeStats(f.length(), files = 1, folders = 0)
        var bytes = 0L
        var files = 0
        var folders = 0
        val stack = ArrayDeque<File>().apply { add(f) }
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val children = stack.removeLast().listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    folders++
                    stack.addLast(child)
                } else {
                    files++
                    bytes += child.length()
                }
            }
        }
        TreeStats(bytes, files, folders)
    }

    suspend fun createFolder(parent: File, name: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = File(parent, name.sanitizedName())
                if (target.exists()) error("\"$name\" already exists")
                if (!target.mkdir()) error("Couldn't create \"$name\"")
                target
            }
        }

    suspend fun rename(entry: FileEntry, newName: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val src = entry.file
                val target = File(src.parentFile, newName.sanitizedName())
                if (target.exists()) error("\"$newName\" already exists")
                if (!src.renameTo(target)) error("Couldn't rename \"${entry.name}\"")
                target
            }
        }

    companion object {
        val primaryStorage: File get() = Environment.getExternalStorageDirectory()

        /**
         * Reject path separators and traversal segments. A file manager is exactly the app
         * where an unsanitised name turns a rename into an arbitrary-write primitive.
         */
        fun String.sanitizedName(): String {
            val cleaned = trim().replace('/', '_').replace('\u0000', '_')
            require(cleaned.isNotEmpty()) { "Name can't be empty" }
            require(cleaned != "." && cleaned != "..") { "Invalid name" }
            return cleaned
        }
    }
}
