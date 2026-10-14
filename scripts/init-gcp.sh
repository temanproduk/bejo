#!/usr/bin/env bash
# GCP setup for bejo on Cloud Run + Cloud SQL. Run it before the first deployment; re-run it any time.
#
# IDEMPOTENT — a re-run converges on what .env.gcp declares and changes only what differs:
#   - existing resources are left alone (an interrupted earlier run is picked up where it stopped;
#     a Cloud SQL instance that is still being created is waited for)
#   - IAM bindings are only written when missing
#   - secrets get a new version only when the value in .env.gcp changed
#   - the Workload Identity provider's repo/branch condition is updated if you changed it
#   - the DB user's password is re-synced to .env.gcp
# The one thing that happens every time is the build + deploy (steps 8-9), which ships the files ON DISK
# (uncommitted changes included). Use --skip-deploy to provision without touching the running service.
#
# Prerequisites:
#   1. gcloud CLI installed and authenticated (gcloud auth login)
#   2. Domain ownership verified in Google Search Console:
#      https://search.google.com/search-console
#   3. .env.gcp filled in (cp .env.gcp.example .env.gcp)
#
# How GitHub Actions authenticates to GCP: by default with Workload Identity Federation (keyless —
# GitHub's OIDC token is exchanged for short-lived credentials, so there is no key file and no
# GCP_SA_KEY secret). That is also the only option on organizations that enforce
# constraints/iam.disableServiceAccountKeyCreation, which forbids downloadable service-account
# keys. --sa-key falls back to a downloaded key for projects that still allow them.
#
# Usage:
#   bash scripts/init-gcp.sh                # first run, or any re-run
#   bash scripts/init-gcp.sh --sa-key       # use a downloaded key instead of Workload Identity
#   bash scripts/init-gcp.sh --rotate-key   # --sa-key, and mint a new key even if one exists
#   bash scripts/init-gcp.sh --skip-deploy  # infrastructure, secrets and domain only: no build, no deploy
#   bash scripts/init-gcp.sh --take-over-domain   # map the custom domain here even if another
#                                                 # project (e.g. an abandoned one) still holds it

set -euo pipefail

USE_SA_KEY=false
ROTATE_KEY=false
TAKE_OVER_DOMAIN=false
SKIP_DEPLOY=false
for arg in "$@"; do
  case "$arg" in
    --sa-key)           USE_SA_KEY=true ;;
    --rotate-key)       USE_SA_KEY=true; ROTATE_KEY=true ;;
    --take-over-domain) TAKE_OVER_DOMAIN=true ;;
    --skip-deploy)      SKIP_DEPLOY=true ;;
    *) echo "ERROR: unknown argument: $arg (supported: --sa-key, --rotate-key, --take-over-domain, --skip-deploy)" >&2; exit 1 ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/../.env.gcp"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: $ENV_FILE not found. Copy .env.gcp.example to .env.gcp and fill in the values."
  exit 1
fi

# Exported so deploy-cloud-run.sh sees the same configuration.
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

required_vars=(
  GCP_PROJECT_ID GCP_REGION GCP_SERVICE_NAME CUSTOM_DOMAIN
  ARTIFACT_REGISTRY_REPO CLOUD_SQL_INSTANCE_NAME
  CLOUD_SQL_DB_NAME CLOUD_SQL_DB_USER CLOUD_RUN_SA GITHUB_ACTIONS_SA
  DB_PASSWORD JWT_SECRET API_TOKEN SEED_PASSWORD
)
for v in "${required_vars[@]}"; do
  if [[ -z "${!v:-}" ]]; then
    echo "ERROR: $v is not set in .env.gcp"
    exit 1
  fi
done

CLOUD_RUN_SA_EMAIL="$CLOUD_RUN_SA@$GCP_PROJECT_ID.iam.gserviceaccount.com"
GITHUB_ACTIONS_SA_EMAIL="$GITHUB_ACTIONS_SA@$GCP_PROJECT_ID.iam.gserviceaccount.com"
ADS_GCS_BUCKET="${ADS_GCS_BUCKET:-$GCP_PROJECT_ID-ads}"
export ADS_GCS_BUCKET
# Daily automated backup window, UTC (19:00 UTC = 02:00 WIB, the quietest hour).
CLOUD_SQL_BACKUP_START_TIME="${CLOUD_SQL_BACKUP_START_TIME:-19:00}"

