# Smoke — AI subtitles (emulator)

> **Fonte canónica** do checklist de smoke no device.  
> Skill do Agent: `.cursor/skills/ai-subtitles-smoke/SKILL.md`  
> Índice: [`README.md`](./README.md) · handoff: [`handoff/2026-09-23-ai-subtitles.md`](./handoff/2026-09-23-ai-subtitles.md)

**Smart AI = só embutidas** (não reeditar o plano Embedded-only aqui). Addons só via **Translate with AI** → `MANUAL`.

---

## Pré-requisitos

| Item | Valor |
|------|--------|
| Package debug | `com.nuviodebug.com` |
| Emulador | `emulator-5554` |
| APK | `app/build/outputs/apk/full/debug/app-full-x86_64-debug.apk` |
| Engine | ExoPlayer |
| Preferred subtitle | **≠ EN** (ex. Spanish) |
| Settings | Smart AI on + keys usáveis |

```bash
./gradlew :app:assembleFullDebug
adb -s emulator-5554 install -r app/build/outputs/apk/full/debug/app-full-x86_64-debug.apk
adb -s emulator-5554 shell am start -n com.nuviodebug.com/com.nuvio.tv.launcher.AppIconDefault
```

**Logcat:** `AI ladder:` · `AI source:` · `Translate with AI` · `SubtitleFocus` · tag `PlayerViewModel`

**Diagnostics (overlay Info):** `rung` · `reason` · `source` · `locked` (+ `target` / `model`)

### Bancada com mock (testbench)

Ferramentas **fora do repo**, em `C:\Users\Usuario\Documents\.personal\NuvioTV-testbench\tools`:

| Ferramenta | Papel |
|------------|-------|
| `mock_llm.py` | LLM falso na porta **8787**; injeta falhas (echo, truncate, short, server_error, slow, refusal, rate_limit). "Traduz" prefixando `(PT) ` ao texto em inglês |
| `local_addon.py` | Addon de legendas local na porta **7000** |
| `quota_guard.py` | Rodar **antes de qualquer play no mock**; deve imprimir `GUARD OK ... override ACTIVE` (garante que o app aponta para o mock e não gasta quota real) |
| `fault_smoke.py` | Cicla os modos de falha e marca linhas `PHASE` no logcat com a tag `SubtitleSmoke` |
| `ai_sub_logcat.py summarize` | Resume o logcat (requests, falhas, cues traduzidas) |

Override de base URL (só build debug; o app loga `DEBUG AI base URL override ACTIVE`):

```bash
adb -s emulator-5554 shell setprop debug.nuvio.ai_base_url http://10.0.2.2:8787   # liga
adb -s emulator-5554 shell setprop debug.nuvio.ai_base_url ""                      # desliga
```

Como ler o resultado: **`(PT) English text` na tela = sucesso** (a tradução passou pelo mock). Texto em inglês puro = fila ainda não respondeu / linha abandonada; `(PT) 040 Where were` (corte no meio) = bug do parser de truncado (corrigido em F1).

Cuidados:

- **Cache em disco F1 sobrevive a reinstall.** Limpar com `adb shell pm trim-caches 999G` (o debug "release-ish" não é debuggable, `run-as` falha). Sem isso, linhas já cacheadas escondem o mock.
- **Windows permite dois servidores python no mesmo bind/porta**: matar instâncias antigas (`mock_llm.py` / `local_addon.py`) antes de subir de novo, senão o app pode falar com a instância velha.

---

## Checklist

| # | Caso | Setup | Esperado |
|---|------|--------|----------|
| 1 | Embedded traduzível | Stream com embutida ≠ preferred (ex. FR, preferred ES) | Log `AI ladder: AI translation source…`; rung **`AI_EMBEDDED`**; UI “Translating…” / opção AI |
| 2 | Preferred embedded | Embutida no idioma preferido | Log `AI ladder: preferred embedded…`; rung **`PREFERRED_EMBEDDED`**; AI **off** |
| 3 | Sem embutida → classic | `internalSubs=0` (ou sem fonte embutida útil); **sem** preferência addon stale do mesmo título | Log `no smart embedded source — classic fallback`; rung **`CLASSIC_FALLBACK`** (ou classic auto-select); AI **off**; **não** auto-traduzir addon |
| 4 | Translate addon → MANUAL | Overlay → idioma EN → addon AIOStreams → Info → **Translate with AI** | rung **`MANUAL`**; `locked=true`; reason `user chose translate with AI`; source addon |

### Gotchas

- **Preferência same-series:** `Restoring same-series addon subtitle` + `SUB_POLICY stop: user explicit selection` **impede** a ladder. Para o caso 3 limpo, usar título/episódio **sem** pref de legenda, ou limpar seleção Off→reabrir / outro media.
- **Focus Info/Translate:** DPAD Options → Info deve focar `info_translate`. Se só há `style_focus_schedule` sem `style_focus_request`, ver fix em `SubtitleSelectionOverlay` (não scrollar LazyColumn na aba Info).
- **Quota / rate-limit:** `aiSubtitleQuotaExhausted` oculta Translate; S6 preserve selection (não é falha da ladder).
- **Task de teste:** `gradlew.bat :app:testFullDebugUnitTest --tests *Subtitle*` (`testDebugUnitTest` é ambíguo por flavor).
- **APK emulador:** `app-full-x86_64-debug.apk` (`app/build/outputs/apk/full/debug/`).
- **Series Graph:** fora deste smoke; não misturar no mesmo commit AI.

