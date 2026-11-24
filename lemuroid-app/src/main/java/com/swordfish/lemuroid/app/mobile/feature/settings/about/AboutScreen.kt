package com.swordfish.lemuroid.app.mobile.feature.settings.about

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.swordfish.lemuroid.BuildConfig
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.lib.library.SystemID
import de.charlex.compose.material3.HtmlText

@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var showHelpDialog by remember { mutableStateOf(false) }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AboutAppHeaderCard()

        AboutCardGroup(title = stringResource(id = R.string.about_category_help)) {
            AboutItemRow(
                icon = Icons.AutoMirrored.Outlined.HelpOutline,
                title = stringResource(id = R.string.about_help_title),
                subtitle = stringResource(id = R.string.about_help_subtitle),
                onClick = { showHelpDialog = true },
            )
            AboutItemRow(
                icon = Icons.Outlined.Description,
                title = stringResource(id = R.string.about_license_title),
                subtitle = stringResource(id = R.string.about_license_subtitle),
                onClick = { openExternalUri(context, "https://www.gnu.org/licenses/gpl-3.0.html") },
            )
        }

        AboutCardGroup(title = stringResource(id = R.string.about_category_developer)) {
            AboutItemRow(
                icon = Icons.Outlined.Person,
                title = stringResource(id = R.string.about_author_title),
                subtitle = stringResource(id = R.string.about_author_subtitle),
                onClick = { openExternalUri(context, "https://github.com/BetterCallShiv") },
            )
            AboutItemRow(
                icon = Icons.Outlined.Code,
                title = stringResource(id = R.string.about_source_title),
                subtitle = stringResource(id = R.string.about_source_subtitle),
                onClick = { openExternalUri(context, "https://github.com/BetterCallShiv/Lemuroid") },
            )
        }

        AboutCardGroup(title = stringResource(id = R.string.about_category_credits)) {
            AboutItemRow(
                icon = Icons.Outlined.FavoriteBorder,
                title = stringResource(id = R.string.about_credit_swordfish_title),
                subtitle = stringResource(id = R.string.about_credit_swordfish_subtitle),
                onClick = { openExternalUri(context, "https://github.com/Swordfish90/Lemuroid") },
            )
            AboutItemRow(
                icon = Icons.Outlined.Extension,
                title = stringResource(id = R.string.about_credit_libretro_title),
                subtitle = stringResource(id = R.string.about_credit_libretro_subtitle),
                onClick = { openExternalUri(context, "https://www.libretro.com/") },
            )
            AboutItemRow(
                icon = Icons.Outlined.EmojiEvents,
                title = stringResource(id = R.string.about_credit_ra_title),
                subtitle = stringResource(id = R.string.about_credit_ra_subtitle),
                onClick = { openExternalUri(context, "https://retroachievements.org/") },
            )
            AboutItemRow(
                icon = Icons.Outlined.Image,
                title = stringResource(id = R.string.about_credit_metadata_title),
                subtitle = stringResource(id = R.string.about_credit_metadata_subtitle),
                onClick = {},
            )
        }
    }

    if (showHelpDialog) {
        val message =
            remember {
                val systemFolders =
                    SystemID.values()
                        .joinToString(", ") { "<i>${it.dbname}</i>" }

                context.getString(R.string.lemuroid_help_content)
                    .replace("\$SYSTEMS", systemFolders)
            }

        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text(text = stringResource(id = R.string.about_help_title)) },
            text = { HtmlText(text = message) },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) {
                    Text(text = stringResource(id = R.string.ok))
                }
            },
        )
    }
}

@Composable
private fun AboutCardGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
            )
            content()
        }
    }
}

@Composable
private fun AboutItemRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AboutAppHeaderCard() {
    val context = LocalContext.current
    val appIconDrawable =
        remember {
            context.packageManager.getApplicationIcon(context.applicationInfo)
        }
    val appIconPainter = rememberDrawablePainter(drawable = appIconDrawable)

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(
                painter = appIconPainter,
                contentDescription = stringResource(id = R.string.about_app_name),
                modifier =
                    Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(14.dp)),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(id = R.string.about_app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text =
                            stringResource(
                                id = R.string.about_version_format,
                                BuildConfig.VERSION_NAME,
                                BuildConfig.VERSION_CODE,
                            ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(id = R.string.about_app_tagline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun openExternalUri(
    context: Context,
    url: String,
) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
