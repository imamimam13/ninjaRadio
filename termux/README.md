# 📻 NinjaRadio Pocket Edition (Android Termux)

Varian resmi **NinjaRadio Pocket / Field Kit Edition** yang dirancang untuk berjalan di smartphone Android menggunakan **Termux + Proot Debian (ARM64)** tanpa memerlukan akses Root.

Membawa seluruh kemampuan **Multi-Bridge Server (DMR, SvxLink/SvxReflector, Mumble, Zello Channels, dan Web PTT PWA)** langsung di dalam saku Anda — ideal untuk repeater darurat lapangan, tim SAR, komunikasi bencana, atau eksperimen pribadi.

---

## 🌟 Fitur & Komponen yang Berjalan

Stack ini menjalankan **9 layanan** secara bersamaan:

1. **FreeDMR Master Engine** (Port UDP `62031` untuk Hotspot MMDVM/Pi-Star, Port UDP `62030` untuk OpenBridge).
2. **NinjaRadio Core Engine** (Direct DMR Vocoder C/C++ `libopendmr.so` & `libmbe.so`, USRP Port UDP `32345`, Web PTT UDP `32100`, Core API `5000`).
3. **FastAPI Backend & Hub** (Port HTTP `8000`, WebSocket telemetry, SQLite Callbook & Self-Care).
4. **Next.js 14 Responsive PWA** (Port HTTP `3000`, Web PTT multi-talkgroup, Dashboard monitor, remote control).
5. **Mumble VoIP Server (Murmur)** (Port TCP/UDP `64738`, VoIP fidelitas tinggi hemat kuota).
6. **Mumble <-> DMR Audio Bridge** (Sinkronisasi dua arah room Mumble dengan DMR Talkgroup).
7. **SvxReflector Conference Server** (Port TCP/UDP `5300`, Status HTTP `8080`, Multi-TG analog reflector).
8. **SvxLink <-> DMR Audio Bridge** (Integrasi repeater SvxLink/EchoLink ke DMR Talkgroup).
9. **Zello Channels Audio Bridge** (Port HTTP `12350`, gateway suara dua arah dengan Zello).

*(Catatan: Layanan TeamSpeak 3 Server sengaja dilewati karena TS3 adalah binary tertutup x86_64 yang tidak mendukung arsitektur CPU ARM Android).*

---

## 📱 Persyaratan Perangkat

- **Aplikasi**: **Termux** (Wajib install dari [F-Droid](https://f-droid.org/en/packages/com.termux/), **JANGAN** dari Google Play Store karena versinya sudah usang).
- **RAM**: Minimal 4 GB (Disarankan 6 GB atau 8 GB untuk compile & multi-service lancar).
- **Penyimpanan Kosong**: Minimal 3.5 GB untuk Debian proot dan compiler C++/Node.js.
- **Sistem Operasi**: Android 8.0 hingga Android 15.

---

## 🚀 Langkah Instalasi Cepat

### 1. Buka Termux dan Pasang Git
Ketik perintah berikut di Termux:
```bash
pkg update -y
pkg install -y git
```

### 2. Clone Repositori NinjaRadio
```bash
git clone https://github.com/imamimam13/ninjaRadio.git
cd ninjaRadio
```

### 3. Jalankan Script Installer
```bash
bash termux/install.sh
```

> **Catatan Proses Instalasi:**
> - Installer otomatis memasang Debian ARM64, dependensi audio, dan meng-compile vocoder `mbelib` serta `libopendmr.so`.
> - Jika ini instalasi pertama, proses kompilasi SvxLink & Next.js membutuhkan waktu sekitar **10–15 menit** tergantung kecepatan prosesor HP Anda.
> - Pastikan koneksi internet stabil dan baterai HP cukup.

---

## 🎮 Cara Mengontrol NinjaRadio

Setelah instalasi selesai, script telah membuat perintah praktis **`ninja`** yang bisa dipanggil dari mana saja di Termux:

| Perintah | Deskripsi |
| :--- | :--- |
| `ninja start` | Menyalakan seluruh 9 service secara otomatis di latar belakang |
| `ninja status` | Memeriksa status setiap service (apakah `RUNNING` atau ada error) |
| `ninja stop` | Mematikan seluruh service secara bersih (*graceful shutdown*) |
| `ninja restart` | Me-restart seluruh server |
| `ninja logs` | Melihat live log gabungan semua service |
| `ninja logs core` | Melihat log khusus Core Engine |
| `ninja logs backend` | Melihat log khusus API Backend |
| `ninja shell` | Masuk ke terminal interaktif Debian root |
| `ninja update` | Menarik update terbaru dari GitHub dan memperbarui konfigurasi |

---

## 🌐 Cara Akses dari Browser & Perangkat Lain

Pastikan HP Anda dan perangkat klien (laptop/HP lain/HT) terhubung ke **jaringan Wi-Fi yang sama** atau **Hotspot HP yang sama**:

1. **Cari IP HP Anda**:
   Ketik `ifconfig` di Termux, atau lihat di status `ninja start` (misalnya `192.168.43.1`).
2. **Buka Web PTT & Dashboard**:
   Buka browser di HP atau laptop:
   ```text
   http://192.168.43.1:3000
   ```
3. **Klien DVSwitch Mobile**:
   Arahkan Host DVSwitch ke `192.168.43.1` Port UDP `32345`.
4. **Hotspot MMDVM / Pi-Star**:
   Arahkan DMR Master ke IP HP Port UDP `62031`.
5. **Klien Mumble**:
   Konek ke `192.168.43.1` Port `64738` (Password admin: `ninjaradio_admin`).

---

## ⚡ Pengaturan Penting Agar HP Tidak Membunuh Aplikasi

Sistem operasi Android modern sangat agresif mematikan proses background demi menghemat baterai. Lakukan 2 langkah penting ini:

1. **Matikan Optimasi Baterai (Battery Optimization)**:
   - Buka **Pengaturan Android** -> **Aplikasi** -> **Termux** -> **Baterai**.
   - Pilih opsi **Tidak Dibatasi / Unrestricted**.
2. **Kunci Aplikasi di Recent Apps**:
   - Buka menu Recent Apps di Android.
   - Tekan dan tahan ikon Termux, lalu pilih ikon **Gembok (Lock)** agar Termux tidak terhapus saat membersihkan RAM.
3. **Wake-Lock**:
   - Perintah `ninja start` otomatis memicu `termux-wake-lock` sehingga CPU tetap aktif memproses audio meski layar HP dimatikan.

---

## 🛡️ Struktur & Isolasi File

Semua data dan database disimpan langsung di folder `data/` repositori utama Anda:
- Database SQLite: `/app/data/ninjaradio.db`
- File Konfigurasi: `/app/data/config.json`
- File Log: `/app/data/logs/*.log`

Data Anda **tidak akan hilang** jika Anda keluar dari Termux atau me-reboot HP.
