<#
.SYNOPSIS
  Adapta o java-react-template para um projeto novo.

.DESCRIPTION
  Rode UMA vez, na raiz do projeto, logo depois de criar o repositorio a partir do modelo.
  Ele troca os nomes genericos do modelo pelos do seu projeto:
    - "nome-do-projeto"   -> o nome do projeto (em todos os arquivos de texto)
    - "com.example"       -> o seu groupId (pom.xml)
    - "com.example.app"   -> o pacote Java do projeto (pastas e arquivos)
    - "NomeDoProjeto"     -> o nome das classes Java (ex.: ControleEstoqueApplication)
  Tambem grava a descricao do projeto no CLAUDE.md e no README, escolhe portas proprias para o banco e a API
  (scripts/escolher-portas.ps1), cria no GitHub os rotulos de triagem
  e liga a opcao de apagar a branch depois do merge.
  No fim, confere se sobrou algum nome generico e, se o Docker estiver aberto, roda "just setup" e "just check".
  (Os textos deste script nao tem acento de proposito: o Windows PowerShell 5.1 os mostraria embaralhados.)

.EXAMPLE
  just novo-projeto controle-estoque com.sergio
  (ou, sem o just: powershell -ExecutionPolicy Bypass -File scripts\novo-projeto.ps1 -Nome controle-estoque -GroupId com.sergio)
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$Nome,

    [Parameter(Mandatory = $true)]
    [string]$GroupId,

    [string]$Descricao,

    [switch]$SemCheck
)

$textoDescricao = 'Descreva aqui, em uma frase, o que o projeto faz e para quem.'

$ErrorActionPreference = 'Stop'

function Passo([string]$texto) { Write-Host ""; Write-Host "==> $texto" -ForegroundColor Cyan }
function Ok([string]$texto) { Write-Host "    OK: $texto" -ForegroundColor Green }
function Falha([string]$texto) {
    Write-Host ""
    Write-Host "ERRO: $texto" -ForegroundColor Red
    exit 1
}

# Grava texto em UTF-8 SEM BOM (o compilador Java e o Biome reclamam de BOM).
$utf8SemBom = New-Object System.Text.UTF8Encoding($false)
function Trocar-Texto([string]$arquivo, [string]$de, [string]$para) {
    if (-not (Test-Path -LiteralPath $arquivo)) { return $false }
    $conteudo = [System.IO.File]::ReadAllText($arquivo)
    if (-not $conteudo.Contains($de)) { return $false }
    [System.IO.File]::WriteAllText($arquivo, $conteudo.Replace($de, $para), $utf8SemBom)
    return $true
}

# ---------------------------------------------------------------------------
Passo "Conferindo os dados informados"

if ($Nome -cnotmatch '^[a-z][a-z0-9]*(-[a-z0-9]+)*$') {
    Falha "O nome '$Nome' nao serve. Use letras minusculas, numeros e hifens, comecando por letra. Exemplo: controle-estoque"
}
if ($GroupId -cnotmatch '^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)+$') {
    Falha "O groupId '$GroupId' nao serve. Use letras minusculas separadas por ponto, com pelo menos duas partes. Exemplo: com.sergio"
}

$raiz = (Get-Location).Path
$pom = Join-Path (Join-Path $raiz 'backend') 'pom.xml'
if (-not (Test-Path -LiteralPath (Join-Path $raiz 'justfile')) -or -not (Test-Path -LiteralPath $pom)) {
    Falha "Rode este script na RAIZ do projeto (a pasta que tem o arquivo justfile e a pasta backend)."
}
$remoto = ''
try { $remoto = "$(& git remote get-url origin 2>$null)" } catch { }
if ($remoto -match 'java-react-template(\.git)?$') {
    Falha "Esta pasta e o proprio modelo (java-react-template). Rode o script so nos projetos criados a partir dele."
}
if (-not ([System.IO.File]::ReadAllText($pom)).Contains('nome-do-projeto')) {
    Falha "Este projeto ja foi adaptado (o pom.xml nao tem mais 'nome-do-projeto'). Nao rode o script duas vezes."
}
if (-not $Descricao) {
    Write-Host ""
    $Descricao = Read-Host "    Descreva o projeto em uma frase (ex.: Controle de estoque da loja, usado pelos vendedores)"
}
if (-not $Descricao) { $Descricao = $textoDescricao }

$sufixo = $Nome.Replace('-', '')
$pacoteNovo = "$GroupId.$sufixo"
$classe = (($Nome -split '-') | ForEach-Object { $_.Substring(0, 1).ToUpper() + $_.Substring(1) }) -join ''

