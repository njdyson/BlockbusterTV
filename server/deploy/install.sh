#!/usr/bin/env bash
# Installs or updates the BlockbusterTV server (PocketBase + this repo's server/ folder)
# on an Ubuntu server, as a systemd service listening on 127.0.0.1 only. Plesk's nginx
# then serves it publicly over HTTPS (see server/README.md).
#
# Run as root:   sudo bash install.sh
# Re-run it any time to update to the latest code from GitHub; data in pb_data is kept.
set -euo pipefail

PB_VERSION="${PB_VERSION:-0.40.6}"
REPO="${REPO:-https://github.com/njdyson/BlockbusterTV.git}"
BRANCH="${BRANCH:-main}"
DIR="${DIR:-/opt/blockbustertv}"
PORT="${PORT:-8095}"
SERVICE=blockbustertv

[ "$(id -u)" = 0 ] || { echo "Please run as root (sudo bash install.sh)"; exit 1; }
for tool in git unzip curl; do
  command -v "$tool" >/dev/null || apt-get install -y "$tool"
done

id "$SERVICE" >/dev/null 2>&1 || useradd --system --home "$DIR" --shell /usr/sbin/nologin "$SERVICE"
mkdir -p "$DIR"

# PocketBase itself.
if ! "$DIR/pocketbase" --version 2>/dev/null | grep -q "$PB_VERSION"; then
  echo "Downloading PocketBase $PB_VERSION…"
  tmp=$(mktemp -d)
  curl -fsSL -o "$tmp/pb.zip" "https://github.com/pocketbase/pocketbase/releases/download/v${PB_VERSION}/pocketbase_${PB_VERSION}_linux_amd64.zip"
  unzip -o -q "$tmp/pb.zip" pocketbase -d "$DIR"
  rm -rf "$tmp"
fi

# The app: API hooks, database migrations and the phone app.
echo "Fetching BlockbusterTV ($BRANCH)…"
src=$(mktemp -d)
git clone --quiet --depth 1 --branch "$BRANCH" "$REPO" "$src"
rm -rf "$DIR/pb_hooks" "$DIR/pb_migrations" "$DIR/pb_public"
cp -r "$src/server/pb_hooks" "$src/server/pb_migrations" "$src/server/pb_public" "$DIR/"
rm -rf "$src"
chown -R "$SERVICE:$SERVICE" "$DIR"

cat > "/etc/systemd/system/$SERVICE.service" <<UNIT
[Unit]
Description=BlockbusterTV server (PocketBase)
After=network.target

[Service]
User=$SERVICE
Group=$SERVICE
WorkingDirectory=$DIR
ExecStart=$DIR/pocketbase serve --http=127.0.0.1:$PORT --dir=$DIR/pb_data
Restart=always
RestartSec=5
LimitNOFILE=4096

[Install]
WantedBy=multi-user.target
UNIT

# Apply database migrations before (re)starting.
sudo -u "$SERVICE" "$DIR/pocketbase" migrate up --dir="$DIR/pb_data"
systemctl daemon-reload
systemctl enable --quiet "$SERVICE"
systemctl restart "$SERVICE"
sleep 2
systemctl is-active --quiet "$SERVICE" && echo "BlockbusterTV is running on 127.0.0.1:$PORT"

# First install: create the admin account for the dashboard (https://<your domain>/_/).
if [ ! -f "$DIR/.admin-created" ]; then
  read -rp "Admin email for the PocketBase dashboard: " email
  read -rsp "Admin password (10+ characters): " password; echo
  sudo -u "$SERVICE" "$DIR/pocketbase" superuser upsert "$email" "$password" --dir="$DIR/pb_data"
  touch "$DIR/.admin-created"
fi
echo "Done."
