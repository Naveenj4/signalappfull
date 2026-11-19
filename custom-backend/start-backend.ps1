# PIT Messenger – Start Custom OTP & Registration Backend
$env:PORT = "8088"
$env:DB_HOST = "127.0.0.1"
$env:DB_PORT = "3306"
$env:DB_NAME = "pit_messenger"
$env:DB_USER = "pituser"
$env:DB_PASSWORD = "pitpassword!"

cd $PSScriptRoot
Write-Host "Starting PIT Messenger Custom Backend on port 8088..." -ForegroundColor Cyan
node server.js
