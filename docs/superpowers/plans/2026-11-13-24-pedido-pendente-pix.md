# Pedido pendente com Pix (#24) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** APROVADO pelo Sérgio em 13/11/2026, com D1(a), D2 provisório (ele lê os textos no diff da PR), D3 a D8 e D10 como recomendado; D9 fica para antes do deploy na homologação. Três ajustes pedidos por ele já estão incorporados abaixo:
- **A1.** Tempo limite curto nas chamadas ao Asaas: 3 s para conectar e 10 s para responder, no máximo. Teste de que uma chamada lenta devolve `502` com código fixo e não esgota o pool de conexões do banco (Tasks 3 e 4).
- **A2.** Se o Asaas criou a cobrança e a gravação no banco falhou, a cobrança é excluída no Asaas em melhor esforço, sem mudar a resposta ao Cliente. Com teste (Task 4).
- **A3.** QR Code e copia e cola são buscados no Asaas a cada visualização do Pedido; nunca gravados no banco nem em log. Com teste (Tasks 1, 4 e 6).

**Goal:** O Cliente logado escolhe um Produto publicado e gera um Pedido pendente com uma cobrança Pix de 30 minutos no Asaas: vê o QR Code, o código copia e cola e a contagem regressiva. O preço vem sempre do servidor, o CPF passa pelo sistema só para o Asaas gerar a cobrança, e o Visitante é levado a entrar e volta à compra.

