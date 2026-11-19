# Suporte ao Cliente

Como atender o Cliente por e-mail: prazos, Reembolso, pedidos de direitos da LGPD e como registrar sem espalhar dado pessoal. Quem atende é o Administrador (pessoa única).

Tudo marcado **conferir** é decisão conservadora ainda não confirmada por você. Prazos e textos para o Cliente: **conferir** antes da primeira venda.

## Canal e prazos

- Canal: e-mail `sapiensia.operacao@gmail.com` (a caixa já existe; decisão do #181). A Política de privacidade e a de reembolso (#23) devem usar este mesmo endereço.
- Primeira resposta em até 2 dias úteis. **conferir**
- Reembolso devolvido em até 2 dias úteis depois da solicitação no site (o mesmo prazo da Política de reembolso). **conferir**
- Pedido de acesso aos dados: resposta completa em até 15 dias do requerimento ([LGPD, art. 19, II](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709.htm)). **conferir**. Os outros direitos (correção, exclusão) não têm prazo fixo nessa regra; adote um prazo próprio (sugestão: os mesmos 15 dias): **conferir com o advogado**.
- Confira que os textos públicos (Termos, Política de reembolso) prometem o mesmo que esta seção.

## Reembolso

Regra (ADR-0007): o Cliente pede o Reembolso pelo site, em Meus Pedidos, até o fim do 7º dia depois do dia do pagamento (horário de São Paulo; pago em 19/11, pede até 26/11 às 23:59). Dentro desse prazo o Reembolso é sempre concedido, qualquer que seja o motivo. Depois dele o site recusa, e só o defeito no arquivo ainda é atendido, por e-mail e sem limite de dias. A regra e os textos devem ser confirmados pelo advogado antes da primeira venda (**conferir com o advogado**).

Ao enviar a solicitação, o Acesso daquele Pedido fica suspenso na hora (sai da Biblioteca e não baixa mais) e você recebe o Alerta "Reembolso solicitado". O Pedido continua pago até o Asaas mostrar a cobrança como estornada. Quem devolve o dinheiro é você, no painel do Asaas; não há botão no painel da SapiensIA (ADR-0003). Prometemos devolver em até 2 dias úteis depois da solicitação, e o Acesso fica suspenso enquanto isso.

Passo a passo:

1. Chega o Alerta "Reembolso solicitado" por e-mail.
2. Entre no painel da SapiensIA e abra **Reembolsos** (a tela vem filtrada em "Aguardando devolução").
3. Na linha do Pedido, leia o **Motivo** (e o "Comentário do Cliente", se houver), os **Downloads do Produto** e os **Outros Reembolsos em 12 meses**. O motivo não muda nada: dentro do prazo o Reembolso é devido.
4. Copie o id da coluna **Cobrança no Asaas** (começa com `pay_`).
5. No painel do Asaas, ache a cobrança pelo id que você copiou (`pay_...`, coluna "Cobrança no Asaas"). **conferir** (a busca por esse id no painel de verdade ainda não foi verificada). A seção "Conferir um pagamento" de [webhook-asaas.md](webhook-asaas.md) mostra essa mesma conferência feita no sandbox.
6. Estorne o valor **inteiro** da cobrança. Um estorno parcial não cancela o Acesso (veja abaixo).
7. Espere o Asaas confirmar. Na tela Reembolsos a Situação muda de "Aguardando devolução" para "Devolvido em ..."; nesse momento o Pedido vira Reembolsado, o Acesso é cancelado em definitivo e o Cliente recebe o e-mail "Seu Pedido na SapiensIA foi reembolsado". Se a Situação não mudar em alguns minutos, siga as seções "Reembolso e MED" e "Reprocessar um evento de pagamento" de [webhook-asaas.md](webhook-asaas.md).
8. Registre o atendimento (seção "Como registrar o atendimento"). Não é preciso responder: o site já confirma a solicitação ao Cliente e o e-mail do sistema avisa quando o valor é devolvido.

### O Cliente pediu por engano

Não há como desistir da solicitação pelo site. Devolva o valor normalmente: depois do estorno o Cliente pode comprar o Produto de novo e recebe um Acesso novo. Enquanto a solicitação estiver aberta, o site recusa uma nova compra do mesmo Produto, com a mensagem "Você pediu o Reembolso deste Produto. Depois que o valor for devolvido, você pode comprar de novo."

### Falta saldo na conta do Asaas

Pela documentação da API do Asaas, o estorno é recusado (resposta 400) quando o saldo não cobre o valor; a solicitação continua em "Aguardando devolução". **conferir** (vem da documentação, não de um teste no painel). Ponha saldo na conta e repita o estorno. O Acesso do Cliente fica suspenso até lá, por isso aja dentro do prazo prometido de 2 dias úteis.

### Estorno parcial feito sem querer

O sistema registra `PARTIAL_REFUND` e **não** cancela o Acesso: a solicitação segue em "Aguardando devolução". Estorne o resto do valor no painel do Asaas; a cobrança passa a estornada e o Acesso é cancelado.

### Pedido por e-mail depois dos 7 dias

Só o defeito no arquivo é atendido depois do prazo do site (sem limite de dias). Se for mais simples para o Cliente, ofereça primeiro o arquivo corrigido. Se ele preferir o Reembolso, faça o mesmo estorno do valor inteiro no painel do Asaas: não há solicitação no site, então a tela Reembolsos não mostra esse caso, mas o sistema cancela o Acesso quando o Asaas mostra a cobrança estornada. Fora do caso de defeito, use o modelo "Reembolso fora da regra".

### Cliente com dois Pedidos pagos do mesmo Produto (compra duplicada)

**conferir** (regra proposta na revisão do #213, aguardando a sua confirmação). Antes de estornar, veja no painel (Pedidos) se o Cliente tem outro Pedido pago do mesmo Produto. Se tiver, estorne **os dois**: o duplicado (como já pede [webhook-asaas.md](webhook-asaas.md), seção "Reembolso e MED") e o da Solicitação de reembolso. Nunca estorne só o duplicado quando a solicitação é do outro Pedido: o Acesso do Pedido solicitado ficaria suspenso para sempre. Depois dos dois estornos, o Cliente pode comprar o Produto de novo.

Outros casos do estorno (MED, compra duplicada): [webhook-asaas.md](webhook-asaas.md), seção "Reembolso e MED".

## Reembolsos repetidos

O Alerta `REFUND_REQUESTS_REPEATED` ("Cliente com 3 ou mais Reembolsos em 12 meses") avisa que o Cliente que acabou de pedir um Reembolso soma, com ele, 3 ou mais em 12 meses (contam os outros Pedidos dele já reembolsados ou com solicitação).

- O Reembolso dentro do prazo continua devido: devolva o valor pelo passo a passo da seção "Reembolso".
- Novas compras do Cliente não são bloqueadas (CDC, art. 39, II).
- Veja o histórico dele na tela Reembolsos (coluna "Outros Reembolsos em 12 meses") e avalie se há indício de abuso, por exemplo baixar o arquivo e pedir o dinheiro de volta várias vezes.
- A contagem inclui todo Pedido reembolsado do Cliente no período, também os que ele não pediu pelo site (compra duplicada devolvida por você, Reembolso pedido por e-mail): olhe a lista antes de concluir que há abuso.
- Limitar ou suspender a conta por abuso é uma decisão manual sua, sob a seção "Suspensão por abuso" dos Termos de uso, cujos critérios e aviso prévio ainda estão por definir (**conferir com o advogado**, como o resto dos textos legais). Sem essa confirmação, não suspenda a conta.

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

> Olá, <nome>. Pela nossa política, o pedido de reembolso pelo site vale até o fim do 7º dia depois do pagamento, e esse prazo já terminou. Se o arquivo tiver algum defeito, nos conte qual por e-mail: nesse caso o reembolso não tem limite de dias. SapiensIA

Pedido de dados (LGPD):

> Olá, <nome>. Para a sua segurança, responda esta mensagem a partir do e-mail cadastrado na conta. Em seguida atendemos o seu pedido em até 15 dias. SapiensIA

## Como registrar o atendimento

- Guarde só: data, tipo (dúvida, Reembolso, LGPD), id do Pedido, decisão e data da resposta. **Sem** nome, e-mail, texto da conversa ou captura de tela no git, em issue, PR ou chat.
- O registro vai para uma planilha ou nota sua, fora do git. Se o assunto virar defeito do sistema, abra a issue sem dado pessoal (use o id do Pedido).
- Se o pedido revelar um incidente (acesso indevido, dado de outro Cliente na tela), pare e siga [incidente.md](incidente.md).
