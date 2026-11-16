# Auditoria de acessibilidade das jornadas (#45) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** escrito no MODO NOTURNO (16/11/2026), sem aprovação do Sérgio. Decisões que dependeriam dele: seção 6, todas **DECIDIDO NO MODO NOTURNO — conferir**, listadas na PR. Regras do Sérgio para este ticket: checagem automatizada com axe (`@axe-core/playwright`, dependência autorizada) nas telas das jornadas de compra e Download, **dentro dos e2e existentes**; corrigir o que for simples; o que não for, e a conferência manual com leitor de tela, viram issues `needs-triage`. Sem migration.

**Regras da execução:** cada verificação nova falha por asserção antes da correção (a primeira execução do axe mostra as violações); localmente só lint, Vitest dos arquivos tocados e os dois testes de jornada do e2e (projeto `desktop`, filtrados por nome), um comando pesado por vez; `verificar-entrega` e `/code-review` antes da PR. O check que vale é o do CI (que roda todos os e2e).

**Goal:** os e2e das jornadas de compra e Download passam a falhar se uma tela delas tiver violação WCAG 2.x A/AA detectável pelo axe; o que o axe achar e for simples é corrigido; o resto e a auditoria manual (teclado e leitor de tela) ficam documentados e com issues.

**Architecture:** um helper `frontend/e2e/a11y.ts` (`expectNoA11yViolations(page, screen)`) roda o `AxeBuilder` com as tags WCAG 2.0/2.1/2.2 A e AA e falha com uma mensagem legível (regra, impacto, seletores). Os testes de jornada do `admin-panel.e2e.ts` chamam o helper em cada ponto estável das telas. Violações que não têm correção simples ficam numa lista de exceções no próprio helper, cada uma com a regra, a tela e o número da issue; qualquer violação nova faz o teste falhar.

**Tech Stack:** Playwright 1.64.1, `@axe-core/playwright` (nova, devDependency do frontend, versão estável atual conferida no registro), React + Tailwind + shadcn/ui, Vitest.

**Spec:** issue #45 (pai #5); `docs/spec/sapiensia-spec.md` NFR-005 (l.570), FR-033 (l.297), seção 14 (l.798-816), gate de lançamento (l.697, l.720).

## IDs cobertos

| ID | O que este ticket entrega | Onde |
|---|---|---|
| NFR-005 | Ferramenta automática (axe, WCAG 2.2 AA) nas jornadas críticas + roteiro da auditoria manual | Tasks 1, 2, 3 |
| FR-033 | Teclado, leitor de tela, foco, contraste: o que o axe cobre corrigido; o resto documentado e com issue | Tasks 2, 3 |

## Global Constraints

- O axe roda **dentro** dos testes existentes (`admin-panel.e2e.ts`, teste da jornada completa ~l.1726 e teste do Download/limite ~l.1511); nenhum spec novo, nenhum mock (`page.route`) novo.
- Tags: `wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa`, `wcag22aa`. Regras `best-practice` fora (não são WCAG).
- A mensagem de falha lista, por violação: id da regra, impacto, ajuda (`helpUrl`) e até 5 seletores. Nunca o HTML inteiro (pode ter e-mail do Cliente).
- Exceção conhecida = regra + tela + issue; nunca desligar uma regra para todas as telas.
- Correção "simples": muda atributo, marcação, cor de token ou foco **sem texto novo de tela**. O que pedir texto novo, mudança de layout ou decisão de design vira issue.
- Nenhum texto novo de tela sem estar na lista da seção 5.
- Testes de jornada levam o ID no nome (`NFR-005 FR-033: ...` acrescentado ao nome existente, sem perder os IDs atuais).

## Review Focus