**Architecture:** Tudo no pacote `payment` (onde já está o webhook do #26). Uma interface `PixGateway` isola o Asaas: `AsaasPixGateway` (HTTP de verdade) em `prod`, `FakePixGateway` (em memória) no perfil local e nos testes. `OrderService.create` roda numa transação que segura uma trava do PostgreSQL por Cliente (`pg_try_advisory_xact_lock`): dentro dela reaproveita o Pedido pendente ou chama o Asaas e só então insere o Pedido, já completo. Não existe Pedido sem cobrança. O código copia e cola não é guardado: `GET /api/orders/{id}` o busca no Asaas a cada visualização, fora de transação (A3). Um índice único parcial (um Pedido `PENDING` por Cliente e Produto) é a segunda barreira. Uma tarefa do db-scheduler, a cada minuto, cancela a cobrança no Asaas e marca o Pedido como expirado.

**Tech Stack:** Spring Boot 4.1 (Spring Security 7, `JdbcClient`, `RestClient`, Jackson 3), Flyway, PostgreSQL, db-scheduler, JUnit 5, Testcontainers; React 19, TanStack Query, React Hook Form + Zod, `qrcode.react` (já é dependência, usada no MFA), Vitest, Playwright. **Nenhuma dependência nova.**

**Spec:** `docs/spec/sapiensia-spec.md` (FR-019, FR-020, SEC-010, SEC-011, SEC-012, SEC-018, SEC-022, SEC-033, seção 8 `Order`/`PaymentAttempt`); issue #24 (pai #5); `docs/adr/0003-pagamento-so-por-pix-via-asaas.md`; `docs/adr/0006-meta-de-seguranca-asvs-nivel-2.md`; `docs/runbooks/webhook-asaas.md`; `GLOSSARY.md` (Pedido, Cliente, Visitante, Acesso); documentação oficial do Asaas (seção 0).

## IDs cobertos

| ID | Como | Onde |
|---|---|---|
| FR-019 | Pedido com `id` próprio (UUID) e cobrança com o `id` do Asaas (`provider_payment_id`, `UNIQUE`); o `id` do Pedido vai no `externalReference` da cobrança | Tasks 1, 3, 4 |
| SEC-012 | O corpo do `POST /api/orders` só tem `productId` e `cpf`; preço, valor e estado saem do banco. Campo extra de preço é ignorado. Pedido de outro Cliente responde 404 | Task 4 |
| SEC-033 | O CPF é pedido só na primeira compra, com a justificativa na tela; não é gravado em tabela, log, auditoria nem evento. Guardamos só o código `cus_...` | Tasks 2, 4, 6, 7 |
| SEC-011 | `GET /api/orders/{id}` filtra por `customer_id` no SQL; outro Cliente recebe 404 vazio | Task 4 |
| SEC-010 | Duas rotas novas liberadas na `SecurityConfig` só para `ROLE_CUSTOMER`; o resto de `/api/orders/**` continua negado | Task 4 |
| SEC-018 | Falha do Asaas vira `502` com um código fixo; o corpo da resposta do Asaas nunca sai | Tasks 3, 4, 6 |
| SEC-022 | Chave de API só em Fly secret (`APP_ASAAS_API_KEY`), uma por ambiente | Tasks 2, 10 |
| ADR-0003 | Adaptador interno, Pix de 30 minutos por regra nossa, cobrança cancelada no Asaas ao expirar, CPF não guardado | Tasks 2 a 5, 10 |

## 0. O que a documentação oficial do Asaas diz (consulta em 13/11/2026)

Fontes: [criar cobrança](https://docs.asaas.com/reference/criar-nova-cobranca), [QR Code Pix](https://docs.asaas.com/reference/obter-qr-code-para-pagamentos-via-pix), [criar cliente](https://docs.asaas.com/reference/criar-novo-cliente), [listar clientes](https://docs.asaas.com/reference/listar-clientes), [excluir cobrança](https://docs.asaas.com/reference/excluir-cobranca), [chaves de API](https://docs.asaas.com/docs/chaves-de-api), [cobranças via Pix](https://docs.asaas.com/docs/cobrancas-via-pix), [autenticação](https://docs.asaas.com/docs/autentica%C3%A7%C3%A3o-1), [sandbox](https://docs.asaas.com/docs/sandbox).

### 0.1 Confirmado

- **Endereços:** sandbox `https://api-sandbox.asaas.com/v3`, produção `https://api.asaas.com/v3`. As chaves dos dois ambientes são diferentes (prefixos `$aact_hmlg_` e `$aact_prod_`).
- **Autenticação:** cabeçalho `access_token` (não é `Authorization: Bearer`). `User-Agent` é obrigatório para contas criadas a partir de 13/06/2024.
- **Chave de API:** só um administrador da conta cria, em `Integrações > Chaves de API`. "A chave de API é exibida apenas uma vez e não pode ser recuperada posteriormente." Até 10 chaves por conta. Chave sem uso por 3 meses é desativada; por 6 meses, expira de vez.
- **Cliente:** `POST /v3/customers`, obrigatórios `name` e `cpfCnpj`; opcionais `externalReference` e `notificationDisabled`. Resposta traz `id` (`cus_...`). "A API permite a criação de clientes duplicados." `GET /v3/customers?externalReference=...` devolve `{ data: [...], totalCount }`.
- **Cobrança:** `POST /v3/payments`, obrigatórios `customer`, `billingType` (`PIX`), `value` (número em reais) e `dueDate` (só data); opcionais `description` (até 500) e `externalReference`. Resposta traz `id` (`pay_...`) e `status`. **A página não oferece chave de idempotência.**
- **QR Code:** `GET /v3/payments/{id}/pixQrCode` devolve `encodedImage` (PNG em Base64), `payload` ("Copia e Cola do QrCode") e `expirationDate`. O QR Code "expira 12 meses após a data de vencimento": os 30 minutos são regra nossa (ADR-0003).
- **Cancelar:** `DELETE /v3/payments/{id}` devolve `{ deleted, id }` e dispara `PAYMENT_DELETED`.
- **Erros:** `400` e `401` com `{ "errors": [ { "code", "description" } ] }`.
- **Chave Pix:** "Recomendamos manter uma chave Pix cadastrada na conta." Sem ela o QR Code ainda é gerado, mas por uma instituição parceira e pagável só até 23:59 do dia; essa saída será descontinuada.
- **Sandbox:** depois de criar uma cobrança Pix, a interface mostra o botão "Confirmar pagamento" para simular o recebimento.

### 0.2 Não confirmado (o plano não supõe; a implementação é defensiva)

| O quê | Como o plano lida |
|---|---|
| Nome exato do código de erro de CPF recusado | Lemos só os `code` (nunca `description`); se algum contiver `cpfCnpj` (sem distinguir maiúsculas), a resposta é `CPF_INVALID`; qualquer outro `400` é falha do provedor |
| Valor mínimo de uma cobrança Pix | Preço abaixo do mínimo daria `400` do Asaas e a mensagem genérica. Confere-se no sandbox na homologação (seção 10) |
| Se o Asaas aceita um e-mail no campo `name` | Decisão D1 da seção 11; confere-se no sandbox |
| Quais estados de cobrança aceitam o `DELETE` | `404` conta como já cancelada; outro `4xx` marca o Pedido como expirado e registra WARN (o webhook dirá se foi paga) |
| Limites de requisição da API | Volume da Fase 1 é baixo; `429` é tratado como indisponibilidade |
| Rótulos exatos dos botões da tela de chaves de API | O runbook diz o caminho do menu e avisa que o rótulo pode variar |

## Global Constraints

- Todo endpoint começa com `/api`. Rotas novas: `POST /api/orders` e `GET /api/orders/{id}`, liberadas na `SecurityConfig` com `hasRole("CUSTOMER")` antes do `denyAll`. Nenhum `permitAll`. O CSRF continua ligado.
- Código, commits, nomes e comentários em inglês. Textos de tela em pt-BR, **só os da seção 8**; texto fora dela volta ao Sérgio antes do push.
- Nenhuma dependência nova (backend e frontend).
- Dinheiro em centavos inteiros (`int`), do banco até a tela. A única conversão é no adaptador: `BigDecimal.valueOf(priceCents, 2)` para o campo `value` do Asaas.
- **Nunca** em log, auditoria, exceção, resposta de erro, `toString` ou tabela: CPF, código copia e cola, QR Code, chave de API, corpo de requisição ou de resposta do Asaas. Em log só: id do Pedido, id da cobrança (`pay_...`), etapa, status HTTP, nome da classe da exceção e os `code` de erro do Asaas reduzidos a `[A-Za-z0-9_.]{1,64}`.
- O CPF não tem coluna em nenhuma tabela, não entra em sessão HTTP, `localStorage`, `sessionStorage` nem URL.
- O código copia e cola e o QR Code não têm coluna em nenhuma tabela e não entram no cache do navegador (`Cache-Control: no-store`) nem no `localStorage` (A3).
- Toda chamada ao Asaas: 3 s para conectar e 10 s para responder, no máximo (`app.asaas.api.connect-timeout: PT3S`, `app.asaas.api.read-timeout: PT10S`); o adaptador recusa subir com valores maiores (A1).
- Segredo novo `APP_ASAAS_API_KEY`: sem valor em arquivo algum. O perfil local e os testes usam `app.pix.provider=fake` e sobem sem a chave. Em `prod` a aplicação não sobe sem ela.
- Migration `V19__create_orders.sql` criada pela skill `nova-migration` (a última é a V18). Nenhuma migration existente é editada.
- Controller e repositórios package-private, DTOs como `record`, exceção de negócio convertida no `ApiExceptionHandler`. Teste que prova requisito leva o ID no `@DisplayName` (backend) ou no título (frontend e e2e). Sem `@MockitoBean` em teste de integração.
- Toda chamada do frontend passa por `apiFetch`. IP do cliente não é usado neste ticket.
- Um comando pesado por vez (suíte do Maven, `just check-rapido`, `just imagem`, e2e); antes de cada um, conferir 2 GB de memória livres. Durante o trabalho só `just check-rapido`; o completo roda no CI.
- Teste focado (`-Dtest=...`): incluir também as classes que já citam os IDs tocados (`git grep -l "SEC-012\|SEC-033\|FR-019\|SEC-011" -- backend/src/test`) e `RequestRecordsRedactionTest`.
- Valor falso que imita segredo é montado em tempo de execução nos testes (`"$aact_" + "hmlg_" + ...`), nunca como literal (gitleaks).

## Review Focus

1. **Duplo clique, duas abas ou a mesma requisição repetida depois de um timeout:** uma só cobrança no Asaas e um só Pedido; a segunda requisição recebe o mesmo Pedido ou `409`. Teste de concorrência na Task 4.
2. **Asaas lento, fora do ar ou respondendo lixo** (timeout, `500`, `401`, `200` sem `id`, `200` sem `payload`, JSON quebrado): no `POST`, nenhum Pedido gravado; no `GET`, o Pedido continua lá; nos dois, `502` com código fixo, nada do corpo vaza e o pool do banco não esgota. Tasks 3 e 4.
3. **CPF digitado como as pessoas digitam** (com pontos e traço, com espaços, 11 dígitos iguais, dígito verificador errado, letras, colado com quebra de linha): normalizado ou recusado com a mesma mensagem, e nunca devolvido na resposta. Tasks 2, 4 e 7.
4. **A fronteira dos 30 minutos:** página aberta depois do prazo e antes de a tarefa rodar mostra "expirou" e não entrega o código; comprar de novo em 30:01 gera Pedido novo; Asaas fora do ar na hora de cancelar não trava a fila. Task 5.
5. **Entrada adulterada:** id de Pedido de outro Cliente, `priceCents` no corpo, `productId` de rascunho, caminho de retorno forjado no `localStorage` apontando para outro site. Tasks 4 e 9.

---

## 1. Modelo do Pedido e como o #28 encaixa

**Decisão:** uma tabela `orders` com a cobrança dentro dela, e uma tabela `payment_customers` com o código do Cliente no Asaas. **Sem** tabela `payment_attempts` e **sem** `order_items`.

```sql
-- The Asaas customer code of a Customer; the CPF is never stored (ADR-0003, SEC-033).
create table payment_customers (
  customer_id uuid primary key references customers (id) on delete cascade,
  provider_customer_id varchar(64) not null,
  created_at timestamp with time zone not null default now(),
  constraint payment_customers_provider_customer_id_key unique (provider_customer_id)
);

-- One Order is one Product and one Pix charge (FR-019). price_cents is the server price when the Order was created.
-- The Pix copy-paste code and QR Code are read from Asaas on every view and never stored.
create table orders (
  id uuid primary key,
  customer_id uuid not null references customers (id),
  product_id uuid not null references products (id),
  price_cents integer not null check (price_cents > 0),
  status varchar(16) not null check (status in ('PENDING', 'EXPIRED')),
  provider_payment_id varchar(64) not null,
  created_at timestamp with time zone not null,
  expires_at timestamp with time zone not null,
  expired_at timestamp with time zone,
  constraint orders_provider_payment_id_key unique (provider_payment_id)
);
create unique index orders_one_pending_per_customer_product on orders (customer_id, product_id) where status = 'PENDING';
create index orders_pending_expires_at on orders (expires_at) where status = 'PENDING';
```

| Escolha | Por quê | Custo se estiver errada |
|---|---|---|
| Cobrança dentro de `orders` | O glossário define Pedido como um Produto e um Pix. Uma tabela de tentativas seria 1 para 1. FR-019 pede IDs únicos para pedido e pagamento: temos os dois (`id` e `provider_payment_id`) | Se um dia houver segunda tentativa no mesmo Pedido, uma migration extrai a tabela |
| `provider_payment_id not null` | O Pedido só é inserido depois que o Asaas criou a cobrança. Não existe estado "criando" | Nenhum |
| Estados só `PENDING` e `EXPIRED` | É o que este ticket escreve. O projeto alarga o `check` por migration quando o estado nasce (V13 para V15 fez isso com o Produto) | Nenhum: o #28 já teria de criar migration |
| `payment_customers` separada de `customers` | A tabela `customers` é do pacote `customer`; o código do Asaas é do pagamento. Evita mexer na entidade JPA | Uma consulta a mais na primeira compra |
| Copia e cola e QR Code nunca gravados (A3) | Cada `GET /api/orders/{id}` de um Pedido pendente busca o `payload` em `GET /v3/payments/{id}/pixQrCode`; recarregar mostra o mesmo código porque a cobrança é a mesma. O QR Code é desenhado no navegador a partir do código (`qrcode.react`), então a imagem do Asaas nem trafega. Pedido expirado não chama o Asaas | Com o Asaas fora do ar, a tela do Pedido mostra o erro e "Tentar novamente" em vez do código; o Pedido não se perde |
| Expiração em `expires_at` (`created_at` + `app.orders.pix-ttl`, `PT30M`) | Regra nossa (ADR-0003); o `dueDate` do Asaas é só a data de `expires_at` no fuso `America/Sao_Paulo` | Nenhum |

**Como o #28 encaixa** (ele depende deste ticket, além do #26):

- Acha o Pedido pelo `payment.id` do evento em `orders.provider_payment_id` (`UNIQUE`) e confere `payment.externalReference = orders.id` e `payment.value = price_cents / 100` (SEC-026).
- Migration própria: alarga o `check` para incluir `PAID`, acrescenta `paid_at` e cria a tabela do Acesso com `unique (customer_id, product_id)`.
- Transição condicional, que tolera evento repetido e fora de ordem: `update orders set status = 'PAID', paid_at = now() where id = :id and status in ('PENDING', 'EXPIRED')`. O `EXPIRED` na lista cumpre a regra do ADR-0003 (pago depois de expirado vira pago). O `GET` de um Pedido pago não chama o Asaas.
- A tarefa de expiração deste ticket só toca `PENDING`: nunca desfaz um `PAID`.
- Acrescenta ao `PixGateway` a leitura da cobrança ("consultando o Asaas quando preciso") e, na tela do Pedido, a consulta periódica do estado. Este ticket não faz nenhum dos dois.
- Bloquear a compra de um Produto que o Cliente já tem é do #28 (o Acesso nasce lá).

## 2. Preço sempre do servidor

- `CreateOrderRequest(UUID productId, String cpf)`: não existe campo de preço. Campo desconhecido no JSON (`priceCents`, `value`, `amount`, `status`) é ignorado pelo Jackson (padrão do Spring Boot) e um teste prova que a cobrança sai com o preço do banco.
- O preço é lido uma vez, por `CatalogService.get(productId)`, que só enxerga `status = 'PUBLISHED'` no SQL e lança `ProductNotFoundException` para rascunho, despublicado ou inexistente: é o mesmo 404 vazio do Catálogo. `CatalogService`, `get` e `CatalogProduct` passam a `public` (regra: outra funcionalidade usa o Service e os DTOs).
- O mesmo `int priceCents` vai para `orders.price_cents` e para o adaptador. Se o Administrador mudar o preço enquanto há um Pedido pendente, esse Pedido mantém o preço com que nasceu por até 30 minutos (a cobrança já existe com esse valor).

## 3. CPF

- Chega no corpo do `POST /api/orders`, é validado no serviço (classe `Cpf`: tira `.`, `-` e espaços; exige 11 dígitos, não todos iguais, com os dois dígitos verificadores certos) e é entregue ao `PixGateway.createCustomer`. Depois disso a variável sai de escopo.
- **Validação no serviço, não por Bean Validation:** a mensagem padrão de `MethodArgumentNotValidException` cita o valor recusado; no serviço, nenhuma mensagem do framework carrega o CPF.
- `CreateOrderRequest` e o corpo enviado ao Asaas (`record` interno do adaptador) têm `toString()` redigido: o Spring registra objetos de corpo pelo `toString` em DEBUG.
- Só na primeira compra: se `payment_customers` já tem o código do Cliente, o `cpf` do corpo é ignorado.
- Dados enviados ao Asaas no cadastro do cliente: `name` (decisão D1), `cpfCnpj`, `externalReference` (o UUID do Cliente) e `notificationDisabled: true` (o Asaas não manda e-mail nem SMS ao Cliente; a cobrança só é pagável pelo código que mostramos).
- Antes de criar, o adaptador procura `GET /v3/customers?externalReference=<uuid>`: se uma tentativa anterior criou o cliente no Asaas e falhou depois, o código é reaproveitado e o CPF nem é pedido de novo.

**Como é provado por teste:**

| Prova | Teste |
|---|---|
| Nenhuma tabela guarda o CPF | `OrderCpfNotStoredTest`: cria um Pedido com o CPF `52998224725` e percorre **todas** as tabelas do schema `public` (`information_schema.tables`), contando linhas cujo texto (`t::text`) contenha `52998224725` ou `529.982.247-25`. Esperado: zero em todas, incluindo `orders`, `payment_customers`, `audit_events`, `scheduled_tasks` e `payment_events`. O teste confere também que o falso **recebeu** o CPF, para não passar no vazio |
| Nenhum log carrega o CPF | `OrderLogTest` com `OutputCaptureExtension` e níveis TRACE/DEBUG (Task 6) |
| O adaptador não vaza o CPF quando o Asaas o devolve num erro | `AsaasPixGatewayWireTest`: o servidor de teste responde `400` com o CPF na `description`; a exceção e o log não o contêm (Task 3) |
| `toString` redigido | `RequestRecordsRedactionTest` ganha `cpf` na lista de palavras sensíveis e passa a exigir `CreateOrderRequest` |
| Evento de pagamento | Já coberto pelo #26 (`ADR-0003: shouldRedactACpfIfTheProviderEverSendsOne`); nada muda |

## 4. Duplicidade

Fluxo de `OrderService.create(customerId, customerEmail, request)`, dentro de **uma** transação (`TransactionTemplate`):

1. `select pg_try_advisory_xact_lock(hashtextextended(:key, 0))` com `key = "order:" + customerId`. Não conseguiu: `OrderInProgressException` (`409`, `errors.order = ORDER_IN_PROGRESS`). A trava solta sozinha no fim da transação, mesmo se a aplicação cair.
2. `catalog.get(productId)` (404 vazio).
3. Existe Pedido `PENDING` deste Cliente e Produto? Se `expires_at` está no futuro, **devolve o mesmo Pedido** (`200`): mesmo código, tempo restante. Se já passou, expira-o agora (seção 5) e segue.
4. Sem código do Cliente no Asaas: procura por `externalReference`; não achou e o `cpf` não veio, `CpfRequiredException` (`400`, `errors.cpf = CPF_REQUIRED`); `cpf` inválido, `CPF_INVALID`; senão cria o cliente e grava `payment_customers`.
5. `gateway.createCharge(...)` com um `orderId` novo (`UUID.randomUUID()`) no `externalReference`.
6. Insere o Pedido completo e responde `201` com `Location: /api/orders/{id}` e o corpo `{ "id": "<uuid>" }`. O reaproveitamento do passo 3 responde `200` com o mesmo corpo. O `POST` não devolve o código Pix: a tela vai para `/pedidos/{id}`, cujo `GET` o busca no Asaas (A3).

**Gravação falhou depois de a cobrança existir (A2).** Qualquer exceção depois do passo 5 (insert recusado pelo índice único, banco fora do ar, falha no commit): já fora da transação, `gateway.cancelCharge(chargeId)` em melhor esforço e a exceção original segue para o Cliente **sem mudança** (o índice único dá `409 ORDER_IN_PROGRESS`; o resto, o `500` genérico de hoje). Se o cancelamento também falhar, a resposta continua a mesma e fica só `WARN "Pix charge left without an Order: chargeId=pay_..."` (o id da cobrança não é segredo e serve para a limpeza à mão).

**Leitura (`GET /api/orders/{id}`).** Lê a linha (filtrada por `id` e `customer_id`) sem transação aberta, devolve a conexão e só então, se o Pedido está `PENDING` e no prazo, chama `gateway.readCopyPaste(providerPaymentId)`. Expirado (no banco ou pelo relógio) não chama o Asaas. Falha do Asaas: `502 PAYMENT_PROVIDER_UNAVAILABLE`, e o Pedido continua intacto.

| Caso | Resultado |
|---|---|
| Duplo clique na mesma aba | O botão fica desabilitado enquanto a requisição corre; se mesmo assim duas chegarem, a segunda recebe `409` e a tela pede para tentar de novo, e aí recebe o mesmo Pedido |
| Duas abas, mesmo Produto | Uma cria; a outra recebe `409` (durante a criação) ou o mesmo Pedido (depois) |
| Requisição repetida (rede caiu, o navegador reenviou) | Recebe o mesmo Pedido pendente, `200` |
| Pedido pendente do mesmo Produto e Cliente | Reaproveitado até expirar: nunca duas cobranças abertas do mesmo Produto para o mesmo Cliente |
| Duas abas, Produtos diferentes | Um por vez (a trava é por Cliente); dois Pedidos, uma cobrança cada |
| Duas máquinas da aplicação | A trava é do banco, vale entre máquinas; o índice único parcial é a segunda barreira |

**Custo assumido:** a transação do `POST` segura uma conexão do banco enquanto fala com o Asaas (no pior caso, 3 chamadas de até 3 s para conectar e 10 s para responder; a primeira que estourar o tempo encerra a requisição com `502` e solta a conexão). No máximo uma conexão por Cliente, porque quem não pega a trava sai na hora. O `GET` não segura conexão durante a chamada. Com o Asaas lento e 10 ou mais Clientes comprando ao mesmo tempo, o pool (10 conexões por padrão) pode ficar ocupado por alguns segundos, mas sempre volta: o tempo limite garante que nenhuma conexão fica presa (teste da A1 na Task 4). É aceitável no volume da Fase 1 e some se o #42 limitar a taxa; a alternativa (gravar um Pedido "criando" antes de chamar o Asaas) traz um estado a mais e um Pedido preso se a aplicação cair no meio.

## 5. Adaptador do Asaas, falso e segredo

```java
public interface PixGateway {
    Optional<String> findCustomerCode(UUID customerId);          // GET /v3/customers?externalReference=
    String createCustomer(UUID customerId, String name, String cpf); // POST /v3/customers -> "cus_..."
    String createCharge(String customerCode, UUID orderId, int amountCents, LocalDate dueDate, String description); // -> "pay_..."
    String readCopyPaste(String chargeId);                       // GET /v3/payments/{id}/pixQrCode -> payload; never stored (A3)
    void cancelCharge(String chargeId);                          // DELETE /v3/payments/{id}; 404 is success
}
public final class PixGatewayException extends RuntimeException { // message: step + "HTTP nnn" or exception class name
    public enum Kind { DOCUMENT_REJECTED, REFUSED, UNAVAILABLE }
}
```

- `createCharge` faz só `POST /v3/payments` (`billingType: "PIX"`, `value`, `dueDate`, `description = "SapiensIA: " + título`, `externalReference = orderId`) e devolve o `id`. `readCopyPaste` faz `GET /v3/payments/{id}/pixQrCode` e devolve só o `payload` (a `encodedImage` é ignorada).
- Tempo limite (A1): `JdkClientHttpRequestFactory` com `HttpClient.connectTimeout` = `app.asaas.api.connect-timeout` (`PT3S`) e `setReadTimeout` = `app.asaas.api.read-timeout` (`PT10S`). Valor acima de 3 s ou 10 s, zero ou negativo: `IllegalStateException` na subida. Timeout vira `Kind.UNAVAILABLE`.
- `Kind`: `DOCUMENT_REJECTED` = `400` no cadastro do cliente com um `code` contendo `cpfCnpj`; `REFUSED` = outro `4xx`; `UNAVAILABLE` = timeout, erro de rede, `5xx`, `429`, `401`/`403` (chave inválida: `log.error`, sem a chave) e `2xx` sem os campos esperados.
- `AsaasPixGateway`: `@ConditionalOnProperty(app.pix.provider=asaas)`, `RestClient` com `JdkClientHttpRequestFactory` (os tempos limite acima), cabeçalhos `access_token` e `User-Agent: sapiensia`, construtor com `RestClient.Builder` como costura de teste (padrão de `ResendEmailSender` e `SupabaseProductFileStorage`). Chave em branco: `IllegalStateException("APP_ASAAS_API_KEY is required when app.pix.provider=asaas")`. `base-url` fora de `https://api-sandbox.asaas.com`, `https://api.asaas.com` ou `http://localhost:<porta>`/`http://127.0.0.1:<porta>`: `IllegalStateException` (a chave nunca é enviada a outro endereço).
- `FakePixGateway`: `@ConditionalOnProperty(app.pix.provider=fake)`, em memória, recusa subir no perfil `prod` (padrão de `FileProductFileStorage`). Devolve `cus_fake_...`, `pay_fake_...` e, em `readCopyPaste`, o código `PIX-FICTICIO-NAO-PAGAVEL-<chargeId>` (o mesmo a cada leitura da mesma cobrança).
- Nos testes de integração, `SwitchablePixGateway` (`@Primary`, padrão de `SwitchableProductFileStorage`) embrulha o falso, registra as chamadas e falha sob comando.

**Configuração**

| Onde | Valor |
|---|---|
| `application.yml` | `app.pix.provider: fake`; `app.orders.pix-ttl: PT30M`; `app.asaas.api.connect-timeout: PT3S`; `app.asaas.api.read-timeout: PT10S` |
| `application-local.yml` | `app.pix.provider: ${APP_PIX_PROVIDER:fake}`; `app.asaas.api.base-url: ${APP_ASAAS_API_BASE_URL:https://api-sandbox.asaas.com}`; `app.asaas.api.key: ${APP_ASAAS_API_KEY:}`. Sem nenhuma variável, sobe com o falso. Com as três no PowerShell, fala com o sandbox (opcional) |
| `application-prod.yml` | `app.pix.provider: asaas`; `base-url: ${APP_ASAAS_API_BASE_URL}` (sem padrão); `key: ${APP_ASAAS_API_KEY:}` (em branco, não sobe) |
| `src/test/resources/application.properties` | `app.pix.provider=fake`, `app.orders.pix-ttl=PT30M`, `app.asaas.api.connect-timeout=PT3S`, `app.asaas.api.read-timeout=PT10S`, `app.asaas.api.base-url=https://api-sandbox.asaas.com`, `app.asaas.api.key=` |
| `fly.homolog.toml`, `[env]` | `APP_ASAAS_API_BASE_URL = "https://api-sandbox.asaas.com"` (endereço, não é segredo) |
| Fly secret | `APP_ASAAS_API_KEY` (chave do **sandbox** na homologação; a produção terá outra) |
| `.env.example` | `APP_ASAAS_API_KEY=` e `APP_ASAAS_API_BASE_URL=`, sem valor |
| `ci.yml` (imagem em `prod`) | Um stub HTTP do Asaas (`.github/asaas-stub.mjs`, porta 54322, padrão do stub do Storage); `APP_ASAAS_API_BASE_URL=http://localhost:54322` e `APP_ASAAS_API_KEY=ci-fake-asaas-key-not-real` |

**Passo a passo para o Sérgio obter a chave** (vai para `docs/runbooks/asaas-chave-api.md`, com os comandos; resumo):

1. Desligue o histórico da área de transferência do Windows (o "Passo zero" do `docs/runbooks/webhook-asaas.md`).
2. Entre em <https://sandbox.asaas.com> com a conta do sandbox (a mesma em que o webhook foi criado). É uma conta separada da produção; nada ali movimenta dinheiro de verdade.
3. Clique no seu nome ou foto, no canto superior direito, e abra `Integrações`. Entre na aba `Chaves de API`.
4. Peça uma chave nova (o rótulo do botão pode variar: "Gerar chave de API" ou "Nova chave"). Dê o nome `sapiensia-homolog`. Sem data de expiração.
5. A chave aparece **uma única vez**. Copie e cole no Bitwarden com o nome `APP_ASAAS_API_KEY (homolog)`, salve, e só então feche a janela do Asaas. Ela começa com `$aact_hmlg_`; se começar com `$aact_prod_`, você está na conta errada: pare.
6. No PowerShell, na pasta do projeto, com a chave copiada do Bitwarden: `fly secrets set APP_ASAAS_API_KEY="$(Get-Clipboard)" --stage -a sapiensia-homolog` (grava o segredo sem reiniciar o app) e depois `Set-Clipboard -Value $null` (esvazia a área de transferência).
7. Ainda no sandbox, em `Pix > Minhas chaves` (o nome do menu pode variar), cadastre uma chave Pix aleatória: o Asaas recomenda e, sem ela, o QR Code depende de uma saída provisória.
8. Nunca cole a chave no chat, em arquivo, commit ou issue. A chave não é o token do webhook: são dois segredos diferentes.

Esse segredo é necessário **antes do deploy desta versão na homologação**, não antes do merge: sem ele o perfil `prod` não sobe. O CI não precisa dele.

## 6. Segurança

| Ponto | Decisão | Teste |
|---|---|---|
| Rotas | `POST /api/orders` e `GET /api/orders/{id}` com `hasRole("CUSTOMER")`; `PUT`, `DELETE`, `GET /api/orders` e `/api/orders/{id}/qualquer-coisa` continuam no `denyAll` | `OrderRouteAccessTest` |
| Anônimo | `401` vazio | idem |
| Administrador logado (sem papel de Cliente) | `403` | idem |
| `POST` sem token CSRF | `403` | idem |
| Pedido de outro Cliente | `404` vazio, igual ao de um id que não existe (o SQL filtra `id` **e** `customer_id`) | `SEC-011: shouldAnswer404ForAnotherCustomersOrder` |
| Preço do navegador | Ignorado | `SEC-012: shouldChargeTheServerPriceWhateverTheBodySays` |
| Produto não publicado | `404` vazio para rascunho, despublicado e inexistente; nenhuma chamada ao Asaas | `SEC-012: shouldRefuseADraftAnUnpublishedAndAMissingProductAlike` |
| Falha do Asaas | `502`, `errors.payment = PAYMENT_PROVIDER_UNAVAILABLE`; nenhuma linha em `orders`; a mesma resposta para timeout, `4xx` e `5xx` | `SEC-018: shouldStoreNoOrderWhenTheProviderFails` (parametrizado por etapa e por tipo de falha) |
| Asaas lento (A1) | Corte em 3 s/10 s; `502` com o código fixo; a conexão do banco volta ao pool | `SEC-018: shouldAnswer502AndFreeThePoolWhenTheProviderIsSlow` (`OrderSlowProviderTest`) |
| Cobrança criada e gravação falhou (A2) | Cobrança excluída no Asaas em melhor esforço; resposta igual à que seria sem a exclusão | `ADR-0003: shouldCancelTheChargeWhenTheOrderCannotBeStored` e `...EvenWhenTheCancelFails` |
| Código Pix (A3) | Buscado no Asaas a cada `GET`; nenhuma tabela o guarda; Asaas fora do ar no `GET` dá `502` sem apagar o Pedido | `ADR-0003: shouldReadThePixCodeFromTheProviderOnEveryView`, `shouldStoreThePixCodeInNoTableAtAll` |
| Resposta com o código Pix | Leva `Cache-Control: no-store` (padrão do Spring Security, conferido em teste) | `shouldNeverCacheAnOrderResponse` |
| Log | Seção Global Constraints | `OrderLogTest` (Task 6) |
| Limite de taxa | **Nenhum neste ticket** (fica no #42). A trava por Cliente e o reaproveitamento do Pedido pendente limitam cada conta a uma cobrança por Produto a cada 30 minutos | Não há |
| Auditoria | Não há linha em `audit_events` (ela é do painel); a tabela `orders` é o registro | Não há |

## 7. Visitante sem login

- Na página do Produto, o botão "Comprar com Pix" aparece para todos. Sem sessão (ou se o `POST` responder `401`), a tela grava o caminho de volta e leva a `/entrar`.
- O caminho fica em `localStorage` (chave `sapiensia.returnTo`, com a hora), não na URL, porque **criar conta** passa por um link de e-mail que abre outra aba: a URL se perderia, o `localStorage` do mesmo navegador não. Vale 60 minutos e é apagado ao ser usado.
- Depois do login, a `LoginPage` lê o caminho e só o segue se casar com `^/catalogo/<uuid>$` ou `^/pedidos/<uuid>$`; qualquer outra coisa (outro site, `//`, `javascript:`) é descartada e o Cliente vai para a página inicial, como hoje.
- A `LoginPage` mostra um aviso quando há um retorno pendente (texto na seção 8). Com o `localStorage` indisponível (janela anônima restrita), o Cliente cai na página inicial: degrada sem quebrar.
- De volta à página do Produto, **nada é comprado sozinho**: o Cliente clica em "Comprar com Pix" de novo. Uma cobrança só nasce de um clique.
- Nada no backend muda para isso; o guarda continua no servidor (`401`).

## 8. Telas e textos (pt-BR)

**Tela A: página do Produto** (`/catalogo/:id`, componente novo `BuyWithPix`)

| Onde | Texto |
|---|---|
| Botão | `Comprar com Pix` |
| Botão, enquanto envia | `Gerando o Pix…` |
| Título do bloco do CPF | `Informe seu CPF` |
| Explicação do CPF | `O CPF é exigido para gerar a cobrança por Pix. Ele é enviado ao Asaas, que processa o pagamento, e não fica guardado na SapiensIA.` |
| Rótulo do campo | `CPF` |
| Dica do campo | `Digite os 11 números.` |
| Botão do bloco do CPF | `Gerar Pix` |
| Erro: campo vazio | `Informe o CPF.` |
| Erro: CPF inválido (tela ou servidor) | `CPF inválido. Confira os números.` |
| Erro: Asaas indisponível (`502`) | `Não foi possível gerar o Pix agora. Tente novamente em alguns minutos.` |
| Erro: já em andamento (`409`) | `Já existe um Pix sendo gerado para você. Aguarde alguns segundos e tente novamente.` |
| Erro: Produto saiu do Catálogo (`404`) | `Este Produto não está mais disponível.` |
| Erro inesperado | `Não foi possível concluir agora. Tente novamente.` |

**Tela B: Pedido** (`/pedidos/:id`, página nova `OrderPage`)

| Onde | Texto |
|---|---|
| Título da aba | `Pagamento por Pix \| SapiensIA` |
| Título da aba, carregando | `Carregando… \| SapiensIA` |
| Carregando | `Carregando…` |
| Título | `Pague com Pix` |
| Termos da lista | `Produto` e `Valor` |
| Instrução | `No aplicativo do seu banco, escolha pagar com Pix e leia o QR Code ou cole o código abaixo.` |
| Nome acessível do QR Code | `QR Code do Pix` |
| Rótulo do código | `Pix copia e cola` |
| Botão | `Copiar código` |
| Depois de copiar | `Código copiado.` |
| Falha ao copiar | `Não foi possível copiar. Selecione o código e copie manualmente.` |
| Contagem | `Este Pix expira em {mm:ss}.` |
| Aviso para leitor de tela | `Faltam 5 minutos para o Pix expirar.` e `Falta 1 minuto para o Pix expirar.` |
| Depois de pagar | `O Acesso ao Produto é liberado depois que o pagamento for confirmado.` |
| Expirado: título da aba | `Pix expirado \| SapiensIA` |
| Expirado: título | `Este Pix expirou` |
| Expirado: texto | `O prazo de 30 minutos para pagar terminou e este código não vale mais. Se ainda quiser o Produto, gere um novo Pix.` |
| Expirado: link | `Voltar ao Produto` |
| Não encontrado: título da aba | `Pedido não encontrado \| SapiensIA` |
| Não encontrado: título | `Pedido não encontrado` |
| Não encontrado: texto | `Não encontramos este Pedido. Confira o endereço ou volte ao Catálogo.` |
| Erro ao carregar | `Não foi possível carregar o Pedido agora. Tente novamente.` |
| Botão do erro | `Tentar novamente` |
| Link | `Voltar ao Catálogo` (texto que já existe) |

**Tela C: Entrar** (`/entrar`, só o aviso novo)

| Onde | Texto |
|---|---|
| Aviso, quando há compra esperando | `Entre ou crie sua conta para continuar a compra.` |

**Acessibilidade e celular**

- QR Code em SVG (`QRCodeSVG`), `role="img"` e `aria-label`, dentro de um quadro branco fixo (o contraste do QR não depende do tema), largura fluida até 256 px.
- O código copia e cola fica num `textarea` somente leitura com `label`, para o leitor de tela e para quem prefere selecionar; quebra em qualquer ponto, sem rolagem horizontal em 390 px.
- A contagem tem `role="timer"` e **não** é anunciada a cada segundo; uma região `aria-live="polite"` separada anuncia só os dois avisos (5 minutos e 1 minuto). "Código copiado." usa `role="status"`. Erros usam `role="alert"`.
- Campo do CPF: `label`, `inputMode="numeric"`, `autoComplete="off"`, `aria-describedby` na dica e no erro, `aria-invalid` no erro. Aceita o CPF com ou sem pontos e traço.
- Ao abrir o bloco do CPF, o foco vai para o campo; ao chegar à tela do Pedido e ao virar "expirou", o foco vai para o título (`tabIndex={-1}`).
- Botões e links de verdade, foco visível, ordem de tabulação: título, QR, código, copiar, contagem, links.
- A contagem usa `secondsToExpire` do servidor (não o relógio do aparelho); ao zerar, a tela consulta o Pedido de novo e mostra "expirou".
- Skill `verificar-tela` nas telas A e B (e no aviso da C): 1280 px e 390 px, com teclado.

## 9. Testes

**Backend (Testcontainers, PostgreSQL de verdade; `PixGateway` é o falso)**

| Classe | O que prova |
|---|---|
| `OrdersTest` | Migration e repositórios: índice de um `PENDING` por Cliente e Produto, `provider_payment_id` único, FKs, nenhuma coluna para o código Pix |
| `CpfTest` (unitário) | `SEC-033`: aceita `52998224725`, `529.982.247-25`, ` 111.444.777-35 `; recusa `52998224724`, `11111111111`, 10 e 12 dígitos, letras, vazio, `null`; `toString` não mostra o número |
| `PixProviderConfigTest` | O falso recusa o perfil `prod`; o adaptador recusa chave em branco e `base-url` fora da lista; o contexto padrão sobe sem chave |
| `AsaasPixGatewayWireTest` | Contra um servidor HTTP local (padrão de `SupabaseProductFileStorageWireTest`): caminhos, verbos, `access_token`, `User-Agent`, JSON enviado (`value` 29.9 para 2990 centavos, `billingType` `PIX`, `externalReference`, `notificationDisabled`); `FR-019`: ids lidos; e as falhas da Review Focus 2, sem vazar corpo, CPF nem chave |
| `OrderRouteAccessTest` | `SEC-010`: matriz anônimo/Administrador/Cliente/CSRF/verbos |
| `OrderCreationTest` | `FR-019`: `201`, `Location`, ids únicos; `SEC-012`: preço do servidor e Produto não publicado; `SEC-011`: outro Cliente recebe 404; `SEC-033`: `CPF_REQUIRED`, `CPF_INVALID`, CPF só na primeira compra |
| `OrderIdempotencyTest` | Review Focus 1: repetição devolve o mesmo Pedido com 1 chamada ao falso; duas threads simultâneas dão 1 cobrança (20 rodadas); Produtos diferentes dão 2 Pedidos |
| `OrderProviderFailureTest` | `SEC-018`: nenhuma linha em `orders` para cada etapa e tipo de falha do `POST`; no `GET`, `502` e o Pedido intacto; A2: cobrança excluída quando a gravação falha |
| `OrderSlowProviderTest` | `SEC-018` (A1): adaptador de verdade contra um servidor local que não responde, pool do banco reduzido a 2: quatro compras simultâneas recebem `502` com o código fixo antes do tempo de espera do pool, o pool volta a zero conexões ativas e o Catálogo responde |
| `OrderServiceStoreFailureTest` (unitário) | A2: banco recusa a gravação; a cobrança é excluída e a mesma exceção sobe, mesmo se a exclusão falhar |
| `OrderCpfNotStoredTest` | `SEC-033, ADR-0003`: a varredura de todas as tabelas (seção 3), também pelo código copia e cola (A3) |
| `OrderExpirationTest` | `ADR-0003`: Review Focus 4, com `Clock` controlado |
| `OrderLogTest` | `SEC-033, ADR-0006`: nada sensível no log em TRACE |
| `RequestRecordsRedactionTest` (alterado) | `cpf` é palavra sensível; `CreateOrderRequest` é exigido |
| `ProdProfileConfigTest` (alterado) | As propriedades novas do perfil `prod` |

**Frontend (Vitest + Testing Library)**

`cpf.test.ts`, `returnTo.test.ts` (inclui os caminhos forjados da Review Focus 5), `BuyWithPix.test.tsx`, `OrderPage.test.tsx` (com relógio falso), `LoginPage.test.tsx` (alterado), `CatalogProductPage.test.tsx` (alterado).

**E2E** (`frontend/e2e/admin-panel.e2e.ts`, um teste novo, contra API e banco de verdade, sem `page.route` nem mock de rede no navegador)

- Título: `FR-019 SEC-012 SEC-033 SEC-011: o Visitante é levado a entrar, volta ao Produto e gera um Pedido com Pix`.
- Fica nesse arquivo porque precisa de um Produto publicado, e só o Administrador publica (o teste da capa, do #21, fez o mesmo). O arquivo roda só no projeto `desktop`; o teste abre também um contexto com `devices["Pixel 7"]` para repetir a compra em tela de celular.
- **Do lado do servidor, o Asaas é falso, e isso é deliberado:** no computador (`just e2e`, perfil local) é o `FakePixGateway` em memória; no CI (imagem em `prod`) é o `AsaasPixGateway` de verdade falando com o stub HTTP `.github/asaas-stub.mjs`. Nenhuma requisição sai para o Asaas.
- O CPF do teste é fictício, gerado em tempo de execução (9 dígitos aleatórios mais os verificadores).

## 10. O que não será provado e por quê

| O quê | Por quê | Quando se prova |
|---|---|---|
| O sandbox real do Asaas aceita nossas requisições (chave, `User-Agent`, `name` como e-mail, valor mínimo, nome do código de erro de CPF) | Depende da chave do sandbox, que não existe no CI nem pode ir para o repositório | Na homologação, depois do deploy: o runbook traz o roteiro (gerar um Pedido, ver a cobrança no painel do sandbox, deixar expirar e ver a cobrança cancelada) |
| O código copia e cola é pagável num aplicativo de banco | Sandbox não movimenta dinheiro; o botão "Confirmar pagamento" simula | Homologação (botão do sandbox) e, de verdade, só em produção |
| O Pedido vira pago | É o #28 | #28 |
| Comportamento com o Asaas lento de verdade | Os testes simulam timeout com um servidor local | Observação na homologação |
| Esgotamento do pool com muitas compras simultâneas e o Asaas lento de verdade | O `OrderSlowProviderTest` prova que o tempo limite devolve as conexões (pool de 2, quatro compras), não o comportamento sob carga real com o pool de 10 | #42 ou teste de carga futuro |
| Cobrança excluída de fato no Asaas quando a gravação falha | O teste prova a chamada ao `DELETE` (falso e stub), não o efeito no sandbox | Homologação, se a falha acontecer; senão, conciliação (#31) |
| Leitor de tela de verdade (NVDA, TalkBack) | O `verificar-tela` confere papéis, nomes e teclado, não a fala | Conferência manual do Sérgio, se quiser |
| Corrida entre o cancelamento da cobrança e um pagamento no mesmo segundo | Só acontece com dinheiro de verdade; a regra do ADR-0003 (pago depois de expirado vira pago) cobre o resultado | #28 |
| Cobrança órfã no Asaas depois de um timeout em que o Asaas criou a cobrança e nós não soubemos | Sem chave de idempotência na API, não há como evitar; ela não é pagável (o código nunca foi mostrado e as notificações estão desligadas) | Conciliação (#31) |

## 11. Decisões que dependem do Sérgio

**Respostas (13/11/2026):** D1 (a); D2 provisório, revisado no diff da PR; D3 a D8 e D10 como recomendado; D9 antes do deploy na homologação.

| # | Decisão | Recomendação |
|---|---|---|
| D1 | O Asaas exige um `name` no cadastro do cliente e o sapiensia não tem o nome do Cliente. Opções: (a) enviar o e-mail do Cliente como nome; (b) pedir "Nome completo" junto com o CPF; (c) enviar um texto fixo | **(a)**: não coleta dado novo (SEC-033) e deixa você achar o Cliente no painel do Asaas na hora de um Reembolso |
| D2 | Todos os textos da seção 8 | Aprovar ou corrigir |
| D3 | Pedido pendente do mesmo Produto é reaproveitado (mesmo código, tempo restante) em vez de gerar outro; mudança de preço não afeta um Pedido já pendente | Reaproveitar |
| D4 | Sem tabela de tentativas de pagamento: um Pedido é uma cobrança | Aceitar (seção 1) |
| D5 | O texto "O Acesso ao Produto é liberado depois que o pagamento for confirmado." entra agora, antes do #28. Este ticket sozinho não deve ir para produção: quem pagasse não receberia nada | Manter o texto; só homologação até o #28 |
| D6 | Sem limite de taxa nas rotas novas até o #42 | Aceitar |
| D7 | O e2e fica dentro de `admin-panel.e2e.ts` (precisa do Administrador para publicar), com um contexto de celular dentro do mesmo teste | Aceitar |
| D8 | Marcar na issue #28 que ela depende da #24 (comentário e dependência no GitHub) | Posso fazer junto com o push |
| D9 | Você cria a chave de API do sandbox e a chave Pix do sandbox (seção 5) antes do deploy na homologação | Depois do merge, antes do deploy |
| D10 | Se o sandbox mostrar que há valor mínimo de cobrança acima de algum preço do Catálogo, abre-se uma issue para validar o preço mínimo no cadastro do Produto | Aceitar |

## 12. Arquivos

**Criar (backend, `backend/src/main/java/br/com/sergio/sapiensia/`)**
- `payment/`: `PixGateway`, `PixGatewayException`, `AsaasPixGateway`, `FakePixGateway`, `Cpf`, `Orders`, `PaymentCustomers`, `OrderStatus`, `OrderResponse`, `OrderCreated`, `CreateOrderRequest`, `OrderService`, `OrderController`, `OrderExpiration`, `OrderNotFoundException`, `OrderInProgressException`, `CpfRequiredException`, `CpfInvalidException`, `PaymentProviderUnavailableException`
- `customer/CurrentCustomer.java`
- `backend/src/main/resources/db/migration/V19__create_orders.sql`
- Testes da seção 9 em `backend/src/test/java/br/com/sergio/sapiensia/payment/`, mais `SwitchablePixGateway`, `SwitchablePixGatewayConfig` e `OrderTestSupport`

**Criar (frontend, `frontend/src/features/orders/`)**
- `schemas.ts`, `api.ts`, `hooks.ts`, `messages.ts`, `cpf.ts`, `returnTo.ts`, `BuyWithPix.tsx`, `OrderPage.tsx` e os testes

**Criar (outros)**
- `.github/asaas-stub.mjs`, `docs/runbooks/asaas-chave-api.md`

**Modificar**
- `security/SecurityConfig.java`, `web/ApiExceptionHandler.java`, `product/CatalogService.java`, `product/CatalogProduct.java`
- `application.yml`, `application-local.yml`, `application-prod.yml`, `src/test/resources/application.properties`
- `RequestRecordsRedactionTest.java`, `ProdProfileConfigTest.java`
- `frontend/src/App.tsx`, `features/catalog/CatalogProductPage.tsx` (+ teste), `features/session/LoginPage.tsx` (+ teste), `features/session/messages.ts`, `frontend/e2e/admin-panel.e2e.ts`
- `.github/workflows/ci.yml`, `fly.homolog.toml`, `.env.example`
- `docs/adr/0003-pagamento-so-por-pix-via-asaas.md`, `docs/runbooks/homologacao-fly.md` (tabela de segredos: 10 nomes), `CLAUDE.md` ("Reaproveitar antes de criar": `PixGateway`, `CurrentCustomer`)

---

## Task 1: Tabelas `orders` e `payment_customers` e os repositórios

**Files:**
- Create: `db/migration/V19__create_orders.sql`, `payment/Orders.java`, `payment/PaymentCustomers.java`, `payment/OrderStatus.java`
- Test: `payment/OrdersTest.java`, `payment/OrderTestSupport.java`

**Interfaces:**
- Produces: `enum OrderStatus { PENDING, EXPIRED }`.
- Produces: `record Orders.Row(UUID id, UUID customerId, UUID productId, String productTitle, int priceCents, OrderStatus status, String providerPaymentId, Instant createdAt, Instant expiresAt)`.
- Produces: `@Repository class Orders` (package-private, `JdbcClient`):
  - `void insert(UUID id, UUID customerId, UUID productId, int priceCents, String providerPaymentId, Instant createdAt, Instant expiresAt)` (status `PENDING`)
  - `Optional<Row> findForCustomer(UUID id, UUID customerId)` (`where o.id = :id and o.customer_id = :customerId`, join em `products` só para o título)
  - `Optional<Row> findPending(UUID customerId, UUID productId)`
  - `List<Row> findPendingExpired(Instant now, int limit)` (ordem por `expires_at`)
  - `boolean markExpired(UUID id, Instant now)` (`set status = 'EXPIRED', expired_at = :now where id = :id and status = 'PENDING'`; devolve `rows == 1`)
  - `boolean tryLockCustomer(UUID customerId)` (o `pg_try_advisory_xact_lock` da seção 4)
- Produces: `@Repository class PaymentCustomers`: `Optional<String> find(UUID customerId)`; `void save(UUID customerId, String providerCustomerId)` (`on conflict (customer_id) do nothing`).
- Produces (testes): `OrderTestSupport` cria um Cliente confirmado e um Produto publicado direto no banco e devolve os ids.

- [ ] **Step 1: Escrever `OrdersTest`** (`@SpringBootTest`, `@Import(TestcontainersConfiguration.class)`):
  - `FR-019: shouldStoreAPendingOrderAndReadItBackForItsCustomer` (confere preço, título, `expiresAt`, id da cobrança)
  - `ADR-0003: shouldHaveNoColumnForThePixCode` (A3: `information_schema.columns` de `orders` e `payment_customers` não tem coluna com `pix`, `copy`, `qr`, `payload` ou `cpf` no nome)
  - `SEC-011: shouldFindNothingForAnotherCustomer`
  - `FR-019: shouldRefuseASecondPendingOrderForTheSameCustomerAndProduct` (`DuplicateKeyException`)
  - `FR-019: shouldRefuseTwoOrdersWithTheSamePaymentId`
  - `shouldAllowANewPendingOrderAfterThePreviousOneExpired`
  - `ADR-0003: shouldExpireOnlyOnce` (segundo `markExpired` devolve `false`)
  - `shouldListOnlyPendingOrdersPastTheirDeadline`
  - `shouldGiveTheCustomerLockToOneTransactionAtATime` (duas `TransactionTemplate` em threads: `true` e `false`; depois do commit, `true` de novo)
  - `SEC-033: shouldKeepOnlyTheProviderCustomerCode` (`save` duas vezes mantém o primeiro)
- [ ] **Step 2: Rodar e ver falhar.** Run: `cd backend; .\mvnw.cmd test -Dtest=OrdersTest`. Expected: FAIL (classes inexistentes).
- [ ] **Step 3: Criar a migration com a skill `nova-migration`** ("orders and payment customers"), com o SQL da seção 1.
- [ ] **Step 4: Implementar `OrderStatus`, `Orders`, `PaymentCustomers` e `OrderTestSupport`.**
- [ ] **Step 5: Rodar e ver passar** (`-Dtest='OrdersTest,SapiensiaApplicationTests'`).
- [ ] **Step 6: Commit** `feat: orders and payment customers tables (#24)`.

## Task 2: `Cpf`, `PixGateway`, o falso e a configuração

**Files:**
- Create: `payment/Cpf.java`, `payment/PixGateway.java`, `payment/PixGatewayException.java`, `payment/FakePixGateway.java`
- Modify: `application.yml`, `application-local.yml`, `application-prod.yml`, `src/test/resources/application.properties`
- Test: `payment/CpfTest.java`, `payment/PixProviderConfigTest.java` (parte do falso), `ProdProfileConfigTest.java`

**Interfaces:**
- Produces: `final class Cpf { static Optional<Cpf> parse(String raw); String digits(); }` com `toString()` igual a `"Cpf[redacted]"`.
- Produces: `PixGateway` e `PixGatewayException` como na seção 5. `PixGatewayException(Kind kind, String step, String reason)`; `getMessage()` é `"Pix gateway failed: step=<step> reason=<reason>"`; sem `cause`.
- Produces: `FakePixGateway` como na seção 5; `createCustomer` guarda só o código gerado por `customerId`, nunca o CPF.

- [ ] **Step 1: Escrever `CpfTest`** com os casos da seção 9 e `SEC-033: shouldNeverPrintTheNumber`.
- [ ] **Step 2: Escrever em `PixProviderConfigTest`**: `shouldStartWithTheFakeGatewayAndNoApiKey` (contexto padrão tem um bean `FakePixGateway`) e `ADR-0003: shouldRefuseTheFakeGatewayInTheProdProfile` (`ApplicationContextRunner` ou construção direta com `MockEnvironment` em `prod`: `IllegalStateException`).
- [ ] **Step 3: Rodar e ver falhar.**
- [ ] **Step 4: Implementar** as classes e as linhas de configuração da tabela da seção 5 (comentários em inglês; no `prod`, o comentário diz que a chave em branco impede a subida).
- [ ] **Step 5: Ajustar `ProdProfileConfigTest`** para as propriedades novas, seguindo o que ele já faz com as do Storage.
- [ ] **Step 6: Rodar e ver passar** (`-Dtest='CpfTest,PixProviderConfigTest,ProdProfileConfigTest,SapiensiaApplicationTests'`).
- [ ] **Step 7: Commit** `feat: Pix gateway interface with an in-memory fake for local and tests (#24)`.

## Task 3: `AsaasPixGateway`

**Files:**
- Create: `payment/AsaasPixGateway.java`
- Test: `payment/AsaasPixGatewayWireTest.java`; `payment/PixProviderConfigTest.java` (parte do adaptador)

**Interfaces:**
- Consumes: `PixGateway`, `PixGatewayException` (Task 2).
- Produces: `@Component @ConditionalOnProperty(name = "app.pix.provider", havingValue = "asaas") class AsaasPixGateway implements PixGateway`, com o construtor `@Autowired AsaasPixGateway(@Value("${app.asaas.api.base-url}") String baseUrl, @Value("${app.asaas.api.key:}") String apiKey, @Value("${app.asaas.api.connect-timeout}") Duration connectTimeout, @Value("${app.asaas.api.read-timeout}") Duration readTimeout)` e o construtor de teste `AsaasPixGateway(RestClient.Builder builder, String baseUrl, String apiKey, Duration connectTimeout, Duration readTimeout)` (o builder recebe o `JdkClientHttpRequestFactory` com os tempos limite dentro do construtor, para o teste exercitar o tempo limite de verdade). Etapas (`step`) usadas nas exceções e no log: `FIND_CUSTOMER`, `CREATE_CUSTOMER`, `CREATE_CHARGE`, `READ_QR_CODE`, `CANCEL_CHARGE`.

- [ ] **Step 1: Escrever `AsaasPixGatewayWireTest`** (servidor HTTP local como em `SupabaseProductFileStorageWireTest`; a chave é montada em tempo de execução; `OutputCaptureExtension`):
  - `FR-019: shouldCreateTheCustomerWithNotificationsOffAndReturnItsCode` (corpo: `name`, `cpfCnpj`, `externalReference`, `notificationDisabled = true`; cabeçalhos `access_token` e `User-Agent: sapiensia`)
  - `shouldFindAnExistingCustomerByExternalReference` e `shouldFindNoCustomerWhenTheListIsEmpty`
  - `FR-019, SEC-012: shouldCreateAPixChargeInReaisFromCents` (2990 vira `29.9`; `billingType = "PIX"`; `dueDate` ISO; `externalReference` = id do Pedido) e devolve o `id`; nenhuma chamada ao `pixQrCode` nesse método
  - `ADR-0003: shouldReadTheCopyPasteCodeOnEveryCall` (A3: duas chamadas a `readCopyPaste` fazem duas requisições `GET /v3/payments/{id}/pixQrCode` e devolvem só o `payload`)
  - `ADR-0003: shouldCancelAChargeAndTreat404AsCancelled`
  - `SEC-033: shouldReportARejectedDocumentWithoutEchoingIt` (`400` com `code` `invalid_cpfCnpj` e o CPF na `description`: `Kind.DOCUMENT_REJECTED`; mensagem e log sem o CPF)
  - `SEC-018: shouldReportUnavailableForServerErrorAndMalformedAnswers` (parametrizado: `500`, `429`, `200` com `{}`, `200` com JSON quebrado, `200` no QR sem `payload`)
  - `SEC-018: shouldGiveUpOnASlowAnswerWithinTheReadTimeout` (A1: servidor que segura a resposta 5 s; adaptador com `read-timeout` `PT0.5S`: `Kind.UNAVAILABLE` em menos de 2 s, em cada um dos cinco métodos)
  - `SEC-018: shouldGiveUpOnAConnectionThatDoesNotOpenWithinTheConnectTimeout` (A1: `ServerSocket` com backlog cheio ou endereço que não responde; se o comportamento do sistema operacional tornar o teste instável, fica só o teste da configuração do Step 2 e isso vai para a seção 10)
  - `SEC-022: shouldReportAnInvalidKeyWithoutLoggingIt` (`401`: `UNAVAILABLE`, log em ERROR sem a chave)
  - `SEC-018: shouldNeverPutTheResponseBodyInTheExceptionOrTheLog` (corpos com um marcador único; saída sem o marcador; o `payload` do QR também não aparece no log)
- [ ] **Step 2: Em `PixProviderConfigTest`**: `SEC-022: shouldRefuseABlankApiKey`, `SEC-022: shouldRefuseABaseUrlOutsideAsaasAndLoopback` (`https://example.com`, `http://api.asaas.com`, `https://api.asaas.com.evil.test`) aceitando as quatro formas permitidas, e `SEC-018: shouldRefuseTimeoutsAboveThreeAndTenSeconds` (A1: `PT3.001S` de conexão, `PT10.001S` de leitura, zero e negativo são recusados; `PT3S` e `PT10S` sobem).
- [ ] **Step 3: Rodar e ver falhar.**
- [ ] **Step 4: Implementar.** Corpos enviados como `record` internos com `toString()` redigido; respostas lidas com `exchange` e `JsonNode`, de onde saem só `id`, `payload`, `data[0].id` e, nos erros, os `code` filtrados por `[A-Za-z0-9_.]{1,64}`. Erro de leitura do corpo de erro não muda o `Kind`.
- [ ] **Step 5: Rodar e ver passar** (`-Dtest='AsaasPixGatewayWireTest,PixProviderConfigTest'`).
- [ ] **Step 6: Commit** `feat: Asaas adapter for Pix charges (#24)`.

## Task 4: Criar e ler o Pedido

**Files:**
- Create: `payment/OrderService.java`, `payment/OrderController.java`, `payment/OrderResponse.java`, `payment/CreateOrderRequest.java`, as cinco exceções, `customer/CurrentCustomer.java`
- Modify: `security/SecurityConfig.java`, `web/ApiExceptionHandler.java`, `product/CatalogService.java`, `product/CatalogProduct.java`, `RequestRecordsRedactionTest.java`
- Test: `payment/OrderRouteAccessTest.java`, `OrderCreationTest.java`, `OrderIdempotencyTest.java`, `OrderProviderFailureTest.java`, `OrderSlowProviderTest.java`, `OrderServiceStoreFailureTest.java`, `OrderCpfNotStoredTest.java`, `SwitchablePixGateway.java`, `SwitchablePixGatewayConfig.java`

**Interfaces:**
- Consumes: `Orders`, `PaymentCustomers` (Task 1); `PixGateway`, `Cpf` (Task 2); `CatalogService.get(UUID)` (agora `public`).
- Produces: `public final class CurrentCustomer { public static UUID id(Authentication a); public static String email(Authentication a); }` no pacote `customer`.
- Produces: `record CreateOrderRequest(@NotNull UUID productId, String cpf)` com `toString()` igual a `"CreateOrderRequest[productId=<id>]"`.
- Produces: `record OrderResponse(UUID id, UUID productId, String productTitle, int priceCents, OrderStatus status, Instant expiresAt, long secondsToExpire, String pixCopyPaste)` com `toString()` sem o código. `pixCopyPaste` vem de `gateway.readCopyPaste` só quando o Pedido está `PENDING` e no prazo. Pedido `PENDING` com `expiresAt` no passado sai como `EXPIRED`, `secondsToExpire = 0` e `pixCopyPaste = null`, sem chamar o Asaas.
- Produces: `record OrderCreated(UUID id)` (corpo do `POST`).
- Produces: `@Service public class OrderService { record Created(UUID orderId, boolean isNew) {} Created create(UUID customerId, String customerEmail, CreateOrderRequest request); OrderResponse get(UUID customerId, UUID orderId); }`, com `TransactionTemplate`, `Clock` e `@Value("${app.orders.pix-ttl}") Duration`. Para o Pedido pendente vencido encontrado no passo 3 da seção 4, chama `OrderExpiration.Expirer.expire(row)` (Task 5; `false` vira `PaymentProviderUnavailableException`); **nesta task** esse ramo lança `PaymentProviderUnavailableException` e a Task 5 o substitui.
- Produces: `@RestController @RequestMapping("/api/orders") class OrderController`: `@PostMapping ResponseEntity<OrderCreated> create(@Valid @RequestBody CreateOrderRequest, Authentication)` (`201` com `Location` ou `200`); `@GetMapping("/{id}") OrderResponse get(@PathVariable UUID id, Authentication)`.
- Produces (`ApiExceptionHandler`): `OrderNotFoundException` vira `404` vazio; `CpfRequiredException` vira `400` com `errors.cpf = CPF_REQUIRED`; `CpfInvalidException` vira `400` com `CPF_INVALID`; `OrderInProgressException` vira `409` com `errors.order = ORDER_IN_PROGRESS`; `PaymentProviderUnavailableException` vira `502` com `errors.payment = PAYMENT_PROVIDER_UNAVAILABLE`.
- Produces (testes): `SwitchablePixGateway` com `calls()`, `lastCustomerCpf()`, `lastChargeAmountCents()`, `copyPasteReads()`, `cancelledCharges()`, `failAt(String step, PixGatewayException.Kind kind)`, `failCancel(boolean)`, `holdNextCharge(CountDownLatch)`, `afterNextCharge(Runnable)` e `reset()`.

- [ ] **Step 1: `OrderRouteAccessTest`** (padrão dos testes de rota existentes): `SEC-010: shouldRefuseAnAnonymousVisitor` (401 nos dois verbos), `SEC-010: shouldRefuseAnAdministrator` (403), `SEC-010: shouldRefuseAPostWithoutTheCsrfToken` (403), `SEC-010: shouldKeepEveryOtherOrderRouteDenied` (`GET /api/orders`, `PUT` e `DELETE /api/orders/{id}`, `GET /api/orders/{id}/pix`).
- [ ] **Step 2: `OrderCreationTest`**:
  - `FR-019: shouldCreateAPendingOrderWithItsOwnIdAndThePaymentId` (`201`, `Location`, corpo só com `id`; o `GET` em seguida tem `status = PENDING`, `secondsToExpire` entre 1795 e 1800, `pixCopyPaste` não vazio; linha em `orders` com `provider_payment_id`; `externalReference` recebido pelo falso igual ao id)
  - `ADR-0003: shouldReadThePixCodeFromTheProviderOnEveryView` (A3: o `POST` não chama `readCopyPaste`; três `GET` fazem três chamadas e devolvem o mesmo código)
  - `SEC-012: shouldChargeTheServerPriceWhateverTheBodySays` (corpo com `priceCents: 1`, `value: 0.01`, `amount: 1`, `status: "PAID"`; o falso recebeu o preço do banco e a linha também)
  - `SEC-012: shouldRefuseADraftAnUnpublishedAndAMissingProductAlike` (três `404` com corpo vazio e cabeçalhos iguais; zero chamadas ao falso)
  - `shouldRefuseABodyWithoutAProductId` (`400`) e `shouldRefuseAMalformedId` (`400` no `GET`)
  - `SEC-033: shouldAskForTheCpfOnlyOnTheFirstPurchase` (sem `cpf`: `CPF_REQUIRED`; com `cpf`: `201`; segundo Produto sem `cpf`: `201` e nenhum `createCustomer` novo)
  - `SEC-033: shouldRefuseAnInvalidCpfWithoutEchoingIt` (casos da Review Focus 3; a resposta não contém os dígitos enviados)
  - `SEC-033: shouldTreatACpfRejectedByTheProviderAsInvalid` (`failAt("CREATE_CUSTOMER", DOCUMENT_REJECTED)`: `400` `CPF_INVALID`)
  - `SEC-033: shouldReuseACustomerThatAlreadyExistsAtTheProvider` (o falso já conhece o `externalReference`: `201` sem `cpf`)
  - `SEC-011: shouldAnswer404ForAnotherCustomersOrder` (Cliente B lê o Pedido de A: `404` vazio, idêntico ao de um UUID aleatório)
  - `FR-019: shouldReadItsOwnOrder` e `shouldNeverCacheAnOrderResponse` (`Cache-Control` contém `no-store`)
- [ ] **Step 3: `OrderIdempotencyTest`**:
  - `FR-019: shouldReturnTheSamePendingOrderWhenTheRequestIsRepeated` (`201` e depois `200`, mesmo `id`; uma chamada `createCharge`)
  - `FR-019: shouldCreateOneChargeForTwoSimultaneousRequests` (duas threads, a primeira presa por `holdNextCharge`; a segunda recebe `409` `ORDER_IN_PROGRESS`; solta a trava; uma linha em `orders`; uma cobrança; 20 rodadas com Clientes novos)
  - `shouldCreateSeparateOrdersForDifferentProducts`
  - `shouldLetTwoCustomersBuyTheSameProduct`
  - `ADR-0003: shouldCancelTheChargeWhenTheOrderCannotBeStored` (A2: linha `PENDING` concorrente inserida, em outra conexão, por `afterNextCharge`: o falso recebe `cancelCharge` com o id da cobrança criada e a resposta é `409 ORDER_IN_PROGRESS`, a mesma de sem a exclusão; uma linha só em `orders`)
  - `ADR-0003: shouldAnswerTheSameWhenTheCancelAlsoFails` (A2: o mesmo com `failCancel(true)`: ainda `409` igual, e o log tem `Pix charge left without an Order: chargeId=pay_...`)
- [ ] **Step 4: `OrderProviderFailureTest`**: `SEC-018: shouldStoreNoOrderWhenTheProviderFails`, parametrizado por (`FIND_CUSTOMER`, `CREATE_CUSTOMER`, `CREATE_CHARGE`) x (`REFUSED`, `UNAVAILABLE`): `502`, `errors.payment`, corpo sem nada além do `ProblemDetail`, zero linhas em `orders`; `SEC-018: shouldKeepTheOrderWhenThePixCodeCannotBeRead` (`READ_QR_CODE` no `GET`: `502` com o mesmo código, linha `PENDING` intacta, o `GET` seguinte com o Asaas de volta mostra o código); e `shouldWorkAgainAfterTheProviderRecovers`.
- [ ] **Step 5: `OrderSlowProviderTest`** (A1; `@SpringBootTest(webEnvironment = RANDOM_PORT)` com contexto próprio: `app.pix.provider=asaas`, `app.asaas.api.base-url=http://127.0.0.1:<porta>` de um `HttpServer` do JDK aberto no teste que segura toda resposta por 30 s, chave montada em tempo de execução, `app.asaas.api.read-timeout=PT1S`, `spring.datasource.hikari.maximum-pool-size=2`, `spring.datasource.hikari.connection-timeout=5000`, `test.isolated-context=order-slow-provider`):
  - `SEC-018: shouldAnswer502AndFreeThePoolWhenTheProviderIsSlow`: quatro Clientes, cada um com sessão, disparam `POST /api/orders` ao mesmo tempo; os quatro recebem `502` `PAYMENT_PROVIDER_UNAVAILABLE` (nenhum `500`, que seria a espera do pool estourando em 5 s), cada um em menos de 5 s; depois, `HikariPoolMXBean.getActiveConnections() == 0`, `GET /api/catalog` responde `200` e `orders` está vazia.
  - `SEC-018: shouldAnswer502WithoutHoldingAConnectionWhenTheProviderIsSlowOnView`: com um Pedido já inserido no banco, quatro `GET` simultâneos dão `502` e o Catálogo responde **durante** a espera (o `GET` não segura conexão).
- [ ] **Step 6: `OrderServiceStoreFailureTest`** (unitário, Mockito sem Spring; `TransactionTemplate` sobre um `PlatformTransactionManager` falso que só executa o bloco; `Orders` e `PaymentCustomers` como mocks; `PixGateway` como o falso): `ADR-0003: shouldCancelTheChargeWhenTheDatabaseRefusesTheOrder` (`Orders.insert` lança `DataAccessResourceFailureException`: `cancelCharge` recebe o id criado e a **mesma** exceção sobe) e `ADR-0003: shouldRethrowTheSameErrorWhenTheCancelFailsToo` (`cancelCharge` lança `PixGatewayException`: sobe a exceção do banco, não a do Asaas).
- [ ] **Step 7: `OrderCpfNotStoredTest`**: `SEC-033, ADR-0003: shouldStoreTheCpfInNoTableAtAll` (seção 3) e `ADR-0003: shouldStoreThePixCodeInNoTableAtAll` (A3: depois de criar e ler o Pedido, a mesma varredura de todas as tabelas não acha o `pixCopyPaste` devolvido pelo `GET`; o teste confere antes que o código não está vazio).
- [ ] **Step 8: Rodar e ver falhar.**
- [ ] **Step 9: Implementar**, na ordem: `CurrentCustomer`; visibilidade `public` em `CatalogService`, `get` e `CatalogProduct`; DTOs e exceções; `OrderService` (fluxo da seção 4); `OrderController`; handlers; `SecurityConfig` (`// Orders (#24): the logged-in Customer only; the Order is filtered by its owner in the SQL (SEC-011)` e as duas regras antes do `denyAll`); `cpf` em `SENSITIVE_WORDS` e `CreateOrderRequest` na asserção de `RequestRecordsRedactionTest`.
- [ ] **Step 10: Rodar e ver passar** (`-Dtest='Order*Test,RequestRecordsRedactionTest,WebSecurityTest,CatalogTest'` mais as classes do `git grep` da Global Constraints).
- [ ] **Step 11: Commit** `feat: pending order with a Pix charge at the server price (#24)`.

## Task 5: Expiração de 30 minutos

**Files:**
- Create: `payment/OrderExpiration.java`
- Modify: `payment/OrderService.java` (usa `OrderExpiration.expire`)
- Test: `payment/OrderExpirationTest.java`

**Interfaces:**
- Consumes: `Orders.findPendingExpired`, `Orders.markExpired`, `PixGateway.cancelCharge`.
- Produces: `@Configuration class OrderExpiration` com o bean `RecurringTask<Void> expirePendingOrdersTask(Expirer expirer)` (`Tasks.recurring("expire-pending-orders", Schedules.fixedDelay(Duration.ofMinutes(1)))`) e o componente `static class Expirer { record Result(int expired, int postponed) {} Result run(); boolean expire(Orders.Row order); }` (padrão de `ProductCoverCleanup`). `expire`: cancela a cobrança; sucesso ou `Kind.REFUSED` marca como expirado (no `REFUSED`, WARN com o id do Pedido e da cobrança); `Kind.UNAVAILABLE` não marca e devolve `false`. `run` trata até 50 Pedidos por vez e registra só as contagens.

- [ ] **Step 1: Escrever `OrderExpirationTest`** (o `Clock` de teste já usado no projeto, ou um `MutableClock` `@Primary` nesta classe):
  - `ADR-0003: shouldExpireAndCancelTheChargeAfterThirtyMinutes` (29:59 não expira; 30:00 expira; o falso recebeu `cancelCharge`)
  - `ADR-0003: shouldShowAnOrderAsExpiredBeforeTheTaskRuns` (`GET` em 30:01 sem rodar a tarefa: `EXPIRED`, `secondsToExpire = 0`, sem código e **nenhuma** chamada a `readCopyPaste`)
  - `ADR-0003: shouldCreateANewOrderAfterThePreviousOneExpired` (`POST` em 30:01: `201`, outro `id`, a cobrança antiga cancelada, a linha antiga `EXPIRED`)
  - `ADR-0003: shouldRetryLaterWhenTheProviderIsUnavailable` (`UNAVAILABLE`: continua `PENDING` no banco, `postponed = 1`; na rodada seguinte, expira)
  - `ADR-0003: shouldExpireAnywayWhenTheProviderRefusesToCancel` (`REFUSED`)
  - `ADR-0003: shouldAnswer502WhenAnExpiredOrderCannotBeCancelledBeforeANewOne` (`POST` em 30:01 com `UNAVAILABLE`: `502`, nenhuma linha nova)
  - `shouldLeaveOrdersStillInTimeAlone`
  - `shouldRegisterTheRecurringTask` (o bean existe com o nome `expire-pending-orders`)
- [ ] **Step 2: Rodar e ver falhar.**
- [ ] **Step 3: Implementar** e trocar o ramo provisório da Task 4.
- [ ] **Step 4: Rodar e ver passar** (`-Dtest='Order*Test'`).
- [ ] **Step 5: Commit** `feat: expire pending orders after 30 minutes and cancel the charge (#24)`.

## Task 6: Guarda de log

**Files:**
- Test: `payment/OrderLogTest.java`

- [ ] **Step 1: Escrever `OrderLogTest`** (padrão de `AsaasWebhookLogTest`: `OutputCaptureExtension`, `RANDOM_PORT`, `test.isolated-context=order-log`, `logging.level.org.springframework.security=TRACE`, `org.springframework.web=TRACE`, `org.springframework.http=TRACE`, `br.com.sergio.sapiensia=TRACE`). Um método, `@DisplayName("SEC-033, ADR-0006: shouldNeverLogTheCpfThePixCodeOrTheProviderAnswer")`, que: (a) cria um Pedido com o CPF `52998224725` mascarado (`529.982.247-25`); (b) repete a requisição; (c) envia um CPF inválido `12345678900`; (d) provoca `UNAVAILABLE` em `CREATE_CHARGE`; (e) lê o Pedido duas vezes (A3); (f) roda a expiração. A saída **não contém** `52998224725`, `529.982.247-25`, `12345678900`, o `pixCopyPaste` devolvido nem o e-mail do Cliente; **contém** o id do Pedido numa linha de criação e a linha de falha com `step=CREATE_CHARGE`.
- [ ] **Step 2: Rodar.** Expected: PASS de primeira se as Tasks 3 a 5 estiverem certas; se falhar, corrige-se o código, nunca o teste.
- [ ] **Step 3: Commit** `test: order logs carry no CPF, Pix code or provider answer (#24)`.

## Task 7: Frontend: comprar na página do Produto

**Files:**
- Create: `features/orders/schemas.ts`, `api.ts`, `hooks.ts`, `messages.ts`, `cpf.ts`, `returnTo.ts`, `BuyWithPix.tsx`, e `cpf.test.ts`, `returnTo.test.ts`, `BuyWithPix.test.tsx`
- Modify: `features/catalog/CatalogProductPage.tsx`, `CatalogProductPage.test.tsx`

**Interfaces:**
- Produces (`schemas.ts`): `orderSchema` (Zod, espelha `OrderResponse`: `status` em `["PENDING", "EXPIRED"]`, `pixCopyPaste` anulável) e `type Order`.
- Produces (`api.ts`): `createOrder(input: { productId: string; cpf?: string }): Promise<{ id: string }>` e `fetchOrder(id: string): Promise<Order>` (`PaymentUnavailableError` também no `GET`), com os erros `NotLoggedInError` (401), `CpfRequiredError`, `CpfInvalidError`, `OrderInProgressError` (409), `ProductUnavailableError` (404 no `POST`), `PaymentUnavailableError` (502), `OrderNotFoundError` (404 ou 400 no `GET`).
- Produces (`hooks.ts`): `useCreateOrder()` e `useOrder(id)` (query key `["orders", id]`, `retry: false`, `refetchOnWindowFocus: false`, para cada abertura da página ser uma leitura no Asaas e não uma por foco de janela; `gcTime: 0`, para o código não ficar no cache depois que a página fecha).
- Produces (`cpf.ts`): `normalizeCpf(text: string): string` e `isValidCpf(text: string): boolean` (as mesmas regras de `Cpf`).
- Produces (`returnTo.ts`): `rememberReturnTo(path: string): void`, `takeReturnTo(now?: number): string | null` (lê, apaga e valida: 60 minutos e os dois padrões da seção 7), `hasReturnTo(): boolean`; todo acesso ao `localStorage` em `try/catch`.
- Produces (`messages.ts`): `ORDER_TEXTS`, com exatamente os textos das telas A e B da seção 8.
- Produces: `BuyWithPix({ productId }: { productId: string })`.

- [ ] **Step 1: Testes** (`getByRole`, `userEvent`):
  - `cpf.test.ts`: os casos de `CpfTest`.
  - `returnTo.test.ts`: `SEC-012: aceita só /catalogo/<uuid> e /pedidos/<uuid>` (recusa `https://evil.test`, `//evil.test`, `/catalogo/../painel`, `javascript:alert(1)`, texto que não é JSON), vence em 60 minutos, é apagado ao ser lido, não quebra quando `localStorage` lança.
  - `BuyWithPix.test.tsx`: `FR-019: Cliente com CPF já conhecido vai direto à tela do Pedido`; `SEC-033: na primeira compra mostra o bloco do CPF com a explicação e envia o CPF só depois` (o primeiro `POST` não tem `cpf`); `SEC-033: CPF inválido mostra o erro no campo e não chama a API`; `CPF recusado pelo servidor mostra o mesmo erro`; `Visitante é levado a /entrar e o caminho do Produto fica guardado`; `mostra a mensagem de cada erro` (502, 409, 404, inesperado); `o botão fica desabilitado e diz "Gerando o Pix…" enquanto envia`; `o foco vai para o campo do CPF quando o bloco abre`.
  - `CatalogProductPage.test.tsx`: o botão aparece na página de um Produto carregado e não aparece em "não encontrado".
- [ ] **Step 2: Rodar e ver falhar** (`cd frontend; pnpm vitest run src/features/orders src/features/catalog`).
- [ ] **Step 3: Implementar.** O CPF vive só no estado do formulário (React Hook Form + Zod) e é limpo depois do envio. Sucesso: `navigate("/pedidos/" + order.id)`.
- [ ] **Step 4: Rodar e ver passar.**
- [ ] **Step 5: Commit** `feat: buy with Pix from the product page (#24)`.

## Task 8: Frontend: tela do Pedido

**Files:**
- Create: `features/orders/OrderPage.tsx`, `OrderPage.test.tsx`
- Modify: `frontend/src/App.tsx` (rota `/pedidos/:id`), `App.test.tsx` se listar rotas

**Interfaces:**
- Consumes: `useOrder`, `ORDER_TEXTS`, `rememberReturnTo` (Task 7); `formatCents` de `features/products/schemas`; `QRCodeSVG` de `qrcode.react`.

- [ ] **Step 1: Escrever `OrderPage.test.tsx`** (relógio falso do Vitest):
  - `FR-019: mostra o QR Code, o código copia e cola, o Produto e o valor`
  - `a contagem começa em secondsToExpire e desce` (`29:59` depois de 1 s)
  - `anuncia 5 minutos e 1 minuto, e não anuncia cada segundo`
  - `ao zerar, consulta o Pedido de novo e mostra "Este Pix expirou" sem o código`
  - `Pedido já expirado abre direto na tela de expirado, com o link para o Produto`
  - `Copiar código escreve na área de transferência e mostra "Código copiado."`; `falha ao copiar mostra a instrução manual`
  - `SEC-011: 404 mostra "Pedido não encontrado"`
  - `502 no GET mostra "Não foi possível carregar o Pedido agora. Tente novamente." e o botão "Tentar novamente", que consulta de novo` (A3: o código vem do Asaas a cada visualização)
  - `401 guarda o caminho do Pedido e leva a /entrar`
  - `erro de rede mostra a mensagem e o botão "Tentar novamente"`
  - `o título da aba muda em cada estado`
- [ ] **Step 2: Rodar e ver falhar.**
- [ ] **Step 3: Implementar**, com as regras de acessibilidade da seção 8.
- [ ] **Step 4: Rodar e ver passar.**
- [ ] **Step 5: Skill `verificar-tela`** nas telas A e B (pendente e expirado), em 1280 px e 390 px, com teclado. Guardar a prova para a `verificar-entrega`.
- [ ] **Step 6: Commit** `feat: order page with the Pix QR code, copy and countdown (#24)`.

## Task 9: Frontend: voltar à compra depois de entrar

**Files:**
- Modify: `features/session/LoginPage.tsx`, `LoginPage.test.tsx`, `features/session/messages.ts`

**Interfaces:**
- Consumes: `takeReturnTo`, `hasReturnTo` (Task 7).

- [ ] **Step 1: Testes em `LoginPage.test.tsx`**: `depois de entrar, volta ao Produto guardado`; `sem nada guardado, vai para a página inicial como antes`; `SEC-012: caminho forjado para outro site é ignorado`; `mostra "Entre ou crie sua conta para continuar a compra." só quando há compra esperando`; `o caminho guardado é apagado depois de usado`.
- [ ] **Step 2: Rodar e ver falhar.**
- [ ] **Step 3: Implementar**: `onSuccess: () => navigate(takeReturnTo() ?? "/")`; o aviso em `role="status"` acima do formulário, com o texto em `messages.ts`.
- [ ] **Step 4: Rodar e ver passar**, e `verificar-tela` na tela C.
- [ ] **Step 5: Commit** `feat: return to the purchase after logging in (#24)`.

## Task 10: CI, e2e e documentação

**Files:**
- Create: `.github/asaas-stub.mjs`, `docs/runbooks/asaas-chave-api.md`
- Modify: `.github/workflows/ci.yml`, `frontend/e2e/admin-panel.e2e.ts`, `fly.homolog.toml`, `.env.example`, `docs/adr/0003-pagamento-so-por-pix-via-asaas.md`, `docs/runbooks/homologacao-fly.md`, `CLAUDE.md`

- [ ] **Step 1: `.github/asaas-stub.mjs`** (Node puro, padrão de `supabase-storage-stub.mjs`, porta no argumento): exige o cabeçalho `access_token` igual a `ci-fake-asaas-key-not-real` (senão `401`); `GET /v3/customers?externalReference=` (lista do que foi criado), `POST /v3/customers`, `POST /v3/payments`, `GET /v3/payments/{id}/pixQrCode` (`payload` igual a `PIX-STUB-<id>`), `DELETE /v3/payments/{id}`. Não escreve corpo algum no log.
- [ ] **Step 2: `ci.yml`**: passo "Start the Asaas stub" igual ao do Storage (porta 54322) e, no `docker run`, `-e APP_ASAAS_API_BASE_URL=http://localhost:54322 -e APP_ASAAS_API_KEY=ci-fake-asaas-key-not-real`.
- [ ] **Step 3: Teste e2e** em `admin-panel.e2e.ts`, com o título da seção 9: o Administrador publica um Produto (os passos do teste de publicação); num contexto novo, um Cliente registrado e confirmado (`registerAndConfirm`), **sem sessão**, abre o Produto e clica em "Comprar com Pix"; cai em `/entrar` com o aviso; entra; volta ao Produto; clica de novo; vê o bloco do CPF com a explicação; envia um CPF fictício gerado na hora; chega a `/pedidos/<id>` e vê "Pague com Pix", o QR Code (`getByRole("img", { name: "QR Code do Pix" })`), o campo "Pix copia e cola" não vazio, o valor igual ao do Produto e a contagem (`/Este Pix expira em \d{2}:\d{2}\./`); recarrega e o código é o mesmo; volta ao Produto, clica em comprar e cai no **mesmo** endereço de Pedido, sem pedir CPF. Um segundo Cliente abre esse endereço e vê "Pedido não encontrado". Por fim, um contexto `devices["Pixel 7"]` com um terceiro Cliente repete a compra e confere que a página não tem rolagem horizontal (`scrollWidth <= clientWidth`).
- [ ] **Step 4: Rodar o e2e no computador**: na pasta principal, `just db`; na worktree, `just dev-back` e `just dev-front`; depois `just e2e`. Expected: todos os testes passam, inclusive os que já existiam.
- [ ] **Step 5: Documentação.** `asaas-chave-api.md` (o passo a passo da seção 5 com os comandos, a prova real na homologação da seção 10, rotação e "o que não fazer", no formato do `webhook-asaas.md`); `homologacao-fly.md` (linha `APP_ASAAS_API_KEY` na tabela de segredos, `APP_ASAAS_API_BASE_URL` na de variáveis, "10 nomes"); `fly.homolog.toml`; `.env.example`; ADR-0003 (consequências do #24: adaptador `PixGateway` e `app.pix.provider`, dados enviados ao Asaas e notificações desligadas, um Pedido pendente por Cliente e Produto, código copia e cola buscado no Asaas a cada visualização e nunca guardado, QR Code desenhado a partir do código, tempos limite de 3 s e 10 s, exclusão da cobrança quando a gravação falha, expiração por tarefa a cada minuto, limite sem chave de idempotência, a decisão D1); `CLAUDE.md`.
- [ ] **Step 6: Commit** `test: end-to-end order with Pix, Asaas stub in CI and runbook (#24)`.

## Task 11: Sistema de verdade e entrega (controlador, sem subagente)

- [ ] **Step 1:** Com banco (pasta principal), API e frontend da worktree no ar, conferir no navegador o fluxo principal: Visitante, entrar, comprar, CPF, QR Code, copiar, recarregar, esperar a expiração com `app.orders.pix-ttl` reduzido por variável de ambiente (`APP_ORDERS_PIX_TTL=PT1M`), ver "Este Pix expirou" e gerar um novo.
- [ ] **Step 2:** `just check-rapido` (conferir antes os 2 GB livres).
- [ ] **Step 3:** Revisão final de segurança (skill `requesting-code-review`, no modelo mais capaz), com a Review Focus e a seção 6 como roteiro.
- [ ] **Step 4:** Skill `verificar-entrega` e mostrar a tabela dela.
- [ ] **Step 5:** Com o OK do Sérgio: `git push -u origin feat/24-pedido-pendente-pix` e a PR com `Closes #24`, citando FR-019, SEC-012, SEC-033 e ADR-0003. A decisão D8 (issue #28) entra aqui.
