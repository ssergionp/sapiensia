# Lista de Pedidos do Administrador (#37) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** APROVADO pelo Sérgio em 2026-11-15: D1 a D6 aceitas como recomendado (ver seção 7), textos da seção 4 sem mudança. Condições da aprovação na seção "Condições da aprovação".

**Goal:** O Administrador abre "Pedidos" no painel e vê todos os Pedidos, do mais recente para o mais antigo, em páginas de 20, com o estado de cada um. Só leitura. O Cliente e o anônimo são negados.

**Architecture:** Uma rota de leitura nova, `GET /api/admin/orders`, num controller novo do pacote `payment`, aberta na `SecurityConfig` só por uma capacidade nova (`ORDERS_VIEW`) mais os dois fatores (`panel(...)`). Um `select` em `orders` + `products` + `customers`, paginado no SQL; o e-mail do Cliente é mascarado já na leitura da linha, então o e-mail completo nunca chega ao DTO. O estado mostrado é a mesma regra `shownStatus` do `OrderService` (pendente vencido = expirado), mas calculada no próprio SQL, para o filtro, a página e a contagem concordarem. No frontend, uma pasta nova `features/admin-orders/`, a rota `/painel/pedidos` e um link no painel.

**Tech Stack:** o que já existe (Spring Boot 4.1 com `JdbcClient`, Testcontainers; React, TanStack Query, Zod, Vitest, Playwright). **Nenhuma dependência nova, nenhuma migration.**

**Spec:** `docs/spec/sapiensia-spec.md` (FR-020, SEC-010; também tocam SEC-013, SEC-015 e a minimização de dados das linhas 83 e 843); issue #37 (pai #5); `GLOSSARY.md` (Pedido, Administrador); plano do #25 (`2026-11-14-25-meus-pedidos.md`, mesma regra de estado) e do #17 (lista de Produtos do painel, mesmo formato de página).

## IDs cobertos

