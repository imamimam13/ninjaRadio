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
    active_bot_ids = set()
    for b in bots:
        b_name = b.get("name", "").strip()
        if not b_name:
            continue
        b_id = sanitize_id(b_name)
        active_bot_ids.add(b_id)
        bots_dict_lines.append(f'{b_id} = {{ run = true }}')

    # Remove stale bot config folders that are no longer in config.json
    if os.path.exists(BOTS_DIR):
        for item in os.listdir(BOTS_DIR):
            item_path = os.path.join(BOTS_DIR, item)
            if os.path.isdir(item_path) and item not in active_bot_ids:
                print(f"[TS3-Bridge] Removing stale bot folder: {item}")
                import shutil
                shutil.rmtree(item_path, ignore_errors=True)

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

    # Write rights.toml to grant local Web API full admin rights (enables /bot/move and /server/tree)
    rights_toml = """[[rule]]
"+" = "*"

[[rule]]
ip = [ "127.0.0.1", "::1" ]
"+" = "*"
"""
    with open(os.path.join(DATA_DIR, "rights.toml"), "w", encoding="utf-8") as f:
        f.write(rights_toml)

    # Write individual bot.toml and target.cfg
    for idx, b in enumerate(bots):
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
        b_tg = int(b.get("tg") if b.get("tg") is not None else 1025)
        b_slot = int(b.get("slot") if b.get("slot") is not None else (1 if b_tg in [510, 5108, 91] else 2))
        rx_p = int(b.get("rx_port", 12352 + idx))
        raw_static = b.get("static_name") if b.get("static_name") is not None else (b.get("static") if b.get("static") is not None else b.get("is_static", False))
        if isinstance(raw_static, str):
            b_static = raw_static.strip().lower() in ["true", "1", "yes"]
        else:
            b_static = bool(raw_static)

        # Format channel parameter for TS3AudioBot:
        # - Always use the channel name directly if a text name is provided (100% portable across any TS3 server/VPS without hardcoded CID mismatch).
        # - Only prefix with '/' if the user explicitly specified a numeric Channel ID (e.g. '172' or '/7').
        if target_ch.startswith("/") and target_ch[1:].isdigit():
            channel_setting = target_ch
        elif target_ch.isdigit():
            channel_setting = f"/{target_ch}"
        elif target_ch:
            channel_setting = target_ch.lstrip("/")
        else:
            cid = room_cid_map.get(str(b_tg)) if is_local else None
            channel_setting = f"/{cid}" if cid else ""

        # TS3AudioBot uses inline table or sub-table for passwords
        server_pw_line = f'server_password = {{ pw = "{server_pw}" }}\n' if server_pw else ""
        channel_pw_line = f'channel_password = {{ pw = "{channel_pw}" }}\n' if channel_pw else ""

        # Outgoing bot name: enforce ALL CAPITAL LETTERS across TeamSpeak
        b_name_upper = b_name.upper()

        # Preserve existing cryptographic identity from bot.toml (CRITICAL: prevents losing Sysop Server Group/permissions on restart/rebuild)
        existing_identity = ""
        bot_toml_path = os.path.join(b_dir, "bot.toml")
        if os.path.exists(bot_toml_path):
            try:
                with open(bot_toml_path, "r", encoding="utf-8") as f:
                    old_toml = f.read()
                if "[connect.identity]" in old_toml:
                    existing_identity = "\n" + old_toml[old_toml.find("[connect.identity]"):].strip() + "\n"
                elif "identity" in old_toml.lower():
                    ident_blocks = []
                    capture = False
                    for line in old_toml.splitlines():
                        if "identity" in line.lower():
                            capture = True
                        if capture:
                            ident_blocks.append(line)
                    if ident_blocks:
                        existing_identity = "\n" + "\n".join(ident_blocks).strip() + "\n"
            except Exception as ex:
                print(f"[TS3-Bridge] Note checking old bot.toml identity: {ex}")

        bot_toml = f"""run = true

[connect]
address = "{target_server}"
name = "{b_name_upper}"
channel = "{channel_setting}"
{server_pw_line}{channel_pw_line}{existing_identity}"""
        with open(bot_toml_path, "w", encoding="utf-8") as f:
            f.write(bot_toml)

        # Write target.cfg for NinjaRadioBridge.cs plugin
        target_cfg = f"""tg={b_tg}
slot={b_slot}
rx_port={rx_p}
name={b_name_upper}
server={target_server}
channel={target_ch}
channel_password={channel_pw}
static_name={"true" if b_static else "false"}
"""
        with open(os.path.join(b_dir, "target.cfg"), "w", encoding="utf-8") as f:
            f.write(target_cfg)

        print(f"[TS3-Bridge] Configured bot '{b_name}' ({b_id}) -> TG {b_tg} (Slot {b_slot}, Port {rx_p}), channel {channel_setting} on {target_server}")

    print(f"[TS3-Bridge] Generated config for {len(bots)} TeamSpeak 3 bot(s) successfully.")

if __name__ == "__main__":
    main()
