# Meus Pedidos (#25) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Implementation subagents run on Sonnet.

**Status:** APROVADO pelo Sérgio em 2026-11-14 (D1 a D5 aceitas como recomendado; textos da D6 aprovados sem mudança).

**Goal:** O Cliente logado abre "Meus Pedidos" e vê os seus Pedidos (título do Produto, valor, data, estado: aguardando pagamento, pago ou expirado), do mais recente para o mais antigo. Nunca aparece Pedido de outro Cliente.

**Architecture:** Uma rota de leitura nova, `GET /api/orders`, no `OrderController` que já existe: um `select` em `orders` junto com `products`, filtrado no SQL pelo Cliente da sessão, sem chamar o Asaas e sem mudar estado. O estado mostrado sai do mesmo `OrderService.shownStatus` do detalhe (pendente vencido aparece como expirado). O detalhe continua sendo o `GET /api/orders/{id}`. No frontend, `OrdersPage` na rota `/pedidos` (o detalhe já é `/pedidos/:id`) e um link na página inicial do Cliente.

**Tech Stack:** o que já existe (Spring Boot 4.1 com `JdbcClient`, Testcontainers; React 19, TanStack Query, Zod, Vitest, Playwright). **Nenhuma dependência nova, nenhuma migration.**

**Spec:** `docs/spec/sapiensia-spec.md` (FR-020, SEC-010, SEC-011); issue #25 (pai #5); `GLOSSARY.md` (Pedido); `docs/adr/0003-pagamento-so-por-pix-via-asaas.md`; planos do #24 (Pedido pendente) e do #32 (Biblioteca, mesmo formato de lista).

## IDs cobertos

| ID | Como | Onde |
|---|---|---|
| FR-020 | Só a parte de leitura dos estados que existem hoje (pendente, pago, expirado). Cumprimento, cancelamento, reembolso e disputa ficam de fora (seção 6) | Tasks 1, 2, 3 |
| SEC-011 | A lista filtra pelo Cliente da sessão no SQL; teste negativo na lista. O detalhe já tem o seu (`OrderCreationTest` `SEC-011: shouldAnswer404ForAnotherCustomersOrder`, `PaidOrderReadTest`) e entra na rodada focada | Tasks 1, 3 |
| SEC-010 | Rota nova negada por padrão e liberada só para `CUSTOMER`: anônimo 401, Administrador 403 | Task 1 |

## Global Constraints

- Rota nova liberada na `SecurityConfig` antes do `denyAll`, só `GET /api/orders` e só `hasRole("CUSTOMER")`. Nenhum `permitAll`.
- A lista **nunca** chama o `PixGateway` e **nunca** grava no banco (nem `markExpired`).
- Controller e repositório package-private, DTOs como `record`, `CurrentCustomer.id(authentication)`. ID do requisito no `@DisplayName`/título do teste. `@Import(TestcontainersConfiguration.class)`, sem `@MockitoBean` em integração.
- Toda chamada do frontend via `apiFetch`. Textos de tela **só os da seção 4**.
- Teste focado: incluir as classes que citam os IDs tocados (`git grep -l -E "FR-020|SEC-011|SEC-010" -- backend/src/test/java/br/com/sergio/sapiensia/payment`) e o `OrderRouteAccessTest`.
- Um comando pesado por vez, com 2 GB livres antes. Só `just check-rapido` durante o trabalho.
- Push só com `git push -u origin feat/25-meus-pedidos`, depois do OK.

## Review Focus

1. **Pendente que vence com a lista aberta:** a tela não calcula expiração sozinha (mesma regra da `OrderPage`): continua "Aguardando pagamento" até recarregar; o detalhe mostra expirado. Teste da Task 2: item `PENDING` com `expiresAt` no passado continua "Aguardando pagamento".
2. **Mesmo Produto em vários Pedidos** (um expirado e depois um pago): dois itens, cada um com o seu estado. Teste da Task 1.
3. **Produto despublicado depois da compra:** o Pedido continua na lista com o título (sem filtro de `p.status`). Teste da Task 1.
4. **Título longo em 390 px:** quebra com `overflow-wrap: anywhere`, sem rolagem horizontal. E2E da Task 3 no `Pixel 7`.
5. **Administrador logado abre `/pedidos`:** `403` cai no estado de erro, sem laço para `/entrar`. Teste da Task 2.

---

## 1. Decisões técnicas

**a) Rota da lista.** `GET /api/orders` → `200 {"items": [{"id", "productTitle", "priceCents", "status", "createdAt", "expiresAt"}]}`, ordenado por `created_at desc` (empate por `id`). `status` já é o estado mostrado. Não saem o código Pix, o id da cobrança no Asaas, o `productId` nem o id do Cliente: a tela não usa. Sem paginação (D4): na Fase 1 um Cliente tem poucos Pedidos (no máximo um pendente por Produto, e o pendente é reaproveitado); mesma escolha da Biblioteca. O volume fica para o #44.

