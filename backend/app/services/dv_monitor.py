import asyncio
import json
import random
from datetime import datetime, timezone
from typing import List, Dict, Set
from fastapi import WebSocket

def get_current_iso_utc() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

SAMPLE_ACTIVE_USERS = [
    {"callsign": "YB0AZ", "name": "Budi Santoso", "dmr_id": 5100001, "tg": 510, "tg_name": "Indonesia National", "slot": 1, "mode": "DMR", "category": "Amatir"},
    {"callsign": "YB1AR", "name": "Arief Rahman", "dmr_id": 5101001, "tg": 5101, "tg_name": "Jawa Barat Regional", "slot": 2, "mode": "YSF", "category": "Amatir"},
    {"callsign": "YC2BBS", "name": "Bambang Sudarmo", "dmr_id": 5102002, "tg": 5102, "tg_name": "Jawa Tengah Regional", "slot": 1, "mode": "DMR", "category": "Amatir"},
    {"callsign": "YD3NJA", "name": "Ninja Operator", "dmr_id": 5103003, "tg": 510, "tg_name": "Indonesia National", "slot": 1, "mode": "DMR", "category": "Amatir"},
    {"callsign": "JZ10XYZ", "name": "Rapi Ranger 01", "dmr_id": 5109101, "tg": 5109, "tg_name": "RAPI Nusantara TG", "slot": 1, "mode": "ANALOG", "category": "RAPI"},
    {"callsign": "91-DX-001", "name": "Garuda DXer", "dmr_id": 5109201, "tg": 5108, "tg_name": "11M CB Gateway", "slot": 1, "mode": "CB", "category": "CB"},
    {"callsign": "RESCUE-01", "name": "Relawan SAR Nusantara", "dmr_id": 5109301, "tg": 5100, "tg_name": "Emergency SAR 911", "slot": 1, "mode": "P25", "category": "Komunitas"},
    {"callsign": "YB7DX", "name": "Hendra Borneo", "dmr_id": 5107001, "tg": 5107, "tg_name": "Kalimantan Regional", "slot": 1, "mode": "DMR", "category": "Amatir"},
]

