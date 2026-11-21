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

# Function to run background loader for plugins and channel mover once web API is up
run_post_startup_tasks() {
    (
        echo "[TS3-Bridge] Waiting for TS3AudioBot Web API on port 58913..."
        for i in $(seq 1 30); do
            if python3 -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:58913/api/bot/list', timeout=1)" 2>/dev/null; then
                echo "[TS3-Bridge] Web API is up! Waiting 2s for bots to settle..."
                sleep 2
                python3 -c "
import urllib.request, json, urllib.parse, os, time, re

def search_tree(node, target):
    if not node or not target: return None
    t = target.strip().lower()
    if isinstance(node, dict):
        name = str(node.get('Name') or node.get('name') or node.get('channel_name') or '').strip().lower()
        cid = node.get('Id') or node.get('id') or node.get('ChannelId') or node.get('cid') or node.get('channel_id')
        clean_name = re.sub(r'\[.*?\]', '', name).strip()
        if (name and name == t) or (clean_name and clean_name == t):
            if cid is not None: return cid
        if clean_name and (t in clean_name or clean_name in t):
            if cid is not None: return cid
        for k, v in node.items():
            res = search_tree(v, t)
            if res is not None: return res
    elif isinstance(node, list):
        for item in node:
            res = search_tree(item, t)
            if res is not None: return res
    return None

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
                    if k.lower() == 'channel': ch = v.strip()
                    elif k.lower() == 'channel_password': pw = v.strip()
            if ch:
                bot_targets[bfolder.lower()] = (ch, pw, bfolder)

for attempt in range(1, 4):
    try:
        data = json.loads(urllib.request.urlopen('http://127.0.0.1:58913/api/bot/list').read().decode())
        for b in data:
            bid = b.get('Id')
            bname = b.get('Name')
            if bid is None: continue

            # 1. Load NinjaRadioBridge plugin on first attempt
            if attempt == 1:
                try:
                    url = f'http://127.0.0.1:58913/api/bot/use/{bid}/(/plugin/load/NinjaRadioBridge.cs)'
                    urllib.request.urlopen(url, timeout=3)
                    print(f'[TS3-Bridge] ✓ Loaded NinjaRadioBridge on bot {bid} ({bname})')
                except Exception as ex:
                    print(f'[TS3-Bridge] Note loading on bot {bid}: {ex}')

            # 2. Automatically resolve channel ID and enforce move
            clean_bname = (bname or '').lower().replace(' ', '_')
            ch_info = bot_targets.get(str(bid).lower()) or bot_targets.get(clean_bname)
            if ch_info:
                ch, pw, bfolder = ch_info
                target_cid = None

                if ch.isdigit():
                    target_cid = ch
                elif ch.startswith('/') and ch[1:].isdigit():
                    target_cid = ch[1:]
                else:
                    # Query server tree to find the Channel ID automatically across all subchannels!
                    try:
                        tree_url = f'http://127.0.0.1:58913/api/bot/use/{bid}/(/server/tree)'
                        tree_res = urllib.request.urlopen(tree_url, timeout=3).read().decode()
                        tree_data = json.loads(tree_res)
                        resolved_id = search_tree(tree_data, ch)
                        if resolved_id is None:
                            try:
                                tree_res2 = urllib.request.urlopen('http://127.0.0.1:58913/api/server/tree', timeout=3).read().decode()
                                resolved_id = search_tree(json.loads(tree_res2), ch)
                            except Exception:
                                pass
                        if resolved_id is not None:
                            target_cid = str(resolved_id)
                            print(f'[TS3-Bridge] 🎯 Auto-resolved channel \'{ch}\' -> Channel ID: {target_cid} for bot {bid} ({bname})')
                    except Exception as err:
                        print(f'[TS3-Bridge] Note querying server/tree on bot {bid}: {err}')

                dest = target_cid if target_cid else ch
                dest_enc = urllib.parse.quote(str(dest).strip(), safe='')
                move_url = f'http://127.0.0.1:58913/api/bot/use/{bid}/(/bot/move/{dest_enc})'
                if pw:
                    pw_enc = urllib.parse.quote(str(pw).strip(), safe='')
                    move_url = f'http://127.0.0.1:58913/api/bot/use/{bid}/(/bot/move/{dest_enc}/{pw_enc})'
                try:
                    urllib.request.urlopen(move_url, timeout=3)
                    print(f'[TS3-Bridge] ✓ Moved bot {bid} ({bname}) -> \'{dest}\'')
                    # Persist resolved channel ID into bot.toml so future restarts/reconnects enter directly!
                    if target_cid and bfolder:
                        bot_toml_path = f'/app/data/bots/{bfolder}/bot.toml'
                        if os.path.exists(bot_toml_path):
                            raw_t = open(bot_toml_path, 'r', encoding='utf-8').read()
                            new_t = re.sub(r'channel\s*=\s*\"[^\"]*\"', f'channel = \"/{target_cid}\"', raw_t)
                            if new_t != raw_t:
                                open(bot_toml_path, 'w', encoding='utf-8').write(new_t)
                                print(f'[TS3-Bridge] 💾 Saved permanent channel = \"/{target_cid}\" in {bfolder}/bot.toml')
                except Exception as ex:
                    print(f'[TS3-Bridge] Note moving bot {bid} to \'{dest}\': {ex}')
    except Exception as e:
        print('[TS3-Bridge] Auto-channel loop error:', e)
    if attempt < 3:
        time.sleep(3)
"
                break
            fi
            sleep 1
        done
    ) &
}