# ---------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------

# create_unless_exists <describe command...> -- <create command...>
# Runs the create command only when the describe command fails. Unlike silencing the create with
# `2>/dev/null || echo "already exists"`, a real failure (permissions, quota, typo) still aborts the script.
# If the create fails but the resource exists afterwards (a concurrent run, an earlier interrupted run
# that finished server-side, eventual consistency) the goal is met, so that is not an error either.
create_unless_exists() {
  local describe=()
  while [[ "$1" != "--" ]]; do describe+=("$1"); shift; done
  shift
  if "${describe[@]}" >/dev/null 2>&1; then
    echo "    (already exists, skipping)"
    return 0
  fi
  if "$@"; then return 0; fi
  sleep 3
  if "${describe[@]}" >/dev/null 2>&1; then
    echo "    (it exists now — created by another or an interrupted run; continuing)"
    return 0
  fi
  return 1
}

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

# True when the service account already holds the role on the project (a read, so a re-run leaves the
# IAM policy — and its etag — alone).
has_project_role() {
  local member=$1 role=$2
  [[ -n "$(gcloud projects get-iam-policy "$GCP_PROJECT_ID" --flatten='bindings[].members' \
        --filter="bindings.role=\"$role\" AND bindings.members=\"serviceAccount:$member\"" \
        --format='value(bindings.role)' 2>/dev/null)" ]]
}

# Grants each role that is missing. Sets ROLES_GRANTED=true if it changed anything.
ROLES_GRANTED=false
grant_project_roles() {
  local member=$1; shift
  ROLES_GRANTED=false
  for role in "$@"; do
    if has_project_role "$member" "$role"; then continue; fi
    gcloud projects add-iam-policy-binding "$GCP_PROJECT_ID" \
      --member="serviceAccount:$member" \
      --role="$role" \
      --quiet >/dev/null
    ROLES_GRANTED=true
  done
}

# Create the secret, or add a new version only when the value actually changed — otherwise every re-run
# would pile up identical versions (clutter, and each active version is billed).
create_or_update_secret() {
  local name=$1 value=$2 current
  if gcloud secrets describe "$name" --quiet >/dev/null 2>&1; then
    # If the latest version can't be read (disabled, destroyed, no permission) fall through and add one.
    if current=$(gcloud secrets versions access latest --secret="$name" 2>/dev/null) && [[ "$current" == "$value" ]]; then
      echo "    (secret $name unchanged)"
      return 0
    fi
    printf '%s' "$value" | gcloud secrets versions add "$name" --data-file=- --quiet
    echo "    (secret $name: new version)"
  else
    printf '%s' "$value" | gcloud secrets create "$name" --data-file=- --replication-policy=automatic --quiet
  fi
}

# Wait for the Cloud SQL instance to be usable. An instance whose creation was interrupted exists
# (so create is skipped) but is still PENDING_CREATE; creating its database then fails.
wait_for_sql_instance() {
  local state
  for _ in $(seq 1 60); do
    state=$(gcloud sql instances describe "$CLOUD_SQL_INSTANCE_NAME" --format="value(state)")
    case "$state" in
      RUNNABLE) return 0 ;;
      SUSPENDED|FAILED|"")
        echo "ERROR: Cloud SQL instance $CLOUD_SQL_INSTANCE_NAME is '${state:-unknown}' — if SUSPENDED, check that billing is enabled on the project." >&2
        return 1 ;;
      *) echo "    (instance is $state; waiting...)"; sleep 10 ;;
    esac
  done
  echo "ERROR: Cloud SQL instance $CLOUD_SQL_INSTANCE_NAME did not become RUNNABLE in 10 minutes" >&2
  return 1
}

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
  cloudbuild.googleapis.com \
  storage.googleapis.com \
  iam.googleapis.com \
  iamcredentials.googleapis.com \
  sts.googleapis.com \
  speech.googleapis.com
# (speech.googleapis.com: "say the amount" in the app — see docs/architecture/voice.md. The service calls it as its
# own service account, so no extra role is needed.)

# ---------------------------------------------------------------
# 2. Artifact Registry repository
# ---------------------------------------------------------------
echo "==> Creating Artifact Registry repository..."
create_unless_exists \
  gcloud artifacts repositories describe "$ARTIFACT_REGISTRY_REPO" --location="$GCP_REGION" \
  -- \
  gcloud artifacts repositories create "$ARTIFACT_REGISTRY_REPO" \
    --repository-format=docker \
    --location="$GCP_REGION" \
    --description="Bejo Docker images" \
    --quiet

