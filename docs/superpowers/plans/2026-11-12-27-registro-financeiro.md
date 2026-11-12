# Registro financeiro só de acréscimo (#27) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** APROVADO pelo Sérgio em 12/11/2026, com as 6 decisões da seção 6 como recomendadas e 4 ajustes, já incorporados nas tasks:
- J1 Cada teste de recusa confere que o erro veio do gatilho: SQLSTATE `42501` **e** a mensagem exata `payment_events is append-only` (Task 1).
- J2 A mensagem do gatilho não contém dado do evento: só o nome da tabela; um teste confere a mensagem inteira, sem o payload (Task 1).
- J3 O comentário da V17 diz que só uma migration futura, com o gatilho desligado de forma explícita, corrige dados (Task 1).
- J4 O ADR-0003 registra que o payload pode ter dado pessoal; num pedido de exclusão, a retenção financeira prevalece, e qualquer anonimização exige migration e ADR próprios (Task 2).

**Goal:** O banco recusa UPDATE, DELETE e TRUNCATE em `payment_events` (SEC-028), o INSERT do webhook continua igual, e nenhum outro papel do banco lê ou grava a tabela.

**Architecture:** Uma migration nova (V17) cria uma função de gatilho genérica (`financial_record_guard`) e dois gatilhos em `payment_events` (linha: UPDATE/DELETE; comando: TRUNCATE), o mesmo mecanismo já usado em `audit_events` (V10, #16). A mesma migration liga o Row Level Security na tabela e retira os privilégios de `PUBLIC` e dos papéis da Data API do Supabase, quando existirem. Nenhum código Java de produção muda.

**Tech Stack:** Flyway, PostgreSQL (PL/pgSQL), JUnit 5, Testcontainers, `JdbcTemplate`. **Nenhuma dependência nova. Sem tela: nenhum texto novo para o usuário.**

**Spec:** `docs/spec/sapiensia-spec.md` (SEC-028; relacionados FR-021, SEC-025, SEC-066); issue #27 (pai #5); `docs/adr/0003-pagamento-so-por-pix-via-asaas.md`; `docs/adr/0002-banco-e-arquivos-no-supabase-com-backup-no-r2.md`; `V16__create_payment_events.sql`; `V10__create_audit_events.sql` (precedente).

## IDs cobertos

| ID | Como | Onde |
|---|---|---|
| SEC-028 "append-only" | Gatilhos recusam UPDATE, DELETE e TRUNCATE em `payment_events` | Task 1 |
| SEC-028 "acesso restrito" | Banco: RLS ligado sem política + `REVOKE`. Aplicação: `PaymentEvents` é package-private e só tem `insertIfAbsent`; não existe rota que leia a tabela; `/api` é negado por padrão | Task 1 (banco); já existente (aplicação) |
| SEC-028 "considerar armazenamento externo imutável" | Considerado e **não** feito agora (seção 3, alternativa E): vira uma issue `needs-triage` | Task 2 (ADR) |
| SEC-025 / FR-021 | `insert ... on conflict do nothing` continua funcionando com o gatilho | Task 1 (teste) |

## Global Constraints

- Nenhuma migration aplicada é editada: tudo vai na V17 (use a skill `nova-migration` para confirmar o número).
- Nenhuma dependência nova; nenhum segredo novo; nada em log, tela ou commit com segredo.
- Classe de teste termina em `Test`, usa `@SpringBootTest` + `@Import(TestcontainersConfiguration.class)`; IDs no `@DisplayName` (`SEC-028: ...`).
- Um comando pesado de cada vez. Antes de cada um, conferir a memória livre; abaixo de 2 GB, parar e avisar o Sérgio:
  `"{0:N1} GB" -f ((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1MB)`
- Push só com `git push -u origin feat/27-registro-financeiro`, depois de o Sérgio aprovar o diff. Sem merge, sem force, sem `reset --hard`.

## 1. Quais tabelas formam o "registro financeiro" hoje

Só **`payment_events`** (V16). Conferi as 16 migrations: não existem ainda Pedido, tentativa de pagamento, reembolso nem direito de acesso (a spec os lista na seção 8; chegam com #28, #29, #31).

- `audit_events` **não** entra: é a trilha de auditoria (SEC-065), já protegida na V10, com a exceção de retenção de 365 dias.
- `scheduled_tasks` não entra: é fila de trabalho, apagada por desenho.
- Tabelas futuras: Pedido muda de estado (não é só de acréscimo). Um registro de reembolso ou de conciliação que seja "evento" ganha os mesmos dois gatilhos com duas linhas de SQL, porque a função é genérica (usa `tg_table_name`). Cada ticket decide; este não cria nada para elas.

## 2. Como o banco recusa UPDATE e DELETE

| Alternativa | Como funciona | Custo / problema | Veredito |
|---|---|---|---|
| **A. Gatilho** (`before update or delete ... for each row` + `before truncate ... for each statement`) | A função só faz `raise exception` com `errcode 42501` | ~12 linhas de SQL. Mesmo padrão da V10, já testado. Vale para **qualquer** usuário, inclusive o dono da tabela e superusuário. `on conflict do nothing` **não** dispara gatilho de UPDATE | **Recomendada** |
| B. Regra (`create rule ... do instead nothing`) | Reescreve o comando para nada | Falha **calada** (o UPDATE "funciona" e altera 0 linhas); não cobre TRUNCATE; e o PostgreSQL **proíbe `INSERT ... ON CONFLICT` em tabela com regra de INSERT/UPDATE**: quebraria o webhook do #26 | Rejeitada |
| C. `REVOKE UPDATE, DELETE, TRUNCATE` | Tira o privilégio | Hoje a aplicação conecta como o **dono** da tabela (o mesmo `DB_USER` roda o Flyway), e o dono pode se devolver o privilégio; superusuário ignora privilégios, então no Testcontainers (usuário superusuário) o teste **não provaria nada**. Só vale com dois usuários (alternativa D) | Rejeitada sozinha |
| D. Dois usuários: um dono/migrador (Flyway) e um de execução, sem ser dono | A aplicação não consegue nem desligar o gatilho | **Exige usuário e segredo novos** (`spring.flyway.user/password` no Fly, Bitwarden, runbook), `GRANT` para todas as 16 tabelas e privilégios padrão para as futuras, ajuste no CI e no banco local. É uma mudança de infraestrutura do projeto inteiro, não deste ticket | Fora do escopo: issue `needs-triage` |
| E. Armazenamento externo imutável (ex.: cópia dos eventos no R2 com Object Lock) | Evidência fora do alcance de quem tem a senha do banco | Integração nova, bucket, credencial, custo. A spec diz "considerar" | Fora do escopo: mesma issue |

**Convivência com o usuário único (`DB_USER`) e o Flyway, na alternativa A:**

- O Flyway roda a V17 como dono: criar função e gatilho não exige nada além disso. `ALTER TABLE` futuro (nova coluna, índice) **não** é barrado pelo gatilho.
- Uma migration futura que precise **corrigir dados** de `payment_events` terá de escrever `alter table payment_events disable trigger ...` de forma explícita: fica visível no diff e na revisão. É o comportamento desejado.
- **Limite honesto (igual ao #16):** quem tem a senha do `DB_USER` é dono da tabela e pode desligar o gatilho (ou usar `session_replication_role = replica`, se o papel tiver permissão). O gatilho protege contra bug da aplicação, SQL injection comum e erro de operador; não protege contra quem domina a credencial do banco. Fechar isso é a alternativa D ou E.
- `DROP TABLE` / apagar o volume local (`just db-stop` com volume) não passam por gatilho: o banco local continua descartável.
- **Sem exceção de retenção** (diferente de `audit_events`): nenhum DELETE é aceito, nem de linha antiga. O payload não guarda CPF (ADR-0003). Se um dia houver prazo de descarte, será uma migration nova com decisão sua.

## 3. "Acesso restrito por permissão do banco e da aplicação"

**Sem criar usuário nem segredo (este ticket):**

- **Banco.** No Supabase, tabelas do schema `public` costumam receber privilégios para os papéis da Data API (`anon`, `authenticated`, `service_role`); se isso valer para o nosso projeto, quem tiver a chave pública da API leria `payment_events` sem passar pela aplicação. **Não consegui verificar o estado real da homologação** (não tenho acesso ao banco; o `application-prod.yml` cita a função `rls_auto_enable` do Supabase, o que sugere RLS automático, mas não prova). Por isso a V17 faz, de forma idempotente:
  - `alter table payment_events enable row level security;` sem nenhuma política: qualquer papel que não seja o dono enxerga zero linhas e não insere, mesmo com `GRANT`. O dono (`DB_USER`) não é afetado.
  - `revoke all on payment_events from public;` e, num bloco `do` que confere `pg_roles`, o mesmo para `anon`, `authenticated` e `service_role` quando existirem (no banco local e no CI eles não existem).
- **Aplicação.** Já está restrito e nada muda: `PaymentEvents` é package-private e só expõe `insertIfAbsent`; nenhum controller lê a tabela; toda rota de `/api` nasce negada (`SecurityConfig`). A tela de consulta de pagamentos, quando existir, terá de exigir uma capacidade própria (SEC-029), no ticket dela.

**O que exigiria outro usuário/segredo:** impedir que a própria aplicação (ou quem roube a credencial dela) altere, apague ou desligue a proteção: alternativas D e E da seção 2.

**Achado fora do ticket:** se a Data API do Supabase estiver aberta, as outras 15 tabelas (clientes, administradores, tokens) têm a mesma exposição. Não mexo nelas aqui; entra na issue `needs-triage` com a consulta de verificação (Task 2).

## 4. Testes de integração (Task 1)

Classe nova `FinancialRecordImmutabilityTest`, no pacote `payment`, contra o PostgreSQL do Testcontainers. Cada teste cria a própria linha (id `evt_<uuid>`) e filtra por ela: a tabela não pode mais ser limpa.

| Teste (`@DisplayName`) | Prova |
|---|---|
| `SEC-028: shouldRefuseToUpdateAPaymentEvent` | UPDATE falha com "append-only"; a linha fica igual |
| `SEC-028: shouldRefuseToDeleteAPaymentEvent` | DELETE falha; a linha continua lá |
| `SEC-028: shouldRefuseToTruncate` | `truncate table payment_events` e `truncate ... cascade` falham; a linha continua lá |
| `SEC-028: shouldRefuseAnUpsertThatRewritesAnExistingEvent` | `insert ... on conflict (provider_event_id) do update set ...` falha; a linha fica igual |
| `SEC-028: shouldStillInsertAndDeduplicate` | `insertIfAbsent` devolve `true`, depois `false` para o mesmo id, sem exceção; 1 linha |
| `SEC-028: shouldHideTheRecordFromAnotherDatabaseRoleEvenWithAGrant` | Um papel novo com `GRANT SELECT, INSERT` vê 0 linhas e não consegue inserir (RLS) |

**O que os testes não provam:** o `REVOKE` dos papéis do Supabase (eles não existem no Testcontainers). Isso se confere na homologação com uma consulta só de leitura, que entra no runbook (Task 2) e que você roda depois do deploy.

## 5. Impacto no que já existe

- **Webhook (#26), `insert ... on conflict do nothing`:** sem impacto. `DO NOTHING` não executa UPDATE, então o gatilho de linha não dispara; o `UNIQUE` continua resolvendo a corrida. Os testes existentes (`PaymentEventsTest`, `AsaasWebhook*Test`, inclusive o de concorrência) têm de continuar verdes sem alteração; o teste novo `shouldStillInsertAndDeduplicate` fixa isso ao lado do gatilho.
- **Seed local:** só existe `R__seed_products.sql`; não toca em `payment_events`. Sem impacto.
- **Testes que limpam tabelas:** nenhum faz `delete`/`truncate` em `payment_events` (conferido por busca em `backend/src/test` e `frontend/e2e`); os testes do webhook já filtram pelo próprio `provider_event_id`, e o `count(*)` total do `AsaasWebhookPersistenceTest` compara antes/depois. Sem impacto.
- **Homologação:** a V17 roda sozinha na próxima subida; o runbook diz "validated 17 migrations" e passa a 18.
- **Linhas já gravadas na homologação** (eventos do sandbox): ficam para sempre. Se quiser apagá-las antes, tem de ser **antes** do deploy desta entrega (decisão 6.4).

## 6. Decisões que dependem de você

1. **Mecanismo:** gatilho (A), sem regra e sem segundo usuário agora. Recomendo.
2. **RLS + REVOKE em `payment_events`** nesta migration (seção 3). Recomendo; o custo são ~10 linhas de SQL e um teste.
3. **Sem exceção de retenção:** nenhum DELETE, nunca (até uma migration futura decidir). Recomendo.
4. **Eventos de sandbox já gravados na homologação:** deixar como estão (recomendo: são dados de teste num banco de teste) ou você apaga à mão antes do deploy.
5. **Uma issue `needs-triage`** citando o #27, com: (a) usuário de migração separado do de execução (alternativa D); (b) armazenamento externo imutável (alternativa E); (c) conferir se a Data API do Supabase expõe as outras tabelas. Abro depois do seu OK ao plano.
6. **ADR:** em vez de ADR novo, um item em "Consequences" do ADR-0003 (o registro é só de acréscimo; limite do usuário único; externo imutável adiado). Recomendo.

## Review Focus

- **Upsert que reescreve:** `on conflict ... do update` num evento existente tem de falhar (teste na Task 1).
- **`truncate ... cascade` e truncate junto com outra tabela:** tem de falhar (teste na Task 1).
- **Reentrega simultânea do mesmo evento:** continua 200 sem erro; coberto pelo teste de concorrência existente do #26, que não pode ser alterado.
- **UPDATE/DELETE que não casa nenhuma linha:** não dispara gatilho de linha e devolve 0, sem erro. É aceitável (nada mudou); não há teste.
- **Dono desliga o gatilho / `session_replication_role`:** não é coberto por desenho (seção 2); documentado no ADR, não testado.

---

### Task 1: Migration V17 e teste de imutabilidade

**Files:**
- Create: `backend/src/main/resources/db/migration/V17__payment_events_append_only.sql`
- Test: `backend/src/test/java/br/com/sergio/sapiensia/payment/FinancialRecordImmutabilityTest.java`

**Interfaces:**
- Consumes: `PaymentEvents.insertIfAbsent(String providerEventId, String eventType, String payloadJson, OffsetDateTime receivedAt) : boolean` (existente).
- Produces: função SQL `financial_record_guard()` (gatilho genérico; mensagem `'<tabela> is append-only'`, `errcode '42501'`); gatilhos `payment_events_guard_row` e `payment_events_guard_truncate`.

- [ ] **Step 1: Escrever o teste que falha.** Seis testes da seção 4, no estilo do `AuditImmutabilityTest`, e depois conferir que a linha não mudou. **J1/J2:** toda recusa passa por um helper `assertRefusedByTheGuard(ThrowingCallable)` que exige `rootCause()` do tipo `org.postgresql.util.PSQLException` com `getSQLState()` igual a `"42501"` **e** `getServerErrorMessage().getMessage()` igual (não "contém") a `"payment_events is append-only"`; a igualdade exata prova que a mensagem não carrega payload nem id do evento. O payload da linha de teste leva um marcador (`{"marker":"secret-payload-marker"}`) e o helper também confere que a mensagem completa da exceção não o contém. Detalhes que o estilo não determina:
  - Linha de teste: `events.insertIfAbsent("evt_" + UUID.randomUUID(), "PAYMENT_RECEIVED", "{\"marker\":\"secret-payload-marker\"}", NOW)`.
  - O teste do outro papel não é recusa do gatilho: confere SQLSTATE `42501` e mensagem contendo `row-level security`.
  - UPDATE tentado: `update payment_events set event_type = 'PAYMENT_REFUNDED' where provider_event_id = ?`.
  - Upsert tentado: `insert into payment_events (provider_event_id, event_type, payload, received_at) values (?, 'PAYMENT_REFUNDED', '{}'::jsonb, now()) on conflict (provider_event_id) do update set event_type = excluded.event_type`.
  - Outro papel: dentro de uma única transação (`TransactionTemplate`, para tudo correr na mesma conexão e o `set local role` se desfazer sozinho): `create role` com nome único (`probe_<8 hex>`), `grant select, insert on payment_events` a ele, `set local role`; `select count(*) from payment_events where provider_event_id = ?` devolve `0` (a linha existe, inserida antes); um `insert` falha com "row-level security". No fim, `setRollbackOnly()` (desfaz o papel e o grant).

- [ ] **Step 2: Rodar e ver falhar.** (Docker Desktop aberto; conferir a memória livre antes.)
  `cd backend; .\mvnw.cmd test -Dtest=FinancialRecordImmutabilityTest`
  Esperado: FALHA em 5 testes (UPDATE/DELETE/TRUNCATE/upsert passam sem exceção; o outro papel enxerga 1 linha); `shouldStillInsertAndDeduplicate` passa.

- [ ] **Step 3: Escrever a V17** (confirmar o número com a skill `nova-migration`). Conteúdo exato:

```sql
-- SEC-028: the financial record only receives new rows. One generic guard, so a future financial table needs
-- only its two triggers. Same mechanism as audit_events (V10), but with no retention exception.
-- Data is never fixed in place: only a future migration that explicitly disables these triggers (and re-enables
-- them in the same migration) may correct a row, so the change is visible in review.
-- The message carries only the table name, never a column of the row (the payload may hold personal data).
create function financial_record_guard() returns trigger language plpgsql as $$
begin
  raise exception '% is append-only', tg_table_name using errcode = '42501';
end $$;

create trigger payment_events_guard_row before update or delete on payment_events
  for each row execute function financial_record_guard();
create trigger payment_events_guard_truncate before truncate on payment_events
  for each statement execute function financial_record_guard();

-- SEC-028: restricted access. With row level security on and no policy, every role but the owner (the application)
-- sees no rows and inserts none, even if it holds a grant. The Supabase Data API roles exist only on Supabase.
alter table payment_events enable row level security;
revoke all on payment_events from public;
do $$
declare api_role text;
begin
  foreach api_role in array array['anon', 'authenticated', 'service_role'] loop
    if exists (select 1 from pg_roles where rolname = api_role) then
      execute format('revoke all on payment_events from %I', api_role);
    end if;
  end loop;
end $$;
```

- [ ] **Step 4: Rodar e ver passar.** Mesmo comando do Step 2. Esperado: 6 testes, 0 falhas.

- [ ] **Step 5: Rodar os testes do pacote de pagamento** (prova de que o webhook do #26 não mudou; nenhum teste existente pode ser editado):
  `cd backend; .\mvnw.cmd test -Dtest="br.com.sergio.sapiensia.payment.*Test"`
  Esperado: tudo verde.

- [ ] **Step 6: Commit.**
  `git add backend/src/main/resources/db/migration/V17__payment_events_append_only.sql backend/src/test/java/br/com/sergio/sapiensia/payment/FinancialRecordImmutabilityTest.java`
  `git commit -m "feat: make payment_events append-only with restricted access (#27)"`

### Task 2: ADR-0003 e runbooks

**Files:**
- Modify: `docs/adr/0003-pagamento-so-por-pix-via-asaas.md` (um item novo em "Consequences")
- Modify: `docs/runbooks/homologacao-fly.md:115` ("validated 17 migrations (16 migrations mais a linha de baseline)" passa a "18 ... (17 migrations ...)")
- Modify: `docs/runbooks/webhook-asaas.md` (perto da consulta da linha 79: nota de que a tabela não aceita UPDATE/DELETE, e a consulta de conferência abaixo)

- [ ] **Step 1: ADR-0003.** Acrescentar o item: registro financeiro (#27, SEC-028) = `payment_events`; o banco recusa UPDATE, DELETE e TRUNCATE por gatilho (`financial_record_guard`, V17), sem exceção de retenção; RLS ligado sem política e privilégios retirados dos outros papéis; a aplicação só insere. Limite: a aplicação conecta como dona da tabela, então quem tem a credencial do banco pode desligar o gatilho; usuário de migração separado e armazenamento externo imutável foram considerados e adiados (citar o número da issue aberta na decisão 6.5). Correção de dados só por migration que desligue o gatilho de forma explícita. **J4:** o payload é guardado como recebido e pode conter dado pessoal (nome, e-mail, descrição), mesmo com o CPF redigido; num pedido de exclusão do titular, a retenção do registro financeiro prevalece, e qualquer anonimização do payload exige migration e ADR próprios.

- [ ] **Step 2: Runbooks.** Trocar a contagem de migrations e acrescentar ao `webhook-asaas.md` a conferência pós-deploy (só leitura, no SQL Editor do Supabase), com o resultado esperado: `rls = true` e as três colunas `false`:

```sql
select c.relrowsecurity as rls,
       has_table_privilege('anon', 'payment_events', 'select') as anon_le,
       has_table_privilege('authenticated', 'payment_events', 'select') as authenticated_le,
       has_table_privilege('service_role', 'payment_events', 'select') as service_role_le
from pg_class c where c.relname = 'payment_events';
```

- [ ] **Step 3: Check completo** (memória livre conferida antes): `just check-resumo`. Esperado: OK.

- [ ] **Step 4: Commit.** `git commit -m "docs: record the append-only financial record in ADR-0003 and runbooks (#27)"`

## Depois das tasks

- Revisão final de segurança (modelo do plano), skill `verificar-entrega` com a tabela, e o diff para o Sérgio aprovar.
- Não há tela nem fluxo novo no navegador: `/verificar-tela` e e2e não se aplicam (a issue pede teste de integração do banco).
- PR com `Closes #27`, citando SEC-028, só depois do OK ao push.

## Riscos

- **A V17 falhar no Supabase** por alguma diferença de permissão (por exemplo, `revoke` de um papel cujo grant veio de outro papel não dá erro, só não tem efeito). A conferência do runbook mostra se sobrou acesso; se sobrar, é correção neste ticket.
- **Dados de sandbox presos** na homologação (decisão 6.4).
- **Falsa sensação de imutabilidade:** o gatilho não é defesa contra quem tem a senha do banco (seção 2). Fica escrito no ADR.
