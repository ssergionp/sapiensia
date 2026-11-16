---
status: accepted
---

# Tarefas em segundo plano com db-scheduler no PostgreSQL

Expirar Pedidos, conciliar pagamentos toda noite e repetir webhooks falhos rodam no próprio PostgreSQL, sem fila externa. Escolhemos o db-scheduler (Apache-2.0, uma tabela criada por migration do Flyway) em vez do JobRunr, cujos recursos avançados são pagos. A retentativa de webhooks é a tarefa única `process-payment-event` (uma por evento de pagamento, agendada na mesma transação que grava o evento): se o processamento falhar, o db-scheduler repete a cada 1 minuto, até 120 vezes; esgotadas, grava o resultado `GAVE_UP` e o evento pode ser reagendado pelo runbook.

## Consequences

- Não há painel de tarefas; na Fase 1 bastam logs e os alertas por e-mail.
- Spike do Java 25 concluído em 28/10/2026 (issue #6): o db-scheduler 16.12.0, com o starter para Spring Boot 4, roda tarefas no PostgreSQL 18 com Java 25.0.4.1 e Spring Boot 4.1.2; tarefas pendentes são executadas depois de um reinício e a tarefa recorrente continua se repetindo (NFR-004). A decisão está confirmada e o JobRunr não foi reaberto.
- A primeira tarefa real é o envio de e-mails (ticket #9): até 5 tentativas, a cada 5 minutos; depois disso a tarefa é descartada e o erro vai para o log (sem e-mail completo, token nem senha). O mesmo ticket cria a tarefa diária de limpeza das contas não confirmadas há mais de 7 dias.
- O e-mail de pagamento confirmado (ticket #29) é a tarefa `order-paid-email`, uma por Pedido, agendada na transação do pagamento; o resultado fica em `orders.payment_email_sent_at` / `payment_email_failed_at`.
- O projeto original ainda não declara suporte ao Java 25 (a biblioteca é compilada com Java 17); a evidência é o nosso teste de integração. A retentativa e o descarte das tarefas de e-mail estão definidos acima (ticket #9); um armazenamento de dead-letter (tarefas descartadas para reprocessar), o restante do NFR-004, continua fora do escopo.
- Os alertas por e-mail ao Administrador (ticket #38, NFR-009) são enviados pela tarefa recorrente `admin-alert-dispatch` (a cada 1 minuto, `app.alerts.dispatch-interval`), nunca dentro da transação do pagamento: quem detecta o problema só grava uma linha em `admin_alerts` (na mesma transação do resultado, quando há uma) e a tarefa lê as linhas pendentes. Recorrente, e não uma tarefa por alerta, para que nenhuma linha fique sem tarefa. A tarefa não tem tratamento de falha que grave alerta (não há alerta sobre alerta). Uma tarefa recorrente que falha várias vezes seguidas ainda não alerta; isso está na issue #134.
- A conciliação com o Asaas (ticket #39) é a tarefa recorrente `asaas-reconciliation`, por cron às 03:30 de São Paulo (`app.reconciliation.cron`; `"-"` a desliga no local, na demonstração e nos testes). A execução fica em `scheduled_tasks`: sobrevive a um reinício, e uma noite perdida roda quando o sistema volta. Se a execução falhar (Asaas fora do ar, teto de páginas), a próxima tentativa é a noite seguinte (`onFailureReschedule`), sem repetir na mesma noite; o alerta é gravado pela própria execução. Para rodar na hora, o runbook `docs/runbooks/conciliacao-asaas.md` antecipa o `execution_time` da linha.
