# Pedido irmão e robustez do pagamento (A2 da triagem) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Quando um Pedido é pago, cancelar o Pedido pendente do mesmo Cliente e Produto (inclusive a cobrança Pix no Asaas), registrar a compra duplicada na auditoria, dar resultado final à cobrança excluída e não paga, e não perder o `GAVE_UP` quando a gravação dele falha.

**Architecture:** A transação do pagamento não muda de forma: o Pedido irmão é cancelado **depois** do commit, pelo mesmo `OrderExpiration.Expirer` que a expiração já usa (cancela no Asaas, depois marca `EXPIRED`). A compra duplicada ganha uma linha de auditoria na mesma transação que marca o Pedido como pago. O failure handler da tarefa `process-payment-event` passa a gravar o `GAVE_UP` antes de remover a tarefa.

**Tech Stack:** Java 25, Spring Boot 4.1, `JdbcClient`, PostgreSQL, db-scheduler 16.12.0, JUnit 5 + Mockito + Testcontainers.

**Spec:** issue #111 (itens citados abaixo); decisão do Sérgio de 18/11/2026 sobre o Pedido irmão; FR-024, SEC-025, SEC-026, SEC-031 de `docs/spec/`; ADR-0003.

**Fluxo:** COMPLETO (pagamento). Branch `fix/111-sibling-order-and-payment-robustness`, worktree `.claude/worktrees/fix-111-pedido-irmao`. PR com `Refs #111` (a issue continua aberta com o que sobra).

## O que a conferência no código mostrou (18/11/2026)

| Item do #111 | Estado no código | Onde |
|---|---|---|
| Pedido irmão pendente continua pagável depois do Acesso | **Aberto.** `pay` concede o Acesso e não olha outros Pedidos | `PaymentConfirmation.java:154-174` |
| Devolução manual em `DUPLICATE_PURCHASE` sem passo a passo | **Já atendido** pelo #36: seção "Compra duplicada" do runbook, com o SQL. Este plano só a atualiza | `docs/runbooks/webhook-asaas.md:170-180` |
| `DUPLICATE_PURCHASE` sem registro na auditoria | **Aberto.** Só `ACCESS_GRANTED` é gravado, e só quando concede | `PaymentConfirmation.java:163-169` |
| Cobrança excluída e não paga repete por 2 h | **Aberto.** `check` lança `PaymentNotReadyException` para `PENDING`/`OVERDUE` sem olhar `deleted` | `PaymentConfirmation.java:145-147` |
| `giveUp` dentro do failure handler | **Aberto, e pior do que a issue diz** (ver abaixo) | `PaymentEventTasks.java:46-50` |
| Falta teste para id de instância que não é UUID | **Aberto** | `PaymentEventTasks.java:52-58, 70-76` |
| `netValue` não é comparado com o preço | **Aberto**, mas a comparação não se aplica (D3) | `PaymentConfirmation.java:142` |

**Achado novo, `giveUp`:** no db-scheduler 16.12.0, `thenRemove(listener)` remove a tarefa **antes** de chamar o listener (`ops.remove(); listener.onMaxRetriesExceeded(complete);`, conferido no código-fonte da versão). Se a gravação do `GAVE_UP` falhar, a tarefa já foi apagada: fica um evento sem resultado, sem alerta e sem nova tentativa. A conciliação noturna ainda pega o dinheiro (cobrança paga com Pedido pendente), mas o Administrador não é avisado.

**Como um Pedido irmão existe:** o índice `orders_one_pending_per_customer_product` só deixa um Pedido `PENDING` por Cliente e Produto, e a compra é recusada (`ALREADY_OWNED`) para quem já tem o Acesso. Então o irmão só aparece assim: o Pedido 1 expira, o Cliente cria o Pedido 2, e o pagamento atrasado do Pedido 1 chega. Há no máximo um irmão.

Fora deste plano, continuam no #111: R1 (update condicional antes do cancelamento), `giveUp` com id desconhecido, validação do `chargeId` duplicada, `Ref.eventType`, os demais testes, a demonstração local e o roteiro de sandbox.

## Decisões aprovadas (18/11/2026)