1. **Tela ainda carregando** (capa da Biblioteca, código Pix): o scan roda depois do ponto estável que o próprio teste já espera; nunca num estado intermediário que muda a cada execução.
2. **Violação que só aparece às vezes** (animação, foco): o scan não pode deixar o teste instável; se uma regra oscilar, ela vira issue e exceção, não `retry`.
3. **Contraste de token compartilhado** (`--muted-foreground`, `--destructive`): mudar o token muda todas as telas; conferir que o painel do Administrador e as outras telas continuam legíveis (as telas do painel entram no `verificar-tela`).
4. **Celular:** o `admin-panel` só roda no projeto `desktop`; as telas do Cliente no celular ficam para a auditoria manual (documentado).

---

## 1. Telas e pontos de scan

| Tela | Ponto estável (teste da jornada completa, salvo indicação) |
|---|---|
| Cadastro `/cadastro` | antes de enviar o formulário (dentro de `registerAndConfirm` não: no próprio teste, depois de abrir a página) |
| Entrar `/entrar` | depois de "Você entrou como…" ficar visível → scan da tela inicial; e antes do login, com o formulário |
| Catálogo `/catalogo` | link do Produto visível e capa carregada |
| Produto `/catalogo/:id` | `h1` e preço visíveis |
| Comprar com Pix (CPF) | depois de clicar em "Comprar com Pix", com o campo CPF visível |
| Pedido pendente `/pedidos/:id` | "Pague com Pix" e o código visíveis |
| Pedido pago | texto de pagamento confirmado visível |
| Meus Pedidos `/pedidos` | item com "Pago" visível |
| Biblioteca `/biblioteca` | item visível e capa carregada |
| Download iniciado | depois do evento de download (mensagem de estado) |
| Limite do Download | teste do Download, depois do alerta do 11º clique |

## 2. O que o axe não cobre (vai para a auditoria manual)

Ordem de foco e armadilhas de teclado, foco visível em cada controle, anúncios do leitor de tela (NVDA + Chrome no Windows), zoom 200% e espaçamento de texto, título da aba por página, link para pular ao conteúdo, telas no celular. Cada item vira linha do roteiro em `docs/acessibilidade/auditoria-jornadas.md` e a execução manual vira **uma** issue `needs-triage` para o Sérgio.

## 3. Testes com IDs (resumo) e o que não será provado

- Provado: nenhuma violação WCAG A/AA detectável pelo axe nas 11 telas da seção 1 (fora as exceções com issue).
- **Não provado:** leitor de tela de verdade, teclado de ponta a ponta, celular, zoom, critérios WCAG 2.2 que o axe não automatiza.

---

### Task 1: dependência e helper

**Files:** Modify `frontend/package.json`, `frontend/pnpm-lock.yaml` (`pnpm add -D @axe-core/playwright@<versão estável atual>`); Create `frontend/e2e/a11y.ts`, `frontend/e2e/a11y.test.ts` (Vitest, como o `totp.test.ts`).

**Interfaces — Produces:** `async function expectNoA11yViolations(page: Page, screen: string): Promise<void>`; `function formatViolations(violations: AxeResult[]): string` (pura, testável); `const KNOWN_ISSUES: ReadonlyArray<{ rule: string; screen: string; issue: number }>`.

- [ ] **Step 1: testes que falham** (Vitest): `formatViolations` lista id, impacto, helpUrl e no máximo 5 seletores por violação e nunca o `html` do nó; uma violação que está em `KNOWN_ISSUES` para a mesma tela é ignorada; a mesma regra em outra tela não é.
- [ ] **Step 2:** `cd frontend; pnpm vitest run e2e/a11y.test.ts` → falham por asserção (o Vitest precisa incluir `e2e/*.test.ts` como já inclui o `totp.test.ts`; conferir). **Step 3:** implementar. **Step 4:** verde + `just lint` + `pnpm exec tsc -p tsconfig.e2e.json` (ou o comando que o projeto usa para checar o e2e). **Step 5:** commit `test: axe accessibility helper for the e2e journeys (#45)`.

### Task 2: scans nas jornadas e correções simples

