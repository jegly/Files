package com.jegly.files.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plain SharedPreferences on purpose.
 *
 * www kept its prefs in EncryptedSharedPreferences because they included browsing state. Nothing
 * here is a secret — a theme key, a font name, and a "lock is armed" flag — and the flag is not
 * a security boundary anyway: the boundary is the Keystore key in [com.jegly.files.security.AppLockKeystore],
 * which the OS refuses to release without biometric auth regardless of what this file says.
 * Encrypting non-secrets would only add a deprecated dependency and the illusion of protection.
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("files_prefs", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(prefs.getString(KEY_THEME, THEME_SYSTEM)!!)
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _fontFamily = MutableStateFlow(prefs.getString(KEY_FONT, DEFAULT_FONT)!!)
    val fontFamily: StateFlow<String> = _fontFamily.asStateFlow()

    private val _fontSize = MutableStateFlow(prefs.getFloat(KEY_FONT_SIZE, 16f))
    val fontSize: StateFlow<Float> = _fontSize.asStateFlow()

    private val _lockBlob = MutableStateFlow(prefs.getString(KEY_LOCK_BLOB, null))
    val lockBlob: StateFlow<String?> = _lockBlob.asStateFlow()

    val lockEnabled: Boolean get() = _lockBlob.value != null

    fun setThemeMode(value: String) {
        _themeMode.value = value
        prefs.edit { putString(KEY_THEME, value) }
    }

    fun setFontFamily(value: String) {
        _fontFamily.value = value
        prefs.edit { putString(KEY_FONT, value) }
    }

    fun setFontSize(value: Float) {
        _fontSize.value = value
        prefs.edit { putFloat(KEY_FONT_SIZE, value) }
    }

    fun armLock(blob: String) {
        _lockBlob.value = blob
        prefs.edit { putString(KEY_LOCK_BLOB, blob) }
    }

    fun disarmLock() {
        _lockBlob.value = null
        prefs.edit { remove(KEY_LOCK_BLOB) }
    }

    companion object {
        const val THEME_SYSTEM = "system"
        const val DEFAULT_FONT = "Courier (Default)"

        private const val KEY_THEME = "theme_mode"
        private const val KEY_FONT = "font_family"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_LOCK_BLOB = "lock_blob"

        @Volatile private var instance: AppSettings? = null

        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also { instance = it }
            }
    }
}