# ---------------------------------------------------------------
# 3. Cloud SQL — PostgreSQL 17 (minimal / cheapest config)
# ---------------------------------------------------------------
echo "==> Creating Cloud SQL instance (this takes ~5 minutes)..."
# Public IP but no authorized networks: only reachable via the Cloud SQL
# connector (IAM-authenticated); private IP would require VPC peering setup
create_unless_exists \
  gcloud sql instances describe "$CLOUD_SQL_INSTANCE_NAME" --quiet \
  -- \
  gcloud sql instances create "$CLOUD_SQL_INSTANCE_NAME" \
    --database-version=POSTGRES_17 \
    --edition=enterprise \
    --tier=db-f1-micro \
    --region="$GCP_REGION" \
    --storage-size=10GB \
    --storage-type=HDD \
    --no-storage-auto-increase \
    --backup-start-time="$CLOUD_SQL_BACKUP_START_TIME" \
    --assign-ip \
    --quiet

wait_for_sql_instance

# Automated daily backups (the last 7 are kept). The instance used to be created with --no-backup,
# so an existing one may have none — turn them on here, idempotently. This does not restart the
# instance. Point-in-time recovery is deliberately NOT enabled: it archives WAL on the instance's own
# disk, which is 10 GB with auto-increase off, and a full disk takes the database down. Enable it
# (and storage auto-increase) together if you want PITR.
if [[ "$(gcloud sql instances describe "$CLOUD_SQL_INSTANCE_NAME" \
      --format="value(settings.backupConfiguration.enabled)")" != "True" ]]; then
  echo "==> Enabling automated backups (daily at $CLOUD_SQL_BACKUP_START_TIME UTC)..."
  gcloud sql instances patch "$CLOUD_SQL_INSTANCE_NAME" \
    --backup-start-time="$CLOUD_SQL_BACKUP_START_TIME" --quiet
else
  echo "    (automated backups already enabled)"
fi

# Full connection name (PROJECT:REGION:INSTANCE), used by the Cloud Run deploy below.
INSTANCE_CONNECTION_NAME=$(gcloud sql instances describe "$CLOUD_SQL_INSTANCE_NAME" \
  --format="value(connectionName)")
export INSTANCE_CONNECTION_NAME
echo "    Instance connection name: $INSTANCE_CONNECTION_NAME"

echo "==> Creating database and user..."
create_unless_exists \
  gcloud sql databases describe "$CLOUD_SQL_DB_NAME" --instance="$CLOUD_SQL_INSTANCE_NAME" \
  -- \
  gcloud sql databases create "$CLOUD_SQL_DB_NAME" \
    --instance="$CLOUD_SQL_INSTANCE_NAME" \
    --quiet

# `sql users` has no describe command, so look the user up in the list instead.
if [[ -n "$(gcloud sql users list --instance="$CLOUD_SQL_INSTANCE_NAME" \
      --filter="name=$CLOUD_SQL_DB_USER" --format="value(name)")" ]]; then
  # The secret in step 7 is rewritten from .env.gcp on every run, so re-sync the database
  # side too — otherwise a changed DB_PASSWORD leaves the app holding a password Postgres
  # no longer accepts.
  echo "    (user already exists, syncing its password with .env.gcp)"
  gcloud sql users set-password "$CLOUD_SQL_DB_USER" \
    --instance="$CLOUD_SQL_INSTANCE_NAME" \
    --password="$DB_PASSWORD" \
    --quiet
else
  gcloud sql users create "$CLOUD_SQL_DB_USER" \
    --instance="$CLOUD_SQL_INSTANCE_NAME" \
    --password="$DB_PASSWORD" \
    --quiet
fi

# ---------------------------------------------------------------
# 4. Service account for Cloud Run (the running app)
# ---------------------------------------------------------------
echo "==> Creating Cloud Run service account..."
ensure_service_account "$CLOUD_RUN_SA" "Bejo Cloud Run"
# roles/firebasecloudmessaging.admin lets the service send push messages as itself (no key file).
grant_project_roles "$CLOUD_RUN_SA_EMAIL" \
  roles/cloudsql.client \
  roles/secretmanager.secretAccessor \
  roles/firebasecloudmessaging.admin

