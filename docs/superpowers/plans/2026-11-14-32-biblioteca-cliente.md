# Biblioteca do Cliente (#32) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Implementation subagents run on Sonnet.

**Status:** APROVADO pelo Sérgio em 14/11/2026, com D1 a D7 como recomendado e sem ajustes (D5 vira issue `needs-triage`). O risco R1 já está registrado no #36; a nota fica também no comentário da V21.

**Goal:** O Cliente logado abre a Biblioteca e vê os Produtos que comprou (título, capa, data do Acesso). Produto despublicado continua lá; Acesso cancelado some. Nunca aparece dado de outro Cliente.

**Architecture:** Uma rota de leitura, `GET /api/library`, que faz um `select` em `accesses` juntando `products` e `product_covers`, filtrado pelo Cliente da sessão e por `cancelled_at is null`. A capa vem de uma rota própria da Biblioteca, `GET /api/library/{productId}/cover`, que confere o Acesso ativo do Cliente em vez de "Produto publicado" e reaproveita o `CoverResponses.serve` do #21. Uma coluna nova `accesses.cancelled_at` (nula = ativo) deixa a regra de cancelamento pronta para o #36 sem quebrar nada. No frontend, a feature `library` (api, schemas, hooks, messages, `LibraryPage`) na rota `/biblioteca`.

**Tech Stack:** o que já existe (Spring Boot 4.1 com `JdbcClient`, Flyway, Testcontainers; React 19, TanStack Query, Zod, Vitest, Playwright). **Nenhuma dependência nova.**

**Spec:** `docs/spec/sapiensia-spec.md` (FR-025, FR-029, SEC-011); issue #32 (pai #5); `docs/adr/0007-modelo-comercial-da-fase-1.md`; `GLOSSARY.md` (Acesso, Biblioteca, Capa, Despublicado); migrations V18 (`product_covers`) e V20 (`accesses`); plano do #28 (decisões D3 e D8).

## IDs cobertos

| ID | Como | Onde |
|---|---|---|
| FR-029 | Lista os Produtos adquiridos, com título, capa e data do Acesso; despublicado continua listado. "Versão disponível, progresso e favoritos" ficam de fora (seção 6) | Tasks 2, 3, 4, 6 |
| FR-025 | Só a parte de leitura: Acesso cancelado (`cancelled_at` preenchido) some da Biblioteca e a capa dele dá 404. Quem preenche a coluna é o #36 | Tasks 1, 2, 3 |
| SEC-011 | Outro Cliente nunca vê o item nem a capa: o filtro está no SQL; teste negativo nas duas rotas | Tasks 2, 3, 6 |
| ADR-0007 | "Despublicar não tira o Acesso" vale também para a Biblioteca e a capa | Tasks 2, 3, 7 |

## Global Constraints

- Rotas novas, liberadas na `SecurityConfig` antes do `denyAll`, só `GET` e só `hasRole("CUSTOMER")`: `/api/library` e `/api/library/*/cover`. Nenhum `permitAll`. Anônimo 401, Administrador 403.
- Migration `V21__access_cancellation.sql` pela skill `nova-migration`. Nenhuma migration existente é editada.
- Controllers e repositórios package-private, DTOs como `record`; `CurrentCustomer.id(authentication)`. Teste de requisito com o ID no `@DisplayName`/título. `@Import(TestcontainersConfiguration.class)`, sem `@MockitoBean` em integração.
- Toda chamada do frontend via `apiFetch`. Textos de tela **só os da seção 4**.
- Teste focado: incluir as classes que citam os IDs tocados (`git grep -l -E "FR-025|FR-029|SEC-011" -- backend/src/test`) e as de capa (`CatalogCoverTest`, `ProductCoverControllerTest`).
- Um comando pesado por vez, com 2 GB livres antes. Só `just check-rapido` durante o trabalho.
- Push só com `git push -u origin feat/32-biblioteca-cliente`, depois do OK.

## 1. Decisões técnicas

**a) Rota da lista.** `GET /api/library` → `200 {"items": [{"productId", "title", "grantedAt", "coverVersion"}]}`, ordenado por `granted_at desc` (empate pelo título). `coverVersion` é nulo quando o Produto não tem capa. Não sai preço, estado do Produto, id do Pedido nem id do Acesso: a tela não usa. Sem paginação: na Fase 1 um Cliente tem poucos Produtos (D4). `Cache-Control: no-store` (padrão). Pacote novo `library` (`LibraryController`, `LibraryItems` com `JdbcClient`).

