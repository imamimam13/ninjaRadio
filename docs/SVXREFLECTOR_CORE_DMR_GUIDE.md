# How NinjaRadio connects SvxReflector to the Core DMR software (voice + callsign, both directions)

This guide explains, based on this repository's implementation, how **SvxLink / SvxReflector** is bridged to the **Core DMR engine** so that **audio and station identity (callsign / DMR ID)** can move in both directions.

> Scope: NinjaRadio's built-in architecture in this workspace (`svxreflector/`, `bridge-svxlink/`, `core/`). It does **not** describe a generic third-party setup except where implied by these configs.

---

## 1) Big picture (who talks to whom)

```
SvxLink client(s) / EchoLink
        │  (SvxReflector protocol, TCP+UDP 5300, OPUS/SPEEX/GSM)
        ▼
  svxreflector  (NinjaRadio SvxReflector server, ports 5300 + 8080 status)
        ▲
        │  (one headless `svxlink` bot process per gateway/TG, auto-managed)
        │
  bridge-svxlink  (Python supervisor + headless SvxLink instances)
        │
        ├─ UDP 12348  core ──► bridge-svxlink : DMR RX PCM (8 kHz, 320-byte frames in USRP-like packets)
        │                                  routed to the matching SvxLink bot/TG
        │
        └─ UDP 32100  bridge-svxlink ──► core : "NSVX" PTT/audio packets (8 kHz PCM) + callsign/DMR ID
                                           when a remote SvxReflector/EchoLink talker speaks

  core (HomeBrew DMR Master + DirectDmrEngine/OpenDMR vocoder)
        │  DMRD frames, BPTC/LC, EMB-LC, TG/slot routing, peering, callbook
        ▼
  DMR network: local hotspots/MMDVM repeaters (62031), upstream peers, Web, Mumble, DVSwitch clients
```

Key services (see `docker-compose.yml`):
- `svxreflector`: built-in SvxReflector conference server (`5300/tcp+udp`, status `8080`).
- `svxlink-bridge`: multi-TG SvxLink/EchoLink gateway pool bridged to Core.
- `core`: DMR master + Direct DMR vocoder/engine (ingress UDP `32100`, feeds bridges incl. UDP `12348`).

---

## 2) SvxReflector side (the reflector)

**Config generation:** `svxreflector/entrypoint.sh` regenerates `/etc/svxlink/svxreflector.conf` at startup:
- Listens on `LISTEN_PORT` (default `5300`, env `SVX_PORT`) and HTTP status `SVX_HTTP_PORT` (default `8080`).
- `ACCEPT_CALLSIGN=.*`, default TG for v1 clients `510`, codecs `OPUS,SPEEX,GSM`.
- `[USERS]`: `* = NinjaReflectorNodes` and also explicitly authorizes callsigns collected from `config.json` (server/operator/peering callsigns), every callsign in `ninjaradio.db` `callbook`, plus common suffix variants (`-M,-L,-R,-P,-ND,-1..-3`).
- `[PASSWORDS]`: `NinjaReflectorNodes = "$SVX_AUTH_KEY"` (default `ninjaradio_svx`). All reflector nodes/bots share this auth key.
- `[SERVER_CERT]`: auto-detects public IP / sets `COMMON_NAME` and `SUBJECT_ALT_NAME`; resets PKI if server identity changes.

**Repo config:** `svxreflector/svxreflector.conf` shows the same shape (auth key `ninjaradio_svx`, port 5300, `* = NinjaReflectorNodes`).

