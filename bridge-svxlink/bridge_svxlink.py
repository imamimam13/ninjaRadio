import os
import sys
import time
import socket
import struct
import threading
import subprocess
import logging
import json
import re
from typing import Optional, List, Dict, Tuple, Any
import numpy as np
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, unquote
import requests

log_handlers = [logging.StreamHandler()]
try:
    os.makedirs("/app/data", exist_ok=True)
    log_handlers.append(logging.FileHandler("/app/data/svxlink_bridge.log", mode="a", encoding="utf-8"))
except Exception:
    pass

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] [SvxLink-Bridge] %(message)s",
    handlers=log_handlers
)
logger = logging.getLogger("svxlink-bridge")

CONFIG_PATH = os.getenv("CONFIG_PATH", "/app/data/config.json")
SVX_HOST = os.getenv("SVX_HOST", "svxreflector")
SVX_PORT = int(os.getenv("SVX_PORT", "5300"))
SVX_HTTP_PORT = int(os.getenv("SVX_HTTP_PORT", "8080"))
SVX_AUTH_KEY = os.getenv("SVX_AUTH_KEY", "ninjaradio_svx")

USRP_HOST = os.getenv("DMR_USRP_HOST", "core")
USRP_PORT = int(os.getenv("DMR_USRP_PORT", "32345"))
SVX_BRIDGE_RX_PORT = int(os.getenv("SVX_BRIDGE_RX_PORT", "12348"))
SVX_BRIDGE_HTTP_PORT = int(os.getenv("SVX_BRIDGE_HTTP_PORT", "12349"))

# State tracking
last_svx_tx = 0.0
last_dmr_tx = 0.0
IS_BRIDGING = True
ROOM_MAPPINGS = {}
GATEWAY_POOL: Dict[str, "SvxGatewayBot"] = {}  # bot_name -> SvxGatewayBot
PORT_ALLOCATOR_INDEX = 0


def allocate_udp_port_pair() -> Tuple[int, int]:
    """Allocates a unique UDP port pair (rx_into_svx, tx_from_svx) for each bot instance."""
    global PORT_ALLOCATOR_INDEX
    base = 16000 + (PORT_ALLOCATOR_INDEX * 2)
    PORT_ALLOCATOR_INDEX += 1
    return base, base + 1


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


def resolve_svxlink_identity(callsign_str: str) -> Tuple[int, str]:
    """
    Extracts DMR ID and Callsign from SvxReflector / EchoLink talker callsign and matches against Callbook DB.
    Supports: Callsign with suffix (e.g. YB0ABC-L, BD62AA-R), DMR ID, or deterministic fallback.
    """
    clean_name = (callsign_str or "SVXLINK").strip().upper()
    # Strip standard SvxLink / EchoLink suffixes (-L, -R, -ND, etc.) for DB lookup
    base_call = re.sub(r"-[A-Z0-9]+$", "", clean_name).strip()

    id_match = re.search(r"\b(\d{7})\b", clean_name)
    found_id = int(id_match.group(1)) if id_match else None

    tokens = [base_call] + re.findall(r"[A-Za-z0-9]{3,10}", clean_name)
    found_call = None
    for token in tokens:
        t_up = token.strip("/").upper()
        if not t_up.isdigit() and any(c.isdigit() for c in t_up) and any(c.isalpha() for c in t_up):
            found_call = t_up
            break

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

    if found_id and found_call:
        return found_id, found_call
    if found_id:
        return found_id, f"ID-{found_id}"
    if found_call:
        virtual_id = 5100000 + (abs(hash(found_call)) % 90000)
        return virtual_id, found_call

    virtual_id = 5100000 + (abs(hash(clean_name)) % 90000)
    clean_call = re.sub(r"[^A-Za-z0-9]", "", base_call)[:8].upper() or f"SVX{virtual_id%1000:03d}"
    return virtual_id, clean_call


