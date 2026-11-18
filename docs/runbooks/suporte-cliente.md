# Suporte ao Cliente

Como atender o Cliente por e-mail: prazos, Reembolso, pedidos de direitos da LGPD e como registrar sem espalhar dado pessoal. Quem atende é o Administrador (pessoa única).

Tudo marcado **conferir** é decisão conservadora ainda não confirmada por você. Prazos e textos para o Cliente: **conferir** antes da primeira venda.

## Canal e prazos

- Canal: e-mail `sapiensia.operacao@gmail.com` (a caixa já existe; decisão do #181). A Política de privacidade e a de reembolso (#23) devem usar este mesmo endereço.
- Primeira resposta em até 2 dias úteis. **conferir**
- Reembolso decidido em até 2 dias úteis depois do pedido. **conferir**
- Pedido de acesso aos dados: resposta completa em até 15 dias do requerimento ([LGPD, art. 19, II](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709.htm)). **conferir**. Os outros direitos (correção, exclusão) não têm prazo fixo nessa regra; adote um prazo próprio (sugestão: os mesmos 15 dias): **conferir com o advogado**.
- Confira que os textos públicos (Termos, Política de reembolso) prometem o mesmo que esta seção.

## Reembolso

- Regra (ADR-0007): em até 7 dias da compra (arrependimento do CDC) ou por defeito no arquivo. A regra e os textos devem ser confirmados pelo advogado antes da primeira venda (**conferir com o advogado**).
- Quem devolve o dinheiro é você, no painel do Asaas; não há botão no painel da SapiensIA. O sistema cancela o Acesso sozinho quando o Asaas mostra a cobrança como estornada.
- Passo a passo, MED e estorno parcial: [webhook-asaas.md](webhook-asaas.md), seção "Reembolso e MED". Para conferir o pagamento do Cliente: seção "Conferir um pagamento".
- Defeito no arquivo: se for mais simples para o Cliente, ofereça primeiro o arquivo corrigido.

## Direitos da LGPD

Confirme **antes** quem pede: responda sempre para o e-mail cadastrado na conta e peça que o Cliente responda a partir dele. Não envie nada a outro endereço. Dado pessoal de outra pessoa nunca sai.

| Pedido | O que fazer |
|---|---|
| Acesso / exportação | Monte à mão, no SQL Editor do Supabase, um resumo só do que é dele (cadastro, Pedidos, Acessos), guiado por [inventario-dados-pessoais.md](../privacidade/inventario-dados-pessoais.md), e envie para o e-mail da conta. |
| Correção | Corrija à mão no banco, pelo SQL Editor, e confirme ao Cliente. Exportação e correção continuam à mão, por e-mail (ADR-0007). |
| Exclusão | [anonimizar-conta-cliente.md](anonimizar-conta-cliente.md) (rota `POST /api/admin/customer-anonymizations`). Explique ao Cliente o que fica, como descrito lá. |

Se o prazo ficar apertado, responda antes dele dizendo o que falta.

## Modelos de resposta

**conferir** Leia e ajuste antes do primeiro uso; o Cliente lê estes textos. Troque o que estiver entre `<>`.

Primeira resposta:

> Olá, <nome>. Recebemos a sua mensagem e vamos responder em até 2 dias úteis. SapiensIA

Reembolso aprovado:

> Olá, <nome>. O seu reembolso foi aprovado. O valor volta pelo Pix e o acesso ao conteúdo será cancelado. O prazo para o dinheiro chegar depende do seu banco. SapiensIA

Reembolso fora da regra:

> Olá, <nome>. Pela nossa política, o reembolso vale em até 7 dias da compra ou em caso de defeito no arquivo, e não conseguimos atender este pedido. Se o arquivo tiver algum problema, nos conte qual. SapiensIA

Pedido de dados (LGPD):

> Olá, <nome>. Para a sua segurança, responda esta mensagem a partir do e-mail cadastrado na conta. Em seguida atendemos o seu pedido em até 15 dias. SapiensIA

## Como registrar o atendimento

- Guarde só: data, tipo (dúvida, Reembolso, LGPD), id do Pedido, decisão e data da resposta. **Sem** nome, e-mail, texto da conversa ou captura de tela no git, em issue, PR ou chat.
- O registro vai para uma planilha ou nota sua, fora do git. Se o assunto virar defeito do sistema, abra a issue sem dado pessoal (use o id do Pedido).
- Se o pedido revelar um incidente (acesso indevido, dado de outro Cliente na tela), pare e siga [incidente.md](incidente.md).