| ID | Como | Onde |
|---|---|---|
| FR-020 | Só a leitura dos estados que existem hoje (pendente, pago, expirado), agora para o Administrador. Reembolso, disputa e cancelamento não existem ainda (#36) | Tasks 1, 2, 3 |
| SEC-010 | Rota negada por padrão, aberta só com `ORDERS_VIEW` e os dois fatores; anônimo 401, Cliente 403, Administrador sem a capacidade 403; os outros verbos e `/api/admin/orders/{id}` continuam negados | Tasks 1, 3 |

## Global Constraints

- A rota entra na `SecurityConfig` antes do `denyAll`: só `GET /api/admin/orders`, só `panel(Capability.ORDERS_VIEW)`. Nenhum `permitAll`.
- A lista **nunca** chama o `PixGateway` e **nunca** grava no banco (nem `markExpired`, nem auditoria: D5).
- A resposta tem exatamente estes campos por item: `id`, `productTitle`, `priceCents`, `status`, `customerEmail` (mascarado), `createdAt`, `paidAt`. **Nunca** CPF, código Pix, `provider_payment_id`, id do Cliente, id do Produto, e-mail completo nem dado do webhook.
- Nenhuma linha de log com e-mail (nem mascarado).
- Os pedaços de `where` do filtro são constantes escolhidas por `switch`; nada vindo da requisição é concatenado no SQL (SEC-013).
- Controller e repositório package-private, DTOs como `record`. ID do requisito no `@DisplayName`/título do teste. `@Import(TestcontainersConfiguration.class)`, sem `@MockitoBean`.
- Toda chamada do frontend via `apiFetch`. Textos de tela **só os da seção 4**.
- Memória (issue #126): o check que vale é o do CI. Localmente só `just lint` e as rodadas focadas da seção 5, **um comando pesado por vez**.
- Push só com `git push -u origin feat/37-pedidos-admin` (OK dado na aprovação; ver abaixo).

## Condições da aprovação (Sérgio, 2026-11-15)

- **Filtro e paginação concordam:** o estado mostrado (pendente vencido = `EXPIRED`) é calculado **no próprio SQL** (`case when o.status = 'PENDING' and o.expires_at <= :now then 'EXPIRED' else o.status end`), e o filtro usa essa mesma expressão. A página e o `count(*)` recebem o **mesmo** `now` (um só `clock.instant()` por requisição). Há um teste que prova isso (Task 1, teste 7).
- **Máscara no servidor:** o e-mail inteiro nunca sai na resposta nem no log.
- **Asserções de capacidades** (`AdminMfaLoginTest`, `AdminSessionTest`): só ganham `ORDERS_VIEW`; nada mais muda nelas.
- **Localmente** só lint e testes focados (incluindo as classes que citam FR-020 e SEC-010), um comando pesado por vez; o check que vale é o do CI.
- Antes de declarar pronto: skill `verificar-entrega`, com o CI no lugar do check completo.
- Push e PR (citando FR-020 e SEC-010) sem parar para pedir OK; com o CI verde, resumo ao Sérgio.

## Review Focus

1. **Pendente vencido e o filtro:** um Pedido `PENDING` com o prazo vencido aparece como expirado; o filtro "Expirado" tem que trazê-lo e o filtro "Aguardando pagamento" não. Teste 5 da Task 1.
2. **Vários Pedidos com a mesma data de criação:** as páginas não repetem nem pulam linha (desempate pelo id). Teste 3 da Task 1.
3. **Produto despublicado depois da compra:** o Pedido continua na lista com o título. Teste 6 da Task 1.
4. **Trocar o filtro estando na página 3:** volta para a página 1; senão a tela mostraria "vazio" com Pedidos existindo. Teste da Task 2.
5. **Título longo e e-mail de domínio longo em 390 px:** quebram dentro da célula; a página não ganha rolagem horizontal (só a região da tabela rola). `/verificar-tela` na Task 3.

---

## 1. Decisões técnicas

**a) Capacidade e acesso (D1).** `Capability.ORDERS_VIEW`, acrescentada no fim do enum. Separada das de Produto para que um perfil futuro cuide do catálogo sem ver quem comprou. Como entra no Administrador que já existe: o login completo dá `EnumSet.allOf(Capability.class)` (`AdminMfaController`), então ele a recebe no próximo login, sem migration; as sessões ficam em memória e acabam no deploy. Negada por padrão: sem a regra nova, `/api/admin/orders` cai no `denyAll`. Regra: `.requestMatchers(HttpMethod.GET, "/api/admin/orders").access(panel(Capability.ORDERS_VIEW))`. Respostas: anônimo e Administrador só com a senha 401; Cliente 403; Administrador sem `ORDERS_VIEW` ou com um fator só 403.

**b) Campos (D2, D3).** `GET /api/admin/orders?page=0&status=PAID` → `200 {"items":[{...}],"page":0,"totalPages":3}`.
- `id`: o UUID do Pedido. A tela mostra só os 8 primeiros caracteres (D3).
- `productTitle`, `priceCents`.
- `status`: o estado mostrado, pela mesma regra `shownStatus` (pendente vencido sai `EXPIRED`), calculada no SQL.
- `createdAt`; `paidAt` (nulo se não pago).
- `customerEmail`: **mascarado no servidor** (D2): primeira letra + `***` + `@domínio`, ex.: `s***@gmail.com`. Sempre três asteriscos, para não revelar o tamanho. Sem `@` ou sem nada antes dele: `***`. Por que mostrar: sem nenhuma pista do Cliente a lista não serve para atender um pedido de suporte nem para distinguir "um Cliente comprou 3 vezes" de "3 Clientes". Por que mascarar: para isso o e-mail completo não é necessário (minimização, spec linhas 83 e 843), e mascarando no servidor ele não trafega nem fica no cache do navegador.

**c) Paginação e filtro (D4).** Tamanho fixo de 20 (`Orders.ADMIN_PAGE_SIZE`, sem parâmetro de tamanho: SEC-015), `page` a partir de 0 com `@Min(0)`, ordem `o.created_at desc, o.id desc`, `offset` como `long`. Página além do fim: lista vazia. `page` negativo ou não numérico: 400. Filtro opcional `status` (`PENDING`, `PAID`, `EXPIRED`; valor desconhecido: 400), aplicado sobre o estado **mostrado**:

