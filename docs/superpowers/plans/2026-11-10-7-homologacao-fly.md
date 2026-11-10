# Homologação no Fly.io (#7) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Colocar a imagem Docker única (perfil `prod`) no ar como o app de homologação `sapiensia-homolog` no Fly.io (região `gru`), ligado ao Supabase `sapiensia-homolog`, com segredos só no Fly.io, desligamento automático e os critérios de #56, #12 e MFA provados na homologação.

**Architecture:** Nenhuma mudança de arquitetura: a mesma imagem do `just imagem` sobe com `SPRING_PROFILES_ACTIVE=prod`; o Fly.io injeta os segredos como variáveis de ambiente; o banco é o Postgres do Supabase em São Paulo pelo pooler em modo sessão (IPv4, aceita prepared statements e a trava de sessão do Flyway). Código muda pouco: o perfil `prod` passa a falhar ao subir sem as variáveis do banco e liga o `Fly-Client-IP`; o resto é configuração (`fly.homolog.toml`) e um runbook.

**Tech Stack:** flyctl, Fly Machines, Supabase Postgres (Supavisor, modo sessão), Spring Boot 4.1 (perfil `prod`), Flyway, PowerShell.

**Spec:** issue #7 (com os 3 comentários), #56, #12; `docs/adr/0001`, `0002`, `0004`, `0006`; `docs/spec/sapiensia-spec.md` (SEC-022, SEC-071).

## Global Constraints

