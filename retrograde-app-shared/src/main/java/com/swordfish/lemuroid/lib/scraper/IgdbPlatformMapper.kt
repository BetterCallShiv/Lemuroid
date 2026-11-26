package com.swordfish.lemuroid.lib.scraper

import com.swordfish.lemuroid.lib.library.SystemID

object IgdbPlatformMapper {
    fun getPlatformId(systemID: SystemID): Int? {
        return when (systemID) {
            SystemID.NES -> 18
            SystemID.SNES -> 19
            SystemID.GENESIS -> 29
            SystemID.GB -> 33
            SystemID.GBC -> 22
            SystemID.GBA -> 24
            SystemID.N64 -> 4
            SystemID.SMS -> 64
            SystemID.PSP -> 38
            SystemID.NDS -> 20
            SystemID.GG -> 35
            SystemID.ATARI2600 -> 59
            SystemID.PSX -> 7
            SystemID.FBNEO -> 52
            SystemID.MAME2003PLUS -> 52
            SystemID.PC_ENGINE -> 86
            SystemID.LYNX -> 61
            SystemID.ATARI7800 -> 60
            SystemID.SEGACD -> 78
            SystemID.NGP -> 119
            SystemID.NGC -> 120
            SystemID.WS -> 57
            SystemID.WSC -> 123
            SystemID.DOS -> 13
            SystemID.NINTENDO_3DS -> 37
        }
    }

    fun getPlatformId(systemIdString: String): Int? {
        val systemID = runCatching {
            SystemID.values().firstOrNull {
                it.dbname.equals(systemIdString, ignoreCase = true) ||
                    it.name.equals(systemIdString, ignoreCase = true)
            }
        }.getOrNull()
        return systemID?.let { getPlatformId(it) }
    }
}

