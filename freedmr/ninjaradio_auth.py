import sqlite3
import os
import logging
from hashlib import sha256
from dmr_utils3.utils import bhex

logger = logging.getLogger("FreeDMR-Auth")

def verify_peer_login(radio_id_int: int, salt_bytes, sent_hash: bytes, default_passphrase_bytes) -> bool:
    """
    Dynamically authenticates FreeDMR repeaters/hotspots against NinjaRadio SQLite Self-Care:
    1. Looks up Radio ID in user_accounts (supports 7-digit ID, 8-digit or 9-digit hotspot SSIDs).
    2. Validates against:
       - Individual Self-Care DMR password (exact, UPPERCASE, lowercase, stripped)
       - Default server passphrase (e.g. ninjaradio_pass)
    3. Enforces account status (REJECTED or SUSPENDED are blocked).
    """
    # Ensure salt_bytes and default_passphrase_bytes are always bytes
    if isinstance(salt_bytes, str):
        salt_bytes = salt_bytes.encode("utf-8")
    else:
        salt_bytes = bytes(salt_bytes)

    if isinstance(default_passphrase_bytes, str):
        default_passphrase_bytes = default_passphrase_bytes.encode("utf-8")
    elif default_passphrase_bytes is not None:
        default_passphrase_bytes = bytes(default_passphrase_bytes)
    else:
        default_passphrase_bytes = b""

    # Candidate DMR IDs to match in database
    s_id = str(radio_id_int).strip()
    candidates = [radio_id_int]
    if len(s_id) >= 8:
        try:
            candidates.append(int(s_id[:7]))
        except ValueError:
            pass
    if radio_id_int > 9999999:
        candidates.append(radio_id_int // 100)
        candidates.append(radio_id_int // 10)

    db_paths = ["/app/data/ninjaradio.db", "/data/ninjaradio.db", "ninjaradio.db"]
    custom_pwd = None
    allow_dmr = True
    status = None

    for p in db_paths:
        if os.path.exists(p):
            try:
                with sqlite3.connect(p, timeout=2.0) as conn:
                    cur = conn.cursor()
                    placeholders = ",".join("?" for _ in candidates)
                    row = cur.execute(
                        f"SELECT dmr_password, allow_dmr, status FROM user_accounts WHERE dmr_id IN ({placeholders})",
                        tuple(candidates)
                    ).fetchone()
                    if row:
                        custom_pwd = str(row[0] or "").strip()
                        allow_dmr = bool(row[1])
                        status = str(row[2] or "").strip().upper()
                break
            except Exception as e:
                logger.debug(f"SQLite auth lookup error in {p}: {e}")

    # Enforce access permissions if account exists in Self-Care
    if status is not None:
        if status in ("REJECTED", "SUSPENDED"):
            logger.warning(f"[Self-Care-Auth] ❌ Denied Radio ID {radio_id_int}: Account status is {status}")
            return False
        if status == "APPROVED" and not allow_dmr:
            logger.warning(f"[Self-Care-Auth] ❌ Denied Radio ID {radio_id_int}: DMR service not permitted for account")
            return False

    # Collect valid passwords to verify against
    valid_passwords = set()
    if custom_pwd:
        valid_passwords.add(custom_pwd)
        valid_passwords.add(custom_pwd.upper())
        valid_passwords.add(custom_pwd.lower())
    if default_passphrase_bytes:
        def_str = default_passphrase_bytes.decode("utf-8", errors="ignore").strip()
        if def_str:
            valid_passwords.add(def_str)

    # Test candidate passwords
    for pwd in valid_passwords:
        if not pwd:
            continue
        try:
            calc_hash = bhex(sha256(salt_bytes + pwd.encode("utf-8")).hexdigest())
            if sent_hash == calc_hash:
                logger.info(f"[Self-Care-Auth] ✓ Authorized Radio ID {radio_id_int} (matched password variant)")
                return True
        except Exception as e:
            logger.debug(f"Hash calculation error for password: {e}")

    # Fallback direct check against default server passphrase bytes
    if default_passphrase_bytes:
        try:
            calc_default = bhex(sha256(salt_bytes + default_passphrase_bytes).hexdigest())
            if sent_hash == calc_default:
                logger.info(f"[Self-Care-Auth] ✓ Authorized Radio ID {radio_id_int} via default master passphrase")
                return True
        except Exception:
            pass

    logger.warning(f"[Self-Care-Auth] ❌ Authentication failed for Radio ID {radio_id_int}")
    return False
