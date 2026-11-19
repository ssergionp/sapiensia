# Adaptador do Asaas e conciliação (A3 da triagem) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Endurecer o `AsaasPixGateway` (formato do id da cobrança criada, limite de códigos de erro no log, exceção inesperada do cliente HTTP) e fechar as pendências da conciliação do #137 que são do adaptador ou só de decisão.

**Architecture:** Tudo fica dentro do `AsaasPixGateway`, a única porta para o Asaas. Nenhum contrato do `PixGateway` muda: as falhas novas saem como `UNAVAILABLE`, que os chamadores já tratam. A `Reconciliation` não muda de comportamento.

**Tech Stack:** Java 25, Spring `RestClient`, JUnit 5 com `HttpServer` local (`AsaasPixGatewayWireTest`).

**Spec:** issues #110 (seção "Segurança e dados pessoais"), #137 e #111 (teste de wire); SEC-018, SEC-026, SEC-030 de `docs/spec/`; ADR-0003.

**Fluxo:** COMPLETO (pagamento). Branch `fix/110-asaas-adapter-hardening`, worktree `.claude/worktrees/fix-110-adaptador-asaas`. PR com `Refs #110`, `Refs #137`, `Refs #111`. Não depende do A2: os dois não tocam o mesmo arquivo.

## O que a conferência no código mostrou (18/11/2026)

| Item | Origem | Estado no código | Onde |
|---|---|---|---|
| Id da cobrança sem validação de formato | #110 | **Aberto.** `createCharge` aceita qualquer texto não vazio, que vai para `orders` e para o log `Order created` | `AsaasPixGateway.java:169`, `OrderService.java:108,156` |
| Códigos de erro no log sem limite de quantidade | #110 | **Aberto.** Cada código é cortado em 64 caracteres, mas a lista não tem teto | `AsaasPixGateway.java:325-347` |
| `RuntimeException` que não é `RestClientException` vira 500 | #110 | **Aberto.** `call` só captura `RestClientException` | `AsaasPixGateway.java:273-276` |
| `call()` decide recusar ou truncar por `maxBody == MAX_LIST_BODY` | #137 item 6 | **Aberto** | `AsaasPixGateway.java:253-258` |
| Teste de wire: `id` ou `billingType` ausentes na leitura da cobrança | #111 | **Aberto** (o código já responde `UNAVAILABLE`; falta o teste) | `AsaasPixGatewayWireTest.java:643` |
| Itens 1, 2, 3, 5 e 7 da conciliação | #137 | **Abertos, como descritos.** São decisões (abaixo) | `Reconciliation.java` |
| Item 4, paginação por `offset` | #137 | **Já está no roteiro de sandbox** ("Ordem das páginas") | `docs/runbooks/conciliacao-asaas.md:132` |

Fora deste plano, continuam no #110: e-mail em log TRACE, cancelamento dentro da transação da compra, cobrança apagada à mão no Asaas, fila de expiração, os itens de tela e os demais testes.

## Decisões aprovadas (18/11/2026)

Aprovadas pelo Sérgio como recomendadas: D1 e D2 valem para o código; D3 a D6 fecham como decididos os itens 1, 2, 3, 5 e 7 do #137 (nenhuma task nova).

