<#
.SYNOPSIS
  Jomhor local stack for testing (docs/TESTING.md): Postgres, backend (dev profile, demo data, fixed OTP), web shop
  and back-office.
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\dev.ps1          # start
  powershell -ExecutionPolicy Bypass -File scripts\dev.ps1 stop     # stop, keep the data
  powershell -ExecutionPolicy Bypass -File scripts\dev.ps1 reset    # stop and wipe the database
.NOTES
  Ports default to 5435 / 8090 / 3000 / 5173; a busy one moves to the next free port (the summary prints them).
  Logs and state: scripts\.dev\. Dev-only values: nothing here is a real secret.
#>
param([ValidateSet('start', 'stop', 'reset')][string]$Command = 'start')

$ErrorActionPreference = 'Stop'
$Root = Split-Path $PSScriptRoot -Parent
$StateDir = Join-Path $PSScriptRoot '.dev'
$StateFile = Join-Path $StateDir 'state.json'
$Compose = @('compose', '-p', 'jomhor-dev', '-f', (Join-Path $Root 'backend\compose.yaml'))
$FixedOtp = '123456'

function Test-PortBusy([int]$Port) {
    [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

function Get-FreePort([int]$Port) {
    while (Test-PortBusy $Port) { $Port++ }
    $Port
}

function Stop-Tree([int]$ProcessId) {
    $ErrorActionPreference = 'Continue' # Windows PowerShell turns redirected native stderr into errors
    if (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue) {
        & taskkill.exe /T /F /PID $ProcessId 2>&1 | Out-Null
    }
}

function Stop-Stack {
    if (Test-Path $StateFile) {
        $state = Get-Content $StateFile -Raw | ConvertFrom-Json
        foreach ($p in $state.pids) { Stop-Tree $p }
        # bootRun's JVM is a child of the Gradle daemon, not of the launcher: stop whatever listens on our ports.
        foreach ($port in @($state.api_port, $state.shop_port, $state.bo_port)) {
            Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
                ForEach-Object { Stop-Tree $_.OwningProcess }
        }
        Remove-Item $StateFile
    }
    & docker @Compose stop | Out-Null
    Write-Host 'Jomhor stopped.'
}

function Wait-Url([string]$Name, [string]$Url, [string]$Expect, [int]$Seconds, [string]$Log) {
    Write-Host -NoNewline "Waiting for $Name "
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
            if (-not $Expect -or $r.Content -like "*$Expect*") { Write-Host 'ok'; return }
        } catch { }
        Write-Host -NoNewline '.'
        Start-Sleep -Seconds 2
    }
    Write-Host ''
    throw "$Name did not answer at $Url within $Seconds s. Log: $Log"
}

function Start-Logged([string]$Name, [string]$File, [string[]]$Arguments, [string]$Dir) {
    Start-Process -FilePath $File -ArgumentList $Arguments -WorkingDirectory $Dir -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $StateDir "$Name.log") -RedirectStandardError (Join-Path $StateDir "$Name.err.log")
}

if ($Command -ne 'start') {
    Stop-Stack
    if ($Command -eq 'reset') {
        & docker @Compose down -v | Out-Null
        Write-Host 'Database wiped: the next start re-creates the demo data.'
    }
    exit 0
}

if (Test-Path $StateFile) {
    throw "Jomhor seems to be running already ($StateFile). Run 'scripts\dev.ps1 stop' first."
}
New-Item -ItemType Directory -Force $StateDir | Out-Null

# Postgres: keep the port of a running container, else the first free one from 5432.
$dbPort = $null
$mapped = & { $ErrorActionPreference = 'Continue'; & docker @Compose port postgres 5432 2>$null }
if ($LASTEXITCODE -eq 0 -and $mapped) { $dbPort = [int]($mapped -split ':')[-1] } else { $dbPort = Get-FreePort 5435 }
$apiPort = Get-FreePort 8090 # 8090, not 8080: the phone apps are built for http://<lan>:8090/v1 (docs/TESTING.md)
$shopPort = Get-FreePort 3000
$boPort = Get-FreePort 5173

$lan = (Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq 'Up' } |
        Select-Object -First 1).IPv4Address.IPAddress
if (-not $lan) { $lan = 'localhost' }

