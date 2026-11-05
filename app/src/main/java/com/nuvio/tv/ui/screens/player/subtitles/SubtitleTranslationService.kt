package com.nuvio.tv.ui.screens.player.subtitles

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.nuvio.tv.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "SubtitleTranslation"
private const val HTTP_TAG = "SubtitleAiHttp"

data class TranslationResult(
    val lines: List<String>,
    val success: Boolean,
    val errorMessage: String? = null,
    /**
     * Indices whose [lines] entry is NOT a usable translation (missing from the response, echoed
     * source, blank...). Those slots hold the source text as a placeholder and must never be cached.
     */
    val rejected: Set<Int> = emptySet(),
    /** Provider-stated wait before this key can be used again (Gemini 429 `retryDelay`). */
    val retryAfterMs: Long? = null
)

/** Bump whenever the prompt changes in a way that should invalidate persisted translations. */
internal const val SUBTITLE_PROMPT_VERSION = 3

/** Request exceeded the caller's call timeout (short timeouts are used for on-screen cues). */
const val TRANSLATION_ERROR_TIMEOUT = "TIMEOUT"

// Marker error for Gemini's non-configurable content-policy rejections (finishReason
// PROHIBITED_CONTENT etc. — safetySettings BLOCK_NONE does NOT cover these). Blocked batches are
// bisected so one flagged line doesn't lose the whole window, and the caller must not toast it:
// it's not actionable and translation keeps working for everything else.
const val TRANSLATION_ERROR_CONTENT_BLOCKED = "CONTENT_BLOCKED"

private val BLOCKED_FINISH_REASONS = setOf(
    "PROHIBITED_CONTENT", "SAFETY", "RECITATION", "BLOCKLIST", "SPII"
)

// llama-3.3-70b-versatile was decommissioned by Groq (August 16, 2026). gpt-oss-120b is Groq's
// recommended replacement: ~500 tok/s (vs 280), same 30 RPM free tier, 2x the daily token budget.
// It is a reasoning model — see GROQ_REASONING_EFFORT below.
private const val GROQ_MODEL_ID = "openai/gpt-oss-120b"
private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
// gpt-oss cannot disable reasoning entirely (unlike Gemini's thinkingLevel=minimal or Qwen's
// reasoning_effort=none) — "low" is the floor. Anything higher spends seconds of thinking on what
// is a mechanical translation, which subtitles can't wait for. "hidden" keeps the chain-of-thought
// out of message.content so extractJsonArray() doesn't have to parse around it.
private const val GROQ_REASONING_EFFORT = "low"
private const val GROQ_REASONING_FORMAT = "hidden"
// gemini-2.5-flash was retired by Google (HTTP 404 "no longer available", July 2026).
// gemini-3.5-flash-lite: ~2x throughput (350 vs 165 tok/s) and ~3.5x cheaper than 3.5-flash,
// positioned by Google for high-volume translation. Same v1beta API + thinkingLevel field.
private const val GEMINI_MODEL_ID = "gemini-3.5-flash-lite"
private const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL_ID:generateContent"
// Haiku is the right latency/cost tradeoff for subtitle windows; Sonnet/Opus are overkill.
private const val CLAUDE_MODEL_ID = "claude-haiku-4-5"
private const val CLAUDE_URL = "https://api.anthropic.com/v1/messages"
private const val CLAUDE_API_VERSION = "2023-06-01"
private const val CLAUDE_MAX_TOKENS = 4096
private const val CLAUDE_MAX_TOKENS_BULK = 8192

// Transient-failure retry (server overload / flaky network). 503/500/502 and IOExceptions are
// Google-side capacity blips that usually clear within ~1s; retry the same batch a couple times
// with short exponential backoff before falling back to English. Kept short so subtitles stay in
// sync with playback. NOT applied to 429 (that's our own quota — retrying fast makes it worse;
// the manager's 5s cooldown handles it) or 4xx/content blocks (retrying won't change the outcome).
private const val GEMINI_MAX_TRANSIENT_RETRIES = 2        // 3 attempts total
private const val GEMINI_RETRY_BASE_DELAY_MS = 400L       // 400ms, then 800ms (×2 each retry)
// Free tier for flash-lite is ~15 RPM. Pace requests so preTranslate + live batches do not
// instantly exhaust the minute budget and leave translation stuck on RATE_LIMITED.
private val GEMINI_RETRYABLE_HTTP = setOf(500, 502, 503)

// DEBUG-only test hook: `adb shell setprop debug.nuvio.ai_base_url http://10.0.2.2:8787` redirects every
// provider (translate + ping + match) to a local mock server; only scheme/host/port are replaced, the
// original path and query are kept. Clear with `setprop debug.nuvio.ai_base_url ""`. Read per request.
private const val DEBUG_AI_BASE_URL_PROP = "debug.nuvio.ai_base_url"
private val lastLoggedAiBaseUrlOverride = java.util.concurrent.atomic.AtomicReference<String?>(null)

private fun readDebugAiBaseUrlOverride(): String? {
    // Not BuildConfig.DEBUG: the debug build type ships with isDebuggable = false.
    if (BuildConfig.BUILD_TYPE != "debug") return null
    return try {
        val cls = Class.forName("android.os.SystemProperties")
        val value = cls.getMethod("get", String::class.java).invoke(null, DEBUG_AI_BASE_URL_PROP) as? String
        value?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }
}

/** Replaces scheme+host+port of [original] with those of [override]; path/query are preserved. */
internal fun rewriteBaseUrl(original: String, override: String?): String {
    if (override.isNullOrBlank()) return original
    val target = override.trim().toHttpUrlOrNull() ?: return original
    val source = original.toHttpUrlOrNull() ?: return original
    return source.newBuilder()
        .scheme(target.scheme)
        .host(target.host)
        .port(target.port)
        .build()
        .toString()
}

