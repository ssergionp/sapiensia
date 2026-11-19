# A6 — Cabeçalhos de segurança e token fora da URL (#61, #141 item 5) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tirar o token do link da barra de endereço depois de lido, mandar `Referrer-Policy: no-referrer` no site todo e fazer o 413 e o 503 saírem com os cabeçalhos de segurança.

**Architecture:** No backend, três ajustes na cadeia do Spring Security (`SecurityConfig`) e um cabeçalho no 413 do controller; nenhum cabeçalho é escrito à mão: os dois filtros que respondem cedo passam a rodar depois do `HeaderWriterFilter`, que escreve os cabeçalhos de toda resposta. No frontend, um hook único lê o token do fragmento, guarda em memória e limpa a URL.

**Tech Stack:** Java 25, Spring Boot 4.1, Spring Security; React + TypeScript, React Router; JUnit 5, Vitest, Playwright.

**Spec:** issue #208 (A6); issue #61 (itens `history.replaceState`, `Referrer-Policy`, 503 do `SessionVersionFilter`); issue #141 (item 5). Requisitos: SEC-007 (link de senha), SEC-006 (versão da sessão), SEC-015 (limite de corpo), SEC-017 (cabeçalhos).

**Processo:** COMPLETO (segurança). Branch `fix/61-cabecalhos-e-token-na-url`.

## Conferência no código (19/11/2026, main em `0036325`)

