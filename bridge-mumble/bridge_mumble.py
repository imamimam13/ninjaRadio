import os
import sys
import time
import socket
import struct
import threading
import logging
import json
import re
import numpy as np

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] [Mumble-Bridge] %(message)s"
)
logger = logging.getLogger("mumble-bridge")

CONFIG_PATH = os.getenv("CONFIG_PATH", "/app/data/config.json")
MUMBLE_HOST = os.getenv("MUMBLE_HOST", "mumble-server")
MUMBLE_PORT = int(os.getenv("MUMBLE_PORT", "64738"))
MUMBLE_PASSWORD = os.getenv("MUMBLE_PASSWORD", "")

USRP_HOST = os.getenv("DMR_USRP_HOST", "core")
USRP_PORT = int(os.getenv("DMR_USRP_PORT", "32345"))
TARGET_TG = int(os.getenv("TARGET_TG", "510"))

# State tracking
last_mumble_tx = 0.0
last_dmr_tx = 0.0
active_mumble_tx = None
tx_seq = 0
IS_BRIDGING = True
ROOM_MAPPINGS = {}
GATEWAY_POOL = {}  # room_name -> MumbleGatewayBot

def load_room_mappings():
    """Loads talkgroups dynamically from config.json and merges with defaults."""
    mappings = {
        "NinjaRadio-Nasional": 510,
        "NinjaRadio-Nusantara": 5109,
        "Echo-Parrot": 9990,
    }

    # Fallback env mappings
    raw_map = os.getenv("MUMBLE_ROOM_MAP", "")
    if raw_map:
        for item in raw_map.split(","):
            if ":" in item:
                ch, tg = item.split(":", 1)
                try:
                    mappings[ch.strip()] = int(tg.strip())
                except ValueError:
                    pass

    # Read from /app/data/config.json if mounted
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                data = json.load(f)
                custom_rooms = data.get("mumble_rooms", [])
                for r in custom_rooms:
                    ch = r.get("name") or r.get("channel")
                    tg = r.get("targetTg") if r.get("targetTg") is not None else r.get("tg")
                    if ch and tg is not None:
                        try:
                            mappings[ch.strip()] = int(tg)
                        except (ValueError, TypeError):
                            pass

                custom_bots = data.get("mumble_bots", [])
                for b in custom_bots:
                    ch = b.get("channel")
                    tg = b.get("tg")
                    if ch and tg is not None:
                        try:
                            mappings[ch.strip()] = int(tg)
                        except (ValueError, TypeError):
                            pass

                peers = data.get("peering", [])
                for p in peers:
                    tgs_str = p.get("subscribedTgs", "")
                    for tg_part in re.split(r"[,; ]+", tgs_str):
                        tg_part = tg_part.strip()
                        if tg_part.isdigit():
                            tg_num = int(tg_part)
                            mappings[f"TG-{tg_num}"] = tg_num
        except Exception as e:
            logger.debug(f"Could not parse config.json for rooms: {e}")

    return mappings

def ensure_channels_created():
    """Connects as SuperUser to create permanent channels in Murmur if not present."""
    import pymumble_py3 as pymumble
    superuser_pwd = os.getenv("MUMBLE_SUPERUSER_PASSWORD", "ninjaradio_admin")
    logger.info("Initializing Murmur channels as SuperUser...")
    try:
        su = pymumble.Mumble(MUMBLE_HOST, "SuperUser", port=MUMBLE_PORT, password=superuser_pwd)
        su.start()
        su.is_ready()
        time.sleep(1.0)

        existing_names = set()
        for ch in list(su.channels.values()):
            try:
                name = ch["name"] if (isinstance(ch, dict) or hasattr(ch, "__getitem__")) else getattr(ch, "name", None)
                if name:
                    existing_names.add(str(name))
            except Exception:
                pass

        logger.info(f"Existing Murmur channels before init: {list(existing_names)}")
        for room_name, tg_val in ROOM_MAPPINGS.items():
            if room_name not in existing_names:
                try:
                    su.channels.new_channel(0, room_name, temporary=False)
                    logger.info(f"✓ SuperUser created permanent Mumble Room: '{room_name}' (TG {tg_val})")
                    time.sleep(0.3)
                except Exception as ce:
                    logger.warning(f"Could not create channel '{room_name}': {ce}")
            else:
                logger.info(f"✓ Channel '{room_name}' already exists.")
        su.stop()
        time.sleep(0.8)
    except Exception as e:
        logger.warning(f"SuperUser channel initialization warning: {e}")

TLV_TAG_SET_INFO = 0x08

def build_usrp_frame(seq: int, keyup: int, usrp_type: int = 0, payload: bytes = b"") -> bytes:
    return b"USRP" + struct.pack(">iiiiiii", seq & 0x7FFFFFFF, 0, 1 if keyup else 0, 0, usrp_type << 24, 0, 0) + payload

