package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fatia B — one named unit test per DoD ID (I1–I7, C1–C6, K1).
 */
class SubtitleInfoRailDecisionTest {

    private fun aiOption(
        id: String = SubtitleAiOptionId,
        title: String = "Portuguese (Brazil)"
    ) = SubtitleInfoOptionSnapshot(
        kind = SubtitleInfoOptionKind.AI,
        id = id,
        title = title,
        sourceLabel = "AI"
    )

    private fun embeddedOption(
        id: String = "internal:0",
        title: String = "French",
        languageCode: String = "fr",
        formatLabel: String = "text",
        isBitmap: Boolean = false,
        isForced: Boolean = false,
        isSdh: Boolean = false
    ) = SubtitleInfoOptionSnapshot(
        kind = SubtitleInfoOptionKind.INTERNAL,
        id = id,
        title = title,
        sourceLabel = "Built in",
        languageLabel = "French",
        languageCode = languageCode,
        formatLabel = formatLabel,
        isBitmap = isBitmap,
        isForced = isForced,
        isSdh = isSdh,
        trackName = title
    )

    private fun addonOption(
        id: String = "addon:AIOStreams:file.srt:https://x/a.srt",
        title: String = "English",
        addonName: String = "AIOStreams",
        fileName: String = "Movie.2024.English.srt",
        formatLabel: String = "SRT",
        score: Int = 87
    ) = SubtitleInfoOptionSnapshot(
        kind = SubtitleInfoOptionKind.ADDON,
        id = id,
        title = title,
        sourceLabel = addonName,
        languageLabel = "English",
        languageCode = "en",
        matchScorePercent = score,
        formatLabel = formatLabel,
        addonName = addonName,
        fileName = fileName
    )

    private fun diagnostics(
        rung: AiSubtitleLadderRung,
        reason: String,
        userLocked: Boolean = false,
        sourceLabel: String? = "French",
        sourceLanguage: String? = "fr",
        sourceKind: AiSubtitleSourceKind? = AiSubtitleSourceKind.EMBEDDED,
        targetLanguage: String? = "pt-br",
        model: String? = "gemini"
    ) = SubtitleInfoDiagnosticsSnapshot(
        rung = rung,
        reason = reason,
        sourceKind = sourceKind,
        sourceLabel = sourceLabel,
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        model = model,
        userLocked = userLocked
    )

    private fun fieldKeys(decision: SubtitleInfoRailDecision): Set<SubtitleInfoFieldKey> =
        decision.content.fields.map { it.key }.toSet()

    // --- I1–I7 content ---

    @Test
    fun i1_preferredAi_showsSourceMethodStatusTargetModel() {
        val decision = decideSubtitleInfoRail(
            displayOption = aiOption(),
            isPlaybackSelected = true,
            diagnostics = diagnostics(
                rung = AiSubtitleLadderRung.AI_EMBEDDED,
                reason = "embedded original-language source"
            ),
            statusLine = "AI translation to preferred language",
            aiAvailable = true,
            aiQuotaExhausted = false,
            isUsingMpv = false,
            translationActive = true
        )

        assertEquals(SubtitleInfoContentKind.AI, decision.content.kind)
        val keys = fieldKeys(decision)
        assertTrue(keys.contains(SubtitleInfoFieldKey.SOURCE))
        assertTrue(keys.contains(SubtitleInfoFieldKey.METHOD))
        assertTrue(keys.contains(SubtitleInfoFieldKey.STATUS))
        assertTrue(keys.contains(SubtitleInfoFieldKey.TARGET))
        assertTrue(keys.contains(SubtitleInfoFieldKey.MODEL))
        assertEquals(SubtitleInfoMethodKind.AUTOMATIC, decision.content.methodKind)
    }

    @Test
    fun i2_languageX_embeddedX_showsEmbeddedTrackInfo() {
        val decision = decideSubtitleInfoRail(
            displayOption = embeddedOption(title = "English", languageCode = "en"),
            isPlaybackSelected = true,
            diagnostics = null,
            statusLine = null,
            aiAvailable = true,
            aiQuotaExhausted = false,
            isUsingMpv = false
        )

        assertEquals(SubtitleInfoContentKind.EMBEDDED, decision.content.kind)
        val keys = fieldKeys(decision)
        assertTrue(keys.contains(SubtitleInfoFieldKey.LANGUAGE))
        assertTrue(keys.contains(SubtitleInfoFieldKey.TRACK_NAME))
        assertTrue(keys.contains(SubtitleInfoFieldKey.FORMAT))
    }

