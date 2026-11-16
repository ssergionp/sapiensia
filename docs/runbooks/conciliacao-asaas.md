# Conciliação noturna com o Asaas

Como a conciliação funciona, como conferir o que ela fez e o que fazer quando ela avisa de uma divergência ou não termina. As decisões estão no `docs/adr/0002-banco-e-arquivos-no-supabase-com-backup-no-r2.md`, no `docs/adr/0003-pagamento-so-por-pix-via-asaas.md` e no `docs/adr/0005-tarefas-em-segundo-plano-com-db-scheduler.md`. Os dois alertas por e-mail dela (`RECONCILIATION_DIVERGENCE` e `RECONCILIATION_FAILED`) apontam para as seções "Divergências" e "A conciliação não terminou" deste arquivo.

Legenda: **[RESPONSÁVEL]** você faz, no SQL Editor do Supabase (SQL do PostgreSQL) ou no painel do Asaas, no navegador.

## O que ela faz e quando

- Roda **toda noite às 03:30 (horário de São Paulo)**, só em produção (`app.reconciliation.cron`; no local, na demonstração e nos testes o valor é `"-"` e ela fica desligada). Se o sistema estava parado às 03:30, a execução vencida roda quando ele volta (ADR-0005).
- Lista no Asaas as cobranças Pix criadas nos últimos **90 dias** (`app.reconciliation.window-days`, pelo calendário de São Paulo), 100 por página, **uma requisição por vez**, até **300 páginas** (`app.reconciliation.max-pages`, 30.000 cobranças), e compara com os Pedidos daqui. Também lê, uma a uma, as cobranças dos Pedidos pagos nesse período que não apareceram na listagem.
- **Ela nunca paga, reembolsa, apaga nem cancela nada sozinha.** Quando acha uma diferença que o processamento normal resolve (cobrança paga e Pedido `PENDING` ou `EXPIRED`; cobrança estornada e Pedido ainda não reembolsado), só grava um **evento marcado** em `payment_events` e agenda a mesma tarefa `process-payment-event` que o webhook usa. Essa tarefa consulta a cobrança no Asaas e passa pelas mesmas conferências de sempre (`PaymentConfirmation` e `RefundConfirmation`: id, referência externa, Pix, valor e estado). Valor diferente, compra duplicada etc. terminam nos resultados e alertas que já existem (veja "Conferir um pagamento" e "Alertas por e-mail" no `docs/runbooks/webhook-asaas.md`).
- O evento marcado tem `provider_event_id` = `reconciliation:<PAYMENT_RECEIVED ou PAYMENT_REFUNDED>:<id da cobrança>` e `payload` = `{"source":"RECONCILIATION","payment":{"id":"<id da cobrança>"}}`. O id é sempre o mesmo para a mesma cobrança: rodar a conciliação duas vezes não cria o evento duas vezes.
- O que ela **não** corrige fica na tabela `reconciliation_divergences` e vira um alerta por e-mail, **uma vez só** por tipo de divergência e cobrança. Veja a seção "Divergências".
- Não compara: cobranças com mais de 90 dias, chargeback e cobranças que este sistema não criou ou que não estão pagas e não têm Pedido (essas só entram na contagem `ignored` do log).
- Terminando bem, o log traz `Reconciliation finished: charges=... paymentFixes=... refundFixes=... divergences=... ignored=...`.

Máquina sempre ligada em produção (para a conciliação não depender de alguém acordar a máquina depois das 03:30): issue #43.

## Conferir o que ela fez

**[RESPONSÁVEL]** no SQL Editor. As três consultas só leem.

1. A próxima e a última execução (`execution_time` é a próxima; `last_success` e `last_failure` mostram as anteriores):

   ```sql
   select task_name, execution_time, last_success, last_failure, consecutive_failures
   from scheduled_tasks
   where task_name = 'asaas-reconciliation';
   ```

   Sem linha, a conciliação está desligada (cron `"-"`) ou o sistema ainda não subiu depois do deploy.

2. As correções pedidas pela conciliação (o evento marcado, o resultado do processamento e a auditoria do Pedido):

   ```sql
   select e.received_at, e.provider_event_id, e.event_type,
          r.result, r.order_id, r.processed_at,
          a.action, a.occurred_at as audit_at
   from payment_events e
   left join payment_event_results r on r.payment_event_id = e.id
   left join audit_events a
     on a.object_type = 'ORDER' and a.object_id = r.order_id
    and a.action in ('ACCESS_GRANTED', 'ACCESS_CANCELLED')
   where e.provider_event_id like 'reconciliation:%'
   order by e.received_at desc;
   ```

   `result` vazio quer dizer que a tarefa ainda está repetindo (veja "Conferir um pagamento", item 6). Resultado `PAID` com `ACCESS_GRANTED`: o Pedido foi pago e o Acesso concedido. `REFUNDED` com `ACCESS_CANCELLED`: o Pedido foi reembolsado e o Acesso cancelado. Qualquer outro resultado (`AMOUNT_MISMATCH`, `GAVE_UP`...) é um caso para revisão, como em qualquer evento de pagamento. Um Pedido com Acesso concedido e depois cancelado aparece com as duas ações, e um Pedido com mais de um evento aparece em mais de uma linha.

