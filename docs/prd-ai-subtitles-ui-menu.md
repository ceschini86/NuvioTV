# Requisitos UX — Menu de legendas (AI) · Overlay do player

> **Uso com Agent:** `@` este arquivo só para fluxos/estados do **overlay** (rails, Translate, diagnostics, locks UX).  
> **Não usar para:** HTTP/providers, scoring interno, settings Playback → AI, nem ladder de implementação (architecture §3).  
> **Fonte canônica de:** comportamento do menu e matrizes de entrada na stream.  
> **Índice:** [`README.md`](./README.md).  
> **§10 (auditoria):** apêndice histórico — `@` só se a tarefa for auditar regressões.

| Campo | Valor |
|-------|--------|
| Escopo | **B+C** — overlay de legendas no player + estados/bloqueios. Settings (A) fora. |
| Fonte de verdade | Working tree / `fix/ai-ladder-and-rate-limit` (`SubtitleSelectionOverlay`, `PlayerRuntimeControllerAiSubtitles`, events/UI state); base ship `release/1.0.2` |
| Relacionados | [`prd-ai-subtitles.md`](./prd-ai-subtitles.md), [`architecture-ai-subtitles.md`](./architecture-ai-subtitles.md) |
| Última revisão | 2026-09-25 (B6d avisos Info + B7 banner F1/F2/F2b de mudança no menu) |

Este documento descreve **o que o usuário vê e o que acontece em cada ramificação**, de forma reproduzível. Onde o código diverge do que o usuário “espera”, isso está marcado como **comportamento atual**.

---

## 0. Glossário (flags que governam o menu)

| Flag | Onde vive | Efeito na UX |
|------|-----------|--------------|
| **AI feature on** (`aiEnabled`) | Settings | Sem isso, opção AI / translate não ficam disponíveis |
| **Smart AI** (`aiAutoSelect`) | Settings | Só afeta **auto-seleção no 1º acesso / refresh**; não muda o layout do menu sozinho |
| **Keys usáveis** | Device-local | Sem key → `aiSubtitleAvailable=false` |
| **ExoPlayer** | Engine | Em MPV → AI indisponível (banner) |
| **`aiSubtitleAvailable`** | UI state | Opção sintética `AI` aparece no rail do idioma **preferido** |
| **`aiSubtitleTranslationActive`** | UI state | Tradução ligada; meta “Translating…” / badge AI selecionada (ver §3) |
| **`aiSubtitleUserLocked`** | Runtime | Escolha manual de AI; ladder **não** sobrescreve |
| **`isUserExplicitSubtitleSelection`** | Runtime | Usuário escolheu track/addon/off clássico; **auto-policy não roda** |
| **Preferred language** | Settings de legenda | Idioma-alvo da tradução **e** único rail onde a opção `AI` é listada |

Dois “locks” diferentes:

1. **Explicit** — “eu escolhi esta legenda (ou off) sem AI”. Auto-select/ladder param.
2. **UserLocked (AI)** — “eu pedi Translate with AI / liguei a opção AI”. Ladder não troca a fonte; tradução continua.

Nova stream / `releasePlayer(flush)` zera lock AI, diagnostics e translating. Troca de episódio (`refreshSubtitlesForCurrentEpisode`) zera **explicit** (salvo “keep disabled” persistido) e dispara de novo a policy.

---

## 1. Modelo mental do overlay

Três rails (TV/DPAD):

```text
[ Idiomas ] → [ Opções daquele idioma ] → [ Info ]
```

- **Idiomas (Col1):** Off + códigos presentes (embedded + addons), filtrados pelas prefs clássicas (`showOnlyPreferred…`). Contador do idioma **preferido** inclui a opção sintética AI quando listada. **Sem ✓** na Col1. Com AI ativa: **ponto amarelo** no idioma da fonte atual (embedded ou addon).
- **Opções (Col2):** No idioma **preferido**, se AI disponível, a **primeira** opção é sintética:
  - id `ai:translate`
  - source badge `AI`
  - título = nome do idioma preferido
  - meta = “AI translation to preferred language” | “Translating…” | hint de API key
- Demais opções: embedded / stream-provided / addons daquele idioma, addons com **badge `%`** se score > 0. O **✓** existe só aqui (opção ativa no playback). Com AI ativa: chip amarelo **“Fonte IA”** / **“AI source”** ao lado do chip de origem na opção que é a fonte atual.
- **Info (Col3):** painel único (sem aba Style; sem long-press Translate/Diagnostics). Card da opção focada/selecionada + diagnostics; CTAs conforme matriz B6 (Translate with AI / Voltar à seleção automática). Desligar legendas = só **None/Off** na Col1.

### Estados visuais (foco) — Fatia D

