# Spike #6: db-scheduler no Java 25 — Plano de implementação

> **Para quem executar:** use superpowers:subagent-driven-development. Os passos usam checkbox (`- [ ]`).

**Objetivo:** provar com testes de integração que o db-scheduler roda tarefas no PostgreSQL com Java 25 e que tarefas pendentes e recorrentes sobrevivem a um reinício do contexto Spring; registrar o resultado no ADR-0005.

**Abordagem:** uma migration cria a tabela `scheduled_tasks`; o starter do db-scheduler para Spring Boot 4 entra no `pom.xml`; um único teste novo sobe o aplicativo duas vezes seguidas sobre o mesmo PostgreSQL (Testcontainers) para simular o reinício. Não há código de produção novo além da dependência e da migration.

**Stack:** Java 25, Spring Boot 4.1.2, Flyway, PostgreSQL 18 (Testcontainers), JUnit 5, db-scheduler 16.12.0.

**Spec:** NFR-004 (`docs/spec/sapiensia-spec.md:569`, "Tarefas sobrevivem a reinício; retry e dead-letter operacionais") e ADR-0005 (`docs/adr/0005-tarefas-em-segundo-plano-com-db-scheduler.md`). Issue #6, filha de #5.

## Fatos conferidos na fonte oficial (28/10/2026)

- Artefato para Spring Boot 4: `com.github.kagkarlsson:db-scheduler-spring-boot-4-starter`. O `-boot-starter` sem o 4 é para Spring Boot 3.
- Versão: o README cita 16.11.0, mas o Maven Central já tem a **16.12.0** (a mais recente), publicada em 29/05/2026, bem mais de 7 dias antes de hoje (regra de cooldown do modelo atendida). Uso a 16.12.0.
- A biblioteca é construída e testada com Java 17 (`.java-version` do repositório dela = 17.0.3). Nada declara Java 25: é exatamente o que o spike verifica.
- Tabela: `scheduled_tasks`, SQL em `db-scheduler/src/test/resources/postgresql_tables.sql` do repositório da biblioteca. A tabela **não** é criada sozinha; vem de migration.
- Propriedades úteis (`db-scheduler.*`): `enabled` (padrão true), `polling-interval` (em segundos), `immediate-execution-enabled` (padrão false), `shutdown-max-wait`, `table-name`.
- Beans do tipo `Task` são registrados automaticamente pelo starter.
- Ambiente local: Java 25.0.4.1, Docker 29.9.2. Check de base na main: OK (7 testes backend, 3 frontend).

## Decisões que dependem do seu OK

1. **Dependência nova:** `db-scheduler-spring-boot-4-starter:16.12.0` (Apache-2.0, já escolhida no ADR-0005).
2. **Exceção a uma regra global de testes.** A regra diz "nunca crie um container por classe de teste". Para simular reinício, o banco precisa continuar vivo enquanto o aplicativo é desligado e ligado de novo. O container padrão do projeto morre junto com o contexto, então este teste cria um `PostgreSQLContainer` próprio (`postgres:18`, o mesmo do projeto). É uma única classe e só neste spike.
3. **Como leio "repete após reinício" do critério:** testo as duas coisas, (a) uma tarefa **pendente** que ainda não rodou é executada depois do reinício e (b) uma tarefa **recorrente** continua repetindo depois do reinício, sem duplicar a linha no banco. Se você queria só uma delas, corto a outra.

## Textos que o usuário vai ver

Nenhum. É um spike de backend: não há tela, mensagem nem texto novo.

## Arquivos

- Modificar: `backend/pom.xml` (dependência)
- Criar: `backend/src/main/resources/db/migration/V<próximo>__create_scheduled_tasks.sql` (número definido pela skill `nova-migration`; hoje o `V1__init.sql` está vazio)
- Criar: `backend/src/test/java/br/com/sergio/sapiensia/scheduling/ScheduledTasksMigrationTest.java`
- Criar: `backend/src/test/java/br/com/sergio/sapiensia/scheduling/DbSchedulerRestartTest.java`
- Modificar: `docs/adr/0005-tarefas-em-segundo-plano-com-db-scheduler.md` (resultado do spike)

## Riscos

