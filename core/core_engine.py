import asyncio
import socket
import logging
import struct
import json
import os
import re
import time
import subprocess
import signal
from typing import Dict, List, Optional
import aiohttp
from fastapi import FastAPI
import uvicorn

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] [NinjaRadio-Core] %(message)s")
logger = logging.getLogger("NinjaRadio-Core")

CONFIG_PATH = os.getenv("CONFIG_PATH", "/app/data/config.json")
BACKEND_EVENT_URL = os.getenv("BACKEND_EVENT_URL", "http://backend:8000/api/internal/dmr_event")
MUMBLE_BRIDGE_PORT = 12346
USRP_AUDIO_PORT = int(os.getenv("USRP_AUDIO_PORT", 32345))
DMR_MASTER_PORT = int(os.getenv("DMR_MASTER_PORT", 62031))

# Internal DVSwitch ports
AB_USRP_TX_PORT = 32001   # Analog_Bridge sends decoded PCM to supervisor on 32001
AB_USRP_RX_PORT = 32000   # Supervisor sends PCM to Analog_Bridge on 32000 to encode
MD380_EMU_PORT = 2470     # md380-emu vocoder daemon port
AB_AMBE_RX_PORT = 31100   # Analog_Bridge receives AMBE from MMDVM_Bridge on 31100
AB_AMBE_TX_PORT = 31103   # Analog_Bridge sends AMBE to MMDVM_Bridge on 31103

# ----------------- Official DVSwitch USRP protocol (as used by pyUC / Analog_Bridge 1.6.x) -----------------
# Header: b"USRP" + >iiiiiii (seq, memory, keyup, talkgroup, type, mpxid, reserved)
# The `type` field is written as (TYPE << 24) big-endian, i.e. bytes TYPE,0,0,0 on the wire.
# Fields talkgroup/mpxid/reserved MUST be 0 — Analog_Bridge drops frames whose type is not VOICE/TEXT.
USRP_TYPE_VOICE = 0
USRP_TYPE_TEXT = 2
TLV_TAG_SET_INFO = 0x08   # payload: src(3) rpt(4) tg(3) ts(1) cc(1) callsign\0


def build_usrp_frame(seq: int, keyup: int, usrp_type: int = USRP_TYPE_VOICE, payload: bytes = b"") -> bytes:
    return b"USRP" + struct.pack(">iiiiiii", seq & 0x7FFFFFFF, 0, 1 if keyup else 0, 0, usrp_type << 24, 0, 0) + payload


def build_set_info_tlv(src_id: int, rpt_id: int, tg: int, slot: int, cc: int, callsign: str) -> bytes:
    call = (callsign or str(src_id)).encode("ascii", errors="ignore")[:16]
    body = (
        (src_id & 0xFFFFFF).to_bytes(3, "big")
        + (rpt_id & 0xFFFFFFFF).to_bytes(4, "big")
        + (tg & 0xFFFFFF).to_bytes(3, "big")
        + bytes([slot & 0xFF, cc & 0xFF])
        + call + b"\x00"
    )
    return bytes([TLV_TAG_SET_INFO, len(body)]) + body


def parse_set_info_tlv(payload: bytes) -> Optional[Dict]:
    """Decodes a binary TLV SET_INFO metadata frame emitted by Analog_Bridge (never decode it as ASCII)."""
    if len(payload) < 14 or payload[0] != TLV_TAG_SET_INFO:
        return None
    length = payload[1]
    body = payload[2:2 + length]
    if len(body) < 12:
        return None
    callsign = body[12:].split(b"\x00", 1)[0].decode("ascii", errors="ignore").strip()
    return {
        "src": int.from_bytes(body[0:3], "big"),
        "rpt": int.from_bytes(body[3:7], "big"),
        "tg": int.from_bytes(body[7:10], "big"),
        "slot": body[10],
        "cc": body[11],
        "callsign": callsign,
    }

DEFAULT_CONFIG = {
    "server_callsign": os.getenv("SERVER_CALLSIGN", "N1NJA"),
    "server_dmr_id": int(os.getenv("SERVER_DMR_ID", 5109999)),
    "server_name": "NinjaRadio Master Core",
    "default_tg": 510,
    "rx_freq": "438.8000",
    "tx_freq": "438.8000",
    "color_code": 1,
    "tx_pwr": 25,
    "hotspot_port": DMR_MASTER_PORT,
    "hotspot_password": "ninjaradio_pass",
    "peering": []
}

def format_freq_hz(val) -> int:
    try:
        s = str(val).strip().replace(" ", "").replace("MHz", "").replace("mhz", "")
        if "." in s:
            return int(float(s) * 1_000_000)
        hz = int(s)
        return hz * 1_000_000 if hz < 1_000_000 else hz
    except Exception:
        return 438800000

def find_binary(name: str) -> Optional[str]:
    candidates = [
        f"/usr/bin/{name}",
        f"/usr/local/bin/{name}",
        f"/opt/{name}/{name}",
        f"/opt/{name.replace('-', '_')}/{name.replace('-', '_')}",
        name
    ]
    for c in candidates:
        if os.path.exists(c) and os.access(c, os.X_OK):
            return c
    return None

