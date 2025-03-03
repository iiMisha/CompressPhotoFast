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
     * Сохраняет сжатое изображение в галерею
     */
    fun saveCompressedImageToGallery(context: Context, compressedFile: File, fileName: String): Uri? {
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CompressPhotoFast")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val contentResolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        return try {
            val imageUri = contentResolver.insert(collection, contentValues)
            
            imageUri?.let { uri ->
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
                
                uri
            }
        } catch (e: IOException) {
            Timber.e(e, "Ошибка при сохранении сжатого изображения")
            null
        }
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