Foco do DPAD = `FocusBackground` (como Settings), **não** Secondary. Secondary/roxo só para opção selecionada (Col2) ou idioma browsed na Col1 (~18%). CTA enabled sem foco = neutro (nunca roxo “falso selecionado”). ✓ só na Col2.

### Overlays empilhados

| Overlay | Status |
|---------|--------|
| Translate menu (long-press) | **Removido** — Translate vive no Info |
| Diagnostics (long-press AI) | **Removido** — diagnostics vivem no Info |

Chip no player: “Translating…” enquanto `isAiSubtitleTranslating && aiSubtitleTranslationActive`.

---

## 2. Resposta direta: Translate with AI “vai” para a opção Smart AI?

**Sim — inclusive na mesma sessão do overlay.**

| Momento | O que o usuário vê |
|---------|---------------------|
| **Após Info → Translate with AI** (mesma abertura) | Foco salta na hora para Col1 = idioma **preferido** → Col2 = opção **`AI` selecionada**. Info mostra MANUAL / User selected + fonte. |
| **Após fechar e reabrir** o overlay, com tradução ainda ativa | Mesmo alinhamento: preferido + `AI` selecionada; indicadores Fonte IA (ponto + chip) na fonte atual. |
| **Clicar na opção `AI` já selecionada** | No-op (G1): não reinicia Translate nem troca a fonte. |
| **Clicar na opção `AI` com tradução já ativa** | Só alinha o highlight; mantém a fonte. |
| **Clicar na opção `AI` com tradução off** | Liga AI em modo MANUAL; runtime escolhe/mantém a fonte; CTA “Voltar à seleção automática”. |

“Smart AI” (settings) **não** é uma opção separada no menu. A opção `AI` é o **slot visual** de “estou recebendo o idioma preferido via tradução”. Smart só decide se, no 1º acesso, o player **entra sozinho** nesse modo (ladder).

---

## 3. Matriz: settings × estado de entrada na stream

Pré-condições comuns: ExoPlayer, idioma preferido ≠ none (senão ladder → rung NONE / sem AI).

### 3.1 Primeiro acesso à stream (sem seleção manual nesta sessão)

`explicit=false`, `userLocked=false` após load (salvo restore de preferência persistida — ver 3.3).

| AI feature | Smart | Keys | Resultado automático | Ao abrir o menu |
|-----------|-------|------|----------------------|-----------------|
| Off | * | * | Auto-select clássico (upstream) | Sem opção `AI` |
| On | Off | Não | Clássico; AI indisponível | Sem opção `AI` (ou meta pedindo key se UI ainda listasse — **atual:** só lista se `aiSubtitleAvailable`) |
| On | Off | Sim | Clássico preferred-language; **AI off** | Opção `AI` visível no preferido, **não** selecionada |
| On | On | Sim | **Ladder** (§3.1.1) | Depende do rung (abaixo) |
| On | On | Não | Clássico | Sem opção `AI` |
| * | * | * + **MPV** | Sem AI | Banner `AI translation requires ExoPlayer`; sem AI acionável |

#### 3.1.1 Ladder → o que o menu mostra depois (Smart on)

Smart **só** considera tracks embutidas. Addons não entram na auto-seleção AI; só via classic (sem tradução) ou **Translate with AI** (MANUAL).

| Rung | Playback | Menu (ao reabrir) |
|------|----------|-------------------|
| **PREFERRED_EMBEDDED** | Embedded no idioma preferido, AI **off** | Idioma preferido; opção embedded selecionada; `AI` presente mas não selecionada |
| **AI_EMBEDDED** | Embedded (original/outro) + tradução **on** | Preferido + opção **`AI` selecionada**; Info: source = track embutida |
| **CLASSIC_FALLBACK** | Auto clássico (pode escolher addon no preferido **sem** AI) | Como upstream |
| **NONE** (`preferred=none`) | Sem alvo | Sem caminho AI útil |

Deferimentos (usuário pode abrir o menu “cedo”): tracks embutidas ainda não escaneadas → ladder completa depois. A ladder **não** espera loading de addons para decidir AI; o classic pode esperar addons no path próprio. Menu pode mudar sozinho quando a policy rodar de novo.

### 3.2 Usuário já selecionou legenda manualmente (clássico)

Ações que marcam **`explicit=true`** e **desligam AI** (`setAi(false)` → limpa `userLocked`):

- Escolher embedded ou addon no rail de opções  
- Disable subtitles (Off)

