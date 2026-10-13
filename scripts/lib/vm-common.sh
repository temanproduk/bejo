# Shared helpers for scripts/init-vm.sh and scripts/deploy-vm.sh. Source it, don't run it.
#
# Reads the local, gitignored .env.vm (override the path with ENV_FILE). Variables starting
# with VM_ describe how to reach the VM and stay local; everything else is app configuration
# that deploy-vm.sh writes to the VM as .env.
#
# With no .env.vm at all, VM_HOST and VM_USER may come from the environment instead — that is
# how the GitHub Actions deploy runs (with --keep-env, so no app config is needed there).

die() { echo "ERROR: $*" >&2; exit 1; }

require_vars() {
  local v
  for v in "$@"; do
    [[ -n "${!v:-}" ]] || die "$v is not set in $ENV_FILE"
  done
}

load_vm_env() {
  ENV_FILE="${ENV_FILE:-$REPO_DIR/.env.vm}"
  if [[ -f "$ENV_FILE" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    set +a
  elif [[ -z "${VM_HOST:-}" || -z "${VM_USER:-}" ]]; then
    die "$ENV_FILE not found (and VM_HOST/VM_USER are not set in the environment). Copy .env.vm.example to .env.vm and fill in the values."
  fi

  require_vars VM_HOST VM_USER
  VM_SSH_PORT="${VM_SSH_PORT:-22}"
  VM_APP_DIR="${VM_APP_DIR:-bejo}"
  # Relative to the VM user's home: the scripts build paths like $HOME/$VM_APP_DIR.
  [[ "$VM_APP_DIR" != /* ]] || die "VM_APP_DIR must be relative to the VM user's home, not absolute"

  # The unprivileged account that owns the app and runs deploys (and that CI logs in as).
  # Logging in as root, init-vm.sh creates it; otherwise it is simply the login user.
  if [[ -z "${VM_DEPLOY_USER:-}" ]]; then
    if [[ "$VM_USER" == root ]]; then VM_DEPLOY_USER=deploy; else VM_DEPLOY_USER="$VM_USER"; fi
  fi

  # Default accept-new: trust the host key on first contact, refuse if it later changes. CI sets
  # VM_HOST_KEY_CHECKING=yes so only the host key pinned in known_hosts is accepted.
  SSH_OPTS=(-p "$VM_SSH_PORT" -o ServerAliveInterval=30
            -o StrictHostKeyChecking="${VM_HOST_KEY_CHECKING:-accept-new}")
  if [[ -n "${VM_SSH_KEY:-}" ]]; then
    SSH_OPTS+=(-i "${VM_SSH_KEY/#\~/$HOME}")
  fi
}

# Print the first line of the public key at $1 (a path, ~ allowed), or nothing if there is no
# such file. Only well-formed keys are returned.
read_pubkey() {
  local f="${1/#\~/$HOME}" line
  [[ -n "$f" && -f "$f" ]] || return 0
  line="$(head -n1 "$f")"
  [[ "$line" =~ ^(ssh-ed25519|ssh-rsa|ecdsa-sha2-[a-z0-9-]+)\  ]] && printf '%s' "$line"
  return 0
}

# The admin's public key to authorize for the deploy user: VM_DEPLOY_PUBKEY, else the public
# half of VM_SSH_KEY, else the usual defaults. Empty if none is found.
find_admin_pubkey() {
  local candidate key
  for candidate in "${VM_DEPLOY_PUBKEY:-}" "${VM_SSH_KEY:+$VM_SSH_KEY.pub}" "~/.ssh/id_ed25519.pub" "~/.ssh/id_rsa.pub"; do
    key="$(read_pubkey "$candidate")"
    if [[ -n "$key" ]]; then printf '%s' "$key"; return 0; fi
  done
  return 0
}

# Run a command on the VM. Remote scripts are fed through stdin (`remote bash -s -- args`).
remote() { ssh "${SSH_OPTS[@]}" "$VM_USER@$VM_HOST" "$@"; }

# Render the app configuration as a docker-compose env file: every non-empty variable from
# .env.vm except VM_*. Values are single-quoted so compose treats them literally — a `$` in a
# password would otherwise be interpolated. A single quote or newline can't be represented
# that way, so those are refused rather than silently mangled.
render_remote_env() {
  local name value
  while IFS= read -r name; do
    [[ "$name" == VM_* ]] && continue
    value="${!name:-}"
    [[ -n "$value" ]] || continue
    if [[ "$value" == *"'"* || "$value" == *$'\n'* ]]; then
      echo "ERROR: $name contains a single quote or a newline, which the env file can't hold safely" >&2
      return 1
    fi
    printf "%s='%s'\n" "$name" "$value"
  done < <(grep -oE '^[A-Za-z_][A-Za-z0-9_]*=' "$ENV_FILE" | sed 's/=$//' | awk '!seen[$0]++')
}
