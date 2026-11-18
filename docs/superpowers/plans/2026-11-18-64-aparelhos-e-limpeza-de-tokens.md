# Aparelhos conhecidos e limpeza de tokens (A1 da triagem) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fechar os 3 itens obrigatórios do gate no #64 (login concorrente com a troca de senha, token de aparelho sempre gerado pelo servidor, limpeza de `customer_devices`), o registro não atômico do aparelho e o cookie sem `Secure`, e apagar os tokens vencidos de #61 e #51 na mesma tarefa recorrente.

**Architecture:** O registro do aparelho passa a rodar numa transação que trava a linha do Cliente e confere a `session_version` lida no login; a troca de senha apaga os aparelhos numa instrução posterior à que trava a mesma linha, então as duas operações nunca se cruzam. O token do cookie só é aceito se já for um aparelho conhecido da conta; qualquer outro valor é trocado por um gerado no servidor. Uma tarefa recorrente diária nova apaga aparelhos sem uso e tokens usados ou vencidos.

**Tech Stack:** Java 25, Spring Boot 4.1, `JdbcClient`, PostgreSQL (READ COMMITTED), db-scheduler, JUnit 5 + Testcontainers.

**Spec:** issues #64, #61 e #51 (itens citados abaixo); requisitos SEC-006 e SEC-007 de `docs/spec/`; ADR-0004.

**Fluxo:** COMPLETO (autenticação e segurança). Branch `fix/64-device-registration-and-token-cleanup`, numa worktree.

## O que a conferência no código mostrou (18/11/2026)

| Item | Origem | Estado no código | Onde |
|---|---|---|---|
| Login concorrente com a troca de senha | #64 obrigatório | **Aberto.** `recognize` insere sem olhar a `session_version` | `KnownDevices.java:52`, `PasswordResetTokens.java:51` |
| Token sempre gerado pelo servidor | #64 obrigatório | **Aberto.** Valor bem formado e desconhecido é gravado como veio | `KnownDevices.java:51` |
| Cookie repetido: fica com o último | #64 obrigatório (junto) | **Aberto** | `SessionController.java:108-112` |
| Limpeza de `customer_devices` | #64 obrigatório | **Aberto.** Nenhuma tarefa apaga a tabela | — |
| `recognize` não atômico | #64 outros | **Aberto.** Três instruções sem transação | `KnownDevices.java:39-63` |
| Cookie sem `Secure` fora do perfil `prod` | #64 outros | **Aberto.** Padrão `false` | `SessionController.java:63` |
| Links usados ou vencidos de `password_reset_tokens` | #61 | **Aberto** | — |
| Tokens vencidos de `email_confirmation_tokens` | #51 | **Aberto** para contas que continuam existindo (os de conta não confirmada somem com a conta aos 7 dias) | — |

Fora deste plano, continuam no #64: "aparelho registrado antes de a sessão ser criada" e "API obsoleta do db-scheduler".

**Achado novo na conferência:** o caminho sugerido no #64 (o `insert` só valer com a `session_version` lida) não fecha a corrida sozinho. No PostgreSQL, todas as partes de um `with ... delete ... update` enxergam o mesmo retrato do banco, tirado no começo da instrução: se o login gravar o aparelho depois desse instante, o `delete` da troca de senha não o vê, mesmo esperando a trava. Por isso a troca de senha também muda (Task 2): o `delete` vira uma instrução separada, depois do `update` que trava a linha do Cliente.

## Ordem das travas (condição da aprovação)

Toda operação que trava a linha do Cliente a trava **antes** de tocar `customer_devices` ou `password_reset_tokens`:

| Operação | Ordem |
|---|---|
| Login (`KnownDevices.recognize`, Task 1) | `customers` (`for no key update`) → `customer_devices` |
| Troca de senha (`consumeAndSetPassword`, Task 2) | lê o `customer_id` do link sem travar → `customers` (`for no key update`) → `password_reset_tokens` → `customers` (update) → `customer_devices` |
| Pedido de link novo (`reissue`, já hoje) | `customers` (`for update`) → `password_reset_tokens` |
| Anonimização (já hoje) | `customers` (`for update`) → tokens e aparelhos |
| Limpeza diária (Task 4) | só `customer_devices` e tokens, com `for update skip locked`: nunca espera por uma linha travada (ela fica para a noite seguinte), então nunca participa de um ciclo |