| Filtro | `where` |
|---|---|
| nenhum | (sem `where`) |
| `PAID` | `o.status = 'PAID'` |
| `PENDING` | `o.status = 'PENDING' and o.expires_at > :now` |
| `EXPIRED` | `(o.status = 'EXPIRED' or (o.status = 'PENDING' and o.expires_at <= :now))` |

O mesmo `where` vale para o `count(*)` do `totalPages`. **Índice: não.** No volume da Fase 1 a leitura sequencial com ordenação basta, e o `count(*)` percorre a tabela de qualquer jeito. Se um dia precisar: `V24__orders_created_at_index.sql` com `create index orders_created_at_id on orders (created_at desc, id desc)`.

**d) Só leitura e auditoria (D5).** O caminho não recebe o `PixGateway` nem o `expirer` e não faz `update`. **A consulta não fica auditada.** Motivos: a trilha hoje registra só ações que mudam algo (nenhuma leitura do painel é auditada, nem a lista de Produtos); a lista mostra só dado mascarado; há um único Administrador; cada troca de página, de filtro e cada volta do foco à aba viraria uma linha de ruído; e gravar auditoria num `GET` amarraria a leitura à disponibilidade da escrita. Revisitar quando existir um segundo perfil de Administrador ou uma tela com o dado completo de um Cliente (aí a auditoria é por objeto visto). Sem ADR: a decisão fica neste plano.

**e) Código.**
- `payment/MaskedEmail.java`: `static String of(String email)`.
- `payment/Orders.java`: `static final int ADMIN_PAGE_SIZE = 20`; `record AdminRow(UUID id, String productTitle, int priceCents, OrderStatus shownStatus, String maskedEmail, Instant createdAt, Instant paidAt)` (o `shownStatus` já vem calculado pelo SQL, ver "Condições da aprovação"); `List<AdminRow> pageForAdministrator(OrderStatus shown, Instant now, int page)`; `long countForAdministrator(OrderStatus shown, Instant now)`. O mapper chama `MaskedEmail.of(rs.getString("email"))`: nenhum objeto carrega o e-mail completo. Join com `products` sem filtro de estado do Produto.
- `payment/AdminOrderItem.java`: `record AdminOrderItem(UUID id, String productTitle, int priceCents, OrderStatus status, String customerEmail, Instant createdAt, Instant paidAt)`; `payment/AdminOrderPage.java`: `record AdminOrderPage(List<AdminOrderItem> items, int page, int totalPages)`.
- `payment/OrderService.java`: novo `AdminOrderPage listForAdministrator(int page, OrderStatus shown)`, um só `clock.instant()` para a página e a contagem.
- `payment/AdminOrderController.java`: `@RequestMapping("/api/admin/orders")`, `@GetMapping AdminOrderPage list(@RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(required = false) OrderStatus status)`.

## 2. Arquivos

- Criar (backend): `payment/MaskedEmail.java`, `AdminOrderItem.java`, `AdminOrderPage.java`, `AdminOrderController.java`
- Modificar (backend): `admin/Capability.java`, `security/SecurityConfig.java` (1 regra + comentário), `payment/Orders.java`, `payment/OrderService.java`
- Testes (backend): criar `payment/MaskedEmailTest.java`, `admin/AdminOrderListTest.java`, `admin/AdminOrderAccessTest.java`, `admin/AdminOrderFixtures.java`; modificar `admin/AdminMfaLoginTest.java:121` e `admin/AdminSessionTest.java:124,409` (a lista de capacidades esperada ganha `"ORDERS_VIEW"` no fim)
- Criar (frontend): `src/features/admin-orders/{schemas,api,hooks,messages}.ts`, `AdminOrdersPage.tsx`, `AdminOrdersPage.test.tsx`, `src/features/admin/AdminHomeOrdersLink.test.tsx`
- Modificar (frontend): `src/App.tsx` (rota `/painel/pedidos`), `src/features/admin/AdminHomePage.tsx` (link), `e2e/admin-panel.e2e.ts`
- Docs: `CLAUDE.md` ("Reaproveitar antes de criar": `MaskedEmail` é o único jeito de mostrar o e-mail de um Cliente no painel)