SQL, no `Orders` (reaproveita o `SELECT` e o `map` que já existem):
```sql
<SELECT existente> where o.customer_id = :customerId order by o.created_at desc, o.id
```
Sem filtro de `p.status`: Produto despublicado continua na lista.

Código:
- `Orders.findAllForCustomer(UUID customerId): List<Row>`
- `record OrderSummary(UUID id, String productTitle, int priceCents, OrderStatus status, Instant createdAt, Instant expiresAt)` e `record OrderListResponse(List<OrderSummary> items)` no pacote `payment`
- `OrderService.list(UUID customerId): OrderListResponse`: lê as linhas e aplica `shownStatus(row, clock.instant())` em cada uma; sem transação, sem gateway
- `OrderController`: `@GetMapping OrderListResponse list(Authentication authentication)`

**b) Só banco, sem efeito.** O `OrderService.list` não recebe nada do `PixGateway` no caminho e não chama `expirer`/`markExpired`. Provado por teste: o `SwitchablePixGateway.calls()` não muda depois da lista, e o Pedido pendente vencido continua `PENDING` no banco depois de aparecer como `EXPIRED`.

**c) Estados mostrados.** `PENDING` → "Aguardando pagamento", com "Pix válido até 14:35" (hora de `expiresAt` no fuso de São Paulo) e o link "Ver o Pix" para `/pedidos/:id`, onde já estão o QR Code e a contagem regressiva. A lista **não** tem contagem viva (D1). `PAID` → "Pago" e o link "Ir para a Biblioteca". `EXPIRED` → "Expirado", sem link (D2). Pendente com o prazo vencido chega do servidor como `EXPIRED`, como no `OrderService.get`.

**d) Proteção da página.** Igual à Biblioteca: `401` → `NotLoggedInError` → `rememberReturnTo("/pedidos")` e `navigate("/entrar")`; `403` cai no estado de erro. A lista não faz polling (D3): carrega ao abrir e quando a aba volta ao foco (padrão do TanStack Query).

## 2. Arquivos

- Modificar: `backend/.../payment/Orders.java` (`findAllForCustomer`), `OrderService.java` (`list`), `OrderController.java` (`@GetMapping`)
- Criar: `backend/.../payment/OrderSummary.java`, `OrderListResponse.java`
- Modificar: `backend/.../security/SecurityConfig.java` (1 regra + comentário)
- Testes: criar `backend/src/test/.../payment/MyOrdersTest.java`; modificar `OrderRouteAccessTest.java`
- Frontend: modificar `frontend/src/features/orders/{api.ts,schemas.ts,hooks.ts,messages.ts}`; criar `OrdersPage.tsx` e `OrdersPage.test.tsx`; modificar `frontend/src/App.tsx` (rota `/pedidos`), `frontend/src/features/session/HomePage.tsx` e `HomePage.test.tsx`
- Modificar: `frontend/e2e/admin-panel.e2e.ts` (o teste FR-021 já existente ganha os passos da lista)

## 3. Tarefas

### Task 1: `GET /api/orders` (TDD)

**Interfaces — Produces:** `GET /api/orders` → `{"items":[{"id":uuid,"productTitle":string,"priceCents":int,"status":"PENDING"|"EXPIRED"|"PAID","createdAt":ISO-8601,"expiresAt":ISO-8601}]}`.

- [ ] **Step 1: testes que falham.** `MyOrdersTest` (Testcontainers; `OrderHttp.newCustomer`, `OrderTestSupport.insertPublishedProduct`; Pedidos inseridos por SQL com `status`, `created_at`, `expires_at` e `paid_at` escolhidos):
  - `FR-020: shouldListTheCustomersOrdersNewestFirst` — um pago, um expirado, um pendente em dia; ordem por `createdAt` desc; título, valor e estado de cada um
  - `FR-020: shouldShowAPendingOrderPastItsTimeAsExpiredWithoutChangingIt` — resposta `EXPIRED`; no banco continua `PENDING` e `expired_at` nulo
  - `FR-020: shouldNeverCallThePaymentProvider` — `SwitchablePixGateway.calls()` igual antes e depois, com um pendente em dia na lista
  - `FR-020: shouldListEveryOrderOfTheSameProduct` — um expirado e um pago do mesmo Produto: dois itens
  - `FR-020: shouldKeepTheOrderOfAnUnpublishedProduct`
  - `FR-020: shouldAnswerAnEmptyListForACustomerWithoutOrders`
  - `FR-020, SEC-011: shouldNeverListAnotherCustomersOrders` — dois Clientes com Pedidos; cada um vê só os seus; a resposta não contém o id do Pedido do outro
  - `FR-020: shouldNotExposeThePixCodeOrTheProviderId` — cada item tem exatamente as 6 chaves
