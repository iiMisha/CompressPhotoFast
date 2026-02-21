#!/usr/bin/env python3
"""
Создаёт тестовые изображения с различными EXIF orientation значениями.
Требуется: Pillow, piexif, pillow-heif (для HEIC)
"""

import os
from PIL import Image, ImageDraw
import piexif

# HEIC support (optional)
try:
    import pillow_heif

    HEIC_AVAILABLE = True
except ImportError:
    HEIC_AVAILABLE = False

output_dir = "app/src/androidTest/assets/orientation"
heic_output_dir = f"{output_dir}/heic"
os.makedirs(output_dir, exist_ok=True)
os.makedirs(heic_output_dir, exist_ok=True)


def create_test_image(width=200, height=300):
    """Создаёт тестовое изображение с метками для проверки ориентации."""
    img = Image.new("RGB", (width, height), color=(100, 150, 200))
    draw = ImageDraw.Draw(img)

    draw.text((10, 10), "TOP-LEFT", fill=(255, 255, 255))
    draw.text((10, height - 30), "BOTTOM-LEFT", fill=(255, 255, 255))
    draw.text((width - 80, 10), "TOP-RIGHT", fill=(255, 255, 255))
    draw.text((width - 100, height - 30), "BOTTOM-RIGHT", fill=(255, 255, 255))

    draw.polygon(
        [(width // 2, 20), (width // 2 - 20, 60), (width // 2 + 20, 60)],
        fill=(255, 200, 0),
    )
    draw.rectangle([10, height // 2 - 20, 30, height // 2 + 20], fill=(0, 255, 0))

    return img


def set_exif_orientation(img, orientation, filepath):
    """Сохраняет изображение с указанным EXIF orientation (JPEG)."""
    exif_dict = {"0th": {piexif.ImageIFD.Orientation: orientation}}
    exif_bytes = piexif.dump(exif_dict)
    img.save(filepath, "JPEG", exif=exif_bytes, quality=95)
    print(f"Created: {filepath} (orientation={orientation})")


def set_heif_exif_orientation(img, orientation, filepath):
    """Сохраняет изображение как HEIC с указанным EXIF orientation."""
    if not HEIC_AVAILABLE:
        print(f"Skipped: {filepath} (pillow-heif not installed)")
        return

    # Создаём EXIF данные с orientation
    exif_dict = {"0th": {piexif.ImageIFD.Orientation: orientation}}
    exif_bytes = piexif.dump(exif_dict)

    # Pillow-heif поддерживает сохранение через pillow с форматом HEIF
    # Добавляем EXIF в exif поле
    img.save(
        filepath,
        format="HEIF",
        exif=exif_bytes,
        quality=90,
        subsampling="4:2:0",  # Стандартный subsampling для фото
    )
    print(f"Created: {filepath} (orientation={orientation})")


base_img = create_test_image()

# JPEG тестовые файлы
set_exif_orientation(base_img, 1, f"{output_dir}/test_normal.jpg")
set_exif_orientation(base_img, 6, f"{output_dir}/test_rotate_90.jpg")
set_exif_orientation(base_img, 3, f"{output_dir}/test_rotate_180.jpg")
set_exif_orientation(base_img, 8, f"{output_dir}/test_rotate_270.jpg")
set_exif_orientation(base_img, 2, f"{output_dir}/test_flip_horizontal.jpg")
set_exif_orientation(base_img, 4, f"{output_dir}/test_flip_vertical.jpg")
set_exif_orientation(base_img, 5, f"{output_dir}/test_transpose.jpg")
set_exif_orientation(base_img, 7, f"{output_dir}/test_transverse.jpg")

print(f"\nCreated 8 JPEG test images in {output_dir}/")

# HEIC тестовые файлы (основные ориентации для тестирования)
if HEIC_AVAILABLE:
    assert pillow_heif is not None  # type: ignore[name-defined]
    # Регистрируем HEIF opener для Pillow
    pillow_heif.register_heif_opener()  # type: ignore[possibly-unbound]

    print("\nCreating HEIC test images...")
    heic_base_img = create_test_image()

    set_heif_exif_orientation(heic_base_img, 1, f"{heic_output_dir}/test_normal.heic")
    set_heif_exif_orientation(
        heic_base_img, 6, f"{heic_output_dir}/test_rotate_90.heic"
    )
    set_heif_exif_orientation(
        heic_base_img, 3, f"{heic_output_dir}/test_rotate_180.heic"
    )
    set_heif_exif_orientation(
        heic_base_img, 8, f"{heic_output_dir}/test_rotate_270.heic"
    )
    set_heif_exif_orientation(
        heic_base_img, 2, f"{heic_output_dir}/test_flip_horizontal.heic"
    )
    set_heif_exif_orientation(
        heic_base_img, 4, f"{heic_output_dir}/test_flip_vertical.heic"
    )

    print(f"\nCreated 6 HEIC test images in {heic_output_dir}/")
else:
    print("\n⚠️  HEIC creation skipped: pillow-heif not installed")
    print("   Install with: pip install pillow-heif")
