package com.jegly.files.security

import android.annotation.SuppressLint
import android.content.Context
import android.security.advancedprotection.AdvancedProtectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ported from www. That version needed an SDK_INT>=36 guard with every reference to
 * AdvancedProtectionManager confined to a guarded method, because its minSdk was 33 and a field
 * typed with an absent class risks eager verifier resolution at class load.
 *
 * minSdk 37 makes all of that unnecessary — the class is guaranteed present, so the type can be
 * referenced directly.
 *
 * Read-only awareness, not control: setAdvancedProtectionEnabled() is @SystemApi and reserved
 * for Settings. When AAPM is on, this app hides destructive shortcuts and forces the app lock on.
 */
class AdvancedProtectionGate private constructor(context: Context) {

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    init {
        observe(context)
    }

    /**
     * Suppressed rather than fixed, and the manifest deliberately does not declare
     * QUERY_ADVANCED_PROTECTION_MODE.
     *
     * Lint's MissingPermission check warns that an ungranted permission makes these calls throw
     * at runtime — which is true, and is precisely the case both runCatching blocks exist to
     * absorb. The permission is not grantable to a third-party app on a normal build, so
     * declaring it would put a line in the manifest that can never be granted, in an app whose
     * permission list is short on purpose and is the first thing a suspicious user reads.
     * Absence is the expected path here, not the error path: staying false means the app simply
     * does not apply the extra hardening.
     */
    @SuppressLint("MissingPermission")
    private fun observe(context: Context) {
        runCatching {
            val manager = context.getSystemService(AdvancedProtectionManager::class.java)
                ?: return@runCatching
            // A plain read first: callback registration is the more heavily guarded of the two,
            // so on builds that allow only the read we still report the correct state, just
            // without live updates.
            runCatching { _enabled.value = manager.isAdvancedProtectionEnabled }
            // Fires once immediately with current state, so no separate initial read is needed.
            manager.registerAdvancedProtectionCallback(context.mainExecutor) { isEnabled ->
                _enabled.value = isEnabled
            }
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