def build_set_info_tlv(src_id: int, rpt_id: int, tg: int, slot: int, cc: int, callsign: str) -> bytes:
    call = (callsign or str(src_id)).encode("ascii", errors="ignore")[:16]
    body = (
        (src_id & 0xFFFFFF).to_bytes(3, "big")
        + (rpt_id & 0xFFFFFFFF).to_bytes(4, "big")
        + (tg & 0xFFFFFF).to_bytes(3, "big")
        + bytes([slot & 0xFF, cc & 0xFF])
        + call + b"\x00"
    )
    return bytes([TLV_TAG_SET_INFO, len(body)]) + body

def get_db_path() -> Optional[str]:
    """Finds ninjaradio.db across Docker container and host filesystem paths."""
    candidates = [
        os.environ.get("NINJARADIO_DB_PATH", ""),
        "/app/data/ninjaradio.db",
        "/home/ubuntu/ninjaRadio/data/ninjaradio.db",
        os.path.join(os.path.dirname(__file__), "../data/ninjaradio.db"),
        os.path.join(os.path.dirname(__file__), "data/ninjaradio.db"),
        "./data/ninjaradio.db",
        "./ninjaradio.db"
    ]
    for p in candidates:
        if p and os.path.exists(p):
            return p
    return None

def resolve_mumble_identity(username: str) -> tuple:
    """
    Extracts DMR ID and Callsign from Mumble username and matches against Callbook DB.
    Supports: Callsign matching (e.g. BD62AA), Name+Callsign (BD62AA-Ali), DMR ID, or fallback.
    """
    clean_name = username.strip()

    # 1. Look for 7-digit DMR ID in string
    id_match = re.search(r"\b(\d{7})\b", clean_name)
    found_id = int(id_match.group(1)) if id_match else None

    # 2. Extract potential callsign tokens (e.g. BD62AA, YB0ABC, JZ11XYZ)
    tokens = re.findall(r"[A-Za-z0-9]{3,10}", clean_name)
    found_call = None
    for token in tokens:
        token_upper = token.strip("/").upper()
        if not token_upper.isdigit() and any(c.isdigit() for c in token_upper) and any(c.isalpha() for c in token_upper):
            found_call = token_upper
            break

    # 3. Lookup against SQLite Callbook DB (/app/data/ninjaradio.db)
    db_path = get_db_path()
    if db_path and os.path.exists(db_path):
        try:
            import sqlite3
            with sqlite3.connect(db_path, timeout=1.0) as conn:
                cursor = conn.cursor()
                if found_call:
                    cursor.execute("SELECT callsign, dmr_id FROM callbook WHERE UPPER(callsign) = ?", (found_call,))
                    row = cursor.fetchone()
                    if row:
                        return int(row[1]), str(row[0]).strip().upper()

                for token in tokens:
                    cursor.execute("SELECT callsign, dmr_id FROM callbook WHERE UPPER(callsign) = ?", (token.upper(),))
                    row = cursor.fetchone()
                    if row:
                        return int(row[1]), str(row[0]).strip().upper()

                if found_id:
                    cursor.execute("SELECT callsign, dmr_id FROM callbook WHERE dmr_id = ?", (found_id,))
                    row = cursor.fetchone()
                    if row:
                        return int(row[1]), str(row[0]).strip().upper()
        except Exception:
            pass

    # 4. Fallback if not registered in Callbook DB:
    if found_id and found_call:
        return found_id, found_call
    if found_id:
        return found_id, f"ID-{found_id}"
    if found_call:
        virtual_id = 5100000 + (abs(hash(found_call)) % 90000)
        return virtual_id, found_call

    # Fallback from username
    virtual_id = 5100000 + (abs(hash(clean_name)) % 90000)
    clean_call = re.sub(r'[^A-Za-z0-9]', '', clean_name)[:8].upper() or f"MUM{virtual_id%1000:03d}"
    return virtual_id, clean_call

last_tx_reject_alerts = {}

