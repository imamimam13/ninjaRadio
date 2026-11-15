#!/bin/bash
set -e

echo "=================================================="
echo "  Starting NinjaRadio FreeDMR Engine              "
echo "  Port DMR Master / Hotspot : 62031/udp           "
echo "  Port OpenBridge Trunk     : 62030/udp           "
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

# Hook dynamic SQLite Self-Care authentication into hblink.py
python3 -c "
import os
hb_path = '/opt/freedmr/hblink.py'
if os.path.exists(hb_path):
    with open(hb_path, 'r', encoding='utf-8') as f:
        src = f.read()
    old_block = \"_calc_hash = bhex(sha256(_salt_str+self._config['PASSPHRASE']).hexdigest())\"
    if old_block in src and 'ninjaradio_auth' not in src:
        new_block = '''try:
                        from ninjaradio_auth import verify_peer_login
                        _is_valid = verify_peer_login(int(_this_peer['RADIO_ID']), _salt_str, _sent_hash, self._config['PASSPHRASE'])
                    except Exception as _ae:
                        _is_valid = (_sent_hash == bhex(sha256(_salt_str+self._config['PASSPHRASE']).hexdigest()))
                    _calc_hash = _sent_hash if _is_valid else b'INVALID_HASH_VALUE_FAIL''''
        src = src.replace(old_block, new_block, 1)
    if 'self.send_config()' in src:
        src = src.replace('self.send_config()', 'self._factory.send_config()')
        print('✓ Patched FreeDMR reporting socket handler in hblink.py')
    with open(hb_path, 'w', encoding='utf-8') as f:
            f.write(src)
        print('✓ Integrated dynamic Self-Care DMR password authentication into FreeDMR')
" 2>/dev/null || true

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
