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

1. **WIP actual (OSD + overlay + i18n + Back):** [`2026-09-24-player-osd-overlay-wip.md`](./2026-09-24-player-osd-overlay-wip.md) — branch `release/1.0.9`, sideload 1074.
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
@docs/README.md @docs/handoff/2026-09-24-player-osd-overlay-wip.md

# Usar o handoff WIP (OSD + overlay) — este close file é histórico 1.0.7/1.0.8.
```

### Histórico — Bugs 1–3 (fechados em 1.0.8)

Já resolvidos na release 1.0.8 (`287b6b36a`): erros Info amigáveis, restore de fonte no click IA, Info addon sem Degrau AI. Manter abaixo só como referência.

<details>
<summary>Texto original dos bugs (arquivo)</summary>

- Bug 1: JSON cru no Info → i18n curta
- Bug 2: click IA usava addon acabado de selecionar → manter fonte prévia
- Bug 3: diagnostics AI no Info de opção não-IA

</details>