Aprovadas pelo Sérgio como recomendadas, com uma condição para D3: o valor bruto da cobrança (`value`) continua conferido contra o preço do Pedido. **Conferido:** `PaymentConfirmation.check` (linha 142) recusa com `AMOUNT_MISMATCH` quando `value` falta ou difere de `price_cents`, antes de olhar o estado da cobrança; os testes `shouldNotGrantWhenTheValueDiffers`, `shouldPayWhenTheValueHasAnotherScale` e, na conciliação, `shouldNotPayWhenTheAmountDiffersAndRaiseTheReviewAlert` cobrem isso. Este plano não toca essa linha, e os três testes rodam nas Tasks 1 e 3.

| # | Decisão | Recomendação |
|---|---|---|
| D1 | Como o Pedido irmão cancelado aparece | **Como "Expirado"**, o estado que já existe: mesma tela ("Este Pix expirou"), mesmo rótulo em Meus pedidos e no painel. Sem estado novo, sem migration, sem texto novo. O texto diz "o prazo de 30 minutos terminou", o que não é exato para esse caso; o Cliente já recebeu o e-mail de pagamento confirmado do outro Pedido. Alternativa: estado `CANCELLED` com texto próprio (migration, tela do Cliente, filtro do painel). |
| D2 | Asaas fora do ar no instante do cancelamento | **O pagamento fica confirmado e o irmão continua pendente até o prazo dele (no máximo 30 min), quando a tarefa de expiração cancela.** Se o Cliente pagar nesse intervalo, cai na devolução manual. Alternativa: antecipar o `expires_at` do irmão dentro da transação do pagamento, para a tarefa de expiração tentar de minuto em minuto; custa uma instrução a mais (com `skip locked`, senão dois pagamentos simultâneos travam um ao outro) na transação mais crítica do sistema. |
| D3 | `netValue` | **Não comparar.** A documentação do Asaas (conferida em 18/11/2026) diz que `netValue` é o "valor líquido da cobrança após desconto da tarifa do Asaas": ele é sempre menor que o preço, então compará-lo recusaria toda venda. Não existe campo de "valor pago"; `originalValue` só é preenchido quando a cobrança é paga com juros e multa, e aí o `value` muda e o `AMOUNT_MISMATCH` de hoje já pega. Fica uma nota no ADR-0003 e um item no roteiro de sandbox (anotar `value`, `netValue` e `originalValue` de uma cobrança paga). |
| D4 | Resultado da cobrança excluída e não paga | **`NOT_PAID`** (final, com o alerta de revisão que já existe). Sem valor novo de resultado. Um evento de pagamento posterior da mesma cobrança é outro evento e paga normalmente. |
| D5 | Auditoria | Uma ação nova, `DUPLICATE_PURCHASE`, no Pedido duplicado. O cancelamento do irmão fica só no log (ids dos dois Pedidos) e em `orders.expired_at`; se quiser também na auditoria, é mais uma ação. |

## Textos que o usuário vê

Nenhum texto novo. Com D1, o irmão cancelado usa os textos de "Expirado" que já existem. Para o Administrador: o alerta `PAYMENT_EVENT_NEEDS_REVIEW` com detalhe `NOT_PAID` e o `DUPLICATE_PURCHASE` já existem.

## Global Constraints

- TDD: cada teste novo falha primeiro **por asserção** (não por erro de compilação), depois passa. Teste de comportamento que já está certo é provado com uma mutação temporária.
- Testes focados (`cd backend; .\mvnw.cmd test "-Dtest=A,B"`), um comando pesado por vez; `just check-rapido` uma vez só, no fim.
- Todo teste focado deste plano inclui também `PaymentLogTest` (confere o log do pagamento) e as classes que citam o ID do requisito: `git grep -l SEC-031 -- backend/src/test`.
- Log só com ids, códigos e nome de classe de exceção; nunca mensagem de exceção, e-mail, CPF ou corpo de resposta.
- A transação de `pay` não ganha nenhuma chamada ao Asaas nem trava nova.
- Sem dependência nova, sem migration, sem propriedade `app.*` nova.
- Um commit por task, Conventional Commits em inglês; comandos de shell um por vez.

## Review Focus

