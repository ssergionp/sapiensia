# Incidente: o que fazer quando algo dá errado

Procedimento para o Administrador agir quando o sistema cai, o dinheiro fica errado ou um dado pessoal pode ter vazado (SEC-040). Plano: `docs/superpowers/plans/2026-11-16-46-incidente-rollback-suporte.md`.

Legenda: **[RESPONSÁVEL]** você faz, no navegador ou numa aba comum do PowerShell. **[AGENTE]** o Claude Code roda na sessão, só com a sua autorização.

Regras que valem para tudo aqui:

- Quem age é o Administrador (pessoa única). Não existe plantão, página de status própria nem ferramenta de alerta além do `AdminAlerts` por e-mail. O agente só roda comando com a sua autorização; todo comando `fly` segue as regras de [homologacao-fly.md](homologacao-fly.md) (`-c fly.homolog.toml`, `--ha=false` em deploy).
- **Nenhum segredo e nenhum dado pessoal** em chat, issue, PR ou arquivo do git. Este documento só tem nomes.
- Termos marcados **conferir** são decisões conservadoras ainda não confirmadas por você. Os marcados **conferir com o advogado** são regras legais que precisam de revisão antes da primeira venda.

## 1. Classificar a gravidade

**conferir** Três níveis, para decidir o que fazer primeiro.

| Nível | Quando | Exemplos neste sistema | Quando agir |
|---|---|---|---|
| SEV1 | Sistema fora do ar, dinheiro errado ou dado pessoal exposto | Site fora do ar; Pix pago sem Acesso; webhook do Asaas rejeitado em massa; vazamento de dado pessoal; chave do Asaas vazada; conta do Administrador comprometida | Agora, antes de qualquer outra coisa |
| SEV2 | Função importante degradada, mas há contorno | Download falhando para alguns Clientes; e-mail de pagamento confirmado não sai (dá para reenviar); conciliação noturna não terminou | No mesmo dia |
| SEV3 | O resto | Texto errado numa tela; divergência isolada da conciliação já explicada | Pelo backlog (issue com `needs-triage`) |

Na dúvida entre dois níveis, use o mais grave.

## 2. Primeiras ações (SEV1 e SEV2)

1. **Conter.** Pare o que está piorando o problema:
   - Chave ou token vazado: troque-o. Chave do Asaas: [asaas-chave-api.md](asaas-chave-api.md), seção "Rotação da chave". Token do webhook: [webhook-asaas.md](webhook-asaas.md), seção "Rotação do token".
   - Conta do Administrador comprometida: [recuperar-acesso-administrador.md](recuperar-acesso-administrador.md).
   - Deploy ruim recente: [rollback.md](rollback.md).
2. **Guardar evidências antes de mudar qualquer coisa.** Sem copiar dado pessoal para chat ou issue: guarde o arquivo numa pasta sua, fora do git, e cite só o caminho.
   - Log do Fly:

     ```powershell
     fly logs -a sapiensia-homolog --no-tail
     ```

     Mostra as últimas linhas do log do app sem ficar acompanhando. O app de produção ainda não existe: quando existir, use o nome dele no lugar de `sapiensia-homolog` (`<app de produção>`, **a conferir**). Precisa da autorização do responsável.
   - Trilha de auditoria: no SQL Editor do Supabase, `select action, origin, occurred_at from audit_events order by occurred_at desc limit 50;` (colunas conforme [homologacao-fly.md](homologacao-fly.md), seção "Fly-Client-IP ausente").
   - Alertas do `AdminAlerts` (e-mails ao Administrador): [webhook-asaas.md](webhook-asaas.md), seção "Alertas por e-mail". Divergências da conciliação: [conciliacao-asaas.md](conciliacao-asaas.md), seção "Divergências".
   - Anote a hora em que você soube do problema (vai para o registro e, se for o caso, para a ANPD).
3. **Comunicar.** Se o problema afeta quem comprou, responda os Clientes pelo canal de [suporte-cliente.md](suporte-cliente.md). Se envolve dado pessoal, siga a seção 5.

## 3. Onde olhar

| Sintoma | Onde |
|---|---|
| Site fora do ar | `fly logs` (acima); página de status do Fly.io (seção 4); [homologacao-fly.md](homologacao-fly.md), seção "Conferências" |
| Pix pago sem Acesso, webhook rejeitado | [webhook-asaas.md](webhook-asaas.md): "Conferir um pagamento", "Motivos do `WEBHOOK_REJECTED`", "Reprocessar um evento de pagamento" |
| Divergência entre Pedido e cobrança | [conciliacao-asaas.md](conciliacao-asaas.md) |
| Reembolso ou MED | [webhook-asaas.md](webhook-asaas.md), seção "Reembolso e MED" |
| Banco ou arquivos fora do ar | Página de status do Supabase (seção 4); [homologacao-fly.md](homologacao-fly.md), "Religar o Supabase pausado"; [bucket-arquivos-produto.md](bucket-arquivos-produto.md) |
| E-mail não sai | Página de status do Resend (seção 4) |

## 4. Contatos dos fornecedores

Só canais públicos, tirados das páginas oficiais em 2026-11-16. O que não deu para confirmar está marcado **a conferir**. Não coloque número de conta nem segredo aqui.

