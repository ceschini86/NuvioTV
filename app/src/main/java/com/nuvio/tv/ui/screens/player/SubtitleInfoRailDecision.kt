package com.nuvio.tv.ui.screens.player

import java.util.Locale

/**
 * Pure decision for subtitle overlay Col3 (Info content + CTAs).
 * Fatia B of overlay-info-rail: no Compose, no side effects.
 * B6d: system notices when Smart auto AI is unavailable.
 */

internal enum class SubtitleInfoOptionKind {
    INTERNAL,
    ADDON,
    AI
}

internal enum class SubtitleInfoContentKind {
    AI,
    EMBEDDED,
    ADDON,
    EMPTY
}

internal enum class SubtitleInfoFieldKey {
    SOURCE,
    METHOD,
    STATUS,
    TARGET,
    MODEL,
    RUNG,
    REASON,
    LOCKED,
    LANGUAGE,
    TRACK_NAME,
    FORMAT,
    FORCED,
    SDH,
    ADDON_NAME,
    FILE_NAME,
    SCORE
}

internal enum class SubtitleInfoCtaAction {
    NONE,
    TRANSLATE_WITH_AI,
    RESET_TO_SMART_AUTO,
    /** Same event as Reset Smart; label promises classic auto, not AI auto. */
    RESET_TO_CLASSIC_AUTO
}

internal enum class SubtitleInfoMethodKind {
    AUTOMATIC,
    USER_SELECTED
}

/**
 * B6d system notices (priority in [resolveSubtitleInfoSystemNotice]).
 * Translate is blocked only for [MPV], [NO_API_KEY], [RATE_LIMITED].
 */
internal enum class SubtitleInfoUnavailableReason {
    MPV,
    NO_API_KEY,
    RATE_LIMITED,
    LOADING,
    SMART_OFF,
    PREFERRED_NONE,
    FORCED_APPLIES,
    BITMAP_ONLY,
    NO_TRANSLATABLE_EMBEDDED,
    NO_EMBEDDED
}

internal enum class EmbeddedAiAvailability {
    USABLE,
    NONE,
    BITMAP_ONLY,
    NO_TRANSLATABLE
}

internal data class SubtitleInfoField(
    val key: SubtitleInfoFieldKey,
    val value: String
)

internal data class SubtitleInfoOptionSnapshot(
    val kind: SubtitleInfoOptionKind,
    val id: String,
    val title: String,
    val sourceLabel: String,
    val languageLabel: String? = null,
    val languageCode: String? = null,
    val meta: String? = null,
    val matchScorePercent: Int = 0,
    val formatLabel: String? = null,
    val isBitmap: Boolean = false,
    val isForced: Boolean = false,
    val isSdh: Boolean = false,
    val addonName: String? = null,
    val fileName: String? = null,
    val trackName: String? = null
)

internal data class SubtitleInfoDiagnosticsSnapshot(
    val rung: AiSubtitleLadderRung,
    val reason: String,
    val sourceKind: AiSubtitleSourceKind? = null,
    val sourceLabel: String? = null,
    val sourceLanguage: String? = null,
    val matchScore: Int? = null,
    val targetLanguage: String? = null,
    val model: String? = null,
    val userLocked: Boolean = false
)

internal data class SubtitleInfoContentDecision(
    val kind: SubtitleInfoContentKind,
    val title: String,
    val sourceChipLabel: String,
    val statusLine: String? = null,
    val fields: List<SubtitleInfoField> = emptyList(),
    val matchScorePercent: Int? = null,
    val methodKind: SubtitleInfoMethodKind? = null,
    val unavailableReason: SubtitleInfoUnavailableReason? = null
)

internal data class SubtitleInfoCtaDecision(
    val action: SubtitleInfoCtaAction,
    val enabled: Boolean = true,
    val focusable: Boolean = true
) {
    val showCta: Boolean get() = action != SubtitleInfoCtaAction.NONE
    val canMoveFocusToCta: Boolean get() = showCta && focusable && enabled

    companion object {
        val None = SubtitleInfoCtaDecision(
            action = SubtitleInfoCtaAction.NONE,
            enabled = false,
            focusable = false
        )
    }
}

internal data class SubtitleInfoRailDecision(
    val content: SubtitleInfoContentDecision,
    val cta: SubtitleInfoCtaDecision
)