# ---------------------------------------------------------------
# 5. Cloud Storage bucket for ad-campaign audio
# ---------------------------------------------------------------
# Holds the ≤10s voice files pushed to devices. Locked down: uniform bucket-level
# access + public-access-prevention, so objects are reachable only via short-lived
# V4 signed URLs the app mints at push time. The Cloud Run SA needs objectAdmin on
# the bucket (read/write) and serviceAccountTokenCreator ON ITSELF so it can sign
# those URLs without a downloaded key (uses the IAM Credentials signBlob API).
echo "==> Creating ads audio bucket gs://$ADS_GCS_BUCKET ..."
create_unless_exists \
  gcloud storage buckets describe "gs://$ADS_GCS_BUCKET" \
  -- \
  gcloud storage buckets create "gs://$ADS_GCS_BUCKET" \
    --project="$GCP_PROJECT_ID" \
    --location="$GCP_REGION" \
    --uniform-bucket-level-access \
    --public-access-prevention \
    --quiet

gcloud storage buckets add-iam-policy-binding "gs://$ADS_GCS_BUCKET" \
  --member="serviceAccount:$CLOUD_RUN_SA_EMAIL" \
  --role="roles/storage.objectAdmin" \
  --quiet >/dev/null

gcloud iam service-accounts add-iam-policy-binding "$CLOUD_RUN_SA_EMAIL" \
  --member="serviceAccount:$CLOUD_RUN_SA_EMAIL" \
  --role="roles/iam.serviceAccountTokenCreator" \
  --project="$GCP_PROJECT_ID" \
  --quiet >/dev/null

# ---------------------------------------------------------------
# 6. Service account for GitHub Actions (CI/CD)
# ---------------------------------------------------------------
echo "==> Creating GitHub Actions service account..."
ensure_service_account "$GITHUB_ACTIONS_SA" "Bejo GitHub Actions"
grant_project_roles "$GITHUB_ACTIONS_SA_EMAIL" \
  roles/run.admin \
  roles/artifactregistry.writer \
  roles/iam.serviceAccountUser

