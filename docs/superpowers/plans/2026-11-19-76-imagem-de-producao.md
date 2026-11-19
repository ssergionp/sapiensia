# A7 — Imagem de produção (#76 M8, #51, #94 item 3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A imagem que vai para homologação e produção sai sem os dados fictícios de `db/seed`, sem aceitar do cliente os cabeçalhos `X-Forwarded-Host`, `X-Forwarded-Prefix` e `Forwarded`, e com uma checagem no CI de que o log de arranque não traz a senha gerada do Spring.

**Architecture:** Três ajustes independentes. (1) O `maven-jar-plugin` deixa `db/seed/**` fora do jar e o `Dockerfile` recusa montar a imagem se o seed estiver lá. (2) Uma subclasse de `ForwardedHeaderFilter` esconde os cabeçalhos que o Fly Proxy não define antes de o Spring os ler; ela ocupa o lugar do filtro do Spring Boot, que se retira sozinho (`@ConditionalOnMissingFilterBean`). (3) O job do CI que já sobe a imagem com o perfil `prod` passa a falhar se o log tiver `generated security password`.

**Tech Stack:** Java 25, Spring Boot 4.1.2 (Spring Framework 7.0.9), Maven, Docker, GitHub Actions; JUnit 5.

**Spec:** issue #208 (A7); issue #76 (M8); issue #51 (item "A senha gerada pelo Spring aparece no log"); issue #94 (item 3). Requisito com ID: NFR-012 (seed só no local). Os outros dois itens não têm ID na spec.

**Processo:** COMPLETO (segurança). Branch `fix/76-imagem-de-producao`, worktree `.claude/worktrees/fix-76-imagem-de-producao`.

## Conferência no código (19/11/2026, main em `4a8be3d`)

