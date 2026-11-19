# Textos legais: decisões de 19/11 (#212) Implementation Plan

> **For agentic workers:** processo LEVE do projeto: implementação direta, sem subagentes, testes focados, um /code-review no fim e verificar-entrega.

**Goal:** aplicar as decisões do Sérgio de 19/11/2026 aos textos legais (sem identificação do responsável por ora, guarda de 5 anos definitiva, frase da transferência internacional) e aos documentos que repetem essas regras.

**Architecture:** só dados e documentos. `frontend/src/features/legal/legalTexts.ts` é a fonte única dos textos visíveis; os testes de `LegalPage.test.tsx` fixam o que muda. Nenhum componente, API ou migration muda.

**Tech Stack:** TypeScript, Vitest + Testing Library, Markdown.

**Spec:** issue #212 (decisões do Sérgio de 19/11/2026), ADR-0007, ADR-0002, `docs/privacidade/inventario-dados-pessoais.md`.

## Global Constraints

- Todo texto visível foi aprovado pelo Sérgio em 19/11/2026 (tabela abaixo); nenhum texto novo fora dela.
- Os demais `[a definir: ...]` continuam (encarregado, prazos de atendimento, cookies, foro, guarda dos operadores etc.).
- A Política de reembolso **não** muda aqui (fica para a #213).
- Código e commits em inglês; textos legais e docs em português.

## Review Focus

- Nenhum `[a definir: nome ou razão social` sobra nos Termos nem na Privacidade.
- "provisório" e "contador" somem do texto de guarda, mas os outros `[a definir]` ficam (o teste de pontos em aberto continua valendo).
- A linha de versão é a mesma nas três páginas.

## Textos aprovados

| Onde | Texto novo |
|---|---|
| Termos, "Quem somos", 2º parágrafo | `Responsável pelo site: uma pessoa física. Contato e reclamações: ${SUPPORT_EMAIL}.` |
| Privacidade, "Quem é o responsável pelos dados", 1º parágrafo | `Responsável pelo tratamento dos dados pessoais (controlador): o responsável pelo site, uma pessoa física.` (o 2º parágrafo, com o canal, não muda) |
| Privacidade, "Por quanto tempo guardamos" | `Pedidos, Acessos, eventos de pagamento e Registros de download: 5 anos depois do Pedido.` |
| Privacidade, "Transferência internacional" | `Dois desses serviços tratam dados fora do Brasil: o Resend, que envia os e-mails, e o Cloudflare R2, onde fica a cópia de segurança do banco. Essa transferência é necessária para executar o contrato com você (Lei 13.709/2018, art. 33, IX, com o art. 7º, V).` |
| `DRAFT_VERSION` | `Versão de rascunho de 19/11/2026.` |

## Task 1: textos visíveis

**Files:**
- Modify: `frontend/src/features/legal/legalTexts.ts`
- Test: `frontend/src/features/legal/LegalPage.test.tsx`

- [ ] **Step 1: testes vermelhos** em `LegalPage.test.tsx`:
  - `a Política de privacidade guarda os registros por 5 anos sem chamar o prazo de provisório nem citar o contador` (`not.toContain("provisório")`, `not.toContain("contador")`, `toContain("5 anos depois do Pedido")`); substitui o `toContain("provisório")` da linha 96.
  - `Termos e Privacidade não pedem mais nome, CPF e endereço do responsável` (sem `[a definir: nome ou razão social`; Termos com "pessoa física" e "Contato e reclamações"; Privacidade com "pessoa física").
  - `a seção Transferência internacional cita Resend, Cloudflare R2 e o art. 33, IX` (sem `[a definir: quais serviços`).
  - o teste da versão passa a esperar `19/11/2026`.
- [ ] **Step 2: rodar** `cd frontend; pnpm vitest run src/features/legal` e ver os quatro falharem por asserção.
- [ ] **Step 3: editar** `legalTexts.ts` com os textos da tabela e `DRAFT_VERSION`.
- [ ] **Step 4: rodar** o mesmo comando; tudo verde.
- [ ] **Step 5: commit** `docs: add the plan for the legal texts decisions (#212)` (só o plano, antes) e `fix: apply the 2026-11-19 legal text decisions (#212)`.

## Task 2: documentos

**Files:**
- Modify: `docs/privacidade/inventario-dados-pessoais.md` (linhas 3–6, 23–25, 43), `docs/adr/0007-modelo-comercial-da-fase-1.md` (linhas 18, 22, 26, 27), `docs/legal/README.md` (pontos 1, 3, 5 e nota do Have I Been Pwned), `GLOSSARY.md` (linha 96), `docs/runbooks/anonimizar-conta-cliente.md` (linhas 20 e 64)

- [ ] Troca de "provisório, a confirmar com o contador" por "5 anos depois do Pedido (art. 27 do CDC; decisão de 19/11/2026)".
- [ ] ADR-0007, gate (linha 22): só o advogado bloqueia a primeira venda; o contador entra depois que a venda se pagar; vendedor pessoa física por ora.
- [ ] README legal: ponto 1 (identificação fora por ora; entra com CNPJ; risco Decreto 7.962/2013, art. 2º), ponto 3 decidido, ponto 5 (Resend e Cloudflare R2; o Have I Been Pwned recebe só os 5 primeiros caracteres do hash SHA-1 da senha, sem e-mail nem conta, ADR-0004, e por isso não entra na frase).
- [ ] Linha 30 do inventário e as menções ao contador no ADR-0003 não mudam (decisões futuras do Administrador).
- [ ] Verificar: `git grep -n -i -E "provisório|a confirmar com o contador|definir o contador" -- docs GLOSSARY.md` sem sobras da guarda.

## Task 3: fechamento

- [ ] `just check-rapido`; `/verificar-tela` em `/termos-de-uso` e `/privacidade` (desktop e celular); skill `verificar-entrega`; um `/code-review`.
- [ ] Push e PR com `Closes #212` (merge com o Sérgio).
- [ ] Issue #47: a linha do contador ganha "(não bloqueia a primeira venda; só depois que a venda se pagar, #212)".