| Situação seguinte | Comportamento |
|-------------------|---------------|
| Smart on, addons chegam / tracks refresh | Policy **não** reaplica ladder (explicit) |
| Usuário long-press → **Translate with AI** | Traduz **aquela** fonte; `userLocked=true`; AI on; diagnostics **MANUAL**. **Não** limpa `explicit` no código atual → futuros `applySubtitleAutoSelectPolicy` ainda saem cedo no check explicit (a tradução já ligada permanece; ladder não “recupera” se a fonte cair) |
| Usuário escolhe opção **`AI`** no preferido | Toggle AI on + `userLocked=true` (se estava off). Não chama `remember*` → **não** seta explicit por esse path |
| Usuário escolhe outra track clássica | AI off, explicit true de novo |

### 3.3 Restore de preferência / “keep disabled”

Se a stream restaura “legendas off” ou addon lembrado:

- Pode iniciar com `autoSubtitleSelected` / flags de persistência de modo que a policy não empurre ladder como num cold first-access limpo.
- `refreshSubtitlesForCurrentEpisode` com keep-disabled mantém off e não reabre AI sozinho.

(Detalhe de engine-switch / remember está na arquitetura; para UX do menu: **Off explícito permanece Off** até o usuário escolher de novo.)

### 3.4 Settings mudando **durante** a reprodução

| Mudança | Efeito imediato |
|---------|-----------------|
| AI feature **off** ou keys removidas | AI desliga; `userLocked` limpo; opção `AI` some do menu |
| Smart **on** (feature+keys ok, sem explicit/lock) | `applySubtitleAutoSelectPolicy` → ladder pode **trocar** a legenda atual |
| Smart **off** | Não desliga tradução já ativa; só para de usar ladder nas próximas policies |
| Preferred language muda | Novo alvo de tradução; opção `AI` migra para o **novo** rail preferido |

### 3.5 Rate-limit total (quota esgotada) — UX de menu

**Regra de ouro:** avisar **sem mudar** o highlight / a fonte que o utilizador tinha. **Nunca** promover sozinho outra língua (ex. French) só porque a tradução falhou.

| Situação | O que o user deve ver no menu |
|----------|-------------------------------|
| Clicou **Translate** e não há quota | Continua no **mesmo idioma + mesmo ficheiro**; Info com aviso de rate limit; **Translate oculto ou desativado** até haver quota |
| Smart já traduzia e esgota quota | Mantém a **fonte que estava a ser traduzida** (visto nela); AI deixa de estar selected; aviso; **sem** saltar para outra língua |
| Abre o menu já sem quota | Lista clássica normal; **sem** CTA Translate with AI útil (ocultar ou cinzento com motivo) |

**CTA:** preferível **ocultar ou desativar** Translate with AI quando `allUsableKeysInCooldown` — não oferecer um clique que só falha e mexe no menu.

**Nota de implementação:** o fallback S6 antigo (desligar AI → classic/ladder e poder selecionar embedded FR) **viola** esta regra quando o preferido não tem track. Corrigir para: AI off + **preserve selection** + erro; não re-correr classic só por 429.

| Ainda há outra key/provider fora de cooldown | M5: tentar a próxima; só depois esgotar é “sem quota” |

---

## 4. Fluxos detalhados (B)

Cada fluxo: **Pré** → **Ações** → **Resultado** → **Ramificações**.

### B0 — Abrir o overlay

**Pré:** Playback ativo.  
**Ação:** Controles → legendas / atalho de overlay.  
**Resultado:**

- Rails montam com snapshot da sessão (`remember(visible)`).
- Idioma inicial:
  - Se **AI translation active** e o preferido existe na lista → **abre no preferido**.
  - Senão → idioma da fonte atualmente selecionada (addon lang ou embedded), senão primeiro idioma útil, senão Off.
- Opção inicial:
  - Se AI active **e** idioma inicial = preferido → **`AI` selecionada**.
  - Senão → opção da fonte real (internal:/addon:…), se ainda no mesmo idioma.
- 3º rail (quando revelado): aba **Info** aberta por padrão (Style à direita).

**Ramificação MPV + feature on:** banner no topo do overlay; AI não acionável.

---

### B1 — Navegar idiomas / opções (sem confirmar AI)

**Pré:** Overlay aberto.  
**Ação:** DPAD entre idiomas e opções; foco em addon com score.  
**Resultado:** Badge `%` visível quando score > 0; foco revela rail Info/Style (aba **Info** aberta por padrão).  
**Não confirma** seleção até click (comportamento clássico do overlay).

---

### B2 — Selecionar opção clássica (embedded / addon)

**Pré:** Overlay aberto; opção não-AI.  
**Ação:** Click na opção.  
**Resultado:**

- Aquela fonte passa a tocar **sem** tradução.
- AI off; `userLocked` limpo; **`explicit=true`** (remember).
- Highlight na opção clássica; Info mostra meta dessa fonte.
- Smart **não** reaplica enquanto explicit permanecer.

**Ramificação:** Estava com AI active → cues voltam ao texto original da nova fonte imediatamente (cache AI reset no disable).

---

