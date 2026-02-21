#!/bin/bash

###############################################################################
# Скрипт для запуска instrumentation тестов (UI тесты)
###############################################################################

set -e  # Выход при ошибке

# Цвета для вывода
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Функция для вывода цветных сообщений
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Проверка подключения устройства
check_device() {
    log_info "Проверка подключения устройства..."

    if ! adb devices | grep -v "List" | grep -q "device$"; then
        log_error "Устройство не подключено или не авторизовано"
        log_info "Пожалуйста, подключите устройство или запустите эмулятор"
        log_info "Для запуска эмулятора используйте: ./scripts/start_emulator.sh"
        exit 1
    fi

    # Получаем информацию об устройстве
    DEVICE_MODEL=$(adb shell getprop ro.product.model 2>/dev/null || echo "Unknown")
    ANDROID_VERSION=$(adb shell getprop ro.build.version.release 2>/dev/null || echo "Unknown")
    API_LEVEL=$(adb shell getprop ro.build.version.sdk 2>/dev/null || echo "Unknown")

    log_info "Устройство: $DEVICE_MODEL"
    log_info "Android: $ANDROID_VERSION (API $API_LEVEL)"

    # Проверка минимального API level
    if [ "$API_LEVEL" -lt 29 ]; then
        log_error "Минимальная версия API - 29 (Android 10)"
        exit 1
    fi
}

# Функция для запуска instrumentation тестов
run_instrumentation_tests() {
    log_info "Запуск instrumentation тестов..."

    # Запускаем instrumentation тесты с включенным coverage
    ./gradlew connectedDebugAndroidTest \
        -Pandroid.testInstrumentationRunnerArguments.coverage=true \
        -Pandroid.testInstrumentationRunnerArguments.jacoco.enabled=true

    if [ $? -eq 0 ]; then
        log_info "Instrumentation тесты выполнены успешно!"
    else
        log_error "Instrumentation тесты завершились с ошибками"
        return 1
    fi
}

# Функция для генерации отчета о покрытии
generate_coverage_report() {
    log_info "Генерация отчета о покрытии..."

    ./gradlew jacocoCombinedTestReport

    if [ $? -eq 0 ]; then
        log_info "Отчет о покрытии сгенерирован:"
        echo "  - HTML: app/build/reports/jacoco/jacocoCombinedTestReport/html/index.html"
        echo "  - XML:  app/build/reports/jacoco/jacocoCombinedTestReport/jacocoCombinedTestReport.xml"
    else
        log_warn "Не удалось сгенерировать отчет о покрытии"
    fi
}

# Главная функция (не вызывается напрямую, используется для документации)
# Фактическое выполнение происходит в конце скрипта после обработки аргументов
main() {
    echo "=================================="
    echo "Запуск instrumentation тестов"
    echo "=================================="
    echo ""
}

# Обработка аргументов командной строки
SKIP_DEVICE_CHECK=false
SKIP_TESTS=false
GENERATE_COVERAGE=true

while [[ $# -gt 0 ]]; do
    case $1 in
        --skip-device-check)
            SKIP_DEVICE_CHECK=true
            shift
            ;;
        --skip-tests)
            SKIP_TESTS=true
            shift
            ;;
        --no-coverage)
            GENERATE_COVERAGE=false
            shift
            ;;
        --help)
            echo "Использование: $0 [OPTIONS]"
            echo ""
            echo "Опции:"
            echo "  --skip-device-check    Пропустить проверку устройства"
            echo "  --skip-tests           Пропустить запуск тестов"
            echo "  --no-coverage          Не генерировать отчет о покрытии"
            echo "  --help                 Показать эту справку"
            exit 0
            ;;
        *)
            log_error "Неизвестный аргумент: $1"
            echo "Используйте --help для справки"
            exit 1
            ;;
    esac
done

# Выполнение
if [ "$SKIP_DEVICE_CHECK" = false ]; then
    check_device
fi

if [ "$SKIP_TESTS" = false ]; then
    run_instrumentation_tests
fi

if [ "$GENERATE_COVERAGE" = true ]; then
    generate_coverage_report
fi
