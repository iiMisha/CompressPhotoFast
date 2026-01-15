#!/bin/bash

# Скрипт для генерации тестовых изображений для проекта CompressPhotoFast
# Использует ImageMagick для создания изображений различных размеров и форматов

set -e

# Цвета для вывода
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Путь к директории с тестовыми изображениями
TEST_IMAGES_DIR="app/src/test/resources/test_images"

# Проверка наличия ImageMagick
if ! command -v convert &> /dev/null; then
    echo -e "${RED}❌ ImageMagick не установлен. Установите его с помощью:${NC}"
    echo "   sudo apt-get install imagemagick"
    exit 1
fi

# Создание директории для тестовых изображений
echo -e "${YELLOW}📁 Создание директории для тестовых изображений...${NC}"
mkdir -p "$TEST_IMAGES_DIR"

# Функция для генерации градиентного изображения
generate_gradient_image() {
    local filename=$1
    local width=$2
    local height=$3
    local quality=$4
    local description=$5
    
    echo -e "${YELLOW}🖼️  Генерация: $description${NC}"
    convert -size "${width}x${height}" gradient:blue-red -quality "$quality" "$TEST_IMAGES_DIR/$filename"
    
    # Проверка размера файла
    local filesize=$(stat -f%z "$TEST_IMAGES_DIR/$filename" 2>/dev/null || stat -c%s "$TEST_IMAGES_DIR/$filename" 2>/dev/null)
    local filesize_kb=$((filesize / 1024))
    echo -e "${GREEN}✅ Создан: $filename ($filesize_kb КБ)${NC}"
}

# Функция для генерации изображения с шумом
generate_noise_image() {
    local filename=$1
    local width=$2
    local height=$3
    local quality=$4
    local description=$5
    
    echo -e "${YELLOW}🖼️  Генерация: $description${NC}"
    convert -size "${width}x${height}" xc:white +noise Gaussian -quality "$quality" "$TEST_IMAGES_DIR/$filename"
    
    # Проверка размера файла
    local filesize=$(stat -f%z "$TEST_IMAGES_DIR/$filename" 2>/dev/null || stat -c%s "$TEST_IMAGES_DIR/$filename" 2>/dev/null)
    local filesize_kb=$((filesize / 1024))
    echo -e "${GREEN}✅ Создан: $filename ($filesize_kb КБ)${NC}"
}

# Функция для генерации изображения с EXIF-данными
generate_image_with_exif() {
    local filename=$1
    local width=$2
    local height=$3
    local quality=$4
    local description=$5
    
    echo -e "${YELLOW}🖼️  Генерация: $description${NC}"
    
    # Создаем временное изображение
    local temp_file=$(mktemp)
    convert -size "${width}x${height}" gradient:green-yellow -quality "$quality" "$temp_file"
    
    # Добавляем EXIF-данные с помощью exiftool (если доступен)
    if command -v exiftool &> /dev/null; then
        exiftool -overwrite_original \
            -Make="Test Camera" \
            -Model="Test Model 2024" \
            -DateTimeOriginal="2024:01:15 10:30:00" \
            -CreateDate="2024:01:15 10:30:00" \
            -GPSLatitude="55.7558" \
            -GPSLatitudeRef="N" \
            -GPSLongitude="37.6173" \
            -GPSLongitudeRef="E" \
            -ExposureTime="1/125" \
            -FNumber="2.8" \
            -ISOSpeedRatings="100" \
            -FocalLength="35mm" \
            "$temp_file" > /dev/null 2>&1
        
        echo -e "${GREEN}   📝 EXIF-данные добавлены${NC}"
    else
        echo -e "${YELLOW}   ⚠️  exiftool не установлен, EXIF-данные не добавлены${NC}"
    fi
    
    mv "$temp_file" "$TEST_IMAGES_DIR/$filename"
    
    # Проверка размера файла
    local filesize=$(stat -f%z "$TEST_IMAGES_DIR/$filename" 2>/dev/null || stat -c%s "$TEST_IMAGES_DIR/$filename" 2>/dev/null)
    local filesize_kb=$((filesize / 1024))
    echo -e "${GREEN}✅ Создан: $filename ($filesize_kb КБ)${NC}"
}

# Функция для генерации скриншота
generate_screenshot() {
    local filename=$1
    local width=$2
    local height=$3
    local description=$4
    
    echo -e "${YELLOW}🖼️  Генерация: $description${NC}"
    
    # Создаем изображение, похожее на скриншот (с текстом)
    convert -size "${width}x${height}" xc:#f0f0f0 \
        -font DejaVu-Sans \
        -pointsize 24 \
        -fill black \
        -gravity center \
        -annotate +0+0 "Screenshot Test" \
        "$TEST_IMAGES_DIR/$filename"
    
    # Проверка размера файла
    local filesize=$(stat -f%z "$TEST_IMAGES_DIR/$filename" 2>/dev/null || stat -c%s "$TEST_IMAGES_DIR/$filename" 2>/dev/null)
    local filesize_kb=$((filesize / 1024))
    echo -e "${GREEN}✅ Создан: $filename ($filesize_kb КБ)${NC}"
}

