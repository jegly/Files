package com.jegly.files.ui.theme

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat
import com.jegly.files.data.AppSettings

/**
 * Ptyxis palettes only — www's Catppuccin, Dracula and Paper (Rosé Pine / Everforest / Kanagawa)
 * scheme builders are deliberately not ported.
 *
 * "system" resolves to Material You. minSdk 37 means dynamic colour is unconditionally
 * available, so www's FallbackDark/FallbackLight pair and its SDK_INT branch are both gone.
 */
@Composable
fun FilesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember(context) { AppSettings.get(context) }

    val themeMode by settings.themeMode.collectAsState()
    val fontFamily by settings.fontFamily.collectAsState()
    val fontSize by settings.fontSize.collectAsState()

    val colorScheme: ColorScheme = when (themeMode) {
        AppSettings.THEME_SYSTEM ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        // Every Ptyxis palette is a dark scheme; darkTheme is deliberately not consulted.
        else -> ptyxisColorScheme(themeMode)
    }

    /*
     * System-bar icon colour, ported from www.
     *
     * enableEdgeToEdge() picks this from the device's dark-mode setting, which is the wrong
     * signal the moment the in-app theme disagrees: a Ptyxis palette on a device in light mode
     * gets dark icons drawn over a near-black bar. Keyed off the resolved scheme's own
     * background luminance rather than a theme-name list, so it stays correct for Material You
     * and for any palette added later.
     */
    val view = LocalView.current
    val lightBars = colorScheme.background.luminance() > 0.5f
    if (!view.isInEditMode) {
        SideEffect {
            val window = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<Activity>()
                .firstOrNull()
                ?.window
            if (window != null) {
                WindowInsetsControllerCompat(window, view).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
        }
    }

    // MaterialExpressiveTheme/MotionScheme.expressive() would be the M3 Expressive equivalent,
    // but that surface is still internal in the stable material3 1.4.0 this project pins —
    // it hasn't graduated to public API yet (looks to be landing in the 1.5.0 alpha line).
    // Plain MaterialTheme is functionally equivalent here since motionScheme has no effect on
    // anything this app currently animates.
    MaterialTheme(
        colorScheme = colorScheme,
        typography = getTypography(fontSize, fontFamily),
        content = content,
    )
}
