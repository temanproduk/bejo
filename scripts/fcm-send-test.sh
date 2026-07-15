#!/usr/bin/env bash
#
# fcm-send-test.sh — send a data-only FCM push DIRECTLY to one device token,
# using a Firebase service-account key, bypassing the app's database.
#
# Purpose: isolate "is this token valid for the project?" from "is the DB storing
# the right token?". If a fresh token from the app works here but the app's normal
# push doesn't, the stored token is stale. If it fails here too (UNREGISTERED),
# the token itself belongs to a different project / an old build.
#
# Usage:
#   scripts/fcm-send-test.sh <FCM_TOKEN>
#   scripts/fcm-send-test.sh <FCM_TOKEN> "Judul" "Isi pesan"
#   SA_KEY=/path/to/key.json scripts/fcm-send-test.sh <FCM_TOKEN>
#
# Env:
#   SA_KEY   Path to the service-account JSON (default: ./bejo-firebase-adminsdk.json)
#
# Requires: openssl, curl, jq, base64 (all standard on macOS/Linux).

set -euo pipefail

TOKEN="${1:-}"
TITLE="${2:-FCM test}"
BODY="${3:-Direct send test $(date '+%H:%M:%S')}"
SA_KEY="${SA_KEY:-$(cd "$(dirname "$0")/.." && pwd)/bejo-firebase-adminsdk.json}"

if [[ -z "$TOKEN" ]]; then
  echo "Usage: $0 <FCM_TOKEN> [title] [body]" >&2
  echo "  (get a fresh token from the app's Logcat: FirebaseMessaging.getInstance().getToken())" >&2
  exit 2
fi
if [[ ! -f "$SA_KEY" ]]; then
  echo "Service-account key not found: $SA_KEY" >&2
  echo "Set SA_KEY=/path/to/key.json" >&2
  exit 2
fi

# --- read the service account -------------------------------------------------
PROJECT_ID=$(jq -r '.project_id' "$SA_KEY")
CLIENT_EMAIL=$(jq -r '.client_email' "$SA_KEY")
PRIVATE_KEY=$(jq -r '.private_key' "$SA_KEY")

echo "→ project:  $PROJECT_ID"
echo "→ sa email: $CLIENT_EMAIL"
echo "→ token:    ${TOKEN:0:24}…(${#TOKEN} chars)"
echo

# --- base64url helper ---------------------------------------------------------
b64url() { openssl base64 -e -A | tr '+/' '-_' | tr -d '='; }

# --- build a signed JWT and exchange it for an OAuth2 access token ------------
NOW=$(date +%s)
EXP=$((NOW + 3600))
HEADER=$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)
CLAIM=$(printf '{"iss":"%s","scope":"https://www.googleapis.com/auth/firebase.messaging","aud":"https://oauth2.googleapis.com/token","iat":%d,"exp":%d}' \
  "$CLIENT_EMAIL" "$NOW" "$EXP" | b64url)
SIGNING_INPUT="${HEADER}.${CLAIM}"

KEYFILE=$(mktemp)
trap 'rm -f "$KEYFILE"' EXIT
printf '%s\n' "$PRIVATE_KEY" > "$KEYFILE"

SIGNATURE=$(printf '%s' "$SIGNING_INPUT" | openssl dgst -sha256 -sign "$KEYFILE" | b64url)
ASSERTION="${SIGNING_INPUT}.${SIGNATURE}"

TOKEN_RESP=$(curl -s -X POST https://oauth2.googleapis.com/token \
  --data-urlencode 'grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer' \
  --data-urlencode "assertion=${ASSERTION}")

ACCESS_TOKEN=$(echo "$TOKEN_RESP" | jq -r '.access_token // empty')
if [[ -z "$ACCESS_TOKEN" ]]; then
  echo "✗ Failed to obtain access token — the service-account key or clock may be wrong:" >&2
  echo "$TOKEN_RESP" | jq . >&2 || echo "$TOKEN_RESP" >&2
  exit 1
fi

# --- send the message (data-only, mirrors the app's real pushes) --------------
PAYLOAD=$(jq -n --arg t "$TOKEN" --arg title "$TITLE" --arg body "$BODY" '
  { message: { token: $t, data: { type: "TEST", title: $title, body: $body } } }')

HTTP_BODY=$(mktemp); trap 'rm -f "$KEYFILE" "$HTTP_BODY"' EXIT
HTTP_CODE=$(curl -s -o "$HTTP_BODY" -w '%{http_code}' \
  -X POST "https://fcm.googleapis.com/v1/projects/${PROJECT_ID}/messages:send" \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -H "Content-Type: application/json" \
  -d "$PAYLOAD")

echo "HTTP $HTTP_CODE"
cat "$HTTP_BODY" | jq . 2>/dev/null || cat "$HTTP_BODY"
echo

# --- interpret ----------------------------------------------------------------
if [[ "$HTTP_CODE" == "200" ]]; then
  echo "✅ SUCCESS — this token IS a valid, live registration in '$PROJECT_ID'."
  echo "   → If the app's normal push still fails, the token stored in bejo_sessions"
  echo "     is STALE/different. Fix the app's token-upload step (deviceId + PUT /api/devices/{id}/fcm-token)."
else
  ERR=$(jq -r '.error.details[]?.errorCode // .error.status // empty' "$HTTP_BODY" 2>/dev/null | head -1)
  echo "❌ FAILED (${ERR:-see body above})."
  case "$ERR" in
    UNREGISTERED)
      echo "   → This exact token is NOT registered in '$PROJECT_ID'. Since the send itself"
      echo "     authenticated, the token belongs to a different project or an old app build."
      echo "     Reinstall the current (bejo-prod) build and get a fresh token." ;;
    SENDER_ID_MISMATCH)
      echo "   → Token belongs to a DIFFERENT Firebase project than '$PROJECT_ID'. Align the app's google-services.json." ;;
    INVALID_ARGUMENT)
      echo "   → Token is malformed (truncated / whitespace?). Check how it's captured & stored." ;;
    *)
      echo "   → See the error body above." ;;
  esac
  exit 1
fi
