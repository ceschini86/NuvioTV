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

## Política de build e device (economia)
**Não** rodar `assemble` + install + smoke de device a cada fatia. Device/TV fica para o **fim** (Fatia C ou gate do monitor), salvo o monitor pedir o contrário.

| Fatia | Validação esperada |
|-------|-------------------|
| **A** | Compile Kotlin + unit tests relevantes. Evidência de N/V/R/L = **diff + inspeção de código** (e testes se houver). **Sem** install/screencap nesta fatia. |
| **B** | Unit tests da função pura Info/CTAs. **Sem** assemble/install/screenshots. |
| **C** | Implementação + unit tests de policy. **Um** assemble + install + smoke/screenshots só no fechamento (T/F/A/S/P + regressão A/B + I/C/K adiados). |

**Gate entre fatias:** se o DoD de código/testes passar sem gaps graves, o monitor **segue para a próxima fatia** sem esperar TV/emulador. Device só bloqueia se o item for impossível de validar sem runtime e for crítico.

## Práticas anti-bug (a partir da Fatia B)
1. **Regra ≠ UI:** decisões de Info/CTA (e, na C, reset Smart / indicadores F*) vivem em funções puras testáveis. Compose só renderiza o resultado.
2. **Um ID = um teste nomeado:** cada linha I*/C*/K* (e na C: S*/F*/T* testáveis) vira teste com o ID no nome, ex. `c4_manualAi_showsResetSmartCta`. Proibido “coberto por teste genérico” sem mapear o ID.
3. **Parcial = débito no log:** item parcial entra na tabela do monitor; não vira “feito” sem evidência.
4. **Logs estruturados (Fatia C, e B se tocar handlers):** em Translate / reset Smart / select AI, uma linha com `source=`, `reason=`, `locked=`, `rung=` (tag existente do player se houver).
5. **Smoke ID-named só no fechamento (C):** screenshots `.tmp_overlay_<ID>.png`; skill estendida.
6. **Revisão adversária opcional após C:** Bugbot / segundo olhar no diff total vs esta spec (“quais IDs faltam?”).

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
- [ ] R1–R4 removidos, sem código morto sobrando (evidência: diff)
- [ ] N1–N8, L1–L2 implementados; evidência = diff + nota de como o foco/layout foi garantido no código (**sem** screenshot nesta fatia)
- [ ] V1–V3: cores 100% / ~50% / neutro no código; evidência = diff (**sem** screenshot nesta fatia)
- [ ] Compile Kotlin + testes unitários existentes relevantes passando
- [ ] **Sem** assemble/install/screencap nesta fatia

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
- Extrair decisão para **função(ões) pura(s)** (ex. arquivo dedicado tipo `SubtitleInfoRailDecision.kt`): entrada `(opção, focada|selecionada, origem/rung, aiDisponível, …)` → `(conteúdoInfo, ctas)`.
- Overlay **só consome** o resultado (não reimplementar a matriz em `if`s de Compose).
- **Unit tests:** no mínimo **um teste por ID** I1–I7, C1–C6, K1, com o **ID no nome do método**.
- Relatório DoD: evidência = `NomeDoTeste` por linha.

## DoD
- [ ] Função pura + wiring no overlay
- [ ] Testes unitários: I1–I7, C1–C6, K1 (nome do teste contém o ID)
- [ ] K1 coberto por teste (screenshot adiado para C)
- [ ] **Sem** assemble/install/screenshots nesta fatia
- [ ] Screenshots de I1–I4 / C1,C4,C5,C6 **adiados** para Fatia C
- [ ] N4–N6 da Fatia A: não revalidar no device; garantir no diff que `computeInfoCtaState` / decisão pura não regride Right/CTA

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

## Implementação exigida (anti-bug)
- Policy de reset Smart / escolha pós-ladder em funções testáveis quando possível.
- Logs estruturados nos handlers: Translate, click AI, reset Smart (`source`, `reason`, `locked`, `rung`).
- Unit tests nomeados por ID para S2–S4 e F3–F4 (e T* se der isolar).
- Smoke no fechamento: screenshots `.tmp_overlay_<ID>.png` + skill.

## DoD
- [ ] T1–T4 validados com addon **e** com embedded (screenshots no fechamento)
- [ ] F1–F4 validados com AI automática (ladder) **e** manual, incluindo troca de fonte e desligamento (screenshots)
- [ ] A1–A3 validados (A1: log mostrando que não houve re-translate)
- [ ] S1–S6: unit tests por rung onde possível + smoke; se rung difícil no device, declarar
- [ ] P1–P3: screenshot de fechar e reabrir o overlay
- [ ] Screenshots adiados da Fatia B (I1–I4, C1/C4/C5/C6, K1) capturados neste smoke
- [ ] Logs estruturados presentes nos handlers críticos
- [ ] **Um** assemble + install (único da sequência A→B→C)
- [ ] Skill `ai-subtitles-smoke` estendida com passos ID-named
- [ ] D1–D2 feitos
- [ ] Regressão: Fatias A e B continuam válidas
- [ ] (Opcional monitor) Bugbot / revisão “quais IDs faltam no diff?”

---

# Log do monitor

| Fatia | Status | Commit | Observações |
|-------|--------|--------|-------------|
| A | aprovada (código) | (ver git: Simplify subtitle overlay…) | R4 parcial: flags/events de diagnostics/long-press sem UI. Device adiado. |
| B | em andamento | — | Sem device |
| C | pendente | — | Smoke/device no fechamento |
