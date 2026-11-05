# NuvioTV (fork)

Fork do NuvioTV com pequenas melhorias de UI e tradução de legendas por IA com a key do próprio usuário. O fork continua sincronizando com o upstream.

## Legendas com IA

**Legenda contínua**:
A garantia de que o espectador nunca fica sem legenda nem preso numa legenda errada. Quando a tradução falha, a cue aparece no idioma da fonte, a tradução volta sozinha quando houver quota, e a tela mostra quando e por que a tradução está pausada.
_Avoid_: "funciona sempre", "sempre traduzida"

**Fonte**:
A faixa de legenda de onde saem as cues a traduzir. Pode ser embutida no stream ou vir de um addon.
_Avoid_: track (quando o sentido é origem do texto), source

**Smart AI**:
O modo em que o player escolhe sozinho a fonte embutida e decide se ela precisa de tradução. Nunca escolhe nem traduz fonte de addon.
_Avoid_: auto-tradução, AI automática

**Translate with AI**:
A escolha manual do usuário de traduzir uma fonte específica, embutida ou de addon. Cria um lock manual.
_Avoid_: tradução forçada

**Lock manual**:
O estado em que a escolha do usuário vale acima do Smart AI até ele mesmo trocar ou parar a tradução.
_Avoid_: pin, fixar

**Chip Traduzindo**:
O aviso na tela de que a cue visível está no idioma da fonte porque a tradução dela ainda não chegou. Tradução de cues futuras em segundo plano não mostra o chip.
_Avoid_: badge, "Translating…" (texto da string, não o nome do conceito), loading

**Tradução pausada**:
O estado em que a tradução para sem o usuário pedir, por quota esgotada, key inválida ou falha de rede. A fonte continua a mesma, as cues saem no idioma da fonte, a tela mostra o motivo e a tradução volta sozinha.
_Avoid_: AI desligada, AI off, quota exhausted

**AI desligada**:
O estado em que não há tradução porque o usuário desligou a feature ou não existe key usável. A tradução não volta sem ação do usuário.
_Avoid_: tradução pausada

**Key gratuita**:
A key de provider no plano sem pagamento, que é o caso de referência do fork. Esgotar a quota dela no meio de um episódio é o caminho normal, não exceção.
_Avoid_: free key, key de teste

## Testes

**Diagnóstico de legendas**:
O registro de eventos de legendas com IA que o app grava no aparelho, um arquivo por sessão, quando o usuário liga o interruptor.
_Avoid_: telemetria (sugere envio para servidor), logs, analytics

**Caso**:
Um comportamento observado num título real ou no mock e registrado no catálogo, com sintoma, evidência e estado (aberto, explicado ou corrigido).
_Avoid_: bug (antes de confirmar), issue