    @Test
    fun i3_languageX_addonX_showsAddonInfo() {
        val decision = decideSubtitleInfoRail(
            displayOption = addonOption(),
            isPlaybackSelected = true,
            diagnostics = null,
            statusLine = null,
            aiAvailable = true,
            aiQuotaExhausted = false,
            isUsingMpv = false
        )

        assertEquals(SubtitleInfoContentKind.ADDON, decision.content.kind)
        val keys = fieldKeys(decision)
        assertTrue(keys.contains(SubtitleInfoFieldKey.ADDON_NAME))
        assertTrue(keys.contains(SubtitleInfoFieldKey.FILE_NAME))
        assertTrue(keys.contains(SubtitleInfoFieldKey.LANGUAGE))
        assertTrue(keys.contains(SubtitleInfoFieldKey.FORMAT))
        assertTrue(keys.contains(SubtitleInfoFieldKey.SCORE))
    }

    @Test
    fun i4_aiActive_focusOtherOption_showsFocusedReadOnlyNoCta() {
        val focusedAddon = addonOption(id = "addon:other:x:https://x/b.srt")
        val decision = decideSubtitleInfoRail(
            displayOption = focusedAddon,
            isPlaybackSelected = false, // focused, not the playback selection (AI)
            diagnostics = diagnostics(
                rung = AiSubtitleLadderRung.AI_EMBEDDED,
                reason = "embedded original-language source"
            ),
            statusLine = null,
            aiAvailable = true,
            aiQuotaExhausted = false,
            isUsingMpv = false,
            translationActive = true
        )

        assertEquals(SubtitleInfoContentKind.ADDON, decision.content.kind)
        assertEquals(SubtitleInfoCtaAction.NONE, decision.cta.action)
        assertFalse(decision.cta.canMoveFocusToCta)
    }

    @Test
    fun i5_addon_minimumFields_nameFileLanguageFormatScore() {
        val decision = decideSubtitleInfoRail(
            displayOption = addonOption(
                addonName = "OpenSubtitles",
                fileName = "Show.S01E01.en.vtt",
                formatLabel = "VTT",
                score = 92
            ),
            isPlaybackSelected = true,
            diagnostics = null,
            statusLine = null,
            aiAvailable = true,
            aiQuotaExhausted = false,
            isUsingMpv = false
        )

        val byKey = decision.content.fields.associate { it.key to it.value }
        assertEquals("OpenSubtitles", byKey[SubtitleInfoFieldKey.ADDON_NAME])
        assertEquals("Show.S01E01.en.vtt", byKey[SubtitleInfoFieldKey.FILE_NAME])
        assertNotNull(byKey[SubtitleInfoFieldKey.LANGUAGE])
        assertEquals("VTT", byKey[SubtitleInfoFieldKey.FORMAT])
        assertEquals("92%", byKey[SubtitleInfoFieldKey.SCORE])
    }

    @Test
    fun i6_embedded_minimumFields_languageTrackFormatForcedSdh() {
        val decision = decideSubtitleInfoRail(
            displayOption = embeddedOption(
                title = "English SDH",
                languageCode = "en",
                formatLabel = "PGS",
                isBitmap = true,
                isForced = true,
                isSdh = true
            ),
            isPlaybackSelected = true,
            diagnostics = null,
            statusLine = null,
            aiAvailable = true,
            aiQuotaExhausted = false,
            isUsingMpv = false
        )

        val byKey = decision.content.fields.associate { it.key to it.value }
        assertNotNull(byKey[SubtitleInfoFieldKey.LANGUAGE])
        assertEquals("English SDH", byKey[SubtitleInfoFieldKey.TRACK_NAME])
        assertTrue(byKey[SubtitleInfoFieldKey.FORMAT]!!.contains("bitmap"))
        assertTrue(byKey[SubtitleInfoFieldKey.FORMAT]!!.contains("PGS"))
        assertEquals("true", byKey[SubtitleInfoFieldKey.FORCED])
        assertEquals("true", byKey[SubtitleInfoFieldKey.SDH])
    }

    @Test
    fun i7_ai_minimumFields_sourceMethodRungStatusTargetModel() {
        val decision = decideSubtitleInfoRail(
            displayOption = aiOption(),
            isPlaybackSelected = true,
            diagnostics = diagnostics(
                rung = AiSubtitleLadderRung.MANUAL,
                reason = "user chose translate with AI",
                userLocked = true,
                sourceLabel = "AIOStreams",
                sourceLanguage = "en",
                sourceKind = AiSubtitleSourceKind.ADDON
            ),
            statusLine = "Translating…",
            aiAvailable = true,
            aiQuotaExhausted = false,
            isUsingMpv = false,
            translationActive = true
        )

        assertEquals(SubtitleInfoContentKind.AI, decision.content.kind)
        assertEquals(SubtitleInfoMethodKind.USER_SELECTED, decision.content.methodKind)
        val keys = fieldKeys(decision)
        assertTrue(keys.contains(SubtitleInfoFieldKey.SOURCE))
        assertTrue(keys.contains(SubtitleInfoFieldKey.METHOD))
        assertTrue(keys.contains(SubtitleInfoFieldKey.RUNG))
        assertTrue(keys.contains(SubtitleInfoFieldKey.REASON))
        assertTrue(keys.contains(SubtitleInfoFieldKey.STATUS))
        assertTrue(keys.contains(SubtitleInfoFieldKey.TARGET))
        assertTrue(keys.contains(SubtitleInfoFieldKey.MODEL))
        assertEquals(
            "User selected",
            decision.content.fields.first { it.key == SubtitleInfoFieldKey.METHOD }.value
        )
    }

