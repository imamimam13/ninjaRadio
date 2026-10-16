from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, or_
from typing import List, Optional, Dict, Any
import os
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
                {"code": "ORARI", "name": "ORARI", "description": "Organisasi Amatir Radio Indonesia", "allowed_tgs": "*", "allow_tx": True},
                {"code": "BRIDI", "name": "BRIDI", "description": "BrandMeister & Digital Amateur Radio Indonesia", "allowed_tgs": "*", "allow_tx": True},
                {"code": "RAPI", "name": "RAPI", "description": "Radio Antar Penduduk Indonesia", "allowed_tgs": "5109,9990", "allow_tx": True},
                {"code": "CB-11M", "name": "CB 11M", "description": "Citizen Band 11 Meter Indonesia", "allowed_tgs": "5108,9990", "allow_tx": True},
                {"code": "KOMUNITAS", "name": "Komunitas / SAR", "description": "Relawan, SAR, & Radio Komunitas", "allowed_tgs": "5100,9990", "allow_tx": True},
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
    async def update_organization_rule(session: AsyncSession, code: str, allowed_tgs: str, allow_tx: bool = True) -> Optional[Organization]:
        await CallbookService.seed_initial_organizations(session)
        stmt = select(Organization).where(Organization.code == code.strip().upper())
        result = await session.execute(stmt)
        org = result.scalar_one_or_none()
        if org:
            org.allowed_tgs = allowed_tgs.strip()
            org.allow_tx = allow_tx
            await session.commit()
            await session.refresh(org)
            return org
        return None

    @staticmethod
    async def check_tx_permission(session: AsyncSession, dmr_id: int, callsign: str, target_tg: int) -> tuple:
        """
        Validates whether the operator is allowed to transmit on target_tg.
        Returns (is_allowed: bool, reason_or_category: str).
        """
        # Always allow Echo/Parrot test TG 9990
        if target_tg == 9990:
            return True, "Echo Test"

        call_upper = (callsign or "").strip().upper()
        if not call_upper or call_upper in ["SWL-MONITOR", "MONITOR", "LISTENER", "GUEST", "TAMU"] or dmr_id == 0:
            return False, "Mode Monitor (SWL) tidak memiliki izin memancar. Gunakan profil berlisensi atau PIN Operator."

        # Find callbook entry
        cb = None
        if dmr_id > 0:
            cb = await CallbookService.get_by_dmr_id(session, dmr_id)
        if not cb and call_upper:
            cb = await CallbookService.get_by_callsign(session, call_upper)

        # User-specific override if present
        if cb and cb.allowed_tgs_override:
            rule_str = cb.allowed_tgs_override.strip()
            if rule_str == "*":
                return True, cb.organization or "User"
            allowed_list = [int(x.strip()) for x in rule_str.split(",") if x.strip().isdigit()]
            if target_tg in allowed_list:
                return True, cb.organization or "User"
            else:
                return False, f"Operator {call_upper} hanya memiliki izin khusus pada TG: {rule_str}"

        org_code = cb.organization.upper() if (cb and cb.organization) else None

        # If not in callbook, resolve organization from callsign prefix or fallback
        if not org_code:
            if call_upper.startswith(("YB", "YC", "YD", "YE", "BD", "N1NJA", "TEST-OP")):
                org_code = "ORARI"
            elif call_upper.startswith("JZ"):
                org_code = "RAPI"
            else:
                org_code = "KOMUNITAS"

        # Lookup organization rule in DB
        await CallbookService.seed_initial_organizations(session)
        stmt = select(Organization).where(Organization.code == org_code)
        res = await session.execute(stmt)
        org = res.scalar_one_or_none()

        if not org:
            if org_code in ["ORARI", "BRIDI"]:
                return True, org_code
            elif org_code == "RAPI":
                if target_tg in [5109, 9990]:
                    return True, "RAPI"
                return False, f"Stasiun RAPI ({call_upper}) tidak diizinkan memancar di TG {target_tg}. Gunakan TG 5109."
            elif org_code == "CB-11M":
                if target_tg in [5108, 9990]:
                    return True, "CB-11M"
                return False, f"Stasiun CB-11M ({call_upper}) tidak diizinkan memancar di TG {target_tg}. Gunakan TG 5108."
            else:
                if target_tg in [5100, 9990]:
                    return True, "KOMUNITAS"
                return False, f"Stasiun Komunitas ({call_upper}) tidak diizinkan memancar di TG {target_tg} (Khusus Amatir). Gunakan TG 5100."

        if not org.allow_tx:
            return False, f"Organisasi {org.name} saat ini diset Monitor Only oleh Sysop."

        rule = (org.allowed_tgs or "*").strip()
        if rule == "*":
            return True, org.name

        allowed_tgs = [int(x.strip()) for x in rule.split(",") if x.strip().isdigit()]
        if target_tg in allowed_tgs:
            return True, org.name

        return False, f"Organisasi {org.name} ({call_upper}) hanya diizinkan memancar di TG [{rule}]. Akses ke TG {target_tg} diblokir oleh TX Firewall."

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
    async def get_entries_by_organization(session: AsyncSession, org_code: Optional[str] = None) -> List[CallbookEntry]:
        stmt = select(CallbookEntry)
        if org_code and org_code.upper() not in ["ALL", "USER", "USERS", "GLOBAL"]:
            clean_org = org_code.strip().upper()
            if clean_org in ["CB", "CB11M"]:
                clean_org = "CB-11M"
            stmt = stmt.where(CallbookEntry.organization == clean_org)
        stmt = stmt.order_by(CallbookEntry.callsign)
        result = await session.execute(stmt)
        return list(result.scalars().all())

    @staticmethod
    async def get_by_dmr_id(session: AsyncSession, dmr_id: int) -> Optional[CallbookEntry]:
        stmt = select(CallbookEntry).where(CallbookEntry.dmr_id == dmr_id)
        result = await session.execute(stmt)
        return result.scalar_one_or_none()

    @staticmethod
    async def get_by_callsign(session: AsyncSession, callsign: str) -> Optional[CallbookEntry]:
        if not callsign:
            return None
        stmt = select(CallbookEntry).where(CallbookEntry.callsign == callsign.strip().upper())
        result = await session.execute(stmt)
        return result.scalar_one_or_none()

    @staticmethod
    async def get_by_id(session: AsyncSession, entry_id: int) -> Optional[CallbookEntry]:
        stmt = select(CallbookEntry).where(CallbookEntry.id == entry_id)
        result = await session.execute(stmt)
        return result.scalar_one_or_none()

    @staticmethod
    async def update_entry(session: AsyncSession, entry_id: int, data: CallbookCreate) -> Optional[CallbookEntry]:
        entry = await CallbookService.get_by_id(session, entry_id)
        if not entry:
            return None
        
        entry.dmr_id = data.dmr_id
        entry.callsign = data.callsign.upper()
        entry.name = data.name
        entry.city = data.city or ""
        entry.province_state = data.province_state or ""
        entry.country = data.country or "Indonesia"
        entry.organization = data.organization.upper() if data.organization else "KOMUNITAS"
        entry.organization_id = data.organization_id or data.callsign.upper()
        entry.remarks = data.remarks or ""
        entry.default_tg = data.default_tg if data.default_tg else 510
        if hasattr(data, "allowed_tgs_override"):
            entry.allowed_tgs_override = data.allowed_tgs_override

        await session.commit()
        await session.refresh(entry)
        await CallbookService.sync_subscribers_csv(session)
        return entry

    @staticmethod
    async def delete_entry(session: AsyncSession, entry_id: int) -> bool:
        entry = await CallbookService.get_by_id(session, entry_id)
        if not entry:
            return False
        await session.delete(entry)
        await session.commit()
        await CallbookService.sync_subscribers_csv(session)
        return True

    @staticmethod
    async def batch_update(
        session: AsyncSession,
        target_org: Optional[str] = None,
        target_tg: Optional[int] = None,
        filter_org: Optional[str] = None,
        callsign_prefix: Optional[str] = None,
        ids: Optional[List[int]] = None
    ) -> Dict[str, Any]:
        stmt = select(CallbookEntry)
        if ids and len(ids) > 0:
            stmt = stmt.where(CallbookEntry.id.in_(ids))
        if filter_org and filter_org.upper() != "ALL":
            stmt = stmt.where(CallbookEntry.organization == filter_org.upper())
        if callsign_prefix:
            stmt = stmt.where(CallbookEntry.callsign.startswith(callsign_prefix.upper()))

        result = await session.execute(stmt)
        entries = result.scalars().all()
        updated_count = 0

        target_org_clean = target_org.strip().upper() if target_org else None
        if target_org_clean:
            try:
                await CallbookService.add_organization(session, code=target_org_clean, name=target_org_clean)
            except Exception:
                pass

        for entry in entries:
            if target_org_clean:
                entry.organization = target_org_clean
            if target_tg is not None and int(target_tg) > 0:
                entry.default_tg = int(target_tg)
            updated_count += 1

        await session.commit()
        await CallbookService.sync_subscribers_csv(session)
        return {"success": True, "updated_count": updated_count}

    @staticmethod
    async def sync_subscribers_csv(session: AsyncSession):
        """
        Synchronizes all registered Callbook entries into /app/data/subscriber_ids.csv
        and /var/lib/dvswitch/subscriber_ids.csv for DVSwitch & MMDVM subscriber lookup.
        """
        try:
            stmt = select(CallbookEntry)
            result = await session.execute(stmt)
            entries = result.scalars().all()

            lines = [
                "; DMR ID,Callsign,Name,City,State,Country",
                "5109999,N1NJA,NinjaRadio Master,Jakarta,ID,0"
            ]
            for e in entries:
                clean_id = e.dmr_id & 0xFFFFFF if e.dmr_id else 0
                if clean_id > 0 and clean_id != 5109999:
                    c_call = (e.callsign or "NOCALL").replace(",", " ").strip().upper()
                    c_name = (e.name or c_call).replace(",", " ").strip()
                    c_city = (e.city or "Indonesia").replace(",", " ").strip()
                    c_org = (e.organization or "ID").replace(",", " ").strip().upper()
                    lines.append(f"{clean_id},{c_call},{c_name},{c_city},{c_org},0")

            csv_text = "\n".join(lines) + "\n"

            for path in ["/app/data/subscriber_ids.csv", "/var/lib/dvswitch/subscriber_ids.csv"]:
                try:
                    os.makedirs(os.path.dirname(path), exist_ok=True)
                    with open(path, "w", encoding="utf-8") as f:
                        f.write(csv_text)
                except Exception:
                    pass

            # Notify Core Engine to refresh subscriber lookup cache
            try:
                import httpx
                async with httpx.AsyncClient(timeout=1.0) as client:
                    await client.post("http://core:5000/api/subscribers/reload")
            except Exception:
                pass
        except Exception:
            pass

    @staticmethod
    async def create_entry(session: AsyncSession, data: CallbookCreate) -> CallbookEntry:
        # Check if DMR ID already exists; if so, update (upsert)
        existing = await CallbookService.get_by_dmr_id(session, data.dmr_id)
        if existing:
            existing.callsign = data.callsign.upper()
            existing.name = data.name
            existing.city = data.city or ""
            existing.province_state = data.province_state or ""
            existing.country = data.country or "Indonesia"
            existing.organization = data.organization.upper() if data.organization else "KOMUNITAS"
            existing.organization_id = data.organization_id or data.callsign.upper()
            existing.remarks = data.remarks or ""
            existing.default_tg = data.default_tg if data.default_tg else 510
            if hasattr(data, "allowed_tgs_override"):
                existing.allowed_tgs_override = data.allowed_tgs_override
            await session.commit()
            await session.refresh(existing)
            await CallbookService.sync_subscribers_csv(session)
            return existing

        target_org_clean = data.organization.strip().upper() if data.organization else None
        if target_org_clean:
            try:
                await CallbookService.add_organization(session, code=target_org_clean, name=target_org_clean)
            except Exception:
                pass

        entry = CallbookEntry(**data.model_dump())
        session.add(entry)
        await session.commit()
        await session.refresh(entry)
        await CallbookService.sync_subscribers_csv(session)
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

        if imported_count > 0:
            await CallbookService.sync_subscribers_csv(session)

        return {
            "success": True,
            "imported_count": imported_count,
            "skipped_count": skipped_count
        }
