# Stops the demo processes by the PIDs saved by demo.ps1 (never by process name). The database keeps running.
$root = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $root '.tmp/demo/pids.txt'
if (-not (Test-Path $pidFile)) {
    Write-Host 'Nenhuma demonstracao ligada.'
    exit 0
}
$writtenAt = (Get-Item $pidFile).LastWriteTime
foreach ($line in Get-Content $pidFile) {
    if ($line -notmatch '^\d+$') { continue }
    # Safety: a stale file (e.g. after a reboot) may hold a PID reused by an unrelated process.
    # Only kill a live cmd/java/node process that started before the file was last written.
    $proc = Get-Process -Id ([int]$line) -ErrorAction SilentlyContinue
    if ($proc -and @('cmd', 'java', 'node') -contains $proc.ProcessName -and $proc.StartTime -le $writtenAt) {
        taskkill /T /F /PID $line *> $null
    }
}
Remove-Item $pidFile -Force
Write-Host 'Demonstracao parada. O banco continua ligado (just db-stop para desligar).'
