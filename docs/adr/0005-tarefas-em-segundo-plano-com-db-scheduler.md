---
status: accepted
---

# Tarefas em segundo plano com db-scheduler no PostgreSQL

Expirar Pedidos, conciliar pagamentos toda noite e repetir webhooks falhos rodam no próprio PostgreSQL, sem fila externa. Escolhemos o db-scheduler (Apache-2.0, uma tabela criada por migration do Flyway) em vez do JobRunr, cujos recursos avançados são pagos. A retentativa de webhooks é a tarefa única `process-payment-event` (uma por evento de pagamento, agendada na mesma transação que grava o evento): se o processamento falhar, o db-scheduler repete a cada 1 minuto, até 120 vezes; esgotadas, grava o resultado `GAVE_UP` e o evento pode ser reagendado pelo runbook.

## Consequences

- Não há painel de tarefas; na Fase 1 bastam logs e os alertas por e-mail.
- Spike do Java 25 concluído em 28/10/2026 (issue #6): o db-scheduler 16.12.0, com o starter para Spring Boot 4, roda tarefas no PostgreSQL 18 com Java 25.0.4.1 e Spring Boot 4.1.2; tarefas pendentes são executadas depois de um reinício e a tarefa recorrente continua se repetindo (NFR-004). A decisão está confirmada e o JobRunr não foi reaberto.
- A primeira tarefa real é o envio de e-mails (ticket #9): até 5 tentativas, a cada 5 minutos; depois disso a tarefa é descartada e o erro vai para o log (sem e-mail completo, token nem senha). O mesmo ticket cria a tarefa diária de limpeza das contas não confirmadas há mais de 7 dias.
- O projeto original ainda não declara suporte ao Java 25 (a biblioteca é compilada com Java 17); a evidência é o nosso teste de integração. A retentativa e o descarte das tarefas de e-mail estão definidos acima (ticket #9); um armazenamento de dead-letter (tarefas descartadas para reprocessar), o restante do NFR-004, continua fora do escopo.
