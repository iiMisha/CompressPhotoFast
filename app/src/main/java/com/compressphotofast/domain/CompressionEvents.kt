package com.compressphotofast.domain

import android.net.Uri
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Внутрипроцессная шина событий сжатия: воркер → UI/сервис.
 * Заменяет broadcast'ы, которые были видны за пределами приложения.
 * Без replay: как и у broadcast, событие получают только активные подписчики.
 */
@Singleton
class CompressionEvents @Inject constructor() {

    sealed interface Event {
        /** Одиночное сжатие (без batch ID) завершено или пропущено. */
        data class Result(
            val uri: Uri,
            val fileName: String,
            val originalSize: Long,
            val compressedSize: Long,
            val sizeReduction: Float,
            val skipped: Boolean
        ) : Event

        /** Оригинал не удалён без подтверждения пользователя (replace-режим). */
        data class DeleteConfirmationRequired(val uri: Uri) : Event
    }

    private val _events = MutableSharedFlow<Event>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun emit(event: Event) {
        _events.tryEmit(event)
    }
}
