#!/usr/bin/env bash
# ==============================================================================
# NinjaRadio Pocket Edition - Debian Environment Provisioner (Android Proot)
# Full Stack (Non-TS3): FreeDMR, Core, WebRTC/PWA, Mumble, SvxLink, Zello
# ==============================================================================
set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}==================================================================${NC}"
echo -e "${BLUE}  NinjaRadio Pocket Edition - Provisioning Debian Environment     ${NC}"
echo -e "${BLUE}  Full Stack Multi-Bridge (Non-TS3) for Android Termux            ${NC}"
echo -e "${BLUE}==================================================================${NC}"

# 1. Environment validation
if [ ! -f /etc/debian_version ]; then
    echo -e "${RED}[ERROR] Script ini harus dijalankan di dalam Debian (proot-distro)!${NC}"
    exit 1
fi

APP_DIR="/app"
if [ ! -d "$APP_DIR" ]; then
    echo -e "${RED}[ERROR] Direktori $APP_DIR tidak ditemukan. Pastikan repo ter-bind ke /app!${NC}"
    exit 1
fi

# 2. Hostname resolution setup (/etc/hosts)
echo -e "${YELLOW}[1/9] Mengonfigurasi resolusi host inter-service di /etc/hosts...${NC}"
HOST_ENTRIES="127.0.0.1 localhost core freedmr svxreflector svxlink-bridge mumble-bridge mumble-server zello-bridge backend frontend"
if ! grep -q "core freedmr" /etc/hosts 2>/dev/null; then
    echo "$HOST_ENTRIES" >> /etc/hosts
    echo -e "${GREEN}✓ Host inter-service berhasil dipetakan ke 127.0.0.1${NC}"
else
    echo -e "${GREEN}✓ Resolusi host inter-service sudah aktif.${NC}"
fi

# 3. APT Dependencies Installation
echo -e "${YELLOW}[2/9] Memperbarui paket Debian & memasang dependensi C/C++, Audio & Python...${NC}"
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends \
    build-essential \
    cmake \
    git \
    curl \
    ca-certificates \
    libsndfile1-dev \
    libasound2-dev \
    libsamplerate0-dev \
    libsamplerate0 \
    libudev-dev \
    sox \
    libopus-dev \
    libopus0 \
    supervisor \
    sqlite3 \
    net-tools \
    procps \
    python3 \
    python3-pip \
    python3-dev \
    nodejs \
    npm \
    libsigc++-2.0-dev \
    libpopt-dev \
    libgcrypt20-dev \
    libjsoncpp-dev \
    libssl-dev \
    libspeex-dev \
    libgsm1-dev \
    libogg-dev \
    libcurl4-openssl-dev \
    tcl-dev \
    tcl \
    mumble-server

# 4. Setup runtime directories
echo -e "${YELLOW}[3/9] Menyiapkan direktori runtime dan data persistence...${NC}"
mkdir -p /app/data /app/data/logs /app/data/svx_pki /var/lib/dvswitch /var/log/supervisor /run
touch /var/lib/dvswitch/subscriber_ids.csv /app/data/subscriber_ids.csv
chmod -R 777 /app/data 2>/dev/null || true

# 5. Mumble Server (Murmur) Configuration
echo -e "${YELLOW}[4/9] Mengonfigurasi Mumble Server (Murmur)...${NC}"
if [ ! -f /etc/mumble-server.ini ] || ! grep -q "NinjaRadio" /etc/mumble-server.ini 2>/dev/null; then
    cat << 'MURMUR_EOF' > /etc/mumble-server.ini
database=/app/data/mumble-server.sqlite
welcometext="<br /><b>Selamat Datang di NinjaRadio Pocket Mumble Server</b><br />"
port=64738
serverpassword=
bandwidth=72000
users=100
uname=root
MURMUR_EOF
    # Set default superuser password
    murmurd -ini /etc/mumble-server.ini -supw ninjaradio_admin 2>/dev/null || true
    echo -e "${GREEN}✓ Mumble Server configured (SuperUser: ninjaradio_admin)${NC}"
fi

# 6. Build & Install mbelib (AMBE2+ synthesis decoder)
echo -e "${YELLOW}[5/9] Membangun mbelib vocoder C-library (ARM64 native)...${NC}"
if [ ! -f /usr/local/lib/libmbe.so ]; then
    rm -rf /tmp/mbelib
    git clone --depth 1 https://github.com/szechyjs/mbelib.git /tmp/mbelib
    cd /tmp/mbelib
    mkdir -p build && cd build
    cmake ..
    make -j$(nproc 2>/dev/null || echo 2)
    make install
    ldconfig
    rm -rf /tmp/mbelib
    echo -e "${GREEN}✓ mbelib berhasil di-compile & terpasang di /usr/local/lib/libmbe.so${NC}"
else
    echo -e "${GREEN}✓ mbelib sudah terpasang.${NC}"
fi

# 7. Build & Install OpenDMR (AMBE+2 encoder & decoder)
echo -e "${YELLOW}[6/9] Membangun OpenDMR vocoder engine (libopendmr.so)...${NC}"
if [ ! -f /usr/local/lib/libopendmr.so ]; then
    if [ -d "/app/core/opendmr" ]; then
        cd /app/core/opendmr
        make clean 2>/dev/null || true
        make
        cp libopendmr.so /usr/local/lib/
        ldconfig
        echo -e "${GREEN}✓ OpenDMR berhasil di-compile & terpasang di /usr/local/lib/libopendmr.so${NC}"
    fi
