import asyncio
import logging
import os
import re
import time
from typing import Dict, List, Any, Optional

logger = logging.getLogger("teamspeak-service")

TS3_HOST = os.getenv("TS3_HOST", "teamspeak-server")
TS3_QUERY_PORT = int(os.getenv("TS3_QUERY_PORT", "10011"))
TS3_VOICE_PORT = int(os.getenv("TS3_VOICE_PORT", "9987"))
TS3_USER = os.getenv("TS3_QUERY_USER", "serveradmin")
TS3_PASS = os.getenv("TS3_QUERY_PASS", "Eao7tfYU")

_ts3_cache = {
    "last_check": 0.0,
    "data": {
        "online": False,
        "host": TS3_HOST,
        "voice_port": TS3_VOICE_PORT,
        "query_port": TS3_QUERY_PORT,
        "channels": [],
        "clients": [],
        "total_clients": 0,
        "max_clients": 512,
        "server_name": "NinjaRadio TeamSpeak 3",
        "uptime_sec": 0
    }
}


def ts_unescape(s: str) -> str:
    """Unescapes TeamSpeak 3 ServerQuery string escape sequences."""
    return (
        s.replace("\\\\", "\\")
        .replace("\\/", "/")
        .replace("\\s", " ")
        .replace("\\p", "|")
        .replace("\\a", "\a")
        .replace("\\b", "\b")
        .replace("\\f", "\f")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
        .replace("\\v", "\v")
    )


def ts_escape(s: str) -> str:
    """Escapes string for TeamSpeak 3 ServerQuery commands."""
    return (
        s.replace("\\", "\\\\")
        .replace("/", "\\/")
        .replace(" ", "\\s")
        .replace("|", "\\p")
    )


def parse_ts3_response(raw_text: str) -> List[Dict[str, str]]:
    """Parses pipe-separated key=value entries from TS3 ServerQuery output."""
    items = []
    lines = [line.strip() for line in raw_text.splitlines() if line.strip()]
    for line in lines:
        if line.startswith("error id=") or "TS3" in line or "Welcome" in line:
            continue
        entries = line.split("|")
        for entry in entries:
            obj = {}
            for pair in entry.split(" "):
                if "=" in pair:
                    k, v = pair.split("=", 1)
                    obj[k] = ts_unescape(v)
                elif pair:
                    obj[pair] = ""
            if obj:
                items.append(obj)
    return items


async def send_ts3_command(cmd: str, timeout: float = 2.0) -> Optional[str]:
    """Connects to TS3 ServerQuery, logs in, selects virtualserver 1, and sends command."""
    try:
        reader, writer = await asyncio.wait_for(
            asyncio.open_connection(TS3_HOST, TS3_QUERY_PORT),
            timeout=timeout
        )
        # Read banner
        await reader.readuntil(b"\n")
        await reader.readuntil(b"\n")

        # Login
        writer.write(f"login {TS3_USER} {TS3_PASS}\n".encode("ascii"))
        await writer.drain()
        await reader.readuntil(b"\n")

        # Select virtual server 1
        writer.write(b"use 1\n")
        await writer.drain()
        await reader.readuntil(b"\n")

        # Send actual command
        writer.write(f"{cmd.strip()}\n".encode("ascii"))
        await writer.drain()

        # Read response until error id line
        response_lines = []
        while True:
            line_bytes = await asyncio.wait_for(reader.readline(), timeout=timeout)
            if not line_bytes:
                break
            line_str = line_bytes.decode("utf-8", errors="ignore")
            response_lines.append(line_str)
            if line_str.startswith("error id="):
                break

        writer.write(b"quit\n")
        await writer.drain()
        writer.close()
        await writer.wait_closed()
        return "".join(response_lines)
    except Exception as e:
        logger.debug(f"TS3 ServerQuery error on '{cmd}': {e}")
        return None


async def get_teamspeak_telemetry(force: bool = False) -> Dict[str, Any]:
    """Returns live TeamSpeak 3 telemetry (channels, clients, server status) with 3s cache."""
    now = time.time()
    if not force and (now - _ts3_cache["last_check"] < 3.0):
        return _ts3_cache["data"]

    resp_channels = await send_ts3_command("channellist")
    if resp_channels is None:
        _ts3_cache["last_check"] = now
        _ts3_cache["data"]["online"] = False
        return _ts3_cache["data"]

    resp_clients = await send_ts3_command("clientlist")
    resp_serverinfo = await send_ts3_command("serverinfo")

    raw_channels = parse_ts3_response(resp_channels)
    raw_clients = parse_ts3_response(resp_clients) if resp_clients else []
    serverinfo = parse_ts3_response(resp_serverinfo) if resp_serverinfo else [{}]

    # Filter out query clients (client_type=1) to only show real users
    real_clients = [c for c in raw_clients if c.get("client_type") != "1"]

    clean_channels = []
    for ch in raw_channels:
        cid = int(ch.get("cid", 0))
        cname = ch.get("channel_name", f"Channel {cid}")
        # Count clients in this channel
        in_ch_clients = [c for c in real_clients if int(c.get("cid", 0)) == cid]
        clean_channels.append({
            "cid": cid,
            "name": cname,
            "topic": ch.get("channel_topic", ""),
            "total_clients": len(in_ch_clients),
            "clients": [c.get("client_nickname", "") for c in in_ch_clients]
        })

    s_info = serverinfo[0] if serverinfo else {}
    data = {
        "online": True,
        "host": TS3_HOST,
        "voice_port": TS3_VOICE_PORT,
        "query_port": TS3_QUERY_PORT,
        "server_name": s_info.get("virtualserver_name", "NinjaRadio TeamSpeak 3"),
        "total_clients": len(real_clients),
        "max_clients": int(s_info.get("virtualserver_maxclients", 512)),
        "uptime_sec": int(s_info.get("virtualserver_uptime", 0)),
        "channels": clean_channels,
        "clients": [
            {
                "clid": int(c.get("clid", 0)),
                "cid": int(c.get("cid", 0)),
                "nickname": c.get("client_nickname", "Guest"),
                "channel_id": int(c.get("cid", 0)),
            }
            for c in real_clients
        ]
    }

    _ts3_cache["last_check"] = now
    _ts3_cache["data"] = data
    return data


async def ensure_default_channels():
    """Ensures standard NinjaRadio DMR/SvxLink Talkgroup channels exist in TeamSpeak 3."""
    default_channels = [
        ("TG 1025 - BRIDISV", "DMR TG 1025 Slot 2"),
        ("TG 510990 - Ninja", "DMR TG 510990 Slot 2"),
        ("TG 510 - Nasional", "DMR TG 510 Slot 1"),
        ("TG 5109 - Nusantara", "DMR TG 5109 Slot 2")
    ]
    resp = await send_ts3_command("channellist")
    if not resp:
        return
    existing = parse_ts3_response(resp)
    existing_names = {ch.get("channel_name", "") for ch in existing}

    for name, topic in default_channels:
        if name not in existing_names:
            esc_name = ts_escape(name)
            esc_topic = ts_escape(topic)
            cmd = f"channelcreate channel_name={esc_name} channel_topic={esc_topic} channel_flag_permanent=1 channel_codec=4 channel_codec_quality=7"
            await send_ts3_command(cmd)
            logger.info(f"✓ Created TeamSpeak 3 channel '{name}'")
