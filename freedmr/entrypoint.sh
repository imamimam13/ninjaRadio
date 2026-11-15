#!/bin/bash
set -e

echo "=================================================="
echo "  Starting NinjaRadio FreeDMR Engine              "
echo "  Port Hotspot MMDVM  : 62031/udp                 "
echo "  Port Core Bridge    : 62032/udp                 "
echo "  Port OpenBridge     : 62030/udp                 "
echo "=================================================="

mkdir -p /opt/freedmr/json
for f in peer_ids.json subscriber_ids.json talkgroup_ids.json; do
    if [ ! -f "/opt/freedmr/json/$f" ]; then
        echo "{}" > "/opt/freedmr/json/$f"
    fi
done

# Synchronize subscriber database from NinjaRadio SQLite DB if available
python3 -c "
import sqlite3, json, os

db_paths = ['/app/data/ninjaradio.db', '/data/ninjaradio.db']
sub_map = {}
for p in db_paths:
    if os.path.exists(p):
        try:
            with sqlite3.connect(p) as conn:
                cur = conn.cursor()
                rows = cur.execute('SELECT dmr_id, callsign, name FROM callbook WHERE dmr_id > 0').fetchall()
                for d_id, call, name in rows:
                    sub_map[str(d_id)] = {'CALLSIGN': str(call or ''), 'NAME': str(name or '')}
            break
        except Exception as e:
            pass

if sub_map:
    with open('/opt/freedmr/json/subscriber_ids.json', 'w') as sf:
        json.dump(sub_map, sf)
    print(f'✓ Exported {len(sub_map)} subscribers to FreeDMR json')
" 2>/dev/null || true

# Hot-patch hblink.py to check per-account Self-Care passwords from ninjaradio.db
python3 -c "
import re, os
path = '/opt/freedmr/hblink.py'
if os.path.exists(path):
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # Check if not already patched
    if 'ninjaradio.db' not in content:
        auth_hook = '''
        # --- NinjaRadio Self-Care SQLite Auth Hook ---
        _nr_auth_ok = False
        try:
            import sqlite3, hashlib
            for _db_cand in ['/app/data/ninjaradio.db', '/data/ninjaradio.db']:
                if os.path.exists(_db_cand):
                    with sqlite3.connect(_db_cand, timeout=1.0) as _conn:
                        _cur = _conn.cursor()
                        _num_id = int.from_bytes(_peer_id, 'big')
                        _cand_ids = [_num_id, _num_id // 100 if _num_id > 9999999 else _num_id]
                        _ph = ','.join('?' for _ in _cand_ids)
                        _row = _cur.execute(f'SELECT dmr_password FROM user_accounts WHERE status = \"APPROVED\" AND allow_dmr = 1 AND dmr_id IN ({_ph})', tuple(_cand_ids)).fetchone()
                        if _row and _row[0]:
                            _sc_pwd = str(_row[0]).strip()
                            for _p_var in [_sc_pwd, _sc_pwd.upper(), _sc_pwd.lower()]:
                                if _sent_hash == hashlib.sha256(_salt_str + _p_var.encode('utf-8')).digest():
                                    _nr_auth_ok = True
                                    break
                    break
        except Exception:
            pass
        if _nr_auth_ok:
            _auth_ok = True
        # --- End NinjaRadio Hook ---
'''
        # Inject hook right after _salt_str is defined in RPTK handling
        if '_salt_str = bytes_4(_this_peer[\'SALT\'])' in content:
            content = content.replace(
                '_salt_str = bytes_4(_this_peer[\'SALT\'])',
                '_salt_str = bytes_4(_this_peer[\'SALT\'])' + auth_hook
            )
            with open(path, 'w', encoding='utf-8') as f:
                f.write(content)
            print('✓ Successfully patched FreeDMR hblink.py with Self-Care SQLite auth hook')
" 2>/dev/null || true

cd /opt/freedmr

# Ensure config aliases exist for whatever FreeDMR looks for
cp /opt/freedmr/freedmr.cfg /opt/freedmr/FreeDMR.cfg 2>/dev/null || true
cp /opt/freedmr/rules.py /opt/freedmr/Rules.py 2>/dev/null || true

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

# Run FreeDMR with automatic live-reload watcher
echo "✓ FreeDMR Engine using script: $SCRIPT"
echo "✓ FreeDMR Engine ready. Watching for rules/config updates..."

while true; do
    echo "[FreeDMR] Starting DMR Master routing daemon (python3 -u $SCRIPT)..."
    python3 -u "$SCRIPT" -c /opt/freedmr/freedmr.cfg -r /opt/freedmr/rules.py &
    FREEDMR_PID=$!
    
    # Background watcher for reload trigger
    (
        LAST_TOUCH=""
        while kill -0 $FREEDMR_PID 2>/dev/null; do
            if [ -f "/app/data/.freedmr_reload" ]; then
                TOUCH_TIME=$(stat -c %Y /app/data/.freedmr_reload 2>/dev/null || stat -f %m /app/data/.freedmr_reload 2>/dev/null)
                if [ "$TOUCH_TIME" != "$LAST_TOUCH" ] && [ -n "$LAST_TOUCH" ]; then
                    echo "[FreeDMR] Reload triggered via .freedmr_reload! Restarting FreeDMR..."
                    kill -TERM $FREEDMR_PID 2>/dev/null || true
                    break
                fi
                LAST_TOUCH=$TOUCH_TIME
            fi
            sleep 2
        done
    ) &
    WATCHER_PID=$!

    wait $FREEDMR_PID || true
    kill -9 $WATCHER_PID 2>/dev/null || true
    echo "[FreeDMR] Process terminated, restarting in 1 second..."
    sleep 1
done
