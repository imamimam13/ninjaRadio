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
import urllib.request
import socket
import shutil

auth_key = os.getenv("SVX_AUTH_KEY", "ninjaradio_svx")
listen_port = os.getenv("SVX_PORT", "5300")
http_port = os.getenv("SVX_HTTP_PORT", "8080")
conf_path = "$CONF_PATH"

# Auto-detect server public IP dynamically (pindah server tidak akan macet)
detected_ip = ""
for url in ["https://api.ipify.org", "https://ifconfig.me/ip", "https://icanhazip.com"]:
    try:
        with urllib.request.urlopen(url, timeout=2.5) as resp:
            ip_str = resp.read().decode("utf-8").strip()
            if re.match(r"^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$", ip_str):
                detected_ip = ip_str
                break
    except Exception:
        continue

if not detected_ip:
    try:
        # Fallback to local outbound interface IP
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        detected_ip = s.getsockname()[0]
        s.close()
    except Exception:
        detected_ip = "127.0.0.1"

common_name = (os.getenv("SVX_COMMON_NAME") or "").strip() or detected_ip
print(f"✓ Detected Server Common Name / IP: {common_name}")

# Build SAN list dynamically
sans = set([
    f"IP:{detected_ip}",
    f"DNS:{detected_ip}.sslip.io",
    "DNS:svxreflector",
    "IP:127.0.0.1",
    "DNS:localhost"
])
if common_name and common_name != detected_ip:
    if re.match(r"^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$", common_name):
        sans.add(f"IP:{common_name}")
        sans.add(f"DNS:{common_name}.sslip.io")
    else:
        sans.add(f"DNS:{common_name}")

custom_san = os.getenv("SVX_CERT_SAN")
if custom_san:
    for s in custom_san.split(","):
        s_clean = s.strip()
        if s_clean:
            sans.add(s_clean)

alt_names = ",".join(sorted(list(sans)))

# Auto-refresh PKI certificate if server IP or Common Name changed
pki_cn_file = "/var/lib/svxlink/pki/.last_common_name"
try:
    if os.path.exists(pki_cn_file):
        with open(pki_cn_file, "r") as f:
            last_cn = f.read().strip()
        if last_cn and last_cn != common_name:
            print(f"⚠️ Server IP / Host berubah dari {last_cn} ke {common_name}. Mereset sertifikat PKI...")
            shutil.rmtree("/var/lib/svxlink/pki", ignore_errors=True)
    os.makedirs("/var/lib/svxlink/pki", exist_ok=True)
    with open(pki_cn_file, "w") as f:
        f.write(common_name)
except Exception as e:
    print(f"Note PKI check: {e}")

users = set([
    "N1NJA", "BD62BOT", "SVX", "SVXLINK", "SVX-GATEWAY", "SVX-GW-NINJA",
    "SVX-NASIONAL", "SVX-NUSANTARA", "SVX-BRIDI", "SVX-PARROT",
    "SVX-DARURAT", "SVX-WORLDWIDE", "ADMIN", "GUEST", "USER"
])

# Dynamically populate broad patterns for custom bot callsigns
for p1 in ["SVX", "NINJA", "GW", "BOT", "NODE", "RADIO"]:
    for p2 in ["GW", "NINJA", "BOT", "NODE", "LINK", "REFLECTOR", "GATEWAY", "RADIO"]:
        for p3 in ["", "-1", "-2", "-3", "-NINJA", "-NASIONAL", "-NUSANTARA", "-BRIDI", "-DARURAT", "-WORLDWIDE", "-LOCAL"]:
            users.add(f"{p1}-{p2}{p3}")
            users.add(f"{p1}{p3}")
            users.add(f"{p1}-{p2}")
            users.add(f"{p1}-GW-{p2}")

tg_v1 = 0

# 1. Read config.json
try:
    if os.path.exists("/app/data/config.json"):
        with open("/app/data/config.json", "r", encoding="utf-8") as f:
            cfg = json.load(f)
            found_tg = (
                cfg.get("default_tg")
                or cfg.get("default_talkgroup")
                or cfg.get("defaultTg")
                or cfg.get("tg")
                or cfg.get("target_tg")
            )
            if found_tg:
                try:
                    tg_v1 = int(found_tg)
                except (ValueError, TypeError):
                    pass
            if cfg.get("server_callsign"):
                users.add(str(cfg["server_callsign"]).strip().upper())
            if cfg.get("operator_callsign"):
                users.add(str(cfg["operator_callsign"]).strip().upper())
            for p in cfg.get("peering", []):
                if p.get("callsign"):
                    users.add(str(p["callsign"]).strip().upper())
            for b in cfg.get("svxlink_bots", []):
                if b.get("name"):
                    users.add(str(b["name"]).strip().upper())
except Exception as e:
    print(f"Note reading config.json: {e}")

if not tg_v1:
    try:
        tg_v1 = int(os.environ.get("DEFAULT_TALKGROUP", os.environ.get("DEFAULT_TG", 0)))
    except (ValueError, TypeError):
        tg_v1 = 0

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
TG_FOR_V1_CLIENTS={tg_v1}
ACCEPT_CALLSIGN=.*
SQL_TIMEOUT=300
SQL_TIMEOUT_BLOCKTIME=10
CODECS=OPUS,SPEEX,GSM

[SERVER_CERT]
COMMON_NAME={common_name}
SUBJECT_ALT_NAME={alt_names}

[USERS]
* = NinjaReflectorNodes
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