| Serviço | Para que usamos | Canal de suporte | Página de status |
|---|---|---|---|
| Fly.io | Hospedagem do app (ADR-0001) | [Fórum da comunidade](https://community.fly.io); suporte por e-mail e portal só em plano pago ([suporte do Fly](https://docs.fly.io/about/support/)); cobrança: `billing@fly.io`, listado na mesma página (**a conferir** antes de usar). Plano atual: **a conferir** | <https://status.flyio.net/> |
| Supabase | Banco PostgreSQL e Storage (ADR-0002) | [Ticket de suporte](https://supabase.com/dashboard/support/new) (resposta varia por plano); [Discord](https://discord.supabase.com/); veja a [página de suporte](https://supabase.com/support) | <https://status.supabase.com> |
| Asaas | Cobrança Pix e webhook (ADR-0003) | Chat na conta, WhatsApp e telefone conforme a [Central de Ajuda](https://central.ajuda.asaas.com/hc/pt-br/articles/31406578052379-Quais-s%C3%A3o-os-canais-de-atendimento-do-Asaas); [Ouvidoria](https://www.asaas.com/ouvidoria) só em última instância. Horário e quem pode ligar: **a conferir** na central | <https://status.asaas.com/> |
| Resend | Envio de e-mail | Formulário na [página de suporte](https://resend.com/support) | Link "status" na página de suporte do Resend (endereço exato: **a conferir**) |
| Cloudflare (R2) | Backup noturno (ADR-0002; vem com o #40) | [Página Support do painel](https://developers.cloudflare.com/support/contacting-cloudflare-support/) e [comunidade](https://community.cloudflare.com); no plano Free, caso técnico só com plano pago (mesma página) | <https://www.cloudflarestatus.com> |

## 5. Incidente com dado pessoal

Use quando dado pessoal de Cliente (ou do Administrador) pode ter sido visto, copiado, alterado ou perdido por quem não devia. O que guardamos está em [inventario-dados-pessoais.md](../privacidade/inventario-dados-pessoais.md). **Tudo desta seção: conferir com o advogado**, antes da primeira venda.

Base legal: [LGPD, art. 48](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709.htm) (comunicar a ANPD e o titular quando o incidente puder acarretar risco ou dano relevante) e a Resolução CD/ANPD nº 15/2024 (Regulamento de Comunicação de Incidente de Segurança), resumida na [página da ANPD](https://www.gov.br/anpd/pt-br/assuntos/incidente-de-seguranca).

1. **Conter e guardar evidências** (seção 2). Anote a data e a hora em que você tomou conhecimento.
2. **Avaliar o risco.** Responda por escrito: quais dados foram afetados (veja o inventário), de quantos titulares, por quanto tempo ficaram expostos, se alguém de fora pode ter usado os dados e que dano isso pode causar. Se houver risco ou dano relevante, comunique. Na dúvida, trate como relevante e fale com o advogado.
3. **Prazo.** A página da ANPD diz **3 dias úteis** para comunicar a ANPD e os titulares, contados do conhecimento do incidente; se não der para dizer tudo nesse prazo, a comunicação pode ser complementada em até 20 dias úteis. Para agente de tratamento de pequeno porte, fontes secundárias falam em prazo em dobro, mediante declaração: **a conferir** no texto da resolução, e **conferir com o advogado** se a SapiensIA se enquadra.
4. **Conteúdo da comunicação ao titular** (linguagem simples), segundo a página da ANPD: natureza e categoria dos dados afetados; medidas técnicas de segurança usadas; riscos e possíveis impactos; motivo da demora, se houver; medidas adotadas ou previstas para reverter ou mitigar; data em que soubemos; contato para informações e, se existir, dados do encarregado.
5. **Comunicar a ANPD** pelo [formulário de comunicação de incidente](https://www.gov.br/anpd/pt-br/canais_atendimento/agente-de-tratamento/comunicado-de-incidente-de-seguranca-cis) no site da ANPD.
6. **Registrar o incidente.** O regulamento manda manter o registro, mesmo de incidente que não foi comunicado, por no mínimo 5 anos (art. 10, segundo o texto publicado). Use o modelo da seção 6 e guarde junto com a avaliação de risco da etapa 2. **Conferir com o advogado** o conteúdo mínimo e o local de guarda.
7. **Encarregado (DPO).** Quem é, ou se há dispensa para agente de pequeno porte, ainda não está decidido: **conferir com o advogado**. Enquanto não houver, o contato da comunicação é o canal de [suporte-cliente.md](suporte-cliente.md).
8. **Titular que pede exclusão depois do incidente:** [anonimizar-conta-cliente.md](anonimizar-conta-cliente.md).

## 6. Ao fechar o incidente

1. Confirme que o sistema voltou ao normal (mesmas conferências da seção 3).
2. Preencha o registro abaixo. Se tiver dado pessoal, guarde-o fora do git; a versão sem dado pessoal pode ir para o repositório.

| Campo | Conteúdo |
|---|---|
| Id e data | (ex.: INC-2026-001, data) |
| Quando começou / quando soubemos | |
| Gravidade | SEV1, SEV2 ou SEV3 |
| O que aconteceu | Uma ou duas frases |
| Dado pessoal envolvido? | Sim ou não; categorias e quantidade de titulares (sem listá-los) |
| O que foi feito | Contenção e correção |
| Comunicação | ANPD? Titulares? Clientes? Datas |
| Causa | |
| Issue de melhoria | Número |

3. Abra **uma** issue de melhoria com o rótulo `needs-triage`, citando o incidente, para o que ainda precisa mudar no sistema ou nos runbooks. Corrija este documento se faltou passo.

## O que não fazer

- Não cole log, e-mail de Cliente nem chave em chat, issue ou PR.
- Não rode `fly`, deploy ou rollback sem a sua autorização.
- Não restaure backup para "desfazer" um incidente sem ler [rollback.md](rollback.md): Pedidos feitos depois do último backup se perdem (ADR-0002).
- Não invente prazo legal: use a seção 5 e confirme com o advogado.
