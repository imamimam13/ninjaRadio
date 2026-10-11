from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, or_
from typing import List, Optional, Dict, Any
import csv
import json
import io
from backend.app.core.database import CallbookEntry
from backend.app.models.schemas import CallbookCreate

INITIAL_CALLBOOK_DATA = [
    {"dmr_id": 5100001, "callsign": "YB0AZ", "name": "Budi Santoso", "city": "Jakarta Pusat", "province_state": "DKI Jakarta", "country": "Indonesia", "organization": "ORARI", "organization_id": "YB0AZ", "remarks": "National Master Control"},
    {"dmr_id": 5101001, "callsign": "YB1AR", "name": "Arief Rahman", "city": "Bandung", "province_state": "Jawa Barat", "country": "Indonesia", "organization": "ORARI", "organization_id": "YB1AR", "remarks": "West Java Repeater Host"},
    {"dmr_id": 5102002, "callsign": "YC2BBS", "name": "Bambang Sudarmo", "city": "Semarang", "province_state": "Jawa Tengah", "country": "Indonesia", "organization": "ORARI", "organization_id": "YC2BBS", "remarks": "Central Java Net Control"},
    {"dmr_id": 5103003, "callsign": "YD3NJA", "name": "Ninja Operator", "city": "Surabaya", "province_state": "Jawa Timur", "country": "Indonesia", "organization": "ORARI", "organization_id": "YD3NJA", "remarks": "NinjaDMR Core Dev"},
    {"dmr_id": 5109101, "callsign": "JZ10XYZ", "name": "Rapi Ranger 01", "city": "Jakarta Timur", "province_state": "DKI Jakarta", "country": "Indonesia", "organization": "RAPI", "organization_id": "10-28 JKT-01", "remarks": "RAPI Wilayah 10"},
    {"dmr_id": 5109102, "callsign": "JZ11BND", "name": "Rapi Priangan", "city": "Bandung", "province_state": "Jawa Barat", "country": "Indonesia", "organization": "RAPI", "organization_id": "10-28 BDG-02", "remarks": "RAPI Wilayah 11"},
    {"dmr_id": 5109201, "callsign": "91-DX-001", "name": "Garuda DXer", "city": "Yogyakarta", "province_state": "DIY", "country": "Indonesia", "organization": "CB-11M", "organization_id": "91-DIV-001", "remarks": "11 Meter Citizens Band DXer"},
    {"dmr_id": 5109301, "callsign": "RESCUE-01", "name": "Relawan SAR Nusantara", "city": "Nusantara", "province_state": "IKN", "country": "Indonesia", "organization": "KOMUNITAS", "organization_id": "SAR-COMM-01", "remarks": "Emergency & Disaster Response"},
]