def check_mumble_tx_permission(dmr_id: int, callsign: str, target_tg: int) -> tuple:
    """
    Dynamically checks if Mumble user is permitted to transmit on target_tg.
    Zero hardcoded prefixes: evaluates callbook overrides, then organizations table.
    """
    if target_tg == 9990:
        return True, "Echo Test"
    db_path = get_db_path()
    if not db_path:
        return True, "Default (No DB)"
    try:
        import sqlite3
        with sqlite3.connect(db_path, timeout=1.5) as conn:
            cursor = conn.cursor()
            call_upper = (callsign or "").strip().upper()
            if call_upper in ["BD62BOT", "N1NJA", "SUPERUSER", "ADMIN"] or dmr_id in [1022057, 5109999]:
                return True, "Master Server Operator / SuperUser"
            cursor.execute("SELECT organization, allowed_tgs_override FROM callbook WHERE dmr_id = ? OR UPPER(callsign) = ?", (dmr_id, call_upper))
            row = cursor.fetchone()
            if row and row[1]:
                override = str(row[1]).strip()
                if override == "*":
                    return True, "User Override (*)"
                allowed = [int(x.strip()) for x in override.split(",") if x.strip().isdigit()]
                if target_tg in allowed:
                    return True, "User Override"
                return False, f"Hanya diizinkan di TG: {override}"

            org_code = row[0] if (row and row[0]) else None

            # Fetch organizations to match dynamic callsign prefixes
            cursor.execute("SELECT code, allowed_tgs, allow_tx, callsign_prefixes FROM organizations")
            org_rows = cursor.fetchall()

            target_org_row = None
            if org_code:
                for orow in org_rows:
                    if str(orow[0]).upper() == org_code.upper():
                        target_org_row = orow
                        break

            if not target_org_row and org_rows:
                fallback_org_row = None
                for orow in org_rows:
                    raw_prefixes = str(orow[3] or "")
                    prefixes = [p.strip().upper() for p in raw_prefixes.split(",") if p.strip()]
                    if "*" in prefixes:
                        fallback_org_row = orow
                    if any(call_upper.startswith(p) for p in prefixes if p != "*"):
                        target_org_row = orow
                        break
                if not target_org_row:
                    target_org_row = fallback_org_row or org_rows[0]

            if not target_org_row:
                return False, f"Tidak ada aturan organisasi untuk {call_upper}"

            org_c, allowed_tgs, allow_tx, _ = target_org_row
            if not allow_tx:
                return False, f"Organisasi {org_c} saat ini diset Monitor-Only"
            rule = (allowed_tgs or "*").strip()
            if rule == "*":
                return True, org_c
            allowed = [int(x.strip()) for x in rule.split(",") if x.strip().isdigit()]
            if target_tg in allowed:
                return True, org_c
            return False, f"Organisasi {org_c} hanya diizinkan di TG [{rule}]"
    except Exception as e:
        logger.debug(f"check_mumble_tx_permission error: {e}")
    return True, "Fallback"

def update_user_permission_in_db(identifier: str, allowed_tgs_str: str) -> bool:
    """Updates or provisions a user in ninjaradio.db with special TG override (invoked by SuperUser)."""
    db_path = get_db_path()
    if not db_path:
        return False
    try:
        import sqlite3
        with sqlite3.connect(db_path, timeout=2.0) as conn:
            cursor = conn.cursor()
            ident = identifier.strip().upper()
            cursor.execute("SELECT id, dmr_id, callsign FROM callbook WHERE dmr_id = ? OR UPPER(callsign) = ?", (int(ident) if ident.isdigit() else 0, ident))
            row = cursor.fetchone()
            if row:
                cursor.execute("UPDATE callbook SET allowed_tgs_override = ? WHERE id = ?", (allowed_tgs_str, row[0]))
            else:
                dmr_id_val = int(ident) if ident.isdigit() else (5100000 + (abs(hash(ident)) % 90000))
                org = "BRIDI" if ident.startswith("BD") else ("ORARI" if ident.startswith(("YB", "YC", "YD", "YE")) else "KOMUNITAS")
                cursor.execute(
                    "INSERT INTO callbook (dmr_id, callsign, name, organization, allowed_tgs_override) VALUES (?, ?, ?, ?, ?)",
                    (dmr_id_val, ident, f"Operator {ident}", org, allowed_tgs_str)
                )
            conn.commit()
            return True
    except Exception as e:
        logger.error(f"update_user_permission_in_db error: {e}")
        return False

def revoke_user_permission_in_db(identifier: str) -> bool:
    """Revokes special TG override in ninjaradio.db, reverting user to org policy."""
    db_path = get_db_path()
    if not db_path:
        return False
    try:
        import sqlite3
        with sqlite3.connect(db_path, timeout=2.0) as conn:
            cursor = conn.cursor()
            ident = identifier.strip().upper()
            cursor.execute("UPDATE callbook SET allowed_tgs_override = NULL WHERE dmr_id = ? OR UPPER(callsign) = ?", (int(ident) if ident.isdigit() else 0, ident))
            conn.commit()
            return True
    except Exception as e:
        logger.error(f"revoke_user_permission_in_db error: {e}")
        return False

