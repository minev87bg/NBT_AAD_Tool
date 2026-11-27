package com.nbtaad.editor.data

import android.content.Context

/**
 * The per-car root SSH private key for an MGU (iDrive 7/8) head unit, pasted in by the person. This app never
 * generates or obtains this key itself - it is produced once by a separate tool the person runs themselves, the
 * same way mgu-store's own installer takes one with its `--identity <key file>` option. Kept in the app's own
 * private storage, same protection level as the rest of this app's settings (ThemePrefs, CommunityPrefs).
 */
class MguKeyStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("mgu_prefs", Context.MODE_PRIVATE)

    fun readKey(): String? = prefs.getString(KEY_PEM, null)?.ifBlank { null }

    fun writeKey(pem: String) {
        prefs.edit().putString(KEY_PEM, pem.trim()).apply()
    }

    fun clearKey() {
        prefs.edit().remove(KEY_PEM).apply()
    }

    fun readHostOverride(): String = prefs.getString(KEY_HOST, null) ?: ""

    fun writeHostOverride(host: String) {
        prefs.edit().putString(KEY_HOST, host.trim()).apply()
    }

    companion object {
        private const val KEY_PEM = "root_key_pem"
        private const val KEY_HOST = "host_override"
    }
}
