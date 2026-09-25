#!/usr/bin/env bash
# Pull the latest FloChat (OFBiz + plugin) from GitHub, rebuild and restart.
#   sudo bash /opt/flochat/ofbiz/plugins/whatsappbot/deploy/update.sh
#   sudo LOAD_SEED=1 bash ...   # also reload seed data (plans, labels, theme)
set -euo pipefail
OFBIZ=/opt/flochat/ofbiz
[ "$(id -u)" = 0 ] || { echo "Run as root (sudo)"; exit 1; }

echo "==> Pulling latest code"
sudo -u flochat git -C $OFBIZ pull --rebase --autostash
sudo -u flochat git -C $OFBIZ log -1 --format='    now at %h %s (%cr)'

echo "==> Stopping FloChat"
systemctl stop flochat

echo "==> Building"
sudo -u flochat bash -c "cd $OFBIZ && ./gradlew --no-daemon classes"
if [ "${LOAD_SEED:-0}" = "1" ]; then
  echo "==> Reloading seed data"
  sudo -u flochat bash -c "cd $OFBIZ && ./gradlew --no-daemon 'ofbiz --load-data readers=seed'"
fi

echo "==> Starting FloChat"
systemctl start flochat
for i in $(seq 1 60); do
  curl -sk -o /dev/null -w '%{http_code}' https://127.0.0.1:8443/control/home 2>/dev/null | grep -q 200 && { echo "==> FloChat is up"; exit 0; }
  sleep 5
done
echo "FloChat did not answer yet - check: journalctl -u flochat -n 100"
