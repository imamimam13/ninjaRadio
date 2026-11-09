#!/bin/bash
set -e

mkdir -p /app/data
CONF_PATH="/etc/svxlink/svxreflector.conf"

AUTH_KEY="${SVX_AUTH_KEY:-ninjaradio_svx}"
LISTEN_PORT="${SVX_PORT:-5300}"
HTTP_PORT="${SVX_HTTP_PORT:-8080}"

cat <<EOF > "$CONF_PATH"
[GLOBAL]
TIMESTAMP_FORMAT="%Y-%m-%d %H:%M:%S"
LISTEN_PORT=${LISTEN_PORT}
HTTP_SRV_PORT=${HTTP_PORT}
SQL_TIMEOUT=300
SQL_TIMEOUT_BLOCKTIME=10
CODECS=OPUS

[USERS]
* = NinjaReflectorNodes
N1NJA = NinjaReflectorNodes
BD62BOT = NinjaReflectorNodes
SVX-NASIONAL = NinjaReflectorNodes
SVX-NUSANTARA = NinjaReflectorNodes
SVX-BRIDI = NinjaReflectorNodes
SVX-PARROT = NinjaReflectorNodes
SVX-DARURAT = NinjaReflectorNodes
SVX-WORLDWIDE = NinjaReflectorNodes

[PASSWORDS]
NinjaReflectorNodes = "${AUTH_KEY}"
EOF

echo "=================================================="
echo "  Starting NinjaRadio SvxReflector Server"
echo "  TCP/UDP Port : ${LISTEN_PORT}"
echo "  Status HTTP  : ${HTTP_PORT}"
echo "=================================================="

exec /usr/bin/svxreflector --config="$CONF_PATH"

