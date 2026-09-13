package com.jegly.files.ui

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jegly.files.data.AppSettings
import com.jegly.files.security.AdvancedProtectionGate
import com.jegly.files.security.AppLockKeystore
import com.jegly.files.security.AppLockSession
import com.jegly.files.security.BiometricAuthManager
import com.jegly.files.security.IntegrityChecker
import com.jegly.files.ui.theme.LEGIBILITY_WARNING_FONTS
import com.jegly.files.ui.theme.PTYXIS_THEMES
import com.jegly.files.ui.theme.fontFamilies
import com.jegly.files.ui.theme.ptyxisThemeFromKey
import kotlin.math.roundToInt

/**
 * Everything the rest of the app reads out of [AppSettings] is written here, and nowhere else.
 *
 * The security rows are deliberately not switches-that-lie: the app lock toggle performs a real
 * biometric ceremony before it flips, and the two read-only rows below it report state this app
 * observes but cannot change.
 */
private const val FONT_MIN = 12f
private const val FONT_MAX = 24f
private const val FONT_STEP = 1f

/** Three taps on the version row, each within this long of the last. */
private const val TAPS_TO_REVEAL = 3
private const val TAP_WINDOW_MS = 1_500L

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val settings = remember { AppSettings.get(context) }
    val biometrics = remember { BiometricAuthManager(context) }
    val protection = remember { AdvancedProtectionGate.get(context) }

    val themeMode by settings.themeMode.collectAsStateWithLifecycle()
    val fontFamily by settings.fontFamily.collectAsStateWithLifecycle()
    val fontSize by settings.fontSize.collectAsStateWithLifecycle()
    val itemScale by settings.itemScale.collectAsStateWithLifecycle()
    val lockBlob by settings.lockBlob.collectAsStateWithLifecycle()
    val vaultTimeout by settings.vaultLockTimeout.collectAsStateWithLifecycle()
    val advancedProtection by protection.enabled.collectAsStateWithLifecycle()
    // Reported separately from the boolean above so the row can tell "off" apart from
    // "this build could not read it" instead of printing "Off" for both.
    val protectionState by protection.state.collectAsStateWithLifecycle()

    var showThemes by remember { mutableStateOf(false) }
    var showFonts by remember { mutableStateOf(false) }
    var showVaultTimeouts by remember { mutableStateOf(false) }

    // Three taps on the version row, in quick succession. See SpaceInvaders.
    var versionTaps by remember { mutableStateOf(0) }
    var lastTapAt by remember { mutableStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var lockError by remember { mutableStateOf<String?>(null) }

    /*
     * Every section starts closed. The screen had grown to a dozen rows in one unbroken scroll,
     * which made the three or four anyone actually changes as hard to find as the ones nobody
     * touches twice. Closed-by-default turns it back into a table of contents.
     *
     * One flag each rather than an accordion's single "which one is open": these are
     * independent, and closing Security to look at About is not something to make the user do.
     * Saveable, so a rotation doesn't fold up the section they were reading.
     */
    var appearanceOpen by rememberSaveable { mutableStateOf(false) }
    var securityOpen by rememberSaveable { mutableStateOf(false) }
    var aboutOpen by rememberSaveable { mutableStateOf(false) }

    val signature = remember { IntegrityChecker.verifySignature(context) }
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "—"
    }

    fun armLock() {
        val act: Activity = activity ?: run { lockError = "No Activity host"; return }
        // Inside the try, not before it. This probe is a binder call that can throw, and having
        // it outside meant a SecurityException from it killed the process instead of turning
        // into an error message on the row.
        try {
            biometrics.unavailableReason()?.let { lockError = it; return }
            val cipher = AppLockKeystore.encryptCipher(biometrics.hasStrongBox())
            biometrics.showCryptoPrompt(
                activity = act,
                cipher = cipher,
                title = "Enable app lock",
                subtitle = "Authenticate to bind the lock to this device",
                onSuccess = { sealed ->
                    lockError = null
                    settings.armLock(AppLockKeystore.sealSentinel(sealed))
                    // Arming IS a successful ceremony against the very key that gates the app,
                    // moments ago and with this Activity in front. Without recording it, the
                    // gate turns on behind this screen and immediately demands a second
                    // fingerprint for the same authentication.
                    AppLockSession.markUnlocked()
                },
                onError = { message ->
                    // The key exists but no sentinel was ever sealed with it. Leaving it behind
                    // would mean a later arm silently reuses a key from an abandoned ceremony.
                    AppLockKeystore.deleteKey()
                    lockError = message
                },
            )
        } catch (t: Throwable) {
            AppLockKeystore.deleteKey()
            lockError = t.message ?: "Couldn't create the lock key"
        }
    }

    fun disarmLock() {
        if (advancedProtection) {
            lockError = "Advanced Protection is on — the app lock can't be turned off"
            return
        }
        settings.disarmLock()
        AppLockKeystore.deleteKey()
        lockError = null
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                SectionHeading(
                    text = "Appearance",
                    open = appearanceOpen,
                    onClick = { appearanceOpen = !appearanceOpen },
                )
            }

            if (appearanceOpen) {
                item {
                    ListItem(
                        modifier = Modifier.clickable { showThemes = true },
                        headlineContent = { Text("Theme") },
                        supportingContent = {
                            Text(
                                if (themeMode == AppSettings.THEME_SYSTEM) "System (Material You)"
                                else ptyxisThemeFromKey(themeMode).displayName
                            )
                        },
                        trailingContent = {
                            if (themeMode != AppSettings.THEME_SYSTEM) {
                                Swatches(ptyxisThemeFromKey(themeMode).let {
                                    listOf(it.primary, it.secondary, it.tertiary)
                                })
                            }
                        },
                    )
                }

                item {
                    ListItem(
                        modifier = Modifier.clickable { showFonts = true },
                        headlineContent = { Text("Font") },
                        supportingContent = { Text(fontFamily) },
                    )
                }

                item {
                    StepperRow(
                        title = "Text size",
                        value = "${fontSize.roundToInt()}sp",
                        canDecrease = fontSize > FONT_MIN,
                        canIncrease = fontSize < FONT_MAX,
                        onDecrease = {
                            settings.setFontSize((fontSize - FONT_STEP).coerceAtLeast(FONT_MIN))
                        },
                        onIncrease = {
                            settings.setFontSize((fontSize + FONT_STEP).coerceAtMost(FONT_MAX))
                        },
                        decreaseLabel = "Smaller text",
                        increaseLabel = "Larger text",
                    )
                }

                item {
                    // One control for both view modes, because it is one question: how much of
                    // the screen should a file get. In list view it drives row height and icon
                    // size; in grid view it drives the cell pitch, so a smaller setting fits
                    // more columns rather than leaving the same two with more air in them.
                    StepperRow(
                        title = "Item size",
                        value = "${(itemScale * 100).roundToInt()}% — list rows and grid cells",
                        canDecrease = itemScale > AppSettings.ITEM_SCALE_MIN,
                        canIncrease = itemScale < AppSettings.ITEM_SCALE_MAX,
                        onDecrease = {
                            settings.setItemScale(itemScale - AppSettings.ITEM_SCALE_STEP)
                        },
                        onIncrease = {
                            settings.setItemScale(itemScale + AppSettings.ITEM_SCALE_STEP)
                        },
                        decreaseLabel = "Smaller items",
                        increaseLabel = "Larger items",
                    )
                }
            }

            item {
                HorizontalDivider()
                SectionHeading(
                    text = "Security",
                    open = securityOpen,
                    onClick = { securityOpen = !securityOpen },
                )
            }

            if (securityOpen) {
                item {
                    ListItem(
                        headlineContent = { Text("App lock") },
                        supportingContent = {
                            Text(
                                // An error still gets said in full; the steady states are one
                                // word, because the switch beside them already carries the detail.
                                lockError ?: if (lockBlob != null) "On" else "Off",
                                color = if (lockError != null) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = lockBlob != null,
                                onCheckedChange = { wanted -> if (wanted) armLock() else disarmLock() },
                                enabled = !(advancedProtection && lockBlob != null),
                            )
                        },
                    )
                }

                item {
                    // Vault keys live in memory only, so this is genuinely a question of how long
                    // the app may hold them once you have gone elsewhere — not a stored-credential
                    // setting. Before this existed the answer was a hard "the instant any screen
                    // stops", which is stricter than anyone asked for and broke copying into a
                    // vault outright, since the destination picker is a second screen.
                    ListItem(
                        modifier = Modifier.clickable { showVaultTimeouts = true },
                        headlineContent = { Text("Vault auto-lock") },
                        supportingContent = { Text(vaultTimeoutLabel(vaultTimeout)) },
                    )
                }

                item {
                    StatusRow(
                        title = "Advanced Protection",
                        text = when (protectionState) {
                            AdvancedProtectionGate.State.On -> "On"
                            AdvancedProtectionGate.State.Off -> "Off"
                            // Kept distinct from "Off" rather than folded into it. Reporting a
                            // protection mode as off when it was never actually read is the one
                            // wrong answer here, and it is the bug this row used to have.
                            AdvancedProtectionGate.State.Unavailable -> "Unavailable"
                        },
                        // Off is not a failure state; it's the default and the app can't change it.
                        level = when (protectionState) {
                            AdvancedProtectionGate.State.On -> StatusLevel.Good
                            AdvancedProtectionGate.State.Off -> StatusLevel.Neutral
                            AdvancedProtectionGate.State.Unavailable -> StatusLevel.Neutral
                        },
                    )
                }

                item {
                    StatusRow(
                        title = "App integrity",
                        // NotChecked says so plainly rather than borrowing the reassuring wording.
                        // A build with no expected key pinned has compared nothing, and telling
                        // someone their signature "matches" on that basis is worse than silence.
                        text = when (signature) {
                            IntegrityChecker.Status.Verified ->
                                "Signing certificate matches the expected release key"
                            IntegrityChecker.Status.Mismatch ->
                                "This APK was re-signed — it is not an official build"
                            IntegrityChecker.Status.NotChecked ->
                                "Not checked — this build declares no expected signing key"
                        },
                        level = when (signature) {
                            IntegrityChecker.Status.Verified -> StatusLevel.Good
                            IntegrityChecker.Status.Mismatch -> StatusLevel.Alarm
                            IntegrityChecker.Status.NotChecked -> StatusLevel.Neutral
                        },
                    )
                }
            }

            item {
                HorizontalDivider()
                SectionHeading(
                    text = "About",
                    open = aboutOpen,
                    onClick = { aboutOpen = !aboutOpen },
                )
            }

            if (aboutOpen) {
                item {
                    // Text, not a link. Tapping it would have fired an ACTION_VIEW at whatever
                    // handles http — which needs no permission and does its fetching in the
                    // browser's process, but it is still this app handing an outbound request to
                    // something else, from a file manager whose entire pitch is that it cannot.
                    // The address is just as usable read as tapped.
                    ListItem(
                        headlineContent = { Text("Developer") },
                        supportingContent = { Text("jegly — github.com/jegly/Files") },
                    )
                }

                item {
                    ListItem(
                        modifier = Modifier.clickable {
                            val now = System.currentTimeMillis()
                            // A run of taps, not three taps ever: without the window, one tap a
                            // week for three weeks would open it.
                            versionTaps = if (now - lastTapAt <= TAP_WINDOW_MS) versionTaps + 1 else 1
                            lastTapAt = now
                            if (versionTaps >= TAPS_TO_REVEAL) {
                                versionTaps = 0
                                playing = true
                            }
                        },
                        headlineContent = { Text("Version") },
                        supportingContent = { Text(version) },
                    )
                }
            }
        }
    }

    if (showThemes) {
        ThemeSheet(
            current = themeMode,
            onPick = { settings.setThemeMode(it) },
            onDismiss = { showThemes = false },
        )
    }

    if (showFonts) {
        FontSheet(
            current = fontFamily,
            onPick = { settings.setFontFamily(it) },
            onDismiss = { showFonts = false },
        )
    }

    if (playing) {
        SpaceInvaders(onDismiss = { playing = false })
    }

    if (showVaultTimeouts) {
        VaultTimeoutSheet(
            current = vaultTimeout,
            onPick = { settings.setVaultLockTimeout(it) },
            onDismiss = { showVaultTimeouts = false },
        )
    }
}

