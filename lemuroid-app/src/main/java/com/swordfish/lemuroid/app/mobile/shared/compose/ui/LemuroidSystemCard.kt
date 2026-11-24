package com.swordfish.lemuroid.app.mobile.shared.compose.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ElevatedCard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.systems.MetaSystemInfo

@Composable
fun LemuroidSystemCard(
    modifier: Modifier = Modifier,
    system: MetaSystemInfo,
    onClick: () -> Unit,
) {
    val context = LocalContext.current

    val title =
        remember(system.metaSystem.titleResId) {
            system.getName(context)
        }

    val subtitle =
        remember(system.metaSystem.titleResId, system.count) {
            context.getString(
                R.string.system_grid_details,
                system.count.toString(),
            )
        }

    ElevatedCard(
        modifier = modifier,
        onClick = onClick,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth(),
        ) {
            LemuroidSystemImage(system)
            LemuroidTexts(title = title, subtitle = subtitle)
        }
    }
}

@Composable
fun LemuroidAllSystemsCard(
    modifier: Modifier = Modifier,
    totalCount: Int,
    onClick: () -> Unit,
) {
    val title = stringResource(R.string.system_all_title)
    val subtitle = stringResource(R.string.system_grid_details, totalCount.toString())

    ElevatedCard(
        modifier = modifier,
        onClick = onClick,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth(),
        ) {
            LemuroidAllSystemsImage()
            LemuroidTexts(title = title, subtitle = subtitle)
        }
    }
}
