# Webhook do Asaas na homologação (token e sandbox)

Passo a passo para ligar o aviso de pagamento do Asaas (webhook) ao app `sapiensia-homolog`: gerar o token, gravá-lo no Fly.io, criar o webhook no sandbox do Asaas e conferir que chega. A rota é `POST /api/webhooks/asaas` e a decisão está no `docs/adr/0003-pagamento-so-por-pix-via-asaas.md`. O deploy em si segue o `docs/runbooks/homologacao-fly.md`.

Legenda: **[AGENTE]** roda na sessão do Claude Code. **[RESPONSÁVEL]** você faz, digitando numa aba comum do PowerShell, na pasta do projeto, nunca no chat, ou no navegador.

Regras que valem para tudo aqui:

- **Nenhum valor de segredo** (o token do webhook, em especial) em chat, arquivo, commit, PR, issue ou log. Este documento só tem nomes.
- O token **não** é uma API Key do Asaas: é um valor inventado por você, que o Asaas manda de volta em cada aviso para provar que veio dele.
- Localmente e no CI não há nada a fazer: o perfil local tem um valor fictício e o CI gera um token novo a cada execução.

## Passo zero: antes de copiar qualquer segredo

O mesmo do `docs/runbooks/homologacao-fly.md`.

1. **[RESPONSÁVEL]** **Desligue o histórico da área de transferência do Windows** (Configurações > Sistema > Área de Transferência) antes de copiar o token; depois, religue-o se quiser. Limpar o histórico uma vez antes **não** protege o que for copiado depois.
2. **[RESPONSÁVEL]** No Bitwarden, ligue a limpeza automática da área de transferência (Configurações > Opções > "Limpar área de transferência"), por exemplo 30 segundos.

## 1. Token e segredo no Fly.io

1. **[RESPONSÁVEL]** Gere o token (64 caracteres hexadecimais: dentro do limite de 32 a 255, sem espaço) e copie-o, sem mostrá-lo.

   ```powershell
   $b = New-Object byte[] 32; [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); ($b | ForEach-Object { $_.ToString("x2") }) -join "" | Set-Clipboard; $b = $null
   ```

   Gera 32 bytes aleatórios seguros, escreve em hexadecimal e põe na área de transferência. Cole no Bitwarden com o nome `APP_ASAAS_WEBHOOK_TOKEN (homolog)` e salve; só depois siga.

2. **[RESPONSÁVEL]** Grave o segredo no Fly, **antes do deploy desta versão** (sem ele a aplicação não sobe).

   ```powershell
   (Get-Clipboard).Length
   ```

   Mostra só o tamanho do que está na área de transferência; o esperado é `64`.

   ```powershell
   fly secrets set APP_ASAAS_WEBHOOK_TOKEN="$(Get-Clipboard)" --stage -a sapiensia-homolog
   ```

   Grava o que está na área de transferência como segredo do app, sem reiniciar.

   ```powershell
   Set-Clipboard -Value $null
   ```

   Esvazia a área de transferência.

3. **[AGENTE]** Confira os nomes.

   ```powershell
   fly secrets list -a sapiensia-homolog
   ```

   Mostra só nomes, digest e data. Devem aparecer 10 nomes (a chave da API do Asaas, `APP_ASAAS_API_KEY`, é o décimo; veja `docs/runbooks/asaas-chave-api.md`).

4. **[AGENTE]** Faça o deploy (seção 5 do `docs/runbooks/homologacao-fly.md`) e leia o log. Procure `Started` e confira que não há nenhum erro de inicialização citando `APP_ASAAS_WEBHOOK_TOKEN` ou `app.asaas.webhook` (significa segredo ausente, ou token fora de 32 a 255 caracteres).

## 2. Criar o webhook no sandbox do Asaas