def check_svxlink_tx_permission(dmr_id: int, callsign: str, target_tg: int) -> Tuple[bool, str]:
    """
    Dynamically checks if SvxLink / EchoLink user is permitted to transmit on target_tg.
    Evaluates callbook overrides, then organizations table.
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
                return True, "Master Server Operator"
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
                return True, "Default Open"

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
        logger.debug(f"check_svxlink_tx_permission error: {e}")
    return True, "Fallback"


def load_room_mappings() -> Dict[str, int]:
    """Loads SvxReflector talkgroup rooms from config.json."""
    mappings = {}
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                data = json.load(f)
            if "svxlink_rooms" in data and isinstance(data["svxlink_rooms"], list):
                for r in data["svxlink_rooms"]:
                    ch = r.get("name") or r.get("channel")
                    tg = r.get("targetTg") if r.get("targetTg") is not None else r.get("tg")
                    if ch and tg is not None:
                        try:
                            mappings[ch.strip()] = int(tg)
                        except (ValueError, TypeError):
                            pass
                for b in data.get("svxlink_bots", []):
                    ch = b.get("channel")
                    tg = b.get("tg", 510)
                    if ch:
                        try:
                            mappings[ch.strip()] = int(tg)
                        except (ValueError, TypeError):
                            pass
                if mappings:
                    return mappings
        except Exception as e:
            logger.debug(f"Could not parse config.json for svxlink_rooms: {e}")

    mappings = {
        "SVX-Nasional": 510,
        "SVX-Nusantara": 5109,
        "SVX-BRIDI": 1025,
        "SVX-Parrot": 9990,
    }
    raw_map = os.getenv("SVXLINK_ROOM_MAP", "")
    if raw_map:
        for item in raw_map.split(","):
            if ":" in item:
                ch, tg = item.split(":", 1)
                try:
                    mappings[ch.strip()] = int(tg.strip())
                except ValueError:
                    pass
    return mappings


def load_configured_bots() -> List[Dict[str, Any]]:
    """Loads SvxLink bot definitions from config.json, ensuring every room has an active bot."""
    bots = []
    seen_channels = set()
    seen_names = set()

    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                data = json.load(f)
                if "svxlink_bots" in data and isinstance(data["svxlink_bots"], list):
                    for b in data["svxlink_bots"]:
                        b_name = b.get("name")
                        b_channel = b.get("channel")
                        if b_name and b_channel:
                            bots.append(b)
                            seen_channels.add(b_channel.strip().lower())
                            seen_names.add(b_name.strip())
        except Exception as e:
            logger.debug(f"Error loading svxlink_bots from config: {e}")

    for room_name, tg_val in ROOM_MAPPINGS.items():
        if room_name.strip().lower() not in seen_channels:
            clean_name = room_name.replace("SVX-", "").replace("NinjaRadio-", "").replace(" ", "-")
            bot_name = f"SVX-{clean_name}"[:15].upper()
            cnt = 1
            while bot_name in seen_names:
                bot_name = f"SVX-{clean_name}-{cnt}"[:15].upper()
                cnt += 1
            fallback_slot = 1 if tg_val in [510, 5108, 91, 1023] else 2
            bots.append({
                "name": bot_name,
                "channel": room_name,
                "tg": tg_val,
                "slot": fallback_slot,
                "echolink_enabled": False
            })
            seen_channels.add(room_name.strip().lower())
            seen_names.add(bot_name)

    return bots


class SvxGatewayBot:
    """
    Dedicated SvxReflector & EchoLink Gateway Bot Instance for a specific DMR Talkgroup.
    Manages an isolated headless `svxlink` daemon bridged via zero-latency UDP 16kHz PCM sockets,
    PTY Squelch/PTT control, and Tcl Event Carry-Over for caller callsigns.
    """

    def __init__(self, room_name: str, tg: int, usrp_sock: socket.socket, bot_user: str = None, slot: int = None, bot_cfg: dict = None):
        self.room_name = room_name
        self.tg = int(tg)
        self.slot = int(slot or (1 if self.tg in [510, 5108, 91, 1023] else 2))
        self.usrp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.bot_cfg = bot_cfg or {}

        if bot_user:
            self.bot_user = re.sub(r"[^A-Za-z0-9\-]", "", bot_user)[:15].upper()
        else:
            clean_name = re.sub(r"[^A-Za-z0-9]", "", room_name.replace("SVX-", ""))[:8].upper()
            self.bot_user = f"SVX-{clean_name}"

        self.udp_rx_into_svx, self.udp_tx_from_svx = allocate_udp_port_pair()
        self.runtime_dir = f"/tmp/svx_{self.bot_user}"
        self.sql_pty_path = f"{self.runtime_dir}/sql_pty"
        self.ptt_pty_path = f"{self.runtime_dir}/ptt_pty"
        self.dtmf_pty_path = f"{self.runtime_dir}/dtmf_pty"
        self.conf_path = f"{self.runtime_dir}/svxlink.conf"

        self.proc: Optional[subprocess.Popen] = None
        self.running = False
        self.connected = False

        # Active remote talker on SvxReflector / EchoLink
        self.active_talker_call: Optional[str] = None
        self.active_talker_dmr_id: int = 0
        self.active_talker_start: float = 0.0
        self.last_svx_audio_rx: float = 0.0
        self.is_transmitting_to_dmr: bool = False

        # Active DMR RX streaming into SvxLink
        self.last_dmr_rx_play: float = 0.0
        self.squelch_is_open: bool = False

        self.tx_pcm_buffer = bytearray()
        from ambe_dsp import AmbeAudioConditioner
        self.tx_conditioner = AmbeAudioConditioner(
            input_rate=16000,
            lowcut_hz=300.0,
            highcut_hz=3300.0,
            filter_order=5,
            gate_threshold_rms=120.0,
            peak_ceiling=24000.0,
        )

    def _build_svxlink_environment(self):
        """Creates isolated config & Tcl event overrides so SvxLink reports talker_start/talker_stop to Python."""
        os.makedirs(self.runtime_dir, exist_ok=True)
        events_dir = f"{self.runtime_dir}/events.d"
        local_events_dir = f"{events_dir}/local"
        os.makedirs(local_events_dir, exist_ok=True)

        # Symlink system Tcl scripts into our isolated events_dir
        sys_events = "/usr/share/svxlink/events.d"
        if os.path.exists(sys_events):
            for item in os.listdir(sys_events):
                if item == "local":
                    continue
                src = os.path.join(sys_events, item)
                dst = os.path.join(events_dir, item)
                if not os.path.exists(dst):
                    try:
                        os.symlink(src, dst)
                    except Exception:
                        pass

        # Create root events.tcl pointing to our isolated events.d
        events_tcl_path = f"{self.runtime_dir}/events.tcl"
        if os.path.exists("/usr/share/svxlink/events.tcl"):
            with open("/usr/share/svxlink/events.tcl", "r", encoding="utf-8", errors="ignore") as rf:
                ev_content = rf.read()
            ev_content = ev_content.replace("/usr/share/svxlink/events.d", events_dir)
            with open(events_tcl_path, "w", encoding="utf-8") as wf:
                wf.write(ev_content)
        else:
            with open(events_tcl_path, "w", encoding="utf-8") as wf:
                wf.write("# Minimal fallback events.tcl\n")

        # Write local/ReflectorLogic.tcl to silence audio clutter and report talker_start/stop via stdout/UDP
        reflector_local_tcl = f"""
