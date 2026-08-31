package com.jegly.files.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Which vaults are open, and their master keys, for as long as the app is in the foreground.
 *
 * Memory only, deliberately. Persisting an unwrapped master key anywhere — even in Keystore —
 * would mean the vault is openable without the password by anything that can reach that store,
 * which is most of what the password was for.
 *
 * LEAVING THE APP CLOSES VAULTS, BUT NOT INSTANTLY, AND NOT PER SCREEN. This used to be
 * [lockAll] wired straight to one screen's ON_STOP, which was wrong twice over. An Activity
 * stopping is not the app being left: opening the destination picker behind "Copy to…" stops the
 * browser Activity, so the vault the user had just unlocked was closed before the copy they
 * started could write a single byte into it — which is why copying into a vault never worked.
 * And "instantly" is not a policy the user got a say in. [noteForeground] / [noteBackground]
 * count started screens instead, so a hand-off between this app's own Activities never counts as
 * leaving, and the lock is deferred by the user's chosen timeout once the last one really stops.
 *
 * Keys are zeroed on the way out rather than dropped, so a heap dump taken after locking does not
 * hand over what the lock was supposed to close.
 */
object VaultSession {

    private val keys = ConcurrentHashMap<String, ByteArray>()

    private val _open = MutableStateFlow<Set<String>>(emptySet())

    /** Absolute paths of currently unlocked vaults, for the UI to reflect. */
    val open: StateFlow<Set<String>> = _open.asStateFlow()

    fun isUnlocked(vault: File): Boolean = keys.containsKey(vault.absolutePath)

    /**
     * The master key for [vault], or null if it is locked.
     *
     * The array is the live one, not a copy: callers read from it and must not zero or mutate it.
     * Copying per call would scatter key material across the heap for the GC to leave lying
     * around, which is worse than the aliasing.
     */
    fun keyFor(vault: File): ByteArray? = keys[vault.absolutePath]

    /**
     * Opens [vault], returning false if the password is wrong. The password is the caller's to
     * wipe; the derived master key becomes this object's responsibility.
     */
    fun unlock(vault: File, password: CharArray): Boolean {
        val wasLocked = !isUnlocked(vault)
        val key = try {
            Vault.unlock(vault, password)
        } catch (e: Vault.WrongPasswordException) {
            return false
        }

        // Before publishing the key, not after, and this ordering is the entire safety argument.
        // Every vault write path has to obtain a key from here first, so during this call there
        // is provably no operation running against this vault and no in-flight import for the
        // collector to mistake for garbage. Publishing first and sweeping afterwards would open
        // exactly the window that makes reclaiming unreferenced blobs dangerous.
        //
        // Skipped when the vault was already open, because then that argument does not hold.
        //
        // runCatching because reclaiming space is housekeeping: a vault with one unreadable
        // subdirectory index must still unlock, rather than being made permanently unopenable by
        // a failure in the part that only tidies up.
        if (wasLocked) runCatching { Vault.reclaimOrphans(vault, key) }

        keys.put(vault.absolutePath, key)?.zero()
        _open.value = keys.keys.toSet()
        return true
    }

    fun lock(vault: File) {
        keys.remove(vault.absolutePath)?.zero()
        _open.value = keys.keys.toSet()
    }

    /**
     * Closes any vault whose folder is no longer there — an ejected SD card, an unplugged drive.
     *
     * Holding the master key for a volume that has been removed serves nobody: the vault cannot
     * be read, and if the medium comes back it would appear already unlocked without the password
     * being asked for again. Unmounting the thing a vault lives on should close it.
     */
    fun lockMissing() {
        keys.keys.toList()
            .filterNot { File(it).isDirectory }
            .forEach { keys.remove(it)?.zero() }
        _open.value = keys.keys.toSet()
    }

    /** Closes everything. */
    fun lockAll() {
        val snapshot = keys.keys.toList()
        snapshot.forEach { keys.remove(it)?.zero() }
        _open.value = keys.keys.toSet()
    }

    // --- foreground tracking ----------------------------------------------------

    /**
     * A daemon thread so a pending auto-lock can never keep the process alive on its own, and a
     * single one so locks are serialised with the bookkeeping that schedules them.
     */
    private val timer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "vault-autolock").apply { isDaemon = true }
    }

    private val gate = Any()
    private var startedScreens = 0
    private var pendingLock: ScheduledFuture<*>? = null

    /**
     * When the last screen stopped, and the timeout it stopped under.
     *
     * Checked again on the way back in because a scheduled task is not a guarantee: a process
     * that was frozen or starved can be resumed with the timer's deadline long past and the task
     * not yet run, and the vault must be closed by then rather than a moment later.
     */
    private var backgroundedAt = 0L
    private var backgroundedTimeout = 0L

    /** One of this app's screens started. */
    fun noteForeground() {
        synchronized(gate) {
            startedScreens++
            if (startedScreens > 1) return
            pendingLock?.cancel(false)
            pendingLock = null
            val since = backgroundedAt
            if (since != 0L &&
                backgroundedTimeout >= 0L &&
                elapsedRealtime() - since >= backgroundedTimeout
            ) {
                lockAll()
            }
            backgroundedAt = 0L
        }
    }

    /**
     * One of this app's screens stopped. Vaults close [timeoutMs] after the last one does;
     * [com.jegly.files.data.AppSettings.VAULT_LOCK_NEVER] leaves them open for the process's life.
     */
    fun noteBackground(timeoutMs: Long) {
        synchronized(gate) {
            if (startedScreens == 0) return
            startedScreens--
            if (startedScreens > 0) return
            backgroundedTimeout = timeoutMs
            if (timeoutMs < 0L) { backgroundedAt = 0L; return }
            backgroundedAt = elapsedRealtime()
            if (timeoutMs == 0L) { lockAll(); return }
            pendingLock?.cancel(false)
            pendingLock = timer.schedule(
                { synchronized(gate) { if (startedScreens == 0) lockAll() } },
                timeoutMs,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    /**
     * elapsedRealtime, not nanoTime or currentTimeMillis: it counts time spent in deep sleep,
     * which nanoTime does not, and cannot be moved by the clock changing under us. A phone that
     * sat asleep overnight has to come back with its vaults closed.
     */
    private fun elapsedRealtime(): Long = android.os.SystemClock.elapsedRealtime()

    private fun ByteArray.zero() = Arrays.fill(this, 0)
}
