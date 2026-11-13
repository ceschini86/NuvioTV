# Ryot é o provedor de tracking self-host do fork

O backend oficial do Nuvio pode fechar, e o fork já roda o próprio backend numa VPS. O tracking (histórico, progresso, watchlist) dependia do Simkl, um serviço de terceiros. O fork passa a falar com uma instância própria do [Ryot](https://github.com/IgnisDa/ryot) (GPL-3.0), como um quarto provedor ao lado de Trakt, Simkl e MDBList. O código fica em `data/ryot/` e em `ui/screens/settings/RyotTrackingSettings.kt`. Os arquivos do upstream recebem só ganchos `// fork`: o valor `RYOT` em `TrackingProviderId`, `WatchProgressSource` e `LibrarySourceMode`, os ramos de `when` que esses valores exigem, e a linha do Ryot na tela de Tracking.

O app faz login com `loginUser` e guarda só a chave de sessão, nunca a senha. No primeiro login ele cria no Ryot uma integração "Generic Json" chamada "Nuvio TV". Scrobbles, marcações de assistido e itens novos na Watchlist vão para o webhook dessa integração (`/_i/<slug>`) pelo ID do TMDB, porque a API GraphQL só aceita o ID interno do Ryot, e a busca do Ryot é só por texto. O Ryot processa o webhook em segundo plano e responde 202 antes; o app aplica a mudança no snapshot local e sincroniza de novo seis segundos depois. Desmarcar usa `deleteSeenItem`, que precisa do ID do registro, lido com `userMetadataDetails`.

A leitura monta um snapshot por perfil em `files/ryot/`. A sincronização completa (no primeiro uso, uma vez por dia ou a pedido) lista a biblioteca e lê os detalhes em lotes de 50 aliases GraphQL. A incremental lê a primeira página de cada tipo em ordem de `LAST_UPDATED` e recarrega só os títulos que subiram na ordem, mais os 10 primeiros. O Ryot guarda só o ID do TMDB; o app resolve o IMDb pelo TMDB depois de salvar o snapshot, em segundo plano. Nenhuma chamada de rede roda dentro do mutex do snapshot: com a rede lá dentro, as ações do usuário esperavam a sincronização por dezenas de segundos.

## Considered Options

- Yamtrack. Descartado: não tem API, só páginas HTML e webhooks de escrita (`webhook/{jellyfin,plex,emby}/<token>`). O app não conseguiria ler biblioteca nem progresso.
- Hound. Tem uma API REST parecida com a do Trakt, mas está em beta e é um media server inteiro.
- Escrever tudo pela GraphQL (`deployBulkMetadataProgressUpdate`). Exige descobrir o ID interno antes de cada escrita, e um título novo exigiria busca por texto.
- Export do Ryot como fonte da leitura. Exige armazenamento S3 configurado no servidor.

## Consequences

O Ryot numera episódios como o TMDB. Um addon que numere de outro jeito (comum em anime) pode marcar o episódio errado; o MDBList tem um conversor de numeração que o Ryot ainda não tem. Títulos que o Ryot guarda por outra fonte (os animes do AniList vindos da importação do Simkl) ficam fora do app. A escrita pelo webhook não confirma o resultado na hora: uma falha só aparece no Ryot, nos resultados da integração.
