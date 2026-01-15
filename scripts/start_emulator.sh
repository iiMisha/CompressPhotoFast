#!/bin/bash

# Скрипт для автоматического запуска эмулятора Android
# Использование: ./scripts/start_emulator.sh [emulator_name]

set -e

# Цвета для вывода
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
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

# Проверка наличия Android SDK
if [ -z "$ANDROID_HOME" ]; then
    log_error "ANDROID_HOME не установлен"
    log_error "Установите переменную окружения ANDROID_HOME"
    exit 1
fi

log_info "ANDROID_HOME: $ANDROID_HOME"

# Проверка наличия эмулятора
EMULATOR="$ANDROID_HOME/emulator/emulator"

if [ ! -f "$EMULATOR" ]; then
    log_error "Эмулятор не найден по пути: $EMULATOR"
    exit 1
fi

# Получение списка доступных эмуляторов
log_info "Доступные эмуляторы:"
$EMULATOR -list-avds

# Если имя эмулятора не указано, используем первый доступный
if [ -z "$1" ]; then
    EMULATOR_NAME=$($EMULATOR -list-avds | head -n 1)
    if [ -z "$EMULATOR_NAME" ]; then
        log_error "Нет доступных эмуляторов"
        log_error "Создайте эмулятор через Android Studio или AVD Manager"
        exit 1
    fi
    log_warn "Имя эмулятора не указано, используется: $EMULATOR_NAME"
else
    EMULATOR_NAME="$1"
fi

log_info "Запуск эмулятора: $EMULATOR_NAME"

# Запуск эмулятора в фоновом режиме
nohup $EMULATOR -avd "$EMULATOR_NAME" -no-snapshot-load > /dev/null 2>&1 &

EMULATOR_PID=$!
log_info "Эмулятор запущен с PID: $EMULATOR_PID"

# Ожидание готовности эмулятора
log_info "Ожидание готовности эмулятора..."
MAX_WAIT=120  # Максимальное время ожидания в секундах
WAIT_TIME=0

while [ $WAIT_TIME -lt $MAX_WAIT ]; do
    if adb shell getprop sys.boot_completed 2>/dev/null | grep -q "1"; then
        log_info "Эмулятор готов!"
        break
    fi
    sleep 2
    WAIT_TIME=$((WAIT_TIME + 2))
    echo -n "."
done

echo ""

if [ $WAIT_TIME -ge $MAX_WAIT ]; then
    log_error "Эмулятор не запустился за $MAX_WAIT секунд"
    exit 1
fi

# Проверка подключения устройства
log_info "Проверка подключения устройства..."
adb devices

log_info "Эмулятор успешно запущен и готов к работе"
