# Publicar e despublicar Produto (#19) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** Decisões aprovadas em 10/11/2026. Os textos (D8) ainda aguardam aprovação, e nada é implementado antes dela.

**Decisões aprovadas:** D1, D2, D3, D4, D5, D6, D7 e D9 como estão na seção 9. D8 está pendente.

**Ajustes obrigatórios (aprovação de 10/11/2026):**
- A1 Conferido no spec: FR-011 diz "validações requeridas", e a história 48 da spec #5 define essa validação como "só conseguir publicar se ele tiver Arquivo do produto". Preço, título, descrição e categoria já são validados ao criar e editar (DTO e checks do banco), então todo Produto salvo é válido. **Publicar exige só o arquivo**, sem erro novo. A capa (história 45) ainda não existe no modelo e fica para o ticket que a criar.
- A2 A V15 deixa os Produtos existentes em `DRAFT` com `first_published_at` nulo, e há um teste de migração (Task 1).
- A3 `PRODUCTS_PUBLISH` entra no único perfil do Administrador, com os testes de capacidade e de sessão atualizados como no `PRODUCTS_FILE` (Task 2).
- A4 Execução por subagentes em sonnet. **Só um comando pesado por vez no computador** (`just check-resumo`, `just imagem`, `just e2e` e a suíte inteira do Maven): antes de cada um, avisar e esperar o OK. Testes de uma classe só (`-Dtest=...`) e o Vitest de um arquivo não precisam de OK. Sem `force`, sem `reset --hard` e sem merge.

**Goal:** O Administrador publica um Produto que tem Arquivo do produto e o despublica depois. Publicar sem arquivo é recusado. O Produto guarda que já foi publicado, e a troca do arquivo de um Produto que já foi publicado fica bloqueada até o #22.

**Architecture:** Duas rotas idempotentes na mesma URL: `PUT /api/admin/products/{id}/publication` publica e `DELETE /api/admin/products/{id}/publication` despublica. As duas são liberadas pela capacidade nova `PRODUCTS_PUBLISH`. A coluna `first_published_at` é o marcador de "já foi publicado": é gravada uma vez e nunca é limpa. A publicação e a troca de arquivo usam a mesma trava (`ProductFiles.lockProduct`, `SELECT ... FOR UPDATE`). Dentro dela, a publicação confere se há arquivo e a troca confere o marcador.

**Tech Stack:** Spring Boot 4.1, JdbcClient, Flyway, React + TanStack Query, Vitest, Playwright. **Nenhuma dependência nova** (nem shadcn `alert-dialog`: a confirmação é feita na própria tela, ver 9.D6).

**Spec:** `docs/spec/sapiensia-spec.md` (FR-011, FR-016, SEC-008, SEC-010, SEC-011, SEC-065); issue #19 e o comentário dela (decisão D1 do #18); spec #5 (histórias 3, 30, 44, 48 e 52); `docs/adr/0002-*.md`; `docs/adr/0004-identidade-e-mfa.md` (linha "Enquanto o Produto nunca foi publicado..."); `docs/adr/0007-*.md` (despublicar não tira o Acesso); `GLOSSARY.md`; planos do #17 e do #18.

## IDs cobertos

| ID | Como | Onde |
|---|---|---|
| FR-011 | Publicar exige Arquivo do produto. A conferência é feita sob a trava do Produto e a recusa é `409` com `errors.file = FILE_REQUIRED` | Tasks 1 e 2 |
| FR-016 | Estado `PUBLISHED` ou `UNPUBLISHED` no Produto, que o Catálogo do #20 filtra. Despublicar tira o Produto do Catálogo (ver 9.D5) | Tasks 1 e 4 |
| SEC-008 | A troca de arquivo de um Produto com `first_published_at` preenchido é recusada (`409`, `PUBLISHED_BEFORE`) até o #22 trazer a reautenticação | Task 3 |
| SEC-010, SEC-017 | As rotas novas nascem negadas e são liberadas só com `PRODUCTS_PUBLISH` e os dois fatores. CSRF é exigido | Task 2 |
| SEC-011 | ID inexistente recebe `404` e nada muda. Publicar o A nunca toca o B | Task 2 |
| SEC-065 | `ADMIN_PRODUCT_PUBLISHED` e `ADMIN_PRODUCT_UNPUBLISHED` (SUCCESS e FAILURE), sem nenhum dado do arquivo (nem chave, nem tamanho, nem páginas) | Tasks 1 e 3 |