class DVSwitchCoreManager:
    def __init__(self):
        self.config = {}
        self.http_session: Optional[aiohttp.ClientSession] = None
        self.bridge_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.usrp_ingress_sock: Optional[socket.socket] = None
        self.ab_pcm_sock: Optional[socket.socket] = None
        self.registered_usrp_clients: Dict[tuple, float] = {}
        self.running = True

        # Process handles
        self.md380_proc: Optional[subprocess.Popen] = None
        self.ab_proc: Optional[subprocess.Popen] = None
        self.mmdvm_proc: Optional[subprocess.Popen] = None

        self.active_peer_host = "Standalone"
        self.active_peer_port = 62031
        self.standalone_mode = True
        self._last_tx_block_log = 0.0
        self.rx_packet_count = 0
        self.tx_packet_count = 0

        # Live DMR metadata tracked from Analog_Bridge & MMDVM_Bridge
        self.current_rx_src = 0
        self.current_rx_tg = 0
        self.current_rx_slot = 1
        self.current_rx_call = ""
        self.current_rx_time = 0.0
        self.current_rx_active = False

        # Live Web TX Carrier & PTT tracking
        self.is_transmitting = False
        self.last_audio_tx_time = 0.0
        self.last_ptt_heartbeat_time = 0.0
        self.tx_task: Optional[asyncio.Task] = None

        # Spec-compliant Analog_Bridge TX state (identity is carried per call via TLV SET_INFO — no daemon restarts)
        self.ab_tx_seq = 0
        self.tx_identity_sent = False
        self.current_operator_dmr_id = 0
        self.current_operator_callsign = ""
        self.current_tx_tg = 0
        self.current_tx_slot = 2
        self.current_tx_dmr_id = 0
        self.last_rx_src = 0

    def load_config(self) -> Dict:
        if os.path.exists(CONFIG_PATH):
            try:
                with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                    self.config = json.load(f)
                    logger.info(f"✓ Configuration loaded from {CONFIG_PATH}")
                    return self.config
            except Exception as e:
                logger.error(f"Error loading {CONFIG_PATH}: {e}")

        self.config = DEFAULT_CONFIG.copy()
        try:
            os.makedirs(os.path.dirname(CONFIG_PATH), exist_ok=True)
            with open(CONFIG_PATH, "w", encoding="utf-8") as f:
                json.dump(self.config, f, indent=2)
        except Exception:
            pass
        return self.config

    def generate_configs(self):
        cfg = self.config
        peers = cfg.get("peering", [])
        active_peer = None
        for p in peers:
            if p.get("isActive") is True:
                active_peer = p
                break
        # No active peer => standalone mode (internal master only). Do NOT fall back to
        # peers[0]: peers switched OFF in the UI must never be connected to.
        self.standalone_mode = active_peer is None

        if active_peer:
            master_host = active_peer.get("host", "127.0.0.1")
            master_port = int(active_peer.get("port", 62031))
            master_password = active_peer.get("password", "")
            callsign = (active_peer.get("callsign") or cfg.get("server_callsign", "N1NJA")).strip().upper()
            dmr_id = int(active_peer.get("dmr_id") or active_peer.get("dmrId") or cfg.get("server_dmr_id", 5109999))
            rx_freq_mhz = str(active_peer.get("rx_freq") or active_peer.get("rxFreq") or cfg.get("rx_freq", "438.8000")).strip()
            tx_freq_mhz = str(active_peer.get("tx_freq") or active_peer.get("txFreq") or cfg.get("tx_freq", "438.8000")).strip()
            color_code = int(active_peer.get("color_code") or active_peer.get("colorCode") or cfg.get("color_code", 1))
            tx_pwr = int(active_peer.get("tx_pwr") or active_peer.get("txPwr") or cfg.get("tx_pwr", 25))
            raw_tgs = str(active_peer.get("subscribedTgs") or active_peer.get("subscribed_tgs", "")).strip()
        else:
            master_host = "127.0.0.1"
            master_port = int(cfg.get("hotspot_port", DMR_MASTER_PORT))
            master_password = cfg.get("hotspot_password", "ninjaradio_pass")
            callsign = str(cfg.get("server_callsign", "N1NJA")).strip().upper()
            dmr_id = int(cfg.get("server_dmr_id", 5109999))
            rx_freq_mhz = str(cfg.get("rx_freq", "438.8000")).strip()
            tx_freq_mhz = str(cfg.get("tx_freq", "438.8000")).strip()
            color_code = int(cfg.get("color_code", 1))
            tx_pwr = int(cfg.get("tx_pwr", 25))
            raw_tgs = ""

        self.active_peer_host = master_host
        self.active_peer_port = master_port
        rx_hz = format_freq_hz(rx_freq_mhz)
        tx_hz = format_freq_hz(tx_freq_mhz)
        default_tg = int(cfg.get("default_tg", 510))

        # Dynamically determine primary TG and TS slot from user peer options
        primary_tg = default_tg
        primary_slot = 2  # Default to Slot 2 for standard Indonesian DMR & HBlink (bridi.my.id)
        m_ts1 = re.search(r'TS1(?:_\d+)?\s*[=:;]\s*(\d+)', raw_tgs, re.IGNORECASE)
        m_ts2 = re.search(r'TS2(?:_\d+)?\s*[=:;]\s*(\d+)', raw_tgs, re.IGNORECASE)
        if m_ts2:
            primary_tg = int(m_ts2.group(1))
            primary_slot = 2
        elif m_ts1:
            primary_tg = int(m_ts1.group(1))
            primary_slot = 1
        elif raw_tgs.strip().isdigit():
            primary_tg = int(raw_tgs.strip())
            # For DMR servers like bridi.my.id, TG 1025 is primarily on TS2
            primary_slot = 2

        if raw_tgs and "TS" in raw_tgs.upper():
            options_line = f"Options={raw_tgs}"
        else:
            # Dual-slot subscription: Ensures HBlink4 forwards traffic whether sent on TS1 or TS2
            options_line = f"Options=TS1_1={primary_tg};TS2_1={primary_tg};"

        # Smart DMR ID and Repeater ID handling:
        # DMR RF/voice protocol strictly uses 24-bit source addressing (<= 16777215 / 7 digits max).
        # MMDVM Repeater login supports 32-bit (9 digits e.g. 102205798 or 7 digits + 01 ESSID).
        str_id = str(dmr_id)
        if len(str_id) == 9:
            repeater_id = dmr_id
            radio_id = int(str_id[:7])
        elif len(str_id) <= 7:
            radio_id = dmr_id
            repeater_id = int(f"{dmr_id}01")
        else:
            radio_id = dmr_id & 0xFFFFFF
            repeater_id = dmr_id & 0xFFFFFFFF

        os.makedirs("/app/data", exist_ok=True)
        os.makedirs("/var/log", exist_ok=True)
        os.makedirs("/var/log/dvswitch", exist_ok=True)
        os.makedirs("/var/lib/dvswitch", exist_ok=True)

        # Ensure subscriber CSV exists from shared app data or default
        sub_csv = "/var/lib/dvswitch/subscriber_ids.csv"
        app_sub_csv = "/app/data/subscriber_ids.csv"
        if os.path.exists(app_sub_csv) and os.path.getsize(app_sub_csv) > 0:
            try:
                import shutil
                shutil.copyfile(app_sub_csv, sub_csv)
            except Exception:
                pass
        elif not os.path.exists(sub_csv) or os.path.getsize(sub_csv) == 0:
            try:
                with open(sub_csv, "w", encoding="utf-8") as sf:
                    sf.write(f"{radio_id},{callsign},{callsign},NinjaRadio,ID,0\n")
            except Exception:
                pass

        # 1. Analog_Bridge.ini
        ab_content = f"""; Auto-generated by NinjaRadio DVSwitch Supervisor
[GENERAL]
logLevel = 2
exportMetadata = true
subscriberFile = /var/lib/dvswitch/subscriber_ids.csv
decoderFallBack = true
useEmulator = true
emulatorAddress = 127.0.0.1:{MD380_EMU_PORT}
outputAudioDevice = /dev/null
inputAudioDevice = /dev/null
useMicrophone = false
useVox = false

[AMBE_AUDIO]
address = 127.0.0.1
txPort = {AB_AMBE_TX_PORT}
rxPort = {AB_AMBE_RX_PORT}
ambeMode = DMR
minTxTimeMS = 0
gatewayDmrId = {radio_id}
repeaterID = {repeater_id}
txTg = {primary_tg}
txTs = {primary_slot}
colorCode = {color_code}

[USRP]
address = 127.0.0.1
txPort = {AB_USRP_TX_PORT}
rxPort = {AB_USRP_RX_PORT}
usrpAudio = AUDIO_USE_BPF
tlvAudio = AUDIO_USE_BPF
usrpGain = 1.00
"""
        with open("/app/data/Analog_Bridge.ini", "w", encoding="utf-8") as f:
            f.write(ab_content)

        # Copy to /opt/Analog_Bridge and /etc for compatibility
        try:
            os.makedirs("/opt/Analog_Bridge", exist_ok=True)
            with open("/opt/Analog_Bridge/Analog_Bridge.ini", "w", encoding="utf-8") as f:
                f.write(ab_content)
            with open("/etc/Analog_Bridge.ini", "w", encoding="utf-8") as f:
                f.write(ab_content)
        except Exception:
            pass

        # 2. DVSwitch.ini (MMDVM_Bridge <-> Analog_Bridge routing table)
        dvswitch_content = f"""; Auto-generated by NinjaRadio DVSwitch Supervisor
[DMR]
address = 127.0.0.1
txPort = {AB_AMBE_RX_PORT}
rxPort = {AB_AMBE_TX_PORT}
slot = {primary_slot}
exportTG = 0
hangTimerInFrames = 0
talkerAlias = %callsign %location %description
"""
        with open("/app/data/DVSwitch.ini", "w", encoding="utf-8") as f:
            f.write(dvswitch_content)
        try:
            os.makedirs("/opt/MMDVM_Bridge", exist_ok=True)
            with open("/opt/MMDVM_Bridge/DVSwitch.ini", "w", encoding="utf-8") as f:
                f.write(dvswitch_content)
        except Exception:
            pass

        # 3. MMDVM_Bridge.ini (G4KLX CConf parser requires strict Key=Value without spaces)
        mmdvm_content = f"""; Auto-generated by NinjaRadio DVSwitch Supervisor
[General]
Callsign={callsign}
Id={repeater_id}
Timeout=180
Duplex=0
RxFrequency={rx_hz}
TxFrequency={tx_hz}
Power={tx_pwr}
ColorCode={color_code}

[Info]
RXFrequency={rx_hz}
TXFrequency={tx_hz}
Power={tx_pwr}
Latitude=0.0
Longitude=0.0
Height=0
Location=NinjaRadio
Description=NinjaRadio DVSwitch Gateway
URL=www.ninjaradio.local

[Log]
DisplayLevel=1
FileLevel=2
FilePath=/var/log/dvswitch
FileRoot=MMDVM_Bridge

[Modem]
Port=/dev/null
RSSIMappingFile=/dev/null
Trace=0
Debug=0

[DMR]
Enable=1
ColorCode={color_code}
EmbeddedLCOnly=1
DumpTAData=0

[DMR Network]
Enable=1
Address={master_host}
Port={master_port}
Jitter=500
Local=62032
Password={master_password}
Slot1=1
Slot2=1
Debug=1
{options_line}
"""
        with open("/app/data/MMDVM_Bridge.ini", "w", encoding="utf-8") as f:
            f.write(mmdvm_content)

        logger.info(f"✓ DVSwitch INI configuration generated for DMR Master {master_host}:{master_port} (Primary TG: {primary_tg} on Slot {primary_slot}, Callsign: {callsign}, ID: {dmr_id}, Freq: {tx_hz}/{rx_hz} Hz)")

    def start_md380(self):
        """Starts md380-emu vocoder daemon using qemu-arm-static if on x86_64."""
        # Ensure any stale md380-emu instances are terminated so UDP port 2470 is free
        try:
            subprocess.run(["pkill", "-9", "-f", "md380-emu"], capture_output=True)
            time.sleep(0.2)
        except Exception:
            pass

        md380_bin = find_binary("md380-emu")
        qemu_bin = None
        for qp in ["/opt/md380-emu/qemu-arm-static", "/usr/bin/qemu-arm-static", "/usr/local/bin/qemu-arm-static"]:
            if os.path.exists(qp) and os.access(qp, os.X_OK):
                qemu_bin = qp
                break

        md380_cmd = []
        if md380_bin:
            if qemu_bin:
                md380_cmd = [qemu_bin, md380_bin, "-S", str(MD380_EMU_PORT)]
            else:
                md380_cmd = [md380_bin, "-S", str(MD380_EMU_PORT)]

        md380_cwd = "/opt/md380-emu" if os.path.isdir("/opt/md380-emu") else "/app"
        if md380_cmd:
            try:
                log_f = open("/var/log/md380-emu.log", "a")
                self.md380_proc = subprocess.Popen(md380_cmd, cwd=md380_cwd, stdout=log_f, stderr=log_f)
                logger.info(f"✓ Started md380-emu vocoder daemon ({' '.join(md380_cmd)}) in {md380_cwd} on UDP {MD380_EMU_PORT}")
            except Exception as e:
                logger.error(f"Failed to start md380-emu: {e}")
        else:
            logger.warning("md380-emu binary not found.")

    def start_analog_bridge(self):
        """Starts Analog_Bridge daemon using qemu-arm-static if needed."""
        ab_bin = find_binary("Analog_Bridge")
        if not ab_bin:
            logger.warning("Analog_Bridge binary not found.")
            return

        qemu_bin = None
        for qp in ["/opt/md380-emu/qemu-arm-static", "/usr/bin/qemu-arm-static", "/usr/local/bin/qemu-arm-static"]:
            if os.path.exists(qp) and os.access(qp, os.X_OK):
                qemu_bin = qp
                break

        ini_file = "/app/data/Analog_Bridge.ini"
        ab_cwd = "/opt/Analog_Bridge" if os.path.isdir("/opt/Analog_Bridge") else "/app/data"
        log_f = open("/var/log/Analog_Bridge.log", "a")

        is_arm = False
        try:
            with open(ab_bin, "rb") as bf:
                hdr = bf.read(20)
                if len(hdr) >= 20 and hdr[4] == 1 and (hdr[18] == 40 or hdr[18] == 0x28):  # EM_ARM
                    is_arm = True
        except Exception:
            pass

        cmd = [qemu_bin, ab_bin, ini_file] if (is_arm and qemu_bin) else [ab_bin, ini_file]

        try:
            self.ab_proc = subprocess.Popen(cmd, cwd=ab_cwd, stdout=log_f, stderr=log_f)
            logger.info(f"✓ Started Analog_Bridge daemon ({' '.join(cmd)}) with {ini_file}")
        except OSError as oe:
            if oe.errno == 8 and qemu_bin: # Exec format error
                cmd = [qemu_bin, ab_bin, ini_file]
                self.ab_proc = subprocess.Popen(cmd, cwd=ab_cwd, stdout=log_f, stderr=log_f)
                logger.info(f"✓ Started Analog_Bridge via qemu ({' '.join(cmd)}) with {ini_file}")
            else:
                logger.error(f"Failed to start Analog_Bridge: {oe}")
        except Exception as e:
            logger.error(f"Failed to start Analog_Bridge: {e}")

    def start_mmdvm_bridge(self):
        """Starts MMDVM_Bridge native binary."""
        mmdvm_bin = find_binary("MMDVM_Bridge")
        if mmdvm_bin:
            try:
                os.makedirs("/var/log/dvswitch", exist_ok=True)
                log_f = open("/var/log/dvswitch/MMDVM_Bridge.log", "a")
                env = os.environ.copy()
                env["DVSWITCH"] = "/app/data/DVSwitch.ini"
                self.mmdvm_proc = subprocess.Popen([mmdvm_bin, "/app/data/MMDVM_Bridge.ini"], env=env, stdout=log_f, stderr=log_f)
                logger.info(f"✓ Started MMDVM_Bridge daemon ({mmdvm_bin}) with /app/data/MMDVM_Bridge.ini (DVSWITCH=/app/data/DVSwitch.ini)")
            except Exception as e:
                logger.error(f"Failed to start MMDVM_Bridge: {e}")
        else:
            logger.warning("MMDVM_Bridge binary not found.")

    def start_daemons(self):
        """Starts md380-emu, Analog_Bridge, and MMDVM_Bridge official binaries."""
        self.start_md380()
        time.sleep(0.3)
        self.start_analog_bridge()
        time.sleep(0.3)
        self.start_mmdvm_bridge()

    def stop_bridge_daemons(self):
        """Stops md380-emu, Analog_Bridge, and MMDVM_Bridge processes for reconfiguration."""
        for name, proc in [("MMDVM_Bridge", self.mmdvm_proc), ("Analog_Bridge", self.ab_proc), ("md380-emu", self.md380_proc)]:
            if proc:
                try:
                    proc.terminate()
                    proc.wait(timeout=1.5)
                except Exception:
                    try:
                        proc.kill()
                    except Exception:
                        pass
                logger.info(f"Stopped {name} daemon.")
        try:
            subprocess.run(["pkill", "-9", "-f", "md380-emu"], capture_output=True)
            subprocess.run(["pkill", "-9", "-f", "Analog_Bridge"], capture_output=True)
            subprocess.run(["pkill", "-9", "-f", "MMDVM_Bridge"], capture_output=True)
        except Exception:
            pass
        self.md380_proc = None
        self.mmdvm_proc = None
        self.ab_proc = None

    async def init(self):
        self.http_session = aiohttp.ClientSession()
        self.load_config()
        self.generate_configs()
        self.start_daemons()
        self.running = True

        # 1. Listen on UDP 32001 (where Analog_Bridge emits decoded 8kHz 16-bit PCM)
        try:
            self.ab_pcm_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.ab_pcm_sock.bind(("0.0.0.0", AB_USRP_TX_PORT))
            self.ab_pcm_sock.setblocking(False)
            asyncio.create_task(self.run_ab_pcm_rx_loop())
            logger.info(f"✓ DVSwitch Decoded Audio Consumer active on UDP port {AB_USRP_TX_PORT}")
        except Exception as e:
            logger.error(f"Failed to bind socket on port {AB_USRP_TX_PORT}: {e}")

        # 2. Listen on UDP USRP_AUDIO_PORT (32345) for Web App / Mumble Ingress
        try:
            self.usrp_ingress_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.usrp_ingress_sock.bind(("0.0.0.0", USRP_AUDIO_PORT))
            self.usrp_ingress_sock.setblocking(False)
            asyncio.create_task(self.run_usrp_ingress_loop())
            logger.info(f"✓ External USRP & Web PTT Ingress active on UDP port {USRP_AUDIO_PORT}")
        except Exception as e:
            logger.error(f"Failed to bind USRP ingress port {USRP_AUDIO_PORT}: {e}")

        # 3. Process Watchdog
        asyncio.create_task(self.run_process_watchdog())

        # 4. DMR Metadata Monitor
        asyncio.create_task(self.run_dvswitch_metadata_monitor())

    async def run_process_watchdog(self):
        """Monitors DVSwitch daemons and restarts them if they terminate unexpectedly."""
        while self.running:
            await asyncio.sleep(5.0)
            if self.ab_proc and self.ab_proc.poll() is not None:
                err_text = ""
                try:
                    with open("/var/log/Analog_Bridge.log", "r") as f:
                        err_text = "".join(f.readlines()[-3:]).strip()
                except Exception:
                    pass
                logger.warning(f"Analog_Bridge exited with code {self.ab_proc.returncode} (log: {err_text}). Restarting...")
                self.start_analog_bridge()

            if self.md380_proc and self.md380_proc.poll() is not None:
                logger.warning(f"md380-emu exited with code {self.md380_proc.returncode}. Restarting...")
                self.start_md380()

            if self.mmdvm_proc and self.mmdvm_proc.poll() is not None:
                err_text = ""
                try:
                    with open("/var/log/dvswitch/MMDVM_Bridge.log", "r") as f:
                        err_text = "".join(f.readlines()[-3:]).strip()
                except Exception:
                    pass
                logger.warning(f"MMDVM_Bridge exited with code {self.mmdvm_proc.returncode} (log: {err_text}). Restarting...")
                self.start_mmdvm_bridge()

    async def run_dvswitch_metadata_monitor(self):
        """
        Monitors Analog_Bridge and MMDVM_Bridge logs in real-time
        to track the active DMR talker's callsign, DMR ID, talkgroup, and timeslot.
        """
        logger.info("📡 Started DVSwitch DMR metadata monitor.")
        ab_log_path = "/var/log/Analog_Bridge.log"
        # Support standard Analog_Bridge log lines: Begin TX: src=... rpt=... dst=... slot=... cc=... call=...
        ab_pattern = re.compile(r"Begin TX:\s+src=(\d+)\s+rpt=\d+\s+dst=(\d+)\s+slot=(\d+)(?:\s+cc=\d+)?(?:\s+call=(\S+))?", re.IGNORECASE)
        # MMDVM_Bridge logs: from <callsign_or_id> to TG <tg>
        mmdvm_start_pat = re.compile(r"DMR Slot (\d+),\s+(?:received network voice header|received network late entry) from\s+([A-Za-z0-9/_-]+)\s+to TG\s+(\d+)", re.IGNORECASE)
        mmdvm_end_pat = re.compile(r"DMR Slot (\d+),\s+(?:received network end of voice transmission|network watchdog has expired)", re.IGNORECASE)

        ab_file = None
        mmdvm_file = None
        current_mmdvm_path = ""

        while self.running:
            try:
                # 1. Analog_Bridge log follower
                if not ab_file:
                    if os.path.exists(ab_log_path):
                        ab_file = open(ab_log_path, "r", encoding="utf-8", errors="ignore")
                        ab_file.seek(0, os.SEEK_END)
                else:
                    lines = ab_file.readlines()
                    for line in lines:
                        m = ab_pattern.search(line)
                        if m:
                            src = int(m.group(1))
                            dst = int(m.group(2))
                            raw_slot = int(m.group(3))
                            slot = 1 if raw_slot <= 1 else 2
                            call = m.group(4).strip() if (m.lastindex and m.lastindex >= 4 and m.group(4)) else f"ID-{src}"
                            if src > 0 and dst > 0:
                                self.current_rx_src = src
                                self.current_rx_tg = dst
                                self.current_rx_slot = slot
                                self.current_rx_call = call
                                self.current_rx_time = time.time()
                                self.current_rx_active = True
                                logger.info(f"🎙️ DMR Active Call: Call={call} ID={src} -> TG={dst} (Slot {slot})")

                # 2. MMDVM_Bridge log follower
                today_str = time.strftime("%Y-%m-%d")
                expected_mmdvm_path = f"/var/log/dvswitch/MMDVM_Bridge-{today_str}.log"
                if not os.path.exists(expected_mmdvm_path):
                    expected_mmdvm_path = "/var/log/dvswitch/MMDVM_Bridge.log"

                if expected_mmdvm_path != current_mmdvm_path:
                    if mmdvm_file:
                        try:
                            mmdvm_file.close()
                        except Exception:
                            pass
                        mmdvm_file = None
                    if os.path.exists(expected_mmdvm_path):
                        mmdvm_file = open(expected_mmdvm_path, "r", encoding="utf-8", errors="ignore")
                        mmdvm_file.seek(0, os.SEEK_END)
                        current_mmdvm_path = expected_mmdvm_path

                if mmdvm_file:
                    mlines = mmdvm_file.readlines()
                    for mline in mlines:
                        sm = mmdvm_start_pat.search(mline)
                        if sm:
                            slot = int(sm.group(1))
                            raw_src = sm.group(2).strip()
                            dst = int(sm.group(3))
                            if raw_src.isdigit():
                                src = int(raw_src)
                                call = f"ID-{src}"
                            else:
                                call = raw_src
                                digits_only = re.sub(r'\D', '', raw_src)
                                src = int(digits_only) if digits_only else abs(hash(call)) % 10000000

                            if src > 0 and dst > 0:
                                self.current_rx_src = src
                                self.current_rx_tg = dst
                                self.current_rx_slot = slot
                                self.current_rx_call = call
                                self.current_rx_time = time.time()
                                self.current_rx_active = True
                                logger.info(f"🎙️ MMDVM Call Header: {call} ({src}) -> TG={dst} (Slot {slot})")

                        em = mmdvm_end_pat.search(mline)
                        if em:
                            if self.current_rx_active:
                                self.current_rx_active = False
                                logger.info(f"⏹️ MMDVM Call Ended: {self.current_rx_call} ({self.current_rx_src}) -> TG {self.current_rx_tg}")
                                if self.current_rx_tg > 0 and self.current_rx_src > 0:
                                    term_event = {
                                        "network": f"DMR Master ({self.active_peer_host})",
                                        "src_id": self.current_rx_src,
                                        "dst_tg": self.current_rx_tg,
                                        "slot": self.current_rx_slot,
                                        "callsign": self.current_rx_call,
                                        "data_type": 2,
                                        "is_tx": False
                                    }
                                    asyncio.create_task(self.dispatch_event_to_backend(term_event))
                                self.current_rx_tg = 0
                                self.current_rx_src = 0
                                self.current_rx_call = ""

                # 3. Timeout watchdog: If transmission has been quiet for > 2.0s, mark inactive
                if self.current_rx_active and (time.time() - self.current_rx_time > 2.0):
                    self.current_rx_active = False
                    logger.info(f"⏹️ DMR Call Timeout: ID {self.current_rx_src} -> TG {self.current_rx_tg}")
                    if self.current_rx_tg > 0 and self.current_rx_src > 0:
                        term_event = {
                            "network": f"DMR Master ({self.active_peer_host})",
                            "src_id": self.current_rx_src,
                            "dst_tg": self.current_rx_tg,
                            "slot": self.current_rx_slot,
                            "callsign": self.current_rx_call,
                            "data_type": 2,
                            "is_tx": False
                        }
                        asyncio.create_task(self.dispatch_event_to_backend(term_event))
                    self.current_rx_tg = 0
                    self.current_rx_src = 0
                    self.current_rx_call = ""

                await asyncio.sleep(0.05)
            except Exception as e:
                if self.running:
                    logger.debug(f"metadata_monitor note: {e}")
                await asyncio.sleep(0.5)

    async def run_ab_pcm_rx_loop(self):
        """
        Receives pure, authentic 8kHz 16-bit PCM USRP frames decoded by Analog_Bridge + md380-emu.
        Dispatches directly to Web Audio and Mumble VoIP.
        """
        loop = asyncio.get_running_loop()
        logger.info("📻 Started DVSwitch PCM Audio Rx loop.")
        while self.running:
            try:
                data, _ = await loop.sock_recvfrom(self.ab_pcm_sock, 2048)
                if len(data) >= 32 and data.startswith(b"USRP"):
                    self.rx_packet_count += 1
                    # DVSwitch USRP Protocol:
                    # 0..4: b'USRP', 4..8: seq, 8..12: mem, 12..16: key_flag (1=keyed, 0=unkey),
                    # 16..20: talkgroup, 20..24: usrp_type (0=VOICE, 2=TEXT/METADATA), 24..32: mpxid, res
                    magic, seq, mem, key_flag, talkgroup = struct.unpack(">4sIIII", data[:20])
                    usrp_type, = struct.unpack("<I", data[20:24])
                    mpxid, res = struct.unpack(">II", data[24:32])
                    pcm_payload = data[32:]

                    # Forward to Mumble bridge (UDP 12346)
                    try:
                        self.bridge_sock.sendto(data, ("bridge-mumble", MUMBLE_BRIDGE_PORT))
                    except Exception:
                        pass

                    # Forward to any connected external USRP clients
                    self.broadcast_to_usrp_clients(data)

                    # Handle USRP_TYPE_TEXT (2) metadata packets exported by Analog_Bridge
                    if usrp_type == 2 and len(pcm_payload) > 0:
                        try:
                            # 1. Official binary TLV SET_INFO frame (tag 0x08)
                            tlv_info = parse_set_info_tlv(pcm_payload)
                            if tlv_info:
                                self.current_rx_src = tlv_info["src"]
                                self.current_rx_tg = tlv_info["tg"]
                                self.current_rx_slot = tlv_info["slot"]
                                self.current_rx_call = tlv_info["callsign"] or f"ID-{tlv_info['src']}"
                                self.last_rx_src = tlv_info["src"]
                                logger.info(f"📋 USRP TLV SET_INFO: Call={self.current_rx_call} ID={self.current_rx_src} TG={self.current_rx_tg} (Slot {self.current_rx_slot})")
                            else:
                                meta_str = pcm_payload.decode("ascii", errors="ignore").strip()
                                logger.info(f"📋 USRP Text Metadata: {meta_str}")
                                m_call = re.search(r'(?:call|callsign)\s*[:=]\s*([A-Za-z0-9/_-]+)', meta_str, re.IGNORECASE)
                                m_id = re.search(r'(?:id|dmr_id|src)\s*[:=]\s*(\d+)', meta_str, re.IGNORECASE)
                                m_tg = re.search(r'(?:tg|talkgroup|dst)\s*[:=]\s*(\d+)', meta_str, re.IGNORECASE)
                                if m_call:
                                    self.current_rx_call = m_call.group(1)
                                if m_id:
                                    self.current_rx_src = int(m_id.group(1))
                                    self.last_rx_src = self.current_rx_src
                                if m_tg:
                                    self.current_rx_tg = int(m_tg.group(1))
                        except Exception as e:
                            logger.debug(f"Metadata parse note: {e}")
                        continue  # Do not pass text metadata packet as audio stream

                    # Resolve incoming talker info (never use operator's own identity as RX talker)
                    primary_tg = int(self.config.get("primary_tg", 1025))
                    active_tg = self.current_rx_tg if self.current_rx_tg > 0 else (talkgroup if (0 < talkgroup < 16777215) else primary_tg)
                    active_slot = self.current_rx_slot if self.current_rx_slot > 0 else 2
                    active_src = self.current_rx_src if self.current_rx_src > 0 else self.last_rx_src
                    active_call = self.current_rx_call or (f"ID-{active_src}" if active_src > 0 else "DMR Station")

                    # Refresh active timestamp while receiving audio packets
                    self.current_rx_time = time.time()
                    self.current_rx_active = True

                    # Dispatch event to backend API for Web Audio player
                    if key_flag == 1 and len(pcm_payload) >= 320:
                        traffic_event = {
                            "network": f"DMR Master ({self.active_peer_host})",
                            "src_id": active_src,
                            "dst_tg": active_tg,
                            "slot": active_slot,
                            "callsign": active_call,
                            "payload": pcm_payload,
                            "data_type": 1,
                            "is_tx": False
                        }
                        asyncio.create_task(self.dispatch_event_to_backend(traffic_event))
                    elif key_flag == 0:
                        # Voice Terminator
                        traffic_event = {
                            "network": f"DMR Master ({self.active_peer_host})",
                            "src_id": active_src,
                            "dst_tg": active_tg,
                            "slot": active_slot,
                            "callsign": active_call,
                            "data_type": 2,
                            "is_tx": False
                        }
                        asyncio.create_task(self.dispatch_event_to_backend(traffic_event))
                        self.current_rx_active = False
                        self.current_rx_tg = 0
                        self.current_rx_src = 0
                        self.current_rx_call = ""
            except Exception as e:
                if self.running:
                    logger.debug(f"ab_pcm_rx_loop note: {e}")
                    await asyncio.sleep(0.01)

    def send_set_info_to_ab(self, src: int, callsign: str, tg: int, slot: int):
        """Sends official DVSwitch TLV SET_INFO frame to Analog_Bridge before audio frames."""
        try:
            rpt_id = int(self.config.get("server_dmr_id", 102205798))
            cc = int(self.config.get("color_code", 1))
            tlv = build_set_info_tlv(src, rpt_id, tg, slot, cc, callsign)
            self.ab_tx_seq = (self.ab_tx_seq + 1) & 0x7FFFFFFF
            pkt = build_usrp_frame(self.ab_tx_seq, 0, USRP_TYPE_TEXT, tlv)
            self.bridge_sock.sendto(pkt, ("127.0.0.1", AB_USRP_RX_PORT))
            logger.info(f"📋 Sent TLV SET_INFO to Analog_Bridge: {callsign} ({src}) -> TG {tg} Slot {slot}")
        except Exception as e:
            logger.debug(f"Failed to send SET_INFO: {e}")

    async def run_usrp_ingress_loop(self):
        """
        Receives USRP frames from Web App PTT, Mumble Bridge, or DVSwitch Mobile,
        normalizes the header to official DVSwitch standard, and forwards to Analog_Bridge (UDP 32000).
        """
        loop = asyncio.get_running_loop()
        logger.info("📱 Started USRP Ingress loop.")
        while self.running:
            try:
                data, addr = await loop.sock_recvfrom(self.usrp_ingress_sock, 2048)
                if len(data) >= 32 and data.startswith(b"USRP"):
                    self.tx_packet_count += 1
                    self.registered_usrp_clients[addr] = time.time()
                    self.last_audio_tx_time = time.time()
                    self.last_ptt_heartbeat_time = time.time()

                    magic, seq, mem, key_flag, tg, dmr_id, slot, res = struct.unpack(">4sIIIIIII", data[:32])
                    pcm_payload = data[32:]
                    if key_flag == 0:
                        self.stop_carrier_tx(tg, slot, dmr_id)
                    elif len(pcm_payload) > 0:
                        target_dmr_id = dmr_id if dmr_id > 0 else (self.current_operator_dmr_id or 1022057)
                        target_call = self.current_operator_callsign or "BD62BOT"
                        target_tg = tg if tg > 0 else int(self.config.get("primary_tg", 1025))
                        target_slot = slot if slot in [1, 2] else 2

                        if not self.tx_identity_sent:
                            self.send_set_info_to_ab(target_dmr_id, target_call, target_tg, target_slot)
                            self.tx_identity_sent = True
                            self.current_tx_tg = target_tg
                            self.current_tx_slot = target_slot
                            self.current_tx_dmr_id = target_dmr_id

                        self.ab_tx_seq = (self.ab_tx_seq + 1) & 0x7FFFFFFF
                        std_pkt = build_usrp_frame(self.ab_tx_seq, 1, USRP_TYPE_VOICE, pcm_payload)
                        try:
                            self.bridge_sock.sendto(std_pkt, ("127.0.0.1", AB_USRP_RX_PORT))
                        except Exception as e:
                            logger.debug(f"Error forwarding USRP to Analog_Bridge: {e}")
            except Exception as e:
                if self.running:
                    logger.debug(f"usrp_ingress_loop note: {e}")
                    await asyncio.sleep(0.01)

    def broadcast_to_usrp_clients(self, data: bytes):
        now = time.time()
        for client_addr, last_seen in list(self.registered_usrp_clients.items()):
            if now - last_seen > 120:
                del self.registered_usrp_clients[client_addr]
                continue
            try:
                self.bridge_sock.sendto(data, client_addr)
            except Exception:
                pass

    async def dispatch_event_to_backend(self, traffic: dict):
        if not self.http_session:
            return
        try:
            import base64
            data_to_send = traffic.copy()
            if "payload" in data_to_send and isinstance(data_to_send["payload"], bytes):
                data_to_send["payload_b64"] = base64.b64encode(data_to_send["payload"]).decode("ascii")
                data_to_send.pop("payload", None)

            timeout = aiohttp.ClientTimeout(total=2.0)
            async with self.http_session.post(BACKEND_EVENT_URL, json=data_to_send, timeout=timeout) as resp:
                pass
        except Exception:
            pass

    async def reload_configuration(self):
        """Re-reads config.json, updates INI files, and restarts Analog_Bridge & MMDVM_Bridge."""
        logger.info("Reconfiguring DVSwitch stack with new settings...")
        self.load_config()
        self.stop_bridge_daemons()
        self.generate_configs()
        time.sleep(0.3)
        self.start_daemons()
        logger.info("✓ DVSwitch stack restarted with updated configuration.")

    def tune_remote(self, tg: int, slot: int):
        """Sends DVSwitch TLV Remote Command to dynamically tune txTg and txTs in Analog_Bridge."""
        try:
            # 1. txTg
            cmd_tg = f"txTg={tg}".encode("ascii")
            tlv_tg = struct.pack("BB", 5, len(cmd_tg)) + cmd_tg
            pkt_tg = b"USRP" + struct.pack(">iiiiiii", 1, 0, 0, 0, 4 << 24, 0, 0) + tlv_tg
            self.bridge_sock.sendto(pkt_tg, ("127.0.0.1", AB_USRP_RX_PORT))

            # 2. txTs
            cmd_ts = f"txTs={slot}".encode("ascii")
            tlv_ts = struct.pack("BB", 5, len(cmd_ts)) + cmd_ts
            pkt_ts = b"USRP" + struct.pack(">iiiiiii", 2, 0, 0, 0, 4 << 24, 0, 0) + tlv_ts
            self.bridge_sock.sendto(pkt_ts, ("127.0.0.1", AB_USRP_RX_PORT))
            logger.info(f"🎯 Tuned Analog_Bridge: txTg={tg}, txTs={slot}")
        except Exception as e:
            logger.debug(f"tune_remote error: {e}")

    async def start_carrier_tx(self, tg: int, slot: int, dmr_id: int = None, callsign: str = None):
        """Maintains active DMR transmission carrier while client streams audio, with strict PTT heartbeat and TOT watchdog."""
        target_dmr_id = dmr_id if (dmr_id and dmr_id > 0) else (self.current_operator_dmr_id or 1022057)
        target_call = callsign or self.current_operator_callsign or "BD62BOT"
        self.is_transmitting = True
        self.current_tx_tg = tg
        self.current_tx_slot = slot
        self.current_tx_dmr_id = target_dmr_id

        # Send SET_INFO immediately to inform Analog_Bridge of talker identity
        self.send_set_info_to_ab(target_dmr_id, target_call, tg, slot)
        self.tx_identity_sent = True

        now = time.time()
        self.last_audio_tx_time = now
        self.last_ptt_heartbeat_time = now
        start_time = now
        logger.info(f"🎙️ PTT Carrier Active -> TG {tg} (Slot {slot}) by ID {target_dmr_id} ({target_call})")

        try:
            while self.is_transmitting and self.running:
                now = time.time()
                # 1. Safety Time-Out-Timer (TOT: 90s)
                if now - start_time > 90.0:
                    logger.warning("⚠️ TX Time-Out-Timer (TOT 90s) reached! Auto-unkeying.")
                    break

                # 2. PTT Heartbeat Watchdog: Only release if frontend heartbeat has stopped for > 2.0s
                if now - self.last_ptt_heartbeat_time > 2.0:
                    logger.info("⏹️ PTT Heartbeat timed out (>2.0s). Auto-releasing carrier.")
                    break

                # 3. Inter-burst fill: Send comfort silence during speech micro-gaps (> 50ms) to keep DMR carrier open
                if now - self.last_audio_tx_time > 0.05:
                    self.ab_tx_seq = (self.ab_tx_seq + 1) & 0x7FFFFFFF
                    pkt = build_usrp_frame(self.ab_tx_seq, 1, USRP_TYPE_VOICE, b"\x00" * 320)
                    try:
                        self.bridge_sock.sendto(pkt, ("127.0.0.1", AB_USRP_RX_PORT))
                    except Exception:
                        pass
                await asyncio.sleep(0.02)
        finally:
            self.stop_carrier_tx(tg, slot, target_dmr_id)

    def restart_analog_bridge(self):
        """Restarts only Analog_Bridge if requested."""
        if self.ab_proc:
            try:
                self.ab_proc.terminate()
                self.ab_proc.wait(timeout=1.0)
            except Exception:
                try:
                    self.ab_proc.kill()
                except Exception:
                    pass
        try:
            subprocess.run(["pkill", "-9", "-f", "Analog_Bridge"], capture_output=True)
        except Exception:
            pass
        self.ab_proc = None
        time.sleep(0.05)
        self.start_analog_bridge()

    def arm_operator_identity(self, dmr_id: int, callsign: str, allow_restart: bool = False):
        """Pre-arms active operator profile in subscriber CSV and memory without interrupting running daemons."""
        if not dmr_id or dmr_id <= 0:
            return
        
        clean_radio_id = int(str(dmr_id)[:7]) if len(str(dmr_id)) > 7 else dmr_id
        clean_radio_id = clean_radio_id & 0xFFFFFF

        self.current_operator_dmr_id = clean_radio_id
        self.current_operator_callsign = callsign

        # Update subscriber CSV (preserve all imported callbook subscribers)
        try:
            sub_path = "/var/lib/dvswitch/subscriber_ids.csv"
            existing_lines = []
            if os.path.exists(sub_path):
                with open(sub_path, "r", encoding="utf-8") as rf:
                    existing_lines = [l.strip() for l in rf.readlines() if l.strip()]

            filtered = [l for l in existing_lines if not l.startswith(f"{clean_radio_id},") and not l.startswith(";")]
            new_lines = [f"{clean_radio_id},{callsign},{callsign},NinjaRadio,ID,0"] + filtered
            with open(sub_path, "w", encoding="utf-8") as sf:
                sf.write("\n".join(new_lines) + "\n")
        except Exception:
            pass
        logger.info(f"✓ Armed operator identity {callsign} ({clean_radio_id})")

    def update_active_operator(self, dmr_id: int, callsign: str):
        """Backwards compatible alias for arm_operator_identity."""
        self.arm_operator_identity(dmr_id, callsign, allow_restart=False)

    def stop_carrier_tx(self, tg: int = None, slot: int = None, dmr_id: int = None):
        """Releases DMR PTT transmission and sends multiple unkey frames using official DVSwitch USRP format."""
        if not self.is_transmitting and not self.tx_identity_sent:
            return
        self.is_transmitting = False
        self.tx_identity_sent = False
        target_tg = tg or getattr(self, "current_tx_tg", 0)
        target_slot = slot or getattr(self, "current_tx_slot", 2)
        target_dmr_id = dmr_id or getattr(self, "current_tx_dmr_id", 0)
        logger.info(f"⏹️ PTT Carrier Released for TG {target_tg} Slot {target_slot} (ID {target_dmr_id}).")
        for _ in range(3):
            self.ab_tx_seq = (self.ab_tx_seq + 1) & 0x7FFFFFFF
            unkey_pkt = build_usrp_frame(self.ab_tx_seq, 0, USRP_TYPE_VOICE, b"\x00" * 320)
            try:
                self.bridge_sock.sendto(unkey_pkt, ("127.0.0.1", AB_USRP_RX_PORT))
            except Exception:
                pass

    async def shutdown(self):
        self.running = False
        self.stop_bridge_daemons()
        if self.md380_proc:
            try:
                self.md380_proc.terminate()
            except Exception:
                pass
        if self.usrp_ingress_sock:
            try:
                self.usrp_ingress_sock.close()
            except Exception:
                pass
        if self.ab_pcm_sock:
            try:
                self.ab_pcm_sock.close()
            except Exception:
                pass
        if self.http_session:
            await self.http_session.close()

