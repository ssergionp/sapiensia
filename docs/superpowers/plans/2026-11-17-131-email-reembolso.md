# #131 E-mail ao Cliente quando o Pedido é reembolsado — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

Plano do modo noturno: as decisões marcadas **DECIDIDO NO MODO NOTURNO — conferir** não tiveram o OK do Sérgio antes da execução. O texto do e-mail é o do plano do #36 (D4) e vai listado na PR para aprovação.

**Goal:** Quando o `RefundConfirmation` marca um Pedido como `REFUNDED` e cancela o Acesso, o Cliente recebe um e-mail automático, uma vez só, no mesmo modelo do e-mail de pagamento confirmado (#29, FR-024).

**Architecture:** Igual ao `OrderPaidEmail`: na mesma transação que marca o Pedido reembolsado, o `RefundConfirmation` agenda a tarefa de execução única `order-refunded-email` (id da instância = id do Pedido). A tarefa, fora da transação do reembolso, envia o e-mail e marca `refund_email_sent_at`; se as tentativas acabam, marca `refund_email_failed_at` e grava um alerta ao Administrador. O e-mail nunca mexe no estado do Pedido nem no Acesso.

**Tech Stack:** Java 25, Spring Boot 4.1 (`JdbcClient`), db-scheduler, Flyway, JUnit 5, Testcontainers.

**Spec:** issue #131 (Refs #36; texto da decisão D4 de `docs/superpowers/plans/2026-11-15-36-reembolso.md`).

## Decisões

- **R1:** migration **V29** (reservada para o #131): `alter table orders add column refund_email_sent_at timestamptz; alter table orders add column refund_email_failed_at timestamptz;` (molde da V23). A V28 (reservada para o #134) não foi usada; o Flyway aceita o salto.
- **R2:** agendamento dentro da transação do reembolso, só quando o Pedido passa de fato a `REFUNDED` (não em `ALREADY_REFUNDED` nem nos resultados que só registram: MED, parcial, NOT_REFUNDED). Mesmo jeito que o `PaymentConfirmation` agenda o `order-paid-email` (copie o mecanismo de lá).
- **R3:** tentativas e intervalo = `app.email.max-attempts` e `app.email.retry-delay` (os mesmos do e-mail de pagamento).
- **R4 — DECIDIDO NO MODO NOTURNO — conferir:** quando desiste, tipo de alerta novo `ORDER_REFUNDED_EMAIL_GAVE_UP` (no molde do `ORDER_PAID_EMAIL_GAVE_UP`, logo depois dele na ordem de prioridade), com o id do Pedido; um envio que dá certo depois resolve o alerta. O `TASK_GAVE_UP` genérico diria ao Administrador que "o Cliente pode pedir o e-mail de novo pelo site", o que não vale aqui.
- **R5:** Cliente sem e-mail (conta anonimizada) → não envia, registra `NO_CUSTOMER` no log, como o e-mail de pagamento. Log só com o id do Pedido e um motivo (ADR-0006).
- **R6 — DECIDIDO NO MODO NOTURNO — conferir:** o texto do e-mail é o do D4 com as mesmas palavras, quebrado em parágrafos como o e-mail de pagamento (seção abaixo).

## Textos que o usuário vê (para aprovar na PR)

E-mail ao Cliente (texto do plano do #36, D4; só as quebras de linha são novas):

- Assunto: `Seu Pedido na SapiensIA foi reembolsado`
- Corpo:

```
Olá!

O valor do seu Pedido na SapiensIA foi devolvido.

Produto: <título>
Valor: <valor>

Com o Reembolso, o seu Acesso a este Produto foi cancelado e ele não aparece mais na sua Biblioteca.

Se você não reconhece este Reembolso, responda a este e-mail.
```

`<valor>` no formato do e-mail de pagamento (`R$ 1.234,56`, `OrderPaidEmail.formatPrice`).

Alerta ao Administrador (R4) — **DECIDIDO NO MODO NOTURNO — conferir:**
- Título: `e-mail de reembolso não enviado`
- Introdução: `O e-mail que avisa o Cliente do Reembolso não foi enviado depois de todas as tentativas. O Pedido está reembolsado e o Acesso foi cancelado; só o e-mail falhou.`
- O que fazer: `avise o Cliente do Reembolso pelo e-mail de suporte, seguindo o runbook suporte-cliente.md. O id do Pedido está em cada linha.`

Documentação: linha nova do `ORDER_REFUNDED_EMAIL_GAVE_UP` na tabela de tipos de `docs/runbooks/webhook-asaas.md`; verbete de alerta do `GLOSSARY.md` (lista de casos); `docs/privacidade/inventario-dados-pessoais.md` se ele listar os e-mails enviados ao Cliente.

## Global Constraints

- Nenhuma dependência nova; uma migration (V29).
- O e-mail nunca altera o Pedido nem o Acesso; só as duas colunas de entrega.
- Log e alerta: só id do Pedido e motivo; nunca o e-mail do Cliente (ADR-0006, SEC-036).
- Testes: só focados, um pesado por vez; cada teste novo falha por asserção antes do código. Testes de integração no contexto padrão (sem propriedades novas, sem `@MockitoBean`).

## Review Focus

1. Reembolso que se repete (`ALREADY_REFUNDED`, outro evento do mesmo estorno) → um e-mail só (teste).
2. Falha de envio → tentativas, depois `refund_email_failed_at` e alerta; envio posterior que dá certo resolve o alerta (teste no molde do #29).
3. Resultados que não reembolsam (MED, parcial, NOT_REFUNDED) → nenhum e-mail (teste de um deles).
4. Cliente anonimizado → nenhum e-mail, sem erro (teste).
5. Título com `%` não quebra a formatação (teste, como o do e-mail de pagamento).

---

### Task 1: Colunas, envio e tarefa do e-mail de reembolso

**Files:**
- Create (via skill nova-migration): `backend/src/main/resources/db/migration/V29__order_refund_email.sql`
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/payment/Orders.java` (`refundEmailPending`, `markRefundEmailSent`, `markRefundEmailFailed`, no molde dos de pagamento)
- Create: `backend/src/main/java/br/com/sergio/sapiensia/payment/OrderRefundedEmail.java` (`SUBJECT`, `body(title, priceCents)`, `send(UUID)`, `giveUp(UUID)`)
- Create: `backend/src/main/java/br/com/sergio/sapiensia/payment/OrderRefundedEmailTasks.java` (`SEND = "order-refunded-email"`)
- Modify: `alert/AdminAlertType.java` (+ `ORDER_REFUNDED_EMAIL_GAVE_UP` logo depois de `ORDER_PAID_EMAIL_GAVE_UP`), `alert/AdminAlertEmail.java` (textos R4)
- Test: as classes de teste do e-mail de pagamento são o molde (`OrderPaidEmailTest`, `OrderPaidEmailTextTest`, `OrderPaidEmailRestartTest`); crie as equivalentes que fizerem sentido (`OrderRefundedEmailTest`, `OrderRefundedEmailTextTest`); `AdminAlertEmailTextTest` (+ linha); rode também `AdminAlertPrivacyTest` e `AdminAlertOriginsTest`, que conferem os tipos de alerta

- [ ] **Step 1: testes que falham:** texto (assunto e corpo exatos acima, `%` no título); envio único; desistência marca `refund_email_failed_at` e grava o alerta; envio posterior resolve o alerta; Cliente sem e-mail não envia. RED por asserção (comece com a classe e os métodos vazios).
- [ ] **Step 2–4:** implemente e rode só as classes novas + `AdminAlertEmailTextTest` + `AdminAlertDispatchTest`.
- [ ] **Step 5:** `git commit -m "feat: refund e-mail to the Customer, sent once in the background (#131)"`

### Task 2: Agendar o e-mail quando o reembolso acontece

**Files:**
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/payment/RefundConfirmation.java` (agendar na transação do reembolso, R2)
- Modify: docs (runbook, glossário, inventário de dados pessoais se couber)
- Test: nas classes de teste do reembolso do #36 (`git grep -l RefundConfirmation -- backend/src/test`) ou numa classe nova no contexto padrão

- [ ] **Step 1: testes que falham:** `@DisplayName("FR-023: o reembolso confirmado envia um e-mail ao Cliente, uma vez")` — webhook `PAYMENT_REFUNDED` com a cobrança estornada no Fake (`FakePixGateway.refund`, do #132, já na main; veja `LocalDemoRefundTest`) → exatamente um e-mail com o assunto acima para o Cliente; um segundo evento de estorno do mesmo Pedido não manda outro. E um resultado que não reembolsa (ex.: estorno parcial) não manda e-mail.
- [ ] **Step 2–4:** implemente; rode só as classes do reembolso + as da Task 1.
- [ ] **Step 5:** `git commit -m "feat: schedule the refund e-mail when an Order is refunded (#131)"`

## Fora do escopo

- Reenvio manual do e-mail de reembolso pelo Administrador.
- E-mail de MED ou de estorno parcial.
