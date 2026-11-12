import os
import sys
import time
import socket
import struct
import threading
import logging
import json
import base64
import asyncio
import ctypes
import re
import sqlite3
from typing import Optional, List, Dict, Any, Tuple
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

import numpy as np

# Cryptography for JWT generation
try:
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import padding
    from cryptography.hazmat.primitives.serialization import load_pem_private_key
    CRYPTO_AVAILABLE = True
except ImportError:
    CRYPTO_AVAILABLE = False

try:
    import websockets
    WEBSOCKETS_AVAILABLE = True
except ImportError:
    WEBSOCKETS_AVAILABLE = False

log_handlers = [logging.StreamHandler()]
try:
    os.makedirs("/app/data", exist_ok=True)
    log_handlers.append(logging.FileHandler("/app/data/zello_bridge.log", mode="a", encoding="utf-8"))
except Exception:
    pass

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] [Zello-Bridge] %(message)s",
    handlers=log_handlers
)
logger = logging.getLogger("zello-bridge")

CONFIG_PATH = os.getenv("CONFIG_PATH", "/app/data/config.json")
ZELLO_KEY_PATH = os.getenv("ZELLO_KEY_PATH", "/app/data/zello_private.key")

def _get_int_env(key: str, default: int) -> int:
    val = os.getenv(key, "").strip()
    if not val:
        return default
    try:
        return int(val)
    except ValueError:
        return default

USRP_HOST = os.getenv("DMR_USRP_HOST", "core")
USRP_PORT = _get_int_env("DMR_USRP_PORT", 32345)
CORE_DIRECT_DMR_PORT = _get_int_env("WEB_PTT_PORT", 32100)
ZELLO_BRIDGE_RX_PORT = _get_int_env("ZELLO_BRIDGE_RX_PORT", 12351)
BRIDGE_HTTP_PORT = _get_int_env("ZELLO_BRIDGE_HTTP_PORT", 12350)

recent_logs: List[str] = []
def record_log(msg: str):
    logger.info(msg)
    timestamp = time.strftime("%H:%M:%S")
    recent_logs.append(f"[{timestamp}] {msg}")
    if len(recent_logs) > 120:
        recent_logs.pop(0)

def get_db_path() -> Optional[str]:
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

def resolve_callbook_info_by_id(dmr_id: int) -> Tuple[str, str]:
    db_path = get_db_path()
    if db_path and os.path.exists(db_path):
        try:
            with sqlite3.connect(db_path, timeout=1.0) as conn:
                cursor = conn.cursor()
                clean_id = dmr_id // 100 if dmr_id > 9999999 else dmr_id
                cursor.execute("SELECT callsign, name FROM callbook WHERE dmr_id = ?", (clean_id,))
                row = cursor.fetchone()
                if row:
                    c_call = str(row[0]).strip().upper() if row[0] else f"ID {dmr_id}"
                    c_name = str(row[1]).strip() if row[1] else "DMR Operator"
                    return c_call, c_name
        except Exception:
            pass
    return f"ID {dmr_id}", "DMR Operator"

def resolve_zello_identity(username: str, display_name: str = "") -> Tuple[Optional[int], Optional[str]]:
    """
    Extracts DMR ID and Callsign from Zello username and display name and matches against Callbook DB.
    Strictly returns (dmr_id, callsign) if verified in Callbook DB, or (None, None) if not registered.
    """
    u_clean = (username or "").strip()
    d_clean = (display_name or "").strip()
    u_lower = u_clean.lower()
    d_lower = d_clean.lower()

    # 0. Check explicit dynamic user_mappings from config (e.g. {"username": "CALLSIGN"})
    mapped_call = bridge_state.user_mappings.get(u_lower) or bridge_state.user_mappings.get(d_lower)

    names_to_check = []
    if mapped_call:
        names_to_check.append(mapped_call)
    if d_clean:
        names_to_check.append(d_clean)
    if u_clean:
        names_to_check.append(u_clean)

    db_path = get_db_path()
    if not db_path or not os.path.exists(db_path):
        return None, None

    try:
        with sqlite3.connect(db_path, timeout=1.0) as conn:
            cursor = conn.cursor()
            for clean_name in names_to_check:
                # 1. Look for 7-digit DMR ID in string
                id_match = re.search(r"\b(\d{7})\b", clean_name)
                found_id = int(id_match.group(1)) if id_match else None
                if found_id:
                    cursor.execute("SELECT callsign, dmr_id FROM callbook WHERE dmr_id = ?", (found_id,))
                    row = cursor.fetchone()
                    if row:
                        return int(row[1]), str(row[0]).strip().upper()

                # 2. Extract potential callsign tokens (e.g. BD62CAN, BD62BOT, YB0ABC, JZ11XYZ)
                tokens = re.findall(r"[A-Za-z0-9]{3,10}", clean_name)
                for token in tokens:
                    token_upper = token.strip("/").upper()
                    cursor.execute("SELECT callsign, dmr_id FROM callbook WHERE UPPER(callsign) = ?", (token_upper,))
                    row = cursor.fetchone()
                    if row:
                        return int(row[1]), str(row[0]).strip().upper()

                # 3. Check if Zello username matches 'remarks' or 'name' in Callbook (e.g. remarks contains "sk8pper13")
                if len(clean_name) >= 3:
                    cursor.execute(
                        "SELECT callsign, dmr_id FROM callbook WHERE LOWER(remarks) LIKE ? OR LOWER(name) LIKE ?",
                        (f"%{clean_name.lower()}%", f"%{clean_name.lower()}%")
                    )
                    row = cursor.fetchone()
                    if row:
                        return int(row[1]), str(row[0]).strip().upper()
    except Exception as e:
        logger.debug(f"Callbook lookup error: {e}")

    # User not verified in Callbook DB
    return None, None

