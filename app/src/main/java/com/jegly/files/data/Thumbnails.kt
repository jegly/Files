package com.jegly.files.data

import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.jegly.files.model.FileEntry
import com.jegly.files.model.FileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thumbnail decoding with no image-loading library and, deliberately, no network stack.
 *
 * ThumbnailUtils reads the embedded EXIF/container thumbnail where one exists, so this is
 * far cheaper than decoding the full bitmap and downsampling.
 */
object Thumbnails {

    /**
     * Sized as a fraction of this process's actual heap, not a fixed number.
     *
     * A flat 24MB was a bet that every device would tolerate it. Android 17 enforces per-app
     * memory limits derived from device RAM and kills apps that exceed them, so on a low-RAM
     * device a fixed cache is a slow walk toward being killed mid-copy. An eighth of max heap
     * is the conventional bitmap-cache fraction, clamped so the cache stays useful on small
     * devices and doesn't balloon on large ones.
     */
    private val CACHE_BYTES: Int = (Runtime.getRuntime().maxMemory() / 8)
        .coerceIn(4L * 1024 * 1024, 32L * 1024 * 1024)
        .toInt()

    private val cache = object : LruCache<String, ImageBitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    private val SIZE = Size(256, 256)

    fun cached(entry: FileEntry): ImageBitmap? = cache[entry.cacheKey()]

    suspend fun load(entry: FileEntry): ImageBitmap? {
        val key = entry.cacheKey()
        cache[key]?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                when (entry.kind) {
                    FileKind.Image -> ThumbnailUtils.createImageThumbnail(entry.file, SIZE, null)
                    FileKind.Video -> ThumbnailUtils.createVideoThumbnail(entry.file, SIZE, null)
                    else -> null
                }
            }.getOrNull()
        } ?: return null
        val image = bitmap.toImageBitmapSafely() ?: return null
        cache.put(key, image)
        return image
    }

    fun evictAll() = cache.evictAll()

    /** Path alone is not enough — a replaced file at the same path must invalidate. */
    private fun FileEntry.cacheKey() = "$path:$lastModified:$size"

    private fun Bitmap.toImageBitmapSafely(): ImageBitmap? = runCatching {
        if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false).asImageBitmap()
        else asImageBitmap()
    }.getOrNull()
}
