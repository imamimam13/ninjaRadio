import asyncio
from contextlib import asynccontextmanager
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware

from backend.app.core.config import settings
from backend.app.core.database import init_db, async_session_maker
from backend.app.api.endpoints import router as api_router
from backend.app.services.callbook_service import CallbookService
from backend.app.services.logbook_service import LogbookService
from backend.app.services.bridge_service import BridgeService
from backend.app.services.dv_monitor import manager, run_dv_simulator

simulator_task = None

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup
    await init_db()
    async with async_session_maker() as session:
        await CallbookService.seed_initial_callbook(session)
        await CallbookService.seed_initial_organizations(session)
        await LogbookService.seed_initial_qso(session)
        await BridgeService.seed_initial_routes(session)
    
    # Start live background simulator only if explicitly enabled
    global simulator_task
    import os
    if os.getenv("ENABLE_DEMO_SIMULATOR", "false").lower() == "true":
        simulator_task = asyncio.create_task(run_dv_simulator())
    
    # Inactivity watchdog for live DMR streams
    from backend.app.api.endpoints import run_stream_watchdog
    watchdog_task = asyncio.create_task(run_stream_watchdog())

    yield

    # Shutdown
    watchdog_task.cancel()
    if simulator_task:
        simulator_task.cancel()

app = FastAPI(
    title=settings.PROJECT_NAME,
    version=settings.VERSION,
    description="NinjaRadio: Multi-Mode Digital Voice & Analog Audio Gateway Hub",
    lifespan=lifespan
)

# Enable CORS for Next.js and PWA apps
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.CORS_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# API routes
app.include_router(api_router, prefix=settings.API_V1_STR)

# Realtime WebSocket for Last Heard, Transceiver Telemetry & Bi-directional Web Audio
@app.websocket("/ws/live")
async def websocket_live_endpoint(websocket: WebSocket):
    import json
    import base64
    from backend.app.api.endpoints import send_real_usrp_packet_to_core

    await manager.connect(websocket)
    client_tx_active = False
    last_tx_info = {"tg": 1025, "dmr_id": 102205798, "slot": 2, "callsign": "WEB-OP"}

    async def notify_core_stop_tx():
        nonlocal client_tx_active
        if client_tx_active:
            client_tx_active = False
            send_real_usrp_packet_to_core(
                last_tx_info["callsign"], last_tx_info["dmr_id"], last_tx_info["tg"], last_tx_info["slot"], is_tx=False
            )
            try:
                import httpx
                async with httpx.AsyncClient(timeout=1.0) as client:
                    await client.post("http://core:5000/api/tx", json={
                        "callsign": last_tx_info["callsign"],
                        "dmr_id": last_tx_info["dmr_id"],
                        "tg": last_tx_info["tg"],
                        "slot": last_tx_info["slot"],
                        "is_transmitting": False
                    })
            except Exception:
                pass

    try:
        while True:
            # Handle incoming client messages (audio stream from Web PTT, PTT events, ping)
            data = await websocket.receive_text()
            if data:
                try:
                    msg = json.loads(data)
                    msg_type = msg.get("type")
                    if msg_type == "AUDIO_TX":
                        pcm_b64 = msg.get("pcm")
                        tg = int(msg.get("tg", 1025))
                        dmr_id = int(msg.get("dmr_id", 102205798))
                        slot = int(msg.get("slot", 2))
                        callsign = str(msg.get("callsign", "WEB-OP"))
                        last_tx_info = {"tg": tg, "dmr_id": dmr_id, "slot": slot, "callsign": callsign}
                        is_tx = bool(msg.get("is_tx", True)) if pcm_b64 else False
                        if pcm_b64 and is_tx:
                            # Verify TX authorization with Callbook & Organization Firewall
                            async with async_session_maker() as session:
                                is_allowed, reason = await CallbookService.check_tx_permission(session, dmr_id, callsign, tg)
                            if not is_allowed:
                                await websocket.send_text(json.dumps({
                                    "type": "TX_REJECTED",
                                    "tg": tg,
                                    "callsign": callsign,
                                    "reason": reason
                                }))
                                continue
                            client_tx_active = True
                            pcm_data = base64.b64decode(pcm_b64)
                            send_real_usrp_packet_to_core(callsign, dmr_id, tg, slot, is_tx=True, pcm_payload=pcm_data)
                        elif not is_tx:
                            await notify_core_stop_tx()
                    elif msg_type == "PTT_HEARTBEAT":
                        try:
                            import httpx
                            async with httpx.AsyncClient(timeout=0.5) as client:
                                await client.post("http://core:5000/api/tx/heartbeat")
                        except Exception:
                            pass
                except Exception:
                    pass
    except WebSocketDisconnect:
        await notify_core_stop_tx()
        manager.disconnect(websocket)
    except Exception:
        await notify_core_stop_tx()
        manager.disconnect(websocket)

@app.get("/health")
async def health_check():
    return {"status": "online", "system": "NinjaRadio Core", "version": settings.VERSION}