5. **[RESPONSÁVEL]** Entre em <https://sandbox.asaas.com> (conta separada da produção) e vá em `Menu do usuário > Integrações > Webhooks > Criar Webhook`. Preencha:

   - **Nome**: `sapiensia-homolog`
   - **URL**: `https://sapiensia-homolog.fly.dev/api/webhooks/asaas`
   - **E-mail**: a caixa que deve receber o aviso de fila pausada
   - **API Version**: 3
   - **Auth Token**: copie do Bitwarden e cole. Se preferir "Gerar token", copie o valor na hora para o Bitwarden, porque a documentação do Asaas não garante que ele reaparece.
   - **Enabled**: ligado
   - **Interrupted**: desligado
   - **Send Type**: `SEQUENTIALLY` (preserva a ordem; o volume da Fase 1 é baixo)
   - **Eventos**: `PAYMENT_CREATED`, `PAYMENT_CONFIRMED`, `PAYMENT_RECEIVED`, `PAYMENT_OVERDUE`, `PAYMENT_DELETED`, `PAYMENT_REFUNDED`, `PAYMENT_REFUND_IN_PROGRESS`, `PAYMENT_PARTIALLY_REFUNDED`, `PAYMENT_CHARGEBACK_REQUESTED`, `BALANCE_VALUE_BLOCKED`, `BALANCE_VALUE_UNBLOCKED`

   Salve. Se usou "Gerar token", repita os passos 2 a 4 com o token gerado.

6. **[RESPONSÁVEL]** **Acorde a máquina.** Abra no navegador `https://sapiensia-homolog.fly.dev/api/system/health` e espere `{"status":"UP"}` (pode levar mais de um minuto). A máquina da homologação desliga sozinha sem tráfego (`auto_stop_machines`); se o Asaas chegar com ela desligada, a primeira entrega pode falhar por tempo esgotado. Não é defeito: o Asaas tenta de novo e a entrega seguinte aparece com 200 nos Logs de Webhooks. Só preocupa se várias seguidas falharem (15 seguidas pausam a fila).

## 3. Prova real

7. **[RESPONSÁVEL]** No sandbox, crie uma cobrança Pix de teste (Cobranças > Nova cobrança; qualquer cliente fictício do sandbox). Isso dispara `PAYMENT_CREATED`. Em `Integrações > Logs de Webhooks`, a entrega deve mostrar **200**. Se o sandbox oferecer "simular pagamento", use-o para ver `PAYMENT_RECEIVED`.

8. **[RESPONSÁVEL]** Confira no banco (SQL Editor do Supabase, no navegador).

   ```sql
   select provider_event_id, event_type, received_at from payment_events order by received_at desc limit 5;
   ```

   Mostra os últimos eventos gravados.

   A tabela `payment_events` é o registro financeiro e não aceita UPDATE, DELETE nem TRUNCATE (SEC-028, migration V17): o banco recusa com `payment_events is append-only`. Não tente corrigir uma linha à mão; correção de dados só por migration que desligue o gatilho de forma explícita (ADR-0003).

   Depois de cada deploy que traga a V17, confira (só leitura, no mesmo SQL Editor) que a proteção está ligada e que nenhum outro papel acessa a tabela. A consulta funciona em qualquer banco (Supabase, local ou CI); o dono da tabela (o usuário da aplicação), os superusuários e os papéis pré-definidos `pg_*` (um membro deles aparece pelo próprio nome) não entram na lista, porque passam por cima de privilégios e de RLS de qualquer jeito (ADR-0003, limite).

   ```sql
   select c.relrowsecurity as rls,
          (select count(*) from pg_trigger t
            where t.tgrelid = c.oid and t.tgenabled = 'O'
              and t.tgname in ('payment_events_guard_row', 'payment_events_guard_truncate')) as gatilhos_ligados,
          (select string_agg(r.rolname, ', ' order by r.rolname) from pg_roles r
            where r.oid <> c.relowner and not r.rolsuper and r.rolname not like 'pg\_%'
              and has_table_privilege(r.oid, c.oid, 'select,insert,update,delete,truncate')) as outros_papeis_com_acesso
   from pg_class c where c.oid = 'public.payment_events'::regclass;
   ```

   Esperado: `rls = true`, `gatilhos_ligados = 2` e `outros_papeis_com_acesso` vazio (`NULL`). Qualquer outro resultado: pare e avise; sobrou acesso ou o gatilho foi desligado, e é correção do ticket #27.

   **[AGENTE]** Depois, confira o log:

   ```powershell
   fly logs -a sapiensia-homolog --no-tail
   ```

   Mostra o log recente; procure `Asaas webhook event stored`.

