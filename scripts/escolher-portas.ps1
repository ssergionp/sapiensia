<#
.SYNOPSIS
  Da a este projeto uma porta propria para o banco local e outra para a API.

.DESCRIPTION
  Rode na raiz do projeto. Cada projeto ganha as suas portas, para varios poderem ficar ligados ao mesmo tempo:
    - banco: 5433 em diante (docker-compose.yml, application.yml e .env.example)
    - API:   8081 em diante (server.port do application.yml, proxy /api do vite.config.ts e o link do Swagger no CLAUDE.md)
  Pula as portas ja usadas pelos outros projetos da pasta de projetos e as ocupadas neste computador.
  Porta que ja foi trocada fica como esta: rodar de novo nao muda nada.
  O novo-projeto.ps1 chama este script. Num projeto antigo (com 5432 e 8080), o /trazer-do-modelo o usa.
  (Os textos deste script nao tem acento de proposito: o Windows PowerShell 5.1 os mostraria embaralhados.)

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts/escolher-portas.ps1
#>

$ErrorActionPreference = 'Stop'

function Ok([string]$texto) { Write-Host "    OK: $texto" -ForegroundColor Green }
function Aviso([string]$texto) { Write-Host "    AVISO: $texto" -ForegroundColor Yellow }
function Falha([string]$texto) {
    Write-Host ""
    Write-Host "ERRO: $texto" -ForegroundColor Red
    exit 1
}

# Grava texto em UTF-8 SEM BOM (o compilador Java e o Biome reclamam de BOM).
$utf8SemBom = New-Object System.Text.UTF8Encoding($false)
function Ler([string]$arquivo) { return [System.IO.File]::ReadAllText($arquivo) }
function Gravar([string]$arquivo, [string]$texto) { [System.IO.File]::WriteAllText($arquivo, $texto, $utf8SemBom) }
function Trocar-Texto([string]$arquivo, [string]$de, [string]$para) {
    if (-not (Test-Path -LiteralPath $arquivo)) { return $false }
    $conteudo = Ler $arquivo
    if (-not $conteudo.Contains($de)) { return $false }
    Gravar $arquivo $conteudo.Replace($de, $para)
    return $true
}

# server.port logo abaixo de "server:" no application.yml
$regexPortaApi = '(?m)^server:[ \t]*\r?\n[ \t]+port:[ \t]*(\d+)'
$regexPortaBanco = '127\.0\.0\.1:(\d+):5432'

function Porta-Ocupada([int]$porta) {
    if (-not (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue)) { return $false }
    return [bool](Get-NetTCPConnection -LocalPort $porta -State Listen -ErrorAction SilentlyContinue)
}
function Primeira-Livre([int]$inicio, [int[]]$usadas) {
    $porta = $inicio
    while (($usadas -contains $porta) -or (Porta-Ocupada $porta)) { $porta++ }
    return $porta
}

Write-Host ""
Write-Host "==> Escolhendo portas proprias para o banco e para a API" -ForegroundColor Cyan

$raiz = (Get-Location).Path
$recursos = Join-Path (Join-Path (Join-Path (Join-Path $raiz 'backend') 'src') 'main') 'resources'
$appYml = Join-Path $recursos 'application.yml'
$compose = Join-Path $raiz 'docker-compose.yml'
$envExemplo = Join-Path $raiz '.env.example'
$vite = Join-Path (Join-Path $raiz 'frontend') 'vite.config.ts'
$claudeMd = Join-Path $raiz 'CLAUDE.md'

if (-not (Test-Path -LiteralPath (Join-Path $raiz 'justfile')) -or -not (Test-Path -LiteralPath $appYml) -or
    -not (Test-Path -LiteralPath $compose)) {
    Falha "Rode este script na RAIZ do projeto (a pasta com justfile, docker-compose.yml e backend)."
}
$remoto = ''
try { $remoto = "$(& git remote get-url origin 2>$null)" } catch { }
if ($remoto -match 'java-react-template(\.git)?$') {
    Falha "Esta pasta e o proprio modelo (java-react-template): ele fica com 5432 e 8080, que cada projeto troca."
}

