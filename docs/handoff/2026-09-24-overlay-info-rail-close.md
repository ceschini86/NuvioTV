# Handoff — Overlay Info-rail Fatias A–D (2026-09-24)

> Colar no chat novo: `@docs/README.md` + `@docs/handoff/2026-09-24-overlay-info-rail-close.md` (este ficheiro).  
> Spec de execução (matrizes): [`2026-09-24-overlay-info-rail.md`](./2026-09-24-overlay-info-rail.md).  
> Docs canónicos: [`prd-ai-subtitles-ui-menu.md`](../prd-ai-subtitles-ui-menu.md) · skill `.cursor/skills/ai-subtitles-smoke`.

| Campo | Valor |
|-------|--------|
| Branch | `feat/subtitle-overlay-info-rail` (pushed; tracking `origin`) |
| Smoke Fatia D (TV) | **PASS** 2026-09-24 — `192.168.31.46:5555` · TWD S08E06 · preferred **pt-BR** · evidência `.tmp_overlay_*.png` + `.tmp_overlay_smoke.logcat` |
| Package / TV | `com.nuvio.tv` · **1.0.7** · versionCode **1071** · sideload `armeabi-v7a` |
| GitHub releases | **1.0.6** publicado; **1.0.7** aguarda aprovação explícita do user |

---

## O que já está feito (A→D)

| Fatia | Commit / estado | Conteúdo |
|-------|-----------------|----------|
| **A** | `f1385ce91` | Info-only Col3; sem Style/long-press/Disable-Stop; navegação N*; layout L*; (V1–V3 **depois substituídos**) |
| **B** | `50dc371b8` | `SubtitleInfoRailDecision` + 14 testes ID-named (I*/C*/K1) |
| **C** | `c9611a57c` | Translate T*, Fonte IA F*, Reset Smart S*, click AI A*, logs, docs D1–D2, skill smoke |
| **D** | smoke PASS → commit | F2 embedded (`sourceInternalIndex` + resolver); foco pós-CTA; S1–S9 FocusBackground; limpeza R4 diagnostics handlers |

### Fatia D — detalhes (working tree)

- `AiSubtitleDiagnostics.sourceInternalIndex` + publishes EMBEDDED
- `resolveEmbeddedAiSourceOptionId` / testes `f2_*`, `f1_without_resolvable_option_hidesBoth`
- Translate: captura fonte → `jumpFocusToPreferredAi` → event; Reset: preflight Col2 + realinhamento
- `OverlayRailVisualRole` + `overlayRailCardColors` (foco ≠ Secondary)
- Docs: handoff visual S1–S9; PRD §1 estados visuais
- R4: removidos handlers `OnShow/DismissAiSubtitleDiagnostics` e refs `showAiSubtitleDiagnosticsOverlay` (quebravam release compile)
- `app/build.gradle.kts`: **1071 / 1.0.7**

Arquivos dirty típicos: `PlayerUiState`, `PlayerRuntimeControllerAiSubtitles`, `…PlaybackEvents`, `…Lifecycle`, `SubtitleAiSourceIndicators`(+Test), `SubtitleSelectionOverlay`, `PlayerScreen`, docs handoff/PRD UI, `build.gradle.kts`.

---

## Pendente

1. **GitHub release 1.0.7** com notas (só após user escrever “aprovo o release” / “publica 1.0.7”).
2. Opcional: residual TranslateMenu (`showSubtitleTranslateMenuOverlay` / events) ainda no UiState sem UI long-press.

### Smoke mínimo Fatia D (TV) — resultados 2026-09-24

| ID | Resultado | Evidência |
|----|-----------|-----------|
| **F2-e** | **PASS** | Chip **Fonte IA** em Embutida EN (`AI_EMBEDDED`); `.tmp_overlay_F2-e.png` · `.tmp_overlay_F1.png` (ponto amarelo Col1) |
| **F2-a** | **PASS** | Chip **Fonte IA** em AIOStreams; log `Translate with AI source=AIOStreams … rung=MANUAL`; `.tmp_overlay_F2-a.png` |
| **T-focus** | **PASS** | `post_cta_focus cta=translate target=ai:translate`; `.tmp_overlay_T-focus.png` |
| **S-focus** | **PASS** | `post_cta_focus cta=reset` → `Reset Smart Auto done … rung=AI_EMBEDDED`; `.tmp_overlay_S-focus.png` |
| **S1–S9** | **PASS** | Foco = FocusBackground + focusRing; selecionado = Secondary; CTA idle neutro; `.tmp_overlay_S1/S5/S8.png` |

Nota: F2-e validado com embutida **EN** (não FR) — mesmo path `sourceInternalIndex` / resolver.

---

## Non-goals neste handoff

- Não reabrir ladder Smart / providers / rate-limit Gemini.
- Não reintroduzir Style tabs / long-press Translate.
- Não publicar 1.0.7 sem aprovação explícita do user.

---

## Prompt sugerido (próximo chat)

Ver secção no fim deste ficheiro (ou mensagem do monitor). Colar com `@` deste handoff + `@docs/handoff/2026-09-24-overlay-info-rail.md` + skill smoke.

---

## Prompt — próximo chat (copiar)

```text
@docs/README.md @docs/handoff/2026-09-24-overlay-info-rail-close.md @docs/handoff/2026-09-24-overlay-info-rail.md

# Tarefa: Smoke Fatia D na TV → commit → release 1.0.7 (só se eu aprovar)

## Contexto
Branch `feat/subtitle-overlay-info-rail`. Fatias A–C commitadas. Fatia D + versionCode 1071 / versionName 1.0.7 estão no working tree e já instaladas na TV `192.168.31.46:5555` (`com.nuvio.tv`). GitHub ainda em 1.0.6.

## Fase 1 — Smoke (obrigatório antes de commit/release)
1. Ler o handoff close + checklist Smoke mínimo Fatia D.
2. Usar skill `.cursor/skills/ai-subtitles-smoke` adaptada à TV (não exigir emulator-5554).
3. Validar F2-e, F2-a, T-focus, S-focus, S1–S9 com screenshots `.tmp_overlay_<ID>.png` e logcat.
4. Relatório PASS/FAIL por ID. Se FAIL crítico: parar, não commit, listar gaps.

## Fase 2 — Commit (só se smoke PASS ou user disser para seguir)
- Commitar **só** ficheiros da Fatia D + bump + docs tocados (não meter `.tmp_*` nem `release-notes-1.0.6.md` solto sem limpar).
- Mensagens: uma para Fatia D (fix/feat overlay), outra ou mesma com bump se preferir estilo `bump version` separado.
- Não push force. Push da branch ok se já tracking.

## Fase 3 — Release GitHub 1.0.7
- **Só após eu escrever explicitamente “aprovo o release” / “publica 1.0.7”.**
- Notas cobrindo Fatia D: F2 embedded, foco pós-CTA, S1–S9 FocusBackground, R4 diagnostics cleanup.
- `gh release create 1.0.7` com APKs fullRelease (como 1.0.6), target = commit do bump.
- Não republicar 1.0.6.

## Non-goals
Sem mudar ladder/CTAs C1–C6. Sem reabrir TranslateMenu residual salvo se bloquear smoke.
```