class CallbookService:
    @staticmethod
    async def seed_initial_callbook(session: AsyncSession):
        stmt = select(CallbookEntry).limit(1)
        result = await session.execute(stmt)
        if not result.scalar_one_or_none():
            for item in INITIAL_CALLBOOK_DATA:
                entry = CallbookEntry(**item)
                session.add(entry)
            await session.commit()

    @staticmethod
    async def search_callbook(session: AsyncSession, query: str, organization: Optional[str] = None) -> List[CallbookEntry]:
        stmt = select(CallbookEntry)
        filters = []
        if query:
            q_clean = query.strip()
            if q_clean.isdigit():
                filters.append(CallbookEntry.dmr_id == int(q_clean))
            filters.append(CallbookEntry.callsign.ilike(f"%{q_clean}%"))
            filters.append(CallbookEntry.name.ilike(f"%{q_clean}%"))
            filters.append(CallbookEntry.city.ilike(f"%{q_clean}%"))
            filters.append(CallbookEntry.organization_id.ilike(f"%{q_clean}%"))
            stmt = stmt.where(or_(*filters))
        
        if organization and organization.upper() != "ALL":
            stmt = stmt.where(CallbookEntry.organization == organization.upper())
            
        stmt = stmt.order_by(CallbookEntry.callsign).limit(100)
        result = await session.execute(stmt)
        return list(result.scalars().all())

    @staticmethod
    async def get_by_dmr_id(session: AsyncSession, dmr_id: int) -> Optional[CallbookEntry]:
        stmt = select(CallbookEntry).where(CallbookEntry.dmr_id == dmr_id)
        result = await session.execute(stmt)
        return result.scalar_one_or_none()

    @staticmethod
    async def create_entry(session: AsyncSession, data: CallbookCreate) -> CallbookEntry:
        entry = CallbookEntry(**data.model_dump())
        session.add(entry)
        await session.commit()
        await session.refresh(entry)
        return entry

    @staticmethod
    async def import_batch_data(session: AsyncSession, raw_content: str, filename: str) -> Dict[str, Any]:
        """
        Supports CSV (RadioID format / Community CSV) and JSON array format.
        """
        imported_count = 0
        skipped_count = 0
        is_json = filename.endswith(".json") or raw_content.strip().startswith("[")

        if is_json:
            try:
                data = json.loads(raw_content)
                for row in data:
                    dmr_id = row.get("dmr_id") or row.get("radio_id") or row.get("id")
                    if not dmr_id:
                        skipped_count += 1
                        continue
                    
                    existing = await CallbookService.get_by_dmr_id(session, int(dmr_id))
                    if existing:
                        existing.callsign = row.get("callsign", existing.callsign)
                        existing.name = row.get("name", existing.name)
                        existing.organization = row.get("organization", existing.organization)
                        existing.city = row.get("city", existing.city)
                        existing.province_state = row.get("province_state", existing.province_state)
                        existing.remarks = row.get("remarks", existing.remarks)
                    else:
                        entry = CallbookEntry(
                            dmr_id=int(dmr_id),
                            callsign=row.get("callsign", "NOCALL").upper(),
                            name=row.get("name", ""),
                            city=row.get("city", ""),
                            province_state=row.get("province_state", ""),
                            country=row.get("country", "Indonesia"),
                            organization=row.get("organization", "KOMUNITAS").upper(),
                            organization_id=row.get("organization_id", ""),
                            remarks=row.get("remarks", "Imported via JSON")
                        )
                        session.add(entry)
                    imported_count += 1
                await session.commit()
            except Exception as e:
                return {"success": False, "error": str(e), "imported": 0}
        else:
            # CSV parsing
            try:
                reader = csv.DictReader(io.StringIO(raw_content))
                for row in reader:
                    # Clean keys
                    clean_row = {k.strip().lower() if k else "": v.strip() if v else "" for k, v in row.items()}
                    
                    dmr_id = clean_row.get("radio_id") or clean_row.get("dmr_id") or clean_row.get("id")
                    if not dmr_id or not dmr_id.isdigit():
                        continue
                    
                    callsign = clean_row.get("callsign") or clean_row.get("call") or "NOCALL"
                    first_name = clean_row.get("first_name") or clean_row.get("name") or clean_row.get("nama") or ""
                    last_name = clean_row.get("last_name") or ""
                    full_name = f"{first_name} {last_name}".strip()
                    city = clean_row.get("city") or clean_row.get("kota") or ""
                    state = clean_row.get("state") or clean_row.get("province_state") or clean_row.get("provinsi") or ""
                    country = clean_row.get("country") or clean_row.get("negara") or "Indonesia"
                    org = clean_row.get("organization") or clean_row.get("organisasi") or ("ORARI" if callsign.startswith(("YB", "YC", "YD")) else "KOMUNITAS")
                    remarks = clean_row.get("remarks") or clean_row.get("keterangan") or ""

                    existing = await CallbookService.get_by_dmr_id(session, int(dmr_id))
                    if existing:
                        existing.callsign = callsign.upper()
                        existing.name = full_name or existing.name
                        existing.city = city or existing.city
                        existing.province_state = state or existing.province_state
                        existing.organization = org.upper()
                        existing.remarks = remarks or existing.remarks
                    else:
                        entry = CallbookEntry(
                            dmr_id=int(dmr_id),
                            callsign=callsign.upper(),
                            name=full_name,
                            city=city,
                            province_state=state,
                            country=country,
                            organization=org.upper(),
                            organization_id=callsign.upper(),
                            remarks=remarks
                        )
                        session.add(entry)
                    imported_count += 1
                await session.commit()
            except Exception as e:
                return {"success": False, "error": str(e), "imported": 0}

        return {
            "success": True,
            "imported_count": imported_count,
            "skipped_count": skipped_count
        }