### B3 — Selecionar opção sintética `AI` (idioma preferido)

**Pré:** `aiSubtitleAvailable`; rail no preferido; opção `AI` listada.  
**Ação:** Click em `AI`.

| Estado antes | Resultado |
|--------------|-----------|
| AI **off** | Liga tradução (`userLocked=true`); escolhe/ refresca fonte via `selectAiTranslationSourceIfAvailable` / manager; meta → Translating… → “AI translation…”. Diagnostics conforme fonte. |
| AI **on** | **Toggle off** (Stop): tradução para; lock limpo; **a fonte por baixo permanece** (ex.: ainda é o addon EN). No menu, se a sessão ainda aponta para `AI`, o highlight pode ficar inconsistente até mudar de opção/idioma ou reabrir — **comportamento atual**. |

**Nota:** Isto **não** é o mesmo que “rodar a ladder do zero” se `userLocked` já estava on; toggle on com lock evita upgrade para preferred embedded.

---

### B4 — Long-press em opção **não-AI** → Translate menu

**Pré:** Foco numa opção INTERNAL/ADDON.  
**Ação:** Long-press.  
**Resultado:** Overlay `SubtitleTranslateMenu`:

1. **Translate with AI** (enabled só se `aiSubtitleAvailable`)
2. **Disable subtitles**

#### B4a — Translate with AI

**Resultado runtime:**

- Seleciona **exatamente** aquela track/addon.
- AI on, `userLocked=true`, diagnostics **MANUAL** / reason `user chose translate with AI`.
- Menu fecha; overlay de legendas permanece aberto (`showSubtitleOverlay=true`).
- Chip Translating… até o batch.

**Menu (mesma sessão):** não força salto para opção `AI` (§2).  
**Menu (reabrir):** preferido + `AI` selecionada; Info mostra a fonte MANUAL.

**Ramificações:**

| Condição | Efeito |
|----------|--------|
| AI feature off / sem key | Click Translate ignorado / botão disabled |
| MPV | Evento no-op |
| Fonte PGS / sem texto | Depois: `onUntranslatableSource` → tenta outra fonte ou desliga AI (+ ladder se smart e permitido **e** sem lock — ver C5) |
| Rate limit (parcial) | M5: próxima key/provider; Info/`lastError` rate limit; cues originais até sucesso |
| Rate limit **total** (`allUsableKeysInCooldown`) | **Não** aplica S6 (lock MANUAL): permanece na fonte escolhida + erro + retry/cooldown. Diferente do path Smart AI-on (§3.5) |
| Keys **já** todas em cooldown no click | Translate **ainda liga** AI+MANUAL (keys “existem”); batches falham 429; mesmo comportamento “sem S6” |
| `explicit` já true (escolheu track antes) | **Permanece** true (G4); policy inteira blocked no check explicit → fonte MANUAL estável em refreshes |
| `explicit` false (ex.: só auto-classic / Smart, depois long-press Translate sem click clássico) | Policy com `userLocked` chama `selectAiTranslationSourceIfAvailable` em refreshes → **pode trocar** a fonte MANUAL por embedded preferido como pivot (**comportamento atual** / risco; ver §4.1) |

#### B4b — Disable subtitles (pelo translate menu)

- Off; AI off; explicit (disabled); menus fecham.

---

### 4.1 Matriz: “Translate with AI” × first play / Smart / rate-limit

Entrada típica = click **Info → Translate** ou long-press → Translate (ambos `OnTranslateSubtitleWithAi`, salvo Info na opção `AI` = toggle B3).

| # | Estado ao clicar Translate | Runtime | Menu (reabrir) | Rate-limit total depois |
|---|----------------------------|---------|----------------|-------------------------|
| T1 | First play, Smart **off**, clássico no secundário (ex. EN), keys ok | MANUAL naquela fonte; lock on; `explicit` inalterado | Preferido + `AI`; diagnostics MANUAL locked | Sem S6; fica MANUAL + erro |
| T2 | First play, Smart **on**, já em `AI_EMBEDDED` | Info Translate na opção clássica: **disabled** (B6). Long-press noutra fonte (embedded/addon): MANUAL nessa fonte (substitui smart) | Preferido + `AI`; MANUAL locked | Sem S6 |
| T3 | First play, Smart **on**, rung sem AI (`PREFERRED_*` / classic) | Translate na fonte focada → MANUAL | Idem | Sem S6 |
| T4 | First play Smart AI-on **sem** Translate; 429 em todas as keys | (sem click) S6: AI off → preferred/classic | `AI` não selected; reason rate-limited | — |
| T5 | Como T1/T2 mas keys já em cooldown | Liga MANUAL na mesma; batches 429; retry | MANUAL + erro rate limit | Sem S6 |
| T6 | Opção sintética `AI` no Info | **Toggle** Stop/Start (não B4a) | — | Stop limpa lock; se Smart ainda elegível, próxima policy pode recolocar ladder |
| T7 | MPV / sem key / feature off | No-op / disabled | Sem `AI` | — |
| T8 | Forced on, áudio ≠ preferido | Ladder pode ter corrido smart (§3 / arch); Translate manual **ainda** disponível (G5) | MANUAL se traduziu | Sem S6 se MANUAL |