## Global Constraints

- Todo endpoint começa com `/api`. As rotas novas são liberadas na `SecurityConfig` por `panel(Capability.PRODUCTS_PUBLISH)`, antes do `denyAll`.
- Código, commits e comentários em inglês. Textos da tela em pt-BR, **só os da seção 7**.
- Nenhuma dependência nova, nem no backend nem no frontend.
- Migration nova `V15` criada pela skill `nova-migration`. As migrations V13 e V14 não são editadas.
- `AuditEntry` não tem texto livre. Não entra na auditoria nem no log nenhum dado do arquivo (chave do objeto, tamanho, páginas).
- Controller e repositório package-private, DTOs como `record`. Teste que prova requisito leva o ID no `@DisplayName` ou no título. Sem `@MockitoBean` em teste de integração (usar os dublês `Switchable*`).
- Publicar e despublicar **não** incrementam `products.version`, porque não mexem nos dados do formulário (ver 9.D7). Os dois atualizam `updated_at`.

## Review Focus

1. **Publicar enquanto a primeira troca de arquivo está no meio do caminho** (o arquivo já subiu para o Storage, mas a transação ainda não gravou): a publicação espera a trava e, quando a troca termina, encontra o arquivo e publica. Ela nunca publica sem `product_files` e nunca devolve 500. Teste na Task 1 (trava segurada pelo teste).
2. **Troca de arquivo que passou na conferência inicial, mas o Produto foi publicado antes de ela pegar a trava**: a conferência dentro da trava recusa (`409 PUBLISHED_BEFORE`), o objeto novo é apagado do Storage e `product_files` continua apontando para o antigo. Teste na Task 3.
3. **Publicar, despublicar e tentar trocar o arquivo** (o caso do comentário da issue): o Produto fica `UNPUBLISHED`, `first_published_at` fica preenchido e a troca é recusada. Teste na Task 3 (API) e na Task 5 (e2e).
4. **Duplo clique em Publicar ou duas abas** (dois `PUT` seguidos): o resultado é `200` nos dois, só um evento de auditoria e `first_published_at` com a data do primeiro. Republicar depois de despublicar também não muda a data da primeira publicação. Teste na Task 1.
5. **Formulário de dados aberto numa aba enquanto outra publica**: salvar o título depois não dá conflito falso, porque `version` não muda. Teste na Task 1.

---

## 1. Escopo

**Entra:**
- Migration V15: `status` passa a aceitar `DRAFT`, `PUBLISHED` e `UNPUBLISHED`, e entra a coluna `first_published_at timestamptz null`, com a regra de consistência da seção 2.
- Rotas `PUT` e `DELETE /api/admin/products/{id}/publication`, a capacidade `PRODUCTS_PUBLISH` e a auditoria.
- **Mudança no #18:** a troca de arquivo passa a ler `first_published_at` (seção 3).
- Tela: o seletor "Publicação" na edição do Produto, o status real na lista e a seção do arquivo bloqueada para Produto já publicado.
- e2e do fluxo crítico, mais o glossário com Publicar, Despublicar e Despublicado.

**Fica fora:**
- A consulta e a tela do Catálogo público e o 404 do Produto não publicado por endereço direto: ficam no #20 (ver 9.D5).
- A reautenticação e a troca de arquivo de Produto já publicado: ficam no #22.
- Outras validações de publicação (capa, revisão por IA): o modelo ainda não tem esses dados.

## 2. Modelo

- `ProductStatus { DRAFT, PUBLISHED, UNPUBLISHED }`. `DRAFT` só existe antes da primeira publicação, e um Produto nunca volta para `DRAFT`.
- `first_published_at`: gravado no primeiro `DRAFT → PUBLISHED` e nunca alterado depois.
- Check na V15: `((status = 'DRAFT') = (first_published_at is null))`. Assim o banco impede um marcador inconsistente, e o `DRAFT` nunca pode reaparecer depois de uma publicação.
- Transições, todas com `200` e o `ProductResponse` atual:

