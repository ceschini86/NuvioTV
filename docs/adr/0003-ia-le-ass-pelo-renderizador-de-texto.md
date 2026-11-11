# Com a tradução por IA ligada, ASS/SSA sai pelo renderizador de texto

No 1.1.0, o upstream passou a ligar o libass por padrão. Os parsers do libass não entregam cues ao Media3, porque o arquivo vai direto para a faixa nativa. Assim, uma Fonte ASS/SSA desenhada pelo libass nunca chega ao `TranslatingTextOutput` nem ao sidecar. Ela ficaria na tela sem tradução, sem nenhum aviso. Por isso, com a tradução por IA ligada, a Fonte ASS/SSA sai pelo renderizador de texto:

- Uma Fonte de addon vai para o sidecar e passa pelo `SsaParser` do Media3.
- Uma faixa embutida faz o player ser reconstruído sem libass. O stream fica sem libass até o próximo, mesmo que a IA seja desligada.

Nos dois casos o estilo do ASS se perde, e a tradução aparece. O código fica em `PlayerAiSubtitleRouting.kt`.

A ordem com o AutoSync é esta: o AutoSync reajusta os tempos da Fonte, e a tradução entra depois, na hora de renderizar a cue, buscada pelo texto dela. O reajuste muda só os tempos, então a Fonte reajustada encontra a mesma entrada no cache de tradução e não gera chamada nova ao provider. Com a Tradução pausada, o AutoSync continua ajustando as cues, que aparecem no idioma da Fonte.

## Considered Options

- Traduzir dentro do libass, reescrevendo as linhas `Dialogue` do arquivo e trocando a faixa com `AssHandler.replaceTrack`, como o AutoSync faz com os tempos. Isso manteria o estilo. Foi adiado porque a tradução chega aos poucos, cue a cue, e cada troca de faixa reprocessa o arquivo inteiro.
- Avisar que a IA não traduz ASS com libass e manter o libass. Foi descartado porque o usuário pediu tradução, e a Legenda contínua pede que a tela mostre a tradução quando ela existe.

## Consequences

Ligar a IA numa faixa ASS embutida reconstrói o player e descarta o buffer, como o upstream já faz quando troca o pipeline do libass. Um stream que passou para o texto não volta ao libass quando a IA é desligada. Esse é o preço de não alternar o pipeline a cada mudança de faixa.
