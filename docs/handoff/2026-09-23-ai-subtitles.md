# Handoff — AI subtitles (2026-09-23)

> Colar no chat novo: `@docs/README.md` + `@docs/handoff/2026-09-23-ai-subtitles.md` (este ficheiro), depois **só** o doc da tarefa.  
> Transcript anterior: `cba76967-b52b-402e-9d57-bdbc2ab54542` (Cursor agent-transcripts).

| Campo | Valor |
|-------|--------|
| Branch | `fix/ai-ladder-and-rate-limit` (base `release/1.0.2`) |
| Package debug | `com.nuviodebug.com` |
| Emulador | `emulator-5554` — APK full x86_64 instalado e app aberto nesta sessão |
| Docs canónicos | `prd-ai-subtitles.md` · `architecture-ai-subtitles.md` §3 · `prd-ai-subtitles-ui-menu.md` |
| Âncora code | `PlayerRuntimeControllerAiSubtitles.kt` |

---

## Decisão de produto (travada nesta sessão)

**Smart AI = só embutidas.** Addons **não** entram na ladder automática.

```text
Smart AI ON
 ├─ 1. Preferred embedded → AI off
 ├─ 2. Embedded traduzível → AI on
 └─ else classic auto-select → AI off
```

- **Translate with AI** (long-press / Info) = único path para traduzir **addon** (rung `MANUAL` + lock).
- Score `%` = badge/info no overlay; **não** gate da policy (`AI_ADDON_SOURCE_MIN_SCORE` legado / unused pela ladder).

Implementado + docs/strings atualizados; **ainda uncommitted** no working tree.
Smoke MANUAL + embedded paths OK; fix de foco Info/Translate também uncommitted.

---

## O que já está no tree (uncommitted / parcial)

### AI — Smart embedded-only (desta fatia)

- `applyAiAutoSelectLadder`: sem rungs addon / weak pivot / defer por `isLoadingAddonSubtitles`.
- `selectAiTranslationSourceIfAvailable`: mantém addon **só** se `aiSubtitleUserLocked`; senão só embedded.
- Helpers `findBestScoredAddonAiSource` / preferred scored **removidos**.
- Strings settings: `sub_ai_auto_select_desc`, `sub_ai_section_desc` (en / pt-BR / pt-PT).
- Docs: PRD M9/M10/W6, UI menu §3.1.1, architecture §3 + ADR-AI-3/5/8, README estado.

### AI — fixes anteriores na mesma branch (ainda no diff)

- `resetSubtitleAiPolicyForNewMedia()` no switch de stream in-player (`releasePlayer(flush=false)`).
- Keep-disabled: só preferência persistida Off (não confundir seleção zerada).
- S6 rate-limit: AI off + **preserve** seleção; `aiSubtitleQuotaExhausted` oculta Translate.
- Tests: `SubtitleAiRateLimitFallbackTest`, `SubtitleAiRouterFallbackTest` (untracked).

### Fora do pacote AI (mesmo working tree)

- **Series Graph** para ratings de episódio IMDb (`SeriesGraphApi`, `ImdbEpisodeRatingsRepository`, UI label) — TMDB key local; proxies IMDb privados removidos do path de episódios.
- Outros ficheiros detail/player/settings no `git status` — misturar com AI no mesmo commit **só** se o utilizador pedir explicitamente.

---

## Smoke (emulador)

**Checklist canónico + last results:** [`../smoke-ai-subtitles.md`](../smoke-ai-subtitles.md)  
**Skill Agent:** `.cursor/skills/ai-subtitles-smoke` (`@` / Agent Skills)

Resumo 2026-09-23: #1 PASS · #2 PASS · #3 PARTIAL (pref bloqueou `CLASSIC_FALLBACK`) · #4 PASS (MANUAL após fix de foco Info).

### Bug encontrado + fix (uncommitted)

`SubtitleSelectionOverlay`: Options → Info chamava `styleListState.scrollItemIntoView` na aba Info (sem LazyColumn) e **suspendia** — `style_focus_schedule` sem `style_focus_request`. Fix: só scrollar se `StyleFocusKey.isStyleContentKey`.

### Ainda pendente (além do smoke doc)

- Log limpo `CLASSIC_FALLBACK` sem preferência persistida.
- Switch de stream in-player.
- Settings copy embedded-only — visual só.

---

## Gaps / não fazer agora

| Item | Nota |
|------|------|
| Bugs UI menu anteriores (Info vazio, texto PL, highlight ao reabrir) | Plano antigo **adiado**; não misturar sem pedido |
| Remover enum/strings `AI_SCORED_ADDON` / `PREFERRED_SCORED_ADDON` | Legado OK; ladder não publica |
| Remover `SubtitleReleaseScoring` / badges | Fora de escopo |
| “Reset to smart” no Info | Gap UX documentado no UI menu |
| Merge `dev` / commit / PR | Só se o utilizador pedir |
| README root do fork (Claude/multi-key) | PRD S1 ainda gap vs README app |

---

## Como validar no device

```bash
./gradlew :app:assembleFullDebug
adb install -r app/build/outputs/apk/full/debug/app-full-x86_64-debug.apk
adb shell am start -n com.nuviodebug.com/com.nuvio.tv.launcher.AppIconDefault
```

Logcat útil: tag do player / `AI ladder:` / `AI source:`.

---

## Prompt sugerido (próximo chat)

Ver secção abaixo no reply do Agent, ou copiar:

```text
@docs/README.md @docs/smoke-ai-subtitles.md
(skill: ai-subtitles-smoke)

Continuar AI subtitles na branch fix/ai-ladder-and-rate-limit.
Smart AI já é embedded-only. Não reeditar o plano Embedded-only.

Prioridade: smoke checklist em docs/smoke-ai-subtitles.md (#3 classic limpo; revalidar #4 se necessário).
Se falhar, usar @ai-subtitles-bug + diagnostics (rung/reason/source/locked).
Não commit/PR sem eu pedir. Não misturar Series Graph no mesmo commit AI.
```