SQL (o filtro de dono e de cancelamento fica no banco, nunca em memória):
```sql
select a.product_id, p.title, a.granted_at, c.version as cover_version
from accesses a
join products p on p.id = a.product_id
left join product_covers c on c.product_id = a.product_id
where a.customer_id = :customerId and a.cancelled_at is null
order by a.granted_at desc, p.title
```
Sem filtro de `p.status`: Despublicado continua listado (ADR-0007).

**Acesso cancelado sem o #36:** a V21 só faz `alter table accesses add column cancelled_at timestamptz` (nula; nenhum valor padrão, nenhum dado mexido). Hoje ninguém a preenche, então todo Acesso existente continua ativo e o `insert` do `PaymentConfirmation` não muda. O #36 só precisa gravar `cancelled_at` (e o motivo, se quiser) e decidir o que fazer com o `unique (customer_id, product_id)` e com o `Accesses.exists` (que hoje bloqueia comprar de novo um Produto com Acesso cancelado); isso fica anotado no comentário da migration e na issue #36. Os testes deste ticket preenchem a coluna por SQL.

**b) Capa na Biblioteca (decisão D1).** Rota própria, `GET /api/library/{productId}/cover`, em vez de abrir a rota pública:
- Acha a capa com `ProductCovers.findOwned(customerId, productId)`: `product_covers` junto com `accesses` do Cliente da sessão e `cancelled_at is null`, **sem** olhar o estado do Produto. Sem Acesso ativo, sem capa ou Produto de outro Cliente: o mesmo `404` sem corpo.
- Responde com o mesmo `CoverResponses.serve` do #21 (`Content-Type` dos bytes, `nosniff`, `ETag` = versão, `Cache-Control: private, no-cache`, 304 só depois de conferir o Acesso). Por isso o controller fica no pacote `product` (`LibraryCoverController`), onde o `CoverResponses` é visível; nada passa a ser público.
- **Por quê:** a rota pública só serve Produto publicado de propósito (ADR-0007, #21), para um Produto despublicado sumir na hora para o Visitante. Abrir uma exceção nela misturaria a regra do Catálogo com a do Acesso e exporia a capa a quem não comprou. A rota da Biblioteca vale só para o dono e reaproveita o código de resposta, então é pouco código novo. A alternativa mais simples (mostrar a capa só quando o Produto está publicado e o desenho neutro quando não está) foi descartada porque o Produto "mudaria de cara" para quem comprou.
- A tela usa sempre a rota da Biblioteca (publicado ou não): um caminho só, `?v=<versão>` como no Catálogo.

**c) Proteção da página.** Não existe guarda de Cliente no frontend; a `LibraryPage` copia o padrão da `OrderPage`: `401` → `NotLoggedInError` → `rememberReturnTo("/biblioteca")` e `navigate("/entrar")`, voltando à Biblioteca depois do login. `403` (Administrador logado) cai no estado de erro.

**d) Como o #33 encaixa o Download.** Cada item é um `<li>` com capa, título, data e, no fim, um espaço de ações (`<div data-slot="actions">`) que hoje fica vazio e não aparece. O #33 coloca ali o botão "Baixar" (e a mensagem de limite), chamando a sua própria rota em `/api/library/{productId}/...`; nem a lista nem o layout mudam. Até lá a tela mostra só capa, título e data (D2).

## 2. Arquivos

- Criar: `backend/src/main/resources/db/migration/V21__access_cancellation.sql`
- Criar: `backend/.../library/LibraryController.java`, `LibraryItems.java`, `LibraryItem.java` (record)
- Criar: `backend/.../product/LibraryCoverController.java`; Modificar: `ProductCovers.java` (`findOwned`), `ProductCoverService.java` (`ownedCover`)
- Modificar: `backend/.../security/SecurityConfig.java` (2 linhas + comentário)
- Testes: `backend/src/test/.../library/LibraryTest.java`, `.../product/LibraryCoverTest.java`, `.../payment/AccessCancellationSchemaTest.java`
- Criar: `frontend/src/features/library/{api.ts,schemas.ts,hooks.ts,messages.ts,LibraryPage.tsx,LibraryPage.test.tsx}`; Modificar: `frontend/src/App.tsx` (rota `/biblioteca`)
- Modificar: `frontend/src/features/session/HomePage.tsx` (+ teste), `frontend/src/features/orders/OrderPage.tsx` e `messages.ts` (+ teste)
- Modificar: `frontend/e2e/admin-panel.e2e.ts` (um teste novo)
- Modificar: `GLOSSARY.md` (Capa e Acesso), `docs/adr/0007-modelo-comercial-da-fase-1.md` (consequência "Biblioteca (#32)")

## 3. Tarefas

