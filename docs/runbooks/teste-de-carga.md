# Teste de carga

Como preparar e rodar o teste de carga (k6) e como ler o resultado. Referência: NFR-001, NFR-002 e NFR-010 em [docs/spec/sapiensia-spec.md](../spec/sapiensia-spec.md); carga nominal do ticket #44: 50 usuários simultâneos e 20 compras por hora no pico.

**Regra de segurança: o teste só roda contra o sistema local.** Rodar contra a homologação ou contra o sandbox do Asaas precisa da autorização do responsável, e este runbook só descreve o que mudaria (seção "Homologação"). Os scripts recusam um endereço que não seja local.

**Situação:** o script está validado localmente. O critério "executado na homologação" do #44 continua aberto, e o resultado local **não é** a meta (veja "Resultado").

## O que o teste faz

Script: [scripts/carga/cenarios.js](../../scripts/carga/cenarios.js) (k6). Três cenários rodam juntos, num perfil de 10 minutos: 1 min subindo até 50 usuários, 8 min no platô, 1 min descendo.

| Cenário | Quem | O que faz | Usuários |
|---|---|---|---|
| Catálogo | visitante | lista, detalhe e (metade das vezes) capa de um Produto | 35 |
| Biblioteca e Download | Cliente logado | login, sessão atual, Biblioteca; até 8 Downloads por conta | 15 |
| Pedido | Cliente | login, cria o Pedido de um Produto que ainda não tem, lê o Pedido | taxa de 20 por hora |

Detalhes que importam:

