package com.jegly.files.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the biometric app lock has been satisfied, for as long as the app is on screen.
 *
 * PROCESS-WIDE, NOT PER-SCREEN, AND THAT IS THE WHOLE POINT. This used to be a `var unlocked`
 * inside [com.jegly.files.ui.LockGate], reset on that composition's ON_STOP — which meant each
 * Activity kept its own idea of whether the user had authenticated. Opening the destination
 * picker behind "Copy to…" stops the browser Activity and starts a second one, so a single copy
 * demanded a fingerprint on the way in and another on the way back, neither of them protecting
 * anything: the app never left the foreground and the storage the lock guards was already on
 * screen. Counting started screens here makes a hand-off between our own Activities a non-event,
 * exactly as it is for [VaultSession].
 *
 * The security property is unchanged. Leaving the app really does re-arm the lock — the count
 * reaches zero and [unlocked] goes false the moment the last screen stops, with no grace period,
 * because a lock that only ever challenges you once is theatre. Nothing here is persisted, so
 * the process ending re-arms it too.
 *
 * Deliberately NOT the same counter as [VaultSession]'s. That one is fed by the browser screen
 * and so drops to zero while this lock screen is up, which is correct: the app being visible but
 * unauthenticated is exactly when open vaults should be closing. Two questions, two counts.
 */
object AppLockSession {

    private val _unlocked = MutableStateFlow(false)

    /** True once a biometric ceremony has succeeded and the app has not left the foreground. */
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    private val gate = Any()
    private var startedScreens = 0

    /** Records a successful authentication. Only the ceremony's own success path may call this. */
    fun markUnlocked() { _unlocked.value = true }

    fun noteForeground() {
        synchronized(gate) { startedScreens++ }
    }

    fun noteBackground() {
        synchronized(gate) {
            if (startedScreens == 0) return
            startedScreens--
            if (startedScreens == 0) _unlocked.value = false
        }
    }
}
