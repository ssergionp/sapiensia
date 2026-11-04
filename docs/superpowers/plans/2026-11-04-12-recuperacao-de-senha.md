# Recuperação de senha (#12) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** O Cliente que esqueceu a senha pede um link por e-mail (uso único, prazo curto), define uma senha nova e todas as suas sessões são encerradas.

**Architecture:** Espelha a confirmação de e-mail já existente: tabela de tokens (só o hash SHA-256 é gravado), e-mails enviados por tarefa em segundo plano (db-scheduler), resposta `202` idêntica exista ou não a conta. Para encerrar sessões que vivem na memória do servidor sem trocar de armazenamento, a conta ganha uma coluna `session_version`; o login grava a versão na sessão e um filtro compara a versão da sessão com a do banco a cada requisição autenticada.

**Tech Stack:** Spring Boot 4.1, JdbcClient/JPA, Flyway, db-scheduler, Argon2id (PasswordEncoder existente), React + RHF + Zod + TanStack Query.

**Spec:** `docs/spec/sapiensia-spec.md` (SEC-006, SEC-007; SEC-002 para as regras de senha); `docs/adr/0004-identidade-e-mfa.md`; issue #12.

## Global Constraints

- Senha nova: 12 a 128 caracteres, nunca aparada (trim), conferida contra senhas vazadas (`CompromisedPasswordChecker`), guardada com Argon2id (ADR-0004; as mesmas regras do cadastro).
- SEC-007: token aleatório, uso único, expiração curta, resposta que não revele a existência da conta, notificação ao titular.
- SEC-006: sessões invalidadas após a troca de senha.
- Rota nova em `/api` começa negada; `permitAll` só nas duas rotas públicas deste ticket, com ADR atualizado (o ADR-0004 já lista as rotas públicas do Cliente).
- Token: `SecureRandom`, 32 bytes, Base64 URL sem padding; só o hash SHA-256 vai para o banco; nunca em log, nunca em dado de tarefa do db-scheduler.
- O token viaja no fragmento da URL (`/redefinir-senha#<token>`): o navegador não manda o fragmento ao servidor nem no `Referer`, então ele não aparece em log de proxy. A tela o lê de `window.location.hash` e o envia no corpo de um `POST`.
- A resposta HTML de `/redefinir-senha` leva `Referrer-Policy: no-referrer`; a página não carrega nada de terceiros (a CSP atual já é `'self'`).
- Uso único atômico: um só `UPDATE ... where token_hash = ? and used_at is null and expires_at > now()`; só a requisição que alterar 1 linha troca a senha.
- Um novo pedido invalida os links ainda não usados da mesma conta.
- Token inexistente, usado ou vencido: a mesma resposta (`400 {errors:{token:"TOKEN_INVALID"}}`).
- A troca não faz login automático: a tela manda para `/entrar`.
- As duas rotas públicas novas (`POST /api/customers/password-resets` e `POST /api/customers/password-resets/confirmation`) entram no ADR-0004 e continuam exigindo o token CSRF.
- Pedido com e-mail cadastrado e não cadastrado: mesma resposta e mesmo trabalho no servidor (uma consulta e um agendamento de tarefa), para o tempo também ser igual.
- Prazo do link: `app.password-reset.ttl=PT30M`, no `application.yml` e no `backend/src/test/resources/application.properties` (a configuração de teste é `.properties`).
- O e-mail de aviso não contém senha nem token.
- Nunca gravar em log senha, token, e-mail ou dado pessoal.
- Todo endpoint começa com `/api`. LF em todo arquivo. Texto do usuário só o da lista abaixo.
- Fluxo crítico novo ganha e2e em `frontend/e2e/` sem `page.route` nem mocks, lendo o e-mail em `.tmp/outbox`.

## Textos que o usuário vai ver (lista fechada)