def check_zello_tx_permission(dmr_id: int, callsign: str, target_tg: int) -> Tuple[bool, str]:
    """
    Strictly verifies if Zello user has authorization in Callbook and Organization rules to transmit on target_tg.
    """
    if not dmr_id or not callsign:
        return False, "Pengguna Zello belum terdaftar di Callbook"

    db_path = get_db_path()
    if not db_path or not os.path.exists(db_path):
        return False, "Database Callbook belum siap"

    try:
        with sqlite3.connect(db_path, timeout=1.0) as conn:
            cursor = conn.cursor()
            cursor.execute(
                "SELECT organization, allowed_tgs_override FROM callbook WHERE UPPER(callsign) = ? OR dmr_id = ?",
                (callsign.upper(), dmr_id)
            )
            row = cursor.fetchone()
            if not row:
                return False, f"Callsign '{callsign}' tidak terdaftar di Callbook Server NinjaRadio"

            if target_tg == 9990:
                return True, "Echo Test"

            # 1. User specific TG override
            if row[1]:
                override = str(row[1]).strip()
                if override.upper() in ("*", "ALL"):
                    return True, "User Override (*)"
                allowed = [int(x.strip()) for x in override.split(",") if x.strip().isdigit()]
                if target_tg in allowed:
                    return True, "User Override"
                return False, f"Hanya diizinkan di TG: {override}"

            # 2. Organization policy
            org_code = row[0] if row[0] else None
            if org_code:
                cursor.execute("SELECT code, allowed_tgs, allow_tx FROM organizations WHERE UPPER(code) = ?", (str(org_code).upper(),))
                target_org_row = cursor.fetchone()
                if target_org_row:
                    org_c, allowed_tgs, allow_tx = target_org_row
                    if not allow_tx:
                        return False, f"Organisasi {org_c} saat ini diset Monitor-Only"
                    rule = (allowed_tgs or "*").strip()
                    if rule.upper() in ("*", "ALL", ""):
                        return True, org_c
                    allowed = [int(x.strip()) for x in rule.split(",") if x.strip().isdigit()]
                    if target_tg in allowed:
                        return True, org_c
                    return False, f"Organisasi {org_c} tidak diizinkan di TG {target_tg}"

            return True, "Callbook Verified"
    except Exception as e:
        logger.debug(f"check_zello_tx_permission error: {e}")
        return False, f"DB Error: {e}"

# ---------------- Native libopus Transcoder via ctypes ----------------
_opus_lib = None
for _name in [
    "libopus.so.0",
    "libopus.so",
    "/usr/lib/x86_64-linux-gnu/libopus.so.0",
    "/usr/lib/aarch64-linux-gnu/libopus.so.0",
    "/usr/lib/arm-linux-gnueabihf/libopus.so.0"
]:
    try:
        _opus_lib = ctypes.CDLL(_name)
        if _opus_lib:
            logger.info(f"Loaded native libopus library from: {_name}")
            break
    except Exception:
        pass

