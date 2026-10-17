import asyncio
import base64
from datetime import datetime
import socket
import logging
import struct
import json
import os
import re
import time
import subprocess
import signal
from typing import Dict, List, Optional, Tuple
import aiohttp
from fastapi import FastAPI, HTTPException
import uvicorn
from dmr_server import HomeBrewMasterServer
from dmr_client import HomeBrewClient

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] [NinjaRadio-Core] %(message)s")
logger = logging.getLogger("NinjaRadio-Core")

CONFIG_PATH = os.getenv("CONFIG_PATH", "/app/data/config.json")
BACKEND_EVENT_URL = os.getenv("BACKEND_EVENT_URL", "http://backend:8000/api/internal/dmr_event")
MUMBLE_BRIDGE_PORT = 12346
USRP_AUDIO_PORT = int(os.getenv("USRP_AUDIO_PORT", 32345))
DMR_MASTER_PORT = int(os.getenv("DMR_MASTER_PORT", 62031))

# Internal DVSwitch ports
AB_USRP_TX_PORT = 32001   # Analog_Bridge sends decoded PCM to supervisor on 32001
AB_USRP_RX_PORT = 32000   # Supervisor sends PCM to Analog_Bridge on 32000 to encode
MD380_EMU_PORT = 2470     # md380-emu vocoder daemon port
AB_AMBE_RX_PORT = 31100   # Analog_Bridge receives AMBE from MMDVM_Bridge on 31100
AB_AMBE_TX_PORT = 31103   # Analog_Bridge sends AMBE to MMDVM_Bridge on 31103

# ----------------- Official DVSwitch USRP protocol (as used by pyUC / Analog_Bridge 1.6.x) -----------------
# Header: b"USRP" + >iiiiiii (seq, memory, keyup, talkgroup, type, mpxid, reserved)
# The `type` field is written as (TYPE << 24) big-endian, i.e. bytes TYPE,0,0,0 on the wire.
# Fields talkgroup/mpxid/reserved MUST be 0 — Analog_Bridge drops frames whose type is not VOICE/TEXT.
USRP_TYPE_VOICE = 0
USRP_TYPE_TEXT = 2
TLV_TAG_SET_INFO = 0x08   # payload: src(3) rpt(4) tg(3) ts(1) cc(1) callsign\0


def build_usrp_frame(seq: int, keyup: int, usrp_type: int = USRP_TYPE_VOICE, payload: bytes = b"") -> bytes:
    return b"USRP" + struct.pack(">iiiiiii", seq & 0x7FFFFFFF, 0, 1 if keyup else 0, 0, usrp_type << 24, 0, 0) + payload


def build_set_info_tlv(src_id: int, rpt_id: int, tg: int, slot: int, cc: int, callsign: str, pad_320: bool = False) -> bytes:
    call = (callsign or str(src_id)).encode("ascii", errors="ignore")[:24]
    body = (
        (src_id & 0xFFFFFF).to_bytes(3, "big")
        + (rpt_id & 0xFFFFFFFF).to_bytes(4, "big")
        + (tg & 0xFFFFFF).to_bytes(3, "big")
        + bytes([slot & 0xFF, cc & 0xFF])
        + call + b"\x00"
    )
    tlv = bytes([TLV_TAG_SET_INFO, len(body)]) + body
    if pad_320 and len(tlv) < 320:
        tlv = tlv.ljust(320, b"\x00")
    return tlv


def parse_set_info_tlv(payload: bytes) -> Optional[Dict]:
    """Decodes a binary TLV SET_INFO metadata frame emitted by Analog_Bridge (never decode it as ASCII)."""
    if len(payload) < 14 or payload[0] != TLV_TAG_SET_INFO:
        return None
    length = payload[1]
    if len(payload) < 2 + length or length < 12:
        return None
    body = payload[2:2 + length]
    logger.info(f"🔍 [PARSE-SET-INFO] body len={len(body)}, hex={body.hex()}")
    slot = body[10]
    if slot not in (1, 2):
        slot = 2
    callsign = body[12:].split(b"\x00", 1)[0].decode("ascii", errors="ignore").strip()
    return {
        "src": int.from_bytes(body[0:3], "big"),
        "rpt": int.from_bytes(body[3:7], "big"),
        "tg": int.from_bytes(body[7:10], "big"),
        "slot": slot,
        "cc": body[11] if body[11] else 1,
        "callsign": callsign,
    }


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


def lookup_callbook_callsign(dmr_id: int, include_name: bool = False) -> Optional[str]:
    """Resolves amateur callsign (and optionally operator name) for DMR ID from SQLite Callbook DB."""
    if not dmr_id or dmr_id <= 0:
        return None
    db_path = get_db_path()
    if not db_path:
        return None
    try:
        import sqlite3
        with sqlite3.connect(db_path, timeout=1.0) as conn:
            cursor = conn.cursor()
            cursor.execute("SELECT callsign, name FROM callbook WHERE dmr_id = ?", (dmr_id,))
            row = cursor.fetchone()
            if row and row[0]:
                call = str(row[0]).strip().upper()
                name = str(row[1]).strip() if (len(row) > 1 and row[1]) else ""
                if include_name and name and name.upper() != call:
                    return f"{call} {name}"[:24]
                return call
    except Exception:
        pass
    return None


def lookup_callbook_dmr_id(callsign: str) -> Optional[int]:
    """Resolves true numeric DMR ID for Callsign from SQLite Callbook DB."""
    if not callsign:
        return None
    c_clean = callsign.strip().upper()
    db_path = get_db_path()
    if not db_path:
        return None
    try:
        import sqlite3
        with sqlite3.connect(db_path, timeout=1.0) as conn:
            cursor = conn.cursor()
            cursor.execute("SELECT dmr_id FROM callbook WHERE UPPER(callsign) = ? LIMIT 1", (c_clean,))
            row = cursor.fetchone()
            if row and row[0] and int(row[0]) > 0:
                return int(row[0])
    except Exception:
        pass
    return None


def lookup_subscriber_csv_id(callsign: str) -> Optional[int]:
    """Finds DMR ID for a callsign from /var/lib/dvswitch/subscriber_ids.csv or /app/data/subscriber_ids.csv."""
    if not callsign:
        return None
    c_clean = callsign.strip().upper()
    for p in ["/var/lib/dvswitch/subscriber_ids.csv", "/app/data/subscriber_ids.csv"]:
        if os.path.exists(p):
            try:
                with open(p, "r", encoding="utf-8", errors="ignore") as f:
                    for line in f:
                        parts = line.strip().split(",")
                        if len(parts) >= 2 and parts[0].strip().isdigit():
                            if parts[1].strip().upper() == c_clean:
                                return int(parts[0].strip())
            except Exception:
                pass
    return None


def sync_callbook_to_subscriber_csv(default_id: int = 5109999, default_call: str = "N1NJA"):
    """Exports all Callbook records from SQLite into /var/lib/dvswitch/subscriber_ids.csv for Analog_Bridge."""
    db_path = get_db_path()
    sub_csv = "/var/lib/dvswitch/subscriber_ids.csv"
    app_sub_csv = "/app/data/subscriber_ids.csv"
    for p in ["/var/lib/dvswitch", "/app/data"]:
        try:
            os.makedirs(p, exist_ok=True)
        except Exception:
            pass
    lines = [f"{default_id},{default_call},{default_call},NinjaRadio,ID,0"]
    seen_ids = {default_id}
    if db_path and os.path.exists(db_path):
        try:
            import sqlite3
            with sqlite3.connect(db_path, timeout=1.5) as conn:
                cursor = conn.cursor()
                cursor.execute("PRAGMA table_info(callbook)")
                cols = {str(col[1]).lower(): col[1] for col in cursor.fetchall()}
                city_col = cols.get("city", cols.get("qth", "''"))
                prov_col = cols.get("province_state", cols.get("state", "''"))
                cursor.execute(f"SELECT dmr_id, callsign, name, {city_col}, {prov_col} FROM callbook WHERE dmr_id > 0")
                for row in cursor.fetchall():
                    d_id = int(row[0])
                    if d_id in seen_ids:
                        continue
                    seen_ids.add(d_id)
                    c_str = str(row[1] or f"ID-{d_id}").strip().upper().replace(",", "")
                    n_str = str(row[2] or c_str).strip().replace(",", " ")
                    city_str = str(row[3] or "Indonesia").strip().replace(",", " ")
                    prov_str = str(row[4] or "ID").strip().replace(",", " ")
                    lines.append(f"{d_id},{c_str},{n_str},{city_str},{prov_str},0")
        except Exception as e:
            logger.debug(f"sync_callbook_to_subscriber_csv DB read note: {e}")

    # Preserve any extra lines from existing /app/data/subscriber_ids.csv or /var/lib/dvswitch/subscriber_ids.csv
    for existing_file in [app_sub_csv, sub_csv]:
        if os.path.exists(existing_file):
            try:
                with open(existing_file, "r", encoding="utf-8", errors="ignore") as f:
                    for raw_l in f:
                        l = raw_l.strip()
                        if not l or l.startswith(";"):
                            continue
                        first_col = l.split(",", 1)[0].strip()
                        if first_col.isdigit() and int(first_col) not in seen_ids:
                            seen_ids.add(int(first_col))
                            lines.append(l)
            except Exception:
                pass

    content = "\n".join(lines) + "\n"
    for target in (sub_csv, app_sub_csv):
        try:
            with open(target, "w", encoding="utf-8") as sf:
                sf.write(content)
        except Exception:
            pass


def check_core_tx_permission(dmr_id: int, callsign: str, target_tg: int) -> tuple:
    """
    Validates whether the incoming DMR/USRP operator is allowed to transmit on target_tg.
    Returns (is_allowed: bool, reason: str).
    """
    if target_tg in (9990, 99990, 4000):
        return True, "Special Service TG"
    db_path = get_db_path()
    if not db_path:
        return True, "Default (No DB)"
    try:
        import sqlite3
        with sqlite3.connect(db_path, timeout=1.0) as conn:
            cursor = conn.cursor()
            call_upper = (callsign or "").strip().upper()
            if call_upper in ["BD62BOT", "N1NJA", "SUPERUSER"] or dmr_id in [1022057, 5109999]:
                return True, "Master Server Operator / SuperUser"
            cursor.execute("SELECT organization, allowed_tgs_override FROM callbook WHERE dmr_id = ? OR UPPER(callsign) = ?", (dmr_id, call_upper))
            row = cursor.fetchone()
            if row and row[1]:
                override = row[1].strip()
                if override == "*":
                    return True, "User Override (*)"
                allowed = [int(x.strip()) for x in override.split(",") if x.strip().isdigit()]
                if target_tg in allowed:
                    return True, "User Override"
                return False, f"Operator {call_upper} hanya memiliki izin khusus pada TG: {override}"

            org_code = row[0] if (row and row[0]) else None

            # Fetch all organizations to dynamically resolve prefix matching (zero hardcoding)
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
                return False, f"Organisasi tidak ditemukan untuk {call_upper}"

            org_c, allowed_tgs, allow_tx, _ = target_org_row
            if not allow_tx:
                return False, f"Organisasi {org_c} saat ini diset Monitor-Only"
            rule = (allowed_tgs or "*").strip()
            if rule == "*":
                return True, org_c
            allowed = [int(x.strip()) for x in rule.split(",") if x.strip().isdigit()]
            if target_tg in allowed:
                return True, org_c
            return False, f"Stasiun {call_upper} ({org_c}) hanya diizinkan di TG [{rule}]. Akses ke TG {target_tg} diblokir."
    except Exception:
        pass
    return True, "Fallback"


def get_matrix_translated_targets(tg: int, slot: int = 2, source_mode: str = "DMR") -> List[dict]:
    """
    Queries active bridge_routes in TG Matrix to find all mapped / patched targets for `(tg, slot)`.
    Supports manual cross-TG and cross-slot mapping (e.g., Local TG 1025 TS2 <-> Peer TG 510 TS1)
    and independent rules without editing Peer Settings.
    """
    db_path = get_db_path()
    targets = []
    if not db_path:
        return targets
    try:
        import sqlite3
        with sqlite3.connect(db_path, timeout=1.0) as conn:
            cursor = conn.cursor()
            cursor.execute("""
                SELECT name, source_mode, source_tg, source_slot, target_mode, target_tg, target_slot, bidirectional
                FROM bridge_routes
                WHERE is_active = 1
                  AND (source_tg = ? OR (bidirectional = 1 AND target_tg = ?))
            """, (tg, tg))
            for r_name, s_mode, s_tg, s_slot, t_mode, t_tg, t_slot, bidir in cursor.fetchall():
                s_tg = int(s_tg or 0)
                t_tg = int(t_tg or 0)
                s_slot = int(s_slot or 2)
                t_slot = int(t_slot or 2)
                if s_tg == tg:
                    targets.append({
                        "rule_name": r_name,
                        "mode": str(t_mode or "DMR").upper(),
                        "tg": t_tg,
                        "slot": t_slot
                    })
                elif bidir and t_tg == tg:
                    targets.append({
                        "rule_name": r_name,
                        "mode": str(s_mode or "DMR").upper(),
                        "tg": s_tg,
                        "slot": s_slot
                    })
    except Exception as e:
        logger.debug(f"get_matrix_translated_targets note: {e}")
    return targets


def rewrite_dmrd_tg_and_slot(raw_packet: bytes, new_tg: int, new_slot: int) -> bytes:
    """Rewrites destination Talkgroup (bytes 8..11) and Timeslot bit (byte 15 bit 7) of a DMRD packet."""
    if not raw_packet or len(raw_packet) < 20 or not raw_packet.startswith(b"DMRD"):
        return raw_packet
    tg_bytes = struct.pack(">I", new_tg & 0xFFFFFF)[1:]
    orig_bits = raw_packet[15]
    slot_bit = 0x80 if new_slot == 2 else 0x00
    new_bits = (orig_bits & 0x7F) | slot_bit
    return raw_packet[:8] + tg_bytes + raw_packet[11:15] + bytes([new_bits]) + raw_packet[16:]


def is_tg_bridged_to_peer(tg: int, peer_name: str = "", client_subscribed_tgs: list = None) -> bool:
    """
    100% Self-Serve Peer & Matrix Routing Engine:
    1. If the route for `tg` is explicitly turned OFF (is_active = 0) in TG Matrix, drop it.
    2. If there is an active route in TG Matrix for `tg` (either as source or target), approve immediately!
    3. If the peer is configured with `subscribedTgs` containing `tg`, auto-approve immediately!
    4. Otherwise, if peer has no explicit restrictions, allow DMR traffic.
    """
    db_path = get_db_path()
    peer_upper = (peer_name or "").strip().upper()

    if db_path:
        try:
            import sqlite3
            with sqlite3.connect(db_path, timeout=1.0) as conn:
                cursor = conn.cursor()
                # Check if there is an active rule for this TG
                cursor.execute("""
                    SELECT id, name, source_mode, target_mode FROM bridge_routes 
                    WHERE is_active = 1 
                      AND (source_tg = ? OR target_tg = ?)
                """, (tg, tg))
                active_rows = cursor.fetchall()
                if active_rows:
                    for _, r_name, s_m, t_m in active_rows:
                        # If rule specifies a peer name or general DMR/PEER mode, allow
                        if not peer_upper or peer_upper in str(r_name).upper() or peer_upper in str(s_m).upper() or peer_upper in str(t_m).upper() or str(t_m).upper() in ("DMR", "PEER", "MUMBLE", "WEBRTC", "ALL"):
                            return True
                    return True

                # Rule 1: Check if explicitly muted / disabled in TG Matrix
                cursor.execute("""
                    SELECT id FROM bridge_routes 
                    WHERE is_active = 0 
                      AND (source_tg = ? OR target_tg = ?)
                    LIMIT 1
                """, (tg, tg))
                if cursor.fetchone() is not None:
                    return False
        except Exception:
            pass

    # Rule 2: If peer client explicitly subscribed to this TG in Settings, it is APPROVED automatically!
    if client_subscribed_tgs and tg in client_subscribed_tgs:
        return True

    # Rule 4: Default allow for connected DMR peers when no explicit block rule exists
    return True

DEFAULT_CONFIG = {
    "server_callsign": os.getenv("SERVER_CALLSIGN", "N1NJA"),
    "server_dmr_id": int(os.getenv("SERVER_DMR_ID", 5109999)),
    "server_name": "NinjaRadio Master Core",
    "default_tg": 510,
    "rx_freq": "438.8000",
    "tx_freq": "438.8000",
    "color_code": 1,
    "tx_pwr": 25,
    "hotspot_port": DMR_MASTER_PORT,
    "hotspot_password": "ninjaradio_pass",
    "rx_audio_gain": 2.50,
    "tx_audio_gain": 1.25,
    "peering": []
}

def format_freq_hz(val) -> int:
    try:
        s = str(val).strip().replace(" ", "").replace("MHz", "").replace("mhz", "")
        if "." in s:
            return int(float(s) * 1_000_000)
        hz = int(s)
        return hz * 1_000_000 if hz < 1_000_000 else hz
    except Exception:
        return 438800000

