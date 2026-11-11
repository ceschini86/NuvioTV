# Código do fork fica isolado do upstream

O fork faz merges periódicos do NuvioTV upstream. Por isso a lógica nova fica em arquivos próprios do fork, como `PlayerRuntimeControllerAiSubtitles.kt` e o pacote `ui/screens/player/subtitles/`, e os arquivos do upstream recebem só ganchos curtos que chamam esse código, marcados com um comentário `// fork`. Refatorar um arquivo do upstream gera conflito em todo merge seguinte, então só vale quando o gancho curto não resolve.

## Base, merge e versão

- **Base atual:** Nuvio 1.1.0, tag `1.1.0` do upstream, commit `12d96bc`. O upstream reescreveu o histórico antes dessa versão. O merge funcionou porque o antigo `7990baa74` e o novo `191aebe38` têm a mesma árvore, e esse par serviu de base comum.
- **Merge commit sempre:** cada sync entra numa branch `sync/upstream-<versão>` e chega ao `dev` por PR com merge commit, sem squash nem rebase. Assim o merge-base do `dev` com o upstream passa a ser a tag sincronizada, e o próximo sync só traz o que mudou depois dela.
- **Versão:** o fork tem semver próprio. O minor sobe a cada sync com o upstream (1.1.4 → 1.2.0 no sync do 1.1.0), e a base do upstream vai nas notas da release. A tag git leva o prefixo `fork-` (`fork-1.2.0`) para não colidir com as tags do upstream. A release no GitHub usa a versão pura como nome (`1.2.0`), porque o updater do app lê o semver da tag ou do nome da release.
