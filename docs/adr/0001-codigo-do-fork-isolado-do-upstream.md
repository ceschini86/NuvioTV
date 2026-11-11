# Código do fork fica isolado do upstream

O fork faz merges periódicos do NuvioTV upstream. Por isso a lógica nova fica em arquivos próprios do fork, como `PlayerRuntimeControllerAiSubtitles.kt` e o pacote `ui/screens/player/subtitles/`, e os arquivos do upstream recebem só ganchos curtos que chamam esse código, marcados com um comentário `// fork`. Refatorar um arquivo do upstream gera conflito em todo merge seguinte, então só vale quando o gancho curto não resolve.

## Base, merge e versão

- **Base atual:** Nuvio 1.1.0, tag `1.1.0` do upstream, commit `12d96bc`. O upstream reescreveu o histórico antes dessa versão. O merge funcionou porque o antigo `7990baa74` e o novo `191aebe38` têm a mesma árvore, e esse par serviu de base comum.
- **Merge commit sempre:** cada sync entra numa branch `sync/upstream-<versão>` e chega ao `dev` por PR com merge commit, sem squash nem rebase. Assim o merge-base do `dev` com o upstream passa a ser a tag sincronizada, e o próximo sync só traz o que mudou depois dela.
- **Versão:** o fork tem semver próprio. O minor sobe a cada sync com o upstream (1.1.4 → 1.2.0 no sync do 1.1.0), e a base do upstream vai nas notas da release. A tag git leva o prefixo `fork-` (`fork-1.2.0`) para não colidir com as tags do upstream. A release no GitHub usa a versão pura como nome (`1.2.0`), porque o updater do app lê o semver da tag ou do nome da release.

## Automação

- `fork-sync-upstream.yml` verifica todo dia as releases estáveis do upstream. Quando sai uma nova, ele faz o merge em `sync/upstream-<tag>`, mantém as linhas de versão do fork no conflito que todo sync tem (`scripts/fork_resolve_version_conflict.py`), sobe o minor (`scripts/fork-bump-version.sh minor`) e abre o PR para o `dev`, onde o CI roda. Qualquer outro conflito abre uma issue para sync manual.
- `fork-release.yml` publica a release quando um push no `dev` muda a versão e a tag `fork-<versão>` ainda não existe. Aprovar o PR de sync, com merge commit, é aprovar a release. Uma release só do fork sobe o patch (`scripts/fork-bump-version.sh patch`).
- Os dois são arquivos do fork. O `android-release.yml` do upstream continua sem mudanças.