# Function to calculate hash of TS3 bots and rooms config
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

# Graceful shutdown handler for Docker container stop/restart
BOT_PID=""
cleanup() {
    echo "[TS3-Bridge] Termination received. Gracefully shutting down TS3AudioBot..."
    if [ -n "$BOT_PID" ] && kill -0 "$BOT_PID" 2>/dev/null; then
        kill -TERM "$BOT_PID" 2>/dev/null || true
        wait "$BOT_PID" 2>/dev/null || true
    fi
    exit 0
}
trap cleanup SIGTERM SIGINT

echo "[TS3-Bridge] Starting supervisor loop for TS3AudioBot..."
cd /app/data

while true; do
    # 1. Sync bot configurations and identities
    python3 /app/sync_bots.py || true
    chown -R 9999:9999 /app/data 2>/dev/null || true

    # 2. Launch post-startup auto-mover and plugin loader in background
    run_post_startup_tasks

    # 3. Launch headless bot engine in background under supervisor
    echo "[TS3-Bridge] Launching headless bot engine (PID supervisor)..."
    su-exec ts3bot dotnet /app/TS3AudioBot.dll --non-interactive --stats-disabled &
    BOT_PID=$!

    LAST_HASH=$(get_cfg_hash)

    # 4. Monitor loop: check bot process health & detect config changes every 2s
    while kill -0 "$BOT_PID" 2>/dev/null; do
        sleep 2
        CUR_HASH=$(get_cfg_hash)
        if [ "$CUR_HASH" != "0" ] && [ "$CUR_HASH" != "$LAST_HASH" ]; then
            echo "[TS3-Bridge] 🔄 Detected TS3 bot/room config change! Auto-reloading TS3AudioBot in-place..."
            kill -TERM "$BOT_PID" 2>/dev/null || true
            for wait_cnt in $(seq 1 15); do
                if ! kill -0 "$BOT_PID" 2>/dev/null; then
                    break
                fi
                sleep 0.2
            done
            if kill -0 "$BOT_PID" 2>/dev/null; then
                kill -9 "$BOT_PID" 2>/dev/null || true
            fi
            break
        fi
    done

    wait "$BOT_PID" 2>/dev/null || true
    BOT_PID=""
    echo "[TS3-Bridge] TS3AudioBot exited. Reloading in 1s..."
    sleep 1
done