## 3. Tela (D6)

`/painel/pedidos`, dentro de `<AdminGuard capability="ORDERS_VIEW" missingMessage=...>`. Entrada no menu: link "Pedidos" no painel, logo abaixo de "Produtos", visível só com `ORDERS_VIEW`.

- **Formato:** tabela, como a de Produtos, com as colunas Pedido (8 primeiros caracteres do id), Produto, Valor, Estado, Cliente, Criado em, Pago em. Datas em `dd/MM/aaaa HH:mm`, fuso de São Paulo, dentro de `<time dateTime>`.
- **Filtro:** um `<select>` com rótulo, acima da tabela; trocar o filtro volta para a página 1.
- **Carregando:** "Carregando…" com `role="status"`. Ao trocar de página ou filtro a tabela anterior fica na tela até a nova chegar (`keepPreviousData`, como em Produtos).
- **Erro:** mensagem com `role="alert"` e o botão "Tentar novamente". 401: a sessão do painel acabou, vai para `/painel/entrar` (mesmo mecanismo de Produtos). 403: a mensagem de sem permissão.
- **Vazio:** um texto sem filtro e outro com filtro; o filtro continua na tela nos dois casos.
- **Celular (390 px):** a tabela fica numa região com rolagem horizontal própria (`overflow-x-auto`); a página não rola de lado. Título e e-mail quebram com `overflow-wrap: anywhere`.
- **Acessibilidade:** `<h1>` recebe o foco quando a lista carrega; `<th scope="col">`; a região rolável tem `role="region"`, `aria-labelledby` do `<h1>` e `tabIndex={0}` (rola pelo teclado); `<nav aria-label="Paginação">`; "Página X de Y" em `aria-live="polite"`; título da aba próprio.

## 4. Textos novos (pt-BR), para aprovação

| Onde | Texto |
|---|---|
| Link no painel | Pedidos |
| Título da página (`<h1>`) | Pedidos |
| Título da aba | Pedidos \| SapiensIA |
| Administrador sem a capacidade | Você não tem permissão para ver os Pedidos. |
| Rótulo do filtro | Filtrar por estado |
| Opção do filtro sem estado | Todos |
| Colunas | Pedido · Produto · Valor · Estado · Cliente · Criado em · Pago em |
| Célula "Pago em" de Pedido não pago | — |
| Lista vazia, sem filtro | Nenhum Pedido ainda. |
| Lista vazia, com filtro | Nenhum Pedido neste estado. |
| Erro ao carregar | Não foi possível carregar os Pedidos agora. Tente novamente. |

Reaproveitados, já aprovados em outras telas (sem mudança): "Carregando…", "Tentar novamente", "Voltar ao painel", "Anterior", "Próxima", "Página X de Y", "Paginação"; estados "Aguardando pagamento", "Pago", "Expirado" (os mesmos de Meus Pedidos, importados de `features/orders/messages`).

## 5. Tasks

### Task 1: API da lista (backend)

**Interfaces:** Produces: `GET /api/admin/orders` com o contrato da seção 1b/1c; `Capability.ORDERS_VIEW`.

As duas classes de integração usam **as mesmas anotações**, para dividirem um só contexto Spring (memória): `@Import({TestcontainersConfiguration.class, AdminMfaTestSupport.ClockConfig.class, SwitchablePixGatewayConfig.class})` e `@SpringBootTest(webEnvironment = RANDOM_PORT, properties = {"test.isolated-context=admin-orders", "app.login-limit.ip-max-attempts=1000"})`. Ficam no pacote de teste `admin` (o `AdminBrowser` e o `AdminProductFixtures` são de lá; mesmo precedente do `LibraryAuthorizationTest`). Datas dos Pedidos inseridos saem do relógio injetado (`clock.instant()`), não do `now()` do banco. `@BeforeEach`: `delete from orders`.