/** A section's title and its disclosure control — the whole row is the hit target. */
@Composable
private fun SectionHeading(text: String, open: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Rounded.ExpandMore,
            // The chevron is the state indicator, so it must not also claim to be the label.
            contentDescription = if (open) "Collapse $text" else "Expand $text",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.rotate(if (open) 180f else 0f),
        )
    }
}

/**
 * A value with a minus/plus pair. Two settings needed the identical row and the second one was
 * about to be a copy of the first.
 */
@Composable
private fun StepperRow(
    title: String,
    value: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    decreaseLabel: String,
    increaseLabel: String,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDecrease, enabled = canDecrease) {
                    Icon(Icons.Rounded.Remove, contentDescription = decreaseLabel)
                }
                IconButton(onClick = onIncrease, enabled = canIncrease) {
                    Icon(Icons.Rounded.Add, contentDescription = increaseLabel)
                }
            }
        },
    )
}

/** Offered timeouts, shortest first. Values are milliseconds. */
private val VAULT_TIMEOUTS = listOf(
    0L,
    30_000L,
    AppSettings.DEFAULT_VAULT_LOCK_TIMEOUT,
    5 * 60_000L,
    15 * 60_000L,
    AppSettings.VAULT_LOCK_NEVER,
)

private fun vaultTimeoutLabel(ms: Long): String = when (ms) {
    AppSettings.VAULT_LOCK_NEVER -> "Only when the app is closed"
    0L -> "Immediately on leaving the app"
    else -> {
        val seconds = ms / 1000
        if (seconds < 60) "$seconds seconds after leaving the app"
        else {
            val minutes = seconds / 60
            "$minutes ${plural(minutes.toInt(), "minute")} after leaving the app"
        }
    }
}

