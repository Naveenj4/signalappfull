# PIT Messenger – 2Factor.in Setup & Configuration Guide

## 1. Prerequisites (Owner Setup)
To enable real-time SMS delivery on PIT Messenger, the project owner completes the following steps in their [2Factor.in Console](https://2factor.in):

1. **Account Registration & KYC**:
   - Register at https://2factor.in.
   - Complete standard DLT / Business KYC requirements for Indian GSM SMS termination.
2. **Retrieve API Key**:
   - Navigate to **API Keys / Credentials** in the 2Factor dashboard.
   - Copy the production API Key (format: `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`).
3. **Sender ID & DLT Template**:
   - Approved Sender ID (e.g., `PITMSG`).
   - Approved Template format:
     ```
     Your PIT Messenger verification code is {#var#}. Valid for 10 minutes. Do not share this OTP with anyone.
     ```
   - Note down the approved Template Name (e.g., `PIT_OTP_VERIF`).

---

## 2. Secure Local Environment Configuration

Never commit live API keys to Git. Use environment variables or a local `.env` file that is listed in `.gitignore`.

### 2.1. Server Environment Variables
Create or update `D:\signalappppp\signal-server\config\pit-server.env`:

```bash
# ==============================================================================
# PIT Messenger – 2Factor.in SMS Configuration (CONFIDENTIAL)
# ==============================================================================

# 2Factor API Key from your dashboard
TWOFACTOR_API_KEY=your_actual_2factor_api_key_here

# 2Factor Base URL (Default production endpoint)
TWOFACTOR_API_BASE_URL=https://2factor.in/API/V1/

# Approved SMS Template Name
TWOFACTOR_OTP_TEMPLATE=PIT_OTP_VERIF

# Operational Mode: "custom" (App-managed OTP) or "autogen" (Provider-managed OTP)
TWOFACTOR_MODE=custom

# OTP Security Parameters
OTP_EXPIRY_SECONDS=600
OTP_RESEND_COOLDOWN_SECONDS=60
OTP_MAX_ATTEMPTS=5

# Fallback Developer Mode (Set to false for real cellular delivery)
TWOFACTOR_MOCK_FALLBACK=false
```

---

## 3. Verification & Live Smoke Test Procedure

Once the API key is placed into `pit-server.env`:

1. Run the live test command:
   ```powershell
   cd D:\signalappppp\signal-server
   .\scripts\test-2factor-sms.ps1 -ApiKey "YOUR_API_KEY" -DestinationPhone "+91XXXXXXXXXX"
   ```
2. Verify that:
   - Terminal outputs: `HTTP 200 OK - Status: Success`.
   - The test phone receives the SMS within 5–15 seconds.
   - The OTP value matches the generated code.