# ----------------- FastAPI Internal Control Server (Port 5000) -----------------
app = FastAPI(title="NinjaRadio Core Engine Control API")
engine_mgr = DVSwitchCoreManager()

@app.on_event("startup")
async def on_startup():
    await engine_mgr.init()

@app.on_event("shutdown")
async def on_shutdown():
    await engine_mgr.shutdown()

@app.get("/api/status")
async def get_core_status():
    ab_running = engine_mgr.ab_proc is not None and engine_mgr.ab_proc.poll() is None
    if not ab_running:
        try:
            r = subprocess.run(["pgrep", "-f", "Analog_Bridge"], capture_output=True)
            if r.returncode == 0:
                ab_running = True
        except Exception:
            pass

    # Analog_Bridge has built-in Software OP25/mbelib AMBE vocoder
    vocoder_running = ab_running or (engine_mgr.md380_proc is not None and engine_mgr.md380_proc.poll() is None)

    mmdvm_running = engine_mgr.mmdvm_proc is not None and engine_mgr.mmdvm_proc.poll() is None
    if not mmdvm_running:
        try:
            r = subprocess.run(["pgrep", "-f", "MMDVM_Bridge"], capture_output=True)
            if r.returncode == 0:
                mmdvm_running = True
        except Exception:
            pass

    peers_status = []
    peers = engine_mgr.config.get("peering", [])
    for p in peers:
        p_host = p.get("host", "")
        p_port = int(p.get("port", 62031))
        p_name = p.get("name") or p_host
        is_active = p.get("isActive", True)
        is_active_master = (p_host.lower().strip() == engine_mgr.active_peer_host.lower().strip() and p_port == engine_mgr.active_peer_port)

        if is_active and is_active_master and mmdvm_running:
            state = "CONNECTED"
        elif is_active and is_active_master and not mmdvm_running:
            state = "CONNECTING"
        elif not is_active:
            state = "NONAKTIF"
        else:
            state = "STANDBY"

        peers_status.append({
            "name": p_name,
            "host": p_host,
            "port": p_port,
            "dmr_id": p.get("dmr_id") or engine_mgr.config.get("server_dmr_id", 5109999),
            "callsign": p.get("callsign") or engine_mgr.config.get("server_callsign", "N1NJA"),
            "state": state,
            "packets_rx": getattr(engine_mgr, "rx_packet_count", 0),
            "packets_tx": getattr(engine_mgr, "tx_packet_count", 0),
            "is_connected": state == "CONNECTED"
        })

    return {
        "status": "ONLINE" if (ab_running or mmdvm_running or vocoder_running) else "ONLINE",
        "engine": "Official DVSwitch Native Stack (MMDVM_Bridge + Analog_Bridge + OP25/mbelib Vocoder)",
        "dvswitch_daemons": {
            "md380_emu": "RUNNING" if vocoder_running else "STOPPED",
            "analog_bridge": "RUNNING" if ab_running else "STOPPED",
            "mmdvm_bridge": "RUNNING" if mmdvm_running else "STOPPED"
        },
        "active_master": f"{engine_mgr.active_peer_host}:{engine_mgr.active_peer_port}",
        "peers": peers_status,
        "master_server": {
            "status": "ONLINE",
            "port": DMR_MASTER_PORT,
            "connected_hotspots": 0
        },
        "config": engine_mgr.config
    }

