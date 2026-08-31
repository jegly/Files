package com.jegly.files.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jegly.files.data.Archives
import com.jegly.files.data.FileRepository
import com.jegly.files.data.FileRepository.Companion.sanitizedName
import com.jegly.files.data.MediaInfo
import com.jegly.files.data.StorageRoot
import com.jegly.files.data.StorageVolumes
import com.jegly.files.model.FileEntry
import com.jegly.files.model.SortSpec
import com.jegly.files.model.ViewMode
import com.jegly.files.ops.ConflictPolicy
import com.jegly.files.ops.FileOperationService
import com.jegly.files.ops.OpKind
import com.jegly.files.security.ArchiveCrypto
import com.jegly.files.security.ArchiveCrypto.wipe
import com.jegly.files.security.Vault
import com.jegly.files.security.VaultSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Where we are inside an archive. [entryPath] is "" at the archive's own root.
 *
 * [currentDir] stays pointed at the folder physically containing the archive while this is set,
 * so leaving the archive is just clearing this field — no path arithmetic to get back out.
 */
data class ArchiveLocation(val archive: File, val entryPath: String) {
    val displayName: String
        get() = if (entryPath.isEmpty()) archive.name else entryPath.substringAfterLast('/')
}

/**
 * Where we are inside an unlocked vault.
 *
 * [dir] is the real on-disk directory holding the ciphertext, which has an opaque name; [trail] is
 * the decrypted names of the path taken to reach it, kept alongside because they exist nowhere on
 * disk in readable form and so cannot be recovered from [dir] alone.
 */
data class VaultLocation(
    val vaultRoot: File,
    val dir: File,
    /** Decrypted names of the directories walked through, deepest last. */
    val trail: List<String> = emptyList(),
    /**
     * The on-disk directory for each step of [trail], same order and length.
     *
     * Carried rather than derived because a vault directory's name on disk is opaque and its real
     * name lives only in its parent's encrypted index — so the chain cannot be reconstructed by
     * walking up from [dir] the way an ordinary path can.
     */
    val dirTrail: List<File> = emptyList(),
) {
    val displayName: String get() = trail.lastOrNull() ?: vaultRoot.name
}

/**
 * One step in the breadcrumb trail. Three shapes because a trail can cross out of the filesystem
 * and into an archive or a vault partway along: `/sdcard/Download` → `backup.zip` → `photos`.
 */
sealed interface Crumb {
    data class Dir(val dir: File) : Crumb
    data class InArchive(val location: ArchiveLocation, val label: String) : Crumb
    data class InVault(val location: VaultLocation, val label: String) : Crumb
}

