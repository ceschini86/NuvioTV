# Handoff — Overlay Info-rail A–D **fechado** (2026-09-24)

> Colar no chat novo: `@docs/README.md` + `@docs/handoff/2026-09-24-overlay-info-rail-close.md` (este ficheiro).  
> Spec (matrizes): [`2026-09-24-overlay-info-rail.md`](./2026-09-24-overlay-info-rail.md).  
> Canónicos: [`prd-ai-subtitles-ui-menu.md`](../prd-ai-subtitles-ui-menu.md) · skill `.cursor/skills/ai-subtitles-smoke`.  
> Regra releases: `.cursor/rules/release-notes-user-facing.mdc` (notas só linguagem de utilizador).

| Campo | Valor |
|-------|--------|
| Branch | `feat/subtitle-overlay-info-rail` (pushed; tracking `origin`) |
| HEAD | (após 1.0.8) — Bugs Info 1–3 (erros amigáveis, restore fonte no click IA, Info addon sem Degrau AI) |
| Release | **1.0.8** · package `com.nuvio.tv` · versionCode **1072** |
| Package / TV | `com.nuvio.tv` · **1.0.7** · versionCode **1071** · universal da release instalada (`192.168.31.46:5555`) |
| Login / auth | Sem mudanças 1.0.6→1.0.7; deve comportar-se como 1.0.6 neste package |

---

## Fechado (A→D + hotfix)

| Fatia / item | Commit | Conteúdo |
|--------------|--------|----------|
| **A** | `f1385ce91` | Info-only Col3; sem Style/long-press/Disable-Stop; N*; L* |
| **B** | `50dc371b8` | `SubtitleInfoRailDecision` + testes ID-named |
| **C** | `c9611a57c` | Translate / Fonte IA / Reset Smart / click AI / logs / docs / skill |
| **D** | `2b0085ba8` + bump `3992cc496` | F2 embedded; S1–S9 FocusBackground; R4 diagnostics handlers |
| **Hotfix foco** | `ba8bcca70` | Pós-CTA: settle real `FocusRequester` Col2 (`force`, `post_cta_focus_complete`, `optionFocusGeneration`) |
| **Docs close** | `15fdf864c` | Handoff SHAs / smoke table |

### Fatia D (resumo técnico)

- `AiSubtitleDiagnostics.sourceInternalIndex` + resolver embedded (`resolveEmbeddedAiSourceOptionId`)
- `OverlayRailVisualRole` / FocusBackground vs Secondary
- Removidos handlers diagnostics overlay (R4)

### Smoke Fatia D (TV) — 2026-09-24

Título: TWD S08E06 · preferred **pt-BR**. Evidência local `.tmp_overlay_*.png` + `.tmp_overlay_smoke.logcat` (não versionar).

| ID | Resultado | Nota |
|----|-----------|------|
| F2-e | PASS | Fonte IA em Embutida EN (não FR; mesmo path) |
| F2-a | PASS | Fonte IA em AIOStreams MANUAL |
| T-focus / S-focus | PASS parcial → **hotfix** | Smoke inicial confiava em `post_cta_focus` / `already_focused` (falso positivo). Bug visual órfão confirmado; fix em `ba8bcca70` instalado na TV. Re-smoke visual T/S recomendado se reabrir overlay bugs. |
| S1–S9 | PASS | FocusBackground vs Secondary |

---

## Pendente (próximo chat)

1. **Bugs UX overlay** (repro na TV 1.0.7 — ver prompt abaixo).
2. Residual **TranslateMenu** (`showSubtitleTranslateMenuOverlay` / events) sem UI long-press — só se bloquear algo.
3. Commit da rule `release-notes-user-facing.mdc` se ainda untracked.
4. Merge branch → `dev` / PR quando o user pedir (não forçar).

### Non-goals

- Não reabrir ladder Smart / providers / rate-limit product plan.
- Não reintroduzir Style tabs / long-press Translate.
- Notas de release: regra user-facing (sem Fatia/IDs).

---

## Prompt — próximo chat (copiar)

```text
@docs/README.md @docs/handoff/2026-09-24-overlay-info-rail-close.md @docs/prd-ai-subtitles-ui-menu.md

# Bugs overlay legendas (pós 1.0.7)

## Contexto
Branch `feat/subtitle-overlay-info-rail` @ `ba8bcca70`. Release **1.0.7** publicada e universal na TV `192.168.31.46:5555` (`com.nuvio.tv` / 1071). Fatias A–D fechadas. Hotfix foco pós-CTA já no release.

Regra: `.cursor/rules/release-notes-user-facing.mdc` — se houver release novo, notas só para o utilizador.

## Bug 1 — Erro de tradução no Info como JSON cru
- **Onde:** Col3 Info, opção **IA** (também vaza no Info de addon — Bug 3).
- **Sintoma:** Corpo HTTP bruto, ex. `HTTP 400: {"type":"error",… Anthropic credit balance…}`.
- **Esperado:** Mensagem curta i18n (estilo rate-limit / API key missing), sem JSON/`request_id`.
- **Âncora:** mapeamento de `aiSubtitleLastError` em `SubtitleSelectionOverlay.kt` (~só RATE_LIMITED/429/API key hoje). Pode haver inconsistência modelo vs provider no Info — DoD deste bug é **UX do texto**.

## Bug 2 — Click na opção IA usa addon acabado de selecionar como fonte
- **Repro:** (1) AI ativa com fonte prévia (ex. `AI_EMBEDDED` ou MANUAL antiga). (2) Col2: selecionar **addon** (playback → addon). (3) Sem CTA Translate, clicar opção **IA**.
- **Atual:** MANUAL / Fonte IA aponta para o addon.
- **Esperado (A2/A3):** reativar IA por click na opção mantém a **fonte que já estava** na tradução; CTA «Traduzir com IA» é o caminho para traduzir *esta* opção.

## Bug 3 — Diagnostics / erro da IA no Info de opção não-IA
- Info de addon (ou outra opção focada) mostra degrau/motivo/erro da tradução AI em vez de (só) infos da opção focada (I4 / campos por tipo).

## DoD
- Patch mínimo por bug; evidência screenshot/log na TV.
- Sem mudar ladder C1–C6 product; sem reabrir Style/long-press.
- Não commit/release sem eu pedir. Notas de release = user-facing se houver bump.
```
