#!/bin/bash
set -e

echo "=================================================="
echo "  Starting NinjaRadio FreeDMR Engine              "
echo "  Port DMR Master / Hotspot : 62031/udp           "
echo "  Port OpenBridge Trunk     : 62030/udp           "
echo "=================================================="

mkdir -p /opt/freedmr/json

# Initialize alias files in the exact format expected by dmr_utils3
echo '{"rptrs": []}' > "/opt/freedmr/json/peer_ids.json"
echo '{"talkgroups": [{"id": 1025, "callsign": "TG 1025"}, {"id": 510, "callsign": "TG 510"}, {"id": 5109, "callsign": "TG 5109"}, {"id": 5101, "callsign": "TG 5101"}, {"id": 5102, "callsign": "TG 5102"}, {"id": 5103, "callsign": "TG 5103"}, {"id": 9990, "callsign": "TG 9990"}, {"id": 91, "callsign": "TG 91"}, {"id": 51024, "callsign": "TG 51024"}, {"id": 510991, "callsign": "TG 510991"}]}' > "/opt/freedmr/json/talkgroup_ids.json"
echo '{"users": []}' > "/opt/freedmr/json/local_subscriber_ids.json"
echo '{"users": []}' > "/opt/freedmr/json/subscriber_ids.json"

# Synchronize subscriber database from NinjaRadio SQLite DB if available
python3 -c "
import sqlite3, json, os

db_paths = ['/app/data/ninjaradio.db', '/data/ninjaradio.db']
users_list = [
    {
        'id': 5109999,
        'callsign': 'N1NJA',
        'fname': 'NinjaRadio Gateway',
        'surname': '',
        'city': 'ID',
        'state': 'DMR',
        'country': 'Indonesia'
    }
]
for p in db_paths:
    if os.path.exists(p):
        try:
            with sqlite3.connect(p) as conn:
                cur = conn.cursor()
                rows = cur.execute('SELECT dmr_id, callsign, name FROM callbook WHERE dmr_id > 0').fetchall()
                for d_id, call, name in rows:
                    users_list.append({
                        'id': int(d_id),
                        'callsign': str(call or ''),
                        'fname': str(name or call or ''),
                        'surname': '',
                        'city': '',
                        'state': '',
                        'country': ''
                    })
            break
        except Exception:
            pass

if users_list:
    with open('/opt/freedmr/json/subscriber_ids.json', 'w', encoding='utf-8') as sf:
        json.dump({'users': users_list}, sf)
    print(f'✓ Exported {len(users_list)} subscribers to FreeDMR json')
" 2>/dev/null || true

cd /opt/freedmr

# Ensure config aliases exist for whatever FreeDMR looks for
cp /opt/freedmr/freedmr.cfg /opt/freedmr/FreeDMR.cfg 2>/dev/null || true
cp /opt/freedmr/rules.py /opt/freedmr/Rules.py 2>/dev/null || true

# Neutralize i8n_voice_map to prevent KeyError on missing audio phonetic mappings
echo "voiceMap = {}" > /opt/freedmr/i8n_voice_map.py