internal data class SubtitleInfoSmartContext(
    val smartAiEnabled: Boolean = true,
    val preferredLanguageNone: Boolean = false,
    val forcedApplies: Boolean = false,
    val embeddedAvailability: EmbeddedAiAvailability = EmbeddedAiAvailability.USABLE,
    val textTracksReady: Boolean = true
)

/**
 * Preferido Col1 count includes the synthetic AI option when it is listed (K1).
 */
internal fun languageRailCountIncludingAi(
    trackAndAddonCount: Int,
    aiOptionListed: Boolean
): Int {
    val base = trackAndAddonCount.coerceAtLeast(0)
    return if (aiOptionListed) base + 1 else base
}

internal fun SubtitleInfoUnavailableReason.blocksAiTranslate(): Boolean =
    this == SubtitleInfoUnavailableReason.MPV ||
        this == SubtitleInfoUnavailableReason.NO_API_KEY ||
        this == SubtitleInfoUnavailableReason.RATE_LIMITED

/** Synthetic AI Col2 option may be selected only with a usable embedded pivot or active translation. */
internal fun isSyntheticAiOptionSelectable(
    embeddedAvailability: EmbeddedAiAvailability,
    translationActive: Boolean
): Boolean = embeddedAvailability == EmbeddedAiAvailability.USABLE || translationActive

internal fun isPreferredSubtitleLanguageNone(preferredLanguage: String): Boolean {
    val code = preferredLanguage.trim()
    return code.isEmpty() || code.equals("none", ignoreCase = true)
}

/**
 * Mirrors [findAiSourceSubtitleTrackIndex] usability (forced / songs-and-signs / bitmap skipped).
 */
internal fun classifyEmbeddedAiAvailability(tracks: List<TrackInfo>): EmbeddedAiAvailability {
    if (tracks.isEmpty()) return EmbeddedAiAvailability.NONE
    var sawBitmap = false
    var sawUnusableText = false
    for (track in tracks) {
        when {
            track.isBitmapSubtitleCodec() -> sawBitmap = true
            track.isEffectivelyForcedOrSongsAndSigns() -> sawUnusableText = true
            else -> return EmbeddedAiAvailability.USABLE
        }
    }
    return when {
        sawBitmap && !sawUnusableText -> EmbeddedAiAvailability.BITMAP_ONLY
        sawBitmap -> EmbeddedAiAvailability.BITMAP_ONLY
        else -> EmbeddedAiAvailability.NO_TRANSLATABLE
    }
}

internal fun TrackInfo.isBitmapSubtitleCodec(): Boolean {
    val c = codec?.uppercase(Locale.ROOT) ?: return false
    return c == "PGS" || c == "DVB" || c.contains("VOB") || c.contains("PGS") || c.contains("HDMV")
}

internal fun TrackInfo.isEffectivelyForcedOrSongsAndSigns(): Boolean {
    val n = name
    return isForced ||
        n.contains("forced", ignoreCase = true) ||
        n.contains("songs and signs", ignoreCase = true) ||
        n.contains("signs and songs", ignoreCase = true)
}

/**
 * B6d priority: S6 → S5 → S7 → S10 → S4 → S8 → S9 → S2 → S3 → S1.
 */
internal fun resolveSubtitleInfoSystemNotice(
    aiAvailable: Boolean,
    aiQuotaExhausted: Boolean,
    isUsingMpv: Boolean,
    smart: SubtitleInfoSmartContext
): SubtitleInfoUnavailableReason? {
    if (isUsingMpv) return SubtitleInfoUnavailableReason.MPV
    if (aiQuotaExhausted) return SubtitleInfoUnavailableReason.RATE_LIMITED
    if (!aiAvailable) return SubtitleInfoUnavailableReason.NO_API_KEY
    if (!smart.textTracksReady) return SubtitleInfoUnavailableReason.LOADING
    if (!smart.smartAiEnabled) return SubtitleInfoUnavailableReason.SMART_OFF
    if (smart.preferredLanguageNone) return SubtitleInfoUnavailableReason.PREFERRED_NONE
    if (smart.forcedApplies) return SubtitleInfoUnavailableReason.FORCED_APPLIES
    return when (smart.embeddedAvailability) {
        EmbeddedAiAvailability.USABLE -> null
        EmbeddedAiAvailability.BITMAP_ONLY -> SubtitleInfoUnavailableReason.BITMAP_ONLY
        EmbeddedAiAvailability.NO_TRANSLATABLE -> SubtitleInfoUnavailableReason.NO_TRANSLATABLE_EMBEDDED
        EmbeddedAiAvailability.NONE -> SubtitleInfoUnavailableReason.NO_EMBEDDED
    }
}