else
    echo -e "${GREEN}✓ libopendmr.so sudah terpasang.${NC}"
fi

# 8. Build & Install SvxReflector & SvxLink (with Talker Override Patch)
echo -e "${YELLOW}[7/9] Memeriksa instalasi SvxReflector & SvxLink...${NC}"
if [ ! -f /usr/bin/svxreflector ] || [ ! -f /usr/bin/svxlink ]; then
    echo -e "${BLUE}ℹ Meng-clone dan meng-compile SvxLink & SvxReflector dari sumber resmi...${NC}"
    useradd -r -g daemon svxlink 2>/dev/null || true
    rm -rf /tmp/svxlink
    git clone --depth 1 https://github.com/sm0svx/svxlink.git /tmp/svxlink
    
    # Terapkan patch surgical gateway talker override
    if [ -f /app/svxreflector/patch_talker_override.py ]; then
        echo -e "${BLUE}ℹ Menerapkan patch Talker Override ke SvxReflector...${NC}"
        sed -i 's|/src/svxlink/src/svxlink/reflector|/tmp/svxlink/src/svxlink/reflector|g' /app/svxreflector/patch_talker_override.py
        python3 /app/svxreflector/patch_talker_override.py
        # Revert path agar file repo tetap bersih
        sed -i 's|/tmp/svxlink/src/svxlink/reflector|/src/svxlink/src/svxlink/reflector|g' /app/svxreflector/patch_talker_override.py
    fi

    mkdir -p /tmp/svxlink/src/build && cd /tmp/svxlink/src/build
    cmake -DCMAKE_INSTALL_PREFIX=/usr \
          -DSYSCONF_INSTALL_DIR=/etc \
          -DLOCAL_STATE_DIR=/var \
          -DUSE_QT=OFF \
          -DWITH_SYSTEMD=OFF \
          ..
    
    # Batasi ke 2 thread agar RAM Android tidak tercekik saat C++ compile
    NPROC=$(nproc 2>/dev/null || echo 2)
    COMPILE_JOBS=$(( NPROC > 2 ? 2 : NPROC ))
    make -j${COMPILE_JOBS}
    make install
    ldconfig
    rm -rf /tmp/svxlink
    echo -e "${GREEN}✓ SvxReflector & SvxLink berhasil di-compile & terpasang!${NC}"
else
    echo -e "${GREEN}✓ SvxReflector & SvxLink sudah terpasang.${NC}"
fi

# 9. FreeDMR Master Engine Setup
echo -e "${YELLOW}[8/9] Menyiapkan FreeDMR Engine di /opt/freedmr...${NC}"
if [ ! -d /opt/freedmr ]; then
    git clone --depth 1 https://github.com/francedmr/FreeDMR.git /opt/freedmr
fi
mkdir -p /opt/freedmr/json
cp /app/freedmr/freedmr.cfg /opt/freedmr/freedmr.cfg 2>/dev/null || true
cp /app/freedmr/rules.py /opt/freedmr/rules.py 2>/dev/null || true
cp /app/freedmr/ninjaradio_auth.py /opt/freedmr/ninjaradio_auth.py 2>/dev/null || true
cp /app/freedmr/entrypoint.sh /opt/freedmr/entrypoint.sh 2>/dev/null || true
chmod +x /opt/freedmr/entrypoint.sh 2>/dev/null || true
chmod +x /app/svxreflector/entrypoint.sh 2>/dev/null || true

# 10. Python Dependencies Installation
echo -e "${YELLOW}[9/9] Menginstal library Python untuk seluruh stack NinjaRadio...${NC}"
PIP_FLAGS="--no-cache-dir --break-system-packages"
pip3 install $PIP_FLAGS \
    dmr_utils3 \
    twisted \
    bitstring \
    bitarray \
    mysql-connector-python \
    resettabletimer \
    setproctitle \
    configparser \
    cryptography \
    pymumble \
    websockets \
    requests \
    numpy \
    scipy \
    fastapi \
    "uvicorn[standard]" \
    pydantic \
    pydantic-settings \
    sqlalchemy \
    aiosqlite \
    python-multipart \
    httpx \
    aiofiles \
    psutil \
    aiohttp

# Build Next.js frontend (jika belum pernah di-build)
if [ -d "/app/frontend" ]; then
    echo -e "${YELLOW}Memeriksa antarmuka Web Next.js 14...${NC}"
    cd /app/frontend
    if [ ! -d "node_modules" ]; then
        echo -e "${BLUE}ℹ Menginstal dependensi frontend (npm install)...${NC}"
        npm install
    fi
    if [ ! -d ".next" ]; then
        echo -e "${BLUE}ℹ Melakukan compile Next.js standalone build...${NC}"
        npm run build || echo -e "${YELLOW}Catatan: Next.js build dilewati atau selesai.${NC}"
    fi
fi

# Link supervisor config
mkdir -p /etc/supervisor/conf.d
cp /app/termux/supervisord.conf /etc/supervisor/conf.d/ninjaradio.conf 2>/dev/null || true

echo -e "${GREEN}==================================================================${NC}"
echo -e "${GREEN}  ✓ PENGATURAN DEBIAN ANDROID BERHASIL DISELESAIKAN!             ${NC}"
echo -e "${GREEN}  Semua komponen (FreeDMR, Core, WebRTC, Mumble, Svx, Zello)      ${NC}"
echo -e "${GREEN}  sudah siap dijalankan via supervisord.                          ${NC}"
echo -e "${GREEN}==================================================================${NC}"
