# Pedido pago e Acesso concedido (#28) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** APROVADO pelo Sérgio em 14/11/2026, com D1 a D12 como recomendado (D1: a #31 fica aberta) e os textos da seção 8 (ele confere no diff da PR). Quatro ajustes pedidos por ele, já incorporados abaixo:
- **A1.** A consulta ao Asaas exige status recebido ou confirmado, forma de pagamento Pix, valor igual a `price_cents`, id da cobrança igual ao `provider_payment_id` do Pedido e `externalReference` igual ao id do Pedido. Cada divergência tem o seu resultado em `payment_event_results` e o seu teste (seção 3, Task 3).
- **A2.** `payment_event_results` é só inserção, como `payment_events` (SEC-028): mesmo gatilho, RLS e privilégios da V17. O estado de repetição fica no db-scheduler. Nenhum `UPDATE` é necessário (seção 5, Task 1).
- **A3.** O runbook ganha o SQL que reagenda o processamento de um evento (por exemplo depois de `GAVE_UP`), e um teste roda esse mesmo SQL, lido do runbook, contra o banco (Task 4).
- **A4.** `just demo-pagar <id do Pedido>` envia ao sistema local o evento de pagamento com o token local, só contra `localhost`; um teste prova que o perfil `prod` recusa esse token. Documentado em `docs/demo.md` (Task 9).

**Goal:** Quando o Asaas confirma o pagamento, o Pedido vira pago e o Acesso é concedido uma única vez, depois de o sistema conferir valor, Pedido e estado consultando o Asaas. Eventos repetidos, atrasados, fora de ordem ou de cobrança sem Pedido não corrompem o estado, e a tela do Pedido passa a mostrar o pagamento confirmado sozinha.

