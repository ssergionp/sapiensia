<#
.SYNOPSIS
  Limpa o que sobrou de um ticket depois do merge da PR: processos, worktree, branch local e main desatualizada.

.DESCRIPTION
  Rode na pasta principal do projeto (nao dentro da worktree), depois que a PR do ticket foi mergeada.
  Passos, sempre na mesma ordem:
    1. confere que a PR da branch foi mergeada (sem isso, nao apaga nada)
    2. encerra os processos que usam a pasta da worktree (API, Vite, servidores de TypeScript), junto com os que eles abriram
    3. remove a worktree, mesmo com caminho longo ou pasta presa
    4. volta para a main e atualiza (git pull)
    5. apaga a branch local e as pastas de rascunho do Superpowers (.superpowers/sdd) dos planos que ja estao na main
  A branch no GitHub o proprio GitHub apaga no merge; se ainda existir, o script so avisa.
  (Os textos deste script nao tem acento de proposito: o Windows PowerShell 5.1 os mostraria embaralhados.)

.EXAMPLE
  just limpar-ticket
  just limpar-ticket feat/12-editar-produto
#>
param(
    [string]$Branch = ''
)

$ErrorActionPreference = 'Continue'

function Passo([string]$texto) { Write-Host ""; Write-Host "==> $texto" -ForegroundColor Cyan }
function Ok([string]$texto) { Write-Host "    OK: $texto" -ForegroundColor Green }
function Aviso([string]$texto) { Write-Host "    AVISO: $texto" -ForegroundColor Yellow }
function Falha([string]$texto) {
    Write-Host ""
    Write-Host "PAREI: $texto" -ForegroundColor Red
    exit 1
}

$raiz = (Get-Location).Path
$comum = (& git rev-parse --git-common-dir 2>$null)
$pastaGit = (& git rev-parse --absolute-git-dir 2>$null)
if (-not $comum) { Falha "esta pasta nao e um repositorio git." }
if ((Resolve-Path -LiteralPath $comum).Path -ne (Resolve-Path -LiteralPath $pastaGit).Path) {
    Falha "voce esta dentro de uma worktree. Rode na pasta principal do projeto."
}

# ---------------------------------------------------------------------------
Passo "Achando a worktree do ticket"
$worktrees = @()
$atual = $null
foreach ($linha in (& git worktree list --porcelain)) {
    if ($linha -like 'worktree *') { $atual = @{ pasta = $linha.Substring(9); branch = '' }; $worktrees += $atual }
    elseif ($linha -like 'branch refs/heads/*' -and $atual) { $atual.branch = $linha.Substring(18) }
}
$extras = @($worktrees | Select-Object -Skip 1)
if (-not $Branch) {
    if ($extras.Count -eq 1) { $Branch = $extras[0].branch }
    elseif ($extras.Count -eq 0) { Falha "nenhuma worktree aberta. Diga qual branch limpar: just limpar-ticket feat/12-assunto" }
    else { Falha ("ha mais de uma worktree; diga qual branch limpar: " + (($extras | ForEach-Object { $_.branch }) -join ', ')) }
}
$alvo = $extras | Where-Object { $_.branch -eq $Branch } | Select-Object -First 1
$pastaWorktree = if ($alvo) { $alvo.pasta } else { $null }
Ok ("branch " + $Branch + $(if ($pastaWorktree) { ", worktree em $pastaWorktree" } else { ", sem worktree" }))

# ---------------------------------------------------------------------------
Passo "Conferindo que a PR foi mergeada"
$mergeada = (& gh pr list --head $Branch --state merged --json number --jq '.[0].number' 2>$null)
if (-not $mergeada) { Falha "nao achei PR mergeada para a branch $Branch. Nada foi apagado." }
Ok "PR #$mergeada mergeada"

# ---------------------------------------------------------------------------
Passo "Encerrando processos que usam a worktree"
if ($pastaWorktree -and ($PSVersionTable.PSEdition -eq 'Desktop' -or $IsWindows)) {
    $janela = $pastaWorktree.Replace('/', '\')
    $processos = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object { $_.ProcessId -ne $PID -and $_.CommandLine -and ($_.CommandLine.Replace('/', '\') -like "*$janela*") })
    foreach ($p in $processos) {
        # /T encerra tambem os processos filhos: sem isso, a API (java) fica rodando depois que o Maven sai
        & taskkill /T /F /PID $p.ProcessId 2>$null | Out-Null
        Ok "encerrado $($p.Name) (PID $($p.ProcessId)) e os processos abertos por ele"
    }
    if ($processos.Count -eq 0) { Ok "nenhum" }
}
else {
    Ok "nada a fazer"
}

# ---------------------------------------------------------------------------
Passo "Removendo a worktree"
if ($pastaWorktree) {
    & git worktree remove --force $pastaWorktree 2>$null
    if (Test-Path -LiteralPath $pastaWorktree) {
        $longo = if ($PSVersionTable.PSEdition -eq 'Desktop' -or $IsWindows) { '\\?\' + $pastaWorktree.Replace('/', '\') } else { $pastaWorktree }
        Remove-Item -LiteralPath $longo -Recurse -Force -ErrorAction SilentlyContinue
    }
    & git worktree prune
    if (Test-Path -LiteralPath $pastaWorktree) { Falha "a pasta $pastaWorktree continua presa. Se uma sessao do Claude esta dentro dela, saia da worktree (ferramenta ExitWorktree, mantendo a pasta). Feche tambem o VS Code, o IntelliJ ou outro programa aberto nela e rode de novo." }
    Ok "worktree removida"
}
else {
    Ok "nada a fazer"
}

# ---------------------------------------------------------------------------
Passo "Atualizando a main"
$sujo = (& git status --porcelain)
if ($sujo) { Falha "a pasta principal tem alteracoes nao commitadas. Resolva antes:`n$($sujo -join "`n")" }
& git switch main 2>&1 | Out-Null
& git pull --ff-only origin main 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Aviso "o git pull nao conseguiu atualizar a main sozinho; confira com git status." }
else { Ok ("main em " + (& git log -1 --format='%h %s')) }

# ---------------------------------------------------------------------------
Passo "Apagando a branch local"
& git show-ref --verify --quiet "refs/heads/$Branch"
if ($LASTEXITCODE -eq 0) {
    # com merge squash, o git nao reconhece a branch como mergeada: por isso -D, depois de conferir a PR acima
    & git branch -D $Branch 2>&1 | Out-Null
    Ok "branch $Branch apagada"
}
else {
    Ok "ja nao existia"
}
# rascunhos do subagent-driven-development: .superpowers/sdd/<plano>, ignorados pelo git
$pastaSdd = Join-Path $raiz '.superpowers\sdd'
if (Test-Path -LiteralPath $pastaSdd) {
    foreach ($d in (Get-ChildItem -LiteralPath $pastaSdd -Directory)) {
        if (Test-Path -LiteralPath (Join-Path $raiz "docs\superpowers\plans\$($d.Name).md")) {
            Remove-Item -LiteralPath $d.FullName -Recurse -Force -ErrorAction SilentlyContinue
            Ok "rascunho .superpowers/sdd/$($d.Name) apagado"
        }
    }
}
& git fetch --prune --quiet 2>$null
if (& git ls-remote --heads origin $Branch 2>$null) {
    Aviso "a branch $Branch ainda existe no GitHub. Para apagar: git push origin --delete $Branch"
}

Passo "Ticket limpo."
exit 0
