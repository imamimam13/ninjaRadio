from pydantic import BaseModel, Field
from typing import Optional, List
from datetime import datetime

class QsoLogBase(BaseModel):
    callsign: str
    source_id: int
    source_name: Optional[str] = ""
    target_tg: int
    target_name: Optional[str] = ""
    slot: int = 1
    mode: str = "DMR"
    category: str = "Amatir"
    duration_sec: float = 0.0
    rssi: Optional[str] = "-75 dBm"
    ber: Optional[str] = "0.0%"
    loss: Optional[str] = "0.0%"
    remarks: Optional[str] = ""
    audio_url: Optional[str] = ""

class QsoLogCreate(QsoLogBase):
    pass

class QsoLogResponse(QsoLogBase):
    id: int
    timestamp: datetime

    class Config:
        from_attributes = True

class CallbookBase(BaseModel):
    dmr_id: int
    callsign: str
    name: str
    city: Optional[str] = ""
    province_state: Optional[str] = ""
    country: Optional[str] = "Indonesia"
    organization: Optional[str] = "ORARI"
    organization_id: Optional[str] = ""
    remarks: Optional[str] = ""
    default_tg: Optional[int] = 510

class CallbookCreate(CallbookBase):
    pass

class CallbookResponse(CallbookBase):
    id: int

    class Config:
        from_attributes = True

class BridgeRouteBase(BaseModel):
    name: str
    source_mode: str
    source_tg: int
    source_slot: int = 1
    target_mode: str
    target_tg: int
    target_slot: int = 1
    is_active: bool = True
    bidirectional: bool = True

class BridgeRouteCreate(BridgeRouteBase):
    pass

class BridgeRouteResponse(BridgeRouteBase):
    id: int

    class Config:
        from_attributes = True

class LastHeardItem(BaseModel):
    id: str
    timestamp: str
    callsign: str
    name: str
    dmr_id: int
    talkgroup: int
    tg_name: str
    slot: int
    mode: str
    category: str
    duration: str
    loss: str
    ber: str
    rssi: str
    is_tx: bool = False

class ServerStatus(BaseModel):
    server_name: str
    server_callsign: str
    server_id: int
    version: str
    uptime: str
    active_transmissions: int
    connected_hotspots: int
    web_clients: int = 1
    connected_peers: int
    active_bridges: int
    cpu_usage: float
    ram_usage: float

class PttTransmitRequest(BaseModel):
    callsign: Optional[str] = "WEB-OP"
    dmr_id: Optional[int] = 5109999
    tg: Optional[int] = 510
    slot: Optional[int] = 1
    call_type: Optional[str] = "group"
    category: Optional[str] = "Amatir"
    is_transmitting: Optional[bool] = True

class PinVerifyRequest(BaseModel):
    pin: str

class OrganizationCreateRequest(BaseModel):
    pin: str
    name: str
    code: Optional[str] = None
    description: Optional[str] = ""

class OrganizationResponse(BaseModel):
    id: int
    name: str
    code: str
    description: Optional[str] = ""
    is_default: Optional[bool] = False


