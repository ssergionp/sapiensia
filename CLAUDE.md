# sapiensia

## O que é
Plataforma web para criar e revisar e-books, cartilhas, tutoriais e checklists com ajuda de IA e depois vendê-los, usada por mim no painel de administração e pelos clientes que compram e leem os conteúdos

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
- `just doctor`: confere o computador (Java, Docker, portas do banco e da API...)
- `just e2e`: testes de ponta a ponta no navegador, contra o sistema de verdade (antes: `just db`, `just dev-back` e `just dev-front`)
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
- docs/: decisões em docs/adr/; passo a passo operacional em docs/runbooks/ (recuperar o acesso do Administrador; homologação no Fly.io); convenções das skills do Pocock em docs/agents/.
- scripts/: novo-projeto.ps1 (usado uma vez, na criação), escolher-portas.ps1 (portas do banco e da API), verificar-ambiente.ps1 (just doctor), check-resumo.ps1 (just check-resumo) e limpar-ticket.ps1 (just limpar-ticket).
- .claude/: hooks (Biome depois de cada edição; check antes de encerrar a resposta numa branch de ticket) e a skill verificar-entrega.

## Domínio
Glossário e convenções das skills: veja docs/agents/ (o domain.md diz onde fica o glossário). Decisões em docs/adr/.

## Reaproveitar antes de criar
- `frontend/src/lib/api.ts` (`apiFetch`): toda chamada do frontend ao backend passa por ele, que manda o cookie de sessão e o token CSRF.
- backend/.../customer/ClientIpResolver.java: o único jeito de obter o IP do cliente (Fly-Client-IP em prod; X-Forwarded-For nunca). Usado pelo limite de login e pelo webhook do Asaas.
- (liste aqui outros helpers e componentes que o Claude deve usar em vez de criar novos)

## Segurança e produção
- Rota nova em /api começa negada: libere na SecurityConfig com o papel ou a capacidade que ela exige. `permitAll` numa rota de /api só com ADR.
- Endpoint que recebe o ID de um objeto (pedido, nota, arquivo, tarefa) tem um teste negativo: outro usuário autenticado recebe 404, nunca o dado.
- Fluxo crítico novo (compra, entrega, login, download) ganha um teste em frontend/e2e/, contra API e banco de verdade, sem `page.route` nem mocks.
- Nunca grave em log senha, token, segredo ou dado pessoal completo.
- Se docs/spec/ tiver requisitos com IDs (FR-, SEC-, NFR-), cite os IDs no plano, no título dos testes que os cobrem e na PR.

## Regras deste projeto
- (exemplo: toda rota nova exige autorização por papel)

## Armadilhas conhecidas
- Testcontainers precisa do Docker Desktop aberto; sem ele, `just test` falha.
- Cada projeto tem as suas portas, escolhidas pelo scripts/escolher-portas.ps1: a do banco (docker-compose.yml, 5433 em diante) e a da API (server.port do application.yml, igual ao proxy /api do vite.config.ts, 8081 em diante). Vários projetos podem ficar ligados juntos. Projeto antigo ainda com 5432 e 8080 ganha portas próprias com o /trazer-do-modelo.
- Classes velhas em `backend/target/` já seguraram código e migrations antigos: na dúvida, rode `.\mvnw.cmd clean` dentro de backend/ antes de testar.
- O `application.yml` de teste precisa de todas as propriedades `app.*` que o código usa, senão o contexto não sobe.
- Teste que depende de arquivo fora do git (dados reais) deve ser pulado quando o arquivo não existe, como acontece no CI.
- Line endings: LF em tudo (`.gitattributes`); só `mvnw.cmd` e os `.ps1` em CRLF. O Spotless e o Biome falham com CRLF.
- Todos os endpoints da API começam com /api: o proxy do vite.config.ts e o /verificar-tela dependem disso.
- Valor falso que imita segredo (`sb_secret_...`, token `eyJ...`) é acusado pelo gitleaks do CI: nos testes, monte-o em tempo de execução (`"sb_" + "secret_" + ...`), nunca como literal.
- Teste focado (`-Dtest=...`) de código que escreve em log ou cumpre um requisito com ID: inclua também as classes que já conferem esse log ou citam esse ID (`git grep -l SEC-047 -- backend/src/test`). Uma checagem de log em outra classe já escapou de um filtro só com o nome da classe mudada.
