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

### **All-in-One Tactical Digital Voice, Analog Reflector & VoIP Gateway Platform**
*Bawa Seluruh Spektrum Komunikasi Radio ke Saku Anda — Mandiri, Portabel, dan Tanpa Hambatan Protokol.*

[![License: MIT](https://img.shields.io/badge/License-MIT-emerald.svg)](LICENSE)
[![Docker](https://img.shields.io/badge/Docker-9_Services_Ready-cyan.svg)](docker-compose.yml)
[![Web Audio DSP](https://img.shields.io/badge/Web_Audio-300Hz--3.4kHz_DSP-amber.svg)](frontend/)
[![Architecture](https://img.shields.io/badge/Platform-VPS_%7C_Android_Non--Root-purple.svg)](deploy.sh)
[![PWA Ready](https://img.shields.io/badge/PWA-1.8%22_%7C_2.4%22_%7C_Desktop-blue.svg)](frontend/)
[![Android APK](https://img.shields.io/badge/Android_App-Download_APK-brightgreen.svg?logo=android)](https://github.com/imamimam13/ninjaRadio/releases)

---

</div>

## 📑 Daftar Isi
1. [Tentang NinjaRadio](#-tentang-ninjaradio)
2. [Isi Server & Arsitektur Layanan](#-isi-server--arsitektur-layanan)
3. [Cheat-Sheet Port, Firewall & Kredensial Default](#-cheat-sheet-port-firewall--kredensial-default)
4. [Kebutuhan Sistem](#-kebutuhan-sistem-system-requirements)
5. [Panduan Instalasi & Deployment](#-panduan-instalasi--deployment)
6. [Panduan Menggunakan Semua Fitur Web](#-panduan-menggunakan-semua-fitur-web)
7. [Panduan Lengkap Setup Callbook & TX Firewall](#-panduan-lengkap-setup-callbook--tx-firewall)
8. [Panduan Koneksi Perangkat & Klien](#-panduan-koneksi-perangkat--klien)
9. [Komponen & Library Open-Source](#-komponen--library-yang-diintegrasikan-upstream-attribution--modifications)
10. [Kontribusi & Lisensi](#-kontribusi--lisensi)

---

## 📖 Tentang NinjaRadio

**NinjaRadio** adalah platform server komunikasi radio terpadu (*all-in-one*) yang menghubungkan **Radio Digital (DMR Tier II, YSF, D-STAR, P25)**, **Radio Analog & Conference (FM VHF/UHF, SvxLink, SvxReflector, EchoLink, AllStarLink, CB 11-Meter)**, serta **Internet VoIP & PoC (Mumble/Mumla, Zello Channels API, DVSwitch Mobile, WebRTC PWA)** ke dalam satu ekosistem.

Dirancang untuk ketahanan bencana (*disaster-proof*) maupun operasi klub/komunitas, NinjaRadio dapat dijalankan di:
1. **Cloud VPS**: Menggunakan script otomatisasi 1-klik (`deploy.sh`) yang langsung mendeteksi IP publik dan menerbitkan sertifikat SSL/HTTPS otomatis via Caddy.
2. **Perangkat Android Standar (Non-Root via Termux)**: Mengubah smartphone menjadi **Pocket Digital Voice Server** mandiri via hotspot Wi-Fi tanpa internet (*air-gapped*).

---

## 🏗️ Isi Server & Arsitektur Layanan

Saat Anda menjalankan NinjaRadio menggunakan Docker Compose (`docker-compose.yml`), server akan menjalankan **9 container layanan terintegrasi** di dalam jaringan internal `ninjaradio_net`:

| No | Service / Container | Teknologi | Fungsi Utama di Server |
| :--- | :--- | :--- | :--- |
| 1 | `proxy` (`ninjaradio_proxy`) | **Caddy v2** | Reverse proxy + Auto-SSL (Let's Encrypt / `sslip.io`), HTTP/3 QUIC, kompresi `gzip`/`zstd`. Merutekan `/api/*`, `/ws/*`, `/docs*` ke `backend` dan trafik web ke `frontend`. |
| 2 | `core` (`ninjaradio_core`) | **Python + OpenDMR C++** | Mesin utama radio: HomeBrew DMR Master (`62031/UDP`), OpenBridge Peering (`62030/UDP`), DirectDmrEngine & OpenDMR Vocoder (AMBE+2 <-> PCM), DVSwitch/USRP (`32345/UDP`), serta ingress NPTT/NSVX (`32100/UDP`). |
| 3 | `mumble-server` (`ninjaradio_mumble`) | **Murmur** | Server VoIP Mumble bawaan (`64738/TCP+UDP`) untuk klien **Mumla / Plumble / Mumble Desktop**. |
| 4 | `mumble-bridge` (`ninjaradio_mumble_bridge`) | **Python Bot Pool** | Menjembatani audio dua arah antara room Mumble dan Talkgroup DMR via protokol Direct NPTT. Default room map: `NinjaRadio-Nasional:510`, `NinjaRadio-Nusantara:5109`, `NinjaRadio-Darurat:5108`, `NinjaRadio-Worldwide:91`. |
| 5 | `svxreflector` (`ninjaradio_svxreflector`) | **SvxReflector** | Server konferensi analog Multi-TG (`5300/TCP+UDP`, status HTTP `8080`) dengan auto-otorisasi callsign dari `ninjaradio.db` Callbook dan sertifikat PKI otomatis. |
| 6 | `svxlink-bridge` (`ninjaradio_svxlink_bridge`) | **Python + Headless SvxLink** | Pool gateway SvxLink & **EchoLink** (`5198-5199/UDP`, `5200/TCP`) yang meneruskan audio dan identitas callsign/DMR ID dua arah antara SvxReflector/EchoLink dan Core DMR. |
| 7 | `zello-bridge` (`ninjaradio_zello_bridge`) | **Python + Zello Channels API** | Jembatan audio dua arah antara channel **Zello** (menggunakan JWT & `zello_private.key`) dengan jaringan DMR / SvxLink. |
| 8 | `backend` (`ninjaradio_backend`) | **FastAPI + SQLite** | REST API (`/api/*`), Live WebSocket Hub (`/ws/*`) untuk telemetry Last Heard & audio monitor, manajemen Callbook, Logbook, TX Firewall, dan Supervisor daemon. |
| 9 | `frontend` (`ninjaradio_frontend`) | **Next.js 14 PWA** | Antarmuka web responsif: NOC Dashboard (`/`), Radio Pocket PWA (`/radio`), TG Matrix (`/matrix`), Callbook (`/callbook`), Logbook (`/logbook`), serta panel Admin. |

### Diagram Arsitektur Sistem

```mermaid
flowchart TD
    subgraph Klien_dan_Radio ["Perangkat Fisik & Klien Eksternal"]
        HT["Radio HT / Hotspot DMR\n(Pi-Star / OpenGD77 / WPSD)"]
        RIG["Radio Analog HT / Rig\n(Kabel TRRS / USB CM108)"]
        POC["Web Browser / PoC Android\n(PWA 1.8\" - Desktop & APK)"]
        HP["Mumble Client\n(Mumla / Plumble / Desktop)"]
        SVX["SvxLink Node / EchoLink"]
        DVS["DVSwitch Mobile Android"]
        ZELLO["Zello Channel Users"]
    end

    subgraph Proxy_Layer ["Lapisan Keamanan & SSL"]
        CADDY["Caddy v2 Reverse Proxy\n(80/443 Auto-SSL)"]
    end

    subgraph NinjaRadio_Stack ["NinjaRadio 9-Container Stack"]
        FRONTEND["Next.js 14 PWA Frontend\n(:3000 + Web Audio DSP)"]
        BACKEND["FastAPI Backend & WS\n(:8000 + SQLite ninjaradio.db)"]
        CORE["Core DV Engine & OpenDMR\n(DMR 62031 | OB 62030 | USRP 32345)"]
        MUMBLE["Mumble Server\n(64738 TCP/UDP)"]
        MBRIDGE["Mumble Bridge Bot"]
        SVXREF["SvxReflector Server\n(5300 TCP/UDP)"]
        SBRIDGE["SvxLink & EchoLink Bridge\n(5198-5200)"]
        ZBRIDGE["Zello Bridge"]
    end

    HT <-->|UDP 62031 HomeBrew| CORE
    DVS <-->|UDP 32345 USRP| CORE
    RIG <-->|Audio Line-In| FRONTEND
    POC <-->|HTTPS / WSS 443| CADDY
    CADDY <--> FRONTEND
    CADDY <--> BACKEND
    HP <-->|TCP/UDP 64738| MUMBLE
    MUMBLE <--> MBRIDGE
    MBRIDGE <-->|NPTT 32100 / PCM| CORE
    SVX <-->|TCP/UDP 5300 / 5198-5200| SVXREF
    SVXREF <--> SBRIDGE
    SBRIDGE <-->|NSVX 32100 / PCM 12348| CORE
    ZELLO <-->|Zello WSS| ZBRIDGE
    ZBRIDGE <-->|PCM / USRP| CORE
    BACKEND <-->|State / Events / Callbook| CORE
```

---

## 📋 Cheat-Sheet Port, Firewall & Kredensial Default

Pastikan port berikut dibuka di Firewall VPS / Cloud Security Group Anda (UFW / AWS / Alibaba / DigitalOcean / dll):

| Port | Protokol | Layanan | Keterangan |
| :--- | :--- | :--- | :--- |
| **80** | TCP | HTTP (Caddy) | Redirect otomatis ke HTTPS & verifikasi ACME Let's Encrypt |
| **443** | TCP & UDP | HTTPS / HTTP3 (Caddy) | Dashboard Web, PWA `/radio`, WebSocket, dan REST API |
| **62031** | UDP | DMR Master | Koneksi Hotspot MMDVM / Pi-Star / OpenGD77 / WPSD |
| **62030** | UDP | OpenBridge | Peering antar-server DMR (BrandMeister / FreeDMR / HBlink) |
| **32345** | UDP | DVSwitch USRP | Port default `USRP_AUDIO_PORT` untuk aplikasi **DVSwitch Mobile** *(dapat diubah ke `12345` di `.env`)* |
| **64738** | TCP & UDP | Mumble (Murmur) | Server VoIP untuk aplikasi **Mumla / Plumble / Mumble** |
| **5300** | TCP & UDP | SvxReflector | Koneksi node **SvxLink** eksternal ke reflector bawaan |
| **5198–5199** | UDP | EchoLink Audio/Control | Trafik suara & kontrol modul EchoLink pada `svxlink-bridge` |
| **5200** | TCP | EchoLink Directory | Koneksi server direktori EchoLink |
| **3000** | TCP | Next.js Frontend | Port web internal (juga diekspos untuk akses lokal tanpa proxy) |
| **8000** | TCP | FastAPI Backend | Port API & Swagger Docs (`/docs`) |

### 🔒 Kredensial & Identitas Default Server
* **Default Admin PIN**: `1234` *(segera ubah melalui menu `/settings` setelah deploy)*
* **Default Hotspot Password (Port 62031)**: `ninjaradio_pass`
* **Default SvxReflector Auth Key (Port 5300)**: `ninjaradio_svx`
* **Default Mumble SuperUser**: Username `SuperUser` / Password `ninjaradio_admin`
* **Default Server Identity**: Callsign `N1NJA` | DMR ID `5109999` | Default Talkgroup `TG 510`

---

## 💻 Kebutuhan Sistem (System Requirements)

### 1. Cloud VPS (Direkomendasikan untuk Operasional 24/7)
* **CPU**: 1 vCPU (2 vCPU disarankan jika menggunakan >50 klien simultan atau banyak bot SvxLink).
* **RAM**: Minimal 2 GB (disarankan saat proses build Next.js & kompilasi awal; atau tambah swap 2 GB jika VPS 1 GB).
* **Storage**: Minimal 15 GB SSD.
* **OS**: Ubuntu 20.04 / 22.04 / 24.04 LTS atau Debian 11 / 12.

### 2. Smartphone Android (Mode Portable Edge Server Non-Root)
* **RAM**: Minimal 2 GB (Rekomendasi 3–4 GB).
* **Storage**: Bebas 3 GB.
* **OS**: Android 8.0 ke atas + aplikasi **Termux** (unduh dari F-Droid).

---

## ⚡ Panduan Instalasi & Deployment

### METODE 1: Zero-Touch 1-Click VPS Deployer (Paling Direkomendasikan)

Cocok untuk VPS Linux baru. Script `deploy.sh` akan mendeteksi IP publik VPS, mengaktifkan domain SSL gratis via `sslip.io` (atau domain kustom Anda), memasang Docker jika belum ada, lalu menjalankan seluruh 9 container:

1. Login ke VPS via SSH, lalu clone repositori:
   ```bash
   git clone https://github.com/imamimam13/ninjaRadio.git
   cd ninjaRadio
   ```
2. Berikan izin eksekusi dan jalankan deployer:
   ```bash
   chmod +x deploy.sh
   # Opsi A: Otomatis pakai IP_VPS.sslip.io + SSL gratis
   ./deploy.sh

   # Opsi B: Menggunakan domain pribadi Anda (pastikan DNS A Record sudah mengarah ke IP VPS)
   ./deploy.sh radio.komunitasanda.org
   ```
3. Setelah selesai, buka URL yang ditampilkan di terminal (contoh: `https://43.173.13.117.sslip.io`).

---

### METODE 2: Docker Compose Manual (VPS / Server Lokal)

Jika Anda ingin mengatur port dan variabel environment sendiri sebelum menjalankan container:

1. Salin template konfigurasi `.env.example` menjadi `.env`:
   ```bash
   cp .env.example .env
   nano .env
   ```
   Sesuaikan variabel penting di `.env`:
   ```env
   DOMAIN=localhost                  # Ganti dengan domain Anda untuk Auto-SSL
   SERVER_CALLSIGN=N1NJA             # Callsign identitas server
   SERVER_DMR_ID=5109999             # DMR ID server
   DEFAULT_TALKGROUP=510             # Talkgroup bawaan
   ADMIN_PIN=1234                    # PIN Admin web
   MUMBLE_SUPERUSER_PASSWORD=ninjaradio_admin
   SVX_AUTH_KEY=ninjaradio_svx
   USRP_AUDIO_PORT=32345             # Port DVSwitch Mobile (default docker-compose: 32345)
   ```
2. Bangun dan jalankan seluruh stack:
   ```bash
   docker compose up -d --build
   ```
3. Cek status seluruh container:
   ```bash
   docker compose ps
   ```
4. Akses antarmuka web:
   * **NOC Dashboard**: `https://[DOMAIN]` (atau `http://localhost:3000`)
   * **Radio Pocket PWA**: `https://[DOMAIN]/radio`
   * **API Docs (Swagger)**: `https://[DOMAIN]/docs` (atau `http://localhost:8000/docs`)

---

### METODE 3: Android Non-Root Portable Server (Termux)

Untuk menjalankan core engine secara mandiri di lapangan tanpa VPS dan tanpa internet:

1. Pasang **Termux** dari F-Droid di HP Android Anda.
2. Instal Python dan Git, lalu clone repositori:
   ```bash
   pkg update && pkg install python git -y
   git clone https://github.com/imamimam13/ninjaRadio.git
   cd ninjaRadio
   pip install -r core/requirements.txt
   ```
3. Jalankan core engine:
   ```bash
   python core/core_engine.py
   ```
4. Nyalakan **Hotspot Portabel Wi-Fi** di HP Anda. Hotspot Pi-Star atau klien lain di jaringan hotspot tersebut dapat langsung terhubung ke IP gateway HP (umumnya `192.168.43.1`).

---

## 📱 Panduan Menggunakan Semua Fitur Web

### Halaman Publik (Tanpa Login Admin)

1. **`/` — NOC Avionics Dashboard**
   * Menampilkan **Live Last Heard** (riwayat transmisi masuk/keluar beserta Callsign, DMR ID, Talkgroup, Sumber Protokol, dan Durasi).
   * Daftar **Klien & Hotspot Aktif** yang sedang terhubung ke server.
   * **Audio Monitor Stream**: Dengarkan trafik suara langsung dari browser (klik area halaman sekali untuk mengaktifkan Web AudioContext sesuai kebijakan autoplay browser).

2. **`/radio` — Radio Pocket PWA (Transceiver Web)**
   * **Multi-Layout**: Mendukung tampilan **Mini 1.8"** (untuk radio PoC Android seperti Inrico/Boxchip/Talkpod dengan tombol putar virtual & shortcut T9 `1–9`), **Touchscreen 2.4"–2.8"**, hingga **Desktop**.
   * **Web PTT & Direct OpenDMR**: Tekan dan tahan tombol PTT untuk berbicara langsung ke jaringan DMR menggunakan vocoder internal tanpa perlu aplikasi tambahan.
   * **Hardware PTT Key Learning**: Rekam tombol PTT fisik samping radio PoC (`KeyCode 138/226`), headset kabel, tombol Bluetooth PTT, atau `Spacebar` / `F1-F12`.
   * **VOX Hands-Free**: Deteksi suara otomatis dengan *Schmitt Trigger* (ambang attack & release terpisah), *hangover timer*, dan *Time-Out Timer (TOT)* agar tidak terjadi *open-mic* permanen.
   * **Setup Kabel Audio Radio (DSP Line-In)**: Hubungkan speaker/mic HT analog atau rig ke HP/PC via kabel TRRS 3.5mm atau USB Soundcard CM108/CM119. Aktifkan filter **Bandpass 300 Hz – 3.4 kHz** (membuang tone CTCSS & desis squelch), **Software Noise Gate Squelch**, serta **Generator Nada Kalibrasi 1.000 Hz**.
   * **Audio Beep Synthesizer**: Umpan balik suara *Talk Grant Beep* dan *Roger Beep* otomatis.

3. **`/matrix` — TG Matrix Router**
   * Mengatur perutean (*bridging*) dan translasi antar-Talkgroup secara dinamis (misalnya menghubungkan trafik TG tertentu ke TG lain atau antar-jaringan) dengan toggle aktif/nonaktif instan.

4. **`/logbook` — Unified QSO Logbook & Ekspor ADIF**
   * Mencatat log QSO lintas kategori (**ORARI / Amatir**, **RAPI 10-28**, **CB 11-Meter**, dan **SAR / Relawan**).
   * Ekspor 1-klik ke file **`.adi` (ADIF)** standar internasional untuk diunggah ke **LoTW**, **QRZ.com**, atau **Cloudlog**.

5. **`/callbook` — Multi-Identity Callbook & TX Firewall**
   * Pencarian cepat database operator berdasarkan Callsign, DMR ID, Nama, Kota, atau Organisasi.
   * Manajemen data pengguna, import/export massal, dan pengaturan aturan izin transmit (*lihat panduan lengkap di bagian bawah*).

---

### Halaman Admin (Klik Ikon Gembok di Navbar & Masukkan PIN `1234`)

6. **`/serverdmr` — DMR Master & Peering Control**
   * Memantau koneksi hotspot MMDVM (`62031/UDP`), status OpenBridge / HBlink peering (`62030/UDP`), serta mengelola repeater/peer yang terhubung.
   * Mendukung **Built-in Voice Parrot (`TG 9990`)** untuk uji modulasi audio Anda sendiri.

7. **`/dvswitch` — DVSwitch Mobile Monitor**
   * Menampilkan telemetry lalu lintas USRP (`32345/UDP` atau sesuai `USRP_AUDIO_PORT`) dan panduan cepat menyambungkan aplikasi **DVSwitch Mobile** Android.

8. **`/mumla` — Mumble / Mumla VoIP Gateway Pool**
   * Kelola channel pada server Mumble (`64738`), tambah/hapus bot bridge, pindahkan bot antar-room ke Talkgroup DMR yang berbeda, dan pantau log bridge secara real-time.

9. **`/svxlink` — SvxReflector & EchoLink Bridge**
   * Kelola channel SvxReflector (`5300`), tambah/hapus bot gateway SvxLink & modul **EchoLink** (`5198-5199/UDP`, `5200/TCP`), serta pantau log percakapan dan status node yang terhubung.

10. **`/settings` — System Config, Workers & Zello Bridge**
    * Ubah identitas server (`SERVER_CALLSIGN`, `SERVER_DMR_ID`, `DEFAULT_TALKGROUP`), frekuensi tampilan, dan ganti **Admin PIN**.
    * Kelola **Dynamic Multi-Worker** (menambah worker TX/RX sekunder tanpa me-restart daemon utama).
    * **Konfigurasi Zello Channels API Bridge**: Atur kredensial Zello (`username`, `password`, channel target, dan kunci privat `zello_private.key`), pantau status koneksi WebSocket Zello, dan lihat log `zello-bridge`.

---

## 📖 Panduan Lengkap Setup Callbook & TX Firewall

Callbook di NinjaRadio bukan sekadar buku telepon — ia berfungsi sebagai **pusat resolusi identitas** (DMR <-> SvxReflector <-> Mumble <-> Web) sekaligus **TX Firewall** untuk mencegah akun tamu/SWL atau identitas palsu memancar sembarangan.

### 1. Cara Mengisi & Mengimpor Data Callbook

Buka menu **`/callbook`** di browser:

* **A. Tambah Manual Satu per Satu**:
  1. Buka kunci Admin (PIN default: `1234`).
  2. Klik tombol **Tambah Stasiun**, lalu isi **DMR ID** (contoh: `5104001`), **Callsign** (contoh: `YD0XYZ`), **Nama**, **Kota**, **Organisasi** (`ORARI`, `BRIDI`, `RAPI`, `CB-11M`, `KOMUNITAS`), **Default TG**, dan **Allowed TGs Override** *(opsional)*.
* **B. Import Massal dari File Lokal (CSV / JSON)**:
  1. Klik tombol **Import**, pilih tab **File Lokal**.
  2. Unggah file `.csv` atau `.json` (mendukung format standar **RadioID.net** `users.json` / `user.csv`).
  3. Pilih target Organisasi (`AUTO` untuk deteksi otomatis berdasarkan prefix callsign, atau pilih organisasi tertentu) dan **Default TG**, lalu klik **Import**.
* **C. Import / Sinkronisasi dari URL Eksternal**:
  1. Klik tombol **Import**, pilih tab **Dari URL**.
  2. Masukkan link langsung ke file JSON/CSV Callbook eksternal (misal link `user.json` dari RadioID.net atau server komunitas Anda).
  3. Sistem akan mengunduh, mem-parsing, dan menyinkronkannya ke `ninjaradio.db` secara otomatis.
* **D. Batch Update (Pembaruan Massal)**:
  * Gunakan fitur **Batch Update** untuk memindahkan organisasi atau mengubah **Default TG** banyak stasiun sekaligus berdasarkan filter organisasi atau **Prefix Callsign** (misal semua prefix `BD` -> organisasi `BRIDI`, TG `510`).

### 2. Cara Setup Organisasi & TX Firewall

Secara bawaan, NinjaRadio sudah menyediakan 5 organisasi:
* **`ORARI`** (Prefix: `YB,YC,YD,YE,7A..8I`)
* **`BRIDI`** (Prefix: `BD,BD62`)
* **`RAPI`** (Prefix: `JZ`)
* **`CB-11M`** (Prefix: `11M,CB`)
* **`KOMUNITAS`** (Prefix: `*` / Relawan & SAR)

Anda dapat mengatur aturan izin transmit di menu **TX Firewall Rules** pada halaman `/callbook`:
1. **Aturan per Organisasi**:
   * **`allowed_tgs`**: Isi `*` untuk mengizinkan semua Talkgroup, atau daftar TG spesifik dipisah koma (misal: `510,5109,9990`).
   * **`callsign_prefixes`**: Daftar prefix callsign yang termasuk organisasi tersebut.
   * **`allow_tx`**: Aktifkan (`true`) atau blokir (`false`) izin transmit untuk seluruh anggota organisasi tersebut.
   * Anda juga dapat menambahkan **Organisasi Baru** sesuai kebutuhan klub/instansi Anda.
2. **Override per Pengguna (`allowed_tgs_override`)**:
   * Jika satu pengguna tertentu perlu dibatasi atau diberi akses khusus berbeda dari organisasinya, edit entri pengguna tersebut dan isi kolom **Allowed TGs Override** (misal `510,9990` atau `*`). Aturan per-user memiliki prioritas tertinggi.
3. **Proteksi Identitas & Mode Monitor (SWL)**:
   * Profil dengan nama `SWL-MONITOR`, `MONITOR`, `LISTENER`, `GUEST`, `TAMU`, atau `dmr_id = 0` otomatis diblokir dari TX (hanya bisa mendengarkan/RX).
   * Jika stasiun terdaftar di Callbook, sistem memastikan **Callsign dan DMR ID harus cocok** agar tidak terjadi pemalsuan identitas (*spoofing*).
   * **TG `9990` (Echo Test / Parrot)** tetap dapat diakses untuk pengujian audio.

### 3. Export Callbook untuk Hotspot & Radio Fisik (`user.json` / `CSV` / `DAT`)

Server NinjaRadio menyediakan endpoint publik (mendukung CORS) agar **Pi-Star, WPSD, OpenGD77, AnyTone, atau MD380** dapat langsung mengunduh daftar Callbook Anda:

* **Semua Pengguna**:
  * JSON: `https://[DOMAIN]/api/callbook/user.json`
  * CSV: `https://[DOMAIN]/api/callbook/user.csv`
  * DAT (Tab-separated): `https://[DOMAIN]/api/callbook/user.dat`
* **Filter Per Organisasi** (ganti `[org]` dengan `orari`, `rapi`, `bridi`, `cb`, `komunitas`):
  * `https://[DOMAIN]/api/callbook/[org].user.json`
  * `https://[DOMAIN]/api/callbook/[org].user.csv`

---

## 🔌 Panduan Koneksi Perangkat & Klien

### 1. Radio Hotspot DMR (Pi-Star / WPSD / OpenGD77 / Z3DMR)
1. Buka dashboard Pi-Star/WPSD $\rightarrow$ **Configuration** $\rightarrow$ **DMR Configuration**.
2. Pilih **DMR Master**: `Custom` / `DMRGateway`.
3. Isi parameter server:
   * **Address**: `[IP_ATAU_DOMAIN_VPS]`
   * **Port**: `62031`
   * **Password**: `ninjaradio_pass`
4. Simpan konfigurasi. Hotspot Anda langsung aktif dan mendukung Timeslot 1 & 2 serta Echo Test `TG 9990`.

### 2. Aplikasi Mumble / Mumla (Android / iOS / PC)
1. Instal **Mumla** (Android: Play Store / F-Droid) atau **Mumble** (iOS / PC).
2. Tambah server baru:
   * **Address**: `[IP_ATAU_DOMAIN_VPS]`
   * **Port**: `64738`
   * **Username**: Callsign Anda (contoh: `YD0XYZ`)
3. Masuk ke salah satu room yang dijembatani (default: `NinjaRadio-Nasional` untuk TG 510, `NinjaRadio-Nusantara` untuk TG 5109, `NinjaRadio-Darurat` untuk TG 5108, `NinjaRadio-Worldwide` untuk TG 91).

### 3. Aplikasi DVSwitch Mobile (Android)
1. Buka aplikasi **DVSwitch Mobile** $\rightarrow$ tab **Accounts**.
2. Isi konfigurasi USRP:
   * **Protocol**: `USRP`
   * **Hostname / IP**: `[IP_ATAU_DOMAIN_VPS]`
   * **Port**: `32345` *(sesuai `USRP_AUDIO_PORT` di `docker-compose.yml`)*
   * **Callsign & DMR ID**: Masukkan callsign dan DMR ID Anda yang valid.

### 4. Node SvxLink / SvxReflector Eksternal
1. Pada konfigurasi `ReflectorLogic` di node SvxLink Anda:
   * **HOST**: `[IP_ATAU_DOMAIN_VPS]`
   * **PORT**: `5300`
   * **CALLSIGN**: Callsign node Anda (pastikan terdaftar di Callbook server jika ingin masuk jaringan DMR dengan identitas valid)
   * **AUTH_KEY**: `ninjaradio_svx` *(atau sesuai `SVX_AUTH_KEY` di `.env`)*
   * **AUDIO_CODEC**: `OPUS` *(atau `SPEEX` / `GSM`)*

### 5. Radio PoC Android & Kabel Audio HT Analog
* **Browser PWA**: Buka `https://[DOMAIN]/radio` di Chrome/WebView radio PoC, pilih **"Add to Home Screen"**, buka menu **Settings**, dan rekam tombol PTT fisik radio Anda.
* **Kabel Audio HT**: Hubungkan kabel 3.5mm TRRS atau USB Soundcard CM108 dari HT analog ke HP/PC, klik **`[🔌 Setup Kabel Audio Radio]`** di `/radio`, atur ambang squelch (misal `-32 dB`), dan aktifkan **ON**.

### 6. Aplikasi Android Native (APK)
Tersedia beberapa varian aplikasi Android di repositori ini:
* **`app-android/`** (*NinjaRadio Full*): Aplikasi Kotlin + C++ OpenDMR Vocoder JNI + Chaquopy Python (100% on-device vocoding, background DMR service, UART PoC control, offline Callbook).
* **`app-lite/`** (*NinjaRadio Lite*): Varian ringan untuk perangkat dengan spesifikasi terbatas.
* **`app-pwa/`** (*NinjaRadio PWA Wrapper*): Pembungkus aplikasi native untuk antarmuka Web PWA.

Unduh APK siap pakai di [GitHub Releases](https://github.com/imamimam13/ninjaRadio/releases), atau build sendiri dari source code:
```bash
cd app-android   # atau app-lite / app-pwa
./gradlew assembleDebug
# Output APK ada di app/build/outputs/apk/
```

---

## 📚 Komponen & Library yang Diintegrasikan (Upstream Attribution & Modifications)

NinjaRadio dibangun dengan mengintegrasikan sejumlah modul open-source radio komunikasi terbaik:

1. **OpenDMR Vocoder (`core/opendmr/` & `app-android/third_party/OpenDMR/`)** — *GPL-2.0*
   * Upstream: [`mbelib`](https://github.com/szechyjs/mbelib), [`mbelib-neo`](https://github.com/arancormonk/mbelib-neo), [`OP25 MBEEncoder`](https://github.com/osmocom/op25), dan [`PFFFT`](https://bitbucket.org/jpommier/pffft).
   * Modifikasi: Streamlining khusus DMR, pemetaan standar ETSI TS 102 361-1 (`encode_49bit`), serta fungsi burst 33-byte (`opendmr_encode_burst` / `opendmr_decode_burst`) untuk ritme TDMA 60ms.
2. **DMR BPTC & Link Control Codec (`core/dmr_bptc/`)** — *GPL-3.0*
   * Upstream: [`dmr_utils3`](https://github.com/n0mjs710/dmr_utils3) (N0MJS & G4KLX).
   * Modifikasi: Vendored module dengan fallback pure-Python `_bitarray` agar berjalan di Termux/Android maupun container minimalis tanpa compiler C.
3. **DVSwitch Suite Integration (`Analog_Bridge`, `MMDVM_Bridge`, `md380-emu`)** — *GPL-2.0*
   * Upstream: [DVSwitch Project](https://dvswitch.org) (N4IRR & N4IRS).
   * Modifikasi: Patch `qemu-user-static` di `core/Dockerfile` untuk Debian modern, manajemen worker dinamis tanpa restart daemon utama (*Zero-Restart*), serta isolasi jalur audio DVSwitch Mobile dari jalur WebRTC/Mumble.
4. **HomeBrew DMR Master & Peer Client (`core/dmr_server.py`, `core/dmr_client.py`)** — *GPL-2.0 / GPL-3.0*
   * Upstream: Konsep protokol HomeBrew [HBlink](https://github.com/n0mjs710/HBlink) & [MMDVMHost](https://github.com/g4klx/MMDVMHost).
   * Modifikasi: Ditulis ulang berbasis Python `asyncio`, Voice Parrot bawaan (`TG 9990`), serta dynamic slot & TG retargeting.
5. **Mumble, SvxLink/SvxReflector & Zello Bridges** (`bridge-mumble/`, `svxreflector/`, `bridge-svxlink/`, `bridge-zello/`)
   * Menjembatani protokol VoIP/Analog/PoC secara langsung ke `DirectDmrEngine` menggunakan protokol internal `NPTT` / `NSVX` (UDP `32100`) dan PCM 8kHz tanpa transcoding ganda yang tidak perlu.

---

## 🤝 Kontribusi & Lisensi

Proyek ini bersifat open-source dan didedikasikan untuk komunitas radio komunikasi, relawan kebencanaan, **ORARI**, **RAPI**, dan **Komunitas CB 11-Meter Indonesia**.

Dilisensikan di bawah [MIT License](LICENSE). Silakan gunakan, modifikasi, dan bagikan untuk kepentingan hobi dan kemanusiaan.

---

<div align="center">

**🥷 73 de NinjaRadio Team — Salam Damai di Udara!**

</div>
