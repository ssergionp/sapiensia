# sapiensia

## O que é
Plataforma web para publicar e vender e-books, cartilhas, tutoriais e checklists prontos (PDF + capa enviados por mim), usada por mim no painel de administração e pelos clientes que compram e baixam os conteúdos. A criação por IA fica fora do plano por enquanto (docs/adr/0008).

## Ambiente
- Desenvolvimento no Windows 11 com PowerShell; o CI roda em Linux.
- O Docker Desktop precisa estar aberto: banco local e testes de integração (Testcontainers).

## Stack
- backend/: Java 25, Spring Boot 4.1, Maven, Spring Data JPA, Spring Security, Flyway, PostgreSQL
- frontend/: React + TypeScript + Vite, pnpm, TanStack Query, React Router, Tailwind + shadcn/ui, React Hook Form + Zod, Biome
- Testes: JUnit 5, Mockito, Testcontainers, Vitest, Testing Library

## Comandos (sempre via just)
- `just setup` · `just db` · `just db-stop` · `just dev-back` · `just dev-front`
- `just fmt` · `just lint` · `just test` · `just build`
- `just check-rapido`: lint + os testes das áreas alteradas (o gancho de fim de resposta exige este); o log fica em .tmp/check-rapido.log
- `just check`: o que o CI roda na PR; localmente só se o Sérgio pedir
- `just check-resumo`: o mesmo check completo, com resumo curto; o log completo fica em .tmp/check.log
- `just demo` · `just demo-parar` · `just demo-codigo`: sobe a demonstração local com dados fictícios (banco, API e frontend em segundo plano), para só os processos dela e mostra o código de verificação do Administrador da demonstração
- `just demo-pagar <id do Pedido>`: na demonstração, marca o Pedido como pago, como se o Asaas tivesse confirmado o Pix (só contra a API local)
- `just demo-estornar <id do Pedido>`: na demonstração, estorna o Pedido pago, como se o Asaas tivesse reembolsado o Pix (só contra a API local)
- `just doctor`: confere o computador (Java, Docker, portas do banco e da API...)
- `just e2e`: testes de ponta a ponta no navegador, contra o sistema de verdade (antes: `just db`, `just dev-back` e `just dev-front`)
- `just carga`: teste de carga com k6 em Docker, só contra o sistema local (antes: `node scripts/carga/preparar.mjs`, que cria as contas); runbook em docs/runbooks/teste-de-carga.md
- `just imagem`: gera a imagem Docker do sistema inteiro, a mesma que vai para homologação e produção
- `just limpar-ticket`: depois do merge da PR, encerra os processos do ticket, remove a worktree, atualiza a main e apaga a branch local.
- Antes de declarar um ticket pronto: skill `verificar-entrega` (check rápido recente, arquivos fora do plano, teste afrouxado, tela e sistema de verdade); depois da PR, o ticket só está pronto com o CI verde.
- Se `just lint` acusar formatação, rode `just fmt`.
- Um teste só: `cd backend; .\mvnw.cmd test -Dtest=NomeDoTest`
- Swagger: http://localhost:8081/swagger-ui.html

## Estrutura
- backend/: API Spring Boot. Código por funcionalidade em src/main/java/<pacote>/<funcionalidade>/; migrations em src/main/resources/db/migration; dados fictícios em db/seed (só no perfil local).
- frontend/: React. Código por funcionalidade em src/features/<funcionalidade>/; componentes shadcn em src/components/ui; testes de ponta a ponta em e2e/ (arquivos `*.e2e.ts`).
- backend/.../security/SecurityConfig.java: rotas de /api negadas por padrão, CSRF para a SPA e cabeçalhos de segurança. backend/.../web/SpaConfig.java: a API serve o build do React.
- Dockerfile: imagem única (React dentro do jar), perfil `prod` (application-prod.yml). Health check em /api/system/health.
- docs/: decisões em docs/adr/; passo a passo operacional em docs/runbooks/ (recuperar o acesso do Administrador; homologação no Fly.io; incidente, rollback e suporte ao Cliente: incidente.md, rollback.md, suporte-cliente.md; teste de carga: teste-de-carga.md); convenções das skills do Pocock em docs/agents/.
- scripts/: novo-projeto.ps1 (usado uma vez, na criação), escolher-portas.ps1 (portas do banco e da API), verificar-ambiente.ps1 (just doctor), check-resumo.ps1 (just check-resumo) e limpar-ticket.ps1 (just limpar-ticket).
- .claude/: hooks (Biome depois de cada edição; check antes de encerrar a resposta numa branch de ticket) e a skill verificar-entrega.

## Domínio
Glossário e convenções das skills: veja docs/agents/ (o domain.md diz onde fica o glossário). Decisões em docs/adr/.

