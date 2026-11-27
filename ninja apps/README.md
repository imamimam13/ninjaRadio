# 📱 Ninja Radio Applications — Latest Production Builds

Folder ini menyatukan berkas instalasi aplikasi Android (`.apk`) **versi terbaru** yang telah dipruning dari versi-versi lawas dan dipertahankan hanya rilis dengan fitur paling mutakhir.

Akses folder:
* [`ninja-apps/`](./) *(format standar)*
* [`ninja apps/`](../ninja%20apps/) *(Windows directory junction)*

---

## 🚀 Daftar Aplikasi & Fitur Terbaru yang Dipertahankan

### 1. [ninja-radio-full/](./ninja-radio-full) — NinjaRadio Full Client
* **Berkas**: `NinjaRadio-Full-v1.0.1.apk` (37.48 MB)
* **Status**: **Rilis Terbaru (v1.0.1)**
* **Fitur Utama**:
  - Full On-Device OpenDMR Vocoder (C++ JNI + Chaquopy Python).
  - Background DMR Audio Service (tetap menyala saat layar mati/berpindah aplikasi).
  - Integrasi tombol PTT fisik / UART PoC device.
  - Callbook DMR lokal offline dengan pencarian callsign instan.
* **Source Code**: [`app-android/`](../app-android)

---

### 2. [ninja-radio-lite/](./ninja-radio-lite) — NinjaRadio Lite Client
* **Berkas**: `NinjaRadio-Lite-v1.0.6.apk` (8.01 MB)
* **Status**: **Rilis Terbaru (v1.0.6 Fixed)**
* **Fitur Utama**:
  - Full hardware DSP vocoder MD380 (`libmd380vocoder.so` + `libmd380emu32.so` + image firmware/core).
  - **Fix Button Calibration**: Dialog kalibrasi sekarang meng-intercept seluruh `KeyEvent` dan `MotionEvent.ACTION_SCROLL` (Knob) langsung di window dialog sehingga tombol CH+, CH-, dan Knob terdeteksi 100% tanpa nyangkut di PTT.
  - **Quick Presets**: Tombol preset instan untuk Rotary Knob (166/167), Volume Up/Down (24/25), dan Page Up/Down (92/93).
  - Ukuran binary ultra-ringan (~8.0 MB), konsumsi RAM sangat minim.
  - Pemutar audio WebSocket berlatensi rendah dengan auto-reconnect.
* **Source Code**: [`app-lite/`](../app-lite)

---

### 3. [ninja-radio-md380/](./ninja-radio-md380) — NinjaRadio MD380 UI Edition
* **Berkas**: `NinjaRadio-MD380-v1.0.0.apk` (7.62 MB)
* **Status**: **Rilis Terbaru (v1.0.0)**
* **Fitur Utama**:
  - Tampilan visual klasik khas radio genggam DMR TYT MD-380 / MD-UV380.
  - S-Meter visual, indikator Talkgroup (TG), Timeslot (TS), dan sinyal RSSI.

---

### 4. [ninja-radio-pwa/](./ninja-radio-pwa) — NinjaRadio PWA Wrapper
* **Berkas**: `NinjaRadio-PWA-v1.0.apk` (2.70 MB)
* **Status**: **Rilis Terbaru (v1.0)**
* **Fitur Utama**:
  - Native Android wrapper untuk antarmuka web modern NinjaRadio.
  - Dukungan full screen immersif dan WebRTC low-latency.
* **Source Code**: [`app-pwa/`](../app-pwa)

---

