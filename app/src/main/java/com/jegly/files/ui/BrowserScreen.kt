package com.jegly.files.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jegly.files.data.AppSettings
import com.jegly.files.data.StorageVolumes
import com.jegly.files.model.FileEntry
import com.jegly.files.model.PickRequest
import com.jegly.files.model.ViewMode
import com.jegly.files.ops.OpKind
import com.jegly.files.ops.OpProgress
import com.jegly.files.ops.Opener
import com.jegly.files.security.AdvancedProtectionGate
import com.jegly.files.security.Vault
import com.jegly.files.security.VaultSession
import com.jegly.files.vm.BrowserViewModel
import com.jegly.files.vm.Crumb
import kotlinx.coroutines.delay
import java.io.File

/**
 * Modelled on com.android.documentsui's actual chrome, not a from-scratch design: a roots
 * drawer opened from a persistent hamburger icon rather than a bottom sheet, an always-visible
 * search icon with everything else behind a single overflow menu, and — checked directly
 * against AOSP's action_mode_menu.xml — a selection bar with only Share and Delete always
 * visible, every other action (rename, copy, move, compress, extract, properties, select all)
 * one tap into overflow. No FAB anywhere; AOSP's own toolbar doesn't have one, and New Folder /
 * Paste live in the overflow menu the same way AOSP's option_menu_create_dir does.
 */