Hoje a troca de senha trava o link e depois o Cliente, o inverso do `reissue`: a Task 2 passa a travar o Cliente primeiro. Teste: `shouldNotDeadlockWhenAPasswordChangeMeetsALoginOrANewLink` (Task 2).

## Decisões aprovadas (18/11/2026)

| # | Decisão | Recomendação |
|---|---|---|
| D1 | Prazo da limpeza de `customer_devices` | **365 dias sem uso** (`last_seen_at`), igual à validade do cookie: uma linha mais velha que isso pertence a um cookie que o navegador já descartou, então nada muda para o Cliente. Prazo menor (ex.: 90 dias) faria um aparelho antigo voltar a gerar aviso. |
| D2 | Cookie sem `Secure` | **Falhar fechado:** o cookie de aparelho sai `Secure` a não ser que o ambiente diga `server.servlet.session.cookie.secure=false`; só o `application-local.yml` diz isso. Alternativa: `request.isSecure()`, que não protege um ambiente sem o perfil `prod` (sem ele o servidor também não lê o `X-Forwarded-Proto`). |
| D3 | Login que perdeu a corrida para a troca de senha | **Sem cookie de aparelho e sem aviso.** A sessão dele já morre pela `session_version`, e o dono acabou de receber o e-mail de senha alterada. |
| D4 | Consequência do token sempre gerado pelo servidor | Duas contas no mesmo navegador: cada troca de conta vira "aparelho novo" e manda aviso, porque o navegador guarda um cookie só. O #64 já previu a mudança; **confirmar que o aviso a mais é aceito.** |
| D5 | Nome e horário da tarefa | `delete-stale-devices-and-tokens`, todo dia às 03:15 (entre a das 03:00 e a das 03:30; fuso da JVM, como as outras duas). |
| D6 | Issues na PR | `Refs #64`, `Refs #61`, `Refs #51` e as caixas marcadas; as três continuam abertas com o que sobra. |

## Textos que o usuário vê

Nenhum texto novo para o Cliente. Para o Administrador: o nome técnico `delete-stale-devices-and-tokens` aparece no detalhe do alerta já existente "tarefa recorrente falhando" se a tarefa falhar 3 vezes seguidas.

## Global Constraints

- TDD: cada teste novo falha primeiro **por asserção** (não por erro de compilação), depois passa.
- Testes focados (`cd backend; .\mvnw.cmd test "-Dtest=A,B"`), um comando pesado por vez; `just check-rapido` uma vez só, no fim.
- Teste focado de SEC-007 inclui as classes que citam o ID: `git grep -l SEC-007 -- backend/src/test`.
- Nunca gravar em log token de aparelho, e-mail ou mensagem de exceção do fluxo de login.
- Sem dependência nova, sem migration, sem propriedade `app.*` nova.
- Um commit por task, Conventional Commits em inglês; comandos de shell um por vez.

## Review Focus

1. Dois primeiros logins simultâneos da mesma conta: os dois terminam sem deadlock, exatamente um conta como o primeiro (Task 1).
2. Login com a versão antiga **e** cookie de aparelho já conhecido: não renova o cookie nem falha (Task 1).
3. Conta apagada ou anonimizada entre a senha conferida e o registro: sem erro de chave estrangeira, sem linha (Task 1).
4. Link de recuperação inválido não apaga nenhum aparelho (Task 2).
5. Limpeza na fronteira: aparelho visto há 364 dias e link válido emitido há pouco ficam (Task 4).

---

### Task 1: Registro do aparelho numa transação, condicionado à `session_version`, com token do servidor