    // --- C1–C6 CTAs ---

    @Test
    fun c1_automaticAiEmbedded_showsNoCta() {
        val decision = decideSubtitleInfoRail(
            displayOption = aiOption(),
            isPlaybackSelected = true,
            diagnostics = diagnostics(
                rung = AiSubtitleLadderRung.AI_EMBEDDED,
                reason = "embedded text source",
                userLocked = false
            ),
            statusLine = "AI translation to preferred language",
            aiAvailable = true,
            aiQuotaExhausted = false,
            isUsingMpv = false,
            translationActive = true
        )

        assertEquals(SubtitleInfoCtaAction.NONE, decision.cta.action)
        assertFalse(decision.cta.canMoveFocusToCta)
        assertTrue(fieldKeys(decision).isNotEmpty()) // diagnostics still present
    }

    @Test
    fun c2_classicFallback_autoChosen_showsNoCta() {
        val classicReason = "classic preferred-language auto-select (primary only)"
        val decision = decideSubtitleInfoRail(
            displayOption = addonOption(),
            isPlaybackSelected = true,
            diagnostics = diagnostics(
                rung = AiSubtitleLadderRung.CLASSIC_FALLBACK,
                reason = classicReason,
                userLocked = false,
                sourceLabel = null,
                sourceLanguage = null,
                sourceKind = null
            ),
            statusLine = null,
            aiAvailable = true,
            aiQuotaExhausted = false,
            isUsingMpv = false,
            userExplicitSelection = false
        )

        assertEquals(SubtitleInfoCtaAction.NONE, decision.cta.action)
        assertEquals(SubtitleInfoContentKind.ADDON, decision.content.kind)
        val byKey = decision.content.fields.associate { it.key to it.value }
        assertEquals(AiSubtitleLadderRung.CLASSIC_FALLBACK.name, byKey[SubtitleInfoFieldKey.RUNG])
        assertEquals(classicReason, byKey[SubtitleInfoFieldKey.REASON])
    }

    @Test
    fun c3_preferredEmbedded_automatic_showsTranslateCta() {
        val decision = decideSubtitleInfoRail(
            displayOption = embeddedOption(languageCode = "pt-br", title = "Portuguese"),
            isPlaybackSelected = true,
            diagnostics = diagnostics(
                rung = AiSubtitleLadderRung.PREFERRED_EMBEDDED,
                reason = "preferred-language embedded available",
                userLocked = false,
                sourceLanguage = "pt-br"
            ),
            statusLine = null,
            aiAvailable = true,
            aiQuotaExhausted = false,
            isUsingMpv = false
        )

        assertEquals(SubtitleInfoCtaAction.TRANSLATE_WITH_AI, decision.cta.action)
        assertTrue(decision.cta.enabled)
        assertTrue(decision.cta.focusable)
        assertTrue(decision.cta.canMoveFocusToCta)
    }

    @Test
    fun c4_manualAi_showsResetSmartCta() {
        val decision = decideSubtitleInfoRail(
            displayOption = aiOption(),
            isPlaybackSelected = true,
            diagnostics = diagnostics(
                rung = AiSubtitleLadderRung.MANUAL,
                reason = "user chose translate with AI",
                userLocked = true
            ),
            statusLine = "AI translation to preferred language",
            aiAvailable = true,
            aiQuotaExhausted = false,
            isUsingMpv = false,
            translationActive = true
        )

        assertEquals(SubtitleInfoCtaAction.RESET_TO_SMART_AUTO, decision.cta.action)
        assertTrue(decision.cta.enabled)
        assertTrue(decision.cta.focusable)
        assertTrue(decision.cta.canMoveFocusToCta)
    }

