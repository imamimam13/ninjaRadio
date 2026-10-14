"""Bundled directory of DMR masters and common talkgroups.

The app ships a small curated, offline-first directory so a user can pick a
network without typing hosts/ports. A remote refresh hook is provided but always
falls back to the bundled data when offline.

This is intentionally data-only; nothing here needs the network to import.
"""

import logging
import urllib.request
from typing import Dict, List, Optional

logger = logging.getLogger("NinjaDMR-Directory")

# protocol keys match the values used elsewhere: "homebrew" | "odtp"
MASTERS: List[Dict] = [
    {"name": "BrandMeister (US)", "protocol": "homebrew", "host": "brandmeister.network", "port": 62031, "region": "US"},
    {"name": "BrandMeister (DE)", "protocol": "homebrew", "host": "brandmeister.network", "port": 62031, "region": "DE"},
    {"name": "DMR+ (Master)", "protocol": "homebrew", "host": "master.dmrplus.net", "port": 62031, "region": "EU"},
    {"name": "TGIF Network", "protocol": "homebrew", "host": "tgif.network", "port": 62031, "region": "US"},
    {"name": "Phoenix (DMR)", "protocol": "homebrew", "host": "phoenix.dmrx.net", "port": 62031, "region": "EU"},
    {"name": "ODTP (BrandMeister Rewind)", "protocol": "odtp", "host": "brandmeister.network", "port": 54005, "region": "US"},
]

# A handful of well-known talkgroups (id, name, scope).
TALKGROUPS: List[Dict] = [
    {"id": 9990, "name": "Parrot", "scope": "worldwide", "note": "Echo test"},
    {"id": 91, "name": "Worldwide", "scope": "worldwide", "note": ""},
    {"id": 92, "name": "Europe", "scope": "EU", "note": ""},
    {"id": 93, "name": "North America", "scope": "NA", "note": ""},
    {"id": 1, "name": "Worldwide (DMR+)", "scope": "worldwide", "note": ""},
    {"id": 3100, "name": "USA Nationwide", "scope": "US", "note": ""},
    {"id": 235, "name": "United Kingdom", "scope": "UK", "note": ""},
    {"id": 262, "name": "Germany", "scope": "DE", "note": ""},
    {"id": 505, "name": "Australia", "scope": "AU", "note": ""},
    {"id": 5100, "name": "Canada", "scope": "CA", "note": ""},
]

_REMOTE_MASTER_URL = "https://api.brandmeister.network/v1.0/groups/"


def masters(protocol: Optional[str] = None) -> List[Dict]:
    if protocol:
        return [m for m in MASTERS if m["protocol"] == protocol]
    return list(MASTERS)


def talkgroups() -> List[Dict]:
    return list(TALKGROUPS)


def masters_json(protocol: Optional[str] = None) -> str:
    import json

    return json.dumps(masters(protocol))


def talkgroups_json() -> str:
    import json

    return json.dumps(talkgroups())


def find_master(name: str) -> Optional[Dict]:
    for m in MASTERS:
        if m["name"].lower() == name.lower():
            return dict(m)
    return None


def find_talkgroup(tg_id: int) -> Optional[Dict]:
    for tg in TALKGROUPS:
        if tg["id"] == int(tg_id):
            return dict(tg)
    return None


def refresh_masters(url: str = _REMOTE_MASTER_URL, timeout: float = 5.0) -> List[Dict]:
    """Best-effort remote refresh; always falls back to the bundled list."""
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:
            import json

            payload = json.loads(resp.read().decode("utf-8"))
        # BrandMeister returns a list of TG objects; we only surface masters we know.
        logger.info("Directory refresh returned %d entries", len(payload))
    except Exception as exc:  # noqa: BLE001 - offline is expected
        logger.debug("Directory refresh skipped: %s", exc)
    return masters()