1. Pedido pendente do mesmo Cliente para **outro** Produto, ou de outro Cliente para o mesmo Produto: não é cancelado (Task 3).
2. Asaas fora no cancelamento do irmão: o resultado continua `PAID`, o Acesso fica, nada lança (Task 3).
3. O mesmo evento processado duas vezes: nenhum segundo cancelamento (Task 3).
4. Gravação do `GAVE_UP` falha: a tarefa não é removida e é reagendada (Task 2).
5. Cobrança excluída **e paga** continua concedendo (ADR-0003): `shouldGrantWhenThePaidChargeIsMarkedDeleted` segue verde (Task 1).

Risco aceito, sem teste: o Cliente cria o Pedido 2 no mesmo instante em que o pagamento do Pedido 1 é confirmado (a compra já passou da checagem de Acesso). O Pedido 2 fica pagável até expirar; se for pago, é a devolução manual da decisão.

---

### Task 1: Cobrança excluída e não paga vira `NOT_PAID`

**Files:**
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/payment/PaymentConfirmation.java:145-147`
- Modify: `docs/adr/0003-pagamento-so-por-pix-via-asaas.md` (linha "Casos de borda (#28)")
- Test: `backend/src/test/java/br/com/sergio/sapiensia/payment/PaymentConfirmationTest.java`

- [ ] **Step 1: Escrever o teste** `shouldRecordNotPaidForADeletedChargeThatWasNeverPaid`, parametrizado com `PENDING` e `OVERDUE`, `@DisplayName("SEC-031, ADR-0003: ...")`:

```java
Fixture f = fixture();
provider(f, new PixCharge(f.chargeId(), status, new BigDecimal("29.90"), "PIX", f.orderId().toString(), true));
assertRefused(f, PaymentResult.NOT_PAID, output);
```

- [ ] **Step 2: Rodar e ver falhar** com `PaymentNotReadyException` (a exceção esperada do comportamento atual).

Run: `.\mvnw.cmd test "-Dtest=PaymentConfirmationTest,PaymentLogTest"`

- [ ] **Step 3: Em `check`, pedir nova tentativa só enquanto a cobrança não foi excluída:** `if (charge.isAwaitingPayment() && !charge.deleted())`. O `NOT_PAID` logo abaixo cobre o resto.

- [ ] **Step 4: Rodar de novo.** Esperado: tudo verde, incluindo `shouldAskForARetryWhileTheProviderStillShowsTheChargeAsPending` e `shouldGrantWhenThePaidChargeIsMarkedDeleted`.

- [ ] **Step 5: ADR-0003,** na linha dos casos de borda: "Cobrança excluída e não paga termina em `NOT_PAID` na primeira leitura, sem repetir."

- [ ] **Step 6: Commit** `fix: end a payment event of a deleted unpaid charge as NOT_PAID`

### Task 2: Gravar o `GAVE_UP` antes de remover a tarefa

**Files:**
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/payment/PaymentEventTasks.java:41-76`
- Test (novo): `backend/src/test/java/br/com/sergio/sapiensia/payment/PaymentEventTasksTest.java`

**Interfaces:**
- Consumes: `FailureHandler.<Void>maxRetries(int).retryEvery(Duration).then(FailureHandler<Void>)` (db-scheduler 16.12.0: depois do limite, entrega o controle ao handler, sem remover sozinho); `ExecutionOperations.remove()` e `reschedule(ExecutionComplete, Instant)`.

- [ ] **Step 1: Escrever `PaymentEventTasksTest`,** unitário, no molde de `alert/RecurringTaskFailureAlertsTest` (`ExecutionOperations` e `PaymentConfirmation` com `mock`, `ExecutionComplete.failure(...)` com `consecutiveFailures` escolhido). A tarefa vem de `new PaymentEventTasks(3, Duration.ofMinutes(1)).processPaymentEventTask(confirmation, refund, events)`; o handler, de `task.getFailureHandler()`. Todos `SEC-031`:
  - `shouldRecordTheGiveUpBeforeRemovingTheTask`: falha nº 3 (2 anteriores); `InOrder`: `confirmation.giveUp(id)` e depois `ops.remove()`.
  - `shouldKeepTheTaskWhenTheGiveUpCannotBeRecorded`: `giveUp` lança `DataAccessResourceFailureException`; `assertThatCode(...).doesNotThrowAnyException()`, `verify(ops).reschedule(complete, DONE.plus(Duration.ofMinutes(1)))`, `verify(ops, never()).remove()`.
  - `shouldRetryBeforeTheLastAttempt`: falha nº 2; `reschedule` chamado, `giveUp` e `remove` nunca.
  - `shouldRemoveATaskWhoseInstanceIdIsNotAUuid`: id `"not-a-uuid"` na falha nº 3; `ops.remove()` chamado, `confirmation` sem interação.
  - `shouldNotRunATaskWhoseInstanceIdIsNotAUuid`: `task.execute(...)` com esse id não lança e não toca `events`, `confirmation` nem `refund`.

