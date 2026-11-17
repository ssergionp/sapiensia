# #113 — Esconder o "Comprar com Pix" para quem já tem o Produto

Origem: D5 do plano do #32 (retoma a D8 do #28). Refs #32.

## O que muda

Só o frontend. Na página do Produto (`/catalogo/:id`), a tela consulta a Biblioteca do Cliente
(`GET /api/library`, que já existe, via `useLibrary`). Se o Produto está na Biblioteca, no lugar
do botão "Comprar com Pix" aparece um aviso e um link para `/biblioteca`.

- Visitante sem sessão (401), Administrador (403), erro ou Biblioteca ainda carregando: o botão
  continua como hoje. O `409 ALREADY_OWNED` do servidor segue sendo a proteção de verdade.
- Nenhuma mudança no backend, nenhuma migration (a V30 reservada não é usada).
- Arquivos: `frontend/src/features/catalog/CatalogProductPage.tsx` e `CatalogProductPage.test.tsx`; no e2e
  `frontend/e2e/admin-panel.e2e.ts`, o trecho "comprar de novo o mesmo Produto" passa a esperar o aviso e o link
  no lugar do botão (o `409 ALREADY_OWNED` continua coberto pelo `PaidOrderReadTest`).

## Decisões

| ID | Decisão | Marca |
|----|---------|-------|
| D1 | Usar a Biblioteca existente em vez de um campo novo na resposta do Catálogo: zero backend, mesma regra de Acesso (só Acesso ativo aparece na Biblioteca) | DECIDIDO NO MODO CONTÍNUO — conferir |
| D2 | Enquanto a Biblioteca carrega, ou se ela falhar, mostra o botão (comportamento de hoje); o servidor recusa com 409 se for o caso | DECIDIDO NO MODO CONTÍNUO — conferir |
| D3 | Textos: reaproveitar os que já existem, sem texto novo: aviso "Você já tem Acesso a este Produto." e link "Ir para a Biblioteca" | DECIDIDO NO MODO CONTÍNUO — conferir |

## Textos que o usuário vê

| Onde | Texto | Novo? |
|------|-------|-------|
| Aviso no lugar do botão | `Você já tem Acesso a este Produto.` | não (`ORDER_TEXTS.alreadyOwned`) |
| Link para `/biblioteca` | `Ir para a Biblioteca` | não (`ORDER_TEXTS.goToLibrary`) |

## Testes (Vitest, `CatalogProductPage.test.tsx`, `fetch` falso por URL)

1. Produto na Biblioteca: sem botão "Comprar com Pix"; mostra o aviso e o link "Ir para a Biblioteca" com `href="/biblioteca"`.
2. Biblioteca com outro Produto: o botão aparece.
3. Sem sessão (`/api/library` responde 401): o botão aparece.

Cada um falha por asserção antes do código (o 1 falha; o 2 e o 3 já passam e protegem contra regressão).

## Riscos

- Uma requisição a mais (`/api/library`) por visita à página do Produto; para o visitante ela responde 401.
- O cache `["library"]` é o mesmo da tela da Biblioteca; depois de um reembolso a página consulta de novo ao abrir.

## Verificação

Lint, teste focado do frontend, `/verificar-tela` na página do Produto, `just check-rapido` no fim.
