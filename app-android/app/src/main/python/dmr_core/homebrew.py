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
from typing import Callable, Dict, List, Optional, Union

logger = logging.getLogger("NinjaDMR-HomeBrew")

RPTL = b"RPTL"
RPTACK = b"RPTACK"
RPTK = b"RPTK"
RPTC = b"RPTC"
RPTO = b"RPTO"
RPTP = b"RPTP"
RPTPING = b"RPTPING"
RPTPONG = b"RPTPONG"
RPTCL = b"RPTCL"
MSTPING = b"MSTPING"
MSTP = b"MSTP"
MSTPONG = b"MSTPONG"
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
        dmr_id: Union[int, str],
        callsign: str,
        password: str = "",
        subscribed_tgs: str = "",
        slot: int = 2,
        rx_freq: str = "438.8000",
        tx_freq: str = "438.8000",
        color_code: int = 1,
        tx_pwr: int = 25,
        station_hardware: str = "NinjaRadio",
        station_software: str = "NinjaRadio",
        station_location: str = "Mobile, ID",
        station_description: str = "NinjaDMR",
        station_url: str = "https://ninjadmr.app",
        on_traffic: Optional[Callable] = None,
        on_status_change: Optional[Callable] = None,
    ):
        self.name = name
        self.host = host
        self.port = int(port)
        self.dmr_id = int(dmr_id)
        self.callsign = callsign.upper().strip()
        self.password = password.strip()
        self.slot = int(slot)
        self.rx_freq = str(rx_freq).strip()
        self.tx_freq = str(tx_freq).strip()
        self.color_code = int(color_code)
        self.tx_pwr = int(tx_pwr)
        self.station_hardware = str(station_hardware or "NinjaRadio").strip()
        self.station_software = str(station_software or "NinjaRadio").strip()
        self.station_location = str(station_location or "Mobile, ID").strip()
        self.station_description = str(station_description or "NinjaDMR").strip()
        self.station_url = str(station_url or "https://ninjadmr.app").strip()
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
        self._sock_opened_at = 0.0
        self._rpto_pending = False

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
        target_slot = getattr(self, "slot", 2)
        for tg in parts:
            if target_slot == 1:
                if tg not in self.ts1_tgs:
                    self.ts1_tgs.append(tg)
            elif target_slot == 2:
                if tg not in self.ts2_tgs:
                    self.ts2_tgs.append(tg)
            else:
                if tg not in self.ts1_tgs:
                    self.ts1_tgs.append(tg)
                if tg not in self.ts2_tgs:
                    self.ts2_tgs.append(tg)

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

    @staticmethod
    def _clean_host(host) -> str:
        """Accept IPs and domain names, tolerating 'udp://', 'http://', ':port', spaces."""
        h = str(host or "").strip()
        h = re.sub(r"^[A-Za-z][A-Za-z0-9+.-]*://", "", h)  # strip scheme
        h = h.split("/")[0].strip()                         # strip path
        if h.count(":") == 1:                                # strip host:port
            h = h.split(":")[0]
        return h.strip().rstrip(".")

    def _resolve_target(self) -> tuple:
        """Resolve the master (IP literal or DNS name) to an IPv4 UDP address."""
        host = self._clean_host(self.host)
        port = int(self.port)
        m = re.search(r'(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})', host)
        candidates = [m.group(1)] if m else []
        candidates.append(host)
        last_exc = None
        for cand in candidates:
            if not cand:
                continue
            try:
                info = socket.getaddrinfo(cand, port, socket.AF_INET, socket.SOCK_DGRAM)
                if info:
                    return info[0][4]
            except Exception as exc:
                last_exc = exc
        if m:
            return (m.group(1), port)
        raise OSError("cannot resolve hostname %s (%s)" % (host, last_exc))

    def _open_socket(self, timeout: float = 5.0) -> None:
        """Open a brand-new UDP socket (new local port => new carrier NAT mapping)."""
        old = self.sock
        self.sock = None
        if old is not None:
            try:
                old.close()
            except Exception:
                pass
        try:
            self.target_addr = self._resolve_target()
        except Exception as exc:
            if not self.target_addr:
                raise
            logger.warning("[%s] DNS re-resolve failed, keeping %s: %s", self.name, self.target_addr, exc)
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("0.0.0.0", 0))
        sock.settimeout(timeout)
        self.sock = sock
        self._sock_opened_at = time.time()

    def _relogin_fresh(self, reason: str) -> None:
        """Danura-style recovery: drop the stale socket, open a new one, send RPTL."""
        logger.warning("[%s] Reconnecting with a fresh socket: %s", self.name, reason)
        self._set_state("RETRYING")
        try:
            self._open_socket()
            self._send_rptl()
        except Exception as exc:
            logger.warning("[%s] Fresh reconnect failed: %s", self.name, exc)

    def connect(self, timeout: float = 5.0) -> None:
        """Resolve the master, open the socket and send the RPTL login."""
        self.running = True
        self._set_state("RESOLVING")
        self._open_socket(timeout)

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
        """Receive and handle datagrams (blocking up to *timeout* on the first)."""
        if not self.sock or not self.target_addr:
            return
        try:
            self.sock.settimeout(timeout)
            data, addr = self.sock.recvfrom(2048)
        except (socket.timeout, OSError):
            return
        if data and len(data) >= 4:
            self.packets_rx += 1
            self._handle_packet(data, addr)
        try:
            self.sock.settimeout(0.0)
            while True:
                data, addr = self.sock.recvfrom(2048)
                if data and len(data) >= 4:
                    self.packets_rx += 1
                    self._handle_packet(data, addr)
        except (socket.timeout, BlockingIOError, OSError):
            pass

    def keepalive(self) -> None:
        """Drive the login retry / RPTP ping state machine. Call ~every 5 s."""
        if not self.sock or not self.target_addr:
            return
        now = time.time()
        if self.state in ("LOGGING_IN", "LOGIN_SENT", "KEY_SENT", "AUTH_FAILED", "RETRYING"):
            if now - self._sock_opened_at >= 15.0:
                self._relogin_fresh("no handshake reply for %.0fs" % (now - self._sock_opened_at))
            else:
                self._send_rptl()
        elif self.state == "CONNECTED":
            if self.last_ping_rx > 0 and (now - self.last_ping_rx) > 20.0:
                logger.warning("[%s] Watchdog timeout: no packet from master for %.1fs, re-authenticating...", self.name, now - self.last_ping_rx)
                self._relogin_fresh("watchdog timeout")
                return

            if self._rpto_pending:
                # Master never ACKed RPTC: push the TG options anyway.
                self._rpto_pending = False
                self._send_rpto()

            if now - self.last_ping_tx >= 5:
                try:
                    self.sock.sendto(RPTPING + struct.pack(">I", self.dmr_id), self.target_addr)
                    self.packets_tx += 1
                    self.last_ping_tx = now
                except Exception as exc:
                    logger.debug("[%s] RPTPING failed: %s", self.name, exc)

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
        logger.warning("[%s] Sent RPTK auth response: id=%s pw=%r salt=%s hash=%s", self.name, self.dmr_id, self.password, salt.hex(), auth_hash.hex())

    def _send_rptc(self) -> None:
        callsign_bytes = self.callsign.ljust(8)[:8].encode("utf-8")
        rx_freq = format_freq_hz_9(self.rx_freq)
        tx_freq = format_freq_hz_9(self.tx_freq)
        tx_pwr = format_tx_pwr(self.tx_pwr)
        color_code = format_color_code(self.color_code)
        lat = b"-06.2088"
        lon = b"+106.8450"
        height = b"050"
        loc_str = getattr(self, "station_location", "Mobile, ID") or "Mobile, ID"
        desc_str = getattr(self, "station_description", "NinjaDMR") or "NinjaDMR"
        url_str = getattr(self, "station_url", "https://ninjadmr.app") or "https://ninjadmr.app"
        soft_str = getattr(self, "station_software", "NinjaRadio") or "NinjaRadio"
        pkg_str = getattr(self, "station_hardware", "NinjaRadio") or "NinjaRadio"

        location = loc_str.ljust(20)[:20].encode("utf-8")
        desc = desc_str.ljust(19)[:19].encode("utf-8")
        slots = b"3"
        url = url_str.ljust(124)[:124].encode("utf-8")
        soft_id = soft_str.ljust(40)[:40].encode("utf-8")
        pkg_id = pkg_str.ljust(40)[:40].encode("utf-8")

        # Talkgroup options go in a separate RPTO packet (MMDVMHost / Danura style);
        # RPTC itself stays at the standard 302-byte length.
        self.tg_options_str = self._build_tg_options()
        self._rpto_pending = bool(self.tg_options_str)

        config_data = (
            callsign_bytes + rx_freq + tx_freq + tx_pwr + color_code +
            lat + lon + height + location + desc + slots + url + soft_id + pkg_id
        )
        packet = RPTC + struct.pack(">I", self.dmr_id) + config_data
        self.sock.sendto(packet, self.target_addr)
        self.packets_tx += 1
        self._set_state("CONNECTED")
        self.last_ping_rx = time.time()
        self.last_ping_tx = time.time()
        logger.info("[%s] Connected. TS1=%s TS2=%s on %s:%s",
                    self.name, self.ts1_tgs, self.ts2_tgs, self.host, self.port)

    def _build_tg_options(self) -> str:
        opt_parts = []
        if self.ts1_tgs:
            opt_parts.append("TS1=" + ",".join(str(t) for t in self.ts1_tgs))
            for i, tg in enumerate(self.ts1_tgs, start=1):
                opt_parts.append(f"TS1_{i}={tg}")
        if self.ts2_tgs:
            opt_parts.append("TS2=" + ",".join(str(t) for t in self.ts2_tgs))
            for i, tg in enumerate(self.ts2_tgs, start=1):
                opt_parts.append(f"TS2_{i}={tg}")
        return (";".join(opt_parts) + ";") if opt_parts else ""

    def _send_rpto(self) -> None:
        tg_options = getattr(self, "tg_options_str", "")
        if not tg_options or not self.sock or not self.target_addr:
            return
        try:
            pkt = RPTO + struct.pack(">I", self.dmr_id) + tg_options.encode("ascii", errors="ignore")
            self.sock.sendto(pkt, self.target_addr)
            self.packets_tx += 1
            logger.info("[%s] Sent RPTO options: %s", self.name, tg_options)
        except Exception as e:
            logger.debug("[%s] Failed to send RPTO: %s", self.name, e)

    def update_subscriptions(self) -> None:
        self.tg_options_str = self._build_tg_options()
        if self.state == "CONNECTED" and self.sock and self.target_addr:
            self._rpto_pending = False
            self._send_rpto()

    # -- ingress ---------------------------------------------------------

    def _handle_packet(self, data: bytes, addr: tuple) -> None:
        self.last_ping_rx = time.time()
        logger.warning("[%s] INGRESS: %r len=%d state=%s", self.name, data[:16], len(data), self.state)
        if data.startswith(RPTACK):
            if self.state in ("LOGGING_IN", "LOGIN_SENT", "RETRYING", "AUTH_FAILED"):
                if len(data) >= 10:
                    salt = data[6:10]
                elif len(data) >= 8:
                    salt = data[-4:]
                else:
                    salt = b"\x00\x00\x00\x00"
                self._send_rptk(salt)
            elif self.state == "KEY_SENT":
                logger.info("[%s] Key accepted by master (RPTACK)! Sending RPTC configuration...", self.name)
                self._send_rptc()
            elif self.state == "CONNECTED" and self._rpto_pending:
                logger.info("[%s] Config accepted by master (RPTACK). Sending RPTO options...", self.name)
                self._rpto_pending = False
                self._send_rpto()
        elif data.startswith(MSTACK):
            if self.state == "KEY_SENT":
                logger.info("[%s] Key accepted by master (MSTACK)! Sending RPTC configuration...", self.name)
                self._send_rptc()
        elif data.startswith(MSTNAK):
            logger.warning("[%s] MSTNAK received in state %s", self.name, self.state)
            if self.state in ("LOGGING_IN", "LOGIN_SENT", "KEY_SENT"):
                self._set_state("AUTH_FAILED")
            elif self.state == "CONNECTED":
                logger.warning("[%s] Master rejected session with MSTNAK, re-authenticating...", self.name)
                self._relogin_fresh("MSTNAK while connected")
        elif data.startswith(MSTPING):
            if self.sock and self.target_addr:
                pong = RPTPONG + struct.pack(">I", self.dmr_id)
                try:
                    self.sock.sendto(pong, self.target_addr)
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
        frame_type = (bits & 0x30) >> 4
        dtype_vseq = bits & 0x0F

        if frame_type == 2:
            if dtype_vseq == 1:
                data_type = 0  # Voice LC Header (0x21)
            elif dtype_vseq in (0, 2):
                data_type = 2  # Voice Terminator with LC (0x22 or legacy 0x20)
            else:
                data_type = 3  # CSBK / Data
            sequence = 0
        elif frame_type == 1:
            data_type = 1      # Voice Burst A (0x10)
            sequence = dtype_vseq
        elif frame_type == 0:
            if dtype_vseq in (1, 2, 3, 4, 5):
                data_type = 1  # Voice Bursts B..F (0x01..0x05)
                sequence = dtype_vseq
            else:
                data_type = 0  # Legacy Voice Header (0x00)
                sequence = 0
        else:
            data_type = 3
            sequence = dtype_vseq

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
        if data_type == 0:
            bits |= 0x21
        elif data_type == 1:
            vseq = sequence % 6
            bits |= 0x10 if vseq == 0 else (vseq & 0x0F)
        elif data_type == 2:
            bits |= 0x22
        else:
            bits |= ((data_type & 0x03) << 4) | (sequence & 0x0F)

        payload = (payload[:BURST_BYTES] if payload else b"").ljust(BURST_BYTES, b"\x00")
        if not getattr(self, "tx_stream_id", None):
            self.tx_stream_id = int(time.time() * 1000) & 0xFFFFFFFF
        stream_id = self.tx_stream_id
        return (
            DMRD
            + struct.pack("B", self.stream_seq)
            + struct.pack(">I", src_id)[1:]
            + struct.pack(">I", dst_id)[1:]
            + struct.pack(">I", self.dmr_id)
            + struct.pack("B", bits)
            + struct.pack(">I", stream_id)
            + payload
            + b"\x00\x00"
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

    def send_sms(self, src_id: int, dst_id: int, slot: int, text: str,
                 call_type: str = "group") -> bool:
        """Send a short DMR SMS/text packet (data_type=3 Data)."""
        raw = text.encode("utf-8")[:32]
        payload = bytes([len(raw)]) + raw
        return self.transmit_dmrd(src_id, dst_id, slot, data_type=3, sequence=0,
                                  payload=payload, call_type=call_type)

    def _build_data_sync_burst(self) -> bytes:
        data_sync = [3, 1, 3, 3, 3, 3, 1, 1, 1, 3, 3, 1, 1, 3, 1, 1, 3, 1, 3, 3, 1, 1, 3, 1]
        dibits = [0] * 132
        dibits[54:78] = data_sync
        out = bytearray(33)
        for i in range(33):
            d0, d1, d2, d3 = dibits[i * 4:i * 4 + 4]
            out[i] = ((d0 & 3) << 6) | ((d1 & 3) << 4) | ((d2 & 3) << 2) | (d3 & 3)
        return bytes(out)

    def send_call_header(self, src_id: int, dst_tg: int, slot: int,
                         call_type: str = "group") -> bool:
        self.tx_stream_id = int(time.time() * 1000) & 0xFFFFFFFF
        hdr_burst = self._build_data_sync_burst()
        return self.transmit_dmrd(src_id, dst_tg, slot, data_type=0, sequence=0,
                                  payload=hdr_burst, call_type=call_type)

    def send_call_terminator(self, src_id: int, dst_tg: int, slot: int,
                             call_type: str = "group") -> bool:
        term_burst = self._build_data_sync_burst()
        res = self.transmit_dmrd(src_id, dst_tg, slot, data_type=2, sequence=0,
                                 payload=term_burst, call_type=call_type)
        self.tx_stream_id = None
        return res
