# Um Postgres para a suíte de testes (#122) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** APROVADO pelo Sérgio em 15/11/2026 com ajustes: D1 trocada (IntelliJ continua aberto; "antes" = CI da PR #123 + check local que não termina; "depois" = `just check-rapido` completo na branch); D2 a D5 aceitas; D3 apaga o banco só depois de fechar o pool; conta de conexões explícita (seção 1.h); se o check for interrompido por memória, parar e levar as medições, sem repetir. Execução por subagent-driven-development (subagentes em Sonnet). Push e PR liberados no fim.

**Goal:** A suíte de testes do backend sobe um único contêiner Postgres e no máximo 8 contextos Spring vivos, sem afrouxar nem pular teste nenhum, para que o `just check-rapido` termine num computador de 16 GB.

**Architecture:** O `TestcontainersConfiguration` deixa de declarar o contêiner como bean. Um contêiner estático, iniciado uma vez por JVM, atende a suíte toda; cada contexto Spring recebe um banco novo (`create database test_<n>`) por um bean `JdbcConnectionDetails`, e o banco é apagado quando o contexto fecha. O cache de contextos do Spring cai de 32 para 8 (`spring.properties` de teste), então os contextos antigos fecham, e com eles o db-scheduler, o pool de conexões, o Tomcat e o banco.

**Tech Stack:** Spring Boot 4.1.2 (`org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails`), Spring TestContext (`spring.test.context.cache.maxSize`), Testcontainers 2.0.5 (`org.testcontainers.postgresql.PostgreSQLContainer`), Postgres 18, JUnit 5. **Nenhuma dependência nova, nenhum código de produção alterado, nenhum texto visto pelo usuário.**

**Spec:** issue #122 (origem: #29).

## Global Constraints

- Nenhum teste é afrouxado, pulado ou removido; nenhuma asserção muda. Só muda de onde vêm a URL, o usuário e a senha do banco.
- Só arquivos em `backend/src/test/`, mais `CLAUDE.md` (seção "Reaproveitar antes de criar") e este plano.
- Nada de `withReuse(true)`: o contêiner continua sob o Ryuk, que o remove quando a JVM de teste termina (Windows e Linux).
- O comando do contêiner mantém o padrão do módulo (`postgres -c fsync=off`, conferido no jar 2.0.5) e acrescenta `-c max_connections=200`.
- Um comando pesado por vez; com menos de 3 GB livres, parar e esperar.

## Review Focus

1. Teste que reinicia o sistema sobre o mesmo banco (`DbSchedulerRestartTest`, `OrderPaidEmailRestartTest`): o banco precisa sobreviver ao `close()` do primeiro contexto. Coberto: eles criam o banco pelo `newDatabase()` estático, fora de qualquer contexto, e só o apagam no `@AfterAll`.
2. Contexto despejado do cache e recriado depois (3 vezes por execução com tamanho 8, seção 1.b): recebe banco novo e vazio. Teste que dependesse de linhas deixadas por outra classe quebraria. Coberto só pela suíte completa verde nas duas ordens (Windows e CI); ver "O que não será provado".
3. Conexões: o pior caso dá 109 (seção 1.h), acima das 100 do padrão do Postgres. Coberto por `max_connections=200` e pelo teste `shouldStartTheSharedServerWithRoomForTheCachedContexts`.
4. Banco apagado antes de o pool fechar: o `TestDatabase` é dependência do `JdbcConnectionDetails`, que é dependência do `DataSource`, então o Spring destrói o `DataSource` (fecha o pool do Hikari, depois de parar o db-scheduler, que depende dele) antes de chamar `drop()`. O `with (force)` só encerra o que sobrar (conexão direta de teste). `shouldDropTheDatabaseEvenWithOpenConnections` e `shouldCloseThePoolAndDropTheDatabaseWhenTheContextCloses` conferem os dois resultados, mas não a ordem (com `with (force)`, apagar antes também passaria); a ordem vem da dependência entre beans e foi provada no CI: 78 contextos despejados, 0 `drop` com falha, 0 conexão encerrada à força, 0 erro de pool.
5. Papéis (`create role`) valem para o servidor inteiro, não por banco: `FinancialRecordImmutabilityTest` e `FlywaySupabaseBaselineTest` já usam nomes aleatórios e o segundo apaga os seus. Nenhuma migration cria papel (conferido com grep). Sem teste novo.

