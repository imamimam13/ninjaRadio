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
RPTO = b"RPTO"
RPTS = b"RPTS"
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
        self.started_at = time.time()
        self.total_rx_packets = 0
        self.total_tx_packets = 0

        # Connected clients: addr -> dict(dmr_id, callsign, software, state, connected_at, last_seen, last_ping_tx, rx_packets, tx_packets, last_tg, last_slot)
        self.clients: Dict[Tuple[str, int], dict] = {}
        # Pending challenges: addr -> (dmr_id, salt, timestamp)
        self.pending_challenges: Dict[Tuple[str, int], Tuple[int, bytes, float]] = {}

        # Live QSO and Last Heard Tracking
        self.active_stream: Optional[dict] = None
        self.recent_qsos: List[dict] = []
        self.max_recent_qsos = 50

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
                now = time.time()
                self.clients[addr] = {
                    "dmr_id": dmr_id,
                    "callsign": "HOTSPOT",
                    "software": "MMDVMHost",
                    "state": "AUTHENTICATED",
                    "connected_at": now,
                    "last_seen": now,
                    "last_ping_tx": now,
                    "rx_packets": 0,
                    "tx_packets": 0,
                    "last_tg": 0,
                    "last_slot": 2,
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
            software_str = "MMDVMHost"
            if len(config_payload) > 8:
                try:
                    # MMDVMHost typically packs version / radio info
                    extra = config_payload[8:].decode("ascii", errors="ignore").strip()
                    if extra:
                        software_str = extra[:30]
                except Exception:
                    pass

            if addr not in self.clients:
                self._update_client_seen(dmr_id, addr)

            if addr in self.clients:
                self.clients[addr]["callsign"] = callsign or self.clients[addr].get("callsign", "HOTSPOT")
                self.clients[addr]["software"] = software_str
                self.clients[addr]["state"] = "CONNECTED"
                self.clients[addr]["last_seen"] = time.time()
                if "connected_at" not in self.clients[addr]:
                    self.clients[addr]["connected_at"] = time.time()
                logger.info(f"[DMR-Master] ✓ Hotspot {callsign} ({dmr_id}) fully CONNECTED from {addr} [{software_str}]")

            # Send RPTACK acknowledgment
            self.sock.sendto(RPTACK, addr)
            return

        # 3b. RPTO: Repeater / Hotspot Options Block (e.g. TS2_1=510990;)
        elif data.startswith(RPTO) and len(data) >= 8:
            dmr_id = struct.unpack(">I", data[4:8])[0]
            options_payload = data[8:].decode("ascii", errors="ignore").strip()

            if addr not in self.clients:
                self._update_client_seen(dmr_id, addr)

            if addr in self.clients:
                self.clients[addr]["options"] = options_payload
                self.clients[addr]["state"] = "CONNECTED"
                self.clients[addr]["last_seen"] = time.time()
                # Parse talkgroup subscription if present (e.g. TS2_1=510990 or TS1_1=...)
                import re
                m2 = re.search(r"TS2_\d+=(\d+)", options_payload)
                if m2:
                    self.clients[addr]["last_tg"] = int(m2.group(1))
                    self.clients[addr]["last_slot"] = 2
                m1 = re.search(r"TS1_\d+=(\d+)", options_payload)
                if m1 and not m2:
                    self.clients[addr]["last_tg"] = int(m1.group(1))
                    self.clients[addr]["last_slot"] = 1
                logger.info(f"[DMR-Master] ✓ Hotspot {self.clients[addr].get('callsign')} ({dmr_id}) options: {options_payload}")

            # Send RPTACK acknowledgment (CRUCIAL: MMDVMHost transitions to STATE_RUNNING upon receiving this)
            self.sock.sendto(RPTACK, addr)
            return

        # 3c. RPTS: Repeater Status / Short Block
        elif data.startswith(RPTS) and len(data) >= 8:
            dmr_id = struct.unpack(">I", data[4:8])[0]
            self._update_client_seen(dmr_id, addr)
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

            now = time.time()
            self.total_rx_packets += 1

            # Update client activity & packet counts
            if addr in self.clients:
                self.clients[addr]["last_seen"] = now
                self.clients[addr]["rx_packets"] = self.clients[addr].get("rx_packets", 0) + 1
                self.clients[addr]["last_tg"] = dst_tg
                self.clients[addr]["last_slot"] = slot
            else:
                self._update_client_seen(src_id, addr)
                if addr in self.clients:
                    self.clients[addr]["rx_packets"] = self.clients[addr].get("rx_packets", 0) + 1
                    self.clients[addr]["last_tg"] = dst_tg
                    self.clients[addr]["last_slot"] = slot

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
            callsign_str = sender_call or f"ID-{src_id}"

            # Track Active QSO stream & Last Heard list
            if data_type == 0:  # Voice Header
                self.active_stream = {
                    "stream_id": stream_id,
                    "src_id": src_id,
                    "callsign": callsign_str,
                    "dst_tg": dst_tg,
                    "slot": slot,
                    "call_type": call_type,
                    "start_time": now,
                    "last_packet_time": now,
                    "packets": 1
                }
            elif data_type == 1:  # Voice Burst
                if self.active_stream and self.active_stream.get("stream_id") == stream_id:
                    self.active_stream["packets"] = self.active_stream.get("packets", 0) + 1
                    self.active_stream["last_packet_time"] = now
                else:
                    self.active_stream = {
                        "stream_id": stream_id,
                        "src_id": src_id,
                        "callsign": callsign_str,
                        "dst_tg": dst_tg,
                        "slot": slot,
                        "call_type": call_type,
                        "start_time": now,
                        "last_packet_time": now,
                        "packets": 1
                    }
            elif data_type == 2:  # Voice Terminator
                if self.active_stream and self.active_stream.get("stream_id") == stream_id:
                    duration = round(now - self.active_stream.get("start_time", now), 1)
                    packets = self.active_stream.get("packets", 1) + 1
                    qso_entry = {
                        "timestamp": self.active_stream.get("start_time", now),
                        "end_time": now,
                        "callsign": self.active_stream.get("callsign", callsign_str),
                        "src_id": src_id,
                        "dst_tg": dst_tg,
                        "slot": slot,
                        "call_type": call_type,
                        "duration": max(duration, 0.4),
                        "packets": packets,
                        "loss": "0.0%"
                    }
                    self.recent_qsos.insert(0, qso_entry)
                    if len(self.recent_qsos) > self.max_recent_qsos:
                        self.recent_qsos = self.recent_qsos[:self.max_recent_qsos]
                    self.active_stream = None

            # Fire callback to core engine
            if self.on_traffic:
                self.on_traffic({
                    "network": "Local DMR Master (62031)",
                    "host": f"{addr[0]}:{addr[1]}",
                    "src_id": src_id,
                    "dst_tg": dst_tg,
                    "slot": slot,
                    "callsign": callsign_str,
                    "call_type": call_type,
                    "data_type": data_type,
                    "sequence": sequence,
                    "stream_id": stream_id,
                    "payload": payload,
                    "raw_packet": data,
                    "timestamp": now
                })
            return

    def broadcast_dmrd(self, data: bytes, exclude_addr: Optional[Tuple[str, int]] = None):
        """Broadcasts a DMRD voice packet to all connected local hotspots and MMDVM_Bridge."""
        if not self.sock:
            return
        self.total_tx_packets += 1
        for addr, client in list(self.clients.items()):
            if addr != exclude_addr and client.get("state") in ["CONNECTED", "AUTHENTICATED"]:
                try:
                    self.sock.sendto(data, addr)
                    client["tx_packets"] = client.get("tx_packets", 0) + 1
                except Exception:
                    pass

    async def _client_watchdog(self):
        """Maintains active NAT keepalives, flushes ended QSOs, and cleans up inactive hotspots."""
        while self.running:
            await asyncio.sleep(5)
            now = time.time()

            # 1. Close stalled active stream (>2.5s with no burst)
            if self.active_stream and (now - self.active_stream.get("last_packet_time", 0) > 2.5):
                duration = round(now - self.active_stream.get("start_time", now), 1)
                qso_entry = {
                    "timestamp": self.active_stream.get("start_time", now),
                    "end_time": now,
                    "callsign": self.active_stream.get("callsign", "UNKNOWN"),
                    "src_id": self.active_stream.get("src_id", 0),
                    "dst_tg": self.active_stream.get("dst_tg", 0),
                    "slot": self.active_stream.get("slot", 2),
                    "call_type": self.active_stream.get("call_type", "group"),
                    "duration": max(duration, 0.4),
                    "packets": self.active_stream.get("packets", 1),
                    "loss": "0.0%"
                }
                self.recent_qsos.insert(0, qso_entry)
                if len(self.recent_qsos) > self.max_recent_qsos:
                    self.recent_qsos = self.recent_qsos[:self.max_recent_qsos]
                self.active_stream = None

            # 2. Outbound keepalive ping to maintain NAT/firewall pinhole open
            for addr, client in list(self.clients.items()):
                if client.get("state") in ["CONNECTED", "AUTHENTICATED"]:
                    dmr_id = client.get("dmr_id", 0)
                    if dmr_id > 0 and self.sock:
                        try:
                            ping_pkt = MSTPING + struct.pack(">I", dmr_id)
                            self.sock.sendto(ping_pkt, addr)
                        except Exception:
                            pass

            # 3. Watchdog timeout: prune clients inactive for > 90 seconds
            for addr, client in list(self.clients.items()):
                if now - client.get("last_seen", 0) > 90:
                    logger.info(f"[DMR-Master] Hotspot {client.get('callsign')} ({client.get('dmr_id')}) at {addr} timed out (>90s), removing.")
                    self.clients.pop(addr, None)

    def get_server_status(self) -> dict:
        """Returns comprehensive telemetry for HBlink / openDMR style monitoring."""
        now = time.time()
        hotspots_list = []
        for addr, c in self.clients.items():
            conn_time = c.get("connected_at", now)
            up_sec = int(now - conn_time)
            up_h, up_rem = divmod(up_sec, 3600)
            up_m, up_s = divmod(up_rem, 60)
            uptime_str = f"{up_h}h {up_m}m {up_s}s" if up_h > 0 else f"{up_m}m {up_s}s"

            hotspots_list.append({
                "ip": addr[0],
                "port": addr[1],
                "dmr_id": c.get("dmr_id", 0),
                "callsign": c.get("callsign", "HOTSPOT"),
                "software": c.get("software", "MMDVMHost"),
                "state": c.get("state", "CONNECTED"),
                "connected_at": conn_time,
                "uptime": uptime_str,
                "last_seen_seconds_ago": round(now - c.get("last_seen", now), 1),
                "rx_packets": c.get("rx_packets", 0),
                "tx_packets": c.get("tx_packets", 0),
                "last_tg": c.get("last_tg", 0),
                "last_slot": c.get("last_slot", 2),
                "options": c.get("options", "")
            })

        active_tx_info = None
        if self.active_stream:
            active_tx_info = {
                "src_id": self.active_stream.get("src_id"),
                "callsign": self.active_stream.get("callsign"),
                "dst_tg": self.active_stream.get("dst_tg"),
                "slot": self.active_stream.get("slot"),
                "duration": round(now - self.active_stream.get("start_time", now), 1),
                "packets": self.active_stream.get("packets", 0)
            }

        up_server = int(now - self.started_at) if self.running else 0
        s_h, s_rem = divmod(up_server, 3600)
        s_m, s_s = divmod(s_rem, 60)
        server_uptime_str = f"{s_h}h {s_m}m {s_s}s"

        return {
            "online": self.running,
            "port": self.port,
            "protocol": "HomeBrew / MMDVM Protocol v1",
            "server_callsign": self.server_callsign,
            "server_dmr_id": self.server_dmr_id,
            "uptime": server_uptime_str,
            "uptime_seconds": up_server,
            "connected_hotspots_count": len(self.clients),
            "total_rx_packets": self.total_rx_packets,
            "total_tx_packets": self.total_tx_packets,
            "active_transmission": active_tx_info,
            "hotspots": hotspots_list,
            "recent_qsos": self.recent_qsos
        }
