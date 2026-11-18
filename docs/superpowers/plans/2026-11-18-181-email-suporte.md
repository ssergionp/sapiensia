# #181 — Trocar o e-mail de suporte (LEVE)

Troca `suporte@sapiensia.app.br` por `sapiensia.operacao@gmail.com`, mantendo uma fonte por lado.

## O que muda
- `frontend/src/features/legal/legalTexts.ts`: `SUPPORT_EMAIL` (fonte única do frontend; rodapé, páginas legais e página do produto já usam a constante).
- `backend/src/main/java/br/com/sergio/sapiensia/payment/OrderRefundedEmail.java`: `SUPPORT_EMAIL` (fonte única do backend).
- `docs/runbooks/suporte-cliente.md` e `docs/legal/README.md`: endereço novo; a caixa já existe, então sai o aviso "a caixa ainda não existe".
- Planos antigos em `docs/superpowers/plans/` ficam como estão (histórico).

## Testes (falham antes)
- `SiteFooter.test.tsx`: link e `mailto:` com o endereço novo, escrito por extenso (não pela constante).
- `backend/src/test/java/br/com/sergio/sapiensia/payment/OrderRefundedEmailTextTest.java` (FR-023): corpo do e-mail termina com o endereço novo.

## Textos que o usuário vê
Só o endereço `sapiensia.operacao@gmail.com` (rodapé, páginas legais, página do Produto, e-mail de Reembolso). Aprovado pelo Sérgio. Nenhum outro texto muda.

## Riscos
- Remetente `nao-responda@sapiensia.app.br` não muda (é outro endereço).
- Sem decisão pendente.
