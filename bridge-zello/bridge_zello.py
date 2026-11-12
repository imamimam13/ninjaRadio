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
from typing import Optional, List, Dict, Any
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
USRP_RX_PORT = _get_int_env("ZELLO_USRP_RX_PORT", 32101)
BRIDGE_HTTP_PORT = _get_int_env("ZELLO_BRIDGE_HTTP_PORT", 12350)

recent_logs: List[str] = []
def record_log(msg: str):
    logger.info(msg)
    timestamp = time.strftime("%H:%M:%S")
    recent_logs.append(f"[{timestamp}] {msg}")
    if len(recent_logs) > 120:
        recent_logs.pop(0)

# Bridge State
class ZelloBridgeState:
    def __init__(self):
        self.enabled = True
        self.channel = ""
        self.username = ""
        self.password = ""
        self.issuer = ""
        self.target_tg = 0
        self.slot = 2
        self.bot_callsign = ""
        self.has_key = False
        self.private_key_obj = None
        self.ws_connected = False
        self.channel_joined = False
        self.active_speaker = None
        self.active_stream_id = None
        self.last_tx_time = 0.0
        self.last_rx_time = 0.0
        self.tx_sequence = 0
        self.seq_counter = 1

bridge_state = ZelloBridgeState()

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

# ---------------- USRP Audio Encoding & Protocol ----------------
TLV_TAG_SET_INFO = 0x08

def build_usrp_frame(seq: int, keyup: int, usrp_type: int = 0, payload: bytes = b"") -> bytes:
    """Header Length: Exactly 32 bytes: b'USRP' + struct.pack('>iiiiiii', seq, 0, key_flag, 0, packetType << 24, 0, 0)"""
    return b"USRP" + struct.pack(">iiiiiii", seq & 0x7FFFFFFF, 0, 1 if keyup else 0, 0, usrp_type << 24, 0, 0) + payload

def build_set_info_tlv(src_id: int, rpt_id: int, tg: int, slot: int, cc: int, callsign: str) -> bytes:
    """Dynamic Identity Carry-Over Frame (Tag 0x08) dispatched before voice bursts."""
    call = (callsign or str(src_id)).encode("ascii", errors="ignore")[:16]
    body = (
        (src_id & 0xFFFFFF).to_bytes(3, "big")
        + (rpt_id & 0xFFFFFFFF).to_bytes(4, "big")
        + (tg & 0xFFFFFF).to_bytes(3, "big")
        + bytes([slot & 0xFF, cc & 0xFF])
        + call + b"\x00"
    )
    return bytes([TLV_TAG_SET_INFO, len(body)]) + body

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

def run_http_server():
    server = ThreadingHTTPServer(("0.0.0.0", BRIDGE_HTTP_PORT), BridgeHTTPHandler)
    record_log(f"Bridge HTTP Server listening on port {BRIDGE_HTTP_PORT}")
    server.serve_forever()

