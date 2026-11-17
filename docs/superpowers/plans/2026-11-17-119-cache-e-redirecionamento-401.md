# #119 — Cache do frontend e redirecionamento do 401 nas páginas do Cliente

Origem: revisões do #25 (Meus Pedidos). Refs #25.

Só frontend; nenhuma migration (a V31 reservada não é usada); nenhum texto novo.

## Task 1: esquecer os dados da conta anterior ao entrar e ao sair

Arquivos: `frontend/src/features/session/hooks.ts`, `HomePage.test.tsx`, `LoginPage.test.tsx`.

- Em `useLogin`, `useLogout` e `useLogoutEverywhere`, no `onSuccess` do hook (antes do que já existe), remover do cache
  todas as consultas que não estão na tela, exceto a da sessão:
  `queryClient.removeQueries({ type: "inactive", predicate: (q) => q.queryKey[0] !== sessionQueryKey[0] })`.
  Uma função interna única no `hooks.ts`, usada pelos três.
- No login, a remoção vem antes do `refetchQueries` da sessão (que continua igual).
- Testes (cada um falha pela asserção antes do código):
  1. `LoginPage.test.tsx`: com `["library"]` e `["orders"]` no cache (`client.setQueryData`), um login bem-sucedido deixa
     os dois fora do cache (`client.getQueryData(...)` é `undefined`).
  2. `HomePage.test.tsx`: com `["library"]` no cache, "Sair" bem-sucedido o remove; o mesmo para "Sair de todos os aparelhos".
  3. Um login que falha (401) não mexe no cache.

## Task 2: o 401 ao abrir a página substitui a entrada do histórico

Arquivos: `LibraryPage.tsx`, `OrdersPage.tsx`, `OrderPage.tsx` e os testes deles.

- Nos três, o `navigate("/entrar")` do 401 ao carregar a página vira `navigate("/entrar", { replace: true })`.
- Fica igual: o 401 depois de um clique (`DownloadButton`, `BuyWithPix`), porque ali a página continua válida no histórico
  e, ao voltar, ela mesma redireciona com `replace`.
- Testes: com `createMemoryRouter` + `RouterProvider` e histórico `["/", "<página>"]`, depois do redirecionamento para
  `/entrar`, `router.navigate(-1)` leva a `/` (e não de volta à página protegida). Um por página.

## Decisões

| ID | Decisão | Marca |
|----|---------|-------|
| D1 | `removeQueries` das consultas inativas, menos a sessão, em vez do `queryClient.clear()` sugerido: o `clear()` apagaria também a sessão (o login espera por ela) e as consultas na tela (o painel do Administrador marca a sessão dele como saída, e uma consulta removida com a tela aberta seria buscada de novo) | DECIDIDO NO MODO CONTÍNUO — conferir |
| D2 | O `OrderPage` (`/pedidos/:id`) entra junto: tem o mesmo redirecionamento do 401 que prende o Voltar | DECIDIDO NO MODO CONTÍNUO — conferir |
| D3 | O 401 depois de um clique continua com `navigate` normal | DECIDIDO NO MODO CONTÍNUO — conferir |

## Textos que o usuário vê

Nenhum texto novo nem alterado.

## Riscos

- Depois de entrar ou sair, o Catálogo é buscado de novo (o cache dele também é removido). É uma requisição a mais e é aceitável.
- `gcTime: 0` das consultas de Pedidos continua; não é removido aqui.

## Verificação

Lint, testes focados de `session`, `library` e `orders`, `just check-rapido` no fim. Sem `/verificar-tela`: nenhuma tela muda de aparência.
