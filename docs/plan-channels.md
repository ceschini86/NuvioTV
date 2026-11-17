# Plano: sessão Channels

Alguns addons publicam catálogos de canais ao vivo. Hoje a Home mostra esses catálogos misturados com filmes e séries. O plano tira os catálogos de canais da Home e cria uma sessão "Channels" na barra lateral só para eles.

## Decisões

- Um catálogo é de canais quando o tipo declarado no manifest (`CatalogDescriptor.apiType`) é `channel` ou `tv`.
- A Home para de mostrar catálogos de canais. Não há opção para trazê-los de volta.
- O item "Channels" aparece na barra lateral só quando algum addon ativo tem catálogo de canais.
- Discover e Search continuam mostrando canais como hoje.
- O código novo fica em arquivos do fork, e os arquivos do upstream recebem ganchos curtos marcados com `// fork`, conforme o [ADR 0001](./adr/0001-codigo-do-fork-isolado-do-upstream.md).

## Risco: `tv` também quer dizer série

No resto do app, `tv` é sinônimo de série:

- `LivePlaybackUiPolicy.kt:9` diz para não tratar `tv` como ao vivo, porque no app `tv` é série.
- `MetaRepositoryImpl.kt:672` troca `tv` por `series` antes de pedir os detalhes ao addon.
- `MetaDetailsViewModel` e `HeroSection` tratam `ContentType.TV` como série.

Isso causa três problemas possíveis:

1. Um addon que publica séries com o tipo `tv` perde essas linhas na Home, e elas passam a aparecer em Channels.
2. Um canal com tipo `tv` abre a tela de detalhes pedindo meta do tipo `series`. Um addon de canais que só responde `tv` devolve erro.
3. O player não marca um item `tv` como ao vivo pelo tipo. Ele ainda detecta o ao vivo pelo sinal do player (`isCurrentMediaItemLive` no ExoPlayer, duração crescente no MPV).

A Fase 0 mede quais addons instalados usam `tv` e para quê. A Fase 5 só entra se o problema 2 aparecer.

## Fases

### Fase 0: levantamento

1. Listar os addons ativos no perfil da TV e, para cada um, os catálogos com tipo `channel` ou `tv`.
2. Para cada addon com catálogo `tv`, conferir se os itens são canais ou séries.
3. Abrir um canal de cada addon pelo Discover e anotar o que acontece nos detalhes e no player.

Saída: uma tabela addon × tipo × resultado, anexada à issue.

### Fase 1: regra de domínio

- Criar `domain/model/ChannelCatalogs.kt` com `CatalogDescriptor.isChannelCatalog()` e `CatalogRow.isChannelCatalog()`. A função compara `apiType` com `channel` e `tv`, sem diferenciar maiúsculas.
- Testes unitários para `channel`, `tv`, `TV`, `movie`, `series` e tipo vazio.

### Fase 2: tirar canais da Home

Ganchos em arquivos do upstream:

| Arquivo | Linha | Mudança |
|---|---|---|
| `ui/screens/home/HomeViewModelCatalogUtils.kt` | 361 | `shouldShowOnHome()` devolve `false` para catálogo de canais. Isso cobre o carregamento das linhas (`HomeViewModelCatalogPipeline.kt:225`), a ordem padrão (`HomeViewModelCatalogUtils.kt:316`) e o fallback do modo ambiente (`AmbientSlideLoader.kt:49`). |
| `ui/screens/home/HomeViewModelCatalogPipeline.kt` | 246 e 391 | O hero carrega catálogos escolhidos nas configurações mesmo fora da Home. Os dois filtros passam a pular catálogos de canais. |
| `ui/screens/settings/LayoutSettingsViewModel.kt` | 1057 | A lista de catálogos do hero esconde os de canais. |
| `ui/screens/addon/CatalogOrderViewModel.kt` | 405 | A tela de ordem dos catálogos da Home esconde os de canais. |