@Composable
fun BrowserScreen(
    vm: BrowserViewModel,
    onOpenDrawer: () -> Unit,
    /** Null hides the Settings action entirely — see AppRoot. */
    onOpenSettings: (() -> Unit)?,
    pick: PickRequest? = null,
    onPicked: (List<FileEntry>) -> Unit = {},
    onDestinationChosen: (File) -> Unit = {},
    /**
     * False while chrome owned by the host should receive Back instead — the roots drawer being
     * open, today. Compose dispatches back callbacks last-registered-first, and this screen is
     * composed inside the drawer, so an unconditionally-enabled handler here silently shadows
     * the drawer's own and Back navigates directories behind an open drawer.
     */
    backEnabled: Boolean = true,
    /** Nothing left to unwind: the directory stack, selection and search are all exhausted. */
    onBackExhausted: () -> Unit = {},
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val operation by vm.operation.collectAsStateWithLifecycle()
    val properties by vm.properties.collectAsStateWithLifecycle()
    val roots by vm.roots.collectAsStateWithLifecycle()
    val pastePrompt by vm.pastePrompt.collectAsStateWithLifecycle()
    val passwordPrompt by vm.passwordPrompt.collectAsStateWithLifecycle()
    val vaultPrompt by vm.vaultPrompt.collectAsStateWithLifecycle()
    val protection = remember { AdvancedProtectionGate.get(context) }
    val advancedProtection by protection.enabled.collectAsStateWithLifecycle()
    val settings = remember { AppSettings.get(context) }
    val itemScale by settings.itemScale.collectAsStateWithLifecycle()

    var searchOpen by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<FileEntry?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var compressing by remember { mutableStateOf(false) }
    var newVault by remember { mutableStateOf(false) }

    /*
     * primaryStorage's raw folder name is the literal path segment "0" — Android's multi-user
     * primary storage lives at /storage/emulated/<userId>, and userId 0 is the primary user.
     * File.name has no idea that's not a meaningful label, so the root breadcrumb and title were
     * showing "0" verbatim. Real AOSP shows the volume's actual description there instead — this
     * does the same, falling back to the raw name only for a volume StorageManager didn't
     * describe (shouldn't normally happen, but a label beats a blank title).
     */
    fun displayName(dir: File): String =
        if (dir.absolutePath == state.volumeRoot.absolutePath) {
            roots.firstOrNull { it.path.absolutePath == dir.absolutePath }?.label
                ?: dir.name.ifEmpty { "Storage" }
        } else {
            dir.name
        }

    // The lone selected entry, when it's an archive we can actually open. Sealed archives count:
    // vm.extract asks for the password itself rather than the menu having to know.
    val extractTarget = state.entries
        .singleOrNull { it.path in state.selection && state.selection.size == 1 }
        ?.takeIf { it.canExtract }

    /*
     * In pick mode, files the caller can't accept are hidden rather than shown-and-refused.
     * Folders always survive the filter — an image picker still has to let you walk through
     * Documents to reach the folder the image is in.
     */
    val visible = remember(state.entries, pick) {
        if (pick == null) state.entries else state.entries.filter(pick::accepts)
    }

    // Two triggers, because neither alone is sufficient. The volume callback catches a USB drive
    // plugged in while the user is watching; the resume hook catches cards swapped while we were
    // backgrounded, and re-reads free space after a copy finished in the foreground service. The
    // roots list itself is read by AppRoot for the drawer — this just keeps it fresh.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        /*
         * Vaults close when the app leaves the foreground — but "this screen stopped" is not
         * that. Copy to… opens a second Activity of this same app, which stops this one, and
         * locking there closed the vault the user had just unlocked mid-copy. So report starts
         * and stops and let VaultSession decide: it locks once nothing is started, after the
         * timeout from Settings, and a hand-off between our own screens never reaches zero.
         *
         * `counted` keeps the pair balanced. Adding an observer to an already-started lifecycle
         * replays ON_START immediately, and leaving the composition never delivers the matching
         * ON_STOP, so an unguarded pair would drift the count upward and vaults would then stay
         * open forever.
         */
        var counted = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!counted) {
                    counted = true
                    VaultSession.noteForeground()
                }

                Lifecycle.Event.ON_RESUME -> vm.refreshRoots()

                Lifecycle.Event.ON_STOP -> if (counted) {
                    counted = false
                    // Read now, not captured: the user may have changed it since this screen
                    // was composed.
                    VaultSession.noteBackground(settings.vaultLockTimeoutMs)
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        val volumes = StorageVolumes.observe(context) { vm.refreshRoots() }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (counted) {
                counted = false
                VaultSession.noteBackground(settings.vaultLockTimeoutMs)
            }
            volumes?.close()
        }
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.dismissMessage()
        }
    }

    // A file tapped inside an archive has been unpacked to the cache and is now a real file, so
    // it can go to a viewer through the normal chooser path.
    val preview by vm.pendingPreview.collectAsStateWithLifecycle()
    LaunchedEffect(preview) {
        preview?.let {
            vm.report(Opener.open(context, FileEntry.from(it)))
            vm.consumePreview()
        }
    }

    // Directory history first, then the Activity. Selection and search unwind before either.
    // navigateBack() returns false once there is nowhere left to go; ignoring that return value
    // left Back inert at a volume root — no way out of the app but Home, and no way for a picker
    // to be cancelled, so the calling app never received its RESULT_CANCELED.
    BackHandler(enabled = backEnabled) {
        if (searchOpen && state.query.isEmpty()) { searchOpen = false; return@BackHandler }
        if (!vm.navigateBack()) onBackExhausted()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            // Checked against AOSP's own BaseActivity/DirectoryFragment rather than assumed:
            // the folder name is a plain Toolbar.setTitle() at the platform's standard Title
            // text appearance (~20sp, single line) — there's no large expanding headline for
            // it anywhere in DocumentsUI. MediumTopAppBar's whole premise is a big collapsing
            // title, which is a different, unrelated component to what AOSP actually does here,
            // not just a differently-sized version of it — hence the plain TopAppBar.
            TopAppBar(
                // The system's own contextual action bar always tints its background — that
                // colour shift is most of what tells you "you're in a different mode now"; ours
                // was leaving the bar exactly as-is and swapping only the icons, which reads as
                // a glitch rather than a deliberate mode change.
                colors = if (state.inSelectionMode) {
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        actionIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                } else {
                    TopAppBarDefaults.topAppBarColors()
                },
                navigationIcon = {
                    // Selection turns the top bar into a contextual bar rather than stacking a
                    // second bar over it, so the list never shifts down when you long-press.
                    when {
                        state.inSelectionMode -> IconButton(onClick = vm::clearSelection) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear selection")
                        }

                        searchOpen -> IconButton(
                            onClick = { searchOpen = false; vm.setQuery("") },
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Close search",
                            )
                        }

                        // Up-navigation is via breadcrumbs or the system back gesture, matching
                        // AOSP — there's no dedicated Up affordance in its toolbar either.
                        else -> IconButton(onClick = onOpenDrawer) {
                            Icon(Icons.Rounded.Menu, contentDescription = "Open storage")
                        }
                    }
                },
                title = {
                    when {
                        // Same size as the folder title, or the bar visibly jumps when a
                        // long-press swaps one for the other.
                        state.inSelectionMode -> Text(
                            "${state.selection.size} selected",
                            style = MaterialTheme.typography.titleMedium,
                        )

                        searchOpen -> TextField(
                            value = state.query,
                            onValueChange = vm::setQuery,
                            placeholder = { Text("Search ${displayName(state.currentDir)}") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // titleMedium (16sp) rather than TopAppBar's default titleLarge (22sp):
                        // at 22 in this app's monospace faces, "Internal storage" truncated to
                        // "Internal stora…" on a phone. A typography token, not a hard-coded sp,
                        // so it still tracks the text-size setting.
                        else -> Text(
                            state.vault?.displayName
                                ?: state.archive?.displayName
                                ?: displayName(state.currentDir),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    when {
                        // Serving a pick: the only toolbar action is confirming a multi-select;
                        // a single-select request is answered by the tap itself, and nothing
                        // here mutates storage, so there's no overflow menu at all.
                        pick != null -> if (pick.allowMultiple) {
                            IconButton(
                                onClick = { onPicked(vm.selectedEntries()) },
                                enabled = state.selection.isNotEmpty(),
                            ) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = "Use ${state.selection.size} selected",
                                )
                            }
                        }

                        state.inSelectionMode -> {
                            // The exact flow described from actual daily use of a stock file
                            // manager: press-and-hold selects, and every action from there on —
                            // including ones this app previously exposed as standalone icons —
                            // lives behind the one 3-dot menu. No separate icon row at all.
                            IconButton(onClick = { overflowOpen = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                            }
                            // Order matches action_mode_menu.xml exactly: share, delete, sort,
                            // select all, copy to, extract, move to, compress, rename, get info.
                            DropdownMenu(
                                expanded = overflowOpen,
                                onDismissRequest = { overflowOpen = false },
                            ) {
                                // Sharing hands another app the file behind an entry, which in a
                                // vault is the ciphertext blob — the recipient would get bytes
                                // they cannot read, under an opaque name. Getting a vault file to
                                // another app means copying it out first, deliberately.
                                if (!state.inVault) {
                                    DropdownMenuItem(
                                        text = { Text("Share") },
                                        onClick = {
                                            overflowOpen = false
                                            vm.report(
                                                Opener.share(
                                                    context,
                                                    vm.selectedEntries().map(FileEntry::file),
                                                )
                                            )
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    onClick = { overflowOpen = false; confirmDelete = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Sort by") },
                                    onClick = { overflowOpen = false; showSort = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Select all") },
                                    onClick = { overflowOpen = false; vm.selectAll() },
                                )
                                // action_menu_deselect_all — clearing a large selection via the
                                // X only works if you haven't scrolled away from the top bar.
                                DropdownMenuItem(
                                    text = { Text("Deselect all") },
                                    onClick = { overflowOpen = false; vm.clearSelection() },
                                )
                                // Clipboard copy/cut, alongside the direct "Copy to…" pair
                                // rather than instead of it: transferring somewhere you can
                                // navigate to right now is one gesture, but pulling a file out
                                // of a folder you then have to go and find is two, and until
                                // now the second half had no way to be expressed.
                                DropdownMenuItem(
                                    text = { Text("Copy") },
                                    onClick = { overflowOpen = false; vm.copy() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Cut") },
                                    onClick = { overflowOpen = false; vm.cut() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Copy to…") },
                                    onClick = {
                                        overflowOpen = false
                                        launchDestinationPicker(
                                            context,
                                            vm.selectedEntries(),
                                            isMove = false,
                                        )
                                        vm.clearSelection()
                                    },
                                )
                                // Only meaningful for one archive at a time.
                                if (extractTarget != null && !state.inVault) {
                                    DropdownMenuItem(
                                        text = { Text("Extract") },
                                        onClick = {
                                            overflowOpen = false
                                            vm.extract(extractTarget)
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Move to…") },
                                    onClick = {
                                        overflowOpen = false
                                        launchDestinationPicker(
                                            context,
                                            vm.selectedEntries(),
                                            isMove = true,
                                        )
                                        vm.clearSelection()
                                    },
                                )
                                // Compressing out of a vault would write a plaintext zip of
                                // encrypted files, which is the one thing a vault exists to stop.
                                if (!state.inVault) {
                                    DropdownMenuItem(
                                        text = { Text("Compress") },
                                        onClick = { overflowOpen = false; compressing = true },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    enabled = state.selection.size == 1,
                                    onClick = {
                                        overflowOpen = false
                                        renaming = vm.selectedEntries().firstOrNull()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Get info") },
                                    enabled = state.selection.size == 1,
                                    onClick = {
                                        overflowOpen = false
                                        vm.selectedEntries().firstOrNull()?.let(vm::showProperties)
                                    },
                                )
                            }
                        }

                        else -> {
                            // Matches activity.xml: search is the one always-visible action.
                            // View-mode isn't a menu item in AOSP's own resources either — it's
                            // a persistent icon outside the overflow system, kept here as one.
                            // "New window" from AOSP's overflow is dropped: this app has no
                            // multi-window document-session concept to open a new one of.
                            // Search walks the real filesystem, so it has nothing to offer
                            // inside an archive or a vault.
                            if (!state.inSyntheticTree) {
                                IconButton(onClick = {
                                    searchOpen = !searchOpen
                                    if (!searchOpen) vm.setQuery("")
                                }) {
                                    Icon(Icons.Rounded.Search, contentDescription = "Search")
                                }
                            }
                            IconButton(onClick = vm::toggleViewMode) {
                                Icon(
                                    if (state.viewMode == ViewMode.List) Icons.Rounded.GridView
                                    else Icons.AutoMirrored.Rounded.ViewList,
                                    contentDescription = if (state.viewMode == ViewMode.List) {
                                        "Grid view"
                                    } else {
                                        "List view"
                                    },
                                )
                            }
                            IconButton(onClick = { overflowOpen = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(
                                expanded = overflowOpen,
                                onDismissRequest = { overflowOpen = false },
                            ) {
                                // option_menu_extract_all: AOSP shows this only when you're
                                // standing inside an archive, extracting the one you're in.
                                if (state.inArchive) {
                                    DropdownMenuItem(
                                        text = { Text("Extract all") },
                                        onClick = {
                                            overflowOpen = false
                                            vm.extractCurrentArchive()
                                        },
                                    )
                                }
                                // Standing inside an unlocked vault, the useful action is closing
                                // it again without waiting for the app to be backgrounded.
                                state.vault?.let { here ->
                                    DropdownMenuItem(
                                        text = { Text("Lock vault") },
                                        onClick = {
                                            overflowOpen = false
                                            vm.lockVault(here.vaultRoot)
                                        },
                                    )
                                }
                                // Everything that writes lands in a real directory, so none of it
                                // is offered while browsing an archive's or a vault's contents.
                                if (!state.inArchive) {
                                    DropdownMenuItem(
                                        text = { Text("New folder") },
                                        onClick = { overflowOpen = false; newFolder = true },
                                    )
                                    // Vaults do not nest: one is a folder of ciphertext, and a
                                    // vault inside a vault would encrypt already-encrypted bytes
                                    // for no benefit and two passwords to lose.
                                    if (!state.inVault) {
                                        DropdownMenuItem(
                                            text = { Text("New vault") },
                                            onClick = { overflowOpen = false; newVault = true },
                                        )
                                    }
                                    // The count is the whole point: a clipboard staged three
                                    // folders ago is invisible otherwise, and "Paste" alone
                                    // gives no way to tell it still holds what you think.
                                    state.clipboard?.let { clip ->
                                        DropdownMenuItem(
                                            text = { Text("Paste (${clip.sources.size})") },
                                            onClick = { overflowOpen = false; vm.paste() },
                                        )
                                    }
                                }
                                DropdownMenuItem(
                                    text = { Text("Sort by") },
                                    onClick = { overflowOpen = false; showSort = true },
                                )
                                // Matches AOSP's main-mode overflow, which carries Select all
                                // and Get info too — Get info here describes the folder you're
                                // standing in, since nothing is selected. Both act on the real
                                // filesystem, so neither applies inside an archive or vault.
                                if (!state.inArchive) {
                                    DropdownMenuItem(
                                        text = { Text("Select all") },
                                        enabled = visible.isNotEmpty(),
                                        onClick = { overflowOpen = false; vm.selectAll() },
                                    )
                                }
                                // Get info reads the real file behind an entry, which inside a
                                // vault is the ciphertext blob — its size and path would describe
                                // the encrypted form, not the file the user is looking at.
                                if (!state.inSyntheticTree) {
                                    DropdownMenuItem(
                                        text = { Text("Get info") },
                                        onClick = {
                                            overflowOpen = false
                                            vm.showProperties(FileEntry.from(state.currentDir))
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            // Matches AOSP's menu_hide_hidden_files exactly —
                                            // it's phrased as an instruction ("don't"), not a
                                            // state description ("hide"), unlike its counterpart.
                                            if (state.showHidden) "Don’t show hidden files"
                                            else "Show hidden files"
                                        )
                                    },
                                    onClick = { overflowOpen = false; vm.toggleHidden() },
                                )
                                onOpenSettings?.let { openSettings ->
                                    DropdownMenuItem(
                                        text = { Text("Settings") },
                                        onClick = { overflowOpen = false; openSettings() },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            // Destination mode: you're browsing to pick a target folder, so the confirm action
            // is "use the folder I'm standing in" — there's nothing to select in the list.
            if (pick?.mode == PickRequest.Mode.Destination) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (pick.isMove) "Move to" else "Copy to",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                state.vault?.displayName ?: displayName(state.currentDir),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                            )
                        }
                        Button(
                            /*
                             * The vault's encrypted directory, not the folder the vault sits in.
                             * currentDir stays pointed at the containing folder for as long as a
                             * vault is open — that is what makes leaving one a single field
                             * clear — so confirming here handed the transfer the folder *around*
                             * the vault. Everything worked: the copy ran, reported success, and
                             * dropped the files in plaintext next to the vault the user had just
                             * unlocked and typed a password into.
                             */
                            onClick = { onDestinationChosen(state.vault?.dir ?: state.currentDir) },
                        ) {
                            Text(if (pick.isMove) "Move here" else "Copy here")
                        }
                    }
                }
            }
        },
    ) { padding ->
        // Tapping an entry: extend the selection if one is in progress, walk into folders,
        // otherwise hand the file to the system chooser.
        fun onEntryClick(entry: FileEntry) {
            when {
                // Inside a vault everything is synthetic: directories descend, files are
                // decrypted to the cache before anything can view them.
                state.inVault -> vm.openInVault(entry)
                // A vault folder is an ordinary directory to the filesystem, so this has to come
                // before the isDirectory branch or it would just be walked into and show its
                // opaque internals.
                entry.isDirectory && Vault.isVault(entry.file) -> vm.enterVault(entry.file)
                // Serving a pick, a folder is only ever a route to a file, never a candidate —
                // so it navigates even mid-selection. Without this, selecting one file in a
                // multi-select pick froze navigation: every later folder tap selected the folder
                // instead of entering it, and PickActivity would then be asked to mint a
                // content:// grant for a directory.
                pick != null && entry.isDirectory -> vm.open(entry)
                state.inSelectionMode -> vm.toggleSelection(entry)
                // Inside an archive nothing is a real file yet, so both branches route through
                // the ViewModel: directories descend, files get unpacked to the cache first.
                state.inArchive -> vm.openInArchive(entry)
                entry.isDirectory -> vm.open(entry)
                // Serving a pick: a single-select request is answered by the tap itself, while a
                // multi-select one builds a selection and waits for the confirm button. Checked
                // before the archive branch so picking a zip returns it rather than opening it.
                pick != null -> if (pick.allowMultiple) vm.toggleSelection(entry)
                else onPicked(listOf(entry))
                // Zips open as folders, the way they do in AOSP, rather than going to a chooser.
                entry.isExtractableArchive -> vm.enterArchive(entry)
                // A sealed archive can't be listed without its key, so tapping one asks for the
                // password and unseals it rather than opening a chooser for bytes nothing can read.
                entry.isSealedArchive -> vm.extract(entry)
                else -> vm.report(Opener.open(context, entry))
            }
        }

        // Selection drives copy/move/delete, none of which can act on an archive's contents,
        // so long-press does nothing while inside one.
        // Archive entries have no real file behind them, so selection stays disabled there.
        // Vault entries do — the blob is a real file, and FileOperations knows how to move it.
        fun onEntryLongClick(entry: FileEntry) {
            if (!state.inArchive) vm.toggleSelection(entry)
        }

        Column(Modifier.fillMaxSize().padding(padding)) {
            Breadcrumbs(
                crumbs = state.crumbs,
                labelFor = { crumb ->
                    when (crumb) {
                        is Crumb.Dir -> displayName(crumb.dir)
                        is Crumb.InArchive -> crumb.label
                        is Crumb.InVault -> crumb.label
                    }
                },
                onNavigate = vm::onCrumbClick,
                modifier = Modifier.fillMaxWidth(),
            )

            OperationBanner(
                progress = operation,
                onCancel = vm::cancelOperation,
                onDismiss = vm::acknowledgeOperation,
            )

            when {
                state.loading || state.searching -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                    // LoadingIndicator (the wavy M3 Expressive one) is still internal in the
                    // stable material3 1.4.0 this project pins; CircularProgressIndicator is
                    // the long-stable equivalent.
                ) { CircularProgressIndicator() }

                state.error != null -> EmptyMessage(state.error!!)

                // Matches AOSP's `empty` ("No items") and `no_results` ("No matches in %1$s").
                visible.isEmpty() -> EmptyMessage(
                    // displayName, not File.name — at a volume root the raw segment is the
                    // literal "0" of /storage/emulated/0.
                    if (state.query.isNotEmpty()) "No matches in ${displayName(state.currentDir)}"
                    else "No items"
                )

                // No gap between rows and none above the first: a file list is a list, and the
                // 2dp of air that used to sit between every row read as an unfinished card
                // stack. Density is the user's call now — `scale` drives row height, icon and
                // text together so the whole row grows or shrinks as one piece.
                state.viewMode == ViewMode.List -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    items(visible, key = FileEntry::path) { entry ->
                        FileRow(
                            entry = entry,
                            selected = entry.path in state.selection,
                            selectionMode = state.inSelectionMode,
                            thumbnails = !state.inSyntheticTree,
                            scale = itemScale,
                            onClick = { onEntryClick(entry) },
                            onLongClick = { onEntryLongClick(entry) },
                        )
                    }
                }

                // Adaptive(164.dp), not a hardcoded 2 — checked against AOSP's actual column
                // formula in DirectoryFragment.calculateColumnCount(): columns =
                // max(2, availableWidth / (grid_width + 2*grid_item_margin)), where AOSP's own
                // dimens.xml sets grid_width=152dp and grid_item_margin=6dp, i.e. a 164dp cell
                // pitch. That's architecturally the same thing Adaptive already computes — it
                // renders as 2 columns on a phone the same way AOSP's does, but grows on a
                // tablet the same way AOSP's does too, instead of being stuck at 2 forever.
                // Scaling the cell pitch rather than a column count keeps that formula intact:
                // a smaller scale fits more columns on the same screen, which is what asking for
                // a smaller grid means.
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(164.dp * itemScale),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    gridItems(visible, key = FileEntry::path) { entry ->
                        FileGridCell(
                            entry = entry,
                            selected = entry.path in state.selection,
                            thumbnails = !state.inSyntheticTree,
                            scale = itemScale,
                            onClick = { onEntryClick(entry) },
                            onLongClick = { onEntryLongClick(entry) },
                        )
                    }
                }
            }
        }
    }

    if (showSort) {
        SortSheet(
            current = state.sort,
            showHidden = state.showHidden,
            onSort = vm::setSort,
            onToggleHidden = vm::toggleHidden,
            onDismiss = { showSort = false },
        )
    }

    if (newFolder) {
        NameDialog(
            title = "New folder",
            initial = "",
            onConfirm = { vm.createFolder(it); newFolder = false },
            onDismiss = { newFolder = false },
        )
    }

    renaming?.let { entry ->
        NameDialog(
            title = "Rename",
            initial = entry.name,
            onConfirm = { vm.rename(entry, it); renaming = null; vm.clearSelection() },
            onDismiss = { renaming = null },
        )
    }

    if (confirmDelete) {
        // Wording matches AOSP's delete_forever_dialog_title / delete_forever_confirmation_message
        // exactly. "Forever" is doing real work in that title, not just AOSP's house style: there
        // is no trash here — MANAGE_EXTERNAL_STORAGE deletes are unlink(2) — so this dialog really
        // is the only chance to stop it, same as it is for AOSP's own non-trash delete path.
        val count = state.selection.size
        val message = if (count == 1) {
            val name = vm.selectedEntries().firstOrNull()?.name.orEmpty()
            "“$name” will be deleted forever. This can’t be undone."
        } else {
            "$count items will be deleted forever. This can’t be undone."
        }
        ConfirmDialog(
            title = "Delete forever?",
            message = if (advancedProtection) {
                "$message\n\nAdvanced Protection is enabled on this device."
            } else {
                message
            },
            confirmLabel = "Delete forever",
            destructive = true,
            onConfirm = { vm.delete(); confirmDelete = false },
            onDismiss = { confirmDelete = false },
        )
    }

    if (compressing) {
        val selected = vm.selectedEntries()
        CompressDialog(
            title = "Compress ${selected.size} ${if (selected.size == 1) "item" else "items"}",
            // One item zips to its own name; a batch has no obvious name, so fall back to the
            // folder they came from.
            initialName = (selected.singleOrNull()?.name?.substringBeforeLast('.')
                ?: displayName(state.currentDir).ifEmpty { "archive" }) + ".zip",
            onConfirm = { name, password ->
                vm.compress(name, password)
                compressing = false
            },
            onDismiss = { compressing = false },
        )
    }

    passwordPrompt?.let { prompt ->
        PasswordDialog(
            archiveName = prompt.entry.name,
            onConfirm = { vm.extract(prompt.entry, it) },
            onDismiss = vm::cancelPasswordPrompt,
        )
    }

    vaultPrompt?.let { prompt ->
        PasswordDialog(
            archiveName = prompt.dir.name,
            title = "Unlock vault",
            onConfirm = { vm.unlockVault(prompt.dir, it) },
            onDismiss = vm::cancelVaultPrompt,
        )
    }

    if (newVault) {
        CompressDialog(
            title = "New vault",
            initialName = "Vault",
            requirePassword = true,
            confirmLabel = "Create",
            onConfirm = { name, password ->
                // requirePassword guarantees a non-null password here.
                password?.let { vm.createVault(name, it) }
                newVault = false
            },
            onDismiss = { newVault = false },
        )
    }

    pastePrompt?.let { prompt ->
        ConflictDialog(
            prompt = prompt,
            onPick = vm::pasteWith,
            onDismiss = vm::cancelPaste,
        )
    }

    properties?.let { PropertiesSheet(it, onDismiss = vm::dismissProperties) }
}

/**
 * Opens the destination picker for a copy or move, mirroring AOSP's transferDocuments(): the
 * chosen folder receives the operation immediately, so there's no clipboard and no separate
 * paste step for the user to remember.
 */
private fun launchDestinationPicker(
    context: android.content.Context,
    entries: List<FileEntry>,
    isMove: Boolean,
) {
    if (entries.isEmpty()) return
    val intent = android.content.Intent(
        PickRequest.ACTION_PICK_COPY_DESTINATION,
        android.net.Uri.EMPTY,
        context,
        // DestinationActivity, not PickActivity: this flow is unexported on purpose (see the
        // note in DestinationActivity — an exported host would be a confused deputy).
        com.jegly.files.DestinationActivity::class.java,
    ).apply {
        putStringArrayListExtra(
            PickRequest.EXTRA_SOURCES,
            ArrayList(entries.map { it.path }),
        )
        putExtra(PickRequest.EXTRA_IS_MOVE, isMove)
    }
    context.startActivity(intent)
}

@Composable
private fun Breadcrumbs(
    crumbs: List<Crumb>,
    labelFor: (Crumb) -> String,
    onNavigate: (Crumb) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(crumbs) { crumb ->
            SuggestionChip(
                onClick = { onNavigate(crumb) },
                label = { Text(labelFor(crumb), maxLines = 1) },
            )
        }
    }
}

/**
 * One compact card, in the same place whether an operation is running or has just ended.
 *
 * The old shape put loose text and a full-width bar straight onto the background between the
 * breadcrumbs and the list, so a finished copy left the words "1 done" floating mid-screen with
 * nothing around them — it read like a rendering fault rather than a result. A spinner carries
 * "working" on its own without a bar the width of the window, and a card gives the summary an
 * edge to sit inside for the second and a half it is up.
 */
@Composable
private fun OperationBanner(
    progress: OpProgress?,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(visible = progress != null) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 2.dp,
        ) {
            when (val p = progress) {
                is OpProgress.Running -> Row(
                    modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                        // Determinate only once the plan has measured something. A ring pinned
                        // at zero while a large folder is being counted looks stalled, whereas
                        // an indeterminate one is honestly saying "started, size unknown".
                        if (p.bytesTotal > 0L) {
                            CircularProgressIndicator(
                                progress = { p.fraction },
                                modifier = Modifier.fillMaxSize(),
                                strokeWidth = 2.5.dp,
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.fillMaxSize(),
                                strokeWidth = 2.5.dp,
                            )
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${p.kind.presentTense()} ${p.currentName}",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                        Text(
                            "${p.filesDone} of ${p.filesTotal}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Rounded.Cancel, contentDescription = "Cancel operation")
                    }
                }

                is OpProgress.Finished -> {
                    // A clean run clears itself — AOSP doesn't make you dismiss anything for a
                    // routine successful copy either, it just shows the notification and moves
                    // on. Only a run with something worth reading (a failure) waits for a manual
                    // tap; silently auto-clearing that would hide the one thing the user might
                    // need to act on.
                    val clean = p.failures.isEmpty()
                    LaunchedEffect(p) {
                        if (clean) {
                            delay(2000)
                            onDismiss()
                        }
                    }
                    Row(
                        modifier = Modifier.padding(
                            start = 14.dp,
                            end = if (clean) 14.dp else 8.dp,
                            top = 10.dp,
                            bottom = 10.dp,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            if (clean) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = if (clean) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                buildString {
                                    if (p.cancelled) append("Cancelled — ")
                                    // "Copied 3 items", not "3 done": the verb is the fact the
                                    // user is checking for, and a bare count next to nothing was
                                    // the least readable part of the old banner.
                                    append(p.kind.pastTense())
                                    append(" ${p.succeeded} ${plural(p.succeeded, "item")}")
                                    if (p.skipped > 0) append(", ${p.skipped} skipped")
                                    if (p.failures.isNotEmpty()) {
                                        append(", ${p.failures.size} failed")
                                    }
                                },
                                style = MaterialTheme.typography.labelLarge,
                            )
                            p.failures.take(3).forEach {
                                Text(
                                    "${it.path}: ${it.reason}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (!clean) {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Rounded.Close, contentDescription = "Dismiss")
                            }
                        }
                    }
                }

                null -> Unit
            }
        }
    }
}

private fun OpKind.presentTense(): String = when (this) {
    OpKind.Copy -> "Copying"
    OpKind.Move -> "Moving"
    OpKind.Delete -> "Deleting"
    OpKind.Compress -> "Compressing"
    OpKind.Extract -> "Extracting"
}

private fun OpKind.pastTense(): String = when (this) {
    OpKind.Copy -> "Copied"
    OpKind.Move -> "Moved"
    OpKind.Delete -> "Deleted"
    OpKind.Compress -> "Compressed"
    OpKind.Extract -> "Extracted"
}

@Composable
private fun EmptyMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
