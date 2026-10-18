#!/usr/bin/env bash
# Deploy an already-pushed container image to Cloud Run.
#
# The single definition of how the service is configured (env vars, secret mounts, sizing).
# Called by scripts/init-gcp.sh for the first deploy and by .github/workflows/deploy.yml on
# every push to `release`, so the two can't drift apart.
#
# `gcloud run deploy --set-env-vars` REPLACES the whole environment, so any setting not
# listed below is wiped by the next deploy. To add a setting: add it here, and for CI also
# forward it in deploy.yml's `env:` block.
#
# Usage:
#   bash scripts/deploy-cloud-run.sh <image>
#
# Configuration is read from the environment. init-gcp.sh exports it from .env.gcp; the
# workflow maps it from GitHub Actions variables. See .env.gcp.example for what each means.

set -euo pipefail

IMAGE="${1:?usage: deploy-cloud-run.sh <image>}"

for v in GCP_PROJECT_ID GCP_REGION GCP_SERVICE_NAME CLOUD_RUN_SA \
         CLOUD_SQL_DB_NAME CLOUD_SQL_DB_USER INSTANCE_CONNECTION_NAME; do
  if [[ -z "${!v:-}" ]]; then
    echo "ERROR: $v is not set" >&2
    exit 1
  fi
done

CLOUD_RUN_SA_EMAIL="$CLOUD_RUN_SA@$GCP_PROJECT_ID.iam.gserviceaccount.com"
ADS_GCS_BUCKET="${ADS_GCS_BUCKET:-$GCP_PROJECT_ID-ads}"
VOICE_MODELS_GCS_BUCKET="${VOICE_MODELS_GCS_BUCKET:-$GCP_PROJECT_ID-voice-models}"
DB_URL="jdbc:postgresql:///$CLOUD_SQL_DB_NAME?cloudSqlInstance=$INSTANCE_CONNECTION_NAME&socketFactory=com.google.cloud.sql.postgres.SocketFactory"

# ---------------------------------------------------------------
# Environment variables
# ---------------------------------------------------------------
env_vars=()
# gcloud takes the whole environment as ONE flag, so entries need a separator that cannot occur inside a
# value. Commas (the default) break a business address or the BUSINESS_INDUSTRIES list; '@' (used before)
# breaks the email addresses in MAIL_USERNAME / MAIL_FROM. '~' appears in none of these; set_env refuses
# any value that contains it rather than silently splitting it into a bogus variable.
ENV_DELIM='~'
set_env() {
  if [[ "$2" == *"$ENV_DELIM"* ]]; then
    echo "ERROR: the value of $1 contains '$ENV_DELIM', which separates env vars in this deploy" >&2
    exit 1
  fi
  env_vars+=("$1=$2")
}
# Add NAME=$NAME only when it is set and non-empty, so the app's built-in default
# (application.yml) applies otherwise.
set_env_if_present() { [[ -z "${!1:-}" ]] || set_env "$1" "${!1}"; }

set_env DB_URL                "$DB_URL"
set_env DB_USERNAME           "$CLOUD_SQL_DB_USER"
set_env SPRING_PROFILES_ACTIVE prod
set_env TZ                    Asia/Jakarta
set_env ADS_GCS_BUCKET        "$ADS_GCS_BUCKET"
set_env VOICE_MODELS_GCS_BUCKET "$VOICE_MODELS_GCS_BUCKET"
# The Firebase project that owns the app's FCM tokens. The service authenticates to it as its own service
# account (application-default credentials), so there is no key to store: the org may forbid creating one,
# and the account needs roles/firebasecloudmessaging.admin (init-gcp.sh grants it). Defaults to this project.
set_env FIREBASE_PROJECT_ID   "${FIREBASE_PROJECT_ID:-$GCP_PROJECT_ID}"
# The project that hosts Gemini on Vertex AI ("say the sale", app.voice.transaction.project-id). Always this project:
# the service calls it as its own service account (roles/aiplatform.user, granted by init-gcp.sh).
set_env GCP_PROJECT_ID        "$GCP_PROJECT_ID"
set_env SEED_USERNAME         "${SEED_USERNAME:-admin}"
set_env SEED_NAME             "${SEED_NAME:-Admin User}"
set_env SEED_BUSINESS_NAME    "${SEED_BUSINESS_NAME:-My Business}"

