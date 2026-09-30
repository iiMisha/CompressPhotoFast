package com.compressphotofast.util

import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/** Ограничивает только тяжёлую фазу обработки, не меняя executor WorkManager. */
@Singleton
class CompressionExecutionGate @Inject constructor() {
    private val mutex = Mutex()

    suspend fun acquire() = mutex.lock()

    fun release() = mutex.unlock()
}
