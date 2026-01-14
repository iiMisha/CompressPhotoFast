#!/bin/bash

# Скрипт запуска всех тестов (Unit + Instrumentation) с coverage
# Выполняет полную проверку перед изменением кода

set -e

echo ""
echo "🧪 Запуск всех тестов CompressPhotoFast"
echo "======================================"
echo ""

# Перейти в директорию проекта
cd "$(dirname "$0")/.."

# 1. Проверка устройства
echo "1️⃣  Проверка устройства..."
./scripts/check_device.sh
if [ $? -ne 0 ]; then
    echo ""
    echo "❌ Устройство не найдено. Instrumentation тесты пропущены."
    echo "   Запуск только Unit тестов..."
    SKIP_INSTRUMENTATION=true
else
    SKIP_INSTRUMENTATION=false
fi
echo ""

# 2. Unit тесты
echo "2️⃣  Запуск Unit тестов..."
echo "   ⏱️  Это может занять 30-60 секунд..."
./gradlew testDebugUnitTest --stacktrace

if [ $? -ne 0 ]; then
    echo ""
    echo "❌ Unit тесты не прошли!"
    echo "   Проверьте вывод выше для деталей"
    exit 1
fi
echo "   ✅ Unit тесты пройдены"
echo ""

# 3. Instrumentation тесты (если устройство подключено)
if [ "$SKIP_INSTRUMENTATION" = false ]; then
    echo "3️⃣  Запуск Instrumentation тестов на устройстве..."
    echo "   ⏱️  Это может занять 3-5 минут..."
    ./gradlew connectedDebugAndroidTest --stacktrace

    if [ $? -ne 0 ]; then
        echo ""
        echo "❌ Instrumentation тесты не прошли!"
        echo "   Проверьте вывод выше для деталей"
        exit 1
    fi
    echo "   ✅ Instrumentation тесты пройдены"
    echo ""
fi

# 4. Coverage отчет
echo "4️⃣  Генерация Coverage отчета..."
./gradlew jacocoTestReport --quiet

if [ $? -eq 0 ]; then
    echo "   ✅ Coverage отчет сгенерирован"
    echo ""
    echo "📊 Coverage отчет:"
    echo "   📁 app/build/reports/jacoco/jacocoTestReport/html/index.html"
    echo ""
    echo "   Для открытия:"
    echo "   xdg-open app/build/reports/jacoco/jacocoTestReport/html/index.html"
else
    echo "   ⚠️  Не удалось сгенерировать coverage отчет"
    echo "   Но тесты прошли успешно!"
fi

# Итог
echo ""
echo "======================================"
echo "✅ Все тесты завершены успешно!"
echo ""
echo "📈 Статистика:"
echo "   • Unit тесты: пройдены"
if [ "$SKIP_INSTRUMENTATION" = false ]; then
    echo "   • Instrumentation тесты: пройдены"
else
    echo "   • Instrumentation тесты: пропущены (нет устройства)"
fi
echo "   • Coverage: сгенерирован"
echo ""

exit 0
