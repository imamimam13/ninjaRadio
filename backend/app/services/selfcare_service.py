import os
import json
import hmac
import time
import base64
import hashlib
import secrets
from datetime import datetime
from typing import Optional, Tuple, Dict, Any, List

from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, or_

from backend.app.core.database import UserAccount, CallbookEntry, Organization
from backend.app.models.schemas import (
    SelfCareRegisterRequest,
    AdminApprovalRequest,
    UserAccountPublicResponse,
    UserAccountAdminResponse,
)

# Unambiguous 6-char alphanumeric alphabet (no 0/O, 1/I/L)
_PROTO_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
_TOKEN_TTL_SECONDS = 7 * 24 * 3600  # 7 days


def _get_token_secret() -> bytes:
    env_secret = os.getenv("SELFCARE_SECRET", "").strip()
    if env_secret:
        return env_secret.encode("utf-8")

    secret_paths = ["/app/data/.selfcare_secret", "./data/.selfcare_secret"]
    for p in secret_paths:
        if os.path.exists(p):
            try:
                with open(p, "r", encoding="utf-8") as f:
                    val = f.read().strip()
                    if val:
                        return val.encode("utf-8")
            except Exception:
                pass

    generated = secrets.token_hex(32)
    for p in secret_paths:
        try:
            os.makedirs(os.path.dirname(p), exist_ok=True)
            with open(p, "w", encoding="utf-8") as f:
                f.write(generated)
            break
        except Exception:
            continue
    return generated.encode("utf-8")


def hash_password(password: str) -> str:
    salt = secrets.token_hex(16)
    dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt.encode("utf-8"), 120_000)
    return f"pbkdf2_sha256${salt}${dk.hex()}"


def verify_password(password: str, stored_hash: str) -> bool:
    try:
        parts = stored_hash.split("$")
        if len(parts) != 3 or parts[0] != "pbkdf2_sha256":
            return False
        _, salt, expected_hex = parts
        dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt.encode("utf-8"), 120_000)
        return hmac.compare_digest(dk.hex(), expected_hex)
    except Exception:
        return False


def generate_protocol_password(length: int = 6) -> str:
    """Generates an easy-to-type 6-character alphanumeric password."""
    return "".join(secrets.choice(_PROTO_ALPHABET) for _ in range(length))


def create_session_token(user: UserAccount) -> str:
    payload = {
        "uid": user.id,
        "callsign": user.callsign,
        "dmr_id": user.dmr_id,
        "exp": int(time.time()) + _TOKEN_TTL_SECONDS,
    }
    raw = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    b64 = base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")
    sig = hmac.new(_get_token_secret(), b64.encode("ascii"), hashlib.sha256).hexdigest()
    return f"{b64}.{sig}"