**First play sem Translate (só Smart)** — não é B4, mas cruzamento frequente:

1. Policy → ladder (§3.1.1).  
2. Se AI-on e tradução ok → menu preferido+`AI`, locked **off**.  
3. Se AI-on e rate-limit total → **S6** (§3.5), não MANUAL.

### B5 — Long-press na opção `AI` → Diagnostics

**Pré:** Opção `AI` focada.  
**Ação:** Long-press.  
**Resultado:** Painel diagnostics (não o translate menu):

- Selection step (rung label)
- Reason, Source, Match score (se houver), Target, Model, Manual selection (locked on/off)

Dismiss → volta ao overlay.

---

### B6 — Painel Info (rail)

**Pré:** Foco na Col2 ou Col3 (Col3 some com foco na Col1). O Info descreve a opção **focada** (read-only, sem CTA se não for a selecionada) ou a **selecionada** (com CTA quando aplicável).

**Card Info mostra:** título/source da opção (ou badge AI), status (erro / Translating… / meta), score, e diagnostics (`rung`, `reason`, `source`, `target`, `model`, `locked`, método Automatic vs User selected).

#### Matriz de CTAs (só opção selecionada)

| Contexto | CTA | Notas |
|----------|-----|-------|
| AI automática (`AI_EMBEDDED`, sem lock) | **Nenhum** | Só diagnostics |
| Classic automático (`CLASSIC_FALLBACK`, sem explicit) | **Nenhum** | Aviso de sistema se Smart não pôde usar IA (§B6d) |
| Embedded no preferido (`PREFERRED_EMBEDDED`) ou embedded/addon qualquer (explícito) | **Traduzir com IA** | Embedded ou addon |
| AI manual (`MANUAL` / `userLocked`) + Smart AI **pode** recolocar embutida | **Voltar à seleção automática** | Reset Smart (não é Stop) |
| AI manual + Smart AI **não** tem degrau embutido (só classic) | **Voltar à seleção clássica** (enabled) | Limpa lock; não prometer IA automática. Aviso §B6d |
| AI indisponível (sem key / rate-limit / MPV) | Translate **disabled** e não focável | Col3 com aviso de sistema (§B6d) |
| Opção sintética `AI` focada, sem embutida usável | Opção **disabled** / não selecionável | Mesmo aviso que “sem embutidas” |

**G1:** clicar numa opção já selecionada não faz nada. **G2:** trocar/desativar só via outra opção Col2 ou None/Off; Reset Smart muda o modo sem ser toggle.

#### B6a — Translate with AI (Info)

`PlayerEvent.OnTranslateSubtitleWithAi` → `userLocked=true`, AI on, diagnostics MANUAL. Na **mesma sessão**, foco → preferido + opção AI.

#### B6b — Voltar à seleção automática (Reset Smart)

`PlayerEvent.OnResetToSmartAuto`: limpa `userLocked` + `explicit`, re-executa `applySubtitleAutoSelectPolicy` / ladder. Resultados típicos:

| Rung após reset | Seleção | Indicadores Fonte IA |
|-----------------|---------|----------------------|
| `AI_EMBEDDED` | Opção AI continua | Seguem a fonte da ladder |
| `PREFERRED_EMBEDDED` | Embedded preferido; AI off | Somem |
| `CLASSIC_FALLBACK` | Escolha clássica do Nuvio; AI off | Somem |

Foco pós-reset: Col2 da opção que ficou selecionada.

**Rótulo do CTA:** só «Voltar à seleção automática» quando existir embutida de texto usável (reset pode cair em `PREFERRED_EMBEDDED` / `AI_EMBEDDED`). Caso contrário → «Voltar à seleção clássica» + aviso §B6d (o reset continua a limpar o lock).

#### B6c — Disable subtitles

Somente via **None/Off** na Col1 (não há CTA Disable no Info).

#### B6d — Smart auto indisponível (avisos de sistema)

Mostrar **uma frase** no Info (tom de sistema, sem jargão de rung). EN = string base; PT-BR/PT-PT alinhados.