---

## 1. Medições e decisões (a a g do pedido)

### Medição na main (1e5a4da), 15/11/2026

| Medida | Valor | Como |
|---|---|---|
| Chaves de contexto Spring distintas | **85**, todas com `TestcontainersConfiguration`, ou seja, 85 Postgres | censo das anotações das 158 classes de teste |
| Postgres criados na suíte | **87** (85 contextos + 2 abertos à mão nos testes de reinício) | log do CI da PR #123, mesmo código da main: `Creating container for image: postgres` |
| Contextos iniciados | 93 (85 + reinícios e o segundo sistema do `AdminMfaLogTest`) | mesmo log: `Started <X> in` |
| Postgres simultâneos | **até 33** (cache padrão de 32 contextos + 1 de teste de reinício); 32 vistos no #29 | teto do cache; nada fecha antes de 32 |
| Memória livre mínima, local | **2,81 GB aos 60 s, com só 3 Postgres de pé**; a trava de 3 GB encerrou a execução | `medir.ps1` (amostra a cada 5 s); começou com 4,52 GB livres, IntelliJ aberto (2,2 GB) |
| Custo por Postgres | ~100 a 115 MB a mais no `vmmemWSL` por contêiner | mesmas amostras |
| Tempo dos testes do backend | **9:44 no CI** (1306 testes, 163 classes); local não mensurável sem quebrar a trava | log do CI |
| `just lint` local | 8 s | cronometrado |

Por que tantos contextos (detalhe no censo):
- 4 chaves compartilhadas cobrem 30 classes: a padrão (MOCK, só `TestcontainersConfiguration`, 22 classes), "orders" (4), "asaas-webhook-persistence" (2), "payment-event-processing" (2).
- 81 chaves de uma classe só. **37 classes diferem apenas pelo valor de `test.isolated-context`**, de propósito: estado em memória (limite de login, relógio mutável, repositório de CSRF trocado pelo `csrf()` do MockMvc) e `delete from administrators`/`products` no `@BeforeEach`. Juntá-las economizaria 36 contextos.
- As outras diferem de verdade: propriedades de limite de login, `@MockitoSpyBean` (3 classes), `@Import` próprio (relógio, Pix, armazenamento, auditoria, sondas), níveis de log, perfil `prod`/`local`, intervalo do db-scheduler, diretório de arquivos por classe, `@DynamicPropertySource` (1), `@Nested` com configuração própria (4).

### Medição depois (branch, 15/11/2026)

| Medida | Antes (main) | Depois (branch) | Como |
|---|---|---|---|
| Testes do backend no CI | 9:44 (1306 testes) | **7:12** (1312 testes, 0 falhas, 0 puladas) | log do CI da PR #124 |
| Postgres criados na suíte (CI) | 87 | **1** | `Creating container for image: postgres` |
| Postgres simultâneos | até 33 | **1** | CI: 1 criado; local: amostras |
| Contextos Spring iniciados (CI) | 93 | 96 (+3 recriados depois de despejados, como a simulação previu) | `Started <X> in` |
| Contextos despejados do cache (CI) | 50 (cache de 32) | **78** (cache de 8) | pools do Hikari fechados no meio da execução, menos os 13 de classes que reiniciam o sistema ou usam `@DirtiesContext` |
| Bancos criados (CI) | — | ~99, derivado: 88 contextos + 11 `newDatabase()` diretos (8 no teste novo, 1 contexto mínimo dele, 2 dos testes de reinício); o log não tem uma linha por banco | contagem |
| `drop database` com falha (CI) | — | **0**; também 0 conexões encerradas à força, 0 erros de pool, 0 "too many clients" | `Could not drop test database`, `destroy method`, `administrator command`, `HikariPool` |
| Check local com IntelliJ aberto | não termina: trava de 3 GB aos 60 s (2,81 GB, 3 Postgres) | **não termina**: trava de 3 GB aos 81 s (2,90 GB, 1 Postgres) | `medir.ps1`; Maven + JVM de teste já levam a memória livre de ~4,1 a ~3,6 GB antes de qualquer Postgres → issue #126 |
| Ryuk | — | Maven morto pelo PID com o Postgres de pé → 0 contêiner do Testcontainers depois | `docker ps` |

