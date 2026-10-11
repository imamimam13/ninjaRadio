import asyncio
import socket
import logging
import struct
import json
import os
import time
from typing import Dict, List
import aiohttp
from fastapi import FastAPI
import uvicorn

from dmr_client import HomeBrewClient

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] [NinjaRadio-Core] %(message)s")
logger = logging.getLogger("NinjaRadio-Core")

CONFIG_PATH = os.getenv("CONFIG_PATH", "/app/data/config.json")
BACKEND_EVENT_URL = os.getenv("BACKEND_EVENT_URL", "http://backend:8000/api/internal/dmr_event")
MUMBLE_BRIDGE_PORT = 12346
USRP_AUDIO_PORT = int(os.getenv("USRP_AUDIO_PORT", 32345))
DMR_MASTER_PORT = int(os.getenv("DMR_MASTER_PORT", 62031))

# Default system configuration if not yet configured from Web UI
DEFAULT_CONFIG = {
    "server_callsign": os.getenv("SERVER_CALLSIGN", "N1NJA"),
    "server_dmr_id": int(os.getenv("SERVER_DMR_ID", 5109999)),
    "server_name": "NinjaRadio Master Core",
    "hotspot_port": DMR_MASTER_PORT,
    "hotspot_password": "ninjaradio_pass",
    "peering": []
}

class CoreEngineManager:
    def __init__(self):
        self.config = {}
        self.active_clients: Dict[str, HomeBrewClient] = {}
        self.http_session: Optional[aiohttp.ClientSession] = None
        self.bridge_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

    def load_config(self) -> Dict:
        if os.path.exists(CONFIG_PATH):
            try:
                with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    logger.info(f"Loaded configuration from {CONFIG_PATH}")
                    self.config = data
                    return data
            except Exception as e:
                logger.error(f"Error reading {CONFIG_PATH}: {e}")

        # Fall back to default
        self.config = DEFAULT_CONFIG.copy()
        # Ensure dir exists and save default
        try:
            os.makedirs(os.path.dirname(CONFIG_PATH), exist_ok=True)
            with open(CONFIG_PATH, "w", encoding="utf-8") as f:
                json.dump(self.config, f, indent=2)
        except Exception:
            pass
        return self.config

    async def init(self):
        self.http_session = aiohttp.ClientSession()
        self.load_config()
        await self.apply_peers()

    async def apply_peers(self):
        """Applies configured peers dynamically without restarting the container."""
        peers = self.config.get("peering", [])
        configured_ids = set()

        for p in peers:
            peer_id = p.get("id") or f"{p.get('host')}:{p.get('port')}"
            configured_ids.add(peer_id)
            is_active = p.get("isActive", True)

            # If client exists and settings changed or disabled, stop it
            if peer_id in self.active_clients:
                client = self.active_clients[peer_id]
                if not is_active or client.host != p.get("host") or client.port != int(p.get("port")):
                    logger.info(f"Stopping client {peer_id}...")
                    await client.stop()
                    del self.active_clients[peer_id]

            # Start client if active and not already running
            if is_active and peer_id not in self.active_clients:
                host = p.get("host")
                port = int(p.get("port", 60231))
                password = p.get("password", "")
                tgs = p.get("subscribedTgs", "1025, 1023")
                callsign = p.get("callsign") or self.config.get("server_callsign", "N1NJA")
                dmr_id = int(p.get("dmr_id") or self.config.get("server_dmr_id", 5109999))

                logger.info(f"🚀 Initializing active HomeBrew client for {p.get('name', host)} -> {host}:{port} (TGs: {tgs})...")
                client = HomeBrewClient(
                    name=p.get("name") or host,
                    host=host,
                    port=port,
                    dmr_id=dmr_id,
                    callsign=callsign,
                    password=password,
                    subscribed_tgs=tgs,
                    on_traffic=self.handle_incoming_dmr_traffic,
                    on_status_change=self.handle_peer_status_change
                )
                self.active_clients[peer_id] = client
                await client.start()

        # Stop any removed peers
        for current_id in list(self.active_clients.keys()):
            if current_id not in configured_ids:
                logger.info(f"Removing deleted peer client {current_id}...")
                await self.active_clients[current_id].stop()
                del self.active_clients[current_id]

    def handle_incoming_dmr_traffic(self, traffic_data: dict):
        """Called when a real DMR frame arrives from the DMR Master."""
        # 1. Forward to Mumble bridge (UDP 12346)
        try:
            src_id = traffic_data["src_id"]
            dst_tg = traffic_data["dst_tg"]
            slot = traffic_data["slot"]
            # Construct USRP packet for audio bridge
            header = struct.pack(">4sIIIIIII", b"USRP", 1, 0, 1, dst_tg, src_id, slot, 0)
            payload = traffic_data.get("payload", b"")
            self.bridge_sock.sendto(header + payload, ("bridge-mumble", MUMBLE_BRIDGE_PORT))
        except Exception:
            pass

        # 2. Dispatch event to backend API for WebSocket broadcast (async in background)
        asyncio.create_task(self.dispatch_event_to_backend(traffic_data))

    async def dispatch_event_to_backend(self, traffic: dict):
        if not self.http_session:
            return
        try:
            async with self.http_session.post(BACKEND_EVENT_URL, json=traffic, timeout=2.0) as resp:
                pass
        except Exception:
            pass

    def handle_peer_status_change(self, peer_name: str, state: str):
        logger.info(f"[PEER-STATUS] {peer_name} is now {state}")

    def transmit_ptt(self, callsign: str, dmr_id: int, tg: int, slot: int, is_transmitting: bool):
        """Transmits PTT frames through all active connected DMR peers."""
        dtype = 1 if is_transmitting else 2  # 1=Voice Header, 2=Voice Terminator
        success = False
        for peer_id, client in self.active_clients.items():
            if client.state == "CONNECTED":
                res = client.transmit_dmrd(
                    src_id=dmr_id,
                    dst_tg=tg,
                    slot=slot,
                    dtype_vseq=dtype
                )
                if res:
                    success = True
                    logger.info(f"[PTT-TX] Sent frame to {client.name} (TG: {tg}, Slot: {slot}, TX: {is_transmitting})")
        return success

    async def shutdown(self):
        for client in self.active_clients.values():
            await client.stop()
        if self.http_session:
            await self.http_session.close()

