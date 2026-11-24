package com.swordfish.lemuroid.lib.saves

data class SaveInfo(
    val exists: Boolean,
    val date: Long,
    val slotIndex: Int = 0,
    val customTitle: String? = null,
)
