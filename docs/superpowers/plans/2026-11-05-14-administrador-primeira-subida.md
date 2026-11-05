# Administrador criado na primeira subida e login com capacidades (#14) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** OK expresso do Sérgio em 05/11/2026 para executar, em modo automático. **A tela `/painel/entrar` (Task 4) só depois do OK nos textos dela.** Os textos do e-mail, de `/painel/definir-senha` e de `/painel` estão aprovados.

**Goal:** Na primeira subida, o sistema cria o único Administrador a partir do e-mail do segredo `APP_ADMIN_EMAIL` e envia a ele um link de uso único para definir a senha. O Administrador entra no painel com e-mail e senha, e cada rota do painel exige uma capacidade verificada no servidor. Não existe cadastro público de Administrador nem recuperação de senha do painel no #14.

**Architecture:**
- Tabela própria `administrators`, separada de `customers`, e um pacote `admin/`.
- Um `ApplicationRunner` faz o bootstrap numa transação com `pg_advisory_xact_lock`, para que duas subidas simultâneas criem um só Administrador.
- O e-mail sai por uma tarefa do db-scheduler com ID fixo (`scheduleIfNotExists`). O token só é criado quando a tarefa roda, e sai no máximo um e-mail por hora.
- O login (`POST /api/admin/sessions`) reaproveita o `LoginAttemptLimiter`, o `ClientIpResolver` e o `SessionVersionFilter` do Cliente. Ele troca a sessão inteira e o token CSRF.
- As capacidades são um `enum Capability` no código. O perfil único da Fase 1 recebe todas, e cada rota é liberada no `SecurityConfig` com `hasAuthority("CAPABILITY_...")`.

**Tech Stack:** Spring Boot 4.1 / Spring Security 7, JdbcClient, Flyway, db-scheduler 16.12.0, React + TanStack Query + React Hook Form + Zod, Vitest, Playwright. **Nenhuma dependência nova.**

**Spec:** `docs/spec/sapiensia-spec.md` (seção 3, SEC-001, SEC-002, SEC-006, SEC-007, SEC-010, SEC-012); `docs/adr/0004-identidade-e-mfa.md`; issue #14 (pai #5, histórias 40 e 44).

## IDs cobertos

| ID | Como | Onde |
|---|---|---|
| SEC-010 | `/api/**` segue `denyAll`. Cada rota nova do painel tem regra própria por capacidade, e uma rota `/api/admin/**` sem regra continua negada (teste com rota de sonda) | Task 3 |
| SEC-012 | As capacidades vêm do principal na sessão, montado no servidor, nunca da requisição | Task 3 |
| SEC-007 | Padrão do link de redefinição do ADR-0004, aplicado ao link de definir senha | Tasks 1 e 2 |
| SEC-006 | Definir a senha incrementa a `session_version` do Administrador; o login troca a sessão e o token CSRF | Tasks 2 e 3 |
| SEC-002/003 | Mesmas regras de senha do Cliente (12 a 128 caracteres, HIBP, Argon2id) e bloqueio por tentativas | Tasks 2 e 3 |
| SEC-001 | **Parcial**: só a base. O MFA (TOTP) é o #15 (Risco c) | — |
| ADR-0004 | Atualizado: bootstrap, rotas públicas novas e divergência da spec | Task 1 |

## Decisões do Sérgio (05/11/2026)

1. **Sem "Esqueci minha senha" do Administrador no #14.** A recuperação será tratada junto com o MFA, no #15. Até lá, quem recupera o acesso é o Sérgio: ele apaga a linha do Administrador no banco e reinicia o sistema (Risco g).
2. **Link do primeiro acesso válido por 24 h.** Um link novo invalida os anteriores. Sai no máximo 1 e-mail de primeiro acesso por hora, mesmo com reinícios repetidos.
3. **E-mails fictícios:** `admin@example.com` no perfil `local` (e no `application.yml` de teste) e `admin-e2e@example.com` no CI.
4. **`APP_ADMIN_EMAIL` vazio ou inválido só impede a subida no perfil `prod`.** Fora do `prod`, o bootstrap é pulado com um aviso no log. Local e CI sempre têm o valor fictício.
5. **E-mail do segredo diferente do gravado:** ignorado, com um aviso genérico no log e sem mostrar nenhum dos e-mails.
6. **Painel só com senha até o #15:** aceito. Não fazer deploy em homologação nem em produção antes do #15. O Sérgio cuida das checklists do #7 e do #47.
7. **Cliente logado em `/painel`:** vê `Esta área é só para o Administrador.` com um link para o início.

## Condições do OK (05/11/2026)

