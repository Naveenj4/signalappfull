# PIT Messenger – Start Custom OTP & Registration Backend
$env:PORT = "8088"
$envFile = Join-Path (Split-Path $PSScriptRoot -Parent) "signal-server\config\pit-server.env"
if (Test-Path $envFile) {
  Get-Content $envFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
      $parts = $line.Split("=", 2)
      [System.Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim(), "Process")
    }
  }
}

cd $PSScriptRoot
Write-Host "Starting PIT Messenger Custom Backend on port 8088..." -ForegroundColor Cyan
node server.js