if _opus_lib:
    try:
        _opus_lib.opus_decoder_create.restype = ctypes.c_void_p
        _opus_lib.opus_decoder_create.argtypes = [ctypes.c_int32, ctypes.c_int, ctypes.POINTER(ctypes.c_int)]
        _opus_lib.opus_decode.restype = ctypes.c_int
        _opus_lib.opus_decode.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int32, ctypes.POINTER(ctypes.c_int16), ctypes.c_int, ctypes.c_int]
        _opus_lib.opus_decoder_destroy.argtypes = [ctypes.c_void_p]

        _opus_lib.opus_encoder_create.restype = ctypes.c_void_p
        _opus_lib.opus_encoder_create.argtypes = [ctypes.c_int32, ctypes.c_int, ctypes.c_int, ctypes.POINTER(ctypes.c_int)]
        _opus_lib.opus_encode.restype = ctypes.c_int32
        _opus_lib.opus_encode.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_int16), ctypes.c_int, ctypes.c_char_p, ctypes.c_int32]
        _opus_lib.opus_encoder_destroy.argtypes = [ctypes.c_void_p]
    except Exception as oe:
        logger.warning(f"Error binding libopus signatures: {oe}")
else:
    logger.warning("libopus shared library not found. Audio transcoding will fallback to pass-through.")

class OpusDecoderWrapper:
    def __init__(self, sample_rate=16000, channels=1):
        self.dec = None
        if _opus_lib:
            err = ctypes.c_int()
            self.dec = _opus_lib.opus_decoder_create(sample_rate, channels, ctypes.byref(err))
        self.sample_rate = sample_rate
        self.channels = channels

    def decode(self, data: bytes, max_frame_size=1920) -> bytes:
        if not self.dec or not data:
            return b""
        out_buf = (ctypes.c_int16 * (max_frame_size * self.channels))()
        ret = _opus_lib.opus_decode(self.dec, data, len(data), out_buf, max_frame_size, 0)
        if ret > 0:
            return ctypes.string_at(ctypes.addressof(out_buf), ret * self.channels * 2)
        return b""

    def __del__(self):
        if self.dec and _opus_lib:
            try:
                _opus_lib.opus_decoder_destroy(self.dec)
            except Exception:
                pass
            self.dec = None

class OpusEncoderWrapper:
    def __init__(self, sample_rate=16000, channels=1):
        self.enc = None
        if _opus_lib:
            err = ctypes.c_int()
            # 2048 = OPUS_APPLICATION_VOIP
            self.enc = _opus_lib.opus_encoder_create(sample_rate, channels, 2048, ctypes.byref(err))
        self.sample_rate = sample_rate
        self.channels = channels

    def encode(self, pcm_data: bytes, frame_size=320) -> bytes:
        if not self.enc or not pcm_data:
            return b""
        n_samples = len(pcm_data) // 2
        in_buf = (ctypes.c_int16 * n_samples).from_buffer_copy(pcm_data)
        out_buf = ctypes.create_string_buffer(1275)
        ret = _opus_lib.opus_encode(self.enc, in_buf, frame_size, out_buf, 1275)
        if ret > 0:
            return bytes(out_buf.raw[:ret])
        return b""

    def __del__(self):
        if self.enc and _opus_lib:
            try:
                _opus_lib.opus_encoder_destroy(self.enc)
            except Exception:
                pass
            self.enc = None

# Bridge State
class ZelloBridgeState:
    def __init__(self):
        self.enabled = True
        self.channel = ""
        self.username = ""
        self.password = ""
        self.issuer = ""
        self.target_tg = 1025
        self.slot = 2
        self.bot_callsign = "NINJA-ZELLO"
        self.dev_token = ""
        self.user_mappings: Dict[str, str] = {}
        self.has_key = False
        self.private_key_obj = None
        self.ws_connected = False
        self.channel_joined = False
        self.active_speaker = None
        self.active_speaker_id = 0
        self.active_speaker_call = None
        self.active_speaker_allowed = False
        self.active_stream_id = None
        self.last_tx_time = 0.0
        self.last_rx_time = 0.0
        self.tx_sequence = 0
        self.seq_counter = 1
        
        # Outgoing DMR -> Zello streaming state
        self.dmr_to_zello_streaming = False
        self.dmr_stream_id = None
        self.dmr_stream_seq = 0
        self.dmr_last_audio_time = 0.0
        self.dmr_tx_pcm_buf = bytearray()
        self.active_ws = None

bridge_state = ZelloBridgeState()
opus_decoder = OpusDecoderWrapper(16000, 1)
opus_encoder = OpusEncoderWrapper(16000, 1)

def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode("utf-8").rstrip("=")

