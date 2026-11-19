# A5 — Anonimização: pendências (#139 itens 1, 2, 4, 5 e `reissue` do #61) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fechar as quatro bordas da anonimização de Cliente que ainda estão abertas no código, antes da primeira venda real.

**Architecture:** Só backend, sem migration, sem rota nova e sem texto novo para o usuário. Cada item é uma mudança pequena numa classe que já existe (`PasswordResetTokens`, `OrderService`, `CustomerAnonymization`, `Customer`/`CustomerRepository`), cada uma com o teste que a reproduz primeiro.

**Tech Stack:** Java 25, Spring Boot 4.1, Spring Data JPA, JdbcClient, JUnit 5, Testcontainers.

**Spec:** issue #208 (A5), issue #139 (itens 1, 2, 4, 5), issue #61 (item do `reissue`). Requisitos: FR-036, SEC-038.

**Processo:** COMPLETO (dados pessoais). Branch `fix/139-anonimizacao-pendencias`.

## Conferência no código (19/11/2026, main em `5532bcc`)

| Item | Estado | Onde |
|---|---|---|
| #139-1 auditoria de falha no controller | **aberto** | `CustomerAnonymizationController` grava os dois `FAILURE`; o serviço só grava o `SUCCESS` |
| #139-2 `anonymized_at` fora da entidade | **aberto** | `Customer` não mapeia a coluna; 3 consultas nativas em `CustomerRepository` |
| #139-4 compra recria o vínculo | **aberto** | `OrderService.createInTransaction` não confere `anonymized_at` depois de pegar a trava |
| #139-5 `reissue` sem checar `anonymized_at` | **aberto** | `PasswordResetTokens.reissue` |
| #61 `reissue` ignora o `select ... for update` | **aberto** | mesma linha; resolvido junto com o #139-5 |

