# Limite de 10 Downloads por dia por Produto (#34) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** APROVADO pelo Sérgio em 14/11/2026: texto da seção 2 aprovado, e2e incluído (D6), subagentes em **Sonnet**, D2 aceita com um ajuste (D7, Task 2): a assinatura no Supabase ganha tempo limite próprio e curto, para a trava do Acesso não segurar uma conexão do banco por até 60 s.

**Goal:** O Cliente obtém até 10 links de Download por dia (horário de Brasília) para o mesmo Produto; o 11º pedido do dia é recusado com `429` e a tela diz para tentar amanhã.

**Architecture:** O `ProductDownloadService.issue` passa a rodar numa transação: trava a linha do Acesso (`select ... for update of a`), conta os Registros de download do Cliente e Produto no dia de São Paulo, recusa o 11º antes de assinar e, abaixo do limite, assina e grava o Registro como hoje. A recusa vira `429` com o código `DOWNLOAD_LIMIT` no `ApiExceptionHandler`; o `DownloadButton` mostra a mensagem.

**Tech Stack:** o que já existe (Spring Boot 4.1, `JdbcClient`, `TransactionTemplate`, Testcontainers; React, TanStack Query, Vitest, Playwright). **Nenhuma dependência nova. Nenhuma migration** (a tabela e o índice `download_records_customer_product_day` vieram no #33).

**Spec:** issue #34; `docs/spec/sapiensia-spec.md` (SEC-015: "Limitar ... concorrência, duração e custo"); `docs/adr/0007-modelo-comercial-da-fase-1.md` ("limite de 10 downloads por dia por Cliente e Produto"); plano do #33 (`docs/superpowers/plans/2026-11-14-33-download-60s.md`, D8: o Registro é gravado na emissão do link e é a base deste limite).

## IDs cobertos

| ID | Como | Onde |
|---|---|---|
| SEC-015 | No máximo 10 links por Cliente e Produto por dia de São Paulo; o 11º é recusado antes de assinar, sem Registro; dois pedidos simultâneos não passam de 10; a assinatura desiste em 5 s e não prende a conexão do banco | Tasks 1, 2, 3, 4, 5 |
| ADR-0007 | Limite por Cliente **e** Produto, contado sobre o Registro de download | Task 1 |
| SEC-011 | Sem Acesso continua `404` mesmo com o limite batido (o `429` nunca revela que o Produto existe) | Task 1 |

## Global Constraints

- Limite **10**, constante `ProductDownloadService.DAILY_LIMIT = 10`. Fuso `ProductDownloadService.DAY_ZONE = ZoneId.of("America/Sao_Paulo")`.
- Janela do dia: `[00:00 de hoje em São Paulo, 00:00 de amanhã em São Paulo)`, calculada do `Clock` da aplicação.
- Resposta do 11º: `429`, `ProblemDetail` com `errors = {"download": "DOWNLOAD_LIMIT"}` (mesmo formato do `refusal(...)` já usado pelo `ApiExceptionHandler`). Sem URL, sem `Retry-After`.
- Ordem no serviço: Acesso (com trava) → contagem → assinatura → Registro. Sem Acesso → vazio (`404`) antes de contar.
- O `429` não é logado (como o `404`). Nenhum log novo.
- Textos de tela: **só os da seção 2**. Texto novo que surgir volta ao Sérgio antes do push.
- Testes de integração com `@Import(TestcontainersConfiguration.class)`, sem `@MockitoBean`; `download_records` é só-inserção: cada classe cria os **seus** Clientes e Produtos e nunca os apaga.
- Teste focado: incluir as classes que citam os IDs (`git grep -l -E "SEC-015|FR-034|SEC-011" -- backend/src/test`).
- Um comando pesado por vez, com 2 GB livres antes. Só `just check-rapido` durante o trabalho.
- Push só com `git push -u origin feat/34-limite-downloads`, depois do OK.

## 1. Decisões técnicas

**D1 — a) O que conta e qual "dia".** Conta-se cada link emitido (linha de `download_records`, gravada na emissão desde o #33), por `customer_id` e `product_id`:

```sql
select count(*) from download_records
where customer_id = :customerId and product_id = :productId
  and downloaded_at >= :dayStart and downloaded_at < :nextDayStart
```

O índice `(customer_id, product_id, downloaded_at)` do #33 atende a consulta. O dia é o de **America/Sao_Paulo, de 00:00 a 23:59:59.999** (intervalo meio-aberto até a 00:00 seguinte). Por quê: os Clientes estão no Brasil e a mensagem diz "amanhã"; com UTC o contador zeraria às 21:00 de Brasília, e "amanhã" não bateria com o que o Cliente entende. O `OrderService` já usa o mesmo fuso. Usar `ZoneId` (e não `-03:00` fixo) mantém o certo se o horário de verão voltar.

**D2 — b) Corrida.** A consulta de Acesso do serviço ganha `for update of a`: trava a linha de `accesses`, que é única por Cliente e Produto (`accesses_customer_product_key`). Dois cliques do mesmo Cliente no mesmo Produto passam um de cada vez; o segundo só conta depois que o primeiro gravou o Registro e fez commit. Outros Clientes e outros Produtos não esperam. A transação vem de um `TransactionTemplate` (como no `OrderService`), para valer também quando o teste monta o serviço à mão.
- *Custo:* a conexão do banco fica presa durante a assinatura no Supabase (normalmente ~100 ms). Aceito pelo Sérgio com o ajuste da D7: no pior caso, 3 s de conexão + 5 s de resposta, nunca os 60 s do envio de arquivo.
- *Alternativas descartadas:* gravar o Registro antes de assinar (reserva) deixaria Registro sem link quando o Storage falha, contrariando o #33; trava em memória não vale com mais de uma máquina; `pg_advisory_xact_lock` faria o mesmo que a linha do Acesso, com hash e mais código.
- Também trava a mesma linha que um cancelamento de Acesso atualiza: o cancelamento espera no máximo o fim de uma emissão. Sem efeito visível.

