<#
.SYNOPSIS
  Confere se o computador tem tudo que um projeto Java + React precisa.

.DESCRIPTION
  So le informacoes: nao instala, nao muda nada. Para cada item mostra [OK], [AVISO] ou [FALTA]
  e, quando algo esta errado, diz o que fazer e em qual parte do guia.
  Pode ser rodado de qualquer pasta. Dentro de um projeto, tambem: just doctor
#>

$problemas = 0
$avisos = 0

function Mostrar-Ok([string]$item, [string]$detalhe) {
    Write-Host ("[OK]    {0,-22} {1}" -f $item, $detalhe) -ForegroundColor Green
}
function Mostrar-Aviso([string]$item, [string]$detalhe, [string]$comoResolver) {
    $script:avisos++
    Write-Host ("[AVISO] {0,-22} {1}" -f $item, $detalhe) -ForegroundColor Yellow
    Write-Host ("        -> {0}" -f $comoResolver) -ForegroundColor Yellow
}
function Mostrar-Falta([string]$item, [string]$detalhe, [string]$comoResolver) {
    $script:problemas++
    Write-Host ("[FALTA] {0,-22} {1}" -f $item, $detalhe) -ForegroundColor Red
    Write-Host ("        -> {0}" -f $comoResolver) -ForegroundColor Red
}
function Tem-Comando([string]$nome) { return [bool](Get-Command $nome -ErrorAction SilentlyContinue) }
function Primeira-Linha([string]$comando, [string[]]$argumentos) {
    try { return ((& $comando @argumentos 2>&1) | Select-Object -First 1 | Out-String).Trim() } catch { return '' }
}

Write-Host ""
Write-Host "Conferindo o ambiente (nada sera alterado)..." -ForegroundColor Cyan
Write-Host ""

# Git
if (Tem-Comando 'git') { Mostrar-Ok 'Git' (Primeira-Linha 'git' @('--version')) }
else { Mostrar-Falta 'Git' 'nao instalado' 'Parte 1.3 do guia: winget install --id Git.Git -e --source winget' }

# GitHub CLI e login
if (Tem-Comando 'gh') {
    gh auth status *> $null
    if ($LASTEXITCODE -eq 0) { Mostrar-Ok 'GitHub CLI' 'instalado e com login feito' }
    else { Mostrar-Falta 'GitHub CLI' 'instalado, mas sem login' 'Parte 2.2 do guia: gh auth login' }
}
else { Mostrar-Falta 'GitHub CLI' 'nao instalado' 'Parte 1.3 do guia: winget install --id GitHub.cli -e --source winget' }

# just (precisa da 1.38 ou mais nova por causa do [working-directory])
if (Tem-Comando 'just') {
    $linha = Primeira-Linha 'just' @('--version')
    if ($linha -match '(\d+)\.(\d+)\.(\d+)') {
        $versao = [version]"$($Matches[1]).$($Matches[2]).$($Matches[3])"
        if ($versao -ge [version]'1.38.0') { Mostrar-Ok 'just' $linha }
        else { Mostrar-Falta 'just' "$linha (antigo)" 'Precisa da 1.38 ou mais nova: winget upgrade --id Casey.Just' }
    }
    else { Mostrar-Aviso 'just' 'versao nao reconhecida' 'Rode just --version e confira se e 1.38 ou mais nova' }
}
else { Mostrar-Falta 'just' 'nao instalado' 'Parte 1.3 do guia: winget install --id Casey.Just -e --source winget' }