- [ ] **Step 2:** `OrderRouteAccessTest`: `SEC-010: shouldRefuseTheListToAnAnonymousVisitor` (401) e `SEC-010: shouldRefuseTheListToAnAdministrator` (403). Em `shouldKeepEveryOtherOrderRouteDenied`, a linha `get(ORDERS)` → 403 **sai** porque a rota passa a existir de propósito; entra no lugar `post(ORDERS + "/" + id)` com `csrf()` → 403, para o teste continuar provando quatro rotas negadas.
- [ ] **Step 3:** rodar só essas classes e ver falhar (`cd backend; .\mvnw.cmd test -Dtest='MyOrdersTest,OrderRouteAccessTest'`).
- [ ] **Step 4:** implementar a seção 1a (repositório, service, controller, records, regra na `SecurityConfig` com o comentário `// my orders (#25): the logged-in Customer only, filtered by owner in the SQL (SEC-011)`).
- [ ] **Step 5:** rodada focada (Global Constraints) verde; `just check-rapido` verde.
- [ ] **Step 6:** commit `feat: list the customer's orders (#25)`.

### Task 2: página `/pedidos` e link na página inicial (TDD)

**Interfaces — Consumes:** a rota da Task 1. **Produces:** `orderListSchema` (Zod, espelha `OrderListResponse`), `listOrders()` em `api.ts` (mesmo tratamento de `401` → `NotLoggedInError` do `getOrder`), `useOrders()` com query key `["orders"]`, textos novos em `ORDER_TEXTS`.

- [ ] **Step 1: testes que falham.** `OrdersPage.test.tsx`:
  - `FR-020: mostra cada Pedido com título, valor, data e estado` (os três estados; "Pedido em 14/11/2026" e "Pix válido até 14:35" no fuso de São Paulo; valor `R$ 29,90`)
  - `FR-020: Pedido pendente tem o link Ver o Pix para o detalhe` (nome acessível `Ver o Pix de <título>`, `href` `/pedidos/<id>`)
  - `FR-020: Pedido pago tem o link Ir para a Biblioteca`; `Pedido expirado não tem link`
  - `não calcula expiração no navegador` (`PENDING` com `expiresAt` no passado continua "Aguardando pagamento")
  - vazio com o link "Ver o Catálogo"; carregando; erro com "Tentar novamente" que refaz a consulta; `403` mostra o erro e não navega; `401` leva a `/entrar` e guarda `/pedidos`; título da aba; foco no h1 ao carregar
  - `HomePage.test.tsx`: Cliente logado vê o link "Meus Pedidos" para `/pedidos`
- [ ] **Step 2:** ver falhar; implementar `OrdersPage` copiando a estrutura da `LibraryPage` (h1 com `tabIndex={-1}`, `<ul aria-labelledby>`, `<time dateTime>`, `formatCents`), rota `/pedidos` no `App.tsx` (ao lado de `/pedidos/:id`), link "Meus Pedidos" na `HomePage` logada depois de "Biblioteca".
- [ ] **Step 3:** `just check-rapido` verde; commit `feat: my orders page (#25)`.

### Task 3: E2E barato

Sem Produto novo nem login novo de Administrador: o teste `FR-021 ...: o pagamento confirmado pelo Asaas libera o Acesso` (`admin-panel.e2e.ts`) já compra, paga e tem um segundo Cliente e um contexto `Pixel 7`. Ele ganha `FR-020 SEC-011` no título e estes passos:
- Antes do evento de pagamento, numa **nova aba** do mesmo contexto do comprador (a aba do Pedido continua aberta para o resto do teste): página inicial → link "Meus Pedidos" → o item com o título mostra "Aguardando pagamento" e o link "Ver o Pix" leva ao Pedido.
- Depois do pago: a lista, recarregada, mostra "Pago" e "Ir para a Biblioteca" leva a `/biblioteca`.
- O segundo Cliente abre `/pedidos`: "Você ainda não fez nenhum Pedido." e o título do comprador não aparece.
- No `Pixel 7`, depois do pago: `/pedidos` sem rolagem horizontal.
- Sem `page.route` nem mocks. Commit `test: my orders e2e (#25)`.

### Task 4: Verificação