- O login do k6 usa o CSRF como o frontend (cookie `XSRF-TOKEN` devolvido no cabeçalho `X-XSRF-TOKEN`). O `noCookiesReset` mantém a sessão do Cliente durante a vida do usuário virtual.
- Cada conta faz no máximo 8 Downloads por execução porque o limite é de 10 por Produto por dia (#34). **Rodar de novo no mesmo dia com as mesmas contas estoura esse limite** (aparece como `429` em `download`): rode o preparo outra vez para criar contas novas.
- `429` não é erro do servidor: é o limite funcionando. Fica numa métrica própria (`respostas_429`) e fora da taxa de erro, mas aparece no resultado.

### Metas (`thresholds`)

| Grupo | Meta |
|---|---|
| Endpoints comuns: Catálogo (lista, detalhe, capa), sessão atual, Biblioteca, leitura do Pedido | p95 < 500 ms e p99 < 1500 ms (NFR-002) |
| Login, Download e criação do Pedido | só medidos, sem meta de NFR-002 |
| Erros (4xx fora o 429, 5xx e falhas de rede) | taxa < 0,5 % |
| Verificações (`check`) | > 99,5 % |

Escolhas conservadoras, marcadas para o responsável conferir: o login fica de fora da meta porque é lento de propósito (hash da senha) e a criação do Pedido porque chama o provedor de Pix; a taxa de erro < 0,5 % só **aproxima** a NFR-001 (que é mensal: 10 minutos de teste não medem disponibilidade de 99,5 %).

## Rodar localmente

Precisa do Docker aberto (banco e k6) e da API local ligada com a demonstração (Produtos publicados com PDF) e com a caixa de saída de e-mail em arquivo.

1. Suba o banco (`just db`; se o banco de outra pasta já usa a porta 5433, reaproveite-o) e a API com a demonstração ligada, num terminal à parte. Foi assim que a validação local foi feita:

   ```powershell
   $env:DB_URL = "jdbc:postgresql://localhost:5433/app_demo"
   $env:APP_DEMO_ENABLED = "true"
   $env:APP_EMAIL_OUTBOX_DIR = "../.tmp/outbox"
   just dev-back
   ```

   Usa o banco da demonstração (`app_demo`, que o `just demo` cria), publica três Produtos de demonstração com PDF e grava os e-mails como arquivos em `.tmp/outbox`. Depois, em outro terminal:

   ```powershell
   node scripts/carga/preparar.mjs 50
   ```

   Cria 50 contas confirmadas (cadastro, link de confirmação lido da caixa de saída local, login), compra e "paga" um Produto para cada uma (Pix falso e webhook local, reaproveitando o `demo-pagar.mjs`) e grava `.tmp/carga/contas.json` (fora do git). O argumento é a quantidade de contas (o perfil completo usa 50; o padrão é 30). A caixa de saída lida é `.tmp/outbox` (outra pasta: `$env:CARGA_OUTBOX_DIR`). Se a API não estiver na porta 8081, use `$env:CARGA_API_URL = "http://127.0.0.1:<porta>"`.

2. Rode o teste (só depois do preparo: sem `.tmp/carga/contas.json` o k6 para e manda rodar o preparo; o k6 também exige pelo menos 20 contas):

   ```powershell
   just carga
   ```

   Roda o k6 em Docker (imagem `grafana/k6:2.3.0`), com os scripts de `scripts/carga` e a pasta `.tmp/carga` montados dentro do contêiner. Escreve o resumo na tela e o resultado completo em `.tmp/carga/resultado.json`. Falha (código de saída diferente de zero) se alguma meta não for cumprida.

3. Versão curta (validação do script, uns 2 minutos):

   ```powershell
   $env:VUS_CATALOG = 7; $env:VUS_LIBRARY = 3; $env:RAMP_UP = "20s"; $env:HOLD = "80s"; $env:RAMP_DOWN = "20s"; $env:ORDERS_PER_HOUR = 120
   just carga
   ```

   Reduz os usuários e as durações e sobe a taxa de Pedidos (20 por hora quase não geraria Pedido em 2 minutos). Limpe as variáveis depois (`Remove-Item Env:VUS_CATALOG` etc.) ou feche o terminal.

Variáveis aceitas pelo k6: `BASE_URL`, `CARGA_PERMITIR_REMOTO`, `RAMP_UP`, `HOLD`, `RAMP_DOWN`, `VUS_CATALOG`, `VUS_LIBRARY`, `ORDERS_PER_HOUR`, `DOWNLOADS_PER_ACCOUNT`. O `BASE_URL` padrão é `http://host.docker.internal:8081` (a API local vista de dentro do Docker); o script recusa outro endereço que não seja local, a menos que `CARGA_PERMITIR_REMOTO=sim`.

Cuidados:

- O teste grava linhas reais no banco local (Pedidos, Registros de download). É o banco de desenvolvimento ou da demonstração; nada de produção.
- A carga local disputa CPU com o próprio k6 e o Docker: os números locais só servem de referência.
- Os contadores de login ficam em memória na API (ADR-0004), e o limite por IP de login é de 1000 por 15 min no perfil local. Um teste que abre sessão sem parar (ou um k6 que ficou rodando em segundo plano) pode esgotá-lo e devolver `429` em todo login até a janela passar; reiniciar a API local zera os contadores.
- O `just carga` monta caminhos com `justfile_directory()` num shell PowerShell: foi testado só no Windows (o CI não o roda).
- A recusa de endereço não local fica em `scripts/carga/local-url.mjs` (testada em `local-url.test.mjs`, e carregada pelo k6).
- A versão do k6 é fixa em `grafana/k6:2.3.0` (release v2.3.0 de 2026-09-21, publicada há mais de 7 dias): <https://github.com/grafana/k6/releases/tag/v2.3.0>. Para trocar, escolha uma versão publicada há mais de 7 dias e atualize o `justfile` e este runbook.

## Resultado

### Validação local do script (não é a meta)

Rodada na máquina de desenvolvimento, com o k6, a API, o banco e o Docker disputando a mesma CPU. **Serve para provar que o script funciona, não para dizer que o sistema cumpre a NFR-002.**

Rodada em 2026-11-16, API local (`just dev-back` com a demonstração ligada), banco local em Docker, k6 `grafana/k6:2.3.0`. O perfil completo usou 50 contas preparadas na hora; a rodada curta reaproveitou contas de rodadas anteriores do mesmo dia.

| Perfil | Grupo | Requisições | p95 | p99 |
|---|---|---|---|---|
| **Completo local** (50 usuários, 1+8+1 min, 20 Pedidos/h) | Catálogo | 11864 | 10 ms | 11 ms |
| | Sessão atual | 4016 | 7 ms | 8 ms |
| | Biblioteca | 4016 | 5 ms | 6 ms |
| | Leitura do Pedido | 4 | 4 ms | 4 ms |
| | **Comuns (NFR-002)** | | **9 ms** | **11 ms** |
| | Login (sem meta) | 112 | 116 ms | 128 ms |
| | Download (sem meta) | 120 | 15 ms | 31 ms |
| | Criação do Pedido (sem meta) | 4 | 19 ms | 19 ms |
| | Taxa de erro / respostas 429 / metas | | 0,000 % / 0 / todas cumpridas | |
| **Curto** (10 usuários, 20 s + 80 s + 20 s, 120 Pedidos/h) | Catálogo | 435 | 12 ms | 13 ms |
| | Sessão atual | 151 | 11 ms | 12 ms |
| | Biblioteca | 151 | 7 ms | 10 ms |
| | Leitura do Pedido | 4 | 16 ms | 17 ms |
| | **Comuns (NFR-002)** | | **12 ms** | **13 ms** |
| | Login (sem meta) | 10 | 261 ms | 357 ms |
| | Download (sem meta) | 8 | 26 ms | 28 ms |
| | Criação do Pedido (sem meta) | 4 | 36 ms | 40 ms |
| | Taxa de erro / respostas 429 / metas | | 0,000 % / 1 / todas cumpridas | |

Notas da rodada:

- O `429` da rodada curta foi um Download: aquelas contas já tinham feito Downloads em rodadas anteriores no mesmo dia (limite de 10 por Produto por dia). Para o perfil completo foram criadas contas novas e não houve `429`.
- Com só 4 Pedidos (20 por hora em 10 minutos), os números de criação e leitura do Pedido têm pouco valor estatístico.
- Os tempos são muito abaixo das metas porque o sistema local não tem rede nem latência de provedor (Pix falso, arquivos locais); isso não diz nada sobre a homologação.
- Um problema do próprio script, achado e corrigido na validação: o k6 zera os cookies a cada iteração, o que derrubava a sessão do Cliente (401 e 15,6 % de erro) e esgotava o limite de login por IP; o script passou a usar `noCookiesReset`.

### Homologação

Ainda não executado: precisa da autorização do responsável. Preencha a tabela abaixo quando rodar.

| Data | Ambiente | Perfil | p95 comuns | p99 comuns | Taxa de erro | 429 | Metas |
|---|---|---|---|---|---|---|---|
| (a preencher) | homologação | 50 usuários, 1+8+1 min | | | | | |

## Homologação: o que muda (com autorização)

Nada abaixo foi executado. Decida antes de rodar:

1. **Limites por IP.** De um só IP, os limites de produção bloqueiam o teste: login 20 tentativas por 15 min, criação de Pedido 30 por 10 min, leitura do Pedido 120 por 10 min, Download 60 por 10 min, capa 600 por 10 min. Opções: elevar o `APP_RATE_LIMIT_IP_CAPACITY_FLOOR` e o limite de login só durante o teste (e voltar depois), ou aceitar os `429` e medir só o que passa.
2. **Contas.** O preparo automático só funciona no local: o e-mail de confirmação sai pelo Resend (não há caixa de saída em arquivo) e as compras passam pelo sandbox do Asaas (sem o webhook local). Na homologação, crie as contas à mão (ou com a ajuda do responsável), compre um Produto em cada e monte o `.tmp/carga/contas.json` no mesmo formato (`products` e `accounts` com `email`, `password` e `productId`).
3. **Pedidos.** Cada Pedido cria uma cobrança Pix no sandbox do Asaas: confira se o volume (20 por hora) cabe nos limites do sandbox.
4. **Download.** Na homologação o link é uma URL assinada do Supabase (o k6 só pede o link, não baixa o arquivo).
5. **Rodar.** `$env:BASE_URL = "https://<endereço da homologação>"` e `$env:CARGA_PERMITIR_REMOTO = "sim"`, depois `just carga`. O `host.docker.internal` não se aplica.
6. Uma máquina só (`--ha=false`, ADR-0004): o teste mede esse limite, não um cluster.

## Gargalo encontrado

Se uma meta falhar na homologação: não afrouxe a meta. Anote o grupo, os p95/p99 e a hora na tabela acima, olhe os logs da API no mesmo intervalo e abra **uma** issue com o rótulo `needs-triage`, citando o #44 e a NFR-002, com o grupo, os números e o que já foi descartado (por exemplo, "o limite por IP, não a API"). Se foi o limite por IP (`429`), é decisão de configuração do teste, não gargalo.
