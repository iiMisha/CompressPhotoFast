# План: выбор разрешения для сжимаемых фото (Android)

## Цель
Добавить пользовательскую настройку максимального разрешения сжатых фото. Пресеты: **Оригинал (по умолчанию) / 1920px / 1280px** — по большей стороне, пропорции сохраняются. Python CLI не затрагивается.

## Контекст (проверено по коду)
- Масштабирование сегодня отсутствует: `calculateInSampleSize()` всегда возвращает 1 (`ImageCompressionUtil.kt:696`), полный decode без изменения размеров (`ImageCompressionUtil.kt:336`, комментарий «preserve source resolution» — инвариант остаётся поведением по умолчанию для «Оригинал»).
- Готовые хуки: параметр `inSampleSize` в `decodeImageBitmap` (`ImageCompressionUtil.kt:235`), `ImageDecoder.setTargetSize` в HEIC-ветке (`ImageCompressionUtil.kt:249`).
- Настройки: SharedPreferences через `SettingsManager` (не DataStore), ключи в `Constants.kt:9–23`; качество — образец (`SettingsManager.kt:84–110`, радио-кнопки в `MainActivity.setupCompressionQualityRadioButtons`, `MainViewModel` LiveData-паттерн).
- Прокидка в worker: `CompressionWorkScheduler.buildInputData` (`CompressionWorkScheduler.kt:97–114`) → `ImageCompressionWorker` читает `inputData` (строка 57). `ImageSettleWorker` пересылает inputData как есть — изменений не требует.

## Решения (согласованы с пользователем)
1. Пресеты: Original / 1920 / 1280 (по большей стороне). Default — Original.
2. Только Android; CLI вне скоупа.
3. Пороги эффективности (30% и 10KB, `isImageProcessingEfficient`) и минимальный размер 100KB — без изменений; EXIF-маркер — без изменения формата.
4. Инвариант «сохранять исходное разрешение» остаётся значением по умолчанию; даунскейл — только при явном выборе пресета.

## Задачи

### 1. Constants
`app/src/main/java/com/compressphotofast/util/Constants.kt`:
- `PREF_MAX_RESOLUTION = "max_resolution"`, `WORK_MAX_RESOLUTION = "work_max_resolution"`.
- `const val RESOLUTION_ORIGINAL = 0`, `RESOLUTION_1920 = 1920`, `RESOLUTION_1280 = 1280`.

### 2. SettingsManager
- `getMaxResolution(): Int` (default `RESOLUTION_ORIGINAL`), `setMaxResolution(Int)`; включить в `SettingsEditor`/backup-restore, если они перечисляют настройки явно.

### 3. ImageCompressionUtil — масштабирование
- Добавить параметр `maxDimension: Int` в `compressImageToFile` и `decodeImageBitmap` (все вызовы обновить; `processAndSaveImage` — тоже).
- Логика при `maxDimension > 0` и `max(w,h) > maxDimension`:
  - JPEG: вычислить `inSampleSize` как степень двойки ≥ целевого коэффициента (не мельчить сильнее нужного), затем точный добор `Bitmap.createScaledBitmap` по большей стороне, если после сэмплинга всё ещё больше цели. RGB_565 сохранить.
  - HEIC: использовать существующую ветку `ImageDecoder.setTargetSize(w', h')` с вычисленными пропорциональными размерами.
  - Если изображение уже ≤ maxDimension — декодировать как сегодня (без изменений).
- Оценка памяти admission-гейта (`estimatePeakMemoryBytes`) можно оставить консервативной (полный размер) — даунскейл только уменьшает память; менять гейт не обязательно.

### 4. Прокидка в пайплайн сжатия
- `CompressionWorkScheduler.buildInputData`: добавить `putInt(WORK_MAX_RESOLUTION, settingsManager.getMaxResolution())`.
- `ImageCompressionWorker`: читать `inputData.getInt(WORK_MAX_RESOLUTION, RESOLUTION_ORIGINAL)`; передавать в `testCompression`/`performCompression` → `compressImageToFile`. `ImageSettleWorker` — без изменений.
- Ручной путь `processAndSaveImage`: добавить параметр, на вызывающей стороне читать из `SettingsManager`.

### 5. UI
- `MainActivity`: радио-группа выбора разрешения рядом с качеством (3 кнопки: Оригинал / 1920px / 1280px), по паттерну `setupCompressionQualityRadioButtons`; layout-файл `activity_main.xml` (+ строки в `strings.xml`, язык проекта — русский + en при наличии).
- `MainViewModel`: LiveData + get/set по паттерну `compressionQuality`.

### 6. Тесты
- Unit-тесты на новую логику масштабирования в `ImageCompressionUtil` (JPEG downscale до 1920/1280, landscape/portrait, изображение меньше цели — без изменений, HEIC target size).
- Тест `CompressionWorkScheduler.buildInputData` прокидывает ключ.
- Существующие тесты, полагающиеся на «исходное разрешение», должны проходить при дефолте Original.

## Риски / заметки
- Точный `createScaledBitmap` после `inSampleSize` требует дополнительной аллокации — применять только если остаточное превышение > ~10%.
- Эффективность (30%/10KB) при даунскейле почти всегда проходит — это ожидаемое поведение.
- MediaStore WIDTH/HEIGHT не выставляются (как и сейчас) — Provider пересчитает.

## Валидация
1. `./gradlew assembleDebug`.
2. `./gradlew testDebugUnitTest` через навык `android-test-suite`.
3. Ручная проверка: сжатие фото с пресетом 1920 → результат по большей стороне 1920; Original → размеры совпадают с исходником.
4. После изменения runtime-кода — собрать и опубликовать APK через навык `apk` (правило AGENTS.md).
