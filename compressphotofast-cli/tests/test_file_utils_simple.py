#!/usr/bin/env python3
"""
Простой тестовый скрипт для проверки функции clean_double_extensions
Не требует установки pytest
"""
import sys
import os

# Добавляем src директорию в путь для импорта
src_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'src')
sys.path.insert(0, src_dir)

from compression import clean_double_extensions


def test_function(name, input_value, expected):
    """Запускает один тест и выводит результат"""
    result = clean_double_extensions(input_value)
    status = "✓ PASS" if result == expected else "✗ FAIL"
    print(f"{status}: {name}")
    if result != expected:
        print(f"  Input: '{input_value}'")
        print(f"  Expected: '{expected}'")
        print(f"  Got: '{result}'")
    return result == expected


def main():
    """Запускает все тесты"""
    print("=" * 60)
    print("Testing clean_double_extensions function")
    print("=" * 60)

    tests = [
        # Одиночные расширения
        ("Single .jpg", "photo.jpg", "photo"),
        ("Single .png", "image.png", "image"),
        ("Single .heic", "photo.HEIC", "photo"),
        ("Single .heif", "image.heif", "image"),

        # Двойные расширения (критические тесты)
        ("Double .HEIC.jpg", "image.HEIC.jpg", "image"),
        ("Double .heif.jpeg", "photo.heif.jpeg", "photo"),
        ("Double .HEIF.png", "picture.HEIF.png", "picture"),

        # Краевые случаи
        ("No extension", "filename", "filename"),
        ("Multiple dots", "my.photo.jpg", "my.photo"),
        ("Triple extension", "image.backup.old.jpg", "image.backup.old"),
        ("Leading dot", ".hidden.jpg", ".hidden"),
        ("Only extension", ".jpg", ""),
        ("Empty string", "", ""),

        # Реальные примеры
        ("Real example 1", "IMG_20240130_123456.HEIC.jpg", "IMG_20240130_123456"),
        ("Real example 2", "Screenshot_2024.heif.jpeg", "Screenshot_2024"),
        ("Real example 3", "my.photo.2024.HEIC.jpg", "my.photo.2024"),
    ]

    passed = 0
    failed = 0

    for test_data in tests:
        if test_function(*test_data):
            passed += 1
        else:
            failed += 1

    print("=" * 60)
    print(f"Results: {passed} passed, {failed} failed out of {len(tests)} tests")
    print("=" * 60)

    return failed == 0


if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1)
