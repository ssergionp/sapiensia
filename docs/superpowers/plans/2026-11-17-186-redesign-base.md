# #186 (etapa 1 de 3) — Redesign: base da marca e tema claro/escuro

> **Para quem executa:** processo LEVE — implementação direta (superpowers:executing-plans), sem subagentes. Passos com `- [ ]`.

**Objetivo:** trocar a base visual (tokens, fontes, logo, cabeçalho, rodapé e troca de tema) pela direção de arte Pinho & Terracota definida no último comentário da issue #186, sem mudar regra de negócio, rota nem chamada de API.

**Arquitetura:** os tokens do shadcn em `frontend/src/index.css` passam a ter os valores da marca (o Tailwind e os componentes já leem `--background`, `--primary` etc., então as telas mudam de cor sem tocar nelas). O tema vive na classe `dark` do `<html>`: um script pequeno no `<head>` (`/theme-init.js`, arquivo próprio por causa da CSP `script-src 'self'`) aplica a escolha antes da primeira pintura; o React só lê e troca essa classe. Um `SiteHeader` global entra no `App.tsx` acima das rotas.

**Stack:** React 19, Tailwind 4 (`@theme inline`), shadcn, @fontsource-variable, Vitest + Testing Library, Playwright + axe.

**Spec:** issue #186 (corpo) + último comentário "Direção de arte DEFINIDA pelo Sérgio (17/11, 23:25)", que prevalece sobre o corpo onde divergem (Plus Jakarta Sans no lugar de Inter; fundo Pedra e superfícies Papiro no claro).

## Restrições globais

- Fontes: Fraunces (títulos, peso 600, `letter-spacing: -0.025em`) e Plus Jakarta Sans (UI, pesos 500/600/700), servidas pelo próprio site via @fontsource; nenhum Google Fonts.
- Claro: página Pedra `#E9E8E2`; superfícies Papiro `#FAF6F0`; texto Carvão `#121615`; ação Pinho `#114B3E` com texto `#FAF6F0`.
- Escuro: página `#0D110F`; superfícies Carvão `#121615`; texto Papiro `#FAF6F0`; ação Terracota `#F08A4B` **sempre com texto `#121615`**.
- Terracota `#F08A4B` no claro só como elemento decorativo, nunca como texto (2,03:1 sobre Pedra).
- Nenhum roxo nem verde-limão (inclui o favicon roxo do Vite e o `--sidebar-primary` roxo do tema escuro atual).
- Contraste: AA (4,5:1) em todo texto, AAA (7:1) no texto dos botões de ação.
- Tema: segue o sistema; o botão troca e a escolha fica lembrada no navegador.
- Nenhuma regra de negócio, rota ou API muda; testes existentes passam sem afrouxar.

## Contrastes conferidos (fórmula WCAG)

| Par | Razão |
|-----|-------|
| Carvão sobre Pedra / sobre Papiro | 14,86 / 16,94 |
| Papiro sobre Pinho (botão claro) | 9,29 |
| Pinho sobre Pedra (link) | 8,15 |
| Secundário `#4F5A55` sobre Pedra / Papiro | 5,85 / 6,67 |
| Papiro sobre `#0D110F` / Carvão | 17,66 / 16,94 |
| Carvão sobre Terracota (botão escuro) | 7,33 |
| Terracota sobre `#0D110F` (link escuro) | 7,65 |
| Secundário escuro `#A9B0AC` sobre `#0D110F` / Carvão | 8,60 / 8,25 |

## Review Focus

1. Primeira pintura no tema escuro sem "piscar" claro (escolha salva = escuro, ou sistema escuro sem escolha) — teste do `theme-init.js` (Task 2).
2. `localStorage` bloqueado (aba privada, cookies bloqueados): a página abre e o botão troca o tema mesmo sem conseguir lembrar — testes da Task 2.
3. Sistema muda de claro para escuro com a página aberta e sem escolha salva: a página acompanha; com escolha salva, não — teste da Task 2.
4. Contraste no tema escuro nas jornadas do e2e (o axe hoje só roda no claro) — scan escuro na Task 4.
5. Links duplicados no cabeçalho quebrariam seletores do e2e sem escopo (`link "Entrar"`, `"Meus Pedidos"`, `"Ver o Catálogo"`) — por isso o cabeçalho desta etapa não tem esses links (D2).

