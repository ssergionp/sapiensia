# #186 (etapa 3 de 3) — Redesign: painel do Administrador

> **Para quem executa:** processo LEVE: implementação direta (superpowers:executing-plans), sem subagentes. Passos com `- [ ]`.

**Objetivo:** aplicar a direção Pinho & Terracota (DESIGN.md) às telas do painel do Administrador e dar ao painel uma navegação própria. Nenhuma regra de negócio, rota ou chamada de API muda. A PR fecha o #186 (`Closes #186`).

**Arquitetura:** o painel é modo *Operate*: a marca aparece nos detalhes (tipografia, superfícies, foco, selos) e não em gestos grandes.
- Uma barra de navegação do painel (`AdminNav`) fica **dentro** do `AdminGuard`. Ela só aparece com a sessão do Administrador já carregada e não faz chamada a mais. O `SiteHeader` continua só com marca e tema nas rotas `/painel/*`.
- As telas trocam só a apresentação. As que hoje usam `p-8` passam a usar os utilitários `page-container` (telas de trabalho) e `auth-panel` (entrar, verificação, definir senha), que já existem.

**Base:** branch `feat/186-redesign-painel`, criada da `origin/main` em `03ddf3b`, depois do merge da #191.

**Spec:** issue #186 (corpo e comentário "Direção de arte DEFINIDA") e `DESIGN.md`.

## Restrições globais

- Só aparência. Nenhuma rota, chamada de API, schema, capacidade ou regra muda.
- Testes existentes passam sem afrouxar. Um seletor só muda quando o elemento mudou de lugar, e cada ajuste fica listado na PR.
- Contraste AA em todo texto e AAA nos botões de ação, nos dois temas. Terracota nunca é cor de texto no tema claro. Nenhum roxo.
- Nenhuma dependência nova e nenhuma animação nova.
- O e-mail do Cliente em Pedidos continua mascarado pelo servidor (`MaskedEmail`); a tela só o exibe.

## Decisões (dependem do seu OK)

- **D1 — Navegação do painel:** barra `AdminNav` logo abaixo do cabeçalho, em todas as telas com sessão do Administrador.
  - Itens: "Painel", "Produtos" (só com `PRODUCTS_EDIT`) e "Pedidos" (só com `ORDERS_VIEW`), mais o botão "Sair".
  - A página atual fica marcada como no cabeçalho do Cliente: `aria-current` e o traço Terracota.
  - Fica fora do `SiteHeader` porque o cabeçalho também aparece em `/painel/entrar`. Lá, consultar a sessão do Administrador daria 401 e um erro no console.
  - "Sair" sai do corpo da página inicial e vai para a barra, disponível em todo o painel, com o mesmo `useLogout`, "Saindo…" e `LOGOUT_ERROR`.
- **D2 — Página inicial do painel sem links repetidos** (como o D3 da etapa 2): os parágrafos "Produtos" e "Pedidos" do corpo saem, porque a barra já os tem.
  - Ficam o h1 "Painel do Administrador", "Você entrou como …" e as duas seções de segurança ("Códigos de recuperação" e a troca do autenticador), cada uma num cartão.
  - Os testes `AdminHomeProductsLink` e `AdminHomeOrdersLink` continuam valendo: a barra é renderizada pela própria página e cada link segue único.
- **D3 — Tabelas continuam tabelas** (Produtos e Pedidos): é o padrão que o Administrador já conhece para conferir linhas.
  - Recebem superfície de cartão, cabeçalho de coluna em texto secundário, números com `tabular-nums` e a coluna de estado como selo.
  - No celular, rolagem horizontal dentro da região. A região de Produtos ganha `tabIndex=0` e `aria-labelledby`, como a de Pedidos já tem: hoje a tabela de Produtos não rola pelo teclado (WCAG 2.1.1).
- **D4 — Selos de estado:**
  - Pedidos usa o mesmo selo de Meus Pedidos.
  - Produtos: Rascunho neutro, Publicado em Pinho, Despublicado neutro com borda.
  - As cores dos selos e do ícone de pago passam a ser tokens (`--success`, `--success-foreground`) no `index.css`, em vez de hex no código. Isso resolve parte do item "Cores fixas no código" do #190. As cores de reserva das capas ficam como estão.
- **D5 — Entrar, verificação (código e recuperação) e definir senha** usam o `auth-panel`, como as telas de conta do Cliente: cartão centralizado, campos e botão de 44 px, botão de envio na largura toda.
- **D6 — Formulário do Produto (novo e editar):** coluna única (`max-w-2xl`), e cada seção num cartão: Dados, Preço, Capa, Arquivo PDF e Publicação.
  - O aviso de estado ("Este Produto está…") vira uma faixa com o selo do D4.
  - Os `<select>` nativos (Categoria e o filtro de Pedidos) ganham a altura e a borda do `Input`.
  - O pedido de confirmação de publicar ou remover fica dentro do cartão da seção, como hoje.
