#!/bin/bash
# Установка системных зависимостей для CompressPhotoFast CLI

# Подключаем общие функции
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/install_common.sh"

# ============================================
# Проверить наличие системных зависимостей для HEIC
# ============================================
check_system_dependencies() {
    log_step "Проверка системных зависимостей для HEIC поддержки..."

    local os=$(detect_os)

    if [ "$os" = "macos" ]; then
        # На macOS проверяем наличие libheif через brew
        if brew list libheif &> /dev/null; then
            log_success "Системные зависимости уже установлены"
            return 0
        else
            log_warning "libheif не найден (требуется для HEIC поддержки)"
            return 1
        fi
    fi

    # На Linux проверяем наличие пакетов
    local missing_packages=()

    # Проверка pkg-config
    if ! check_command pkg-config; then
        missing_packages+=("pkg-config")
    fi

    # Проверка libheif
    if ! pkg-config --exists libheif 2>/dev/null; then
        missing_packages+=("libheif-dev")
    fi

    # Проверка libffi
    if ! pkg-config --exists libffi 2>/dev/null; then
        missing_packages+=("libffi-dev")
    fi

    if [ ${#missing_packages[@]} -eq 0 ]; then
        log_success "Системные зависимости уже установлены"
        return 0
    else
        log_info "Отсутствуют пакеты: ${missing_packages[*]}"
        return 1
    fi
}

# ============================================
# Установить зависимости на Ubuntu/Debian
# ============================================
install_system_dependencies_ubuntu() {
    log_info "Установка системных зависимостей на Ubuntu/Debian..."

    if ! request_sudo "установки системных зависимостей"; then
        return 1
    fi

    # Обновить список пакетов
    log_info "Обновление списка пакетов..."
    sudo apt-get update -qq || {
        log_error "Не удалось обновить список пакетов"
        return 1
    }

    # Установить зависимости
    log_info "Установка libheif, libffi, python3-venv..."
    sudo apt-get install -y \
        libheif-dev \
        libffi-dev \
        python3-venv \
        pkg-config \
        || {
        log_error "Не удалось установить системные зависимости"
        return 1
    }

    log_success "Системные зависимости установлены"
    return 0
}

# ============================================
# Установить зависимости на Fedora/RHEL
# ============================================
install_system_dependencies_fedora() {
    log_info "Установка системных зависимостей на Fedora/RHEL..."

    if ! request_sudo "установки системных зависимостей"; then
        return 1
    fi

    sudo dnf install -y \
        libheif-devel \
        libffi-devel \
        python3-venv \
        pkg-config \
        || {
        log_error "Не удалось установить системные зависимости"
        return 1
    }

    log_success "Системные зависимости установлены"
    return 0
}

# ============================================
# Установить зависимости на Arch Linux
# ============================================
install_system_dependencies_arch() {
    log_info "Установка системных зависимостей на Arch Linux..."

    if ! request_sudo "установки системных зависимостей"; then
        return 1
    fi

    sudo pacman -S --noconfirm \
        libheif \
        libffi \
        pkgconf \
        || {
        log_error "Не удалось установить системные зависимости"
        return 1
    }

    log_success "Системные зависимости установлены"
    return 0
}

# ============================================
# Установить зависимости на macOS
# ============================================
install_system_dependencies_macos() {
    log_info "Установка системных зависимостей на macOS..."

    if check_command brew; then
        brew install libheif pkg-config || {
            log_error "Не удалось установить libheif через Homebrew"
            return 1
        }
    else
        log_error "Homebrew не найден. Установите Homebrew:"
        log_info "  /bin/bash -c \"\$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)\""
        return 1
    fi

    log_success "Системные зависимости установлены"
    return 0
}

# ============================================
# Установить системные зависимости (автоопределение)
# ============================================
install_system_dependencies() {
    log_step "Установка системных зависимостей..."

    local os=$(detect_os)
    local distro=""

    case "$os" in
        linux)
            distro=$(detect_distro)
            case "$distro" in
                ubuntu|debian|linuxmint|pop|zorin|elementary)
                    install_system_dependencies_ubuntu
                    ;;
                fedora|rhel|centos|rocky|almalinux)
                    install_system_dependencies_fedora
                    ;;
                arch|manjaro|endeavouros)
                    install_system_dependencies_arch
                    ;;
                *)
                    log_warning "Неподдерживаемый дистрибутив: $distro"
                    log_info "Пожалуйста, установите зависимости вручную:"
                    log_info "  - libheif-dev (или libheif-devel)"
                    log_info "  - libffi-dev (или libffi-devel)"
                    log_info "  - python3-venv"
                    log_info "  - pkg-config"
                    return 1
                    ;;
            esac
            ;;
        macos)
            install_system_dependencies_macos
            ;;
        *)
            log_error "Неподдерживаемая ОС: $os"
            return 1
            ;;
    esac
}

# ============================================
# Установить без sudo (в домашнюю директорию)
# ============================================
install_without_sudo() {
    log_warning "Установка без прав sudo в домашнюю директорию"
    log_info "Системные зависимости HEIC могут не работать без libheif"

    local install_dir="$HOME/.local"
    mkdir -p "$install_dir/bin" "$install_dir/lib" "$install_dir/include"

    log_info "Для полной функциональности HEIC установите:"
    log_info "  sudo apt-get install libheif-dev libffi-dev"

    # Предлагаем продолжить без HEIC поддержки
    echo
    read -p "Продолжить без HEIC поддержки? (y/N) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        return 1
    fi

    return 0
}

# ============================================
# Экспорт функций
# ============================================
export -f check_system_dependencies
export -f install_system_dependencies_ubuntu
export -f install_system_dependencies_fedora
export -f install_system_dependencies_arch
export -f install_system_dependencies_macos
export -f install_system_dependencies
export -f install_without_sudo