Os fechamentos de contexto no fim da JVM não aparecem no log do CI (nem antes nem depois), então um `drop` que falhasse ali não seria visto; o contêiner é removido pelo Ryuk de qualquer jeito.

### a) Um contêiner; um banco por contexto Spring

- `TestcontainersConfiguration` ganha um contêiner `static` iniciado na primeira chamada e nunca parado à mão (o Ryuk o remove no fim da JVM).
- Cada contexto recebe um banco próprio criado na hora: o bean `TestDatabase` chama `newDatabase()`, que faz `create database test_<n>` (contador `AtomicInteger`) conectado ao banco padrão do contêiner; o bean `JdbcConnectionDetails` aponta o `DataSource` (e, por ele, o Flyway) para esse banco. O bean é destruído depois do `DataSource` (depende dele) e faz `drop database test_<n> with (force)`.
- Por que banco e não schema: o db-scheduler usa a tabela `scheduled_tasks` sem schema e o Flyway usa `public`; com um banco por contexto, nada no código de produção muda e um agendador em cache só enxerga as tarefas do seu banco. Isolamento igual ao de hoje (um contêiner por contexto), com um processo Postgres só.
- Os dois testes de reinício trocam o contêiner próprio por `TestcontainersConfiguration.newDatabase()` no `@BeforeAll` e `drop()` no `@AfterAll`: o banco vive fora dos contextos, então fechar e subir o sistema de novo encontra o mesmo banco, como hoje.
- `AdminMfaLogTest` e `FlywaySupabaseBaselineTest` hoje injetam o bean `PostgreSQLContainer` só para ler URL, usuário e senha; passam a injetar `TestDatabase`. Nenhuma asserção muda.
- `TestSapiensiaApplication` (o `main` de desenvolvimento com `with(TestcontainersConfiguration.class)`) continua funcionando: ganha um banco no contêiner compartilhado.

### b) Cache de contextos: 8

- `backend/src/test/resources/spring.properties` com `spring.test.context.cache.maxSize=8`. É lido pelo Spring tanto no Maven quanto no IntelliJ, sem mexer no `pom.xml`.
- Por que 8: simulei o cache LRU sobre a ordem real das classes (a do CI, Linux, e a alfabética do Windows). Contextos recriados a mais: 2 → +13/+8; 4 → +6/+6; **8 → +3/+3**; 12 → +1/0; 32 → 0. Com 8, são 3 contextos recriados (~5 a 6 s cada, uns 20 s em ~10 min) e 4 vezes menos contextos vivos que hoje (Tomcat de ~60 contextos RANDOM_PORT, agendador consultando o banco a cada 1 s, 10 conexões cada).
- Reduzir as combinações (as 36 que diferem só por `test.isolated-context`): **deixar para depois**, numa issue própria. Depois deste ticket um contexto extra custa uns 5 s de partida e um banco, não um contêiner; juntar classes mexe no isolamento de estado em memória de cada uma e precisa de revisão classe a classe. Ganho estimado: ~3 min de CI.

### c) Nenhum teste afrouxado; testes que dependem de banco vazio

- Todo contexto novo recebe banco recém-criado a partir do `template1`, o mesmo estado de um contêiner novo. Os que dependem disso continuam iguais: `SapiensiaApplicationTests.contextLoads` (sobe só com as migrations), `DemoSeedTest.LocalDemo` (contagens exatas 1, 1, 3, 3, 3), `CustomerServiceTest`, `CustomerControllerTest`, `OrdersTest`, `CatalogTest`, os `AdminProduct*`/`ProductFile*`/`ProductCover*` com `count(*)` da tabela inteira e os 18 que apagam `administrators`.
- Contexto recriado depois de despejado do cache recebe banco vazio, mais limpo que hoje. Só quebraria um teste que dependesse de linhas de outra classe, o que já seria um defeito; a suíte verde no Windows e no CI (ordens diferentes) é a prova.

### d) CI (Linux) e Windows

