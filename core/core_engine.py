import asyncio
import socket
import logging
import struct
import json
import os
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
        """Generates Analog_Bridge.ini and MMDVM_Bridge.ini from live config."""
        cfg = self.config
        peers = cfg.get("peering", [])
        active_peer = None
        for p in peers:
            if p.get("isActive", True):
                active_peer = p
                break

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

        if raw_tgs:
            options_line = f"Options = {raw_tgs}" if "TS" in raw_tgs.upper() else f"Options = TS1_1={raw_tgs};"
        else:
            options_line = f"Options = TS1_1={default_tg};"

        os.makedirs("/app/data", exist_ok=True)
        os.makedirs("/var/log", exist_ok=True)

        # 1. Analog_Bridge.ini
        ab_content = f"""; Auto-generated by NinjaRadio DVSwitch Supervisor
[GENERAL]
logLevel = 2
fileLogLevel = 2
pcmFormat = 0
exportAMBE = 0
useEmulator = true
emulatorAddress = 127.0.0.1:{MD380_EMU_PORT}

[AMBE_AUDIO]
address = 127.0.0.1
txPort = {AB_AMBE_TX_PORT}
rxPort = {AB_AMBE_RX_PORT}
ambeMode = DMR
minGain = 0.8
maxGain = 2.0

[USRP]
address = 127.0.0.1
txPort = {AB_USRP_TX_PORT}
rxPort = {AB_USRP_RX_PORT}
usrpAudio = AUDIO_USE_PCM
aslAudio = AUDIO_USE_PCM

[DMR]
address = 127.0.0.1
txPort = {AB_AMBE_RX_PORT}
rxPort = {AB_AMBE_TX_PORT}
slot = 1
tg = {default_tg}
call = {callsign}
id = {dmr_id}
"""
        with open("/app/data/Analog_Bridge.ini", "w", encoding="utf-8") as f:
            f.write(ab_content)

        # 2. MMDVM_Bridge.ini
        mmdvm_content = f"""; Auto-generated by NinjaRadio DVSwitch Supervisor
[General]
Callsign = {callsign}
Id = {dmr_id}
Timeout = 180
Duplex = 0
RxFrequency = {rx_hz}
TxFrequency = {tx_hz}
Power = {tx_pwr}
ColorCode = {color_code}
Debug = 0

[Info]
RXFrequency = {rx_hz}
TXFrequency = {tx_hz}
Power = {tx_pwr}
Latitude = 0.0
Longitude = 0.0
Height = 0
Location = NinjaRadio
Description = NinjaRadio DVSwitch Gateway
URL = www.ninjaradio.local

[Log]
DisplayLevel = 1
FileLevel = 1
FilePath = /var/log/
FileRoot = MMDVM_Bridge

[DMR]
Enable = 1
Beacons = 0
Id = {dmr_id}
ColorCode = {color_code}
SelfOnly = 0
CallHang = 3
TXHang = 3

[DMR Network]
Enable = 1
Type = Gateway
Address = {master_host}
Port = {master_port}
Jitter = 500
Password = {master_password}
Slot1 = 1
Slot2 = 1
ModeHang = 3
Debug = 0
{options_line}

[AMBE]
Enable = 1
Address = 127.0.0.1
Port = {AB_AMBE_TX_PORT}
Send = {AB_AMBE_RX_PORT}
"""
        with open("/app/data/MMDVM_Bridge.ini", "w", encoding="utf-8") as f:
            f.write(mmdvm_content)

        logger.info(f"✓ DVSwitch INI configuration generated for DMR Master {master_host}:{master_port} (Callsign: {callsign}, ID: {dmr_id}, Freq: {tx_hz}/{rx_hz} Hz)")

    def start_daemons(self):
        """Starts md380-emu, Analog_Bridge, and MMDVM_Bridge official binaries."""
        # 1. md380-emu (Vocoder Emulator)
        md380_bin = find_binary("md380-emu")
        if md380_bin:
            try:
                self.md380_proc = subprocess.Popen(
                    [md380_bin, "-S", str(MD380_EMU_PORT)],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL
                )
                logger.info(f"✓ Started md380-emu vocoder daemon ({md380_bin}) on UDP {MD380_EMU_PORT}")
            except Exception as e:
                logger.error(f"Failed to start md380-emu: {e}")
        else:
            logger.warning("md380-emu binary not found. Please verify DVSwitch package installation.")

        # Give md380-emu a moment to bind port 2470
        time.sleep(0.3)

        # 2. Analog_Bridge
        ab_bin = find_binary("Analog_Bridge")
        if ab_bin:
            try:
                self.ab_proc = subprocess.Popen(
                    [ab_bin, "/app/data/Analog_Bridge.ini"],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL
                )
                logger.info(f"✓ Started Analog_Bridge daemon ({ab_bin}) with /app/data/Analog_Bridge.ini")
            except Exception as e:
                logger.error(f"Failed to start Analog_Bridge: {e}")
        else:
            logger.warning("Analog_Bridge binary not found. Please verify DVSwitch package installation.")

        time.sleep(0.3)

        # 3. MMDVM_Bridge
        mmdvm_bin = find_binary("MMDVM_Bridge")
        if mmdvm_bin:
            try:
                self.mmdvm_proc = subprocess.Popen(
                    [mmdvm_bin, "/app/data/MMDVM_Bridge.ini"],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL
                )
                logger.info(f"✓ Started MMDVM_Bridge daemon ({mmdvm_bin}) with /app/data/MMDVM_Bridge.ini")
            except Exception as e:
                logger.error(f"Failed to start MMDVM_Bridge: {e}")
        else:
            logger.warning("MMDVM_Bridge binary not found. Please verify DVSwitch package installation.")

    def stop_bridge_daemons(self):
        """Stops Analog_Bridge and MMDVM_Bridge processes for reconfiguration."""
        for name, proc in [("MMDVM_Bridge", self.mmdvm_proc), ("Analog_Bridge", self.ab_proc)]:
            if proc:
                try:
                    proc.terminate()
                    proc.wait(timeout=2.0)
                except Exception:
                    try:
                        proc.kill()
                    except Exception:
                        pass
                logger.info(f"Stopped {name} daemon.")
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

    async def run_process_watchdog(self):
        """Monitors DVSwitch daemons and restarts them if they terminate unexpectedly."""
        while self.running:
            await asyncio.sleep(5.0)
            if self.md380_proc and self.md380_proc.poll() is not None:
                logger.warning(f"md380-emu exited with code {self.md380_proc.returncode}. Restarting...")
                md380_bin = find_binary("md380-emu")
                if md380_bin:
                    self.md380_proc = subprocess.Popen([md380_bin, "-S", str(MD380_EMU_PORT)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

            if self.ab_proc and self.ab_proc.poll() is not None:
                logger.warning(f"Analog_Bridge exited with code {self.ab_proc.returncode}. Restarting...")
                ab_bin = find_binary("Analog_Bridge")
                if ab_bin:
                    self.ab_proc = subprocess.Popen([ab_bin, "/app/data/Analog_Bridge.ini"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

            if self.mmdvm_proc and self.mmdvm_proc.poll() is not None:
                logger.warning(f"MMDVM_Bridge exited with code {self.mmdvm_proc.returncode}. Restarting...")
                mmdvm_bin = find_binary("MMDVM_Bridge")
                if mmdvm_bin:
                    self.mmdvm_proc = subprocess.Popen([mmdvm_bin, "/app/data/MMDVM_Bridge.ini"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

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
                    magic, seq, mem, key_flag, tg, dmr_id, slot, res = struct.unpack(">4sIIIIIII", data[:32])
                    pcm_payload = data[32:]

                    # Forward to Mumble bridge (UDP 12346)
                    try:
                        self.bridge_sock.sendto(data, ("bridge-mumble", MUMBLE_BRIDGE_PORT))
                    except Exception:
                        pass

                    # Forward to any connected external USRP clients
                    self.broadcast_to_usrp_clients(data)

                    # Dispatch event to backend API for Web Audio player
                    if key_flag == 1 and len(pcm_payload) >= 320:
                        traffic_event = {
                            "network": f"DMR Master ({self.active_peer_host})",
                            "src_id": dmr_id,
                            "dst_tg": tg,
                            "slot": slot,
                            "payload": pcm_payload,
                            "data_type": 1,
                            "is_tx": False
                        }
                        asyncio.create_task(self.dispatch_event_to_backend(traffic_event))
                    elif key_flag == 0:
                        # Voice Terminator
                        traffic_event = {
                            "network": f"DMR Master ({self.active_peer_host})",
                            "src_id": dmr_id,
                            "dst_tg": tg,
                            "slot": slot,
                            "data_type": 2,
                            "is_tx": False
                        }
                        asyncio.create_task(self.dispatch_event_to_backend(traffic_event))
            except Exception as e:
                if self.running:
                    logger.debug(f"ab_pcm_rx_loop note: {e}")
                    await asyncio.sleep(0.01)

    async def run_usrp_ingress_loop(self):
        """
        Receives USRP frames from Web App PTT, Mumble Bridge, or DVSwitch Mobile,
        and forwards to Analog_Bridge (UDP 32000) for AMBE encoding and DMR transmission.
        """
        loop = asyncio.get_running_loop()
        logger.info("📱 Started USRP Ingress loop.")
        while self.running:
            try:
                data, addr = await loop.sock_recvfrom(self.usrp_ingress_sock, 2048)
                if len(data) >= 32 and data.startswith(b"USRP"):
                    self.registered_usrp_clients[addr] = time.time()
                    # Forward to Analog_Bridge rxPort (127.0.0.1:32000)
                    try:
                        self.bridge_sock.sendto(data, ("127.0.0.1", AB_USRP_RX_PORT))
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
    md380_running = engine_mgr.md380_proc is not None and engine_mgr.md380_proc.poll() is None
    ab_running = engine_mgr.ab_proc is not None and engine_mgr.ab_proc.poll() is None
    mmdvm_running = engine_mgr.mmdvm_proc is not None and engine_mgr.mmdvm_proc.poll() is None

    return {
        "status": "ONLINE" if (ab_running or mmdvm_running) else "INITIALIZING",
        "engine": "Official DVSwitch Native Stack (MMDVM_Bridge + Analog_Bridge + md380-emu)",
        "dvswitch_daemons": {
            "md380_emu": "RUNNING" if md380_running else "STOPPED",
            "analog_bridge": "RUNNING" if ab_running else "STOPPED",
            "mmdvm_bridge": "RUNNING" if mmdvm_running else "STOPPED"
        },
        "active_master": f"{engine_mgr.active_peer_host}:{engine_mgr.active_peer_port}",
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

@app.post("/api/tx")
async def ptt_transmit(payload: dict):
    callsign = payload.get("callsign", "N1NJA")
    dmr_id = int(payload.get("dmr_id", 5109999))
    tg = int(payload.get("tg", 510))
    slot = int(payload.get("slot", 1))
    is_tx = bool(payload.get("is_transmitting", True))

    key_flag = 1 if is_tx else 0
    header = struct.pack(">4sIIIIIII", b"USRP", 1, 0, key_flag, tg, dmr_id, slot, 0)
    packet = header + (b"\x00" * 320)
    try:
        engine_mgr.bridge_sock.sendto(packet, ("127.0.0.1", AB_USRP_RX_PORT))
        return {"status": "TX_DISPATCHED", "sent_to_dvswitch": True}
    except Exception as e:
        return {"status": "ERROR", "detail": str(e)}

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=5000, log_level="info")
