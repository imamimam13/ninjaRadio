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
        await LogbookService.seed_initial_qso(session)
        await BridgeService.seed_initial_routes(session)
    
    # Start live background simulator only if explicitly enabled
    global simulator_task
    import os
    if os.getenv("ENABLE_DEMO_SIMULATOR", "false").lower() == "true":
        simulator_task = asyncio.create_task(run_dv_simulator())
    
    yield
    
    # Shutdown
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
                        if pcm_b64:
                            pcm_data = base64.b64decode(pcm_b64)
                            tg = int(msg.get("tg", 510))
                            dmr_id = int(msg.get("dmr_id", 5109999))
                            slot = int(msg.get("slot", 1))
                            callsign = str(msg.get("callsign", "WEB-OP"))
                            send_real_usrp_packet_to_core(callsign, dmr_id, tg, slot, is_tx=True, pcm_payload=pcm_data)
                except Exception:
                    pass
    except WebSocketDisconnect:
        manager.disconnect(websocket)
    except Exception:
        manager.disconnect(websocket)

@app.get("/health")
async def health_check():
    return {"status": "online", "system": "NinjaRadio Core", "version": settings.VERSION}
