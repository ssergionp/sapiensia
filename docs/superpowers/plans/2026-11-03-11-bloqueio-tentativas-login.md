# Bloqueio por tentativas e limite por IP no login (#11) — Plano de implementação

> Para quem executa: use `superpowers:subagent-driven-development`. Os passos usam caixas (`- [ ]`).

**Objetivo:** 5 senhas erradas em 15 minutos bloqueiam o login daquela conta por 15 minutos, e um limite de tentativas por IP vale separado do bloqueio da conta. A resposta de bloqueio nunca revela se a conta existe.

**Arquitetura:** o `SessionController` (login manual, já existente) consulta um `LoginAttemptLimiter` antes de conferir a senha. O limiter guarda, na memória do servidor, as falhas por e-mail normalizado (regra escrita à mão, porque o ticket pede "bloqueio por 15 minutos a partir da 5ª falha") e um balde do Bucket4j por IP. Bloqueado, o login responde `429` sem calcular o hash.

**Tecnologias:** Spring Boot 4.1.2, Java 25, Bucket4j (`com.bucket4j:bucket4j_jdk17-core:8.21.0`), JUnit 5, Vitest.

**Requisitos:** SEC-002 (limitação de tentativas), SEC-014 (rate limiting por IP e por conta). Spec: `docs/spec/sapiensia-spec.md:335` e `:372`; ADR 0004. Issue #11, filha de #5.
Ficam fora: recuperação de senha (#12), aviso de login novo (#13), limites gerais de requisição e erros públicos (#42), alertas ao Administrador (#38).

## Decisões (aguardam o seu OK)

1. **Contadores na memória do servidor (recomendado).** Sem migration e sem dependência além do Bucket4j; é o mesmo desenho da sessão (ADR 0004: uma instância por ambiente). O custo: um reinício ou deploy zera os bloqueios. A alternativa é uma tabela no PostgreSQL, que sobrevive ao reinício mas pede migration, consulta a cada login e limpeza; só se paga com mais de uma instância.
2. **Limite por IP: 20 tentativas por 15 minutos, contando todas** (certas e erradas, de qualquer e-mail). É regra de negócio, o ticket não dá o número. Risco: um escritório ou rede móvel que divide um IP pode esgotar o limite; 20 deixa folga para uso normal.
3. **IP real atrás do Fly.io: ler um cabeçalho configurável** (`app.login-limit.client-ip-header`; vazio em desenvolvimento e nos testes, `Fly-Client-IP` em produção, a confirmar na doc do Fly na Task 1). Sem cabeçalho configurado, usa o endereço da conexão. Não usar `X-Forwarded-For`: o cliente pode escrever nele.
4. **Qualquer 401 conta como erro da conta**: senha errada, e-mail inexistente e e-mail não confirmado. A chave é o e-mail normalizado (minúsculas, sem espaços), mesmo que a conta não exista; se só contas reais fossem bloqueadas, o `429` revelaria que a conta existe. O custo, que aceito: quem souber o e-mail de um Cliente pode bloqueá-lo por 15 minutos, repetidamente; só o limite por IP segura isso. Um login certo zera as falhas da conta.
5. **Bucket4j na versão 8.21.0** (2/10/2026), e não a 8.22.0, publicada hoje. Dependência nova, sem dependências próprias; o ticket já a prevê, mas peço o OK pela regra global.
6. **Incluir, vindo da #53, a recusa com `401` antes do hash** de senha com mais de 128 caracteres e e-mail com mais de 254 (o cadastro nunca aceita esses tamanhos, então nunca conferem). Fora dos critérios do ticket; são poucas linhas e protegem do mesmo abuso (Argon2id de ~19 MiB por tentativa). Diga se prefere deixar para o #42.
7. **O bloqueio é fixo (5 erros, 15 minutos), não progressivo.** O SEC-002 diz "limitação progressiva", mas o critério do ticket é fixo; sigo o ticket. Prazos mais longos para quem insiste seriam uma regra nova, que fica para outro ticket se você quiser.

## Fatos conferidos (3/11/2026)

- Bucket4j 8.21.0 (jar baixado, `javap`): `Bucket.builder().addLimit(Bandwidth.simple(long, Duration)).withCustomTimePrecision(TimeMeter).build()`, `bucket.tryConsumeAndReturnRemaining(1)` com `isConsumed()` e `getNanosToWaitForRefill()`. `TimeMeter` é interface com `currentTimeNanos()` e `isWallClockBased()`. O pom do artefato não declara dependências. `Bandwidth.simple` pode estar marcado como obsoleto; se estiver, usar `addLimit(Function)` (builder novo).
- O login é o `SessionController`: `POST /api/sessions`, resposta `401` vazia e idêntica nos três tipos de falha, `204` no sucesso.
- Não existe bean `Clock` no projeto. O `application.properties` de teste precisa de todas as propriedades `app.*`.
- O CI sobe a imagem de produção com `-e` (`.github/workflows`, job `e2e`); sem cabeçalho de proxy, todo o e2e chega com o mesmo IP.

## Fato conferido na Task 1 (3/11/2026): Fly-Client-IP

- A documentação (docs.fly.io/networking/request-headers) confirma que o `Fly-Client-IP` existe e é posto pelo Fly Proxy ("o endereço IP do cliente do ponto de vista do Fly Proxy"). **Ela não diz** que o proxy sobrescreve um valor enviado pelo cliente; só posts não oficiais de fórum dizem que não dá para forjar.
- Por isso `application-prod.yml` ficou com o cabeçalho em branco (a decisão 3 só manda configurá-lo com a confirmação). O código lê o cabeçalho de qualquer jeito; ligar é uma linha no yml.
- **Decisão em aberto para o Sérgio, antes do deploy:** ligar `Fly-Client-IP` (risco: se o Fly não sobrescrever, o limite por IP pode ser contornado; o bloqueio da conta continua valendo) ou confirmar com o suporte do Fly. Em branco, todos os Clientes dividem o IP do proxy e os mesmos 20 de 15 minutos.

## Rotas

Nenhuma rota nova. `POST /api/sessions` (pública) ganha uma resposta: `429`, corpo vazio, cabeçalho `Retry-After` em segundos, sem cookie. Não há ID de objeto, então o teste negativo de "outro usuário recebe 404" não se aplica.

## Textos que o usuário vai ver (todos, para o seu OK)

Só um texto novo, na tela `/entrar`, no mesmo lugar da mensagem de falha:
- Bloqueio (conta ou IP, sem distinguir): **"Muitas tentativas de entrada. Aguarde alguns minutos e tente de novo."**

Não mostro quantas tentativas restam nem por quanto tempo (revelaria se a conta existe). Os outros textos do login não mudam. Qualquer texto fora desta lista volta para o Sérgio antes do push.

## Global Constraints

- `429` idêntico para conta bloqueada, e-mail inexistente bloqueado e IP bloqueado (status, corpo vazio, sem cookie).
- Bloqueado, o login não calcula o hash.
- Nunca em log: senha, e-mail, IP ou o corpo do login. O IP e o e-mail só existem na memória, como chave do limiter.
- Janela das falhas, duração do bloqueio, número de falhas e o limite por IP vêm de `app.login-limit.*` (valores acima como padrão), para o e2e poder subir o limite do IP.
- Sem migration. Toda chamada do frontend passa por `apiFetch`.
- Teste que prova requisito leva o ID no `@DisplayName`.

## Review Focus

- Bloqueio de e-mail que não existe responde igual ao de e-mail que existe.
- Conta bloqueada recusa até a senha certa (senão o bloqueio vira oráculo).
- Cabeçalho de IP enviado pelo cliente é ignorado quando nenhum cabeçalho está configurado.
- E-mail com maiúsculas, espaços ou 300 caracteres cai na mesma chave/recusa.
- Memória: entradas vencidas somem; o mapa não cresce sem fim com e-mails e IPs aleatórios.

## Tarefas

### Task 1: Conferir o Fly.io e preparar a dependência
**Arquivos:** `backend/pom.xml`, `backend/src/main/resources/application.yml`, `application-prod.yml`, `backend/src/test/resources/application.properties`.
- [ ] Conferir na documentação do Fly.io o item "Fato ainda a conferir"; anotar o resultado em 3 linhas no topo deste plano. Se contradisser a decisão 3, parar.
- [ ] Adicionar `com.bucket4j:bucket4j_jdk17-core:8.21.0` ao `pom.xml` (só após o OK da decisão 5).
- [ ] Criar o bean `Clock` (`Clock.systemUTC()`) numa `@Configuration` de `security/` e o record `LoginLimitProperties` (`@ConfigurationProperties("app.login-limit")`) em `customer/` com: `accountMaxFailures` (5), `accountWindow` (`PT15M`), `accountLock` (`PT15M`), `ipMaxAttempts` (20), `ipWindow` (`PT15M`), `clientIpHeader` (vazio). Valores no `application.yml`; `client-ip-header: Fly-Client-IP` no `application-prod.yml` (conforme a Task 1); `ip-max-attempts` do e2e vem pelo ambiente (Task 5).

### Task 2: `LoginAttemptLimiter` e `ClientIpResolver` (TDD, testes unitários sem Spring)
**Arquivos:** `customer/LoginAttemptLimiter.java`, `customer/ClientIpResolver.java` (novos, package-private); testes `customer/LoginAttemptLimiterTest.java` e `ClientIpResolverTest.java` com um `Clock` mutável escrito no teste.

**Interfaces (produz, usado pela Task 3):**
- `LoginAttemptLimiter(LoginLimitProperties, Clock)`
- `Optional<Duration> blockedFor(String email, String clientIp)`: vazio = pode tentar. Gasta uma ficha do balde do IP a cada chamada; se o IP estourou ou a conta está bloqueada, devolve o tempo que falta (arredondado para cima, ao menos 1 s).
- `void recordFailure(String email)` e `void recordSuccess(String email)` (zera as falhas da conta).
- `int trackedKeys()` (só para o teste de memória).
- `ClientIpResolver(LoginLimitProperties)` com `String resolve(HttpServletRequest)`.

Testes primeiro:
- [ ] `SEC-002: shouldBlockAccountOnFifthFailureWithinWindow`: 4 falhas → `blockedFor` vazio; a 5ª → bloqueado por 15 min.
- [ ] `SEC-002: shouldUnblockAccountExactlyWhenLockEnds`: bloqueado em +14 min 59 s; livre em +15 min, e uma nova falha não bloqueia (contagem recomeça).
- [ ] `SEC-002: shouldIgnoreFailuresOlderThanWindow`: 4 falhas, +15 min, 1 falha → livre.
- [ ] `SEC-002: shouldResetFailuresAfterSuccess`: 4 falhas, sucesso, 4 falhas → livre.
- [ ] `SEC-002: shouldTreatEmailCaseAndSpacesAsTheSameAccount`: `Ana@Example.com ` e `ana@example.com` somam.
- [ ] `SEC-014: shouldLimitIpIndependentlyOfAccounts`: IP A com 20 e-mails diferentes (nenhuma conta chega a 5) → a 21ª é bloqueada; IP B livre; 5 falhas de uma conta vindas de 5 IPs bloqueiam a conta sem estourar nenhum IP.
- [ ] `SEC-014: shouldAllowIpAgainAfterWindow`: bloqueado; em +15 min volta a ter 20 tentativas.
- [ ] `shouldDropExpiredEntries`: depois de 15 min sem uso e de mais uma chamada, `trackedKeys()` volta a 0.
- [ ] `ClientIpResolverTest`: cabeçalho configurado e presente → o valor do cabeçalho (primeiro item, sem espaços); configurado e ausente → `getRemoteAddr()`; **não configurado e presente → `getRemoteAddr()`** (cabeçalho forjado é ignorado).

Implementação: mapa concorrente de e-mail → estado (falhas recentes e `lockedUntil`) e de IP → `Bucket` com `TimeMeter` que lê o `Clock`; limpeza das entradas vencidas feita dentro das chamadas, no máximo uma vez por minuto (sem agendador novo). Sincronizar por chave, não globalmente.

### Task 3: Ligar ao login (TDD, teste de integração)
**Arquivos:** `customer/SessionController.java`; teste novo `customer/LoginLockoutTest.java` com contexto próprio (`test.isolated-context=login-lockout`, `app.login-limit.ip-max-attempts=8`, `app.login-limit.client-ip-header=X-Test-Client-Ip`); mesmo estilo do `SessionControllerTest` (Testcontainers, CSRF real, contas criadas com `JdbcTemplate`).

Ordem no `login(...)`: (a) e-mail em branco, e-mail > 254 ou senha > 128 → `401` (decisão 6); (b) `blockedFor(email, clientIp)` presente → `429` com `Retry-After`; (c) autentica; falha de qualquer tipo → `recordFailure` e `401`; sucesso → `recordSuccess`.

Testes primeiro:
- [ ] `SEC-002: shouldAnswer429AfterFiveWrongPasswordsEvenWithTheCorrectPassword`: 5 × `401`, depois a senha certa → `429`, `Retry-After` > 0, sem `Set-Cookie`.
- [ ] `SEC-002: shouldBlockUnknownEmailTheSameWayAsAnExistingOne`: mesmos 5 + 1 com e-mail que não existe → `429`, corpo vazio, sem cookie, igual ao caso anterior.
- [ ] `SEC-002: shouldCountUnconfirmedAccountAsFailure`: conta não confirmada com a senha certa, 5 vezes → a 6ª é `429`.
- [ ] `SEC-002: shouldClearFailuresAfterSuccessfulLogin`: 4 erradas, 1 certa, 4 erradas → a última ainda é `401`.
- [ ] `SEC-014: shouldAnswer429AfterIpLimitWithAFreshEmail`: 8 tentativas com e-mails diferentes e `X-Test-Client-Ip: 10.0.0.1` → a 9ª (e-mail novo) é `429`; com `10.0.0.2` → `401`.
- [ ] `shouldAnswer401WithoutHashingForTooLongInputs`: senha de 129 caracteres e e-mail de 255 → `401` (decisão 6).
- [ ] `shouldNotLogEmailPasswordOrIpWhenBlocked`: captura de log como no `SessionControllerTest`; nenhum desses valores aparece.
- [ ] Os testes antigos do `SessionControllerTest` continuam passando (o limite padrão de 20 não os atrapalha; se atrapalhar, ajustar o contexto deles, não afrouxar o teste).

### Task 4: Frontend (TDD)
**Arquivos:** `frontend/src/features/session/api.ts`, `LoginPage.tsx`, `LoginPage.test.tsx`.
- [ ] Teste: `fetch` devolve `429` → aparece "Muitas tentativas de entrada. Aguarde alguns minutos e tente de novo."; o botão volta a ficar ativo; `401` continua mostrando a mensagem de falha antiga; `500` continua mostrando a de erro inesperado.
- [ ] `api.ts`: nova `LoginBlockedError` para `429` (como a `LoginRejectedError`); `LoginPage` escolhe o texto pelo tipo do erro.

### Task 5: Ponta a ponta e CI
**Arquivos:** `frontend/e2e/customer-login.e2e.ts`, `.github/workflows/` (job `e2e`), `backend/src/main/resources/application-local.yml` (se existir).
- [ ] Teste `SEC-002: depois de 5 senhas erradas a conta fica bloqueada, mesmo com a senha certa`: cadastra e confirma (mesmo método do arquivo), 5 senhas erradas mostram a mensagem de falha, a 6ª, com a senha certa, mostra a de bloqueio e a Home continua sem sessão. Sem `page.route`, sem mocks, e-mail único por projeto (desktop e celular).
- [ ] Os dois projetos do Playwright e os outros testes fazem muitos logins do mesmo IP: subir o limite por IP só no e2e (`-e APP_LOGIN_LIMIT_IP_MAX_ATTEMPTS=1000` no job do CI; no `just dev-back` local, o mesmo valor no perfil `local`, se esse arquivo existir). O limite do IP fica provado no teste de integração, não no e2e.

### Task 6: Documentação
- [ ] ADR 0004: uma frase na lista de consequências com a regra visível ao usuário (5 erros em 15 minutos bloqueiam a conta por 15 minutos; limite de 20 tentativas por IP; contadores na memória, zerados no reinício; bloqueio igual para conta existente ou não). Sem ADR novo.
- [ ] Depois da PR: comentar na #53 quais itens este ticket resolveu (limite de tentativas e senha longa) e quais não (sessão sem prazo absoluto etc.), sem fechá-la.

### Task 7: Verificação e entrega
- [ ] `/verificar-tela` na `/entrar` (desktop ~1280px, celular ~390px, teclado) com a mensagem de bloqueio.
- [ ] Rodar o sistema de verdade (banco, API e frontend; `just db` na pasta principal) e fazer o fluxo no navegador: 5 erradas, bloqueio, e-mail novo também bloqueado igual.
- [ ] `just check-resumo`, depois a skill `verificar-entrega` com a tabela.
- [ ] Push e PR (`Closes #11`, citando SEC-002 e SEC-014) só com o OK do Sérgio.

## Riscos

- **Bloquear de propósito o Cliente alheio** (decisão 4): quem sabe o e-mail trava a conta por 15 minutos, de novo e de novo. Só o limite por IP segura isso; vai na descrição da PR.
- **Reinício zera os contadores** (decisão 1): um atacante não consegue provocar o reinício, mas um deploy no meio de um ataque dá 5 tentativas novas por conta.
- **IP errado em produção**: se o cabeçalho do Fly não vier, o limiter usa o IP do proxy e todos os Clientes dividem os 20 de 15 minutos (login fora do ar para todos). Teste de resolução cobre o fallback; a conferência real é na homologação.
- **Memória**: com a limpeza por minuto e o limite de 20 por IP, o mapa só cresce com muitos IPs distintos (botnet). Teto global fica para o #42; aqui só se registra.
- **Sem bloqueio progressivo** (decisão 7), embora o SEC-002 use essa palavra.
- **Rede compartilhada**: vários Clientes atrás do mesmo IP somam no limite de 20 (decisão 2).