- **Mensagens de senha idênticas às do Cliente:**
  - No backend, o `POST /api/admin/password-setup` reaproveita o próprio record `customer/ResetPasswordRequest` (que passa a `public`), com os mesmos `@NotBlank`/`@Size(min = 12)`/`@Size(max = 128)` e os mesmos códigos.
  - Também reaproveita o mesmo `CompromisedPasswordChecker` (SEC-002) e o mesmo `PasswordEncoder` (Argon2id).
  - No frontend, usa `resetPasswordSchema` e `errorMessages` de `features/customer/schemas.ts`.
  - Nenhum texto de senha novo.
- **Tarefa de e-mail, na ordem "envia, depois grava":**
  - A tarefa gera o token em memória e envia o e-mail.
  - **Só depois do envio dar certo**, numa única transação, ela apaga os links não usados, insere o hash do novo e grava `password_link_sent_at = now()`.
  - Se o envio falhar, nada é gravado. A retentativa gera outro token em memória, mas o banco nunca fica com o da tentativa que falhou, então nunca há um segundo token.
  - O caso de envio ambíguo está no Risco (k).
- **Tempo de resposta igual no login:**
  - Os três casos são: Administrador existente com senha errada, e-mail inexistente e Administrador ainda sem senha.
  - O `UserDetailsService` do painel lança `UsernameNotFoundException` também para a senha nula. Assim, o `DaoAuthenticationProvider` calcula o hash falso (`mitigateAgainstTimingAttack`) nos três casos.
  - O teste usa a mesma técnica do `PasswordResetRequestTest`: 5 requisições de aquecimento, 20 alternadas e diferença de mediana menor que 50 ms.
- **Travas do e2e:**
  - O arquivo se recusa a rodar se o host da URL base não for `localhost` ou `127.0.0.1`.
  - A senha nunca aparece na saída: `trace`, `screenshot` e `video` ficam desligados nesse arquivo, e a senha não entra em título, mensagem ou `expect`.
  - A verificação final procura a senha no stdout, em `.tmp/e2e-report` e em `.tmp/e2e-results` (zero ocorrências).
