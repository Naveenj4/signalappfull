#!/usr/bin/env bash
# ==============================================================================
# Signal-Server MySQL End-to-End Smoke Test Script
# ==============================================================================
# Validates server health, MySQL connectivity, admin allowlist, registration,
# and company security restrictions.
#
# Usage:
#   bash scripts/smoke-test.sh [BASE_URL] [ADMIN_KEY]
# Defaults:
#   BASE_URL:  http://127.0.0.1:8080
#   ADMIN_KEY: company_admin_secret_key_change_in_production
# ==============================================================================

set -euo pipefail

BASE_URL="${1:-http://127.0.0.1:8080}"
ADMIN_PORT="${ADMIN_PORT:-8081}"
ADMIN_KEY="${2:-company_admin_secret_key_change_in_production}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

PASSED=0
FAILED=0

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_pass() {
    echo -e "${GREEN}[PASS]${NC} $1"
    PASSED=$((PASSED + 1))
}

log_fail() {
    echo -e "${RED}[FAIL]${NC} $1"
    FAILED=$((FAILED + 1))
}

log_section() {
    echo ""
    echo -e "${YELLOW}=== $1 ===${NC}"
}

# ------------------------------------------------------------------------------
# Test 1: Dropwizard Ping & Health Check
# ------------------------------------------------------------------------------
log_section "Test 1: Server Health Check"
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:${ADMIN_PORT}/ping" || echo "000")
if [ "$HTTP_CODE" -eq 200 ]; then
    log_pass "Dropwizard admin ping succeeded (HTTP 200)"
else
    log_fail "Dropwizard admin ping failed (HTTP $HTTP_CODE)"
fi

# ------------------------------------------------------------------------------
# Test 2: Admin Allowlist Authorization (Security Check)
# ------------------------------------------------------------------------------
log_section "Test 2: Admin API Authorization"
# 2a: Request with invalid key should be 401
UNAUTH_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
    -H "X-Admin-Api-Key: wrong_key" \
    "${BASE_URL}/v1/admin/registrations" || echo "000")
if [ "$UNAUTH_CODE" -eq 401 ]; then
    log_pass "Invalid admin key correctly rejected with HTTP 401"
else
    log_fail "Invalid admin key gave HTTP $UNAUTH_CODE (expected 401)"
fi

# 2b: Request with valid key should be 200
AUTH_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
    -H "X-Admin-Api-Key: ${ADMIN_KEY}" \
    "${BASE_URL}/v1/admin/registrations" || echo "000")
if [ "$AUTH_CODE" -eq 200 ]; then
    log_pass "Valid admin key accepted with HTTP 200"
else
    log_fail "Valid admin key gave HTTP $AUTH_CODE (expected 200)"
fi

# ------------------------------------------------------------------------------
# Test 3: Add Staff Member to Company Allowlist
# ------------------------------------------------------------------------------
log_section "Test 3: Add Staff to Company Allowlist"
STAFF_PHONE="+15551230001"
ADD_RESP=$(curl -s -w "\n%{http_code}" \
    -X POST \
    -H "X-Admin-Api-Key: ${ADMIN_KEY}" \
    -H "Content-Type: application/json" \
    -d "{\"phoneNumber\": \"${STAFF_PHONE}\", \"note\": \"Smoke Test Engineer\"}" \
    "${BASE_URL}/v1/admin/registrations")

ADD_BODY=$(echo "$ADD_RESP" | head -n -1)
ADD_CODE=$(echo "$ADD_RESP" | tail -n 1)

if [ "$ADD_CODE" -eq 200 ]; then
    log_pass "Staff phone ${STAFF_PHONE} added to allowlist (HTTP 200)"
else
    log_fail "Failed to add ${STAFF_PHONE} to allowlist (HTTP ${ADD_CODE}): ${ADD_BODY}"
fi

# ------------------------------------------------------------------------------
# Test 4: Registration Session for Allowlisted Number
# ------------------------------------------------------------------------------
log_section "Test 4: Registration Flow for Allowlisted User"
SESSION_RESP=$(curl -s -w "\n%{http_code}" \
    -X POST \
    -H "Content-Type: application/json" \
    -d "{\"number\": \"${STAFF_PHONE}\", \"transport\": \"SMS\"}" \
    "${BASE_URL}/v1/verification/session")

