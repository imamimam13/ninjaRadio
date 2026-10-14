"""High-level DMR session: ties the vocoder to a network protocol.

This is the single entry point the Android layer talks to. It owns:

* a vocoder backend (:class:`~dmr_core.vocoder.OpenDmrVocoder`),
* a network transport (:class:`~dmr_core.homebrew.HomeBrewClient` or
  :class:`~dmr_core.odtp.OdmrTpClient`),
* a blocking run-loop thread (receive + keep-alive),
* a TX call state machine (header -> bursts A..F -> terminator).

Callbacks are plain Python callables and are always invoked from the session
thread, so the caller must marshal to its own thread if needed.
"""

import json
import logging
import threading
import time
from collections import deque
from typing import Callable, Optional

from .homebrew import HomeBrewClient
from .odtp import OdmrTpClient
from .vocoder import BURST_PCM_BYTES, OpenDmrVocoder

logger = logging.getLogger("NinjaDMR-Session")

PROTOCOL_HOMEBREW = "homebrew"
PROTOCOL_ODTP = "odtp"

# DMR voice data_type values carried in the DMRD "bits" byte.
DATA_HEADER = 0
DATA_VOICE = 1
DATA_TERMINATOR = 2

# Number of voice bursts per superframe (A..F).
BURSTS_PER_SUPERFRAME = 6


def coerce_config(config) -> dict:
    """Normalise the host-supplied session config into a plain ``dict``.

    Chaquopy hands a Kotlin ``Map`` across the bridge as an opaque
    ``java.util.LinkedHashMap``, which Python cannot iterate (``dict(x)`` then
    raises ``TypeError: 'LinkedHashMap' object is not iterable``).  The host
    therefore sends JSON; dicts are still accepted so host-side tests keep
    working.
    """
    if config is None:
        return {}
    if isinstance(config, dict):
        return dict(config)
    if isinstance(config, bytes):
        config = config.decode("utf-8", "replace")
    if isinstance(config, str):
        text = config.strip()
        if not text:
            return {}
        data = json.loads(text)
        if not isinstance(data, dict):
            raise TypeError("config JSON must be an object, got %s" % type(data).__name__)
        return data
    raise TypeError(
        "configure() expects a dict or a JSON object string, got %s"
        % type(config).__name__
    )