def find_binary(name: str) -> Optional[str]:
    candidates = [
        f"/usr/bin/{name}",
        f"/usr/local/bin/{name}",
        f"/opt/{name}/{name}",
        f"/opt/{name.replace('-', '_')}/{name.replace('-', '_')}",
        name
    ]
    for c in candidates:
        if os.path.exists(c) and os.access(c, os.X_OK):
            return c
    return None

class DVSwitchCoreManager:
    def __init__(self):
        self.config = {}
        self.http_session: Optional[aiohttp.ClientSession] = None
        self.bridge_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.usrp_ingress_sock: Optional[socket.socket] = None
        self.ab_pcm_sock: Optional[socket.socket] = None
        self.registered_usrp_clients: Dict[tuple, float] = {}
        self.client_metadata: Dict[tuple, dict] = {}
        self.running = True

        # Process handles
        self.md380_proc: Optional[subprocess.Popen] = None
        self.ab_proc: Optional[subprocess.Popen] = None
        self.mmdvm_proc: Optional[subprocess.Popen] = None

        self.active_peer_host = "Standalone"
        self.active_peer_port = 62031
        self.standalone_mode = True
        self._last_tx_block_log = 0.0
        self.dmr_master_server: Optional[HomeBrewMasterServer] = None
        self.peer_clients: Dict[str, HomeBrewClient] = {}
        self.rx_packet_count = 0
        self.tx_packet_count = 0

        # Live DMR metadata tracked from Analog_Bridge & MMDVM_Bridge
        self.current_rx_src = 0
        self.current_rx_tg = 0
        self.current_rx_slot = 1
        self.current_rx_call = ""
        self.current_rx_time = 0.0
        self.current_rx_active = False

        # Live Web TX Carrier & PTT tracking
        self.is_transmitting = False
        self.last_audio_tx_time = 0.0
        self.last_ptt_heartbeat_time = 0.0
        self.tx_task: Optional[asyncio.Task] = None

        # Spec-compliant Analog_Bridge TX state (identity is carried per call via TLV SET_INFO — no daemon restarts)
        self.ab_tx_seq = 0
        self.tx_identity_sent = False
        self.current_operator_dmr_id = 0
        self.current_operator_callsign = ""
        self.primary_tg = 1025
        self.primary_slot = 2
        self.current_tx_tg = 0
        self.current_tx_slot = 2
        self.current_tx_dmr_id = 0
        self.current_tx_start_time = 0.0
        self.last_rx_src = 0
        self.active_tx_client_addr = None

        # Active talker tracking per Talkgroup: tg -> {"src": int, "callsign": str, "slot": int, "time": float}
        self.active_talkers_by_tg: Dict[int, dict] = {}
        # Track initialized DVSwitch Mobile clients (addr -> timestamp of last mode/identity init)
        self.initialized_usrp_clients: Dict[tuple, float] = {}

        # Modular Dynamic Analog_Bridge Worker Pool (Metode B)
        self.bridge_workers: Dict[str, dict] = {}
        self.worker_rx_tasks: Dict[str, asyncio.Task] = {}

    def load_config(self) -> Dict:
        if os.path.exists(CONFIG_PATH):
            try:
                with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                    self.config = json.load(f)
                    logger.info(f"✓ Configuration loaded from {CONFIG_PATH}")
                    return self.config
            except Exception as e:
                logger.error(f"Error loading {CONFIG_PATH}: {e}")

        self.config = DEFAULT_CONFIG.copy()
        try:
            os.makedirs(os.path.dirname(CONFIG_PATH), exist_ok=True)
            with open(CONFIG_PATH, "w", encoding="utf-8") as f:
                json.dump(self.config, f, indent=2)
        except Exception:
            pass
        return self.config

    def save_config(self):
        try:
            os.makedirs(os.path.dirname(CONFIG_PATH), exist_ok=True)
            with open(CONFIG_PATH, "w", encoding="utf-8") as f:
                json.dump(self.config, f, indent=2)
        except Exception as e:
            logger.error(f"Error saving {CONFIG_PATH}: {e}")

    def generate_configs(self):
        cfg = self.config
        peers = cfg.get("peering", [])

        # In centralized architecture, MMDVM_Bridge is our internal AMBE/PCM gateway
        # connecting strictly to our local DMR Master Server on 127.0.0.1:62031.
        master_host = "127.0.0.1"
        master_port = int(cfg.get("hotspot_port", DMR_MASTER_PORT))
        master_password = cfg.get("hotspot_password", "ninjaradio_pass")
        callsign = str(cfg.get("server_callsign", "N1NJA")).strip().upper()
        dmr_id = int(cfg.get("server_dmr_id", 5109999))
        rx_freq_mhz = str(cfg.get("rx_freq", "438.8000")).strip()
        tx_freq_mhz = str(cfg.get("tx_freq", "438.8000")).strip()
        color_code = int(cfg.get("color_code", 1))
        tx_pwr = int(cfg.get("tx_pwr", 25))
        default_tg = int(cfg.get("default_tg", 510))
        rx_audio_gain = float(cfg.get("rx_audio_gain", 2.50))
        tx_audio_gain = float(cfg.get("tx_audio_gain", 1.25))

        active_peers = [p for p in peers if p.get("isActive") is True]
        self.standalone_mode = len(active_peers) == 0
        self.active_peer_host = master_host
        self.active_peer_port = master_port
        rx_hz = format_freq_hz(rx_freq_mhz)
        tx_hz = format_freq_hz(tx_freq_mhz)
        primary_tg = int(cfg.get("primary_tg") or default_tg or 1025)
        primary_slot = int(cfg.get("primary_slot") or 2)
        self.primary_tg = primary_tg
        self.primary_slot = primary_slot

        # Collect all talkgroups that MMDVM_Bridge should subscribe to on the internal master
        ts1_list = [510, 5108, 91, 5155]
        ts2_list = [5109, 1025, 9990]

        for tg_obj in cfg.get("talkgroups", []):
            tg_val = int(tg_obj.get("tg", 0) or tg_obj.get("tg_id", 0) or 0)
            slot_val = int(tg_obj.get("slot", 2))
            if tg_val > 0:
                if slot_val == 1 and tg_val not in ts1_list:
                    ts1_list.append(tg_val)
                elif slot_val == 2 and tg_val not in ts2_list:
                    ts2_list.append(tg_val)

        for p in peers:
            raw_sub = str(p.get("subscribedTgs") or p.get("subscribed_tgs", "")).strip()
            for m in re.finditer(r'TS1(?:_\d+)?\s*[=:;]\s*([0-9,\s]+)', raw_sub, re.IGNORECASE):
                for part in re.split(r'[,\s]+', m.group(1)):
                    if part.strip().isdigit():
                        v = int(part.strip())
                        if v not in ts1_list:
                            ts1_list.append(v)
            for m in re.finditer(r'TS2(?:_\d+)?\s*[=:;]\s*([0-9,\s]+)', raw_sub, re.IGNORECASE):
                for part in re.split(r'[,\s]+', m.group(1)):
                    if part.strip().isdigit():
                        v = int(part.strip())
                        if v not in ts2_list:
                            ts2_list.append(v)
            if raw_sub.isdigit():
                v = int(raw_sub)
                if v not in ts2_list:
                    ts2_list.append(v)

        opt_parts = []
        for idx, t in enumerate(ts1_list, 1):
            opt_parts.append(f"TS1_{idx}={t}")
        for idx, t in enumerate(ts2_list, 1):
            opt_parts.append(f"TS2_{idx}={t}")
        options_line = f"Options={';'.join(opt_parts)};"

        # Smart DMR ID and Repeater ID handling:
        # DMR RF/voice protocol strictly uses 24-bit source addressing (<= 16777215 / 7 digits max).
        # MMDVM Repeater login supports 32-bit (9 digits e.g. 102205798 or 7 digits + 01 ESSID).
        str_id = str(dmr_id)
        if len(str_id) == 9:
            repeater_id = dmr_id
            radio_id = int(str_id[:7])
        elif len(str_id) <= 7:
            radio_id = dmr_id
            repeater_id = int(f"{dmr_id}01")
        else:
            radio_id = dmr_id & 0xFFFFFF
            repeater_id = dmr_id & 0xFFFFFFFF

        os.makedirs("/app/data", exist_ok=True)
        os.makedirs("/var/log", exist_ok=True)
        os.makedirs("/var/log/dvswitch", exist_ok=True)
        os.makedirs("/var/lib/dvswitch", exist_ok=True)

        # Synchronize SQLite Callbook + subscriber CSV into /var/lib/dvswitch/subscriber_ids.csv
        sync_callbook_to_subscriber_csv(default_id=radio_id, default_call=callsign)

        # 1. Analog_Bridge.ini
        ab_content = f"""; Auto-generated by NinjaRadio DVSwitch Supervisor
[GENERAL]
logLevel = 2
exportMetadata = true
subscriberFile = /var/lib/dvswitch/subscriber_ids.csv
decoderFallBack = true
useEmulator = true
emulatorAddress = 127.0.0.1:{MD380_EMU_PORT}
outputAudioDevice = /dev/null
inputAudioDevice = /dev/null
useMicrophone = false
useVox = false

[AMBE_AUDIO]
address = 127.0.0.1
txPort = {AB_AMBE_TX_PORT}
rxPort = {AB_AMBE_RX_PORT}
ambeMode = DMR
minTxTimeMS = 0
gatewayDmrId = {radio_id}
repeaterID = {repeater_id}
txTg = {primary_tg}
txTs = {primary_slot}
colorCode = {color_code}

[USRP]
address = 127.0.0.1
txPort = {AB_USRP_TX_PORT}
rxPort = {AB_USRP_RX_PORT}
usrpAudio = AUDIO_USE_GAIN
tlvAudio = AUDIO_USE_GAIN
usrpGain = {rx_audio_gain:.2f}
tlvGain = {tx_audio_gain:.2f}
usrpAGC = -20,10,100
"""
        with open("/app/data/Analog_Bridge.ini", "w", encoding="utf-8") as f:
            f.write(ab_content)

        # Copy to /opt/Analog_Bridge and /etc for compatibility
        try:
            os.makedirs("/opt/Analog_Bridge", exist_ok=True)
            with open("/opt/Analog_Bridge/Analog_Bridge.ini", "w", encoding="utf-8") as f:
                f.write(ab_content)
            with open("/etc/Analog_Bridge.ini", "w", encoding="utf-8") as f:
                f.write(ab_content)
        except Exception:
            pass

        # 2. DVSwitch.ini (MMDVM_Bridge <-> Analog_Bridge routing table)
        dvswitch_content = f"""; Auto-generated by NinjaRadio DVSwitch Supervisor
[DMR]
Address = 127.0.0.1
TXPort = {AB_AMBE_RX_PORT}
RXPort = {AB_AMBE_TX_PORT}
txPort = {AB_AMBE_RX_PORT}
rxPort = {AB_AMBE_TX_PORT}
Slot = {primary_slot}
slot = {primary_slot}
ExportTG = 0
exportTG = 0
hangTimerInFrames = 0
talkerAlias = %callsign %location %description
"""
        for p in ["/app/data/DVSwitch.ini", "/opt/MMDVM_Bridge/DVSwitch.ini", "/app/DVSwitch.ini", "/etc/dvswitch/DVSwitch.ini", "/etc/DVSwitch.ini"]:
            try:
                os.makedirs(os.path.dirname(p), exist_ok=True)
                with open(p, "w", encoding="utf-8") as f:
                    f.write(dvswitch_content)
            except Exception:
                pass

        # 3. MMDVM_Bridge.ini (G4KLX CConf parser requires strict Key=Value without spaces)
        mmdvm_content = f"""; Auto-generated by NinjaRadio DVSwitch Supervisor
[General]
Callsign={callsign}
Id={repeater_id}
Timeout=180
Duplex=0
RxFrequency={rx_hz}
TxFrequency={tx_hz}
Power={tx_pwr}
ColorCode={color_code}

[Info]
RXFrequency={rx_hz}
TXFrequency={tx_hz}
Power={tx_pwr}
Latitude=0.0
Longitude=0.0
Height=0
Location=NinjaRadio
Description=NinjaRadio DVSwitch Gateway
URL=www.ninjaradio.local

[Log]
DisplayLevel=1
FileLevel=2
FilePath=/var/log/dvswitch
FileRoot=MMDVM_Bridge

[Modem]
Port=/dev/null
RSSIMappingFile=/dev/null
Trace=0
Debug=0

[DMR]
Enable=1
Beacons=0
Id={repeater_id}
ColorCode={color_code}
EmbeddedLCOnly=1
DumpTAData=0
SelfOnly=0
CallHang=3
TXHang=3

[DMR Network]
Enable=1
Type=Gateway
Address={master_host}
Port={master_port}
Jitter=500
Local=62032
Password={master_password}
Slot1=1
Slot2=1
ModeHang=3
Debug=1
{options_line}

[AMBE]
Enable=1
Address=127.0.0.1
Port={AB_AMBE_TX_PORT}
Send={AB_AMBE_RX_PORT}
"""
        for p in ["/app/data/MMDVM_Bridge.ini", "/opt/MMDVM_Bridge/MMDVM_Bridge.ini", "/etc/MMDVM_Bridge.ini", "/etc/dvswitch/MMDVM_Bridge.ini"]:
            try:
                os.makedirs(os.path.dirname(p), exist_ok=True)
                with open(p, "w", encoding="utf-8") as f:
                    f.write(mmdvm_content)
            except Exception:
                pass

        logger.info(f"✓ DVSwitch INI configuration generated for DMR Master {master_host}:{master_port} (Primary TG: {primary_tg} on Slot {primary_slot}, Callsign: {callsign}, ID: {dmr_id}, Freq: {tx_hz}/{rx_hz} Hz)")

    def start_md380(self):
        """Starts md380-emu vocoder daemon using qemu-arm-static if on x86_64."""
        # Ensure any stale primary md380-emu instance is terminated so UDP port 2470 is free
        if self.md380_proc:
            try:
                self.md380_proc.terminate()
                self.md380_proc.wait(timeout=1.0)
            except Exception:
                try:
                    self.md380_proc.kill()
                except Exception:
                    pass
        try:
            subprocess.run(["pkill", "-9", "-f", f"md380-emu -S {MD380_EMU_PORT}"], capture_output=True)
            time.sleep(0.1)
        except Exception:
            pass

        md380_bin = find_binary("md380-emu")
        qemu_bin = None
        for qp in ["/opt/md380-emu/qemu-arm-static", "/usr/bin/qemu-arm-static", "/usr/local/bin/qemu-arm-static"]:
            if os.path.exists(qp) and os.access(qp, os.X_OK):
                qemu_bin = qp
                break

        md380_cmd = []
        if md380_bin:
            if qemu_bin:
                md380_cmd = [qemu_bin, md380_bin, "-S", str(MD380_EMU_PORT)]
            else:
                md380_cmd = [md380_bin, "-S", str(MD380_EMU_PORT)]

        md380_cwd = "/opt/md380-emu" if os.path.isdir("/opt/md380-emu") else "/app"
        if md380_cmd:
            try:
                log_f = open("/var/log/md380-emu.log", "a")
                self.md380_proc = subprocess.Popen(md380_cmd, cwd=md380_cwd, stdout=log_f, stderr=log_f)
                logger.info(f"✓ Started md380-emu vocoder daemon ({' '.join(md380_cmd)}) in {md380_cwd} on UDP {MD380_EMU_PORT}")
            except Exception as e:
                logger.error(f"Failed to start md380-emu: {e}")
        else:
            logger.warning("md380-emu binary not found.")

    def start_analog_bridge(self):
        """Starts Analog_Bridge daemon using qemu-arm-static if needed."""
        ab_bin = find_binary("Analog_Bridge")
        if not ab_bin:
            logger.warning("Analog_Bridge binary not found.")
            return

        qemu_bin = None
        for qp in ["/opt/md380-emu/qemu-arm-static", "/usr/bin/qemu-arm-static", "/usr/local/bin/qemu-arm-static"]:
            if os.path.exists(qp) and os.access(qp, os.X_OK):
                qemu_bin = qp
                break

        ini_file = "/app/data/Analog_Bridge.ini"
        ab_cwd = "/opt/Analog_Bridge" if os.path.isdir("/opt/Analog_Bridge") else "/app/data"
        log_f = open("/var/log/Analog_Bridge.log", "a")

        is_arm = False
        try:
            with open(ab_bin, "rb") as bf:
                hdr = bf.read(20)
                if len(hdr) >= 20 and hdr[4] == 1 and (hdr[18] == 40 or hdr[18] == 0x28):  # EM_ARM
                    is_arm = True
        except Exception:
            pass

        cmd = [qemu_bin, ab_bin, ini_file] if (is_arm and qemu_bin) else [ab_bin, ini_file]

        try:
            self.ab_proc = subprocess.Popen(cmd, cwd=ab_cwd, stdout=log_f, stderr=log_f)
            logger.info(f"✓ Started Analog_Bridge daemon ({' '.join(cmd)}) with {ini_file}")
        except OSError as oe:
            if oe.errno == 8 and qemu_bin: # Exec format error
                cmd = [qemu_bin, ab_bin, ini_file]
                self.ab_proc = subprocess.Popen(cmd, cwd=ab_cwd, stdout=log_f, stderr=log_f)
                logger.info(f"✓ Started Analog_Bridge via qemu ({' '.join(cmd)}) with {ini_file}")
            else:
                logger.error(f"Failed to start Analog_Bridge: {oe}")
        except Exception as e:
            logger.error(f"Failed to start Analog_Bridge: {e}")

    def start_mmdvm_bridge(self):
        """Starts MMDVM_Bridge native binary."""
        mmdvm_bin = find_binary("MMDVM_Bridge")
        if mmdvm_bin:
            try:
                os.makedirs("/var/log/dvswitch", exist_ok=True)
                log_f = open("/var/log/dvswitch/MMDVM_Bridge.log", "a")
                env = os.environ.copy()
                env["DVSWITCH"] = "/opt/MMDVM_Bridge/DVSwitch.ini"
                mmdvm_cwd = "/opt/MMDVM_Bridge" if os.path.isdir("/opt/MMDVM_Bridge") else "/app/data"
                self.mmdvm_proc = subprocess.Popen([mmdvm_bin, "/app/data/MMDVM_Bridge.ini"], cwd=mmdvm_cwd, env=env, stdout=log_f, stderr=log_f)
                logger.info(f"✓ Started MMDVM_Bridge daemon ({mmdvm_bin}) in {mmdvm_cwd} with /app/data/MMDVM_Bridge.ini")
            except Exception as e:
                logger.error(f"Failed to start MMDVM_Bridge: {e}")
        else:
            logger.warning("MMDVM_Bridge binary not found.")

    def start_daemons(self):
        """Starts md380-emu, Analog_Bridge, and MMDVM_Bridge official binaries."""
        self.start_md380()
        time.sleep(0.3)
        self.start_analog_bridge()
        time.sleep(0.3)
        self.start_mmdvm_bridge()

    def stop_bridge_daemons(self):
        """Stops primary md380-emu, Analog_Bridge, and MMDVM_Bridge processes without killing workers."""
        for name, proc in [("MMDVM_Bridge", self.mmdvm_proc), ("Analog_Bridge", self.ab_proc), ("md380-emu", self.md380_proc)]:
            if proc:
                try:
                    proc.terminate()
                    proc.wait(timeout=1.5)
                except Exception:
                    try:
                        proc.kill()
                    except Exception:
                        pass
                logger.info(f"Stopped {name} daemon.")
        try:
            # Only kill primary daemon instances, preserving worker instances:
            subprocess.run(["pkill", "-9", "-f", f"md380-emu -S {MD380_EMU_PORT}"], capture_output=True)
            subprocess.run(["pkill", "-9", "-f", "Analog_Bridge /app/data/Analog_Bridge.ini"], capture_output=True)
            subprocess.run(["pkill", "-9", "-f", "MMDVM_Bridge /app/data/MMDVM_Bridge.ini"], capture_output=True)
        except Exception:
            pass
        self.md380_proc = None
        self.mmdvm_proc = None
        self.ab_proc = None

    def get_workers_status(self) -> List[dict]:
        """Returns live status of all active Analog_Bridge workers."""
        workers = []
        ab_running = self.ab_proc is not None and self.ab_proc.poll() is None
        primary_tg_locked = int(self.primary_tg or self.config.get("primary_tg") or self.config.get("default_tg") or 1025)
        primary_slot_locked = int(self.primary_slot or self.config.get("primary_slot") or 2)
        workers.append({
            "id": "worker_primary",
            "name": f"Primary Gateway (TG {primary_tg_locked})",
            "tg": primary_tg_locked,
            "slot": primary_slot_locked,
            "usrp_rx_port": AB_USRP_RX_PORT,
            "usrp_tx_port": AB_USRP_TX_PORT,
            "status": "RUNNING" if ab_running else "STOPPED",
            "is_primary": True,
            "rx_packets": self.rx_packet_count,
            "tx_packets": self.tx_packet_count,
            "created_at": "Boot"
        })

        for w_id, w in list(self.bridge_workers.items()):
            ab_p = w.get("ab_proc")
            mmdvm_p = w.get("mmdvm_proc")
            md380_p = w.get("md380_proc")
            ab_alive = ab_p is not None and ab_p.poll() is None
            mmdvm_alive = mmdvm_p is not None and mmdvm_p.poll() is None
            md380_alive = md380_p is not None and md380_p.poll() is None
            is_alive = ab_alive and (mmdvm_alive or mmdvm_p is None)
            workers.append({
                "id": w_id,
                "name": w.get("name", f"TG {w.get('tg')}"),
                "tg": w.get("tg"),
                "slot": w.get("slot", 2),
                "usrp_rx_port": w.get("usrp_rx_port"),
                "usrp_tx_port": w.get("usrp_tx_port"),
                "ambe_rx_port": w.get("ambe_rx_port"),
                "ambe_tx_port": w.get("ambe_tx_port"),
                "emu_port": w.get("emu_port"),
                "mmdvm_local_port": w.get("mmdvm_local_port"),
                "components": {
                    "analog_bridge": "RUNNING" if ab_alive else "STOPPED",
                    "mmdvm_bridge": "RUNNING" if mmdvm_alive else "STOPPED",
                    "md380_emu": "RUNNING" if md380_alive else "STOPPED"
                },
                "status": "RUNNING" if is_alive else "STOPPED",
                "is_primary": False,
                "rx_packets": w.get("rx_packets", 0),
                "tx_packets": w.get("tx_packets", 0),
                "created_at": w.get("created_at", "")
            })
        return workers

    async def spawn_bridge_worker(self, tg: int, slot: int = 2, name: str = "") -> dict:
        """Spawns an on-demand Full-Stack Trio (md380-emu + MMDVM_Bridge + Analog_Bridge) for a specific talkgroup."""
        w_id = f"worker_tg_{tg}_{slot}"
        if w_id in self.bridge_workers:
            w = self.bridge_workers[w_id]
            ab_p = w.get("ab_proc")
            if ab_p and ab_p.poll() is None:
                return {
                    "status": "ALREADY_RUNNING",
                    "message": f"Worker Full-Stack untuk TG {tg} (Slot {slot}) sudah aktif.",
                    "worker": {
                        "id": w_id, "name": w.get("name"), "tg": tg, "slot": slot, "pid": ab_p.pid, "status": "RUNNING"
                    }
                }
            # Clean up dead worker remnants so it can be cleanly re-spawned
            self.stop_bridge_worker(w_id)

        ab_bin = find_binary("Analog_Bridge")
        md380_bin = find_binary("md380-emu")
        mmdvm_bin = find_binary("MMDVM_Bridge")
        if not ab_bin:
            raise RuntimeError("Analog_Bridge binary not found")

        # Pick unique port offset based on index (1..16)
        allocated_indices = {w.get("index", 1) for w in self.bridge_workers.values()}
        idx = 1
        while idx in allocated_indices:
            idx += 1

        usrp_rx = 32000 + (idx * 10)
        usrp_tx = 32001 + (idx * 10)
        ambe_rx = 31100 + (idx * 10)
        ambe_tx = 31103 + (idx * 10)
        emu_port = 2470 + idx
        mmdvm_local = 62032 + idx

        server_dmr_id = int(self.config.get("server_dmr_id", 5109999))
        repeater_id = f"{server_dmr_id}{idx + 1:02d}"
        callsign = str(self.config.get("server_callsign", "N1NJA")).strip().upper()
        master_host = self.config.get("master_host", "127.0.0.1")
        master_port = int(self.config.get("master_port", self.config.get("hotspot_port", DMR_MASTER_PORT)))
        master_password = str(self.config.get("master_password", self.config.get("hotspot_password", "ninjaradio_pass")))

        os.makedirs("/var/log/dvswitch", exist_ok=True)
        os.makedirs("/app/data", exist_ok=True)

        # Detect qemu if running ARM binary on x86_64
        qemu_bin = None
        for qp in ["/opt/md380-emu/qemu-arm-static", "/usr/bin/qemu-arm-static", "/usr/local/bin/qemu-arm-static"]:
            if os.path.exists(qp) and os.access(qp, os.X_OK):
                qemu_bin = qp
                break

        # 1. Start md380-emu dedicated vocoder daemon on emu_port
        md380_proc = None
        if md380_bin:
            is_arm = False
            try:
                with open(md380_bin, "rb") as bf:
                    hdr = bf.read(20)
                    if len(hdr) >= 20 and hdr[4] == 1 and (hdr[18] == 40 or hdr[18] == 0x28):
                        is_arm = True
            except Exception:
                pass
            md380_cmd = [qemu_bin, md380_bin, "-S", str(emu_port)] if (is_arm and qemu_bin) else [md380_bin, "-S", str(emu_port)]
            md380_log = open(f"/var/log/dvswitch/md380_emu_{w_id}.log", "a")
            try:
                md380_proc = subprocess.Popen(md380_cmd, cwd="/opt/md380-emu" if os.path.isdir("/opt/md380-emu") else "/app", stdout=md380_log, stderr=md380_log)
                logger.info(f"✓ Started worker md380-emu on port {emu_port} (PID: {md380_proc.pid})")
            except Exception as e:
                logger.error(f"Failed to start worker md380-emu: {e}")

        # 2. Write DVSwitch routing ini & MMDVM_Bridge.ini for this worker
        dvswitch_ini_path = f"/app/data/DVSwitch_{w_id}.ini"
        dvswitch_content = f"""; Auto-generated by NinjaRadio for Worker TG {tg} Slot {slot}
[DMR]
Address = 127.0.0.1
TXPort = {ambe_rx}
RXPort = {ambe_tx}
txPort = {ambe_rx}
rxPort = {ambe_tx}
Slot = {slot}
slot = {slot}
ExportTG = 0
exportTG = 0
hangTimerInFrames = 0
talkerAlias = %callsign %location %description
"""
        with open(dvswitch_ini_path, "w", encoding="utf-8") as f:
            f.write(dvswitch_content)

        opt_line = f"Options=TS{slot}_1={tg};TS{slot}={tg};"
        mmdvm_ini_path = f"/app/data/MMDVM_Bridge_{w_id}.ini"
        mmdvm_content = f"""; Auto-generated by NinjaRadio for Worker TG {tg} Slot {slot}
[General]
Callsign={callsign}
Id={repeater_id}
Timeout=180
Duplex=0
RxFrequency=438800000
TxFrequency=438800000
Power=25
ColorCode=1

[Info]
RXFrequency=438800000
TXFrequency=438800000
Power=25
Latitude=0.0
Longitude=0.0
Height=0
Location=NinjaRadio
Description=NinjaRadio Worker TG {tg}
URL=www.ninjaradio.local

[Log]
DisplayLevel=1
FileLevel=2
FilePath=/var/log/dvswitch
FileRoot=MMDVM_{w_id}

[Modem]
Port=/dev/null
RSSIMappingFile=/dev/null
Trace=0
Debug=0

[DMR]
Enable=1
Beacons=0
Id={repeater_id}
ColorCode=1
EmbeddedLCOnly=1
DumpTAData=0
SelfOnly=0
CallHang=3
TXHang=3

[DMR Network]
Enable=1
Type=Gateway
Address={master_host}
Port={master_port}
Jitter=500
Local={mmdvm_local}
Password={master_password}
Slot1=1
Slot2=1
ModeHang=3
Debug=1
{opt_line}

[AMBE]
Enable=1
Address=127.0.0.1
Port={ambe_tx}
Send={ambe_rx}
"""
        with open(mmdvm_ini_path, "w", encoding="utf-8") as f:
            f.write(mmdvm_content)

        mmdvm_proc = None
        if mmdvm_bin:
            try:
                env = os.environ.copy()
                env["DVSWITCH"] = dvswitch_ini_path
                mmdvm_log = open(f"/var/log/dvswitch/MMDVM_Bridge_{w_id}.log", "a")
                mmdvm_cwd = "/opt/MMDVM_Bridge" if os.path.isdir("/opt/MMDVM_Bridge") else "/app/data"
                mmdvm_proc = subprocess.Popen([mmdvm_bin, mmdvm_ini_path], cwd=mmdvm_cwd, env=env, stdout=mmdvm_log, stderr=mmdvm_log)
                logger.info(f"✓ Started worker MMDVM_Bridge (ID: {repeater_id}, NetPort: {mmdvm_local}, PID: {mmdvm_proc.pid})")
            except Exception as e:
                logger.error(f"Failed to start worker MMDVM_Bridge: {e}")

        # 3. Write Analog_Bridge.ini and start Analog_Bridge
        ini_path = f"/app/data/Analog_Bridge_{w_id}.ini"
        log_path = f"/var/log/dvswitch/Analog_Bridge_{w_id}.log"
        rx_audio_gain = float(self.config.get("rx_audio_gain", 2.50))
        tx_audio_gain = float(self.config.get("tx_audio_gain", 1.25))
        ini_content = f"""; Auto-generated On-Demand Full-Stack Worker for TG {tg} Slot {slot}
[GENERAL]
logLevel = 2
exportMetadata = true
subscriberFile = /var/lib/dvswitch/subscriber_ids.csv
decoderFallBack = true
useEmulator = true
emulatorAddress = 127.0.0.1:{emu_port}
outputAudioDevice = /dev/null
inputAudioDevice = /dev/null
useMicrophone = false
useVox = false

[AMBE_AUDIO]
address = 127.0.0.1
txPort = {ambe_tx}
rxPort = {ambe_rx}
ambeMode = DMR
minTxTimeMS = 0
gatewayDmrId = {server_dmr_id}
repeaterID = {repeater_id}
txTg = {tg}
txTs = {slot}
colorCode = 1

[USRP]
address = 127.0.0.1
txPort = {usrp_tx}
rxPort = {usrp_rx}
usrpAudio = AUDIO_USE_GAIN
tlvAudio = AUDIO_USE_GAIN
usrpGain = {rx_audio_gain:.2f}
tlvGain = {tx_audio_gain:.2f}
usrpAGC = -20,10,100
"""
        with open(ini_path, "w", encoding="utf-8") as f:
            f.write(ini_content)

        is_arm = False
        try:
            with open(ab_bin, "rb") as bf:
                hdr = bf.read(20)
                if len(hdr) >= 20 and hdr[4] == 1 and (hdr[18] == 40 or hdr[18] == 0x28):
                    is_arm = True
        except Exception:
            pass

        cmd = [qemu_bin, ab_bin, ini_path] if (is_arm and qemu_bin) else [ab_bin, ini_path]
        log_f = open(log_path, "a")
        ab_cwd = "/opt/Analog_Bridge" if os.path.isdir("/opt/Analog_Bridge") else "/app/data"
        ab_proc = subprocess.Popen(cmd, cwd=ab_cwd, stdout=log_f, stderr=log_f)

        # Create worker UDP socket for PCM RX
        rx_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        rx_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        rx_sock.bind(("0.0.0.0", usrp_tx))
        rx_sock.setblocking(False)

        worker_entry = {
            "id": w_id,
            "name": name or f"Dynamic Trio TG {tg}",
            "tg": tg,
            "slot": slot,
            "index": idx,
            "usrp_rx_port": usrp_rx,
            "usrp_tx_port": usrp_tx,
            "ambe_rx_port": ambe_rx,
            "ambe_tx_port": ambe_tx,
            "emu_port": emu_port,
            "mmdvm_local_port": mmdvm_local,
            "ab_proc": ab_proc,
            "mmdvm_proc": mmdvm_proc,
            "md380_proc": md380_proc,
            "rx_sock": rx_sock,
            "rx_packets": 0,
            "tx_packets": 0,
            "rx_src": 0,
            "rx_call": "",
            "rx_active": False,
            "created_at": datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S")
        }
        self.bridge_workers[w_id] = worker_entry

        # Start background RX loop for this worker
        loop = asyncio.get_running_loop()
        task = loop.create_task(self.run_worker_pcm_rx_loop(w_id))
        self.worker_rx_tasks[w_id] = task

        # Persist to configured_workers in config.json
        try:
            c_workers = self.config.setdefault("configured_workers", [])
            if not any(cw.get("tg") == tg and cw.get("slot") == slot for cw in c_workers):
                c_workers.append({"tg": tg, "slot": slot, "name": name or f"Dynamic Trio TG {tg}"})
                self.save_config()
        except Exception:
            pass

        logger.info(f"✓ Spawned Full-Stack Trio worker '{w_id}' for TG {tg} (Ports: USRP={usrp_rx}/{usrp_tx}, AMBE={ambe_rx}/{ambe_tx}, Emu={emu_port}, MMDVM={mmdvm_local}, PID={ab_proc.pid})")
        return {
            "status": "SUCCESS",
            "message": f"Worker Full-Stack Trio (Analog + MMDVM + Emu) untuk TG {tg} (Slot {slot}) berhasil dijalankan.",
            "worker": {
                "id": w_id,
                "name": worker_entry["name"],
                "tg": tg,
                "slot": slot,
                "usrp_rx_port": usrp_rx,
                "usrp_tx_port": usrp_tx,
                "ambe_rx_port": ambe_rx,
                "ambe_tx_port": ambe_tx,
                "emu_port": emu_port,
                "mmdvm_local_port": mmdvm_local,
                "pid": ab_proc.pid,
                "status": "RUNNING"
            }
        }

    def stop_bridge_worker(self, worker_id: str) -> bool:
        """Stops and frees an on-demand worker stack (AB + MMDVM + md380)."""
        w = self.bridge_workers.get(worker_id)
        if not w:
            return False
        task = self.worker_rx_tasks.pop(worker_id, None)
        if task and not task.done():
            task.cancel()
        sock = w.get("rx_sock")
        if sock:
            try:
                sock.close()
            except Exception:
                pass
        for p_key in ["ab_proc", "mmdvm_proc", "md380_proc"]:
            proc = w.get(p_key)
            if proc:
                try:
                    proc.terminate()
                    proc.wait(timeout=1.0)
                except Exception:
                    try:
                        proc.kill()
                    except Exception:
                        pass
        tg = w.get("tg")
        slot = w.get("slot", 2)
        try:
            c_workers = self.config.get("configured_workers", [])
            self.config["configured_workers"] = [cw for cw in c_workers if not (cw.get("tg") == tg and cw.get("slot") == slot)]
            self.save_config()
        except Exception:
            pass
        del self.bridge_workers[worker_id]
        logger.info(f"✓ Stopped and removed on-demand Full-Stack worker '{worker_id}'")
        return True

    def retune_primary_bridge(self, tg: int, slot: int = 2) -> dict:
        """Dynamically retunes the primary Analog_Bridge to a new talkgroup without restart."""
        self.primary_tg = tg
        self.primary_slot = slot
        self.current_tx_tg = tg
        self.current_tx_slot = slot
        self.config["primary_tg"] = tg
        self.config["primary_slot"] = slot
        self.save_config()
        self.send_set_info_to_ab(
            src=int(self.config.get("server_dmr_id", 1022057)),
            callsign=self.config.get("server_callsign", "BD62BOT"),
            tg=tg,
            slot=slot
        )
        logger.info(f"📻 Retuned Primary Analog_Bridge to TG {tg} (Slot {slot})")
        return {
            "status": "SUCCESS",
            "message": f"Primary Gateway berhasil dialihkan ke TG {tg} (Slot {slot})",
            "tg": tg,
            "slot": slot
        }

    async def run_worker_pcm_rx_loop(self, worker_id: str):
        """Asynchronously receives decoded PCM and TLV metadata from an on-demand worker socket."""
        w = self.bridge_workers.get(worker_id)
        if not w:
            return
        sock = w.get("rx_sock")
        tg = int(w.get("tg", 0))
        slot = int(w.get("slot", 2))
        loop = asyncio.get_running_loop()
        logger.info(f"📻 Worker {worker_id} started PCM Rx loop on UDP port {w.get('usrp_tx_port')}")
        while self.running and worker_id in self.bridge_workers:
            try:
                data, _ = await loop.sock_recvfrom(sock, 2048)
                if len(data) >= 32 and data.startswith(b"USRP"):
                    w["rx_packets"] = w.get("rx_packets", 0) + 1
                    magic, seq, mem, key_flag, hdr_tg = struct.unpack(">4sIIII", data[:20])
                    usrp_type, = struct.unpack("<I", data[20:24])
                    pcm_payload = data[32:]
                    if usrp_type == 2 and len(pcm_payload) > 0:
                        tlv_info = parse_set_info_tlv(pcm_payload)
                        if tlv_info:
                            w_src = tlv_info["src"]
                            w_tg = tlv_info["tg"] or tg
                            w_slot = tlv_info["slot"] or slot
                            raw_call = (tlv_info["callsign"] or "").strip()
                            if not raw_call or raw_call.isdigit() or raw_call == str(w_src):
                                w_call = lookup_callbook_callsign(w_src) or raw_call or f"ID-{w_src}"
                            else:
                                w_call = raw_call
                            w["rx_src"] = w_src
                            w["rx_call"] = w_call
                            self.active_talkers_by_tg[w_tg] = {
                                "src": w_src,
                                "callsign": w_call,
                                "slot": w_slot,
                                "time": time.time()
                            }
                        continue

                    # Resolve talker identity for this worker's TG
                    talker_info = self.active_talkers_by_tg.get(tg, {})
                    active_src = w.get("rx_src") or talker_info.get("src") or self.current_rx_src or self.last_rx_src
                    if not active_src:
                        active_src = int(self.config.get("server_dmr_id", 5109999))
                    active_call = w.get("rx_call") or talker_info.get("callsign") or lookup_callbook_callsign(active_src) or f"ID-{active_src}"

                    # Avoid echoing primary bridge if primary bridge is already decoding this exact TG
                    primary_tg_locked = int(self.primary_tg or self.config.get("primary_tg", 1025))
                    if self.current_rx_active and self.current_rx_tg == tg and tg == primary_tg_locked:
                        continue

                    if key_flag == 1 and len(pcm_payload) >= 320:
                        w["rx_active"] = True
                        try:
                            mumble_pkt = struct.pack(">4sIIIIIII", b"USRP", seq & 0x7FFFFFFF, 0, key_flag, tg, active_src, slot, 0) + pcm_payload
                            self.bridge_sock.sendto(mumble_pkt, ("mumble-bridge", MUMBLE_BRIDGE_PORT))
                        except Exception:
                            try:
                                self.bridge_sock.sendto(mumble_pkt, ("bridge-mumble", MUMBLE_BRIDGE_PORT))
                            except Exception:
                                pass
                        asyncio.create_task(self.dispatch_event_to_backend({
                            "network": f"Analog_Bridge (Worker TG {tg})",
                            "host": "127.0.0.1",
                            "src_id": active_src,
                            "dst_tg": tg,
                            "slot": slot,
                            "callsign": active_call,
                            "is_tx": False,
                            "is_transmitting": True,
                            "data_type": 1,
                            "payload": pcm_payload
                        }))
                    elif key_flag == 0 and w.get("rx_active"):
                        w["rx_active"] = False
                        asyncio.create_task(self.dispatch_event_to_backend({
                            "network": f"Analog_Bridge (Worker TG {tg})",
                            "host": "127.0.0.1",
                            "src_id": active_src,
                            "dst_tg": tg,
                            "slot": slot,
                            "callsign": active_call,
                            "is_tx": False,
                            "is_transmitting": False,
                            "data_type": 2
                        }))
                        w["rx_src"] = 0
                        w["rx_call"] = ""
            except asyncio.CancelledError:
                break
            except Exception:
                await asyncio.sleep(0.01)

    async def init(self):
        self.http_session = aiohttp.ClientSession()
        self.load_config()
        self.generate_configs()
        self.running = True

        # 0. Start Native DMR HomeBrew Master Server on UDP 62031 FIRST
        # So MMDVM_Bridge and incoming hotspots can connect immediately.
        try:
            hotspot_port = int(self.config.get("hotspot_port", DMR_MASTER_PORT))
            hotspot_pass = str(self.config.get("hotspot_password", "ninjaradio_pass"))
            server_id = int(self.config.get("server_dmr_id", 5109999))
            server_call = str(self.config.get("server_callsign", "N1NJA")).strip().upper()
            self.dmr_master_server = HomeBrewMasterServer(
                port=hotspot_port,
                password=hotspot_pass,
                server_dmr_id=server_id,
                server_callsign=server_call,
                on_traffic=self.handle_dmr_master_traffic
            )
            await self.dmr_master_server.start()
            logger.info(f"✓ Native DMR Master Server running on UDP port {hotspot_port}")
        except Exception as e:
            logger.error(f"Failed to start DMR Master Server on UDP {DMR_MASTER_PORT}: {e}")

        # Start DVSwitch daemons (md380-emu, Analog_Bridge, MMDVM_Bridge)
        self.start_daemons()

        # 1. Listen on UDP 32001 (where Analog_Bridge emits decoded 8kHz 16-bit PCM)
        try:
            self.ab_pcm_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.ab_pcm_sock.bind(("0.0.0.0", AB_USRP_TX_PORT))
            self.ab_pcm_sock.setblocking(False)
            asyncio.create_task(self.run_ab_pcm_rx_loop())
            logger.info(f"✓ DVSwitch Decoded Audio Consumer active on UDP port {AB_USRP_TX_PORT}")
        except Exception as e:
            logger.error(f"Failed to bind socket on port {AB_USRP_TX_PORT}: {e}")

        # 2. Listen on UDP USRP_AUDIO_PORT (32345) for Web App / Mumble Ingress
        try:
            self.usrp_ingress_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.usrp_ingress_sock.bind(("0.0.0.0", USRP_AUDIO_PORT))
            self.usrp_ingress_sock.setblocking(False)
            asyncio.create_task(self.run_usrp_ingress_loop())
            logger.info(f"✓ External USRP & Web PTT Ingress active on UDP port {USRP_AUDIO_PORT}")
        except Exception as e:
            logger.error(f"Failed to bind USRP ingress port {USRP_AUDIO_PORT}: {e}")

        # 3. Process Watchdog
        asyncio.create_task(self.run_process_watchdog())

        # 4. DMR Metadata Monitor
        asyncio.create_task(self.run_dvswitch_metadata_monitor())

        # 5. PTT Carrier Watchdog (Auto-unkeys carrier if audio stream drops/pauses for >0.6s)
        asyncio.create_task(self.run_ptt_carrier_watchdog())

        # 6. Synchronize Native DMR Upstream Peer Clients (e.g., BRIDI)
        asyncio.create_task(self.sync_peer_clients())

        # 7. Auto-restore configured on-demand workers
        asyncio.create_task(self.restore_configured_workers())

    async def restore_configured_workers(self):
        """Restores configured workers after engine boots."""
        await asyncio.sleep(2.0)
        c_workers = self.config.get("configured_workers", [])
        for cw in c_workers:
            tg = int(cw.get("tg", 0))
            slot = int(cw.get("slot", 2))
            name = cw.get("name", "")
            if tg > 0:
                try:
                    await self.spawn_bridge_worker(tg=tg, slot=slot, name=name)
                    logger.info(f"✓ Restored configured worker for TG {tg} (Slot {slot})")
                except Exception as e:
                    logger.error(f"Failed to restore worker for TG {tg}: {e}")

    async def run_ptt_carrier_watchdog(self):
        """Monitors active PTT carrier and auto-unkeys if no audio frame arrives after startup grace period."""
        while self.running:
            await asyncio.sleep(0.1)
            if self.is_transmitting:
                now = time.time()
                # Allow 2.5s startup grace period for browser getUserMedia() / WebAudio initialization
                if now - getattr(self, "current_tx_start_time", 0.0) < 2.5:
                    continue
                if now - self.last_audio_tx_time > 1.2:
                    logger.info(f"[PTT-Watchdog] ⏹️ Audio silence detected (>1.2s). Auto-releasing carrier PTT.")
                    self.stop_carrier_tx()
                    asyncio.create_task(self.dispatch_event_to_backend({
                        "network": "DVSwitch Mobile / USRP (32345)",
                        "host": "auto-watchdog",
                        "src_id": self.current_tx_dmr_id or 1022057,
                        "dst_tg": self.current_tx_tg or self.primary_tg or 510,
                        "slot": self.current_tx_slot or 2,
                        "callsign": self.current_operator_callsign or "N1NJA",
                        "is_tx": False,
                        "is_transmitting": False,
                        "data_type": 2
                    }))

    async def run_process_watchdog(self):
        """Monitors DVSwitch daemons and restarts them if they terminate unexpectedly."""
        while self.running:
            await asyncio.sleep(5.0)
            if self.ab_proc and self.ab_proc.poll() is not None:
                err_text = ""
                try:
                    with open("/var/log/Analog_Bridge.log", "r") as f:
                        err_text = "".join(f.readlines()[-3:]).strip()
                except Exception:
                    pass
                logger.warning(f"Analog_Bridge exited with code {self.ab_proc.returncode} (log: {err_text}). Restarting...")
                self.start_analog_bridge()

            if self.md380_proc and self.md380_proc.poll() is not None:
                logger.warning(f"md380-emu exited with code {self.md380_proc.returncode}. Restarting...")
                self.start_md380()

            if self.mmdvm_proc and self.mmdvm_proc.poll() is not None:
                err_text = ""
                try:
                    with open("/var/log/dvswitch/MMDVM_Bridge.log", "r") as f:
                        err_text = "".join(f.readlines()[-3:]).strip()
                except Exception:
                    pass
                logger.warning(f"MMDVM_Bridge exited with code {self.mmdvm_proc.returncode} (log: {err_text}). Restarting...")
                self.start_mmdvm_bridge()

    async def run_dvswitch_metadata_monitor(self):
        """
        Monitors Analog_Bridge and MMDVM_Bridge logs in real-time
        to track the active DMR talker's callsign, DMR ID, talkgroup, and timeslot.
        """
        logger.info("📡 Started DVSwitch DMR metadata monitor.")
        ab_log_path = "/var/log/Analog_Bridge.log"
        # Support standard Analog_Bridge log lines: Begin TX: src=... rpt=... dst=... slot=... cc=... call=...
        ab_pattern = re.compile(r"Begin TX:\s+src=(\d+)\s+rpt=\d+\s+dst=(\d+)\s+slot=(\d+)(?:\s+cc=\d+)?(?:\s+call=(\S+))?", re.IGNORECASE)
        # MMDVM_Bridge logs: from <callsign_or_id> to TG <tg>
        mmdvm_start_pat = re.compile(r"DMR Slot (\d+),\s+(?:received network voice header|received network late entry) from\s+([A-Za-z0-9/_-]+)\s+to TG\s+(\d+)", re.IGNORECASE)
        mmdvm_end_pat = re.compile(r"DMR Slot (\d+),\s+(?:received network end of voice transmission|network watchdog has expired)", re.IGNORECASE)

        ab_file = None
        mmdvm_file = None
        current_mmdvm_path = ""

        while self.running:
            try:
                # 1. Analog_Bridge log follower
                if not ab_file:
                    if os.path.exists(ab_log_path):
                        ab_file = open(ab_log_path, "r", encoding="utf-8", errors="ignore")
                        ab_file.seek(0, os.SEEK_END)
                else:
                    lines = ab_file.readlines()
                    for line in lines:
                        m = ab_pattern.search(line)
                        if m:
                            raw_src = int(m.group(1))
                            dst = int(m.group(2))
                            raw_slot = int(m.group(3))
                            slot = 1 if raw_slot <= 1 else 2
                            call = m.group(4).strip() if (m.lastindex and m.lastindex >= 4 and m.group(4)) else ""
                            now = time.time()

                            src = raw_src
                            if src <= 0:
                                talker = self.active_talkers_by_tg.get(dst)
                                if talker and talker.get("src", 0) > 10000 and (now - talker.get("time", 0) < 15.0):
                                    src = int(talker["src"])
                                elif call:
                                    src = lookup_callbook_dmr_id(call) or 0
                            if not call:
                                call = lookup_callbook_callsign(src) or (f"ID-{src}" if src > 0 else "")

                            if dst > 0:
                                if src > 0:
                                    self.current_rx_src = src
                                    self.last_rx_src = src
                                    self.active_talkers_by_tg[dst] = {
                                        "src": src,
                                        "callsign": call or f"ID-{src}",
                                        "slot": slot,
                                        "time": now
                                    }
                                if call:
                                    self.current_rx_call = call
                                self.current_rx_tg = dst
                                self.current_rx_slot = slot
                                self.current_rx_time = now
                                self.current_rx_active = True
                                logger.info(f"🎙️ DMR Active Call: Call={call or 'Unknown'} ID={src} -> TG={dst} (Slot {slot})")

                # 2. MMDVM_Bridge log follower
                today_str = time.strftime("%Y-%m-%d")
                expected_mmdvm_path = f"/var/log/dvswitch/MMDVM_Bridge-{today_str}.log"
                if not os.path.exists(expected_mmdvm_path):
                    expected_mmdvm_path = "/var/log/dvswitch/MMDVM_Bridge.log"

                if expected_mmdvm_path != current_mmdvm_path:
                    if mmdvm_file:
                        try:
                            mmdvm_file.close()
                        except Exception:
                            pass
                        mmdvm_file = None
                    if os.path.exists(expected_mmdvm_path):
                        mmdvm_file = open(expected_mmdvm_path, "r", encoding="utf-8", errors="ignore")
                        mmdvm_file.seek(0, os.SEEK_END)
                        current_mmdvm_path = expected_mmdvm_path

                if mmdvm_file:
                    mlines = mmdvm_file.readlines()
                    for mline in mlines:
                        sm = mmdvm_start_pat.search(mline)
                        if sm:
                            slot = int(sm.group(1))
                            raw_src = sm.group(2).strip()
                            dst = int(sm.group(3))
                            now = time.time()
                            src = 0
                            call = ""

                            if raw_src.isdigit():
                                src = int(raw_src)
                                call = lookup_callbook_callsign(src) or f"ID-{src}"
                            else:
                                call = raw_src
                                # 1. Check if our active DMR talkers cache already has the real DMR ID for this TG!
                                talker = self.active_talkers_by_tg.get(dst)
                                if talker and talker.get("src", 0) > 10000 and (now - talker.get("time", 0) < 15.0):
                                    src = int(talker["src"])
                                # 2. Lookup genuine DMR ID from Callbook DB
                                if src <= 0:
                                    src = lookup_callbook_dmr_id(raw_src) or 0
                                # 3. Lookup genuine DMR ID from subscriber CSV
                                if src <= 0:
                                    src = lookup_subscriber_csv_id(raw_src) or 0
                                # 4. Fallback: if current_rx_src is already a valid DMR ID on this TG, preserve it!
                                if src <= 0 and self.current_rx_src > 10000 and self.current_rx_tg == dst:
                                    src = self.current_rx_src

                            if dst > 0:
                                if src > 0:
                                    self.current_rx_src = src
                                    self.last_rx_src = src
                                if call:
                                    self.current_rx_call = call
                                self.current_rx_tg = dst
                                self.current_rx_slot = slot
                                self.current_rx_time = now
                                self.current_rx_active = True
                                if src > 0:
                                    self.active_talkers_by_tg[dst] = {
                                        "src": src,
                                        "callsign": call or f"ID-{src}",
                                        "slot": slot,
                                        "time": self.current_rx_time
                                    }
                                logger.info(f"🎙️ MMDVM Call Header: {call or 'Unknown'} ({src}) -> TG={dst} (Slot {slot})")

                        em = mmdvm_end_pat.search(mline)
                        if em:
                            if self.current_rx_active:
                                self.current_rx_active = False
                                logger.info(f"⏹️ MMDVM Call Ended: {self.current_rx_call} ({self.current_rx_src}) -> TG {self.current_rx_tg}")
                                if self.current_rx_tg > 0 and self.current_rx_src > 0:
                                    term_event = {
                                        "network": f"DMR Master ({self.active_peer_host})",
                                        "src_id": self.current_rx_src,
                                        "dst_tg": self.current_rx_tg,
                                        "slot": self.current_rx_slot,
                                        "callsign": self.current_rx_call,
                                        "data_type": 2,
                                        "is_tx": False
                                    }
                                    asyncio.create_task(self.dispatch_event_to_backend(term_event))
                                self.current_rx_tg = 0
                                self.current_rx_src = 0
                                self.current_rx_call = ""

                # 3. Timeout watchdog: If transmission has been quiet for > 2.0s, mark inactive
                if self.current_rx_active and (time.time() - self.current_rx_time > 2.0):
                    self.current_rx_active = False
                    logger.info(f"⏹️ DMR Call Timeout: ID {self.current_rx_src} -> TG {self.current_rx_tg}")
                    if self.current_rx_tg > 0 and self.current_rx_src > 0:
                        term_event = {
                            "network": f"DMR Master ({self.active_peer_host})",
                            "src_id": self.current_rx_src,
                            "dst_tg": self.current_rx_tg,
                            "slot": self.current_rx_slot,
                            "callsign": self.current_rx_call,
                            "data_type": 2,
                            "is_tx": False
                        }
                        asyncio.create_task(self.dispatch_event_to_backend(term_event))
                    self.current_rx_tg = 0
                    self.current_rx_src = 0
                    self.current_rx_call = ""

                await asyncio.sleep(0.05)
            except Exception as e:
                if self.running:
                    logger.debug(f"metadata_monitor note: {e}")
                await asyncio.sleep(0.5)

    async def run_ab_pcm_rx_loop(self):
        """
        Receives pure, authentic 8kHz 16-bit PCM USRP frames decoded by Analog_Bridge + md380-emu.
        Dispatches directly to Web Audio, Mumble VoIP, and DVSwitch Mobile clients.
        """
        loop = asyncio.get_running_loop()
        logger.info("📻 Started DVSwitch PCM Audio Rx loop.")
        last_tlv_push_time = 0.0
        while self.running:
            try:
                data, _ = await loop.sock_recvfrom(self.ab_pcm_sock, 2048)
                if len(data) >= 32 and data.startswith(b"USRP"):
                    self.rx_packet_count += 1
                    magic, seq, mem, key_flag, talkgroup = struct.unpack(">4sIIII", data[:20])
                    usrp_type, = struct.unpack("<I", data[20:24])
                    mpxid, res = struct.unpack(">II", data[24:32])
                    pcm_payload = data[32:]

                    # Handle USRP_TYPE_TEXT (2) metadata packets exported by Analog_Bridge
                    if usrp_type == 2 and len(pcm_payload) > 0:
                        try:
                            # 1. Official binary TLV SET_INFO frame (tag 0x08)
                            tlv_info = parse_set_info_tlv(pcm_payload)
                            if tlv_info:
                                tlv_src = tlv_info["src"]
                                tlv_tg = tlv_info["tg"]
                                tlv_slot = tlv_info["slot"]
                                raw_call = (tlv_info["callsign"] or "").strip()

                                # If Analog_Bridge sends src=0 or missing, recover from active_talkers_by_tg
                                if tlv_src <= 0:
                                    talker = self.active_talkers_by_tg.get(tlv_tg or self.current_rx_tg, {})
                                    if talker.get("src", 0) > 10000:
                                        tlv_src = int(talker["src"])
                                    elif raw_call:
                                        tlv_src = lookup_callbook_dmr_id(raw_call) or 0

                                if tlv_src > 0:
                                    self.current_rx_src = tlv_src
                                    self.last_rx_src = tlv_src

                                if tlv_tg > 0:
                                    self.current_rx_tg = tlv_tg
                                self.current_rx_slot = tlv_slot

                                cb_call_full = lookup_callbook_callsign(tlv_src, include_name=True) if tlv_src > 0 else None
                                cb_call_only = lookup_callbook_callsign(tlv_src, include_name=False) if tlv_src > 0 else None
                                if cb_call_only:
                                    self.current_rx_call = cb_call_only
                                    mobile_display_call = cb_call_full or cb_call_only
                                elif raw_call and not raw_call.isdigit() and raw_call != str(tlv_src):
                                    self.current_rx_call = raw_call.split()[0]
                                    mobile_display_call = raw_call
                                else:
                                    self.current_rx_call = f"ID-{tlv_src}" if tlv_src > 0 else "DMR Station"
                                    mobile_display_call = self.current_rx_call

                                if tlv_src > 0 and self.current_rx_tg > 0:
                                    self.active_talkers_by_tg[self.current_rx_tg] = {
                                        "src": tlv_src,
                                        "callsign": self.current_rx_call,
                                        "slot": self.current_rx_slot,
                                        "time": time.time()
                                    }
                                    logger.info(f"📋 USRP TLV SET_INFO: Call={mobile_display_call} ID={tlv_src} TG={self.current_rx_tg} (Slot {self.current_rx_slot})")
                                    # Forward clean 320-byte padded TLV SET_INFO to DVSwitch Mobile clients
                                    try:
                                        rpt_id = int(self.config.get("server_dmr_id", 5109999))
                                        rich_tlv = build_set_info_tlv(
                                            tlv_src, rpt_id, self.current_rx_tg, self.current_rx_slot, 1, mobile_display_call, pad_320=True
                                        )
                                        self.broadcast_to_usrp_clients(build_usrp_frame(seq, 0, USRP_TYPE_TEXT, rich_tlv))
                                        last_tlv_push_time = time.time()
                                    except Exception as ex:
                                        logger.debug(f"Error broadcasting TLV to DVSwitch: {ex}")
                            else:
                                meta_str = pcm_payload.decode("ascii", errors="ignore").strip()
                                logger.info(f"📋 USRP Text Metadata: {meta_str}")
                                m_call = re.search(r'(?:call|callsign)\s*[:=]\s*([A-Za-z0-9/_-]+)', meta_str, re.IGNORECASE)
                                m_id = re.search(r'(?:id|dmr_id|src)\s*[:=]\s*(\d+)', meta_str, re.IGNORECASE)
                                m_tg = re.search(r'(?:tg|talkgroup|dst)\s*[:=]\s*(\d+)', meta_str, re.IGNORECASE)
                                if m_call:
                                    self.current_rx_call = m_call.group(1)
                                if m_id:
                                    self.current_rx_src = int(m_id.group(1))
                                    self.last_rx_src = self.current_rx_src
                                if m_tg:
                                    self.current_rx_tg = int(m_tg.group(1))
                        except Exception as e:
                            logger.debug(f"Metadata parse note: {e}")
                        continue

                    # Resolve incoming talker info (never use operator's own identity as RX talker)
                    primary_tg = int(self.primary_tg or self.config.get("primary_tg", 1025))
                    active_tg = self.current_rx_tg if self.current_rx_tg > 0 else (talkgroup if (0 < talkgroup < 16777215) else primary_tg)
                    talker_for_tg = self.active_talkers_by_tg.get(active_tg, {})
                    active_slot = self.current_rx_slot if self.current_rx_slot > 0 else (talker_for_tg.get("slot") or 2)
                    active_src = self.current_rx_src if self.current_rx_src > 0 else (talker_for_tg.get("src") or self.last_rx_src)
                    active_call = self.current_rx_call or talker_for_tg.get("callsign") or (lookup_callbook_callsign(active_src) if active_src > 0 else None) or (f"ID-{active_src}" if active_src > 0 else "DMR Station")

                    now = time.time()
                    # Ensure DVSwitch Mobile clients receive 320B-padded TLV SET_INFO on burst start and every 1.0s for late-joiners
                    if (not self.current_rx_active or (now - last_tlv_push_time > 1.0)) and active_src > 0:
                        try:
                            rpt_id = int(self.config.get("server_dmr_id", 5109999))
                            mobile_call = lookup_callbook_callsign(active_src, include_name=True) or active_call
                            tlv_burst = build_set_info_tlv(active_src, rpt_id, active_tg, active_slot, 1, mobile_call, pad_320=True)
                            self.broadcast_to_usrp_clients(build_usrp_frame(seq, 0, USRP_TYPE_TEXT, tlv_burst))
                            last_tlv_push_time = now
                        except Exception:
                            pass

                    # Refresh active timestamp while receiving audio packets
                    self.current_rx_time = now
                    self.current_rx_active = True

                    # Determine all target TGs (including any cross-TG translations from TG Matrix)
                    routed_tgs = [(active_tg, active_slot)]
                    for mt in get_matrix_translated_targets(active_tg, active_slot):
                        t_pair = (mt["tg"], mt["slot"])
                        if mt["tg"] > 0 and t_pair not in routed_tgs:
                            routed_tgs.append(t_pair)

                    if len(pcm_payload) >= 320:
                        for r_tg, r_slot in routed_tgs:
                            try:
                                mumble_pkt = struct.pack(">4sIIIIIII", b"USRP", seq & 0x7FFFFFFF, 0, key_flag, r_tg, active_src, r_slot, 0) + pcm_payload
                                self.bridge_sock.sendto(mumble_pkt, ("mumble-bridge", MUMBLE_BRIDGE_PORT))
                            except Exception:
                                try:
                                    self.bridge_sock.sendto(mumble_pkt, ("bridge-mumble", MUMBLE_BRIDGE_PORT))
                                except Exception:
                                    pass

                        # Forward standard voice frame to external USRP clients (DVSwitch Mobile)
                        try:
                            usrp_mobile_pkt = build_usrp_frame(seq, key_flag, USRP_TYPE_VOICE, pcm_payload)
                            self.broadcast_to_usrp_clients(usrp_mobile_pkt)
                        except Exception:
                            pass

                    # Dispatch event to backend API for Web Audio player
                    if key_flag == 1 and len(pcm_payload) >= 320:
                        for r_tg, r_slot in routed_tgs:
                            traffic_event = {
                                "network": f"DMR Master ({self.active_peer_host})",
                                "src_id": active_src,
                                "dst_tg": r_tg,
                                "slot": r_slot,
                                "callsign": active_call,
                                "payload": pcm_payload,
                                "data_type": 1,
                                "is_tx": False
                            }
                            asyncio.create_task(self.dispatch_event_to_backend(traffic_event))
                    elif key_flag == 0:
                        # Voice Terminator
                        try:
                            unkey_fwd = build_usrp_frame(seq, 0, USRP_TYPE_VOICE, b"\x00" * 320)
                            self.broadcast_to_usrp_clients(unkey_fwd)
                        except Exception:
                            pass
                        for r_tg, r_slot in routed_tgs:
                            traffic_event = {
                                "network": f"DMR Master ({self.active_peer_host})",
                                "src_id": active_src,
                                "dst_tg": r_tg,
                                "slot": r_slot,
                                "callsign": active_call,
                                "data_type": 2,
                                "is_tx": False
                            }
                            asyncio.create_task(self.dispatch_event_to_backend(traffic_event))
                        self.current_rx_active = False
                        self.current_rx_tg = 0
                        self.current_rx_src = 0
                        self.current_rx_call = ""
            except Exception as e:
                if self.running:
                    logger.debug(f"ab_pcm_rx_loop note: {e}")
                    await asyncio.sleep(0.01)

    def send_set_info_to_ab(self, src: int, callsign: str, tg: int, slot: int, port: int = AB_USRP_RX_PORT):
        """Sends official DVSwitch TLV SET_INFO frame to Analog_Bridge before audio frames."""
        try:
            rpt_id = int(self.config.get("server_dmr_id", 102205798))
            cc = int(self.config.get("color_code", 1))
            tlv = build_set_info_tlv(src, rpt_id, tg, slot, cc, callsign)
            self.ab_tx_seq = (self.ab_tx_seq + 1) & 0x7FFFFFFF
            pkt = build_usrp_frame(self.ab_tx_seq, 0, USRP_TYPE_TEXT, tlv)
            self.bridge_sock.sendto(pkt, ("127.0.0.1", port))
            logger.info(f"📋 Sent TLV SET_INFO to Analog_Bridge (port {port}): {callsign} ({src}) -> TG {tg} Slot {slot}")
        except Exception as e:
            logger.debug(f"Failed to send SET_INFO: {e}")

    def initialize_dvswitch_client(self, addr: tuple, seq: int = 1, force: bool = False):
        """
        Pushes automatic DMR mode selection, available modes, INFO JSON, and initial TLV SET_INFO
        (Callsign, DMR ID, and active Talkgroup) to a connected DVSwitch Mobile / USRP client.
        Works even when DVSwitch Mobile connects without sending 'REG:' first!
        """
        if not self.usrp_ingress_sock or addr[0] == "127.0.0.1":
            return
        now = time.time()
        last_init = self.initialized_usrp_clients.get(addr, 0.0)
        if not force and (now - last_init < 30.0):
            return
        self.initialized_usrp_clients[addr] = now

        try:
            meta = self.client_metadata.get(addr) or self.client_metadata.get(addr[0], {})
            primary_tg_val = int(meta.get("tg") or self.primary_tg or self.config.get("primary_tg") or self.config.get("default_tg", 1025))
            slot_val = int(meta.get("slot") or self.primary_slot or 2)
            op_id = int(meta.get("src") or self.current_operator_dmr_id or self.config.get("server_dmr_id", 1022057))
            op_call = (
                meta.get("callsign")
                or lookup_callbook_callsign(op_id, include_name=False)
                or self.current_operator_callsign
                or str(self.config.get("server_callsign", "BD62BOT"))
            )
            rpt_id = int(self.config.get("server_dmr_id", 5109999))
            cc_val = int(self.config.get("color_code", 1))

            # 1. Advertise modes & force DMR mode via both USRP TEXT and TLV Tag 5 (Remote Command)
            modes_reply = b'MODES:["DMR", "DSTAR", "NXDN", "P25", "YSF"]\x00'
            self.usrp_ingress_sock.sendto(build_usrp_frame(seq + 1, 0, USRP_TYPE_TEXT, modes_reply), addr)
            self.usrp_ingress_sock.sendto(build_usrp_frame(seq + 2, 0, USRP_TYPE_TEXT, b"MODE:DMR\x00"), addr)
            self.usrp_ingress_sock.sendto(build_usrp_frame(seq + 3, 0, USRP_TYPE_TEXT, f"TG:{primary_tg_val}\x00".encode("ascii")), addr)

            cmd_mode1 = b"mode=DMR"
            tlv_mode1 = struct.pack("BB", 5, len(cmd_mode1)) + cmd_mode1
            self.usrp_ingress_sock.sendto(build_usrp_frame(seq + 4, 0, USRP_TYPE_TEXT, tlv_mode1), addr)

            # 2. Send full Analog_Bridge INFO JSON declaring DMR mode, callsign, and active TG
            info_dict = {
                "ab": "Analog_Bridge",
                "tlv": {
                    "ambe_mode": "DMR",
                    "ambe_size": 72,
                    "ip": "127.0.0.1",
                    "rx_port": AB_AMBE_RX_PORT,
                    "tx_port": AB_AMBE_TX_PORT
                },
                "digital": {
                    "call": op_call,
                    "gw": op_id,
                    "rpt": rpt_id,
                    "tg": primary_tg_val,
                    "ts": slot_val,
                    "cc": cc_val
                },
                "usrp": {
                    "rx_port": USRP_AUDIO_PORT,
                    "tx_port": USRP_AUDIO_PORT
                },
                "last_tune": str(primary_tg_val)
            }
            info_bytes = b"INFO:" + json.dumps(info_dict).encode("ascii") + b"\x00"
            self.usrp_ingress_sock.sendto(build_usrp_frame(seq + 5, 0, USRP_TYPE_TEXT, info_bytes), addr)

            # NOTE: Never send TLV_TAG_SET_INFO (0x08) during client initialization or idle!
            # TLV SET_INFO sets the remote RX Talker banner in DVSwitch Mobile.
            # Sending operator identity here causes DVSwitch Mobile to flicker self-callsign.
            # TLV SET_INFO is dispatched exclusively when genuine incoming RX voice arrives.

            logger.info(f"📱 Initialized DVSwitch Mobile client at {addr} -> Mode: DMR, Call: {op_call} ({op_id}), TG: {primary_tg_val} TS{slot_val}")
        except Exception as ex:
            logger.debug(f"initialize_dvswitch_client note: {ex}")

    async def run_usrp_ingress_loop(self):
        """
        Receives USRP frames from Web App PTT, Mumble Bridge, or DVSwitch Mobile,
        normalizes the header to official DVSwitch standard, and forwards to Analog_Bridge (UDP 32000 or Worker port).
        """
        loop = asyncio.get_running_loop()
        logger.info("📱 Started USRP Ingress loop.")
        while self.running:
            try:
                data, addr = await loop.sock_recvfrom(self.usrp_ingress_sock, 2048)
                if len(data) >= 32 and data.startswith(b"USRP"):
                    self.tx_packet_count += 1
                    is_new_client = addr not in self.registered_usrp_clients
                    self.registered_usrp_clients[addr] = time.time()

                    magic, seq, mem, key_flag, tg, dmr_id, slot, res = struct.unpack(">4sIIIIIII", data[:32])
                    payload = data[32:]
                    packet_type = (dmr_id >> 24) & 0xFF
                    is_control_pkt = (key_flag == 0) or (packet_type in [USRP_TYPE_TEXT, 4, 3])

                    if is_new_client and addr[0] != "127.0.0.1":
                        self.initialize_dvswitch_client(addr, seq, force=True)

                    if is_control_pkt or key_flag == 0 or len(payload) != 320:
                        logger.info(f"🔍 [USRP-RAW] from {addr}: key={key_flag}, type={packet_type}, tg={tg}, dmr_id={dmr_id}, slot={slot}, len={len(payload)}, hex={payload[:64].hex()}")

                    # 1. Handle DVSwitch USRP TEXT / Control packets (Registration, Ping, Info, Remote Commands, TLV SET_INFO)
                    if payload.startswith(b"REG:"):
                        if b"UNREG" in payload:
                            self.registered_usrp_clients.pop(addr, None)
                            self.initialized_usrp_clients.pop(addr, None)
                            self.client_metadata.pop(addr, None)
                            reply_pkt = build_usrp_frame(seq, 0, USRP_TYPE_TEXT, b"REG:UNREG\x00")
                            try:
                                self.usrp_ingress_sock.sendto(reply_pkt, addr)
                            except Exception:
                                pass
                            logger.info(f"📱 DVSwitch Mobile client UNREGISTERED from {addr}")
                        else:
                            self.registered_usrp_clients[addr] = time.time()
                            logger.info(f"✓ DVSwitch Mobile client REGISTERED from {addr} ({payload.decode('ascii', errors='ignore').strip()})")
                            reply_pkt = build_usrp_frame(seq, 0, USRP_TYPE_TEXT, b"REG:OK\x00")
                            try:
                                self.usrp_ingress_sock.sendto(reply_pkt, addr)
                            except Exception:
                                pass
                            self.initialize_dvswitch_client(addr, seq, force=True)
                        continue

                    if payload.startswith(b"PING") or packet_type == 3:
                        self.registered_usrp_clients[addr] = time.time()
                        try:
                            pong_pkt = b"USRP" + struct.pack(">iiiiiii", seq & 0x7FFFFFFF, 0, 0, 0, 3 << 24, 0, 0) + b"PONG\x00"
                            self.usrp_ingress_sock.sendto(pong_pkt, addr)
                        except Exception:
                            pass
                        # PING only requires PONG; do not re-run full client initialization
                        continue

                    if payload.startswith(b"MODES:"):
                        self.registered_usrp_clients[addr] = time.time()
                        self.initialize_dvswitch_client(addr, seq, force=True)
                        continue

                    if payload.startswith(b"INFO:"):
                        self.registered_usrp_clients[addr] = time.time()
                        self.initialize_dvswitch_client(addr, seq, force=True)
                        continue

                    # Handle DTMF dialing / Talkgroup tuning from DVSwitch Mobile
                    if packet_type == 1 or (is_control_pkt and len(payload) > 0 and payload[0] not in [TLV_TAG_SET_INFO, 5, 3] and not payload.startswith(b"REG:") and not payload.startswith(b"PING") and not payload.startswith(b"INFO:") and not payload.startswith(b"MODES:")):
                        text_clean = payload.split(b"\x00")[0].decode("ascii", errors="ignore").strip()
                        if text_clean:
                            if text_clean.isdigit():
                                dialed_tg = int(text_clean)
                                if addr not in self.client_metadata:
                                    self.client_metadata[addr] = {}
                                self.client_metadata[addr]["tg"] = dialed_tg
                                self.client_metadata[addr[0]] = self.client_metadata[addr]
                                logger.info(f"📱 DVSwitch Mobile ({addr}) tuned to TG {dialed_tg} via DTMF/Send ('{text_clean}')")
                                self.initialize_dvswitch_client(addr, seq, force=True)
                                continue
                            elif text_clean.startswith("*"):
                                digits = "".join([c for c in text_clean if c.isdigit()])
                                if digits:
                                    dialed_tg = int(digits)
                                    if addr not in self.client_metadata:
                                        self.client_metadata[addr] = {}
                                    self.client_metadata[addr]["tg"] = dialed_tg
                                    self.client_metadata[addr[0]] = self.client_metadata[addr]
                                    logger.info(f"📱 DVSwitch Mobile ({addr}) dialed TG {dialed_tg} via DTMF *{digits}")
                                    self.initialize_dvswitch_client(addr, seq, force=True)
                                    continue
                                elif text_clean.upper() in ["*DMR", "*YSF", "*DSTAR", "*P25"]:
                                    logger.info(f"📱 DVSwitch Mobile ({addr}) mode selection: {text_clean}")
                                    self.initialize_dvswitch_client(addr, seq, force=True)
                                    continue

                    # Handle remote talkgroup tuning / commands from DVSwitch Mobile
                    cmd_str = ""
                    if len(payload) >= 3 and payload[0] == 5:  # TLV_TAG_REMOTE_CMD
                        cmd_len = payload[1]
                        cmd_str = payload[2:2 + cmd_len].decode("ascii", errors="ignore").strip()
                    elif len(payload) >= 5 and payload[0] == 3:  # TLV_TAG_TG_TUNE
                        tune_val = int.from_bytes(payload[2:2 + payload[1]], "big") if payload[1] <= 4 else 0
                        if tune_val > 0:
                            if addr not in self.client_metadata:
                                self.client_metadata[addr] = {}
                            self.client_metadata[addr]["tg"] = tune_val
                            self.client_metadata[addr[0]] = self.client_metadata[addr]
                            logger.info(f"📱 DVSwitch Mobile ({addr}) tuned to TG {tune_val} via TLV_TAG_TG_TUNE")
                            self.initialize_dvswitch_client(addr, seq, force=True)
                            continue
                    elif is_control_pkt and (payload.startswith(b"txTg=") or payload.startswith(b"*")):
                        cmd_str = payload.decode("ascii", errors="ignore").strip()

                    if cmd_str:
                        if "txTg=" in cmd_str:
                            val_part = cmd_str.split("txTg=")[-1].strip().split()[0].replace("#", "")
                            if val_part.isdigit():
                                new_tg = int(val_part)
                                if addr not in self.client_metadata:
                                    self.client_metadata[addr] = {}
                                self.client_metadata[addr]["tg"] = new_tg
                                self.client_metadata[addr[0]] = self.client_metadata[addr]
                                logger.info(f"📱 DVSwitch Mobile ({addr}) tuned to TG {new_tg} via remote command '{cmd_str}'")
                                self.initialize_dvswitch_client(addr, seq, force=True)
                                continue
                        elif cmd_str.startswith("*"):
                            digits = "".join([c for c in cmd_str if c.isdigit()])
                            if digits:
                                new_tg = int(digits)
                                if addr not in self.client_metadata:
                                    self.client_metadata[addr] = {}
                                self.client_metadata[addr]["tg"] = new_tg
                                self.client_metadata[addr[0]] = self.client_metadata[addr]
                                logger.info(f"📱 DVSwitch Mobile ({addr}) dialed TG {new_tg} via keypad '{cmd_str}'")
                                self.initialize_dvswitch_client(addr, seq, force=True)
                                continue

                    # Handle TLV SET_INFO (Identity carry-over from DVSwitch Mobile)
                    if is_control_pkt and len(payload) >= 14 and payload[0] == TLV_TAG_SET_INFO:
                        tlv_info = parse_set_info_tlv(payload)
                        if tlv_info:
                            existing = self.client_metadata.get(addr) or self.client_metadata.get(addr[0], {})
                            if tlv_info.get("tg", 0) == 0:
                                tlv_info["tg"] = existing.get("tg", 0) or self.primary_tg or int(self.config.get("primary_tg", 1025))
                            if tlv_info.get("slot") not in (1, 2):
                                tlv_info["slot"] = 2
                            if (not tlv_info.get("callsign") or tlv_info["callsign"].isdigit()) and tlv_info.get("src", 0) > 0:
                                cb_c = lookup_callbook_callsign(tlv_info["src"])
                                if cb_c:
                                    tlv_info["callsign"] = cb_c
                            self.client_metadata[addr] = tlv_info
                            self.client_metadata[addr[0]] = tlv_info
                            self.registered_usrp_clients[addr] = time.time()
                            logger.info(f"📱 DVSwitch client identity recorded from {addr}: {tlv_info['callsign']} (ID: {tlv_info['src']}, TG: {tlv_info['tg']}, Slot: {tlv_info['slot']})")
                            self.initialize_dvswitch_client(addr, seq, force=True)
                        continue

                    # 2. Handle PTT Release / Unkey (key_flag == 0)
                    if key_flag == 0:
                        default_fallback_tg = int(self.primary_tg or self.config.get("default_tg", 510))
                        default_server_id = int(self.config.get("server_dmr_id", 5109999))
                        if self.is_transmitting or self.tx_identity_sent:
                            active_tg = self.current_tx_tg or tg or default_fallback_tg
                            active_slot = self.current_tx_slot or slot or 2
                            active_id = self.current_tx_dmr_id or dmr_id or self.current_operator_dmr_id or default_server_id
                            self.stop_carrier_tx(active_tg, active_slot, active_id)
                            asyncio.create_task(self.dispatch_event_to_backend({
                                "network": "DVSwitch Mobile / USRP (32345)",
                                "host": f"{addr[0]}:{addr[1]}",
                                "src_id": active_id,
                                "dst_tg": active_tg,
                                "slot": active_slot,
                                "callsign": self.current_operator_callsign or "BD62BOT",
                                "is_tx": False,
                                "is_transmitting": False,
                                "data_type": 2
                            }))
                        try:
                            ack_pkt = build_usrp_frame(seq, 0, USRP_TYPE_VOICE, b"\x00" * 320)
                            self.usrp_ingress_sock.sendto(ack_pkt, addr)
                        except Exception:
                            pass

                    # 3. Handle PTT Key-down / Voice audio (key_flag == 1)
                    elif len(payload) > 0:
                        now = time.time()
                        self.last_audio_tx_time = now
                        self.last_ptt_heartbeat_time = now
                        default_fallback_tg = int(self.primary_tg or self.config.get("default_tg", 510))
                        hdr_tg = tg if (0 < tg < 16777215) else 0
                        hdr_slot = slot if slot in [1, 2] else 2
                        hdr_dmr_id = dmr_id if (1000 < dmr_id < 16777215 and dmr_id != (USRP_TYPE_TEXT << 24) and packet_type == 0) else 0

                        client_meta = self.client_metadata.get(addr) or self.client_metadata.get(addr[0], {})
                        meta_slot = client_meta.get("slot", 2)
                        if meta_slot not in (1, 2):
                            meta_slot = 2

                        if client_meta and (not hdr_dmr_id or client_meta.get("src") == hdr_dmr_id):
                            target_dmr_id = client_meta.get("src", 0) or hdr_dmr_id or self.current_operator_dmr_id or 1022057
                            target_call = client_meta.get("callsign") or lookup_callbook_callsign(target_dmr_id) or "DVSWITCH"
                            target_tg = hdr_tg or client_meta.get("tg", 0) or default_fallback_tg
                            target_slot = hdr_slot if hdr_tg else meta_slot
                        elif hdr_dmr_id:
                            target_dmr_id = hdr_dmr_id
                            target_call = lookup_callbook_callsign(target_dmr_id) or self.current_operator_callsign or f"ID-{target_dmr_id}"
                            target_tg = hdr_tg or default_fallback_tg
                            target_slot = hdr_slot
                        else:
                            target_dmr_id = self.current_operator_dmr_id or 1022057
                            target_call = self.current_operator_callsign or lookup_callbook_callsign(target_dmr_id) or "BD62BOT"
                            target_tg = hdr_tg or default_fallback_tg
                            target_slot = hdr_slot

                        # Server-Side TX Firewall check
                        is_allowed, reason = check_core_tx_permission(target_dmr_id, target_call, target_tg)
                        if not is_allowed:
                            if now - getattr(self, "_last_fw_alert_time", 0) > 3.0:
                                self._last_fw_alert_time = now
                                logger.warning(f"🛑 [TX-FIREWALL] Blocked PTT from {addr} ({target_call} - {target_dmr_id}) to TG {target_tg}: {reason}")
                            continue

                        # Check if a dedicated on-demand worker is active for target_tg (FIXED: check "ab_proc", not "proc")
                        target_worker_port = AB_USRP_RX_PORT
                        for w in self.bridge_workers.values():
                            ab_p = w.get("ab_proc")
                            if w.get("tg") == target_tg and ab_p and ab_p.poll() is None:
                                target_worker_port = w.get("usrp_rx_port", AB_USRP_RX_PORT)
                                w["tx_packets"] = w.get("tx_packets", 0) + 1
                                break

                        if self.dmr_master_server:
                            self.dmr_master_server.mark_local_transmission(target_dmr_id, target_tg, target_slot)

                        if not self.tx_identity_sent or not self.is_transmitting or self.current_tx_tg != target_tg or self.current_tx_dmr_id != target_dmr_id:
                            self.send_set_info_to_ab(target_dmr_id, target_call, target_tg, target_slot, port=target_worker_port)
                            self.tx_identity_sent = True
                            self.is_transmitting = True
                            self.active_tx_client_addr = addr
                            self.current_tx_tg = target_tg
                            self.current_tx_slot = target_slot
                            self.current_tx_dmr_id = target_dmr_id
                            self.current_tx_start_time = now
                            logger.info(f"[USRP-Ingress] 🔴 PTT Keyed up from {addr} (Call: {target_call}, ID: {target_dmr_id}, TG: {target_tg}, Slot: {target_slot}, Port: {target_worker_port})")
                            asyncio.create_task(self.dispatch_event_to_backend({
                                "network": "DVSwitch Mobile / USRP (32345)",
                                "host": f"{addr[0]}:{addr[1]}",
                                "src_id": target_dmr_id,
                                "dst_tg": target_tg,
                                "slot": target_slot,
                                "callsign": target_call,
                                "is_tx": True,
                                "is_transmitting": True,
                                "data_type": 1
                            }))

                        self.ab_tx_seq = (self.ab_tx_seq + 1) & 0x7FFFFFFF
                        std_pkt = build_usrp_frame(self.ab_tx_seq, 1, USRP_TYPE_VOICE, payload)
                        try:
                            self.bridge_sock.sendto(std_pkt, ("127.0.0.1", target_worker_port))
                        except Exception as e:
                            logger.debug(f"Error forwarding USRP to Analog_Bridge port {target_worker_port}: {e}")
            except Exception as e:
                if self.running:
                    logger.debug(f"usrp_ingress_loop note: {e}")
                    await asyncio.sleep(0.01)

    def broadcast_to_usrp_clients(self, data: bytes):
        now = time.time()
        for client_addr, last_seen in list(self.registered_usrp_clients.items()):
            if now - last_seen > 120:
                del self.registered_usrp_clients[client_addr]
                self.initialized_usrp_clients.pop(client_addr, None)
                continue
            if client_addr[0] == "127.0.0.1":
                continue
            try:
                self.usrp_ingress_sock.sendto(data, client_addr)
            except Exception:
                try:
                    self.bridge_sock.sendto(data, client_addr)
                except Exception:
                    pass

    async def dispatch_event_to_backend(self, traffic: dict):
        if not self.http_session:
            return
        try:
            data_to_send = traffic.copy()
            if "payload" in data_to_send and isinstance(data_to_send["payload"], bytes):
                data_to_send["payload_b64"] = base64.b64encode(data_to_send["payload"]).decode("ascii")
                data_to_send.pop("payload", None)

            timeout = aiohttp.ClientTimeout(total=2.0)
            async with self.http_session.post(BACKEND_EVENT_URL, json=data_to_send, timeout=timeout) as resp:
                pass
        except Exception:
            pass

    async def reload_configuration(self):
        """Re-reads config.json, updates INI files, and restarts Analog_Bridge & MMDVM_Bridge."""
        logger.info("Reconfiguring DVSwitch stack with new settings...")
        self.load_config()
        if self.dmr_master_server:
            self.dmr_master_server.server_callsign = str(self.config.get("server_callsign", "N1NJA")).strip().upper()
            self.dmr_master_server.server_dmr_id = int(self.config.get("server_dmr_id", 5109999))
            if "parrot_tgs" in self.config and isinstance(self.config["parrot_tgs"], list):
                self.dmr_master_server.parrot_tgs = [int(x) for x in self.config["parrot_tgs"]]
        self.stop_bridge_daemons()
        self.generate_configs()
        time.sleep(0.3)
        self.start_daemons()
        await self.sync_peer_clients()
        logger.info("✓ DVSwitch stack restarted with updated configuration.")

    def tune_remote(self, tg: int, slot: int):
        """Sends DVSwitch TLV Remote Command to dynamically tune txTg and txTs in Analog_Bridge."""
        try:
            cmd_tg = f"txTg={tg}".encode("ascii")
            tlv_tg = struct.pack("BB", 5, len(cmd_tg)) + cmd_tg
            self.ab_tx_seq = (self.ab_tx_seq + 1) & 0x7FFFFFFF
            pkt_tg = build_usrp_frame(self.ab_tx_seq, 0, USRP_TYPE_TEXT, tlv_tg)
            self.bridge_sock.sendto(pkt_tg, ("127.0.0.1", AB_USRP_RX_PORT))

            cmd_ts = f"txTs={slot}".encode("ascii")
            tlv_ts = struct.pack("BB", 5, len(cmd_ts)) + cmd_ts
            self.ab_tx_seq = (self.ab_tx_seq + 1) & 0x7FFFFFFF
            pkt_ts = build_usrp_frame(self.ab_tx_seq, 0, USRP_TYPE_TEXT, tlv_ts)
            self.bridge_sock.sendto(pkt_ts, ("127.0.0.1", AB_USRP_RX_PORT))
            logger.info(f"🎯 Tuned Analog_Bridge: txTg={tg}, txTs={slot}")
        except Exception as e:
            logger.debug(f"tune_remote error: {e}")

    async def start_carrier_tx(self, tg: int, slot: int, dmr_id: int = None, callsign: str = None):
        """Maintains active DMR transmission carrier while client streams audio, with strict PTT heartbeat and TOT watchdog."""
        target_dmr_id = dmr_id if (dmr_id and dmr_id > 0) else (self.current_operator_dmr_id or 1022057)
        target_call = callsign or self.current_operator_callsign or lookup_callbook_callsign(target_dmr_id) or "BD62BOT"
        now = time.time()
        self.is_transmitting = True
        self.current_tx_tg = tg
        self.current_tx_slot = slot
        self.current_tx_dmr_id = target_dmr_id
        self.current_tx_start_time = now

        # Route to dedicated worker if one exists for `tg`
        target_worker_port = AB_USRP_RX_PORT
        for w in self.bridge_workers.values():
            ab_p = w.get("ab_proc")
            if w.get("tg") == tg and ab_p and ab_p.poll() is None:
                target_worker_port = w.get("usrp_rx_port", AB_USRP_RX_PORT)
                break

        # Send SET_INFO immediately to inform Analog_Bridge of talker identity
        self.send_set_info_to_ab(target_dmr_id, target_call, tg, slot, port=target_worker_port)
        self.tx_identity_sent = True
        if self.dmr_master_server:
            self.dmr_master_server.mark_local_transmission(target_dmr_id, tg, slot)

        self.last_audio_tx_time = now
        self.last_ptt_heartbeat_time = now
        start_time = now
        logger.info(f"🎙️ PTT Carrier Active -> TG {tg} (Slot {slot}, Port {target_worker_port}) by ID {target_dmr_id} ({target_call})")

        try:
            while self.is_transmitting and self.running:
                now = time.time()
                # 1. Safety Time-Out-Timer (TOT: 90s)
                if now - start_time > 90.0:
                    logger.warning("⚠️ TX Time-Out-Timer (TOT 90s) reached! Auto-unkeying.")
                    break

                # 2. PTT Heartbeat Watchdog: Only release if frontend heartbeat has stopped for > 2.5s
                if now - self.last_ptt_heartbeat_time > 2.5:
                    logger.info("⏹️ PTT Heartbeat timed out (>2.5s). Auto-releasing carrier.")
                    break

                # 3. Inter-burst fill: Send comfort silence only during genuine speech pauses (> 180ms after first audio arrives)
                if (now - start_time > 0.35) and (now - self.last_audio_tx_time > 0.18):
                    self.ab_tx_seq = (self.ab_tx_seq + 1) & 0x7FFFFFFF
                    pkt = build_usrp_frame(self.ab_tx_seq, 1, USRP_TYPE_VOICE, b"\x00" * 320)
                    try:
                        self.bridge_sock.sendto(pkt, ("127.0.0.1", target_worker_port))
                    except Exception:
                        pass
                await asyncio.sleep(0.02)
        finally:
            self.stop_carrier_tx(tg, slot, target_dmr_id)

    def restart_analog_bridge(self):
        """Restarts only Analog_Bridge if requested without touching workers."""
        if self.ab_proc:
            try:
                self.ab_proc.terminate()
                self.ab_proc.wait(timeout=1.0)
            except Exception:
                try:
                    self.ab_proc.kill()
                except Exception:
                    pass
        try:
            subprocess.run(["pkill", "-9", "-f", "Analog_Bridge /app/data/Analog_Bridge.ini"], capture_output=True)
        except Exception:
            pass
        self.ab_proc = None
        time.sleep(0.05)
        self.start_analog_bridge()

    def arm_operator_identity(self, dmr_id: int, callsign: str, allow_restart: bool = False):
        """Pre-arms active operator profile in subscriber CSV and memory without interrupting running daemons."""
        if not dmr_id or dmr_id <= 0:
            return
        
        clean_radio_id = int(str(dmr_id)[:7]) if len(str(dmr_id)) > 7 else dmr_id
        clean_radio_id = clean_radio_id & 0xFFFFFF

        self.current_operator_dmr_id = clean_radio_id
        self.current_operator_callsign = callsign

        # Update subscriber CSV (preserve all imported callbook subscribers)
        try:
            sub_path = "/var/lib/dvswitch/subscriber_ids.csv"
            existing_lines = []
            if os.path.exists(sub_path):
                with open(sub_path, "r", encoding="utf-8") as rf:
                    existing_lines = [l.strip() for l in rf.readlines() if l.strip()]

            filtered = [l for l in existing_lines if not l.startswith(f"{clean_radio_id},") and not l.startswith(";")]
            new_lines = [f"{clean_radio_id},{callsign},{callsign},NinjaRadio,ID,0"] + filtered
            with open(sub_path, "w", encoding="utf-8") as sf:
                sf.write("\n".join(new_lines) + "\n")
        except Exception:
            pass
        logger.info(f"✓ Armed operator identity {callsign} ({clean_radio_id})")

    def update_active_operator(self, dmr_id: int, callsign: str):
        """Backwards compatible alias for arm_operator_identity."""
        self.arm_operator_identity(dmr_id, callsign, allow_restart=False)

    def stop_carrier_tx(self, tg: int = None, slot: int = None, dmr_id: int = None):
        """Releases DMR PTT transmission, sends unkey frames to target worker/primary, and restores Primary Gateway TG."""
        if not self.is_transmitting and not self.tx_identity_sent:
            return
        self.is_transmitting = False
        self.tx_identity_sent = False
        self.active_tx_client_addr = None
        target_tg = tg or getattr(self, "current_tx_tg", 0) or self.primary_tg
        target_slot = slot or getattr(self, "current_tx_slot", 2) or self.primary_slot
        target_dmr_id = dmr_id or getattr(self, "current_tx_dmr_id", 0)

        target_worker_port = AB_USRP_RX_PORT
        for w in self.bridge_workers.values():
            ab_p = w.get("ab_proc")
            if w.get("tg") == target_tg and ab_p and ab_p.poll() is None:
                target_worker_port = w.get("usrp_rx_port", AB_USRP_RX_PORT)
                break

        logger.info(f"⏹️ PTT Carrier Released for TG {target_tg} Slot {target_slot} (ID {target_dmr_id}, Port {target_worker_port}).")
        for _ in range(3):
            self.ab_tx_seq = (self.ab_tx_seq + 1) & 0x7FFFFFFF
            unkey_pkt = build_usrp_frame(self.ab_tx_seq, 0, USRP_TYPE_VOICE, b"\x00" * 320)
            try:
                self.bridge_sock.sendto(unkey_pkt, ("127.0.0.1", target_worker_port))
            except Exception:
                pass

        # Restore Primary Gateway back to its locked primary_tg if a temporary TX used the primary bridge for another TG
        primary_tg_locked = int(self.primary_tg or self.config.get("primary_tg") or 1025)
        primary_slot_locked = int(self.primary_slot or self.config.get("primary_slot") or 2)
        self.current_tx_tg = 0
        if target_worker_port == AB_USRP_RX_PORT and target_tg != primary_tg_locked:
            self.send_set_info_to_ab(
                src=int(self.config.get("server_dmr_id", 1022057)),
                callsign=self.config.get("server_callsign", "BD62BOT"),
                tg=primary_tg_locked,
                slot=primary_slot_locked,
                port=AB_USRP_RX_PORT
            )

    def handle_dmr_master_traffic(self, traffic: dict):
        """Callback from HomeBrewMasterServer when an external hotspot or MMDVM_Bridge transmits."""
        try:
            src_id = traffic.get("src_id", 0)
            dst_tg = traffic.get("dst_tg", 0)
            slot = traffic.get("slot", 2)
            now = time.time()
            callsign = traffic.get("callsign") or lookup_callbook_callsign(src_id) or f"ID-{src_id}"

            self.current_rx_src = src_id
            self.current_rx_tg = dst_tg
            self.current_rx_slot = slot
            self.current_rx_call = callsign
            self.current_rx_time = now
            self.current_rx_active = True
            self.last_rx_src = src_id
            self.rx_packet_count += 1

            if dst_tg > 0 and src_id > 0:
                self.active_talkers_by_tg[dst_tg] = {
                    "src": src_id,
                    "callsign": callsign,
                    "slot": slot,
                    "time": now
                }

            raw_packet = traffic.get("raw_packet")
            # Build list of (target_tg, target_slot, packet_bytes) including manual TG Matrix cross-TG mappings
            packets_to_route = [(dst_tg, slot, raw_packet)]
            if raw_packet:
                for mt in get_matrix_translated_targets(dst_tg, slot):
                    t_tg = mt["tg"]
                    t_slot = mt["slot"]
                    if t_tg > 0 and (t_tg, t_slot) != (dst_tg, slot):
                        translated_pkt = rewrite_dmrd_tg_and_slot(raw_packet, t_tg, t_slot)
                        packets_to_route.append((t_tg, t_slot, translated_pkt))
                        self.active_talkers_by_tg[t_tg] = {
                            "src": src_id,
                            "callsign": callsign,
                            "slot": t_slot,
                            "time": now
                        }
                        # Also broadcast the translated TG to local hotspots on our DMR Master Server
                        if self.dmr_master_server:
                            self.dmr_master_server.broadcast_dmrd(translated_pkt)

            # Forward DMRD packet(s) to matching connected upstream peer clients
            if raw_packet and self.peer_clients:
                for peer_key, client in list(self.peer_clients.items()):
                    if client.state == "CONNECTED":
                        sub_tgs = getattr(client, "subscribed_tgs", [])
                        for r_tg, r_slot, r_pkt in packets_to_route:
                            if is_tg_bridged_to_peer(r_tg, client.name, sub_tgs):
                                # Send if peer subscribes to r_tg OR if there is an explicit TG Matrix route for r_tg
                                if (not sub_tgs) or (r_tg in sub_tgs) or (r_tg != dst_tg) or get_matrix_translated_targets(r_tg, r_slot):
                                    client.send_raw_dmrd(r_pkt)
                                    if traffic.get("data_type") == 0:
                                        logger.info(f"🚀 [BRIDGE-FORWARD] TG {dst_tg}->TG {r_tg} (TS{r_slot}) forwarded to upstream peer '{client.name}' (Src: {src_id} {callsign})")
                                    break

            # Dispatch traffic notification to backend for live monitor & web clients
            traffic_event = {
                "network": traffic.get("network", "Local DMR Master (62031)"),
                "src_id": src_id,
                "dst_tg": dst_tg,
                "slot": slot,
                "callsign": callsign,
                "is_tx": False,
                "data_type": traffic.get("data_type", 1)
            }
            asyncio.create_task(self.dispatch_event_to_backend(traffic_event))
        except Exception as e:
            logger.debug(f"handle_dmr_master_traffic note: {e}")

    async def sync_peer_clients(self):
        """
        Synchronizes active upstream DMR peers (e.g. BRIDI, KRAP)
        by creating or terminating HomeBrewClient instances.
        """
        cfg = self.config
        peers = cfg.get("peering", [])
        active_keys = set()

        for p in peers:
            if p.get("isActive") is True:
                host = str(p.get("host", "")).strip()
                port = int(p.get("port", 62031))
                if not host:
                    continue
                peer_key = str(p.get("id") or f"{host}:{port}")
                active_keys.add(peer_key)

                if peer_key not in self.peer_clients:
                    name = str(p.get("name") or host)
                    dmr_id = int(p.get("dmr_id") or p.get("dmrId") or cfg.get("server_dmr_id", 5109999))
                    callsign = str(p.get("callsign") or cfg.get("server_callsign", "N1NJA")).strip().upper()
                    password = str(p.get("password", ""))
                    subscribed_tgs = str(p.get("subscribedTgs") or p.get("subscribed_tgs", ""))
                    rx_freq = str(p.get("rx_freq") or p.get("rxFreq") or cfg.get("rx_freq", "438.8000"))
                    tx_freq = str(p.get("tx_freq") or p.get("txFreq") or cfg.get("tx_freq", "438.8000"))
                    color_code = int(p.get("color_code") or p.get("colorCode") or cfg.get("color_code", 1))
                    tx_pwr = int(p.get("tx_pwr") or p.get("txPwr") or cfg.get("tx_pwr", 25))

                    logger.info(f"[Peering] 🌐 Connecting HomeBrew peer client: {name} -> {peer_key}")
                    client = HomeBrewClient(
                        name=name,
                        host=host,
                        port=port,
                        dmr_id=dmr_id,
                        callsign=callsign,
                        password=password,
                        subscribed_tgs=subscribed_tgs,
                        rx_freq=rx_freq,
                        tx_freq=tx_freq,
                        color_code=color_code,
                        tx_pwr=tx_pwr,
                        on_traffic=self.handle_peer_traffic,
                        on_status_change=self.handle_peer_status_change
                    )
                    self.peer_clients[peer_key] = client
                    await client.start()

        # Stop removed or deactivated peers
        for peer_key in list(self.peer_clients.keys()):
            if peer_key not in active_keys:
                logger.info(f"[Peering] ⏹️ Stopping inactive peer client: {peer_key}")
                client = self.peer_clients.pop(peer_key)
                try:
                    await client.stop()
                except Exception as e:
                    logger.debug(f"Error stopping peer client {peer_key}: {e}")

    def handle_peer_status_change(self, name: str, state: str):
        logger.info(f"[Peering] Peer '{name}' connection state: {state}")

    def handle_peer_traffic(self, traffic: dict):
        """
        Handles incoming DMR traffic from upstream peer (e.g. BRIDI).
        Suppresses loopback, applies TG Matrix translations, and broadcasts to local hotspots + MMDVM_Bridge (Web/Mumble).
        """
        try:
            src_id = traffic.get("src_id", 0)
            dst_tg = traffic.get("dst_tg", 0)
            slot = traffic.get("slot", 2)
            raw_packet = traffic.get("raw_packet")
            network_name = traffic.get("network", "Remote Peer")
            now = time.time()

            # 1. Loopback suppression: if transmission originated locally, drop!
            if self.dmr_master_server and self.dmr_master_server.is_local_transmission(src_id):
                return

            # 2. Check TG Matrix bridging: drop incoming peer traffic ONLY if explicitly disabled
            if not is_tg_bridged_to_peer(dst_tg, network_name, [dst_tg]):
                return

            callsign = traffic.get("callsign") or lookup_callbook_callsign(src_id) or f"ID-{src_id}"

            # Update live RX metadata immediately so Analog_Bridge & Worker PCM loops know the caller even on late entry!
            self.current_rx_src = src_id
            self.current_rx_tg = dst_tg
            self.current_rx_slot = slot
            self.current_rx_call = callsign
            self.current_rx_time = now
            self.current_rx_active = True
            self.last_rx_src = src_id
            if dst_tg > 0 and src_id > 0:
                self.active_talkers_by_tg[dst_tg] = {
                    "src": src_id,
                    "callsign": callsign,
                    "slot": slot,
                    "time": now
                }

            # 3. Forward DMRD frame (and any TG Matrix translated frames) to our local DMR Master Server
            # so all connected local hotspots and MMDVM_Bridge (Web Audio/Mumble) hear it!
            if self.dmr_master_server and raw_packet:
                self.dmr_master_server.broadcast_dmrd(raw_packet)
                for mt in get_matrix_translated_targets(dst_tg, slot):
                    t_tg = mt["tg"]
                    t_slot = mt["slot"]
                    if t_tg > 0 and (t_tg, t_slot) != (dst_tg, slot):
                        translated_pkt = rewrite_dmrd_tg_and_slot(raw_packet, t_tg, t_slot)
                        self.active_talkers_by_tg[t_tg] = {
                            "src": src_id,
                            "callsign": callsign,
                            "slot": t_slot,
                            "time": now
                        }
                        self.dmr_master_server.broadcast_dmrd(translated_pkt)

            # 4. Last heard & live traffic event to backend
            traffic_event = {
                "network": f"Peer: {network_name}",
                "src_id": src_id,
                "dst_tg": dst_tg,
                "slot": slot,
                "callsign": callsign,
                "is_tx": False,
                "data_type": traffic.get("data_type", 1)
            }
            asyncio.create_task(self.dispatch_event_to_backend(traffic_event))
        except Exception as e:
            logger.debug(f"handle_peer_traffic note: {e}")

    async def shutdown(self):
        self.running = False
        for peer_key, client in list(self.peer_clients.items()):
            try:
                await client.stop()
            except Exception:
                pass
        self.peer_clients.clear()

        if self.dmr_master_server:
            try:
                await self.dmr_master_server.stop()
            except Exception:
                pass
        self.stop_bridge_daemons()
        for w_id in list(self.bridge_workers.keys()):
            self.stop_bridge_worker(w_id)
        if self.md380_proc:
            try:
                self.md380_proc.terminate()
            except Exception:
                pass
        if self.usrp_ingress_sock:
            try:
                self.usrp_ingress_sock.close()
            except Exception:
                pass
        if self.ab_pcm_sock:
            try:
                self.ab_pcm_sock.close()
            except Exception:
                pass
        if self.http_session:
            await self.http_session.close()

    def get_peers_status(self) -> List[dict]:
        peers_status = []
        peers = self.config.get("peering", [])
        for p in peers:
            p_host = str(p.get("host", "")).strip()
            p_port = int(p.get("port", 62031))
            p_name = p.get("name") or p_host
            is_active = p.get("isActive", True)
            peer_key = str(p.get("id") or f"{p_host}:{p_port}")
            client = self.peer_clients.get(peer_key) or self.peer_clients.get(f"{p_host}:{p_port}")

            if client:
                state = client.state
                packets_rx = client.packets_rx
                packets_tx = client.packets_tx
            elif not is_active:
                state = "NONAKTIF"
                packets_rx = 0
                packets_tx = 0
            else:
                state = "STANDBY"
                packets_rx = 0
                packets_tx = 0

            peers_status.append({
                "name": p_name,
                "host": p_host,
                "port": p_port,
                "dmr_id": p.get("dmr_id") or p.get("dmrId") or self.config.get("server_dmr_id", 5109999),
                "callsign": p.get("callsign") or self.config.get("server_callsign", "N1NJA"),
                "state": state,
                "subscribed_tgs": p.get("subscribedTgs") or p.get("subscribed_tgs", "510"),
                "packets_rx": packets_rx,
                "packets_tx": packets_tx,
                "is_connected": state == "CONNECTED"
            })
        return peers_status

    def get_dvswitch_status(self) -> dict:
        ab_running = self.ab_proc is not None and self.ab_proc.poll() is None
        if not ab_running:
            try:
                r = subprocess.run(["pgrep", "-f", "Analog_Bridge"], capture_output=True)
                if r.returncode == 0:
                    ab_running = True
            except Exception:
                pass

        vocoder_running = ab_running or (self.md380_proc is not None and self.md380_proc.poll() is None)
        mmdvm_running = self.mmdvm_proc is not None and self.mmdvm_proc.poll() is None
        if not mmdvm_running:
            try:
                r = subprocess.run(["pgrep", "-f", "MMDVM_Bridge"], capture_output=True)
                if r.returncode == 0:
                    mmdvm_running = True
            except Exception:
                pass

        now = time.time()
        usrp_clients = []
        for (ip, port), seen in self.registered_usrp_clients.items():
            meta = self.client_metadata.get((ip, port), {})
            call = meta.get("callsign", "")
            sid = meta.get("src", 0)
            tg = meta.get("tg", 0) or self.primary_tg
            is_client_tx = bool(self.is_transmitting and self.active_tx_client_addr == (ip, port))
            usrp_clients.append({
                "ip": ip,
                "port": port,
                "type": f"DVSwitch ({call} / {sid})" if call or sid else "DVSwitch Mobile / USRP Client",
                "callsign": call,
                "dmr_id": sid,
                "tg": tg,
                "last_seen_seconds_ago": round(now - seen, 1),
                "is_active": (now - seen) < 120,
                "is_transmitting": is_client_tx
            })

        return {
            "online": ab_running and mmdvm_running,
            "daemons": {
                "analog_bridge": {
                    "name": "Analog_Bridge",
                    "status": "RUNNING" if ab_running else "STOPPED",
                    "pid": self.ab_proc.pid if self.ab_proc else None,
                    "description": "AMBE <-> USRP PCM Transcoder (Software OP25/mbelib)"
                },
                "mmdvm_bridge": {
                    "name": "MMDVM_Bridge",
                    "status": "RUNNING" if mmdvm_running else "STOPPED",
                    "pid": self.mmdvm_proc.pid if self.mmdvm_proc else None,
                    "target_master": f"{self.active_peer_host}:{self.active_peer_port}",
                    "description": "HomeBrew DMR Network Bridge"
                },
                "md380_emu": {
                    "name": "md380-emu",
                    "status": "RUNNING" if vocoder_running else "STOPPED",
                    "port": MD380_EMU_PORT,
                    "description": "AMBE Vocoder Hardware/Software Emulator"
                }
            },
            "ports": {
                "ambe_rx": AB_AMBE_RX_PORT,
                "ambe_tx": AB_AMBE_TX_PORT,
                "usrp_tx": AB_USRP_TX_PORT,
                "usrp_rx": AB_USRP_RX_PORT,
                "usrp_gateway": USRP_AUDIO_PORT
            },
            "live_carrier": {
                "is_transmitting": self.is_transmitting,
                "operator_callsign": self.current_operator_callsign or self.config.get("server_callsign", "N1NJA"),
                "operator_dmr_id": self.current_operator_dmr_id or self.config.get("server_dmr_id", 5109999),
                "primary_tg": self.primary_tg or self.config.get("primary_tg", 1025),
                "primary_slot": self.primary_slot or 2,
                "tx_tg": self.current_tx_tg or self.primary_tg or self.config.get("default_tg", 510),
                "tx_slot": self.current_tx_slot or self.primary_slot or 2,
                "rx_active": self.current_rx_active,
                "rx_callsign": self.current_rx_call,
                "rx_src": self.current_rx_src,
                "rx_tg": self.current_rx_tg,
                "rx_slot": self.current_rx_slot
            },
            "connected_usrp_clients": usrp_clients,
            "packets": {
                "rx_count": self.rx_packet_count,
                "tx_count": self.tx_packet_count
            }
        }

