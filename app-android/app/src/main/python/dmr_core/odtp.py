"""Open DMR Terminal Protocol (ODTP) / BrandMeister "Rewind" client.

ODTP is BrandMeister's low-level terminal interface; it is based on the Rewind
protocol and is very close to the "Simple External Application" protocol.

Wire format (all multi-byte fields little-endian)::

    Data header (18 bytes)
        Sign     [8]  "REWIND01"
        Type     u16
        Flags    u16
        Sequence u32
        Length   u16   # length of the payload that follows

This module mirrors the reference implementation in
``github.com/BrandMeister/go-brandmeister/rewind`` (MIT) so the byte layout is
byte-compatible with servers speaking the Rewind protocol.

The protocol still needs validation against a live master (e.g. BrandMeister
talkgroup 9990) before being considered production-ready.
"""

import hashlib
import logging
import socket
import struct
import time
from typing import Callable, Dict, Optional

logger = logging.getLogger("NinjaDMR-ODTP")

SIGN = b"REWIND01"
SIGN_LEN = len(SIGN)
DEFAULT_PORT = 54005
HEADER_LEN = SIGN_LEN + 10  # 18
DESCRIPTION_LEN = 96
CALL_LEN = 10
SUPER_HEADER_LEN = 12 + 2 * CALL_LEN  # 32

# Packet classes
CLASS_REWIND_CONTROL = 0x0000
CLASS_SYSTEM_CONSOLE = 0x0100
CLASS_SERVICE_NOTICE = 0x0200
CLASS_DEVICE_DATA = 0x0800
CLASS_APPLICATION = 0x0900

# Control types
TYPE_KEEP_ALIVE = CLASS_REWIND_CONTROL + 0
TYPE_CLOSE = CLASS_REWIND_CONTROL + 1
TYPE_CHALLENGE = CLASS_REWIND_CONTROL + 2
TYPE_AUTHENTICATION = CLASS_REWIND_CONTROL + 3

# Service notice / console types
TYPE_REPORT = CLASS_SYSTEM_CONSOLE + 0

# Application types
TYPE_CONFIGURATION = CLASS_APPLICATION + 0
TYPE_SUBSCRIPTION = CLASS_APPLICATION + 1
TYPE_DMR_DATA_BASE = CLASS_APPLICATION + 0x10
TYPE_DMR_AUDIO_BASE = CLASS_APPLICATION + 0x20
TYPE_DMR_EMBEDDED_DATA = CLASS_APPLICATION + 0x27
TYPE_SUPER_HEADER = CLASS_APPLICATION + 0x28
TYPE_FAILURE_CODE = CLASS_APPLICATION + 0x29
TYPE_LOCATION = CLASS_APPLICATION + 0x30

# Flags
FLAG_NONE = 0
FLAG_REAL_TIME_1 = 1
FLAG_REAL_TIME_2 = 2

# Roles / services
ROLE_REPEATER_AGENT = 0x10
ROLE_APPLICATION = 0x20
SERVICE_SIMPLE_APPLICATION = ROLE_APPLICATION

# Options
OPTION_SUPER_HEADER = 1
OPTION_LINEAR_FRAME = 2

# Session types
SESSION_PRIVATE_VOICE = 5
SESSION_GROUP_VOICE = 7


def pack_header(ptype: int, sequence: int, flags: int = FLAG_NONE, length: int = 0) -> bytes:
    return SIGN + struct.pack("<HHIH", ptype, flags, sequence & 0xFFFFFFFF, length)


def unpack_header(data: bytes) -> Dict:
    if len(data) < HEADER_LEN:
        raise ValueError("packet too short for header")
    sign, ptype, flags, seq, length = struct.unpack_from("<8sHHIH", data, 0)
    return {
        "sign": sign,
        "type": ptype,
        "flags": flags,
        "sequence": seq,
        "length": length,
    }