internal fun canResetToSmartAiAuto(smart: SubtitleInfoSmartContext): Boolean =
    smart.smartAiEnabled &&
        !smart.preferredLanguageNone &&
        !smart.forcedApplies &&
        smart.textTracksReady &&
        smart.embeddedAvailability == EmbeddedAiAvailability.USABLE

internal fun decideSubtitleInfoRail(
    displayOption: SubtitleInfoOptionSnapshot?,
    isPlaybackSelected: Boolean,
    diagnostics: SubtitleInfoDiagnosticsSnapshot?,
    statusLine: String?,
    aiAvailable: Boolean,
    aiQuotaExhausted: Boolean,
    isUsingMpv: Boolean,
    userExplicitSelection: Boolean = false,
    translationActive: Boolean = false,
    smart: SubtitleInfoSmartContext = SubtitleInfoSmartContext()
): SubtitleInfoRailDecision {
    val rawNotice = resolveSubtitleInfoSystemNotice(
        aiAvailable = aiAvailable,
        aiQuotaExhausted = aiQuotaExhausted,
        isUsingMpv = isUsingMpv,
        smart = smart
    )
    val showSmartContext = translationActive ||
        displayOption?.kind == SubtitleInfoOptionKind.AI ||
        diagnostics?.rung == AiSubtitleLadderRung.MANUAL ||
        diagnostics?.rung == AiSubtitleLadderRung.CLASSIC_FALLBACK ||
        diagnostics?.rung == AiSubtitleLadderRung.NONE
    val systemNotice = filterSubtitleInfoSystemNoticeForDisplay(
        notice = rawNotice,
        smartAiEnabled = smart.smartAiEnabled,
        showSmartContext = showSmartContext
    )
    val content = buildInfoContent(
        displayOption = displayOption,
        diagnostics = diagnostics,
        statusLine = statusLine,
        systemNotice = systemNotice,
        translationActive = translationActive
    )
    val cta = decideInfoCta(
        displayOption = displayOption,
        isPlaybackSelected = isPlaybackSelected,
        diagnostics = diagnostics,
        systemNotice = rawNotice,
        userExplicitSelection = userExplicitSelection,
        smart = smart
    )
    return SubtitleInfoRailDecision(content = content, cta = cta)
}

/**
 * Translate-blocking notices always show. Smart-auto explanations (S1–S4, S8–S9) only when
 * Smart is on, or the Info is already in an AI/classic-auto context — avoids painting
 * “Smart off” on every classic track when [aiAutoSelect] defaults to false.
 */
internal fun filterSubtitleInfoSystemNoticeForDisplay(
    notice: SubtitleInfoUnavailableReason?,
    smartAiEnabled: Boolean,
    showSmartContext: Boolean
): SubtitleInfoUnavailableReason? {
    if (notice == null) return null
    if (notice.blocksAiTranslate()) return notice
    if (notice == SubtitleInfoUnavailableReason.LOADING) return notice
    if (smartAiEnabled) return notice
    return if (showSmartContext) notice else null
}

private fun buildInfoContent(
    displayOption: SubtitleInfoOptionSnapshot?,
    diagnostics: SubtitleInfoDiagnosticsSnapshot?,
    statusLine: String?,
    systemNotice: SubtitleInfoUnavailableReason?,
    translationActive: Boolean
): SubtitleInfoContentDecision {
    if (displayOption == null && diagnostics == null && !translationActive) {
        return SubtitleInfoContentDecision(
            kind = SubtitleInfoContentKind.EMPTY,
            title = "",
            sourceChipLabel = ""
        )
    }

    return when (displayOption?.kind) {
        SubtitleInfoOptionKind.AI, null -> buildAiContent(
            displayOption = displayOption,
            diagnostics = diagnostics,
            statusLine = statusLine,
            systemNotice = systemNotice
        )
        SubtitleInfoOptionKind.INTERNAL -> buildEmbeddedContent(
            option = displayOption,
            diagnostics = diagnostics,
            statusLine = statusLine,
            systemNotice = systemNotice
        )
        SubtitleInfoOptionKind.ADDON -> buildAddonContent(
            option = displayOption,
            diagnostics = diagnostics,
            statusLine = statusLine,
            systemNotice = systemNotice
        )
    }
}

