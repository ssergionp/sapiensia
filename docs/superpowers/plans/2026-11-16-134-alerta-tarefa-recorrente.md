# #134 Alerta de tarefa recorrente que falha seguidas vezes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

Plano do modo noturno: as decisões marcadas **DECIDIDO NO MODO NOTURNO — conferir** não tiveram o OK do Sérgio antes da execução.

**Goal:** Quando uma tarefa recorrente do db-scheduler falha 3 vezes seguidas, o Administrador recebe um alerta (NFR-009), uma vez por sequência de falhas.

**Architecture:** Um `FailureHandler` próprio no pacote `alert` faz o que o padrão das tarefas recorrentes já faz (`OnFailureReschedule` com o mesmo `Schedule`) e, quando a falha atual é a 3ª seguida, grava um alerta com `AdminAlerts.recordSafely`. Cada tarefa recorrente, menos a `admin-alert-dispatch`, passa a usar esse handler. O contador é o do próprio db-scheduler: `Execution.consecutiveFailures` é o número de falhas seguidas *antes* da atual (em sucesso volta a 0; em falha, `reschedule` grava `consecutiveFailures + 1` — conferido no bytecode do 16.12.0).

**Tech Stack:** Java 25, Spring Boot 4.1, db-scheduler 16.12.0, JUnit 5, Mockito, Testcontainers.

**Spec:** issue #134 (Refs #38, decisão D5 de `docs/superpowers/plans/2026-11-15-38-alertas.md`).

## Decisões

- **N1 — DECIDIDO NO MODO NOTURNO — conferir:** o alerta sai na **3ª falha seguida** (constante `CONSECUTIVE_FAILURES_TO_ALERT = 3`, sem propriedade nova), contada por execução e não por tempo. Para a `expire-pending-orders` (a cada minuto) são ~3 minutos; para as diárias, 3 dias.
- **N2 — DECIDIDO NO MODO NOTURNO — conferir:** **um alerta por sequência**: só quando a falha atual é exatamente a 3ª. Da 4ª em diante, nada novo (evita um e-mail por minuto). Se a tarefa voltar a funcionar e depois falhar 3 vezes de novo, sai outro alerta.
- **N3:** tipo novo `RECURRING_TASK_FAILING` em `AdminAlertType`, no **fim** da lista (prioridade mais baixa no limite por hora; não desloca os tipos de pagamento). `detail` = nome da tarefa (código fechado, cabe em `[A-Za-z0-9_-]{1,64}`). Sem migration (o banco valida só o formato do tipo).
- **N4:** a `admin-alert-dispatch` continua **sem** o handler (evita laço: alerta sobre o próprio envio de alertas). Um teste garante isso.
- **N5:** tarefas que passam a alertar: `expire-pending-orders`, `delete-orphan-cover-objects`, a limpeza de contas não confirmadas (`UnconfirmedCustomersCleanup`), a limpeza da auditoria (`AuditRetentionCleanup`) e a `asaas-reconciliation` (que já grava `RECONCILIATION_FAILED` por noite; este alerta a mais só chega na 3ª noite seguida). Um teste de contexto confere que toda `RecurringTask` exceto a dispatch usa o handler, para tarefa nova não ficar de fora.
- **N6:** log da 3ª falha: `log.error("Recurring task {} failed {} times in a row", taskName, 3)` — só o nome, nunca a mensagem da exceção (ADR-0006).
- **N7 (do /code-review; substitui N2) — DECIDIDO NO MODO NOTURNO — conferir:** com o alerta só na 3ª exata, um alerta perdido (falha ao gravar, ou deploy com a tarefa já falhando: o contador fica no banco) deixava a sequência inteira em silêncio. Agora o alerta sai quando a falha atual `n` (= `consecutiveFailures + 1`) é `3 × 2^k`: 3ª, 6ª, 12ª, 24ª… (`n % 3 == 0 && Integer.bitCount(n / 3) == 1`). Repete com intervalos que dobram, sem virar um e-mail por minuto. O log de N6 passa a levar `n`. Os números do texto do e-mail vêm da constante.
- **N8 (do /code-review):** o runbook diz que a `asaas-reconciliation` também tem o `RECONCILIATION_FAILED` (conferir lá primeiro) e que uma execução "morta" (processo derrubado no meio, revivida pelo db-scheduler) não passa pelo handler e não conta como falha. Ficam de fora, registrados na PR: nome e `Schedule` passados duas vezes em cada tarefa; teste de integração com uma tarefa que falha de verdade (pediria um contexto Spring novo); nomes literais no teste de ligação. Não procede: "8 tipos = limite de 8 e-mails/hora" (cada tipo pode mandar 4 por hora, uma a cada janela de 15 min, então o limite continua valendo).

