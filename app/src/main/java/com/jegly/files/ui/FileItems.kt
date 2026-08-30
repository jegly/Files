package com.jegly.files.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jegly.files.data.Thumbnails
import com.jegly.files.model.FileEntry
import com.jegly.files.model.FileKind

@Composable
fun FileRow(
    entry: FileEntry,
    selected: Boolean,
    selectionMode: Boolean,
    thumbnails: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Compose's combinedClickable does not fire a haptic on long-press the way the platform's
    // own View long-press always has — without this, starting a selection is silent and the
    // whole gesture reads as unresponsive/broken rather than as "you just did something".
    val haptics = LocalHapticFeedback.current
    ListItem(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            ),
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface,
        ),
        leadingContent = {
            EntryAvatar(
                entry = entry,
                selected = selected,
                selectionMode = selectionMode,
                thumbnails = thumbnails,
            )
        },
        headlineContent = {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        },
        supportingContent = {
            val meta = remember(entry) {
                val when_ = DateUtils.getRelativeTimeSpanString(entry.lastModified)
                if (entry.isDirectory) {
                    val n = entry.childCount
                    if (n < 0) "$when_" else "$n item${if (n == 1) "" else "s"} · $when_"
                } else {
                    "${Formatter.formatShortFileSize(context, entry.size)} · $when_"
                }
            }
            Text(meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
    )
}

/**
 * Matches AOSP's actual grid cells, checked directly against a screenshot rather than guessed:
 * a thin-bordered rounded card holding a compact icon-then-label row, not a big centred
 * thumbnail with a caption underneath. Grid mode there buys you two columns of these compact
 * rows, not a photo-grid — DocumentsUI is a document browser first, not a gallery app.
 */
@Composable
fun FileGridCell(
    entry: FileEntry,
    selected: Boolean,
    thumbnails: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Surface(
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongClick()
            },
        ),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                val thumb = if (selected) null else rememberThumbnail(entry, thumbnails)
                when {
                    selected -> Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                    )

                    thumb != null -> Image(
                        bitmap = thumb,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)),
                    )

                    else -> Icon(
                        entry.kind.icon(),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun EntryAvatar(
    entry: FileEntry,
    selected: Boolean,
    selectionMode: Boolean,
    thumbnails: Boolean,
) {
    // Plain icon, no circular chip behind it — AOSP's own rows don't put one there either,
    // and it was pure decoration this app invented rather than anything DocumentsUI does.
    // Selection still swaps the icon to a checkmark, just without a coloured backing shape.
    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        val thumb = if (selected) null else rememberThumbnail(entry, thumbnails)
        when {
            selected -> Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
            )

            thumb != null -> Image(
                bitmap = thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
            )

            else -> Icon(
                entry.kind.icon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * [enabled] is false anywhere the listing is synthetic, because a thumbnail cannot be produced
 * there and trying costs an IO dispatch per visible image or video:
 *
 * - inside an archive an entry's `path` is the composite `/real/to.zip!/inner` form, which
 *   resolves to nothing on disk;
 * - inside a vault it resolves to the ciphertext blob, which decodes to nothing.
 *
 * Both merely fail and render the kind icon, so this is latency and wasted IO rather than a
 * visible bug — but it is paid on every scroll through a folder of photos.
 */
@Composable
private fun rememberThumbnail(entry: FileEntry, enabled: Boolean): ImageBitmap? {
    if (!enabled) return null
    if (entry.kind != FileKind.Image && entry.kind != FileKind.Video) return null
    var bitmap by remember(entry.path) { mutableStateOf(Thumbnails.cached(entry)) }
    LaunchedEffect(entry.path, entry.lastModified) {
        if (bitmap == null) bitmap = Thumbnails.load(entry)
    }
    return bitmap
}

private fun FileKind.icon(): ImageVector = when (this) {
    FileKind.Directory -> Icons.Rounded.Folder
    FileKind.Image -> Icons.Rounded.Image
    FileKind.Video -> Icons.Rounded.Videocam
    FileKind.Audio -> Icons.Rounded.MusicNote
    FileKind.Archive -> Icons.Rounded.Archive
    // A distinct icon rather than the archive one: whether a file is readable without a password
    // is the single most useful thing to know at a glance in a listing.
    FileKind.Sealed -> Icons.Rounded.Lock
    FileKind.Text -> Icons.Rounded.Description
    FileKind.Document -> Icons.Rounded.PictureAsPdf
    FileKind.Apk -> Icons.Rounded.Android
    FileKind.Other -> Icons.AutoMirrored.Rounded.InsertDriveFile
}
