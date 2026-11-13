package com.nuvio.tv.ui.screens.player.resolution

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.DebridStreamResolution
import com.nuvio.tv.ui.screens.player.playerControlsBrightness
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Fork: the player's resolution list. A Dialog is its own window, so it takes the remote's
 * focus and Back without the player's panel bookkeeping.
 */
@Composable
internal fun ResolutionPickerDialog(
    options: List<ResolutionOption>,
    isLoading: Boolean,
    onResolutionSelected: (DebridStreamResolution) -> Unit,
    onDismiss: () -> Unit,
    brightness: Float = 1f
) {
    val focusRequesters = remember(options) { options.map { FocusRequester() } }
    val initialFocus = options.indexOfFirst { it.isCurrent }.coerceAtLeast(0)

    LaunchedEffect(focusRequesters) {
        focusRequesters.getOrNull(initialFocus)?.let { runCatching { it.requestFocus() } }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .playerControlsBrightness(brightness)
                .width(320.dp)
                .clip(RoundedCornerShape(NuvioTheme.radii.xl))
                .background(NuvioTheme.colors.BackgroundElevated)
                .padding(NuvioTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            Text(
                text = stringResource(R.string.player_resolution_title),
                style = MaterialTheme.typography.headlineSmall,
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.padding(bottom = NuvioTheme.spacing.sm)
            )

            val onlyCurrent = options.all { it.isCurrent }
            when {
                options.isEmpty() && isLoading -> StatusText(stringResource(R.string.player_resolution_loading))
                options.isEmpty() || (onlyCurrent && !isLoading) ->
                    StatusText(stringResource(R.string.player_resolution_none))
            }

            options.forEachIndexed { index, option ->
                ResolutionItem(
                    option = option,
                    modifier = Modifier.focusRequester(focusRequesters[index]),
                    onClick = { if (option.isCurrent) onDismiss() else onResolutionSelected(option.resolution) }
                )
            }

            if (options.isNotEmpty() && isLoading) StatusText(stringResource(R.string.player_resolution_loading))
        }
    }
}

@Composable
private fun StatusText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = NuvioTheme.colors.TextSecondary
    )
}

@Composable
private fun ResolutionItem(
    option: ResolutionOption,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = if (option.isCurrent) NuvioTheme.colors.Secondary.copy(alpha = 0.2f) else NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(NuvioTheme.radii.sm))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(NuvioTheme.spacing.lg),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = option.resolution.displayLabel(),
                style = MaterialTheme.typography.bodyLarge,
                color = if (option.isCurrent) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary
            )
            Text(
                text = if (option.isCurrent) {
                    stringResource(R.string.player_resolution_current)
                } else {
                    pluralStringResource(R.plurals.player_resolution_count, option.count, option.count)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
        }
    }
}

internal fun DebridStreamResolution.displayLabel(): String =
    if (this == DebridStreamResolution.P2160) "4K" else label

/** Fork: the resolution button's text, in the colour the control button gives its content. */
@Composable
internal fun ControlButtonLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), // "1080p" fits the 48dp circle
        maxLines = 1,
        softWrap = false
    )
}
