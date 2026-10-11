import os
import sys
import time
import socket
import struct
import threading
import logging

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] [Mumble-Bridge] %(message)s"
)
logger = logging.getLogger("mumble-bridge")

MUMBLE_HOST = os.getenv("MUMBLE_HOST", "mumble-server")
MUMBLE_PORT = int(os.getenv("MUMBLE_PORT", "64738"))
MUMBLE_USER = os.getenv("MUMBLE_USER", "NinjaRadio-Gateway")
MUMBLE_PASSWORD = os.getenv("MUMBLE_PASSWORD", "")
MUMBLE_CHANNEL = os.getenv("MUMBLE_CHANNEL", "NinjaRadio-Nasional")

USRP_HOST = os.getenv("DMR_USRP_HOST", "core")
USRP_PORT = int(os.getenv("DMR_USRP_PORT", "32345"))
TARGET_TG = int(os.getenv("TARGET_TG", "5109"))

# Multi-room / Multi-Talkgroup dictionary
RAW_ROOM_MAP = os.getenv("MUMBLE_ROOM_MAP", "NinjaRadio-Nasional:510,NinjaRadio-Nusantara:5109,NinjaRadio-Darurat:5108,NinjaRadio-Worldwide:91")
ROOM_MAPPINGS = {}
for item in RAW_ROOM_MAP.split(","):
    if ":" in item:
        ch, tg = item.split(":", 1)
        try:
            ROOM_MAPPINGS[ch.strip()] = int(tg.strip())
        except ValueError:
            pass
if not ROOM_MAPPINGS:
    ROOM_MAPPINGS = {"NinjaRadio-Nasional": 510, "NinjaRadio-Nusantara": 5109}

# Anti-loop state
last_mumble_tx = 0
last_dmr_tx = 0
IS_BRIDGING = True

def run_usrp_listener(mumble_instance):
    """
    Listens for PCM audio frames from NinjaDMR USRP UDP port and forwards them to Mumble.
    """
    global last_dmr_tx
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    # Bind to local bridge port to receive audio from Analog_Bridge / Core
    bind_port = 12346
    try:
        sock.bind(("0.0.0.0", bind_port))
        logger.info(f"USRP RX Listener bound on UDP port {bind_port}")
    except Exception as e:
        logger.warning(f"Could not bind on {bind_port}, operating in direct relay mode: {e}")

    while IS_BRIDGING:
        try:
            data, addr = sock.recvfrom(2048)
            if len(data) > 32 and (time.time() - last_mumble_tx > 0.4):
                # USRP header is usually 32 bytes (USRP\x00...), payload is PCM
                last_dmr_tx = time.time()
                pcm_payload = data[32:]
                
                # Transmit to Mumble if connected
                if mumble_instance and mumble_instance.is_alive():
                    # Send audio to Mumble
                    try:
                        mumble_instance.sound_output.add_sound(pcm_payload)
                    except Exception as err:
                        pass
        except Exception as e:
            time.sleep(0.05)

def main():
    logger.info("==================================================")
    logger.info("  NinjaRadio Multi-Room Mumble (Murmur) Gateway   ")
    logger.info("==================================================")
    logger.info(f"Connecting to Mumble Server: {MUMBLE_HOST}:{MUMBLE_PORT}")
    logger.info(f"Bot Username: {MUMBLE_USER}")
    logger.info(f"Configured Multi-Room Mappings:")
    for room_name, tg_val in ROOM_MAPPINGS.items():
        logger.info(f"  • Room '{room_name}' <---> DMR TG {tg_val}")
    logger.info(f"USRP Audio Host: {USRP_HOST}:{USRP_PORT}")

    mumble = None

    try:
        import pymumble_py3 as pymumble
        from pymumble_py3.callbacks import PyMumbleCallback
        
        mumble = pymumble.Mumble(MUMBLE_HOST, MUMBLE_USER, port=MUMBLE_PORT, password=MUMBLE_PASSWORD)
        mumble.set_receive_sound(True)
        mumble.start()
        mumble.is_ready()
        logger.info("✓ Successfully connected to Mumble Server!")

        # Initialize and setup multi-rooms
        for room_name, tg_val in ROOM_MAPPINGS.items():
            ch = mumble.channels.find_by_name(room_name)
            if not ch:
                try:
                    # Create channel under root if possible
                    mumble.channels.new_channel(0, room_name, temporary=False)
                    logger.info(f"✓ Created Mumble Channel: {room_name} (TG {tg_val})")
                except Exception as ce:
                    logger.debug(f"Channel create notice: {ce}")
            else:
                logger.info(f"✓ Found Mumble Channel: {room_name} (TG {tg_val})")

        # Move bot into first configured channel or root
        first_ch_name = list(ROOM_MAPPINGS.keys())[0]
        channel = mumble.channels.find_by_name(first_ch_name)
        if channel:
            mumble.users.myself.move_in(channel)
            logger.info(f"✓ Joined Primary Mumble Channel: {first_ch_name}")

    except Exception as e:
        logger.warning(f"pymumble connection standby / simulated mode: {e}")

    # Start USRP Listener thread
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