# GitHub Actions authenticates with Workload Identity Federation (keyless) unless --sa-key is given.
if [[ "$USE_SA_KEY" != true ]]; then
  # The repo allowed to deploy, as OWNER/REPO. Derived from the git remote; override with GITHUB_REPO.
  GITHUB_REPO="${GITHUB_REPO:-$(git -C "$SCRIPT_DIR/.." remote get-url origin 2>/dev/null \
    | sed -E 's#^(git@github\.com:|https://github\.com/)##; s#\.git$##')}"
  if [[ ! "$GITHUB_REPO" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then
    echo "ERROR: could not work out the GitHub repository (got '${GITHUB_REPO}'). Set GITHUB_REPO=OWNER/REPO in .env.gcp." >&2
    exit 1
  fi
  WIF_POOL="${WIF_POOL:-github}"
  WIF_PROVIDER="${WIF_PROVIDER:-github-actions}"
  # Only runs from this branch may deploy. Anyone who can push a workflow to another branch of the
  # repo still cannot mint credentials for the deploy service account. Set GITHUB_DEPLOY_REF=any to
  # allow every ref of the repository (e.g. to dispatch a deploy from a feature branch).
  GITHUB_DEPLOY_REF="${GITHUB_DEPLOY_REF:-refs/heads/release}"
  PROJECT_NUMBER=$(gcloud projects describe "$GCP_PROJECT_ID" --format="value(projectNumber)")

  echo "==> Setting up Workload Identity Federation for $GITHUB_REPO (no service-account key)..."
  create_unless_exists \
    gcloud iam workload-identity-pools describe "$WIF_POOL" --location=global \
    -- \
    gcloud iam workload-identity-pools create "$WIF_POOL" \
      --location=global --display-name="GitHub Actions" --quiet
  # A deleted pool stays describable for 30 days; "exists" would then be a lie. Restore it.
  if [[ "$(gcloud iam workload-identity-pools describe "$WIF_POOL" --location=global --format='value(state)')" == DELETED ]]; then
    echo "    (the pool was deleted; restoring it)"
    gcloud iam workload-identity-pools undelete "$WIF_POOL" --location=global --quiet
  fi

  if [[ "$GITHUB_DEPLOY_REF" == any ]]; then
    ATTRIBUTE_CONDITION="assertion.repository == '$GITHUB_REPO'"
  else
    ATTRIBUTE_CONDITION="assertion.repository == '$GITHUB_REPO' && assertion.ref == '$GITHUB_DEPLOY_REF'"
  fi
  create_unless_exists \
    gcloud iam workload-identity-pools providers describe "$WIF_PROVIDER" \
      --workload-identity-pool="$WIF_POOL" --location=global \
    -- \
    gcloud iam workload-identity-pools providers create-oidc "$WIF_PROVIDER" \
      --workload-identity-pool="$WIF_POOL" --location=global \
      --display-name="GitHub Actions OIDC" \
      --issuer-uri="https://token.actions.githubusercontent.com" \
      --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.ref=assertion.ref" \
      --attribute-condition="$ATTRIBUTE_CONDITION" \
      --quiet
  if [[ "$(gcloud iam workload-identity-pools providers describe "$WIF_PROVIDER" \
        --workload-identity-pool="$WIF_POOL" --location=global --format='value(state)')" == DELETED ]]; then
    echo "    (the provider was deleted; restoring it)"
    gcloud iam workload-identity-pools providers undelete "$WIF_PROVIDER" \
      --workload-identity-pool="$WIF_POOL" --location=global --quiet
  fi
  # create_unless_exists leaves an EXISTING provider as it was, so a changed GITHUB_REPO / GITHUB_DEPLOY_REF
  # would be silently ignored. Converge the condition.
  CURRENT_CONDITION=$(gcloud iam workload-identity-pools providers describe "$WIF_PROVIDER" \
    --workload-identity-pool="$WIF_POOL" --location=global --format='value(attributeCondition)')
  if [[ "$CURRENT_CONDITION" != "$ATTRIBUTE_CONDITION" ]]; then
    echo "    updating the provider's condition:"
    echo "      was: $CURRENT_CONDITION"
    echo "      now: $ATTRIBUTE_CONDITION"
    gcloud iam workload-identity-pools providers update-oidc "$WIF_PROVIDER" \
      --workload-identity-pool="$WIF_POOL" --location=global \
      --attribute-condition="$ATTRIBUTE_CONDITION" --quiet
  fi

  # Let identities from this repository impersonate the CI service account (and nothing else).
  gcloud iam service-accounts add-iam-policy-binding "$GITHUB_ACTIONS_SA_EMAIL" \
    --role="roles/iam.workloadIdentityUser" \
    --member="principalSet://iam.googleapis.com/projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/$WIF_POOL/attribute.repository/$GITHUB_REPO" \
    --project="$GCP_PROJECT_ID" --quiet >/dev/null

  # Bindings for any OTHER repository (say the repo was renamed) are left alone — they may be deliberate —
  # but are reported, because each one is also a way to deploy.
  WIF_PRINCIPAL="principalSet://iam.googleapis.com/projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/$WIF_POOL/attribute.repository/$GITHUB_REPO"
  OTHER_PRINCIPALS=$(gcloud iam service-accounts get-iam-policy "$GITHUB_ACTIONS_SA_EMAIL" --project="$GCP_PROJECT_ID" \
    --flatten='bindings[].members' --filter='bindings.role="roles/iam.workloadIdentityUser"' \
    --format='value(bindings.members)' | grep -vxF "$WIF_PRINCIPAL" || true)
  if [[ -n "$OTHER_PRINCIPALS" ]]; then
    echo "    NOTE: these other identities can also impersonate $GITHUB_ACTIONS_SA (remove any you do not want):"
    echo "$OTHER_PRINCIPALS" | sed 's/^/      /'
  fi

  WIF_PROVIDER_RESOURCE=$(gcloud iam workload-identity-pools providers describe "$WIF_PROVIDER" \
    --workload-identity-pool="$WIF_POOL" --location=global --format="value(name)")

  echo ""
  echo "    *** ACTION REQUIRED — tell GitHub how to authenticate (variables, not secrets) ***"
  echo ""
  echo "      gh variable set GCP_WORKLOAD_IDENTITY_PROVIDER -R $GITHUB_REPO --body '$WIF_PROVIDER_RESOURCE'"
  echo "      gh variable set GCP_DEPLOY_SERVICE_ACCOUNT     -R $GITHUB_REPO --body '$GITHUB_ACTIONS_SA_EMAIL'"
  echo ""
  echo "    Deploys are accepted only from $GITHUB_REPO on ${GITHUB_DEPLOY_REF/#any/any ref}."
  echo "    No GCP_SA_KEY secret is needed; an old one can be deleted once this works."
  echo ""
else
  # A service account may hold at most 10 keys and each one is a long-lived credential, so a
  # re-run must not mint another. Only create one when the account has none, or on --rotate-key.
  EXISTING_KEYS=$(gcloud iam service-accounts keys list \
    --iam-account="$GITHUB_ACTIONS_SA_EMAIL" --managed-by=user --format="value(name)")

  if [[ -n "$EXISTING_KEYS" && "$ROTATE_KEY" != true ]]; then
    echo "==> GitHub Actions deploy key already exists, skipping (use --rotate-key to mint a new one)."
  else
    echo "==> Generating GitHub Actions service account key..."
    KEY_FILE="$SCRIPT_DIR/../github-actions-key.json"
    if [[ -s "$KEY_FILE" ]]; then
      echo "ERROR: $KEY_FILE already holds a key. Store it as the secret (or delete the file) before minting another." >&2
      exit 1
    fi
    # umask: the key file is readable by this user only
    (umask 077 && gcloud iam service-accounts keys create "$KEY_FILE" \
      --iam-account="$GITHUB_ACTIONS_SA_EMAIL")

    # The key itself is never echoed to the terminal (scrollback, screen shares).
    echo ""
    echo "    *** ACTION REQUIRED ***"
    echo "    Store the key as the GitHub Actions secret GCP_SA_KEY (Settings → Secrets and"
    echo "    variables → Actions). Its value is the base64-encoded key file:"
    echo ""
    echo "      base64 < $KEY_FILE | tr -d '\\n' | gh secret set GCP_SA_KEY"
    echo "      # or copy it, then paste into GitHub (macOS):"
    echo "      base64 < $KEY_FILE | tr -d '\\n' | pbcopy"
    echo ""
    echo "    Then delete the key file:  rm $KEY_FILE"
    if [[ "$ROTATE_KEY" == true && -n "$EXISTING_KEYS" ]]; then
      echo "    After updating the secret, delete the old key(s) so they can no longer be used:"
      echo "      gcloud iam service-accounts keys list --iam-account=$GITHUB_ACTIONS_SA_EMAIL --managed-by=user"
      echo "      gcloud iam service-accounts keys delete <KEY_ID> --iam-account=$GITHUB_ACTIONS_SA_EMAIL"
    fi
    echo ""
  fi
fi

# ---------------------------------------------------------------
# 7. Secret Manager — app secrets
# ---------------------------------------------------------------
echo "==> Creating secrets in Secret Manager..."
create_or_update_secret "bejo-db-password"          "$DB_PASSWORD"
create_or_update_secret "bejo-jwt-secret"           "$JWT_SECRET"
create_or_update_secret "bejo-api-token"            "$API_TOKEN"
create_or_update_secret "bejo-seed-password"        "$SEED_PASSWORD"

# FCM: by default the service sends as its own service account (FIREBASE_PROJECT_ID, defaulting to this
# project) — no key. FIREBASE_CREDENTIALS_BASE64 is only for installs that must use a service-account key
# (e.g. the Firebase project lives elsewhere); then also set the GitHub variable MOUNT_FIREBASE_CREDENTIALS=true.
if [[ -n "${FIREBASE_CREDENTIALS_BASE64:-}" && "${MOUNT_FIREBASE_CREDENTIALS:-false}" == true ]]; then
  create_or_update_secret "bejo-firebase-credentials" "$FIREBASE_CREDENTIALS_BASE64"
else
  echo "    (Firebase: sending push as the service account; no key stored. Set MOUNT_FIREBASE_CREDENTIALS=true"
  echo "     and FIREBASE_CREDENTIALS_BASE64 in .env.gcp only if you need a key.)"
fi

# The QRIS callback shared secret is optional the same way. Set CALLBACK_SECRET in .env.gcp, and
# give the payment provider the same value to send as the X-Callback-Secret header; until the
# provider sends it the callback must stay open, so the mount is opt-in — set the GitHub
# variable MOUNT_CALLBACK_SECRET=true for release deploys once the provider is configured.
if [[ -n "${CALLBACK_SECRET:-}" ]]; then
  create_or_update_secret "bejo-callback-secret" "$CALLBACK_SECRET"
  export MOUNT_CALLBACK_SECRET=true
  echo "    (CALLBACK_SECRET stored. The provider must send it as X-Callback-Secret, and the GitHub"
  echo "     variable MOUNT_CALLBACK_SECRET=true makes release deploys keep mounting it.)"
else
  export MOUNT_CALLBACK_SECRET=false
fi

# The SMTP password (a Zoho application-specific password) is optional: without it the password-reset
# endpoint answers 503. Set MAIL_PASSWORD (plus MAIL_HOST / MAIL_USERNAME) in .env.gcp to enable it. The
# mount is opt-in for CI: set the GitHub variable MOUNT_MAIL_PASSWORD=true so release deploys keep it —
# otherwise the next release would unmount it and silently switch password reset off.
if [[ -n "${MAIL_PASSWORD:-}" ]]; then
  create_or_update_secret "bejo-mail-password" "$MAIL_PASSWORD"
  export MOUNT_MAIL_PASSWORD=true
  echo "    (MAIL_PASSWORD stored. Set the GitHub variable MOUNT_MAIL_PASSWORD=true so release deploys keep"
  echo "     mounting it, and the MAIL_HOST / MAIL_USERNAME / MAIL_FROM variables too.)"
else
  export MOUNT_MAIL_PASSWORD=false
fi

# The ads admin token is optional (Secret Manager rejects an empty payload): only create
# it, and have deploy-cloud-run.sh mount it, when a value is provided in .env.gcp.
if [[ -n "${ADS_ADMIN_TOKEN:-}" ]]; then
  create_or_update_secret "bejo-ads-admin-token" "$ADS_ADMIN_TOKEN"
  export MOUNT_ADS_ADMIN_TOKEN=true
else
  export MOUNT_ADS_ADMIN_TOKEN=false
  echo "    (ADS_ADMIN_TOKEN is blank: ads admin surface stays off. Also set the GitHub Actions"
  echo "     variable MOUNT_ADS_ADMIN_TOKEN=false, or release deploys will fail on the missing secret.)"
fi

# ---------------------------------------------------------------
# 8. Build & push initial Docker image
# ---------------------------------------------------------------
IMAGE="$GCP_REGION-docker.pkg.dev/$GCP_PROJECT_ID/$ARTIFACT_REGISTRY_REPO/$GCP_SERVICE_NAME:latest"

if [[ "$SKIP_DEPLOY" == true ]]; then
  echo "==> --skip-deploy: not building or deploying; the running service is untouched."
else

  # Cloud Build needs permissions on a project it hasn't been used in: projects created since April
  # 2024 run it as the Compute Engine default service account (older ones as
  # PROJECT_NUMBER@cloudbuild.gserviceaccount.com), and neither is granted anything by default — the
  # build fails with "<number>-compute@... does not have storage.objects.get access to the Google
  # Cloud Storage object". Grant the builder role (source access, logs, pushing to Artifact Registry)
  # to whichever of the two exists. Idempotent.
  BUILD_PROJECT_NUMBER=$(gcloud projects describe "$GCP_PROJECT_ID" --format="value(projectNumber)")
  for build_sa in "$BUILD_PROJECT_NUMBER-compute@developer.gserviceaccount.com" \
                  "$BUILD_PROJECT_NUMBER@cloudbuild.gserviceaccount.com"; do
    if gcloud iam service-accounts describe "$build_sa" --project="$GCP_PROJECT_ID" --quiet >/dev/null 2>&1; then
      grant_project_roles "$build_sa" roles/cloudbuild.builds.builder
      if [[ "$ROLES_GRANTED" == true ]]; then
        echo "==> Granted the Cloud Build builder role to $build_sa"
        BUILD_SA_GRANTED=true
      fi
    fi
  done
  # IAM changes take a moment to propagate; a build started instantly can still be refused.
  if [[ "${BUILD_SA_GRANTED:-false}" == true ]]; then sleep 20; fi

  # Build with Cloud Build: runs on native amd64 server-side, so no local Docker/
  # podman and no slow qemu emulation on Apple Silicon. Uploads the source
  # (respecting .gcloudignore, or .gitignore when there is none), builds the Dockerfile,
  # and pushes to Artifact Registry.
  if [[ -n "$(git -C "$SCRIPT_DIR/.." status --porcelain 2>/dev/null)" ]]; then
    echo "    NOTE: the working tree has uncommitted changes — the image is built from the files on disk, not from a commit."
  fi
  echo "==> Building image with Cloud Build (native amd64, no local emulation)..."
  gcloud builds submit "$SCRIPT_DIR/.." --tag="$IMAGE" --quiet

  # ---------------------------------------------------------------
  # 9. Initial Cloud Run deployment
  # ---------------------------------------------------------------
  # Same script the release workflow uses, so env vars/secrets/sizing are defined once.
  bash "$SCRIPT_DIR/deploy-cloud-run.sh" "$IMAGE"
fi

# ---------------------------------------------------------------
# 10. Custom domain mapping (one-time, persists across deployments)
# ---------------------------------------------------------------
# Managed Cloud Run domain mappings live under `gcloud beta run domain-mappings`
# (the GA `gcloud run domain-mappings` group is Anthos-only and rejects --region).
# Requires the domain verified in Google Search Console.
echo "==> Custom domain $CUSTOM_DOMAIN..."
# Idempotent: a re-run must not report the existing mapping as a failure. Only create it when this
# project does not have it yet.
if gcloud beta run domain-mappings describe \
     --domain="$CUSTOM_DOMAIN" --region="$GCP_REGION" --platform=managed >/dev/null 2>&1; then
  mapped_to=$(gcloud beta run domain-mappings describe \
    --domain="$CUSTOM_DOMAIN" --region="$GCP_REGION" --platform=managed --format="value(spec.routeName)")
  echo "    (already mapped in this project to service '$mapped_to' — nothing to do)"
  if [[ "$mapped_to" != "$GCP_SERVICE_NAME" ]]; then
    echo "    WARNING: it points at '$mapped_to', not '$GCP_SERVICE_NAME'. Delete it and re-run to remap:"
    echo "      gcloud beta run domain-mappings delete --domain=$CUSTOM_DOMAIN --region=$GCP_REGION --platform=managed"
  fi
else
  create_args=(--service="$GCP_SERVICE_NAME" --domain="$CUSTOM_DOMAIN" --region="$GCP_REGION" --platform=managed --quiet)
  # Takes the domain over from whichever project holds it now — only on request.
  if [[ "$TAKE_OVER_DOMAIN" == true ]]; then create_args+=(--force-override); fi
  if ! gcloud beta run domain-mappings create "${create_args[@]}"; then
    # A create that fails but leaves the mapping behind (a concurrent or interrupted run) is not a failure.
    if gcloud beta run domain-mappings describe \
         --domain="$CUSTOM_DOMAIN" --region="$GCP_REGION" --platform=managed >/dev/null 2>&1; then
      echo "    (the mapping exists now — continuing)"
    else
      echo "    (not mapped — see the error above. Common causes: $CUSTOM_DOMAIN is not verified in Search Console,"
      echo "     or it is mapped in ANOTHER project: re-run with --take-over-domain to take it over)"
    fi
  fi
fi

# Show where it stands and the DNS record it needs (nothing is printed if there is no mapping).
# `read` returns non-zero on empty input (no mapping), which would abort the script under `set -e`
map_ready=""; rec_type=""; rec_name=""; rec_data=""
read -r map_ready < <(gcloud beta run domain-mappings describe --domain="$CUSTOM_DOMAIN" \
  --region="$GCP_REGION" --platform=managed --format="value(status.conditions[0].status)" 2>/dev/null || true) || true
read -r rec_type rec_name rec_data < <(gcloud beta run domain-mappings describe --domain="$CUSTOM_DOMAIN" \
  --region="$GCP_REGION" --platform=managed \
  --format="value(status.resourceRecords[0].type,status.resourceRecords[0].name,status.resourceRecords[0].rrdata)" \
  2>/dev/null || true) || true
if [[ -n "${rec_type:-}" ]]; then
  echo "    Ready: ${map_ready:-unknown}  (True = certificate issued; Unknown = still being issued, can take a while)"
  echo "    DNS record the domain needs at your registrar/Cloudflare:  $rec_type  $rec_name  ->  $rec_data"
fi

echo ""
echo "==> Done!"
echo "    Cloud Run URL : $(gcloud run services describe "$GCP_SERVICE_NAME" --region="$GCP_REGION" --format="value(status.url)")"
echo "    Custom domain : https://$CUSTOM_DOMAIN  (active after DNS propagates + SSL provisioned)"