**Files:**
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/customer/KnownDevices.java`
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/customer/CustomerService.java:135-148`
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/customer/SessionController.java:87-128`
- Test: `backend/src/test/java/br/com/sergio/sapiensia/customer/KnownDevicesTest.java`, `NewDeviceNoticeTest.java`, `CustomerServiceTest.java`

**Interfaces:**
- Produces: `@Transactional DeviceCheck recognize(UUID customerId, int sessionVersion, String cookieToken)`; `record DeviceCheck(String token, boolean notifyOwner)` com `static final DeviceCheck NOT_REGISTERED = new DeviceCheck(null, false)`.
- Produces: `public Optional<String> recognizeDevice(UUID customerId, int sessionVersion, String cookieToken, Instant loggedInAt)` em `CustomerService` (vazio = não registrado: sem cookie, sem aviso).

Ordem das instruções em `recognize` (uma transação):

```sql
-- 1. trava a linha do Cliente e confere a versão lida no login; sem linha => NOT_REGISTERED
select 1 from customers where id = :customerId and session_version = :sessionVersion for no key update
-- 2. só se o cookie for bem formado: 1 linha => DeviceCheck(cookieToken, false)
update customer_devices set last_seen_at = now() where customer_id = :customerId and token_hash = :hash
-- 3. sempre um token novo (PasswordResetTokens.newToken()), nunca o valor do cookie
insert into customer_devices (customer_id, token_hash) values (:customerId, :hash)
-- 4. como hoje: 0 linhas => notifyOwner = true
update customers set first_login_at = now() where id = :customerId and first_login_at is null
```

`for no key update` é a mesma trava de um `update` comum: bloqueia a troca de senha e outro login da mesma conta, mas não um `insert` de Pedido que referencia o Cliente.

- [ ] **Step 1: Adaptar os dois testes existentes de `KnownDevicesTest` à nova assinatura** (`recognize(id, 0, ...)`) e escrever os novos, todos `SEC-007`:
  - `shouldReplaceAnUnknownWellFormedTokenWithAServerToken`: depois de um primeiro `recognize(id, 0, null)`, `recognize(id, 0, "A".repeat(43))` devolve token diferente de `"A".repeat(43)`, `notifyOwner` verdadeiro, e nenhuma linha com `Tokens.hash("A".repeat(43))`.
  - `shouldNotRegisterWhenTheSessionVersionChanged`: `update customers set session_version = 1`; `recognize(id, 0, null)` devolve `NOT_REGISTERED`, 0 linhas em `customer_devices`, `first_login_at` nulo.
  - `shouldNotRefreshAKnownDeviceWhenTheSessionVersionChanged` (Review Focus 2): registra com versão 0, sobe a versão, `recognize(id, 0, tokenConhecido)` devolve `NOT_REGISTERED` e o `last_seen_at` não muda.
  - `shouldNotRegisterForAnAccountThatNoLongerExists` (Review Focus 3): `recognize(UUID.randomUUID(), 0, null)` devolve `NOT_REGISTERED` sem exceção.
  - `shouldLeaveNoDeviceWhenTheFirstLoginUpdateFails` (atomicidade): cria no teste um gatilho `before update of first_login_at on customers ... when (new.id = '<id>')` que lança exceção, chama `recognize`, espera a exceção e confere 0 linhas em `customer_devices`; o gatilho e a função são apagados num `finally`.
  - `shouldCountExactlyOneOfTwoSimultaneousFirstLogins` (Review Focus 1): duas threads com `CountDownLatch` chamam `recognize(id, 0, null)`; as duas terminam em até 10 s, 2 linhas, exatamente um `notifyOwner` falso.
- [ ] **Step 2: Mudar só a assinatura (corpo antigo, ignorando a versão) para compilar e rodar** `"-Dtest=KnownDevicesTest"`. Expected: 4 testes novos FALHAM por asserção; o da conta que não existe mais falha com o erro de chave estrangeira do código atual; o dos dois primeiros logins simultâneos já passa (ele protege a mudança); os 2 antigos passam.
- [ ] **Step 3: Implementar `recognize` com as quatro instruções acima e `@Transactional`.**
- [ ] **Step 4: `CustomerService.recognizeDevice`** passa a versão adiante e devolve `Optional.empty()` sem agendar aviso quando recebe `NOT_REGISTERED`. Teste em `CustomerServiceTest`, no estilo do arquivo: `shouldNotScheduleTheNoticeWhenTheDeviceWasNotRegistered`.
- [ ] **Step 5: `SessionController`**: `rememberDevice` recebe `fullPrincipal.credentials().sessionVersion()` e só escreve o `Set-Cookie` quando o `Optional` vem preenchido.
- [ ] **Step 6: `NewDeviceNoticeTest`** (os dois testes mudam por decisão do #64, não por afrouxamento):
  - `shouldNotNotifyAccountBOnItsFirstLoginWithTheCookieOfAccountA` → nome de exibição "o cookie da conta A no primeiro login da conta B não avisa B e é trocado por um token novo"; `deviceOf(firstOfB)` passa a ser **diferente** de `deviceOfA`; o resto igual.
  - `shouldTreatTheCookieOfAccountAAsANewDeviceOfBAfterItsFirstLogin` → `deviceOf(response)` **diferente** de `deviceOfA`; aviso para B, 2 linhas de B e 1 de A, como hoje.
  - Novo `shouldReplaceAPlantedCookieAndNotifyTheOwner`: conta com primeiro login feito; login com `"A".repeat(43)` no cookie → 204, e-mail de aparelho novo, cookie devolvido diferente do plantado.
- [ ] **Step 7: Rodar** `"-Dtest=KnownDevicesTest,NewDeviceNoticeTest,NewDeviceNoticeFailureTest,CustomerServiceTest,SessionControllerTest,SessionServerTest"`. Expected: PASS.
- [ ] **Step 8: Commit** `fix: register a login device only for the session version read at login`

### Task 2: A troca de senha apaga os aparelhos depois de travar a linha do Cliente

**Files:**
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/customer/PasswordResetTokens.java:41-63`
- Test: `backend/src/test/java/br/com/sergio/sapiensia/customer/PasswordResetTokensTest.java`

