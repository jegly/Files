package com.jegly.files.data

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.jegly.files.model.FileEntry
import com.jegly.files.model.FileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The one-line media summary DocumentsUI's inspector shows: pixel dimensions for images,
 * duration (and resolution) for audio and video.
 *
 * Images go through BitmapFactory with inJustDecodeBounds, which parses the header and stops —
 * no pixel data is ever allocated, so this is cheap even on a 50MP photo. Video and audio need
 * MediaMetadataRetriever, which is heavier and must be released explicitly.
 */
object MediaInfo {

    /** Null when the file isn't media, or when nothing could be read out of it. */
    suspend fun describe(entry: FileEntry): String? = withContext(Dispatchers.IO) {
        when (entry.kind) {
            FileKind.Image -> imageSummary(entry)
            FileKind.Video, FileKind.Audio -> mediaSummary(entry)
            else -> null
        }
    }

    private fun imageSummary(entry: FileEntry): String? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(entry.path, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return "${options.outWidth} × ${options.outHeight}"
    }

    private fun mediaSummary(entry: FileEntry): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(entry.path)
            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.let(::formatDuration)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val resolution = if (width != null && height != null) "$width × $height" else null
            listOfNotNull(resolution, duration).joinToString(" · ").ifEmpty { null }
        } catch (t: Throwable) {
            // A truncated download or a codec this device has no parser for; not worth surfacing.
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun formatDuration(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }
}