namespace eval ReflectorLogic {{
    proc talker_start {{tg callsign}} {{
        puts "NINJA_EVENT:TALKER_START:$tg:$callsign"
        flush stdout
    }}
    proc talker_stop {{tg callsign}} {{
        puts "NINJA_EVENT:TALKER_STOP:$tg:$callsign"
        flush stdout
    }}
    proc select_tg {{new_tg}} {{
        puts "NINJA_EVENT:SELECT_TG:$new_tg"
        flush stdout
    }}
}}
"""
        with open(f"{local_events_dir}/ReflectorLogic.tcl", "w", encoding="utf-8") as wf:
            wf.write(reflector_local_tcl)

        # Write local/SimplexLogic.tcl to disable CW/Voice IDs and Roger Beeps from leaking into DMR
        simplex_local_tcl = """
namespace eval SimplexLogic {
    proc send_short_ident {{hour -1} {minute -1}} {}
    proc send_long_ident {{hour -1} {minute -1}} {}
    proc send_rgr_sound {} {}
    proc squelch_open {{rx_id 0} {is_open 0}} {}
}
"""
        with open(f"{local_events_dir}/SimplexLogic.tcl", "w", encoding="utf-8") as wf:
            wf.write(simplex_local_tcl)

        # Optional EchoLink module configuration if user provided EchoLink credentials
        modules_str = ""
        echolink_conf = ""
        if self.bot_cfg.get("echolink_enabled") and self.bot_cfg.get("echolink_callsign") and self.bot_cfg.get("echolink_password"):
            modules_str = "MODULES=ModuleEchoLink"
            el_call = self.bot_cfg.get("echolink_callsign", self.bot_user)
            el_pass = self.bot_cfg.get("echolink_password", "")
            el_sysop = self.bot_cfg.get("echolink_sysop", "NinjaRadio Gateway")
            el_loc = self.bot_cfg.get("echolink_location", f"NinjaRadio TG {self.tg}")
            echolink_conf = f"""
[ModuleEchoLink]
NAME=EchoLink
ID=2
TIMEOUT=300
SERVERS=servers.echolink.org
CALLSIGN={el_call}
PASSWORD={el_pass}
SYSOPNAME={el_sysop}
LOCATION={el_loc}
MAX_QSOS=10
MAX_CONNECTIONS=11
LINK_IDLE_TIMEOUT=300
DESCRIPTION="NinjaRadio Multi-TG DMR Gateway (TG {self.tg})"
"""
            echolink_tcl = """
namespace eval EchoLink {
    proc is_receiving {rx {call ""}} {
        if {$rx == 1} {
            puts "NINJA_EVENT:ECHOLINK_START:0:$call"
        } else {
            puts "NINJA_EVENT:ECHOLINK_STOP:0:$call"
        }
        flush stdout
    }
}
"""
            with open(f"{local_events_dir}/EchoLink.tcl", "w", encoding="utf-8") as wf:
                wf.write(echolink_tcl)

        # Generate svxlink.conf
        # Rx1 receives DMR audio from Python (UDP 16kHz S16_LE).
        # Tx1 sends SvxReflector/EchoLink audio to Python (UDP 16kHz S16_LE).
        svxlink_conf = f"""[GLOBAL]
LOGICS=SimplexLogic,ReflectorLogic
LINKS=ReflectorLink
CFG_DIR={self.runtime_dir}/svxlink.d
TIMESTAMP_FORMAT="%Y-%m-%d %H:%M:%S"
CARD_SAMPLE_RATE=16000
CARD_CHANNELS=1

[SimplexLogic]
TYPE=Simplex
RX=Rx1
TX=Tx1
{modules_str}
CALLSIGN={self.bot_user}
SHORT_IDENT_INTERVAL=0
LONG_IDENT_INTERVAL=0
EVENT_HANDLER={events_tcl_path}
DEFAULT_LANG=en_US
RGR_SOUND_DELAY=-1
MUTE_RX_ON_TX=1
MUTE_TX_ON_RX=1
DTMF_CTRL_PTY={self.dtmf_pty_path}

