from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, or_
from typing import List, Optional, Dict, Any
import csv
import json
import io
from backend.app.core.database import CallbookEntry, Organization
from backend.app.models.schemas import CallbookCreate

INITIAL_CALLBOOK_DATA = []

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
    async def seed_initial_organizations(session: AsyncSession):
        stmt = select(Organization).limit(1)
        result = await session.execute(stmt)
        if not result.scalar_one_or_none():
            defaults = [
                {"code": "ORARI", "name": "ORARI", "description": "Organisasi Amatir Radio Indonesia"},
                {"code": "RAPI", "name": "RAPI", "description": "Radio Antar Penduduk Indonesia"},
                {"code": "CB-11M", "name": "CB 11M", "description": "Citizen Band 11 Meter Indonesia"},
                {"code": "KOMUNITAS", "name": "Komunitas / SAR", "description": "Relawan, SAR, & Radio Komunitas"},
            ]
            for item in defaults:
                session.add(Organization(**item))
            await session.commit()

    @staticmethod
    async def get_all_organizations(session: AsyncSession) -> List[Organization]:
        await CallbookService.seed_initial_organizations(session)
        stmt = select(Organization).order_by(Organization.id)
        result = await session.execute(stmt)
        return list(result.scalars().all())

    @staticmethod
    async def add_organization(session: AsyncSession, code: str, name: str, description: str = "") -> Organization:
        await CallbookService.seed_initial_organizations(session)
        code_clean = code.strip().upper()
        name_clean = name.strip()
        stmt = select(Organization).where(or_(Organization.code == code_clean, Organization.name == name_clean))
        result = await session.execute(stmt)
        existing = result.scalar_one_or_none()
        if existing:
            return existing
        org = Organization(code=code_clean, name=name_clean, description=description)
        session.add(org)
        await session.commit()
        await session.refresh(org)
        return org

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