**D3 — c) Resposta da API ao 11º.** `429 Too Many Requests` com `{"errors": {"download": "DOWNLOAD_LIMIT"}}` (`ProblemDetail`, como os outros códigos do projeto), `Cache-Control: no-store` (padrão do Spring Security). Exceção pública `DownloadLimitReachedException` no pacote `product` (o handler fica no pacote `web`, como o de `ProductNotFoundException`). Sem `Retry-After`: a tela não usa e o texto já diz "amanhã".

**D4 — Tela.** `requestDownloadLink` trata `429` com `DownloadLimitError`. O `DownloadButton` ganha o resultado `"limit"`: texto 1 da seção 2 em `role="alert"`, mesmo lugar e estilo do erro atual. O botão continua clicável (um novo clique só repete a mensagem) e a Biblioteca não é recarregada.

**D5 — d) Separação.** A contagem filtra por Cliente e Produto, e a trava é a linha do Acesso daquele par. Teste com outro Produto do mesmo Cliente e outro Cliente do mesmo Produto (Task 1).

**D6 — e) E2E: sim, barato.** O e2e do Download do #33 já tem Cliente, Produto e compra prontos e já fez 2 Downloads. Acrescento no fim: 8 `POST` pela API com o token CSRF (padrão já usado no mesmo teste) respondendo `200`, depois o clique em "Baixar" mostra o texto 1, e um `POST` direto responde `429`. Custo: ~10 requisições a mais num teste que já existe.

**D7 — Tempo limite próprio da assinatura (ajuste do Sérgio).** O `SupabaseProductFileStorage` passa a ter dois `RestClient`: o atual (conexão 2 s, resposta 60 s) para enviar, ler e apagar, e um `signClient` só para `signedDownloadUrl`, com **conexão 3 s e resposta 5 s** (valores do Sérgio), constantes `SIGN_CONNECT_TIMEOUT` e `SIGN_READ_TIMEOUT`. O `signClient` sai de `builder.clone()` com outra `JdkClientHttpRequestFactory`. Estourou o tempo: `ProductFileStorageException` (pelo `failure("sign", ...)` que já existe, sem URL nem chave), e daí o caminho do #33: `503` sem corpo, nenhum Registro, e a transação desfeita libera a trava e devolve a conexão ao pool.

