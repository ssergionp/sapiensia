# Anonimização de conta e inventário de dados (#41) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** escrito no MODO NOTURNO (16/11/2026), sem aprovação do Sérgio. Cada decisão que dependeria dele está marcada **DECIDIDO NO MODO NOTURNO — conferir** (seção 8) e listada na PR. Regras do Sérgio para este ticket: a anonimização nunca apaga nem altera linha das tabelas só de acréscimo (`payment_events`, `payment_event_results`, `download_records`); segue os comentários da issue; retenção de 5 anos provisória, "a confirmar com o contador"; operação só do Administrador (sem botão para o Cliente), com capacidade própria, dois fatores e auditoria. Migration reservada: **V27**.

**Regras da execução:** cada teste novo falha por asserção antes do código; localmente só lint e testes focados (incluindo as classes que citam os mesmos IDs e as que conferem log e auditoria: `AuditPrivacyTest`, `FinancialRecordImmutabilityTest`), um comando pesado por vez, nunca dois subagentes rodando testes juntos; `just check-rapido` uma vez no fim (se for morto por memória, não se repete: vale o CI); `verificar-entrega` e `/code-review` antes da PR.

**Goal:** quando um Cliente pede por e-mail a exclusão dos dados, o Administrador roda uma operação que tira da conta todo dado pessoal, sem apagar a conta nem os Pedidos, e o sistema passa a ter o inventário de dados pessoais e a política de retenção escritos.

**Architecture:** uma rota de administração, `POST /api/admin/customer-anonymizations` com o e-mail do Cliente no corpo, protegida pela capacidade nova `CUSTOMERS_ANONYMIZE`, pelos dois fatores e por autenticação recente (mesmo modelo do `ProductFileReplacementAccess`). Um serviço no pacote `customer` faz, numa transação só: troca o e-mail por um marcador sem dado pessoal, inutiliza a senha, encerra as sessões, apaga tokens, aparelhos e o vínculo com o cliente do Asaas, marca `anonymized_at` e grava a auditoria. A linha de `customers` fica (as chaves de `orders`, `accesses` e `download_records` continuam válidas); as tabelas só de acréscimo não são tocadas.

**Tech Stack:** Spring Boot 4.1 (`JdbcClient`), Spring Security, Flyway, PostgreSQL, JUnit 5, Testcontainers. **Nenhuma dependência nova, nenhuma tela nova.** Uma rota nova (admin).

**Spec:** issue #41 (pai #5) e seus dois comentários (payment_events no inventário; anonimizar a linha do Cliente no lugar por causa de `download_records`); `docs/spec/sapiensia-spec.md` FR-036 (l.300), SEC-032 (l.429), SEC-037 (l.444), SEC-038 (l.447); `docs/adr/0007-modelo-comercial-da-fase-1.md` (l.18), `0003-...` (l.25-26: payload de `payment_events` só muda com migration e ADR próprios), `0006-...` (auditoria ≥ 365 dias).

## IDs cobertos

| ID | O que este ticket entrega | Onde |
|---|---|---|
| FR-036 | Exclusão a pedido do titular, ressalvada a retenção legal (exportação e correção continuam à mão, por e-mail, ADR-0007) | Tasks 1, 2, 4 |
| SEC-038 | Exclusão implementada como anonimização; Pedidos e registros financeiros ficam | Tasks 1, 2 |
| SEC-032 | Inventário de dados pessoais (dado, finalidade, base legal, titular, destino, retenção, operador, atendimento de direitos) | Task 4 |
| SEC-037 | Retenção por tipo de dado (Pedidos, logs, notas, arquivos temporários, prompts, suporte) | Task 4 |
| ADR-0007 | "Apagar uma conta anonimiza os dados pessoais e mantém o registro do Pedido" | Tasks 1, 4 |

## Global Constraints