Ok "nome do projeto: $Nome"
Ok "groupId: $GroupId"
Ok "pacote Java: $pacoteNovo"
Ok "classe principal: ${classe}Application"

# ---------------------------------------------------------------------------
Passo "Movendo o codigo Java para o pacote $pacoteNovo"

$partesPacote = $pacoteNovo -split '\.'
foreach ($tipo in @('main', 'test')) {
    $java = Join-Path (Join-Path (Join-Path (Join-Path $raiz 'backend') 'src') $tipo) 'java'
    $origem = Join-Path (Join-Path (Join-Path $java 'com') 'example') 'app'
    if (-not (Test-Path -LiteralPath $origem)) { continue }

    $destino = $java
    foreach ($parte in $partesPacote) { $destino = Join-Path $destino $parte }
    if (-not (Test-Path -LiteralPath $destino)) { New-Item -ItemType Directory -Path $destino | Out-Null }

    Get-ChildItem -LiteralPath $origem -Force | Move-Item -Destination $destino

    # apaga as pastas antigas que ficaram vazias (app, example, com)
    $pasta = $origem
    while ($pasta -ne $java) {
        if ((Test-Path -LiteralPath $pasta) -and -not (Get-ChildItem -LiteralPath $pasta -Force)) {
            Remove-Item -LiteralPath $pasta
        }
        $pasta = Split-Path -Parent $pasta
    }
    Ok "src/$tipo movido"
}

# ---------------------------------------------------------------------------
Passo "Renomeando as classes Java para ${classe}..."

$arquivosJava = Get-ChildItem -LiteralPath (Join-Path $raiz 'backend') -Recurse -File -Filter '*.java'
foreach ($arquivo in $arquivosJava) {
    $mudou = $false
    if (Trocar-Texto $arquivo.FullName 'com.example.app' $pacoteNovo) { $mudou = $true }
    if (Trocar-Texto $arquivo.FullName 'NomeDoProjeto' $classe) { $mudou = $true }
    if ($arquivo.Name.Contains('NomeDoProjeto')) {
        $novoNome = $arquivo.Name.Replace('NomeDoProjeto', $classe)
        Rename-Item -LiteralPath $arquivo.FullName -NewName $novoNome
        Ok "$($arquivo.Name) -> $novoNome"
    }
    elseif ($mudou) {
        Ok "$($arquivo.Name) atualizado"
    }
}

# ---------------------------------------------------------------------------
Passo "Trocando 'nome-do-projeto' pelo nome do projeto em todos os arquivos de texto"

if (Trocar-Texto $pom '<groupId>com.example</groupId>' "<groupId>$GroupId</groupId>") { Ok "groupId no pom.xml" }

# Arquivos de texto do projeto (fora as pastas geradas e a pasta scripts, que cita os nomes genericos de proposito)
$extensoesTexto = @('.java', '.xml', '.yml', '.yaml', '.properties', '.sql', '.md', '.json', '.jsonc',
    '.ts', '.tsx', '.js', '.mjs', '.html', '.css', '.toml', '.txt')