---

### Task 1: tokens, fontes e DESIGN.md

**Arquivos:**
- Modificar: `frontend/package.json` (+ `pnpm-lock.yaml`): adicionar `@fontsource-variable/fraunces@^5.3.0` e `@fontsource-variable/plus-jakarta-sans@^5.3.0`; remover `@fontsource-variable/geist`.
- Modificar: `frontend/src/index.css`
- Criar: `frontend/src/theme-tokens.test.ts`
- Criar: `DESIGN.md` (raiz)

**Produz:** tokens CSS `--background`, `--foreground`, `--card`, `--popover`, `--primary`, `--primary-foreground`, `--muted-foreground`, `--brand-mark` (cor do ponto do logo), `--brand-decor` (traços/marcadores terracota) em `:root` e `.dark`, em hex; `--font-sans` = Plus Jakarta Sans, `--font-heading` = Fraunces.

- [ ] **Passo 1: teste que falha** — `theme-tokens.test.ts` lê `src/index.css` (fs), extrai os valores hex de `:root` e `.dark` e confere:
  - `tema claro e escuro usam as cores da marca`: `:root` → background `#E9E8E2`, card `#FAF6F0`, foreground `#121615`, primary `#114B3E`, primary-foreground `#FAF6F0`; `.dark` → background `#0D110F`, card `#121615`, foreground `#FAF6F0`, primary `#F08A4B`, primary-foreground `#121615`.
  - `texto comum tem contraste AA e botão de ação AAA nos dois temas`: foreground e muted-foreground ≥ 4,5 sobre background e card; primary-foreground sobre primary ≥ 7; primary (como link) ≥ 4,5 sobre background.
  - `nenhum token usa roxo`: nenhum valor dos dois blocos tem matiz entre 250° e 320° com saturação > 20%.
- [ ] **Passo 2:** `cd frontend; pnpm exec vitest run src/theme-tokens.test.ts` → FALHA pela asserção (valores oklch atuais).
- [ ] **Passo 3: implementar** — `pnpm add` / `pnpm remove` dos pacotes; em `index.css`: `@import "@fontsource-variable/fraunces"`, `@import "@fontsource-variable/fraunces/wght-italic.css"` (o itálico do Hero na etapa 2), `@import "@fontsource-variable/plus-jakarta-sans"`; tokens em hex (secondary/muted/accent/border/input/ring/sidebar/chart derivados da paleta: muted `#DEDCD4`/`#1A201D`, border `#D3D0C6`/`#26302C`, ring = primary); `--muted-foreground` `#4F5A55` / `#A9B0AC`; `--destructive` mantido (conferir contraste ≥ 4,5 sobre os dois fundos; se reprovar, `#B42318` / `#FF8A7A`); `@layer base`: `h1, h2, h3 { font-family: var(--font-heading); font-weight: 600; letter-spacing: -0.025em }`.
- [ ] **Passo 4:** mesmo comando → PASSA.
- [ ] **Passo 5:** `DESIGN.md` na raiz: tokens claro/escuro (tabela acima), tipografia (incluindo o Hero e `tabular-nums` nos preços), regra da terracota, contrastes, cores de reserva das capas (Finanças `#114B3E`/`#133B4D`, Direito `#8C3B20`, Segurança `#182026`, Saúde `#1F5C4D`, Educação `#16264C`), componentes (botão de ação, link "Ver detalhes →", filtro ativo, selo, cartão), tema e movimento (respeitar "reduzir movimento").
- [ ] **Passo 6:** commit `feat: Pinho & Terracota tokens, brand fonts and DESIGN.md (#186)`.

### Task 2: tema claro/escuro (script de início + botão)

**Arquivos:**
- Criar: `frontend/public/theme-init.js`; modificar `frontend/index.html` (`<script src="/theme-init.js"></script>` no `<head>`, antes do CSS; trocar `<meta name="color-scheme" content="light dark">`).
- Criar: `frontend/src/lib/theme.ts`, `frontend/src/lib/theme.test.ts`, `frontend/src/lib/theme-init.test.ts`
- Criar: `frontend/src/components/ThemeToggle.tsx`, `frontend/src/components/ThemeToggle.test.tsx`

