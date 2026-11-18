# Inventário de dados pessoais e retenção

Atende SEC-032 e SEC-037. Dois pontos dependem de outras pessoas e estão marcados:

- **Base legal**: é uma proposta, **a confirmar com o advogado**.
- **Prazo de 5 anos** dos registros financeiros: **provisório, a confirmar com o contador**.

Onde o prazo ou a regra é de um serviço de fora (Fly.io, Asaas, Resend), o valor está como **a conferir** (ninguém verificou ainda). Não há limpeza automática dos registros de 5 anos neste ticket (#41).

Titulares: o **Cliente** (quem compra e baixa) e o **Administrador** (uma pessoa só na Fase 1).

## Dados por tabela e por serviço

| Dado | Onde fica | Finalidade | Base legal (proposta, a confirmar com o advogado) | Titular | Destino / operador | Retenção | Como se atende o direito |
|---|---|---|---|---|---|---|---|
| E-mail do Cliente (`customers.email`) | Banco (Supabase, ADR-0002) | Entrar na conta, avisar sobre compra e segurança | Execução de contrato | Cliente | Supabase (banco); Resend (envio de e-mails) | Enquanto a conta existir; a conta nunca confirmada é apagada em 7 dias (`app.unconfirmed-customer.retention`, `UnconfirmedCustomersCleanup`) | Exportação e correção à mão, por e-mail (ADR-0007). Exclusão: anonimização (runbook `anonimizar-conta-cliente.md`) |
| Hash da senha (`customers.password_hash`) | Banco | Entrar na conta | Execução de contrato | Cliente | Supabase | Enquanto a conta existir; a anonimização troca por um hash de bytes aleatórios descartados | Exclusão: anonimização |
| Datas da conta (`created_at`, `email_confirmed_at`, `first_login_at`) | Banco | Operar a conta e detectar aparelho novo | Execução de contrato; legítimo interesse (segurança) | Cliente | Supabase | `created_at` e `email_confirmed_at` ficam depois da anonimização; `first_login_at` é zerado | Exportação à mão; exclusão: anonimização |
| Aparelhos conhecidos (`customer_devices`: hash do token do aparelho e datas) | Banco | Avisar o Cliente quando entra de um aparelho novo | Legítimo interesse (segurança) | Cliente | Supabase | Até 365 dias sem uso (limpeza diária) ou até a conta deixar de existir, o que vier primeiro; apagado na anonimização | Exclusão: anonimização |
| Tokens de confirmação de e-mail (`email_confirmation_tokens`) | Banco | Confirmar o e-mail da conta | Execução de contrato | Cliente | Supabase | Até o uso ou a expiração: 24 h (`app.email-confirmation.ttl`); apagados pela limpeza diária depois de vencidos; apagado na anonimização | Exclusão: anonimização |
| Tokens de nova senha (`password_reset_tokens`) | Banco | Recuperar a senha | Execução de contrato | Cliente | Supabase | Até o uso ou a expiração: 30 min (`app.password-reset.ttl`); apagados pela limpeza diária depois do uso ou da expiração; apagado na anonimização | Exclusão: anonimização |
| Vínculo com o Asaas (`payment_customers`: código do cliente no Asaas; o CPF nunca é guardado aqui, ADR-0003) | Banco | Cobrar o Pix do mesmo cliente | Execução de contrato | Cliente | Supabase; Asaas | Enquanto a conta existir; a linha é apagada na anonimização | Exclusão: anonimização (o lado do Asaas fica, veja abaixo) |
| Pedidos (`orders`) e Acessos (`accesses`): ids, Produto, valor, datas, estado | Banco | Registro da venda, entrega e reembolso | Execução de contrato; obrigação legal (registro financeiro) | Cliente | Supabase | 5 anos depois do Pedido, **provisório, a confirmar com o contador** | Ficam na anonimização (não têm e-mail nem nome) |
| Eventos de pagamento (`payment_events`, com o `payload` como chegou) e resultados (`payment_event_results`) | Banco | Prova do pagamento e do processamento (ADR-0003) | Obrigação legal (registro financeiro) | Cliente | Supabase; Asaas (origem do aviso) | 5 anos depois do Pedido, **provisório, a confirmar com o contador**; só admitem inserção | O `payload` **não** é anonimizado (ADR-0003: só com migration e ADR próprios); a retenção do registro financeiro prevalece |
| Registros de download (`download_records`: Cliente, Produto, versão do arquivo, data; sem IP) | Banco | Prova de entrega e limite diário (ADR-0007) | Execução de contrato; obrigação legal | Cliente | Supabase | 5 anos depois do Pedido, **provisório, a confirmar com o contador**; só admitem inserção | Ficam na anonimização; a chave do Cliente aponta para a linha anonimizada |
| Trilha de auditoria (`audit_events`: ator, ação, objeto, resultado, hora, origem/IP) | Banco | Segurança e prova das ações do painel (SEC-065, ADR-0006) | Legítimo interesse (segurança); obrigação legal | Administrador (ator) e Cliente (objeto, por id) | Supabase | 365 dias (`app.audit.retention`, limpeza diária, ADR-0006) | Não leva o e-mail do Cliente; a anonimização grava mais uma linha `ADMIN_CUSTOMER_ANONYMIZED` |
| Dados do Administrador (e-mail, hash da senha, segredo do MFA cifrado, códigos de recuperação) | Banco | Operar o painel com MFA (ADR-0004) | Legítimo interesse (segurança) | Administrador | Supabase | Enquanto a conta existir | Atendido à mão, por e-mail (ADR-0007) |
| E-mail do Cliente no limite de tentativas de login | Só na memória da aplicação, por pouco tempo | Frear tentativas de login | Legítimo interesse (segurança) | Cliente | Fly.io (processo) | Some sozinho; nada a fazer na anonimização | Não se aplica |
| Logs da aplicação | Fly.io | Operar e investigar falhas | Legítimo interesse (segurança) | Cliente e Administrador | Fly.io | Sem dado pessoal por regra (SEC-036). Prazo do Fly.io: **a conferir** no painel do Fly | Não se aplica (não deve haver dado pessoal) |
| E-mail e CPF da cobrança no Asaas (o Asaas guarda o e-mail como nome do cliente) | Asaas | Cobrar o Pix e manter o registro financeiro | Execução de contrato; obrigação legal | Cliente | Asaas | Regra do Asaas: **a conferir** | A anonimização **não** chama o Asaas. O Administrador decide com o contador se pede a remoção lá (runbook) |
| E-mails enviados (confirmação, nova senha, aparelho novo, pagamento confirmado, reembolso) | Resend | Entregar os e-mails | Execução de contrato | Cliente e Administrador | Resend | Regra do Resend: **a conferir** | A anonimização não apaga o histórico do Resend; e-mail pendente para conta anonimizada é descartado |

## O que ainda não existe no sistema

Notas, arquivos temporários de Clientes, prompts de IA e dados de suporte: o sistema ainda não guarda nada disso. O atendimento é por e-mail, fora do sistema.

## O que a anonimização muda

A explicação completa está no runbook `docs/runbooks/anonimizar-conta-cliente.md` e no ADR-0007. Em resumo: a linha do Cliente continua, sem e-mail, sem senha e sem sessões; as tabelas só de acréscimo (`payment_events`, `payment_event_results`, `download_records`) não são tocadas.

## Limites deste inventário

- Base legal e prazo de 5 anos esperam advogado e contador (o gate de lançamento do ADR-0007 já os exige).
- O que o Asaas, o Resend e o Fly.io guardam não foi verificado aqui.
- Exportação e correção de dados (FR-036, SEC-038) continuam à mão, por e-mail (ADR-0007).
