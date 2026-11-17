# #73 — Troca do autenticador pelo próprio Administrador

**Origem:** issue #73 (Parent #5, depende de #16). **Refs:** `docs/adr/0004-identidade-e-mfa.md`; `docs/runbooks/recuperar-acesso-administrador.md`; `docs/spec/sapiensia-spec.md` (SEC-001, SEC-006, SEC-008, SEC-065).

**Status:** plano do modo contínuo; todas as decisões estão marcadas para conferência na PR.

**Objetivo:** o Administrador logado, ou que entrou com um código de recuperação, troca o autenticador sozinho. O segredo novo só vale depois de confirmado com um código dele; o antigo para de valer no mesmo instante; as outras sessões caem; os códigos de recuperação são regenerados; a ação fica na auditoria.

## O que muda

- Duas rotas novas do painel: `POST /api/admin/authenticator-replacement` (começa: devolve o segredo novo e o URI otpauth) e `POST /api/admin/authenticator-replacement/confirmation` (confirma com um código do segredo novo).
- `AdministratorMfa` ganha `startReplacement` e `confirmReplacement`, ambos dentro de `guarded(...)` (mesmo limitador `admin-mfa:<id>`, mesma proteção contra repetição de passo, mesma auditoria de FAILURE/BLOCKED).
- `AdministratorPrincipal` ganha o marcador `viaRecoveryCode` (a sessão nasceu de um código de recuperação). `GET /api/admin/sessions/current` passa a informar `oldCodeRequired`.
- Nova `Capability.AUTHENTICATOR_REPLACE`, nova `AuditAction.ADMIN_MFA_REPLACED`.
- Seção "Trocar o autenticador" na `AdminHomePage`. ADR-0004, runbook e `docs/api` atualizados.
- **Sem migration** (a V32 reservada não é usada): o segredo pendente fica só na sessão HTTP, cifrado.

## Decisões

| # | Decisão | Marca |
|---|---|---|
| D1 | O segredo novo pendente fica cifrado (SecretCipher, mesmo AAD `administrator-totp:<id>`) num atributo da sessão HTTP (`REPLACEMENT_SECRET`), como o `STARTED_ENROLLMENT`. Sem coluna e sem migration. O antigo segue valendo até a confirmação. Abandonar ou perder a sessão descarta o novo; começar de novo sobrescreve o atributo. | DECIDIDO NO MODO CONTÍNUO — conferir |
| D2 | Início com sessão TOTP exige o código atual, via `guarded(...)` (`acceptStep` + limitador + auditoria de falha). Sessão criada por código de recuperação não exige o código antigo, mas só dentro da janela de autenticação recente (`app.admin.reauthentication-window`, via `RecentAuthentication.oldCodeRequired`); depois dela vale a regra da sessão TOTP, e quem perdeu o celular entra de novo com outro código de recuperação. Para distinguir: componente novo `boolean viaRecoveryCode` no record `AdministratorPrincipal`, com construtor secundário de 6 argumentos que assume `false` (os usos existentes não mudam). O `recover` passa `true`; reautenticação e confirmação da troca deixam `false`. | DECIDIDO NO MODO CONTÍNUO — conferir |
| D3 | A confirmação (código do segredo novo; limitador e auditoria de falha) faz numa só transação: `incrementSessionVersion` primeiro (trava a linha, como `recover` e `regenerateCodes`), grava o cifrado novo com compare-and-set sobre o cifrado antigo lido, grava `totp_last_step` = passo confirmado, `recoveryCodes.replaceAll` com 10 novos e auditoria `ADMIN_MFA_REPLACED` SUCCESS. Depois recria a sessão atual com a versão nova (novo id e CSRF, mesmo padrão da reautenticação), então só ela sobrevive. Resposta: os 10 códigos novos, `no-store`. | DECIDIDO NO MODO CONTÍNUO — conferir |
| D4 | Os códigos de recuperação são regenerados (não só invalidados) e mostrados uma vez com `RecoveryCodesList`. | DECIDIDO NO MODO CONTÍNUO — conferir |
| D5 | `Capability.AUTHENTICATOR_REPLACE`, concedida como `RECOVERY_CODES_MANAGE`. As duas rotas usam `panel(Capability.AUTHENTICATOR_REPLACE)` na `SecurityConfig`, antes do `denyAll`. | DECIDIDO NO MODO CONTÍNUO — conferir |
| D6 | UI: seção "Trocar o autenticador" na `AdminHomePage`, abaixo dos códigos de recuperação. Extrai da `AdminMfaPage` o bloco chave/QR para um componente `AuthenticatorKey`, se for autocontido; senão, duplica o mínimo. | DECIDIDO NO MODO CONTÍNUO — conferir |
| D7 | Atualiza o ADR-0004 (sem ADR novo). O runbook aponta para o fluxo novo e mantém o procedimento manual para quem não tem nem celular nem código de recuperação. | DECIDIDO NO MODO CONTÍNUO — conferir |
| D8 | A tela sabe se pede o código antigo pelo campo novo `oldCodeRequired` em `GET /api/admin/sessions/current` (`!viaRecoveryCode`). Sem rota extra. Na sessão por recuperação, o corpo do início é vazio e o servidor ignora qualquer `code` enviado. | DECIDIDO NO MODO CONTÍNUO — conferir |
| D9 | O código antigo consumido no início conta como passo aceito (replay). O `totp_last_step` da confirmação é o passo do código novo, mesmo que seja menor que o último do segredo antigo, porque o segredo é outro. A janela de passos é a mesma do login. | DECIDIDO NO MODO CONTÍNUO — conferir |
| D10 | O início bem-sucedido não é auditado (nada mudou); falha e bloqueio do início são auditados como `ADMIN_MFA_REPLACED` FAILURE/BLOCKED. A confirmação audita SUCCESS, FAILURE e BLOCKED. | DECIDIDO NO MODO CONTÍNUO — conferir |