| Item | Estado | Onde |
|---|---|---|
| `db/seed` fora da imagem (#76 M8) | **aberto** | `backend/src/main/resources/db/seed/R__seed_products.sql` é um recurso comum do Maven e entra no jar (`BOOT-INF/classes/db/seed/`). O `pom.xml` não exclui nada e o `Dockerfile` copia `backend/src` inteiro. O seed **não é aplicado** em prod (só o `application-local.yml` lista `classpath:db/seed`; `ProductSeedTest.ProdProfile` prova), mas o arquivo viaja na imagem |
| Senha gerada do Spring no log (#51) | **já não acontece** | `CustomerUserDetailsService` é um `@Service` desde o #10, então o `UserDetailsServiceAutoConfiguration` do Spring Boot se retira. Prova: `SessionControllerTest.shouldDefineOnlyTheCustomerUserDetailsService` (linha 285) e nenhum `generated security password` nos três logs de arranque guardados (`.tmp/demo/api.log`, `.tmp/dev-back.log`, `.tmp/dev-back-18.log`). Esses logs são do perfil `local`; não há log guardado da imagem `prod`, e o teste confere os beans, não o log |
| `X-Forwarded-Host` em prod (#94 item 3) | **aberto** | `application-prod.yml` tem `server.forward-headers-strategy: framework`; o `ForwardedHeaderFilter` do Spring lê `Forwarded`, `X-Forwarded-Host`, `-Port`, `-Proto`, `-Prefix`, `-Ssl` e `-For` de qualquer cliente. Hoje nenhum código de `backend/src/main` monta URL a partir do host do pedido (os links de e-mail usam `app.public-base-url`; não há `sendRedirect` nem `ServletUriComponentsBuilder`), então não há efeito explorável conhecido: é endurecimento, para que código futuro não herde o furo |

Observação sobre o #94 item 3: a issue fala em `X-Forwarded-Host` e `X-Forwarded-Prefix`. O cabeçalho padrão `Forwarded` (RFC 7239) carrega o mesmo `host=` e ainda `proto=`, e o filtro do Spring o lê também; por isso ele entra na lista dos escondidos.

Fora deste plano (continuam nas issues): os outros itens da #76 (M7, teste instável), da #51 e da #94.

## Global Constraints

- O seed continua funcionando onde é usado: `just dev-back`, `just demo` e os testes rodam de `target/classes`, que não muda. `ProductSeedTest` não é alterado.
- `server.forward-headers-strategy` continua `framework` em prod: `X-Forwarded-Proto`, `-Port` e `-Ssl` (que o Fly Proxy define) continuam valendo, porque o HSTS e o cookie `XSRF-TOKEN` dependem de o pedido ser visto como HTTPS.
- O IP do cliente não muda: `ClientIpResolver` e `ConnectionAddressRecorder` ficam como estão (SEC-014). `X-Forwarded-For` não entra na lista dos escondidos, para não mexer nesse caminho.
- Nenhum contexto Spring novo nos testes (o cache guarda 8): o teste de integração novo usa exatamente as mesmas anotações do `FlyClientIpLoginLimitTest`, para reaproveitar o contexto dele.
- Nenhuma migration, dependência, rota ou propriedade `app.*` nova.
- Testes focados, um comando pesado por vez; cada teste novo falha POR ASSERÇÃO antes do código; `just check-rapido` uma vez no fim.
- Um comando de shell por vez, sem encadear.

## Textos que o usuário vê

Nenhum. As duas mensagens novas são de build e de CI, em inglês, lidas só por quem monta a imagem:
- Dockerfile: `db/seed must not ship in the image`
- CI: `The startup log has the Spring generated password`

## Decisões que dependem do Sérgio

1. **Como tirar o seed do jar.** Recomendado: `maven-jar-plugin` com `<excludes>db/seed/**</excludes>` no `pom.xml` (qualquer jar montado, dentro ou fora do Docker, sai sem o seed) mais uma linha de guarda no `Dockerfile`. Alternativa com menos código: só `RUN rm -rf src/main/resources/db/seed` no `Dockerfile` (um jar montado fora do Docker continuaria com o seed).
2. **Como fechar o `X-Forwarded-Host`.** Recomendado: manter `framework` e esconder `Forwarded`, `X-Forwarded-Host` e `X-Forwarded-Prefix` (uma classe pequena). Alternativa: trocar para `native` (o Tomcat trata os cabeçalhos); descartada porque o Tomcat reescreve o endereço da conexão antes do `ConnectionAddressRecorder`, o que mexe no limite de login por IP (SEC-014), e porque só confia em proxies de faixas privadas de IP, o que teria de ser conferido no Fly.
3. **Incluir o `Forwarded` (RFC 7239) na lista**, além do que a issue pede. Recomendado: sim, é o mesmo furo por outro cabeçalho.
4. **Senha gerada: sem código de produção.** Recomendado: só a checagem no CI sobre o log da imagem `prod` de verdade (Task 3). Alternativa: não fazer nada e fechar o item com a prova da tabela acima.
5. **ADR:** nenhum novo. A regra dos cabeçalhos encaminhados entra em duas linhas no `docs/runbooks/homologacao-fly.md`, ao lado da regra do `Fly-Client-IP`. Se preferir registrar no ADR-0002, é uma linha a mais.

## Review Focus

1. Cliente manda `Forwarded: host=evil.example;proto=http` junto com o `X-Forwarded-Proto: https` do Fly: o pedido continua HTTPS e o host continua o do `Host` (teste na Task 2).
2. Cliente manda `X-Forwarded-Host` em maiúsculas/minúsculas diferentes (`x-forwarded-host`): escondido do mesmo jeito (teste na Task 2).
3. Código depois do filtro pergunta por `getHeaders("X-Forwarded-Host")` ou lista `getHeaderNames()`: não vê o cabeçalho (teste na Task 2).
4. Pedido sem nenhum cabeçalho encaminhado (acesso direto, health check do Fly): passa intacto (teste na Task 2).
5. `just dev-back` com o perfil `local` depois da exclusão: os 5 Produtos fictícios continuam aparecendo (verificação manual na Task 4; `ProductSeedTest` cobre o classpath de teste).

---

### Task 1: `db/seed` fora do jar e da imagem (#76 M8, NFR-012)

**Files:**
- Modify: `Dockerfile` (estágio `backend`, depois do `package`)
- Modify: `backend/pom.xml` (`<build><plugins>`)

**Interfaces:**
- Consumes: nada.
- Produces: `just imagem` falha se o jar tiver qualquer entrada `db/seed/`.

- [ ] **Step 1: Escrever a guarda que falha (RED).** No `Dockerfile`, logo depois da linha `RUN ./mvnw -q -B -DskipTests package && cp target/*.jar /app/app.jar`:

```dockerfile
# NFR-012: the fictitious seed is for the local profile only and must not ship in the image
RUN if jar tf /app/app.jar | grep -q 'db/seed/'; then echo 'db/seed must not ship in the image' >&2; exit 1; fi
```

(`jar` existe no estágio `eclipse-temurin:25-jdk`.)

- [ ] **Step 2: Rodar e ver falhar.**

Run (pasta da worktree; Docker Desktop aberto): `just imagem`
Expected: o build para nesse `RUN` com `db/seed must not ship in the image`.

- [ ] **Step 3: Excluir o seed do jar.** Em `backend/pom.xml`, dentro de `<build><plugins>`, sem `<version>` (vem do `spring-boot-starter-parent`):

```xml
<plugin>
	<groupId>org.apache.maven.plugins</groupId>
	<artifactId>maven-jar-plugin</artifactId>
	<configuration>
		<!-- NFR-012: the fictitious seed is for the local profile only; it stays in target/classes for dev and tests -->
		<excludes>
			<exclude>db/seed/**</exclude>
		</excludes>
	</configuration>
</plugin>
```

- [ ] **Step 4: Rodar e ver passar.**

Run: `just imagem`
Expected: imagem `app:local` montada, sem erro.

- [ ] **Step 5: Conferir que o seed continua no classpath de teste.**

Run (em `backend/`): `.\mvnw.cmd test -Dtest=ProductSeedTest`
Expected: PASS (inclui `new ClassPathResource("db/seed/R__seed_products.sql").exists()` verdadeiro).

- [ ] **Step 6: Commit.** `fix: keep the fictitious seed out of the jar and of the image (#76)`

---

### Task 2: esconder `Forwarded`, `X-Forwarded-Host` e `X-Forwarded-Prefix` (#94 item 3)

**Files:**
- Create: `backend/src/main/java/br/com/sergio/sapiensia/web/TrustedForwardedHeaderFilter.java`
- Create: `backend/src/main/java/br/com/sergio/sapiensia/web/ForwardedHeadersConfig.java`
- Test: `backend/src/test/java/br/com/sergio/sapiensia/web/TrustedForwardedHeaderFilterTest.java` (unitário, sem Spring)
- Test: `backend/src/test/java/br/com/sergio/sapiensia/web/TrustedForwardedHeadersIntegrationTest.java`
- Modify: `backend/src/main/resources/application-prod.yml` (só o comentário da linha 3)
- Modify: `docs/runbooks/homologacao-fly.md` (seção do `Fly-Client-IP`, perto da linha 341)

**Interfaces:**
- Consumes: `org.springframework.web.filter.ForwardedHeaderFilter` (Spring 7.0.9; `doFilterInternal` é `protected`). O Spring Boot 4.1.2 registra o dele em `ServletWebServerConfiguration.forwardedHeaderFilter` com `@ConditionalOnProperty(server.forward-headers-strategy=framework)` e `@ConditionalOnMissingFilterBean(ForwardedHeaderFilter.class)`, ordem `Ordered.HIGHEST_PRECEDENCE`, despachos `REQUEST`, `ASYNC` e `ERROR` (conferido no jar).
- Produces:
  - `final class TrustedForwardedHeaderFilter extends ForwardedHeaderFilter` (package-private), com `static final Set<String> HIDDEN = Set.of("forwarded", "x-forwarded-host", "x-forwarded-prefix")`.
  - `@Configuration(proxyBeanMethods = false) class ForwardedHeadersConfig` com `@Bean @ConditionalOnProperty(name = "server.forward-headers-strategy", havingValue = "framework") FilterRegistrationBean<ForwardedHeaderFilter> forwardedHeaderFilter()`.

- [ ] **Step 1: Escrever os testes unitários que falham.** `TrustedForwardedHeaderFilterTest`, com `MockHttpServletRequest`, `MockHttpServletResponse` e `MockFilterChain`; o pedido de base é `GET http://sapiensia.example/api/products` (serverName `sapiensia.example`, porta 80). Depois de `filter.doFilter(request, response, chain)`, as asserções são sobre `(HttpServletRequest) chain.getRequest()`:

| Teste | Cabeçalhos enviados | Asserções |
|---|---|---|
| `shouldIgnoreAForgedForwardedHost` | `X-Forwarded-Host: evil.example` | `getServerName()` = `sapiensia.example`; `getRequestURL()` não contém `evil.example` |
| `shouldIgnoreAForgedForwardedPrefix` | `X-Forwarded-Prefix: /evil` | `getContextPath()` = `""`; `getRequestURI()` = `/api/products` |
| `shouldIgnoreTheStandardForwardedHeader` | `Forwarded: host=evil.example;proto=http` e `X-Forwarded-Proto: https` | `getServerName()` = `sapiensia.example`; `isSecure()` verdadeiro; `getScheme()` = `https` |
| `shouldStillTrustTheProtoAndPortTheProxySets` | `X-Forwarded-Proto: https`, `X-Forwarded-Port: 443` | `isSecure()` verdadeiro; `getScheme()` = `https`; `getServerPort()` = 443 |
| `shouldHideTheHeadersWhateverTheCase` | `x-forwarded-host: evil.example` | `getServerName()` = `sapiensia.example` |
| `shouldNotShowTheHiddenHeadersDownstream` | os três escondidos | `getHeader(...)` nulo para os três; `getHeaders("X-Forwarded-Host")` vazio; `getHeaderNames()` sem nenhum dos três |
| `shouldPassARequestWithoutForwardedHeadersUntouched` | nenhum | `getServerName()` = `sapiensia.example`; `getScheme()` = `http`; `chain.getRequest()` não nulo |

`@DisplayName` em inglês, descrevendo o comportamento (não há ID de requisito).

- [ ] **Step 2: Rodar e ver falhar por asserção.** Para compilar, crie antes a classe vazia `final class TrustedForwardedHeaderFilter extends ForwardedHeaderFilter {}`.

Run (em `backend/`): `.\mvnw.cmd test -Dtest=TrustedForwardedHeaderFilterTest`
Expected: FAIL por asserção pelo menos no primeiro, no terceiro e no quinto (ex.: `expected "sapiensia.example" but was "evil.example"`); o quarto e o sétimo já passam. O segundo e o sexto podem já passar (o filtro do Spring remove os cabeçalhos encaminhados para quem vem depois, e o tratamento do prefixo tem uma chave própria, `setUseForwardedPrefix`); eles ficam como proteção contra regressão. Anote no relato quais falharam.

- [ ] **Step 3: Implementar `TrustedForwardedHeaderFilter`.** Sobrescreve `doFilterInternal(HttpServletRequest, HttpServletResponse, FilterChain)`: embrulha o pedido num `HttpServletRequestWrapper` cujos `getHeader`, `getHeaders` e `getHeaderNames` omitem os nomes de `HIDDEN` (comparação sem diferenciar maiúsculas, `Locale.ROOT`) e chama `super.doFilterInternal` com o embrulho. Javadoc em inglês dizendo por quê: o Fly Proxy define `X-Forwarded-Proto`, `-Port` e `-Ssl`; os três escondidos chegam do cliente.

- [ ] **Step 4: Rodar e ver passar.**

Run: `.\mvnw.cmd test -Dtest=TrustedForwardedHeaderFilterTest`
Expected: PASS, 7 testes.

- [ ] **Step 5: Escrever o teste de integração que falha.** `TrustedForwardedHeadersIntegrationTest`, com **exatamente** as anotações do `FlyClientIpLoginLimitTest` (mesmo `@Import`, mesmo `webEnvironment` e as mesmas quatro `properties`, na mesma ordem), para cair no mesmo contexto do cache:

  - `shouldRegisterOnlyTheTrustedForwardedHeaderFilter`: entre os beans `FilterRegistrationBean` do contexto, exatamente um tem `getFilter() instanceof ForwardedHeaderFilter`, e esse filtro é `TrustedForwardedHeaderFilter`; `getOrder()` = `Ordered.HIGHEST_PRECEDENCE`.
  - `shouldStillSeeHttpsFromTheProxyProtoHeader`: `GET http://127.0.0.1:<porta>/entrar` com `X-Forwarded-Proto: https` responde com o cabeçalho `Strict-Transport-Security` (o Spring Security só o escreve em pedido seguro); o mesmo GET sem o cabeçalho responde sem ele.

- [ ] **Step 6: Rodar e ver falhar por asserção.**

Run: `.\mvnw.cmd test -Dtest=TrustedForwardedHeadersIntegrationTest`
Expected: o primeiro teste FALHA (o filtro registrado é o `ForwardedHeaderFilter` do Spring Boot); o segundo já passa.

- [ ] **Step 7: Implementar `ForwardedHeadersConfig`.** O bean devolve `new FilterRegistrationBean<>(new TrustedForwardedHeaderFilter())` com `setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR)` e `setOrder(Ordered.HIGHEST_PRECEDENCE)`, como o do Spring Boot. O tipo de retorno é `FilterRegistrationBean<ForwardedHeaderFilter>`: é ele que faz o `@ConditionalOnMissingFilterBean` do Spring Boot se retirar.

- [ ] **Step 8: Rodar e ver passar, com as classes que já dependem do filtro.**

Run: `.\mvnw.cmd test "-Dtest=TrustedForwardedHeader*Test,FlyClientIpLoginLimitTest,AsaasWebhookForgedIpTest,ClientIpResolverTest"`
Expected: PASS em todas (SEC-014 intacto).

- [ ] **Step 9: Documentar.** Em `application-prod.yml`, o comentário da linha 3 passa a ser:

```yaml
  # behind the platform's HTTPS proxy: trust X-Forwarded-Proto/-Port/-Ssl to know the request was HTTPS;
  # Forwarded, X-Forwarded-Host and X-Forwarded-Prefix come from the client and are hidden (TrustedForwardedHeaderFilter)
```

Em `docs/runbooks/homologacao-fly.md`, na lista da seção do `Fly-Client-IP` (depois do item da linha 341), um item novo:

```markdown
- **Outros cabeçalhos encaminhados:** o sistema confia só em `X-Forwarded-Proto`, `X-Forwarded-Port` e `X-Forwarded-Ssl`, que o proxy do Fly.io define, para saber que o pedido chegou por HTTPS. `Forwarded`, `X-Forwarded-Host` e `X-Forwarded-Prefix` são ignorados: o endereço do site nos e-mails vem de `APP_PUBLIC_BASE_URL`, nunca do pedido.
```

- [ ] **Step 10: Commit.** `fix: ignore the client's Forwarded, X-Forwarded-Host and X-Forwarded-Prefix headers (#94)`

---

### Task 3: o CI confere o log de arranque da imagem `prod` (#51)

**Files:**
- Modify: `.github/workflows/ci.yml` (job que sobe a imagem para o e2e, passo com o `docker run -d --name app`, linhas 89–111)

**Interfaces:**
- Consumes: o contêiner `app` que o job já sobe com o perfil `prod`.
- Produces: o job falha se o log de arranque tiver `generated security password`.

- [ ] **Step 1: Trocar a saída de sucesso do laço de espera.** Hoje o laço faz `curl -fsS http://localhost:8080/api/system/health && exit 0`. Passa a sair do laço com `break` e marcar sucesso numa variável; depois do laço, se o sistema subiu:

```bash
if docker logs app 2>&1 | grep -qi 'generated security password'; then
  .github/ci-report.sh "The startup log has the Spring generated password" false
  exit 1
fi
exit 0
```

O ramo de falha (`The system did not start`) fica como está. Confira em `.github/ci-report.sh` como ele recebe o comando antes de usar `false`; se ele exigir outro formato, siga o dos outros passos.

- [ ] **Step 2: Provar localmente a expressão do `grep`.** Sem Docker: `echo 'Using generated security password: abc' | grep -qi 'generated security password'` devolve 0 e `echo 'Started SapiensiaApplication' | grep -qi 'generated security password'` devolve 1 (Git Bash). A prova de verdade é o CI da PR (este passo não tem RED automático: o defeito já não existe; registre isso no relato).

- [ ] **Step 3: Commit.** `ci: fail when the prod image logs the Spring generated password (#51)`

---

### Task 4: verificação do conjunto e fechamento

- [ ] **Step 1:** `just check-rapido` na worktree. Expected: verde.
- [ ] **Step 2: Sistema de verdade (perfil local).** Na pasta principal, `just db`; na worktree, `just dev-back`. Expected: a API sobe, o log **não** tem `generated security password`, e `select count(*) from products where title like '[Fictício]%'` no banco local devolve pelo menos 5 (o seed continua aplicado em desenvolvimento). Encerrar o processo pelo PID.
- [ ] **Step 3: Imagem.** `just imagem` (já rodado na Task 1; rodar de novo depois da Task 2). Expected: imagem montada.
- [ ] **Step 4:** `/code-review`, skill `verificar-entrega` (mostrar a tabela) e, com o OK do Sérgio, push e PR com `Refs #208`, `Refs #76`, `Refs #51`, `Refs #94` (as issues têm outros itens abertos; nenhuma fecha). Depois do merge: marcar a caixa do A7 na #208 e comentar nas três issues qual item foi atendido.

Sem tela alterada: `/verificar-tela` e e2e novo não se aplicam.

## Riscos

- **`maven-jar-plugin` e o `repackage` do Spring Boot:** o `repackage` parte do jar que o `maven-jar-plugin` montou, então a exclusão vale para o jar final. A guarda do `Dockerfile` prova isso em todo build; se falhar no Step 4 da Task 1, a exclusão não pegou e a alternativa é o `rm -rf` da decisão 1.
- **Contexto de teste compartilhado:** `TrustedForwardedHeadersIntegrationTest` divide o contexto do `FlyClientIpLoginLimitTest` (limite de 3 logins por IP). O teste novo só faz `GET /entrar`, que não conta no limite.
- **HSTS como prova do HTTPS:** se o `SecurityConfig` não mandar `Strict-Transport-Security` em `/entrar`, o segundo teste de integração usa o atributo `Secure` do cookie `XSRF-TOKEN`; o executor para e avisa antes de trocar.
- **Fly Proxy:** não foi conferido na homologação se o Fly sobrescreve `X-Forwarded-Host`; com a Task 2 a resposta deixa de importar.
- **Checagem do CI só roda na PR:** a Task 3 não tem como ser exercitada localmente por inteiro.
