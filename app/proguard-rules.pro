# Проверьте, что правила ProGuard не влияют на функциональность приложения
# Общие правила для Android-проектов
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception
-keep class androidx.** { *; }

# Правила для Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel

# Правила для сохранения метаданных изображений (ExifInterface)
-keep class androidx.exifinterface.** { *; }

# Правила для библиотеки сжатия
-keep class id.zelory.compressor.** { *; }

# Правила для Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Правила для WorkManager
-keep class androidx.work.** { *; }

# Правила для Timber
-keep class timber.** { *; } 