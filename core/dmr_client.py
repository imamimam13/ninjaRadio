import asyncio
import socket
import struct
import hashlib
import hmac
import logging
import time
import os
from typing import Callable, Optional, Dict, List

logger = logging.getLogger("DMR-Client")

RPTL = b"RPTL"
RPTACK = b"RPTACK"
RPTK = b"RPTK"
RPTC = b"RPTC"
RPTP = b"RPTP"
RPTPING = b"RPTPING"
RPTCL = b"RPTCL"
MSTPING = b"MSTPING"
MSTP = b"MSTP"
MSTPONG = b"MSTPONG"
MSTACK = b"MSTACK"
MSTNAK = b"MSTNAK"
DMRD = b"DMRD"

def format_freq_hz_9(val) -> bytes:
    """Formats a frequency (either in MHz like 438.8000 or Hz like 438800000) into 9-byte ASCII string."""
    try:
        s = str(val).strip().replace(" ", "").replace("MHz", "").replace("mhz", "")
        if "." in s:
            hz = int(float(s) * 1_000_000)
        else:
            hz = int(s)
            if hz < 1_000_000:
                hz = hz * 1_000_000
        return f"{hz:09d}"[:9].encode("ascii")
    except Exception:
        return b"438800000"

def format_tx_pwr(val) -> bytes:
    try:
        p = int(str(val).strip().replace("W", "").replace("w", ""))
        return f"{p:02d}"[:2].encode("ascii")
    except Exception:
        return b"25"

def format_color_code(val) -> bytes:
    try:
        c = int(str(val).strip())
        return f"{c:02d}"[:2].encode("ascii")
    except Exception:
        return b"01"