## Reaproveitar antes de criar
- `frontend/src/lib/api.ts` (`apiFetch`): toda chamada do frontend ao backend passa por ele, que manda o cookie de sessão e o token CSRF.
- backend/.../customer/ClientIpResolver.java: o único jeito de obter o IP do cliente (Fly-Client-IP em prod; X-Forwarded-For nunca). Usado pelo limite de login e pelo webhook do Asaas.
- backend/.../payment/PixGateway.java: a única porta para o Asaas (cliente, cobrança, código copia e cola, cancelamento); adaptador `AsaasPixGateway` em prod e `FakePixGateway` no local e nos testes, escolhidos por `app.pix.provider`.
- backend/.../customer/CurrentCustomer.java: o jeito de obter o Cliente da sessão num controller.
- backend/.../payment/PaymentConfirmation.java: o único lugar que marca um Pedido como pago e concede o Acesso, e cancela o Pedido pendente irmão depois do commit.
- backend/.../payment/RefundConfirmation.java: o único lugar que marca um Pedido como reembolsado e cancela o Acesso dele, só depois de o GET na cobrança no Asaas confirmar o id, a referência externa e o estado REFUNDED.
- backend/.../product/ProductDownloadService.java: o único lugar que emite o link de Download (URL assinada de 60 s no Supabase, `/api/local-downloads/<token>` no local) e grava o Registro de download; nunca devolve nem guarda o endereço do objeto.
- backend/.../payment/Reconciliation.java: o único lugar que compara Pedidos com cobranças do Asaas (toda noite); nunca paga nem reembolsa sozinho: grava um evento marcado em `payment_events` e agenda `process-payment-event`; as divergências vão uma vez para `reconciliation_divergences` e para o `AdminAlerts`. Runbook: docs/runbooks/conciliacao-asaas.md.
- backend/.../customer/CustomerAnonymization.java: o único lugar que anonimiza um Cliente (no lugar; nunca apaga a linha de `customers` nem toca `payment_events`, `payment_event_results` e `download_records`); o e-mail original nunca vai para log, auditoria nem resposta; e-mail pendente para conta anonimizada é descartado. Rota: `POST /api/admin/customer-anonymizations`. Runbook: docs/runbooks/anonimizar-conta-cliente.md; inventário: docs/privacidade/inventario-dados-pessoais.md.
- backend/.../payment/OrderPaidEmail.java: o único lugar que envia o e-mail de pagamento confirmado.
- backend/.../payment/OrderRefundedEmail.java: o único lugar que envia o e-mail de Pedido reembolsado (só quando o reembolso cancelou o Acesso daquele Pedido).
- backend/.../payment/RefundRequestService.java: o único lugar que cria a Solicitação de reembolso (`refund_requests`) e suspende o Acesso (`accesses.suspended_at`); o cancelamento do Acesso continua só no `RefundConfirmation`.- backend/.../payment/RefundWindow.java: a única regra do prazo do Reembolso pelo site (até o fim do 7º dia depois do dia do pagamento, fuso de São Paulo).
- backend/.../payment/RefundWindow.java: a única regra do prazo do Reembolso pelo site (até o fim do 7º dia depois do dia do pagamento, fuso de São Paulo).
- backend/src/test/.../TestcontainersConfiguration.java: um único Postgres para a suíte de testes; cada contexto Spring ganha um banco próprio, apagado quando o contexto fecha. Teste que precisa de um banco fora de um contexto (ex.: reiniciar o sistema sobre o mesmo banco) usa `TestcontainersConfiguration.newDatabase()` e `drop()`; nunca um `PostgreSQLContainer` próprio. O cache de contextos de teste guarda no máximo 8 (`backend/src/test/resources/spring.properties`).
- backend/.../payment/MaskedEmail.java: o único jeito de mostrar o e-mail de um Cliente no painel (primeira letra + `***` + `@domínio`, mascarado no servidor); o e-mail inteiro nunca sai na resposta nem no log.
- `frontend/src/lib/saoPauloDate.ts` (`saoPauloDateAndTime`): o formatador único de data ("dd/MM/aaaa") e hora ("HH:mm") no fuso de São Paulo; use-o em vez de criar outro.
- backend/.../alert/AdminAlerts.java: o único jeito de gerar um alerta ao Administrador; `record` dentro de uma transação (grava a linha junto com o resultado), `recordSafely` fora dela (nunca lança). O e-mail sai depois, pela tarefa `admin-alert-dispatch`, nunca na transação do pagamento.
- backend/.../web/RequestRateLimiter.java (com `RateLimitInterceptor` e `RateLimitConfig`): o único jeito de limitar uma rota por IP e por conta (em memória, por instância). Rota limitada nova = um `RateLimitedOperation` + as propriedades `app.rate-limit.<operação>` + uma entrada no `RateLimitConfig` + uma linha em docs/api/endpoints.md. `customer/IpLimitKey.of` é o único jeito de montar a chave por IP de um limitador (IPv4 por endereço, IPv6 por /64). `web/RefusalLogThrottle` é o jeito de registrar no log uma recusa que um cliente anônimo consegue repetir em laço (uma linha por chave por minuto, com a contagem das seguradas; a chave é sempre um texto fixo do código, nunca dado do cliente).
- `frontend/e2e/a11y.ts` (`expectNoA11yViolations`): o axe do e2e; tela nova numa jornada crítica ganha um scan no teste da jornada; exceção só por regra + tela em `KNOWN_ISSUES`, com issue. Auditoria: docs/acessibilidade/auditoria-jornadas.md.
- (liste aqui outros helpers e componentes que o Claude deve usar em vez de criar novos)