# Node
if (Tem-Comando 'node') {
    $linha = Primeira-Linha 'node' @('--version')
    # minimo declarado em engines.node do frontend/package.json (relativo a este script, nao a pasta atual)
    $minimoNode = $null
    $pacoteFront = Join-Path $PSScriptRoot '..\frontend\package.json'
    if (Test-Path -LiteralPath $pacoteFront) {
        try {
            $engines = (Get-Content -LiteralPath $pacoteFront -Raw | ConvertFrom-Json).engines
            if ($engines -and $engines.node -match '>=(\d+\.\d+\.\d+)') { $minimoNode = [version]$Matches[1] }
        } catch { }
    }
    $versaoNode = $null
    if ($linha -match '^v(\d+\.\d+\.\d+)') { $versaoNode = [version]$Matches[1] }
    if ($minimoNode -and $versaoNode -and $versaoNode -lt $minimoNode) {
        Mostrar-Aviso 'Node.js' "$linha (o projeto pede >=$minimoNode)" 'Atualize o Node: winget install --id OpenJS.NodeJS.LTS -e --source winget'
    }
    elseif ($linha -match '^v(\d+)\.' -and ([int]$Matches[1] % 2 -eq 0)) { Mostrar-Ok 'Node.js' $linha }
    else { Mostrar-Aviso 'Node.js' "$linha (nao e uma versao LTS)" 'Prefira a LTS: winget install --id OpenJS.NodeJS.LTS -e --source winget' }
}
else { Mostrar-Falta 'Node.js' 'nao instalado' 'Parte 1.3 do guia: winget install --id OpenJS.NodeJS.LTS -e --source winget' }

# pnpm
if (Tem-Comando 'pnpm') { Mostrar-Ok 'pnpm' (Primeira-Linha 'pnpm' @('--version')) }
else { Mostrar-Falta 'pnpm' 'nao instalado' 'Parte 1.3 do guia: npm install -g pnpm (com o terminal reaberto)' }

# Java: o que roda primeiro no PATH precisa ser o 25
if (Tem-Comando 'java') {
    # pula linhas como "Picked up JAVA_TOOL_OPTIONS..." e pega a que diz a versao
    $linha = ''
    try { $linha = ((& java --version 2>&1) | ForEach-Object { "$_" } | Where-Object { $_ -match '^(openjdk|java)\s+\d+' } | Select-Object -First 1) } catch { }
    if ($linha -match '^\S+\s+(\d+)') {
        if ([int]$Matches[1] -ge 25) { Mostrar-Ok 'Java' $linha }
        else {
            Mostrar-Falta 'Java' "$linha (precisa do 25)" 'Instale o 25 (Parte 1.3). Se ja instalou, ele precisa vir antes no PATH: rode where.exe java e veja a ordem'
        }
    }
    else { Mostrar-Aviso 'Java' 'versao nao reconhecida' 'Rode java --version e confira se e o 25' }
}
else { Mostrar-Falta 'Java' 'nao instalado' 'Parte 1.3 do guia: winget install --id Microsoft.OpenJDK.25 -e --source winget' }

# JAVA_HOME (o Maven usa ele)
if ($env:JAVA_HOME) {
    if ($env:JAVA_HOME -match 'jdk-?2[5-9]|jdk-?[3-9]\d') { Mostrar-Ok 'JAVA_HOME' $env:JAVA_HOME }
    else { Mostrar-Aviso 'JAVA_HOME' "$($env:JAVA_HOME) (nao parece o 25)" 'Aponte JAVA_HOME para a pasta do JDK 25 e reabra o terminal e o app Claude' }
}
else { Mostrar-Aviso 'JAVA_HOME' 'nao definido' 'Nao e obrigatorio se o java do PATH for o 25; se o Maven reclamar, defina JAVA_HOME para o JDK 25' }

# VS Code
if (Tem-Comando 'code') { Mostrar-Ok 'VS Code' 'instalado' }
else { Mostrar-Aviso 'VS Code' 'comando code nao encontrado' 'Parte 1.3 do guia; depois de instalar, reabra o terminal' }

# WSL
if (Tem-Comando 'wsl') {
    $saida = (wsl --version 2>&1 | Out-String)
    $saida = $saida -replace "`0", ''
    if ($saida -match '(WSL|Vers\S+ do WSL)\D*(\d+\.\d+\.\d+)') { Mostrar-Ok 'WSL' $Matches[2] }
    else { Mostrar-Falta 'WSL' 'nao instalado ou antigo' 'Parte 1.4 do guia: wsl --install --no-distribution (como administrador) e reinicie' }
}
else { Mostrar-Falta 'WSL' 'nao instalado' 'Parte 1.4 do guia: wsl --install --no-distribution (como administrador) e reinicie' }

