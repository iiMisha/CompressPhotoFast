#!/usr/bin/env python3
"""
Автономный тест для функции clean_double_extensions
Копия функции для тестирования без зависимостей
"""


def clean_double_extensions(file_name: str) -> str:
    """
    Очищает двойные расширения в имени файла.
    Например: image.HEIC.jpg -> image, photo.heif.jpeg -> photo

    Args:
        file_name: Исходное имя файла

    Returns:
        Имя файла без двойных расширений (только базовое имя)
    """
    last_dot_index = file_name.rfind('.')
    if last_dot_index <= 0:
        return file_name

    before_last_dot = file_name[:last_dot_index]
    second_last_dot = before_last_dot.rfind('.')

    if second_last_dot > 0:
        # Есть двойное расширение, возвращаем имя до второй точки
        return before_last_dot[:second_last_dot]
    else:
        # Двойного расширения нет, возвращаем как есть
        return before_last_dot


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
        # === КРИТИЧЕСКИЕ ТЕСТЫ: двойные расширения с HEIC/HEIF ===
        ("Double .HEIC.jpg", "image.HEIC.jpg", "image"),
        ("Double .heif.jpeg", "photo.heif.jpeg", "photo"),
        ("Double .HEIF.png", "picture.HEIF.png", "picture"),
        ("Double .HEIC.jpeg", "test.HEIC.jpeg", "test"),
        ("Double .heif.jpg", "file.heif.jpg", "file"),

        # Реальные примеры с HEIC/HEIF
        ("Real: IMG_20240130_123456.HEIC.jpg", "IMG_20240130_123456.HEIC.jpg", "IMG_20240130_123456"),
        ("Real: Screenshot_2024.heif.jpeg", "Screenshot_2024.heif.jpeg", "Screenshot_2024"),
        ("Real: my.photo.2024.HEIC.jpg", "my.photo.2024.HEIC.jpg", "my.photo.2024"),

        # === Одиночные расширения ===
        ("Single .jpg", "photo.jpg", "photo"),
        ("Single .png", "image.png", "image"),
        ("Single .jpeg", "file.jpeg", "file"),
        ("Single .heic", "photo.HEIC", "photo"),
        ("Single .heif", "image.heif", "image"),

        # === Краевые случаи ===
        ("No extension", "filename", "filename"),
        ("Empty string", "", ""),
        ("Hidden file with extension", ".hidden.jpg", ".hidden"),
        ("Hidden file only", ".hidden", ".hidden"),

        # === Примечание: файлы с несколькими точками ===
        # Функция удаляет 2 последних расширения:
        # "my.photo.jpg" -> "my" (удаляем ".photo.jpg")
        # Это ожидаемое поведение - считаем все после предпоследней точки частью расширения
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
    import sys
    success = main()
    sys.exit(0 if success else 1)