- Nenhum valor de segredo em chat, log, arquivo, commit, PR ou issue. Só nomes. O agente nunca lê `.env` nem roda `fly secrets` com valor.
- Os `fly secrets set` são digitados pelo responsável numa aba comum do PowerShell, colando do Bitwarden pela área de transferência.
- Não tocar no pacote `product`, em Storage nem em `db/migration` (o #18 está em outra worktree).
- Uma só máquina por ambiente (ADR-0004: sessão e contadores de login em memória). Todo `fly deploy` usa `--ha=false`.
- Região `gru`; app `sapiensia-homolog` na organização `sapiensia`.
- Nunca `X-Forwarded-For` como cabeçalho do IP do cliente (#56).
- Push, PR e merge só com o OK do responsável.
- Subagentes sempre com `model: sonnet`.

## Review Focus

1. **Cabeçalho forjado pelo cliente:** `Fly-Client-IP` ou `X-Forwarded-For` falsos não podem mudar o IP que o limite de login e a auditoria enxergam. Coberto pela Tarefa 4, passo 4 (prova na homologação).
2. **Variável do banco ausente em prod:** sem `DB_URL`, `DB_USER` ou `DB_PASSWORD`, a aplicação deve parar ao subir, nunca tentar `localhost` com a senha `app`. Coberto pela Tarefa 1.
3. **Partida a frio depois do desligamento automático:** a primeira requisição depois de a máquina parar precisa ser atendida (sem 502), mesmo com a JVM e o Flyway subindo. Coberto pela Tarefa 4, passo 5 (tempo medido).
4. **Remetente de e-mail fora do domínio verificado:** o padrão `nao-responda@sapiensia.app.br` não está verificado no Resend (só `homolog.sapiensia.app.br`); sem `APP_EMAIL_FROM` o link do primeiro acesso do Administrador não chega. Coberto pela Tarefa 2 (`[env]`) e Tarefa 4, passo 6.
5. **Segunda máquina criada pelo `fly deploy`:** o padrão do Fly.io cria 2 máquinas; com duas, sessões e limites se dividem. Coberto pela Tarefa 3 (`--ha=false`) e Tarefa 4, passo 1 (`fly machine list` com 1 linha).

---

## 1. Inventário dos segredos e variáveis do perfil `prod`

Levantado de `application.yml`, `application-prod.yml`, `Dockerfile`, `ci.yml` e dos `@Value`/`@ConfigurationProperties` do código na `main` (08229b0). Só nomes.

### Segredos (Fly secrets, digitados pelo responsável)

| Nome | Obrigatória | De onde vem o valor | Observação |
|---|---|---|---|
| `DB_URL` | Sim (hoje tem padrão perigoso) | Supabase `sapiensia-homolog` > Connect > Session pooler (montada **sem** usuário e senha, ver seção 3) | Não é segredo forte, mas fica fora do git para o arquivo do Fly.io valer para qualquer ambiente |
| `DB_USER` | Sim (hoje tem padrão perigoso) | Supabase, mesmo painel (`postgres.<ref do projeto>`) | idem |
| `DB_PASSWORD` | Sim (hoje tem padrão perigoso) | Supabase > Database > senha do banco, guardada no Bitwarden | Segredo de verdade |
| `APP_MFA_ENCRYPTION_KEY` | Sim (sem ela não sobe) | Gerada pelo responsável no PowerShell (32 bytes aleatórios em Base64), guardada no Bitwarden **antes** do `fly secrets set` | Trocar inutiliza o MFA gravado (ADR-0004); um backup restaurado precisa da mesma chave |
| `RESEND_API_KEY` | Sim (sem ela não sobe) | Resend > API Keys (chave só de envio, já no Bitwarden) | Só da homologação; produção terá outra (SEC-071) |
| `APP_ADMIN_EMAIL` | Sim (sem ela não sobe em `prod`) | Escolha do responsável: a caixa que recebe o link de primeiro acesso | Dado pessoal: segredo, nunca no `fly.homolog.toml` |

### Variáveis comuns (`[env]` do `fly.homolog.toml`, versionadas, sem segredo)

| Nome | Obrigatória | Valor | Observação |
|---|---|---|---|
| `APP_PUBLIC_BASE_URL` | Sim (sem ela não sobe) | `https://sapiensia-homolog.fly.dev` (decisão 6.4) | Base dos links dos e-mails |
| `APP_EMAIL_FROM` | Na prática, sim | `SapiensIA <nao-responda@homolog.sapiensia.app.br>` (decisão 6.5) | O padrão `@sapiensia.app.br` não está verificado no Resend |
| `SPRING_PROFILES_ACTIVE`, `SERVER_PORT`, `JAVA_TOOL_OPTIONS` | Já vêm do `Dockerfile` | `prod`, `8080`, `-XX:MaxRAMPercentage=75` | Não redefinir |

### Nunca definir na homologação nem na produção

`APP_EMAIL_PROVIDER` (o padrão `prod` é `resend`; `file` grava e-mail em disco), `APP_EMAIL_OUTBOX_DIR`, `SERVER_SERVLET_SESSION_COOKIE_SECURE`, `APP_LOGIN_LIMIT_IP_MAX_ATTEMPTS` e `DB_SCHEDULER_POLLING_INTERVAL` (todos do CI e2e).

### Padrões perigosos em prod, hoje

1. **`DB_URL`, `DB_USER`, `DB_PASSWORD`** têm padrão no `application.yml` (`localhost:5433`, usuário `app`, senha `app`). Faltando o segredo, a aplicação tenta o banco local com uma senha versionada em vez de parar com erro claro. **Tarefa 1** remove o padrão no perfil `prod`.
2. **`app.login-limit.client-ip-header`** está vazio: atrás do proxy todos dividem um limite de 20 tentativas (#56). E, com `server.forward-headers-strategy: framework`, o endereço de fallback (`getRemoteAddr`) vem do `X-Forwarded-For`, cujo primeiro valor é escrito pelo cliente: hoje o limite por IP é forjável. **Tarefa 1** liga `Fly-Client-IP`; a **Tarefa 4** prova que nenhum dos dois cabeçalhos forjados passa.
3. **`app.email.from`** aponta para um domínio não verificado no Resend da homologação (não é falha de segurança, mas quebra o primeiro acesso). **Tarefa 2** define `APP_EMAIL_FROM`.
4. **`RESEND_API_KEY:`** tem padrão vazio, mas a aplicação recusa subir com ele vazio: seguro.

### Fora deste ticket (o código da `main` ainda não usa)

Supabase Storage (entra com o #18: os nomes virão do plano dele e serão acrescentados ao runbook depois do merge), Cloudflare R2 (backup, #47) e Asaas sandbox (pagamento). Não cadastrar segredo que o código não usa (escopo mínimo, SEC-022).

## 2. Arquivo do Fly.io (`fly.homolog.toml`)

Conteúdo que a Tarefa 2 cria (nome com o ambiente, porque a produção terá o seu; todo comando usa `-c fly.homolog.toml`):

```toml
app = "sapiensia-homolog"
primary_region = "gru"

[env]
  APP_PUBLIC_BASE_URL = "https://sapiensia-homolog.fly.dev"
  APP_EMAIL_FROM = "SapiensIA <nao-responda@homolog.sapiensia.app.br>"

[http_service]
  internal_port = 8080
  force_https = true
  auto_stop_machines = "stop"
  auto_start_machines = true
  min_machines_running = 0

  [[http_service.checks]]
    method = "GET"
    path = "/api/system/health"
    interval = "30s"
    timeout = "5s"
    grace_period = "90s"

[[vm]]
  size = "shared-cpu-1x"
  memory = "1gb"
```

- **Desligamento automático:** `auto_stop_machines = "stop"` + `min_machines_running = 0`: o proxy para a máquina depois de alguns minutos sem tráfego e liga de novo na próxima requisição. Escolhi `stop` e não `suspend` porque é o comportamento documentado sem pré-requisitos; `suspend` religaria em milissegundos e manteria as sessões, mas tem requisitos próprios que não conferi (fica como melhoria).
- **`grace_period = "90s"`:** a JVM com Flyway numa CPU compartilhada demora; sem folga o Fly.io marcaria a máquina como doente na subida.
- **Máquina:** `shared-cpu-1x` com 1 GB. Com 512 MB, o heap fica em ~384 MB (75 %) para Spring Boot, Hibernate, Flyway e db-scheduler: apertado, risco de OOM. 1 GB é a menor opção segura.
- **Custo estimado** (tabela oficial: shared-cpu-1x 1 GB = US$ 6,70/mês em Ashburn; multiplicador de `gru` = 1,615; disco parado US$ 0,15/GB/mês; IPv4 compartilhado grátis):
  - ligada o mês inteiro: ~US$ 10,82/mês (teto, se nunca desligar);
  - uso real de homologação (~40 h/mês ligada): ~US$ 0,60 de máquina + ~US$ 0,15 de disco ≈ **US$ 1/mês**;
  - robôs da internet batendo no `*.fly.dev` podem acordar a máquina e empurrar o custo para perto do teto.
- **Período de teste:** **não cobre.** É de US$ 1 de crédito por 7 dias (conta criada em 10/11/2026, então acaba por volta de 17/11/2026). Para a homologação ficar no ar depois disso, o Fly.io exige cartão. O Fly.io perdoa faturas abaixo de US$ 5, mas a documentação de preços fala em organização pessoal e a `sapiensia` não é: tratar o perdão como incerto (decisão 6.1).

## 3. Banco (Supabase `sapiensia-homolog`, `sa-east-1`)

- **Conexão: pooler compartilhado em modo sessão** (Supavisor), host `aws-<n>-sa-east-1.pooler.supabase.com`, porta `5432`, usuário `postgres.<ref>`, `sslmode=require`.
  - Por quê: a conexão direta do Supabase é só IPv6 (IPv4 é complemento pago), e o modo sessão funciona por IPv4 em qualquer plano. O modo transação (porta 6543) não serve: não aceita prepared statements nem travas de sessão, e o Flyway usa trava de sessão.
  - `sslmode=require` cifra mas não confere o certificado; `verify-full` exige embutir o certificado raiz do Supabase na imagem. Fica `require` agora, com `verify-full` como item do #47 (decisão 6.6).
- **Formato do `DB_URL`** (sem usuário e sem senha): `jdbc:postgresql://aws-<n>-sa-east-1.pooler.supabase.com:5432/postgres?sslmode=require`. O responsável copia host e `<n>` da tela Connect do Supabase. A string JDBC que o Supabase mostra traz `password=[YOUR-PASSWORD]`: **não** usar essa; a senha vai só em `DB_PASSWORD`.
- **Senha:** só no Bitwarden e no `fly secrets`. Nunca em `fly.homolog.toml`, runbook, comando com valor digitado, log ou chat.
- **Migrations:** rodam sozinhas na primeira subida (`spring.flyway.enabled: true`), antes de a aplicação aceitar tráfego; o health check só responde depois. Pré-condições:
  - o schema `public` do projeto novo precisa estar vazio de tabelas (o Flyway recusa schema não vazio sem histórico); a conferência é a primeira subida dar certo, e o log mostra `Successfully applied N migrations`;
  - as tabelas nascem com o dono `postgres`, o mesmo usuário da aplicação: o RLS automático do Supabase não bloqueia o dono, e a Data API está desligada. Se um dia a aplicação usar outro papel, o RLS sem política bloqueia tudo (decisão 6.7).
  - nenhuma migration usa `create extension` (conferido), então nada depende de extensão do Supabase.
- **Pausa do Supabase gratuito:** projeto parado após 1 semana sem atividade; religar pelo painel. Registrar no runbook.

## 4. Roteiro de comandos (PowerShell, na ordem)

Legenda: **[AGENTE]** roda nesta sessão · **[RESPONSÁVEL]** você digita numa aba comum do PowerShell, na pasta da worktree, nunca neste chat.

### 4.1 Preparação
1. **[RESPONSÁVEL]** `winget install --id Fly-io.flyctl -e` — instala o flyctl (confirmo o ID do pacote antes, com `winget search flyctl`). Feche e abra o terminal.
2. **[AGENTE]** `fly version` — confirma que o flyctl está no PATH.
3. **[RESPONSÁVEL]** `fly auth login` — abre o navegador para entrar na conta do Fly.io (com a 2FA).
4. **[AGENTE]** `fly auth whoami` — mostra a conta logada (só o e-mail da conta).
5. **[AGENTE]** `fly orgs list` — confere que a organização `sapiensia` aparece.

### 4.2 Criar o app (sem máquina)
6. **[AGENTE]** `fly apps create sapiensia-homolog --org sapiensia` — cria o app vazio; ainda não liga máquina nem gera custo.
7. **[AGENTE]** `fly ips list -a sapiensia-homolog` — deve mostrar só IPv6 e IPv4 compartilhado (sem IPv4 dedicado, que custa US$ 2/mês). Os IPs aparecem no primeiro deploy.

### 4.3 Segredos (só o responsável)
Padrão para cada segredo: copiar do Bitwarden (o valor vai para a área de transferência) e rodar o comando, que lê a área de transferência sem o valor aparecer na tela nem no histórico do PowerShell. `--stage` grava sem reiniciar nada.

8. **[RESPONSÁVEL]** gerar a chave do MFA e copiá-la (não aparece na tela):
   ```powershell
   $b = New-Object byte[] 32; [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b) | Set-Clipboard; $b = $null
   ```
   Gera 32 bytes aleatórios seguros, converte para Base64 e põe na área de transferência. **Cole primeiro no Bitwarden e salve**, só depois siga.
9. **[RESPONSÁVEL]** `fly secrets set APP_MFA_ENCRYPTION_KEY="$(Get-Clipboard)" --stage -a sapiensia-homolog` — grava a chave como segredo do app.
10. **[RESPONSÁVEL]** copie `DB_PASSWORD` do Bitwarden e rode `fly secrets set DB_PASSWORD="$(Get-Clipboard)" --stage -a sapiensia-homolog`.
11. **[RESPONSÁVEL]** repita o passo 10 para `DB_URL`, `DB_USER`, `RESEND_API_KEY` e `APP_ADMIN_EMAIL` (um comando por segredo, trocando o nome).
12. **[RESPONSÁVEL]** `Set-Clipboard -Value $null` — limpa a área de transferência.
13. **[AGENTE]** `fly secrets list -a sapiensia-homolog` — mostra só nomes, digest e data; confiro que os 6 nomes da seção 1 estão lá e nenhum a mais.

### 4.4 Imagem e deploy
14. **[AGENTE]** `just imagem` — gera a imagem `app:local` com o `Dockerfile` (a mesma do CI).
15. **[AGENTE]** `fly deploy -c fly.homolog.toml --image app:local --local-only --ha=false` — envia a imagem local ao registro do Fly.io e cria **uma** máquina. Se o flyctl não aceitar a imagem local, o caminho alternativo é: `fly auth docker` (autoriza o Docker no registro do Fly.io), `docker tag app:local registry.fly.io/sapiensia-homolog:<sha>`, `docker push registry.fly.io/sapiensia-homolog:<sha>` e `fly deploy -c fly.homolog.toml --image registry.fly.io/sapiensia-homolog:<sha> --ha=false`.
16. **[AGENTE]** `fly logs -a sapiensia-homolog --no-tail` — confiro `Successfully applied` do Flyway e `Started`, e que não há segredo nem e-mail no log.
17. **[AGENTE]** `fly machine list -a sapiensia-homolog` — exatamente uma máquina, região `gru`, tamanho `shared-cpu-1x:1024MB`.

### 4.5 Conferências
18. **[AGENTE]** `curl.exe -sS https://sapiensia-homolog.fly.dev/api/system/health` — espera `{"status":"UP"}`.
19. **[AGENTE]** `curl.exe -sSI https://sapiensia-homolog.fly.dev/redefinir-senha` — espera a linha `Referrer-Policy: no-referrer`.
20. Os demais passos de verificação estão na seção 5 e na Tarefa 4.

## 5. Critérios da issue: como cada um é provado

| Critério | Como verifico | Prova que vai para a PR |
|---|---|---|
| Imagem única com perfil `prod` e health check | Passos 14 a 18; `fly checks list -a sapiensia-homolog` mostra o check `passing` | saída do `curl` com `UP` e do `fly checks list` |
| Segredos no Fly.io, separados por ambiente, fora do repositório | `fly secrets list` (só nomes); `git grep` pelos nomes mostra só referências `${...}` e o runbook; gitleaks do CI verde | lista de nomes + CI verde |
| Desliga sozinha quando ociosa | 15 min sem tráfego, `fly machine list` mostra `stopped`; `fly logs` mostra a parada pelo proxy; em seguida `Measure-Command { curl.exe -sS .../api/system/health }` religa e mede a partida a frio | as duas saídas e o tempo medido |
| `Fly-Client-IP` (#56), com o proxy sobrescrevendo | (a) teste automático do perfil `prod` (Tarefa 1) falha se o cabeçalho estiver vazio; (b) na homologação, script de 21 logins do Cliente com e-mails fictícios diferentes, cada um com `Fly-Client-IP` **e** `X-Forwarded-For` forjados e diferentes: se o proxy sobrescreve, os 21 contam no meu IP real e o 21º recebe `429`; se não sobrescrevesse, cada um teria IP próprio e o 21º seria `401`; (c) o responsável, do celular em 4G (outro IP), tenta um login e recebe `401`, não `429`; (d) o responsável entra no painel e roda no SQL Editor do Supabase `select origin, action, occurred_at from audit_events order by occurred_at desc limit 3;` e compara `origin` com o IP de `curl.exe -s https://api.ipify.org` | saída do script (status por tentativa), relato do celular, resultado da consulta (o IP do próprio responsável, que ele decide se aparece na PR) |
| `Referrer-Policy` em `/redefinir-senha` na imagem de produção (#12) | Passo 19 na homologação; o mesmo `curl -I` em `/painel/definir-senha`; `/entrar` sem o cabeçalho | as três saídas do `curl -I` |
| MFA: `APP_MFA_ENCRYPTION_KEY` como segredo, sem padrão em prod, e primeiro acesso cadastrando o autenticador | (a) `fly secrets list` mostra o nome; (b) `application.yml` e `application-prod.yml` sem valor padrão (só `application-local.yml` tem); (c) o responsável recebe o e-mail de primeiro acesso, define a senha em `/painel/definir-senha`, entra em `/painel/entrar` e a tela pede o QR code antes de abrir o painel; depois roda `select totp_enabled_at is not null from administrators;` e vê `true` | trechos dos YAML, relato com captura de tela do QR **borrado**, resultado da consulta |
| E-mail real (pendência do #9 no comentário da issue) | O próprio e-mail de primeiro acesso do Administrador é o envio real; conferir remetente `nao-responda@homolog.sapiensia.app.br` e se caiu em spam | relato do responsável |
| ADR-0001, ADR-0002, SEC-022, SEC-071 | Região `gru`, app e Supabase próprios da homologação, segredos só no Fly.io e diferentes dos de produção (chaves novas quando a produção existir) | tabela da PR citando os IDs |

## 6. Riscos e decisões que dependem de você

1. **Cartão no Fly.io.** O teste (US$ 1, 7 dias) acaba por volta de 17/11/2026 e não cobre a homologação. Estimativa ~US$ 1/mês de uso real, teto ~US$ 11/mês se ficar ligada direto. O perdão de faturas abaixo de US$ 5 pode não valer para a organização `sapiensia`. Decida se cadastra o cartão agora e se quer um alerta de gasto.
2. **Tamanho da máquina.** Recomendo 1 GB (~US$ 10,82/mês de teto); 512 MB custa ~US$ 5,96 de teto, com risco de falta de memória. Confirma 1 GB?
3. **Partida a frio.** Com `stop`, depois de alguns minutos sem uso a primeira tela demora a JVM inteira (estimo 20 a 60 s, vou medir) e todas as sessões caem (o Administrador entra de novo com senha e código). Aceita para homologação, ou prefere `suspend` (religa rápido, guarda as sessões; requer conferir os requisitos)?
4. **Endereço público.** `https://sapiensia-homolog.fly.dev` (sem DNS, certificado automático) ou `https://homolog.sapiensia.app.br` (exige registro no Registro.br e `fly certs add`)? O plano usa o `fly.dev`. O nome `sapiensia-homolog` também precisa estar livre no Fly.io; se não estiver, preciso de outro.
5. **Remetente da homologação.** O padrão é `SapiensIA <nao-responda@sapiensia.app.br>`, mas só `homolog.sapiensia.app.br` está verificado no Resend. Proponho `SapiensIA <nao-responda@homolog.sapiensia.app.br>` (texto que o usuário lê). Aprova?
6. **SSL do banco.** `sslmode=require` agora, `verify-full` (certificado do Supabase dentro da imagem) como item do #47. Aprova?
7. **Usuário do banco.** A aplicação usa o `postgres` do Supabase (dono das tabelas, roda o Flyway). Um papel próprio com menos poder atende melhor o "escopo mínimo" do SEC-022, mas mexe em migrations e em RLS; proponho deixar para o #47. Aprova?
8. **`X-Forwarded-For` no fallback.** Achado da leitura: com `forward-headers-strategy: framework`, `getRemoteAddr()` passa a vir do primeiro valor do `X-Forwarded-For`, escrito pelo cliente. Com o `Fly-Client-IP` ligado isso só pesa se o cabeçalho faltar, e o Fly.io sempre o põe. Se a prova da Tarefa 4 mostrar o contrário, paro e trago para você antes de mudar o código.
9. **`APP_ADMIN_EMAIL`.** Qual caixa recebe o primeiro acesso da homologação? Você só digita no `fly secrets set`; não preciso saber o valor.
10. **Portas.** Nenhuma porta local muda: a imagem escuta 8080 dentro do container e o Fly.io publica 443. `just imagem` não sobe container. Nada a decidir, só registro.
11. **Contas que faltam.** Para este ticket, nenhuma: Fly.io, Supabase e Resend estão prontos. R2 e Asaas ainda não têm código que os use; os segredos do Storage dependem do merge do #18.
12. **Robôs e custo.** O `*.fly.dev` é público e recebe varredura automática que pode manter a máquina acordada. Se o custo medido passar de US$ 3 na primeira semana, trago opções.
13. **Supabase pausado.** Uma semana sem uso pausa o projeto e a homologação para de subir (health check `DOWN`). Religa pelo painel; vai no runbook.

---

## 7. Tarefas

Ordem: 1 → 2 → (OK do responsável nas decisões da seção 6) → 3 → 4 → 5. As Tarefas 1 e 2 vão para subagentes (`model: sonnet`). As Tarefas 3, 4 e 5 **não**: dependem do flyctl logado, de segredos digitados pelo responsável, de conferência com o responsável e de decisões que só ele toma.

### Task 1: Perfil `prod` sem padrão para o banco e com `Fly-Client-IP` (subagente sonnet)

**Files:**
- Modify: `backend/src/main/resources/application-prod.yml`
- Create: `backend/src/test/java/br/com/sergio/sapiensia/ProdProfileConfigTest.java`

**Interfaces:**
- Produces: em `prod`, `spring.datasource.url = ${DB_URL}`, `spring.datasource.username = ${DB_USER}`, `spring.datasource.password = ${DB_PASSWORD}` (sem padrão) e `app.login-limit.client-ip-header = Fly-Client-IP`.

- [ ] **Step 1: Write the failing test** — teste unitário (sem Spring, sem banco) que carrega `application-prod.yml` do classpath com `YamlPropertySourceLoader` e confere os valores brutos. `prod(key)` devolve `String.valueOf(source.getProperty(key))` (o loader devolve `OriginTrackedValue`, não `String`):

```java
@Test
@DisplayName("SEC-014: prod profile reads the client IP from Fly-Client-IP")
void prodProfileUsesFlyClientIp() {
    assertThat(prod("app.login-limit.client-ip-header")).isEqualTo("Fly-Client-IP");
}

@Test
@DisplayName("SEC-022: prod profile has no default for the database settings")
void prodProfileHasNoDatabaseDefaults() {
    assertThat(prod("spring.datasource.url")).isEqualTo("${DB_URL}");
    assertThat(prod("spring.datasource.username")).isEqualTo("${DB_USER}");
    assertThat(prod("spring.datasource.password")).isEqualTo("${DB_PASSWORD}");
}
```

- [ ] **Step 2: Run to verify it fails** — `cd backend; .\mvnw.cmd test -Dtest=ProdProfileConfigTest` → FAIL (`null` nos dois testes).
- [ ] **Step 3: Implement** — acrescentar ao `application-prod.yml` o bloco `spring.datasource` com as três chaves sem padrão (comentário: "no default: a missing value stops the application") e `app.login-limit.client-ip-header: Fly-Client-IP` (comentário: "Fly Proxy overwrites it; proven on staging (#7); never X-Forwarded-For").
- [ ] **Step 4: Run to verify it passes** — mesmo comando → PASS.
- [ ] **Step 5: `just check-resumo`** → verde (o CI e2e usa `-e DB_URL ...`, então continua subindo).
- [ ] **Step 6: Commit** — `git add` dos dois arquivos; `fix: no database defaults and Fly-Client-IP in the prod profile (#7, #56)`.

### Task 2: `fly.homolog.toml` e runbook da homologação (subagente sonnet)

**Files:**
- Create: `fly.homolog.toml` (conteúdo exato da seção 2)
- Create: `docs/runbooks/homologacao-fly.md`
- Modify: `CLAUDE.md` (linha da `docs/` citando o runbook novo)
- Modify: `docs/runbooks/recuperar-acesso-administrador.md` (a frase "pelo caminho aprovado de acesso ao banco (#7)" passa a apontar para o SQL Editor do Supabase e `fly machine restart`)

- [ ] **Step 1:** criar `fly.homolog.toml` com o conteúdo da seção 2. Verificação: `fly config validate -c fly.homolog.toml` **só depois do login (Tarefa 3)**; aqui, conferir que não há segredo: `Select-String -Path fly.homolog.toml -Pattern 'PASSWORD|KEY|@gmail|postgres\.'` → nenhuma linha.
- [ ] **Step 2:** criar o runbook com: a tabela de nomes da seção 1 (sem valores), o padrão `fly secrets set NOME="$(Get-Clipboard)" --stage`, o roteiro 4.3 a 4.5, `--ha=false` em todo deploy, como religar o Supabase pausado, como ver custo (`fly dashboard` > Billing) e como desligar tudo (`fly scale count 0 -a sapiensia-homolog`).
- [ ] **Step 3:** `git grep -n -i -E "password=|apikey|re_[A-Za-z0-9]{8}"` nos arquivos novos → nenhuma linha.
- [ ] **Step 4: Commit** — `chore: Fly.io config and runbook for the staging app (#7)`.

### Task 3: Criar o app, segredos e deploy (sessão principal + responsável, **não** subagente)

- [ ] Seção 4, passos 1 a 17, na ordem, parando a cada **[RESPONSÁVEL]**.
- [ ] `fly config validate -c fly.homolog.toml` → `Configuration is valid`.
- [ ] Esperado no fim: `fly machine list` com 1 máquina `started` em `gru`, `fly secrets list` com os 6 nomes.
- [ ] Se o deploy falhar por config ou segredo faltando: ler `fly logs` (filtrando), corrigir, sem nunca imprimir valores.

### Task 4: Provas na homologação (sessão principal + responsável, **não** subagente)

- [ ] **Step 1:** health e máquina única (passos 17 e 18; `fly checks list`).
- [ ] **Step 2:** `Referrer-Policy` (`curl.exe -sSI` em `/redefinir-senha`, `/painel/definir-senha` e `/entrar`).
- [ ] **Step 3:** primeiro acesso do Administrador e MFA (seção 5), com o responsável.
- [ ] **Step 4:** prova do `Fly-Client-IP`: script PowerShell no scratchpad (fora do repositório) que pega o `XSRF-TOKEN` com um `GET /api/sessions/current`, faz 21 `POST /api/sessions` com e-mails `forge-<n>@example.invalid` e senha fictícia, cada um com `Fly-Client-IP: 198.51.100.<n>` e `X-Forwarded-For: 203.0.113.<n>`, e imprime só o status de cada tentativa. Esperado: tentativas 1 a 20 → `401`, 21 → `429`. Depois o responsável faz o teste do celular e a consulta do `audit_events`. Efeito colateral: o IP do responsável fica bloqueado no login por 15 minutos; rodar antes do Step 3 ou depois de terminar. Se o 21º der `401`, **parar**: desligar o cabeçalho (reverter a linha da Tarefa 1) e trazer ao responsável.
- [ ] **Step 5:** desligamento automático: 15 min sem tráfego, `fly machine list` → `stopped`; `Measure-Command` no health → anotar o tempo de partida a frio.
- [ ] **Step 6:** remetente e entregabilidade do e-mail de primeiro acesso (relato do responsável).

### Task 5: Fechamento (sessão principal, **não** subagente)

- [ ] Atualizar o ADR-0004 (linha do `Fly-Client-IP`: "confirmado na homologação em <data>, ligado no perfil `prod`") e o ADR-0002 (uma linha: conexão pelo pooler do Supabase em modo sessão, e por quê).
- [ ] Skill `verificar-entrega` e a tabela dela.
- [ ] Marcar os critérios da #7 e da #56 com o link das provas; o que ficar de fora vira uma issue `needs-triage` citando o #7.
- [ ] Push e PR (`Closes #7`, `Closes #56`) **só com o seu OK**.