- [ ] **Task 1 — Migration V21** (skill `nova-migration`). `AccessCancellationSchemaTest`: `cancelled_at` existe e é nula por padrão; um Acesso concedido pelo `PaymentConfirmation` continua com `cancelled_at` nulo (o `PaymentConfirmationTest` existente continua verde). Commit `feat: access cancellation column (#32)`.
- [ ] **Task 2 — `GET /api/library`** (TDD). `LibraryTest` (Testcontainers, `OrderHttp.newCustomer`, `OrderTestSupport.insertPublishedProduct`, Pedido pago + Acesso por SQL como no `PaidOrderReadTest`):
  - `FR-029: shouldListTheProductsTheCustomerBought` (título, `grantedAt`, `coverVersion`; mais recente primeiro)
  - `FR-029: shouldKeepAnUnpublishedProductInTheLibrary`
  - `FR-025: shouldHideACancelledAccess`
  - `FR-029: shouldAnswerAnEmptyListForACustomerWithoutAccesses`
  - `FR-029, SEC-011: shouldNeverListAnotherCustomersAccesses` (dois Clientes, cada um vê só os seus; a resposta não contém o id do Produto do outro)
  - `FR-029: shouldNotExposeOrderPriceOrProductStatus` (só as 4 chaves)
  - `shouldRefuseAnonymousWith401AndAdministratorWith403`
  Commit `feat: customer library listing (#32)`.
- [ ] **Task 3 — `GET /api/library/{productId}/cover`** (TDD). `LibraryCoverTest`:
  - `FR-029: shouldServeTheCoverOfAnUnpublishedProductToItsOwner` (200, `Content-Type`, `nosniff`, `private, no-cache`, `ETag`)
  - `FR-029: shouldAnswerNotModifiedWhenTheVersionMatches`
  - `SEC-011: shouldAnswerNotFoundToAnotherCustomer` (Produto publicado, com capa, comprado por outro)
  - `FR-025: shouldAnswerNotFoundForACancelledAccess` (também com `If-None-Match` certo: 404, nunca 304)
  - `shouldAnswerNotFoundWithoutCover`, `shouldAnswerNotFoundForAMalformedId`, 401/403
  - O `CatalogCoverTest` existente continua provando que a rota pública dá 404 para despublicado.
  Commit `feat: library cover for the owner (#32)`.
