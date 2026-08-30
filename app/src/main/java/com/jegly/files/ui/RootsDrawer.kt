package com.jegly.files.ui

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jegly.files.data.StorageRoot

/**
 * com.android.documentsui's roots list (fragment_roots.xml), minus provider federation.
 *
 * DocumentsUI lists every DocumentsProvider root, which on a Play device includes Drive and any
 * other cloud backend. This app has no INTERNET permission, so there is nothing to federate —
 * what's left is the set of physically mounted volumes, which is exactly what this shows.
 *
 * Lives in a real ModalNavigationDrawer (AppRoot owns the DrawerState) rather than a bottom
 * sheet, matching AOSP's own persistent side-drawer rather than inventing a different pattern.
 */
@Composable
fun RootsDrawerContent(
    roots: List<StorageRoot>,
    currentRoot: String,
    onPick: (StorageRoot) -> Unit,
) {
    val context = LocalContext.current

    ModalDrawerSheet {
        Column(Modifier.fillMaxHeight().systemBarsPadding()) {
            Text(
                "Files",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(24.dp),
            )
            HorizontalDivider()

            Text(
                "Storage",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            roots.forEach { root ->
                val selected = root.path.absolutePath == currentRoot
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(root) }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(
                        if (root.isRemovable) Icons.Rounded.SdCard else Icons.Rounded.Smartphone,
                        contentDescription = null,
                        tint = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            root.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        // totalSpace reads 0 on volumes we can stat but not measure; showing
                        // "0 B free of 0 B" would be worse than showing nothing.
                        if (root.totalBytes > 0L) {
                            Text(
                                "${Formatter.formatShortFileSize(context, root.freeBytes)} free " +
                                    "of ${Formatter.formatShortFileSize(context, root.totalBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            LinearProgressIndicator(
                                progress = { root.usedFraction },
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))
        }
    }
}
