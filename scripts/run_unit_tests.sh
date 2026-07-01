#!/bin/bash

# Скрипт быстрого запуска только Unit тестов
# Используется для быстрой проверки во время разработки
# Использование: ./scripts/run_unit_tests.sh [-c|--continuous] [-v|--verbose] [--eco|--fast]

set -e

echo ""
echo "🔬 Запуск Unit тестов CompressPhotoFast"
echo "========================================"
echo ""

# Перейти в директорию проекта
cd "$(dirname "$0")/.."

# Перейти в директорию проекта
CONTINUOUS_MODE=false
VERBOSE=false

while [[ $# -gt 0 ]]; do
    case $1 in
        -c|--continuous)
            CONTINUOUS_MODE=true
            shift
            ;;
        -v|--verbose)
            VERBOSE=true
            shift
            ;;
        --eco)
            export GRADLE_MODE=eco
            shift
            ;;
        --fast)
            export GRADLE_MODE=fast
            shift
            ;;
        *)
            echo "Использование: $0 [-c|--continuous] [-v|--verbose] [--eco|--fast]"
            echo ""
            echo "  -c, --continuous  Непрерывный режим (автозапуск при изменениях)"
            echo "  -v, --verbose     Подробный вывод"
            echo "  --eco             Экономичный режим (минимальная нагрузка на CPU)"
            echo "  --fast            Быстрый режим (максимальная производительность)"
            exit 1
            ;;
    esac
done

# Показать текущий режим
MODE="${GRADLE_MODE:-balanced}"
case $MODE in
    balanced) echo "⚡ Сбалансированный режим (параллельное выполнение)";;
    eco)      echo "🌱 Экономичный режим (последовательное выполнение)";;
    fast)     echo "🚀 Быстрый режим (максимальная параллельность)";;
esac
echo ""

# Формирование команды Gradle
GRADLE_CMD="./gradlew testDebugUnitTest"

if [ "$VERBOSE" = true ]; then
    GRADLE_CMD="$GRADLE_CMD --info --stacktrace"
fi

if [ "$CONTINUOUS_MODE" = true ]; then
    GRADLE_CMD="$GRADLE_CMD --continuous"
    echo "🔄 Непрерывный режим: тесты будут перезапускаться при изменениях"
    echo ""
fi

# Запуск тестов
echo "⏱️  Запуск..."
echo ""

if $GRADLE_CMD; then
    echo ""
    echo "========================================"
    echo "✅ Unit тесты пройдены успешно!"
    echo ""

    # Показать статистику если не continuous режим
    if [ "$CONTINUOUS_MODE" = false ]; then
        echo "📊 Для просмотра coverage отчёта:"
        echo "   ./gradlew jacocoTestReport"
        echo "   xdg-open app/build/reports/jacoco/jacocoTestReport/html/index.html"
        echo ""
    fi

    exit 0
else
    echo ""
    echo "========================================"
    echo "❌ Unit тесты не прошли!"
    echo ""
    echo "Для деталей запустите с флагом --verbose:"
    echo "   $0 --verbose"
    echo ""
    exit 1
fi
