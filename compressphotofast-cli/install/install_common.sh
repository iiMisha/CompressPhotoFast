#!/bin/bash
# Общие функции для установщика CompressPhotoFast CLI
# Используется в install.sh, detect_python.sh, install_deps.sh

# ============================================
# Цвета для вывода
# ============================================
export RED='\033[0;31m'
export GREEN='\033[0;32m'
export YELLOW='\033[1;33m'
export BLUE='\033[0;34m'
export CYAN='\033[0;36m'
export NC='\033[0m' # No Color

# ============================================
# Логирование
# ============================================
log_info() {
    echo -e "${BLUE}[i]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[+]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[!]${NC} $1"
}

log_error() {
    echo -e "${RED}[x]${NC} $1"
}

log_step() {
    echo -e "${CYAN}[*]${NC} $1"
}

# ============================================
# Баннер
# ============================================
print_banner() {
    echo -e "${CYAN}"
    cat << 'EOF'
╔══════════════════════════════════════════════════════════╗
║  CompressPhotoFast CLI - Installer                       ║
║  Version 1.0.0                                           ║
╚══════════════════════════════════════════════════════════╝
EOF
    echo -e "${NC}"
}

# ============================================
# Progress spinner для долгих операций
# ============================================
spinner_pid=""

start_spinner() {
    local message="$1"
    local delay=0.1
    local spinstr='|/-\'

    printf "${BLUE}%s${NC}" "$message"

    # Запускаем spinner в фоне
    (
        while true; do
            for (( i=0; i<${#spinstr}; i++ )); do
                printf "${CYAN}%c${NC}" "${spinstr:$i:1}"
                sleep "$delay"
                printf "\b"
            done
        done
    ) &
    spinner_pid=$!
}

stop_spinner() {
    if [ -n "$spinner_pid" ]; then
        kill "$spinner_pid" 2>/dev/null
        wait "$spinner_pid" 2>/dev/null
        spinner_pid=""
        # Очистить строку и вывести результат
        printf "\r%40s\r" " "  # Очистить строку
    fi
}

# ============================================
# Проверка наличия команды
# ============================================
check_command() {
    command -v "$1" &> /dev/null
}

# ============================================
# Определение ОС
# ============================================
detect_os() {
    if [[ "$OSTYPE" == "linux-gnu"* ]]; then
        echo "linux"
    elif [[ "$OSTYPE" == "darwin"* ]]; then
        echo "macos"
    else
        echo "unknown"
    fi
}

# ============================================
# Определение дистрибутива Linux
# ============================================
detect_distro() {
    if [ -f /etc/os-release ]; then
        . /etc/os-release
        echo "$ID"
    elif [ -f /etc/redhat-release ]; then
        echo "rhel"
    elif [ -f /etc/debian_version ]; then
        echo "debian"
    else
        echo "unknown"
    fi
}

# ============================================
# Определение пакетного менеджера
# ============================================
detect_package_manager() {
    if check_command apt-get; then
        echo "apt"
    elif check_command dnf; then
        echo "dnf"
    elif check_command yum; then
        echo "yum"
    elif check_command pacman; then
        echo "pacman"
    elif check_command zypper; then
        echo "zypper"
    else
        echo "unknown"
    fi
}

# ============================================
# Проверка sudo
# ============================================
has_sudo() {
    sudo -n true 2>/dev/null
}

# ============================================
# Запрос sudo с сообщением
# ============================================
request_sudo() {
    local message="$1"

    if ! has_sudo; then
        log_warning "Требуются права sudo для: $message"
        sudo -v || {
            log_error "Не удалось получить права sudo"
            return 1
        }
    fi
    return 0
}

# ============================================
# Cleanup при прерывании
# ============================================
cleanup() {
    local exit_code=$?

    # Остановить spinner если работает
    stop_spinner

    if [ $exit_code -ne 0 ]; then
        log_error "Установка прервана с кодом: $exit_code"
        if [ -n "$INSTALL_LOG" ]; then
            log_info "Лог установки сохранен в: $INSTALL_LOG"
        fi
    fi

    exit $exit_code
}

# Настройка trap для сигналов
trap cleanup SIGINT SIGTERM ERR EXIT

# ============================================
# Создание лог-файла
# ============================================
setup_logging() {
    local log_dir="/tmp"
    if [ -w "$log_dir" ]; then
        INSTALL_LOG="$log_dir/compressphotofast-install-$(date +%Y%m%d-%H%M%S).log"
        export INSTALL_LOG
    fi
}

# ============================================
# Проверка интернет-соединения
# ============================================
check_internet() {
    if check_command curl; then
        curl -s --head https://www.google.com | head -n 1 | grep "200" > /dev/null
    elif check_command wget; then
        wget -q --spider https://www.google.com
    else
        log_warning "Не удалось проверить интернет-соединение (curl/wget не найдены)"
        return 0  # Предполагаем, что соединение есть
    fi
}

# ============================================
# Экспорт всех функций для использования в других скриптах
# ============================================
export -f log_info log_success log_warning log_error log_step
export -f print_banner
export -f start_spinner stop_spinner
export -f check_command
export -f detect_os detect_distro detect_package_manager
export -f has_sudo request_sudo
export -f cleanup
export -f setup_logging
export -f check_internet