class OdmrTpClient:
    """Blocking-socket ODTP/Rewind client (mirrors the Go reference client)."""

    def __init__(
        self,
        host: str,
        port: int = DEFAULT_PORT,
        remote_id: int = 0,
        password: str = "",
        options: int = 0,
        description: str = "NinjaDMR",
        on_audio: Optional[Callable] = None,
        on_super_header: Optional[Callable] = None,
        on_location: Optional[Callable] = None,
        on_status_change: Optional[Callable] = None,
    ):
        self.host = host
        self.port = int(port)
        self.remote_id = int(remote_id)
        self.password = password
        self.options = int(options)
        self.description = description
        self.on_audio = on_audio
        self.on_super_header = on_super_header
        self.on_location = on_location
        self.on_status_change = on_status_change

        self.sock: Optional[socket.socket] = None
        self.target_addr: Optional[tuple] = None
        self.sequence = 0
        self.authenticated = False
        self.subscriptions: Dict[int, int] = {}
        self.last_keep_alive = 0.0
        self.state = "DISCONNECTED"
        self.location = None  # type: Optional[tuple]
        self.location_interval = 30.0
        self.last_location = 0.0

    # -- lifecycle -------------------------------------------------------

    def connect(self, timeout: float = 5.0) -> None:
        addr_info = socket.getaddrinfo(self.host, self.port, proto=socket.IPPROTO_UDP)
        if not addr_info:
            raise OSError("cannot resolve %s" % self.host)
        self.target_addr = addr_info[0][4]
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("0.0.0.0", 0))
        self.sock.settimeout(timeout)
        self._set_state("KEEP_ALIVE_SENT")
        self._send_keep_alive()

    def close(self) -> None:
        if self.sock:
            try:
                self.sock.sendto(self._packet(TYPE_CLOSE, b""), self.target_addr)
            except Exception:
                pass
            try:
                self.sock.close()
            except Exception:
                pass
        self.sock = None
        self.authenticated = False
        self._set_state("DISCONNECTED")

    def _set_state(self, new_state: str) -> None:
        if self.state != new_state:
            self.state = new_state
            logger.info("[ODTP %s:%s] State -> %s", self.host, self.port, new_state)
            if self.on_status_change:
                self.on_status_change(self.host, new_state)

    # -- outbound --------------------------------------------------------

    def _next_seq(self) -> int:
        self.sequence = (self.sequence + 1) & 0xFFFFFFFF
        return self.sequence

    def _packet(self, ptype: int, payload: bytes, flags: int = FLAG_NONE) -> bytes:
        return pack_header(ptype, self._next_seq(), flags, len(payload)) + payload

    def _send(self, ptype: int, payload: bytes, flags: int = FLAG_NONE) -> bool:
        if not self.sock or not self.target_addr:
            return False
        try:
            self.sock.sendto(self._packet(ptype, payload, flags), self.target_addr)
            return True
        except Exception as exc:
            logger.debug("[ODTP] send failed: %s", exc)
            return False

    def _send_keep_alive(self) -> bool:
        description = self.description.encode("utf-8")[:DESCRIPTION_LEN]
        description = description.ljust(DESCRIPTION_LEN, b"\x00")
        payload = struct.pack("<IB", self.remote_id, SERVICE_SIMPLE_APPLICATION) + description
        self.last_keep_alive = time.time()
        return self._send(TYPE_KEEP_ALIVE, payload)

    def send_configuration(self) -> bool:
        self.last_keep_alive = time.time()
        return self._send(TYPE_CONFIGURATION, struct.pack("<I", self.options))

    def send_authentication(self, challenge: bytes) -> bool:
        digest = hashlib.sha256(challenge + self.password.encode("utf-8")).digest()
        return self._send(TYPE_AUTHENTICATION, digest)

    def set_location(self, latitude, longitude, altitude=0, speed=0, heading=0) -> None:
        """Enable periodic GPS reporting (also sent immediately when available)."""
        self.location = (latitude, longitude, altitude, speed, heading)

    def subscribe(self, target: int, session_type: int = SESSION_GROUP_VOICE) -> bool:
        self.subscriptions[int(target)] = int(session_type)
        return self._send(TYPE_SUBSCRIPTION, struct.pack("<II", session_type, target))

    def send_dmr_audio(self, frame_index: int, data: bytes, real_time: bool = True) -> bool:
        """Send a DMR audio packet (frame_index maps to TYPE_DMR_AUDIO_BASE + i)."""
        flags = FLAG_REAL_TIME_1 if real_time else FLAG_NONE
        return self._send(TYPE_DMR_AUDIO_BASE + (frame_index & 0x0F), data, flags)

    def send_dmr_data(self, data_index: int, data: bytes, real_time: bool = False) -> bool:
        flags = FLAG_REAL_TIME_1 if real_time else FLAG_NONE
        return self._send(TYPE_DMR_DATA_BASE + (data_index & 0x0F), data, flags)

    def send_super_header(self, session_type: int, source: int, target: int,
                          source_call: bytes = b"", target_call: bytes = b"") -> bool:
        payload = (
            struct.pack("<III", session_type, source, target)
            + source_call[:CALL_LEN].ljust(CALL_LEN, b"\x00")
            + target_call[:CALL_LEN].ljust(CALL_LEN, b"\x00")
        )
        return self._send(TYPE_SUPER_HEADER, payload)

    def send_location(
        self,
        latitude: float,
        longitude: float,
        altitude: int = 0,
        speed: int = 0,
        heading: int = 0,
    ) -> bool:
        """Report GPS position to the master (best-effort; wire format follows the
        Rewind ``Location`` message: lat/lon as signed degrees x 1e6)."""
        payload = struct.pack(
            "<iiihH",
            int(round(latitude * 1_000_000)),
            int(round(longitude * 1_000_000)),
            int(altitude),
            int(speed),
            int(heading) & 0xFFFF,
        )
        return self._send(TYPE_LOCATION, payload)

    # -- inbound ---------------------------------------------------------

    def keepalive(self) -> None:
        """Call roughly every 5 s: re-send keepalive or configuration."""
        if not self.sock:
            return
        now = time.time()
        if self.authenticated:
            if now - self.last_keep_alive >= 5:
                self.send_configuration()
            if self.location and now - self.last_location >= self.location_interval:
                self.send_location(*self.location)
                self.last_location = now
        elif now - self.last_keep_alive >= 5:
            self._send_keep_alive()

    def poll(self, timeout: float = 0.5) -> None:
        if not self.sock:
            return
        try:
            self.sock.settimeout(timeout)
            data, addr = self.sock.recvfrom(2048)
        except socket.timeout:
            return
        if len(data) < SIGN_LEN or data[:SIGN_LEN] != SIGN:
            return
        try:
            self._parse(data)
        except Exception as exc:
            logger.debug("[ODTP] parse error: %s", exc)

    def _parse(self, data: bytes) -> None:
        header = unpack_header(data)
        ptype = header["type"]
        payload = data[HEADER_LEN:]

        if ptype == TYPE_CLOSE:
            logger.info("[ODTP] server sent close")
            self.authenticated = False
        elif ptype == TYPE_KEEP_ALIVE:
            if not self.authenticated:
                self.send_configuration()
        elif ptype == TYPE_CONFIGURATION:
            self.authenticated = True
            self._set_state("CONNECTED")
            for target, session_type in self.subscriptions.items():
                self.subscribe(target, session_type)
        elif ptype == TYPE_SUBSCRIPTION:
            logger.debug("[ODTP] subscription confirmed")
        elif ptype == TYPE_REPORT:
            logger.debug("[ODTP] report: %r", payload)
        elif ptype == TYPE_CHALLENGE:
            self.authenticated = False
            self.send_authentication(payload)
        elif ptype == TYPE_SUPER_HEADER:
            if len(payload) >= SUPER_HEADER_LEN and self.on_super_header:
                st, src, dst, sc, tc = struct.unpack_from("<III10s10s", payload, 0)
                self.on_super_header({
                    "session_type": st,
                    "source": src,
                    "target": dst,
                    "source_call": sc.rstrip(b"\x00").decode("utf-8", "replace"),
                    "target_call": tc.rstrip(b"\x00").decode("utf-8", "replace"),
                })
        elif ptype == TYPE_LOCATION:
            if len(payload) >= 16 and self.on_location:
                lat_e6, lon_e6, alt, speed, heading = struct.unpack_from("<iiihH", payload, 0)
                self.on_location({
                    "latitude": lat_e6 / 1_000_000.0,
                    "longitude": lon_e6 / 1_000_000.0,
                    "altitude": alt,
                    "speed": speed,
                    "heading": heading,
                })
        elif TYPE_DMR_AUDIO_BASE <= ptype < TYPE_DMR_EMBEDDED_DATA:
            if self.on_audio:
                self.on_audio({
                    "frame": ptype - TYPE_DMR_AUDIO_BASE,
                    "data": payload,
                    "flags": header["flags"],
                })
