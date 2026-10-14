"""HomeBrew / MMDVM repeater protocol client.

Adapted from the NinjaRadio ``core/dmr_client.py`` implementation so the same
transport can run on Android (stdlib only, no asyncio dependency needed for the
socket itself).

The client actively connects to a configured DMR master, performs the
``RPTL -> RPTACK -> RPTK -> RPTACK -> RPTC`` handshake, keeps the link alive with
``RPTP`` pings and streams voice/data using ``DMRD`` frames.
"""

import hashlib
import logging
import re
import socket
import struct
import time
from typing import Callable, Dict, List, Optional

logger = logging.getLogger("NinjaDMR-HomeBrew")

RPTL = b"RPTL"
RPTACK = b"RPTACK"
RPTK = b"RPTK"
RPTC = b"RPTC"
RPTP = b"RPTP"
RPTCL = b"RPTCL"
MSTPING = b"MSTPING"
MSTP = b"MSTP"
MSTACK = b"MSTACK"
MSTNAK = b"MSTNAK"
DMRD = b"DMRD"

BURST_BYTES = 33


def format_freq_hz_9(val) -> bytes:
    """Format a frequency (MHz like 438.8000 or Hz like 438800000) as 9 ASCII digits."""
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
    """Native HomeBrew / MMDVM repeater protocol client (blocking sockets)."""

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
        on_status_change: Optional[Callable] = None,
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
        self._parse_talkgroups()

        self.subscribed_tgs = list(dict.fromkeys(self.ts1_tgs + self.ts2_tgs))
        logger.info("[%s] Talkgroups: TS1=%s | TS2=%s", self.name, self.ts1_tgs, self.ts2_tgs)

        self.on_traffic = on_traffic
        self.on_status_change = on_status_change

        self.state = "DISCONNECTED"
        self.sock: Optional[socket.socket] = None
        self.target_addr: Optional[tuple] = None
        self.running = False
        self.stream_seq = 0
        self.last_ping_rx = 0
        self.last_ping_tx = 0
        self.packets_rx = 0
        self.packets_tx = 0

    # -- configuration ---------------------------------------------------

    def _parse_talkgroups(self) -> None:
        ts_matches = re.findall(
            r"(?i)TS([12])(?:_\d+)?\s*[=:;_]\s*([0-9, ]+)", self.raw_tgs_str
        )
        if ts_matches:
            for ts_num, tgs in ts_matches:
                for tg_part in re.split(r"[,; ]+", tgs):
                    if tg_part.strip().isdigit():
                        val = int(tg_part.strip())
                        target = self.ts1_tgs if ts_num == "1" else self.ts2_tgs
                        if val not in target:
                            target.append(val)
            return
        parts = [
            int(tg.strip())
            for tg in re.split(r"[,; ]+", self.raw_tgs_str)
            if tg.strip().isdigit()
        ]
        if len(parts) == 1:
            self.ts1_tgs.append(parts[0])
        elif len(parts) >= 2:
            self.ts1_tgs.append(parts[0])
            self.ts2_tgs.extend(parts[1:])

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

    def _set_state(self, new_state: str) -> None:
        if self.state != new_state:
            self.state = new_state
            logger.info("[%s] State -> %s (%s:%s)", self.name, new_state, self.host, self.port)
            if self.on_status_change:
                self.on_status_change(self.name, new_state)

    # -- lifecycle -------------------------------------------------------

    def connect(self, timeout: float = 5.0) -> None:
        """Resolve the master, open the socket and send the RPTL login."""
        self.running = True
        self._set_state("RESOLVING")
        addr_info = socket.getaddrinfo(self.host, self.port, proto=socket.IPPROTO_UDP)
        if not addr_info:
            raise OSError("cannot resolve hostname %s" % self.host)
        self.target_addr = addr_info[0][4]

        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("0.0.0.0", 0))
        self.sock.settimeout(timeout)

        self._set_state("LOGGING_IN")
        self._send_rptl()

    def close(self) -> None:
        self.running = False
        if self.sock and self.target_addr:
            try:
                dereg = RPTCL + struct.pack(">I", self.dmr_id)
                self.sock.sendto(dereg, self.target_addr)
                logger.info("[%s] Sent RPTCL logout", self.name)
            except Exception:
                pass
            try:
                self.sock.close()
            except Exception:
                pass
        self.sock = None
        self._set_state("DISCONNECTED")

    def poll(self, timeout: float = 0.5) -> None:
        """Receive and handle a single datagram (blocking up to *timeout*)."""
        if not self.sock or not self.target_addr:
            return
        try:
            self.sock.settimeout(timeout)
            data, addr = self.sock.recvfrom(2048)
        except socket.timeout:
            return
        if data and len(data) >= 4:
            self.packets_rx += 1
            self._handle_packet(data, addr)

    def keepalive(self) -> None:
        """Drive the login retry / RPTP ping state machine. Call ~every 5 s."""
        if not self.sock or not self.target_addr:
            return
        now = time.time()
        if self.state in ("LOGGING_IN", "LOGIN_SENT"):
            self._send_rptl()
        elif self.state in ("AUTH_FAILED", "RETRYING"):
            self._send_rptl()
        elif self.state == "CONNECTED":
            if now - self.last_ping_tx >= 5:
                try:
                    self.sock.sendto(RPTP + struct.pack(">I", self.dmr_id), self.target_addr)
                    self.packets_tx += 1
                    self.last_ping_tx = now
                except Exception as exc:
                    logger.debug("[%s] RPTP failed: %s", self.name, exc)

    # -- handshake -------------------------------------------------------

    def _send_rptl(self) -> None:
        packet = RPTL + struct.pack(">I", self.dmr_id)
        self.sock.sendto(packet, self.target_addr)
        self.packets_tx += 1
        self._set_state("LOGIN_SENT")
        logger.info("[%s] Sent RPTL login request (DMR ID %s)", self.name, self.dmr_id)

    def _send_rptk(self, salt: bytes) -> None:
        auth_hash = hashlib.sha256(salt + self.password.encode("utf-8")).digest()
        packet = RPTK + struct.pack(">I", self.dmr_id) + auth_hash
        self.sock.sendto(packet, self.target_addr)
        self.packets_tx += 1
        self._set_state("KEY_SENT")
        logger.info("[%s] Sent RPTK auth response", self.name)

    def _send_rptc(self) -> None:
        callsign_bytes = self.callsign.ljust(8)[:8].encode("utf-8")
        rx_freq = format_freq_hz_9(self.rx_freq)
        tx_freq = format_freq_hz_9(self.tx_freq)
        tx_pwr = format_tx_pwr(self.tx_pwr)
        color_code = format_color_code(self.color_code)
        lat = b"-06.2088"
        lon = b"+106.8450"
        height = b"050"
        location = "Mobile, ID".ljust(20)[:20].encode("utf-8")
        desc = "NinjaDMR".ljust(19)[:19].encode("utf-8")
        slots = b"3"
        url = "https://ninjadmr.app".ljust(124)[:124].encode("utf-8")
        soft_id = "MMDVM_Bridge".ljust(40)[:40].encode("utf-8")
        pkg_id = "MMDVM".ljust(40)[:40].encode("utf-8")

        opt_parts = []
        for i, tg in enumerate(self.ts1_tgs, start=1):
            opt_parts.append(f"TS1_{i}={tg}")
            opt_parts.append(f"TS1={tg}")
        for i, tg in enumerate(self.ts2_tgs, start=1):
            opt_parts.append(f"TS2_{i}={tg}")
            opt_parts.append(f"TS2={tg}")
        tg_options = ";".join(opt_parts) + ";"
        options_bytes = ("OPTIONS:" + tg_options).encode("utf-8")

        config_data = (
            callsign_bytes + rx_freq + tx_freq + tx_pwr + color_code +
            lat + lon + height + location + desc + slots + url + soft_id + pkg_id +
            options_bytes
        )
        packet = RPTC + struct.pack(">I", self.dmr_id) + config_data
        self.sock.sendto(packet, self.target_addr)
        self.packets_tx += 1
        self._set_state("CONNECTED")
        logger.info("[%s] Connected. TS1=%s TS2=%s on %s:%s",
                    self.name, self.ts1_tgs, self.ts2_tgs, self.host, self.port)

    # -- ingress ---------------------------------------------------------

    def _handle_packet(self, data: bytes, addr: tuple) -> None:
        if data.startswith(RPTACK):
            if self.state in ("LOGGING_IN", "LOGIN_SENT"):
                salt = data[-4:] if len(data) >= 8 else b"\x00\x00\x00\x00"
                self._send_rptk(salt)
            elif self.state == "KEY_SENT":
                self._send_rptc()
            elif self.state == "CONNECTED":
                self.last_ping_rx = time.time()
        elif data.startswith(MSTNAK):
            logger.warning("[%s] Authentication rejected (MSTNAK)", self.name)
            self._set_state("AUTH_FAILED")
        elif data.startswith((MSTPING, MSTP, MSTACK)):
            self.last_ping_rx = time.time()
            try:
                self.sock.sendto(MSTP + struct.pack(">I", self.dmr_id), self.target_addr)
            except Exception:
                pass
        elif data[:4] == DMRD and len(data) >= 20:
            self._handle_dmrd(data)

    def _handle_dmrd(self, data: bytes) -> None:
        src_id = int.from_bytes(data[5:8], "big")
        if src_id == self.dmr_id:
            return  # self-echo suppression
        dst_id = int.from_bytes(data[8:11], "big")
        bits = data[15]
        slot = 2 if (bits & 0x80) else 1
        call_type = "unit" if (bits & 0x40) else "group"
        data_type = (bits & 0x30) >> 4
        sequence = bits & 0x0F
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
                "timestamp": time.time(),
            })

    # -- egress ----------------------------------------------------------

    def _dmrd(
        self,
        src_id: int,
        dst_id: int,
        slot: int,
        data_type: int,
        sequence: int,
        payload: bytes,
        call_type: str = "group",
    ) -> bytes:
        self.stream_seq = (self.stream_seq + 1) % 256
        bits = 0
        if slot == 2:
            bits |= 0x80
        if call_type == "unit":
            bits |= 0x40
        bits |= (data_type & 0x03) << 4
        bits |= sequence & 0x0F

        payload = (payload[:BURST_BYTES] if payload else b"").ljust(BURST_BYTES, b"\x00")
        stream_id = int(time.time()) & 0xFFFFFFFF
        return (
            DMRD
            + struct.pack("B", self.stream_seq)
            + struct.pack(">I", src_id)[1:]
            + struct.pack(">I", dst_id)[1:]
            + struct.pack(">I", self.dmr_id)
            + struct.pack("B", bits)
            + struct.pack(">I", stream_id)
            + payload
        )

    def send_frame(self, frame: bytes) -> bool:
        if not self.sock or not self.target_addr or self.state != "CONNECTED":
            return False
        try:
            self.sock.sendto(frame, self.target_addr)
            self.packets_tx += 1
            return True
        except Exception as exc:
            logger.debug("[%s] TX failed: %s", self.name, exc)
            return False

    def transmit_dmrd(
        self,
        src_id: int,
        dst_tg: int,
        slot: int,
        data_type: int = 1,
        sequence: int = 0,
        payload: Optional[bytes] = None,
        call_type: str = "group",
    ) -> bool:
        """Build and transmit a single DMRD voice/signalling frame."""
        frame = self._dmrd(src_id, dst_tg, slot, data_type, sequence, payload or b"", call_type)
        return self.send_frame(frame)

    def send_voice_burst(self, burst: bytes, src_id: int, dst_tg: int, slot: int,
                         sequence: int) -> bool:
        """Send a 33-byte voice burst (sequence 0..5 = bursts A..F)."""
        return self.transmit_dmrd(src_id, dst_tg, slot, data_type=1,
                                  sequence=sequence, payload=burst)

    def send_call_header(self, src_id: int, dst_tg: int, slot: int,
                         call_type: str = "group") -> bool:
        return self.transmit_dmrd(src_id, dst_tg, slot, data_type=0, sequence=0,
                                  payload=b"", call_type=call_type)

    def send_call_terminator(self, src_id: int, dst_tg: int, slot: int,
                             call_type: str = "group") -> bool:
        return self.transmit_dmrd(src_id, dst_tg, slot, data_type=2, sequence=0,
                                  payload=b"", call_type=call_type)