SESSION_BODY=$(echo "$SESSION_RESP" | head -n -1)
SESSION_CODE=$(echo "$SESSION_RESP" | tail -n 1)

if [ "$SESSION_CODE" -eq 200 ] || [ "$SESSION_CODE" -eq 201 ]; then
    log_pass "Verification session created successfully for ${STAFF_PHONE} (HTTP ${SESSION_CODE})"
    SESSION_ID=$(echo "$SESSION_BODY" | grep -o '"id":"[^"]*' | cut -d'"' -f4 || echo "")
    
    # Verify fixed test code
    if [ -n "$SESSION_ID" ]; then
        VERIFY_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
            -X PUT \
            -H "Content-Type: application/json" \
            -d '{"code": "123456"}' \
            "${BASE_URL}/v1/verification/session/${SESSION_ID}/code")
        if [ "$VERIFY_CODE" -eq 200 ]; then
            log_pass "Verification code 123456 verified successfully (HTTP 200)"
        else
            log_fail "Verification code submission returned HTTP ${VERIFY_CODE}"
        fi
    fi
else
    log_fail "Verification session creation returned HTTP ${SESSION_CODE}: ${SESSION_BODY}"
fi

# ------------------------------------------------------------------------------
# Test 5: Rejection of Non-Allowlisted Number
# ------------------------------------------------------------------------------
log_section "Test 5: Unauthorized Phone Rejection Check"
OUTSIDER_PHONE="+19999999999"
REJECT_RESP=$(curl -s -w "\n%{http_code}" \
    -X POST \
    -H "Content-Type: application/json" \
    -d "{\"number\": \"${OUTSIDER_PHONE}\", \"transport\": \"SMS\"}" \
    "${BASE_URL}/v1/verification/session")

REJECT_BODY=$(echo "$REJECT_RESP" | head -n -1)
REJECT_CODE=$(echo "$REJECT_RESP" | tail -n 1)

if [ "$REJECT_CODE" -eq 403 ]; then
    log_pass "Non-allowlisted number ${OUTSIDER_PHONE} rejected with HTTP 403 Forbidden"
else
    log_fail "Expected HTTP 403 for unauthorized number, got HTTP ${REJECT_CODE}: ${REJECT_BODY}"
fi

# ------------------------------------------------------------------------------
# Test 6: One-Time Admin Code Generation
# ------------------------------------------------------------------------------
log_section "Test 6: Admin One-Time Verification Code Generation"
STAFF_PHONE_2="+15551230002"
# Add second user
curl -s -X POST \
    -H "X-Admin-Api-Key: ${ADMIN_KEY}" \
    -H "Content-Type: application/json" \
    -d "{\"phoneNumber\": \"${STAFF_PHONE_2}\", \"note\": \"Second Test User\"}" \
    "${BASE_URL}/v1/admin/registrations" > /dev/null

CODE_RESP=$(curl -s -w "\n%{http_code}" \
    -X POST \
    -H "X-Admin-Api-Key: ${ADMIN_KEY}" \
    "${BASE_URL}/v1/admin/registrations/${STAFF_PHONE_2}/code")

CODE_BODY=$(echo "$CODE_RESP" | head -n -1)
CODE_HTTP=$(echo "$CODE_RESP" | tail -n 1)

if [ "$CODE_HTTP" -eq 200 ] && echo "$CODE_BODY" | grep -q "verificationCode"; then
    GENERATED_CODE=$(echo "$CODE_BODY" | grep -o '"verificationCode":"[^"]*' | cut -d'"' -f4)
    log_pass "One-time code generated successfully: ${GENERATED_CODE} (HTTP 200)"
else
    log_fail "Failed to generate one-time code (HTTP ${CODE_HTTP}): ${CODE_BODY}"
fi

# ------------------------------------------------------------------------------
# Summary
# ------------------------------------------------------------------------------
echo ""
echo "=============================================================================="
echo "Smoke Test Summary: ${PASSED} Passed, ${FAILED} Failed"
echo "=============================================================================="

if [ "$FAILED" -eq 0 ]; then
    echo -e "${GREEN}ALL TESTS PASSED! Signal Server is functioning properly on MySQL.${NC}"
    exit 0
else
    echo -e "${RED}SOME TESTS FAILED. Please check the logs above.${NC}"
    exit 1
fi
