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
     * Создание имени файла для сжатого изображения
     */
    fun createCompressedFileName(originalFileName: String): String {
        val dotIndex = originalFileName.lastIndexOf(".")
        return if (dotIndex != -1) {
            val name = originalFileName.substring(0, dotIndex)
            val extension = originalFileName.substring(dotIndex)
            "$name${Constants.COMPRESSED_SUFFIX}$extension"
        } else {
            "${originalFileName}${Constants.COMPRESSED_SUFFIX}"
        }
    }

    /**
     * Проверка, было ли изображение уже сжато нашим приложением
     */
    fun isAlreadyCompressed(uri: Uri): Boolean {
        val path = uri.toString()
        // Проверяем наличие суффикса в пути или имени файла
        val containsSuffix = path.contains(Constants.COMPRESSED_SUFFIX)
        Timber.d("Проверка на наличие суффикса сжатия в URI: $uri, результат: $containsSuffix")
        return containsSuffix
    }

    /**
     * Сохранение сжатого изображения в галерею
     */
    fun saveCompressedImageToGallery(
        context: Context,
        compressedImageFile: File,
        originalImageUri: Uri
    ): Uri? {
        val contentResolver = context.contentResolver
        val originalFileName = getFileName(contentResolver, originalImageUri)
            ?: "compressed_image.jpg"
        
        val compressedFileName = createCompressedFileName(originalFileName)
        
        // Копирование EXIF данных из оригинального изображения
        try {
            copyExifData(contentResolver, originalImageUri, compressedImageFile)
        } catch (e: Exception) {
            Timber.e(e, "Ошибка при копировании EXIF данных")
        }
        
        // Сохранение в MediaStore
        val imageCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, compressedFileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + Constants.APP_DIRECTORY)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        
        val compressedImageUri = contentResolver.insert(imageCollection, contentValues)
        
        compressedImageUri?.let { uri ->
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                FileInputStream(compressedImageFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, contentValues, null, null)
            }
        }
        
        return compressedImageUri
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