# ----------------- FastAPI Internal Control Server (Port 5000) -----------------
app = FastAPI(title="NinjaRadio Core Engine Control API")
engine_mgr = DVSwitchCoreManager()

@app.on_event("startup")
async def on_startup():
    await engine_mgr.init()

@app.on_event("shutdown")
async def on_shutdown():
    await engine_mgr.shutdown()

@app.get("/api/status")
async def get_core_status():
    ab_running = engine_mgr.ab_proc is not None and engine_mgr.ab_proc.poll() is None
    if not ab_running:
        try:
            r = subprocess.run(["pgrep", "-f", "Analog_Bridge"], capture_output=True)
            if r.returncode == 0:
                ab_running = True
        except Exception:
            pass

    # Analog_Bridge has built-in Software OP25/mbelib AMBE vocoder
    vocoder_running = ab_running or (engine_mgr.md380_proc is not None and engine_mgr.md380_proc.poll() is None)

    mmdvm_running = engine_mgr.mmdvm_proc is not None and engine_mgr.mmdvm_proc.poll() is None
    if not mmdvm_running:
        try:
            r = subprocess.run(["pgrep", "-f", "MMDVM_Bridge"], capture_output=True)
            if r.returncode == 0:
                mmdvm_running = True
        except Exception:
            pass

    peers_status = engine_mgr.get_peers_status()

    return {
        "status": "ONLINE" if (ab_running or mmdvm_running or vocoder_running) else "ONLINE",
        "engine": "Official DVSwitch Native Stack (MMDVM_Bridge + Analog_Bridge + OP25/mbelib Vocoder)",
        "dvswitch_daemons": {
            "md380_emu": "RUNNING" if vocoder_running else "STOPPED",
            "analog_bridge": "RUNNING" if ab_running else "STOPPED",
            "mmdvm_bridge": "RUNNING" if mmdvm_running else "STOPPED"
        },
        "active_master": f"{engine_mgr.active_peer_host}:{engine_mgr.active_peer_port}",
        "peers": peers_status,
        "master_server": {
            "status": "ONLINE" if (engine_mgr.dmr_master_server and engine_mgr.dmr_master_server.running) else "OFFLINE",
            "port": DMR_MASTER_PORT,
            "connected_hotspots": len(engine_mgr.dmr_master_server.clients) if engine_mgr.dmr_master_server else 0,
            "hotspots": [
                {
                    "addr": f"{addr[0]}:{addr[1]}",
                    "callsign": c.get("callsign", "HOTSPOT"),
                    "dmr_id": c.get("dmr_id", 0),
                    "state": c.get("state", "UNKNOWN")
                }
                for addr, c in (engine_mgr.dmr_master_server.clients.items() if engine_mgr.dmr_master_server else [])
            ]
        },
        "config": engine_mgr.config
    }

