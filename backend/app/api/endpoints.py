from fastapi import APIRouter, Depends, HTTPException, Query, Response, UploadFile, File, Form, Header, Request
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, or_
from typing import List, Optional
from datetime import datetime, timezone
import asyncio
import psutil
import time
import os
import logging

logger = logging.getLogger("NinjaRadio-Endpoints")

from backend.app.core.database import get_db, UserAccount, CallbookSyncSource
from backend.app.core.config import settings
from backend.app.models.schemas import (
    QsoLogResponse, QsoLogCreate,
    CallbookResponse, CallbookCreate,
    BridgeRouteResponse, BridgeRouteCreate, BridgeRouteUpdate,
    ServerStatus, LastHeardItem, PttTransmitRequest, PinVerifyRequest,
    OrganizationCreateRequest, OrganizationResponse, OrganizationRuleUpdate,
    SelfCareRegisterRequest, SelfCareLoginRequest, SelfCareServiceRequest,
    SelfCareRotatePasswordRequest, AdminApprovalRequest, AdminRejectRequest,
    UserAccountPublicResponse, UserAccountAdminResponse
)
from backend.app.services.logbook_service import LogbookService
from backend.app.services.callbook_service import CallbookService
from backend.app.services.bridge_service import BridgeService
from backend.app.services.dv_monitor import manager
from backend.app.services.selfcare_service import (
    SelfCareService, create_session_token, verify_session_token,
    sanitize_user_response, generate_protocol_password, trigger_protocol_reload
)

router = APIRouter()
START_TIME = time.time()

def get_current_iso_utc() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

def _trigger_ts3_talker(tg: int, callsign: Optional[str]):
    try:
        from backend.app.services.teamspeak_service import notify_ts3_talker_change
        asyncio.create_task(notify_ts3_talker_change(tg, callsign))
    except Exception:
        pass

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

    connected_hotspots = 0
    connected_peers = 0
    svxlink_clients = 0
    teamspeak_clients = 0
    mumble_clients = 0
    usrp_clients_cnt = 0
    crisscross_bridges = active_bridges
    outgoing_peers = 0

    cfg = read_config_file()
    
    # 1. Count configured outgoing bots (connecting outward to external servers)
    for b in cfg.get("teamspeak_bots", []):
        srv = str(b.get("server") or "").strip()
        if srv and not (srv.startswith("127.0.0.1") or srv.startswith("localhost") or srv.startswith("teamspeak-server")):
            outgoing_peers += 1
            
    for b in cfg.get("mumble_bots", []):
        srv = str(b.get("server") or "").strip()
        if srv and not (srv.startswith("127.0.0.1") or srv.startswith("localhost") or srv.startswith("mumble")):
            outgoing_peers += 1

    for b in cfg.get("svxlink_bots", []):
        srv = str(b.get("server") or "").strip()
        if srv and not (srv.startswith("127.0.0.1") or srv.startswith("localhost") or srv.startswith("svxreflector")):
            outgoing_peers += 1

    # 2. Count criss-cross bridges across all protocols (SvxLink + TS3 + Mumble + Zello + DB matrix)
    svx_bots_cnt = len(cfg.get("svxlink_bots", []))
    ts3_bots_cnt = len(cfg.get("teamspeak_bots", []))
    mumble_bots_cnt = len(cfg.get("mumble_bots", []))
    zello_active = 1 if cfg.get("zello_config", {}).get("enabled", True) else 0
    crisscross_bridges += (svx_bots_cnt + ts3_bots_cnt + mumble_bots_cnt + zello_active)

    # 3. Concurrently gather live protocol telemetry
    try:
        import httpx
        from backend.app.services.teamspeak_service import get_teamspeak_telemetry
        
        async with httpx.AsyncClient(timeout=1.0) as client:
            res = await asyncio.gather(
                client.get("http://core:5000/api/serverdmr/status"),
                client.get("http://core:5000/api/status"),
                client.get("http://core:5000/api/dvswitch/status"),
                client.get("http://svxlink-bridge:12349/status"),
                client.get("http://mumble-bridge:12347/status"),
                get_teamspeak_telemetry(),
                return_exceptions=True
            )
            
            # Hotspots count
            if not isinstance(res[0], Exception) and res[0].status_code == 200:
                hotspots = res[0].json().get("hotspots", [])
                connected_hotspots = len(hotspots)
            elif not isinstance(res[1], Exception) and res[1].status_code == 200:
                connected_hotspots = res[1].json().get("master_server", {}).get("connected_hotspots", 0)

            # Core Peers
            if not isinstance(res[1], Exception) and res[1].status_code == 200:
                cinfo = res[1].json()
                connected_peers = len([p for p in cinfo.get("peers", []) if p.get("state") == "CONNECTED"])
            
            # DVSwitch USRP clients
            if not isinstance(res[2], Exception) and res[2].status_code == 200:
                ddata = res[2].json()
                active_usrp = [u for u in ddata.get("connected_usrp_clients", []) if u.get("is_active", True)]
                usrp_clients_cnt = len(active_usrp)

            # SvxLink reflector clients
            if not isinstance(res[3], Exception) and res[3].status_code == 200:
                sdata = res[3].json()
                ref_nodes = sdata.get("reflector_nodes", {})
                bot_names = {b.get("name") for b in sdata.get("active_bots", [])}
                svx_ext = [k for k in ref_nodes.keys() if k not in bot_names and not str(k).startswith("SVX-GW")]
                svxlink_clients = len(svx_ext)

            # Mumble clients
            if not isinstance(res[4], Exception) and res[4].status_code == 200:
                mdata = res[4].json()
                mumble_clients = len(mdata.get("users", []))

            # TeamSpeak 3 clients
            if not isinstance(res[5], Exception) and isinstance(res[5], dict):
                teamspeak_clients = res[5].get("total_clients", 0)

    except Exception as e:
        logger.debug(f"Telemetry gather error in /status: {e}")

    web_clients = len(manager.active_connections) or 1
    total_clients = web_clients + connected_hotspots + usrp_clients_cnt + svxlink_clients + teamspeak_clients + mumble_clients

    return ServerStatus(
        server_name="NinjaRadio Master Core",
        server_callsign=settings.SERVER_CALLSIGN,
        server_id=settings.SERVER_DMR_ID,
        version=settings.VERSION,
        uptime=uptime_str,
        active_transmissions=1 if manager.current_tx else 0,
        connected_hotspots=connected_hotspots,
        web_clients=web_clients,
        connected_peers=connected_peers,
        active_bridges=active_bridges,
        cpu_usage=cpu,
        ram_usage=ram,
        svxlink_clients=svxlink_clients,
        teamspeak_clients=teamspeak_clients,
        mumble_clients=mumble_clients,
        total_clients=total_clients,
        outgoing_peers=outgoing_peers,
        crisscross_bridges=crisscross_bridges
    )

@router.get("/last-heard", response_model=List[LastHeardItem])
async def get_last_heard():
    return manager.last_heard_history

@router.get("/clients")
async def get_connected_clients():
    all_clients = []
    
    # 1. Web browser clients (from manager)
    web_sessions = manager.get_connected_clients()
    for ws in web_sessions:
        all_clients.append({
            "id": ws.get("id") or f"web_{abs(hash(ws.get('ip', '')))}",
            "protocol": "Web",
            "type": "Web Operator (PTT / Monitor)",
            "callsign": ws.get("callsign", "Web Operator"),
            "name": ws.get("app", "Web Dashboard"),
            "ip": ws.get("ip", "127.0.0.1"),
            "target": "Web Console / PTT",
            "connected_at": ws.get("connected_at") or get_current_iso_utc(),
            "status": "ONLINE",
            "details": ws.get("device", "Browser Client"),
            "app": ws.get("app", "Web Dashboard"),
            "device": ws.get("device", "Browser Client")
        })
        
    cfg = read_config_file()
    
    # 2. Concurrently fetch external telemetries
    try:
        import httpx
        from backend.app.services.teamspeak_service import get_teamspeak_telemetry
        
        async with httpx.AsyncClient(timeout=1.2) as client:
            res = await asyncio.gather(
                client.get("http://core:5000/api/serverdmr/status"),
                client.get("http://core:5000/api/status"),
                client.get("http://core:5000/api/dvswitch/status"),
                client.get("http://svxlink-bridge:12349/status"),
                client.get("http://mumble-bridge:12347/status"),
                get_teamspeak_telemetry(),
                return_exceptions=True
            )
            
            # MMDVM Hotspots (check serverdmr first, fallback to core status)
            hotspots = []
            if not isinstance(res[0], Exception) and res[0].status_code == 200:
                hotspots = res[0].json().get("hotspots", [])
            elif not isinstance(res[1], Exception) and res[1].status_code == 200:
                hotspots = res[1].json().get("master_server", {}).get("hotspots", [])
                
            for hs in hotspots:
                h_call = str(hs.get("callsign") or "HOTSPOT").upper()
                h_id = hs.get("dmr_id") or 0
                all_clients.append({
                    "id": f"mmdvm_{hs.get('addr', h_call)}",
                    "protocol": "MMDVM",
                    "type": "MMDVM Hotspot / Repeater",
                    "callsign": h_call,
                    "name": f"DMR ID: {h_id}",
                    "ip": str(hs.get("addr", "remote")),
                    "target": str(hs.get("subscribed_tg", "TS1/TS2")),
                    "connected_at": str(hs.get("uptime", "Active")),
                    "status": "ONLINE" if hs.get("status") in ("ONLINE", "CONNECTED") or hs.get("state") == "CONNECTED" else "CONNECTING",
                    "details": str(hs.get("software", "MMDVM_Host")),
                    "app": f"MMDVM ({h_call})",
                    "device": str(hs.get("software", "MMDVM Modem"))
                })
                
            # DVSwitch USRP Clients
            if not isinstance(res[2], Exception) and res[2].status_code == 200:
                ddata = res[2].json()
                for uc in ddata.get("connected_usrp_clients", []):
                    if not uc.get("is_active", True):
                        continue
                    u_call = (uc.get("callsign") or f"DVS-{uc.get('dmr_id') or uc.get('port')}").upper()
                    u_sid = uc.get("dmr_id") or 0
                    u_tg = uc.get("tg") or 1025
                    all_clients.append({
                        "id": f"dvs_{uc.get('ip')}_{uc.get('port')}",
                        "protocol": "DVSwitch",
                        "type": "DVSwitch Mobile / USRP",
                        "callsign": u_call,
                        "name": f"DMR ID: {u_sid}",
                        "ip": f"{uc.get('ip')}:{uc.get('port')}",
                        "target": f"TG {u_tg}",
                        "connected_at": f"Active ({uc.get('last_seen_seconds_ago', 0)}s ago)",
                        "status": "TALKING" if uc.get("is_transmitting") else "ONLINE",
                        "details": f"UDP USRP Port {uc.get('port')}",
                        "app": f"DVSwitch Mobile ({u_call})",
                        "device": "Android / iOS USRP"
                    })
                    
            # SvxLink Nodes (SvxReflector clients)
            if not isinstance(res[3], Exception) and res[3].status_code == 200:
                sdata = res[3].json()
                ref_nodes = sdata.get("reflector_nodes", {})
                bot_names = {b.get("name") for b in sdata.get("active_bots", [])}
                for ncall, ninfo in ref_nodes.items():
                    if ncall in bot_names or str(ncall).startswith("SVX-GW"):
                        continue
                    ntg = ninfo.get("tg") or "Standby"
                    all_clients.append({
                        "id": f"svx_{ncall}",
                        "protocol": "SvxLink",
                        "type": "SvxLink Node / Repeater",
                        "callsign": str(ncall).upper(),
                        "name": str(ninfo.get("nodeLocation") or "SvxReflector Node"),
                        "ip": f"{sdata.get('reflector_host', 'svxreflector:5300')}",
                        "target": f"SvxReflector TG {ntg}",
                        "connected_at": "Active",
                        "status": "TALKING" if ninfo.get("isTalker") else "ONLINE",
                        "details": f"{ninfo.get('sw', 'SvxLink')} {ninfo.get('swVer', '')}".strip() or "SvxLink Client",
                        "app": f"SvxLink ({ncall})",
                        "device": str(ninfo.get("nodeLocation") or "RF Node")
                    })
                    
            # Mumble Users
            if not isinstance(res[4], Exception) and res[4].status_code == 200:
                mdata = res[4].json()
                for mu in mdata.get("users", []):
                    muname = mu.get("name", "User")
                    all_clients.append({
                        "id": f"mum_user_{muname}",
                        "protocol": "Mumble",
                        "type": "Mumble Voice Client",
                        "callsign": str(muname),
                        "name": "Mumble / Mumla App",
                        "ip": f"{mdata.get('murmur_host', 'mumble:64738')}",
                        "target": f"Room ID {mu.get('channel_id', 0)}",
                        "connected_at": "Online",
                        "status": "ONLINE",
                        "details": "Murmur High-Quality Opus Voice",
                        "app": f"Mumble ({muname})",
                        "device": "Mumble Client"
                    })
                    
            # TeamSpeak 3 Users
            if not isinstance(res[5], Exception) and isinstance(res[5], dict):
                ts_data = res[5]
                ts_channels = {c.get("cid"): c.get("name") for c in ts_data.get("channels", [])}
                for tu in ts_data.get("clients", []):
                    t_nick = tu.get("nickname", "Guest")
                    t_cid = tu.get("cid")
                    t_chname = ts_channels.get(t_cid, f"Channel {t_cid}")
                    all_clients.append({
                        "id": f"ts3_user_{tu.get('clid')}_{t_nick}",
                        "protocol": "TeamSpeak",
                        "type": "TeamSpeak 3 User",
                        "callsign": str(t_nick),
                        "name": f"TS3 Client (CLID {tu.get('clid')})",
                        "ip": f"{ts_data.get('host', 'teamspeak')}:{ts_data.get('voice_port', 9987)}",
                        "target": f"Channel: {t_chname}",
                        "connected_at": "Online",
                        "status": "ONLINE",
                        "details": "TeamSpeak 3 Voice",
                        "app": f"TeamSpeak 3 ({t_nick})",
                        "device": "TS3 Client App"
                    })

    except Exception as e:
        logger.debug(f"Error gathering external clients: {e}")

    # 3. Outgoing Link Peers (External TS3, Mumble, SvxLink Bots)
    # TS3 External Bots
    for b in cfg.get("teamspeak_bots", []):
        srv = str(b.get("server") or "").strip()
        if srv and not (srv.startswith("127.0.0.1") or srv.startswith("localhost") or srv.startswith("teamspeak-server")):
            bname = str(b.get("name") or "TS3-OUT").upper()
            all_clients.append({
                "id": f"out_ts3_{bname}",
                "protocol": "Outgoing",
                "type": "TS3 Outgoing Bot Link",
                "callsign": bname,
                "name": f"Remote TS3: {srv}",
                "ip": srv,
                "target": f"{b.get('channel', 'Room')} ➔ TG {b.get('tg', 510)}",
                "connected_at": "Stationed Link",
                "status": "LINKED",
                "details": f"HoneyBBQ Cross-Link to {b.get('svx_bot', 'Local')}",
                "app": f"HoneyBBQ ({bname})",
                "device": f"External TS3 ({srv})"
            })

    # Mumble External Bots
    for b in cfg.get("mumble_bots", []):
        srv = str(b.get("server") or "").strip()
        if srv and not (srv.startswith("127.0.0.1") or srv.startswith("localhost") or srv.startswith("mumble")):
            bname = str(b.get("name") or "MUM-OUT").upper()
            all_clients.append({
                "id": f"out_mum_{bname}",
                "protocol": "Outgoing",
                "type": "Mumble Outgoing Bot Link",
                "callsign": bname,
                "name": f"Remote Mumble: {srv}",
                "ip": srv,
                "target": f"{b.get('channel', 'Room')} ➔ TG {b.get('tg', 510)}",
                "connected_at": "Stationed Link",
                "status": "LINKED",
                "details": f"Mumble Bridge Cross-Link",
                "app": f"Mumble Bot ({bname})",
                "device": f"External Mumble ({srv})"
            })

    # SvxLink External Bots
    for b in cfg.get("svxlink_bots", []):
        srv = str(b.get("server") or "").strip()
        if srv and not (srv.startswith("127.0.0.1") or srv.startswith("localhost") or srv.startswith("svxreflector")):
            bname = str(b.get("name") or "SVX-OUT").upper()
            all_clients.append({
                "id": f"out_svx_{bname}",
                "protocol": "Outgoing",
                "type": "SvxLink Outgoing Bot Link",
                "callsign": bname,
                "name": f"Remote SvxReflector: {srv}",
                "ip": srv,
                "target": f"TG {b.get('tg', 510)}",
                "connected_at": "Stationed Link",
                "status": "LINKED",
                "details": f"SvxLink Cross-Link",
                "app": f"SvxLink Bot ({bname})",
                "device": f"External Reflector ({srv})"
            })

    return all_clients

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