for name in \
  SEED_EMAIL SEED_BUSINESS_ADDRESS SEED_NMID SEED_MERCHANT_PAN \
  ADS_SIGNED_URL_TTL_SECONDS ADS_MAX_AUDIO_BYTES ADS_MAX_AUDIO_SECONDS \
  JWT_EXPIRATION_MS CALLBACK_DEFAULT_USERNAME CALLBACK_FALLBACK_TO_DEFAULT \
  BUSINESS_MAX_USERS BUSINESS_SIZES BUSINESS_DEFAULT_SIZE \
  BUSINESS_INDUSTRIES BUSINESS_DEFAULT_INDUSTRY \
  LOGIN_MAX_FAILURES LOGIN_LOCKOUT_MINUTES MAX_REQUEST_BYTES DB_POOL_SIZE JAVA_OPTS \
  PUSH_QUEUE_THREADS PUSH_QUEUE_CAPACITY \
  MAIL_HOST MAIL_PORT MAIL_USERNAME MAIL_FROM MAIL_FROM_NAME MAIL_STARTTLS MAIL_SSL \
  ADMIN_EMAILS ADMIN_DASHBOARD_ORIGINS \
  VOICE_TRANSACTION_MODEL VOICE_TRANSACTION_LOCATION VOICE_TRANSACTION_TIMEOUT_SECONDS VOICE_TRANSACTION_THINKING_LEVEL \
  VOICE_TRANSACTION_MAX_REQUESTS_PER_MINUTE VOICE_TRANSACTION_MAX_REQUESTS_PER_DAY \
  REQUEST_LOG_BODY_ON_ERROR REQUEST_LOG_MAX_BODY_LENGTH; do
  set_env_if_present "$name"
done

# gcloud's alternate delimiter syntax (^X^...) lets values contain commas — e.g. a business address
# like "Jl. Contoh No. 1, Jakarta", or the comma-separated BUSINESS_INDUSTRIES list.
ENV_VARS_ARG="^${ENV_DELIM}^$(IFS=$ENV_DELIM; printf '%s' "${env_vars[*]}")"

# ---------------------------------------------------------------
# Secrets (Secret Manager references — the values never touch the CI runner or the
# revision's plaintext env; created by init-gcp.sh)
# ---------------------------------------------------------------
secrets=(
  DB_PASSWORD=bejo-db-password:latest
  JWT_SECRET=bejo-jwt-secret:latest
  API_TOKEN=bejo-api-token:latest
  SEED_PASSWORD=bejo-seed-password:latest
)
# A Firebase service-account key is opt-in: by default FCM uses the service's own identity (see
# FIREBASE_PROJECT_ID above). Set MOUNT_FIREBASE_CREDENTIALS=true to mount the key instead, once
# `bejo-firebase-credentials` exists (init-gcp.sh creates it when FIREBASE_CREDENTIALS_BASE64 is in .env.gcp).
# An explicit key takes precedence over the service identity, so never leave one for a different project.
if [[ "${MOUNT_FIREBASE_CREDENTIALS:-false}" == true ]]; then
  secrets+=(FIREBASE_CREDENTIALS_BASE64=bejo-firebase-credentials:latest)
fi
# The ads admin token is optional: init-gcp.sh only creates the secret when a value was
# provided. Mounting a secret that doesn't exist fails the deploy, so an install without ads
# sets MOUNT_ADS_ADMIN_TOKEN=false (init-gcp.sh does this for its own deploy; CI reads the
# GitHub Actions variable of the same name). Unset or empty means true, so an existing
# install that has the secret keeps mounting it without any configuration.
if [[ "${MOUNT_ADS_ADMIN_TOKEN:-true}" == true ]]; then
  secrets+=(ADS_ADMIN_TOKEN=bejo-ads-admin-token:latest)