| Estado atual | PUT (publicar) | DELETE (despublicar) |
|---|---|---|
| DRAFT sem arquivo | `409 FILE_REQUIRED` e FAILURE na auditoria | sem mudança e sem auditoria |
| DRAFT com arquivo | → PUBLISHED, grava `first_published_at` e SUCCESS na auditoria | sem mudança e sem auditoria |
| PUBLISHED | sem mudança e sem auditoria | → UNPUBLISHED e SUCCESS na auditoria |
| UNPUBLISHED (sempre tem arquivo) | → PUBLISHED, mantém `first_published_at` e SUCCESS na auditoria | sem mudança e sem auditoria |

## 3. Onde o #18 passa a ler o marcador

Em `ProductFileService.upload`, em dois pontos:
1. **Conferência barata, antes da validação e do `storage.put`.** Ela vem logo depois de `products.exists`: `if (products.everPublished(productId))`, grava FAILURE (`ADMIN_PRODUCT_FILE_UPLOADED`) com `recordIndependent` e lança `ProductFileRejectedException(PUBLISHED_BEFORE)`. Assim nenhum byte sobe para o Storage à toa.
2. **Conferência que vale, dentro de `store`, logo depois de `files.lockProduct(productId)`.** Ela é feita na mesma `TransactionTemplate`. Se o Produto já foi publicado, lança a mesma exceção. O `catch (RuntimeException)` que já existe apaga o objeto novo, e a FAILURE é gravada fora da transação revertida.

`ProductFileController` mapeia `PUBLISHED_BEFORE` para `409` (os demais motivos continuam `400` ou `413`). O #22 vai substituir essas duas conferências pela exigência de reautenticação.

## 4. Concorrência

- Publicar faz, numa transação: `files.lockProduct(id)` (devolve `false` → 404), depois `files.currentKey(id).isPresent()` e depois `products.publish(id)`. É a mesma trava da troca de arquivo, então as duas operações ficam em fila. A publicação sempre vê o arquivo já gravado, e a troca sempre vê o marcador já gravado.
- Despublicar usa a mesma trava, para manter uma ordem só, embora não precise do arquivo.
- O SQL de publicar e despublicar é condicional (`where status <> 'PUBLISHED'` ou `where status = 'PUBLISHED'`). `rows == 0` sob a trava significa "já estava no estado pedido": a resposta não muda nada e nada vai para a auditoria.

## 5. API

- `PUT /api/admin/products/{id}/publication`, sem corpo: `200 ProductResponse`, `404`, `409 {"errors":{"file":"FILE_REQUIRED"}}` (ProblemDetail, no estilo do `ApiExceptionHandler.problem`) ou `503`.
- `DELETE /api/admin/products/{id}/publication`: `200 ProductResponse`, `404` ou `503`.
- `ProductResponse` ganha `Instant firstPublishedAt` (pode ser nulo). `ProductListItem` já tem `status`.
- Por que `PUT`/`DELETE` num sub-recurso em vez de `POST /publish` e `/unpublish`: a publicação é um estado que existe ou não, e os dois verbos já são idempotentes por definição, o que bate com a tabela da seção 2.

## 6. Segurança

- `Capability.PRODUCTS_PUBLISH`. O Administrador recebe `EnumSet.allOf`, então não há outra mudança no login.
- `SecurityConfig`: `.requestMatchers(PUT, "/api/admin/products/*/publication").access(panel(PRODUCTS_PUBLISH))` e o mesmo para `DELETE`, antes do `denyAll`.
- A resposta negada é igual, exista o Produto ou não (o padrão de SEC-011 que já existe).

## 7. Tela do painel (todos os textos que o usuário vê, para aprovação)

**Lista (`/painel/produtos`), coluna Status** (hoje está fixo em "Rascunho"):
- `DRAFT`: "Rascunho"
- `PUBLISHED`: "Publicado"
- `UNPUBLISHED`: "Despublicado"

**Edição (`/painel/produtos/:id`), aviso no topo** (o de rascunho já existe e é mantido):
- DRAFT: "Este Produto está em rascunho: os clientes ainda não conseguem vê-lo nem comprá-lo."
- PUBLISHED: "Este Produto está publicado: aparece no Catálogo e pode ser comprado."
- UNPUBLISHED: "Este Produto está despublicado: saiu do Catálogo, e quem já comprou continua com acesso."

