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
import re

import logging

import threading

import time

from collections import deque

from typing import Callable, Optional, Union



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

        dmr_id: Union[int, str],

        callsign: str = "",

        password: str = "",

        talkgroup: int = 9990,

        slot: int = 2,

        color_code: int = 1,

        subscribed_tgs: str = "",

        on_rx_pcm: Optional[Callable[[bytes], None]] = None,

        on_status: Optional[Callable[[str], None]] = None,

        on_traffic: Optional[Callable[[dict], None]] = None,
        promiscuous: bool = False,
        station_hardware: str = "NinjaRadio",
        station_software: str = "NinjaRadio",
        station_location: str = "Mobile, ID",
        station_description: str = "NinjaDMR",
        station_url: str = "https://ninjadmr.app",
    ):
        self.protocol = protocol
        self.station_hardware = station_hardware
        self.station_software = station_software
        self.station_location = station_location
        self.station_description = station_description
        self.station_url = station_url

        self.host = host

        self.port = int(port)

        self.dmr_id = int(dmr_id)

        self.callsign = callsign

        self.password = password

        self.talkgroup = int(talkgroup)

        self.slot = int(slot)

        self.color_code = int(color_code)

        self.promiscuous = bool(promiscuous)



        self.on_rx_pcm = on_rx_pcm

        self.on_status = on_status

        self.on_traffic = on_traffic



        # The vocoder is deliberately non-fatal: connecting to the master is the

        # primary goal, and audio can only matter once the link is up. A missing

        # libopendmr must therefore not abort the handshake.

        self.vocoder: Optional[OpenDmrVocoder] = None

        self.vocoder_error: Optional[str] = None

        try:

            self.vocoder = OpenDmrVocoder()

        except Exception as exc:

            self.vocoder_error = str(exc)

            logger.warning("vocoder unavailable, connecting without audio: %s", exc)

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

            subs = subscribed_tgs.strip() if (subscribed_tgs and subscribed_tgs.strip()) else str(self.talkgroup)

            self.client = HomeBrewClient(
                name="NinjaDMR",
                host=self.host,
                port=self.port,
                dmr_id=self.dmr_id,
                callsign=self.callsign,
                password=self.password,
                subscribed_tgs=subs,
                slot=self.slot,
                color_code=self.color_code,
                station_hardware=self.station_hardware,
                station_software=self.station_software,
                station_location=self.station_location,
                station_description=self.station_description,
                station_url=self.station_url,
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

        if self.vocoder is not None:

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

        if self.vocoder is not None:

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

                self.client.poll(timeout=0.04)

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

        if self.vocoder is None:

            return False

        # ETSI TS 102 361-1 Base Station Voice Sync (Burst A) & EMB with Colour Code 1 (Bursts B..F)
        voice_sync = [1, 3, 1, 1, 1, 1, 3, 3, 3, 1, 1, 3, 3, 1, 3, 3, 1, 3, 1, 1, 3, 3, 1, 3]
        emb_cc1 = [0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 3]
        sync_dibits = voice_sync if (sequence % 6 == 0) else emb_cc1
        burst = self.vocoder.encode_burst(pcm, sync_dibits=sync_dibits)

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

        # Auto-terminate a call after ~5.0 s of silence (extended for mobile audio jitter).

        with self._lock:

            in_call = self._tx_in_call

        if in_call and getattr(self, "_last_voice_ts", 0) and now - self._last_voice_ts > 5.0:

            self.end_call()



    # -- receive ---------------------------------------------------------



    def _matches_channel(self, info: dict) -> bool:
        if getattr(self, "promiscuous", False):
            return True
        dst_tg = int(info.get("dst_tg") or 0)
        slot = int(info.get("slot") or 0)
        call_type = str(info.get("call_type") or "group")

        # 1. Private Call addressed to our DMR ID is ALWAYS accepted
        if call_type == "unit" and dst_tg == self.dmr_id:
            return True
        if dst_tg == self.dmr_id:
            return True

        # 2. Universal broadcast / special TGs (All-Call, Local 9, Parrot 9990, 9999)
        if dst_tg in (16777215, 9, 9990, 9999):
            return True

        # 3. If scanning
        if hasattr(self, "scan_tgs") and self.scan_tgs and dst_tg in self.scan_tgs:
            return True

        # 4. Talkgroup match:
        # If dst_tg matches active talkgroup (or is 0 voice burst payload), accept it!
        # Do NOT drop matching talkgroup audio just because repeater forwarded on other slot.
        if self.talkgroup and (dst_tg == self.talkgroup or dst_tg == 0):
            return True

        return False



    def _on_hb_traffic(self, info: dict) -> None:

        if not self._matches_channel(info):

            return



        if self.on_traffic:

            try:

                self.on_traffic(dict(info))

            except Exception:

                logger.exception("on_traffic callback failed")

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

        if not self._matches_channel(info):

            return

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



    def send_sms(self, dst_id: int, text: str) -> bool:

        if self.protocol == PROTOCOL_HOMEBREW and self.client is not None:

            call_type = "unit" if int(dst_id) > 999999 else "group"

            return bool(self.client.send_sms(self.dmr_id, int(dst_id), self.slot, str(text), call_type=call_type))

        return False



    # -- talkgroup ---------------------------------------------------------



    def set_talkgroup(self, talkgroup: int, slot: Optional[int] = None) -> None:

        """Change the active talkgroup and slot (used by scan, channel switch, and manual retune)."""

        self.talkgroup = int(talkgroup)

        if slot is not None and int(slot) in (1, 2):

            self.slot = int(slot)

        logger.info("[Session] active talkgroup -> %s (slot %s)", self.talkgroup, self.slot)

        if self.client and hasattr(self.client, 'ts2_tgs'):

            if self.slot == 1:

                self.client.ts1_tgs = [self.talkgroup]

                self.client.ts2_tgs = []

            elif self.slot == 2:

                self.client.ts1_tgs = []

                self.client.ts2_tgs = [self.talkgroup]

            else:

                self.client.ts1_tgs = [self.talkgroup]

                self.client.ts2_tgs = [self.talkgroup]

            if hasattr(self.client, 'update_subscriptions'):

                self.client.update_subscriptions()



    def _decode_burst(self, burst: bytes) -> None:

        if self.vocoder is None:

            return

        try:

            pcm = self.vocoder.decode_burst(burst)

            if pcm and self.on_rx_pcm:

                self.on_rx_pcm(pcm)

        except Exception:

            logger.exception("decode_burst failed")





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

        def _safe_int(v, def_val):
            if v is None: return def_val
            try:
                digits = re.sub(r'[^\d]', '', str(v))
                return int(digits) if digits else def_val
            except Exception:
                return def_val

        host_raw = str(cfg.get("host") or "").strip()
        m_ip = re.search(r'(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})', host_raw)
        # If host has an IP (e.g. 43.173.13.117.sslip.io), prefer the raw IP to bypass cellular DNS blocks
        resolved_host = m_ip.group(1) if m_ip else (host_raw if host_raw else "43.173.13.117")

        session = DmrSession(
            protocol=cfg.get("protocol", PROTOCOL_HOMEBREW),
            host=resolved_host,
            port=_safe_int(cfg.get("port"), 62301),
            dmr_id=_safe_int(cfg.get("dmr_id"), 1022057),
            callsign=str(cfg.get("callsign", "BD62BOT")).strip(),
            password=str(cfg.get("password", "")).strip(),
            talkgroup=_safe_int(cfg.get("talkgroup"), 1025),
            slot=_safe_int(cfg.get("slot"), 2),
            color_code=_safe_int(cfg.get("color_code"), 1),
            subscribed_tgs=str(cfg.get("subscribed_tgs", "")),
            on_rx_pcm=self._rx.append,
            on_status=self._set_status,
            on_traffic=self._traffic.append,
            station_hardware=cfg.get("station_hardware", "NinjaRadio"),
            station_software=cfg.get("station_software", "NinjaRadio"),
            station_location=cfg.get("station_location", "Mobile, ID"),
            station_description=cfg.get("station_description", "NinjaDMR"),
            station_url=cfg.get("station_url", "https://ninjadmr.app"),
        )

        lat = cfg.get("latitude")
        lon = cfg.get("longitude")
        if lat is not None and lon is not None and str(lat).strip() and str(lon).strip():
            try:
                session.set_location(
                    float(lat),
                    float(lon),
                    _safe_int(cfg.get("altitude"), 0),
                    _safe_int(cfg.get("speed"), 0),
                    _safe_int(cfg.get("heading"), 0),
                )
            except Exception:
                pass

        try:
            session.start()
        except Exception as exc:
            logger.exception("DmrSession start failure: %s", exc)
            self._set_status("ERROR: %s" % exc)
            session.close()
            with self._lock:
                self._session = None
            return {"state": "error", "error": str(exc), "vocoder": "disabled"}

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



    def start_call(self) -> bool:

        with self._lock:

            session = self._session

        if session is not None:

            return session.start_call()

        return False



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



    def send_sms(self, dst_id: int, text: str) -> bool:

        with self._lock:

            session = self._session

        if session is None:

            return False

        return session.send_sms(int(dst_id), str(text))



    def set_talkgroup(self, talkgroup: int, slot: Optional[int] = None) -> None:

        with self._lock:

            session = self._session

        if session is not None:

            session.set_talkgroup(int(talkgroup), int(slot) if slot is not None else None)



    def _set_status(self, status: str) -> None:

        with self._lock:

            self._status = status



    def status(self) -> dict:

        with self._lock:

            session = self._session

            active = session is not None

            if session is None:

                vocoder = "not started"

            elif session.vocoder is not None:

                vocoder = "ok"

            else:

                # First line only: the full path list stays in the exception/log.

                first = (session.vocoder_error or "unknown").split("\n", 1)[0]

                vocoder = "unavailable: %s" % first

            return {"state": self._status, "active": active, "vocoder": vocoder}



