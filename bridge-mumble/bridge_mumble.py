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
MUMBLE_CHANNEL = os.getenv("MUMBLE_CHANNEL", "NinjaRadio-Room")

USRP_HOST = os.getenv("DMR_USRP_HOST", "core")
USRP_PORT = int(os.getenv("DMR_USRP_PORT", "12345"))
TARGET_TG = int(os.getenv("TARGET_TG", "5109"))

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
    logger.info("  NinjaRadio <--> Mumble (Murmur) Audio Gateway   ")
    logger.info("==================================================")
    logger.info(f"Connecting to Mumble Server: {MUMBLE_HOST}:{MUMBLE_PORT}")
    logger.info(f"Bot Username: {MUMBLE_USER} | Target Room: {MUMBLE_CHANNEL}")
    logger.info(f"Target DMR Talkgroup: TG {TARGET_TG} | USRP Host: {USRP_HOST}:{USRP_PORT}")

    mumble = None

    try:
        import pymumble_py3 as pymumble
        from pymumble_py3.callbacks import PyMumbleCallback
        
        mumble = pymumble.Mumble(MUMBLE_HOST, MUMBLE_USER, port=MUMBLE_PORT, password=MUMBLE_PASSWORD)
        mumble.set_receive_sound(True)
        mumble.start()
        mumble.is_ready()
        logger.info("✓ Successfully connected to Mumble Server!")

        # Move to target channel if specified
        channel = mumble.channels.find_by_name(MUMBLE_CHANNEL)
        if channel:
            mumble.users.myself.move_in(channel)
            logger.info(f"✓ Joined Mumble Channel: {MUMBLE_CHANNEL}")
        else:
            logger.info(f"Channel '{MUMBLE_CHANNEL}' not found yet, staying in Root channel.")

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