    @Test
    fun c5_embeddedOrAddon_anyOrigin_showsTranslateCta() {
        val embedded = decideSubtitleInfoRail(
            displayOption = embeddedOption(),
            isPlaybackSelected = true,
            diagnostics = null,
            statusLine = null,
            aiAvailable = true,
            aiQuotaExhausted = false,
            isUsingMpv = false,
            userExplicitSelection = true
        )
        val addon = decideSubtitleInfoRail(
            displayOption = addonOption(),
            isPlaybackSelected = true,
            diagnostics = null,
            statusLine = null,
            aiAvailable = true,
            aiQuotaExhausted = false,
            isUsingMpv = false,
            userExplicitSelection = true
        )

        assertEquals(SubtitleInfoCtaAction.TRANSLATE_WITH_AI, embedded.cta.action)
        assertTrue(embedded.cta.enabled && embedded.cta.focusable)
        assertEquals(SubtitleInfoCtaAction.TRANSLATE_WITH_AI, addon.cta.action)
        assertTrue(addon.cta.enabled && addon.cta.focusable)
    }

    @Test
    fun c6_aiUnavailable_showsDisabledNonFocusableTranslateAndReason() {
        val noKey = decideSubtitleInfoRail(
            displayOption = embeddedOption(),
            isPlaybackSelected = true,
            diagnostics = diagnostics(
                rung = AiSubtitleLadderRung.PREFERRED_EMBEDDED,
                reason = "preferred-language embedded available"
            ),
            statusLine = null,
            aiAvailable = false,
            aiQuotaExhausted = false,
            isUsingMpv = false
        )
        val rateLimited = decideSubtitleInfoRail(
            displayOption = addonOption(),
            isPlaybackSelected = true,
            diagnostics = null,
            statusLine = null,
            aiAvailable = true,
            aiQuotaExhausted = true,
            isUsingMpv = false,
            userExplicitSelection = true
        )
        val mpv = decideSubtitleInfoRail(
            displayOption = embeddedOption(),
            isPlaybackSelected = true,
            diagnostics = null,
            statusLine = null,
            aiAvailable = true,
            aiQuotaExhausted = false,
            isUsingMpv = true,
            userExplicitSelection = true
        )

        listOf(noKey, rateLimited, mpv).forEach { decision ->
            assertEquals(SubtitleInfoCtaAction.TRANSLATE_WITH_AI, decision.cta.action)
            assertFalse(decision.cta.enabled)
            assertFalse(decision.cta.focusable)
            assertFalse(decision.cta.canMoveFocusToCta)
            assertNotNull(decision.content.unavailableReason)
        }
        assertEquals(SubtitleInfoUnavailableReason.NO_API_KEY, noKey.content.unavailableReason)
        assertEquals(SubtitleInfoUnavailableReason.RATE_LIMITED, rateLimited.content.unavailableReason)
        assertEquals(SubtitleInfoUnavailableReason.MPV, mpv.content.unavailableReason)
    }

    // --- K1 ---

    @Test
    fun k1_preferredLanguageCount_includesSyntheticAiWhenListed() {
        assertEquals(1, languageRailCountIncludingAi(trackAndAddonCount = 0, aiOptionListed = true))
        assertEquals(3, languageRailCountIncludingAi(trackAndAddonCount = 2, aiOptionListed = true))
        assertEquals(2, languageRailCountIncludingAi(trackAndAddonCount = 2, aiOptionListed = false))

        // Preferred = en; one embedded EN + synthetic AI → count 2.
        val withAi = buildSubtitleLanguageRailItems(
            internalTracks = listOf(
                TrackInfo(index = 0, name = "English", language = "eng", codec = "SRT")
            ),
            addonSubtitles = emptyList(),
            preferredLanguage = "en",
            secondaryPreferredLanguage = null,
            showOnlyPreferredLanguages = false,
            currentLanguageKey = "en",
            noneLabel = "None",
            unknownLabel = "Unknown",
            includeSyntheticAiOnPreferred = true
        )
        val withoutAi = buildSubtitleLanguageRailItems(
            internalTracks = listOf(
                TrackInfo(index = 0, name = "English", language = "eng", codec = "SRT")
            ),
            addonSubtitles = emptyList(),
            preferredLanguage = "en",
            secondaryPreferredLanguage = null,
            showOnlyPreferredLanguages = false,
            currentLanguageKey = "en",
            noneLabel = "None",
            unknownLabel = "Unknown",
            includeSyntheticAiOnPreferred = false
        )

        assertEquals(2, withAi.first { it.key == "en" }.count)
        assertEquals(1, withoutAi.first { it.key == "en" }.count)

        // Preferred with zero tracks still appears with count 1 (AI only).
        val aiOnlyPreferred = buildSubtitleLanguageRailItems(
            internalTracks = listOf(
                TrackInfo(index = 0, name = "French", language = "fra", codec = "SRT")
            ),
            addonSubtitles = emptyList(),
            preferredLanguage = "es",
            secondaryPreferredLanguage = null,
            showOnlyPreferredLanguages = false,
            currentLanguageKey = "fr",
            noneLabel = "None",
            unknownLabel = "Unknown",
            includeSyntheticAiOnPreferred = true
        )
        assertEquals(1, aiOnlyPreferred.first { it.key == "es" }.count)
    }
}