@app.get("/api/serverdmr/status")
async def get_serverdmr_status():
    if engine_mgr.dmr_master_server:
        data = engine_mgr.dmr_master_server.get_server_status()
    else:
        data = {
            "online": False,
            "port": DMR_MASTER_PORT,
            "protocol": "HomeBrew / MMDVM Protocol v1",
            "server_callsign": engine_mgr.config.get("server_callsign", "N1NJA"),
            "server_dmr_id": engine_mgr.config.get("server_dmr_id", 5109999),
            "uptime": "0s",
            "uptime_seconds": 0,
            "connected_hotspots_count": 0,
            "total_rx_packets": 0,
            "total_tx_packets": 0,
            "active_transmission": None,
            "hotspots": [],
            "recent_qsos": []
        }
    data["upstream_peers"] = engine_mgr.get_peers_status()
    return data

@app.get("/api/dvswitch/status")
async def get_dvswitch_status_endpoint():
    return engine_mgr.get_dvswitch_status()

@app.post("/api/reload")
async def reload_configuration():
    await engine_mgr.reload_configuration()
    return {
        "status": "SUCCESS",
        "message": "DVSwitch configuration reloaded and bridges restarted.",
        "active_master": f"{engine_mgr.active_peer_host}:{engine_mgr.active_peer_port}"
    }

