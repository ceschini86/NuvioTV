# Handoff — Overlay Info-rail A–D **fechado** (2026-09-24)

> Colar no chat novo: `@docs/README.md` + `@docs/handoff/2026-09-24-overlay-info-rail-close.md` (este ficheiro).  
> Spec (matrizes): [`2026-09-24-overlay-info-rail.md`](./2026-09-24-overlay-info-rail.md).  
> Canónicos: [`prd-ai-subtitles-ui-menu.md`](../prd-ai-subtitles-ui-menu.md) · skill `.cursor/skills/ai-subtitles-smoke`.  
> Regra releases: `.cursor/rules/release-notes-user-facing.mdc` (notas só linguagem de utilizador).

| Campo | Valor |
|-------|--------|
| Branch | `feat/subtitle-overlay-info-rail` (pushed; tracking `origin`) |
| HEAD | `287b6b36a` — Bugs Info 1–3 (erros amigáveis, restore fonte no click IA, Info addon sem Degrau AI) |
| Release | **[1.0.8](https://github.com/ceschini86/NuvioTV/releases/tag/1.0.8)** · target `287b6b36a` · notas user-facing |
| Package / TV | `com.nuvio.tv` · **1.0.8** · versionCode **1072** |
| Login / auth | Sem mudanças neste package face a 1.0.7 |

---

## Fechado (A→D + hotfix + Info bugs)

| Fatia / item | Commit | Conteúdo |
|--------------|--------|----------|
| **A** | `f1385ce91` | Info-only Col3; sem Style/long-press/Disable-Stop; N*; L* |
| **B** | `50dc371b8` | `SubtitleInfoRailDecision` + testes ID-named |
| **C** | `c9611a57c` | Translate / Fonte IA / Reset Smart / click AI / logs / docs / skill |
| **D** | `2b0085ba8` + bump `3992cc496` | F2 embedded; S1–S9 FocusBackground; R4 diagnostics handlers |
| **Hotfix foco** | `ba8bcca70` | Pós-CTA: settle real `FocusRequester` Col2 (`force`, `post_cta_focus_complete`, `optionFocusGeneration`) |
| **Docs close** | `15fdf864c` | Handoff SHAs / smoke table |
| **Bugs Info 1–3** | `287b6b36a` + **1.0.8** | Erros i18n; restore fonte no click IA; Info addon sem Degrau/erro AI |

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

1. Instalar **1.0.8** na TV e validar Bugs 1–3 no device (unit tests já PASS).
2. Residual **TranslateMenu** (`showSubtitleTranslateMenuOverlay` / events) sem UI long-press — só se bloquear algo.
3. Merge branch → `dev` / PR quando o user pedir (não forçar).

### Non-goals

- Não reabrir ladder Smart / providers / rate-limit product plan.
- Não reintroduzir Style tabs / long-press Translate.
- Notas de release: regra user-facing (sem Fatia/IDs).

---

## Prompt — próximo chat (copiar)

```text
@docs/README.md @docs/handoff/2026-09-24-overlay-info-rail-close.md

# Validar 1.0.8 na TV (Bugs Info)

## Contexto
Branch `feat/subtitle-overlay-info-rail` @ `287b6b36a`. Release **1.0.8** publicada.
Instalar universal: https://github.com/ceschini86/NuvioTV/releases/tag/1.0.8
TV `192.168.31.46:5555` · `com.nuvio.tv` / 1072.

## Checklist
1. Erro de tradução no Info da opção **IA** — mensagem curta (créditos / falha), sem JSON.
2. AI ativa → selecionar addon → click **IA** (sem Traduzir) → Fonte IA volta à fonte prévia, não ao addon.
3. Focar addon com diagnostics AI no runtime → Info do addon **sem** Degrau/Motivo/erro AI.
4. CTA «Traduzir com IA» num addon ainda usa **esse** addon como fonte.

## Non-goals
Sem reabrir ladder / Style / long-press. Não commit/release sem eu pedir.
```
