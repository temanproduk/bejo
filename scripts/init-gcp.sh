#!/usr/bin/env bash
# One-time GCP setup for bejo on Cloud Run + Cloud SQL.
# Run this once before the first deployment.
#
# Prerequisites:
#   1. gcloud CLI installed and authenticated (gcloud auth login)
#   2. Domain ownership verified in Google Search Console:
#      https://search.google.com/search-console
#   3. .env.gcp filled in (cp .env.gcp.example .env.gcp)
#
# Usage:
#   bash scripts/init-gcp.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/../.env.gcp"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: $ENV_FILE not found. Copy .env.gcp.example to .env.gcp and fill in the values."
  exit 1
fi

# shellcheck disable=SC1090
source "$ENV_FILE"

# Validate required vars
required_vars=(
  GCP_PROJECT_ID GCP_REGION GCP_SERVICE_NAME CUSTOM_DOMAIN
  ARTIFACT_REGISTRY_REPO CLOUD_SQL_INSTANCE_NAME
  CLOUD_SQL_DB_NAME CLOUD_SQL_DB_USER CLOUD_RUN_SA GITHUB_ACTIONS_SA
  DB_PASSWORD JWT_SECRET API_TOKEN FIREBASE_CREDENTIALS_BASE64 SEED_PASSWORD
)
for v in "${required_vars[@]}"; do
  if [[ -z "${!v:-}" ]]; then
    echo "ERROR: $v is not set in .env.gcp"
    exit 1
  fi
done

echo "==> Project: $GCP_PROJECT_ID  Region: $GCP_REGION"
gcloud config set project "$GCP_PROJECT_ID"

# ---------------------------------------------------------------
# 1. Enable required APIs
# ---------------------------------------------------------------
echo "==> Enabling APIs..."
gcloud services enable \
  run.googleapis.com \
  sqladmin.googleapis.com \
  artifactregistry.googleapis.com \
  secretmanager.googleapis.com \
  cloudbuild.googleapis.com

# ---------------------------------------------------------------
# 2. Artifact Registry repository
# ---------------------------------------------------------------
echo "==> Creating Artifact Registry repository..."
gcloud artifacts repositories create "$ARTIFACT_REGISTRY_REPO" \
  --repository-format=docker \
  --location="$GCP_REGION" \
  --description="Bejo Docker images" \
  --quiet 2>/dev/null || echo "    (already exists, skipping)"

# ---------------------------------------------------------------
# 3. Cloud SQL — PostgreSQL 17 (minimal / cheapest config)
# ---------------------------------------------------------------
echo "==> Creating Cloud SQL instance (this takes ~5 minutes)..."
# Public IP but no authorized networks: only reachable via the Cloud SQL
# connector (IAM-authenticated); private IP would require VPC peering setup
if gcloud sql instances describe "$CLOUD_SQL_INSTANCE_NAME" --quiet >/dev/null 2>&1; then
  echo "    (already exists, skipping)"
else
  gcloud sql instances create "$CLOUD_SQL_INSTANCE_NAME" \
    --database-version=POSTGRES_17 \
    --edition=enterprise \
    --tier=db-f1-micro \
    --region="$GCP_REGION" \
    --storage-size=10GB \
    --storage-type=HDD \
    --no-storage-auto-increase \
    --no-backup \
    --assign-ip \
    --quiet
fi

# Retrieve full connection name (PROJECT:REGION:INSTANCE)
INSTANCE_CONNECTION_NAME=$(gcloud sql instances describe "$CLOUD_SQL_INSTANCE_NAME" \
  --format="value(connectionName)")
echo "    Instance connection name: $INSTANCE_CONNECTION_NAME"

echo "==> Creating database and user..."
gcloud sql databases create "$CLOUD_SQL_DB_NAME" \
  --instance="$CLOUD_SQL_INSTANCE_NAME" \
  --quiet 2>/dev/null || echo "    (already exists, skipping)"

gcloud sql users create "$CLOUD_SQL_DB_USER" \
  --instance="$CLOUD_SQL_INSTANCE_NAME" \
  --password="$DB_PASSWORD" \
  --quiet 2>/dev/null || echo "    (already exists, skipping)"

