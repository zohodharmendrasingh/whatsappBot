#!/bin/bash
# flolink.ai = landing page for all products; FloChat stays on flochat.flolink.ai (run on the server as root).
# DNS (GoDaddy): @ A 103.48.51.17, www CNAME @, flochat A 103.48.51.17, app CNAME Catalyst (FlowLinker).
set -euo pipefail
DIR=$(cd "$(dirname "$0")" && pwd)
OFBIZ=${OFBIZ:-$(cd "$DIR/../../.." && pwd)}
CONF=/etc/flochat/flochat.properties

# 1. landing page
mkdir -p /var/www/flolink
cp -r "$DIR/flolink-site/." /var/www/flolink/
chmod -R a+rX /var/www/flolink

# 2. nginx (certificate for flolink.ai, www and flochat already exists)
[ -f /etc/letsencrypt/live/flochat.flolink.ai/fullchain.pem ] || { echo "No certificate yet: certbot certonly --nginx -d flochat.flolink.ai -d flolink.ai -d www.flolink.ai"; exit 1; }
PREV=/etc/nginx/flochat.prev-$(date +%Y%m%d%H%M)
cp /etc/nginx/sites-available/flochat "$PREV" 2>/dev/null || true
mkdir -p /etc/nginx/snippets
cp "$DIR/nginx-flochat-proxy.conf" /etc/nginx/snippets/flochat-proxy.conf
cp "$DIR/nginx-flolink.conf" /etc/nginx/sites-available/flochat
ln -sf /etc/nginx/sites-available/flochat /etc/nginx/sites-enabled/flochat
if ! nginx -t; then echo "nginx config error - restoring the previous one"; cp "$PREV" /etc/nginx/sites-available/flochat; nginx -t && systemctl reload nginx; exit 1; fi
systemctl reload nginx

# 3. FloChat keeps its own address
mkdir -p "$(dirname "$CONF")"; touch "$CONF"
set_prop() { grep -q "^$1=" "$CONF" && sed -i "s#^$1=.*#$1=$2#" "$CONF" || echo "$1=$2" >> "$CONF"; }
set_prop brand.domain flochat.flolink.ai
set_prop brand.app.url https://flochat.flolink.ai
set_prop zoho.redirect.uri https://flochat.flolink.ai/control/zohoCallback
chmod 644 "$CONF"
systemctl restart flochat
echo "Done. https://flolink.ai = landing page, https://flochat.flolink.ai = FloChat (ready in about a minute)."