private fun aiProviderUrl(original: String): String {
    val override = readDebugAiBaseUrlOverride()
    if (override != lastLoggedAiBaseUrlOverride.getAndSet(override) && override != null) {
        Log.w(HTTP_TAG, "DEBUG AI base URL override ACTIVE: $override (providers redirected, path/query kept)")
    }
    return rewriteBaseUrl(original, override)
}

class SubtitleTranslationService(
    private val apiKeyProvider: () -> String = { "" },
    private val modelProvider: () -> SubtitleAiModel = { SubtitleAiModel.GROQ_LLAMA_70B }
) {
    /** Min gap between Gemini HTTP calls; overridable from advanced settings. */
    @Volatile
    var geminiMinIntervalMs: Long =
        SubtitleAiAdvancedSettings.DEFAULT_GEMINI_MIN_INTERVAL_MS.toLong()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val geminiRequestLock = Mutex()
    private val lastGeminiRequestAtMs = HashMap<String, Long>()

    /**
     * Reserves the next Gemini slot for [apiKey] (guarded by the mutex) and returns how long the
     * caller must wait. Spacing is per key: each key may belong to a distinct AI Studio project
     * with its own RPM, so one waiting key must not block another.
     */
    internal suspend fun reserveGeminiSlot(apiKey: String, nowMs: Long = System.currentTimeMillis()): Long =
        geminiRequestLock.withLock {
            val last = synchronized(lastGeminiRequestAtMs) { lastGeminiRequestAtMs[apiKey] }
            val waitMs = if (last == null) 0L else (geminiMinIntervalMs - (nowMs - last)).coerceAtLeast(0L)
            synchronized(lastGeminiRequestAtMs) { lastGeminiRequestAtMs[apiKey] = nowMs + waitMs }
            waitMs
        }

    private suspend fun throttleGeminiRequests(apiKey: String): Long {
        val waitMs = reserveGeminiSlot(apiKey)
        if (waitMs > 0L) delay(waitMs)
        return waitMs
    }

    /** How long a Gemini request with [apiKey] would wait for its min-interval slot right now. */
    fun geminiReadyInMs(apiKey: String, nowMs: Long = System.currentTimeMillis()): Long {
        val last = synchronized(lastGeminiRequestAtMs) { lastGeminiRequestAtMs[apiKey] } ?: return 0L
        return (geminiMinIntervalMs - (nowMs - last)).coerceAtLeast(0L)
    }

    private fun executeCall(request: Request, callTimeoutMs: Long?): okhttp3.Response {
        // The shared client reads for 30 s; a long bulk call has to outlive that as well.
        val http = if (callTimeoutMs != null && callTimeoutMs > 30_000L) {
            client.newBuilder().readTimeout(callTimeoutMs, TimeUnit.MILLISECONDS).build()
        } else {
            client
        }
        val call = http.newCall(request)
        if (callTimeoutMs != null) call.timeout().timeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        return call.execute()
    }

    private fun extractJsonArray(text: String): JSONArray? =
        SubtitleAiResponseJsonParser.extractJsonArray(text)

    private fun buildSystemPrompt(targetLanguage: String, NL: String) =
        "You are a professional subtitle translator. Translate the following JSON array into natural $targetLanguage.\n" +
        "Rules:\n" +
        "1. Return ONLY a valid JSON array of strings.\n" +
        "2. Every input element starts with a numeric prefix like '7: ' — keep the EXACT same numeric prefix on the corresponding translated element. Never merge or split elements.\n" +
        "3. Keep the exact same order and element count.\n" +
        "4. Preserve the '$NL' symbol exactly where it appears as a line break.\n" +
        "5. Use informal, spoken $targetLanguage suitable for cinema.\n" +
        "6. If a 'Context' block is provided, those previous lines are only for coherence, gender and pronouns. Never translate them and never include them in the output."

    /** Gemini structured-output variant: `{"i","t"}` objects instead of "7: " string prefixes. */
    private fun buildGeminiSystemPrompt(targetLanguage: String, NL: String) =
        "You are a professional subtitle translator. Translate the following JSON array into natural $targetLanguage.\n" +
        "Rules:\n" +
        "1. Each input element is an object {\"i\": <id>, \"t\": <text>}. Return a JSON array with exactly one {\"i\", \"t\"} object per input id, where \"t\" is the translation of that element's \"t\".\n" +
        "2. Keep the EXACT same ids. Never merge or split elements.\n" +
        "3. Keep the exact same order and element count.\n" +
        "4. Preserve the '$NL' symbol exactly where it appears as a line break.\n" +
        "5. Use informal, spoken $targetLanguage suitable for cinema.\n" +
        "6. If a 'Context' block is provided, those previous lines are only for coherence, gender and pronouns. Never translate them and never include them in the output."

    private fun encodeStructured(lines: List<String>, NL: String): JSONArray =
        JSONArray().apply {
            lines.forEachIndexed { i, line ->
                put(JSONObject().apply { put("i", i); put("t", line.replace("\n", NL)) })
            }
        }

    private fun geminiResponseSchema(): JSONObject = JSONObject().apply {
        put("type", "ARRAY")
        put("items", JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply {
                put("i", JSONObject().apply { put("type", "INTEGER") })
                put("t", JSONObject().apply { put("type", "STRING") })
            })
            put("required", JSONArray(listOf("i", "t")))
            put("propertyOrdering", JSONArray(listOf("i", "t")))
        })
    }

    private fun encodeIndexed(lines: List<String>, NL: String): JSONArray =
        JSONArray(lines.mapIndexed { i, line -> "$i: ${line.replace("\n", NL)}" })

    // ── Semantic line matching (subtitle sync) ──────────────────────────────────

    /** One confident pairing: reference line [referenceIndex] says the same thing as [candidateIndex]. */
    data class LineMatch(val referenceIndex: Int, val candidateIndex: Int)

    private fun buildMatchPrompt() =
        "You align two subtitle files for the SAME video, written in different languages.\n" +
        "You are given REFERENCE lines and CANDIDATE lines, each prefixed with its index.\n" +
        "Rules:\n" +
        "1. Return ONLY a valid JSON array of objects, each exactly {\"r\": <reference index>, \"c\": <candidate index>}.\n" +
        "2. Pair a reference line with the candidate line that expresses the SAME dialogue, across languages.\n" +
        "3. OMIT any reference line you are not confident about. A short, correct list beats a complete, guessed one.\n" +
        "4. Never translate, never invent text, never output anything except the JSON array.\n" +
        "5. Each index may appear at most once."

    /**
     * Asks the model which lines of two differently-languaged subtitle files say the same thing.
     *
     * This is a *measuring instrument*, not a decision: the caller turns the returned pairings into
     * a timing offset and then re-scores it with the ordinary timing metric, so a hallucinated
     * pairing can only fail to improve the score — never select a subtitle on its own.
     *
     * Why it exists: our overlap-maximising offset search is a proxy for alignment that degrades
     * when the two files are segmented differently (an English CC track splits lines that a
     * translation merges). Matching by meaning is segmentation-independent.
     */
    suspend fun matchSubtitleLines(
        referenceLines: List<String>,
        candidateLines: List<String>
    ): List<LineMatch>? {
        if (referenceLines.isEmpty() || candidateLines.isEmpty()) return null
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) return null
        val prompt = buildMatchPrompt()
        val payload = "REFERENCE:\n${encodeIndexed(referenceLines, " ")}\n\nCANDIDATE:\n${encodeIndexed(candidateLines, " ")}"
        val raw = when (modelProvider()) {
            SubtitleAiModel.GROQ_LLAMA_70B -> matchGroq(prompt, payload, apiKey)
            SubtitleAiModel.GEMINI_FLASH_25 -> matchGemini(prompt, payload, apiKey)
            SubtitleAiModel.CLAUDE_HAIKU -> matchClaude(prompt, payload, apiKey)
        } ?: return null
        val array = extractJsonArray(raw) ?: return null
        val matches = ArrayList<LineMatch>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val r = obj.optInt("r", -1)
            val c = obj.optInt("c", -1)
            if (r !in referenceLines.indices || c !in candidateLines.indices) continue
            matches.add(LineMatch(r, c))
        }
        // Each index at most once — a model that maps several references onto one candidate line is
        // guessing, and those pairs would all vote the same bogus offset.
        val seenRef = HashSet<Int>()
        val seenCand = HashSet<Int>()
        return matches.filter { seenRef.add(it.referenceIndex) && seenCand.add(it.candidateIndex) }
    }

    private suspend fun matchGroq(systemPrompt: String, payload: String, apiKey: String): String? {
        val body = JSONObject().apply {
            put("model", GROQ_MODEL_ID)
            put("temperature", 0)
            put("reasoning_effort", GROQ_REASONING_EFFORT)
            put("reasoning_format", GROQ_REASONING_FORMAT)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                put(JSONObject().apply { put("role", "user"); put("content", payload) })
            })
        }
        val request = Request.Builder()
            .url(aiProviderUrl(GROQ_URL))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    if (!response.isSuccessful || responseBody == null) {
                        Log.w(TAG, "matchSubtitleLines groq HTTP ${response.code}")
                        return@use null
                    }
                    JSONObject(responseBody).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content").trim()
                }
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                Log.w(TAG, "matchSubtitleLines groq failed: ${it.message}")
            }.getOrNull()
        }
    }

    private suspend fun matchGemini(systemPrompt: String, payload: String, apiKey: String): String? {
        val body = JSONObject().apply {
            put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().apply { put(JSONObject().apply { put("text", systemPrompt) }) })
            })
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply { put(JSONObject().apply { put("text", payload) }) })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0)
                put("responseMimeType", "application/json")
                put("thinkingConfig", JSONObject().apply { put("thinkingLevel", "minimal") })
            })
            // Same reason as translation: this is the film's own dialogue, and default safety
            // filters block such prompts outright (HTTP 200 with no candidates).
            put("safetySettings", JSONArray().apply {
                listOf(
                    "HARM_CATEGORY_HARASSMENT",
                    "HARM_CATEGORY_HATE_SPEECH",
                    "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                    "HARM_CATEGORY_DANGEROUS_CONTENT"
                ).forEach { category ->
                    put(JSONObject().apply { put("category", category); put("threshold", "BLOCK_NONE") })
                }
            })
        }
        val request = Request.Builder()
            .url(aiProviderUrl("$GEMINI_BASE_URL?key=$apiKey"))
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    if (!response.isSuccessful || responseBody == null) {
                        Log.w(TAG, "matchSubtitleLines gemini HTTP ${response.code}")
                        return@use null
                    }
                    JSONObject(responseBody).getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                        .getString("text").trim()
                }
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                Log.w(TAG, "matchSubtitleLines gemini failed: ${it.message}")
            }.getOrNull()
        }
    }

    suspend fun translateBatch(lines: List<String>, targetLanguage: String): TranslationResult =
        translateBatchInternal(lines, targetLanguage, depth = 0)

    /** Explicit provider+key call used by [SubtitleAiRouter] (multi-key / multi-provider). */
    suspend fun translateWith(
        model: SubtitleAiModel,
        apiKey: String,
        lines: List<String>,
        targetLanguage: String,
        context: List<String> = emptyList(),
        callTimeoutMs: Long? = null
    ): ProviderAttemptResult {
        if (lines.isEmpty()) {
            return ProviderAttemptResult(TranslationResult(lines, true))
        }
        if (apiKey.isBlank()) {
            return ProviderAttemptResult(TranslationResult(lines, false, "API key missing"), httpCode = 401)
        }
        return translateWithInternal(model, apiKey, lines, targetLanguage, depth = 0, context = context, callTimeoutMs = callTimeoutMs)
    }

    private suspend fun translateWithInternal(
        model: SubtitleAiModel,
        apiKey: String,
        lines: List<String>,
        targetLanguage: String,
        depth: Int,
        context: List<String> = emptyList(),
        callTimeoutMs: Long? = null
    ): ProviderAttemptResult {
        val translation = when (model) {
            SubtitleAiModel.GROQ_LLAMA_70B -> translateGroq(lines, targetLanguage, apiKey, context, callTimeoutMs)
            SubtitleAiModel.GEMINI_FLASH_25 -> translateGemini(lines, targetLanguage, apiKey, context = context, callTimeoutMs = callTimeoutMs)
            SubtitleAiModel.CLAUDE_HAIKU -> translateClaude(lines, targetLanguage, apiKey, context = context, callTimeoutMs = callTimeoutMs)
        }
        val attempt = ProviderAttemptResult(
            translation = translation,
            httpCode = when (translation.errorMessage) {
                "RATE_LIMITED" -> 429
                "API key missing" -> 401
                else -> null
            },
            // A spent daily quota says "retry in 12 h": without this the router retries every 60 s.
            quota = translation.retryAfterMs?.let { waitMs ->
                SubtitleAiQuotaSnapshot(
                    model = model,
                    keySuffix = maskApiKey(apiKey).removePrefix("••••"),
                    remainingRequests = 0L,
                    resetHint = "${waitMs / 1000}s",
                    cooldownUntilMs = System.currentTimeMillis() + waitMs
                )
            }
        )
        if (!attempt.translation.success &&
            attempt.translation.errorMessage == TRANSLATION_ERROR_CONTENT_BLOCKED &&
            lines.size >= 2 && depth < 2
        ) {
            val mid = lines.size / 2
            val left = translateWithInternal(model, apiKey, lines.subList(0, mid), targetLanguage, depth + 1, context, callTimeoutMs)
            val right = translateWithInternal(model, apiKey, lines.subList(mid, lines.size), targetLanguage, depth + 1, context, callTimeoutMs)
            val rejected = HashSet<Int>()
            if (left.translation.success) rejected.addAll(left.translation.rejected)
            else rejected.addAll(0 until mid)
            if (right.translation.success) right.translation.rejected.mapTo(rejected) { it + mid }
            else rejected.addAll(mid until lines.size)
            return ProviderAttemptResult(
                translation = TranslationResult(left.translation.lines + right.translation.lines, true, rejected = rejected),
                httpCode = attempt.httpCode,
                quota = attempt.quota ?: left.quota ?: right.quota
            )
        }
        return attempt
    }

    /** Lightweight auth/model reachability check for settings UI. */
    suspend fun ping(model: SubtitleAiModel, apiKey: String): ProviderPingHttpResult {
        if (apiKey.isBlank()) {
            return ProviderPingHttpResult(false, "Empty key", httpCode = 401)
        }
        return when (model) {
            SubtitleAiModel.GROQ_LLAMA_70B -> pingGroq(apiKey)
            SubtitleAiModel.GEMINI_FLASH_25 -> pingGemini(apiKey)
            SubtitleAiModel.CLAUDE_HAIKU -> pingClaude(apiKey)
        }
    }

    private suspend fun translateBatchInternal(
        lines: List<String>,
        targetLanguage: String,
        depth: Int
    ): TranslationResult {
        if (lines.isEmpty()) return TranslationResult(lines, true)
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) {
            Log.e(TAG, "API key is blank — translation skipped")
            return TranslationResult(lines, false, "API key missing")
        }

        val result = when (modelProvider()) {
            SubtitleAiModel.GROQ_LLAMA_70B -> translateGroq(lines, targetLanguage, apiKey)
            SubtitleAiModel.GEMINI_FLASH_25 -> translateGemini(lines, targetLanguage, apiKey)
            SubtitleAiModel.CLAUDE_HAIKU -> translateClaude(lines, targetLanguage, apiKey)
        }

        // A content-policy block poisons the whole window because of (usually) one line.
        // Bisect so the innocuous majority still gets translated; a line that stays blocked all
        // the way down is shown in its original language. Depth cap keeps worst-case extra API
        // calls bounded (window → halves → quarters).
        if (!result.success &&
            result.errorMessage == TRANSLATION_ERROR_CONTENT_BLOCKED &&
            lines.size >= 2 && depth < 2
        ) {
            Log.w(TAG, "Content-blocked batch of ${lines.size} — bisecting (depth=$depth)")
            val mid = lines.size / 2
            val left = translateBatchInternal(lines.subList(0, mid), targetLanguage, depth + 1)
            val right = translateBatchInternal(lines.subList(mid, lines.size), targetLanguage, depth + 1)
            // Best-effort merge: failed halves already carry their original lines.
            val rejected = HashSet<Int>()
            if (left.success) rejected.addAll(left.rejected) else rejected.addAll(0 until mid)
            if (right.success) right.rejected.mapTo(rejected) { it + mid } else rejected.addAll(mid until lines.size)
            return TranslationResult(left.lines + right.lines, true, rejected = rejected)
        }
        return result
    }

    private suspend fun translateGroq(
        lines: List<String>,
        targetLanguage: String,
        apiKey: String,
        context: List<String> = emptyList(),
        callTimeoutMs: Long? = null
    ): TranslationResult {
        val NL = "⏎"
        val inputArray = encodeIndexed(lines, NL)
        val systemPrompt = buildSystemPrompt(targetLanguage, NL)

        val body = JSONObject().apply {
            put("model", GROQ_MODEL_ID)
            put("temperature", 0.1)
            put("reasoning_effort", GROQ_REASONING_EFFORT)
            put("reasoning_format", GROQ_REASONING_FORMAT)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", buildTranslateUserMessage(inputArray.toString(), targetLanguage, context))
                })
            })
        }

        val request = Request.Builder()
            .url(aiProviderUrl(GROQ_URL))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return withContext(Dispatchers.IO) {
            try {
                val response = executeCall(request, callTimeoutMs)
                val responseBody = response.body?.string() ?: run {
                    Log.e(TAG, "Empty response body (HTTP ${response.code})")
                    return@withContext TranslationResult(lines, false, "Empty response (${response.code})")
                }
                if (!response.isSuccessful) {
                    val errorMsg = if (response.code == 429) "RATE_LIMITED" else "HTTP ${response.code}: $responseBody"
                    return@withContext TranslationResult(lines, false, errorMsg)
                }

                val json = JSONObject(responseBody)
                val rawText = json
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()

                parseTranslationResult(lines, targetLanguage, rawText, NL)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (callTimeoutMs != null && e is java.io.InterruptedIOException) {
                    return@withContext TranslationResult(lines, false, TRANSLATION_ERROR_TIMEOUT)
                }

                Log.e(TAG, "translateGroq exception: ${e.message}", e)
                TranslationResult(lines, false, e.message)
            }
        }
    }

    /** ±30% jittered exponential backoff so a fleet hitting the same overload spike doesn't re-sync. */
    private suspend fun geminiBackoff(transientAttempt: Int) {
        val factor = 1L shl transientAttempt          // 2^transientAttempt: 1, 2, ...
        val jitter = 0.7 + Math.random() * 0.6        // 0.7 .. 1.3
        delay((GEMINI_RETRY_BASE_DELAY_MS * factor * jitter).toLong())
    }

    private suspend fun translateGemini(
        lines: List<String>,
        targetLanguage: String,
        apiKey: String,
        attempt: Int = 0,
        transientAttempt: Int = 0,
        context: List<String> = emptyList(),
        callTimeoutMs: Long? = null
    ): TranslationResult {
        val throttleWaitMs = throttleGeminiRequests(apiKey)
        val NL = "⏎"
        val inputArray = encodeStructured(lines, NL)
        val systemPrompt = buildGeminiSystemPrompt(targetLanguage, NL)
        val keySuffix = apiKey.takeLast(4)
        val preview = lines.firstOrNull()?.take(80).orEmpty()

        val body = JSONObject().apply {
            put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", systemPrompt) })
                })
            })
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", buildTranslateUserMessage(inputArray.toString(), targetLanguage, context))
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("responseMimeType", "application/json")
                put("responseSchema", geminiResponseSchema())
                put("thinkingConfig", JSONObject().apply {
                    // Subtitles need raw inference speed, not reasoning. Measured on
                    // gemini-3.5-flash (July 2026): default = ~5s + 875 thought tokens per batch;
                    // minimal = ~1.3s + 0. "thinkingLevel" is the Gemini 3.x field (the legacy
                    // 2.5 "thinkingBudget: 0" still works there today, but is deprecated).
                    put("thinkingLevel", "minimal")
                })
            })
            // Movie dialogue routinely contains violence/profanity/sexual references; default
            // safety filters block such prompts outright (HTTP 200 with NO candidates — seen as
            // "translation error" toasts at episode start on gemini-3.5-flash). We're translating
            // the film's own subtitle text, not generating new content.
            put("safetySettings", JSONArray().apply {
                listOf(
                    "HARM_CATEGORY_HARASSMENT",
                    "HARM_CATEGORY_HATE_SPEECH",
                    "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                    "HARM_CATEGORY_DANGEROUS_CONTENT"
                ).forEach { category ->
                    put(JSONObject().apply {
                        put("category", category)
                        put("threshold", "BLOCK_NONE")
                    })
                }
            })
        }

        val request = Request.Builder()
            .url(aiProviderUrl(GEMINI_BASE_URL))
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        Log.i(
            HTTP_TAG,
            "Gemini POST start lines=${lines.size} key=…$keySuffix " +
                "throttleWaitMs=$throttleWaitMs attempt=$attempt transient=$transientAttempt " +
                "preview=$preview"
        )
        val httpStartedAt = System.currentTimeMillis()

        return withContext(Dispatchers.IO) {
            try {
                val response = executeCall(request, callTimeoutMs)
                val latencyMs = System.currentTimeMillis() - httpStartedAt
                val responseBody = response.body?.string() ?: run {
                    Log.e(TAG, "Empty Gemini response body (HTTP ${response.code})")
                    Log.w(
                        HTTP_TAG,
                        "Gemini POST end lines=${lines.size} key=…$keySuffix " +
                            "httpCode=${response.code} latencyMs=$latencyMs " +
                            "throttleWaitMs=$throttleWaitMs success=false error=empty_body"
                    )
                    return@withContext TranslationResult(lines, false, "Empty response (${response.code})")
                }
                if (!response.isSuccessful) {
                    if (response.code in GEMINI_RETRYABLE_HTTP && transientAttempt < GEMINI_MAX_TRANSIENT_RETRIES) {
                        Log.w(
                            HTTP_TAG,
                            "Gemini POST end lines=${lines.size} key=…$keySuffix " +
                                "httpCode=${response.code} latencyMs=$latencyMs " +
                                "throttleWaitMs=$throttleWaitMs success=false retry=transient"
                        )
                        Log.w(TAG, "Gemini HTTP ${response.code} (transient) — retry ${transientAttempt + 1}/$GEMINI_MAX_TRANSIENT_RETRIES")
                        geminiBackoff(transientAttempt)
                        return@withContext translateGemini(lines, targetLanguage, apiKey, attempt, transientAttempt + 1, context, callTimeoutMs)
                    }
                    val errorMsg = if (response.code == 429) "RATE_LIMITED" else "HTTP ${response.code}: $responseBody"
                    Log.w(
                        HTTP_TAG,
                        "Gemini POST end lines=${lines.size} key=…$keySuffix " +
                            "httpCode=${response.code} latencyMs=$latencyMs " +
                            "throttleWaitMs=$throttleWaitMs success=false error=$errorMsg" +
                            if (response.code == 429) " quota=${geminiQuotaDetail(responseBody)}" else ""
                    )
                    return@withContext TranslationResult(
                        lines, false, errorMsg,
                        retryAfterMs = if (response.code == 429) geminiRetryDelayMs(responseBody) else null
                    )
                }

                val json = JSONObject(responseBody)
                val candidates = json.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    // HTTP 200 without candidates = prompt-level block (safety/recitation).
                    val blockReason = json.optJSONObject("promptFeedback")
                        ?.optString("blockReason").orEmpty()
                    Log.e(TAG, "Gemini missing candidates (block=$blockReason): ${responseBody.take(400)}")
                    Log.w(
                        HTTP_TAG,
                        "Gemini POST end lines=${lines.size} key=…$keySuffix " +
                            "httpCode=200 latencyMs=$latencyMs throttleWaitMs=$throttleWaitMs " +
                            "success=false error=no_candidates block=$blockReason"
                    )
                    return@withContext TranslationResult(
                        lines, false,
                        if (blockReason.isNotBlank()) TRANSLATION_ERROR_CONTENT_BLOCKED else "No candidates in response"
                    )
                }
                val candidate = candidates.getJSONObject(0)
                // Gemini 3.x can emit thought/thoughtSignature parts before the text — take
                // every non-thought text part, not blindly parts[0].
                val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
                val rawText = buildString {
                    for (i in 0 until (parts?.length() ?: 0)) {
                        val part = parts!!.getJSONObject(i)
                        if (part.optBoolean("thought", false)) continue
                        append(part.optString("text"))
                    }
                }.trim()
                val finishReason = candidate.optString("finishReason")
                if (rawText.isBlank()) {
                    Log.e(TAG, "Gemini candidate has no text (finish=$finishReason): ${responseBody.take(400)}")
                    Log.w(
                        HTTP_TAG,
                        "Gemini POST end lines=${lines.size} key=…$keySuffix " +
                            "httpCode=200 latencyMs=$latencyMs throttleWaitMs=$throttleWaitMs " +
                            "success=false error=empty_candidate finish=$finishReason"
                    )
                    return@withContext TranslationResult(
                        lines, false,
                        if (finishReason in BLOCKED_FINISH_REASONS) TRANSLATION_ERROR_CONTENT_BLOCKED
                        else "Empty candidate (finish=$finishReason)"
                    )
                }

                val parsed = parseTranslationResult(lines, targetLanguage, rawText, NL)
                if (!parsed.success && finishReason in BLOCKED_FINISH_REASONS) {
                    // The policy filter can also cut generation mid-array: partial text +
                    // blocked finishReason parses as garbage. Same treatment — bisect upstream.
                    Log.e(TAG, "Gemini output truncated by policy (finish=$finishReason)")
                    Log.w(
                        HTTP_TAG,
                        "Gemini POST end lines=${lines.size} key=…$keySuffix " +
                            "httpCode=200 latencyMs=$latencyMs throttleWaitMs=$throttleWaitMs " +
                            "success=false error=content_blocked finish=$finishReason"
                    )
                    return@withContext TranslationResult(lines, false, TRANSLATION_ERROR_CONTENT_BLOCKED)
                }
                // Urgent (on-screen) calls skip the re-roll: the router moves to the next key at
                // once instead of waiting out this key's min interval.
                if (!parsed.success && attempt == 0 && callTimeoutMs == null) {
                    // Intermittent JSON-mode malformation (e.g. tail truncation with STOP) —
                    // one re-roll usually returns a clean array.
                    Log.w(TAG, "Parse failed (${parsed.errorMessage}, finish=$finishReason) — retrying batch once")
                    Log.w(
                        HTTP_TAG,
                        "Gemini POST end lines=${lines.size} key=…$keySuffix " +
                            "httpCode=200 latencyMs=$latencyMs throttleWaitMs=$throttleWaitMs " +
                            "success=false retry=parse"
                    )
                    return@withContext translateGemini(lines, targetLanguage, apiKey, attempt = 1, context = context, callTimeoutMs = callTimeoutMs)
                }
                if (!parsed.success) {
                    Log.e(TAG, "Parse failed after retry (finish=$finishReason) body=${responseBody.take(400)}")
                    Log.w(
                        HTTP_TAG,
                        "Gemini POST end lines=${lines.size} key=…$keySuffix " +
                            "httpCode=200 latencyMs=$latencyMs throttleWaitMs=$throttleWaitMs " +
                            "success=false error=${parsed.errorMessage}"
                    )
                } else {
                    Log.i(
                        HTTP_TAG,
                        "Gemini POST end lines=${lines.size} key=…$keySuffix " +
                            "httpCode=200 latencyMs=$latencyMs throttleWaitMs=$throttleWaitMs success=true"
                    )
                }
                parsed
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e

                val latencyMs = System.currentTimeMillis() - httpStartedAt
                if (callTimeoutMs != null && e is java.io.InterruptedIOException) {
                    return@withContext TranslationResult(lines, false, TRANSLATION_ERROR_TIMEOUT)
                }
                if (e is java.io.IOException && transientAttempt < GEMINI_MAX_TRANSIENT_RETRIES) {
                    Log.w(
                        HTTP_TAG,
                        "Gemini POST end lines=${lines.size} key=…$keySuffix " +
                            "httpCode=null latencyMs=$latencyMs throttleWaitMs=$throttleWaitMs " +
                            "success=false retry=network error=${e.message}"
                    )
                    Log.w(TAG, "translateGemini network error (${e.message}) — retry ${transientAttempt + 1}/$GEMINI_MAX_TRANSIENT_RETRIES")
                    geminiBackoff(transientAttempt)
                    return@withContext translateGemini(lines, targetLanguage, apiKey, attempt, transientAttempt + 1, context, callTimeoutMs)
                }
                Log.e(TAG, "translateGemini exception: ${e.message}", e)
                Log.w(
                    HTTP_TAG,
                    "Gemini POST end lines=${lines.size} key=…$keySuffix " +
                        "httpCode=null latencyMs=$latencyMs throttleWaitMs=$throttleWaitMs " +
                        "success=false error=${e.message}"
                )
                TranslationResult(lines, false, e.message)
            }
        }
    }


    private fun extractClaudeText(responseBody: String): String {
        val json = JSONObject(responseBody)
        val content = json.optJSONArray("content") ?: return ""
        return buildString {
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                if (part.optString("type") == "text") {
                    append(part.optString("text"))
                }
            }
        }.trim()
    }

    private suspend fun matchClaude(prompt: String, payload: String, apiKey: String): String? {
        val body = JSONObject().apply {
            put("model", CLAUDE_MODEL_ID)
            put("max_tokens", 2048)
            put("temperature", 0)
            put("system", prompt)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", payload)
                })
            })
        }
        val request = Request.Builder()
            .url(aiProviderUrl(CLAUDE_URL))
            .header("x-api-key", apiKey)
            .header("anthropic-version", CLAUDE_API_VERSION)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    if (!response.isSuccessful || responseBody == null) {
                        Log.w(TAG, "matchSubtitleLines claude HTTP ${response.code}")
                        return@use null
                    }
                    extractClaudeText(responseBody).ifBlank { null }
                }
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                Log.w(TAG, "matchSubtitleLines claude failed: ${it.message}")
            }.getOrNull()
        }
    }

    private suspend fun translateClaude(
        lines: List<String>,
        targetLanguage: String,
        apiKey: String,
        attempt: Int = 0,
        context: List<String> = emptyList(),
        callTimeoutMs: Long? = null
    ): TranslationResult {
        val NL = "⏎"
        val inputArray = encodeIndexed(lines, NL)
        val systemPrompt = buildSystemPrompt(targetLanguage, NL)

        val body = JSONObject().apply {
            put("model", CLAUDE_MODEL_ID)
            // Big (bulk) batches need more room than the 4096 default or the array is cut short.
            put("max_tokens", (lines.size * 40 + 512).coerceIn(CLAUDE_MAX_TOKENS, CLAUDE_MAX_TOKENS_BULK))
            put("temperature", 0.1)
            put("system", systemPrompt)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", buildTranslateUserMessage(inputArray.toString(), targetLanguage, context))
                })
            })
        }

        val request = Request.Builder()
            .url(aiProviderUrl(CLAUDE_URL))
            .header("x-api-key", apiKey)
            .header("anthropic-version", CLAUDE_API_VERSION)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return withContext(Dispatchers.IO) {
            try {
                val response = executeCall(request, callTimeoutMs)
                val responseBody = response.body?.string() ?: run {
                    Log.e(TAG, "Empty Claude response body (HTTP ${response.code})")
                    return@withContext TranslationResult(lines, false, "Empty response (${response.code})")
                }
                if (!response.isSuccessful) {
                    val errorMsg = if (response.code == 429) "RATE_LIMITED" else "HTTP ${response.code}: $responseBody"
                    return@withContext TranslationResult(lines, false, errorMsg)
                }

                val rawText = extractClaudeText(responseBody)
                if (rawText.isBlank()) {
                    Log.e(TAG, "Claude response has no text: ${responseBody.take(400)}")
                    return@withContext TranslationResult(lines, false, "Empty Claude content")
                }

                val parsed = parseTranslationResult(lines, targetLanguage, rawText, NL)
                if (!parsed.success && attempt == 0 && callTimeoutMs == null) {
                    Log.w(TAG, "Claude parse failed (${parsed.errorMessage}) — retrying batch once")
                    return@withContext translateClaude(lines, targetLanguage, apiKey, attempt = 1, context = context, callTimeoutMs = callTimeoutMs)
                }
                parsed
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (callTimeoutMs != null && e is java.io.InterruptedIOException) {
                    return@withContext TranslationResult(lines, false, TRANSLATION_ERROR_TIMEOUT)
                }
                Log.e(TAG, "translateClaude exception: ${e.message}", e)
                TranslationResult(lines, false, e.message)
            }
        }
    }


    private suspend fun pingGroq(apiKey: String): ProviderPingHttpResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", GROQ_MODEL_ID)
            put("temperature", 0)
            put("max_tokens", 8)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "Reply with OK")
                })
            })
        }
        val request = Request.Builder()
            .url(aiProviderUrl(GROQ_URL))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                val quota = parseProviderQuota(
                    SubtitleAiModel.GROQ_LLAMA_70B, apiKey, response.headers, response.code
                )
                if (response.isSuccessful) {
                    ProviderPingHttpResult(true, "Groq key OK", response.code, quota)
                } else {
                    val bodyText = response.body?.string().orEmpty()
                    val msg = if (response.code == 429) "RATE_LIMITED" else "HTTP ${response.code}"
                    ProviderPingHttpResult(false, msg, response.code, quota)
                }
            }
        }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            ProviderPingHttpResult(false, it.message ?: "Ping failed")
        }
    }

    private suspend fun pingGemini(apiKey: String): ProviderPingHttpResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", "Reply with OK") })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0)
                put("maxOutputTokens", 8)
                put("thinkingConfig", JSONObject().apply { put("thinkingLevel", "minimal") })
            })
        }
        val request = Request.Builder()
            .url(aiProviderUrl(GEMINI_BASE_URL))
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                val quota = parseProviderQuota(
                    SubtitleAiModel.GEMINI_FLASH_25, apiKey, response.headers, response.code
                )
                val bodyText = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    ProviderPingHttpResult(true, "Gemini key OK", response.code, quota)
                } else {
                    val msg = if (response.code == 429) "RATE_LIMITED" else "HTTP ${response.code}"
                    ProviderPingHttpResult(false, msg, response.code, quota)
                }
            }
        }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            ProviderPingHttpResult(false, it.message ?: "Ping failed")
        }
    }

    private suspend fun pingClaude(apiKey: String): ProviderPingHttpResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", CLAUDE_MODEL_ID)
            put("max_tokens", 8)
            put("temperature", 0)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "Reply with OK")
                })
            })
        }
        val request = Request.Builder()
            .url(aiProviderUrl(CLAUDE_URL))
            .header("x-api-key", apiKey)
            .header("anthropic-version", CLAUDE_API_VERSION)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                val quota = parseProviderQuota(
                    SubtitleAiModel.CLAUDE_HAIKU, apiKey, response.headers, response.code
                )
                if (response.isSuccessful) {
                    ProviderPingHttpResult(true, "Claude key OK", response.code, quota)
                } else {
                    val msg = if (response.code == 429) "RATE_LIMITED" else "HTTP ${response.code}"
                    ProviderPingHttpResult(false, msg, response.code, quota)
                }
            }
        }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            ProviderPingHttpResult(false, it.message ?: "Ping failed")
        }
    }

    private fun parseTranslationResult(
        lines: List<String>,
        targetLanguage: String,
        rawText: String,
        NL: String
    ): TranslationResult {
        val parsed = SubtitleAiResponseJsonParser.parseTranslationResult(
            lines = lines,
            targetLanguage = targetLanguage,
            rawText = rawText,
            NL = NL
        )
        if (!parsed.success) {
            // The only failure path that carries no HTTP/finishReason context — always log the
            // offending text or the toast is undiagnosable.
            Log.e(TAG, "No JSON array in model output (sent ${lines.size} lines): ${rawText.take(300)}")
        } else {
            if (parsed.rejected.isNotEmpty()) {
                Log.w(TAG, "Partial alignment: rejected ${parsed.rejected.size}/${lines.size}")
            }
        }
        return parsed
    }
}

