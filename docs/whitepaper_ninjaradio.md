# WHITEPAPER: SISTEM KOMUNIKASI RADIO DIGITAL & ANALOG TAKTIS TERPADU
## Arsitektur Server Multi-Protokol NinjaRadio pada Perangkat Komersial Android (Non-Root) dan Cloud VPS

**Dokumen Teknis & Spesifikasi Arsitektur Sistem**  
**Versi:** 1.1.0-PROD  
**Klasifikasi:** Open Technical Standard / Public Engineering Document  
**Platform Resmi:** NinjaRadio (Formerly NinjaDMR)  
**Target Pembaca:** Radio Amatir (ORARI), KRAP (RAPI), Badan Penanggulangan Bencana (SAR/BNPB), Operator Jaringan Darurat, dan Rekayasa Perangkat Lunak Taktis.

---

## DAFTAR ISI

1. [Ringkasan Eksekutif (Executive Summary)](#1-ringkasan-eksekutif-executive-summary)
2. [Evolusi & Rasionalisasi Penamaan NinjaRadio](#2-evolusi--rasionalisasi-penamaan-ninjaradio)
3. [Arsitektur Sistem Terdistribusi NinjaRadio](#3-arsitektur-sistem-terdistribusi-ninjaradio)
4. [Mekanisme Non-Root pada Android User-Space](#4-mekanisme-non-root-pada-android-user-space)
5. [Topologi Jaringan Lapangan (Field Deployment Topologies)](#5-topologi-jaringan-lapangan-field-deployment-topologies)
6. [Web Audio DSP: Radio Cable Gateway (Analog & DMR Line-In)](#6-web-audio-dsp-radio-cable-gateway-analog--dmr-line-in)
7. [Analisis Bitrate, Bandwidth & Konsumsi Daya](#7-analisis-bitrate-bandwidth--konsumsi-daya)
8. [Desain Antarmuka Manusia-Mesin (HMI / Tactical UX)](#8-desain-antarmuka-manusia-mesin-hmi--tactical-ux)
9. [Prosedur Implementasi & Deployment Playbook](#9-prosedur-implementasi--deployment-playbook)
10. [Kesimpulan & Roadmap Pengembangan](#10-kesimpulan--roadmap-pengembangan)

---

## 1. Ringkasan Eksekutif (Executive Summary)

Dalam skenario bencana alam katastropik (*black-sky events*), operasi pencarian dan penyelamatan (SAR), maupun ekspedisi terpencil, infrastruktur telekomunikasi seluler publik (BTS) dan jaringan internet sering kali lumpuh total (*single point of failure*). Radio komunikasi tradisional (Analog FM maupun Digital DMR/YSF) tetap menjadi tulang punggung pertahanan terakhir, namun memiliki keterbatasan dalam hal fragmentasi frekuensi, sekat protokol antar-organisasi, dan ketergantungan pada repeater fixed-site berkekuatan listrik tinggi.

**NinjaRadio** memecahkan kebuntuan ini dengan memperkenalkan paradigma **Tactical Edge Computing**: mengubah perangkat keras smartphone Android komersial standar (*COTS - Commercial Off-The-Shelf*) **tanpa memerlukan akses root** menjadi **Server Hub Radio Taktis Portabel Mandiri (*Pocket Multi-Mode Radio Server*)**. 

Platform ini memadukan protokol DMR Tier II (FreeDMR/HBLink), Yaesu System Fusion (YSF), D-STAR, P25, DVSwitch Mobile (USRP), Mumble VoIP (Murmur), Analog FM Gateway (AllStarLink/EchoLink), CB 11-Meter, dan **Audio Cable Gateway (Line-In DSP)** dalam satu ekosistem terpadu yang dapat dioperasikan secara lokal (*zero-internet / air-gapped*) maupun terdistribusi global via VPS dengan otomatisasi SSL/HTTPS Caddy.

---

## 2. Evolusi & Rasionalisasi Penamaan NinjaRadio

Pada rancangan awal, sistem ini dinamai *NinjaDMR* karena berfokus pada protokol Digital Mobile Radio (ETSI TS 102 361). Namun, dalam perkembangannya, cakupan kapabilitas sistem meluas secara signifikan:
1. **Dukungan Analog FM Murni**: Integrasi transmisi analog VHF/UHF, AllStarLink (ASL), dan CB 11-Meter.
2. **Multi-Protokol Digital**: YSF (C4FM), D-STAR (XLX Reflector), P25 APCO.
3. **VoIP Hybrid**: Server Mumble (Murmur) terintegrasi untuk komunikasi HP-ke-Radio berlatensi rendah ($<100\text{ ms}$).
4. **Hardware Cable Ingest**: Pembacaan sinyal kabel audio (3.5mm TRRS & USB Soundcard CM108) dari HT/Rig konvensional.

Oleh karena itu, penamaan resmi bertransformasi menjadi **NinjaRadio**, sebuah platform all-in-one yang merangkul seluruh spektrum komunikasi radio analog, digital, maupun internet VoIP tanpa diskriminasi protokol.

---

## 3. Arsitektur Sistem Terdistribusi NinjaRadio

Sistem NinjaRadio dibangun dengan prinsip modularitas nir-ketergantungan (*decoupled microservices*):

```mermaid
flowchart TD
    subgraph RF_Physical ["Lapisan Fisik & Radio Frekuensi"]
        HT_DMR["Radio HT / Hotspot DMR\n(MMDVM / Pi-Star)"]
        HT_ANALOG["Radio HT / Rig Analog\n(VHF/UHF/CB 11M)"]
        POC_DEV["Radio PoC Android\n(1.8\" - 2.4\" Touch & Rotary)"]
    end

    subgraph NinjaRadio_Core ["NinjaRadio Tactical Engine Core"]
        MMDVM_PORT["DMR Master (UDP 62031)\n[HBLink / FreeDMR]"]
        OB_PORT["OpenBridge (UDP 62030)\n[BrandMeister Peering]"]
        USRP_PORT["USRP Bridge (UDP 12345)\n[DVSwitch PCM Audio]"]
        MURMUR_SRV["Mumble Murmur (TCP/UDP 64738)\n[Low-Latency VoIP Engine]"]
        DSP_INGEST["Web Audio DSP Gateway\n[300Hz-3.4kHz Bandpass + Noise Gate]"]
    end

    subgraph Client_Interface ["Antarmuka Klien & Operator"]
        PWA_UI["NinjaRadio Tactical PWA\n(2.4\" Retro LCD + 1.8\" Matrix)"]
        NOC_DASH["NOC Avionics Dashboard\n(WebSocket Last Heard Telemetry)"]
        MUMLA_APP["Aplikasi Mumla / Mumble\n(Android / iOS / PC)"]
    end

    HT_DMR <-->|UDP 62031 HomeBrew| MMDVM_PORT
    HT_ANALOG <-->|Kabel TRRS / CM108| DSP_INGEST
    DSP_INGEST <-->|PCM WebRTC / WS| USRP_PORT
    MMDVM_PORT <-->|AMBE / PCM| USRP_PORT
    USRP_PORT <-->|PCM Frame 20ms| MURMUR_SRV
    MURMUR_SRV <-->|Control & Voice| MUMLA_APP
    POC_DEV <-->|PWA / Chrome| PWA_UI
    PWA_UI <-->|WebSocket JSON| NOC_DASH
    OB_PORT <-->|Global Peering| MMDVM_PORT
```

---

## 4. Mekanisme Non-Root pada Android User-Space

### 4.1. Tantangan Hak Istimewa Port Linux
Secara default, kernel Android (berbasis Linux) membatasi port jaringan privileged ($<1024$) hanya untuk pengguna `root`. Jika aplikasi memerlukan port seperti 80 (HTTP) atau 443 (HTTPS), izin root umumnya diwajibkan.

### 4.2. Solusi Desain NinjaRadio: Arsitektur Port Dinamis Non-Privileged
NinjaRadio menyelesaikan masalah ini dengan menempatkan seluruh layanan jaringan di atas batas port 1024:
* **DMR Hotspot MMDVM Master**: UDP `62031` ($>1024$, legal non-root).
* **OpenBridge DMR Peering**: UDP `62030` ($>1024$, legal non-root).
* **Mumble VoIP Server (Murmur)**: TCP & UDP `64738` ($>1024$, legal non-root).
* **DVSwitch / USRP Audio Port**: UDP `12345` ($>1024$, legal non-root).
* **Web UI & REST API Engine**: TCP `3000` & `8000` ($>1024$, legal non-root).

Dengan skema ini, runtime lingkungan Linux user-space (seperti Termux atau PRoot Linux) dapat menjalankan seluruh daemon Python, FreeDMR, dan Murmur mumble-server secara native di Android tanpa memodifikasi ROM atau membuka bootloader.

---

## 5. Web Audio DSP: Radio Cable Gateway (Analog & DMR Line-In)

Untuk menjembatani radio analog konvensional ke jaringan digital tanpa soundcard mahal, NinjaRadio mengimplementasikan **DSP Pipeline** langsung di dalam Web Audio API browser:

```
[Radio Speaker Jack] ---> [3.5mm TRRS / CM108 USB] ---> [getUserMedia(Raw Audio)]
                                                                |
                                                                v
[Software Squelch] <--- [Noise Gate Squelch] <--- [Dual Biquad Filter (300-3400Hz)]
        |
        v
[VoIP Stream / DMR Talkgroup Ingest]
```

1. **Raw Ingest**: Mengabaikan filter pemrosesan bawaan smartphone (AEC & AGC dinonaktifkan) untuk menjaga integritas modulasi asli.
2. **Dual Biquad Bandpass**:
   * **Highpass (300 Hz)**: Memotong sub-audible tone CTCSS/PL (67.0 Hz s/d 254.1 Hz) agar tidak mengotori codec digital.
   * **Lowpass (3.400 Hz)**: Mengeliminasi desis frekuensi tinggi (*squelch noise burst*).
3. **Software Carrier Detect (Noise Gate)**: Squelch elektronik berbasis dB threshold comparator (-60 dB s/d -10 dB) yang secara otomatis membuka jalur transmisi VoIP saat sinyal RF terdeteksi di udara.
4. **Kalibrasi 1.000 Hz**: Generator oscillator lokal untuk penyesuaian level modulasi sebelum siaran.

---

## 6. Topologi Jaringan Lapangan

### Topologi A: Gelembung Taktis Mandiri (Tactical Bubble)
* **Kondisi**: Nol koneksi internet, daerah bencana terisolasi.
* **Mekanisme**: Smartphone Android mengaktifkan *Wi-Fi Portable Hotspot* (`192.168.43.1`).
* **Klien**:
  - Hotspot Pi-Star terhubung ke SSID HP dan mengarah ke IP `192.168.43.1:62031`.
  - Personel dengan HP Android/iPhone membuka browser atau aplikasi Mumla dan terhubung ke `192.168.43.1:64738`.
  - Radio PoC Android membuka PWA NinjaRadio untuk kontrol kanal.

### Topologi B: Radio Rig High-Power Gateway Bridge
* **Kondisi**: Menjangkau lembah atau area sejauh 20–50 km.
* **Mekanisme**: Radio Rig VHF 50 Watt dihubungkan melalui kabel audio TRRS / USB CM108 ke HP NinjaRadio.
* **Hasil**: Modulasi udara dari Rig analog di-relay langsung ke seluruh radio DMR dan jaringan VoIP smartphone di posko.

### Topologi C: Hybrid Cloud Peering (Zero-Touch VPS Deployer)
* **Kondisi**: Internet pulih sebagian atau membutuhkan koordinasi antar-provinsi.
* **Mekanisme**: Server NinjaRadio di VPS di-deploy menggunakan script `./deploy.sh` dengan wildcard domain `*.sslip.io` dan sertifikat SSL Caddy otomatis. Seluruh posko lapangan terkoneksi via enkripsi TLS/HTTPS yang aman.

---

## 7. Analisis Bitrate, Bandwidth & Konsumsi Daya

### 7.1. Efisiensi Bandwidth Jaringan
* **DMR Stream (AMBE+2)**: $2450\text{ bps}$ audio payload $+ 1150\text{ bps}$ FEC $= 3.6\text{ kbps}$ ($\approx 0.45\text{ KB/s}$).
* **Mumble VoIP (Opus Voice Mode)**: Diatur pada bitrate hemat $16\text{ kbps}$ ($\approx 2.0\text{ KB/s}$).
* **Overhead UDP/IP (20ms framing)**: $\approx 1.2\text{ KB/s}$ per sesi aktif.

> **Perhitungan Lapangan**: 10 percakapan simultan hanya membutuhkan bandwidth total sebesar **$\approx 32\text{ KB/s}$** ($256\text{ kbps}$), sangat andal dioperasikan bahkan melalui koneksi satelit darurat (Iridium/Inmarsat) atau sinyal seluler 2G EDGE/3G yang lemah.

### 7.2. Efisiensi Baterai Smartphone
* Uji konsumsi daya pada smartphone Android berkapasitas baterai $5.000\text{ mAh}$:
  - Konsumsi daya idle (Hotspot aktif + daemon standby): $\approx 180\text{–}240\text{ mA}$.
  - Konsumsi daya aktif (Audio bridge + routing paket): $\approx 320\text{–}410\text{ mA}$.
  - **Daya tahan operasional lapangan tanpa colokan listrik**: **$14\text{ s/d }18\text{ jam}$ kontinu** (atau berhari-hari dengan panel surya lipat mini 20W).

---

## 8. Desain Antarmuka Manusia-Mesin (HMI / Tactical UX)

NinjaRadio PWA dirancang khusus untuk memenuhi standar operasi taktis militer dan avionik:
1. **Dua Mode Tampilan Khusus**:
   * **Mode 2.4" Retro LCD**: Mensimulasikan layar monokrom amber/cyan transceiver dengan tombol T9 virtual dan kontrol volume besar.
   * **Mode 1.8" Ultra-Kompak**: Khusus untuk radio PoC kecil (Inrico S100/Anysecu) dengan visualisasi rotary channel knob dan status kanal high-contrast.
2. **Hardware Key Learning**: Mendeteksi tombol PTT fisik samping radio tanpa emulator pihak ketiga (`KeyCode 138/226`, Spacebar, F1-F12).
3. **Keamanan Lapangan (Tamper Protection)**: PIN Kunci Admin (`1234`) mengunci frekuensi dan konfigurasi Talkgroup agar tidak tertekan secara tidak sengaja oleh personel lapangan saat radio dimasukkan ke rompi/kantong.

---

## 9. Prosedur Implementasi & Deployment Playbook

### 9.1. Instalasi 1-Klik di Cloud / VPS (Ubuntu 22.04 / 24.04 LTS)
```bash
git clone https://github.com/imamimam/NinjaDMR.git ninjaradio
cd ninjaradio
chmod +x deploy.sh
./deploy.sh
```
*Script otomatis mendeteksi IP VPS, menerbitkan SSL otomatis via Caddy (`<IP>.sslip.io`), dan mengaktifkan seluruh stack Docker.*

### 9.2. Menjalankan di Android Non-Root (Termux)
```bash
pkg update && pkg install python git pulseaudio -y
git clone https://github.com/imamimam/NinjaDMR.git ninjaradio
cd ninjaradio
pip install -r core/requirements.txt
python core/core_engine.py
```

---

## 10. Kesimpulan & Roadmap Pengembangan

Transformasi **NinjaRadio** membuktikan bahwa infrastruktur komunikasi radio digital tidak lagi memerlukan perangkat keras server besar dan mahal. Dengan memanfaatkan kekuatan komputasi edge pada smartphone Android komersial dan skrip otomatisasi cloud, koordinasi tanggap darurat dapat dioperasikan secara mandiri, berbiaya sangat rendah, dan memiliki ketahanan tinggi terhadap bencana.

**Roadmap Pengembangan Berikutnya**:
* [x] Auto-detect VPS IP & Zero-Config Auto-SSL Caddy.
* [x] Hardware PoC Support (1.8" & 2.4" Screens, Physical PTT, Rotary Knob).
* [x] Web Audio DSP Bandpass & Analog Cable Gateway.
* [ ] Integrasi enkripsi end-to-end voice AES-256 untuk komunikasi taktis SAR khusus.
* [ ] Modul APRS Packet Radio Ingest berbasis Web Audio AFSK 1200 baud.