Write-Host "Starting Postgres on 127.0.0.1:$dbPort"
$env:DB_PORT = "$dbPort"
& docker @Compose up -d --wait
if ($LASTEXITCODE -ne 0) { throw 'docker compose up failed (is Docker Desktop running?)' }

# Backend: dev profile, every interface (a phone on the LAN uses http://<lan>:<port>/v1). Card payment pages and
# redirects use the LAN address, so they work from a phone and from this PC alike.
$publicBase = "http://${lan}:$apiPort/v1"
$env:SPRING_PROFILES_ACTIVE = 'dev'
$env:SERVER_ADDRESS = '0.0.0.0'
$env:SERVER_PORT = "$apiPort"
$env:DB_URL = "jdbc:postgresql://localhost:$dbPort/jomhor"
$env:JOMHOR_DEV_FIXED_OTP = $FixedOtp
$env:PUBLIC_BASE_URL = $publicBase
$env:PAYMENTS_RETURN_URL_WEB = "http://localhost:$shopPort/orders/{order_id}"
$backend = Start-Logged 'backend' (Join-Path $Root 'backend\gradlew.bat') @('bootRun', '--console=plain') (Join-Path $Root 'backend')

# Web: install only when node_modules is missing.
foreach ($app in 'shop', 'backoffice') {
    $dir = Join-Path $Root "web\$app"
    if (-not (Test-Path (Join-Path $dir 'node_modules'))) {
        Write-Host "npm ci in web\$app"
        Push-Location $dir; try { & npm.cmd ci; if ($LASTEXITCODE -ne 0) { throw "npm ci failed in web\$app" } } finally { Pop-Location }
    }
}

# Shop: talks to the backend on localhost (the backend trusts X-Forwarded-For from loopback only); no proxy in front.
$env:API_BASE_URL = "http://localhost:$apiPort/v1"
$env:API_PUBLIC_URL = $publicBase
$env:SHOP_ORIGIN = "http://localhost:$shopPort"
$env:TRUSTED_PROXY_HOPS = '0'
$shop = Start-Logged 'shop' 'npm.cmd' @('run', 'dev', '--', '-p', "$shopPort") (Join-Path $Root 'web\shop')

$env:API_PROXY_TARGET = "http://localhost:$apiPort"
$bo = Start-Logged 'backoffice' 'npm.cmd' @('run', 'dev', '--', '--port', "$boPort", '--strictPort') (Join-Path $Root 'web\backoffice')

@{ pids = @($backend.Id, $shop.Id, $bo.Id); api_port = $apiPort; shop_port = $shopPort; bo_port = $boPort } |
    ConvertTo-Json | Set-Content $StateFile

try {
    # The demo events exist once DevSeeder has run (after the web server is up).
    Wait-Url 'backend' "http://localhost:$apiPort/v1/fan/events" 'Rapid Oued' 300 `
        (Join-Path $StateDir 'backend.log')
    Wait-Url 'shop' "http://localhost:$shopPort/" '' 180 (Join-Path $StateDir 'shop.log')
    Wait-Url 'back-office' "http://localhost:$boPort/" '' 60 (Join-Path $StateDir 'backoffice.log')
} catch {
    Write-Host $_ -ForegroundColor Red
    Stop-Stack
    exit 1
}

Write-Host ''
Write-Host 'Jomhor is running (dev data, fake payments).' -ForegroundColor Green
Write-Host "  Web shop      http://localhost:$shopPort"
Write-Host "  Back-office   http://localhost:$boPort"
Write-Host "  API           http://localhost:$apiPort/v1    (phones on the LAN: $publicBase)"
Write-Host "  Postgres      127.0.0.1:$dbPort  db/user/password jomhor"
Write-Host ''
Write-Host "  OTP for every phone: $FixedOtp (the real code is also in scripts\.dev\backend.log)"
Write-Host '  Admin       +212600000001    Cashier  +212600000002'
Write-Host '  Ops         +212600000003    Finance  +212600000004'
Write-Host '  Fan (tickets) +212600000010  Fan (none) +212600000011'
Write-Host '  Scanner PINs: Agent Porte 1234 (GATE), Chef de porte 5678 (SUPERVISOR)'
Write-Host ''
Write-Host 'Stop: scripts\dev.ps1 stop    Wipe data: scripts\dev.ps1 reset    Guide: docs\TESTING.md'