class HomeBrewClient:
    """
    Native HomeBrew / MMDVM Repeater Protocol Client.
    Connects actively to configured DMR Master servers.
    Performs handshake (RPTL -> RPTK -> RPTC), periodic keepalive (RPTP),
    and bidirectional DMR voice/data streaming (DMRD).
    """

    def __init__(
        self,
        name: str,
        host: str,
        port: int,
        dmr_id: int,
        callsign: str,
        password: str = "",
        subscribed_tgs: str = "",
        rx_freq: str = "438.8000",
        tx_freq: str = "438.8000",
        color_code: int = 1,
        tx_pwr: int = 25,
        on_traffic: Optional[Callable] = None,
        on_status_change: Optional[Callable] = None
    ):
        self.name = name
        self.host = host
        self.port = int(port)
        self.dmr_id = int(dmr_id)
        self.callsign = callsign.upper().strip()
        self.password = password.strip()
        self.rx_freq = str(rx_freq).strip()
        self.tx_freq = str(tx_freq).strip()
        self.color_code = int(color_code)
        self.tx_pwr = int(tx_pwr)
        self.raw_tgs_str = str(subscribed_tgs).strip()
        self.ts1_tgs: List[int] = []
        self.ts2_tgs: List[int] = []

        # Parse explicit Timeslot notation (e.g. "TS1;1023,TS2;1025", "TS1=1023;TS2=1025", "TS1_1=1023;TS2_1=1025", "Options=TS1_1=1023;TS2_1=1025;")
        import re
        ts_matches = re.findall(r'(?i)TS([12])(?:_\d+)?\s*[=:;_]\s*([0-9, ]+)', self.raw_tgs_str)
        if ts_matches:
            for ts_num, tgs in ts_matches:
                for tg_part in re.split(r'[,; ]+', tgs):
                    if tg_part.strip().isdigit():
                        val = int(tg_part.strip())
                        if ts_num == "1":
                            if val not in self.ts1_tgs:
                                self.ts1_tgs.append(val)
                        else:
                            if val not in self.ts2_tgs:
                                self.ts2_tgs.append(val)
        else:
            # Simple list: first TG goes to TS1, second to TS2
            parts = [int(tg.strip()) for tg in re.split(r'[,; ]+', self.raw_tgs_str) if tg.strip().isdigit()]
            if len(parts) == 1:
                self.ts1_tgs.append(parts[0])
            elif len(parts) >= 2:
                self.ts1_tgs.append(parts[0])
                self.ts2_tgs.extend(parts[1:])

        self.subscribed_tgs = list(dict.fromkeys(self.ts1_tgs + self.ts2_tgs))
        logger.info(f"[{self.name}] Configured Talkgroups: TS1={self.ts1_tgs} | TS2={self.ts2_tgs}")
        
        self.on_traffic = on_traffic
        self.on_status_change = on_status_change

        self.state = "DISCONNECTED" # DISCONNECTED, LOGIN_SENT, KEY_SENT, CONNECTED
        self.sock: Optional[socket.socket] = None
        self.target_addr: Optional[tuple] = None
        self.running = False
        self.worker_task: Optional[asyncio.Task] = None
        self.ping_task: Optional[asyncio.Task] = None
        self.stream_seq = 0
        self.last_ping_rx = 0
        self.packets_rx = 0
        self.packets_tx = 0

    def get_status_info(self) -> Dict:
        return {
            "name": self.name,
            "host": self.host,
            "port": self.port,
            "dmr_id": self.dmr_id,
            "callsign": self.callsign,
            "state": self.state,
            "ts1_tgs": self.ts1_tgs,
            "ts2_tgs": self.ts2_tgs,
            "subscribed_tgs": self.subscribed_tgs,
            "rx_freq": self.rx_freq,
            "tx_freq": self.tx_freq,
            "color_code": self.color_code,
            "tx_pwr": self.tx_pwr,
            "packets_rx": self.packets_rx,
            "packets_tx": self.packets_tx,
            "last_ping_rx": self.last_ping_rx,
        }

    def _set_state(self, new_state: str):
        if self.state != new_state:
            self.state = new_state
            logger.info(f"[{self.name}] State transition: {new_state} -> {self.host}:{self.port}")
            if self.on_status_change:
                self.on_status_change(self.name, new_state)

    async def start(self):
        self.running = True
        self.worker_task = asyncio.create_task(self._run_loop())

    async def stop(self):
        self.running = False
        if self.ping_task:
            self.ping_task.cancel()
        if self.worker_task:
            self.worker_task.cancel()
        if self.sock and self.target_addr:
            try:
                # Send graceful RPTCL de-registration so master immediately frees the DMR ID session
                dereg = RPTCL + struct.pack(">I", self.dmr_id)
                self.sock.sendto(dereg, self.target_addr)
                logger.info(f"[{self.name}] Sent RPTCL logout to {self.target_addr}")
            except Exception:
                pass
            try:
                self.sock.close()
            except Exception:
                pass
        self._set_state("DISCONNECTED")

    async def _run_loop(self):
        while self.running:
            try:
                # 1. Resolve hostname to IP
                self._set_state("RESOLVING")
                loop = asyncio.get_running_loop()
                addr_info = await loop.getaddrinfo(self.host, self.port, proto=socket.IPPROTO_UDP)
                if not addr_info:
                    raise Exception(f"Cannot resolve hostname {self.host}")
                self.target_addr = addr_info[0][4]

                # 2. Setup UDP socket
                self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                try:
                    self.sock.bind(("0.0.0.0", 0))
                except Exception as b_err:
                    logger.debug(f"[{self.name}] Socket bind note: {b_err}")
                self.sock.setblocking(False)
                
                # 3. Send Login RPTL
                self._set_state("LOGGING_IN")
                await self._send_rptl()

                # Start ping worker
                if self.ping_task:
                    self.ping_task.cancel()
                self.ping_task = asyncio.create_task(self._ping_worker())

                # 4. Ingress packet receive loop
                while self.running:
                    try:
                        data, addr = await loop.sock_recvfrom(self.sock, 2048)
                        if data and len(data) >= 4:
                            self.packets_rx += 1
                            await self._handle_packet(data, addr)
                    except (asyncio.CancelledError, KeyboardInterrupt):
                        raise
                    except Exception as e:
                        await asyncio.sleep(0.01)

            except asyncio.CancelledError:
                break
            except Exception as err:
                logger.warning(f"[{self.name}] Connection error to {self.host}:{self.port} - {err}. Retrying in 5s...")
                self._set_state("RETRYING")
                await asyncio.sleep(5)

    async def _send_rptl(self):
        """Sends initial Repeater Login request (RPTL + DMR ID)."""
        packet = RPTL + struct.pack(">I", self.dmr_id)
        self.sock.sendto(packet, self.target_addr)
        self.packets_tx += 1
        self._set_state("LOGIN_SENT")
        logger.info(f"[{self.name}] Sent RPTL login request to {self.target_addr} (DMR ID: {self.dmr_id})")

    async def _send_rptk(self, salt: bytes):
        """Sends challenge response authentication (RPTK + DMR ID + SHA256(salt + password))."""
        to_hash = salt + self.password.encode("utf-8")
        auth_hash = hashlib.sha256(to_hash).digest()
        packet = RPTK + struct.pack(">I", self.dmr_id) + auth_hash
        self.sock.sendto(packet, self.target_addr)
        self.packets_tx += 1
        self._set_state("KEY_SENT")
        logger.info(f"[{self.name}] Sent RPTK auth hash response to {self.target_addr}")

    async def _send_rptc(self):
        """Sends repeater configuration and talkgroup subscriptions (RPTC).
        Follows standard HomeBrew DMR / MMDVM protocol byte lengths:
        - callsign: 8 bytes
        - rx_freq: 9 bytes
        - tx_freq: 9 bytes
        - tx_pwr: 2 bytes
        - color_code: 2 bytes
        - lat: 8 bytes
        - lon: 9 bytes
        - height: 3 bytes
        - location: 20 bytes
        - desc: 19 bytes
        - slots: 1 byte (b"3" for duplex TS1+TS2, b"2" or b"1")
        - url: 124 bytes
        - soft_id: 40 bytes
        - pkg_id: 40 bytes
        - options: variable (e.g. OPTIONS:TS1_1=...;TS2_1=...;)
        """
        callsign_bytes = self.callsign.ljust(8)[:8].encode("utf-8")
        rx_freq = format_freq_hz_9(self.rx_freq)
        tx_freq = format_freq_hz_9(self.tx_freq)
        tx_pwr = format_tx_pwr(self.tx_pwr)
        color_code = format_color_code(self.color_code)
        lat = b"-06.2088"                           # 8 bytes
        lon = b"+106.8450"                          # 9 bytes (fixed 1-byte shift)
        height = b"050"                             # 3 bytes
        location = "Jakarta, ID".ljust(20)[:20].encode("utf-8")     # 20 bytes
        desc = "NinjaRadio".ljust(19)[:19].encode("utf-8")          # 19 bytes
        slots = b"3"                                                # 1 byte (Both Slots 1 & 2 enabled)
        url = "https://ninjaradio.id".ljust(124)[:124].encode("utf-8") # 124 bytes
        soft_id = "MMDVM_Bridge".ljust(40)[:40].encode("utf-8")     # 40 bytes (standard MMDVM identifier)
        pkg_id = "MMDVM".ljust(40)[:40].encode("utf-8")             # 40 bytes (standard MMDVM repeater package)

        opt_parts = []
        for i, tg in enumerate(self.ts1_tgs, start=1):
            opt_parts.append(f"TS1_{i}={tg}")
            opt_parts.append(f"TS1={tg}")
        for i, tg in enumerate(self.ts2_tgs, start=1):
            opt_parts.append(f"TS2_{i}={tg}")
            opt_parts.append(f"TS2={tg}")

        tg_options = ";".join(opt_parts) + ";"
        options_bytes = f"OPTIONS:{tg_options}".encode("utf-8")
        logger.info(f"[{self.name}] Configured RPTC Options: {tg_options} (TS1: {self.ts1_tgs}, TS2: {self.ts2_tgs}, Freq: {self.tx_freq}/{self.rx_freq} MHz, CC: {self.color_code})")

        config_data = (
            callsign_bytes + rx_freq + tx_freq + tx_pwr + color_code +
            lat + lon + height + location + desc + slots + url + soft_id + pkg_id +
            options_bytes
        )

        packet = RPTC + struct.pack(">I", self.dmr_id) + config_data
        self.sock.sendto(packet, self.target_addr)
        self.packets_tx += 1
        self._set_state("CONNECTED")
        logger.info(f"[{self.name}] ✓ Successfully connected! TS1: {self.ts1_tgs} | TS2: {self.ts2_tgs} on {self.host}:{self.port}")

    async def _ping_worker(self):
        """Periodically pings the Master to maintain keepalive or retries login."""
        retry_count = 0
        while self.running:
            await asyncio.sleep(5)
            if self.sock and self.target_addr:
                try:
                    if self.state in ["LOGGING_IN", "LOGIN_SENT"]:
                        retry_count += 1
                        logger.info(f"[{self.name}] Retrying RPTL login to {self.target_addr} (attempt {retry_count})...")
                        await self._send_rptl()
                    elif self.state == "CONNECTED":
                        ping_pkt = RPTPING + struct.pack(">I", self.dmr_id)
                        self.sock.sendto(ping_pkt, self.target_addr)
                        self.packets_tx += 1
                    elif self.state in ["AUTH_FAILED", "RETRYING"]:
                        retry_count += 1
                        logger.info(f"[{self.name}] Retrying RPTL handshake to {self.target_addr} (attempt {retry_count})...")
                        await self._send_rptl()
                except Exception as e:
                    logger.debug(f"[{self.name}] Ping/retry worker error: {e}")

    async def _handle_packet(self, data: bytes, addr: tuple):
        header = data[:4]

        # 1. RPTACK (Auth challenge or ACK)
        if data.startswith(RPTACK):
            if self.state in ["LOGGING_IN", "LOGIN_SENT"]:
                # The 4-byte challenge salt is always the last 4 bytes of RPTACK (whether len is 8 or 12)
                salt = data[-4:] if len(data) >= 8 else os.urandom(4)
                logger.info(f"[{self.name}] Received RPTACK with salt: {salt.hex()}")
                await self._send_rptk(salt)
            elif self.state == "KEY_SENT":
                logger.info(f"[{self.name}] Key accepted by master! Sending configuration...")
                await self._send_rptc()
            elif self.state == "CONNECTED":
                self.last_ping_rx = time.time()

        # 2. Master NAK (Auth rejected or invalid ID)
        elif data.startswith(MSTNAK):
            logger.warning(f"[{self.name}] ❌ Authentication REJECTED by Master (MSTNAK)! Invalid password or DMR ID.")
            self._set_state("AUTH_FAILED")

        # 3. Ping Keepalive responses
        elif data.startswith(MSTPONG) or data.startswith(MSTP) or data.startswith(MSTACK):
            self.last_ping_rx = time.time()
        elif data.startswith(MSTPING):
            self.last_ping_rx = time.time()
            if self.sock and self.target_addr:
                pong = RPTPONG + struct.pack(">I", self.dmr_id)
                self.sock.sendto(pong, self.target_addr)

        # 3. DMRD Voice and Data Frames from Network
        elif header == DMRD and len(data) >= 20:
            seq = data[4]
            src_id = int.from_bytes(data[5:8], "big")
            dst_id = int.from_bytes(data[8:11], "big")

            # Self-Echo Suppression: ignore packets reflected back by Master when we are the source
            if src_id == self.dmr_id:
                return

            bits = data[15]
            slot = 2 if (bits & 0x80) else 1
            call_type = "unit" if (bits & 0x40) else "group"
            data_type = (bits & 0x30) >> 4  # 0=Header, 1=Voice, 2=Terminator, 3=Data
            sequence = bits & 0x0F  # Burst sequence 0..5 (A..F)
            stream_id = int.from_bytes(data[16:20], "big")
            payload = data[20:]

            if self.on_traffic:
                self.on_traffic({
                    "network": self.name,
                    "host": self.host,
                    "src_id": src_id,
                    "dst_tg": dst_id,
                    "slot": slot,
                    "call_type": call_type,
                    "data_type": data_type,
                    "sequence": sequence,
                    "stream_id": stream_id,
                    "payload": payload,
                    "raw_packet": data,
                    "timestamp": time.time()
                })

    def transmit_dmrd(self, src_id: int, dst_tg: int, slot: int, data_type: int = 1, sequence: int = 0, payload: bytes = None, call_type: str = "group", dtype_vseq: int = None):
        """Constructs and transmits an authentic DMRD voice/signaling frame to Master."""
        if not self.sock or not self.target_addr or self.state != "CONNECTED":
            return False

        # Support backward compatibility if caller passes dtype_vseq
        if dtype_vseq is not None:
            if dtype_vseq == 2:
                data_type = 2
            elif dtype_vseq in [0, 1]:
                data_type = dtype_vseq

        self.stream_seq = (self.stream_seq + 1) % 256
        seq_byte = struct.pack("B", self.stream_seq)
        src_bytes = struct.pack(">I", src_id)[1:]  # 3 bytes
        dst_bytes = struct.pack(">I", dst_tg)[1:]  # 3 bytes
        repeater_bytes = struct.pack(">I", self.dmr_id)  # 4 bytes

        bits = 0
        if slot == 2:
            bits |= 0x80
        if call_type == "unit":
            bits |= 0x40
        bits |= ((data_type & 0x03) << 4)
        bits |= (sequence & 0x0F)
        bits_byte = struct.pack("B", bits)

        stream_id_bytes = struct.pack(">I", int(time.time()) & 0xFFFFFFFF)
        if not payload or len(payload) != 33:
            payload = (payload[:33] if payload else b"").ljust(33, b"\x00")

        frame = DMRD + seq_byte + src_bytes + dst_bytes + repeater_bytes + bits_byte + stream_id_bytes + payload
        try:
            self.sock.sendto(frame, self.target_addr)
            self.packets_tx += 1
            return True
        except Exception as e:
            logger.debug(f"[{self.name}] TX DMRD failed: {e}")
            return False

    def send_raw_dmrd(self, raw_data: bytes) -> bool:
        """Transmits an authentic raw DMRD packet directly to the remote master."""
        if not self.sock or not self.target_addr or self.state != "CONNECTED":
            return False
        try:
            if len(raw_data) >= 15:
                # Stamp the authenticated client repeater ID (bytes 11..14) so remote Master accepts it
                tx_pkt = raw_data[:11] + struct.pack(">I", self.dmr_id) + raw_data[15:]
            else:
                tx_pkt = raw_data
            self.sock.sendto(tx_pkt, self.target_addr)
            self.packets_tx += 1
            return True
        except Exception as e:
            logger.debug(f"[{self.name}] send_raw_dmrd error: {e}")
            return False
