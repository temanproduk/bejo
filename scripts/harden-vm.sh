#!/usr/bin/env bash
# Lock the VM down. Run from your machine AFTER scripts/init-vm.sh. Debian/Ubuntu only.
#
# What it does on the VM:
#   - firewall (ufw): deny all inbound except SSH, 80 and 443. Docker bypasses ufw for ports it
#     publishes, but only Caddy publishes any (80/443) — never add `ports:` for Postgres/app.
#   - SSH: key-only login, no passwords, no root login, only the deploy user may log in
#     (a drop-in file, /etc/ssh/sshd_config.d/00-bejo-hardening.conf)
#   - fail2ban: bans an IP for an hour after 5 failed SSH logins
#   - unattended-upgrades: automatic OS security updates (no automatic reboot — reboot
#     yourself when a kernel update asks for it; Docker's own packages are left alone)
#
# Not locking yourself out — three layers:
#   1. Before touching anything it checks, from here, that the deploy user can log in with
#      your key and use docker. If not, it stops.
#   2. It keeps one authenticated root connection open, arms a timer ON THE VM that undoes the
#      SSH change after 5 minutes, applies the change, then tests a brand-new login as the
#      deploy user. Only if that works does it disarm the timer. If anything fails — or this
#      script is killed — the SSH change reverts itself.
#   3. If it all goes wrong anyway, use the hosting panel's web console and run:
#        rm /etc/ssh/sshd_config.d/00-bejo-hardening.conf && systemctl reload ssh
#
# After it succeeds root can no longer SSH in (that is the point), and the deploy user has no
# sudo — so re-running this script, or other admin work, goes through the web console, or
# temporarily:  VM_ROOT_LOGIN=prohibit-password  (keeps key-only root login; needs a key in
# /root/.ssh/authorized_keys). Set VM_USER=<deploy user> in .env.vm afterwards.
#
# Usage:
#   VM_USER=root bash scripts/harden-vm.sh      (VM_USER comes from .env.vm)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$SCRIPT_DIR/.."
# shellcheck source=lib/vm-common.sh
source "$SCRIPT_DIR/lib/vm-common.sh"

load_vm_env

ROOT_LOGIN="${VM_ROOT_LOGIN:-no}"
case "$ROOT_LOGIN" in
  no|prohibit-password) ;;
  *) die "VM_ROOT_LOGIN must be 'no' or 'prohibit-password' (got '$ROOT_LOGIN')" ;;
esac
CONF=/etc/ssh/sshd_config.d/00-bejo-hardening.conf

# A brand-new connection (not the held-open one) as the deploy user: key only, must reach docker.
deploy_login_ok() {
  [[ "$(ssh "${SSH_OPTS[@]}" -o BatchMode=yes -o ControlPath=none \
        "$VM_DEPLOY_USER@$VM_HOST" 'docker ps >/dev/null 2>&1 && echo ok' 2>/dev/null)" == ok ]]
}

echo "==> Checking that $VM_DEPLOY_USER can log in with a key and use docker"
deploy_login_ok || die "cannot log in as $VM_DEPLOY_USER@$VM_HOST with your key (BatchMode, no password), or docker is not usable.
       Run scripts/init-vm.sh first, and check VM_SSH_KEY / VM_DEPLOY_PUBKEY. Nothing was changed."
echo "    ok"

