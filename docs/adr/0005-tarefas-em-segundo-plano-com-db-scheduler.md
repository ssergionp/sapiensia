---
status: accepted
---

# Tarefas em segundo plano com db-scheduler no PostgreSQL

Expirar Pedidos, conciliar pagamentos toda noite e repetir webhooks falhos rodam no próprio PostgreSQL, sem fila externa. Escolhemos o db-scheduler (Apache-2.0, uma tabela criada por migration do Flyway) em vez do JobRunr, cujos recursos avançados são pagos. A retentativa de webhooks vem do registro de eventos do Spring Modulith.

## Consequences

- Não há painel de tarefas; na Fase 1 bastam logs e os alertas por e-mail.
- Nenhuma das duas bibliotecas declara suporte ao Java 25 nas fontes oficiais. Antes de fixar a escolha, um teste de integração com Java 25 deve passar; se falhar, reabrir esta decisão e considerar o JobRunr.
