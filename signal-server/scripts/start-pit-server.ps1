# ==============================================================================
# PIT Messenger – Production Server Launch Script (Windows PowerShell)
# ==============================================================================

param(
  [string] $DatabaseHost = "",
  [int] $DatabasePort = 0,
  [string] $DatabaseName = "",
  [string] $DatabaseUser = "",
  [string] $DatabasePassword = ""
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$serverRoot = Split-Path -Parent $PSScriptRoot

function Test-PortOpen([string]$hostAddress, [int]$portNumber) {
  try {
    $client = New-Object System.Net.Sockets.TcpClient
    $iar = $client.BeginConnect($hostAddress, $portNumber, $null, $null)
    $success = $iar.AsyncWaitHandle.WaitOne(1000, $false)
    if ($success -and $client.Connected) {
      $client.EndConnect($iar)
      $client.Close()
      return $true
    }
    $client.Close()
    return $false
  } catch {
    return $false
  }
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " Starting PIT Messenger Server Stack" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Environment Variables
foreach ($envFile in @(
  (Join-Path $serverRoot "config\pit_messenger.local.env"),
  (Join-Path $serverRoot "config\pit-server.env")
)) {
  if (Test-Path $envFile) {
    Write-Host "--> Loading environment config from $envFile..." -ForegroundColor Yellow
    Get-Content $envFile | ForEach-Object {
      $line = $_.Trim()
      if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
        $parts = $line.Split("=", 2)
        $key = $parts[0].Trim()
        $val = $parts[1].Trim()
        [System.Environment]::SetEnvironmentVariable($key, $val, "Process")
      }
    }
  }
}

if ([string]::IsNullOrWhiteSpace($DatabaseHost)) {
  $DatabaseHost = if ($env:SIGNAL_DB_HOST) { $env:SIGNAL_DB_HOST } else { "127.0.0.1" }
}
if ($DatabasePort -le 0) {
  $DatabasePort = if ($env:SIGNAL_DB_PORT) { [int]$env:SIGNAL_DB_PORT } else { 3306 }
}
if ([string]::IsNullOrWhiteSpace($DatabaseName)) {
  $DatabaseName = if ($env:SIGNAL_DB_NAME) { $env:SIGNAL_DB_NAME } else { "pit_messenger" }
}
if ([string]::IsNullOrWhiteSpace($DatabaseUser)) {
  $DatabaseUser = if ($env:SIGNAL_DB_USER) { $env:SIGNAL_DB_USER } else { "pituser" }
}
if ([string]::IsNullOrWhiteSpace($DatabasePassword)) {
  $DatabasePassword = $env:SIGNAL_DB_PASSWORD
}
if ([string]::IsNullOrWhiteSpace($DatabasePassword)) {
  throw "Set SIGNAL_DB_PASSWORD in config\pit_messenger.local.env or pass -DatabasePassword."
}

$env:SIGNAL_DB_HOST = $DatabaseHost
$env:SIGNAL_DB_PORT = "$DatabasePort"
$env:SIGNAL_DB_NAME = $DatabaseName
$env:SIGNAL_DB_USER = $DatabaseUser
$env:SIGNAL_DB_PASSWORD = $DatabasePassword

# 2. Check Redis
$redisRunning = Test-PortOpen "127.0.0.1" 6379
if (-not $redisRunning) {
  Write-Host "--> Starting Redis cluster node from D:\redis..." -ForegroundColor Yellow
  Start-Process -FilePath "D:\redis\redis-server.exe" -ArgumentList "D:\redis\redis-signal.conf" -WindowStyle Minimized
  Start-Sleep -Seconds 2
} else {
  Write-Host "--> Redis is active on port 6379." -ForegroundColor Green
}

# Ensure Redis cluster slots 0-16383 are assigned to local node
$clusterInfo = & "D:\redis\redis-cli.exe" cluster info 2>$null
if ($clusterInfo -match "cluster_slots_assigned:0") {
  Write-Host "--> Initializing 16,384 Redis cluster slots..." -ForegroundColor Yellow
  0..32 | ForEach-Object { $start = $_ * 500; $end = [Math]::Min($start + 499, 16383); & "D:\redis\redis-cli.exe" cluster addslots ($start..$end) 2>$null }
  & "D:\redis\redis-cli.exe" cluster saveconfig 2>$null
}

# 3. Check MySQL
Write-Host "--> Verifying MySQL connection at ${DatabaseHost}:${DatabasePort}..." -ForegroundColor Yellow
$mysqlPortOpen = Test-PortOpen $DatabaseHost $DatabasePort
if (-not $mysqlPortOpen) {
  Write-Error "MySQL port $DatabasePort is not reachable on $DatabaseHost. Verify MySQL service is running."
  exit 1
}

# Optional auth verification if mysql CLI is present
$mysqlCli = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
if (Test-Path $mysqlCli) {
  $env:MYSQL_PWD = $DatabasePassword
  $prevEap = $ErrorActionPreference
  $ErrorActionPreference = "Continue"
  try {
    & $mysqlCli -h $DatabaseHost -P $DatabasePort -u $DatabaseUser -D $DatabaseName -e "SELECT 1;" 2>$null
    if ($LASTEXITCODE -eq 0) {
      Write-Host "--> MySQL credentials and database $DatabaseName verified." -ForegroundColor Green
    } else {
      Write-Host "--> Note: MySQL port is open; credentials will be validated by Dropwizard HikariCP." -ForegroundColor Yellow
    }
  } catch {}
  $ErrorActionPreference = $prevEap
} else {
  Write-Host "--> MySQL port is open and ready." -ForegroundColor Green
}

# 4. Locate Shaded Jar
$jarPath = Get-ChildItem (Join-Path $serverRoot "service\target") -Filter "TextSecureServer-*.jar" |
  Where-Object { $_.Name -notlike "original-*" } |
  Sort-Object LastWriteTime -Descending |
  Select-Object -First 1
if (-not $jarPath) {
  Write-Error "Server JAR not found at $jarPath. Build with .\mvnw.cmd package -Dmaven.test.skip=true -pl :websocket-resources,:service"
  exit 1
}

# 5. Launch Dropwizard Application
$secretsBundle = Join-Path $serverRoot "config\secrets.bundle.yml"
$configFile = Join-Path $serverRoot "config\local.yml"

Write-Host "--> Launching TextSecureServer on port 8080 (Admin: 8081)..." -ForegroundColor Cyan
java "-Dsecrets.bundle.filename=$secretsBundle" -jar "$($jarPath.FullName)" server "$configFile"
