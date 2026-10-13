#!/usr/bin/env bash
# Deploy bejo to a VM with Docker Compose (Caddy → app → Postgres, see
# docker-compose.prod.yml). Run from your machine; the image is built ON the VM, so no
# container registry is needed.
#
# What a deploy does:
#   1. ships the files the build needs (Dockerfile, pom.xml, src, compose + Caddyfile) as a
#      new release in ~/<VM_APP_DIR>/releases/<git-sha>
#   2. writes your .env.vm to the VM as .env (mode 600; secrets travel over SSH stdin, never
#      on a command line)
#   3. `docker compose up -d --build --wait`: builds the image tagged with the release id,
#      recreates the containers and waits for their health checks
#   4. if the new release doesn't come up healthy, prints the app logs and puts the
#      previous release back
#   5. prunes old releases/images (the last 3 are kept) and checks https://$DOMAIN/actuator/health
#
# The app is down for the ~30-60s the new container takes to start (single VM, no
# blue/green). Postgres and the data volume are never touched by a deploy.
#
# Usage:
#   bash scripts/deploy-vm.sh                 # deploy the current commit
#   bash scripts/deploy-vm.sh --allow-dirty   # deploy the working tree incl. uncommitted changes
#   bash scripts/deploy-vm.sh --keep-env      # deploy code only; keep the .env already on the VM
#   bash scripts/deploy-vm.sh --rollback      # go back to the previous release
#
# --keep-env is what the GitHub Actions deploy uses: secrets then live only on the VM (and
# in your local .env.vm), never in GitHub. To change app config, run a normal deploy from
# your machine. It needs a .env on the VM already, i.e. one earlier deploy without it.
#
# A rollback restarts the old CODE; it never reverts database migrations (Flyway is
# forward-only), so only roll back across releases whose migrations are backwards-compatible.
#
# Prerequisite: scripts/init-vm.sh has been run once for this VM.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$SCRIPT_DIR/.."
# shellcheck source=lib/vm-common.sh
source "$SCRIPT_DIR/lib/vm-common.sh"

# Everything the image build and the stack need — nothing else is shipped.
DEPLOY_PATHS=(Dockerfile pom.xml src docker-compose.prod.yml docker/Caddyfile scripts/vm-backup.sh)

MODE=deploy
ALLOW_DIRTY=false
KEEP_ENV=false
for arg in "$@"; do
  case "$arg" in
    --rollback)    MODE=rollback ;;
    --allow-dirty) ALLOW_DIRTY=true ;;
    --keep-env)    KEEP_ENV=true ;;
    -h|--help)     sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d' | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown argument: $arg (supported: --rollback, --allow-dirty, --keep-env, --help)" ;;
  esac
done

load_vm_env

TAG="-"
if [[ "$MODE" == deploy ]]; then
  if [[ "$KEEP_ENV" != true ]]; then
    require_vars DOMAIN DB_PASSWORD API_TOKEN JWT_SECRET FIREBASE_CREDENTIALS_BASE64 SEED_PASSWORD
  fi

  cd "$REPO_DIR"
  SHA="$(git rev-parse --short=12 HEAD)"
  if [[ -n "$(git status --porcelain)" ]]; then
    [[ "$ALLOW_DIRTY" == true ]] \
      || die "working tree has uncommitted changes. Commit them, or pass --allow-dirty to deploy them anyway."
    TAG="$SHA-dirty-$(date +%Y%m%d%H%M%S)"
    echo "!! Deploying UNCOMMITTED changes on top of $SHA as $TAG"
  else
    TAG="$SHA"
  fi
  echo "==> Deploying $TAG ($(git rev-parse --abbrev-ref HEAD)) to $VM_USER@$VM_HOST:~/$VM_APP_DIR"

  if [[ "$KEEP_ENV" == true ]]; then
    echo "==> Keeping the .env already on the VM"
    remote "test -f '$VM_APP_DIR/.env'" \
      || die "no .env on the VM yet. Run a normal deploy (without --keep-env) from your machine once to create it."
  else
    # Render first so a bad value aborts before anything is sent or replaced on the VM.
    REMOTE_ENV="$(render_remote_env)"

    echo "==> Uploading config"
    printf '%s\n' "$REMOTE_ENV" | remote "mkdir -p '$VM_APP_DIR' && cd '$VM_APP_DIR' && umask 077 && cat > .env.new && mv .env.new .env"
  fi

  echo "==> Uploading release"
  # Tracked + untracked-but-not-ignored files under DEPLOY_PATHS, skipping files deleted on
  # disk but still in the index. COPYFILE_DISABLE stops macOS tar adding ._* resource forks.
  release_files() {
    git ls-files -z --cached --others --exclude-standard -- "${DEPLOY_PATHS[@]}" \
      | while IFS= read -r -d '' f; do
          [[ -e "$f" ]] && printf '%s\0' "$f"
        done
  }
  release_files | COPYFILE_DISABLE=1 tar -cf - --null -T - \
    | remote "cd '$VM_APP_DIR' && rm -rf 'releases/$TAG' && mkdir -p 'releases/$TAG' && tar -xf - -C 'releases/$TAG'"