def generate_jwt(issuer: str, priv_key) -> Optional[str]:
    """Generates an RS256 signed JSON Web Token (JWT) for Zello Channel API authentication."""
    if not priv_key or not CRYPTO_AVAILABLE:
        return None
    try:
        now = int(time.time())
        header = b64url(json.dumps({"alg": "RS256", "typ": "JWT"}).encode("utf-8"))
        payload = b64url(json.dumps({"iss": issuer, "exp": now + 300}).encode("utf-8"))
        signing_input = f"{header}.{payload}".encode("utf-8")
        signature = priv_key.sign(signing_input, padding.PKCS1v15(), hashes.SHA256())
        return f"{header}.{payload}.{b64url(signature)}"
    except Exception as e:
        logger.error(f"Error signing Zello JWT: {e}")
        return None

def load_bridge_config():
    """Loads configuration and private key from storage."""
    # 1. Read config.json
    for p in [CONFIG_PATH, "./data/config.json", "../data/config.json"]:
        if os.path.exists(p):
            try:
                with open(p, "r", encoding="utf-8") as f:
                    data = json.load(f)
                z = data.get("zello_config", {})
                if isinstance(z, dict):
                    bridge_state.enabled = bool(z.get("enabled", True))
                    bridge_state.channel = str(z.get("channel", "NINJARADIOZL")).strip()
                    bridge_state.username = str(z.get("username", "")).strip()
                    bridge_state.password = str(z.get("password", "")).strip()
                    bridge_state.issuer = str(z.get("issuer", bridge_state.issuer)).strip()
                    bridge_state.target_tg = int(z.get("target_tg", 1025))
                    bridge_state.slot = int(z.get("slot", 2))
                    bridge_state.bot_callsign = str(z.get("bot_callsign", "NINJA-ZELLO")).strip().upper()
                    bridge_state.dev_token = str(z.get("dev_token", "")).strip()
                    bridge_state.user_mappings.clear()
                    if "user_mappings" in z and isinstance(z["user_mappings"], dict):
                        for mk, mv in z["user_mappings"].items():
                            bridge_state.user_mappings[str(mk).lower().strip()] = str(mv).upper().strip()
                break
            except Exception as e:
                logger.warning(f"Could not read config from {p}: {e}")

    # 2. Read Private Key
    bridge_state.has_key = False
    bridge_state.private_key_obj = None
    for kp in [ZELLO_KEY_PATH, "./data/zello_private.key", "../data/zello_private.key"]:
        if os.path.exists(kp):
            try:
                with open(kp, "rb") as kf:
                    key_bytes = kf.read()
                if (b"PRIVATE KEY" in key_bytes or b"RSA PRIVATE KEY" in key_bytes) and CRYPTO_AVAILABLE:
                    bridge_state.private_key_obj = load_pem_private_key(key_bytes, password=None)
                    bridge_state.has_key = True
                    logger.info(f"Loaded RSA Private Key from {kp} (Size: {getattr(bridge_state.private_key_obj, 'key_size', 'unknown')} bits)")
                break
            except Exception as e:
                logger.error(f"Failed to load private key from {kp}: {e}")

    record_log(f"Config loaded: Channel={bridge_state.channel}, TG={bridge_state.target_tg}, KeyLoaded={bridge_state.has_key}")

# ---------------- HTTP Bridge Control Server ----------------
class BridgeHTTPHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/status":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            data = {
                "status": "ONLINE" if (bridge_state.enabled and bridge_state.has_key and bridge_state.channel_joined) else "STANDBY",
                "enabled": bridge_state.enabled,
                "channel": bridge_state.channel,
                "target_tg": bridge_state.target_tg,
                "slot": bridge_state.slot,
                "bot_callsign": bridge_state.bot_callsign,
                "has_key": bridge_state.has_key,
                "websocket": "CONNECTED" if bridge_state.ws_connected else "DISCONNECTED",
                "channel_joined": bridge_state.channel_joined,
                "active_speaker": bridge_state.active_speaker,
                "last_tx": bridge_state.last_tx_time,
                "last_rx": bridge_state.last_rx_time
            }
            self.wfile.write(json.dumps(data).encode("utf-8"))
        elif self.path == "/logs":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"status": "SUCCESS", "recent_logs": recent_logs}).encode("utf-8"))
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        if self.path == "/sync":
            load_bridge_config()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"status": "SUCCESS", "message": "Bridge synced"}')
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format, *args):
        pass

import signal

http_server_instance: Optional[ThreadingHTTPServer] = None
is_app_running = True

