# Códigos de recuperação do MFA e auditoria (#16) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** APROVADO pelo Sérgio em 08/11/2026 com os ajustes da seção "Ajustes do OK". Backend primeiro (Tasks 1 a 4, sem rodar o `just check` inteiro até o fim do backend); telas (Task 5) e qualquer texto novo para o usuário só depois da aprovação da seção 4. Sem push, PR nem merge sem OK expresso.

**Goal:** Ao ativar o MFA, o Administrador recebe 10 códigos de recuperação de uso único, e cada ação sensível dele deixa um registro de auditoria sem dado sensível.

**Architecture:**
- Códigos: 120 bits aleatórios, guardados só como SHA-256 na tabela nova `administrator_recovery_codes`. Consumo por `UPDATE` condicional, dentro do mesmo `guarded()` do TOTP (mesma conta no limitador).
- Auditoria: pacote novo `audit`, tabela `audit_events` cujas colunas só aceitam UUID, IP, data e palavras em maiúsculas. Não existe coluna de texto livre.
- Correlation ID: gerado pelo servidor em um filtro antes do Spring Security; vai para o MDC (contexto do log), para o cabeçalho `X-Correlation-Id` e para a auditoria.

**Tech Stack:** Spring Boot 4.1.2 / Spring Security 7.1.2, JdbcClient, Flyway, db-scheduler, React + TanStack Query + React Hook Form + Zod, Vitest, Playwright. Sem dependência nova.

**Spec:** `docs/spec/sapiensia-spec.md` (SEC-006, SEC-008, SEC-014, SEC-036, SEC-037, SEC-065); `docs/adr/0004-identidade-e-mfa.md`; `docs/adr/0006-meta-de-seguranca-asvs-nivel-2.md`; issue #16 (pai #5); OWASP ASVS 5.0, V6.5.1 a V6.5.4 (texto conferido na fonte oficial, tag v5.0.0).

## IDs cobertos

| ID | Como | Onde |
|---|---|---|
| ADR-0004 | 10 códigos de uso único; ADR atualizado | Tasks 2, 3, 6 |
| ADR-0006 | Nada de CPF, e-mail de Cliente, token ou senha em log ou auditoria; ADR atualizado | Tasks 1, 4, 6 |
| SEC-036 | "Evitar PII desnecessária em logs" (teste negativo). A criptografia de campo não se aplica: os códigos ficam só como hash | Tasks 2, 4 |
| SEC-065 | Ator, ação, objeto, timestamp UTC, resultado, correlation ID e origem | Tasks 1, 3 |
| SEC-037 | Retenção definida para a auditoria | Task 1 |
| SEC-006 | Usar um código de recuperação encerra as outras sessões | Task 2 |
| SEC-014 | Limite compartilhado com o MFA | Task 2 |
| SEC-008 | Reautenticação (código TOTP, com o replay guard) para gerar códigos novos | Task 3 |
| SEC-007 | **Não exige** aviso por e-mail ao usar um código de recuperação (leitura literal em "Ajustes do OK", item 5); o aviso vai para a issue de fora do ticket | — |
| ASVS 5.0.0, 6.5.1 e 6.5.2 | Uso único; SHA-256 simples permitido a partir de 112 bits (D2) | Task 2 |

---

## Ajustes do OK (08/11/2026)

| # | Ajuste | Onde ficou |
|---|---|---|
| 1 | Citar o item exato do ASVS (versão e número) | D2 |
| 2 | Falha em transação própria; sucesso na transação da ação | D6 |
| 3 | `audit_events` não editável nem apagável, exceto retenção; mecanismo e teste | D6, V10, `AuditImmutabilityTest` |
| 4 | Gerar de novo: sessão completa, replay guard do TOTP, evento de auditoria | D4 |
| 5 | SEC-007 lido literalmente | abaixo |
| 6 | Issue "trocar autenticador" e passo a passo em `docs/` | abaixo e Task 6 |
| 7 | IP da auditoria só é confiável depois do #56, anotado para o #7 | abaixo e Riscos |

### Item 5: SEC-007 exige aviso por e-mail ao usar um código de recuperação?

Texto literal (`docs/spec/sapiensia-spec.md`, linhas 351 e 352): *"SEC-007 — Recuperação com token aleatório, uso único, expiração curta, resposta que não revele existência da conta e notificação ao titular."*