### 5. [ninja-ts3/](./ninja-ts3) — Ninja TS3 PoC Client
* **Berkas**: `NINJATS3_v2.7_smeter_signed.apk` (2.22 MB) — **Fitur Terbaru v2.7 dengan S-Meter Realtime & Universal PTT**
* **Status**: **Rilis Stabil Terbaru (Universal Caraka & PoC Ready)**
* **Fitur Utama**:
  - Klien TeamSpeak 3 khusus gateway radio NinjaRadio dengan S-Meter aktif saat RX.
  - **Auto-Detect Universal PTT**: Mendukung langsung tombol PTT fisik Caraka (Side Key 1 code 290, DMR Key 2 code 291, PTT 228, Sonim 261, Kyocera 149, Samsung XCover 1011, Bluetooth PTT 130, dll.) tanpa perlu registrasi `BroadcastReceiver` yang menyebabkan force closed di Android < 13.
  - **Navigasi Rotary Knob**: Mendukung Channel UP/DOWN (166/167), Page UP/DOWN (92/93), dan DPAD (23/66) untuk perpindahan fokus channel.
  - **Mode Layar HT PoC (Orientasi Layar)**:
    - *Kenapa layar tampak menyamping jika Portrait?* Perangkat HT PoC (Caraka, Inrico T320, Boxchip) secara fisik menggunakan modul panel LCD horizontal (native 0° hardware scanlines adalah mode landscape). Jika disetel ke Portrait, OS Android memutar framebuffer 90° sehingga layar tampil menyamping di bodi HT.
    - *Solusi*: Buka menu **Settings (ikon gerigi)** -> klik **Mode layar** -> pilih **Landscape**. Layar akan langsung tegak sempurna sesuai bodi perangkat.

---

### 6. [ninja-mumble/](./ninja-mumble) — Ninja Mumble Client
* **Berkas**: `NINJA_MUMBLE_v2.1_signed.apk` (12.74 MB)
* **Status**: **Rilis Stabil Terbaru v2.1 (Fixed Layout & Universal PTT)**
* **Fitur Utama**:
  - Voice engine Opus ultra-rendah latensi (<80ms) terhubung ke server Mumble NinjaRadio.
  - **Fix Layar PTT Tidak Terpotong**: Memperbaiki `content_frame` dan padding PTT view sehingga tombol PTT layar tampil utuh 100% (tidak lagi terpotong setengah oleh toolbar).
  - **Tampilan Kotak Channel Lebih Terdefinisi**: Saluran kini berada dalam card container khusus (`channel_box_panel`) bergradasi taktis dengan border 1.5dp, dan tiap item baris saluran memiliki kartu tersendiri (`channel_item_selector`) dengan status pressed/activated yang jelas.
  - **Auto-Detect Universal PTT**: Mendukung langsung tombol PTT fisik Caraka (Side Key 1 code 290, DMR Key 2 code 291, PTT 228, Sonim 261, Kyocera 149, Samsung XCover 1011, Bluetooth PTT 130, dll.) pada `onKeyDown`/`onKeyUp` tanpa crash BroadcastReceiver.

---

### 7. [ninja-svxlink/](./ninja-svxlink) — Ninja SvxLink & Repeater Gateway
* **Berkas**:
  - `NINJA_SVX_v2.0_signed.apk` (65.54 MB) — **Klien Lengkap SvxLink v2.0 (Jetpack Compose & Material 3)**
  - `NinjaSvxLink-Gateway-v0.2.0-debug.apk` (1.10 MB) — **Modul Standalone Gateway v0.2.0**
* **Fitur Utama**:
  - Protokol SvxReflector asli (Opus 16kHz via Concentus).
  - Tampilan UI modern Material 3 dengan status repeater/reflector aktif.
* **Source Code**: [`app-svx/`](../app-svx) & [`app-svx-simple/`](../app-svx-simple)

---

### 8. [ninja-talk/](./ninja-talk) — Ninja Talk (Zello Channel Gateway)
* **Berkas**: `NINJA_TALK_v5.9.1_signed.apk` (17.44 MB)
* **Status**: **Rilis Terbaru (v5.9.1 Signed)**
* **Fitur Utama**:
  - Klien push-to-talk yang terintegrasi dengan bridge Zello / Loudtalks.

---

### 9. [ninja-bridge/](./ninja-bridge) — Ninja Bridge Companion
* **Berkas**: `NinjaBridge-v1.0.apk` (10.41 MB)
* **Status**: **Rilis Stabil (v1.0)**
* **Fitur Utama**:
  - Manajemen koneksi bridge dan routing audio multi-protokol.

---

### 10. [related-poc/](./related-poc) — Digital PoC & Network Radio
* **Berkas**:
  - `POC_INDONESIA_CARAKA_signed.apk` (5.70 MB) — Edisi terbaru PoC Indonesia
  - `BRIKER_DIGITAL_v2.0_signed.apk` (2.41 MB) — Briker Digital v2.0
  - `VOXLINK_v2.0_signed.apk` (60.58 MB) — Voxlink v2.0