Pontos para conferir na implementação:

- `rebuildCatalogOrder` (`HomeViewModelCatalogUtils.kt:295`) filtra a ordem salva pelo conjunto disponível. Confirmar que uma chave de canal salva antes da mudança sai da ordem e não deixa linha vazia.
- `HomeCatalogSyncSupport.kt:163` fica igual. Esse sync é lido pelo app mobile, e mudar o filtro ali tiraria os canais do mobile.
- Coleções criadas pelo usuário continuam aceitando catálogos de canais, porque o usuário escolheu cada um.

### Fase 3: tela Channels

Arquivos novos em `ui/screens/channels/`:

- `ChannelsViewModel.kt` lê os addons ativos de `AddonRepository`, pega os catálogos com `isChannelCatalog()` que não são só de busca e carrega cada um com `CatalogRepository.getCatalog`. A paginação usa `CatalogRow.mergeCatalogPage`, como na Home.
- `ChannelsScreen.kt` mostra uma coluna de `CatalogRowSection`, uma linha por catálogo. "Ver tudo" abre `Screen.CatalogSeeAll`. Clicar num item abre `Screen.Detail` com o tipo do item. Sem catálogos, a tela mostra uma mensagem vazia.

Os cards usam o `posterShape` que o addon manda em `MetaPreview`, porque canais costumam vir com logo quadrado ou horizontal. Conferir na TV se o card fica legível.

### Fase 4: navegação

| Arquivo | Mudança |
|---|---|
| `ui/navigation/Screen.kt` | `data object Channels : Screen("channels")` |
| `ui/navigation/NuvioNavHost.kt` | `composable(Screen.Channels.route)` com os mesmos callbacks de detalhe do Discover (linha 1235). |
| `MainActivity.kt` | Em `rootRoutes` (linha 1048) e `drawerItems` (linha 1065), o item Channels entra logo depois de Home quando existe catálogo de canais. Ícone `Icons.Default.LiveTv`. |
| `res/values*/strings.xml` | `nav_channels` em inglês e português. |

`MainActivity` precisa de um `StateFlow<Boolean>` que diz se existe catálogo de canais. Ele sai de um ViewModel do fork, para não pôr lógica de addon dentro de `MainActivity`.

### Fase 5: detalhes de canal `tv` (condicional)

Não precisou de código. `MetaRepositoryImpl.getMeta` chama `supportedCandidateType` (linha 691), que pede o tipo original antes do tipo inferido. Um addon que declara `meta` para `tv` recebe o pedido como `tv`. Falta conferir na TV como a tela de detalhes mostra um canal `tv`, porque `MetaDetailsViewModel` trata `tv` como série.

## Como ficou

- A Fase 0 não rodou. O app instalado na TV é build de release, e `run-as` não lê os dados dele.
- "Ver tudo" de um catálogo de canais abre a rota `channel_see_all`. `CatalogSeeAllScreen` passou a aceitar qualquer `CatalogRowSource` no parâmetro `serverCatalog`, e `ServerCatalogViewModel` implementa essa interface. A rota usa o `ChannelsViewModel` da tela Channels, então a grade reaproveita as linhas e páginas já carregadas.
- As strings ficam em `res/values*/strings_channels.xml`. Em português o item da barra se chama "Canais".

### Fase 6: validação na TV

Na TV real (armeabi-v7a, 192.168.31.46):

1. A Home não mostra linhas de canais, nem no hero.
2. O item Channels aparece com um addon de canais ativo e some quando o addon é desativado.
3. Channels carrega as linhas, pagina e abre "Ver tudo".
4. Um canal abre os detalhes e toca, e o player mostra a interface de ao vivo.
5. Voltar do player e dos detalhes devolve o foco ao card certo em Channels.

## Fora do escopo

- Guia de programação (EPG).
- Mudar como Discover e Search tratam canais.
- Mudar o sync da Home com o app mobile.
