#!/usr/bin/env bash
# Installs or updates the BlockbusterTV server: PocketBase as a systemd service listening
# on 127.0.0.1 only. Plesk's nginx then serves it publicly over HTTPS (see server/README.md).
#
# With Plesk Git (recommended): point APP_DIR at the folder Plesk deploys the repo to.
# PocketBase then runs straight from that folder, so each Git deploy updates the app:
# hook changes restart it automatically, and the phone app files are served as they are.
#
#   sudo APP_DIR=/var/www/vhosts/psybob.uk/blockbuster-app bash install.sh
#
# Without APP_DIR the script clones the repo itself into /opt/blockbustertv (re-run to update).
#
# It can also run non-interactively, e.g. as a Plesk Scheduled Task (Run Now) as root:
# set ADMIN_EMAIL and ADMIN_PASSWORD to create the dashboard admin on first install.
#
# Re-running is safe: the database is never touched except to apply new migrations.
set -euo pipefail
# Scheduled tasks start with a minimal PATH.
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DEBIAN_FRONTEND=noninteractive

PB_VERSION="${PB_VERSION:-0.40.6}"
PORT="${PORT:-8095}"
APP_DIR="${APP_DIR:-}"
REPO="${REPO:-https://github.com/njdyson/BlockbusterTV.git}"
BRANCH="${BRANCH:-main}"
BIN_DIR=/opt/blockbustertv
SERVICE=blockbustertv

[ "$(id -u)" = 0 ] || { echo "Please run as root (sudo bash install.sh)"; exit 1; }
for tool in git unzip curl; do
  command -v "$tool" >/dev/null || apt-get install -y "$tool"
done
mkdir -p "$BIN_DIR"

if [ -n "$APP_DIR" ]; then
  # Plesk Git mode: run as the subscription's user, who owns the deployed files.
  [ -d "$APP_DIR/server/pb_hooks" ] || { echo "$APP_DIR doesn't look like the deployed repo (no server/pb_hooks). Check the Git deployment path in Plesk."; exit 1; }
  SRC="$APP_DIR/server"
  RUN_AS=$(stat -c %U "$APP_DIR")
  DATA="${DATA:-$(dirname "$APP_DIR")/blockbuster-data}"
else
  # Standalone mode: copy the app from GitHub into /opt/blockbustertv.
  id "$SERVICE" >/dev/null 2>&1 || useradd --system --home "$BIN_DIR" --shell /usr/sbin/nologin "$SERVICE"
  echo "Fetching BlockbusterTV ($BRANCH)…"
  tmp=$(mktemp -d)
  git clone --quiet --depth 1 --branch "$BRANCH" "$REPO" "$tmp"
  rm -rf "$BIN_DIR/app"
  mkdir -p "$BIN_DIR/app"
  cp -r "$tmp/server/pb_hooks" "$tmp/server/pb_migrations" "$tmp/server/pb_public" "$BIN_DIR/app/"
  rm -rf "$tmp"
  SRC="$BIN_DIR/app"
  RUN_AS=$SERVICE
  DATA="${DATA:-$BIN_DIR/pb_data}"
  chown -R "$SERVICE:$SERVICE" "$BIN_DIR/app"
fi

# The database lives outside the deployed code, so deploys never touch it.
mkdir -p "$DATA"
chown "$RUN_AS" "$DATA"

# PocketBase itself.
if ! "$BIN_DIR/pocketbase" --version 2>/dev/null | grep -q "$PB_VERSION"; then
  echo "Downloading PocketBase $PB_VERSION…"
  tmp=$(mktemp -d)
  curl -fsSL -o "$tmp/pb.zip" "https://github.com/pocketbase/pocketbase/releases/download/v${PB_VERSION}/pocketbase_${PB_VERSION}_linux_amd64.zip"
  unzip -o -q "$tmp/pb.zip" pocketbase -d "$BIN_DIR"
  chmod 755 "$BIN_DIR/pocketbase"
  rm -rf "$tmp"
fi

PB_ARGS="--dir=$DATA --hooksDir=$SRC/pb_hooks --migrationsDir=$SRC/pb_migrations --publicDir=$SRC/pb_public"

cat > "/etc/systemd/system/$SERVICE.service" <<UNIT
[Unit]
Description=BlockbusterTV server (PocketBase)
After=network.target

[Service]
User=$RUN_AS
WorkingDirectory=$DATA
ExecStart=$BIN_DIR/pocketbase serve --http=127.0.0.1:$PORT $PB_ARGS
Restart=always
RestartSec=5
LimitNOFILE=4096

[Install]
WantedBy=multi-user.target
UNIT

# Plesk Git mode: restart the server whenever a deploy changes its API code or migrations.
# PocketBase's own reload on hook changes can catch a deploy halfway and leave the
# /api/bbtv routes missing (the phone app then shows no shelves), so a clean restart
# follows a few seconds after the files stop changing. Restarting also applies new migrations.
if [ -n "$APP_DIR" ]; then
  cat > "/etc/systemd/system/$SERVICE-redeploy.path" <<UNIT
[Unit]
Description=Restart BlockbusterTV when a deploy changes its code

[Path]
PathChanged=$SRC/pb_hooks
PathChanged=$SRC/pb_migrations
Unit=$SERVICE-redeploy.service

[Install]
WantedBy=multi-user.target
UNIT
  cat > "/etc/systemd/system/$SERVICE-redeploy.service" <<UNIT
[Unit]
Description=Restart BlockbusterTV after a deploy

[Service]
Type=oneshot
ExecStart=/bin/sh -c 'sleep 10; systemctl restart $SERVICE'
UNIT
else
  systemctl disable --now --quiet "$SERVICE-redeploy.path" 2>/dev/null || true
  rm -f "/etc/systemd/system/$SERVICE-redeploy.path" "/etc/systemd/system/$SERVICE-redeploy.service"
fi

# Apply any new database migrations, then (re)start.
sudo -u "$RUN_AS" "$BIN_DIR/pocketbase" migrate up $PB_ARGS
systemctl daemon-reload
if [ -n "$APP_DIR" ]; then
  systemctl enable --quiet "$SERVICE-redeploy.path"
  systemctl restart "$SERVICE-redeploy.path"
fi
systemctl enable --quiet "$SERVICE"
systemctl restart "$SERVICE"
sleep 2
if systemctl is-active --quiet "$SERVICE"; then
  echo "BlockbusterTV is running on 127.0.0.1:$PORT as $RUN_AS (data in $DATA)"
else
  echo "The service didn't start. See: journalctl -u $SERVICE -n 50"
  exit 1
fi

# First install: create the admin account for the dashboard (https://<your domain>/_/).
if [ ! -f "$DATA/.admin-created" ]; then
  email="${ADMIN_EMAIL:-}"
  password="${ADMIN_PASSWORD:-}"
  if [ -z "$email" ] && [ -t 0 ]; then
    read -rp "Admin email for the PocketBase dashboard: " email
    read -rsp "Admin password (10+ characters): " password; echo
  fi
  if [ -n "$email" ]; then
    sudo -u "$RUN_AS" "$BIN_DIR/pocketbase" superuser upsert "$email" "$password" --dir="$DATA"
    sudo -u "$RUN_AS" touch "$DATA/.admin-created"
    echo "Dashboard admin $email created."
  else
    echo "No dashboard admin yet: run again with ADMIN_EMAIL and ADMIN_PASSWORD set."
  fi
fi
echo "Done."