# Docker: comando e motor rodando
if (Tem-Comando 'docker') {
    docker info *> $null
    if ($LASTEXITCODE -eq 0) { Mostrar-Ok 'Docker' 'instalado e rodando' }
    else { Mostrar-Falta 'Docker' 'instalado, mas parado' 'Abra o Docker Desktop (sem administrador) e espere Engine running. Erros: Parte 1.4' }
}
else { Mostrar-Falta 'Docker' 'nao instalado' 'Parte 1.4 do guia: winget install --id Docker.DockerDesktop -e --source winget' }

# Porta do banco: a do projeto (docker-compose.yml da pasta atual) ou 5432 fora de um projeto
$portaBanco = 5432
if (Test-Path -LiteralPath 'docker-compose.yml') {
    $achou = [regex]::Match([System.IO.File]::ReadAllText((Resolve-Path 'docker-compose.yml')), '127\.0\.0\.1:(\d+):5432')
    if ($achou.Success) { $portaBanco = [int]$achou.Groups[1].Value }
}
$conexao = $null
if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) {
    $conexao = Get-NetTCPConnection -LocalPort $portaBanco -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
}
if (-not $conexao) { Mostrar-Ok "Porta do banco $portaBanco" 'livre' }
else {
    $processo = (Get-Process -Id $conexao.OwningProcess -ErrorAction SilentlyContinue).ProcessName
    if ($processo -match 'docker|wslrelay|com\.docker|vpnkit') { Mostrar-Ok "Porta do banco $portaBanco" "usada pelo Docker ($processo): banco de um projeto no ar" }
    else { Mostrar-Falta "Porta do banco $portaBanco" "ocupada por '$processo'" 'Outro programa usa a porta do banco deste projeto; veja a Parte 7.4 do guia' }
}

# Porta da API: a do projeto (server.port do application.yml; sem ele, 8080, o padrao do Spring).
# So dentro de um projeto ja adaptado: no modelo, a porta e escolhida pelo novo-projeto.ps1.
$appYml = 'backend/src/main/resources/application.yml'
if (Test-Path -LiteralPath $appYml) {
    $pom = 'backend/pom.xml'
    if ((Test-Path -LiteralPath $pom) -and ([System.IO.File]::ReadAllText((Resolve-Path $pom))).Contains('nome-do-projeto')) {
        Mostrar-Ok 'Porta da API' 'sera escolhida pelo novo-projeto.ps1'
    }
    else {
        $portaApi = 8080
        $achou = [regex]::Match([System.IO.File]::ReadAllText((Resolve-Path $appYml)), '(?m)^server:[ \t]*\r?\n[ \t]+port:[ \t]*(\d+)')
        if ($achou.Success) { $portaApi = [int]$achou.Groups[1].Value }
        $conexaoApi = $null
        if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) {
            $conexaoApi = Get-NetTCPConnection -LocalPort $portaApi -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
        }
        if (-not $conexaoApi) { Mostrar-Ok "Porta da API $portaApi" 'livre' }
        else {
            $processoApi = (Get-Process -Id $conexaoApi.OwningProcess -ErrorAction SilentlyContinue).ProcessName
            if ($processoApi -match '^java') { Mostrar-Ok "Porta da API $portaApi" "usada pelo Java ($processoApi): a API de um projeto no ar" }
            else {
                Mostrar-Falta "Porta da API $portaApi" "ocupada por '$processoApi'" 'Outro programa usa a porta da API deste projeto. Projeto antigo (8080): rode /trazer-do-modelo no Claude para ganhar uma porta propria. Veja a Parte 10.6 do guia'
            }
        }
    }
}

# Claude Code
if (Tem-Comando 'claude') { Mostrar-Ok 'Claude Code' (Primeira-Linha 'claude' @('--version')) }
else { Mostrar-Falta 'Claude Code' 'nao instalado' 'Parte 2.3 do guia: irm https://claude.ai/install.ps1 | iex' }

# Resumo
Write-Host ""
if ($problemas -eq 0 -and $avisos -eq 0) {
    Write-Host "Tudo certo: o ambiente esta pronto." -ForegroundColor Green
}
elseif ($problemas -eq 0) {
    Write-Host "Ambiente pronto, com $avisos aviso(s). Leia as setas amarelas acima." -ForegroundColor Yellow
}
else {
    Write-Host "Encontrei $problemas problema(s) e $avisos aviso(s). Resolva os itens [FALTA] seguindo as setas acima." -ForegroundColor Red
    exit 1
}
