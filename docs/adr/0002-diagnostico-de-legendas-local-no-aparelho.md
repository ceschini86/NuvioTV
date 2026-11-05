# Diagnóstico de legendas fica no aparelho

A campanha de testes precisa de eventos de legendas com IA sem conectar o adb a cada sessão. O app grava esses eventos em JSONL no próprio aparelho, um arquivo por sessão de reprodução, quando o usuário liga o interruptor nas settings. O usuário exporta tudo de uma vez por `adb pull` ou por um botão de exportar.

O fork não usa o Sentry nem o Supabase que vieram do upstream. O Sentry é feito para erros, tem cota mensal e não guarda bem eventos de sessão. O Supabase é o backend de sync de perfil e não deve receber dados de teste. O arquivo guarda o texto das cues porque o `sub_scorecard.py` precisa dos pares fonte e tradução. Ele nunca guarda API key. A retenção é de 50 sessões ou 50 MB.

## Considered Options

- Coletor na rede local, com POST para um script no PC da bancada. Fica como acréscimo opcional se faltar tempo real, e usa os mesmos arquivos.
- Backend próprio na nuvem. Foi descartado porque dá mais trabalho e tira do aparelho texto que não precisa sair.
