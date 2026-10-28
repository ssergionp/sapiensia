<#
.SYNOPSIS
  Roda "just check" guardando a saida completa em .tmp/check.log e mostra so um resumo.

.DESCRIPTION
  Feito para o Claude gastar menos contexto: ele ve o resumo e so abre o log quando algo falha.
  Resultado igual ao do just check: termina com codigo 0 se tudo passou.
  (Os textos deste script nao tem acento de proposito: o Windows PowerShell 5.1 os mostraria embaralhados.)

.EXAMPLE
  just check-resumo
#>

$ErrorActionPreference = 'Continue'

$raiz = (Get-Location).Path
$pasta = Join-Path $raiz '.tmp'
if (-not (Test-Path -LiteralPath $pasta)) { New-Item -ItemType Directory -Path $pasta | Out-Null }
$log = Join-Path $pasta 'check.log'

# O cmd grava a saida dos programas como ela vem (UTF-8), sem o formato de erro do PowerShell.
$inicio = Get-Date
if ($PSVersionTable.PSEdition -eq 'Desktop' -or $IsWindows) {
    cmd /c "just check > .tmp\check.log 2>&1"
}
else {
    sh -c "just check > .tmp/check.log 2>&1"
}
$codigo = $LASTEXITCODE
$segundos = [int]((Get-Date) - $inicio).TotalSeconds

# Resultado para os hooks do Claude Code (estado da sessao e trava do fim do ticket).
$quando = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$ok = if ($codigo -eq 0) { 'true' } else { 'false' }
$json = '{"ok":' + $ok + ',"quando":' + $quando + ',"segundos":' + $segundos + '}'
[System.IO.File]::WriteAllText((Join-Path $pasta 'check-resultado.json'), $json, (New-Object System.Text.UTF8Encoding($false)))
$linhas = @(Get-Content -LiteralPath $log -Encoding UTF8)

if ($codigo -eq 0) {
    Write-Host "CHECK OK em $segundos s ($($linhas.Count) linhas de log em .tmp/check.log)"
    $linhas | Select-String -Pattern 'Tests run: \d+, Failures', 'Test Files ', '^\s+Tests\s', 'built in' |
        Select-Object -Last 6 | ForEach-Object { '  ' + $_.Line.Trim() }
    exit 0
}

Write-Host "CHECK FALHOU em $segundos s, codigo $codigo ($($linhas.Count) linhas de log em .tmp/check.log)"
Write-Host '--- linhas com erro (ate 40) ---'
$linhas | Select-String -CaseSensitive -Pattern '\[ERROR\]', 'FAIL', 'error:', 'Error:', 'BUILD FAILURE', 'Failures: [1-9]', 'Errors: [1-9]' |
    Select-Object -First 40 | ForEach-Object { $_.Line }
Write-Host '--- ultimas 25 linhas ---'
$linhas | Select-Object -Last 25
exit $codigo