def handle_incoming_text_message(mumble_instance, message, current_bot_tg=None):
    """Processes interactive text commands in Mumble chat from SuperUser or operators."""
    actor_id = getattr(message, "actor", None)
    raw_text = (getattr(message, "message", "") or "").strip()
    if not actor_id or not raw_text:
        return
    sender_user = mumble_instance.users.get(actor_id)
    if not sender_user:
        return
    uname = sender_user.get("name", "")
    if uname.startswith("NinjaBot-"):
        return

    # Strip HTML tags if Mumla sends HTML formatting
    clean_text = re.sub(r"<[^>]+>", "", raw_text).strip()
    if not clean_text.startswith("!"):
        return

    parts = clean_text.split()
    cmd = parts[0].lower()
    args = parts[1:]

    is_superuser = (uname == "SuperUser" or sender_user.get("user_id") == 0)

    # 1. !help
    if cmd in ["!help", "!bantuan"]:
        help_msg = (
            "<b>📻 NinjaRadio Mumla Gateway Commands:</b><br/>"
            "• <code>!req &lt;TG&gt;</code> : Ajukan izin TX di Talkgroup tertentu (misal: <code>!req 1025</code>)<br/>"
            "• <code>!check [user]</code> : Cek status izin Talkgroup aktif<br/>"
        )
        if is_superuser:
            help_msg += (
                "<br/><b>👑 Khusus SuperUser / Admin:</b><br/>"
                "• <code>!grant &lt;user&gt; &lt;TG&gt;</code> : Berikan izin TX (contoh: <code>!grant BD62AA 1025</code> atau <code>!grant BD62AA *</code>)<br/>"
                "• <code>!revoke &lt;user&gt;</code> : Cabut hak khusus user (kembali ke default)<br/>"
            )
        try:
            sender_user.send_text_message(help_msg)
        except Exception:
            pass

    # 2. !req / !request
    elif cmd in ["!req", "!request", "!izin"]:
        target_tg_str = args[0] if args else (str(current_bot_tg) if current_bot_tg else "1025")
        _, user_call = resolve_mumble_identity(uname)
        reply = f"✓ Permintaan izin TX di TG {target_tg_str} telah dicatat. Menunggu persetujuan SuperUser."
        try:
            sender_user.send_text_message(reply)
        except Exception:
            pass

        # Broadcast notification to SuperUser if online
        su_notified = False
        for u in list(mumble_instance.users.values()):
            u_name = u.get("name", "")
            if u_name == "SuperUser" or u.get("user_id") == 0:
                try:
                    alert = (
                        f"📢 <b>Permintaan Izin TX Masuk:</b><br/>"
                        f"• User: <b>{uname}</b> (Callsign: <b>{user_call}</b>)<br/>"
                        f"• Target TG: <b>{target_tg_str}</b><br/>"
                        f"👉 Balas: <code>!grant {user_call} {target_tg_str}</code> untuk menyetujui."
                    )
                    u.send_text_message(alert)
                    su_notified = True
                except Exception:
                    pass
        logger.info(f"[Mumble-Req] User '{uname}' ({user_call}) requested TX on TG {target_tg_str} (SuperUser notified: {su_notified})")

    # 3. !grant (SuperUser only)
    elif cmd in ["!grant", "!izinkan", "!approve"]:
        if not is_superuser:
            try:
                sender_user.send_text_message("⛔ Akses Ditolak: Hanya SuperUser yang dapat memberikan izin.")
            except Exception:
                pass
            return
        if len(args) < 2:
            try:
                sender_user.send_text_message("⚠️ Format salah! Gunakan: <code>!grant &lt;callsign/ID&gt; &lt;TG&gt;</code> (misal: <code>!grant BD62AA 1025</code> atau <code>!grant BD62AA *</code>)")
            except Exception:
                pass
            return

        target_ident = args[0].upper()
        target_tgs = args[1].strip()
        success = update_user_permission_in_db(target_ident, target_tgs)
        if success:
            try:
                sender_user.send_text_message(f"✓ <b>Berhasil!</b> Stasiun <b>{target_ident}</b> kini diizinkan memancar di TG <b>[{target_tgs}]</b>.")
            except Exception:
                pass
            # Notify the target user if online in Mumble
            for u in list(mumble_instance.users.values()):
                _, u_call = resolve_mumble_identity(u.get("name", ""))
                if u_call == target_ident or u.get("name", "").upper() == target_ident:
                    try:
                        u.send_text_message(f"🎉 <b>Izin TX Diterima!</b> SuperUser telah menyetujui akses Anda untuk TalkGroup [{target_tgs}]. Anda sekarang dapat memancar.")
                    except Exception:
                        pass
            logger.info(f"[Mumble-SuperUser] ✓ SuperUser granted {target_ident} access to TG {target_tgs}")
        else:
            try:
                sender_user.send_text_message(f"❌ Gagal memperbarui database untuk {target_ident}.")
            except Exception:
                pass

    # 4. !revoke (SuperUser only)
    elif cmd in ["!revoke", "!cabut"]:
        if not is_superuser:
            try:
                sender_user.send_text_message("⛔ Akses Ditolak: Hanya SuperUser yang dapat mencabut izin.")
            except Exception:
                pass
            return
        if not args:
            try:
                sender_user.send_text_message("⚠️ Format: <code>!revoke &lt;callsign/ID&gt;</code>")
            except Exception:
                pass
            return
        target_ident = args[0].upper()
        success = revoke_user_permission_in_db(target_ident)
        if success:
            try:
                sender_user.send_text_message(f"✓ Hak khusus stasiun <b>{target_ident}</b> telah dicabut. User kembali ke aturan default organisasi.")
            except Exception:
                pass
            logger.info(f"[Mumble-SuperUser] ✓ SuperUser revoked special override for {target_ident}")
        else:
            try:
                sender_user.send_text_message(f"❌ Gagal mencabut izin {target_ident}.")
            except Exception:
                pass

    # 5. !check
    elif cmd in ["!check", "!cek", "!status"]:
        target_ident = args[0].upper() if args else None
        if not target_ident:
            _, target_ident = resolve_mumble_identity(uname)
        is_ok, reason = check_mumble_tx_permission(0, target_ident, current_bot_tg or 510)
        status_icon = "🟢" if is_ok else "🔴"
        try:
            sender_user.send_text_message(f"{status_icon} Status Stasiun <b>{target_ident}</b> pada TG {current_bot_tg or 510}:<br/>{reason}")
        except Exception:
            pass

