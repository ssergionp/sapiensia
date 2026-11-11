# Troca do PDF com nova autenticação (#22) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** APROVADO em 10/11/2026 (itens 2 a 8 da seção 8 como escritos; ajustes 1 e 3a–3d incorporados). Em 11/11/2026 a main com o #19 (`0eef1df`) entrou nesta branch por `git merge origin/main` (sem conflitos) e o plano foi alinhado ao código real (Task 1, seções 2, 4.2, 5, 6, 7, 8 e Tasks 3–5). **Aguardam aprovação antes de qualquer código:** os textos novos da seção 6 (incluindo a frase de confirmação da publicação, nova neste ajuste), D8 (ADR-0004), D9 e D10.

**Goal:** Trocar o Arquivo do produto de um Produto que já foi publicado (mesmo despublicado depois) exige que o Administrador digite de novo a senha e um código TOTP; antes da primeira publicação nada muda.

**Architecture:** O carimbo da última autenticação já existe: o `AdministratorPrincipal` guardado na sessão tem `passwordVerifiedAt` e `totpVerifiedAt`. Uma rota nova (`POST /api/admin/sessions/reauthentication`) confere senha e código com os limites, o replay guard e a auditoria do login e renova os dois carimbos numa sessão nova. A rota do arquivo ganha um `AuthorizationManager` que, para Produto já publicado (`first_published_at`, do #19), exige os dois carimbos dentro da janela `app.admin.reauthentication-window`, decidido **antes** de ler o corpo; o serviço confere de novo dentro da trava da linha. Isso **substitui** o bloqueio `409 PUBLISHED_BEFORE` que o #19 põe no lugar. A tela guarda o `File` escolhido no estado do componente, pede senha e código num formulário na própria seção e reenvia o mesmo arquivo.

**Tech Stack:** Spring Boot 4.1.2 / Spring Security 7.1 (`AuthorizationManager`, `AuthorizationDeniedException`), JdbcClient, React + TanStack Query, Vitest, Playwright. **Nenhuma dependência nova** (o formulário é inline, sem o `Dialog` do shadcn, que traria `@radix-ui/react-dialog`). **Nenhuma migration.**

**Spec:** issue #22 (pai #5); `docs/spec/sapiensia-spec.md` (SEC-008, FR-010, SEC-065); `docs/adr/0004-identidade-e-mfa.md`; `docs/adr/0007-modelo-comercial-da-fase-1.md` (Acesso vitalício, sempre na versão mais recente); plano do #18 (`2026-11-09-18-envio-pdf-produto.md`, D1 e D2); plano do #19 (`2026-11-10-19-publicar-produto.md`, seções 2, 3 e 7, D2); `GLOSSARY.md` ("Arquivo do produto", "Rascunho", "Acesso").

## IDs cobertos

| ID | Como | Onde |
|---|---|---|
| SEC-008 | Reautenticação (senha + TOTP, com replay guard) com janela curta antes de trocar o arquivo de Produto já publicado | Tasks 2, 3, 4 |
| FR-010 | Uma só versão atual; a nova substitui a anterior para todos que compraram; a anterior sai do Storage (D2 do #18, confirmado na seção 4) | Task 3 |
| ADR-0004 | Rascunho segue sem reautenticação; já publicado (inclusive despublicado) exige | Task 3 |
| ADR-0007 | Quem comprou recebe sempre a versão mais recente (o download do #33 lê a chave do banco) | Task 3 (teste de ponteiro) |
| SEC-065 | Reautenticação (SUCCESS/FAILURE/BLOCKED) e troca recusada por falta dela (BLOCKED) na auditoria, sem senha nem código | Tasks 2, 3 |
| SEC-010, SEC-011, SEC-017 | Rota nova negada por padrão, teste negativo de ID, CSRF (inclusive o token novo depois da reautenticação) | Tasks 2, 3 |

## Global Constraints

- Nenhuma dependência nova (backend e frontend). Nenhuma migration no #22.
- Todo endpoint começa com `/api`; a rota nova é liberada na `SecurityConfig` só com `panel(Capability.PANEL_ACCESS)` (Administrador completo, dois fatores); nada de `permitAll`.
- Senha, código TOTP, código de recuperação, hash e segredo nunca vão para log, mensagem de exceção, auditoria nem `toString()`. Records com senha/código têm `toString()` redigido (padrão do `MfaCodeRequest`).
- Janela: `app.admin.reauthentication-window`, `PT5M` no `application.yml` **e** no `application.properties` de teste (senão o contexto não sobe). Valor positivo obrigatório (falha na subida, como `app.admin.mfa-pending-ttl`).
- A reautenticação usa os **mesmos** contadores do login: senha na chave `admin:<e-mail normalizado>`, código na chave `admin-mfa:<id>`, e o limite por IP compartilhado. Sem tentativas extras. Usa o **mesmo** replay guard do TOTP (`Administrators.acceptStep`, `totp_last_step`).
- Textos da tela: **só os da seção 6**. Código, commits e comentários em inglês.
- Teste que prova requisito leva o ID no `@DisplayName`/título. Falhas em integração com dublês trocáveis (`SwitchableAuditLog`, `SwitchableProductFileStorage`), sem `@MockitoBean`.
- Controller e repositório package-private; DTOs como `record`. Tempo sempre pelo `Clock` da aplicação.
- Na máquina: só um `just check-resumo` ou `just imagem` por vez. Sem `--force`, sem `reset --hard`; o único merge é o `git merge origin/main` da Task 1, depois do aviso do Sérgio.

## Review Focus

1. **Upload lento de 50 MB que termina depois da janela**: a decisão vale no começo da requisição (filtro de segurança, antes do corpo), então o envio autorizado não é recusado no fim. Teste na Task 3.
2. **Publicação no meio de um envio de rascunho** (duas abas): o envio foi autorizado como rascunho, mas na trava o Produto já tem `first_published_at` → `403 REAUTHENTICATION_REQUIRED`, objeto novo apagado, linha antiga intacta. Teste na Task 3 (é o teste de corrida do #19, com a resposta nova).
3. **Código TOTP já usado** (no login ou numa reautenticação anterior): recusado pelo replay guard, com a mesma resposta de código errado. Teste na Task 2 (ajuste 3a).
4. **Sessão "esquecida" com login de horas atrás**: depois da janela, trocar o arquivo de Produto publicado é recusado mesmo com a sessão válida. Teste na Task 3.
5. **Token CSRF velho depois da reautenticação**: a sessão mudou de ID e o token mudou; o envio com o token velho é recusado e nada é gravado; com o novo, passa. Teste na Task 3 (ajuste 3d).

---

## 1. Escopo

**Entra:**
- Rota `POST /api/admin/sessions/reauthentication` (senha + código TOTP) com limite, replay guard, auditoria e sessão nova com os carimbos renovados.
- Regra na rota do arquivo: Produto com `first_published_at` exige reautenticação na janela; rascunho nunca publicado segue como no #18.
- **Remoção do bloqueio do #19**: `ProductFileRejection.PUBLISHED_BEFORE`, o mapeamento para `409`, as duas conferências que o lançam no `ProductFileService`, o texto `FILE_MESSAGES.PUBLISHED_BEFORE` e o ramo que troca o formulário por essa frase na tela; a frase da confirmação de publicação que diz que a troca "ainda não está disponível" (tabela de 4.2 e seção 6).
- Formulário de reautenticação inline na seção "Arquivo do produto", sem perder o arquivo escolhido.
- e2e da troca com reautenticação; a linha do ADR-0004 (D8).

**Fica fora:**
- A coluna `first_published_at`, a publicação e a despublicação: são do #19.
- Download e URL assinada (#33).
- Reautenticação em outras operações sensíveis (preço etc.): vai para a issue de sobras, se você quiser.
- Código de recuperação na reautenticação (D4).
- Aviso por e-mail aos Clientes de que há versão nova.

## 2. Dependência do #19 e ordem das migrations

- **O #22 não cria coluna nem migration. Só lê.** O marcador é do #19: `products.first_published_at timestamptz null`, gravado no primeiro `DRAFT → PUBLISHED` e nunca alterado, com o check `products_publication_consistent` (`(status = 'DRAFT') = (first_published_at is null)`).
- **Ordem das migrations:** `V14__create_product_files.sql` (#18, já na main) → `V15__product_publication.sql` (#19: estados `DRAFT/PUBLISHED/UNPUBLISHED` e `first_published_at`) → **nenhuma no #22**. Se outro ticket ocupar a V15 antes, o #19 renumera; o #22 não é afetado.
- **O que o #22 usa do #19 — conferido no código mergeado em 11/11/2026** (`V15__product_publication.sql` com `first_published_at` e o check `products_publication_consistent`):
  - `Products.everPublished(UUID id) -> boolean` (package-private, `Products.java:148`; `false` se o Produto não existe). O `ProductFileReplacementAccess` fica no pacote `product`, então o acesso package-private basta;
  - `ProductResponse.firstPublishedAt` (`Instant`, nulo em rascunho) e `Product.firstPublishedAt: string | null` no `schemas.ts`; a fixture `product({...})` do `products/testing.tsx` já aceita `status` e `firstPublishedAt`;
  - as duas conferências em `ProductFileService` — a pré-checagem em `upload` (linhas 69–74, antes do validador, grava `FAILURE` independente) e **a que conta**, em `store`, dentro da `transaction.execute` logo depois de `files.lockProduct` (linhas 93–96); o `catch` de `store` apaga o objeto novo e grava `FAILURE` independente quando a exceção é `ProductFileRejectedException`;
  - `ProductFileRejection.PUBLISHED_BEFORE` e o ramo `case PUBLISHED_BEFORE -> HttpStatus.CONFLICT` em `ProductFileController.java:111` (e o Javadoc da linha 53);
  - os testes do #19 que esperam o `409`: em `AdminProductFileUploadTest` — `SEC-008: depois de publicar e despublicar, trocar o arquivo é recusado`, `SEC-008: Produto publicado não aceita arquivo novo` e `SEC-008: a publicação que entra antes da trava faz a troca em andamento ser recusada`; em `ProductFileServiceTest` — `SEC-008: a Product that was ever published refuses a new file, stores nothing and audits a FAILURE`; em `ProductFileControllerTest` — o caso `PUBLISHED_BEFORE -> 409` dentro de `SEC-043: cada recusa vira 400 com o código, FILE_TOO_LARGE vira 413 e PUBLISHED_BEFORE vira 409`;
  - no frontend: `PUBLISHED_BEFORE_TEXT` e `FILE_MESSAGES.PUBLISHED_BEFORE` (`messages.ts:58–72`, texto 10 do #19), `PUBLICATION_MESSAGES.confirmQuestion` (`messages.ts:105`, texto 25 do #19), o código `PUBLISHED_BEFORE` em `FILE_REJECTION_CODES` (`schemas.ts:34`), o ramo `firstPublishedAt !== null` que esconde o formulário (`ProductFileSection.tsx:73–77`), os testes `ProductFileSection.test.tsx:132–152` (frase de bloqueio e `SEC-008 409 PUBLISHED_BEFORE from the API shows the same sentence`), `ProductPublicationSection.test.tsx:81` (frase de confirmação) e o e2e `FR-011 FR-016 SEC-008: o Administrador publica um Produto com arquivo, despublica e não consegue trocar o arquivo` (`admin-panel.e2e.ts:591`).

## 3. Reautenticação

**Rota:** `POST /api/admin/sessions/reauthentication`, corpo `{"password": "...", "code": "123456"}`. Liberada por `.requestMatchers(HttpMethod.POST, "/api/admin/sessions/reauthentication").access(panel(Capability.PANEL_ACCESS))` antes do `/api/**` negado. CSRF exigido.

**Respostas:**

| Situação | Resposta |
|---|---|
| Senha e código certos | `204`; ID da sessão e token CSRF trocados (`passwordLogin.startSession` com `AdminSessionController.panelSessionStrategy()`); principal novo com `passwordVerifiedAt = totpVerifiedAt = clock.instant()` e a mesma `sessionVersion` |
| Senha errada; código errado, malformado ou já usado | `400 {"errors":{"credentials":"CREDENTIALS_INVALID"}}` (uma resposta só) |
| Conta ou IP bloqueados | `429` com `Retry-After` |
| Banco/auditoria fora | `503` sem corpo |
| Anônimo / sessão pendente de MFA | `401` (comportamento atual da `SecurityConfig`) |
| Sessão de Cliente; sem token CSRF | `403` |
| Senha > 128 caracteres, campos ausentes | `400 CREDENTIALS_INVALID` sem calcular hash e sem tocar no limitador (como o login) |

**Ordem (serviço `AdministratorReauthentication`, pacote `admin`):**
1. Limites de tamanho (acima).
2. `limiter.admit("admin:" + Emails.normalize(principal.email()), ip)`; bloqueado → auditoria `BLOCKED`, `429`.
3. `administrators.findForLogin(email)`; vazio ou id diferente do principal → tratado como senha errada. `passwordEncoder.matches`. Errada → a falha fica no balde, auditoria `FAILURE`, `400`. **O código não é conferido** (não gasta tentativa de código nem o passo TOTP). Banco fora → `release` e `503`.
4. Senha certa → `limiter.recordSuccess(passwordKey)` (como o login) e `AdministratorMfa.reauthenticate(id, code, ip)`: o `guarded(...)` existente com `AuditAction.ADMIN_REAUTHENTICATION` (chave `admin-mfa:<id>`, auditoria `SUCCESS` na transação do passo, `FAILURE`/`BLOCKED` independentes).
5. **Replay guard (ajuste 3a):** igual ao login. `Totp.matchingStep` acha o passo do código e o aceite é o `UPDATE` condicional `Administrators.acceptStep(id, step)` (`totp_last_step` nulo ou menor). `0` linhas → `REJECTED`. Assim o código do login não vale na reautenticação, o da reautenticação não vale de novo, e duas reautenticações paralelas com o mesmo código dão um `204` e um `400`.
6. `ACCEPTED` → o controller abre a sessão nova; `REJECTED` → `400 CREDENTIALS_INVALID`; `LOCKED` → `429`; `UNAVAILABLE` → `503`; `NOT_ENROLLED` (impossível para Administrador completo) → `503` com log.

**Auditoria:** `AuditAction.ADMIN_REAUTHENTICATION` (a checagem `^[A-Z_]+$` da tabela aceita sem migration), `AuditObject.ADMINISTRATOR`, id do Administrador, origem. Nunca senha, código nem e-mail.

**Onde fica o carimbo:** no `SecurityContext` da sessão (memória do servidor, ADR-0004), dentro do `AdministratorPrincipal` (`passwordVerifiedAt`, `totpVerifiedAt`), que já viram `FactorGrantedAuthority.issuedAt`. Nenhuma coluna nova. O login completo também conta como autenticação recente durante a janela.

**"Recente":** `RecentAuthentication.isRecent(AdministratorPrincipal p)` = `passwordVerifiedAt` **e** `totpVerifiedAt` maiores que `clock.instant().minus(window)`. Classe pública no pacote `admin` (usada pelo pacote `product`). Por que não `RequiredFactor.validDuration` do Spring Security 7.1: a exigência depende do estado do Produto no banco, que um `AuthorizationManager` de fatores não conhece; comparar dois `Instant` com o `Clock` é menos código e testável com relógio fixo.

**Consequência aceita (ajuste 3b, vai na linha do ADR-0004):** como os contadores são os do login, quem roubar uma sessão aberta do Administrador pode errar a senha 5 vezes na reautenticação e bloquear o login do painel daquela conta por 15 minutos. É o mesmo risco do login já aceito no ADR-0004; a saída continua sendo reiniciar a API.

## 4. Regra na troca do arquivo

### 4.1 Autorização antes do corpo

`ProductFileReplacementAccess` (pacote `product`, `public` para a `SecurityConfig`, no padrão do `AdminMfaAccess`):
- Rota: `.requestMatchers(HttpMethod.POST, "/api/admin/products/*/file").access(productFileReplacementAccess)` (substitui o `panel(PRODUCTS_FILE)` atual).
- `authorize`: primeiro delega a `SecurityConfig.panel(Capability.PRODUCTS_FILE)`; negado → devolve essa decisão (401/403 como hoje). Depois lê `{id}` do caminho (`context.getVariables()`; não é UUID → concede e deixa o controller responder como hoje). `products.everPublished(id) == false` (rascunho ou inexistente) → concede. `true` → concede só se `RecentAuthentication.isRecent`; concedido assim grava o atributo de requisição `ProductFileReplacementAccess.REAUTHENTICATED = Boolean.TRUE`. Negado → auditoria `ADMIN_PRODUCT_FILE_UPLOADED`/`BLOCKED` (independente) e `new ReauthenticationRequired()` (`extends AuthorizationDecision`, `granted = false`).
- Banco fora ao ler o marcador → `new Unavailable()` (`extends AuthorizationDecision`, `granted = false`).
- Por que antes do corpo: um upload de 50 MB não é recebido para depois ser recusado, e a janela vale no início da requisição (Review Focus 1).

### 4.2 Resposta quando falta reautenticação (ajuste 3c)

`403 Forbidden`, `Content-Type: application/problem+json`, corpo exato:

```json
{
  "type": "about:blank",
  "title": "Reauthentication required",
  "status": 403,
  "instance": "/api/admin/products/{id}/file",
  "errors": { "reauthentication": "REAUTHENTICATION_REQUIRED" }
}
```

- Produzido em dois lugares, com o mesmo `ProblemDetail` (método estático `ProductFileReplacementAccess.problem(HttpServletRequest)`): pelo `SecurityConfig.accessDeniedHandler()` quando a `AuthorizationDeniedException` traz `ReauthenticationRequired` em `getAuthorizationResult()`, e pelo handler de `ProductFileReauthenticationRequiredException` no `ProductFileController` (recheque do serviço). `Unavailable` → `503` sem corpo. CSRF ausente/errado continua `403` sem esse corpo; pendente de MFA continua `401`.
- Por que `403` e não `409`: falta uma credencial recente do usuário, não há conflito com o estado do recurso; e `401` faria a tela tratar como sessão encerrada.
- **Como substitui a trava provisória do #19 (o que sai e o que entra):**

| Onde | Sai (#19) | Entra (#22) |
|---|---|---|
| `ProductFileRejection` | `PUBLISHED_BEFORE` | — (o enum volta a ter só recusas do PDF) |
| `ProductFileController` | `case PUBLISHED_BEFORE -> CONFLICT` e "409 PUBLISHED_BEFORE" no Javadoc | lê o atributo `REAUTHENTICATED`; `@ExceptionHandler(ProductFileReauthenticationRequiredException)` → corpo de 4.2 |
| `ProductFileService.upload` (pré-checagem, antes do validador) | `if (everPublished) { FAILURE; throw Rejected(PUBLISHED_BEFORE) }` | `if (!reauthenticated && everPublished) { BLOCKED; throw ReauthenticationRequired }` — continua barata, antes dos 20 s do validador |
| `ProductFileService.store` (**dentro** da `transaction.execute`, logo depois de `files.lockProduct`) | `if (everPublished) throw Rejected(PUBLISHED_BEFORE)` | `if (!reauthenticated && everPublished) throw ReauthenticationRequired` — **mesmo lugar, mesma transação, mesma trava da linha** (a publicação pega a mesma trava, então o marcador não muda até o commit). `store` ganha o parâmetro `boolean reauthenticated` |
| `catch` de `store` | `FAILURE` independente para `ProductFileRejectedException` | igual para ela; `BLOCKED` independente para `ProductFileReauthenticationRequiredException`; o objeto novo é apagado nos dois casos (já é) |
| Comentário "Ticket #22 replaces both…" | sai | comentário dizendo que a conferência sob a trava é a que vale |

  - Os cinco testes do #19 listados na seção 2 mudam: os três de `AdminProductFileUploadTest` passam a esperar `403` com o corpo de 4.2 e `BLOCKED` (o resto das asserções — chave antiga mantida, nenhum objeto novo — fica igual), e ganham uma versão "com reautenticação recente → 200"; o de `ProductFileServiceTest` espera `ProductFileReauthenticationRequiredException` + `BLOCKED` com `reauthenticated=false` e o swap com `reauthenticated=true`; o de `ProductFileControllerTest` perde o caso `PUBLISHED_BEFORE` (título sem "e PUBLISHED_BEFORE vira 409").
  - Frontend: sai o código `PUBLISHED_BEFORE` do `FILE_REJECTION_CODES` e `FILE_MESSAGES.PUBLISHED_BEFORE`; o `403` com esse corpo vira `ProductFileReauthenticationRequiredError` (seção 5).
  - Não há compatibilidade com o `409`: a SPA e a API saem no mesmo deploy (imagem única).

### 4.3 Recheque na trava e serviço

`ProductFileService.upload(...)` ganha o último parâmetro `boolean reauthenticated` (o controller lê o atributo de 4.1; ausente = `false`). O cheque barato (antes da validação do PDF, para não gastar os 20 s do validador) e o cheque dentro da trava usam a condição de 4.2. O cheque dentro da trava é o que vale para a corrida com a publicação (Review Focus 2).

### 4.4 Uma só versão atual (FR-010) — confirmado no código do #18

`ProductFileService.store` troca o ponteiro (`product_files`, chave primária `product_id`) numa transação com `FOR UPDATE` e só **depois do commit** (`transaction.execute` já retornou) chama `deleteBestEffort(chaveAntiga)`. Falha do banco apaga o objeto **novo** e mantém o antigo. Falha ao apagar o antigo deixa um órfão que nunca é entregue, porque o download (#33) lê a chave do banco. Nada muda; a Task 3 só acrescenta um teste para o caso "Produto já publicado".

Reconferido em 11/11/2026: o #19 não mexeu em `store` além da conferência sob a trava (o ponteiro único em `product_files`, PK `product_id`, e o `deleteBestEffort` depois do commit são os do #18/#82). O #33 ("Download com link de 60 segundos") tem o critério "Entrega sempre a versão mais recente do arquivo" e emite o link a cada clique: basta ele ler `product_files.object_key` no momento do clique, sem cache da chave e sem guardar versão por compra. O #22 não cria nada para o #33; o que o #33 precisa saber fica escrito no FR-010 do teste de ponteiro (chave antiga fora do Storage, linha única). Link de 60 s já emitido para a chave antiga pode falhar se a troca acontecer nesses 60 s — o Cliente clica de novo e recebe a nova; risco do #33, anotado aqui para ele.

### 4.5 Testes negativos (SEC-011 e "sessão esquecida aberta não basta")

O Produto não tem dono (um Administrador). Provas: Cliente autenticado → `403` igual para id existente e inexistente; Administrador sem reautenticação → id inexistente `404`, id de Produto publicado `403 REAUTHENTICATION_REQUIRED`, id de rascunho `200`; reautenticar e trocar o arquivo do Produto A não muda linha nem objeto do B.

**Outra sessão:** o carimbo vive no `SecurityContext` de cada sessão. Sessão A reautentica; sessão B do mesmo Administrador, com login de 5 min + 1 s atrás, tenta trocar o arquivo do Produto publicado → `403 REAUTHENTICATION_REQUIRED`, nada no Storage nem em `product_files`, e `audit_events` com `ADMIN_PRODUCT_FILE_UPLOADED`/`BLOCKED` (Administrador, Produto, origem; sem nome do arquivo). Mesma coisa para a própria sessão A depois que a janela vence (relógio avançado).

## 5. Tela

Componente existente `ProductFileSection.tsx`. Sai a frase do #19 que trocava o formulário em Produto já publicado. Quando `product.firstPublishedAt !== null`:
- Mostra o formulário de envio de sempre e a orientação extra da seção 6.
- "Enviar arquivo" valida o arquivo no navegador como hoje e, em vez de enviar, abre o formulário **inline** (não modal) abaixo do campo: senha, código, "Confirmar e enviar", "Cancelar". O `File` fica num `useState` do componente; o `<input type="file">` não é limpo. Cancelar fecha o formulário e mantém o arquivo escolhido.
- "Confirmar e enviar": `reauthenticate({password, code})` → `204` → `uploadProductFile(id, file)` com o **mesmo** `File`. O `apiFetch` relê o cookie `XSRF-TOKEN` a cada chamada, então o token novo da sessão nova vale.
- Upload que volta `403 REAUTHENTICATION_REQUIRED` (janela venceu, outra aba publicou) → reabre o formulário com a mensagem de "venceu", arquivo mantido.
- Senha e código são apagados do estado depois de cada tentativa; campos com `autocomplete="current-password"` e `autocomplete="one-time-code"`, `inputMode="numeric"`. Ao abrir, o foco vai para a senha; ao cancelar, volta ao botão "Enviar arquivo".
- Rascunho nunca publicado: comportamento do #18 sem nenhuma mudança.

Funções novas: `reauthenticate(body): Promise<void>` em `frontend/src/features/admin/api.ts` (erros `ReauthenticationRejectedError` para o 400 e `ReauthenticationLockedError` para o 429; 401 e 503 com os erros existentes); `ProductFileReauthenticationRequiredError` em `products/api.ts`. Hook `useReauthenticate()` em `admin/hooks.ts`.

**Distinguir os dois `403` (em `uploadProductFile`, antes do `check(raw, 200)`):** se `raw.status === 403`, ler o corpo com `.json().catch(() => null)`; se `errors.reauthentication === "REAUTHENTICATION_REQUIRED"` → `ProductFileReauthenticationRequiredError` (a tela abre/reabre o formulário de senha e código); qualquer outro `403` (sem capacidade, CSRF velho, Cliente, corpo vazio ou não-JSON) → `ProductForbiddenError` de sempre (texto `Você não tem permissão para enviar o arquivo do Produto.`). O mesmo vale na tela: só o erro novo abre o formulário; o resto segue o tratamento do #18. Testes Vitest: `403` com o corpo de 4.2 → formulário; `403` sem corpo → texto de permissão, sem formulário.

**Seção "Publicação" (#19):** só muda o texto de `PUBLICATION_MESSAGES.confirmQuestion` (seção 6); nenhum comportamento muda.

## 6. Textos da seção "Arquivo do produto" (todos)

Do #18, sem mudança:

| Onde | Texto |
|---|---|
| Título da seção | `Arquivo do produto` |
| Sem arquivo | `Nenhum arquivo enviado.` |
| Com arquivo | `Arquivo atual: {N} páginas, {tamanho}, enviado em {dd/mm/aaaa} às {hh:mm}.` (1 página: `1 página`) |
| Orientação | `Somente PDF, até 50 MB e 1.000 páginas.` |
| Orientação extra, com arquivo | `Enviar outro arquivo substitui o atual.` |
| Rótulo do campo | `Arquivo PDF` |
| Botão | `Enviar arquivo` |
| Botão durante o envio | `Enviando arquivo…` |
| Sucesso | `Arquivo enviado.` |
| `FILE_REQUIRED` | `Selecione um arquivo PDF.` |
| `FILE_EMPTY` | `O arquivo está vazio.` |
| `FILE_TOO_LARGE` | `O arquivo passa de 50 MB. Envie um PDF menor.` |
| `FILE_NOT_PDF` | `O arquivo não é um PDF. Envie um arquivo PDF.` |
| `FILE_INVALID` | `Não foi possível ler este PDF. Ele pode estar corrompido. Gere o PDF de novo e tente outra vez.` |
| `FILE_ENCRYPTED` | `O PDF está protegido por senha. Envie uma versão sem proteção.` |
| `FILE_TOO_MANY_PAGES` | `O PDF passa de 1.000 páginas. Envie um arquivo com até 1.000 páginas.` |
| 403 (sem capacidade) | `Você não tem permissão para enviar o arquivo do Produto.` |
| 404 | `Produto não encontrado.` |
| 503 (envio ou confirmação) | `O sistema está indisponível no momento e o arquivo não foi enviado. Tente novamente em alguns instantes.` |
| Falha de rede ou resposta inesperada (envio ou confirmação) | `Não foi possível enviar o arquivo. Verifique a conexão e tente novamente.` |
| 401 (sessão encerrada) | o tratamento existente (`ProductSessionEndedError`), sem texto novo |

Novos do #22 (aprovados no item 2 em 10/11; **reapresentados para aprovação final em 11/11**, sem mudança de redação):

| Onde | Texto |
|---|---|
| Orientação extra, só em Produto já publicado | `Este Produto já foi publicado. Para trocar o arquivo, confirme sua senha e o código do aplicativo autenticador. Quem já comprou passa a baixar a nova versão.` |
| Título do formulário | `Confirme sua identidade` |
| Rótulo | `Senha` |
| Rótulo | `Código do aplicativo autenticador` |
| Botão | `Confirmar e enviar` |
| Botão durante a confirmação | `Confirmando…` |
| Botão | `Cancelar` |
| 400 `CREDENTIALS_INVALID` | `Senha ou código incorretos. Confira e tente de novo.` |
| 429 | `Muitas tentativas. Aguarde alguns minutos e tente de novo.` |
| 403 `REAUTHENTICATION_REQUIRED` no envio | `A confirmação venceu. Digite sua senha e o código de novo para enviar o arquivo.` |

Substituídos (eram do #19, `frontend/src/features/products/messages.ts`) — **aguardam aprovação**:

| Texto do #19 | Sai | Entra |
|---|---|---|
| 10 — `PUBLISHED_BEFORE_TEXT` (seção "Arquivo do produto", no lugar do formulário) | `Este Produto já foi publicado. A troca do arquivo vai exigir nova autenticação, que ainda não está disponível.` | A constante e o código `PUBLISHED_BEFORE` saem. No lugar da frase, o formulário volta, com a orientação extra acima (`Este Produto já foi publicado. Para trocar o arquivo, confirme sua senha e o código do aplicativo autenticador. Quem já comprou passa a baixar a nova versão.`) |
| 25 — `PUBLICATION_MESSAGES.confirmQuestion` (confirmação de "Publicar") | `Publicar este Produto? Ele vai aparecer no Catálogo. Depois da primeira publicação, o arquivo só poderá ser trocado com nova autenticação, que ainda não está disponível.` | `Publicar este Produto? Ele vai aparecer no Catálogo. Depois da primeira publicação, trocar o arquivo vai exigir sua senha e o código do aplicativo autenticador.` |

## 7. Testes por requisito

| Teste (título com ID) | Tipo | Prova |
|---|---|---|
| `SEC-008: senha e código certos renovam os dois fatores e trocam a sessão` | integração (`AdminReauthenticationTest`) | 204, novo `JSESSIONID` e `XSRF-TOKEN`, principal com os dois `Instant` = relógio |
| `SEC-008: senha errada responde CREDENTIALS_INVALID sem conferir o código` | integração | o passo TOTP não é gasto (o mesmo código vale logo depois com a senha certa) |
| `SEC-008: código errado ou malformado responde CREDENTIALS_INVALID` | integração | |
| `SEC-008: o código usado no login não vale na reautenticação` | integração | ajuste 3a: mesmo passo do login → 400; `totp_last_step` não muda |
| `SEC-008: o código de uma reautenticação não vale numa segunda` | integração | ajuste 3a: 204, depois o mesmo código → 400 |
| `SEC-008: duas reautenticações paralelas com o mesmo código dão um 204 e um 400` | integração | ajuste 3a: `CountDownLatch`, como no teste de corrida do login |
| `SEC-008: código de recuperação não serve para reautenticar` | integração | D4 |
| `SEC-008: 5 senhas erradas bloqueiam a reautenticação e o login do painel com 429` | integração | ajuste 3b: `Retry-After`; `POST /api/admin/sessions` com a senha certa também `429` |
| `SEC-008: 5 códigos errados bloqueiam a reautenticação com 429` | integração | chave `admin-mfa:<id>` compartilhada com o login |
| `SEC-010: anônimo e sessão pendente recebem 401, Cliente 403, sem token CSRF 403` | integração | rota negada por padrão |
| `SEC-065: reautenticação certa, errada e bloqueada vão para a auditoria sem senha nem código` | integração | `audit_events` e log capturado sem a senha nem o código |
| `SEC-008: ReauthenticationRequest não mostra senha nem código no toString` | unit | |
| `SEC-008: RecentAuthentication aceita até a janela e recusa 1 s depois; fator TOTP velho também recusa` | unit | relógio fixo |
| `ADR-0004: rascunho nunca publicado troca o arquivo sem reautenticação` | integração (`AdminProductFileReplacementTest`) | 200, regressão do #18 |
| `SEC-008 ADR-0004: Produto publicado e despublicado recusa a troca sem reautenticação recente` | integração | login há 5 min + 1 s → corpo exato da seção 4.2, Storage e banco iguais, auditoria `BLOCKED` (substitui o teste do #19) |
| `SEC-008: a recusa vem antes de ler o corpo` | integração (`RANDOM_PORT`) | corpo de 50 MB em stream: 403 sem temporário criado |
| `SEC-008 FR-010: depois de reautenticar, a troca vale e o objeto anterior sai do Storage` | integração | ponteiro novo em `product_files`, chave antiga fora de `storage.keys()` |
| `SEC-008 SEC-017: depois da reautenticação o envio usa a sessão e o token CSRF novos` | integração (`RANDOM_PORT`, cliente HTTP com cookies de verdade) | ajuste 3d: o `JSESSIONID` e o `XSRF-TOKEN` mudam no 204; envio com a sessão nova e o token **velho** → 403 sem o corpo de 4.2, nada no Storage nem em `product_files`; envio com o `JSESSIONID` **velho** → 401; envio com a sessão e o token novos → 200 |
| `SEC-008 FR-010: outra sessão sem reautenticação recente é recusada e a tentativa vai para a auditoria` | integração (`AdminProductFileReplacementTest`) | seção 4.5: sessão A reautentica; sessão B (login há 5 min + 1 s) → 403 com o corpo de 4.2, Storage e `product_files` iguais, `ADMIN_PRODUCT_FILE_UPLOADED`/`BLOCKED` com o id do Administrador e do Produto |
| `SEC-008 FR-010: sessão esquecida aberta, com a janela vencida, não troca o arquivo` | integração | mesma sessão que reautenticou, relógio avançado 5 min + 1 s → 403, `BLOCKED` |
| `SEC-008: janela que vence durante o upload não recusa o envio já autorizado` | integração | Review Focus 1 |
| `SEC-008: a publicação que entra antes da trava faz a troca em andamento ser recusada` | integração (`AdminProductFileUploadTest`, teste do #19) | Review Focus 2: agora 403 com o corpo de 4.2 e `BLOCKED`; objeto novo apagado |
| `SEC-008: depois de publicar e despublicar, trocar o arquivo é recusado` e `SEC-008: Produto publicado não aceita arquivo novo` | integração (`AdminProductFileUploadTest`, testes do #19) | títulos acrescidos de "sem reautenticação recente"; 409 → 403 com o corpo de 4.2, `FAILURE` → `BLOCKED` |
| `SEC-008: a Product that was ever published refuses a new file without recent reauthentication, stores nothing and audits BLOCKED` | integração do serviço (`ProductFileServiceTest`, teste do #19 reescrito) | + caso `reauthenticated=true` → swap feito |
| `SEC-043: cada recusa vira 400 com o código e FILE_TOO_LARGE vira 413` | unit (`ProductFileControllerTest`, teste do #19) | sem o caso `PUBLISHED_BEFORE` |
| `SEC-011: Cliente recebe 403 igual para id existente e inexistente; Administrador 404 para inexistente` | integração | |
| `SEC-011: reautenticar e trocar o arquivo do Produto A não toca o B` | integração | |
| `ProductFileSection`: rascunho igual ao #18; publicado mostra a orientação, abre o formulário sem chamar o upload, envia o mesmo `File` depois do 204, mantém o arquivo no erro e no Cancelar, cada resposta vira o texto da seção 6, campos limpos depois da tentativa, foco | Vitest | substitui os dois testes do #19 da frase de bloqueio |
| `SEC-008: 403 REAUTHENTICATION_REQUIRED reabre o formulário; 403 sem esse corpo mostra o texto de permissão` | Vitest (`ProductFileSection.test.tsx`) | seção 5, os dois `403` |
| `ProductPublicationSection`: a confirmação mostra o texto 25 novo | Vitest (teste do #19, só a string) | |
| `FR-011 FR-016 SEC-008 FR-010: o Administrador publica, despublica e troca o PDF depois de confirmar senha e código` | e2e (`admin-panel.e2e.ts`, o teste do #19 estendido) | Task 5 |

## 8. Decisões

Aprovadas em 10/11/2026: D1 (o #22 depende do #19 e só lê `first_published_at`), D2 (janela de 5 min em `app.admin.reauthentication-window`), D3 (a tela sempre pede senha e código em Produto já publicado), D4 (só TOTP), D5 (resposta única `CREDENTIALS_INVALID`), D6 (sessão nova a cada reautenticação), D7 (textos novos da seção 6).

**Janela (D2), reconferida em 11/11:** o ADR-0004 não define a janela de "autenticação recente" (só diz, na linha 61, que a troca "exige a nova autenticação do #22"). Fica a proposta já aprovada: **5 minutos**, porque (a) é o mesmo prazo da sessão pendente de MFA (`app.admin.mfa-pending-ttl`), já aceito no ADR-0004 como "tempo de digitar a senha e o código"; (b) dá para escolher o arquivo e clicar, já que a decisão vale no início da requisição e um upload lento de 50 MB não é cortado; (c) é curto o bastante para a sessão esquecida aberta (2 horas sem uso) não valer. Custo se estiver errada: curto demais → o Administrador redigita senha e código numa segunda troca seguida (só incômodo; configurável sem código); longo demais → janela maior para quem pegar o computador destravado logo depois.

Aguardando aprovação final:
- **D7** — a lista completa da seção 6, incluindo os dois substitutos dos textos 10 e 25 do #19.
- **D9 — o login completo conta como autenticação recente durante a janela** (seção 3, "Onde fica o carimbo"). Quem acabou de entrar com senha e TOTP (há menos de 5 min) troca o arquivo sem digitar de novo. Custo se estiver errada: se você quiser senha e código **sempre** na hora da troca, é um campo a mais no carimbo (`reauthenticatedAt`) em vez de reaproveitar os dois do login — pouco código, mas muda os testes da Task 2.
- **D10 — o e2e do #19 é estendido, não duplicado:** `FR-011 FR-016 SEC-008: … despublica e não consegue trocar o arquivo` vira `FR-011 FR-016 SEC-008 FR-010: … despublica e troca o PDF depois de confirmar senha e código`; a parte de criar/publicar/despublicar fica igual e o final troca a frase de bloqueio pelo fluxo de reautenticação. Custo se estiver errada: um teste e2e mais longo (um só ponto de falha para publicação e troca); separar depois é mover ~30 linhas de preparo para um helper.
- **D8 — ADR-0004:** a linha 61 (a do #19, "Enquanto o Produto nunca foi publicado…") é substituída por:

> Enquanto o Produto nunca foi publicado (`first_published_at` nulo), o Administrador envia e substitui o Arquivo do produto sem nova autenticação. Depois da primeira publicação, mesmo despublicado, a troca exige senha e código TOTP digitados há no máximo `app.admin.reauthentication-window` (5 minutos), em `POST /api/admin/sessions/reauthentication`, com os mesmos limites de tentativas, a mesma proteção contra reuso do código TOTP e a mesma auditoria do login; só o TOTP serve (código de recuperação não), e a resposta é a mesma para senha ou código errados. Sem a reautenticação, a troca responde `403` com `errors.reauthentication = REAUTHENTICATION_REQUIRED`, decidido antes de receber o arquivo. Consequência aceita: como os contadores são os do login, quem roubar uma sessão aberta do Administrador pode errar a senha 5 vezes na reautenticação e bloquear o login do painel por 15 minutos; a saída continua sendo reiniciar a API.

## 9. Riscos

- **Sessão em memória:** um deploy derruba a sessão e o carimbo junto; o Administrador entra de novo. Comportamento atual, aceito no ADR-0004.
- **Composição de `AuthorizationManager`:** o `ProductFileReplacementAccess` delega ao `panel(...)` em vez de usar `AuthorizationManagers.allOf`, para que a decisão que chega ao `AccessDeniedHandler` seja a nossa. O teste do corpo exato de 4.2 prova isso.
- **Consulta ao banco na autorização:** mais uma consulta por envio de arquivo; banco fora → `503` (falha fechada).
- **Bloqueio por senha errada compartilhado com o login:** ajuste 3b, registrado no ADR-0004.
- **#33 ainda não existe:** "a anterior deixa de ser entregue" é provado pelo ponteiro e pelo Storage; a entrega vem com o #33, que lê `product_files.object_key` a cada clique (seção 4.4).
- **Recheque fora da trava:** se a implementação mover a conferência de `store` para fora da `transaction.execute` (ou para depois de `files.swap`), a corrida com a publicação volta. O teste `a publicação que entra antes da trava…` é o guarda; não pode ser afrouxado.

## 10. Ordem das tarefas e subagentes (todos `model: sonnet`)

| Task | O quê | Depende de | Subagente |
|---|---|---|---|
| 1 | Merge da main com o #19 e conferência do plano (feito em 11/11, falta só o `just check-resumo` de base) | — | comigo |
| 2 | Reautenticação no backend | 1 | sonnet |
| 3 | Regra na troca do arquivo (substitui o `409 PUBLISHED_BEFORE`) | 1, 2 | sonnet |
| 4 | Tela | contrato das seções 3–5 (pode começar junto com a 3) | sonnet |
| 5 | e2e, ADR, sistema de verdade, entrega | 3, 4, D8 | sonnet para o e2e; o resto comigo |

---

## Tasks

### Task 1: Trazer o #19 e conferir o plano

- [x] **Step 1:** `git fetch origin` e `git merge origin/main` nesta branch (sem rebase) → merge `00df01e`, sem conflitos.
- [x] **Step 2:** conferido no código mergeado (seção 2).
- [x] **Step 3:** seções 2, 4.2, 4.4, 4.5, 5, 6, 7, 8 e Tasks 3–5 atualizadas; textos e D8–D10 apresentados ao Sérgio.
- [ ] **Step 4:** depois da aprovação, `just check-resumo` → OK (linha de base com o #19).
- [x] **Step 5: Commit** `docs: align the #22 plan with the merged #19 (#22)`.

### Task 2: Reautenticação no backend

**Files:**
- Create: `admin/RecentAuthentication.java` (público), `admin/AdministratorReauthentication.java`, `admin/AdminReauthenticationController.java`, `admin/ReauthenticationRequest.java` (`record(String password, String code)`, `toString()` redigido)
- Modify: `admin/AdministratorMfa.java` (+ `Outcome reauthenticate(UUID id, String code, String clientIp)`: o passo TOTP do `regenerateCodes` — `findTotp`, `Totp.matchingStep`, `acceptStep` na transação, auditoria `SUCCESS` — sem gerar códigos), `audit/AuditAction.java` (+ `ADMIN_REAUTHENTICATION`), `security/SecurityConfig.java` (regra da seção 3), `application.yml` e `backend/src/test/resources/application.properties` (`app.admin.reauthentication-window=PT5M`)
- Test: `admin/AdminReauthenticationTest.java` (integração), `admin/RecentAuthenticationTest.java` (unit), `AdminRouteDenyByDefaultTest` (rota nova)

**Interfaces:**
- Consumes: `LoginAttemptLimiter.admit/recordSuccess/release`, `Administrators.findForLogin`, `Administrators.acceptStep`, `PasswordEncoder`, `AdministratorMfa.guarded`, `PasswordLogin.startSession`, `AdminSessionController.panelSessionStrategy()`.
- Produces:
  - `public class RecentAuthentication { public boolean isRecent(AdministratorPrincipal principal) }` (bean; `Clock` + `@Value("${app.admin.reauthentication-window}") Duration`; janela não positiva → `IllegalStateException` na subida).
  - `AdministratorReauthentication.Result reauthenticate(AdministratorPrincipal who, String password, String code, String clientIp)` com `enum Verdict { ACCEPTED, REJECTED, LOCKED, UNAVAILABLE }` e `Duration retryAfter`.
  - `POST /api/admin/sessions/reauthentication` → `204` / `400 {"errors":{"credentials":"CREDENTIALS_INVALID"}}` / `429` + `Retry-After` / `503`.

- [ ] **Step 1: Testes que falham:** as linhas da seção 7 da reautenticação (de `senha e código certos` até `RecentAuthentication`), incluindo as três do replay guard. Exemplo:

```java
@Test
@DisplayName("SEC-008: o código usado no login não vale na reautenticação")
void loginCodeIsNotReusable() throws Exception {
    String loginCode = loginWithTotp(session); // accepts the current step
    reauthenticate(session, PASSWORD, loginCode)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.credentials").value("CREDENTIALS_INVALID"));
    assertThat(lastStep(administratorId)).isEqualTo(stepOf(loginCode));
}
```

- [ ] **Step 2:** `cd backend; .\mvnw.cmd test -Dtest='AdminReauthenticationTest,RecentAuthenticationTest,AdminRouteDenyByDefaultTest'` → FAIL.
- [ ] **Step 3: Implementar** na ordem da seção 3. Logs só com o tipo de erro.
- [ ] **Step 4:** mesmo comando, mais `-Dtest='AdminMfa*Test,AdminSession*Test,AuditPrivacyTest'` (o login não quebrou) → PASS.
- [ ] **Step 5: Commit** `feat: administrator reauthentication with password and TOTP (SEC-008) (#22)`.

### Task 3: Regra na troca do arquivo

**Files:**
- Create: `product/ProductFileReplacementAccess.java` (público; `ReauthenticationRequired` e `Unavailable` como subclasses de `AuthorizationDecision`; constante `REAUTHENTICATED`; `static ProblemDetail problem(HttpServletRequest)`), `product/ProductFileReauthenticationRequiredException.java`
- Modify: `security/SecurityConfig.java` (rota do arquivo usa o manager; `accessDeniedHandler` com os dois casos de 4.2), `product/ProductFileService.java` (parâmetro `boolean reauthenticated` em `upload` e `store`; as duas conferências do #19 trocadas conforme a tabela de 4.2, a de `store` no mesmo lugar dentro da trava; `catch` com `BLOCKED`), `product/ProductFileController.java` (lê o atributo; handler da exceção nova; remove o ramo `409` e o "409 PUBLISHED_BEFORE" do Javadoc), `product/ProductFileRejection.java` (remove `PUBLISHED_BEFORE`)
- Test: `admin/AdminProductFileReplacementTest.java` (integração nova, `RANDOM_PORT` para o teste do corpo e o do CSRF), `admin/AdminProductFileUploadTest.java` (os três testes do #19), `product/ProductFileServiceTest.java` (o teste do #19 e a assinatura nova), `product/ProductFileControllerTest.java` (sem o caso `PUBLISHED_BEFORE`)

**Interfaces:**
- Consumes: `RecentAuthentication.isRecent`, `SecurityConfig.panel(Capability)`, `Products.everPublished`, `AuditLog.recordIndependent`.
- Produces: resposta exata de 4.2 em `POST /api/admin/products/{id}/file`; `ProductFileService.upload(UUID, Path, String, String, UUID, String, boolean reauthenticated)`.

- [ ] **Step 1: Testes que falham:** as linhas `ADR-0004`, `SEC-008` da troca, `SEC-008 SEC-017` (CSRF novo, ajuste 3d), `FR-010` e `SEC-011` da seção 7; os dois testes do #19 passam a esperar 4.2. "Já publicado" é preparado pela rota do #19 (`PUT .../publication`, e `DELETE` para o despublicado), não por SQL.
- [ ] **Step 2:** `cd backend; .\mvnw.cmd test -Dtest='AdminProductFile*Test,AdminProductPublication*Test,ProductFile*Test'` → FAIL.
- [ ] **Step 3: Implementar** a seção 4.
- [ ] **Step 4:** mesmo comando, mais `AdminRouteDenyByDefaultTest,AuditPrivacyTest` → PASS; depois `just check-resumo` → OK.
- [ ] **Step 5: Commit** `feat: replacing a published product file requires recent reauthentication (SEC-008) (#22)`.

### Task 4: Tela

**Files:**
- Modify: `frontend/src/features/admin/api.ts` (`reauthenticate`), `admin/hooks.ts` (`useReauthenticate`), `products/api.ts` (`ProductFileReauthenticationRequiredError` para o 403 com o código; os dois `403` da seção 5), `products/schemas.ts` (sai `PUBLISHED_BEFORE` de `FILE_REJECTION_CODES`), `products/messages.ts` (sai `PUBLISHED_BEFORE_TEXT`/`FILE_MESSAGES.PUBLISHED_BEFORE`; `PUBLICATION_MESSAGES.confirmQuestion` com o texto 25 novo; entra `REAUTHENTICATION_TEXTS` com os textos novos da seção 6, exatamente), `products/ProductFileSection.tsx` (sai o ramo `firstPublishedAt !== null` que esconde o formulário), `products/ProductFileSection.test.tsx`, `products/ProductPublicationSection.test.tsx` (só a string da confirmação). A fixture publicada já existe (`product({ status, firstPublishedAt })` em `testing.tsx`).

**Interfaces:**
- Consumes: contratos das Tasks 2 e 3; `Product.firstPublishedAt`.
- Produces: `reauthenticate(body: { password: string; code: string }): Promise<void>`.

- [ ] **Step 1: Testes que falham:** a linha `ProductFileSection` da seção 7 (API simulada só no Vitest); os testes do #19 que esperavam a frase de bloqueio passam a esperar o formulário.
- [ ] **Step 2:** `cd frontend; pnpm vitest run src/features/products src/features/admin` → FAIL.
- [ ] **Step 3: Implementar** a seção 5, sem texto fora da seção 6 e sem componente ou dependência nova.
- [ ] **Step 4:** mesmo comando → PASS; `/verificar-tela` na edição de Produto publicado (desktop, celular, teclado).
- [ ] **Step 5: Commit** `feat: reauthentication form before replacing a published product file (#22)`.

### Task 5: E2E, ADR e entrega

**Files:**
- Modify: `frontend/e2e/admin-panel.e2e.ts` (o e2e do #19 que esperava a frase de bloqueio passa a usar o fluxo abaixo), `docs/adr/0004-identidade-e-mfa.md` (D8)

- [ ] **Step 1: e2e** (D10: estende o teste do #19, renomeado para `FR-011 FR-016 SEC-008 FR-010: o Administrador publica, despublica e troca o PDF depois de confirmar senha e código`; sai a constante `blocked` e a asserção de que o campo some) (contra API e banco de verdade, sem `page.route`): entra no painel com os helpers existentes; cria Produto; envia PDF (sem pedir senha, rascunho); publica e despublica pela tela (#19); a orientação de Produto já publicado aparece; escolhe outro PDF e clica "Enviar arquivo" → `Confirme sua identidade`; código errado → `Senha ou código incorretos. Confira e tente de novo.` e o arquivo continua escolhido; senha e código certos (próximo passo TOTP, com `waitForStepAfter`) → `Arquivo enviado.` e a linha "Arquivo atual" com a nova contagem de páginas. Rodar `just db`, `just dev-back`, `just dev-front`, `just e2e` → PASS.
- [ ] **Step 2:** sistema de verdade no navegador no fluxo principal; conferir em `audit_events` as linhas `ADMIN_REAUTHENTICATION` e `ADMIN_PRODUCT_FILE_UPLOADED` sem senha nem código.
- [ ] **Step 3:** ADR-0004 (D8, texto aprovado).
- [ ] **Step 4:** `just check-resumo` → OK; skill `verificar-entrega` com a tabela.
- [ ] **Step 5: Commit** `test: e2e for replacing a published product file (#22)` e `docs: reauthentication rule for product files (#22)`. Push e PR (`Closes #22`) só com o seu OK.
