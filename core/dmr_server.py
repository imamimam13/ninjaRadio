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

def get_db_path() -> Optional[str]:
    """Finds ninjaradio.db across Docker container and host filesystem paths."""
    candidates = [
        os.environ.get("NINJARADIO_DB_PATH", ""),
        "/app/data/ninjaradio.db",
        "/home/ubuntu/ninjaRadio/data/ninjaradio.db",
        os.path.join(os.path.dirname(__file__), "../data/ninjaradio.db"),
        os.path.join(os.path.dirname(__file__), "data/ninjaradio.db"),
        "./data/ninjaradio.db",
        "./ninjaradio.db"
    ]
    for p in candidates:
        if p and os.path.exists(p):
            return p
    return None

_CALLSIGN_MEM_CACHE: Dict[int, str] = {}

def lookup_callbook_callsign(dmr_id: int) -> Optional[str]:
    """Resolves amateur callsign for DMR ID dynamically from SQLite Callbook DB or subscriber_ids.csv."""
    if not dmr_id or dmr_id <= 0:
        return None
    if dmr_id in _CALLSIGN_MEM_CACHE:
        return _CALLSIGN_MEM_CACHE[dmr_id]

    # 1. Check SQLite Callbook table
    db_path = get_db_path()
    if db_path:
        try:
            import sqlite3
            with sqlite3.connect(db_path, timeout=1.0) as conn:
                cursor = conn.cursor()
                cursor.execute("SELECT callsign FROM callbook WHERE dmr_id = ?", (dmr_id,))
                row = cursor.fetchone()
                if row and row[0]:
                    call = str(row[0]).strip().upper()
                    _CALLSIGN_MEM_CACHE[dmr_id] = call
                    return call
        except Exception:
            pass

    # 2. Dynamic Fallback: Check /var/lib/dvswitch/subscriber_ids.csv or /app/data/subscriber_ids.csv
    for p in ["/var/lib/dvswitch/subscriber_ids.csv", "/app/data/subscriber_ids.csv"]:
        if os.path.exists(p):
            try:
                with open(p, "r", encoding="utf-8", errors="ignore") as f:
                    for line in f:
                        parts = line.strip().split(",")
                        if len(parts) >= 2 and parts[0].strip().isdigit():
                            if int(parts[0].strip()) == dmr_id:
                                call = parts[1].strip().upper()
                                _CALLSIGN_MEM_CACHE[dmr_id] = call
                                return call
            except Exception:
                pass

    return None

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

        # Native Parrot / Audio Echo Service (TG 9990 & 99990)
        self.parrot_recordings: Dict[Tuple[str, int], list] = {}
        self.parrot_tgs: List[int] = [9990, 99990]

        # Loopback suppression & dynamic talkgroup retargeting for local ingress
        self.active_local_txs: Dict[int, dict] = {}
        # Worker TG -> local MMDVM_Bridge DMRD port mapping (updated by core_engine)
        self.worker_dmr_ports: Dict[int, Tuple[str, int, int]] = {}

    def parse_mmdvm_options(self, client: dict, options_str: str):
        """Parses all TS1 and TS2 talkgroup subscriptions from RPTC/RPTO options payload."""
        if not options_str:
            return
        import re
        ts1_tgs = list(client.get("ts1_tgs") or [])
        ts2_tgs = list(client.get("ts2_tgs") or [])

        # Match patterns like TS1=510,5108; TS1_1=510; TS2_1=1025; TS2=1025,5109
        for m in re.finditer(r'(?i)TS([12])(?:_\d+)?\s*=\s*([0-9,\s]+)', options_str):
            slot_num = int(m.group(1))
            tg_str = m.group(2)
            for part in re.split(r'[,\s]+', tg_str):
                if part.strip().isdigit():
                    tg_val = int(part.strip())
                    if tg_val <= 0:
                        continue
                    if slot_num == 1:
                        if tg_val not in ts1_tgs:
                            ts1_tgs.append(tg_val)
                    else:
                        if tg_val not in ts2_tgs:
                            ts2_tgs.append(tg_val)

        client["ts1_tgs"] = ts1_tgs
        client["ts2_tgs"] = ts2_tgs
        if ts2_tgs:
            client["last_tg"] = ts2_tgs[0]
            client["last_slot"] = 2
        elif ts1_tgs:
            client["last_tg"] = ts1_tgs[0]
            client["last_slot"] = 1

    def is_local_transmission(self, src_id: int) -> bool:
        """Returns True if the transmission was originated locally within the last 2.5s (Loopback suppression)."""
        entry = self.active_local_txs.get(src_id)
        if entry:
            ts = entry["time"] if isinstance(entry, dict) else entry
            if time.time() - ts < 2.5:
                return True
        return False

    def mark_local_transmission(self, src_id: int, tg: int = 0, slot: int = 0):
        now = time.time()
        existing = self.active_local_txs.get(src_id)
        if isinstance(existing, dict) and (now - existing.get("time", 0) < 3.5):
            if not tg and existing.get("tg"):
                tg = existing["tg"]
            if not slot and existing.get("slot"):
                slot = existing["slot"]
        self.active_local_txs[src_id] = {
            "time": now,
            "tg": tg,
            "slot": slot
        }
        for s, item in list(self.active_local_txs.items()):
            t = item["time"] if isinstance(item, dict) else item
            if now - t > 10.0:
                self.active_local_txs.pop(s, None)

    def get_active_local_tx(self, src_id: int) -> Optional[dict]:
        entry = self.active_local_txs.get(src_id)
        if isinstance(entry, dict) and (time.time() - entry.get("time", 0) < 3.5):
            return entry
        return None

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
                    "slots_mode": 3,  # 1=TS1 simplex, 2=TS2 simplex, 3=Duplex, 4=Simplex
                    "ts1_tgs": [],
                    "ts2_tgs": [],
                    "dynamic_tgs": {},  # slot -> tg
                    "slot_locks": {}    # slot -> {"stream_id": int, "tg": int, "until": float}
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
            slots_mode = 3
            options_str = ""

            # Standard HomeBrew RPTC layout:
            # 0..8 callsign, 8..17 rx_freq, 17..26 tx_freq, 26..28 pwr, 28..30 cc,
            # 30..38 lat, 38..47 lon, 47..50 height, 50..70 loc, 70..89 desc, 89..90 slots,
            # 90..214 url, 214..254 soft_id, 254..294 pkg_id, 294.. options
            if len(config_payload) >= 90:
                try:
                    rx_f = config_payload[8:17].decode("ascii", errors="ignore").strip()
                    tx_f = config_payload[17:26].decode("ascii", errors="ignore").strip()
                    slots_ch = config_payload[89:90].decode("ascii", errors="ignore").strip()
                    if slots_ch in ("1", "2", "4"):
                        slots_mode = int(slots_ch)
                    elif rx_f and tx_f and rx_f == tx_f and slots_ch != "3":
                        slots_mode = 2
                except Exception:
                    pass
            if len(config_payload) >= 254:
                try:
                    s_id = config_payload[214:254].decode("ascii", errors="ignore").strip("\x00 ")
                    if s_id:
                        software_str = s_id[:30]
                except Exception:
                    pass
            elif len(config_payload) > 8:
                try:
                    extra = config_payload[8:].decode("ascii", errors="ignore").strip()
                    if extra:
                        software_str = extra[:30]
                except Exception:
                    pass

            if len(config_payload) > 294:
                try:
                    options_str = config_payload[294:].decode("ascii", errors="ignore").strip("\x00 ")
                except Exception:
                    pass
            elif b"TS" in config_payload:
                try:
                    full_ascii = config_payload.decode("ascii", errors="ignore")
                    idx = full_ascii.upper().find("TS")
                    if idx != -1:
                        options_str = full_ascii[idx:].strip("\x00 ")
                except Exception:
                    pass

            if addr not in self.clients:
                self._update_client_seen(dmr_id, addr)

            if addr in self.clients:
                self.clients[addr]["callsign"] = callsign or self.clients[addr].get("callsign", "HOTSPOT")
                self.clients[addr]["software"] = software_str
                self.clients[addr]["slots_mode"] = slots_mode
                self.clients[addr]["state"] = "CONNECTED"
                self.clients[addr]["last_seen"] = time.time()
                if "connected_at" not in self.clients[addr]:
                    self.clients[addr]["connected_at"] = time.time()
                if options_str:
                    self.clients[addr]["options"] = options_str
                    self.parse_mmdvm_options(self.clients[addr], options_str)
                logger.info(
                    f"[DMR-Master] ✓ Hotspot {callsign} ({dmr_id}) CONNECTED from {addr} "
                    f"[{software_str}, slots={slots_mode}, TS1={self.clients[addr].get('ts1_tgs')}, TS2={self.clients[addr].get('ts2_tgs')}]"
                )

            # Send RPTACK acknowledgment
            self.sock.sendto(RPTACK, addr)
            return

        # 3b. RPTO: Repeater / Hotspot Options Block (e.g. TS1_1=510;TS2_1=1025; or TS2=1025,5109)
        elif data.startswith(RPTO) and len(data) >= 8:
            dmr_id = struct.unpack(">I", data[4:8])[0]
            options_payload = data[8:].decode("ascii", errors="ignore").strip("\x00 ")

            if addr not in self.clients:
                self._update_client_seen(dmr_id, addr)

            if addr in self.clients:
                self.clients[addr]["options"] = options_payload
                self.clients[addr]["state"] = "CONNECTED"
                self.clients[addr]["last_seen"] = time.time()
                self.clients[addr]["ts1_tgs"] = []
                self.clients[addr]["ts2_tgs"] = []
                self.parse_mmdvm_options(self.clients[addr], options_payload)
                logger.info(
                    f"[DMR-Master] ✓ Hotspot {self.clients[addr].get('callsign')} ({dmr_id}) RPTO options: "
                    f"{options_payload} -> TS1={self.clients[addr].get('ts1_tgs')}, TS2={self.clients[addr].get('ts2_tgs')}"
                )

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
            rpt_id = struct.unpack(">I", data[11:15])[0]
            bits = data[15]
            slot = 2 if (bits & 0x80) else 1
            call_type = "unit" if (bits & 0x40) else "group"
            data_type = (bits & 0x30) >> 4  # 0=Header, 1=Voice Burst, 2=Voice Terminator, 3=CSBK
            sequence = bits & 0x0F  # 0..5 (Burst A..F)
            stream_id = int.from_bytes(data[16:20], "big")
            payload = data[20:]

            # Retarget packets from local DVSwitch / MMDVM_Bridge (127.0.0.1) to user's intended TG & Slot
            local_tx = self.get_active_local_tx(src_id)
            if local_tx and local_tx.get("tg") and addr[0] == "127.0.0.1":
                intended_tg = int(local_tx["tg"])
                intended_slot = int(local_tx.get("slot") or slot)
                if dst_tg != intended_tg or slot != intended_slot:
                    tg_bytes = struct.pack(">I", intended_tg)[1:]
                    slot_bit = 0x80 if intended_slot == 2 else 0x00
                    new_bits = (bits & 0x7F) | slot_bit
                    data = data[:8] + tg_bytes + data[11:15] + bytes([new_bits]) + data[16:]
                    dst_tg = intended_tg
                    slot = intended_slot
                    bits = new_bits

            now = time.time()
            self.total_rx_packets += 1
            # Preserve intended_tg and intended_slot across all frames of the transmission!
            self.mark_local_transmission(src_id, dst_tg, slot)

            # Update client activity & dynamic TG subscription when a hotspot user transmits
            if addr not in self.clients:
                self._update_client_seen(rpt_id or src_id, addr)

            if addr in self.clients:
                cl = self.clients[addr]
                cl["last_seen"] = now
                cl["rx_packets"] = cl.get("rx_packets", 0) + 1
                if dst_tg == 4000:
                    # TG 4000 Unlink Dynamic Talkgroups on this slot
                    dyn = cl.setdefault("dynamic_tgs", {})
                    dyn.pop(slot, None)
                    cl.setdefault("slot_locks", {}).pop(slot, None)
                    if data_type == 0:
                        logger.info(f"[DMR-Master] 🔌 Hotspot {cl.get('callsign')} ({cl.get('dmr_id')}) dialed TG 4000 -> Unlinked dynamic TG on TS{slot}")
                    return
                elif dst_tg not in getattr(self, "parrot_tgs", [9990, 99990]):
                    cl["last_tg"] = dst_tg
                    cl["last_slot"] = slot
                    if addr[0] != "127.0.0.1":
                        dyn = cl.setdefault("dynamic_tgs", {})
                        if dyn.get(slot) != dst_tg:
                            dyn[slot] = dst_tg
                            logger.info(f"[DMR-Master] 📌 Hotspot {cl.get('callsign')} ({cl.get('dmr_id')}) dynamically linked to TG {dst_tg} on TS{slot}")
                        # Lock this hotspot's slot to its own ongoing TX/RX TG
                        cl.setdefault("slot_locks", {})[slot] = {
                            "stream_id": stream_id,
                            "tg": dst_tg,
                            "until": now + 3.0
                        }

            # Native Parrot (Configured Parrot Talkgroups or Private Calls)
            if dst_tg in getattr(self, "parrot_tgs", [9990, 99990]):
                if data_type == 0:  # Voice Header
                    self.parrot_recordings[addr] = [data]
                    logger.info(f"[DMR-Master] 🦜 Parrot started recording from {addr} (DMR ID: {src_id}, TG: {dst_tg}, Slot: {slot})")
                elif data_type == 1:  # Voice Burst
                    if addr not in self.parrot_recordings:
                        self.parrot_recordings[addr] = []
                    if len(self.parrot_recordings[addr]) < 350:
                        self.parrot_recordings[addr].append(data)
                elif data_type == 2:  # Voice Terminator
                    if addr in self.parrot_recordings:
                        self.parrot_recordings[addr].append(data)
                        bursts = list(self.parrot_recordings.pop(addr, []))
                        if len(bursts) >= 3:
                            logger.info(f"[DMR-Master] 🦜 Parrot received terminator, queued {len(bursts)} frames for playback to {addr}")
                            asyncio.create_task(self._play_parrot_echo(addr, src_id, dst_tg, slot, call_type, bursts))
            else:
                # Broadcast to other connected hotspots and MMDVM_Bridge
                self.broadcast_dmrd(data, exclude_addr=addr)

            # Resolve callsign from connected clients if available
            sender_call = None
            if addr in self.clients:
                sender_call = self.clients[addr].get("callsign")
            if not sender_call or sender_call in ["HOTSPOT", "DVSWITCH"]:
                for c in self.clients.values():
                    if c.get("dmr_id") == src_id and c.get("callsign") not in ["HOTSPOT", "DVSWITCH"]:
                        sender_call = c.get("callsign")
                        break
            if not sender_call or sender_call in ["HOTSPOT", "DVSWITCH"]:
                cb_call = lookup_callbook_callsign(src_id)
                if cb_call:
                    sender_call = cb_call
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
        """
        Broadcasts a DMRD voice packet to connected local hotspots and MMDVM_Bridge.
        Critical features for MMDVMHost / Pi-Star / WPSD + HT compatibility:
        1. Stamps the target client's own 4-byte Repeater DMR ID into bytes 11..15 (required by MMDVMHost!).
        2. Adapts the timeslot bit for Simplex hotspots and local MMDVM_Bridge (Slot 2) so Slot 1 TGs are never missed.
        3. Enforces per-hotspot timeslot stream locking so two concurrent TGs never collide on the same RF slot.
        4. Respects static (RPTC/RPTO) & dynamic (PTT kerchunk) TG subscriptions when configured.
        """
        if not self.sock or len(data) < 20:
            return

        src_id = int.from_bytes(data[5:8], "big")
        dst_tg = int.from_bytes(data[8:11], "big")
        orig_bits = data[15]
        orig_slot = 2 if (orig_bits & 0x80) else 1
        call_type = "unit" if (orig_bits & 0x40) else "group"
        data_type = (orig_bits & 0x30) >> 4
        stream_id = int.from_bytes(data[16:20], "big")
        now = time.time()

        self.total_tx_packets += 1

        for addr, client in list(self.clients.items()):
            if addr == exclude_addr or client.get("state") not in ["CONNECTED", "AUTHENTICATED"]:
                continue

            client_dmr_id = client.get("dmr_id", 0)
            if client_dmr_id and client_dmr_id == src_id and addr[0] != "127.0.0.1" and addr == exclude_addr:
                # Do not echo a hotspot user's own transmission back to their own hotspot
                continue

            is_local_bridge = (addr[0] == "127.0.0.1")
            target_slot = orig_slot

            if is_local_bridge:
                # Local MMDVM_Bridge (DVSwitch.ini) on 127.0.0.1 must ALWAYS receive DMR voice on Slot 2
                # so Analog_Bridge & Dashboard Web Audio stream flawlessly!
                target_slot = 2
            else:
                ts1_tgs = client.get("ts1_tgs") or []
                ts2_tgs = client.get("ts2_tgs") or []
                dyn_tgs = client.get("dynamic_tgs") or {}
                slots_mode = client.get("slots_mode", 3)

                # Determine if this hotspot has explicit static subscriptions via RPTO/RPTC
                has_static = bool(ts1_tgs or ts2_tgs)
                if has_static and call_type == "group":
                    in_ts1 = (dst_tg in ts1_tgs) or (dyn_tgs.get(1) == dst_tg)
                    in_ts2 = (dst_tg in ts2_tgs) or (dyn_tgs.get(2) == dst_tg)
                    is_last = (client.get("last_tg") == dst_tg)
                    if not (in_ts1 or in_ts2 or is_last):
                        continue
                    if in_ts2 and not in_ts1:
                        target_slot = 2
                    elif in_ts1 and not in_ts2:
                        target_slot = 1

                # Adapt slot for Simplex MMDVM hotspots (slots_mode 1, 2, or 4)
                if slots_mode == 1:
                    target_slot = 1
                elif slots_mode in (2, 4):
                    target_slot = 2
                elif client.get("last_tg") == dst_tg and client.get("last_slot") in (1, 2):
                    # Deliver on the exact slot the HT operator used to key up this TG
                    target_slot = client["last_slot"]

                # Per-hotspot timeslot stream lock to prevent TDMA frame collision on HT
                slot_locks = client.setdefault("slot_locks", {})
                lock = slot_locks.get(target_slot)
                if lock and now < lock.get("until", 0):
                    if lock.get("stream_id") != stream_id and lock.get("tg") != dst_tg:
                        # Another QSO is actively playing on this hotspot's slot; do not corrupt it
                        continue

                if data_type == 2:  # Voice Terminator releases lock shortly after
                    slot_locks[target_slot] = {
                        "stream_id": stream_id,
                        "tg": dst_tg,
                        "until": now + 0.4
                    }
                else:
                    slot_locks[target_slot] = {
                        "stream_id": stream_id,
                        "tg": dst_tg,
                        "until": now + 2.0
                    }

            # Stamp target client's own 4-byte Repeater ID into bytes 11..15 and set target_slot bit
            rpt_bytes = struct.pack(">I", client_dmr_id if client_dmr_id else self.server_dmr_id)
            slot_bit = 0x80 if target_slot == 2 else 0x00
            new_bits = (orig_bits & 0x7F) | slot_bit
            out_pkt = data[:11] + rpt_bytes + bytes([new_bits]) + data[16:]

            try:
                self.sock.sendto(out_pkt, addr)
                client["tx_packets"] = client.get("tx_packets", 0) + 1
            except Exception:
                pass

    async def _play_parrot_echo(self, target_addr: Tuple[str, int], caller_id: int, orig_tg: int, slot: int, call_type: str, recorded_packets: list):
        """Plays back recorded DMR voice burst to the hotspot on TG 9990 after turnaround delay."""
        try:
            logger.info(f"[DMR-Master] 🦜 Parrot buffering complete ({len(recorded_packets)} frames from ID {caller_id}). Waiting 1.2s turnaround...")
            await asyncio.sleep(1.2)

            dest_addr = target_addr
            if dest_addr not in self.clients:
                for a, cl in list(self.clients.items()):
                    if cl.get("dmr_id") == caller_id:
                        dest_addr = a
                        break

            target_client = self.clients.get(dest_addr, {})
            target_rpt_id = target_client.get("dmr_id") or caller_id or self.server_dmr_id

            logger.info(f"[DMR-Master] 🦜 Echoing {len(recorded_packets)} frames back to {dest_addr} (Caller: {caller_id}, Repeater: {target_rpt_id}, Slot: {slot})")

            new_stream_id = int(time.time()) & 0xFFFFFFFF
            out_seq = 0

            if call_type == "unit":
                echo_dst = caller_id
                echo_call_type_bit = 0x40
            else:
                echo_dst = orig_tg
                echo_call_type_bit = 0x00

            slot_bit = 0x80 if slot == 2 else 0x00
            src_bytes = orig_tg.to_bytes(3, "big")  # Parrot is sender (ID 9990 or 99990)
            dst_bytes = echo_dst.to_bytes(3, "big")
            repeater_bytes = struct.pack(">I", int(target_rpt_id))

            for pkt in recorded_packets:
                if not self.running or not self.sock:
                    break
                if len(pkt) < 20:
                    continue

                out_seq = (out_seq + 1) % 256
                orig_bits = pkt[15]
                data_type_seq = orig_bits & 0x3F  # preserve data_type and burst sequence
                new_bits = slot_bit | echo_call_type_bit | data_type_seq

                payload = pkt[20:]
                echo_pkt = DMRD + bytes([out_seq]) + src_bytes + dst_bytes + repeater_bytes + bytes([new_bits]) + struct.pack(">I", new_stream_id) + payload

                try:
                    self.sock.sendto(echo_pkt, dest_addr)
                    self.total_tx_packets += 1
                except Exception:
                    pass

                await asyncio.sleep(0.06)

            logger.info(f"[DMR-Master] 🦜 Parrot echo playback finished to {caller_id} at {dest_addr}")
        except Exception as e:
            logger.error(f"[DMR-Master] Parrot playback error: {e}")

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
                "ts1_tgs": c.get("ts1_tgs", []),
                "ts2_tgs": c.get("ts2_tgs", []),
                "dynamic_tgs": c.get("dynamic_tgs", {}),
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