- **E-mail longo em `/painel`:** quebra desde o início (`[overflow-wrap:anywhere]`, cf. #63). O `/verificar-tela` confere isso a 390 px com um e-mail de 254 caracteres.

## Global Constraints

- Rotas `permitAll` novas: só `POST /api/admin/sessions` e `POST /api/admin/password-setup`. As duas continuam exigindo o token CSRF e entram no ADR-0004. Nenhuma rota cria Administrador.
- **Link de definir senha (padrão do ADR-0004 para a redefinição):**
  - token de 32 bytes do `SecureRandom` (Base64 URL sem padding, 43 caracteres), e só o hash SHA-256 no banco;
  - uso único com um único `UPDATE` atômico (`... where token_hash = :hash and used_at is null and expires_at > now()`);
  - o token vai no fragmento (`/painel/definir-senha#<token>`) e é enviado à API só no corpo de um `POST`;
  - `GET /painel/definir-senha` responde com `Referrer-Policy: no-referrer`.
- O link do e-mail é montado **só** com `app.public-base-url` (`APP_PUBLIC_BASE_URL`), nunca com o cabeçalho `Host` nem com dados de requisição. O bootstrap roda sem requisição, e a classe de e-mail não recebe `HttpServletRequest`.
- `app.admin.email: ${APP_ADMIN_EMAIL:}`; `app.admin.required: true` só no `application-prod.yml`.
- A validação do e-mail é a mesma do cadastro do Cliente: `@Email` mais no máximo 254 caracteres, conferida pelo `jakarta.validation.Validator`. O e-mail é normalizado com `trim().toLowerCase(Locale.ROOT)`.
- Não existe senha inicial: o Administrador nasce com `password_hash` nulo.
- O erro de login do painel é idêntico (`401`, sem corpo e sem cookie) para e-mail errado, senha errada e senha ainda não definida. O bloqueio responde `429` com `Retry-After`, e uma falha do banco responde `503`, como no #10/#11. As chaves do limitador levam o prefixo `admin:`.
- Nunca vão para log: o e-mail, o token, a senha nem o hash. O `toString()` dos records de credenciais e do principal mostra só o `id`.
- Textos que o usuário vê: só os da lista fechada abaixo. LF em todos os arquivos.

## Textos que o usuário vai ver (lista fechada)

**E-mail do primeiro acesso.** Assunto: `Defina sua senha de Administrador na SapiensIA`
```
Olá!

Uma conta de Administrador da SapiensIA foi criada para este e-mail. Use o link abaixo para definir a sua senha. O link vale por 24 horas e só pode ser usado uma vez.

%s

Se você não esperava esta mensagem, não use o link.
```
O `%s` vira `<APP_PUBLIC_BASE_URL>/painel/definir-senha#<token>`. É texto puro, como os e-mails do #9 e do #12.

**Tela `/painel/definir-senha`**
- Título: `Definir senha do painel`
- Instrução: `Escolha a senha da sua conta de Administrador.`
- Rótulo do campo: `Nova senha`
- Botão: `Salvar senha`; enquanto envia: `Salvando…`
- Sucesso: `Senha definida. Entre no painel com a nova senha.` + link `Entrar no painel`
- Link inválido, vencido ou já usado: `Este link venceu ou já foi usado. Se você ainda não definiu a senha, um novo link chega por e-mail quando o sistema for reiniciado.`
- Senha curta (ou vazia): `A senha precisa ter pelo menos 12 caracteres.`
- Senha longa: `A senha pode ter no máximo 128 caracteres.`
- Senha vazada: `Essa senha já apareceu em vazamentos de dados. Escolha outra.`
- Erro inesperado: `Não foi possível salvar agora. Tente novamente em instantes.`

**Tela `/painel/entrar`**
- Título: `Entrar no painel`
- Rótulos: `E-mail` e `Senha`
- Botão: `Entrar`; enquanto envia: `Entrando…`
- Falha (e-mail errado, senha errada ou senha não definida, sempre igual): `E-mail ou senha incorretos.`
- E-mail em formato inválido (validação da tela): `Informe um e-mail válido.`
- Senha vazia (validação da tela): `Informe a senha.`
- Bloqueio: `Muitas tentativas de entrada. Aguarde alguns minutos e tente de novo.`
- Erro inesperado: `Não foi possível entrar agora. Tente novamente em instantes.`

**Tela `/painel`**
- Carregando: `Carregando…`
- Título: `Painel do Administrador`
- Texto: `Você entrou como <e-mail>.`
- Botão: `Sair`; enquanto envia: `Saindo…`; erro: `Não foi possível sair agora. Tente novamente.`
- Sem sessão: redireciona para `/painel/entrar`, sem texto próprio.
- Cliente logado: `Esta área é só para o Administrador.` + link `Voltar ao início` (para `/`)
- Erro ao carregar: `Não foi possível carregar o painel agora. Tente novamente.`

**Página `/painel/*` que não existe:** segue o comportamento atual do `App.tsx` para rotas desconhecidas, sem texto novo.

## Rotas de `/api` afetadas

| Rota | Regra no `SecurityConfig` | Anônimo | Cliente logado | Administrador logado |
|---|---|---|---|---|
| `POST /api/admin/sessions` | `permitAll` + CSRF | 204 (login), 401, 429 | 204 só se tiver conta de Administrador; senão 401. Um 204 substitui a sessão do Cliente | 204 (nova sessão) |
| `GET /api/admin/sessions/current` | `hasAuthority("CAPABILITY_PANEL_ACCESS")` | 401 | **403** | 200 `{email, capabilities}` |
| `POST /api/admin/password-setup` | `permitAll` + CSRF (o token do link é a credencial) | 204 / 400 `TOKEN_INVALID` ou erro de senha | igual ao anônimo | igual ao anônimo |
| `DELETE /api/sessions/current` (logout existente) | filtro de logout, sem mudança | 204 | 204 | 204 (encerra a sessão do painel) |
| `GET /api/sessions/current` | `hasRole("CUSTOMER")`, sem mudança | 401 | 200 | **403** |
| `DELETE /api/sessions` (sair de todos) | `hasRole("CUSTOMER")`, sem mudança | 401 | 204 | **403** |
| `POST /api/sessions`, `/api/customers/**` | sem mudança | como hoje | como hoje | como hoje (não abrem nada de painel) |
| qualquer outra `/api/admin/**` (ex.: `POST /api/admin`, `POST /api/administrators`, a rota de sonda do teste) | `denyAll` (regra final, sem mudança) | 401 | 403 | **403** |

Sem o token CSRF, toda rota que muda estado responde `403`. O Administrador recebe só `CAPABILITY_PANEL_ACCESS`, sem `ROLE_CUSTOMER`. Cada ticket do painel (#17 em diante) acrescenta a sua capacidade ao enum e a sua regra no `SecurityConfig`.

## Riscos

**a) Concorrência: duas instâncias ou duas subidas podem criar dois Administradores?** Não.
- O bootstrap é `@Transactional` e começa com `select pg_advisory_xact_lock(hashtext('administrator-bootstrap'))`. Duas instâncias no mesmo banco passam uma de cada vez, e a segunda já encontra a linha.
- O insert é `insert into administrators (email) select :email where not exists (select 1 from administrators)`, com `unique (email)` como reforço. Rodar N vezes deixa 1 Administrador: é atômico e idempotente.
- O agendamento do e-mail **não** entra nessa transação: o starter 16.12.0 não envolve o `DataSource` num proxy transacional. Por isso a tarefa `administrator-password-setup` usa um ID de instância fixo com `scheduleIfNotExists(TaskInstance<T>, Instant)` (assinatura conferida na 16.12.0), e fica no máximo 1 pendente.
- Se o processo cair entre o insert e o agendamento, a próxima subida vê o Administrador "sem senha" e agenda o e-mail.
- Duas abas usando o mesmo link: o `UPDATE` atômico deixa só uma passar.

**b) O que cresce sem limite?** Nada.
- `administrators`: 1 linha.
- `administrator_password_tokens`: cada emissão apaga os links não usados, e o único usado fica (1 linha).
- `scheduled_tasks`: no máximo 1 tarefa, pelo ID fixo.
- E-mails: no máximo 1 por hora (coluna `administrators.password_link_sent_at`, gravada pela tarefa só depois do envio dar certo). O bootstrap só agenda se ela for nula ou tiver mais de 1 hora (`app.admin.password-link-min-interval: PT1H`).
- `LoginAttemptLimiter`: os mapas já têm teto desde o #11, e as chaves `admin:` entram no mesmo teto.
- Logs: 1 linha por subida, mais as do login que já existem.

