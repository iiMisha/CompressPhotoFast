#!/bin/bash
# Детектор и установщик Python для CompressPhotoFast CLI

# Подключаем общие функции
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/install_common.sh"

# Минимальная версия Python
MIN_PYTHON_MAJOR=3
MIN_PYTHON_MINOR=10

# ============================================
# Получить версию Python в формате major.minor
# ============================================
get_python_version() {
    local python_cmd="$1"
    $python_cmd --version 2>&1 | sed -E 's/Python ([0-9]+\.[0-9]+)\.[0-9]+/\1/'
}

# ============================================
# Проверить версию Python
# ============================================
check_python_version_sufficient() {
    local version="$1"
    local major=$(echo "$version" | cut -d. -f1)
    local minor=$(echo "$version" | cut -d. -f2)

    if [ "$major" -gt "$MIN_PYTHON_MAJOR" ]; then
        return 0
    elif [ "$major" -eq "$MIN_PYTHON_MAJOR" ] && [ "$minor" -ge "$MIN_PYTHON_MINOR" ]; then
        return 0
    fi
    return 1
}

# ============================================
# Проверить наличие Python 3.10+
# ============================================
check_python_version() {
    log_step "Проверка Python..."

    # Список команд для проверки
    local python_commands=("python3" "python" "python3.12" "python3.11" "python3.10")

    for cmd in "${python_commands[@]}"; do
        if check_command "$cmd"; then
            local version=$(get_python_version "$cmd")

            if check_python_version_sufficient "$version"; then
                PYTHON_CMD="$cmd"
                PYTHON_VERSION="$version"
                log_success "Python $version найден (>= $MIN_PYTHON_MAJOR.$MIN_PYTHON_MINOR требуется)"
                return 0
            else
                log_info "Найден Python $version, но требуется >= $MIN_PYTHON_MAJOR.$MIN_PYTHON_MINOR"
            fi
        fi
    done

    log_warning "Python $MIN_PYTHON_MAJOR.$MIN_PYTHON_MINOR+ не найден"
    return 1
}

# ============================================
# Получить команду Python
# ============================================
get_python_executable() {
    echo "$PYTHON_CMD"
}

# ============================================
# Установить Python на Ubuntu/Debian
# ============================================
install_python_ubuntu() {
    log_info "Установка Python на Ubuntu/Debian..."

    # Добавить deadsnakes PPA для последних версий Python
    if ! request_sudo "добавления PPA репозитория"; then
        return 1
    fi

    # Обновить список пакетов
    log_info "Обновление списка пакетов..."
    sudo apt-get update -qq || {
        log_error "Не удалось обновить список пакетов"
        return 1
    }

    # Установить необходимые пакеты
    log_info "Установка Python и зависимостей..."
    sudo apt-get install -y \
        software-properties-common \
        python3.12 \
        python3.12-venv \
        python3.12-dev \
        python3-pip \
        || {
        log_error "Не удалось установить Python"
        return 1
    }

    log_success "Python установлен"
    return 0
}

# ============================================
# Установить Python на Fedora/RHEL
# ============================================
install_python_fedora() {
    log_info "Установка Python на Fedora/RHEL..."

    if ! request_sudo "установки Python"; then
        return 1
    fi

    sudo dnf install -y python3.12 python3.12-pip python3-devel || {
        log_error "Не удалось установить Python"
        return 1
    }

    log_success "Python установлен"
    return 0
}

# ============================================
# Установить Python на Arch Linux
# ============================================
install_python_arch() {
    log_info "Установка Python на Arch Linux..."

    if ! request_sudo "установки Python"; then
        return 1
    fi

    sudo pacman -S --noconfirm python python-pip || {
        log_error "Не удалось установить Python"
        return 1
    }

    log_success "Python установлен"
    return 0
}

# ============================================
# Установить Python на macOS
# ============================================
install_python_macos() {
    log_info "Установка Python на macOS..."

    if check_command brew; then
        log_info "Использование Homebrew..."
        brew install python@3.12 || {
            log_error "Не удалось установить Python через Homebrew"
            return 1
        }
    else
        log_error "Homebrew не найден. Установите Homebrew:"
        log_info "  /bin/bash -c \"\$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)\""
        return 1
    fi

    log_success "Python установлен"
    return 0
}

# ============================================
# Установить Python (автоопределение дистрибутива)
# ============================================
install_python() {
    log_step "Установка Python $MIN_PYTHON_MAJOR.$MIN_PYTHON_MINOR+..."

    local os=$(detect_os)
    local distro=""

    case "$os" in
        linux)
            distro=$(detect_distro)
            case "$distro" in
                ubuntu|debian|linuxmint|pop|zorin|elementary)
                    install_python_ubuntu
                    ;;
                fedora|rhel|centos|rocky|almalinux)
                    install_python_fedora
                    ;;
                arch|manjaro|endeavouros)
                    install_python_arch
                    ;;
                *)
                    log_error "Неподдерживаемый дистрибутив: $distro"
                    log_info "Пожалуйста, установите Python $MIN_PYTHON_MAJOR.$MIN_PYTHON_MINOR+ вручную"
                    return 1
                    ;;
            esac
            ;;
        macos)
            install_python_macos
            ;;
        *)
            log_error "Неподдерживаемая ОС: $os"
            return 1
            ;;
    esac

    # Повторная проверка после установки
    if check_python_version; then
        return 0
    else
        log_error "Установка Python не удалась или Python не найден после установки"
        return 1
    fi
}

# ============================================
# Установить pip если не установлен
# ============================================
ensure_pip() {
    log_step "Проверка pip..."

    local python_cmd=$(get_python_executable)

    # Проверить наличие pip
    if $python_cmd -m pip --version &> /dev/null; then
        log_success "pip уже установлен"
        return 0
    fi

    log_info "pip не найден, установка..."

    # Попытка использовать ensurepip
    if $python_cmd -m ensurepip --upgrade &> /dev/null; then
        log_success "pip установлен через ensurepip"
        return 0
    fi

    # Fallback: установка pip через get-pip.py
    log_info "Установка pip через get-pip.py..."

    local tmp_pip="/tmp/get-pip.py"
    if curl -fsSL https://bootstrap.pypa.io/get-pip.py -o "$tmp_pip"; then
        $python_cmd "$tmp_pip" --user && {
            rm -f "$tmp_pip"
            log_success "pip установлен"
            return 0
        }
    fi

    log_error "Не удалось установить pip"
    return 1
}

# ============================================
# Экспорт функций
# ============================================
export -f get_python_version
export -f check_python_version_sufficient
export -f check_python_version
export -f get_python_executable
export -f install_python_ubuntu
export -f install_python_fedora
export -f install_python_arch
export -f install_python_macos
export -f install_python
export -f ensure_pip
