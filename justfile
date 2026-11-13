set windows-shell := ["powershell.exe", "-NoLogo", "-NoProfile", "-Command"]

# Maven Wrapper: .cmd no Windows, script no Linux
mvnw := if os_family() == "windows" { "./mvnw.cmd" } else { "./mvnw" }

# mostra a lista de comandos
default:
    @just --list

# confere se o computador tem tudo instalado (so le, nao muda nada)
[windows]
doctor:
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verificar-ambiente.ps1

# adapta o modelo a um projeto novo, uma vez so (ex.: just novo-projeto controle-estoque com.sergio)
[windows]
novo-projeto nome groupid:
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/novo-projeto.ps1 -Nome {{nome}} -GroupId {{groupid}}

# sobe o PostgreSQL local
db:
    docker compose up -d

# para o PostgreSQL local (os dados ficam guardados)
db-stop:
    docker compose stop

# instala as dependências
setup: setup-back setup-front

[working-directory: 'backend']
setup-back:
    {{mvnw}} -q dependency:go-offline

[working-directory: 'frontend']
setup-front:
    pnpm install

# roda a API com o perfil local e os dados de seed (precisa do banco: just db)
# as aspas são necessárias: sem elas o PowerShell parte o argumento -D no ponto
[working-directory: 'backend']
dev-back:
    {{mvnw}} spring-boot:run "-Dspring-boot.run.profiles=local"

# roda o frontend em http://localhost:5173
[working-directory: 'frontend']
dev-front:
    pnpm dev

# formata o código
fmt: fmt-back fmt-front

[working-directory: 'backend']
fmt-back:
    {{mvnw}} -q spotless:apply

[working-directory: 'frontend']
fmt-front:
    pnpm exec biome check --write .

# procura erros e formatação fora do padrão
lint: lint-back lint-front

[working-directory: 'backend']
lint-back:
    {{mvnw}} -q spotless:check

[working-directory: 'frontend']
lint-front:
    pnpm exec biome check .

# roda os testes (o backend precisa do Docker aberto; só classes terminadas em Test)
test: test-back test-front test-scripts

# testes dos scripts e dos hooks (aspas: o node expande o glob, inclusive na pasta .claude)
test-scripts:
    node --test ".claude/**/*.test.mjs" "scripts/*.test.mjs"

[working-directory: 'backend']
test-back:
    {{mvnw}} test

[working-directory: 'frontend']
test-front:
    pnpm exec vitest run --passWithNoTests

# gera a versão final (o build do frontend também checa os tipos)
build: build-back build-front

[working-directory: 'backend']
build-back:
    {{mvnw}} -q -DskipTests package

[working-directory: 'frontend']
build-front:
    pnpm build

# tudo que precisa passar antes de um PR
check: lint test build

# o mesmo check, com resumo curto; o log completo fica em .tmp/check.log (o Claude usa este)
[windows]
check-resumo:
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-resumo.ps1

# lint + só os testes das áreas que a branch mudou (o gancho de fim de resposta exige este)
check-rapido:
    node scripts/check-rapido.mjs

# testes de ponta a ponta contra o sistema de verdade (antes: just db, just dev-back e just dev-front)
[working-directory: 'frontend']
e2e:
    pnpm exec playwright test

# gera a imagem Docker do sistema inteiro (a mesma que vai para homologação e produção)
imagem:
    docker build -t app:local .

# depois do merge da PR: encerra processos, remove a worktree, atualiza a main e apaga a branch local
[windows]
limpar-ticket branch='':
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/limpar-ticket.ps1 {{ if branch == "" { "" } else { "-Branch " + branch } }}

# sobe a demonstração local (banco, API e frontend em segundo plano, com dados fictícios)
[windows]
demo:
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/demo.ps1

# para a demonstração local (só os processos dela)
[windows]
demo-parar:
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/demo-parar.ps1

# mostra o código de verificação atual do Administrador da demonstração
demo-codigo:
    node scripts/demo-codigo.mjs
