package com.jegly.files.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jegly.files.model.FileEntry
import com.jegly.files.model.PickRequest
import com.jegly.files.vm.BrowserViewModel
import kotlinx.coroutines.launch

/**
 * Gates, then screens.
 *
 * Both gates wrap everything rather than sitting inside the browser: the lock has to cover
 * Settings too, or the one screen that can disarm the lock would be reachable without passing it.
 *
 * The roots drawer lives here rather than inside BrowserScreen because it's chrome shared with
 * wherever navigation goes next — matching com.android.documentsui, where the roots list is
 * host-level UI, not something owned by the directory-listing screen itself.
 *
 * There are two screens and no deep links, so screen selection is a boolean rather than a nav
 * graph — a navigation dependency would earn its keep at the third destination, not the second.
 */
@Composable
fun AppRoot(
    vm: BrowserViewModel,
    /** Non-null when hosted by [com.jegly.files.PickActivity] to serve a pick request. */
    pick: PickRequest? = null,
    onPicked: (List<FileEntry>) -> Unit = {},
    /** Destination-mode only: the user confirmed the folder they're currently browsing. */
    onDestinationChosen: (java.io.File) -> Unit = {},
) {
    PermissionGate {
        LockGate {
            NotificationPermission()

            val roots by vm.roots.collectAsStateWithLifecycle()
            val state by vm.state.collectAsStateWithLifecycle()
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            val activity = LocalActivity.current

            // Standard drawer idiom: back closes the drawer instead of leaving the screen,
            // and only takes over while the drawer is actually open.
            BackHandler(enabled = drawerState.isOpen) {
                scope.launch { drawerState.close() }
            }

            var settingsOpen by rememberSaveable { mutableStateOf(false) }

            if (settingsOpen) {
                BackHandler { settingsOpen = false }
                SettingsScreen(onBack = { settingsOpen = false })
            } else {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        RootsDrawerContent(
                            roots = roots,
                            currentRoot = state.volumeRoot.absolutePath,
                            onPick = { root ->
                                vm.switchVolume(root)
                                scope.launch { drawerState.close() }
                            },
                        )
                    },
                ) {
                    BrowserScreen(
                        vm = vm,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        // Settings is a place to wander off to; a picker invoked by another app
                        // should hand back a file and end, not offer a detour into preferences.
                        onOpenSettings = { settingsOpen = true }.takeIf { pick == null },
                        pick = pick,
                        onPicked = onPicked,
                        onDestinationChosen = onDestinationChosen,
                        // Stand down while the drawer owns Back, or this screen's handler — being
                        // registered later, and so dispatched first — swallows it.
                        backEnabled = !drawerState.isOpen,
                        // Out of directory history: leave, which for a picker is the cancel that
                        // hands RESULT_CANCELED back to whoever invoked it.
                        onBackExhausted = { activity?.finish() },
                    )
                }
            }
        }
    }
}

/**
 * Asked for once, after the storage gate, and never insisted on.
 *
 * The copy/move/delete service runs either way — POST_NOTIFICATIONS only decides whether its
 * foreground notification is visible. Denying it costs the user progress visibility while they
 * are outside the app, not the operation itself, so a refusal is simply accepted.
 */
@Composable
private fun NotificationPermission() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Either answer is fine; nothing downstream branches on it. */ }

    LaunchedEffect(Unit) {
        val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
