# Spec — Overlay de legendas: painel Info dinâmico (3ª coluna)

> **Doc temporário de execução.** Substitui [`2026-09-23-subtitle-menu-info-ladder.md`](./2026-09-23-subtitle-menu-info-ladder.md). Pode ser removido depois que as 3 fatias forem integradas e o PRD de UI menu estiver atualizado (D1/D2).
>
> **Uso:** cada executor lê a **Base** + **a sua fatia**. Ordem obrigatória: A → B → C. Branch: `feat/subtitle-overlay-info-rail`.

---

# BASE

## Papel
Você é um agent no repositório **NuvioTV** (Android TV / Compose). Implemente **uma fatia** de uma atualização de comportamento do overlay de seleção de legendas. Faça um patch focado e alinhado aos docs canônicos.

## Contexto obrigatório (ler nesta ordem)
1. `docs/README.md`
2. `docs/prd-ai-subtitles-ui-menu.md`
3. `docs/architecture-ai-subtitles.md`
4. Âncoras: `SubtitleSelectionOverlay.kt`, `PlayerRuntimeControllerAiSubtitles.kt`, `PlayerRuntimeControllerPlaybackEvents.kt` e os handlers de Translate / Info existentes.

## Notação e termos
```text
Col1 (Idiomas + None/Off) → Col2 (embedded | addon | AI sintética) → Col3 (Info, painel único)
```
- **Focada:** a opção tem o foco do DPAD, sem confirmação.
- **Selecionada:** a opção está ativa no playback.
- **CTA:** botão acionável na Col3.
- **Automático:** o estado veio da ladder ou do classic, sem ação do usuário.
- **Manual:** o estado veio de uma ação do usuário (click na AI, Translate no Info).
- Os exemplos usam PT-BR como idioma preferido. A regra vale para qualquer idioma preferido.

## Regras globais (valem para todas as fatias)
- **G1.** Clicar numa opção já selecionada **não faz nada**: não desativa, não reinicia e não troca a fonte.
- **G2.** Trocar ou desativar só acontece ao selecionar **outra** opção na Col2 ou **None/Off** na Col1. "Voltar à seleção automática" muda o modo (reset Smart), mas não é um toggle.
- **G3.** Desligar legendas é **somente** via None/Off na Col1.
- **G4.** Smart continua **só embedded**. Translate with AI aceita embedded **ou** addon. Não alterar a lógica da ladder, providers, scoring, rate-limit HTTP nem settings.
- **G5.** Strings novas seguem o padrão de i18n do app.

## Processo obrigatório
1. **Antes de editar:** produzir uma tabela `ID → arquivo/função a alterar → abordagem` cobrindo **todos** os IDs da fatia. Um ID sem linha na tabela é falha.
2. Implementar.
3. **Relatório final**, com uma linha por ID do DoD: `status (feito / parcial / não feito)` + `arquivo:função` + `evidência` (teste, screenshot do emulador ou log). **É proibido marcar como feito sem evidência.** Um item parcial ou não feito precisa do motivo.
4. Listar os gaps e decisões tomadas por conta própria. Se algo for ambíguo, parar e reportar em vez de assumir.
5. Não commitar. O monitor revisa e commita.

---

# FATIA A — remoções, navegação e estilo do CTA

## Requisitos
| ID | Requisito |
|----|-----------|
| R1 | Remover a navegação superior **Info \| Estilos**. Fica só o Info |
| R2 | Remover a UI de estilos de legenda (delay/estilo) do overlay |
| R3 | Remover os CTAs **Disable subtitles** e **Stop AI translation** do Info |
| R4 | Remover o **long-press** nas opções (menu Translate e overlay de Diagnostics). Os diagnostics passam a aparecer no Info |
| N1 | Foco na Col1 → a Col3 **não é renderizada** |
| N2 | Col1 = None/Off → **não existem** Col2 nem Col3 |
| N3 | Trocar de idioma na Col1 só navega. **Não altera** a seleção nem o playback |
| N4 | Foco na Col2 com opção **não selecionada** → Col3 read-only, **sem CTA**, e Right **não faz nada** |
| N5 | Foco na Col2 com opção **selecionada** e **pelo menos um CTA habilitado** → Right foca o **primeiro CTA habilitado** |
| N6 | Opção selecionada **sem CTA habilitado** (ex.: AI automática) → Right **não faz nada** |
| N7 | Com foco no CTA, a Col2 continua destacando a opção selecionada |
| N8 | Left/Back volta uma coluna por vez. Ao chegar na Col1, a Col3 some na hora. A seleção não muda |
| V1 | O CTA **não pode ficar roxo** (nem com outro destaque) sem foco. Sem foco, usar estilo neutro (hoje ele parece selecionado com o foco em outro lugar) |

## Nesta fatia
Os CTAs podem continuar com a lógica atual de quais aparecem. A matriz completa é da Fatia B. O objetivo aqui é a navegação e a estrutura.

## DoD
- [ ] R1–R4 removidos, sem código morto sobrando
- [ ] N1–N8 validados no emulador, com screenshot por item
- [ ] V1: screenshot com o CTA sem foco (neutro) e com foco (destacado)
- [ ] Build ok + testes existentes passando

---

# FATIA B — conteúdo do Info e matriz de CTAs

## Requisitos: conteúdo
O Info descreve **a opção da Col2** (focada ou selecionada): nome completo, tipo, idioma e os diagnostics aplicáveis (`rung`, `reason`/método, status, `source`, `score`, `target`, `model`, `locked`).