- [ ] **Task 4 — Página `/biblioteca`** (TDD). `LibraryPage.test.tsx`: `FR-029: lista título, capa e "Comprado em"` (data em pt-BR, fuso de São Paulo); estado vazio com link ao Catálogo; carregando; erro com "Tentar novamente" que refaz a consulta; `401` leva a `/entrar` e guarda a volta; capa usa `/api/library/<id>/cover?v=`; título da aba. Reaproveita `CoverImage`. Commit `feat: library page (#32)`.
- [ ] **Task 5 — Entradas.** `HomePage` (logado): link "Biblioteca" ao lado de "Catálogo". `OrderPage` (pago): link "Ir para a Biblioteca" acima de "Voltar ao Produto". Testes nos dois. Commit `feat: links to the library (#32)`.
- [ ] **Task 6 — E2E** em `admin-panel.e2e.ts` (é onde estão o login do Administrador, a publicação com capa e o `sendPaymentReceived`; o projeto `celular` ignora este arquivo, então a parte de celular abre um contexto `Pixel 7`, como no #28). Título `FR-025 FR-029 SEC-011: a Biblioteca mostra o Produto comprado, mesmo despublicado`. Roteiro: o Administrador publica um Produto com capa; um Cliente novo compra; o teste envia `PAYMENT_RECEIVED` ao `POST /api/webhooks/asaas` real (mesmo caminho do `just demo-pagar`: Pedido → id da cobrança → evento com o token local); na página do Pedido pago, clicar "Ir para a Biblioteca" mostra o título, a data e a capa carregada (`naturalWidth > 0`); o Administrador despublica; o Catálogo não mostra mais o Produto, mas a Biblioteca, recarregada, ainda mostra o título e a capa; um segundo Cliente vê a Biblioteca vazia; repete a vista num contexto `Pixel 7`. Sem `page.route` nem mocks. Commit `test: library e2e (#32)`.
- [ ] **Task 7 — Documentos.** `GLOSSARY.md`: Capa "mostrada no Catálogo, na página do Produto e na Biblioteca de quem comprou"; Acesso "cancelado: deixa de aparecer na Biblioteca". ADR-0007, nova consequência "Biblioteca (#32)": as duas rotas, só Cliente, despublicado listado, capa servida ao dono pela rota da Biblioteca com as mesmas regras de cache da pública. Commit `docs: library rules (#32)`.
- [ ] **Task 8 — Verificação.** `/verificar-tela` na Biblioteca (com itens, vazia, erro) e na vista de pago, 1280 e 390 px, teclado; sistema de verdade (`just db` na pasta principal, `just dev-back`, `just dev-front`) e `just e2e` do teste novo; `just check-rapido`; skill `verificar-entrega`; revisão de código (`requesting-code-review`).

## 4. Textos novos (pt-BR)

**Biblioteca** (`/biblioteca`)

| Onde | Texto |
|---|---|
| Título da aba | `Biblioteca \| SapiensIA` |
| Título (h1) | `Biblioteca` |
| Carregando | `Carregando…` (já existe no Catálogo) |
| Data do item | `Comprado em 14/11/2026` (só a data) |
| Vazio | `Você ainda não tem Produtos na Biblioteca.` |
| Vazio: link | `Ver o Catálogo` |
| Erro | `Não foi possível carregar a Biblioteca. Tente novamente.` |
| Erro: botão | `Tentar novamente` (já existe no Catálogo) |
| Capa | sem texto alternativo (`alt=""`): o título está ao lado, e o leitor de tela não deve ler o título duas vezes |

**Entradas**

| Onde | Texto |
|---|---|
| Página inicial, Cliente logado (ao lado de "Catálogo") | `Biblioteca` |
| Pedido pago (`/pedidos/:id`), link novo | `Ir para a Biblioteca` |

Nenhum aviso de "fora do Catálogo" no Produto despublicado (D3).

**Celular e acessibilidade:** `main` com `max-w-2xl`; lista `<ul aria-labelledby>` do h1; cada item em linha (capa 2:3 com 64 px de largura, título que quebra com `overflow-wrap: anywhere`, data em `<time dateTime>`), sem rolagem horizontal em 390 px; foco no h1 ao carregar (`tabIndex={-1}`, como nas outras páginas); links com nome claro; sem `aria-live` novo.

## 5. Riscos

- **R1 — `accesses.unique` e `Accesses.exists` ignoram `cancelled_at`:** depois do #36, um Cliente com Acesso cancelado não conseguiria comprar o mesmo Produto de novo. Não afeta este ticket (ninguém cancela ainda); anotado para o #36.
- **R2 — Capa trocada depois da compra:** o Cliente vê a capa atual, não a da época da compra. Coerente com "sempre a versão mais recente" (ADR-0007).
- **R3 — Lista sem paginação:** um Cliente com centenas de Produtos teria uma resposta grande. Improvável na Fase 1 (D4).

## 6. O que não será provado e por quê

| O quê | Por quê | Quando |
|---|---|---|
| Acesso cancelado de verdade (estorno, MED) sumindo da Biblioteca | Quem cancela é o #36; aqui o cancelamento é simulado preenchendo `cancelled_at` por SQL nos testes de integração. O e2e não cobre cancelamento | #36 (e2e do estorno) |
| "Versão disponível, progresso e favoritos" (FR-029) | Leitor, progresso e favoritos são Fase 3/4 (ADR-0007); a versão é sempre a mais recente e entra no Download | #33 e fases seguintes |
| Download | É o #33 | #33 |
| Capa no Supabase Storage de verdade | Testes e e2e usam o armazenamento local; a rota usa o mesmo `ProductFileStorage` da capa pública, já provada em homologação | Homologação, abrindo a Biblioteca |
| Leitor de tela de verdade | O `verificar-tela` confere papéis, nomes e teclado, não a fala | Conferência manual, se quiser |
| Muitos itens (desempenho) | Volume da Fase 1 é baixo | #44 |

## 7. Decisões que dependem do Sérgio

| # | Decisão | Recomendação |
|---|---|---|
| D1 | Capa do Produto despublicado: rota própria `GET /api/library/{productId}/cover`, só para o dono com Acesso ativo, mesmas regras de cache da pública (seção 1b). Alternativa: desenho neutro no lugar da capa quando o Produto está despublicado | Rota própria |
| D2 | Até o #33, cada item mostra só capa, título e "Comprado em"; nenhum aviso de "download em breve" | Aceitar |
| D3 | Nenhuma marca de "fora do Catálogo" no Produto despublicado | Aceitar (menos texto; para quem comprou nada muda) |
| D4 | Lista sem paginação, do mais recente para o mais antigo | Aceitar |
| D5 | A D8 do #28 ("esconder o Comprar com Pix para quem já tem fica para a Biblioteca") **não** entra aqui: vira issue `needs-triage` citando o #32. O `409 ALREADY_OWNED` continua protegendo | Issue separada |
| D6 | "Menu do Cliente" = os links da página inicial logada (não existe cabeçalho comum); criar um cabeçalho fica fora | Aceitar |
| D7 | Todos os textos da seção 4 | Aprovar ou corrigir |