| ID | Condição | CTA Reset (se MANUAL) | CTA Translate | Opção `AI` Col2 | Aviso (PT) | Aviso (EN) |
|----|----------|----------------------|---------------|-----------------|------------|------------|
| S1 | Sem tracks embutidas (scan feito) | **Voltar à seleção clássica** | Enabled em addon/texto | Disabled | Não há legendas embutidas. Selecione um addon para traduzir. | No embedded subtitles. Select an addon to translate. |
| S2 | Só embutidas bitmap (PGS / DVB / VOBSUB) | **Voltar à seleção clássica** | Enabled em addon; disabled na bitmap | Disabled | Não é possível traduzir automaticamente legendas embutidas em imagem (PGS). | Embedded image subtitles (PGS) cannot be translated automatically. |
| S3 | Só forced / songs-and-signs (sem texto usável) | **Voltar à seleção clássica** | Enabled noutro texto/addon | Disabled | Não há legendas embutidas traduzíveis. Selecione um addon para traduzir. | No translatable embedded subtitles. Select an addon to translate. |
| S4 | Smart AI off nas Definições | Disabled + aviso | Enabled se key/Exo | Pode existir; toggle = MANUAL | A seleção automática com IA está desativada nas Definições. | Automatic AI selection is turned off in Settings. |
| S5 | Sem key / feature AI off | — | Disabled | Oculta | Adicione uma chave de API de IA nas Definições de reprodução. | Add an AI API key in Playback settings. |
| S6 | MPV | — | Disabled | Oculta / banner | A tradução com IA está disponível apenas no ExoPlayer. | AI translation is available only with ExoPlayer. |
| S7 | Quota esgotada (todas as keys em cooldown) | Enabled (limpa lock; sem retomar IA até haver quota) | Disabled / oculto | Não selected | Limite da IA atingido. Aguarde e tente de novo. | AI rate limit reached. Wait and try again. |
| S8 | Idioma preferido = none / vazio | Disabled + aviso | Enabled em fonte explícita | Disabled se Smart on | Defina um idioma preferido de legendas para usar a seleção automática. | Set a preferred subtitle language to use automatic selection. |
| S9 | Forced aplica (áudio ≈ preferido) | **Voltar à seleção clássica** se MANUAL | Enabled | Conforme ladder classic | O modo de legendas forçadas está ativo para este áudio. | Forced subtitles mode is active for this audio. |
| S10 | Tracks ainda não digitalizadas | Sem aviso “permanente” | — | — | A carregar legendas… | Loading subtitles… |

**Regras:**

1. Um aviso de cada vez (prioridade: S6 → S5 → S7 → S10 → S4 → S8 → S9 → S2 → S3 → S1).
2. Em `CLASSIC_FALLBACK` automático (sem explicit), **sem** CTA Translate na opção auto-escolhida; o aviso S* explica porque a IA automática não correu.
3. Traduzir com IA num **addon** continua o escape hatch sempre que Translate estiver enabled (S1–S3, S8, S9).
4. Não usar “ladder”, “rung”, “CLASSIC_FALLBACK” nem “embedded-only” no texto visível.
5. Avisos S1–S4 / S8–S9 **só** no Info quando Smart está on **ou** o painel já está em contexto AI/classic-auto/MANUAL (não pintar «Smart off» em toda a lista clássica quando `aiAutoSelect` está desligado por defeito). S5–S7 (e LOADING) mostram sempre.

---

### B7 — Feedback de mudança (banner no menu)

Avisar com **uma frase** (tom de sistema) quando uma ação **salta o foco/seleção** no overlay, ou quando o reset explicita seleção clássica. Não inventar toasts para cada click clássico.

#### Canais

| Canal | Uso |
|-------|-----|
| Banner transitório no menu (topo do overlay, ~2–3 s, 1 linha) | Ações com salto de foco com menu aberto |
| Aviso estável no Info (Col3) | B6d / quota / sem embutida (estado que permanece) |
| Chip «Traduzindo…» no player | Já existe — manter; **não** duplicar no banner |
| Toast global | Só se a mudança automática relevante acontecer com menu **fechado** (P2 — fora deste corte) |

**Nunca** banner + toast no mesmo evento.

#### Matriz (menu aberto)

| ID | Trigger | Banner (PT) | Banner (EN) | Notas |
|----|---------|-------------|-------------|-------|
| F1 | Info → Traduzir com IA (antes/durante o salto para preferido + opção IA) | A traduzir a partir de %1$s. | Translating from %1$s. | %1$s = label curto da fonte (ex. «Inglês · AIOStreams» ou nome/idioma da embutida). Avisa mesmo se já estiver no preferido. |
| F2 | Voltar à seleção automática (reset Smart) | Seleção automática: %1$s. | Automatic selection: %1$s. | %1$s conforme rung pós-reset: «IA a partir de embutida» / «Embutida preferida» / «Seleção clássica». |
| F2b | Voltar à seleção clássica (mesmo evento, label CTA B6d) | Seleção clássica. | Classic selection. | Quando reset não pode recolocar Smart AI. |

