package com.jegly.files.model

import android.webkit.MimeTypeMap
import com.jegly.files.security.ArchiveCrypto
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/** An immutable snapshot of one filesystem entry. Cheap to diff, safe to hold in UI state. */
data class FileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val isHidden: Boolean,
    /** Child count for directories, -1 when unknown/unreadable. */
    val childCount: Int = -1,
) {
    val file: File get() = File(path)

    val extension: String
        get() = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase()

    val mimeType: String
        get() = if (isDirectory) MIME_DIR
        else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: MIME_UNKNOWN

    /**
     * Archives this app can actually open, which is narrower than [FileKind.Archive]: that set
     * includes rar and 7z for iconography, but only zip containers are extractable without
     * pulling in a third-party decoder.
     *
     * Deliberately excludes sealed archives. This flag also drives "walk into it like a folder",
     * and a sealed archive cannot be listed without first asking for a password.
     */
    val isExtractableArchive: Boolean
        get() = !isDirectory && extension in EXTRACTABLE_EXTS

    /** A password-protected container produced by this app. Needs a key before anything else. */
    val isSealedArchive: Boolean
        get() = !isDirectory && extension == ArchiveCrypto.EXTENSION

    /** Anything the Extract action can act on, with a password where one is required. */
    val canExtract: Boolean get() = isExtractableArchive || isSealedArchive

    val kind: FileKind
        get() = when {
            isDirectory -> FileKind.Directory
            mimeType.startsWith("image/") -> FileKind.Image
            mimeType.startsWith("video/") -> FileKind.Video
            mimeType.startsWith("audio/") -> FileKind.Audio
            extension == ArchiveCrypto.EXTENSION -> FileKind.Sealed
            extension in ARCHIVE_EXTS -> FileKind.Archive
            mimeType.startsWith("text/") || extension in CODE_EXTS -> FileKind.Text
            mimeType == "application/pdf" -> FileKind.Document
            extension == "apk" -> FileKind.Apk
            else -> FileKind.Other
        }

    companion object {
        const val MIME_DIR = "resource/folder"
        const val MIME_UNKNOWN = "application/octet-stream"

        private val ARCHIVE_EXTS =
            setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz", "zst", "tgz", "jar")

        /** Zip containers — everything java.util.zip can read without help. */
        private val EXTRACTABLE_EXTS = setOf("zip", "jar", "apk")
        private val CODE_EXTS =
            setOf("kt", "java", "c", "h", "cpp", "py", "rs", "go", "sh", "json", "xml", "md", "toml", "yaml", "yml")

        fun from(f: File): FileEntry {
            // One readAttributes rather than isDirectory + length + lastModified, which are
            // three separate stat(2) calls against the same inode. A listing is N of these and
            // shared storage is FUSE-backed, where a syscall is far from free, so collapsing
            // them is the largest available win on opening a folder. Attributes are read
            // following links, exactly as File's own accessors do, so a symlinked directory
            // still displays as a directory. Falls back to File on anything unreadable or on a
            // path java.nio rejects, which is where the old code's nulls and zeroes came from.
            val attrs = runCatching {
                Files.readAttributes(f.toPath(), BasicFileAttributes::class.java)
            }.getOrNull()
            val dir = attrs?.isDirectory ?: f.isDirectory
            return FileEntry(
                path = f.absolutePath,
                name = f.name,
                isDirectory = dir,
                size = if (dir) 0L else attrs?.size() ?: f.length(),
                lastModified = attrs?.lastModifiedTime()?.toMillis() ?: f.lastModified(),
                isHidden = f.name.startsWith('.'),
                childCount = if (dir) countChildren(f) else -1,
            )
        }

        /**
         * The "N items" subtitle, which costs one directory read per subdirectory — unavoidable
         * while the subtitle exists at all, since there is no cheaper way to ask how many
         * children a directory has.
         *
         * Streamed rather than `File.list()`, which materialises a String array of every child
         * name only to read its `size`. A folder of subfolders each holding thousands of files
         * allocates and discards all of them; this reads the same directory entries and keeps
         * none. -1 on anything unreadable, matching what the array form returned as null.
         */
        private fun countChildren(dir: File): Int = runCatching {
            Files.newDirectoryStream(dir.toPath()).use { stream ->
                var n = 0
                for (ignored in stream) n++
                n
            }
        }.getOrDefault(-1)
    }
}

enum class FileKind { Directory, Image, Video, Audio, Archive, Sealed, Text, Document, Apk, Other }