Result: any SvxLink client (or the bridge's own bot nodes) that presents an accepted callsign and the correct `AUTH_KEY` can join the reflector and select/monitor talkgroups.

---

## 3) Bridge side: SvxLink ↔ Core (`bridge-svxlink/bridge_svxlink.py`)

The bridge is a **pool manager** that runs one **headless `svxlink` process per gateway bot** and shuttles audio between SvxReflector and Core.

### 3.1 Connection to the reflector (per bot)
For each bot, `_build_svxlink_environment()` writes an isolated `svxlink.conf` under `/tmp/svx_<BOT>/` with:
- `ReflectorLogic`: `HOST=$SVX_HOST`, `PORT=$SVX_PORT` (default `svxreflector:5300`), `CALLSIGN=<bot>`, `AUTH_KEY="$SVX_AUTH_KEY"`, `DEFAULT_TG`, `MONITOR_TGS` (all known TGs), `AUDIO_CODEC=OPUS`, and a `node_info.json`.
- `SimplexLogic` linked to `ReflectorLogic` (`LINKS=ReflectorLink`, `CONNECT_LOGICS=SimplexLogic:9:SVX,ReflectorLogic`), `DTMF_CTRL_PTY` so the bridge can switch TGs (DTMF `9<TG>#`).
- **Audio I/O via Local/UDP** (16 kHz, S16_LE, mono), so no soundcard is needed:
  - `Rx1`: `AUDIO_DEV=udp:127.0.0.1:<udp_rx_into_svx>`, `SQL_DET=PTY` (`sql_pty`) — Python injects DMR audio and opens/closes squelch by writing `O`/`Z` to the PTY.
  - `Tx1`: `AUDIO_DEV=udp:127.0.0.1:<udp_tx_from_svx>`, `PTT_TYPE=PTY` — SvxLink emits audio here when a remote reflector/EchoLink talker is active.
- Local Tcl overrides (`events.d/local/...`) **silence announcements/beeps** and emit parseable markers to stdout:
  - `NINJA_EVENT:TALKER_START:<tg>:<callsign>` / `TALKER_STOP...` (and EchoLink variants) for **callsign carry-over**.
- Optional `[ModuleEchoLink]` if configured per bot.

The supervisor launches `/usr/bin/svxlink --config=...` with `ASYNC_AUDIO_UDP_ZEROFILL=1`, watches stdout for connection/auth, and parses `NINJA_EVENT` plus fallback log lines (`Talker start/stop on TG #...: CALL`) to track the active remote talker and TG.

### 3.2 DMR/Core → SvxReflector (downlink audio path)
- `run_usrp_listener()` binds UDP **`SVX_BRIDGE_RX_PORT`** (default **12348**) and receives packets from Core.
- For each voice packet (with `keyup==1` and 8 kHz PCM payload at offset 32), it extracts `talkgroup`, finds/creates the matching gateway bot (`SVX-GATEWAY` or per-TG bot, dynamic `get_or_create_dynamic_bot`), and calls `bot.play_pcm_8k(pcm_8k, tg)`.
- `play_pcm_8k()`: guards against echo while SvxLink is transmitting, selects the reflector TG (`select_talkgroup` via DTMF PTY when needed), opens Rx1 squelch, **upsamples 8 kHz → 16 kHz** (linear 2x), and sends PCM to SvxLink's Rx UDP port. A watchdog closes squelch after RX stops. `keyup==0` closes squelch immediately.

Core side (`core/core_engine.py`): on decoded/ingressed traffic Core forwards 8 kHz PCM to bridges as `USRP`-shaped packets `struct.pack(">4sIIIIIII", b"USRP", seq, 0, 1, tg, src_id, slot, 0) + chunk320` to `("svxlink-bridge", 12348)` (and to mumble). It also sends TLV `SET_INFO`/metadata to DVSwitch clients; SvxLink audio path uses the PCM stream above.

### 3.3 SvxReflector/EchoLink → DMR/Core (uplink audio path) and **callsign/ID carry-over**
- `_audio_tx_from_svx_loop()` receives 16 kHz PCM from SvxLink `Tx1` UDP.
- It ignores loopback/idle, requires a real **external** active talker callsign (from reflector/EchoLink events) — not the bot itself and not `SVX*` internals.
- **Identity resolution:** `resolve_svxlink_identity(raw_talker)` strips suffixes, extracts tokens/7-digit IDs, and looks up `ninjaradio.db` `callbook` (`callsign`/`dmr_id`) to map to a numeric **DMR ID + canonical callsign** (with deterministic/virtual fallbacks if not found).
- **Permission gate:** `check_svxlink_tx_permission()` requires the callsign/ID to be in the callbook (or allowed by org/override rules) for the target TG (TG 9990 parrot allowed when verified).
- On allowed audio it **downsamples 16 kHz → 8 kHz** (2:1), chunks into 320-byte (20 ms) frames, and sends **`NSVX`** packets to Core on **UDP 32100**:
  - Header: `struct.pack(">4sIIII?16s", b"NSVX", user_dmr_id, tx_tg, tx_slot, len(chunk), True, callsign_16)` + PCM chunk
  - On release: same header with `len=0, False` to unkey.

### 3.4 Talkgroup/slot mapping
- TGs come from `config.json` (`svxlink_rooms` / `svxlink_bots`), bridge_routes, and defaults (`SVX-Nasional` 510, `SVX-Nusantara` 5109, `SVX-BRIDI` 1025, `SVX-Parrot` 9990). Slot heuristic used: TS1 for TGs {510,5108,91,1023} else TS2 (overridable per bot). Unified `SVX-GATEWAY` can act as an any-TG gateway and retune dynamically.

---

## 4) Core DMR side (`core/`)

### 4.1 Ingress from SvxLink/Mumble/Web (`run_web_ptt_ingress_loop`, UDP 32100)
`core/core_engine.py` listens on UDP **32100** (`WEB_PTT_PORT`) and accepts packets whose magic is `b"NPTT"` (Mumble/Web-style in this ingress) or `b"NSVX"` (SvxLink bridge), parsing `(dmr_id, tg, slot, pcm_len, is_tx, callsign[16]) + pcm`.
- Applies TX firewall `check_core_tx_permission` (valid registered identity; not monitor/SVX-bot placeholder).
- Marks local transmission, resolves/keeps callsign & DMR ID, emits backend events with that identity.
- On voice: forwards pristine 8 kHz PCM to DVSwitch/Mumble/SvxLink clients as appropriate and feeds **`DirectDmrEngine.push_web_ptt_pcm(callsign, dmr_id, tg, slot, pcm, is_tx)`** which vocodes PCM to DMR and paces/broadcasts DMRD (60 ms TDMA) to the DMR master/peers/routes.
- On unkey: sends unkey notifications and pushes `is_tx=False` so the engine emits proper DMR terminators.

### 4.2 How voice becomes DMR (and how identity is preserved)
`core/direct_dmr.py` (`DirectDmrEngine` + `libopendmr`/OpenDMR vocoder, BPTC/EMB utilities from `dmr_bptc`):
- Builds ETSI-compliant bursts: **Voice LC Header** (`data_type=0`, BPTC 196,96 with LC carrying FLCO, dst TG, src ID), **voice bursts** with BS sync/EMB-LC fragments (`build_dmr_emblc_fragments`), and **Voice Terminator** (`data_type=2`).
- `build_dmrd_packet()` constructs 55-byte HomeBrew **DMRD** frames: seq, src(24-bit), dst TG(24-bit), repeater ID, bits (slot/call-type/frame/dtype), stream id, 33-byte AMBE+FEC payload, BER/RSSI.
- `push_web_ptt_pcm()` buffers PCM into 60 ms bursts, encodes via `DirectOpenDmrVocoder`, enqueues paced DMRD for broadcast to local DMR master/connected hotspots and (per core routing) upstream peers and TG-matrix translations.
- **Identity on air/network:** the source **DMR ID** is embedded in DMRD and LC/EMB-LC; the **callsign string** carried in `NSVX`/ingress is kept for logging/events, callbook lookups, and UI/metadata (e.g. TLV `SET_INFO` to USRP/DVSwitch clients). The same `src_id`/callsign pairing is used when Core relays traffic out.

### 4.3 DMR/Core → SvxLink (and others) with callsign
When DMR traffic is received/decoded (`handle_dmr_master_traffic`, `handle_peer_traffic`, direct decode paths), Core:
- Resolves callsign from callbook/cache (`lookup_callbook_callsign`) for `src_id`.
- Decodes DMR bursts to 8 kHz PCM via `DirectDmrEngine.decode_dmr_burst`.
- Streams PCM to **`svxlink-bridge` on UDP 12348** (and mumble), and broadcasts TLV/USRP metadata with callsign to DVSwitch clients (`_broadcast_direct_rx_to_dvswitch_mobile`), applying TG-matrix translations and routing rules.

So the remote DMR talker's identity is preserved as **DMR ID in protocol** and **resolved/displayed callsign** across Core, Web, Mumble, DVSwitch, and the PCM feed sent toward SvxLink.

---

## 5) Bidirectional flow summary (voice + callsign)

| Direction | Voice path | Identity / callsign path |
|---|---|---|
| **DMR → SvxReflector** | DMRD/AMBE from hotspots/peers → Core decodes to 8 kHz PCM → Core sends PCM (USRP-like, TG/src in header) to bridge UDP 12348 → bridge upsamples to 16 kHz, opens SvxLink Rx squelch, injects into SvxLink → SvxLink streams to SvxReflector (and connected clients/EchoLink). | DMR `src_id`/TG come from DMRD; Core resolves callsign from callbook for events/UI/metadata and routes per TG. (Remote listener hears audio on the reflector TG; identity presentation on reflector clients follows SvxLink/reflector behavior and node metadata.) |
| **SvxReflector/EchoLink → DMR** | Remote talker audio on reflector TG → SvxLink Tx1 UDP 16 kHz → bridge downsamples to 8 kHz → sends `NSVX` PCM+PTT to Core UDP 32100 → Core ingress + `DirectDmrEngine` encodes to DMRD (Header/Voice/Terminator, EMB-LC) → broadcast to local DMR master/hotspots, peers, and translated TGs. | Bridge captures talker callsign (Tcl events/log), `resolve_svxlink_identity()` → (DMR ID, callsign) via callbook, permission-checked, placed into `NSVX` header → Core embeds **src DMR ID** in DMRD/LC/EMB and carries callsign through events/routing/callbook-aware displays (carry-over to DMR side). |

---

## 6) Ports & env knobs (from this repo)

| Component | Port/Proto | Purpose |
|---|---|---|
| `svxreflector` | 5300 TCP/UDP | SvxReflector clients/bots (`SVX_PORT`) |
| `svxreflector` | 8080 TCP | Reflector HTTP status (`SVX_HTTP_PORT`) |
| `bridge-svxlink` | 12348 UDP | Receive DMR RX PCM from Core (`SVX_BRIDGE_RX_PORT`) |
| `bridge-svxlink` | 12349 TCP | Bridge HTTP control/status (`SVX_BRIDGE_HTTP_PORT`) |
| `bridge-svxlink` | 5198-5199 UDP, 5200 TCP | Exposed in compose (SvxLink/EchoLink related) |
| `core` | 32100 UDP | Direct PTT/audio ingress `NPTT`/`NSVX` (`WEB_PTT_PORT`) |
| `core` | 62031 UDP | HomeBrew DMR Master for hotspots/repeaters |

Shared auth/config: `SVX_AUTH_KEY` (reflector password/group), `SVX_HOST/PORT/HTTP_PORT`, `DMR_USRP_HOST`, `SVX_BRIDGE_RX_PORT`, callbook DB at `/app/data/ninjaradio.db` (identity/permission resolution), `config.json` room/bot mappings.

---

## 7) Practical notes / limits
- **Registration matters for uplink:** bridge and core enforce callbook/permission checks; unknown or disallowed stations may be blocked from TX to a TG.
- **DMR identity is numeric on-air:** callsign is carried alongside as resolved metadata/string in the bridge/core pipeline and in DMRD/LC source-ID fields (DMR ID) so networks, hotspots, logs, and UIs can show/route the correct station.
- **Gateway model:** audio is transcoded/conditioned (resampling, filtering/gating in components) and vocoded by Core's Direct/OpenDMR path — not a raw RF repeater of SvxLink audio.
- **Per-peer/TG matrix routing:** Core can translate/route TGs and bind peers; bridge can auto-station dynamic TG gateways.
- **Echo/loop guards:** bridge suppresses feeding audio back while transmitting/receiving in conflicting states; core marks local transmissions to avoid loops.
- **ALSA dummy device? (Tidak wajib / not required in this design):** each bot uses `AUDIO_DEV=udp:127.0.0.1:<port>` for Rx/Tx and PTYs for squelch/PTT/DTMF, so audio and control do not pass through ALSA soundcards. The `bridge-svxlink` container does not load `snd-aloop`/`snd-dummy`; you only need a physical/virtual ALSA device if you later change to ALSA-based `AUDIO_DEV` or hardware audio.

*Evidence: `svxreflector/entrypoint.sh`, `svxreflector/svxreflector.conf`, `bridge-svxlink/bridge_svxlink.py`, `core/core_engine.py`, `core/direct_dmr.py`, `docker-compose.yml`, `README.md`.*
