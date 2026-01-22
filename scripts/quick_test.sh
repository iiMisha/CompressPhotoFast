#!/bin/bash

# Скрипт для быстрого запуска тестов в ЭКОНОМИЧНОМ РЕЖИМЕ
# Использование: ./scripts/quick_test.sh [unit|instrumentation|all]
# По умолчанию используется эко режим (низкая нагрузка на CPU)

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

# Эко режим по умолчанию
export GRADLE_MODE=eco

log_info "🌱 Запуск тестов в ЭКОНОМИЧНОМ режиме"
log_info "💡 Низкая нагрузка на CPU, последовательное выполнение тестов"
echo ""

# Проверка аргументов
TEST_TYPE="${1:-all}"

case $TEST_TYPE in
    unit)
        log_info "Запуск Unit тестов..."
        log_step "Выполнение: ./gradlew testDebugUnitTest"
        ./gradlew testDebugUnitTest
        ;;
    instrumentation)
        log_info "Запуск Instrumentation тестов..."
        log_step "Проверка устройства и запуск эмулятора при необходимости..."
        ./scripts/check_device.sh --start-emulator
        log_step "Выполнение: ./gradlew connectedDebugAndroidTest"
        ./gradlew connectedDebugAndroidTest
        ;;
    all)
        log_info "Запуск всех тестов..."
        log_step "Выполнение: ./scripts/run_all_tests.sh --start-emulator"
        ./scripts/run_all_tests.sh --start-emulator
        ;;
    *)
        log_error "Неизвестный тип тестов: $TEST_TYPE"
        echo ""
        echo "Использование: $0 [unit|instrumentation|all]"
        echo ""
        echo "  unit          - Запуск только unit тестов"
        echo "  instrumentation - Запуск только instrumentation тестов (с автозапуском эмулятора)"
        echo "  all           - Запуск всех тестов (unit + instrumentation + coverage)"
        echo ""
        echo "💡 По умолчанию используется ЭКОНОМИЧНЫЙ режим (низкая нагрузка на CPU)"
        echo "   Для быстрого режима используйте: GRADLE_MODE=fast $0 [тип]"
        echo ""
        exit 1
        ;;
esac

log_info "✅ Тесты завершены успешно"