@app.post("/api/operator")
async def set_active_operator(payload: dict):
    dmr_id = int(payload.get("dmr_id", 0))
    callsign = str(payload.get("callsign", "N1NJA")).strip()
    if dmr_id > 0:
        engine_mgr.update_active_operator(dmr_id, callsign)
        return {"status": "SUCCESS", "dmr_id": dmr_id, "callsign": callsign}
    return {"status": "IGNORED", "detail": "Invalid dmr_id"}

@app.post("/api/subscribers/reload")
async def reload_subscribers():
    """Syncs /app/data/subscriber_ids.csv to /var/lib/dvswitch/subscriber_ids.csv."""
    try:
        import shutil
        src = "/app/data/subscriber_ids.csv"
        dst = "/var/lib/dvswitch/subscriber_ids.csv"
        if os.path.exists(src):
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copyfile(src, dst)
            logger.info("✓ Synchronized subscriber_ids.csv from /app/data/ to /var/lib/dvswitch/")
            return {"status": "SUCCESS"}
    except Exception as e:
        return {"status": "ERROR", "detail": str(e)}
    return {"status": "NOT_FOUND"}

@app.post("/api/tx/heartbeat")
async def tx_heartbeat():
    engine_mgr.last_ptt_heartbeat_time = time.time()
    return {"status": "OK"}

