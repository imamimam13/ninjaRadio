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
MUMBLE_USER = os.getenv("MUMBLE_USER", "NinjaRadio-Gateway")
MUMBLE_PASSWORD = os.getenv("MUMBLE_PASSWORD", "")
MUMBLE_CHANNEL = os.getenv("MUMBLE_CHANNEL", "NinjaRadio-Nasional")

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

def run_usrp_listener(mumble_instance):
    """
    Listens for PCM audio frames from Core / DMR USRP UDP port (12346) and forwards them to Mumble.
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

                # Transmit to Mumble if connected
                if mumble_instance and mumble_instance.is_alive() and len(pcm_8k) > 0:
                    try:
                        # Resample 8kHz 16-bit mono PCM to 48kHz for Mumble Opus encoder
                        samples_8k = np.frombuffer(pcm_8k, dtype=np.int16)
                        samples_48k = np.repeat(samples_8k, 6)
                        mumble_instance.sound_output.add_sound(samples_48k.tobytes())
                    except Exception:
                        pass
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
                # Transmit USRP PTT Release frame (key_flag = 0)
                tx_seq = (tx_seq + 1) % 65535
                header = struct.pack(">4sIIIIIII", b"USRP", tx_seq, 0, 0, tg, dmr_id, 1, 0)
                try:
                    usrp_sock.sendto(header, (USRP_HOST, USRP_PORT))
                    logger.info(f"[MUMBLE-TX] ⏹️ PTT RELEASE from {user_info.get('name')} -> TG {tg}")
                except Exception:
                    pass
                active_mumble_tx = None

def main():
    global ROOM_MAPPINGS, last_mumble_tx, active_mumble_tx, tx_seq
    logger.info("==================================================")
    logger.info("  NinjaRadio Multi-Room Mumble (Murmur) Gateway   ")
    logger.info("==================================================")
    logger.info(f"Connecting to Mumble Server: {MUMBLE_HOST}:{MUMBLE_PORT}")
    logger.info(f"Bot Username: {MUMBLE_USER}")
    logger.info(f"USRP Audio Target: {USRP_HOST}:{USRP_PORT}")

    ROOM_MAPPINGS = load_room_mappings()
    for room_name, tg_val in ROOM_MAPPINGS.items():
        logger.info(f"  • Room '{room_name}' <---> DMR TG {tg_val}")

    usrp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    watchdog = threading.Thread(target=ptt_watchdog_loop, args=(usrp_sock,), daemon=True)
    watchdog.start()

    mumble = None
    try:
        import pymumble_py3 as pymumble
        from pymumble_py3.callbacks import PyMumbleCallback

        mumble = pymumble.Mumble(MUMBLE_HOST, MUMBLE_USER, port=MUMBLE_PORT, password=MUMBLE_PASSWORD)
        mumble.set_receive_sound(True)

        def sound_received_handler(user, soundchunk):
            """Called whenever a Mumla/Mumble user speaks into their microphone."""
            global last_mumble_tx, active_mumble_tx, tx_seq
            if not user or user.get("name") == MUMBLE_USER:
                return

            # Avoid echo loops from recent DMR playback
            if time.time() - last_dmr_tx < 0.3:
                return

            pcm_48k = getattr(soundchunk, "pcm", None)
            if not pcm_48k:
                return

            # Resolve user's current room / channel
            channel_id = user.get("channel_id", 0)
            channel = mumble.channels.get(channel_id, {})
            channel_name = channel.get("name", "") if isinstance(channel, dict) else getattr(channel, "name", "")
            target_tg = ROOM_MAPPINGS.get(channel_name, TARGET_TG)

            # Resolve DMR ID from username or session ID
            match = re.search(r"\b(\d{7})\b", str(user.get("name", "")))
            if match:
                user_dmr_id = int(match.group(1))
            else:
                user_dmr_id = 5100000 + (user.get("session", 1) % 99999)

            # Downsample 48kHz PCM -> 8kHz PCM (decimate by 6) for DMR / DVSwitch
            try:
                samples_48k = np.frombuffer(pcm_48k, dtype=np.int16)
                samples_8k = samples_48k[::6]
                pcm_8k = samples_8k.tobytes()

                # Package 32-byte USRP packet (key_flag = 1 for voice frame)
                tx_seq = (tx_seq + 1) % 65535
                header = struct.pack(">4sIIIIIII", b"USRP", tx_seq, 0, 1, target_tg, user_dmr_id, 1, 0)
                usrp_sock.sendto(header + pcm_8k, (USRP_HOST, USRP_PORT))

                last_mumble_tx = time.time()
                if not active_mumble_tx:
                    logger.info(f"[MUMBLE-TX] 🔴 PTT TRANSMIT from {user.get('name')} in '{channel_name}' -> TG {target_tg}")
                active_mumble_tx = (user, target_tg, user_dmr_id, last_mumble_tx)
            except Exception as ex:
                logger.debug(f"Error streaming sound chunk: {ex}")

        mumble.callbacks.set_callback(PyMumbleCallback.SOUND_RECEIVED, sound_received_handler)
        mumble.start()
        mumble.is_ready()
        logger.info("✓ Successfully connected to Mumble Server!")

        # Initialize and create channels
        for room_name, tg_val in ROOM_MAPPINGS.items():
            ch = mumble.channels.find_by_name(room_name)
            if not ch:
                try:
                    mumble.channels.new_channel(0, room_name, temporary=False)
                    logger.info(f"✓ Created Mumble Channel: {room_name} (TG {tg_val})")
                except Exception as ce:
                    logger.debug(f"Channel create notice: {ce}")
            else:
                logger.info(f"✓ Found Mumble Channel: {room_name} (TG {tg_val})")

        first_ch_name = list(ROOM_MAPPINGS.keys())[0]
        channel = mumble.channels.find_by_name(first_ch_name)
        if channel:
            mumble.users.myself.move_in(channel)
            logger.info(f"✓ Joined Primary Mumble Channel: {first_ch_name}")

    except Exception as e:
        logger.warning(f"pymumble connection standby / simulated mode: {e}")

    # Start USRP Listener thread (for audio arriving from DMR into Mumble)
    t = threading.Thread(target=run_usrp_listener, args=(mumble,), daemon=True)
    t.start()

    # Keep alive loop
    try:
        while True:
            time.sleep(5)
    except KeyboardInterrupt:
        logger.info("Stopping Mumble Bridge...")

if __name__ == "__main__":
    main()