data class BrowserState(
    /** The mounted volume [currentDir] lives on. Breadcrumbs and "up" stop here. */
    val volumeRoot: File = FileRepository.primaryStorage,
    val currentDir: File = FileRepository.primaryStorage,
    /** Non-null while browsing inside an archive; mutations are blocked for as long as it is. */
    val archive: ArchiveLocation? = null,
    /** Non-null while browsing inside an unlocked vault. */
    val vault: VaultLocation? = null,
    val entries: List<FileEntry> = emptyList(),
    val loading: Boolean = false,
    /** A listing failure: the list can't be shown at all, so it replaces the content. */
    val error: String? = null,
    /** A transient failure that doesn't invalidate the listing — shown as a snackbar. */
    val message: String? = null,
    val selection: Set<String> = emptySet(),
    val sort: SortSpec = SortSpec(),
    // Matches AOSP's own default — DocumentsUI opens every folder in grid view.
    val viewMode: ViewMode = ViewMode.Grid,
    val showHidden: Boolean = false,
    val query: String = "",
    val searching: Boolean = false,
    val clipboard: Clipboard? = null,
) {
    /**
     * Lands on a real filesystem location, leaving whichever synthetic tree we were standing in.
     *
     * One helper because the alternative is what actually shipped: `switchVolume`, `navigateTo`
     * and the removed-volume fallback each cleared [archive], and none of them cleared [vault],
     * which was added later. Listing prefers vault over archive over the real directory, so a
     * stale vault silently won all three — tapping a filesystem breadcrumb from inside a vault
     * re-listed the vault instead of going where the breadcrumb said, and switching volumes did
     * the same. Whoever adds a third synthetic tree clears it here once, instead of at four call
     * sites of which one will be missed.
     *
     * Deliberately does not touch [clipboard]: copying from one volume and pasting into another
     * is the whole point of having one, so leaving a location must not empty it.
     */
    fun ontoFilesystem(): BrowserState =
        copy(archive = null, vault = null, selection = emptySet(), query = "")

    val inSelectionMode: Boolean get() = selection.isNotEmpty()

    val inArchive: Boolean get() = archive != null

    val inVault: Boolean get() = vault != null

    /** Anywhere entries are synthetic rather than real files, so writes cannot apply. */
    val inSyntheticTree: Boolean get() = inArchive || inVault

    /**
     * The volume root is the ceiling. Above it are /storage and / — directories we hold no
     * useful access to, so walking up into them only ever produces an empty, confusing listing.
     * Inside an archive there is always somewhere up to go, even if that's back out to the
     * containing folder.
     */
    val canGoUp: Boolean get() = archive != null || vault != null ||
        (currentDir.absolutePath != volumeRoot.absolutePath &&
            currentDir.absolutePath.startsWith(volumeRoot.absolutePath))

    /**
     * Ancestors only, not including the current location itself — the top bar's title already
     * says where you are; a breadcrumb also naming it was pure duplication (confirmed live: at
     * a volume's root the title and the one breadcrumb chip showed the literal same text twice).
     * A breadcrumb's actual job is letting you jump back UP, which the current folder can't do
     * for itself anyway. Naturally empty at the volume root, since there's nowhere up to jump to.
     */
    val crumbs: List<Crumb>
        get() {
            // Outside a synthetic tree the chain stops one short of where we are. Inside one, the
            // containing folder IS an ancestor, so the filesystem half runs all the way down.
            val deepestDir = if (!inSyntheticTree) currentDir.parentFile else currentDir
            val dirs = generateSequence(deepestDir) { it.parentFile }
                .takeWhile { it.absolutePath.startsWith(volumeRoot.absolutePath) }
                .toList()
                .reversed()
                .map(Crumb::Dir)

            vault?.let { here ->
                // The vault's own name is a crumb once you are below its root; at the root the
                // title already says it, matching how the filesystem half stops one short.
                if (here.trail.isEmpty()) return dirs
                val inside = buildList {
                    add(
                        Crumb.InVault(
                            VaultLocation(here.vaultRoot, Vault.treeRoot(here.vaultRoot)),
                            here.vaultRoot.name,
                        )
                    )
                    // Ancestors only, so the last step — where we are now — is left off.
                    for (depth in 0 until here.trail.size - 1) {
                        add(
                            Crumb.InVault(
                                VaultLocation(
                                    here.vaultRoot,
                                    here.dirTrail[depth],
                                    here.trail.take(depth + 1),
                                    here.dirTrail.take(depth + 1),
                                ),
                                here.trail[depth],
                            )
                        )
                    }
                }
                return dirs + inside
            }

            val location = archive ?: return dirs

            val segments = location.entryPath.split('/').filter { it.isNotEmpty() }
            // At the archive's own root there is nothing to add: the archive is the current
            // location, and its parent is already the last filesystem crumb.
            if (segments.isEmpty()) return dirs

            val inside = buildList {
                add(Crumb.InArchive(ArchiveLocation(location.archive, ""), location.archive.name))
                for (depth in 1 until segments.size) {
                    add(
                        Crumb.InArchive(
                            ArchiveLocation(location.archive, segments.take(depth).joinToString("/")),
                            segments[depth - 1],
                        )
                    )
                }
            }
            return dirs + inside
        }
}

data class Clipboard(val kind: OpKind, val sources: List<File>)

/**
 * Names already present in the paste destination, awaiting a ConflictPolicy from the user.
 *
 * [destination] is carried rather than re-read when the answer comes back: the dialog is not
 * modal to navigation, so the user can walk into another folder while it is up, and re-reading
 * currentDir at that point applied their answer — including Overwrite — to a directory whose
 * contents were never the ones checked for collisions.
 */
data class PastePrompt(
    val kind: OpKind,
    val conflicts: List<String>,
    val destination: File,
)

/** A sealed archive waiting on a password before it can be opened. */
data class PasswordPrompt(val entry: FileEntry)

/** A locked vault waiting on its password. */
data class VaultPrompt(val dir: File)

