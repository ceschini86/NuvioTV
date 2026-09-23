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
- **Series Graph:** fora deste smoke; não misturar no mesmo commit AI.

---

## Se falhar

1. Recolher diagnostics + linhas `AI ladder` / `SubtitleFocus`.
2. Usar `@ai-subtitles-bug` + [`architecture-ai-subtitles.md`](./architecture-ai-subtitles.md) §3.
3. Patch mínimo; re-smoke só o caso falhado.
4. **Não** commit/PR sem pedido do utilizador.

---

## Last results (2026-09-23)

Preferido = **Spanish** (perfil Mateus). Branch `fix/ai-ladder-and-rate-limit`.

| # | Caso | Resultado | Notas |
|---|------|-----------|--------|
| 1 | Embedded traduzível | **PASS** | Amélie remux FR → `AI_EMBEDDED`; Gemini timeout/rate-limit depois |
| 2 | Preferred embedded | **PASS** | Paradise WEB-DL ES → `PREFERRED_EMBEDDED` |
| 3 | Sem embutida → classic | **PARTIAL** | SOA: AI off + addon EN restaurado (não auto-traduz); ladder **não** publicou `CLASSIC_FALLBACK` por pref explicit |
| 4 | Translate addon → MANUAL | **PASS** | SOA AIOStreams EN → Translate; `locked=true`; reason translate with AI (após fix de foco Info) |

Atualizar esta tabela ao fechar uma sessão de smoke. Uma linha no handoff se o estado global mudar.