**c) O código bate com o ADR-0004 e a spec?** Divergências:
1. SEC-001 e o ADR-0004 dizem que o MFA é obrigatório, e o #14 entrega o login só com senha. Aceito pelo Sérgio (decisão 6): sem deploy em homologação nem em produção antes do #15.
2. O ADR-0004 não fala do bootstrap nem das rotas públicas do painel. A Task 1 o atualiza com a criação na primeira subida pelo segredo (sem senha inicial, link de 24 h, no máximo 1 e-mail por hora), as duas rotas `permitAll` novas e a divergência abaixo.
3. A spec (seção 8) cita `User`, `Role` e `Permission`. O plano usa a tabela `administrators` mais o `enum Capability` no código, sem tabelas de papéis. Isso cumpre o ADR ("novos perfis sem refazer a autorização": um perfil novo é um novo mapeamento de capacidades) sem criar estrutura para o futuro. Fica registrado no ADR.
4. A sessão do Administrador fica na memória, como a do Cliente (uma só instância por ambiente, já está no ADR).
5. SEC-008 (nova autenticação) fica para o #22, e a auditoria para o #16.

**d) Segredos.**
- Só o **e-mail** vem do segredo `APP_ADMIN_EMAIL`: Fly secrets em homologação e produção, sem valor no `.env.example` e fictício no local e no CI.
- **Não existe senha inicial**: nada é gerado, impresso, logado ou guardado. O próprio Administrador escolhe a senha pelo link, e só o hash Argon2id é gravado.
- O token não é logado e não vai nos dados da tarefa, que levam só o `id`. No banco fica só o hash SHA-256.
- Um teste com `OutputCaptureExtension` confere que o log do bootstrap, da tarefa e do login não contém o e-mail nem o token.
- A troca obrigatória é estrutural: sem definir a senha pelo link, não há como entrar. O segundo fator obrigatório no primeiro acesso é o critério 1 do #15.

**e) Reinício da aplicação.**
- Já existe com senha: nada muda, nenhum e-mail, log `administrator bootstrap: exists`.
- Existe sem senha: se o último e-mail tiver mais de 1 hora, sai um link novo que invalida os anteriores; senão, nada, e o link anterior continua valendo até vencer (log `pending`).
- E-mail do segredo diferente do gravado: ignorado, com o log `administrator bootstrap: configured e-mail differs from the existing administrator; ignored`, sem nenhum e-mail.
- `APP_ADMIN_EMAIL` vazio ou inválido: no `prod`, a subida falha; fora dele, o bootstrap é pulado com aviso.
- Desativado: a Fase 1 não tem desativação nem coluna para isso. Se a linha for apagada, a próxima subida cria o Administrador de novo pelo segredo, e a sessão antiga cai no `SessionVersionFilter`.
- O reinício encerra todas as sessões (sessão em memória).

**f) Capacidades: o login do Administrador abre só o que a spec prevê.** Ver a tabela de rotas acima.
- O Administrador ganha só `CAPABILITY_PANEL_ACCESS`, que abre `GET /api/admin/sessions/current`.
- As rotas do Cliente respondem `403` a ele, e qualquer outra `/api/admin/**` segue `denyAll`.
- Um teste registra uma rota de sonda `GET /api/admin/probe` (controller só do código de teste, sem regra no `SecurityConfig`) e prova que ela responde `401` ao anônimo, `403` ao Cliente e `403` ao Administrador com `PANEL_ACCESS`.

