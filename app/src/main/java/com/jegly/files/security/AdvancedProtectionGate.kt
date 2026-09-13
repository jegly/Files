package com.jegly.files.security

import android.content.Context
import android.security.advancedprotection.AdvancedProtectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Read-only awareness of Android Advanced Protection, not control: setAdvancedProtectionEnabled()
 * is @SystemApi and reserved for Settings.
 *
 * What [enabled] changes, in full — it is less than an earlier version of this comment claimed,
 * which said the app "hides destructive shortcuts and forces the app lock on": the app lock
 * cannot be switched off while AAPM is on, *if it was already armed*. AAPM does not arm it, so a
 * user who never set one up gets nothing from this. Nothing is hidden, and no operation is
 * blocked.
 *
 * It deliberately does not annotate the UI. A line in the delete dialog saying AAPM was on was
 * the only other thing this flag drove, and it was removed: it restricted nothing, appeared at
 * the moment the user was trying to get something done, and told them about a device setting
 * they had turned on themselves. Restating a protection is not applying one. [state] reports
 * AAPM in Settings, where someone is actually asking.
 *
 * minSdk 37 means AdvancedProtectionManager is guaranteed present, so the type can be referenced
 * directly with no SDK_INT guard and no risk of eager verifier resolution at class load.
 *
 * The manifest declares QUERY_ADVANCED_PROTECTION_MODE, which all three of this class's calls
 * require. It is a normal permission — granted at install, no runtime request, no prompt — and
 * declaring it is the path Google documents for ordinary apps. An earlier version left it out on
 * the theory that it could never be granted to a third-party app, and swallowed the resulting
 * SecurityException; the visible effect was Settings reporting "Off" on a device where Advanced
 * Protection was switched on.
 */
class AdvancedProtectionGate private constructor(context: Context) {

    /**
     * [Unavailable] exists so a failed read cannot masquerade as a real "off". They call for
     * opposite things: "off" is the ordinary state of most devices and worth stating plainly,
     * while "unavailable" means this app does not know and should say so rather than reassure.
     */
    enum class State { On, Off, Unavailable }

    private val _state = MutableStateFlow(State.Unavailable)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _enabled = MutableStateFlow(false)

    /**
     * Hardening decisions only. Unavailable reports false here on purpose: the extra restrictions
     * exist to match a protection mode that has not been confirmed to be on, so applying them on
     * a guess would lock features off for no established reason. Anything that *describes* the
     * state to the user must read [state] instead, which keeps the two cases apart.
     */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /**
     * Held so the callback can be unregistered, and so the only strong reference outside the
     * system service lives as long as this process-wide singleton does.
     */
    private val callback = AdvancedProtectionManager.Callback { isEnabled -> publish(isEnabled) }

    init {
        observe(context)
    }

    private fun publish(isEnabled: Boolean) {
        _state.value = if (isEnabled) State.On else State.Off
        _enabled.value = isEnabled
    }

    private fun observe(context: Context) {
        val manager = context.getSystemService(AdvancedProtectionManager::class.java) ?: return

        // A plain read first: registration is the more heavily guarded of the two, so on a build
        // that allows only the read we still report the correct state, just without live updates.
        runCatching { manager.isAdvancedProtectionEnabled }
            .onSuccess { publish(it) }
            .onFailure { _state.value = State.Unavailable }

        // Fires once immediately with the current state, so this also repairs the value above if
        // the direct read was the call that was refused.
        runCatching {
            manager.registerAdvancedProtectionCallback(context.mainExecutor, callback)
        }
    }

    companion object {
        @Volatile private var instance: AdvancedProtectionGate? = null

        /**
         * One instance per process. Each construction registers a system callback that is never
         * unregistered, so building one per composition would leak a callback per recomposition.
         */
        fun get(context: Context): AdvancedProtectionGate =
            instance ?: synchronized(this) {
                instance ?: AdvancedProtectionGate(context.applicationContext).also { instance = it }
            }
    }
}