# Hook dynamic SQLite Self-Care authentication into hblink.py
python3 - <<'PYEOF' || true
import os
import re
hb_path = '/opt/freedmr/hblink.py'
if os.path.exists(hb_path):
    with open(hb_path, 'r', encoding='utf-8') as f:
        src = f.read()

    # Pattern matches _calc_hash line with any trailing whitespace
    pattern = r"_calc_hash\s*=\s*bhex\(sha256\(_salt_str\+self\._config\['PASSPHRASE'\]\)\.hexdigest\(\)\)"
    if 'ninjaradio_auth' not in src and re.search(pattern, src):
        new_block = (
            "try:\n"
            "                        from ninjaradio_auth import verify_peer_login\n"
            "                        _is_valid = verify_peer_login(int(_this_peer['RADIO_ID']), _salt_str, _sent_hash, self._config['PASSPHRASE'])\n"
            "                    except Exception as _ae:\n"
            "                        _is_valid = (_sent_hash == bhex(sha256(_salt_str+self._config['PASSPHRASE']).hexdigest()))\n"
            "                    _calc_hash = _sent_hash if _is_valid else b'INVALID_HASH_VALUE_FAIL'"
        )
        src = re.sub(pattern, new_block, src, count=1)
        print('✓ Integrated dynamic Self-Care DMR password authentication into FreeDMR')

    if 'self.send_config()' in src:
        src = src.replace('self.send_config()', 'self._factory.send_config()')
        print('✓ Patched FreeDMR reporting socket handler in hblink.py')

    # 1. Prevent a single peer's RPTO options (e.g. mobile app sending TS2_1=555;) from wiping out
    # system-wide TS1_STATIC / TS2_STATIC for other MMDVM hotspots connected to the same MASTER port!
    old_rpto = "                self._CONFIG['SYSTEMS'][self._system]['OPTIONS'] = _this_peer['OPTIONS'].decode()"
    new_rpto = (
        "                if self._config.get('MAX_PEERS', 1) <= 1:\n"
        "                    self._CONFIG['SYSTEMS'][self._system]['OPTIONS'] = _this_peer['OPTIONS'].decode()\n"
        "                else:\n"
        "                    try:\n"
        "                        import re as _re\n"
        "                        _opt_s = _this_peer['OPTIONS'].decode('ascii', errors='ignore')\n"
        "                        _p_tgs = set()\n"
        "                        for _m in _re.finditer(r'(?:TS[12](?:_\\d+)?|StartTG)\\s*=\\s*([0-9,]+)', _opt_s):\n"
        "                            for _p in _m.group(1).split(','):\n"
        "                                if _p.strip().isdigit() and int(_p.strip()) > 0:\n"
        "                                    _p_tgs.add(int(_p.strip()))\n"
        "                        if _p_tgs:\n"
        "                            _this_peer['SUB_TGS'] = _p_tgs\n"
        "                            _this_peer['ACTIVE_TG'] = next(iter(_p_tgs))\n"
        "                            _this_peer['ACTIVE_TG_TIME'] = time()\n"
        "                    except Exception:\n"
        "                        pass"
    )
    if old_rpto in src and "SUB_TGS" not in src:
        src = src.replace(old_rpto, new_rpto)
        print('✓ Patched FreeDMR hblink.py: isolated per-peer RPTO options on multi-peer MASTER')

    # 2. Track each hotspot peer's active TX TG and isolate send_peers so two hotspots on different TGs
    # (e.g. one on TG 1025 and another on TG 51024/555) never cut off each other's RF timeslot!
    old_dmrd_rx = "                # The basic purpose of a master is to repeat to the peers"
    new_dmrd_rx = (
        "                try:\n"
        "                    _tx_tg_int = int_id(_dst_id)\n"
        "                    if _tx_tg_int > 0 and _tx_tg_int != 4000:\n"
        "                        self._peers[_peer_id]['ACTIVE_TG'] = _tx_tg_int\n"
        "                        self._peers[_peer_id]['ACTIVE_TG_TIME'] = time()\n"
        "                except Exception:\n"
        "                    pass\n"
        "                # The basic purpose of a master is to repeat to the peers"
    )
    if old_dmrd_rx in src and "ACTIVE_TG_TIME" not in src:
        src = src.replace(old_dmrd_rx, new_dmrd_rx, 1)

    old_send_peers = (
        "    def send_peers(self, _packet, _hops = b'', _ber = b'\\x00', _rssi = b'\\x00',_source_server = b'\\x00\\x00\\x00\\x00'):\n"
        "        for _peer in self._peers:\n"
        "            if len(_packet) < 54:\n"
        "                _packet =b''.join([_packet,_ber,_rssi])\n"
        "            self.send_peer(_peer, _packet)"
    )
    new_send_peers = (
        "    def send_peers(self, _packet, _hops = b'', _ber = b'\\x00', _rssi = b'\\x00',_source_server = b'\\x00\\x00\\x00\\x00'):\n"
        "        _pkt_tg = int_id(_packet[8:11]) if _packet[:4] == DMRD and len(_packet) >= 20 else 0\n"
        "        _pkt_strid = _packet[16:20] if _packet[:4] == DMRD and len(_packet) >= 20 else b''\n"
        "        _pkt_slot = (2 if (_packet[15] & 0x80) else 1) if _packet[:4] == DMRD and len(_packet) >= 20 else 2\n"
        "        _pkt_vterm = ((_packet[15] & 0x30) >> 4 == 2 and (_packet[15] & 0x0F) in (0, 2)) if _packet[:4] == DMRD and len(_packet) >= 20 else False\n"
        "        _now_t = time()\n"
        "        for _peer in list(self._peers.keys()):\n"
        "            _pinfo = self._peers.get(_peer)\n"
        "            if not _pinfo:\n"
        "                continue\n"
        "            if _pkt_tg > 0:\n"
        "                _sub_tgs = _pinfo.get('SUB_TGS')\n"
        "                _act_tg = _pinfo.get('ACTIVE_TG', 0)\n"
        "                if _sub_tgs and _pkt_tg not in _sub_tgs and _pkt_tg != _act_tg:\n"
        "                    continue\n"
        "                elif not _sub_tgs and _act_tg and (_now_t - _pinfo.get('ACTIVE_TG_TIME', 0)) < 1800 and _pkt_tg != _act_tg:\n"
        "                    continue\n"
        "                elif not _sub_tgs and not _act_tg and _pkt_tg in (555, 200):\n"
        "                    continue\n"
        "                _plocks = _pinfo.setdefault('SLOT_LOCKS', {})\n"
        "                _plock = _plocks.get(_pkt_slot)\n"
        "                if _plock and _now_t < _plock.get('until', 0.0):\n"
        "                    if _plock.get('stream_id') != _pkt_strid or _plock.get('tg') != _pkt_tg:\n"
        "                        continue\n"
        "                _plocks[_pkt_slot] = {'stream_id': _pkt_strid, 'tg': _pkt_tg, 'until': _now_t + (0.2 if _pkt_vterm else 1.5)}\n"
        "            if len(_packet) < 54:\n"
        "                _packet =b''.join([_packet,_ber,_rssi])\n"
        "            self.send_peer(_peer, _packet)"
    )
    if old_send_peers in src and "SLOT_LOCKS" not in src:
        src = src.replace(old_send_peers, new_send_peers)
        print('✓ Patched FreeDMR hblink.py: per-hotspot TG & slot lock isolation in send_peers')

    with open(hb_path, 'w', encoding='utf-8') as f:
        f.write(src)
