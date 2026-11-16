# #23 Rascunhos dos textos legais

Plano curto, auto-aprovado pelo responsável no pedido da sessão. Decisões conservadoras estão marcadas **conferir**.

Requisitos: FR-023 (Reembolso alinhado à política publicada), FR-036 (direitos do titular), SEC-041 (avaliar a necessidade de RIPD), ADR-0007 (regras comerciais que o Cliente enxerga).

## O que muda

1. **Frontend, `src/features/legal/`** (novo):
   - `legalTexts.ts`: fonte única dos três documentos (Termos de uso, Política de privacidade, Política de reembolso) como dados (título, seções com parágrafos e listas), mais o aviso de rascunho, o endereço do canal de atendimento e os textos do rodapé.
   - `LegalPage.tsx`: uma página que mostra um documento: `<main>`, `<h1>`, aviso de rascunho bem visível (`role="note"`), seções com `<h2>`, título da aba pelo `useDocumentTitle`.
   - `SiteFooter.tsx`: `<footer>` com os três links e o e-mail de atendimento (`mailto:`).
   - Testes Vitest de cada peça.
2. **`App.tsx`**: rotas `/termos-de-uso`, `/privacidade` e `/reembolso`, e o `SiteFooter` depois das rotas, em todas as telas (inclusive o painel: **conferir**). Sem mudança no backend: o `SpaConfig` já manda essas rotas ao `index.html`.
3. **e2e** `frontend/e2e/legal-pages.e2e.ts`: com o sistema de verdade, abre a página inicial, segue cada link do rodapé, confere título e aviso de rascunho e roda `expectNoA11yViolations` em cada página (telas `termos`, `privacidade`, `reembolso`); a lista de telas em `docs/acessibilidade/auditoria-jornadas.md` ganha as três.
4. **`docs/legal/README.md`** (novo): onde ficam os textos, como trocar depois da revisão, a avaliação preliminar de RIPD (SEC-041) e a **lista de pontos que o advogado precisa decidir**.

## Conteúdo dos textos (pt-BR, todos marcados "RASCUNHO — revisar com advogado")

Coerente com `docs/privacidade/inventario-dados-pessoais.md`, ADR-0002, ADR-0003, ADR-0004, ADR-0007:
- **Termos de uso**: quem somos (identificação do responsável em aberto), conta obrigatória, compra só por Pix via Asaas, Acesso "vitalício" (expressão a rever, ADR-0007), Download por link de 60 s com limite de 10 por dia por Produto, uso pessoal e proibição de redistribuição, preço calculado no servidor, Reembolso (remete à política), suspensão por abuso, mudanças nos termos, lei e foro (em aberto), contato.
- **Política de privacidade**: controlador (em aberto), dados tratados (e-mail, hash da senha, aparelhos conhecidos, Pedidos e Acessos, Registros de download sem IP, trilha de auditoria com IP em ações do painel, CPF e e-mail no Asaas para a cobrança), finalidades e bases legais (propostas), operadores (Supabase — banco e arquivos, São Paulo; Fly.io — hospedagem, região São Paulo; Asaas — pagamento; Resend — e-mails; Cloudflare R2 — cópia de segurança), transferência internacional (em aberto), retenção (tabela do inventário; 5 anos provisório), cookies só essenciais (sessão, CSRF, aparelho conhecido; sem análise nem publicidade — o implementador confere no código), direitos do titular e como pedir (e-mail; resposta em até 15 dias, **conferir**), anonimização em vez de apagar o registro financeiro, segurança, encarregado (em aberto), mudanças.
- **Política de reembolso**: pedido por e-mail, em até 7 dias da compra (arrependimento, CDC art. 49) ou por defeito no arquivo; devolução pelo Pix via Asaas; o Acesso é cancelado depois do estorno; pode comprar de novo; prazos de resposta (**conferir**).

## Decisões conservadoras (conferir)

- **conferir** Endereço do canal: `suporte@sapiensia.app.br` (o mesmo do runbook de suporte do #46; a caixa ainda não existe).
- **conferir** Rotas em português: `/termos-de-uso`, `/privacidade`, `/reembolso`.
- **conferir** Rodapé em todas as telas, inclusive o painel.
- **conferir** Identificação do responsável (nome, CPF/CNPJ, endereço) aparece como `[a definir: …]` no próprio texto, visível como pendência.
- Os textos ficam só no frontend (um lugar); o advogado revisa as páginas na homologação ou o `legalTexts.ts`. O `docs/legal/README.md` não repete o texto.
- O ticket diz "aprovados pelo responsável antes do push"; o pedido da sessão autorizou PR com os rascunhos, e a aprovação fica na lista de decisões.
- A PR usa `Closes #23`: os critérios do ticket são as páginas em rascunho; a revisão do advogado é do ticket do gate de lançamento.

## Textos que o usuário vê (fora o corpo dos documentos)

- Rodapé: "Termos de uso", "Política de privacidade", "Política de reembolso", "Atendimento: suporte@sapiensia.app.br", rótulo do rodapé "Informações legais".
- Aviso: "RASCUNHO — revisar com advogado. Este texto ainda não é a versão final e pode mudar."
- Títulos das páginas (h1): "Termos de uso", "Política de privacidade", "Política de reembolso"; abas: "<título> | SapiensIA".
- Linha "Versão de rascunho de 16/11/2026."
- O corpo dos três documentos: rascunho completo, para o responsável e o advogado lerem na PR.

## Testes

- Vitest: o rodapé tem os três links com os destinos certos e o `mailto:`; cada página mostra o `h1`, o aviso de rascunho, o e-mail do canal; a Política de reembolso cita 7 dias e defeito no arquivo (FR-023); a Política de privacidade cita os direitos e o canal (FR-036). IDs no título dos testes.
- e2e: o descrito acima (sistema de verdade + axe).
- `/verificar-tela` nas três páginas e no rodapé (desktop e celular, teclado).
- SEC-041: não se testa; a avaliação está no `docs/legal/README.md`.

## Riscos

- O rodapé aparece em toda tela: um teste existente que conte links pode mudar (ajustar só se for consequência direta, explicando).
- Texto jurídico errado: tudo marcado como rascunho e a lista de pontos vai ao advogado.
