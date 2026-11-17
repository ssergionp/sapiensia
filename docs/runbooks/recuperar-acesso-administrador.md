# Recuperar o acesso do Administrador (troca definitiva do autenticador)

O primeiro caminho é pelo painel: quem tem o celular, ou ao menos um código de recuperação, troca o autenticador sozinho (veja a tabela abaixo e a atualização de 17/11/2026 do ADR-0004). O procedimento manual deste documento (ADR-0004, `docs/adr/0004-identidade-e-mfa.md`) é só para quem não tem nem o celular nem um código de recuperação. Não há rota pública para isso: quem tem acesso ao banco apaga a linha do Administrador, o sistema recria a conta e o Administrador refaz a senha e o MFA. Não coloque senha, segredo, chave nem código de recuperação em nenhum comando, ticket ou conversa.

## Quando usar este procedimento e quando usar os códigos

| Situação | O que fazer |
|---|---|
| Perdi o celular, mas guardei os códigos de recuperação | Entre com um código ("Perdeu o celular? Use um código de recuperação") e, no painel, use "Trocar o autenticador": a sessão aberta por código de recuperação não pede o código do aplicativo antigo. Confirme com um código do aplicativo novo e guarde os 10 códigos novos (os anteriores deixam de valer, e as outras sessões caem). Cada código vale uma vez. |
| Quero trocar de celular e ainda tenho o aplicativo atual | Entre normalmente, use "Trocar o autenticador" no painel e digite o código do aplicativo atual para começar. O autenticador antigo vale até você confirmar o novo. |
| Perdi o celular e não tenho código de recuperação (acabaram ou se perderam) | Este procedimento manual. |
| Troquei a `APP_MFA_ENCRYPTION_KEY` | Este procedimento manual (os segredos gravados deixam de decifrar). |

## Passos

Os comandos abaixo são para o banco local de desenvolvimento (PowerShell, a partir da pasta principal do projeto). Em homologação e produção, rode o mesmo SQL no banco do ambiente, pelo SQL Editor do projeto Supabase `sapiensia-homolog` (veja `docs/runbooks/homologacao-fly.md`), e reinicie a aplicação com `fly machine restart <id da máquina> -a sapiensia-homolog` (o id vem de `fly machine list -a sapiensia-homolog`).

1. Descubra o PID da API, para ela não recriar nada no meio do caminho.

   ```powershell
   Get-NetTCPConnection -LocalPort 8081 -State Listen | Select-Object OwningProcess
   ```

   Mostra o PID de quem escuta a porta da API.

2. Encerre a API pelo PID (nunca por nome).

   ```powershell
   Stop-Process -Id <PID>
   ```

   Troque `<PID>` pelo número do passo 1.

3. Confirme que o banco está ligado.

   ```powershell
   just db
   ```

   Sobe o PostgreSQL local, ou não faz nada se já estiver de pé.

4. Apague a linha do Administrador.

   ```powershell
   docker compose exec db psql -U app -d app -c "delete from administrators;"
   ```

   Apaga a conta; os links de senha e os códigos de recuperação saem junto, pelo `on delete cascade`.

5. Confirme que a tabela ficou vazia.

   ```powershell
   docker compose exec db psql -U app -d app -c "select count(*) from administrators;"
   ```

   Deve mostrar `0`.

6. Suba a API de novo.

   ```powershell
   just dev-back
   ```

   O bootstrap recria o Administrador a partir de `APP_ADMIN_EMAIL` e envia na hora o link para definir a senha.

7. Abra o e-mail do link e defina a senha nova em `/painel/definir-senha`. No local, o e-mail é um arquivo na caixa de saída (`app.email.outbox-dir`, padrão `../.tmp/outbox` a partir de `backend/`, ou seja, `.tmp/outbox` na pasta principal; `APP_EMAIL_OUTBOX_DIR` muda o caminho).

   ```powershell
   Get-ChildItem .tmp/outbox | Sort-Object LastWriteTime -Descending | Select-Object -First 1 | Get-Content
   ```

   Mostra o e-mail mais recente da caixa de saída, com o link.
8. Entre em `/painel/entrar` com a senha nova e refaça a inscrição do aplicativo autenticador (QR code ou chave).
9. Na tela seguinte, guarde os 10 códigos novos em um gerenciador de senhas, marque a caixa e continue.
10. Se você roda os testes de ponta a ponta, apague `.tmp/e2e/admin-password.txt` e `.tmp/e2e/admin-totp-secret.json` (guardam a senha e o segredo antigos) antes do próximo `just e2e`.

## O que muda com o #16

- Os códigos de recuperação ficam em `administrator_recovery_codes` com chave estrangeira para o Administrador: o passo 4 os apaga junto, e os códigos antigos nunca valem para a conta nova.
- Os registros de `audit_events` **não** são apagados: a tabela não tem chave estrangeira de propósito, para a trilha sobreviver a este procedimento. O ator antigo continua aparecendo pelo id dele.
- A sessão antiga cai sozinha, porque o id do Administrador deixa de existir.
- Esta troca em si não grava evento de auditoria (é feita no banco, fora da aplicação). Anote a data e o motivo em outro lugar.
