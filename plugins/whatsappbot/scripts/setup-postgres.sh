#!/usr/bin/env bash
# Point Apache OFBiz 24.09 at PostgreSQL and create the database.
# Usage (from the OFBiz root folder):
#   plugins/whatsappbot/scripts/setup-postgres.sh [db_name] [db_user] [db_password] [db_host] [timezone]
set -euo pipefail
DB=${1:-ofbiz}; USER_=${2:-ofbiz}; PASS=${3:-ofbiz}; HOST=${4:-127.0.0.1}; TZNAME=${5:-Asia/Kolkata}
CONF=framework/entity/config/entityengine.xml
[ -f "$CONF" ] || { echo "Run this from the OFBiz root folder"; exit 1; }

if [ "${SKIP_DB_CREATE:-0}" != "1" ]; then
echo "==> Creating role '$USER_' and database '$DB' (needs a postgres superuser; you may be asked for its password)"
psql -h "$HOST" -U "${PGSUPERUSER:-postgres}" -v ON_ERROR_STOP=0 <<SQL
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '$USER_') THEN
    CREATE ROLE "$USER_" LOGIN PASSWORD '$PASS';
  END IF;
END \$\$;
SQL
psql -h "$HOST" -U "${PGSUPERUSER:-postgres}" -tc "SELECT 1 FROM pg_database WHERE datname='$DB'" | grep -q 1 \
  || psql -h "$HOST" -U "${PGSUPERUSER:-postgres}" -c "CREATE DATABASE \"$DB\" OWNER \"$USER_\" ENCODING 'UTF8' TEMPLATE template0"
fi

echo "==> Switching the 'default' delegator to PostgreSQL in $CONF (backup: $CONF.bak)"
cp -n "$CONF" "$CONF.bak" || true
python3 - "$CONF" "$DB" "$USER_" "$PASS" "$HOST" <<'PY'
import re, sys
conf, db, user, pw, host = sys.argv[1:]
s = open(conf, encoding="utf-8").read()
# 1) default + default-no-eca delegators -> localpostgres for all groups
def fix_delegator(m):
    block = m.group(0)
    return re.sub(r'datasource-name="localderby(olap|tenant)?"', 'datasource-name="localpostgres"', block)
s = re.sub(r'<delegator name="default(-no-eca)?".*?</delegator>', fix_delegator, s, flags=re.S)
# 2) credentials / url of the localpostgres datasource
def fix_ds(m):
    block = m.group(0)
    block = re.sub(r'jdbc-uri="[^"]*"', f'jdbc-uri="jdbc:postgresql://{host}/{db}"', block, count=1)
    block = re.sub(r'jdbc-username="[^"]*"', f'jdbc-username="{user}"', block, count=1)
    block = re.sub(r'jdbc-password="[^"]*"', f'jdbc-password="{pw}"', block, count=1)
    return block
s = re.sub(r'<datasource name="localpostgres"\s.*?</datasource>', fix_ds, s, flags=re.S)
open(conf, "w", encoding="utf-8").write(s)
print("entityengine.xml updated")
PY
echo "==> Pinning OFBiz JVM timezone to $TZNAME in gradle.properties"
# PostgreSQL rejects legacy zone names such as "Asia/Calcutta" (the macOS/Java default for India)
# with: FATAL invalid value for parameter "TimeZone". Pin a canonical IANA name instead.
if ! grep -q '^jvmArgs=' gradle.properties 2>/dev/null; then
  printf '\njvmArgs=-Xms128M -Xmx%s -Djdk.serialFilter=maxarray=100000;maxdepth=20;maxrefs=1000;maxbytes=500000 --add-opens=java.base/java.util=ALL-UNNAMED -Duser.timezone=%s\n' "${JVM_XMX:-1024M}" "$TZNAME" >> gradle.properties
fi
echo "==> Done. PostgreSQL JDBC driver is added by plugins/whatsappbot/build.gradle."
echo "    Next: ./gradlew cleanAll loadAll   (first time)   then   ./gradlew ofbiz"
