# ===============================================================================
# FreeDMR / HBlink3 Conference Bridges Rules File (rules.py)
# Bridges between Hotspots (HOTSPOT-MASTER) and NinjaRadio Core (CORE-BRIDGE)
# ===============================================================================

BRIDGES = {
    'TG1025': [
        {'SYSTEM': 'HOTSPOT-MASTER', 'TS': 2, 'TGID': 1025, 'ACTIVE': True, 'TIMEOUT': 0, 'TO_TYPE': 'NONE', 'ON': [1025], 'OFF': []},
        {'SYSTEM': 'CORE-BRIDGE',    'TS': 2, 'TGID': 1025, 'ACTIVE': True, 'TIMEOUT': 0, 'TO_TYPE': 'NONE', 'ON': [1025], 'OFF': []},
    ],
    'TG510': [
        {'SYSTEM': 'HOTSPOT-MASTER', 'TS': 2, 'TGID': 510,  'ACTIVE': True, 'TIMEOUT': 0, 'TO_TYPE': 'NONE', 'ON': [510],  'OFF': []},
        {'SYSTEM': 'CORE-BRIDGE',    'TS': 2, 'TGID': 510,  'ACTIVE': True, 'TIMEOUT': 0, 'TO_TYPE': 'NONE', 'ON': [510],  'OFF': []},
    ],
    'TG5109': [
        {'SYSTEM': 'HOTSPOT-MASTER', 'TS': 2, 'TGID': 5109, 'ACTIVE': True, 'TIMEOUT': 0, 'TO_TYPE': 'NONE', 'ON': [5109], 'OFF': []},
        {'SYSTEM': 'CORE-BRIDGE',    'TS': 2, 'TGID': 5109, 'ACTIVE': True, 'TIMEOUT': 0, 'TO_TYPE': 'NONE', 'ON': [5109], 'OFF': []},
    ],
    'TG5101': [
        {'SYSTEM': 'HOTSPOT-MASTER', 'TS': 2, 'TGID': 5101, 'ACTIVE': True, 'TIMEOUT': 0, 'TO_TYPE': 'NONE', 'ON': [5101], 'OFF': []},
        {'SYSTEM': 'CORE-BRIDGE',    'TS': 2, 'TGID': 5101, 'ACTIVE': True, 'TIMEOUT': 0, 'TO_TYPE': 'NONE', 'ON': [5101], 'OFF': []},
    ],
    'TG5102': [
        {'SYSTEM': 'HOTSPOT-MASTER', 'TS': 2, 'TGID': 5102, 'ACTIVE': True, 'TIMEOUT': 0, 'TO_TYPE': 'NONE', 'ON': [5102], 'OFF': []},
        {'SYSTEM': 'CORE-BRIDGE',    'TS': 2, 'TGID': 5102, 'ACTIVE': True, 'TIMEOUT': 0, 'TO_TYPE': 'NONE', 'ON': [5102], 'OFF': []},
    ],
    'TG5103': [
        {'SYSTEM': 'HOTSPOT-MASTER', 'TS': 2, 'TGID': 5103, 'ACTIVE': True, 'TIMEOUT': 0, 'TO_TYPE': 'NONE', 'ON': [5103], 'OFF': []},
        {'SYSTEM': 'CORE-BRIDGE',    'TS': 2, 'TGID': 5103, 'ACTIVE': True, 'TIMEOUT': 0, 'TO_TYPE': 'NONE', 'ON': [5103], 'OFF': []},
    ],
    'TG1023': [
        {'SYSTEM': 'HOTSPOT-MASTER', 'TS': 2, 'TGID': 1023, 'ACTIVE': True, 'TIMEOUT': 0, 'TO_TYPE': 'NONE', 'ON': [1023], 'OFF': []},
        {'SYSTEM': 'CORE-BRIDGE',    'TS': 2, 'TGID': 1023, 'ACTIVE': True, 'TIMEOUT': 0, 'TO_TYPE': 'NONE', 'ON': [1023], 'OFF': []},
    ],
    'TG555': [
        {'SYSTEM': 'HOTSPOT-MASTER', 'TS': 2, 'TGID': 555,  'ACTIVE': True, 'TIMEOUT': 0, 'TO_TYPE': 'NONE', 'ON': [555],  'OFF': []},
        {'SYSTEM': 'CORE-BRIDGE',    'TS': 2, 'TGID': 555,  'ACTIVE': True, 'TIMEOUT': 0, 'TO_TYPE': 'NONE', 'ON': [555],  'OFF': []},
    ],
    'TG91': [
        {'SYSTEM': 'HOTSPOT-MASTER', 'TS': 1, 'TGID': 91,   'ACTIVE': True, 'TIMEOUT': 0, 'TO_TYPE': 'NONE', 'ON': [91],   'OFF': []},
        {'SYSTEM': 'CORE-BRIDGE',    'TS': 1, 'TGID': 91,   'ACTIVE': True, 'TIMEOUT': 0, 'TO_TYPE': 'NONE', 'ON': [91],   'OFF': []},
    ],
    'PARROT': [
        {'SYSTEM': 'HOTSPOT-MASTER', 'TS': 2, 'TGID': 9990, 'ACTIVE': True, 'TIMEOUT': 0, 'TO_TYPE': 'NONE', 'ON': [9990], 'OFF': []},
        {'SYSTEM': 'CORE-BRIDGE',    'TS': 2, 'TGID': 9990, 'ACTIVE': True, 'TIMEOUT': 0, 'TO_TYPE': 'NONE', 'ON': [9990], 'OFF': []},
    ],
}