[ReflectorLogic]
TYPE=Reflector
HOST={SVX_HOST}
PORT={SVX_PORT}
CALLSIGN={self.bot_user}
AUTH_KEY="{SVX_AUTH_KEY}"
DEFAULT_TG={self.tg}
MONITOR_TGS={self.tg}++
AUDIO_CODEC=OPUS
EVENT_HANDLER={events_tcl_path}
NODE_INFO_FILE={self.runtime_dir}/node_info.json

[ReflectorLink]
CONNECT_LOGICS=SimplexLogic:9,ReflectorLogic
DEFAULT_ACTIVE=1
TIMEOUT=0

[Rx1]
TYPE=Local
AUDIO_DEV=udp:127.0.0.1:{self.udp_rx_into_svx}
AUDIO_CHANNEL=0
SQL_DET=PTY
PTY_PATH={self.sql_pty_path}
SQL_START_DELAY=0
SQL_DELAY=0
SQL_HANGTIME=40
DEEMPHASIS=0
PREAMP=0
PEAK_METER=0

[Tx1]
TYPE=Local
AUDIO_DEV=udp:127.0.0.1:{self.udp_tx_from_svx}
AUDIO_CHANNEL=0
PTT_TYPE=PTY
PTT_PTY={self.ptt_pty_path}
TIMEOUT=300
TX_DELAY=0
PREEMPHASIS=0
{echolink_conf}
"""
        os.makedirs(f"{self.runtime_dir}/svxlink.d", exist_ok=True)
        with open(self.conf_path, "w", encoding="utf-8") as wf:
            wf.write(svxlink_conf)

        node_info = {
            "callsign": self.bot_user,
            "sw": "NinjaRadio-SvxBridge",
            "swVer": "2.0",
            "nodeLocation": f"NinjaRadio DMR TG {self.tg} (TS{self.slot})",
            "sysop": "BD62BOT"
        }
        with open(f"{self.runtime_dir}/node_info.json", "w", encoding="utf-8") as wf:
            json.dump(node_info, wf, indent=2)

    def start(self):
        if self.running:
            return
        self.running = True
        threading.Thread(target=self._audio_tx_from_svx_loop, daemon=True).start()
        threading.Thread(target=self._squelch_watchdog_loop, daemon=True).start()
        threading.Thread(target=self._process_supervisor_loop, daemon=True).start()

    def stop(self):
        self.running = False
        self.connected = False
        self._set_squelch(False)
        if self.proc:
            try:
                self.proc.terminate()
                self.proc.wait(timeout=2.0)
            except Exception:
                try:
                    self.proc.kill()
                except Exception:
                    pass
            self.proc = None

    def move_to_channel(self, target_channel_name: str, new_tg: int = None, new_slot: int = None, new_cfg: dict = None):
        """Moves this SvxLink bot to a new Reflector Talkgroup / room dynamically."""
        changed = False
        if target_channel_name != self.room_name:
            self.room_name = target_channel_name
            changed = True
        if new_tg is not None and int(new_tg) != self.tg:
            self.tg = int(new_tg)
            changed = True
        if new_slot is not None and int(new_slot) != self.slot:
            self.slot = int(new_slot)
        if new_cfg is not None:
            self.bot_cfg = new_cfg

        if changed:
            logger.info(f"[{self.bot_user}] 🔀 Retuning SvxLink bot to '{self.room_name}' (TG {self.tg} Slot {self.slot})")
            if self.proc and self.proc.poll() is None:
                try:
                    self.proc.terminate()
                except Exception:
                    pass

    def _set_squelch(self, open_sql: bool):
        """Writes 'O' (open) or 'Z' (close) to SvxLink Rx1 SQL_DET PTY."""
        if self.squelch_is_open == open_sql:
            return
        if os.path.exists(self.sql_pty_path):
            try:
                fd = os.open(self.sql_pty_path, os.O_WRONLY | os.O_NONBLOCK)
                os.write(fd, b"O" if open_sql else b"Z")
                os.close(fd)
                self.squelch_is_open = open_sql
            except Exception:
                pass

    def play_pcm_8k(self, pcm_8k: bytes):
        """
        Receives 8kHz 16-bit mono PCM from NinjaRadio DirectDmrEngine (UDP 12348),
        upsamples 2x to 16kHz S16_LE, opens Rx1 PTY squelch, and feeds SvxLink UDP socket.
        """
        if not self.running or len(pcm_8k) == 0:
            return
        # Echo suppression: do not feed DMR audio back if SvxLink is currently talking into DMR
        if self.is_transmitting_to_dmr or (time.time() - self.last_svx_audio_rx < 0.35):
            return

        try:
            self.last_dmr_rx_play = time.time()
            self._set_squelch(True)

            samples_8k = np.frombuffer(pcm_8k, dtype=np.int16).astype(np.float32)
            if len(samples_8k) == 0:
                return

            # Clean 2x linear interpolation (8kHz -> 16kHz)
            idx_orig = np.arange(len(samples_8k))
            idx_target = np.linspace(0, len(samples_8k) - 1, len(samples_8k) * 2)
            samples_16k = np.clip(np.interp(idx_target, idx_orig, samples_8k), -32768, 32767).astype(np.int16)

            self.usrp_sock.sendto(samples_16k.tobytes(), ("127.0.0.1", self.udp_rx_into_svx))
        except Exception as e:
            logger.debug(f"[{self.bot_user}] play_pcm_8k error: {e}")

    def _squelch_watchdog_loop(self):
        """Closes SvxLink Rx1 squelch cleanly when DMR RX stream stops, and unkeys DMR when SvxLink talker stops."""
        global last_svx_tx
        while self.running:
            time.sleep(0.05)
            now = time.time()

            # 1. Close Rx1 squelch 250ms after DMR RX stops
            if self.squelch_is_open and (now - self.last_dmr_rx_play > 0.25):
                self._set_squelch(False)

            # 2. Release Direct DMR PTT 450ms after SvxLink Tx1 audio stops
            if self.is_transmitting_to_dmr and (now - self.last_svx_audio_rx > 0.45):
                self._release_dmr_tx()

    def _release_dmr_tx(self):
        if not self.is_transmitting_to_dmr:
            return
        self.is_transmitting_to_dmr = False
        call_str = self.active_talker_call or self.bot_user
        dmr_id = self.active_talker_dmr_id or 5109999
        try:
            call_bytes = call_str.encode("ascii", errors="ignore")[:15].ljust(16, b"\x00")
            unkey_nptt = struct.pack(">4sIIII?16s", b"NPTT", int(dmr_id), int(self.tg), int(self.slot), 0, False, call_bytes)
            self.usrp_sock.sendto(unkey_nptt, (USRP_HOST, 32100))
            logger.info(f"[{self.bot_user}] ⏹️ DIRECT DMR RELEASE from {call_str} ({dmr_id}) -> TG {self.tg} Slot {self.slot}")
        except Exception:
            pass
        self.tx_pcm_buffer.clear()
        self.tx_conditioner.reset()

    def _audio_tx_from_svx_loop(self):
        """
        Listens on UDP `self.udp_tx_from_svx` for 16kHz S16_LE PCM emitted by SvxLink Tx1
        (when a remote station on SvxReflector or EchoLink talks on this TG),
        downsamples to 8kHz 320-byte frames, and dispatches NPTT frames with caller callsign carry-over to Core (32100).
        """
        global last_svx_tx
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind(("127.0.0.1", self.udp_tx_from_svx))
            sock.settimeout(0.5)
        except Exception as e:
            logger.error(f"[{self.bot_user}] Failed to bind SvxLink Tx UDP {self.udp_tx_from_svx}: {e}")
            return

        while self.running:
            try:
                data, _ = sock.recvfrom(8192)
                if not data or len(data) < 4:
                    continue

                # Ignore loopback if DMR RX is currently playing into this bot
                if self.squelch_is_open or (time.time() - self.last_dmr_rx_play < 0.25):
                    continue

                # Ignore our own bot callsign if echoed
                raw_talker = self.active_talker_call or ""
                if raw_talker.upper() == self.bot_user.upper():
                    continue

                # Check if audio frame is non-zero (ignore zero-fill silence frames)
                pcm_16k = np.frombuffer(data, dtype=np.int16)
                if len(pcm_16k) == 0:
                    continue
                rms = float(np.sqrt(np.mean(pcm_16k.astype(np.float32) ** 2)))
                if rms < 15.0 and not self.is_transmitting_to_dmr:
                    continue

                # Resolve caller identity (Carry-Over Callsign from SvxReflector / EchoLink)
                caller_input = raw_talker if raw_talker else self.bot_user
                user_dmr_id, user_callsign = resolve_svxlink_identity(caller_input)

                is_allowed, reason = check_svxlink_tx_permission(user_dmr_id, user_callsign, self.tg)
                if not is_allowed:
                    continue

                now = time.time()
                self.last_svx_audio_rx = now
                last_svx_tx = now

                if not self.is_transmitting_to_dmr:
                    self.is_transmitting_to_dmr = True
                    self.active_talker_dmr_id = user_dmr_id
                    self.tx_pcm_buffer.clear()
                    self.tx_conditioner.reset()
                    logger.info(f"[{self.bot_user}] 🔴 SVXLINK->DMR TX from {user_callsign} ({user_dmr_id}) in '{self.room_name}' -> DMR TG {self.tg} Slot {self.slot}")

                # Downsample 16kHz -> 8kHz (clean 2:1 decimation)
                n_8k = len(pcm_16k) // 2
                if n_8k > 0:
                    pcm_8k_arr = pcm_16k[:n_8k * 2].astype(np.float32).reshape(-1, 2).mean(axis=1)
                    peak = float(np.max(np.abs(pcm_8k_arr))) if len(pcm_8k_arr) > 0 else 0.0
                    if peak > 24000.0:
                        pcm_8k_arr = pcm_8k_arr * (24000.0 / peak)
                    pcm_8k_bytes = np.clip(pcm_8k_arr, -32768, 32767).astype(np.int16).tobytes()
                    self.tx_pcm_buffer.extend(pcm_8k_bytes)

                call_bytes = (user_callsign or "SVXLINK").encode("ascii", errors="ignore")[:15].ljust(16, b"\x00")
                while len(self.tx_pcm_buffer) >= 320:
                    chunk = bytes(self.tx_pcm_buffer[:320])
                    self.tx_pcm_buffer = self.tx_pcm_buffer[320:]
                    nptt_pkt = struct.pack(">4sIIII?16s", b"NPTT", int(user_dmr_id), int(self.tg), int(self.slot), len(chunk), True, call_bytes) + chunk
                    self.usrp_sock.sendto(nptt_pkt, (USRP_HOST, 32100))

            except socket.timeout:
                continue
            except Exception as ex:
                logger.debug(f"[{self.bot_user}] Error in _audio_tx_from_svx_loop: {ex}")
                time.sleep(0.02)

        sock.close()

    def _process_supervisor_loop(self):
        """Supervises the headless `svxlink` process and parses stdout events for talker callsign carry-over."""
        logger.info(f"[{self.bot_user}] 🚀 Launching SvxLink Gateway Bot for '{self.room_name}' <---> DMR TG {self.tg} (TS{self.slot})")
        while self.running and IS_BRIDGING:
            try:
                self._build_svxlink_environment()
                # Clean stale PTY symlinks
                for p in (self.sql_pty_path, self.ptt_pty_path, self.dtmf_pty_path):
                    if os.path.lexists(p):
                        try:
                            os.remove(p)
                        except Exception:
                            pass

                env = os.environ.copy()
                env["ASYNC_AUDIO_UDP_ZEROFILL"] = "1"

                self.proc = subprocess.Popen(
                    ["/usr/bin/svxlink", f"--config={self.conf_path}"],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True,
                    bufsize=1,
                    env=env
                )

                for line in iter(self.proc.stdout.readline, ""):
                    if not self.running:
                        break
                    sline = line.strip()
                    if not sline:
                        continue

                    if (
                        "ReflectorLogic: Connected to" in sline
                        or "ReflectorLogic: Connection established" in sline
                        or "ReflectorLogic: Authentication OK" in sline
                    ):
                        self.connected = True
                        logger.info(f"[{self.bot_user}] ✓ Connected to SvxReflector ({SVX_HOST}:{SVX_PORT}) on TG {self.tg}!")
                    elif "ReflectorLogic: Disconnected" in sline:
                        self.connected = False
                        logger.warning(f"[{self.bot_user}] ⚠️ Disconnected from SvxReflector: {sline}")
                    elif "Authentication failed" in sline or "Error" in sline or "WARNING" in sline or "ERROR" in sline:
                        logger.warning(f"[{self.bot_user}] SvxLink: {sline}")

                    # Parse custom Tcl event hooks for Callsign Carry-Over
                    if "NINJA_EVENT:" in sline:
                        parts = sline.split("NINJA_EVENT:", 1)[1].split(":")
                        ev_type = parts[0] if len(parts) > 0 else ""
                        ev_tg = parts[1] if len(parts) > 1 else str(self.tg)
                        ev_call = parts[2].strip() if len(parts) > 2 else ""

                        if ev_type in ("TALKER_START", "ECHOLINK_START"):
                            if ev_call and ev_call.upper() != self.bot_user.upper():
                                self.active_talker_call = ev_call
                                dmr_id, resolved_call = resolve_svxlink_identity(ev_call)
                                self.active_talker_dmr_id = dmr_id
                                self.active_talker_start = time.time()
                                logger.info(f"[{self.bot_user}] 🎙️ SvxReflector Talker Active: {ev_call} -> Mapped to DMR {resolved_call} ({dmr_id}) on TG {self.tg}")
                        elif ev_type in ("TALKER_STOP", "ECHOLINK_STOP"):
                            if ev_call and ev_call.upper() != self.bot_user.upper():
                                logger.info(f"[{self.bot_user}] ⏹️ SvxReflector Talker Stopped: {ev_call} on TG {self.tg}")
                                self._release_dmr_tx()
                                self.active_talker_call = None

                    # Also fallback-parse standard SvxLink log lines ("ReflectorLogic: Talker start on TG #510: YB0ABC")
                    elif "Talker start on TG #" in sline:
                        m = re.search(r"Talker start on TG #(\d+):\s*(\S+)", sline)
                        if m:
                            t_call = m.group(2).strip()
                            if t_call.upper() != self.bot_user.upper():
                                self.active_talker_call = t_call
                                dmr_id, _ = resolve_svxlink_identity(t_call)
                                self.active_talker_dmr_id = dmr_id
                    elif "Talker stop on TG #" in sline:
                        m = re.search(r"Talker stop on TG #(\d+):\s*(\S+)", sline)
                        if m:
                            t_call = m.group(2).strip()
                            if t_call.upper() != self.bot_user.upper():
                                self._release_dmr_tx()
                                self.active_talker_call = None

                if self.proc:
                    self.proc.wait()
            except Exception as e:
                logger.warning(f"[{self.bot_user}] SvxLink supervisor retry in 4s: {e}")
            self.connected = False
            if self.running:
                time.sleep(4.0)


def run_usrp_listener(gateway_pool: Dict[str, SvxGatewayBot]):
    """
    Listens for PCM audio frames from Core DirectDmrEngine on UDP port 12348
    and routes audio exclusively to the SvxLink bot(s) assigned to that Talkgroup.
    """
    global last_dmr_tx
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.bind(("0.0.0.0", SVX_BRIDGE_RX_PORT))
        logger.info(f"SvxLink Bridge DMR RX Listener bound on UDP port {SVX_BRIDGE_RX_PORT}")
    except Exception as e:
        logger.warning(f"Could not bind on UDP {SVX_BRIDGE_RX_PORT}: {e}")

    last_rx_log = 0.0
    while IS_BRIDGING:
        try:
            data, _ = sock.recvfrom(2048)
            if len(data) >= 32 and (time.time() - last_svx_tx > 0.45):
                keyup = struct.unpack(">I", data[12:16])[0]
                talkgroup = struct.unpack(">I", data[16:20])[0]
                pcm_8k = data[32:]

                target_bots = [bot for bot in gateway_pool.values() if bot.tg == talkgroup]
                if not target_bots:
                    continue

                if keyup == 0:
                    # Unkey frame from DMR Core: close squelch immediately
                    for bot in target_bots:
                        bot._set_squelch(False)
                    continue

                last_dmr_tx = time.time()
                if time.time() - last_rx_log > 2.5 and target_bots:
                    last_rx_log = time.time()
                    bot_names = ", ".join([f"{b.bot_user} ('{b.room_name}')" for b in target_bots])
                    logger.info(f"[SvxLink-Bridge] 🔊 Routing DMR RX (TG {talkgroup}) -> {bot_names}")

                for bot in target_bots:
                    bot.play_pcm_8k(pcm_8k)

        except Exception:
            time.sleep(0.02)


def sync_gateway_bots(usrp_sock: socket.socket):
    """Ensures each configured SvxLink bot is running and stationed on its assigned Talkgroup."""
    global GATEWAY_POOL
    configured_bots = load_configured_bots()
    configured_names = set()

    for b in configured_bots:
        b_name = re.sub(r"[^A-Za-z0-9\-]", "", str(b.get("name", "")))[:15].upper()
        b_channel = str(b.get("channel", "")).strip()
        b_tg = int(b.get("tg", 510))
        b_slot = int(b.get("slot") or (1 if b_tg in [510, 5108, 91, 1023] else 2))
        if not b_name or not b_channel:
            continue
        configured_names.add(b_name)

        if b_name not in GATEWAY_POOL:
            bot = SvxGatewayBot(b_channel, b_tg, usrp_sock, bot_user=b_name, slot=b_slot, bot_cfg=b)
            GATEWAY_POOL[b_name] = bot
            bot.start()
            time.sleep(0.3)
        else:
            bot = GATEWAY_POOL[b_name]
            if bot.room_name != b_channel or bot.tg != b_tg or bot.slot != b_slot:
                bot.move_to_channel(b_channel, b_tg, b_slot, new_cfg=b)

    for b_name in list(GATEWAY_POOL.keys()):
        if b_name not in configured_names:
            logger.info(f"[{b_name}] Stopping decommissioned SvxLink bot...")
            GATEWAY_POOL[b_name].stop()
            GATEWAY_POOL.pop(b_name, None)


def fetch_svxreflector_status() -> Dict[str, Any]:
    """Queries SvxReflector's built-in HTTP status port (8080) for connected external nodes and active talkers."""
    try:
        resp = requests.get(f"http://{SVX_HOST}:{SVX_HTTP_PORT}/status", timeout=0.6)
        if resp.status_code == 200:
            return resp.json()
    except Exception:
        pass
    # Build live fallback matrix from our active bots
    fallback_nodes = {}
    for b in GATEWAY_POOL.values():
        fallback_nodes[b.bot_user] = {
            "callsign": b.bot_user,
            "tg": b.tg,
            "monitoredTGs": [b.tg],
            "isTalker": bool(b.squelch_is_open or b.active_talker_call),
            "nodeLocation": f"NinjaRadio DMR TG {b.tg} (TS{b.slot})",
            "sw": "NinjaRadio-DirectDMR",
            "swVer": "2.0"
        }
    return {"nodes": fallback_nodes}