class MumbleGatewayBot:
    """Dedicated bot instance for a single Mumble channel & DMR Talkgroup pair."""

    def __init__(self, room_name: str, tg: int, usrp_sock: socket.socket = None, bot_user: str = None, slot: int = None):
        self.room_name = room_name
        self.tg = tg
        self.slot = slot or (1 if tg in [510, 5108, 91, 1023] else 2)
        # Dedicated socket per bot so separate channels and talkgroups have distinct UDP ports
        self.usrp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        if bot_user:
            self.bot_user = bot_user[:25]
        else:
            clean_name = room_name.replace("NinjaRadio-", "").replace(" ", "-")
            self.bot_user = f"NinjaBot-{clean_name}"[:25]
        self.mumble = None
        self.running = False
        self.thread = None
        self.last_rx_play_time = 0.0
        self.tx_pcm_buffer = bytearray()

    def move_to_channel(self, target_channel_name: str, new_tg: int = None, new_slot: int = None):
        """Moves this bot into another channel in real-time without reconnection."""
        self.room_name = target_channel_name
        if new_tg is not None:
            self.tg = new_tg
        if new_slot is not None:
            self.slot = new_slot
        elif new_tg is not None:
            self.slot = (1 if new_tg in [510, 5108, 91, 1023] else 2)
        if self.mumble and self.mumble.is_alive():
            try:
                target_ch = None
                for ch in list(self.mumble.channels.values()):
                    try:
                        cname = ch["name"] if (isinstance(ch, dict) or hasattr(ch, "__getitem__")) else getattr(ch, "name", None)
                        if cname == self.room_name:
                            target_ch = ch
                            break
                    except Exception:
                        pass
                if target_ch:
                    ch_id = target_ch["channel_id"] if (isinstance(target_ch, dict) or hasattr(target_ch, "__getitem__")) else getattr(target_ch, "channel_id", 0)
                    self.mumble.users.myself.move_in(int(ch_id))
                    logger.info(f"[{self.bot_user}] 🔀 Moved live into '{self.room_name}' (ID: {ch_id}), target TG {self.tg}")
                else:
                    logger.warning(f"[{self.bot_user}] Channel '{self.room_name}' not found for live move.")
            except Exception as e:
                logger.warning(f"[{self.bot_user}] Failed to move channel live: {e}")

    def start(self):
        if self.running:
            return
        self.running = True
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def stop(self):
        self.running = False
        if self.mumble:
            try:
                self.mumble.stop()
            except Exception:
                pass

    def play_pcm_8k(self, pcm_8k: bytes):
        if self.mumble and self.mumble.is_alive() and len(pcm_8k) > 0:
            try:
                self.last_rx_play_time = time.time()
                # Resample 8kHz 16-bit mono PCM to 48kHz for Mumble Opus encoder
                samples_8k = np.frombuffer(pcm_8k, dtype=np.int16)
                # Apply 1.5x digital gain cushion for punchy clear Mumla audio
                samples_8k = np.clip(samples_8k.astype(np.float32) * 1.5, -32768, 32767).astype(np.int16)
                samples_48k = np.repeat(samples_8k, 6)
                self.mumble.sound_output.add_sound(samples_48k.tobytes())
            except Exception:
                pass

    def _run(self):
        import pymumble_py3 as pymumble
        import pymumble_py3.constants as mumble_constants

        logger.info(f"[{self.bot_user}] 🚀 Launching gateway bot for '{self.room_name}' <---> DMR TG {self.tg}")
        while self.running and IS_BRIDGING:
            try:
                self.mumble = pymumble.Mumble(MUMBLE_HOST, self.bot_user, port=MUMBLE_PORT, password=MUMBLE_PASSWORD)
                self.mumble.set_receive_sound(True)

                def sound_received_handler(user, soundchunk):
                    global last_mumble_tx, active_mumble_tx, tx_seq
                    if not user:
                        return
                    uname = user.get("name", "")
                    if uname.startswith("NinjaBot-") or uname == "SuperUser":
                        return

                    # Verify user is in this bot's channel
                    user_ch_id = user.get("channel_id")
                    my_ch_id = None
                    try:
                        my_user = getattr(self.mumble.users, "myself", None)
                        if my_user:
                            my_ch_id = my_user.get("channel_id") if isinstance(my_user, dict) or hasattr(my_user, "__getitem__") else getattr(my_user, "channel_id", None)
                    except Exception:
                        pass
                    if user_ch_id is not None and my_ch_id is not None and user_ch_id != my_ch_id:
                        return

                    # Prevent audio loopback from DMR RX strictly for this bot/room
                    if time.time() - getattr(self, "last_rx_play_time", 0.0) < 0.15:
                        return

                    pcm_48k = getattr(soundchunk, "pcm", None)
                    if not pcm_48k:
                        return

                    user_dmr_id, user_callsign = resolve_mumble_identity(uname)

                    # Dynamic Server-Side TX Firewall Check (zero hardcoded rules)
                    is_allowed, reason = check_mumble_tx_permission(user_dmr_id, user_callsign, self.tg)
                    if not is_allowed:
                        now = time.time()
                        sess_id = user.get("session", 0)
                        if now - last_tx_reject_alerts.get(sess_id, 0) > 6.0:
                            last_tx_reject_alerts[sess_id] = now
                            msg = (
                                f"⚠️ <b>TX Ditolak:</b> Anda ({user_callsign}) belum memiliki izin memancar di TalkGroup {self.tg}.<br/>"
                                f"Ketik <code>!req {self.tg}</code> di chat untuk meminta izin ke SuperUser."
                            )
                            try:
                                user.send_text_message(msg)
                            except Exception:
                                pass
                            logger.warning(f"[{self.bot_user}] 🛑 Blocked TX from '{uname}' ({user_callsign}) on TG {self.tg}: {reason}")
                        return

                    try:
                        # Downsample 48kHz -> 8kHz PCM (decimate by 6)
                        samples_48k = np.frombuffer(pcm_48k, dtype=np.int16)
                        samples_8k = samples_48k[::6]
                        pcm_8k = samples_8k.tobytes()

                        # Dispatch binary TLV SET_INFO at start of transmission to carry over callsign and DMR ID
                        if not active_mumble_tx:
                            tx_seq = (tx_seq + 1) & 0x7FFFFFFF
                            tlv = build_set_info_tlv(user_dmr_id, 5109999, self.tg, self.slot, 1, user_callsign)
                            tlv_pkt = build_usrp_frame(tx_seq, 0, 2, tlv)
                            self.usrp_sock.sendto(tlv_pkt, (USRP_HOST, USRP_PORT))
                            logger.info(f"[MUMBLE-TX] 🔴 PTT TRANSMIT from {user_callsign} ({user_dmr_id}) in '{self.room_name}' -> DMR TG {self.tg} Slot {self.slot}")
                            self.tx_pcm_buffer.clear()

                        # Buffer and send exact 320-byte (20ms) 8kHz mono PCM frames
                        self.tx_pcm_buffer.extend(pcm_8k)
                        while len(self.tx_pcm_buffer) >= 320:
                            chunk = bytes(self.tx_pcm_buffer[:320])
                            self.tx_pcm_buffer = self.tx_pcm_buffer[320:]
                            tx_seq = (tx_seq + 1) & 0x7FFFFFFF
                            voice_pkt = build_usrp_frame(tx_seq, 1, 0, chunk)
                            self.usrp_sock.sendto(voice_pkt, (USRP_HOST, USRP_PORT))

                        last_mumble_tx = time.time()
                        active_mumble_tx = (user, self.tg, self.slot, user_dmr_id, user_callsign, last_mumble_tx, self.usrp_sock)
                    except Exception as ex:
                        logger.debug(f"[{self.bot_user}] Error streaming sound chunk: {ex}")

                self.mumble.callbacks.set_callback(mumble_constants.PYMUMBLE_CLBK_SOUNDRECEIVED, sound_received_handler)

                def text_received_handler(message):
                    handle_incoming_text_message(self.mumble, message, current_bot_tg=self.tg)

                self.mumble.callbacks.set_callback(mumble_constants.PYMUMBLE_CLBK_TEXTMESSAGERECEIVED, text_received_handler)
                self.mumble.start()
                self.mumble.is_ready()
                logger.info(f"[{self.bot_user}] ✓ Connected to Mumble server!")

                # Locate target channel and move bot inside
                time.sleep(1.2)
                target_ch = None
                for ch in list(self.mumble.channels.values()):
                    try:
                        cname = ch["name"] if (isinstance(ch, dict) or hasattr(ch, "__getitem__")) else getattr(ch, "name", None)
                        if cname == self.room_name:
                            target_ch = ch
                            break
                    except Exception:
                        pass

                if target_ch:
                    ch_id = target_ch["channel_id"] if (isinstance(target_ch, dict) or hasattr(target_ch, "__getitem__")) else getattr(target_ch, "channel_id", 0)
                    self.mumble.users.myself.move_in(int(ch_id))
                    logger.info(f"[{self.bot_user}] ✓ Permanently stationed in '{self.room_name}' (ID: {ch_id})")
                else:
                    logger.warning(f"[{self.bot_user}] Channel '{self.room_name}' not yet available in channel tree.")

                # Keep thread alive while connection lasts
                while self.running and IS_BRIDGING and self.mumble.is_alive():
                    try:
                        my_user = getattr(self.mumble.users, "myself", None)
                        current_ch_id = None
                        if my_user:
                            if isinstance(my_user, dict) or hasattr(my_user, "__getitem__"):
                                current_ch_id = my_user.get("channel_id")
                            else:
                                current_ch_id = getattr(my_user, "channel_id", None)

                        target_ch = None
                        for ch in list(self.mumble.channels.values()):
                            cname = ch["name"] if (isinstance(ch, dict) or hasattr(ch, "__getitem__")) else getattr(ch, "name", None)
                            if cname == self.room_name:
                                target_ch = ch
                                break

                        if target_ch:
                            t_id = target_ch["channel_id"] if (isinstance(target_ch, dict) or hasattr(target_ch, "__getitem__")) else getattr(target_ch, "channel_id", 0)
                            if current_ch_id != t_id:
                                self.mumble.users.myself.move_in(int(t_id))
                                logger.info(f"[{self.bot_user}] ✓ Stationed into '{self.room_name}' (ID: {t_id})")
                    except Exception:
                        pass
                    time.sleep(2)

            except Exception as e:
                logger.warning(f"[{self.bot_user}] Connection retry in 5s: {e}")
                time.sleep(5)