**Produz:**
- `THEME_STORAGE_KEY = "sapiensia-theme"`; valores `"light" | "dark"`; sem chave = seguir o sistema.
- `useTheme(): { theme: "light" | "dark"; toggle: () => void }` — estado inicial lido de `document.documentElement.classList.contains("dark")`; `toggle` troca a classe e grava a chave (gravação em `try/catch`); sem chave salva, escuta `matchMedia("(prefers-color-scheme: dark)")` e acompanha.
- `<ThemeToggle />` — `<button type="button">` com ícone `Moon`/`Sun` (lucide) e `aria-label` "Usar tema escuro" / "Usar tema claro".

- [ ] **Passo 1: testes que falham**
  - `theme-init.test.ts` (executa o conteúdo de `public/theme-init.js` com `new Function` sobre o jsdom, `matchMedia` simulado): `escolha salva "dark" liga o tema escuro`; `sem escolha salva, segue o sistema escuro`; `escolha salva "light" vence o sistema escuro`; `localStorage que lança erro não impede seguir o sistema`.
  - `theme.test.ts`: `sem escolha salva, acompanha a mudança do sistema`; `com escolha salva, ignora a mudança do sistema`.
  - `ThemeToggle.test.tsx`: `no tema claro, o botão "Usar tema escuro" liga o escuro e lembra a escolha` (classe `dark` no `<html>`, `localStorage["sapiensia-theme"] === "dark"`, botão passa a "Usar tema claro"); `sem conseguir gravar no navegador, o botão ainda troca o tema` (`setItem` lança).
- [ ] **Passo 2:** `pnpm exec vitest run src/lib/theme src/lib/theme-init src/components/ThemeToggle` → FALHA (módulos/arquivo inexistentes; depois, pela asserção).
- [ ] **Passo 3:** implementar `theme-init.js` (IIFE, sem dependências, `try/catch` no `localStorage`), `theme.ts` e `ThemeToggle.tsx` (variante `ghost` do `Button`, tamanho ícone, foco visível com `ring`).
- [ ] **Passo 4:** mesmo comando → PASSA.
- [ ] **Passo 5:** commit `feat: light/dark theme that follows the system and remembers the choice (#186)`.

### Task 3: logo, favicon, cabeçalho e rodapé

**Arquivos:**
- Criar: `frontend/src/components/brand/Logo.tsx` (símbolo SVG inline: quadrado arredondado `fill: var(--primary)`, livro aberto em traço `var(--primary-foreground)`, ponto `var(--brand-mark)` — terracota no claro, Papiro no escuro; `aria-hidden`), e a palavra "SapiensIA" em Fraunces 600.
- Substituir: `frontend/public/favicon.svg` (mesmo símbolo, cores fixas Pinho/Papiro/Terracota).
- Criar: `frontend/src/components/SiteHeader.tsx`, `frontend/src/components/SiteHeader.test.tsx`
- Modificar: `frontend/src/App.tsx` (`<SiteHeader />` antes de `<Routes>`), `frontend/src/features/legal/SiteFooter.tsx` (+ teste existente, se houver; senão `SiteFooter.test.tsx`)

**Consome:** `<ThemeToggle />` (Task 2), tokens (Task 1).

- [ ] **Passo 1: testes que falham**
  - `SiteHeader.test.tsx`: `mostra a marca com link para a página inicial` (`link "SapiensIA — página inicial"` com `href="/"`); `tem o botão de trocar o tema`; `não repete os links de navegação das páginas` (nenhum link "Entrar", "Meus Pedidos", "Ver o Catálogo" no cabeçalho — guarda o D2).
  - Rodapé: `mostra a frase da marca` ("Conhecimento pronto para usar.") e os links legais e de suporte continuam iguais.
- [ ] **Passo 2:** `pnpm exec vitest run src/components src/features/legal` → FALHA.
- [ ] **Passo 3:** implementar. Cabeçalho: `<header>` em superfície Papiro/Carvão, borda inferior `border`, conteúdo em `mx-auto max-w-6xl px-4 sm:px-6`, altura 64 px; marca à esquerda, botão de tema à direita. Rodapé: mesma largura; marca pequena + frase; links legais sem sublinhado até o hover/foco (sublinhado volta no foco); `© 2026 SapiensIA`. Fundo da página = Pedra/`#0D110F` (vem dos tokens).
- [ ] **Passo 4:** mesmo comando → PASSA; depois `pnpm exec vitest run` (suíte do frontend inteira, para pegar `App.test.tsx` e testes que contam links/landmarks).
- [ ] **Passo 5:** commit `feat: brand logo, site header and restyled footer (#186)`.

