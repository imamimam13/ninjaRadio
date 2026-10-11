import asyncio
import socket
import logging
import struct
import json
import os

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] [NinjaRadio-Core] %(message)s")

DMR_MASTER_PORT = int(os.getenv("DMR_MASTER_PORT", 62031))
OPENBRIDGE_PORT = int(os.getenv("OPENBRIDGE_PORT", 62030))
USRP_AUDIO_PORT = int(os.getenv("USRP_AUDIO_PORT", 32345))

class NinjaRadioCoreServer:
    def __init__(self):
        self.connected_hotspots = {}
        self.active_bridges = {}

    def start_hotspot_listener(self):
        """Listens for incoming MMDVM / Pi-Star HomeBrew protocol UDP packets."""
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.bind(("0.0.0.0", DMR_MASTER_PORT))
        logging.info(f"📻 DMR Master Engine listening on UDP port {DMR_MASTER_PORT} (Hotspot / Pi-Star / MMDVM)")
        return sock

    def start_openbridge_listener(self):
        """Listens for OpenBridge / FreeDMR peering UDP packets."""
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.bind(("0.0.0.0", OPENBRIDGE_PORT))
        logging.info(f"🌐 OpenBridge / FreeDMR Engine listening on UDP port {OPENBRIDGE_PORT} (BrandMeister / FreeDMR Peering)")
        return sock

    def start_usrp_listener(self):
        """Listens for USRP Audio UDP packets from DVSwitch Mobile Android app."""
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.bind(("0.0.0.0", USRP_AUDIO_PORT))
        logging.info(f"📱 DVSwitch & USRP Engine listening on UDP port {USRP_AUDIO_PORT} (DVSwitch Mobile Android & ASL)")
        return sock

    async def handle_udp_traffic(self, name, sock):
        loop = asyncio.get_running_loop()
        while True:
            try:
                data, addr = await loop.sock_recvfrom(sock, 2048)
                if len(data) > 0:
                    logging.info(f"[{name}] Packet received from {addr} ({len(data)} bytes)")
            except Exception as e:
                await asyncio.sleep(1)

    async def run(self):
        sock_dmr = self.start_hotspot_listener()
        sock_ob = self.start_openbridge_listener()
        sock_usrp = self.start_usrp_listener()

        # Set non-blocking for asyncio
        sock_dmr.setblocking(False)
        sock_ob.setblocking(False)
        sock_usrp.setblocking(False)

        await asyncio.gather(
            self.handle_udp_traffic("DMR-Hotspot", sock_dmr),
            self.handle_udp_traffic("OpenBridge", sock_ob),
            self.handle_udp_traffic("DVSwitch-USRP", sock_usrp)
        )

if __name__ == "__main__":
    server = NinjaRadioCoreServer()
    asyncio.run(server.run())
