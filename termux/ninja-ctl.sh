#!/usr/bin/env bash
# ==============================================================================
# NinjaRadio Pocket Edition - Service Controller
# ==============================================================================
CONF="/app/termux/supervisord.conf"
SOCK="/run/supervisor.sock"
PID_FILE="/run/supervisord.pid"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

get_local_ip() {
    ip route get 1.1.1.1 2>/dev/null | awk '{print $7}' || hostname -I | awk '{print $1}'
}

is_running() {
    if [ -S "$SOCK" ]; then
        supervisorctl -c "$CONF" pid >/dev/null 2>&1
        return $?
    fi
    return 1
}

start_services() {
    if is_running; then
        echo -e "${YELLOW}ℹ NinjaRadio sudah berjalan!${NC}"
        supervisorctl -c "$CONF" status
        return 0
    fi

    echo -e "${BLUE}▶ Menyalakan NinjaRadio Pocket Stack...${NC}"
    mkdir -p /app/data/logs /run
    supervisord -c "$CONF"
    sleep 3

    echo -e "${GREEN}✓ NinjaRadio Pocket Edition aktif!${NC}"
    supervisorctl -c "$CONF" status

    MY_IP=$(get_local_ip)
    echo -e ""
    echo -e "${CYAN}================================================================${NC}"
    echo -e "${GREEN}  ANTARMUKA PENGGUNA TERSEDIA DI:${NC}"
    echo -e "  - Web PTT & Dashboard : ${CYAN}http://${MY_IP}:3000${NC}"
    echo -e "  - Backend REST / Docs : ${CYAN}http://${MY_IP}:8000/docs${NC}"
    echo -e "  - DMR Master Hotspot  : ${CYAN}UDP ${MY_IP}:62031${NC}"
    echo -e "  - DVSwitch Mobile     : ${CYAN}UDP ${MY_IP}:32345${NC}"
    echo -e "  - Mumble VoIP Server  : ${CYAN}${MY_IP}:64738${NC}"
    echo -e "  - SvxReflector Server : ${CYAN}${MY_IP}:5300${NC}"
    echo -e "${CYAN}================================================================${NC}"
}

stop_services() {
    if ! is_running; then
        echo -e "${YELLOW}ℹ NinjaRadio sedang tidak berjalan.${NC}"
        return 0
    fi

    echo -e "${YELLOW}⏹ Menghentikan seluruh service NinjaRadio...${NC}"
    supervisorctl -c "$CONF" stop all 2>/dev/null || true
    supervisorctl -c "$CONF" shutdown 2>/dev/null || true
    sleep 2
    rm -f "$SOCK" "$PID_FILE" 2>/dev/null || true
    echo -e "${GREEN}✓ Semua service NinjaRadio berhasil dihentikan secara bersih.${NC}"
}

status_services() {
    if ! is_running; then
        echo -e "${RED}● Status: NinjaRadio MATI (Off)${NC}"
        return 0
    fi
    echo -e "${GREEN}● Status: NinjaRadio AKTIF (Running)${NC}"
    echo -e "${CYAN}----------------------------------------------------------------${NC}"
    supervisorctl -c "$CONF" status
    echo -e "${CYAN}----------------------------------------------------------------${NC}"
}

logs_service() {
    SVC="$1"
    if [ -z "$SVC" ]; then
        echo -e "${YELLOW}Pilih service untuk melihat log:${NC}"
        echo -e "  core, freedmr, backend, frontend, mumble-server, mumble-bridge, svxreflector, svxlink-bridge, zello-bridge, all"
        read -p "Nama service: " SVC
    fi

    if [ "$SVC" == "all" ] || [ -z "$SVC" ]; then
        tail -f /app/data/logs/*.log
    else
        LOG_FILE="/app/data/logs/${SVC//-/_}.log"
        if [ -f "$LOG_FILE" ]; then
            tail -n 100 -f "$LOG_FILE"
        else
            echo -e "${RED}Log untuk '$SVC' tidak ditemukan di $LOG_FILE${NC}"
        fi
    fi
}

case "$1" in
    start)
        start_services
        ;;
    stop)
        stop_services
        ;;
    restart)
        stop_services
        sleep 2
        start_services
        ;;
    status)
        status_services
        ;;
    logs|log)
        logs_service "$2"
        ;;
    supervisorctl)
        shift
        supervisorctl -c "$CONF" "$@"
        ;;
    *)
        echo "Penggunaan: $0 {start|stop|restart|status|logs [service]|supervisorctl}"
        exit 1
        ;;
esac
