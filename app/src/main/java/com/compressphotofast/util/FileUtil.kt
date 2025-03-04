package com.compressphotofast.util

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Утилитарный класс для работы с файлами
 */
object FileUtil {

    /**
     * Получение имени файла из URI
     */
    fun getFileName(contentResolver: ContentResolver, uri: Uri): String? {
        var fileName: String? = null
        val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
        
        cursor?.use {
            if (it.moveToFirst()) {
                val displayNameIndex = it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                if (displayNameIndex != -1) {
                    fileName = it.getString(displayNameIndex)
                }
            }
        }
        
        return fileName
    }

    /**
     * Проверяет, содержит ли URI маркеры сжатого изображения
     */
    fun isAlreadyCompressed(uri: Uri): Boolean {
        val path = uri.toString().lowercase()
        return path.contains("_compressed") || 
               path.contains("_сжатое") || 
               path.contains("_small")
    }

    /**
     * Создает имя файла для сжатой версии
     */
    fun createCompressedFileName(originalName: String): String {
        val extension = originalName.substringAfterLast(".", "")
        val nameWithoutExt = originalName.substringBeforeLast(".")
        return "${nameWithoutExt}_compressed.$extension"
    }

    /**
     * Сохраняет сжатое изображение в ту же папку, где находится оригинал
     */
    fun saveCompressedImageToGallery(context: Context, compressedFile: File, fileName: String, originalUri: Uri): Uri? {
        val contentResolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Получаем путь к оригинальному файлу
            val originalPath = getOriginalRelativePath(context, originalUri)
            if (originalPath != null) {
                contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, originalPath)
            }
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        return try {
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    FileInputStream(compressedFile).use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, contentValues, null, null)
                }

                Timber.d("Файл успешно сохранен: $fileName")
                uri
            } else {
                Timber.e("Не удалось создать URI для сохранения файла")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при сохранении файла: ${e.message}")
            null
        }
    }

    /**
     * Получает относительный путь к папке оригинального файла
     */
    private fun getOriginalRelativePath(context: Context, uri: Uri): String? {
        val projection = arrayOf(
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATA
        )

        try {
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    // Сначала пробуем получить RELATIVE_PATH (Android 10+)
                    val relativePathIndex = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                    if (relativePathIndex != -1 && !cursor.isNull(relativePathIndex)) {
                        return cursor.getString(relativePathIndex)
                    }

                    // Если RELATIVE_PATH недоступен, пробуем получить путь из DATA
                    val dataIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                    if (dataIndex != -1 && !cursor.isNull(dataIndex)) {
                        val fullPath = cursor.getString(dataIndex)
                        // Извлекаем относительный путь, убирая имя файла
                        val lastSeparator = fullPath.lastIndexOf('/')
                        if (lastSeparator != -1) {
                            // Получаем путь без имени файла и убираем начальный слэш
                            val path = fullPath.substring(0, lastSeparator)
                            val startIndex = path.indexOf("Pictures/")
                            if (startIndex != -1) {
                                return path.substring(startIndex)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при получении пути к файлу")
        }

        // Если не удалось получить путь, возвращаем путь по умолчанию
        return Environment.DIRECTORY_PICTURES
    }

    /**
     * Копирование EXIF данных из оригинального изображения в сжатое
     */
    private fun copyExifData(
        contentResolver: ContentResolver,
        originalImageUri: Uri,
        compressedImageFile: File
    ) {
        var inputStream: InputStream? = null
        
        try {
            inputStream = contentResolver.openInputStream(originalImageUri)
            if (inputStream != null) {
                val originalExif = ExifInterface(inputStream)
                val compressedExif = ExifInterface(compressedImageFile.absolutePath)
                
                // Копирование всех тегов EXIF
                val tags = arrayOf(
                    ExifInterface.TAG_DATETIME,
                    ExifInterface.TAG_EXPOSURE_TIME,
                    ExifInterface.TAG_FLASH,
                    ExifInterface.TAG_FOCAL_LENGTH,
                    ExifInterface.TAG_GPS_ALTITUDE,
                    ExifInterface.TAG_GPS_ALTITUDE_REF,
                    ExifInterface.TAG_GPS_DATESTAMP,
                    ExifInterface.TAG_GPS_LATITUDE,
                    ExifInterface.TAG_GPS_LATITUDE_REF,
                    ExifInterface.TAG_GPS_LONGITUDE,
                    ExifInterface.TAG_GPS_LONGITUDE_REF,
                    ExifInterface.TAG_GPS_PROCESSING_METHOD,
                    ExifInterface.TAG_GPS_TIMESTAMP,
                    ExifInterface.TAG_MAKE,
                    ExifInterface.TAG_MODEL,
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.TAG_WHITE_BALANCE
                )
                
                for (tag in tags) {
                    val value = originalExif.getAttribute(tag)
                    if (value != null) {
                        compressedExif.setAttribute(tag, value)
                    }
                }
                
                compressedExif.saveAttributes()
            }
        } catch (e: IOException) {
            Timber.e(e, "Ошибка при копировании EXIF данных")
        } finally {
            try {
                inputStream?.close()
            } catch (e: IOException) {
                Timber.e(e, "Ошибка при закрытии потока")
            }
        }
    }
} 