- [ ] **Step 2: Rodar e ver falhar** os dois primeiros por asserção (a ordem está invertida; a exceção escapa). Os três últimos passam: são comportamento atual, prove-os com mutação temporária.

Run: `.\mvnw.cmd test "-Dtest=PaymentEventTasksTest"`

- [ ] **Step 3: Trocar o `thenRemove(...)` por `then(...)`:**

```java
.then((complete, ops) -> {
    try {
        giveUp(confirmation, complete.getExecution().taskInstance.getId());
    } catch (RuntimeException e) {
        // the event has no result yet: keep the task, or nobody would ever be told
        log.error("Payment event give-up could not be recorded, will retry: error={}",
                e.getClass().getSimpleName());
        ops.reschedule(complete, complete.getTimeDone().plus(retryDelay));
        return;
    }
    ops.remove();
})
```

Se o `reschedule` também falhar, a exceção sai do handler de propósito: o db-scheduler deixa a tarefa marcada e a retoma como execução morta.

- [ ] **Step 4: Rodar** `.\mvnw.cmd test "-Dtest=PaymentEventTasksTest,PaymentEventProcessingTest,PaymentLogTest"`. Esperado: verde, incluindo `shouldGiveUpAfterTheLastAttemptAndKeepTheEvent`.

- [ ] **Step 5: Commit** `fix: record GAVE_UP before removing the payment event task`

### Task 3: Cancelar o Pedido irmão e auditar a compra duplicada

**Files:**
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/payment/PaymentConfirmation.java` (construtor, `process`, `pay`)
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/audit/AuditAction.java`
- Modify: `docs/adr/0003-pagamento-so-por-pix-via-asaas.md`, `docs/runbooks/webhook-asaas.md` ("Compra duplicada" e roteiro de sandbox), `CLAUDE.md` (linha do `PaymentConfirmation`)
- Test: `backend/src/test/java/br/com/sergio/sapiensia/payment/PaymentConfirmationTest.java`

**Interfaces:**
- Consumes: `OrderExpiration.Expirer.expire(Orders.Row)` (cancela no Asaas e marca `EXPIRED`; `false` quando o Asaas está indisponível; recusa do Asaas expira mesmo assim); `Orders.findPending(UUID customerId, UUID productId)`.
- Produces: `AuditAction.DUPLICATE_PURCHASE`; `PaymentConfirmation` recebe `OrderExpiration.Expirer` no construtor.

- [ ] **Step 1: Escrever os testes** em `PaymentConfirmationTest` (FR-024, ADR-0003). Auxiliar: `siblings()` cria o Pedido 1 com `fixture()`, marca-o com `orders.markExpired`, e cria o Pedido 2 com `fixtureFor(mesmoCliente, mesmoProduto)`.
  - `shouldCancelThePendingSiblingWhenAnExpiredOrderIsPaid`: paga o 1; resultado `PAID`; `statusOf(second) == "EXPIRED"`; `gateway.cancelledCharges()` contém a cobrança do 2 e não a do 1; um Acesso só.
  - `shouldKeepThePaymentWhenTheSiblingCannotBeCancelledNow` (Review Focus 2): `gateway.failCancel(Kind.UNAVAILABLE)`; resultado `PAID`, Acesso concedido, `statusOf(second) == "PENDING"`, log com o id do Pedido 2.
  - `shouldNotCancelPendingOrdersOfAnotherProductOrAnotherCustomer` (Review Focus 1): os dois continuam `PENDING` e `gateway.cancelledCharges()` fica vazio.
  - `shouldNotCancelAgainWhenTheSameEventIsProcessedTwice` (Review Focus 3): segundo `process` devolve `PAID` e `gateway.count("CANCEL_CHARGE")` não muda (confira o nome do passo em `SwitchablePixGateway.enter`).
  - `shouldRecordADuplicatePurchaseInTheAuditTrailWhenBothSiblingsArePaid`: `gateway.failCancel(Kind.REFUSED)` (o Asaas não cancela cobrança paga); paga o 1 (o 2 vira `EXPIRED`); processa o evento pago do 2: `DUPLICATE_PURCHASE`, Pedido 2 `PAID`, um Acesso só, e uma linha em `audit_events` com `action = 'DUPLICATE_PURCHASE'` e `object_id` do Pedido 2, nenhuma do Pedido 1.
  - Em `shouldMarkTheSecondPaidOrderOfTheSameProductAsADuplicatePurchase`, acrescentar a mesma asserção de auditoria.

