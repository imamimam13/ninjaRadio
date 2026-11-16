#!/bin/bash
set -e

echo "=========================================================="
echo " Starting TeamSpeak 3 Headless Audio Bridge Daemon"
echo " (Open-Source TS3AudioBot · Anti Phone-Home)"
echo "=========================================================="

python3 /app/sync_bots.py

chown -R 9999:9999 /app/data
chmod -R 777 /app/data

echo "[TS3-Bridge] Launching headless bot engine from /app/data..."
cd /app/data
exec su-exec ts3bot dotnet /app/TS3AudioBot.dll --non-interactive --stats-disabled
