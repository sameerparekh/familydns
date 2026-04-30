#!/usr/bin/env bash
# One-time host setup for a fresh Linux deploy target.
#
# Creates the familydns user, /opt/familydns layout, clones the repo, and
# installs the systemd unit. Idempotent — safe to re-run.
#
# Usage:  sudo ./bootstrap-host.sh [git-url]
#         (default: git@github.com:sameerparekh/familydns.git)

set -euo pipefail

REPO_URL="${1:-git@github.com:sameerparekh/familydns.git}"
PREFIX="${FAMILYDNS_PREFIX:-/opt/familydns}"
USER_NAME="familydns"

[ "$EUID" -eq 0 ] || { echo "must run as root (sudo)"; exit 1; }

# ── Build tooling: JDK 21, Node 22, Coursier+Mill+scalafmt ────────────────
if ! command -v java >/dev/null 2>&1 || ! java -version 2>&1 | grep -qE 'version "(21|22|23|24)'; then
  if command -v apt-get >/dev/null 2>&1; then
    apt-get update -qq
    apt-get install -y -qq openjdk-21-jdk-headless ca-certificates curl git
  fi
fi
if ! command -v node >/dev/null 2>&1; then
  if command -v apt-get >/dev/null 2>&1; then
    curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
    apt-get install -y -qq nodejs
  fi
fi
if [ ! -x /usr/local/bin/cs ]; then
  ARCH="$(uname -m)"; case "$ARCH" in
    x86_64) CS_URL="https://github.com/coursier/coursier/releases/latest/download/cs-x86_64-pc-linux.gz" ;;
    aarch64|arm64) CS_URL="https://github.com/coursier/coursier/releases/latest/download/cs-aarch64-pc-linux.gz" ;;
    *) echo "unsupported arch $ARCH" >&2; exit 1 ;;
  esac
  curl -fsSL "$CS_URL" | gunzip > /usr/local/bin/cs
  chmod +x /usr/local/bin/cs
fi
/usr/local/bin/cs install --install-dir /usr/local/bin --quiet mill:1.1.5 scalafmt

# ── User & directories ────────────────────────────────────────────────────
if ! id -u "$USER_NAME" >/dev/null 2>&1; then
  useradd --system --create-home --home-dir /var/lib/familydns \
          --shell /usr/sbin/nologin "$USER_NAME"
fi
install -d -o "$USER_NAME" -g "$USER_NAME" "$PREFIX"
install -d -o "$USER_NAME" -g "$USER_NAME" /var/lib/familydns
install -d -o "$USER_NAME" -g "$USER_NAME" /var/log/familydns
install -d -m 0750 -o "$USER_NAME" -g "$USER_NAME" /etc/familydns

# ── Clone or update the repo ──────────────────────────────────────────────
if [ ! -d "$PREFIX/repo/.git" ]; then
  sudo -u "$USER_NAME" git clone --depth 50 "$REPO_URL" "$PREFIX/repo"
else
  sudo -u "$USER_NAME" git -C "$PREFIX/repo" fetch --prune origin
fi

# ── Install systemd unit (symlinked from the repo so updates flow with git)
ln -sfn "$PREFIX/repo/deploy/familydns-api.service" \
        /etc/systemd/system/familydns-api.service

# ── Optional: install a deploy timer that re-runs scripts/deploy.sh hourly
cat >/etc/systemd/system/familydns-deploy.service <<EOF
[Unit]
Description=Pull and deploy FamilyDNS from main
After=network-online.target

[Service]
Type=oneshot
User=$USER_NAME
WorkingDirectory=$PREFIX/repo
ExecStart=$PREFIX/repo/scripts/deploy.sh
EOF

cat >/etc/systemd/system/familydns-deploy.timer <<EOF
[Unit]
Description=Run familydns-deploy hourly

[Timer]
OnBootSec=2min
OnUnitActiveSec=1h
Unit=familydns-deploy.service

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload

# ── Sample config (only if not already present) ───────────────────────────
if [ ! -f /etc/familydns/application.conf ]; then
  install -m 0640 -o "$USER_NAME" -g "$USER_NAME" \
    "$PREFIX/repo/config/application.conf.example" \
    /etc/familydns/application.conf
  echo "Wrote /etc/familydns/application.conf — edit secrets before starting the service."
fi

# Allow the deploy user to restart the service without a password
cat >/etc/sudoers.d/familydns-deploy <<EOF
$USER_NAME ALL=(root) NOPASSWD: /bin/systemctl restart familydns-api.service, /usr/bin/install, /bin/mv, /bin/rm, /bin/cp, /usr/bin/tee
EOF
chmod 0440 /etc/sudoers.d/familydns-deploy

cat <<MSG
Bootstrap complete.

Next steps:
  1. Edit /etc/familydns/application.conf and set jwt.secret + db.password
  2. (Optional) write env overrides in /etc/familydns/api.env
  3. systemctl enable --now familydns-api.service
  4. systemctl enable --now familydns-deploy.timer    # auto-pull & redeploy
  5. Run an initial deploy:  sudo -u $USER_NAME $PREFIX/repo/scripts/deploy.sh

The service unit and deploy script are tracked in the repo
($PREFIX/repo/deploy/, $PREFIX/repo/scripts/) — to roll out changes,
merge to main and the timer (or a manual deploy.sh run) will pick them up.
MSG