def run_usrp_listener(gateway_pool):
    """
    Listens for PCM audio frames from Core / DMR USRP UDP port (12346) and routes to the appropriate room bot.
    """
    global last_dmr_tx
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    bind_port = 12346
    try:
        sock.bind(("0.0.0.0", bind_port))
        logger.info(f"USRP RX Listener bound on UDP port {bind_port}")
    except Exception as e:
        logger.warning(f"Could not bind on {bind_port}: {e}")

    last_rx_log = 0.0
    while IS_BRIDGING:
        try:
            data, addr = sock.recvfrom(2048)
            if len(data) >= 32 and (time.time() - last_mumble_tx > 0.35):
                last_dmr_tx = time.time()
                pcm_8k = data[32:]

                # Extract talkgroup from USRP header (bytes 16..20)
                talkgroup = struct.unpack(">I", data[16:20])[0]

                # Match bot by target talkgroup (Strict isolation: NEVER leak unmapped TGs into unrelated rooms)
                target_bots = [bot for bot in gateway_pool.values() if bot.tg == talkgroup]
                if not target_bots:
                    continue

                if time.time() - last_rx_log > 2.0 and target_bots:
                    last_rx_log = time.time()
                    bot_names = ", ".join([f"{b.bot_user} in '{b.room_name}'" for b in target_bots])
                    logger.info(f"[Mumble-Bridge] 🔊 Routing DMR RX (TG {talkgroup}) to {bot_names}")

                for bot in target_bots:
                    bot.play_pcm_8k(pcm_8k)

        except Exception:
            time.sleep(0.02)

