# Diagnóstico de legendas fica no aparelho, como logcat do próprio app

A campanha de testes precisa dos eventos de legendas com IA sem conectar o adb a cada sessão. Quando o usuário liga "Registrar diagnóstico de legendas" em Playback → AI subtitles, o app roda `logcat` filtrado pelas tags de legenda (`PlayerViewModel`, `SubtitleTranslation`, `SubtitleAiRouter`, `SubtitleAiHttp`, `SubtitleCueDisplay`, `SubtitleRepository`, `SubtitleFocus`, `SubtitleSmoke`, `SubtitleDiag`) e grava em `Android/data/<pacote>/files/subtitle-diagnostics/`. O logcat gira os arquivos em 5 MB, e a pasta guarda 10 arquivos, cerca de 50 MB no total. O usuário puxa tudo de uma vez com `NuvioTV-testbench/tools/ai_sub_diag.py pull`, que junta os arquivos em ordem e roda o `ai_sub_logcat.py summarize`.

O fork não usa o Sentry nem o Supabase que vieram do upstream. O Sentry é feito para erros, tem cota mensal e não guarda bem eventos de sessão. O Supabase é o backend de sync de perfil e não deve receber dados de teste. O log guarda o texto das cues porque o `sub_scorecard.py` precisa dos pares fonte e tradução. Os logs já mascaram as API keys.

## Considered Options

- JSONL estruturado, um arquivo por sessão, com um evento novo ao lado de cada ponto relevante. Foi adiado porque exige mudar cerca de 110 pontos de log e escrever um leitor novo, e o formato de log atual já tem um leitor pronto. Ele volta a ser opção quando a Legenda contínua mudar os eventos.
- Coletor na rede local, com POST para um script no PC da bancada. Fica como acréscimo opcional se faltar tempo real.
- Backend próprio na nuvem. Foi descartado porque dá mais trabalho e tira do aparelho texto que não precisa sair.

## Consequences

O formato é texto de log, e um log reescrito pode quebrar o parser da bancada. Os arquivos não se separam por sessão de reprodução. O leitor separa as sessões pelas linhas `SUMMARY reason=player_reset` e pelas linhas `capture start` desta captura.
