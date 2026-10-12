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
    async def import_batch_data(
        session: AsyncSession,
        raw_content: str,
        filename: str,
        target_organization: Optional[str] = None,
        default_tg: Optional[int] = None
    ) -> Dict[str, Any]:
        """
        Supports CSV (RadioID format / Community CSV) and JSON formats
        (arrays, wrapped dictionaries, keyed dictionaries), with optional
        target organization and default talkgroup overrides.
        """
        imported_count = 0
        skipped_count = 0
        trimmed = raw_content.strip()
        is_json = filename.lower().endswith(".json") or trimmed.startswith("[") or trimmed.startswith("{")

        target_org_clean = target_organization.strip().upper() if target_organization and target_organization.strip().upper() not in ["", "AUTO", "ALL", "NONE"] else None

        if target_org_clean:
            try:
                await CallbookService.add_organization(session, code=target_org_clean, name=target_org_clean)
            except Exception:
                pass

        def extract_row_fields(row: Dict[str, Any]) -> Optional[Dict[str, Any]]:
            if not isinstance(row, dict):
                return None
            clean = {str(k).strip().lower(): v for k, v in row.items() if k is not None}
            
            # DMR ID extraction
            raw_id = (
                clean.get("dmr_id") or clean.get("radio_id") or clean.get("id") or 
                clean.get("dmrid") or clean.get("radioid") or clean.get("user_id") or 
                clean.get("radio_id_id")
            )
            if raw_id is None:
                return None
            try:
                dmr_id = int(str(raw_id).strip())
                if dmr_id <= 0:
                    return None
            except (ValueError, TypeError):
                return None

            # Callsign extraction
            callsign = str(clean.get("callsign") or clean.get("call") or clean.get("call_sign") or clean.get("user") or "NOCALL").strip().upper()

            # Name extraction
            first = str(clean.get("first_name") or clean.get("fname") or "").strip()
            last = str(clean.get("last_name") or clean.get("surname") or "").strip()
            name = str(clean.get("name") or clean.get("nama") or clean.get("fullname") or "").strip()
            if not name and (first or last):
                name = f"{first} {last}".strip()
            if not name:
                name = f"Operator {callsign}"

            # Location
            city = str(clean.get("city") or clean.get("kota") or "").strip()
            province = str(clean.get("province_state") or clean.get("state") or clean.get("provinsi") or "").strip()
            country = str(clean.get("country") or clean.get("negara") or "Indonesia").strip()

            # Organization
            if target_org_clean:
                org = target_org_clean
            else:
                org_raw = clean.get("organization") or clean.get("organisasi") or clean.get("group") or clean.get("org")
                if org_raw:
                    org = str(org_raw).strip().upper()
                else:
                    org = "ORARI" if callsign.startswith(("YB", "YC", "YD")) else "KOMUNITAS"

            # Default TalkGroup
            if default_tg is not None and int(default_tg) > 0:
                tg_val = int(default_tg)
            else:
                raw_tg = clean.get("default_tg") or clean.get("tg") or clean.get("talkgroup") or clean.get("target_tg")
                try:
                    tg_val = int(str(raw_tg).strip()) if raw_tg else 510
                except Exception:
                    tg_val = 510

            org_id = str(clean.get("organization_id") or clean.get("org_id") or clean.get("rapi_id") or clean.get("10_28") or callsign).strip()
            remarks = str(clean.get("remarks") or clean.get("keterangan") or clean.get("comment") or "").strip()

            return {
                "dmr_id": dmr_id,
                "callsign": callsign,
                "name": name,
                "city": city,
                "province_state": province,
                "country": country,
                "organization": org,
                "organization_id": org_id,
                "remarks": remarks,
                "default_tg": tg_val,
            }

        if is_json:
            try:
                data = json.loads(trimmed)
                raw_rows: List[Any] = []
                if isinstance(data, list):
                    raw_rows = data
                elif isinstance(data, dict):
                    # Check common container keys
                    for key in ["users", "data", "callbook", "results", "records", "items", "subscribers", "entries", "contacts", "rows"]:
                        if key in data and isinstance(data[key], list):
                            raw_rows = data[key]
                            break
                    
                    if not raw_rows:
                        # Check if any value is a list of dicts
                        for k, v in data.items():
                            if isinstance(v, list) and len(v) > 0 and isinstance(v[0], dict):
                                raw_rows = v
                                break

                    if not raw_rows:
                        # Keyed dictionary (e.g. {"1022057": {"callsign": "BD62BOT", ...}})
                        for k, v in data.items():
                            if isinstance(v, dict):
                                row_dict = dict(v)
                                if str(k).strip().isdigit() and not any(f in row_dict for f in ["dmr_id", "radio_id", "id", "dmrid", "radioid"]):
                                    row_dict["dmr_id"] = int(k)
                                elif not str(k).strip().isdigit() and not any(f in row_dict for f in ["callsign", "call", "call_sign"]):
                                    row_dict["callsign"] = str(k).strip()
                                raw_rows.append(row_dict)
                            elif isinstance(v, (str, int)):
                                if str(k).strip().isdigit():
                                    raw_rows.append({"dmr_id": int(k), "callsign": str(v).strip()})

                for row in raw_rows:
                    parsed = extract_row_fields(row)
                    if not parsed:
                        skipped_count += 1
                        continue

                    existing = await CallbookService.get_by_dmr_id(session, parsed["dmr_id"])
                    if existing:
                        existing.callsign = parsed["callsign"] or existing.callsign
                        existing.name = parsed["name"] or existing.name
                        existing.city = parsed["city"] or existing.city
                        existing.province_state = parsed["province_state"] or existing.province_state
                        existing.country = parsed["country"] or existing.country
                        existing.organization = parsed["organization"] or existing.organization
                        existing.organization_id = parsed["organization_id"] or existing.organization_id
                        existing.remarks = parsed["remarks"] or existing.remarks
                        existing.default_tg = parsed["default_tg"] or existing.default_tg
                    else:
                        entry = CallbookEntry(
                            dmr_id=parsed["dmr_id"],
                            callsign=parsed["callsign"],
                            name=parsed["name"],
                            city=parsed["city"],
                            province_state=parsed["province_state"],
                            country=parsed["country"],
                            organization=parsed["organization"],
                            organization_id=parsed["organization_id"],
                            remarks=parsed["remarks"],
                            default_tg=parsed["default_tg"],
                        )
                        session.add(entry)
                    imported_count += 1

                await session.commit()
            except Exception as e:
                return {"success": False, "error": f"JSON parse error: {str(e)}", "imported": 0}

        else:
            # CSV parsing
            try:
                reader = csv.DictReader(io.StringIO(trimmed))
                for row in reader:
                    parsed = extract_row_fields(row)
                    if not parsed:
                        skipped_count += 1
                        continue

                    existing = await CallbookService.get_by_dmr_id(session, parsed["dmr_id"])
                    if existing:
                        existing.callsign = parsed["callsign"] or existing.callsign
                        existing.name = parsed["name"] or existing.name
                        existing.city = parsed["city"] or existing.city
                        existing.province_state = parsed["province_state"] or existing.province_state
                        existing.country = parsed["country"] or existing.country
                        existing.organization = parsed["organization"] or existing.organization
                        existing.organization_id = parsed["organization_id"] or existing.organization_id
                        existing.remarks = parsed["remarks"] or existing.remarks
                        existing.default_tg = parsed["default_tg"] or existing.default_tg
                    else:
                        entry = CallbookEntry(
                            dmr_id=parsed["dmr_id"],
                            callsign=parsed["callsign"],
                            name=parsed["name"],
                            city=parsed["city"],
                            province_state=parsed["province_state"],
                            country=parsed["country"],
                            organization=parsed["organization"],
                            organization_id=parsed["organization_id"],
                            remarks=parsed["remarks"],
                            default_tg=parsed["default_tg"],
                        )
                        session.add(entry)
                    imported_count += 1

                await session.commit()
            except Exception as e:
                return {"success": False, "error": f"CSV parse error: {str(e)}", "imported": 0}

        return {
            "success": True,
            "imported_count": imported_count,
            "skipped_count": skipped_count
        }
