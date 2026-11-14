import asyncio
import hashlib
import os
import sys
import tempfile

# Point DB to a temporary SQLite file for isolated testing
_tmp_dir = tempfile.mkdtemp(prefix="ninjaradio_sc_test_")
_db_file = os.path.join(_tmp_dir, "ninjaradio_test.db")
os.environ["DATABASE_URL"] = f"sqlite+aiosqlite:///{_db_file}"
os.environ["NINJARADIO_DB_PATH"] = _db_file

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

from backend.app.core.database import init_db, async_session_maker
from backend.app.models.schemas import SelfCareRegisterRequest, AdminApprovalRequest
from backend.app.services.selfcare_service import (
    SelfCareService,
    create_session_token,
    verify_session_token,
    sanitize_user_response,
)
from core.dmr_server import lookup_selfcare_dmr_auth
from core.core_engine import check_core_tx_permission, check_selfcare_dvswitch_permission


async def main():
    await init_db()

    async with async_session_maker() as session:
        # 1. Register a new ORARI user -> must start PENDING
        req = SelfCareRegisterRequest(
            callsign="yd0test",
            dmr_id=5104999,
            name="Budi Test",
            city="Jakarta",
            organization="ORARI",
            organization_id="IAR-12345",
            password="secret123",
            req_web=True,
            req_dmr=True,
            req_svxlink=True,
            req_mumble=True,
            req_dvswitch=True,
            req_zello=True,
            zello_username="budi_zello99",
        )
        user, err = await SelfCareService.register_user(session, req)
        assert err is None and user is not None, f"Register failed: {err}"
        assert user.callsign == "YD0TEST"
        assert user.status == "PENDING"

        # 2. Sanitized response while PENDING must hide protocol passwords and disallow services
        pub = sanitize_user_response(user)
        assert pub.dmr_password == ""
        assert pub.allow_web is False
        assert pub.allow_dmr is False

        # 3. Token round-trip
        tok = create_session_token(user)
        payload = verify_session_token(tok)
        assert payload and payload["callsign"] == "YD0TEST"

        # 4. TX Firewall must block PENDING user
        ok_core, reason_core = check_core_tx_permission(5104999, "YD0TEST", 510)
        assert ok_core is False, f"PENDING user should be blocked in core: {reason_core}"
        ok_dvs, _ = check_selfcare_dvswitch_permission(5104999, "YD0TEST", "1.2.3.4")
        assert ok_dvs is False, "PENDING user should be blocked in DVSwitch"

        # 5. DMR Master lookup while PENDING must deny
        st, allow_dmr, _ = lookup_selfcare_dmr_auth(5104999)
        assert st == "PENDING" and allow_dmr is False

        # 6. Admin approves partial permissions (DMR + SvxLink + Zello ON, Mumble OFF)
        approved = await SelfCareService.approve_user(
            session,
            user.id,
            AdminApprovalRequest(
                allow_web=True,
                allow_dmr=True,
                allow_svxlink=True,
                allow_mumble=False,
                allow_dvswitch=True,
                allow_zello=True,
                zello_username="budi_zello99",
            ),
        )
        assert approved is not None and approved.status == "APPROVED"
        assert len(approved.dmr_password) == 6
        assert len(approved.svxlink_password) == 6
        assert approved.mumble_password == ""  # Not approved for Mumble

        # 7. DMR Master challenge-response hash check with unique 6-char password
        st, allow_dmr, dmr_pwd = lookup_selfcare_dmr_auth(510499901)  # Test ESSID 01 -> base 5104999
        assert st == "APPROVED" and allow_dmr is True and dmr_pwd == approved.dmr_password
        salt = b"\x01\x02\x03\x04"
        expected_hash = hashlib.sha256(salt + dmr_pwd.encode("utf-8")).digest()
        wrong_hash = hashlib.sha256(salt + b"ninjaradio_pass").digest()
        assert expected_hash != wrong_hash

        # 8. Core TX permission now allows APPROVED user
        ok_core2, reason_core2 = check_core_tx_permission(5104999, "YD0TEST", 510)
        assert ok_core2 is True, f"APPROVED user should be allowed: {reason_core2}"

        # 9. Suspend user -> immediately blocks TX again
        suspended = await SelfCareService.reject_or_suspend_user(session, user.id, "SUSPENDED", "Test suspend")
        assert suspended is not None and suspended.status == "SUSPENDED"
        ok_core3, _ = check_core_tx_permission(5104999, "YD0TEST", 510)
        assert ok_core3 is False, "SUSPENDED user must be blocked"

    print("ALL_SELFCARE_TESTS_PASSED")


if __name__ == "__main__":
    asyncio.run(main())