@router.get("/peers")
async def get_configured_peers():
    cfg = read_config_file()
    peers = cfg.get("peering", [])
    result = []
    for p in peers:
        p_name = str(p.get("name") or "").strip()
        if p_name:
            result.append({
                "id": p.get("id"),
                "name": p_name,
                "host": p.get("host", ""),
                "port": p.get("port", 62031),
                "isActive": p.get("isActive", True)
            })
    return result

@router.delete("/peering/{peer_id_or_name}")
async def delete_peering_peer(peer_id_or_name: str):
    """Deletes a peer from config.json by ID or Name."""
    cfg = read_config_file()
    peers = cfg.get("peering", [])
    target = peer_id_or_name.strip().lower()
    new_peers = [
        p for p in peers
        if str(p.get("id", "")).strip().lower() != target
        and str(p.get("name", "")).strip().lower() != target
        and str(p.get("host", "")).strip().lower() != target
    ]
    cfg["peering"] = new_peers
    await save_system_config(cfg)
    return {"status": "SUCCESS", "message": f"Peer '{peer_id_or_name}' deleted successfully", "remaining": len(new_peers)}

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
            "started_at": get_current_iso_utc()
        }
        await manager.update_tx_state(parrot_tx)
        
        # Audio playback duration
        await asyncio.sleep(3.5)
        
        # 2. Parrot stops transmitting
        await manager.update_tx_state(None)
        
        # 3. Push Parrot to Last Heard
        lh_item = {
            "id": f"tx_parrot_{int(datetime.utcnow().timestamp())}",
            "timestamp": get_current_iso_utc(),
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
_core_resolved_ip = None
_core_resolved_at = 0.0

def _get_core_ip() -> str:
    global _core_resolved_ip, _core_resolved_at
    now = time.time()
    if not _core_resolved_ip or (now - _core_resolved_at) > 3.0:
        import socket
        try:
            _core_resolved_ip = socket.gethostbyname(CORE_USRP_HOST)
            _core_resolved_at = now
        except Exception:
            if not _core_resolved_ip:
                _core_resolved_ip = CORE_USRP_HOST
    return _core_resolved_ip
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
        # Dispatch Web PTT directly to Core Direct Z3DMR Ingress (magic: b"NWEB")
        hdr = b"NWEB" + struct.pack(">IIII?16s", safe_dmr_id, safe_tg, safe_slot, len(payload), bool(is_tx), call_b)
        _direct_dmr_sock.sendto(hdr + payload, (_get_core_ip(), CORE_DIRECT_DMR_PORT))
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
                    sock.sendto(tlv_header + tlv, (_get_core_ip(), CORE_USRP_PORT))
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
                    sock.sendto(header + chunk, (_get_core_ip(), CORE_USRP_PORT))
        else:
            # Unkey event: flush residual buffer, reset session flags, and send standard unkey packet
            _tx_session_active = False
            _last_tx_tg = 0
            _last_tx_id = 0
            _tx_pcm_buffer.clear()
            _usrp_packet_seq = (_usrp_packet_seq + 1) % 65536
            header = b"USRP" + struct.pack(">iiiiiii", _usrp_packet_seq, 0, 0, safe_tg, safe_dmr_id, safe_slot, 0)
            sock.sendto(header + (b"\x00" * 320), (_get_core_ip(), CORE_USRP_PORT))
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
    return {"valid": payload.pin.strip() == admin_pin}

@router.post("/auth/change-pin")
async def change_admin_pin(payload: dict):
    """Changes admin PIN and persists it to /app/data/config.json."""
    old_pin = str(payload.get("old_pin", "")).strip()
    new_pin = str(payload.get("new_pin", "")).strip()
    current_pin = get_admin_pin()
    if old_pin != current_pin:
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
async def web_ptt_transmit(
    payload: PttTransmitRequest,
    session: AsyncSession = Depends(get_db),
    authorization: Optional[str] = Header(None)
):
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
                detail="Mode Monitor (SWL) tidak diizinkan memancar. Silakan gunakan profil Callsign pribadi atau login akun Self-Care."
            )

        # Enforce Self-Care account rules if account exists or token supplied
        token_user = None
        if authorization and authorization.lower().startswith("bearer "):
            token_payload = verify_session_token(authorization[7:].strip())
            if not token_payload:
                raise HTTPException(status_code=401, detail="Sesi login Self-Care tidak valid atau kedaluwarsa.")
            res_u = await session.execute(select(UserAccount).where(UserAccount.id == token_payload.get("uid")))
            token_user = res_u.scalar_one_or_none()
            if not token_user or token_user.callsign != callsign.upper():
                raise HTTPException(status_code=403, detail="Callsign tidak sesuai dengan akun Self-Care yang login.")

        if not token_user:
            res_u = await session.execute(
                select(UserAccount).where(
                    or_(UserAccount.callsign == callsign.upper(), UserAccount.dmr_id == dmr_id)
                )
            )
            token_user = res_u.scalar_one_or_none()

        if token_user:
            if token_user.status != "APPROVED":
                raise HTTPException(
                    status_code=403,
                    detail=f"Akun {token_user.callsign} berstatus {token_user.status} (hanya bisa RX/Monitor sampai disetujui Admin)."
                )
            # Automatically enable allow_web for approved accounts
            if not token_user.allow_web:
                token_user.allow_web = True
                await session.commit()

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

    stream_key = f"{dmr_id}_{tg}_{slot}"
    if is_transmitting:
        now_ts = time.time()
        tx_data = {
            "id": f"tx_web_{stream_key}",
            "stream_key": stream_key,
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
            "started_at": get_current_iso_utc(),
            "start_time": now_ts,
            "last_active": now_ts
        }
        ACTIVE_STREAMS[stream_key] = tx_data
        await manager.update_stream(stream_key, tx_data)
        _trigger_ts3_talker(tg, callsign)
        return {"status": "TX_STARTED", "data": tx_data}
    else:
        ACTIVE_STREAMS.pop(stream_key, None)
        await manager.update_stream(stream_key, None)
        _trigger_ts3_talker(tg, None)
        lh_item = {
            "id": f"tx_web_{stream_key}_{int(datetime.utcnow().timestamp())}",
            "timestamp": get_current_iso_utc(),
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
                await manager.update_stream(stream_key, None)
                _trigger_ts3_talker(int(info.get("talkgroup", 0)), None)

                start_ts = info.get("start_time", now - 3.0)
                dur = round(max(0.5, now - start_ts), 1)
                lh_item = {
                    "id": f"lh_{stream_key}_{int(now)}",
                    "timestamp": get_current_iso_utc(),
                    "callsign": info.get("callsign", "N1NJA"),
                    "name": info.get("name", "DMR Operator"),
                    "dmr_id": info.get("dmr_id", 0),
                    "talkgroup": info.get("talkgroup", 510),
                    "tg_name": info.get("tg_name", "Talkgroup 510"),
                    "slot": info.get("slot", 1),
                    "mode": info.get("mode", "DMR"),
                    "category": info.get("category", "Amatir"),
                    "duration": f"{dur}s",
                    "loss": info.get("loss", "0.0%"),
                    "ber": info.get("ber", "0.0%"),
                    "rssi": info.get("rssi", "Digital IP"),
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

    # Check if stream is already active (subsequent 60ms bursts inherit resolved identity without DB overhead)
    cached = ACTIVE_STREAMS.get(stream_key)
    custom_call = event.get("callsign")

    if cached:
        callsign = cached.get("callsign") or custom_call or f"ID-{src_id}"
        name = cached.get("name") or f"DMR Operator {src_id}"
        category = cached.get("category") or "Amatir"
    else:
        # Resolve callsign from database cache or perform one-time async lookup on initial burst
        callsign = custom_call if custom_call else f"ID-{src_id}"
        name = f"DMR Operator {src_id}"
        category = "Amatir"

        lookup_id = src_id // 100 if src_id > 9999999 else src_id
        cb_entry = None
        if lookup_id in _CALLBOOK_CACHE:
            cb_entry = _CALLBOOK_CACHE[lookup_id]
        elif lookup_id > 0:
            cb_entry = await CallbookService.get_by_dmr_id(session, lookup_id)
            # Cache result (including negative None) to stop 50x/sec SQLite queries
            _CALLBOOK_CACHE[lookup_id] = cb_entry
            _CALLBOOK_CACHE[src_id] = cb_entry

        if not cb_entry and custom_call:
            clean_lookup = custom_call[3:] if custom_call.startswith("ZL:") else custom_call
            if clean_lookup in _CALLBOOK_CACHE:
                cb_entry = _CALLBOOK_CACHE[clean_lookup]
            else:
                cb_entry = await CallbookService.get_by_callsign(session, clean_lookup)
                _CALLBOOK_CACHE[clean_lookup] = cb_entry

        if cb_entry:
            callsign = cb_entry.callsign
            name = cb_entry.name
            category = cb_entry.organization or "Amatir"
        elif custom_call and custom_call.startswith("ZL:"):
            callsign = custom_call
            name = f"Zello User ({custom_call[3:]})"
            category = "Zello"
        elif custom_call:
            # Smart Callsign & Name separation for VoIP / TS3 / SvxLink nicknames
            c_clean = custom_call.strip()
            if " - " in c_clean:
                parts = c_clean.split(" - ", 1)
                callsign = parts[0].strip().upper()
                name = parts[1].strip()
            elif "/" in c_clean:
                parts = c_clean.split("/", 1)
                callsign = parts[0].strip().upper()
                name = parts[1].strip()
            elif " " in c_clean:
                parts = c_clean.split(None, 1)
                callsign = parts[0].strip().upper()
                name = parts[1].strip()
            else:
                callsign = c_clean.upper()
                name = c_clean
            category = "VoIP Gateway"

    tg_name = TG_NAMES.get(dst_tg, f"Talkgroup {dst_tg}")

    # Voice Header or active bursts (data_type 0, 1, or 3)
    if data_type in [0, 1, 3]:
        now = time.time()
        is_new_stream = stream_key not in ACTIVE_STREAMS
        start_time = cached.get("start_time", now) if cached else now
        started_at = cached.get("started_at", get_current_iso_utc()) if cached else get_current_iso_utc()
        last_broadcast = cached.get("last_broadcast", 0.0) if cached else 0.0

        mode_val = cached.get("mode") if (cached and cached.get("mode") == "DMR (WebRTC)") else f"DMR ({network})"
        live_rssi = str(event.get("rssi") or (cached.get("rssi") if cached else None) or "Digital IP")
        live_loss = str(event.get("loss") or (cached.get("loss") if cached else None) or "0.0%")
        live_ber = str(event.get("ber") or (cached.get("ber") if cached else None) or "0.0%")
        tx_data = {
            "id": cached.get("id", f"tx_rf_{stream_key}") if cached else f"tx_rf_{stream_key}",
            "callsign": callsign,
            "name": name,
            "dmr_id": src_id,
            "talkgroup": dst_tg,
            "tg_name": tg_name,
            "slot": slot,
            "mode": mode_val,
            "category": category,
            "rssi": live_rssi,
            "loss": live_loss,
            "ber": live_ber,
            "started_at": started_at,
            "start_time": start_time,
            "last_active": now,
            "last_broadcast": last_broadcast
        }
        ACTIVE_STREAMS[stream_key] = tx_data

        tx_data["stream_key"] = stream_key
        # Throttle TX_STATE_CHANGE to avoid flooding WebSockets 50x per sec (prevents React re-render lag)
        if is_new_stream or (now - last_broadcast > 0.5):
            tx_data["last_broadcast"] = now
            await manager.update_stream(stream_key, tx_data)
            if is_new_stream:
                _trigger_ts3_talker(dst_tg, callsign)

        # Broadcast live audio chunk to web clients if payload present and web clients connected
        payload_b64 = event.get("payload_b64")
        if payload_b64 and getattr(manager, "active_connections", None):
            try:
                await manager.broadcast({
                    "type": "AUDIO_RX",
                    "data": {
                        "src_id": src_id,
                        "callsign": callsign,
                        "tg": dst_tg,
                        "slot": slot,
                        "pcm": payload_b64
                    }
                })
            except Exception:
                pass

    # Voice Terminator (Call Ended)
    elif data_type == 2:
        cached = ACTIVE_STREAMS.pop(stream_key, None)
        await manager.update_stream(stream_key, None)
        _trigger_ts3_talker(dst_tg, None)
        now = time.time()
        start_ts = cached.get("start_time", now - 3.5) if cached else (now - 3.5)
        dur = round(max(0.5, now - start_ts), 1)

        if cached:
            callsign = cached.get("callsign") or callsign
            name = cached.get("name") or name
            category = cached.get("category") or category

        lh_rssi = str(event.get("rssi") or (cached.get("rssi") if cached else None) or "Digital IP")
        lh_loss = str(event.get("loss") or (cached.get("loss") if cached else None) or "0.0%")
        lh_ber = str(event.get("ber") or (cached.get("ber") if cached else None) or "0.0%")
        lh_item = {
            "id": f"lh_{stream_key}_{int(datetime.utcnow().timestamp())}",
            "timestamp": get_current_iso_utc(),
            "callsign": callsign,
            "name": name,
            "dmr_id": src_id,
            "talkgroup": dst_tg,
            "tg_name": tg_name,
            "slot": slot,
            "mode": f"DMR ({network})",
            "category": category,
            "duration": f"{dur}s",
            "loss": lh_loss,
            "ber": lh_ber,
            "rssi": lh_rssi,
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

DEFAULT_SVXLINK_ROOMS = [
    {"id": "svx_gateway", "name": "SvxLink Any-TG Gateway", "channel": "Direct Any-TG", "targetTg": 0, "tg": 0, "slot": 2, "description": "SvxLink Dynamic Direct Any-TG Gateway", "isActive": True}
]

DEFAULT_SVXLINK_BOTS = [
    {"name": "SVX-GATEWAY", "channel": "Direct Any-TG", "tg": 0, "slot": 2, "status": "ONLINE"}
]

DEFAULT_TEAMSPEAK_ROOMS = [
    {"cid": 2, "id": "ts3_1025", "name": "TG 1025 - BRIDISV", "channel": "TG 1025 - BRIDISV", "targetTg": 1025, "tg": 1025, "slot": 2, "description": "DMR TG 1025 Slot 2", "isActive": True},
    {"cid": 3, "id": "ts3_510990", "name": "TG 510990 - Ninja", "channel": "TG 510990 - Ninja", "targetTg": 510990, "tg": 510990, "slot": 2, "description": "DMR TG 510990 Slot 2", "isActive": True},
    {"cid": 4, "id": "ts3_510", "name": "TG 510 - Nasional", "channel": "TG 510 - Nasional", "targetTg": 510, "tg": 510, "slot": 1, "description": "DMR TG 510 Slot 1", "isActive": True},
    {"cid": 5, "id": "ts3_5109", "name": "TG 5109 - Nusantara", "channel": "TG 5109 - Nusantara", "targetTg": 5109, "tg": 5109, "slot": 2, "description": "DMR TG 5109 Slot 2", "isActive": True}
]

DEFAULT_TEAMSPEAK_BOTS = [
    {"name": "TS3-GW-BRIDISV", "channel": "TG 1025 - BRIDISV", "tg": 1025, "slot": 2, "svx_bot": "SVX-GWBRIDI", "status": "STATIONED"},
    {"name": "TS3-GW-NINJA", "channel": "TG 510990 - Ninja", "tg": 510990, "slot": 2, "svx_bot": "SVX-GW-NINJA", "status": "STATIONED"},
    {"name": "TS3-GW-NASIONAL", "channel": "TG 510 - Nasional", "tg": 510, "slot": 1, "svx_bot": "SVX-GW-NASIONAL", "status": "STATIONED"}
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
        "mumble_bots": DEFAULT_MUMBLE_BOTS,
        "svxlink_rooms": DEFAULT_SVXLINK_ROOMS,
        "svxlink_bots": DEFAULT_SVXLINK_BOTS,
        "teamspeak_rooms": DEFAULT_TEAMSPEAK_ROOMS,
        "teamspeak_bots": DEFAULT_TEAMSPEAK_BOTS,
        "zello_config": {
            "enabled": True,
            "channel": "NINJARADIOZL",
            "issuer": "WkM6R0FURVdBWS1OSU5KQTox.B9gxcZLgOZUmcY0g4HXmHGZsCvPNby0e6GWnsj04GuY",
            "target_tg": 510,
            "slot": 2,
            "bot_callsign": "NINJA-ZELLO"
        }
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
    for k in ["mumble_rooms", "mumble_bots", "svxlink_rooms", "svxlink_bots", "teamspeak_rooms", "teamspeak_bots", "zello_config", "parrot_tgs", "parrot_delay"]:
        if (k not in config or not config[k]) and k in existing and existing[k]:
            config[k] = existing[k]
    if "peering" not in config and "peering" in existing:
        config["peering"] = existing["peering"]

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

    # Notify mumble-bridge to synchronize channels & bots with Murmur
    if "mumble_rooms" in config or "mumble_bots" in config:
        try:
            import httpx
            async with httpx.AsyncClient(timeout=6.0) as client:
                await client.post("http://mumble-bridge:12347/sync")
        except Exception:
            pass

    # Notify svxlink-bridge to synchronize Multi-TG bots with SvxReflector
    if "svxlink_rooms" in config or "svxlink_bots" in config:
        try:
            reload_marker = "/app/data/.svxlink_reload"
            with open(reload_marker, "w") as rf:
                rf.write(str(time.time()))
        except Exception:
            pass
        try:
            import httpx
            async with httpx.AsyncClient(timeout=6.0) as client:
                await client.post("http://svxlink-bridge:12349/sync")
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

_last_serverdmr_telemetry = None
_last_serverdmr_time = 0.0

@router.get("/serverdmr/telemetry")
async def get_serverdmr_telemetry():
    global _last_serverdmr_telemetry, _last_serverdmr_time
    now = time.time()
    try:
        import httpx
        async with httpx.AsyncClient(timeout=4.0) as client:
            r = await client.get("http://core:5000/api/serverdmr/status")
            if r.status_code == 200:
                data = r.json()
                _last_serverdmr_telemetry = data
                _last_serverdmr_time = now
                return data
    except Exception:
        pass

    # Fallback to last known good telemetry if within 10 seconds grace period (anti flip-flop)
    if _last_serverdmr_telemetry and (now - _last_serverdmr_time < 10.0):
        return _last_serverdmr_telemetry

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

_last_dvswitch_telemetry = None
_last_dvswitch_time = 0.0

@router.get("/dvswitch/telemetry")
async def get_dvswitch_telemetry():
    global _last_dvswitch_telemetry, _last_dvswitch_time
    now = time.time()
    try:
        import httpx
        async with httpx.AsyncClient(timeout=4.0) as client:
            r = await client.get("http://core:5000/api/dvswitch/status")
            if r.status_code == 200:
                data = r.json()
                _last_dvswitch_telemetry = data
                _last_dvswitch_time = now
                return data
    except Exception:
        pass

    if _last_dvswitch_telemetry and (now - _last_dvswitch_time < 10.0):
        return _last_dvswitch_telemetry

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
        tg = int(payload.get("tg", 0))
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


# ----------------- FreeDMR Rules & Talkgroup Matrix Management -----------------

def _get_freedmr_file(filename: str) -> str:
    candidates = [
        os.path.join("/app/freedmr", filename),
        os.path.join(os.getcwd(), "freedmr", filename),
        os.path.join("/opt/freedmr", filename)
    ]
    for c in candidates:
        if os.path.exists(c):
            return c
    return candidates[0]

def _touch_freedmr_reload():
    reload_paths = ["/app/data/.freedmr_reload", "data/.freedmr_reload"]
    for rp in reload_paths:
        try:
            os.makedirs(os.path.dirname(rp), exist_ok=True)
            with open(rp, "w") as f:
                f.write(str(time.time()))
            break
        except Exception:
            pass

@router.get("/freedmr/diag")
async def get_freedmr_diag():
    """Diagnostic endpoint to inspect FreeDMR container reachability and socket status."""
    import socket
    import subprocess

    results = {
        "freedmr_dns": False,
        "freedmr_ip": None,
        "freedmr_62031_open": False,
        "freedmr_log_tail": "",
        "error": None
    }
    
    # 1. DNS check for freedmr container
    try:
        ip = socket.gethostbyname("freedmr")
        results["freedmr_dns"] = True
        results["freedmr_ip"] = ip
    except Exception as e:
        results["error"] = f"Cannot resolve container 'freedmr': {e}"
        return results

    # 2. Test UDP connect to DMR Master (62031)
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.settimeout(1.5)
        # Send RPTL ping
        sock.sendto(b"RPTL\x00\x0f\x68\x63PING\x00\x00\x00\x00", (ip, 62031))
        results["freedmr_62031_open"] = True
        sock.close()
    except Exception as e:
        results["freedmr_62031_open"] = f"Error: {e}"

    # 3. Check for log file in /app/data or local candidates
    for log_path in ["/app/data/freedmr.log", "/app/freedmr/freedmr.log"]:
        if os.path.exists(log_path):
            try:
                with open(log_path, "r", encoding="utf-8", errors="ignore") as f:
                    results["freedmr_log_tail"] = "".join(f.readlines()[-30:])
                break
            except Exception:
                pass

    return results


@router.get("/freedmr/rules")
async def get_freedmr_rules():
    """Returns FreeDMR rules.py raw content and parsed Talkgroup Matrix bridges."""
    import ast
    path = _get_freedmr_file("rules.py")
    content = ""
    bridges = {}
    if os.path.exists(path):
        try:
            with open(path, "r", encoding="utf-8") as f:
                content = f.read()
            tree = ast.parse(content)
            for stmt in tree.body:
                if isinstance(stmt, ast.Assign):
                    for target in stmt.targets:
                        if isinstance(target, ast.Name) and target.id == "BRIDGES":
                            bridges = ast.literal_eval(stmt.value)
        except Exception as e:
            logger.warning(f"Error parsing BRIDGES from rules.py: {e}")

    return {
        "path": path,
        "content": content,
        "bridges": bridges
    }

@router.post("/freedmr/rules")
async def save_freedmr_rules(payload: dict):
    """Saves FreeDMR rules.py from either raw Python code or structured Talkgroup Matrix."""
    import ast
    path = _get_freedmr_file("rules.py")
    raw_content = payload.get("content")
    bridges_data = payload.get("bridges")

    final_code = ""
    if raw_content is not None and str(raw_content).strip():
        # Validate Python syntax before saving
        try:
            compile(raw_content, "<rules.py>", "exec")
        except SyntaxError as e:
            raise HTTPException(status_code=400, detail=f"Python Syntax Error pada baris {e.lineno}: {e.msg}")
        except Exception as e:
            raise HTTPException(status_code=400, detail=f"Invalid Python code: {e}")
        final_code = raw_content
    elif isinstance(bridges_data, dict):
        # Format structured bridges dictionary into clean Python code
        lines = [
            "# FreeDMR Conference Bridge & Rules Configuration",
            "# Automatically maintained by NinjaRadio Web Admin",
            "",
            "BRIDGES = {"
        ]
        for tg, systems in bridges_data.items():
            lines.append(f"    '{tg}': [")
            for sys in systems:
                s_name = sys.get("SYSTEM", "HOTSPOT-MASTER")
                s_ts = sys.get("TS", 2)
                s_tgid = sys.get("TGID", int(tg) if str(tg).isdigit() else 1025)
                s_on = sys.get("ON", [s_tgid])
                s_off = sys.get("OFF", [])
                s_to = sys.get("TIMEOUT", sys.get("TO", 60))
                s_active = sys.get("ACTIVE", True)
                lines.append(f"        {{'SYSTEM': '{s_name}', 'TS': {s_ts}, 'TGID': {s_tgid}, 'ON': {s_on}, 'OFF': {s_off}, 'TIMEOUT': {s_to}, 'ACTIVE': {s_active}}},")
            lines.append("    ],")
        lines.append("}\n")
        final_code = "\n".join(lines)
    else:
        raise HTTPException(status_code=400, detail="Missing 'content' or 'bridges' payload.")

    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(final_code)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Gagal menulis ke rules.py: {e}")

    # Trigger live reload
    _touch_freedmr_reload()

    return {
        "status": "SUCCESS",
        "message": "rules.py (TG Matrix) berhasil disimpan dan FreeDMR di-reload secara live.",
        "path": path
    }

@router.get("/freedmr/config")
async def get_freedmr_config():
    """Returns FreeDMR freedmr.cfg raw file content."""
    path = _get_freedmr_file("freedmr.cfg")
    content = ""
    if os.path.exists(path):
        try:
            with open(path, "r", encoding="utf-8") as f:
                content = f.read()
        except Exception as e:
            raise HTTPException(status_code=500, detail=f"Gagal membaca freedmr.cfg: {e}")
    return {"path": path, "content": content}

@router.post("/freedmr/config")
async def save_freedmr_config(payload: dict):
    """Saves FreeDMR freedmr.cfg and triggers reload."""
    path = _get_freedmr_file("freedmr.cfg")
    content = payload.get("content")
    if content is None:
        raise HTTPException(status_code=400, detail="Missing 'content' in payload.")

    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(str(content))
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Gagal menulis freedmr.cfg: {e}")

    _touch_freedmr_reload()
    return {
        "status": "SUCCESS",
        "message": "freedmr.cfg berhasil disimpan dan FreeDMR di-reload.",
        "path": path
    }


# ----------------- SvxLink & SvxReflector Multi-TG Gateway Management -----------------

@router.get("/svxlink/telemetry")
async def get_svxlink_telemetry(request: Request, session: AsyncSession = Depends(get_db)):
    """Returns live SvxReflector & Multi-TG Unified Switchboard Node Matrix telemetry."""
    import asyncio
    import httpx
    import json
    import os

    svx_host = os.getenv("SVX_HOST", "svxreflector")
    svx_port = int(os.getenv("SVX_PORT", "5300"))
    svx_auth_key = os.getenv("SVX_AUTH_KEY", "ninjaradio_svx")
    now = time.time()

    tcp_connected = False
    try:
        reader, writer = await asyncio.wait_for(
            asyncio.open_connection(svx_host, svx_port),
            timeout=0.8
        )
        tcp_connected = True
        writer.close()
        await writer.wait_closed()
    except Exception:
        tcp_connected = False

    reflector_nodes = {}
    active_bots = []
    mumble_bots = []
    core_peers = []
    core_hotspots = []
    usrp_clients = []

    async with httpx.AsyncClient(timeout=1.5) as client:
        results = await asyncio.gather(
            client.get("http://svxlink-bridge:12349/status"),
            client.get("http://mumble-bridge:12347/status"),
            client.get("http://core:5000/api/status"),
            client.get("http://core:5000/api/dvswitch/status"),
            return_exceptions=True
        )
        if not isinstance(results[0], Exception) and results[0].status_code == 200:
            bdata = results[0].json()
            reflector_nodes = bdata.get("reflector_nodes", {})
            active_bots = bdata.get("active_bots", [])
        if not isinstance(results[1], Exception) and results[1].status_code == 200:
            mdata = results[1].json()
            mumble_bots = mdata.get("active_bots", [])
        if not isinstance(results[2], Exception) and results[2].status_code == 200:
            cdata = results[2].json()
            core_peers = cdata.get("peers", [])
            core_hotspots = cdata.get("master_server", {}).get("hotspots", [])
        if not isinstance(results[3], Exception) and results[3].status_code == 200:
            ddata = results[3].json()
            usrp_clients = ddata.get("connected_usrp_clients", [])

    # Build active talkers per TG from ACTIVE_STREAMS + manager.current_tx
    active_streams_by_tg = {}
    for _, sinfo in list(ACTIVE_STREAMS.items()):
        stg = int(sinfo.get("talkgroup") or 0)
        if stg > 0:
            dur_s = max(1, int(now - float(sinfo.get("start_time", now))))
            active_streams_by_tg[stg] = {
                "callsign": sinfo.get("callsign", "N1NJA"),
                "name": sinfo.get("name", ""),
                "dmr_id": sinfo.get("dmr_id", 0),
                "tg": stg,
                "slot": sinfo.get("slot", 2),
                "mode": sinfo.get("mode", "DMR"),
                "duration_sec": dur_s
            }
    if manager.current_tx:
        ctx = manager.current_tx
        ctg = int(ctx.get("talkgroup") or 0)
        if ctg > 0 and ctg not in active_streams_by_tg:
            active_streams_by_tg[ctg] = {
                "callsign": ctx.get("callsign", "N1NJA"),
                "name": ctx.get("name", ""),
                "dmr_id": ctx.get("dmr_id", 0),
                "tg": ctg,
                "slot": ctx.get("slot", 2),
                "mode": ctx.get("mode", "DMR"),
                "duration_sec": 3
            }

    # Build Unified Node Cards (SvxReflector Dashboard Style)
    unified_nodes = []
    seen_callsigns = set()

    # 1. SvxLink Multi-TG Gateway Bots
    for b in active_bots:
        bcall = b.get("name", "SVX-NODE")
        btg = int(b.get("tg") or 0)
        bslot = int(b.get("slot") or 2)
        broom = b.get("channel") or f"TG {btg}"
        stream = active_streams_by_tg.get(btg)
        bot_talker = b.get("active_talker")
        is_talking = bool(stream or bot_talker)
        talker_call = (stream.get("callsign") if stream else None) or bot_talker or ""
        talk_dur = stream.get("duration_sec", 2) if stream else (3 if bot_talker else 0)
        if bcall == "SVX-GATEWAY":
            bridge_lbl = "SvxLink · Direct Any-TG Gateway"
            route_from_str = "SvxReflector / App"
            route_to_str = "DMR Core (Any TG)"
            m_tgs = [1025, 1023, 510, 5109, 5108, 91, 9990]
        else:
            bridge_lbl = f"SvxReflector · {broom}"
            route_from_str = f"SVX TG {btg}"
            route_to_str = f"DMR TG {btg} TS{bslot}"
            m_tgs = [btg]

        seen_callsigns.add(bcall)
        unified_nodes.append({
            "id": f"svx_{bcall}",
            "callsign": bcall,
            "category": "SvxLink",
            "sourceType": "Local",
            "bridgeLabel": bridge_lbl,
            "hostLabel": f"{svx_host}:{svx_port}",
            "routeFrom": route_from_str,
            "routeTo": route_to_str,
            "tg": btg,
            "slot": bslot,
            "monitoredTGs": m_tgs,
            "isTalking": is_talking,
            "talkerCallsign": talker_call,
            "talkingDurationSec": talk_dur,
            "connected": bool(b.get("connected", True)),
            "sysop": "NINJARADIO · SVX MULTI-TG CORE",
            "sw": "SvxLink_Bridge 1.9 (DirectDMR)"
        })

    # 2. External SvxReflector Nodes (Repeaters / Simplex / EchoLink connected to port 5300)
    for ncall, ninfo in reflector_nodes.items():
        if ncall in seen_callsigns:
            continue
        ntg = int(ninfo.get("tg") or 0)
        mon_tgs = ninfo.get("monitoredTGs") or ([ntg] if ntg > 0 else [])
        is_node_talker = bool(ninfo.get("isTalker"))
        stream = active_streams_by_tg.get(ntg) if ntg > 0 else None
        
        # An external node is ONLY 'talking' if this specific node is keying up itself
        if is_node_talker:
            is_talking = True
            talker_call = ncall
            talk_dur = 3
        else:
            is_talking = False
            talker_call = stream.get("callsign", "") if stream else ""
            talk_dur = stream.get("duration_sec", 0) if stream else 0

        seen_callsigns.add(ncall)
        unified_nodes.append({
            "id": f"ext_svx_{ncall}",
            "callsign": ncall,
            "category": "SvxLink",
            "sourceType": "Ext SVX",
            "bridgeLabel": ninfo.get("nodeLocation") or "External SvxLink Repeater",
            "hostLabel": f"svxreflector:{svx_port}",
            "routeFrom": f"RF / Analog Node",
            "routeTo": f"SVX TG {ntg or 'Standby'}",
            "tg": ntg,
            "slot": 2,
            "monitoredTGs": mon_tgs,
            "isTalking": is_talking,
            "talkerCallsign": talker_call,
            "talkingDurationSec": talk_dur,
            "connected": True,
            "sysop": ninfo.get("sysop") or "EXTERNAL REPEATER NODE",
            "sw": f"{ninfo.get('sw', 'SvxLink')} {ninfo.get('swVer', '')}".strip()
        })

    # Load TG Matrix cross-TG translation routes
    matrix_routes = []
    try:
        matrix_routes = await BridgeService.get_routes(session)
    except Exception:
        matrix_routes = []

    # 3. DMR Upstream Peers (e.g. BRIDI, FreeDMR, BrandMeister)
    for p in core_peers:
        pname = str(p.get("name") or p.get("host") or "DMR-PEER").upper()
        pcall = f"DMR-{pname[:10]}" if not pname.startswith("DMR") else pname
        sub_raw = p.get("subscribed_tgs") or p.get("subscribedTgs") or p.get("target_tg") or p.get("targetTg") or p.get("tg") or []
        if isinstance(sub_raw, str):
            sub_tgs = [int(x.strip()) for x in sub_raw.split(",") if x.strip().isdigit()]
        elif isinstance(sub_raw, list):
            sub_tgs = [int(x) for x in sub_raw if str(x).isdigit()]
        elif isinstance(sub_raw, int) and sub_raw > 0:
            sub_tgs = [sub_raw]
        else:
            sub_tgs = []
        primary_ptg = sub_tgs[0] if sub_tgs else 0

        # Check if this peer has an active TG Matrix translation rule
        peer_name_clean = str(p.get("name") or pname).strip().lower()
        mapped_rule = None
        for r in matrix_routes:
            if getattr(r, "is_active", True) and getattr(r, "peer_name", None):
                r_peer_clean = r.peer_name.strip().lower()
                if r_peer_clean in peer_name_clean or peer_name_clean in r_peer_clean:
                    mapped_rule = r
                    break

        if mapped_rule:
            s_tg = mapped_rule.source_tg
            t_tg = mapped_rule.target_tg
            t_slot = mapped_rule.target_slot or 2
            route_from_str = f"Peer TG {s_tg}"
            route_to_str = f"DMR TG {t_tg} TS{t_slot}"
            primary_ptg = t_tg
            # Isolated local monitoring: only monitor target TG so traffic on source TG (e.g. 1025) from other peers does not hijack this card!
            monitored_tgs = [t_tg]
        else:
            route_from_str = f"TG {primary_ptg}"
            route_to_str = f"DMR TG {primary_ptg} TS2"
            monitored_tgs = sub_tgs or [primary_ptg]

        active_st = None
        for t in monitored_tgs:
            if t in active_streams_by_tg:
                active_st = active_streams_by_tg[t]
                if not mapped_rule:
                    primary_ptg = t
                break

        unified_nodes.append({
            "id": f"peer_{pname}",
            "callsign": pcall,
            "category": "DMR",
            "sourceType": "Network",
            "bridgeLabel": f"DMR Master Peer · {p.get('name', pname)}",
            "hostLabel": f"{p.get('host', '')}:{p.get('port', 62031)}",
            "routeFrom": route_from_str,
            "routeTo": route_to_str,
            "tg": primary_ptg,
            "slot": 2,
            "monitoredTGs": monitored_tgs,
            "isTalking": bool(active_st),
            "talkerCallsign": active_st.get("callsign", "") if active_st else "",
            "talkingDurationSec": active_st.get("duration_sec", 0) if active_st else 0,
            "connected": p.get("state") == "CONNECTED",
            "sysop": f"NINJARADIO · {p.get('callsign', 'BD62BOT')}",
            "sw": "DMR_Bridge 2.0 (HomeBrew)"
        })

    # 4. Mumble Multi-TG Bots
    for mb in mumble_bots:
        mcall = mb.get("name", "MUM-BOT")
        mtg = int(mb.get("tg") or 0)
        mslot = int(mb.get("slot") or 2)
        mchan = mb.get("channel") or f"TG {mtg}"
        stream = active_streams_by_tg.get(mtg)
        mtalker = mb.get("active_talker")
        is_talking = bool(stream or mtalker)
        talker_call = (stream.get("callsign") if stream else None) or mtalker or ""
        talk_dur = stream.get("duration_sec", 2) if stream else (3 if mtalker else 0)
        unified_nodes.append({
            "id": f"mumble_{mcall}",
            "callsign": mcall,
            "category": "Mumble",
            "sourceType": "Local",
            "bridgeLabel": f"Mumble · {mchan}",
            "hostLabel": "mumble:64738",
            "routeFrom": f"TG {mtg}",
            "routeTo": f"MUMBLE {mchan}",
            "tg": mtg,
            "slot": mslot,
            "monitoredTGs": [mtg],
            "isTalking": is_talking,
            "talkerCallsign": talker_call,
            "talkingDurationSec": talk_dur,
            "connected": bool(mb.get("connected", True)),
            "sysop": "NINJARADIO · MUMBLE SERVER",
            "sw": "Mumble_Bridge 1.4"
        })

    # 5. Connected DVSwitch Mobile Clients (Per-User Isolated Sessions)
    for uc in usrp_clients:
        if not uc.get("is_active", True):
            continue
        ucall = (uc.get("callsign") or f"DVS-{uc.get('dmr_id') or uc.get('port')}").upper()
        utg = int(uc.get("tg") or 1025)
        uid = uc.get("dmr_id") or 0
        stream = active_streams_by_tg.get(utg)
        is_talking = bool(uc.get("is_transmitting") or stream)
        talker_call = ucall if uc.get("is_transmitting") else (stream.get("callsign", "") if stream else "")
        talk_dur = stream.get("duration_sec", 2) if stream else (2 if is_talking else 0)
        unified_nodes.append({
            "id": f"dvs_{uc.get('ip')}_{uc.get('port')}",
            "callsign": ucall,
            "category": "DVSwitch",
            "sourceType": "Network",
            "bridgeLabel": f"DVSwitch Mobile (ID: {uid or 'USRP'})",
            "hostLabel": f"{uc.get('ip')}:{uc.get('port')}",
            "routeFrom": f"USRP 32345",
            "routeTo": f"TG {utg} TS2",
            "tg": utg,
            "slot": 2,
            "monitoredTGs": [utg],
            "isTalking": is_talking,
            "talkerCallsign": talker_call,
            "talkingDurationSec": talk_dur,
            "connected": True,
            "sysop": f"DVSWITCH MOBILE · {ucall}",
            "sw": "DVSwitch_USRP Isolated"
        })

    # 6. Connected Local MMDVM Hotspots
    for hs in core_hotspots:
        hcall = str(hs.get("callsign") or "HOTSPOT").upper()
        hid = hs.get("dmr_id") or 0
        unified_nodes.append({
            "id": f"hs_{hs.get('addr', hcall)}",
            "callsign": hcall,
            "category": "Hotspot",
            "sourceType": "Network",
            "bridgeLabel": f"MMDVM RF Hotspot ({hid})",
            "hostLabel": str(hs.get("addr", "62031")),
            "routeFrom": "RF Hotspot",
            "routeTo": "DMR Master 62031",
            "tg": 1025,
            "slot": 2,
            "monitoredTGs": [510, 1025, 5109],
            "isTalking": False,
            "talkerCallsign": "",
            "talkingDurationSec": 0,
            "connected": hs.get("state") == "CONNECTED",
            "sysop": f"RF HOTSPOT · {hcall}",
            "sw": "MMDVM_Host"
        })

    # 7. Connected Zello Channel Bridge Node
    z_cfg = read_config_file().get("zello_config", {})
    if z_cfg.get("enabled", True):
        z_call = str(z_cfg.get("bot_callsign") or "NINJA-ZELLO").upper()
        z_tg = int(z_cfg.get("target_tg") or 510)
        z_slot = int(z_cfg.get("slot") or 2)
        z_channel = str(z_cfg.get("channel") or "NINJARADIOZL")
        stream = active_streams_by_tg.get(str(z_tg)) or active_streams_by_tg.get(z_tg)
        is_talking = bool(stream)
        talker_call = stream.get("callsign", "") if stream else ""
        talk_dur = stream.get("duration_sec", 2) if stream else 0
        key_file = get_zello_key_file()
        has_key = os.path.exists(key_file) and os.path.getsize(key_file) > 100

        unified_nodes.append({
            "id": f"zello_{z_call}",
            "callsign": z_call,
            "category": "Zello",
            "sourceType": "Network",
            "bridgeLabel": f"Zello Channel '{z_channel}'",
            "hostLabel": "wss://zello.io/ws",
            "routeFrom": f"Zello ({z_channel})",
            "routeTo": f"DMR/SVX TG {z_tg}",
            "tg": z_tg,
            "slot": z_slot,
            "monitoredTGs": [z_tg],
            "isTalking": is_talking,
            "talkerCallsign": talker_call,
            "talkingDurationSec": talk_dur,
            "connected": has_key,
            "sysop": f"ZELLO GATEWAY · {z_channel}",
            "sw": "Zello Channel API"
        })

    # 8. Connected TeamSpeak 3 Voice Server & External Cross-Server Bots
    try:
        from backend.app.services.teamspeak_service import get_teamspeak_telemetry
        ts3_telemetry = await get_teamspeak_telemetry()
        ts_cfg_all = read_config_file()
        ts_rooms_cfg = ts_cfg_all.get("teamspeak_rooms", DEFAULT_TEAMSPEAK_ROOMS)
        ts_bots_cfg = ts_cfg_all.get("teamspeak_bots", DEFAULT_TEAMSPEAK_BOTS)

        # Dynamically collect all internal monitored TGs from rooms and local bots
        ts_monitored_tgs = []
        for r in ts_rooms_cfg:
            rtg = r.get("tg") if r.get("tg") is not None else r.get("targetTg")
            if rtg:
                try:
                    rtg_int = int(rtg)
                    if rtg_int > 0 and rtg_int not in ts_monitored_tgs:
                        ts_monitored_tgs.append(rtg_int)
                except Exception:
                    pass

        local_ts_bots = []
        external_ts_bots = []
        for b in ts_bots_cfg:
            b_srv = str(b.get("server") or "127.0.0.1:9987").strip()
            is_local_srv = (
                b_srv.startswith("127.0.0.1")
                or b_srv.startswith("localhost")
                or b_srv.startswith("teamspeak-server")
                or not b_srv
            )
            if is_local_srv:
                local_ts_bots.append(b)
                btg = b.get("tg")
                if btg:
                    try:
                        btg_int = int(btg)
                        if btg_int > 0 and btg_int not in ts_monitored_tgs:
                            ts_monitored_tgs.append(btg_int)
                    except Exception:
                        pass
            else:
                external_ts_bots.append(b)

        if not ts_monitored_tgs:
            ts_monitored_tgs = [1025, 510990, 510, 5109]

        if ts3_telemetry.get("online"):
            req_host = (request.headers.get("x-forwarded-host") or request.headers.get("host") or "").split(":")[0].strip()
            ext_ip = os.getenv("EXTERNAL_IP", "").strip() or (req_host if req_host and req_host not in ("127.0.0.1", "localhost", "backend") else "127.0.0.1")
            tot_clients = ts3_telemetry.get("total_clients", 0)
            channels_cnt = len(ts3_telemetry.get("channels", []))

            active_ts_stream = None
            active_ts_tg = 0
            for t in ts_monitored_tgs:
                st = active_streams_by_tg.get(t) or active_streams_by_tg.get(str(t))
                if st:
                    active_ts_stream = st
                    active_ts_tg = t
                    break

            is_ts_talking = bool(active_ts_stream)
            ts_talker = active_ts_stream.get("callsign", "") if active_ts_stream else ""
            ts_dur = active_ts_stream.get("duration_sec", 2) if active_ts_stream else 0
            local_bot_names = ", ".join([str(b.get("name", "")).upper() for b in local_ts_bots if b.get("name")])

            unified_nodes.append({
                "id": "ts3_server",
                "callsign": "NINJA-TS3",
                "category": "TeamSpeak",
                "sourceType": "Network",
                "bridgeLabel": f"TeamSpeak 3 Server ({channels_cnt} Rooms)",
                "hostLabel": f"{ext_ip}:9987",
                "routeFrom": "TS3 Multi-Room",
                "routeTo": f"DMR TG {active_ts_tg} (Active)" if is_ts_talking else "DMR/SVX Multi-TG Dynamic",
                "tg": active_ts_tg if active_ts_tg > 0 else (ts_monitored_tgs[0] if ts_monitored_tgs else 1025),
                "slot": 2,
                "monitoredTGs": ts_monitored_tgs,
                "isTalking": is_ts_talking,
                "talkerCallsign": ts_talker,
                "talkingDurationSec": ts_dur,
                "connected": True,
                "sysop": f"TEAMSPEAK 3 · {tot_clients} Users ({local_bot_names})" if local_bot_names else f"TEAMSPEAK 3 · {tot_clients} Users Online",
                "sw": "TeamSpeak 3 (Anti Phone-Home)"
            })

        # 8b. External TeamSpeak 3 Crosslink Bot Clients
        for eb in external_ts_bots:
            eb_name = str(eb.get("name") or "TS3-EXT").strip().upper()
            eb_chan = str(eb.get("channel") or "Default").strip()
            eb_srv = str(eb.get("server") or "").strip()
            eb_tg = int(eb.get("tg") or 510)
            eb_slot = int(eb.get("slot") or 2)
            eb_svx = str(eb.get("svx_bot") or "").strip()
            eb_stream = active_streams_by_tg.get(eb_tg) or active_streams_by_tg.get(str(eb_tg))
            eb_talking = bool(eb_stream)
            eb_talker = eb_stream.get("callsign", "") if eb_stream else ""
            eb_dur = eb_stream.get("duration_sec", 2) if eb_stream else 0

            matching_local = [str(lb.get("name", "")).upper() for lb in local_ts_bots if int(lb.get("tg") or 0) == eb_tg]
            cross_targets = []
            if eb_svx:
                cross_targets.append(f"SVX:{eb_svx}")
            if matching_local:
                cross_targets.append(f"TS3:{','.join(matching_local)}")
            cross_targets.append(f"TG {eb_tg}")
            route_to_label = " + ".join(cross_targets)

            unified_nodes.append({
                "id": f"ts3_ext_{eb_name}",
                "callsign": eb_name,
                "category": "TeamSpeak",
                "sourceType": "Network",
                "bridgeLabel": f"Ext TS3 Room '{eb_chan}'",
                "hostLabel": eb_srv,
                "routeFrom": f"Ext TS3 ({eb_chan})",
                "routeTo": route_to_label,
                "tg": eb_tg,
                "slot": eb_slot,
                "monitoredTGs": [eb_tg],
                "isTalking": eb_talking,
                "talkerCallsign": eb_talker,
                "talkingDurationSec": eb_dur,
                "connected": True,
                "sysop": f"EXT TS3 CLIENT · Cross ➔ {route_to_label}",
                "sw": f"HoneyBBQ Client ({eb_srv})"
            })
    except Exception as e:
        logger.debug(f"Note loading TS3 telemetry: {e}")

    mappings = DEFAULT_SVXLINK_ROOMS
    config_path = "/app/data/config.json"
    if os.path.exists(config_path):
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                cfg = json.load(f)
                if "svxlink_rooms" in cfg and isinstance(cfg["svxlink_rooms"], list) and len(cfg["svxlink_rooms"]) > 0:
                    mappings = cfg["svxlink_rooms"]
        except Exception:
            pass

    return {
        "online": tcp_connected,
        "server_host": svx_host,
        "server_port": svx_port,
        "auth_key": svx_auth_key,
        "protocol": "SvxReflector V2 (Opus / Multi-TG Switchboard)",
        "server_version": "19.09.2-Debian",
        "tcp_status": "OPEN" if tcp_connected else "CLOSED",
        "udp_status": "ACTIVE" if tcp_connected else "UNREACHABLE",
        "connected_nodes": len(unified_nodes),
        "reflector_nodes": reflector_nodes,
        "unified_nodes": unified_nodes,
        "active_streams_by_tg": active_streams_by_tg,
        "active_bots": active_bots,
        "bridge_bot": {
            "name": "NinjaRadio-SvxBridge",
            "status": "RUNNING" if tcp_connected else "IDLE",
            "audio_transcoding": "PCM 8kHz (Direct DMR) <-> PCM 16kHz / Opus (SvxReflector)",
            "gateway_port": 12348
        },
        "channels": mappings
    }


@router.get("/svxlink/channels")
async def get_svxlink_channels():
    """Returns configured SvxReflector talkgroup rooms."""
    import httpx
    import json
    import os

    try:
        async with httpx.AsyncClient(timeout=3.0) as client:
            resp = await client.get("http://svxlink-bridge:12349/channels")
            if resp.status_code == 200:
                data = resp.json()
                chs = data.get("channels", [])
                if isinstance(chs, list) and len(chs) > 0:
                    return {"status": "SUCCESS", "source": "svxlink_live", "channels": chs}
    except Exception:
        pass

    config_path = "/app/data/config.json"
    if os.path.exists(config_path):
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                data = json.load(f)
                if "svxlink_rooms" in data and isinstance(data["svxlink_rooms"], list) and len(data["svxlink_rooms"]) > 0:
                    return {"status": "SUCCESS", "source": "config", "channels": data["svxlink_rooms"]}
        except Exception:
            pass

    return {"status": "SUCCESS", "source": "default", "channels": DEFAULT_SVXLINK_ROOMS}


@router.post("/svxlink/channels")
async def save_svxlink_channels(payload: dict):
    """Saves user-defined SvxReflector room to DMR talkgroup mappings and synchronizes bots."""
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
                "id": ch.get("id") or f"svx_{name}",
                "name": name,
                "channel": name,
                "targetTg": tg,
                "tg": tg,
                "slot": slot,
                "description": desc or f"SvxReflector TG {tg} (Slot {slot})",
                "isActive": True
            })
        except (ValueError, TypeError):
            continue

    if not cleaned_channels:
        raise HTTPException(status_code=400, detail="No valid channels provided")

    config_path = "/app/data/config.json"
    data = {}
    if os.path.exists(config_path):
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                data = json.load(f)
        except Exception:
            data = {}

    data["svxlink_rooms"] = cleaned_channels
    try:
        with open(config_path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to save config: {e}")

    try:
        reload_marker = "/app/data/.svxlink_reload"
        with open(reload_marker, "w") as rf:
            rf.write(str(time.time()))
    except Exception:
        pass

    try:
        async with httpx.AsyncClient(timeout=8.0) as client:
            await client.post("http://svxlink-bridge:12349/sync")
    except Exception:
        pass

    return {
        "status": "SUCCESS",
        "message": f"Saved {len(cleaned_channels)} SvxReflector Talkgroup rooms.",
        "channels": cleaned_channels
    }


@router.delete("/svxlink/channels/{channel_name}")
async def delete_svxlink_channel(channel_name: str):
    """Deletes an SvxReflector talkgroup room and stops its assigned bot."""
    import httpx
    import json
    import os

    clean_name = channel_name.strip()
    config_path = "/app/data/config.json"
    remaining = []
    if os.path.exists(config_path):
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                data = json.load(f)
            rooms = [r for r in data.get("svxlink_rooms", []) if (r.get("name") or r.get("channel")) != clean_name]
            bots = [b for b in data.get("svxlink_bots", []) if b.get("channel") != clean_name]
            data["svxlink_rooms"] = rooms
            data["svxlink_bots"] = bots
            remaining = rooms
            with open(config_path, "w", encoding="utf-8") as f:
                json.dump(data, f, indent=2)
        except Exception as e:
            raise HTTPException(status_code=500, detail=f"Failed to update config: {e}")

    try:
        reload_marker = "/app/data/.svxlink_reload"
        with open(reload_marker, "w") as rf:
            rf.write(str(time.time()))
    except Exception:
        pass

    try:
        async with httpx.AsyncClient(timeout=8.0) as client:
            await client.post("http://svxlink-bridge:12349/sync")
    except Exception:
        pass

    return {"status": "SUCCESS", "message": f"SvxReflector Room '{clean_name}' removed", "channels": remaining}


@router.get("/svxlink/bots")
async def get_svxlink_bots():
    """Returns the list of active SvxLink Multi-TG bots and their assigned talkgroups."""
    import httpx
    import json
    import os

    live_map = {}
    try:
        async with httpx.AsyncClient(timeout=2.5) as client:
            resp = await client.get("http://svxlink-bridge:12349/status")
            if resp.status_code == 200:
                for lb in resp.json().get("active_bots", []):
                    live_map[lb.get("name", "").upper()] = lb
    except Exception:
        pass

    bots = DEFAULT_SVXLINK_BOTS
    config_path = "/app/data/config.json"
    if os.path.exists(config_path):
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                data = json.load(f)
                if "svxlink_bots" in data and isinstance(data["svxlink_bots"], list) and len(data["svxlink_bots"]) > 0:
                    bots = data["svxlink_bots"]
        except Exception:
            pass

    active_streams_by_tg = {}
    for _, sinfo in list(ACTIVE_STREAMS.items()):
        stg = int(sinfo.get("talkgroup") or 0)
        if stg > 0:
            active_streams_by_tg[stg] = sinfo.get("callsign", "")
    if manager.current_tx:
        ctg = int(manager.current_tx.get("talkgroup") or 0)
        if ctg > 0 and ctg not in active_streams_by_tg:
            active_streams_by_tg[ctg] = manager.current_tx.get("callsign", "")

    enriched = []
    for b in bots:
        b_copy = dict(b)
        b_up = str(b_copy.get("name", "")).upper()
        b_tg = int(b_copy.get("tg", 0))
        live_info = live_map.get(b_up, {})
        b_copy["connected"] = live_info.get("connected", False) if b_up in live_map else False
        live_stream_call = active_streams_by_tg.get(b_tg)
        b_copy["active_talker"] = live_stream_call or live_info.get("active_talker")
        b_copy["status"] = "CONNECTED" if b_copy["connected"] else "CONNECTING"
        enriched.append(b_copy)

    return {"bots": enriched}


@router.post("/svxlink/bots")
async def create_or_update_svxlink_bot(payload: dict):
    """Creates a new SvxLink Multi-TG bot or updates an existing bot."""
    import httpx
    import json
    import os
    import re

    raw_name = str(payload.get("name", "")).strip().upper()
    name = re.sub(r"[^A-Z0-9\-]", "", raw_name)[:15]
    original_name = str(payload.get("original_name", "")).strip().upper()
    channel = str(payload.get("channel", "")).strip().replace(" ", "-")
    if not name or not channel:
        raise HTTPException(status_code=400, detail="Bot Callsign/Name and target room are required")
    try:
        tg = int(payload.get("tg", 0))
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

    bots = list(data.get("svxlink_bots", DEFAULT_SVXLINK_BOTS))
    existing_idx = None
    if original_name:
        existing_idx = next((i for i, b in enumerate(bots) if str(b.get("name", "")).upper() == original_name), None)
    if existing_idx is None:
        existing_idx = next((i for i, b in enumerate(bots) if str(b.get("name", "")).upper() == name), None)

    bot_entry = {
        "name": name,
        "channel": channel,
        "tg": tg,
        "slot": slot,
        "echolink_enabled": bool(payload.get("echolink_enabled", False)),
        "echolink_callsign": str(payload.get("echolink_callsign", "")).strip().upper(),
        "echolink_password": str(payload.get("echolink_password", "")).strip(),
        "status": "STATIONED"
    }
    if existing_idx is not None:
        bots[existing_idx] = bot_entry
    else:
        bots.append(bot_entry)

    rooms = list(data.get("svxlink_rooms", DEFAULT_SVXLINK_ROOMS))
    room_exists = any((r.get("name") == channel or r.get("channel") == channel) for r in rooms)
    if not room_exists:
        rooms.append({
            "id": f"svx_{channel}",
            "name": channel,
            "channel": channel,
            "targetTg": tg,
            "tg": tg,
            "slot": slot,
            "description": f"SvxReflector TG {tg} (Slot {slot})",
            "isActive": True
        })
        data["svxlink_rooms"] = rooms

    data["svxlink_bots"] = bots
    try:
        with open(config_path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to save SvxLink bot: {e}")

    try:
        reload_marker = "/app/data/.svxlink_reload"
        with open(reload_marker, "w") as rf:
            rf.write(str(time.time()))
    except Exception:
        pass

    try:
        async with httpx.AsyncClient(timeout=8.0) as client:
            await client.post("http://svxlink-bridge:12349/sync")
    except Exception:
        pass

    return {"status": "SUCCESS", "message": f"SvxLink Bot '{name}' stationed on TG {tg}", "bot": bot_entry}


@router.delete("/svxlink/bots/{bot_name}")
async def delete_svxlink_bot(bot_name: str):
    """Removes an SvxLink bot from configuration and terminates its daemon."""
    import httpx
    import json
    import os

    clean_name = bot_name.strip().upper()
    config_path = "/app/data/config.json"
    data = {}
    if os.path.exists(config_path):
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                data = json.load(f)
        except Exception:
            data = {}

    bots = [b for b in data.get("svxlink_bots", DEFAULT_SVXLINK_BOTS) if str(b.get("name", "")).upper() != clean_name]
    data["svxlink_bots"] = bots
    try:
        with open(config_path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to delete SvxLink bot: {e}")

    try:
        reload_marker = "/app/data/.svxlink_reload"
        with open(reload_marker, "w") as rf:
            rf.write(str(time.time()))
    except Exception:
        pass

    try:
        async with httpx.AsyncClient(timeout=8.0) as client:
            await client.post("http://svxlink-bridge:12349/sync")
    except Exception:
        pass

    return {"status": "SUCCESS", "message": f"SvxLink Bot '{clean_name}' removed"}


@router.get("/svxlink/logs")
async def get_svxlink_logs():
    """Returns live logs and bot states from SvxLink Bridge."""
    import httpx
    import os
    try:
        async with httpx.AsyncClient(timeout=3.5) as client:
            resp = await client.get("http://svxlink-bridge:12349/logs")
            if resp.status_code == 200:
                return resp.json()
    except Exception:
        pass

    log_path = "/app/data/svxlink_bridge.log"
    logs = []
    if os.path.exists(log_path):
        try:
            with open(log_path, "r", encoding="utf-8") as f:
                logs = [l.strip() for l in f.readlines()[-100:]]
        except Exception as e:
            logs = [f"Error reading log: {e}"]
    return {"status": "FALLBACK", "recent_logs": logs}





# ----------------- Zello Channel Bridge Management -----------------
ZELLO_KEY_PATHS = [
    "/app/data/zello_private.key",
    "./data/zello_private.key",
    os.path.join(os.path.dirname(__file__), "../../data/zello_private.key")
]

def get_zello_key_file() -> str:
    for p in ZELLO_KEY_PATHS:
        if os.path.exists(p):
            return p
    if os.path.exists("/app/data"):
        return "/app/data/zello_private.key"
    local_data = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../data"))
    os.makedirs(local_data, exist_ok=True)
    return os.path.join(local_data, "zello_private.key")

@router.get("/zello/config")
async def get_zello_config():
    """Returns Zello Bridge configuration without exposing the private key directly."""
    cfg = read_config_file()
    z_cfg = cfg.get("zello_config", {})
    key_file = get_zello_key_file()
    has_key = os.path.exists(key_file) and os.path.getsize(key_file) > 100
    
    return {
        "status": "SUCCESS",
        "enabled": z_cfg.get("enabled", True),
        "channel": z_cfg.get("channel", ""),
        "username": z_cfg.get("username", ""),
        "has_password": bool(z_cfg.get("password")),
        "issuer": z_cfg.get("issuer", ""),
        "target_tg": z_cfg.get("target_tg", 0),
        "slot": z_cfg.get("slot", 2),
        "bot_callsign": z_cfg.get("bot_callsign", "NINJA-ZELLO"),
        "dev_token": z_cfg.get("dev_token", ""),
        "user_mappings": z_cfg.get("user_mappings", {}),
        "has_key": has_key,
        "key_path": key_file if has_key else None
    }

@router.post("/zello/config")
async def save_zello_config(data: dict):
    """Updates Zello configuration and optionally saves the RSA private key securely."""
    import json
    cfg = read_config_file()
    z_cfg = cfg.get("zello_config", {})
    if not isinstance(z_cfg, dict):
        z_cfg = {}
    
    if "enabled" in data:
        z_cfg["enabled"] = bool(data["enabled"])
    if "channel" in data and data["channel"]:
        z_cfg["channel"] = str(data["channel"]).strip()
    if "username" in data:
        z_cfg["username"] = str(data["username"]).strip()
    if "password" in data and data["password"]:
        z_cfg["password"] = str(data["password"])
    if "dev_token" in data:
        z_cfg["dev_token"] = str(data["dev_token"]).strip()
    if "issuer" in data and data["issuer"]:
        z_cfg["issuer"] = str(data["issuer"]).strip()
    if "target_tg" in data:
        try:
            z_cfg["target_tg"] = int(data["target_tg"])
        except (ValueError, TypeError):
            pass
    if "slot" in data:
        try:
            z_cfg["slot"] = int(data["slot"])
        except (ValueError, TypeError):
            pass
    if "bot_callsign" in data and data["bot_callsign"]:
        z_cfg["bot_callsign"] = str(data["bot_callsign"]).strip().upper()
    if "user_mappings" in data:
        raw_map = data["user_mappings"]
        clean_map = {}
        if isinstance(raw_map, dict):
            for k, v in raw_map.items():
                if k and v:
                    clean_map[str(k).strip().lower()] = str(v).strip().upper()
        elif isinstance(raw_map, str):
            for line in raw_map.splitlines():
                line = line.strip()
                if ":" in line:
                    k, v = line.split(":", 1)
                    if k.strip() and v.strip():
                        clean_map[k.strip().lower()] = v.strip().upper()
                elif "=" in line:
                    k, v = line.split("=", 1)
                    if k.strip() and v.strip():
                        clean_map[k.strip().lower()] = v.strip().upper()
        z_cfg["user_mappings"] = clean_map
        
    private_key_pem = data.get("private_key")
    if private_key_pem and isinstance(private_key_pem, str) and ("PRIVATE KEY" in private_key_pem):
        key_file = get_zello_key_file()
        try:
            os.makedirs(os.path.dirname(key_file), exist_ok=True)
            with open(key_file, "w", encoding="utf-8") as f:
                f.write(private_key_pem.strip() + "\n")
            try:
                os.chmod(key_file, 0o600)
            except Exception:
                pass
            z_cfg["has_key"] = True
        except Exception as e:
            logger.error(f"Failed to write zello private key: {e}")
            
    cfg["zello_config"] = z_cfg
    config_path = "/app/data/config.json"
    if not os.path.exists("/app/data"):
        config_path = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../data/config.json"))
        os.makedirs(os.path.dirname(config_path), exist_ok=True)
        
    try:
        with open(config_path, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2)
    except Exception as e:
        logger.error(f"Could not persist config.json: {e}")

    try:
        import httpx
        async with httpx.AsyncClient(timeout=3.0) as client:
            await client.post("http://zello-bridge:12350/sync")
    except Exception:
        pass

    key_file = get_zello_key_file()
    has_key = os.path.exists(key_file) and os.path.getsize(key_file) > 100

    return {
        "status": "SUCCESS",
        "message": "Zello configuration updated successfully",
        "config": {
            "enabled": z_cfg.get("enabled", True),
            "channel": z_cfg.get("channel", "NINJARADIOZL"),
            "issuer": z_cfg.get("issuer"),
            "target_tg": z_cfg.get("target_tg", 510),
            "slot": z_cfg.get("slot", 2),
            "bot_callsign": z_cfg.get("bot_callsign", "NINJA-ZELLO"),
            "has_key": has_key
        }
    }

@router.get("/zello/status")
async def get_zello_status():
    """Queries live state from zello-bridge daemon."""
    import httpx
    try:
        async with httpx.AsyncClient(timeout=2.0) as client:
            resp = await client.get("http://zello-bridge:12350/status")
            if resp.status_code == 200:
                return resp.json()
    except Exception:
        pass
    
    cfg = read_config_file()
    z_cfg = cfg.get("zello_config", {})
    key_file = get_zello_key_file()
    has_key = os.path.exists(key_file) and os.path.getsize(key_file) > 100
    return {
        "status": "ONLINE" if (z_cfg.get("enabled", True) and has_key) else "STANDBY",
        "enabled": z_cfg.get("enabled", True),
        "channel": z_cfg.get("channel", "NINJARADIOZL"),
        "target_tg": z_cfg.get("target_tg", 510),
        "slot": z_cfg.get("slot", 2),
        "bot_callsign": z_cfg.get("bot_callsign", "NINJA-ZELLO"),
        "has_key": has_key,
        "websocket": "CONNECTED" if (z_cfg.get("enabled", True) and has_key) else "STANDBY",
        "details": "Zello Channel API Ready" if has_key else "Private key needed"
    }

@router.get("/zello/logs")
async def get_zello_logs():
    """Returns recent log lines from zello bridge."""
    import httpx
    try:
        async with httpx.AsyncClient(timeout=2.0) as client:
            resp = await client.get("http://zello-bridge:12350/logs")
            if resp.status_code == 200:
                data = resp.json()
                if data.get("recent_logs"):
                    return data
    except Exception:
        pass

    import os
    for p in ["/app/data/zello_bridge.log", "./data/zello_bridge.log"]:
        if os.path.exists(p):
            try:
                with open(p, "r", encoding="utf-8") as f:
                    lines = [l.strip() for l in f.readlines() if l.strip()]
                    if lines:
                        return {"status": "SUCCESS", "recent_logs": lines[-100:]}
            except Exception as e:
                return {"status": "ERROR", "recent_logs": [f"Error reading log: {e}"]}
    return {"status": "STANDBY", "recent_logs": ["[Zello-Bridge] Daemon ready. Listening for Channel WebSocket traffic."]}


# ----------------- Self-Care Registration & Admin Approval -----------------

async def _get_current_selfcare_user(
    authorization: Optional[str] = Header(None),
    session: AsyncSession = Depends(get_db)
) -> UserAccount:
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(status_code=401, detail="Token autentikasi diperlukan.")
    payload = verify_session_token(authorization[7:].strip())
    if not payload:
        raise HTTPException(status_code=401, detail="Sesi login tidak valid atau kedaluwarsa.")
    res = await session.execute(select(UserAccount).where(UserAccount.id == payload.get("uid")))
    user = res.scalar_one_or_none()
    if not user:
        raise HTTPException(status_code=401, detail="Akun pengguna tidak ditemukan.")
    return user


def _require_admin_pin(x_admin_pin: Optional[str] = Header(None, alias="X-Admin-Pin")):
    admin_pin = get_admin_pin()
    supplied = (x_admin_pin or "").strip()
    if not supplied or supplied != admin_pin:
        raise HTTPException(status_code=403, detail="PIN Admin tidak valid.")


@router.post("/selfcare/register")
async def selfcare_register(payload: SelfCareRegisterRequest, session: AsyncSession = Depends(get_db)):
    user, err = await SelfCareService.register_user(session, payload)
    if err or not user:
        raise HTTPException(status_code=400, detail=err or "Gagal mendaftar.")
    token = create_session_token(user)
    return {
        "status": "SUCCESS",
        "message": "Pendaftaran berhasil dikirim! Status akun Anda PENDING (RX Only) menunggu verifikasi Admin.",
        "token": token,
        "user": sanitize_user_response(user),
    }


@router.post("/selfcare/login")
async def selfcare_login(payload: SelfCareLoginRequest, session: AsyncSession = Depends(get_db)):
    user = await SelfCareService.authenticate_user(session, payload.identifier, payload.password)
    if not user:
        raise HTTPException(status_code=401, detail="Callsign/DMR ID atau password salah.")
    token = create_session_token(user)
    return {
        "status": "SUCCESS",
        "token": token,
        "user": sanitize_user_response(user),
    }


@router.get("/selfcare/me", response_model=UserAccountPublicResponse)
async def selfcare_me(user: UserAccount = Depends(_get_current_selfcare_user)):
    return sanitize_user_response(user)


@router.post("/selfcare/request-services", response_model=UserAccountPublicResponse)
async def selfcare_request_services(
    payload: SelfCareServiceRequest,
    user: UserAccount = Depends(_get_current_selfcare_user),
    session: AsyncSession = Depends(get_db)
):
    for field in ["req_web", "req_dmr", "req_svxlink", "req_mumble", "req_dvswitch", "req_zello", "req_teamspeak"]:
        val = getattr(payload, field)
        if val is not None:
            setattr(user, field, bool(val))
    if payload.zello_username is not None:
        user.zello_username = payload.zello_username.strip()

    # If the user is already APPROVED, automatically sync their allowed services and generate missing credentials
    if user.status == "APPROVED":
        for svc in ["dmr", "svxlink", "mumble", "dvswitch", "zello", "teamspeak"]:
            req_val = getattr(user, f"req_{svc}")
            setattr(user, f"allow_{svc}", req_val)
            if svc in ["dmr", "svxlink", "mumble", "teamspeak"] and req_val and not getattr(user, f"{svc}_password"):
                setattr(user, f"{svc}_password", generate_protocol_password(6))
        await SelfCareService._sync_callbook_entry(session, user)
        trigger_protocol_reload()

    await session.commit()
    await session.refresh(user)
    return sanitize_user_response(user)


@router.post("/selfcare/dvswitch/whitelist-ip", response_model=UserAccountPublicResponse)
async def selfcare_whitelist_dvswitch_ip(
    request: Request,
    user: UserAccount = Depends(_get_current_selfcare_user),
    session: AsyncSession = Depends(get_db)
):
    if user.status != "APPROVED" or not user.allow_dvswitch:
        raise HTTPException(status_code=403, detail="Layanan DVSwitch belum disetujui Admin untuk akun ini.")
    forwarded = request.headers.get("x-forwarded-for", "").split(",")[0].strip()
    client_ip = forwarded or (request.client.host if request.client else "")
    if not client_ip:
        raise HTTPException(status_code=400, detail="Gagal mendeteksi IP klien.")
    user.dvswitch_allowed_ip = client_ip
    await session.commit()
    await session.refresh(user)
    return sanitize_user_response(user)


@router.post("/selfcare/rotate-password", response_model=UserAccountPublicResponse)
async def selfcare_rotate_password(
    payload: SelfCareRotatePasswordRequest,
    user: UserAccount = Depends(_get_current_selfcare_user),
    session: AsyncSession = Depends(get_db)
):
    if user.status != "APPROVED":
        raise HTTPException(status_code=403, detail="Akun harus berstatus APPROVED untuk mengatur ulang password protokol.")
    proto = payload.protocol.strip().lower()
    clean_pwd = payload.new_password.strip() if payload.new_password else ""
    if clean_pwd:
        if len(clean_pwd) < 4 or len(clean_pwd) > 32:
            raise HTTPException(status_code=400, detail="Password minimal 4 karakter dan maksimal 32 karakter.")
        new_pwd = clean_pwd
    else:
        new_pwd = generate_protocol_password(6)

    if proto == "dmr" and user.allow_dmr:
        user.dmr_password = new_pwd
    elif proto == "svxlink" and user.allow_svxlink:
        user.svxlink_password = new_pwd
    elif proto == "mumble" and user.allow_mumble:
        user.mumble_password = new_pwd
    elif proto == "teamspeak" and user.allow_teamspeak:
        user.teamspeak_password = new_pwd
    else:
        raise HTTPException(status_code=400, detail="Protokol tidak valid atau belum disetujui untuk akun Anda.")

    await session.commit()
    await session.refresh(user)
    trigger_protocol_reload()
    return sanitize_user_response(user)


@router.get("/admin/registrations", response_model=List[UserAccountAdminResponse])
async def admin_list_registrations(
    status: Optional[str] = Query("ALL"),
    session: AsyncSession = Depends(get_db),
    _: None = Depends(_require_admin_pin)
):
    return await SelfCareService.list_registrations(session, status_filter=status)


@router.post("/admin/registrations/{user_id}/approve", response_model=UserAccountAdminResponse)
async def admin_approve_registration(
    user_id: int,
    payload: AdminApprovalRequest,
    session: AsyncSession = Depends(get_db),
    _: None = Depends(_require_admin_pin)
):
    user = await SelfCareService.approve_user(session, user_id, payload)
    if not user:
        raise HTTPException(status_code=404, detail="Pendaftaran tidak ditemukan.")
    asyncio.create_task(_notify_core_reload_subscribers())
    items = await SelfCareService.list_registrations(session, status_filter="ALL")
    for item in items:
        if item.id == user.id:
            return item
    raise HTTPException(status_code=500, detail="Gagal memuat ulang data user.")


@router.post("/admin/registrations/{user_id}/reject", response_model=UserAccountAdminResponse)
async def admin_reject_registration(
    user_id: int,
    payload: AdminRejectRequest,
    session: AsyncSession = Depends(get_db),
    _: None = Depends(_require_admin_pin)
):
    user = await SelfCareService.reject_or_suspend_user(session, user_id, "REJECTED", payload.reason or "")
    if not user:
        raise HTTPException(status_code=404, detail="Pendaftaran tidak ditemukan.")
    asyncio.create_task(_notify_core_reload_subscribers())
    items = await SelfCareService.list_registrations(session, status_filter="ALL")
    for item in items:
        if item.id == user.id:
            return item
    raise HTTPException(status_code=500, detail="Gagal memuat ulang data user.")


@router.post("/admin/registrations/{user_id}/suspend", response_model=UserAccountAdminResponse)
async def admin_suspend_registration(
    user_id: int,
    payload: AdminRejectRequest,
    session: AsyncSession = Depends(get_db),
    _: None = Depends(_require_admin_pin)
):
    user = await SelfCareService.reject_or_suspend_user(session, user_id, "SUSPENDED", payload.reason or "Akun dibekukan oleh Admin.")
    if not user:
        raise HTTPException(status_code=404, detail="Pendaftaran tidak ditemukan.")
    asyncio.create_task(_notify_core_reload_subscribers())
    items = await SelfCareService.list_registrations(session, status_filter="ALL")
    for item in items:
        if item.id == user.id:
            return item
    raise HTTPException(status_code=500, detail="Gagal memuat ulang data user.")


# ----------------- Callbook Auto-Sync URL Sources -----------------

@router.get("/callbook/sync-sources")
async def list_callbook_sync_sources(session: AsyncSession = Depends(get_db)):
    try:
        res = await session.execute(select(CallbookSyncSource).order_by(CallbookSyncSource.id.desc()))
        rows = res.scalars().all()
        return [
            {
                "id": r.id,
                "name": r.name,
                "url": r.url,
                "organization": r.organization,
                "default_tg": r.default_tg,
                "interval_hours": r.interval_hours,
                "enabled": r.enabled,
                "last_sync_at": r.last_sync_at.isoformat() if r.last_sync_at else None,
                "last_status": r.last_status,
                "last_count": r.last_count,
            }
            for r in rows
        ]
    except Exception as e:
        logger.error(f"Error querying CallbookSyncSource: {e}")
        return []


@router.post("/callbook/sync-sources")
async def upsert_callbook_sync_source(
    payload: dict,
    session: AsyncSession = Depends(get_db),
    _: None = Depends(_require_admin_pin)
):
    url = str(payload.get("url") or "").strip()
    if not (url.startswith("http://") or url.startswith("https://")):
        raise HTTPException(status_code=400, detail="URL harus diawali http:// atau https://")
    name = str(payload.get("name") or "External Callbook").strip()[:64]
    org = str(payload.get("organization") or "AUTO").strip().upper()[:32]
    default_tg = int(payload.get("default_tg") or 510)
    interval_hours = max(1, min(168, int(payload.get("interval_hours") or 12)))
    enabled = bool(payload.get("enabled", True))

    res = await session.execute(select(CallbookSyncSource).where(CallbookSyncSource.url == url))
    src = res.scalar_one_or_none()
    if src:
        src.name = name
        src.organization = org
        src.default_tg = default_tg
        src.interval_hours = interval_hours
        src.enabled = enabled
    else:
        src = CallbookSyncSource(
            name=name,
            url=url,
            organization=org,
            default_tg=default_tg,
            interval_hours=interval_hours,
            enabled=enabled,
        )
        session.add(src)
    await session.commit()
    await session.refresh(src)
    return {"status": "SUCCESS", "id": src.id}


@router.delete("/callbook/sync-sources/{source_id}")
async def delete_callbook_sync_source(
    source_id: int,
    session: AsyncSession = Depends(get_db),
    _: None = Depends(_require_admin_pin)
):
    res = await session.execute(select(CallbookSyncSource).where(CallbookSyncSource.id == source_id))
    src = res.scalar_one_or_none()
    if not src:
        raise HTTPException(status_code=404, detail="Sumber sync tidak ditemukan.")
    await session.delete(src)
    await session.commit()
    return {"status": "SUCCESS"}


@router.post("/callbook/sync-sources/{source_id}/run")
async def run_callbook_sync_source_now(
    source_id: int,
    session: AsyncSession = Depends(get_db),
    _: None = Depends(_require_admin_pin)
):
    res = await session.execute(select(CallbookSyncSource).where(CallbookSyncSource.id == source_id))
    src = res.scalar_one_or_none()
    if not src:
        raise HTTPException(status_code=404, detail="Sumber sync tidak ditemukan.")
    result = await CallbookService.sync_url_source(session, src)
    if result.get("success"):
        asyncio.create_task(_notify_core_reload_subscribers())
    return {
        "status": "SUCCESS" if result.get("success") else "ERROR",
        "last_status": src.last_status,
        "imported_count": result.get("imported_count", 0),
    }


@router.get("/teamspeak/telemetry")
async def get_teamspeak_telemetry_endpoint():
    """Returns live TeamSpeak 3 channels, online clients, and server status via ServerQuery."""
    try:
        from backend.app.services.teamspeak_service import get_teamspeak_telemetry
        return await get_teamspeak_telemetry()
    except Exception as e:
        return {"online": False, "error": str(e), "channels": [], "clients": []}


@router.post("/teamspeak/token/generate")
async def generate_teamspeak_token_endpoint():
    """Generates a fresh ServerAdmin privilege key dynamically from TS3 ServerQuery."""
    try:
        from backend.app.services.teamspeak_service import get_or_create_admin_token, _ts3_cache
        new_token = await get_or_create_admin_token(force_new=True)
        _ts3_cache["last_check"] = 0.0
        return {"status": "SUCCESS", "token": new_token}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/teamspeak/rooms")
async def get_teamspeak_rooms_endpoint():
    """Returns configured TS3 rooms merged with live ServerQuery telemetry (resolving live CIDs dynamically)."""
    cfg = read_config_file()
    saved_rooms = cfg.get("teamspeak_rooms", DEFAULT_TEAMSPEAK_ROOMS)
    try:
        from backend.app.services.teamspeak_service import get_teamspeak_telemetry
        telem = await get_teamspeak_telemetry()
        live_list = telem.get("channels", [])
        live_by_cid = {ch.get("cid"): ch for ch in live_list if ch.get("cid")}
        live_by_name = {str(ch.get("name", "")).strip().lower(): ch for ch in live_list if ch.get("name")}
        matched_cids = set()
        enriched = []
        for r in saved_rooms:
            r_copy = dict(r)
            r_name = str(r.get("name") or r.get("channel") or "").strip().lower()
            cid = r.get("cid")
            # Match by channel name first so CIDs automatically adapt on any VPS/TS3 server
            l_info = live_by_name.get(r_name) or (live_by_cid.get(cid) if cid in live_by_cid else None)
            if l_info:
                real_cid = l_info.get("cid", cid)
                r_copy["cid"] = real_cid
                r_copy["name"] = l_info.get("name", r_copy.get("name", ""))
                r_copy["channel"] = r_copy["name"]
                r_copy["topic"] = l_info.get("topic", r_copy.get("description", ""))
                r_copy["total_clients"] = l_info.get("total_clients", 0)
                r_copy["clients"] = l_info.get("clients", [])
                if real_cid:
                    matched_cids.add(real_cid)
            enriched.append(r_copy)
        # Also include any additional permanent channels present on the live TS3 server (excluding Default Channel CID 1)
        for ch in live_list:
            ch_cid = ch.get("cid", 0)
            if ch_cid > 1 and ch_cid not in matched_cids:
                ch_name = str(ch.get("name", f"Channel {ch_cid}"))
                m_tg = re.search(r"\b(\d{3,7})\b", ch_name + " " + str(ch.get("topic", "")))
                guessed_tg = int(m_tg.group(1)) if m_tg else 1025
                enriched.append({
                    "cid": ch_cid,
                    "id": f"ts3_live_{ch_cid}",
                    "name": ch_name,
                    "channel": ch_name,
                    "targetTg": guessed_tg,
                    "tg": guessed_tg,
                    "slot": 1 if guessed_tg in (510, 5108, 91) else 2,
                    "description": ch.get("topic", ""),
                    "topic": ch.get("topic", ""),
                    "total_clients": ch.get("total_clients", 0),
                    "clients": ch.get("clients", []),
                    "isActive": True
                })
        return {"status": "SUCCESS", "rooms": enriched}
    except Exception:
        return {"status": "SUCCESS", "rooms": saved_rooms}


@router.post("/teamspeak/rooms")
async def save_teamspeak_room_endpoint(payload: dict):
    """Creates or updates a TeamSpeak 3 channel and saves mapping to config."""
    import json
    from backend.app.services.teamspeak_service import create_ts3_channel, edit_ts3_channel
    cfg = read_config_file()
    rooms = list(cfg.get("teamspeak_rooms", DEFAULT_TEAMSPEAK_ROOMS))
    cid = payload.get("cid")
    name = (payload.get("name") or payload.get("channel") or "").strip()
    target_tg = int(payload.get("targetTg") or payload.get("tg") or 510)
    slot = int(payload.get("slot") or 2)
    topic = (payload.get("topic") or payload.get("description") or f"DMR TG {target_tg} Slot {slot}").strip()

    if not name:
        raise HTTPException(status_code=400, detail="Nama channel tidak boleh kosong.")

    if cid and int(cid) > 0:
        cid_val = int(cid)
        await edit_ts3_channel(cid_val, name, topic, target_tg, slot)
        updated = False
        for r in rooms:
            if r.get("cid") == cid_val:
                r["name"] = name
                r["channel"] = name
                r["targetTg"] = target_tg
                r["tg"] = target_tg
                r["slot"] = slot
                r["description"] = topic
                r["topic"] = topic
                updated = True
                break
        if not updated:
            rooms.append({"cid": cid_val, "id": f"ts3_{target_tg}", "name": name, "channel": name, "targetTg": target_tg, "tg": target_tg, "slot": slot, "description": topic, "isActive": True})
    else:
        new_cid = await create_ts3_channel(name, topic, target_tg, slot)
        rooms.append({
            "cid": new_cid or (len(rooms) + 10),
            "id": f"ts3_{target_tg}_{int(time.time())}",
            "name": name,
            "channel": name,
            "targetTg": target_tg,
            "tg": target_tg,
            "slot": slot,
            "description": topic,
            "isActive": True
        })

    cfg["teamspeak_rooms"] = rooms
    try:
        with open(CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2)
    except Exception as e:
        logger.error(f"Failed to save config.json: {e}")

    return {"status": "SUCCESS", "rooms": rooms}


@router.delete("/teamspeak/rooms/{cid}")
async def delete_teamspeak_room_endpoint(cid: int):
    """Deletes a TS3 channel from ServerQuery and removes from config."""
    import json
    from backend.app.services.teamspeak_service import delete_ts3_channel
    if cid <= 1:
        raise HTTPException(status_code=400, detail="Default channel tidak boleh dihapus.")
    await delete_ts3_channel(cid)
    cfg = read_config_file()
    rooms = [r for r in cfg.get("teamspeak_rooms", DEFAULT_TEAMSPEAK_ROOMS) if r.get("cid") != cid]
    cfg["teamspeak_rooms"] = rooms
    try:
        with open(CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2)
    except Exception as e:
        logger.error(f"Failed to update config.json: {e}")
    return {"status": "SUCCESS", "rooms": rooms}


@router.get("/teamspeak/bots")
async def get_teamspeak_bots_endpoint():
    """Returns configured TS3 audio bots."""
    cfg = read_config_file()
    bots = cfg.get("teamspeak_bots", DEFAULT_TEAMSPEAK_BOTS)
    return {"status": "SUCCESS", "bots": bots}


@router.post("/teamspeak/bots")
async def save_teamspeak_bot_endpoint(payload: dict):
    """Adds a new stationed bot to TeamSpeak 3."""
    import json
    cfg = read_config_file()
    bots = list(cfg.get("teamspeak_bots", DEFAULT_TEAMSPEAK_BOTS))
    name = (payload.get("name") or "").strip().upper()
    channel = (payload.get("channel") or "").strip()
    tg = int(payload.get("tg") or 510)
    slot = int(payload.get("slot") or 2)
    svx_bot = (payload.get("svx_bot") or "").strip()
    server = (payload.get("server") or "127.0.0.1:9987").strip()
    server_password = (payload.get("server_password") or "").strip()
    channel_password = (payload.get("channel_password") or "").strip()
    raw_static = payload.get("static_name", False)
    if isinstance(raw_static, str):
        static_name = raw_static.strip().lower() in ["true", "1", "yes"]
    else:
        static_name = bool(raw_static)
    if not name or not channel:
        raise HTTPException(status_code=400, detail="Nama bot dan channel harus diisi.")

    bots = [b for b in bots if (b.get("name") or "").strip().upper() != name]
    bot_entry = {
        "name": name,
        "channel": channel,
        "tg": tg,
        "slot": slot,
        "svx_bot": svx_bot,
        "server": server,
        "server_password": server_password,
        "channel_password": channel_password,
        "static_name": static_name,
        "status": "STATIONED"
    }
    bots.append(bot_entry)
    cfg["teamspeak_bots"] = bots
    try:
        with open(CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2)
    except Exception as e:
        logger.error(f"Failed to update config.json: {e}")
    return {"status": "SUCCESS", "bots": bots}


@router.delete("/teamspeak/bots/{bot_name}")
async def delete_teamspeak_bot_endpoint(bot_name: str):
    """Deletes a stationed bot from TeamSpeak 3."""
    import json
    cfg = read_config_file()
    bots = [b for b in cfg.get("teamspeak_bots", DEFAULT_TEAMSPEAK_BOTS) if b.get("name") != bot_name.strip().upper()]
    cfg["teamspeak_bots"] = bots
    try:
        with open(CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2)
    except Exception as e:
        logger.error(f"Failed to update config.json: {e}")
    return {"status": "SUCCESS", "bots": bots}


@router.post("/teamspeak/bots/{bot_name}/move")
async def move_teamspeak_bot_endpoint(bot_name: str, payload: dict):
    """Instantly moves an active TeamSpeak 3 bot to target channel and persists config."""
    import json, urllib.request, urllib.parse
    cfg = read_config_file()
    bots = list(cfg.get("teamspeak_bots", DEFAULT_TEAMSPEAK_BOTS))
    clean_name = bot_name.strip().upper()
    target_channel = (payload.get("channel") or "").strip()
    target_password = (payload.get("password") or "").strip()

    if not target_channel:
        raise HTTPException(status_code=400, detail="Target channel tidak boleh kosong.")

    matched_bot = None
    for b in bots:
        if (b.get("name") or "").strip().upper() == clean_name:
            b["channel"] = target_channel
            if target_password:
                b["channel_password"] = target_password
            matched_bot = b
            break

    if not matched_bot:
        raise HTTPException(status_code=404, detail=f"Bot '{bot_name}' tidak ditemukan.")

    # Save to config.json
    cfg["teamspeak_bots"] = bots
    try:
        with open(CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2)
    except Exception as e:
        logger.error(f"Failed to update config.json: {e}")

    # Dispatch live move command to TS3AudioBot Web API on localhost:58913
    api_result = "Config updated"
    try:
        list_url = "http://127.0.0.1:58913/api/bot/list"
        req = urllib.request.Request(list_url, headers={"User-Agent": "NinjaRadio-Backend"})
        resp = json.loads(urllib.request.urlopen(req, timeout=2).read().decode())

        target_bid = None
        for b_inst in resp:
            inst_name = str(b_inst.get("Name") or "").strip().upper()
            inst_id = b_inst.get("Id")
            if inst_name == clean_name or str(inst_id) == clean_name.lower():
                target_bid = inst_id
                break
        if target_bid is None and len(resp) == 1:
            target_bid = resp[0].get("Id", 0)

        if target_bid is not None:
            # If target_channel is a numeric CID like '24' or '/24', pass clean integer
            dest = target_channel.lstrip("/") if (target_channel.isdigit() or (target_channel.startswith("/") and target_channel[1:].isdigit())) else target_channel
            dest_enc = urllib.parse.quote(str(dest).strip(), safe="")
            move_url = f"http://127.0.0.1:58913/api/bot/use/{target_bid}/(/bot/move/{dest_enc})"
            if target_password:
                pw_enc = urllib.parse.quote(str(target_password).strip(), safe="")
                move_url = f"http://127.0.0.1:58913/api/bot/use/{target_bid}/(/bot/move/{dest_enc}/{pw_enc})"

            move_req = urllib.request.Request(move_url, headers={"User-Agent": "NinjaRadio-Backend"})
            move_res = urllib.request.urlopen(move_req, timeout=3).read().decode()
            api_result = f"Live moved via Web API (Bot {target_bid}): {move_res}"
            logger.info(f"[TS3 Live Move] {clean_name} -> '{target_channel}' (result: {move_res})")
    except Exception as ex:
        api_result = f"Config saved, note live move: {ex}"
        logger.warning(f"[TS3 Live Move Note] {ex}")

    return {"status": "SUCCESS", "channel": target_channel, "detail": api_result, "bots": bots}


async def run_callbook_autosync_loop():
    """Periodically syncs enabled CallbookSyncSource URLs in the background."""
    from backend.app.core.database import async_session_maker
    from datetime import timedelta
    await asyncio.sleep(20)
    while True:
        try:
            async with async_session_maker() as session:
                res = await session.execute(
                    select(CallbookSyncSource).where(CallbookSyncSource.enabled == True)
                )
                sources = res.scalars().all()
                now = datetime.utcnow()
                any_synced = False
                for src in sources:
                    due = (
                        src.last_sync_at is None
                        or (now - src.last_sync_at) >= timedelta(hours=max(1, src.interval_hours))
                    )
                    if due:
                        r = await CallbookService.sync_url_source(session, src)
                        if r.get("success"):
                            any_synced = True
                if any_synced:
                    asyncio.create_task(_notify_core_reload_subscribers())
        except Exception as e:
            logger.debug(f"Callbook auto-sync loop note: {e}")
        await asyncio.sleep(900)  # Check every 15 minutes
