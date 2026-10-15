import os
import sys
import time
import socket
import struct
import threading
import logging
import json
import re
import numpy as np

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] [Mumble-Bridge] %(message)s"
)
logger = logging.getLogger("mumble-bridge")

CONFIG_PATH = os.getenv("CONFIG_PATH", "/app/data/config.json")
MUMBLE_HOST = os.getenv("MUMBLE_HOST", "mumble-server")
MUMBLE_PORT = int(os.getenv("MUMBLE_PORT", "64738"))
MUMBLE_PASSWORD = os.getenv("MUMBLE_PASSWORD", "")

USRP_HOST = os.getenv("DMR_USRP_HOST", "core")
USRP_PORT = int(os.getenv("DMR_USRP_PORT", "32345"))
TARGET_TG = int(os.getenv("TARGET_TG", "510"))

# State tracking
last_mumble_tx = 0.0
last_dmr_tx = 0.0
active_mumble_tx = None
tx_seq = 0
IS_BRIDGING = True
ROOM_MAPPINGS = {}
GATEWAY_POOL = {}  # room_name -> MumbleGatewayBot

def load_room_mappings():
    """Loads talkgroups dynamically from config.json and merges with defaults."""
    mappings = {
        "NinjaRadio-Nasional": 510,
        "NinjaRadio-Nusantara": 5109,
        "Echo-Parrot": 9990,
    }

    # Fallback env mappings
    raw_map = os.getenv("MUMBLE_ROOM_MAP", "")
    if raw_map:
        for item in raw_map.split(","):
            if ":" in item:
                ch, tg = item.split(":", 1)
                try:
                    mappings[ch.strip()] = int(tg.strip())
                except ValueError:
                    pass

    # Read from /app/data/config.json if mounted
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                data = json.load(f)
                custom_rooms = data.get("mumble_rooms", [])
                for r in custom_rooms:
                    ch = r.get("channel")
                    tg = r.get("tg")
                    if ch and tg is not None:
                        try:
                            mappings[ch.strip()] = int(tg)
                        except (ValueError, TypeError):
                            pass

                peers = data.get("peering", [])
                for p in peers:
                    tgs_str = p.get("subscribedTgs", "")
                    for tg_part in re.split(r"[,; ]+", tgs_str):
                        tg_part = tg_part.strip()
                        if tg_part.isdigit():
                            tg_num = int(tg_part)
                            mappings[f"TG-{tg_num}"] = tg_num
        except Exception as e:
            logger.debug(f"Could not parse config.json for rooms: {e}")

    return mappings

def ensure_channels_created():
    """Connects as SuperUser to create permanent channels in Murmur if not present."""
    import pymumble_py3 as pymumble
    superuser_pwd = os.getenv("MUMBLE_SUPERUSER_PASSWORD", "ninjaradio_admin")
    logger.info("Initializing Murmur channels as SuperUser...")
    try:
        su = pymumble.Mumble(MUMBLE_HOST, "SuperUser", port=MUMBLE_PORT, password=superuser_pwd)
        su.start()
        su.is_ready()
        time.sleep(1.0)

        existing_names = set()
        for ch in list(su.channels.values()):
            try:
                name = ch["name"] if (isinstance(ch, dict) or hasattr(ch, "__getitem__")) else getattr(ch, "name", None)
                if name:
                    existing_names.add(str(name))
            except Exception:
                pass

        logger.info(f"Existing Murmur channels before init: {list(existing_names)}")
        for room_name, tg_val in ROOM_MAPPINGS.items():
            if room_name not in existing_names:
                try:
                    su.channels.new_channel(0, room_name, temporary=False)
                    logger.info(f"✓ SuperUser created permanent Mumble Room: '{room_name}' (TG {tg_val})")
                    time.sleep(0.3)
                except Exception as ce:
                    logger.warning(f"Could not create channel '{room_name}': {ce}")
            else:
                logger.info(f"✓ Channel '{room_name}' already exists.")
        su.stop()
        time.sleep(0.8)
    except Exception as e:
        logger.warning(f"SuperUser channel initialization warning: {e}")