- **Nunca** `update`, `delete` ou `truncate` em `payment_events`, `payment_event_results` e `download_records`; nenhum gatilho desligado; a V27 não toca essas tabelas.
- A linha de `customers` nunca é apagada pela anonimização.
- O e-mail original nunca vai para log, auditoria, `ProblemDetail`, exceção ou resposta. O log da operação leva só o id do Cliente e o id do Administrador.
- Rota nova começa negada; liberada na `SecurityConfig` só com `CUSTOMERS_ANONYMIZE` + dois fatores + autenticação recente.
- Classes de teste terminam em `Test`; teste que prova requisito leva o ID no `@DisplayName`. Vermelho por asserção, não por erro de compilação.

## Review Focus

1. **E-mail em outra caixa ou com espaços** (`  Fulano@Exemplo.com `): acha o mesmo Cliente (normalização de `Emails`). Teste na Task 2.
2. **Segunda chamada com o mesmo e-mail:** 404, nada muda, nenhuma auditoria de sucesso nova. Teste na Task 2.
3. **Sessão aberta do Cliente no momento da anonimização:** a próxima requisição dele é recusada (401). Teste na Task 1.
4. **E-mail pendente na fila** (confirmação, aviso de novo aparelho, pagamento confirmado) para o Cliente anonimizado: não é enviado a ninguém. Teste na Task 3.
5. **Pessoa volta e se cadastra de novo com o mesmo e-mail:** cadastro novo funciona (o e-mail antigo não fica preso à linha anonimizada). Teste na Task 1.

---

## 1. O que a anonimização faz (FR-036, SEC-038)

Numa transação só, para o Cliente achado pelo e-mail normalizado:

| Dado | Antes | Depois |
|---|---|---|
| `customers.email` | e-mail da pessoa | `anonimizado-<UUID aleatório>@anonimizado.invalid` (domínio `.invalid` é reservado e nunca entrega; único, ≤ 254) |
| `customers.password_hash` | hash da senha | hash de 32 bytes aleatórios descartados (ninguém conhece a senha) |
| `customers.session_version` | n | n + 1 (`incrementSessionVersion`): toda sessão aberta cai |
| `customers.first_login_at` | data | `null` |
| `customers.anonymized_at` (novo, V27) | `null` | agora |
| `email_confirmation_tokens`, `password_reset_tokens`, `customer_devices` | linhas do Cliente | apagadas |
| `payment_customers` | id do cliente no Asaas | linha apagada (o vínculo com a pessoa no Asaas deixa de existir aqui) |
| `customers.id`, `created_at`, `email_confirmed_at` | | ficam (não identificam a pessoa sem o resto; mantêm Pedidos, Acessos e Registros de download válidos) |
| `orders`, `accesses` | | ficam iguais (não têm e-mail nem nome) |
| `download_records`, `payment_events`, `payment_event_results` | | **intocados** (só de acréscimo; ADR-0003) |
| `audit_events` | | +1 linha `ADMIN_CUSTOMER_ANONYMIZED` / `SUCCESS` / ator = Administrador / objeto `CUSTOMER` + id / origem = IP do Administrador (`ClientIpResolver`), na mesma transação (`recordInTransaction`) |

- Depois disso o painel de Pedidos mostra o e-mail mascarado `a***@anonimizado.invalid` (o `MaskedEmail` de sempre): sem tela nova.
- `find*EmailById` passam a ignorar Cliente com `anonymized_at` preenchido: e-mail pendente na fila é descartado em silêncio (o envio já trata "sem destinatário").
- O limite de tentativas de login guarda o e-mail normalizado só em memória e por pouco tempo: nada a fazer (vai no inventário).

## 2. A rota

- `POST /api/admin/customer-anonymizations`, corpo `{"email": "..."}` (`@NotBlank @Email @Size(max = 254)`), resposta **204** sem corpo.
- Cliente não achado (inclusive já anonimizado): **404** `ProblemDetail` com `code = CUSTOMER_NOT_FOUND` (título em inglês, como os outros: "Customer not found"); grava auditoria independente `ADMIN_CUSTOMER_ANONYMIZED` / `FAILURE` sem objeto.
- Sem a capacidade: 403; só a senha (sem o segundo fator): 401; anônimo: 401; autenticação velha: 403 `REAUTHENTICATION_REQUIRED` (o mesmo corpo do `ProductFileReplacementAccess`); corpo inválido: 400.
- Capacidade nova `Capability.CUSTOMERS_ANONYMIZE` (o Administrador recebe todas: `EnumSet.allOf`).
- OpenAPI: a rota aparece com os códigos acima.

