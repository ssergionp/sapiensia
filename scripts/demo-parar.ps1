# Stops the demo processes by the PIDs saved by demo.ps1 (never by process name). The database keeps running.
$root = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $root '.tmp/demo/pids.txt'
if (-not (Test-Path $pidFile)) {
    Write-Host 'Nenhuma demonstracao ligada.'
    exit 0
}
foreach ($line in Get-Content $pidFile) {
    if ($line -match '^\d+$') {
        taskkill /T /F /PID $line *> $null
    }
}
Remove-Item $pidFile -Force
Write-Host 'Demonstracao parada. O banco continua ligado (just db-stop para desligar).'