- Mesmo código nos dois; nenhuma propriedade de ambiente. O Ryuk continua ligado (sem `withReuse`), e é ele que remove o contêiner no fim da JVM, inclusive se ela for morta. Prova no Windows: matar o Maven pelo PID no meio da execução e conferir `docker ps` vazio (Task 3).

### e) Medição antes e depois (D1 ajustada)

- **Antes:** números do CI da PR #123 (87 Postgres criados, até 33 simultâneos, testes do backend em 9:44) mais o fato de o check local na main não terminar com o IntelliJ aberto (trava de 3 GB disparou aos 60 s, 2,81 GB livres, 3 Postgres de pé).
- **Depois:** `just check-rapido` completo na branch (lint + todos os testes do backend, porque a branch muda o pacote raiz de teste), **com o IntelliJ aberto**, pelo `medir.ps1` (amostra a cada 5 s memória livre, Postgres do Testcontainers, `vmmemWSL` e `java`; encerra pelo PID se a memória livre ficar abaixo de 3 GB duas vezes seguidas). Registrar contêineres simultâneos, memória livre mínima e tempo. No CI da PR, comparar o tempo dos testes do backend com os 9:44.
- **Interrompido por memória:** não repetir; parar e levar as medições ao Sérgio.

### h) Conexões no único Postgres

Pior caso de conexões abertas ao mesmo tempo:

| Origem | Conexões |
|---|---|
| Contextos vivos: 8 no cache + 1 sendo criado antes de o mais antigo ser despejado = 9 × 10 (Hikari: máximo 10 e mínimo ocioso 10, padrão; o db-scheduler usa o mesmo pool, sem conexões próprias) | 90 |
| Segundo sistema de teste: `DbSchedulerRestartTest`/`OrderPaidEmailRestartTest` (um sistema por vez, o primeiro fecha antes do segundo subir) ou `AdminMfaLogTest` (sistema com chave ruim); um teste por vez, então conta o maior: 10 do pool + 1 do `JdbcTemplate` direto | 11 |
| Conexões diretas: criar/apagar banco (1), `FlywaySupabaseBaselineTest` (1), `TestcontainersConfigurationTest` (até 3) | 5 |
| Reservadas ao superusuário pelo Postgres (`superuser_reserved_connections`) | 3 |
| **Total** | **109** |

- **Escolha: `max_connections=200`**, folga de 91 conexões (o pior caso usa 55%). Cada conexão ociosa custa poucos MB no Postgres; 109 ficam bem abaixo de um contêiner por contexto.
- **Pool do Hikari fica em 10**: `DownloadDailyLimitTest` confere a variação de conexões ativas e há testes de concorrência (`AdminProductConcurrencyTest`, `AdminProductPublicationConcurrencyTest`, `PaymentConcurrencyTest`); reduzir o pool mudaria o comportamento sob carga que eles provam.

### f) Repositório modelo

`ssergionp/java-react-template` tem o mesmo padrão (`@Bean @ServiceConnection PostgreSQLContainer` em `backend/src/test/java/com/example/app/TestcontainersConfiguration.java`), ou seja, um contêiner por contexto. Hoje tem poucos testes, mas cada projeto criado dele herda o problema. **Só anotado; nada muda lá neste ticket.**

### g) O que não será provado

- Que a suíte cabe na memória com outros projetos ou sistemas ligados ao mesmo tempo: a medição é com o computador como estiver no dia, IntelliJ aberto (D1).
- Que a contagem "antes" no Windows bate com a do CI: o "antes" local não termina; os números completos vêm do CI.
- Que o pior caso de 109 conexões acontece de fato: é uma conta, não uma medição.
- Que nenhum teste depende de dados deixados por outra classe em outras ordens de execução além das duas que vamos rodar (Windows e CI).
- O pico de heap da JVM de teste por contexto: medimos o processo `java` inteiro, não o heap.
- Comportamento com testes em paralelo (o projeto não usa paralelismo do JUnit nem mais de um fork).

## 2. Arquivos