## Textos que o usuário vê

| Onde | Texto | Origem |
|---|---|---|
| Título da seção | Trocar o autenticador | novo |
| Explicação | Use isto se trocou de celular ou quer um aplicativo novo. O autenticador atual continua valendo até você confirmar o novo, e as outras sessões do painel são encerradas. | novo |
| Botão de começar | Trocar o autenticador | novo |
| Instrução (sessão TOTP) | Para começar, digite o código de 6 dígitos do autenticador atual. | novo |
| Instrução (sessão por recuperação) | Você entrou com um código de recuperação, então não precisa do código do autenticador atual. | novo |
| Rótulo do campo do código atual | Código do autenticador atual | novo |
| Botão do código atual | Continuar / Continuando… | novo |
| Passo 1 (QR) | 1. No aplicativo autenticador, leia o QR code abaixo. | reusa `ENROLL_STEP_1` da AdminMfaPage |
| Nome do QR | QR code para o aplicativo autenticador | reusa `QR_NAME` |
| Chave manual | Não consegue ler o QR code? Digite esta chave no aplicativo: | reusa o texto da AdminMfaPage |
| Passo 2 | 2. Digite o código de 6 dígitos que o aplicativo novo mostra. | novo |
| Rótulo do código novo | Código do aplicativo novo | novo |
| Botão de confirmar | Confirmar a troca / Confirmando… | novo |
| Cancelar | Cancelar | reusa `CANCEL` |
| Código atual errado, vencido ou já usado | Código incorreto ou vencido. Aguarde o próximo código do aplicativo e tente de novo. | reusa `WRONG_CODE` |
| Código novo errado | Código incorreto ou vencido. Aguarde o próximo código do aplicativo novo e tente de novo. | novo |
| 429 bloqueado | `LOGIN_BLOCKED` de `features/session/messages` | reusa |
| Troca interrompida (409) | A troca foi interrompida. Comece de novo. | novo |
| Erro geral | Não foi possível trocar o autenticador agora. Tente novamente. | novo |
| Resultado (título) | Autenticador trocado. Seus novos códigos de recuperação | novo |
| Resultado (aviso) | O autenticador anterior e os códigos anteriores não funcionam mais. Estes códigos aparecem só agora. Guarde-os no seu gerenciador de senhas. Quem tiver um código e a sua senha entra no painel. | novo |
| Concluir | Concluir | reusa `DONE` |

Nenhum texto novo no backend (só status e códigos de erro já existentes).

## Riscos

