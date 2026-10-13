#!/usr/bin/env bash
# One-time setup of a fresh Linux VM for bejo. Run from your machine; it does its work over
# SSH, as root (or a user with passwordless sudo). Safe to re-run.
#
# What it does on the VM:
#   - installs Docker Engine + the Compose plugin (if missing) and enables the service
#   - creates an unprivileged deploy user (VM_DEPLOY_USER, default `deploy` when you log in
#     as root), authorizes your SSH key — and, if given, the CI key — for it, and puts it in
#     the docker group. The app, its .env, releases and backups all live in that user's home,
#     never in root's. Deploys (and GitHub Actions) log in as this user.
#   - adds a 2 GB swapfile on VMs with < 4 GB RAM and no swap (the Maven build + JVM +
#     Postgres are tight on a small box)
#   - installs a nightly database backup (cron for the deploy user, 03:00 VM time, 7 days)
#
# Order of use:
#   1. cp .env.vm.example .env.vm   and set VM_HOST, VM_USER=root and the key settings.
#   2. Make sure key login to root works:  ssh-copy-id root@<host>   (avoids password prompts).
#   3. bash scripts/init-vm.sh      (this script)
#   4. bash scripts/harden-vm.sh    (firewall, SSH lockdown, fail2ban, auto-updates)
#   5. set VM_USER=deploy in .env.vm, then:  bash scripts/deploy-vm.sh
#
# Also before the first deploy: point a DNS A record for DOMAIN at the VM, and if the hosting
# panel has its own firewall, allow inbound TCP 80 + 443 there. harden-vm.sh sets up the
# host firewall (ufw) itself. Never open 5432 or 8080 — Postgres and the app are only
# reachable inside the compose network.
#
# Region note: avoid mainland China. FCM push — the core of the payment alerts — is not
# reachable from there, and a domain served from the mainland on 80/443 needs an ICP filing.
#
# Usage:
#   bash scripts/init-vm.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$SCRIPT_DIR/.."
# shellcheck source=lib/vm-common.sh
source "$SCRIPT_DIR/lib/vm-common.sh"

load_vm_env

# Authorize the admin key (and optionally the CI key) for the deploy user. Without an admin
# key the deploy user could never be logged into — and harden-vm.sh would then lock you out.
ADMIN_KEY="$(find_admin_pubkey)"
CI_KEY="$(read_pubkey "${VM_CI_PUBKEY:-}")"
if [[ -n "${VM_CI_PUBKEY:-}" && -z "$CI_KEY" ]]; then
  die "VM_CI_PUBKEY=$VM_CI_PUBKEY is not a readable public key file"
fi
if [[ "$VM_DEPLOY_USER" != "$VM_USER" && -z "$ADMIN_KEY" ]]; then
  die "no public key found to authorize for $VM_DEPLOY_USER. Set VM_DEPLOY_PUBKEY (or VM_SSH_KEY) in $ENV_FILE, or run: ssh-keygen -t ed25519"
fi
b64() { if [[ -n "$1" ]]; then printf '%s' "$1" | base64 | tr -d '\n'; else printf '%s' -; fi; }

echo "==> Connecting to $VM_USER@$VM_HOST ..."
remote true || die "cannot SSH to $VM_USER@$VM_HOST (check VM_HOST / VM_USER / VM_SSH_KEY and any panel firewall)"

# `-` stands in for "unset": ssh would drop an empty argument.
remote bash -s -- "$VM_APP_DIR" "${VM_DOCKER_INSTALL_MIRROR:--}" "${VM_DOCKER_REGISTRY_MIRROR:--}" \
  "$VM_DEPLOY_USER" "$(b64 "$ADMIN_KEY")" "$(b64 "$CI_KEY")" <<'REMOTE'
set -euo pipefail
APP_DIR=$1
INSTALL_MIRROR=$2
REGISTRY_MIRROR=$3
DEPLOY_USER=$4
ADMIN_KEY_B64=$5
CI_KEY_B64=$6

SUDO=""
[ "$(id -u)" -eq 0 ] || SUDO="sudo"

echo "==> Docker"
if command -v docker >/dev/null 2>&1; then
  echo "    already installed: $(docker --version)"
else
  curl -fsSL https://get.docker.com -o /tmp/get-docker.sh
  if [ "$INSTALL_MIRROR" != "-" ]; then
    $SUDO sh /tmp/get-docker.sh --mirror "$INSTALL_MIRROR"
  else
    $SUDO sh /tmp/get-docker.sh
  fi
  rm -f /tmp/get-docker.sh
fi
$SUDO systemctl enable --now docker
$SUDO docker compose version >/dev/null 2>&1 \
  || { echo "ERROR: the 'docker compose' plugin is missing (need Compose v2)" >&2; exit 1; }
echo "    $($SUDO docker compose version)"

