package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.core.build.AppFeaturePolicy

@Composable
fun ImdbRatingSourceLabel(
    logoModifier: Modifier,
    textStyle: TextStyle,
    textColor: Color,
    textModifier: Modifier = Modifier
) {
    val label = stringResource(R.string.cd_imdb)
    if (AppFeaturePolicy.imdbRatingLogoEnabled) {
        val context = LocalContext.current
        val model = remember(context) {
            ImageRequest.Builder(context)
                .data(R.raw.imdb_logo_2016)
                .build()
        }
        AsyncImage(
            model = model,
            contentDescription = label,
            modifier = logoModifier,
            contentScale = ContentScale.Fit
        )
    } else {
        Text(
            text = label,
            modifier = textModifier,
            style = textStyle,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Series Graph source mark.
 *
 * - [compact]=true: logo only (episode cards / overlays), attribution as contentDescription
 * - [compact]=false: wordmark with attribution underneath (Ratings tab header)
 */
@Composable
fun SeriesGraphRatingSourceLabel(
    textStyle: TextStyle,
    textColor: Color,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    logoHeightDp: Int = if (compact) 14 else 18
) {
    val attribution = stringResource(R.string.episode_rating_attribution_series_graph)
    val shortFallback = stringResource(R.string.episode_rating_source_series_graph_short)
    val context = LocalContext.current
    val model = remember(context) {
        ImageRequest.Builder(context)
            .data(R.raw.series_graph_logo_dark)
            .build()
    }
    var logoFailed by remember { mutableStateOf(false) }
    // Wordmark aspect ≈ 10:1 — keep max width proportional so Fit does not squash height.
    val logoMaxWidthDp = (logoHeightDp * 10).coerceAtMost(if (compact) 100 else 180)
    val logoModifier = Modifier
        .height(logoHeightDp.dp)
        .widthIn(max = logoMaxWidthDp.dp)

    if (compact) {
        if (logoFailed) {
            Text(
                text = shortFallback,
                modifier = modifier.semantics { contentDescription = attribution },
                style = textStyle,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            AsyncImage(
                model = model,
                contentDescription = attribution,
                modifier = modifier.then(logoModifier),
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                onError = { logoFailed = true }
            )
        }
        return
    }

    Column(
        modifier = modifier.semantics { contentDescription = attribution },
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.Start
    ) {
        if (!logoFailed) {
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = logoModifier,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                onError = { logoFailed = true }
            )
        } else {
            Text(
                text = stringResource(R.string.episode_rating_source_series_graph),
                style = textStyle,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = attribution,
            style = textStyle,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
