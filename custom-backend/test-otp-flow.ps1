# PIT Messenger – End-to-End OTP Verification Flow Test
param(
  [string] $PhoneNumber = "+15551234567"
)

$baseUrl = "http://127.0.0.1:8088"

Write-Host "=== 1. Checking Backend Health ===" -ForegroundColor Cyan
$health = Invoke-RestMethod -Uri "$baseUrl/v1/health"
Write-Host "Service Status: $($health.status), Database: $($health.database)" -ForegroundColor Green

Write-Host "=== 2. Requesting OTP for $PhoneNumber ===" -ForegroundColor Cyan
$body = @{ e164 = $PhoneNumber; transport = "sms" } | ConvertTo-Json
$otp = Invoke-RestMethod -Uri "$baseUrl/v1/registration/request-code" -Method Post -Body $body -ContentType "application/json"
Write-Host "Session Created: $($otp.session_id)" -ForegroundColor Green
if ($otp.dev_code) {
  Write-Host "Development code returned by server: $($otp.dev_code)" -ForegroundColor Yellow
} else {
  Write-Host "A real SMS/OTP was requested; enter the code received on the phone." -ForegroundColor Yellow
}

Write-Host "=== 3. Submitting Verification Code ===" -ForegroundColor Cyan
$code = Read-Host "Enter the OTP received on $PhoneNumber"
$verifyBody = @{ session_id = $otp.session_id; code = $code } | ConvertTo-Json
$verify = Invoke-RestMethod -Uri "$baseUrl/v1/registration/verify-code" -Method Post -Body $verifyBody -ContentType "application/json"
Write-Host "Verified: $($verify.verified)" -ForegroundColor Green
Write-Host "Verification Token Issued: $($verify.verification_token)" -ForegroundColor Green

Write-Host "=== 4. Inspecting MySQL Record ===" -ForegroundColor Cyan
$session = Invoke-RestMethod -Uri "$baseUrl/v1/sessions/$($otp.session_id)"
$session | Format-List
