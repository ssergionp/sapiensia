# Conciliação noturna com o Asaas (#39) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** APROVADO pelo Sérgio em 15/11/2026 com um ajuste: D2 = 03:30 de São Paulo com janela de **90 dias** (devolução de Pix e MED podem chegar a ~80-90 dias). D1, D3 (textos da seção 5 sem mudança), D4, D5, D6, D7 e D8 aceitas como recomendado. O ADR-0002 (linha 11) e o critério da issue #39 foram atualizados conforme a D1 junto com esta aprovação. A máquina sempre ligada em produção já está no #43: fora deste ticket. Execução por subagent-driven-development (subagentes em Sonnet).

**Regras da execução (do Sérgio, na aprovação):**
- A conciliação nunca marca pago nem reembolsado por conta própria: só grava o evento marcado e agenda a tarefa; `PaymentConfirmation` e `RefundConfirmation` não mudam de comportamento.
- `payment_events` continua append-only; o evento da conciliação é distinguível do webhook (id e payload) e tem teste.
- Cada teste novo falha por asserção antes do código.
- Localmente só lint e testes focados (incluindo as classes que citam FR-022, SEC-030, ADR-0002 e NFR-004), um comando pesado por vez, nunca dois subagentes rodando testes ao mesmo tempo. `just check-rapido` uma vez no fim; se for interrompido por memória, não se repete: vale o CI.
- Antes de declarar pronto: skill `verificar-entrega` (CI no lugar do check completo) e `/code-review`.
- Push (`git push -u origin feat/39-conciliacao`) e PR (`Closes #39`) sem parar para pedir OK; com o CI verde, resumo ao Sérgio. Merge continua precisando do OK dele.

**Goal:** Toda madrugada o sistema compara os Pedidos com as cobranças Pix do Asaas, corrige sozinho o que tem caminho único (pagamento e estorno que o webhook perdeu) e avisa o Administrador por e-mail do que não tem.

**Architecture:** Uma tarefa recorrente do db-scheduler (`asaas-reconciliation`) lista as cobranças Pix dos últimos 90 dias pelo `PixGateway`. A listagem só **indica candidatos**: para corrigir, a conciliação grava um evento de pagamento com origem `RECONCILIATION` em `payment_events` e agenda a mesma tarefa `process-payment-event` do webhook; quem decide e grava continua sendo `PaymentConfirmation` e `RefundConfirmation`, que consultam a cobrança de novo por id. O que não tem correção vira uma linha em `reconciliation_divergences` (uma vez por cobrança e tipo) e um alerta pelo `AdminAlerts`.

**Tech Stack:** Spring Boot 4.1 (`JdbcClient`, `TransactionTemplate`, `RestClient`), Flyway, PostgreSQL, db-scheduler 16.12.0, JUnit 5, Testcontainers. **Nenhuma dependência nova, nenhuma rota nova, nenhuma tela nova.**

**Spec:** issue #39 (pai #5); `docs/spec/sapiensia-spec.md` (FR-022 linha 281, SEC-030 linha 422, NFR-004 linha 569); `docs/adr/0002-...` (linha 11), `0003-...` (linhas 24, 32, 33, 39, 40), `0005-...`; plano do #38 (seção 1, "Conciliação (#39), só o gancho"). Asaas, consultado em 15/11/2026: [Listar cobranças](https://docs.asaas.com/reference/listar-cobrancas), [Listagem e paginação](https://docs.asaas.com/reference/listagem-e-paginacao), [Limites da API](https://docs.asaas.com/reference/rate-e-quota-limit).

## IDs cobertos

| ID | O que este ticket entrega | Onde |
|---|---|---|
| FR-022 | Comparação de Pedidos e cobranças; divergência sinalizada | Tasks 1, 3 |
| SEC-030 | Conciliação periódica (toda noite) com alerta por e-mail | Tasks 2, 3, 4 |
| NFR-004 | A tarefa sobrevive a reinício e a noite perdida roda na volta | Task 4 |
| ADR-0002 | Pedido pago que o webhook perdeu é corrigido; Pedido que sumiu do banco é avisado (D1) | Tasks 3, 5 |

