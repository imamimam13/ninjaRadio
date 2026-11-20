#!/bin/bash
set -e

echo "=========================================================="
echo " Starting TeamSpeak 3 Headless Audio Bridge Daemon"
echo " (Open-Source TS3AudioBot · Anti Phone-Home)"
echo "=========================================================="

python3 /app/sync_bots.py

mkdir -p /app/data/plugins
if [ -d "/app/plugins" ]; then
    cp -f /app/plugins/*.cs /app/data/plugins/ 2>/dev/null || true
fi

chown -R 9999:9999 /app/data
chmod -R 777 /app/data

# Background loader for plugins once web API is ready
(
    echo "[TS3-Bridge] Waiting for TS3AudioBot Web API on port 58913..."
    for i in $(seq 1 30); do
        if python3 -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:58913/api/bot/list', timeout=1)" 2>/dev/null; then
            echo "[TS3-Bridge] Web API is up! Waiting 2s for bots to settle..."
            sleep 2
            python3 -c "
import urllib.request, json, urllib.parse, os, time

bot_targets = {}
bots_dir = '/app/data/bots'
if os.path.exists(bots_dir):
    for bfolder in os.listdir(bots_dir):
        cfg_p = os.path.join(bots_dir, bfolder, 'target.cfg')
        if os.path.exists(cfg_p):
            ch = ''
            pw = ''
            for line in open(cfg_p, encoding='utf-8', errors='ignore'):
                if '=' in line:
                    k, v = line.strip().split('=', 1)
                    if k.lower() == 'channel': ch = v
                    elif k.lower() == 'channel_password': pw = v
            if ch:
                bot_targets[bfolder.lower()] = (ch, pw)

try:
    data = json.loads(urllib.request.urlopen('http://127.0.0.1:58913/api/bot/list').read().decode())
    for b in data:
        bid = b.get('Id')
        bname = b.get('Name')
        if bid is not None:
            # 1. Load NinjaRadioBridge plugin
            try:
                url = f'http://127.0.0.1:58913/api/bot/use/{bid}/(/plugin/load/NinjaRadioBridge.cs)'
                urllib.request.urlopen(url)
                print(f'[TS3-Bridge] ✓ Loaded NinjaRadioBridge on bot {bid} ({bname})')
            except Exception as ex:
                print(f'[TS3-Bridge] Note loading on bot {bid}: {ex}')

            # 2. Enforce moving bot into target channel
            clean_bname = (bname or '').lower().replace(' ', '_')
            ch_info = bot_targets.get(str(bid).lower()) or bot_targets.get(clean_bname)
            if ch_info:
                ch, pw = ch_info
                ch_param = ch if (ch.startswith('/') or not ch.isdigit()) else f'/{ch}'
                ch_enc = urllib.parse.quote(ch_param, safe='/')
                move_url = f'http://127.0.0.1:58913/api/bot/use/{bid}/(/bot/move/{ch_enc})'
                if pw:
                    pw_enc = urllib.parse.quote(pw, safe='')
                    move_url = f'http://127.0.0.1:58913/api/bot/use/{bid}/(/bot/move/{ch_enc}/{pw_enc})'
                try:
                    urllib.request.urlopen(move_url, timeout=3)
                    print(f'[TS3-Bridge] ✓ Sent move command to channel \'{ch_param}\' for bot {bid} ({bname})')
                except Exception as ex:
                    print(f'[TS3-Bridge] Note moving bot {bid} to \'{ch}\': {ex}')
except Exception as e:
    print('[TS3-Bridge] Plugin autoload error:', e)
"
            break
        fi
        sleep 1
    done
) &

# Background watcher for config.json changes (auto-reloads bots when edited in Web UI)
(
    get_cfg_hash() {
        python3 -c "
import json, hashlib
try:
    with open('/app/ninja_data/config.json', 'r', encoding='utf-8') as f:
        cfg = json.load(f)
    raw = json.dumps(cfg.get('teamspeak_bots', []), sort_keys=True) + '|' + json.dumps(cfg.get('teamspeak_rooms', []), sort_keys=True)
    print(hashlib.md5(raw.encode()).hexdigest())
except Exception:
    print('0')
" 2>/dev/null
    }
    LAST_HASH=$(get_cfg_hash)
    while sleep 3; do
        CUR_HASH=$(get_cfg_hash)
        if [ "$CUR_HASH" != "0" ] && [ "$CUR_HASH" != "$LAST_HASH" ]; then
            echo "[TS3-Bridge] 🔄 Detected TS3 bot/room config change! Auto-reloading TS3AudioBot..."
            kill -TERM 1 2>/dev/null || pkill -f TS3AudioBot.dll
            break
        fi
    done
) &

echo "[TS3-Bridge] Launching headless bot engine from /app/data..."
cd /app/data
exec su-exec ts3bot dotnet /app/TS3AudioBot.dll --non-interactive --stats-disabled
