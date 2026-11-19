# Limitador de requisições: /64, tabela por operação e log amostrado (A4 da triagem) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fechar o A4 da #208: o limite por IP das rotas passa a contar o IPv6 pelo prefixo /64 (#167), uma operação com a tabela cheia deixa de travar as outras (#141 item 2) e o aviso de recusa deixa de sair uma vez por requisição (#141 item 3, #94 item 2).

**Architecture:** A função que monta a chave por IP sai do `LoginAttemptLimiter` para uma classe única (`IpLimitKey`), usada pelos dois limitadores. O `RequestRateLimiter` troca o mapa único de 100 000 chaves por um mapa por operação, cada um com teto de 20 000. Um componente pequeno (`RefusalLogThrottle`) deixa passar uma linha de log por chave por minuto e conta as que segurou; o limitador de rotas, o de login (só o aviso novo de tabela cheia) e o webhook do Asaas passam por ele.

**Tech Stack:** Java 25, Spring Boot 4.1, Bucket4j 8.22.0 (já no projeto), JUnit 5 + AssertJ, `OutputCaptureExtension`. Nenhuma dependência nova.

**Spec:** issues #167, #141 (itens 1 a 3), #94 (item 2) e #208 (A4); requisitos SEC-002 e SEC-014 de `docs/spec/`; ADR-0004.

**Fluxo:** COMPLETO (segurança). Branch `fix/167-rate-limiter-hardening`, worktree `.claude/worktrees/fix-167-limitador-requisicoes`.

## O que a conferência no código mostrou (19/11/2026, main em 5c97f70)

| Item | Origem | Estado no código | Onde |
|---|---|---|---|
| Limite por IP das rotas conta o IPv6 inteiro | #167 | **Aberto.** A chave é `operação\|ip\|<endereço>`; o /64 só existe no login | `RequestRateLimiter.java:109`, `LoginAttemptLimiter.java:207-224` |
| Cota por e-mail gasta por outra pessoa | #141 item 1 | **Aberto como escolha**, já descrita no ADR-0004 (linha 32). Não é defeito: é decisão sua (ver "Decisões") | `CustomerController.java:36-50` |
| Tabela cheia recusa toda chave nova | #141 item 2 | **Aberto.** Um mapa só, 100 000 chaves, para as 9 operações | `RequestRateLimiter.java:39,113,133` |
| `log.warn` a cada recusa do limite | #141 item 3 | **Aberto** | `RequestRateLimiter.java:49` |
| `WARN` a cada recusa do webhook | #94 item 2 | **Aberto.** O webhook não tem limite de taxa (não está no `RateLimitConfig`) | `AsaasWebhookAccess.java:59` |
| Login: tabela cheia | #208 ("trava login") | O login já conta por /64 e tem tabelas próprias (100 000 contas, 100 000 IPs). Recusa chave nova quando enche e **não registra nada no log** | `LoginAttemptLimiter.java:105-109` |

**Medição feita na conferência:** cada chave do limitador custa cerca de 492 bytes de memória (100 000 chaves = 46 MB, medido com Bucket4j 8.22.0 no Java 25). A homologação roda com 1 GB e 75% disso de heap (768 MB).

**Achado da conferência:** hoje não é preciso um botnet para encher a tabela. Cada IP pode criar 20 chaves de e-mail por hora no cadastro e mais 20 no pedido de nova senha; com cerca de 2 400 endereços (qualquer conexão IPv6 residencial tem bilhões dentro do seu /64) a tabela única enche e **todas** as rotas limitadas recusam clientes novos, inclusive compra e download.

## O que este plano resolve e o que não resolve

