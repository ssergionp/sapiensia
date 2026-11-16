# Backup e restauração do banco (Cloudflare R2)

Atende NFR-007 e NFR-008 e põe em prática o ADR-0002. Duas vezes por dia, um workflow do GitHub Actions copia o banco para um bucket privado do Cloudflare R2, **já criptografado** com o `age`. A restauração usa o mesmo script nos três lugares (CI, workflow e este runbook): `scripts/backup/restore.sh`.

Legenda: **[AGENTE]** roda na sessão do Claude Code. **[RESPONSÁVEL]** você digita numa aba comum do PowerShell, na pasta do projeto, nunca no chat.

Regras que valem para tudo aqui:

- **Nenhum valor de segredo** (senha, chave, token) em chat, arquivo, commit, PR, issue ou log. Este documento só tem nomes e `<marcadores>`.
- Os valores ficam no Bitwarden. A **chave privada do age** (`AGE-SECRET-KEY-...`) nunca vai para o GitHub nem para o chat.
- Comandos de senha: **copie a senha do Bitwarden** e use `Get-Clipboard` no comando (`$env:NOME = Get-Clipboard`), em vez de digitar o valor. Assim a senha não fica no histórico do PowerShell (o que você digita fica gravado em `ConsoleHost_history.txt`; o que está na área de transferência, não). Apague a variável no fim (`Remove-Item Env:NOME`). Vale o cuidado de `docs/runbooks/homologacao-fly.md` ("Passo zero"): histórico da área de transferência do Windows desligado e limpeza automática no Bitwarden.

## O que existe

| Peça | O que é |
|---|---|
| Workflow `.github/workflows/backup.yml` | Roda às 04:17 e 16:17 de São Paulo (`cron: '17 7,19 * * *'`, em UTC) e quando você aperta *Run workflow*. Fora da hora cheia de propósito: é quando o GitHub mais atrasa. |
| `scripts/backup/backup.sh` | `pg_dump` (só o schema `public`) direto para o `age`; só o arquivo criptografado `.age` chega ao disco do runner e sobe ao R2. |
| `scripts/backup/restore.sh` | Descriptografa e restaura num banco de destino vazio, numa única transação. |
| `scripts/backup/age-recipients.txt` | A chave **pública** do age (`age1...`). Não é segredo: só serve para criptografar. |
| `scripts/backup/create-backup-role.sql` | Cria o papel `backup_reader` (só leitura) no banco. |
| Bucket `sapiensia-backup-homolog` | Privado, sem domínio público. Pastas: `daily/` (todo backup) e `monthly/` (o primeiro envio bem-sucedido de cada mês). |
| Teste de ida e volta no CI | A cada PR, `scripts/backup/roundtrip-test.sh` faz backup, restaura num banco novo e compara tabelas, contagens e sequências. Prova o mecanismo, não o bucket nem a sua chave. |

Retenção (ADR-0002): 30 dias de backups diários e os 3 últimos mensais. Quem apaga é o próprio R2, por regra de ciclo de vida; a trava do bucket impede apagar ou sobrescrever antes do prazo, mesmo com a credencial.

| Pasta | Trava (bucket lock) | Ciclo de vida (apaga) |
|---|---|---|
| `daily/` | 30 dias | 30 dias |
| `monthly/` | 93 dias | 93 dias |

Nome do arquivo: `daily/<AAAA-MM-DD>T<HHMM>Z.dump.age` (hora em UTC).

## Configuração inicial (uma vez, só você pode fazer)

