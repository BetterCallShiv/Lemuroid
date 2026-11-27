package com.swordfish.lemuroid.lib.library

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

object SystemRomSizeCache {
    private const val PREFS_NAME = "library_system_rom_sizes"
    private const val KEY_SIZES_JSON = "sizes_json"
    private const val KEY_HAS_CACHE = "has_cache"

    private val _sizesFlow = MutableStateFlow<Map<String, Long>?>(null)
    val sizesFlow: StateFlow<Map<String, Long>?> = _sizesFlow.asStateFlow()

    fun getSizes(context: Context): Map<String, Long>? {
        _sizesFlow.value?.let { return it }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_HAS_CACHE, false)) {
            return null
        }
        val raw = prefs.getString(KEY_SIZES_JSON, null) ?: return emptyMap()
        val parsed = runCatching {
            val json = JSONObject(raw)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = json.optLong(key, 0L)
                    if (value > 0L) {
                        put(key, value)
                    }
                }
            }
        }.getOrNull() ?: return null
        _sizesFlow.value = parsed
        return parsed
    }

    fun saveSizes(context: Context, sizes: Map<String, Long>) {
        val clean = sizes.filterValues { it > 0L }
        val json = JSONObject()
        clean.forEach { (systemId, bytes) ->
            json.put(systemId, bytes)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SIZES_JSON, json.toString())
            .putBoolean(KEY_HAS_CACHE, true)
            .apply()
        _sizesFlow.value = clean
    }
}