fi

echo "==> Building and starting on the VM (the first build downloads Maven dependencies — be patient)"
remote bash -s -- "$VM_APP_DIR" "$MODE" "$TAG" <<'REMOTE'
set -euo pipefail
APP_DIR=$1
MODE=$2
TAG=$3
cd "$APP_DIR"
[ -f .env ] || { echo "ERROR: $PWD/.env is missing - run a normal deploy from your machine first" >&2; exit 1; }

current_tag() { if [ -L current ]; then basename "$(readlink current)"; fi; }
previous_tag() { if [ -L previous ]; then basename "$(readlink previous)"; fi; }

# Build (layers are cached when nothing changed), start, and wait for the health checks.
activate() {
  local tag=$1
  ln -sfn "$PWD/.env" "releases/$tag/.env"
  ( cd "releases/$tag" && BEJO_TAG="$tag" docker compose -f docker-compose.prod.yml \
      up -d --build --remove-orphans --wait --wait-timeout 240 )
}

prune() {
  local keep n=0 d
  keep=" $(current_tag) $(previous_tag) "
  for d in $(ls -1t releases); do
    n=$((n + 1))
    if [ "$n" -le 3 ] || [[ "$keep" == *" $d "* ]]; then continue; fi
    docker rmi "bejo:$d" >/dev/null 2>&1 || true
    rm -rf "releases/$d"
    echo "    pruned release $d"
  done
  docker image prune -f >/dev/null
  docker builder prune -f --filter until=168h >/dev/null
}

if [ "$MODE" = deploy ]; then
  old=$(current_tag)
  if activate "$TAG"; then
    if [ -n "$old" ] && [ "$old" != "$TAG" ]; then ln -sfn "releases/$old" previous; fi
    ln -sfn "releases/$TAG" current
    echo "==> $TAG is live"
  else
    echo "!! $TAG did not become healthy. Recent app logs:" >&2
    ( cd "releases/$TAG" && docker compose -f docker-compose.prod.yml logs --tail 80 app ) >&2 || true
    if [ -n "$old" ] && [ "$old" != "$TAG" ]; then
      echo "==> Putting the previous release ($old) back" >&2
      if activate "$old"; then
        echo "==> $old is live again" >&2
      else
        echo "!! Restoring $old failed too - the site is down. Inspect: docker compose logs" >&2
      fi
    else
      echo "!! No previous release to fall back to." >&2
    fi
    exit 1
  fi
else
  [ -L previous ] || { echo "ERROR: there is no previous release to roll back to" >&2; exit 1; }
  prev=$(previous_tag)
  [ -d "releases/$prev" ] || { echo "ERROR: release $prev no longer exists on the VM" >&2; exit 1; }
  cur=$(current_tag)
  activate "$prev"
  ln -sfn "releases/$prev" current
  if [ -n "$cur" ]; then ln -sfn "releases/$cur" previous; fi   # rolling back again toggles
  echo "==> Rolled back to $prev"
fi

prune
REMOTE

if [[ "$MODE" == deploy && -z "${DOMAIN:-}" ]]; then
  echo "==> Skipping the public health check (DOMAIN is not set)"
elif [[ "$MODE" == deploy ]]; then
  echo "==> Checking https://$DOMAIN/actuator/health"
  ok=false
  for _ in 1 2 3 4 5 6; do
    if curl -fsS --max-time 10 -o /dev/null "https://$DOMAIN/actuator/health"; then ok=true; break; fi
    sleep 5
  done
  if [[ "$ok" == true ]]; then
    echo "==> Done: https://$DOMAIN is serving $TAG"
  else
    echo "!! The containers are healthy but https://$DOMAIN did not answer. On a first deploy this"
    echo "   usually means DNS isn't pointing at the VM yet, ports 80/443 are closed in the security"
    echo "   group, or Caddy is still obtaining the certificate (ssh in: docker logs bejo-caddy)."
    exit 1
  fi
fi
