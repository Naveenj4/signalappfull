# ==============================================================================
# PIT Messenger – 2Factor.in Live Cellular SMS Verification Test Script
# ==============================================================================

param(
  [Parameter(Mandatory=$true)]
  [string] $ApiKey,

  [Parameter(Mandatory=$true)]
  [string] $DestinationPhone,

  [string] $TemplateName = "",

  [string] $BaseUrl = "https://2factor.in/API/V1/"
)

$ErrorActionPreference = "Stop"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " PIT Messenger – Testing Real 2Factor.in SMS Delivery" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Clean Phone Number (Strip + and spaces)
$cleanPhone = $DestinationPhone -replace '[^0-9]', ''

# 2. Generate a random 6-digit test code
$testCode = (Get-Random -Minimum 100000 -Maximum 999999).ToString()

# 3. Construct 2Factor endpoint
$trimmedBase = if ($BaseUrl.EndsWith("/")) { $BaseUrl } else { "$BaseUrl/" }
if ($TemplateName -and $TemplateName.Trim() -ne "") {
  $encodedTemplate = [System.Uri]::EscapeDataString($TemplateName.Trim())
  $endpoint = "${trimmedBase}${ApiKey}/SMS/${cleanPhone}/${testCode}/${encodedTemplate}"
} else {
  $endpoint = "${trimmedBase}${ApiKey}/SMS/${cleanPhone}/${testCode}"
}

Write-Host "--> Destination: $DestinationPhone (Clean: $cleanPhone)" -ForegroundColor Yellow
Write-Host "--> Generated Test OTP: $testCode" -ForegroundColor Yellow
Write-Host "--> Contacting 2Factor.in API..." -ForegroundColor Yellow

try {
  $response = Invoke-RestMethod -Uri $endpoint -Method Get -TimeoutSec 15
  Write-Host "==========================================================" -ForegroundColor Green
  Write-Host " 2Factor.in API Response:" -ForegroundColor Green
  Write-Host " Status:  $($response.Status)" -ForegroundColor Green
  Write-Host " Details: $($response.Details)" -ForegroundColor Green
  Write-Host "==========================================================" -ForegroundColor Green

  if ($response.Status -eq "Success") {
    Write-Host "SUCCESS! An SMS has been dispatched by 2Factor.in." -ForegroundColor Green
    Write-Host "Check your mobile phone for code: $testCode" -ForegroundColor Cyan
  } else {
    Write-Host "Warning: 2Factor returned non-success status: $($response.Details)" -ForegroundColor Yellow
  }
} catch {
  Write-Host "FAILED: Unable to dispatch SMS via 2Factor.in" -ForegroundColor Red
  Write-Host "Error Details: $_" -ForegroundColor Red
  if ($_.Exception.Response) {
    $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
    Write-Host "Server Response: $($reader.ReadToEnd())" -ForegroundColor Red
  }
}