- Modify: `backend/src/test/java/br/com/sergio/sapiensia/TestcontainersConfiguration.java`: contêiner estático, `TestDatabase`, `newDatabase()`, beans `TestDatabase` e `JdbcConnectionDetails`.
- Create: `backend/src/test/java/br/com/sergio/sapiensia/TestcontainersConfigurationTest.java` (sem Spring).
- Create: `backend/src/test/resources/spring.properties`.
- Modify: `scheduling/DbSchedulerRestartTest.java`, `payment/OrderPaidEmailRestartTest.java`, `admin/AdminMfaLogTest.java`, `FlywaySupabaseBaselineTest.java`: só a origem da URL/usuário/senha e o comentário sobre o contêiner próprio.
- Modify: `CLAUDE.md`: uma linha em "Reaproveitar antes de criar".

## 3. Decisões do Sérgio (15/11/2026)

- **D1. Medição com o IntelliJ aberto** (a sessão roda dentro dele): "antes" = CI da PR #123 + check local que não termina; "depois" = `just check-rapido` completo na branch (seção 1.e).
- **D2. `maxSize=8`**: aceita.
- **D3. Apagar o banco quando o contexto fecha**, com `drop database ... with (force)` só depois de o pool fechar: aceita.
- **D4. Reduzir combinações de contexto depois**, numa issue `needs-triage` separada citando o #122: aceita.
- **D5. Sem ADR**, regra no `CLAUDE.md`: aceita.

---

### Task 1: Um contêiner, um banco por contexto

**Files:**
- Modify: `backend/src/test/java/br/com/sergio/sapiensia/TestcontainersConfiguration.java`
- Create: `backend/src/test/java/br/com/sergio/sapiensia/TestcontainersConfigurationTest.java`
- Modify: `backend/src/test/java/br/com/sergio/sapiensia/scheduling/DbSchedulerRestartTest.java`, `backend/src/test/java/br/com/sergio/sapiensia/payment/OrderPaidEmailRestartTest.java`, `backend/src/test/java/br/com/sergio/sapiensia/admin/AdminMfaLogTest.java`, `backend/src/test/java/br/com/sergio/sapiensia/FlywaySupabaseBaselineTest.java`

**Interfaces:**
- Produces, em `TestcontainersConfiguration`:
  - `public record TestDatabase(String jdbcUrl, String username, String password, String name)` com `public void drop()` (`drop database <name> with (force)` pela conexão administrativa).
  - `public static TestDatabase newDatabase()`: inicia o contêiner compartilhado se preciso (sincronizado), cria `test_<n>` e devolve as credenciais (as do contêiner).
  - `@Bean(destroyMethod = "drop") TestDatabase testDatabase()` → `newDatabase()`.
  - `@Bean JdbcConnectionDetails jdbcConnectionDetails(TestDatabase db)` com `getJdbcUrl/getUsername/getPassword` do `db`.
  - Remove o bean `PostgreSQLContainer`; os beans `CompromisedPasswordChecker` e `RecordingEmailSender` ficam como estão.

- [ ] **Step 1: Write the failing tests** em `TestcontainersConfigurationTest` (JUnit puro, sem Spring):
  - `shouldGiveEachCallItsOwnDatabaseOnTheSameServer`: dois `newDatabase()` → mesmo host e porta (parse da URL), `name()` diferentes.
  - `shouldIsolateTablesBetweenDatabases`: `create table probe(id int)` no primeiro; no segundo, `select to_regclass('probe') is null` é `true`.
  - `shouldDropTheDatabaseEvenWithOpenConnections`: abre uma conexão no banco, chama `drop()`, e `select count(*) from pg_database where datname = ?` (pela conexão de outro banco) é `0`.
  - `shouldStartTheSharedServerWithRoomForTheCachedContexts`: `show max_connections` é `"200"`.
  - `shouldCloseThePoolAndDropTheDatabaseWhenTheContextCloses`: um `AnnotationConfigApplicationContext` mínimo registra `TestcontainersConfiguration` e um `HikariDataSource` criado a partir do bean `JdbcConnectionDetails`; abre uma conexão pelo pool; `context.close()`; depois do close o `HikariDataSource` está `isClosed()` e o banco não existe mais em `pg_database`. (Confere os dois resultados, não a ordem; a ordem foi provada no CI.)