- **Resolve:** uma conexão IPv6 sozinha (um /64) vira uma chave só por operação; encher a tabela de uma operação pública (capa, cadastro) não trava mais compra, leitura de pedido nem download de quem está logado; o log deixa de crescer uma linha por requisição recusada; tabela cheia passa a aparecer no log.
- **Não resolve (fica documentado no ADR-0004 e vai para uma issue `needs-triage`):** quem controla muitos endereços diferentes (cerca de 1 000 IPs ou prefixos /64 para o cadastro, cerca de 5 000 para o login) ainda enche a tabela **daquela** operação e recusa clientes novos nela enquanto o ataque durar. Um limitador em memória não segura ataque distribuído; a saída é um segundo nível por /48 no IPv6 ou proteção na borda (antes do Fly).

## Decisões (aprovadas pelo Sérgio em 19/11/2026)

1. **#141 item 1 (cota de 5 por hora por e-mail):** recomendo **manter** como está. No pedido de nova senha o dono do e-mail não fica travado: os links pedidos pelo atacante chegam na caixa dele e funcionam. Trocar a chave para e-mail + IP abriria a caixa da vítima para quem tem muitos IPs. Nenhum código muda; o ADR-0004 já descreve a troca.
2. **Tabela cheia continua recusando chave nova** (nunca libera sem contar, nunca apaga contador). Liberar sem contar tiraria o limite de 5 e-mails por hora e o bloqueio de conta justamente durante um ataque.
3. **Teto de 20 000 chaves por operação** no limitador de rotas (pior caso: 9 × 20 000 × 492 bytes = 89 MB; hoje 46 MB).
4. **Log amostrado:** uma linha por minuto por operação (limitador) e por motivo (webhook), com o número de linhas seguradas desde a anterior. No webhook, os IPs das recusas seguradas não aparecem no log.
5. **Acréscimo meu, fora do texto das issues:** aviso no log (também amostrado) quando o limitador do **login** recusa por tabela cheia. Hoje não há sinal nenhum disso.
6. **Fica de fora:** segundo nível por /48 e proteção na borda (uma issue `needs-triage` citando o #167).

## Textos que o usuário vê

Nenhum. A resposta `429` e as mensagens das telas não mudam. Mudam só linhas de log (em inglês, para o operador):

- `Request limit reached for {operation}: suppressed={n}`
- `Request limit table full for {operation}: suppressed={n}`
- `Login limit table full: table={accounts|ips} suppressed={n}`
- `Asaas webhook refused: ip={ip} reason={reason} suppressed={n}`

## Global Constraints

- Nenhuma dependência nova.
- As linhas de log dos limitadores nunca levam IP, e-mail, id de conta nem chave; a do webhook continua levando o IP, como hoje.
- Status, corpo e cabeçalhos do `429` não mudam.
- As chaves passadas ao `RefusalLogThrottle` são sempre de um conjunto fixo e pequeno (nome da operação, motivo). Nunca um valor que o cliente escolhe.
- `@DisplayName` no padrão dos testes vizinhos: `SEC-014: <nome do método>` (login: `SEC-002` quando for da tabela de contas).
- Testes: só os focados (`-Dtest` com as classes da tarefa), um comando pesado por vez. Cada teste novo aparece falhando **por asserção** (não por erro de compilação) antes do código. `just check-rapido` roda uma vez, no fim.
- Commits em inglês, Conventional Commits, com as linhas de atribuição da sessão.

## Review Focus

1. IPv6 com zona (`fe80::1%eth0`) ou texto que não é IP: a chave nunca lança exceção e a requisição é sempre contada. Teste na Task 1 (`IpLimitKeyTest`).
2. IPv4 embutido em IPv6 escrito em hexadecimal (`::ffff:cb00:7107`): conta como o IPv4 `203.0.113.7`. Teste na Task 1.
3. Conta que não cabe na tabela da operação: a ficha do IP já gasta volta (senão o cliente perde fichas sem ter sido atendido). Teste na Task 2.
4. Muitas threads recusando ao mesmo tempo: sai uma linha só por intervalo e a contagem das seguradas não perde nenhuma. Teste na Task 3 (`RefusalLogThrottleTest`).
5. Recusa "só o token errado" do webhook no meio de uma enxurrada de "IP e token errados": a linha aparece (motivos têm chaves separadas), porque é ela que mostra um token trocado. Teste na Task 3.

---

### Task 1: Chave por IP única, com IPv6 por /64, nos dois limitadores (#167)

**Files:**
- Create: `backend/src/main/java/br/com/sergio/sapiensia/customer/IpLimitKey.java`
- Create: `backend/src/test/java/br/com/sergio/sapiensia/customer/IpLimitKeyTest.java`
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/customer/LoginAttemptLimiter.java` (remove `ipKey`, linhas 200-224, e usa `IpLimitKey.of`)
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/web/RequestRateLimiter.java:109`
- Test: `backend/src/test/java/br/com/sergio/sapiensia/web/RequestRateLimiterTest.java`
- Docs: `docs/adr/0004-identidade-e-mfa.md` (linha 32), `docs/api/endpoints.md` (linha 7), `CLAUDE.md` (linha 62)

**Interfaces:**
- Produces: `public final class IpLimitKey { public static String of(String clientIp) }`. Regras (as do #57, sem mudança): `null` ou vazio vira `""`; IPv4 pelo endereço; IPv6 pelo prefixo /64 no formato `2001:db8:1:2:0:0:0:0/64`; IPv6 que embute IPv4 conta como o IPv4; texto que não é IP volta como veio; nunca consulta DNS e nunca lança exceção.

- [ ] **Step 1: Escrever `IpLimitKeyTest` (falha: a classe ainda devolve o texto como veio)**

Para o RED ser por asserção, crie antes `IpLimitKey.of` devolvendo o próprio argumento.

```java
@ParameterizedTest
@CsvSource({
    "203.0.113.7, 203.0.113.7",
    "2001:db8:1:2::1, 2001:db8:1:2:0:0:0:0/64",
    "2001:db8:1:2:aaaa:bbbb:cccc:dddd, 2001:db8:1:2:0:0:0:0/64",
    "2001:0db8:0001:0002::9, 2001:db8:1:2:0:0:0:0/64",
    "2001:db8:1:3::1, 2001:db8:1:3:0:0:0:0/64",
    "::1, 0:0:0:0:0:0:0:0/64",
    "::ffff:203.0.113.7, 203.0.113.7",
    "::ffff:cb00:7107, 203.0.113.7",
    "not:an:ip, not:an:ip"
})
@DisplayName("SEC-014: shouldBuildTheCountingKey")
void shouldBuildTheCountingKey(String clientIp, String expected) {
    assertThat(IpLimitKey.of(clientIp)).isEqualTo(expected);
}

@Test
@DisplayName("SEC-014: shouldNeverThrowAndAlwaysReturnAKey")
void shouldNeverThrowAndAlwaysReturnAKey() {
    assertThat(IpLimitKey.of(null)).isEmpty();
    assertThat(IpLimitKey.of("")).isEmpty();
    assertThat(IpLimitKey.of("fe80::1%eth0")).isNotEmpty();
    assertThat(IpLimitKey.of("[2001:db8::1]")).isNotEmpty();
}
```

- [ ] **Step 2: Escrever os testes do `RequestRateLimiterTest` (falham: hoje cada endereço IPv6 tem o seu balde)**

`IP_ONLY` aceita 3 por IP em 10 minutos.

```java
@Test
@DisplayName("SEC-014: shouldShareTheIpLimitBetweenIpv6AddressesOfTheSame64")
void shouldShareTheIpLimitBetweenIpv6AddressesOfTheSame64() {
    RequestRateLimiter limiter = limiter(0);
    for (int i = 1; i <= 3; i++) {
        assertThat(limiter.tryConsume(IP_ONLY, "2001:db8:1:2::" + i, Optional.empty())).isEmpty();
    }
    assertThat(limiter.tryConsume(IP_ONLY, "2001:db8:1:2:aaaa:bbbb:cccc:dddd", Optional.empty())).isPresent();
    assertThat(limiter.tryConsume(IP_ONLY, "2001:db8:1:3::1", Optional.empty())).isEmpty();
    assertThat(limiter.trackedKeys()).isEqualTo(2);
}

@Test
@DisplayName("SEC-014: shouldCountIpv6WithEmbeddedIpv4AsTheIpv4")
void shouldCountIpv6WithEmbeddedIpv4AsTheIpv4() {
    RequestRateLimiter limiter = limiter(0);
    for (int i = 0; i < 3; i++) {
        assertThat(limiter.tryConsume(IP_ONLY, "203.0.113.7", Optional.empty())).isEmpty();
    }
    assertThat(limiter.tryConsume(IP_ONLY, "::ffff:203.0.113.7", Optional.empty())).isPresent();
    assertThat(limiter.tryConsume(IP_ONLY, "203.0.113.8", Optional.empty())).isEmpty();
}
```

- [ ] **Step 3: Rodar e ver falhar por asserção**

Run (em `backend`): `./mvnw -q test -Dtest=IpLimitKeyTest,RequestRateLimiterTest`
Expected: FAIL nas asserções dos testes novos; os antigos passam.

- [ ] **Step 4: Implementar**

Mova o corpo de `LoginAttemptLimiter.ipKey` (com o Javadoc) para `IpLimitKey.of`; o `LoginAttemptLimiter.admit` chama `IpLimitKey.of(clientIp)`. Em `RequestRateLimiter.tryConsume`, a chave do balde por IP passa a ser `operation + "|ip|" + IpLimitKey.of(ipKey)`: a conversão fica dentro do limitador, então o `RateLimitInterceptor` e o `CustomerController` não mudam.

- [ ] **Step 5: Rodar os testes dos dois limitadores**

Run: `./mvnw -q test -Dtest=IpLimitKeyTest,RequestRateLimiterTest,LoginAttemptLimiterTest,AdminAccountKeysLimiterTest,RateLimitInterceptorTest`
Expected: PASS (os testes de /64 do `LoginAttemptLimiterTest`, linhas 158-197, continuam passando sem mudança).

- [ ] **Step 6: Documentar**

ADR-0004, linha 32: os limites por IP de todas as rotas contam o IPv4 por endereço e o IPv6 por prefixo /64, pela mesma função do login (#167). `docs/api/endpoints.md`, linha 7: "por IP" significa por endereço IPv4 ou por prefixo /64 do IPv6. `CLAUDE.md`, linha 62: `IpLimitKey` é o único jeito de montar a chave por IP de um limitador. O terceiro item do #167 (piso e e2e) fica respondido no ADR: o `ip-capacity-floor` vale por balde, depois do agrupamento, e o e2e roda de `127.0.0.1` ou `::1`, que já eram uma chave cada.

- [ ] **Step 7: Commit**

`fix: count IPv6 by /64 in the per-IP request limits (#167)`

---

### Task 2: Tabela de chaves por operação (#141 item 2)

**Files:**
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/web/RequestRateLimiter.java`
- Test: `backend/src/test/java/br/com/sergio/sapiensia/web/RequestRateLimiterTest.java`
- Docs: `docs/adr/0004-identidade-e-mfa.md` (linha 32)

**Interfaces:**
- Consumes: `IpLimitKey.of` (Task 1).
- Produces: `RequestRateLimiter(RateLimitProperties properties, Clock clock, int maxTrackedKeysPerOperation)` (o mesmo construtor de teste, agora com o teto valendo por operação); constante `DEFAULT_MAX_TRACKED_KEYS_PER_OPERATION = 20_000`; `int trackedKeys()` continua somando todas as operações.

- [ ] **Step 1: Escrever os testes (falham: hoje o teto é um só para todas as operações)**

```java
@Test
@DisplayName("SEC-014: shouldKeepOtherOperationsOpenWhenOneOperationIsFull")
void shouldKeepOtherOperationsOpenWhenOneOperationIsFull() {
    RequestRateLimiter small = new RequestRateLimiter(properties(0), clock, 3);
    for (int i = 1; i <= 3; i++) {
        assertThat(small.tryConsume(IP_ONLY, "192.0.2." + i, Optional.empty())).isEmpty();
    }
    assertThat(small.tryConsume(IP_ONLY, "198.51.100.1", Optional.empty())).isPresent();

    assertThat(small.tryConsume(WITH_ACCOUNT, "198.51.100.1", account("ana@example.com"))).isEmpty();
    assertThat(small.tryConsume(RateLimitedOperation.ORDER_CREATE, "198.51.100.1", Optional.of("customer-1"))).isEmpty();
    assertThat(small.trackedKeys()).isEqualTo(7);
}

@Test
@DisplayName("SEC-014: shouldGiveTheIpTokenBackWhenTheAccountKeyDoesNotFit")
void shouldGiveTheIpTokenBackWhenTheAccountKeyDoesNotFit() {
    // WITH_ACCOUNT: 5 per IP. The table of the operation holds 2 keys: one IP and one account.
    RequestRateLimiter small = new RequestRateLimiter(properties(0), clock, 2);
    assertThat(small.tryConsume(WITH_ACCOUNT, "192.0.2.1", account("ana@example.com"))).isEmpty();
    for (int i = 0; i < 10; i++) {
        assertThat(small.tryConsume(WITH_ACCOUNT, "192.0.2.1", account("new-" + i + "@example.com"))).isPresent();
    }
    // the 10 refusals spent nothing: the IP still has 4 of its 5 tokens, but the account allows only one more
    assertThat(small.tryConsume(WITH_ACCOUNT, "192.0.2.1", account("ana@example.com"))).isEmpty();
    assertThat(small.trackedKeys()).isEqualTo(2);
}
```

- [ ] **Step 2: Rodar e ver falhar por asserção**

Run: `./mvnw -q test -Dtest=RequestRateLimiterTest`
Expected: FAIL em `shouldKeepOtherOperationsOpenWhenOneOperationIsFull` (o segundo teste já deve passar hoje; se passar, prove que ele morde com uma mutação temporária: comente o `ipBucket.addTokens(1)` da linha 134 e veja falhar).

- [ ] **Step 3: Implementar**

Troque o `ConcurrentHashMap` único por um `EnumMap<RateLimitedOperation, ConcurrentHashMap<String, Tracked>>` preenchido no construtor com todas as operações. O teste de "cheio" compara o tamanho do mapa **da operação** com `maxTrackedKeysPerOperation`; a limpeza de minuto em minuto percorre todos os mapas. A ordem "IP primeiro, conta depois" e as devoluções de ficha não mudam. Atualize o Javadoc da classe (teto por operação e o motivo).

- [ ] **Step 4: Rodar**

Run: `./mvnw -q test -Dtest=RequestRateLimiterTest,RateLimitInterceptorTest`
Expected: PASS, inclusive `shouldRefuseNewKeysWhenFullAndNeverEvictATrackedOne` e `shouldDropIdleBucketsAfterTheirWindow` sem mudança.

- [ ] **Step 5: Documentar**

ADR-0004, linha 32: troque "a tabela de 100 000 chaves" por: cada operação tem a sua tabela, com teto de 20 000 chaves (cerca de 492 bytes por chave; pior caso de 89 MB com as 9 cheias); operação cheia recusa chave nova só nela; o que continua possível (o parágrafo "Não resolve" deste plano), com o número da issue de pendência.

- [ ] **Step 6: Commit**

`fix: cap the tracked keys per operation so a full table blocks only that operation (#141)`

---

### Task 3: Aviso de recusa amostrado (#141 item 3, #94 item 2) e aviso de tabela cheia

**Files:**
- Create: `backend/src/main/java/br/com/sergio/sapiensia/web/RefusalLogThrottle.java`
- Create: `backend/src/main/java/br/com/sergio/sapiensia/web/RefusalLogProperties.java`
- Create: `backend/src/test/java/br/com/sergio/sapiensia/web/RefusalLogThrottleTest.java`
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/web/RequestRateLimiter.java` (`require`, construtores)
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/customer/LoginAttemptLimiter.java` (`admit`, construtores)
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/payment/AsaasWebhookAccess.java:59`
- Modify: `backend/src/main/resources/application.yml` (`app.refusal-log.interval: PT1M`, com comentário), `backend/src/test/resources/application.properties` (`app.refusal-log.interval=PT0S`)
- Test: `RequestRateLimiterTest`, `LoginAttemptLimiterTest`, `AsaasWebhookAccessTest`
- Docs: `docs/adr/0004-identidade-e-mfa.md` (linha 32), `CLAUDE.md` (junto da linha 62)

**Interfaces:**
- Produces:
  - `@Validated @ConfigurationProperties("app.refusal-log") record RefusalLogProperties(@NotNull Duration interval)`; intervalo negativo impede a aplicação de subir; zero significa "nunca segura" (os testes de integração dividem o contexto do Spring e conferem linhas de log, por isso usam zero).
  - `@Component public class RefusalLogThrottle` com `@Autowired RefusalLogThrottle(RefusalLogProperties, Clock)`, `public RefusalLogThrottle(Duration interval, Clock clock)` e `public OptionalLong pass(String key)`: vazio = não escreva a linha; preenchido = escreva, e o valor é quantas linhas dessa chave foram seguradas desde a última que passou. A primeira de cada chave passa com 0. Atômico por chave (`ConcurrentHashMap.compute`).
  - `RequestRateLimiter`: construtor `@Autowired (RateLimitProperties, Clock, RefusalLogThrottle)`; os dois construtores de teste que existem hoje continuam, com `new RefusalLogThrottle(Duration.ZERO, clock)`; novo `RequestRateLimiter(RateLimitProperties, Clock, int, RefusalLogThrottle)`.
  - `LoginAttemptLimiter`: o mesmo desenho (`@Autowired (LoginLimitProperties, Clock, RefusalLogThrottle)`, os dois construtores atuais mantidos, novo com quatro argumentos).
  - `AsaasWebhookAccess(ClientIpResolver, AsaasWebhookProperties, AdminAlerts, RefusalLogThrottle)`.
- Chaves do throttle: `"rate-limit:" + operation`, `"rate-limit-full:" + operation`, `"login-full:accounts"`, `"login-full:ips"`, `"asaas-webhook:" + reason`.
- Linhas de log: exatamente as da seção "Textos que o usuário vê".

- [ ] **Step 1: Escrever `RefusalLogThrottleTest` (com um `RefusalLogThrottle` cujo `pass` devolve sempre `OptionalLong.of(0)`, para o RED ser por asserção)**

Use um relógio mutável como o `MutableClock` do `RequestRateLimiterTest`.

```java
@Test
void shouldPassTheFirstLineAndHoldTheRestOfTheInterval() {
    RefusalLogThrottle throttle = new RefusalLogThrottle(Duration.ofMinutes(1), clock);
    assertThat(throttle.pass("a")).hasValue(0);
    for (int i = 0; i < 99; i++) {
        assertThat(throttle.pass("a")).isEmpty();
    }
    clock.advance(Duration.ofSeconds(59));
    assertThat(throttle.pass("a")).isEmpty();
    clock.advance(Duration.ofSeconds(1));
    assertThat(throttle.pass("a")).hasValue(100);
    assertThat(throttle.pass("a")).isEmpty();
}

@Test
void shouldCountEachKeyByItself() {
    RefusalLogThrottle throttle = new RefusalLogThrottle(Duration.ofMinutes(1), clock);
    assertThat(throttle.pass("a")).hasValue(0);
    assertThat(throttle.pass("b")).hasValue(0);
    assertThat(throttle.pass("a")).isEmpty();
}

@Test
void shouldNeverHoldWhenTheIntervalIsZero() {
    RefusalLogThrottle throttle = new RefusalLogThrottle(Duration.ZERO, clock);
    for (int i = 0; i < 5; i++) {
        assertThat(throttle.pass("a")).hasValue(0);
    }
}

@Test
void shouldLoseNoCountUnderConcurrency() throws Exception {
    // 8 threads x 1000 calls on one key inside one interval: exactly one passes, then the next interval reports 7999
    RefusalLogThrottle throttle = new RefusalLogThrottle(Duration.ofMinutes(1), clock);
    AtomicInteger passed = new AtomicInteger();
    try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
        for (int t = 0; t < 8; t++) {
            pool.submit(() -> {
                for (int i = 0; i < 1000; i++) {
                    if (throttle.pass("a").isPresent()) {
                        passed.incrementAndGet();
                    }
                }
            });
        }
    }
    assertThat(passed).hasValue(1);
    clock.advance(Duration.ofMinutes(1));
    assertThat(throttle.pass("a")).hasValue(7999);
}
```

- [ ] **Step 2: Escrever os testes dos três chamadores (com `@ExtendWith(OutputCaptureExtension.class)` e `CapturedOutput output`)**

Conte linhas com `output.getAll().lines().filter(l -> l.contains(...)).count()`.

`RequestRateLimiterTest`, com `new RequestRateLimiter(properties(0), clock, 3, new RefusalLogThrottle(Duration.ofMinutes(1), clock))`:

```java
@Test
@DisplayName("SEC-014: shouldLogOneRefusalLinePerOperationPerMinute")
void shouldLogOneRefusalLinePerOperationPerMinute(CapturedOutput output) {
    // IP_ONLY: 3 per IP. 3 pass, then 50 refusals through require()
    ...
    assertThat(lines(output, "Request limit reached for COVER")).isEqualTo(1);
    assertThat(output.getAll()).contains("Request limit reached for COVER: suppressed=0");
    // another operation in the same minute has its own line
    ... refuse SIGN_UP once ...
    assertThat(lines(output, "Request limit reached for SIGN_UP")).isEqualTo(1);
    clock.advance(Duration.ofMinutes(1));
    ... refuse COVER once more ...
    assertThat(output.getAll()).contains("Request limit reached for COVER: suppressed=49");
    assertThat(output.getAll()).doesNotContain("192.0.2.");
}

@Test
@DisplayName("SEC-014: shouldLogAFullTableApartFromAnOrdinaryRefusal")
void shouldLogAFullTableApartFromAnOrdinaryRefusal(CapturedOutput output) {
    // cap 3: three IPs fill COVER, then 20 new IPs are refused through require()
    ...
    assertThat(lines(output, "Request limit table full for COVER")).isEqualTo(1);
    assertThat(output.getAll()).doesNotContain("Request limit reached for COVER").doesNotContain("198.51.100.");
}
```

`LoginAttemptLimiterTest` (limitador com teto 3 e throttle de 1 minuto):

```java
@Test
@DisplayName("SEC-002: shouldLogOnceWhenTheTableIsFull")
void shouldLogOnceWhenTheTableIsFull(CapturedOutput output) {
    // 3 accounts fill the table; 20 attempts for new e-mails from the same IP are refused
    ...
    assertThat(lines(output, "Login limit table full: table=accounts")).isEqualTo(1);
    assertThat(output.getAll()).doesNotContain("@example.com").doesNotContain("192.0.2.");
}
```

`AsaasWebhookAccessTest` (o helper `access(...)` ganha o throttle; nos testes que já existem, `Duration.ZERO`):

```java
@Test
@DisplayName("SEC-023: shouldLogOneRefusalLinePerReasonPerMinute")
void shouldLogOneRefusalLinePerReasonPerMinute(CapturedOutput output) {
    // throttle of one minute; 30 refusals with IP and token wrong, then one with only the token wrong
    ...
    assertThat(lines(output, "reason=IP_NOT_ALLOWED_AND_TOKEN_INVALID")).isEqualTo(1);
    assertThat(lines(output, "reason=TOKEN_INVALID suppressed=0")).isEqualTo(1);
    // every refusal is still refused
}
```

- [ ] **Step 3: Rodar e ver falhar por asserção**

Run: `./mvnw -q test -Dtest=RefusalLogThrottleTest,RequestRateLimiterTest,LoginAttemptLimiterTest,AsaasWebhookAccessTest`
Expected: FAIL nas contagens de linhas (50 em vez de 1) e nos testes do throttle.

- [ ] **Step 4: Implementar**

`RefusalLogThrottle.pass`: por chave, guarda o instante da última linha que passou e o número de seguradas; passa quando o intervalo é zero, quando é a primeira ou quando `agora >= última + intervalo`. No `RequestRateLimiter`, um método interno devolve a recusa com o motivo (`record Refusal(Duration wait, boolean tableFull)`); `tryConsume` continua devolvendo só a espera e `require` escolhe a linha e a chave pelo motivo. No `LoginAttemptLimiter.admit`, o aviso sai no ramo `accountsFull || ipsFull` (linha 107), dizendo qual tabela encheu. No `AsaasWebhookAccess`, só a linha de log passa pelo throttle: a decisão e o `alerts.recordSafely` não mudam.

- [ ] **Step 5: Rodar os testes de unidade e os de integração que conferem essas linhas**

Run: `./mvnw -q test -Dtest=RefusalLogThrottleTest,RequestRateLimiterTest,LoginAttemptLimiterTest,AdminAccountKeysLimiterTest,AsaasWebhookAccessTest`
Depois (precisa do Docker; um comando por vez): `./mvnw -q test -Dtest=RateLimitRoutesTest,AsaasWebhookLogTest,RateLimitPropertiesValidationTest`
Expected: PASS. `RateLimitRoutesTest:388` e `AsaasWebhookLogTest:97` continuam valendo porque o começo das linhas não mudou e o intervalo de teste é zero.

- [ ] **Step 6: Documentar**

ADR-0004, linha 32: o aviso de recusa sai uma vez por minuto por operação, com a contagem das seguradas; tabela cheia tem linha própria nos dois limitadores. ADR-0003 só se já descrever o log de recusa do webhook (confira antes; se não descreve, não mexa). `CLAUDE.md`: `RefusalLogThrottle` é o jeito de registrar no log uma recusa que um cliente anônimo consegue repetir em laço.

- [ ] **Step 7: Commit**

`fix: sample the refusal warnings of the request limiter and the Asaas webhook (#141, #94)`

---

### Task 4: Fechamento

- [ ] **Step 1:** `just check-rapido` uma vez (se o Claude Code o encerrar por falta de memória, não repetir: o CI da PR conta como o check).
- [ ] **Step 2:** Revisão da branch inteira (superpowers:requesting-code-review) e a skill `verificar-entrega`, com a tabela dela.
- [ ] **Step 3:** Criar **uma** issue `needs-triage` citando o #167: segundo nível por /48 no IPv6 e proteção na borda contra ataque distribuído (os números do "Não resolve").
- [ ] **Step 4 (OK dado em 19/11/2026):** push e PR com `Refs #167, #141, #94` (o merge fica com o Sérgio).
- [ ] **Step 5 (depois do merge):** marcar a caixa do A4 na #208; comentar no #141 (itens 1 a 3: o que foi decidido e feito) e no #94 (item 2), que continuam abertas pelos outros itens; `just limpar-ticket` na pasta principal.
