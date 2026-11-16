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
import urllib.request, json
try:
    data = json.loads(urllib.request.urlopen('http://127.0.0.1:58913/api/bot/list').read().decode())
    for b in data:
        bid = b.get('Id')
        bname = b.get('Name')
        if bid is not None:
            try:
                url = f'http://127.0.0.1:58913/api/bot/use/{bid}/(/plugin/load/NinjaRadioBridge.cs)'
                urllib.request.urlopen(url)
                print(f'[TS3-Bridge] ✓ Loaded NinjaRadioBridge on bot {bid} ({bname})')
            except Exception as ex:
                print(f'[TS3-Bridge] Note loading on bot {bid}: {ex}')
except Exception as e:
    print('[TS3-Bridge] Plugin autoload error:', e)
"
            break
        fi
        sleep 1
    done
) &

echo "[TS3-Bridge] Launching headless bot engine from /app/data..."
cd /app/data
exec su-exec ts3bot dotnet /app/TS3AudioBot.dll --non-interactive --stats-disabled
