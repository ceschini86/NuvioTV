# Docs — índice para Agent / humanos

> **Uso com Agent:** `@docs/README.md` no início de um chat novo, depois **só** o doc da tarefa.  
> **Não** `@` a pasta `docs/` inteira nem vários docs AI de uma vez.

## Pacote AI subtitles (versionado no git)

| Preciso de… | Abrir | Fonte canônica de |
|-------------|--------|-------------------|
| **Handoff para chat novo** | [`handoff/2026-09-23-ai-subtitles.md`](./handoff/2026-09-23-ai-subtitles.md) | Estado branch / emulador / pending |
| **Spec overlay: Info rail dinâmico (em execução)** | [`handoff/2026-09-24-overlay-info-rail.md`](./handoff/2026-09-24-overlay-info-rail.md) | Base + fatias A/B/C + log do monitor (substitui o handoff de 2026-09-23 do menu) |
| **Smoke no emulador (checklist + last results)** | [`smoke-ai-subtitles.md`](./smoke-ai-subtitles.md) | Casos PASS/FAIL no device · skill `.cursor/skills/ai-subtitles-smoke` |
| Escopo, Must/Should/Won’t, smoke de produto | [`prd-ai-subtitles.md`](./prd-ai-subtitles.md) | Requisitos de produto |
| Ladder real, pipeline, keys, ADRs, mapa de arquivos | [`architecture-ai-subtitles.md`](./architecture-ai-subtitles.md) | Implementação / ordem da ladder |
| Overlay, locks UX, matrizes de fluxo do menu | [`prd-ai-subtitles-ui-menu.md`](./prd-ai-subtitles-ui-menu.md) | Comportamento do menu (não settings) |

**Estado (2026-09-23):** código presente neste working tree na branch `fix/ai-ladder-and-rate-limit` (base `release/1.0.2`). Ainda pode estar ausente em `dev` até merge. **Smart AI = só embutidas** (preferred → AI embedded → classic); **Translate with AI** = qualquer fonte (embedded ou addon).

### Âncoras de código (AI)

- `ui/screens/player/PlayerRuntimeControllerAiSubtitles.kt`
- `ui/screens/player/subtitles/SubtitleAiRouter.kt`
- `ui/screens/player/subtitles/SubtitleTranslationManager.kt`
- `ui/screens/player/SubtitleSelectionOverlay.kt`
- `data/local/DeviceLocalPlayerPreferences.kt`

## Outros docs (locais / não no pacote AI versionado)

| Doc | Quando usar |
|-----|-------------|
| [`architecture.md`](./architecture.md) | Onboarding geral (player, legendas clássicas, stack). §7.B só aponta para o pacote AI. |
| [`essential-mode.md`](./essential-mode.md) | Spec Essential vs Advanced (arquivo grande — peça seções). |
| [`architecture/mdblist-library.md`](./architecture/mdblist-library.md) | Library MDBList |

## Regras rápidas

1. Um tema AI → um doc canônico (tabela acima).  
2. Ladder de **produto** no PRD; ladder **como no código** em `architecture-ai-subtitles.md` §3.  
3. Auditorias / dumps históricos: só se a tarefa for auditar (ex. UI menu §10).  
4. Ao fechar uma fatia: atualizar status/gaps do PRD + 1 frase no doc canônico se a decisão mudou.  
5. **Score de release ≠ LLM** e **≠ gate do Smart** (só badge / info no overlay). Rate-limit → preserve selection (PRD **S6** / UI menu §3.5) só no path com **tradução AI ativa**.