Hoje só existe a homologação. A produção (#43) repete estes passos com bucket, papel e segredos próprios (SEC-071).

### 1. Cloudflare e o bucket

1. **[RESPONSÁVEL]** Crie a conta na Cloudflare (se não tiver) e ative o R2. A ativação costuma pedir uma forma de pagamento, mesmo dentro do plano gratuito (confirme na tela).
2. **[RESPONSÁVEL]** Em R2 > Create bucket: nome `sapiensia-backup-homolog`, localização automática, **sem** acesso público e **sem** domínio `r2.dev`.
3. **[RESPONSÁVEL]** Em Settings do bucket, crie quatro regras:
   - *Object lifecycle*: prefixo `daily/`, apagar depois de 30 dias; prefixo `monthly/`, apagar depois de 93 dias.
   - *Bucket lock*: prefixo `daily/`, 30 dias; prefixo `monthly/`, 93 dias.

   Por que 93 dias: três meses seguidos somam no máximo 92 dias, então ficam os 3 últimos mensais (por algumas horas após o primeiro envio do mês, 4). A trava vence antes da regra de apagar; o R2 remove em até cerca de 24 h depois do prazo.
4. **[RESPONSÁVEL]** Em R2 > Manage API tokens, crie um token **Object Read & Write**, restrito ao bucket `sapiensia-backup-homolog`. O R2 não tem permissão só de escrita; esta é a menor que grava. O que compensa: o que está no bucket é criptografado e a trava impede apagar. Guarde no Bitwarden o *Access Key ID*, o *Secret Access Key* e o *Account ID*.

### 2. Chave do age

1. **[RESPONSÁVEL]** Instale o age.

   ```powershell
   winget install FiloSottile.age
   ```

   Instala o programa `age`. Feche e abra o terminal depois.

2. **[RESPONSÁVEL]** Gere o par de chaves.

   ```powershell
   age-keygen -o sapiensia-backup-key.txt
   ```

   Cria o arquivo com a chave privada e mostra a pública (`age1...`) na tela.

3. **[RESPONSÁVEL]** Guarde o arquivo inteiro no Bitwarden **e** numa segunda cópia fora do computador (pendrive ou papel impresso, guardado em casa). Depois apague-o do disco.

   ```powershell
   Remove-Item sapiensia-backup-key.txt
   ```

   Apaga o arquivo da pasta atual.

4. **[RESPONSÁVEL]** Passe ao Claude **só** a linha pública (`age1...`). Ele a coloca em `scripts/backup/age-recipients.txt`.

**Atenção:** perder a chave privada inutiliza todos os backups. Não existe recuperação.

**Enquanto a chave pública não estiver no arquivo, o backup agendado falha** (o script para com erro em vez de subir sem criptografia) e o workflow abre a issue de falha (veja "O backup falhou"). É esperado até você mandar a linha `age1...`.

### 3. Papel de leitura no banco (`backup_reader`)

O backup não usa a senha do `postgres` da aplicação: usa um papel só de leitura. O arquivo `create-backup-role.sql` é um script do `psql` (usa `\getenv` e `\gexec`), então **não cole no SQL Editor do Supabase**. Rode-o pelo `psql` da imagem Docker (Docker Desktop aberto).

1. **[RESPONSÁVEL]** No SQL Editor do Supabase `sapiensia-homolog`, descubra a versão do PostgreSQL.

   ```sql
   show server_version;
   ```

   Mostra a versão do servidor. Anote o número maior (por exemplo, `17` em `17.4`): é o `PG_MAJOR`. Me diga o número.

2. **[RESPONSÁVEL]** Gere no Bitwarden uma senha nova para o `backup_reader` e coloque nas variáveis de ambiente, junto com a senha do `postgres` do projeto. Copie cada senha do Bitwarden logo antes do comando dela.

   ```powershell
   $env:BACKUP_ROLE_PASSWORD = Get-Clipboard
   ```

   Guarda a senha nova (a que está na área de transferência) só nesta janela do terminal.

   ```powershell
   $env:PGPASSWORD = Get-Clipboard
   ```

   Guarda a senha do `postgres` (copiada do Bitwarden) só nesta janela, para o `psql` entrar.

3. **[RESPONSÁVEL]** Rode o script contra o pooler em modo sessão (o host e o `<ref>` estão em Supabase > Connect > Session pooler).

   ```powershell
   Get-Content scripts/backup/create-backup-role.sql | docker run --rm -i -e PGPASSWORD -e BACKUP_ROLE_PASSWORD postgres:<PG_MAJOR> psql "host=<host do pooler> port=5432 dbname=postgres user=postgres.<ref do projeto> sslmode=require" -f -
   ```

   Manda o script para o `psql` dentro de um contêiner, que cria o papel `backup_reader` (só leitura, com `bypassrls`) e dá `select` nas tabelas do `public`. Pode ser repetido: só refaz a senha e as permissões. A senha do `backup_reader` viaja dentro do comando `ALTER ROLE` e pode aparecer nos logs de comandos do Supabase; o papel só lê, e a senha pode ser trocada rodando o script de novo.

4. **[RESPONSÁVEL]** Apague as senhas da janela.

   ```powershell
   Remove-Item Env:BACKUP_ROLE_PASSWORD
   ```

   Remove a senha do `backup_reader` do ambiente.

   ```powershell
   Remove-Item Env:PGPASSWORD
   ```

   Remove a senha do `postgres` do ambiente.

**Se o comando falhar citando `bypassrls` ou permissão, pare e avise o Claude.** O Supabase pode não deixar criar esse papel; o plano B (usar o usuário `postgres` no backup) só se decide com registro no ADR-0002.

### 4. Segredos no GitHub

Em Settings > Secrets and variables > Actions do repositório. São segredos e variável **do repositório** com o prefixo `HOMOLOG_` (não há *Environment*: ambiente em repositório privado depende do plano; na produção, #43, os nomes ganham o prefixo `PROD_`).

| Nome | Tipo | O que é |
|---|---|---|
| `HOMOLOG_BACKUP_DB_HOST` | Secret | Host do pooler em modo sessão (o mesmo do `DB_URL` da homologação) |
| `HOMOLOG_BACKUP_DB_USER` | Secret | `backup_reader.<ref do projeto>` |
| `HOMOLOG_BACKUP_DB_PASSWORD` | Secret | Senha do `backup_reader` (a do passo 3) |
| `HOMOLOG_R2_ACCOUNT_ID` | Secret | Account ID da Cloudflare |
| `HOMOLOG_R2_ACCESS_KEY_ID` | Secret | Access Key ID do token do R2 |
| `HOMOLOG_R2_SECRET_ACCESS_KEY` | Secret | Secret Access Key do token do R2 |
| `HOMOLOG_R2_BUCKET` | Secret | `sapiensia-backup-homolog` |
| `HOMOLOG_PG_MAJOR` | Variável (*Variables*) | A versão maior do PostgreSQL do passo 3 |

A conexão é pelo pooler porque o runner do GitHub não tem IPv6 e a conexão direta do Supabase é só IPv6.

### 5. Notificações

**[RESPONSÁVEL]** Em github.com/settings/notifications, deixe ligado o e-mail de Actions para workflows que falham.

### 6. Primeira execução

Depois do merge (o `schedule` e o botão só funcionam com o workflow já na `main`): em Actions > Backup > *Run workflow*. Confira que o arquivo apareceu em `daily/` no bucket. Esta é a primeira vez que o envio ao R2 é provado de verdade.

## O backup falhou

Quando uma execução falha, o workflow abre (ou comenta, se já houver uma aberta) uma issue com o rótulo `backup-failure`, e o GitHub avisa por e-mail. O texto só traz data, ambiente e o link da execução.

1. Abra o link da execução e leia o passo que ficou vermelho. A mensagem do script começa com `backup.sh:` e não traz dado do banco.
2. Causas comuns:
   - **Chave do age ainda não configurada** (`age-recipients.txt` vazio): mande a linha `age1...` ao Claude.
   - **Senha do banco trocada ou projeto Supabase pausado**: religue o projeto (veja `docs/runbooks/homologacao-fly.md`, "Religar o Supabase pausado") e confira `HOMOLOG_BACKUP_DB_PASSWORD`.
   - **Versão do PostgreSQL mudou**: o script para se `HOMOLOG_PG_MAJOR` não bater com o servidor. Rode `show server_version;` e atualize a variável.
   - **Token do R2 expirado ou apagado**: crie outro e atualize os segredos `HOMOLOG_R2_*`.
3. Corrija e rode *Run workflow* à mão.
4. **Só feche a issue depois de uma execução verde.** Enquanto ela estiver aberta, novas falhas viram comentários nela.

O que nenhum alerta pega: uma execução que o GitHub **descartou** (sob carga alta) ou um workflow desligado. Não há falha, então não há aviso. Por isso são duas execuções por dia: se uma sumir, o intervalo sem backup fica em 24 h, ainda dentro do RPO. Se o GitHub desligar o agendamento, só o exercício periódico abaixo percebe.

## Restaurar um backup

Use para o desastre de verdade e para o exercício periódico. Um banco restaurado **não** recupera o que aconteceu depois do último backup (RPO de até 24 h, ADR-0002).

### O que você precisa

- A chave privada do age (Bitwarden ou a segunda cópia).
- Os segredos do Fly que estão no Bitwarden. Sem a `APP_MFA_ENCRYPTION_KEY` original o MFA gravado não funciona (ADR-0004).
- No computador: Docker Desktop aberto, Git para Windows e o `age` no PATH (`winget install FiloSottile.age`).
- Um projeto Supabase **novo e vazio**, na mesma versão do PostgreSQL (o `PG_MAJOR`).

### Passos

1. **[RESPONSÁVEL]** Baixe o `.age` mais recente. No painel da Cloudflare: R2 > `sapiensia-backup-homolog` > `daily/` > escolha o arquivo de nome com a data mais nova > Download. A credencial do backup não fica no computador. Anote a data e a hora do arquivo (o RPO observado é a hora da decisão menos essa hora).
2. **[RESPONSÁVEL]** Crie o projeto Supabase novo (na homologação, `sapiensia-restore-test`; o plano Free permite 2 projetos ativos, então faça isso **antes** de criar o de produção). Copie do Connect o host do Session pooler e o `<ref>`.
3. **[RESPONSÁVEL]** Guarde a chave privada num arquivo temporário (por exemplo, em `$env:TEMP`) e guarde o destino nas variáveis de ambiente.

   ```powershell
   $env:PGHOST = "<host do pooler do projeto novo>"
   ```

   Diz ao script em qual servidor restaurar.

   ```powershell
   $env:PGPORT = "5432"
   ```

   Porta do pooler em modo sessão.

   ```powershell
   $env:PGUSER = "postgres.<ref do projeto novo>"
   ```

   Usuário do projeto novo.

   ```powershell
   $env:PGPASSWORD = Get-Clipboard
   ```

   Copie antes a senha do projeto novo do Bitwarden; ela fica só nesta janela.

   ```powershell
   $env:PGDATABASE = "postgres"
   ```

   Nome do banco do projeto novo.

   ```powershell
   $env:PG_MAJOR = "<versão maior do PostgreSQL>"
   ```

   Versão da imagem `postgres:<major>` que o script usa (igual à do servidor).

   ```powershell
   $env:PGSSLMODE = "require"
   ```

   Obriga a conexão com o banco novo a ser criptografada (TLS); sem isso o `psql` aceitaria uma conexão aberta.

4. **[RESPONSÁVEL]** Restaure.

   ```powershell
   just backup-restaurar <caminho do arquivo.age> <caminho da chave privada.txt>
   ```

   Descriptografa numa pasta temporária, roda o `pg_restore` pela imagem `postgres:<major>` numa só transação (se algo falhar, o banco novo fica como estava) e apaga o texto aberto no fim, mesmo se der erro (não se o processo for encerrado à força; nesse caso apague a pasta temporária que o script criou, uma `tmp.XXXXXX` dentro da pasta temporária do sistema, em `$env:TEMP`). Com a chave errada, para antes de tocar o banco.

5. **[RESPONSÁVEL]** Confira. O script imprime a lista de tabelas com a contagem exata de cada uma: compare com o que você espera. Depois confira a última versão do Flyway.

   ```sql
   select version from flyway_schema_history where success order by installed_rank desc limit 1;
   ```

   No SQL Editor do projeto novo, mostra a última migration aplicada; deve ser a mais nova do repositório.

6. **[RESPONSÁVEL]** Apague a chave privada temporária e as variáveis.

   ```powershell
   Remove-Item <caminho da chave privada.txt>
   ```

   Apaga o arquivo temporário da chave.

   ```powershell
   Remove-Item Env:PGPASSWORD
   ```

   Remove a senha do ambiente. Faça o mesmo para `PGHOST`, `PGPORT`, `PGUSER`, `PGDATABASE`, `PG_MAJOR` e `PGSSLMODE`.

7. **[RESPONSÁVEL]** Aponte a aplicação para o banco novo, trocando `DB_URL`, `DB_USER` e `DB_PASSWORD` (formato em `docs/runbooks/homologacao-fly.md`, seção 3).

   Para cada segredo, copie o valor do Bitwarden (ou do Connect do Supabase) e rode o comando dele.

   ```powershell
   fly secrets set DB_URL="$(Get-Clipboard)" --stage -c fly.homolog.toml
   ```

   Grava o que está na área de transferência como `DB_URL` (o JDBC do pooler novo), sem reiniciar ainda.

   ```powershell
   fly secrets set DB_USER="$(Get-Clipboard)" --stage -c fly.homolog.toml
   ```

   Mesmo padrão para `DB_USER` (`postgres.<ref do projeto novo>`).

   ```powershell
   fly secrets set DB_PASSWORD="$(Get-Clipboard)" --stage -c fly.homolog.toml
   ```

   Mesmo padrão para `DB_PASSWORD` (a senha do projeto novo).

   ```powershell
   fly secrets deploy -c fly.homolog.toml
   ```

   Aplica os três segredos de uma vez e reinicia a máquina. Confira o health check (`/api/system/health`) e um login do Administrador com MFA.

8. **Depois de restaurar:**
   - refaça as **anonimizações** pedidas depois da data do backup (`docs/runbooks/anonimizar-conta-cliente.md`): o backup guarda o Cliente como era antes;
   - rode a conciliação para os Pedidos perdidos no intervalo (`docs/runbooks/conciliacao-asaas.md`);
   - recrie o papel `backup_reader` no banco novo (seção 3 da configuração) e atualize os segredos `HOMOLOG_BACKUP_DB_*` e `HOMOLOG_PG_MAJOR`.

## Exercício de restauração

O CI prova o mecanismo a cada PR, mas só você consegue abrir um backup real (a chave privada não está no GitHub). Repita a prova **a cada 3 meses e a cada troca de versão do PostgreSQL**, com cronômetro, e anote na tabela. Meta: RTO até 1 dia e RPO até 24 h.

Roteiro (na homologação, onde os dados são fictícios):

| Etapa | Quem |
|---|---|
| T0: decisão de restaurar; anotar a hora do último backup (RPO = T0 menos essa hora) | Você |
| Baixar o `.age` mais recente | Você |
| Criar o projeto Supabase `sapiensia-restore-test` | Você |
| `just backup-restaurar` e conferência das contagens | Você, com o Claude acompanhando |
| Apontar o Fly da homologação para o banco novo, health check, login do Administrador com MFA, abrir a Biblioteca de um Cliente de teste e baixar um PDF | Você |
| Voltar o Fly para o banco original e apagar o projeto de teste | Você (fora do RTO) |

### Exercícios feitos

| Data | Ambiente | Backup usado | Tempo de cada etapa | RPO observado | RTO total | Resultado |
|---|---|---|---|---|---|---|
| | | | | | | |

## O que este backup não cobre

- **Os PDFs e as capas dos Produtos** no Supabase Storage. Banco e Storage moram no mesmo projeto Supabase: perder o projeto perde os dois. Isso foi registrado como pendência separada, ainda sem solução.
- Pedidos e cadastros feitos depois do último backup (até 24 h).
- A chave privada do age: se for perdida, nenhum backup abre.
- Os schemas do próprio Supabase (`auth`, `storage`...): a aplicação não os usa e eles não restauram limpo em outro projeto.
- Dados de Cliente anonimizado continuam nos backups por até 93 dias (a trava impede apagar antes). Por isso, depois de restaurar, refaça as anonimizações recentes.