# Portas que os outros projetos da pasta de projetos ja usam
$usadasBanco = @()
$usadasApi = @()
Get-ChildItem -LiteralPath (Split-Path -Parent $raiz) -Directory | Where-Object { $_.FullName -ne $raiz } | ForEach-Object {
    $outroCompose = Join-Path $_.FullName 'docker-compose.yml'
    if (Test-Path -LiteralPath $outroCompose) {
        foreach ($m in [regex]::Matches((Ler $outroCompose), $regexPortaBanco)) { $usadasBanco += [int]$m.Groups[1].Value }
    }
    $outroYml = Join-Path (Join-Path (Join-Path (Join-Path (Join-Path $_.FullName 'backend') 'src') 'main') 'resources') 'application.yml'
    if (Test-Path -LiteralPath $outroYml) {
        $m = [regex]::Match((Ler $outroYml), $regexPortaApi)
        if ($m.Success) { $usadasApi += [int]$m.Groups[1].Value }
    }
}

# ---------------------------------------------------------------------------
# Banco
$textoCompose = Ler $compose
if ($textoCompose.Contains('127.0.0.1:5432:5432')) {
    $portaBanco = Primeira-Livre 5433 $usadasBanco
    if (Trocar-Texto $compose '127.0.0.1:5432:5432' "127.0.0.1:${portaBanco}:5432") { Ok "docker-compose.yml: banco na porta $portaBanco" }
    if (Trocar-Texto $appYml 'localhost:5432' "localhost:$portaBanco") { Ok "application.yml: banco na porta $portaBanco" }
    if (Trocar-Texto $envExemplo 'localhost:5432' "localhost:$portaBanco") { Ok ".env.example: banco na porta $portaBanco" }
}
else {
    $m = [regex]::Match($textoCompose, $regexPortaBanco)
    if (-not $m.Success) { Falha "Nao achei a porta do banco no docker-compose.yml (esperava 127.0.0.1:NUMERO:5432)." }
    $portaBanco = [int]$m.Groups[1].Value
    Ok "banco: ja tem a porta propria $portaBanco"
}

# ---------------------------------------------------------------------------
# API
$textoYml = Ler $appYml
if (-not [regex]::IsMatch($textoYml, $regexPortaApi)) {
    if ([regex]::IsMatch($textoYml, '(?m)^server:')) {
        Falha "O application.yml tem um bloco server: sem 'port:' na primeira linha dele. Coloque 'port: 8080' logo abaixo de server: e rode de novo."
    }
    # Projeto antigo, sem server.port: a API usava a 8080, o padrao do Spring.
    $quebra = "`n"
    if ($textoYml.Contains("`r`n")) { $quebra = "`r`n" }
    $textoYml = "server:${quebra}  port: 8080${quebra}" + $textoYml
    Gravar $appYml $textoYml
    Ok "application.yml: bloco server.port acrescentado"
}
$m = [regex]::Match($textoYml, $regexPortaApi)
$portaApi = [int]$m.Groups[1].Value
if ($portaApi -eq 8080) {
    $portaApi = Primeira-Livre 8081 $usadasApi
    $grupo = $m.Groups[1]
    Gravar $appYml ($textoYml.Substring(0, $grupo.Index) + $portaApi + $textoYml.Substring($grupo.Index + $grupo.Length))
    Ok "application.yml: API na porta $portaApi"
    if (Trocar-Texto $vite 'localhost:8080' "localhost:$portaApi") { Ok "vite.config.ts: proxy /api para a porta $portaApi" }
    if (Trocar-Texto $claudeMd 'localhost:8080' "localhost:$portaApi") { Ok "CLAUDE.md: Swagger na porta $portaApi" }
}
else {
    Ok "API: ja tem a porta propria $portaApi"
}
if (-not ((Test-Path -LiteralPath $vite) -and (Ler $vite).Contains("localhost:$portaApi"))) {
    Aviso "o vite.config.ts nao tem o proxy /api para http://localhost:$portaApi. Sem ele, a tela nao chega na API."
}

Write-Host ""
Write-Host "    Portas deste projeto: banco $portaBanco, API $portaApi." -ForegroundColor Green
exit 0