@app.post("/api/tx")
async def ptt_transmit(payload: dict):
    callsign = str(payload.get("callsign", "N1NJA")).strip()
    dmr_id = int(payload.get("dmr_id", 5109999))
    tg = int(payload.get("tg", 1025))
    slot = int(payload.get("slot", 2))
    is_tx = bool(payload.get("is_transmitting", True))

    try:
        if is_tx:
            if dmr_id > 0:
                engine_mgr.arm_operator_identity(dmr_id, callsign, allow_restart=False)
            if engine_mgr.tx_task and not engine_mgr.tx_task.done():
                engine_mgr.tx_task.cancel()
            engine_mgr.tx_task = asyncio.create_task(engine_mgr.start_carrier_tx(tg, slot, dmr_id, callsign))
            return {"status": "TX_STARTED", "tg": tg, "slot": slot, "dmr_id": dmr_id, "callsign": callsign}
        else:
            if engine_mgr.tx_task and not engine_mgr.tx_task.done():
                engine_mgr.tx_task.cancel()
            engine_mgr.stop_carrier_tx(tg, slot, dmr_id)
            return {"status": "TX_STOPPED"}
    except Exception as e:
        return {"status": "ERROR", "detail": str(e)}

# ----------------- Dynamic Multi-Worker API (Metode B) -----------------