# ----------------- FastAPI Internal Control Server (Port 5000) -----------------
app = FastAPI(title="NinjaRadio Core Engine Control API")
engine_mgr = CoreEngineManager()

@app.on_event("startup")
async def on_startup():
    await engine_mgr.init()

@app.on_event("shutdown")
async def on_shutdown():
    await engine_mgr.shutdown()

@app.get("/api/status")
async def get_core_status():
    peers_status = [client.get_status_info() for client in engine_mgr.active_clients.values()]
    return {
        "status": "ONLINE",
        "engine": "OpenDMR / FreeDMR HomeBrew Multi-Client Core",
        "active_clients_count": len(engine_mgr.active_clients),
        "peers": peers_status,
        "config": engine_mgr.config
    }

@app.post("/api/reload")
async def reload_configuration():
    """Reloads /app/data/config.json dynamically and updates all active links."""
    engine_mgr.load_config()
    await engine_mgr.apply_peers()
    return {
        "status": "SUCCESS",
        "message": "Configuration reloaded and applied to DMR engine.",
        "active_clients": list(engine_mgr.active_clients.keys())
    }

@app.post("/api/tx")
async def ptt_transmit(payload: dict):
    callsign = payload.get("callsign", "N1NJA")
    dmr_id = int(payload.get("dmr_id", 5109999))
    tg = int(payload.get("tg", 510))
    slot = int(payload.get("slot", 1))
    is_tx = bool(payload.get("is_transmitting", True))

    res = engine_mgr.transmit_ptt(callsign, dmr_id, tg, slot, is_tx)
    return {"status": "TX_DISPATCHED", "sent_to_masters": res}

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=5000, log_level="info")
