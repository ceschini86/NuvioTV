# PRD — Legendas com IA (fork NuvioTV)

> **Uso com Agent:** `@` este arquivo para escopo, aceite e gaps de **produto**.  
> **Não usar para:** detalhes de classes, HTTP, ou fluxos pixel do overlay.  
> **Âncoras:** ver [`architecture-ai-subtitles.md`](./architecture-ai-subtitles.md) §9.  
> **Índice:** [`README.md`](./README.md).

| Campo | Valor |
|-------|--------|
| Status | Shipada em `release/1.0.2`; **presente** neste working tree (`fix/ai-ladder-and-rate-limit`). Pode ainda estar ausente em `dev` até merge |
| Fonte canônica | **Este arquivo** = requisitos de produto |
| UX do menu (overlay) | [`prd-ai-subtitles-ui-menu.md`](./prd-ai-subtitles-ui-menu.md) |
| Fonte técnica | [`architecture-ai-subtitles.md`](./architecture-ai-subtitles.md) (ladder **real** do código = §3) |
| Última revisão | 2026-09-23 (Smart AI = **só embutidas**) |

---

## 1. Problema

Legendas no idioma do usuário costumam faltar, vir com release errado, ou existir só no idioma original. Tradução manual (ou “pegar qualquer addon no idioma certo”) falha quando o release não casa com o stream.

## 2. Solução

**Traduzir legendas já existentes** (embedded ou addon) para o idioma preferido do usuário, via LLM com **chave própria (BYOK)**. Com **Smart AI** ligado, o player escolhe automaticamente a melhor fonte **embutida**; addons só entram na tradução via **Translate with AI** (manual).

> Fora de escopo: gerar legendas a partir do áudio (Whisper/ASR), tradução offline on-device, ou sync de API keys na nuvem.

## 3. Personas / contexto

- Usuário de Android TV / phone com ExoPlayer, addons de legenda, e preferência de idioma já configurada.
- Trazer a própria key (Groq / Gemini / Claude); não há conta Nuvio para IA.

## 4. Requisitos

### Must (já em `release/1.0.2` + branch de fix)

| ID | Requisito | Aceite (resumo) |
|----|-----------|-----------------|
| M1 | Tradução LLM de cues textuais no **ExoPlayer** | Com AI on + key válida, cues viram texto no idioma alvo; indicador “Translating…” durante batches |
| M2 | Engine: **somente ExoPlayer** | Em MPV, AI indisponível (mensagem clara); não quebra seleção clássica |
| M3 | BYOK; keys **só no device** | Keys em prefs locais; não entram no sync de perfil |
| M4 | Providers: Groq, Gemini, Claude | Modelos mapeados no serviço; enum persistido estável |
| M5 | Multi-key + fallback de provider | Keys em ordem; 429 → cooldown e próxima key/provider |
| M6 | Ping / “Test key” | Settings mostra Valid / Invalid / Rate limited |
| M7 | Idioma alvo = preferência de legenda do app | Mudança de idioma preferido altera traduções novas |
| M8 | Respeitar strip SDH | Com strip SDH, tags/HI removidos no pipeline de tradução |
| M9 | Smart ladder (auto-select) | Só embutidas: preferred → AI embedded → classic; implementação canônica em architecture §3 |
| M10 | Score de release-name 0–100 | Badge `%` no overlay (info humana); **não** gate da ladder automática |
| M11 | “Translate with AI” manual | Lock: auto-select não sobrescreve; único path auto-livre para traduzir **addon** |
| M12 | Diagnósticos | Long-press / Details: rung, reason, source, score, target, model, locked |
| M13 | Fonte sem texto extraível (ex.: PGS) | Não ficar no original “silencioso”; tenta outra **embedded** ou desliga AI (+ classic se smart) |
| M14 | Settings Playback → AI subtitles | Enable, smart auto-select, provider preferido, enable por provider, keys |

### Should (parcial / próximo)

| ID | Requisito | Status |
|----|-----------|--------|
| S1 | README alinhado ao código (Claude, multi-key, ping) | **Gap** — README cita só Groq + Gemini |
| S2 | Feature mergeada em `dev` | **Em curso** — código neste branch; `dev` pode ainda não ter merge |
| S3 | Pré-tradução / cache suficiente pós-seek | Implementado na manager; validar UX em seeks longos |
| S4 | Mensagens de erro amigáveis (rate limit, key inválida) | Em melhoria neste branch; revisar locales |
| S5 | Quota / cooldown visível nas settings (além do ping) | Router guarda snapshots; UI limitada |
| S6 | Rate-limit total → avisar sem mudar seleção | **Implementado:** AI off + **preserve** track/addon (nunca promover French); `aiSubtitleQuotaExhausted` oculta Translate; cooldown refresh reabilita. Manual e Smart iguais nesta regra. |
| S7 | Quality gate: não cachear / falhar lote ainda no idioma fonte | **Implementado:** `TranslationQualityGate` + `LOW_QUALITY` → fallback de provider; ver architecture ADR-AI-9 |