- **R1.** O marcador `viaRecoveryCode` se perder na recriação de sessão (reautenticação, confirmação). Há um teste para isso: depois da reautenticação por TOTP, o início volta a exigir o código.
- **R2.** Corrida entre duas confirmações, ou entre a troca e um login por recuperação: o compare-and-set sobre o cifrado antigo e a ordem das escritas evitam deadlock e troca dupla.
- **R3.** Uma sessão por recuperação roubada (senha + código de recuperação) troca o autenticador sem o antigo. É o desenho pedido (aceite 2); o que limita é o limitador na confirmação e a auditoria. Fica registrado no ADR-0004.
- **R4.** Segredo ou código em log, auditoria ou `toString`: os tipos novos redigem o `toString`, no padrão de `Started`/`Enrollment`.
- **R5.** E2E: o segredo guardado em `E2E_ADMIN_TOTP_FILE` precisa acompanhar a troca, senão os testes seguintes não entram (Task 3).

## Task 1: Backend: início e confirmação da troca, SecurityConfig e endpoints

**Files:**
- `backend/src/main/java/br/com/sergio/sapiensia/admin/AdministratorPrincipal.java` (componente `viaRecoveryCode` + construtor de 6 argumentos)
- `.../admin/Capability.java` (`AUTHENTICATOR_REPLACE`, com Javadoc)
- `.../audit/AuditAction.java` (`ADMIN_MFA_REPLACED`; confirmar que o regex da coluna `action` aceita, sem migration)
- `.../admin/Administrators.java` (`replaceTotp(id, oldCipher, newCipher, step)`: UPDATE condicional ao cifrado antigo e ao MFA ativo; devolve as linhas afetadas; usar os nomes reais das colunas)
- `.../admin/AdministratorMfa.java` (`startReplacement`, `confirmReplacement`)
- `.../admin/AdminAuthenticatorReplacementController.java` (novo; rotas da D5)
- `.../admin/AdminMfaController.java` (`answer` passa `viaRecoveryCode = true` no `recover`; o `verify` e a confirmação da ativação passam `false`)
- `.../admin/AdminSessionController.java` e o record da resposta de `GET /api/admin/sessions/current` (campo `oldCodeRequired`)
- `.../security/SecurityConfig.java` (junto de `recovery-codes`): dois `requestMatchers(HttpMethod.POST, ...)` com `panel(Capability.AUTHENTICATOR_REPLACE)`, antes do `denyAll`
- `backend/src/test/resources/openapi/endpoints.txt`: as duas rotas
- `docs/api/endpoints.md`: duas linhas (modelo da linha de `recovery-codes`): "Painel: `AUTHENTICATOR_REPLACE`", "limite: o do MFA do Administrador (ADR-0004)"; descrever o campo `oldCodeRequired` em `sessions/current`

**Comportamento:**
- `startReplacement(who, code, clientIp)`:
  - Sessão por recuperação (`viaRecoveryCode`): sem `guarded` de código; confere que o MFA está ativo (senão NOT_ENROLLED → 409), gera o segredo novo (`Totp.newSecret`), cifra com `secretAad(id)` e devolve `Started`. Não grava nada no banco.
  - Sessão TOTP: dentro de `guarded(id, ADMIN_MFA_REPLACED, ...)`, decifra o segredo atual, `Totp.matchingStep` e `acceptStep` numa transação (0 linhas = REJECTED, replay). Não audita SUCCESS (D10).
  - O controller guarda o cifrado novo no atributo `REPLACEMENT_SECRET` e responde 200 `no-store` com o mesmo formato do `Enrollment`. Outras respostas: 400 `CODE_INVALID` (código errado, malformado, já usado ou ausente), 429 com `Retry-After`, 409 sem MFA ativo, 503.
- `confirmReplacement(who, pendingCipher, code, clientIp)` dentro de `guarded(id, ADMIN_MFA_REPLACED, ...)`:
  - `pendingCipher == null` ou MFA inativo: NOT_ENROLLED (409; não conta como falha de código).
  - `Totp.matchingStep` com o segredo novo; se não bate: REJECTED.
  - Transação: `incrementSessionVersion`; `replaceTotp` com o cifrado antigo lido (0 linhas: rollback e NOT_ENROLLED); `recoveryCodes.replaceAll` com 10 novos; `audit.recordInTransaction(ADMIN_MFA_REPLACED SUCCESS)`; devolve os códigos e a versão nova.
- Controller da confirmação: no ACCEPTED, monta o principal novo (versão nova, `viaRecoveryCode = false`, TOTP agora), chama `passwordLogin.startSession(..., AdminSessionController.panelSessionStrategy(), ...)` (novo id e CSRF; o atributo morre com a sessão antiga) e responde 200 `no-store` com `RecoveryCodeList`. Usa o mesmo `switch` de veredictos e o mesmo tratamento de `UnavailableException` do `AdminRecoveryCodesController`.