/** Properties sheet state. Null counts mean "the tree walk hasn't finished yet". */
data class Properties(
    val entry: FileEntry,
    val totalBytes: Long? = null,
    val fileCount: Int? = null,
    val folderCount: Int? = null,
    /** Dimensions/duration for media. Meaningful only once [mediaPending] is false. */
    val media: String? = null,
    val mediaPending: Boolean = true,
)

class BrowserViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = FileRepository()

    private val _state = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = _state.asStateFlow()

    val operation = FileOperationService.state

    private val _properties = MutableStateFlow<Properties?>(null)
    val properties: StateFlow<Properties?> = _properties.asStateFlow()

    /**
     * Directory back stack. Distinct from screen navigation — this is path history.
     *
     * Each entry carries the volume root and archive location as well as the directory: crossing
     * to the SD card and pressing back has to restore the breadcrumb ceiling too, or the crumbs
     * render against the wrong volume and "up" walks off the end of the old one. The same applies
     * to stepping out of an archive.
     */
    private data class NavPoint(
        val volumeRoot: File,
        val currentDir: File,
        val archive: ArchiveLocation?,
        val vault: VaultLocation?,
    )

    private val backStack = ArrayDeque<NavPoint>()

    private fun pushNav() {
        _state.value.let {
            backStack.addLast(NavPoint(it.volumeRoot, it.currentDir, it.archive, it.vault))
        }
    }

    private var searchJob: Job? = null
    private var propertiesJob: Job? = null

    private val _roots = MutableStateFlow<List<StorageRoot>>(emptyList())
    val roots: StateFlow<List<StorageRoot>> = _roots.asStateFlow()

    private val _pastePrompt = MutableStateFlow<PastePrompt?>(null)
    val pastePrompt: StateFlow<PastePrompt?> = _pastePrompt.asStateFlow()

    /** A file unpacked from an archive, waiting for the UI to hand it to a viewer. */
    private val _pendingPreview = MutableStateFlow<File?>(null)
    val pendingPreview: StateFlow<File?> = _pendingPreview.asStateFlow()

    private val _passwordPrompt = MutableStateFlow<PasswordPrompt?>(null)
    val passwordPrompt: StateFlow<PasswordPrompt?> = _passwordPrompt.asStateFlow()

    private val _vaultPrompt = MutableStateFlow<VaultPrompt?>(null)
    val vaultPrompt: StateFlow<VaultPrompt?> = _vaultPrompt.asStateFlow()

    /** Creates a vault in a new subfolder of the current directory. */
    fun createVault(name: String, password: CharArray) {
        val parent = _state.value.currentDir
        viewModelScope.launch {
            val made = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(parent, name.sanitizedName())
                    Vault.create(dir, password)
                    dir
                }
            }
            password.wipe()
            made
                .onSuccess { refresh() }
                .onFailure { report(it.message) }
        }
    }

    init {
        refreshRoots()
        refresh()
    }

    // --- volumes ----------------------------------------------------------------

    /** Removable volumes mount and unmount; re-read on resume rather than trusting a cache. */
    fun refreshRoots() {
        val roots = StorageVolumes.roots(getApplication())
        _roots.value = roots

        /*
         * If the volume we're standing on just went away — USB pulled, SD ejected — every
         * subsequent listing would fail against a path that no longer resolves, leaving the user
         * staring at an error with no way back. Retreat to primary storage instead, and drop the
         * back stack because its entries point into the departed volume too.
         */
        val current = _state.value.volumeRoot.absolutePath
        if (roots.none { it.path.absolutePath == current }) {
            val fallback = roots.firstOrNull { it.isPrimary } ?: roots.firstOrNull() ?: return
            backStack.clear()
            // A vault on the departed volume is gone, but its master key would otherwise sit in
            // memory until the app was backgrounded — and re-inserting the card would then show
            // it already open, with no password asked for. Ejecting the medium should close what
            // was on it.
            VaultSession.lockMissing()
            _state.update {
                // An archive or a vault on the departed volume is just as unreachable as the
                // folder was.
                it.ontoFilesystem().copy(
                    volumeRoot = fallback.path,
                    currentDir = fallback.path,
                    clipboard = null,
                )
            }
            report("Storage was removed")
            refresh()
        }
    }

    fun switchVolume(root: StorageRoot) {
        pushNav()
        _state.update {
            it.ontoFilesystem().copy(volumeRoot = root.path, currentDir = root.path)
        }
        refresh()
    }

    // --- navigation -------------------------------------------------------------

    fun open(entry: FileEntry) {
        if (entry.isDirectory) navigateTo(entry.file) else Unit
    }

    fun navigateTo(dir: File) {
        pushNav()
        _state.update { it.ontoFilesystem().copy(currentDir = dir) }
        refresh()
    }

    fun onCrumbClick(crumb: Crumb) = when (crumb) {
        is Crumb.Dir -> navigateTo(crumb.dir)
        is Crumb.InArchive -> navigateInArchive(crumb.location)
        is Crumb.InVault -> navigateInVault(crumb.location)
    }

    /** Returns false when there is nowhere left to go, so the Activity can finish. */
    fun navigateBack(): Boolean {
        val s = _state.value
        if (s.inSelectionMode) { clearSelection(); return true }
        if (s.query.isNotEmpty()) { setQuery(""); return true }
        val previous = backStack.removeLastOrNull() ?: return false
        _state.update {
            it.copy(
                volumeRoot = previous.volumeRoot,
                currentDir = previous.currentDir,
                archive = previous.archive,
                vault = previous.vault,
                selection = emptySet(),
            )
        }
        refresh()
        return true
    }

    fun navigateUp() {
        val s = _state.value
        s.vault?.let { here ->
            // At the vault's root, up means back out to the folder containing it.
            if (here.trail.isEmpty()) exitVault() else navigateBack()
            return
        }
        if (s.archive != null) {
            val inner = s.archive.entryPath
            if (inner.isEmpty()) exitArchive()
            else navigateInArchive(
                ArchiveLocation(s.archive.archive, inner.substringBeforeLast('/', "")),
            )
            return
        }
        if (!s.canGoUp) return
        s.currentDir.parentFile?.let(::navigateTo)
    }

    // --- archives ---------------------------------------------------------------

    /** Walks into a zip as though it were a folder. */
    fun enterArchive(entry: FileEntry) {
        pushNav()
        _state.update {
            it.copy(
                archive = ArchiveLocation(entry.file, ""),
                // Mirrors enterVault clearing `archive`. Unreachable from inside a vault today,
                // because the tap dispatch checks inVault first and a vault entry's `file` is a
                // ciphertext blob rather than a zip — but that is the UI's ordering guaranteeing
                // a ViewModel invariant, which is not where it belongs.
                vault = null,
                selection = emptySet(),
                query = "",
            )
        }
        refresh()
    }

    fun navigateInArchive(location: ArchiveLocation) {
        pushNav()
        _state.update { it.copy(archive = location, selection = emptySet(), query = "") }
        refresh()
    }

    fun exitArchive() {
        pushNav()
        _state.update { it.copy(archive = null, selection = emptySet(), query = "") }
        refresh()
    }

    /**
     * Tapping something inside an archive: descend into directories, and for a file unpack it to
     * the cache so another app can open it. Nothing inside an archive is a real file yet, so
     * there is no path to hand to a viewer until it has been written out.
     */
    fun openInArchive(entry: FileEntry) {
        val location = _state.value.archive ?: return
        val inner = Archives.innerPath(entry)
        if (entry.isDirectory) {
            navigateInArchive(ArchiveLocation(location.archive, inner))
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            Archives.extractToCache(getApplication(), location.archive, inner)
                .onSuccess { _pendingPreview.value = it }
                .onFailure { report(it.message) }
            _state.update { it.copy(loading = false) }
        }
    }

    fun consumePreview() { _pendingPreview.value = null }

    // --- vaults -----------------------------------------------------------------

    /**
     * Opens a vault folder. Prompts for the password unless it is already unlocked this session.
     */
    fun enterVault(dir: File) {
        if (!VaultSession.isUnlocked(dir)) {
            _vaultPrompt.value = VaultPrompt(dir)
            return
        }
        pushNav()
        _state.update {
            it.copy(
                vault = VaultLocation(dir, Vault.treeRoot(dir)),
                archive = null,
                selection = emptySet(),
                query = "",
            )
        }
        refresh()
    }

    /** Answers the unlock prompt. The array is wiped here; callers must not reuse it. */
    fun unlockVault(dir: File, password: CharArray) {
        val opened = try {
            VaultSession.unlock(dir, password)
        } finally {
            password.wipe()
        }
        if (!opened) {
            report("Wrong password")
            return
        }
        _vaultPrompt.value = null
        enterVault(dir)
    }

    fun cancelVaultPrompt() { _vaultPrompt.value = null }

    fun navigateInVault(location: VaultLocation) {
        pushNav()
        _state.update { it.copy(vault = location, selection = emptySet(), query = "") }
        refresh()
    }

    fun exitVault() {
        pushNav()
        _state.update { it.copy(vault = null, selection = emptySet(), query = "") }
        refresh()
    }

    /** Closes a vault, and steps out of it if that is where we are standing. */
    fun lockVault(dir: File) {
        VaultSession.lock(dir)
        if (_state.value.vault?.vaultRoot?.absolutePath == dir.absolutePath) exitVault()
        report("\"${dir.name}\" locked")
    }

    /**
     * Tapping something inside a vault: descend into directories, and for a file decrypt it to
     * the cache so a viewer can open it. Nothing in a vault is readable in place.
     */
    fun openInVault(entry: FileEntry) {
        val here = _state.value.vault ?: return
        val key = VaultSession.keyFor(here.vaultRoot) ?: run {
            report("That vault is locked"); return
        }
        if (entry.isDirectory) {
            navigateInVault(
                VaultLocation(
                    here.vaultRoot,
                    entry.file,
                    here.trail + entry.name,
                    here.dirTrail + entry.file,
                ),
            )
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val opened = withContext(Dispatchers.IO) {
                runCatching {
                    val bucket = File(getApplication<Application>().cacheDir, VAULT_PREVIEW_DIR)
                    if (!bucket.exists() && !bucket.mkdirs()) error("Couldn't prepare the cache")
                    // Named from the decrypted name so the viewer gets a usable extension, but
                    // placed in a bucket keyed by the opaque storage name so two files with the
                    // same name in different vault folders cannot collide.
                    val dir = File(bucket, entry.file.name).apply { mkdirs() }
                    val out = File(dir, entry.name)
                    Vault.exportFile(here.dir, entry.file.name, out, key)
                    out
                }
            }
            opened
                .onSuccess { _pendingPreview.value = it }
                .onFailure { report(it.message) }
            _state.update { it.copy(loading = false) }
        }
    }

    /** Unpacks the archive currently being browsed, alongside it on disk. */
    fun extractCurrentArchive() {
        val location = _state.value.archive ?: return
        extract(FileEntry.from(location.archive))
    }

    /**
     * Everything that writes to disk is unavailable while inside an archive: entries there have
     * synthetic paths, so a rename or delete would operate on a path that does not exist.
     */
    private fun blockedInArchive(): Boolean {
        if (_state.value.archive != null) {
            report("Not available inside an archive")
            return true
        }
        return false
    }

    /**
     * Refuses the operations that still have no vault-aware implementation.
     *
     * Copy, move and delete DO work inside a vault — FileOperations dispatches on whether either
     * end of the transfer sits inside one. Compressing from a vault is what remains unsupported:
     * it would have to decrypt everything into a zip, which is a plaintext copy of the vault and
     * exactly what the user was avoiding.
     */
    private fun blockedInVault(): Boolean {
        if (_state.value.vault != null) {
            report("Not available inside a vault")
            return true
        }
        return false
    }

    // --- listing ----------------------------------------------------------------

    /**
     * Reloads whatever the list is currently showing. When a search is active that means
     * re-running the search, not the directory listing — otherwise any refresh trigger
     * (finishing a copy, toggling hidden files) silently throws the user's results away.
     */
    fun refresh() {
        val current = _state.value
        // Search walks the real filesystem, so it has no meaning inside an archive.
        if (current.query.isNotBlank() && current.archive == null) {
            runSearch(current.query, debounce = false)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val s = _state.value
            val listing = s.vault?.let { listVault(it) }
                ?: s.archive?.let { Archives.list(it.archive, it.entryPath) }
                ?: repo.list(s.currentDir)
            listing
                .onSuccess { entries -> _state.update { it.copy(entries = entries.applyView(it), loading = false) } }
                .onFailure { t -> _state.update { it.copy(error = t.message, loading = false, entries = emptyList()) } }
        }
    }

    /**
     * A vault directory as ordinary [FileEntry] values.
     *
     * `path` is the real on-disk path of the ciphertext blob, which is both unique (so it works as
     * a LazyColumn key) and exactly what Vault needs to read the thing back — the opaque storage
     * name is just `file.name`. `name` is the decrypted name, which exists nowhere on disk in
     * readable form, so the two deliberately disagree.
     */
    private suspend fun listVault(location: VaultLocation): Result<List<FileEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val key = VaultSession.keyFor(location.vaultRoot)
                    ?: error("That vault is locked")
                Vault.list(location.dir, key).map { entry ->
                    FileEntry(
                        path = File(location.dir, entry.storageName).absolutePath,
                        name = entry.name,
                        isDirectory = entry.isDirectory,
                        size = entry.size,
                        lastModified = entry.lastModified,
                        isHidden = entry.name.startsWith('.'),
                        childCount = -1,
                    )
                }
            }
        }

    private fun List<FileEntry>.applyView(s: BrowserState): List<FileEntry> =
        filter { s.showHidden || !it.isHidden }.sortedWith(s.sort.comparator())

    private fun reapplyView() {
        _state.update { it.copy(entries = it.entries.applyView(it)) }
        // Hidden files may have been filtered out of the cached list entirely.
        if (_state.value.showHidden) refresh()
    }

    fun setSort(sort: SortSpec) {
        _state.update { it.copy(sort = sort) }
        reapplyView()
    }

    fun toggleViewMode() = _state.update {
        it.copy(viewMode = if (it.viewMode == ViewMode.List) ViewMode.Grid else ViewMode.List)
    }

    fun toggleHidden() {
        _state.update { it.copy(showHidden = !it.showHidden) }
        refresh()
    }

    // --- search -----------------------------------------------------------------

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        if (query.isBlank()) {
            searchJob?.cancel()
            _state.update { it.copy(searching = false) }
            refresh()
            return
        }
        runSearch(query, debounce = true)
    }

    private fun runSearch(query: String, debounce: Boolean) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (debounce) delay(DEBOUNCE_MS)
            _state.update { it.copy(searching = true, error = null) }
            val root = _state.value.currentDir
            // runCatching would swallow the CancellationException thrown by the next keystroke's
            // cancel(), and the dead job would then write its stale results over the live ones.
            val results = try {
                Result.success(repo.search(root, query))
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Result.failure(t)
            }
            _state.update { s ->
                results.fold(
                    onSuccess = { s.copy(entries = it.applyView(s), searching = false) },
                    onFailure = { s.copy(error = it.message, searching = false) },
                )
            }
        }
    }

    // --- selection --------------------------------------------------------------

    fun toggleSelection(entry: FileEntry) = _state.update {
        val next = it.selection.toMutableSet()
        if (!next.add(entry.path)) next.remove(entry.path)
        it.copy(selection = next)
    }

    fun selectAll() = _state.update { it.copy(selection = it.entries.map(FileEntry::path).toSet()) }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    /**
     * Derived from the live listing rather than straight from the selection set, so it agrees
     * with [selectedEntries]. A refresh — a finished copy, a toggled filter, a volume that came
     * and went — can leave paths in the selection that are no longer present, and mapping those
     * to File handed delete and copy targets that had already stopped existing.
     */
    private fun selectedFiles(): List<File> = selectedEntries().map(FileEntry::file)

    /** Selected entries in display order, for actions that need names or kinds. */
    fun selectedEntries(): List<FileEntry> =
        _state.value.let { s -> s.entries.filter { it.path in s.selection } }

    // --- properties -------------------------------------------------------------

    fun showProperties(entry: FileEntry) {
        propertiesJob?.cancel()
        _properties.value = Properties(entry)
        propertiesJob = viewModelScope.launch {
            // Header parse first: it returns in milliseconds, whereas the tree walk can take
            // seconds on a large folder, and there's no reason to make the fast fact wait.
            val media = MediaInfo.describe(entry)
            publishProperties(entry) { it.copy(media = media, mediaPending = false) }

            val stats = repo.treeStats(entry.file)
            publishProperties(entry) {
                it.copy(
                    totalBytes = stats.bytes,
                    fileCount = stats.files,
                    folderCount = stats.folders,
                )
            }
        }
    }

    /**
     * The sheet may have been dismissed and reopened on a different entry while a background
     * read was in flight; only publish if we're still describing the same thing.
     */
    private fun publishProperties(entry: FileEntry, transform: (Properties) -> Properties) {
        _properties.update { current ->
            if (current?.entry?.path != entry.path) current else transform(current)
        }
    }

    fun dismissProperties() {
        propertiesJob?.cancel()
        _properties.value = null
    }

    // --- mutations --------------------------------------------------------------

    fun cut() { if (!blockedInArchive()) stageClipboard(OpKind.Move) }
    fun copy() { if (!blockedInArchive()) stageClipboard(OpKind.Copy) }

    /**
     * Stages a clipboard and says so. Paste lives in the overflow menu, so without the
     * acknowledgement a copy is a menu tap that visibly does nothing but drop the selection —
     * indistinguishable from having mis-tapped.
     */
    private fun stageClipboard(kind: OpKind) {
        val staged = selectedFiles()
        if (staged.isEmpty()) return
        val noun = if (staged.size == 1) "item" else "items"
        val verb = if (kind == OpKind.Move) "Cut" else "Copied"
        _state.update {
            it.copy(
                clipboard = Clipboard(kind, staged),
                selection = emptySet(),
                message = "$verb ${staged.size} $noun — paste from the menu",
            )
        }
    }

    /**
     * Pastes straight away when nothing collides, and asks first when something does.
     *
     * Silently defaulting to KeepBoth is the safe choice but not the expected one: a user
     * re-pasting over a folder they just edited wants Overwrite, and gets a directory full of
     * "report (2).pdf" instead, with no indication anything was ambiguous.
     */
    fun paste() {
        if (blockedInArchive()) return
        val s = _state.value
        val clip = s.clipboard ?: return
        // Pinned here, and carried through the prompt, so the folder that gets written is the
        // one whose contents were examined for collisions. Inside a vault that is the encrypted
        // directory being browsed, not the folder the vault happens to sit in — FileOperations
        // decides encrypt/decrypt/re-encrypt from the two ends of the transfer, so pointing it
        // at the right end is the whole of what "paste into a vault" means here.
        val destination = s.vault?.dir ?: s.currentDir
        viewModelScope.launch {
            val conflicts = withContext(Dispatchers.IO) { collisions(clip.sources, destination) }
            if (conflicts.isEmpty()) startPaste(clip, destination, ConflictPolicy.KeepBoth)
            else _pastePrompt.value = PastePrompt(clip.kind, conflicts, destination)
        }
    }

    /**
     * Names in [destination] that [sources] would land on top of.
     *
     * Empty whenever either end is a vault, and that is a decision rather than an oversight: a
     * vault entry's real name lives in an encrypted index, so a File's name on either side is an
     * opaque blob name that collides with nothing and matches nothing. Answering "no conflicts"
     * sends the transfer down the KeepBoth path, where Vault.uniqueName does the deduplication
     * with the names it can actually read. The cost is that Overwrite is not offered for a vault
     * paste; the alternative was a dialog listing blob names nobody can recognise.
     */
    private fun collisions(sources: List<File>, destination: File): List<String> {
        if (Vault.isInsideVault(destination) || sources.any { Vault.isInsideVault(it) }) {
            return emptyList()
        }
        return sources.filter { File(destination, it.name).exists() }.map { it.name }
    }

    fun pasteWith(policy: ConflictPolicy) {
        val prompt = _pastePrompt.value ?: return
        val clip = _state.value.clipboard ?: return
        startPaste(clip, prompt.destination, policy)
    }

    private fun startPaste(clip: Clipboard, destination: File, policy: ConflictPolicy) {
        FileOperationService.start(getApplication(), clip.kind, clip.sources, destination, policy)
        _state.update { it.copy(clipboard = null) }
        _pastePrompt.value = null
    }

    fun cancelPaste() { _pastePrompt.value = null }

    fun delete() {
        if (blockedInArchive()) return
        val targets = selectedFiles()
        if (targets.isEmpty()) return
        FileOperationService.start(getApplication(), OpKind.Delete, targets, null)
        clearSelection()
    }

    // --- archives ---------------------------------------------------------------

    /**
     * Zips the current selection into [archiveName] in the current directory.
     *
     * A non-null [password] seals the result into an [ArchiveCrypto] container instead, which
     * gets its own extension: the file is not a zip any more and nothing else can open it, so
     * naming it ".zip" would be a lie that other apps would act on. Ownership of the array passes
     * to the service, which zeroes it when the operation ends.
     */
    fun compress(archiveName: String, password: CharArray? = null) {
        if (blockedInArchive() || blockedInVault()) {
            password?.wipe()
            return
        }
        val sources = selectedFiles()
        if (sources.isEmpty()) {
            password?.wipe()
            return
        }
        val cleaned = runCatching { archiveName.sanitizedName() }.getOrElse {
            password?.wipe()
            report(it.message); return
        }
        val zipped = if (cleaned.endsWith(".zip", ignoreCase = true)) cleaned else "$cleaned.zip"
        val named = if (password == null) zipped else "$zipped.${ArchiveCrypto.EXTENSION}"
        val target = File(_state.value.currentDir, named)
        if (target.exists()) {
            password?.wipe()
            report("\"$named\" already exists")
            return
        }
        FileOperationService.start(
            getApplication(), OpKind.Compress, sources, target, password = password,
        )
        clearSelection()
    }

    /**
     * Extracts into a sibling folder named after the archive, the way every desktop file manager
     * does — spraying an archive's contents into the current directory is how people lose track
     * of which of the 200 new files came from where.
     */
    fun extract(entry: FileEntry, password: CharArray? = null) {
        // A sealed archive is unreadable without a key, so ask before starting an operation that
        // could only fail. The prompt carries the entry so the answer knows what it unlocks.
        if (entry.isSealedArchive && password == null) {
            _passwordPrompt.value = PasswordPrompt(entry)
            return
        }
        val parent = entry.file.parentFile ?: run {
            password?.wipe()
            report("No parent folder"); return
        }
        // "secrets.zip.jfsec" should land in "secrets", not "secrets.zip" — strip both suffixes.
        val bare = entry.name.removeSuffix(".${ArchiveCrypto.EXTENSION}")
        val stem = bare.substringBeforeLast('.', bare)
        var destination = File(parent, stem)
        var n = 2
        while (destination.exists()) {
            destination = File(parent, "$stem ($n)")
            n++
        }
        FileOperationService.start(
            getApplication(), OpKind.Extract, listOf(entry.file), destination, password = password,
        )
        _passwordPrompt.value = null
        clearSelection()
    }

    fun cancelPasswordPrompt() { _passwordPrompt.value = null }

    fun cancelOperation() = FileOperationService.cancel(getApplication())

    fun acknowledgeOperation() {
        FileOperationService.clearState()
        refresh()
    }

    fun createFolder(name: String) {
        if (blockedInArchive()) return
        val here = _state.value.vault
        viewModelScope.launch {
            val result = if (here == null) {
                repo.createFolder(_state.value.currentDir, name)
            } else {
                // Inside a vault a directory is a real one with an opaque name, plus an entry in
                // the parent's encrypted index. Vault owns keeping those two in step.
                withContext(Dispatchers.IO) {
                    runCatching {
                        val key = VaultSession.keyFor(here.vaultRoot) ?: error("That vault is locked")
                        Vault.createDirectory(here.dir, name.sanitizedName(), key)
                    }
                }
            }
            result.onSuccess { refresh() }.onFailure { t -> report(t.message) }
        }
    }

    fun rename(entry: FileEntry, newName: String) {
        if (blockedInArchive()) return
        val here = _state.value.vault
        viewModelScope.launch {
            val result = if (here == null) {
                repo.rename(entry, newName)
            } else {
                // Renaming in a vault rewrites one index record; the ciphertext is never touched,
                // because the on-disk name was never derived from the real one.
                withContext(Dispatchers.IO) {
                    runCatching {
                        val key = VaultSession.keyFor(here.vaultRoot) ?: error("That vault is locked")
                        Vault.rename(here.dir, entry.file.name, newName.sanitizedName(), key)
                    }
                }
            }
            result.onSuccess { refresh() }.onFailure { t -> report(t.message) }
        }
    }

    /** Surfaces a transient failure without tearing down the listing. */
    fun report(message: String?) {
        if (message != null) _state.update { it.copy(message = message) }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun dismissError() = _state.update { it.copy(error = null) }

    private companion object {
        const val DEBOUNCE_MS = 250L

        /**
         * Where vault files land while a viewer has them open. App-private, but plaintext — that
         * is unavoidable, because handing a file to another app means giving it something
         * readable. Cleared alongside the archive preview cache under memory pressure.
         */
        const val VAULT_PREVIEW_DIR = "vault-preview"
    }
}