**g) O Administrador perde a senha antes do #15.** Não existe rota de recuperação no #14 (decisão 1). Quem recupera o acesso é o Sérgio, com acesso ao banco:
1. apaga a linha (`delete from administrators;`; os links vão junto pelo `on delete cascade`);
2. reinicia o sistema;
3. o bootstrap recria o Administrador pelo segredo e envia o link na hora, porque a linha nova não tem `password_link_sent_at`.

Isso só vale para o banco local e para o de homologação, porque não há produção antes do #15. A operação fica documentada no ADR-0004. Quem tem acesso ao banco já tem acesso total, então isso não abre nenhum caminho novo. O e2e nunca faz isso.

**h) Bloqueio provocado por quem conhece o e-mail do Administrador.** Quem souber o e-mail consegue bloquear o login do painel por 15 minutos com 5 senhas erradas, e repetir isso. É o mesmo comportamento do #11, e só o limite por IP o freia. **Aceito na Fase 1.** O #15 (MFA) e o #42 (limites) podem rever isso.

**i) O mesmo navegador com sessão de Cliente e de Administrador ao mesmo tempo?** **Não.** Um navegador tem um só cookie `JSESSIONID`, logo uma só sessão e uma só identidade.
- O login do painel substitui a sessão inteira: ID novo, **sem** copiar os atributos da sessão anterior (`SessionFixationProtectionStrategy` com `setMigrateSessionAttributes(false)`) e token CSRF novo. O Cliente que estava logado naquele navegador sai.
- O login do Cliente depois do Administrador também substitui a identidade (`ChangeSessionIdAuthenticationStrategy` mais um contexto novo, já existentes).
- Testado nos dois sentidos (Task 3).

**j) Repetir o e2e no mesmo banco sem rota nova.** O e2e guarda a senha que definiu para o Administrador em `.tmp/e2e/admin-password.txt`, uma pasta já ignorada pelo git. O fluxo:
1. Se o arquivo existe, o e2e entra com essa senha.
2. Se o arquivo não existe, ou se o login com ele der `401`, o e2e pega o e-mail de primeiro acesso mais recente do Administrador em `.tmp/outbox`. Define uma senha aleatória pela tela `/painel/definir-senha`, grava o arquivo e entra.
3. Se nada disso funcionar (senha desconhecida e nenhum link válido), o teste falha com uma mensagem que aponta para o Risco (g). Ele não é pulado.

Assim:
- No CI (banco novo a cada execução), toda execução passa pelo primeiro acesso completo, pelo link, e a retentativa reaproveita o arquivo.
- No local, a primeira execução faz o primeiro acesso e as seguintes entram com a senha guardada.

O teste roda só no projeto `desktop` do Playwright, porque há um só Administrador e o `celular` disputaria o mesmo link. O celular fica coberto pelo `/verificar-tela`.

Travas: o teste se recusa a rodar fora de `localhost`/`127.0.0.1`, e a senha nunca aparece na saída (ver "Condições do OK").

Descartado: preparar o estado direto no banco a partir do Playwright. Isso exigiria o pacote `pg` (dependência nova) ou chamar `psql` por fora.

**k) Envio ambíguo do e-mail do primeiro acesso.** Como a tarefa grava o token só depois do envio dar certo, há dois casos em que o link entregue não vale:
- o provedor entrega o e-mail e mesmo assim responde com erro;
- o processo cai entre o envio e a gravação.

Nos dois casos, a retentativa manda outro link, e só ele vale. O Administrador recebe dois e-mails e usa o mais recente. A tela de link inválido já explica o caminho.

---

### Task 1: Tabelas, bootstrap, e-mail do primeiro acesso e ADR

**Files:**
- Create: `backend/src/main/resources/db/migration/V8__create_administrators.sql` (skill `nova-migration`)
- Create: `backend/src/main/java/br/com/sergio/sapiensia/admin/AdministratorBootstrap.java`, `Administrators.java` (JdbcClient), `AdministratorPasswordTokens.java`, `AdministratorEmailService.java`, `AdministratorEmailTasks.java`
- Modify: `backend/src/main/resources/application.yml`, `application-local.yml`, `application-prod.yml`, `backend/src/test/resources/application.yml`, `.env.example` (`APP_ADMIN_EMAIL=`), `.github/workflows/ci.yml` (`-e APP_ADMIN_EMAIL=admin-e2e@example.com` e `E2E_ADMIN_EMAIL`), `docs/adr/0004-identidade-e-mfa.md`
- Modify (visibilidade): `customer/PasswordResetTokens.newToken()` e `customer/EmailConfirmationTokens.hash(String)` passam a `public`
- Test: `backend/src/test/java/br/com/sergio/sapiensia/admin/AdministratorBootstrapTest.java`, `AdministratorBootstrapProdConfigTest.java`, `AdministratorEmailTest.java`

