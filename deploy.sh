#!/bin/bash
# ==============================================================================
#  🥷 NinjaRadio 100% Automated Zero-Touch VPS Deployer & Auto-SSL Engine
# ==============================================================================

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m' # No Color

echo -e "${CYAN}${BOLD}"
echo "================================================================="
echo "   🥷 NINJARADIO - AUTOMATED ZERO-TOUCH VPS DEPLOYER"
echo "   Auto-Detect VPS IP • Auto-SSL HTTPS • PWA • Mumble • DVSwitch"
echo "================================================================="
echo -e "${NC}"

# 1. Otomatis Deteksi IP Publik VPS dari beberapa sumber terpercaya
echo -e "${YELLOW}🔍 Mendeteksi Alamat IP Publik VPS secara otomatis...${NC}"
PUBLIC_IP=$(curl -s4 --max-time 4 https://api.ipify.org || \
            curl -s4 --max-time 4 https://ifconfig.me || \
            curl -s4 --max-time 4 https://icanhazip.com || \
            curl -s4 --max-time 4 https://checkip.amazonaws.com || echo "")

# Hapus spasi / newline jika ada
PUBLIC_IP=$(echo "$PUBLIC_IP" | tr -d '[:space:]')

# 2. Tentukan Domain Secara Otomatis
if [ -n "$1" ]; then
    # Jika user memasukkan domain custom lewat argumen: ./deploy.sh dmr.domain.com
    FINAL_DOMAIN="$1"
    echo -e "${GREEN}✓ Menggunakan Domain Kustom: ${BOLD}${FINAL_DOMAIN}${NC}"
elif [ -n "$PUBLIC_IP" ]; then
    # Otomatis menggunakan wildcard domain sslip.io berbasis IP publik VPS
    FINAL_DOMAIN="${PUBLIC_IP}.sslip.io"
    echo -e "${GREEN}✓ Berhasil mendeteksi IP VPS: ${BOLD}${PUBLIC_IP}${NC}"
    echo -e "${GREEN}✓ Otomatis mengonfigurasi Domain SSL: ${BOLD}${FINAL_DOMAIN}${NC}"
else
    FINAL_DOMAIN="localhost"
    echo -e "${RED}⚠️  Gagal mendeteksi IP publik (offline/lokal). Menggunakan 'localhost'.${NC}"
fi

# 3. Otomatis Tulis Konfigurasi ke file .env
echo ""
echo -e "${YELLOW}⚙️  Menyimpan konfigurasi otomatis ke .env...${NC}"
cat <<EOF > .env
DOMAIN=${FINAL_DOMAIN}
SERVER_CALLSIGN=N1NJA
SERVER_DMR_ID=5109999
DEFAULT_TALKGROUP=510
MUMBLE_SUPERUSER_PASSWORD=ninjaradio_admin
EOF
echo -e "${GREEN}✓ Konfigurasi .env siap dengan DOMAIN=${FINAL_DOMAIN}${NC}"

# 4. Cek & Pasang Docker Otomatis jika belum terpasang
if ! command -v docker &> /dev/null; then
    echo -e "${YELLOW}📦 Docker belum terpasang di VPS ini. Memasang Docker secara otomatis...${NC}"
    curl -fsSL https://get.docker.com -o get-docker.sh && sh get-docker.sh
    rm -f get-docker.sh
    echo -e "${GREEN}✓ Docker berhasil dipasang.${NC}"
fi

# Tentukan apakah butuh sudo untuk menjalankan docker
if docker info &>/dev/null; then
    DOCKER_CMD="docker"
elif command -v sudo &>/dev/null; then
    DOCKER_CMD="sudo docker"
    sudo usermod -aG docker "$USER" 2>/dev/null || true
else
    DOCKER_CMD="docker"
fi

# 5. Jalankan Seluruh Stack NinjaRadio di Docker
echo ""
echo -e "${CYAN}🚀 Membangun dan menjalankan seluruh kontainer NinjaRadio...${NC}"
$DOCKER_CMD compose up -d --build

# 6. Tampilkan Hasil Akhir & Link Akses Langsung
echo ""
echo -e "${GREEN}${BOLD}================================================================="
echo "   🎉 NINJARADIO AKTIF & BERJALAN DENGAN SERTIFIKAT SSL OTOMATIS!"
echo "=================================================================${NC}"
echo ""
echo -e "  🌐 ${BOLD}NOC Dashboard:${NC}        https://${FINAL_DOMAIN}"
echo -e "  📻 ${BOLD}Radio Pocket PWA:${NC}     https://${FINAL_DOMAIN}/radio"
echo -e "  🔀 ${BOLD}TG Matrix Router:${NC}     https://${FINAL_DOMAIN}/matrix"
echo -e "  ⚙️  ${BOLD}Web Config:${NC}          https://${FINAL_DOMAIN}/settings"
echo -e "  🔒 ${BOLD}Default Admin PIN:${NC}    1234"
echo ""
echo -e "  ${CYAN}--- Port Layanan Radio Eksternal ---${NC}"
echo -e "  📶 ${BOLD}MMDVM Hotspot (Pi-Star):${NC}  UDP Port 62031 (Password: ninjaradio_pass)"
echo -e "  📱 ${BOLD}DVSwitch USRP Mobile:${NC}     UDP Port 12345"
echo -e "  🎙️  ${BOLD}Mumble VoIP Server:${NC}       TCP/UDP Port 64738"
echo -e "  🌐 ${BOLD}OpenBridge Peering:${NC}       UDP Port 62030"
echo ""
echo -e "${YELLOW}💡 Info PWA: Buka https://${FINAL_DOMAIN}/radio di HP Anda,${NC}"
echo -e "${YELLOW}   tekan 'Add to Home Screen' untuk install & aktifkan izin Mic/VOX!${NC}"
echo "================================================================="
