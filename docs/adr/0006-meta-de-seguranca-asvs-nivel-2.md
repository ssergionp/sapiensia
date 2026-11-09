---
status: accepted
---

# Meta de segurança: OWASP ASVS nível 2

O sistema segue o OWASP ASVS nível 2 como critério de segurança, o que justifica itens que parecem exagero para um projeto pequeno: rotas de `/api` negadas por padrão, teste negativo em todo endpoint que recebe o ID de um objeto, deduplicação de webhooks, reautenticação em ações sensíveis e nada de dado pessoal em log. Escolhemos o nível 2, e não o 1, porque o sistema trata pagamentos e dados pessoais; o nível 3 ficou de fora por custo.

## Consequences

- Alertas e logs nunca levam CPF, e-mail de cliente, token ou senha, só o tipo de problema e o número do Pedido.
- O gate de lançamento verifica os itens aplicáveis do ASVS nível 2 antes da primeira venda.
- Trilha de auditoria (SEC-065): a tabela `audit_events` registra ator, ação, objeto, resultado, hora, origem e correlation id, e só a limpeza de retenção (mínimo de 365 dias, garantido por um gatilho no banco) apaga linhas; ninguém edita. Logs e auditoria nunca levam senha, código, token, segredo nem dado pessoal completo, e o `AuditPrivacyTest` guarda isso.
