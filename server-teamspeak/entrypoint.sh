#!/bin/bash
set -e

echo "=========================================================="
echo " Starting TeamSpeak 3 Server (Isolated / Anti Phone-Home)"
echo " NinjaRadio Ecosystem Integration"
echo "=========================================================="

cd /app
export LD_LIBRARY_PATH="/app:${LD_LIBRARY_PATH}"

# Ensure DNS loopback for anti-phone home
DOMAINS="accounting.teamspeak.com backupaccounting.teamspeak.com blacklist.teamspeak.com blacklist2.teamspeak.com weblist.teamspeak.com weblist2.teamspeak.com"
for d in $DOMAINS; do
    if ! grep -q "$d" /etc/hosts 2>/dev/null; then
        echo "127.0.0.1 $d" >> /etc/hosts 2>/dev/null || true
    fi
done

# Persistent SQLite Database link
mkdir -p /app/data
if [ ! -f /app/data/ts3server.sqlitedb ] && [ -f /app/ts3server.sqlitedb ]; then
    cp /app/ts3server.sqlitedb /app/data/ts3server.sqlitedb
fi
ln -sf /app/data/ts3server.sqlitedb /app/ts3server.sqlitedb

# Start Accounting Server Emulator in background
echo "[TS3-Emu] Starting AccountingServerEmulator-Linux on 127.0.0.1:2008..."
/app/AccountingServerEmulator-Linux > /app/logs/emulator.log 2>&1 &
EMU_PID=$!

sleep 1

# Start TeamSpeak 3 Server
echo "[TS3-Core] Starting ts3server binary..."
exec /app/ts3server inifile=/app/ts3server.ini default_voice_port=9987 voice_ip=0.0.0.0 create_default_vserver=1 serveradmin_password="${TS3_SERVERADMIN_PASSWORD:-Eao7tfYU}"