class SvxBridgeApiHandler(BaseHTTPRequestHandler):
    usrp_socket = None

    def log_message(self, format, *args):
        pass

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path in ("/channels", "/channels/", "/status", "/status/"):
            mappings = load_room_mappings()
            ref_status = fetch_svxreflector_status()
            channels = []
            for idx, (cname, tg) in enumerate(mappings.items(), start=1):
                slot = 1 if tg in [510, 5108, 91, 1023] else 2
                channels.append({
                    "id": f"svx_{cname}",
                    "channel_id": idx,
                    "name": cname,
                    "channel": cname,
                    "targetTg": tg,
                    "tg": tg,
                    "slot": slot,
                    "description": f"SvxReflector TG {tg} (TS{slot})",
                    "isActive": True
                })
            active_bots = [
                {
                    "name": bot.bot_user,
                    "channel": bot.room_name,
                    "tg": bot.tg,
                    "slot": bot.slot,
                    "connected": bot.connected,
                    "active_talker": bot.active_talker_call,
                    "status": "CONNECTED" if bot.connected else "CONNECTING"
                }
                for bot in GATEWAY_POOL.values()
            ]
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({
                "status": "SUCCESS",
                "reflector_host": f"{SVX_HOST}:{SVX_PORT}",
                "channels": channels,
                "active_bots": active_bots,
                "reflector_nodes": ref_status.get("nodes", {})
            }).encode("utf-8"))
            return

        elif parsed.path in ("/logs", "/logs/"):
            log_lines = []
            if os.path.exists("/app/data/svxlink_bridge.log"):
                try:
                    with open("/app/data/svxlink_bridge.log", "r", encoding="utf-8") as f:
                        log_lines = [l.strip() for l in f.readlines()[-100:]]
                except Exception:
                    pass
            ref_status = fetch_svxreflector_status()
            active_bots = [
                {
                    "name": bot.bot_user,
                    "channel": bot.room_name,
                    "tg": bot.tg,
                    "slot": bot.slot,
                    "connected": bot.connected,
                    "active_talker": bot.active_talker_call
                }
                for bot in GATEWAY_POOL.values()
            ]
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({
                "status": "SUCCESS",
                "reflector_host": f"{SVX_HOST}:{SVX_PORT}",
                "active_bots": active_bots,
                "reflector_nodes": ref_status.get("nodes", {}),
                "recent_logs": log_lines
            }).encode("utf-8"))
            return

        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        parsed = urlparse(self.path)
        if parsed.path in ("/sync", "/sync/", "/bots", "/bots/", "/channels", "/channels/"):
            try:
                global ROOM_MAPPINGS
                ROOM_MAPPINGS = load_room_mappings()
                if self.usrp_socket:
                    sync_gateway_bots(self.usrp_socket)
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({
                    "status": "SUCCESS",
                    "message": "Synced SvxLink gateway bots with SvxReflector",
                    "bot_count": len(GATEWAY_POOL)
                }).encode("utf-8"))
                return
            except Exception as e:
                self.send_response(500)
                self.end_headers()
                self.wfile.write(json.dumps({"error": str(e)}).encode("utf-8"))
                return

        self.send_response(404)
        self.end_headers()


