# Avisos de segurança e "Sair de todos os aparelhos" (#13) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** O Cliente recebe um e-mail quando entra num aparelho novo (o aviso de troca de senha já existe desde o #12) e tem um botão "Sair de todos os aparelhos" que encerra todas as suas sessões, inclusive a atual.

**Architecture:** "Sair de todos" é `DELETE /api/sessions`: incrementa a `session_version` da conta (o `SessionVersionFilter` do #12 já derruba as outras sessões) e encerra a sessão atual como o logout. O "aparelho" é um cookie de longa duração (`SAPIENSIA_DEVICE`) com um token aleatório, e só o hash dele fica na tabela `customer_devices`. Um login sem cookie conhecido registra o aparelho. Se a conta já tinha outro aparelho, agenda um e-mail (tarefa db-scheduler, como os outros e-mails).

**Tech Stack:** Spring Boot 4.1, JdbcClient, Flyway, db-scheduler, React + TanStack Query, Vitest, Playwright.

**Spec:** `docs/spec/sapiensia-spec.md` (SEC-006, SEC-007, SEC-009); `docs/adr/0004-identidade-e-mfa.md`; issue #13.

## Decisões do Sérgio (04/11/2026)

1. **Aparelho novo = cookie de aparelho.** Login sem o cookie `SAPIENSIA_DEVICE` conhecido para aquela conta conta como aparelho novo. Aba anônima, outro navegador e cookies apagados também contam como aparelho novo.
2. **O primeiro aparelho da conta não gera aviso**: ele só é registrado. O aviso vai a partir do segundo aparelho diferente.
3. **O aviso de troca de senha já está atendido** pelo e-mail do #12 (`Sua senha na SapiensIA foi alterada`), e a senha só muda pelo "Esqueci minha senha". Uma tela "Alterar senha" para quem está logado fica fora deste ticket.

4. **Sem confirmação no botão.** Um clique em "Sair de todos os aparelhos" já encerra as sessões, sem caixa de "tem certeza?".
5. **Sair de todos não "esquece" os aparelhos** e não manda e-mail. **A troca de senha pelo link (#12) apaga os aparelhos conhecidos da conta**, e o primeiro login depois da troca gera aviso. Por isso, "primeiro aparelho" quer dizer o primeiro login da história da conta. Ele fica gravado na coluna `customers.first_login_at`, que um `UPDATE ... where first_login_at is null` preenche uma vez só e de forma atômica. Apagar os aparelhos não zera essa coluna.
6. **O e-mail de aparelho novo não traz IP nem navegador, mas traz a data e a hora do acesso** no horário de Brasília (`America/Sao_Paulo`), no formato `dd/MM/yyyy 'às' HH:mm`.

Aprovadas em 04/11/2026.

## Global Constraints

- SEC-009 na Fase 1 é só revogar todas as sessões, sem lista de sessões (ADR-0004).
- SEC-006: depois de "Sair de todos", toda sessão antiga da conta responde `401`.
- `DELETE /api/sessions` exige `ROLE_CUSTOMER` e o token CSRF. Sem sessão responde `401`; sem CSRF responde `403`. Não há `permitAll` novo.
- Cookie de aparelho: nome `SAPIENSIA_DEVICE`, valor de 32 bytes do `SecureRandom` em Base64 URL sem padding (43 caracteres), `HttpOnly`, `Path=/api/sessions`, `Max-Age` de 365 dias, `SameSite=Lax`. O `Secure` vem de `server.servlet.session.cookie.secure` (padrão `false`, `true` no perfil `prod`). O cookie é regravado a cada login bem-sucedido.
- Só o hash SHA-256 do token vai para o banco (reaproveitar `EmailConfirmationTokens.hash`). O token nunca vai para log nem para os dados de uma tarefa do db-scheduler.
- Um valor de cookie fora do formato (diferente de 43 caracteres Base64 URL) é tratado como cookie ausente.
- O aparelho é registrado por conta + aparelho: um Cliente que usa o mesmo navegador não herda o aparelho de outro. O cookie sozinho não dá acesso a nada.
- Só login bem-sucedido e com e-mail confirmado registra aparelho. Login que falha (`401`/`429`/`503`) não grava cookie, não registra aparelho e não envia e-mail.
- O aviso não atrasa nem quebra o login. O e-mail sai numa tarefa em segundo plano. Se o reconhecimento do aparelho ou o agendamento do aviso lançar exceção, o login segue com a mesma resposta (`204` e a sessão), e o log registra só o tipo do erro, sem e-mail nem token.
- Nunca gravar em log senha, token, e-mail ou dado pessoal. LF em todo arquivo. Texto do usuário só o da lista abaixo.
- Fluxo crítico (login) ganha e2e em `frontend/e2e/`, sem `page.route` nem mocks, lendo o e-mail em `.tmp/outbox`.

## Textos que o usuário vai ver (lista fechada)

**Tela inicial, logado (`HomePage`)**
- Botão novo: `Sair de todos os aparelhos`; enquanto envia: `Saindo de todos os aparelhos…`
- Erro: `Não foi possível sair de todos os aparelhos agora. Tente novamente.`
- Sucesso: a tela volta ao modo de visitante (links `Entrar` e `Criar conta`) com a mensagem `Você saiu de todos os aparelhos.`

**E-mail de aparelho novo**: assunto `Novo acesso à sua conta na SapiensIA`. Corpo:
```
Olá!

Sua conta na SapiensIA foi acessada em %s (horário de Brasília), em um aparelho ou navegador que ainda não tínhamos visto.

Se foi você, não precisa fazer nada.

Se não foi você, use "Esqueci minha senha" para criar outra senha agora. A troca de senha desconecta todos os aparelhos.
```
O `%s` vira a data e a hora do login, por exemplo `04/11/2026 às 14:32`. O e-mail segue o padrão dos e-mails do #9 e do #12: texto puro e sem link, token ou senha.

## Review Focus

- Mesmo navegador com duas contas: o cookie do navegador é reaproveitado e o aparelho é registrado por conta. A conta B no navegador da conta A é o primeiro aparelho de B. → Task 2.
- Um cookie adulterado ou lixo não quebra o login, não é gravado como veio e gera um cookie novo. → Task 2.
- Sair de todos não derruba a sessão de outro Cliente. → Task 1.
- Um login com a senha errada vindo de aparelho desconhecido não manda e-mail (senão qualquer um faria spam com o e-mail do Cliente). → Task 2.
- Depois de sair de todos, entrar de novo funciona e a nova sessão vale. → Task 1.

---

### Task 1: `DELETE /api/sessions`, sair de todos os aparelhos (SEC-009, SEC-006)

**Files:**
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/customer/SessionController.java`, `CustomerRepository.java`, `security/SecurityConfig.java`
- Test: `backend/src/test/java/br/com/sergio/sapiensia/customer/LogoutEverywhereTest.java` (no estilo do `SessionRevocationTest`: contexto isolado `test.isolated-context=logout-everywhere`, `HttpClient`, helpers `login`/`current`)

**Interfaces:**
- Produces: `@Modifying @Query("update Customer c set c.sessionVersion = c.sessionVersion + 1 where c.id = :id") int incrementSessionVersion(UUID id)` em `CustomerRepository` (com `@Transactional`); rota `@DeleteMapping ResponseEntity<Void> logoutEverywhere(Authentication, HttpServletRequest, HttpServletResponse)` → `204`. Depois de incrementar, ela invalida a sessão HTTP atual, limpa o `SecurityContext` e apaga o cookie `JSESSIONID` (`Max-Age=0`, como faz o logout). No `SecurityConfig`, `DELETE /api/sessions` fica com `hasRole("CUSTOMER")`.

- [ ] **Step 1: Testes que falham** (títulos citam SEC-009/SEC-006)
  - Duas sessões (`phone`, `laptop`) → `DELETE /api/sessions` com `laptop` → `204`, e o `Set-Cookie` apaga `JSESSIONID`; `current(phone)` → `401`; `current(laptop)` → `401`.
  - Depois disso, um login novo → `current` → `200`.
  - Outro Cliente logado antes continua com `current` → `200`.
  - Sem sessão → `401`; com sessão e sem o token CSRF → `403`, e as sessões continuam valendo.
- [ ] **Step 2: Rodar e ver falhar**: `cd backend; .\mvnw.cmd test -Dtest=LogoutEverywhereTest`
- [ ] **Step 3: Implementar** conforme as Interfaces.
- [ ] **Step 4: Rodar e ver passar**, e rodar também `-Dtest=Session*Test,Login*Test`.
- [ ] **Step 5: Commit**: `feat: log out of all devices`

### Task 2: Aparelho conhecido e e-mail de aparelho novo

**Files:**
- Create: `backend/src/main/resources/db/migration/V7__create_customer_devices.sql` (skill `nova-migration`)
- Create: `backend/src/main/java/br/com/sergio/sapiensia/customer/KnownDevices.java`, `NewDeviceNotice.java`
- Modify: `SessionController.java`, `CustomerService.java` (inclusive `resetPassword`), `CustomerEmailService.java`, `CustomerEmailTasks.java`
- Test:
  - `NewDeviceNoticeTest.java`: integração, como o `SessionRevocationTest`, lendo os e-mails no `RecordingEmailSender` com um e-mail único por teste.
  - `NewDeviceNoticeFailureTest.java`: contexto isolado com `@MockitoSpyBean SchedulerClient`.
  - `KnownDevicesTest.java` e `CustomerEmailServiceTest.java`.
  - Ajuste em `SessionServerTest.java` (perfil `prod`).

**Interfaces:**
- Migration V7:
  - Tabela `customer_devices(customer_id uuid not null references customers(id) on delete cascade, token_hash varchar(64) not null, created_at timestamptz not null default now(), last_seen_at timestamptz not null default now(), primary key (customer_id, token_hash))`.
  - Coluna nova `customers.first_login_at timestamptz null`.
  - A migration não preenche `first_login_at` nas contas que já existem. O primeiro login delas depois do deploy conta como primeiro login e não avisa (aceito: a Fase 1 ainda não está no ar).
- Produces:
  - `record DeviceCheck(String token, boolean notify)`.
  - `DeviceCheck KnownDevices.recognize(UUID customerId, @Nullable String cookieToken)`:
    - Um token válido e já registrado para a conta só atualiza `last_seen_at`, e a resposta não notifica.
    - Senão, reaproveita o token do cookie se ele for válido, ou gera um novo, e grava a linha com `on conflict do nothing`. Depois roda `update customers set first_login_at = now() where id = ? and first_login_at is null`. `notify` é `true` quando esse `UPDATE` não alterou nenhuma linha, ou seja, a conta já tinha feito login antes.
  - `void KnownDevices.forgetAll(UUID customerId)`: `delete from customer_devices where customer_id = ?`.
  - `record NewDeviceNotice(UUID customerId, Instant loggedInAt) implements Serializable`: é o dado da tarefa, sem e-mail e sem token.
  - `String CustomerService.recognizeDevice(UUID customerId, @Nullable String cookieToken, Instant loggedInAt)`: chama `recognize` e, se `notify`, agenda `CustomerEmailTasks.NEW_DEVICE_NOTICE = "customer-new-device-notice"` com um `NewDeviceNotice`. Devolve o token para o cookie.
  - `CustomerService.resetPassword` chama `knownDevices.forgetAll(customerId)` logo depois de `consumeAndSetPassword` ter sucesso.
  - `void CustomerEmailService.sendNewDeviceNotice(NewDeviceNotice notice)`, com as constantes `NEW_DEVICE_SUBJECT` e `NEW_DEVICE_TEXT` (com `%s`). A data é formatada por `DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm").withZone(ZoneId.of("America/Sao_Paulo"))`. O fuso vem da zona, nunca de um `ZoneOffset` fixo. O e-mail real leva só a data, sem nenhuma frase de exemplo.
  - `retryThenDrop` em `CustomerEmailTasks` passa a ser genérico (`<T> FailureHandler<T>`).
  - Em `SessionController.login`, depois de `limiter.recordSuccess` e antes de salvar o contexto, num `try/catch (RuntimeException)`:
    - Ler o cookie `SAPIENSIA_DEVICE`, chamar `recognizeDevice(..., Instant.now())` e escrever o cookie com `ResponseCookie` (atributos das Global Constraints).
    - Se cair no `catch`: `log.warn` só com o tipo da exceção, sem cookie, e o login segue para o `204`.

- [ ] **Step 1: Testes que falham** (títulos citam ADR-0004 e SEC-007; o aviso de troca de senha da decisão 3 já é coberto pelo `PasswordResetConfirmationTest` do #12)
  - Primeiro login sem cookie → `204`, `Set-Cookie: SAPIENSIA_DEVICE=<43 caracteres>; Max-Age=31536000; Path=/api/sessions; HttpOnly; SameSite=Lax` sem `Secure` (perfil de teste), e nenhum e-mail chega em 3 s.
  - Segundo login sem cookie → chega o e-mail `Novo acesso à sua conta na SapiensIA` com o corpo exato, e a data e a hora ficam entre o antes e o depois do login. O corpo não contém a senha nem o valor do cookie.
  - Login com o cookie recebido antes → nenhum e-mail em 3 s.
  - Conta + aparelho: o cookie da conta A, usado no primeiro login da conta B, não gera e-mail para B (é o primeiro login de B). Um segundo login de B sem cookie gera e-mail para B. Um login de B com o cookie da conta A, depois do primeiro de B, conta como aparelho novo de B, porque o hash está registrado só para A.
  - O cookie sozinho não dá acesso: `GET /api/sessions/current` só com `SAPIENSIA_DEVICE` válido → `401`.
  - Cookie lixo (`abc`) num segundo aparelho → e-mail enviado, cookie novo diferente de `abc`, e nenhuma linha com `hash("abc")`.
  - Senha errada vinda de aparelho novo → `401`, sem `Set-Cookie` de aparelho, nenhum e-mail e nenhuma linha nova.
  - Troca de senha pelo link apaga os aparelhos (decisão 5): login no aparelho X, reset, login de novo com o cookie de X → chega o e-mail de aparelho novo.
  - Envio falho não quebra o login: com `failNextSendTo` para o e-mail da conta, o segundo login responde `204` com sessão válida, e o e-mail chega na nova tentativa.
  - `NewDeviceNoticeFailureTest`: com o `SchedulerClient.schedule` lançando exceção para `NEW_DEVICE_NOTICE`, o login de aparelho novo responde `204`, e `GET /api/sessions/current` com a sessão → `200`.
  - `SessionServerTest` (perfil `prod`): o cookie `SAPIENSIA_DEVICE` sai com `Secure` e `SameSite=Lax`.
  - `KnownDevicesTest`: a tabela guarda só o hash (nenhuma coluna contém o token); o token tem 32 bytes decodificados.
  - `CustomerEmailServiceTest`: `NewDeviceNotice` de `2026-11-04T17:32:00Z` gera o corpo com `04/11/2026 às 14:32`; uma conta que não existe não recebe nada.
  - Nenhum log capturado no teste contém o token nem o e-mail.
- [ ] **Step 2: Rodar e ver falhar**: `.\mvnw.cmd test -Dtest=NewDeviceNotice*Test,KnownDevicesTest,CustomerEmailServiceTest,SessionServerTest`
- [ ] **Step 3: Implementar** a migration V7 e as Interfaces.
  - O `Secure` vem de `@Value("${server.servlet.session.cookie.secure:false}")`.
  - O token é gerado como em `PasswordResetTokens.issue`. Se a geração for privada, extraia um método estático package-private e reaproveite-o nas duas classes.
- [ ] **Step 4: Rodar e ver passar**, e rodar também `-Dtest=Session*Test,Login*Test,LogoutEverywhereTest,PasswordReset*Test`.
- [ ] **Step 5: Commit**: `feat: e-mail the customer about a login from a new device`

### Task 3: Botão na tela inicial

**Files:**
- Modify: `frontend/src/features/session/api.ts`, `hooks.ts`, `HomePage.tsx`, `HomePage.test.tsx` (ou crie o teste no molde dos testes de `session`)

**Interfaces:**
- Produces:
  - `logoutEverywhere(): Promise<void>`: chama `apiFetch("/api/sessions", { method: "DELETE" })` e espera `204`; outra resposta lança erro.
  - `useLogoutEverywhere()`: no `onSuccess`, `invalidateQueries({ queryKey: sessionQueryKey })`, como o `useLogout`.

- [ ] **Step 1: Testes que falham** (textos exatos da lista)
  - Logado: aparecem os botões `Sair` e `Sair de todos os aparelhos`.
  - Clicar chama `DELETE /api/sessions`; durante o envio, o botão fica desabilitado e mostra `Saindo de todos os aparelhos…`.
  - Sucesso: a tela mostra `Entrar` e `Criar conta` e um `role="status"` com `Você saiu de todos os aparelhos.`
  - Falha: um `role="alert"` com `Não foi possível sair de todos os aparelhos agora. Tente novamente.`, e a pessoa continua logada.
- [ ] **Step 2: Rodar e ver falhar**: `cd frontend; pnpm vitest run src/features/session`
- [ ] **Step 3: Implementar**, reaproveitando o `Button` do shadcn e a estrutura do botão `Sair`, sem componente novo.
- [ ] **Step 4: Rodar e ver passar**; depois `/verificar-tela` na `HomePage` logada (desktop e celular, teclado).
- [ ] **Step 5: Commit**: `feat: log out of all devices button`

### Task 4: E2E, ADR e fechamento

**Files:**
- Create: `frontend/e2e/logout-everywhere.e2e.ts` (molde: `password-reset.e2e.ts`, com os helpers copiados como nos outros e2e)
- Modify: `docs/adr/0004-identidade-e-mfa.md`

- [ ] **Step 1: Escrever o e2e**:
  - Cadastrar e confirmar uma conta.
  - Entrar no contexto de navegador A: nenhum e-mail `Novo acesso…`.
  - Entrar no contexto B, outro aparelho: chega o e-mail `Novo acesso…`.
  - Em B, clicar `Sair de todos os aparelhos`: B mostra `Você saiu de todos os aparelhos.`
  - Recarregar A: A está como visitante.
  - Entrar de novo em A: funciona, e nenhum e-mail novo chega.
- [ ] **Step 2: Rodar** `just e2e` (antes: `just db` na pasta principal, `just dev-back` e `just dev-front`). Esperado: PASS.
- [ ] **Step 3: Atualizar o ADR-0004** com as decisões de arquitetura e as regras visíveis (sem o texto das mensagens):
  - (a) `DELETE /api/sessions` encerra todas as sessões pela `session_version`, inclusive a atual, e exige sessão e CSRF.
  - (b) Aparelho novo = cookie `SAPIENSIA_DEVICE` registrado por conta + aparelho: 32 bytes aleatórios, 365 dias, `HttpOnly`, `Secure` em produção, `SameSite=Lax`, `Path=/api/sessions`, só o hash no banco, gravado só depois do login correto.
    - O primeiro login da conta não gera aviso.
    - Sair de todos não esquece os aparelhos; a troca de senha pelo link esquece.
    - O aviso leva a data e a hora de Brasília, sem IP nem navegador.
    - Uma falha no aviso não muda a resposta do login.
  - (c) O aviso de troca de senha é o do #12, porque a senha só muda pela recuperação na Fase 1.
- [ ] **Step 4: `just check-resumo`** verde; depois a skill `verificar-entrega` e a tabela dela.
- [ ] **Step 5: Commit**: `test: log out of all devices end to end` e `docs: update ADR-0004 for new-device notice and logout everywhere`

## Riscos

- **Dois primeiros logins simultâneos**: o `UPDATE` condicional de `first_login_at` deixa só um deles ser o "primeiro". O outro avisa.
- **A tabela `customer_devices` cresce** sem limpeza. Uma linha pequena por navegador e conta; a limpeza fica para quando incomodar.
- **Quem tem a senha certa e usa sempre uma aba anônima** gera um e-mail por login. Isso é o comportamento esperado do aviso.
- **A PR** leva `Closes #13`, cita SEC-006, SEC-007 e SEC-009, e diz que a tela "Alterar senha" logada ficou fora (decisão 3).