def verify_session_token(token: str) -> Optional[Dict[str, Any]]:
    try:
        if not token or "." not in token:
            return None
        b64, sig = token.strip().split(".", 1)
        expected_sig = hmac.new(_get_token_secret(), b64.encode("ascii"), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(sig, expected_sig):
            return None
        padding = "=" * (-len(b64) % 4)
        payload = json.loads(base64.urlsafe_b64decode((b64 + padding).encode("ascii")).decode("utf-8"))
        if int(payload.get("exp", 0)) < int(time.time()):
            return None
        return payload
    except Exception:
        return None


def sanitize_user_response(user: UserAccount) -> UserAccountPublicResponse:
    """Masks protocol passwords unless the user is APPROVED and the service is allowed."""
    is_approved = user.status == "APPROVED"
    return UserAccountPublicResponse(
        id=user.id,
        callsign=user.callsign,
        dmr_id=user.dmr_id,
        name=user.name,
        city=user.city,
        organization=user.organization,
        organization_id=user.organization_id,
        verification_note=user.verification_note,
        status=user.status,
        rejected_reason=user.rejected_reason,
        approved_at=user.approved_at,
        created_at=user.created_at,
        req_web=user.req_web,
        req_dmr=user.req_dmr,
        req_svxlink=user.req_svxlink,
        req_mumble=user.req_mumble,
        req_dvswitch=user.req_dvswitch,
        req_zello=user.req_zello,
        allow_web=user.allow_web if is_approved else False,
        allow_dmr=user.allow_dmr if is_approved else False,
        allow_svxlink=user.allow_svxlink if is_approved else False,
        allow_mumble=user.allow_mumble if is_approved else False,
        allow_dvswitch=user.allow_dvswitch if is_approved else False,
        allow_zello=user.allow_zello if is_approved else False,
        dmr_password=user.dmr_password if (is_approved and user.allow_dmr) else "",
        svxlink_password=user.svxlink_password if (is_approved and user.allow_svxlink) else "",
        mumble_password=user.mumble_password if (is_approved and user.allow_mumble) else "",
        zello_username=user.zello_username or "",
        dvswitch_allowed_ip=user.dvswitch_allowed_ip if (is_approved and user.allow_dvswitch) else "",
    )


async def check_org_prefix(session: AsyncSession, callsign: str, org_code: str) -> Tuple[bool, str]:
    call_upper = (callsign or "").strip().upper()
    org_upper = (org_code or "").strip().upper()
    stmt = select(Organization).where(Organization.code == org_upper)
    res = await session.execute(stmt)
    org = res.scalar_one_or_none()
    if not org or not org.callsign_prefixes or org.callsign_prefixes.strip() == "*":
        return True, "Prefix bebas / sesuai organisasi"
    prefixes = [p.strip().upper() for p in org.callsign_prefixes.split(",") if p.strip()]
    if any(call_upper.startswith(p) for p in prefixes):
        return True, f"Sesuai prefix {org_upper} ({org.callsign_prefixes})"
    return False, f"Peringatan: Callsign {call_upper} tidak cocok dengan prefix standar {org_upper} ({org.callsign_prefixes})"


def trigger_protocol_reload() -> None:
    """Writes reload marker files so SvxReflector / Mumble bridge can pick up updates."""
    for marker_dir in ["/app/data", "./data"]:
        if os.path.isdir(marker_dir):
            try:
                with open(os.path.join(marker_dir, ".svxlink_reload"), "w", encoding="utf-8") as f:
                    f.write(str(time.time()))
                with open(os.path.join(marker_dir, ".mumble_sync"), "w", encoding="utf-8") as f:
                    f.write(str(time.time()))
                break
            except Exception:
                pass


class SelfCareService:
    @staticmethod
    async def register_user(session: AsyncSession, data: SelfCareRegisterRequest) -> Tuple[Optional[UserAccount], Optional[str]]:
        callsign = (data.callsign or "").strip().upper()
        if not callsign or len(callsign) < 3:
            return None, "Callsign minimal 3 karakter."
        if data.dmr_id < 1000:
            return None, "DMR ID tidak valid (minimal 4 digit)."

        existing = await session.execute(
            select(UserAccount).where(
                or_(UserAccount.callsign == callsign, UserAccount.dmr_id == data.dmr_id)
            )
        )
        found = existing.scalar_one_or_none()
        if found:
            if found.callsign == callsign:
                return None, f"Callsign {callsign} sudah terdaftar."
            return None, f"DMR ID {data.dmr_id} sudah terdaftar oleh {found.callsign}."

        user = UserAccount(
            callsign=callsign,
            dmr_id=data.dmr_id,
            name=(data.name or callsign).strip(),
            city=(data.city or "").strip(),
            organization=(data.organization or "ORARI").strip().upper(),
            organization_id=(data.organization_id or "").strip(),
            verification_note=(data.verification_note or "").strip(),
            password_hash=hash_password(data.password),
            status="PENDING",
            req_web=bool(data.req_web),
            req_dmr=bool(data.req_dmr),
            req_svxlink=bool(data.req_svxlink),
            req_mumble=bool(data.req_mumble),
            req_dvswitch=bool(data.req_dvswitch),
            req_zello=bool(data.req_zello),
            zello_username=(data.zello_username or "").strip(),
        )
        session.add(user)
        await session.commit()
        await session.refresh(user)
        return user, None

    @staticmethod
    async def authenticate_user(session: AsyncSession, identifier: str, password: str) -> Optional[UserAccount]:
        ident = (identifier or "").strip().upper()
        if not ident or not password:
            return None
        if ident.isdigit():
            stmt = select(UserAccount).where(
                or_(UserAccount.dmr_id == int(ident), UserAccount.callsign == ident)
            )
        else:
            stmt = select(UserAccount).where(UserAccount.callsign == ident)
        res = await session.execute(stmt)
        user = res.scalar_one_or_none()
        if not user or not verify_password(password, user.password_hash):
            return None
        return user

    @staticmethod
    async def list_registrations(session: AsyncSession, status_filter: Optional[str] = None) -> List[UserAccountAdminResponse]:
        stmt = select(UserAccount).order_by(UserAccount.created_at.desc())
        if status_filter and status_filter.upper() != "ALL":
            stmt = stmt.where(UserAccount.status == status_filter.upper())
        res = await session.execute(stmt)
        users = list(res.scalars().all())
        output: List[UserAccountAdminResponse] = []
        for u in users:
            prefix_ok, hint = await check_org_prefix(session, u.callsign, u.organization)
            base = sanitize_user_response(u).model_dump()
            # Admin always sees configured protocol passwords
            base["dmr_password"] = u.dmr_password or ""
            base["svxlink_password"] = u.svxlink_password or ""
            base["mumble_password"] = u.mumble_password or ""
            base["allow_web"] = u.allow_web
            base["allow_dmr"] = u.allow_dmr
            base["allow_svxlink"] = u.allow_svxlink
            base["allow_mumble"] = u.allow_mumble
            base["allow_dvswitch"] = u.allow_dvswitch
            base["allow_zello"] = u.allow_zello
            base["dvswitch_allowed_ip"] = u.dvswitch_allowed_ip or ""
            output.append(UserAccountAdminResponse(**base, prefix_valid=prefix_ok, prefix_hint=hint))
        return output

    @staticmethod
    async def approve_user(session: AsyncSession, user_id: int, req: AdminApprovalRequest) -> Optional[UserAccount]:
        res = await session.execute(select(UserAccount).where(UserAccount.id == user_id))
        user = res.scalar_one_or_none()
        if not user:
            return None

        user.status = "APPROVED"
        user.rejected_reason = ""
        user.approved_at = datetime.utcnow()

        user.allow_web = bool(req.allow_web)
        user.allow_dmr = bool(req.allow_dmr)
        user.allow_svxlink = bool(req.allow_svxlink)
        user.allow_mumble = bool(req.allow_mumble)
        user.allow_dvswitch = bool(req.allow_dvswitch)
        user.allow_zello = bool(req.allow_zello)

        if req.zello_username is not None:
            user.zello_username = req.zello_username.strip()

        # Auto-generate 6-char unique password per enabled protocol if not yet assigned
        if user.allow_dmr and not user.dmr_password:
            user.dmr_password = generate_protocol_password(6)
        if user.allow_svxlink and not user.svxlink_password:
            user.svxlink_password = generate_protocol_password(6)
        if user.allow_mumble and not user.mumble_password:
            user.mumble_password = generate_protocol_password(6)

        await SelfCareService._sync_callbook_entry(session, user, default_tg=req.default_tg or 510)
        await session.commit()
        await session.refresh(user)
        trigger_protocol_reload()
        return user

    @staticmethod
    async def reject_or_suspend_user(
        session: AsyncSession, user_id: int, new_status: str, reason: str
    ) -> Optional[UserAccount]:
        res = await session.execute(select(UserAccount).where(UserAccount.id == user_id))
        user = res.scalar_one_or_none()
        if not user:
            return None

        user.status = new_status
        user.rejected_reason = (reason or "").strip()
        user.allow_web = False
        user.allow_dmr = False
        user.allow_svxlink = False
        user.allow_mumble = False
        user.allow_dvswitch = False
        user.allow_zello = False

        # Block TX in callbook fallback as well
        cb_res = await session.execute(
            select(CallbookEntry).where(
                or_(CallbookEntry.dmr_id == user.dmr_id, CallbookEntry.callsign == user.callsign)
            )
        )
        cb = cb_res.scalar_one_or_none()
        if cb:
            cb.allowed_tgs_override = "NONE"

        await session.commit()
        await session.refresh(user)
        trigger_protocol_reload()
        return user

    @staticmethod
    async def _sync_callbook_entry(session: AsyncSession, user: UserAccount, default_tg: int = 510) -> None:
        cb_res = await session.execute(
            select(CallbookEntry).where(
                or_(CallbookEntry.dmr_id == user.dmr_id, CallbookEntry.callsign == user.callsign)
            )
        )
        cb = cb_res.scalar_one_or_none()
        zello_tag = f"zello:{user.zello_username}" if (user.allow_zello and user.zello_username) else ""
        remarks_val = zello_tag or "SelfCare Verified"

        if cb:
            cb.dmr_id = user.dmr_id
            cb.callsign = user.callsign
            cb.name = user.name
            cb.city = user.city
            cb.organization = user.organization
            cb.organization_id = user.organization_id
            if zello_tag:
                cb.remarks = zello_tag
            if cb.allowed_tgs_override == "NONE":
                cb.allowed_tgs_override = None
        else:
            session.add(
                CallbookEntry(
                    dmr_id=user.dmr_id,
                    callsign=user.callsign,
                    name=user.name,
                    city=user.city,
                    organization=user.organization,
                    organization_id=user.organization_id,
                    remarks=remarks_val,
                    default_tg=default_tg,
                )
            )