| Item | Estado | Onde |
|---|---|---|
| `replaceState` em /redefinir-senha (#61) | **aberto** | `PasswordLinkForm.tsx` (usado por /redefinir-senha e /painel/definir-senha) e `ConfirmEmailPage.tsx` leem o fragmento e o deixam na barra de endereço e no histórico |
| `Referrer-Policy: no-referrer` (#61) | **feito pela metade** | `SecurityConfig` só manda em `GET /redefinir-senha` e `GET /painel/definir-senha` (caminho exato); faltam /confirmar-email, a barra no final e o HEAD |
| 503 do `SessionVersionFilter` sem cabeçalhos (#61) | **aberto** | o filtro entra depois do `SecurityContextHolderFilter`, antes do `HeaderWriterFilter` |
| 413 do filtro sem cabeçalhos de segurança (#141-5) | **aberto** | `RequestBodyLimitFilter` roda antes do Spring Security (`@Order(HIGHEST_PRECEDENCE + 10)`) |
| 413 do controller sem `Connection: close` (#141-5) | **aberto** | `UnreadableBodyHandler.payloadTooLarge()` |

Observação: o token já viaja no fragmento (`#`), que o navegador nunca manda ao servidor nem no `Referer`. O `Referrer-Policy` e o `replaceState` são defesa a mais (histórico do navegador, tela compartilhada, extensões).

Fora deste plano (continuam nas issues): os outros itens da #61 e da #141.

## Global Constraints

- Nenhum cabeçalho de segurança escrito à mão num filtro ou handler: a única fonte é a configuração `headers(...)` do `SecurityConfig`.
- O token do link nunca vai para log, `sessionStorage`, `localStorage` nem para a URL depois de lido; fica só em memória (estado do React).
- Nenhum contexto Spring novo nos testes (o cache guarda 8); testes novos entram em classes que já existem.
- Nenhuma migration, dependência, rota ou propriedade `app.*` nova.
- `RequestBodyLimitFilter` continua antes do `CsrfFilter` (o CSRF lê parâmetros de formulário e não pode ler um corpo sem limite) e continua recusando o `Content-Length` grande antes de qualquer consulta ao banco.
- `@DisplayName` dos testes novos começa com o ID do requisito (`SEC-007:`, `SEC-015 SEC-017:`, `SEC-006 SEC-017:`).
- Testes focados, um comando pesado por vez; cada teste novo falha POR ASSERÇÃO antes do código; `just check-rapido` uma vez no fim.

## Textos que o usuário vê

Nenhum texto novo. Muda um comportamento visível (decisão 2).

## Decisões que dependem do Sérgio

1. **`Referrer-Policy: no-referrer` no site todo** (recomendado), em vez de acrescentar caminhos à lista. Cobre /confirmar-email, barra no final, HEAD e qualquer página de link futura, com menos código. Efeito: o site deixa de informar a outros sites de onde o visitante veio (hoje não há link externo que dependa disso). Alternativa: manter por página e acrescentar /confirmar-email e as variações.
2. **Recarregar a página do link (F5) depois de aberta mostra "link inválido"**, porque o token saiu da URL; a pessoa abre o link do e-mail de novo (o link continua válido até ser usado ou vencer). É o custo de tirar o token da barra de endereço. Alternativa: guardar o token no `sessionStorage` (não recomendo: volta a deixá-lo gravado no navegador).
3. **O limite de corpo passa a rodar dentro da cadeia do Spring Security**, logo depois do `HeaderWriterFilter` (recomendado), em vez de antes dela. Continua antes do CSRF, da sessão no banco e dos controllers. Alternativa: deixá-lo onde está e repetir a lista de cabeçalhos à mão no filtro (duplica a configuração e pode divergir).
4. **Issues #61 e #141 continuam abertas** (sobram itens); PR com `Refs #61, #141, #208` e os itens entregues riscados, como no A5.

## Review Focus

1. Corpo de formulário chunked acima de 64 KiB sem `X-XSRF-TOKEN`: o 413 continua saindo antes de o CSRF ler o corpo (teste que já existe em `RequestBodyLimitFilterTest`, tem de continuar verde depois da Task 2).
2. Link novo aberto na mesma aba (outro token): o formulário troca para o token novo e a URL fica limpa de novo (Task 4).
3. `React.StrictMode` monta duas vezes em desenvolvimento: o token não se perde na segunda montagem (Task 4).
4. Upload multipart do painel (50 MB) e webhook do Asaas: continuam fora do limite de 64 KiB depois da mudança de posição do filtro (testes que já existem, Task 2).
5. Voltar/avançar do navegador depois de abrir o link: a entrada do histórico não traz mais o token (e2e, Task 4).

---

### Task 1: `Referrer-Policy: no-referrer` no site todo (#61)

**Files:**
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/security/SecurityConfig.java` (bloco `headers(...)`)
- Test: `backend/src/test/java/br/com/sergio/sapiensia/security/ResetPasswordPageHeadersTest.java`

- [ ] **Step 1: testes que falham.** `otherRoutesAreUnchanged` vira `everyRouteHasNoReferrerPolicy` (`SEC-007: toda resposta sai com Referrer-Policy no-referrer`), parametrizado em `GET /entrar`, `GET /confirmar-email`, `GET /redefinir-senha/`, `HEAD /redefinir-senha` e `GET /api/system/health`: `header().string("Referrer-Policy", "no-referrer")`. Os dois testes das páginas de senha ficam.
- [ ] **Step 2: rodar** `-Dtest=ResetPasswordPageHeadersTest` → falha por asserção (cabeçalho ausente).
- [ ] **Step 3: implementar.** Trocar o `addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(...))` por `.referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))`; remover os imports que sobrarem; o comentário passa a dizer que vale para o site todo.
- [ ] **Step 4: rodar** `-Dtest=ResetPasswordPageHeadersTest,WebSecurityTest` → verde.
- [ ] **Step 5: commit** `fix: send Referrer-Policy no-referrer on every response (#61)`

### Task 2: 413 e 503 com os cabeçalhos de segurança (#61, #141-5)

**Files:**
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/security/SecurityConfig.java` (registro dos filtros)
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/web/RequestBodyLimitFilter.java` (classe `public`, sem `@Order`; Javadoc)
- Test: `backend/src/test/java/br/com/sergio/sapiensia/web/RequestBodyLimitFilterTest.java`, `backend/src/test/java/br/com/sergio/sapiensia/WebSecurityTest.java`

**Interfaces:**
- Ordem final na cadeia: `HeaderWriterFilter` → `RequestBodyLimitFilter` → `SessionVersionFilter` → `CsrfFilter`. Em `SecurityConfig`: `http.addFilterAfter(requestBodyLimitFilter, HeaderWriterFilter.class)` e `http.addFilterAfter(sessionVersionFilter, RequestBodyLimitFilter.class)`; `RequestBodyLimitFilter` entra como parâmetro do bean, como o `SessionVersionFilter` já entra.
- `RequestBodyLimitFilter` continua `@Component` (o registro automático como filtro do servlet fica no fim da fila e o `OncePerRequestFilter` não o deixa rodar duas vezes — o mesmo arranjo do `SessionVersionFilter`).

- [ ] **Step 1: testes que falham.**
  - `RequestBodyLimitFilterTest` (HTTP de verdade, porta aleatória): no teste do `Content-Length` grande, conferir também na resposta 413 `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Content-Security-Policy` igual a `SecurityConfig.CONTENT_SECURITY_POLICY` e `Referrer-Policy: no-referrer` (`SEC-015 SEC-017:`).
  - `WebSecurityTest`: `SEC-006 SEC-017: o limite de corpo e a conferência da sessão rodam depois dos cabeçalhos e antes do CSRF` — injeta o `SecurityFilterChain`, pega `getFilters()` e confere os índices: `HeaderWriterFilter` < `RequestBodyLimitFilter` < `SessionVersionFilter` < `CsrfFilter`.
- [ ] **Step 2: rodar** `-Dtest=RequestBodyLimitFilterTest,WebSecurityTest` → falham por asserção.
- [ ] **Step 3: implementar** conforme Interfaces.
- [ ] **Step 4: rodar** `-Dtest=RequestBodyLimitFilterTest,WebSecurityTest,SessionVersionFilterTest,UnreadableBodyHandlerTest` mais as classes que citam SEC-015 (`git grep -l SEC-015 -- backend/src/test`) → verde, incluindo os testes de multipart, webhook e formulário chunked que já existem.
- [ ] **Step 5: commit** `fix: answer the early 413 and 503 with the security headers (#61, #141)`

Limite aceito: o 503 com cabeçalhos não tem teste por HTTP (pediria um contexto Spring novo com o banco falhando); a prova é a ordem dos filtros mais o 413, que sai do mesmo ponto da cadeia e é conferido por HTTP.

### Task 3: `Connection: close` no 413 do controller (#141-5)

**Files:**
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/web/UnreadableBodyHandler.java` (`payloadTooLarge`)
- Test: `backend/src/test/java/br/com/sergio/sapiensia/web/RequestBodyLimitFilterTest.java`

- [ ] **Step 1: teste que falha.** No teste do corpo JSON chunked acima de 64 KiB sem `Content-Length` (`shouldRefuseAChunkedJsonBodyAbove64KiBWithoutContentLength`), conferir `Connection: close` na resposta, do mesmo jeito que o teste do `Content-Length` já confere.
- [ ] **Step 2: rodar** `-Dtest=RequestBodyLimitFilterTest` → falha por asserção.
- [ ] **Step 3: implementar.** `payloadTooLarge()` devolve o `ResponseEntity` com `.header(HttpHeaders.CONNECTION, "close")` (o corpo não foi consumido; a conexão não pode ser reaproveitada).
- [ ] **Step 4: rodar** `-Dtest=RequestBodyLimitFilterTest,UnreadableBodyHandlerTest` → verde.
- [ ] **Step 5: commit** `fix: close the connection after a 413 on a chunked body (#141)`

### Task 4: token fora da barra de endereço (#61)

**Files:**
- Create: `frontend/src/features/customer/useLinkToken.ts`
- Modify: `frontend/src/features/customer/PasswordLinkForm.tsx`, `frontend/src/features/customer/ConfirmEmailPage.tsx`
- Test: `frontend/src/features/customer/ResetPasswordPage.test.tsx`, `ConfirmEmailPage.test.tsx`; e2e `frontend/e2e/password-reset.e2e.ts`, `email-confirmation.e2e.ts`, `admin-panel.e2e.ts`

**Interfaces:**
- Produces: `export function useLinkToken(): string` — devolve o token do fragmento, guardado em estado; quando o roteador mostra um fragmento não vazio, guarda o token e troca a entrada do histórico pela mesma URL sem o fragmento (`navigate({ pathname, search, hash: "" }, { replace: true })`). Um fragmento novo (outro link na mesma aba) substitui o token.
- `PasswordLinkForm` usa `useLinkToken()` no lugar de `useLocation().hash` e mantém `key={token}`; `ConfirmEmailPage` usa o hook no lugar do `useState(window.location.hash...)`.

- [ ] **Step 1: testes que falham** (Vitest, com um componente-sonda que mostra `useLocation().hash`):
  - `SEC-007: o token sai da URL depois de lido`: abre `/redefinir-senha#tok-1`; a sonda mostra fragmento vazio; enviar o formulário chama a API com `token: "tok-1"`.
  - `SEC-007: um link novo na mesma aba troca o token`: navega para `#tok-2`; fragmento vazio de novo; o envio usa `tok-2`.
  - `SEC-007: o token sobrevive ao StrictMode`: mesma primeira asserção com a árvore dentro de `<StrictMode>`.
  - O mesmo primeiro caso em `ConfirmEmailPage.test.tsx`.
- [ ] **Step 2: rodar** `pnpm vitest run src/features/customer` → falham por asserção (o fragmento continua lá).
- [ ] **Step 3: implementar** conforme Interfaces.
- [ ] **Step 4: rodar** `pnpm vitest run src/features/customer src/features/admin` e `pnpm tsc -b` → verde.
- [ ] **Step 5: e2e.** Nas três jornadas (nova senha, confirmar e-mail, definir senha do Administrador), logo depois de abrir o link: `await expect(page).not.toHaveURL(/#/)`; na de nova senha, também `page.goBack()` seguido de `page.goForward()` e a URL continua sem `#`. O controlador roda o e2e na Task 5 (precisa do sistema de pé).
- [ ] **Step 6: commit** `fix: drop the link token from the address bar once read (#61)`

### Task 5: fechamento

- [ ] `just check-rapido` uma vez.
- [ ] Sistema de verdade (banco da pasta principal, API e frontend da worktree): `just e2e` com as três jornadas alteradas; no navegador, abrir um link de nova senha, conferir a barra de endereço sem token, trocar a senha, e conferir F5 → "link inválido".
- [ ] `/verificar-tela` nas páginas /redefinir-senha e /confirmar-email (sem mudança visual esperada).
- [ ] Riscar os itens entregues na #61 e na #141; `verificar-entrega`; `/code-review`; parar para o OK do push e da PR (`Refs #61, #141, #208`, citando SEC-006, SEC-007, SEC-015, SEC-017).

## Riscos

- **Task 2:** mudar a posição do limite de corpo é a mudança mais sensível; os oito testes de `RequestBodyLimitFilterTest` (multipart, webhook, formulário chunked antes do CSRF) são a rede. Uma requisição com `Content-Length` grande passa agora por três filtros leves do Spring Security antes do 413 (nenhum toca o banco).
- **Task 1:** `no-referrer` vale também para navegação dentro do próprio site; nada no sistema lê o `Referer`.
- **Task 4:** um teste e2e ou helper que releia `page.url()` esperando o token quebra; o implementador procura por isso (`git grep -n "#" -- frontend/e2e`) antes de alterar.