**Seção nova "Publicação"** (só com a capacidade `PRODUCTS_PUBLISH`):
- Título: "Publicação"
- DRAFT ou UNPUBLISHED sem arquivo: botão "Publicar" desabilitado e a dica "Envie o Arquivo do produto para poder publicar."
- DRAFT com arquivo, ao clicar em "Publicar", aparece a confirmação na própria seção:
  - "Publicar este Produto? Ele vai aparecer no Catálogo. Depois da primeira publicação, o arquivo só poderá ser trocado com nova autenticação, que ainda não está disponível."
  - Botões "Confirmar publicação" e "Cancelar"
- UNPUBLISHED: botão "Publicar de novo", sem confirmação (o arquivo já está travado).
- PUBLISHED: botão "Despublicar" e a dica "Quem já comprou continua com acesso."
- Carregando: "Publicando…" e "Despublicando…"
- Sucesso: "Produto publicado." e "Produto despublicado."
- Erros:
  - `409 FILE_REQUIRED`: "Envie o Arquivo do produto antes de publicar."
  - 403: "Você não tem permissão para publicar ou despublicar este Produto."
  - 404: "Produto não encontrado." (já existe)
  - 503: "O sistema está indisponível no momento e nada foi alterado. Tente novamente em alguns instantes."
  - Outro erro: "Não foi possível alterar a publicação agora. Tente novamente."

**Seção "Arquivo do produto"**, quando `firstPublishedAt` está preenchido, o formulário de envio é trocado por:
- "Este Produto já foi publicado. A troca do arquivo vai exigir nova autenticação, que ainda não está disponível."
- A mesma frase é a mensagem do código `PUBLISHED_BEFORE`, caso a troca seja recusada pela API (outra aba).

## 8. Testes por requisito

| Teste | ID |
|---|---|
| `AdminProductPublicationTest`: publica com arquivo; recusa sem arquivo (`409`, estado não muda, FAILURE na auditoria); despublica; republica mantendo `first_published_at`; `PUT` e `DELETE` repetidos (idempotentes, um só evento); `version` não muda | FR-011, FR-016, SEC-065 |
| `AdminProductPublicationAuthorizationTest`: anônimo `401`; cliente `403`; MFA pendente `401`; sem CSRF `403`; sem `PRODUCTS_PUBLISH` `403`; ID inexistente `404` nos dois verbos e nada gravado; publicar o A não toca o B | SEC-010, SEC-011, SEC-017 |
| `AdminProductPublicationConcurrencyTest`: publicar espera a trava segurada pelo teste e vê o arquivo gravado nela | FR-011 |
| `AdminProductFileUploadTest` (casos novos): publicar, despublicar e trocar é recusado; troca liberada antes da publicação continua funcionando; corrida em que a troca passa na conferência inicial e a publicação entra antes da trava | SEC-008 |
| `AdminRouteDenyByDefaultTest`: as duas rotas novas entram na lista | SEC-010 |
| Vitest `ProductPublicationSection.test.tsx`, `ProductListPage.test.tsx`, `ProductFileSection.test.tsx` | FR-011, FR-016 |
| e2e `admin-panel.e2e.ts` "FR-011 FR-016 SEC-008: ..." | FR-011, FR-016, SEC-008 |

## 9. Riscos e decisões (precisam do OK)