### Won’t (neste fork, por enquanto)

| ID | Item |
|----|------|
| W1 | ASR / Whisper / legendas geradas do áudio |
| W2 | Tradução AI no motor MPV |
| W3 | Servidor Nuvio intermediando keys ou prompts |
| W4 | ML Kit / modelo on-device para tradução |
| W5 | Sync de API keys entre dispositivos / perfis |
| W6 | Smart AI auto-selecionar ou traduzir **addons** (só via Translate with AI) |

## 5. Smart ladder (comportamento de produto)

Ordem **esperada pelo produto** (resumo). Para a ordem **exata** do código (`applyAiAutoSelectLadder`), use [`architecture-ai-subtitles.md`](./architecture-ai-subtitles.md) §3 — não duplique detalhes aqui.

```text
Smart AI ON + ExoPlayer + credenciais usáveis
 │
 ├─ 1. Embedded no idioma preferido     → seleciona, AI off
 ├─ 2. Embedded / original traduzível   → AI on dessa fonte
 └─ else classic auto-select / none     → AI off (addons só no classic, sem tradução)
```

Pick manual “Translate with AI” → rung **Manual** + lock até override do usuário (funciona em embedded **ou** addon).

### 5.1 Rate-limit esgotado vs score (esclarecimento)

| Conceito | O que é | Usa LLM? | Relação com fallback clássico |
|----------|---------|----------|-------------------------------|
| **Score de release** (M10) | 0–100, nome do stream × id/filename do addon | **Não** — só ranking local / badge | **Não** governa a ladder Smart; útil no menu e em diagnostics MANUAL |
| **Tradução AI** (M1/M5) | HTTP Groq/Gemini/Claude das cues | **Sim** | Se 429 em todas as keys → **S6**: AI off + **mesma** fonte; Translate oculto |
| **Seleção legada** | Auto-select clássico (preferred → secondary), sem tradução | Não | Degrau final quando Smart não acha embedded traduzível — **não** é o destino do S6 por 429 |

**Resumo:** “alternar para legado quando não houver keys válidas por rate limit” = só enquanto o usuário está (ou ficaria) **vendo legendas via tradução AI** (Smart embedded ou MANUAL). Se a ladder já escolheu preferred embedded **sem** AI, ou classic sem tradução, não há o que “desligar” por 429.

## 6. Settings & privacidade

| Dado | Onde | Sync perfil |
|------|------|-------------|
| `aiEnabled`, `aiAutoSelect`, `aiModel` | `PlayerSettingsDataStore` | Excluídos do sync (`localOnly…`) |
| Credentials JSON (+ legacy key) | `DeviceLocalPlayerPreferences` | Nunca |

## 7. Critérios de aceite (smoke)

1. Sem key / AI off → comportamento idêntico ao upstream (seleção clássica).
2. AI on + key + ExoPlayer + smart on + só original embedded → traduz para idioma preferido.
3. Embedded preferido presente → usa embedded **sem** chamar API.
4. Sem embutida traduzível + addons presentes → **classic** (AI off); smart **não** traduz addon. Translate with AI no addon → AI on + lock.
5. Translate with AI no menu → traduz e permanece após nova lista de tracks (enquanto lock).
6. Trocar para MPV → AI some / mensagem ExoPlayer-only.
7. Key inválida no ping → Invalid; 429 → Rate limited (ainda “válida”).
8. Desinstalar / limpar dados do app → keys somem (device-local).
9. (**S6**) Todas as keys em cooldown 429 → AI off, **mesma** fonte no menu, aviso, Translate oculto até haver quota. **Não** saltar para French/outra língua.

## 8. Gaps vs estado do repo

| Gap | Ação sugerida |
|-----|----------------|
| Merge para `dev` | Integrar `fix/ai-ladder-and-rate-limit` (ou release) quando for a linha ativa |
| README sem Claude / multi-key / ping | Atualizar seção “AI subtitles” |
| PRD vs arquitetura | Este arquivo = produto; `architecture-ai-subtitles.md` = implementação |
| **S6** rate-limit | AI off + seleção preservada + CTA Translate oculto (`aiSubtitleQuotaExhausted`). Ver UI menu §3.5 |

## 9. Fora deste PRD

Seek bar do player (outra fatia do fork), legendas clássicas upstream (charset, libass, sidecar sem AI), e modo Essential/Advanced — ver [`README.md`](./README.md).
