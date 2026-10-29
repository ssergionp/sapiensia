---
status: accepted
---

# Tarefas em segundo plano com db-scheduler no PostgreSQL

Expirar Pedidos, conciliar pagamentos toda noite e repetir webhooks falhos rodam no próprio PostgreSQL, sem fila externa. Escolhemos o db-scheduler (Apache-2.0, uma tabela criada por migration do Flyway) em vez do JobRunr, cujos recursos avançados são pagos. A retentativa de webhooks vem do registro de eventos do Spring Modulith.

## Consequences

- Não há painel de tarefas; na Fase 1 bastam logs e os alertas por e-mail.
- Spike do Java 25 concluído em 28/10/2026 (issue #6): o db-scheduler 16.12.0, com o starter para Spring Boot 4, roda tarefas no PostgreSQL 18 com Java 25.0.4.1 e Spring Boot 4.1.2; tarefas pendentes são executadas depois de um reinício e a tarefa recorrente continua se repetindo (NFR-004). A decisão está confirmada e o JobRunr não foi reaberto.
- A primeira tarefa real é o envio de e-mails (ticket #9): até 5 tentativas, a cada 5 minutos; depois disso a tarefa é descartada e o erro vai para o log (sem e-mail completo, token nem senha). O mesmo ticket cria a tarefa diária de limpeza das contas não confirmadas há mais de 7 dias.
- O projeto original ainda não declara suporte ao Java 25 (a biblioteca é compilada com Java 17); a evidência é o nosso teste de integração. Retentativa e dead-letter, o restante do NFR-004, ficam para o ticket que criar a primeira tarefa real.