class MumbleGatewayBot:
    """Dedicated bot instance for a single Mumble channel & DMR Talkgroup pair."""

    def __init__(self, room_name: str, tg: int, usrp_sock: socket.socket):
        self.room_name = room_name
        self.tg = tg
        self.usrp_sock = usrp_sock
        # Generate clean readable bot username: e.g. "NinjaBot-Nasional", "NinjaBot-Nusantara", "NinjaBot-Echo"
        clean_name = room_name.replace("NinjaRadio-", "").replace(" ", "-")
        self.bot_user = f"NinjaBot-{clean_name}"[:25]
        self.mumble = None
        self.running = False
        self.thread = None

    def start(self):
        if self.running:
            return
        self.running = True
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def stop(self):
        self.running = False
        if self.mumble:
            try:
                self.mumble.stop()
            except Exception:
                pass

    def play_pcm_8k(self, pcm_8k: bytes):
        if self.mumble and self.mumble.is_alive() and len(pcm_8k) > 0:
            try:
                # Resample 8kHz 16-bit mono PCM to 48kHz for Mumble Opus encoder
                samples_8k = np.frombuffer(pcm_8k, dtype=np.int16)
                samples_48k = np.repeat(samples_8k, 6)
                self.mumble.sound_output.add_sound(samples_48k.tobytes())
            except Exception:
                pass

    def _run(self):
        import pymumble_py3 as pymumble
        import pymumble_py3.constants as mumble_constants

        logger.info(f"[{self.bot_user}] 🚀 Launching gateway bot for '{self.room_name}' <---> DMR TG {self.tg}")
        while self.running and IS_BRIDGING:
            try:
                self.mumble = pymumble.Mumble(MUMBLE_HOST, self.bot_user, port=MUMBLE_PORT, password=MUMBLE_PASSWORD)
                self.mumble.set_receive_sound(True)

                def sound_received_handler(user, soundchunk):
                    global last_mumble_tx, active_mumble_tx, tx_seq
                    if not user:
                        return
                    uname = user.get("name", "")
                    if uname.startswith("NinjaBot-") or uname == "SuperUser":
                        return

                    # Prevent audio loopback from DMR RX
                    if time.time() - last_dmr_tx < 0.3:
                        return

                    pcm_48k = getattr(soundchunk, "pcm", None)
                    if not pcm_48k:
                        return

                    match = re.search(r"\b(\d{7})\b", str(uname))
                    if match:
                        user_dmr_id = int(match.group(1))
                    else:
                        user_dmr_id = 5100000 + (user.get("session", 1) % 99999)

                    try:
                        # Downsample 48kHz -> 8kHz PCM (decimate by 6)
                        samples_48k = np.frombuffer(pcm_48k, dtype=np.int16)
                        samples_8k = samples_48k[::6]
                        pcm_8k = samples_8k.tobytes()

                        tx_seq = (tx_seq + 1) % 65535
                        header = struct.pack(">4sIIIIIII", b"USRP", tx_seq, 0, 1, self.tg, user_dmr_id, 1, 0)
                        self.usrp_sock.sendto(header + pcm_8k, (USRP_HOST, USRP_PORT))

                        last_mumble_tx = time.time()
                        if not active_mumble_tx:
                            logger.info(f"[MUMBLE-TX] 🔴 PTT TRANSMIT from {uname} in '{self.room_name}' -> DMR TG {self.tg}")
                        active_mumble_tx = (user, self.tg, user_dmr_id, last_mumble_tx)
                    except Exception as ex:
                        logger.debug(f"[{self.bot_user}] Error streaming sound chunk: {ex}")

                self.mumble.callbacks.set_callback(mumble_constants.PYMUMBLE_CLBK_SOUNDRECEIVED, sound_received_handler)
                self.mumble.start()
                self.mumble.is_ready()
                logger.info(f"[{self.bot_user}] ✓ Connected to Mumble server!")

                # Locate target channel and move bot inside
                time.sleep(1.2)
                target_ch = None
                for ch in list(self.mumble.channels.values()):
                    try:
                        cname = ch["name"] if (isinstance(ch, dict) or hasattr(ch, "__getitem__")) else getattr(ch, "name", None)
                        if cname == self.room_name:
                            target_ch = ch
                            break
                    except Exception:
                        pass

                if target_ch:
                    ch_id = target_ch["channel_id"] if (isinstance(target_ch, dict) or hasattr(target_ch, "__getitem__")) else getattr(target_ch, "channel_id", 0)
                    self.mumble.users.myself.move_in(int(ch_id))
                    logger.info(f"[{self.bot_user}] ✓ Permanently stationed in '{self.room_name}' (ID: {ch_id})")
                else:
                    logger.warning(f"[{self.bot_user}] Channel '{self.room_name}' not yet available in channel tree.")

                # Keep thread alive while connection lasts
                while self.running and IS_BRIDGING and self.mumble.is_alive():
                    time.sleep(2)

            except Exception as e:
                logger.warning(f"[{self.bot_user}] Connection retry in 5s: {e}")
                time.sleep(5)

