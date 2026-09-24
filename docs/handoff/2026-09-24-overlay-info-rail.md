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
3. **Relatório final**, com uma linha por ID do DoD: `status (feito / parcial / não feito)` + `arquivo:função` + `evidência` (teste, screenshot do dispositivo ou log). **É proibido marcar como feito sem evidência.** Um item parcial ou não feito precisa do motivo.
4. Listar os gaps e decisões tomadas por conta própria. Se algo for ambíguo, parar e reportar em vez de assumir.
5. Não commitar. O monitor revisa e commita.

## Política de build (economia de tempo)
**Não** rodar `assemble` + install a cada fatia.

| Fatia | Validação esperada |
|-------|-------------------|
| **A** | Já em andamento: compile Kotlin + unit tests; install/screenshots nesta fatia se forem necessários para N/V. Não repetir `assembleFullRelease` se já houver APK fresco. |
| **B** | **Só** `:app:compile*Kotlin` (se precisar) + **unit tests** da função pura de Info/CTAs. **Sem** assemble/install/screenshots no dispositivo — evidência = testes. Screenshots de I*/C*/K1 ficam para a Fatia C. |
| **C** | **Um** `assemble` + install + smoke/screenshots cobrindo regressão de A+B e os IDs de C (T/F/A/S/P). Estender a skill de smoke aqui. |

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
| L1 | Quando a Col3 não é renderizada (N1), o **espaço dela fica reservado**: a Col2 não muda de largura nem de posição |
| L2 | A Col3 **não tem cabeçalho** (sem título "Info") |

## Requisitos: estados visuais (padrão do app)
Hoje há vários elementos em roxo cheio ao mesmo tempo e não dá para saber onde está o foco. Regra única para as três colunas:

| Papel | Visual |
|-------|--------|
| Foco do DPAD | Roxo 100%. **Só um elemento por vez** na tela inteira |
| Navegação / selecionado sem foco | Roxo ~50% (`Secondary.copy(alpha ≈ 0.5f)`, seguindo o padrão já usado no app) |
| Demais | Neutro, sem fundo roxo |

| ID | Coluna | Regra |
|----|--------|-------|
| V1 | Col1 | Linha focada → 100%. Idioma cuja lista está aberta na Col2, com o foco já na Col2/Col3 → ~50%. Outros → neutro. **Sem ✓** e sem marcar o idioma da legenda em playback (a única exceção é o ponto amarelo da fonte AI, Fatia C) |
| V2 | Col2 | O ✓ existe **só aqui**, na opção ativa no playback. Focada → 100% (com ✓ se também estiver selecionada). Selecionada sem foco → ~50% + ✓. Demais → neutro |
| V3 | Col3 | CTA focado → 100%. CTA visível sem foco → ~50%. Nunca roxo cheio sem foco |

## Nesta fatia
Os CTAs podem continuar com a lógica atual de quais aparecem. A matriz completa é da Fatia B. O objetivo aqui é a navegação, a estrutura e os estados visuais. O nome das tracks embutidas continua como está hoje (ex.: "en"); não alterar.

## DoD
- [ ] R1–R4 removidos, sem código morto sobrando
- [ ] N1–N8, L1–L2 validados no dispositivo, com screenshot por item
- [ ] V1–V3: screenshots com o foco em cada coluna, mostrando só um elemento em roxo 100%
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

### Campos mínimos por tipo
| ID | Tipo | Campos |
|----|------|--------|
| I5 | Addon | Nome do addon, nome completo do arquivo, idioma, formato (SRT/VTT/ASS), score % (se houver) |
| I6 | Embedded | Idioma, nome da track, formato (texto ou bitmap/PGS, indicando que bitmap não é traduzível), forced/SDH |
| I7 | AI | Fonte, método (automático ou manual/User selected), rung/motivo, status, target, model |