Fora deste plano (continuam na #139): itens 3, 6, 7, 8 e 9.

## Global Constraints

- O e-mail original do Cliente nunca vai para log, auditoria, mensagem de exceção nem resposta (FR-036).
- Nenhum contexto Spring novo nos testes: cada teste novo entra numa classe que já existe (o cache guarda 8).
- Nenhuma migration, dependência, rota ou propriedade `app.*` nova.
- `@DisplayName` dos testes novos começa com `FR-036 SEC-038:`.
- Ordem das travas não muda: trava da compra (advisory) antes da linha de `customers`.
- Testes focados (`.\mvnw.cmd test -Dtest=...`), um pesado por vez; incluir sempre as classes que citam FR-036 (`git grep -l FR-036 -- backend/src/test`). `just check-rapido` uma vez no fim.

## Textos que o usuário vê

Nenhum texto novo. O 401 da Task 3 cai no tratamento de sessão vencida que o frontend já tem.

## Decisões que dependem do Sérgio

1. **Task 3 — resposta da compra de um Cliente já anonimizado: 401** (igual a sessão vencida, que é o que a requisição seguinte dele já recebe). Alternativa: 409. Recomendo 401.
2. **Issue #139 continua aberta** depois da PR (sobram os itens 3, 6–9); a PR usa `Refs #139, #61, #208` e eu risco os itens entregues nas duas issues. Alternativa: fechar a #139 e mover o resto para uma issue nova.

## Review Focus

1. Compra que pega a trava **depois** do commit da anonimização: não pode criar cliente no Asaas, cobrança nem linha em `payment_customers` (Task 3).
2. Tarefa de e-mail de nova senha lida antes e executada depois da anonimização: nenhum link gravado, nenhum e-mail (Task 1).
3. Conta apagada (limpeza de não confirmadas) no meio do `reissue`: termina sem erro de chave estrangeira (Task 1).
4. Um segundo chamador do serviço (sem controller) com e-mail desconhecido ou Pedido pendente: a auditoria `FAILURE` existe, uma linha só, sem o e-mail (Task 2).
5. Linha anonimizada lida por JPQL: login e pedido de nova senha pelo e-mail substituto (`anonimizado-...@anonimizado.invalid`) não encontram conta (Task 4).

---

### Task 1: `reissue` não emite link para conta anonimizada ou apagada (#139-5, #61)

**Files:**
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/customer/PasswordResetTokens.java` (`reissue`)
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/customer/CustomerEmailService.java` (`sendPasswordReset`)
- Test: `backend/src/test/java/br/com/sergio/sapiensia/customer/PasswordResetTokensTest.java`, `CustomerEmailServiceTest.java`

**Interfaces:**
- Produces: `Optional<String> PasswordResetTokens.reissue(UUID customerId)` — vazio quando a linha não existe ou tem `anonymized_at`; nesse caso nada é apagado nem inserido.

- [ ] **Step 1: testes que falham** em `PasswordResetTokensTest`:
  - `shouldIssueNoLinkForAnAnonymizedAccount`: cria Cliente, `update customers set anonymized_at = now()`, chama `reissue` → `isEmpty()`, e `count(*) from password_reset_tokens where customer_id = ?` é 0.
  - `shouldIssueNoLinkForADeletedAccount`: `reissue(UUID.randomUUID())` → `isEmpty()`, sem exceção.
  - Ajustar os testes existentes de `reissue` para o `Optional`.
  Em `CustomerEmailServiceTest`: `sendPasswordReset` com `resetTokens.reissue` devolvendo vazio → `sender` nunca é chamado.
- [ ] **Step 2: rodar** `-Dtest=PasswordResetTokensTest,CustomerEmailServiceTest` → falha por asserção (ou compilação do `Optional`).
- [ ] **Step 3: implementar.** O `select` vira `select id from customers where id = :customerId and anonymized_at is null for update`; se vier vazio, devolve `Optional.empty()` antes de `revokeUnused`. `sendPasswordReset` só monta o link e envia quando há token.
- [ ] **Step 4: rodar** os mesmos testes mais `CustomerAnonymizationEmailTest` → verde.
- [ ] **Step 5: commit** `fix: issue no reset link for an anonymized or deleted account (#139, #61)`

### Task 2: auditoria de falha dentro do serviço (#139-1)

**Files:**
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/customer/CustomerAnonymization.java` (`anonymize`)
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/customer/CustomerAnonymizationController.java`
- Test: `backend/src/test/java/br/com/sergio/sapiensia/customer/CustomerAnonymizationTest.java`; `admin/CustomerAnonymizationControllerTest.java` e `admin/AuditPrivacyTest.java` sem mudança (provam que não duplicou)

**Interfaces:**
- A assinatura `Optional<UUID> anonymize(String email, UUID administratorId, String origin)` não muda. Passa a gravar, com `auditLog.recordIndependent`, fora da transação: `FAILURE` com `objectId = null` quando devolve vazio; `FAILURE` com `objectId = refused.customerId()` antes de relançar `CustomerHasPendingOrderException`.

- [ ] **Step 1: testes que falham** em `CustomerAnonymizationTest`, chamando o serviço direto:
  - e-mail desconhecido → exatamente 1 linha `ADMIN_CUSTOMER_ANONYMIZED / FAILURE` com `actor_id` do Administrador e `object_id is null`;
  - Pedido pendente → exatamente 1 linha `FAILURE` com `object_id` = id do Cliente;
  - em nenhuma das duas a linha contém o e-mail.
- [ ] **Step 2: rodar** `-Dtest=CustomerAnonymizationTest` → falha por asserção (0 linhas).
- [ ] **Step 3: implementar.** `try/catch` em volta do `transaction.execute` em `anonymize`; o controller perde os dois `recordIndependent`, o `AuditLog` e fica só com `clientIp.resolve`, a chamada e o `orElseThrow(CustomerNotFoundException::new)`.
- [ ] **Step 4: rodar** `-Dtest=CustomerAnonymizationTest,CustomerAnonymizationControllerTest,AuditPrivacyTest` → verde (o controller test continua exigindo 1 linha `FAILURE`, não 2).
- [ ] **Step 5: commit** `refactor: audit a refused anonymization in the service (#139)`

### Task 3: compra recusa Cliente anonimizado (#139-4)

**Files:**
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/payment/Orders.java`
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/payment/OrderService.java` (`createInTransaction`)
- Create: `backend/src/main/java/br/com/sergio/sapiensia/payment/CustomerAccountClosedException.java`
- Test: a classe de integração que já chama `OrderService.create` com o `FakePixGateway` (o implementador confere `OrderServiceStoreFailureTest`; se ela usa dublês, o teste vai para `OrdersTest`, que já tem o contexto padrão)

**Interfaces:**
- Produces: `boolean Orders.customerIsActive(UUID customerId)` — `select exists (select 1 from customers where id = :customerId and anonymized_at is null)`.
- Produces: `final class CustomerAccountClosedException extends org.springframework.security.core.AuthenticationException`, mensagem fixa `"Customer account is closed"` (sem id nem e-mail). O `ApiExceptionHandler.handleUnexpected` já relança `AuthenticationException` para a cadeia de filtros, que responde 401; nenhum handler novo.

- [ ] **Step 1: teste que falha** `shouldRefuseAPurchaseOfAnAnonymizedCustomer`: Cliente com `anonymized_at = now()` e sem linha em `payment_customers`; `orderService.create(id, "old@example.com", pedido com CPF válido)` → lança `CustomerAccountClosedException`; depois: 0 linhas em `payment_customers`, 0 em `orders` para o Cliente, e o `FakePixGateway` sem cliente nem cobrança criados para esse id.
- [ ] **Step 2: rodar** o teste → falha por asserção (o Pedido é criado).
- [ ] **Step 3: implementar.** Em `createInTransaction`, logo depois do `tryLockCustomer` e antes de qualquer leitura do Catálogo ou chamada ao gateway: `if (!orders.customerIsActive(customerId)) throw new CustomerAccountClosedException();`. A anonimização segura a mesma trava até o commit, então a leitura (READ COMMITTED) já enxerga o `anonymized_at`.
- [ ] **Step 4: rodar** a classe do teste mais `CustomerAnonymizationTest` (tem a corrida compra × anonimização) → verde.
- [ ] **Step 5: commit** `fix: refuse a purchase of an anonymized customer (#139)`

Limite aceito: o 401 por HTTP não tem teste próprio, porque o `SessionVersionFilter` derruba a sessão antes de a rota ser alcançada (só a corrida real chega lá); o teste prova a exceção e o repasse dela é comportamento que o handler já tem.

### Task 4: `anonymizedAt` na entidade e filtro em JPQL (#139-2)

**Files:**
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/customer/Customer.java`
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/customer/CustomerRepository.java`
- Test: `backend/src/test/java/br/com/sergio/sapiensia/customer/CustomerAnonymizationTest.java`

**Interfaces:**
- `Customer` ganha `@Column(name = "anonymized_at", insertable = false, updatable = false) private Instant anonymizedAt;` (só o SQL da anonimização escreve).
- `findEmailById`, `findConfirmedEmailById`, `findUnconfirmedEmailById`: de SQL nativo para JPQL com `c.anonymizedAt is null` (mesmas assinaturas).
- `findCredentialsByEmail` e `findConfirmedIdByEmail` ganham `and c.anonymizedAt is null`.
- Não mudam: `findIdByEmail` (o cadastro precisa enxergar a linha para o `on conflict`) e `findSessionVersion` (a versão já foi incrementada; a sessão morre por ela).

- [ ] **Step 1: teste que falha** `shouldHideTheAnonymizedRowFromLoginAndPasswordReset`: depois de anonimizar, lê o e-mail substituto da linha e confere `findCredentialsByEmail(substituto)` e `findConfirmedIdByEmail(substituto)` vazios.
- [ ] **Step 2: rodar** `-Dtest=CustomerAnonymizationTest` → falha por asserção.
- [ ] **Step 3: implementar** conforme Interfaces.
- [ ] **Step 4: rodar** `-Dtest=CustomerAnonymizationTest,CustomerAnonymizationEmailTest,CustomerServiceTest,SessionVersionFilterTest` → verde (o `ddl-auto: validate` confere o mapeamento na subida do contexto).
- [ ] **Step 5: commit** `refactor: map anonymized_at on Customer and filter in JPQL (#139)`

### Task 5: fechamento

- [ ] `just check-rapido` uma vez; se falhar, olhar só as linhas que importam em `.tmp/check-rapido.log`.
- [ ] Riscar os itens entregues na #139 e na #61 (edição das issues).
- [ ] `verificar-entrega`, `/code-review`, e parar para o OK do push e da PR (`Refs #139, #61, #208`, citando FR-036 e SEC-038).

## Riscos

- **Task 2:** se o `recordIndependent` falhar (banco fora), a exceção dele substitui a resposta 404/409 por um 500/503; é o comportamento de hoje no controller, só muda de lugar.
- **Task 3:** um Cliente anonimizado com requisição em voo recebe 401 em vez de um Pedido; é o efeito desejado.
- **Task 4:** trocar nativo por JPQL muda o SQL gerado; os testes de e-mail pendente (`CustomerAnonymizationEmailTest`) cobrem as três consultas.