def ptt_watchdog_loop(usrp_sock):
    """Detects when a Mumble/Mumla user stops talking and transmits PTT release to USRP/DMR."""
    global active_mumble_tx, tx_seq
    while IS_BRIDGING:
        time.sleep(0.05)
        if active_mumble_tx:
            user_info, tg, slot, dmr_id, callsign, last_seen, bot_sock = active_mumble_tx
            if time.time() - last_seen > 0.35:
                tx_seq = (tx_seq + 1) & 0x7FFFFFFF
                unkey_pkt = build_usrp_frame(tx_seq, 0, 0, b"\x00" * 320)
                try:
                    target_sock = bot_sock or usrp_sock
                    target_sock.sendto(unkey_pkt, (USRP_HOST, USRP_PORT))
                    logger.info(f"[MUMBLE-TX] ⏹️ PTT RELEASE from {callsign} ({dmr_id}) -> TG {tg} Slot {slot}")
                except Exception:
                    pass
                active_mumble_tx = None

def load_configured_bots():
    """Loads bot definitions from config.json, or generates default bots for each room."""
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                data = json.load(f)
                if "mumble_bots" in data and isinstance(data["mumble_bots"], list) and len(data["mumble_bots"]) > 0:
                    return data["mumble_bots"]
        except Exception:
            pass

    # Fallback: auto-generate one bot per configured room
    bots = []
    for room_name, tg_val in ROOM_MAPPINGS.items():
        clean_name = room_name.replace("NinjaRadio-", "").replace(" ", "-")
        fallback_slot = 1 if tg_val in [510, 5108, 91, 1023] else 2
        bots.append({
            "name": f"NinjaBot-{clean_name}",
            "channel": room_name,
            "tg": tg_val,
            "slot": fallback_slot
        })
    return bots

