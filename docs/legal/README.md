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

1. Identificação do controlador (nome ou razão social, CPF ou CNPJ, endereço): **decidido em 19/11/2026 (#212)**: fica de fora por ora. Os textos dizem só "uma pessoa física" e o canal (`sapiensia.operacao@gmail.com`, para contato e reclamações). Entram nome e CNPJ quando houver CNPJ. Risco conhecido: Decreto 7.962/2013, art. 2º. O advogado confirma.
2. Bases legais de cada tratamento (hoje uma proposta no inventário). Em particular: o prazo de 5 anos se apoia no art. 27 do CDC (prescrição), mas o texto e o inventário chamam o registro financeiro de "obrigação legal"; o advogado confirma se a base é essa ou outra (por exemplo, exercício regular de direitos). E se o art. 33, IX da LGPD ("necessária para a execução de contrato") cobre a cópia de segurança do banco inteiro no Cloudflare R2, ponto que a #47 já registra.
3. Prazo de guarda de 5 anos dos registros financeiros: **decidido em 19/11/2026 (#212)**, 5 anos depois do Pedido (art. 27 do CDC). O contador só entra depois que a venda se pagar.
4. A expressão "Acesso vitalício" (ADR-0007) e a alternativa "enquanto a SapiensIA estiver em operação".
5. Transferência internacional de dados: **decidido em 19/11/2026 (#212)**: o texto diz que o Resend (e-mails) e o Cloudflare R2 (cópia de segurança do banco) tratam dados fora do Brasil e que isso é necessário para executar o contrato (LGPD, art. 33, IX, com o art. 7º, V), sem citar países (a região da conta do Resend não está registrada; o bucket de homologação do R2 está na América do Norte Leste). O advogado confirma a base legal. O Have I Been Pwned (`api.pwnedpasswords.com`) fica fora da frase: recebe só os 5 primeiros caracteres do hash SHA-1 da senha, sem e-mail nem conta (ADR-0004). Supabase e Fly.io ficam em São Paulo.
6. Encarregado de dados, ou dispensa para agente de tratamento de pequeno porte.
7. Prazo do Reembolso e direito de arrependimento (CDC art. 49) para produto digital já baixado: **decidido em 19/11/2026 (#213)**: Reembolso pelo site em até 7 dias depois do pagamento (até o fim do 7º dia), sempre concedido dentro do prazo, mesmo com o arquivo já baixado; por defeito no arquivo, por e-mail, sem o limite de 7 dias. O advogado confirma.
8. Foro e lei aplicável.
9. Cláusula de uso pessoal e proibição de redistribuição, e suas consequências.
10. Cookies: se os cookies essenciais (JSESSIONID, XSRF-TOKEN, SAPIENSIA_DEVICE) exigem aviso.
11. Idade mínima e tratamento de menores.
12. Como avisar mudanças nos Termos e na Política, e com que antecedência.
13. Prazos de atendimento: direitos do titular (proposta: declaração completa de acesso em até 15 dias, art. 19, II da LGPD), pedidos de Reembolso e atendimento em geral (proposta: primeira resposta em até 2 dias úteis), atendimento em geral, aviso de incidente.
    - Devolução do Reembolso: **decidido em 19/11/2026 (#213)**: em até 2 dias úteis depois do pedido. O prazo de resposta aos pedidos feitos por e-mail continua em aberto (o texto da Política mantém o `[a definir]`).
14. Prazos de guarda dos operadores (Asaas, Resend, Fly.io), ainda a conferir.
15. Endereço do canal de atendimento (`sapiensia.operacao@gmail.com`; a caixa já existe).

A redação final dos três textos (Termos de uso, Política de privacidade e Política de reembolso) alterados pela #213 aguarda o OK do Sérgio na PR da #213 e a revisão do advogado; os textos continuam rascunho.
