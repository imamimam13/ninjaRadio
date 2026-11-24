#!/usr/bin/env bash
# ==============================================================================
# NinjaRadio Pocket Edition - Termux Bootstrap Installer
# Runs natively in Termux (Android)
# ==============================================================================
set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${CYAN}==================================================================${NC}"
echo -e "${CYAN}   NinjaRadio Pocket Edition - Installer Android Termux           ${NC}"
echo -e "${CYAN}   Multi-Bridge Server: DMR, SvxLink, Mumble, Zello & Web PTT     ${NC}"
echo -e "${CYAN}==================================================================${NC}"

# 1. Pastikan dijalankan di dalam Termux
if [ -z "$PREFIX" ] || [ ! -d "/data/data/com.termux" ]; then
    echo -e "${RED}[ERROR] Script ini WAJIB dijalankan langsung di aplikasi Termux Android!${NC}"
    exit 1
fi

# 2. Aktifkan Termux Wake Lock agar CPU tidak tertidur saat layar mati
echo -e "${YELLOW}Mengaktifkan Termux Wake-Lock...${NC}"
termux-wake-lock 2>/dev/null || true

# 3. Dapatkan path absolut direktori repository ninjaRadio
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

echo -e "${BLUE}Direktori ninjaRadio terdeteksi di: ${REPO_DIR}${NC}"

# 4. Update package Termux dan install proot-distro
echo -e "${YELLOW}Memperbarui paket Termux & memasang proot-distro...${NC}"
pkg update -y
pkg install -y proot-distro git curl jq

# 5. Pasang Debian Bookworm jika belum terpasang
if ! proot-distro list 2>/dev/null | grep -q "debian (installed)"; then
    echo -e "${YELLOW}Memasang sistem Debian Bookworm ARM64 via proot-distro...${NC}"
    proot-distro install debian
else
    echo -e "${GREEN}✓ Debian Bookworm sudah terpasang di proot-distro.${NC}"
fi

# 6. Pasang permissions eksekusi pada script termux
chmod +x "${REPO_DIR}/termux/setup-debian.sh"
chmod +x "${REPO_DIR}/termux/ninja-ctl.sh"
chmod +x "${REPO_DIR}/svxreflector/entrypoint.sh" 2>/dev/null || true
chmod +x "${REPO_DIR}/freedmr/entrypoint.sh" 2>/dev/null || true

# 7. Jalankan setup Debian di dalam proot
echo -e "${BLUE}==================================================================${NC}"
echo -e "${BLUE}  Masuk ke Debian untuk meng-compile Vocoder & mengonfigurasi stack... ${NC}"
echo -e "${BLUE}==================================================================${NC}"
proot-distro login debian --bind "${REPO_DIR}:/app" -- bash /app/termux/setup-debian.sh

# 8. Buat perintah 'ninja' di Termux ($PREFIX/bin/ninja)
echo -e "${YELLOW}Membuat shortcut perintah 'ninja' di Termux...${NC}"
cat << EOF > "$PREFIX/bin/ninja"
#!/usr/bin/env bash
REPO="${REPO_DIR}"
CMD="\$1"
shift 1 2>/dev/null || true

case "\$CMD" in
    start|stop|restart|status|logs|log)
        termux-wake-lock 2>/dev/null || true
        proot-distro login debian --bind "\$REPO:/app" -- bash /app/termux/ninja-ctl.sh "\$CMD" "\$@"
        ;;
    shell|sh|bash)
        proot-distro login debian --bind "\$REPO:/app"
        ;;
    update)
        echo "Memperbarui kode dari git..."
        cd "\$REPO" && git pull
        proot-distro login debian --bind "\$REPO:/app" -- bash /app/termux/setup-debian.sh
        ;;
    *)
        echo "NinjaRadio Pocket Edition CLI"
        echo "Penggunaan: ninja {start|stop|restart|status|logs [service]|shell|update}"
        ;;
esac
EOF
chmod +x "$PREFIX/bin/ninja"

echo -e ""
echo -e "${GREEN}==================================================================${NC}"
echo -e "${GREEN}  ✓ INSTALASI NINJARADIO POCKET EDITION BERHASIL!                 ${NC}"
echo -e "${GREEN}==================================================================${NC}"
echo -e "Sekarang Anda dapat mengontrol NinjaRadio dari mana saja di Termux:"
echo -e "  - ${CYAN}ninja start${NC}       : Menyalakan seluruh server & bridge"
echo -e "  - ${CYAN}ninja stop${NC}        : Mematikan seluruh server secara bersih"
echo -e "  - ${CYAN}ninja status${NC}      : Melihat status running seluruh komponen"
echo -e "  - ${CYAN}ninja logs [svc]${NC}  : Melihat log langsung (core, backend, dll)"
echo -e "  - ${CYAN}ninja shell${NC}       : Masuk ke terminal Debian root"
echo -e ""
echo -e "${YELLOW}Tips Hemat Baterai & Anti-Kill:${NC}"
echo -e "1. Matikan Battery Optimization untuk aplikasi Termux di Setelan Android."
echo -e "2. Ketik ${CYAN}ninja start${NC} untuk mulai menjalankan!"
echo -e "${GREEN}==================================================================${NC}"