**Testes (JUnit + Testcontainers, no estilo de `AdminRecoveryCodesTest`; classes `AdminAuthenticatorReplacementTest` e `AdminAuthenticatorReplacementAuthorizationTest`; reusar `AdminMfaTestSupport`):**
- `SEC-006 SEC-008: a sessão TOTP começa a troca com o código atual e recebe segredo e URI sem cache`
- `SEC-008: a sessão TOTP não começa a troca sem código` e `...com código errado (audita ADMIN_MFA_REPLACED FAILURE, sem segredo na resposta)`
- `SEC-008 SEC-006: o código usado no login não começa a troca (replay)` e `o mesmo passo não serve duas vezes`
- `SEC-006: depois do limite de falhas o início responde 429 com Retry-After e audita BLOCKED` (limite compartilhado com o login MFA)
- `SEC-065 SEC-006: a sessão que entrou com código de recuperação começa a troca sem código antigo`
- `SEC-006: depois de reautenticar por TOTP, a sessão que veio de recuperação volta a exigir o código antigo` (R1)
- `SEC-001: antes da confirmação o segredo antigo continua entrando e o novo é recusado no login`
- `SEC-001: a confirmação sem troca iniciada na sessão responde 409 e não altera o segredo`
- `SEC-008: código errado do segredo novo é recusado, audita FAILURE e mantém o segredo antigo`
- `SEC-001 SEC-006: confirmada a troca, o segredo antigo é recusado e o novo entra`
- `SEC-006: confirmada a troca, a outra sessão do mesmo Administrador cai (401) e a atual segue com novo id e novo CSRF`
- `SEC-065: confirmada a troca, os códigos de recuperação antigos são recusados e os 10 novos funcionam uma vez`
- `SEC-008: duas confirmações simultâneas trocam uma vez só`
- `SEC-065: a auditoria tem ADMIN_MFA_REPLACED SUCCESS e nenhum segredo, código ou URI`; incluir as rotas novas na varredura de log do `AdminMfaLogTest`
- Autorização, nos dois endpoints: anônimo 401; Administrador pendente (só senha) recebe o mesmo que nas outras rotas do painel; Cliente 403; sem CSRF 403; `SEC-010: sem AUTHENTICATOR_REPLACE a troca é negada` (modelo: `AdminRouteDenyByDefaultTest`/`AdminProductCapabilityTest`)
- O teste de OpenAPI (`OpenApiEndpointsTest`) segue verde com as rotas novas.
- `AdministratorPrincipal`: o construtor de 6 argumentos assume `viaRecoveryCode=false`; o `toString` não mostra o e-mail.

## Task 2: Frontend: seção na AdminHomePage

**Files:**
- `frontend/src/features/admin/api.ts` (`startAuthenticatorReplacement(code?)`, `confirmAuthenticatorReplacement(code)`; erros no padrão de `regenerateRecoveryCodes`: 400 código recusado, 401 sessão expirada, 409 conflito, 429 bloqueado)
- `frontend/src/features/admin/schemas.ts` (`oldCodeRequired` no schema da sessão do Administrador; resposta do início igual ao schema da ativação)
- `frontend/src/features/admin/hooks.ts` (`useStartAuthenticatorReplacement`, `useConfirmAuthenticatorReplacement`, no padrão de `useRegenerateRecoveryCodes`: `gcTime: 0`, sem o código no cache; ao concluir, invalida `adminSessionQueryKey`)
- `frontend/src/features/admin/AuthenticatorKey.tsx` (extraído do bloco QR/chave da `AdminMfaPage`, se for autocontido; a `AdminMfaPage` passa a usá-lo sem mudar os testes dela)
- `frontend/src/features/admin/AuthenticatorReplacementSection.tsx` (novo) e uso em `AdminHomePage.tsx` logo abaixo de `<RecoveryCodesSection />`
- Textos: constantes no topo do componente, como em `RecoveryCodesSection`, exatamente como na tabela "Textos que o usuário vê"

**Comportamento:** estados `idle` → `askingOld` (se `oldCodeRequired`; senão, o clique já começa) → `confirming` (chave/QR + campo do código novo) → `result` (`RecoveryCodesList`). Erros em `role="alert"`; o foco vai para o campo ao abrir cada passo. Um 401 deixa agir o redirecionamento que já existe. `Cancelar` descarta o estado local. A chave nunca vai para log nem `localStorage`.