PYEOF

# Make PEER systems (CORE-BRIDGE -> NinjaRadio core:62035) an always-ACTIVE member of every
# dynamically created TG bridge, on the same slot the bridge was created on. Without this,
# FreeDMR creates the PEER entries as ACTIVE=False and hotspot traffic never reaches core/peers.
python3 - <<'PYEOF' || true
import os
bm = '/opt/freedmr/bridge_master.py'
if os.path.exists(bm):
    src = open(bm, 'r', encoding='utf-8').read()
    anchor = "            if _system == _sourcesystem:\n                    if _slot == 1:"
    if 'NINJA_PEER_ALWAYS_ACTIVE' not in src and anchor in src:
        inject = (
            "            if CONFIG['SYSTEMS'][_system]['MODE'] == 'PEER':  # NINJA_PEER_ALWAYS_ACTIVE\n"
            "                BRIDGES[_tgid_s].append({'SYSTEM': _system, 'TS': 1, 'TGID': _tgid,'ACTIVE': True,'TIMEOUT': 0,'TO_TYPE': 'NONE','OFF': [],'ON': [_tgid,],'RESET': [], 'TIMER': time()})\n"
            "                BRIDGES[_tgid_s].append({'SYSTEM': _system, 'TS': 2, 'TGID': _tgid,'ACTIVE': True,'TIMEOUT': 0,'TO_TYPE': 'NONE','OFF': [],'ON': [_tgid,],'RESET': [], 'TIMER': time()})\n"
            "                continue\n"
        )
        src = src.replace(anchor, inject + anchor, 1)
        open(bm, 'w', encoding='utf-8').write(src)
        print('✓ Patched FreeDMR: PEER systems always active in TG bridges')
    elif 'NINJA_PEER_ALWAYS_ACTIVE' in src:
        print('✓ FreeDMR PEER-always-active patch already present')
    else:
        print('⚠ FreeDMR PEER patch anchor not found!')

    # Preserve OTA Talker Alias Embedded LC (FLCO 0x04/0x05/0x06) from CORE-BRIDGE so FreeDMR does not strip it
    if 'NINJA_PRESERVE_TALKER_ALIAS' not in src:
        target_1 = "                    elif _dtype_vseq in [1,2,3,4]:\n                        dmrbits = dmrbits[0:116] + _target_status[_target['TS']]['TX_EMB_LC'][_dtype_vseq] + dmrbits[148:264]"
        replace_1 = "                    elif _dtype_vseq in [1,2,3,4]:  # NINJA_PRESERVE_TALKER_ALIAS\n                        if self._system != 'CORE-BRIDGE' and not (_target['TGID'] == _dst_id and _system['TS'] == _target['TS']):\n                            dmrbits = dmrbits[0:116] + _target_status[_target['TS']]['TX_EMB_LC'][_dtype_vseq] + dmrbits[148:264]"
        target_2 = "                        elif _dtype_vseq in [1,2,3,4]:\n                            dmrbits = dmrbits[0:116] + _target_status[_target['TS']]['TX_EMB_LC'][_dtype_vseq] + dmrbits[148:264]"
        replace_2 = "                        elif _dtype_vseq in [1,2,3,4]:  # NINJA_PRESERVE_TALKER_ALIAS\n                            if self._system != 'CORE-BRIDGE' and not (_target['TGID'] == _dst_id and _system['TS'] == _target['TS']):\n                                dmrbits = dmrbits[0:116] + _target_status[_target['TS']]['TX_EMB_LC'][_dtype_vseq] + dmrbits[148:264]"
        if target_1 in src:
            src = src.replace(target_1, replace_1)
            print('✓ Patched FreeDMR: preserved Talker Alias in bridge_master.py dmrd_received')
        if target_2 in src:
            src = src.replace(target_2, replace_2)
            print('✓ Patched FreeDMR: preserved Talker Alias in bridge_master.py sendDataToHBP')

    # Exempt CORE-BRIDGE from aggressive PacketControl drops (out-of-order & duplicate payload)
    if 'NINJA_CORE_BRIDGE_DROP_FIX' not in src:
        target_ooo = "            if _seq and self.STATUS[_slot]['lastSeq']  and (_seq != 1) and (_seq < self.STATUS[_slot]['lastSeq']):"
        replace_ooo = "            if self._system != 'CORE-BRIDGE' and _seq and self.STATUS[_slot]['lastSeq']  and (_seq != 1) and (_seq < self.STATUS[_slot]['lastSeq']):  # NINJA_CORE_BRIDGE_DROP_FIX"
        target_crc = "            if _seq > 0 and _pkt_crc in self.STATUS[_slot]['crcs']:"
        replace_crc = "            if self._system != 'CORE-BRIDGE' and _seq > 0 and _pkt_crc in self.STATUS[_slot]['crcs']:  # NINJA_CORE_BRIDGE_DROP_FIX"
        if target_ooo in src:
            src = src.replace(target_ooo, replace_ooo)
            print('✓ Patched FreeDMR: exempt CORE-BRIDGE from out-of-order drop')
        if target_crc in src:
            src = src.replace(target_crc, replace_crc)
            print('✓ Patched FreeDMR: exempt CORE-BRIDGE from duplicate payload drop')

    # Prevent KeyError: 'DEFAULT_UA_TIMER' when PEER systems (CORE-BRIDGE) dynamically activate unbridged TGs (e.g. TG 555)
    if "_options.get('DEFAULT_UA_TIMER', 60) =" in src:
        src = src.replace("_options.get('DEFAULT_UA_TIMER', 60) =", "_options['DEFAULT_UA_TIMER'] =")
    if "CONFIG['SYSTEMS'][self._system]['DEFAULT_UA_TIMER']" in src:
        src = src.replace("CONFIG['SYSTEMS'][self._system]['DEFAULT_UA_TIMER']", "CONFIG['SYSTEMS'][self._system].get('DEFAULT_UA_TIMER', 60)")
        print('✓ Patched FreeDMR: safe .get(DEFAULT_UA_TIMER, 60) lookup for PEER systems')

    # Fix FreeDMR bug in options_config(): handle single TS2_1 option (e.g. TS2_1=1025;) and fix UnboundLocalError: _sysslot on >180s stream timeout
    if "if 'TS2_2' in _options:\n                        _options['TS2_STATIC'] = _options.pop('TS2_1')" in src:
        src = src.replace(
            "if 'TS2_2' in _options:\n                        _options['TS2_STATIC'] = _options.pop('TS2_1')",
            "if 'TS2_1' in _options:\n                        _options['TS2_STATIC'] = _options.pop('TS2_1')",
        )
        print('✓ Patched FreeDMR: fixed TS2_1 single-talkgroup RPTO option parser')
    if 'int_id(_dst_id),_sysslot)' in src:
        src = src.replace(
            'logger.info("(%s) HBP * SOURCE TIMEOUT* STREAM ID: %s, TG: %s, TS: %s, IGNORE THIS SOURCE",self._system, int_id(_stream_id), int_id(_dst_id),_sysslot)',
            'logger.info("(%s) HBP * SOURCE TIMEOUT* STREAM ID: %s, TG: %s, TS: %s, IGNORE THIS SOURCE",self._system, int_id(_stream_id), int_id(_dst_id),_slot)',
        )
        print('✓ Patched FreeDMR: fixed _sysslot UnboundLocalError on >180s stream timeout')

    # Prevent CORE-BRIDGE from forwarding traffic to both TS1 and TS2 of the same target system, or being blocked by contention on an inactive slot
    if "NINJA_MULTI_PEER_CONTENTION_BYPASS" not in src:
        old_cont = "                        if ((_target['TGID'] != _target_status[_target['TS']]['RX_TGID']) and ((pkt_time - _target_status[_target['TS']]['RX_TIME']) < _target_system['GROUP_HANGTIME'])):"
        new_cont = (
            "                        if _target_system.get('MAX_PEERS', 1) > 1:  # NINJA_MULTI_PEER_CONTENTION_BYPASS\n"
            "                            pass\n"
            "                        elif ((_target['TGID'] != _target_status[_target['TS']]['RX_TGID']) and ((pkt_time - _target_status[_target['TS']]['RX_TIME']) < _target_system['GROUP_HANGTIME'])):"
        )
        if old_cont in src:
            src = src.replace(old_cont, new_cont, 1)
            src = src.replace(
                "                        if ((_target['TGID'] != _target_status[_target['TS']]['TX_TGID']) and ((pkt_time - _target_status[_target['TS']]['TX_TIME']) < _target_system['GROUP_HANGTIME'])):",
                "                        elif ((_target['TGID'] != _target_status[_target['TS']]['TX_TGID']) and ((pkt_time - _target_status[_target['TS']]['TX_TIME']) < _target_system['GROUP_HANGTIME'])):",
                1,
            )
            src = src.replace(
                "                        if (_target['TGID'] == _target_status[_target['TS']]['RX_TGID']) and ((pkt_time - _target_status[_target['TS']]['RX_TIME']) < STREAM_TO):",
                "                        elif (_target['TGID'] == _target_status[_target['TS']]['RX_TGID']) and ((pkt_time - _target_status[_target['TS']]['RX_TIME']) < STREAM_TO):",
                1,
            )
            src = src.replace(
                "                        if (_target['TGID'] == _target_status[_target['TS']]['TX_TGID']) and (_rf_src != _target_status[_target['TS']]['TX_RFS']) and ((pkt_time - _target_status[_target['TS']]['TX_TIME']) < STREAM_TO):",
                "                        elif (_target['TGID'] == _target_status[_target['TS']]['TX_TGID']) and (_rf_src != _target_status[_target['TS']]['TX_RFS']) and ((pkt_time - _target_status[_target['TS']]['TX_TIME']) < STREAM_TO):",
                1,
            )
            print('✓ Patched FreeDMR: delegated multi-peer MASTER contention to per-hotspot send_peers')
    if "NINJA_PEER_SLOT_AGNOSTIC" not in src:
        old_loop = "if _system['SYSTEM'] == self._system and _system['TGID'] == _dst_id and _system['TS'] == _slot and _system['ACTIVE'] == True:"
        new_loop = "if _system['SYSTEM'] == self._system and _system['TGID'] == _dst_id and (_system['TS'] == _slot or CONFIG['SYSTEMS'][self._system]['MODE'] == 'PEER') and _system['ACTIVE'] == True and not _bridge.startswith('#'):  # NINJA_PEER_SLOT_AGNOSTIC"
        if old_loop in src:
            src = src.replace(old_loop, new_loop)
            print('✓ Patched FreeDMR: slot-agnostic PEER ingress matching in dmrd_received')
        old_ref = (
            "                            #Send to reflector or TG too, if it exists\n"
            "                            if _bridge[0:1] == '#':\n"
            "                                _bridge = _bridge[1:]\n"
            "                            else:\n"
            "                                _bridge = '#'+_bridge\n"
            "                            if _bridge in BRIDGES:\n"
            "                                _sysIgnore = self.to_target(_peer_id, _rf_src, _dst_id, _seq, _slot, _call_type, _frame_type, _dtype_vseq, _stream_id, _data, pkt_time, dmrpkt, _bits,_bridge,_system,False,_sysIgnore,_source_server,_ber,_rssi)"
        )
        new_ref = (
            "                            #Send to reflector or TG too, if it exists\n"
            "                            _alt_bridge = _bridge[1:] if _bridge[0:1] == '#' else ('#' + _bridge)\n"
            "                            if _alt_bridge in BRIDGES:\n"
            "                                _sysIgnore = self.to_target(_peer_id, _rf_src, _dst_id, _seq, _slot, _call_type, _frame_type, _dtype_vseq, _stream_id, _data, pkt_time, dmrpkt, _bits,_alt_bridge,_system,False,_sysIgnore,_source_server,_ber,_rssi)"
        )
        if old_ref in src:
            src = src.replace(old_ref, new_ref)
            print('✓ Patched FreeDMR: preserved _bridge name across multi-slot loop')
        old_rw = "                        if _system['TS'] != _target['TS']:"
        new_rw = "                        if (self._system != 'CORE-BRIDGE' and _system['TS'] != _target['TS']) or (self._system == 'CORE-BRIDGE' and _slot != _target['TS']):"
        if old_rw in src:
            src = src.replace(old_rw, new_rw)
            print('✓ Patched FreeDMR: accurate slot bit rewrite from CORE-BRIDGE')
    old_dedup = "                    if (_target['SYSTEM'],_target['TS']) in _sysIgnore:"
    new_dedup = "                    if (_target['SYSTEM'],_target['TS']) in _sysIgnore or _target['SYSTEM'] in _sysIgnore:"
    if old_dedup in src and "or _target['SYSTEM'] in _sysIgnore:" not in src:
        src = src.replace(old_dedup, new_dedup)
        src = src.replace(
            "                    systems[_target['SYSTEM']].send_system(_tmp_data,b'',_ber,_rssi,_source_server)",
            "                    _sysIgnore.append((_target['SYSTEM'],_target['TS']))\n                    _sysIgnore.append(_target['SYSTEM'])\n                    systems[_target['SYSTEM']].send_system(_tmp_data,b'',_ber,_rssi,_source_server)",
        )
        print('✓ Patched FreeDMR: per-system target dedup in routerHBP.to_target')

    open(bm, 'w', encoding='utf-8').write(src)
PYEOF

# Determine FreeDMR master script
SCRIPT="bridge_master.py"
if [ ! -f "/opt/freedmr/$SCRIPT" ]; then
    if [ -f "/opt/freedmr/bridge.py" ]; then
        SCRIPT="bridge.py"
    elif [ -f "/opt/freedmr/FreeDMR.py" ]; then
        SCRIPT="FreeDMR.py"
    else
        SCRIPT=$(ls -1 /opt/freedmr/*master*.py /opt/freedmr/bridge*.py 2>/dev/null | head -n 1 | xargs -n 1 basename)
    fi
fi

echo "✓ FreeDMR Engine using script: $SCRIPT"
echo "✓ FreeDMR Engine ready. Starting DMR Master daemon..."

exec python3 -u "$SCRIPT" -c /opt/freedmr/freedmr.cfg -r /opt/freedmr/rules.py
