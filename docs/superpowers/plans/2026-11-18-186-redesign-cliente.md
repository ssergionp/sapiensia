# #186 (etapa 2 de 3) — Redesign: telas do Cliente e navegação no cabeçalho

> **Para quem executa:** processo LEVE — implementação direta (superpowers:executing-plans), sem subagentes. Passos com `- [ ]`.

**Objetivo:** aplicar a direção Pinho & Terracota (DESIGN.md) às telas do Cliente e levar os links de navegação para o cabeçalho, sem mudar regra de negócio, rota nem chamada de API.

**Arquitetura:** o `SiteHeader` passa a ler a sessão do Cliente pelo `useSession` que já existe (mesma query `["session"]` da página inicial, então não há chamada a mais nas telas do Cliente) e mostra os links conforme a sessão; nas rotas `/painel/*` ele continua só com marca e tema e não chama a sessão do Cliente (evita um GET que o Administrador recebe com 403). As telas trocam só a apresentação: largura, cartões em superfície Papiro/Carvão, títulos em Fraunces, botões de ação na cor de ação. Os textos existentes ficam; os novos estão listados abaixo.

**Base:** branch `feat/186-redesign-cliente`, criada a partir de `feat/186-redesign-base` (PR #188). Depois do merge da #188, faço rebase na `main`.

**Spec:** issue #186 (corpo + comentário "Direção de arte DEFINIDA") e `DESIGN.md`.

## Restrições globais

- Só aparência: nenhuma rota, chamada de API, schema ou regra muda. Endpoints: `/api/products?page=` (só `page`), `/api/sessions/current`, os de pedidos e biblioteca, como estão.
- Testes existentes passam sem afrouxar; seletores mudam só onde um texto ou papel ARIA mudou de lugar, e cada ajuste fica listado abaixo.
- Contraste AA em todo texto e AAA nos botões de ação, nos dois temas; Terracota nunca como texto no claro; nenhum roxo.
- O QR Code do Pix fica sempre sobre fundo branco (leitura pelo app do banco), também no tema escuro.
- Respeitar "reduzir movimento"; sem biblioteca de animação nova (só transições CSS curtas em hover/foco).

## Review Focus

1. Admin logado abre `/painel/*`: o cabeçalho não pode chamar `/api/sessions/current` (403 → erro no console) — teste na Task 1.
2. Sessão carregando ou com erro: o cabeçalho não mostra "Carregando…" nem pisca o estado sem sessão depois do login — teste na Task 1 (o `App.test.tsx` já vigia o "piscar" com um MutationObserver global; ele continua global).
3. Links duplicados entre cabeçalho e corpo quebram seletores sem escopo — o corpo deixa de repetir os links do cabeçalho (D3) e os seletores afetados estão listados.
4. Capa ausente ou que falha ao carregar: o cartão mantém a proporção 2:3 com a cor de reserva da categoria — teste na Task 3.
5. 390 px com sessão: quatro itens no cabeçalho cabem sem rolagem horizontal (segunda linha no celular) — `/verificar-tela`.

---

### Task 1: navegação no cabeçalho e página inicial

**Arquivos:** `frontend/src/components/SiteHeader.tsx` (+ teste), `frontend/src/features/session/HomePage.tsx` (+ teste), `frontend/src/App.test.tsx` (só se um seletor mudar), `frontend/e2e/password-reset.e2e.ts:84` (escopo `main`).

**Comportamento do cabeçalho:**
- Rotas `/painel/*`: marca + tema (como hoje), sem `useSession`.
- Sem sessão: "Catálogo" e "Entrar" (este como botão de ação). Na própria `/entrar`, o link "Entrar" não aparece (você já está lá; também evita duplicar com o h1 e o botão da tela).
- Com sessão: "Catálogo", "Biblioteca", "Meus Pedidos" e o botão "Sair" (mesmo `useLogout`, mesmos "Saindo…" e mensagem de erro `LOGOUT_ERROR`).
- Sessão carregando ou com erro: só "Catálogo".
- Link da página atual com `aria-current="page"` e um traço Terracota decorativo embaixo.
- Celular (< 640 px): os links vão para uma segunda linha do cabeçalho, sem menu escondido.

**Página inicial (Hero):** título "Conhecimento pronto *para usar.*" (Fraunces, "para usar." em itálico, cor de ação, `nowrap`), frase de apoio e ações:
- sem sessão: "Ver o Catálogo" (ação) e "Criar conta" (secundário); "Entrar" fica só no cabeçalho;
- com sessão: "Você entrou como {email}." (texto atual), "Ver o Catálogo" e "Sair de todos os aparelhos" (continua aqui; "Sair" vai para o cabeçalho); "Biblioteca" e "Meus Pedidos" ficam só no cabeçalho.
- A mensagem "Você saiu de todos os aparelhos." continua no corpo.

- [ ] **Testes primeiro** (`SiteHeader.test.tsx`, com `QueryClientProvider` e `fetch` simulado):
  - `sem sessão, mostra Catálogo e Entrar`; `com sessão, mostra Catálogo, Biblioteca, Meus Pedidos e Sair`; `Sair encerra a sessão` (DELETE `/api/sessions/current`, depois mostra Entrar);
  - `no painel, não consulta a sessão do Cliente` (nenhum fetch para `/api/sessions/current` em `/painel/pedidos`);
  - `em /entrar, não repete o link Entrar`; `marca a página atual` (`aria-current="page"` no link "Biblioteca" em `/biblioteca`);
  - `enquanto a sessão carrega, não mostra Carregando…`.
  - Substitui o teste da etapa 1 `não repete os links de navegação das páginas` (a decisão D2 da etapa 1 era temporária).
- [ ] **Testes que mudam de arquivo** (mesmas asserções, agora no cabeçalho): em `HomePage.test.tsx`, as que procuram os links "Entrar", "Biblioteca", "Meus Pedidos" e o botão "Sair" passam para `SiteHeader.test.tsx`; os testes da página inicial passam a provar o que ela mostra agora ("Ver o Catálogo", "Criar conta", "Sair de todos os aparelhos", e-mail).
- [ ] Implementar; `pnpm exec vitest run src/components src/features/session src/App.test.tsx`.
- [ ] Commit `feat: customer navigation in the site header and hero home page (#186)`.

### Task 2: base comum das telas

**Arquivos:** `frontend/src/components/ui/card.tsx` (componente Card do shadcn, gerado pelo CLI que já é dependência: `pnpm dlx shadcn@4.23.0 add card`, sem pacote novo), `frontend/src/index.css` (utilitário `page-container`: `mx-auto w-full max-w-6xl px-4 sm:px-6 py-8 sm:py-12`, alinhado ao cabeçalho).

- [ ] Gerar o Card e ajustar ao DESIGN.md (raio 14 px, borda 1 px Traço, fundo `bg-card`, sem sombra).
- [ ] Commit `feat: card and page container for the redesign (#186)`.

### Task 3: Catálogo, capa e Produto

**Arquivos:** `features/catalog/CatalogPage.tsx`, `CatalogProductPage.tsx`, `CoverImage.tsx` (+ testes), `features/orders/BuyWithPix.tsx` (só classes), `features/catalog/messages.ts`.

- **Catálogo:** grade de cartões (1 coluna no celular, 2 em 640 px, 3 em 1024 px); a capa é o maior elemento do cartão; título (Plus Jakarta Sans 600), categoria, preço com `tabular-nums`, resumo em até 3 linhas e "Ver detalhes →" na cor de ação. O cartão inteiro é clicável por um único link (o título; o resto do cartão estende a área de clique), sem link duplicado para leitor de tela. Paginação com os botões atuais.
- **Capa (`CoverImage`):** recebe a categoria (opcional); sem imagem ou com falha, o fundo de reserva usa a cor da categoria (D5) e mostra o título em Fraunces sobre ela, tudo `aria-hidden` (o título já está no cartão).
- **Produto:** capa grande à esquerda (em cima no celular), título, categoria, preço grande, descrição; o botão único "Comprar com Pix" (já existe) com a lista de vantagens e as linhas de reembolso e suporte logo abaixo (textos novos).
- [ ] **Testes primeiro:** `CoverImage`: `sem imagem, mostra a cor da categoria e o título` (`data-category="FINANCE"` e o texto do título no quadro `aria-hidden`); `com falha ao carregar, troca pela cor da categoria`. `CatalogProductPage`: `mostra as vantagens, o link da Política de reembolso e o atendimento perto do botão de compra` (texto das três vantagens; link "Política de reembolso" → `/reembolso`; link mailto do atendimento). `CatalogPage`: `cada cartão tem um único link para o produto` (`getAllByRole("link", { name: título })` com tamanho 1).
- [ ] Implementar; `pnpm exec vitest run src/features/catalog src/features/orders/BuyWithPix`.
- [ ] Commit `feat: catalog cards, category cover fallback and product page redesign (#186)`.

### Task 4: Entrar, Cadastro, Confirmar e-mail, Recuperar senha e Nova senha

**Arquivos:** `features/session/LoginPage.tsx`, `features/customer/RegisterPage.tsx`, `ConfirmEmailPage.tsx`, `ForgotPasswordPage.tsx`, `PasswordLinkForm.tsx`.

- Formulário num Card centralizado (largura `max-w-md`), título em Fraunces, campos e botões do shadcn com a altura de 44 px, botão de envio na cor de ação ocupando a largura, links secundários abaixo. Estados de sucesso e erro com o mesmo texto, em caixas com ícone (lucide) e cor de erro/sucesso que passam AA.
- Sem teste novo de comportamento (nada muda); os testes atuais da tela precisam passar como estão.
- [ ] `pnpm exec vitest run src/features/session src/features/customer`; commit `feat: redesign of the customer sign-in and account screens (#186)`.

### Task 5: Biblioteca, Meus Pedidos e Pedido/Pix

**Arquivos:** `features/library/LibraryPage.tsx`, `DownloadButton.tsx` (só classes), `features/orders/OrdersPage.tsx`, `OrderPage.tsx`.

- **Biblioteca:** lista de cartões com capa (cor neutra de reserva, a Biblioteca não tem categoria), título, "Comprado em …" e o botão "Baixar" na cor de ação.
- **Meus Pedidos:** cartões com título, valor (`tabular-nums`), data e o estado num selo (Aguardando pagamento, Pago, Expirado, Reembolsado — os mesmos textos), com cor + texto (nunca só cor).
- **Pedido/Pix:** QR Code no quadro branco, código copia e cola, "Copiar código" na cor de ação, contagem regressiva em destaque; os estados pago, expirado e reembolsado em Card com o mesmo texto.
- [ ] **Teste primeiro:** `OrdersPage`: `o estado do pedido aparece como texto no selo` (cada estado mostra o texto atual dentro do selo).
- [ ] `pnpm exec vitest run src/features/library src/features/orders`; commit `feat: redesign of library, orders and Pix screens (#186)`.

### Task 6: acessibilidade no tema escuro na jornada do Cliente (e2e)

**Arquivos:** `frontend/e2e/a11y.ts` (+ `a11y.test.ts`), `frontend/e2e/admin-panel.e2e.ts` (jornada do Cliente, linha ~1886).

- [ ] `expectNoA11yViolations(page, screen, { bothThemes: true })`: varre no tema atual, troca o esquema de cor do navegador para o outro, varre de novo com o nome `<screen>-escuro`, e volta. Teste unitário do helper para os nomes das telas.
- [ ] Usar `bothThemes` nas varreduras da jornada do Cliente (cadastro, entrar, início, catálogo, produto, comprar-pix, pedido-pendente, pedido-pago, meus-pedidos, biblioteca).
- [ ] Com o sistema de verdade: `pnpm exec playwright test e2e/admin-panel.e2e.ts -g "jornada"` e `e2e/legal-pages.e2e.ts e2e/customer-login.e2e.ts e2e/logout-everywhere.e2e.ts e2e/password-reset.e2e.ts e2e/system.e2e.ts` → passam. O e2e completo roda no CI.
- [ ] Commit `test: axe scans of the customer journey in both themes (#186)`.

### Fechamento da etapa

- [ ] `/impeccable critique` e `/impeccable polish` nas telas do Cliente.
- [ ] `/verificar-tela` de cada tela em 1280, 768 e 390 px, claro e escuro, com os estados carregando, vazio e erro.
- [ ] Sistema de verdade: a jornada do Cliente da Task 6 (cadastro → catálogo → produto → Pix → pago → Biblioteca → Download) roda contra API e banco de verdade, sem simulação; além dela, clico a mesma jornada uma vez no navegador nos dois temas.
- [ ] `just check-rapido`, `verificar-entrega`, `/code-review`, PR com `Refs #186` depois do seu OK.

## Decisões (precisam do seu OK)

| ID | Decisão |
|----|---------|
| D1 | **Sem filtro por categoria e sem busca no Catálogo nesta etapa.** A API do catálogo só aceita `page`; filtrar só a página carregada mostraria resultados errados. Proponho uma issue separada (com mudança de API, processo COMPLETO) e deixar a etapa 2 só visual. |
| D2 | Cabeçalho do Cliente: sem sessão "Catálogo" + "Entrar"; com sessão "Catálogo", "Biblioteca", "Meus Pedidos" + "Sair". "Criar conta" fica na página inicial e no Entrar; "Sair de todos os aparelhos" fica na página inicial. No celular, os links numa segunda linha, sem menu "hambúrguer". |
| D3 | A página inicial deixa de repetir os links do cabeçalho (Entrar, Biblioteca, Meus Pedidos, Sair). Os testes dessas ações passam para o teste do cabeçalho; os e2e continuam encontrando um único link de cada nome. |
| D4 | Selo "Novos materiais toda semana" (citado na direção de arte): **fica fora** até você confirmar que é verdade — é uma promessa ao Cliente. Se confirmar, entra no Hero. |
| D5 | Cores de reserva das categorias sem cor na direção de arte: Tecnologia `#133B4D`, Negócios `#114B3E`, Outros `#182026` (as definidas: Finanças `#114B3E`, Direito `#8C3B20`, Segurança `#182026`, Saúde `#1F5C4D`, Educação `#16264C`). |
| D6 | Sem animação nova nesta etapa (só transições de hover/foco); "Motion" fica para quando houver um momento que precise. |
| D7 | Processo LEVE (só aparência), como você indicou, mesmo tocando as telas de login e compra. |

## Textos que o usuário vê (novos)

- Hero: "Conhecimento pronto para usar." (título; "para usar." em itálico) e a frase de apoio "E-books, cartilhas, tutoriais e checklists para estudar e aplicar no dia a dia."
- Cabeçalho: "Catálogo" (link; o mesmo nome do título da tela) e o nome da navegação para leitor de tela "Navegação principal".
- Cartão do Catálogo: "Ver detalhes →".
- Produto, perto do botão "Comprar com Pix":
  - "Pagamento por Pix, confirmado automaticamente."
  - "Arquivo em PDF, sempre na versão mais recente."
  - "Fica guardado na sua Biblioteca."
  - "Quer devolver? Veja a [Política de reembolso]."
  - "Dúvidas? [suporte@sapiensia.app.br]"
- Nenhum texto existente muda; "Entrar", "Biblioteca", "Meus Pedidos", "Sair", "Saindo…", "Ver o Catálogo", "Criar conta" e "Sair de todos os aparelhos" só mudam de lugar.

## Seletores de teste que mudam (sem afrouxar)

- `frontend/e2e/password-reset.e2e.ts:84`: link "Entrar" da mensagem de sucesso em `/redefinir-senha` passa a ser procurado dentro de `main` (o cabeçalho sem sessão também tem "Entrar").
- `HomePage.test.tsx`: asserções de "Entrar", "Biblioteca", "Meus Pedidos" e "Sair" passam para `SiteHeader.test.tsx` (D3).
- `SiteHeader.test.tsx`: sai o teste temporário da etapa 1 que proibia links no cabeçalho.
- Nenhum outro: os demais seletores do levantamento continuam com um único elemento de cada nome (conferido nome a nome: "Entrar" só no cabeçalho em `/`; "Criar conta" e "Ver o Catálogo" só no corpo; "Meus Pedidos" só no cabeçalho; "Sair de todos os aparelhos" só no corpo; o painel não ganha botão "Sair" do Cliente).

## Riscos

- O `App.test.tsx` vigia, com um MutationObserver, qualquer link para `/entrar` durante o login; por isso o cabeçalho esconde "Entrar" na própria `/entrar` e não mostra o estado sem sessão enquanto a sessão carrega.
- O cabeçalho chama a sessão em toda tela do Cliente: é a mesma query da página inicial (sem chamada a mais lá), mas telas como `/termos-de-uso` passam a fazer um GET `/api/sessions/current` que não faziam (com o cache do TanStack Query, uma vez por montagem e ao voltar o foco à aba). Antes de implementar, confiro se esse endpoint tem limite por IP que o e2e possa estourar.
- A etapa 2 é grande (11 telas). Se a revisão ficar pesada, separo a Task 5 numa PR própria, avisando antes.