- Se a Java serialization do `task_data` (formato padrão da biblioteca) falhar no Java 25, o spike falha de verdade: é o resultado esperado de um spike, e o ADR é reaberto.
- Subir o aplicativo completo fora do `@SpringBootTest` pode exigir `server.port=0` e perfil web; se a `SecurityConfig` reclamar, ajusto no teste, sem mexer no código de produção.
- Testes com tempo podem ser instáveis; por isso o teste usa `CountDownLatch` com timeout generoso e intervalos de polling curtos (1 s), sem `sleep` fixo e sem biblioteca nova (Awaitility não está no `pom.xml`).
- Se o spike falhar, **não** implemento o JobRunr: registro o resultado, reabro o ADR e abro uma issue para decidir.

---

### Task 1: Migration da tabela e dependência

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/main/resources/db/migration/V<próximo>__create_scheduled_tasks.sql`
- Test: `backend/src/test/java/br/com/sergio/sapiensia/scheduling/ScheduledTasksMigrationTest.java`

**Interfaces:**
- Produces: tabela `scheduled_tasks` com as colunas do esquema oficial (`task_name`, `task_instance`, `task_data`, `execution_time`, `picked`, `picked_by`, `last_success`, `last_failure`, `consecutive_failures`, `last_heartbeat`, `version`, `priority`), chave primária `(task_name, task_instance)` e os 3 índices do SQL oficial.

- [ ] **Step 1: Escrever o teste que falha.** `ScheduledTasksMigrationTest` com `@Import(TestcontainersConfiguration.class)` e `@SpringBootTest`, injetando `JdbcTemplate`. Método `shouldCreateSchedulerTableWithOfficialColumns` com `@DisplayName("NFR-004: a migration cria a tabela do db-scheduler")`: consulta `information_schema.columns` para `scheduled_tasks` e confere que as 12 colunas acima existem; consulta `pg_indexes` e confere `execution_time_idx`, `last_heartbeat_idx` e `priority_execution_time_idx`.
- [ ] **Step 2: Rodar e ver falhar.** `cd backend; .\mvnw.cmd test -Dtest=ScheduledTasksMigrationTest`. Esperado: FAIL (tabela não existe; colunas vêm vazias).
- [ ] **Step 3: Criar a migration** com a skill `nova-migration`, copiando o SQL oficial (create table + 3 índices; sem os comentários do arquivo original), nome `create_scheduled_tasks`.
- [ ] **Step 4: Rodar e ver passar.** Mesmo comando. Esperado: PASS.
- [ ] **Step 5: Adicionar a dependência** `com.github.kagkarlsson:db-scheduler-spring-boot-4-starter:16.12.0` no `pom.xml` (depois de `springdoc`, mesma indentação por tabs). Rodar `.\mvnw.cmd test -Dtest=SapiensiaApplicationTests`; esperado: PASS (o contexto sobe com o scheduler ligado e a tabela já existente).
- [ ] **Step 6: Commit.** `git add backend/pom.xml backend/src docs/superpowers/plans` e `git commit -m "feat: add db-scheduler dependency and scheduled_tasks migration"` (mais a linha `Co-Authored-By` do ambiente).

### Task 2: Teste de execução e de reinício

**Files:**
- Test: `backend/src/test/java/br/com/sergio/sapiensia/scheduling/DbSchedulerRestartTest.java`

**Interfaces:**
- Consumes: tabela `scheduled_tasks` (Task 1); beans `Task` auto-registrados pelo starter; bean `Scheduler` (`scheduler.schedule(taskInstance, Instant)`).
- Produces: nada que outra task use.

Estrutura da classe (uma só, sem `@SpringBootTest`):
- `static PostgreSQLContainer postgres` (`postgres:18`) iniciado no `@BeforeAll` e parado no `@AfterAll`. **Obrigatório (condição do OK):** um comentário em inglês, logo acima do campo, explicando por que esta classe cria o próprio container em vez de usar `TestcontainersConfiguration`: o banco precisa continuar vivo enquanto o contexto Spring é desligado e religado para simular o reinício, e o container padrão morre junto com o contexto.
- Método auxiliar `ConfigurableApplicationContext startApp(Duration pollingInterval)` que usa `SpringApplicationBuilder(SapiensiaApplication.class, TestTasks.class)` com `spring.datasource.url/username/password` do container, `server.port=0` e `db-scheduler.polling-interval` em segundos.
- `@TestConfiguration static class TestTasks` com: um `OneTimeTask<String>` chamado `probe-one-time` que conta execuções e guarda o último payload recebido; um `RecurringTask<Void>` chamado `probe-recurring` (`Tasks.recurring(..., FixedDelay.ofSeconds(1))`) que conta execuções. Os contadores são `CountDownLatch`/`AtomicInteger` estáticos, zerados antes de cada teste.

- [ ] **Step 1: Escrever os 3 testes** (cada um com `@DisplayName("NFR-004: ...")`):
  - `shouldRunOneTimeTaskWithPayloadOnPostgres`: sobe o app (polling 1 s), agenda `probe-one-time` com o payload `"payload-é-ç"` para daqui a 1 s, espera o latch (timeout 20 s) e confere que o payload chegou idêntico. Exercita a serialização do `task_data` no Java 25.
  - `shouldRunPendingTaskAfterRestart`: sobe o app com polling de 3600 s (a primeira leitura acontece na partida, antes de agendar, então nada roda), agenda a tarefa para daqui a 1 s, fecha o contexto, confere por JDBC que a linha existe com `picked = false`, sobe o app de novo (polling 1 s) e espera o latch (timeout 20 s).
  - `shouldKeepRecurringTaskRepeatingAfterRestart`: sobe o app, espera a recorrente rodar pelo menos 1 vez, fecha o contexto, zera o contador, sobe o app de novo, espera a recorrente rodar pelo menos 1 vez e confere por JDBC que existe exatamente 1 linha para `probe-recurring` em `scheduled_tasks`.
- [ ] **Step 2: Rodar.** `cd backend; .\mvnw.cmd test -Dtest=DbSchedulerRestartTest`. Esperado: PASS nos 3. Se algum falhar, é o resultado do spike: não "conserto até passar"; investigo a causa (skill systematic-debugging) e registro no ADR.
- [ ] **Step 3: Provar que o teste não é vazio.** Troque temporariamente, no segundo start de `shouldRunPendingTaskAfterRestart`, a propriedade para `db-scheduler.enabled=false`, rode e confirme que o teste **falha** por timeout; desfaça a troca e rode de novo para confirmar PASS. Mostro os dois resultados.
- [ ] **Step 4: Formatar e commitar.** `just fmt`, depois `git add backend/src/test` e `git commit -m "test: prove db-scheduler runs and survives restart on Java 25"`.

### Task 3: Registrar o resultado no ADR-0005 e fechar

**Files:**
- Modify: `docs/adr/0005-tarefas-em-segundo-plano-com-db-scheduler.md`

- [ ] **Step 1: Atualizar o ADR** com o resultado real: na seção `Consequences`, trocar a frase "Antes de fixar a escolha, um teste de integração com Java 25 deve passar..." por um registro do que foi provado, com versões (db-scheduler 16.12.0, Java 25.0.4.1, Spring Boot 4.1.2, PostgreSQL 18), os 3 testes e a data 28/10/2026. Se os testes tiverem passado, status continua `accepted` e a decisão fica "confirmada". Se tiverem falhado, mudar o status para refletir a reabertura e descrever a falha e o próximo passo (avaliar JobRunr), sem implementá-lo.
- [ ] **Step 2: Rodar o check completo.** `just check-resumo`. Esperado: `CHECK OK`, com 11 testes no backend (7 de antes + 1 da migration + 3 do restart) e os mesmos 3 do frontend.
- [ ] **Step 3: Commit.** `git add docs/adr` e `git commit -m "docs: record db-scheduler Java 25 spike result in ADR-0005"`.
- [ ] **Step 4: Skill `verificar-entrega`** e mostrar a tabela dela. Push e PR (`Closes #6`) só com o seu OK.

---

## Revisão do plano contra a issue

| Critério da issue | Onde é coberto |
|---|---|
| Migration do Flyway cria a tabela | Task 1 (teste `shouldCreateSchedulerTableWithOfficialColumns`) |
| Teste de integração executa tarefa agendada e a repete após reinício (Java 25, Testcontainers) | Task 2 (3 testes) |
| Resultado registrado no ADR-0005 | Task 3 |
| Requisitos NFR-004 e ADR-0005 | IDs nos `@DisplayName` e no ADR |

Ressalva honesta: o app de produção ainda não tem nenhuma tarefa real, então este spike não prova retry nem dead-letter (outra parte da frase do NFR-004); isso fica para o ticket que criar a primeira tarefa de verdade.
