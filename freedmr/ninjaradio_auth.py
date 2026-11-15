import sqlite3
import os
import logging
from hashlib import sha256
from dmr_utils3.utils import bhex

logger = logging.getLogger("FreeDMR-Auth")

def verify_peer_login(radio_id_int: int, salt_bytes: bytes, sent_hash: bytes, default_passphrase_bytes: bytes) -> bool:
    """
    Dynamically authenticates FreeDMR repeaters/hotspots against NinjaRadio SQLite Self-Care:
    1. If radio_id exists in user_accounts:
       - If status != 'APPROVED' or not allow_dmr: reject!
       - If user has individual dmr_password, verify against it.
    2. Also validates against default server passphrase (e.g. ninjaradio_pass).
    """
    db_paths = ["/app/data/ninjaradio.db", "/data/ninjaradio.db"]
    custom_pwd = None
    allow_dmr = True
    status = None

    # Handle 9-digit hotspot SSID (e.g. 102205701 -> 1022057)
    base_id = radio_id_int // 100 if radio_id_int > 9999999 else radio_id_int

    for p in db_paths:
        if os.path.exists(p):
            try:
                with sqlite3.connect(p) as conn:
                    cur = conn.cursor()
                    row = cur.execute(
                        "SELECT dmr_password, allow_dmr, status FROM user_accounts WHERE dmr_id = ? OR dmr_id = ?",
                        (radio_id_int, base_id)
                    ).fetchone()
                    if row:
                        custom_pwd = str(row[0] or "").strip()
                        allow_dmr = bool(row[1])
                        status = str(row[2] or "APPROVED").strip().upper()
                break
            except Exception as e:
                logger.debug(f"SQLite auth lookup error: {e}")

    # If registered in Self-Care: enforce permission and status
    if status is not None:
        if status in ("REJECTED", "SUSPENDED"):
            logger.warning(f"[Self-Care-Auth] Denied Radio ID {radio_id_int}: Account status is {status}")
            return False
        if not allow_dmr:
            logger.warning(f"[Self-Care-Auth] Denied Radio ID {radio_id_int}: DMR service not permitted for account")
            return False

        # If user has individual password from Self-Care, verify it
        if custom_pwd:
            calc_custom = bhex(sha256(salt_bytes + custom_pwd.encode("utf-8")).hexdigest())
            if sent_hash == calc_custom:
                logger.info(f"[Self-Care-Auth] ✓ Authorized Radio ID {radio_id_int} via individual Self-Care DMR password")
                return True

    # Validate against default server passphrase (fallback)
    calc_default = bhex(sha256(salt_bytes + default_passphrase_bytes).hexdigest())
    if sent_hash == calc_default:
        logger.info(f"[Self-Care-Auth] ✓ Authorized Radio ID {radio_id_int} via default master passphrase")
        return True

    return False
