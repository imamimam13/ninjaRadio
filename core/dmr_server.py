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
RPTPING = b"RPTPING"
MSTPING = b"MSTPING"
MSTP = b"MSTP"
MSTPONG = b"MSTPONG"
RPTPONG = b"RPTPONG"
MSTACK = b"MSTACK"
MSTNAK = b"MSTNAK"
RPTCL = b"RPTCL"
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

        # Connected clients: addr -> dict(dmr_id, callsign, state, last_seen, last_ping_tx)
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

    def _remove_client_by_id(self, dmr_id: int, current_addr: Tuple[str, int]):
        """Removes previous connection entries for the same DMR ID upon new login or address change."""
        for a, c in list(self.clients.items()):
            if a != current_addr and c.get("dmr_id") == dmr_id:
                logger.info(f"[DMR-Master] 🔄 Pruning previous session for DMR ID {dmr_id} at {a}")
                self.clients.pop(a, None)

    def _update_client_seen(self, dmr_id: int, addr: Tuple[str, int]):
        """Updates last_seen and dynamically shifts client address if NAT port remapped."""
        now = time.time()
        if addr in self.clients:
            self.clients[addr]["last_seen"] = now
            if not self.clients[addr].get("dmr_id"):
                self.clients[addr]["dmr_id"] = dmr_id
        else:
            # Check if this DMR ID was registered under a different address/port
            for old_addr, client in list(self.clients.items()):
                if client.get("dmr_id") == dmr_id:
                    client["last_seen"] = now
                    self.clients[addr] = client
                    if old_addr != addr:
                        logger.info(f"[DMR-Master] 🔄 Shifted address for {client.get('callsign')} ({dmr_id}): {old_addr} -> {addr}")
                        self.clients.pop(old_addr, None)
                    return

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
        # 1. RPTL: Hotspot/Repeater Login Request (RPTL + 4-byte DMR ID)
        if data.startswith(RPTL) and len(data) >= 8:
            dmr_id = struct.unpack(">I", data[4:8])[0]
            salt = os.urandom(4)
            self.pending_challenges[addr] = (dmr_id, salt, time.time())
            self._remove_client_by_id(dmr_id, addr)

            # Send RPTACK + salt challenge
            ack_packet = RPTACK + salt
            self.sock.sendto(ack_packet, addr)
            logger.info(f"[DMR-Master] Inbound RPTL login from {addr} (DMR ID: {dmr_id}), sent challenge salt {salt.hex()}")
            return

        # 2. RPTK: Client Challenge Response Authentication (RPTK + 4-byte ID + 32-byte SHA256)
        elif data.startswith(RPTK) and len(data) >= 40:
            dmr_id = struct.unpack(">I", data[4:8])[0]
            client_hash = data[8:40]

            challenge = self.pending_challenges.get(addr)
            if not challenge or challenge[0] != dmr_id:
                # Fallback search by dmr_id in pending challenges
                for a, ch in list(self.pending_challenges.items()):
                    if ch[0] == dmr_id and time.time() - ch[2] < 30:
                        challenge = ch
                        break

            if not challenge:
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
                    "last_ping_tx": time.time(),
                    "ts1_tgs": [],
                    "ts2_tgs": []
                }
                self.pending_challenges.pop(addr, None)
                self.sock.sendto(RPTACK, addr)
                logger.info(f"[DMR-Master] ✓ Client authenticated from {addr} (DMR ID: {dmr_id})")
            else:
                logger.warning(f"[DMR-Master] ❌ Auth failed from {addr} (DMR ID: {dmr_id}) - Invalid password")
                self.sock.sendto(MSTNAK, addr)
            return

        # 3. RPTC: Client Configuration Block
        elif data.startswith(RPTC) and len(data) >= 8:
            dmr_id = struct.unpack(">I", data[4:8])[0]
            config_payload = data[8:]
            callsign = config_payload[:8].decode("utf-8", errors="ignore").strip()

            if addr not in self.clients:
                self._update_client_seen(dmr_id, addr)

            if addr in self.clients:
                self.clients[addr]["callsign"] = callsign or self.clients[addr]["callsign"]
                self.clients[addr]["state"] = "CONNECTED"
                self.clients[addr]["last_seen"] = time.time()
                logger.info(f"[DMR-Master] ✓ Hotspot {callsign} ({dmr_id}) fully CONNECTED from {addr}")

            # Send RPTACK acknowledgment
            self.sock.sendto(RPTACK, addr)
            return

        # 4. Keepalive Pings (RPTPING / RPTP / MSTPING / RPTPONG)
        # 4a. RPTPING (7-byte header + 4-byte DMR ID = 11 bytes): Standard MMDVMHost / MMDVM_Bridge / Pi-Star
        elif data.startswith(RPTPING) and len(data) >= 11:
            peer_id_bytes = data[7:11]
            dmr_id = struct.unpack(">I", peer_id_bytes)[0]
            self._update_client_seen(dmr_id, addr)
            # HomeBrew standard Pong: MSTPONG + 4-byte client DMR ID
            pong = MSTPONG + peer_id_bytes
            self.sock.sendto(pong, addr)
            return

        # 4b. RPTP (4-byte header + 4-byte DMR ID = 8 bytes): Legacy HomeBrew clients
        elif data.startswith(RPTP) and len(data) >= 8:
            peer_id_bytes = data[4:8]
            dmr_id = struct.unpack(">I", peer_id_bytes)[0]
            self._update_client_seen(dmr_id, addr)
            self.sock.sendto(MSTPONG + peer_id_bytes, addr)
            self.sock.sendto(MSTP + peer_id_bytes, addr)
            return

        # 4c. MSTPING from peer (7-byte header + 4-byte DMR ID = 11 bytes)
        elif data.startswith(MSTPING) and len(data) >= 11:
            peer_id_bytes = data[7:11]
            dmr_id = struct.unpack(">I", peer_id_bytes)[0]
            self._update_client_seen(dmr_id, addr)
            self.sock.sendto(MSTPONG + peer_id_bytes, addr)
            return

        # 4d. RPTPONG from peer (response to master-initiated keepalive)
        elif data.startswith(RPTPONG) and len(data) >= 11:
            peer_id_bytes = data[7:11]
            dmr_id = struct.unpack(">I", peer_id_bytes)[0]
            self._update_client_seen(dmr_id, addr)
            return

        # 5. RPTCL: Client Logout / Disconnect cleanly
        elif data.startswith(RPTCL) and len(data) >= 8:
            dmr_id = struct.unpack(">I", data[4:8])[0]
            logger.info(f"[DMR-Master] Hotspot {dmr_id} disconnected cleanly (RPTCL) from {addr}")
            self.clients.pop(addr, None)
            self.sock.sendto(RPTACK, addr)
            return

        # 6. DMRD: Inbound DMR Voice / Data Burst from Hotspot
        elif data.startswith(DMRD) and len(data) >= 20:
            seq = data[4]
            src_id = int.from_bytes(data[5:8], "big")
            dst_tg = int.from_bytes(data[8:11], "big")
            bits = data[15]
            slot = 2 if (bits & 0x80) else 1
            call_type = "unit" if (bits & 0x40) else "group"
            data_type = (bits & 0x30) >> 4  # 0=Header, 1=Voice Burst, 2=Voice Terminator, 3=CSBK
            sequence = bits & 0x0F  # 0..5 (Burst A..F)
            stream_id = int.from_bytes(data[16:20], "big")
            payload = data[20:]

            # Update client activity
            if addr in self.clients:
                self.clients[addr]["last_seen"] = time.time()
            else:
                self._update_client_seen(src_id, addr)

            # Broadcast to other connected hotspots and MMDVM_Bridge
            self.broadcast_dmrd(data, exclude_addr=addr)

            # Resolve callsign from connected clients if available
            sender_call = None
            if addr in self.clients:
                sender_call = self.clients[addr].get("callsign")
            if not sender_call or sender_call == "HOTSPOT":
                for c in self.clients.values():
                    if c.get("dmr_id") == src_id:
                        sender_call = c.get("callsign")
                        break

            # Fire callback to core engine
            if self.on_traffic:
                self.on_traffic({
                    "network": "Local DMR Master (62031)",
                    "host": f"{addr[0]}:{addr[1]}",
                    "src_id": src_id,
                    "dst_tg": dst_tg,
                    "slot": slot,
                    "callsign": sender_call or f"ID-{src_id}",
                    "call_type": call_type,
                    "data_type": data_type,
                    "sequence": sequence,
                    "stream_id": stream_id,
                    "payload": payload,
                    "raw_packet": data,
                    "timestamp": time.time()
                })
            return

    def broadcast_dmrd(self, data: bytes, exclude_addr: Optional[Tuple[str, int]] = None):
        """Broadcasts a DMRD voice packet to all connected local hotspots and MMDVM_Bridge."""
        if not self.sock:
            return
        for addr, client in list(self.clients.items()):
            if addr != exclude_addr and client.get("state") in ["CONNECTED", "AUTHENTICATED"]:
                try:
                    self.sock.sendto(data, addr)
                except Exception:
                    pass

    async def _client_watchdog(self):
        """Maintains active NAT keepalives and cleans up inactive hotspots."""
        while self.running:
            await asyncio.sleep(10)
            now = time.time()

            # 1. Outbound keepalive ping to maintain NAT/firewall pinhole open
            for addr, client in list(self.clients.items()):
                if client.get("state") in ["CONNECTED", "AUTHENTICATED"]:
                    dmr_id = client.get("dmr_id", 0)
                    if dmr_id > 0 and self.sock:
                        try:
                            ping_pkt = MSTPING + struct.pack(">I", dmr_id)
                            self.sock.sendto(ping_pkt, addr)
                        except Exception:
                            pass

            # 2. Watchdog timeout: prune clients inactive for > 90 seconds
            for addr, client in list(self.clients.items()):
                if now - client.get("last_seen", 0) > 90:
                    logger.info(f"[DMR-Master] Hotspot {client.get('callsign')} ({client.get('dmr_id')}) at {addr} timed out (>90s), removing.")
                    self.clients.pop(addr, None)