**Testes (Vitest, `AuthenticatorReplacementSection.test.tsx`):**
- `SEC-008: sessão TOTP pede o código atual antes de mostrar a chave`
- `SEC-065: sessão por recuperação começa sem pedir o código atual`
- `mostra a chave e o QR depois do início e pede o código novo`
- `código atual errado mostra "Código incorreto ou vencido..." e não mostra a chave`
- `código novo errado mostra a mensagem do código novo e mantém a tela`
- `429 mostra a mensagem de bloqueio`
- `409 mostra "A troca foi interrompida. Comece de novo."`
- `sucesso mostra os 10 códigos e o aviso; Concluir volta ao início e limpa o estado`
- `o botão fica desabilitado e mostra "Continuando…"/"Confirmando…" enquanto envia`
- `AdminHomePage.test.tsx`: a seção aparece abaixo dos códigos de recuperação
- `AdminMfaPage.test.tsx` segue verde com o `AuthenticatorKey`.

## Task 3: E2E sem mocks

**File:** `frontend/e2e/admin-panel.e2e.ts` (reusar `readStoredTotp`/`writeStoredTotp`, `waitForStepAfter`, `enterCode`, `readAndHideKey`, `readAndHideCodes`, `recoveryLogin`).

**Teste:** `SEC-001 SEC-006 SEC-008 SEC-065: o Administrador troca o autenticador, a outra sessão cai e o segredo antigo para de valer`. Fica logo depois do teste de recuperação e antes dos testes que dependem do segredo; a ordem importa, porque o arquivo guarda um segredo só.
1. Entrar com senha e código; abrir uma segunda sessão (outro contexto) já logada.
2. Na primeira, abrir a seção, digitar o código atual (respeitando `waitForStepAfter`) e ler o segredo novo.
3. Gerar o código do segredo novo, confirmar e ler os 10 códigos. **Logo em seguida, `writeStoredTotp({ secret: novo, lastStep: passoConfirmado })`**, antes de qualquer outra ação que possa falhar.
4. Conferir que a segunda sessão cai ao recarregar, que o painel atual segue de pé e que um login novo entra com o segredo novo.
5. Entrar por `recoveryLogin` com um dos códigos novos; a seção não pede o código atual; trocar de novo e gravar o arquivo de novo; conferir que um código de recuperação da primeira troca é recusado.
6. Terminar com o arquivo apontando para o segredo vigente e o `lastStep` correto.
O "segredo antigo vale até a confirmação" e o "código antigo é recusado depois" ficam provados no JUnit (Task 1), para não gastar passos do segredo no e2e.

## Task 4: ADR-0004, runbook e docs

- `docs/adr/0004-identidade-e-mfa.md`: atualização datada descrevendo a troca (início com código atual ou por recuperação, segredo pendente só na sessão, confirmação atômica, sessões e códigos de recuperação, auditoria `ADMIN_MFA_REPLACED`), a consequência R3 e o que continua manual.
- `docs/runbooks/recuperar-acesso-administrador.md`: o primeiro caminho passa a ser "entre com um código de recuperação e use Trocar o autenticador no painel"; o procedimento manual fica para quem não tem nem celular nem código de recuperação.
- Se o glossário ou `docs/spec` disserem que o procedimento manual é o único caminho, ajustar a frase.

## Verificação

- Backend focado (um por vez): `-Dtest=AdminAuthenticatorReplacementTest,AdminAuthenticatorReplacementAuthorizationTest,AdminRecoveryCodesTest,AdminMfaLoginTest,AdminReauthenticationTest,AuditPrivacyTest,AdminMfaLogTest,AdminRouteDenyByDefaultTest,OpenApiEndpointsTest` e as classes que citam os mesmos SEC-IDs (`git grep -l SEC-006 -- backend/src/test`) no fim.
- Frontend: `pnpm exec vitest run src/features/admin`; lint.
- `just check-rapido` uma vez, no fim.
- `/verificar-tela` na `AdminHomePage` (desktop e celular, teclado).
- Sistema de verdade antes da PR: banco, API e frontend; troca real no navegador e login depois dela; `just e2e` do painel.
- `verificar-entrega` antes da PR.

## Apêndice: arquivos alterados (caminhos completos)