fi
# The QRIS callback shared secret (provider sends it as X-Callback-Secret) is optional too, and
# opt-in: set MOUNT_CALLBACK_SECRET=true once `bejo-callback-secret` exists (init-gcp.sh creates it
# when CALLBACK_SECRET is in .env.gcp). Default false so an existing install keeps deploying.
# The SMTP password (Zoho application password) is optional and opt-in the same way: set
# MOUNT_MAIL_PASSWORD=true once `bejo-mail-password` exists (init-gcp.sh creates it when MAIL_PASSWORD is
# in .env.gcp). Without it password reset answers 503; the other MAIL_* settings are plain env above.
if [[ "${MOUNT_MAIL_PASSWORD:-false}" == true ]]; then
  secrets+=(MAIL_PASSWORD=bejo-mail-password:latest)
fi
if [[ "${MOUNT_CALLBACK_SECRET:-false}" == true ]]; then
  secrets+=(CALLBACK_SECRET=bejo-callback-secret:latest)
fi
SECRETS_ARG="$(IFS=,; printf '%s' "${secrets[*]}")"

# Cloud Run bills CPU either only while a request is being handled (throttling, the default and
# the cheaper mode) or for the instance's whole lifetime. With throttling, work that continues
# after the response is sent — such as the FCM push queue — gets almost no CPU until the next
# request, which can delay payment notifications. Set CLOUD_RUN_CPU_THROTTLING=false to keep the
# CPU always allocated (higher cost) if pushes turn out to lag behind the callback.
if [[ "${CLOUD_RUN_CPU_THROTTLING:-true}" == false ]]; then
  CPU_THROTTLING_ARG=--no-cpu-throttling
else
  CPU_THROTTLING_ARG=--cpu-throttling
fi

# ---------------------------------------------------------------
# Deploy
# ---------------------------------------------------------------
echo "==> Deploying $IMAGE to Cloud Run service $GCP_SERVICE_NAME ($GCP_REGION)..."
# NOTE: --timeout (CLOUD_RUN_TIMEOUT, default 15 s) is the budget the Android app's HTTP client is built around:
# RetrofitInstances.SERVER_REQUEST_TIMEOUT_S mirrors it and the app waits that long plus a few seconds. Change
# this and that constant together. Likewise --min-instances: with 0, the first request after a quiet spell also
# waits 30+ s for a container, which the app's tighter timeouts no longer cover — production keeps one warm.
# VOICE_TRANSACTION_TIMEOUT_SECONDS (the Gemini call, default 12) must stay BELOW this, so that call gives up and answers
# 503 before the platform cuts the request: raise this first if that is ever raised.
gcloud run deploy "$GCP_SERVICE_NAME" \
  --project="$GCP_PROJECT_ID" \
  --image="$IMAGE" \
  --region="$GCP_REGION" \
  --platform=managed \
  --service-account="$CLOUD_RUN_SA_EMAIL" \
  --add-cloudsql-instances="$INSTANCE_CONNECTION_NAME" \
  --set-env-vars="$ENV_VARS_ARG" \
  --set-secrets="$SECRETS_ARG" \
  --min-instances="${CLOUD_RUN_MIN_INSTANCES:-0}" \
  --max-instances="${CLOUD_RUN_MAX_INSTANCES:-2}" \
  --memory="${CLOUD_RUN_MEMORY:-512Mi}" \
  --cpu="${CLOUD_RUN_CPU:-1}" \
  --timeout="${CLOUD_RUN_TIMEOUT:-15}" \
  --concurrency=80 \
  "$CPU_THROTTLING_ARG" \
  --cpu-boost \
  --allow-unauthenticated \
  --quiet
