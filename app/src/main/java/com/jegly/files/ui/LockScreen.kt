package com.jegly.files.ui

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jegly.files.data.AppSettings
import com.jegly.files.security.AppLockKeystore
import com.jegly.files.security.AppLockSession
import com.jegly.files.security.BiometricAuthManager

/**
 * Biometric crypto lock, ported from www's UnlockScreen but reduced to the biometric path —
 * www's passcode and pattern fallbacks are not carried over, because here the Keystore key is
 * the only thing gating access and a PIN fallback would have to bypass it to be useful.
 *
 * If biometric enrollment changed, the Keystore key is destroyed by the OS. That surfaces as
 * KeyPermanentlyInvalidated, and the correct response is to fail closed: drop the lock blob and
 * make the user re-arm deliberately.
 */
@Composable
fun LockGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val settings = remember { AppSettings.get(context) }
    val biometrics = remember { BiometricAuthManager(context) }

    val blob by settings.lockBlob.collectAsStateWithLifecycle()
    // Held in AppLockSession, not here: every Activity composes its own LockGate, and a local
    // flag made each one demand its own fingerprint — so "Copy to…", which is just a second
    // Activity of this same app, cost two prompts for one copy.
    val unlocked by AppLockSession.unlocked.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }
    var prompting by remember { mutableStateOf(false) }

    /*
     * A lock that only challenges on cold start is theatre — anyone who finds the phone with the
     * task still in recents walks straight in. Re-arm whenever the app leaves the foreground:
     * AppLockSession counts started screens and re-arms the moment the count reaches zero, so
     * leaving still locks immediately while a hand-off between our own screens does not.
     *
     * `counted` keeps the pair balanced. Adding an observer to an already-started lifecycle
     * replays ON_START at once, and leaving the composition never delivers the matching ON_STOP,
     * so an unguarded pair would drift the count upward and the lock would stop re-arming.
     */
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var counted = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!counted) {
                    counted = true
                    AppLockSession.noteForeground()
                }

                Lifecycle.Event.ON_STOP -> if (counted) {
                    counted = false
                    AppLockSession.noteBackground()
                    // The system dismisses the prompt when the app goes away, and this flag is
                    // what stops a second one being raised over the first. Left true, the next
                    // attempt would be swallowed and the screen would sit there unable to ask.
                    prompting = false
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (counted) {
                counted = false
                AppLockSession.noteBackground()
            }
        }
    }

    if (blob == null || unlocked) {
        content()
        return
    }

    fun attempt() {
        val currentBlob = blob ?: return
        val act: Activity = activity ?: run { error = "Lock needs an Activity host"; return }
        if (prompting) return
        prompting = true
        try {
            val cipher = AppLockKeystore.decryptCipher(currentBlob)
            biometrics.showCryptoPrompt(
                activity = act,
                cipher = cipher,
                title = "Unlock Files",
                subtitle = "Authenticate to browse your storage",
                onSuccess = { unlockedCipher ->
                    prompting = false
                    if (AppLockKeystore.openSentinel(unlockedCipher, currentBlob)) {
                        AppLockSession.markUnlocked()
                        error = null
                    } else {
                        error = "Verification failed"
                    }
                },
                onError = { message ->
                    prompting = false
                    error = message
                },
            )
        } catch (e: AppLockKeystore.KeyPermanentlyInvalidated) {
            prompting = false
            settings.disarmLock()
            error = "Biometrics changed — the lock was cleared. Re-arm it in Settings."
        } catch (t: Throwable) {
            prompting = false
            error = t.message ?: "Couldn't start the unlock prompt"
        }
    }

    LaunchedEffect(blob, unlocked) { if (!unlocked) attempt() }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Rounded.Fingerprint,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text("Files is locked", style = MaterialTheme.typography.headlineSmall)
        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(onClick = ::attempt) { Text("Unlock") }
    }
}
