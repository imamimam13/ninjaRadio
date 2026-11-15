# FreeDMR Conference Bridge & Rules Configuration
# Bridges traffic between HOTSPOT-MASTER (physical MMDVM/Pi-Star on port 62031)
# and CORE-BRIDGE (PEER connecting to core on port 62035)

BRIDGES = {
    '1025': [
        {'SYSTEM': 'HOTSPOT-MASTER', 'TS': 2, 'TGID': 1025, 'ON': [1025], 'OFF': [], 'TO_TYPE': 'NONE', 'TIMEOUT': 0, 'RESET': [], 'ACTIVE': True},
        {'SYSTEM': 'CORE-BRIDGE', 'TS': 2, 'TGID': 1025, 'ON': [1025], 'OFF': [], 'TO_TYPE': 'NONE', 'TIMEOUT': 0, 'RESET': [], 'ACTIVE': True},
    ],
    '1023': [
        {'SYSTEM': 'HOTSPOT-MASTER', 'TS': 1, 'TGID': 1023, 'ON': [1023], 'OFF': [], 'TO_TYPE': 'NONE', 'TIMEOUT': 0, 'RESET': [], 'ACTIVE': True},
        {'SYSTEM': 'CORE-BRIDGE', 'TS': 1, 'TGID': 1023, 'ON': [1023], 'OFF': [], 'TO_TYPE': 'NONE', 'TIMEOUT': 0, 'RESET': [], 'ACTIVE': True},
    ],
    '510': [
        {'SYSTEM': 'HOTSPOT-MASTER', 'TS': 2, 'TGID': 510, 'ON': [510], 'OFF': [], 'TO_TYPE': 'NONE', 'TIMEOUT': 0, 'RESET': [], 'ACTIVE': True},
        {'SYSTEM': 'CORE-BRIDGE', 'TS': 2, 'TGID': 510, 'ON': [510], 'OFF': [], 'TO_TYPE': 'NONE', 'TIMEOUT': 0, 'RESET': [], 'ACTIVE': True},
    ],
    '5109': [
        {'SYSTEM': 'HOTSPOT-MASTER', 'TS': 2, 'TGID': 5109, 'ON': [5109], 'OFF': [], 'TO_TYPE': 'NONE', 'TIMEOUT': 0, 'RESET': [], 'ACTIVE': True},
        {'SYSTEM': 'CORE-BRIDGE', 'TS': 2, 'TGID': 5109, 'ON': [5109], 'OFF': [], 'TO_TYPE': 'NONE', 'TIMEOUT': 0, 'RESET': [], 'ACTIVE': True},
    ],
    '510990': [
        {'SYSTEM': 'HOTSPOT-MASTER', 'TS': 2, 'TGID': 510990, 'ON': [510990], 'OFF': [], 'TO_TYPE': 'NONE', 'TIMEOUT': 0, 'RESET': [], 'ACTIVE': True},
        {'SYSTEM': 'CORE-BRIDGE', 'TS': 2, 'TGID': 510990, 'ON': [510990], 'OFF': [], 'TO_TYPE': 'NONE', 'TIMEOUT': 0, 'RESET': [], 'ACTIVE': True},
    ],
    '5101': [
        {'SYSTEM': 'HOTSPOT-MASTER', 'TS': 2, 'TGID': 5101, 'ON': [5101], 'OFF': [], 'TO_TYPE': 'NONE', 'TIMEOUT': 0, 'RESET': [], 'ACTIVE': True},
        {'SYSTEM': 'CORE-BRIDGE', 'TS': 2, 'TGID': 5101, 'ON': [5101], 'OFF': [], 'TO_TYPE': 'NONE', 'TIMEOUT': 0, 'RESET': [], 'ACTIVE': True},
    ],
    '5102': [
        {'SYSTEM': 'HOTSPOT-MASTER', 'TS': 2, 'TGID': 5102, 'ON': [5102], 'OFF': [], 'TO_TYPE': 'NONE', 'TIMEOUT': 0, 'RESET': [], 'ACTIVE': True},
        {'SYSTEM': 'CORE-BRIDGE', 'TS': 2, 'TGID': 5102, 'ON': [5102], 'OFF': [], 'TO_TYPE': 'NONE', 'TIMEOUT': 0, 'RESET': [], 'ACTIVE': True},
    ],
    '5103': [
        {'SYSTEM': 'HOTSPOT-MASTER', 'TS': 2, 'TGID': 5103, 'ON': [5103], 'OFF': [], 'TO_TYPE': 'NONE', 'TIMEOUT': 0, 'RESET': [], 'ACTIVE': True},
        {'SYSTEM': 'CORE-BRIDGE', 'TS': 2, 'TGID': 5103, 'ON': [5103], 'OFF': [], 'TO_TYPE': 'NONE', 'TIMEOUT': 0, 'RESET': [], 'ACTIVE': True},
    ],
    '5108': [
        {'SYSTEM': 'HOTSPOT-MASTER', 'TS': 1, 'TGID': 5108, 'ON': [5108], 'OFF': [], 'TO_TYPE': 'NONE', 'TIMEOUT': 0, 'RESET': [], 'ACTIVE': True},
        {'SYSTEM': 'CORE-BRIDGE', 'TS': 1, 'TGID': 5108, 'ON': [5108], 'OFF': [], 'TO_TYPE': 'NONE', 'TIMEOUT': 0, 'RESET': [], 'ACTIVE': True},
    ],
    '555': [
        {'SYSTEM': 'HOTSPOT-MASTER', 'TS': 2, 'TGID': 555, 'ON': [555], 'OFF': [], 'TO_TYPE': 'NONE', 'TIMEOUT': 0, 'RESET': [], 'ACTIVE': True},
        {'SYSTEM': 'CORE-BRIDGE', 'TS': 2, 'TGID': 555, 'ON': [555], 'OFF': [], 'TO_TYPE': 'NONE', 'TIMEOUT': 0, 'RESET': [], 'ACTIVE': True},
    ],
    '9990': [
        {'SYSTEM': 'HOTSPOT-MASTER', 'TS': 2, 'TGID': 9990, 'ON': [9990], 'OFF': [], 'TO_TYPE': 'NONE', 'TIMEOUT': 0, 'RESET': [], 'ACTIVE': True},
        {'SYSTEM': 'CORE-BRIDGE', 'TS': 2, 'TGID': 9990, 'ON': [9990], 'OFF': [], 'TO_TYPE': 'NONE', 'TIMEOUT': 0, 'RESET': [], 'ACTIVE': True},
    ],
    '91': [
        {'SYSTEM': 'HOTSPOT-MASTER', 'TS': 1, 'TGID': 91, 'ON': [91], 'OFF': [], 'TO_TYPE': 'NONE', 'TIMEOUT': 0, 'RESET': [], 'ACTIVE': True},
        {'SYSTEM': 'CORE-BRIDGE', 'TS': 1, 'TGID': 91, 'ON': [91], 'OFF': [], 'TO_TYPE': 'NONE', 'TIMEOUT': 0, 'RESET': [], 'ACTIVE': True},
    ]
}