# ---------------- Zello WebSocket Client Worker ----------------
async def zello_websocket_worker():
    """Asynchronous WebSocket client connecting to wss://zello.io/ws"""
    if not WEBSOCKETS_AVAILABLE:
        record_log("websockets package not available, waiting...")
        return

    udp_tx_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

    while True:
        if not bridge_state.enabled:
            bridge_state.ws_connected = False
            bridge_state.channel_joined = False
            await asyncio.sleep(4.0)
            continue

        if not bridge_state.has_key or not bridge_state.private_key_obj:
            record_log("Waiting for RSA private key to be configured in Settings...")
            await asyncio.sleep(5.0)
            continue

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
                record_log("Connected to Zello WebSocket. Sending logon command...")

                # 1. Send logon
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

                # 2. Message Loop
                async for msg in ws:
                    if isinstance(msg, str):
                        try:
                            data = json.loads(msg)
                            # Logon result
                            if data.get("command") == "logon" or data.get("success") is True:
                                bridge_state.channel_joined = True
                                record_log(f"Successfully joined Zello Channel: '{bridge_state.channel}'")
                            
                            # Stream started (Someone is talking on Zello)
                            if data.get("event") == "on_stream_start":
                                stream_id = data.get("stream_id")
                                speaker = data.get("from") or "ZelloUser"
                                bridge_state.active_speaker = speaker
                                bridge_state.active_stream_id = stream_id
                                bridge_state.last_rx_time = time.time()
                                record_log(f"Zello RX Started: '{speaker}' on stream {stream_id}")

                                # Send Dynamic SET_INFO TLV to USRP Target
                                tlv_frame = build_set_info_tlv(
                                    src_id=1022057,
                                    rpt_id=1022057,
                                    tg=bridge_state.target_tg,
                                    slot=bridge_state.slot,
                                    cc=1,
                                    callsign=speaker[:15]
                                )
                                tlv_pkt = build_usrp_frame(seq=bridge_state.tx_sequence, keyup=1, usrp_type=2, payload=tlv_frame)
                                bridge_state.tx_sequence += 1
                                try:
                                    udp_tx_sock.sendto(tlv_pkt, (USRP_HOST, 32000))
                                    udp_tx_sock.sendto(tlv_pkt, (USRP_HOST, USRP_PORT))
                                except Exception as se:
                                    logger.debug(f"USRP TLV Send: {se}")

                            # Stream stopped
                            elif data.get("event") == "on_stream_stop":
                                stream_id = data.get("stream_id")
                                record_log(f"Zello RX Ended for stream {stream_id}")
                                bridge_state.active_speaker = None
                                bridge_state.active_stream_id = None
                                
                                # Send unkey frame to USRP
                                unkey_pkt = build_usrp_frame(seq=bridge_state.tx_sequence, keyup=0, usrp_type=0, payload=b"\x00" * 320)
                                bridge_state.tx_sequence += 1
                                try:
                                    udp_tx_sock.sendto(unkey_pkt, (USRP_HOST, USRP_PORT))
                                except Exception:
                                    pass

                            # Error response
                            elif data.get("success") is False:
                                err = data.get("error", "Unknown Zello error")
                                record_log(f"Zello Error response: {err}")

                        except Exception as pe:
                            logger.debug(f"Error parsing json frame: {pe}")

                    elif isinstance(msg, bytes):
                        # Binary Audio packet from Zello: [0x01][4B stream_id][4B packet_seq][Opus audio payload]
                        if len(msg) > 9 and msg[0] == 0x01:
                            bridge_state.last_rx_time = time.time()
                            # 320 bytes zero-padded or decoded voice frame into USRP (20ms 8kHz mono)
                            # When full opus decoding pipeline is running, decoded PCM goes here
                            dummy_pcm = b"\x00" * 320
                            voice_pkt = build_usrp_frame(seq=bridge_state.tx_sequence, keyup=1, usrp_type=0, payload=dummy_pcm)
                            bridge_state.tx_sequence += 1
                            try:
                                udp_tx_sock.sendto(voice_pkt, (USRP_HOST, USRP_PORT))
                            except Exception:
                                pass

        except Exception as e:
            record_log(f"Zello WebSocket connection error: {e}. Reconnecting in 5s...")
            bridge_state.ws_connected = False
            bridge_state.channel_joined = False
            bridge_state.active_speaker = None
            await asyncio.sleep(5.0)

def main():
    record_log("Starting NinjaRadio Zello Channel API Bridge...")
    load_bridge_config()

    # Start HTTP Status server
    http_thread = threading.Thread(target=run_http_server, daemon=True)
    http_thread.start()

    # Start Asyncio WebSocket Loop
    try:
        asyncio.run(zello_websocket_worker())
    except KeyboardInterrupt:
        record_log("Zello Bridge stopped by user")

if __name__ == "__main__":
    main()
