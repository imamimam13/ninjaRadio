import os
import json
import re

CONFIG_FILE = "/app/ninja_data/config.json"
DATA_DIR = "/app/data"
BOTS_DIR = os.path.join(DATA_DIR, "bots")

DEFAULT_ROOMS = [
    {"cid": 2, "name": "TG 1025 - BRIDISV", "tg": 1025},
    {"cid": 3, "name": "TG 510990 - Ninja", "tg": 510990},
    {"cid": 4, "name": "TG 510 - Nasional", "tg": 510},
    {"cid": 5, "name": "TG 5109 - Nusantara", "tg": 5109}
]

DEFAULT_BOTS = [
    {"name": "TS3-GW-BRIDISV", "channel": "TG 1025 - BRIDISV", "tg": 1025, "slot": 2},
    {"name": "TS3-GW-NINJA", "channel": "TG 510990 - Ninja", "tg": 510990, "slot": 2},
    {"name": "TS3-GW-NASIONAL", "channel": "TG 510 - Nasional", "tg": 510, "slot": 1}
]

def sanitize_id(name: str) -> str:
    return re.sub(r"[^a-zA-Z0-9_-]", "_", name.lower())

def main():
    os.makedirs(BOTS_DIR, exist_ok=True)

    rooms = DEFAULT_ROOMS
    bots = DEFAULT_BOTS

    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                cfg = json.load(f)
                if "teamspeak_rooms" in cfg and isinstance(cfg["teamspeak_rooms"], list) and len(cfg["teamspeak_rooms"]) > 0:
                    rooms = cfg["teamspeak_rooms"]
                if "teamspeak_bots" in cfg and isinstance(cfg["teamspeak_bots"], list) and len(cfg["teamspeak_bots"]) > 0:
                    bots = cfg["teamspeak_bots"]
        except Exception as e:
            print(f"[TS3-Bridge] Error reading config.json: {e}")

    # Build room lookup table (name/tg -> cid)
    room_cid_map = {}
    for r in rooms:
        cid = r.get("cid")
        rname = (r.get("name") or r.get("channel") or "").strip()
        rtg = r.get("targetTg") if r.get("targetTg") is not None else r.get("tg")
        if cid:
            if rname:
                room_cid_map[rname.lower()] = cid
            if rtg:
                room_cid_map[str(rtg)] = cid

    # Write root ts3audiobot.toml
    bots_dict_lines = []
    for b in bots:
        b_name = b.get("name", "").strip()
        if not b_name:
            continue
        b_id = sanitize_id(b_name)
        bots_dict_lines.append(f'{b_id} = {{ run = true }}')

    bots_block = "\n".join(bots_dict_lines)
    ts3audiobot_toml = f"""[bot]
language = "en"
run = false

[bots]
{bots_block}

[web]
hosts = ["127.0.0.1"]
port = 58913

[web.api]
enabled = true

[configs]
bots_path = "bots"
send_stats = false
"""

    with open(os.path.join(DATA_DIR, "ts3audiobot.toml"), "w", encoding="utf-8") as f:
        f.write(ts3audiobot_toml)

    # Write individual bot.toml
    for b in bots:
        b_name = b.get("name", "").strip()
        if not b_name:
            continue
        b_id = sanitize_id(b_name)
        b_dir = os.path.join(BOTS_DIR, b_id)
        os.makedirs(b_dir, exist_ok=True)

        target_server = (b.get("server") or b.get("address") or "127.0.0.1:9987").strip()
        server_pw = (b.get("server_password") or b.get("password") or "").strip()
        channel_pw = (b.get("channel_password") or "").strip()

        target_ch = (b.get("channel") or "").strip()
        target_tg = b.get("tg")

        # For local server, resolve to channel CID if known; for remote server, use channel path directly
        is_local = target_server.startswith("127.0.0.1") or target_server.startswith("localhost") or target_server.startswith("teamspeak-server")
        cid = None
        if is_local:
            cid = room_cid_map.get(target_ch.lower())
            if not cid and target_tg:
                cid = room_cid_map.get(str(target_tg))

        channel_setting = f"/{cid}" if cid else f"/{target_ch}"
        server_pw_line = f'server_password = "{server_pw}"\n' if server_pw else ""
        channel_pw_line = f'channel_password = "{channel_pw}"\n' if channel_pw else ""

        bot_toml = f"""run = true

[connect]
address = "{target_server}"
name = "{b_name}"
channel = "{channel_setting}"
{server_pw_line}{channel_pw_line}"""
        with open(os.path.join(b_dir, "bot.toml"), "w", encoding="utf-8") as f:
            f.write(bot_toml)

        print(f"[TS3-Bridge] Configured bot '{b_name}' ({b_id}) -> channel {channel_setting}")

    print(f"[TS3-Bridge] Generated config for {len(bots)} TeamSpeak 3 bot(s) successfully.")

if __name__ == "__main__":
    main()