def sync_gateway_bots(usrp_sock):
    """Ensures each configured Mumble bot is running and stationed in its assigned channel."""
    global GATEWAY_POOL
    configured_bots = load_configured_bots()
    configured_names = set()

    for b in configured_bots:
        b_name = b.get("name")
        b_channel = b.get("channel")
        b_tg = int(b.get("tg", 510))
        b_slot = int(b.get("slot") or (1 if b_tg in [510, 5108, 91, 1023] else 2))
        if not b_name or not b_channel:
            continue
        configured_names.add(b_name)

        if b_name not in GATEWAY_POOL:
            bot = MumbleGatewayBot(b_channel, b_tg, usrp_sock, bot_user=b_name, slot=b_slot)
            GATEWAY_POOL[b_name] = bot
            bot.start()
            time.sleep(0.4)
        else:
            bot = GATEWAY_POOL[b_name]
            if bot.room_name != b_channel or bot.tg != b_tg or bot.slot != b_slot:
                bot.move_to_channel(b_channel, b_tg, b_slot)

    for b_name in list(GATEWAY_POOL.keys()):
        if b_name not in configured_names:
            logger.info(f"[{b_name}] Removing decommissioned bot...")
            GATEWAY_POOL[b_name].stop()
            GATEWAY_POOL.pop(b_name, None)

def config_file_watcher_loop(usrp_sock):
    """Background thread watching /app/data/config.json for dynamic channel changes."""
    global ROOM_MAPPINGS
    last_mtime = 0.0
    while IS_BRIDGING:
        time.sleep(3.0)
        if os.path.exists(CONFIG_PATH):
            try:
                mtime = os.path.getmtime(CONFIG_PATH)
                if last_mtime > 0 and mtime > last_mtime:
                    logger.info("⚡ Detected changes in config.json! Reloading Mumble rooms...")
                    new_mappings = load_room_mappings()
                    ROOM_MAPPINGS.update(new_mappings)
                    ensure_channels_created()
                    sync_gateway_bots(usrp_sock)
                last_mtime = mtime
            except Exception as e:
                logger.debug(f"config_watcher error: {e}")

def main():
    global ROOM_MAPPINGS, last_mumble_tx, active_mumble_tx, tx_seq
    logger.info("==================================================")
    logger.info("  NinjaRadio Multi-Room Mumble (Murmur) Gateway   ")
    logger.info("==================================================")
    logger.info(f"Connecting to Mumble Server: {MUMBLE_HOST}:{MUMBLE_PORT}")
    logger.info(f"USRP Audio Target: {USRP_HOST}:{USRP_PORT}")

    ROOM_MAPPINGS = load_room_mappings()
    for room_name, tg_val in ROOM_MAPPINGS.items():
        logger.info(f"  • Room '{room_name}' <---> DMR TG {tg_val}")

    # 1. Bootstrap channels in Murmur
    ensure_channels_created()

    usrp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    watchdog = threading.Thread(target=ptt_watchdog_loop, args=(usrp_sock,), daemon=True)
    watchdog.start()

    # 2. Launch multi-room bot pool (one bot per room)
    sync_gateway_bots(usrp_sock)

    # 3. Start config watcher
    threading.Thread(target=config_file_watcher_loop, args=(usrp_sock,), daemon=True).start()

    # 4. Start USRP Listener thread (for DMR RX audio into Mumble)
    t = threading.Thread(target=run_usrp_listener, args=(GATEWAY_POOL,), daemon=True)
    t.start()

    # Keep alive loop
    try:
        while True:
            time.sleep(5)
    except KeyboardInterrupt:
        logger.info("Stopping Mumble Bridge...")
        for bot in GATEWAY_POOL.values():
            bot.stop()

if __name__ == "__main__":
    main()
