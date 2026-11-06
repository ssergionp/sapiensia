# MFA (TOTP) do Administrador (#15) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** APROVADO pelo Sérgio em 06/11/2026 com as decisões e condições da seção final. Backend: executar com subagent-driven-development (Tasks 1 a 5) e parar para ele revisar os diffs críticos. Telas e e2e (Tasks 6 e 7): só depois de ele aprovar os textos da seção "Textos que o usuário vê". Sem push, PR nem merge sem OK expresso.

**Goal:** O Administrador só ganha a sessão do painel depois da senha **e** de um código TOTP válido. No primeiro acesso, ele é obrigado a ativar o MFA com um aplicativo autenticador antes de entrar. A trava `app.admin.panel-login-enabled` sai.

**Architecture:**
- O login fica em duas etapas, sobre o mecanismo de MFA do Spring Security 7.1.2 (`FactorGrantedAuthority`, `AllRequiredFactorsAuthorizationManager`, `RequiredFactor.validDuration`). Esse mecanismo existe no jar, conferido com `javap`, mas não traz um fator TOTP.
- A senha certa no `POST /api/admin/sessions`, que continua no `PasswordLogin` compartilhado, grava na sessão um `PendingAdministrator` só com o fator `FACTOR_PASSWORD`, sem nenhuma capacidade.
- O código TOTP certo troca a sessão por um `AdministratorPrincipal` com `FACTOR_PASSWORD` + `FACTOR_TOTP` + as capacidades, com novo ID de sessão e novo token CSRF.
- Cada rota do painel exige a capacidade **e** os dois fatores. As rotas do MFA aceitam só o `PendingAdministrator` com a senha verificada há no máximo 5 minutos.
- O segredo TOTP fica no banco cifrado com AES-256-GCM (JDK). A chave vem do segredo `APP_MFA_ENCRYPTION_KEY`, e o ID do Administrador entra como dado associado (AAD).
- Código reusado: um único `UPDATE` condicional sobre `totp_last_step`. Força bruta: o mesmo `LoginAttemptLimiter`, com a chave `admin-mfa:<id>`.

**Tech Stack:** Spring Boot 4.1.2 / Spring Security 7.1.2, JdbcClient, Flyway, `javax.crypto` (HMAC-SHA1, AES-GCM), React + TanStack Query + React Hook Form + Zod, Vitest, Playwright. Dependência nova: só `qrcode.react` 4.2.0 no frontend.

**Spec:** `docs/spec/sapiensia-spec.md` (seções 3 e 6.1: SEC-001, SEC-006, SEC-008; SEC-014, SEC-018, SEC-036, SEC-067); `docs/adr/0004-identidade-e-mfa.md`; issue #15 (pai #5); irmãs #16 (códigos de recuperação e auditoria) e #22 (reautenticação para trocar o PDF).

## IDs cobertos

