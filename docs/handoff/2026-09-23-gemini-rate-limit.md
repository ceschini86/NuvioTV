# Handoff — Gemini rate-limit / subtitle wait UX (2026-09-23)

> Transcript investigação: `b5a5cd2d-9dbb-4bb8-b80f-957ae3771acc`.  
> Plano Cursor: `gemini_rate-limit_batching_9b6d085f.plan.md` (também em `.cursor/plans/`).

| Campo | Valor |
|-------|--------|
| Relacionado | [`2026-09-23-ai-subtitles.md`](2026-09-23-ai-subtitles.md) (ladder Smart embedded-only — **não** reabrir) |
| Branch | `fix/ai-ladder-and-rate-limit` |
| TV ADB | `192.168.31.46:5555` (Streamer; ABI **armeabi-v7a**) |
| Package / build | `com.nuvio.tv` **versionCode 1068** (`app-full-armeabi-v7a-release.apk`) |
| Caso | Amélie `tt0211915` — AI_EMBEDDED FR→pt-br |

---

## Problema (antes)

- Ladder OK; ~4 min player; legenda útil **&lt;~1 min**.
- Causas: blank no cache miss; RPM key `…fm9Q` (fallback `…3zFU` ok); prefetch `translateBatch` paralelo à fila live.
- Cota Gemini **por projeto** AI Studio, não por key. ARVIO REST = 150 ms/40 (não copiar como fix).

---

## Implementação (feita)

1. **`TranslatingTextOutput`**: cache miss → `kind=waiting` com texto fonte; troca quando traduz; sidecar alinhado; `SUMMARY` em `player_reset` / `ai_disabled`.
2. **`preTranslateWindow`**: enfileira via `translate(..., source=prefetch)` (mesma fila); sem `translateBatch` paralelo.
3. **`BATCH_WINDOW_MS`**: Gemini **2000 ms**, Groq/Claude **150 ms**; cap 40.
4. **Logs**: `SubtitleCueDisplay`, `SubtitleTranslation` (`source`/`batchSize`/`windowMs`), `SubtitleAiRouter` (+ `projectHint`), `SubtitleAiHttp` (`throttleWaitMs`/`httpCode`/`latencyMs`).
5. **Nota multi-key**: log + ADR-AI-6 — keys só ajudam com **projetos AI Studio distintos**.

### Ficheiros

- `…/subtitles/TranslatingTextOutput.kt`
- `…/subtitles/SubtitleTranslationManager.kt`
- `…/subtitles/SubtitleTranslationService.kt`
- `…/subtitles/SubtitleAiRouter.kt`
- `PlayerSidecarSubtitles.kt` (wait UX)
- `docs/architecture-ai-subtitles.md` (wait / batch / ADR-AI-6)
- `app/build.gradle.kts` — `versionCode` **1068** (device tinha 1067; downgrade bloqueado)

---

## Smoke TV Amélie (2026-09-23 ~22:42–22:48)

Filtro:

```bash
adb -s 192.168.31.46:5555 logcat -v time \
  SubtitleCueDisplay:I SubtitleAiRouter:I SubtitleTranslation:I SubtitleAiHttp:I PlayerViewModel:I '*:S'
```

| Sinal | Resultado |
|-------|-----------|
| Ladder | `AI ladder: AI translation source index=0 lang=fr` |
| Sessão | ~**5,7 min** (22:42:23 → 22:48:04 `player_reset`) |
| Wait UX | `START kind=waiting` com FR (5×); **não** blank no miss |
| SUMMARY | `translatedMs=181195` (~3,0 min) · `originalMs=10959` (~11 s wait/fonte) · `blankMs=143718` · cues 56 / 5 |
| Blank | só `reason=empty_source` (gaps entre cues) — **não** `waiting_ai` |
| Batch | `windowMs=2000`; sizes hist `{1:21, 2:12, 3:3, 5:1}` avg **1,59** max **5**; sources prefetch + 1 mixed |
| HTTP | 37 OK / 8×429 (`…fm9Q`); fallback `…3zFU` ok; `batch_fail=0` |
| Multi-key log | `Gemini multi-key (2 keys): projectHint=…` |

**Veredito:** UX no wait OK; prefetch na fila OK; janela 2 s + batches &gt;1 quando há burst; legenda traduzida ~3 min wall-clock vs &lt;1 min na sessão baseline. `blankMs` alto = silêncios/`empty_source`, não wait blank. RPM na 1ª key continua (cota por projeto) — fallback cobre.

---

## Status

| Item | Estado |
|------|--------|
| Diagnóstico TV Amélie | Feito |
| Plano + logs + doc Google | Feito |
| Implementação código | **Feito** |
| Build/install TV + smoke ~5 min | **Feito** (v1068) |
| Commit / PR | **Não** — só a pedido |

---

## Prompt de recuperação (se reabrir)

```text
Handoff Gemini rate-limit — implementação + smoke Amélie feitos (v1068).
Ver last results em @docs/handoff/2026-09-23-gemini-rate-limit.md
Sem reabrir ladder Smart embedded-only. Commit/PR só se pedido.
```