## Textos que o usuário vê

O Administrador lê no e-mail de alerta (assunto `Alerta SapiensIA: <título> (<n>)`, corpo no molde dos outros tipos). **DECIDIDO NO MODO NOTURNO — conferir:**

- Título: `tarefa recorrente falhando`
- Introdução: `Uma tarefa em segundo plano que roda de tempos em tempos falhou pelo menos 3 vezes seguidas. Ela continua tentando sozinha no próximo horário.`
- O que fazer: `procure o nome da tarefa no log da aplicação para ver o motivo. Enquanto a tarefa continuar falhando, este aviso se repete com intervalos cada vez maiores: na 3ª, na 6ª, na 12ª falha seguida e assim por diante.`
  (Texto revisto por N7; o primeiro texto dizia "falhou 3 vezes seguidas" e "Este aviso sai uma vez por sequência de falhas…".)

Documentação (não é tela): uma linha nova na tabela "O que cada tipo quer dizer" de `docs/runbooks/webhook-asaas.md` e "tarefa recorrente que falha seguidas vezes" na lista do verbete de alerta do `GLOSSARY.md`.

## Global Constraints

- Nenhuma dependência nova, nenhuma migration (V28 fica reservada e não é usada se não for preciso).
- Log e alerta carregam só o nome da tarefa e a contagem (ADR-0006, SEC-036).
- Testes: só focados (`-Dtest=...`), um comando pesado por vez; cada teste novo falha **por asserção** antes do código.

## Review Focus

1. A falha da tarefa não pode deixar de ser reagendada se o alerta falhar → `recordSafely` nunca lança, e o teste do handler confere o `reschedule` também no caso do alerta.
2. 4ª, 5ª... falha seguida não gera alerta novo → teste com `consecutiveFailures` = 3 e 10.
3. Tarefa recorrente nova sem o handler → teste de contexto sobre todas as `RecurringTask`.
4. A dispatch alertando sobre si mesma → mesmo teste de contexto, ao contrário.
5. Próxima execução calculada errado (cron da conciliação) → o handler delega ao `OnFailureReschedule` com o **mesmo** `Schedule` da tarefa; o teste confere o horário passado ao `reschedule`.

---

### Task 1: Handler que reagenda e alerta na 3ª falha seguida, com o tipo e o texto novos

**Files:**
- Create: `backend/src/main/java/br/com/sergio/sapiensia/alert/RecurringTaskFailureAlerts.java`
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/alert/AdminAlertType.java` (+ `RECURRING_TASK_FAILING` no fim)
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/alert/AdminAlertEmail.java` (case novo com os textos acima, exatos)
- Modify: `docs/runbooks/webhook-asaas.md`, `GLOSSARY.md`
- Test: `backend/src/test/java/br/com/sergio/sapiensia/alert/RecurringTaskFailureAlertsTest.java` (unitário, Mockito)
- Test: `backend/src/test/java/br/com/sergio/sapiensia/alert/AdminAlertEmailTextTest.java` (+ uma linha no `texts()`)

**Interfaces:**
- Produces: `@Component public class RecurringTaskFailureAlerts` (construtor package-private `(AdminAlerts alerts)`), `static final int CONSECUTIVE_FAILURES_TO_ALERT = 3`, e
  `public <T> FailureHandler<T> rescheduleAndAlert(String taskName, Schedule schedule)` que devolve uma instância de `static final class RescheduleAndAlert<T> implements FailureHandler<T>` (package-private, para o teste de contexto da Task 2 usar `instanceof`; expõe `String taskName()`).

- [ ] **Step 1: testes que falham.** Em `RecurringTaskFailureAlertsTest` (Mockito: `AdminAlerts`, `ExecutionOperations<Void>`; `Execution` pelo construtor público de 9 argumentos com `consecutiveFailures` escolhido; `ExecutionComplete.failure(execution, start, done, new RuntimeException("x"))`; `Schedule` = `FixedDelay.ofMinutes(1)`):
  - `shouldAlertOnTheThirdConsecutiveFailure` (`consecutiveFailures = 2`): `verify(alerts).recordSafely(AdminAlertType.RECURRING_TASK_FAILING, "some-task")` e `verify(ops).reschedule(complete, done.plus(Duration.ofMinutes(1)))`.
  - `shouldNotAlertBeforeTheThirdFailure` (`consecutiveFailures` 0 e 1, parametrizado): `verifyNoInteractions(alerts)`; `reschedule` chamado com o mesmo horário.
  - `shouldNotAlertAgainAfterTheThirdFailure` (`consecutiveFailures` 3 e 10): `verifyNoInteractions(alerts)`; `reschedule` chamado.
  - Em `AdminAlertEmailTextTest.texts()`: `Arguments.of(AdminAlertType.RECURRING_TASK_FAILING, "tarefa recorrente falhando", <introdução>, <o que fazer>)` com os textos exatos da seção acima.
  Para o RED ser por asserção: primeiro acrescente só o valor do enum, a classe com `rescheduleAndAlert` devolvendo um handler que não faz nada e um `case RECURRING_TASK_FAILING -> new Text("", "", "")`; rode e veja falhar nas asserções.
