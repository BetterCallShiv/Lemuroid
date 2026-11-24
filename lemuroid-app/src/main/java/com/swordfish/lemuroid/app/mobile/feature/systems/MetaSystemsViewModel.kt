package com.swordfish.lemuroid.app.mobile.feature.systems

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.swordfish.lemuroid.app.shared.systems.MetaSystemInfo
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.metaSystemID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

sealed interface MetaSystemGridItem {
    val key: String

    data class AllSystems(
        val totalCount: Int,
    ) : MetaSystemGridItem {
        override val key: String = "ALL_SYSTEMS"
    }

    data class SingleSystem(
        val info: MetaSystemInfo,
    ) : MetaSystemGridItem {
        override val key: String = info.metaSystem.name
    }
}

class MetaSystemsViewModel(retrogradeDb: RetrogradeDatabase, appContext: Context) : ViewModel() {
    class Factory(
        val retrogradeDb: RetrogradeDatabase,
        val appContext: Context,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MetaSystemsViewModel(retrogradeDb, appContext) as T
        }
    }

    val availableMetaSystems: Flow<List<MetaSystemGridItem>> =
        retrogradeDb.gameDao()
            .selectSystemsWithCount()
            .map { systemCounts ->
                val systemInfos =
                    systemCounts.asSequence()
                        .filter { (_, count) -> count > 0 }
                        .map { (systemId, count) -> GameSystem.findById(systemId).metaSystemID() to count }
                        .groupBy { (metaSystemId, _) -> metaSystemId }
                        .map { (metaSystemId, counts) -> MetaSystemInfo(metaSystemId, counts.sumOf { it.second }) }
                        .sortedBy { it.getName(appContext) }
                        .toList()

                if (systemInfos.isEmpty()) {
                    emptyList()
                } else {
                    val allItem = MetaSystemGridItem.AllSystems(totalCount = systemInfos.sumOf { it.count })
                    listOf(allItem) + systemInfos.map { MetaSystemGridItem.SingleSystem(it) }
                }
            }
}