@app.get("/api/workers")
async def api_get_workers():
    """Returns active Analog_Bridge workers with status and metrics."""
    return {
        "status": "OK",
        "count": len(engine_mgr.get_workers_status()),
        "workers": engine_mgr.get_workers_status()
    }

@app.post("/api/workers/spawn")
async def api_spawn_worker(payload: dict):
    """Dynamically spawns an on-demand Analog_Bridge worker."""
    tg = int(payload.get("tg", 0))
    slot = int(payload.get("slot", 2))
    name = str(payload.get("name", "")).strip()
    if tg <= 0 or tg > 16777215:
        raise HTTPException(status_code=400, detail="Nomor Talkgroup tidak valid.")
    try:
        res = await engine_mgr.spawn_bridge_worker(tg=tg, slot=slot, name=name)
        return res
    except Exception as e:
        logger.error(f"Error spawning worker: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/api/workers/primary/tune")
async def api_tune_primary(payload: dict):
    """Dynamically retunes the primary Analog_Bridge to a new default Talkgroup (Zero-restart)."""
    tg = int(payload.get("tg", 0))
    slot = int(payload.get("slot", 2))
    if tg <= 0 or tg > 16777215:
        raise HTTPException(status_code=400, detail="Nomor Talkgroup tidak valid.")
    try:
        res = engine_mgr.retune_primary_bridge(tg=tg, slot=slot)
        return res
    except Exception as e:
        logger.error(f"Error tuning primary bridge: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))

@app.delete("/api/workers/{worker_id}")
async def api_delete_worker(worker_id: str):
    """Stops and deletes an on-demand Analog_Bridge worker."""
    if worker_id == "worker_primary":
        raise HTTPException(status_code=400, detail="Primary Gateway tidak dapat dihapus.")
    success = engine_mgr.stop_bridge_worker(worker_id)
    if success:
        return {"status": "DELETED", "worker_id": worker_id}
    raise HTTPException(status_code=404, detail="Worker tidak ditemukan.")

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=5000, log_level="info")
