package com.nuvio.tv.ui.components

import com.nuvio.tv.ui.theme.NuvioTheme

import android.graphics.ColorSpace
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.util.StableList
import com.nuvio.tv.ui.util.recompositionHighlighter
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.colorSpace
import coil3.request.crossfade
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.util.contentTextDirection
import com.nuvio.tv.ui.util.formatHeroRuntime
import com.nuvio.tv.ui.util.LocalRecompositionHighlighterEnabled
import com.nuvio.tv.ui.util.localizedContentType
import com.nuvio.tv.ui.util.localizedGenreLabel
import kotlinx.coroutines.delay

private const val AUTO_ADVANCE_INTERVAL_MS = 10000L
private val YEAR_REGEX = Regex("""\b\d{4}\b""")

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HeroCarousel(
    items: StableList<MetaPreview>,
    onItemClick: (MetaPreview) -> Unit,
    onItemFocus: (MetaPreview) -> Unit = {},
    onActiveItemChanged: (MetaPreview) -> Unit = {},
    focusRequester: FocusRequester? = null,
    showImdbRatings: Boolean = true,
    showBackdrop: Boolean = true,
    /** Tall Modern hero: full-bleed artwork + Modern scrims so the banner matches row hero. */
    immersiveBackdrop: Boolean = false,
    fullWidth: Dp = Dp.Unspecified,
    carouselHeight: Dp = 400.dp,
    indicatorBottomPadding: Dp = Dp.Unspecified,
    /**
     * Extra bottom inset for logo/meta when the carousel fills the screen behind the rows
     * (typically rows viewport height + gap), mirroring Modern [HeroTitleBlock] placement.
     */
    contentBottomInset: Dp = Dp.Unspecified,
    /**
     * Modern peek→rest (0–1), animated upstream. Drives logo, column width, and description height.
     * No-op unless [immersiveBackdrop] is true.
     */
    contentExpandProgress: Float = 0f,
    initialActiveIndex: Int = 0,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    val currentOnItemClick by rememberUpdatedState(onItemClick)
    val currentOnItemFocus by rememberUpdatedState(onItemFocus)
    val currentOnActiveItemChanged by rememberUpdatedState(onActiveItemChanged)
    var activeIndex by remember { mutableIntStateOf(initialActiveIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))) }
    var isFocused by remember { mutableStateOf(false) }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    LaunchedEffect(activeIndex, isFocused) {
        if (!isFocused) return@LaunchedEffect
        items.getOrNull(activeIndex)?.let { currentOnItemFocus(it) }
    }

    LaunchedEffect(activeIndex, items) {
        items.getOrNull(activeIndex)?.let { currentOnActiveItemChanged(it) }
    }

    // Auto-advance every 10s, including while focused (Modern keeps the carousel focused when visible).
    // Restarting on activeIndex resets the dwell timer after manual L/R.
    LaunchedEffect(activeIndex, items.size) {
        if (items.size <= 1) return@LaunchedEffect
        delay(AUTO_ADVANCE_INTERVAL_MS)
        activeIndex = (activeIndex + 1) % items.size
    }

    Box(
        modifier = modifier
            .then(
                if (fullWidth != Dp.Unspecified)
                    Modifier.requiredWidth(fullWidth)
                else
                    Modifier.fillMaxWidth()
            )
            .height(carouselHeight)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                isFocused = it.hasFocus || it.isFocused
            }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionLeft -> {
                            activeIndex = if (isRtl) {
                                (activeIndex + 1) % items.size
                            } else {
                                (activeIndex - 1 + items.size) % items.size
                            }
                            true
                        }
                        Key.DirectionRight -> {
                            activeIndex = if (isRtl) {
                                (activeIndex - 1 + items.size) % items.size
                            } else {
                                (activeIndex + 1) % items.size
                            }
                            true
                        }
                        else -> false
                    }
                } else if (event.type == KeyEventType.KeyUp &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter)
                ) {
                    currentOnItemClick(items[activeIndex])
                    true
                } else {
                    false
                }
            }
    ) {
        // Crossfade between slides
        Crossfade(
            targetState = activeIndex,
            animationSpec = tween(300),
            label = "heroSlide"
        ) { index ->
            val item = items.getOrNull(index) ?: return@Crossfade
            HeroCarouselSlide(
                item = item,
                showImdbRatings = showImdbRatings,
                showBackdrop = showBackdrop,
                immersiveBackdrop = immersiveBackdrop,
                contentBottomInset = contentBottomInset,
                contentExpandProgress = contentExpandProgress
            )
        }

        // Indicator chips — fixed sizes so inactive pills stay visually identical
        val focusRingBrush = NuvioTheme.focusRing.brush()
        val dotColorInactive = remember { Color.White.copy(alpha = 0.3f) }
        val inactiveChipWidth = 12.dp
        val activeChipWidth = 28.dp
        val chipHeight = 6.dp
        val chipShape = remember { RoundedCornerShape(percent = 50) }
        val resolvedIndicatorBottomPadding = when {
            indicatorBottomPadding != Dp.Unspecified -> indicatorBottomPadding
            immersiveBackdrop -> NuvioTheme.spacing.md
            else -> NuvioTheme.spacing.lg
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = resolvedIndicatorBottomPadding),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(items.size) { index ->
                val isActive = index == activeIndex
                Box(
                    modifier = Modifier
                        .size(
                            width = if (isActive) activeChipWidth else inactiveChipWidth,
                            height = chipHeight
                        )
                        .clip(chipShape)
                        .then(
                            if (isActive) Modifier.background(focusRingBrush)
                            else Modifier.background(dotColorInactive)
                        )
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HeroCarouselSlide(
    item: MetaPreview,
    showImdbRatings: Boolean,
    showBackdrop: Boolean,
    immersiveBackdrop: Boolean = false,
    contentBottomInset: Dp = Dp.Unspecified,
    contentExpandProgress: Float = 0f
) {
    val highlighterEnabled = LocalRecompositionHighlighterEnabled.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val progress = if (immersiveBackdrop) contentExpandProgress.coerceIn(0f, 1f) else 0f
    // Immersive keeps a fixed logo box so peek→rest only moves vertically (no horizontal reflow).
    val logoMaxWidth = if (immersiveBackdrop) 300.dp else 220.dp
    val logoHeight = if (immersiveBackdrop) 140.dp else 100.dp
    val logoRequestWidthPx = remember(density, logoMaxWidth) {
        with(density) { logoMaxWidth.roundToPx() }.coerceAtLeast(1)
    }
    val logoRequestHeightPx = remember(density, logoHeight) {
        with(density) { logoHeight.roundToPx() }.coerceAtLeast(1)
    }

    val logoModel = remember(context, item.logo, logoRequestWidthPx, logoRequestHeightPx) {
        item.logo?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(true)
                .size(width = logoRequestWidthPx, height = logoRequestHeightPx)
                .build()
        }
    }
    var logoLoadFailed by remember(item.logo) { mutableStateOf(false) }
    val showLogo = !item.logo.isNullOrBlank() && !logoLoadFailed
    val contentTypeText = remember(context, item.apiType) {
        localizedContentType(context, item.apiType).takeIf { it.isNotBlank() }
    }
    val primaryGenreText = remember(context, item.genres) {
        item.genres.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { localizedGenreLabel(context, it) }
    }
    val runtimeText = remember(item.runtime) { formatHeroRuntime(item.runtime) }
    val releaseYear = remember(item.releaseInfo) {
        item.releaseInfo?.let { releaseInfo ->
            YEAR_REGEX.find(releaseInfo)?.value ?: releaseInfo.split("-").firstOrNull()
        }?.trim()?.takeIf { it.isNotEmpty() }
    }
    val leadingMetaText = remember(contentTypeText, primaryGenreText) {
        listOfNotNull(contentTypeText, primaryGenreText).joinToString(separator = " • ")
    }
    val trailingMetadata = remember(runtimeText, releaseYear) {
        listOfNotNull(runtimeText, releaseYear)
    }
    val ratingText = remember(item.imdbRating, showImdbRatings) {
        item.imdbRating
            ?.takeIf { showImdbRatings }
            ?.let { String.format("%.1f", it) }
    }
    val contentWidthFraction = 0.42f
    val descriptionStyle = MaterialTheme.typography.bodyMedium
    val descriptionLineHeight = with(density) { descriptionStyle.lineHeight.toDp() }
    val descriptionMaxHeight = if (immersiveBackdrop) {
        lerp(descriptionLineHeight * 4, descriptionLineHeight * 6, progress)
    } else {
        descriptionLineHeight * 4
    }
    val logoAlignment = if (immersiveBackdrop) Alignment.BottomStart else Alignment.CenterStart

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (highlighterEnabled) Modifier.recompositionHighlighter() else Modifier)
    ) {
        if (showBackdrop) {
            if (immersiveBackdrop) {
                HeroCarouselModernBackdrop(
                    item = item,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                HeroCarouselBackdrop(
                    item = item,
                    fullPage = false,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        val contentBottomPadding = when {
            contentBottomInset != Dp.Unspecified -> contentBottomInset
            immersiveBackdrop -> NuvioTheme.spacing.xxl
            else -> NuvioTheme.spacing.xxxl
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = NuvioTheme.spacing.xxxl,
                    bottom = contentBottomPadding,
                    end = NuvioTheme.spacing.xxxl
                )
                .fillMaxWidth(contentWidthFraction)
                .then(
                    if (immersiveBackdrop) {
                        Modifier.graphicsLayer {
                            alpha = 0.94f + 0.06f * progress
                            transformOrigin = TransformOrigin(0f, 1f)
                        }
                    } else {
                        Modifier
                    }
                ),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            if (showLogo) {
                AsyncImage(
                    model = logoModel,
                    contentDescription = item.name,
                    onError = { logoLoadFailed = true },
                    modifier = Modifier
                        .height(logoHeight)
                        .widthIn(min = logoHeight, max = logoMaxWidth)
                        .fillMaxWidth(),
                    contentScale = ContentScale.Fit,
                    alignment = logoAlignment
                )
            } else {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.headlineLarge,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (leadingMetaText.isNotBlank() || trailingMetadata.isNotEmpty() || ratingText != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val hasTrailingMeta = trailingMetadata.isNotEmpty() || ratingText != null
                    if (leadingMetaText.isNotBlank()) {
                        Text(
                            text = leadingMetaText,
                            style = MaterialTheme.typography.labelMedium,
                            color = NuvioTheme.colors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (hasTrailingMeta) {
                                Modifier.weight(1f, fill = false)
                            } else {
                                Modifier
                            }
                        )
                    }
                    if (hasTrailingMeta) {
                        if (leadingMetaText.isNotBlank()) HeroCarouselMetaDivider()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                        ) {
                            trailingMetadata.forEachIndexed { index, value ->
                                if (index > 0) HeroCarouselMetaDivider()
                                Text(
                                    text = value,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = NuvioTheme.colors.TextSecondary,
                                    maxLines = 1
                                )
                            }
                            if (ratingText != null) {
                                if (trailingMetadata.isNotEmpty()) HeroCarouselMetaDivider()
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
                                ) {
                                    ImdbRatingSourceLabel(
                                        logoModifier = Modifier.size(30.dp),
                                        textStyle = MaterialTheme.typography.labelMedium,
                                        textColor = NuvioTheme.colors.TextSecondary
                                    )
                                    Text(
                                        text = ratingText,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = NuvioTheme.colors.TextSecondary,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }

            val heroBlurb = item.tagline?.takeIf { it.isNotBlank() }
                ?: item.description?.takeIf { it.isNotBlank() }
            heroBlurb?.let { description ->
                Text(
                    text = description,
                    style = descriptionStyle.copy(
                        textDirection = description.contentTextDirection()
                    ),
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = if (immersiveBackdrop) 6 else 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = descriptionMaxHeight)
                        .clipToBounds()
                )
            }
        }
    }
}

@Composable
private fun HeroCarouselMetaDivider() {
    Box(
        modifier = Modifier
            .size(NuvioTheme.spacing.xs.coerceAtLeast(NuvioTheme.spacing.xxs))
            .clip(RoundedCornerShape(percent = 50))
            .background(NuvioTheme.colors.TextTertiary.copy(alpha = 0.78f))
    )
}

@Composable
internal fun HeroCarouselBackdrop(
    item: MetaPreview,
    fullPage: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val requestWidthPx = remember(configuration.screenWidthDp, density) {
        with(density) { configuration.screenWidthDp.dp.roundToPx() }.coerceAtLeast(1)
    }
    val requestHeightPx = remember(configuration.screenHeightDp, density, fullPage) {
        val height = if (fullPage) configuration.screenHeightDp.dp else 400.dp
        with(density) { height.roundToPx() }.coerceAtLeast(1)
    }
    val backdropUrl = item.backdropUrl
    val backgroundModel = remember(context, backdropUrl, requestWidthPx, requestHeightPx, fullPage) {
        ImageRequest.Builder(context)
            .data(backdropUrl)
            .crossfade(false)
            .size(width = requestWidthPx, height = requestHeightPx)
            .apply {
                if (fullPage && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    colorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                }
            }
            .build()
    }
    val bgColor = NuvioTheme.colors.Background

    Box(
        modifier = modifier
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithCache {
            val bottomStartFraction = if (fullPage) 0.55f else 0.30f
            val leftEndFraction = if (fullPage) 0.66f else 0.72f
            val bottomStartY = size.height * bottomStartFraction
            val leftEndX = size.width * leftEndFraction
            val bottomGradient = Brush.verticalGradient(
                colorStops = if (fullPage) {
                    arrayOf(
                        0.0f to Color.Transparent,
                        0.4222f to bgColor.copy(alpha = 0.32f),
                        0.7778f to bgColor.copy(alpha = 0.62f),
                        1.0f to bgColor.copy(alpha = 0.78f)
                    )
                } else {
                    arrayOf(
                        0.0f to Color.Transparent,
                        0.4286f to bgColor.copy(alpha = 0.5f),
                        0.7143f to bgColor.copy(alpha = 0.85f),
                        1.0f to bgColor
                    )
                },
                startY = bottomStartY,
                endY = size.height
            )
            val leftGradient = Brush.horizontalGradient(
                colorStops = if (fullPage) {
                    arrayOf(
                        0.0f to bgColor.copy(alpha = 0.98f),
                        0.2424f to bgColor.copy(alpha = 0.88f),
                        0.5152f to bgColor.copy(alpha = 0.56f),
                        0.7879f to bgColor.copy(alpha = 0.20f),
                        1.0f to Color.Transparent
                    )
                } else {
                    arrayOf(
                        0.0f to bgColor.copy(alpha = 0.98f),
                        0.2222f to bgColor.copy(alpha = 0.88f),
                        0.4722f to bgColor.copy(alpha = 0.56f),
                        0.7778f to bgColor.copy(alpha = 0.20f),
                        1.0f to Color.Transparent
                    )
                },
                startX = 0f,
                endX = leftEndX
            )
            onDrawWithContent {
                drawContent()
                drawRect(
                    brush = bottomGradient,
                    topLeft = Offset(0f, bottomStartY),
                    size = Size(size.width, size.height - bottomStartY)
                )
                drawRect(
                    brush = leftGradient,
                    size = Size(leftEndX, size.height)
                )
            }
        }
    ) {
        AsyncImage(
            model = backgroundModel,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter
        )
    }
}

/** Carousel backdrop matching Modern hero media + full-screen gradient scrims. */
@Composable
internal fun HeroCarouselModernBackdrop(
    item: MetaPreview,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val requestWidthPx = remember(configuration.screenWidthDp, density) {
        with(density) { configuration.screenWidthDp.dp.roundToPx() }.coerceAtLeast(1)
    }
    val requestHeightPx = remember(configuration.screenHeightDp, density) {
        with(density) { configuration.screenHeightDp.dp.roundToPx() }.coerceAtLeast(1)
    }
    val backdropUrl = item.backdropUrl
    val backgroundModel = remember(context, backdropUrl, requestWidthPx, requestHeightPx) {
        ImageRequest.Builder(context)
            .data(backdropUrl)
            .size(width = requestWidthPx, height = requestHeightPx)
            .build()
    }
    val bgColor = NuvioTheme.colors.Background

    Box(modifier = modifier) {
        AsyncImage(
            model = backgroundModel,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopEnd
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache {
                    val horizontalFadeEndX = size.width * 0.65f
                    val colorStops = arrayOf(
                        0.0f to bgColor,
                        0.22f to bgColor.copy(alpha = 0.90f),
                        0.46f to bgColor.copy(alpha = 0.80f),
                        0.76f to bgColor.copy(alpha = 0.42f),
                        1.0f to Color.Transparent
                    )
                    val horizontalGradient = if (isRtl) {
                        Brush.horizontalGradient(
                            colorStops = colorStops,
                            startX = size.width,
                            endX = size.width - horizontalFadeEndX
                        )
                    } else {
                        Brush.horizontalGradient(
                            colorStops = colorStops,
                            startX = 0f,
                            endX = horizontalFadeEndX
                        )
                    }
                    val bottomStripStartY = size.height * 0.64f
                    val verticalGradient = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.30f to bgColor.copy(alpha = 0.35f),
                            0.60f to bgColor.copy(alpha = 0.75f),
                            1.0f to bgColor
                        ),
                        startY = bottomStripStartY,
                        endY = size.height
                    )
                    onDrawBehind {
                        val rectLeft = if (isRtl) size.width - horizontalFadeEndX else 0f
                        drawRect(
                            brush = horizontalGradient,
                            topLeft = Offset(rectLeft, 0f),
                            size = Size(horizontalFadeEndX, size.height)
                        )
                        drawRect(
                            brush = verticalGradient,
                            topLeft = Offset(0f, bottomStripStartY),
                            size = Size(size.width, size.height - bottomStripStartY)
                        )
                    }
                }
        )
    }
}