def run_bridge_http_server(usrp_sock: socket.socket):
    server_address = ("0.0.0.0", SVX_BRIDGE_HTTP_PORT)

    class HandlerWithSock(SvxBridgeApiHandler):
        usrp_socket = usrp_sock

    try:
        httpd = ThreadingHTTPServer(server_address, HandlerWithSock)
        logger.info(f"✓ Started SvxLink Bridge HTTP Control API on port {SVX_BRIDGE_HTTP_PORT}")
        httpd.serve_forever()
    except Exception as e:
        logger.warning(f"Could not start SvxLink Bridge HTTP server: {e}")


def config_file_watcher_loop(usrp_sock: socket.socket):
    """Background thread watching /app/data/config.json for dynamic SvxLink room/bot changes."""
    global ROOM_MAPPINGS
    last_mtime = 0.0
    while IS_BRIDGING:
        time.sleep(3.0)
        if os.path.exists(CONFIG_PATH):
            try:
                mtime = os.path.getmtime(CONFIG_PATH)
                if last_mtime > 0 and mtime > last_mtime:
                    logger.info("⚡ Detected changes in config.json! Reloading SvxLink Multi-TG bots...")
                    ROOM_MAPPINGS = load_room_mappings()
                    sync_gateway_bots(usrp_sock)
                last_mtime = mtime
            except Exception as e:
                logger.debug(f"config_watcher error: {e}")


