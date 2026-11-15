# FreeDMR Conference Bridge & Rules Configuration
#
# IMPORTANT: Keep this EMPTY.
# HOTSPOT-MASTER uses GENERATOR, so FreeDMR renames it at runtime into
# HOTSPOT-MASTER-0 .. HOTSPOT-MASTER-99. Any static rule that references
# 'HOTSPOT-MASTER' points to a system that no longer exists and crashes the
# router with KeyError: 'HOTSPOT-MASTER' (no TG gets through).
#
# Routing is built dynamically instead:
#   - Hotspot static TGs  -> TS1_STATIC / TS2_STATIC in [HOTSPOT-MASTER]
#   - CORE-BRIDGE (PEER)  -> always ACTIVE member of every TG bridge
#                            (patched into bridge_master.py by entrypoint.sh)

BRIDGES = {}

if __name__ == '__main__':
    from pprint import pprint
    pprint(BRIDGES)
