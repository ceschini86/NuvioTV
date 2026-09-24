# Handoff — Menu legendas: Info por opção + “voltar ao smart” (2026-09-23)

> Colar no chat novo: `@docs/README.md` + `@docs/handoff/2026-09-23-subtitle-menu-info-ladder.md` (este ficheiro).  
> Docs canónicos: [`prd-ai-subtitles-ui-menu.md`](../prd-ai-subtitles-ui-menu.md) · [`architecture-ai-subtitles.md`](../architecture-ai-subtitles.md) §3 (só se precisar de ladder/locks).  
> **Não** reabrir Embedded-only / rate-limit Gemini / Series Graph neste handoff.

| Campo | Valor |
|-------|--------|
| Branch típica | `fix/ai-ladder-and-rate-limit` |
| TV / package (sessão recente) | `192.168.31.46:5555` · `com.nuvio.tv` |
| Âncora UI | `SubtitleSelectionOverlay.kt` · events em `PlayerRuntimeControllerPlaybackEvents.kt` |
| Âncora policy | `PlayerRuntimeControllerAiSubtitles.kt` (`aiSubtitleUserLocked`, `isUserExplicitSubtitleSelection`, ladder) |
| Gap já no PRD | UI menu **G2** — sem “Reset to smart”; §B6 “Reset via menu Info” |

---

## Notação do menu (3 colunas)

```text
1ª coluna          →  2ª coluna              →  3ª coluna
[Idiomas + Nenhum] →  [Opções desse idioma]  →  [Painel Info | Style]
```

- **1ª:** lista de idiomas de legenda + Off/Nenhum.  
- **2ª:** ficheiros/tracks desse idioma **e**, no idioma **preferido**, a opção sintética `ai:translate` (“Traduzir com IA” / slot AI).  
- **3ª:** Info (default) ou Style — card da fonte + CTAs (Translate / Stop / Disable).

---

## Problema observado (produto)

1. **Não há botão para devolver o controlo à ladder automática** depois de MANUAL / escolha explícita.  
   Hoje: Stop AI só desliga tradução (fonte por baixo fica); Disable = off. Para “voltar ao smart” o utilizador quase tem de **escolher manualmente** FR/RU/EN e outra vez **Traduzir com IA**, ou depender de nova stream / limpar `explicit` (path opaco).  
   Documentado: `prd-ai-subtitles-ui-menu.md` §B6 + gap **G2**.

2. **Info (3ª coluna) não acompanha bem o foco na 2ª** como o utilizador espera: ao **passar** pelas opções (sem necessariamente confirmar playback), o painel Info deveria mostrar os dados **dessa** opção (título, lang, score, se é AI, CTAs corretos).

---

## Comportamento desejado (lateral)

Enquanto o utilizador **navega** (foco) na 2ª coluna:

| Foco (exemplo) | Info (3ª) deve mostrar |
|----------------|-------------------------|
| Idioma preferido → opção **`AI` / Traduzir com IA** | Meta **dessa** opção AI (status translating / diagnostics da fonte por baixo se active; CTA Stop vs Start conforme estado) |
| Idioma preferido → outra opção clássica (embedded/addon pt) | Card **dessa** fonte; Translate se aplicável |
| Outro idioma (ex. EN) → ficheiro concreto | Card **desse** ficheiro (score, lang, Translate…) |

**Regra:** Info = preview da opção **focada** na 2ª coluna, não só da seleção “efetiva” de playback / sticky de sessão.

### Caso de referência: preferido = **Inglês**

Pré: settings idioma preferido de legenda = **English**; Smart AI on; stream com embutidas e/ou addons mistos.

Walkthrough esperado:

1. Abrir overlay → 1ª coluna em **English** (preferido).  
2. 2ª coluna: opções EN + slot **`AI`** (quando `aiSubtitleAvailable`).  
3. Foco em **`AI`** → Info descreve o modo AI (e, se active, fonte real por baixo: embedded/addon + diagnostics).  
4. Mover foco para um **addon/embedded EN** concreto → Info **muda** para esse ficheiro (sem precisar long-press).  
5. Após MANUAL / explicit: deve existir CTA claro tipo **“Voltar ao automático / Smart”** (nome TBD) que:
   - limpa `aiSubtitleUserLocked` + `isUserExplicitSubtitleSelection` (e diagnostics MANUAL),
   - re-corre `applySubtitleAutoSelectPolicy` / ladder,
   - realinha menu para preferido + resultado da ladder (preferred embedded **ou** `AI` se `AI_EMBEDDED`).

**Não** confundir com Stop AI (só tradução off, mesma fonte) nem Disable (off total).

---

## Estado atual (código / doc) — resumo

| Peça | Hoje |
|------|------|
| Opção `ai:translate` | Slot visual “estou no preferido via tradução”; Smart settings ≠ opção separada |
| Translate (Info / long-press) | `MANUAL` + `userLocked`; `refreshSource=false` se track/addon explícito |
| Toggle AI (`ai:translate`) | `locked=true` + `refreshSource=true` → `findAiSource…` (pode escolher RU em vez de FR — sessão TV 2026-09-23) |
| Stop AI | Limpa lock AI; **não** rearm ladder se `explicit` |
| Reset to smart | **Não existe** (G2) |
| Info vs foco 2ª coluna | PRD B6: “opção efetiva selecionada”; sticky de sessão após Translate (§2 / G1) — candidato a alinhar Info ao **foco** |

---

## Fora de escopo deste handoff

- Reabrir política Smart embedded-only.  
- Gemini rate-limit / batching / badge Translating (já tratado noutro handoff).  
- Series Graph / outros diffs no mesmo commit.  
- Redesign completo do overlay (só Info-follow-focus + reset smart).

---

## Status

| Item | Estado |
|------|--------|
| Gap G2 documentado no UI menu | Feito (PRD) |
| Spec desejada (Info = foco 2ª + Reset smart) | **Este handoff** |
| Implementação | **Pendente** |
| Smoke TV/emulador do caso English | **Pendente** |

---

## Prompt de recuperação (copiar)

```text
Handoff menu legendas — Info por opção + voltar ao smart.

Lê:
- @docs/README.md
- @docs/handoff/2026-09-23-subtitle-menu-info-ladder.md
- @docs/prd-ai-subtitles-ui-menu.md (§1 rails, §2, B6, G1/G2)
- architecture-ai-subtitles.md §3 só se precisares de locks/ladder

Notação: 1ª idiomas+nenhum > 2ª opções > 3ª Info.

Problema: sem CTA “voltar ao automático/ladder”; Info não acompanha o foco na 2ª coluna como esperado.
Caso referência: preferido = Inglês.

Implementar (quando Agent):
1. Info (3ª) atualiza conforme foco/navega opções na 2ª (incl. English > AI vs ficheiro EN).
2. CTA explícito “voltar ao smart / automático” que limpa explicit+userLocked e re-corre ladder (≠ Stop AI, ≠ Disable).
3. Não misturar rate-limit Gemini / Series Graph / Embedded-only redesign.

Sem commit/PR até eu pedir.
```
