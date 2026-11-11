# Webhook do Asaas: autenticação e deduplicação (#26) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** RASCUNHO para revisão do Sérgio (11/11/2026). Nada foi implementado.

**Goal:** O sistema recebe o aviso (webhook) do Asaas em `POST /api/webhooks/asaas`, aceita-o só se o token do cabeçalho `asaas-access-token` e o IP de origem forem válidos, grava cada evento antes de qualquer processamento e descarta o repetido (mesmo `id`) sem nenhum efeito.

**Architecture:** A rota é liberada na `SecurityConfig` por um `AuthorizationManager` próprio (`AsaasWebhookAccess`), que confere IP e token antes de o corpo ser lido, sem `permitAll`; o CSRF é ignorado só nessa rota (ADR-0003 atualizado). O controller lê o corpo com limite de tamanho, o serviço valida `id` e `event`, redige um eventual CPF e insere na tabela nova `payment_events` com `insert ... on conflict (provider_event_id) do nothing`: o banco decide quem é o primeiro, sem corrida. Nenhum processamento acontece neste ticket; #28, #29 e #31 leem essa tabela depois.

**Tech Stack:** Spring Boot 4.1 (Spring Security 7, JdbcClient, Jackson 3), Flyway, PostgreSQL, JUnit 5, Testcontainers, `java.net.http.HttpClient`. **Nenhuma dependência nova.** Sem tela: nada no frontend.

**Spec:** `docs/spec/sapiensia-spec.md` (FR-020, FR-021, SEC-023, SEC-024, SEC-025, SEC-031, seção 8 `PaymentEvent`, história 22 da 9.2); issue #26 (pai #5); `docs/adr/0003-pagamento-so-por-pix-via-asaas.md`, `0004-identidade-e-mfa.md` (IP do cliente, `ClientIpResolver`), `0006-meta-de-seguranca-asvs-nivel-2.md`; `GLOSSARY.md`; documentação oficial do Asaas (seção 1).

## IDs cobertos

