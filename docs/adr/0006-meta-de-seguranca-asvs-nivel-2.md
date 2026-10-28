---
status: accepted
---

# Meta de segurança: OWASP ASVS nível 2

O sistema segue o OWASP ASVS nível 2 como critério de segurança, o que justifica itens que parecem exagero para um projeto pequeno: rotas de `/api` negadas por padrão, teste negativo em todo endpoint que recebe o ID de um objeto, deduplicação de webhooks, reautenticação em ações sensíveis e nada de dado pessoal em log. Escolhemos o nível 2, e não o 1, porque o sistema trata pagamentos e dados pessoais; o nível 3 ficou de fora por custo.

## Consequences

- Alertas e logs nunca levam CPF, e-mail de cliente, token ou senha, só o tipo de problema e o número do Pedido.
- O gate de lançamento verifica os itens aplicáveis do ASVS nível 2 antes da primeira venda.