**f) O que não será provado, e por quê.**
- **Corrida entre máquinas diferentes:** a trava é do PostgreSQL, então vale para várias instâncias, mas o teste roda numa só JVM (duas threads). Não há como subir duas APIs no CI de forma barata.
- **Esgotamento do pool com muitos downloads e Supabase lento ao mesmo tempo:** não testado como carga. Provado: a assinatura desiste em ~5 s e a conexão volta ao pool (Tasks 1 e 2). O Supabase de verdade não roda no CI; o tempo limite é provado contra um servidor HTTP local lento.
- **Virada do dia com espera real:** provada com relógio fixo (`Clock.fixed`) passado ao serviço, não esperando a meia-noite.
- **E2E perto da meia-noite de Brasília:** se o e2e atravessar a 00:00 entre o 10º e o 11º pedido, o 11º passa. Raro e aceito; a virada é provada no teste de integração.
- **Horário de verão:** o Brasil não tem desde 2019; o uso de `ZoneId` cobre se voltar, sem teste próprio.

## 2. Textos que o usuário vê

| # | Onde | Texto |
|---|---|---|
| 1 | Mensagem abaixo do botão, ao recusar o 11º | `Você já baixou este Produto 10 vezes hoje. Tente novamente amanhã.` |

Nenhum outro texto muda ("Baixar", "Preparando…", "Download iniciado…" e os erros do #33 ficam como estão).

## Review Focus

1. Sem Acesso (ou Acesso cancelado) com 10 Registros no dia: deve dar `404`, nunca `429` → teste na Task 1.
2. Storage fora no 11º pedido: deve dar `429` (a recusa vem antes de assinar), não `503` → teste na Task 1.
3. Pedido às 21:00 de Brasília (00:00 UTC) com 10 links do dia: continua recusado → teste na Task 1.
4. Clique duplo no 10º: um link só → teste de concorrência na Task 1.
5. Na tela, depois do `429`, o botão continua ativo e o foco não some → teste na Task 4.

---

### Task 1: Limite e trava no serviço

**Files:**
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/product/ProductDownloadService.java`
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/product/ProductDownloads.java`
- Create: `backend/src/main/java/br/com/sergio/sapiensia/product/DownloadLimitReachedException.java`
- Modify: `backend/src/test/java/br/com/sergio/sapiensia/product/SwitchableProductFileStorage.java` (contador de assinaturas e bloqueio)
- Modify: `backend/src/test/java/br/com/sergio/sapiensia/product/ProductDownloadServiceTest.java` (novo construtor)
- Create: `backend/src/test/java/br/com/sergio/sapiensia/product/DownloadDailyLimitTest.java`

**Interfaces:**
- Produces:
  - `public class DownloadLimitReachedException extends RuntimeException` (sem mensagem com dado do Cliente).
  - `ProductDownloadService(ProductDownloads, ProductFileStorage, Clock, PlatformTransactionManager)`; `static final int DAILY_LIMIT = 10`; `static final ZoneId DAY_ZONE`; `Optional<String> issue(UUID customerId, UUID productId)` lança `DownloadLimitReachedException`.
  - `ProductDownloads.findOwned` passa a ter `for update of a` (só válido dentro de transação; javadoc diz isso).
  - `long ProductDownloads.countIssued(UUID customerId, UUID productId, Instant from, Instant to)`.
  - Em `SwitchableProductFileStorage`: `int signCalls()` e `void holdSigns(CountDownLatch release)` (a assinatura espera o latch; `null` desliga). `@AfterEach` desliga.

`DownloadDailyLimitTest`: contexto isolado (`test.isolated-context=download-daily-limit`, `app.storage.file.dir=target/test-storage-download-limit`), `SwitchableProductFileStorageConfig`, Clientes e Produtos próprios com Acesso e Arquivo (como `ProductDownloadServiceTest.setUp`). Cada teste monta `new ProductDownloadService(downloads, storage, Clock.fixed(<instante>, UTC), transactionManager)`.

- [ ] **Step 1: Testes que falham**
  - `@DisplayName("SEC-015: o 11º link do dia é recusado antes de assinar e sem gravar Registro")`: 10 `issue` às `2026-11-14T10:00-03:00` devolvem link; `storage.failSigns(true)`; o 11º lança `DownloadLimitReachedException` (não `ProductFileStorageException`); Registros do par = 10.
  - `@DisplayName("SEC-015: o dia é o de Brasília: zera à 00:00 de São Paulo, não à 00:00 UTC")`: 10 links às `2026-11-14T20:59:59-03:00`; às `21:00:00-03:00` (00:00 UTC) e às `23:59:59-03:00` recusa; às `2026-11-15T00:00:00-03:00` devolve link; Registros = 11.
  - `@DisplayName("SEC-015: a contagem é separada por Cliente e por Produto")`: Cliente A com 10 links no Produto P; A no Produto Q devolve link; Cliente B no P devolve link.
  - `@DisplayName("SEC-015: dois pedidos ao mesmo tempo no 10º geram um link só")`: 9 links; `holdSigns(release)`; thread A chama `issue` e fica presa na assinatura (`signCalls()` = 10); thread B chama `issue`; espera (até 10 s) `select count(*) from pg_stat_activity where wait_event_type = 'Lock' and query like '%for update of a%'` ser ≥ 1; confere `signCalls()` ainda 10; `release.countDown()`; resultado: exatamente um link e uma `DownloadLimitReachedException`; Registros = 10.
  - `@DisplayName("SEC-011: Acesso cancelado com o limite batido continua 404, não 429")`: 10 links; `update accesses set cancelled_at = now()` do par; `issue` devolve vazio.
  - `@DisplayName("SEC-015: Storage lento que desiste não grava Registro, devolve a conexão ao pool e solta a trava")`: anota `getActiveConnections()` do `HikariPoolMXBean` (do `DataSource`, que é `HikariDataSource`); `failSigns(true)` + `holdSigns(release)`; thread A chama `issue` e fica presa na assinatura; confere conexões ativas = base + 1 (o custo da D2); `release.countDown()`; A lança `ProductFileStorageException`; conexões ativas voltam à base (esperar até 5 s); Registros inalterados; `failSigns(false)` e um novo `issue` do mesmo par devolve link sem esperar (trava solta).
- [ ] **Step 2:** `cd backend; .\mvnw.cmd test -Dtest=DownloadDailyLimitTest` → falham (11º passa; teste da corrida falha com `signCalls()` = 11).
- [ ] **Step 3: Implementar.** No `issue`, dentro do `TransactionTemplate`: `findOwned` (trava) → vazio devolve `Optional.empty()` → `countIssued(dia)` ≥ `DAILY_LIMIT` lança `DownloadLimitReachedException` → assina → `record` → link. Janela: `LocalDate.now(clock.withZone(DAY_ZONE))` não serve (o `MutableClock` dos testes ignora `withZone`); use `clock.instant().atZone(DAY_ZONE).toLocalDate()` e `atStartOfDay(DAY_ZONE)` para hoje e amanhã. Atualizar o javadoc da classe (agora há transação, e por quê). Ajustar `ProductDownloadServiceTest` ao novo construtor.
- [ ] **Step 4:** o mesmo teste, mais as classes do grep de IDs → passam.
- [ ] **Step 5:** `just check-rapido` → verde. Commit `feat: daily download limit per customer and product (#34)`.

### Task 2: Tempo limite curto da assinatura no Supabase (D7)

**Files:**
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/product/SupabaseProductFileStorage.java`
- Modify: `backend/src/test/java/br/com/sergio/sapiensia/product/SupabaseProductFileStorageWireTest.java` (servidor HTTP local de verdade)
- Modify, se preciso: `backend/src/test/java/br/com/sergio/sapiensia/product/SupabaseProductFileStorageTest.java` (o `Fixture` usa um builder espiado com `MockRestServiceServer`; faça `clone()` devolver o mesmo builder espiado para os dois clientes passarem pelo servidor simulado)

**Interfaces:** `static final Duration SIGN_CONNECT_TIMEOUT = Duration.ofSeconds(3)`, `SIGN_READ_TIMEOUT = Duration.ofSeconds(5)`; o construtor de teste `(RestClient.Builder, url, secretKey, bucket, coverBucket)` não muda. `put`, `get` e `delete` continuam no cliente atual (2 s / 60 s).

- [ ] **Step 1: Testes que falham** (no `SupabaseProductFileStorageWireTest`):
  - `"SEC-015: a assinatura desiste em 5 s quando o Storage demora"`: a rota de assinatura espera 8 s antes de responder; `signedDownloadUrl` lança `ProductFileStorageException` em menos de 7 s (medido); a mensagem não contém a URL do servidor, a chave nem `token`.
  - `"O envio de arquivo continua com o tempo limite longo"`: a rota de envio espera 6 s e responde `200`; `put` termina sem exceção (prova que os 5 s valem só para a assinatura).
- [ ] **Step 2:** `.\mvnw.cmd test -Dtest=SupabaseProductFileStorageWireTest` → o primeiro falha (hoje espera os 8 s e assina).
- [ ] **Step 3:** `signClient` como na D7; comentário do `requestFactory` atualizado.
- [ ] **Step 4:** `-Dtest=SupabaseProductFileStorageWireTest+SupabaseProductFileStorageTest` → passam (os testes de log do Storage seguem verdes).
- [ ] **Step 5:** `just check-rapido`. Commit `fix: short timeout for signing the download link (#34)`.

### Task 3: `429 DOWNLOAD_LIMIT` na API

**Files:**
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/web/ApiExceptionHandler.java`
- Modify: `backend/src/test/java/br/com/sergio/sapiensia/payment/LibraryDownloadTest.java`

**Interfaces:** Consumes `DownloadLimitReachedException` (Task 1). Handler: `refusal(HttpStatus.TOO_MANY_REQUESTS, Map.of("download", "DOWNLOAD_LIMIT"))`.

- [ ] **Step 1: Teste que falha** — `@DisplayName("SEC-015: o 11º pedido do dia responde 429 DOWNLOAD_LIMIT sem link")`: com Acesso concedido (`grant`), 10 `POST /api/library/{id}/download` → `200`; o 11º → `429`, corpo com `errors.download == "DOWNLOAD_LIMIT"`, sem `url` e sem `local-downloads`, `Cache-Control` contém `no-store`; Registros do Cliente = 10; a saída capturada não tem `ERROR` do `LibraryDownloadController`.
- [ ] **Step 2:** `.\mvnw.cmd test -Dtest=LibraryDownloadTest` → falha (hoje a exceção vira `500`).
- [ ] **Step 3:** handler no `ApiExceptionHandler`.
- [ ] **Step 4:** teste passa, com as classes do grep de IDs.
- [ ] **Step 5:** `just check-rapido`. Commit `feat: answer 429 DOWNLOAD_LIMIT on the 11th download (#34)`.

### Task 4: Mensagem na Biblioteca

**Files:**
- Modify: `frontend/src/features/library/api.ts`, `DownloadButton.tsx`, `messages.ts`
- Modify: `frontend/src/features/library/LibraryPage.test.tsx`

**Interfaces:** `export class DownloadLimitError extends Error {}` em `api.ts`; `requestDownloadLink` lança no `429`. `LIBRARY_TEXTS.downloadLimit` = texto 1 da seção 2. `Outcome` ganha `"limit"`.

- [ ] **Step 1: Teste que falha** — `"SEC-015: o 429 mostra a mensagem do limite e o botão continua ativo"`: `fetch` responde a Biblioteca com 1 item e o `POST` com `429`; clicar "Baixar {título}" mostra `role="alert"` com o texto 1; o botão tem `aria-disabled="false"` e continua focável; `window.location.assign` não foi chamado; a Biblioteca não foi buscada de novo.
- [ ] **Step 2:** `just check-rapido` → falha nesse teste.
- [ ] **Step 3:** implementar (D4).
- [ ] **Step 4:** `just check-rapido` → verde. `/verificar-tela` na Biblioteca com o `429` simulado (desktop, celular, teclado).
- [ ] **Step 5:** Commit `feat: show the daily download limit message (#34)`.

### Task 5: E2E do limite

**Files:** Modify `frontend/e2e/admin-panel.e2e.ts` (teste do Download do #33).

- [ ] **Step 1:** no fim do `try`, com o `buyer` (2 Downloads feitos): 8 `buyer.request.post("/api/library/<id>/download")` com `X-XSRF-TOKEN` do contexto `first` → todos `200`; clicar "Baixar {título}" → texto 1 visível; um `POST` direto → `429`. Título do trecho com `SEC-015`.
- [ ] **Step 2:** Na pasta principal `just db`; na worktree `just dev-back` e `just dev-front`; depois `just e2e`. Esperado: todos passam. Conferir o fluxo à mão no navegador (10 cliques e a mensagem).
- [ ] **Step 3:** Commit `test: daily download limit e2e (#34)`.

### Fim

- Skill `verificar-entrega` (tabela). Push `git push -u origin feat/34-limite-downloads` e PR com `Closes #34`, citando SEC-015 e ADR-0007, só com o OK do Sérgio.
- Acompanhar o CI até os 3 jobs ficarem verdes; se falhar, parar e mostrar.
