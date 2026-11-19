#!/usr/bin/env bash
# ==============================================================================
# Signal Server Cryptographic Keys & Secrets Generator
# Generates zero-knowledge server params, unidentified sender keys, and admin tokens
# ==============================================================================

set -euo pipefail

echo "=========================================================="
echo " Generating Cryptographic Keys and Secrets"
echo "=========================================================="

# Helper function to generate 32 random bytes in base64
gen_b64_32() {
    openssl rand -base64 32
}

# Helper function to generate 32 random hex bytes
gen_hex_32() {
    openssl rand -hex 32
}

CHAT_ZK_SECRET=$(gen_b64_32)
CALLING_ZK_SECRET=$(gen_b64_32)
UD_PRIVATE_KEY=$(gen_b64_32)
LINK_DEVICE_SECRET=$(gen_b64_32)
ADMIN_KEY=$(gen_hex_32)
DB_PASS=$(gen_hex_32)
TURN_SECRET=$(gen_hex_32)

echo "--> Generated fresh cryptographic keys."

# Update config/secrets.bundle.yml
cat << EOF > config/secrets.bundle.yml
# ==============================================================================
# Signal Server Secrets Bundle (Auto-generated)
# ==============================================================================
tlsKeyStore.password: "signal_tls_password"
stripe.apiKey: "dummy_stripe_api_key"
stripe.idempotencyKeyGenerator: "dummy_idempotency_key"
braintree.publicKey: "dummy_braintree_public"
braintree.privateKey: "dummy_braintree_private"
appleAppStore.encodedKey: "dummy_apple_key"
directoryV2.client.userAuthenticationTokenSharedSecret: "${CHAT_ZK_SECRET}"
directoryV2.client.userIdTokenSharedSecret: "${CHAT_ZK_SECRET}"
svr2.userAuthenticationTokenSharedSecret: "${CHAT_ZK_SECRET}"
svr2.userIdTokenSharedSecret: "${CHAT_ZK_SECRET}"
svrb.userAuthenticationTokenSharedSecret: "${CHAT_ZK_SECRET}"
svrb.userIdTokenSharedSecret: "${CHAT_ZK_SECRET}"
gcpAttachments.rsaSigningKey: "dummy_rsa_key"
tus.userAuthenticationTokenSharedSecret: "${CHAT_ZK_SECRET}"
apn.keyId: "dummy_apn_key_id"
apn.teamId: "dummy_apn_team_id"
apn.signingKey: "dummy_apn_signing_key"
fcm.credentials: "{}"
cdn.accessKey: "minioadmin"
cdn.accessSecret: "minio_strong_password"
cdn3StorageManager.clientSecret: "${CHAT_ZK_SECRET}"
unidentifiedDelivery.privateKey: "${UD_PRIVATE_KEY}"
storageService.userAuthenticationTokenSharedSecret: "${CHAT_ZK_SECRET}"
callingZkConfigPreV101.serverSecret: "${CALLING_ZK_SECRET}"
callingZkConfigV101.serverSecret: "${CALLING_ZK_SECRET}"
chatZkConfig.serverSecret: "${CHAT_ZK_SECRET}"
zkConfig-libsignal-0.42.serverSecret: "${CHAT_ZK_SECRET}"
paymentsService.userAuthenticationTokenSharedSecret: "${CHAT_ZK_SECRET}"
paymentsService.fixerApiKey: "dummy_fixer_key"
paymentsService.coinGeckoApiKey: "dummy_coingecko_key"
registrationService.collationKeySalt: "${CHAT_ZK_SECRET}"
keyTransparencyService.clientPrivateKey: "${CHAT_ZK_SECRET}"
turn.cloudflare.apiToken: "dummy_turn_token"
turn.secret: "${TURN_SECRET}"
linkDevice.secret: "${LINK_DEVICE_SECRET}"
hlrLookup.apiKey: "dummy_hlr_api_key"
hlrLookup.apiSecret: "dummy_hlr_api_secret"
foundationDbMessages.versionstampCipherKey.0: "${CHAT_ZK_SECRET}"
registrationWebAuthn.userHandleBlindingSecret: "${CHAT_ZK_SECRET}"
EOF

# Update .env
cat << EOF > .env
SIGNAL_DB_NAME=signal
SIGNAL_DB_USER=signal
SIGNAL_DB_PASSWORD=${DB_PASS}
SIGNAL_DB_HOST=127.0.0.1
SIGNAL_DB_PORT=3306
SIGNAL_ADMIN_KEY=${ADMIN_KEY}
SIGNAL_SERVER_URL=http://127.0.0.1:8080
MINIO_ROOT_USER=minioadmin
MINIO_ROOT_PASSWORD=minio_strong_password
SIGNAL_TURN_SECRET=${TURN_SECRET}
FCM_CREDENTIALS_PATH=/opt/signal/fcm-service-account.json
EOF

echo "--> Updated config/secrets.bundle.yml and .env"
echo "--> Admin Key: ${ADMIN_KEY}"
echo "=========================================================="
echo " Secrets successfully generated!"
echo "=========================================================="
