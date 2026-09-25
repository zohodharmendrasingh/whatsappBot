#!/usr/bin/env bash
# FloChat one-time server install (Ubuntu 22.04 / 24.04, run as root).
# The GitHub repo holds the complete OFBiz 24.09 with the FloChat plugin in plugins/whatsappbot.
#
#   sudo GH_TOKEN=<github token> DOMAIN=flochat.flolink.ai EMAIL=info@msoftdynamic.com bash install.sh
#
# GH_TOKEN: a GitHub personal access token with read access to the private repo.
# Before running: point the domain's DNS A record at this server (needed for the SSL certificate).
# Optional env: REPO (default zohodharmendrasingh/whatsappBot), BRANCH (main), SKIP_SSL=1
set -euo pipefail

DOMAIN=${DOMAIN:?Set DOMAIN, e.g. DOMAIN=flochat.flolink.ai}
EMAIL=${EMAIL:?Set EMAIL for the SSL certificate}
REPO=${REPO:-zohodharmendrasingh/whatsappBot}
BRANCH=${BRANCH:-main}
BASE=/opt/flochat
OFBIZ=$BASE/ofbiz

say() { printf '\n\033[1;32m==> %s\033[0m\n' "$*"; }
[ "$(id -u)" = 0 ] || { echo "Run as root (sudo)"; exit 1; }

say "Installing packages (Java 17, PostgreSQL, nginx, certbot)"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y openjdk-17-jdk-headless git postgresql nginx certbot python3-certbot-nginx curl python3 ufw
JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")

say "Creating system user"
id flochat >/dev/null 2>&1 || useradd --system --create-home --home-dir $BASE --shell /usr/sbin/nologin flochat
mkdir -p $BASE && chown flochat:flochat $BASE

say "Cloning $REPO"
if [ ! -d $OFBIZ/.git ]; then
  : "${GH_TOKEN:?Set GH_TOKEN (GitHub token) to clone the private repo}"
  sudo -u flochat git clone -b "$BRANCH" "https://x-access-token:${GH_TOKEN}@github.com/${REPO}.git" $OFBIZ
  # keep the token out of .git/config; store it for later 'git pull' in a file only flochat can read
  sudo -u flochat git -C $OFBIZ remote set-url origin "https://github.com/${REPO}.git"
  sudo -u flochat git -C $OFBIZ config credential.helper store
  printf 'https://x-access-token:%s@github.com\n' "$GH_TOKEN" > $BASE/.git-credentials
  chown flochat:flochat $BASE/.git-credentials; chmod 600 $BASE/.git-credentials
fi
cd $OFBIZ
[ -f gradle/wrapper/gradle-wrapper.jar ] || sudo -u flochat ./gradle/init-gradle-wrapper.sh
chmod +x gradlew

say "PostgreSQL database (same name/user/password as in framework/entity/config/entityengine.xml)"
read -r DB_NAME DB_USER DB_PASS < <(python3 - <<'PY'
import re
s = open("framework/entity/config/entityengine.xml", encoding="utf-8").read()
b = re.search(r'<datasource name="localpostgres"\s.*?</datasource>', s, re.S).group(0)
uri = re.search(r'jdbc-uri="jdbc:postgresql://[^/]+/([^"?]+)', b).group(1)
user = re.search(r'jdbc-username="([^"]*)"', b).group(1)
pw = re.search(r'jdbc-password="([^"]*)"', b).group(1)
print(uri, user, pw)
PY
)
sudo -u postgres psql -tc "SELECT 1 FROM pg_roles WHERE rolname='$DB_USER'" | grep -q 1 \
  || sudo -u postgres psql -c "CREATE ROLE \"$DB_USER\" LOGIN PASSWORD '$DB_PASS'"
sudo -u postgres psql -tc "SELECT 1 FROM pg_database WHERE datname='$DB_NAME'" | grep -q 1 \
  || sudo -u postgres psql -c "CREATE DATABASE \"$DB_NAME\" OWNER \"$DB_USER\" ENCODING 'UTF8' TEMPLATE template0"

say "Allowing https://$DOMAIN in OFBiz"
SECP=framework/security/config/security.properties
grep -q "^host-headers-allowed=.*$DOMAIN" $SECP || sed -i "s/^host-headers-allowed=\(.*\)/host-headers-allowed=\1,$DOMAIN/" $SECP

if [ ! -f $BASE/.data-loaded ]; then
  say "Building and loading data (first run: 10-20 minutes)"
  sudo -u flochat bash -c "cd $OFBIZ && ./gradlew --no-daemon loadAll"
  touch $BASE/.data-loaded; chown flochat:flochat $BASE/.data-loaded
fi

say "Installing the flochat service"
sed "s#^Environment=JAVA_HOME=.*#Environment=JAVA_HOME=$JAVA_HOME#" $OFBIZ/plugins/whatsappbot/deploy/flochat.service > /etc/systemd/system/flochat.service
systemctl daemon-reload
systemctl enable --now flochat

say "nginx + SSL for $DOMAIN"
sed "s/__DOMAIN__/$DOMAIN/" $OFBIZ/plugins/whatsappbot/deploy/nginx-flochat.conf > /etc/nginx/sites-available/flochat
ln -sf /etc/nginx/sites-available/flochat /etc/nginx/sites-enabled/flochat
rm -f /etc/nginx/sites-enabled/default
nginx -t && systemctl reload nginx
[ "${SKIP_SSL:-0}" = "1" ] || certbot --nginx -d "$DOMAIN" -m "$EMAIL" --agree-tos -n --redirect

say "Firewall: only SSH, HTTP and HTTPS from outside"
ufw allow OpenSSH >/dev/null; ufw allow 'Nginx Full' >/dev/null; ufw --force enable >/dev/null

say "Waiting for FloChat to start"
for i in $(seq 1 90); do
  curl -sk -o /dev/null -w '%{http_code}' https://127.0.0.1:8443/control/home 2>/dev/null | grep -q 200 && break; sleep 10
done
echo
echo "================================================================"
echo " FloChat:   https://$DOMAIN"
echo " Admin:     admin / ofbiz  and  wademo / ofbiz  -> CHANGE BOTH PASSWORDS NOW"
echo " Logs:      journalctl -u flochat -f   |  $OFBIZ/runtime/logs/ofbiz.log"
echo " Update:    sudo bash $OFBIZ/plugins/whatsappbot/deploy/update.sh"
echo "================================================================"