@Composable
private fun VaultTimeoutSheet(current: Long, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
            items(VAULT_TIMEOUTS) { value ->
                PickRow(
                    label = vaultTimeoutLabel(value),
                    // Never is not "no lock": the keys are memory-only, so the process ending —
                    // which Android does on its own schedule — still closes every vault. Saying
                    // so keeps the strongest-sounding option from reading as a promise it can't
                    // make either way.
                    supporting = when (value) {
                        AppSettings.VAULT_LOCK_NEVER ->
                            "Keys are never written to disk, so they still go when Android ends the process"
                        0L -> "Strictest — note that Copy to… briefly leaves the browser screen"
                        else -> null
                    },
                    selected = current == value,
                    onClick = { onPick(value); onDismiss() },
                )
            }
        }
    }
}

/** Read-only security facts. [Neutral] is "not enabled", [Alarm] is "something is wrong". */
private enum class StatusLevel { Good, Neutral, Alarm }

@Composable
private fun StatusRow(title: String, text: String, level: StatusLevel) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(text) },
        leadingContent = {
            Icon(
                if (level == StatusLevel.Alarm) Icons.Rounded.Warning else Icons.Rounded.Shield,
                contentDescription = null,
                tint = when (level) {
                    StatusLevel.Good -> MaterialTheme.colorScheme.primary
                    StatusLevel.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
                    StatusLevel.Alarm -> MaterialTheme.colorScheme.error
                },
            )
        },
    )
}

