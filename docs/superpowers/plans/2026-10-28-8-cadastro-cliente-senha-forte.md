# Cadastro de Cliente com senha forte — Plano de implementação

> **Para quem executar:** use superpowers:subagent-driven-development (recomendado) ou superpowers:executing-plans. Os passos usam checkbox (`- [ ]`).

**Objetivo:** o Visitante cria uma conta de Cliente com e-mail e senha; a senha tem no mínimo 12 caracteres, é recusada se já vazou (Have I Been Pwned) e é guardada com Argon2id; a conta nasce sem e-mail confirmado.

**Abordagem:** um endpoint público `POST /api/customers` que responde sempre `202` (igual para e-mail novo e repetido) e uma tela em `/cadastro`. A regra de senha mora em um só lugar: limites de tamanho nos `record`s de entrada (viram OpenAPI e schema Zod) e a checagem de vazamento no service, com o verificador que o próprio Spring Security já traz. O backend devolve **códigos** de erro (`PASSWORD_COMPROMISED`...) e o frontend traduz para o texto da tela.

**Stack:** Java 25, Spring Boot 4.1.2 (Spring Security 7.1.2), PostgreSQL 18 (Testcontainers), Flyway, JUnit 5, Vitest, Playwright, React Hook Form + Zod.

**Spec:** SEC-002, SEC-003 e SEC-033 (`docs/spec/sapiensia-spec.md:335`, `:338`, `:432`); ADR-0004 (`docs/adr/0004-identidade-e-mfa.md`). Issue #8, filha de #5.

## Fatos conferidos na fonte oficial (28/10/2026)

- **Argon2id:** `org.springframework.security.crypto.argon2.Argon2PasswordEncoder(int saltLength, int hashLength, int parallelism, int memory, int iterations)`, com `memory` em KiB. A documentação do Spring diz que a implementação "requires BouncyCastle". O Spring Boot **não** gerencia a versão do BouncyCastle (conferido no `effective-pom`).
- **BouncyCastle:** `org.bouncycastle:bcprov-jdk18on:1.86`, a mais recente do Maven Central (metadados atualizados em 11/09/2026, bem mais de 7 dias, cooldown do modelo atendido).
- **Parâmetros:** o padrão do Spring (`defaultsForSpringSecurity_v5_8`) usa memória `1 << 14` (16 MiB), **abaixo** do mínimo da OWASP para Argon2id: m=19456 KiB (19 MiB), t=2, p=1. Uso o mínimo da OWASP (cheat sheet de armazenamento de senhas).
- **Verificador de vazamento:** `org.springframework.security.web.authentication.password.HaveIBeenPwnedRestApiPasswordChecker` (já está no `spring-security-web`; **nenhuma dependência nova para o HIBP**). Tem `setRestClient(RestClient)`. Lê o `bytecode`: chama `https://api.pwnedpasswords.com/range/` + 5 primeiros caracteres do SHA-1, captura `RestClientException`, registra "Request for leaked passwords failed" no log e **segue sem recusar** (deixa passar). Usa o `RestClient` padrão, **sem tempo-limite**: é por isso que o plano injeta um com tempo-limite.
- **API do HIBP:** `GET https://api.pwnedpasswords.com/range/{5 primeiros caracteres do SHA-1}`, resposta com linhas `SUFIXO:CONTAGEM`, sem chave de API e sem limite de requisições; o cabeçalho `Add-Padding: true` completa a resposta com entradas de contagem 0, que devem ser ignoradas.
- **CSRF:** a documentação do `csrf.spa()` **não** diz se o cookie `XSRF-TOKEN` chega na primeira resposta de um GET (o token é carregado sob demanda). Como o cadastro é o primeiro POST de um visitante anônimo, o Task 3 tem um teste que decide isso.
- **Convenção de nomes:** código em inglês, pacote `br.com.sergio.sapiensia.customer` (Cliente = `Customer` no GLOSSARY).

## Decisões que dependem do seu OK

