#!/bin/bash
# CompressPhotoFast CLI - Installer for Linux/macOS
# Автоматическая установка с детекцией Python и зависимостей

set -e

# ============================================
# Конфигурация
# ============================================
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
VENV_DIR="$PROJECT_DIR/venv"
INSTALL_LOG=""

# ============================================
# Подключение модулей
# ============================================
source "$SCRIPT_DIR/install_common.sh"
source "$SCRIPT_DIR/detect_python.sh"
source "$SCRIPT_DIR/install_deps.sh"

# ============================================
# Создание виртуального окружения
# ============================================
create_virtual_environment() {
    log_step "Создание виртуального окружения..."

    # Если venv уже существует, спрашиваем о переустановке
    if [ -d "$VENV_DIR" ]; then
        log_warning "Виртуальное окружение уже существует: $VENV_DIR"
        read -p "Удалить и создать заново? (y/N) " -n 1 -r
        echo
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            log_info "Удаление старого виртуального окружения..."
            rm -rf "$VENV_DIR"
        else
            log_info "Использование существующего виртуального окружения"
            return 0
        fi
    fi

    # Создаем venv
    local python_cmd=$(get_python_executable)
    $python_cmd -m venv "$VENV_DIR" || {
        log_error "Не удалось создать виртуальное окружение"
        return 1
    }

    log_success "Виртуальное окружение создано"
}

# ============================================
# Установка пакета с HEIC поддержкой
# ============================================
install_package_with_heic() {
    log_step "Установка CompressPhotoFast CLI с HEIC поддержкой..."

    local pip_cmd="$VENV_DIR/bin/pip"

    # Обновляем pip
    log_info "Обновление pip..."
    $pip_cmd install --upgrade pip -q

    # Устанавливаем пакет
    log_info "Установка зависимостей..."
    $pip_cmd install -e "$PROJECT_DIR" -q || {
        log_error "Не удалось установить пакет"
        return 1
    }

    log_success "Пакет установлен"
}

# ============================================
# Проверка установки
# ============================================
verify_installation() {
    log_step "Проверка установки..."

    local cli_cmd="$VENV_DIR/bin/python -m src.cli"

    # Проверяем версию
    if $VENV_DIR/bin/python -m src.cli version &> /dev/null; then
        local version=$($VENV_DIR/bin/python -m src.cli version 2>&1 | head -n1)
        log_success "CompressPhotoFast CLI $version готов к работе!"
    else
        log_warning "Не удалось проверить версию (это может быть нормально)"
    fi
}

# ============================================
# Создание симлинка (опционально)
# ============================================
create_symlink_optional() {
    local symlink_path="/usr/local/bin/compressphotofast"

    # Проверяем, есть ли sudo
    if ! has_sudo; then
        log_info "Пропуск создания симлинка (нет прав sudo)"
        log_info "Используйте ./compressphotofast.sh для запуска"
        return 0
    fi

    # Если симлинк уже существует, пропускаем
    if [ -L "$symlink_path" ]; then
        log_info "Симлинк уже существует: $symlink_path"
        return 0
    fi

    # Предлагаем создать симлинк
    echo
    read -p "Создать симлинк $symlink_path для глобального доступа? (y/N) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        sudo ln -sf "$PROJECT_DIR/compressphotofast.sh" "$symlink_path" && {
            log_success "Симлинк создан: $symlink_path"
        }
    else
        log_info "Используйте ./compressphotofast.sh для запуска"
    fi
}

# ============================================
# Показать сообщение об успешной установке
# ============================================
show_success_message() {
    echo
    echo -e "${GREEN}╔══════════════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║  Installation Complete!                                  ║${NC}"
    echo -e "${GREEN}╠══════════════════════════════════════════════════════════╣${NC}"
    echo -e "${GREEN}║  To use CompressPhotoFast CLI:                          ║${NC}"
    echo -e "${GREEN}║    cd $PROJECT_DIR${NC}"
    echo -e "${GREEN}║    source venv/bin/activate                              ║${NC}"
    echo -e "${GREEN}║    compressphotofast --help                              ║${NC}"
    echo -e "${GREEN}║                                                          ║${NC}"
    echo -e "${GREEN}║  Or use the launcher script:                             ║${NC}"
    echo -e "${GREEN}║    ./compressphotofast.sh --help                         ║${NC}"
    echo -e "${GREEN}╚══════════════════════════════════════════════════════════╝${NC}"
    echo
    log_info "HEIC/HEIF support: ${GREEN}ENABLED${NC}"
    if [ -n "$INSTALL_LOG" ]; then
        log_info "Installation log: $INSTALL_LOG"
    fi
    echo
}

# ============================================
# Главная функция
# ============================================
main() {
    # Настраиваем логирование
    setup_logging

    # Показываем баннер
    print_banner

    # Проверяем интернет
    if ! check_internet; then
        log_warning "Нет интернет-соединения. Установка может не удасться."
        read -p "Продолжить? (y/N) " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            exit 1
        fi
    fi

    # Определяем ОС
    local os=$(detect_os)
    local distro=""
    if [ "$os" = "linux" ]; then
        distro=$(detect_distro)
        log_success "ОС определена: $distro"
    elif [ "$os" = "macos" ]; then
        log_success "ОС определена: macOS"
    else
        log_error "Неподдерживаемая ОС"
        exit 1
    fi

    # Проверяем Python
    if ! check_python_version; then
        if ! install_python; then
            log_error "Не удалось установить Python"
            log_info "Пожалуйста, установите Python 3.10+ вручную и запустите установщик снова"
            exit 1
        fi
    fi

    # Убеждаемся, что pip установлен
    if ! ensure_pip; then
        log_error "Не удалось установить pip"
        exit 1
    fi

    # Проверяем и устанавливаем системные зависимости
    if ! check_system_dependencies; then
        if ! install_system_dependencies; then
            # Если не удалось установить с sudo, предлагаем вариант без него
            if ! has_sudo; then
                if ! install_without_sudo; then
                    exit 1
                fi
            else
                log_error "Не удалось установить системные зависимости"
                exit 1
            fi
        fi
    fi

    # Создаем виртуальное окружение
    if ! create_virtual_environment; then
        log_error "Не удалось создать виртуальное окружение"
        exit 1
    fi

    # Устанавливаем пакет
    if ! install_package_with_heic; then
        log_error "Не удалось установить пакет"
        exit 1
    fi

    # Проверяем установку
    verify_installation

    # Предлагаем создать симлинк
    create_symlink_optional

    # Показываем сообщение об успехе
    show_success_message
}

# ============================================
# Запуск
# ============================================
main "$@"
