package com.swordfish.lemuroid.app.shared.retroachievements

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper

data class CachedGameSummary(
    val title: String,
    val unlockedAchievements: Int,
    val totalAchievements: Int,
    val unlockedPoints: Int,
    val totalPoints: Int,
    val badgeUrl: String,
)

class RetroAchievementsPreferences(context: Context) {
    private val prefs: SharedPreferences = SharedPreferencesHelper.getSharedPreferences(context)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var username: String
        get() = prefs.getString(KEY_USERNAME, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_USERNAME, value).commit()
        }

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_TOKEN, value).commit()
        }

    var hardcore: Boolean
        get() = prefs.getBoolean(KEY_HARDCORE, false)
        set(value) = prefs.edit().putBoolean(KEY_HARDCORE, value).apply()

    fun getCachedGameId(vararg keys: String): Int? {
        for (k in keys) {
            if (k.isEmpty()) continue
            val id = prefs.getInt(KEY_CACHE_GAME_ID_PREFIX + k, -1)
            if (id > 0) return id
        }
        return null
    }

    fun setCachedGameId(keys: List<String>, gameId: Int) {
        val editor = prefs.edit()
        for (k in keys) {
            if (k.isNotEmpty()) {
                editor.putInt(KEY_CACHE_GAME_ID_PREFIX + k, gameId)
            }
        }
        editor.apply()
    }

    fun getCachedGameSummary(vararg keys: String): CachedGameSummary? {
        for (k in keys) {
            if (k.isEmpty()) continue
            val raw = prefs.getString(KEY_CACHE_SUMMARY_PREFIX + k, null)
            if (raw != null) {
                val parts = raw.split("|||")
                if (parts.size >= 6) {
                    try {
                        return CachedGameSummary(
                            title = parts[0],
                            unlockedAchievements = parts[1].toInt(),
                            totalAchievements = parts[2].toInt(),
                            unlockedPoints = parts[3].toInt(),
                            totalPoints = parts[4].toInt(),
                            badgeUrl = parts[5],
                        )
                    } catch (ignored: Exception) {}
                }
            }
        }
        return null
    }

    fun setCachedGameSummary(
        keys: List<String>,
        title: String,
        unlockedAchievements: Int,
        totalAchievements: Int,
        unlockedPoints: Int,
        totalPoints: Int,
        badgeUrl: String,
    ) {
        val raw = "$title|||$unlockedAchievements|||$totalAchievements|||$unlockedPoints|||$totalPoints|||$badgeUrl"
        val editor = prefs.edit()
        for (k in keys) {
            if (k.isNotEmpty()) {
                editor.putString(KEY_CACHE_SUMMARY_PREFIX + k, raw)
            }
        }
        editor.apply()
    }

    fun getCachedGameHash(gamePath: String): String? {
        return prefs.getString(KEY_CACHE_HASH_PREFIX + gamePath, null)
    }

    fun setCachedGameHash(gamePath: String, hash: String) {
        prefs.edit().putString(KEY_CACHE_HASH_PREFIX + gamePath, hash).apply()
    }

    var cachedSyncValue: String
        get() {
            val encoded = prefs.getString(KEY_SYNC_VALUE, null) ?: return ""
            return runCatching {
                String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8)
            }.getOrDefault("")
        }
        set(value) {
            if (value.isBlank()) {
                prefs.edit().remove(KEY_SYNC_VALUE).apply()
            } else {
                val encoded = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                prefs.edit().putString(KEY_SYNC_VALUE, encoded).apply()
            }
        }

    var cachedSyncStamp: Long
        get() = prefs.getLong(KEY_SYNC_STAMP, 0L)
        set(value) {
            prefs.edit().putLong(KEY_SYNC_STAMP, value).apply()
        }

    fun clearCachedSyncValue() {
        prefs.edit().remove(KEY_SYNC_VALUE).remove(KEY_SYNC_STAMP).apply()
    }

    companion object {
        const val KEY_ENABLED = "pref_retroachievements_enabled"
        const val KEY_USERNAME = "pref_retroachievements_username"
        const val KEY_TOKEN = "pref_retroachievements_token"
        const val KEY_HARDCORE = "pref_retroachievements_hardcore"
        private const val KEY_CACHE_SUMMARY_PREFIX = "ra_cache_summary_"
        private const val KEY_CACHE_GAME_ID_PREFIX = "ra_cache_game_id_"
        private const val KEY_CACHE_HASH_PREFIX = "ra_cache_hash_"
        private const val KEY_SYNC_VALUE = "cfg_x9k4m2_v1"
        private const val KEY_SYNC_STAMP = "cfg_p7r3q8_ts"
    }
}
