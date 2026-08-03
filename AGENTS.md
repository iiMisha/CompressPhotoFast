# CompressPhotoFast

Кроссплатформенная утилита сжатия фото с сохранением EXIF: Android (API 29+) и Python CLI (3.10+). Язык проекта: русский. Версия: `2.2.10`.

## Быстрые правила

- Для сборки или передачи APK обязательно использовать навык `apk`.
- Android-тесты запускать через навык `android-test-suite`; по умолчанию только unit-тесты.
- Не обходить ограничения Android для force stop, отозванных разрешений и ручных ограничений батареи.
- Не удалять код, используемый в `test` или `androidTest`, без одновременного обновления тестов.

## Стек

- Android: Kotlin 2.2.10, Coroutines, Hilt, WorkManager, DataStore, Coil, ExifInterface; minSdk 29, targetSdk 36.
- Тесты: JUnit, MockK, Robolectric, Espresso, JaCoCo.
- CLI: Pillow, pillow-heif, piexif, Click, Rich, tqdm, `ProcessPoolExecutor`.

## Архитектура Android

- UI: `ui/MainActivity.kt`, `ui/MainViewModel.kt`.
- Сжатие: `worker/ImageCompressionWorker.kt`, `util/ImageCompressionUtil.kt`, `util/ImageProcessingChecker.kt`.
- Настройки и данные: `util/SettingsManager.kt`, `MediaStore`.
- Инфраструктура: `di/AppModule.kt`, `util/UriProcessingTracker.kt`, `util/CompressionBatchTracker.kt`, `util/StatsTracker.kt`.
- Мониторинг: `service/BackgroundMonitoringService.kt`, `service/ImageDetectionJobService.kt`, `service/MonitoringController.kt`, `service/BootCompletedReceiver.kt`.
- CLI: `compressphotofast-cli/src/cli.py`, `compressphotofast-cli/src/compression.py`.

## Инварианты сжатия

- Не сжимать файлы меньше 100 КБ.
- Сохранять результат, только если экономия не меньше 30% и 10 КБ.
- Маркировать сжатые файлы EXIF-тегом `CompressPhotoFast_Compressed:quality:timestamp`.
- Для экономии памяти применять `inSampleSize` и `RGB_565`, пакетно работать с MediaStore.
- Python-логика должна сохранять семантическое соответствие Android-реализации.

## Актуальный контекст

- Мониторинг фото работает как `specialUse` foreground service на API 34+; `MonitoringController` централизует запуск и остановку.
- Сервис использует `START_STICKY`, корректно отменяет резервный Job при ручной остановке и восстанавливается после перезагрузки или обновления приложения.
- При первом включении автосжатия однократно запрашивается исключение из оптимизации батареи; флаг хранится в `SettingsManager`.
- Игнорирование фото из мессенджеров удалено: защита от повторного сжатия основана на проверке эффективности.
- Реализованы резервное копирование и восстановление исходного файла при неудаче файловых операций.
- UI/E2E instrumentation-тесты удалены как неактуальные; сохранены интеграционные тесты утилит и сервисов.
- Последнее изменение: суточная статистика сжатия в уведомлениях (`966ccc5`).

## Проверка и релиз

- Сборка: `./gradlew assembleDebug`.
- Unit-тесты: `./gradlew testDebugUnitTest` через навык `android-test-suite`.
- Instrumentation-тесты: `./scripts/run_instrumentation_tests.sh`; нужен эмулятор `Small_Phone`.
- Перед релизом: `./scripts/run_all_tests.sh`, затем `./gradlew assembleDebug` и `./gradlew assembleRelease`.
- Версию обновлять в `gradle.properties` (`VERSION_NAME_BASE`) и `app/build.gradle.kts` (`versionCode`).

## Рабочий процесс

1. Внести изменения в код.
2. Проверить сборку Android командой `./gradlew assembleDebug`.
3. Запустить unit-тесты `./gradlew testDebugUnitTest` через навык `android-test-suite`.
4. Обновить этот файл навыком `agents-updater`, сохраняя его кратким и актуальным.
5. Собрать и расшарить debug-APK через навык `apk`.
