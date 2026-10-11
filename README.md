# 🥷 NinjaRadio

<div align="center">

```
  _   _ _        _       ____            _ _       
 | \ | (_)_ __  (_) __ _|  _ \ __ _   __| (_) ___  
 |  \| | | '_ \ | |/ _` | |_) / _` | / _` | |/ _ \ 
 | |\  | | | | || | (_| |  _ < (_| || (_| | | (_) |
 |_| \_|_|_| |_|/ |\__,_|_| \_\__,_(_)__,_|_|\___/ 
              |__/                                  
```

### **All-in-One Tactical Digital Voice & Analog Audio Gateway Platform**
*Bawa Seluruh Spektrum Komunikasi Radio ke Saku Anda — Mandiri, Portabel, dan Tanpa Hambatan Protokol.*

[![License: MIT](https://img.shields.io/badge/License-MIT-emerald.svg)](LICENSE)
[![Docker](https://img.shields.io/badge/Docker-Ready-cyan.svg)](docker-compose.yml)
[![Web Audio DSP](https://img.shields.io/badge/Web_Audio-300Hz--3.4kHz_DSP-amber.svg)](frontend/)
[![Architecture](https://img.shields.io/badge/Platform-VPS_%7C_Android_Non--Root-purple.svg)](deploy.sh)
[![PWA Ready](https://img.shields.io/badge/PWA-1.8%22_%7C_2.4%22_%7C_Desktop-blue.svg)](frontend/)

---

</div>

## 📖 Tentang NinjaRadio

**NinjaRadio** adalah platform server komunikasi radio modern, mandiri, dan berkinerja tinggi yang dirancang khusus untuk memadukan dunia **Radio Digital (DMR, YSF, P25, D-STAR)**, **Radio Analog (FM VHF/UHF, AllStarLink, CB 11-Meter)**, dan **Internet VoIP (Mumble/WebRTC)** ke dalam satu ekosistem terpadu.

Dirancang untuk ketahanan bencana (*disaster-proof*) dan operasi taktis lapangan, NinjaRadio dapat dioperasikan secara:
1. **Cloud VPS**: Menggunakan script otomatisasi 1-klik (*Zero-Touch Deployer*) yang langsung menerbitkan sertifikat SSL/HTTPS gratis.
2. **Perangkat Lapangan Android Standar (Non-Root)**: Mengubah smartphone biasa menjadi **Pocket Digital Voice Server** mandiri via hotspot Wi-Fi tanpa butuh koneksi internet sama sekali (*air-gapped bubble*).

---

## 🌟 Keunggulan & Fitur Utama

### 1. 📻 Multi-Protocol Cross-Network Transceiver
* **DMR (Digital Mobile Radio / ETSI TS 102 361)**: Mendukung Tier II, Timeslot 1 & 2, serta dynamic Talkgroups.
* **Yaesu System Fusion (YSF / C4FM)**: Interkoneksi ke reflector YSF.
* **D-STAR & P25**: Kompatibilitas XLX Reflector dan APCO Project 25.
* **Analog FM & AllStarLink (ASL)**: Bridge audio PCM bidirectional via DVSwitch / USRP port.
* **CB 11-Meter Band**: Ruang komunikasi khusus gateway warga dan radio komunitas.

### 2. 🔌 Web Audio DSP: Radio Cable Gateway (Analog & DMR Line-In)
Ingest modulasi audio langsung dari radio HT atau Rig mobil melalui kabel audio:
* **Kompatibilitas Kabel**: Jack 3.5mm TRRS (handsfree pinout) atau USB Soundcard OTG (CM108 / CM119).
* **Bypass AEC & AGC**: Mematikan echo cancellation ponsel agar modulasi radio masuk murni (*raw audio*).
* **Dual Biquad Voice Bandpass Filter (300 Hz – 3.4 kHz)**:
  * **Highpass 300 Hz**: Memotong sub-audible tone CTCSS/PL (67 Hz – 254.1 Hz) agar tidak mengotori suara digital.
  * **Lowpass 3.400 Hz**: Meredam suara desis squelch (*hiss noise*).
* **Software Noise Gate Squelch**: Slider ambang batas dB otomatis mendeteksi ketika radio HT menerima transmisi udara (*Carrier Detect*).
* **Generator Kalibrasi 1.000 Hz**: Uji coba gain audio dengan nada sinus 1 kHz sebelum on-air.

### 3. 🎙️ Built-in Low-Latency Mumble VoIP Server (Murmur)
* Mengintegrasikan daemon **Murmur (Port 64738)** di dalam container.
* Pengguna smartphone (Android / iOS / PC) dapat berbicara langsung dua arah dengan HT DMR menggunakan aplikasi open-source gratis seperti **Mumla**, **Plumble**, atau **Mumble Desktop**.
* Bot bridge otomatis merutekan audio VoIP ke Talkgroup DMR (default: `TG 5109 RAPI ASL`).

### 4. 📱 Dual-Mode Tactical PWA (Layar 1.8" s/d Desktop)
* **Mode Touchscreen 2.4" – 2.8"**: UI retro LCD dengan tombol PTT jempol besar, tombol prev/next channel, serta direct dialer.
* **Mode Ultra-Mini 1.8" (Khusus Radio PoC Cina)**:
  * Didesain khusus untuk radio fisik bergaya Zello (Inrico S100/T320, Boxchip, Talkpod, Anysecu).
  * Dilengkapi tombol putar rotary knob virtual & antena interaktif.
  * Dukungan **T9 Keypad Shortcuts (1–9)** untuk berpindah channel secara instan.
* **Hardware PTT Key Learning**: Mendeteksi tombol PTT fisik samping radio (`KeyCode 138/226`, `F1-F12`, Spacebar, Bluetooth PTT, dan Headset PTT).
* **VOX Hands-Free**: Transmisi otomatis aktif saat berbicara dengan hang-time timer cerdas.
* **Audio Beep Synthesizer**: Suara sintetis *Talk Grant Beep* dan *Roger Beep* berbasis Web Audio API.

### 5. 🔒 Keamanan Lapangan & Tamper Protection
* Dilengkapi sistem **Admin PIN Lock (`1234`)**.
* Tampilan publik bersifat aman (*read-only cheat-sheet*), mencegah personel lapangan salah mengubah frekuensi atau Talkgroup saat radio berada di kantong/rompi.

### 6. 📖 Unified Multi-Logbook & ADIF Export
* Pencatatan QSO lintas kategori: Radio Amatir (ORARI), RAPI (10-28), CB 11M, dan Relawan SAR.
* Ekspor 1-klik ke format standar **ADIF** (Amateur Data Interchange Format) untuk LoTW, QRZ, dan Cloudlog.

### 7. 👥 Multi-Identity Callbook Database
* Pencarian cepat DMR ID (RadioID.net), Callsign ORARI, ID RAPI 10-28, dan CB DXer lokal.

---

## 🏛️ Arsitektur Sistem

```mermaid
flowchart TD
    subgraph Klien_dan_Radio ["Perangkat Fisik & Klien"]
        HT["Radio HT / Hotspot DMR\n(Pi-Star / OpenGD77)"]
        RIG["Radio Analog HT / Rig Mobil\n(VHF/UHF/CB 11M)"]
        POC["Radio PoC Android\n(1.8\" - 2.4\" Layar & PTT Fisik)"]
        HP["Smartphone Klien\n(Aplikasi Mumla / Mumble)"]
    end

    subgraph Proxy_Layer ["Lapisan Keamanan & SSL"]
        CADDY["Caddy v2 Reverse Proxy\n(Auto-SSL Let's Encrypt / sslip.io)"]
    end

    subgraph NinjaRadio_Engine ["NinjaRadio Core Stack"]
        CORE["DV Core Engine\n(HBLink / FreeDMR / USRP)"]
        MUMBLE["Mumble Server (Murmur)\n(TCP/UDP 64738)"]
        BRIDGE["Audio Bridge Relay Bot\n(PCM 20ms Frames)"]
        BACKEND["FastAPI Backend & WebSocket\n(Last Heard Telemetry)"]
        FRONTEND["Next.js 14 Tactical PWA\n(Web Audio DSP Ingest)"]
    end

    HT <-->|UDP 62031 HomeBrew| CORE
    RIG <-->|Kabel TRRS / CM108| FRONTEND
    POC <-->|HTTPS 443 PWA| CADDY
    HP <-->|TCP/UDP 64738| MUMBLE
    CADDY <--> FRONTEND
    CADDY <--> BACKEND
    CORE <-->|USRP PCM| BRIDGE
    BRIDGE <--> MUMBLE
    BACKEND <-->|SQLite / State| CORE
```

---

## 💻 Kebutuhan Sistem (System Requirements)

### 1. Cloud VPS (Rekomendasi Skala Menengah / Besar)
* **CPU**: 1 vCPU (2 vCPU untuk >50 pengguna simultan).
* **RAM**: Minimal 1 GB (Rekomendasi 2 GB untuk build Next.js).
* **Storage**: 15 GB SSD.
* **OS**: Ubuntu 20.04 / 22.04 / 24.04 LTS atau Debian 11 / 12.

### 2. Smartphone Android (Mode Portable Edge Server Non-Root)
* **RAM**: Minimal 2 GB RAM (Rekomendasi 3–4 GB).
* **Storage**: Bebas 3 GB.
* **Android**: Android 8.0 ke atas.
* **Aplikasi Pendukung**: Termux (dari F-Droid).

---

## ⚡ Panduan Instalasi & Deployment

### METODE 1: Zero-Touch 1-Click VPS Deployer (Paling Direkomendasikan)

Script ini dirancang untuk pengguna yang ingin instalasi beres dalam 1 menit tanpa konfigurasi manual:

1. Clone repositori ke VPS:
   ```bash
   git clone https://github.com/imamimam13/ninjaRadio.git
   cd ninjaRadio
   ```

2. Berikan izin eksekusi dan jalankan deployer:
   ```bash
   chmod +x deploy.sh
   ./deploy.sh
   ```

3. **Apa yang dilakukan deployer otomatis ini?**
   * Mendeteksi IP publik VPS Anda melalui multi-resolver IP.
   * Mengonfigurasi domain SSL otomatis via `[IP_VPS].sslip.io` (misal `128.199.200.10.sslip.io`).
   * Memasang Docker & Docker Compose jika belum ada.
   * Menjalankan Caddy dengan sertifikat SSL gratis dari Let's Encrypt (membuat izin microphone browser dan instalasi PWA di smartphone langsung aktif tanpa peringatan insecure).

---

### METODE 2: Docker Compose Standar

Jika Anda ingin menjalankan secara lokal di PC atau server privat:

1. Salin konfigurasi environment:
   ```bash
   cp .env.example .env
   ```

2. Jalankan seluruh kontainer:
   ```bash
   docker compose up -d --build
   ```

3. Akses dashboard:
   * **NOC Avionics Dashboard**: `http://localhost:3000` (atau `https://localhost`)
   * **Radio Pocket PWA**: `http://localhost:3000/radio`
   * **TG Matrix Router**: `http://localhost:3000/matrix`
   * **System Config**: `http://localhost:3000/settings` (PIN: `1234`)
   * **Backend API Docs**: `http://localhost:8000/docs`

---

### METODE 3: Android Standar Non-Root (Termux)

Jadikan smartphone Android Anda sebagai server radio portable di tengah hutan / posko bencana:

1. Unduh dan buka **Termux** dari F-Droid.
2. Pasang dependensi dasar:
   ```bash
   pkg update && pkg install python git -y
   ```
3. Unduh NinjaRadio:
   ```bash
   git clone https://github.com/imamimam13/ninjaRadio.git
   cd ninjaRadio
   ```
4. Pasang library Python:
   ```bash
   pip install -r core/requirements.txt
   ```
5. Jalankan server:
   ```bash
   python core/core_engine.py
   ```
6. Aktifkan **Hotspot Portabel Wi-Fi** di HP Anda. Hubungkan radio hotspot Pi-Star atau HP rekanan ke IP gateway HP (`192.168.43.1`).

---

## 📱 Panduan Koneksi Klien

### 1. Aplikasi HP Android / iOS via Mumble (Mumla / Plumble)
1. Pasang aplikasi **Mumla** (Play Store / F-Droid) atau **Mumble** (iOS).
2. Tambahkan server baru:
   * **Label**: NinjaRadio POSKO
   * **Address**: `[DOMAIN_ATAU_IP_VPS]`
   * **Port**: `64738`
   * **Username**: Callsign atau Nama Anda (contoh: `YD0XYZ-Budi`)
3. Masuk ke channel **`NinjaRadio-Room`**. Tekan PTT di layar HP untuk langsung mengudara ke HT DMR!

### 2. Radio Hotspot (Pi-Star / OpenGD77 / WPSD)
1. Buka dashboard Pi-Star $\rightarrow$ **Configuration** $\rightarrow$ **DMR Configuration**.
2. Pilih DMR Master: **DMRGateway** atau Custom Master.
3. Masukkan parameter:
   * **Address**: `[DOMAIN_ATAU_IP_VPS]`
   * **Port**: `62031`
   * **Password**: `ninjaradio_pass`
4. Simpan konfigurasi. Hotspot Anda kini terhubung ke NinjaRadio.

### 3. Radio PoC Android Fisik (Layar 1.8" / 2.4")
1. Buka browser di radio PoC Anda dan kunjungi `https://[DOMAIN]/radio`.
2. Klik menu browser $\rightarrow$ **"Add to Home Screen" / "Install App"**.
3. Buka menu **Config** (`/settings`), masukkan PIN **`1234`**.
4. Klik **"Rekam Tombol PTT"**, lalu tekan tombol fisik PTT di samping bodi radio Anda.
5. Tombol samping radio sekarang resmi mengontrol transmisi on-air!

### 4. Kabel Audio Ingest (HT / Rig Analog ke PWA)
1. Sambungkan kabel jack 3.5mm TRRS atau USB OTG Soundcard CM108 dari speaker HT ke smartphone/PC.
2. Di halaman `/radio`, klik tombol **`[🔌 Setup Kabel Audio Radio]`**.
3. Pilih port input: *Headset TRRS* atau *USB Audio Device*.
4. Geser ambang batas squelch (misal: `-32 dB`) dan nyalakan tombol **ON**.
5. Radio HT Anda kini otomatis ter-bridge ke seluruh jaringan digital!

---

## 📋 Cheat-Sheet Port Layanan

| Port | Protokol | Layanan | Keterangan |
| :--- | :--- | :--- | :--- |
| **80** | TCP | HTTP / Caddy | Redirect otomatis ke HTTPS |
| **443** | TCP/UDP | HTTPS / HTTP3 | Dashboard Web, PWA, dan WebSocket Telemetry |
| **62031** | UDP | DMR Master | Koneksi Hotspot MMDVM / Pi-Star (Pass: `ninjaradio_pass`) |
| **62030** | UDP | OpenBridge | Peering antar-server BrandMeister & FreeDMR |
| **12345** | UDP | DVSwitch USRP | Port Audio PCM untuk DVSwitch Mobile Android |
| **64738** | TCP & UDP | Mumble VoIP | Server suara untuk aplikasi Mumla/Mumble di smartphone |
| **3000** | TCP | Next.js Frontend | Port internal web container |
| **8000** | TCP | FastAPI Backend | REST API & WebSocket internal |

---

## 🔒 Informasi Kredensial Default

* **Default Admin PIN**: `1234` (atau `admin`)
* **Default Hotspot Password**: `ninjaradio_pass`
* **Default Mumble SuperUser**: `SuperUser` / `ninjaradio_admin`
* **Default Server Callsign**: `N1NJA` (DMR ID: `5109999`)

---

## 🤝 Kontribusi & Lisensi

Proyek ini bersifat open-source dan didedikasikan untuk:
* **ORARI (Organisasi Amatir Radio Indonesia / IARU Region 3)**
* **RAPI (Radio Antar Penduduk Indonesia)**
* **Komunitas CB 11-Meter Indonesia**
* **Badan Penanggulangan Bencana & Tim Relawan Komunikasi Taktis**

Dilisensikan di bawah [MIT License](LICENSE). Silakan gunakan, modifikasi, dan bagikan untuk kepentingan kemanusiaan dan hobi radio komunikasi.

---

<div align="center">

**🥷 73 de NinjaRadio Team — Salam Damai di Udara!**

</div>
