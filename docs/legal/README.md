# Textos legais (rascunhos)

Origem: #23. Os Termos de uso, a Política de privacidade e a Política de reembolso são **rascunhos** e precisam da revisão de um advogado antes da primeira venda real (gate de lançamento do ADR-0007). Toda página mostra o aviso "RASCUNHO — revisar com advogado".

## Onde ficam os textos

- Texto dos três documentos, aviso de rascunho, versão e e-mail de atendimento: `frontend/src/features/legal/legalTexts.ts` (fonte única; este README não repete o texto).
- Página: `LegalPage.tsx`; rodapé com os links: `SiteFooter.tsx` (aparece em todas as telas).
- Rotas: `/termos-de-uso`, `/privacidade`, `/reembolso`.
- Pontos em aberto aparecem no próprio texto como `[a definir: ...]`.

## Como trocar pelo texto revisado

1. Edite os `sections` em `legalTexts.ts` com o texto aprovado; apague cada `[a definir: ...]` resolvido.
2. Atualize `DRAFT_VERSION`. Quando o texto for final, remova `DRAFT_NOTICE` e a linha de versão de rascunho em `LegalPage.tsx` (e ajuste os testes em `frontend/src/features/legal/` e `frontend/e2e/legal-pages.e2e.ts`).
3. Confira que o texto continua coerente com `docs/privacidade/inventario-dados-pessoais.md` e com o ADR-0007; rode `just check-rapido`.

## Avaliação preliminar de RIPD (SEC-041)

- O sistema não trata dados pessoais sensíveis, o volume é pequeno (um Administrador e poucos Clientes), e não há perfilamento nem decisão automatizada.
- Conclusão provisória: o RIPD provavelmente **não é exigido agora**. **Conferir com o advogado.**
- Reavaliar quando chegarem recursos de IA com dados de Clientes ou perfis (fases seguintes), e a cada mudança relevante no inventário.

## Pontos que o advogado precisa decidir

1. Identificação do controlador (nome ou razão social, CPF ou CNPJ, endereço): pessoa física ou jurídica.
2. Bases legais de cada tratamento (hoje uma proposta no inventário).
3. Prazo de guarda de 5 anos dos registros financeiros (provisório; combinar com o contador).
4. A expressão "Acesso vitalício" (ADR-0007) e a alternativa "enquanto a SapiensIA estiver em operação".
5. Transferência internacional de dados (Fly.io, Resend, Cloudflare e outros): países e garantia legal.
6. Encarregado de dados, ou dispensa para agente de tratamento de pequeno porte.
7. Prazo do Reembolso e direito de arrependimento (CDC art. 49) para produto digital já baixado.
8. Foro e lei aplicável.
9. Cláusula de uso pessoal e proibição de redistribuição, e suas consequências.
10. Cookies: se os cookies essenciais (JSESSIONID, XSRF-TOKEN, SAPIENSIA_DEVICE) exigem aviso.
11. Idade mínima e tratamento de menores.
12. Como avisar mudanças nos Termos e na Política, e com que antecedência.
13. Prazos de atendimento: direitos do titular (proposta: declaração completa de acesso em até 15 dias, art. 19, II da LGPD), pedidos de Reembolso e atendimento em geral (proposta: primeira resposta em até 2 dias úteis), atendimento em geral, aviso de incidente.
14. Prazos de guarda dos operadores (Asaas, Resend, Fly.io), ainda a conferir.
15. Endereço do canal de atendimento (`sapiensia.operacao@gmail.com`; a caixa já existe).