def handle_sigterm(signum, frame):
    global is_app_running, http_server_instance
    is_app_running = False
    if http_server_instance:
        try:
            threading.Thread(target=http_server_instance.shutdown).start()
        except Exception:
            pass
    sys.exit(0)

signal.signal(signal.SIGTERM, handle_sigterm)
signal.signal(signal.SIGINT, handle_sigterm)

def run_http_server():
    global http_server_instance
    http_server_instance = ThreadingHTTPServer(("0.0.0.0", BRIDGE_HTTP_PORT), BridgeHTTPHandler)
    record_log(f"Bridge HTTP Server listening on port {BRIDGE_HTTP_PORT}")
    try:
        http_server_instance.serve_forever()
    except Exception:
        pass

# ---------------- DMR RX Listener (Routes DMR Audio to Zello) ----------------
dmr_to_zello_queue = asyncio.Queue()

def run_dmr_rx_listener(loop: asyncio.AbstractEventLoop):
    """Listens for decoded PCM audio from Core on UDP port ZELLO_BRIDGE_RX_PORT (12351)"""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.bind(("0.0.0.0", ZELLO_BRIDGE_RX_PORT))
        logger.info(f"DMR RX Listener bound on UDP port {ZELLO_BRIDGE_RX_PORT}")
    except Exception as e:
        logger.warning(f"Could not bind DMR RX listener on port {ZELLO_BRIDGE_RX_PORT}: {e}")
        return

    while is_app_running:
        try:
            data, addr = sock.recvfrom(2048)
            if len(data) >= 32 and data[:4] == b"USRP":
                magic, seq, mem, key_flag, tg, src_id, slot, res = struct.unpack(">4sIIIIIII", data[:32])
                pcm_8k = data[32:]
                if tg == bridge_state.target_tg:
                    loop.call_soon_threadsafe(dmr_to_zello_queue.put_nowait, (key_flag, pcm_8k, tg, src_id, slot))
        except Exception:
            time.sleep(0.01)

