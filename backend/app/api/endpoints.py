from fastapi import APIRouter, Depends, HTTPException, Query, Response, UploadFile, File
from sqlalchemy.ext.asyncio import AsyncSession
from typing import List, Optional
from datetime import datetime
import psutil
import time

from backend.app.core.database import get_db
from backend.app.core.config import settings
from backend.app.models.schemas import (
    QsoLogResponse, QsoLogCreate,
    CallbookResponse, CallbookCreate,
    BridgeRouteResponse, BridgeRouteCreate,
    ServerStatus, LastHeardItem, PttTransmitRequest, PinVerifyRequest
)
from backend.app.services.logbook_service import LogbookService
from backend.app.services.callbook_service import CallbookService
from backend.app.services.bridge_service import BridgeService
from backend.app.services.dv_monitor import manager

router = APIRouter()
START_TIME = time.time()

# ----------------- Dashboard & Status -----------------

@router.get("/status", response_model=ServerStatus)
async def get_server_status(session: AsyncSession = Depends(get_db)):
    uptime_sec = int(time.time() - START_TIME)
    hours, rem = divmod(uptime_sec, 3600)
    minutes, seconds = divmod(rem, 60)
    uptime_str = f"{hours}h {minutes}m {seconds}s"
    
    routes = await BridgeService.get_routes(session)
    active_bridges = len([r for r in routes if r.is_active])
    
    # Try reading system stats
    try:
        cpu = psutil.cpu_percent(interval=None)
        ram = psutil.virtual_memory().percent
    except Exception:
        cpu = 0.0
        ram = 0.0

    return ServerStatus(
        server_name="NinjaRadio Master Core",
        server_callsign=settings.SERVER_CALLSIGN,
        server_id=settings.SERVER_DMR_ID,
        version=settings.VERSION,
        uptime=uptime_str,
        active_transmissions=1 if manager.current_tx else 0,
        connected_hotspots=0,
        web_clients=len(manager.active_connections) or 1,
        connected_peers=0,
        active_bridges=active_bridges,
        cpu_usage=cpu,
        ram_usage=ram
    )

@router.get("/last-heard", response_model=List[LastHeardItem])
async def get_last_heard():
    return manager.last_heard_history

@router.get("/clients")
async def get_connected_clients():
    return manager.get_connected_clients()

# ----------------- Multi-Logbook -----------------

@router.get("/logbook", response_model=List[QsoLogResponse])
async def list_qso_logs(
    query: Optional[str] = None,
    category: Optional[str] = None,
    mode: Optional[str] = None,
    limit: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
    session: AsyncSession = Depends(get_db)
):
    return await LogbookService.get_logs(
        session=session,
        query=query,
        category=category,
        mode=mode,
        limit=limit,
        offset=offset
    )

@router.post("/logbook", response_model=QsoLogResponse)
async def create_qso_log(
    data: QsoLogCreate,
    session: AsyncSession = Depends(get_db)
):
    return await LogbookService.add_qso(session, data)

@router.get("/logbook/export/adif")
async def export_adif(
    category: Optional[str] = None,
    session: AsyncSession = Depends(get_db)
):
    adif_data = await LogbookService.generate_adif_export(session, category)
    filename = f"ninjaradio_export_{category or 'all'}_{datetime.utcnow().strftime('%Y%m%d_%H%M%S')}.adi"
    return Response(
        content=adif_data,
        media_type="text/plain",
        headers={"Content-Disposition": f"attachment; filename={filename}"}
    )

# ----------------- Callbook (RadioID / RAPI / CB) -----------------

@router.get("/callbook", response_model=List[CallbookResponse])
async def search_callbook(
    q: Optional[str] = Query("", description="Search by Callsign, Name, DMR ID, RAPI ID"),
    org: Optional[str] = Query("ALL", description="ORARI, RAPI, CB-11M, KOMUNITAS"),
    session: AsyncSession = Depends(get_db)
):
    return await CallbookService.search_callbook(session, query=q, organization=org)

@router.post("/callbook", response_model=CallbookResponse)
async def add_callbook_entry(
    data: CallbookCreate,
    session: AsyncSession = Depends(get_db)
):
    return await CallbookService.create_entry(session, data)