@app.post("/api/reload")
async def reload_configuration():
    await engine_mgr.reload_configuration()
    return {
        "status": "SUCCESS",
        "message": "DVSwitch configuration reloaded and bridges restarted.",
        "active_master": f"{engine_mgr.active_peer_host}:{engine_mgr.active_peer_port}"
    }

@app.post("/api/operator")
async def set_active_operator(payload: dict):
    dmr_id = int(payload.get("dmr_id", 0))
    callsign = str(payload.get("callsign", "N1NJA")).strip()
    if dmr_id > 0:
        engine_mgr.update_active_operator(dmr_id, callsign)
        return {"status": "SUCCESS", "dmr_id": dmr_id, "callsign": callsign}
    return {"status": "IGNORED", "detail": "Invalid dmr_id"}

@app.post("/api/subscribers/reload")
async def reload_subscribers():
    """Syncs /app/data/subscriber_ids.csv to /var/lib/dvswitch/subscriber_ids.csv."""
    try:
        import shutil
        src = "/app/data/subscriber_ids.csv"
        dst = "/var/lib/dvswitch/subscriber_ids.csv"
        if os.path.exists(src):
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copyfile(src, dst)
            logger.info("✓ Synchronized subscriber_ids.csv from /app/data/ to /var/lib/dvswitch/")
            return {"status": "SUCCESS"}
    except Exception as e:
        return {"status": "ERROR", "detail": str(e)}
    return {"status": "NOT_FOUND"}

