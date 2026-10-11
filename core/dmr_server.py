import asyncio
import socket
import struct
import hashlib
import logging
import time
import os
from typing import Callable, Optional, Dict, List, Tuple

logger = logging.getLogger("DMR-MasterServer")

RPTL = b"RPTL"
RPTACK = b"RPTACK"
RPTK = b"RPTK"
RPTC = b"RPTC"
RPTP = b"RPTP"
MSTPING = b"MSTPING"
MSTP = b"MSTP"
MSTACK = b"MSTACK"
MSTNAK = b"MSTNAK"
DMRD = b"DMRD"

class HomeBrewMasterServer:
    """
    Native DMR HomeBrew / MMDVM Protocol Master Server.
    Listens for incoming connections from Pi-Star, MMDVMHost, OpenGD77,
    and mobile DMR clients on port 62031/udp.
    """

    def __init__(
        self,
        port: int = 62031,
        password: str = "ninjaradio_pass",
        server_dmr_id: int = 5109999,
        server_callsign: str = "N1NJA",
        on_traffic: Optional[Callable] = None
    ):
        self.port = port
        self.password = password
        self.server_dmr_id = server_dmr_id
        self.server_callsign = server_callsign
        self.on_traffic = on_traffic

        self.sock: Optional[socket.socket] = None
        self.running = False
        self.server_task: Optional[asyncio.Task] = None
        self.watchdog_task: Optional[asyncio.Task] = None

        # Connected clients: addr -> dict(dmr_id, callsign, salt, state, last_seen, ts1_tgs, ts2_tgs)
        self.clients: Dict[Tuple[str, int], dict] = {}
        # Pending challenges: addr -> (dmr_id, salt, timestamp)
        self.pending_challenges: Dict[Tuple[str, int], Tuple[int, bytes, float]] = {}

    async def start(self):
        self.running = True
        loop = asyncio.get_running_loop()

        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("0.0.0.0", self.port))
        self.sock.setblocking(False)

        logger.info(f"📻 DMR Master Server listening for incoming hotspots on UDP port {self.port}")
        self.server_task = asyncio.create_task(self._listen_loop())
        self.watchdog_task = asyncio.create_task(self._client_watchdog())

    async def stop(self):
        self.running = False
        if self.watchdog_task:
            self.watchdog_task.cancel()
        if self.server_task:
            self.server_task.cancel()
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass
        self.clients.clear()
        self.pending_challenges.clear()

    async def _listen_loop(self):
        loop = asyncio.get_running_loop()
        while self.running:
            try:
                data, addr = await loop.sock_recvfrom(self.sock, 2048)
                if data and len(data) >= 4:
                    await self._handle_incoming(data, addr)
            except (asyncio.CancelledError, KeyboardInterrupt):
                break
            except Exception as e:
                await asyncio.sleep(0.01)

    async def _handle_incoming(self, data: bytes, addr: Tuple[str, int]):
        header = data[:4]

        # 1. RPTL: Hotspot/Repeater Login Request
        if header == RPTL and len(data) >= 8:
            dmr_id = struct.unpack(">I", data[4:8])[0]
            salt = os.urandom(4)
            self.pending_challenges[addr] = (dmr_id, salt, time.time())
            
            # Send RPTACK + salt challenge
            ack_packet = RPTACK + salt
            self.sock.sendto(ack_packet, addr)
            logger.info(f"[DMR-Master] Inbound RPTL login from {addr} (DMR ID: {dmr_id}), sent challenge salt {salt.hex()}")

        # 2. RPTK: Client Challenge Response Authentication
        elif header == RPTK and len(data) >= 40:
            dmr_id = struct.unpack(">I", data[4:8])[0]
            client_hash = data[8:40]

            challenge = self.pending_challenges.get(addr)
            if not challenge or challenge[0] != dmr_id:
                self.sock.sendto(MSTNAK, addr)
                return

            _, salt, _ = challenge
            expected_hash = hashlib.sha256(salt + self.password.encode("utf-8")).digest()

            # Accept if hash matches OR if password is blank
            if client_hash == expected_hash or not self.password:
                self.clients[addr] = {
                    "dmr_id": dmr_id,
                    "callsign": "HOTSPOT",
                    "state": "AUTHENTICATED",
                    "last_seen": time.time(),
                    "ts1_tgs": [],
                    "ts2_tgs": []
                }
                self.pending_challenges.pop(addr, None)
                self.sock.sendto(RPTACK, addr)
                logger.info(f"[DMR-Master] ✓ Client authenticated from {addr} (DMR ID: {dmr_id})")
            else:
                logger.warning(f"[DMR-Master] ❌ Auth failed from {addr} (DMR ID: {dmr_id}) - Invalid password")
                self.sock.sendto(MSTNAK, addr)

        # 3. RPTC: Client Configuration
        elif header == RPTC and len(data) >= 8:
            dmr_id = struct.unpack(">I", data[4:8])[0]
            config_payload = data[8:]
            callsign = config_payload[:8].decode("utf-8", errors="ignore").strip()

            if addr in self.clients:
                self.clients[addr]["callsign"] = callsign or self.clients[addr]["callsign"]
                self.clients[addr]["state"] = "CONNECTED"
                self.clients[addr]["last_seen"] = time.time()
                logger.info(f"[DMR-Master] ✓ Hotspot {callsign} ({dmr_id}) fully CONNECTED from {addr}")

            # Send RPTACK acknowledgment
            self.sock.sendto(RPTACK, addr)

        # 4. RPTP: Client Keepalive Ping
        elif header == RPTP and len(data) >= 8:
            dmr_id = struct.unpack(">I", data[4:8])[0]
            if addr in self.clients:
                self.clients[addr]["last_seen"] = time.time()
            # Send MSTP pong response
            pong = MSTP + struct.pack(">I", self.server_dmr_id)
            self.sock.sendto(pong, addr)

        # 5. DMRD: Inbound DMR Voice Burst from Hotspot
        elif header == DMRD and len(data) >= 20:
            if addr in self.clients:
                self.clients[addr]["last_seen"] = time.time()

            seq = data[4]
            src_id = int.from_bytes(data[5:8], "big")
            dst_tg = int.from_bytes(data[8:11], "big")
            bits = data[15]
            slot = 2 if (bits & 0x80) else 1
            call_type = "unit" if (bits & 0x40) else "group"
            dtype_vseq = bits & 0x0F
            stream_id = int.from_bytes(data[16:20], "big")
            payload = data[20:]

            # Broadcast to other local hotspots on this master
            self.broadcast_dmrd(data, exclude_addr=addr)

            # Fire callback to core engine
            if self.on_traffic:
                self.on_traffic({
                    "network": "Local DMR Master (62031)",
                    "host": f"{addr[0]}:{addr[1]}",
                    "src_id": src_id,
                    "dst_tg": dst_tg,
                    "slot": slot,
                    "call_type": call_type,
                    "dtype_vseq": dtype_vseq,
                    "stream_id": stream_id,
                    "payload": payload,
                    "timestamp": time.time()
                })

    def broadcast_dmrd(self, data: bytes, exclude_addr: Optional[Tuple[str, int]] = None):
        """Broadcasts a DMRD voice packet to all connected local hotspots."""
        if not self.sock:
            return
        for addr, client in list(self.clients.items()):
            if addr != exclude_addr and client.get("state") == "CONNECTED":
                try:
                    self.sock.sendto(data, addr)
                except Exception:
                    pass

    async def _client_watchdog(self):
        """Cleans up inactive hotspots that haven't sent keepalive pings in 60s."""
        while self.running:
            await asyncio.sleep(15)
            now = time.time()
            for addr, client in list(self.clients.items()):
                if now - client.get("last_seen", 0) > 60:
                    logger.info(f"[DMR-Master] Hotspot {client.get('callsign')} ({addr}) timed out, removing.")
                    self.clients.pop(addr, None)