3. As divergências, com o id da cobrança (é o que você procura no painel do Asaas):

   ```sql
   select found_at, kind, charge_id, order_id
   from reconciliation_divergences
   order by found_at desc;
   ```

   `order_id` vem vazio em `CHARGE_WITHOUT_ORDER`: não há Pedido.

## Divergências

Alerta `RECONCILIATION_DIVERGENCE` ("divergência na conciliação com o Asaas"): a conciliação comparou os Pedidos com as cobranças e achou uma diferença que o sistema não corrige sozinho. O e-mail traz só o código e o Pedido; o **id da cobrança** (`pay_...`) está na tabela: use a consulta 3 acima e procure esse id no painel do Asaas (Cobranças). Cada divergência é avisada **uma vez só**: ela continua na tabela, mas não gera novo e-mail nas noites seguintes.

Regra antes de agir: **confira sempre a cobrança no painel do Asaas**; o que vale é o que o Asaas mostra agora. A conciliação já relê por id a cobrança de um Pedido antes de gravar a divergência, mas a listagem do Asaas pode estar atrasada.

### `CHARGE_WITHOUT_ORDER`

- **Quer dizer:** o Asaas tem uma cobrança Pix **paga**, criada por este sistema (a referência externa dela é um UUID), mas não há Pedido com esse id de cobrança no banco. Quase sempre é um Pedido perdido numa restauração de backup (ADR-0002: o que foi feito depois do último backup se perde).
- **No painel do Asaas:** abra a cobrança e anote o valor, a data do pagamento e a **referência externa** (o UUID: é o id que o Pedido tinha).
- **O que fazer:** o Cliente pagou e não tem o Acesso. A conciliação **não recria o Pedido** (criá-lo a partir da cobrança faria a cobrança conferir a si mesma, e ela não diz qual é o Produto). Recrie o Pedido à mão, como `EXPIRED`, pelo modelo abaixo (a conciliação paga Pedido `PENDING` ou `EXPIRED`; o expirado a tarefa de expiração nunca toca, então nenhum pedido de cancelamento vai ao Asaas para uma cobrança paga); a conciliação seguinte o paga e concede o Acesso pelo caminho único (`PaymentConfirmation`). Se a cobrança não for de um Cliente real (um teste, por exemplo), não faça nada.

**Recriar um Pedido perdido (correção manual e excepcional de dados).** É uma escrita direta no banco de produção: só para esta situação, uma cobrança por vez, e confira os valores duas vezes antes de rodar. Pré-requisitos: o Cliente e o Produto existem no banco. Se o Cliente também foi perdido, ele precisa se cadastrar de novo no site antes; se o Produto foi perdido, recupere-o antes. De onde vem cada valor: `COLE_O_ID_DO_PEDIDO` é a referência externa da cobrança no Asaas (tem de ser exatamente ela, senão a conferência do pagamento termina em `REFERENCE_MISMATCH`); o Cliente você acha em `customers` e o Produto em `products`; o valor em centavos é o da cobrança (a conferência o compara com o preço do Pedido; se for diferente, termina em `AMOUNT_MISMATCH`).

```sql
-- Modelo: troque os marcadores COLE_...; nunca rode com os marcadores.
insert into orders (id, customer_id, product_id, price_cents, status, provider_payment_id, created_at, expires_at, expired_at)
values (
  'COLE_O_ID_DO_PEDIDO',      -- referência externa (UUID) da cobrança no Asaas
  'COLE_O_ID_DO_CLIENTE',     -- customers.id
  'COLE_O_ID_DO_PRODUTO',     -- products.id
  COLE_O_VALOR_EM_CENTAVOS,   -- igual ao valor da cobrança (R$ 29,90 = 2990)
  'EXPIRED',
  'COLE_O_ID_DA_COBRANCA',    -- reconciliation_divergences.charge_id
  now(),                      -- created_at
  now(),                      -- expires_at
  now()                       -- expired_at
);
```

Depois, rode a conciliação na hora (veja "Rodar agora" na seção "A conciliação não terminou") e confira com a consulta 2: resultado `PAID` e `ACCESS_GRANTED`. Se o `insert` falhar por estar fora do padrão (por exemplo, id repetido), não insista: o Pedido já existe ou o id da cobrança está errado. A divergência continua na tabela como histórico; não a apague.

### `ORDER_PAID_CHARGE_NOT_PAID`

- **Quer dizer:** o Pedido está `PAID` (o Cliente tem o Acesso), mas o Asaas mostra a cobrança como não paga e também não estornada nem em estorno (por exemplo, pendente, vencida ou cancelada).
- **No painel do Asaas:** abra a cobrança, veja o estado e o histórico dela e se o dinheiro entrou na conta.
- **O que fazer:** o Acesso existe sem um pagamento confirmado agora. Descubra por quê: um evento de teste, uma cobrança desfeita no painel, um banco restaurado de outro momento. O sistema não cancela o Acesso nesse caso; se o dinheiro não entrou, a decisão de manter ou não o Acesso é sua. Os eventos do Pedido estão em "Conferir um pagamento", no `docs/runbooks/webhook-asaas.md`.

