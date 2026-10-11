from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, or_, desc
from typing import List, Optional
from datetime import datetime
from backend.app.core.database import QsoLog
from backend.app.models.schemas import QsoLogCreate

INITIAL_QSO_DATA = [
    {"timestamp": datetime.utcnow(), "callsign": "YB0AZ", "source_id": 5100001, "source_name": "Budi Santoso", "target_tg": 510, "target_name": "Indonesia National", "slot": 1, "mode": "DMR", "category": "Amatir", "duration_sec": 14.5, "rssi": "-72 dBm", "ber": "0.1%", "loss": "0.0%", "remarks": "Clear audio 5/9"},
    {"timestamp": datetime.utcnow(), "callsign": "YB1AR", "source_id": 5101001, "source_name": "Arief Rahman", "target_tg": 5101, "target_name": "Jawa Barat Regional", "slot": 2, "mode": "YSF", "category": "Amatir", "duration_sec": 8.2, "rssi": "-80 dBm", "ber": "0.0%", "loss": "0.0%", "remarks": "Cross-mode test via YSF Bridge"},
    {"timestamp": datetime.utcnow(), "callsign": "JZ10XYZ", "source_id": 5109101, "source_name": "Rapi Ranger 01", "target_tg": 5109, "target_name": "RAPI Nusantara TG", "slot": 1, "mode": "ANALOG", "category": "RAPI", "duration_sec": 21.0, "rssi": "-68 dBm", "ber": "0.0%", "loss": "0.0%", "remarks": "Laporan situasi kamtibmas 10-2"},
    {"timestamp": datetime.utcnow(), "callsign": "91-DX-001", "source_id": 5109201, "source_name": "Garuda DXer", "target_tg": 5108, "target_name": "11M CB Gateway", "slot": 1, "mode": "CB", "category": "CB", "duration_sec": 12.0, "rssi": "-75 dBm", "ber": "0.0%", "loss": "0.0%", "remarks": "11 Meter Digital Voice check"},
    {"timestamp": datetime.utcnow(), "callsign": "RESCUE-01", "source_id": 5109301, "source_name": "Relawan SAR", "target_tg": 5100, "target_name": "Emergency SAR 911", "slot": 1, "mode": "P25", "category": "Komunitas", "duration_sec": 5.4, "rssi": "-65 dBm", "ber": "0.0%", "loss": "0.0%", "remarks": "Radio check posko siaga"},
]

class LogbookService:
    @staticmethod
    async def seed_initial_qso(session: AsyncSession):
        stmt = select(QsoLog).limit(1)
        result = await session.execute(stmt)
        if not result.scalar_one_or_none():
            for item in INITIAL_QSO_DATA:
                entry = QsoLog(**item)
                session.add(entry)
            await session.commit()

    @staticmethod
    async def get_logs(
        session: AsyncSession,
        query: Optional[str] = None,
        category: Optional[str] = None,
        mode: Optional[str] = None,
        limit: int = 50,
        offset: int = 0
    ) -> List[QsoLog]:
        stmt = select(QsoLog)
        
        if query:
            q = f"%{query.strip()}%"
            stmt = stmt.where(or_(
                QsoLog.callsign.ilike(q),
                QsoLog.source_name.ilike(q),
                QsoLog.target_name.ilike(q),
                QsoLog.remarks.ilike(q)
            ))
            
        if category and category.upper() != "ALL":
            stmt = stmt.where(QsoLog.category.ilike(category))
            
        if mode and mode.upper() != "ALL":
            stmt = stmt.where(QsoLog.mode.ilike(mode))
            
        stmt = stmt.order_by(desc(QsoLog.timestamp)).offset(offset).limit(limit)
        result = await session.execute(stmt)
        return list(result.scalars().all())

    @staticmethod
    async def add_qso(session: AsyncSession, data: QsoLogCreate) -> QsoLog:
        entry = QsoLog(**data.model_dump())
        session.add(entry)
        await session.commit()
        await session.refresh(entry)
        return entry

    @staticmethod
    async def generate_adif_export(session: AsyncSession, category: Optional[str] = None) -> str:
        """Generates standard Amateur Data Interchange Format (ADIF) v3 string."""
        stmt = select(QsoLog)
        if category and category.upper() != "ALL":
            stmt = stmt.where(QsoLog.category.ilike(category))
        stmt = stmt.order_by(desc(QsoLog.timestamp)).limit(500)
        result = await session.execute(stmt)
        logs = result.scalars().all()
        
        header = (
            "NinjaRadio ADIF Export v1.0\n"
            "<ADIF_VER:5>3.1.4\n"
            "<PROGRAMID:10>NinjaRadio\n"
            "<PROGRAMVERSION:5>1.0.0\n"
            "<EOH>\n\n"
        )
        
        records = []
        for log in logs:
            qso_date = log.timestamp.strftime("%Y%m%d")
            time_on = log.timestamp.strftime("%H%M%S")
            call = log.callsign
            mode = "DIGITALVOICE" if log.mode in ["DMR", "YSF", "D-STAR", "P25", "NXDN"] else "FM"
            submode = log.mode
            comment = f"TG {log.target_tg} ({log.target_name}) - {log.remarks}"
            
            record = (
                f"<QSO_DATE:{len(qso_date)}>{qso_date}"
                f"<TIME_ON:{len(time_on)}>{time_on}"
                f"<CALL:{len(call)}>{call}"
                f"<MODE:{len(mode)}>{mode}"
                f"<SUBMODE:{len(submode)}>{submode}"
                f"<BAND:2>70CM"
                f"<COMMENT:{len(comment)}>{comment}"
                f"<EOR>\n"
            )
            records.append(record)
            
        return header + "".join(records)
