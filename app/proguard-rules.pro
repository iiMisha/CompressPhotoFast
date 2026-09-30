# AndroidX, Hilt, WorkManager, ExifInterface и coroutines поставляют собственные
# consumer-правила: широкие -keep здесь отключали R8 для всего androidx (dex ~6 МБ).
-keepattributes SourceFile,LineNumberTable

# Логирование в релизе выключено: вызовы LogUtil (вместе с построением строк
# аргументов) вырезаются целиком.
-assumenosideeffects class com.compressphotofast.util.LogUtil {
    public void *(...);
}
