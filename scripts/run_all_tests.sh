#!/bin/bash

# Скрипт запуска всех тестов (Unit + Instrumentation) с coverage
# Выполняет полную проверку перед изменением кода
# Использование: ./scripts/run_all_tests.sh [--start-emulator] [--skip-unit] [--skip-instrumentation]

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

# Парсинг аргументов
START_EMULATOR=false
SKIP_UNIT=false
SKIP_INSTRUMENTATION=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --start-emulator)
            START_EMULATOR=true
            shift
            ;;
        --skip-unit)
            SKIP_UNIT=true
            shift
            ;;
        --skip-instrumentation)
            SKIP_INSTRUMENTATION=true
            shift
            ;;
        *)
            log_error "Неизвестный аргумент: $1"
            echo ""
            echo "Использование: $0 [опции]"
            echo ""
            echo "Опции:"
            echo "  --start-emulator        Автоматически запустить эмулятор, если устройство не найдено"
            echo "  --skip-unit             Пропустить unit тесты"
            echo "  --skip-instrumentation  Пропустить instrumentation тесты"
            echo ""
            exit 1
            ;;
    esac
done

# Перейти в директорию проекта
cd "$(dirname "$0")/.."

# Начало тестирования
echo ""
echo "======================================"
echo "🧪 Запуск всех тестов CompressPhotoFast"
echo "======================================"
echo ""

# 1. Проверка устройства
if [ "$SKIP_INSTRUMENTATION" = false ]; then
    log_step "Проверка устройства..."
    if [ "$START_EMULATOR" = true ]; then
        ./scripts/check_device.sh --start-emulator
    else
        ./scripts/check_device.sh
    fi
    
    if [ $? -ne 0 ]; then
        echo ""
        log_warn "Устройство не найдено. Instrumentation тесты пропущены."
        echo "   Используйте --start-emulator для автоматического запуска эмулятора"
        SKIP_INSTRUMENTATION=true
    fi
    echo ""
fi

# 2. Unit тесты
if [ "$SKIP_UNIT" = false ]; then
    log_step "Запуск Unit тестов..."
    echo "   ⏱️  Это может занять 30-60 секунд..."
    ./gradlew testDebugUnitTest --stacktrace

    if [ $? -ne 0 ]; then
        echo ""
        log_error "Unit тесты не прошли!"
        echo "   Проверьте вывод выше для деталей"
        exit 1
    fi
    log_info "Unit тесты пройдены"
    echo ""
else
    log_warn "Unit тесты пропущены"
    echo ""
fi

# 3. Instrumentation тесты (если устройство подключено)
if [ "$SKIP_INSTRUMENTATION" = false ]; then
    log_step "Запуск Instrumentation тестов на устройстве..."
    echo "   ⏱️  Это может занять 3-5 минут..."
    ./gradlew connectedDebugAndroidTest --stacktrace

    if [ $? -ne 0 ]; then
        echo ""
        log_error "Instrumentation тесты не прошли!"
        echo "   Проверьте вывод выше для деталей"
        exit 1
    fi
    log_info "Instrumentation тесты пройдены"
    echo ""
else
    log_warn "Instrumentation тесты пропущены"
    echo ""
fi

# 4. Coverage отчет
log_step "Генерация объединенного Coverage отчета..."
./gradlew jacocoCombinedTestReport --quiet

if [ $? -eq 0 ]; then
    log_info "Coverage отчет сгенерирован"
    echo ""
    echo "📊 Coverage отчет:"
    echo "   📁 app/build/reports/jacoco/jacocoCombinedTestReport/html/index.html"
    echo ""
    echo "   Для открытия:"
    echo "   xdg-open app/build/reports/jacoco/jacocoCombinedTestReport/html/index.html"
else
    log_warn "Не удалось сгенерировать coverage отчет"
    echo "   Но тесты прошли успешно!"
fi

# Итог
echo ""
echo "======================================"
log_info "Все тесты завершены успешно!"
echo ""
echo "📈 Статистика:"
if [ "$SKIP_UNIT" = false ]; then
    echo "   • Unit тесты: пройдены"
else
    echo "   • Unit тесты: пропущены"
fi

if [ "$SKIP_INSTRUMENTATION" = false ]; then
    echo "   • Instrumentation тесты: пройдены"
else
    echo "   • Instrumentation тесты: пропущены"
fi
echo "   • Coverage: сгенерирован"
echo ""

exit 0