# Create a service account (idempotent) and wait until it is readable —
# add-iam-policy-binding right after create otherwise races the propagation
# and fails with "Service account ... does not exist".
ensure_service_account() {
  local name=$1 display=$2
  local email="$name@$GCP_PROJECT_ID.iam.gserviceaccount.com"
  if ! gcloud iam service-accounts describe "$email" --quiet >/dev/null 2>&1; then
    gcloud iam service-accounts create "$name" --display-name="$display" --quiet
  fi
  for _ in $(seq 1 12); do
    gcloud iam service-accounts describe "$email" --quiet >/dev/null 2>&1 && return 0
    sleep 5
  done
  echo "ERROR: service account $email not visible after creation" >&2
  return 1
}

# ---------------------------------------------------------------
# 4. Service account for Cloud Run (the running app)
# ---------------------------------------------------------------
echo "==> Creating Cloud Run service account..."
ensure_service_account "$CLOUD_RUN_SA" "Bejo Cloud Run"

CLOUD_RUN_SA_EMAIL="$CLOUD_RUN_SA@$GCP_PROJECT_ID.iam.gserviceaccount.com"

for role in "roles/cloudsql.client" "roles/secretmanager.secretAccessor"; do
  gcloud projects add-iam-policy-binding "$GCP_PROJECT_ID" \
    --member="serviceAccount:$CLOUD_RUN_SA_EMAIL" \
    --role="$role" \
    --quiet
done

# ---------------------------------------------------------------
# 5. Service account for GitHub Actions (CI/CD)
# ---------------------------------------------------------------
echo "==> Creating GitHub Actions service account..."
ensure_service_account "$GITHUB_ACTIONS_SA" "Bejo GitHub Actions"

GITHUB_ACTIONS_SA_EMAIL="$GITHUB_ACTIONS_SA@$GCP_PROJECT_ID.iam.gserviceaccount.com"

for role in \
  "roles/run.admin" \
  "roles/artifactregistry.writer" \
  "roles/iam.serviceAccountUser"; do
  gcloud projects add-iam-policy-binding "$GCP_PROJECT_ID" \
    --member="serviceAccount:$GITHUB_ACTIONS_SA_EMAIL" \
    --role="$role" \
    --quiet
done

echo "==> Generating GitHub Actions service account key..."
KEY_FILE="$SCRIPT_DIR/../github-actions-key.json"
gcloud iam service-accounts keys create "$KEY_FILE" \
  --iam-account="$GITHUB_ACTIONS_SA_EMAIL"

echo ""
echo "    *** ACTION REQUIRED ***"
echo "    Add the following to GitHub → Settings → Secrets and variables → Actions:"
echo ""
echo "    Secret name : GCP_SA_KEY"
echo "    Secret value: $(base64 < "$KEY_FILE" | tr -d '\n')"
echo ""
echo "    Then delete the key file: rm $KEY_FILE"
echo ""

# ---------------------------------------------------------------
# 6. Secret Manager — app secrets
# ---------------------------------------------------------------
echo "==> Creating secrets in Secret Manager..."

create_or_update_secret() {
  local name=$1
  local value=$2
  if gcloud secrets describe "$name" --quiet 2>/dev/null; then
    echo -n "$value" | gcloud secrets versions add "$name" --data-file=- --quiet
  else
    echo -n "$value" | gcloud secrets create "$name" --data-file=- --replication-policy=automatic --quiet
  fi
}

create_or_update_secret "bejo-db-password"          "$DB_PASSWORD"
create_or_update_secret "bejo-jwt-secret"           "$JWT_SECRET"
create_or_update_secret "bejo-api-token"            "$API_TOKEN"
create_or_update_secret "bejo-firebase-credentials" "$FIREBASE_CREDENTIALS_BASE64"
create_or_update_secret "bejo-seed-password"        "$SEED_PASSWORD"

# ---------------------------------------------------------------
# 7. Build & push initial Docker image
# ---------------------------------------------------------------
IMAGE="$GCP_REGION-docker.pkg.dev/$GCP_PROJECT_ID/$ARTIFACT_REGISTRY_REPO/$GCP_SERVICE_NAME:latest"

