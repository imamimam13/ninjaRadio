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
    allowed_tgs_override: Optional[str] = None

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
    peer_name: Optional[str] = None

class BridgeRouteCreate(BridgeRouteBase):
    pass

class BridgeRouteUpdate(BaseModel):
    name: Optional[str] = None
    source_mode: Optional[str] = None
    source_tg: Optional[int] = None
    source_slot: Optional[int] = None
    target_mode: Optional[str] = None
    target_tg: Optional[int] = None
    target_slot: Optional[int] = None
    is_active: Optional[bool] = None
    bidirectional: Optional[bool] = None
    peer_name: Optional[str] = None

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
    allowed_tgs: Optional[str] = "*"
    callsign_prefixes: Optional[str] = ""
    allow_tx: Optional[bool] = True

class OrganizationRuleUpdate(BaseModel):
    pin: str
    allowed_tgs: str
    callsign_prefixes: Optional[str] = None
    allow_tx: Optional[bool] = True

class OrganizationResponse(BaseModel):
    id: int
    name: str
    code: str
    description: Optional[str] = ""
    allowed_tgs: Optional[str] = "*"
    callsign_prefixes: Optional[str] = ""
    allow_tx: Optional[bool] = True
    is_default: Optional[bool] = False


class SelfCareRegisterRequest(BaseModel):
    callsign: str
    dmr_id: int
    name: str
    city: Optional[str] = ""
    organization: str = "ORARI"
    organization_id: Optional[str] = ""
    verification_note: Optional[str] = ""
    password: str = Field(..., min_length=4)
    req_web: bool = True
    req_dmr: bool = False
    req_svxlink: bool = False
    req_mumble: bool = False
    req_dvswitch: bool = False
    req_zello: bool = False
    zello_username: Optional[str] = ""


class SelfCareLoginRequest(BaseModel):
    identifier: str  # callsign or dmr_id
    password: str


class SelfCareServiceRequest(BaseModel):
    req_web: Optional[bool] = None
    req_dmr: Optional[bool] = None
    req_svxlink: Optional[bool] = None
    req_mumble: Optional[bool] = None
    req_dvswitch: Optional[bool] = None
    req_zello: Optional[bool] = None
    zello_username: Optional[str] = None


class SelfCareRotatePasswordRequest(BaseModel):
    protocol: str  # dmr | svxlink | mumble


class AdminApprovalRequest(BaseModel):
    allow_web: bool = True
    allow_dmr: bool = False
    allow_svxlink: bool = False
    allow_mumble: bool = False
    allow_dvswitch: bool = False
    allow_zello: bool = False
    zello_username: Optional[str] = None
    default_tg: Optional[int] = 510


class AdminRejectRequest(BaseModel):
    reason: Optional[str] = "Data tidak valid atau belum memenuhi syarat organisasi."


class UserAccountPublicResponse(BaseModel):
    id: int
    callsign: str
    dmr_id: int
    name: str
    city: str
    organization: str
    organization_id: str
    verification_note: str
    status: str
    rejected_reason: str
    approved_at: Optional[datetime] = None
    created_at: datetime
    req_web: bool
    req_dmr: bool
    req_svxlink: bool
    req_mumble: bool
    req_dvswitch: bool
    req_zello: bool
    allow_web: bool
    allow_dmr: bool
    allow_svxlink: bool
    allow_mumble: bool
    allow_dvswitch: bool
    allow_zello: bool
    dmr_password: str = ""
    svxlink_password: str = ""
    mumble_password: str = ""
    zello_username: str = ""
    dvswitch_allowed_ip: str = ""

    class Config:
        from_attributes = True


class UserAccountAdminResponse(UserAccountPublicResponse):
    prefix_valid: bool = True
    prefix_hint: str = ""