private fun buildAiContent(
    displayOption: SubtitleInfoOptionSnapshot?,
    diagnostics: SubtitleInfoDiagnosticsSnapshot?,
    statusLine: String?,
    systemNotice: SubtitleInfoUnavailableReason?
): SubtitleInfoContentDecision {
    val methodKind = when {
        diagnostics?.userLocked == true || diagnostics?.rung == AiSubtitleLadderRung.MANUAL ->
            SubtitleInfoMethodKind.USER_SELECTED
        else -> SubtitleInfoMethodKind.AUTOMATIC
    }
    val sourceValue = listOfNotNull(
        diagnostics?.sourceLabel?.takeIf { it.isNotBlank() },
        diagnostics?.sourceLanguage?.takeIf { it.isNotBlank() },
        diagnostics?.sourceKind?.name
    ).joinToString(" · ").ifBlank { null }
    val fields = buildList {
        sourceValue?.let { add(SubtitleInfoField(SubtitleInfoFieldKey.SOURCE, it)) }
        add(
            SubtitleInfoField(
                SubtitleInfoFieldKey.METHOD,
                if (methodKind == SubtitleInfoMethodKind.USER_SELECTED) {
                    "User selected"
                } else {
                    "Automatic"
                }
            )
        )
        diagnostics?.rung?.let { add(SubtitleInfoField(SubtitleInfoFieldKey.RUNG, it.name)) }
        diagnostics?.reason?.takeIf { it.isNotBlank() }?.let {
            add(SubtitleInfoField(SubtitleInfoFieldKey.REASON, it))
        }
        statusLine?.takeIf { it.isNotBlank() }?.let {
            add(SubtitleInfoField(SubtitleInfoFieldKey.STATUS, it))
        }
        diagnostics?.targetLanguage?.takeIf { it.isNotBlank() }?.let {
            add(SubtitleInfoField(SubtitleInfoFieldKey.TARGET, it))
        }
        diagnostics?.model?.takeIf { it.isNotBlank() }?.let {
            add(SubtitleInfoField(SubtitleInfoFieldKey.MODEL, it))
        }
        if (diagnostics != null) {
            add(
                SubtitleInfoField(
                    SubtitleInfoFieldKey.LOCKED,
                    if (diagnostics.userLocked) "true" else "false"
                )
            )
        }
    }
    return SubtitleInfoContentDecision(
        kind = SubtitleInfoContentKind.AI,
        title = displayOption?.title.orEmpty(),
        sourceChipLabel = displayOption?.sourceLabel.orEmpty(),
        statusLine = statusLine,
        fields = fields,
        matchScorePercent = diagnostics?.matchScore?.takeIf { it > 0 },
        methodKind = methodKind,
        unavailableReason = systemNotice
    )
}

private fun buildEmbeddedContent(
    option: SubtitleInfoOptionSnapshot,
    diagnostics: SubtitleInfoDiagnosticsSnapshot?,
    @Suppress("UNUSED_PARAMETER") statusLine: String?,
    systemNotice: SubtitleInfoUnavailableReason?
): SubtitleInfoContentDecision {
    val format = when {
        option.isBitmap -> option.formatLabel?.let { "bitmap ($it)" } ?: "bitmap (PGS)"
        !option.formatLabel.isNullOrBlank() -> option.formatLabel
        else -> "text"
    }
    val fields = buildList {
        option.languageLabel?.takeIf { it.isNotBlank() }?.let {
            add(SubtitleInfoField(SubtitleInfoFieldKey.LANGUAGE, it))
        } ?: option.languageCode?.takeIf { it.isNotBlank() }?.let {
            add(SubtitleInfoField(SubtitleInfoFieldKey.LANGUAGE, it))
        }
        val trackName = option.trackName?.takeIf { it.isNotBlank() } ?: option.title
        add(SubtitleInfoField(SubtitleInfoFieldKey.TRACK_NAME, trackName))
        add(SubtitleInfoField(SubtitleInfoFieldKey.FORMAT, format))
        if (option.isForced) {
            add(SubtitleInfoField(SubtitleInfoFieldKey.FORCED, "true"))
        }
        if (option.isSdh) {
            add(SubtitleInfoField(SubtitleInfoFieldKey.SDH, "true"))
        }
        appendClassicDiagnosticsFields(diagnostics)
    }
    return SubtitleInfoContentDecision(
        kind = SubtitleInfoContentKind.EMBEDDED,
        title = option.title,
        sourceChipLabel = option.sourceLabel,
        statusLine = null,
        fields = fields,
        matchScorePercent = option.matchScorePercent.takeIf { it > 0 },
        unavailableReason = systemNotice
    )
}