# Build with Cloud Build: runs on native amd64 server-side, so no local Docker/
# podman and no slow qemu emulation on Apple Silicon. Uploads the source
# (respecting .gcloudignore), builds the Dockerfile, and pushes to Artifact Registry.
echo "==> Building image with Cloud Build (native amd64, no local emulation)..."
gcloud builds submit "$SCRIPT_DIR/.." --tag="$IMAGE" --quiet

# ---------------------------------------------------------------
# 8. Initial Cloud Run deployment
# ---------------------------------------------------------------
echo "==> Deploying to Cloud Run..."

DB_URL="jdbc:postgresql:///$CLOUD_SQL_DB_NAME?cloudSqlInstance=$INSTANCE_CONNECTION_NAME&socketFactory=com.google.cloud.sql.postgres.SocketFactory"

# Build all env vars into one flag using gcloud's alternate delimiter (^@^)
# so individual values may contain commas — e.g. a business address like
# "Jl. Contoh No. 1, Jakarta" (default comma delimiter would mis-split it).
ENV_VARS="DB_URL=$DB_URL"
ENV_VARS+="@DB_USERNAME=$CLOUD_SQL_DB_USER"
ENV_VARS+="@SPRING_PROFILES_ACTIVE=prod"
ENV_VARS+="@SEED_USERNAME=${SEED_USERNAME:-admin}"
ENV_VARS+="@SEED_NAME=${SEED_NAME:-Admin User}"
ENV_VARS+="@SEED_BUSINESS_NAME=${SEED_BUSINESS_NAME:-My Business}"
[[ -n "${SEED_BUSINESS_ADDRESS:-}" ]] && ENV_VARS+="@SEED_BUSINESS_ADDRESS=$SEED_BUSINESS_ADDRESS"
[[ -n "${SEED_NMID:-}" ]]            && ENV_VARS+="@SEED_NMID=$SEED_NMID"

gcloud run deploy "$GCP_SERVICE_NAME" \
  --image="$IMAGE" \
  --region="$GCP_REGION" \
  --platform=managed \
  --service-account="$CLOUD_RUN_SA_EMAIL" \
  --add-cloudsql-instances="$INSTANCE_CONNECTION_NAME" \
  --set-env-vars="^@^$ENV_VARS" \
  --set-secrets="DB_PASSWORD=bejo-db-password:latest" \
  --set-secrets="JWT_SECRET=bejo-jwt-secret:latest" \
  --set-secrets="API_TOKEN=bejo-api-token:latest" \
  --set-secrets="FIREBASE_CREDENTIALS_BASE64=bejo-firebase-credentials:latest" \
  --set-secrets="SEED_PASSWORD=bejo-seed-password:latest" \
  --min-instances="${CLOUD_RUN_MIN_INSTANCES:-0}" \
  --max-instances="${CLOUD_RUN_MAX_INSTANCES:-2}" \
  --memory="${CLOUD_RUN_MEMORY:-512Mi}" \
  --cpu="${CLOUD_RUN_CPU:-1}" \
  --allow-unauthenticated \
  --quiet

# ---------------------------------------------------------------
# 9. Custom domain mapping (one-time, persists across deployments)
# ---------------------------------------------------------------
echo "==> Mapping custom domain $CUSTOM_DOMAIN..."
gcloud run domain-mappings create \
  --service="$GCP_SERVICE_NAME" \
  --domain="$CUSTOM_DOMAIN" \
  --region="$GCP_REGION" \
  --quiet 2>/dev/null || echo "    (already mapped, skipping)"

echo ""
echo "==> DNS records to add at your registrar for $CUSTOM_DOMAIN:"
gcloud run domain-mappings describe \
  --domain="$CUSTOM_DOMAIN" \
  --region="$GCP_REGION" \
  --format="table(status.resourceRecords[].name,status.resourceRecords[].rrdata,status.resourceRecords[].type)" \
  || echo "    (domain mapping not created — verify $CUSTOM_DOMAIN ownership in Search Console, then re-run this script)"

echo ""
echo "==> Done!"
echo "    Cloud Run URL : $(gcloud run services describe "$GCP_SERVICE_NAME" --region="$GCP_REGION" --format="value(status.url)")"
echo "    Custom domain : https://$CUSTOM_DOMAIN  (active after DNS propagates + SSL provisioned)"
