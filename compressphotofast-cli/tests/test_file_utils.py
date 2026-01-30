"""
Тесты для утилит работы с файлами в CLI
Проверяют функцию очистки двойных расширений
"""
import pytest
from compression import clean_double_extensions


class TestCleanDoubleExtensions:
    """Тесты функции clean_double_extensions"""

    def test_single_extension_jpg(self):
        """Одиночное расширение .jpg"""
        assert clean_double_extensions("photo.jpg") == "photo"

    def test_single_extension_png(self):
        """Одиночное расширение .png"""
        assert clean_double_extensions("image.png") == "image"

    def test_single_extension_heic(self):
        """Одиночное расширение .heic"""
        assert clean_double_extensions("photo.HEIC") == "photo"

    def test_single_extension_heif(self):
        """Одиночное расширение .heif"""
        assert clean_double_extensions("image.heif") == "image"

    def test_double_extension_heic_jpg(self):
        """Двойное расширение .HEIC.jpg"""
        assert clean_double_extensions("image.HEIC.jpg") == "image"

    def test_double_extension_heif_jpg(self):
        """Двойное расширение .heif.jpeg"""
        assert clean_double_extensions("photo.heif.jpeg") == "photo"

    def test_double_extension_heif_png(self):
        """Двойное расширение .HEIF.png"""
        assert clean_double_extensions("picture.HEIF.png") == "picture"

    def test_no_extension(self):
        """Файл без расширения"""
        assert clean_double_extensions("filename") == "filename"

    def test_multiple_dots_in_name(self):
        """Имя файла с несколькими точками, но без двойного расширения"""
        assert clean_double_extensions("my.photo.jpg") == "my.photo"

    def test_triple_extension(self):
        """Тройное расширение (редкий случай)"""
        # Должно очистить до последней точки
        assert clean_double_extensions("image.backup.old.jpg") == "image.backup.old"

    def test_leading_dot(self):
        """Файл начинающийся с точки (hidden file в Unix)"""
        # Файлы с ведущей точкой обрабатываются особым образом
        assert clean_double_extensions(".hidden.jpg") == ".hidden"

    def test_only_extension(self):
        """Только расширение без имени"""
        assert clean_double_extensions(".jpg") == ""

    def test_empty_string(self):
        """Пустая строка"""
        assert clean_double_extensions("") == ""

    def test_case_sensitivity(self):
        """Проверка регистрозависимости"""
        assert clean_double_extensions("IMAGE.HEIC.JPG") == "IMAGE"
        assert clean_double_extensions("Photo.Heic.Jpg") == "Photo"

    def test_complex_real_world_example_1(self):
        """Реальный пример: IMG_20240130_123456.HEIC.jpg"""
        assert clean_double_extensions("IMG_20240130_123456.HEIC.jpg") == "IMG_20240130_123456"

    def test_complex_real_world_example_2(self):
        """Реальный пример: Screenshot_2024.heif.jpeg"""
        assert clean_double_extensions("Screenshot_2024.heif.jpeg") == "Screenshot_2024"

    def test_real_world_example_with_dots(self):
        """Реальный пример с точками в имени: my.photo.2024.HEIC.jpg"""
        assert clean_double_extensions("my.photo.2024.HEIC.jpg") == "my.photo.2024"


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