**Interfaces:**
- Produces:
  - Tabela `administrators(id uuid pk default gen_random_uuid(), email varchar(254) not null unique, password_hash varchar(255), session_version integer not null default 0, password_link_sent_at timestamptz, created_at timestamptz not null default now())`.
  - Tabela `administrator_password_tokens(id uuid pk, administrator_id uuid not null references administrators on delete cascade, token_hash varchar(64) not null unique, expires_at timestamptz not null, used_at timestamptz, created_at timestamptz not null default now())`, com índice em `administrator_id`.
  - `enum BootstrapResult { CREATED, PENDING_EMAIL_SCHEDULED, PENDING_RECENTLY_SENT, EXISTS, CONFIG_DIFFERS, SKIPPED_NOT_CONFIGURED }` e `@Transactional BootstrapResult AdministratorBootstrap.bootstrap()`, chamado por um `ApplicationRunner`.
  - `@Transactional void AdministratorPasswordTokens.replaceWith(UUID administratorId, String tokenHash)`: trava a linha, apaga os links não usados, insere o novo (TTL `app.admin.password-link-ttl: PT24H`) e grava `password_link_sent_at = now()`. A tarefa só o chama **depois** do envio dar certo.
  - `public OptionalInt Administrators.findSessionVersion(UUID id)`, usado pelo `SessionVersionFilter` na Task 3 (outro pacote usa um método público, nunca o repositório).
  - `Optional<UUID> consumeAndSetPassword(String token, String passwordHash)`: um único `UPDATE` que marca o link como usado, grava o hash e faz `session_version + 1` (usado na Task 2).
  - Tarefa `administrator-password-setup` (`OneTimeTask<UUID>`), com instância de ID fixo `"bootstrap"`. Ela gera o token (`PasswordResetTokens.newToken()`), envia o e-mail e só então chama `replaceWith`. Repete como `retryThenDrop`, no padrão de `CustomerEmailTasks`.

- [ ] **Step 1: Testes que falham** (títulos citam SEC-007/ADR-0004):
  - Banco vazio → `CREATED`; 1 linha com o e-mail normalizado e `password_hash` nulo; 1 e-mail no `RecordingEmailSender`, com o assunto da lista e o link casando `^https://sapiensia\.test/painel/definir-senha#[A-Za-z0-9_-]{43}$` (com `app.public-base-url=https://sapiensia.test` no teste).
  - Segunda chamada logo depois → `PENDING_RECENTLY_SENT`, nenhum e-mail novo, e o primeiro link continua válido.
  - Com `password_link_sent_at` recuado em 61 min → `PENDING_EMAIL_SCHEDULED`, um e-mail novo, o link antigo deixa de valer e o novo vale.
  - Com senha definida → `EXISTS`, nenhum e-mail.
  - E-mail configurado diferente → `CONFIG_DIFFERS`, continua 1 linha e o e-mail gravado não muda.
  - Concorrência: 2 threads chamam `bootstrap()` juntas (`CountDownLatch`) → `count(*) = 1` e no máximo 1 tarefa pendente.
  - Linha apagada → a próxima chamada é `CREATED` e envia na hora (Risco g).
  - Envio que falha (`RecordingEmailSender.failNextSendTo`):
    - depois da falha: 0 linhas em `administrator_password_tokens` e `password_link_sent_at` nulo;
    - depois da retentativa: exatamente 1 linha e `password_link_sent_at` preenchido;
    - só o token do e-mail entregue funciona.
  - O link do e-mail começa com o `app.public-base-url` do teste. A classe de e-mail não recebe `HttpServletRequest` nem lê cabeçalhos: o bootstrap roda sem requisição.
  - Fora do `prod`, e-mail vazio ou `nao-e-email` → `SKIPPED_NOT_CONFIGURED`, sem linha. No `prod` (`app.admin.required=true`), vazio ou inválido → o contexto não sobe (`AdministratorBootstrapProdConfigTest`, no estilo do `ResendEmailSenderConfigurationTest`).
  - O log capturado de todos os casos não contém o e-mail nem o token.
- [ ] **Step 2:** `cd backend; .\mvnw.cmd test -Dtest="AdministratorBootstrap*Test,AdministratorEmailTest"` → FAIL.
- [ ] **Step 3:** Implementar conforme os Riscos (a), (b) e (e). Atualizar o ADR-0004 conforme o Risco (c).2 e (c).3, e documentar a operação do Risco (g).
- [ ] **Step 4:** Os mesmos testes → PASS.
- [ ] **Step 5:** `git commit -m "feat: create the administrator on first startup (#14)"`