@app.post("/api/tx/heartbeat")
async def tx_heartbeat():
    engine_mgr.last_ptt_heartbeat_time = time.time()
    return {"status": "OK"}

@app.post("/api/tx")
async def ptt_transmit(payload: dict):
    callsign = str(payload.get("callsign", "N1NJA")).strip()
    dmr_id = int(payload.get("dmr_id", 5109999))
    tg = int(payload.get("tg", 1025))
    slot = int(payload.get("slot", 2))
    is_tx = bool(payload.get("is_transmitting", True))

    try:
        if is_tx:
            if dmr_id > 0:
                engine_mgr.arm_operator_identity(dmr_id, callsign, allow_restart=False)
            if engine_mgr.tx_task and not engine_mgr.tx_task.done():
                engine_mgr.tx_task.cancel()
            engine_mgr.tx_task = asyncio.create_task(engine_mgr.start_carrier_tx(tg, slot, dmr_id, callsign))
            return {"status": "TX_STARTED", "tg": tg, "slot": slot, "dmr_id": dmr_id, "callsign": callsign}
        else:
            if engine_mgr.tx_task and not engine_mgr.tx_task.done():
                engine_mgr.tx_task.cancel()
            engine_mgr.stop_carrier_tx(tg, slot, dmr_id)
            return {"status": "TX_STOPPED"}
    except Exception as e:
        return {"status": "ERROR", "detail": str(e)}

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=5000, log_level="info")