@router.post("/callbook/import")
async def import_callbook_file(
    file: UploadFile = File(...),
    session: AsyncSession = Depends(get_db)
):
    content = (await file.read()).decode("utf-8", errors="ignore")
    result = await CallbookService.import_batch_data(session, content, file.filename)
    if not result.get("success"):
        raise HTTPException(status_code=400, detail=result.get("error", "Failed to import callbook"))
    return result

# ----------------- Matrix Talkgroup Router -----------------

@router.get("/matrix", response_model=List[BridgeRouteResponse])
async def get_matrix_routes(session: AsyncSession = Depends(get_db)):
    return await BridgeService.get_routes(session)

@router.post("/matrix", response_model=BridgeRouteResponse)
async def create_matrix_route(
    data: BridgeRouteCreate,
    session: AsyncSession = Depends(get_db)
):
    return await BridgeService.create_route(session, data)

@router.post("/matrix/{route_id}/toggle", response_model=BridgeRouteResponse)
async def toggle_matrix_route(
    route_id: int,
    session: AsyncSession = Depends(get_db)
):
    entry = await BridgeService.toggle_route(session, route_id)
    if not entry:
        raise HTTPException(status_code=404, detail="Route not found")
    return entry

TG_NAMES = {
    510: "Indo National DMR",
    5101: "Jawa Barat Regional",
    5102: "Jawa Tengah Regional",
    5103: "Jawa Timur Regional",
    5109: "RAPI Nusantara ASL",
    5108: "11M CB Digital Gateway",
    5100: "Emergency SAR 911",
    9990: "Audio Test Echo / Parrot",
    91: "BrandMeister Worldwide DX",
}

async def trigger_parrot_reply(caller_callsign: str, dmr_id: int, slot: int):
    """Simulates real DMR repeater Parrot / Echo service replying back after PTT release."""
    try:
        await asyncio.sleep(1.5)
        # 1. Parrot starts transmitting back on TG 9990
        parrot_tx = {
            "id": f"tx_parrot_{int(datetime.utcnow().timestamp())}",
            "callsign": "PARROT",
            "name": f"Echo Audio Playback ({caller_callsign})",
            "dmr_id": 9990,
            "talkgroup": 9990,
            "tg_name": "Audio Test Echo / Parrot",
            "slot": slot,
            "mode": "DMR (Echo Server)",
            "category": "Amatir",
            "rssi": "-48 dBm",
            "ber": "0.0%",
            "started_at": datetime.utcnow().strftime("%H:%M:%S")
        }
        await manager.update_tx_state(parrot_tx)
        
        # Audio playback duration
        await asyncio.sleep(3.5)
        
        # 2. Parrot stops transmitting
        await manager.update_tx_state(None)
        
        # 3. Push Parrot to Last Heard
        lh_item = {
            "id": f"tx_parrot_{int(datetime.utcnow().timestamp())}",
            "timestamp": datetime.utcnow().strftime("%H:%M:%S"),
            "callsign": "PARROT",
            "name": f"Echo Audio Playback ({caller_callsign})",
            "dmr_id": 9990,
            "talkgroup": 9990,
            "tg_name": "Audio Test Echo / Parrot",
            "slot": slot,
            "mode": "DMR (Echo Server)",
            "category": "Amatir",
            "duration": "3.5s",
            "loss": "0.0%",
            "ber": "0.0%",
            "rssi": "-48 dBm",
            "is_tx": False
        }
        await manager.push_last_heard(lh_item)
    except Exception as e:
        pass

CORE_USRP_HOST = "core"
CORE_USRP_PORT = 32345

def send_real_usrp_packet_to_core(callsign: str, dmr_id: int, tg: int, slot: int, is_tx: bool):
    """
    Constructs and dispatches standard 32-byte DVSwitch / USRP UDP packets
    directly to the NinjaRadio Core engine (core:32345) and Mumble Bridge.
    """
    try:
        import socket
        import struct
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        key_flag = 1 if is_tx else 0
        header = struct.pack(">4sIIIIIII", b"USRP", 1, 0, key_flag, tg, dmr_id, slot, 0)
        payload = b"\x00" * 320
        packet = header + payload

        # Send to Core USRP port (32345)
        sock.sendto(packet, (CORE_USRP_HOST, CORE_USRP_PORT))
        # Send to Mumble Bridge port (12346)
        try:
            sock.sendto(packet, ("bridge-mumble", 12346))
        except Exception:
            pass
    except Exception:
        pass

