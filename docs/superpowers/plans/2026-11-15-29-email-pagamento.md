# E-mail de confirmação de pagamento (#29) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** AGUARDANDO APROVAÇÃO do Sérgio (decisões D1 a D8 na seção 8). Nenhum código antes do OK.

**Goal:** Quando o Pedido vira pago, o Cliente recebe um único e-mail de confirmação, sem que o e-mail possa atrasar ou desfazer o Pedido pago e o Acesso.

**Architecture:** `PaymentConfirmation.pay()` já marca o Pedido como pago e concede o Acesso numa transação. Nessa mesma transação entra só um `insert` na tabela do db-scheduler: a tarefa `order-paid-email`, cujo id de instância é o id do Pedido e que não carrega dado nenhum. O envio roda depois, na thread do agendador, pelo `EmailSender` que já existe (#9); ao terminar, grava `orders.payment_email_sent_at`. Se as tentativas acabam, grava `orders.payment_email_failed_at` (o registro que o alerta do #38 vai ler) e o Acesso continua como está.

**Tech Stack:** Spring Boot 4.1 (`JdbcClient`), Flyway, PostgreSQL, db-scheduler 16.12.0, Resend (HTTP, o `ResendEmailSender` do #9), JUnit 5, Testcontainers, Playwright. **Nenhuma dependência nova, nenhum cliente HTTP novo, nenhuma rota nova, nenhuma tela nova.**

**Spec:** issue #29 (pai #5); `docs/spec/sapiensia-spec.md` (FR-024, NFR-004); `docs/adr/0005-tarefas-em-segundo-plano-com-db-scheduler.md`; plano do #28 (`2026-11-14-28-pedido-pago-acesso.md`); issue #38 (alerta) e o comentário dela; [chaves de idempotência do Resend](https://resend.com/docs/dashboard/emails/idempotency-keys) (consulta em 15/11/2026).

## 1. Decisões do plano (a a g do pedido)

### a) Onde o envio nasce e como fica um e-mail por Pedido

- O agendamento fica em `PaymentConfirmation.pay()`, logo depois de `accesses.grant(...)` devolver `true`: `scheduler.scheduleIfNotExists(new TaskInstance<Void>(OrderPaidEmailTasks.SEND, order.id().toString()), now)`. Nada é enviado dentro da transação.
- Três travas, uma por camada:
  1. **Agendar uma vez:** `orders.markPaid` só devolve `true` na única execução que tira o Pedido de `PENDING`/`EXPIRED`. Aviso duplicado do Asaas, evento novo para a mesma cobrança e reprocessamento pelo runbook caem em `ALREADY_PAID` e não agendam nada. A chave primária `(task_name, task_instance)` da `scheduled_tasks` impede duas tarefas vivas para o mesmo Pedido.
  2. **Enviar uma vez:** a tarefa só envia se o Pedido está `PAID` e `payment_email_sent_at` é nulo; depois do envio faz `update ... where payment_email_sent_at is null`. Tarefa executada de novo (reinício, tarefa reinserida) encontra a coluna preenchida e não envia.
  3. **Janela entre o Resend aceitar e a coluna ser gravada:** o pedido ao Resend leva o cabeçalho `Idempotency-Key: order-paid-email/<id do Pedido>`. Pela documentação, a mesma chave com o mesmo conteúdo em até 24 horas devolve a resposta original sem enviar de novo. As 5 tentativas cabem em 20 minutos.
- **Migration: sim, `V23__order_payment_email.sql`** (a última é a V22), criada pela skill `nova-migration`: duas colunas nulas em `orders`, `payment_email_sent_at` e `payment_email_failed_at` (`timestamp with time zone`). Sem índice: o #38 decide a consulta dele.
- `PaymentConfirmation` recebe `@Lazy SchedulerClient`: sem o `@Lazy` há ciclo (o agendador precisa dos beans de tarefa, a tarefa `process-payment-event` precisa do `PaymentConfirmation`, que precisaria do agendador). O `contextLoads` prova que o contexto sobe.
- Compra duplicada (`DUPLICATE_PURCHASE`: o Pedido foi pago, mas o Cliente já tinha o Acesso por outro Pedido): **não agenda e-mail** (D2).
- Pedido pago antes deste deploy não recebe e-mail retroativo.

### b) Reaproveitar o envio do #9; tempos, tentativas e falha definitiva

- Envio por `EmailSender.send(EmailMessage)`: `ResendEmailSender` em prod, `FileEmailSender` no local e no e2e, `RecordingEmailSender` nos testes. Tempos do `ResendEmailSender` ficam como estão: 2 s para conectar, 5 s para ler.
- Única mudança no pacote `email`: `EmailMessage` ganha o campo opcional `idempotencyKey` (o construtor de 3 argumentos continua existindo, então os outros e-mails não mudam) e o `ResendEmailSender` manda o cabeçalho quando o campo não é nulo.
- Tentativas: as propriedades que já existem, `app.email.max-attempts` (5) e `app.email.retry-delay` (5 min). A tarefa usa `maxRetries(maxAttempts - 1)`, que dá 5 execuções no total (D4).
- Falhou de vez: a tarefa é removida, `payment_email_failed_at` é gravado (só se `payment_email_sent_at` continua nulo) e sai um `ERROR` no log com o id do Pedido. O #38 lê a coluna. Reenviar à mão: SQL no runbook que reinsere a tarefa (D6).
- O endereço do Cliente vem de um método público novo, `CustomerService.findEmail(UUID)`, porque outra funcionalidade não usa o repositório do `customer`.

### c) O e-mail nunca atrasa nem desfaz o Acesso

- Dentro da transação do pagamento só existe um `insert` a mais, no mesmo banco. Chamada ao Resend, leitura do endereço e montagem do texto acontecem depois do commit, em outra thread.
- Nenhum caminho do e-mail escreve em `orders.status` nem em `accesses`. Falha, repetição e desistência só tocam nas duas colunas novas.
- Limite honesto: se esse `insert` falhar, a transação inteira volta e o evento é repetido pela tarefa do #28 (a cada 1 minuto, até 120 vezes). Só acontece com o próprio banco em falha, caso em que o pagamento também não seria gravado.

### d) Conteúdo (pt-BR, texto puro, como os outros e-mails)

Texto completo na seção 7. Tem: título do Produto, valor, link da Biblioteca (`<app.public-base-url>/biblioteca`). **Não tem:** CPF, código Pix, id da cobrança, id do Pedido, nome, token.

Log (sempre só o id do Pedido, nunca o endereço, o título ou o valor):

| Quando | Nível | Mensagem |
|---|---|---|
| Enviado | INFO | `Order paid e-mail sent: orderId={}` |
| Nada a enviar | INFO | `Order paid e-mail skipped: orderId={} reason={}` (`NOT_PAID`, `ALREADY_SENT`, `NO_CUSTOMER`) |
| Desistiu | ERROR | `Order paid e-mail gave up: orderId={}` |
| Id de instância que não é UUID | ERROR | `Order paid e-mail task has an instance id that is not a UUID; it will not be retried` |

A falha de cada tentativa é registrada pelo próprio db-scheduler com a `EmailDeliveryException`, cuja mensagem já traz só o status HTTP ou o nome da classe (#9). A `scheduled_tasks` guarda só o nome da tarefa e o id do Pedido.

### e) Perfil local e CI

- Local e demonstração usam `app.email.provider=file`: o e-mail vira um arquivo `.txt` em `.tmp/demo/outbox` (`just demo`) ou `.tmp/outbox` (`just dev-back`), cerca de 1 segundo depois do `just demo-pagar` (o perfil local consulta as tarefas a cada 1 s). Nada sai para a internet.
- `just demo-pagar` passa a dizer onde o arquivo aparece e `docs/demo.md` ganha um parágrafo (textos na seção 7).
- CI: os testes de integração usam o `RecordingEmailSender`; o e2e sobe a imagem com `APP_EMAIL_PROVIDER=file` (já é assim no `ci.yml`). Nenhuma variável nova.

### f) Testes (seção 5) e g) o que não será provado (seção 6)

## 2. Global Constraints

- Código, commits, nomes e comentários em inglês. Textos que o usuário lê em pt-BR, **só os da seção 7**; texto fora dela volta ao Sérgio antes do push.
- Nenhuma dependência nova. Nenhum `permitAll`, nenhuma rota nova.
- Em log, exceção, `toString` e `scheduled_tasks`: nunca o e-mail do Cliente, o título, o valor, CPF, código Pix ou id da cobrança. Só o id do Pedido e o motivo (enum).
- Migration `V23__order_payment_email.sql` pela skill `nova-migration`. Nenhuma migration existente é editada.
- Sem `@MockitoBean` em teste de integração; `@Import(TestcontainersConfiguration.class)`. Teste novo reusa a combinação de `@Import` e propriedades de uma classe existente do pacote `payment`, para não criar outro contexto Spring.
- Teste que prova requisito leva `FR-024` no `@DisplayName` (backend) ou no título (e2e).
- Teste focado (`-Dtest=...`): incluir as classes que conferem log ou citam o ID (`git grep -l FR-024 -- backend/src/test`; hoje pelo menos `PaymentLogTest`, `OrderLogTest`, `PaymentConfirmationTest`, `PaymentEventProcessingTest`, `PaymentConcurrencyTest`).
- Um comando pesado por vez. Durante o trabalho só `just check-rapido`; o completo roda no CI.
- Push só com `git push -u origin feat/29-email-pagamento`, depois do OK do Sérgio.

## Review Focus

1. Título do Produto com `%`, quebra de linha ou alterado depois da compra: o e-mail sai com o título atual e o texto não quebra (teste na Task 3 com o título `100% Prático`).
2. Valor de R$ 1.000,00 ou mais: `R$ 1.234,56`, com espaço comum depois de `R$` (Task 3).
3. Cliente não encontrado na hora do envio: a tarefa termina com `NO_CUSTOMER`, sem repetir para sempre (Task 3).
4. Tarefa executada duas vezes para o mesmo Pedido (reinício, SQL do runbook): um e-mail só (Tasks 3 e 4).
5. Transação do pagamento desfeita (auditoria falha): nenhuma tarefa de e-mail fica agendada (Task 1).

---

## 3. Arquivos

| Arquivo | O que muda |
|---|---|
| `backend/src/main/resources/db/migration/V23__order_payment_email.sql` | Criar: as duas colunas |
| `backend/.../payment/Orders.java` | `paymentEmailPending`, `markPaymentEmailSent`, `markPaymentEmailFailed` |
| `backend/.../payment/PaymentConfirmation.java` | Agendar a tarefa dentro de `pay()`; `@Lazy SchedulerClient` |
| `backend/.../payment/OrderPaidEmail.java` | Criar: monta o texto, envia, grava enviado ou desistência |
| `backend/.../payment/OrderPaidEmailTasks.java` | Criar: a tarefa `order-paid-email` |
| `backend/.../email/EmailMessage.java`, `ResendEmailSender.java` | Campo `idempotencyKey` e cabeçalho `Idempotency-Key` |
| `backend/.../customer/CustomerService.java` | `public Optional<String> findEmail(UUID customerId)` |
| `backend/src/test/.../RecordingEmailSender.java` | `failAllSendsTo(String)` e `stopFailingSendsTo(String)` |
| `scripts/demo-pagar.mjs`, `docs/demo.md` | Onde o e-mail aparece |
| `docs/adr/0005-...md`, `docs/runbooks/webhook-asaas.md`, `CLAUDE.md` | Uma frase no ADR; SQL de reenvio (D6); linha em "Reaproveitar antes de criar" |
| `frontend/e2e/admin-panel.e2e.ts` | Duas conferências no teste de pagamento que já existe (D7) |

## 4. Tasks

### Task 1: Migration e agendamento na transação do pagamento

**Files:** criar `V23__order_payment_email.sql`; modificar `Orders.java`, `PaymentConfirmation.java`; testes em `PaymentConfirmationTest.java` (o contexto dele não roda tarefas, então a linha em `scheduled_tasks` fica visível).

**Interfaces (Produces):**
- `OrderPaidEmailTasks.SEND = "order-paid-email"` (a constante nasce aqui; a tarefa em si é da Task 3. Para o contexto subir já nesta task, crie a classe com a tarefa chamando `OrderPaidEmail.send`, com corpo vazio até a Task 3).
- `Orders.paymentEmailPending(UUID id) -> boolean`: `status = 'PAID' and payment_email_sent_at is null`.
- `Orders.markPaymentEmailSent(UUID id, Instant now) -> boolean`: `true` só para quem preencheu a coluna.
- `Orders.markPaymentEmailFailed(UUID id, Instant now) -> void`: só grava se `payment_email_sent_at` é nulo.

- [ ] Testes que falham, em `PaymentConfirmationTest`:
  - `FR-024: o Pedido pago agenda uma tarefa de e-mail na mesma transação`: depois de `process`, `select count(*) from scheduled_tasks where task_name = 'order-paid-email' and task_instance = '<orderId>'` é 1 e `task_data` é nulo.
  - `FR-024: aviso duplicado do Asaas não agenda outro e-mail`: `process` de um segundo evento da mesma cobrança devolve `ALREADY_PAID` e a contagem continua 1.
  - `FR-024: transação do pagamento desfeita não deixa tarefa de e-mail`: com `audit.failWrites(true)`, `process` lança, o Pedido continua `PENDING` e a contagem é 0.
  - `FR-024: compra duplicada não agenda e-mail`: resultado `DUPLICATE_PURCHASE`, contagem 0 para esse Pedido.
- [ ] Rodar e ver falhar; criar a migration com `nova-migration`; implementar; rodar e ver passar (com `SapiensiaApplicationTests` para o `contextLoads`).
- [ ] Commit: `feat: schedule the paid e-mail with the payment (#29)`.

### Task 2: Chave de idempotência no envio e e-mail do Cliente por id

**Files:** `EmailMessage.java`, `ResendEmailSender.java`, `CustomerService.java`; testes em `ResendEmailSenderTest.java` e no teste de serviço do `customer` que já cobre `CustomerService`.

**Interfaces (Produces):**
- `record EmailMessage(String to, String subject, String text, String idempotencyKey)` mais o construtor `EmailMessage(String to, String subject, String text)` (chave nula).
- `CustomerService.findEmail(UUID customerId) -> Optional<String>` (delega a `customers.findEmailById`).

- [ ] Testes que falham em `ResendEmailSenderTest` (MockRestServiceServer): com chave, o pedido leva `Idempotency-Key` igual à chave; sem chave, o cabeçalho não existe. Em `CustomerService`: devolve o endereço de quem existe e vazio para id desconhecido.
- [ ] Implementar, rodar, commit: `feat: idempotency key on Resend e-mails (#29)`.

### Task 3: O e-mail e a tarefa

**Files:** criar `OrderPaidEmail.java`; completar `OrderPaidEmailTasks.java`; `RecordingEmailSender.java`; criar `OrderPaidEmailTest.java` (mesmo `@Import` e propriedades de `PaymentEventProcessingTest`, onde as tarefas rodam de verdade) e `OrderPaidEmailTextTest.java` (unitário, sem Spring).

**Interfaces:**
- Consumes: Task 1 (`Orders.*`), Task 2 (`EmailMessage` com chave, `CustomerService.findEmail`), `Orders.findById`, `app.public-base-url`, `app.email.max-attempts`, `app.email.retry-delay`.
- Produces: `OrderPaidEmail.send(UUID orderId)`; `OrderPaidEmail.giveUp(UUID orderId)`; `static String OrderPaidEmail.formatPrice(int priceCents)`; bean `OneTimeTask<Void> orderPaidEmailTask(OrderPaidEmail email)`.
- `send`: se `!orders.paymentEmailPending(id)` registra `skipped` (`NOT_PAID` ou `ALREADY_SENT`) e termina; sem Cliente, `NO_CUSTOMER`; senão envia com a chave `"order-paid-email/" + orderId`, chama `markPaymentEmailSent` e registra `sent`. A `EmailDeliveryException` sobe para o db-scheduler repetir.
- `formatPrice`: `String.format(Locale.of("pt", "BR"), "R$ %,.2f", BigDecimal.valueOf(priceCents, 2))`.
- Tarefa: sem dado, id de instância = id do Pedido; `FailureHandler.<Void>maxRetries(maxAttempts - 1).retryEvery(retryDelay).thenRemove(...)` chamando `giveUp`; id que não é UUID só registra o erro (como em `PaymentEventTasks`).

- [ ] Testes que falham:
  - `OrderPaidEmailTextTest`: `formatPrice(2990)` é `R$ 29,90`; `formatPrice(123456)` é `R$ 1.234,56`; o texto montado para o título `100% Prático` é exatamente o da seção 7.
  - `FR-024: o Cliente recebe o e-mail de pagamento confirmado`: pagamento de ponta a ponta (webhook, tarefa, e-mail); assunto e corpo iguais aos da seção 7, com título, valor e `http://localhost:5173/biblioteca`; o corpo não contém o id da cobrança nem o id do Pedido; `payment_email_sent_at` preenchido.
  - `FR-024: aviso duplicado do Asaas gera um e-mail só`: o mesmo evento duas vezes e um evento novo da mesma cobrança; depois do primeiro e-mail, `awaitMessageTo(email, 3 s)` volta vazio.
  - `FR-024: tarefa executada de novo não envia outro e-mail`: `send(orderId)` chamado de novo depois do envio; nenhum e-mail a mais.
  - `FR-024: falha no envio não atrasa nem desfaz o Acesso`: com `failNextSendTo`, o Pedido já está `PAID` e a linha em `accesses` existe enquanto o e-mail ainda não saiu; na tentativa seguinte chega 1 e-mail.
  - `FR-024: quando o envio falha de vez, o Acesso fica e a falha é registrada`: com `failAllSendsTo`, depois das 5 tentativas `payment_email_failed_at` está preenchido, `payment_email_sent_at` nulo, Pedido `PAID`, Acesso presente, a tarefa saiu da `scheduled_tasks` e há um `ERROR` com o id do Pedido.
  - `FR-024: o log do e-mail de pagamento não traz dado pessoal`: em todos os cenários acima, nenhuma linha de log contém o e-mail do Cliente, o título do Produto ou o valor.
  - `FR-024: Cliente não encontrado encerra a tarefa sem repetir`: `send` de um Pedido pago cujo `findEmail` volta vazio não lança e registra `NO_CUSTOMER` (unitário com Mockito, já que a chave estrangeira impede apagar o Cliente no banco).
- [ ] Implementar, rodar (com as classes de log da lista das Global Constraints), commit: `feat: payment confirmation e-mail (#29)`.

### Task 4: Reinício não duplica

**Files:** criar `backend/src/test/.../payment/OrderPaidEmailRestartTest.java`, no molde de `scheduling/DbSchedulerRestartTest` (container próprio, porque o banco precisa sobreviver ao contexto; `app.email.provider=file` com `@TempDir`).

- [ ] `FR-024, NFR-004: o e-mail agendado antes do reinício sai uma vez depois dele`: 1º contexto com `db-scheduler.polling-interval=PT1H`: cria Cliente, Produto e Pedido pelo `OrderService`, grava o evento e chama `PaymentConfirmation.process`; fecha o contexto com a tarefa ainda na tabela e nenhum arquivo no outbox. 2º contexto com `1s`: aparece exatamente 1 arquivo com o assunto da seção 7 e `payment_email_sent_at` é preenchido.
- [ ] `FR-024, NFR-004: a tarefa que reaparece depois do envio não envia de novo`: no 2º contexto, reinserir a tarefa do mesmo Pedido com o SQL do runbook (D6, lido do arquivo como no #28) e esperar ela sumir da `scheduled_tasks`: o outbox continua com 1 arquivo.
- [ ] Rodar, commit: `test: paid e-mail survives a restart without duplicating (#29)`.

### Task 5: Demonstração, documentos e e2e

**Files:** `scripts/demo-pagar.mjs` (e o teste dele, se conferir a mensagem), `docs/demo.md`, `docs/adr/0005-...md`, `docs/runbooks/webhook-asaas.md`, `CLAUDE.md`, `frontend/e2e/admin-panel.e2e.ts`.

- [ ] Textos da seção 7 no `demo-pagar` e no `docs/demo.md`.
- [ ] ADR-0005: uma frase (o e-mail de pagamento confirmado é a tarefa `order-paid-email`, uma por Pedido, com o resultado em `orders.payment_email_sent_at` / `payment_email_failed_at`).
- [ ] Runbook: seção "Reenviar o e-mail de pagamento confirmado" com o `insert into scheduled_tasks` (mesmo formato da seção "Reprocessar um evento de pagamento").
- [ ] `CLAUDE.md`, "Reaproveitar antes de criar": `OrderPaidEmail.java` é o único lugar que envia o e-mail de pagamento confirmado.
- [ ] e2e, no teste `FR-020 FR-021 FR-024 ...` que já existe: `logInNewCustomerPage` passa a devolver o e-mail; depois do pagamento, `waitForMessage(email, <assunto>)` e o corpo contém o título e `/biblioteca`; depois dos eventos repetidos, `messagesFor(email, <assunto>)` tem tamanho 1.
- [ ] `just check-rapido`; sistema de verdade (`just db` na pasta principal, `just dev-back`, `just dev-front`) com `just e2e` desse teste; `just demo` + `just demo-pagar` conferindo o arquivo no outbox; skill `verificar-entrega`. Commit: `docs: paid e-mail in the demo, runbook and ADR (#29)`.

## 5. Mapa dos testes pedidos

| Pedido | Onde |
|---|---|
| Testcontainers | Tasks 1, 3 e 4 |
| Aviso duplicado gera 1 e-mail | Task 1 (uma tarefa), Task 3 (um e-mail), e2e |
| Reinício no meio não duplica | Task 4; Task 3 (tarefa executada de novo); Task 2 (cabeçalho de idempotência) |
| Falha do Resend não afeta o Acesso | Task 3 (uma falha e falha definitiva) |
| Log sem dado pessoal | Task 3 |
| E2E | Duas conferências no teste que já existe (barato: sem navegador a mais) |

## 6. O que não será provado

- **Resend de verdade:** entrega, caixa de spam e aparência no cliente de e-mail. Nenhum teste chama o Resend.
- **Que o Resend de fato não duplica com a mesma `Idempotency-Key`:** provo só que o cabeçalho é enviado; o comportamento é o da documentação.
- **Queda real do processo no meio da chamada HTTP:** os testes reproduzem o estado de antes (tarefa pendente) e de depois (coluna gravada, tarefa de volta), não um `kill` no meio.
- **Quanto tempo o db-scheduler leva para retomar uma execução interrompida:** depende do batimento dele (minutos); não é medido.
- **Título alterado entre duas tentativas:** o Resend responde 409 para a mesma chave com conteúdo diferente; isso vira falha e, no limite, desistência registrada. Sem teste.
- **O alerta do #38:** só o registro que ele vai ler.
- **Homologação:** o envio real no Fly.io fica para uma conferência manual depois do merge e do deploy.

## 7. Textos que o usuário lê

**E-mail, assunto:**

```
Seu pagamento na SapiensIA foi confirmado
```

**E-mail, corpo** (os três `%s`: título do Produto, valor como `R$ 29,90`, link):

```
Olá!

Recebemos o seu Pix. O pagamento do seu Pedido na SapiensIA foi confirmado.

Produto: %s
Valor: %s

Você já tem Acesso a este Produto. Para baixar, entre na sua conta e abra a sua Biblioteca:

%s

Obrigado pela compra!
```

**`just demo-pagar`, mensagem final (duas linhas; a primeira já existe):**

```
Evento enviado. Em alguns segundos o Pedido aparece como pago na página dele.
O e-mail de confirmação é gravado como arquivo em .tmp/demo/outbox (fora da demonstração, em .tmp/outbox).
```

**`docs/demo.md`, na seção "Comprar e pagar"** (troca a frase "Baixar o conteúdo ainda não existe.", que ficou velha com a Biblioteca):

```
Em alguns segundos a página vira "Pagamento confirmado" e o Produto aparece na Biblioteca. O e-mail de confirmação de pagamento não é enviado de verdade: ele é gravado como um arquivo de texto em `.tmp/demo/outbox`.
```

Nenhuma tela muda; não há texto de "carregando", erro ou confirmação novos.

## 8. Decisões que dependem do Sérgio

| # | Decisão | Recomendação |
|---|---|---|
| D1 | Texto do e-mail (assunto e corpo da seção 7) | Aprovar como está. Assunto fixo, sem o título, igual ao padrão dos outros e-mails |
| D2 | Compra duplicada (pagou um Pedido de um Produto que já tinha): manda o e-mail? | Não. O texto diria "você já tem Acesso" para um pagamento que precisa de revisão e devolução; o caso já vai para revisão no #28 |
| D3 | Registro do envio: duas colunas em `orders` (V23) ou tabela nova | Duas colunas: menos código e o #38 lê direto |
| D4 | Tentativas: reusar 5 vezes a cada 5 minutos, ou regra própria mais longa | Reusar. Cabe na janela de 24 h da chave de idempotência e segue o ADR-0005 |
| D5 | Mandar `Idempotency-Key` ao Resend (mexe em duas classes do #9) | Sim. É o que fecha a janela entre o Resend aceitar e o banco gravar |
| D6 | SQL de reenvio manual no runbook | Sim, uma seção curta, exercitada pelo teste da Task 4 |
| D7 | e2e: duas conferências no teste de pagamento que já existe | Sim, custa segundos |
| D8 | Textos do `demo-pagar` e do `docs/demo.md` (seção 7) | Aprovar como estão |

**Fora do ticket, só para registro:** as tarefas de e-mail do #9 usam `maxRetries(maxAttempts)`, o que pela conta feita no #28 dá 6 execuções em vez das 5 que o log e o ADR dizem. Não conferi rodando. Se confirmar, vira issue `needs-triage` citando o #29.