- **D1 Modelo.** Três estados (`DRAFT`, `PUBLISHED`, `UNPUBLISHED`) mais `first_published_at`, com o check de consistência. Alternativa: só `DRAFT`/`PUBLISHED`, em que despublicar volta para `DRAFT` e só a coluna marca o histórico. Recomendo os três estados: na lista, "Rascunho" para um Produto que clientes já compraram engana, e o glossário diz que Rascunho é o estado de criação.
- **D2 Bloqueio da troca de arquivo** de Produto já publicado (inclusive despublicado) até o #22: `409 PUBLISHED_BEFORE`, conferido antes do upload e de novo sob a trava, e a tela esconde o formulário. Isso aplica a decisão D1 do #18 e o ADR-0004, que já tem a linha. **Consequência:** até o #22, um PDF com erro num Produto publicado não pode ser corrigido pelo painel.
- **D3 Rotas** `PUT`/`DELETE .../publication`, idempotentes, e a chamada que não muda nada não gera auditoria.
- **D4 Capacidade única** `PRODUCTS_PUBLISH` para publicar e despublicar.
- **D5 "Despublicar tira o Produto do Catálogo".** A consulta do Catálogo não existe e é do #20 (que tem como critério "Catálogo e detalhe públicos só de Produtos publicados"). No #19 o critério é garantido pelo estado (`UNPUBLISHED`), e o teste de que ele não aparece fica no #20. Alternativa: criar já aqui um `Products.findPublished()`, que não teria uso fora do teste. Recomendo deixar a consulta para o #20 e citar isso na PR.
- **D6 Confirmação só na primeira publicação**, feita na própria seção (sem diálogo modal, para não trazer `@radix-ui/react-alert-dialog`). A publicação é a única ação que não se desfaz, porque trava o arquivo.
- **D7 `version` não muda** ao publicar e despublicar (evita conflito falso no formulário aberto). A tela recarrega o Produto depois da ação.
- **D8 Textos da seção 7**, exatamente como estão.
- **D9 Documentação:** o glossário ganha Publicar, Despublicar e Despublicado (skill `domain-modeling`). **Sem ADR novo**, porque a regra de troca de arquivo já está no ADR-0004 e o resto é glossário. Se preferir um ADR para a "publicação exige arquivo", diga.

---

## Tasks

### Task 1: Migration, modelo e serviço de publicação

**Files:**
- Create: `backend/src/main/resources/db/migration/V15__product_publication.sql` (via a skill `nova-migration`)
- Create: `backend/src/main/java/br/com/sergio/sapiensia/product/ProductPublicationService.java`, `ProductFileRequiredException.java`
- Modify: `product/ProductStatus.java`, `Products.java` (COLUMNS, MAPPERs, métodos novos), `ProductResponse.java`, `audit/AuditAction.java`, `web/ApiExceptionHandler.java`
- Modify: `backend/src/test/.../admin/AdminProductFixtures.java` (`insertProductWithFile(jdbc)` e `publish(jdbc, id)` para o estado direto no banco)
- Test: `backend/src/test/.../admin/AdminProductPublicationTest.java`, `AdminProductPublicationConcurrencyTest.java` e `product/ProductPublicationMigrationTest.java`

**Interfaces:**
- Produces:
  - `ProductStatus { DRAFT, PUBLISHED, UNPUBLISHED }`
  - `ProductResponse(..., ProductFileInfo file, Instant firstPublishedAt)`: campo novo no fim
  - `Products.publish(UUID id) -> int` (`status='PUBLISHED'`, `first_published_at = coalesce(first_published_at, now())`, `updated_at = now()`, `where id=:id and status <> 'PUBLISHED'`)
  - `Products.unpublish(UUID id) -> int` (`where id=:id and status = 'PUBLISHED'`)
  - `Products.everPublished(UUID id) -> boolean` (`first_published_at is not null`, `false` se não existe)
  - `ProductPublicationService.publish(UUID id, UUID adminId, String origin) -> ProductResponse` e `unpublish(...)`, `@Transactional`
  - `AuditAction.ADMIN_PRODUCT_PUBLISHED`, `ADMIN_PRODUCT_UNPUBLISHED`
  - `ProductFileRequiredException` → `409` com `errors.file = "FILE_REQUIRED"` no `ApiExceptionHandler`