### Task 4: acessibilidade no tema escuro (e2e)

**Arquivos:** modificar `frontend/e2e/legal-pages.e2e.ts` (jornada pública que não precisa de conta).

- [ ] **Passo 1:** novo teste `páginas públicas passam no axe também no tema escuro`: `page.emulateMedia({ colorScheme: "dark" })`, abre `/` e `/termos-de-uso`, confere `html` com classe `dark` e chama `expectNoA11yViolations`; depois clica em "Usar tema claro", recarrega e confere que continua claro (escolha lembrada vence o sistema).
- [ ] **Passo 2:** com `just db`, `just dev-back`, `just dev-front`: `cd frontend; pnpm exec playwright test e2e/legal-pages.e2e.ts e2e/system.e2e.ts` → PASSA. (O e2e completo roda no CI.)
- [ ] **Passo 3:** commit `test: axe scan of public pages in dark theme (#186)`.

### Fechamento da etapa

- [ ] `/impeccable critique` e `/impeccable polish` (ver D4) sobre cabeçalho, rodapé e tokens; correções que couberem nesta etapa.
- [ ] `/verificar-tela` da página inicial, `/entrar` e `/termos-de-uso` em 1280 e 390 px, claro e escuro, com teclado (foco visível no botão de tema e nos links do rodapé).
- [ ] `just check-rapido`; `verificar-entrega`; `/code-review`; PR com `Refs #186` (D5) e a lista de textos novos, depois do seu OK para o push.

## Decisões (precisam do seu OK)

| ID | Decisão |
|----|---------|
| D1 | Novas dependências: `@fontsource-variable/fraunces` e `@fontsource-variable/plus-jakarta-sans` (5.3.0, OFL-1.1); sai `@fontsource-variable/geist`. |
| D2 | Nesta etapa o cabeçalho tem só a marca e o botão de tema, igual em todas as telas (inclusive o painel). Os links (Catálogo, Biblioteca, Meus Pedidos, Entrar) vão para o cabeçalho na etapa 2, junto com a nova página inicial, onde os seletores do e2e são ajustados no mesmo commit. Motivo: hoje os mesmos links estão no corpo da página inicial e o e2e os procura sem escopo. |
| D3 | Na palavra "SapiensIA" do logo, "IA" vai na cor de ação (Pinho no claro, Terracota no escuro), em Fraunces itálico — eco do "*para usar.*" do Hero. |
| D4 | frontend-design não está instalada (existe no marketplace oficial) e impeccable não existe nesta máquina. Instalar fica fora da pasta do projeto: preciso do seu OK para instalar os dois plugins, ou sigo lendo o SKILL.md da frontend-design do marketplace e faço critique/polish manualmente por um roteiro equivalente. |
| D5 | Etapas 1 e 2 usam `Refs #186`; só a PR da etapa 3 usa `Closes #186`. |
| D6 | O sistema muda de tema sozinho só enquanto não há escolha salva; não há opção "seguir o sistema" depois de clicar (para voltar, limpar os dados do site). Menos UI; se quiser um terceiro estado, entra na etapa 2. |

## Textos que o usuário vê (novos)

- Link da marca (nome acessível): "SapiensIA — página inicial"
- Botão de tema (nome acessível e dica): "Usar tema escuro" / "Usar tema claro"
- Rodapé: "Conhecimento pronto para usar." e "© 2026 SapiensIA"

Nenhum texto existente muda.

## Riscos

- Fraunces variável completa pesa mais que a Geist: importar só o eixo `wght` (+ itálico) e os subsets que o fontsource separa por `unicode-range` (o navegador baixa só o latim).
- Componentes que hoje usam `bg-background` como superfície de cartão passam a ficar Pedra, não Papiro: aceito nesta etapa; as telas ganham `bg-card` na etapa 2.
- Snapshot/teste que conte `role="banner"` ou links: coberto pela suíte inteira no Passo 4 da Task 3.
