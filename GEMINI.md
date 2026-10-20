# NinjaDMR / NinjaRadio Development Guidelines & Architecture

## 1. DVSwitch USRP Protocol Specifications
- **Header Length**: Exactly 32 bytes: `b"USRP" + struct.pack(">iiiiiii", seq, 0, key_flag, 0, packetType << 24, 0, 0)`
- **Packet Types**:
  - `packetType = 0`: Voice PCM (8kHz, 16-bit mono, 320 bytes payload per 20ms frame).
  - `packetType = 2`: Text / TLV metadata.
- **STRICT RULE**: Never insert DMR ID, Talkgroup, or custom data into header fields 3, 4, 5, or 6. Analog_Bridge drops frames if packetType is not 0 or 2.

## 2. Dynamic Identity Carry-Over (Zero Daemon Restarts)
- **STRICT RULE**: NEVER restart Analog_Bridge or MMDVM_Bridge during runtime or when switching operator profiles / PTT keying. Restarting terminates ongoing RX sessions with Signal 15 and damages DMR Master registration.
- To switch transmitter identity, dispatch a binary `TLV SET_INFO` frame (Tag `0x08`) to Analog_Bridge on UDP port 32000 before voice bursts:
  - Format: `[0x08, len, src(3B big), rpt(4B big), tg(3B big), slot(1B), cc(1B), callsign\0]`

## 3. Metadata Parsing for Incoming DMR RX
- Analog_Bridge dispatches RX metadata as binary TLV frames (Tag `0x08`) on UDP port 32001.
- Parse this metadata using binary unpacking (`parse_set_info_tlv`), never plain ASCII regex.
- RX metadata MUST accurately reflect the remote sender. Never substitute the operator's own DMR ID into RX traffic events, as the web frontend will drop incoming audio due to self-echo suppression.

## 4. Web Audio Playback & Browser Integration
- Register global gesture unlock listeners (`click`, `touchstart`, `keydown`) for Web AudioContext to comply with browser autoplay policies.
- Queue incoming audio chunks if the context is `suspended` and immediately play them upon `resume()`.
- Use a 150ms jitter cushion for TDMA DMR bursts (60-80ms interval) with micro cosine envelopes on chunk boundaries to prevent audio pops and stutter.

## 5. Deployment Environment
- Remote VPS: `43.173.13.117` (`/home/ubuntu/ninjaRadio`)
- Official Operator Profile: `BD62BOT` (`1022057`)
- Primary Talkgroup: TG 1025, Slot 2

## 6. Strict Regression Prevention & Codebase Stability
- **STRICT RULE - Minimal Surgical Changes**: Ketika memperbaiki bug atau laporan masalah, perbaiki HANYA baris/komponen yang bermasalah secara langsung. DILARANG merombak fungsi atau alur pipeline TX/RX yang sudah stabil sebelumnya ("jangan gali lobang tutup lobang").
- **Talkgroup Priority Invariant**: Talkgroup yang dipilih secara eksplisit oleh pengguna (baik di Web PTT `/radio` maupun DVSwitch Mobile) HARUS selalu menjadi prioritas utama. DILARANG menimpa target TG pengguna dengan TG bawaan (fallback / primary TG 1025) selama transmisi aktif.
- **Client Identity Isolation**: Klien DVSwitch Mobile eksternal (UDP 32345) dan Klien WebRTC/Web PTT adalah entitas terpisah. Pergantian profil di Web Frontend TIDAK BOLEH menimpa data pendaftaran/identitas DVSwitch Mobile (`REG:`, `INFO:`, DTMF).
- **Session Cleanup Invariant**: Flag sesi transmisi (`_tx_session_active`, carrier PTT) HARUS selalu di-reset secara bersih saat unkey agar transmisi berikutnya tidak mewarisi parameter lama.

## 7. RF DMR Cadence, Dashboard Isolation & VOX Hysteresis

### 7.1 Dual-Path Audio Architecture (Dashboard vs /radio)
- Antarmuka WebRTC (`/radio`) dan Dashboard Monitor (`/`) adalah jalur audio independen:
  - `/radio` ditenagai oleh Direct DMR Vocoder bi-directional (OpenDMR / mbelib).
  - Dashboard monitor ditenagai oleh USRP / WebSocket monitor stream.
- Perbaikan atau penyesuaian pada salah satu antarmuka TIDAK BOLEH mengorbankan atau mengubah format buffer audio antarmuka lainnya.

### 7.2 DMR RF Superframe Cadence & Timing Invariant (Anti-Cutoff HT/Z3DMR)
- Klien DMR fisik dan RF (MMDVMHost, Pi-Star, HT, Z3DMR) sangat bergantung pada ketepatan waktu TDMA:
  - Paket suara DMRD (3 AMBE frame = 60ms audio) HARUS dikirimkan dengan ritme waktu berkala (pacing ~60ms) tanpa penumpukan burst (*burst bunching*).
  - Siklus superframe 6 burst (A -> B -> C -> D -> E -> F) dengan BS Voice Sync pada burst A dan Embedded LC pada burst B-E harus dijaga tanpa ada frame yang hilang agar modem MMDVM tidak memicu timer *Loss of Sync* (cutoff 2 detik).

### 7.3 VOX Energy Detector & Hysteresis Guardrails
- Detektor VOX (baik di frontend browser maupun backend) WAJIB menerapkan:
  - **Dual-Threshold / Hysteresis (Schmitt Trigger)**: Ambang aktivasi (*attack threshold*) harus lebih tinggi daripada ambang pelepasan (*release threshold*) untuk mencegah mic berguncang di batas noise.
  - **Hangover Timer**: Ketika level audio turun di bawah release threshold, transmisi tidak langsung mati, melainkan menunggu jeda hangover (misal 400–600ms). Jika setelah jeda tersebut tetap hening, barulah kirim sinyal unkey secara bersih.
  - **Hard Time-Out Timer (TOT)**: Batas maksimal transmisi terus-menerus (misal 120-180 detik) untuk mencegah open-mic permanen jika ada noise statis tinggi.