**Interfaces:**
- Consumes: `KnownDevices.recognize(UUID, int, String)` da Task 1.
- Produces: `@Transactional Optional<UUID> consumeAndSetPassword(String token, String newPasswordHash)`, mesma assinatura e mesmo resultado de hoje.

Uma transação: (0) `select customer_id from password_reset_tokens where token_hash = :hash` sem trava (vazio → `Optional.empty()`), depois `select id from customers where id = :customerId for no key update`; (1) o `with used as (update password_reset_tokens ...) update customers set password_hash, session_version + 1 ... returning customers.id` de hoje, **sem** o `forgotten`; (2) só se (1) devolveu um id: `delete from customer_devices where customer_id = :customerId`. A instrução (2) tira um retrato novo do banco, depois de qualquer login que segurava a trava. O passo (0) põe a trava do Cliente antes da do link, na mesma ordem do `reissue` e do login (seção "Ordem das travas").

- [ ] **Step 1: Escrever os testes** (`SEC-007`):
  - `shouldForgetADeviceRegisteredWhileThePasswordWasChanging`: thread A abre uma transação (`TransactionTemplate`), chama `devices.recognize(id, 0, null)` e espera um `CountDownLatch`; thread B chama `consumeAndSetPassword(token, hash)`; o teste espera B aparecer bloqueada (`select count(*) from pg_stat_activity where wait_event_type = 'Lock'` maior que zero, até 5 s), solta A e espera as duas. Asserções: B devolveu o id, `session_version` = 1 e **0 linhas** em `customer_devices`.
  - `shouldKeepTheDevicesWhenTheLinkIsNotValid` (Review Focus 4): Cliente com 1 aparelho; `consumeAndSetPassword("token-inexistente", hash)` devolve vazio e a linha continua.
  - `shouldNotDeadlockWhenAPasswordChangeMeetsALoginOrANewLink`: 20 rodadas; em cada uma, três threads soltas por um `CountDownLatch` sobre o mesmo Cliente: `consumeAndSetPassword(token, hash)`, `devices.recognize(id, versãoLida, null)` e `reissue(id)`. Asserção: nenhuma `DataAccessException` cuja causa tenha SQLState `40P01` (deadlock) e todas as threads terminam em até 10 s.
- [ ] **Step 2: Rodar** `"-Dtest=PasswordResetTokensTest"`. Expected: o primeiro FALHA por asserção (1 linha em vez de 0); o segundo passa (protege a mudança); o de deadlock pode passar ou falhar hoje (a janela é estreita) e protege a mudança.
- [ ] **Step 3: Implementar** as duas instruções e `@Transactional`; atualizar o Javadoc (a frase "in one statement" deixa de valer para os aparelhos).
- [ ] **Step 4: Rodar** `"-Dtest=PasswordResetTokensTest,PasswordResetConfirmationTest,NewDeviceNoticeTest,SessionRevocationTest"`. Expected: PASS.
- [ ] **Step 5: Commit** `fix: forget known devices after the password change holds the customer row`

### Task 3: Primeiro cookie de aparelho e `Secure` por padrão