## Reprocessar um evento de pagamento

Um evento `PAYMENT_RECEIVED` ou `PAYMENT_CONFIRMED` é processado em segundo plano: o app consulta a cobrança no Asaas e, se estiver paga, marca o Pedido como pago e libera o Acesso. Um evento de estorno (`PAYMENT_REFUNDED`, `PAYMENT_PARTIALLY_REFUNDED`) ou de bloqueio de saldo (`BALANCE_VALUE_BLOCKED`) segue o mesmo caminho: o app consulta a cobrança e só cancela o Acesso se ela estiver estornada. Se o Asaas ficar fora do ar por tempo demais, o app desiste (resultado `GAVE_UP`, com um `ERROR` no log) e o evento continua guardado em `payment_events`. Quando o Asaas voltar, reagende o evento. **[RESPONSÁVEL]** roda no SQL Editor do Supabase, no navegador.

1. Liste os eventos que desistiram e ainda não têm resultado final:

   ```sql
   select e.provider_event_id, e.event_type, e.received_at
   from payment_events e
   where exists (select 1 from payment_event_results r where r.payment_event_id = e.id and r.result = 'GAVE_UP')
     and not exists (select 1 from payment_event_results r where r.payment_event_id = e.id and r.result <> 'GAVE_UP')
   order by e.received_at;
   ```

   Mostra o id (`evt_...`) de cada evento a reprocessar.

2. Troque `evt_COLE_AQUI_O_ID_DO_EVENTO` pelo id do evento e rode:

   <!-- reprocess-sql:start -->
   ```sql
   insert into scheduled_tasks (task_name, task_instance, task_data, execution_time, picked, version)
   select 'process-payment-event', e.id::text, null, now(), false, 1
   from payment_events e
   where e.provider_event_id = 'evt_COLE_AQUI_O_ID_DO_EVENTO'
     and e.event_type in ('PAYMENT_RECEIVED', 'PAYMENT_CONFIRMED', 'PAYMENT_REFUNDED',
                        'PAYMENT_PARTIALLY_REFUNDED', 'BALANCE_VALUE_BLOCKED')
   on conflict (task_name, task_instance) do nothing;
   ```
   <!-- reprocess-sql:end -->

   Agenda o processamento do evento para agora; o app o pega em segundos. Resultado `INSERT 0 1` é o esperado; `INSERT 0 0` quer dizer que o evento não existe, não é um evento de pagamento, de estorno ou de bloqueio de saldo, ou já tem uma tarefa na fila.

3. Rode de novo a consulta do passo 1: o evento some da lista quando recebe o resultado final.

Reprocessar é seguro: o Pedido só vira pago uma vez, só vira reembolsado uma vez, o Acesso só é liberado uma vez e só é cancelado uma vez, mesmo que o SQL rode de novo. Vale para os cinco tipos do SQL acima, os de pagamento e os de estorno e bloqueio de saldo. O `on conflict` não mexe numa tarefa que ainda está repetindo, então não há risco de duplicar nem de atropelar a tentativa em andamento.

## Reembolso e MED