1. **Dependência nova:** `org.bouncycastle:bcprov-jdk18on:1.86` (necessária para o Argon2id). Nenhuma outra: o HIBP usa o verificador do Spring Security e o `RestClient` que já existem.
2. **HIBP fora do ar ou lento: o que fazer?** Recomendo **deixar o cadastro passar** (é o comportamento do verificador do Spring; a conta só dá acesso a PDFs que o Cliente já pagou, e uma queda de um serviço de terceiros não deve parar as vendas), com tempo-limite de 2 s para conectar e 3 s para ler, e o erro registrado no log. A alternativa é recusar com "tente novamente", o que exige código novo e um texto novo.
3. **Regra de senha (regra de negócio):** mínimo **12** e máximo **128** caracteres (o máximo evita que alguém mande um texto enorme só para gastar CPU do Argon2; 128 é o teto do ASVS), sem exigir símbolo, número ou maiúscula. Argon2id com m=19456 KiB, t=2, p=1 (mínimo da OWASP; a calibração fina no servidor de produção fica para o ticket da produção, #43).
4. **E-mail repetido não revela nada:** o endpoint responde `202` e a mesma mensagem nos dois casos, e o cadastro de um e-mail que já existe é ignorado em silêncio (sem erro, sem e-mail). O e-mail é guardado em minúsculas e sem espaços nas pontas, então `Ana@Example.com` e `ana@example.com` são o mesmo e-mail.
5. **Rota pública em `/api`:** `POST /api/customers` precisa de `permitAll`, que o CLAUDE.md só aceita com ADR. Proponho **atualizar o ADR-0004** (identidade) com as consequências: cadastro público, regra de senha e fail-open do HIBP. Sem ADR novo.
6. **Este ticket não envia e-mail** (o provedor e a confirmação são do #9). A conta nasce com `email_confirmed_at` vazio. O texto de sucesso abaixo promete um e-mail que só passa a existir com o #9.
7. **A tela fica em `/cadastro` sem link na home**, porque ainda não existe menu ou cabeçalho (virão com o Catálogo, #20).

## Textos que o usuário vai ver (todos, para o seu OK)

| Onde | Texto |
|---|---|
| Título da página (h1) | Criar conta |
| Rótulo do campo | E-mail |
| Rótulo do campo | Senha |
| Dica sob a senha | Use pelo menos 12 caracteres. |
| Botão | Criar conta |
| Botão enquanto envia | Criando conta… |
| E-mail vazio ou inválido (também o código `EMAIL_INVALID`) | Informe um e-mail válido. |
| Senha vazia ou curta (também `PASSWORD_REQUIRED` e `PASSWORD_TOO_SHORT`) | A senha precisa ter pelo menos 12 caracteres. |
| Senha longa demais (`PASSWORD_TOO_LONG`) | A senha pode ter no máximo 128 caracteres. |
| Senha vazada (`PASSWORD_COMPROMISED`) | Essa senha já apareceu em vazamentos de dados. Escolha outra. |
| Erro inesperado (rede, 500) | Não foi possível criar a conta agora. Tente novamente em instantes. |
| Sucesso (substitui o formulário) | Cadastro recebido. Verifique seu e-mail para continuar. |

## Global Constraints

- Código, nomes, commits e comentários em inglês; textos da tela em português.
- Commits Conventional Commits, terminando com `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`. Nunca na `main`; push e PR só com o OK do Sérgio.
- Toda chamada ao backend passa por `apiFetch` (`frontend/src/lib/api.ts`).
- Controller e Repository package-private; DTOs como `record`; entidade JPA nunca sai do service; injeção por construtor.
- Teste de integração com `@Import(TestcontainersConfiguration.class)`, sem `@MockitoBean`, sem container novo. Classes de teste terminam em `Test`. Teste que prova requisito leva o ID no `@DisplayName` (ex.: `"SEC-003: ..."`).
- Nunca registrar em log senha, e-mail completo ou o hash.
- Antes de commitar Java: `just fmt`. Saída longa: `just check-resumo`.
- Fora deste ticket: confirmação de e-mail e provedor de e-mail (#9), login/logout (#10), bloqueio por tentativas e limite por IP (#11), limite de requisições geral (#42), recuperação de senha (#12).

## Review Focus

Entradas que a spec não cita mas que um Visitante vai mandar (cada uma tem teste no task indicado):

1. E-mail com maiúsculas e espaços nas pontas (`" Ana@Example.COM "`) contra um já cadastrado como `ana@example.com`: deve ser tratado como repetido, sem erro (Task 2).
2. Corpo sem campos, `{}`, JSON quebrado ou e-mail com mais de 254 caracteres: deve dar `400` com códigos, nunca `500` (Task 3). Um campo ausente passa pelo `@Size` se não houver `@NotBlank`.
3. HIBP lento, fora do ar ou devolvendo `500`: o cadastro não trava e não quebra (Task 1).
4. Dois cadastros simultâneos com o mesmo e-mail: um só registro e nenhum `500` (Task 2).
5. Senha com acentos (`ááááááááááá`, 11 letras): conta caracteres, não bytes; 11 é recusada e 12 é aceita (Task 3).

---

### Task 1: Argon2id e verificador de senhas vazadas

**Files:**
- Modify: `backend/pom.xml` (dependência `org.bouncycastle:bcprov-jdk18on:1.86`)
- Create: `backend/src/main/java/br/com/sergio/sapiensia/security/PasswordSecurityConfig.java`
- Test: `backend/src/test/java/br/com/sergio/sapiensia/security/PasswordSecurityConfigTest.java`

**Interfaces:**
- Produces: bean `PasswordEncoder passwordEncoder()` (um `Argon2PasswordEncoder(16, 32, 1, 19456, 2)`) e bean `CompromisedPasswordChecker compromisedPasswordChecker(RestClient.Builder builder)` (um `HaveIBeenPwnedRestApiPasswordChecker` com `RestClient` de tempo-limite 2 s para conectar e 3 s para ler e o cabeçalho padrão `Add-Padding: true`). Os dois são `@Bean` de uma `@Configuration` package-private.

- [ ] **Step 1: Escrever os testes que falham** em `PasswordSecurityConfigTest` (JUnit puro, sem Spring, sem banco). Constrói a configuração e chama os métodos de bean direto; para o HIBP, usa `RestClient.Builder` ligado a `MockServerRestClientCustomizer`/`MockRestServiceServer.bindTo(builder)`:
  - `@DisplayName("SEC-003: a senha é guardada em Argon2id com os parâmetros mínimos da OWASP")`: `encode("correct horse battery")` começa com `$argon2id$v=19$m=19456,t=2,p=1$`, não contém a senha, e `matches` é verdadeiro para a senha certa e falso para outra.
  - `@DisplayName("SEC-002: só os 5 primeiros caracteres do SHA-1 saem para o HIBP")`: para a senha `password` (SHA-1 `5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8`), o servidor simulado espera exatamente `GET https://api.pwnedpasswords.com/range/5BAA6` com o cabeçalho `Add-Padding: true`; resposta com a linha `1E4C9B93F3F0682250B6CF8331B7EE68FD8:3861493`; `check("password").isCompromised()` é `true`.
  - `@DisplayName("SEC-002: entradas de preenchimento (contagem 0) são ignoradas")`: a resposta tem o sufixo da senha com `:0`; `isCompromised()` é `false`.
  - `@DisplayName("SEC-002: senha que não está na lista não é vazada")`: a resposta não tem o sufixo; `false`.
  - `@DisplayName("SEC-002: HIBP com erro 500 não derruba o cadastro")`: a resposta é `500`; `check(...)` não lança e devolve `false`.

- [ ] **Step 2: Rodar e ver falhar.** Run: `cd backend; .\mvnw.cmd test -Dtest=PasswordSecurityConfigTest`. Esperado: erro de compilação (a classe de configuração não existe).

- [ ] **Step 3: Adicionar a dependência e implementar `PasswordSecurityConfig`** com os dois beans acima. O `RestClient` usa `JdkClientHttpRequestFactory` com `HttpClient` de `connectTimeout(2s)` e `setReadTimeout(3s)`. Rode `just fmt`.

- [ ] **Step 4: Rodar e ver passar.** Run: `cd backend; .\mvnw.cmd test -Dtest=PasswordSecurityConfigTest`. Esperado: 5 testes, 0 falhas.

- [ ] **Step 5: Commit.** `git add backend/pom.xml backend/src; git commit` com `feat: add Argon2id encoder and HIBP leaked-password checker`.

---

### Task 2: Tabela de Clientes e o cadastro no service

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__create_customers.sql` (número confirmado pela skill `nova-migration`)
- Create: `backend/src/main/java/br/com/sergio/sapiensia/customer/Customer.java` (entidade, package-private), `CustomerRepository.java` (package-private), `CustomerService.java` (público), `PasswordCompromisedException.java` (público)
- Modify: `backend/src/test/java/br/com/sergio/sapiensia/TestcontainersConfiguration.java` (verificador falso)
- Test: `backend/src/test/java/br/com/sergio/sapiensia/customer/CustomerServiceTest.java`

**Interfaces:**
- Consumes: `PasswordEncoder` e `CompromisedPasswordChecker` do Task 1.
- Produces: `public void CustomerService.register(String email, String rawPassword)`. Normaliza o e-mail (`trim` + minúsculas), lança `PasswordCompromisedException` se o verificador acusar vazamento, calcula o hash **antes** de olhar se o e-mail existe (assim o tempo de resposta não revela se a conta existe), ignora em silêncio e-mail repetido (checagem `existsByEmail` e também a violação de unicidade do banco, para a corrida), e grava com `email_confirmed_at` vazio.
- Tabela `customers`: `id uuid` (PK, `gen_random_uuid()`), `email varchar(254) not null unique`, `password_hash varchar(255) not null`, `email_confirmed_at timestamptz` (nulo), `created_at timestamptz not null default now()`. Nada além disso (SEC-033).
- No `TestcontainersConfiguration`: um `@Bean @Primary CompromisedPasswordChecker` falso que acusa vazamento só para a senha `Password123456`. Assim os testes de integração não chamam a internet e continuam compartilhando um único contexto.

- [ ] **Step 1: Escrever os testes que falham** em `CustomerServiceTest` (`@SpringBootTest` + `@Import(TestcontainersConfiguration.class)`, `JdbcTemplate` para conferir o banco; limpar a tabela `customers` antes de cada teste):
  - `@DisplayName("SEC-003: o banco guarda Argon2id, nunca a senha")`: depois de `register("ana@example.com", "uma senha longa 1")`, a coluna `password_hash` começa com `$argon2id$`, não contém a senha, e `passwordEncoder.matches(...)` é verdadeiro.
  - `@DisplayName("SEC-002: senha vazada é recusada e nada é gravado")`: `register(..., "Password123456")` lança `PasswordCompromisedException`; `count(*)` de `customers` é 0.
  - `@DisplayName("SEC-033: a tabela só guarda o necessário")`: as colunas de `customers` em `information_schema` são exatamente `id`, `email`, `password_hash`, `email_confirmed_at`, `created_at`.
  - `shouldCreateAccountWithUnconfirmedEmail`: `email_confirmed_at` é nulo.
  - `@DisplayName("SEC-002: e-mail repetido, mesmo com maiúsculas e espaços, é ignorado sem erro")`: `register("ana@example.com", ...)` e depois `register("  Ana@Example.COM ", ...)` não lançam; continua 1 linha e a senha original não muda.
  - `shouldKeepOneRowWhenSameEmailRegistersConcurrently`: 8 threads com um `CountDownLatch` chamam `register` com o mesmo e-mail; nenhuma lança; `count(*)` é 1.

- [ ] **Step 2: Rodar e ver falhar.** Run: `cd backend; .\mvnw.cmd test -Dtest=CustomerServiceTest`. Esperado: erro de compilação (as classes não existem).

- [ ] **Step 3: Criar a migration com a skill `nova-migration`, a entidade, o repositório (`boolean existsByEmail(String)`), a exceção e o service.** O `save` do repositório já é transacional; o service captura `DataIntegrityViolationException` fora dessa transação para tratar a corrida sem marcar nada como `rollback-only`. Adicionar o verificador falso ao `TestcontainersConfiguration`. Rode `just fmt`.

- [ ] **Step 4: Rodar e ver passar.** Run: `cd backend; .\mvnw.cmd test -Dtest=CustomerServiceTest`. Esperado: 6 testes, 0 falhas. Rode também `.\mvnw.cmd test -Dtest=SapiensiaApplicationTests` (o contexto sobe só com as migrations).

- [ ] **Step 5: Commit.** `feat: store customers with Argon2id password hashes`.

---

### Task 3: Endpoint público `POST /api/customers`

**Files:**
- Create: `backend/src/main/java/br/com/sergio/sapiensia/customer/CustomerController.java` (package-private), `RegisterCustomerRequest.java` (`record`)
- Create: `backend/src/main/java/br/com/sergio/sapiensia/web/ApiExceptionHandler.java` (`@RestControllerAdvice`)
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/security/SecurityConfig.java:30-36` (liberar o POST)
- Modify: `docs/adr/0004-identidade-e-mfa.md` (consequências)
- Test: `backend/src/test/java/br/com/sergio/sapiensia/customer/CustomerControllerTest.java`

**Interfaces:**
- Consumes: `CustomerService.register` e `PasswordCompromisedException` do Task 2.
- Produces: `POST /api/customers` com `RegisterCustomerRequest(String email, String password)`:
  - `email`: `@NotBlank(message = "EMAIL_INVALID")`, `@Email(message = "EMAIL_INVALID")`, `@Size(max = 254, message = "EMAIL_INVALID")`
  - `password`: `@NotBlank(message = "PASSWORD_REQUIRED")`, `@Size(min = 12, message = "PASSWORD_TOO_SHORT")`, `@Size(max = 128, message = "PASSWORD_TOO_LONG")`
  - Resposta de sucesso: `202 Accepted`, sem corpo, **igual** para e-mail novo e repetido.
  - Erro: `400` em `ProblemDetail` com a propriedade `errors`, um objeto `{campo: CÓDIGO}` (ex.: `{"password":"PASSWORD_COMPROMISED"}`). O `ApiExceptionHandler` estende `ResponseEntityExceptionHandler`, preenche `errors` para falha de validação e converte `PasswordCompromisedException` em `400` com `errors.password = PASSWORD_COMPROMISED`.
  - `SecurityConfig` ganha `.requestMatchers(HttpMethod.POST, "/api/customers").permitAll()` antes do `denyAll` de `/api/**`.

- [ ] **Step 1: Escrever os testes que falham** em `CustomerControllerTest` (`@SpringBootTest` + MockMvc com `springSecurity()`, como o `WebSecurityTest`; POST sempre `.with(csrf())`, exceto onde dito):
  - `@DisplayName("SEC-002: senha com 11 caracteres é recusada com PASSWORD_TOO_SHORT")` → `400` e `$.errors.password == "PASSWORD_TOO_SHORT"`.
  - `@DisplayName("SEC-002: senha com 12 caracteres e sem símbolos é aceita")` (`"senhalongaok"`) → `202`.
  - `@DisplayName("SEC-002: senha vazada é recusada com PASSWORD_COMPROMISED")` (`Password123456`) → `400`, `$.errors.password == "PASSWORD_COMPROMISED"`.
  - `@DisplayName("SEC-002: senha com acentos conta caracteres, não bytes")`: `ááááááááááá` (11) → `400`; `áááááááááááá` (12) → `202`.
  - `shouldRejectPasswordLongerThan128Characters` → `PASSWORD_TOO_LONG`.
  - `@DisplayName("SEC-002: e-mail repetido responde igual ao e-mail novo")`: dois POSTs com o mesmo e-mail dão o mesmo `status` e o mesmo corpo (vazio).
  - `shouldRejectEmptyBodyWithCodesNotServerError`: `{}` → `400` com `errors.email == "EMAIL_INVALID"` e `errors.password == "PASSWORD_REQUIRED"`; JSON quebrado → `400`; e-mail com 255 caracteres → `400 EMAIL_INVALID`.
  - `shouldAllowAnonymousPostWithCsrfToken` e `@DisplayName("a POST sem token CSRF continua recusado")` → `403`.
  - `@DisplayName("o cookie XSRF-TOKEN chega na primeira visita à tela de cadastro")`: um `GET /cadastro` anônimo (sem nenhum POST antes) devolve um `Set-Cookie: XSRF-TOKEN=...`.

- [ ] **Step 2: Rodar e ver falhar.** Run: `cd backend; .\mvnw.cmd test -Dtest=CustomerControllerTest`. Esperado: erro de compilação / `403` no POST (rota ainda negada).

- [ ] **Step 3: Implementar controller, `record`, advice e a regra no `SecurityConfig`.** Rode `just fmt`. Se **só** o teste do cookie `XSRF-TOKEN` continuar falhando, acrescente ao `SecurityConfig` um filtro pequeno depois do `CsrfFilter` que lê o atributo de requisição `CsrfToken` e chama `getToken()` para forçar a gravação do cookie (o token é carregado sob demanda), e explique isso em um comentário de uma linha.

- [ ] **Step 4: Atualizar o ADR-0004** com três linhas em "Consequences": o cadastro de Cliente é público (`POST /api/customers` liberado no `SecurityConfig`); a regra de senha (12 a 128 caracteres, sem exigir símbolo, HIBP, Argon2id m=19456 KiB, t=2, p=1); e que, se o HIBP estiver fora do ar, o cadastro segue (com erro no log). Texto do ADR conforme a decisão 2 que você tomar.

- [ ] **Step 5: Rodar e ver passar.** Run: `cd backend; .\mvnw.cmd test -Dtest=CustomerControllerTest,WebSecurityTest`. Esperado: 0 falhas (o `WebSecurityTest` prova que o resto de `/api` segue negado).

- [ ] **Step 6: Commit.** `feat: add public customer registration endpoint`.

---

### Task 4: Tela de cadastro

**Files:**
- Create (gerados): `frontend/src/components/ui/input.tsx`, `frontend/src/components/ui/label.tsx` (comando `pnpm exec shadcn add input label` dentro de `frontend/`; usam o `@base-ui/react` que já está instalado, sem pacote novo)
- Create: `frontend/src/features/customer/schemas.ts`, `api.ts`, `hooks.ts`, `RegisterPage.tsx`
- Modify: `frontend/src/main.tsx` (envolver em `BrowserRouter`), `frontend/src/App.tsx` (rotas `/` e `/cadastro`), `frontend/src/App.test.tsx` (renderizar dentro de `MemoryRouter`)
- Test: `frontend/src/features/customer/RegisterPage.test.tsx`

**Interfaces:**
- Produces:
  - `schemas.ts`: `registerCustomerSchema` (Zod) com `email` (e-mail válido) e `password` (12 a 128 caracteres, as mesmas regras e os mesmos textos da tabela), e `type RegisterCustomerInput = z.infer<typeof registerCustomerSchema>`. Um `errorMessages: Record<string, string>` que traduz os códigos do backend para os textos da tabela (`EMAIL_INVALID`, `PASSWORD_REQUIRED`, `PASSWORD_TOO_SHORT`, `PASSWORD_TOO_LONG`, `PASSWORD_COMPROMISED`).
  - `api.ts`: `registerCustomer(input: RegisterCustomerInput): Promise<void>` por `apiFetch("/api/customers", { method: "POST", ... })`; `202` resolve; `400` lança `RegisterCustomerError` com `fieldErrors: Record<string, string>` (o `errors` do backend); qualquer outro status lança `Error`.
  - `hooks.ts`: `useRegisterCustomer()` (`useMutation` do TanStack Query). O `QueryClientProvider` entra em `main.tsx` (hoje não existe).
  - `RegisterPage` (export default), rota `/cadastro`: campos com `<Label>`, `autoComplete="email"` e `autoComplete="new-password"`, `type="password"`; erros por campo com `role="alert"`; botão desabilitado e com o texto "Criando conta…" enquanto envia; sucesso troca o formulário pela mensagem (`role="status"`).

- [ ] **Step 1: Escrever os testes que falham** em `RegisterPage.test.tsx` (Vitest + Testing Library + `userEvent`; a rede simulada com `vi.spyOn(globalThis, "fetch")`; renderizar dentro de `MemoryRouter` e `QueryClientProvider`):
  - `SEC-002: não envia o formulário com senha curta e mostra o motivo` — digita e-mail válido e `curta`; clica "Criar conta"; aparece "A senha precisa ter pelo menos 12 caracteres." e `fetch` não foi chamado.
  - `mostra "Informe um e-mail válido." para e-mail inválido`.
  - `envia e mostra a mensagem de sucesso` — `fetch` devolve `202`; o corpo enviado é `{"email": "...", "password": "..."}` para `POST /api/customers`; aparece "Cadastro recebido. Verifique seu e-mail para continuar." e o formulário some.
  - `SEC-002: mostra a mensagem de senha vazada no campo` — `fetch` devolve `400` com `{"errors":{"password":"PASSWORD_COMPROMISED"}}`; aparece "Essa senha já apareceu em vazamentos de dados. Escolha outra." perto do campo Senha.
  - `mostra erro amigável quando a API falha` — `fetch` devolve `500`; aparece "Não foi possível criar a conta agora. Tente novamente em instantes."
  - `desabilita o botão e mostra "Criando conta…" enquanto envia` — `fetch` pendente; botão com o novo nome e `disabled`.
  - `os campos são achados pelo rótulo` — `getByLabelText("E-mail")` e `getByLabelText("Senha")`; o campo de senha tem `type="password"`.

- [ ] **Step 2: Rodar e ver falhar.** Run: `cd frontend; pnpm exec vitest run src/features/customer` (de dentro de `frontend/`). Esperado: falha por falta dos arquivos.

- [ ] **Step 3: Gerar `input` e `label` com a CLI do shadcn, criar `schemas.ts`, `api.ts`, `hooks.ts`, `RegisterPage.tsx`, e ligar o roteamento** (`BrowserRouter` em `main.tsx`, `Routes` em `App.tsx`, `QueryClientProvider` em `main.tsx`). Atualizar `App.test.tsx` para renderizar dentro de `MemoryRouter`. Rode `just fmt`.

- [ ] **Step 4: Rodar e ver passar.** Run: `cd frontend; pnpm exec vitest run`. Esperado: todos os testes passam (os 3 anteriores continuam).

- [ ] **Step 5: Commit.** `feat: add customer registration screen`.

---

### Task 5: Fluxo de ponta a ponta e verificações

**Files:**
- Create: `frontend/e2e/customer-registration.e2e.ts`

- [ ] **Step 1: Escrever o teste e2e** (contra API e banco de verdade, sem `page.route` e sem mocks), com e-mail único por execução (`cliente-${Date.now()}@example.com`) e senha forte aleatória:
  - `SEC-002/SEC-033: o Visitante cria a conta pelo navegador` — abre `/cadastro`, preenche e envia, vê "Cadastro recebido. Verifique seu e-mail para continuar." Isso prova também o cookie e o cabeçalho CSRF no navegador de verdade.
  - `e-mail repetido mostra a mesma mensagem de sucesso` — repete o mesmo e-mail e vê exatamente a mesma mensagem.
  - O caso "senha vazada" **não** entra no e2e (dependeria da internet e do HIBP de verdade); já está coberto no Task 1 (HIBP simulado), no Task 3 (400) e no Task 4 (mensagem).

- [ ] **Step 2: Subir o sistema de verdade e rodar.** Na pasta principal: `just db`. Na worktree: `just dev-back`, `just dev-front` e então `just e2e`. Esperado: os testes novos e o smoke test passam. Se o teste do cookie do Task 3 exigiu o filtro CSRF, aqui se confirma no navegador.

- [ ] **Step 3: Verificar a tela** com `/verificar-tela` (desktop ~1280 px, celular ~390 px, teclado).

- [ ] **Step 4: `just check-resumo`** e depois a skill `verificar-entrega`, mostrando a tabela dela.

- [ ] **Step 5: Commit** (`test: add customer registration e2e`) e, com o seu OK, push e PR com `Closes #8` na primeira linha, citando SEC-002, SEC-003 e SEC-033.

## Riscos

- **Cookie CSRF na primeira visita:** documentação silenciosa; o teste do Task 3 e o e2e decidem. Pior caso: um filtro pequeno no `SecurityConfig`.
- **BouncyCastle 1.86 com Spring Security 7.1.2:** o Spring declara só "requer BouncyCastle", sem faixa de versão; o teste do Task 1 prova que o Argon2id funciona com a 1.86 no Java 25.
- **Sem limite de requisições no cadastro** até o #42/#11: alguém pode criar muitas contas e gastar CPU de Argon2. Não é tratado aqui por escopo; fica registrado.
- **Texto de sucesso promete e-mail** que só existe depois do #9 (decisão 6).
- **Dados de teste no banco local:** o e2e cria contas `@example.com` no banco de desenvolvimento; elas não saem do computador e não vão para o git.
