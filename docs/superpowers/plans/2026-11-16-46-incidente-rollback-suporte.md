# #46 Plano de incidente, rollback e suporte

Plano curto, auto-aprovado pelo responsável no pedido da sessão. Decisões conservadoras estão marcadas **conferir**.

Requisitos: SEC-040 (procedimento de incidente: avaliação, contenção, evidências e comunicação). Spec, seção do lançamento: "Plano de incidente, contatos de fornecedores, rollback e suporte".

## O que muda

Só documentação; nenhum código, nenhuma infraestrutura nova.

1. `docs/runbooks/incidente.md` (novo): quem age (o Administrador, pessoa única; o agente só executa comandos com autorização), como classificar a gravidade (SEV1, SEV2, SEV3), primeiras ações (conter, guardar evidências, comunicar), onde olhar (log do Fly, `AdminAlerts`, conciliação, `audit_events`), contatos dos fornecedores (Fly.io, Supabase, Asaas, Resend, Cloudflare R2: canal de suporte e página de status, tirados das páginas oficiais) e o roteiro para incidente com dado pessoal (LGPD art. 48, Resolução CD/ANPD nº 15/2024: avaliar risco ou dano relevante, comunicar ANPD e titulares no prazo, registro do incidente). Reaproveita por link os runbooks existentes (webhook, conciliação, chave do Asaas, recuperar acesso, anonimizar, homologação) em vez de repetir passos.
2. `docs/runbooks/rollback.md` (novo): voltar a imagem anterior no Fly (`fly releases --image`, `fly deploy --image <imagem anterior> --ha=false`), a regra das migrations (Flyway sem "undo": corrigir para a frente com migration nova; migration compatível com a imagem anterior para o rollback da imagem funcionar; restaurar backup só em último caso, ADR-0002) e uma tabela "Registro do teste na homologação" para preencher quando o teste for feito.
3. `docs/runbooks/suporte-cliente.md` (novo): canal de e-mail, prazos de resposta, o que fazer com pedido de Reembolso (ADR-0007, `webhook-asaas.md` seção Reembolso) e com pedido de direito LGPD (acesso/exportação e correção à mão; exclusão pelo `anonimizar-conta-cliente.md`), e como registrar o atendimento sem espalhar dado pessoal.
4. `CLAUDE.md`, linha de `docs/`: citar os três runbooks novos.

## Decisões conservadoras (conferir)

- **conferir** Endereço do canal de suporte: `suporte@sapiensia.app.br` (a caixa ainda não existe; o domínio é o mesmo do remetente dos e-mails). O #23 usa o mesmo endereço.
- **conferir** Prazos: primeira resposta ao Cliente em até 2 dias úteis; Reembolso decidido em até 2 dias úteis depois do pedido; direitos LGPD: confirmação e acesso completos em até 15 dias (LGPD art. 19, II).
- **conferir** Gravidade: SEV1 = sistema fora do ar, dinheiro errado ou dado pessoal exposto; SEV2 = função importante degradada com contorno; SEV3 = o resto.
- **conferir com o advogado** Incidente com dado pessoal: prazo de 3 dias úteis para comunicar ANPD e titulares quando houver risco ou dano relevante, registro do incidente guardado por 5 anos, possível prazo em dobro para agente de pequeno porte; quem é o encarregado (DPO) ou a dispensa dele.
- **conferir** O teste do rollback na homologação **não** é feito neste ticket: mexe no ambiente real e o runbook da homologação exige autorização do responsável para qualquer comando `fly`. O critério "rollback testado na homologação" fica aberto; por isso a PR usa `Refs #46`, não `Closes #46`.
- Contatos: só canais públicos das páginas oficiais; o que não for confirmado na página oficial fica "a conferir". Nenhum número de conta, e-mail pessoal ou segredo.

## Textos que o usuário vê

Nenhum texto na aplicação. Os modelos de resposta por e-mail do `suporte-cliente.md` são lidos pelo Cliente: **conferir** antes do primeiro uso.

## Testes

Só documentação: nenhum teste de código. Conferência: `just check-rapido` (lint, inclusive o de Markdown se houver), links relativos entre runbooks existentes, e o CI da PR.

## Riscos

- Comando do flyctl errado no rollback: conferir a sintaxe na documentação oficial do Fly antes de escrever; o teste real na homologação fica pendente.
- Regra da ANPD mudada: citar a resolução e marcar para o advogado.