**Tela "Esqueci minha senha" (`/esqueci-senha`)**
- Título: `Recuperar senha`
- Instrução: `Informe o e-mail da sua conta. Se ele estiver cadastrado, enviaremos um link para você criar uma nova senha.`
- Rótulo: `E-mail`; botão: `Enviar link`; botão enviando: `Enviando…`
- Erro de e-mail inválido (mesmo texto do cadastro): `Informe um e-mail válido.`
- Sucesso (igual exista ou não a conta): `Pedido recebido. Se o e-mail estiver cadastrado, você receberá um link em instantes.`
- Erro inesperado: `Não foi possível enviar agora. Tente novamente em instantes.`

**Tela "Nova senha" (`/redefinir-senha#token`)**
- Título: `Nova senha`
- Instrução: `Escolha a nova senha da sua conta.`
- Rótulo: `Nova senha`; botão: `Salvar nova senha`; botão salvando: `Salvando…`
- Erros de senha (já existentes em `errorMessages`): `A senha precisa ter pelo menos 12 caracteres.`, `A senha pode ter no máximo 128 caracteres.`, `Essa senha já apareceu em vazamentos de dados. Escolha outra.`
- Link inválido, vencido ou já usado: `Este link venceu ou já foi usado. Peça um novo link em "Esqueci minha senha".` (com link para `/esqueci-senha`, texto `Esqueci minha senha`)
- Sucesso: `Senha alterada. Por segurança, você saiu de todos os aparelhos. Entre com a nova senha.` (com link para `/entrar`, texto `Entrar`)
- Erro inesperado: `Não foi possível salvar agora. Tente novamente em instantes.`

**Tela de login:** novo link `Esqueci minha senha` (para `/esqueci-senha`).

**E-mail do link** — assunto: `Redefinição de senha na SapiensIA`. Corpo:
```
Olá!

Recebemos um pedido para redefinir a senha da sua conta na SapiensIA. Use o link abaixo para escolher uma nova senha. O link vale por 30 minutos e só pode ser usado uma vez.

%s

Se você não pediu isso, ignore esta mensagem: sua senha continua a mesma.
```

**E-mail de aviso após a troca** — assunto: `Sua senha na SapiensIA foi alterada`. Corpo:
```
Olá!

A senha da sua conta na SapiensIA acabou de ser alterada, e todos os aparelhos foram desconectados.

Se foi você, não precisa fazer nada.

Se não foi você, use "Esqueci minha senha" para criar outra senha agora.
```

Os dois e-mails seguem o padrão dos e-mails do #9 (`CustomerEmailService`): texto puro, saudação `Olá!`, assunto neutro e sem token; o link do e-mail 1 aparece em texto puro, numa linha só. O corpo do e-mail 2 não tem link, senha nem token.

**Comportamento aprovado:** depois do sucesso, a tela "Nova senha" mostra a mensagem com o link `Entrar`; não há redirecionamento automático e a tela de login só ganha o link `Esqueci minha senha`.

## Decisões do Sérgio (aprovadas em 04/11/2026)