### `ORDER_PAID_CHARGE_NOT_FOUND`

- **Quer dizer:** o Pedido está `PAID`, mas o Asaas responde que a cobrança não existe (404).
- **No painel do Asaas:** procure a cobrança pelo id. Veja se ela foi excluída ou se o id é de outra conta (por exemplo, um banco da homologação ou do sandbox restaurado em produção).
- **O que fazer:** o mesmo raciocínio do código anterior: sem prova de que o dinheiro entrou, o Acesso está sem pagamento, e não há correção automática.

### `ORDER_REFUNDED_CHARGE_PAID`

- **Quer dizer:** o Pedido está `REFUNDED` (Acesso cancelado), mas o Asaas mostra a cobrança como paga (`RECEIVED` ou `CONFIRMED`). Um Pedido reembolsado nunca volta a `PAID` (ADR-0003).
- **No painel do Asaas:** veja se o estorno foi desfeito, se foi só parcial ou se o Pedido foi marcado como reembolsado por engano.
- **O que fazer:** não há correção por aqui. Se o Cliente deve ter o conteúdo, combine com ele uma nova compra; qualquer ajuste de dados do Pedido só por migration revisada. Se o dinheiro foi mesmo devolvido, não há nada a fazer.

## A conciliação não terminou

Alerta `RECONCILIATION_FAILED` ("conciliação com o Asaas não terminou"): a execução parou antes do fim. O que ela já tinha agendado continua valendo (cada correção é conferida de novo por id); o resto não foi comparado nesta noite. O motivo vem no alerta:

- `ASAAS_UNAVAILABLE`: o Asaas não respondeu direito (fora do ar, tempo esgotado, `429`, erro 5xx ou resposta de formato inesperado). Não precisa fazer nada: **a próxima noite tenta de novo sozinha** e refaz a janela inteira (a janela de 90 dias cobre a noite perdida). Se o aviso se repetir em noites seguidas, procure no log `Reconciliation incomplete: reason=ASAAS_UNAVAILABLE page=<n>` e confira a chave da API (`docs/runbooks/asaas-chave-api.md`).
- `TOO_MANY_CHARGES`: a janela tem mais cobranças que o teto (300 páginas de 100). Nada se perde em silêncio: a conciliação para e avisa. Suba o teto: em produção, defina a variável `APP_RECONCILIATION_MAX_PAGES` (o Spring a lê como `app.reconciliation.max-pages`) com um valor maior que o atual e reinicie o sistema. Cada página a mais é uma requisição a mais por noite (a cota do Asaas é de 25.000 por 12 horas).

**Rodar agora**, sem esperar a noite (por exemplo, depois de o Asaas voltar ou de subir o teto): **[RESPONSÁVEL]** no SQL Editor:

```sql
update scheduled_tasks set execution_time = now() where task_name = 'asaas-reconciliation';
```

`UPDATE 1` é o esperado; o sistema a pega em segundos. `UPDATE 0` quer dizer que a conciliação está desligada ou que o sistema ainda não registrou a tarefa. Acompanhe pela consulta 1 (`last_success`) e pelo log (`Reconciliation finished: ...`). Rodar de novo é seguro: a conciliação não duplica eventos nem divergências.

## Roteiro de sandbox: o que o Asaas de verdade faz

A documentação do Asaas não responde a estes pontos e os testes usam um Asaas simulado. **[RESPONSÁVEL]** Faça no sandbox (<https://sandbox.asaas.com>), com a conciliação rodando na homologação (ligue o `app.reconciliation.cron` só lá, ou use "Rodar agora"), e anote o resultado de cada item; ao terminar, avise o agente para ajustar este arquivo e o ADR-0003.

1. **`dateCreated[ge]`.** O sistema envia o filtro com os colchetes codificados (`dateCreated%5Bge%5D=<aaaa-mm-dd>`). Confirme que o Asaas aceita esse formato e que o filtro age de fato: compare a contagem `charges` do log com as cobranças que existem a partir da data da janela.
2. **Cobranças excluídas.** Exclua uma cobrança e veja se a listagem ainda a traz. Se não trouxer, um Pedido expirado e pago depois de a cobrança ser cancelada (risco R1 do ADR-0003) só é coberto pelo webhook, não pela conciliação.
3. **Ordem das páginas.** Com mais de 100 cobranças, veja se a ordem é estável entre as páginas. O sistema ignora a mesma cobrança vista duas vezes, mas uma cobrança que mude de posição entre duas requisições pode ser pulada nesta noite e ser pega na seguinte.
4. **`429` de verdade.** Veja se o Asaas responde `429` ao passar da cota e se a conciliação termina como `ASAAS_UNAVAILABLE` e tenta de novo na noite seguinte.
5. **Estado da cobrança depois de estorno parcial.** Estorne parte do valor e veja o estado da cobrança na listagem: se continuar `RECEIVED`, a conciliação não faz nada (o esperado); se virar `REFUNDED` antes do estorno total, o Acesso seria cancelado, e isso muda o ADR-0003.
