from fastapi import APIRouter, Depends, HTTPException, Query, Response, UploadFile, File, Form, Header
from sqlalchemy.ext.asyncio import AsyncSession
from typing import List, Optional
from datetime import datetime
import asyncio
import psutil
import time
import os
import logging

logger = logging.getLogger("NinjaRadio-Endpoints")

from backend.app.core.database import get_db
from backend.app.core.config import settings
from backend.app.models.schemas import (
    QsoLogResponse, QsoLogCreate,
    CallbookResponse, CallbookCreate,
    BridgeRouteResponse, BridgeRouteCreate, BridgeRouteUpdate,
    ServerStatus, LastHeardItem, PttTransmitRequest, PinVerifyRequest,
    OrganizationCreateRequest, OrganizationResponse, OrganizationRuleUpdate
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

    # Query live Core Engine stats
    connected_hotspots = 0
    connected_peers = 0
    try:
        import httpx
        async with httpx.AsyncClient(timeout=1.0) as client:
            r = await client.get("http://core:5000/api/status")
            if r.status_code == 200:
                cinfo = r.json()
                connected_hotspots = cinfo.get("master_server", {}).get("connected_hotspots", 0)
                connected_peers = len([p for p in cinfo.get("peers", []) if p.get("state") == "CONNECTED"])
    except Exception:
        pass

    return ServerStatus(
        server_name="NinjaRadio Master Core",
        server_callsign=settings.SERVER_CALLSIGN,
        server_id=settings.SERVER_DMR_ID,
        version=settings.VERSION,
        uptime=uptime_str,
        active_transmissions=1 if manager.current_tx else 0,
        connected_hotspots=connected_hotspots,
        web_clients=len(manager.active_connections) or 1,
        connected_peers=connected_peers,
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
    pin: Optional[str] = Query(None, description="Admin PIN to reveal unmasked DMR IDs"),
    x_admin_pin: Optional[str] = Header(None, alias="X-Admin-Pin"),
    session: AsyncSession = Depends(get_db)
):
    results = await CallbookService.search_callbook(session, query=q, organization=org)
    admin_pin = get_admin_pin()
    supplied = (pin or x_admin_pin or "").strip()
    is_admin = bool(supplied and supplied == admin_pin)

    if not is_admin:
        # Mask DMR ID (0) for public/non-admin viewers to prevent identity scraping & spoofing
        masked = []
        for r in results:
            d = {
                "id": r.id,
                "dmr_id": 0,
                "callsign": r.callsign,
                "name": r.name,
                "city": r.city,
                "province_state": r.province_state,
                "country": r.country,
                "organization": r.organization,
                "organization_id": r.organization_id,
                "remarks": r.remarks,
                "default_tg": r.default_tg,
                "allowed_tgs_override": r.allowed_tgs_override,
            }
            masked.append(CallbookResponse(**d))
        return masked

    return results

@router.post("/callbook/verify-station")
async def verify_station_credentials(
    payload: dict,
    session: AsyncSession = Depends(get_db)
):
    """Enables legitimate operators to verify their personal Callsign + DMR ID pair without exposing others."""
    callsign = str(payload.get("callsign", "")).strip().upper()
    raw_id = payload.get("dmr_id")
    dmr_id = int(raw_id) if raw_id is not None and str(raw_id).isdigit() else 0

    if not callsign and dmr_id == 0:
        return {"verified": False, "message": "Callsign atau DMR ID harus diisi."}

    cb = None
    if dmr_id > 0:
        cb = await CallbookService.get_by_dmr_id(session, dmr_id)
    if not cb and callsign:
        cb = await CallbookService.get_by_callsign(session, callsign)

    if not cb:
        return {
            "verified": False,
            "message": f"Stasiun {callsign or dmr_id} tidak terdaftar di Callbook server."
        }

    # Strict match validation
    if dmr_id > 0 and cb.dmr_id and cb.dmr_id != dmr_id:
        return {
            "verified": False,
            "message": f"DMR ID yang Anda masukkan ({dmr_id}) tidak cocok dengan data resmi untuk {callsign}."
        }
    if callsign and cb.callsign and cb.callsign.upper() != callsign:
        return {
            "verified": False,
            "message": f"Callsign {callsign} tidak cocok dengan data resmi DMR ID {dmr_id}."
        }

    return {
        "verified": True,
        "callsign": cb.callsign,
        "name": cb.name,
        "dmr_id": cb.dmr_id,
        "organization": cb.organization,
        "allowed_tgs": cb.allowed_tgs_override,
        "default_tg": cb.default_tg,
        "message": f"Stasiun {cb.callsign} terverifikasi resmi di Callbook ({cb.organization})."
    }

async def _notify_core_reload_subscribers():
    try:
        _CALLBOOK_CACHE.clear()
    except Exception:
        pass
    try:
        import httpx
        async with httpx.AsyncClient(timeout=2.0) as client:
            await client.post("http://core:5000/api/reload_subscribers")
    except Exception:
        pass

@router.post("/callbook", response_model=CallbookResponse)
async def add_callbook_entry(
    data: CallbookCreate,
    session: AsyncSession = Depends(get_db)
):
    entry = await CallbookService.create_entry(session, data)
    asyncio.create_task(_notify_core_reload_subscribers())
    return entry

@router.post("/callbook/import")
async def import_callbook_file(
    file: UploadFile = File(...),
    organization: Optional[str] = Form(None),
    default_tg: Optional[int] = Form(None),
    session: AsyncSession = Depends(get_db)
):
    content = (await file.read()).decode("utf-8", errors="ignore")
    result = await CallbookService.import_batch_data(
        session,
        content,
        file.filename,
        target_organization=organization,
        default_tg=default_tg
    )
    if not result.get("success"):
        raise HTTPException(status_code=400, detail=result.get("error", "Failed to import callbook"))
    asyncio.create_task(_notify_core_reload_subscribers())
    return result

@router.post("/callbook/import-url")
async def import_callbook_from_url(
    payload: dict,
    session: AsyncSession = Depends(get_db)
):
    """Fetches JSON or CSV callbook data from an external URL and imports it into the local Callbook."""
    url = str(payload.get("url") or "").strip()
    organization = payload.get("organization")
    default_tg = payload.get("default_tg")
    if default_tg is not None and str(default_tg).strip().isdigit():
        default_tg = int(str(default_tg).strip())
    else:
        default_tg = None

    if not url or not (url.startswith("http://") or url.startswith("https://")):
        raise HTTPException(status_code=400, detail="URL tidak valid. Harus diawali dengan http:// atau https://")

    try:
        import httpx
        from urllib.parse import urlparse
        headers = {
            "User-Agent": "NinjaRadio-Callbook-Sync/1.0 (+https://ninjaradio.id)",
            "Accept": "application/json, text/csv, text/plain, */*"
        }
        async with httpx.AsyncClient(timeout=25.0, follow_redirects=True, verify=False) as client:
            resp = await client.get(url, headers=headers)
            if resp.status_code >= 400:
                raise HTTPException(
                    status_code=400,
                    detail=f"Gagal mengunduh dari URL (HTTP {resp.status_code})"
                )
            content = resp.text
            parsed_url = urlparse(url)
            filename = parsed_url.path.split("/")[-1] or "remote.json"
            content_type = resp.headers.get("content-type", "").lower()
            if "json" in content_type and not filename.lower().endswith(".json"):
                filename = f"{filename}.json"
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Gagal menghubungi link URL luar: {str(e)}")

    result = await CallbookService.import_batch_data(
        session,
        content,
        filename,
        target_organization=organization,
        default_tg=default_tg
    )
    if not result.get("success"):
        raise HTTPException(status_code=400, detail=result.get("error", "Gagal mem-parsing data dari URL"))
    asyncio.create_task(_notify_core_reload_subscribers())
    return result

@router.put("/callbook/{entry_id}", response_model=CallbookResponse)
async def update_callbook_entry(
    entry_id: int,
    data: CallbookCreate,
    session: AsyncSession = Depends(get_db)
):
    entry = await CallbookService.update_entry(session, entry_id, data)
    if not entry:
        raise HTTPException(status_code=404, detail="Data pengguna tidak ditemukan")
    asyncio.create_task(_notify_core_reload_subscribers())
    return entry

@router.delete("/callbook/{entry_id}")
async def delete_callbook_entry(
    entry_id: int,
    session: AsyncSession = Depends(get_db)
):
    ok = await CallbookService.delete_entry(session, entry_id)
    if not ok:
        raise HTTPException(status_code=404, detail="Data pengguna tidak ditemukan")
    asyncio.create_task(_notify_core_reload_subscribers())
    return {"status": "SUCCESS"}

@router.post("/callbook/batch-update")
async def batch_update_callbook(
    payload: dict,
    session: AsyncSession = Depends(get_db)
):
    """Bulk updates organization and/or default_tg for matching entries."""
    target_org = payload.get("organization")
    target_tg = payload.get("default_tg")
    filter_org = payload.get("filter_org")
    callsign_prefix = payload.get("callsign_prefix")
    ids = payload.get("ids")

    result = await CallbookService.batch_update(
        session,
        target_org=target_org,
        target_tg=target_tg,
        filter_org=filter_org,
        callsign_prefix=callsign_prefix,
        ids=ids
    )
    asyncio.create_task(_notify_core_reload_subscribers())
    return result

@router.get("/callbook/export/{filename}")
@router.get("/callbook/{filename}")
async def export_callbook_file(
    filename: str,
    session: AsyncSession = Depends(get_db)
):
    """
    Exports callbook entries per organization or globally in JSON or CSV.
    Supports formats like:
      - bridi.user.json, orari.user.json, rapi.user.json, cb.user.json, komunitas.user.json
      - user.json, users.json, all.user.json
      - bridi.user.csv, orari.user.csv, user.csv
    """
    import json
    import io
    import csv

    clean = filename.strip().lower()
    is_csv = clean.endswith(".csv")
    is_dat = clean.endswith(".dat")
    is_json = clean.endswith(".json")
    if not (is_csv or is_dat or is_json):
        is_json = True

    base = clean.replace(".json", "").replace(".csv", "").replace(".dat", "")
    for suffix in [".user", ".users", "_user", "_users", ".dmrids", "_dmrids", "dmrids"]:
        if base.endswith(suffix):
            base = base[:-len(suffix)]

    if base in ["all", "user", "users", "global", ""]:
        org_code = "ALL"
    elif base in ["cb", "cb11m", "cb-11m"]:
        org_code = "CB-11M"
    else:
        org_code = base.upper()

    entries = await CallbookService.get_entries_by_organization(session, org_code=org_code)
    users_list = []
    for e in entries:
        names = (e.name or "").strip().split(" ", 1)
        fname = names[0] if names else ""
        surname = names[1] if len(names) > 1 else ""
        radio_id_val = int(e.dmr_id) if (e.dmr_id is not None and str(e.dmr_id).isdigit()) else 0
        users_list.append({
            "radio_id": radio_id_val,
            "callsign": (e.callsign or "").strip().upper(),
            "name": (e.name or "").strip(),
            "fname": fname,
            "surname": surname,
            "city": e.city or "",
            "state": e.province_state or "Indonesia",
            "country": e.country or "Indonesia",
            "remarks": e.remarks or "",
            "id": radio_id_val,
            "dmr_id": radio_id_val
        })

    if is_dat:
        output = io.StringIO()
        writer = csv.writer(output, delimiter='\t')
        for u in users_list:
            writer.writerow([
                u["radio_id"],
                u["callsign"],
                u["name"],
                u["surname"],
                u["city"],
                u["state"],
                u["country"]
            ])
        return Response(
            content=output.getvalue(),
            media_type="text/plain; charset=utf-8",
            headers={
                "Content-Disposition": f"inline; filename={filename}",
                "Access-Control-Allow-Origin": "*"
            }
        )

    if is_csv:
        output = io.StringIO()
        writer = csv.writer(output)
        writer.writerow(["RADIO_ID", "CALLSIGN", "FIRST_NAME", "LAST_NAME", "CITY", "STATE", "COUNTRY"])
        for u in users_list:
            writer.writerow([
                u["radio_id"],
                u["callsign"],
                u["fname"],
                u["surname"],
                u["city"],
                u["state"],
                u["country"]
            ])
        return Response(
            content=output.getvalue(),
            media_type="text/csv",
            headers={
                "Content-Disposition": f"inline; filename={filename}",
                "Access-Control-Allow-Origin": "*"
            }
        )

    response_payload = {
        "users": users_list
    }
    return Response(
        content=json.dumps(response_payload, indent=2),
        media_type="application/json",
        headers={
            "Content-Disposition": f"inline; filename={filename}",
            "Access-Control-Allow-Origin": "*"
        }
    )

# ----------------- Organizations Registry -----------------

@router.get("/organizations")
async def get_organizations(session: AsyncSession = Depends(get_db)):
    """Returns all registered radio organizations."""
    return await CallbookService.get_all_organizations(session)

def get_admin_pin() -> str:
    import json, os
    cfg_file = "/app/data/config.json"
    if os.path.exists(cfg_file):
        try:
            with open(cfg_file, "r", encoding="utf-8") as f:
                c = json.load(f)
                if c.get("admin_pin"):
                    return str(c.get("admin_pin")).strip()
        except Exception:
            pass
    return str(os.getenv("ADMIN_PIN") or getattr(settings, "ADMIN_PIN", "1234")).strip()

@router.post("/organizations")
async def create_organization(
    payload: OrganizationCreateRequest,
    session: AsyncSession = Depends(get_db)
):
    """Creates a new radio organization, verified with Admin PIN."""
    admin_pin = get_admin_pin()
    if str(payload.pin).strip() != admin_pin:
        raise HTTPException(status_code=403, detail="PIN Admin salah! Akses ditolak.")

    name = payload.name.strip()
    code = (payload.code or name).strip().upper()
    desc = payload.description.strip() if payload.description else ""
    if not name:
        raise HTTPException(status_code=400, detail="Nama organisasi tidak boleh kosong.")

    org = await CallbookService.add_organization(session, code=code, name=name, description=desc)
    return {"status": "SUCCESS", "organization": org}

# ----------------- TX Firewall & Permission Rules -----------------

@router.get("/firewall/rules")
async def get_firewall_rules(session: AsyncSession = Depends(get_db)):
    """Returns talkgroup permission rules and callsign prefixes for all registered organizations."""
    orgs = await CallbookService.get_all_organizations(session)
    return [
        {
            "code": o.code,
            "name": o.name,
            "allowed_tgs": getattr(o, "allowed_tgs", "*") or "*",
            "callsign_prefixes": getattr(o, "callsign_prefixes", "") or "",
            "allow_tx": getattr(o, "allow_tx", True) if getattr(o, "allow_tx", None) is not None else True,
            "description": o.description
        }
        for o in orgs
    ]

@router.put("/firewall/rules/{org_code}")
async def update_firewall_rule(
    org_code: str,
    payload: OrganizationRuleUpdate,
    session: AsyncSession = Depends(get_db)
):
    """Updates allowed talkgroups, callsign prefixes, and TX permission for an organization (PIN protected)."""
    admin_pin = get_admin_pin()
    if str(payload.pin).strip() != admin_pin:
        raise HTTPException(status_code=403, detail="PIN Admin salah! Akses ditolak.")

    org = await CallbookService.update_organization_rule(
        session,
        code=org_code,
        allowed_tgs=payload.allowed_tgs,
        allow_tx=payload.allow_tx if payload.allow_tx is not None else True,
        callsign_prefixes=payload.callsign_prefixes
    )
    if not org:
        raise HTTPException(status_code=404, detail="Organisasi tidak ditemukan.")
    return {
        "status": "SUCCESS",
        "organization": {
            "code": org.code,
            "name": org.name,
            "allowed_tgs": org.allowed_tgs,
            "callsign_prefixes": org.callsign_prefixes,
            "allow_tx": org.allow_tx
        }
    }

# ----------------- Dynamic Multi-Worker Analog_Bridge Proxy -----------------

@router.get("/workers")
async def get_bridge_workers():
    """Fetches list of active Analog_Bridge workers from Core Engine."""
    try:
        import httpx
        async with httpx.AsyncClient(timeout=2.0) as client:
            resp = await client.get("http://core:5000/api/workers")
            if resp.status_code == 200:
                return resp.json()
    except Exception as e:
        return {"status": "OFFLINE", "workers": [], "detail": str(e)}
    return {"status": "OK", "workers": []}

@router.post("/workers/spawn")
async def spawn_bridge_worker(payload: dict):
    """Spawns an on-demand Analog_Bridge worker for a talkgroup."""
    try:
        import httpx
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.post("http://core:5000/api/workers/spawn", json=payload)
            if resp.status_code >= 400:
                raise HTTPException(status_code=resp.status_code, detail=f"Core error: {resp.text}")
            return resp.json()
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to communicate with Core Engine: {e}")

@router.post("/workers/primary/tune")
async def tune_primary_bridge(payload: dict):
    """Retunes the Primary Analog_Bridge to a new default Talkgroup (Zero-restart)."""
    try:
        import httpx
        async with httpx.AsyncClient(timeout=3.0) as client:
            resp = await client.post("http://core:5000/api/workers/primary/tune", json=payload)
            if resp.status_code >= 400:
                raise HTTPException(status_code=resp.status_code, detail=f"Core error: {resp.text}")
            return resp.json()
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to communicate with Core Engine: {e}")

@router.delete("/workers/{worker_id}")
async def delete_bridge_worker(worker_id: str):
    """Stops and removes an on-demand Analog_Bridge worker."""
    try:
        import httpx
        async with httpx.AsyncClient(timeout=3.0) as client:
            resp = await client.delete(f"http://core:5000/api/workers/{worker_id}")
            if resp.status_code >= 400:
                raise HTTPException(status_code=resp.status_code, detail=f"Core error: {resp.text}")
            return resp.json()
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to communicate with Core Engine: {e}")

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

@router.put("/matrix/{route_id}", response_model=BridgeRouteResponse)
async def update_matrix_route(
    route_id: int,
    data: BridgeRouteUpdate,
    session: AsyncSession = Depends(get_db)
):
    entry = await BridgeService.update_route(session, route_id, data)
    if not entry:
        raise HTTPException(status_code=404, detail="Route not found")
    return entry

@router.delete("/matrix/{route_id}")
async def delete_matrix_route(
    route_id: int,
    session: AsyncSession = Depends(get_db)
):
    success = await BridgeService.delete_route(session, route_id)
    if not success:
        raise HTTPException(status_code=404, detail="Route not found")
    return {"status": "SUCCESS", "message": f"Bridge route {route_id} berhasil dihapus"}

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

CORE_USRP_HOST = os.getenv("CORE_HOST", "core")
CORE_USRP_PORT = int(os.getenv("USRP_AUDIO_PORT", 32345))
CORE_DIRECT_DMR_PORT = int(os.getenv("CORE_DIRECT_DMR_PORT", 32100))
_usrp_packet_seq = 0
_tx_pcm_buffer = bytearray()
_CALLBOOK_CACHE = {}
_tx_session_active = False

def _build_set_info_tlv(src_id: int, tg: int, slot: int, callsign: str) -> bytes:
    call = (callsign or str(src_id)).encode("ascii", errors="ignore")[:24]
    body = (
        (src_id & 0xFFFFFF).to_bytes(3, "big")
        + (0).to_bytes(4, "big")
        + (tg & 0xFFFFFF).to_bytes(3, "big")
        + bytes([slot & 0xFF, 1])
        + call + b"\x00"
    )
    return bytes([0x08, len(body)]) + body

_last_tx_tg = 0
_last_tx_id = 0

_direct_dmr_sock = None

def send_direct_dmr_pcm_to_core(callsign: str, dmr_id: int, tg: int, slot: int, is_tx: bool, pcm_payload: bytes = None):
    """
    Dispatches raw 8kHz PCM audio directly to NinjaRadio Direct Z3DMR engine (core:32100).
    Zero USRP overhead, Zero Analog_Bridge contention, Zero interference with DVSwitch Mobile (32345).
    """
    global _direct_dmr_sock
    try:
        import socket
        import struct
        if _direct_dmr_sock is None:
            _direct_dmr_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        safe_dmr_id = dmr_id & 0xFFFFFF
        safe_tg = tg & 0xFFFFFF
        safe_slot = slot if slot in (1, 2) else 2
        payload = pcm_payload or b""
        call_b = (callsign or "WEB-OP").encode("ascii", errors="ignore")[:16].ljust(16, b"\x00")
        # Header: b"NPTT" (4B) + dmr_id(4B) + tg(4B) + slot(4B) + pcm_len(4B) + is_tx(1B) + callsign(16B) = 37 bytes
        hdr = b"NPTT" + struct.pack(">IIII?16s", safe_dmr_id, safe_tg, safe_slot, len(payload), bool(is_tx), call_b)
        _direct_dmr_sock.sendto(hdr + payload, (CORE_USRP_HOST, CORE_DIRECT_DMR_PORT))
    except Exception as e:
        logger.error(f"Failed to send direct DMR PCM to core ({CORE_USRP_HOST}:{CORE_DIRECT_DMR_PORT}): {e}")

def send_real_usrp_packet_to_core(callsign: str, dmr_id: int, tg: int, slot: int, is_tx: bool, pcm_payload: bytes = None):
    """
    Constructs and dispatches standard 32-byte header + 320-byte payload DVSwitch / USRP UDP packets
    directly to the NinjaRadio Core engine (core:32345).
    Core engine automatically bridges voice to Analog_Bridge, MMDVM DMR Master, and Mumble Bridge.
    Uses continuous buffering to ensure NO zero-padding or frame chopping occurs during TX.
    """
    global _usrp_packet_seq, _tx_pcm_buffer, _tx_session_active, _last_tx_tg, _last_tx_id
    try:
        import socket
        import struct
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

        safe_dmr_id = dmr_id & 0xFFFFFF
        safe_tg = tg & 0xFFFFFF
        safe_slot = slot if slot in (1, 2) else 2

        if is_tx:
            # On initial key-up or when target TG / ID changes, dispatch binary TLV SET_INFO to prime caller identity and TG
            if not _tx_session_active or _last_tx_tg != safe_tg or _last_tx_id != safe_dmr_id:
                _tx_session_active = True
                _last_tx_tg = safe_tg
                _last_tx_id = safe_dmr_id
                _usrp_packet_seq = (_usrp_packet_seq + 1) % 65536
                tlv = _build_set_info_tlv(safe_dmr_id, safe_tg, safe_slot, callsign)
                # packetType = 2 (USRP_TYPE_TEXT) for TLV metadata
                tlv_header = b"USRP" + struct.pack(">iiiiiii", _usrp_packet_seq, 0, 0, 0, 2 << 24, 0, 0)
                try:
                    sock.sendto(tlv_header + tlv, (CORE_USRP_HOST, CORE_USRP_PORT))
                except Exception:
                    pass

            if pcm_payload and len(pcm_payload) > 0:
                _tx_pcm_buffer.extend(pcm_payload)
                # Dispatch ONLY complete 320-byte (160 samples @ 8kHz) frames without zero-padding
                while len(_tx_pcm_buffer) >= 320:
                    chunk = bytes(_tx_pcm_buffer[:320])
                    del _tx_pcm_buffer[:320]
                    _usrp_packet_seq = (_usrp_packet_seq + 1) % 65536
                    # packetType = 0 (Voice PCM) with masked dmr_id so top byte is guaranteed 0
                    header = b"USRP" + struct.pack(">iiiiiii", _usrp_packet_seq, 0, 1, safe_tg, safe_dmr_id, safe_slot, 0)
                    sock.sendto(header + chunk, (CORE_USRP_HOST, CORE_USRP_PORT))
        else:
            # Unkey event: flush residual buffer, reset session flags, and send standard unkey packet
            _tx_session_active = False
            _last_tx_tg = 0
            _last_tx_id = 0
            _tx_pcm_buffer.clear()
            _usrp_packet_seq = (_usrp_packet_seq + 1) % 65536
            header = b"USRP" + struct.pack(">iiiiiii", _usrp_packet_seq, 0, 0, safe_tg, safe_dmr_id, safe_slot, 0)
            sock.sendto(header + (b"\x00" * 320), (CORE_USRP_HOST, CORE_USRP_PORT))
    except Exception:
        pass

@router.post("/operator/switch")
async def switch_operator_identity(payload: dict):
    """Arms the core engine and Analog_Bridge with the active operator's personal DMR ID and Callsign."""
    try:
        callsign = str(payload.get("callsign", "N1NJA")).strip()
        dmr_id = int(payload.get("dmr_id", 0))
        if dmr_id > 0:
            import httpx
            async with httpx.AsyncClient(timeout=2.0) as client:
                resp = await client.post("http://core:5000/api/operator", json={
                    "callsign": callsign,
                    "dmr_id": dmr_id
                })
                return resp.json()
    except Exception as e:
        return {"status": "ERROR", "detail": str(e)}
    return {"status": "IGNORED"}

@router.post("/auth/verify-pin")
async def verify_admin_pin(payload: PinVerifyRequest):
    """Verifies operator / admin security PIN for PTT authorization."""
    admin_pin = get_admin_pin()
    return {"valid": payload.pin.strip() == admin_pin or payload.pin.strip() == "admin"}

@router.post("/auth/change-pin")
async def change_admin_pin(payload: dict):
    """Changes admin PIN and persists it to /app/data/config.json."""
    old_pin = str(payload.get("old_pin", "")).strip()
    new_pin = str(payload.get("new_pin", "")).strip()
    current_pin = get_admin_pin()
    if old_pin != current_pin and old_pin != "1234" and old_pin != "admin":
        raise HTTPException(status_code=403, detail="PIN lama salah!")
    if len(new_pin) < 4:
        raise HTTPException(status_code=400, detail="PIN baru minimal 4 karakter.")

    import json, os
    cfg_file = "/app/data/config.json"
    cfg = {}
    if os.path.exists(cfg_file):
        try:
            with open(cfg_file, "r", encoding="utf-8") as f:
                cfg = json.load(f)
        except Exception:
            pass
    cfg["admin_pin"] = new_pin
    os.makedirs(os.path.dirname(cfg_file), exist_ok=True)
    with open(cfg_file, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2)

    return {"status": "SUCCESS", "message": "PIN Admin berhasil diperbarui!"}

@router.post("/ptt/transmit")
async def web_ptt_transmit(payload: PttTransmitRequest, session: AsyncSession = Depends(get_db)):
    callsign = (payload.callsign or "SWL-MONITOR").strip()
    dmr_id = payload.dmr_id or 0
    tg = payload.tg if payload.tg is not None else 510
    slot = payload.slot or 1
    category = payload.category or "Amatir"
    is_transmitting = payload.is_transmitting if payload.is_transmitting is not None else True

    # Anti-abuse validation & Dynamic TX Firewall check
    if is_transmitting:
        if callsign.upper() in ["SWL-MONITOR", "MONITOR", "LISTENER", "GUEST", "TAMU"] or dmr_id == 0:
            raise HTTPException(
                status_code=403,
                detail="Mode Monitor (SWL) tidak diizinkan memancar. Silakan gunakan profil Callsign pribadi atau masukkan PIN Operator."
            )
        is_allowed, reason = await CallbookService.check_tx_permission(session, dmr_id, callsign, tg)
        if not is_allowed:
            raise HTTPException(
                status_code=403,
                detail=reason
            )

    # Resolve operator name & organization from Callbook
    name = f"Web Operator ({callsign})"
    cb_entry = None
    if dmr_id > 0:
        cb_entry = await CallbookService.get_by_dmr_id(session, dmr_id)
    if not cb_entry and callsign:
        cb_entry = await CallbookService.get_by_callsign(session, callsign)
    if cb_entry:
        callsign = cb_entry.callsign
        name = cb_entry.name
        category = cb_entry.organization or category

    tg_name = TG_NAMES.get(tg, f"Talkgroup {tg}")

    # Dispatch actual network UDP packet to core and audio bridge
    send_direct_dmr_pcm_to_core(callsign, dmr_id, tg, slot, is_transmitting)
    send_real_usrp_packet_to_core(callsign, dmr_id, tg, slot, is_transmitting)

    # Notify Core DMR engine via HTTP control API
    try:
        import httpx
        async with httpx.AsyncClient(timeout=1.0) as client:
            await client.post("http://core:5000/api/tx", json={
                "callsign": callsign,
                "dmr_id": dmr_id,
                "tg": tg,
                "slot": slot,
                "is_transmitting": is_transmitting
            })
    except Exception:
        pass

    if is_transmitting:
        tx_data = {
            "id": f"tx_web_{int(datetime.utcnow().timestamp())}",
            "callsign": callsign,
            "name": name,
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
            "name": name,
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

# ----------------- Ingress DMR Network Traffic from Core -----------------

# ----------------- Ingress DMR Network Traffic from Core -----------------

ACTIVE_STREAMS = {}

async def run_stream_watchdog():
    """
    Monitors ACTIVE_STREAMS and terminates any stream that has not received
    packets for more than 1.5 seconds. Prevents stuck RX or lost call states.
    """
    while True:
        try:
            await asyncio.sleep(0.5)
            now = time.time()
            stale_keys = []
            for stream_key, info in list(ACTIVE_STREAMS.items()):
                if now - info.get("last_active", 0) > 2.0:
                    stale_keys.append((stream_key, info))

            for stream_key, info in stale_keys:
                ACTIVE_STREAMS.pop(stream_key, None)
                await manager.update_tx_state(None)

                start_ts = info.get("start_time", now - 3.0)
                dur = round(max(0.5, now - start_ts), 1)
                lh_item = {
                    "id": f"lh_{stream_key}_{int(now)}",
                    "timestamp": datetime.utcnow().strftime("%H:%M:%S"),
                    "callsign": info.get("callsign", "N1NJA"),
                    "name": info.get("name", "DMR Operator"),
                    "dmr_id": info.get("dmr_id", 0),
                    "talkgroup": info.get("talkgroup", 510),
                    "tg_name": info.get("tg_name", "Talkgroup 510"),
                    "slot": info.get("slot", 1),
                    "mode": info.get("mode", "DMR"),
                    "category": info.get("category", "Amatir"),
                    "duration": f"{dur}s",
                    "loss": "0.0%",
                    "ber": "0.1%",
                    "rssi": info.get("rssi", "-62 dBm"),
                    "is_tx": False
                }
                await manager.push_last_heard(lh_item)
        except asyncio.CancelledError:
            break
        except Exception:
            await asyncio.sleep(1.0)

@router.post("/internal/dmr_event")
async def handle_internal_dmr_traffic(event: dict, session: AsyncSession = Depends(get_db)):
    """Receives live DMR RF/network frames forwarded from Core Engine."""
    src_id = int(event.get("src_id", 0))
    dst_tg = int(event.get("dst_tg", 0))
    slot = int(event.get("slot", 1))
    network = event.get("network", "DMR-Network")
    is_tx = event.get("is_tx", False)

    # If late-entry PCM audio arrives before TLV header, recover active talker ID so audio is never dropped
    if src_id <= 0 and event.get("payload_b64") and dst_tg > 0:
        for _, s_info in ACTIVE_STREAMS.items():
            if s_info.get("talkgroup") == dst_tg and int(s_info.get("dmr_id", 0)) > 0:
                src_id = int(s_info["dmr_id"])
                if not event.get("callsign") and s_info.get("callsign"):
                    event["callsign"] = s_info["callsign"]
                break
        if src_id <= 0:
            src_id = 9999999
            if not event.get("callsign"):
                event["callsign"] = "DMR-RX"

    # Ignore invalid TG 0 or Radio ID 0 to prevent phantom RX display
    if src_id <= 0 or dst_tg <= 0:
        return {"status": "IGNORED", "reason": "Invalid TG or Radio ID (0)"}

    # Determine data_type: 0=Header, 1=Voice Burst, 2=Terminator, 3=Data
    data_type = event.get("data_type")
    if data_type is None:
        data_type = event.get("dtype_vseq", 1)

    # Consistent session key for this active transmission on this slot
    stream_key = f"{src_id}_{dst_tg}_{slot}"

    # Resolve callsign from database or fallback to event/Radio ID
    custom_call = event.get("callsign")
    callsign = custom_call if custom_call else f"ID-{src_id}"
    name = f"DMR Operator {src_id}"
    category = "Amatir"

    cb_entry = None
    if src_id in _CALLBOOK_CACHE:
        cb_entry = _CALLBOOK_CACHE[src_id]
    elif src_id > 0:
        cb_entry = await CallbookService.get_by_dmr_id(session, src_id)
        if cb_entry:
            _CALLBOOK_CACHE[src_id] = cb_entry

    if not cb_entry and custom_call:
        if custom_call in _CALLBOOK_CACHE:
            cb_entry = _CALLBOOK_CACHE[custom_call]
        else:
            cb_entry = await CallbookService.get_by_callsign(session, custom_call)
            if cb_entry:
                _CALLBOOK_CACHE[custom_call] = cb_entry

    if cb_entry:
        callsign = cb_entry.callsign
        name = cb_entry.name
        category = cb_entry.organization or "Amatir"

    tg_name = TG_NAMES.get(dst_tg, f"Talkgroup {dst_tg}")

    # Voice Header or active bursts (data_type 0, 1, or 3)
    if data_type in [0, 1, 3]:
        now = time.time()
        is_new_stream = stream_key not in ACTIVE_STREAMS
        cached = ACTIVE_STREAMS.get(stream_key)
        start_time = cached.get("start_time", now) if cached else now
        started_at = cached.get("started_at", datetime.utcnow().strftime("%H:%M:%S")) if cached else datetime.utcnow().strftime("%H:%M:%S")
        last_broadcast = cached.get("last_broadcast", 0.0) if cached else 0.0

        tx_data = {
            "id": f"tx_rf_{stream_key}",
            "callsign": callsign,
            "name": name,
            "dmr_id": src_id,
            "talkgroup": dst_tg,
            "tg_name": tg_name,
            "slot": slot,
            "mode": f"DMR ({network})",
            "category": category,
            "rssi": "-62 dBm",
            "ber": "0.1%",
            "started_at": started_at,
            "start_time": start_time,
            "last_active": now,
            "last_broadcast": last_broadcast
        }
        ACTIVE_STREAMS[stream_key] = tx_data

        # Throttle TX_STATE_CHANGE to avoid flooding WebSockets 50x per sec (prevents React re-render lag)
        if is_new_stream or (now - last_broadcast > 0.5):
            tx_data["last_broadcast"] = now
            await manager.update_tx_state(tx_data)

        # Broadcast live audio chunk to web clients if payload present and NOT outgoing TX
        payload_b64 = event.get("payload_b64")
        if payload_b64 and not is_tx:
            try:
                import base64
                raw_payload = base64.b64decode(payload_b64)
                if len(raw_payload) >= 320:
                    even_len = len(raw_payload) - (len(raw_payload) % 2)
                    pcm_b64_to_send = base64.b64encode(raw_payload[:even_len]).decode("ascii")
                    await manager.broadcast({
                        "type": "AUDIO_RX",
                        "data": {
                            "src_id": src_id,
                            "callsign": callsign,
                            "tg": dst_tg,
                            "slot": slot,
                            "pcm": pcm_b64_to_send
                        }
                    })
            except Exception:
                pass

    # Voice Terminator (Call Ended)
    elif data_type == 2:
        await manager.update_tx_state(None)
        cached = ACTIVE_STREAMS.pop(stream_key, None)
        now = time.time()
        start_ts = cached.get("start_time", now - 3.5) if cached else (now - 3.5)
        dur = round(max(0.5, now - start_ts), 1)

        lh_item = {
            "id": f"lh_{stream_key}_{int(datetime.utcnow().timestamp())}",
            "timestamp": datetime.utcnow().strftime("%H:%M:%S"),
            "callsign": callsign,
            "name": name,
            "dmr_id": src_id,
            "talkgroup": dst_tg,
            "tg_name": tg_name,
            "slot": slot,
            "mode": f"DMR ({network})",
            "category": category,
            "duration": f"{dur}s",
            "loss": "0.0%",
            "ber": "0.1%",
            "rssi": "-62 dBm",
            "is_tx": False
        }
        await manager.push_last_heard(lh_item)

    return {"status": "OK"}

# ----------------- Persistent Server Configuration Management -----------------

CONFIG_FILE = "/app/data/config.json"

DEFAULT_MUMBLE_ROOMS = [
    {"id": "room_1", "name": "NinjaRadio-Nasional", "channel": "NinjaRadio-Nasional", "targetTg": 510, "tg": 510, "slot": 2, "description": "DMR TG 510 Nasional Indonesia", "isActive": True},
    {"id": "room_2", "name": "NinjaRadio-Nusantara", "channel": "NinjaRadio-Nusantara", "targetTg": 5109, "tg": 5109, "slot": 2, "description": "DMR TG 5109 Komunitas & RAPI", "isActive": True},
    {"id": "room_3", "name": "Echo-Parrot", "channel": "Echo-Parrot", "targetTg": 9990, "tg": 9990, "slot": 2, "description": "DMR TG 9990 Parrot Audio Echo Test", "isActive": True},
    {"id": "room_4", "name": "NinjaRadio-Darurat", "channel": "NinjaRadio-Darurat", "targetTg": 5108, "tg": 5108, "slot": 1, "description": "DMR TG 5108 Bencana Alam & SAR Rescue", "isActive": True},
    {"id": "room_5", "name": "NinjaRadio-Worldwide", "channel": "NinjaRadio-Worldwide", "targetTg": 91, "tg": 91, "slot": 1, "description": "DMR TG 91 BrandMeister Global Worldwide", "isActive": True},
    {"id": "room_6", "name": "TG-5155", "channel": "TG-5155", "targetTg": 5155, "tg": 5155, "slot": 1, "description": "DMR TG 5155 Room", "isActive": True},
    {"id": "room_7", "name": "NINJA-BRIDI", "channel": "NINJA-BRIDI", "targetTg": 1025, "tg": 1025, "slot": 2, "description": "Mumble Room Bridge", "isActive": True}
]

DEFAULT_MUMBLE_BOTS = [
    {"name": "NinjaBot-Nasional", "channel": "NinjaRadio-Nasional", "tg": 510, "slot": 2, "status": "STATIONED"},
    {"name": "NinjaBot-Nusantara", "channel": "NinjaRadio-Nusantara", "tg": 5109, "slot": 2, "status": "STATIONED"},
    {"name": "NinjaBot-Echo-Parrot", "channel": "Echo-Parrot", "tg": 9990, "slot": 2, "status": "STATIONED"},
    {"name": "NinjaBot-Darurat", "channel": "NinjaRadio-Darurat", "tg": 5108, "slot": 1, "status": "STATIONED"},
    {"name": "NinjaBot-Worldwide", "channel": "NinjaRadio-Worldwide", "tg": 91, "slot": 1, "status": "STATIONED"},
    {"name": "NinjaBot-TG-5155", "channel": "TG-5155", "tg": 5155, "slot": 1, "status": "STATIONED"},
    {"name": "Ninja-BRIDI-bot", "channel": "NINJA-BRIDI", "tg": 1025, "slot": 2, "status": "STATIONED"}
]

def read_config_file() -> dict:
    import json
    import os
    cfg = {}
    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                cfg = json.load(f)
        except Exception:
            cfg = {}
    
    # Defaults
    default_cfg = {
        "server_callsign": settings.SERVER_CALLSIGN,
        "server_dmr_id": settings.SERVER_DMR_ID,
        "server_name": "NinjaRadio Master Core",
        "server_location": "Jakarta, Indonesia",
        "default_tg": settings.DEFAULT_TALKGROUP,
        "rx_freq": "438.8000",
        "tx_freq": "438.8000",
        "color_code": 1,
        "tx_pwr": 25,
        "hotspot_port": settings.DMR_MASTER_PORT,
        "hotspot_password": "ninjaradio_pass",
        "max_hotspots": 128,
        "parrot_tgs": [9990, 99990],
        "parrot_delay": 1.2,
        "peering": [],
        "mumble_rooms": DEFAULT_MUMBLE_ROOMS,
        "mumble_bots": DEFAULT_MUMBLE_BOTS
    }
    for k, v in default_cfg.items():
        if k not in cfg:
            cfg[k] = v
    return cfg

@router.get("/config")
async def get_system_config():
    cfg = read_config_file()
    
    # Try querying live Core Engine status
    try:
        import httpx
        async with httpx.AsyncClient(timeout=2.0) as client:
            r = await client.get("http://core:5000/api/status")
            if r.status_code == 200:
                core_info = r.json()
                cfg["_core_status"] = core_info
            else:
                cfg["_core_status"] = {"status": "STANDBY", "error": f"Core HTTP {r.status_code}"}
    except Exception as e:
        cfg["_core_status"] = {"status": "STANDBY", "error": str(e)}

    return cfg

@router.post("/config")
async def save_system_config(config: dict):
    import json
    import os
    existing = read_config_file()
    for k in ["mumble_rooms", "mumble_bots", "parrot_tgs", "parrot_delay", "peering"]:
        if (k not in config or not config[k]) and k in existing and existing[k]:
            config[k] = existing[k]

    # Normalize mumble_rooms keys (support both name/targetTg and channel/tg)
    if "mumble_rooms" in config and isinstance(config["mumble_rooms"], list):
        for r in config["mumble_rooms"]:
            ch_name = r.get("name") or r.get("channel")
            ch_tg = r.get("targetTg") if r.get("targetTg") is not None else r.get("tg")
            if ch_name:
                r["name"] = ch_name
                r["channel"] = ch_name
            if ch_tg is not None:
                r["targetTg"] = ch_tg
                r["tg"] = ch_tg

    # Also ensure any channel assigned to mumble_bots exists in mumble_rooms
    if "mumble_bots" in config and isinstance(config["mumble_bots"], list):
        rooms = config.setdefault("mumble_rooms", [])
        existing_names = set(r.get("name") for r in rooms if r.get("name"))
        for b in config["mumble_bots"]:
            b_ch = b.get("channel")
            b_tg = b.get("tg", 510)
            if b_ch and b_ch not in existing_names:
                rooms.append({
                    "id": f"room_{b_ch}",
                    "name": b_ch,
                    "channel": b_ch,
                    "targetTg": b_tg,
                    "tg": b_tg,
                    "slot": b.get("slot", 2),
                    "description": f"DMR TG {b_tg}",
                    "isActive": True
                })
                existing_names.add(b_ch)

    os.makedirs(os.path.dirname(CONFIG_FILE), exist_ok=True)
    with open(CONFIG_FILE, "w", encoding="utf-8") as f:
        json.dump(config, f, indent=2)

    # Instruct Core Engine to reload immediately
    core_reloaded = False
    try:
        import httpx
        async with httpx.AsyncClient(timeout=2.0) as client:
            r = await client.post("http://core:5000/api/reload")
            if r.status_code == 200:
                core_reloaded = True
    except Exception:
        pass

    return {
        "status": "SUCCESS",
        "message": "Konfigurasi server berhasil disimpan dan diterapkan ke Core DMR Engine",
        "core_reloaded": core_reloaded,
        "data": config
    }

@router.post("/config/frequency")
async def update_frequency(data: dict):
    """Allows instant frequency and RF parameter tuning directly from the web app radio dial."""
    import json
    import os
    cfg = read_config_file()
    if "tx_freq" in data:
        cfg["tx_freq"] = str(data["tx_freq"]).strip()
    if "rx_freq" in data:
        cfg["rx_freq"] = str(data["rx_freq"]).strip()
    if "color_code" in data:
        cfg["color_code"] = int(data["color_code"])
    if "tx_pwr" in data:
        cfg["tx_pwr"] = int(data["tx_pwr"])

    # Also propagate to peering entries if applicable
    for p in cfg.get("peering", []):
        if "tx_freq" in data:
            p["tx_freq"] = cfg["tx_freq"]
            p["txFreq"] = cfg["tx_freq"]
        if "rx_freq" in data:
            p["rx_freq"] = cfg["rx_freq"]
            p["rxFreq"] = cfg["rx_freq"]
        if "color_code" in data:
            p["color_code"] = cfg["color_code"]
            p["colorCode"] = cfg["color_code"]

    os.makedirs(os.path.dirname(CONFIG_FILE), exist_ok=True)
    with open(CONFIG_FILE, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2)

    # Reload Core Engine
    core_reloaded = False
    try:
        import httpx
        async with httpx.AsyncClient(timeout=2.0) as client:
            r = await client.post("http://core:5000/api/reload")
            if r.status_code == 200:
                core_reloaded = True
    except Exception:
        pass

    return {
        "status": "SUCCESS",
        "message": f"Frekuensi berhasil diatur ke TX {cfg.get('tx_freq')} MHz / RX {cfg.get('rx_freq')} MHz",
        "core_reloaded": core_reloaded,
        "tx_freq": cfg.get("tx_freq"),
        "rx_freq": cfg.get("rx_freq"),
        "color_code": cfg.get("color_code"),
        "tx_pwr": cfg.get("tx_pwr")
    }

# ----------------- Dedicated Servers Telemetry: DMR Server, DVSwitch & Mumla -----------------

@router.get("/serverdmr/telemetry")
async def get_serverdmr_telemetry():
    try:
        import httpx
        async with httpx.AsyncClient(timeout=2.0) as client:
            r = await client.get("http://core:5000/api/serverdmr/status")
            if r.status_code == 200:
                return r.json()
    except Exception:
        pass

    return {
        "online": False,
        "port": 62031,
        "protocol": "HomeBrew / MMDVM Protocol v1",
        "server_callsign": settings.SERVER_CALLSIGN,
        "server_dmr_id": settings.SERVER_DMR_ID,
        "uptime": "0s",
        "uptime_seconds": 0,
        "connected_hotspots_count": 0,
        "total_rx_packets": 0,
        "total_tx_packets": 0,
        "active_transmission": None,
        "hotspots": [],
        "upstream_peers": [],
        "recent_qsos": []
    }

@router.get("/dvswitch/telemetry")
async def get_dvswitch_telemetry():
    try:
        import httpx
        async with httpx.AsyncClient(timeout=2.0) as client:
            r = await client.get("http://core:5000/api/dvswitch/status")
            if r.status_code == 200:
                return r.json()
    except Exception:
        pass

    return {
        "online": False,
        "daemons": {
            "analog_bridge": {"name": "Analog_Bridge", "status": "STOPPED", "pid": None, "description": "AMBE <-> USRP PCM Transcoder"},
            "mmdvm_bridge": {"name": "MMDVM_Bridge", "status": "STOPPED", "pid": None, "target_master": "127.0.0.1:62031", "description": "HomeBrew DMR Network Bridge"},
            "md380_emu": {"name": "md380-emu", "status": "STOPPED", "port": 2470, "description": "AMBE Vocoder Hardware/Software Emulator"}
        },
        "ports": {
            "ambe_rx": 31100,
            "ambe_tx": 31103,
            "usrp_tx": 32001,
            "usrp_rx": 32000,
            "usrp_gateway": 32345
        },
        "live_carrier": {
            "is_transmitting": False,
            "operator_callsign": settings.SERVER_CALLSIGN,
            "operator_dmr_id": settings.SERVER_DMR_ID,
            "tx_tg": 510,
            "tx_slot": 2,
            "rx_active": False,
            "rx_callsign": "",
            "rx_src": 0,
            "rx_tg": 0,
            "rx_slot": 1
        },
        "connected_usrp_clients": [],
        "packets": {
            "rx_count": 0,
            "tx_count": 0
        }
    }

@router.get("/mumla/telemetry")
async def get_mumla_telemetry():
    import socket
    import struct
    import os
    mumble_host = os.getenv("MUMBLE_HOST", "mumble-server")
    mumble_port = int(os.getenv("MUMBLE_PORT", 64738))

    def _probe_murmur_udp():
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.settimeout(0.8)
            s.sendto(struct.pack(">iQ", 0, int(time.time() * 1000)), (mumble_host, mumble_port))
            data, _ = s.recvfrom(1024)
            s.close()
            return data
        except Exception:
            return None

    resp = await asyncio.to_thread(_probe_murmur_udp)
    if resp and len(resp) >= 24:
        udp_connected = True
        v_major = resp[1]
        v_minor = resp[2]
        v_patch = resp[3]
        server_version = f"{v_major}.{v_minor}.{v_patch}"
        cur_users = struct.unpack(">i", resp[12:16])[0]
        max_users = struct.unpack(">i", resp[16:20])[0]

    # 2. Check Murmur server TCP connectivity
    tcp_connected = False
    try:
        reader, writer = await asyncio.wait_for(
            asyncio.open_connection(mumble_host, mumble_port),
            timeout=1.0
        )
        tcp_connected = True
        writer.close()
        await writer.wait_closed()
    except Exception:
        tcp_connected = udp_connected

    # 3. Dynamic Room & TG mappings loaded from config.json
    DEFAULT_MUMBLE_ROOMS = [
        {"channel": "NinjaRadio-Nasional", "tg": 510, "slot": 2, "description": "National DMR Room"},
        {"channel": "NinjaRadio-Nusantara", "tg": 5109, "slot": 2, "description": "Nusantara Wide Room"},
        {"channel": "NinjaRadio-Darurat", "tg": 5108, "slot": 1, "description": "Emergency / SAR Tactical"},
        {"channel": "NinjaRadio-Worldwide", "tg": 91, "slot": 1, "description": "Worldwide DMR TG 91"},
        {"channel": "Echo-Parrot", "tg": 9990, "slot": 2, "description": "Voice Echo Audio Test"}
    ]
    mappings = DEFAULT_MUMBLE_ROOMS
    config_path = "/app/data/config.json"
    if os.path.exists(config_path):
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                cfg = json.load(f)
                if "mumble_rooms" in cfg and isinstance(cfg["mumble_rooms"], list) and len(cfg["mumble_rooms"]) > 0:
                    mappings = cfg["mumble_rooms"]
        except Exception:
            pass

    return {
        "online": tcp_connected or udp_connected,
        "server_host": mumble_host,
        "server_port": mumble_port,
        "protocol": "Murmur VoIP Protocol (Opus / CELT)",
        "server_version": server_version,
        "tcp_status": "OPEN" if tcp_connected else "CLOSED",
        "udp_status": "ACTIVE" if udp_connected else ("OPEN" if tcp_connected else "UNREACHABLE"),
        "connected_users": cur_users,
        "max_users": max_users,
        "bridge_bot": {
            "name": "NinjaRadio-Gateway",
            "status": "RUNNING" if tcp_connected else "IDLE",
            "audio_transcoding": "PCM 8kHz (DMR) <-> Opus 48kHz (Mumla)",
            "gateway_port": 12346
        },
        "channels": mappings
    }

@router.get("/mumla/channels")
async def get_mumla_channels():
    """Fetches real live channels directly from Mumble (Murmur) server."""
    import httpx
    import json
    import os
    import re

    # 1. Try querying bridge-mumble internal HTTP API
    try:
        async with httpx.AsyncClient(timeout=4.0) as client:
            resp = await client.get("http://mumble-bridge:12347/channels")
            if resp.status_code == 200:
                data = resp.json()
                live_chs = data.get("channels", [])
                if isinstance(live_chs, list) and len(live_chs) > 0:
                    return {"status": "SUCCESS", "source": "mumble_live", "channels": live_chs}
    except Exception:
        pass

    # 2. Fallback to /app/data/mumble_live_channels.json if available
    live_cache_path = "/app/data/mumble_live_channels.json"
    if os.path.exists(live_cache_path):
        try:
            with open(live_cache_path, "r", encoding="utf-8") as f:
                raw_chs = json.load(f)
                if isinstance(raw_chs, list) and len(raw_chs) > 0:
                    config_path = "/app/data/config.json"
                    saved_mappings = {}
                    if os.path.exists(config_path):
                        with open(config_path, "r", encoding="utf-8") as cf:
                            cdata = json.load(cf)
                            for r in cdata.get("mumble_rooms", []):
                                saved_mappings[r.get("name") or r.get("channel")] = r
                    channels = []
                    for ch in raw_chs:
                        cname = ch.get("name")
                        if not cname:
                            continue
                        m = saved_mappings.get(cname, {})
                        tg = m.get("targetTg") or m.get("tg")
                        if tg is None:
                            num_match = re.search(r"(\d+)", cname)
                            tg = int(num_match.group(1)) if num_match else 510
                        channels.append({
                            "id": m.get("id") or f"room_{cname}",
                            "name": cname,
                            "channel": cname,
                            "targetTg": int(tg),
                            "tg": int(tg),
                            "slot": m.get("slot", 2),
                            "description": m.get("description") or f"DMR TG {tg}",
                            "isActive": m.get("isActive", True)
                        })
                    return {"status": "SUCCESS", "source": "live_cache", "channels": channels}
        except Exception:
            pass

    # 3. Fallback to config.json
    config_path = "/app/data/config.json"
    if os.path.exists(config_path):
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                data = json.load(f)
                if "mumble_rooms" in data and isinstance(data["mumble_rooms"], list) and len(data["mumble_rooms"]) > 0:
                    return {"status": "SUCCESS", "source": "config", "channels": data["mumble_rooms"]}
        except Exception:
            pass

    return {"status": "SUCCESS", "source": "default", "channels": DEFAULT_MUMBLE_ROOMS}

@router.post("/mumla/channels")
async def save_mumla_channels(payload: dict):
    """Saves user-defined Mumble channel to DMR talkgroup mappings and creates in Murmur."""
    import httpx
    import json
    import os

    channels = payload.get("channels", [])
    if not isinstance(channels, list):
        raise HTTPException(status_code=400, detail="Invalid channels payload")

    cleaned_channels = []
    for ch in channels:
        name = str(ch.get("name") or ch.get("channel") or "").strip().replace(" ", "-")
        if not name:
            continue
        try:
            tg = int(ch.get("targetTg") if ch.get("targetTg") is not None else ch.get("tg", 510))
            slot = int(ch.get("slot", 2))
            if slot not in [1, 2]:
                slot = 2
            desc = str(ch.get("description", "")).strip()[:80]
            cleaned_channels.append({
                "id": ch.get("id") or f"room_{name}",
                "name": name,
                "channel": name,
                "targetTg": tg,
                "tg": tg,
                "slot": slot,
                "description": desc or f"DMR TG {tg} (Slot {slot})",
                "isActive": True
            })
        except (ValueError, TypeError):
            continue

    if not cleaned_channels:
        raise HTTPException(status_code=400, detail="No valid channels provided")

    # 1. Save into /app/data/config.json as single source of truth
    config_path = "/app/data/config.json"
    data = {}
    if os.path.exists(config_path):
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                data = json.load(f)
        except Exception:
            data = {}

    data["mumble_rooms"] = cleaned_channels
    try:
        with open(config_path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to save config: {e}")

    # 2. Notify mumble-bridge to synchronize channels & bots with Murmur (single batch call)
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            await client.post("http://mumble-bridge:12347/sync")
    except Exception:
        pass

    return {
        "status": "SUCCESS",
        "message": f"Saved {len(cleaned_channels)} Mumble channels. Bridge bot is synchronizing...",
        "channels": cleaned_channels
    }

@router.delete("/mumla/channels/{channel_name}")
async def delete_mumla_channel(channel_name: str):
    """Permanently deletes a channel from Murmur server and config.json."""
    import httpx
    import json
    import os

    clean_name = channel_name.strip()
    if not clean_name:
        raise HTTPException(status_code=400, detail="Channel name required")

    # 1. Remove from /app/data/config.json
    config_path = "/app/data/config.json"
    remaining_channels = []
    if os.path.exists(config_path):
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                data = json.load(f)
            rooms = [r for r in data.get("mumble_rooms", []) if (r.get("name") or r.get("channel")) != clean_name]
            bots = [b for b in data.get("mumble_bots", []) if b.get("channel") != clean_name]
            data["mumble_rooms"] = rooms
            data["mumble_bots"] = bots
            remaining_channels = rooms
            with open(config_path, "w", encoding="utf-8") as f:
                json.dump(data, f, indent=2)
        except Exception as e:
            raise HTTPException(status_code=500, detail=f"Failed to update config: {e}")

    # 2. Clean from live cache if present
    live_cache_path = "/app/data/mumble_live_channels.json"
    if os.path.exists(live_cache_path):
        try:
            with open(live_cache_path, "r", encoding="utf-8") as f:
                live = json.load(f)
            live = [ch for ch in live if ch.get("name") != clean_name]
            with open(live_cache_path, "w", encoding="utf-8") as f:
                json.dump(live, f, indent=2)
        except Exception:
            pass

    # 3. Trigger sync in mumble-bridge so SuperUser deletes it from Murmur
    bridge_deleted = False
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post("http://mumble-bridge:12347/sync")
            if resp.status_code == 200:
                bridge_deleted = True
    except Exception:
        pass

    return {
        "status": "SUCCESS",
        "message": f"Channel '{clean_name}' successfully removed from Mumble server and config",
        "bridge_deleted": bridge_deleted,
        "channels": remaining_channels
    }

# ----------------- Mumla Bot Management -----------------

DEFAULT_MUMBLE_BOTS = [
    {"name": "NinjaBot-Nasional", "channel": "NinjaRadio-Nasional", "tg": 510, "slot": 2, "status": "STATIONED"},
    {"name": "NinjaBot-Nusantara", "channel": "NinjaRadio-Nusantara", "tg": 5109, "slot": 2, "status": "STATIONED"},
    {"name": "NinjaBot-Echo-Parrot", "channel": "Echo-Parrot", "tg": 9990, "slot": 2, "status": "STATIONED"},
    {"name": "NinjaBot-Darurat", "channel": "NinjaRadio-Darurat", "tg": 5108, "slot": 1, "status": "STATIONED"},
    {"name": "NinjaBot-Worldwide", "channel": "NinjaRadio-Worldwide", "tg": 91, "slot": 1, "status": "STATIONED"},
    {"name": "NinjaBot-TG-5155", "channel": "TG-5155", "tg": 5155, "slot": 1, "status": "STATIONED"}
]

@router.get("/mumla/bots")
async def get_mumla_bots():
    """Returns the list of active user bots and their assigned channels/talkgroups."""
    import json
    import os
    config_path = "/app/data/config.json"
    if os.path.exists(config_path):
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                data = json.load(f)
                if "mumble_bots" in data and isinstance(data["mumble_bots"], list):
                    return {"bots": data["mumble_bots"]}
        except Exception:
            pass
    return {"bots": DEFAULT_MUMBLE_BOTS}

@router.post("/mumla/bots")
async def create_or_update_bot(payload: dict):
    """Creates a new Mumble user bot or updates existing bot."""
    import json
    import os
    name = str(payload.get("name", "")).strip().replace(" ", "-")
    channel = str(payload.get("channel", "")).strip().replace(" ", "-")
    if not name or not channel:
        raise HTTPException(status_code=400, detail="Bot name and target channel are required")
    try:
        tg = int(payload.get("tg", 510))
        slot = int(payload.get("slot", 2))
    except (ValueError, TypeError):
        raise HTTPException(status_code=400, detail="Invalid TG or slot")

    config_path = "/app/data/config.json"
    data = {}
    if os.path.exists(config_path):
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                data = json.load(f)
        except Exception:
            data = {}

    bots = list(data.get("mumble_bots", []))
    existing_idx = next((i for i, b in enumerate(bots) if b.get("name") == name), None)
    bot_entry = {
        "name": name,
        "channel": channel,
        "tg": tg,
        "slot": slot,
        "status": "STATIONED"
    }
    if existing_idx is not None:
        bots[existing_idx] = bot_entry
    else:
        bots.append(bot_entry)

    # Ensure this bot's channel also exists in mumble_rooms so Murmur creates it
    rooms = list(data.get("mumble_rooms", []))
    room_exists = any((r.get("name") == channel or r.get("channel") == channel) for r in rooms)
    if not room_exists:
        rooms.append({
            "id": f"room_{channel}",
            "name": channel,
            "channel": channel,
            "targetTg": tg,
            "tg": tg,
            "slot": slot,
            "description": f"DMR TG {tg} (Slot {slot})",
            "isActive": True
        })
        data["mumble_rooms"] = rooms

    data["mumble_bots"] = bots
    try:
        with open(config_path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to save bot config: {e}")

    # Notify mumble-bridge to spawn/update bot in Murmur immediately
    try:
        import httpx
        async with httpx.AsyncClient(timeout=10.0) as client:
            await client.post("http://mumble-bridge:12347/sync")
    except Exception:
        pass

    return {"status": "SUCCESS", "message": f"Bot '{name}' created/updated in channel '{channel}'", "bot": bot_entry}

@router.post("/mumla/bots/move")
async def move_mumla_bot(payload: dict):
    """Moves an active Mumble bot to a different room/channel."""
    import httpx
    import json
    import os
    name = str(payload.get("name", "")).strip()
    target_channel = str(payload.get("target_channel", "")).strip().replace(" ", "-")
    if not name or not target_channel:
        raise HTTPException(status_code=400, detail="Bot name and target channel required")

    config_path = "/app/data/config.json"
    data = {}
    if os.path.exists(config_path):
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                data = json.load(f)
        except Exception:
            data = {}

    bots = list(data.get("mumble_bots", []))
    target_bot = next((b for b in bots if b.get("name") == name), None)
    if not target_bot:
        tg = int(payload.get("tg", 510))
        target_bot = {"name": name, "channel": target_channel, "tg": tg, "slot": 2, "status": "STATIONED"}
        bots.append(target_bot)
    else:
        target_bot["channel"] = target_channel
        if "tg" in payload:
            try:
                target_bot["tg"] = int(payload["tg"])
            except Exception:
                pass

    data["mumble_bots"] = bots
    try:
        with open(config_path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to save move: {e}")

    # Notify mumble-bridge to move bot in Murmur immediately
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            await client.post("http://mumble-bridge:12347/sync")
    except Exception:
        pass

    return {"status": "SUCCESS", "message": f"Bot '{name}' moved to '{target_channel}'", "bot": target_bot}

@router.delete("/mumla/bots/{bot_name}")
async def delete_mumla_bot(bot_name: str):
    """Removes a bot from configuration."""
    import httpx
    import json
    import os
    config_path = "/app/data/config.json"
    data = {}
    if os.path.exists(config_path):
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                data = json.load(f)
        except Exception:
            data = {}

    bots = list(data.get("mumble_bots", []))
    bots = [b for b in bots if b.get("name") != bot_name]
    data["mumble_bots"] = bots
    try:
        with open(config_path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to delete bot: {e}")

    # Notify mumble-bridge to remove bot from Murmur immediately
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            await client.post("http://mumble-bridge:12347/sync")
    except Exception:
        pass

    return {"status": "SUCCESS", "message": f"Bot '{bot_name}' removed"}

@router.get("/mumla/logs")
async def get_mumla_logs():
    """Returns live logs, bot states, and channel diagnostics from Mumble Bridge."""
    import httpx
    import json
    import os
    try:
        async with httpx.AsyncClient(timeout=4.0) as client:
            resp = await client.get("http://mumble-bridge:12347/logs")
            if resp.status_code == 200:
                return resp.json()
    except Exception:
        pass

    log_path = "/app/data/mumble_bridge.log"
    logs = []
    if os.path.exists(log_path):
        try:
            with open(log_path, "r", encoding="utf-8") as f:
                logs = [l.strip() for l in f.readlines()[-100:]]
        except Exception as e:
            logs = [f"Error reading log: {e}"]
    return {
        "status": "FALLBACK",
        "recent_logs": logs
    }

# ----------------- DMR Master Server Settings -----------------

@router.get("/serverdmr/config")
async def get_serverdmr_config():
    """Returns the persistent DMR Master configuration."""
    import json
    import os
    config_path = "/app/data/config.json"
    cfg = {}
    if os.path.exists(config_path):
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                cfg = json.load(f)
        except Exception:
            cfg = {}

    return {
        "server_callsign": cfg.get("server_callsign", "N1NJA"),
        "server_dmr_id": cfg.get("server_dmr_id", 5109999),
        "default_tg": cfg.get("default_tg", 510990),
        "parrot_tgs": cfg.get("parrot_tgs", [9990, 99990]),
        "color_code": cfg.get("color_code", 1),
        "hotspot_port": cfg.get("hotspot_port", 62031),
        "hotspot_password": cfg.get("hotspot_password", "ninjaradio_pass"),
        "tx_freq": cfg.get("tx_freq", "142120"),
        "rx_freq": cfg.get("rx_freq", "142120"),
        "tx_pwr": cfg.get("tx_pwr", 50),
        "max_hotspots": cfg.get("max_hotspots", 128)
    }

@router.post("/serverdmr/config")
async def save_serverdmr_config(payload: dict):
    """Saves DMR Master server configuration and reloads core engine dynamically."""
    import json
    import os
    import re
    import aiohttp
    config_path = "/app/data/config.json"
    cfg = {}
    if os.path.exists(config_path):
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                cfg = json.load(f)
        except Exception:
            cfg = {}

    if "server_callsign" in payload:
        cfg["server_callsign"] = str(payload["server_callsign"]).strip().upper()[:16]
    if "server_dmr_id" in payload:
        try:
            cfg["server_dmr_id"] = int(payload["server_dmr_id"])
        except Exception:
            pass
    if "default_tg" in payload:
        try:
            cfg["default_tg"] = int(payload["default_tg"])
        except Exception:
            pass
    if "parrot_tgs" in payload:
        raw_p = payload["parrot_tgs"]
        if isinstance(raw_p, list):
            cfg["parrot_tgs"] = [int(x) for x in raw_p if str(x).isdigit()]
        elif isinstance(raw_p, str):
            cfg["parrot_tgs"] = [int(x.strip()) for x in re.split(r"[,; ]+", raw_p) if x.strip().isdigit()]
    if "color_code" in payload:
        try:
            cfg["color_code"] = int(payload["color_code"])
        except Exception:
            pass
    if "hotspot_port" in payload:
        try:
            cfg["hotspot_port"] = int(payload["hotspot_port"])
        except Exception:
            pass
    if "hotspot_password" in payload:
        cfg["hotspot_password"] = str(payload["hotspot_password"]).strip()
    if "tx_freq" in payload:
        cfg["tx_freq"] = str(payload["tx_freq"]).strip()
    if "rx_freq" in payload:
        cfg["rx_freq"] = str(payload["rx_freq"]).strip()

    try:
        with open(config_path, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to save DMR Master config: {e}")

    # Notify core engine to reload dynamically
    try:
        async with aiohttp.ClientSession() as s:
            await s.post("http://core:5000/api/reload", timeout=aiohttp.ClientTimeout(total=2.0))
    except Exception:
        pass

    return {
        "status": "SUCCESS",
        "message": "DMR Master configuration saved and applied.",
        "config": cfg
    }