private fun buildAddonContent(
    option: SubtitleInfoOptionSnapshot,
    diagnostics: SubtitleInfoDiagnosticsSnapshot?,
    @Suppress("UNUSED_PARAMETER") statusLine: String?,
    systemNotice: SubtitleInfoUnavailableReason?
): SubtitleInfoContentDecision {
    val fields = buildList {
        option.addonName?.takeIf { it.isNotBlank() }?.let {
            add(SubtitleInfoField(SubtitleInfoFieldKey.ADDON_NAME, it))
        } ?: add(SubtitleInfoField(SubtitleInfoFieldKey.ADDON_NAME, option.sourceLabel))
        val fileName = option.fileName?.takeIf { it.isNotBlank() }
            ?: option.meta?.takeIf { it.isNotBlank() }
            ?: option.title
        add(SubtitleInfoField(SubtitleInfoFieldKey.FILE_NAME, fileName))
        option.languageLabel?.takeIf { it.isNotBlank() }?.let {
            add(SubtitleInfoField(SubtitleInfoFieldKey.LANGUAGE, it))
        } ?: option.languageCode?.takeIf { it.isNotBlank() }?.let {
            add(SubtitleInfoField(SubtitleInfoFieldKey.LANGUAGE, it))
        }
        option.formatLabel?.takeIf { it.isNotBlank() }?.let {
            add(SubtitleInfoField(SubtitleInfoFieldKey.FORMAT, it))
        }
        option.matchScorePercent.takeIf { it > 0 }?.let {
            add(SubtitleInfoField(SubtitleInfoFieldKey.SCORE, "$it%"))
        }
        appendClassicDiagnosticsFields(diagnostics)
    }
    return SubtitleInfoContentDecision(
        kind = SubtitleInfoContentKind.ADDON,
        title = option.title,
        sourceChipLabel = option.sourceLabel,
        statusLine = null,
        fields = fields,
        matchScorePercent = option.matchScorePercent.takeIf { it > 0 },
        unavailableReason = systemNotice
    )
}

/** C2 / classic auto: Degrau + Motivo only for CLASSIC_FALLBACK — not AI ladder diagnostics. */
private fun MutableList<SubtitleInfoField>.appendClassicDiagnosticsFields(
    diagnostics: SubtitleInfoDiagnosticsSnapshot?
) {
    if (diagnostics == null) return
    if (diagnostics.rung != AiSubtitleLadderRung.CLASSIC_FALLBACK) return
    add(SubtitleInfoField(SubtitleInfoFieldKey.RUNG, diagnostics.rung.name))
    diagnostics.reason.takeIf { it.isNotBlank() }?.let {
        add(SubtitleInfoField(SubtitleInfoFieldKey.REASON, it))
    }
}

