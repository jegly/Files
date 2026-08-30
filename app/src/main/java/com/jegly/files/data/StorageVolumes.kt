package com.jegly.files.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

/**
 * The set of mounted volumes the user can browse.
 *
 * DocumentsUI gets this for free: every volume is a DocumentsProvider root, and the drawer just
 * lists roots. Holding MANAGE_EXTERNAL_STORAGE instead means we see real paths but have to
 * enumerate the volumes ourselves — StorageManager is the supported way to do that, and
 * StorageVolume.getDirectory() (API 30+) hands back the mount point directly, so none of the
 * old /storage path-guessing folklore is needed at minSdk 37.
 *
 * Removable volumes come and go. Callers should re-read this on resume rather than caching it,
 * which is why nothing here is memoised.
 */
data class StorageRoot(
    val label: String,
    val path: File,
    val isPrimary: Boolean,
    val isRemovable: Boolean,
    val totalBytes: Long,
    val freeBytes: Long,
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0L)

    val usedFraction: Float
        get() = if (totalBytes <= 0L) 0f else (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

object StorageVolumes {

    // usableSpace, not StorageManager.getAllocatableBytes as lint suggests. These figures are
    // shown to the user in the roots drawer, so they have to agree with the number Settings puts
    // on the same volume. getAllocatableBytes counts space the system would free by evicting
    // other apps' caches, which is a larger number that nothing else on the device displays.
    @SuppressLint("UsableSpace")
    fun roots(context: Context): List<StorageRoot> {
        val manager = context.getSystemService(StorageManager::class.java)
            ?: return listOf(primaryFallback())

        val roots = manager.storageVolumes.mapNotNull { volume ->
            // A volume that isn't mounted has no directory; ejected SD cards land here.
            val dir = volume.directory ?: return@mapNotNull null
            if (!dir.canRead()) return@mapNotNull null
            StorageRoot(
                label = volume.label(context),
                path = dir,
                isPrimary = volume.isPrimary,
                isRemovable = volume.isRemovable,
                totalBytes = runCatching { dir.totalSpace }.getOrDefault(0L),
                freeBytes = runCatching { dir.usableSpace }.getOrDefault(0L),
            )
        }

        return roots.ifEmpty { listOf(primaryFallback()) }
            .sortedByDescending { it.isPrimary }
    }

    /**
     * Fires whenever a volume is mounted, unmounted or ejected — which is the whole point of
     * plugging in a USB drive while the app is already open.
     *
     * Polling on resume alone isn't enough: OTG storage typically appears while the user is
     * staring at the browser, and a list that only refreshes when you background the app reads
     * as "USB not supported". registerStorageVolumeCallback needs no permission.
     *
     * Returns null when the callback couldn't be registered; callers should keep their
     * resume-time refresh as the fallback rather than relying on this alone.
     */
    fun observe(context: Context, onChanged: () -> Unit): AutoCloseable? {
        val manager = context.getSystemService(StorageManager::class.java) ?: return null
        val callback = object : StorageManager.StorageVolumeCallback() {
            override fun onStateChanged(volume: StorageVolume) = onChanged()
        }
        return runCatching {
            manager.registerStorageVolumeCallback(context.mainExecutor, callback)
            AutoCloseable { manager.unregisterStorageVolumeCallback(callback) }
        }.getOrNull()
    }

    /** The volume containing [path], used to anchor breadcrumbs to the right root. */
    fun rootFor(context: Context, path: File): StorageRoot? {
        val target = path.absolutePath
        return roots(context)
            .filter { target == it.path.absolutePath || target.startsWith(it.path.absolutePath + "/") }
            // Nested mount points exist; the deepest matching prefix is the real owner.
            .maxByOrNull { it.path.absolutePath.length }
    }

    /**
     * The primary volume always gets our own short label rather than the OS-provided
     * description — Android's own text there varies by OEM/build ("Internal shared storage",
     * "Internal storage", "This device", ...) and some of it is verbose enough to visibly
     * overflow a title bar. Removable volumes keep the system description since there's no
     * single generic replacement that's still meaningful (an SD card's own volume label is
     * genuinely useful information, unlike "internal" repeated with extra words).
     */
    private fun StorageVolume.label(context: Context): String =
        if (isPrimary) "Internal storage" else getDescription(context) ?: "Storage"

    @SuppressLint("UsableSpace") // Same reasoning as roots(): this is a displayed figure.
    private fun primaryFallback(): StorageRoot {
        val dir = FileRepository.primaryStorage
        return StorageRoot(
            label = "Internal storage",
            path = dir,
            isPrimary = true,
            isRemovable = false,
            totalBytes = runCatching { dir.totalSpace }.getOrDefault(0L),
            freeBytes = runCatching { dir.usableSpace }.getOrDefault(0L),
        )
    }
}
