package com.swordfish.lemuroid.app.mobile.shared.compose.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.systems.MetaSystemInfo
import com.swordfish.lemuroid.common.graphics.ColorUtils
import com.swordfish.lemuroid.lib.R as LibR

@Composable
fun LemuroidSystemImage(system: MetaSystemInfo) {
    LemuroidSystemTile(
        backgroundColor = Color(system.metaSystem.color()),
        imageResId = system.metaSystem.imageResId,
        contentDescriptionResId = system.metaSystem.titleResId,
    )
}

@Composable
fun LemuroidAllSystemsImage() {
    LemuroidSystemTile(
        backgroundColor = Color(ColorUtils.color(hue = 0.58f, saturation = 0.42f, lightness = 0.44f)),
        imageResId = LibR.drawable.game_system_all,
        contentDescriptionResId = R.string.system_all_title,
    )
}

@Composable
private fun LemuroidSystemTile(
    backgroundColor: Color,
    @DrawableRes imageResId: Int,
    @StringRes contentDescriptionResId: Int,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.0f)
                .background(backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            modifier = Modifier.fillMaxSize(0.75f),
            painter = painterResource(id = imageResId),
            contentDescription = stringResource(id = contentDescriptionResId),
            contentScale = ContentScale.FillBounds,
        )
    }
}
