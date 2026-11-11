# Homologação no Fly.io (app `sapiensia-homolog`)

Passo a passo para colocar e manter a imagem Docker única (perfil `prod`) no ar como app de homologação, na região `gru`, ligada ao projeto Supabase `sapiensia-homolog`. Plano: `docs/superpowers/plans/2026-11-10-7-homologacao-fly.md`.

Legenda: **[AGENTE]** roda na sessão do Claude Code. **[RESPONSÁVEL]** você digita numa aba comum do PowerShell, na pasta do projeto, nunca no chat.

Regras que valem para tudo aqui:

- **Nenhum valor de segredo** (senha, chave, token, e-mail do Administrador) em chat, arquivo, commit, PR, issue ou log. Este documento só tem nomes.
- **A instalação do flyctl e todo comando `fly` só acontecem depois que o responsável autoriza o deploy.**
- Todo comando usa `-c fly.homolog.toml` (ou `-a sapiensia-homolog`) e todo deploy usa `--ha=false`: uma só máquina por ambiente, porque a sessão e os contadores de login ficam em memória (ADR-0004).

## Nomes dos segredos e das variáveis

### Segredos (Fly secrets, digitados pelo responsável)

| Nome | De onde vem o valor | Observação |
|---|---|---|
| `DB_URL` | Supabase `sapiensia-homolog` > Connect > Session pooler, montada sem usuário e sem senha (veja "Banco") | Sem ela a aplicação não sobe em `prod` |
| `DB_USER` | Supabase, mesmo painel (`postgres.<ref do projeto>`) | Sem ele a aplicação não sobe em `prod` |
| `DB_PASSWORD` | Supabase > Database, senha guardada no Bitwarden | Segredo de verdade; sem ela a aplicação não sobe em `prod` |
| `APP_MFA_ENCRYPTION_KEY` | Gerada pelo responsável (32 bytes em Base64), guardada no Bitwarden antes do `fly secrets set` | Trocar inutiliza o MFA gravado (ADR-0004); um backup restaurado precisa da mesma chave |
| `RESEND_API_KEY` | Resend > API Keys (chave só de envio, no Bitwarden) | Só da homologação; a produção terá outra (SEC-071) |
| `APP_ADMIN_EMAIL` | Escolha do responsável: a caixa que recebe o link de primeiro acesso | Dado pessoal: só no `fly secrets set`, nunca em arquivo |
| `APP_STORAGE_SUPABASE_URL` | Supabase `sapiensia-homolog` > configurações de API (endereço do projeto) | Arquivos dos Produtos (#18); sem ela a aplicação não sobe em `prod` |
| `APP_STORAGE_SUPABASE_SECRET_KEY` | Supabase > chaves de API (a *secret key*, no Bitwarden) | Segredo de verdade; vazia, a aplicação não sobe em `prod` |

### Variáveis comuns (`[env]` do `fly.homolog.toml`, versionadas)

| Nome | Observação |
|---|---|
| `APP_PUBLIC_BASE_URL` | Base dos links dos e-mails (endereço `fly.dev` do app) |
| `APP_EMAIL_FROM` | Remetente no domínio verificado no Resend da homologação |

`SPRING_PROFILES_ACTIVE`, `SERVER_PORT` e `JAVA_TOOL_OPTIONS` já vêm do `Dockerfile`: não redefina.

### Nunca defina

`APP_EMAIL_PROVIDER` (em `prod` o padrão é `resend`; `file` grava e-mail em disco), `APP_EMAIL_OUTBOX_DIR`, `SERVER_SERVLET_SESSION_COOKIE_SECURE`, `APP_LOGIN_LIMIT_IP_MAX_ATTEMPTS` e `DB_SCHEDULER_POLLING_INTERVAL` (são do CI de ponta a ponta).

## Passo zero: antes de copiar qualquer segredo

1. **[RESPONSÁVEL]** **Desligue o histórico da área de transferência do Windows** (Configurações > Sistema > Área de Transferência) antes de copiar qualquer segredo; depois dos segredos, religue-o se quiser. Limpar o histórico uma vez antes **não** protege o que for copiado depois, porque o histórico continua gravando. Alternativa, se preferir manter o histórico ligado: tecle Win+V e clique em "Limpar tudo" **de novo depois do último segredo** (e rode o `Set-Clipboard -Value $null` do passo 14).
2. **[RESPONSÁVEL]** No Bitwarden, ligue a limpeza automática da área de transferência: Configurações > Opções > "Limpar área de transferência", por exemplo 30 segundos.

## 1. Preparação

Só depois da autorização do deploy.

1. **[RESPONSÁVEL]** Instale o flyctl na versão `0.5.2`.

   ```powershell
   winget install --id Fly-io.flyctl -e --version 0.5.2
   ```

   Instala o flyctl pelo winget, fixando a versão. Regra: só versão com mais de 7 dias de publicada, a mesma das dependências. A `0.5.2` saiu em 02/11/2026; as `0.5.3` a `0.5.6` tinham menos de 7 dias em 10/11/2026. Antes de trocar de versão, confira a data de publicação nas releases do flyctl. Feche e abra o terminal depois.

2. **[AGENTE]** Confirme que o flyctl está no PATH.

   ```powershell
   fly version
   ```

   Mostra a versão instalada.

3. **[RESPONSÁVEL]** Entre na conta do Fly.io.

   ```powershell
   fly auth login
   ```

   Abre o navegador para o login (com 2FA).

4. **[AGENTE]** Confira a conta e a organização.

   ```powershell
   fly auth whoami
   ```

   Mostra a conta logada.

   ```powershell
   fly orgs list
   ```

   Lista as organizações; `sapiensia` deve aparecer.

## 2. Criar o app (sem máquina)

5. **[AGENTE]** Crie o app vazio.

   ```powershell
   fly apps create sapiensia-homolog --org sapiensia
   ```

   Cria o app sem máquina, então ainda sem custo. Se o nome não estiver livre, pare e escolha outro com o responsável.

6. **[AGENTE]** Confira os IPs.

   ```powershell
   fly ips list -a sapiensia-homolog
   ```

   Deve ter só IPv6 e IPv4 compartilhado (IPv4 dedicado custa à parte). Os IPs aparecem no primeiro deploy.

## 3. Banco (Supabase `sapiensia-homolog`, `sa-east-1`)

- **Conexão: pooler compartilhado em modo sessão** (Supavisor). Formato: host `aws-<n>-sa-east-1.pooler.supabase.com`, porta `5432`, usuário `postgres.<ref do projeto>`, `sslmode=require`.
  - Por quê: a conexão direta do Supabase é só IPv6 (IPv4 é complemento pago); o modo sessão funciona por IPv4. O modo transação (porta `6543`) não serve: não aceita prepared statements nem travas de sessão, e o Flyway usa trava de sessão.
  - `sslmode=require` cifra mas não confere o certificado. `verify-full` (certificado raiz do Supabase dentro da imagem) fica para o #47.
- **Formato do `DB_URL`, sem usuário e sem senha:** `jdbc:postgresql://aws-<n>-sa-east-1.pooler.supabase.com:5432/postgres?sslmode=require`. Copie host e `<n>` da tela Connect do Supabase.
- A string JDBC que o Supabase mostra traz um parâmetro de senha: **não** use essa. A senha vai só em `DB_PASSWORD`; o usuário vai só em `DB_USER`.
- **Migrations** rodam sozinhas na primeira subida (Flyway), antes de a aplicação aceitar tráfego. O schema `public` do projeto novo precisa estar sem tabelas (a função `rls_auto_enable` do Supabase pode ficar: o perfil `prod` faz o baseline na versão 0, #84). O log da primeira subida mostra `Successfully applied N migrations`; nas seguintes, `Successfully validated 15 migrations` (14 migrations mais a linha de baseline).
- **Bucket dos Arquivos do produto (#18):** o bucket privado `product-files` do Storage do mesmo projeto Supabase é criado à mão, antes do deploy. O passo a passo está em `docs/runbooks/bucket-arquivos-produto.md`; siga o que está lá (aqui não se repete).

## 4. Segredos (só o responsável)

Faça antes o "Passo zero". O padrão para cada segredo: copie o valor do Bitwarden (ele vai para a área de transferência) e rode o comando, que lê a área de transferência sem o valor aparecer na tela nem no histórico do PowerShell. `--stage` grava sem reiniciar nada.

7. **[RESPONSÁVEL]** Gere a chave do MFA e copie-a, sem mostrá-la.

   ```powershell
   $b = New-Object byte[] 32; [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b) | Set-Clipboard; $b = $null
   ```

   Gera 32 bytes aleatórios seguros, converte para Base64 e põe na área de transferência. Cole primeiro no Bitwarden e salve; só depois siga.

8. **[RESPONSÁVEL]** Grave a chave do MFA como segredo.

   ```powershell
   fly secrets set APP_MFA_ENCRYPTION_KEY="$(Get-Clipboard)" --stage -a sapiensia-homolog
   ```

   Grava o que está na área de transferência como segredo do app.

9. **[RESPONSÁVEL]** Copie `DB_PASSWORD` do Bitwarden e grave.

   ```powershell
   fly secrets set DB_PASSWORD="$(Get-Clipboard)" --stage -a sapiensia-homolog
   ```

   Mesmo padrão, com o nome do segredo trocado.

10. **[RESPONSÁVEL]** Copie `DB_URL` do Bitwarden e grave.

    ```powershell
    fly secrets set DB_URL="$(Get-Clipboard)" --stage -a sapiensia-homolog
    ```

    Grava a URL do banco (sem usuário e senha) como segredo do app.

11. **[RESPONSÁVEL]** Copie `DB_USER` do Bitwarden e grave.

    ```powershell
    fly secrets set DB_USER="$(Get-Clipboard)" --stage -a sapiensia-homolog
    ```

    Grava o usuário do banco como segredo do app.

12. **[RESPONSÁVEL]** Copie `RESEND_API_KEY` do Bitwarden e grave.

    ```powershell
    fly secrets set RESEND_API_KEY="$(Get-Clipboard)" --stage -a sapiensia-homolog
    ```

    Grava a chave de envio do Resend como segredo do app.

13. **[RESPONSÁVEL]** Copie a caixa de e-mail do Administrador de onde você a guarda e grave `APP_ADMIN_EMAIL`.

    ```powershell
    fly secrets set APP_ADMIN_EMAIL="$(Get-Clipboard)" --stage -a sapiensia-homolog
    ```

    Grava a caixa que recebe o link de primeiro acesso como segredo do app; o valor nunca entra em arquivo nem no chat.

13a. **[RESPONSÁVEL]** Copie `APP_STORAGE_SUPABASE_URL` (endereço do projeto, `https://<projeto>.supabase.co`) e grave.

    ```powershell
    fly secrets set APP_STORAGE_SUPABASE_URL="$(Get-Clipboard)" --stage -a sapiensia-homolog
    ```

    Grava o endereço do projeto Supabase como segredo do app.

13b. **[RESPONSÁVEL]** Copie `APP_STORAGE_SUPABASE_SECRET_KEY` do Bitwarden (a *secret key* `sb_secret_...`, não a publicável) e grave.

    ```powershell
    fly secrets set APP_STORAGE_SUPABASE_SECRET_KEY="$(Get-Clipboard)" --stage -a sapiensia-homolog
    ```

    Mesmo padrão; o valor nunca é digitado no comando, mostrado na tela nem colado no chat.

14. **[RESPONSÁVEL]** Limpe a área de transferência.

    ```powershell
    Set-Clipboard -Value $null
    ```

    Esvazia a área de transferência. Se o histórico do Windows ficou ligado durante os segredos, tecle também Win+V e clique em "Limpar tudo" agora; se foi desligado, religue-o se quiser.

15. **[AGENTE]** Confira os nomes.

    ```powershell
    fly secrets list -a sapiensia-homolog
    ```

    Mostra só nomes, digest e data. Devem estar os 8 nomes da tabela e nenhum a mais.

## 5. Imagem e deploy

16. **[AGENTE]** Gere a imagem.

    ```powershell
    just imagem
    ```

    Gera a imagem `app:local` com o `Dockerfile`, a mesma do CI.

17. **[AGENTE]** Valide o arquivo de configuração.

    ```powershell
    fly config validate -c fly.homolog.toml
    ```

    Confere o `fly.homolog.toml`; deve responder que a configuração é válida.

18. **[AGENTE]** Faça o deploy.

    ```powershell
    fly deploy -c fly.homolog.toml --image app:local --local-only --ha=false --wait-timeout 10m
    ```

    Envia a imagem local ao registro do Fly.io e cria **uma** máquina. O `--wait-timeout 10m` dá tempo à primeira subida, que no `shared-cpu-1x` pode passar de 60 s (o `grace_period` do Fly é limitado a 60 s). Sem `--ha=false` o Fly.io cria duas, e com duas as sessões e os limites se dividem. Se o flyctl não aceitar a imagem local, o caminho alternativo é `fly auth docker`, depois `docker tag app:local registry.fly.io/sapiensia-homolog:<sha>`, `docker push registry.fly.io/sapiensia-homolog:<sha>` e `fly deploy -c fly.homolog.toml --image registry.fly.io/sapiensia-homolog:<sha> --ha=false`.

19. **[AGENTE]** Leia o log da subida.

    ```powershell
    fly logs -a sapiensia-homolog --no-tail
    ```

    Procure `Successfully applied` (Flyway) e `Started`; confira que não há segredo nem e-mail no log.

## 6. Conferências

20. **[AGENTE]** Confira que há uma só máquina.

    ```powershell
    fly machine list -a sapiensia-homolog
    ```

    Exatamente uma máquina, região `gru`, tamanho `shared-cpu-1x:1024MB`.

21. **[AGENTE]** Confira o health check.

    ```powershell
    curl.exe -sS https://sapiensia-homolog.fly.dev/api/system/health
    ```

    Espera `{"status":"UP"}`.

    ```powershell
    fly checks list -a sapiensia-homolog
    ```

    O check deve estar `passing`.

22. **[AGENTE]** Confira o `Referrer-Policy`.

    ```powershell
    curl.exe -sS -D - -o NUL https://sapiensia-homolog.fly.dev/redefinir-senha
    ```

    Mostra os cabeçalhos de uma requisição GET (o `-I` faz HEAD, e o `Referrer-Policy` só vem no GET). Espera a linha `Referrer-Policy: no-referrer`. Repita em `/painel/definir-senha`; em `/entrar` o cabeçalho não deve aparecer.

## Fly-Client-IP ausente

O limite de tentativas de login por IP lê o IP do cliente do cabeçalho `Fly-Client-IP` (`app.login-limit.client-ip-header` no perfil `prod`), que o proxy do Fly.io define.

- **O que acontece se o cabeçalho faltar ou for inválido:** o sistema usa o endereço real da conexão (registrado antes do tratamento de cabeçalhos encaminhados do Spring). **Nunca** usa o `X-Forwarded-For`, que o cliente pode forjar.
- **Efeito no Fly.io:** o endereço da conexão é o do proxy, então todos os clientes passam a dividir um único limite de 20 tentativas de login por 15 minutos.
- **Como conferir:** a prova na homologação (plano, seção 5, e Tarefa 4): 21 logins com e-mails fictícios diferentes, cada um com `Fly-Client-IP` e `X-Forwarded-For` forjados e diferentes. Se o proxy sobrescreve o cabeçalho, todos contam no seu IP real e o 21º recebe `429`; se não sobrescrevesse, o 21º seria `401`. Se o 21º der `401`, pare e traga ao responsável antes de mudar código. Confirme também a origem gravada na auditoria: no SQL Editor do Supabase, `select origin, action, occurred_at from audit_events order by occurred_at desc limit 3;`, comparando `origin` com o seu IP. Atenção: a prova bloqueia o seu IP no login por 15 minutos. Dicas: use `curl.exe -4` em todas as chamadas (com IPv4 e IPv6 alternando, o seu IP vira dois baldes e o 21º pode dar `401`); no PowerShell 5.1 escape as aspas do JSON com `.Replace('"','\"')`, senão o corpo chega sem aspas e dá `400`, que não conta como tentativa; e rode a prova logo depois de um auto-stop, porque o reinício zera os contadores (que ficam em memória). Resultado esperado na homologação de 2026-11-11: 20 respostas `401` e o 21º `429`.

## Religar o Supabase pausado

O projeto gratuito é pausado depois de cerca de uma semana sem atividade; então a aplicação não sobe e o health check fica `DOWN`.

1. **[RESPONSÁVEL]** Abra o painel do Supabase, entre no projeto `sapiensia-homolog` e clique em restaurar (Restore project).
2. **[RESPONSÁVEL]** Espere o projeto ficar ativo e reinicie a máquina.

   ```powershell
   fly machine restart <id da máquina> -a sapiensia-homolog
   ```

   Reinicia a máquina; o id vem de `fly machine list -a sapiensia-homolog`.

## Custo

```powershell
fly dashboard -a sapiensia-homolog
```

Abre o painel no navegador; veja em Billing. Estimativa: cerca de US$ 1/mês com uso real de homologação; teto de cerca de US$ 11/mês se a máquina ficar ligada o mês inteiro. Robôs da internet batendo no `*.fly.dev` podem acordar a máquina e empurrar o custo para o teto. O período de teste do Fly.io (US$ 1, 7 dias) não cobre a homologação: o cartão é cadastrado pelo responsável no site.

## Desligar tudo

```powershell
fly scale count 0 -a sapiensia-homolog
```

Para todas as máquinas do app (os segredos e o app continuam). Para religar, `fly scale count 1 -a sapiensia-homolog`. A máquina também para sozinha depois de alguns minutos sem tráfego (`auto_stop_machines = "stop"`) e liga na próxima requisição; a primeira demora a JVM inteira e as sessões caem.

## O que não fazer

- Nunca cole segredo no chat, em arquivo, em commit, em PR, em issue nem em comando com o valor digitado.
- Nunca defina `APP_EMAIL_PROVIDER` nem as variáveis do CI listadas acima.
- Nunca use `X-Forwarded-For` como cabeçalho do IP do cliente.
- Nunca faça deploy sem `--ha=false`.
- Nunca instale uma versão do flyctl com menos de 7 dias de publicada.