1. **Encerrar sessões pela `session_version`**, sem dependência nova. O ADR-0004 registra que a decisão "a sessão passa para o banco" mudou e por quê. SEC-009: na Fase 1 vale só a revogação de todas as sessões (#13, "sem lista de sessões na Fase 1"); a sessão passa para o banco quando entrar a lista de sessões por aparelho, que não tem fase definida.
2. **Prazo do link: 30 minutos**, configurável (`app.password-reset.ttl`).
3. **Conta não confirmada:** mesma resposta `202`, nenhum e-mail.
4. **Limite de pedidos:** fora deste ticket, sem issue nova; o item entrou no #42 e na checklist do #47, e a PR comenta isso.

O aviso por e-mail da troca de senha também aparece no #13; aqui ele é feito para a recuperação (SEC-007), e o #13 reaproveita.

## Review Focus

- Link usado duas vezes (ou duas requisições paralelas com o mesmo link): só uma troca a senha. → Task 2.
- Pedir um segundo link invalida o primeiro. → Task 1.
- Senha nova vazada/curta/longa: link continua válido para nova tentativa. → Task 2.
- Sessão aberta em outro aparelho antes da troca recebe 401 depois dela; login com a senha nova funciona. → Task 3.
- E-mail em maiúsculas ou com espaços nas pontas encontra a conta. → Task 1.

---

### Task 1: Pedido do link (tabela, serviço, e-mail, rota)

**Files:**
- Create: `backend/src/main/resources/db/migration/V5__create_password_reset_tokens.sql` (nova-migration skill)
- Create: `backend/src/main/java/br/com/sergio/sapiensia/customer/PasswordResetTokens.java`
- Create: `backend/src/main/java/br/com/sergio/sapiensia/customer/RequestPasswordResetRequest.java`
- Modify: `CustomerEmailService.java`, `CustomerEmailTasks.java`, `CustomerService.java`, `CustomerController.java`, `CustomerRepository.java`, `EmailConfirmationTokens.java` (tornar `hash(String)` package-private estático para reaproveitar), `SecurityConfig.java`, `application.yml`
- Test: `PasswordResetTokensTest.java`, `PasswordResetRequestTest.java` (mesmo estilo de `EmailConfirmationTest`/`CustomerEmailTest`), `CustomerEmailServiceTest.java`

**Interfaces:**
- Produces: `String PasswordResetTokens.issue(UUID customerId)` (token em texto, grava só o hash, `app.password-reset.ttl` = `PT30M`); `void PasswordResetTokens.revokeAll(UUID customerId)`; `void CustomerService.requestPasswordReset(String email)`; `void CustomerEmailService.sendPasswordReset(UUID customerId)`; tarefa `CustomerEmailTasks.PASSWORD_RESET = "customer-password-reset"`; rota `POST /api/customers/password-resets` → `202`, corpo vazio.
- Tabela `password_reset_tokens(id uuid pk, customer_id uuid fk on delete cascade, token_hash varchar(64) unique, expires_at timestamptz, used_at timestamptz null, created_at timestamptz)` (sem `failed_attempts`: não há senha a adivinhar).

- [ ] **Step 1: Testes que falham** (títulos citam SEC-007)
  - `PasswordResetTokensTest`: `issue` gera token de 32 bytes (43 caracteres Base64 URL) e grava só o hash (a tabela não contém o texto do token); `revokeUnused` apaga os links não usados do cliente.
  - `PasswordResetRequestTest` (integração, `POST /api/customers/password-resets`): e-mail confirmado → `202`, vazio, e-mail com link `.../redefinir-senha#<token>` na caixa de saída de teste; e-mail inexistente → `202` com status, corpo e cabeçalhos iguais e nenhum e-mail; conta não confirmada → `202` e nenhum e-mail; `"  CLIENTE@Exemplo.com "` encontra a conta; dois pedidos seguidos → o primeiro link deixa de funcionar e só o último vale; e-mail inválido (vazio/sem `@`/ >254) → `400` `EMAIL_INVALID`; sem o token CSRF → `403`.
  - Tempo de resposta (SEC-007): 5 requisições de aquecimento, depois 20 alternadas para e-mail cadastrado e não cadastrado; as medianas diferem menos de 50 ms.
  - `CustomerEmailServiceTest`: `sendPasswordReset` não envia nada para cliente inexistente ou não confirmado.
  - Nenhum log capturado no teste contém o token nem o e-mail.
- [ ] **Step 2: Rodar e ver falhar** — `cd backend; .\mvnw.cmd test -Dtest=PasswordReset*Test,CustomerEmailServiceTest`
- [ ] **Step 3: Implementar** a migration V5; `PasswordResetTokens` (mesma forma de `EmailConfirmationTokens`, sem tentativas; usar `EmailConfirmationTokens.hash`; `revokeUnused(UUID)` apaga `where customer_id = ? and used_at is null`); `CustomerRepository.findConfirmedIdByEmail(String email): Optional<UUID>` e `findConfirmedEmailById(UUID)`; `CustomerService.requestPasswordReset` normaliza com `trim().toLowerCase(Locale.ROOT)`, faz uma consulta e **sempre** agenda uma tarefa: com o id da conta confirmada ou, sem conta, com um `UUID` aleatório que a tarefa não encontra. Assim o trabalho é igual nos dois casos. Na tarefa: `revokeUnused` + `issue` + envio; tarefa e bean em `CustomerEmailTasks` com o mesmo `retryThenDrop`; `CustomerEmailService.sendPasswordReset`; DTO com as mesmas anotações de e-mail de `RegisterCustomerRequest`; rota em `CustomerController`; `permitAll` para `POST /api/customers/password-resets` em `SecurityConfig` (CSRF continua valendo); `app.password-reset.ttl: PT30M` no `application.yml` e `app.password-reset.ttl=PT30M` no `backend/src/test/resources/application.properties`.
- [ ] **Step 4: Rodar e ver passar** — mesmo comando. Esperado: PASS.
- [ ] **Step 5: Commit** — `feat: request a password reset link by e-mail`

### Task 2: Troca da senha com o link

**Files:**
- Create: `ResetPasswordRequest.java` (`token`: `@NotBlank`/`@Size(max=64)` → `TOKEN_INVALID`; `newPassword`: `PASSWORD_REQUIRED`/`PASSWORD_TOO_SHORT`/`PASSWORD_TOO_LONG`, como no cadastro)
- Modify: `PasswordResetTokens.java`, `CustomerService.java`, `CustomerController.java`, `CustomerEmailService.java`, `CustomerEmailTasks.java`, `web/ApiExceptionHandler.java` (reaproveitar `InvalidConfirmationTokenException` → `400` `{errors:{token:"TOKEN_INVALID"}}`), `SecurityConfig.java`
- Test: `PasswordResetConfirmationTest.java`

**Interfaces:**
- Consumes: `PasswordResetTokens`, tarefa e e-mails da Task 1.
- Produces: `void CustomerService.resetPassword(String token, String rawPassword)`; `Optional<UUID> PasswordResetTokens.consumeAndSetPassword(String token, String newPasswordHash)`: um só statement (CTE) com `update password_reset_tokens set used_at = now() where token_hash = :hash and used_at is null and expires_at > now() returning customer_id`, que no mesmo statement troca `customers.password_hash`; só quem alterar 1 linha recebe valor. Rota `POST /api/customers/password-resets/confirmation` → `204`, sem cookie de sessão (não há login automático). Tarefa `CustomerEmailTasks.PASSWORD_CHANGED_NOTICE = "customer-password-changed-notice"` + `CustomerEmailService.sendPasswordChangedNotice(UUID)`.

- [ ] **Step 1: Testes que falham** (SEC-007 / regras do cadastro)
  - link válido + senha boa → `204`, sem `Set-Cookie` de sessão; login com a senha nova → `204`; com a antiga → `401`.
  - chega o e-mail de aviso ao dono, e o corpo não contém a senha nova nem o token.
  - dois cliques simultâneos: duas threads liberadas juntas por um `CountDownLatch`, com o mesmo link e senhas diferentes → exatamente um `204` e um `400 TOKEN_INVALID`; a senha gravada é a da requisição que recebeu `204`.
  - link já usado, vencido (mudar `expires_at` no banco) e inexistente → a **mesma** resposta: `400`, mesmo corpo `{errors:{token:"TOKEN_INVALID"}}`.
  - senha fraca (11 caracteres) e com 129 caracteres → `400 PASSWORD_TOO_SHORT`/`PASSWORD_TOO_LONG`; senha vazada (`CompromisedPasswordChecker` simulado) → `400 PASSWORD_COMPROMISED`; nos três casos o link **continua** válido.
  - a senha gravada é Argon2id (hash começa com `$argon2id$`); espaços nas pontas não são aparados.
  - sem o token CSRF → `403`.
- [ ] **Step 2: Rodar e ver falhar** — `.\mvnw.cmd test -Dtest=PasswordResetConfirmationTest`
- [ ] **Step 3: Implementar** `resetPassword` na ordem do cadastro: checar vazada (lança `PasswordCompromisedException`) → `passwordEncoder.encode` → `consumeAndSetPassword` (vazio → `InvalidConfirmationTokenException`) → agendar o aviso; não usar `@Transactional` (Argon2 não segura conexão). A validação de tamanho pelo `@Valid` roda antes de consumir o link. A troca **não** confirma o e-mail (decisão 3: conta não confirmada nunca recebe link).
- [ ] **Step 4: Rodar e ver passar.**
- [ ] **Step 5: Commit** — `feat: reset the password with a single-use link`

### Task 3: Encerrar todas as sessões na troca (SEC-006, decisão 1)

**Files:**
- Create: `backend/src/main/resources/db/migration/V6__add_customer_session_version.sql` (`alter table customers add column session_version integer not null default 0`)
- Create: `backend/src/main/java/br/com/sergio/sapiensia/customer/SessionVersionFilter.java`
- Modify: `CustomerCredentials.java` e a consulta `findCredentialsByEmail` (campo `sessionVersion`), `CustomerUserDetailsService.CustomerPrincipal.withoutPassword()` (mantém a versão), `PasswordResetTokens.consumeAndSetPassword` (mesmo statement faz `session_version = session_version + 1`), `SecurityConfig.java` (registra o filtro depois do `SecurityContextHolderFilter`)
- Test: `SessionRevocationTest.java` (estilo `SessionServerTest`), ajustes em `SessionControllerTest` se o construtor de `CustomerCredentials` mudar

**Interfaces:**
- Produces: `int CustomerRepository.findSessionVersion(UUID id)`; `SessionVersionFilter`: se a autenticação é um `CustomerPrincipal` e a versão dele difere da do banco (ou a conta não existe), invalida a sessão HTTP, limpa o contexto e segue a cadeia (o resto responde `401`); se a consulta ao banco falhar, falha fechado (`503`, sem log de dado pessoal).

- [ ] **Step 1: Testes que falham** (SEC-006)
  - Cliente com duas sessões (dois logins); reset de senha; `GET /api/sessions/current` em **cada** sessão antiga → `401`.
  - Depois do reset, novo login com a senha nova → `GET /api/sessions/current` → `200`.
  - Sem reset, a sessão segue valendo (o filtro não derruba sessão boa); rota pública continua acessível sem sessão.
  - Login em andamento durante o reset (versão lida antes, reset, sessão gravada com a versão velha) → próxima requisição `401`.
- [ ] **Step 2: Rodar e ver falhar** — `.\mvnw.cmd test -Dtest=SessionRevocationTest`
- [ ] **Step 3: Implementar** conforme as Interfaces; uma consulta `select session_version from customers where id = ?` por requisição autenticada.
- [ ] **Step 4: Rodar e ver passar**, e rodar também `-Dtest=Session*Test,Login*Test` (nada do login/logout quebra).
- [ ] **Step 5: Commit** — `feat: end all customer sessions when the password changes`

### Task 4: Telas (React)

**Files:**
- Create: `frontend/src/features/customer/ForgotPasswordPage.tsx` (+ `.test.tsx`), `ResetPasswordPage.tsx` (+ `.test.tsx`)
- Modify: `features/customer/api.ts`, `hooks.ts`, `schemas.ts`, `frontend/src/App.tsx` (rotas `/esqueci-senha` e `/redefinir-senha`), `features/session/LoginPage.tsx` (link `Esqueci minha senha`) e seu teste
- Modify (backend): `SecurityConfig.java`, com um `HeaderWriter` só para `GET /redefinir-senha` que escreve `Referrer-Policy: no-referrer`; teste em `security/` confirma o cabeçalho nessa rota e a CSP `'self'` (sem terceiros)

**Interfaces:**
- Produces: `requestPasswordReset({email}): Promise<void>` (`202`); `resetPassword({token, newPassword}): Promise<void>` (`204`; `400` lança `ResetPasswordError` com `fieldErrors`, no molde de `ConfirmEmailError`); hooks `useRequestPasswordReset`, `useResetPassword`; `forgotPasswordSchema` (e-mail como no cadastro) e `resetPasswordSchema` (12 a 128, como no cadastro). Toda chamada por `apiFetch`.

- [ ] **Step 1: Testes que falham** (Vitest + Testing Library; os textos exatos da lista acima)
  - Esqueci: e-mail inválido mostra o erro e não chama a API; sucesso mostra a mensagem única; falha inesperada mostra o erro inesperado; botão desabilitado e `Enviando…` durante o envio.
  - Nova senha: sem token no fragmento mostra a mensagem de link inválido; senha curta/longa bloqueada no cliente; `TOKEN_INVALID` mostra o link inválido; `PASSWORD_COMPROMISED` aparece no campo e o formulário continua; sucesso mostra a mensagem com link `Entrar`; o token vem de `window.location.hash`.
  - Login: existe o link `Esqueci minha senha` apontando para `/esqueci-senha`.
- [ ] **Step 2: Rodar e ver falhar** — `cd frontend; pnpm vitest run src/features/customer src/features/session`
- [ ] **Step 3: Implementar** copiando a estrutura de `ConfirmEmailPage`/`RegisterPage` (rótulos associados, `role="alert"`/`role="status"`, `autoComplete="new-password"`); sem componente novo de UI.
- [ ] **Step 4: Rodar e ver passar**; depois `/verificar-tela` nas duas telas novas (desktop e celular).
- [ ] **Step 5: Commit** — `feat: forgot password and new password screens`

### Task 5: E2E, ADR e fechamento

**Files:**
- Create: `frontend/e2e/password-reset.e2e.ts` (molde: `email-confirmation.e2e.ts`: caixa de saída em arquivos, e-mail único por projeto, sem mocks)
- Modify: `docs/adr/0004-identidade-e-mfa.md` (linha 25: sessões continuam na memória; a troca de senha as encerra pela `session_version`; rotas públicas novas; prazo de 30 min; conta não confirmada não recebe link; aviso ao titular)

- [ ] **Step 1: Escrever o e2e**: cadastrar e confirmar uma conta; logar na aba A; em outra aba pedir recuperação com o e-mail da conta **e** com um e-mail inexistente (mesma mensagem); abrir o link do e-mail; trocar a senha; mensagem de sucesso; a aba A volta ao login (401); entrar com a senha nova; reusar o link mostra o link inválido; chegou o e-mail de aviso.
- [ ] **Step 2: Rodar** `just e2e` (antes: `just db`, `just dev-back`, `just dev-front`). Esperado: PASS.
- [ ] **Step 3: Atualizar o ADR-0004** (só decisões de arquitetura/regra visível; sem texto de mensagem): (a) a nota "a sessão passa para o banco" muda para a `session_version`, com o porquê: sem dependência nova e sem mudar onde a sessão fica, a troca de senha e o "Sair de todos os aparelhos" (#13) encerram todas as sessões, ao custo de uma consulta por requisição autenticada; (b) SEC-009 na Fase 1 é só revogar todas as sessões; o banco de sessões entra quando houver a lista de sessões por aparelho (fora da Fase 1); (c) as duas rotas públicas novas, com CSRF; (d) link de uso único, 30 min configurável, token no fragmento, `Referrer-Policy: no-referrer`, conta não confirmada sem link, um pedido novo invalida os anteriores, troca sem login automático, aviso ao titular; (e) o limite de pedidos fica para o #42.
- [ ] **Step 4: `just check-resumo`** verde; depois a skill `verificar-entrega` e a tabela dela.
- [ ] **Step 5: Commit** — `test: password reset end to end` e `docs: update ADR-0004 for password reset`

## Auto-revisão

- Cobertura: resposta idêntica (T1, e2e); uso único e prazo curto (T1/T2); sessões encerradas (T3); mesmas regras de senha (T2, T4); SEC-006 (T3), SEC-007 (T1, T2, aviso na T2).
- Riscos: consulta por requisição (T3); `permitAll` em duas rotas (listadas no ADR, com CSRF); enchente de e-mails sem limite de pedidos (decisão 4, #42/#47, citado na PR); limite de login em memória não é zerado pela troca (aceito: some em 15 minutos); teste de tempo com margem de 50 ms pode oscilar no CI (se oscilar, investigar antes de mexer na margem).
- PR: `Closes #12`, cita SEC-006 e SEC-007 e comenta que o limite de pedidos e da troca de senha ficou para o #42/#47, e que o canal de suporte (e a frase de contato no e-mail de aviso) ficou na checklist do #47.
