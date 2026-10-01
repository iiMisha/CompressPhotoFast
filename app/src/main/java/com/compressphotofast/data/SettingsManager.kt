package com.compressphotofast.data

import android.content.Context
import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Singleton
import com.compressphotofast.util.Constants

/**
 * Централизованный менеджер настроек приложения
 * Предотвращает дублирование кода для работы с SharedPreferences
 */
@Singleton
class SettingsManager @Inject constructor(
    private val sharedPreferences: SharedPreferences
) {
    /**
     * Проверка, включено ли автоматическое сжатие
     */
    fun isAutoCompressionEnabled(): Boolean {
        return sharedPreferences.getBoolean(Constants.PREF_AUTO_COMPRESSION, false)
    }

    /**
     * Установка статуса автоматического сжатия
     */
    fun setAutoCompression(enabled: Boolean) {
        sharedPreferences.edit()
            .putBoolean(Constants.PREF_AUTO_COMPRESSION, enabled)
            .apply()
    }
    
    /**
     * Проверка, включен ли режим замены оригинальных файлов
     */
    fun isSaveModeReplace(): Boolean {
        return sharedPreferences.getBoolean(Constants.PREF_SAVE_MODE, false)
    }

    /**
     * Установка режима сохранения
     * @param replace true - заменять оригинальные файлы, false - сохранять в отдельной папке
     */
    fun setSaveMode(replace: Boolean) {
        sharedPreferences.edit()
            .putBoolean(Constants.PREF_SAVE_MODE, replace)
            .apply()
    }

    /**
     * Проверка, был ли уже показан системный запрос на исключение из оптимизации батареи.
     * Используется, чтобы не показывать диалог повторно после отказа.
     */
    fun isBatteryExemptionRequested(): Boolean {
        return sharedPreferences.getBoolean(Constants.PREF_BATTERY_EXEMPTION_REQUESTED, false)
    }

    /**
     * Отметка, что системный запрос на исключение из оптимизации батареи был показан.
     */
    fun setBatteryExemptionRequested(requested: Boolean) {
        sharedPreferences.edit()
            .putBoolean(Constants.PREF_BATTERY_EXEMPTION_REQUESTED, requested)
            .apply()
    }
    
    /**
     * Получение текущего уровня сжатия
     */
    fun getCompressionQuality(): Int {
        return sharedPreferences.getInt(
            Constants.PREF_COMPRESSION_QUALITY, 
            Constants.COMPRESSION_QUALITY_MEDIUM
        )
    }
    
    /**
     * Установка уровня сжатия
     */
    fun setCompressionQuality(quality: Int) {
        sharedPreferences.edit()
            .putInt(Constants.PREF_COMPRESSION_QUALITY, quality)
            .apply()
    }
    
    /**
     * Получение максимального разрешения (по большей стороне).
     * @return 0 для исходного разрешения, либо 2560/1920
     */
    fun getMaxResolution(): Int {
        val value = sharedPreferences.getInt(Constants.PREF_MAX_RESOLUTION, Constants.DEFAULT_MAX_RESOLUTION)
        // Миграция: пресет 1280 удалён, возвращаем к значению по умолчанию
        return if (value == 1280) {
            sharedPreferences.edit()
                .putInt(Constants.PREF_MAX_RESOLUTION, Constants.DEFAULT_MAX_RESOLUTION)
                .apply()
            Constants.DEFAULT_MAX_RESOLUTION
        } else {
            value
        }
    }

    /**
     * Установка максимального разрешения (по большей стороне)
     */
    fun setMaxResolution(maxDimension: Int) {
        sharedPreferences.edit()
            .putInt(Constants.PREF_MAX_RESOLUTION, maxDimension)
            .apply()
    }

    /**
     * Установка уровня сжатия по предустановке (низкий, средний, высокий)
     */
    fun setCompressionPreset(preset: CompressionPreset) {
        val quality = when (preset) {
            CompressionPreset.LOW -> Constants.COMPRESSION_QUALITY_LOW
            CompressionPreset.MEDIUM -> Constants.COMPRESSION_QUALITY_MEDIUM
            CompressionPreset.HIGH -> Constants.COMPRESSION_QUALITY_HIGH
        }
        setCompressionQuality(quality)
    }

    /**
     * Сохранение отложенных запросов на удаление
     */
    fun savePendingDeleteUri(uri: String) {
        val pendingDeleteUris = sharedPreferences.getStringSet(Constants.PREF_PENDING_DELETE_URIS, mutableSetOf()) ?: mutableSetOf()
        val newSet = pendingDeleteUris.toMutableSet()
        newSet.add(uri)
        
        sharedPreferences.edit()
            .putStringSet(Constants.PREF_PENDING_DELETE_URIS, newSet)
            .apply()
    }
    
    /**
     * Получение и удаление первого отложенного запроса на удаление
     * @return URI для удаления или null если список пуст
     */
    fun getAndRemoveFirstPendingDeleteUri(): String? {
        val pendingDeleteUris = sharedPreferences.getStringSet(Constants.PREF_PENDING_DELETE_URIS, null)
        if (pendingDeleteUris.isNullOrEmpty()) return null
        
        val uriString = pendingDeleteUris.firstOrNull() ?: return null
        val newSet = pendingDeleteUris.toMutableSet()
        newSet.remove(uriString)
        
        sharedPreferences.edit()
            .putStringSet(Constants.PREF_PENDING_DELETE_URIS, newSet)
            .apply()
        
        return uriString
    }
    
    /**
     * Проверяет, нужно ли обрабатывать скриншоты
     * @return true если нужно обрабатывать скриншоты, false в противном случае
     */
    fun shouldProcessScreenshots(): Boolean {
        return sharedPreferences.getBoolean(Constants.PREF_PROCESS_SCREENSHOTS, true)
    }
    
    /**
     * Устанавливает настройку обработки скриншотов
     * @param processScreenshots true если нужно обрабатывать скриншоты, false в противном случае
     */
    fun setProcessScreenshots(processScreenshots: Boolean) {
        sharedPreferences.edit().putBoolean(Constants.PREF_PROCESS_SCREENSHOTS, processScreenshots).apply()
    }

    /**
     * Проверяет, нужно ли показывать Toast сообщения о результатах сжатия
     * @return true если нужно показывать Toast, false в противном случае (по умолчанию false)
     */
    fun shouldShowCompressionToast(): Boolean {
        return sharedPreferences.getBoolean(Constants.PREF_SHOW_COMPRESSION_TOAST, false)
    }

    /**
     * Устанавливает настройку показа Toast сообщений о результатах сжатия
     * @param show true если нужно показывать Toast, false в противном случае
     */
    fun setShowCompressionToast(show: Boolean) {
        sharedPreferences.edit().putBoolean(Constants.PREF_SHOW_COMPRESSION_TOAST, show).apply()
    }

    fun getLastScanTimestamp(): Long {
        return sharedPreferences.getLong(Constants.PREF_LAST_SCAN_TIMESTAMP, 0L)
    }

    fun setLastScanTimestamp(timestamp: Long) {
        sharedPreferences.edit()
            .putLong(Constants.PREF_LAST_SCAN_TIMESTAMP, timestamp)
            .apply()
    }

    /** Время начала последнего durable HISTORY-скана. */
    fun getLastHistoryScanTimestamp(): Long {
        return sharedPreferences.getLong(Constants.PREF_LAST_HISTORY_SCAN_TIMESTAMP, 0L)
    }

    fun setLastHistoryScanTimestamp(timestamp: Long) {
        sharedPreferences.edit()
            .putLong(Constants.PREF_LAST_HISTORY_SCAN_TIMESTAMP, timestamp)
            .apply()
    }

    companion object {
        /**
         * Создает экземпляр SettingsManager без внедрения зависимостей (для классов без Hilt)
         */
        fun getInstance(context: Context): SettingsManager {
            val prefs = context.getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE)
            return SettingsManager(prefs)
        }
    }
}