- [ ] **Step 1: testes que falham.**
  - `MaskedEmailTest` (sem Spring), `@DisplayName("FR-020: ...")`: `sergio@gmail.com` → `s***@gmail.com`; `a@x.com` → `a***@x.com`; `semarroba` → `***`; `@x.com` → `***`.
  - `AdminOrderListTest` (login de verdade com `AdminBrowser.loginAdminFully`):
    1. `FR-020 SEC-010: o Administrador vê os Pedidos do mais recente para o mais antigo, só com os campos permitidos`: três Pedidos (pago, expirado, pendente) de dois Clientes; confere a ordem, os valores, `paidAt` só no pago; as chaves de cada item são exatamente as 7 da seção 1b; o corpo não contém `pay_`, nenhum e-mail completo, nenhum id de Cliente ou de Produto.
    2. `FR-020: o e-mail do Cliente sai mascarado`: `maria.silva@example.com` → `m***@example.com`.
    3. `FR-020 SEC-015: 20 por página, sem repetir nem pular com a mesma data de criação`: 45 Pedidos `EXPIRED` com o mesmo `created_at` (o índice único só deixa um pendente por Cliente e Produto); páginas 0, 1 e 2 com 20, 20 e 5; os 45 ids distintos; `totalPages` 3; página 3 vazia; `page=-1` e `page=abc` dão 400.
    4. `FR-020: Pedido pendente vencido aparece como expirado, nada é gravado e o Asaas não é chamado`: no banco continua `PENDING` com `expired_at` nulo; `gateway.calls()` não cresce.
    5. `FR-020: o filtro usa o estado mostrado`: um pendente no prazo, um pendente vencido, um expirado, um pago → `PENDING` traz 1, `EXPIRED` traz 2, `PAID` traz 1, e `totalPages` segue o filtro; `status=REFUNDED` dá 400.
    6. `FR-020: o Pedido de um Produto despublicado continua na lista`.
    7. `FR-020 SEC-015: filtro, estado mostrado e contagem usam o mesmo agora`: relógio fixo em T; 20 Pedidos `EXPIRED` e 1 `PENDING` com `expires_at = T` exato (vence no limite). `status=EXPIRED` → `totalPages` 2, e a página 1 traz esse Pedido com `status` `EXPIRED`; `status=PENDING` → lista vazia e `totalPages` 0; sem filtro → 21 linhas em 2 páginas, nenhuma `PENDING`. Avançar o relógio não é preciso: o ponto é o limite `expires_at <= now` valer igual na página e na contagem.
  - `AdminOrderAccessTest`:
    1. `SEC-010: anônimo recebe 401 e Cliente recebe 403, sem nenhum dado` (logins de verdade; o corpo não tem o título do Produto).
    2. `SEC-010 SEC-001: só a senha, sem o código, recebe 401`.
    3. `SEC-010: todas as capacidades menos ORDERS_VIEW, com os dois fatores, recebe 403` (sessão montada à mão com MockMvc, como no `AdminProductCapabilityTest`).
    4. `SEC-010: ORDERS_VIEW só com a senha, ou só com o TOTP, recebe 403`.
    5. `SEC-010: ORDERS_VIEW com os dois fatores recebe 200`.
    6. `SEC-010: POST, PUT, DELETE e /api/admin/orders/{id} seguem negados (403) até para o Administrador completo`.
- [ ] **Step 2:** rodada A (seção 5.1): falha por 403/compilação.
- [ ] **Step 3:** implementar a seção 1e, a capacidade e a regra; atualizar as três listas de capacidades esperadas.
- [ ] **Step 4:** rodadas A, B, C1 e C2 verdes, uma por vez; `just lint`.
- [ ] **Step 5:** commit `feat: order list for the Administrator (#37)`.