| # | Decisão | Recomendação |
|---|---|---|
| D1 | Id de cobrança fora do padrão na **criação** | **Recusar como `UNAVAILABLE`** (o Cliente vê o erro de "pagamento indisponível" que já existe) e não gravar nem logar o valor. Padrão: `[A-Za-z0-9_]{1,64}`, o mesmo que o webhook já exige. A cobrança fica órfã no Asaas, sem cancelamento (não se chama o Asaas com um id que não passou no padrão). Na **leitura**, nada muda: id diferente do pedido já termina em `CHARGE_ID_MISMATCH`. Na **listagem**, nada muda: a conciliação já ignora e conta. |
| D2 | Teto de códigos de erro | **10 códigos** por resposta, no log e na detecção de CPF recusado. |
| D3 | #137 item 3: evento `GAVE_UP` reagendado toda noite, sem teto | **Manter.** É a conciliação fazendo o trabalho dela enquanto a cobrança continua paga e o Pedido não; cada noite gera o alerta `PAYMENT_EVENT_GAVE_UP`, então o caso não fica escondido. Fechar o item como decidido. |
| D4 | #137 item 5: estado desconhecido do Asaas num Pedido pago vira `ORDER_PAID_CHARGE_NOT_PAID` | **Manter.** É um alerta a mais, do lado seguro; uma lista explícita de "não pago" esconderia estados novos. Fechar como decidido. |
| D5 | #137 itens 1 e 2: id da cobrança no e-mail; divergência avisada uma vez só | **Manter** (decisões D3 e D5 do plano do #39). Reabrir se o uso real pedir. |
| D6 | #137 item 7: segunda passada lê cobrança por cobrança | **Manter.** Um `429` para a execução com o alerta `RECONCILIATION_FAILED` e a noite seguinte tenta de novo; o caso real fica para o item "429 de verdade" do roteiro de sandbox. |

## Textos que o usuário vê

Nenhum texto novo.

## Global Constraints

- TDD: cada teste novo falha primeiro **por asserção**, depois passa. Teste de comportamento que já está certo é provado com uma mutação temporária.
- Testes focados (`cd backend; .\mvnw.cmd test "-Dtest=AsaasPixGatewayWireTest"`); `just check-rapido` uma vez só, no fim.
- O corpo da resposta, a chave, o CPF, o código Pix e a **mensagem** de qualquer exceção nunca chegam ao log nem à mensagem do `PixGatewayException`; só passo, status HTTP, nome da classe, id da cobrança já validado e códigos reduzidos a `[A-Za-z0-9_.]`.
- Valor que imita segredo é montado em tempo de execução nos testes (gitleaks).
- Sem dependência nova, sem migration, sem propriedade `app.*` nova.
- Um commit por task, Conventional Commits em inglês; comandos de shell um por vez.

## Review Focus

1. Id de 64 caracteres passa e de 65 é recusado (Task 1).
2. Id com quebra de linha (injeção de log) não aparece em nenhuma linha do log (Task 1).
3. Resposta de erro com centenas de códigos: no máximo 10 no log, e o CPF recusado ainda é reconhecido quando o código vem entre os 10 primeiros (Task 2).
4. `PixGatewayException` lançado dentro do `exchange` continua saindo com o tipo original (`REFUSED`, `DOCUMENT_REJECTED`), não vira `UNAVAILABLE` (Task 2).
5. Corpo acima do limite: a listagem continua recusando e as outras chamadas continuam truncando (Task 3).

---

### Task 1: Validar o id da cobrança criada

**Files:**
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/payment/AsaasPixGateway.java:151-170`
- Test: `backend/src/test/java/br/com/sergio/sapiensia/payment/AsaasPixGatewayWireTest.java`

**Interfaces:**
- Consumes: `PaymentConfirmation.CHARGE_ID` (`Pattern`, `[A-Za-z0-9_]{1,64}`), já usado por `Reconciliation` e `RefundConfirmation`.

- [ ] **Step 1: Escrever os testes** (`SEC-026`):
  - `shouldRefuseACreatedChargeWhoseIdIsOutsideThePattern`, parametrizado com `"pay 1"`, `"pay-1"`, `"pay_1\nINFO forged"` e `"a".repeat(65)`: `assertKind(() -> gateway().createCharge(...), Kind.UNAVAILABLE)` e o log capturado não contém o valor (para o terceiro, não contém `forged`).
  - `shouldAcceptACreatedChargeIdOf64Characters`: devolve o id.

- [ ] **Step 2: Rodar e ver falhar** o primeiro por asserção (hoje devolve o id).

- [ ] **Step 3: Em `createCharge`,** conferir o id lido com `PaymentConfirmation.CHARGE_ID`; fora do padrão, `throw unavailable(CREATE_CHARGE, "unexpected answer", null)`.

- [ ] **Step 4: Rodar** `.\mvnw.cmd test "-Dtest=AsaasPixGatewayWireTest,OrderProviderFailureTest,OrderLogTest"`. Esperado: verde.

- [ ] **Step 5: Commit** `fix: refuse an Asaas charge id outside the expected format`

### Task 2: Teto de códigos de erro e exceção inesperada como `UNAVAILABLE`

**Files:**
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/payment/AsaasPixGateway.java:243-277, 325-347`
- Test: `backend/src/test/java/br/com/sergio/sapiensia/payment/AsaasPixGatewayWireTest.java`