## Global Constraints

- `PaymentConfirmation` e `RefundConfirmation` **não mudam uma linha**. Nenhum outro código escreve `PAID`, `REFUNDED`, Acesso ou o e-mail de pagamento.
- A conciliação nunca apaga nem altera linha: só `insert` em `payment_events`, `reconciliation_divergences` e `admin_alerts`, e agendamento de tarefa. Nunca cancela cobrança no Asaas.
- O Asaas só é chamado pelo `PixGateway`, fora de transação, uma requisição por vez.
- Da resposta do Asaas só saem os 6 campos do `PixCharge`. Log, e-mail e tabelas da conciliação levam só: código fechado, id da cobrança (`[A-Za-z0-9_]{1,64}`), UUID do Pedido, data e contagens. Nunca corpo de resposta, nome, e-mail, CPF ou descrição.
- Textos em pt-BR exatamente como na seção 5; logs em inglês exatamente como na seção 3.
- Classes de teste terminam em `Test`; teste que prova requisito leva o ID no `@DisplayName`. Vermelho por asserção, não por erro de compilação.
- Testes locais: só os focados, um comando pesado por vez, nunca dois subagentes rodando testes ao mesmo tempo. O check que vale é o do CI. Todo comando focado inclui as classes que conferem os mesmos logs e IDs: `PaymentLogTest`, `AsaasWebhookLogTest`, `AdminAlertPrivacyTest`, `FinancialRecordImmutabilityTest`.

## Review Focus

1. **Id de cobrança fora de `[A-Za-z0-9_]{1,64}` na listagem:** o item é ignorado e contado; nunca chega a SQL, log ou evento. Teste na Task 3.
2. **Cobrança paga com `externalReference` nulo ou que não é UUID e sem Pedido:** não alerta e não quebra (D4). Teste na Task 3.
3. **Asaas cai na segunda página:** as correções da primeira ficam, sai um alerta `RECONCILIATION_FAILED` e a execução falha. Teste na Task 3.
4. **Listagem que nunca termina (`hasMore` sempre `true`):** para em `max-pages` com `TOO_MANY_CHARGES`. Teste na Task 3.
5. **A mesma cobrança em duas páginas (paginação por `offset` enquanto entram cobranças):** um evento e uma divergência só. Teste na Task 3.

---

## 1. Tarefa recorrente (item a)

- `asaas-reconciliation`, `Schedules.cron(cron, ZoneId.of("America/Sao_Paulo"))`. `app.reconciliation.cron`: `"-"` no `application.yml` (desligada no local, na demonstração e nos testes) e `"0 30 3 * * *"` no `application-prod.yml`: **03:30 de São Paulo** (D2). Conferido no jar 16.12.0: `Schedules.cron(String, ZoneId)` existe e uma agenda `"-"` (`isDisabled`) cancela a execução que estiver na tabela.
- **Janela:** cobranças criadas nos últimos **90 dias** (`app.reconciliation.window-days: 90`, D2), pelo calendário de São Paulo, e Pedidos `PAID` criados no mesmo período. Noventa dias cobrem a devolução de Pix e o MED, que podem chegar a ~80-90 dias depois do pagamento.
- **Reinício (NFR-004):** a execução fica em `scheduled_tasks`. Se o sistema estava parado às 03:30, a linha está vencida e roda quando ele volta. Se morrer no meio, o db-scheduler a revive (`onDeadExecutionRevive`, o padrão) e ela recomeça do zero; recomeçar é seguro (seção 6). Trocar o cron reagenda na subida.
- **Falha:** `onFailureReschedule()`: a próxima tentativa é a noite seguinte. Sem nova tentativa na mesma noite (a documentação pede para não repetir logo depois de um `429`; a janela de 90 dias cobre a noite perdida).
- **Homologação:** a máquina do Fly dorme (`auto_stop_machines`); lá a conciliação roda quando alguém a acorda depois das 03:30. A máquina sempre ligada em produção está no #43 e fica fora deste ticket.

