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

    private val _fontSize = MutableStateFlow(prefs.getFloat(KEY_FONT_SIZE, DEFAULT_FONT_SIZE))
    val fontSize: StateFlow<Float> = _fontSize.asStateFlow()

    private val _lockBlob = MutableStateFlow(prefs.getString(KEY_LOCK_BLOB, null))
    val lockBlob: StateFlow<String?> = _lockBlob.asStateFlow()

    /**
     * How long a vault stays unlocked after the app leaves the foreground, in milliseconds.
     * [VAULT_LOCK_NEVER] keeps it open until the process dies; 0 locks the moment the last
     * screen stops.
     */
    private val _vaultLockTimeout =
        MutableStateFlow(prefs.getLong(KEY_VAULT_LOCK_TIMEOUT, DEFAULT_VAULT_LOCK_TIMEOUT))
    val vaultLockTimeout: StateFlow<Long> = _vaultLockTimeout.asStateFlow()

    /**
     * Read at lifecycle-event time rather than collected, because the lock decision happens in a
     * lifecycle observer that must see the value as it is *now*, not as it was when the
     * enclosing composition last ran.
     */
    val vaultLockTimeoutMs: Long get() = _vaultLockTimeout.value

    /** Multiplier on row height, icon size and grid cell width. 1.0 is the stock density. */
    private val _itemScale = MutableStateFlow(prefs.getFloat(KEY_ITEM_SCALE, 1f))
    val itemScale: StateFlow<Float> = _itemScale.asStateFlow()

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

    fun setVaultLockTimeout(value: Long) {
        _vaultLockTimeout.value = value
        prefs.edit { putLong(KEY_VAULT_LOCK_TIMEOUT, value) }
    }

    fun setItemScale(value: Float) {
        val clamped = value.coerceIn(ITEM_SCALE_MIN, ITEM_SCALE_MAX)
        _itemScale.value = clamped
        prefs.edit { putFloat(KEY_ITEM_SCALE, clamped) }
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
        const val DEFAULT_FONT_SIZE = 16f

        /** Sentinel for "stay unlocked until the process ends". */
        const val VAULT_LOCK_NEVER = -1L

        /**
         * A minute, not zero. Locking the instant the last screen stops sounds stricter but is
         * not: the destination picker behind "Copy to…" is a second Activity, so an instant lock
         * closed the vault the user had just unlocked, in the middle of copying into it.
         */
        const val DEFAULT_VAULT_LOCK_TIMEOUT = 60_000L

        const val ITEM_SCALE_MIN = 0.7f
        const val ITEM_SCALE_MAX = 1.4f
        const val ITEM_SCALE_STEP = 0.1f

        private const val KEY_THEME = "theme_mode"
        private const val KEY_FONT = "font_family"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_LOCK_BLOB = "lock_blob"
        private const val KEY_VAULT_LOCK_TIMEOUT = "vault_lock_timeout"
        private const val KEY_ITEM_SCALE = "item_scale"

        @Volatile private var instance: AppSettings? = null

        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also { instance = it }
            }
    }
}
