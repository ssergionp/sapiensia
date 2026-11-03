# Login e logout do Cliente (#10) — Plano de implementação

> Para quem executa: use `superpowers:subagent-driven-development`. Os passos usam caixas (`- [ ]`).

**Objetivo:** o Cliente com e-mail confirmado entra e sai da conta. A sessão fica em cookie seguro e o logout a encerra de verdade no servidor.

**Requisitos:** SEC-004 (cookie HttpOnly, Secure, SameSite), SEC-005 (sessão de curta duração, nada em localStorage), SEC-006 (sessão invalidada após logout). Ficam fora: bloqueio por tentativas e limite por IP (#11), recuperação de senha (#12), aviso de login novo e "Sair de todos os aparelhos" (#13), MFA do Administrador (#14 a #16).

## Fatos ainda a conferir na fonte oficial (Task 1 começa por isto)

Não conferi estes pontos; o Spring Security 7 e o Boot 4.1 são recentes e não vou supor.

1. Login feito à mão num controller: qual `SessionAuthenticationStrategy` aplicar para trocar o ID da sessão (contra fixação de sessão) e renovar o token CSRF (`csrf.spa()`), e como gravar o `SecurityContext` na sessão.
2. Construtor do `DaoAuthenticationProvider` e como ele reage a um usuário que não existe (tempo de resposta).
3. Propriedades de cookie e de tempo de sessão do Tomcat no Boot 4.1 (`server.servlet.session.*`). O `application-prod.yml` já traz `secure: true` e `same-site: lax`.
4. Se uma regra `.logout(...)` do Spring Security com `DELETE` e resposta 204 dá menos código do que um método no controller.

## Fatos conferidos (3/11/2026)

Fonte: `javap` nos jars do ~/.m2 (spring-security-web/core/config 7.1.2, spring-boot-web-server 4.1.2). Docs online não consultadas.

1. **Login manual.** Existem `ChangeSessionIdAuthenticationStrategy()` e `CsrfAuthenticationStrategy(CsrfTokenRepository)` (pacotes `web.authentication.session` e `web.csrf`), ambas `SessionAuthenticationStrategy.onAuthentication(auth, req, res)`, e `CompositeSessionAuthenticationStrategy(List)`. O `csrf.spa()` instala uma `CsrfAuthenticationStrategy` só no fluxo do `SessionManagementFilter`/formLogin: num controller manual é preciso chamá-la. O Spring 7 não grava o contexto sozinho, então a ordem é:
   `var ctx = SecurityContextHolder.createEmptyContext(); ctx.setAuthentication(auth); strategy.onAuthentication(auth, req, res); repo.saveContext(ctx, req, res);`
   com `strategy = new CompositeSessionAuthenticationStrategy(List.of(new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrfRepo)))` e `repo = new HttpSessionSecurityContextRepository()` (`saveContext(SecurityContext, req, res)`). O `csrfRepo` deve ser o mesmo do `.csrf(...)` (`CookieCsrfTokenRepository.withHttpOnlyFalse()` é o que o `spa()` usa; confirmar na Task do controller com teste que o cookie XSRF-TOKEN muda após o login).
2. **DaoAuthenticationProvider.** Construtor `DaoAuthenticationProvider(UserDetailsService)` (o do v6 sem argumento saiu da API pública; `setPasswordEncoder(PasswordEncoder)`). Em `UsernameNotFoundException`, `retrieveUser` chama `mitigateAgainstTimingAttack` (um `matches` contra hash falso): o tempo é igual. **Cuidado com a Decisão 4:** os `preAuthenticationChecks` padrão rodam antes da senha e recusam conta `isEnabled()==false`; por isso não mapear "e-mail não confirmado" para `enabled=false`. Conferir a confirmação depois do `authenticate` (ou via `setPostAuthenticationChecks`). Esta ordem vem do meu conhecimento do fluxo, não de teste: cobrir com teste de tempo/ordem.
3. **Tomcat/Boot.** Propriedades existentes (metadata do spring-boot-web-server 4.1.2): `server.servlet.session.timeout` (padrão 30m; usar `2h`), `cookie.http-only`, `cookie.secure`, `cookie.same-site`, `cookie.name`, `cookie.max-age`, `cookie.path`, `cookie.partitioned`. O padrão de `http-only` não consta no metadata: declarar `http-only: true` explícito (SEC-004). Os valores `secure`/`same-site` do `application-prod.yml` seguem válidos. Em dev (http) `secure` fica falso.
4. **Logout.** `http.logout(l -> l.logoutUrl(...))` casa por padrão POST; para DELETE use `logoutRequestMatcher(PathPatternRequestMatcher.pathPattern(HttpMethod.DELETE, "/api/sessions/current"))` (classe do `web.servlet.util.matcher`, não conferida) + `logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))` (existe, construtor com `HttpStatus`) + `deleteCookies("JSESSIONID")`; invalida a sessão e limpa o contexto por padrão. São ~4 linhas na `SecurityConfig` contra um método no controller com `session.invalidate()`; o método exigiria também limpar o contexto. Qualquer um serve; a regra `.logout` evita código novo.
5. **401 em /api.** `new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)` existe; usar em `.exceptionHandling(e -> e.authenticationEntryPoint(...))` (sem formLogin/httpBasic o padrão seria 403).
6. **ADR 0001 (Fly.io):** não fala em número de instâncias; só produção e homologação (apps separados). Sessão em memória assume 1 instância por app: registrar isso ao decidir.

## Decisões (aguardam o OK do Sérgio)

1. **Onde a sessão fica: na memória do servidor (recomendado).** Atende os três critérios do ticket sem dependência nova. O custo: cada reinício ou deploy desloga todo mundo. A alternativa é o Spring Session JDBC (sessões no PostgreSQL): é dependência nova, mais uma migration, e só se paga no #12/#13, que precisam encerrar as sessões de uma conta de outro aparelho. Recomendo decidir isso no #13, quando a necessidade existir.
2. **Duração da sessão: 2 horas sem uso** (SEC-005 pede "curta"). É regra de negócio; mude o número se preferir.
3. **Uma só mensagem para qualquer falha de login** (e-mail inexistente, senha errada ou e-mail não confirmado): não revela se a conta existe. O custo: quem não confirmou o e-mail vê uma frase que cobre os três casos.
4. **A conta não confirmada é recusada só depois de a senha ser conferida**, para o tempo de resposta ser o mesmo nos três casos.
5. **Sem "lembrar de mim".** Fora do ticket.
6. **Usar a entidade `Customer`** (pendência da #49): o login lê pela `CustomerRepository` com uma consulta de projeção (id, e-mail, hash, confirmado em). Menos código do que trocar por `JdbcClient` e apagar a entidade.
7. **Definir um `UserDetailsService` próprio** faz o Spring parar de gerar a "senha gerada" que aparece no log de produção (item da #51). Fica resolvido de graça; eu comento na #51.

## Rotas novas

| Rota | O que faz | Quem acessa |
|---|---|---|
| `POST /api/sessions` | Login: recebe `{email, password}`, responde 204 e o cookie de sessão; 401 em qualquer falha | público |
| `GET /api/sessions/current` | Diz quem está logado: 200 `{email}`; 401 se não há sessão | autenticado (papel `CUSTOMER`) |
| `DELETE /api/sessions/current` | Logout: encerra a sessão no servidor e apaga o cookie; 204 | autenticado (papel `CUSTOMER`) |

Todas liberadas na `SecurityConfig` uma a uma; o resto de `/api` continua negado. Não há ID de objeto na URL, então o teste negativo de "outro usuário recebe 404" não se aplica.

## Textos que o usuário vai ver (todos, para o seu OK)

**Tela `/entrar`**
- Título: "Entrar"
- Rótulos: "E-mail", "Senha"
- Botão: "Entrar"; enquanto envia: "Entrando…"
- Falha de login (qualquer motivo): "E-mail ou senha incorretos, ou o e-mail ainda não foi confirmado."
- Erro inesperado: "Não foi possível entrar agora. Tente novamente em instantes."
- Validação no navegador: "Informe um e-mail válido." e "Informe a senha."
- Link: "Ainda não tem conta? Criar conta" (vai para `/cadastro`)

**Página inicial `/`** (hoje é o texto de modelo "sapiensia" + botão "Funciona"; o botão sai)
- Enquanto verifica a sessão: "Carregando…"
- Sem sessão: links "Entrar" e "Criar conta"
- Com sessão: "Você entrou como {e-mail}." e o botão "Sair"; enquanto sai: "Saindo…"
- Falha ao sair: "Não foi possível sair agora. Tente novamente."

Não há texto de "carregando" na tela `/entrar` além do botão. Qualquer texto que aparecer fora desta lista volta para o Sérgio antes do push.

## Global Constraints

- Nada de senha, token ou e-mail completo em log. O corpo do login nunca é registrado.
- A resposta 401 é idêntica nos três casos (mesmo corpo, sem cookie de sessão), e o tempo de resposta também.
- O ID da sessão muda no login (contra fixação de sessão) e o token CSRF é renovado.
- Cookie: HttpOnly sempre; Secure e SameSite=Lax no perfil `prod` (já configurados) e conferidos por teste.
- Nada guardado em `localStorage`: o frontend só pergunta ao servidor quem está logado.
- Toda chamada passa por `apiFetch`.
- Migrations: nenhuma (a sessão fica na memória). Se a decisão 1 mudar, usa-se a skill `nova-migration`.

## Review Focus

- Troca do ID da sessão e do token CSRF no login manual (item 1 dos fatos a conferir).
- Mesma resposta e mesmo tempo para os três tipos de falha.
- Logout recusa o cookie antigo no servidor, não só apaga o cookie no navegador.
- Regras da `SecurityConfig`: cada rota nova aberta de forma explícita.

## Tarefas

### Task 1: Conferir a fonte oficial e decidir o desenho do login
- [ ] Conferir os 4 itens da seção "Fatos a conferir" na documentação oficial (Spring Security 7, Boot 4.1) e no código do projeto. Anotar o resultado em 5 linhas no topo deste plano.
- [ ] Se algum fato contradisser o desenho, parar e voltar ao Sérgio antes da Task 2.

### Task 2: Backend — login, quem está logado e logout (TDD)
Arquivos: `customer/SessionController.java` (novo), `customer/CustomerUserDetailsService.java` (novo), `CustomerRepository.java` (consulta de projeção), `security/SecurityConfig.java`, `application.yml` (tempo de sessão). Testes em `customer/SessionControllerTest.java`.

Testes primeiro (títulos citam os IDs):
- [ ] `SEC-004 shouldLoginConfirmedCustomerAndSetSessionCookie`: 204 e `Set-Cookie` com `HttpOnly`.
- [ ] `shouldRejectWrongPasswordUnknownEmailAndUnconfirmedAccountWithTheSameResponse`: três chamadas, mesmo status 401 e mesmo corpo, sem `Set-Cookie`.
- [ ] `shouldRejectUnconfirmedCustomerEvenWithTheCorrectPassword`.
- [ ] `SEC-006 shouldRefuseOldSessionAfterLogout`: login, logout, depois `GET /api/sessions/current` com o cookie antigo dá 401.
- [ ] `shouldChangeSessionIdAndCsrfTokenOnLogin` (fixação de sessão).
- [ ] `shouldRejectLoginWithoutCsrfToken`: 403.
- [ ] `shouldAnswer401ForCurrentSessionWithoutLogin`.
- [ ] `shouldNotLogPasswordOrEmail` (captura de log durante um login com falha).
- [ ] Teste com servidor de verdade (`RANDOM_PORT`) e propriedades do `prod`: `SEC-004 shouldSendSecureHttpOnlyLaxSessionCookie`, porque o MockMvc não aplica a configuração de cookie do Tomcat.
- [ ] `SEC-005 shouldExpireSessionAfterIdleTimeout` (leitura da configuração: 2 h).

Depois a implementação, a menor que passe nos testes. Conferir `just check-resumo` no fim.

### Task 3: Frontend — tela `/entrar`, sessão e botão "Sair" (TDD)
Arquivos: `frontend/src/features/session/` (`LoginPage.tsx`, `api.ts`, `hooks.ts`, `schemas.ts`, testes), `App.tsx` (rota `/entrar` e a Home nova).
- [ ] Testes de `LoginPage`: validação dos dois campos, envio com sucesso (vai para `/`), 401 mostra a mensagem de falha, erro de rede mostra a mensagem inesperada, botão desativado enquanto envia, foco e `aria-*` como no cadastro.
- [ ] Testes da Home: três estados (carregando, sem sessão, com sessão) e o "Sair", inclusive a falha ao sair.
- [ ] Implementação: `useSession` (TanStack Query em `GET /api/sessions/current`; 401 vira "sem sessão"), `useLogin`, `useLogout` (invalida a consulta da sessão). Tudo via `apiFetch`.

### Task 4: Teste de ponta a ponta do fluxo crítico
Arquivo: `frontend/e2e/customer-login.e2e.ts`, contra API e banco de verdade, sem `page.route` nem mocks.
- [ ] Cadastro, confirmação pelo e-mail do arquivo de saída (mesmo método do `email-confirmation.e2e.ts`), login, ver "Você entrou como …", recarregar a página e continuar logado, sair, recarregar e continuar fora.
- [ ] Caso negativo: senha errada mostra a mensagem de falha e não cria sessão.

### Task 5: Documentação
- [ ] ADR nova só se a decisão 1 ou 2 virar regra que o usuário enxerga; se a sessão ficar na memória com 2 h, registrar em uma frase no ADR 0004 (identidade) em vez de ADR novo. O Sérgio decide.
- [ ] Comentário na #51: o item "senha gerada no log" ficou resolvido neste ticket.

### Task 6: Verificação e entrega
- [ ] `/verificar-tela` na `/entrar` e na Home (desktop ~1280px, celular ~390px, teclado).
- [ ] Rodar o sistema de verdade (banco, API e frontend; `just db` na pasta principal) e conferir o fluxo no navegador.
- [ ] `just check-resumo`, depois a skill `verificar-entrega` com a tabela.
- [ ] Push e PR (`Closes #10`) só com o OK do Sérgio.

## Riscos

- **Cada login gasta ~19 MiB de memória no Argon2id**, e até o #11 e o #42 não há limite de tentativas: é o mesmo risco já registrado na #49. Entra na descrição da PR.
- **Reinício do servidor desloga todos** (decisão 1). Aceitável antes do lançamento; precisa constar na PR.
- **Tempo de resposta igual nos três casos** é difícil de provar em teste sem ficar frágil; a garantia vem do desenho (a senha é sempre conferida antes de checar "confirmado") e da conferência do item 2 dos fatos.
- **Sessão na memória + mais de uma instância** (Fly.io com 2 máquinas) quebraria o login sem afinidade de sessão. Hoje há uma só; o ADR 0001 deve ser reconferido na Task 1.
