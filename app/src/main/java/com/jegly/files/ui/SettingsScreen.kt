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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val lockBlob by settings.lockBlob.collectAsStateWithLifecycle()
    val advancedProtection by protection.enabled.collectAsStateWithLifecycle()

    var showThemes by remember { mutableStateOf(false) }
    var showFonts by remember { mutableStateOf(false) }
    var lockError by remember { mutableStateOf<String?>(null) }

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
                subtitle = "Authenticate to bind the lock to your biometrics",
                onSuccess = { sealed ->
                    lockError = null
                    settings.armLock(AppLockKeystore.sealSentinel(sealed))
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
            item { SectionHeading("Appearance") }

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
                ListItem(
                    headlineContent = { Text("Text size") },
                    supportingContent = { Text("${fontSize.roundToInt()}sp") },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    settings.setFontSize((fontSize - FONT_STEP).coerceAtLeast(FONT_MIN))
                                },
                                enabled = fontSize > FONT_MIN,
                            ) {
                                Icon(Icons.Rounded.Remove, contentDescription = "Smaller text")
                            }
                            IconButton(
                                onClick = {
                                    settings.setFontSize((fontSize + FONT_STEP).coerceAtMost(FONT_MAX))
                                },
                                enabled = fontSize < FONT_MAX,
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = "Larger text")
                            }
                        }
                    },
                )
            }

            item { HorizontalDivider(); SectionHeading("Security") }

            item {
                ListItem(
                    headlineContent = { Text("App lock") },
                    supportingContent = {
                        Text(
                            lockError
                                ?: if (lockBlob != null) {
                                    "Requires a strong biometric to open Files"
                                } else {
                                    "Off — anyone with the phone unlocked can browse your storage"
                                },
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
                StatusRow(
                    title = "Advanced Protection",
                    text = if (advancedProtection) {
                        "On — the app lock is pinned on and deletes are called out as permanent"
                    } else {
                        "Off — this is a device-wide Android setting, not an app one"
                    },
                    // Off is not a failure state; it's the default and the app can't change it.
                    level = if (advancedProtection) StatusLevel.Good else StatusLevel.Neutral,
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

            item {
                StatusRow(
                    title = "Network access",
                    text = "None. This app holds no INTERNET permission, so it cannot phone home.",
                    level = StatusLevel.Good,
                )
            }

            item { HorizontalDivider(); SectionHeading("About") }

            item {
                ListItem(
                    headlineContent = { Text("Version") },
                    supportingContent = { Text(version) },
                )
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
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
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
