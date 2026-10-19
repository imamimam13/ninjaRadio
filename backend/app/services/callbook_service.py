from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, or_, func
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
                {"code": "ORARI", "name": "ORARI", "description": "Organisasi Amatir Radio Indonesia", "allowed_tgs": "*", "callsign_prefixes": "YB,YC,YD,YE,7A,7B,7C,7D,7E,7F,7G,7H,7I,8A,8B,8C,8D,8E,8F,8G,8H,8I", "allow_tx": True},
                {"code": "BRIDI", "name": "BRIDI", "description": "BrandMeister & Digital Amateur Radio Indonesia", "allowed_tgs": "*", "callsign_prefixes": "BD,BD62", "allow_tx": True},
                {"code": "RAPI", "name": "RAPI", "description": "Radio Antar Penduduk Indonesia", "allowed_tgs": "*", "callsign_prefixes": "JZ", "allow_tx": True},
                {"code": "CB-11M", "name": "CB 11M", "description": "Citizen Band 11 Meter Indonesia", "allowed_tgs": "*", "callsign_prefixes": "11M,CB", "allow_tx": True},
                {"code": "KOMUNITAS", "name": "Komunitas / SAR", "description": "Relawan, SAR, & Radio Komunitas", "allowed_tgs": "*", "callsign_prefixes": "*", "allow_tx": True},
            ]
            for item in defaults:
                session.add(Organization(**item))
            await session.commit()
        else:
            # Auto-migrate existing organizations to open access (*) to eliminate any restrictive talkgroup locks
            stmt_all = select(Organization)
            res_all = await session.execute(stmt_all)
            all_orgs = res_all.scalars().all()
            changed = False
            for org in all_orgs:
                if org.allowed_tgs in ["5100,9990", "5109,9990", "5108,9990"]:
                    org.allowed_tgs = "*"
                    changed = True
            if changed:
                await session.commit()

    @staticmethod
    async def get_all_organizations(session: AsyncSession) -> List[Organization]:
        await CallbookService.seed_initial_organizations(session)
        stmt = select(Organization).order_by(Organization.id)
        result = await session.execute(stmt)
        return list(result.scalars().all())

    @staticmethod
    async def update_organization_rule(
        session: AsyncSession,
        code: str,
        allowed_tgs: str,
        allow_tx: bool = True,
        callsign_prefixes: Optional[str] = None
    ) -> Optional[Organization]:
        await CallbookService.seed_initial_organizations(session)
        stmt = select(Organization).where(Organization.code == code.strip().upper())
        result = await session.execute(stmt)
        org = result.scalar_one_or_none()
        if org:
            org.allowed_tgs = allowed_tgs.strip()
            org.allow_tx = allow_tx
            if callsign_prefixes is not None:
                org.callsign_prefixes = callsign_prefixes.strip().upper()
            await session.commit()
            await session.refresh(org)
            return org
        return None

    @staticmethod
    async def grant_user_permission(session: AsyncSession, identifier: str, allowed_tgs: str) -> Optional[CallbookEntry]:
        """
        Grants talkgroups permission to a user by callsign or DMR ID.
        If user does not exist in callbook, an entry is created.
        """
        ident = identifier.strip().upper()
        cb = None
        if ident.isdigit():
            cb = await CallbookService.get_by_dmr_id(session, int(ident))
        if not cb:
            cb = await CallbookService.get_by_callsign(session, ident)

        rule = allowed_tgs.strip()
        if cb:
            cb.allowed_tgs_override = rule
            await session.commit()
            await session.refresh(cb)
            return cb
        else:
            dmr_id_val = int(ident) if ident.isdigit() else (5100000 + (abs(hash(ident)) % 90000))
            new_entry = CallbookEntry(
                dmr_id=dmr_id_val,
                callsign=ident,
                name=f"Operator {ident}",
                organization="BRIDI" if ident.startswith("BD") else ("ORARI" if ident.startswith(("YB", "YC", "YD", "YE")) else "KOMUNITAS"),
                allowed_tgs_override=rule
            )
            session.add(new_entry)
            await session.commit()
            await session.refresh(new_entry)
            return new_entry

    @staticmethod
    async def revoke_user_permission(session: AsyncSession, identifier: str) -> Optional[CallbookEntry]:
        """
        Revokes specific talkgroups permission override, reverting to organization rules.
        """
        ident = identifier.strip().upper()
        cb = None
        if ident.isdigit():
            cb = await CallbookService.get_by_dmr_id(session, int(ident))
        if not cb:
            cb = await CallbookService.get_by_callsign(session, ident)
        if cb:
            cb.allowed_tgs_override = None
            await session.commit()
            await session.refresh(cb)
            return cb
        return None

    @staticmethod
    async def check_tx_permission(session: AsyncSession, dmr_id: int, callsign: str, target_tg: int) -> tuple:
        """
        Validates whether the operator is allowed to transmit on target_tg.
        Returns (is_allowed: bool, reason_or_category: str).
        Zero hardcoded prefixes: everything resolved dynamically from DB.
        """
        if target_tg == 9990:
            return True, "Echo Test"

        call_upper = (callsign or "").strip().upper()
        if not call_upper or call_upper in ["SWL-MONITOR", "MONITOR", "LISTENER", "GUEST", "TAMU"] or dmr_id == 0:
            return False, "Mode Monitor (SWL) tidak memiliki izin memancar. Gunakan profil berlisensi atau PIN Operator."

        # 1. Find callbook entry
        cb = None
        if dmr_id > 0:
            cb = await CallbookService.get_by_dmr_id(session, dmr_id)
        if not cb and call_upper:
            cb = await CallbookService.get_by_callsign(session, call_upper)

        # 2. Check user-specific override if present
        if cb and cb.allowed_tgs_override:
            rule_str = cb.allowed_tgs_override.strip()
            if rule_str == "*":
                return True, cb.organization or "User Override (*)"
            allowed_list = [int(x.strip()) for x in rule_str.split(",") if x.strip().isdigit()]
            if target_tg in allowed_list:
                return True, cb.organization or "User Override"
            else:
                return False, f"Operator {call_upper} hanya memiliki izin khusus pada TG: {rule_str}"

        # 3. Dynamic lookup of Organization from database
        await CallbookService.seed_initial_organizations(session)
        orgs = await CallbookService.get_all_organizations(session)

        org_code = cb.organization.upper() if (cb and cb.organization) else None
        target_org = None
        if org_code:
            target_org = next((o for o in orgs if o.code.upper() == org_code), None)

        # 4. If not assigned to an organization, dynamically match callsign prefixes from DB
        if not target_org:
            fallback_org = None
            for o in orgs:
                prefixes = [p.strip().upper() for p in (o.callsign_prefixes or "").split(",") if p.strip()]
                if "*" in prefixes:
                    fallback_org = o
                if any(call_upper.startswith(p) for p in prefixes if p != "*"):
                    target_org = o
                    break
            if not target_org:
                target_org = fallback_org or (orgs[0] if orgs else None)

        if not target_org:
            return True, "Open Access"

        if not target_org.allow_tx:
            return False, f"Organisasi {target_org.name} saat ini diset Monitor Only oleh Sysop."

        rule = (target_org.allowed_tgs or "*").strip()
        if rule == "*" or rule == "" or rule.upper() == "ALL":
            return True, target_org.name

        allowed_tgs = [int(x.strip()) for x in rule.split(",") if x.strip().isdigit()]
        if target_tg in allowed_tgs:
            return True, target_org.name

        return False, f"Stasiun {call_upper} ({target_org.name}) hanya diizinkan di TG [{rule}]. Akses ke TG {target_tg} diblokir oleh TX Firewall."

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
            stmt = stmt.where(
                or_(
                    func.upper(CallbookEntry.organization) == clean_org,
                    CallbookEntry.organization.ilike(clean_org)
                )
            )
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
                clean_id = int(e.dmr_id) if e.dmr_id else 0
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

        # Preload existing entries by dmr_id in a single query for fast bulk import from external URLs
        existing_res = await session.execute(select(CallbookEntry))
        existing_map: Dict[int, CallbookEntry] = {
            e.dmr_id: e for e in existing_res.scalars().all() if e.dmr_id is not None
        }

        if is_json:
            try:
                data = json.loads(trimmed)
                raw_rows: List[Any] = []
                if isinstance(data, list):
                    raw_rows = data
                elif isinstance(data, dict):
                    # Check common container keys
                    for key in ["users", "data", "callbook", "results", "records", "items", "subscribers", "entries", "contacts", "rows", "result", "list"]:
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
                        # Single user object or keyed dictionary (e.g. {"1022057": {"callsign": "BD62BOT", ...}})
                        if any(k in data for k in ["dmr_id", "radio_id", "id", "dmrid", "radioid", "callsign"]):
                            raw_rows = [data]
                        else:
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

                    existing = existing_map.get(parsed["dmr_id"])
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
                        existing_map[parsed["dmr_id"]] = entry
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

                    existing = existing_map.get(parsed["dmr_id"])
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
                        existing_map[parsed["dmr_id"]] = entry
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