#### Regras do banner

1. Mostrar só se a ação mudar língua focada e/ou opção selecionada — **exceto** Translate/Reset, que avisam sempre (Translate mesmo já no preferido).
2. Timeout ~2,5–3 s; dismiss ao mudar foco de rail (após settle pós-CTA) ou fechar overlay.
3. Não bloquear DPAD; acima das rails, abaixo de diálogos.
4. Debounce: não re-disparar o mesmo texto se o estado for idêntico.
5. **Não** avisar (v1): click clássico noutra track/addon; Smart → preferred embedded sem AI; first-play Smart AI-on com menu fechado (P2).

#### Fora deste corte

- P2 toast com menu fechado (first AI-on / S6).
- P3 upgrade AI→preferred com menu aberto (TODO).

Âncora: `SubtitleSelectionOverlay` post-CTA Translate/Reset (`post_cta_focus`); helpers em `SubtitleMenuChangeFeedback.kt`.

---

### B8 — Fechar overlay e reabrir (com AI ainda active)

**Pré:** Tradução active (ladder ou MANUAL).  
**Ação:** Fechar overlay → abrir de novo.  
**Resultado:** §2 — **sempre** alinha para idioma preferido + opção `AI` selecionada (se preferido estiver na lista).

---

## 5. Fluxos de estado / bloqueio (C)

### C1 — Sem key / feature off

- Opção `AI` **não** listada (`aiSubtitleAvailable=false`).
- Long-press clássico: Translate **disabled**.
- Info Translate disabled.
- Playback: só clássico.

### C2 — MPV

- Banner no overlay.
- Toggles/translates no-op.
- Sem opção AI útil.

### C3 — Rate limit / erro de batch

- Chip / Info: mensagem amigável de rate limit (ou bruta).
- Cues: original até sucesso; router tenta outras keys/providers (M5).
- Menu continua navegável.
- **Smart AI-on (locked off) + todas as keys em cooldown → S6** (§3.5): AI off + seleção legada.
- **MANUAL / userLocked → sem S6**: mantém fonte; só erro + cooldown/retry (B4a / §4.1 T1–T5).

### C4 — Conteúdo bloqueado pelo provider

- Sem toast genérico de erro (tratado no service); pode parecer “não traduziu”.

### C5 — Fonte sem texto (PGS etc.) após AI on

- Sem lock: tenta outra **embedded** traduzível; se falhar, desliga AI e, se smart permitido, re-roda ladder (→ classic). **Não** pivota para addon por score.
- Com MANUAL lock: `onUntranslatableSource` pode `selectAiTranslationSourceIfAvailable(excludeCurrent)` — mantém addon atual se locked; senão só outra embedded (sem hunt scored addon).
- Menu/diagnostics atualizam conforme nova escolha.

### C6 — Forced subtitles mode (settings clássico)

- Ladder: só desvia para classic forced se forced **aplica** (áudio ≈ idioma preferido). Senão continua smart (fix 2026-09-23).
- Menu ainda oferece Translate manual se AI available (B4/B6) — forced **não** bloqueia Translate.

### C7 — Nova stream / release player

- Lock AI, diagnostics, translating, score cache limpos.
- Próxima stream = “primeiro acesso” de novo (§3.1), salvo restore de preferência.

### C8 — Troca de episódio (mesma sessão player)

- `explicit` reset (exceto keep-disabled).
- Addons refetch → policy de novo (smart pode recolocar AI).
- Lock AI da stream anterior **não** deve sobreviver a `releasePlayer`; em refresh de episódio ver lifecycle — treat as novo first-access de legendas se flush limpar lock.

---

## 6. Matriz resumida: “o que está selecionado no menu?”

| Playback real | AI active? | Overlay **reaberto** | Overlay **mesma sessão** após Translate |
|---------------|------------|----------------------|----------------------------------------|
| Embedded preferido | Não | Preferido + embedded | — |
| Embedded EN + tradução (smart) | Sim | Preferido + **`AI`** | Continua no rail EN / opção EN até o usuário mover ou reabrir |
| Addon/track via Translate MANUAL | Sim | Preferido + **`AI`** | Sticky na opção long-pressada |
| Addon preferido sem AI | Não | Preferido + esse addon | — |
| Off | Não | Off | — |

---

## 7. Critérios de aceite reproduzíveis (smoke UX)

Use idioma preferido **pt** (ou outro ≠ EN), stream com embedded EN e addons.