function Arquivos-Texto {
    Get-ChildItem -LiteralPath $raiz -Recurse -File -Force |
        Where-Object { $_.FullName -notmatch '[\\/](\.git|node_modules|target|dist|scripts)([\\/]|$)' } |
        Where-Object { ($extensoesTexto -contains $_.Extension.ToLower()) -or ($_.Name -eq 'justfile') -or ($_.Name -eq '.env.example') }
}
function Relativo([string]$caminho) { return $caminho.Substring($raiz.Length + 1).Replace('\', '/') }

foreach ($arquivo in Arquivos-Texto) {
    $mudou = $false
    if (Trocar-Texto $arquivo.FullName 'com.example.app' $pacoteNovo) { $mudou = $true }
    if (Trocar-Texto $arquivo.FullName 'nome-do-projeto' $Nome) { $mudou = $true }
    if ($mudou) { Ok (Relativo $arquivo.FullName) }
}
foreach ($relativo in @('CLAUDE.md', 'README.md')) {
    if (Trocar-Texto (Join-Path $raiz $relativo) $textoDescricao $Descricao) { Ok "descricao gravada em $relativo" }
}

# ---------------------------------------------------------------------------
# Portas proprias do banco (5433 em diante) e da API (8081 em diante): veja scripts/escolher-portas.ps1
& (Join-Path $PSScriptRoot 'escolher-portas.ps1')
if ($LASTEXITCODE -ne 0) { Falha "Nao consegui escolher as portas do projeto (veja a mensagem acima)." }

# ---------------------------------------------------------------------------
Passo "Procurando nomes genericos que tenham sobrado"

$sobras = Arquivos-Texto | Select-String -SimpleMatch -Pattern 'nome-do-projeto', 'NomeDoProjeto', 'com.example.app' -List

if ($sobras) {
    Write-Host "    Ainda aparecem nomes genericos nestes arquivos (troque a mao ou peca ao Claude):" -ForegroundColor Yellow
    $sobras | ForEach-Object { Write-Host "      $(Relativo $_.Path), linha $($_.LineNumber)" -ForegroundColor Yellow }
}
else {
    Ok "nenhum nome generico sobrou"
}

# ---------------------------------------------------------------------------
# Daqui em diante so chamamos programas externos (gh, docker, just).
# No Windows PowerShell 5.1, 'Stop' transformaria qualquer aviso deles em erro fatal.
$ErrorActionPreference = 'Continue'

Passo "Configurando o repositorio no GitHub (rotulos de triagem e branch apagada depois do merge)"

$rotulos = @(
    @{ nome = 'needs-triage';    cor = 'e4e669'; descricao = 'Precisa de triagem' },
    @{ nome = 'needs-info';      cor = 'd876e3'; descricao = 'Falta informacao de quem pediu' },
    @{ nome = 'ready-for-agent'; cor = '0e8a16'; descricao = 'Pronta para o Claude implementar' },
    @{ nome = 'ready-for-human'; cor = '1d76db'; descricao = 'Precisa de uma pessoa' },
    @{ nome = 'wontfix';         cor = 'ffffff'; descricao = 'Nao sera feito' }
)
if (-not $remoto) {
    Write-Host "    Pulei: esta pasta ainda nao esta ligada a um repositorio do GitHub." -ForegroundColor Yellow
}
else {
    gh auth status *> $null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "    Pulei: o gh esta sem login. Rode gh auth login e depois peca ao Claude:" -ForegroundColor Yellow
        Write-Host "    'crie os rotulos de triagem descritos em docs/agents/triage-labels.md'." -ForegroundColor Yellow
    }
    else {
        $existentes = @(gh label list --limit 200 --json name --jq '.[].name' 2>$null)
        foreach ($r in $rotulos) {
            if ($existentes -contains $r.nome) { Ok "$($r.nome) (ja existia)"; continue }
            gh label create $r.nome --color $r.cor --description $r.descricao *> $null
            if ($LASTEXITCODE -eq 0) { Ok $r.nome } else { Write-Host "    Nao consegui criar $($r.nome)." -ForegroundColor Yellow }
        }
        # Apaga a branch no GitHub depois do merge da PR (sem isso, elas se acumulam).
        gh repo edit --delete-branch-on-merge *> $null
        if ($LASTEXITCODE -eq 0) { Ok 'branch apagada sozinha depois do merge' }
        else { Write-Host "    Nao consegui ligar 'apagar a branch depois do merge'. Rode: gh repo edit --delete-branch-on-merge" -ForegroundColor Yellow }
    }
}

# ---------------------------------------------------------------------------
if ($SemCheck) {
    Passo "Pronto. Rode 'just setup', 'just db' e 'just check' para conferir."
    exit 0
}

Passo "Conferindo o projeto com 'just setup' e 'just check'"

docker info *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host "    O Docker nao esta rodando. Abra o Docker Desktop, espere 'Engine running'" -ForegroundColor Yellow
    Write-Host "    e rode na raiz do projeto: just setup  e depois  just check" -ForegroundColor Yellow
    exit 0
}

just setup
if ($LASTEXITCODE -ne 0) { Falha "'just setup' falhou. Copie as ultimas linhas acima e cole numa conversa com o Claude." }

# No Windows, logo depois de instalar tudo, o Vitest pode levar mais de 60 s so para iniciar
# (disco frio, antivirus examinando os arquivos novos) e desistir com "Timeout waiting for worker".
# Uma execucao de aquecimento, cujo resultado e ignorado, deixa a seguinte rapida.
Passo "Aquecendo o Vitest (no Windows a primeira execucao pode levar 1 minuto)"
Push-Location (Join-Path $raiz 'frontend')
pnpm exec vitest run --passWithNoTests *> $null
Pop-Location

just check
if ($LASTEXITCODE -ne 0) { Falha "'just check' falhou. Copie as ultimas 30 linhas acima e cole numa conversa com o Claude." }

Passo "Projeto '$Nome' pronto. Proximo passo: commit e push (Parte 7.5 do guia)."