| ID | Como | Onde |
|---|---|---|
| SEC-001 | MFA (TOTP) obrigatório para o Administrador, já no primeiro acesso. Nenhuma rota do painel abre sem os dois fatores. Passkey continua como melhoria futura (ADR-0004) | Tasks 3–6 |
| SEC-014 | Limite de tentativas do código por conta (`admin-mfa:<id>`) e por IP (a mesma cota do login) | Task 4 |
| SEC-036 | Criptografia de campo para o segredo TOTP (AES-256-GCM, chave fora do banco e do git) | Task 2 |
| SEC-067 | Chave de cifragem só como segredo da plataforma ou gerada na hora no CI; nunca em log | Tasks 2 e 7 |
| SEC-018 | Erros do MFA sem detalhe: código errado, vencido e reusado recebem a mesma resposta | Task 4 |
| SEC-006 | Novo ID de sessão e novo token CSRF ao concluir o MFA; o `SessionVersionFilter` passa a cobrir também a sessão pendente | Tasks 3 e 4 |
| SEC-008 | **Não entregue** (é o #22). O plano só deixa o caminho pronto: `FACTOR_TOTP` guarda `issuedAt`, e o #22 pode exigir `RequiredFactor.validDuration` na rota da troca do PDF | — |
| SEC-065 | **Não entregue** (auditoria é o #16) | — |
| ADR-0004 | Atualizado: trava removida, fluxo em duas etapas, chave de cifragem, limites do código, recuperação de acesso decidida | Task 7 |

## Global Constraints

- TOTP conforme o RFC 6238: HMAC-SHA1, 6 dígitos, passo de 30 s, segredo de 20 bytes (160 bits, recomendado pelo RFC 4226), codificado em Base32 sem `=` na URI.
- Tolerância de relógio: passos `t-1`, `t` e `t+1`. Um código só vale se o passo dele for **maior** que o último passo aceito (`totp_last_step`).
- URI de inscrição: `otpauth://totp/SapiensIA:<e-mail>?secret=<BASE32>&issuer=SapiensIA&algorithm=SHA1&digits=6&period=30`.
- Etapa pendente: 5 minutos depois da senha certa (`app.admin.mfa-pending-ttl: PT5M`).
- Limite do código: os mesmos números do login (5 erros em 15 min bloqueiam por 15 min; `429` com `Retry-After`), com chave `admin-mfa:<uuid do Administrador>`. A cota por IP é a mesma do login.
- Chave de cifragem: `app.mfa.encryption-key: ${APP_MFA_ENCRYPTION_KEY}`, Base64 de exatamente 32 bytes. Vazia ou inválida impede a subida **em qualquer perfil**.
- Formato gravado: `v1:` + Base64(nonce de 12 bytes ‖ texto cifrado ‖ tag de 16 bytes). AAD = `administrator-totp:<uuid>`.
- Nunca em log, resposta de erro, relatório ou trace: o segredo, a URI `otpauth`, o código digitado e a chave. Os `record` de requisição têm `toString()` redigido.
- Textos do usuário: só os da lista fechada abaixo. Os textos iguais aos do login usam as constantes de `features/session/messages.ts`.

## Dependências (decisão do Sérgio)

### TOTP no backend

| | (a) Biblioteca | (b) RFC 6238 com a JDK |
|---|---|---|
| Candidata | `com.github.bastiaanjansen:otp-java` **2.1.0**, publicada em **23/12/2024** (Maven Central, ~23 meses). Alternativas mais paradas: `com.eatthepath:java-otp` 0.4.0 (31/07/2022), sem dependências; `dev.samstevens.totp:totp` 1.7.1 (05/11/2020), abandonada | `Mac.getInstance("HmacSHA1")` da JDK. O Base32 usa `org.bouncycastle.util.encoders.Base32`, que **já está no pom** (`bcprov-jdk18on` 1.86, usado pelo Argon2; conferi a classe no jar) |
| Prós | Código revisado por terceiros; menos linhas nossas | Zero dependência nova; ~40 linhas; provado com os vetores do Apêndice B do RFC 6238; controle total da janela e da comparação em tempo constante |
| Contras | Traz `commons-codec` 1.15 (de 2020; o Spring Boot gerencia a versão). Mais uma peça na cadeia de fornecimento (SEC-068). Nenhuma candidata tem lançamento recente. **Não resolve as partes arriscadas**: reuso, atomicidade, cifragem e limite continuam sendo nossas | Código de cripto nosso (o HMAC é da JDK; o nosso é a truncagem dinâmica e a janela). Diverge da letra da issue ("biblioteca de TOTP mantida"), mas o ADR-0004 já previa isso: "usando uma biblioteca mantida para o cálculo do TOTP, **se houver uma adequada**" |

**Recomendação: (b).** O cálculo do TOTP é pequeno e totalmente coberto pelos vetores oficiais do RFC. Nenhuma biblioteca disponível é mantida de forma ativa, e todo o risco real (reuso, corrida, cifragem, força bruta) fica no nosso código de qualquer jeito. O ADR-0004 recebe uma linha explicando a escolha.

### QR code da inscrição (frontend)

| | (a) Biblioteca | (b) Só a chave manual + link `otpauth://` |
|---|---|---|
| Candidata | `qrcode.react` **4.2.0**, publicada em **11/12/2024**, sem dependências, peer `react ^19`. Desenha um `<svg>` no navegador (cabe na CSP atual), então o segredo não sai para nenhum serviço | Mostra a chave em grupos de 4 caracteres e um link que, no celular, abre o aplicativo autenticador |
| Prós | Inscrição em segundos com o celular; é o fluxo que os aplicativos esperam | Zero dependência nova |
| Contras | Mais uma dependência de produção (pequena) | Digitar 32 caracteres no celular é chato e propenso a erro. O link `otpauth://` não funciona no navegador do computador, que é onde o painel é usado |

**Recomendação: (a), `qrcode.react` 4.2.0**, com a chave manual sempre visível como alternativa (acessibilidade e quem não consegue ler o QR). Alternativa sem dependência: gerar o QR em SVG no backend com o ZXing. Eu não recomendo: é dependência do mesmo jeito, só que maior.

## Arquivos

**Backend: criar**
- `security/Totp.java`: RFC 6238. `static String code(byte[] secret, long step, int digits)`, `static OptionalLong matchingStep(byte[] secret, String code, Instant now)` (testa `t-1`, `t` e `t+1` sem sair cedo, compara com `MessageDigest.isEqual`), `static byte[] newSecret()` (20 bytes de `SecureRandom`), `static String base32(byte[])`.
- `security/SecretCipher.java`: `@Component`. `String encrypt(byte[] plain, String aad)`, `byte[] decrypt(String stored, String aad)`. AES/GCM/NoPadding, nonce aleatório de 12 bytes.
- `security/MfaProperties.java`: `@ConfigurationProperties("app.mfa") record MfaProperties(@NotBlank String encryptionKey)`, que confere os 32 bytes na subida.
- `admin/PendingAdministrator.java`: `record PendingAdministrator(UUID id, String email, int sessionVersion, boolean mfaEnrolled) implements UserDetails`. Autoridades: só `FactorGrantedAuthority` `FACTOR_PASSWORD` com `issuedAt`. `toString()` sem o e-mail.
- `admin/AdministratorMfa.java`: `@Service`. `Optional<Enrollment> startEnrollment(PendingAdministrator)`, `Verdict confirmEnrollment(PendingAdministrator, String code)`, `Verdict verify(PendingAdministrator, String code)`. `record Enrollment(String secret, String otpauthUri)` com `toString()` redigido; `enum Verdict { ACCEPTED, REJECTED }`.
- `admin/AdminMfaController.java`: as quatro rotas do MFA (tabela no Risco a).
- `admin/MfaCodeRequest.java`: `record MfaCodeRequest(String code)`, sem anotação de validação (código malformado = código errado) e com `toString()` redigido.
- `db/migration/V9__add_administrator_totp.sql` (pela skill `nova-migration`).

**Backend: alterar**
- `customer/PasswordLogin.java`: extrair a parte final do sucesso (novo ID de sessão, novo token CSRF, `saveContext`) para um método público `startSession(UserDetails principal, Authentication authentication, SessionAuthenticationStrategy strategy, HttpServletRequest, HttpServletResponse)`, chamado pelo `login` e pela conclusão do MFA. O login do Cliente não muda de comportamento.
- `customer/LoginAttemptLimiter.java`: a classe, `Admission`, `admit`, `recordSuccess` e `release` passam a ser `public`, para o MFA usar os mesmos contadores e o mesmo balde por IP.
- `admin/AdminSessionController.java`: sai a trava e o 404. `accept()` devolve um `PendingAdministrator`.
- `admin/AdministratorUserDetailsService.java`: a etapa da senha deixa de montar capacidades.
- `admin/AdministratorPrincipal.java`: as autoridades passam a ser as capacidades + `FACTOR_PASSWORD` + `FACTOR_TOTP` (com `issuedAt`).
- `admin/Administrators.java`: consultas novas (ver Task 3).
- `security/SecurityConfig.java`: regras das rotas do MFA e do painel (capacidade **e** os dois fatores), mais um `AccessDeniedHandler` que responde `401` quando o principal é um `PendingAdministrator`.
- `customer/SessionVersionFilter.java`: confere também o `PendingAdministrator`.
- `application.yml`, `application-local.yml`, `application-prod.yml`, `src/test/resources/application.properties`: sai `app.admin.panel-login-enabled`; entram `app.mfa.encryption-key` e `app.admin.mfa-pending-ttl`.

**Backend: testes**
- Criar: `security/TotpTest`, `security/SecretCipherTest`, `security/MfaPropertiesTest`, `admin/AdminMfaEnrollmentTest`, `admin/AdminMfaLoginTest`, `admin/AdminMfaBypassTest`, `admin/AdminMfaConcurrencyTest`, `admin/AdminMfaLogTest`.
- Alterar: `AdminTestSupport` (o login completo passa a incluir o MFA, com o segredo de teste), `AdminSessionTest`, `AdminSharedIpLimitTest`, `AdminCustomerKeyCollisionTest`, `AdminRouteDenyByDefaultTest`, `SessionVersionFilterTest`.
- Apagar: `AdminPanelLoginDisabledTest` (a trava deixa de existir).

**Frontend**
- Criar: `features/admin/AdminMfaPage.tsx` + `AdminMfaPage.test.tsx` (inscrição e verificação na mesma página, conforme o `GET`).
- Alterar: `features/admin/api.ts`, `hooks.ts`, `schemas.ts`; `AdminLoginPage.tsx` (sucesso navega para `/painel/verificacao`) + teste; `App.tsx` (rota `/painel/verificacao`).
- e2e: alterar `e2e/admin-panel.e2e.ts`; criar `e2e/totp.ts` (gera o código com `node:crypto`, sem dependência).

**Outros**
- `.github/workflows/ci.yml`: sai `-e APP_ADMIN_PANEL_LOGIN_ENABLED=true` e o comentário dele; entra `-e APP_MFA_ENCRYPTION_KEY="$(openssl rand -base64 32)"`.
- `.env.example`: sai a linha da trava; entra `APP_MFA_ENCRYPTION_KEY=` (sem valor).
- `docs/adr/0004-identidade-e-mfa.md`: atualizado (Task 7).

## Migration

`V9__add_administrator_totp.sql`:

```sql
alter table administrators
  add column totp_secret varchar(255),
  add column totp_enabled_at timestamp with time zone,
  add column totp_last_step bigint;
```

- `totp_secret` preenchido com `totp_enabled_at` nulo significa inscrição começada e não confirmada. Um novo início sobrescreve o segredo, mas só enquanto `totp_enabled_at is null`.
- As colunas ficam na própria linha de `administrators`, não numa tabela `MfaCredential` (seção 8 da spec). É a mesma divergência já aceita no ADR-0004 para `User`/`Role`: um só fator por conta na Fase 1. Passkey ou mais fatores criariam a tabela quando chegarem. Os códigos de recuperação (#16) terão a tabela deles.
- Apagar a linha (procedimento manual do ADR-0004) leva o MFA junto, o que serve de base para o Risco g.

## Ajustes de desenho aprovados em 06/11/2026 (valem sobre o que está acima)

- `PendingAdministrator(UUID id, String email, int sessionVersion, Instant passwordVerifiedAt)` (sem `mfaEnrolled`: o estado do MFA é sempre lido do banco). Autoridade: só `FACTOR_PASSWORD`. `toString()` sem e-mail.
- `AdministratorPrincipal` ganha `Instant passwordVerifiedAt, Instant totpVerifiedAt`; autoridades = capacidades + `FACTOR_PASSWORD` + `FACTOR_TOTP`.
- Rotas do painel: `hasAuthority(PANEL_ACCESS)` **e** os dois fatores (`AuthorizationManagers.allOf` com `AllRequiredFactorsAuthorizationManager`; o implementador confere a API com `javap` no jar 7.1.2). Rotas do MFA: um `AuthorizationManager` próprio (`AdminMfaAccess`) que só aceita `PendingAdministrator` com `passwordVerifiedAt + app.admin.mfa-pending-ttl` no futuro (relógio = bean `Clock`).
- `AccessDeniedHandler`: principal `PendingAdministrator` → `401`; qualquer outro → `403` (o de hoje).
- `PasswordLogin` ganha `public void startSession(UserDetails principal, Authentication forStrategy, SessionAuthenticationStrategy strategy, HttpServletRequest, HttpServletResponse)` (novo ID de sessão, novo CSRF, `saveContext`); `login` passa a chamá-lo. `LoginAttemptLimiter` (classe, `Admission`, `admit`, `recordSuccess`, `release`) e `ClientIpResolver` (classe, `resolve`) viram `public`. O construtor do limitador continua privado ao pacote.
- A estratégia de sessão do painel (`SessionFixationProtectionStrategy`, `setMigrateSessionAttributes(false)`) é criada num só lugar e usada pelo login e pelo MFA.
- Ordem: as Tasks 3 e 4 do rascunho viraram uma só (o login em duas etapas precisa do `verify` para os testes antigos continuarem verdes). A inscrição é a Task 4. `ci.yml` e o `.env.example` entram na Task 5; o e2e e as telas ficam depois da aprovação dos textos.
- Resposta com o segredo (`POST .../enrollment`) leva `Cache-Control: no-store`.
- Chave de cifragem: `application.yml` tem só `${APP_MFA_ENCRYPTION_KEY}` (sem padrão); o `application-prod.yml` não tem a chave; o `application-local.yml` tem `${APP_MFA_ENCRYPTION_KEY:<chave fictícia>}` com `# gitleaks:allow` na mesma linha; `src/test/resources/application.properties` tem a chave fictícia de baixa entropia (um `.properties` não aceita comentário na mesma linha; confirmar com o gitleaks, ver Task 2).

## Tarefas de BACKEND (executadas com subagent-driven-development)

Regras de todas as tarefas: TDD (teste que falha primeiro, visto falhar; depois o código; depois o teste verde); só testes direcionados (`cd backend; .\mvnw.cmd test -Dtest=...`), nunca o `just check`; nada de dependência nova; commits em inglês (Conventional Commits) na branch `feat/15-admin-mfa`; segredo, código TOTP, URI `otpauth` e chave nunca em log, mensagem de exceção ou `toString()`.

### Task 1: TOTP do RFC 6238

**Files:** Create `backend/src/main/java/br/com/sergio/sapiensia/security/Totp.java`; Test `backend/src/test/java/br/com/sergio/sapiensia/security/TotpTest.java`.

**Interfaces produced** (classe `public final`, só métodos estáticos):
```java
public static byte[] newSecret();                                   // 20 bytes de SecureRandom
public static String toBase32(byte[] secret);                       // maiúsculas, sem "=" (BouncyCastle Base32, dependência direta do pom)
public static long stepAt(Instant t);                               // floor(epochSecond / 30)
public static String code(byte[] secret, long step, int digits);    // HMAC-SHA1 da JDK, truncagem dinâmica, zeros à esquerda
public static OptionalLong matchingStep(byte[] secret, String code, Instant now); // passos stepAt-1, stepAt, stepAt+1
public static String otpauthUri(String issuer, String account, byte[] secret);
```

- [ ] **Step 1: testes que falham** (`TotpTest`)
  - `rfc6238AppendixBVectors`: segredo ASCII `12345678901234567890`, SHA-1, 8 dígitos, `code(secret, time/30, 8)`: 59 → `94287082`; 1111111109 → `07081804`; 1111111111 → `14050471`; 1234567890 → `89005924`; 2000000000 → `69279037`; 20000000000 → `65353130`.
  - `sixDigitsKeepLeadingZeros`: com os mesmos vetores, `code(..., 6)` é os 6 últimos dígitos do vetor de 8 (`07081804` → `081804`).
  - `acceptsPreviousCurrentAndNextStepAndReturnsTheMatchingStep`; `rejectsTwoStepsAwayInBothDirections` (t-2 e t+2).
  - `rejectsMalformedCodes`: nulo, vazio, 5 dígitos, 7 dígitos, letras, espaço no meio, espaço nas pontas, dígitos Unicode (`"٠٠٠٠٠٠"`), `"+12345"`.
  - `matchingStepChecksAllThreeCandidatesWithoutEarlyExit`: teste de desenho, via um `Totp` interno que conta chamadas de `code`, ou revisão do reviewer; o comparador é `MessageDigest.isEqual` sobre bytes ASCII.
  - `newSecretHas20BytesAndBase32Has32CharsWithoutPadding`; dois `newSecret` diferem.
  - `otpauthUri` exato para segredo conhecido e e-mail com `+` e `@`: `otpauth://totp/SapiensIA:<e-mail com %40/%2B>?secret=<BASE32>&issuer=SapiensIA&algorithm=SHA1&digits=6&period=30`.
- [ ] **Step 2:** rodar `-Dtest=TotpTest` e ver falhar (classe inexistente).
- [ ] **Step 3:** implementar. Comparação em tempo constante; o código só vale com exatamente 6 caracteres `'0'..'9'`.
- [ ] **Step 4:** `-Dtest=TotpTest` verde.
- [ ] **Step 5:** `git add` dos dois arquivos; commit `feat: add RFC 6238 TOTP calculation`.

### Task 2: cifragem do segredo e chave (condição b)

**Files:** Create `security/SecretCipher.java`, `security/MfaProperties.java`; Modify `application.yml`, `application-local.yml`, `src/test/resources/application.properties`; Test `security/SecretCipherTest`, `security/MfaConfigurationTest`.

**Interfaces produced:**
```java
@Component public class SecretCipher {
  public String encrypt(byte[] plain, String aad);   // "v1:" + Base64(nonce12 || ciphertext || tag16); nonce novo a cada chamada
  public byte[] decrypt(String stored, String aad);  // IllegalStateException("MFA secret could not be decrypted") sem dado nenhum na mensagem
}
@ConfigurationProperties("app.mfa") record MfaProperties(String encryptionKey)  // toString() = "MfaProperties[redacted]"
```
O construtor do `SecretCipher` decodifica a chave (Base64) e exige exatamente 32 bytes; senão `IllegalStateException("Invalid app.mfa.encryption-key")` (a mensagem nunca traz o valor).

- [ ] **Step 1: testes que falham**
  - `SecretCipherTest`: ida e volta; dois `encrypt` do mesmo valor diferem; AAD diferente falha; um byte adulterado falha; prefixo diferente de `v1:` falha; texto curto demais falha; nenhuma mensagem de exceção contém o texto cifrado, o AAD nem a chave.
  - `MfaConfigurationTest`: chave vazia, Base64 inválido, 16 bytes e 31 bytes impedem o contexto de subir (mensagem sem o valor, conferida na saída capturada); o contexto sobe com 32 bytes. Um teste que **lê os arquivos**: o `application.yml` principal tem `app.mfa.encryption-key` igual a `${APP_MFA_ENCRYPTION_KEY}` exatamente (sem `:`), e o `application-prod.yml` **não** menciona `mfa` nem `APP_MFA_ENCRYPTION_KEY`. Um teste com o perfil `prod` e sem a variável: o contexto não sobe (no estilo de `AdministratorBootstrapProdConfigTest`).
- [ ] **Step 2:** ver falhar.
- [ ] **Step 3:** implementar (AES/GCM/NoPadding de `javax.crypto`, tag de 128 bits, `SecureRandom` para o nonce, AAD via `updateAAD`). Configuração: `application.yml` ganha `app.mfa.encryption-key: ${APP_MFA_ENCRYPTION_KEY}` com um comentário de uma linha; `application-local.yml` ganha `${APP_MFA_ENCRYPTION_KEY:<Base64 de 32 bytes fictícios, baixa entropia>}` com `# gitleaks:allow` na mesma linha; `application.properties` de teste ganha o mesmo valor fictício, numa linha precedida de um comentário "fictitious, tests only".
- [ ] **Step 4:** rodar `-Dtest='SecretCipherTest,MfaConfigurationTest'` verde. Rodar o gitleaks como o CI (`docker run --rm -v "<worktree>:/repo" ghcr.io/gitleaks/gitleaks:v8.30.1 dir /repo --redact`); se acusar a chave fictícia, **não** criar entrada no `.gitleaksignore`: parar e relatar.
- [ ] **Step 5:** commit `feat: encrypt the Administrator TOTP secret with AES-GCM`.

### Task 3: login em duas etapas (senha → pendente → código), limite, replay e rotas (condições c, d, e)

**Files:**
- Create: `db/migration/V9__add_administrator_totp.sql` (o SQL da seção Migration; conferir que V9 é o próximo número); `admin/PendingAdministrator.java`; `admin/AdminMfaAccess.java`; `admin/AdministratorMfa.java`; `admin/AdminMfaController.java` (nesta tarefa só `POST /api/admin/sessions/mfa`); `admin/MfaCodeRequest.java`; `admin/InvalidMfaCodeException.java` (pública, para o `ApiExceptionHandler`).
- Modify: `customer/PasswordLogin.java`, `customer/LoginAttemptLimiter.java`, `customer/ClientIpResolver.java` (visibilidade, ver Ajustes), `admin/AdministratorPrincipal.java`, `admin/AdministratorUserDetailsService.java`, `admin/AdminSessionController.java` (sai a trava e o 404; `accept` devolve `PendingAdministrator`), `admin/Administrators.java`, `customer/SessionVersionFilter.java`, `security/SecurityConfig.java`, `web/ApiExceptionHandler.java` (`InvalidMfaCodeException` → `400 {errors:{code:"CODE_INVALID"}}`), `application.yml`, `application-local.yml`, `application-prod.yml`, `src/test/resources/application.properties` (sai `app.admin.panel-login-enabled`; entra `app.admin.mfa-pending-ttl: PT5M`).
- Delete: `AdminPanelLoginDisabledTest`.
- Test: create `AdminMfaTestSupport` (ver Interfaces), `AdminMfaLoginTest`, `AdminMfaBypassTest`, `AdminMfaReplayTest`; modify `AdminBrowser` (`verifyMfa(code)`, `loginAdminFully(email, password, secret, clock)`), `AdminSessionTest`, `AdminSharedIpLimitTest`, `AdminCustomerKeyCollisionTest`, `AdminRouteDenyByDefaultTest`, `SessionVersionFilterTest` (construtor novo do principal).

**Interfaces produced:**
```java
// Administrators (package-private)
record Totp(String secretCipher, boolean enabled, OptionalLong lastStep) {}
Optional<Totp> findTotp(UUID id);
int acceptStep(UUID id, long step);   // UPDATE administrators SET totp_last_step = :step WHERE id = :id AND totp_enabled_at IS NOT NULL AND (totp_last_step IS NULL OR totp_last_step < :step); devolve as linhas
// AdministratorMfa (@Service, package-private)
enum Verdict { ACCEPTED, REJECTED, NOT_ENROLLED, UNAVAILABLE }
Verdict verify(PendingAdministrator who, String code);  // admite no limitador com a chave "admin-mfa:" + id (já em minúsculas) e o IP do pedido; não conta o que não é erro de código
```
Chave do limitador: `"admin-mfa:" + id`, e o `Retry-After` do `429` vem da admissão, como no login. As falhas de código gastam a ficha do IP compartilhada com o login.

**Fluxo do `verify`:** admitir (429 se negado) → ler `findTotp` (sem MFA ativo: `release` e `NOT_ENROLLED`, que o controller responde `409`) → decifrar (AAD `administrator-totp:<id>`) → `Totp.matchingStep` (vazio: `REJECTED`, a falha já está contada) → `acceptStep` (0 linhas: `REJECTED`) → `recordSuccess` e `ACCEPTED`. Erro de banco ou de decifragem: `release`, log só com o nome da classe, `UNAVAILABLE` → `503`. Controller: `ACCEPTED` → monta `AdministratorPrincipal` completo (capacidades + os dois fatores), chama `passwordLogin.startSession(...)` e responde `204`; `REJECTED` → `InvalidMfaCodeException`; `NOT_ENROLLED` → `409`.

- [ ] **Step 1: testes que falham**
  - `AdminMfaLoginTest` (servidor real, relógio de teste controlável por um `@TestConfiguration` com `Clock` `@Primary`, em `AdminMfaTestSupport`): senha certa → `204` e `GET /current` → `401`; código certo → `204`, `JSESSIONID` novo, `XSRF-TOKEN` novo, `GET /current` → `200`; código errado → `400 {errors.code: CODE_INVALID}` e `GET /current` continua `401`; código de 5 ou 7 dígitos → `400`; relógio +5 min 1 s depois da senha → `POST /mfa` → `401`; relógio +4 min 59 s → ainda funciona; código do passo anterior ao último aceito → `400`; 5 códigos errados → `429` com `Retry-After`, e o código certo também recebe `429` durante o bloqueio; o limite do MFA não bloqueia o login por senha de um Cliente com o mesmo e-mail nem o contrário; cada tentativa de código gasta uma ficha do IP (com `ip-max-attempts` baixo, o IP esgotado recebe `429` mesmo com a conta livre); `findTotp` vazio (linha apagada) → `401` pelo `SessionVersionFilter`.
  - `AdminMfaReplayTest` (condição c): (1) o mesmo código usado duas vezes em sessões pendentes diferentes: a segunda dá `400`; (2) duas threads com o mesmo código válido, liberadas por um `CountDownLatch`, cada uma com a sua sessão pendente: exatamente um `204` e um `400`, e `totp_last_step` no banco = o passo do código; (3) teste de repositório: `acceptStep(id, n)` → 1, `acceptStep(id, n)` → 0, `acceptStep(id, n-1)` → 0, `acceptStep(id, n+1)` → 1; (4) `acceptStep` em conta com `totp_enabled_at` nulo e segredo pendente → 0 (segredo pendente nunca autentica).
  - `AdminMfaBypassTest` (condição e; `@ParameterizedTest` com um caso por rota e por estado, todos contra o servidor real): estado **pendente** recebe `401` em `GET /api/admin/sessions/current`, `GET /api/admin/qualquer-coisa`, `POST /api/admin/qualquer-coisa`, `GET /api/sessions/current` e `DELETE /api/sessions` (e nenhuma resposta traz dado do Administrador); estado pendente com TTL vencido recebe `401` também em `POST /api/admin/sessions/mfa`; **Cliente** recebe `403` em `POST /api/admin/sessions/mfa` e em `current`; **anônimo** recebe `401` em todas; Administrador **completo** recebe `403` em `POST /api/admin/sessions/mfa` e `200` em `current`; `POST /api/admin/password-setup` não cria sessão (nenhum `Set-Cookie: JSESSIONID`); o login do Cliente com a sessão pendente aberta no mesmo navegador a troca por uma de Cliente sem acesso ao painel; `login` com o Administrador completo volta ao estado pendente (`current` → `401`); o principal pendente **não tem** `CAPABILITY_PANEL_ACCESS` (teste unitário de `PendingAdministrator.getAuthorities()`).
  - Testes antigos adaptados: onde esperavam sessão completa depois do login passam a usar `loginAdminFully`; `AdminSessionTest` (fixação de sessão, 503, `session_version`) e `AdminSharedIpLimitTest` mantêm as mesmas asserções.
- [ ] **Step 2:** rodar `-Dtest='AdminMfa*Test,AdminSessionTest,AdminSharedIpLimitTest,AdminCustomerKeyCollisionTest,AdminRouteDenyByDefaultTest,SessionVersionFilterTest'` e ver falhar pelos motivos certos.
- [ ] **Step 3:** implementar na ordem: migration e `Administrators`; `PendingAdministrator` e `AdministratorPrincipal`; `PasswordLogin.startSession` e as visibilidades; `AdminSessionController`; `AdministratorMfa` e o controller; `AdminMfaAccess`, as regras e o `AccessDeniedHandler` no `SecurityConfig`; `SessionVersionFilter`; remoção da trava nos YAML.
- [ ] **Step 4:** rodar os mesmos testes (e `-Dtest='Customer*Test,Login*Test,Session*Test,PasswordReset*Test'`, que exercitam o `PasswordLogin` e o limitador mexidos) até ficarem verdes.
- [ ] **Step 5:** commit `feat: two-step administrator login with TOTP code, replay guard and attempt limits`.

### Task 4: inscrição do MFA e recusa quando já ativo (condição a)

**Files:** Modify `admin/AdminMfaController.java`, `admin/AdministratorMfa.java`, `admin/Administrators.java`, `security/SecurityConfig.java`; Test `AdminMfaEnrollmentTest`; extend `AdminMfaBypassTest`.

**Interfaces produced:**
```java
// Administrators
int startEnrollment(UUID id, String secretCipher);   // UPDATE ... SET totp_secret = :c, totp_last_step = NULL WHERE id = :id AND totp_enabled_at IS NULL
int enable(UUID id, String secretCipher, long step); // UPDATE ... SET totp_enabled_at = now(), totp_last_step = :step WHERE id = :id AND totp_enabled_at IS NULL AND totp_secret = :c
// AdministratorMfa
boolean enrolled(UUID id);
Optional<Enrollment> startEnrollment(PendingAdministrator who);   // vazio = já ativo (409); Enrollment(String secret, String otpauthUri) com toString() redigido
Verdict confirmEnrollment(PendingAdministrator who, String code); // ACCEPTED = MFA ativado; mesmos limites de admissão e as mesmas chaves do verify
```
Rotas (todas só para o estado pendente, via `AdminMfaAccess`): `GET /api/admin/sessions/mfa` → `200 {"enrolled": boolean}`; `POST /api/admin/sessions/mfa/enrollment` → `200 {secret, otpauthUri}` com `Cache-Control: no-store`, ou `409` se o MFA já está ativo; `POST /api/admin/sessions/mfa/enrollment/confirmation` com `{code}` → `204` (MFA ativado e sessão completa, com novo ID de sessão e novo CSRF), `400 CODE_INVALID`, `429`, ou `409` se o MFA já está ativo ou não há inscrição começada.

- [ ] **Step 1: testes que falham** (`AdminMfaEnrollmentTest`)
  - Sem MFA: `GET .../mfa` → `{"enrolled":false}`; `POST .../enrollment` → `200` com `secret` (32 caracteres Base32) e `otpauthUri` exata, `Cache-Control: no-store`; a coluna `totp_secret` começa com `v1:` e **não** contém o Base32 devolvido; `totp_enabled_at` continua nulo.
  - Segundo `POST .../enrollment`: troca o segredo; o código do primeiro segredo na confirmação → `400`; o do segundo → `204`.
  - Confirmação com código errado → `400`, nada ativado; certo → `204`, `totp_enabled_at` preenchido, `totp_last_step` = o passo do código, `GET /current` → `200`, e o **mesmo código** no login seguinte → `400` (replay).
  - **Condição a:** com o MFA já ativo, `POST .../enrollment` e `POST .../enrollment/confirmation` → `409`, e a coluna `totp_secret` fica **idêntica** (lida antes e depois); o código do autenticador antigo continua entrando; um `GET .../mfa` → `{"enrolled":true}`; nenhuma resposta de nenhuma rota contém o segredo depois da ativação.
  - **Segredo pendente nunca autentica:** com `totp_secret` preenchido e `totp_enabled_at` nulo, `POST /api/admin/sessions/mfa` com um código válido para esse segredo → `409`, sem sessão completa.
  - Corrida: duas confirmações paralelas com o mesmo código válido: um `204`, o outro `409`/`400`, e `enable` devolve 1 linha uma só vez.
  - Limites: 5 códigos errados na confirmação → `429` com `Retry-After`; a confirmação e o `verify` dividem o mesmo contador `admin-mfa:<id>`.
  - `AdminMfaBypassTest` ganha as linhas das 3 rotas novas: anônimo `401`, Cliente `403`, Administrador completo `403`, pendente vencido `401`.
- [ ] **Step 2:** ver falhar. **Step 3:** implementar. **Step 4:** testes verdes (`-Dtest='AdminMfa*Test'`). **Step 5:** commit `feat: enroll the Administrator authenticator and refuse re-enrollment while active`.

### Task 5: ADR-0004, `ci.yml`, `.env.example` e teste de log (condições b, h, i)

**Files:** Modify `docs/adr/0004-identidade-e-mfa.md`, `.github/workflows/ci.yml`, `.env.example`; Test `admin/AdminMfaLogTest`.

- [ ] **Step 1: teste que falha** (`AdminMfaLogTest`, com `OutputCaptureExtension`, servidor real): percorrer a inscrição, a confirmação, um login com código certo, um com código errado, um replay e um bloqueio `429`; a saída capturada (do início ao fim, e a de uma subida com chave inválida) não contém: o segredo Base32, os bytes do segredo em Base64/hex, a URI `otpauth`, nenhum código de 6 dígitos usado, a chave de cifragem (Base64), o texto cifrado de `totp_secret`, nem o e-mail do Administrador. `toString()` de `MfaCodeRequest`, `Enrollment`, `PendingAdministrator`, `AdministratorPrincipal` e `MfaProperties` não traz esses valores.
- [ ] **Step 2:** ver falhar só se achar vazamento real (se passar de primeira, registrar isso no relatório; o teste fica como guarda).
- [ ] **Step 3: ADR-0004**, nos 6 pontos do plano e nas decisões: (1) o TOTP é nosso, com a JDK e os vetores do RFC 6238, e **por que** (nenhuma biblioteca ativa e adequada; desvia da letra da issue "biblioteca mantida", como o ADR já previa com "se houver uma adequada"); (2) os 10 códigos de recuperação ficam no #16; (3) a recuperação do acesso é o procedimento manual (apagar a linha em `administrators`, reiniciar; o bootstrap recria e envia o link de senha; senha e MFA novos), sem rota pública, até o #16; (4) o deploy em homologação e produção exige `APP_MFA_ENCRYPTION_KEY` criada antes (Fly secrets) e **a troca dessa chave exige refazer o MFA pelo procedimento manual**; (5) o bloqueio fixo vale também para o código (`admin-mfa:<id>`, a cota por IP compartilhada, `429`); (6) colunas em `administrators` em vez de `MfaCredential` (seção 8 da spec). Mais: o fluxo em duas etapas, as rotas novas (`/api/admin/sessions/mfa**`, só para o estado "senha certa, MFA pendente", vencendo em 5 min; **não** são `permitAll`), o replay (`totp_last_step` em um `UPDATE` condicional), a cifragem (AES-GCM, nonce aleatório, id do Administrador como AAD, chave só em segredo da plataforma; valor fictício só no perfil local e nos testes) e a remoção da trava `app.admin.panel-login-enabled` (substituir o parágrafo que a descreve e o da recuperação manual "até o #15").
- [ ] **Step 4: `ci.yml`**: no passo "Start the system": gerar `MFA_KEY="$(openssl rand -base64 32)"`, mascarar com `echo "::add-mask::$MFA_KEY"` **antes** de qualquer uso, passar `-e APP_MFA_ENCRYPTION_KEY="$MFA_KEY"` ao `docker run`, **remover** `-e APP_ADMIN_PANEL_LOGIN_ENABLED=true` e o comentário dele. **`.env.example`:** remover a linha da trava, acrescentar `APP_MFA_ENCRYPTION_KEY=` (sem valor; um comentário diz "Base64 de 32 bytes; no local, o perfil local já tem uma chave fictícia").
- [ ] **Step 5:** `-Dtest='AdminMfaLogTest'` verde; `git diff --check`; commit `docs: ADR-0004 for the Administrator MFA, CI key and log guard test`.

## Tarefas de FRONTEND e e2e (só depois do OK aos textos)

### Task 6: telas
`features/admin/AdminMfaPage.tsx` (+ teste), `api.ts`, `hooks.ts`, `schemas.ts`, `AdminLoginPage.tsx` (sucesso → `/painel/verificacao`), `App.tsx`, `qrcode.react` **4.2.0 exato (sem `^`)** com lockfile e `pnpm audit`. Textos: a lista fechada abaixo, depois de aprovada. Passos de TDD detalhados depois do OK.

### Task 7: e2e com MFA
`e2e/totp.ts` e `e2e/admin-panel.e2e.ts` (Risco h; condição g: segredo em `.tmp/e2e/admin-totp-secret.txt`, recusa fora de localhost, nada na saída, no relatório nem no trace). Depois, `just e2e` com um servidor de cada vez, e `/verificar-tela`.

## Textos que o usuário vê (lista fechada, para aprovação)

**`/painel/verificacao`, carregando e falhas comuns**
| Onde | Texto |
|---|---|
| Carregando | `Carregando…` (o mesmo de hoje) |
| Falha ao carregar | `Não foi possível carregar esta página agora. Tente novamente.` |
| Tempo da etapa esgotado (401) | `O tempo para digitar o código acabou. Entre de novo com a sua senha.` |
| Link do 401 | `Voltar para a entrada` |
| Validação do campo | `Digite os 6 dígitos do código.` |
| Código errado, vencido ou repetido (400) | `Código incorreto ou vencido. Confira o aplicativo e tente de novo.` |
| Bloqueio (429) | constante `LOGIN_BLOCKED`: `Muitas tentativas de entrada. Aguarde alguns minutos e tente de novo.` |
| Erro inesperado | constante `LOGIN_UNEXPECTED_ERROR`: `Não foi possível entrar agora. Tente novamente em instantes.` |

**Inscrição (primeiro acesso)**
| Onde | Texto |
|---|---|
| Título | `Ative a verificação em duas etapas` |
| Introdução | `Para proteger o painel, a entrada pede a senha e um código do aplicativo autenticador do seu celular.` |
| Passo 1 | `1. No aplicativo autenticador, leia o QR code abaixo.` |
| Nome acessível do QR | `QR code para o aplicativo autenticador` |
| Alternativa | `Não consegue ler o QR code? Digite esta chave no aplicativo:` (seguida da chave em grupos de 4) |
| Passo 2 | `2. Digite o código de 6 dígitos que o aplicativo mostra.` |
| Rótulo | `Código` |
| Botão / enquanto envia | `Ativar e entrar` / `Ativando…` |

**Verificação (acessos seguintes)**
| Onde | Texto |
|---|---|
| Título | `Verificação em duas etapas` |
| Instrução | `Digite o código de 6 dígitos do aplicativo autenticador.` |
| Rótulo | `Código` |
| Botão / enquanto envia | `Entrar` / `Entrando…` |

**No aplicativo autenticador do Administrador:** emissor `SapiensIA`, conta = o e-mail do Administrador.

Nenhum texto muda em `/painel/entrar`, `/painel/definir-senha`, `/painel` nem no e-mail de primeiro acesso.

## Riscos

### a) Caminho de contorno do MFA

O estado pendente nunca recebe `CAPABILITY_PANEL_ACCESS`. As capacidades só existem no `AdministratorPrincipal`, e ele só é criado em `AdministratorMfa` depois de `acceptStep`/`enable` devolverem 1 linha. Além disso, cada rota do painel exige os fatores `FACTOR_PASSWORD` **e** `FACTOR_TOTP` (`AllRequiredFactorsAuthorizationManager`), como segunda trava independente. Não uso `@EnableMultiFactorAuthentication` global, porque ele exigiria os fatores também nas rotas do Cliente.

| Rota | Anônimo | Cliente | MFA pendente | Administrador completo |
|---|---|---|---|---|
| `POST /api/admin/sessions` (senha) | 204 → pendente / 401 / 429 | idem; a sessão do Cliente é trocada | idem; recomeça a etapa | idem; **volta a pendente** (a sessão é trocada) |
| `GET /api/admin/sessions/mfa` | 401 | 403 | 200 `{enrolled}` (até 5 min) / 401 depois | 403 |
| `POST /api/admin/sessions/mfa/enrollment` | 401 | 403 | 200 `{secret, otpauthUri}` se ainda não ativou; 409 se já ativou | 403 |
| `POST /api/admin/sessions/mfa/enrollment/confirmation` | 401 | 403 | 204 / 400 / 429 / 409 | 403 |
| `POST /api/admin/sessions/mfa` (código) | 401 | 403 | 204 / 400 / 429; 409 se ainda não ativou | 403 |
| `GET /api/admin/sessions/current` | 401 | 403 | **401** | 200 |
| qualquer outra `/api/admin/**` | 401 | 403 | 401 | 403 (`denyAll`) |
| `POST /api/admin/password-setup` | pública: só funciona com `password_hash` nulo; nunca cria sessão nem mexe no MFA | idem | idem | idem |
| `POST /api/sessions` (login do Cliente) | troca qualquer sessão por uma de Cliente, que não tem autoridade de painel nem `FACTOR_PASSWORD` de Administrador | | | |
| `GET/DELETE /api/sessions[/current]` do Cliente | regras de hoje | regras de hoje | 401 (não é Cliente) | 403, como hoje |
| `DELETE /api/sessions/current` (logout) | 204 | 204 | 204 e encerra a pendência | 204 |

O `401` do estado pendente vem de um `AccessDeniedHandler`. Sem ele, o Spring daria `403`, e a tela mostraria "Esta área é só para o Administrador." a quem largou o login no meio. Os testes cobrem cada célula da tabela (Task 3).

### b) Segredo TOTP
- Cifrado com AES-256-GCM (JDK). AAD com o ID do Administrador: copiar o texto cifrado para outra linha não funciona.
- A chave vem de `APP_MFA_ENCRYPTION_KEY`: Fly secrets em homologação e produção (criar antes do primeiro deploy; isso entra na checklist do #47), `openssl rand` na hora no CI, e uma chave fixa **não secreta** no perfil `local` e nos testes, que só protegem dados fictícios (Decisão 5).
- Nunca vai para log: `MfaProperties` e `Enrollment` têm `toString()` redigido, e o erro de subida diz só "invalid app.mfa.encryption-key".
- O segredo só sai na resposta do `POST .../enrollment`, antes da ativação. Depois disso a rota responde `409`, e nenhuma outra o devolve.
- Rotação da chave: o prefixo `v1:` permite rotacionar no futuro, mas a rotação não entra no #15. Hoje, perder a chave equivale a perder o MFA, e a saída é o procedimento do Risco g.

### c) Reuso do código (replay)
- Janela de `t-1` a `t+1` (±30 s).
- Um código só é aceito se o passo dele for maior que `totp_last_step`, e quem grava isso é um único `UPDATE ... where totp_last_step is null or totp_last_step < :step`. Quando duas requisições chegam com o mesmo código, o PostgreSQL trava a linha e reavalia o `WHERE` da segunda depois da primeira: só uma recebe 1 linha. Teste com duas threads (Task 4).
- Efeito colateral aceito: dois logins legítimos no mesmo passo de 30 s precisam esperar o próximo código.
- A ativação grava o passo usado (`enable`), então o código usado na inscrição também não vale de novo no login.
- O relógio é o `Clock` injetado (`ClockConfig`), para os testes controlarem o tempo.

### d) Força bruta do código de 6 dígitos
- O mesmo `LoginAttemptLimiter` (admissão antes de calcular; erro já contado; `recordSuccess` só com o código aceito).
- Chave da conta: `admin-mfa:` + UUID do Administrador, tirado do principal na sessão e nunca de um campo digitado. Por isso não precisa de normalização, e o prefixo próprio não cruza com `admin:<e-mail>` nem com `customer:<e-mail>`. O balde por IP é o mesmo do login.
- 5 códigos errados em 15 min bloqueiam o MFA daquela conta por 15 min (`429` + `Retry-After`), inclusive para o código certo. O balde é por conta e não por sessão, então refazer a senha não zera os erros.
- Conta: com 3 códigos válidos por janela, são ~480 tentativas por dia por conta, ou ~0,14 % de acerto por dia. Isso exige a senha correta antes, e a senha tem o seu próprio limite.
- Bloqueio fixo (ADR-0004): quem tem a senha pode manter o Administrador fora do painel errando o código. Sem a senha, não alcança o balde `admin-mfa:`. O bloqueio da senha por quem só sabe o e-mail continua como está; o #42 pode revê-lo. Registrado no ADR.

### e) Códigos de recuperação
São escopo do **#16** (bloqueado pelo #15): "10 códigos exibidos uma só vez; cada um funciona uma única vez". Proposta para o #16, sem implementar agora: 10 códigos de 16 caracteres Base32 em 4 grupos (`abcd-efgh-ijkl-mnop`, 80 bits), guardados só como SHA-256 (80 bits aleatórios tornam um hash lento desnecessário), uso único por `UPDATE` condicional. Ao usar o último, o Administrador entra e o painel pede para gerar novos (gerar exige reautenticação). Decisão 3 pergunta se eles entram já no #15.

### f) Inscrição no primeiro acesso
- A ordem com o #14 não muda: o link do e-mail define a senha. Só depois a senha leva à etapa pendente, e a inscrição não começa sem a senha.
- O Administrador sem MFA nunca ganha sessão completa, porque a única saída do estado pendente é `enable` devolver 1 linha.
- Se ele não terminar: a pendência vence em 5 min (ou no logout ou no fim da sessão), e o segredo não confirmado fica cifrado no banco sem valer nada. O próximo login gera um segredo novo.
- Risco aceito (TOFU): quem souber a senha antes da primeira inscrição pode inscrever o próprio aplicativo. Para chegar à senha, precisaria do link enviado ao e-mail do Administrador (24 h, uso único), e a janela fecha na primeira inscrição. Uma alternativa mais forte seria inscrever o MFA na própria página do link de definir senha, mas ela muda o fluxo do #14 e eu não recomendo agora.

### g) Recuperação do acesso do Administrador (decisão aberta do #14)

| Opção | Como | Avaliação |
|---|---|---|
| 1. Procedimento manual atual, documentado | Quem tem acesso ao banco apaga a linha (`delete from administrators;`) e reinicia. O bootstrap recria a conta e manda o link: senha nova e inscrição nova | Exige banco **e** e-mail; reaproveita o que existe; zero rota nova. Perde-se só a linha do Administrador (as futuras referências a ela, como a auditoria do #16, terão de considerar isso) |
| 2. SQL que zera só o MFA | `update administrators set totp_secret=null, totp_enabled_at=null, totp_last_step=null, session_version=session_version+1;` | Mais rápido, mas quem souber a senha inscreve o próprio aplicativo na janela seguinte. Mais fraco que a 1 |
| 3. Códigos de recuperação (#16) | Ver Risco e | A saída do dia a dia, sem acesso ao banco |
| 4. Rota pública de recuperação por e-mail | — | **Não proposta**: transformaria o e-mail num jeito de pular o MFA. Só com aprovação explícita |

**Recomendação: opção 1 agora** (documentada no ADR-0004 como procedimento oficial até o #16) **+ opção 3 no #16**. O deploy em produção fica liberado pelo MFA. Se o Sérgio quiser os códigos de recuperação antes da produção, o #16 entra antes do deploy (Decisão 3).

### h) Remoção da trava e o e2e do CI
- O CI sobe a imagem `prod` com o banco zerado. Com a trava fora, o e2e precisa: (1) definir a senha pelo link do outbox (como hoje); (2) entrar com a senha; (3) inscrever o MFA, lendo a chave manual da tela; (4) gerar o código com `e2e/totp.ts` (HMAC do `node:crypto`); (5) sair; (6) entrar de novo com senha + código, esperando o próximo passo de 30 s por causa do reuso; (7) conferir que um código errado mostra a mensagem; (8) manter a parte do Cliente barrado.
- O segredo e o código não podem aparecer em log, relatório nem trace:
  - O teste já usa `trace: "off"`, `screenshot: "off"` e `video: "off"`.
  - A chave é lida com `textContent()`, que não grava o valor no passo.
  - O código é digitado com o `fillSecret` existente (o `fill()` grava o valor no título do passo).
  - Nenhum `expect` compara o segredo ou o código (a mensagem de falha mostraria o valor). As mensagens de erro do teste não citam os dois.
- A chave de cifragem do CI nasce com `openssl rand` dentro do `run` e não é impressa.
- No local, o segredo fica em `.tmp/e2e/admin-totp-secret.txt` (fora do git, ao lado do `admin-password.txt` que já existe), para repetir o teste contra o mesmo banco. Se o banco já tiver MFA ativo e o arquivo sumir, o teste para com uma mensagem que aponta o procedimento do Risco g.
- O tempo do teste sobe para até 120 s (`test.setTimeout`), por causa da espera do próximo passo.
- O Administrador que já existe no seu banco local (do #14, com senha e sem MFA) cai na inscrição no próximo login, como esperado.

### i) Concorrência, crescimento, logs e o ADR-0004
- **Concorrência:**
  - Dois inícios de inscrição: o último vence, e a confirmação só ativa se o segredo ainda for o lido (`totp_secret=:c` no `UPDATE`).
  - Dois códigos iguais: Risco c.
  - Ativação dupla: `totp_enabled_at is null` no `UPDATE`.
- **Crescimento:** nenhuma tabela nova. Os mapas do limitador já têm teto (100 000) e limpeza. As sessões pendentes vencem pelo fator de 5 min e pelo tempo de sessão de 2 h.
- **Logs:** nada de segredo, código, URI ou chave. O `AdminMfaLogTest` captura a saída durante a inscrição, a verificação e uma subida com chave inválida, e procura esses valores.
- **Divergências com o ADR-0004:**
  1. "Usando uma biblioteca mantida… se houver uma adequada": com a opção (b), o ADR registra que não havia uma adequada.
  2. "10 códigos de recuperação": ficam no #16. O ADR passa a dizer "no #16".
  3. "O #15 remove a trava… junto com a recuperação": a recuperação fica decidida como no Risco g.
  4. "Não se faz deploy antes do #15": passa a "antes do #15 e de `APP_MFA_ENCRYPTION_KEY` criada".
  5. "Bloqueio fixo… o #15 pode rever": mantido, com o limite do código acrescentado.
  6. Seção 8 da spec (`MfaCredential`): colunas na tabela `administrators`, mesma justificativa já dada para `User`/`Role`.
- **Spec SEC-001** prefere passkeys/WebAuthn (resistentes a phishing). O TOTP não é. Isso foi decidido no ADR-0004, e passkey continua como melhoria futura.

### j) Reaproveitamento
- `PasswordLogin` continua sendo o único dono da etapa da senha (limite, hash, 401/429/503). Sua parte final (trocar sessão, CSRF e salvar o contexto) vira `startSession`, chamada pelo login e pela conclusão do MFA. Nada é copiado.
- `LoginAttemptLimiter` fica com os mesmos contadores e o mesmo balde por IP; só passa a ser público.
- `SessionVersionFilter`, `ClockConfig`, `ApiExceptionHandler` (formato `errors`), `apiFetch`, as constantes de `messages.ts` e o `fillSecret`/outbox do e2e também são reaproveitados.
- Código novo de verdade: o TOTP, a cifragem e a página do MFA.

## Review Focus
1. Administrador que larga a inscrição e volta horas depois: deve recomeçar da senha, e o segredo antigo não pode valer. Teste na Task 5.
2. Relógio do celular adiantado ou atrasado 25 s: deve funcionar; 65 s: deve falhar com a mensagem de código incorreto. Teste na Task 1 (janela) e na Task 4.
3. Duplo clique em "Ativar e entrar" ou em "Entrar": um sucesso e nenhum erro na tela (o botão fica desabilitado durante o envio). Teste no Vitest da Task 6.
4. Código colado com espaço no meio ("123 456"): proponho aceitar, tirando os espaços **no frontend**, e o backend continua estrito. Teste na Task 6.
5. Cliente logado que abre `/painel/verificacao`: deve ir para `/painel/entrar` (403 do `GET .../mfa`), sem ver a inscrição. Teste na Task 6.

## Decisões e condições do Sérgio (aprovadas em 06/11/2026)

1. **TOTP próprio com a JDK**, provado com os vetores do RFC 6238, comparação em tempo constante, tolerância de ±1 passo. Registrar no ADR e na PR que isso se desvia da letra da issue ("biblioteca mantida") e por quê. (Tasks 1 e 5.)
2. **QR com `qrcode.react` 4.2.0**, versão exata sem `^`, lockfile atualizado e `pnpm audit`; chave manual sempre visível. (Task 6.)
3. **Códigos de recuperação ficam no #16.**
4. **Recuperação do acesso:** procedimento manual (apagar a linha em `administrators` e reiniciar) documentado no ADR-0004, sem rota pública. (Task 5.)
5. **Chave de cifragem fixa e fictícia só no perfil local e nos testes.** (Task 2.)
6. **"Verificação em duas etapas"** nas telas.
7. **5 minutos** para digitar o código depois da senha.

| Condição | Onde |
|---|---|
| a) Inscrição recusada com o MFA ativo; segredo novo só vale depois do primeiro código; segredo pendente nunca autentica | Task 4 (testes) e Task 3 (`acceptStep` exige `totp_enabled_at is not null`) |
| b) Chave sem padrão no `prod`; subida falha se ausente ou inválida; valor fictício só em local e testes com `gitleaks:allow`; AES-GCM com nonce aleatório e o id como AAD; ADR registra que trocar a chave exige refazer o MFA | Tasks 2 e 5 |
| c) Último passo aceito, atualizado só se o novo for maior, em um `UPDATE` condicional; teste com duas requisições paralelas | Task 3 (`AdminMfaReplayTest`) e Task 4 |
| d) Falhas de código contam no limite por IP compartilhado; pendente expira em 5 min | Task 3 |
| e) Um teste por rota provando `401` do estado pendente nas rotas do painel | Task 3 (`AdminMfaBypassTest`) e Task 4 |
| f) Bouncy Castle é dependência **direta** do `pom.xml` (`bcprov-jdk18on` 1.86, sem escopo): pode ser usado | Conferido em 06/11/2026 |
| g) e2e: chave do CI com `openssl rand` e `::add-mask::`; segredo local em `.tmp/e2e`, recusa fora de localhost, nada na saída, no relatório, no trace nem nos logs | Task 5 (CI) e Task 7 |
| h) Nenhum log, exceção ou relatório com código, segredo ou chave; teste de captura de log | Task 5 (`AdminMfaLogTest`) |
| i) Remover a trava e o `-e` do `ci.yml`; atualizar o ADR-0004 nos 6 pontos | Tasks 3 e 5 |

Textos das telas: **aguardam aprovação** (seção "Textos que o usuário vê"). Nenhuma tela é implementada antes disso.