## 3. Inventário e retenção (SEC-032, SEC-037)

Arquivo novo `docs/privacidade/inventario-dados-pessoais.md` (pt-BR), uma tabela por dado: dado; onde fica; finalidade; base legal (proposta, **a confirmar com o advogado**); titular; destino/operador (Supabase, Fly.io, Asaas, Resend); retenção; como se atende o direito (exportação, correção, exclusão). Retenção:

| O quê | Retenção |
|---|---|
| Pedidos, Acessos, `payment_events` (com o `payload` como chegou), `payment_event_results`, `download_records` | **5 anos depois do Pedido — provisório, a confirmar com o contador**; nenhuma limpeza automática neste ticket |
| `audit_events` | 365 dias (ADR-0006, limpeza automática existente) |
| Conta não confirmada | o prazo da limpeza `UnconfirmedCustomersCleanup` (copiar o valor do código) |
| Tokens de confirmação e de nova senha | até o uso ou a expiração (copiar do código) |
| Logs da aplicação | sem dado pessoal por regra (SEC-036); prazo do Fly.io (a conferir no painel do Fly) |
| Notas, arquivos temporários, prompts, suporte | ainda não existem no sistema; o atendimento é por e-mail, fora do sistema |
| Dados no Asaas (e-mail como nome do cliente e CPF) e no Resend (e-mails enviados) | regra de cada operador; a anonimização não os apaga (seção 8, N4) |

Também: a anonimização **não** mexe no `payload` de `payment_events` (ADR-0003); a retenção do registro financeiro prevalece.

## 4. Testes com IDs (resumo) e o que não será provado

- Provados: seção 1 inteira (dados trocados, linhas apagadas, linhas das tabelas só de acréscimo iguais antes e depois, auditoria), sessões caídas, recadastro, rota com os 7 códigos, e-mails pendentes descartados, nada de e-mail no log e na auditoria.
- **Não provado:** o que o Asaas e o Resend guardam (fora do sistema); o prazo de logs do Fly.io; a base legal (advogado) e o prazo (contador).

---

### Task 1: serviço de anonimização e V27

**Files:** Create migration `V27__add_customer_anonymized_at.sql` (skill `nova-migration`): `alter table customers add column anonymized_at timestamptz;` Create `customer/CustomerAnonymization.java` (public, `@Service`); Modify `customer/CustomerRepository.java` (ou `JdbcClient` no serviço; o `email` da entidade é `updatable=false`), `payment/PaymentCustomers.java` (`void deleteByCustomerId(UUID)`), `audit/AuditAction.java` (+`ADMIN_CUSTOMER_ANONYMIZED`), `audit/AuditObject.java` (+`CUSTOMER`). Test: `customer/CustomerAnonymizationTest.java`.

**Interfaces — Produces:** `Optional<UUID> CustomerAnonymization.anonymize(String email, UUID administratorId, String origin)` — vazio quando não acha; `@Transactional`.

- [ ] **Step 1: testes que falham:**
  - `FR-036, SEC-038: anonimizar troca o e-mail, inutiliza a senha e apaga tokens, aparelhos e o vínculo com o Asaas` (a tabela da seção 1, linha a linha; o e-mail novo casa `^anonimizado-[0-9a-f-]{36}@anonimizado\.invalid$`; o login com a senha antiga falha).
  - `ADR-0007, SEC-038: Pedidos, Acessos, Registros de download e eventos de pagamento ficam iguais` (snapshot `select *` das seis tabelas do Cliente antes e depois, iguais; a linha de `customers` existe).
  - `SEC-038: a anonimização fica na auditoria sem o e-mail` (1 linha `ADMIN_CUSTOMER_ANONYMIZED`/`SUCCESS`/`CUSTOMER`/id; nenhuma coluna contém o e-mail).
  - `FR-036: a sessão aberta do Cliente cai na anonimização` (Review Focus 3).
  - `FR-036: a pessoa pode se cadastrar de novo com o mesmo e-mail` (Review Focus 5).
  - `FR-036: e-mail desconhecido não muda nada` (vazio; contagens iguais).