@router.post("/auth/verify-pin")
async def verify_admin_pin(payload: PinVerifyRequest):
    """Verifies operator / admin security PIN for PTT authorization."""
    import os
    admin_pin = str(os.getenv("ADMIN_PIN") or getattr(settings, "ADMIN_PIN", "1234")).strip()
    return {"valid": payload.pin.strip() == admin_pin}

@router.post("/ptt/transmit")
async def web_ptt_transmit(payload: PttTransmitRequest):
    callsign = (payload.callsign or "SWL-MONITOR").strip()
    dmr_id = payload.dmr_id or 0
    tg = payload.tg if payload.tg is not None else 510
    slot = payload.slot or 1
    category = payload.category or "Amatir"
    is_transmitting = payload.is_transmitting if payload.is_transmitting is not None else True

    # Anti-abuse validation: Monitor mode cannot transmit
    if is_transmitting:
        if callsign.upper() in ["SWL-MONITOR", "MONITOR", "LISTENER", "GUEST", "TAMU"] or dmr_id == 0:
            raise HTTPException(
                status_code=403,
                detail="Mode Monitor (SWL) tidak diizinkan memancar. Silakan gunakan profil Callsign pribadi atau masukkan PIN Operator."
            )

    tg_name = TG_NAMES.get(tg, f"Talkgroup {tg}")

    # Dispatch actual network UDP packet to core and audio bridge
    send_real_usrp_packet_to_core(callsign, dmr_id, tg, slot, is_transmitting)

    if is_transmitting:
        tx_data = {
            "id": f"tx_web_{int(datetime.utcnow().timestamp())}",
            "callsign": callsign,
            "name": f"Web Operator ({callsign})",
            "dmr_id": dmr_id,
            "talkgroup": tg,
            "tg_name": tg_name,
            "slot": slot,
            "mode": "DMR (WebRTC)",
            "category": category,
            "rssi": "-55 dBm",
            "ber": "0.0%",
            "started_at": datetime.utcnow().strftime("%H:%M:%S")
        }
        await manager.update_tx_state(tx_data)
        return {"status": "TX_STARTED", "data": tx_data}
    else:
        await manager.update_tx_state(None)
        lh_item = {
            "id": f"tx_web_{int(datetime.utcnow().timestamp())}",
            "timestamp": datetime.utcnow().strftime("%H:%M:%S"),
            "callsign": callsign,
            "name": f"Web Operator ({callsign})",
            "dmr_id": dmr_id,
            "talkgroup": tg,
            "tg_name": tg_name,
            "slot": slot,
            "mode": "DMR (WebRTC)",
            "category": category,
            "duration": "3.8s",
            "loss": "0.0%",
            "ber": "0.0%",
            "rssi": "-55 dBm",
            "is_tx": False
        }
        await manager.push_last_heard(lh_item)

        # Trigger automatic Parrot reply if user transmitted to TG 9990
        if tg == 9990:
            asyncio.create_task(trigger_parrot_reply(callsign, dmr_id, slot))

        return {"status": "TX_STOPPED", "data": lh_item}

# ----------------- Server Configuration Management -----------------

SERVER_CONFIG_CACHE = {
    "server_callsign": settings.SERVER_CALLSIGN,
    "server_dmr_id": settings.SERVER_DMR_ID,
    "server_name": "NinjaRadio Indonesia Master Core",
    "server_location": "Jakarta, Indonesia",
    "default_tg": settings.DEFAULT_TALKGROUP,
    "hotspot_port": settings.DMR_MASTER_PORT,
    "hotspot_password": "ninjaradio_pass",
    "max_hotspots": 128,
    "peer_host": "master.freedmr.org",
    "peer_port": settings.HBLINK_PEER_PORT,
    "peer_password": "openbridge_secret",
    "subscribed_tgs": "510, 5101, 5102, 5108, 5109, 5100",
    "usrp_port": settings.USRP_AUDIO_PORT,
    "ysf_room_id": 51000,
    "asl_node": 59123,
    "xlx_reflector": "XLX510 B"
}

@router.get("/config")
async def get_system_config():
    return SERVER_CONFIG_CACHE

@router.post("/config")
async def save_system_config(config: dict):
    SERVER_CONFIG_CACHE.update(config)
    return {"status": "SUCCESS", "message": "Konfigurasi server berhasil diperbarui secara live", "data": SERVER_CONFIG_CACHE}

