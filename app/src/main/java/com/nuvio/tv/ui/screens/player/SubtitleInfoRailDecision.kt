package com.nuvio.tv.ui.screens.player

/**
 * Pure decision for subtitle overlay Col3 (Info content + CTAs).
 * Fatia B of overlay-info-rail: no Compose, no side effects.
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
    RESET_TO_SMART_AUTO
}

internal enum class SubtitleInfoMethodKind {
    AUTOMATIC,
    USER_SELECTED
}

internal enum class SubtitleInfoUnavailableReason {
    NO_API_KEY,
    RATE_LIMITED,
    MPV
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

internal fun decideSubtitleInfoRail(
    displayOption: SubtitleInfoOptionSnapshot?,
    isPlaybackSelected: Boolean,
    diagnostics: SubtitleInfoDiagnosticsSnapshot?,
    statusLine: String?,
    aiAvailable: Boolean,
    aiQuotaExhausted: Boolean,
    isUsingMpv: Boolean,
    userExplicitSelection: Boolean = false,
    translationActive: Boolean = false
): SubtitleInfoRailDecision {
    val content = buildInfoContent(
        displayOption = displayOption,
        diagnostics = diagnostics,
        statusLine = statusLine,
        aiAvailable = aiAvailable,
        aiQuotaExhausted = aiQuotaExhausted,
        isUsingMpv = isUsingMpv,
        translationActive = translationActive
    )
    val cta = decideInfoCta(
        displayOption = displayOption,
        isPlaybackSelected = isPlaybackSelected,
        diagnostics = diagnostics,
        aiAvailable = aiAvailable,
        aiQuotaExhausted = aiQuotaExhausted,
        isUsingMpv = isUsingMpv,
        userExplicitSelection = userExplicitSelection
    )
    return SubtitleInfoRailDecision(content = content, cta = cta)
}

private fun buildInfoContent(
    displayOption: SubtitleInfoOptionSnapshot?,
    diagnostics: SubtitleInfoDiagnosticsSnapshot?,
    statusLine: String?,
    aiAvailable: Boolean,
    aiQuotaExhausted: Boolean,
    isUsingMpv: Boolean,
    translationActive: Boolean
): SubtitleInfoContentDecision {
    if (displayOption == null && diagnostics == null && !translationActive) {
        return SubtitleInfoContentDecision(
            kind = SubtitleInfoContentKind.EMPTY,
            title = "",
            sourceChipLabel = ""
        )
    }

    val unavailable = resolveUnavailableReason(
        aiAvailable = aiAvailable,
        aiQuotaExhausted = aiQuotaExhausted,
        isUsingMpv = isUsingMpv
    )

    return when (displayOption?.kind) {
        SubtitleInfoOptionKind.AI, null -> buildAiContent(
            displayOption = displayOption,
            diagnostics = diagnostics,
            statusLine = statusLine,
            unavailable = unavailable
        )
        SubtitleInfoOptionKind.INTERNAL -> buildEmbeddedContent(
            option = displayOption,
            diagnostics = diagnostics,
            statusLine = statusLine,
            unavailable = unavailable
        )
        SubtitleInfoOptionKind.ADDON -> buildAddonContent(
            option = displayOption,
            diagnostics = diagnostics,
            statusLine = statusLine,
            unavailable = unavailable
        )
    }
}

private fun buildAiContent(
    displayOption: SubtitleInfoOptionSnapshot?,
    diagnostics: SubtitleInfoDiagnosticsSnapshot?,
    statusLine: String?,
    unavailable: SubtitleInfoUnavailableReason?
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
        unavailableReason = unavailable
    )
}

private fun buildEmbeddedContent(
    option: SubtitleInfoOptionSnapshot,
    diagnostics: SubtitleInfoDiagnosticsSnapshot?,
    @Suppress("UNUSED_PARAMETER") statusLine: String?,
    unavailable: SubtitleInfoUnavailableReason?
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
        // STATUS for AI errors/translating belongs on the AI card only (Bug 3).
    }
    return SubtitleInfoContentDecision(
        kind = SubtitleInfoContentKind.EMBEDDED,
        title = option.title,
        sourceChipLabel = option.sourceLabel,
        statusLine = null,
        fields = fields,
        matchScorePercent = option.matchScorePercent.takeIf { it > 0 },
        unavailableReason = unavailable
    )
}

private fun buildAddonContent(
    option: SubtitleInfoOptionSnapshot,
    diagnostics: SubtitleInfoDiagnosticsSnapshot?,
    @Suppress("UNUSED_PARAMETER") statusLine: String?,
    unavailable: SubtitleInfoUnavailableReason?
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
        // STATUS for AI errors/translating belongs on the AI card only (Bug 3).
    }
    return SubtitleInfoContentDecision(
        kind = SubtitleInfoContentKind.ADDON,
        title = option.title,
        sourceChipLabel = option.sourceLabel,
        statusLine = null,
        fields = fields,
        matchScorePercent = option.matchScorePercent.takeIf { it > 0 },
        unavailableReason = unavailable
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
    aiAvailable: Boolean,
    aiQuotaExhausted: Boolean,
    isUsingMpv: Boolean,
    userExplicitSelection: Boolean
): SubtitleInfoCtaDecision {
    // I4 / N4: focused but not selected → read-only, no CTA.
    if (displayOption == null || !isPlaybackSelected) {
        return SubtitleInfoCtaDecision.None
    }

    val unavailable = resolveUnavailableReason(
        aiAvailable = aiAvailable,
        aiQuotaExhausted = aiQuotaExhausted,
        isUsingMpv = isUsingMpv
    )
    val rung = diagnostics?.rung
    val userLocked = diagnostics?.userLocked == true
    val isManualAi = userLocked || rung == AiSubtitleLadderRung.MANUAL

    when (displayOption.kind) {
        SubtitleInfoOptionKind.AI -> {
            // C4: AI manual → reset to smart.
            if (isManualAi) {
                return SubtitleInfoCtaDecision(
                    action = SubtitleInfoCtaAction.RESET_TO_SMART_AUTO,
                    enabled = true,
                    focusable = true
                )
            }
            // C1: AI automatic (AI_EMBEDDED) → no CTA.
            return SubtitleInfoCtaDecision.None
        }
        SubtitleInfoOptionKind.INTERNAL, SubtitleInfoOptionKind.ADDON -> {
            // C2: classic automatic fallback → no CTA (unless user later re-selected).
            if (rung == AiSubtitleLadderRung.CLASSIC_FALLBACK && !userLocked && !userExplicitSelection) {
                return SubtitleInfoCtaDecision.None
            }
            // C3 / C5: Translate with AI; C6 when AI unavailable.
            return if (unavailable != null) {
                SubtitleInfoCtaDecision(
                    action = SubtitleInfoCtaAction.TRANSLATE_WITH_AI,
                    enabled = false,
                    focusable = false
                )
            } else {
                SubtitleInfoCtaDecision(
                    action = SubtitleInfoCtaAction.TRANSLATE_WITH_AI,
                    enabled = true,
                    focusable = true
                )
            }
        }
    }
}

private fun resolveUnavailableReason(
    aiAvailable: Boolean,
    aiQuotaExhausted: Boolean,
    isUsingMpv: Boolean
): SubtitleInfoUnavailableReason? = when {
    isUsingMpv -> SubtitleInfoUnavailableReason.MPV
    aiQuotaExhausted -> SubtitleInfoUnavailableReason.RATE_LIMITED
    !aiAvailable -> SubtitleInfoUnavailableReason.NO_API_KEY
    else -> null
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
