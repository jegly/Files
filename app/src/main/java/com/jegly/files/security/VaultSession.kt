package com.jegly.files.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap

/**
 * Which vaults are open, and their master keys, for as long as the app is in the foreground.
 *
 * Memory only, deliberately. Persisting an unwrapped master key anywhere — even in Keystore —
 * would mean the vault is openable without the password by anything that can reach that store,
 * which is most of what the password was for. The cost is that leaving the app closes every
 * vault, and that is the intended trade: [lockAll] is wired to ON_STOP, matching the biometric
 * app lock, which re-arms on exactly the same signal for exactly the same reason.
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

    /** Closes everything. Called when the app leaves the foreground. */
    fun lockAll() {
        val snapshot = keys.keys.toList()
        snapshot.forEach { keys.remove(it)?.zero() }
        _open.value = keys.keys.toSet()
    }

    private fun ByteArray.zero() = Arrays.fill(this, 0)
}