### Task 2: Definir a senha pelo link (`POST /api/admin/password-setup`)

**Files:**
- Create: `admin/AdministratorPasswordController.java` (recebe o `customer/ResetPasswordRequest`, que passa a `public`)
- Modify: `security/SecurityConfig.java` (`permitAll` e o `Referrer-Policy` em `GET /painel/definir-senha`)
- Test: `admin/AdministratorPasswordSetupTest.java`; um caso novo em `security/ResetPasswordPageHeadersTest.java`

**Interfaces:**
- Consumes: `AdministratorPasswordTokens.consumeAndSetPassword` (Task 1), `PasswordEncoder` e `CompromisedPasswordChecker`.
- Produces: corpo `{token, newPassword}` → `204`. Os erros são `400` no formato do #12: `errors.token = TOKEN_INVALID`, ou `errors.newPassword = PASSWORD_TOO_SHORT | PASSWORD_TOO_LONG | PASSWORD_COMPROMISED`. O HIBP roda antes do `UPDATE`, então uma senha recusada não gasta o link.

- [ ] **Step 1: Testes que falham** (SEC-007, SEC-006):
  - Link válido → `204`, e o hash no banco confere com a senha.
  - Reuso, vencido (`expires_at` recuado) e desconhecido → o mesmo `400 TOKEN_INVALID`.
  - Senha curta, longa ou vazada → `400`, e o link continua valendo.
  - 2 requisições paralelas com o mesmo link → exatamente um `204`.
  - Sem CSRF → `403`.
  - `GET /painel/definir-senha` → `Referrer-Policy: no-referrer`.
  - O token só vale no corpo do `POST`. `GET /api/admin/password-setup?token=...` não define a senha: responde `401` ao anônimo, porque a rota só existe para `POST` e o `denyAll` pega o resto.
- [ ] **Step 2:** FAIL. **Step 3:** Implementar. **Step 4:** PASS.
- [ ] **Step 5:** `git commit -m "feat: administrator sets the password with a single-use link (#14)"`

### Task 3: Login do Administrador e capacidades (SEC-010)

**Files:**
- Create: `admin/Capability.java` (`enum { PANEL_ACCESS }`, com `String authority()` → `"CAPABILITY_" + name()`), `admin/AdministratorPrincipal.java`, `admin/AdminSessionController.java`
- Modify: `customer/SessionVersionFilter.java` (também confere o `AdministratorPrincipal` contra `administrators.session_version`; falha fechada com `503`), `customer/LoginAttemptLimiter.java` e `customer/ClientIpResolver.java` (só a visibilidade, `public`), `security/SecurityConfig.java`
- Test: `admin/AdminSessionTest.java`, `admin/AdminRouteDenyByDefaultTest.java` (com a sonda `GET /api/admin/probe` num `@TestConfiguration`)

**Interfaces:**
- Consumes: Task 1 (tabela) e Task 2 (senha definida).
- Produces:
  - `POST /api/admin/sessions {email, password}` → `204`, com `JSESSIONID` novo (sessão anterior invalidada, sem migrar atributos) e `XSRF-TOKEN` novo; `401`/`429`/`503` como no #10/#11.
  - `GET /api/admin/sessions/current` → `{"email": "...", "capabilities": ["PANEL_ACCESS"]}`.
  - Na sessão fica o `record AdministratorPrincipal(UUID id, String email, int sessionVersion, Set<Capability> capabilities) implements UserDetails`, sem o hash.
  - O `DaoAuthenticationProvider` próprio usa um `UserDetailsService` que **não** é bean (para não disputar com o `CustomerUserDetailsService`). Senha nula → a conta é tratada como inexistente, com o mesmo `401` e o mesmo custo de hash.

