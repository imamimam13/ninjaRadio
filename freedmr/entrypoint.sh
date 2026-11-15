#!/bin/bash
set -e

echo "=================================================="
echo "  Starting NinjaRadio FreeDMR Engine              "
echo "  Port Hotspot MMDVM  : 62031/udp                 "
echo "  Port Core Bridge    : 62032/udp                 "
echo "  Port OpenBridge     : 62030/udp                 "
echo "=================================================="

mkdir -p /opt/freedmr/json

# Initialize alias files in the exact format expected by dmr_utils3
echo '{"rptrs": []}' > "/opt/freedmr/json/peer_ids.json"
echo '{"talkgroups": [{"id": 1025, "callsign": "TG 1025"}, {"id": 510, "callsign": "TG 510"}, {"id": 5109, "callsign": "TG 5109"}, {"id": 5101, "callsign": "TG 5101"}, {"id": 5102, "callsign": "TG 5102"}, {"id": 5103, "callsign": "TG 5103"}, {"id": 9990, "callsign": "TG 9990"}, {"id": 91, "callsign": "TG 91"}]}' > "/opt/freedmr/json/talkgroup_ids.json"
echo '{"users": []}' > "/opt/freedmr/json/local_subscriber_ids.json"
echo '{"users": []}' > "/opt/freedmr/json/subscriber_ids.json"

# Synchronize subscriber database from NinjaRadio SQLite DB if available
python3 -c "
import sqlite3, json, os

db_paths = ['/app/data/ninjaradio.db', '/data/ninjaradio.db']
users_list = []
for p in db_paths:
    if os.path.exists(p):
        try:
            with sqlite3.connect(p) as conn:
                cur = conn.cursor()
                rows = cur.execute('SELECT dmr_id, callsign, name FROM callbook WHERE dmr_id > 0').fetchall()
                for d_id, call, name in rows:
                    users_list.append({
                        'id': int(d_id),
                        'callsign': str(call or ''),
                        'fname': str(name or call or ''),
                        'surname': '',
                        'city': '',
                        'state': '',
                        'country': ''
                    })
            break
        except Exception:
            pass

if users_list:
    with open('/opt/freedmr/json/subscriber_ids.json', 'w', encoding='utf-8') as sf:
        json.dump({'users': users_list}, sf)
    print(f'✓ Exported {len(users_list)} subscribers to FreeDMR json')
" 2>/dev/null || true

cd /opt/freedmr

# Ensure config aliases exist for whatever FreeDMR looks for
cp /opt/freedmr/freedmr.cfg /opt/freedmr/FreeDMR.cfg 2>/dev/null || true
cp /opt/freedmr/rules.py /opt/freedmr/Rules.py 2>/dev/null || true

# Neutralize i8n_voice_map to prevent KeyError on missing audio phonetic mappings
echo "voiceMap = {}" > /opt/freedmr/i8n_voice_map.py

# Determine FreeDMR master script
SCRIPT="bridge_master.py"
if [ ! -f "/opt/freedmr/$SCRIPT" ]; then
    if [ -f "/opt/freedmr/bridge.py" ]; then
        SCRIPT="bridge.py"
    elif [ -f "/opt/freedmr/FreeDMR.py" ]; then
        SCRIPT="FreeDMR.py"
    else
        SCRIPT=$(ls -1 /opt/freedmr/*master*.py /opt/freedmr/bridge*.py 2>/dev/null | head -n 1 | xargs -n 1 basename)
    fi
fi

echo "✓ FreeDMR Engine using script: $SCRIPT"
echo "✓ FreeDMR Engine ready. Starting DMR Master daemon..."

exec python3 -u "$SCRIPT" -c /opt/freedmr/freedmr.cfg -r /opt/freedmr/rules.py