# Функция для генерации HEIC изображения (если поддерживается)
generate_heic_image() {
    local filename=$1
    local width=$2
    local height=$3
    local description=$4
    
    echo -e "${YELLOW}🖼️  Генерация: $description${NC}"
    
    # Проверяем поддержку HEIC
    if convert -list format | grep -q "HEIC"; then
        convert -size "${width}x${height}" gradient:purple-orange "$TEST_IMAGES_DIR/$filename"
        
        # Проверка размера файла
        local filesize=$(stat -f%z "$TEST_IMAGES_DIR/$filename" 2>/dev/null || stat -c%s "$TEST_IMAGES_DIR/$filename" 2>/dev/null)
        local filesize_kb=$((filesize / 1024))
        echo -e "${GREEN}✅ Создан: $filename ($filesize_kb КБ)${NC}"
    else
        echo -e "${YELLOW}⚠️  HEIC формат не поддерживается текущей версией ImageMagick${NC}"
        echo -e "${YELLOW}   Создаем JPEG вместо HEIC${NC}"
        convert -size "${width}x${height}" gradient:purple-orange "$TEST_IMAGES_DIR/test_image_heic.jpg"
        
        # Проверка размера файла
        local filesize=$(stat -f%z "$TEST_IMAGES_DIR/test_image_heic.jpg" 2>/dev/null || stat -c%s "$TEST_IMAGES_DIR/test_image_heic.jpg" 2>/dev/null)
        local filesize_kb=$((filesize / 1024))
        echo -e "${GREEN}✅ Создан: test_image_heic.jpg ($filesize_kb КБ)${NC}"
    fi
}

# Генерация тестовых изображений
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Генерация тестовых изображений${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""

# 1. Маленькое изображение (100x100, ~50 КБ)
generate_gradient_image "test_image_small.jpg" 100 100 85 "Маленькое изображение (100x100)"

# 2. Среднее изображение (800x600, ~200 КБ)
generate_noise_image "test_image_medium.jpg" 800 600 85 "Среднее изображение (800x600)"

# 3. Большое изображение (1920x1080, ~500 КБ)
generate_noise_image "test_image_large.jpg" 1920 1080 85 "Большое изображение (1920x1080)"

# 4. Огромное изображение (4000x3000, ~2 МБ)
generate_noise_image "test_image_huge.jpg" 4000 3000 85 "Огромное изображение (4000x3000)"

# 5. Изображение с EXIF-данными (800x600)
generate_image_with_exif "test_image_with_exif.jpg" 800 600 85 "Изображение с EXIF-данными"

# 6. Скриншот (для тестирования фильтрации)
generate_screenshot "test_image_screenshot.png" 1080 1920 "Скриншот (1080x1920)"

# 7. HEIC изображение (если поддерживается)
generate_heic_image "test_image_heic.heic" 800 600 "HEIC изображение (800x600)"

# Дополнительные изображения для тестирования
echo ""
echo -e "${YELLOW}🖼️  Генерация дополнительных тестовых изображений...${NC}"

# Изображение меньше минимального размера (50 КБ)
convert -size 50x50 gradient:blue-green -quality 85 "$TEST_IMAGES_DIR/test_image_too_small.jpg"
echo -e "${GREEN}✅ Создан: test_image_too_small.jpg (менее 100 КБ)${NC}"

# Изображение с низким качеством
convert -size 800x600 gradient:red-blue -quality 30 "$TEST_IMAGES_DIR/test_image_low_quality.jpg"
echo -e "${GREEN}✅ Создан: test_image_low_quality.jpg (качество 30)${NC}"

# Изображение с высоким качеством
convert -size 800x600 gradient:yellow-purple -quality 95 "$TEST_IMAGES_DIR/test_image_high_quality.jpg"
echo -e "${GREEN}✅ Создан: test_image_high_quality.jpg (качество 95)${NC}"

# PNG изображение (для тестирования конвертации)
convert -size 800x600 gradient:cyan-magenta "$TEST_IMAGES_DIR/test_image.png"
echo -e "${GREEN}✅ Создан: test_image.png${NC}"

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}✅ Генерация тестовых изображений завершена!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "${YELLOW}📂 Директория: $TEST_IMAGES_DIR${NC}"
echo -e "${YELLOW}📊 Количество файлов: $(ls -1 "$TEST_IMAGES_DIR" | wc -l)${NC}"
echo ""