1. **Smart on, first access, só EN embedded** → cues em pt; reabrir menu → pt + `AI` selecionada; diagnostics rung AI_EMBEDDED; locked off.  
2. **Smart off, same** → clássico (pode ficar EN ou sem legenda conforme upstream); menu mostra `AI` mas não selecionada; click `AI` → passa a traduzir + locked.  
2b. **Smart on, sem embutida + addons presentes** → classic (AI off); smart **não** auto-traduz addon; long-press addon EN → Translate → `AI` + MANUAL.  
3. **Escolher addon pt manualmente** → sem tradução; smart não troca depois; long-press outro addon EN → Translate → cues pt; reabrir → `AI` selecionada; diagnostics MANUAL + locked on.  
4. **Info → Stop AI** após (3) → volta texto EN da mesma fonte; locked off; `AI` não selected no reopen.  
5. **Info → Disable** → Off; reopen em Off.  
6. **Mesma sessão:** long-press Translate **sem** fechar overlay → confirmar que o highlight **não** salta sozinho para `AI` (comportamento atual); após fechar/abrir, salta.  
7. **MPV** → banner; Translate disabled/no-op.  
8. **Sem key** → sem opção `AI`; Translate disabled.  
9. **Sem quota (Translate ou Smart)** → aviso; **mesma** fonte selected; Translate oculto; **não** saltar para outra língua.  
10. **First play Smart off → clássico EN → Translate ok** → ao reabrir: preferido + AI.  
11. **Quota recupera** → Translate pode voltar; Smart pode retomar se elegível.

---

## 8. Gaps / ambiguidades de produto (para decidir depois)

| ID | Tema | Nota |
|----|------|------|
| G1 | Sticky session vs redirect | Translate não realinha o highlight até reabrir — pode confundir; candidato a fix (forçar preferido + `AI` na mesma sessão) |
| G2 | Sem “Reset to smart” | Depois de explicit, usuário não tem botão claro para devolver o controle à ladder |
| G3 | Toggle `AI` off deixa fonte estrangeira | Esperado tecnicamente; UX pode querer “voltar ao preferred embedded” |
| G4 | `explicit` não limpo no Translate MANUAL | Policy futura fica bloqueada no check explicit mesmo com lock AI |
| G5 | Forced + Translate manual | Forced não bloqueia Translate; ladder só short-circuit se forced aplica (áudio≈preferido) |
| G6 | Rate-limit total | §3.5 — AI off + **preserve selection** + ocultar Translate; sem saltar para French |
| G7 | Policy + userLocked | **Mitigado:** com lock, policy não chama `selectAiTranslationSource` se já há fonte selecionada |
| G8 | PGS sob MANUAL | `onUntranslatableSource` pode abandonar a fonte pedida (C5) |
| G9 | P3 banner AI→preferred (menu aberto) | Upgrade automático Smart com overlay aberto ainda sem feedback F* (fora do corte B7 v1) |

---

## 9. Fora deste documento

Settings Playback → AI subtitles (seção A), implementação HTTP/providers, scoring interno (exceto o que aparece como badge/diagnostics), seek bar do fork.

---

## 10. Auditoria vs código — 2026-09-22 *(apêndice; não carregar no Agent por padrão)*

Método: revisão estática na linha `release/1.0.2` / fixes em `fix/ai-ladder-and-rate-limit`. Não há suíte UI automatizada destes fluxos. Não foi executado smoke em device.

### Smoke §7

| # | Resultado |
|---|-----------|
| 1–8 | **PASS** (todos) |

### Claims §2–6

| Resultado | Qtd |
|-----------|-----|
| PASS | 14 |
| PARTIAL / FAIL | 1 |

### Violação real (não era gap documentado)

| ID | Requisito no doc | Código |
|----|------------------|--------|
| **V1** | §3.3 / C8: keep-disabled mantém Off e **não** reabre AI sozinho após refresh de episódio | `refreshSubtitlesForCurrentEpisode` zera `explicit` e seta `autoSubtitleSelected=keepDisabled`. Clássico (`tryAutoSelect…`) respeita `autoSubtitleSelected`; **`applyAiAutoSelectLadder` não**. Com Smart on, a policy chama a ladder e pode **religar legenda/AI** mesmo com Off persistido. |
| **V2** | Smart on + embutida de texto (ex. FR) não deve cair no 1º addon do idioma secundário | Corrida: scan vazio / addons antes da embutida → classic `firstOrNull` EN. Menu abre no preferido sem highlight da fonte real. |

### Correções (branch `fix/ai-ladder-and-rate-limit`)

| ID | Fix |
|----|-----|
| V1 | Ladder retorna cedo se `autoSubtitleSelected` e nada selecionado (keep-disabled). |
| V2 | Defer se `subtitleTracks` vazio; seleciona embutida AI **antes** de ligar o manager; fallback Smart usa pivot com score relaxado + classic **primary-only** (nunca secundário `firstOrNull`). |
| Rate limit UX | Mensagens amigáveis; normaliza 429; delay de retry alinhado ao cooldown; fallback multi-key/provider no meio do batch (com log `fallback ok`). |