**Files:**
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/customer/SessionController.java:47-128`
- Modify: `backend/src/main/resources/application-local.yml` (`server.servlet.session.cookie.secure: false`, com comentário de uma linha)
- Modify: `docs/adr/0004-identidade-e-mfa.md:34-36`
- Test: `NewDeviceNoticeTest.java`, `SessionControllerTest.java`

**Interfaces:**
- Produces: `static ResponseCookie deviceCookie(String token, boolean secure)` em `SessionController` (o que hoje está embutido em `rememberDevice`).

- [ ] **Step 1: Testes** (`SEC-007`):
  - `NewDeviceNoticeTest.shouldUseTheFirstDeviceCookieWhenTheBrowserSendsTwo`: cabeçalho `Cookie: SAPIENSIA_DEVICE=<conhecido>; SAPIENSIA_DEVICE=<"B".repeat(43)>` → 204, nenhum e-mail, cookie devolvido igual ao conhecido.
  - `NewDeviceNoticeTest.shouldSetTheDeviceCookieAndNotNotifyOnTheFirstLogin`: `.doesNotContain("Secure")` vira `.contains("Secure")` (o contexto de teste não tem o perfil `prod` nem a propriedade: é o caso "falhar fechado"). Mudança de comportamento decidida em D2.
  - `SessionControllerTest.shouldBuildTheDeviceCookieWithoutSecureOnlyWhenToldTo`: `deviceCookie("t", false).toString()` não contém `Secure`; com `true`, contém.
- [ ] **Step 2: Rodar** `"-Dtest=NewDeviceNoticeTest,SessionControllerTest"`. Expected: os dois primeiros FALHAM por asserção.
- [ ] **Step 3: Implementar**: leitura com `WebUtils.getCookie(request, DEVICE_COOKIE)` (devolve o primeiro); `@Value("${server.servlet.session.cookie.secure:true}")`; extrair `deviceCookie`; a linha no `application-local.yml`.
- [ ] **Step 4: Atualizar o ADR-0004** (linhas 34 a 36): o cookie sai `Secure` a não ser que o ambiente desligue; o valor do cookie só é aceito se já for aparelho conhecido da conta, senão o servidor gera outro; o registro só vale com a `session_version` lida no login; a troca de senha apaga os aparelhos na mesma transação.
- [ ] **Step 5: Rodar** `"-Dtest=NewDeviceNoticeTest,NewDeviceNoticeFailureTest,SessionControllerTest,SessionServerTest"`. Expected: PASS.
- [ ] **Step 6: Commit** `fix: keep the first device cookie and send it Secure unless disabled`

### Task 4: Tarefa diária que apaga aparelhos sem uso e tokens usados ou vencidos

**Files:**
- Create: `backend/src/main/java/br/com/sergio/sapiensia/customer/StaleDevicesAndTokensCleanup.java` (no molde de `UnconfirmedCustomersCleanup.java`)
- Modify: `KnownDevices.java`, `PasswordResetTokens.java`, `EmailConfirmationTokens.java`, `SessionController.java` (a constante de 365 dias muda de lugar)
- Modify: `docs/privacidade/inventario-dados-pessoais.md:19-21` (coluna de retenção)
- Create: `backend/src/test/java/br/com/sergio/sapiensia/customer/StaleDevicesAndTokensCleanupTest.java`
- Modify: `backend/src/test/java/br/com/sergio/sapiensia/alert/RecurringTaskFailureWiringTest.java:27-33`

**Interfaces:**
- Produces: `static final Duration COOKIE_MAX_AGE = Duration.ofDays(365)` em `KnownDevices` (usada pelo cookie e pela limpeza; sai de `SessionController`).
- Produces: `int KnownDevices.deleteNotSeenWithinCookieMaxAge()` → apaga de `customer_devices` as linhas com `last_seen_at < now() - make_interval(secs => :seconds)`.
- Produces: `int PasswordResetTokens.deleteUsedOrExpired()` → apaga de `password_reset_tokens` as linhas com `used_at is not null or expires_at <= now()`.
- Produces: `int EmailConfirmationTokens.deleteExpired()` → apaga de `email_confirmation_tokens` as linhas com `expires_at <= now()`.
- Os três `delete` escolhem as linhas por uma subconsulta `... for update skip locked` (por chave primária: `(customer_id, token_hash)` nos aparelhos, `id` nos tokens), para nunca esperar por uma linha travada (seção "Ordem das travas").
- Produces: `static final String TASK_NAME = "delete-stale-devices-and-tokens"`, `Schedules.daily(LocalTime.of(3, 15))`, `onFailure(failures.rescheduleAndAlert(TASK_NAME, schedule))`. A execução chama os três métodos e grava em log só as três contagens.

- [ ] **Step 1: Escrever `StaleDevicesAndTokensCleanupTest`** (`@SpringBootTest` + `@Import(TestcontainersConfiguration.class)`, linhas inseridas com `JdbcTemplate`; só as linhas do próprio teste são conferidas):
  - `shouldDeleteOnlyDevicesNotSeenForMoreThan365Days` (`#64`): `last_seen_at` de 366 dias some; de 364 dias fica (Review Focus 5).
  - `shouldDeleteUsedAndExpiredPasswordResetLinks` (`#61`): usado some, vencido some, válido sem uso fica.
  - `shouldDeleteOnlyExpiredConfirmationLinks` (`#51`): vencido some, válido fica.
  - `shouldSkipADeviceLockedByALogin`: uma transação aberta no teste trava um aparelho antigo (`select ... for update`); `deleteNotSeenWithinCookieMaxAge()` volta em até 2 s sem apagá-lo; depois do `rollback`, a chamada seguinte o apaga.
  - `shouldRegisterTheDailyCleanupTaskInTheScheduler`: uma linha em `scheduled_tasks` com `task_name = 'delete-stale-devices-and-tokens'` em até 15 s (mesmo laço de `UnconfirmedCustomersCleanupTest`).
  - Em `RecurringTaskFailureWiringTest`: acrescentar `"delete-stale-devices-and-tokens"` à lista.
