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
from dmr_server import HomeBrewMasterServer

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
        self.master_server: Optional[HomeBrewMasterServer] = None
        self.http_session: Optional[aiohttp.ClientSession] = None
        self.bridge_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.usrp_sock: Optional[socket.socket] = None
        self.usrp_task: Optional[asyncio.Task] = None
        self.registered_usrp_clients: Dict[tuple, float] = {}
        self.parrot_buffers: Dict[tuple, list] = {}
        self.running = True

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
        self.running = True

        # Start USRP DVSwitch UDP Listener
        try:
            self.usrp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.usrp_sock.bind(("0.0.0.0", USRP_AUDIO_PORT))
            self.usrp_sock.setblocking(False)
            self.usrp_task = asyncio.create_task(self.run_usrp_listener())
            logger.info(f"📱 DVSwitch USRP Audio Engine active on UDP port {USRP_AUDIO_PORT}")
        except Exception as e:
            logger.error(f"Failed to bind USRP socket on port {USRP_AUDIO_PORT}: {e}")

        # Start Inbound DMR Master Server for local hotspots (port 62031)
        try:
            hp_port = int(self.config.get("hotspot_port", DMR_MASTER_PORT))
            hp_pass = self.config.get("hotspot_password", "ninjaradio_pass")
            srv_call = self.config.get("server_callsign", "N1NJA")
            srv_id = int(self.config.get("server_dmr_id", 5109999))
            self.master_server = HomeBrewMasterServer(
                port=hp_port,
                password=hp_pass,
                server_dmr_id=srv_id,
                server_callsign=srv_call,
                on_traffic=self.handle_incoming_dmr_traffic
            )
            await self.master_server.start()
            logger.info(f"📻 Local DMR Master Server active on UDP port {hp_port} for Pi-Star/Hotspots")
        except Exception as e:
            logger.error(f"Failed to bind DMR Master Server on port {DMR_MASTER_PORT}: {e}")

        await self.apply_peers()

    async def apply_peers(self):
        """Applies configured peers dynamically without restarting the container."""
        peers = self.config.get("peering", [])
        configured_ids = set()

        for p in peers:
            peer_id = p.get("id") or f"{p.get('host')}:{p.get('port')}"
            configured_ids.add(peer_id)
            is_active = p.get("isActive", True)
            host = p.get("host")
            port = int(p.get("port", 62031))
            password = p.get("password", "")
            tgs = p.get("subscribedTgs") or p.get("subscribed_tgs", "")
            callsign = (p.get("callsign") or self.config.get("server_callsign", "N1NJA")).strip().upper()
            dmr_id = int(p.get("dmr_id") or p.get("dmrId") or self.config.get("server_dmr_id", 5109999))

            # If client exists and ANY settings changed or disabled, stop it so it reloads with new credentials
            if peer_id in self.active_clients:
                client = self.active_clients[peer_id]
                if (not is_active or 
                    client.host != host or 
                    client.port != port or 
                    client.dmr_id != dmr_id or 
                    client.callsign != callsign or 
                    client.password != password or 
                    client.subscribed_tgs != tgs):
                    logger.info(f"Configuration changed for peer {peer_id} (DMR ID: {client.dmr_id} -> {dmr_id}, Callsign: {client.callsign} -> {callsign}). Re-connecting...")
                    await client.stop()
                    del self.active_clients[peer_id]

            # Start client if active and not already running
            if is_active and peer_id not in self.active_clients:
                logger.info(f"🚀 Initializing active HomeBrew client for {p.get('name', host)} -> {host}:{port} (DMR ID: {dmr_id}, Callsign: {callsign}, TGs: {tgs})...")
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
        """Called when a real DMR frame arrives from an external Master or local hotspot."""
        try:
            src_id = traffic_data["src_id"]
            dst_tg = traffic_data["dst_tg"]
            slot = traffic_data["slot"]
            raw_packet = traffic_data.get("raw_packet")
            network = traffic_data.get("network", "")

            # Anti-echo suppression: Drop if src_id is our own client DMR ID or server DMR ID
            client_ids = {c.dmr_id for c in self.active_clients.values()}
            if self.master_server:
                client_ids.add(self.master_server.server_dmr_id)
            if src_id in client_ids:
                return

            # Anti-echo suppression: Drop if src_id was transmitting locally within the last 1.5s
            if src_id == getattr(self, "last_tx_src_id", None) and (time.time() - getattr(self, "last_tx_time", 0)) < 1.5:
                return

            # 1. Bi-directional DMR cross-routing between Master Server and External Peers
            if "Local DMR Master" in network:
                # Arrived from local hotspot -> Forward out to all connected external DMR peers
                if raw_packet:
                    for client in self.active_clients.values():
                        if client.state == "CONNECTED" and client.sock and client.target_addr:
                            try:
                                client.sock.sendto(raw_packet, client.target_addr)
                            except Exception:
                                pass
            else:
                # Arrived from external DMR peer -> Forward to all local hotspots connected to this master
                if raw_packet and self.master_server:
                    self.master_server.broadcast_dmrd(raw_packet)

            # 2. Forward to Mumble bridge (UDP 12346) & DVSwitch clients
            payload = traffic_data.get("payload", b"")
            header = struct.pack(">4sIIIIIII", b"USRP", 1, 0, 1, dst_tg, src_id, slot, 0)
            usrp_packet = header + payload

            try:
                self.bridge_sock.sendto(usrp_packet, ("bridge-mumble", MUMBLE_BRIDGE_PORT))
            except Exception:
                pass

            self.broadcast_to_usrp_clients(usrp_packet)
        except Exception as e:
            logger.debug(f"handle_incoming_dmr_traffic error: {e}")

        # 3. Dispatch event to backend API for WebSocket broadcast (async in background)
        asyncio.create_task(self.dispatch_event_to_backend(traffic_data))

    def broadcast_to_usrp_clients(self, data: bytes, exclude_addr=None):
        if not self.usrp_sock:
            return
        now = time.time()
        for client_addr, last_seen in list(self.registered_usrp_clients.items()):
            if now - last_seen > 120:
                del self.registered_usrp_clients[client_addr]
                continue
            if client_addr != exclude_addr:
                try:
                    self.usrp_sock.sendto(data, client_addr)
                except Exception:
                    pass

    async def run_usrp_listener(self):
        """Listens for incoming USRP UDP frames from DVSwitch Mobile Android app and Mumble Bridge."""
        loop = asyncio.get_running_loop()
        logger.info("📱 DVSwitch & Mumble USRP Ingress loop started.")
        while self.running:
            try:
                data, addr = await loop.sock_recvfrom(self.usrp_sock, 2048)
                if len(data) >= 32 and data.startswith(b"USRP"):
                    magic, seq, mem, key_flag, tg, dmr_id, slot, res = struct.unpack(">4sIIIIIII", data[:32])
                    self.registered_usrp_clients[addr] = time.time()

                    # Echo test service on TG 9990
                    if tg == 9990:
                        if key_flag == 1:
                            if addr not in self.parrot_buffers:
                                self.parrot_buffers[addr] = []
                            self.parrot_buffers[addr].append(data)
                        else:
                            buffered = self.parrot_buffers.pop(addr, [])
                            if buffered:
                                asyncio.create_task(self.play_parrot_back(addr, buffered))
                    else:
                        payload = data[32:]
                        if key_flag == 1:
                            self.last_tx_src_id = dmr_id
                            self.last_tx_time = time.time()
                            # 1. Transmit voice burst to active DMR Master connections
                            for client in self.active_clients.values():
                                if client.state == "CONNECTED":
                                    client.transmit_dmrd(
                                        src_id=dmr_id,
                                        dst_tg=tg,
                                        slot=slot or 1,
                                        data_type=1,
                                        payload=payload
                                    )
                            # 2. Forward to local hotspots on DMR Master Server
                            if self.master_server:
                                dmrd_bits = (0x80 if slot == 2 else 0) | 0x10  # TS slot + Voice Burst
                                dmrd_pkt = (
                                    b"DMRD" +
                                    struct.pack("B", 1) +
                                    struct.pack(">I", dmr_id)[1:] +
                                    struct.pack(">I", tg)[1:] +
                                    struct.pack(">I", self.master_server.server_dmr_id) +
                                    struct.pack("B", dmrd_bits) +
                                    struct.pack(">I", int(time.time()) & 0xFFFFFFFF) +
                                    (payload[:33] if payload else b"").ljust(33, b"\x00")
                                )
                                self.master_server.broadcast_dmrd(dmrd_pkt)

                            # 3. Forward to Mumble bridge (UDP 12346)
                            try:
                                self.bridge_sock.sendto(data, ("bridge-mumble", MUMBLE_BRIDGE_PORT))
                            except Exception:
                                pass
                            # 4. Broadcast to other DVSwitch Mobile clients
                            self.broadcast_to_usrp_clients(data, exclude_addr=addr)
                            # 5. Dispatch live TX visualizer & audio to web UI
                            asyncio.create_task(self.dispatch_event_to_backend({
                                "network": "DVSwitch-USRP",
                                "src_id": dmr_id,
                                "dst_tg": tg,
                                "slot": slot or 1,
                                "call_type": "group",
                                "data_type": 1,
                                "payload": payload,
                                "is_tx": True
                            }))
                        else:
                            # PTT Release - send terminator burst
                            for client in self.active_clients.values():
                                if client.state == "CONNECTED":
                                    client.transmit_dmrd(
                                        src_id=dmr_id,
                                        dst_tg=tg,
                                        slot=slot or 1,
                                        data_type=2
                                    )
                            if self.master_server:
                                dmrd_bits = (0x80 if slot == 2 else 0) | 0x20  # TS slot + Terminator
                                dmrd_pkt = (
                                    b"DMRD" +
                                    struct.pack("B", 1) +
                                    struct.pack(">I", dmr_id)[1:] +
                                    struct.pack(">I", tg)[1:] +
                                    struct.pack(">I", self.master_server.server_dmr_id) +
                                    struct.pack("B", dmrd_bits) +
                                    struct.pack(">I", int(time.time()) & 0xFFFFFFFF) +
                                    b"\x00" * 33
                                )
                                self.master_server.broadcast_dmrd(dmrd_pkt)
                            try:
                                self.bridge_sock.sendto(data, ("bridge-mumble", MUMBLE_BRIDGE_PORT))
                            except Exception:
                                pass
                            self.broadcast_to_usrp_clients(data, exclude_addr=addr)
                            asyncio.create_task(self.dispatch_event_to_backend({
                                "network": "DVSwitch-USRP",
                                "src_id": dmr_id,
                                "dst_tg": tg,
                                "slot": slot or 1,
                                "call_type": "group",
                                "data_type": 2,
                                "is_tx": False
                            }))
            except asyncio.CancelledError:
                break
            except Exception as e:
                await asyncio.sleep(0.01)

    async def play_parrot_back(self, addr, packets: list):
        """Replays recorded voice packets back to DVSwitch caller like a DMR parrot repeater."""
        logger.info(f"[PARROT] 🦜 Triggering Echo Playback to {addr} ({len(packets)} frames)...")
        await asyncio.sleep(0.8)
        for pkt in packets:
            try:
                self.usrp_sock.sendto(pkt, addr)
                await asyncio.sleep(0.02)  # Standard 20ms frame pacing
            except Exception:
                pass
        # Send final release packet
        try:
            rel = struct.pack(">4sIIIIIII", b"USRP", 9999, 0, 0, 9990, 9990, 1, 0)
            self.usrp_sock.sendto(rel, addr)
        except Exception:
            pass
        logger.info(f"[PARROT] ✓ Echo Playback finished for {addr}")

    async def dispatch_event_to_backend(self, traffic: dict):
        if not self.http_session:
            return
        try:
            import base64
            data_to_send = traffic.copy()
            if "payload" in data_to_send and isinstance(data_to_send["payload"], bytes):
                data_to_send["payload_b64"] = base64.b64encode(data_to_send["payload"]).decode("ascii")
                data_to_send.pop("payload", None)
            if "raw_packet" in data_to_send:
                data_to_send.pop("raw_packet", None)

            timeout = aiohttp.ClientTimeout(total=2.0)
            async with self.http_session.post(BACKEND_EVENT_URL, json=data_to_send, timeout=timeout) as resp:
                pass
        except Exception as e:
            logger.debug(f"dispatch_event_to_backend note: {e}")

    def handle_peer_status_change(self, peer_name: str, state: str):
        logger.info(f"[PEER-STATUS] {peer_name} is now {state}")

    def transmit_ptt(self, callsign: str, dmr_id: int, tg: int, slot: int, is_transmitting: bool):
        """Transmits PTT frames through all active connected DMR peers and local hotspots."""
        data_type = 1 if is_transmitting else 2  # 1=Voice Burst, 2=Voice Terminator
        success = False
        for peer_id, client in self.active_clients.items():
            if client.state == "CONNECTED":
                res = client.transmit_dmrd(
                    src_id=dmr_id,
                    dst_tg=tg,
                    slot=slot,
                    data_type=data_type
                )
                if res:
                    success = True
                    logger.info(f"[PTT-TX] Sent frame to {client.name} (TG: {tg}, Slot: {slot}, TX: {is_transmitting})")

        if self.master_server:
            dmrd_bits = (0x80 if slot == 2 else 0) | ((data_type & 0x03) << 4)
            dmrd_pkt = (
                b"DMRD" +
                struct.pack("B", 1) +
                struct.pack(">I", dmr_id)[1:] +
                struct.pack(">I", tg)[1:] +
                struct.pack(">I", self.master_server.server_dmr_id) +
                struct.pack("B", dmrd_bits) +
                struct.pack(">I", int(time.time()) & 0xFFFFFFFF) +
                b"\x00" * 33
            )
            self.master_server.broadcast_dmrd(dmrd_pkt)
            success = True

        return success

    async def shutdown(self):
        self.running = False
        if self.master_server:
            await self.master_server.stop()
        if self.usrp_task:
            self.usrp_task.cancel()
        if self.usrp_sock:
            try:
                self.usrp_sock.close()
            except Exception:
                pass
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
    hotspots = []
    if engine_mgr.master_server:
        for addr, client in engine_mgr.master_server.clients.items():
            hotspots.append({
                "address": f"{addr[0]}:{addr[1]}",
                "dmr_id": client.get("dmr_id"),
                "callsign": client.get("callsign"),
                "state": client.get("state"),
                "last_seen": client.get("last_seen")
            })
    return {
        "status": "ONLINE",
        "engine": "NinjaRadio Standalone DMR Master & Multi-Peer Gateway",
        "master_server": {
            "status": "ONLINE" if engine_mgr.master_server else "OFFLINE",
            "port": engine_mgr.master_server.port if engine_mgr.master_server else DMR_MASTER_PORT,
            "connected_hotspots": len(hotspots),
            "hotspots": hotspots
        },
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