**Architecture:** O recebedor do webhook (#26) continua gravando o evento em `payment_events` e, **na mesma transação**, agenda uma tarefa única do db-scheduler para os eventos de pagamento; responde 200 logo depois do commit. A tarefa (`PaymentConfirmation`) acha o Pedido pelo id da cobrança, consulta a cobrança no Asaas (`GET /v3/payments/{id}`), confere tudo e, numa só transação, faz o `update` condicional do Pedido para `PAID`, insere o Acesso (`unique (customer_id, product_id)`), grava a auditoria e o resultado do evento em `payment_event_results`. Falha passageira é repetida pela própria tarefa; divergência vira um resultado para revisão humana. A tela consulta uma rota leve (`GET /api/orders/{id}/status`, só banco) a cada 5 segundos.

**Tech Stack:** Spring Boot 4.1 (`JdbcClient`, `RestClient`, Jackson 3, Spring Security 7), Flyway, PostgreSQL, db-scheduler 16.12.0, JUnit 5, Testcontainers; React 19, TanStack Query, Zod, Vitest, Playwright. **Nenhuma dependência nova.**

**Spec:** `docs/spec/sapiensia-spec.md` (FR-021, FR-024, SEC-025, SEC-026, SEC-027, SEC-031); issue #28 e seus comentários (pai #5); issue #110; `docs/adr/0003-pagamento-so-por-pix-via-asaas.md`; `docs/adr/0005-tarefas-em-segundo-plano-com-db-scheduler.md`; `docs/runbooks/webhook-asaas.md`; migrations V16, V17 e V19; `GLOSSARY.md` (Pedido, Acesso); plano do #24 (`2026-11-13-24-pedido-pendente-pix.md`, seção 1 "Como o #28 encaixa").

## IDs cobertos

| ID | Como | Onde |
|---|---|---|
| FR-021 | Evento autenticado (já no #26), gravado, deduplicado e **processado** de forma idempotente: o efeito (Pedido pago + Acesso) acontece uma vez | Tasks 1, 3, 4 |
| SEC-025 | Dedup por `provider_event_id` (já existe) mais idempotência do efeito: `update` condicional, `unique` no Acesso e `payment_event_results` com o evento como chave | Tasks 1, 3, 5 |
| SEC-026 | Antes de pagar: Pedido existe, a cobrança é a do Pedido (`id` e `externalReference`), valor igual a `price_cents`, é Pix, e o estado no Asaas é pago. Tudo lido do Asaas com a nossa chave (recebedor), não do corpo do webhook | Tasks 2, 3 |
| SEC-027 | Nenhuma rota do Cliente altera o Pedido; parâmetro na URL não muda nada; só a tarefa do evento confirmado escreve `PAID` | Tasks 6, 8 |
| SEC-031 | Testes de duplicado, simultâneo, fora de ordem, atrasado, replay e Asaas indisponível | Tasks 3, 4, 5 |
| FR-024 | Acesso concedido só depois da confirmação válida, junto com o Pedido pago, e mostrado ao Cliente | Tasks 1, 3, 8, 9 |

## 0. O que a documentação oficial do Asaas diz (consulta em 14/11/2026)

Fontes: [webhook para cobranças](https://docs.asaas.com/docs/webhook-para-cobrancas), [sobre os webhooks](https://docs.asaas.com/docs/sobre-os-webhooks), [recuperar uma única cobrança](https://docs.asaas.com/reference/recuperar-uma-unica-cobranca).

### 0.1 Confirmado

- **Entrega "at least once":** o mesmo evento pode chegar mais de uma vez; a recomendação é persistir o `id`, responder `200` e "executar processamentos demorados de forma assíncrona". 15 falhas seguidas podem pausar a fila; o Asaas guarda os eventos por 14 dias.
- **Eventos de pagamento:** `PAYMENT_CONFIRMED` ("Pagamento efetuado, mas com saldo ainda não disponibilizado") e `PAYMENT_RECEIVED` ("Cobrança recebida, com valor disponível"). No diagrama do Pix aparece só `PAYMENT_CREATED` → `PAYMENT_RECEIVED` (com `PAYMENT_OVERDUE` no meio se venceu antes).
- **Corpo do evento:** `id`, `event`, `dateCreated`, `payment` com `id`, `customer`, `value`, `netValue`, `originalValue`, `billingType`, `status`, `externalReference`, `deleted`, entre outros.
- **Consulta:** `GET /v3/payments/{id}` devolve `id`, `status` (`PENDING`, `RECEIVED`, `CONFIRMED`, `OVERDUE`, `REFUNDED`, `RECEIVED_IN_CASH`, `REFUND_REQUESTED`, `REFUND_IN_PROGRESS`, `CHARGEBACK_REQUESTED`, `CHARGEBACK_DISPUTE`, `AWAITING_CHARGEBACK_REVERSAL`, `DUNNING_REQUESTED`, `DUNNING_RECEIVED`, `AWAITING_RISK_ANALYSIS`), `value`, `billingType`, `externalReference`, `deleted`. `404` quando o id não existe.
- **Recebimento em dinheiro:** marcado no painel do Asaas, chega com `billingType = RECEIVED_IN_CASH`.

### 0.2 Não confirmado (o plano não supõe; a implementação é defensiva)

| O quê | Como o plano lida |
|---|---|
| Se o Pix manda também `PAYMENT_CONFIRMED` | Os dois tipos disparam o mesmo processamento; o segundo encontra o Pedido já pago e não faz nada |
| Ordem de entrega entre eventos | Nada depende da ordem: o estado vem da consulta ao Asaas na hora de processar |
| Se a consulta já mostra `RECEIVED` no instante em que o webhook sai | `PENDING` ou `OVERDUE` na consulta conta como "ainda não": a tarefa repete depois, em vez de recusar para sempre |
| O que `value` traz quando o pagador paga valor diferente | Qualquer `value` diferente de `price_cents` não concede Acesso e vai para revisão |
| Tempo limite do Asaas para a resposta `200` | A resposta só depende de um `insert` e de agendar a tarefa; nenhuma chamada externa na requisição |
| Se uma cobrança excluída (Pedido expirado) ainda pode ser paga, e o que a consulta devolve (`404` ou `deleted: true`) | `404` vira revisão humana; estado pago com `deleted: true` concede o Acesso (o dinheiro entrou) e registra WARN. Confere-se no sandbox (seção 11) |
| Se o Asaas aceita `DELETE` de uma cobrança já recebida | Ver risco R1 na seção 4 |

## Global Constraints

- Todo endpoint começa com `/api`. Rota nova: `GET /api/orders/{id}/status`, liberada na `SecurityConfig` com `hasRole("CUSTOMER")` antes do `denyAll`. Nenhum `permitAll`. O webhook continua como está (token + IP, ADR-0003).
- Código, commits, nomes e comentários em inglês. Textos de tela em pt-BR, **só os da seção 8**; texto fora dela volta ao Sérgio antes do push.
- Nenhuma dependência nova.
- Dinheiro em centavos (`int`). A comparação com o `value` do Asaas é `value.compareTo(BigDecimal.valueOf(priceCents, 2)) == 0`.
- **Nunca** em log, auditoria, exceção, `toString` ou tabela nova: CPF, QR Code, código copia e cola, chave de API, token do webhook, corpo do evento ou da resposta do Asaas, e-mail. Em log só: id do evento (`evt_...`), id interno do evento, id do Pedido, id da cobrança, resultado (enum), etapa, status HTTP e nome da classe da exceção. O valor recebido **não** vai para o log.
- `payment_events` continua só com inserções (V17): nenhum `update` nela. O estado do processamento mora em `payment_event_results`.
- O corpo do evento não é carregado inteiro pelo processamento: só `payload->'payment'->>'id'`, por SQL, validado contra `[A-Za-z0-9_]{1,64}`.
- Migration `V20__paid_orders_and_accesses.sql` criada pela skill `nova-migration` (a última é a V19). Nenhuma migration existente é editada.
- Controller e repositórios package-private, DTOs como `record`, exceção de negócio convertida no `ApiExceptionHandler`. Teste que prova requisito leva o ID no `@DisplayName` (backend) ou no título (frontend e e2e). Sem `@MockitoBean` em teste de integração; `@Import(TestcontainersConfiguration.class)`.
- Toda chamada do frontend passa por `apiFetch`.
- Propriedade `app.*` nova entra também em `backend/src/test/resources/application.properties`.
- Um comando pesado por vez (Maven, `just check-rapido`, e2e, `just imagem`); antes de cada um, conferir 2 GB de memória livres. Durante o trabalho só `just check-rapido`; o completo roda no CI.
- Teste focado (`-Dtest=...`): incluir as classes que já citam os IDs tocados (`git grep -l -E "SEC-025|SEC-026|SEC-027|SEC-031|FR-021|FR-024" -- backend/src/test`: hoje `AsaasWebhookAuthenticationTest`, `AsaasWebhookPersistenceTest`, `PaymentEventsTest`) e as que conferem log (`AsaasWebhookLogTest`, `OrderLogTest`).
- Valor falso que imita segredo é montado em tempo de execução nos testes (gitleaks).
- Push só com `git push -u origin feat/28-pedido-pago-acesso`, depois do OK do Sérgio.

## Review Focus

1. **O Cliente paga nos últimos segundos e a tela já mostrou "Este Pix expirou":** o Pedido vira pago mesmo expirado e a tela troca sozinha para "Pagamento confirmado", sem ele recarregar. Tasks 3 e 8.
2. **Asaas fora do ar ou lento na hora da consulta:** o webhook já respondeu 200, nenhum Acesso é concedido sem a consulta, a tarefa repete e o Pedido vira pago quando o Asaas volta. Task 4.
3. **O mesmo pagamento chega várias vezes e ao mesmo tempo** (mesmo `id`, ids diferentes, `RECEIVED` e `CONFIRMED` juntos, reexecução da tarefa depois de uma queda): um Pedido pago, um Acesso, uma linha de auditoria. Task 5.
4. **Evento que não bate** (cobrança sem Pedido, valor diferente, `externalReference` de outro Pedido, cobrança que não existe no Asaas, corpo sem `payment.id` ou com id malformado): nunca 500, nunca Acesso, um resultado gravado para revisão. Task 3.
5. **Cliente que já tem o Acesso e paga de novo** (Pedido antigo expirado pago depois de um Pedido novo também pago): os dois Pedidos ficam pagos, um só Acesso, e o segundo fica marcado para Reembolso. Tasks 3 e 6.

---

## 1. Do evento gravado ao processamento (decisão 1)

**Decisão: assíncrono, por tarefa única do db-scheduler agendada na mesma transação do `insert` do evento.**

| Passo | O que acontece |
|---|---|
| Requisição | `AsaasWebhookService.receive` abre uma transação: `insert ... on conflict do nothing returning id`. Se gravou **e** o tipo é `PAYMENT_RECEIVED` ou `PAYMENT_CONFIRMED`, agenda `process-payment-event` com o id interno do evento (UUID) como id da instância e **sem dado** (`task_data` nulo), para que o runbook possa reagendar com um `insert` simples (A3). Commit. |
| Resposta ao Asaas | `200` sem corpo, sempre que o evento foi gravado, é repetido ou é de tipo que não tratamos. Nada do processamento acontece na requisição, então a resposta leva o tempo de um `insert`. `500` só se o próprio `insert` falhar: aí o evento não foi gravado e a reentrega do Asaas é o comportamento certo (ADR-0003, sem mudança). |
| Processamento | A tarefa roda em segundo plano (sondagem a cada 10 s em produção, 1 s no local e no CI), chama `PaymentConfirmation.process(eventId)` e grava o resultado. |
| Falha passageira | Asaas indisponível, banco fora, exceção inesperada: a tarefa lança, o db-scheduler repete a cada 1 minuto, até 120 vezes (2 horas). O evento nunca se perde: está em `payment_events` e a tarefa está em `scheduled_tasks`, que sobrevive a reinício (ADR-0005). |
| Falha definitiva | Esgotadas as tentativas, grava o resultado `GAVE_UP`, registra `ERROR` no log (só ids) e remove a tarefa. O Administrador pode reagendar com o SQL do runbook (A3); a conciliação noturna (#39) é a rede de baixo. O alerta por e-mail é obrigatório antes do lançamento (registrado pelo Sérgio no #38 e no #47). |

Por quê:
- O Asaas recomenda responder logo e processar depois; a consulta à cobrança pode levar até 13 s (3 s + 10 s) e, com envio `SEQUENTIALLY`, uma resposta lenta atrasa a fila toda.
- O starter do db-scheduler envolve o `DataSource` num `TransactionAwareDataSourceProxy` (conferido no jar `db-scheduler-spring-common-16.12.0`, classe `DbSchedulerConfigurationSupport`), então o `schedule` entra na transação do Spring: não existe evento gravado sem tarefa, nem tarefa sem evento. A Task 4 prova isso com teste.
- Processar na própria requisição e engolir a falha exigiria uma varredura própria para retomar; a tarefa única já traz repetição e sobrevivência a reinício com cinco linhas.

**Sobre a issue #31 (retentativa de webhooks):** este desenho já entrega os critérios dela (reprocessa até dar certo ou esgotar, não duplica Acesso, falha definitiva registrada). O ADR-0005 dizia que a retentativa viria do Spring Modulith, que não está no projeto; o plano atualiza o ADR. Ver decisão D1.

**Resultados** (`payment_event_results.result`, um por evento de pagamento processado):

| Resultado | Quando | Acesso | Revisão humana |
|---|---|---|---|
| `PAID` | Tudo conferido; Pedido virou pago | Concedido | Não |
| `ALREADY_PAID` | O Pedido já estava pago (segundo evento do mesmo pagamento) | Já existia | Não |
| `DUPLICATE_PURCHASE` | Tudo conferido e o Pedido virou pago, mas o Cliente já tinha o Acesso por outro Pedido | Já existia | **Sim** (devolver o dinheiro) |
| `NO_ORDER` | Nenhum Pedido com essa cobrança (cobrança órfã ou de outra origem) | Não | Não |
| `INVALID_PAYLOAD` | Evento sem `payment.id` ou com id fora de `[A-Za-z0-9_]{1,64}` | Não | Sim |
| `CHARGE_NOT_FOUND` | O Asaas respondeu que a cobrança não existe ou recusou a consulta (4xx) | Não | Sim |
| `CHARGE_ID_MISMATCH` | O `id` da cobrança devolvida pelo Asaas não é o `provider_payment_id` do Pedido | Não | Sim |
| `REFERENCE_MISMATCH` | O `externalReference` da cobrança não é o id do Pedido (diferente ou ausente) | Não | Sim |
| `AMOUNT_MISMATCH` | `value` diferente de `price_cents` | Não | Sim |
| `NOT_PIX` | `billingType` diferente de `PIX` (por exemplo `RECEIVED_IN_CASH`) | Não | Sim |
| `NOT_PAID` | Estado no Asaas não é pago nem "ainda não" (por exemplo `REFUNDED`) | Não | Sim |
| `GAVE_UP` | 120 tentativas sem conseguir processar. Não é final: um reprocessamento (A3) pode gravar depois um dos outros resultados | Não | Sim |

"Revisão humana" neste ticket é: a linha em `payment_event_results`, uma linha `WARN` no log (`Payment event needs review: eventId=... result=... orderId=...`) e uma consulta pronta no runbook. Tela do Administrador (#37) e alerta por e-mail (#38) ficam nos tickets deles. Ver decisão D5.

## 2. Quais eventos viram pago (decisão 2)

| Evento | O que fazemos |
|---|---|
| `PAYMENT_RECEIVED`, `PAYMENT_CONFIRMED` | Gravado e processado (seção 3). O tipo só dispara; quem decide é a consulta ao Asaas |
| `PAYMENT_CREATED`, `PAYMENT_UPDATED`, `PAYMENT_OVERDUE`, `PAYMENT_DELETED`, `PAYMENT_RESTORED` | Gravado e ignorado: nenhuma tarefa, nenhum resultado. `PAYMENT_OVERDUE` e `PAYMENT_DELETED` não expiram o Pedido (quem expira é a nossa regra de 30 minutos) |
| `PAYMENT_REFUNDED`, `PAYMENT_REFUND_IN_PROGRESS`, `PAYMENT_PARTIALLY_REFUNDED`, `PAYMENT_CHARGEBACK_*`, `BALANCE_VALUE_BLOCKED`, `BALANCE_VALUE_UNBLOCKED` | Gravado e ignorado **neste ticket**: cancelar o Acesso é o #36 |
| Qualquer outro tipo | Gravado e ignorado (como hoje) |

| Situação | Comportamento |
|---|---|
| Repetido (mesmo `id`) | O `unique` de `payment_events` descarta; nenhuma segunda tarefa |
| Dois eventos do mesmo pagamento (`RECEIVED` e `CONFIRMED`) | O primeiro paga; o segundo dá `ALREADY_PAID` |
| Atrasado (dias depois, Pedido expirado) | A consulta confirma; `EXPIRED` vira `PAID` |
| Fora de ordem: estorno chegou antes do pagamento | A consulta mostra `REFUNDED`: `NOT_PAID`, sem Acesso |
| Fora de ordem: `PAYMENT_DELETED` (nosso cancelamento) e depois `PAYMENT_RECEIVED` | A consulta decide: pago concede; `404` vira `CHARGE_NOT_FOUND` |
| Cobrança sem Pedido | `NO_ORDER`, log em INFO, sem consultar o Asaas, sem erro |
| Replay de um evento antigo com `id` novo forjado | Precisa do token e do IP (#26); mesmo assim a consulta ao Asaas diz o estado real, e um Pedido já pago dá `ALREADY_PAID` |

## 3. Conferência antes de pagar (decisão 3)

**Decisão: consultar o Asaas sempre** que um evento de pagamento encontra um Pedido ainda não pago. O corpo do webhook serve só para achar a cobrança (`payment.id`); estado, valor e referência vêm do `GET /v3/payments/{id}`, feito com a nossa chave.

Por quê: o Asaas não assina o corpo (ADR-0003); a única prova de origem é um token fixo mais o IP. Com a consulta, um token vazado não basta para liberar um Produto, e eventos atrasados ou fora de ordem se resolvem pelo estado atual. Custo: uma chamada por Pedido pago (o segundo evento do mesmo pagamento não consulta).

Ordem em `PaymentConfirmation.process(eventId)`, fora de transação até o passo 6:

1. Já existe resultado **final** (qualquer um menos `GAVE_UP`) para o evento? Devolve esse e sai (a tarefa pode rodar duas vezes; um `GAVE_UP` não impede o reprocessamento).
2. Lê `payload->'payment'->>'id'`. Ausente ou malformado: `INVALID_PAYLOAD`.
3. Acha o Pedido por `provider_payment_id`. Não existe: `NO_ORDER`.
4. Pedido já `PAID`: `ALREADY_PAID`.
5. `gateway.readCharge(chargeId)` e as cinco exigências (A1), nesta ordem; a primeira que falhar decide:
   - `UNAVAILABLE` (timeout, 5xx, 429, 401): lança; a tarefa repete. **Nenhum Acesso sem consulta.**
   - `REFUSED` (404 e outros 4xx): `CHARGE_NOT_FOUND`.
   - `charge.id` diferente de `orders.provider_payment_id`: `CHARGE_ID_MISMATCH`.
   - `charge.externalReference` diferente de `orders.id` (ou ausente): `REFERENCE_MISMATCH`.
   - `billingType` diferente de `PIX`: `NOT_PIX`.
   - `value` diferente de `price_cents`: `AMOUNT_MISMATCH`.
   - `status` `PENDING` ou `OVERDUE`: lança (ainda não refletiu; repete; se nunca refletir, termina em `GAVE_UP`). Outro estado que não `RECEIVED` nem `CONFIRMED`: `NOT_PAID`.
6. Uma transação: `markPaid` (`where status in ('PENDING','EXPIRED')`); se mudou a linha, `accesses.grant` (`on conflict do nothing`), auditoria `ACCESS_GRANTED` quando o Acesso nasceu, e o resultado `PAID` ou `DUPLICATE_PURCHASE`; se não mudou (outra execução chegou antes), resultado `ALREADY_PAID`.

SEC-026 item a item: **valor** (`AMOUNT_MISMATCH`), **moeda** (o Asaas só opera em reais; a conferência do valor cobre), **pedido** (`CHARGE_ID_MISMATCH`, `REFERENCE_MISMATCH`, `NO_ORDER`), **recebedor** (a consulta usa a chave da nossa conta: uma cobrança de outra conta dá 404), **estado** (`NOT_PAID` e o `update` condicional).

## 4. Pedido que expirou e foi pago logo depois (decisão 4)

- **Transição:** `update orders set status = 'PAID', paid_at = :now where id = :id and status in ('PENDING', 'EXPIRED')`. `expired_at` fica como está (história). A tarefa de expiração só toca `PENDING`, então nunca desfaz um `PAID`.
- **`OrderService.get`:** `PAID` é devolvido como `PAID` (sem chamar o Asaas, sem código Pix); `EXPIRED`, ou `PENDING` além do prazo, continua `EXPIRED`. O comentário que apontava para o #28 sai.
- **O que o Cliente vê:** a tela "Este Pix expirou" ganha um aviso para quem já pagou e continua consultando o estado por mais 10 minutos; se o pagamento for confirmado, ela troca para "Pagamento confirmado" (seção 8).
- **Pagar depois de cancelada:** ao expirar, o #24 exclui a cobrança no Asaas. Se o pagamento entrou antes, o Asaas recusa a exclusão, o Pedido fica `EXPIRED` e o webhook o leva a `PAID`. Se a exclusão deu certo, o QR Code deixa de valer (não confirmado na documentação; seção 0.2).
- **Risco R1 (não resolvido aqui):** a tarefa de expiração lê a lista de Pedidos vencidos e depois exclui a cobrança de cada um. Se um Pedido for pago entre a leitura e a exclusão, a tarefa pede ao Asaas para excluir uma cobrança já recebida. O `markExpired` não desfaz o `PAID`, mas não sabemos se o Asaas aceita essa exclusão. A Task 5 fecha a janela do nosso lado (a tarefa confere de novo o estado antes de excluir); o comportamento do Asaas confere-se no sandbox.

## 5. O Acesso (decisão 5)

```sql
-- Paid Orders (ADR-0003: an expired Order paid later becomes paid).
alter table orders drop constraint orders_status_check;
alter table orders add constraint orders_status_check check (status in ('PENDING', 'EXPIRED', 'PAID'));
alter table orders add column paid_at timestamp with time zone;
alter table orders add constraint orders_paid_at_check check ((status = 'PAID') = (paid_at is not null));

-- The right of a Customer to download a Product (FR-024). One per Customer and Product; order_id is the Order that
-- granted it. Revocation (refund, fraud) is #36.
create table accesses (
  id uuid primary key default gen_random_uuid(),
  customer_id uuid not null references customers (id),
  product_id uuid not null references products (id),
  order_id uuid not null references orders (id),
  granted_at timestamp with time zone not null,
  constraint accesses_customer_product_key unique (customer_id, product_id),
  constraint accesses_order_id_key unique (order_id)
);

-- What processing a payment event ended with (SEC-028: append-only, like payment_events). At most one final result
-- per event; GAVE_UP is not final, so an event that was given up can be reprocessed and get its final result later.
create table payment_event_results (
  id uuid primary key default gen_random_uuid(),
  payment_event_id uuid not null references payment_events (id),
  result varchar(32) not null check (result ~ '^[A-Z_]+$'),
  order_id uuid references orders (id),
  processed_at timestamp with time zone not null
);
create unique index payment_event_results_one_final on payment_event_results (payment_event_id)
  where result <> 'GAVE_UP';
create index payment_event_results_order_id on payment_event_results (order_id);

create trigger payment_event_results_guard_row before update or delete on payment_event_results
  for each row execute function financial_record_guard();
create trigger payment_event_results_guard_truncate before truncate on payment_event_results
  for each statement execute function financial_record_guard();
alter table payment_event_results enable row level security;
revoke all on payment_event_results from public;
-- plus the same "revoke all from anon, authenticated, service_role when the role exists" block as V17
```

(O nome real do `check` de `orders.status` é conferido no banco antes de escrever o `drop`; se for outro, usa-se o nome real.)

**Por que `payment_event_results` não precisa de `UPDATE` (A2):** cada processamento só **insere** o seu resultado. A tarefa que repete não grava nada enquanto não termina (o contador de tentativas é do db-scheduler, em `scheduled_tasks`). `GAVE_UP` é uma linha a mais, não uma troca: se o evento for reprocessado e pago, a linha `PAID` entra ao lado da `GAVE_UP`, e o resultado atual é o final, quando existe, ou o último `GAVE_UP`. O índice único parcial garante um só resultado final por evento. `accesses` e `orders` não são registro financeiro e não ganham o gatilho (o #36 vai precisar mudar os dois).

- **Uma transação** para Pedido pago, Acesso, auditoria e resultado: ou tudo, ou nada.
- **Repetir o aviso** não gera segundo Acesso: o `update` condicional só deixa uma execução passar, e o `unique (customer_id, product_id)` é a segunda barreira.
- **Cliente que já tem o Acesso e compra de novo:**
  - `POST /api/orders` passa a recusar com `409` e `{"errors": {"order": "ALREADY_OWNED"}}` quando o Cliente já tem o Acesso ao Produto (conferido dentro da trava por Cliente, antes de reaproveitar Pedido pendente). Nenhuma cobrança é criada.
  - Ainda pode acontecer de dois Pedidos do mesmo Produto serem pagos (o antigo expirado, pago com atraso, e um novo). Os dois ficam `PAID` (o dinheiro entrou nos dois), o Acesso é um só e o segundo evento fica `DUPLICATE_PURCHASE`, para o Administrador devolver o valor no painel do Asaas.
  - Um Pedido novo criado no instante em que o antigo está sendo pago escapa da conferência do `409`; cai no mesmo caso acima se for pago.
- `on delete`: nenhuma cascata. Anonimização de conta é o #41.

## 6. Concorrência (decisão 6)

| Corrida | Quem resolve | Prova (Task 5) |
|---|---|---|
| Dois avisos com o mesmo `id` ao mesmo tempo | `unique` de `payment_events`: um grava e agenda, o outro é repetido | 8 requisições simultâneas, 20 rodadas: 1 evento, 1 tarefa, 1 Acesso |
| Dois eventos diferentes do mesmo pagamento processados juntos | `update ... where status in (...)` devolve 1 para um só; o outro grava `ALREADY_PAID` | 2 threads chamando `process`, 20 rodadas: 1 Pedido pago, 1 Acesso, 1 auditoria |
| A mesma tarefa executada duas vezes (queda no meio) | Índice único parcial do resultado final e `on conflict do nothing` | `process` duas vezes seguidas: nada muda na segunda |
| Pagamento e expiração juntos | `markExpired` só toca `PENDING`; `markPaid` aceita `PENDING` e `EXPIRED` | Tarefa de expiração e `process` em paralelo, 20 rodadas: sempre termina `PAID` com Acesso |
| Webhook e consulta do Cliente (`GET` do Pedido e do estado) juntos | Leituras sem trava; a escrita é uma transação curta | Leitores em laço durante o `process`: só veem `PENDING` e depois `PAID`, nunca erro nem volta |
| Dois Pedidos do mesmo Produto pagos juntos | `unique` do Acesso com `on conflict do nothing` | 2 threads: 2 Pedidos pagos, 1 Acesso, 1 `DUPLICATE_PURCHASE` |

## 7. Voltar do navegador nunca paga (decisão 7)

- Só existem três rotas de Pedido para o Cliente: `POST /api/orders` (cria ou reaproveita, sempre `PENDING`), `GET /api/orders/{id}` e `GET /api/orders/{id}/status`. Nenhuma escreve `PAID`. O único código que escreve `PAID` é `Orders.markPaid`, chamado só por `PaymentConfirmation`, que só roda pela tarefa do evento.
- Não há página de retorno do Asaas (o Pix é mostrado na nossa tela), e a tela ignora qualquer parâmetro da URL.
- Testes (Task 6 e 8): `POST`, `PUT`, `PATCH` e `DELETE` em `/api/orders/{id}` e em `/api/orders/{id}/status`, com e sem corpo `{"status":"PAID"}`, não mudam o Pedido; `GET` repetido não muda; o webhook chamado por um Cliente logado, sem o token, recebe 401; a tela aberta com `?status=PAID&paid=true` continua mostrando "Pague com Pix".

## 8. Tela e textos (decisão 8)

**Como a página descobre que foi pago.** Rota nova `GET /api/orders/{id}/status`, que devolve só `{"status": "PENDING" | "EXPIRED" | "PAID"}` lendo o banco: **zero chamadas ao Asaas** (o `GET /api/orders/{id}` de um Pedido pendente chama o Asaas para ler o código, e é isso que a #110 e o #42 apontam como custo). A tela consulta essa rota a cada 5 segundos enquanto a aba está visível:
- Pedido pendente: até o fim do prazo (no máximo 360 consultas em 30 minutos).
- Pedido expirado: por mais 10 minutos (120 consultas) e para; recarregar a página consulta de novo.
- Aba em segundo plano: não consulta; ao voltar, consulta na hora.
- Quando o estado muda, a tela busca o Pedido completo uma vez e troca de vista. Pedido pago não consulta mais nada.
- Sem limite de taxa no servidor até o #42 (decisão D6 do #24, mantida): cada consulta é uma leitura por chave primária.

**Tela B: Pedido** (`/pedidos/:id`), textos novos

| Onde | Texto |
|---|---|
| Pendente: frase depois do pagamento (substitui "O Acesso ao Produto é liberado depois que o pagamento for confirmado.") | `Depois de pagar, aguarde nesta página: ela se atualiza sozinha quando o pagamento for confirmado e o Acesso ao Produto for liberado.` |
| Expirado: aviso novo, abaixo do texto atual | `Se você já pagou, aguarde alguns minutos: esta página se atualiza sozinha quando o pagamento for confirmado.` |
| Pago: título da aba | `Pagamento confirmado \| SapiensIA` |
| Pago: título | `Pagamento confirmado` |
| Pago: texto | `Recebemos o seu Pix. Você já tem Acesso a este Produto.` |
| Pago: termos da lista | `Produto` e `Valor` (já existem) |
| Pago: link | `Voltar ao Catálogo` (já existe) |

A vista de pago **não** fala de download nem de Biblioteca: essas telas são dos tickets #32 e #33 (decisão D3).

**Tela A: página do Produto** (`/catalogo/:id`), texto novo

| Onde | Texto |
|---|---|
| Erro: o Cliente já tem o Acesso (`409 ALREADY_OWNED`) | `Você já tem Acesso a este Produto.` |

**Celular e acessibilidade**
- A vista de pago usa o mesmo molde das outras (`main` com `max-w-md`, lista `dl`), sem rolagem horizontal em 390 px; título longo do Produto quebra (`overflow-wrap: anywhere`).
- Ao virar "Pagamento confirmado", o foco vai para o título (`tabIndex={-1}`, como nas outras vistas), e é isso que o leitor de tela anuncia; nenhuma região `aria-live` nova. A consulta periódica não mexe no foco nem anuncia nada enquanto o estado não muda.
- A contagem e os avisos de 5 e 1 minuto continuam como estão.
- Skill `verificar-tela` nas três vistas (pendente, expirado, pago) e no erro da tela A: 1280 px e 390 px, com teclado.

## 9. Auditoria e log (decisão 9)

- **Auditoria:** `AuditAction.ACCESS_GRANTED`, `AuditResult.SUCCESS`, `AuditObject.ORDER`, `objectId` = id do Pedido, `actorId` nulo (quem age é o sistema, a partir do evento do Asaas) e origem nula. Gravada com `recordInTransaction`: sem trilha, não há Acesso. Só a concessão é auditada; recusas ficam em `payment_event_results`.
- **Log:** `INFO` `Payment event processed: eventId={} result={} orderId={} chargeId={}`; `WARN` `Payment event needs review: ...` com os mesmos campos; `ERROR` ao desistir. Nunca o corpo, o valor, o e-mail, o CPF, o código Pix nem o token.
- `PaymentLogTest` (Task 7) roda o fluxo inteiro em `TRACE` com marcadores no corpo do evento e na resposta da consulta e confere que nenhum aparece.

## 10. Testes (decisão 10)

**Backend (Testcontainers; `PixGateway` é o `SwitchablePixGateway`)**

| Classe | O que prova |
|---|---|
| `PaidOrdersSchemaTest` | Migration: `PAID` exige `paid_at`; `unique` do Acesso; `payment_event_results` aceita um resultado por evento |
| `AsaasPixGatewayWireTest` (alterado) | `SEC-026`: `GET /v3/payments/{id}` com `access_token`; lê `id`, `status`, `value`, `billingType`, `externalReference`, `deleted`; 404 vira `REFUSED`; timeout, 500, 401, JSON quebrado e campo faltando viram `UNAVAILABLE`; nada do corpo vaza |
| `PaymentConfirmationTest` | `FR-024`, `SEC-026`: cada linha da tabela de resultados da seção 1, e que só `PAID` cria Acesso e auditoria |
| `PaymentEventProcessingTest` | `FR-021`, `SEC-031`: webhook de verdade (token e IP) até o Pedido pago; evento e tarefa na mesma transação; Asaas indisponível, depois disponível; desistência grava `GAVE_UP`; tipos ignorados não agendam nada; resposta 200 sem depender do Asaas |
| `PaymentConcurrencyTest` | `SEC-025`, `SEC-031`: a tabela da seção 6 |
| `PaidOrderReadTest` | `FR-024`: `GET` do Pedido pago sem chamar o Asaas; rota do estado: 401 anônimo, 403 Administrador, 404 para outro Cliente (`SEC-011`), 200 para o dono; `SEC-027`: nenhuma rota do Cliente muda o estado; `409 ALREADY_OWNED` sem criar cobrança |
| `PaymentLogTest` | `ADR-0006`: nada sensível no log |
| `OrderExpirationTest` (alterado) | A tarefa de expiração não exclui a cobrança de um Pedido que acabou de ser pago (R1) |

**Frontend (Vitest):** `OrderPage.test.tsx` (vista de pago; troca de pendente para pago e de expirado para pago com relógio falso; `SEC-027` com parâmetros na URL; consulta para depois do limite), `BuyWithPix.test.tsx` (`ALREADY_OWNED`), `schemas`/`api` (estado `PAID`, rota do estado).

**E2E** (`frontend/e2e/admin-panel.e2e.ts`, um teste novo; API e banco de verdade, sem `page.route` nem mock de rede no navegador)
- Título: `FR-021 FR-024 SEC-025 SEC-026 SEC-027 SEC-031: o pagamento confirmado pelo Asaas libera o Acesso`.
- Roteiro: o Administrador publica um Produto; o Cliente gera o Pedido; recarregar e abrir com `?status=PAID` continua mostrando "Pague com Pix"; o teste envia `PAYMENT_RECEIVED` ao `POST /api/webhooks/asaas` real, com o token e o IP aceitos, **fora do navegador** (`request` do Playwright); a mesma página, sem recarregar, passa a mostrar "Pagamento confirmado"; o evento é enviado de novo (mesmo `id`) e outra vez com `id` novo: tudo continua igual; comprar o mesmo Produto de novo mostra "Você já tem Acesso a este Produto."; outro Cliente não vê o Pedido. Repete a vista de pago num contexto `Pixel 7`.
- **O Asaas é falso na borda do servidor, e isso é deliberado:** no computador é o `FakePixGateway`; no CI é o `AsaasPixGateway` de verdade contra `.github/asaas-stub.mjs`, que ganha o `GET /v3/payments/{id}`. Os dois respondem que a cobrança que eles criaram está recebida, com o valor e a referência com que foi criada (não há dinheiro para decidir outra coisa). As respostas divergentes são provadas nos testes de integração.
- O id da cobrança, que o evento precisa, é lido do código Pix fictício mostrado na tela (`PIX-FICTICIO-NAO-PAGAVEL-<id>` no falso, `PIX-STUB-<id>` no stub).
- Token: `E2E_ASAAS_WEBHOOK_TOKEN` (o CI passa o token que gerou; sem a variável, o teste monta em tempo de execução o valor fictício do perfil local). IP: o teste manda `Fly-Client-IP` com um IP oficial do Asaas, que o perfil `prod` do CI aceita; no local o cabeçalho é ignorado e vale o `127.0.0.1` da conexão.

## 11. O que não será provado e por quê (decisão 11)

| O quê | Por quê | Quando se prova |
|---|---|---|
| Pagamento real por Pix | Só existe em produção, com dinheiro | Primeira venda em produção |
| O sandbox manda `PAYMENT_RECEIVED` com o corpo que esperamos e a consulta devolve os campos como na documentação | Depende da chave e do webhook do sandbox, que não existem no CI | Homologação: gerar um Pedido, clicar "Confirmar pagamento" no sandbox e ver a tela virar "Pagamento confirmado" (roteiro no runbook) |
| Se o Pix manda também `PAYMENT_CONFIRMED`, e em que ordem | Documentação não diz | Homologação (olhar `payment_events`) |
| Se a consulta já mostra `RECEIVED` quando o webhook chega | Só o Asaas de verdade responde | Homologação (se aparecer repetição no log, é esse caso) |
| Pagar um Pedido expirado cuja cobrança foi excluída, e o `DELETE` de cobrança já recebida (R1) | O sandbox pode não deixar simular | Homologação, se o sandbox permitir; senão fica como risco registrado na issue de pendências |
| Valor pago diferente do cobrado | O Pix com valor fixo não deixa alterar; não há como simular | Coberto por teste com o falso; nunca observado de verdade |
| Fila do Asaas pausada e reentrega depois de horas | Exige derrubar a homologação | Teste de integração cobre o evento atrasado; a pausa é do runbook |
| Carga (muitos pagamentos juntos) | Volume da Fase 1 é baixo | #44 |
| Leitor de tela de verdade | O `verificar-tela` confere papéis, nomes e teclado, não a fala | Conferência manual do Sérgio, se quiser |
| E2E com resposta "não pago" do Asaas | O falso e o stub sempre respondem recebido | Testes de integração (`PaymentConfirmationTest`) |

## 12. Decisões que dependem do Sérgio

| # | Decisão | Recomendação |
|---|---|---|
| D1 | A repetição da tarefa (1 em 1 minuto, até 2 horas, depois `GAVE_UP`) entra neste ticket e cobre os critérios da #31. Opções: (a) a PR também fecha a #31; (b) a #31 fica aberta só para ligar a falha definitiva ao alerta (#38) | **(b)**: o alerta ainda não existe |
| D2 | Todos os textos da seção 8 | Aprovar ou corrigir |
| D3 | A tela de pago não fala de download nem de Biblioteca (#32 e #33 ainda não existem). Este ticket sozinho não deve ir para produção: quem pagasse teria o Acesso sem ter onde baixar | Manter; só homologação até o #33 |
| D4 | Pagamento marcado no painel do Asaas como "recebido em dinheiro" (`RECEIVED_IN_CASH`) **não** concede Acesso e vai para revisão. A alternativa seria usar isso como jeito manual de liberar um Produto | Não conceder (ADR-0003: só Pix) |
| D5 | "Revisão humana" neste ticket é a tabela `payment_event_results`, o `WARN` no log e uma consulta SQL no runbook; sem tela e sem e-mail até o #37 e o #38 | Aceitar |
| D6 | Cobrança paga com `deleted: true` no Asaas (Pedido expirado, cobrança excluída, e mesmo assim paga): concede o Acesso, porque o dinheiro entrou. A alternativa é mandar para revisão e o Cliente esperar por você | Conceder |
| D7 | Dois Pedidos do mesmo Produto pagos: os dois ficam pagos e o segundo fica marcado `DUPLICATE_PURCHASE` para você devolver o valor no Asaas. Nada é devolvido sozinho | Aceitar |
| D8 | Comprar um Produto que já tem: `409` com a mensagem na página do Produto. Esconder o botão "Comprar com Pix" para quem já tem fica para a Biblioteca (#32) | Aceitar |
| D9 | Consulta do estado a cada 5 s, até o prazo, e por mais 10 minutos depois de expirar | Aceitar |
| D10 | O ADR-0005 dizia que a retentativa de webhooks viria do Spring Modulith; passa a dizer que é uma tarefa única do db-scheduler. O ADR-0003 ganha as regras deste ticket | Aceitar |
| D11 | O `FakePixGateway` (local e demonstração) passa a responder que toda cobrança criada por ele está recebida: no computador, um evento enviado com o token local marca o Pedido como pago. Não existe em `prod` (o falso recusa subir) | Aceitar |
| D12 | Da #110 entram só os dois itens que este ticket toca: validar o formato do id da cobrança e o falso recusar ler cobrança que não criou. O resto continua na #110 | Aceitar |

## 13. Arquivos

**Criar (backend, `backend/src/main/java/br/com/sergio/sapiensia/payment/`)**
- `Accesses.java`, `PaymentEventResults.java`, `PaymentResult.java`, `PixCharge.java`, `PaymentConfirmation.java`, `PaymentEventTasks.java`, `OrderStatusResponse.java`, `ProductAlreadyOwnedException.java`
- `backend/src/main/resources/db/migration/V20__paid_orders_and_accesses.sql`
- Testes da seção 10 em `backend/src/test/java/br/com/sergio/sapiensia/payment/`

**Modificar (backend)**
- `payment/`: `OrderStatus`, `Orders`, `PaymentEvents`, `PixGateway`, `AsaasPixGateway`, `FakePixGateway`, `AsaasWebhookService`, `OrderService`, `OrderController`, `OrderExpiration`
- `audit/AuditAction.java`, `audit/AuditObject.java`, `security/SecurityConfig.java`, `web/ApiExceptionHandler.java`
- `application.yml`, `src/test/resources/application.properties`
- Testes: `SwitchablePixGateway`, `AsaasWebhookTestSupport`, `AsaasPixGatewayWireTest`, `OrderExpirationTest`, `PaymentEventsTest`, `PixProviderConfigTest` (se o falso mudar o formato do id)

**Modificar (frontend, `frontend/src/features/orders/`)**
- `schemas.ts`, `api.ts`, `hooks.ts`, `messages.ts`, `OrderPage.tsx`, `BuyWithPix.tsx` e os testes; `frontend/e2e/admin-panel.e2e.ts`

**Modificar (outros)**
- `.github/asaas-stub.mjs`, `.github/workflows/ci.yml`
- `docs/adr/0003-pagamento-so-por-pix-via-asaas.md`, `docs/adr/0005-tarefas-em-segundo-plano-com-db-scheduler.md`, `docs/runbooks/webhook-asaas.md`, `CLAUDE.md` ("Reaproveitar antes de criar": `PaymentConfirmation` é o único lugar que marca Pedido como pago)

---

## Task 1: Migration, Acesso e resultados do evento

**Files:**
- Create: `db/migration/V20__paid_orders_and_accesses.sql`, `payment/Accesses.java`, `payment/PaymentEventResults.java`, `payment/PaymentResult.java`
- Modify: `payment/OrderStatus.java`, `payment/Orders.java`, `payment/PaymentEvents.java`
- Test: `payment/PaidOrdersSchemaTest.java`, `payment/PaymentEventsTest.java`, `payment/FinancialRecordImmutabilityTest.java`

**Interfaces:**
- Produces: `enum OrderStatus { PENDING, EXPIRED, PAID }`.
- Produces: `enum PaymentResult { PAID, ALREADY_PAID, DUPLICATE_PURCHASE, NO_ORDER, INVALID_PAYLOAD, CHARGE_NOT_FOUND, CHARGE_ID_MISMATCH, REFERENCE_MISMATCH, AMOUNT_MISMATCH, NOT_PIX, NOT_PAID, GAVE_UP; boolean needsReview(); boolean isFinal() }` (`needsReview` é falso só para `PAID`, `ALREADY_PAID` e `NO_ORDER`; `isFinal` é falso só para `GAVE_UP`).
- Produces em `Orders`:
  - `Optional<Row> findByProviderPaymentId(String providerPaymentId)`
  - `Optional<Row> findById(UUID id)`
  - `boolean markPaid(UUID id, Instant now)` (o `update` da seção 4; devolve `rows == 1`)
- Produces: `@Repository class Accesses`: `boolean grant(UUID customerId, UUID productId, UUID orderId, Instant now)` (`on conflict (customer_id, product_id) do nothing`; verdadeiro quando inseriu); `boolean exists(UUID customerId, UUID productId)`.
- Produces: `@Repository class PaymentEventResults` (só `insert` e `select`; nenhum `update`/`delete`):
  - `boolean record(UUID paymentEventId, PaymentResult result, UUID orderId, Instant now)`: para resultado final, `insert ... on conflict (payment_event_id) where result <> 'GAVE_UP' do nothing` e devolve se inseriu; para `GAVE_UP`, insere só se o evento ainda não tem resultado final.
  - `Optional<PaymentResult> findFinal(UUID paymentEventId)`.
- Produces em `PaymentEvents`:
  - `Optional<UUID> insertIfAbsent(String providerEventId, String eventType, String payloadJson, OffsetDateTime receivedAt)` (passa a devolver o id interno com `returning id`; vazio quando repetido)
  - `record Ref(String providerEventId, String eventType, String chargeId)` e `Optional<Ref> findRef(UUID id)` (`chargeId` vem de `payload->'payment'->>'id'`, pode ser nulo; o corpo inteiro nunca é lido)

- [ ] **Step 1: Escrever `PaidOrdersSchemaTest`** e ajustar `PaymentEventsTest`:
  - `FR-024: shouldMarkAPendingOrderAsPaidOnlyOnce` (segundo `markPaid` devolve `false`; `paid_at` preenchido)
  - `ADR-0003: shouldMarkAnExpiredOrderAsPaid` (`expired_at` continua preenchido)
  - `shouldRefuseAPaidOrderWithoutPaidAt` e `shouldRefuseAPendingOrderWithPaidAt` (SQL direto; `DataIntegrityViolationException`)
  - `SEC-025: shouldGrantOneAccessPerCustomerAndProduct` (segundo `grant`, com outro Pedido, devolve `false`; `count(*) = 1`)
  - `shouldRefuseAnAccessForAnOrderThatAlreadyGrantedOne` (`unique (order_id)`, SQL direto)
  - `SEC-025: shouldKeepTheFirstFinalResultOfAnEvent` (segundo `record` final devolve `false` e não altera)
  - `shouldAcceptAFinalResultAfterGivingUp` (`GAVE_UP`, depois `PAID`: duas linhas; `findFinal` devolve `PAID`)
  - `shouldNotRecordGivingUpAfterAFinalResult`
  - `shouldRefuseAResultForAnUnknownEvent` (FK)
  - `FinancialRecordImmutabilityTest` (alterado): `SEC-028: shouldRefuseUpdateDeleteAndTruncateOnPaymentEventResults` (mensagem `payment_event_results is append-only`, SQLSTATE `42501`) e `SEC-028: shouldKeepPaymentEventResultsClosedToOtherRoles` (RLS ligado, sem privilégio para `public`; mesmo molde do teste de `payment_events`)
  - `shouldFindAnOrderByItsChargeId` e `shouldFindNoOrderForAnUnknownCharge`
  - `shouldLeaveANewPendingOrderPossibleAfterThePreviousOneWasPaid` (o índice de um `PENDING` não conta o pago)
  - `PaymentEventsTest`: `FR-021: shouldReturnTheInternalIdOfAStoredEventAndNothingForARepeatedOne`; `shouldReadOnlyTheChargeIdOfAnEvent` (com e sem `payment.id`)
- [ ] **Step 2: Rodar e ver falhar.** Run: `cd backend; .\mvnw.cmd test -Dtest='PaidOrdersSchemaTest,PaymentEventsTest'`. Expected: FAIL (classes e métodos inexistentes).
- [ ] **Step 3: Criar a migration com a skill `nova-migration`** ("paid orders and accesses"), com o SQL da seção 5. Antes, conferir o nome do `check` de `orders.status` (`select conname from pg_constraint where conrelid = 'orders'::regclass and contype = 'c'` no banco local).
- [ ] **Step 4: Implementar** enums, repositórios e as mudanças em `Orders` e `PaymentEvents`; ajustar `AsaasWebhookService` só o necessário para compilar (`isPresent()` no lugar do `boolean`).
- [ ] **Step 5: Rodar e ver passar** (`-Dtest='PaidOrdersSchemaTest,PaymentEventsTest,OrdersTest,AsaasWebhookPersistenceTest,AsaasWebhookAuthenticationTest,FinancialRecordImmutabilityTest,SapiensiaApplicationTests'`).
- [ ] **Step 6: Commit** `feat: paid orders, accesses and payment event results tables (#28)`.

## Task 2: Consulta da cobrança no Asaas

**Files:**
- Create: `payment/PixCharge.java`
- Modify: `payment/PixGateway.java`, `payment/AsaasPixGateway.java`, `payment/FakePixGateway.java`, `.github/asaas-stub.mjs`
- Test: `payment/AsaasPixGatewayWireTest.java`, `payment/SwitchablePixGateway.java`, `payment/PixProviderConfigTest.java`

**Interfaces:**
- Produces: `public record PixCharge(String id, String status, BigDecimal value, String billingType, String externalReference, boolean deleted)` com `toString()` igual a `"PixCharge[id=<id>, status=<status>]"`; `boolean isPaid()` (status `RECEIVED` ou `CONFIRMED`); `boolean isAwaitingPayment()` (status `PENDING` ou `OVERDUE`).
- Produces em `PixGateway`: `PixCharge readCharge(String chargeId)` ("Reads the charge as the provider has it now"). Falha: `PixGatewayException` com `Kind.REFUSED` (404 e outros 4xx) ou `Kind.UNAVAILABLE`; etapa `READ_CHARGE`.
- `AsaasPixGateway.readCharge`: `GET /v3/payments/{id}`, não leniente; `id`, `status` e `billingType` obrigatórios como texto; `value` obrigatório como número (`decimalValue()`); `externalReference` pode faltar (nulo); `deleted` ausente conta como `false`. Campo obrigatório faltando ou de tipo errado: `UNAVAILABLE` ("unexpected answer").
- `FakePixGateway`: guarda as cobranças que criou (`Map<String, PixCharge>`, com o valor e o id do Pedido); `readCharge` devolve a cobrança com `status = "RECEIVED"`, `billingType = "PIX"` (decisão D11); id desconhecido lança `REFUSED`; `cancelCharge` marca `deleted`. O id passa a ser `"pay_fake_" + UUID sem hifens` (cabe em `[A-Za-z0-9_]{1,64}`).
- `SwitchablePixGateway` (testes): conta `READ_CHARGE` em `calls()`; `failAt("READ_CHARGE", kind)` já funciona pelo `enter`; novo `void answerCharge(String chargeId, PixCharge charge)` (resposta fixa para aquela cobrança; `reset()` limpa); novo `void holdNextRead(CountDownLatch latch)` (como `holdNextCharge`).
- Stub do CI: guarda `value` e `externalReference` no `POST /v3/payments`; `GET /v3/payments/<id>` devolve `{object, id, status: "RECEIVED", value, billingType: "PIX", externalReference, deleted: false}` ou 404; o `DELETE` continua removendo. O `GET .../pixQrCode` continua como está.

- [ ] **Step 1: Escrever os testes** em `AsaasPixGatewayWireTest` (servidor HTTP local do próprio teste):
  - `SEC-026: shouldReadTheChargeWithTheApiKey` (caminho `/v3/payments/pay_1`, verbo `GET`, cabeçalhos `access_token` e `User-Agent`; resposta com `value: 29.9` vira `BigDecimal` igual a `29.90` por `compareTo`; `externalReference` e `deleted` lidos)
  - `shouldReadAChargeWithoutExternalReference` (nulo)
  - `SEC-026: shouldReportAMissingChargeAsRefused` (404)
  - `SEC-031: shouldReportTheProviderAsUnavailableWhenTheChargeCannotBeRead` (parametrizado: 500, 429, 401, timeout, corpo que não é JSON, `200` sem `status`, `200` com `value` texto)
  - `shouldNeverPutTheChargeAnswerInAnExceptionOrLog` (marcador em `description` e em `customer` da resposta não aparece na mensagem da exceção nem no log capturado)
  - `PixProviderConfigTest`: `shouldRefuseToReadAChargeTheFakeNeverCreated`; `shouldAnswerACreatedFakeChargeAsReceivedWithItsValueAndOrder`
- [ ] **Step 2: Rodar e ver falhar.** Run: `cd backend; .\mvnw.cmd test -Dtest='AsaasPixGatewayWireTest,PixProviderConfigTest'`. Expected: FAIL (`readCharge` inexistente).
- [ ] **Step 3: Implementar** `PixCharge`, `readCharge` nos dois adaptadores e no `SwitchablePixGateway`; atualizar o stub.
- [ ] **Step 4: Rodar e ver passar** (as duas classes mais `OrderCreationTest`, `OrderIdempotencyTest`, `OrderProviderFailureTest`, `OrderLogTest`, por causa do formato novo do id do falso).
- [ ] **Step 5: Conferir o stub à mão:** `node .github/asaas-stub.mjs 54399` e, em outra aba, criar uma cobrança e lê-la com `curl` (com o cabeçalho `access_token` do stub). Expected: `status` `RECEIVED` e o `value` enviado. Encerrar o processo pelo PID.
- [ ] **Step 6: Commit** `feat: read a charge from the Pix provider (#28)`.

## Task 3: `PaymentConfirmation`, o único lugar que paga

**Files:**
- Create: `payment/PaymentConfirmation.java`
- Modify: `audit/AuditAction.java` (`ACCESS_GRANTED`), `audit/AuditObject.java` (`ORDER`)
- Test: `payment/PaymentConfirmationTest.java`

**Interfaces:**
- Consumes: Task 1 (`Orders`, `Accesses`, `PaymentEventResults`, `PaymentEvents.findRef`, `PaymentResult`), Task 2 (`PixGateway.readCharge`, `PixCharge`), `AuditLog.recordInTransaction`, `Clock`, `PlatformTransactionManager`.
- Produces: `@Service class PaymentConfirmation`:
  - `PaymentResult process(UUID paymentEventId)`: a ordem da seção 3. Devolve o resultado gravado (o já existente, se o evento já tinha um). Lança `PaymentNotReadyException` (classe interna estática, `RuntimeException` sem stack trace) quando o Asaas está indisponível ou a cobrança ainda aparece como `PENDING`/`OVERDUE`; nesses casos **nada** é gravado.
  - `void giveUp(UUID paymentEventId)`: grava `GAVE_UP` (com o id do Pedido, se achar) e registra `ERROR`.
- A transação do passo 6 usa `TransactionTemplate` (como `OrderService`), e a consulta ao Asaas fica fora dela.
- Log conforme a seção 9. `needsReview()` decide entre `INFO` e `WARN`.

- [ ] **Step 1: Escrever `PaymentConfirmationTest`** (`@SpringBootTest`, `SwitchablePixGatewayConfig`, `db-scheduler.polling-interval=PT1H` para a tarefa não interferir; cada teste cria Cliente, Produto de 2990 centavos, Pedido e um evento direto em `payment_events`):
  - `FR-024, SEC-026: shouldPayThePendingOrderAndGrantTheAccessWhenTheProviderConfirms` (Pedido `PAID` com `paid_at`; 1 Acesso com `order_id` certo; 1 auditoria `ACCESS_GRANTED`/`ORDER`/id do Pedido/ator nulo; resultado `PAID`)
  - `FR-024: shouldAcceptConfirmedAsPaid` (status `CONFIRMED`)
  - `ADR-0003, SEC-031: shouldPayAnOrderThatHadAlreadyExpired`
  - `SEC-031: shouldPayAnOrderWhoseEventArrivesDaysLate` (relógio adiantado 5 dias)
  - `SEC-025: shouldDoNothingWhenTheSameEventIsProcessedAgain` (segunda chamada: mesmo resultado, 1 Acesso, 1 auditoria, **nenhuma** nova leitura no Asaas)
  - `SEC-025: shouldAnswerAlreadyPaidForASecondEventOfTheSamePayment` (sem consultar o Asaas)
  - `SEC-031: shouldRecordAnOrphanChargeAndIgnoreIt` (`NO_ORDER`; nenhuma chamada ao Asaas; nenhum Acesso)
  - Um teste por exigência do A1; em todos, Pedido continua como estava, nenhum Acesso, nenhuma auditoria, o resultado gravado é o indicado e o log tem `Payment event needs review`:
    - `SEC-026: shouldNotGrantWhenTheValueDiffers` (parametrizado: `29.89`, `29.91`, `2990`, `0`; `AMOUNT_MISMATCH`)
    - `SEC-026: shouldNotGrantWhenTheProviderReturnsAnotherChargeId` (`CHARGE_ID_MISMATCH`)
    - `SEC-026: shouldNotGrantWhenTheExternalReferenceIsAnotherOrder` (`REFERENCE_MISMATCH`)
    - `SEC-026: shouldNotGrantWhenTheExternalReferenceIsMissing` (`REFERENCE_MISMATCH`)
    - `SEC-026: shouldNotGrantWhenTheChargeIsNotPix` (parametrizado: `RECEIVED_IN_CASH`, `BOLETO`, `CREDIT_CARD`; `NOT_PIX`)
    - `SEC-026, SEC-031: shouldNotGrantWhenTheStatusIsNotReceivedOrConfirmed` (parametrizado: `REFUNDED`, `REFUND_IN_PROGRESS`, `CHARGEBACK_REQUESTED`, `RECEIVED_IN_CASH`, `AWAITING_RISK_ANALYSIS`; `NOT_PAID`)
  - `SEC-026: shouldNotGrantWhenTheProviderDoesNotKnowTheCharge` (`REFUSED`; `CHARGE_NOT_FOUND`)
  - `SEC-031: shouldRecordNothingAndAskForARetryWhileTheProviderIsUnavailable` (`PaymentNotReadyException`; nenhum resultado; Pedido `PENDING`; depois, com o Asaas de volta, `PAID`)
  - `SEC-031: shouldAskForARetryWhileTheProviderStillShowsTheChargeAsPending` (`PENDING` e `OVERDUE`)
  - `shouldRecordAnEventWithoutAChargeIdAsInvalid` (parametrizado: sem `payment`, `payment.id` numérico, vazio, com espaço, com 65 caracteres, com `'; drop`)
  - `ADR-0003: shouldGrantWhenThePaidChargeIsMarkedDeleted` (decisão D6)
  - `FR-024: shouldMarkTheSecondPaidOrderOfTheSameProductAsADuplicatePurchase` (2 Pedidos pagos, 1 Acesso do primeiro, 1 auditoria, segundo resultado `DUPLICATE_PURCHASE`)
  - `shouldGrantNothingWhenTheAuditTrailCannotBeWritten` (com `SwitchableAuditLogConfig`: a transação desfaz o Pedido pago e o Acesso; a exceção sobe)
  - `shouldRecordThatProcessingGaveUp` (`giveUp`: `GAVE_UP` com o id do Pedido; não grava nada se já há resultado final)
  - `SEC-031: shouldPayWhenAnEventThatWasGivenUpIsProcessedAgain` (`giveUp`, depois `process` com o Asaas de volta: `PAID`, Acesso concedido)
- [ ] **Step 2: Rodar e ver falhar.** Run: `cd backend; .\mvnw.cmd test -Dtest=PaymentConfirmationTest`. Expected: FAIL (classe inexistente).
- [ ] **Step 3: Implementar** `PaymentConfirmation` e os dois valores de enum da auditoria.
- [ ] **Step 4: Rodar e ver passar** (`-Dtest='PaymentConfirmationTest,AuditLogTest,AuditImmutabilityTest'`).
- [ ] **Step 5: Commit** `feat: confirm a payment with the provider and grant the access (#28)`.

## Task 4: Do webhook à tarefa, com repetição

**Files:**
- Create: `payment/PaymentEventTasks.java`
- Modify: `payment/AsaasWebhookService.java`, `application.yml`, `src/test/resources/application.properties`, `docs/runbooks/webhook-asaas.md`
- Test: `payment/PaymentEventProcessingTest.java`, `payment/AsaasWebhookTestSupport.java`

**Interfaces:**
- Consumes: `PaymentConfirmation.process`, `PaymentConfirmation.giveUp` (Task 3); `PaymentEvents.insertIfAbsent` (Task 1); `SchedulerClient`.
- Produces: `@Configuration(proxyBeanMethods = false) class PaymentEventTasks` com `static final String PROCESS = "process-payment-event"` e o bean `OneTimeTask<Void> processPaymentEventTask(PaymentConfirmation confirmation)`: o id do evento é o **id da instância** (`UUID.fromString(instance.getId())`) e a tarefa não tem dado, para o reagendamento por SQL (A3) não precisar serializar nada. Id de instância que não é UUID: `ERROR` no log e a tarefa termina sem repetir. `onFailure` no mesmo molde de `CustomerEmailTasks.retryThenDrop` (`FailureHandler.<Void>maxRetries(maxAttempts).retryEvery(retryDelay).thenRemove(...)`), cujo `thenRemove` chama `confirmation.giveUp(...)` com o id da instância.
- Produces: propriedades `app.orders.payment-processing.max-attempts: 120` e `app.orders.payment-processing.retry-delay: PT1M` (as duas também no `application.properties` de teste).
- `AsaasWebhookService.receive`: o `insert` e o agendamento numa `TransactionTemplate`; agenda só quando gravou e o tipo está em `Set.of("PAYMENT_RECEIVED", "PAYMENT_CONFIRMED")`: `scheduler.schedule(new TaskInstance<Void>(PaymentEventTasks.PROCESS, eventId.toString()), Instant.now(clock))`. O log e o `Outcome` não mudam.
- Runbook (A3): seção "Reprocessar um evento de pagamento" em `docs/runbooks/webhook-asaas.md`, com o SQL entre os marcadores `<!-- reprocess-sql:start -->` e `<!-- reprocess-sql:end -->`:
  ```sql
  insert into scheduled_tasks (task_name, task_instance, task_data, execution_time, picked, version)
  select 'process-payment-event', e.id::text, null, now(), false, 1
  from payment_events e
  where e.provider_event_id = 'evt_COLE_AQUI_O_ID_DO_EVENTO'
    and e.event_type in ('PAYMENT_RECEIVED', 'PAYMENT_CONFIRMED')
  on conflict (task_name, task_instance) do nothing;
  ```
  Mais a consulta que lista os eventos com `GAVE_UP` e sem resultado final, para achar o id. O texto avisa: reprocessar é seguro (o Pedido só vira pago uma vez), e o `on conflict` não mexe numa tarefa que ainda está repetindo.
- `AsaasWebhookTestSupport`: novo `static String paymentEvent(String id, String type, String chargeId)` (corpo como o da documentação, com `payment.id` escolhido).

- [ ] **Step 1: Escrever `PaymentEventProcessingTest`** (porta aleatória, `SwitchablePixGatewayConfig`, `db-scheduler.polling-interval=PT0.2S`, `app.orders.payment-processing.retry-delay=PT0.5S`, `app.orders.payment-processing.max-attempts=3`, contexto isolado; esperas com Awaitility ou laço com limite de 20 s, nunca espera fixa):
  - `FR-021, FR-024: shouldPayTheOrderAfterAnAuthenticatedPaymentEvent` (`POST` real com token e IP; resposta 200; em seguida o Pedido fica `PAID` com Acesso)
  - `FR-021: shouldAnswerOkWithoutWaitingForTheProvider` (leitura da cobrança presa por um latch: o 200 chega antes de soltar; depois de soltar, `PAID`)
  - `FR-021: shouldStoreTheEventAndTheTaskInOneTransaction` (chama `receive` dentro de uma transação de teste que desfaz: nem evento nem linha em `scheduled_tasks` com esse `task_instance`)
  - `SEC-025: shouldScheduleNothingForARepeatedEvent` (mesmo corpo 3 vezes: 1 evento, 1 execução, 1 Acesso)
  - `SEC-031: shouldRetryUntilTheProviderIsBack` (`failAt("READ_CHARGE", UNAVAILABLE)`; o Pedido continua `PENDING`; ao liberar, vira `PAID` sem novo webhook)
  - `SEC-031: shouldGiveUpAfterTheLastAttemptAndKeepTheEvent` (falha permanente: resultado `GAVE_UP`, evento ainda em `payment_events`, tarefa removida, Pedido `PENDING`, nenhum Acesso)
  - `SEC-031: shouldPayAfterTheRunbookReschedulesAnEventThatWasGivenUp` (A3): depois do `GAVE_UP`, com o Asaas de volta, lê o SQL entre os marcadores do runbook (caminho relativo `../docs/runbooks/webhook-asaas.md`; o teste falha se os marcadores não existirem), troca `evt_COLE_AQUI_O_ID_DO_EVENTO` pelo id do evento, roda com `JdbcTemplate` e espera o Pedido `PAID` com Acesso; rodar o SQL de novo não cria segunda tarefa nem segundo Acesso
  - `shouldDoNothingWhenTheRunbookSqlNamesAnEventThatIsNotAPayment` (evento `PAYMENT_CREATED`: zero linhas inseridas)
  - `SEC-031: shouldAnswerOkAndGrantNothingForAChargeWithoutAnOrder` (200; resultado `NO_ORDER`)
  - `shouldOnlyStoreEventsThatAreNotPayments` (parametrizado: `PAYMENT_CREATED`, `PAYMENT_OVERDUE`, `PAYMENT_DELETED`, `PAYMENT_REFUNDED`, `PAYMENT_CHARGEBACK_REQUESTED`, `BALANCE_VALUE_BLOCKED`, `SOMETHING_NEW`: 200, evento gravado, nenhuma tarefa, nenhum resultado, Pedido intacto)
  - `SEC-027: shouldRefuseAPaymentEventFromALoggedInCustomerWithoutTheToken` (sessão de Cliente e CSRF válidos, sem o cabeçalho do token: 401, Pedido `PENDING`)
- [ ] **Step 2: Rodar e ver falhar.** Run: `cd backend; .\mvnw.cmd test -Dtest=PaymentEventProcessingTest`. Expected: FAIL.
- [ ] **Step 3: Implementar** `PaymentEventTasks`, a mudança no `AsaasWebhookService` e as propriedades.
- [ ] **Step 4: Rodar e ver passar** (`-Dtest='PaymentEventProcessingTest,AsaasWebhookPersistenceTest,AsaasWebhookAuthenticationTest,AsaasWebhookLogTest,AsaasWebhookRouteVariantsTest,PaymentEventsTest,ProdProfileConfigTest,SapiensiaApplicationTests'`).
- [ ] **Step 5: Commit** `feat: process payment events in a retried background task (#28)`.

## Task 5: Concorrência e a corrida com a expiração

**Files:**
- Modify: `payment/OrderExpiration.java`
- Test: `payment/PaymentConcurrencyTest.java`, `payment/OrderExpirationTest.java`

**Interfaces:**
- Consumes: Tasks 1 a 4.
- `OrderExpiration.Expirer.tryExpire`: antes de `cancelCharge`, relê o Pedido (`orders.findById`) e, se não estiver mais `PENDING`, devolve `ALREADY_EXPIRED` sem chamar o Asaas (R1).

- [ ] **Step 1: Escrever `PaymentConcurrencyTest`** (20 rodadas por caso, `CountDownLatch` para largar juntas, `Future.get` com limite de 30 s) com os seis casos da seção 6:
  - `SEC-025, SEC-031: shouldGrantOnceWhenTheSameEventArrivesEightTimesAtOnce`
  - `SEC-025, SEC-031: shouldGrantOnceWhenTwoEventsOfTheSamePaymentAreProcessedTogether`
  - `SEC-025: shouldChangeNothingWhenTheTaskRunsTwice`
  - `ADR-0003, SEC-031: shouldEndPaidWhenThePaymentAndTheExpirationRace`
  - `SEC-031: shouldShowOnlyPendingThenPaidToACustomerReadingDuringThePayment` (leitores em `GET /api/orders/{id}` e `/status`, depois da Task 6; **este caso entra na Task 6** e fica aqui só listado)
  - `SEC-025: shouldGrantOneAccessWhenTwoOrdersOfTheSameProductArePaidTogether`
  - e, em `OrderExpirationTest`: `ADR-0003: shouldNotCancelTheChargeOfAnOrderPaidAfterItWasListed` (o Pedido vira `PAID` entre a listagem e a exclusão: `cancelledCharges()` não o contém e o Pedido continua `PAID`)
- [ ] **Step 2: Rodar.** Run: `cd backend; .\mvnw.cmd test -Dtest='PaymentConcurrencyTest,OrderExpirationTest'`. Expected: os casos de concorrência devem passar já (as barreiras são das Tasks 1 e 3); o de `OrderExpirationTest` deve FALHAR. Se algum de concorrência falhar, é defeito das Tasks 1 a 4: corrigir lá, com `systematic-debugging`.
- [ ] **Step 3: Implementar** a releitura em `tryExpire`.
- [ ] **Step 4: Rodar e ver passar** (as duas classes, 3 vezes seguidas, para pegar instabilidade).
- [ ] **Step 5: Commit** `test: payment concurrency and the race with expiration (#28)`.

## Task 6: Leitura do Pedido pago, rota do estado e compra repetida

**Files:**
- Create: `payment/OrderStatusResponse.java`, `payment/ProductAlreadyOwnedException.java`
- Modify: `payment/OrderService.java`, `payment/OrderController.java`, `security/SecurityConfig.java`, `web/ApiExceptionHandler.java`
- Test: `payment/PaidOrderReadTest.java`, `payment/PaymentConcurrencyTest.java` (o caso dos leitores)

**Interfaces:**
- Produces: `record OrderStatusResponse(OrderStatus status)`.
- Produces em `OrderService`: `OrderStatus status(UUID customerId, UUID orderId)` (404 pela mesma `OrderNotFoundException`; `PENDING` além do prazo vira `EXPIRED`, pela mesma regra de `get`, extraída para um método privado). **Nunca chama o `PixGateway`.**
- `OrderService.get`: `PAID` devolve `OrderResponse` com `status = PAID`, `secondsToExpire = 0`, `pixCopyPaste = null`, sem chamar o Asaas.
- `OrderService.createInTransaction`: logo depois da trava e do `catalog.get`, `if (accesses.exists(customerId, product.id())) throw new ProductAlreadyOwnedException();`.
- `OrderController`: `@GetMapping("/{id}/status") OrderStatusResponse status(@PathVariable UUID id, Authentication authentication)`.
- `SecurityConfig`: `.requestMatchers(HttpMethod.GET, "/api/orders/*/status").hasRole("CUSTOMER")`, junto das outras duas rotas de Pedido.
- `ApiExceptionHandler`: `ProductAlreadyOwnedException` vira `409` com `Map.of("order", "ALREADY_OWNED")`.
- Contrato para o frontend (Task 8): `OrderResponse.status` e `OrderStatusResponse.status` aceitam `PAID`.

- [ ] **Step 1: Escrever `PaidOrderReadTest`** (porta aleatória; usa `OrderHttp` para o login do Cliente):
  - `FR-024: shouldShowAPaidOrderWithoutCallingTheProvider` (`status` `PAID`, `pixCopyPaste` nulo, `copyPasteReads()` não aumenta, `Cache-Control: no-store`)
  - `shouldAnswerTheStatusWithoutCallingTheProvider` (parametrizado: `PENDING`, `PENDING` além do prazo vira `EXPIRED`, `EXPIRED`, `PAID`; `calls()` vazio; o corpo tem só `status`)
  - `SEC-010: shouldRefuseTheStatusToAnAnonymousVisitor` (401) e `shouldRefuseTheStatusToTheAdministrator` (403)
  - `SEC-011: shouldAnswerNotFoundForTheStatusOfAnotherCustomersOrder` (404, corpo sem dado do Pedido) e `shouldAnswerNotFoundForAMalformedOrderId`
  - `SEC-027: shouldNeverChangeAnOrderThroughACustomerRoute` (parametrizado: `POST`, `PUT`, `PATCH`, `DELETE` em `/api/orders/{id}` e `/api/orders/{id}/status`, com CSRF válido e corpo `{"status":"PAID"}`: resposta 403 ou 405, linha do Pedido idêntica, nenhum Acesso)
  - `SEC-027: shouldKeepAPendingOrderPendingNoMatterHowOftenItIsRead` (50 leituras das duas rotas, com `?status=PAID`)
  - `FR-024: shouldRefuseToBuyAProductTheCustomerAlreadyHas` (409, `errors.order = ALREADY_OWNED`, nenhuma linha nova em `orders`, `count("CREATE_CHARGE")` igual)
  - `shouldLetAnotherCustomerBuyTheSameProduct`
  - em `PaymentConcurrencyTest`: `SEC-031: shouldShowOnlyPendingThenPaidToACustomerReadingDuringThePayment`
- [ ] **Step 2: Rodar e ver falhar.** Run: `cd backend; .\mvnw.cmd test -Dtest=PaidOrderReadTest`. Expected: FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Rodar e ver passar** (`-Dtest='PaidOrderReadTest,PaymentConcurrencyTest,OrderRouteAccessTest,OrderCreationTest,OrderIdempotencyTest,OrderProviderFailureTest,RequestRecordsRedactionTest'`).
- [ ] **Step 5: Commit** `feat: paid order view, status route and already-owned refusal (#28)`.

## Task 7: Guarda de log

**Files:**
- Test: `payment/PaymentLogTest.java`

- [ ] **Step 1: Escrever `PaymentLogTest`** no molde de `AsaasWebhookLogTest` (`OutputCaptureExtension`, `logging.level.br.com.sergio.sapiensia=TRACE`, `org.springframework.web=DEBUG`, `org.springframework.jdbc=TRACE`, contexto isolado):
  - `ADR-0006, SEC-033: shouldNeverLogThePayloadTheValueOrPersonalDataWhilePaying`: evento com marcadores em `payment.description`, `payment.customer` e num campo `cpfCnpj`; consulta respondendo com marcador em `externalReference` errado (caso `REFERENCE_MISMATCH`) e, noutro Pedido, o caminho feliz; também uma desistência. Confere que o log **não** contém os marcadores, o token, o e-mail do Cliente, o código copia e cola nem o texto `29.9`/`2990` junto de `value`; e que **contém** `Payment event processed` (prova de que a captura funciona).
- [ ] **Step 2: Rodar.** Run: `cd backend; .\mvnw.cmd test -Dtest='PaymentLogTest,AsaasWebhookLogTest,OrderLogTest'`. Expected: PASS; se falhar, corrigir o log na Task 3 ou 4.
- [ ] **Step 3: Commit** `test: payment processing never logs sensitive data (#28)`.

## Task 8: Frontend: pago, consulta periódica e compra repetida

**Files:**
- Modify: `frontend/src/features/orders/schemas.ts`, `api.ts`, `hooks.ts`, `messages.ts`, `OrderPage.tsx`, `BuyWithPix.tsx`
- Test: `OrderPage.test.tsx`, `BuyWithPix.test.tsx`

**Interfaces:**
- Consumes: contrato da Task 6.
- `schemas.ts`: `status: z.enum(["PENDING", "EXPIRED", "PAID"])`; novo `orderStatusSchema = z.object({ status: z.enum(["PENDING", "EXPIRED", "PAID"]) })`.
- `api.ts`: `fetchOrderStatus(id: string): Promise<Order["status"]>` (`GET /api/orders/{id}/status`; 404 e 400 viram `OrderNotFoundError`, 401 vira `NotLoggedInError`); `createOrder` passa a lançar `AlreadyOwnedError` no `409` com `errors.order === "ALREADY_OWNED"`.
- `hooks.ts`: `useOrderStatus(id: string, enabled: boolean)`: `queryKey: ["orders", id, "status"]`, `refetchInterval: 5000`, `refetchIntervalInBackground: false`, `refetchOnWindowFocus: true`, `retry: false`, `gcTime: 0`.
- `messages.ts`: os textos da seção 8, com as chaves `afterPaying` (texto trocado), `expiredAlreadyPaid`, `paidDocumentTitle`, `paidHeading`, `paidText`, `alreadyOwned`.
- `OrderPage.tsx`:
  - `watching` = Pedido carregado e (`PENDING`, ou `EXPIRED` há menos de 10 minutos contados desde que a vista de expirado apareceu nesta página, por `performance.now()`); `useOrderStatus(id, watching)`.
  - Quando o estado consultado difere do estado do Pedido mostrado, `query.refetch()` uma vez.
  - Vista nova de pago (título com foco, texto, `dl` com Produto e Valor, link do Catálogo). `view` passa a ter `"paid"`.
  - Erro da consulta periódica é ignorado (a próxima tentativa corrige); nunca troca a tela para erro.
  - Nenhuma leitura de `useSearchParams` nem de `location.search`.
- `BuyWithPix.tsx`: `AlreadyOwnedError` mostra `T.alreadyOwned` no lugar da mensagem genérica.

- [ ] **Step 1: Escrever os testes** (Vitest, relógio falso, `fetch` simulado como nos testes atuais):
  - `FR-024: mostra "Pagamento confirmado", o Produto e o valor quando o Pedido está pago` (título da aba incluído; sem QR Code, sem código, sem contagem)
  - `FR-024: troca de "Pague com Pix" para "Pagamento confirmado" sem recarregar quando a consulta devolve PAID` (avança 5 s; o foco vai para o título)
  - `ADR-0003: troca de "Este Pix expirou" para "Pagamento confirmado" quando o pagamento chega depois` 
  - `mostra o aviso para quem já pagou na tela de expirado`
  - `para de consultar 10 minutos depois de expirar` (conta as chamadas a `/status`)
  - `não consulta o estado de um Pedido pago`
  - `consulta só a rota do estado, e não o Pedido inteiro, enquanto nada muda` (nenhuma chamada extra a `/api/orders/{id}`)
  - `uma falha na consulta do estado não tira a tela do Pix`
  - `SEC-027: parâmetros na URL não mudam o que a tela mostra` (`/pedidos/1?status=PAID&paid=true` com o servidor em `PENDING`)
  - `BuyWithPix`: `FR-024: mostra "Você já tem Acesso a este Produto." quando o servidor responde ALREADY_OWNED`
- [ ] **Step 2: Rodar e ver falhar.** Run: `cd frontend; pnpm vitest run src/features/orders`. Expected: FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Rodar e ver passar** (mesmo comando) e `just lint`.
- [ ] **Step 5: `/verificar-tela`** nas vistas pendente, expirado e pago e no erro da tela A, em 1280 px e 390 px, com teclado.
- [ ] **Step 6: Commit** `feat: paid order screen with status polling (#28)`.

## Task 9: E2E, `just demo-pagar`, CI e documentação

**Files:**
- Create: `scripts/demo-pagar.mjs`, `scripts/demo-pagar.test.mjs`, `backend/src/test/java/br/com/sergio/sapiensia/payment/DemoPaymentTokenTest.java`
- Modify: `frontend/e2e/admin-panel.e2e.ts`, `.github/workflows/ci.yml`, `justfile`, `docs/demo.md`, `docs/adr/0003-pagamento-so-por-pix-via-asaas.md`, `docs/adr/0005-tarefas-em-segundo-plano-com-db-scheduler.md`, `docs/runbooks/webhook-asaas.md`, `CLAUDE.md`

**`just demo-pagar <id do Pedido>` (A4):**
- `justfile`: receita `demo-pagar pedido:` que roda `node scripts/demo-pagar.mjs {{pedido}}`, com o comentário `# demonstração local: marca um Pedido como pago, como se o Asaas tivesse confirmado o Pix`.
- `scripts/demo-pagar.mjs` (só Node, sem pacote novo; funções exportadas para o teste, como `demo-codigo.mjs`):
  - `export function parseOrderId(text)`: aceita só UUID; senão, sai com `Use: just demo-pagar <id do Pedido>`.
  - `export function apiBaseUrl(env)`: `http://127.0.0.1:8081` por padrão; `DEMO_API_URL` pode trocar, mas só por `http://127.0.0.1:<porta>` ou `http://localhost:<porta>`; qualquer outro endereço sai com erro (o token local nunca vai para outro lugar).
  - `export const LOCAL_WEBHOOK_TOKEN`: o mesmo valor fictício de `application-local.yml`, com o comentário `gitleaks:allow` e `nosemgrep` como no `demo-codigo.mjs`.
  - `export function paymentEvent(chargeId)`: `{"id": "evt_demo_<aleatório>", "event": "PAYMENT_RECEIVED", "dateCreated": ..., "payment": {"object": "payment", "id": chargeId, "billingType": "PIX", "status": "RECEIVED"}}`.
  - Acha o id da cobrança no banco local: contêiner publicado na porta 5433 (o mesmo jeito do `demo.ps1`), `psql -U app -tAc` com o id já validado como UUID, primeiro no banco `app_demo`, depois no `app`. Pedido não encontrado: `Pedido não encontrado no banco local.`
  - Envia o evento com `fetch` ao `POST <apiBaseUrl>/api/webhooks/asaas`, cabeçalho `asaas-access-token`. Resposta 401: `A API recusou o token local. Ela está no perfil local?`; outra resposta diferente de 200: o status. Sucesso: `Evento enviado. Em alguns segundos o Pedido aparece como pago na página dele.`
  - Esses textos são do terminal do Sérgio, não da tela do Cliente; ficam como estão aqui.
- `scripts/demo-pagar.test.mjs` (`node --test`): UUID válido e inválido; `apiBaseUrl` aceita `127.0.0.1` e `localhost` e recusa `https://sapiensia-homolog.fly.dev`, `http://10.0.0.5:8081` e `http://localhost.evil.com:8081`; o evento tem o `payment.id` dado e tipo `PAYMENT_RECEIVED`; o token do script é igual ao de `backend/src/main/resources/application-local.yml` (o teste lê o arquivo).
- `DemoPaymentTokenTest` (backend): `SEC-022: shouldNotHaveTheLocalWebhookTokenOutsideTheLocalProfile` (o valor do token local não aparece em `application.yml` nem em `application-prod.yml`, e `application.yml` declara o token sem valor padrão) e `SEC-022: shouldRefuseTheLocalDemoTokenWhenAnotherTokenIsConfigured` (contexto com o token de teste: `POST` com o token local responde 401 e nada é gravado). O perfil `prod` em si já não sobe sem `APP_ASAAS_WEBHOOK_TOKEN` e recusa o `FakePixGateway` (testes do #24 e do #26).
- `docs/demo.md`: trocar "Ainda não dá para comprar, pagar nem baixar" por uma seção "Comprar e pagar" (o Cliente compra, a página do Pedido mostra o Pix fictício; copie o id do Pedido do endereço `/pedidos/<id>` e rode `just demo-pagar <id>`; a página vira "Pagamento confirmado"; baixar ainda não existe). Avisar que, se a API for reiniciada, Pedidos criados antes não podem ser pagos (o Asaas falso é em memória); crie outro.

- [ ] **Step 1: Escrever o e2e** da seção 10 (título e roteiro de lá). O envio do evento usa `request.post("/api/webhooks/asaas", { headers: { "asaas-access-token": token, "Fly-Client-IP": "52.67.12.206" }, data: {...} })`, com `id` do evento `evt_e2e_<aleatório>` e `payment.id` extraído do código mostrado por `/(pay_[A-Za-z0-9_]+)$/`. O token vem de `process.env.E2E_ASAAS_WEBHOOK_TOKEN` ou é montado por partes com o valor fictício do `application-local.yml`.
- [ ] **Step 2: CI.** No passo "Start the system", gravar o token gerado em `$GITHUB_ENV` como `E2E_ASAAS_WEBHOOK_TOKEN` (já mascarado pelo `add-mask`), para o passo "End-to-end tests" o receber. Nenhum valor em arquivo.
- [ ] **Step 3: `demo-pagar`.** Escrever `scripts/demo-pagar.test.mjs` e `DemoPaymentTokenTest`, rodar e ver falhar (`node --test scripts/demo-pagar.test.mjs`; `cd backend; .\mvnw.cmd test -Dtest=DemoPaymentTokenTest`), implementar o script e a receita, rodar e ver passar.
- [ ] **Step 4: Rodar o e2e no computador.** Na pasta principal: `just db`. Na worktree: `just dev-back`, `just dev-front` e `just e2e`. Expected: PASS nos dois projetos. Com a API ainda ligada, criar um Pedido no navegador e rodar `just demo-pagar <id>`: a página vira "Pagamento confirmado". Encerrar os processos pelo PID.
- [ ] **Step 5: Documentação.**
  - ADR-0003: parágrafo "Pedido pago e Acesso (#28)" com as regras das seções 1 a 5 (assíncrono com tarefa; consulta sempre; tabela de resultados; `RECEIVED_IN_CASH` não concede; cobrança `deleted` paga concede; compra repetida) e a nota de que os eventos de estorno continuam só gravados até o #36.
  - ADR-0005: trocar "A retentativa de webhooks vem do registro de eventos do Spring Modulith" pela tarefa única `process-payment-event` (1 minuto, 120 tentativas, `GAVE_UP`).
  - Runbook (além da seção de reprocessar, que é da Task 4): seção "Conferir um pagamento" (roteiro do sandbox da seção 11) e a consulta de revisão:
    ```sql
    select r.processed_at, r.result, r.order_id, e.provider_event_id, e.event_type
    from payment_event_results r join payment_events e on e.id = r.payment_event_id
    where r.result not in ('PAID', 'ALREADY_PAID', 'NO_ORDER')
    order by r.processed_at desc;
    ```
    e a de eventos de pagamento que ainda não têm resultado (tarefa em repetição). E a nota de que `payment_event_results` também é só inserção (SEC-028), conferida pela mesma consulta de proteção, trocando o nome da tabela.
  - `CLAUDE.md`, "Reaproveitar antes de criar": `backend/.../payment/PaymentConfirmation.java` é o único lugar que marca um Pedido como pago e concede o Acesso. Na lista de comandos: `just demo-pagar <id>`.
- [ ] **Step 6: Commit** em três: `test: e2e of the confirmed payment (#28)`, `feat: just demo-pagar for the local demo (#28)` e `docs: paid order rules (#28)`.

## Task 10: Sistema de verdade e entrega (controlador, sem subagente)

- [ ] **Step 1:** `just check-rapido` (2 GB livres antes). Se falhar, ler só as linhas que importam em `.tmp/check-rapido.log`.
- [ ] **Step 2:** Sistema de verdade (banco da pasta principal, API e frontend da worktree): comprar no navegador, enviar o evento com `curl` e o token local, ver a tela virar "Pagamento confirmado" sozinha; repetir o evento; tentar comprar de novo; conferir `accesses`, `payment_event_results` e `audit_events` no banco.
- [ ] **Step 3:** `requesting-code-review` na branch inteira; corrigir o que for do ticket.
- [ ] **Step 4:** Revisão final de segurança por um subagente independente, que não viu o trabalho (só a branch e a spec), focada em: Pedido pago sem confirmação do Asaas, Acesso duplicado, divergência de valor, evento forjado e vazamento em log. O que for do ticket se corrige aqui, com teste.
- [ ] **Step 5:** Skill `verificar-entrega`, mostrando a tabela. Texto de tela fora da seção 8 volta ao Sérgio.
- [ ] **Step 6:** O que ficou de fora vira **uma** issue `needs-triage` citando o #28 (R1 no sandbox, itens da seção 11, o que as revisões apontarem).
- [ ] **Step 7:** OK do Sérgio já dado (14/11/2026) para o push quando check, e2e, revisão de segurança e `verificar-entrega` passarem: `git push -u origin feat/28-pedido-pago-acesso` e PR com `Closes #28` na primeira linha, citando os IDs. Parar com a PR aberta (sem merge). Depois do merge: `just limpar-ticket` na pasta principal.
