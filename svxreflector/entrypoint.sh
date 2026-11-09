#!/bin/bash
set -e

mkdir -p /app/data
CONF_PATH="/etc/svxlink/svxreflector.conf"
USERS_FILE="/app/data/svx_users.txt"
LOG_FILE="/tmp/svxreflector.log"

AUTH_KEY="${SVX_AUTH_KEY:-ninjaradio_svx}"
LISTEN_PORT="${SVX_PORT:-5300}"
HTTP_PORT="${SVX_HTTP_PORT:-8080}"

touch "$USERS_FILE"
for default_call in N1NJA BD62BOT SVX-NASIONAL SVX-NUSANTARA SVX-BRIDI SVX-PARROT SVX-DARURAT SVX-WORLDWIDE; do
    grep -qxF "$default_call" "$USERS_FILE" 2>/dev/null || echo "$default_call" >> "$USERS_FILE"
done

generate_conf() {
    # Extract any custom bot names from /app/data/config.json if present
    if [ -f /app/data/config.json ]; then
        grep -o '"name"[[:space:]]*:[[:space:]]*"[^"]*"' /app/data/config.json | cut -d'"' -f4 | while read -r bname; do
            if [ -n "$bname" ]; then
                grep -qxF "$bname" "$USERS_FILE" 2>/dev/null || echo "$bname" >> "$USERS_FILE"
            fi
        done
    fi

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
EOF

    sort -u "$USERS_FILE" | while read -r ucall; do
        if [ -n "$ucall" ]; then
            echo "${ucall} = NinjaReflectorNodes" >> "$CONF_PATH"
        fi
    done

    cat <<EOF >> "$CONF_PATH"

[PASSWORDS]
NinjaReflectorNodes = "${AUTH_KEY}"
EOF
}

generate_conf

echo "=================================================="
echo "  Starting NinjaRadio SvxReflector Server"
echo "  Mode         : Open Callsign (Zero Amateur Check)"
echo "  TCP/UDP Port : ${LISTEN_PORT}"
echo "  Status HTTP  : ${HTTP_PORT}"
echo "=================================================="

: > "$LOG_FILE"

# Background watcher: auto-registers ANY new callsign (custom bot or external node)
(
    tail -n 0 -F "$LOG_FILE" 2>/dev/null | while read -r line; do
        if echo "$line" | grep -q 'Unknown user "'; then
            new_call=$(echo "$line" | sed -n 's/.*Unknown user "\([^"]*\)".*/\1/p')
            if [ -n "$new_call" ]; then
                if ! grep -qxF "$new_call" "$USERS_FILE" 2>/dev/null; then
                    echo "🔓 [Auto-Allow Callsign] Registering new node callsign '$new_call'..."
                    echo "$new_call" >> "$USERS_FILE"
                    generate_conf
                    pkill -f "/usr/bin/svxreflector" || true
                fi
            fi
        fi
    done
) &

# Keep svxreflector running and auto-restart within 0.5s when new callsigns are hot-added
while true; do
    generate_conf
    /usr/bin/svxreflector --config="$CONF_PATH" 2>&1 | tee -a "$LOG_FILE" || true
    sleep 0.5
done