if [ "$REGISTRY_MIRROR" != "-" ]; then
  if [ -f /etc/docker/daemon.json ]; then
    echo "    /etc/docker/daemon.json already exists - not overwriting it. Add this yourself:"
    echo "      \"registry-mirrors\": [\"$REGISTRY_MIRROR\"]"
  else
    printf '{\n  "registry-mirrors": ["%s"]\n}\n' "$REGISTRY_MIRROR" | $SUDO tee /etc/docker/daemon.json >/dev/null
    $SUDO systemctl restart docker
    echo "    registry mirror set to $REGISTRY_MIRROR"
  fi
fi

echo "==> Deploy user ($DEPLOY_USER)"
if id "$DEPLOY_USER" >/dev/null 2>&1; then
  echo "    already exists"
else
  $SUDO useradd -m -s /bin/bash "$DEPLOY_USER"
  echo "    created (no password, no sudo: it can only log in with a key)"
fi
# docker-group access is effectively root, so only this account gets it. It applies from the
# user's next login.
$SUDO usermod -aG docker "$DEPLOY_USER"
DEPLOY_HOME=$(getent passwd "$DEPLOY_USER" | cut -d: -f6)

if [ "$ADMIN_KEY_B64" != "-" ] || [ "$CI_KEY_B64" != "-" ]; then
  $SUDO install -d -m 700 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "$DEPLOY_HOME/.ssh"
  auth="$DEPLOY_HOME/.ssh/authorized_keys"
  $SUDO touch "$auth"
  $SUDO chown "$DEPLOY_USER:$DEPLOY_USER" "$auth"
  $SUDO chmod 600 "$auth"
  add_key() {
    if $SUDO grep -qxF "$1" "$auth"; then
      echo "    key already authorized: $(echo "$1" | awk '{print $NF}')"
    else
      echo "$1" | $SUDO tee -a "$auth" >/dev/null
      echo "    authorized: $(echo "$1" | awk '{print $NF}')"
    fi
  }
  if [ "$ADMIN_KEY_B64" != "-" ]; then add_key "$(printf '%s' "$ADMIN_KEY_B64" | base64 -d)"; fi
  # The CI key can do exactly what deploy scripts need (commands over stdin) and nothing else:
  # `restrict` turns off pty allocation, port/agent/X11 forwarding and ~/.ssh/rc.
  if [ "$CI_KEY_B64" != "-" ]; then add_key "restrict $(printf '%s' "$CI_KEY_B64" | base64 -d)"; fi
fi

echo "==> Swap"
mem_mb=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
if [ -n "$(swapon --show --noheadings)" ]; then
  echo "    swap already present"
elif [ "$mem_mb" -ge 4096 ]; then
  echo "    ${mem_mb} MB RAM, no swap needed"
else
  echo "    ${mem_mb} MB RAM and no swap: creating a 2 GB swapfile"
  $SUDO fallocate -l 2G /swapfile 2>/dev/null || $SUDO dd if=/dev/zero of=/swapfile bs=1M count=2048 status=none
  $SUDO chmod 600 /swapfile
  $SUDO mkswap /swapfile >/dev/null
  $SUDO swapon /swapfile
  grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' | $SUDO tee -a /etc/fstab >/dev/null
fi

echo "==> Directories ($DEPLOY_HOME/$APP_DIR)"
APP_PATH="$DEPLOY_HOME/$APP_DIR"
# 700: the directory will hold .env (secrets) and the database backups.
$SUDO install -d -m 700 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "$APP_PATH" "$APP_PATH/releases" "$APP_PATH/backups"

echo "==> Nightly database backup (cron, as $DEPLOY_USER)"
if command -v crontab >/dev/null 2>&1; then
  line="0 3 * * * BACKUP_DIR=$APP_PATH/backups bash $APP_PATH/current/scripts/vm-backup.sh >> $APP_PATH/backups/backup.log 2>&1"
  { $SUDO crontab -u "$DEPLOY_USER" -l 2>/dev/null | grep -v 'vm-backup.sh' || true; echo "$line"; } \
    | $SUDO crontab -u "$DEPLOY_USER" -
  echo "    installed (fails harmlessly until the first deploy creates 'current')"
else
  echo "    WARNING: crontab not found - install cron or schedule scripts/vm-backup.sh yourself"
fi
REMOTE

echo ""
echo "==> VM is ready. Deploy user: $VM_DEPLOY_USER"
echo "    Next:"
echo "      1. Check the deploy user can log in:   ssh ${VM_SSH_KEY:+-i $VM_SSH_KEY }-p $VM_SSH_PORT $VM_DEPLOY_USER@$VM_HOST docker ps"
echo "      2. Lock the VM down:                   bash scripts/harden-vm.sh"
echo "      3. Set VM_USER=$VM_DEPLOY_USER in .env.vm, then:    bash scripts/deploy-vm.sh"
echo "    Also: DNS A record for ${DOMAIN:-your domain} -> $VM_HOST, and 80/443 open in any panel firewall."
