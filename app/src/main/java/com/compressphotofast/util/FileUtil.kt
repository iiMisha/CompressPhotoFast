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
     * @deprecated Используйте ImageTrackingUtil.isImageProcessed вместо этого метода
     */
    @Deprecated("Используйте ImageTrackingUtil.isImageProcessed для проверки статуса сжатия", 
                ReplaceWith("ImageTrackingUtil.isImageProcessed(context, uri)"))
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
    suspend fun saveCompressedImageToGallery(
        context: Context,
        compressedFile: File,
        fileName: String,
        originalUri: Uri
    ): Uri? {
        try {
            // Проверяем существование файла с таким именем
            var finalFileName = fileName
            var counter = 1
            
            while (true) {
                val testFileName = if (counter == 1) finalFileName else {
                    val baseName = finalFileName.substringBeforeLast(".")
                    val ext = finalFileName.substringAfterLast(".", "")
                    "${baseName}_$counter.$ext"
                }
                
                val exists = context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID),
                    "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
                    arrayOf(testFileName),
                    null
                )?.use { cursor -> cursor.count > 0 } ?: false

                if (!exists) {
                    finalFileName = testFileName
                    break
                }
                counter++
            }

            // Создаем запись в MediaStore
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, finalFileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DESCRIPTION, "Compressed")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + Constants.APP_DIRECTORY)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            // Сохраняем файл
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    compressedFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                // Копируем EXIF данные
                copyExifData(context, originalUri, uri)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                }

                // Отмечаем изображение как обработанное
                ImageTrackingUtil.markImageAsProcessed(context, uri)
                
                return uri
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при сохранении сжатого изображения")
        }
        return null
    }

    /**
     * Копирует EXIF данные из оригинального изображения в сжатое
     */
    private fun copyExifData(context: Context, sourceUri: Uri, destUri: Uri) {
        try {
            val sourceStream = context.contentResolver.openInputStream(sourceUri)
            val destStream = context.contentResolver.openInputStream(destUri)
            
            if (sourceStream != null && destStream != null) {
                val sourceExif = ExifInterface(sourceStream)
                val destExif = ExifInterface(destStream)
                
                // Копируем все EXIF теги
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
                    ExifInterface.TAG_IMAGE_LENGTH,
                    ExifInterface.TAG_IMAGE_WIDTH,
                    ExifInterface.TAG_MAKE,
                    ExifInterface.TAG_MODEL,
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.TAG_SUBSEC_TIME,
                    ExifInterface.TAG_WHITE_BALANCE
                )
                
                for (tag in tags) {
                    val value = sourceExif.getAttribute(tag)
                    if (value != null) {
                        destExif.setAttribute(tag, value)
                    }
                }
                
                // Сохраняем изменения
                context.contentResolver.openOutputStream(destUri)?.use { outputStream ->
                    destExif.saveAttributes()
                }
            }
            
            sourceStream?.close()
            destStream?.close()
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при копировании EXIF данных")
        }
    }
} 