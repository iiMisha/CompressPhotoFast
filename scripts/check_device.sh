#!/bin/bash

# Скрипт проверки подключения устройства или эмулятора
# Используется перед запуском instrumentation тестов
# Использование: ./scripts/check_device.sh [--start-emulator]

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

# Проверка аргументов
START_EMULATOR=false
if [ "$1" == "--start-emulator" ]; then
    START_EMULATOR=true
fi

echo "🔍 Проверка подключения Android устройства..."

# Проверка ADB
if ! command -v adb &> /dev/null; then
    log_error "ADB не найден. Установите Android SDK."
    exit 1
fi

# Проверка подключенных устройств
DEVICE_COUNT=$(adb devices | grep -c "device$" || true)

if [ "$DEVICE_COUNT" -eq 0 ]; then
    log_warn "Нет подключенных устройств или эмуляторов"
    echo ""
    
    if [ "$START_EMULATOR" = true ]; then
        log_info "Попытка запуска эмулятора..."
        ./scripts/start_emulator.sh
        
        # Повторная проверка после запуска эмулятора
        DEVICE_COUNT=$(adb devices | grep -c "device$" || true)
        if [ "$DEVICE_COUNT" -eq 0 ]; then
            log_error "Не удалось запустить эмулятор"
            exit 1
        fi
    else
        log_error "Устройство не подключено"
        echo ""
        echo "Возможные решения:"
        echo "  1. Запустите эмулятор Android"
        echo "  2. Подключите физическое устройство с включенным USB-отладкой"
        echo "  3. Используйте --start-emulator для автоматического запуска эмулятора"
        echo "  4. Проверьте 'adb devices' для диагностики"
        exit 1
    fi
fi

# Проверка на несколько устройств
if [ "$DEVICE_COUNT" -gt 1 ]; then
    log_warn "Обнаружено несколько устройств ($DEVICE_COUNT)"
    echo ""
    echo "Подключенные устройства:"
    adb devices
    echo ""
    echo "Используйте 'adb -s <device_id>' для выбора конкретного устройства"
fi

# Информация о первом устройстве
DEVICE_ID=$(adb devices | grep "device$" | head -n 1 | awk '{print $1}')
log_info "Устройство подключено: $DEVICE_ID"

# Показать информацию об устройстве
ANDROID_VERSION=$(adb -s "$DEVICE_ID" shell getprop ro.build.version.release)
API_LEVEL=$(adb -s "$DEVICE_ID" shell getprop ro.build.version.sdk)
DEVICE_MODEL=$(adb -s "$DEVICE_ID" shell getprop ro.product.model)

echo "   📱 Модель: $DEVICE_MODEL"
echo "   🤖 Android: $ANDROID_VERSION (API $API_LEVEL)"

# Проверка API level
if [ "$API_LEVEL" -lt 29 ]; then
    log_warn "Предупреждение: минимальная версия API 29 (Android 10)"
    echo "   Текущее устройство: API $API_LEVEL"
fi

exit 0