private fun decideInfoCta(
    displayOption: SubtitleInfoOptionSnapshot?,
    isPlaybackSelected: Boolean,
    diagnostics: SubtitleInfoDiagnosticsSnapshot?,
    systemNotice: SubtitleInfoUnavailableReason?,
    userExplicitSelection: Boolean,
    smart: SubtitleInfoSmartContext
): SubtitleInfoCtaDecision {
    if (displayOption == null) {
        return SubtitleInfoCtaDecision.None
    }

    val translateBlocked = systemNotice?.blocksAiTranslate() == true
    val rung = diagnostics?.rung
    val userLocked = diagnostics?.userLocked == true
    val isManualAi = userLocked || rung == AiSubtitleLadderRung.MANUAL

    when (displayOption.kind) {
        SubtitleInfoOptionKind.AI -> {
            if (!isPlaybackSelected) {
                return SubtitleInfoCtaDecision.None
            }
            if (isManualAi) {
                return decideResetCta(smart = smart, systemNotice = systemNotice)
            }
            return SubtitleInfoCtaDecision.None
        }
        SubtitleInfoOptionKind.INTERNAL, SubtitleInfoOptionKind.ADDON -> {
            if (isPlaybackSelected &&
                rung == AiSubtitleLadderRung.CLASSIC_FALLBACK &&
                !userLocked &&
                !userExplicitSelection
            ) {
                return SubtitleInfoCtaDecision.None
            }
            val translateEnabled = !translateBlocked && !displayOption.isBitmap
            return SubtitleInfoCtaDecision(
                action = SubtitleInfoCtaAction.TRANSLATE_WITH_AI,
                enabled = translateEnabled,
                focusable = translateEnabled
            )
        }
    }
}

private fun decideResetCta(
    smart: SubtitleInfoSmartContext,
    systemNotice: SubtitleInfoUnavailableReason?
): SubtitleInfoCtaDecision {
    val canSmart = canResetToSmartAiAuto(smart) &&
        systemNotice != SubtitleInfoUnavailableReason.MPV &&
        systemNotice != SubtitleInfoUnavailableReason.NO_API_KEY
    val enabled = when (systemNotice) {
        SubtitleInfoUnavailableReason.SMART_OFF,
        SubtitleInfoUnavailableReason.PREFERRED_NONE,
        SubtitleInfoUnavailableReason.MPV,
        SubtitleInfoUnavailableReason.NO_API_KEY -> false
        else -> true
    }
    return SubtitleInfoCtaDecision(
        action = if (canSmart) {
            SubtitleInfoCtaAction.RESET_TO_SMART_AUTO
        } else {
            SubtitleInfoCtaAction.RESET_TO_CLASSIC_AUTO
        },
        enabled = enabled,
        focusable = enabled
    )
}

internal fun subtitleFormatLabelFromCodec(codec: String?): Pair<String, Boolean> {
    val c = codec?.trim().orEmpty()
    if (c.isEmpty()) return "text" to false
    val upper = c.uppercase()
    val isBitmap = upper == "PGS" ||
        upper == "DVB" ||
        upper.contains("VOB") ||
        upper.contains("PGS") ||
        upper.contains("HDMV")
    return if (isBitmap) {
        val label = when {
            upper.contains("PGS") -> "PGS"
            upper.contains("DVB") -> "DVB"
            upper.contains("VOB") -> "VOBSUB"
            else -> upper
        }
        label to true
    } else {
        when {
            upper.contains("ASS") || upper.contains("SSA") -> "ASS"
            upper.contains("VTT") || upper.contains("WEBVTT") -> "VTT"
            upper.contains("SRT") || upper.contains("SUBRIP") -> "SRT"
            else -> c
        } to false
    }
}

internal fun subtitleFormatLabelFromUrl(url: String): String {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return when {
        path.endsWith(".srt") -> "SRT"
        path.endsWith(".vtt") || path.endsWith(".webvtt") -> "VTT"
        path.endsWith(".ass") || path.endsWith(".ssa") -> "ASS"
        else -> "SRT"
    }
}

internal fun trackNameLooksSdh(name: String?): Boolean {
    if (name.isNullOrBlank()) return false
    return name.contains("SDH", ignoreCase = true) ||
        name.contains("CC", ignoreCase = true) ||
        name.contains("closed caption", ignoreCase = true)
}

internal fun AiSubtitleDiagnostics.toInfoSnapshot(): SubtitleInfoDiagnosticsSnapshot =
    SubtitleInfoDiagnosticsSnapshot(
        rung = rung,
        reason = reason,
        sourceKind = sourceKind,
        sourceLabel = sourceLabel,
        sourceLanguage = sourceLanguage,
        matchScore = matchScore,
        targetLanguage = targetLanguage,
        model = model,
        userLocked = userLocked
    )
