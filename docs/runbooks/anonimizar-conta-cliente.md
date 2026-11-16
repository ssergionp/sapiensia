# Anonimizar a conta de um Cliente (pedido de exclusão)

Atende FR-036 e SEC-038 (direito de exclusão da LGPD). Não há tela: é uma rota de API, chamada pelo console do navegador, porque a operação é rara e feita à mão (ADR-0007). O inventário está em `docs/privacidade/inventario-dados-pessoais.md`.

## O que a anonimização faz

A linha do Cliente **não é apagada**: Pedidos, Acessos e Registros de download dependem dela e são registros que só admitem inserção (ADR-0003, ADR-0007). Numa transação só:

- o e-mail vira `anonimizado-<código aleatório>@anonimizado.invalid` (o domínio `.invalid` nunca entrega);
- a senha vira um valor aleatório que ninguém conhece, e toda sessão aberta do Cliente cai;
- `first_login_at` fica vazio e `anonymized_at` recebe a data;
- as linhas de tokens de confirmação, tokens de nova senha, aparelhos conhecidos e do vínculo com o Asaas são apagadas;
- a trilha de auditoria ganha uma linha `ADMIN_CUSTOMER_ANONYMIZED` com o seu id e o id do Cliente, nunca o e-mail;
- e-mails pendentes para essa conta deixam de ser enviados.

Depois disso o painel de Pedidos mostra o e-mail como `a***@anonimizado.invalid`. A pessoa pode se cadastrar de novo com o mesmo e-mail: será uma conta nova.

## O que fica, e por quê

- Pedidos, Acessos, eventos de pagamento (com o `payload` como chegou), resultados e Registros de download ficam por **5 anos depois do Pedido**. O prazo é **provisório, a confirmar com o contador**. São registro financeiro e prova de entrega; sem eles não há como responder a um reembolso ou a uma cobrança.
- Eles não têm e-mail nem nome do Cliente; ficam ligados ao `id` da linha anonimizada.
- Ainda não há limpeza automática desses registros.

## Passos

1. **Confira o pedido.** O e-mail que pediu deve ser o da conta. Se vier de outro e-mail, não anonimize: peça para a pessoa escrever do e-mail da conta. Não cole esse e-mail em ticket, log ou conversa.
2. **Entre no painel** com senha e código do aplicativo.
3. **Abra o console do navegador** (F12, aba Console) na aba do painel, a mesma onde você entrou. Se o navegador pedir para liberar a colagem, digite o que ele indicar.
4. **Reautentique.** A anonimização exige senha e código digitados há pouco (5 minutos, `app.admin.reauthentication-window`). Cole, trocando a senha e o código de 6 dígitos do aplicativo:

   ```js
   const token = () => decodeURIComponent(document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=')[1] ?? '');
   await fetch('/api/admin/sessions/reauthentication', {
     method: 'POST',
     credentials: 'include',
     headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token() },
     body: JSON.stringify({ password: '<sua senha>', code: '<código de 6 dígitos>' }),
   }).then((r) => r.status);
   ```

   Responde `204` quando deu certo. A reautenticação troca o cookie `XSRF-TOKEN`, por isso o `token()` lê o cookie de novo a cada chamada. `400` é senha ou código errado; `429` é bloqueio por tentativas; `401` é sessão encerrada (entre de novo no painel). Depois, feche a aba ou limpe o console, para a senha não ficar no histórico.
5. **Anonimize**, trocando o e-mail pelo da conta:

   ```js
   await fetch('/api/admin/customer-anonymizations', {
     method: 'POST',
     credentials: 'include',
     headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token() },
     body: JSON.stringify({ email: 'cliente@exemplo.com' }),
   }).then((r) => r.status);
   ```

   | Resposta | O que significa |
   |---|---|
   | `204` | Feito. |
   | `404` (`CUSTOMER_NOT_FOUND`) | Não achou a conta, ou ela já foi anonimizada. Confira o e-mail digitado; nada mudou. A tentativa fica na auditoria como falha. |
   | `403` (`REAUTHENTICATION_REQUIRED`) | Passaram mais de 5 minutos: refaça o passo 4 e repita. |
   | `403` (outro) | O perfil não tem a capacidade de anonimizar, ou o token CSRF está ausente ou velho (o cookie `XSRF-TOKEN` muda depois da reautenticação): rode de novo a linha do `token()` e repita a chamada. |
   | `401` | A sessão do painel acabou: entre de novo. |
   | `400` | O e-mail está vazio ou malformado. |

6. **Confira** em Pedidos no painel: se a pessoa tinha Pedidos, o e-mail aparece como `a***@anonimizado.invalid`.
7. **Responda ao Cliente** por e-mail: a conta foi anonimizada, os dados de acesso foram removidos e os registros de compra ficam pelo prazo legal (só cite o prazo de 5 anos depois que o contador o confirmar).
8. **Decida sobre o Asaas.** O sistema não chama o Asaas. Ele guarda o e-mail como nome do cliente e o CPF da cobrança, como registro financeiro dele. Pergunte ao contador se vale pedir a remoção lá (**a conferir**) e, se sim, faça pelo painel do Asaas.
9. **Resend.** O histórico de e-mails enviados fica com o Resend, pela regra dele (**a conferir**). Se for preciso apagar, peça pelo painel do Resend.

## Exportação e correção de dados

Continuam à mão, por e-mail (ADR-0007). Esta rota não faz nenhuma das duas.

## Se algo der errado

- A anonimização é uma transação: ou muda tudo ou nada. Se a resposta for `5xx`, nada mudou; veja o log (sem dado pessoal) e tente de novo.
- Não dá para desfazer: o e-mail original não é guardado em lugar nenhum.