def main():
    global ROOM_MAPPINGS
    logger.info("======================================================")
    logger.info("  NinjaRadio Multi-TG SvxReflector & EchoLink Bridge  ")
    logger.info("======================================================")
    logger.info(f"SvxReflector Target : {SVX_HOST}:{SVX_PORT}")
    logger.info(f"Direct DMR Target   : {USRP_HOST}:32100 (RX on UDP {SVX_BRIDGE_RX_PORT})")

    ROOM_MAPPINGS = load_room_mappings()
    for room_name, tg_val in ROOM_MAPPINGS.items():
        logger.info(f"  • SvxReflector Room '{room_name}' <---> DMR TG {tg_val}")

    usrp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

    # 1. Spawn Multi-TG SvxLink Bot Pool
    sync_gateway_bots(usrp_sock)

    # 2. Start config.json hot-reload watcher
    threading.Thread(target=config_file_watcher_loop, args=(usrp_sock,), daemon=True).start()

    # 3. Start Direct DMR PCM Listener thread (UDP 12348)
    threading.Thread(target=run_usrp_listener, args=(GATEWAY_POOL,), daemon=True).start()

    # 4. Start HTTP Control API (TCP 12349)
    threading.Thread(target=run_bridge_http_server, args=(usrp_sock,), daemon=True).start()

    try:
        while True:
            time.sleep(5)
    except KeyboardInterrupt:
        logger.info("Stopping SvxLink Bridge...")
        for bot in GATEWAY_POOL.values():
            bot.stop()


if __name__ == "__main__":
    main()
