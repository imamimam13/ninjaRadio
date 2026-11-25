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
echo '{"talkgroups": [{"id": 1025, "callsign": "TG 1025"}, {"id": 510, "callsign": "TG 510"}, {"id": 5109, "callsign": "TG 5109"}, {"id": 5101, "callsign": "TG 5101"}, {"id": 5102, "callsign": "TG 5102"}, {"id": 5103, "callsign": "TG 5103"}, {"id": 9990, "callsign": "TG 9990"}, {"id": 91, "callsign": "TG 91"}, {"id": 51024, "callsign": "TG 51024"}, {"id": 510991, "callsign": "TG 510991"}]}' > "/opt/freedmr/json/talkgroup_ids.json"
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
python3 - <<'PYEOF' || true
import os
import re
hb_path = '/opt/freedmr/hblink.py'
if os.path.exists(hb_path):
    with open(hb_path, 'r', encoding='utf-8') as f:
        src = f.read()

    # Pattern matches _calc_hash line with any trailing whitespace
    pattern = r"_calc_hash\s*=\s*bhex\(sha256\(_salt_str\+self\._config\['PASSPHRASE'\]\)\.hexdigest\(\)\)"
    if 'ninjaradio_auth' not in src and re.search(pattern, src):
        new_block = (
            "try:\n"
            "                        from ninjaradio_auth import verify_peer_login\n"
            "                        _is_valid = verify_peer_login(int(_this_peer['RADIO_ID']), _salt_str, _sent_hash, self._config['PASSPHRASE'])\n"
            "                    except Exception as _ae:\n"
            "                        _is_valid = (_sent_hash == bhex(sha256(_salt_str+self._config['PASSPHRASE']).hexdigest()))\n"
            "                    _calc_hash = _sent_hash if _is_valid else b'INVALID_HASH_VALUE_FAIL'"
        )
        src = re.sub(pattern, new_block, src, count=1)
        print('✓ Integrated dynamic Self-Care DMR password authentication into FreeDMR')

    if 'self.send_config()' in src:
        src = src.replace('self.send_config()', 'self._factory.send_config()')
        print('✓ Patched FreeDMR reporting socket handler in hblink.py')

    with open(hb_path, 'w', encoding='utf-8') as f:
        f.write(src)
PYEOF

# Make PEER systems (CORE-BRIDGE -> NinjaRadio core:62035) an always-ACTIVE member of every
# dynamically created TG bridge, on the same slot the bridge was created on. Without this,
# FreeDMR creates the PEER entries as ACTIVE=False and hotspot traffic never reaches core/peers.
python3 - <<'PYEOF' || true
import os
bm = '/opt/freedmr/bridge_master.py'
if os.path.exists(bm):
    src = open(bm, 'r', encoding='utf-8').read()
    anchor = "            if _system == _sourcesystem:\n                    if _slot == 1:"
    if 'NINJA_PEER_ALWAYS_ACTIVE' not in src and anchor in src:
        inject = (
            "            if CONFIG['SYSTEMS'][_system]['MODE'] == 'PEER':  # NINJA_PEER_ALWAYS_ACTIVE\n"
            "                BRIDGES[_tgid_s].append({'SYSTEM': _system, 'TS': 1, 'TGID': _tgid,'ACTIVE': _slot == 1,'TIMEOUT': 0,'TO_TYPE': 'NONE','OFF': [],'ON': [_tgid,],'RESET': [], 'TIMER': time()})\n"
            "                BRIDGES[_tgid_s].append({'SYSTEM': _system, 'TS': 2, 'TGID': _tgid,'ACTIVE': _slot == 2,'TIMEOUT': 0,'TO_TYPE': 'NONE','OFF': [],'ON': [_tgid,],'RESET': [], 'TIMER': time()})\n"
            "                continue\n"
        )
        src = src.replace(anchor, inject + anchor, 1)
        open(bm, 'w', encoding='utf-8').write(src)
        print('✓ Patched FreeDMR: PEER systems always active in TG bridges')
    elif 'NINJA_PEER_ALWAYS_ACTIVE' in src:
        print('✓ FreeDMR PEER-always-active patch already present')
    else:
        print('⚠ FreeDMR PEER patch anchor not found!')
PYEOF

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
