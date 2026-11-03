# E-mail transacional (Resend), confirmação de e-mail e proteção contra pré-sequestro — Plano de implementação

> **Para quem executar:** use superpowers:subagent-driven-development (recomendado) ou superpowers:executing-plans. Os passos usam checkbox (`- [ ]`).

**Objetivo:** o Cliente recebe um e-mail com link de uso único e prazo curto e confirma o endereço digitando a senha que escolheu no cadastro; quem tenta se cadastrar com um e-mail que já tem conta confirmada recebe um aviso; um atacante não consegue "pré-sequestrar" o e-mail de outra pessoa; tudo sai por um adaptador do Resend atrás de uma interface.

**Abordagem:** o cadastro (`POST /api/customers`, do #8) agenda **uma** tarefa em segundo plano (db-scheduler, ADR-0005) em todos os casos, e a resposta continua idêntica. A tarefa gera o token no momento do envio (o token nunca fica em texto puro no banco, nem na tabela de tarefas), grava só o hash SHA-256 e manda o e-mail pela interface `EmailSender`. O link abre uma página do React com um campo de senha; ela chama `POST /api/customers/email-confirmations` com o token e a senha, e o servidor só confirma se a senha bate com o hash da conta. Depois de 5 senhas erradas no mesmo link, o link deixa de valer. Um cadastro repetido de e-mail **ainda não confirmado** troca a senha e invalida os links anteriores; uma tarefa diária apaga contas não confirmadas há mais de 7 dias. O envio é escolhido por configuração (`app.email.provider`): `resend` em produção (a chave `RESEND_API_KEY` é obrigatória, senão a aplicação nem sobe) e `file` no desenvolvimento e no job e2e do CI (os e-mails viram arquivos em `.tmp/outbox/`); nos testes, um falso.

**Stack:** Java 25, Spring Boot 4.1.2, `RestClient` (já existe, **nenhuma dependência nova**), db-scheduler 16.12.0 (já no `pom.xml`), Flyway, PostgreSQL 18 (Testcontainers), React + TanStack Query, Vitest, Playwright.

**Spec:** SEC-007 (`docs/spec/sapiensia-spec.md:351`, aplicado por analogia ao link de confirmação: token aleatório, uso único, expiração curta, resposta que não revela se a conta existe, aviso ao titular), SEC-002 e SEC-033; ADR-0004; ADR-0005. Issue #9, filha de #5; comentário da #9 (aviso de e-mail repetido); **item 1 da #49** (pré-sequestro de conta), tratado neste ticket por decisão do Sérgio. Os itens 2 e 3 da #49 ficam abertos (a limpeza de contas não confirmadas, citada no item 3, passa a existir aqui).

## Fatos conferidos na fonte oficial (28/10/2026)

- **Resend:** `POST https://api.resend.com/emails`, cabeçalho `Authorization: Bearer <chave>` e `Content-Type: application/json`; corpo com `from` (aceita `Nome <endereco@dominio>`), `to`, `subject` e `text` (texto simples; `html` é opcional). Sucesso devolve `{"id": "..."}`. A página não documenta códigos de erro: o adaptador trata **qualquer resposta que não seja 2xx** como falha. Fonte: resend.com/docs/api-reference/emails/send-email.
- **Plano gratuito do Resend:** 100 e-mails por dia, 3.000 por mês, 3 domínios, 30 dias de histórico. Fonte: resend.com/pricing.
- **`Idempotency-Key` não será usado:** a documentação só diz que a chave é única por pedido e vale 24 h; não diz o que acontece quando a mesma chave volta com outro conteúdo, e cada retentativa gera um token novo. Em vez de depender de um comportamento não documentado, aceito, no caso raro de um tempo-limite depois do envio, um e-mail repetido (cada link continua válido por si).
- **db-scheduler:** tarefa única que lança exceção é repetida depois de 5 minutos, sem limite padrão de tentativas; o limite se configura com `.onFailure(FailureHandler)` no `Tasks.oneTime(...)` (exemplos `MaxRetriesMain` e `ExponentialBackoffWithMaxRetriesMain` no repositório da biblioteca). O dado da tarefa usa a serialização Java padrão: por isso o dado é só um `UUID`. O spike do #6 prova `Tasks.oneTime` e `Tasks.recurring` como `@Bean` e `Scheduler.schedule(new TaskInstance<>(nome, id, dado), Instant)`. Quem executa confere no jar 16.12.0 os nomes exatos das classes de `FailureHandler` e de `Schedules.daily` antes de usar.
- **Já existe e será reaproveitado:** `CustomerRepository.insertIfAbsent` (`on conflict do nothing`, devolve 1 ou 0), `PasswordEncoder` Argon2id (`PasswordSecurityConfig`), `ApiExceptionHandler` (códigos de erro em `errors`), o padrão de `RestClient` com tempo-limite do `PasswordSecurityConfig` e o `MockRestServiceServer` do `PasswordSecurityConfigTest`.
- **`.tmp/` já está no `.gitignore`** (linha 9), então `.tmp/outbox/` fica fora do git.

## Decisões (aprovadas em 28/10/2026; os ajustes do Sérgio estão marcados)

1. **Prazo do link: 24 horas** (aprovado). Propriedade `app.email-confirmation.ttl`.
2. **Envio em segundo plano** (db-scheduler) com **5 tentativas, uma a cada 5 minutos**, depois a tarefa é descartada com erro no log, sem e-mail e sem token (aprovado). Registro isso no ADR-0005.
3. **Links do e-mail de aviso:** `/entrar` (#10) e `/recuperar-senha` (#12), telas que ainda não existem (aprovado). Os caminhos ficam combinados para os dois tickets.
4. **Remetente (ajuste):** `SapiensIA <nao-responda@sapiensia.app.br>`.
5. **E-mails em texto simples**, sem HTML.
6. **A página `/confirmar-email` pede a senha (ajuste, item 1a da #49).** Ela **não confirma sozinha**: mostra um campo de senha e um botão; só confirma se a senha bate com a da conta. O token vai **depois do `#`** na URL (`/confirmar-email#<token>`): o navegador nunca envia essa parte ao servidor, então o token não aparece em log de acesso nem em `Referer`. Senha errada **não gasta o link**: a pessoa pode tentar de novo.
7. **Link desconhecido, vencido ou já usado dá a mesma resposta** (`TOKEN_INVALID`) e **a mesma frase** na tela (ver textos); senha errada tem resposta própria (`PASSWORD_INCORRECT`), que só quem tem o link vê. Se quiser frases diferentes para "venceu" e "já foi usado", é preciso guardar os tokens usados até vencerem (mais uma coluna e mais código): me diga na revisão dos textos.
8. **Rota pública nova em `/api`:** `POST /api/customers/email-confirmations` precisa de `permitAll`; atualizo o **ADR-0004** (inclusive a linha "ignorado em silêncio", que deixa de ser verdade) e o **ADR-0005**, sem ADR novo (aprovado).
9. **E-mail local em arquivo (ajuste):** com `app.email.provider=file` (perfil `local` e job e2e do CI), cada e-mail vira `.tmp/outbox/<data-hora>-<destinatário>.txt`, na raiz do projeto (na worktree, a pasta `.tmp/` da própria worktree). Está no `.gitignore`. O arquivo contém o link com o token; fica só no seu computador.
10. **Resend, domínio e DNS ficam para o #7 (ajuste):** conta no Resend, `sapiensia.app.br` com SPF, DKIM e DMARC, e o segredo `RESEND_API_KEY` na plataforma são do ticket #7 (Homologação no ar). **O critério "domínio verificado" da #9 fica aberto e registrado na entrega.** Este ticket prova o contrato com o Resend por teste (servidor simulado) e o fluxo completo com o envio local.
11. **Pré-sequestro (ajuste, #49 item 1), três regras, cada uma com teste:**
    - **(a)** só confirma com a senha certa (decisão 6);
    - **(b)** cadastro repetido de e-mail **não confirmado** troca o hash da senha, apaga os links anteriores e envia um **novo e-mail de confirmação** (o mesmo texto do cadastro novo); a resposta continua `202` igual. Cadastro repetido de e-mail **já confirmado** envia o e-mail de aviso e não altera nada;
    - **(c)** conta não confirmada há mais de **7 dias** (contados de `created_at`) é apagada por uma tarefa diária do db-scheduler às 03:00 (`app.unconfirmed-customer.retention`, padrão `P7D`); os links dela somem junto (FK em cascata).
12. **5 senhas erradas (ajuste):** cada link guarda um contador de erros; a 5ª senha errada apaga o link e já responde `TOKEN_INVALID` (a tela mostra a frase de link vencido ou já usado); acertar antes disso confirma. Para tentar de novo, a pessoa se cadastra outra vez (se a conta ainda não foi confirmada, isso gera outro link).
13. **Envio escolhido por configuração (ajuste):** `app.email.provider` é `resend` (valor do `application-prod.yml`; exige `RESEND_API_KEY`, sem ela a aplicação não sobe) ou `file` (valor do `application-local.yml`; no job e2e do CI vem de `APP_EMAIL_PROVIDER=file`, **nunca uma chave falsa**). Sem valor nenhum nos testes, que usam o remetente falso. O CI grava os e-mails em `.tmp/outbox` do runner por um volume do Docker. Risco: `file` em produção por engano gravaria e-mails (com links) em disco, então a plataforma (#7) não pode definir `APP_EMAIL_PROVIDER`.

## Textos que o usuário vai ver (todos, para o seu OK)

Marca: **SapiensIA**. Remetente: `SapiensIA <nao-responda@sapiensia.app.br>`.

**E-mail de confirmação** (cadastro novo e cadastro repetido de e-mail ainda não confirmado)

Assunto:
> Confirme seu e-mail na SapiensIA

Corpo:
> Olá!
>
> Para concluir seu cadastro na SapiensIA, confirme seu e-mail pelo link abaixo. Você vai precisar digitar a senha que escolheu no cadastro. O link vale por 24 horas e só pode ser usado uma vez.
>
> {link}
>
> Se você se cadastrou mais de uma vez com este e-mail, vale a senha e o link do cadastro mais recente.
>
> Se você não criou uma conta na SapiensIA, ignore esta mensagem.

(`{link}` = `<endereço do site>/confirmar-email#<token>`.)

**E-mail de aviso** (cadastro com e-mail que já tem conta confirmada)

Assunto:
> Tentativa de cadastro na SapiensIA

Corpo:
> Olá!
>
> Alguém tentou criar uma conta na SapiensIA com este e-mail, que já tem cadastro.
>
> Se foi você, entre na sua conta: {endereço do site}/entrar
> Se esqueceu a senha, recupere-a: {endereço do site}/recuperar-senha
>
> Se não foi você, ignore esta mensagem. Nada foi alterado na sua conta.

**Página `/confirmar-email`**

| Onde | Texto |
|---|---|
| Título da página (h1) | Confirmação de e-mail |
| Instrução sob o título | Digite a senha que você escolheu no cadastro para confirmar seu e-mail. |
| Rótulo do campo | Senha |
| Botão | Confirmar e-mail |
| Botão enquanto envia | Confirmando… |
| Senha em branco (também o código `PASSWORD_REQUIRED`) | Informe a senha. |
| **Senha errada** (`PASSWORD_INCORRECT`; o link continua valendo) | Senha incorreta. Use a senha do seu cadastro mais recente. |
| **Link vencido, link já usado, link desconhecido, sem token na URL ou depois de 5 senhas erradas** (`TOKEN_INVALID`; o formulário some) | Este link venceu ou já foi usado. Se o seu e-mail ainda não foi confirmado, faça o cadastro de novo para receber outro link. |
| Erro inesperado (rede, 500) | Não foi possível confirmar agora. Tente novamente em instantes. |
| Sucesso (substitui o formulário) | E-mail confirmado. Agora você já pode entrar na sua conta. |

A tela de cadastro **não muda** ("Cadastro recebido. Verifique seu e-mail para continuar." continua igual em todos os casos).

## Global Constraints

- Código, nomes, commits e comentários em inglês; textos da tela e dos e-mails em português.
- Commits Conventional Commits, terminando com `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`. Nunca na `main`; push e PR só com o OK do Sérgio.
- Toda chamada ao backend passa por `apiFetch` (`frontend/src/lib/api.ts`).
- Controller e Repository package-private; DTOs como `record`; entidade JPA nunca sai do service; injeção por construtor.
- Teste de integração com `@Import(TestcontainersConfiguration.class)`, sem `@MockitoBean`, sem container novo. Classes de teste terminam em `Test`. Teste que prova requisito leva o ID no `@DisplayName` (ex.: `"SEC-007: ..."`).
- **Nenhum log traz e-mail completo, token, senha ou hash** (critério de aceite). Mensagens de exceção do adaptador também não: só o status HTTP.
- Variáveis novas vão para `.env.example` **sem valor** (`RESEND_API_KEY`, `APP_PUBLIC_BASE_URL`). O `application.yml` de teste precisa de todas as propriedades `app.*` que o código usa.
- Antes de commitar Java: `just fmt`. Saída longa: `just check-resumo`.
- Fora deste ticket: tela de login (#10), recuperação de senha (#12), limite de requisições e bloqueio por tentativas (#11, #42), conta e DNS do Resend (#7), exigir e-mail confirmado para comprar, limpeza de tokens vencidos de contas que continuam existindo, itens 2 e 3 da #49.

## Review Focus

Entradas que a spec não cita mas que acontecem (cada uma tem teste no task indicado):

1. O link é usado duas vezes (clique duplo no botão, antivírus que pré-carrega): a segunda vez dá `TOKEN_INVALID`, a conta continua confirmada, e o botão fica desabilitado enquanto envia (Tasks 2, 5 e 6).
2. Senha errada no link: responde `PASSWORD_INCORRECT` e **não gasta o link** até a 5ª senha errada (que apaga o link e responde `TOKEN_INVALID`); a senha certa antes disso confirma; senha vazia, ou com 129 caracteres, nunca dá `500` (Tasks 2, 5, 6 e 7).
3. Token vencido por um segundo, vazio, com 10 000 caracteres ou JSON quebrado: `400` com código, nunca `500` (Tasks 2 e 5).
4. O Resend fora do ar, devagar ou respondendo `429`/`500`: o cadastro responde `202` do mesmo jeito, a tarefa é repetida, e nenhum log traz e-mail ou token (Tasks 1 e 3).
5. Cadastros repetidos: `" Ana@Example.COM "` é o mesmo e-mail que `ana@example.com`; oito cadastros simultâneos do mesmo e-mail novo dão uma conta, nenhum `500` e só e-mails de confirmação; a conta que o atacante deixou parada nunca confirma com a senha dele depois que o dono se cadastra (Tasks 3 e 7).

---

### Task 1: Interface de e-mail, adaptador do Resend e e-mail local em arquivo

**Files:**
- Create (pacote `br.com.sergio.sapiensia.email`): `EmailSender.java` (interface pública), `EmailMessage.java` (`record`), `EmailDeliveryException.java`, `ResendEmailSender.java`, `FileEmailSender.java`
- Modify: `backend/src/main/resources/application.yml`, `application-local.yml`, `application-prod.yml`, `.env.example` (se existir; senão só documente no `application-prod.yml`)
- Test: `backend/src/test/java/br/com/sergio/sapiensia/email/ResendEmailSenderTest.java`, `FileEmailSenderTest.java`

**Interfaces:**
- Produces:
  - `public interface EmailSender { void send(EmailMessage message); }` — lança `EmailDeliveryException` (não verificada) em qualquer falha.
  - `public record EmailMessage(String to, String subject, String text)`
  - `public class EmailDeliveryException extends RuntimeException` — construtor `(String message)` e `(String message, Throwable cause)`; a mensagem **nunca** contém destinatário, corpo da resposta ou chave.
  - `ResendEmailSender(RestClient.Builder builder, String apiKey, String from)`, com `@ConditionalOnProperty(name = "app.email.provider", havingValue = "resend")`; o construtor lança `IllegalStateException("RESEND_API_KEY is required when app.email.provider=resend")` se a chave estiver em branco (a aplicação não sobe sem ela): `POST https://api.resend.com/emails`, `Authorization: Bearer <apiKey>`, corpo JSON `{"from", "to": [to], "subject", "text"}`, `RestClient` com tempo-limite de 2 s para conectar e 5 s para ler (mesmo padrão do `PasswordSecurityConfig`).
  - `FileEmailSender(Path outboxDir)`, com `@ConditionalOnProperty(name = "app.email.provider", havingValue = "file")`: grava `<yyyyMMdd-HHmmssSSS>-<to com caracteres fora de [a-z0-9@.-] trocados por _>.txt` com `To:`, `Subject:`, linha em branco e o texto.
  - Propriedades: `app.email.from` (padrão `SapiensIA <nao-responda@sapiensia.app.br>`), `app.email.provider` (`resend` em `application-prod.yml`, `file` em `application-local.yml`, **sem valor em mais nenhum lugar**; a variável de ambiente `APP_EMAIL_PROVIDER` sobrepõe), `app.email.resend.api-key: ${RESEND_API_KEY:}` (em `application-prod.yml`; em branco, só derruba a partida quando o provedor é `resend`), `app.email.outbox-dir` (padrão `../.tmp/outbox` no `application.yml`; a variável `APP_EMAIL_OUTBOX_DIR` sobrepõe, o que se confere rodando a imagem na Task 7).

- [ ] **Step 1: Escrever os testes que falham.**
  `ResendEmailSenderTest` (JUnit puro, `RestClient.Builder` ligado ao `MockRestServiceServer`, como o `PasswordSecurityConfigTest`):
  - `shouldPostJsonWithBearerKeyToResend`: espera `POST https://api.resend.com/emails`, `Authorization: Bearer re_test_key`, `Content-Type: application/json`, e corpo com `from`, `to` (lista de um item), `subject` e `text` exatamente como enviados; resposta `200 {"id":"abc"}`; `send` não lança.
  - `@DisplayName("SEC-007: falha do Resend vira EmailDeliveryException sem e-mail nem corpo na mensagem")`: para as respostas `500`, `422` e `429` (cada uma com o corpo `{"message":"invalid to ana@example.com"}`), `send` lança `EmailDeliveryException` cuja mensagem contém o status e **não** contém `ana@example.com`, o corpo nem `re_test_key`.
  - `shouldWrapConnectionFailureWithoutRecipient`: o servidor simulado lança `IOException`; vira `EmailDeliveryException` sem o destinatário na mensagem.
  - `shouldRefuseToStartWithoutApiKey`: construir `ResendEmailSender` com chave em branco lança `IllegalStateException` cuja mensagem cita `RESEND_API_KEY`.
  - `FileEmailSenderTest` (pasta temporária `@TempDir`): `send` cria um arquivo cujo conteúdo começa com `To: ana@example.com`, tem o assunto e o texto; o nome do arquivo não tem caracteres fora de `[A-Za-z0-9@._-]`.

- [ ] **Step 2: Rodar e ver falhar.** Run: `cd backend; .\mvnw.cmd test -Dtest="ResendEmailSenderTest,FileEmailSenderTest"`. Esperado: erro de compilação (as classes não existem).

- [ ] **Step 3: Implementar** as cinco classes e as propriedades acima. Em `ResendEmailSender`, capture `RestClientException` e converta; não registre nada em log (quem chama decide). Rode `just fmt`.

- [ ] **Step 4: Rodar e ver passar.** Run: `cd backend; .\mvnw.cmd test -Dtest="ResendEmailSenderTest,FileEmailSenderTest"`. Esperado: 0 falhas.

- [ ] **Step 5: Commit.** `feat: add e-mail sender interface with Resend and local file adapters`.

---

### Task 2: Tokens de confirmação e a confirmação com senha no service

**Files:**
- Create: `backend/src/main/resources/db/migration/V4__create_email_confirmation_tokens.sql` (número confirmado com a skill `nova-migration`)
- Create (pacote `customer`): `EmailConfirmationTokens.java` (`@Component` package-private, guarda, consulta e apaga tokens), `InvalidConfirmationTokenException.java` e `IncorrectConfirmationPasswordException.java` (públicas)
- Modify: `CustomerService.java` (novo método `confirmEmail`)
- Test: `backend/src/test/java/br/com/sergio/sapiensia/customer/EmailConfirmationTest.java`

**Interfaces:**
- Tabela `email_confirmation_tokens`: `id uuid` PK (`gen_random_uuid()`), `customer_id uuid not null` (FK para `customers(id)`, `on delete cascade`), `token_hash varchar(64) not null unique` (SHA-256 em hexadecimal), `expires_at timestamptz not null`, `failed_attempts smallint not null default 0`, `created_at timestamptz not null default now()`. Nada além disso (SEC-033).
- Produces:
  - `String EmailConfirmationTokens.issue(UUID customerId)`: gera 32 bytes com `SecureRandom`, codifica em Base64 URL sem preenchimento (43 caracteres), grava só o hash com `expires_at = now() + app.email-confirmation.ttl` (padrão `PT24H`, relógio do banco) e devolve o token em texto puro.
  - `void EmailConfirmationTokens.revokeAll(UUID customerId)`: apaga todos os tokens do cliente.
  - `public void CustomerService.confirmEmail(String token, String rawPassword)`: procura o token não vencido (junto com o `password_hash` do cliente); se não existe, lança `InvalidConfirmationTokenException`; se `passwordEncoder.matches(rawPassword, hash)` é falso, soma 1 em `failed_attempts` (um `update ... returning`, atômico): com menos de 5 erros lança `IncorrectConfirmationPasswordException` **sem apagar o token**; no 5º erro apaga o token e lança `InvalidConfirmationTokenException` (constante `MAX_WRONG_PASSWORDS = 5`); se a senha bate, apaga o token com um `delete ... returning` (atômico: dois pedidos simultâneos, só um vence; o perdedor lança `InvalidConfirmationTokenException`) e preenche `email_confirmed_at = now()` se ainda estiver vazio. O token é verificado antes da senha, e o Argon2 só roda para token válido.

- [ ] **Step 1: Escrever os testes que falham** em `EmailConfirmationTest` (`@SpringBootTest` + `@Import(TestcontainersConfiguration.class)`, `JdbcTemplate`; um cliente criado por `register` com e-mail e senha conhecidos, único por teste):
  - `@DisplayName("SEC-007: o token é aleatório e o banco guarda só o hash")`: dois `issue` para o mesmo cliente devolvem tokens diferentes, com 43 caracteres de `[A-Za-z0-9_-]`; a coluna `token_hash` tem 64 caracteres hexadecimais e a tabela inteira **não contém** nenhum dos tokens em texto puro.
  - `@DisplayName("SEC-007: o link confirma o e-mail uma única vez")`: `confirmEmail(token, senhaCerta)` preenche `email_confirmed_at`; a segunda chamada com o mesmo token lança `InvalidConfirmationTokenException`; `email_confirmed_at` não muda.
  - `@DisplayName("#49: sem a senha certa o e-mail não é confirmado, e o link continua valendo")`: `confirmEmail(token, "senha errada 123")` lança `IncorrectConfirmationPasswordException`; `email_confirmed_at` continua nulo; a linha do token continua na tabela; `confirmEmail(token, senhaCerta)` logo depois confirma.
  - `@DisplayName("SEC-007: token vencido é recusado")`: depois de `issue`, `update email_confirmation_tokens set expires_at = now() - interval '1 second'`; `confirmEmail(token, senhaCerta)` lança `InvalidConfirmationTokenException` e `email_confirmed_at` continua nulo.
  - `@DisplayName("SEC-007: token desconhecido, vazio ou enorme é recusado do mesmo jeito")`: `""`, `"x"` e uma string de 10 000 caracteres lançam `InvalidConfirmationTokenException` (nunca outra exceção).
  - `shouldAllowOnlyOneWinnerWhenSameTokenIsConfirmedConcurrently`: 8 threads com `CountDownLatch` chamam `confirmEmail(mesmoToken, senhaCerta)`; exatamente 1 conclui e 7 lançam `InvalidConfirmationTokenException`.
  - `@DisplayName("#49: depois de 5 senhas erradas o link deixa de valer")`: quatro `confirmEmail(token, errada)` lançam `IncorrectConfirmationPasswordException`; a quinta lança `InvalidConfirmationTokenException` e a linha do token some; `confirmEmail(token, senhaCerta)` logo depois também lança `InvalidConfirmationTokenException` e `email_confirmed_at` continua nulo.
  - `shouldConfirmWithCorrectPasswordAfterFourWrongOnes`: quatro senhas erradas e depois a certa confirmam.
  - `shouldCountWrongPasswordsPerLink`: 4 erros no token 1 não afetam o token 2 do mesmo cliente (que ainda aceita 4 erros e a senha certa).
  - `shouldRevokeAllTokensOfACustomer`: dois `issue` e um `revokeAll`; a tabela fica sem linhas desse cliente e os dois tokens dão `InvalidConfirmationTokenException`.
  - `shouldDeleteTokensWhenCustomerIsDeleted`: apagar o cliente apaga os tokens (FK em cascata).
  - `@DisplayName("SEC-033: a tabela de tokens só guarda o necessário")`: colunas em `information_schema` exatamente `id`, `customer_id`, `token_hash`, `expires_at`, `failed_attempts`, `created_at`.

- [ ] **Step 2: Rodar e ver falhar.** Run: `cd backend; .\mvnw.cmd test -Dtest=EmailConfirmationTest`. Esperado: erro de compilação.

- [ ] **Step 3: Criar a migration (skill `nova-migration`), `EmailConfirmationTokens`, as exceções e `confirmEmail`.** Use `JdbcClient`/`JdbcTemplate` ou queries nativas para a busca com `join` e para o `delete ... returning`; o hash é `MessageDigest.getInstance("SHA-256")`. Acrescente `app.email-confirmation.ttl` ao `application.yml` (e ao de teste). Rode `just fmt`.

- [ ] **Step 4: Rodar e ver passar.** Run: `cd backend; .\mvnw.cmd test -Dtest="EmailConfirmationTest,SapiensiaApplicationTests"`. Esperado: 0 falhas (o `contextLoads` prova que o app sobe só com as migrations).

- [ ] **Step 5: Commit.** `feat: add single-use e-mail confirmation tokens checked against the password`.

---

### Task 3: Tarefas de envio, ligação com o cadastro e troca de senha de conta não confirmada

**Files:**
- Create (pacote `customer`): `CustomerEmailTasks.java` (`@Configuration` com os dois `OneTimeTask<UUID>`), `CustomerEmailService.java` (`@Service` package-private: monta e envia as duas mensagens)
- Modify: `CustomerService.java` (agenda a tarefa; troca a senha de conta não confirmada), `CustomerRepository.java`, `backend/src/main/resources/application.yml` (`app.public-base-url`), `application-local.yml` (`app.public-base-url: http://localhost:5173`, conferir a porta do Vite em `frontend/vite.config.ts`), `application-prod.yml` (`app.public-base-url: ${APP_PUBLIC_BASE_URL}` sem padrão)
- Modify: `backend/src/test/java/br/com/sergio/sapiensia/TestcontainersConfiguration.java` (remetente falso)
- Test: `backend/src/test/java/br/com/sergio/sapiensia/customer/CustomerEmailTest.java`; ajustar `CustomerServiceTest` onde o comportamento do e-mail repetido mudou (conta não confirmada repetida agora **troca** a senha)

**Interfaces:**
- Consumes: `EmailSender`, `EmailMessage` (Task 1); `EmailConfirmationTokens.issue` e `revokeAll` (Task 2).
- Produces:
  - `Optional<UUID> CustomerRepository.replacePasswordIfUnconfirmed(String email, String passwordHash)`: `update customers set password_hash = :passwordHash where email = :email and email_confirmed_at is null returning id` (consulta nativa; devolve vazio se a conta não existe ou já está confirmada) e `Optional<UUID> findIdByEmail(String)`, `Optional<String> findEmailById(UUID)`.
  - Tarefas `customer-email-confirmation` e `customer-account-exists-notice`, ambas `OneTimeTask<UUID>` (o dado é o id do cliente), com `.onFailure(...)` de `app.email.max-attempts` tentativas (padrão 5) a cada `app.email.retry-delay` (padrão `PT5M`) e erro no log só com o nome da tarefa (sem e-mail, sem token).
  - `CustomerService.register` passa a agendar **exatamente uma** tarefa, depois de aplicar a regra: `insertIfAbsent == 1` → `customer-email-confirmation`; senão `replacePasswordIfUnconfirmed` com id → `emailConfirmationTokens.revokeAll(id)` e `customer-email-confirmation`; senão (conta confirmada) → `customer-account-exists-notice` com o id de `findIdByEmail`. Instância com `UUID.randomUUID().toString()` e `Instant.now()`. A senha continua sendo conferida contra vazamento e hasheada **antes** do banco, nos três caminhos (o tempo de resposta não revela o caminho). A troca da senha e a revogação dos links não são uma operação só, então `EmailConfirmationTokens.consumeAndConfirm` recebe o `password_hash` que `confirmEmail` acabou de verificar e só confirma se o hash da conta ainda for esse (`and password_hash = :verifiedHash` no `update customers`); se a senha mudou no meio do caminho, a confirmação falha com `InvalidConfirmationTokenException` e a conta não é confirmada. Sem isso, uma confirmação que passou pela checagem de senha poderia confirmar a conta com a senha de quem se cadastrou logo depois (o pré-sequestro da #49).
  - `CustomerEmailService.sendConfirmation(UUID customerId)`: carrega o e-mail; se o cliente não existe mais ou já está confirmado, não envia; senão envia a mensagem da tabela de textos com `{link}` = `<public-base-url>/confirmar-email#<token>`.
  - `CustomerEmailService.sendAccountExistsNotice(UUID customerId)`: envia a mensagem de aviso com `{endereço do site}` = `app.public-base-url`; o corpo não traz token, data de criação nem estado da conta.
  - Teste: bean `@Primary RecordingEmailSender` (em `TestcontainersConfiguration`) com `List<EmailMessage>` concorrente e `Optional<EmailMessage> awaitMessageTo(String to, Duration timeout)` (sondagem a cada 100 ms). Sem Awaitility e sem dependência nova. Valores só de teste (`db-scheduler.polling-interval=1s`, `app.email.retry-delay=PT1S`, `app.public-base-url=http://localhost:5173`) ficam na configuração de teste do projeto: confira se já existe um arquivo em `backend/src/test/resources`; se não, crie `application.properties` lá (um `.properties` soma-se ao `application.yml` principal; um `application.yml` de teste o substituiria). Não use `@SpringBootTest(properties=...)`: cada combinação cria outro contexto e outro container. O `DbSchedulerRestartTest` sobe a aplicação sem `TestcontainersConfiguration`, então passa a precisar de `--app.email.provider=file` e `--app.email.outbox-dir=<pasta temporária>` nos argumentos de `startApp`.

- [ ] **Step 1: Escrever os testes que falham** em `CustomerEmailTest` (`@SpringBootTest` + `@ExtendWith(OutputCaptureExtension.class)`; e-mail único por teste, porque o falso é compartilhado e as outras classes de teste também cadastram clientes):
  - `@DisplayName("SEC-007: cadastro novo recebe o e-mail com o link de confirmação")`: `register` e `awaitMessageTo(email, 15 s)` traz assunto "Confirme seu e-mail na SapiensIA" e um link `<base>/confirmar-email#<43 caracteres>`; `confirmEmail(<esse token>, senha)` confirma o cliente (fluxo inteiro, com o agendador de verdade).
  - `@DisplayName("SEC-007: e-mail repetido de conta confirmada recebe o aviso, sem token e sem dados da conta")`: cadastra, confirma, cadastra de novo; a nova mensagem tem o assunto "Tentativa de cadastro na SapiensIA", contém `<base>/entrar` e `<base>/recuperar-senha`, não contém `confirmar-email`, a senha digitada nem o hash; a senha e o `email_confirmed_at` da conta não mudam.
  - `@DisplayName("#49(b): cadastro repetido de e-mail não confirmado troca a senha e invalida o link anterior")`: cadastra com a senha A e recebe o link 1; cadastra de novo o mesmo e-mail com a senha B e recebe o link 2; `confirmEmail(token1, A)` e `confirmEmail(token1, B)` lançam `InvalidConfirmationTokenException`; `confirmEmail(token2, A)` lança `IncorrectConfirmationPasswordException`; `confirmEmail(token2, B)` confirma.
  - `@DisplayName("#49: o atacante que se cadastrou primeiro não consegue confirmar a conta da vítima")`: o "atacante" cadastra `vitima-<id>@example.com` com a senha X (e **não** recebe o link, que vai para a caixa da vítima); a "vítima" cadastra o mesmo e-mail com a senha Y e usa o link da mensagem recebida: `confirmEmail(link, X)` falha com `IncorrectConfirmationPasswordException`, `confirmEmail(link, Y)` confirma, e `password_hash` casa com Y e não com X.
  - `@DisplayName("SEC-007: cadastro novo e cadastro repetido geram um e-mail cada, nem mais nem menos")`: duas chamadas de `register` com o mesmo e-mail; depois de receber as duas mensagens, uma espera extra de 3 s não traz uma terceira.
  - `shouldSendToNormalizedAddress`: `register("  Ana-<id>@Example.COM ", ...)` entrega a `ana-<id>@example.com`.
  - `shouldNotSendConfirmationWhenEmailAlreadyConfirmed`: `sendConfirmation` de um cliente já confirmado não envia nada.
  - `@DisplayName("SEC-007: nenhum log traz o e-mail, o token ou a senha")`: depois do fluxo completo e de uma falha de envio provocada (sender que lança `EmailDeliveryException` uma vez), `CapturedOutput` não contém o e-mail, o token nem a senha.
  - `shouldRetryWhenSenderFails`: um remetente que falha na primeira tentativa e passa na segunda; a mensagem chega (com o `retry` curto configurado para o teste).
  - `shouldKeepOneAccountAndSendOnlyConfirmationsWhenSameNewEmailRegistersConcurrently`: 8 threads; 1 linha em `customers`, nenhuma exceção, 8 mensagens para o mesmo endereço, todas de confirmação, e `password_hash` casa com uma das 8 senhas.

- [ ] **Step 2: Rodar e ver falhar.** Run: `cd backend; .\mvnw.cmd test -Dtest=CustomerEmailTest`. Esperado: erro de compilação.

- [ ] **Step 3: Implementar** as classes e a ligação acima. As mensagens ficam em constantes/`String.formatted` dentro do `CustomerEmailService` (sem motor de template), com os textos exatos da tabela. Rode `just fmt`.

- [ ] **Step 4: Rodar e ver passar.** Run: `cd backend; .\mvnw.cmd test -Dtest="CustomerEmailTest,CustomerServiceTest,CustomerControllerTest"`. Esperado: 0 falhas.

- [ ] **Step 5: Commit.** `feat: send confirmation and account-exists e-mails and replace unconfirmed passwords`.

---

### Task 4: Limpeza diária de contas não confirmadas

**Files:**
- Create (pacote `customer`): `UnconfirmedCustomersCleanup.java` (`@Configuration` com o `RecurringTask<Void>` `delete-unconfirmed-customers`, diário às 03:00)
- Modify: `CustomerService.java` (`public int deleteUnconfirmedOlderThanRetention()`), `application.yml` (`app.unconfirmed-customer.retention: P7D`, e no de teste)
- Test: `backend/src/test/java/br/com/sergio/sapiensia/customer/UnconfirmedCustomersCleanupTest.java`

**Interfaces:**
- Produces: `deleteUnconfirmedOlderThanRetention()` executa `delete from customers where email_confirmed_at is null and created_at < now() - :retention` e devolve quantas linhas apagou (os tokens saem em cascata); a tarefa registra só essa contagem no log, sem e-mails.

- [ ] **Step 1: Escrever os testes que falham** (`@SpringBootTest`, `JdbcTemplate`):
  - `@DisplayName("#49(c): apaga conta não confirmada com mais de 7 dias, com os links dela")`: duas contas não confirmadas, uma com `created_at = now() - interval '8 days'` e uma com `6 days`, cada uma com um token; depois da limpeza só a de 6 dias continua (e o token dela); o retorno é 1 (os testes usam e-mails únicos e contam só as próprias contas).
  - `@DisplayName("#49(c): nunca apaga conta confirmada, por mais antiga que seja")`: conta confirmada com `created_at` de 30 dias continua.
  - `shouldRegisterTheDailyCleanupTaskInTheScheduler`: depois de subir o contexto existe uma linha em `scheduled_tasks` com `task_name = 'delete-unconfirmed-customers'`.
  - `shouldAllowTheSameEmailToRegisterAgainAfterCleanup`: depois da limpeza, `register` do mesmo e-mail cria uma conta nova (e envia a confirmação).

- [ ] **Step 2: Rodar e ver falhar.** Run: `cd backend; .\mvnw.cmd test -Dtest=UnconfirmedCustomersCleanupTest`. Esperado: erro de compilação.

- [ ] **Step 3: Implementar** o método e a tarefa recorrente (`Tasks.recurring("delete-unconfirmed-customers", Schedules.daily(LocalTime.of(3, 0)))`, conferindo o nome exato no jar 16.12.0). Rode `just fmt`.

- [ ] **Step 4: Rodar e ver passar.** Run: `cd backend; .\mvnw.cmd test -Dtest="UnconfirmedCustomersCleanupTest,DbSchedulerRestartTest"`. Esperado: 0 falhas.

- [ ] **Step 5: Commit.** `feat: delete customers whose e-mail stays unconfirmed for 7 days`.

---

### Task 5: Endpoint público de confirmação e ADRs

**Files:**
- Create: `backend/src/main/java/br/com/sergio/sapiensia/customer/ConfirmEmailRequest.java` (`record`)
- Modify: `CustomerController.java`, `web/ApiExceptionHandler.java`, `security/SecurityConfig.java:34-35`, `docs/adr/0004-identidade-e-mfa.md`, `docs/adr/0005-tarefas-em-segundo-plano-com-db-scheduler.md`
- Test: `backend/src/test/java/br/com/sergio/sapiensia/customer/EmailConfirmationControllerTest.java`

**Interfaces:**
- Consumes: `CustomerService.confirmEmail` e as duas exceções (Task 2).
- Produces: `POST /api/customers/email-confirmations` com `ConfirmEmailRequest(String token, String password)`:
  - `token`: `@NotBlank(message = "TOKEN_INVALID")`, `@Size(max = 64, message = "TOKEN_INVALID")`
  - `password`: `@NotBlank(message = "PASSWORD_REQUIRED")`, `@Size(max = 128, message = "PASSWORD_INCORRECT")`
  - `204 No Content` no sucesso; `400` em `ProblemDetail` com `errors.token = TOKEN_INVALID` (validação ou `InvalidConfirmationTokenException`) ou `errors.password = PASSWORD_INCORRECT` (`IncorrectConfirmationPasswordException`) ou `PASSWORD_REQUIRED`. Se token e senha estiverem ruins ao mesmo tempo, vale só `TOKEN_INVALID`.
  - `SecurityConfig` ganha `.requestMatchers(HttpMethod.POST, "/api/customers/email-confirmations").permitAll()` antes do `denyAll` de `/api/**`.

- [ ] **Step 1: Escrever os testes que falham** (`@SpringBootTest` + MockMvc com `springSecurity()`, como o `CustomerControllerTest`; POST com `.with(csrf())` salvo onde dito):
  - `@DisplayName("SEC-007: o token e a senha certos confirmam o e-mail e respondem 204")` (token emitido com `EmailConfirmationTokens.issue` para um cliente de teste).
  - `@DisplayName("#49(a): senha errada responde 400 PASSWORD_INCORRECT e o link continua valendo")`.
  - `@DisplayName("#49: a 5ª senha errada apaga o link e responde TOKEN_INVALID")`: quatro POSTs com senha errada dão `400 PASSWORD_INCORRECT`; o quinto dá `400 TOKEN_INVALID`; um sexto com a senha certa também dá `400 TOKEN_INVALID` e `email_confirmed_at` continua nulo.
  - `@DisplayName("SEC-007: usar o mesmo token de novo responde 400 TOKEN_INVALID")`.
  - `@DisplayName("SEC-007: token vencido, desconhecido e ausente respondem igual")`: os três dão `400` com o mesmo corpo `errors.token == "TOKEN_INVALID"`; também `{}`, `{"token":"","password":"x"}`, token com 65 caracteres e JSON quebrado (nunca `500`).
  - `shouldRejectBlankAndTooLongPassword`: senha `""` → `errors.password == "PASSWORD_REQUIRED"`; senha com 129 caracteres → `PASSWORD_INCORRECT`; nenhum dos dois gasta o link.
  - `@DisplayName("a POST sem token CSRF continua recusado")` → `403`.
  - `shouldKeepOtherApiRoutesDenied`: o `WebSecurityTest` existente continua passando (resto de `/api` negado).

- [ ] **Step 2: Rodar e ver falhar.** Run: `cd backend; .\mvnw.cmd test -Dtest=EmailConfirmationControllerTest`. Esperado: `403`/`404` (rota ainda negada).

- [ ] **Step 3: Implementar** o endpoint, o tratamento das duas exceções no `ApiExceptionHandler` e a regra no `SecurityConfig`. Rode `just fmt`.

- [ ] **Step 4: Atualizar os ADRs.** ADR-0004, em "Consequences": trocar a linha "…é ignorado em silêncio…" por: cadastro com e-mail que já tem conta responde igual a um novo (HTTP 202, mesma mensagem); se a conta já está confirmada, o titular recebe um aviso com os links para entrar e recuperar a senha; se **não** está confirmada, o novo cadastro troca a senha, invalida os links anteriores e envia um novo link de confirmação (defesa contra pré-sequestro de conta). Acrescentar: o link de confirmação (aleatório de 256 bits, só o hash no banco, uso único, 24 h, token depois do `#` da URL), a confirmação só com a senha do cadastro (senha errada não gasta o link, mas a 5ª senha errada apaga o link), a mesma resposta para link inválido/vencido/usado, a limpeza diária das contas não confirmadas há mais de 7 dias, a rota pública `POST /api/customers/email-confirmations` liberada no `SecurityConfig` e o Resend como provedor de e-mail atrás da interface `EmailSender`. ADR-0005, em "Consequences": a primeira tarefa real é o envio de e-mails, com 5 tentativas a cada 5 minutos e depois descartada com erro no log, mais a tarefa diária de limpeza.

- [ ] **Step 5: Rodar e ver passar.** Run: `cd backend; .\mvnw.cmd test -Dtest="EmailConfirmationControllerTest,WebSecurityTest,CustomerControllerTest"`. Esperado: 0 falhas.

- [ ] **Step 6: Commit.** `feat: add public e-mail confirmation endpoint`.

---

### Task 6: Página de confirmação no frontend

**Files:**
- Create: `frontend/src/features/customer/ConfirmEmailPage.tsx`
- Modify: `frontend/src/features/customer/schemas.ts` (schema Zod `confirmEmailSchema` e textos dos novos códigos), `api.ts` (`confirmEmail`), `hooks.ts` (`useConfirmEmail`), `frontend/src/App.tsx` (rota `/confirmar-email`)
- Test: `frontend/src/features/customer/ConfirmEmailPage.test.tsx`

**Interfaces:**
- Consumes: `POST /api/customers/email-confirmations` (Task 5), `apiFetch`, o `QueryClientProvider`, o roteador e os componentes `Input`/`Label`/`Button` do #8.
- Produces: `confirmEmail(input: { token: string; password: string }): Promise<void>` (`apiFetch` com `method: "POST"`; `204` resolve; `400` lança `ConfirmEmailError` com `fieldErrors: Record<string, string>`; outro status lança `Error`); `useConfirmEmail()` (`useMutation`); `ConfirmEmailPage` (export default) lê o token de `window.location.hash` (sem o `#`) e **não chama a API ao abrir**. Campo de senha (`type="password"`, `autoComplete="current-password"`), botão desabilitado e com "Confirmando…" enquanto envia, erro de campo com `role="alert"`, sucesso com `role="status"`. `TOKEN_INVALID` (ou ausência de token no `#`) troca o formulário pela frase de link inválido. `PASSWORD_INCORRECT` mantém o formulário e mostra a frase de senha errada junto ao campo. Os textos são os da tabela.

- [ ] **Step 1: Escrever os testes que falham** (Vitest + Testing Library + `userEvent`; `fetch` simulado com `vi.spyOn(globalThis, "fetch")`; `window.location.hash` definido antes de renderizar; renderizar dentro de `MemoryRouter` e `QueryClientProvider`):
  - `não chama a API ao abrir e mostra o formulário`: `fetch` não foi chamado; aparecem o h1 "Confirmação de e-mail", a instrução, o campo "Senha" (`type="password"`) e o botão "Confirmar e-mail".
  - `não envia com a senha em branco`: clica o botão; aparece "Informe a senha." e `fetch` não foi chamado.
  - `confirma e mostra o sucesso`: `fetch` devolve `204`; o corpo enviado é `{"token":"abc","password":"..."}` para `POST /api/customers/email-confirmations`; aparece "E-mail confirmado. Agora você já pode entrar na sua conta." e o formulário some.
  - `#49: mostra senha incorreta e deixa tentar de novo`: `fetch` devolve `400` com `{"errors":{"password":"PASSWORD_INCORRECT"}}`; aparece "Senha incorreta. Use a senha do seu cadastro mais recente."; o formulário continua e uma segunda tentativa (com `204`) mostra o sucesso.
  - `SEC-007: mostra link inválido quando a API recusa o token`: `400` com `{"errors":{"token":"TOKEN_INVALID"}}`; aparece "Este link venceu ou já foi usado. Se o seu e-mail ainda não foi confirmado, faça o cadastro de novo para receber outro link." e o formulário some.
  - `mostra link inválido sem formulário e sem chamar a API quando não há token na URL`.
  - `mostra erro amigável quando a API falha`: `500` → "Não foi possível confirmar agora. Tente novamente em instantes."
  - `desabilita o botão e mostra "Confirmando…" enquanto envia`; um segundo clique não faz uma segunda chamada (`fetch` chamado 1 vez).

- [ ] **Step 2: Rodar e ver falhar.** Run: `cd frontend; pnpm exec vitest run src/features/customer`. Esperado: falha por falta do arquivo.

- [ ] **Step 3: Implementar** `confirmEmail`, `useConfirmEmail`, o schema, `ConfirmEmailPage` e a rota em `App.tsx`. Rode `just fmt`.

- [ ] **Step 4: Rodar e ver passar.** Run: `cd frontend; pnpm exec vitest run`. Esperado: todos passam (os do #8 continuam).

- [ ] **Step 5: Commit.** `feat: add e-mail confirmation page`.

---

### Task 7: Fluxo de ponta a ponta, verificações e entrega

**Files:**
- Create: `frontend/e2e/email-confirmation.e2e.ts`

- [ ] **Step 1: Escrever o teste e2e** (API e banco de verdade, perfil `local`, sem `page.route` e sem mocks; e-mail único por execução; lê a pasta `../.tmp/outbox` do projeto, espera o arquivo do destinatário por até 15 s e extrai o link com uma expressão regular):
  - `SEC-007: o Visitante cadastra, abre o link do e-mail e confirma com a senha`: cadastra por `/cadastro`; acha o arquivo com o assunto "Confirme seu e-mail na SapiensIA"; abre o link; digita uma senha errada e vê "Senha incorreta. Use a senha do seu cadastro mais recente."; digita a certa e vê "E-mail confirmado. Agora você já pode entrar na sua conta."; abre o **mesmo link** de novo e vê a frase de link que venceu ou já foi usado.
  - `#49: o segundo cadastro do mesmo e-mail não confirmado vale; o link e a senha do primeiro não`: cadastra o mesmo e-mail duas vezes com senhas A e B (a tela mostra "Cadastro recebido. Verifique seu e-mail para continuar." nas duas); abre o link do **primeiro** e-mail e vê a frase de link inválido; abre o link do segundo, a senha A dá "Senha incorreta…" e a B confirma.
  - `SEC-007: cadastro com e-mail de conta confirmada mostra a mesma mensagem e envia o aviso`: depois de confirmar, cadastra de novo; a tela mostra a mesma mensagem de sucesso e aparece um arquivo com o assunto "Tentativa de cadastro na SapiensIA", sem `confirmar-email`.
  - `#49: cinco senhas erradas apagam o link`: cadastra, abre o link, erra a senha 4 vezes (vê "Senha incorreta…" a cada uma) e na 5ª vê a frase de link que venceu ou já foi usado; abrir o link de novo e digitar a senha certa também mostra essa frase.

- [ ] **Step 2: Subir o sistema de verdade e rodar.** Na pasta principal: `just db`. Na worktree: `just dev-back`, `just dev-front` e `just e2e`. Esperado: os testes novos e os do #8 passam. Conferir que `.tmp/outbox/` está fora do `git status`.

- [ ] **Step 3: Verificar a tela** `/confirmar-email` com `/verificar-tela` (desktop ~1280 px, celular ~390 px, teclado), em todos os estados da tabela de textos.

- [ ] **Step 4: CI e imagem (ajuste do Sérgio).**
  - (a) Em `.github/workflows/ci.yml`, no passo "Start the system" do job `e2e`: antes do `docker run`, `mkdir -p .tmp/outbox && chmod 777 .tmp/outbox` (o usuário `app` do contêiner precisa gravar na pasta do runner); no `docker run` acrescentar `-v "$PWD/.tmp/outbox:/outbox" -e APP_EMAIL_PROVIDER=file -e APP_EMAIL_OUTBOX_DIR=/outbox -e APP_PUBLIC_BASE_URL=http://localhost:8080`. **Nenhuma `RESEND_API_KEY`, nem falsa, no CI.** O Playwright roda em `frontend/` e lê `../.tmp/outbox`, a mesma pasta.
  - (b) Rodar a imagem localmente de dois jeitos e mostrar o resultado: sem `APP_EMAIL_PROVIDER` e sem `RESEND_API_KEY`, ela **não sobe** e o log cita `RESEND_API_KEY` (sem valor nenhum no log); com `APP_EMAIL_PROVIDER=file`, sobe e grava na pasta montada (confirma também que `APP_EMAIL_OUTBOX_DIR` é lida).
  - (c) `just imagem` (build da imagem) e uma **simulação local do job e2e**: banco em `host.docker.internal:5433`, imagem numa porta livre, `docker run` com as mesmas variáveis do CI, `E2E_BASE_URL` apontando para ela e `pnpm exec playwright test`. Se a porta 8080 estiver ocupada por outro projeto, usar outra e não parar o outro projeto.
  - (d) Validar a sintaxe do `ci.yml`. O CI de verdade só roda depois do push (que precisa do seu OK): na entrega, dizer que o CI foi **simulado localmente, não executado**.

- [ ] **Step 5: Critério do domínio (aberto).** Não há verificação de DNS neste ticket (decisão 10). Na entrega e na PR, escrever que o critério "domínio sapiensia.app.br com SPF, DKIM e DMARC verificados" **continua aberto e foi transferido para o #7**; comentar na #7 com o que ela precisa fazer (conta no Resend, domínio, `RESEND_API_KEY` e `APP_PUBLIC_BASE_URL` obrigatórias em produção, **sem** definir `APP_EMAIL_PROVIDER` na plataforma, e um envio real de teste).

- [ ] **Step 6: `just check-resumo`** e depois a skill `verificar-entrega`, mostrando a tabela dela.

- [ ] **Step 7: Commit** (`test: add e-mail confirmation e2e`) e, com o seu OK, push e PR com `Closes #9` na primeira linha, citando SEC-007, SEC-002, SEC-033, ADR-0004 e ADR-0005, e dizendo que o item 1 da #49 foi resolvido aqui (os itens 2 e 3 continuam abertos; depois do merge comento na #49, sem fechá-la).

## Riscos

- **Cota do Resend (100 por dia):** sem limite de requisições no cadastro (#11, #42), alguém pode esgotar a cota e atrasar as confirmações de clientes de verdade. Registro como risco; não é tratado aqui.
- **O aviso de "e-mail repetido" e o novo e-mail de confirmação do repetido podem encher a caixa de outra pessoa** (cada tentativa gera um e-mail para o endereço da vítima). É inerente ao requisito; a mitigação é o limite de requisições do #42/#11.
- **Um atacante pode trocar a senha pendente de uma conta não confirmada** repetidamente e invalidar o link da vítima (aborrecimento, não invasão: ele nunca recebe o link, e sem o link ninguém confirma). A vítima recupera cadastrando de novo. Mitigação real: #11/#42.
- **Chutar a senha com um link válido:** cada link aceita no máximo 5 senhas erradas e um link novo só vem de um novo cadastro, que troca a senha da conta. Risco baixo.
- **Conta apagada em 7 dias:** quem cadastrou, não confirmou e voltou no dia 8 precisa se cadastrar de novo (o link já vence em 24 h; o cadastro repetido gera outro enquanto a conta existir).
- **O aviso de conta já existente não tem links** (as telas /entrar e /recuperar-senha só existem a partir dos tickets #10 e #12; o aviso pode ganhá-los lá).
- **Entregabilidade real não é provada aqui** (decisão 10): os testes provam o contrato com o Resend e o fluxo local, não que o e-mail chega na caixa de entrada. O #7 faz o envio real.
- **Tokens vencidos de contas que continuam existindo ficam na tabela** (sem limpeza); volume pequeno, e as contas não confirmadas somem em 7 dias com os tokens delas.

## Ajustes depois do code-review

- A conta não confirmada que se cadastra de novo reinicia os 7 dias de retenção (`created_at = now()`), para a limpeza não apagá-la logo depois de um link novo.
- E-mail de aviso "Tentativa de cadastro" sem links (as telas /entrar e /recuperar-senha não existem): "Se foi você, não precisa fazer nada: use a conta que você já tem."
- A 5ª senha errada responde `TOKEN_LOCKED` (o link já foi apagado); depois disso o link é `TOKEN_INVALID`. Texto da página: "Você errou a senha 5 vezes e, por segurança, este link foi cancelado. Faça o cadastro de novo para receber outro link." (o trecho "Faça o cadastro de novo" é link para /cadastro).
- Fora deste ticket (issue de pendências): Idempotency-Key e não repetir erro 4xx no Resend, limpeza de tokens vencidos, ordem entre revogar e tarefa antiga, agendamento fora da transação, nome de arquivo do outbox.
