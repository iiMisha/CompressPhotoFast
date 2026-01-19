#!/bin/bash

# Скрипт запуска E2E тестов CompressPhotoFast
# Выполняет end-to-end тестирование на эмуляторе или реальном устройстве
# Использование: ./scripts/run_e2e_tests.sh [опции]

set -e

# Цвета для вывода
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
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

log_category() {
    echo -e "${CYAN}[CATEGORY]${NC} $1"
}

# Парсинг аргументов
START_EMULATOR=false
SKIP_DEVICE_CHECK=false
CATEGORY=""
CLEAN_BUILD=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --start-emulator)
            START_EMULATOR=true
            shift
            ;;
        --skip-device-check)
            SKIP_DEVICE_CHECK=true
            shift
            ;;
        --category)
            CATEGORY="$2"
            shift 2
            ;;
        --clean)
            CLEAN_BUILD=true
            shift
            ;;
        --help)
            echo "Использование: $0 [опции]"
            echo ""
            echo "Опции:"
            echo "  --start-emulator        Автоматически запустить эмулятор, если устройство не найдено"
            echo "  --skip-device-check     Пропустить проверку устройства"
            echo "  --category CAT          Запустить только определенную категорию тестов"
            echo "                          Доступные категории:"
            echo "                            - manualcompression: Ручное сжатие"
            echo "                            - batchcompression: Пакетное сжатие"
            echo "                            - autocompression: Автоматическое сжатие"
            echo "                            - shareintent: Обработка через 'Поделиться'"
            echo "                            - settings: Изменение настроек"
            echo "  --clean                 Очистить кэш перед сборкой"
            echo "  --help                  Показать эту справку"
            echo ""
            exit 0
            ;;
        *)
            log_error "Неизвестный аргумент: $1"
            echo "Используйте --help для справки"
            exit 1
            ;;
    esac
done

# Перейти в директорию проекта
cd "$(dirname "$0")/.."

# Начало тестирования
echo ""
echo "======================================"
echo "🎯 E2E тесты CompressPhotoFast"
echo "======================================"
echo ""

# 1. Проверка устройства
if [ "$SKIP_DEVICE_CHECK" = false ]; then
    log_step "Проверка устройства..."
    if [ "$START_EMULATOR" = true ]; then
        ./scripts/check_device.sh --start-emulator
    else
        ./scripts/check_device.sh
    fi

    if [ $? -ne 0 ]; then
        echo ""
        log_error "Устройство не найдено!"
        echo "   Используйте --start-emulator для автоматического запуска эмулятора"
        exit 1
    fi
    log_info "Устройство подключено"
    echo ""
else
    log_warn "Проверка устройства пропущена"
    echo ""
fi

# 2. Определение тестовых классов на основе категории
if [ -n "$CATEGORY" ]; then
    log_category "Запуск категории: $CATEGORY"

    case "$CATEGORY" in
        manualcompression)
            TEST_CLASS="com.compressphotofast.e2e.manualcompression.*"
            ;;
        batchcompression)
            TEST_CLASS="com.compressphotofast.e2e.batchcompression.*"
            ;;
        autocompression)
            TEST_CLASS="com.compressphotofast.e2e.autocompression.*"
            ;;
        shareintent)
            TEST_CLASS="com.compressphotofast.e2e.shareintent.*"
            ;;
        settings)
            TEST_CLASS="com.compressphotofast.e2e.settings.*"
            ;;
        *)
            log_error "Неизвестная категория: $CATEGORY"
            echo "Доступные категории:"
            echo "  - manualcompression"
            echo "  - batchcompression"
            echo "  - autocompression"
            echo "  - shareintent"
            echo "  - settings"
            exit 1
            ;;
    esac
else
    log_info "Запуск всех E2E тестов"
    TEST_CLASS="com.compressphotofast.e2e.**"
fi

echo ""
log_step "Сборка тестов..."
BUILD_OPTIONS=""
if [ "$CLEAN_BUILD" = true ]; then
    BUILD_OPTIONS="clean"
    log_info "Очистка кэша сборки..."
fi

./gradlew $BUILD_OPTIONS assembleDebug assembleAndroidTest --stacktrace

if [ $? -ne 0 ]; then
    log_error "Сборка не удалась!"
    exit 1
fi

log_info "Сборка завершена"
echo ""

# 3. Запуск E2E тестов
log_step "Запуск E2E тестов на устройстве..."
echo "   ⏱️  Это может занять 5-15 минут..."

GRADLE_TASK="./gradlew connectedDebugAndroidTest --stacktrace"

# Добавляем фильтрацию по классу если указана категория
if [ -n "$TEST_CLASS" ]; then
    GRADLE_TASK="./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=$TEST_CLASS --stacktrace"
fi

$GRADLE_TASK

if [ $? -ne 0 ]; then
    echo ""
    log_error "E2E тесты не прошли!"
    echo "   Проверьте вывод выше для деталей"
    echo ""
    echo "📊 Отчеты:"
    echo "   HTML: app/build/reports/androidTests/connected/index.html"
    exit 1
fi

log_info "E2E тесты пройдены успешно!"
echo ""

# 4. Отчеты
log_step "Генерация отчетов..."
echo ""
echo "📊 Отчеты:"
echo "   HTML: app/build/reports/androidTests/connected/index.html"
echo "   XML:  app/build/test-results/connected/"
echo ""
echo "   Для открытия HTML отчета:"
echo "   xdg-open app/build/reports/androidTests/connected/index.html"
echo ""

# 5. Статистика
echo "======================================"
log_info "E2E тесты завершены успешно!"
echo ""
echo "📈 Статистика запуска:"
if [ -n "$CATEGORY" ]; then
    echo "   • Категория: $CATEGORY"
else
    echo "   • Запущены все E2E тесты"
fi
echo "   • Устройство: подключено"
echo "   • Отчеты: сгенерированы"
echo ""

exit 0
