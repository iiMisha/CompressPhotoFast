package com.compressphotofast.util

import android.content.Context
import android.content.SharedPreferences
import com.compressphotofast.ui.CompressionPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

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
     * Получение текущего режима сохранения
     */
    fun getSaveMode(): Int {
        return if (isSaveModeReplace()) Constants.SAVE_MODE_REPLACE else Constants.SAVE_MODE_SEPARATE
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
     * Получение всех отложенных запросов на удаление
     */
    fun getPendingDeleteUris(): Set<String> {
        return sharedPreferences.getStringSet(Constants.PREF_PENDING_DELETE_URIS, emptySet()) ?: emptySet()
    }
    
    /**
     * Проверка и установка статуса первого запуска
     */
    fun isFirstLaunch(): Boolean {
        return sharedPreferences.getBoolean(Constants.PREF_FIRST_LAUNCH, true)
    }
    
    /**
     * Установка флага первого запуска
     */
    fun setFirstLaunch(isFirst: Boolean) {
        sharedPreferences.edit()
            .putBoolean(Constants.PREF_FIRST_LAUNCH, isFirst)
            .apply()
    }
    
    /**
     * Проверка запрашивалось ли разрешение на удаление
     */
    fun isDeletePermissionRequested(): Boolean {
        return sharedPreferences.getBoolean(Constants.PREF_DELETE_PERMISSION_REQUESTED, false)
    }
    
    /**
     * Установка флага запроса разрешения на удаление
     */
    fun setDeletePermissionRequested(requested: Boolean) {
        sharedPreferences.edit()
            .putBoolean(Constants.PREF_DELETE_PERMISSION_REQUESTED, requested)
            .apply()
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
     * Проверяет, нужно ли игнорировать изображения из мессенджеров
     * @return true если нужно игнорировать, false в противном случае
     */
    fun shouldIgnoreMessengerPhotos(): Boolean {
        return sharedPreferences.getBoolean(Constants.PREF_IGNORE_MESSENGER_PHOTOS, true)
    }
    
    /**
     * Устанавливает настройку игнорирования изображений из мессенджеров
     * @param ignore true если нужно игнорировать, false в противном случае
     */
    fun setIgnoreMessengerPhotos(ignore: Boolean) {
        sharedPreferences.edit().putBoolean(Constants.PREF_IGNORE_MESSENGER_PHOTOS, ignore).apply()
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

    /**
     * Выполняет пакетное обновление нескольких настроек за одну операцию I/O
     *
     * Используйте этот метод когда нужно обновить несколько настроек одновременно.
     * Это более эффективно чем множественные вызовы отдельных set* методов.
     *
     * Пример использования:
     * ```kotlin
     * settingsManager.batchUpdate {
     *     setAutoCompression(true)
     *     setCompressionQuality(85)
     *     setSaveMode(false)
     * }
     * ```
     *
     * @param updates Лямбда с вызовами методов SettingsEditor для обновления настроек
     */
    suspend fun batchUpdate(updates: SettingsEditor.() -> Unit) = withContext(Dispatchers.IO) {
        val editor = sharedPreferences.edit()
        updates(SettingsEditor(editor))
        editor.apply()
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

/**
 * Editor для пакетного обновления настроек
 *
 * Используется внутри метода [SettingsManager.batchUpdate] для эффективного
 * обновления нескольких настроек за одну операцию I/O.
 *
 * Пример использования:
 * ```kotlin
 * settingsManager.batchUpdate {
 *     setAutoCompression(true)
 *     setCompressionQuality(85)
 *     setSaveMode(false)
 * }
 * ```
 */
class SettingsEditor(private val editor: SharedPreferences.Editor) {

    /**
     * Установка статуса автоматического сжатия
     */
    fun setAutoCompression(enabled: Boolean) {
        editor.putBoolean(Constants.PREF_AUTO_COMPRESSION, enabled)
    }

    /**
     * Установка режима сохранения
     * @param replace true - заменять оригинальные файлы, false - сохранять в отдельной папке
     */
    fun setSaveMode(replace: Boolean) {
        editor.putBoolean(Constants.PREF_SAVE_MODE, replace)
    }

    /**
     * Установка уровня сжатия
     */
    fun setCompressionQuality(quality: Int) {
        editor.putInt(Constants.PREF_COMPRESSION_QUALITY, quality)
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
     * Устанавливает настройку обработки скриншотов
     * @param processScreenshots true если нужно обрабатывать скриншоты, false в противном случае
     */
    fun setProcessScreenshots(processScreenshots: Boolean) {
        editor.putBoolean(Constants.PREF_PROCESS_SCREENSHOTS, processScreenshots)
    }

    /**
     * Устанавливает настройку игнорирования изображений из мессенджеров
     * @param ignore true если нужно игнорировать, false в противном случае
     */
    fun setIgnoreMessengerPhotos(ignore: Boolean) {
        editor.putBoolean(Constants.PREF_IGNORE_MESSENGER_PHOTOS, ignore)
    }

    /**
     * Устанавливает настройку показа Toast сообщений о результатах сжатия
     * @param show true если нужно показывать Toast, false в противном случае
     */
    fun setShowCompressionToast(show: Boolean) {
        editor.putBoolean(Constants.PREF_SHOW_COMPRESSION_TOAST, show)
    }

    /**
     * Установка флага первого запуска
     */
    fun setFirstLaunch(isFirst: Boolean) {
        editor.putBoolean(Constants.PREF_FIRST_LAUNCH, isFirst)
    }

    /**
     * Установка флага запроса разрешения на удаление
     */
    fun setDeletePermissionRequested(requested: Boolean) {
        editor.putBoolean(Constants.PREF_DELETE_PERMISSION_REQUESTED, requested)
    }
}