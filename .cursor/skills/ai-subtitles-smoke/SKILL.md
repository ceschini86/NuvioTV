---
name: ai-subtitles-smoke
description: >-
  Run NuvioTV AI subtitles emulator smoke (Smart embedded-only ladder +
  Translate with AI MANUAL + overlay Info-rail IDs). Use when the user asks to
  smoke-test AI subtitles, validate the ladder on emulator-5554, or re-run the
  AI subtitle checklist.
---

# AI subtitles — emulator smoke

## Canonical checklist

**Source of truth for cases / expected signals / last results:**
[`docs/smoke-ai-subtitles.md`](../../../docs/smoke-ai-subtitles.md)

**Overlay Info-rail handoff (IDs T/F/A/S/P + regressão I/C/K):**
[`docs/handoff/2026-09-24-overlay-info-rail.md`](../../../docs/handoff/2026-09-24-overlay-info-rail.md)

Read those docs first. Update **Last results** in the smoke doc when a session finishes.

## When to use

- User asks to smoke / validate AI subtitles on the emulator
- After ladder/policy or overlay Info-rail changes
- Before claiming embedded-only Smart + MANUAL addon path + Info CTAs are OK

**Not** for product redesign of Embedded-only. **Not** for Series Graph.

## Preconditions

| Item | Value |
|------|--------|
| Branch | usually `feat/subtitle-overlay-info-rail` (confirm with user/handoff) |
| Package | `com.nuviodebug.com` |
| Emulator | `emulator-5554` (Android TV) |
| Engine | ExoPlayer (AI path) |
| Preferred subtitle lang | **≠ EN** (e.g. Spanish / pt) |
| Settings | Smart AI on + usable API keys |

```bash
./gradlew :app:assembleFullDebug
adb -s emulator-5554 install -r app/build/outputs/apk/full/debug/app-full-x86_64-debug.apk
adb -s emulator-5554 shell am start -n com.nuviodebug.com/com.nuvio.tv.launcher.AppIconDefault
```

Logcat filters: `AI ladder:` · `AI source:` · `Translate with AI` · `Reset Smart Auto` · `Select AI option` · `SubtitleFocus` · `PlayerViewModel`

Screenshots: save as `.tmp_overlay_<ID>.png` (ex.: `.tmp_overlay_T1.png`, `.tmp_overlay_F2.png`).

## Cases (must cover)

### Ladder / Translate (core)

1. **Embedded translatable** → `AI_EMBEDDED` (AI on)
2. **Preferred embedded** → `PREFERRED_EMBEDDED` (AI off)
3. **No embedded** → classic / `CLASSIC_FALLBACK` (AI off; **do not** auto-translate addon)
4. **Translate with AI on addon** → `MANUAL` + `locked=true`

### Overlay Info-rail (ID-named — Fatia C fechamento)

| ID | Passo | Esperado | Screenshot |
|----|-------|----------|------------|
| **T1** | Info → Translate (addon **e** embedded) | `locked=true`, rung `MANUAL`, AI on | `.tmp_overlay_T1.png` |
| **T2** | Após T1 | F1 ponto amarelo + F2 chip “Fonte IA” na fonte | (com F*) |
| **T3** | Mesma sessão após Translate | Foco preferido + opção AI | `.tmp_overlay_T3.png` |
| **T4** | Info da AI | User selected / MANUAL + fonte | `.tmp_overlay_T4.png` |
| **F1** | AI on (auto ou manual) | Ponto amarelo no idioma-fonte (Col1) | `.tmp_overlay_F1.png` |
| **F2** | AI on | Chip “Fonte IA” na opção-fonte (Col2) | `.tmp_overlay_F2.png` |
| **F3** | Trocar fonte (novo Translate) | Ponto/chip mudam na hora | `.tmp_overlay_F3.png` |
| **F4** | None/Off ou Reset sem AI | Indicadores somem | `.tmp_overlay_F4.png` |
| **A1** | Click AI já selecionada | No-op; log `reason=already_selected` | log |
| **A2** | AI ativa, opção AI não selecionada → click AI | Mantém fonte | log/UI |
| **A3** | AI off → click AI | MANUAL + CTA Reset | `.tmp_overlay_A3.png` |
| **S1** | CTA “Voltar à seleção automática” | Limpa lock+explicit; re-roda ladder | log `Reset Smart Auto` |
| **S2** | Reset → `AI_EMBEDDED` | AI continua; sem CTA | `.tmp_overlay_S2.png` |
| **S3** | Reset → `PREFERRED_EMBEDDED` | Embedded preferido; AI off | `.tmp_overlay_S3.png` |
| **S4** | Reset → classic | Nuvio escolhe; Info explica | `.tmp_overlay_S4.png` |
| **S5** | Após reset | F1–F2 seguem resultado (ou F4) | (com F*) |
| **S6** | Após reset | Foco na opção selecionada (Col2) | UI |
| **P1** | Fechar/reabrir com MANUAL | Preferido + AI + Info MANUAL | `.tmp_overlay_P1.png` |
| **P2** | Reopen com AI on | F1+F2 na fonte | `.tmp_overlay_P2.png` |
| **P3** | Indicadores | Só mudam/somem per F3/F4 | — |

### Regressão Fatia B (screenshots adiados)

| ID | Nota |
|----|------|
| I1–I4, C1, C4, C5, C6, K1 | Capturar no mesmo smoke se o monitor pedir |

## Diagnostics truth

Treat overlay / logs as runtime truth: `rung`, `reason`, `source`, `locked` (plus `target` / `model` when present). Structured handler lines include `source=` `reason=` `locked=` `rung=`.

## If a case fails

1. Stop guessing — collect diagnostics + relevant logcat lines
2. Follow `@ai-subtitles-bug` (rule) + read `docs/architecture-ai-subtitles.md` §3
3. Minimal policy/UI fix only; do not reopen Embedded-only product plan
4. Re-smoke the failed case

## Constraints

- No commit / PR unless the user asks
- Do not put Series Graph changes in the same AI commit
- Do not mix deferred UI-menu bugs unless asked
- After smoke: update **Last results** in `docs/smoke-ai-subtitles.md` (and one line in handoff if status changed)
- **Device install:** only when the monitor/user asks for smoke — Fatia C code phase may end with “pronto para smoke” without install

## Related

- Handoff overlay: `docs/handoff/2026-09-24-overlay-info-rail.md`
- Handoff ladder: `docs/handoff/2026-09-23-ai-subtitles.md`
- Bug rule: `.cursor/rules/ai-subtitles-bug.mdc`
- Docs hub: `docs/README.md`
