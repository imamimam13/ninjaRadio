#!/bin/bash
set -e

mkdir -p /app/data
CONF_PATH="/etc/svxlink/svxreflector.conf"

AUTH_KEY="${SVX_AUTH_KEY:-ninjaradio_svx}"
LISTEN_PORT="${SVX_PORT:-5300}"
HTTP_PORT="${SVX_HTTP_PORT:-8080}"

# Dynamically generate svxreflector.conf with all callsigns from Callbook DB & config.json
python3 - <<PYEOF
import os
import json
import sqlite3
import re

auth_key = os.getenv("SVX_AUTH_KEY", "ninjaradio_svx")
listen_port = os.getenv("SVX_PORT", "5300")
http_port = os.getenv("SVX_HTTP_PORT", "8080")
conf_path = "$CONF_PATH"

users = set([
    "N1NJA", "BD62BOT", "SVX", "SVXLINK",
    "SVX-NASIONAL", "SVX-NUSANTARA", "SVX-BRIDI", "SVX-PARROT",
    "SVX-DARURAT", "SVX-WORLDWIDE", "ADMIN", "GUEST", "USER"
])

# 1. Read config.json
try:
    if os.path.exists("/app/data/config.json"):
        with open("/app/data/config.json", "r", encoding="utf-8") as f:
            cfg = json.load(f)
            if cfg.get("server_callsign"):
                users.add(str(cfg["server_callsign"]).strip().upper())
            if cfg.get("operator_callsign"):
                users.add(str(cfg["operator_callsign"]).strip().upper())
            for p in cfg.get("peering", []):
                if p.get("callsign"):
                    users.add(str(p["callsign"]).strip().upper())
except Exception as e:
    print(f"Note reading config.json: {e}")

# 2. Read Callbook SQLite DB (load every registered amateur / operator callsign)
db_paths = ["/app/data/ninjaradio.db", "/app/ninjaradio.db", "ninjaradio.db"]
for db_p in db_paths:
    if os.path.exists(db_p):
        try:
            conn = sqlite3.connect(db_p)
            cur = conn.cursor()
            rows = cur.execute("SELECT callsign FROM callbook").fetchall()
            for r in rows:
                c = str(r[0] or "").strip().upper()
                clean = re.sub(r"[^A-Za-z0-9\-]", "", c)
                if clean:
                    users.add(clean)
            conn.close()
            print(f"Loaded {len(rows)} callsigns from {db_p}")
            break
        except Exception as e:
            print(f"Note reading {db_p}: {e}")

# 3. Expand mobile/repeater node suffixes (-M, -L, -R, -P, -ND, -1..-3)
expanded_users = set()
for u in users:
    expanded_users.add(u)
    # Also strip standard 1-2 char trailing suffixes if present
    base = re.sub(r"-[A-Z0-9]{1,2}$", "", u).strip().upper()
    if base:
        expanded_users.add(base)
        expanded_users.add(f"{base}-M")
        expanded_users.add(f"{base}-L")
        expanded_users.add(f"{base}-R")
        expanded_users.add(f"{base}-P")
        expanded_users.add(f"{base}-ND")
        expanded_users.add(f"{base}-1")
        expanded_users.add(f"{base}-2")
        expanded_users.add(f"{base}-3")

sorted_users = sorted(list(expanded_users))

conf_content = f"""[GLOBAL]
TIMESTAMP_FORMAT="%Y-%m-%d %H:%M:%S"
LISTEN_PORT={listen_port}
HTTP_SRV_PORT={http_port}
TG_FOR_V1_CLIENTS=510
SQL_TIMEOUT=300
SQL_TIMEOUT_BLOCKTIME=10
CODECS=OPUS

[USERS]
"""

for u in sorted_users:
    conf_content += f"{u} = NinjaReflectorNodes\n"

conf_content += f"""
[PASSWORDS]
NinjaReflectorNodes = "{auth_key}"
"""

with open(conf_path, "w", encoding="utf-8") as f:
    f.write(conf_content)

print(f"✓ Generated svxreflector.conf with {len(sorted_users)} authorized users.")
PYEOF

echo "=================================================="
echo "  Starting NinjaRadio SvxReflector Server"
echo "  TCP/UDP Port : ${LISTEN_PORT}"
echo "  Status HTTP  : ${HTTP_PORT}"
echo "=================================================="

exec /usr/bin/svxreflector --config="$CONF_PATH"