@Composable
private fun Swatches(colors: List<Long>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        colors.forEach {
            Box(
                Modifier
                    .size(14.dp)
                    .background(Color(it), CircleShape)
            )
        }
    }
}

@Composable
private fun ThemeSheet(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
            item {
                PickRow(
                    label = "System (Material You)",
                    // The only entry that follows the device's light/dark setting; every Ptyxis
                    // palette is a dark scheme by construction.
                    supporting = "Follows your wallpaper and dark mode setting",
                    selected = current == AppSettings.THEME_SYSTEM,
                    onClick = { onPick(AppSettings.THEME_SYSTEM); onDismiss() },
                )
            }
            items(PTYXIS_THEMES) { theme ->
                PickRow(
                    label = theme.displayName,
                    supporting = null,
                    selected = current == theme.key,
                    onClick = { onPick(theme.key); onDismiss() },
                    trailing = {
                        Swatches(listOf(theme.primary, theme.secondary, theme.tertiary, theme.error))
                    },
                )
            }
        }
    }
}

@Composable
private fun FontSheet(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
            items(fontFamilies.keys.toList()) { name ->
                PickRow(
                    label = name,
                    supporting = if (name in LEGIBILITY_WARNING_FONTS) {
                        "Display face — hard to read as body text"
                    } else null,
                    selected = current == name,
                    onClick = { onPick(name); onDismiss() },
                    // Render each name in its own face, so the list is its own specimen sheet.
                    labelStyle = TextStyle(fontFamily = fontFamilies[name], fontSize = 16.sp),
                )
            }
        }
    }
}

@Composable
private fun PickRow(
    label: String,
    supporting: String?,
    selected: Boolean,
    onClick: () -> Unit,
    labelStyle: TextStyle? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = labelStyle ?: MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            supporting?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}
