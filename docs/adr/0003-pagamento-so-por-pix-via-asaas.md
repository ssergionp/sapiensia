---
status: accepted
---

# Pagamento só por Pix via Asaas

A Fase 1 aceita apenas Pix, pelo Asaas, atrás de um adaptador interno para permitir trocar de fornecedor. O Pedido expira em 30 minutos por regra nossa (o QR Code do Asaas dura até 12 meses depois do vencimento), e ao expirar o sistema cancela a cobrança no Asaas. O sistema só marca o Pedido como pago e concede o Acesso depois que o Asaas confirma.

## Regras

- Se o Asaas confirmar o pagamento de um Pedido já expirado, o Pedido vira pago e o Acesso é concedido normalmente.
- O Reembolso é feito pelo Administrador no painel do Asaas. O sistema recebe o aviso de estorno do Asaas e o de devolução por fraude (o MED do Pix) e cancela o Acesso sozinho; não há botão de reembolso no painel na Fase 1. Os nomes exatos dos eventos, sobretudo o do MED, devem ser conferidos na documentação do Asaas ao desenhar o ticket.

## Consequences

- O webhook é autenticado pelo token do cabeçalho `asaas-access-token`, com conferência dos IPs oficiais. O `id` de cada evento é a chave de deduplicação, porque a entrega é "pelo menos uma vez".
- O Asaas exige o CPF do pagador. Pedimos o CPF só na primeira compra, enviamos ao Asaas e **não o guardamos**: o banco do sapiensia mantém apenas o código do cliente no Asaas. Se o contador exigir o CPF para nota fiscal, esta decisão deve ser reaberta.
- Se o Asaas pausar a fila de webhooks (após 15 falhas seguidas), a conciliação noturna cobre a lacuna.
- Em aberto, fora do código: se uma pessoa física pode vender produto digital pelo Asaas e qual o limite de recebimento (perguntar ao suporte do Asaas e ao contador).