- `backend/src/main/java/br/com/sergio/sapiensia/admin/AdminAuthenticatorReplacementController.java`
- `backend/src/main/java/br/com/sergio/sapiensia/admin/AdminMfaController.java`
- `backend/src/main/java/br/com/sergio/sapiensia/admin/AdminReauthenticationController.java`
- `backend/src/main/java/br/com/sergio/sapiensia/admin/AdminSessionController.java`
- `backend/src/main/java/br/com/sergio/sapiensia/admin/AdministratorMfa.java`
- `backend/src/main/java/br/com/sergio/sapiensia/admin/AdministratorPrincipal.java`
- `backend/src/main/java/br/com/sergio/sapiensia/admin/Administrators.java`
- `backend/src/main/java/br/com/sergio/sapiensia/admin/Capability.java`
- `backend/src/main/java/br/com/sergio/sapiensia/audit/AuditAction.java`
- `backend/src/main/java/br/com/sergio/sapiensia/security/SecurityConfig.java`
- `backend/src/test/java/br/com/sergio/sapiensia/admin/AdminAuthenticatorReplacementAuthorizationTest.java`
- `backend/src/test/java/br/com/sergio/sapiensia/admin/AdminAuthenticatorReplacementTest.java`
- `backend/src/test/java/br/com/sergio/sapiensia/admin/AdminMfaLogTest.java`
- `backend/src/test/java/br/com/sergio/sapiensia/admin/AdminMfaLoginTest.java`
- `backend/src/test/java/br/com/sergio/sapiensia/admin/AdminSessionTest.java`
- `backend/src/test/resources/openapi/endpoints.txt`
- `docs/adr/0004-identidade-e-mfa.md`
- `docs/api/endpoints.md`
- `docs/runbooks/recuperar-acesso-administrador.md`
- `frontend/e2e/admin-panel.e2e.ts`
- `frontend/src/features/admin-orders/AdminOrdersPage.test.tsx`
- `frontend/src/features/admin/AdminHomeOrdersLink.test.tsx`
- `frontend/src/features/admin/AdminHomePage.test.tsx`
- `frontend/src/features/admin/AdminHomePage.tsx`
- `frontend/src/features/admin/AdminHomeProductsLink.test.tsx`
- `frontend/src/features/admin/AdminMfaPage.test.tsx`
- `frontend/src/features/admin/AdminMfaPage.tsx`
- `frontend/src/features/admin/AuthenticatorKey.tsx`
- `frontend/src/features/admin/AuthenticatorReplacementSection.test.tsx`
- `frontend/src/features/admin/AuthenticatorReplacementSection.tsx`
- `frontend/src/features/admin/api.ts`
- `frontend/src/features/admin/hooks.ts`
- `frontend/src/features/admin/schemas.ts`
- `frontend/src/features/products/ProductFormPage.test.tsx`
- `frontend/src/features/products/ProductListPage.test.tsx`
- `frontend/src/features/products/testing.tsx`

Motivos dos que as tasks não citavam: os mocks da sessão do Administrador nos testes de Pedidos e Produtos (`AdminOrdersPage.test.tsx`, `AdminHome*Link.test.tsx`, `ProductFormPage.test.tsx`, `ProductListPage.test.tsx`, `products/testing.tsx`) ganharam `oldCodeRequired`, que o schema agora exige; a `AdminMfaPage.tsx` passou a usar o `AuthenticatorKey` extraído (D6); `AdminMfaLoginTest` e `AdminSessionTest` comparam o JSON exato de `sessions/current`, que ganhou a capacidade e o campo novos; `AdminMfaLogTest` varre também o fluxo novo (R4); `AdminReauthenticationController` passa `viaRecoveryCode = false` explicitamente (R1).
- `backend/src/main/java/br/com/sergio/sapiensia/admin/RecentAuthentication.java` e `backend/src/test/java/br/com/sergio/sapiensia/admin/AdminSessionControllerTest.java`: a regra única `oldCodeRequired` (a dispensa por recuperação só dentro da janela de reautenticação), vinda do /code-review da branch.
- `AdministratorEmailTasks.java`, `AdministratorEmailService.java`, `Administrators.java`, `AdministratorMfa.java` e `AdminAuthenticatorReplacementTest.java`: o aviso por e-mail ao Administrador quando a troca é confirmada (agendado na mesma transação da troca), vindo da revisão da PR #166.
- `AuthenticatorReplacementSection.test.tsx`: as chaves TOTP falsas passam a ser montadas em tempo de execução (Semgrep), sem `nosemgrep`.
