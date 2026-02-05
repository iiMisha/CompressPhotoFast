package com.compressphotofast.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore для хранения настроек приложения
 * Асинхронная альтернатива SharedPreferences для избежания ANR
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsDataStore(private val context: Context) {

    companion object {
        private val AUTO_COMPRESSION = booleanPreferencesKey("auto_compression")
        private val COMPRESSION_QUALITY = intPreferencesKey("compression_quality")
        private val SAVE_ORIGINAL = booleanPreferencesKey("save_original")
        private val AUTO_COMPRESS_MESSENGERS = booleanPreferencesKey("auto_compress_messengers")
        private val AUTO_COMPRESS_SCREENSHOTS = booleanPreferencesKey("auto_compress_screenshots")
    }

    // Auto compression
    val isAutoCompressionEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[AUTO_COMPRESSION] ?: false
    }

    suspend fun setAutoCompression(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_COMPRESSION] = enabled }
    }

    // Compression quality
    val compressionQuality: Flow<Int> = context.dataStore.data.map {
        it[COMPRESSION_QUALITY] ?: Constants.COMPRESSION_QUALITY_MEDIUM
    }

    suspend fun setCompressionQuality(quality: Int) {
        context.dataStore.edit { it[COMPRESSION_QUALITY] = quality }
    }

    // Save original
    val isSaveOriginalEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[SAVE_ORIGINAL] ?: false
    }

    suspend fun setSaveOriginal(enabled: Boolean) {
        context.dataStore.edit { it[SAVE_ORIGINAL] = enabled }
    }

    // Auto compress messengers
    val isAutoCompressMessengersEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[AUTO_COMPRESS_MESSENGERS] ?: true
    }

    suspend fun setAutoCompressMessengers(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_COMPRESS_MESSENGERS] = enabled }
    }

    // Auto compress screenshots
    val isAutoCompressScreenshotsEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[AUTO_COMPRESS_SCREENSHOTS] ?: true
    }

    suspend fun setAutoCompressScreenshots(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_COMPRESS_SCREENSHOTS] = enabled }
    }
}