- [ ] **Step 1: Testes que falham** (títulos com SEC-010/SEC-006/SEC-002):
  - Senha certa → `204`; o `current` → `200` com `PANEL_ACCESS`; o `JSESSIONID` e o `XSRF-TOKEN` do login diferem dos anteriores.
  - E-mail errado, senha errada e senha não definida → `401` com resposta idêntica (status, cabeçalhos relevantes, corpo vazio).
  - 5 erros → `429` com `Retry-After`. Um Cliente com o mesmo e-mail continua entrando por `POST /api/sessions` (chaves separadas).
  - Sessão de Cliente → `GET /api/admin/sessions/current` → `403`. Sessão de Administrador → `GET /api/sessions/current` e `DELETE /api/sessions` → `403`. Anônimo → `401`.
  - Mesmo navegador (mesmo cookie jar): Cliente logado → login do painel → `GET /api/sessions/current` → `403` e o `current` do painel → `200`. O sentido inverso também (Risco i).
  - Senha definida de novo por um link novo (Task 2) → a sessão antiga do Administrador → `401`.
  - O logout `DELETE /api/sessions/current` encerra a sessão do Administrador.
  - Sonda `GET /api/admin/probe` e `POST /api/admin` / `POST /api/administrators` → `401` anônimo, `403` Cliente, `403` Administrador.
  - O log do login não contém o e-mail.
  - Tempo igual (técnica do `PasswordResetRequestTest`): Administrador com senha errada, e-mail inexistente e Administrador sem senha, com diferença de mediana menor que 50 ms entre os pares.
- [ ] **Step 2:** FAIL. **Step 3:** Implementar. **Step 4:** PASS.
- [ ] **Step 5:** `git commit -m "feat: administrator login with server-side capabilities (#14)"`

### Task 4: Telas do painel

**Files:**
- Create: `frontend/src/features/admin/{api.ts,hooks.ts,schemas.ts,AdminLoginPage.tsx,SetAdminPasswordPage.tsx,AdminHomePage.tsx}` e os `*.test.tsx` de cada tela
- Modify: `frontend/src/App.tsx` (rotas `/painel`, `/painel/entrar` e `/painel/definir-senha`)

**Interfaces:**
- Consumes: as rotas das Tasks 2 e 3, sempre via `apiFetch`. Reaproveita `resetPasswordSchema` e `errorMessages` de `features/customer/schemas.ts` e `useLogout` de `features/session/hooks.ts`.
- `fetchAdminSession(): Promise<AdminSession | null | "forbidden">`: `401` → `null` (redireciona) e `403` → `"forbidden"`.
- `SetAdminPasswordPage` lê o token de `useLocation().hash`, como a `ResetPasswordPage`.

- [ ] **Step 0:** a `AdminLoginPage` só depois do OK do Sérgio nos textos de `/painel/entrar`.
- [ ] **Step 1:** Testes Vitest de cada estado da lista de textos. Além disso: a `SetAdminPasswordPage` envia o token só no corpo do `POST`, nunca na URL da chamada, e a `AdminHomePage` usa `[overflow-wrap:anywhere]` no e-mail. Estados: carregando, sucesso, cada erro, link inválido, `403`, `401` → redireciona, botão desabilitado enquanto envia.
- [ ] **Step 2–4:** FAIL → implementar → PASS (`just test-front`).
- [ ] **Step 5:** `/verificar-tela` nas 3 telas, em desktop e celular (390 px, com um e-mail de 254 caracteres em `/painel`) e pelo teclado.
- [ ] **Step 6:** `git commit -m "feat: administrator panel login and password pages (#14)"`

### Task 5: E2E contra o sistema de verdade

**Files:** `frontend/e2e/admin-panel.e2e.ts`; `frontend/playwright.config.ts` (o arquivo é ignorado no projeto `celular`).
- Sem `page.route` nem mocks. O e-mail é lido em `.tmp/outbox` com o mesmo helper do `password-reset.e2e.ts` (copiado para o arquivo, ou extraído para `e2e/outbox.ts` se ficar idêntico).
- O e-mail do Administrador vem de `E2E_ADMIN_EMAIL` (padrão `admin@example.com`; `admin-e2e@example.com` no CI). A senha fica em `.tmp/e2e/admin-password.txt`, conforme o Risco (j).
- Fluxo: entrar no painel (pelo primeiro acesso, quando for o caso) → `/painel` mostra `Você entrou como admin…` → `Sair` volta para `/painel/entrar`. Depois, um Cliente novo (cadastro e confirmação, com os helpers do #12) abre `/painel` e vê `Esta área é só para o Administrador.`
- Travas: o teste se recusa a rodar fora de `localhost`/`127.0.0.1`; `test.use({ trace: "off", screenshot: "off", video: "off" })`; a senha nunca entra em título, log ou `expect`.
- [ ] Rodar duas vezes seguidas no banco local (`just db` e `just dev-back` na pasta principal, `just dev-front`, `just e2e`) → PASS nas duas. Procurar a senha no stdout, em `.tmp/e2e-report` e em `.tmp/e2e-results` → 0 ocorrências. **Commit:** `test: e2e of the administrator panel login (#14)`

### Task 6: Fechamento

- [ ] `just check-resumo` verde; rodar o sistema de verdade e conferir o fluxo no navegador; skill `verificar-entrega` com a tabela dela.
- [ ] O que ficar de fora vira uma única issue `needs-triage` citando o #14.
