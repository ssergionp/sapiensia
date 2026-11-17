# #63 — Tela inicial: e-mail longo faz a página rolar na horizontal no celular

Origem: `/verificar-tela` do #13. Refs #13.

Só frontend; sem migration; nenhum texto novo nem alterado.

## Task 1: deixar o e-mail quebrar

Arquivos: `frontend/src/features/session/HomePage.tsx` e `frontend/src/features/session/HomePage.test.tsx`.

- No parágrafo "Você entrou como {email}.", o e-mail vai num `<span className="[overflow-wrap:anywhere]">`, igual à `AdminHomePage` ("Você entrou como …" do painel).
- Teste (falha pela asserção antes do código): `com um e-mail longo, o e-mail fica num trecho que pode quebrar em qualquer ponto` — com o e-mail `ana.souza.com.nome.bem.comprido@example.com`, o elemento com o texto do e-mail tem a classe `[overflow-wrap:anywhere]` e o parágrafo continua com o texto "Você entrou como ana.souza.com.nome.bem.comprido@example.com.".

## Decisões

| ID | Decisão | Marca |
|----|---------|-------|
| D1 | Quebra com `overflow-wrap:anywhere` só no e-mail (o mesmo da `AdminHomePage`), em vez de `break-all` no parágrafo inteiro: as palavras do texto continuam inteiras e só o endereço quebra quando não cabe | DECIDIDO NO MODO CONTÍNUO — conferir |

## Textos que o usuário vê

Nenhum novo nem alterado.

## Verificação

`pnpm exec vitest run src/features/session`; lint; `/verificar-tela` da tela inicial em 390 px com o e-mail de 44 caracteres e com um de 254 (sem rolagem horizontal); `just check-rapido` no fim.
