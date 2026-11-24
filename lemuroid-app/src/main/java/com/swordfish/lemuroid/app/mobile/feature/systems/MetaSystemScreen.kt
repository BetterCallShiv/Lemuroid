package com.swordfish.lemuroid.app.mobile.feature.systems

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import com.swordfish.lemuroid.app.mobile.feature.games.GamesViewModel
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidAllSystemsCard
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidEmptyView
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidSystemCard

@Composable
fun MetaSystemsScreen(
    modifier: Modifier = Modifier,
    navController: NavController,
    viewModel: MetaSystemsViewModel,
) {
    val metaSystems = viewModel.availableMetaSystems.collectAsState(emptyList())
    MetaSystemsScreen(
        modifier = modifier,
        metaSystems = metaSystems.value,
        onItemClicked = { item ->
            if (navController.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
                val routeId =
                    when (item) {
                        is MetaSystemGridItem.AllSystems -> GamesViewModel.ALL_SYSTEMS_ID
                        is MetaSystemGridItem.SingleSystem -> item.info.metaSystem.name
                    }
                navController.navigate("systems/$routeId") {
                    launchSingleTop = true
                }
            }
        },
    )
}

@Composable
private fun MetaSystemsScreen(
    modifier: Modifier = Modifier,
    metaSystems: List<MetaSystemGridItem>,
    onItemClicked: (MetaSystemGridItem) -> Unit,
) {
    if (metaSystems.isEmpty()) {
        LemuroidEmptyView()
        return
    }

    LazyVerticalGrid(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        columns = GridCells.Adaptive(144.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(metaSystems, key = { it.key }) { item ->
            when (item) {
                is MetaSystemGridItem.AllSystems ->
                    LemuroidAllSystemsCard(
                        modifier = Modifier.animateItem(),
                        totalCount = item.totalCount,
                        onClick = { onItemClicked(item) },
                    )

                is MetaSystemGridItem.SingleSystem ->
                    LemuroidSystemCard(
                        modifier = Modifier.animateItem(),
                        system = item.info,
                        onClick = { onItemClicked(item) },
                    )
            }
        }
    }
}
