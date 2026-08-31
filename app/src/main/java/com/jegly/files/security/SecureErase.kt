package com.jegly.files.security

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.SecureRandom

/**
 * Overwriting a file's bytes where they lie, before [com.jegly.files.ops.FileOperations] unlinks
 * them. Not optional and not a setting: "delete" meaning "unlink and leave the contents sitting
 * in free space" is the surprising behaviour, not this one.
 *
 * WHAT IT DOES AND DOES NOT PROMISE. One pass of random bytes, written through and fsynced, over
 * the blocks the filesystem currently maps for the file. On a spinning disk or an unencrypted,
 * directly-addressed medium that is erasure. On flash — which is every phone, every SD card,
 * every USB stick — it is not: the controller wear-levels, so the write may land on a fresh page
 * and leave the original intact but unmapped until it is recycled. It raises the cost of recovery
 * from "undelete utility" to "chip-off forensics", which is worth the one extra write pass; it is
 * not erasure, and nothing in the UI claims it is.
 */
object SecureErase {

    private const val BUFFER = 256 * 1024

    /** Shared: seeding a SecureRandom per file would cost more than the writes it feeds. */
    private val random = SecureRandom()

    /**
     * Overwrites every byte [file] currently occupies, leaving it in place. Unlinking it is the
     * caller's next move.
     *
     * "rwd" plus an explicit fsync, because a write sitting in the page cache has overwritten
     * nothing: unlinking immediately afterwards leaves the kernel free to drop the dirty pages
     * without ever putting them on the medium.
     *
     * Never call this on a symlink. Writing "through" one destroys the file it points at, which
     * is somebody else's data and not the file being deleted.
     */
    fun overwrite(file: File) {
        val length = file.length()
        if (length <= 0L) return
        try {
            // Filled once and reused: a second pass of *different* random bytes buys nothing
            // against any medium a first pass did not already deal with, and drawing gigabytes
            // out of SecureRandom would dominate the cost of the delete.
            val buffer = ByteArray(BUFFER).also(random::nextBytes)
            RandomAccessFile(file, "rwd").use { raf ->
                var remaining = length
                while (remaining > 0L) {
                    val chunk = minOf(buffer.size.toLong(), remaining).toInt()
                    raf.write(buffer, 0, chunk)
                    remaining -= chunk
                }
                raf.fd.sync()
            }
        } catch (t: IOException) {
            throw IOException("Couldn't overwrite ${file.name} before deleting it")
        }
    }
}