**5.1 Rodadas focadas** (dentro de `backend/`, uma por vez):
- A: `.\mvnw.cmd test "-Dtest=MaskedEmailTest,AdminOrderListTest,AdminOrderAccessTest"`
- B (lista de capacidades e negação por padrão): `.\mvnw.cmd test "-Dtest=AdminMfaLoginTest,AdminSessionTest,AdminRouteDenyByDefaultTest"`
- C1 (as outras classes que citam FR-020 ou SEC-010, fora de `admin`; inclui quem usa o `shownStatus`): `.\mvnw.cmd test "-Dtest=PublicCatalogMatcherTest,CatalogTest,PaidOrderReadTest,OrderRouteAccessTest,LibraryTest,LocalDownloadProdTest,LocalDownloadTest,MyOrdersTest,OrderCreationTest,AsaasWebhookRouteVariantsTest,AsaasWebhookAuthenticationTest,LoginRequestTest"`
- C2 (as que citam FR-020 ou SEC-010 em `admin`, fora da B): `.\mvnw.cmd test "-Dtest=CatalogCoverTest,LibraryAuthorizationTest,LibraryDownloadAuthorizationTest,AdminProductPublicationTest,AdminProductAuthorizationTest,AdminProductCapabilityTest,AdminProductCoverTest,AdminProductFileAuthorizationTest,AdminProductFileCapabilityTest,AdminProductFileUploadTest,AdminProductPublicationAuthorizationTest,AdminProductPublicationCapabilityTest,AdminLoginTimingTest,AdminMfaBypassTest"`

(Lista tirada de `git grep -l -E "FR-020|SEC-010" -- backend/src/test` em 2026-11-15; pedido do Sérgio na aprovação.)

### Task 2: Tela e link no painel (frontend)

**Interfaces:** Consumes: o contrato da Task 1. Produces: `useAdminOrders(page: number, status: OrderStatus | null)`, query key `["admin-orders", page, status]`; rota `/painel/pedidos`.

- [ ] **Step 1: testes que falham** (`AdminOrdersPage.test.tsx`, `fetch` simulado como nos testes de Produtos): mostra as linhas (8 caracteres do id, título, `R$ 29,90`, estado em português, e-mail mascarado, datas formatadas, "—" sem pagamento); "Carregando…"; vazio sem filtro e com filtro; erro e "Tentar novamente" refaz a busca; 403 mostra a mensagem de sem permissão; 401 leva a `/painel/entrar`; "Próxima" pede `page=1`; trocar o filtro pede `status=PAID&page=0` estando na página 2; sem `ORDERS_VIEW` a página mostra a mensagem e não chama a API de Pedidos. `AdminHomeOrdersLink.test.tsx`: o link "Pedidos" aparece com `ORDERS_VIEW` (`href="/painel/pedidos"`) e some sem ela.
- [ ] **Step 2:** `pnpm exec vitest run src/features/admin-orders src/features/admin/AdminHomeOrdersLink.test.tsx` (em `frontend/`): falha.
- [ ] **Step 3:** implementar. `schemas.ts` espelha `AdminOrderPage` (`paidAt` anulável; `status` como enum dos três estados); `api.ts` com `AdminOrdersSessionEndedError` (401) e `AdminOrdersForbiddenError` (403); textos só em `messages.ts`.
- [ ] **Step 4:** o mesmo comando verde, mais `src/App.test.tsx`, `src/features/orders/OrdersPage.test.tsx` e `src/features/catalog/CatalogProductPage.test.tsx` (citam FR-020/SEC-010); `just lint`.
- [ ] **Step 5:** commit `feat: Orders page in the Administrator panel (#37)`.

### Task 3: E2E, sistema de verdade e documentação

- [ ] **Step 1: E2E** em `frontend/e2e/admin-panel.e2e.ts`, sem teste novo:
  - no teste `FR-020 FR-021 FR-024 ...: o pagamento confirmado pelo Asaas libera o Acesso` (o Administrador já está logado em `page`): antes do evento, `/painel` → link "Pedidos" → a linha do Produto mostra "Aguardando pagamento"; depois da confirmação, recarrega e a linha mostra "Pago", "R$ 29,90" e o e-mail mascarado do comprador; o e-mail completo não aparece na página.
  - no teste `SEC-010 FR-016: sem sessão, as rotas de Produto do painel negam`: `GET /api/admin/orders` dá 401 (o título ganha "e de Pedidos" e `FR-020`).
