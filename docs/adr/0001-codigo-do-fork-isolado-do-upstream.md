# Código do fork fica isolado do upstream

O fork continua fazendo merges periódicos do NuvioTV upstream. Por isso a lógica nova fica em arquivos próprios do fork, como `PlayerRuntimeControllerAiSubtitles.kt` e o pacote `ui/screens/player/subtitles/`, e os arquivos do upstream recebem só ganchos curtos que chamam esse código. Refatorar um arquivo do upstream gera conflito em todo merge seguinte, então só vale quando o gancho curto não resolve.