class DmrSession:
    def __init__(
        self,
        protocol: str,
        host: str,
        port: int,
        dmr_id: int,
        callsign: str = "",
        password: str = "",
        talkgroup: int = 9990,
        slot: int = 2,
        color_code: int = 1,
        subscribed_tgs: str = "",
        on_rx_pcm: Optional[Callable[[bytes], None]] = None,
        on_status: Optional[Callable[[str], None]] = None,
        on_traffic: Optional[Callable[[dict], None]] = None,
    ):
        self.protocol = protocol
        self.host = host
        self.port = int(port)
        self.dmr_id = int(dmr_id)
        self.callsign = callsign
        self.password = password
        self.talkgroup = int(talkgroup)
        self.slot = int(slot)
        self.color_code = int(color_code)

        self.on_rx_pcm = on_rx_pcm
        self.on_status = on_status
        self.on_traffic = on_traffic

        self.vocoder = OpenDmrVocoder()
        self.client = None
        self._build_client(subscribed_tgs)

        self._thread: Optional[threading.Thread] = None
        self._running = False
        self._lock = threading.Lock()

        self._tx_in_call = False
        self._tx_burst = 0
        self._tx_warmup = 0
        self._last_voice_ts = 0.0

    # -- setup -----------------------------------------------------------

    def _build_client(self, subscribed_tgs: str) -> None:
        if self.protocol == PROTOCOL_ODTP:
            self.client = OdmrTpClient(
                host=self.host,
                port=self.port,
                remote_id=self.dmr_id,
                password=self.password,
                on_audio=self._on_odtp_audio,
                on_super_header=self._on_odtp_super_header,
                on_location=self._on_odtp_location,
                on_status_change=lambda _h, s: self._emit_status(s),
            )
        else:
            self.client = HomeBrewClient(
                name="NinjaDMR",
                host=self.host,
                port=self.port,
                dmr_id=self.dmr_id,
                callsign=self.callsign,
                password=self.password,
                subscribed_tgs=subscribed_tgs or str(self.talkgroup),
                color_code=self.color_code,
                on_traffic=self._on_hb_traffic,
                on_status_change=lambda _n, s: self._emit_status(s),
            )

    def _emit_status(self, status: str) -> None:
        logger.info("[Session] status: %s", status)
        if self.on_status:
            try:
                self.on_status(status)
            except Exception:
                logger.exception("on_status callback failed")

    # -- lifecycle -------------------------------------------------------

    def start(self) -> None:
        if self._running:
            return
        self.vocoder.reset_decoder()
        self.vocoder.reset_encoder()
        self._running = True
        self.client.connect()
        self._thread = threading.Thread(target=self._run_loop, name="dmr-session", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._running = False
        if self._thread is not None:
            self._thread.join(timeout=2.0)
            self._thread = None
        try:
            self.client.close()
        finally:
            self._emit_status("DISCONNECTED")

    def close(self) -> None:
        self.stop()
        self.vocoder.close()

    def __enter__(self) -> "DmrSession":
        self.start()
        return self

    def __exit__(self, *_exc) -> None:
        self.close()

    def _run_loop(self) -> None:
        last_keepalive = 0.0
        while self._running:
            try:
                self.client.poll(timeout=0.2)
                now = time.time()
                if now - last_keepalive >= 5.0:
                    self.client.keepalive()
                    last_keepalive = now
                self._check_tx_timeout(now)
            except Exception:
                logger.exception("session loop error")
                time.sleep(0.1)

    # -- transmit --------------------------------------------------------

    def start_call(self) -> bool:
        with self._lock:
            self._tx_burst = 0
            self._tx_warmup = 2  # prime the encoder; first frames are quiet
            self._tx_in_call = True
        if self.protocol == PROTOCOL_HOMEBREW:
            self.client.send_call_header(self.dmr_id, self.talkgroup, self.slot)
        return True

    def send_voice(self, pcm: bytes) -> bool:
        """Encode and send one 480-sample (960-byte) voice burst."""
        if len(pcm) != BURST_PCM_BYTES:
            raise ValueError("pcm must be 960 bytes (480 samples)")
        with self._lock:
            if not self._tx_in_call:
                self._tx_in_call = True
                self._tx_burst = 0
                if self.protocol == PROTOCOL_HOMEBREW:
                    self.client.send_call_header(self.dmr_id, self.talkgroup, self.slot)
            sequence = self._tx_burst
        burst = self.vocoder.encode_burst(pcm)
        ok = self._send_burst(burst, sequence)
        with self._lock:
            self._tx_burst = (self._tx_burst + 1) % BURSTS_PER_SUPERFRAME
            self._last_voice_ts = time.time()
        return ok

    def end_call(self) -> None:
        with self._lock:
            if not self._tx_in_call:
                return
            self._tx_in_call = False
        if self.protocol == PROTOCOL_HOMEBREW:
            self.client.send_call_terminator(self.dmr_id, self.talkgroup, self.slot)

    def _send_burst(self, burst: bytes, sequence: int) -> bool:
        if self.protocol == PROTOCOL_HOMEBREW:
            return self.client.send_voice_burst(burst, self.dmr_id, self.talkgroup,
                                                self.slot, sequence)
        return self.client.send_dmr_audio(sequence, burst)

    def _check_tx_timeout(self, now: float) -> None:
        # Auto-terminate a call after ~400 ms of silence.
        with self._lock:
            in_call = self._tx_in_call
        if in_call and getattr(self, "_last_voice_ts", 0) and now - self._last_voice_ts > 0.4:
            self.end_call()

    # -- receive ---------------------------------------------------------

    def _on_hb_traffic(self, info: dict) -> None:
        data_type = info.get("data_type")
        if data_type == DATA_TERMINATOR:
            self._emit_status("RX_END")
            return
        if data_type == DATA_HEADER:
            self._emit_status(
                "RX_START tg=%s src=%s slot=%s" % (info.get("dst_tg"), info.get("src_id"),
                                                   info.get("slot"))
            )
            return
        if data_type != DATA_VOICE:
            return
        payload = info.get("payload") or b""
        if len(payload) >= 33:
            self._decode_burst(payload[:33])

    def _on_odtp_audio(self, info: dict) -> None:
        payload = info.get("data") or b""
        if len(payload) >= 33:
            self._decode_burst(payload[:33])

    def _on_odtp_super_header(self, info: dict) -> None:
        if self.on_traffic:
            try:
                self.on_traffic(dict(info, kind="super_header"))
            except Exception:
                logger.exception("on_traffic callback failed")

    def _on_odtp_location(self, info: dict) -> None:
        if self.on_traffic:
            try:
                self.on_traffic(dict(info, kind="location"))
            except Exception:
                logger.exception("on_traffic callback failed")

    # -- location --------------------------------------------------------

    def set_location(self, latitude, longitude, altitude=0, speed=0, heading=0) -> None:
        setter = getattr(self.client, "set_location", None)
        if setter is not None:
            setter(latitude, longitude, altitude, speed, heading)

    def send_location(self, latitude, longitude, altitude=0, speed=0, heading=0) -> bool:
        sender = getattr(self.client, "send_location", None)
        if sender is not None:
            return bool(sender(latitude, longitude, altitude, speed, heading))
        return False

    # -- talkgroup ---------------------------------------------------------

    def set_talkgroup(self, talkgroup: int) -> None:
        """Change the active talkgroup (used by scan and manual retune)."""
        self.talkgroup = int(talkgroup)
        logger.info("[Session] active talkgroup -> %s", self.talkgroup)

    def _decode_burst(self, burst: bytes) -> None:
        pcm = self.vocoder.decode_burst(burst)
        if pcm and self.on_rx_pcm:
            try:
                self.on_rx_pcm(pcm)
            except Exception:
                logger.exception("on_rx_pcm callback failed")


class Runtime:
    """Thread-safe facade for foreign runtimes (Kotlin/Chaquopy) to drive.

    The host side calls :meth:`configure`, :meth:`start`, :meth:`feed_tx` and
    :meth:`poll_rx`; decoded PCM is buffered and drained by the host audio
    engine, avoiding cross-language callbacks on the session thread.
    """

    def __init__(self):
        self._lock = threading.Lock()
        self._session: Optional[DmrSession] = None
        self._rx: deque = deque(maxlen=64)
        self._status = "idle"
        self._traffic: deque = deque(maxlen=32)
        self._config: dict = {}

    def configure(self, config) -> None:
        with self._lock:
            self._config = coerce_config(config)

    def start(self) -> dict:
        with self._lock:
            if self._session is not None:
                return self.status()
            cfg = dict(self._config)
        session = DmrSession(
            protocol=cfg.get("protocol", PROTOCOL_HOMEBREW),
            host=cfg["host"],
            port=int(cfg.get("port", 62031)),
            dmr_id=int(cfg["dmr_id"]),
            callsign=cfg.get("callsign", ""),
            password=cfg.get("password", ""),
            talkgroup=int(cfg.get("talkgroup", 9990)),
            slot=int(cfg.get("slot", 2)),
            color_code=int(cfg.get("color_code", 1)),
            subscribed_tgs=cfg.get("subscribed_tgs", ""),
            on_rx_pcm=self._rx.append,
            on_status=self._set_status,
            on_traffic=self._traffic.append,
        )
        if cfg.get("latitude") is not None and cfg.get("longitude") is not None:
            session.set_location(
                float(cfg["latitude"]),
                float(cfg["longitude"]),
                int(cfg.get("altitude", 0)),
                int(cfg.get("speed", 0)),
                int(cfg.get("heading", 0)),
            )
        try:
            session.start()
        except Exception as exc:
            self._set_status("ERROR: %s" % exc)
            session.close()
            raise
        with self._lock:
            self._session = session
        return self.status()

    def stop(self) -> None:
        with self._lock:
            session = self._session
            self._session = None
        if session is not None:
            session.close()
        self._set_status("idle")

    def feed_tx(self, pcm: bytes) -> bool:
        with self._lock:
            session = self._session
        if session is None:
            return False
        return session.send_voice(pcm)

    def end_call(self) -> None:
        with self._lock:
            session = self._session
        if session is not None:
            session.end_call()

    def poll_rx(self) -> Optional[bytes]:
        try:
            return self._rx.popleft()
        except IndexError:
            return None

    def drain_traffic(self) -> list:
        items = list(self._traffic)
        self._traffic.clear()
        return items

    def set_location(self, latitude, longitude, altitude=0, speed=0, heading=0) -> None:
        with self._lock:
            session = self._session
        if session is not None:
            session.set_location(latitude, longitude, altitude, speed, heading)

    def send_location(self, latitude, longitude, altitude=0, speed=0, heading=0) -> bool:
        with self._lock:
            session = self._session
        if session is None:
            return False
        return session.send_location(latitude, longitude, altitude, speed, heading)

    def set_talkgroup(self, talkgroup: int) -> None:
        with self._lock:
            session = self._session
        if session is not None:
            session.set_talkgroup(int(talkgroup))

    def _set_status(self, status: str) -> None:
        with self._lock:
            self._status = status

    def status(self) -> dict:
        with self._lock:
            active = self._session is not None
            return {"state": self._status, "active": active}

