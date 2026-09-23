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
| Última revisão | 2026-09-23 |

---

## 1. Problema

Legendas no idioma do usuário costumam faltar, vir com release errado, ou existir só no idioma original. Tradução manual (ou “pegar qualquer addon no idioma certo”) falha quando o release não casa com o stream.

## 2. Solução

**Traduzir legendas já existentes** (embedded ou addon) para o idioma preferido do usuário, via LLM com **chave própria (BYOK)**, e **escolher a melhor fonte** automaticamente quando o modo smart estiver ligado.

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
| M9 | Smart ladder (auto-select) | Ordem de produto abaixo; implementação canônica em architecture §3 |
| M10 | Score de release-name 0–100 | Addon como fonte AI só se score ≥ 50; badge % no overlay |
| M11 | “Translate with AI” manual | Lock: auto-select não sobrescreve até o usuário mudar |
| M12 | Diagnósticos | Long-press / Details: rung, reason, source, score, target, model, locked |
| M13 | Fonte sem texto extraível (ex.: PGS) | Não ficar no original “silencioso”; tenta próxima fonte ou desliga AI |
| M14 | Settings Playback → AI subtitles | Enable, smart auto-select, provider preferido, enable por provider, keys |

### Should (parcial / próximo)

| ID | Requisito | Status |
|----|-----------|--------|
| S1 | README alinhado ao código (Claude, multi-key, ping) | **Gap** — README cita só Groq + Gemini |
| S2 | Feature mergeada em `dev` | **Em curso** — código neste branch; `dev` pode ainda não ter merge |
| S3 | Pré-tradução / cache suficiente pós-seek | Implementado na manager; validar UX em seeks longos |
| S4 | Mensagens de erro amigáveis (rate limit, key inválida) | Em melhoria neste branch; revisar locales |
| S5 | Quota / cooldown visível nas settings (além do ping) | Router guarda snapshots; UI limitada |

### Won’t (neste fork, por enquanto)

| ID | Item |
|----|------|
| W1 | ASR / Whisper / legendas geradas do áudio |
| W2 | Tradução AI no motor MPV |
| W3 | Servidor Nuvio intermediando keys ou prompts |
| W4 | ML Kit / modelo on-device para tradução |
| W5 | Sync de API keys entre dispositivos / perfis |

## 5. Smart ladder (comportamento de produto)

Ordem **esperada pelo produto** (resumo). Para a ordem **exata** do código (`applyAiAutoSelectLadder`), use [`architecture-ai-subtitles.md`](./architecture-ai-subtitles.md) §3 — não duplique detalhes aqui.

```text
Smart AI ON + ExoPlayer + credenciais usáveis
 │
 ├─ 1. Embedded no idioma preferido     → seleciona, AI off
 ├─ 2. Embedded / original traduzível   → AI on dessa fonte
 ├─ 3. Melhor addon (idioma pivot) com score ≥ 50 → AI on
 ├─ 4. Addon no idioma preferido (melhor score)   → sem AI
 └─ else classic auto-select / none
```

Pick manual “Translate with AI” → rung **Manual** + lock até override do usuário.

## 6. Settings & privacidade

| Dado | Onde | Sync perfil |
|------|------|-------------|
| `aiEnabled`, `aiAutoSelect`, `aiModel` | `PlayerSettingsDataStore` | Excluídos do sync (`localOnly…`) |
| Credentials JSON (+ legacy key) | `DeviceLocalPlayerPreferences` | Nunca |

## 7. Critérios de aceite (smoke)

1. Sem key / AI off → comportamento idêntico ao upstream (seleção clássica).
2. AI on + key + ExoPlayer + smart on + só original embedded → traduz para idioma preferido.
3. Embedded preferido presente → usa embedded **sem** chamar API.
4. Addon com score baixo (< 50) **não** vira fonte AI; score alto (≥ 50) pode.
5. Translate with AI no menu → traduz e permanece após nova lista de tracks (enquanto lock).
6. Trocar para MPV → AI some / mensagem ExoPlayer-only.
7. Key inválida no ping → Invalid; 429 → Rate limited (ainda “válida”).
8. Desinstalar / limpar dados do app → keys somem (device-local).

## 8. Gaps vs estado do repo

| Gap | Ação sugerida |
|-----|----------------|
| Merge para `dev` | Integrar `fix/ai-ladder-and-rate-limit` (ou release) quando for a linha ativa |
| README sem Claude / multi-key / ping | Atualizar seção “AI subtitles” |
| PRD vs arquitetura | Este arquivo = produto; `architecture-ai-subtitles.md` = implementação |

## 9. Fora deste PRD

Seek bar do player (outra fatia do fork), legendas clássicas upstream (charset, libass, sidecar sem AI), e modo Essential/Advanced — ver [`README.md`](./README.md).