class ConnectionManager:
    def __init__(self):
        self.active_connections: Set[WebSocket] = set()
        self.client_sessions: Dict[WebSocket, Dict] = {}
        self.last_heard_history: List[Dict] = []
        self.current_tx: Dict = None
        self.active_streams: Dict[str, Dict] = {}

    def get_active_tx_list(self) -> List[Dict]:
        """Returns all active transmissions ordered chronologically: first transmit stays at top, newer appear below."""
        streams = list(self.active_streams.values())
        streams.sort(key=lambda s: s.get("start_time", 0.0))
        return streams

    async def connect(self, websocket: WebSocket):
        await websocket.accept()
        self.active_connections.add(websocket)

        # Detect Client IP
        client_ip = "127.0.0.1"
        if websocket.headers.get("x-forwarded-for"):
            client_ip = websocket.headers.get("x-forwarded-for").split(",")[0].strip()
        elif websocket.client:
            client_ip = websocket.client.host

        # Detect Device / OS
        user_agent = websocket.headers.get("user-agent", "Unknown Device")
        device_type = "Desktop PC / Mac"
        if "Android" in user_agent:
            device_type = "Android Mobile (Mumla/Browser)"
        elif "iPhone" in user_agent or "iPad" in user_agent:
            device_type = "Apple iOS (iPhone/iPad)"
        elif "Mobile" in user_agent:
            device_type = "Mobile Device"
        elif "Linux" in user_agent:
            device_type = "Linux PC / Gateway"

        app_name = websocket.query_params.get("app", "NOC Dashboard")
        callsign = websocket.query_params.get("callsign", "Web Operator")

        session_id = f"client_{abs(id(websocket)) % 100000}"
        self.client_sessions[websocket] = {
            "id": session_id,
            "ip": client_ip,
            "device": device_type,
            "app": app_name,
            "callsign": callsign,
            "connected_at": get_current_iso_utc(),
            "status": "ONLINE"
        }

        # Send initial state
        active_list = self.get_active_tx_list()
        await websocket.send_json({
            "type": "INITIAL_STATE",
            "data": {
                "last_heard": self.last_heard_history,
                "current_tx": self.current_tx,
                "active_tx_list": active_list
            }
        })

    def disconnect(self, websocket: WebSocket):
        self.active_connections.discard(websocket)
        self.client_sessions.pop(websocket, None)

    def get_connected_clients(self) -> List[Dict]:
        return list(self.client_sessions.values())

    async def broadcast(self, message: Dict):
        dead_connections = set()
        for connection in list(self.active_connections):
            try:
                await connection.send_json(message)
            except Exception:
                dead_connections.add(connection)
        for dead in dead_connections:
            self.active_connections.discard(dead)

    async def push_last_heard(self, item: Dict):
        # Deduplication check: if latest entry is the same DMR ID, talkgroup and slot, update duration rather than duplicating
        if self.last_heard_history:
            prev = self.last_heard_history[0]
            if (prev.get("dmr_id") == item.get("dmr_id") and 
                prev.get("talkgroup") == item.get("talkgroup") and 
                prev.get("slot") == item.get("slot")):
                prev["duration"] = item.get("duration", prev.get("duration"))
                return

        self.last_heard_history.insert(0, item)
        if len(self.last_heard_history) > 100:
            self.last_heard_history.pop()
        await self.broadcast({
            "type": "LAST_HEARD_UPDATE",
            "data": item
        })

    async def update_stream(self, stream_key: str, stream_data: Dict = None):
        """Updates or removes a single stream without disrupting other concurrent transmissions."""
        if stream_data is None:
            self.active_streams.pop(stream_key, None)
        else:
            self.active_streams[stream_key] = stream_data

        active_list = self.get_active_tx_list()
        self.current_tx = active_list[0] if active_list else None
        await self.broadcast({
            "type": "TX_STATE_CHANGE",
            "data": self.current_tx,
            "active_tx_list": active_list
        })

    async def update_tx_state(self, tx_data: Dict = None):
        if tx_data is None:
            self.active_streams.clear()
            self.current_tx = None
            await self.broadcast({
                "type": "TX_STATE_CHANGE",
                "data": None,
                "active_tx_list": []
            })
        else:
            s_key = tx_data.get("stream_key") or tx_data.get("id") or "default_stream"
            await self.update_stream(s_key, tx_data)

manager = ConnectionManager()

# Background live simulator to provide rich dynamic data out-of-the-box
async def run_dv_simulator():
    while True:
        try:
            await asyncio.sleep(random.randint(8, 20))
            user = random.choice(SAMPLE_ACTIVE_USERS)
            
            # Start TX
            tx_data = {
                "id": f"tx_{int(datetime.utcnow().timestamp())}",
                "callsign": user["callsign"],
                "name": user["name"],
                "dmr_id": user["dmr_id"],
                "talkgroup": user["tg"],
                "tg_name": user["tg_name"],
                "slot": user["slot"],
                "mode": user["mode"],
                "category": user["category"],
                "rssi": f"-{random.randint(60, 85)} dBm",
                "ber": "0.0%",
                "started_at": get_current_iso_utc()
            }
            await manager.update_tx_state(tx_data)
            
            # TX duration
            duration_s = random.randint(3, 10)
            await asyncio.sleep(duration_s)
            
            # Stop TX
            await manager.update_tx_state(None)
            
            # Add to Last Heard
            lh_item = {
                "id": tx_data["id"],
                "timestamp": get_current_iso_utc(),
                "callsign": user["callsign"],
                "name": user["name"],
                "dmr_id": user["dmr_id"],
                "talkgroup": user["tg"],
                "tg_name": user["tg_name"],
                "slot": user["slot"],
                "mode": user["mode"],
                "category": user["category"],
                "duration": f"{duration_s}s",
                "loss": "0.0%",
                "ber": "0.0%",
                "rssi": tx_data["rssi"],
                "is_tx": False
            }
            await manager.push_last_heard(lh_item)
            
        except asyncio.CancelledError:
            break
        except Exception as e:
            await asyncio.sleep(5)