- [ ] **Step 2:** `cd backend; .\mvnw.cmd test -Dtest=RecurringTaskFailureAlertsTest,AdminAlertEmailTextTest` → FAIL por asserção (Wanted but not invoked / expected texto).
- [ ] **Step 3: implementar.** `onFailure`: primeiro `new FailureHandler.OnFailureReschedule<T>(schedule).onFailure(complete, ops)`; depois, se `complete.getExecution().consecutiveFailures + 1 == CONSECUTIVE_FAILURES_TO_ALERT`, o log de N6 e `alerts.recordSafely(RECURRING_TASK_FAILING, taskName)`. Textos do e-mail exatos. Javadoc curto citando NFR-009 e o motivo de a dispatch ficar de fora. Linha no runbook (mesmo formato da `TASK_GAVE_UP`) e no glossário.
- [ ] **Step 4:** o mesmo comando → PASS.
- [ ] **Step 5:** `git commit -m "feat: alert the Administrator when a recurring task fails three times in a row (#134)"`

### Task 2: Ligar o handler a todas as tarefas recorrentes, menos a dispatch

**Files:**
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/payment/OrderExpiration.java`, `payment/ReconciliationTasks.java` (troca `.onFailureReschedule()`), `product/ProductCoverCleanup.java`, `customer/UnconfirmedCustomersCleanup.java`, `audit/AuditRetentionCleanup.java`
- Test: `backend/src/test/java/br/com/sergio/sapiensia/alert/RecurringTaskFailureWiringTest.java`

**Interfaces:**
- Consumes: `RecurringTaskFailureAlerts.rescheduleAndAlert(String, Schedule)`, `RecurringTaskFailureAlerts.RescheduleAndAlert` (Task 1).

- [ ] **Step 1: teste que falha.** `RecurringTaskFailureWiringTest`: `@Import(TestcontainersConfiguration.class) @SpringBootTest` **sem propriedades** (reaproveita o contexto padrão do cache; não cria contexto novo). Injeta `List<RecurringTask<?>> tasks`.
  - `shouldAlertOnRepeatedFailuresOfEveryRecurringTaskButTheAlertDispatch`: os nomes incluem `admin-alert-dispatch`, `expire-pending-orders`, `asaas-reconciliation`, `delete-orphan-cover-objects` e os nomes das duas limpezas (use as constantes `TASK_NAME`, se acessíveis; senão as strings); para cada tarefa ≠ dispatch, `getFailureHandler()` é `RescheduleAndAlert` com `taskName()` igual ao nome da tarefa; para a dispatch, **não** é.
- [ ] **Step 2:** `.\mvnw.cmd test -Dtest=RecurringTaskFailureWiringTest` → FAIL por asserção (handler é `OnFailureReschedule`).
- [ ] **Step 3:** em cada tarefa, injete `RecurringTaskFailureAlerts failures` no método `@Bean`, guarde o `Schedule` numa variável e use `Tasks.recurring(TASK_NAME, schedule).onFailure(failures.rescheduleAndAlert(TASK_NAME, schedule))`. Atualize o javadoc da `ReconciliationTasks` (que hoje cita `onFailureReschedule`).
- [ ] **Step 4:** `.\mvnw.cmd test -Dtest=RecurringTaskFailureWiringTest,ReconciliationTasksTest,OrderExpirationTest` (só as classes que existirem; confira com `git grep -l` as classes de teste de cada tarefa alterada e inclua-as) → PASS.
- [ ] **Step 5:** `git commit -m "feat: wire the repeated-failure alert into every recurring task but the alert dispatch (#134)"`

## Fora do escopo

- Alerta por tempo sem sucesso (só por contagem, N1).
- Tarefas de execução única (já têm `TASK_GAVE_UP`).
