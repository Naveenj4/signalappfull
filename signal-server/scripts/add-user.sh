#!/usr/bin/env bash
# ==============================================================================
# Helper script to add an allowlisted staff member to Signal Server
# Usage: ./scripts/add-user.sh <phone_number_e164> [verification_code] [notes]
# Example: ./scripts/add-user.sh +15551234567 123456 "Alice Smith - IT"
# ==============================================================================

set -euo pipefail

PHONE="${1:-}"
CODE="${2:-123456}"
NOTES="${3:-Company Staff}"
ADMIN_KEY="${SIGNAL_ADMIN_KEY:-signal-admin-secret}"
SERVER_URL="${SIGNAL_SERVER_URL:-http://127.0.0.1:8080}"

if [ -z "$PHONE" ]; then
    echo "Usage: $0 <phone_number_e164> [verification_code] [notes]"
    echo "Example: $0 +15551234567 123456 \"Alice Smith - Engineering\""
    exit 1
fi

echo "Allowlisting staff number: $PHONE (Code: $CODE, Notes: $NOTES)..."

curl -s -f -X POST "${SERVER_URL}/v1/admin/registrations" \
    -H "Content-Type: application/json" \
    -H "X-Admin-Api-Key: ${ADMIN_KEY}" \
    -d "{
        \"identifier\": \"${PHONE}\",
        \"verificationCode\": \"${CODE}\",
        \"expiresAt\": $(($(date +%s) + 86400 * 30)),
        \"notes\": \"${NOTES}\"
    }" | jq . || true

echo ""
echo "Done! Staff member $PHONE is now authorized to register on this server."