| ID | Como | Onde |
|---|---|---|
| SEC-023 | O mecanismo oficial do Asaas é token fixo no cabeçalho `asaas-access-token` mais lista de IPs (não há assinatura nem timestamp: a doc não os oferece, então "janela contra replay" não se aplica; o replay é coberto pela deduplicação). Comparação em tempo constante. Corpo bruto guardado | Tasks 2, 3, 4 |
| SEC-024 | Não se presume HMAC: implementa-se exatamente o método documentado (token + IP) | Task 2 |
| SEC-025 | `payment_events.provider_event_id` é `UNIQUE`; repetido responde 200 e não grava nem faz nada | Tasks 1, 3 |
| FR-021 | Validar autenticidade, persistir evento, deduplicar (processar idempotentemente fica no #28) | Tasks 1 a 4 |
| SEC-031 (parte) | Testes de token inválido, duplicação, replay, corpo inválido, concorrência. Fora de ordem, atraso e indisponibilidade ficam no #28 e no #31 | Tasks 2, 3 |
| ADR-0003 | Token + IPs oficiais, `id` como chave de deduplicação, CPF nunca guardado, nomes dos eventos conferidos | Tasks 1, 3, 5 |

## 1. O que a documentação oficial do Asaas diz (consulta em 11/11/2026)

Fontes (todas em `docs.asaas.com`, consultadas em 11/11/2026):

- Eventos de cobrança: <https://docs.asaas.com/docs/webhook-para-cobrancas>
- Sobre os webhooks (token, `id`, pausa): <https://docs.asaas.com/docs/sobre-os-webhooks>
- Receber eventos no endpoint (regras do token, HTTP 200): <https://docs.asaas.com/docs/receba-eventos-do-asaas-no-seu-endpoint-de-webhook>
- Fila pausada (o que conta como falha): <https://docs.asaas.com/docs/fila-pausada>
- Tipos de envio: <https://docs.asaas.com/docs/tipos-de-envio>
- IPs oficiais: <https://docs.asaas.com/docs/ips-oficiais-do-asaas> (página com `updatedAt` 2026-10-27) e changelog <https://docs.asaas.com/changelog/atualiza%C3%A7%C3%A3o-na-lista-de-ips-oficiais-dos-webhooks-asaas>
- Eventos de bloqueio de saldo (onde o MED aparece): <https://docs.asaas.com/docs/eventos-para-bloqueios-de-saldo>
- Criar webhook pela aplicação web: <https://docs.asaas.com/docs/criar-novo-webhook-pela-aplicacao-web>
- Criar webhook pela API (campos e `sendType`): <https://docs.asaas.com/reference/criar-novo-webhook>

### 1.1 Confirmado

- **Método:** `POST` com corpo JSON. Autenticação por **token fixo** no cabeçalho `asaas-access-token`, definido na configuração do webhook (campo "Auth Token", botão "Gerar token"). Regras do token: 32 a 255 caracteres, sem espaços, sem sequências simples, nunca uma API Key do Asaas. **Não há assinatura HMAC, timestamp nem nonce.**
- **Payload:** objeto com `id` (identificador do evento), `event` (nome, ex.: `PAYMENT_RECEIVED`), `dateCreated`, `account {id, ownerId}` e `payment {...}` (ou `balanceData {...}` nos eventos de saldo). O **`id` é a chave de deduplicação**: "O campo `id` identifica o evento e deve ser utilizado para evitar processamento duplicado"; a entrega é "pelo menos uma vez" e "O mesmo evento pode ser enviado mais de uma vez, mantendo o mesmo `id`". A doc pede que campos desconhecidos sejam ignorados (o Asaas adiciona atributos).
- **Exemplo do `id`:** `evt_05b708f961d739ea7eba7e4db318f621&368604920` (prefixo `evt_`, 32 hex, `&`, dígitos). A página não define a regra formal; só o exemplo.
- **`payment` não traz CPF:** o exemplo traz `customer` como código (`cus_...`), sem `cpfCnpj`. Campos listados: `object, id, dateCreated, customer, subscription, installment, paymentLink, dueDate, originalDueDate, value, netValue, originalValue, interestValue, nossoNumero, description, externalReference, billingType, status, pixTransaction, confirmedDate, paymentDate, clientPaymentDate, installmentNumber, creditDate, custody, estimatedCreditDate, invoiceUrl, bankSlipUrl, transactionReceiptUrl, invoiceNumber, deleted, anticipated, anticipable, lastInvoiceViewedDate, lastBankSlipViewedDate, postalService, creditCard{creditCardNumber, creditCardBrand, creditCardToken}, discount, fine, interest, split[], chargeback{status, reason}, refunds`.
- **Eventos de cobrança que interessam à Fase 1** (nomes exatos):
  - `PAYMENT_CONFIRMED`: "Pagamento efetuado, mas com saldo ainda não disponibilizado."
  - `PAYMENT_RECEIVED`: "Cobrança recebida, com valor disponível na conta Asaas."
  - `PAYMENT_REFUNDED`: "Cobrança estornada." (Pix: `PAYMENT_CREATED -> PAYMENT_RECEIVED -> PAYMENT_REFUNDED`.)
  - `PAYMENT_PARTIALLY_REFUNDED`, `PAYMENT_REFUND_IN_PROGRESS`: estorno parcial e em processamento.
  - `PAYMENT_CHARGEBACK_REQUESTED` ("Chargeback recebido"), `PAYMENT_CHARGEBACK_DISPUTE`, `PAYMENT_AWAITING_CHARGEBACK_REVERSAL`: a doc os descreve no contexto de cartão.
  - `PAYMENT_CREATED`, `PAYMENT_OVERDUE`, `PAYMENT_DELETED`, `PAYMENT_UPDATED`, `PAYMENT_RESTORED`: ciclo de vida da cobrança.
  - A lista completa (30 eventos `PAYMENT_*`) está na página; o #26 grava **qualquer** `event` e não interpreta nenhum.
- **MED (devolução por fraude do Pix):** **não existe evento de cobrança específico do MED.** O MED aparece nos **eventos de saldo**: `BALANCE_VALUE_BLOCKED` e `BALANCE_VALUE_UNBLOCKED`, com `balanceData.type = PIX_INFRACTION` ("Bloqueio originado pelo Mecanismo Especial de Devolução (MED) do Pix") e `balanceData.paymentId` (que **pode ser nulo**). Isso muda a premissa do ADR-0003 ("o aviso de devolução por fraude"): o ticket que cancela o Acesso por estorno precisa ouvir `PAYMENT_REFUNDED` **e** `BALANCE_VALUE_BLOCKED` com `PIX_INFRACTION`, e tratar `paymentId` nulo. Registrado na Task 5 (ADR-0003) para o ticket futuro.
- **Resposta esperada:** só **HTTP 200** conta como entrega. "Qualquer outro código HTTP, incluindo `201`, `204`, `3xx`, `4xx` e `5xx`, é tratado como falha de entrega." Timeout e falha de conexão também contam. Após **15 falhas consecutivas** a fila daquela configuração é pausada; novos eventos ficam pendentes e os guardados por mais de **14 dias** são apagados; o e-mail do webhook recebe aviso. A reativação é pelo painel ("Integrações > Webhooks") ou pela API.
- **Tipos de envio:** `SEQUENTIALLY` (ordem preservada; um evento lento ou com falha atrasa os seguintes) e `NON_SEQUENTIALLY` (podem chegar simultâneos e fora de ordem). Em ambos o mesmo evento pode ser reenviado com o mesmo `id`.
- **IPs oficiais de produção (4):** `52.67.12.206`, `18.230.8.159`, `54.94.136.112`, `54.94.183.101`. O changelog diz que `54.207.175.46` e `54.94.35.137` foram desativados "a partir do dia 23/10" (ano não informado na página; a busca aponta 2024). A página avisa que o **sandbox pode usar IPs adicionais** e que não se deve presumir que ele usa a lista de produção.
- **Limite:** até 10 webhooks por conta; sandbox e produção têm configurações independentes.

### 1.2 Não confirmado (o plano não supõe; a implementação é defensiva)

| O quê | Como o plano lida |
|---|---|
| Regra formal do formato do `id` do evento | Validação generosa: 1 a 255 caracteres ASCII imprimíveis sem espaço (`^[\x21-\x7E]{1,255}$`), coluna `varchar(255)` |
| Quais IPs o **sandbox** usa | Lista configurável por `APP_ASAAS_WEBHOOK_ALLOWED_IPS`; o IP recusado aparece no log `WARN`; o runbook explica como acrescentá-lo na homologação |
| Valor do timeout e o cronograma de retentativas do Asaas | Respondemos logo depois do insert; nada mais acontece na requisição |
| Se o Asaas manda `Content-Type: application/json` exato | A rota exige `application/json` (qualquer charset). Se o sandbox mostrar outro, a correção é uma linha, e a prova real na homologação detecta antes de produção |
| Se o token aparece só uma vez na interface | O runbook manda copiar para o Bitwarden na hora de gerar |
| Se o sandbox consegue simular o pagamento de um Pix sem dinheiro real | O runbook pede a prova com `PAYMENT_CREATED` (criar uma cobrança já dispara) e, se existir simulação, `PAYMENT_RECEIVED` |
| Se, no Pix, um MED gera depois um `PAYMENT_REFUNDED` além do `BALANCE_VALUE_BLOCKED` | Fica anotado no ADR-0003 para o ticket de estorno confirmar no sandbox ou com o suporte do Asaas |

## Global Constraints

- Todo endpoint começa com `/api`. A rota nova é `POST /api/webhooks/asaas`, liberada na `SecurityConfig` com `.access(asaasWebhookAccess)` antes do `denyAll`; **não é `permitAll`**. O CSRF é ignorado **só** nessa rota, com a atualização do ADR-0003 (regra: desligar CSRF ou abrir `/api` só com ADR).
- Código, commits, nomes e comentários em inglês. **Nenhum texto que o usuário vê** (não há tela, e-mail nem mensagem nova; as respostas HTTP ao Asaas são só status e um `ProblemDetail` com código).
- Nenhuma dependência nova.
- Migration `V16__create_payment_events.sql` criada pela skill `nova-migration` (V15 é a última). Nenhuma migration existente é editada.
- Nunca em log, auditoria, resposta ou exceção: o token, o corpo do evento (inteiro ou trecho), dado pessoal. Em log só: o `id` do evento (depois de validado), o `event`, o resultado e, nas recusas, o IP de origem e o motivo.
- O IP de origem vem **sempre** do `ClientIpResolver` (`Fly-Client-IP` em `prod`, endereço da conexão no resto). `X-Forwarded-For` nunca.
- Segredo novo `APP_ASAAS_WEBHOOK_TOKEN`: sem valor padrão no `application.yml` nem no `prod` (a aplicação não sobe sem ele); valor fictício só no perfil `local`, nos testes e no CI (`ci.yml`, imagem em perfil `prod`). Nunca no git nem no chat.
- Controller e repositório package-private, DTOs como `record`, exceção de negócio convertida no `ApiExceptionHandler`. Teste que prova requisito leva o ID no `@DisplayName`. Sem `@MockitoBean` em teste de integração.
- Só um comando pesado por vez no computador (`just check-resumo`, suíte inteira do Maven, `just imagem`).

## Review Focus

1. **Asaas reenvia o mesmo evento em paralelo (`NON_SEQUENTIALLY`)**: os dois chegam juntos, um grava, o outro recebe 200 sem gravar; nunca 500 por violação de `UNIQUE`. Teste de concorrência na Task 3.
2. **Proxy do Fly entrega a conexão do proxy e o IP real no `Fly-Client-IP`**: a lista de IPs é conferida contra o `Fly-Client-IP`, não contra o endereço da conexão; e um `X-Forwarded-For` forjado com `forward-headers-strategy=framework` nunca passa. Teste da Task 2 (classe com `framework` ligado).
3. **Asaas adiciona um campo novo ou um evento novo**: o evento é gravado e recebe 200; a fila não pausa. Testes da Task 3 (campo extra, `event` desconhecido).
4. **Alguém descobre a URL e manda lixo**: sem token válido nada é lido nem gravado, não há sessão nem cookie na resposta, e um corpo de 10 MB não entra na memória. Testes das Tasks 2 e 3 (401 sem `Set-Cookie`; 413 antes de ler tudo).
5. **O token vaza por um caminho indireto**: log em `TRACE`, mensagem de exceção, `toString` de properties, resposta 401. Teste de log na Task 4 e `toString()` redigido no record de properties.

---

## 2. Decisões (cada uma com o custo se estiver errada)

| # | Decisão | Por quê | Custo se estiver errada |
|---|---|---|---|
| D1 | Rota `POST /api/webhooks/asaas`, pacote novo `br.com.sergio.sapiensia.payment` | Pacote por funcionalidade; o #24 põe o adaptador do Asaas no mesmo pacote | Renomear rota exige reconfigurar o webhook no Asaas (1 campo) |
| D2 | Autenticação por `AuthorizationManager` próprio (`AsaasWebhookAccess`: IP **e** token), em vez de `permitAll` + conferência no controller | Nega antes de ler o corpo; cumpre "sem permitAll solto"; a recusa cai no fluxo padrão (401, sem corpo) | Se o Spring Security 7 mudar a interface, é uma classe só |
| D3 | IP conferido com `ClientIpResolver` (já existente) e lista em `app.asaas.webhook.allowed-ips`, com os 4 IPs oficiais como padrão no `application.yml` e `APP_ASAAS_WEBHOOK_ALLOWED_IPS` sobrescrevendo a lista inteira. Reaproveita a propriedade `app.login-limit.client-ip-header` (nome herdado do #11) sem renomear | Sem hardcode escondido, uma só fonte de "IP do cliente" no projeto; o sandbox pode exigir IP extra (homologação) | Lista desatualizada: Asaas recusado, fila pausa em 15 falhas, e-mail do Asaas avisa; corrige-se por variável de ambiente sem deploy de código |
| D4 | Token em `app.asaas.webhook.token` (`APP_ASAAS_WEBHOOK_TOKEN`), 32–255 caracteres sem espaço, comparado com `MessageDigest.isEqual` sobre bytes UTF-8 | Regras do próprio Asaas; tempo constante para tokens do mesmo tamanho (o tamanho vazar é aceitável: é público que são 32–255) | Nenhum conhecido |
| D5 | Tabela `payment_events` (nome da seção 8 da spec), sem coluna `provider`, com `provider_event_id UNIQUE`, `event_type`, `payload jsonb`, `received_at`. Nenhuma coluna de processamento | YAGNI: #28/#31 acrescentam o que precisarem por migration; `jsonb` deixa #28 ler `payment.id` e `payment.value` sem reparse | Se um 2º provedor entrar, uma migration acrescenta `provider` e muda a `UNIQUE` |
| D6 | Deduplicação pelo banco: `insert ... on conflict (provider_event_id) do nothing`; 1 linha = `STORED`, 0 = `DUPLICATE`. Os dois respondem 200 | Resolve a corrida sem trava nem `select` antes; a `UNIQUE` é a única verdade | Nenhum conhecido |
| D7 | Corpo lido pelo controller com limite `app.asaas.webhook.max-body-bytes = 65536` (413 acima) e `consumes = application/json` (415 fora) | Tomcat não limita corpo JSON; o payload real tem ~3 KB | Se o Asaas mandar `Content-Type` diferente: 415, fila pausa; a prova no sandbox pega isso antes de produção |
| D8 | Validação do corpo: objeto JSON com `id` (1–255 ASCII imprimível sem espaço) e `event` (`^[A-Z0-9_]{1,64}$`); o resto é livre e campos desconhecidos são ignorados | Mínimo para deduplicar e registrar; não rejeita evento novo | Se o `id` real tiver outro alfabeto, 400 e fila pausa; corrigir é mudar uma regex |
| D9 | Chaves `cpfCnpj` e `cpf` (qualquer nível, sem distinguir maiúsculas) são substituídas por `"[redacted]"` antes de gravar | ADR-0003: CPF nunca guardado; a doc não mostra o campo, mas o Asaas adiciona campos | Nenhum (caminho que hoje não executa) |
| D10 | Respostas: token/IP inválido **401 sem corpo**; repetido **200**; tipo desconhecido **200 e gravado**; JSON malformado ou sem `id`/`event` **400** (`errors.body = BODY_INVALID`); corpo grande **413**; content-type errado **415**; erro interno **500** | Veja a tabela da seção 4 | Veja a tabela da seção 4 |
| D11 | Recusas vão só para o log `WARN` (IP e motivo), **não** para `audit_events` | Rota pública na internet: um `insert` por requisição recusada seria um vetor de inundação do banco; o log cumpre o ASVS (falha de autenticação registrada) | Se for exigida trilha durável das recusas, acrescenta-se a ação de auditoria com limite de taxa (#42) |
| D12 | Aceites não geram linha de auditoria: a própria `payment_events` é o registro | Evitar registro duplo | Nenhum |
| D13 | **Sem ADR novo**; o ADR-0003 ganha consequências (rota, `access` em vez de `permitAll`, CSRF ignorado só ali, respostas, descoberta sobre o MED) | A decisão "token + IPs oficiais" já é dele; a regra de CSRF/permitAll exige ADR, e o certo é o ADR que já trata do webhook | Nenhum |
| D14 | Glossário ganha **Evento de pagamento** (o aviso do Asaas gravado em `payment_events`) | A spec chama de `PaymentEvent`; o glossário não tem o termo | Decisão de domínio: precisa do OK do Sérgio (ver seção 8) |
| D15 | Sem limite de taxa próprio na rota | Fica no #42 (limites por rota); o IP + token já barram quase tudo antes do banco | Inundação só gera 401 e linhas de log |

## 3. Como o IP e o token são conferidos

```
requisição → CorrelationIdFilter → (ForwardedHeaderFilter, só em prod) → Spring Security
  → AsaasWebhookAccess.authorize():
      ip      = clientIpResolver.resolve(request)        // Fly-Client-IP válido, senão endereço da conexão gravado pelo ConnectionAddressRecorder
      ipOk    = allowedIps.contains(ip)                   // formas canônicas (ClientIpResolver.canonicalLiteralIp)
      tokenOk = header != null && MessageDigest.isEqual(expected, header)   // os dois sempre avaliados
      !ipOk || !tokenOk → log WARN "Asaas webhook refused: ip={} reason={}" → denied → 401
  → controller lê até max-body-bytes → serviço valida → insert on conflict → 200
```

## 4. Respostas HTTP ao Asaas

| Caso | Resposta | Justificativa | Custo se errada |
|---|---|---|---|
| Token ausente/errado ou IP fora da lista | `401`, sem corpo, sem cookie | Não é o Asaas, ou o Asaas está mal configurado. No 2º caso a fila pausa em 15 falhas e o e-mail do webhook avisa: é o sinal certo, porque aceitar sem autenticação seria pior | Se o sandbox usar IP extra, pausa até acrescentá-lo (runbook) |
| Evento repetido | `200` | Já foi entregue; sem efeito. Asaas para de reenviar | Nenhum |
| `event` desconhecido | `200`, gravado | Não conhecer o tipo não é falha de entrega; o registro serve à conciliação | Linhas a mais na tabela |
| JSON malformado, não-objeto, sem `id`/`event` | `400` | Sem `id` não dá para deduplicar; responder 200 esconderia perda de evento. Com 400 o Asaas retenta e, se persistir, pausa e avisa: falha **visível** | Um corpo legítimo em formato inesperado pausa a fila; corrigir é 1 regex e reativar |
| Corpo acima de 64 KiB | `413` | Idem; protege a memória | Idem |
| `Content-Type` diferente de `application/json` | `415` (Spring) | Idem | Idem (ver D7) |
| Banco fora do ar ou erro interno | `500` (padrão do Spring, sem detalhe: `server.error.include-*` já é `never`) | Asaas retenta; a conciliação noturna cobre a lacuna (ADR-0003) | Nenhum |

## 5. Arquivos

**Criar**
- `backend/src/main/resources/db/migration/V16__create_payment_events.sql`
- `backend/src/main/java/br/com/sergio/sapiensia/payment/AsaasWebhookProperties.java`
- `backend/src/main/java/br/com/sergio/sapiensia/payment/AsaasWebhookAccess.java`
- `backend/src/main/java/br/com/sergio/sapiensia/payment/PaymentEvents.java`
- `backend/src/main/java/br/com/sergio/sapiensia/payment/AsaasWebhookService.java`
- `backend/src/main/java/br/com/sergio/sapiensia/payment/InvalidWebhookBodyException.java`
- `backend/src/main/java/br/com/sergio/sapiensia/payment/AsaasWebhookController.java`
- `backend/src/test/java/br/com/sergio/sapiensia/payment/AsaasWebhookPropertiesValidationTest.java`
- `backend/src/test/java/br/com/sergio/sapiensia/payment/AsaasWebhookAccessTest.java`
- `backend/src/test/java/br/com/sergio/sapiensia/payment/AsaasWebhookAuthenticationTest.java`
- `backend/src/test/java/br/com/sergio/sapiensia/payment/AsaasWebhookForgedIpTest.java`
- `backend/src/test/java/br/com/sergio/sapiensia/payment/AsaasWebhookPersistenceTest.java`
- `backend/src/test/java/br/com/sergio/sapiensia/payment/AsaasWebhookLogTest.java`
- `backend/src/test/java/br/com/sergio/sapiensia/payment/AsaasWebhookTestSupport.java` (monta requisições; corpos de exemplo)
- `docs/runbooks/webhook-asaas.md`

**Modificar**
- `backend/src/main/java/br/com/sergio/sapiensia/security/SecurityConfig.java` (regra `.access()` e CSRF ignorado na rota)
- `backend/src/main/java/br/com/sergio/sapiensia/customer/ClientIpResolver.java` (`canonicalLiteralIp` vira `public static`)
- `backend/src/main/java/br/com/sergio/sapiensia/web/ApiExceptionHandler.java` (handler do 400)
- `backend/src/main/resources/application.yml`, `application-local.yml`, `backend/src/test/resources/application.properties`
- `.github/workflows/ci.yml` (`-e APP_ASAAS_WEBHOOK_TOKEN=...` no `docker run`, valor fictício)
- `docs/adr/0003-pagamento-so-por-pix-via-asaas.md`, `docs/runbooks/homologacao-fly.md` (tabela de segredos: 9 nomes), `GLOSSARY.md` (D14, se aprovado), `CLAUDE.md` ("Reaproveitar antes de criar": `ClientIpResolver`)

---

## Task 1: Tabela `payment_events` e repositório

**Files:**
- Create: `backend/src/main/resources/db/migration/V16__create_payment_events.sql`
- Create: `backend/src/main/java/br/com/sergio/sapiensia/payment/PaymentEvents.java`
- Test: `backend/src/test/java/br/com/sergio/sapiensia/payment/PaymentEventsTest.java`

**Interfaces:**
- Produces: `@Repository class PaymentEvents { boolean insertIfAbsent(String providerEventId, String eventType, String payloadJson, OffsetDateTime receivedAt) }` — `true` quando gravou, `false` quando o `providerEventId` já existia. Package-private.

- [ ] **Step 1: Escrever o teste do repositório (falha: tabela não existe)**

```java
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PaymentEventsTest {
    @Autowired PaymentEvents events;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("FR-021: shouldStoreTheEventWithItsRawPayload")
    void shouldStoreTheEventWithItsRawPayload() {
        String id = "evt_" + UUID.randomUUID() + "&1";
        assertThat(events.insertIfAbsent(id, "PAYMENT_RECEIVED", "{\"id\":\"x\",\"payment\":{\"value\":10.5}}", NOW)).isTrue();
        Map<String, Object> row = jdbc.queryForMap("select event_type, payload::text as payload, received_at from payment_events where provider_event_id = ?", id);
        assertThat(row.get("event_type")).isEqualTo("PAYMENT_RECEIVED");
        assertThat((String) row.get("payload")).contains("\"value\": 10.5");
    }

    @Test
    @DisplayName("SEC-025: shouldInsertNothingWhenTheEventIdAlreadyExists")
    void shouldInsertNothingWhenTheEventIdAlreadyExists() {
        String id = "evt_" + UUID.randomUUID();
        assertThat(events.insertIfAbsent(id, "PAYMENT_RECEIVED", "{\"a\":1}", NOW)).isTrue();
        assertThat(events.insertIfAbsent(id, "PAYMENT_REFUNDED", "{\"a\":2}", NOW)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from payment_events where provider_event_id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select event_type from payment_events where provider_event_id = ?", String.class, id)).isEqualTo("PAYMENT_RECEIVED");
    }

    @Test
    @DisplayName("ADR-0003: shouldRefuseAnEventTypeOutsideTheUpperCaseAlphabet")
    void shouldRefuseAnEventTypeOutsideTheUpperCaseAlphabet() {
        assertThatThrownBy(() -> events.insertIfAbsent("evt_" + UUID.randomUUID(), "payment received", "{}", NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `cd backend; .\mvnw.cmd test -Dtest=PaymentEventsTest`
Expected: FAIL (bean `PaymentEvents` inexistente).

- [ ] **Step 3: Criar a migration com a skill `nova-migration`** ("payment events received from the payment provider")

```sql
-- Every webhook event from the payment provider, stored before any processing (FR-021). provider_event_id is the
-- provider's event id and the deduplication key (SEC-025, ADR-0003); payload is the body as received, with any
-- cpfCnpj redacted by the application (the Asaas payload does not carry it; ADR-0003 forbids storing the CPF).
create table payment_events (
  id uuid primary key default gen_random_uuid(),
  provider_event_id varchar(255) not null,
  event_type varchar(64) not null check (event_type ~ '^[A-Z0-9_]+$'),
  payload jsonb not null,
  received_at timestamp with time zone not null,
  constraint payment_events_provider_event_id_key unique (provider_event_id)
);
```

- [ ] **Step 4: Implementar `PaymentEvents`** em `payment/PaymentEvents.java`: `JdbcClient`, SQL `insert into payment_events (provider_event_id, event_type, payload, received_at) values (:id, :type, cast(:payload as jsonb), :receivedAt) on conflict (provider_event_id) do nothing`; retorna `update() == 1`.

- [ ] **Step 5: Rodar e ver passar**

Run: `cd backend; .\mvnw.cmd test -Dtest=PaymentEventsTest`
Expected: PASS (3 testes).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration/V16__create_payment_events.sql backend/src/main/java/br/com/sergio/sapiensia/payment/PaymentEvents.java backend/src/test/java/br/com/sergio/sapiensia/payment/PaymentEventsTest.java
git commit -m "feat: payment_events table deduplicated by the provider event id (#26)"
```

---

## Task 2: Propriedades, `AsaasWebhookAccess` e a regra na `SecurityConfig`

**Files:**
- Create: `payment/AsaasWebhookProperties.java`, `payment/AsaasWebhookAccess.java`
- Modify: `customer/ClientIpResolver.java` (visibilidade de `canonicalLiteralIp`), `security/SecurityConfig.java`, `application.yml`, `application-local.yml`, `src/test/resources/application.properties`, `.github/workflows/ci.yml`
- Test: `payment/AsaasWebhookPropertiesValidationTest.java`, `payment/AsaasWebhookAccessTest.java`, `payment/AsaasWebhookAuthenticationTest.java`, `payment/AsaasWebhookForgedIpTest.java`, `payment/AsaasWebhookTestSupport.java`

**Interfaces:**
- Produces: `@Validated @ConfigurationProperties("app.asaas.webhook") public record AsaasWebhookProperties(@NotBlank @Size(min = 32, max = 255) @Pattern(regexp = "\\S+") String token, @NotEmpty List<String> allowedIps, @Positive int maxBodyBytes)` com `toString()` redigido (`AsaasWebhookProperties[token=[redacted], allowedIps=..., maxBodyBytes=...]`) e `Set<String> canonicalAllowedIps()`. Compact constructor lança `IllegalArgumentException("Invalid app.asaas.webhook.allowed-ips")` se algum item não for IP literal.
- Produces: `@Component public class AsaasWebhookAccess implements AuthorizationManager<RequestAuthorizationContext>`, assinatura igual à de `AdminMfaAccess.authorize`. Constante `public static final String TOKEN_HEADER = "asaas-access-token"`.
- Produces (Task 3 consome): `ClientIpResolver.canonicalLiteralIp(String)` `public static`.
- Nesta task a rota ainda **não tem controller**: um `ProbeController` de teste (padrão `AdminRouteDenyByDefaultTest`) responde `200` em `POST /api/webhooks/asaas` para provar só a camada de segurança.

- [ ] **Step 1: Teste de validação das propriedades** (`AsaasWebhookPropertiesValidationTest`, padrão `LoginLimitPropertiesValidationTest`): `shouldAcceptAValidConfiguration` (token de 32 chars, `List.of("52.67.12.206")`, 65536) sem violações; `SEC-023: shouldRejectATokenShorterThan32OrWithWhitespace` (31 chars → 1 violação; `"a".repeat(20) + " " + "b".repeat(20)` → 1); `shouldRejectAnEmptyIpList` (1 violação); `SEC-023: shouldRejectAnAllowedIpThatIsNotALiteralAddress` (`List.of("asaas.com")` → `IllegalArgumentException`); `shouldHideTheTokenInToString` (`toString()` não contém o token); `shouldBeValidatedWhenBound`.

- [ ] **Step 2: Teste unitário do access** (`AsaasWebhookAccessTest`, com `MockHttpServletRequest` e um `ClientIpResolver` **mockado** com Mockito — o construtor dele e o `LoginLimitProperties` são package-private do pacote `customer`, e o resolver já tem os próprios testes; `when(resolver.resolve(any())).thenReturn(ip)`): `SEC-023: shouldGrantWhenTokenAndIpMatch`; `SEC-023: shouldDenyAPrefixOfTheToken`; `SEC-023: shouldDenyWhenTheHeaderIsMissing`; `SEC-023: shouldDenyAnIpOutsideTheList`; `shouldMatchIpv6InAnyForm` (lista `["::1"]`, resolver devolve `0:0:0:0:0:0:0:1`, a forma canônica de `InetAddress.getHostAddress()` → granted); `shouldEvaluateIpAndTokenEvenWhenTheFirstFails` (IP errado e token errado → `reason=IP_NOT_ALLOWED_AND_TOKEN_INVALID`, verificado pelo log com `OutputCaptureExtension`).

- [ ] **Step 3: Teste de integração da autenticação** (`AsaasWebhookAuthenticationTest`, `RANDOM_PORT`, `HttpClient` ligado a um loopback por teste como em `FlyClientIpLoginLimitTest`; properties `test.isolated-context=asaas-webhook-auth`, `app.asaas.webhook.allowed-ips=127.0.0.21`; `@Import` do `ProbeConfig`):
  - `SEC-023: shouldRefuseWithoutTheToken` — de `127.0.0.21`, sem cabeçalho → 401, corpo vazio.
  - `SEC-023: shouldRefuseAWrongToken` — token certo com 1 caractere trocado e token de outro tamanho → 401.
  - `SEC-023: shouldRefuseAnIpOutsideTheList` — token certo de `127.0.0.22` → 401.
  - `SEC-023: shouldIgnoreXForwardedForWhenNoHeaderIsConfigured` — de `127.0.0.22` com `X-Forwarded-For: 127.0.0.21` → 401.
  - `SEC-023: shouldAcceptTokenAndIp` — de `127.0.0.21` com token → 200.
  - `SEC-023: shouldNotNeedCsrfNorOpenASession` — o 200 acima sem cookie `XSRF-TOKEN` nem `X-XSRF-TOKEN`, e a resposta não tem `Set-Cookie`.
  - `SEC-010: shouldKeepOtherVerbsDenied` — `GET` com token e IP certos → 401 (a regra é só do `POST`).

- [ ] **Step 4: Teste do IP forjado atrás do proxy** (`AsaasWebhookForgedIpTest`, properties `test.isolated-context=asaas-webhook-forged-ip`, `server.forward-headers-strategy=framework`, `app.login-limit.client-ip-header=Fly-Client-IP`, `app.asaas.webhook.allowed-ips=127.0.0.31`):
  - `SEC-023: shouldTrustFlyClientIpBehindTheProxy` — conexão de `127.0.0.32`, `Fly-Client-IP: 127.0.0.31` → 200.
  - `SEC-023: shouldNeverTrustXForwardedFor` — de `127.0.0.32`, `X-Forwarded-For: 127.0.0.31`, sem `Fly-Client-IP` → 401.
  - `SEC-023: shouldFallBackToTheConnectionWhenFlyClientIpIsInvalid` — de `127.0.0.32` com `Fly-Client-IP: not-an-ip` → 401; de `127.0.0.31` com `Fly-Client-IP: not-an-ip` → 200.
  - `SEC-023: shouldRefuseAForgedFlyClientIpFromAnUnknownConnection` — de `127.0.0.32`, `Fly-Client-IP: 127.0.0.31`, token errado → 401 (o cabeçalho não substitui o token).

- [ ] **Step 5: Rodar os 4 testes e ver falhar** (`-Dtest='AsaasWebhook*Test'`): classes não existem / contexto não sobe.

- [ ] **Step 6: Implementar**
  - `ClientIpResolver.canonicalLiteralIp` → `public static` (sem outra mudança).
  - `AsaasWebhookProperties` conforme Interfaces; `canonicalAllowedIps()` aplica `ClientIpResolver.canonicalLiteralIp` a cada item.
  - `AsaasWebhookAccess(ClientIpResolver, AsaasWebhookProperties)`: guarda `byte[] expectedToken` e o `Set<String>` canônico; `authorize` avalia IP e token **sempre os dois** (sem curto-circuito), `MessageDigest.isEqual`; recusa → `log.warn("Asaas webhook refused: ip={} reason={}", ip, reason)` com `reason` em `{IP_NOT_ALLOWED, TOKEN_INVALID, IP_NOT_ALLOWED_AND_TOKEN_INVALID}`; retorna `new AuthorizationDecision(granted)`.
  - `SecurityConfig`: injetar `AsaasWebhookAccess asaasWebhookAccess`; antes do `denyAll`: `// Asaas webhook (#26, ADR-0003): the token header and the source IP are the credential; no session, no CSRF` + `.requestMatchers(HttpMethod.POST, "/api/webhooks/asaas").access(asaasWebhookAccess)`; CSRF: `csrf.spa().csrfTokenRepository(csrfTokenRepository).ignoringRequestMatchers(PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/webhooks/asaas"))`.
  - `application.yml` (bloco `app.asaas.webhook`, comentários em inglês): `token: ${APP_ASAAS_WEBHOOK_TOKEN}` (sem padrão, com comentário "secret; a missing or invalid value stops the application"), `allowed-ips: 52.67.12.206,18.230.8.159,54.94.136.112,54.94.183.101` (comentário com a URL e a data 2026-11-11, e que `APP_ASAAS_WEBHOOK_ALLOWED_IPS` substitui a lista inteira; sandbox pode precisar de IP extra), `max-body-bytes: 65536`.
  - `application-local.yml`: `token: ${APP_ASAAS_WEBHOOK_TOKEN:local-only-asaas-webhook-token-000000000000} # gitleaks:allow` e `allowed-ips: 127.0.0.1,::1`.
  - `src/test/resources/application.properties`: `app.asaas.webhook.token=test-only-asaas-webhook-token-0000000000000`, `app.asaas.webhook.allowed-ips=127.0.0.1`, `app.asaas.webhook.max-body-bytes=65536`.
  - `.github/workflows/ci.yml`, no `docker run`: `-e APP_ASAAS_WEBHOOK_TOKEN=ci-only-asaas-webhook-token-000000000000 \` (a imagem roda em `prod`, que não tem padrão). O nome entra na tabela de segredos do runbook (Task 5), não na lista "Nunca defina".

- [ ] **Step 7: Rodar e ver passar** (`-Dtest='AsaasWebhook*Test,ClientIpResolverTest,FlyClientIpLoginLimitTest'`): PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/br/com/sergio/sapiensia/payment backend/src/main/java/br/com/sergio/sapiensia/security/SecurityConfig.java backend/src/main/java/br/com/sergio/sapiensia/customer/ClientIpResolver.java backend/src/main/resources backend/src/test .github/workflows/ci.yml
git commit -m "feat: Asaas webhook route guarded by the access token and the official IPs (#26)"
```

---

## Task 3: Controller e serviço: ler com limite, validar, redigir, gravar antes de tudo

**Files:**
- Create: `payment/AsaasWebhookService.java`, `payment/InvalidWebhookBodyException.java`, `payment/AsaasWebhookController.java`
- Modify: `web/ApiExceptionHandler.java`
- Test: `payment/AsaasWebhookPersistenceTest.java`; remover o `ProbeController` dos testes da Task 2 (o controller real assume)

**Interfaces:**
- Consumes: `PaymentEvents.insertIfAbsent(...)` (Task 1); `AsaasWebhookProperties.maxBodyBytes()` (Task 2).
- Produces: `@Service class AsaasWebhookService { enum Outcome { STORED, DUPLICATE } Outcome receive(String rawBody) }` lança `InvalidWebhookBodyException` (`public class ... extends RuntimeException`, sem mensagem com o corpo: mensagem fixa `"invalid webhook body"`).
- Produces: `@RestController class AsaasWebhookController` com `@PostMapping(path = "/api/webhooks/asaas", consumes = MediaType.APPLICATION_JSON_VALUE) ResponseEntity<Void> receive(HttpServletRequest request)`.
- Produces: `ApiExceptionHandler.handleInvalidWebhookBody()` → `400` com `errors.body = BODY_INVALID`.

- [ ] **Step 1: Escrever `AsaasWebhookPersistenceTest`** (`RANDOM_PORT`, allowed-ips padrão `127.0.0.1`, cliente ligado a `127.0.0.1`; `AsaasWebhookTestSupport.event(id, type)` monta o JSON do exemplo da doc com `payment.customer = "cus_000"` e `payment.value = 59.9`):
  - `FR-021: shouldStoreTheEventBeforeAnsweringOk` — 200; linha com `provider_event_id`, `event_type`, `payload->'payment'->>'value' = '59.9'`, `received_at` não nulo.
  - `SEC-025: shouldAnswerOkAndKeepOneRowForARepeatedEvent` — mesmo `id` duas vezes, 2º com `payment.value` diferente → 200 e 200, 1 linha, `value` do 1º.
  - `SEC-025: shouldLetTheDatabaseDecideBetweenTwoSimultaneousDeliveries` — `ExecutorService` de 2 threads + `CountDownLatch`, mesmo corpo → ambos 200, `count = 1`; repetir 20 vezes com ids diferentes.
  - `FR-021: shouldStoreAnUnknownEventType` — `event = "PAYMENT_SOMETHING_NEW_2027"` → 200, gravado.
  - `FR-021: shouldIgnoreUnknownFields` — corpo com `"futureField": {"x": 1}` no topo e em `payment` → 200.
  - `SEC-023: shouldRefuseMalformedJson` — `{"id": "evt_1", ` → 400, `errors.body = BODY_INVALID`, nenhuma linha.
  - `SEC-023: shouldRefuseABodyWithoutIdOrEvent` — sem `id`; `id` vazio; `id` com espaço; `id` de 256 chars; sem `event`; `event = "payment received"`; corpo `[]`; corpo `"texto"` → 400 cada, nenhuma linha.
  - `FR-021: shouldRefuseABodyLargerThanTheLimit` — corpo válido com `description` de 70 000 caracteres → 413, nenhuma linha.
  - `SEC-023: shouldRefuseAnotherContentType` — `Content-Type: text/plain` → 415.
  - `ADR-0003: shouldRedactACpfIfTheProviderEverSendsOne` — corpo com `payment.customer = {"id":"cus_1","cpfCnpj":"12345678901"}` e `CPF` no topo → 200; `payload::text` não contém `12345678901` e contém `"[redacted]"`.
  - `shouldAnswerWithoutABodyOrCookie` — o 200 não tem `Set-Cookie` e tem `Content-Length: 0`.

- [ ] **Step 2: Rodar e ver falhar** (`-Dtest=AsaasWebhookPersistenceTest`): 404/405 (sem controller).

- [ ] **Step 3: Implementar**
  - `InvalidWebhookBodyException`: `public final class`, construtor sem argumentos, mensagem fixa.
  - `AsaasWebhookService(PaymentEvents, JsonMapper, Clock)`: `receive(String rawBody)`: `JsonNode root = mapper.readTree(rawBody)` (captura `JacksonException` → `InvalidWebhookBodyException`); exige `root.isObject()`, `id` textual que casa `^[\x21-\x7E]{1,255}$`, `event` textual que casa `^[A-Z0-9_]{1,64}$`; `redact(root)` percorre recursivamente `ObjectNode`/`ArrayNode` e troca o valor de toda chave cujo `toLowerCase(Locale.ROOT)` seja `cpfcnpj` ou `cpf` por `"[redacted]"`; `boolean stored = events.insertIfAbsent(id, event, mapper.writeValueAsString(root), OffsetDateTime.now(clock))`; `log.info("Asaas webhook event {}: id={} type={}", stored ? "stored" : "duplicate", id, event)`; retorna `Outcome`.
  - `AsaasWebhookController(AsaasWebhookService, AsaasWebhookProperties)`: `if (request.getContentLengthLong() > max) return 413`; `byte[] bytes = request.getInputStream().readNBytes(max + 1)`; `if (bytes.length > max) return 413`; `service.receive(new String(bytes, UTF_8))`; `return ResponseEntity.ok().build()` nos dois `Outcome`s.
  - `ApiExceptionHandler`: `@ExceptionHandler(InvalidWebhookBodyException.class)` → `problem(Map.of("body", "BODY_INVALID"))` com 400.
  - Remover `ProbeConfig`/`ProbeController` das classes da Task 2 (elas passam a bater no controller real com um corpo válido mínimo).

- [ ] **Step 4: Rodar e ver passar** (`-Dtest='AsaasWebhook*Test,PaymentEventsTest'`): PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/br/com/sergio/sapiensia/payment backend/src/main/java/br/com/sergio/sapiensia/web/ApiExceptionHandler.java backend/src/test/java/br/com/sergio/sapiensia/payment
git commit -m "feat: store every Asaas webhook event before anything else and drop repeats (#26)"
```

---

## Task 4: Guarda de log: nem token, nem corpo, nem dado pessoal

**Files:**
- Test: `payment/AsaasWebhookLogTest.java`

**Interfaces:**
- Consumes: tudo das Tasks 2 e 3.

- [ ] **Step 1: Escrever `AsaasWebhookLogTest`** (padrão `AdminMfaLogTest`: `OutputCaptureExtension`, `RANDOM_PORT`, `test.isolated-context=asaas-webhook-log`, `logging.level.org.springframework.security=TRACE`, `logging.level.org.springframework.web=DEBUG`, `logging.level.br.com.sergio.sapiensia=TRACE`). Um só método, `@DisplayName("SEC-023, ADR-0006: shouldNeverLogTheTokenTheBodyOrPersonalData")`, que: (a) envia um evento válido cujo `payment.description` é `MARKER-` + UUID e `payment.customer` é `cus_MARKER2`; (b) repete o mesmo evento; (c) envia com token errado `WRONG-` + UUID; (d) envia JSON malformado que contém `MARKER3`; (e) envia um corpo com `cpfCnpj = "98765432100"`. Depois afirma sobre `output.getAll()`: **não contém** o token configurado (lido do `Environment`), nem `MARKER-`, `cus_MARKER2`, `WRONG-`, `MARKER3`, `98765432100`; **contém** `Asaas webhook event stored: id=` com o `id` de (a), `Asaas webhook event duplicate: id=` e `Asaas webhook refused: ip=127.0.0.1 reason=TOKEN_INVALID`.

- [ ] **Step 2: Rodar** (`-Dtest=AsaasWebhookLogTest`). Expected: PASS de primeira se as Tasks 2 e 3 estiverem certas; se falhar, o teste mostra o trecho vazado e a correção acontece no código da Task 2/3 (não no teste).

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/br/com/sergio/sapiensia/payment/AsaasWebhookLogTest.java
git commit -m "test: Asaas webhook logs carry no token, body or personal data (#26)"
```

---

## Task 5: ADR-0003, runbook, glossário e CLAUDE.md

**Files:**
- Modify: `docs/adr/0003-pagamento-so-por-pix-via-asaas.md`, `docs/runbooks/homologacao-fly.md`, `GLOSSARY.md` (só se D14 for aprovada), `CLAUDE.md`
- Create: `docs/runbooks/webhook-asaas.md`

- [ ] **Step 1: ADR-0003, acrescentar em "Consequences"** (rascunho; texto final em português, sem segredo):

> - Webhook (#26): a rota é `POST /api/webhooks/asaas`. Ela **não** é `permitAll`: um `AuthorizationManager` próprio (`AsaasWebhookAccess`) exige, antes de ler o corpo, o token do cabeçalho `asaas-access-token` (segredo `APP_ASAAS_WEBHOOK_TOKEN`, 32 a 255 caracteres, comparado em tempo constante) **e** que o IP de origem esteja em `app.asaas.webhook.allowed-ips` (padrão: os 4 IPs oficiais de produção publicados em docs.asaas.com em 2026-11-11; `APP_ASAAS_WEBHOOK_ALLOWED_IPS` substitui a lista, porque o sandbox pode usar outros). O IP vem do `ClientIpResolver` (`Fly-Client-IP` em produção; nunca `X-Forwarded-For`). O CSRF é ignorado só nessa rota, e ela não cria sessão: o Asaas não é um navegador.
> - O Asaas não assina o corpo nem manda timestamp (SEC-023/SEC-024): o método oficial é token fixo mais lista de IPs, e é o que se implementa. Replay é neutralizado pela deduplicação: cada evento é gravado em `payment_events` com `provider_event_id` único **antes** de qualquer processamento; o repetido responde 200 e não faz nada, e dois envios simultâneos são resolvidos pelo `UNIQUE` do banco.
> - Respostas ao Asaas, que só aceita 200 e pausa a fila após 15 falhas seguidas: 200 para evento gravado, repetido ou de tipo desconhecido (gravado); 401 para token ou IP inválido; 400 para corpo sem `id` ou `event` válidos; 413 acima de 64 KiB; 500 em erro interno (o Asaas retenta; a conciliação cobre). Uma fila pausada é um sinal visível e preferível a aceitar um evento não autenticado ou sem `id`.
> - O corpo é guardado como recebido (`jsonb`), com toda chave `cpfCnpj`/`cpf` redigida por precaução: a documentação do Asaas não traz o CPF no `payment` (só o código `cus_...` do cliente).
> - **Conferido na documentação em 2026-11-11:** os eventos de pagamento são `PAYMENT_CONFIRMED` (pago, saldo indisponível) e `PAYMENT_RECEIVED` (pago, saldo disponível); o estorno é `PAYMENT_REFUNDED` (com `PAYMENT_REFUND_IN_PROGRESS` e `PAYMENT_PARTIALLY_REFUNDED`). **Não existe evento de cobrança para o MED do Pix:** ele chega como `BALANCE_VALUE_BLOCKED` com `balanceData.type = PIX_INFRACTION` e `balanceData.paymentId` (que pode ser nulo). O ticket que cancela o Acesso por estorno deve ouvir `PAYMENT_REFUNDED` e esse evento de saldo, tratar `paymentId` nulo e confirmar no sandbox (ou com o suporte do Asaas) se o MED também gera `PAYMENT_REFUNDED` depois.

- [ ] **Step 2: `docs/runbooks/webhook-asaas.md`** (passo a passo para leigo; legenda `[AGENTE]`/`[RESPONSÁVEL]` como no runbook do Fly; nenhum valor de segredo):
  1. **Passo zero**: o mesmo do runbook do Fly (histórico da área de transferência desligado; Bitwarden limpa a área em 30 s).
  2. **[RESPONSÁVEL] Gerar o token** (64 caracteres hexadecimais, dentro de 32–255, sem espaço, não é API Key): `$b = New-Object byte[] 32; [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); ($b | ForEach-Object { $_.ToString("x2") }) -join "" | Set-Clipboard; $b = $null` — "Gera 32 bytes aleatórios seguros, escreve em hexadecimal e põe na área de transferência." Colar no Bitwarden como `APP_ASAAS_WEBHOOK_TOKEN (homolog)` e salvar.
  3. **[RESPONSÁVEL] Gravar o segredo no Fly, antes do deploy desta versão** (sem ele a aplicação não sobe): `fly secrets set APP_ASAAS_WEBHOOK_TOKEN="$(Get-Clipboard)" --stage -a sapiensia-homolog` — "Grava o que está na área de transferência como segredo do app, sem reiniciar." Depois `Set-Clipboard -Value $null`.
  4. **[AGENTE]** `fly secrets list -a sapiensia-homolog` — devem aparecer 9 nomes.
  5. **[AGENTE]** Deploy (seção 5 do runbook do Fly) e conferência do log: `Started`, sem `Invalid app.asaas.webhook`.
  6. **[RESPONSÁVEL] Criar o webhook no sandbox** (<https://sandbox.asaas.com>, conta separada da produção): `Menu do usuário > Integrações > Webhooks > Criar Webhook`. Campos: **Nome** `sapiensia-homolog`; **URL** `https://sapiensia-homolog.fly.dev/api/webhooks/asaas`; **E-mail** a caixa que deve receber avisos de fila pausada; **API Version** 3; **Auth Token**: copiar do Bitwarden e colar (ou "Gerar token" **e** copiar na hora para o Bitwarden, porque a doc não garante que ele reaparece); **Enabled** ligado; **Interrupted** desligado; **Send Type** `SEQUENTIALLY` (ordem preservada; volume da Fase 1 é baixo); **Eventos**: `PAYMENT_CREATED`, `PAYMENT_CONFIRMED`, `PAYMENT_RECEIVED`, `PAYMENT_OVERDUE`, `PAYMENT_DELETED`, `PAYMENT_REFUNDED`, `PAYMENT_REFUND_IN_PROGRESS`, `PAYMENT_PARTIALLY_REFUNDED`, `PAYMENT_CHARGEBACK_REQUESTED`, `BALANCE_VALUE_BLOCKED`, `BALANCE_VALUE_UNBLOCKED`. Salvar. Se usou "Gerar token", repetir os passos 3 a 5 com o token gerado.
  7. **[RESPONSÁVEL] Prova real**: no sandbox, criar uma cobrança Pix de teste (Cobranças > Nova cobrança; qualquer cliente fictício do sandbox) — isso dispara `PAYMENT_CREATED`. Em `Integrações > Logs de Webhooks`, a entrega deve mostrar **200**. Se o sandbox oferecer "simular pagamento", usá-lo para ver `PAYMENT_RECEIVED`.
  8. **[AGENTE] Conferir no banco** (SQL Editor do Supabase): `select provider_event_id, event_type, received_at from payment_events order by received_at desc limit 5;` — "Mostra os últimos eventos gravados." E no log: `fly logs -a sapiensia-homolog --no-tail` com `Asaas webhook event stored`.
  9. **Se o log mostrar `Asaas webhook refused: ip=<x> reason=IP_NOT_ALLOWED`**: o sandbox usa um IP fora da lista oficial de produção (a doc avisa). **[AGENTE]** acrescenta no `fly.homolog.toml`, em `[env]`, `APP_ASAAS_WEBHOOK_ALLOWED_IPS = "52.67.12.206,18.230.8.159,54.94.136.112,54.94.183.101,<x>"` (só na homologação, nunca no `prod` de verdade), faz o deploy e, no Asaas, `Integrações > Webhooks` reativa a fila se ela pausou.
  10. **Se o log mostrar `reason=TOKEN_INVALID`**: o token do Asaas e o do Fly diferem; repetir 2 a 5 e editar o webhook.
  11. **Produção (quando chegar)**: outro token (nunca o da homologação), outro webhook na conta de produção do Asaas, lista de IPs padrão (sem `APP_ASAAS_WEBHOOK_ALLOWED_IPS`).
  12. **Rotação**: gerar token novo, `fly secrets set ... --stage`, editar o webhook no Asaas com o novo e `fly deploy` (ou `fly secrets deploy`); entre a edição no Asaas e o reinício, as entregas falham e são retentadas.
  13. **O que não fazer**: colar o token em chat, arquivo, commit, issue ou comando com valor digitado; usar uma API Key do Asaas como token; desligar a conferência de IP.

- [ ] **Step 3: `docs/runbooks/homologacao-fly.md`**: na tabela de segredos, linha `APP_ASAAS_WEBHOOK_TOKEN | Gerado pelo responsável (64 hex), guardado no Bitwarden; o mesmo valor vai no webhook do sandbox do Asaas (docs/runbooks/webhook-asaas.md) | Sem ele a aplicação não sobe em prod`; no passo 15, "Devem estar os 9 nomes"; na seção "Variáveis comuns", `APP_ASAAS_WEBHOOK_ALLOWED_IPS` (opcional, só se o sandbox precisar de IP extra).

- [ ] **Step 4: `GLOSSARY.md`** (só com D14 aprovada), depois de **Pedido**:

> **Evento de pagamento**:
> O aviso que o Asaas envia ao sistema (webhook) sobre uma cobrança ou o saldo, identificado pelo `id` do Asaas. É gravado antes de qualquer processamento, e um aviso repetido é descartado sem efeito.
> _Avoid_: Notificação, callback

- [ ] **Step 5: `CLAUDE.md`**, em "Reaproveitar antes de criar": `- backend/.../customer/ClientIpResolver.java: o único jeito de obter o IP do cliente (Fly-Client-IP em prod; X-Forwarded-For nunca). Usado pelo limite de login e pelo webhook do Asaas.`

- [ ] **Step 6: Commit**

```bash
git add docs/adr/0003-pagamento-so-por-pix-via-asaas.md docs/runbooks/webhook-asaas.md docs/runbooks/homologacao-fly.md GLOSSARY.md CLAUDE.md
git commit -m "docs: Asaas webhook decisions in ADR-0003, token and sandbox runbook (#26)"
```

---

## Task 6: Check completo e entrega

- [ ] **Step 1:** `just check-resumo` (único comando pesado; em falha, ler só as linhas que importam de `.tmp/check.log`).
- [ ] **Step 2:** `cd backend; .\mvnw.cmd clean` antes, se houver suspeita de classes velhas em `target/`.
- [ ] **Step 3:** Skill `verificar-entrega` (não há tela; "sistema de verdade" = a prova real do runbook, a cargo do Sérgio na homologação, **depois** do merge e do segredo no Fly).
- [ ] **Step 4:** Relatório ao Sérgio e pedido de OK para push e PR (`Closes #26`). Issues a abrir com `needs-triage`, citando o #26: (a) o ticket de estorno/MED precisa ouvir `BALANCE_VALUE_BLOCKED` + `PIX_INFRACTION` e tratar `paymentId` nulo; (b) renomear `app.login-limit.client-ip-header` para um nome neutro (`app.client-ip-header`), se o Sérgio quiser.

## 6. Como o fluxo real será provado (sem e2e)

Não há tela, então não entra teste em `frontend/e2e/`. A prova em três camadas:

1. **Integração com Testcontainers e HTTP real** (Tasks 2–4): IP de conexão real por loopback, `ForwardedHeaderFilter` ligado como em `prod`, banco PostgreSQL 18 real, concorrência real com duas threads.
2. **CI** (`just check` em Linux): a imagem `prod` sobe com `APP_ASAAS_WEBHOOK_TOKEN` fictício, provando que a propriedade obrigatória está ligada.
3. **Homologação** (runbook `webhook-asaas.md`, a cargo do Sérgio depois do merge): webhook criado no sandbox do Asaas, cobrança Pix de teste, entrega `200` nos Logs de Webhooks do Asaas e a linha em `payment_events` no Supabase. É aqui que se descobre o IP do sandbox e se confirma o `Content-Type`.

## 7. Textos que o usuário vê

Nenhum. Não há tela, e-mail nem mensagem nova. As respostas HTTP ao Asaas são status e o código `BODY_INVALID` num `ProblemDetail`, que nenhuma pessoa lê.

## 8. O que depende de decisão do Sérgio

1. **D14**: acrescentar "Evento de pagamento" ao `GLOSSARY.md` (decisão de domínio).
2. **D10/400 para corpo inválido** (em vez de 200 "engolindo"): o plano prefere a falha visível; se o Sérgio preferir nunca pausar a fila, a alternativa é gravar o corpo inválido numa tabela de rejeitados e responder 200 (mais código; não recomendado).
3. **D11**: recusas só em log, sem `audit_events` (para não deixar a rota pública escrever no banco).
4. **Eventos a assinar no webhook do sandbox** (lista do runbook, passo 6): o #26 grava qualquer um; a lista só define o que o Asaas manda.
5. **Send Type `SEQUENTIALLY`** no Asaas: o plano recomenda pela ordem; o código funciona nos dois modos.

## 9. Riscos

- **Supabase liga RLS automaticamente em tabela nova** (`rls_auto_enable`): a aplicação grava com o usuário dono (`postgres.<ref>`), que não é afetado pela RLS; já comprovado com `audit_events` na homologação (ADR-0004). Se o insert em `payment_events` falhar em homologação, é isso: conferir no painel do Supabase.
- **Deploy desta versão sem o segredo**: a aplicação não sobe (`Invalid app.asaas.webhook.token`). O runbook põe o `fly secrets set --stage` **antes** do deploy.
- **Sandbox com IP fora da lista**: previsto (D3, runbook passo 9).
- **Teste de concorrência instável**: 2 threads e `CountDownLatch`; se oscilar, aumentar repetições, nunca afrouxar a asserção `count = 1`.
- **Bind em `127.0.0.x`**: funciona no Windows e no Linux do CI (já usado em `FlyClientIpLoginLimitTest`).