- [ ] **Step 2:** `/verificar-tela` em `/painel/pedidos`: 1280 px e 390 px, teclado (filtro, região da tabela, paginação), sem rolagem horizontal da página.
- [ ] **Step 3: sistema de verdade**, com os testes do Maven parados: `just db` na pasta principal; `just dev-back` e `just dev-front` na worktree; `pnpm exec playwright test admin-panel -g "SEC-001|pagamento confirmado|sem sessão"`. Porta ocupada por outro projeto: perguntar antes de parar.
- [ ] **Step 4:** linha nova no `CLAUDE.md` (seção 2); commit `test: e2e and docs for the Administrator's order list (#37)`.
- [ ] **Step 5:** skill `verificar-entrega` (o CI no lugar do check completo); push e PR com `Closes #37`, citando FR-020 e SEC-010 (OK dado na aprovação). O ticket só está pronto com o CI verde.

## 6. O que não será provado

- **O check completo local:** não roda (issue #126). Quem prova a suíte inteira é o CI da PR.
- **Volume:** nenhum teste de carga. Paginação por `offset` e `count(*)` a cada página servem ao volume da Fase 1; não há índice.
- **Pedido novo chegando durante a navegação:** com `offset`, um Pedido criado entre duas páginas empurra uma linha para a página seguinte (ela aparece duas vezes). Aceito; não testado.
- **A máscara não esconde o domínio:** um e-mail de domínio próprio (`joao@joaosilva.com.br`) ainda identifica a pessoa.
- **Sessão do Administrador aberta antes do deploy:** não ganha a capacidade até o próximo login. Como as sessões ficam em memória, o deploy as encerra; isso não tem teste.
- **Perfil sem `ORDERS_VIEW`:** só existe em sessão montada à mão nos testes; não há tela nem dado que crie esse perfil.
- **Leitor de tela de verdade:** só papéis ARIA, teclado e foco (`/verificar-tela`).
- **Celular no E2E:** o E2E do painel roda só no projeto desktop; os 390 px são conferidos no `/verificar-tela`, com a API simulada.
- **Estados que ainda não existem** (reembolso, disputa, cancelamento: #36) e o **Asaas de verdade** (o caminho não o chama; os testes usam o `FakePixGateway`).
- **Quem viu a lista:** sem auditoria da leitura (D5), não há registro.

## 7. Decisões que dependem do Sérgio

| # | Decisão | Recomendado | Alternativa |
|---|---|---|---|
| D1 | Nome da capacidade | `ORDERS_VIEW` | outro nome |
| D2 | Como o Cliente aparece | e-mail mascarado no servidor (`s***@gmail.com`) | não mostrar nada do Cliente |
| D3 | Id do Pedido | a API manda o id inteiro e a tela mostra os 8 primeiros caracteres (o id não é segredo: o Cliente o vê no endereço do próprio Pedido) | a API manda só os 8 caracteres |
| D4 | Filtro por estado | incluir (umas 15 linhas no backend, um `<select>` e 2 testes) | cortar e ficar só com a paginação |
| D5 | Auditar a consulta | não auditar agora (seção 1d) | gravar `ADMIN_ORDERS_VIEWED` a cada requisição, sem derrubar a leitura se a gravação falhar |
| D6 | Formato da tela | tabela com rolagem própria no celular, como Produtos | cartões empilhados, como Meus Pedidos |
| Textos | seção 4 | como listados | os seus ajustes |

**Decididas em 2026-11-15:** D1 `ORDERS_VIEW`; D2 e-mail mascarado no servidor; D3 id inteiro na API, 8 caracteres na tela; D4 filtro incluído; D5 consulta sem auditoria; D6 tabela com rolagem própria; textos da seção 4 aprovados sem mudança.
