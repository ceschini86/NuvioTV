# Handoff — Player OSD + overlay legendas WIP (2026-09-24 noite)

> Colar no chat novo: `@docs/README.md` + `@docs/handoff/2026-09-24-player-osd-overlay-wip.md` (este ficheiro).  
> Spec overlay (matrizes actualizadas): [`2026-09-24-overlay-info-rail.md`](./2026-09-24-overlay-info-rail.md).  
> Seek Stremio histórico: [`2026-09-24-player-stremio-seeker.md`](./2026-09-24-player-stremio-seeker.md) (parcialmente supersedido).  
> Overlay A–D fechado / 1.0.8: [`2026-09-24-overlay-info-rail-close.md`](./2026-09-24-overlay-info-rail-close.md).  
> Regra releases: `.cursor/rules/release-notes-user-facing.mdc`.  
> Build: **um** agente Gradle de cada vez (`./gradlew --stop` antes se necessário).

| Campo | Valor |
|-------|--------|
| Branch | `release/1.0.9` (tracking `origin/release/1.0.9`) |
| HEAD remoto / published | `dae7c3c26` bump · UI seek `da341e4d7` · release **[1.0.9](https://github.com/ceschini86/NuvioTV/releases/tag/1.0.9)** · versionCode **1073** |
| Working tree | **Uncommitted** · versionCode local **1075** / versionName `1.0.9` |
| TV Streamer | `192.168.31.46:5555` · ABI `armeabi-v7a` · sideload **1075** (não é GitHub release) |
| Figma base OSD | [`docs/design/player-osd-figma-base-1920x1080.png`](../design/player-osd-figma-base-1920x1080.png) (untracked) |

---

## Fechado nesta sessão (ainda **não** commitado)

### A) Player OSD / seek bar

Arquivo: [`PlayerScreen.kt`](../../app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerScreen.kt)

| Item | Detalhe |
|------|---------|
| Tempo VOD | Na mesma `Row` dos `ControlButton` (direita), via `PlayerControlsTimeTextHost`; barra sem linha de tempo por baixo |
| Spacer pós-barra | `md` → `xs` |
| Thumb | idle **18.dp** · foco/scrub leve **20.dp** · full scrub **22.dp** · anel **2.dp** (+2dp vs 1074) |
| Track | idle **5.dp** · foco/leve **7.dp** · full **9.dp** |
| Anel branco | Só com foco ou scrub (`showThumbRing`); idle = bolinha sólida accent |
| `sliderHeight` | **30.dp** |
| SeekOverlay | Mantém tempo sob a barra (scrub) |

### B) Overlay legendas (Info-rail)

Arquivos: `SubtitleInfoRailDecision.kt`, `SubtitleSelectionOverlay.kt`, `SubtitleAiSourceIndicators.kt`, testes, specs.

| Item | Detalhe |
|------|---------|
| CTA sem seleção | «Traduzir com IA» na opção **focada** (embedded/addon), mesmo sem estar selected |
| C2 | Sem CTA só se classic fallback **e** `isPlaybackSelected` |
| Bitmap | Translate disabled + não focável |
| IA focada não selected | Sem CTA (click = A2/A3) |
| Âncora Col3 | `infoEntryOptionId` — Info/CTA/Translate usam opção ancorada/focada |
| Back N8 | Col1/Col2 → **fecha** overlay; Col3 Back/Left → Col2 |
| **N3 click** | OK num idioma ≠ Off → browse + foco real Col2 (`language_click_to_option`) |
| **Altura** | WIP: tentativa `fillMaxHeight`/`weight` — **ainda não** toca o fundo do ecrã (pendente) |
| **F1** | `resolveIndicatorLanguageKeyForRail` (pt↔pt-br); addon resolve por label/lang se seleção limpa |
| **F4** | Indicadores exigem `translationActive`; rung `PREFERRED_EMBEDDED`/`CLASSIC`/`NONE` → Hidden |
| Spec | N3/N4/I4/N8/F1/F4 actualizados em `overlay-info-rail.md` |
| Testes | `SubtitleInfoRailDecisionTest` + `SubtitleAiSourceIndicatorsTest` (F1/F4/addon) |

### C) i18n

| String | EN | pt-BR | pt-PT |
|--------|----|-------|-------|
| `sub_ai_translating` | Translating… | Traduzindo… | A traduzir… |

### D) Build local mais rápido

```bash
./gradlew :app:assembleFullRelease -PlocalSideloadAbi=armeabi-v7a \
  -x lintVitalAnalyzeFullRelease -x lintVitalReportFullRelease -x lintVitalFullRelease \
  -x produceFullReleaseComposeMapping -x reportFullReleaseComposeMappingErrors
adb -s 192.168.31.46:5555 install -r app/build/outputs/apk/full/release/app-full-armeabi-v7a-release.apk
```

**Não** usar `-PlocalSideloadAbi` em release GitHub / AAB.

---

## Pendente

1. **Max-height / rails até ao fundo** — ainda **não** resolvido (fillMaxHeight/weight WIP; rails continuam a parar a meio do ecrã). Reabrir debug de layout.
2. Validação visual TV: OSD thumb; click idioma→Col2; F1/F4; Back/CTA.
3. Bump **1.0.10** / publish só se o user pedir.
4. Branches: `release/1.0.9`, `feat/subtitle-overlay-info-rail`, `fix/ai-ladder-and-rate-limit` (não misturar sem pedido).

### Non-goals

- Ladder Smart / rate-limit product.  
- Layout completo Stremio (rewind, separators).  
- Reabrir Style / long-press Translate.  
- Release automática só porque há WIP.