- Consumes: `ProductFiles.lockProduct(UUID) -> boolean` e `currentKey(UUID) -> Optional<String>` (do #18; o serviço fica no mesmo pacote)

- [ ] **Step 1: Migration.** Pela skill `nova-migration`: confirme o nome do check atual com `select conname from pg_constraint where conrelid = 'products'::regclass` (provável `products_status_check`). Depois troque o check por `status in ('DRAFT','PUBLISHED','UNPUBLISHED')`, adicione `first_published_at timestamptz` e o check `products_publication_consistent` da seção 2. O seed continua válido (todos `DRAFT`, coluna nula).
- [ ] **Step 1b: Teste de migração que falha (A2)**, `ProductPublicationMigrationTest`, no padrão do `ProductSeedTest`: schema novo e `Flyway.configure()...locations("classpath:db/migration")`. O teste migra com `.target("14")` e insere dois Produtos, um sem arquivo e um com linha em `product_files`. Depois migra até a última versão e confere:
  - os dois continuam `status = 'DRAFT'`, com `first_published_at is null`;
  - um `update` que põe `status = 'PUBLISHED'` sem preencher `first_published_at` é recusado pelo check;
  - um `update` de volta para `DRAFT` com a data preenchida também é recusado.
  O schema é apagado no `@AfterEach`. `@DisplayName("FR-016: a V15 deixa os Produtos existentes em rascunho e nunca publicados")`.
- [ ] **Step 2: Testes que falham** em `AdminProductPublicationTest` (via HTTP, como os outros `AdminProduct*Test`; as rotas da Task 2 ainda não existem, então este passo e a Task 2 podem ser feitos juntos, ou os testes chamam o serviço). `@DisplayName` com os IDs:
  - `FR-011: publicar sem Arquivo do produto é recusado e nada muda`: `409`, `errors.file == "FILE_REQUIRED"`, `status` continua `DRAFT`, `first_published_at` nulo e um evento `ADMIN_PRODUCT_PUBLISHED/FAILURE`.
  - `FR-011 FR-016: publicar com arquivo deixa o Produto publicado`: `200`, `status == "PUBLISHED"`, `firstPublishedAt != null` e um evento SUCCESS cuja linha em `audit_events` só tem as colunas padrão (nada do arquivo).
  - `FR-016: despublicar tira o Produto de publicado e mantém a data da primeira publicação`: `status == "UNPUBLISHED"`, `firstPublishedAt` igual ao de antes e um evento `ADMIN_PRODUCT_UNPUBLISHED/SUCCESS`.
  - `FR-016: publicar de novo mantém a data da primeira publicação`.
  - `SEC-065: publicar ou despublicar duas vezes responde 200 e audita uma vez`.
  - `publicar não muda a versão do Produto` (depois, um `PUT /{id}` com a `version` antiga responde `200`).
- [ ] **Step 3: Teste de concorrência que falha** `FR-011: publicar espera a troca de arquivo em andamento e vê o arquivo gravado`. Uma transação do teste faz `select ... for update` no Produto sem arquivo e insere a linha em `product_files`. O `PUT` é disparado noutra thread e não pode responder antes do commit. Depois do commit, a resposta é `200 PUBLISHED`.
- [ ] **Step 4: Implementar** a migration, o modelo, `Products.*` e `ProductPublicationService` seguindo a seção 4. A FAILURE sem arquivo vai por `audit.recordIndependent`, e o SUCCESS por `recordInTransaction`.
- [ ] **Step 5:** `cd backend; .\mvnw.cmd test -Dtest='AdminProductPublication*,ProductPublicationMigrationTest,ProductSeedTest'` com tudo verde. O `just check-resumo` fica para o fim da Task 3 (A4).
- [ ] **Step 6: Commit** `feat: publish and unpublish products (#19)`.

### Task 2: Rotas, capacidade e autorização

**Files:**
- Create: `product/ProductPublicationController.java` (`@RequestMapping("/api/admin/products/{id}/publication")`, com o mesmo handler de `503` do `ProductAdminController`)
- Modify: `admin/Capability.java` (`PRODUCTS_PUBLISH`, com javadoc, depois de `PRODUCTS_FILE`), `security/SecurityConfig.java`
- Test: `admin/AdminProductPublicationAuthorizationTest.java`, `admin/AdminProductPublicationCapabilityTest.java` (cópia do padrão do `AdminProductFileCapabilityTest`), `admin/AdminRouteDenyByDefaultTest.java`, `admin/AdminSessionTest.java:124,409` e `admin/AdminMfaLoginTest.java:121` (a lista de capacidades do perfil ganha `"PRODUCTS_PUBLISH"` no fim)

**Interfaces:**
- Consumes: `ProductPublicationService.publish/unpublish` (Task 1)
- Produces: `PUT` e `DELETE /api/admin/products/{id}/publication` (contrato da seção 5) e `Capability.PRODUCTS_PUBLISH`

- [ ] **Step 1: Testes que falham** (`@DisplayName` com "SEC-010", "SEC-011", "SEC-017"): para cada verbo, anônimo `401`, cliente `403`, MFA pendente `401`, sem CSRF `403`, Administrador sem `PRODUCTS_PUBLISH` `403` (no padrão do `AdminProductFileCapabilityTest`), `SEC-011: ID inexistente responde 404 e nada é gravado` (sem linha nova em `audit_events` com SUCCESS; `products` igual), ID malformado `400`, e publicar o A deixa o B `DRAFT`. Acrescente as duas rotas ao `AdminRouteDenyByDefaultTest`.
  - **A3, perfil único:**
    - `AdminSessionTest` e `AdminMfaLoginTest` esperam `PRODUCTS_PUBLISH` na lista de capacidades do Administrador logado.
    - `AdminProductPublicationCapabilityTest` cobre três casos: `SEC-010 SEC-011: sem PRODUCTS_PUBLISH a rota nega igual exista o Produto ou não, e nada muda`; `SEC-010: PRODUCTS_PUBLISH só com a senha, ou só com o TOTP, é negado`; e `SEC-010: PRODUCTS_PUBLISH com os dois fatores publica (200)`.
    - O perfil vem de `EnumSet.allOf(Capability.class)` (`AdminMfaController.java:140`), sem seed nem bootstrap à parte. Confirme com um grep por `PRODUCTS_FILE` em `backend/src/main` e `db/` que não há outra lista a atualizar.
- [ ] **Step 2: Implementar** o controller e as duas regras na `SecurityConfig`, antes do `denyAll`.
- [ ] **Step 3:** `.\mvnw.cmd test -Dtest='AdminProductPublication*,AdminRouteDenyByDefaultTest,AdminSessionTest,AdminMfaLoginTest'` verde.
- [ ] **Step 4: Commit** `feat: publication routes behind PRODUCTS_PUBLISH (#19)`.

### Task 3: Troca de arquivo bloqueada depois da primeira publicação

**Files:**
- Modify: `product/ProductFileService.java` (dois pontos, seção 3), `product/ProductFileRejection.java` (`PUBLISHED_BEFORE`), `product/ProductFileController.java` (`PUBLISHED_BEFORE` → `409`)
- Modify (só se faltar): `SwitchableProductFileStorage` ganha uma pausa opcional em `put` (latch), para o teste de corrida
- Test: `admin/AdminProductFileUploadTest.java` e `product/ProductFileServiceTest.java`

**Interfaces:**
- Consumes: `Products.everPublished(UUID)` (Task 1) e as rotas da Task 2
- Produces: `409 {"errors":{"file":"PUBLISHED_BEFORE"}}` em `POST /api/admin/products/{id}/file`

- [ ] **Step 1: Testes que falham:**
  - `SEC-008: depois de publicar e despublicar, trocar o arquivo é recusado`: `409 PUBLISHED_BEFORE`; a chave em `product_files` é a mesma de antes; o Storage não recebeu objeto novo; e há um evento `ADMIN_PRODUCT_FILE_UPLOADED/FAILURE`.
  - `SEC-008: Produto publicado não aceita arquivo novo`.
  - `SEC-008: a publicação que entra antes da trava faz a troca em andamento ser recusada`: o `put` no Storage pausa; o Produto (que já tem arquivo) é publicado pela API; o `put` é liberado; a troca responde `409`, o objeto novo é apagado e a chave antiga continua.
  - O caso existente "substituir o arquivo de um rascunho" continua passando, sem alteração.
- [ ] **Step 2: Implementar** a seção 3.
- [ ] **Step 3:** `.\mvnw.cmd test -Dtest='AdminProductFile*,ProductFileServiceTest'` verde. Depois, **avisar e esperar o OK (A4)** para rodar `just check-resumo` (fim do backend).
- [ ] **Step 4: Commit** `feat: block file replacement after first publication until reauth (#19)`.

### Task 4: Tela do painel

**Files:**
- Create: `frontend/src/features/products/ProductPublicationSection.tsx` e `.test.tsx`
- Modify: `schemas.ts` (`status: z.enum(["DRAFT","PUBLISHED","UNPUBLISHED"])`, `firstPublishedAt: z.string().nullable()`, código `PUBLISHED_BEFORE` em `ProductFileRejectionCode`), `api.ts` (`publishProduct(id)` e `unpublishProduct(id)`; o `409` com `errors.file` vira `ProductValidationError`), `hooks.ts` (`usePublishProduct(id)` e `useUnpublishProduct(id)` com `setQueryData` e invalidação de `productsQueryKey`, no padrão existente), `messages.ts` (textos da seção 7: `STATUS_LABELS`, `STATUS_NOTICES`, `PUBLICATION_MESSAGES`, `PUBLICATION_ERRORS`, `FILE_MESSAGES.PUBLISHED_BEFORE`), `ProductListPage.tsx:88` (`STATUS_LABELS[item.status]`), `ProductFormPage.tsx` (aviso conforme o status e a seção nova quando `admin.capabilities.includes("PRODUCTS_PUBLISH")`), `ProductFileSection.tsx` (a mensagem no lugar do formulário quando `firstPublishedAt`), `testing.tsx` (`PRODUCTS_PUBLISH` no `FULL`)
- Test: `ProductPublicationSection.test.tsx`, `ProductListPage.test.tsx`, `ProductFileSection.test.tsx`, `ProductFormPage.test.tsx`

**Interfaces:**
- Consumes: o contrato da seção 5
- Produces: `ProductPublicationSection({ product }: { product: Product })`

- [ ] **Step 1: Testes que falham** (Testing Library, `getByRole`):
  - DRAFT sem arquivo: o botão "Publicar" está desabilitado e aparece a dica.
  - DRAFT com arquivo: clicar em "Publicar" mostra a confirmação; "Cancelar" não chama a API; "Confirmar publicação" chama `PUT` e mostra "Produto publicado.".
  - PUBLISHED: "Despublicar" chama `DELETE` e mostra "Produto despublicado.".
  - UNPUBLISHED: "Publicar de novo" chama `PUT` sem confirmação.
  - `409 FILE_REQUIRED`, `403` e `503` mostram os textos da seção 7.
  - Sem `PRODUCTS_PUBLISH`, a seção não aparece.
  - A lista mostra "Publicado" e "Despublicado".
  - `ProductFileSection` com `firstPublishedAt` mostra a frase e não tem o campo de arquivo.
- [ ] **Step 2: Implementar.** O foco vai para a mensagem de resultado (`role="status"` para sucesso e `role="alert"` para erro), e o botão fica desabilitado durante a ação.
- [ ] **Step 3:** `cd frontend; pnpm vitest run src/features/products` verde (leve). O `just check-resumo` completo fica para a Task 5.
- [ ] **Step 4:** `/verificar-tela` em `/painel/produtos` e `/painel/produtos/:id` (desktop, celular e teclado), nos três estados.
- [ ] **Step 5: Commit** `feat: publish and unpublish from the admin panel (#19)`.

### Task 5: e2e, glossário e entrega

**Files:**
- Modify: `frontend/e2e/admin-panel.e2e.ts`, `GLOSSARY.md`

- [ ] **Step 1: e2e** `FR-011 FR-016 SEC-008: o Administrador publica um Produto com arquivo, despublica e não consegue trocar o arquivo`, sem `page.route`. O teste cria o Produto pela tela, verifica que "Publicar" está desabilitado, envia o PDF (`buildPdf`) e publica com confirmação. A lista mostra "Publicado". Depois ele despublica, a lista mostra "Despublicado", e a seção do arquivo mostra a frase de bloqueio, sem campo de envio.
- [ ] **Step 2:** `just db` (na pasta principal), `just dev-back`, `just dev-front` e depois, **com OK (A4)**, `just e2e`, tudo verde. Confira o fluxo à mão no navegador.
- [ ] **Step 3: Glossário** (skill `domain-modeling`): entradas Publicar ("só com Arquivo do produto"), Despublicar ("tira do Catálogo, não cancela o Acesso") e Despublicado. Em Rascunho, deixe claro que um Produto que já foi publicado nunca volta a rascunho.
- [ ] **Step 4:** **com OK (A4)**, `just check-resumo` verde, depois a skill `verificar-entrega` com a tabela e a revisão final (`superpowers:requesting-code-review`).
- [ ] **Step 5: Commit** `docs: glossary entries for publishing (#19)` e `test: e2e for product publication (#19)`.
- [ ] **Step 6:** push e PR (`Closes #19`, citando os IDs e a 9.D5) **só com o OK**.