- [ ] **Step 2:** `cd backend; .\mvnw.cmd test "-Dtest=CustomerAnonymizationTest"` → falham por asserção. **Step 3:** implementar. **Step 4:** `.\mvnw.cmd test "-Dtest=CustomerAnonymizationTest,FinancialRecordImmutabilityTest,AuditPrivacyTest,SapiensiaApplicationTests"` → verde. **Step 5:** commit `feat: anonymize a customer account in place (#41)`.

### Task 2: rota do Administrador

**Files:** Create `customer/CustomerAnonymizationController.java` (package-private), `customer/CustomerAnonymizationAccess.java` (espelha `ProductFileReplacementAccess`: `panel(CUSTOMERS_ANONYMIZE)` e depois `RecentAuthentication.isRecent`, sempre exigida); Modify `admin/Capability.java` (+`CUSTOMERS_ANONYMIZE` com javadoc), `security/SecurityConfig.java`, `web/ApiExceptionHandler.java` se for o lugar do 404. Test: `customer/CustomerAnonymizationControllerTest.java`; `admin/AuditPrivacyTest.java` (o fluxo novo entra).

- [ ] **Step 1: testes que falham:**
  - `FR-036, SEC-038: o Administrador com os dois fatores e autenticação recente anonimiza a conta` (204; e-mail trocado).
  - `SEC-038: anônimo recebe 401`; `SEC-038: Administrador só com a senha recebe 401`; `SEC-038: Administrador sem a capacidade recebe 403`; `SEC-038: autenticação velha recebe 403 REAUTHENTICATION_REQUIRED e nada muda`; `FR-036: e-mail desconhecido recebe 404 CUSTOMER_NOT_FOUND e a tentativa fica na auditoria`; `FR-036: segunda chamada recebe 404` (Review Focus 2); `FR-036: e-mail com caixa e espaços diferentes acha o Cliente` (Review Focus 1); `corpo sem e-mail recebe 400`.
  - Em `AuditPrivacyTest`: a anonimização entra no fluxo e o e-mail do Cliente não aparece em log nem em `audit_events`.
- [ ] **Step 2:** rodar → falham por asserção. **Step 3:** implementar. **Step 4:** `.\mvnw.cmd test "-Dtest=CustomerAnonymizationControllerTest,CustomerAnonymizationTest,AuditPrivacyTest,WebSecurityTest,AdminReauthenticationTest"` (e as classes que `git grep -l -e FR-036 -e SEC-038 -- backend/src/test` achar) → verde. **Step 5:** commit `feat: admin route to anonymize a customer (#41)`.

### Task 3: e-mail pendente para conta anonimizada

**Files:** Modify `customer/CustomerRepository.java` (`find*EmailById` com `and anonymized_at is null`), e o que o `OrderPaidEmail` usa para achar o destinatário. Test: `customer/CustomerAnonymizationEmailTest.java`.

- [ ] **Step 1: teste que falha:** `FR-036: e-mail pendente de confirmação, de novo aparelho e de pagamento para conta anonimizada não sai` (agenda os três, anonimiza, roda as tarefas: outbox vazio para o marcador e para o e-mail antigo; as tarefas terminam sem erro, sem novas tentativas).
- [ ] **Step 2:** rodar → falha por asserção. **Step 3:** implementar. **Step 4:** a classe mais `CustomerEmailServiceTest`, `CustomerEmailTest`, `OrderPaidEmailTest` → verde. **Step 5:** commit `fix: never e-mail an anonymized account (#41)`.

### Task 4: documentação