---

## Se falhar

1. Recolher diagnostics + linhas `AI ladder` / `SubtitleFocus`.
2. Usar `@ai-subtitles-bug` + [`architecture-ai-subtitles.md`](./architecture-ai-subtitles.md) §3.
3. Patch mínimo; re-smoke só o caso falhado.
4. **Não** commit/PR sem pedido do utilizador.

---

## Last results (2026-10-29)

Refactor **F1** (fila LIVE/NEAR/FAR + hedge, validador, cache em disco). Logs em `NuvioTV-testbench\runs\2026-10-29\` (`fault_smoke.log`, `mini_smoke_fixes.log`, `real_round.log`).

### 1. Fault smoke no mock (`embedded_mix`, ~7,5 min, seek do usuário 2:30 → ~3:57)

- 63 requests (59 Gemini, 4 fallback Claude). Falhas injetadas: echo 6, truncate 5, short 3, server_error 11, slow 2, refusal 1, rate_limit 6.
- 71 cues traduzidas, sem crash.
- **2 bugs achados, ambos corrigidos:**
  - (a) cauda de JSON truncado fechada como linha válida (`(PT) 040 Where were` exibida e cacheada) → fix no parser (`repairTruncatedArray`).
  - (b) cue na tela esperou ~30 s atrás de um lote de prefetch lento → hedge (3 s).
- **Conhecido/aceito:** com todas as keys em 429 a AI desliga na sessão e re-testa após o cooldown (oscila on/off enquanto todas seguem limitadas); primeira cue traduzida após seek +3,3 s.

### 2. Mini smoke após os fixes (ok 30 s, truncate 60% 70 s, slow 40% 100 s)

- 41/41 cues exibidas traduzidas e completas; 8 truncamentos recuperados dentro do lote; 30 requests.
- Hold de prefetch fino: lotes de 1 linha 81% → 26%, mas linhas/request quase igual (1,71 baseline → 1,73): cues embutidas chegam ~1 a cada 6 s, então o hold rende sobretudo lotes de 2 linhas.

### 3. Rodada real (Gemini real, AIOStreams)

40 POST, todos HTTP 200; 38 lotes ok; 0 429; 0 abandonadas; 2 rejeições por alinhamento parcial recuperadas linha a linha; segunda key nunca necessária.

| Título | Resultado |
|--------|-----------|
| Amélie | Addon pt-BR nativo escolhido (sem AI) |
| Paradise S01E01 | Embutida pt-BR nativa (sem AI) |
| Gomorra: The Origins S01E01 | AI na embutida **polonesa**: 28/28 cues traduzidas, latência mediana do lote ~1,0 s, 17 requests para 39 linhas |
| 12 Angry Men | Embutida pt-BR nativa (sem AI) |
| Twelve Monkeys S01E01 (REMUX FraMeSToR) | Auto escolheu addon pt-BR; usuário escolheu addon EN manualmente (HDTV TELEVISION, depois RERIP 720p DEMAND) + AI: 197 linhas em 23 requests (lotes de até 40), lote mediano 3,1 s |

Pontos em aberto:

- Polonês escolhido como fonte AI para uma série italiana.
- AI habilita de forma transitória em EN enquanto as tracks recarregam (0 requests).
- Cues de sidecar/addon não são logadas por `SubtitleCueDisplay`: % traduzido não é mensurável para fontes addon.
- Releases de legenda escolhidos para Twelve Monkeys não casam com a release do vídeo (sync não verificável sem áudio).

---

## Last results (2026-09-23)

Preferido = **Spanish** (perfil Mateus). Branch `fix/ai-ladder-and-rate-limit`.

| # | Caso | Resultado | Notas |
|---|------|-----------|--------|
| 1 | Embedded traduzível | **PASS** | Amélie remux FR → `AI_EMBEDDED`; Gemini timeout/rate-limit depois |
| 2 | Preferred embedded | **PASS** | Paradise WEB-DL ES → `PREFERRED_EMBEDDED` |
| 3 | Sem embutida → classic | **PARTIAL** | SOA: AI off + addon EN restaurado (não auto-traduz); ladder **não** publicou `CLASSIC_FALLBACK` por pref explicit |
| 4 | Translate addon → MANUAL | **PASS** | SOA AIOStreams EN → Translate; `locked=true`; reason translate with AI (após fix de foco Info) |

### Overlay Fatia D (2026-09-24) — TV `192.168.31.46:5555`

Preferido = **pt-BR** (perfil Mateus). `com.nuvio.tv` **1.0.7** / 1071. Título: TWD S08E06.

| ID | Resultado | Notas |
|----|-----------|--------|
| F2-e | **PASS** | Fonte IA em Embutida EN (`AI_EMBEDDED`) |
| F2-a | **PASS** | Fonte IA em AIOStreams MANUAL |
| T-focus / S-focus | **PASS** | `post_cta_focus` translate + reset → `AI_EMBEDDED` |
| S1–S9 | **PASS** | FocusBackground vs Secondary |

Atualizar esta tabela ao fechar uma sessão de smoke. Uma linha no handoff se o estado global mudar.