- [ ] **Step 2: Run** `cd backend; .\mvnw.cmd test -Dtest=TestcontainersConfigurationTest` → falha de compilação (`newDatabase` não existe).
- [ ] **Step 3: Implement** o que está em Interfaces. Contêiner: `new PostgreSQLContainer(DockerImageName.parse("postgres:18")).withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=200")`. URL do banco: a do contêiner com o nome do banco trocado (`getJdbcUrl()` termina em `/<databaseName>`, com parâmetros depois de `?`).
- [ ] **Step 4: Run** o mesmo comando → 5 testes passam.
- [ ] **Step 5: Migrar os quatro testes** que liam o contêiner: os dois de reinício usam `static TestDatabase db = TestcontainersConfiguration.newDatabase()` no `@BeforeAll` e `db.drop()` no `@AfterAll` (o comentário sobre o contêiner próprio passa a explicar que o banco vive fora dos contextos); `AdminMfaLogTest` e `FlywaySupabaseBaselineTest` injetam `TestDatabase` no lugar de `PostgreSQLContainer`. Diff só nessas linhas e nos imports.
- [ ] **Step 6: Run** `cd backend; .\mvnw.cmd test "-Dtest=TestcontainersConfigurationTest,DbSchedulerRestartTest,OrderPaidEmailRestartTest,AdminMfaLogTest,FlywaySupabaseBaselineTest,SapiensiaApplicationTests,DemoSeedTest*,FinancialRecordImmutabilityTest"` mais as classes que citam os mesmos IDs (`git grep -l NFR-004 -- backend/src/test` etc., regra do CLAUDE.md) → tudo verde; `docker ps` durante a execução mostra 1 Postgres do Testcontainers.
- [ ] **Step 7: Commit** `test: share one Postgres container with a database per Spring context (#122)`

### Task 2: Cache de contextos com 8

**Files:**
- Create: `backend/src/test/resources/spring.properties`
- Modify: `backend/src/test/java/br/com/sergio/sapiensia/TestcontainersConfigurationTest.java`

**Interfaces:** nenhuma nova.

- [ ] **Step 1: Write the failing test** `shouldConfigureTheContextCacheSizeAsEight`: `SpringProperties.getProperty("spring.test.context.cache.maxSize")` é `"8"`.
- [ ] **Step 2: Run** `-Dtest=TestcontainersConfigurationTest` → falha (`null`).
- [ ] **Step 3: Create** `spring.properties` com a linha `spring.test.context.cache.maxSize=8` e um comentário de uma linha com o porquê (memória; seção 1.b do plano).
- [ ] **Step 4: Run** → passa.
- [ ] **Step 5: Commit** `test: keep at most 8 Spring test contexts alive (#122)`

### Task 3: Suíte completa, medição e documentação

**Files:**
- Modify: `CLAUDE.md` (seção "Reaproveitar antes de criar": `TestcontainersConfiguration.newDatabase()` é o jeito de um teste ter um banco fora de um contexto Spring; nunca um `PostgreSQLContainer` próprio)
- Modify: este plano (tabela "depois")

- [ ] **Step 1:** Medição "depois": `just check-rapido` completo pelo `medir.ps1`, IntelliJ aberto (D1) → termina sem a trava, 1306 testes ou mais, 0 falhas, 0 puladas a mais que na main; registrar máximo de Postgres simultâneos (esperado: 1), memória livre mínima e tempo. Interrompido por memória: parar e levar as medições ao Sérgio, sem repetir.
- [ ] **Step 2:** `docker ps --filter label=org.testcontainers=true` depois da execução → vazio (Ryuk).
- [ ] **Step 3:** Prova do Ryuk: rodar `mvnw test` de novo, matar o Maven pelo PID (árvore inteira) com um Postgres do Testcontainers de pé → `docker ps` sem contêiner do Testcontainers em até ~30 s.
- [ ] **Step 4:** Atualizar `CLAUDE.md` e a tabela "depois" do plano; commit `docs: one test database per Spring context (#122)`.
- [ ] **Step 5:** skill `verificar-entrega`; depois, com o OK do Sérgio, push e PR (`Closes #122`, tabela antes/depois, IDs citados). Issue de acompanhamento (D4) com `needs-triage` citando o #122, incluindo a anotação do repositório modelo.