O Administrador devolve o dinheiro no painel do Asaas. O app não tem botão de reembolso: quando o Asaas manda `PAYMENT_REFUNDED`, `PAYMENT_PARTIALLY_REFUNDED` ou `BALANCE_VALUE_BLOCKED`, a tarefa consulta a cobrança e só cancela o Acesso se ela estiver `REFUNDED` (ADR-0003, #36). Se o Asaas estiver fora do ar ou o estorno ainda em andamento (`REFUND_REQUESTED`, `REFUND_IN_PROGRESS`), a tarefa repete a cada minuto; esgotadas as tentativas, o resultado é `GAVE_UP` (veja "Reprocessar um evento de pagamento"). `PAYMENT_REFUND_IN_PROGRESS` e `BALANCE_VALUE_UNBLOCKED` só são gravados.

Resultados novos, na tabela `payment_event_results` (todos, menos os dois primeiros, aparecem como `WARN` `Payment event needs review`):

| Resultado | O que significa | O que fazer |
|---|---|---|
| `REFUNDED` | Pedido reembolsado e Acesso do Pedido cancelado | Nada |
| `ALREADY_REFUNDED` | O Pedido já estava reembolsado (segundo aviso, ou pagamento que chegou depois do estorno) | Nada: o Pedido não volta a pago e não concede Acesso |
| `NOT_REFUNDED` | Aviso de estorno, mas o Asaas ainda mostra a cobrança como não estornada (aviso adiantado ou forjado) | Conferir a cobrança no painel do Asaas; se o estorno foi mesmo feito, reagende o evento depois |
| `PARTIAL_REFUND` | Estorno parcial; o Acesso **continua** | Se o Cliente deve perder o Acesso, estorne o restante no painel do Asaas (a cobrança fica `REFUNDED` e o aviso seguinte cancela); se não, nada |
| `MED_BLOCKED` | Bloqueio de saldo por MED (`PIX_INFRACTION`) com a cobrança ainda não estornada; o Acesso **continua** | Ação manual abaixo |
| `MED_WITHOUT_CHARGE` | MED sem o id da cobrança (`paymentId` vazio) | Ação manual abaixo, achando a cobrança pelo valor e pela data no painel do Asaas |
| `BALANCE_BLOCKED` | Bloqueio de saldo de outro tipo (judicial, administrativo, efeito contratual) | Tratar no painel do Asaas; não mexe em Pedido |
| `CHARGE_NOT_FOUND`, `CHARGE_ID_MISMATCH`, `REFERENCE_MISMATCH`, `NO_ORDER`, `INVALID_PAYLOAD` | A cobrança do aviso não bate com nenhum Pedido | Investigar no painel do Asaas; nada foi cancelado |

**Ação manual para `MED_BLOCKED` e `MED_WITHOUT_CHARGE`.** O bloqueio de saldo não prova que o dinheiro foi devolvido, então o app não cancela nada. **[RESPONSÁVEL]** Acompanhe o MED no painel do Asaas (e com o suporte, se preciso). Se o MED for aceito e o dinheiro devolvido, o Asaas deve mudar a cobrança para estornada e mandar `PAYMENT_REFUNDED`, e o Acesso cai sozinho. Se a cobrança continuar `RECEIVED` depois do MED aceito (ainda não se sabe se isso acontece; veja o roteiro de sandbox), reagende o evento `BALANCE_VALUE_BLOCKED` (SQL de "Reprocessar um evento de pagamento", que também aceita os tipos de estorno) depois que o Asaas mostrar a cobrança estornada. Se o Asaas nunca a mostrar estornada, o app não cancela: decida com o Administrador e registre a decisão fora do banco (`payment_events` e `payment_event_results` não aceitam correção). Um aviso de estorno que nunca chega também não cancela nada até existir a conciliação (#39).

**Compra duplicada.** Se o Cliente pagou dois Pedidos do mesmo Produto, o segundo termina em `DUPLICATE_PURCHASE` (sem Acesso novo). Estorne no painel do Asaas **o Pedido marcado `DUPLICATE_PURCHASE`**, não o que concedeu o Acesso: o app cancela o Acesso do Pedido estornado, e estornar o outro deixaria o Cliente com um Pedido pago e sem Acesso. Para achar o Pedido certo:

```sql
select r.order_id, e.provider_event_id, r.processed_at
from payment_event_results r join payment_events e on e.id = r.payment_event_id
where r.result = 'DUPLICATE_PURCHASE'
order by r.processed_at desc;
```

Lista os Pedidos marcados como compra duplicada, com o evento que os marcou.

**Limites.** O cancelamento não invalida um link de Download já emitido (vale 60 s) nem o arquivo já baixado. Depois do cancelamento o Cliente pode comprar de novo, e o novo Pedido pago cria um Acesso novo.

### Roteiro de sandbox: o que o Asaas de verdade faz

A documentação do Asaas não responde a estes pontos e os testes usam um Asaas simulado. **[RESPONSÁVEL]** Faça no sandbox (<https://sandbox.asaas.com>) e anote o resultado de cada item; ao terminar, avise o agente para ajustar esta seção, o ADR-0003 e o `RefundConfirmation`, se preciso. Para cada passo, consulte os eventos (`payment_events`), os resultados (`payment_event_results`) e o Pedido (`orders.status`) como em "Conferir um pagamento".

1. **Estorno total do Pix.** Compre um Produto, confirme o pagamento no sandbox e estorne o valor total da cobrança. Anote: o estado da cobrança no Asaas depois do estorno, quais eventos chegaram e em que ordem, e se o resultado foi `REFUNDED` (Pedido reembolsado, Acesso cancelado, Produto fora da Biblioteca).
2. **Estornos parciais que somam o total.** Em outra compra, estorne parte do valor, depois o resto. Anote: o estado da cobrança depois do primeiro e depois do segundo estorno, e se o resultado foi `PARTIAL_REFUND` e depois `REFUNDED`. Se a cobrança continuar sem virar `REFUNDED` mesmo com a soma total, o app não cancela: registre como problema.
3. **`PAYMENT_REFUND_IN_PROGRESS`.** Anote se esse evento acontece num estorno de Pix e se o `PAYMENT_REFUNDED` vem depois (o app só grava o primeiro e espera o segundo).
4. **MED.** Se o sandbox permitir simular um MED: anote o estado da cobrança durante o bloqueio (esperado: o app responde `MED_BLOCKED`, ou `MED_WITHOUT_CHARGE` se o `paymentId` vier vazio); depois, se um MED aceito gera `PAYMENT_REFUNDED` ou muda a cobrança para `REFUNDED`, ou se a cobrança continua `RECEIVED`. Se o sandbox não simular o MED, registre isso e deixe o ponto em aberto para a produção.
5. **Aviso de estorno de uma cobrança que não foi estornada**, se o sandbox deixar reenviar um evento: o resultado esperado é `NOT_REFUNDED`, sem mudança no Pedido.

## Reenviar o e-mail de pagamento confirmado

O e-mail de pagamento confirmado é enviado em segundo plano, depois que o Pedido vira pago. Se o envio falhar todas as tentativas, a coluna `orders.payment_email_failed_at` é preenchida (e o log traz `Order paid e-mail gave up`); o Pedido e o Acesso não mudam. Use este passo quando essa coluna estiver preenchida ou quando o Cliente disser que o e-mail não chegou. **[RESPONSÁVEL]** roda no SQL Editor do Supabase, no navegador.

Troque `COLE_AQUI_O_ID_DO_PEDIDO` pelo id do Pedido e rode:

<!-- resend-email-sql:start -->
```sql
insert into scheduled_tasks (task_name, task_instance, task_data, execution_time, picked, version)
select 'order-paid-email', o.id::text, null, now(), false, 1
from orders o
where o.id = 'COLE_AQUI_O_ID_DO_PEDIDO'
  and o.status = 'PAID'
on conflict (task_name, task_instance) do nothing;
```
<!-- resend-email-sql:end -->

Agenda o envio para agora; o app o pega em segundos. Resultado `INSERT 0 1` é o esperado; `INSERT 0 0` quer dizer que o Pedido não existe, não está pago, ou já tem um envio na fila. Se o e-mail já tinha sido enviado, rodar de novo é seguro: a tarefa vê que ele já saiu e não envia outro.

## Conferir um pagamento

Roteiro da homologação (o sandbox é a única prova do que o Asaas de verdade manda; o CI usa um Asaas falso).

1. **[RESPONSÁVEL]** Entre na homologação como Cliente, compre um Produto com Pix e deixe a página do Pedido aberta.
2. **[RESPONSÁVEL]** No sandbox do Asaas, ache a cobrança do Pedido e clique em "Confirmar pagamento".
3. **[RESPONSÁVEL]** A página deve virar "Pagamento confirmado" em segundos, sem recarregar.
4. **[RESPONSÁVEL]** No SQL Editor, veja os eventos recebidos (o sandbox pode mandar também `PAYMENT_CONFIRMED`; anote a ordem):

   ```sql
   select provider_event_id, event_type, received_at from payment_events order by received_at desc limit 5;
   ```

   Mostra os últimos eventos gravados.

5. **[RESPONSÁVEL]** Revisão: resultados que não são o esperado (`PAID`, `ALREADY_PAID`, `NO_ORDER`):

   ```sql
   select r.processed_at, r.result, r.order_id, e.provider_event_id, e.event_type
   from payment_event_results r join payment_events e on e.id = r.payment_event_id
   where r.result not in ('PAID', 'ALREADY_PAID', 'NO_ORDER')
   order by r.processed_at desc;
   ```

   Cada linha é um caso para revisão humana (por exemplo `DUPLICATE_PURCHASE` pede devolução do dinheiro no painel do Asaas). O log traz o mesmo aviso em `WARN`: `Payment event needs review`. Adicione `'REFUNDED', 'ALREADY_REFUNDED'` à lista de exclusão para não ver os estornos que deram certo. A seção "Reembolso e MED" abaixo diz o que fazer com cada resultado de estorno.

6. **[RESPONSÁVEL]** Eventos de pagamento que ainda não têm resultado (a tarefa está repetindo, por exemplo com o Asaas fora do ar):

   ```sql
   select e.provider_event_id, e.event_type, e.received_at
   from payment_events e
   where e.event_type in ('PAYMENT_RECEIVED', 'PAYMENT_CONFIRMED', 'PAYMENT_REFUNDED',
                          'PAYMENT_PARTIALLY_REFUNDED', 'BALANCE_VALUE_BLOCKED')
     and not exists (select 1 from payment_event_results r where r.payment_event_id = e.id)
   order by e.received_at;
   ```

   Mostra os eventos ainda em processamento; se ficarem aqui por mais de 2 horas, viram `GAVE_UP` (veja "Reprocessar um evento de pagamento").

`payment_event_results` também só aceita inserção (SEC-028): a consulta de proteção da seção 3 vale para ela, trocando `payment_events` por `payment_event_results` e os nomes dos gatilhos por `payment_event_results_guard_row` e `payment_event_results_guard_truncate`.

## Se der problema

- **O log mostra `Asaas webhook refused: ip=<x> reason=IP_NOT_ALLOWED`**: o sandbox usa um IP fora da lista oficial de produção (a documentação do Asaas avisa). **[AGENTE]** acrescenta no `fly.homolog.toml`, em `[env]`, `APP_ASAAS_WEBHOOK_ALLOWED_IPS = "52.67.12.206,18.230.8.159,54.94.136.112,54.94.183.101,<x>"` (só na homologação, nunca no `prod` de verdade), faz o deploy e, no Asaas, em `Integrações > Webhooks`, reativa a fila se ela pausou. Esse valor é uma lista de IPs, não um segredo.
- **O log mostra `reason=TOKEN_INVALID`**: o token do Asaas e o do Fly diferem. Repita os passos 1 a 4 e edite o webhook no Asaas com o mesmo valor.
- **O log mostra `reason=IP_NOT_ALLOWED_AND_TOKEN_INVALID`**: os dois problemas acima ao mesmo tempo; resolva um de cada vez.
- As recusas ficam só no log (nível WARN), não na tabela de auditoria.

## Produção (quando chegar)

Outro token (nunca o da homologação), outro webhook na conta de produção do Asaas e a lista de IPs padrão (sem `APP_ASAAS_WEBHOOK_ALLOWED_IPS`).

## Rotação do token

Gere um token novo (passo 1), grave com `fly secrets set ... --stage` (passo 2), edite o webhook no Asaas com o novo e faça `fly deploy` (ou `fly secrets deploy`). Entre a edição no Asaas e o reinício, as entregas falham e são retentadas.

## O que não fazer

- Colar o token em chat, arquivo, commit, issue ou comando com o valor digitado.
- Usar uma API Key do Asaas como token.
- Desligar a conferência de IP.