## 2. Como lê o Asaas (item b)

- Método novo na porta: `PixChargePage listCharges(LocalDate createdFrom, int offset, int limit)`.
- `AsaasPixGateway`: `GET /v3/payments?billingType=PIX&dateCreated[ge]=<yyyy-MM-dd>&offset=<n>&limit=100` ([Listar cobranças](https://docs.asaas.com/reference/listar-cobrancas): `limit` máximo 100; resposta `{hasMore, data[]}` com `id`, `status`, `value`, `billingType`, `externalReference`, `deleted`). Sem filtro de `status`: uma listagem só, filtrada aqui.
- **Paginação** ([Listagem e paginação](https://docs.asaas.com/reference/listagem-e-paginacao)): `offset` 0, soma 100 enquanto `hasMore`; teto de `app.reconciliation.max-pages: 300` (30.000 cobranças).
- **Folga do teto com 90 dias (conferida na aprovação):** cada Pedido cria uma cobrança, inclusive os que expiram sem pagamento. Com o teto original de 100 páginas, 10.000 cobranças em 90 dias davam ~111 cobranças por dia: sobra hoje, mas é pouca folga para crescer. Com 300 páginas são ~333 por dia; o custo é no máximo 300 requisições por noite (~1,2% da cota de 25.000 a cada 12 h) e alguns minutos de execução. Passar do teto não perde dado em silêncio: gera o alerta `RECONCILIATION_FAILED`/`TOO_MANY_CHARGES`, sinal para subir o valor.
- **Timeouts:** os mesmos do adaptador (3 s para conectar, 10 s para responder). O limite de corpo deste passo sobe para 2 MiB (uma página de 100 cobranças não cabe nos 64 KiB dos outros passos); acima disso é `UNAVAILABLE`.
- **Limite do Asaas** ([Limites da API](https://docs.asaas.com/reference/rate-e-quota-limit)): 25.000 requisições a cada 12 h por conta e 50 `GET` simultâneos; estouro responde `429`. A conciliação faz uma requisição por vez e no máximo 300 páginas mais uma consulta por correção ou conferência: longe do limite. `429`, 5xx, timeout e resposta de formato inesperado (item sem `id`, `status` ou `value`) já viram `UNAVAILABLE` no adaptador.
- **Se o Asaas cair no meio:** a execução para na hora. O que já foi agendado continua valendo (cada correção é conferida de novo por id). Grava o alerta `RECONCILIATION_FAILED` com `ASAAS_UNAVAILABLE` e termina com erro; a noite seguinte refaz a janela inteira.

## 3. Correções automáticas (item c)

Para cada cobrança Pix da listagem, com o Pedido achado por `provider_payment_id`:

| Cobrança no Asaas | Pedido aqui | O que a conciliação faz |
|---|---|---|
| `RECEIVED`/`CONFIRMED` | `PENDING` ou `EXPIRED` | evento `PAYMENT_RECEIVED` de origem conciliação → `PaymentConfirmation` |
| `REFUNDED` | `PENDING`, `EXPIRED` ou `PAID` | evento `PAYMENT_REFUNDED` de origem conciliação → `RefundConfirmation` |
| paga | `PAID` · ou estornada com `REFUNDED` | nada |
| qualquer outro caso | | seção 4 |

- **O evento:** `provider_event_id = "reconciliation:<PAYMENT_RECEIVED|PAYMENT_REFUNDED>:<id da cobrança>"`, `event_type` igual ao do Asaas, `payload = {"source":"RECONCILIATION","payment":{"id":"<id>"}}` (nenhum dado pessoal). Gravado com `PaymentEvents.insertIfAbsent` e, na mesma transação, `scheduler.schedule(process-payment-event, <id do evento>)`: exatamente o que o webhook faz.
- **Mesmas conferências:** a tarefa chama `PaymentConfirmation.process` ou `RefundConfirmation.process`, que fazem o `GET` por id e conferem id, `externalReference`, Pix, valor e estado. Valor diferente, referência errada, compra duplicada etc. terminam nos resultados que já existem (`AMOUNT_MISMATCH`, ...) com o alerta `PAYMENT_EVENT_NEEDS_REVIEW` do #38. Asaas fora repete até 120 vezes e termina em `GAVE_UP` com o alerta dele.
- **Evento que já existe:** se não tem resultado final (`GAVE_UP` ou tarefa ainda repetindo), `scheduleIfNotExists` reagenda; se tem, nada acontece.
- **Registro de que veio da conciliação (D6):** resultado = a linha de `payment_event_results` do evento cujo `provider_event_id` começa com `reconciliation:`; auditoria = o `ACCESS_GRANTED`/`ACCESS_CANCELLED` que os caminhos únicos já gravam para o Pedido. Sem ação de auditoria nova. O runbook traz o SQL que junta os três.
- **Logs (inglês):** `Reconciliation finished: charges={} paymentFixes={} refundFixes={} divergences={} ignored={}` (INFO); `Reconciliation divergence: kind={} chargeId={} orderId={}` (WARN); `Reconciliation incomplete: reason={} page={}` (ERROR).

## 4. Pedido que faltava no banco e divergências que só alertam (itens d, e)

**D1 (item d): a conciliação não cria Pedido; só alerta.** Motivos:
1. As conferências do `PaymentConfirmation` comparam a cobrança **com o Pedido** (id, valor). Criar o Pedido a partir da cobrança faz a cobrança conferir a si mesma: o caminho único deixa de proteger.
2. A cobrança não diz qual é o Produto (só a descrição em texto) e, depois de restaurar um backup, o Cliente também pode ter sumido: seria criar conta e Pedido com dados vindos de fora.
3. Só acontece depois de uma restauração do banco, que já é manual e rara; um erro aqui libera conteúdo pago.
4. FR-022 e SEC-030 pedem comparar e sinalizar. O runbook ensina a recriar o Pedido como `EXPIRED` (nunca `PENDING`: a tarefa de expiração cancelaria a cobrança já paga no Asaas); a conciliação seguinte o paga e concede o Acesso pelo caminho único.

Consequência (feita na aprovação): o critério "Pedido pago que faltava no banco é recriado com o Acesso" da issue #39 virou dois critérios, provados em dois testes (Pedido existente não pago → pago com Acesso; cobrança paga sem Pedido → só alerta), e a frase da linha 11 do ADR-0002 foi corrigida.

Divergências (`reconciliation_divergences.kind`, alerta `RECONCILIATION_DIVERGENCE` com o `kind` no `detail`):

| `kind` | Quando | Pedido no alerta |
|---|---|---|
| `CHARGE_WITHOUT_ORDER` | cobrança paga, `externalReference` é um UUID, nenhum Pedido com esse `provider_payment_id` | não |
| `ORDER_PAID_CHARGE_NOT_PAID` | Pedido `PAID` e a cobrança, lida por id, não está paga, estornada nem em estorno | sim |
| `ORDER_PAID_CHARGE_NOT_FOUND` | Pedido `PAID` e o Asaas responde 404 para a cobrança | sim |
| `ORDER_REFUNDED_CHARGE_PAID` | Pedido `REFUNDED` e a cobrança, lida por id, está paga | sim |

- Divergência sobre um Pedido que existe é **confirmada com `readCharge`** antes de gravar (a listagem pode estar atrasada). Pedido `PAID` que não apareceu pago na listagem também é lido por id.
- "Valor diferente" não é `kind` novo: nasce no `PaymentConfirmation` como `AMOUNT_MISMATCH`.
- **Uma vez só (D5):** `unique (kind, charge_id)`; só a linha nova gera alerta, na mesma transação.
- **Não alerta, só conta no log (D4):** cobrança paga sem `externalReference` em UUID (não foi este sistema que criou); cobrança não paga sem Pedido (a órfã da linha 32 do ADR-0003, que não é pagável); estorno em andamento; `RECEIVED_IN_CASH`, chargeback.

## 5. Textos que o Administrador lê (item e) — decisão do Sérgio

Assunto: `Alerta SapiensIA: <título> (<total>)`, como no #38.

| Tipo | Título | Abertura | O que fazer |
|---|---|---|---|
| `RECONCILIATION_DIVERGENCE` | divergência na conciliação com o Asaas | A conciliação noturna comparou os Pedidos com as cobranças do Asaas e encontrou diferenças que o sistema não corrige sozinho. | veja o que cada código quer dizer e como achar a cobrança no runbook conciliacao-asaas.md, seção "Divergências". Cada divergência é avisada uma vez só. |
| `RECONCILIATION_FAILED` | conciliação com o Asaas não terminou | A conciliação noturna com o Asaas parou antes do fim. O que já tinha sido conferido continua valendo; o resto não foi comparado nesta noite. | a próxima noite tenta de novo sozinha. Para rodar agora, ou se este aviso se repetir, siga o runbook conciliacao-asaas.md, seção "A conciliação não terminou". |

- Linha: `15/11/2026 03:31 · CHARGE_WITHOUT_ORDER · Pedido não identificado` ou `... · ORDER_PAID_CHARGE_NOT_PAID · Pedido <UUID>`. O id da cobrança não vai no e-mail (o alerta não tem esse campo): fica na tabela, com o SQL no runbook.
- `detail` de `RECONCILIATION_FAILED`: `ASAAS_UNAVAILABLE` ou `TOO_MANY_CHARGES`.
- Ordem em `AdminAlertType` (prioridade): `PAYMENT_EVENT_GAVE_UP`, `PAYMENT_EVENT_NEEDS_REVIEW`, **`RECONCILIATION_DIVERGENCE`**, `ORDER_PAID_EMAIL_GAVE_UP`, `WEBHOOK_REJECTED`, **`RECONCILIATION_FAILED`**, `TASK_GAVE_UP`.
- Glossário: entram "Conciliação" e "Divergência" (D8).

## 6. Idempotência (item f)

- **Duas execuções seguidas:** o `provider_event_id` do evento é determinístico e único: a segunda não insere nem agenda (ou só reagenda o que não terminou). A divergência é única por `(kind, charge_id)`: sem linha nova, sem alerta novo.
- **Junto de um webhook:** as duas tarefas chamam o mesmo `process`. `Orders.markPaid`/`markRefunded` são updates condicionais: uma grava `PAID`, a outra `ALREADY_PAID`. O Acesso é único por Pedido e o e-mail é `scheduleIfNotExists` pelo id do Pedido: um Acesso, um e-mail.
- **Pedido criado durante a execução:** só cobrança **paga** sem Pedido alerta, e uma cobrança só pode ser paga depois de o Pedido existir.

## 7. O que não será provado e riscos (item g)

- **Fica para o roteiro de sandbox do runbook:** o formato e o efeito real de `dateCreated[ge]`; se a listagem traz cobranças excluídas (se não trouxer, um Pedido expirado pago depois de cancelado, risco R1 do ADR-0003, só é coberto pelo webhook); a ordem das páginas; o `429` de verdade; o estado da cobrança depois de estorno parcial.
- **Não compara:** cobranças com mais de 90 dias; chargeback; nada no painel (a lista de divergências na tela é outro ticket).
- **Riscos:** `payment_events` passa a ter linhas que não vieram do webhook (marcadas no id e no `payload`; o ADR-0003 registra). Uma recusa final (ex.: `AMOUNT_MISMATCH`) não é tentada de novo nas noites seguintes: depende do Administrador. Migration nova (V26).

## 8. Decisões do Sérgio (15/11/2026)

| # | Decisão | Decidido |
|---|---|---|
| D1 | Cobrança paga sem Pedido: criar o Pedido ou só alertar | Só alertar; recriação manual pelo runbook; frase do ADR-0002 e critério do ticket corrigidos na aprovação |
| D2 | Horário e janela | 03:30 de São Paulo; **90 dias** (ajuste do Sérgio); teto de 300 páginas (seção 2) |
| D3 | Textos da seção 5 | Como escritos |
| D4 | Cobrança paga que não foi criada por este sistema (sem UUID na referência) | Não alertar; só contar no log |
| D5 | Divergência não resolvida: avisar uma vez ou toda noite | Uma vez |
| D6 | Registro da correção | Evento marcado + resultado + auditoria existente; sem ação de auditoria nova |
| D7 | Asaas fora durante a execução | Alerta e nova tentativa só na noite seguinte |
| D8 | Termos "Conciliação" e "Divergência" no glossário | Sim |

---

### Task 1: `PixGateway.listCharges`

**Files:** Modify `payment/PixGateway.java`, `AsaasPixGateway.java`, `FakePixGateway.java`; Create `payment/PixChargePage.java`; Test: `AsaasPixGatewayWireTest.java`, `SwitchablePixGateway.java` (duplo de teste).

**Interfaces — Produces:**
- `public record PixChargePage(List<PixCharge> charges, boolean hasMore)`
- `PixChargePage listCharges(LocalDate createdFrom, int offset, int limit)` em `PixGateway`; passo `LIST_CHARGES`.
- `FakePixGateway.listCharges`: as cobranças em memória **com o estado guardado** (`PENDING`), em ordem de criação, fatiadas por `offset`/`limit`.
- `SwitchablePixGateway`: `answerList(List<PixCharge>)` (substitui a listagem), `failListFromOffset(int)` (falha `UNAVAILABLE` a partir desse `offset`), `failAt("LIST_CHARGES", kind)`; `reset()` limpa.

- [ ] **Step 1: testes que falham** em `AsaasPixGatewayWireTest`:
  - `FR-022: lista as cobranças Pix criadas desde a data, com offset e limit`: a requisição é `GET /v3/payments` com `billingType=PIX`, `dateCreated[ge]=2026-10-16`, `offset=100`, `limit=100`; a resposta com 2 itens e `hasMore=true` vira 2 `PixCharge` com os 6 campos.
  - `FR-022: item da listagem sem id, status ou value é indisponibilidade`; `FR-022: 429 na listagem é indisponibilidade`; `FR-022: listagem acima de 2 MiB é indisponibilidade`.
  - `SEC-030: a falha da listagem não leva o corpo da resposta ao log` (corpo com e-mail e CPF plantados).
- [ ] **Step 2:** rodar `cd backend; .\mvnw.cmd test "-Dtest=AsaasPixGatewayWireTest"` → falham por asserção.
- [ ] **Step 3:** implementar; o item da lista e o `readCharge` usam a mesma função de leitura dos 6 campos.
- [ ] **Step 4:** `.\mvnw.cmd test "-Dtest=AsaasPixGatewayWireTest,PaymentLogTest,PixProviderConfigTest"` → verde. **Step 5:** commit `feat: list Pix charges through the gateway (#39)`.

### Task 2: divergências e alertas novos

**Files:** Create migration `V26__create_reconciliation_divergences.sql` (skill `nova-migration`), `payment/ReconciliationDivergences.java`, `payment/ReconciliationDivergence.java` (enum com os 4 `kind`); Modify `alert/AdminAlertType.java`, `alert/AdminAlertEmail.java`; Test: `ReconciliationDivergencesTest.java`, `alert/AdminAlertEmailTextTest.java`, `AdminAlertPrivacyTest.java`.

**Interfaces — Produces:**
- Tabela: `id` identity, `kind text check (kind ~ '^[A-Z_]{1,40}$')`, `charge_id text not null check (charge_id ~ '^[A-Za-z0-9_]{1,64}$')`, `order_id uuid`, `found_at timestamptz not null`, `unique (kind, charge_id)`; RLS ligado e privilégios retirados como na V22.
- `boolean ReconciliationDivergences.insertIfAbsent(ReconciliationDivergence kind, String chargeId, UUID orderId, Instant now)` (`true` só para a linha nova).
- `AdminAlertType.RECONCILIATION_DIVERGENCE` e `RECONCILIATION_FAILED`, na ordem da seção 5.

- [ ] **Step 1: testes que falham:** `FR-022: a mesma divergência da mesma cobrança é gravada uma vez`; `SEC-030: o e-mail de divergência da conciliação traz o texto aprovado` e `... de conciliação que não terminou ...` (assunto e corpo iguais à seção 5; linha sem Pedido mostra "Pedido não identificado"); em `AdminAlertPrivacyTest`, os dois tipos novos sem dado pessoal.
- [ ] **Step 2:** rodar as três classes → falham por asserção. **Step 3:** implementar. **Step 4:** as três classes mais `FinancialRecordImmutabilityTest` → verde. **Step 5:** commit `feat: reconciliation divergences and their alerts (#39)`.

### Task 3: `Reconciliation.run()`

**Files:** Create `payment/Reconciliation.java`; Modify `payment/PaymentEvents.java`, `payment/Orders.java`; Test: `ReconciliationTest.java`, `ReconciliationConcurrencyTest.java` (os dois com `@Import({TestcontainersConfiguration.class, SwitchablePixGatewayConfig.class})`).

**Interfaces — Consumes:** Tasks 1 e 2; `PaymentEventTasks.PROCESS`; `AdminAlerts.record`/`recordSafely`.
**Produces:**
- `record Reconciliation.Result(int charges, int paymentFixes, int refundFixes, int divergences, int ignored)`; `Result run()`; `Reconciliation.IncompleteException` (não verificada, com `reason()`).
- `Optional<UUID> PaymentEvents.findIdByProviderEventId(String)`; `List<Orders.Row> Orders.findPaidCreatedSince(Instant since)` (só `PAID`: o Pedido pago cuja cobrança não apareceu na listagem é lido por id).
- Propriedades `app.reconciliation.window-days: 90` e `max-pages: 300` (também em `src/test/resources/application.properties`; nos testes, `max-pages` pequeno quando o teste exige).
- Teste extra (regra da aprovação): `SEC-028, FR-022: o evento da conciliação se distingue do webhook pelo id e pelo payload e não pode ser alterado` (id `reconciliation:...`, `payload->>'source' = 'RECONCILIATION'`, `update`/`delete` na linha recusados pelo gatilho).

Ordem do `run()`: lista página a página aplicando a tabela da seção 3 e a seção 4; depois confere os Pedidos `PAID` da janela; grava o log final. Qualquer `PixGatewayException` `UNAVAILABLE` (ou `max-pages`) → `recordSafely(RECONCILIATION_FAILED, reason)` e `IncompleteException`.

- [ ] **Step 1: testes que falham** (`ReconciliationTest`):
  - `FR-022, SEC-030, ADR-0002: cobrança paga no Asaas com Pedido pendente vira Pedido pago com Acesso` (também com Pedido `EXPIRED`): 1 Acesso, 1 e-mail no outbox, resultado `PAID` num evento `reconciliation:PAYMENT_RECEIVED:<id>`, auditoria `ACCESS_GRANTED`.
  - `FR-022, SEC-030: cobrança estornada no Asaas com Pedido pago vira reembolsado e o Acesso é cancelado`.
  - `FR-022: valor diferente não paga o Pedido e gera o alerta de revisão AMOUNT_MISMATCH`.
  - `ADR-0002: cobrança paga sem Pedido só alerta; nenhum Pedido é criado` (contagem de `orders` igual; 1 linha `CHARGE_WITHOUT_ORDER`; 1 alerta).
  - `FR-022: Pedido pago com cobrança não paga, ou que o Asaas não acha, gera divergência` (os dois `kind`); `FR-022: Pedido reembolsado com cobrança paga gera divergência`.
  - `SEC-030: rodar duas vezes seguidas não duplica evento, Acesso, e-mail, divergência nem alerta` (`occurrences = 1`).
  - `SEC-030: evento da conciliação que desistiu é reagendado na noite seguinte`.
  - Review Focus 1 a 5, um teste cada (o 3 confere: correção da página 1 feita, 1 alerta `RECONCILIATION_FAILED`/`ASAAS_UNAVAILABLE`, `IncompleteException`).
  - `SEC-030: o log da conciliação só traz códigos, ids e contagens`.
- [ ] **Step 2: teste que falha** (`ReconciliationConcurrencyTest`): `SEC-030: conciliação junto do webhook da mesma cobrança concede um Acesso e manda um e-mail` (segura o `readCharge` da tarefa do webhook com `holdNextRead`, roda a conciliação, solta: resultados `PAID` e `ALREADY_PAID`, 1 Acesso, 1 e-mail, nenhuma divergência).
- [ ] **Step 3:** rodar as duas classes, uma por vez → falham por asserção. **Step 4:** implementar.
- [ ] **Step 5:** `.\mvnw.cmd test "-Dtest=ReconciliationTest,ReconciliationConcurrencyTest,PaymentLogTest,AsaasWebhookLogTest,FinancialRecordImmutabilityTest"` mais `git grep -l -e FR-022 -e SEC-030 -- backend/src/test` → verde. **Step 6:** commit `feat: nightly reconciliation with Asaas (#39)`.

### Task 4: tarefa recorrente e reinício

**Files:** Create `payment/ReconciliationTasks.java`; Modify `application.yml`, `application-prod.yml`, `src/test/resources/application.properties`; Test: `ReconciliationRestartTest.java` (usa `TestcontainersConfiguration.newDatabase()` e `drop()`).

**Interfaces — Produces:** `ReconciliationTasks.TASK_NAME = "asaas-reconciliation"`; bean `RecurringTask<Void>` com `Schedules.cron(${app.reconciliation.cron}, São Paulo)`, `.onFailureReschedule()`, que só chama `Reconciliation.run()`.

- [ ] **Step 1: testes que falham:**
  - `NFR-004, SEC-030: a conciliação fica agendada para as 03:30 de São Paulo` (1º contexto, cron de produção: a linha existe com esse horário).
  - `NFR-004: a noite perdida com o sistema parado roda depois do reinício` (vence a linha por SQL, fecha o contexto; no 2º, sobre o mesmo banco, o `LIST_CHARGES` é chamado e a linha volta para as próximas 03:30).
  - `NFR-004: com o cron "-" a conciliação não fica agendada`.
- [ ] **Step 2:** rodar → falham por asserção. **Step 3:** implementar. **Step 4:** `.\mvnw.cmd test "-Dtest=ReconciliationRestartTest,DbSchedulerRestartTest,SapiensiaApplicationTests"` → verde. **Step 5:** commit `feat: schedule the reconciliation every night (#39)`.

### Task 5: documentação

**Files:** Create `docs/runbooks/conciliacao-asaas.md`; Modify `docs/adr/0003-...md`, `0005-...md`, o glossário (caminho em `docs/agents/domain.md`), `CLAUDE.md` (uma linha em "Reaproveitar antes de criar"). O ADR-0002 já foi corrigido na aprovação; só conferir que a referência ao runbook bate.

- [ ] Runbook: o que a conciliação faz e quando; SQL para ver a última execução, as correções (evento → resultado → auditoria) e as divergências com o id da cobrança; seção "Divergências" (os 4 códigos e o que fazer; recriar um Pedido perdido como `PENDING`); seção "A conciliação não terminou" (rodar agora: `update scheduled_tasks set execution_time = now() where task_name = 'asaas-reconciliation'`); roteiro de sandbox da seção 7. Máquina sempre ligada: só uma linha apontando para o #43.
- [ ] ADRs conforme D1, D6 e a seção 1. Commit `docs: reconciliation runbook and ADRs (#39)`.

## Verificação final

`just check-rapido` uma vez; skill `verificar-entrega` (CI no lugar do check completo); `/code-review`. Push e PR (`Closes #39`) só com o OK do Sérgio.