**Files:** Create `docs/privacidade/inventario-dados-pessoais.md` (seção 3), `docs/runbooks/anonimizar-conta-cliente.md`; Modify `docs/adr/0007-modelo-comercial-da-fase-1.md` (uma consequência: como é a anonimização e onde está o inventário), `GLOSSARY.md` (termo "Anonimização"), `CLAUDE.md` (uma linha em "Reaproveitar antes de criar": `CustomerAnonymization`).

- [ ] Runbook: conferir o pedido (o e-mail que pediu é o da conta); entrar no painel (senha + código); no console do navegador, na aba do painel, reautenticar com `POST /api/admin/sessions/reauthentication` (corpo como o `frontend/src/features/admin/api.ts` manda) e em seguida a chamada `fetch('/api/admin/customer-anonymizations', {method:'POST', credentials:'include', headers:{'Content-Type':'application/json','X-XSRF-TOKEN': <cookie XSRF-TOKEN>}, body: JSON.stringify({email})})` (204 = feito; 404 = não achou; 403 REAUTHENTICATION_REQUIRED = reautenticar); responder ao Cliente; o que fica (Pedidos, registros financeiros, por 5 anos provisórios) e por quê; Asaas e Resend (seção 8, N4); exportação e correção continuam à mão (ADR-0007).
- [ ] Commit `docs: personal data inventory, retention and anonymization runbook (#41)`.

## Verificação final

`just check-rapido` uma vez; `verificar-entrega` (CI no lugar do check completo); `/code-review`. Push, PR (`Closes #41`) com a lista das decisões da seção 8 e dos textos novos.

## 8. Decisões tomadas no modo noturno

| # | Decisão | Escolha (mais conservadora) | Status |
|---|---|---|---|
| N1 | Tela para o Administrador | Nenhuma: rota de API chamada pelo console do navegador, passo a passo no runbook (operação rara, à mão, ADR-0007) | DECIDIDO NO MODO NOTURNO — conferir |
| N2 | Como achar o Cliente | Pelo e-mail no corpo do POST (é o que chega no pedido); nunca na URL | DECIDIDO NO MODO NOTURNO — conferir |
| N3 | Apagar ou anonimizar a linha | Anonimizar no lugar (comentário da issue; chaves de `orders`, `accesses`, `download_records`) | DECIDIDO NO MODO NOTURNO — conferir |
| N4 | Asaas (guarda e-mail como nome e CPF) e Resend | Não chamar o Asaas (sem rota nova no `PixGateway`; a cobrança é registro financeiro dele); apagar só o vínculo local; o runbook manda o Administrador decidir com o contador se pede a remoção no Asaas | DECIDIDO NO MODO NOTURNO — conferir |
| N5 | `payload` de `payment_events` | Fica como chegou (ADR-0003); entra no inventário com retenção de 5 anos | DECIDIDO NO MODO NOTURNO — conferir |
| N6 | Retenção | 5 anos provisórios para Pedido e registros financeiros, **a confirmar com o contador**; sem limpeza automática neste ticket | DECIDIDO NO MODO NOTURNO — conferir |
| N7 | Base legal no inventário | Proposta (execução de contrato, obrigação legal, legítimo interesse para segurança), **a confirmar com o advogado** | DECIDIDO NO MODO NOTURNO — conferir |
| N8 | Segunda chamada / e-mail desconhecido | 404 e auditoria `FAILURE` | DECIDIDO NO MODO NOTURNO — conferir |
| N9 | Exportação e correção (FR-036, SEC-038) | Continuam à mão, por e-mail (ADR-0007); fora deste ticket | DECIDIDO NO MODO NOTURNO — conferir |

## Textos novos em pt-BR

Nenhum texto de tela. Novos em pt-BR só os documentos: inventário (`docs/privacidade/inventario-dados-pessoais.md`), runbook (`docs/runbooks/anonimizar-conta-cliente.md`), a consequência no ADR-0007 e o termo "Anonimização" no glossário. O marcador de e-mail `anonimizado-<UUID>@anonimizado.invalid` aparece mascarado no painel como `a***@anonimizado.invalid`.
