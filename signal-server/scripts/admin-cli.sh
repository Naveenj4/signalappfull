#!/usr/bin/env bash
# ==============================================================================
# Signal Server Admin CLI Tool
# Manages allowed users, generates OTP codes, enables/disables accounts.
# ==============================================================================

set -euo pipefail

ADMIN_TOKEN="${ADMIN_TOKEN:-${ADMIN_API_KEY:-signal-admin-secret}}"
SERVER_URL="${SERVER_URL:-http://127.0.0.1:8080}"

usage() {
    echo "Signal Server Admin CLI"
    echo "Usage: $0 <command> [arguments]"
    echo ""
    echo "Commands:"
    echo "  list-users                              List all registered/allowlisted users"
    echo "  add-user <phone> [name] [email] [notes] Allowlist a new phone number"
    echo "  remove-user <phone>                     Remove a user from the allowlist"
    echo "  disable-user <phone>                    Disable a user without deleting them"
    echo "  enable-user <phone>                     Re-enable a disabled user"
    echo "  generate-code <phone>                   Generate a fresh 24-hour single-use 6-digit OTP"
    echo "  get-code <phone>                        View the currently active OTP for a user"
    echo ""
    echo "Environment Variables:"
    echo "  ADMIN_TOKEN   Secret admin token (default: signal-admin-secret)"
    echo "  SERVER_URL    Server URL (default: http://127.0.0.1:8080)"
    exit 1
}

if [ $# -lt 1 ]; then
    usage
fi

COMMAND="$1"
shift

case "$COMMAND" in
    list-users)
        curl -s -f -H "X-Admin-Api-Key: $ADMIN_TOKEN" "$SERVER_URL/v1/admin/registrations" | jq . || \
        curl -s -H "X-Admin-Api-Key: $ADMIN_TOKEN" "$SERVER_URL/v1/admin/registrations"
        echo ""
        ;;

    add-user)
        if [ $# -lt 1 ]; then
            echo "Error: Phone number is required (e.g. +1234567890)"
            exit 1
        fi
        PHONE="$1"
        NAME="${2:-}"
        EMAIL="${3:-}"
        NOTES="${4:-}"
        
        BODY=$(jq -n \
            --arg id "$PHONE" \
            --arg name "$NAME" \
            --arg email "$EMAIL" \
            --arg notes "$NOTES" \
            '{identifier: $id, displayName: $name, email: $email, notes: $notes}')

        curl -s -X POST -H "Content-Type: application/json" -H "X-Admin-Api-Key: $ADMIN_TOKEN" \
            -d "$BODY" "$SERVER_URL/v1/admin/registrations" | jq . || true
        echo "--> User $PHONE added/updated successfully."
        ;;

    remove-user)
        if [ $# -lt 1 ]; then
            echo "Error: Phone number is required"
            exit 1
        fi
        PHONE="$1"
        curl -s -X DELETE -H "X-Admin-Api-Key: $ADMIN_TOKEN" "$SERVER_URL/v1/admin/registrations/$PHONE"
        echo "--> User $PHONE removed."
        ;;

    disable-user)
        if [ $# -lt 1 ]; then
            echo "Error: Phone number is required"
            exit 1
        fi
        PHONE="$1"
        curl -s -X PUT -H "X-Admin-Api-Key: $ADMIN_TOKEN" "$SERVER_URL/v1/admin/registrations/$PHONE/disable" | jq . || true
        echo "--> User $PHONE disabled."
        ;;

    enable-user)
        if [ $# -lt 1 ]; then
            echo "Error: Phone number is required"
            exit 1
        fi
        PHONE="$1"
        curl -s -X PUT -H "X-Admin-Api-Key: $ADMIN_TOKEN" "$SERVER_URL/v1/admin/registrations/$PHONE/enable" | jq . || true
        echo "--> User $PHONE enabled."
        ;;

    generate-code)
        if [ $# -lt 1 ]; then
            echo "Error: Phone number is required"
            exit 1
        fi
        PHONE="$1"
        curl -s -X POST -H "X-Admin-Api-Key: $ADMIN_TOKEN" "$SERVER_URL/v1/admin/registrations/$PHONE/generate-code" | jq . || true
        echo ""
        ;;

    get-code)
        if [ $# -lt 1 ]; then
            echo "Error: Phone number is required"
            exit 1
        fi
        PHONE="$1"
        curl -s -H "X-Admin-Api-Key: $ADMIN_TOKEN" "$SERVER_URL/v1/admin/registrations/$PHONE/code" | jq . || true
        echo ""
        ;;

    *)
        usage
        ;;
esac
