# Demonstração local e fluxo rápido Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Subagentes em Sonnet.

**Status:** AGUARDANDO APROVAÇÃO do Sérgio (decisões na seção "Decisões").

**Goal:** (A) `just demo` sobe o sistema inteiro no computador, só com Docker, com um Administrador, um Cliente e Produtos publicados prontos; (B) três ajustes de texto da capa (#21); (C) `just check-rapido` para o dia a dia, com o `just check` completo só no CI.

**Architecture:** A demonstração usa um banco próprio (`app_demo`) no mesmo contêiner do `just db`, para não misturar com os dados de desenvolvimento nem quebrar os testes e2e locais (que esperam o e-mail de primeiro acesso do Administrador). Três semeadores Java (um por funcionalidade, porque os serviços que eles usam são package-private) só existem com o perfil `local` **e** `app.demo.enabled=true`, que só o `scripts/demo.ps1` liga. O check rápido é um script Node com a escolha dos testes numa função pura e testada.

**Tech Stack:** Spring Boot (`@Profile`, `@ConditionalOnProperty`, `ApplicationRunner`, `@Order`), PDFBox 3 (já é dependência), `java.awt`/`ImageIO` do JDK, PowerShell, Node (`node:test`), Vitest. **Nenhuma dependência nova.**

**Spec:** pedido do Sérgio de 13/11/2026 (este plano); NFR-012 (dados fictícios só em dev); `backend/src/main/resources/db/seed/R__seed_products.sql`; `backend/src/test/java/br/com/sergio/sapiensia/product/ProductSeedTest.java` (precedente do teste com perfil prod).

## Global Constraints

- Nada de Supabase, Asaas, Resend nem Fly na demonstração: armazenamento `file` e e-mail `file`, como o perfil `local` já faz.
- Os usuários de demonstração **nunca** existem no perfil `prod` (três travas na Task 2; teste na Task 2).
- Senha, código e segredo de demonstração nunca vão para log (regra do projeto). Quem imprime é o `scripts/demo.ps1`.
- Literais que parecem segredo (segredo TOTP) levam `// gitleaks:allow` ou são montados em tempo de execução.
- Um comando pesado por vez; o `just demo` confere 2 GB de memória livre antes de subir.
- `.ps1` em CRLF, o resto em LF.
- O MFA do Administrador continua obrigatório também na demonstração.

## O que da jornada NÃO funciona ainda (vai para o docs/demo.md)

Não existe compra, cobrança Pix, pedido nem download para o Cliente: o backend só tem o recebedor do webhook do Asaas. Na demonstração o Cliente cria conta, entra e vê o Catálogo e os Produtos; o Administrador entra no painel, cria, edita, envia PDF e capa, publica e despublica.

Frase do docs/demo.md: "Ainda não dá para comprar, pagar nem baixar os conteúdos: essa parte do sistema está em construção."

## Decisões (preciso do seu OK)

| # | Decisão | Recomendo |
|---|---|---|
| D1 | Código MFA: o TOTP muda a cada 30 s, então não existe um código fixo. O Administrador de demonstração tem um **segredo fixo**; o `just demo` imprime o código da hora e o segredo (para quem quiser pôr num app autenticador), e `just demo-codigo` imprime um código novo quando precisar. Dois logins dentro dos mesmos 30 s recusam o segundo código (proteção contra reuso). | Sim |
| D2 | Banco `app_demo` separado no mesmo contêiner, arquivos em `.tmp/demo/storage` e e-mails em `.tmp/demo/outbox`. Os dados de demonstração não aparecem no `just dev-back`. | Sim |
| D3 | O formato em KB vale para o tamanho da capa **e** do PDF ("Arquivo atual: 2 páginas, 18 KB, ..."), porque os dois usam a mesma função `formatFileSize`. | Sim |
| D4 | `just demo` deixa API e frontend rodando em segundo plano (logs em `.tmp/demo/`); `just demo-parar` encerra pelos PIDs guardados. O banco continua ligado (mesmo contêiner do `just db`). | Sim |
| D5 | Os testes Node (`.claude/` e `scripts/`) passam a rodar no `just test` (portanto no CI) e no check rápido. Hoje o `conferir.test.mjs` não roda em lugar nenhum. | Sim |
| D6 | Contas: Administrador `admin@example.com` / `Demo-Admin-2026!`; Cliente `cliente@example.com` / `Demo-Cliente-2026!`. | Sim |

## Textos que o usuário vê

**Frontend (#21, aprovados):**
- `COVER_TEXTS.help`: "PNG, JPEG ou WebP, até 2 MB e até 4.000 pixels de largura e de altura. Recomendado: 1.200 × 1.800 pixels."
- `COVER_TEXTS.helpReplace`: "Enviar outra imagem substitui a atual. Os dados da foto (como local e aparelho) são removidos. Se a foto aparecer girada, gire-a em um editor e envie de novo."
- Tamanho: abaixo de 1 MB, "320 KB"; mínimo "1 KB"; de 1 MB em diante, como hoje ("1,2 MB").

**Produtos de demonstração (Catálogo):** os Produtos 101, 103 e 104 do seed atual ("[Fictício] Guia de alimentação saudável", "[Fictício] Cartilha de orçamento doméstico", "[Fictício] Tutorial de senhas seguras") ganham capa e PDF e são publicados; 102 e 105 ficam como rascunho. Capa: retângulo 1200×1800 com faixas de cor por categoria, sem texto (desenhar texto exige fontes do sistema, que faltam no Linux do CI). PDF de 2 páginas: "[Fictício] <título>" / "Conteúdo de demonstração gerado pelo sistema. Não é um e-book de verdade."

**Terminal do `just demo` (sem acento, por causa do PowerShell 5.1):**
```
Demonstracao pronta. Abra no navegador: http://localhost:5173
Cliente:        cliente@example.com  /  Demo-Cliente-2026!
Administrador:  http://localhost:5173/painel/entrar
                admin@example.com  /  Demo-Admin-2026!
                codigo de verificacao agora: 123456 (vale ~30 s; outro: just demo-codigo)
                chave para app autenticador: <BASE32>
Para parar: just demo-parar
```
Erros: "Pouca memoria livre: X GB. Feche programas e tente de novo (precisa de 2 GB)." · "O Docker nao esta aberto. Abra o Docker Desktop e tente de novo." · "A porta N ja esta em uso (just dev-back ou dev-front ligado?). Pare e tente de novo." · "A API nao subiu em 5 minutos. Veja .tmp/demo/api.log".

**Gancho de fim de resposta:** "O código desta branch mudou e o just check-rapido ainda não rodou nela." / "O último check desta branch falhou." / "Houve edição de código depois do último check." + "Antes de dizer que terminou, rode just check-rapido e mostre o resultado. Se você parou só para fazer uma pergunta ao usuário, pode parar de novo."

## Review Focus

1. `just demo` rodado duas vezes seguidas: não duplica nada nem falha (semeadores idempotentes; banco já criado).
2. `local` e `prod` ativos juntos (`SPRING_PROFILES_ACTIVE=local,prod`): a aplicação **não sobe** (trava no construtor).
3. Check rápido com mudança só em `security/`, `web/`, `resources/`, migration ou `pom.xml`: roda **todos** os testes do backend.
4. Gancho com check completo mais novo que o rápido (ou o contrário): vale o mais recente.
5. Tamanho de 1.048.000 bytes (arredonda para 1.023 KB) e de 1.048.575 bytes (seria "1.024 KB"): mostra "1,0 MB".

---

### Task 1: Ajustes de texto da capa (#21)

**Files:**
- Modify: `frontend/src/features/products/schemas.ts` (`formatFileSize`)
- Modify: `frontend/src/features/products/messages.ts` (`COVER_TEXTS.help`, `COVER_TEXTS.helpReplace`)
- Test: `frontend/src/features/products/schemas.test.ts` (criar se não existir), `frontend/src/features/products/ProductCoverSection.test.tsx` (linhas 59 e 64)

- [ ] **Step 1: Testes que falham** — `formatFileSize`: `327680 → "320 KB"`, `1 → "1 KB"`, `1048000 → "1.023 KB"`, `1048575 → "1,0 MB"`, `1258291 → "1,2 MB"`, `3565158 → "3,4 MB"`; nunca `"0,0 MB"` para 1..1048575. Atualize os dois textos esperados no `ProductCoverSection.test.tsx`.
- [ ] **Step 2:** `cd frontend; pnpm exec vitest run src/features/products` → FAIL.
- [ ] **Step 3:** abaixo de 1.048.576 bytes, `Math.max(1, Math.round(bytes / 1024))` KB; se der 1.024, cai no formato MB atual. Troque os dois textos.
- [ ] **Step 4:** mesmo comando → PASS. Commit `fix: cover size in KB and help texts (#21)`.

### Task 2: Semeadores de demonstração (backend)

**Files:**
- Create: `backend/src/main/java/br/com/sergio/sapiensia/demo/DemoAccounts.java` — `public final class` com as constantes de e-mail, senha e segredo TOTP Base32 (D6), e `public static void refuseInProd(Environment env)` que lança `IllegalStateException("demo data is never allowed in the prod profile")` se `env.acceptsProfiles(Profiles.of("prod"))`.
- Create: `backend/src/main/java/br/com/sergio/sapiensia/admin/DemoAdministratorSeeder.java` — `@Order(1)`
- Create: `backend/src/main/java/br/com/sergio/sapiensia/customer/DemoCustomerSeeder.java` — `@Order(2)`
- Create: `backend/src/main/java/br/com/sergio/sapiensia/product/DemoProductSeeder.java` — `@Order(3)`, com `static byte[] coverPng(ProductCategory)` (ImageIO) e `static byte[] pdf(String title)` (PDFBox, fonte Standard14 Helvetica)
- Modify: `backend/src/main/resources/application-local.yml` (`app.demo.enabled: ${APP_DEMO_ENABLED:false}`)
- Modify: `backend/src/test/resources/application.properties` (`app.demo.enabled=false`)
- Test: `backend/src/test/java/br/com/sergio/sapiensia/demo/DemoSeedTest.java`

**As três travas contra o perfil prod:**
1. Cada semeador é `@Profile("local")` **e** `@ConditionalOnProperty(name = "app.demo.enabled", havingValue = "true")`; o `application-prod.yml` não tem a propriedade e o perfil `local` nunca vai para a imagem (Dockerfile usa `prod`).
2. O construtor de cada semeador chama `DemoAccounts.refuseInProd(env)`: com `local,prod` juntos a aplicação não sobe.
3. As senhas só viram hash dentro dos semeadores; não há SQL, migration nem seed em `db/` com essas contas.

**Interfaces:**
- Produces: e-mails/senhas/segredo em `DemoAccounts` (usados pelo `scripts/demo.ps1`, Task 3, como texto copiado; um teste confere que os dois batem).
- `DemoAdministratorSeeder.run`: se não existe Administrador, insere `admin@example.com` com `passwordEncoder.encode(senha)`, `totp_secret = secretCipher.encrypt(segredo, AdministratorMfa.secretAad(id))`, `totp_enabled_at = now()`. Roda antes do `AdministratorBootstrapRunner` (sem `@Order`), que então vê senha definida e não manda e-mail de primeiro acesso. Se já existe, não mexe.
- `DemoCustomerSeeder.run`: insere `cliente@example.com` confirmado (`email_confirmed_at = now()`, `first_login_at` nulo) se não existe.
- `DemoProductSeeder.run`: para 101, 103 e 104, se o Produto existe em DRAFT e sem arquivo, grava capa e PDF em arquivo temporário e chama `ProductCoverService.upload(productId, temp, adminId, "demo")`, `ProductFileService.upload(productId, temp, "demo.pdf", "application/pdf", adminId, "demo", true)` e `ProductPublicationService.publish(productId, adminId, "demo")`. Assim as validações de verdade (PDF, capa, EXIF) se aplicam.

- [ ] **Step 1: Testes que falham** em `DemoSeedTest`:
  - `@Nested LocalDemo` (`@ActiveProfiles("local")`, `app.demo.enabled=true`, storage em `target/test-storage-demo`): Administrador entra com a senha e um código `Totp.code(segredo, step)`; Cliente entra com a senha; `GET /api/products` lista os 3 títulos com `coverVersion` não nulo; nenhum e-mail "Defina sua senha" gravado; rodar os três semeadores de novo não muda a contagem de linhas.
  - `@Nested ProdProfile` (`@ActiveProfiles("prod")`, `app.demo.enabled=true` e as propriedades mínimas do `ProductSeedTest.ProdProfile`): nenhum bean `Demo*Seeder` no contexto; nenhuma linha com os e-mails de demonstração; `POST /api/admin/sessions` e `POST /api/sessions` com as senhas de demonstração → 401.
  - Unidade: `DemoAccounts.refuseInProd` com perfis `local,prod` lança; só `local` não lança.
  - Unidade: o texto de `scripts/demo.ps1` contém os dois e-mails, as duas senhas e o segredo de `DemoAccounts`.
- [ ] **Step 2:** `cd backend; .\mvnw.cmd test "-Dtest=DemoSeedTest,ProductSeedTest"` → FAIL.
- [ ] **Step 3:** implemente os arquivos acima.
- [ ] **Step 4:** mesmo comando → PASS. Commit `feat: local demo seeders, never in prod`.

### Task 3: `just demo`, `just demo-codigo`, `just demo-parar` e `docs/demo.md`

**Files:**
- Create: `scripts/demo.ps1`, `scripts/demo-parar.ps1`, `scripts/demo-codigo.mjs` (HMAC-SHA1 com `node:crypto`, mesmos parâmetros do `Totp`: 6 dígitos, 30 s)
- Modify: `justfile` (receitas `[windows] demo`, `[windows] demo-parar`, `demo-codigo`)
- Create: `docs/demo.md` (para leigo: o que instalar — só Docker Desktop, Java, Node/pnpm e just já usados pelo `just setup`; o comando; as contas; o aviso da compra; como parar)
- Test: `scripts/demo-codigo.test.mjs` (vetor do RFC 6238 para SHA1 com 6 dígitos: segredo ASCII `12345678901234567890`, T=59 → `287082`)

Passos do `demo.ps1`: (1) memória livre ≥ 2 GB (`Win32_OperatingSystem.FreePhysicalMemory`); (2) Docker aberto; (3) portas 8081 e 5173 livres; (4) `docker compose up -d` e espera o `pg_isready`; (5) cria `app_demo` se não existe (`docker compose exec -T db psql -U app -d app -tAc ...`); (6) inicia a API em segundo plano com `DB_URL=jdbc:postgresql://localhost:5433/app_demo`, `APP_DEMO_ENABLED=true`, `APP_STORAGE_FILE_DIR=../.tmp/demo/storage`, `APP_EMAIL_OUTBOX_DIR=../.tmp/demo/outbox`, perfil `local`, log em `.tmp/demo/api.log`; (7) espera `/api/system/health` até 5 min; (8) inicia o Vite, log em `.tmp/demo/front.log`, espera a porta 5173; (9) grava os PIDs em `.tmp/demo/pids.txt`; (10) imprime o bloco da seção de textos. `demo-parar.ps1` encerra cada PID com `taskkill /T /PID` (árvore do processo), nunca por nome.

- [ ] **Step 1:** teste do `demo-codigo` → FAIL; implemente → PASS.
- [ ] **Step 2:** escreva `demo.ps1`, `demo-parar.ps1`, receitas e `docs/demo.md`.
- [ ] **Step 3 (verificação real):** `just demo`; no navegador, entrar como Cliente e ver os 3 Produtos com capa no Catálogo; entrar como Administrador com o código impresso e ver o painel; `just demo` de novo com tudo ligado → mensagem de porta em uso; `just demo-parar` → processos encerrados; `just demo` de novo → sobe sem duplicar.
- [ ] **Step 4:** commit `feat: just demo for a local walkthrough`.

### Task 4: `just check-rapido`, gancho e skill

**Files:**
- Create: `scripts/check-rapido.mjs` com `export function chooseTests(changedFiles: string[]): { backend: 'none' | 'all' | string[], frontend: string[], nodeTests: boolean }` e o executor (roda só quando chamado direto)
- Create: `scripts/check-rapido.test.mjs`
- Modify: `justfile` (`check-rapido: node scripts/check-rapido.mjs`; `test-scripts: node --test .claude scripts`; `test: test-back test-front test-scripts`)
- Modify: `.claude/hooks/check-antes-de-parar.mjs`
- Create: `.claude/hooks/check-antes-de-parar.test.mjs` (repo temporário, como o `conferir.test.mjs`)
- Modify: `.claude/skills/verificar-entrega/conferir.mjs` (seção 1), `.claude/skills/verificar-entrega/conferir.test.mjs`, `.claude/skills/verificar-entrega/SKILL.md`
- Modify: `CLAUDE.md`

**O que o `check-rapido` roda** (arquivos mudados = `git diff --name-only <merge-base com origin/main>` + não rastreados):
1. Sempre: `just lint` (Spotless + Biome).
2. Frontend com mudança em `frontend/`: `pnpm exec tsc -b` e `pnpm exec vitest related --run <arquivos de frontend/src mudados>`.
3. Backend: só arquivos em `backend/src/{main,test}/java/br/com/sergio/sapiensia/<f>/` com `<f>` em {admin, audit, customer, demo, email, payment, product} → `-Dtest=br/com/sergio/sapiensia/<f>/**` por funcionalidade, mais as classes de teste que citam os IDs (SEC-/FR-/NFR-) que aparecem nos arquivos mudados (armadilha do CLAUDE.md), com `-Dsurefire.failIfNoSpecifiedTests=false`. Qualquer outro arquivo de backend (`security/`, `web/`, pacote raiz, `resources/`, migration, `pom.xml`) → **todos** os testes do backend.
4. `.claude/` ou `scripts/` mudados: `node --test .claude scripts`.
5. Saída: `.tmp/check-rapido.log` completo e `.tmp/check-rapido-resultado.json` `{"ok","quando","segundos","tipo":"rapido"}`; na tela, o resumo no estilo do `check-resumo`.

**O que muda no gancho:** hoje ele só aceita `.tmp/check-resultado.json` (check completo). Passa a ler esse e o `.tmp/check-rapido-resultado.json` e usar **o mais recente**: bloqueia se não há nenhum, se o mais recente falhou ou se é mais velho que a última edição de código. A mensagem pede `just check-rapido`. O resto (só branches feat/fix/chore, só código de backend/frontend conta, bloqueia uma vez) não muda.

**O que muda na skill verificar-entrega:**
- Passo 1: "rode `just check-rapido`"; o `just check-resumo` completo só se o Sérgio pedir.
- `conferir.mjs` seção 1: mostra os dois resultados e dá OK se o mais recente passou e é posterior à última edição, dizendo qual foi ("check rápido" ou "check completo").
- Novo passo depois da PR: o CI roda o `just check` completo, os e2e e as varreduras de segurança; a entrega só está pronta com o CI verde. CI vermelho: pare e mostre (regra que já existe).
- Tabela: a linha vira "just check-rapido (ou completo)".
- **Nada mais muda:** arquivos fora do plano, teste afrouxado, requisito sem teste, `/verificar-tela`, sistema de verdade e `/code-review` continuam iguais.

**O que muda no CLAUDE.md:** seção Comandos ganha `just check-rapido` (lint + testes das áreas alteradas; o gancho exige ele) e `just demo` / `just demo-parar` / `just demo-codigo`; o texto de `just check` diz "o que o CI roda na PR; localmente só se o Sérgio pedir"; "Antes de declarar um ticket pronto" passa a citar o check rápido e o CI verde. Seções de segurança e armadilhas não mudam.

**Fora deste plano (precisa do seu OK, fora da pasta):** o `~/.claude/CLAUDE.md` global manda usar `just check-resumo` para saída longa; sugiro trocar por `just check-rapido` depois.

- [ ] **Step 1: Testes que falham** em `check-rapido.test.mjs`: `["frontend/src/a.ts"]` → backend `none`, frontend `["frontend/src/a.ts"]`; `[".../product/X.java"]` → `["br/com/sergio/sapiensia/product/**"]`; `[".../security/SecurityConfig.java"]` → `all`; `["backend/src/main/resources/db/migration/V19__x.sql"]` → `all`; `["backend/pom.xml"]` → `all`; `["docs/a.md"]` → tudo vazio; `[".claude/hooks/x.mjs"]` → `nodeTests: true`. No teste do gancho: sem resultado → bloqueia; rápido OK e recente → passa; completo OK mais velho e rápido falho mais novo → bloqueia; rápido OK mais velho que a edição → bloqueia. No `conferir.test.mjs`: rápido OK recente → seção 1 "OK" citando "check rápido".
- [ ] **Step 2:** `node --test .claude scripts` → FAIL.
- [ ] **Step 3:** implemente; atualize SKILL.md e CLAUDE.md.
- [ ] **Step 4:** `node --test .claude scripts` → PASS; `just check-rapido` nesta branch → OK, mostrando quais testes escolheu e o tempo.
- [ ] **Step 5:** commit `chore: just check-rapido and the stop hook uses it`.

### Task 5: Entrega

- [ ] `just check-rapido`; skill `verificar-entrega`; `/verificar-tela` da tela da capa; sistema de verdade = o `just demo` da Task 3, Step 3.
- [ ] Push só com `git push -u origin chore/demo-local-e-fluxo-rapido`, depois do OK; PR com `Refs #21` (não há issue própria; a Parte B vem do #21) e o CI completo como prova final.