| ID | Caso | O Info mostra |
|----|------|---------------|
| I1 | Preferido → **AI** | Fonte da tradução (embedded/addon), método/motivo, status, target, model |
| I2 | Idioma X → embedded X | Infos da track embutida |
| I3 | Idioma X → addon X | Infos do addon (nome completo etc.) |
| I4 | AI ativa + foco sem seleção em outra opção | Infos da **focada**, read-only e sem CTA. O playback continua na AI |

## Requisitos: CTAs (só com opção selecionada)
| ID | Opção selecionada | Origem | CTA |
|----|-------------------|--------|-----|
| C1 | AI | Automático (`AI_EMBEDDED`) | **Nenhum**. O Info mostra os diagnostics |
| C2 | Legenda escolhida pelo Nuvio | Automático, fallback classic | **Nenhum**. O Info explica o motivo do fallback nos diagnostics |
| C3 | Embedded no preferido | Automático (`PREFERRED_EMBEDDED`) | "Traduzir com IA" (é embedded selecionado) |
| C4 | AI | Manual | Único: **"Voltar à seleção automática"** |
| C5 | Embedded ou addon | Qualquer | Único: **"Traduzir com IA"** |
| C6 | Qualquer, com AI indisponível (sem key / rate-limit / MPV) | — | "Traduzir com IA" **disabled** e **não focável**. A Col3 explica o motivo |

## Implementação exigida
Extrair a decisão para uma **função pura**, por exemplo `(opção, focada|selecionada, origem/rung, aiDisponível) → (conteúdoInfo, ctas)`, com **testes unitários cobrindo I1–I4 e C1–C6**.

## DoD
- [ ] Função pura + testes unitários, um caso por ID
- [ ] Screenshot no emulador de I1, I2, I3, I4, C1, C4, C5 e C6
- [ ] N4–N6 da Fatia A continuam válidos (sem regressão)

---

# FATIA C — Translate, reset Smart, persistência e docs

## Requisitos: Translate (addon **ou** embedded)
| ID | Requisito |
|----|-----------|
| T1 | O CTA "Traduzir com IA" ativa a tradução daquela fonte (reutilizando `OnTranslateSubtitleWithAi` ou equivalente): `userLocked=true`, AI on, diagnostics MANUAL |
| T2 | Na Col2 da fonte, mostrar o label **"Fonte IA"** abaixo do nome do addon ou da track embutida |
| T3 | **Na mesma sessão do overlay**, o foco vai para Col1 = preferido → Col2 = AI, já selecionada. Isso muda o §2 atual do UI menu (que só alinha no reopen) |
| T4 | O Info da AI mostra **User selected / MANUAL** + nome da fonte + diagnostics |

## Requisitos: click na opção AI
| ID | Requisito |
|----|-----------|
| A1 | A AI já está selecionada → o click **não faz nada** (G1): não troca a fonte nem reinicia o Translate |
| A2 | A AI não está selecionada e existe fonte manual ativa → mantém essa fonte |
| A3 | A AI não está selecionada e não existe fonte manual → comportamento atual (o runtime escolhe a fonte), estado **MANUAL**, CTA "Voltar à seleção automática" |

## Requisitos: "Voltar à seleção automática"
| ID | Requisito |
|----|-----------|
| S1 | **Reset Smart**: limpar `userLocked` e o `explicit` pertinente, e re-executar `applySubtitleAutoSelectPolicy` / ladder. Não é só um Stop |
| S2 | Rung `AI_EMBEDDED` → a AI **continua selecionada**. O Info mostra a track escolhida pela ladder, sem CTA |
| S3 | Rung `PREFERRED_EMBEDDED` → a seleção passa para a **track embedded no idioma preferido** (tradução off) |
| S4 | Fallback classic → **o Nuvio escolhe** a legenda. O Info explica o motivo |
| S5 | O badge "Fonte IA" da fonte manual anterior é removido |
| S6 | Foco após o reset: vai para a opção que ficou selecionada, na Col2 do idioma dela |

## Requisitos: persistência
| ID | Requisito |
|----|-----------|
| P1 | Enquanto a tradução manual estiver ativa, o reopen mostra Col1 = preferido → Col2 = AI selecionada → Info MANUAL |
| P2 | O badge "Fonte IA" continua na fonte (idioma dela → addon/embedded) no reopen |
| P3 | O badge só some quando a fonte muda: outra opção na Col2, reset Smart, None/Off ou AI desligada |

## Docs
| ID | Requisito |
|----|-----------|
| D1 | Atualizar `docs/prd-ai-subtitles-ui-menu.md`: §1 (modelo mental, sem Style, sem long-press, tabela de overlays), §2 (salto de foco imediato), §B6 (Info por foco/seleção, matriz de CTAs, G1/G2) e fechar o gap "Reset to smart auto" |
| D2 | Em `docs/architecture-ai-subtitles.md`, 1 frase sobre o reset Smart nos locks |

## DoD
- [ ] T1–T4 validados com addon **e** com embedded (screenshots)
- [ ] A1–A3 validados (A1: log mostrando que não houve re-translate)
- [ ] S1–S6 validados por rung. Se um rung for difícil de reproduzir no emulador, cobrir com teste unitário da policy e declarar isso
- [ ] P1–P3: screenshot de fechar e reabrir o overlay
- [ ] Skill `ai-subtitles-smoke` estendida com os passos novos
- [ ] D1–D2 feitos
- [ ] Regressão: Fatias A e B continuam válidas

---

# Log do monitor

| Fatia | Status | Commit | Observações |
|-------|--------|--------|-------------|
| A | pendente | — | — |
| B | pendente | — | — |
| C | pendente | — | — |