- [ ] **Step 2: Rodar e ver falhar** por asserção o 1º, o 5º e o 6º. O 2º, o 3º e o 4º passam antes do código: depois do Step 3, prove cada um com mutação temporária (tirar o `try/catch`, tirar o filtro de Produto de uma cópia da consulta, chamar o cancelamento também em `ALREADY_PAID`).

Run: `.\mvnw.cmd test "-Dtest=PaymentConfirmationTest,PaymentLogTest"`

- [ ] **Step 3: Implementar.**
  - `pay`: quando `granted` é falso, `audit.recordInTransaction(new AuditEntry(AuditAction.DUPLICATE_PURCHASE, AuditResult.SUCCESS, null, AuditObject.ORDER, order.id(), null))`, antes de gravar o resultado.
  - `process`: depois do commit, só quando o resultado é `PAID`, `cancelPendingSibling(order)`:

```java
private void cancelPendingSibling(Orders.Row paid) {
    try {
        orders.findPending(paid.customerId(), paid.productId()).ifPresent(sibling -> {
            if (expirer.expire(sibling)) {
                log.info("Pending sibling order cancelled: orderId={} paidOrderId={}", sibling.id(), paid.id());
            } else {
                log.warn("Pending sibling order stays payable until it expires: orderId={} paidOrderId={}",
                        sibling.id(), paid.id());
            }
        });
    } catch (RuntimeException e) {
        // the payment is committed: nothing here may change its result
        log.warn("Pending sibling order check failed: paidOrderId={} error={}",
                paid.id(), e.getClass().getSimpleName());
    }
}
```

- [ ] **Step 4: Rodar** `.\mvnw.cmd test "-Dtest=PaymentConfirmationTest,PaymentConcurrencyTest,ReconciliationTest,RefundRepurchaseTest,AuditLogTest,AuditPrivacyTest,PaymentLogTest"`. Esperado: verde. Se `shouldGrantOneAccessWhenTwoOrdersOfTheSameProductArePaidTogether` falhar por causa do cancelamento novo, **pare e relate**: não afrouxe a asserção.

- [ ] **Step 5: Documentos.**
  - ADR-0003, regra nova: ao pagar um Pedido, o Pedido pendente do mesmo Cliente e Produto é cancelado (cobrança cancelada no Asaas, Pedido `EXPIRED`); se os dois forem pagos, o segundo é `DUPLICATE_PURCHASE`, com auditoria e devolução manual. Nota do `netValue` (D3), com a data da conferência.
  - Runbook, "Compra duplicada": quando ainda pode acontecer (os dois pagos quase juntos; Asaas fora no cancelamento), a consulta da auditoria (`select object_id, created_at from audit_events where action = 'DUPLICATE_PURCHASE' order by created_at desc`, conferindo os nomes das colunas na V10) e como reconhecer um irmão cancelado (`expired_at` antes de `expires_at`). Roteiro de sandbox: anotar `value`, `netValue` e `originalValue` de uma cobrança paga, e se o Asaas aceita cancelar uma cobrança no instante em que ela é paga.
  - `CLAUDE.md`: a linha do `PaymentConfirmation` ganha "e cancela o Pedido pendente irmão depois do commit".

- [ ] **Step 6: Commit** `feat: cancel the pending sibling order when an order is paid and audit duplicate purchases`

---

## Fim da branch

`just check-rapido`; skill `verificar-entrega`; `/code-review`. Não há tela nem API nova, então não há `/verificar-tela`; o fluxo de compra roda uma vez no sistema de verdade (`just e2e` do pagamento) antes da PR. Push e PR só com o OK do Sérgio; na PR, marcar no #111 as caixas atendidas.