- [ ] **Step 1: Escrever os testes:**
  - `shouldLogAtMostTenErrorCodes` (`SEC-018`): resposta 400 com 50 erros `code_0`…`code_49`; o log contém `code_9` e não contém `code_10`.
  - `shouldStillRecognizeARejectedDocumentAmongTheFirstTenCodes` (`SEC-033`): o código de `cpfCnpj` na 10ª posição ainda dá `DOCUMENT_REJECTED`.
  - `shouldReportAnUnexpectedClientFailureAsUnavailable` (`SEC-018`): `new AsaasPixGateway(RestClient.builder().requestInterceptor((request, body, execution) -> { throw new IllegalStateException(MARKER); }), baseUrl(), KEY, CONNECT, READ)`; `readCharge` dá `UNAVAILABLE`, e nem a mensagem da exceção nem o log contêm `MARKER`.

- [ ] **Step 2: Rodar e ver falhar** o 1º e o 3º por asserção. O 2º já passa: prove-o com mutação temporária depois do Step 3 (teto de 9).

- [ ] **Step 3: Implementar.**
  - `errorCodes`: parar de ler ao chegar a `MAX_ERROR_CODES = 10`.
  - `call`: `catch (PixGatewayException e) { throw e; }` antes de um `catch (RuntimeException e)` que faz o que o de `RestClientException` faz hoje (`unavailable(step, e.getClass().getSimpleName(), chargeId)`).

- [ ] **Step 4: Rodar** `.\mvnw.cmd test "-Dtest=AsaasPixGatewayWireTest"`. Esperado: verde, incluindo `shouldRefuseOtherClientErrors` e `shouldReportARejectedDocumentWithoutEchoingIt` (Review Focus 4).

- [ ] **Step 5: Commit** `fix: cap logged Asaas error codes and treat unexpected client failures as unavailable`

### Task 3: Parâmetro explícito para corpo grande e testes de wire que faltam

**Files:**
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/payment/AsaasPixGateway.java:239-258`
- Test: `backend/src/test/java/br/com/sergio/sapiensia/payment/AsaasPixGatewayWireTest.java:616-660`

- [ ] **Step 1: Acrescentar** a `shouldReportTheProviderAsUnavailableWhenTheChargeCannotBeRead` dois casos: resposta 200 sem `id` e resposta 200 sem `billingType`. Passam hoje; prove cada um com mutação temporária (trocar o `requiredText` do campo por leitura opcional).

- [ ] **Step 2: Refatorar `call`:** o overload de 5 parâmetros recebe `boolean refuseOversized` no lugar da comparação `maxBody == MAX_LIST_BODY`; `listCharges` passa `true`, as outras chamadas, `false`. Trocar `java.util.Arrays.copyOf` pelo import.

- [ ] **Step 3: Rodar** `.\mvnw.cmd test "-Dtest=AsaasPixGatewayWireTest,ReconciliationTest"`. Esperado: verde, incluindo `shouldReportAListAbove2MiBAsUnavailable`, `shouldReadAPageLargerThan64KiB` e `shouldNeverPutTheResponseBodyInTheExceptionOrTheLog` (Review Focus 5).

- [ ] **Step 4: Commit** `refactor: make the oversized-answer policy of the Asaas adapter explicit`

---

## Fim da branch

`just check-rapido`; skill `verificar-entrega`; `/code-review`. Sem tela e sem API nova. Push e PR só com o OK do Sérgio. Na PR: comentar no #137 as decisões D3 a D6 e marcar o item 6; comentar no #110 os três itens atendidos (a issue não usa caixas); marcar no #111 o teste de wire.