def run_usrp_listener(gateway_pool):
    """
    Listens for PCM audio frames from Core / DMR USRP UDP port (12346) and routes to the appropriate room bot.
    """
    global last_dmr_tx
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    bind_port = 12346
    try:
        sock.bind(("0.0.0.0", bind_port))
        logger.info(f"USRP RX Listener bound on UDP port {bind_port}")
    except Exception as e:
        logger.warning(f"Could not bind on {bind_port}: {e}")

    while IS_BRIDGING:
        try:
            data, addr = sock.recvfrom(2048)
            if len(data) >= 32 and (time.time() - last_mumble_tx > 0.35):
                last_dmr_tx = time.time()
                pcm_8k = data[32:]

                # Extract talkgroup from USRP header (bytes 16..20)
                talkgroup = struct.unpack(">I", data[16:20])[0]

                # Match bot by target talkgroup
                target_bots = [bot for bot in gateway_pool.values() if bot.tg == talkgroup]
                if not target_bots:
                    # Fallback to default TG bot or first active bot
                    target_bots = [bot for bot in gateway_pool.values() if bot.tg == TARGET_TG]
                    if not target_bots and gateway_pool:
                        target_bots = [list(gateway_pool.values())[0]]

                for bot in target_bots:
                    bot.play_pcm_8k(pcm_8k)

        except Exception:
            time.sleep(0.02)

def ptt_watchdog_loop(usrp_sock):
    """Detects when a Mumble/Mumla user stops talking and transmits PTT release to USRP/DMR."""
    global active_mumble_tx, tx_seq
    while IS_BRIDGING:
        time.sleep(0.05)
        if active_mumble_tx:
            user_info, tg, dmr_id, last_seen = active_mumble_tx
            if time.time() - last_seen > 0.35:
                tx_seq = (tx_seq + 1) % 65535
                header = struct.pack(">4sIIIIIII", b"USRP", tx_seq, 0, 0, tg, dmr_id, 1, 0)
                try:
                    usrp_sock.sendto(header, (USRP_HOST, USRP_PORT))
                    logger.info(f"[MUMBLE-TX] ⏹️ PTT RELEASE from {user_info.get('name')} -> TG {tg}")
                except Exception:
                    pass
                active_mumble_tx = None

def sync_gateway_bots(usrp_sock):
    """Ensures each configured Mumble room has an active gateway bot running."""
    global GATEWAY_POOL, ROOM_MAPPINGS
    for room_name, tg_val in ROOM_MAPPINGS.items():
        if room_name not in GATEWAY_POOL:
            bot = MumbleGatewayBot(room_name, tg_val, usrp_sock)
            GATEWAY_POOL[room_name] = bot
            bot.start()
            time.sleep(0.5)

def config_file_watcher_loop(usrp_sock):
    """Background thread watching /app/data/config.json for dynamic channel changes."""
    global ROOM_MAPPINGS
    last_mtime = 0.0
    while IS_BRIDGING:
        time.sleep(3.0)
        if os.path.exists(CONFIG_PATH):
            try:
                mtime = os.path.getmtime(CONFIG_PATH)
                if last_mtime > 0 and mtime > last_mtime:
                    logger.info("⚡ Detected changes in config.json! Reloading Mumble rooms...")
                    new_mappings = load_room_mappings()
                    ROOM_MAPPINGS.update(new_mappings)
                    ensure_channels_created()
                    sync_gateway_bots(usrp_sock)
                last_mtime = mtime
            except Exception as e:
                logger.debug(f"config_watcher error: {e}")

def main():
    global ROOM_MAPPINGS, last_mumble_tx, active_mumble_tx, tx_seq
    logger.info("==================================================")
    logger.info("  NinjaRadio Multi-Room Mumble (Murmur) Gateway   ")
    logger.info("==================================================")
    logger.info(f"Connecting to Mumble Server: {MUMBLE_HOST}:{MUMBLE_PORT}")
    logger.info(f"USRP Audio Target: {USRP_HOST}:{USRP_PORT}")

    ROOM_MAPPINGS = load_room_mappings()
    for room_name, tg_val in ROOM_MAPPINGS.items():
        logger.info(f"  • Room '{room_name}' <---> DMR TG {tg_val}")

    # 1. Bootstrap channels in Murmur
    ensure_channels_created()

    usrp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    watchdog = threading.Thread(target=ptt_watchdog_loop, args=(usrp_sock,), daemon=True)
    watchdog.start()

    # 2. Launch multi-room bot pool (one bot per room)
    sync_gateway_bots(usrp_sock)

    # 3. Start config watcher
    threading.Thread(target=config_file_watcher_loop, args=(usrp_sock,), daemon=True).start()

    # 4. Start USRP Listener thread (for DMR RX audio into Mumble)
    t = threading.Thread(target=run_usrp_listener, args=(GATEWAY_POOL,), daemon=True)
    t.start()

    # Keep alive loop
    try:
        while True:
            time.sleep(5)
    except KeyboardInterrupt:
        logger.info("Stopping Mumble Bridge...")
        for bot in GATEWAY_POOL.values():
            bot.stop()

if __name__ == "__main__":
    main()