### Contadores da Col1
| ID | Requisito |
|----|-----------|
| K1 | O contador do idioma preferido **inclui** a opção AI sintética quando ela está listada |

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
- [ ] Função pura + testes unitários, um caso por ID (I1–I7, C1–C6) — **esta é a evidência principal da Fatia B**
- [ ] K1 coberto por teste (ou deixa screenshot para Fatia C; declarar qual)
- [ ] **Sem** assemble/install nesta fatia (ver política de build na Base)
- [ ] Screenshots de I1–I4 / C1,C4,C5,C6 **adiados** para Fatia C
- [ ] N4–N6 da Fatia A: não revalidar no device aqui; só garantir que o código não regride a lógica (revisão do diff)

---

# FATIA C — Translate, reset Smart, persistência e docs

## Requisitos: Translate (addon **ou** embedded)
| ID | Requisito |
|----|-----------|
| T1 | O CTA "Traduzir com IA" ativa a tradução daquela fonte (reutilizando `OnTranslateSubtitleWithAi` ou equivalente): `userLocked=true`, AI on, diagnostics MANUAL |
| T2 | Os indicadores de fonte AI (F1–F4) passam a apontar para esta fonte |
| T3 | **Na mesma sessão do overlay**, o foco vai para Col1 = preferido → Col2 = AI, já selecionada. Isso muda o §2 atual do UI menu (que só alinha no reopen) |
| T4 | O Info da AI mostra **User selected / MANUAL** + nome da fonte + diagnostics |

## Requisitos: indicadores da fonte AI
Valem **sempre que a tradução AI estiver ativa**, seja pela ladder (automático) ou por escolha do usuário (manual).

| ID | Requisito |
|----|-----------|
| F1 | Col1: **ponto amarelo pequeno** no idioma da fonte (embedded ou addon). Sem ✓ |
| F2 | Col2: chip amarelo **"Fonte IA"** na opção que é a fonte atual, como **segundo chip ao lado** do chip de origem (ex.: `AIOStreams` `Fonte IA`, `Built in` `Fonte IA`) |
| F3 | Ponto e chip acompanham a fonte: se o usuário ou a policy mudar a fonte da AI, eles mudam **na hora** para o novo idioma/opção |
| F4 | Ponto e chip **somem** quando a AI desliga: None/Off, reset Smart que termina sem tradução (`PREFERRED_EMBEDDED` ou classic), ou seleção de uma legenda sem AI |

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
| S5 | Os indicadores F1–F2 seguem o resultado do reset: apontam para a fonte da ladder (`AI_EMBEDDED`) ou somem (F4) |
| S6 | Foco após o reset: vai para a opção que ficou selecionada, na Col2 do idioma dela |

## Requisitos: persistência
| ID | Requisito |
|----|-----------|
| P1 | Enquanto a tradução manual estiver ativa, o reopen mostra Col1 = preferido → Col2 = AI selecionada → Info MANUAL |
| P2 | Enquanto a AI estiver ativa (automática ou manual), o reopen mantém o ponto amarelo (F1) e o chip "Fonte IA" (F2) na fonte atual |
| P3 | Os indicadores só mudam ou somem conforme F3/F4 |

## Docs
| ID | Requisito |
|----|-----------|
| D1 | Atualizar `docs/prd-ai-subtitles-ui-menu.md`: §1 (modelo mental, sem Style, sem long-press, tabela de overlays, estados visuais, indicadores de fonte AI), §2 (salto de foco imediato), §B6 (Info por foco/seleção, matriz de CTAs, G1/G2) e fechar o gap "Reset to smart auto" |
| D2 | Em `docs/architecture-ai-subtitles.md`, 1 frase sobre o reset Smart nos locks |

## DoD
- [ ] T1–T4 validados com addon **e** com embedded (screenshots)
- [ ] F1–F4 validados com AI automática (ladder) **e** manual, incluindo troca de fonte e desligamento (screenshots)
- [ ] A1–A3 validados (A1: log mostrando que não houve re-translate)
- [ ] S1–S6 validados por rung. Se um rung for difícil de reproduzir no device, cobrir com teste unitário da policy e declarar isso
- [ ] P1–P3: screenshot de fechar e reabrir o overlay
- [ ] Screenshots adiados da Fatia B (I1–I4, C1/C4/C5/C6, K1) capturados neste smoke
- [ ] **Um** assemble + install (único da sequência A→B→C)
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