/**
 * Which Gemini quota a 429 hit ("quotaId=value retry=Ns"), from the QuotaFailure/RetryInfo details.
 * Tells a per-minute token cap (big batches only) from a request or daily cap. Never echoes the key.
 */
internal fun geminiQuotaDetail(body: String?): String {
    if (body.isNullOrBlank()) return "none"
    return try {
        val details = JSONObject(body).optJSONObject("error")?.optJSONArray("details") ?: return "none"
        val parts = ArrayList<String>()
        for (i in 0 until details.length()) {
            val d = details.optJSONObject(i) ?: continue
            val violations = d.optJSONArray("violations")
            if (violations != null) {
                for (j in 0 until violations.length()) {
                    val v = violations.optJSONObject(j) ?: continue
                    parts += "${v.optString("quotaId")}=${v.optString("quotaValue")}"
                }
            }
            d.optString("retryDelay").takeIf { it.isNotEmpty() }?.let { parts += "retry=$it" }
        }
        parts.joinToString(",").ifEmpty { "none" }
    } catch (_: Exception) {
        "unparsed"
    }
}

/** Gemini 429 RetryInfo `retryDelay` ("37s", "0.5s") in ms, or null when absent. */
internal fun geminiRetryDelayMs(body: String?): Long? {
    if (body.isNullOrBlank()) return null
    return try {
        val details = JSONObject(body).optJSONObject("error")?.optJSONArray("details") ?: return null
        (0 until details.length()).asSequence()
            .mapNotNull { details.optJSONObject(it)?.optString("retryDelay")?.takeIf { d -> d.isNotEmpty() } }
            .firstOrNull()
            ?.removeSuffix("s")?.toDoubleOrNull()
            ?.let { (it * 1000).toLong() }
            ?.takeIf { it > 0L }
    } catch (_: Exception) {
        null
    }
}

/** Builds the user message; the `Translate to <lang>:` block is identical with or without context. */
internal fun buildTranslateUserMessage(
    inputArray: String,
    targetLanguage: String,
    context: List<String>
): String {
    val translateBlock = "Translate to $targetLanguage:\n$inputArray"
    if (context.isEmpty()) return translateBlock
    val contextJson = JSONArray(context).toString()
    return "Context (previous lines, do NOT translate or output):\n$contextJson\n\n$translateBlock"
}
