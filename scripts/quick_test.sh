#!/bin/bash

# Скрипт для быстрого запуска тестов
# Использование: ./scripts/quick_test.sh [unit|instrumentation|all] [--eco|--fast]
# По умолчанию используется сбалансированный режим (параллельное выполнение, config-cache)

set -e

# Цвета для вывода
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Функция для вывода сообщений
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

log_step() {
    echo -e "${BLUE}[STEP]${NC} $1"
}

# Сбалансированный режим по умолчанию
export GRADLE_MODE=balanced

# Парсинг аргументов
TEST_TYPE="all"
MODE="balanced"

while [[ $# -gt 0 ]]; do
    case $1 in
        unit|instrumentation|all)
            TEST_TYPE=$1
            shift
            ;;
        --eco)
            MODE="eco"
            export GRADLE_MODE=eco
            shift
            ;;
        --fast)
            MODE="fast"
            export GRADLE_MODE=fast
            shift
            ;;
        *)
            log_error "Неизвестный аргумент: $1"
            echo ""
            echo "Использование: $0 [unit|instrumentation|all] [--eco|--fast]"
            echo ""
            echo "  unit              - Запуск только unit тестов"
            echo "  instrumentation   - Запуск только instrumentation тестов (с автозапуском эмулятора)"
            echo "  all               - Запуск всех тестов (unit + instrumentation)"
            echo ""
            echo "  --eco             - Экономичный режим (минимальная нагрузка на CPU)"
            echo "  --fast            - Быстрый режим (максимальная производительность)"
            echo ""
            echo "💡 По умолчанию: сбалансированный режим (параллельное выполнение, config-cache)"
            echo ""
            exit 1
            ;;
    esac
done

case $MODE in
    balanced)
        log_info "⚡ Запуск тестов в СБАЛАНСИРОВАННОМ режиме"
        log_info "💡 Параллельное выполнение, config-cache, умеренная нагрузка на CPU"
        ;;
    eco)
        log_info "🌱 Запуск тестов в ЭКОНОМИЧНОМ режиме"
        log_info "💡 Низкая нагрузка на CPU, последовательное выполнение тестов"
        ;;
    fast)
        log_info "🚀 Запуск тестов в БЫСТРОМ режиме"
        log_info "💡 Максимальная параллельность, высокая нагрузка на CPU"
        ;;
esac
echo ""

case $TEST_TYPE in
    unit)
        log_info "Запуск Unit тестов..."
        log_step "Выполнение: ./gradlew testDebugUnitTest"
        cd "$(dirname "$0")/.."
        ./gradlew testDebugUnitTest
        ;;
    instrumentation)
        log_info "Запуск Instrumentation тестов..."
        log_step "Проверка устройства и запуск эмулятора при необходимости..."
        cd "$(dirname "$0")/.."
        ./scripts/check_device.sh --start-emulator
        log_step "Выполнение: ./gradlew connectedDebugAndroidTest"
        ./gradlew connectedDebugAndroidTest
        ;;
    all)
        log_info "Запуск всех тестов..."
        log_step "Выполнение: ./scripts/run_all_tests.sh --start-emulator"
        cd "$(dirname "$0")/.."
        ./scripts/run_all_tests.sh --start-emulator
        ;;
esac

log_info "✅ Тесты завершены успешно"
