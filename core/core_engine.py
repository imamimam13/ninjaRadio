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
        self.parrot_buffers = {}

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
        """Listens for USRP Audio UDP packets from DVSwitch Mobile Android app & Web Transceiver."""
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.bind(("0.0.0.0", USRP_AUDIO_PORT))
        logging.info(f"📱 DVSwitch & USRP Engine listening on UDP port {USRP_AUDIO_PORT} (DVSwitch Mobile & Web PTT)")
        return sock

    async def handle_usrp_traffic(self, sock):
        loop = asyncio.get_running_loop()
        while True:
            try:
                data, addr = await loop.sock_recvfrom(sock, 2048)
                if len(data) >= 32 and data.startswith(b"USRP"):
                    # Decode standard 32-byte DVSwitch / USRP header
                    magic, seq, mem, key_flag, tg, dmr_id, slot, res = struct.unpack(">4sIIIIIII", data[:32])
                    if key_flag == 1:
                        logging.info(f"[USRP/DVSwitch] 🔴 PTT TRANSMIT from {addr} | TG: {tg} | DMR ID: {dmr_id} | Slot: {slot}")
                        if tg == 9990:
                            if addr not in self.parrot_buffers:
                                self.parrot_buffers[addr] = []
                            self.parrot_buffers[addr].append(data)
                    else:
                        logging.info(f"[USRP/DVSwitch] ⏹️ PTT RELEASE from {addr} | TG: {tg} | DMR ID: {dmr_id}")
                        if tg == 9990 and addr in self.parrot_buffers:
                            # Trigger background Parrot Echo playback
                            buffered = self.parrot_buffers.pop(addr, [])
                            if buffered:
                                asyncio.create_task(self.play_parrot_back(sock, addr, buffered))
                elif len(data) > 0:
                    logging.info(f"[USRP-Raw] Packet received from {addr} ({len(data)} bytes)")
            except Exception as e:
                await asyncio.sleep(0.05)

    async def play_parrot_back(self, sock, addr, packets):
        """Replays recorded voice packets back to caller like a real DMR repeater parrot."""
        logging.info(f"[PARROT] 🦜 Triggering Echo Playback to {addr} ({len(packets)} frames)...")
        await asyncio.sleep(1.2)
        for pkt in packets:
            try:
                sock.sendto(pkt, addr)
                await asyncio.sleep(0.02) # Standard 20ms frame interval
            except Exception:
                pass
        logging.info(f"[PARROT] ✓ Echo Playback finished for {addr}")

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
            self.handle_usrp_traffic(sock_usrp)
        )

if __name__ == "__main__":
    server = NinjaRadioCoreServer()
    asyncio.run(server.run())