**Conclusão: não exige, e o aviso não entra neste ticket.** Motivos:
- As cinco propriedades descrevem um fluxo de recuperação **por token**. Um código de recuperação não é enviado a ninguém, não expira e é usado depois de a senha já ter sido verificada, então não há "existência da conta" a esconder. O item só faria sentido inteiro para a recuperação de senha, que é como o ADR-0004 já o lê (linha 37: o aviso do SEC-007 é o do #12, o e-mail depois da troca de senha).
- Nenhum outro item da spec pede aviso ao titular por entrada com código de recuperação.

**Ressalva honesta:** "notificação ao titular" é genérico, e o SEC-006 lista "recuperação" e "remoção de MFA" entre os eventos de risco. Alguém que roubou senha e códigos entraria exatamente assim. Por isso o aviso fica numa issue de fora do ticket, para ser decidido antes do gate #47. Ele traz texto novo para o usuário (decisão de produto) e o canal de e-mail do Administrador. O dado já existe: o evento `ADMIN_RECOVERY_CODE_LOGIN` fica na auditoria.

### Item 6: trocar o autenticador do Administrador

Issue nova (rascunho abaixo; só abro com o OK do texto): troca do autenticador com a sessão completa. O procedimento manual do ADR-0004 vira um passo a passo em **`docs/runbooks/recuperar-acesso-administrador.md`** (Task 6): apagar a linha, reiniciar, o bootstrap manda o link, refazer a senha e o MFA, e os efeitos nesta entrega (os códigos de recuperação saem junto, pelo `on delete cascade`; os registros de auditoria ficam, porque não têm chave estrangeira). O ADR-0004 passa a apontar para esse arquivo.

**Rascunho da issue**

> **Título:** Administrador troca o autenticador (celular novo ou perdido)
>
> **Parent:** #5 · **Depende de:** #16
>
> **What to build:** Hoje o autenticador do Administrador só muda pelo procedimento manual (apagar a linha no banco e refazer tudo). O Administrador logado, ou que entrou com um código de recuperação, troca o autenticador sozinho: o segredo novo só vale depois de confirmado com um código dele; o antigo para de valer no mesmo instante; as outras sessões caem; a ação fica na auditoria.
>
> **Acceptance criteria**
> - [ ] Com a sessão completa, a troca exige o código atual do autenticador (mesma proteção contra repetição e o mesmo limite de tentativas do MFA)
> - [ ] Quem entrou com um código de recuperação (sem o celular) consegue cadastrar o autenticador novo, sem o procedimento manual
> - [ ] O segredo novo só substitui o antigo depois que um código dele for confirmado; antes disso o antigo continua valendo
> - [ ] Ao trocar, as outras sessões do Administrador são encerradas e os códigos de recuperação restantes deixam de valer (ou são regerados, a decidir)
> - [ ] Registro de auditoria da troca (sucesso e falha), sem segredo nem código
> - [ ] Teste e2e do fluxo, sem mocks
> - [ ] Requisitos: ADR-0004, SEC-001, SEC-006, SEC-008, SEC-065

### Item 7: IP da auditoria

A coluna `origin` guarda o IP do `ClientIpResolver`. Sem o `Fly-Client-IP` ligado (#56), todos os acessos chegam com o IP do proxy do Fly.io e a origem na auditoria não identifica ninguém. **Só é confiável depois do #56.** Anotado no ADR-0004 (Task 6) e nos Riscos. Para o **#7** (Homologação no ar), que já tem o item "Ligar Fly-Client-IP": depois de ligar, entrar uma vez e conferir que `audit_events.origin` mostra o IP de quem entrou, não o do proxy. Esse texto é um comentário que sugiro para o #7; só posto com o seu OK.

---

## 1. Decisões de projeto

Marcadas com **[OK seu]**: regra de negócio ou produto, que pedem a sua aprovação expressa.

### D1. Formato e quantidade

**Recomendação:** 10 códigos (ADR-0004 e issue). Cada um com 15 bytes do `SecureRandom` (120 bits) em Base32 com o `Totp.toBase32` que já existe: 24 caracteres `A-Z2-7`, exibidos em grupos de 4 (`ABCD-EFGH-IJKL-MNOP-QRST-UVWX`).

**Motivo:** o ASVS 6.5.2 só permite hash comum (SHA-256) a partir de 112 bits; abaixo disso exige algoritmo de senha com sal. O Base32 não tem `0` nem `1`, então não há confusão com `O` e `I` ao digitar, e não precisa de código novo de codificação. A entrada ignora hífens e espaços e aceita minúsculas. Custo: 24 caracteres é longo, mas só se digita em emergência (por isso o botão "Copiar códigos").

**Alternativa descartada:** 16 caracteres (80 bits) com Argon2id. O texto a digitar fica menor, mas cada tentativa custa até 10 cálculos de Argon2id e o servidor precisa de mais lógica.

### D2. Como guardar e comparar

**Recomendação:** guardar só `Tokens.hash(normalizado)` (SHA-256 em hex, o helper dos links), nunca o texto. Para conferir: ler os hashes **não usados daquele Administrador** (no máximo 10) e comparar **todos** com `MessageDigest.isEqual`, sem sair do laço no primeiro acerto.

**Base no ASVS:** OWASP ASVS **5.0.0** (tag `v5.0.0` do repositório OWASP/ASVS, arquivo `V6 Authentication`), requisito **6.5.2, nível 2**: *"A standard hash function can be used if the secret has 112 bits of entropy or more."* Abaixo de 112 bits o mesmo requisito exige algoritmo de senha com sal de 32 bits. Nossos 120 bits passam, então SHA-256 simples basta. Também valem o **6.5.1** (uso único), o **6.5.3** (gerado por CSPRNG, o `SecureRandom`) e o **6.5.4** (mínimo de 20 bits). O arquivo do ASVS não traz número de versão no texto; a versão é a do tag. O ADR-0006 diz só "ASVS nível 2": o ADR-0004 passa a citar a 5.0.0.

**Motivo:** procurar direto por `where code_hash = ?` também seria seguro com 120 bits (é o que os links fazem), mas você pediu tempo constante, e com 10 linhas ele custa uma consulta só. O escopo por Administrador também impede que o código de uma conta valha em outra.

### D3. Uso único

**Recomendação:** `update ... set used_at = now() where id = :id and used_at is null`; zero linhas = recusa (`400 CODE_INVALID`). Dois pedidos simultâneos com o mesmo código dão um sucesso e uma recusa, o mesmo desenho do `totp_last_step`.

Tudo isso em uma transação com duas outras escritas, nesta ordem: (1) incrementa `session_version` (trava a linha de `administrators`), (2) consome o código, (3) grava a auditoria. A ordem importa: gerar códigos novos também começa travando `administrators`, então as duas operações nunca se cruzam em deadlock.

**Rota nova:** `POST /api/admin/sessions/mfa/recovery-code`, liberada só para o estado pendente (`AdminMfaAccess`), com as mesmas respostas do TOTP (`204`, `400`, `409`, `429`, `503`).

**Encerra as outras sessões:** o SEC-006 pede invalidar sessões após recuperação, e quem usa um código pode estar reagindo a um roubo. A sessão aberta já nasce com a nova `session_version`.

**Fator:** a sessão aberta por código é o mesmo `AdministratorPrincipal`, com `totpVerifiedAt` = agora. Criar um terceiro fator exigiria mudar o `panel()` sem ganho nesta fase; a auditoria registra que foi por código.

**Exibição única:** a confirmação da inscrição passa de `204` para `200 {"codes":[...]}` com `Cache-Control: no-store`. Ativação, códigos e auditoria na mesma transação. O `toString()` dos records com códigos é redigido, porque o Spring MVC em DEBUG imprime o corpo da resposta via `toString`.

### D4. Gerar códigos de novo **[OK seu]**

**Recomendação (com o ajuste 4):** entra no ticket, como `POST /api/admin/recovery-codes`. Exige **sessão completa** (senha e TOTP: `panel(Capability.RECOVERY_CODES_MANAGE)` confere a capacidade e os dois fatores, então uma sessão pendente recebe `401`, como em toda rota do painel; na tela, o `401` marca a sessão do painel como encerrada e o app leva sozinho a `/painel/entrar`, sem mensagem própria) **e** um código TOTP atual no corpo, que passa pelo **mesmo replay guard** do login (`acceptStep`: passo maior que o último aceito, um `UPDATE` condicional que trava a linha). Na mesma transação: aceita o passo, apaga **todos** os códigos da conta, grava 10 novos e grava o evento `ADMIN_RECOVERY_CODES_REGENERATED`. Os antigos morrem na hora. Código errado ou repetido conta no mesmo limite do MFA e grava o evento `FAILURE` ou `BLOCKED` (D6).

**Motivo:** sem isso não há saída para (a) a resposta da ativação perdida na rede, (b) quem ativou o MFA antes do #16 e tem zero códigos, como o seu banco local, ou (c) quem usou vários. Exigir TOTP e não só a sessão impede que uma sessão roubada troque os códigos (SEC-008).

**Limite que você precisa conhecer:** se o celular foi perdido, não dá para gerar códigos novos nem trocar o autenticador. Os códigos dão até 10 entradas, e a troca definitiva continua sendo o procedimento manual do ADR-0004. "Trocar autenticador" é uma funcionalidade nova, e vira a issue própria do item 6 dos Ajustes.

**Capacidade nova:** `RECOVERY_CODES_MANAGE` (o `Capability` pede uma por ticket do painel).

### D5. Limite de tentativas

**Recomendação:** o mesmo `guarded()` e a mesma chave `admin-mfa:<id>`: 5 erros somados entre TOTP, confirmação, código de recuperação e geração bloqueiam por 15 minutos (`429` + `Retry-After`), inclusive para o código certo, e o limite por IP é o já compartilhado. Sem contador separado, porque um contador por tipo daria ao atacante 5 tentativas a mais. Código malformado conta como erro, como no TOTP.

O bloqueio fixo que o ADR-0004 deixou para o #16 reavaliar **fica como está**: chegar a esta etapa exige a senha certa, e os códigos não contornam o limite da senha. O ADR registra a reavaliação.

### D6. O que a auditoria registra e onde

**Recomendação:** tabela `audit_events` no PostgreSQL (é a entidade `AuditEvent` da spec), acesso pelo serviço `AuditLog` do pacote novo `audit`, que os tickets #17 e #22 vão reusar.

| Campo (SEC-065) | Coluna | Conteúdo |
|---|---|---|
| Ator | `actor_id uuid` | Id do Administrador; nulo em senha errada |
| Ação | `action` | `ADMIN_PASSWORD_LOGIN`, `ADMIN_MFA_LOGIN`, `ADMIN_MFA_ENABLED`, `ADMIN_RECOVERY_CODE_LOGIN`, `ADMIN_RECOVERY_CODES_REGENERATED`, `ADMIN_PASSWORD_SET` |
| Objeto | `object_type`, `object_id uuid` | `ADMINISTRATOR` + id; nulos em senha errada |
| Quando | `occurred_at timestamptz` | UTC, do `Clock` da aplicação |
| Resultado | `result` | `SUCCESS`, `FAILURE`, `BLOCKED` |
| Correlation ID | `correlation_id uuid` | D8 |
| Origem | `origin inet` | IP do `ClientIpResolver` |

- **Senha errada não identifica ninguém:** o campo e-mail é texto digitado (pode ser o e-mail de um Cliente, ou até a senha digitada no campo errado), então não é gravado.
- **`429` na etapa de senha não é auditado:** é a única tentativa que qualquer um repete sem limite, e encheria a tabela. O `429` das etapas de código é (`BLOCKED`), porque só chega lá quem acertou a senha. A entrada rejeitada antes do limitador (e-mail vazio ou gigante) também não é auditada.
- **Sem chave estrangeira em `actor_id` e `object_id`:** o procedimento manual do ADR-0004 apaga a linha do Administrador, e a trilha precisa sobreviver a isso.
- **Transações (ajuste 2):**
  - **Sucesso** (`SUCCESS`) grava na **mesma transação da ação**, por `AuditLog.recordInTransaction`, que usa `Propagation.MANDATORY` (sem transação aberta é erro de programação, não um registro perdido). Se o registro falhar, a ação desfaz e a resposta é `503`: sem trilha, sem ação. Ação que não escreve no banco (a senha certa, que só abre a sessão pendente) grava o evento antes de abrir a sessão e também responde `503` se falhar.
  - **Falha** (`FAILURE`, `BLOCKED`) grava em **transação própria** (`Propagation.REQUIRES_NEW`, por `AuditLog.recordIndependent`), para não sumir se a transação da ação for desfeita. É melhor esforço: se o registro falhar, o log leva só a classe do erro e a resposta não muda. A transação própria não disputa trava com a da ação, porque a tabela não tem chave estrangeira.
  - `UNAVAILABLE` (`503`) e `NOT_ENROLLED` (`409`) não são auditados: não são falha de código, e o banco pode ser justamente o que caiu.
- **Imutável (ajuste 3):** a aplicação não edita nem apaga a trilha, exceto a limpeza de retenção. O mecanismo é um **gatilho no banco** (SQL na seção 2): `UPDATE` e `TRUNCATE` sempre falham; `DELETE` só passa para linha com mais de 365 dias, o **piso de retenção** gravado na migration. Por consequência, `app.audit.retention` tem de ser no mínimo `P365D`: o `AuditProperties` recusa um valor menor e a aplicação não sobe. A limpeza calcula o corte no próprio SQL (`now() - make_interval(...)`), com o relógio do banco, o mesmo que o gatilho usa. Mudar o piso exige uma migration nova, de propósito. **Limite:** quem é dono da tabela (o mesmo usuário das migrations) ainda pode desligar o gatilho. Ele protege contra bug, SQL injection e qualquer código da aplicação, não contra quem tem as credenciais do banco; separar um usuário só de gravação é infraestrutura (#7) e vai para a issue de fora do ticket.
- **Sem tela nem rota de consulta** nesta entrega: a issue pede o registro.

### D7. Retenção **[OK seu]**

**Recomendação:** 12 meses (`app.audit.retention: P365D`), com limpeza diária às 03:30 por tarefa recorrente do db-scheduler, igual à `UnconfirmedCustomersCleanup`; o log traz só a quantidade apagada.

**Motivo:** a spec manda definir (SEC-037) mas não dá número. O IP é dado pessoal, então a retenção não deve ser indefinida, e 12 meses cobre uma investigação que só começa meses depois. O Marco Civil pede 6 meses para registros de acesso a aplicações. Esse número é uma recomendação técnica: vale a sua decisão, e uma conferida jurídica antes do gate #47.

### D8. Correlation ID

**Recomendação:** `CorrelationIdFilter` com a maior precedência, antes do Spring Security, para que até `401` e `403` tenham um. UUID novo a cada requisição, no MDC (`correlationId`) e no cabeçalho de resposta `X-Correlation-Id`.

**Nunca aceito do cliente:** um cabeçalho de entrada seria um jeito de plantar e-mail ou texto arbitrário no log e na auditoria. No log: JSON ECS em produção (a conferir por teste que o campo do MDC aparece) e `logging.pattern.correlation` no console (propriedade confirmada no jar do Spring Boot 4.1.2).

### D9. Como garantir que nada sensível entra

Quatro travas independentes:
1. **Pela API:** `AuditLog` não recebe `String` livre; só enums, UUIDs e o IP já validado pelo `ClientIpResolver`.
2. **Pelo banco:** `uuid`, `inet`, `timestamptz` e `check (col ~ '^[A-Z_]+$')` nas três colunas de palavra. Um e-mail, CPF (com dígitos) ou token não é aceito pelo Postgres. Não existe coluna `details`.
3. **Pelos objetos:** `toString()` redigido nos records com código ou hash.
4. **Por teste:** o `AuditPrivacyTest` (Task 4) percorre o fluxo com log em TRACE e formato ECS e procura os valores no log e na tabela; outro teste fixa a lista exata de colunas, para que uma coluna nova não passe despercebida.

---

## 2. Migrations (próximo número livre: V10 (auditoria, Task 1); V11 (códigos, Task 2))

Criadas com a skill `nova-migration`, que confere a numeração na hora.

**`V11__create_administrator_recovery_codes.sql`**

```sql
create table administrator_recovery_codes (
  id uuid primary key default gen_random_uuid(),
  administrator_id uuid not null references administrators (id) on delete cascade,
  code_hash varchar(64) not null,
  used_at timestamp with time zone,
  created_at timestamp with time zone not null default now()
);
create index administrator_recovery_codes_administrator_id_idx on administrator_recovery_codes (administrator_id);
```

**`V10__create_audit_events.sql`**

```sql
create table audit_events (
  id uuid primary key default gen_random_uuid(),
  occurred_at timestamp with time zone not null,
  actor_id uuid,
  action varchar(64) not null check (action ~ '^[A-Z_]+$'),
  object_type varchar(32) check (object_type ~ '^[A-Z_]+$'),
  object_id uuid,
  result varchar(16) not null check (result ~ '^[A-Z_]+$'),
  correlation_id uuid not null,
  origin inet
);
create index audit_events_occurred_at_idx on audit_events (occurred_at);

-- append-only: UPDATE and TRUNCATE never; DELETE only of rows older than the 365-day retention floor
create function audit_events_guard_row() returns trigger language plpgsql as $$
begin
  if tg_op = 'DELETE' then
    if old.occurred_at < now() - interval '365 days' then
      return old;
    end if;
  end if;
  raise exception 'audit_events is append-only' using errcode = '42501';
end $$;

create function audit_events_guard_truncate() returns trigger language plpgsql as $$
begin
  raise exception 'audit_events is append-only' using errcode = '42501';
end $$;

create trigger audit_events_guard_row before update or delete on audit_events
  for each row execute function audit_events_guard_row();
create trigger audit_events_guard_truncate before truncate on audit_events
  for each statement execute function audit_events_guard_truncate();
```

Dois cuidados: `old` não existe num gatilho de `TRUNCATE`, por isso são duas funções; e o gatilho de linha tem o `if` aninhado para nunca ler `old` fora do `DELETE`. Os testes não conseguem limpar `audit_events` entre si (o gatilho impede de propósito): cada teste filtra pelas suas linhas, por `actor_id` ou `correlation_id`.

---

## 3. Arquivos e testes

### Criar

| Arquivo | Responsabilidade |
|---|---|
| `backend/.../audit/AuditLog.java` | `recordInTransaction(...)` (`MANDATORY`, falha fechada) e `recordIndependent(...)` (`REQUIRES_NEW`, melhor esforço) |
| `backend/.../audit/AuditProperties.java` | `app.audit.retention`, no mínimo `P365D` (o piso do gatilho) |
| `backend/.../audit/AuditEntry.java`, `AuditAction.java`, `AuditResult.java`, `AuditObject.java` | Tipos fechados da entrada |
| `backend/.../audit/AuditEvents.java` | Repositório (insert e apagar vencidos) |
| `backend/.../audit/AuditRetentionCleanup.java` | Tarefa diária |
| `backend/.../audit/CorrelationIdFilter.java` | D8 |
| `backend/.../admin/RecoveryCodes.java` | Gerar, normalizar, comparar em tempo constante |
| `backend/.../admin/AdministratorRecoveryCodes.java` | Repositório da tabela |
| `backend/.../admin/AdminRecoveryCodesController.java` | Geração de códigos novos |
| `backend/.../admin/RecoveryCodeList.java` | Resposta `{codes}`, `toString` redigido |
| `frontend/src/features/admin/RecoveryCodesList.tsx` | Exibição única, copiar, confirmação |
| `frontend/src/features/admin/RecoveryCodesSection.tsx` | Seção do painel |

### Alterar

| Arquivo | Mudança |
|---|---|
| `backend/.../admin/AdministratorMfa.java` | `recover`, `regenerateCodes`; `guarded` recebe `UUID` e a `AuditAction`; `Check` devolve `Outcome`; `Outcome` ganha códigos e nova versão de sessão (`toString` redigido); auditoria |
| `backend/.../admin/AdminMfaController.java` | Rota do código de recuperação; confirmação devolve `200` com os códigos |
| `backend/.../admin/Administrators.java` | `incrementSessionVersion` |
| `backend/.../admin/AdminSessionController.java` | Auditoria da senha |
| `backend/.../admin/AdministratorPasswordController.java` | Auditoria da senha definida (só sucesso) |
| `backend/.../admin/Capability.java`, `security/SecurityConfig.java` | Capacidade e as 2 rotas |
| `backend/.../customer/PasswordLogin.java`, `customer/SessionController.java` | `Variant` ganha um aviso de senha errada, chamado só em tentativa admitida pelo limitador (sem efeito para o Cliente) |
| `backend/src/main/resources/application.yml`, `backend/src/test/resources/application.properties` | `app.audit.retention`, padrão de log |
| `frontend/src/features/admin/AdminMfaPage.tsx`, `AdminHomePage.tsx`, `api.ts`, `hooks.ts`, `schemas.ts` | Modo "recuperação", tela dos códigos, seção do painel |
| `frontend/e2e/admin-panel.e2e.ts` | Tela dos códigos e novo teste |
| `docs/adr/0004-identidade-e-mfa.md`, `docs/adr/0006-meta-de-seguranca-asvs-nivel-2.md`, `CLAUDE.md` | Decisões; `AuditLog` em "Reaproveitar antes de criar" |

**Contrato alterado, e testes que mudam junto** (`204` passa a `200`, sem afrouxar nenhuma asserção): `AdminMfaEnrollmentTest` (linhas 146, 298 e o auxiliar da 339), `AdminMfaLogTest:131`. `AdminMfaLoginTest:118` e `AdminSessionTest:120` ganham a capacidade nova na lista exata.

### Testes

**Backend**

| Teste | Prova |
|---|---|
| `RecoveryCodesTest` (unitário) | 10 distintos, formato, 120 bits; normalização (hífen, espaço, minúscula); recusa de tamanho, caractere, nulo e entrada gigante; acerta na primeira e na última posição |
| `AdminRecoveryCodesTest` (integração) | ADR-0004: 10 códigos só na confirmação, com `no-store`; banco só com hash; cada código funciona uma vez; dois em paralelo dão um `204` e um `400`; SEC-014: 5 erros somados com TOTP dão `429` mesmo com o código certo, e o código não é gasto; `409` sem MFA ativo; SEC-006: as outras sessões caem; ID de sessão e CSRF novos; geração invalida os antigos; TOTP errado ou reusado mantém os antigos; duas gerações em sequência sempre deixam exatamente 10 |
| `AuditLogTest` (integração) | SEC-065: uma linha exata por ação e resultado, com correlation ID igual ao do cabeçalho; senha errada sem ator; sucesso e ação na mesma transação: um gatilho temporário faz o insert em `audit_events` falhar, a confirmação responde `503`, o MFA não ativa e nenhum código fica gravado (sem mock); falha em transação própria: `recordIndependent` chamado dentro de uma transação que depois desfaz mantém a linha; `recordInTransaction` fora de uma transação é recusado |
| `AuditImmutabilityTest` (integração) | Ajuste 3: `UPDATE` de uma linha falha e a linha não muda; `DELETE` de linha nova falha e ela continua lá; `TRUNCATE` falha; `DELETE` de linha com mais de 365 dias passa; o corte do gatilho é de 365 dias (linha de 364 dias não apaga, a de 366 apaga). Cada teste filtra pelas suas linhas |
| `CorrelationIdFilterTest` | Presente em `200`, `401` e `403`; muda a cada requisição; o valor enviado pelo cliente é ignorado; MDC limpo no fim |
| `AuditRetentionCleanupTest` | SEC-037: apaga só o vencido (linhas de 400 e 100 dias: sai a de 400); devolve a quantidade apagada |
| `AuditPropertiesTest` (unitário) | `app.audit.retention` abaixo de `P365D`, zero, negativo ou nulo é recusado; `P365D` e maiores passam |
| **`AuditPrivacyTest`** (negativo) | ADR-0006 / SEC-036 / SEC-065: ver abaixo |
| `AdminMfaBypassTest`, `AdminRouteDenyByDefaultTest` | Matriz das 2 rotas novas: anônimo `401`, Cliente `403`, sessão pendente, pendente vencida, Administrador completo |

Nenhuma rota nova recebe ID de objeto, então o teste negativo de `404` entre usuários não se aplica.

**`AuditPrivacyTest`:** contexto isolado, log em TRACE, formato ECS (o de produção), senha, TOTP e os códigos passando pelo fluxo inteiro: senha errada com um **e-mail de Cliente** no campo e-mail; senha errada com um **CPF fictício** no campo e-mail e no campo senha; ativação; login com TOTP; código de recuperação certo, errado e repetido; bloqueio; geração; link de senha inválido; `X-Correlation-Id` e o cabeçalho de IP forjados com o e-mail. Procura no log inteiro **e** em `select t::text from audit_events t`: e-mail do Cliente e do Administrador, CPF com e sem máscara, senha, token do link, todos os códigos (com e sem hífen) e os hashes. Também fixa a lista de colunas e os `toString()`.

**Frontend (Vitest):** tela dos códigos (10 itens; "Continuar" só depois da confirmação; copiar com sucesso e falha), modo recuperação em `AdminMfaPage` (validação, erro, `429`, voltar), `RecoveryCodesSection` (fluxo, erros, cancelar). `/verificar-tela` em desktop, celular e teclado.

**E2E (`admin-panel.e2e.ts`, API e banco de verdade, sem `page.route`):**
- Teste existente: no caminho de inscrição (CI, banco novo), aparece a tela com 10 códigos e o painel só abre depois da confirmação.
- Novo: `ADR-0004 / SEC-065: o código de recuperação entra uma única vez e os antigos morrem ao gerar novos`. Entra com TOTP → gera códigos no painel (funciona no banco local já ativado) → sai → entra com o código 1 → sai → repete o código 1 e é recusado → entra com o código 2 → gera de novo com TOTP → sai → código 3 antigo recusado.
- Fica no mesmo arquivo porque só há um Administrador. Os códigos são lidos e ocultados no DOM na mesma chamada, como o `readAndHideKey`, e digitados com `fillSecret`.

## Review Focus

1. Resposta da ativação perdida (rede caiu depois do commit): o MFA fica ativo sem códigos vistos; a saída é gerar no painel depois de entrar com TOTP. Coberto pelo e2e novo, que gera códigos numa conta sem nenhum.
2. Código colado com espaços, hífen ou minúsculas: aceito. `RecoveryCodesTest`.
3. Gerar códigos logo depois de entrar com TOTP: o mesmo código do login é recusado (anti-replay); a mensagem manda aguardar o próximo. `AdminRecoveryCodesTest` e teste de `RecoveryCodesSection`.
4. Recarregar a página na tela dos códigos: eles não voltam, e a sessão completa continua; o painel oferece gerar novos. Teste de `AdminMfaPage`.
5. Auditoria fora do ar em plena ativação: `503` e nada ativado. `AuditLogTest` (gatilho).

## Global Constraints

- Nunca em log, resposta de erro, teste ou trace: código de recuperação, hash, senha, token, e-mail, CPF.
- Textos do usuário: só a lista da seção 4. Os iguais aos existentes reutilizam as constantes atuais.
- Sem dependência nova. Sem push, PR ou merge sem OK.

---

## Tarefas de execução

### Task 1: Auditoria e correlation ID (base)

**Files:** criar `audit/*` (inclui `AuditProperties`), `V10__create_audit_events.sql` (tabela e gatilhos); alterar `application.yml`, `application.properties`. Test: `AuditLogTest` (parte do `AuditLog`), `AuditImmutabilityTest`, `AuditPropertiesTest`, `CorrelationIdFilterTest`, `AuditRetentionCleanupTest`.

**Interfaces — Produces:** `AuditLog.recordInTransaction(AuditEntry)` (`MANDATORY`) e `AuditLog.recordIndependent(AuditEntry)` (`REQUIRES_NEW`, melhor esforço); `record AuditEntry(AuditAction action, AuditResult result, UUID actorId, AuditObject objectType, UUID objectId, String origin)`. O `AuditLog` lê o `correlationId` do MDC (UUID novo se não houver) e tira o sufixo `%zona` de um IPv6.

- [ ] Testes falhando → rodar → implementar → rodar: `cd backend; .\mvnw.cmd test -Dtest='AuditLogTest,AuditImmutabilityTest,AuditPropertiesTest,CorrelationIdFilterTest,AuditRetentionCleanupTest'`
- [ ] Commit `feat: add audit log, correlation id and retention cleanup`

### Task 2: Códigos de recuperação (ativação, uso, limite)

**Files:** criar `RecoveryCodes`, `AdministratorRecoveryCodes`, `RecoveryCodeList`, `V11__...sql`; alterar `AdministratorMfa`, `AdminMfaController`, `Administrators`, `SecurityConfig`. Test: `RecoveryCodesTest`, `AdminRecoveryCodesTest`, `AdminMfaBypassTest`, `AdminMfaEnrollmentTest`, `AdminMfaLogTest`.

**Interfaces — Consumes:** `AuditLog`. **Produces:** `POST /api/admin/sessions/mfa/recovery-code` (`{"code"}`, reusa `MfaCodeRequest`); confirmação `200 {"codes":[10 strings]}`; `AdministratorMfa.recover(PendingAdministrator, String code, String clientIp)` → `Outcome` com a nova versão de sessão; `RecoveryCodes.generate()`, `.normalize(String)` → `Optional<String>`, `.matching(List<Unused>, String)` → `Optional<UUID>`.

- [ ] TDD como na Task 1, usando `TransactionTemplate` dentro do `Check` (o `guarded` pega a exceção de fora da transação)
- [ ] Commit `feat: add one-time recovery codes to administrator MFA`

### Task 3: Gerar novos códigos e auditar as ações

**Files:** criar `AdminRecoveryCodesController`; alterar `AdministratorMfa`, `Capability`, `SecurityConfig`, `AdminSessionController`, `AdministratorPasswordController`, `PasswordLogin`, `SessionController`. Test: `AdminRecoveryCodesTest`, `AuditLogTest`, `AdminMfaBypassTest`, `AdminRouteDenyByDefaultTest`, `AdminMfaLoginTest`, `AdminSessionTest`.

**Produces:** `POST /api/admin/recovery-codes` (`{"code"}` TOTP) → `200 {"codes":[...]}`, `no-store`, exige `panel(Capability.RECOVERY_CODES_MANAGE)`.

- [ ] TDD; dentro da transação, `acceptStep` sempre primeiro
- [ ] Commit `feat: regenerate recovery codes and audit administrator actions`

### Task 4: Teste negativo de log e auditoria

**Files:** Test: `backend/src/test/java/br/com/sergio/sapiensia/admin/AuditPrivacyTest.java`.

- [ ] Escrever o teste (`@DisplayName("ADR-0006 / SEC-036 / SEC-065: ...")`), rodar, e corrigir no código qualquer vazamento que ele achar. Conferir que ele falha ao inserir de propósito um log com o e-mail, e desfazer.
- [ ] Commit `test: guard logs and audit against sensitive data`

### Task 5: Telas (só depois de aprovados os textos)

**Files:** criar `RecoveryCodesList.tsx`, `RecoveryCodesSection.tsx` e testes; alterar `AdminMfaPage.tsx`, `AdminHomePage.tsx`, `api.ts`, `hooks.ts`, `schemas.ts`. Schema: remove espaços e hífens, passa a maiúsculas, `^[A-Z2-7]{24}$`.

- [ ] TDD com Vitest; `/verificar-tela`
- [ ] Commit `feat: show and use recovery codes in the panel`

### Task 6: E2E, ADRs e verificação final

**Files:** `frontend/e2e/admin-panel.e2e.ts`, ADR-0004 (inclui a nota do IP, o ASVS 5.0.0 e o link para o passo a passo), ADR-0006, `CLAUDE.md`, e o novo **`docs/runbooks/recuperar-acesso-administrador.md`** (o procedimento manual do ADR-0004 em passos numerados, com o comando para cada passo e o que muda nesta entrega).

- [ ] E2E contra o sistema real (`just db` na pasta principal, `just dev-back`, `just dev-front`, `just e2e`)
- [ ] `just check-resumo`; skill `verificar-entrega`, com a tabela
- [ ] Commit `docs: record recovery codes and audit decisions`
- [ ] Parar para o OK de push e PR (`Closes #16`, citando ADR-0004, ADR-0006, SEC-036, SEC-065)

---

## 4. Textos novos que o usuário vê (para aprovar)

**Tela dos códigos (depois de ativar o MFA)**

| Onde | Texto |
|---|---|
| Título | Guarde seus códigos de recuperação |
| Explicação | Se você perder o celular, cada código abaixo permite entrar uma única vez no lugar do código do aplicativo. |
| Aviso | Estes códigos aparecem só agora. Guarde-os no seu gerenciador de senhas. Quem tiver um código e a sua senha entra no painel. |
| Nome da lista (leitor de tela) | Códigos de recuperação |
| Exibição | Cada código em grupos de 4 separados por hífen (ABCD-EFGH-...); a cópia continua sem hífen, um código por linha |
| Botão | Copiar códigos |
| Sucesso ao copiar | Códigos copiados. Cole-os no seu gerenciador de senhas agora. |
| Falha ao copiar | Não foi possível copiar. Selecione os códigos e copie manualmente. |
| Caixa de confirmação | Guardei meus códigos em um lugar seguro |
| Botão | Continuar para o painel |

**Entrada com código de recuperação**

| Onde | Texto |
|---|---|
| Botão na tela do código | Perdeu o celular? Use um código de recuperação |
| Título | Entrar com código de recuperação |
| Instrução | Digite um dos seus códigos de recuperação. Cada código só funciona uma vez. |
| Rótulo | Código de recuperação |
| Validação | Digite o código de recuperação completo, com 24 letras e números. |
| Erro | Código de recuperação incorreto ou já usado. Confira e tente de novo. |
| Botão para voltar | Usar o código do aplicativo |

Reaproveitados, sem mudança: "Entrar", "Entrando…", "Carregando…", `LOGIN_BLOCKED`, `LOGIN_UNEXPECTED_ERROR` e "O tempo para digitar o código acabou. Entre de novo com a sua senha."

**Seção no painel**

| Onde | Texto |
|---|---|
| Título | Códigos de recuperação |
| Explicação | Gere novos códigos se você perdeu os anteriores ou já usou alguns. Os códigos antigos param de funcionar na hora. |
| Botão | Gerar novos códigos |
| Instrução | Para confirmar, digite o código de 6 dígitos do aplicativo autenticador. |
| Rótulo | Código do aplicativo autenticador |
| Botão | Confirmar e gerar |
| Carregando | Gerando… |
| Botão | Cancelar |
| Erro de código | Código incorreto ou vencido. Aguarde o próximo código do aplicativo e tente de novo. |
| Erro geral | Não foi possível gerar novos códigos agora. Tente novamente. |
| Título depois de gerar | Seus novos códigos de recuperação |
| Aviso depois de gerar | Os códigos anteriores não funcionam mais. Estes aparecem só agora. Guarde-os no seu gerenciador de senhas. Quem tiver um código e a sua senha entra no painel. |
| Botão final | Concluir |
| Sessão encerrada (`401`) | Sem texto: o app redireciona para `/painel/entrar` |

Reaproveitados: `LOGIN_BLOCKED`, "Digite os 6 dígitos do código.", "Copiar códigos", as mensagens de copiar, a caixa de confirmação.

## 5. Dependências novas

Nenhuma. Usa o que já existe: JDK (`SecureRandom`, `MessageDigest`), `Totp.toBase32`, `Tokens.hash`, `LoginAttemptLimiter`, `ClientIpResolver`, db-scheduler, `TransactionTemplate` do Spring.

## 6. Riscos e o que fica de fora

**Riscos**
- **O `guarded()` e o `Outcome` mudam** e sustentam o login do MFA, área de alto impacto. Os testes existentes do #15 ficam valendo, com a mudança de status como única alteração nas asserções da confirmação.
- **Mudança de contrato:** a confirmação muda de `204` para `200`; backend e frontend vão juntos.
- **IP antes do #56:** sem o `Fly-Client-IP` configurado, a origem na auditoria será o IP do proxy e **não é confiável**. Anotado para o **#7** (conferir `audit_events.origin` depois de ligar o cabeçalho) e no ADR-0004.
- **Conflito de migration:** se outra branch usar V10 antes do merge, renumerar.
- **A tabela pode crescer** com senhas erradas de muitos IPs: cada IP tem no máximo 20 tentativas a cada 15 minutos, e a retenção apaga as antigas.
- **O gatilho não vale contra o dono da tabela:** quem tem as credenciais do banco pode desligá-lo (D6). Não é armazenamento externo imutável (SEC-028 pede isso só para eventos financeiros).
- **Piso de retenção no banco:** reduzir a retenção abaixo de 365 dias exige uma migration nova e a decisão de apagar menos tempo de trilha.
- **E2E mais lento:** cada TOTP novo espera o próximo passo de 30 s.

**Fora do ticket (uma issue só, `needs-triage`, citando o #16)**
- **Issue própria (item 6):** trocar o autenticador do Administrador.
- **Issue de sobras (`needs-triage`, uma só):**
  - E-mail de aviso ao usar um código de recuperação ou gerar novos: o SEC-007 não exige literalmente (item 5), mas decidir antes do gate #47.
  - Tela ou rota de consulta da auditoria, e separação de acesso (SEC-066).
  - Usuário de banco só de gravação para a auditoria (hoje o gatilho cobre bug e injeção, não quem tem as credenciais).
  - Mostrar quantos códigos restam.
  - Auditar ações do Cliente e o logout do Administrador.
  - Aviso da origem (IP) só depois do #56 (conferência no #7).