- [ ] **Step 2: Criar os três métodos devolvendo 0 e a tarefa ainda não registrada; rodar** `"-Dtest=StaleDevicesAndTokensCleanupTest,RecurringTaskFailureWiringTest"`. Expected: FALHAM por asserção.
- [ ] **Step 3: Implementar** os três `delete` e o `@Bean RecurringTask<Void>`; mover a constante.
- [ ] **Step 4: Atualizar o inventário de dados pessoais**: aparelhos "até 365 dias sem uso (limpeza diária) ou enquanto a conta existir; apagado na anonimização"; tokens "apagados pela limpeza diária depois do uso ou da expiração".
- [ ] **Step 5: Rodar** `"-Dtest=StaleDevicesAndTokensCleanupTest,RecurringTaskFailureWiringTest,UnconfirmedCustomersCleanupTest,NewDeviceNoticeTest"`. Expected: PASS.
- [ ] **Step 6: Commit** `feat: delete stale devices and used or expired tokens every night`

### Task 5: Fechamento

- [ ] **Step 1:** `just check-rapido` (uma vez). Se falhar, procurar no `.tmp/check-rapido.log` só as linhas do erro.
- [ ] **Step 2:** Skill `verificar-entrega` e mostrar a tabela dela.
- [ ] **Step 3:** `/code-review` da branch; defeito do próprio ticket se corrige aqui, o resto vira uma issue `needs-triage`.
- [ ] **Step 4:** Rodar localmente os e2e `logout-everywhere` e `password-reset` contra o sistema de verdade (perfil `local`, banco da pasta principal), porque eles dependem do aviso de aparelho novo.
- [ ] **Step 5:** Push e PR (OK dado em 18/11/2026; o merge fica com o Sérgio): `Refs #64`, `Refs #61`, `Refs #51`; citar SEC-006 e SEC-007. Esperar o CI; se falhar, parar e mostrar. Depois, marcar as caixas atendidas nas três issues.

O e2e do CI sobe a imagem `prod` com `SERVER_SERVLET_SESSION_COOKIE_SECURE=false` (`.github/workflows/ci.yml`), a mesma propriedade que o cookie de aparelho lê: no CI o cookie continua sem `Secure`.

## Riscos

- **Trava a mais por login:** cada login correto trava a linha do Cliente por alguns milissegundos; dois logins da mesma conta passam a rodar um depois do outro. Requisições já logadas só leem a linha e não esperam.
- **Teste de corrida com threads:** depende de ver a thread B bloqueada em `pg_stat_activity`; se ficar instável no CI, trocar a espera por uma consulta a `pg_locks` filtrada pela tabela `customers`.
- **Primeira execução da limpeza:** apaga de uma vez tudo o que acumulou; hoje são poucas linhas (homologação com dados fictícios).
- **Ambiente sem perfil e sem HTTPS:** com D2, o navegador descarta o cookie `Secure` e cada login vira "aparelho novo" (aviso a mais, nunca a menos). O `local` desliga isso explicitamente.