## Segurança e produção
- Rota nova em /api começa negada: libere na SecurityConfig com o papel ou a capacidade que ela exige. `permitAll` numa rota de /api só com ADR.
- Endpoint que recebe o ID de um objeto (pedido, nota, arquivo, tarefa) tem um teste negativo: outro usuário autenticado recebe 404, nunca o dado.
- Fluxo crítico novo (compra, entrega, login, download) ganha um teste em frontend/e2e/, contra API e banco de verdade, sem `page.route` nem mocks.
- Nunca grave em log senha, token, segredo ou dado pessoal completo.
- Se docs/spec/ tiver requisitos com IDs (FR-, SEC-, NFR-), cite os IDs no plano, no título dos testes que os cobrem e na PR.

## Fluxo de ticket
Dois níveis. Na dúvida entre os dois, use o COMPLETO.
- **COMPLETO** (dinheiro, pagamento, segurança, dados pessoais, autenticação, migrations): plano para aprovação, subagent-driven-development, revisões por tarefa, verificar-entrega e /code-review.
- **LEVE** (o resto: telas, textos, ajustes, testes, docs, pendências pequenas): plano curto, implementação direta sem subagentes, testes focados, um /code-review no fim, verificar-entrega. Pendências pequenas da mesma área podem ir juntas numa PR só.

## Regras deste projeto
- Comandos git e de shell: prefira um comando por vez, simples, sem encadear com `;` ou `&&`, e confira a saída antes do próximo.

## Armadilhas conhecidas
- Testcontainers precisa do Docker Desktop aberto; sem ele, `just test` falha.
- Cada projeto tem as suas portas, escolhidas pelo scripts/escolher-portas.ps1: a do banco (docker-compose.yml, 5433 em diante) e a da API (server.port do application.yml, igual ao proxy /api do vite.config.ts, 8081 em diante). Vários projetos podem ficar ligados juntos. Projeto antigo ainda com 5432 e 8080 ganha portas próprias com o /trazer-do-modelo.
- Classes velhas em `backend/target/` já seguraram código e migrations antigos: na dúvida, rode `.\mvnw.cmd clean` dentro de backend/ antes de testar.
- O `application.yml` de teste precisa de todas as propriedades `app.*` que o código usa, senão o contexto não sobe.
- Teste que depende de arquivo fora do git (dados reais) deve ser pulado quando o arquivo não existe, como acontece no CI.
- Line endings: LF em tudo (`.gitattributes`); só `mvnw.cmd` e os `.ps1` em CRLF. O Spotless e o Biome falham com CRLF.
- Todos os endpoints da API começam com /api: o proxy do vite.config.ts e o /verificar-tela dependem disso.
- Limite novo por IP: o e2e roda de um IP só e estoura o limite; o `ip-capacity-floor` (10000 no application-local.yml, no application.properties de teste e no ci.yml, via `APP_RATE_LIMIT_IP_CAPACITY_FLOOR`) já cobre isso, então não crie outro jeito. Limites por conta são elevados no application.properties de teste.
- Endpoint novo precisa entrar em backend/src/test/resources/openapi/endpoints.txt e em docs/api/endpoints.md, senão o `OpenApiEndpointsTest` falha.
- Valor falso que imita segredo (`sb_secret_...`, token `eyJ...`) é acusado pelo gitleaks do CI: nos testes, monte-o em tempo de execução (`"sb_" + "secret_" + ...`), nunca como literal.
- Teste focado (`-Dtest=...`) de código que escreve em log ou cumpre um requisito com ID: inclua também as classes que já conferem esse log ou citam esse ID (`git grep -l SEC-047 -- backend/src/test`). Uma checagem de log em outra classe já escapou de um filtro só com o nome da classe mudada.
