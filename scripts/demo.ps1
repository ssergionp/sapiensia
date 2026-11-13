# Starts the local demo: database, API (demo seeders on) and frontend, all in the background.
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$demoDir = Join-Path $root '.tmp/demo'
New-Item -ItemType Directory -Force -Path $demoDir | Out-Null

function Stop-Demo([string]$message) {
    Write-Host $message
    exit 1
}

# 1. Free memory (KB -> GB)
$freeGb = [math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1MB, 1)
if ($freeGb -lt 2) {
    Stop-Demo "Pouca memoria livre: $freeGb GB. Feche programas e tente de novo (precisa de 2 GB)."
}

# 2. Docker running
docker info *> $null
if ($LASTEXITCODE -ne 0) {
    Stop-Demo 'O Docker nao esta aberto. Abra o Docker Desktop e tente de novo.'
}

# 3. Ports free
foreach ($port in 8081, 5173) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
        Stop-Demo "A porta $port ja esta em uso (just dev-back ou dev-front ligado?). Pare e tente de novo."
    }
}

# 4. Database up and ready. A database already published on 5433 (for example the one of the main folder,
# which is also the one to use from a git worktree) is reused; otherwise docker compose starts one.
$dbId = (docker ps --filter 'publish=5433' --format '{{.ID}}' | Select-Object -First 1)
if (-not $dbId) {
    docker compose up -d
    $dbId = (docker compose ps -q db | Select-Object -First 1)
}
if (-not $dbId) { Stop-Demo 'O banco nao subiu. Veja: docker compose logs db' }
$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    docker exec $dbId pg_isready -U app *> $null
    if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    Start-Sleep -Seconds 2
}
if (-not $ready) { Stop-Demo 'O banco nao ficou pronto em 2 minutos. Veja: docker compose logs db' }

# 5. Separate demo database, created once
$exists = docker exec $dbId psql -U app -d app -tAc "SELECT 1 FROM pg_database WHERE datname='app_demo'"
if (-not ("$exists".Trim() -eq '1')) {
    docker exec $dbId psql -U app -d app -c 'CREATE DATABASE app_demo' | Out-Null
}

# 6. API in the background (env vars are inherited by the child process)
$env:DB_URL = 'jdbc:postgresql://localhost:5433/app_demo'
$env:APP_DEMO_ENABLED = 'true'
$env:APP_STORAGE_FILE_DIR = '../.tmp/demo/storage'
$env:APP_EMAIL_OUTBOX_DIR = '../.tmp/demo/outbox'
$pidFile = Join-Path $demoDir 'pids.txt'
Remove-Item $pidFile -Force -ErrorAction SilentlyContinue   # ports are free, so any old file is stale
$api = Start-Process -FilePath 'cmd.exe' `
    -ArgumentList '/c', '.\mvnw.cmd', 'spring-boot:run', '-Dspring-boot.run.profiles=local' `
    -WorkingDirectory (Join-Path $root 'backend') -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $demoDir 'api.log') -RedirectStandardError (Join-Path $demoDir 'api.err.log')
Add-Content -Path $pidFile -Value $api.Id   # saved right away, so demo-parar can always find it

# 7. Wait for the API health check (up to 5 minutes)
$up = $false
for ($i = 0; $i -lt 100; $i++) {
    try {
        $r = Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:8081/api/system/health' -TimeoutSec 3
        if ($r.StatusCode -eq 200) { $up = $true; break }
    } catch { }
    if ($api.HasExited) { break }
    Start-Sleep -Seconds 3
}
if (-not $up) {
    taskkill /T /F /PID $api.Id *> $null
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
    Stop-Demo 'A API nao subiu em 5 minutos. Veja .tmp/demo/api.log'
}

# 8. Frontend in the background, wait for port 5173
$front = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', 'pnpm', 'dev' `
    -WorkingDirectory (Join-Path $root 'frontend') -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $demoDir 'front.log') -RedirectStandardError (Join-Path $demoDir 'front.err.log')
Add-Content -Path $pidFile -Value $front.Id
$frontUp = $false
for ($i = 0; $i -lt 60; $i++) {
    if (Get-NetTCPConnection -LocalPort 5173 -State Listen -ErrorAction SilentlyContinue) { $frontUp = $true; break }
    Start-Sleep -Seconds 2
}
if (-not $frontUp) {
    taskkill /T /F /PID $front.Id *> $null
    taskkill /T /F /PID $api.Id *> $null
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
    Stop-Demo 'O frontend nao subiu em 2 minutos. Veja .tmp/demo/front.log'
}

# 9. (PIDs were saved right after each Start-Process)

# 10. Print the block (code from the single source of the algorithm)
$code = (node scripts/demo-codigo.mjs).Trim()
Write-Host 'Demonstracao pronta. Abra no navegador: http://localhost:5173'
Write-Host 'Cliente:        cliente@example.com  /  Demo-Cliente-2026!' # gitleaks:allow
Write-Host 'Administrador:  http://localhost:5173/painel/entrar'
Write-Host '                admin@example.com  /  Demo-Admin-2026!' # gitleaks:allow
Write-Host "                codigo de verificacao agora: $code (vale ~30 s; outro: just demo-codigo)"
Write-Host '                chave para app autenticador: JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP' # gitleaks:allow
Write-Host 'Para parar: just demo-parar'
