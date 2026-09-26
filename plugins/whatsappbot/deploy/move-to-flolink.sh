#!/bin/bash
# Move FloChat from flochat.flolink.ai to the main domain flolink.ai (run on the server as root).
# Before running: in GoDaddy DNS point @ (flolink.ai) to this server (www is a CNAME to @).
# Keep flochat.flolink.ai here too. app.flolink.ai is FlowLinker (Zoho Catalyst) - not touched.
set -euo pipefail
EMAIL=${EMAIL:-info@msoftdynamic.com}
CONF=/etc/flochat/flochat.properties
for h in flolink.ai www.flolink.ai; do
  ip=$(getent hosts "$h" | awk '{print $1}' | head -1)
  echo "$h -> ${ip:-not resolving}"
done
read -r -p "Do all of these point to this server? [y/N] " ok; [ "$ok" = "y" ] || { echo "Fix DNS first."; exit 1; }

cp -n /etc/nginx/sites-available/flochat /etc/nginx/sites-available/flochat.bak-$(date +%Y%m%d) || true
mkdir -p /etc/nginx/snippets
DIR=$(cd "$(dirname "$0")" && pwd)
cp "$DIR/nginx-flochat-proxy.conf" /etc/nginx/snippets/flochat-proxy.conf
cp "$DIR/nginx-flolink.conf" /etc/nginx/sites-available/flochat
ln -sf /etc/nginx/sites-available/flochat /etc/nginx/sites-enabled/flochat
nginx -t && systemctl reload nginx
certbot --nginx --non-interactive --agree-tos -m "$EMAIL" --expand \
  -d flolink.ai -d www.flolink.ai -d flochat.flolink.ai --redirect

# app settings: new address everywhere
cp "$CONF" "$CONF.bak-$(date +%Y%m%d)"
set_prop() { grep -q "^$1=" "$CONF" && sed -i "s#^$1=.*#$1=$2#" "$CONF" || echo "$1=$2" >> "$CONF"; }
set_prop brand.domain flolink.ai
set_prop brand.app.url https://flolink.ai
set_prop zoho.redirect.uri https://flolink.ai/control/zohoCallback
# OFBiz only answers to known host names
OFBIZ=${OFBIZ:-/opt/flochat/ofbiz}
SECP=$OFBIZ/framework/security/config/security.properties
for h in flolink.ai www.flolink.ai; do
  grep -Eq "^host-headers-allowed=(.*,)?${h//./\\.}(,|$)" "$SECP" || sed -i "s/^host-headers-allowed=\(.*\)/host-headers-allowed=\1,$h/" "$SECP"
done
grep "^host-headers-allowed" "$SECP"
systemctl stop flochat
sudo -u flochat bash -c "cd $OFBIZ && ./gradlew --no-daemon classes"
systemctl start flochat
echo "Done. Open https://flolink.ai - then update Meta and Zoho as listed in the README (Moving to flolink.ai)."