- **D7 — Links "Voltar ao painel" e "Voltar para Produtos" ficam** como links de texto (`text-link`). Ainda servem de atalho no celular, e o e2e os usa.

## Textos novos que o usuário vai ver

- "Painel" (item da barra; "Produtos" e "Pedidos" só mudam de lugar).
- "Navegação do painel" (nome da barra para leitor de tela).
- Nenhum texto existente muda. "Sair", "Saindo…", "Produtos" e "Pedidos" só mudam de lugar.

## Review Focus

1. Administrador sem `PRODUCTS_EDIT` ou sem `ORDERS_VIEW`: o item correspondente não aparece na barra. Teste na Task 1.
2. `/painel/entrar` e `/painel/verificacao` (sem sessão completa): sem barra e sem chamada a mais. Teste na Task 1 e conferência do console no `/verificar-tela`.
3. "Sair" falhando numa tela que não é a inicial: a mensagem `LOGOUT_ERROR` aparece na barra. Teste na Task 1.
4. Seletores do e2e sem escopo ("Sair", "Produtos", "Pedidos") devem continuar achando um elemento só. Conferido pelo e2e completo.
5. 390 px: barra e tabelas sem rolagem horizontal da página; a rolagem fica só dentro da região da tabela. Conferido pelo `/verificar-tela`.

---

### Task 1: barra de navegação do painel e página inicial

**Arquivos:** criar `frontend/src/features/admin/AdminNav.tsx` (+ `AdminNav.test.tsx`); alterar `AdminGuard.tsx` (estados de carregando, erro e sem permissão em `page-container`) e `AdminHomePage.tsx` (+ ajuste do teste dela, se um seletor mudar).

- [ ] Teste (vermelho): com as duas capacidades, a barra mostra "Painel", "Produtos", "Pedidos" e "Sair". Sem `ORDERS_VIEW`, não mostra "Pedidos". Sem `PRODUCTS_EDIT`, não mostra "Produtos". A página atual tem `aria-current="page"`. Se o logout falhar, a barra mostra `LOGOUT_ERROR`.
- [ ] `AdminNav({ session }: { session: AdminSession })`: `NavLink` com o mesmo estilo dos links do cabeçalho (extrair a classe `NAV_LINK` do `SiteHeader` para `components/brand/brandTexts.ts` e reutilizá-la); a lógica de sair vem do `AdminHomePage`.
- [ ] As páginas com sessão (início, Produtos, formulário e Pedidos) renderizam `<AdminNav session={…} />` no topo. A página inicial perde os links e o botão "Sair" do corpo; as seções de segurança vão para cartões.
- [ ] `just check-rapido` verde; commit.

### Task 2: tokens de estado e selos

**Arquivos:** `frontend/src/index.css` (`--success` e `--success-foreground`, claro e escuro), `frontend/src/theme-tokens.test.ts` (contraste dos novos pares), um componente `frontend/src/components/StatusBadge.tsx` (+ teste) usado por `orders/OrdersPage.tsx`, `orders/OrderPage.tsx` (ícone de pago), `admin-orders/AdminOrdersPage.tsx` e `products/ProductListPage.tsx`.

- [ ] Teste (vermelho): `--success-foreground` sobre o fundo do selo atinge AA nos dois temas.
- [ ] Teste: o selo mostra o mesmo texto de hoje e tem `data-status`. Os testes de selo de Meus Pedidos continuam passando sem mudança.
- [ ] Implementar e trocar os hex pelos tokens; commit.

### Task 3: telas de entrada do painel

**Arquivos:** `AdminLoginPage.tsx`, `AdminMfaPage.tsx`, `SetAdminPasswordPage.tsx`, `RecoveryCodesList.tsx`, `AuthenticatorKey.tsx` (só classes).

- [ ] `auth-panel`, h1 `text-3xl`, envio `h-11 w-full text-base`, links em `text-link`, h1 com foco programático em `outline-none` (como na etapa 2).
- [ ] Testes existentes verdes; commit.

### Task 4: Produtos (lista e formulário) e Pedidos

**Arquivos:** `ProductListPage.tsx`, `ProductFormPage.tsx`, `ProductCoverSection.tsx`, `ProductFileSection.tsx`, `ProductPublicationSection.tsx`, `AdminOrdersPage.tsx`.

- [ ] Teste (vermelho): a região da tabela de Produtos tem nome ("Produtos") e é focável (WCAG 2.1.1).
- [ ] Tabelas no D3, formulário no D6, `select` no estilo do `Input`; commit.

### Task 5: verificação

- [ ] e2e completo contra o sistema de verdade (`just e2e`); a jornada do painel já escaneia 10 telas com axe nos dois temas.
- [ ] `/impeccable critique` e `/impeccable polish` nas telas do painel.
- [ ] `/verificar-tela` das telas do painel em 1280, 768 e 390 px, claro e escuro, nos estados normal, carregando, vazio e erro 500.
- [ ] `/code-review`, `verificar-entrega` com a tabela, e PR com `Closes #186` (push só com o seu OK).