**Files:** Modify `frontend/e2e/admin-panel.e2e.ts` (chamadas da seção 1; nomes dos dois testes ganham `NFR-005 FR-033`), os componentes/tokens que tiverem correção simples (`frontend/src/...`, `src/index.css`), `frontend/e2e/a11y.ts` (`KNOWN_ISSUES` com as issues abertas).

- [ ] **Step 1:** acrescentar as chamadas do helper nos pontos da seção 1.
- [ ] **Step 2: rodar e ver falhar por asserção:** com o sistema local (banco da pasta principal na 5433 já ligado; `just dev-back` e `just dev-front` na worktree), `cd frontend; pnpm exec playwright test e2e/admin-panel.e2e.ts --project=desktop -g "FR-016|FR-034"`. Guardar a lista de violações por tela no relatório. Se a máquina não tiver memória para subir API + frontend + navegador (ela matou o check nesta noite), parar os processos pelo PID e usar o CI da branch como execução (push e leitura do relatório do e2e), registrando isso.
- [ ] **Step 3: corrigir as simples** (Global Constraints), cada uma com teste Vitest quando muda comportamento do componente; para cada não simples, abrir uma issue `needs-triage` (título "Acessibilidade: <regra> em <tela>", corpo com regra, impacto, seletores, critério WCAG e a origem #45) e colocar a exceção em `KNOWN_ISSUES`.
- [ ] **Step 4:** rodar de novo o mesmo comando → verde. `/verificar-tela` das telas cujos componentes ou tokens mudaram (inclui uma tela do painel se mudou token global). Parar `dev-back` e `dev-front` pelo PID.
- [ ] **Step 5:** commit `fix: accessibility issues found by axe on the purchase and download journeys (#45)` (ou `test: ...` se nada precisou de correção).

### Task 3: auditoria documentada

**Files:** Create `docs/acessibilidade/auditoria-jornadas.md` (pt-BR: o que é verificado automaticamente e onde; resultado por tela da execução desta noite; correções feitas; exceções com issue; roteiro manual da seção 2 passo a passo para teclado e NVDA + Chrome, por tela, com o que conferir; como rodar); Modify `CLAUDE.md` ("Reaproveitar antes de criar": `frontend/e2e/a11y.ts` — tela nova numa jornada crítica ganha um `expectNoA11yViolations` no e2e).

- [ ] Abrir **uma** issue `needs-triage` "Acessibilidade: auditoria manual das jornadas (teclado e leitor de tela)" apontando para o roteiro. Commit `docs: accessibility audit of the purchase and download journeys (#45)`.

## Verificação final

`verificar-entrega` (CI no lugar do check completo; telas mudadas → `/verificar-tela`; sistema de verdade = a execução local da Task 2 ou o e2e do CI); `/code-review`. `just check-rapido` uma vez no fim (se for morto por memória, não repete). Push, PR (`Closes #45`) com a seção 6, as issues abertas e os textos.

## 6. Decisões tomadas no modo noturno

| # | Decisão | Escolha (mais conservadora) | Status |
|---|---|---|---|
| A1 | Regras do axe | Só WCAG 2.0/2.1/2.2 A e AA (sem `best-practice`) | DECIDIDO NO MODO NOTURNO — conferir |
| A2 | Onde rodar | Dentro dos dois testes de jornada existentes, projeto `desktop` | DECIDIDO NO MODO NOTURNO — conferir |
| A3 | Violação sem correção simples | Exceção por regra + tela, com issue; nunca desligar a regra inteira | DECIDIDO NO MODO NOTURNO — conferir |
| A4 | O que é "simples" | Atributo, marcação, token de cor ou foco, sem texto novo de tela | DECIDIDO NO MODO NOTURNO — conferir |
| A5 | Auditoria manual (teclado, NVDA, celular, zoom) | Roteiro documentado + uma issue para o Sérgio executar | DECIDIDO NO MODO NOTURNO — conferir |

## Textos novos em pt-BR

Nenhum texto novo de tela previsto. Documento novo em pt-BR: `docs/acessibilidade/auditoria-jornadas.md`. Títulos das issues abertas em pt-BR.
