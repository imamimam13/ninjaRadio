from sqlalchemy.ext.asyncio import create_async_engine, async_sessionmaker, AsyncSession
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column
from sqlalchemy import String, Integer, Float, DateTime, Boolean, Text
from datetime import datetime
from typing import Optional
from backend.app.core.config import settings

engine = create_async_engine(settings.DATABASE_URL, echo=False)
async_session_maker = async_sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)

class Base(DeclarativeBase):
    pass

class QsoLog(Base):
    __tablename__ = "qso_logs"
    
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    timestamp: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, index=True)
    callsign: Mapped[str] = mapped_column(String(32), index=True)
    source_id: Mapped[int] = mapped_column(Integer, index=True)
    source_name: Mapped[str] = mapped_column(String(64), default="")
    target_tg: Mapped[int] = mapped_column(Integer, index=True)
    target_name: Mapped[str] = mapped_column(String(64), default="")
    slot: Mapped[int] = mapped_column(Integer, default=1)
    mode: Mapped[str] = mapped_column(String(16), default="DMR")  # DMR, YSF, P25, NXDN, D-STAR, ANALOG, RAPI, CB
    category: Mapped[str] = mapped_column(String(32), default="Amatir") # Amatir, RAPI, CB, Komunitas
    duration_sec: Mapped[float] = mapped_column(Float, default=0.0)
    rssi: Mapped[str] = mapped_column(String(16), default="-75 dBm")
    ber: Mapped[str] = mapped_column(String(16), default="0.0%")
    loss: Mapped[str] = mapped_column(String(16), default="0.0%")
    remarks: Mapped[str] = mapped_column(String(255), default="")
    audio_url: Mapped[str] = mapped_column(String(255), default="")

class CallbookEntry(Base):
    __tablename__ = "callbook"
    
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    dmr_id: Mapped[int] = mapped_column(Integer, unique=True, index=True)
    callsign: Mapped[str] = mapped_column(String(32), index=True)
    name: Mapped[str] = mapped_column(String(64))
    city: Mapped[str] = mapped_column(String(64), default="")
    province_state: Mapped[str] = mapped_column(String(64), default="")
    country: Mapped[str] = mapped_column(String(64), default="Indonesia")
    organization: Mapped[str] = mapped_column(String(32), default="ORARI") # ORARI, RAPI, CB-11M, KOMUNITAS, INTERNATIONAL
    organization_id: Mapped[str] = mapped_column(String(64), default="") # e.g. 10-28 code / RAPI ID
    remarks: Mapped[str] = mapped_column(String(255), default="")
    default_tg: Mapped[Optional[int]] = mapped_column(Integer, nullable=True, default=510)
    allowed_tgs_override: Mapped[Optional[str]] = mapped_column(String(255), nullable=True, default=None)

class BridgeRoute(Base):
    __tablename__ = "bridge_routes"
    
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    name: Mapped[str] = mapped_column(String(64))
    source_mode: Mapped[str] = mapped_column(String(16)) # DMR, YSF, P25, NXDN, ANALOG, D-STAR
    source_tg: Mapped[int] = mapped_column(Integer)
    source_slot: Mapped[int] = mapped_column(Integer, default=1)
    target_mode: Mapped[str] = mapped_column(String(16))
    target_tg: Mapped[int] = mapped_column(Integer)
    target_slot: Mapped[int] = mapped_column(Integer, default=1)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    bidirectional: Mapped[bool] = mapped_column(Boolean, default=True)

class Organization(Base):
    __tablename__ = "organizations"
    
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    code: Mapped[str] = mapped_column(String(32), unique=True, index=True)
    name: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    description: Mapped[str] = mapped_column(String(255), default="")
    allowed_tgs: Mapped[str] = mapped_column(String(255), default="*") # e.g. "5100,9990" or "*"
    callsign_prefixes: Mapped[str] = mapped_column(String(255), default="") # e.g. "YB,YC" or "BD,BD62"
    allow_tx: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)

async def init_db():
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
        # Safe migration for existing SQLite database
        from sqlalchemy import text
        try:
            await conn.execute(text("ALTER TABLE callbook ADD COLUMN default_tg INTEGER DEFAULT 510"))
        except Exception:
            pass
        try:
            await conn.execute(text("ALTER TABLE callbook ADD COLUMN allowed_tgs_override VARCHAR(255) DEFAULT NULL"))
        except Exception:
            pass
        try:
            await conn.execute(text("ALTER TABLE organizations ADD COLUMN allowed_tgs VARCHAR(255) DEFAULT '*'"))
        except Exception:
            pass
        try:
            await conn.execute(text("ALTER TABLE organizations ADD COLUMN callsign_prefixes VARCHAR(255) DEFAULT ''"))
        except Exception:
            pass
        try:
            await conn.execute(text("ALTER TABLE organizations ADD COLUMN allow_tx BOOLEAN DEFAULT 1"))
        except Exception:
            pass
        # Automatically update any existing BD callsigns from KOMUNITAS to BRIDI
        try:
            await conn.execute(text("UPDATE callbook SET organization = 'BRIDI' WHERE callsign LIKE 'BD%' AND organization = 'KOMUNITAS'"))
        except Exception:
            pass
        # Set default organization talkgroups policy & prefixes
        try:
            await conn.execute(text("UPDATE organizations SET allowed_tgs = '*' WHERE UPPER(code) IN ('ORARI', 'BRIDI', 'SENKOM', 'KRAPDMR', 'KOMUNITAS')"))
            await conn.execute(text("UPDATE organizations SET allowed_tgs = '5109,9990' WHERE UPPER(code) = 'RAPI' AND (allowed_tgs IS NULL OR allowed_tgs = '*')"))
            await conn.execute(text("UPDATE organizations SET allowed_tgs = '5108,9990' WHERE UPPER(code) = 'CB-11M' AND (allowed_tgs IS NULL OR allowed_tgs = '*')"))
            # Default callsign prefix routing per organization
            await conn.execute(text("UPDATE organizations SET callsign_prefixes = 'YB,YC,YD,YE,7A,7B,7C,7D,7E,7F,7G,7H,7I,8A,8B,8C,8D,8E,8F,8G,8H,8I' WHERE UPPER(code) = 'ORARI' AND (callsign_prefixes IS NULL OR callsign_prefixes = '')"))
            await conn.execute(text("UPDATE organizations SET callsign_prefixes = 'BD,BD62' WHERE UPPER(code) = 'BRIDI' AND (callsign_prefixes IS NULL OR callsign_prefixes = '')"))
            await conn.execute(text("UPDATE organizations SET callsign_prefixes = 'JZ' WHERE UPPER(code) = 'RAPI' AND (callsign_prefixes IS NULL OR callsign_prefixes = '')"))
            await conn.execute(text("UPDATE organizations SET callsign_prefixes = '11M,CB' WHERE UPPER(code) = 'CB-11M' AND (callsign_prefixes IS NULL OR callsign_prefixes = '')"))
            await conn.execute(text("UPDATE organizations SET callsign_prefixes = '*' WHERE UPPER(code) = 'KOMUNITAS' AND (callsign_prefixes IS NULL OR callsign_prefixes = '')"))
        except Exception:
            pass

async def get_db():
    async with async_session_maker() as session:
        yield session