`/verificar-tela` em `/pedidos` (com os três estados, vazia, erro), 1280 e 390 px, teclado; sistema de verdade (`just db` na pasta principal, `just dev-back`, `just dev-front`) e `just e2e` do teste alterado; `just check-rapido`; skill `verificar-entrega`; revisão de código (`requesting-code-review`).

## 4. Textos novos (pt-BR)

**Meus Pedidos** (`/pedidos`)

| Onde | Texto |
|---|---|
| Título da aba | `Meus Pedidos \| SapiensIA` |
| Título (h1) | `Meus Pedidos` |
| Carregando | `Carregando…` (já existe) |
| Data do item | `Pedido em 14/11/2026` (só a data) |
| Valor | `R$ 29,90` (formato que já existe) |
| Estado pendente | `Aguardando pagamento` |
| Prazo do pendente | `Pix válido até 14:35.` |
| Link do pendente | `Ver o Pix` (nome acessível: `Ver o Pix de <título>`) |
| Estado pago | `Pago` |
| Link do pago | `Ir para a Biblioteca` (já existe) |
| Estado expirado | `Expirado` |
| Vazio | `Você ainda não fez nenhum Pedido.` |
| Vazio: link | `Ver o Catálogo` (já existe na Biblioteca) |
| Erro | `Não foi possível carregar os seus Pedidos. Tente novamente.` |
| Erro: botão | `Tentar novamente` (já existe) |

**Entrada**

| Onde | Texto |
|---|---|
| Página inicial, Cliente logado (depois de "Biblioteca") | `Meus Pedidos` |

**Celular e acessibilidade:** `main` com `max-w-2xl`, como a Biblioteca; `<ul aria-labelledby>` do h1; cada item em bloco (título com `overflow-wrap: anywhere`, estado em texto, nunca só por cor; valor e data numa linha; prazo e link abaixo), sem rolagem horizontal em 390 px; foco no h1 ao carregar; datas em `<time dateTime>`; links com nome único por item; sem `aria-live` novo.

## 5. Riscos

- **R1 — Lista sem paginação e sem índice por Cliente:** a consulta faz varredura em `orders`. Irrelevante no volume da Fase 1; o #44 (teste de carga) mede. Um índice `(customer_id, created_at)` é uma migration de uma linha, se precisar.
- **R2 — Estado defasado com a tela aberta:** sem polling, um pendente pago ou vencido só muda na lista ao recarregar ou voltar à aba. O detalhe continua sendo a fonte viva.

## 6. O que não será provado e por quê

| O quê | Por quê | Quando |
|---|---|---|
| Cumprimento, cancelamento, reembolso e disputa (resto do FR-020) | Esses estados não existem no Pedido ainda | #36 (estorno) e tickets seguintes |
| Pedido expirado no e2e | O Pix vale 30 minutos; o expirado é provado na integração (Pedido por SQL) e no teste de componente | — |
| "Nunca chama o Asaas" contra o Asaas de verdade | A integração prova com o `SwitchablePixGateway` (zero chamadas); a lista não tem o gateway no caminho | — |
| Leitor de tela de verdade | O `verificar-tela` confere papéis, nomes e teclado, não a fala | Conferência manual, se quiser |
| Muitos Pedidos (desempenho) | Volume da Fase 1 é baixo | #44 |

## 7. Decisões que dependem do Sérgio

| # | Decisão | Recomendação | Decidido |
|---|---|---|---|
| D1 | Pendente mostra a hora-limite fixa ("Pix válido até 14:35.") e o link "Ver o Pix"; a contagem viva fica só no detalhe | Aceitar (um relógio por item exigiria timer e anúncios na lista) | Aceita |
| D2 | Expirado sem link (o detalhe só repetiria "Este Pix expirou") | Aceitar. Alternativa: link "Ver o Pedido" | Aceita (sem link) |
| D3 | Lista sem polling: atualiza ao abrir e ao voltar à aba | Aceitar | Aceita |
| D4 | Lista sem paginação, do mais recente para o mais antigo, sem índice novo | Aceitar (igual à Biblioteca) | Aceita |
| D5 | E2E dentro do teste FR-021 existente (título ganha `FR-020 SEC-011`), em vez de um teste novo que publicaria outro Produto | Aceitar (economiza um login de Administrador e uma compra) | Aceita |
| D6 | Todos os textos da seção 4 | Aprovar ou corrigir | Aprovados sem mudança |

Regras adicionais do Sérgio na aprovação: a lista nunca devolve o código Pix nem o id da cobrança no Asaas, nunca chama o Asaas e nunca muda o estado de um Pedido; no `OrderRouteAccessTest`, a rota negada que entra no lugar de `get(ORDERS)` é uma rota de `/api/orders` que continua sem existir (`POST /api/orders/{id}`), para o teste não ficar mais fraco.