# ---------------- Zello WebSocket Client Worker ----------------
async def zello_websocket_worker():
    """Asynchronous WebSocket client connecting to wss://zello.io/ws"""
    if not WEBSOCKETS_AVAILABLE:
        record_log("websockets package not available, waiting...")
        return

    udp_tx_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    zello_rx_pcm_buffer = bytearray()

    while is_app_running:
        if not bridge_state.enabled:
            bridge_state.ws_connected = False
            bridge_state.channel_joined = False
            await asyncio.sleep(4.0)
            continue

        if not bridge_state.has_key or not bridge_state.private_key_obj:
            record_log("Waiting for RSA private key to be configured in Settings...")
            await asyncio.sleep(5.0)
            continue

        if bridge_state.dev_token:
            jwt_token = bridge_state.dev_token
            record_log("Using custom Sample Development Token from Settings")
        else:
            jwt_token = generate_jwt(bridge_state.issuer, bridge_state.private_key_obj)
        if not jwt_token:
            record_log("Failed to generate JWT auth token. Retrying in 10s...")
            await asyncio.sleep(10.0)
            continue

        ws_url = "wss://zello.io/ws"
        record_log(f"Connecting to Zello Channel API WebSocket: {ws_url}...")

        try:
            async with websockets.connect(
                ws_url,
                ping_interval=20,
                ping_timeout=20,
                close_timeout=10,
                ssl=True
            ) as ws:
                bridge_state.ws_connected = True
                bridge_state.active_ws = ws
                u_info = bridge_state.username if bridge_state.username else "anonymous"
                record_log(f"Connected to Zello WebSocket. Logging in to '{bridge_state.channel}' as '{u_info}'...")

                # 1. Send logon command
                logon_msg = {
                    "command": "logon",
                    "seq": bridge_state.seq_counter,
                    "auth_token": jwt_token,
                    "channel": bridge_state.channel
                }
                if bridge_state.username:
                    logon_msg["username"] = bridge_state.username
                if bridge_state.password:
                    logon_msg["password"] = bridge_state.password
                bridge_state.seq_counter += 1
                await ws.send(json.dumps(logon_msg))

                # Background task: Stream audio from DMR -> Zello
                async def dmr_to_zello_task():
                    last_pcm_time = 0.0
                    while is_app_running and bridge_state.ws_connected:
                        try:
                            try:
                                key_flag, pcm_8k, tg, src_id, slot = await asyncio.wait_for(dmr_to_zello_queue.get(), timeout=0.1)
                            except asyncio.TimeoutError:
                                # Watchdog: if no DMR audio for > 0.45s while streaming, stop Zello stream
                                if bridge_state.dmr_to_zello_streaming and (time.time() - last_pcm_time > 0.45):
                                    if bridge_state.dmr_stream_id:
                                        stop_cmd = {
                                            "command": "stop_stream",
                                            "seq": bridge_state.seq_counter,
                                            "stream_id": bridge_state.dmr_stream_id
                                        }
                                        bridge_state.seq_counter += 1
                                        await ws.send(json.dumps(stop_cmd))
                                        record_log(f"DMR -> Zello TX Ended (stream {bridge_state.dmr_stream_id})")
                                    bridge_state.dmr_to_zello_streaming = False
                                    bridge_state.dmr_stream_id = None
                                    bridge_state.dmr_tx_pcm_buf.clear()
                                continue

                            if key_flag == 1 and pcm_8k:
                                last_pcm_time = time.time()
                                bridge_state.last_tx_time = last_pcm_time

                                # Start stream if not yet started
                                if not bridge_state.dmr_to_zello_streaming:
                                    # 16kHz, 1 frame/pkt, 20ms duration -> codec_header = "gD4BFA=="
                                    start_cmd = {
                                        "command": "start_stream",
                                        "seq": bridge_state.seq_counter,
                                        "type": "audio",
                                        "codec": "opus",
                                        "codec_header": "gD4BFA==",
                                        "packet_duration": 20
                                    }
                                    bridge_state.seq_counter += 1
                                    await ws.send(json.dumps(start_cmd))
                                    bridge_state.dmr_to_zello_streaming = True
                                    bridge_state.dmr_stream_seq = 0
                                    record_log(f"DMR -> Zello TX Started on TG {tg} (slot {slot})")

                                    # Send text notification into Zello channel with talker callsign & name
                                    talker_call, talker_name = resolve_callbook_info_by_id(src_id)
                                    text_cmd = {
                                        "command": "send_text_message",
                                        "seq": bridge_state.seq_counter,
                                        "channel": bridge_state.channel,
                                        "text": f"🎙️ [DMR TX] {talker_call} ({talker_name}) -> TG {tg}"
                                    }
                                    bridge_state.seq_counter += 1
                                    try:
                                        await ws.send(json.dumps(text_cmd))
                                    except Exception as te:
                                        logger.debug(f"Error sending text notification to Zello: {te}")

                                # Smooth 8kHz -> 16kHz linear interpolation with anti-imaging to eliminate treble harshness
                                pcm_8k_arr = np.frombuffer(pcm_8k, dtype=np.int16)
                                pcm_8k_f = pcm_8k_arr.astype(np.float32)
                                pcm_16k_arr = np.empty(len(pcm_8k_f) * 2, dtype=np.float32)
                                pcm_16k_arr[0::2] = pcm_8k_f
                                pcm_16k_arr[1:-1:2] = 0.5 * (pcm_8k_f[:-1] + pcm_8k_f[1:])
                                pcm_16k_arr[-1] = pcm_8k_f[-1]
                                pcm_16k = np.clip(pcm_16k_arr, -32768, 32767).astype(np.int16).tobytes()

                                # Encode 20ms of 16kHz PCM (320 samples = 640 bytes) to Opus
                                opus_bytes = opus_encoder.encode(pcm_16k, frame_size=320)
                                if opus_bytes and bridge_state.dmr_stream_id:
                                    # Zello binary audio frame: [0x01][4B stream_id][4B seq][Opus data]
                                    hdr = bytes([0x01]) + struct.pack(">II", bridge_state.dmr_stream_id, bridge_state.dmr_stream_seq)
                                    bridge_state.dmr_stream_seq += 1
                                    await ws.send(hdr + opus_bytes)

                            elif key_flag == 0:
                                if bridge_state.dmr_to_zello_streaming and bridge_state.dmr_stream_id:
                                    stop_cmd = {
                                        "command": "stop_stream",
                                        "seq": bridge_state.seq_counter,
                                        "stream_id": bridge_state.dmr_stream_id
                                    }
                                    bridge_state.seq_counter += 1
                                    await ws.send(json.dumps(stop_cmd))
                                    record_log(f"DMR -> Zello TX Released (stream {bridge_state.dmr_stream_id})")
                                bridge_state.dmr_to_zello_streaming = False
                                bridge_state.dmr_stream_id = None
                                bridge_state.dmr_tx_pcm_buf.clear()
                        except Exception as de:
                            logger.debug(f"dmr_to_zello note: {de}")
                            await asyncio.sleep(0.01)

                dmr_fwd_task = asyncio.create_task(dmr_to_zello_task())

                # 2. Incoming Zello WebSocket Message Loop (Zello -> DMR)
                try:
                    async for msg in ws:
                        if isinstance(msg, str):
                            try:
                                data = json.loads(msg)
                                cmd = data.get("command") or data.get("action") or data.get("event")

                                # Logon response
                                if cmd == "logon" or data.get("success") is True:
                                    bridge_state.channel_joined = True
                                    record_log(f"Successfully joined Zello Channel: '{bridge_state.channel}'")

                                # Response to our start_stream command
                                if data.get("command") == "start_stream" or (data.get("success") is True and "stream_id" in data):
                                    bridge_state.dmr_stream_id = data.get("stream_id")
                                    record_log(f"Zello granted outbound TX stream ID: {bridge_state.dmr_stream_id}")

                                # Remote speaker started talking on Zello
                                if cmd == "on_stream_start":
                                    stream_id = data.get("stream_id")
                                    speaker_user = (data.get("from") or "").strip()
                                    speaker_disp = (data.get("display_name") or data.get("name") or "").strip()
                                    speaker_label = speaker_disp or speaker_user or "ZelloUser"
                                    user_dmr_id, user_callsign = resolve_zello_identity(speaker_user, speaker_disp)
                                    bridge_state.active_speaker = speaker_label
                                    bridge_state.active_stream_id = stream_id
                                    bridge_state.last_rx_time = time.time()
                                    zello_rx_pcm_buffer.clear()

                                    # Strict TX Firewall: Must exist in Callbook DB to transmit to DMR/Repeaters
                                    if not user_dmr_id or not user_callsign:
                                        bridge_state.active_speaker_allowed = False
                                        bridge_state.active_speaker_id = 0
                                        bridge_state.active_speaker_call = None
                                        reject_msg = f"🛑 [BLOCKED] Akun Zello '{speaker_label}' ({speaker_user}) tidak terdaftar di Callbook NinjaRadio. Transmisi ditolak."
                                        record_log(f"🛑 [ZELLO-FIREWALL] Blocked TX from unverified Zello user: '{speaker_label}' (from: '{speaker_user}') on stream {stream_id}")
                                        text_cmd = {
                                            "command": "send_text_message",
                                            "seq": bridge_state.seq_counter,
                                            "channel": bridge_state.channel,
                                            "text": reject_msg
                                        }
                                        bridge_state.seq_counter += 1
                                        try:
                                            await ws.send(json.dumps(text_cmd))
                                        except Exception as te:
                                            logger.debug(f"Error sending reject text to Zello: {te}")
                                    else:
                                        allowed, reason = check_zello_tx_permission(user_dmr_id, user_callsign, bridge_state.target_tg)
                                        if not allowed:
                                            bridge_state.active_speaker_allowed = False
                                            bridge_state.active_speaker_id = 0
                                            bridge_state.active_speaker_call = None
                                            reject_msg = f"🛑 [BLOCKED] {user_callsign} ({speaker_label}): {reason}. Transmisi ditolak."
                                            record_log(f"🛑 [ZELLO-FIREWALL] Blocked TX for {user_callsign}: {reason}")
                                            text_cmd = {
                                                "command": "send_text_message",
                                                "seq": bridge_state.seq_counter,
                                                "channel": bridge_state.channel,
                                                "text": reject_msg
                                            }
                                            bridge_state.seq_counter += 1
                                            try:
                                                await ws.send(json.dumps(text_cmd))
                                            except Exception as te:
                                                logger.debug(f"Error sending reject text to Zello: {te}")
                                        else:
                                            bridge_state.active_speaker_allowed = True
                                            bridge_state.active_speaker_id = user_dmr_id
                                            bridge_state.active_speaker_call = user_callsign
                                            record_log(f"✓ [ZELLO-FIREWALL] Authorized Zello TX: '{speaker_label}' as {user_callsign} ({user_dmr_id}) -> TG {bridge_state.target_tg} (Slot {bridge_state.slot})")

                                # Remote speaker stopped talking on Zello
                                elif cmd == "on_stream_stop":
                                    stream_id = data.get("stream_id")
                                    record_log(f"Zello RX Ended for stream {stream_id}")
                                    
                                    # Send unkey frame to DirectDmrEngine only if session was authorized
                                    if getattr(bridge_state, "active_speaker_allowed", False) and bridge_state.active_speaker_id:
                                        cur_dmr_id = bridge_state.active_speaker_id
                                        cur_call = bridge_state.active_speaker_call or (bridge_state.bot_callsign or "ZELLO")
                                        call_b = cur_call.encode("ascii", errors="ignore")[:15].ljust(16, b"\x00")
                                        unkey_nptt = struct.pack(">4sIIII?16s", b"NZEL", int(cur_dmr_id), int(bridge_state.target_tg), int(bridge_state.slot), 0, False, call_b)
                                        try:
                                            udp_tx_sock.sendto(unkey_nptt, (USRP_HOST, CORE_DIRECT_DMR_PORT))
                                        except Exception:
                                            pass
                                    bridge_state.active_speaker_allowed = False
                                    bridge_state.active_speaker = None
                                    bridge_state.active_speaker_id = 0
                                    bridge_state.active_speaker_call = None
                                    bridge_state.active_stream_id = None
                                    zello_rx_pcm_buffer.clear()

                                elif data.get("success") is False:
                                    err = data.get("error", "Unknown Zello error")
                                    record_log(f"Zello error: {err}")

                            except Exception as pe:
                                logger.debug(f"JSON parse error: {pe}")

                        elif isinstance(msg, bytes):
                            # Binary audio packet from Zello: [0x01][4B stream_id][4B packet_seq][Opus payload]
                            if len(msg) > 9 and msg[0] == 0x01:
                                bridge_state.last_rx_time = time.time()
                                
                                # Discard audio if speaker is NOT authorized by TX Firewall
                                if not getattr(bridge_state, "active_speaker_allowed", False) or not bridge_state.active_speaker_id:
                                    zello_rx_pcm_buffer.clear()
                                    continue

                                opus_payload = msg[9:]
                                
                                # Decode Opus to 16kHz PCM (supports 20ms, 40ms, 60ms frames from Zello)
                                pcm_16k = opus_decoder.decode(opus_payload, max_frame_size=1920)
                                if pcm_16k:
                                    # Downsample 16kHz PCM to 8kHz PCM using 2:1 averaging (anti-aliasing)
                                    pcm_16k_arr = np.frombuffer(pcm_16k, dtype=np.int16).astype(np.float32)
                                    pcm_8k_arr = 0.5 * (pcm_16k_arr[0::2] + pcm_16k_arr[1::2])
                                    pcm_8k = np.clip(pcm_8k_arr, -32768, 32767).astype(np.int16).tobytes()
                                    zello_rx_pcm_buffer.extend(pcm_8k)

                                    # Buffer into 320-byte (20ms) frames and transmit directly to DirectDmrEngine
                                    cur_dmr_id = bridge_state.active_speaker_id
                                    cur_call = bridge_state.active_speaker_call or (bridge_state.bot_callsign or "ZELLO")
                                    call_b = cur_call.encode("ascii", errors="ignore")[:15].ljust(16, b"\x00")
                                    while len(zello_rx_pcm_buffer) >= 320:
                                        chunk = bytes(zello_rx_pcm_buffer[:320])
                                        del zello_rx_pcm_buffer[:320]
                                        nptt_pkt = struct.pack(
                                            ">4sIIII?16s",
                                            b"NZEL",
                                            int(cur_dmr_id),
                                            int(bridge_state.target_tg),
                                            int(bridge_state.slot),
                                            len(chunk),
                                            True,
                                            call_b
                                        ) + chunk
                                        try:
                                            udp_tx_sock.sendto(nptt_pkt, (USRP_HOST, CORE_DIRECT_DMR_PORT))
                                        except Exception:
                                            pass
                finally:
                    dmr_fwd_task.cancel()

        except Exception as e:
            record_log(f"Zello WebSocket connection error: {e}. Reconnecting in 5s...")
            bridge_state.ws_connected = False
            bridge_state.channel_joined = False
            bridge_state.active_speaker = None
            bridge_state.active_ws = None
            await asyncio.sleep(5.0)

def main():
    record_log("Starting NinjaRadio Zello Channel API Bridge...")
    load_bridge_config()

    # Start HTTP Status server
    http_thread = threading.Thread(target=run_http_server, daemon=True)
    http_thread.start()

    # Get asyncio event loop
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)

    # Start DMR RX Listener thread
    dmr_rx_thread = threading.Thread(target=run_dmr_rx_listener, args=(loop,), daemon=True)
    dmr_rx_thread.start()

    # Start Asyncio WebSocket Loop
    try:
        loop.run_until_complete(zello_websocket_worker())
    except KeyboardInterrupt:
        record_log("Zello Bridge stopped by user")

if __name__ == "__main__":
    main()