# One authenticated connection stays open for the whole run; commands go through it, so an
# sshd reload can't cut us off from the VM.
CTL="$(mktemp -u /tmp/bejo-harden.XXXXXX)"
cleanup() { ssh -S "$CTL" -O exit "$VM_USER@$VM_HOST" >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "==> Opening an admin connection as $VM_USER@$VM_HOST"
ssh "${SSH_OPTS[@]}" -M -S "$CTL" -o ControlPersist=15m -fN "$VM_USER@$VM_HOST" \
  || die "cannot SSH to $VM_USER@$VM_HOST as the admin user"
admin() { ssh "${SSH_OPTS[@]}" -S "$CTL" "$VM_USER@$VM_HOST" "$@"; }

admin bash -s -- "$VM_DEPLOY_USER" "$ROOT_LOGIN" "$CONF" <<'REMOTE'
set -euo pipefail
DEPLOY_USER=$1
ROOT_LOGIN=$2
CONF=$3

fail() { echo "ERROR: $*" >&2; exit 1; }

SUDO=""
if [ "$(id -u)" -ne 0 ]; then
  sudo -n true 2>/dev/null || fail "need root or passwordless sudo for this step"
  SUDO="sudo"
fi
command -v apt-get >/dev/null 2>&1 || fail "apt-get not found - this script supports Debian/Ubuntu only"
export DEBIAN_FRONTEND=noninteractive

# Never apply a lockout that has no way back in.
deploy_home=$(getent passwd "$DEPLOY_USER" | cut -d: -f6)
[ -s "$deploy_home/.ssh/authorized_keys" ] || fail "$DEPLOY_USER has no authorized key - run init-vm.sh first"
if [ "$ROOT_LOGIN" = prohibit-password ]; then
  $SUDO test -s /root/.ssh/authorized_keys || fail "VM_ROOT_LOGIN=prohibit-password but /root/.ssh/authorized_keys is empty"
fi
if [ -d /etc/ssh/sshd_config.d ] && grep -qiE '^[[:space:]]*Include[[:space:]]+/etc/ssh/sshd_config\.d/\*\.conf' /etc/ssh/sshd_config; then
  :
else
  fail "/etc/ssh/sshd_config does not include sshd_config.d/*.conf, so a drop-in would be ignored. Apply the SSH settings by hand."
fi

echo "==> Packages (ufw, fail2ban, unattended-upgrades)"
$SUDO apt-get update -qq
$SUDO apt-get install -y -qq ufw fail2ban python3-systemd unattended-upgrades >/dev/null

echo "==> Automatic security updates"
printf 'APT::Periodic::Update-Package-Lists "1";\nAPT::Periodic::Unattended-Upgrade "1";\n' \
  | $SUDO tee /etc/apt/apt.conf.d/20auto-upgrades >/dev/null

echo "==> Firewall (ufw)"
# Allow whatever port sshd really listens on, not an assumed 22.
ssh_ports=$($SUDO sshd -T 2>/dev/null | awk '$1 == "port" {print $2}')
[ -n "$ssh_ports" ] || ssh_ports=22
$SUDO ufw default deny incoming >/dev/null
$SUDO ufw default allow outgoing >/dev/null
for p in $ssh_ports; do $SUDO ufw allow "$p/tcp" >/dev/null; done
$SUDO ufw allow 80/tcp >/dev/null
$SUDO ufw allow 443/tcp >/dev/null
$SUDO ufw --force enable >/dev/null
echo "    inbound allowed: ssh ($(echo $ssh_ports)), 80, 443"

echo "==> fail2ban (sshd)"
$SUDO tee /etc/fail2ban/jail.d/bejo.local >/dev/null <<'EOF'
[sshd]
enabled = true
backend = systemd
maxretry = 5
findtime = 10m
bantime = 1h
EOF
$SUDO systemctl enable fail2ban >/dev/null 2>&1 || true
$SUDO systemctl restart fail2ban
if $SUDO fail2ban-client status sshd >/dev/null 2>&1; then
  echo "    sshd jail active"
else
  echo "    WARNING: the sshd jail is not active - check: systemctl status fail2ban"
fi

echo "==> SSH"
allow="$DEPLOY_USER"
[ "$ROOT_LOGIN" = prohibit-password ] && allow="$allow root"
write_conf() {   # $1 = name of the keyboard-interactive keyword (it was renamed in OpenSSH 8.7)
  $SUDO tee "$CONF" >/dev/null <<EOF
# Managed by scripts/harden-vm.sh - remove this file to undo.
PermitRootLogin $ROOT_LOGIN
PasswordAuthentication no
$1 no
PubkeyAuthentication yes
AllowUsers $allow
MaxAuthTries 3
LoginGraceTime 30
X11Forwarding no
EOF
}
write_conf KbdInteractiveAuthentication
if ! $SUDO sshd -t 2>/dev/null; then write_conf ChallengeResponseAuthentication; fi
if ! $SUDO sshd -t; then
  $SUDO rm -f "$CONF"
  fail "sshd rejected the new configuration; it was removed and nothing changed"
fi

# Dead-man's switch: if nobody disarms this within 5 minutes, the SSH change undoes itself.
$SUDO systemctl stop bejo-sshd-revert.timer >/dev/null 2>&1 || true
if command -v systemd-run >/dev/null 2>&1; then
  $SUDO systemd-run --quiet --unit=bejo-sshd-revert --on-active=300 \
    /bin/sh -c "rm -f $CONF; systemctl reload ssh 2>/dev/null || systemctl reload sshd"
  echo "    safety timer armed: these SSH changes revert in 5 minutes unless confirmed"
else
  echo "    WARNING: systemd-run not found, so there is no automatic revert"
fi
$SUDO systemctl reload ssh 2>/dev/null || $SUDO systemctl reload sshd
$SUDO sshd -T | grep -E '^(permitrootlogin|passwordauthentication|allowusers|maxauthtries) ' | sed 's/^/    /'
REMOTE

echo "==> Verifying with a NEW connection (the safety timer is still armed)"
if deploy_login_ok; then
  echo "    $VM_DEPLOY_USER still logs in with the key and reaches docker"
else
  echo "!! The deploy user can no longer log in. Reverting the SSH change now." >&2
  admin "if [ \"\$(id -u)\" -eq 0 ]; then S=; else S=sudo; fi; \$S rm -f '$CONF'; \$S systemctl reload ssh 2>/dev/null || \$S systemctl reload sshd" || true
  die "reverted. Nothing is locked out; investigate with the web console if needed."
fi

if [[ "$ROOT_LOGIN" == no && "$VM_USER" == root ]]; then
  if ssh "${SSH_OPTS[@]}" -o BatchMode=yes -o ControlPath=none -o ConnectTimeout=10 \
       "root@$VM_HOST" true >/dev/null 2>&1; then
    echo "!! root can STILL log in over SSH (unexpected) - check: sshd -T | grep permitrootlogin" >&2
  else
    echo "    root login is refused, as intended"
  fi
fi

echo "==> Confirmed - disarming the safety timer"
admin 'if [ "$(id -u)" -eq 0 ]; then S=; else S=sudo; fi; $S systemctl stop bejo-sshd-revert.timer 2>/dev/null || true'

echo ""
echo "==> Hardened."
echo "    Now set VM_USER=$VM_DEPLOY_USER in .env.vm (and the VM_USER variable in GitHub), then:"
echo "      bash scripts/deploy-vm.sh"
echo "    Root can no longer SSH in. For admin work use the hosting panel's web console."
