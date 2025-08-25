# CLAUDE.md

Этот файл предоставляет руководство для Claude Code (claude.ai/code) при работе с кодом в данном репозитории.

## Обзор проекта

CompressPhotoFast — это Android приложение (API 29+) для быстрого и эффективного сжатия фотографий. Приложение использует Kotlin с современными архитектурными компонентами Android, включая Hilt для внедрения зависимостей, WorkManager для фоновой обработки и паттерн MVVM.

## Команды сборки

- `./gradlew assembleDebug` - сборка debug версии
- `./gradlew assembleRelease` - сборка release версии  
- `./gradlew clean` - очистка артефактов сборки

## Архитектура кода

### Основные компоненты

- **Класс приложения**: `CompressPhotoApp.kt` - точка входа Hilt приложения с конфигурацией WorkManager и настройкой каналов уведомлений
- **Внедрение зависимостей**: Единственный `AppModule.kt` предоставляет экземпляры SharedPreferences, DataStore и WorkManager
- **Главная активность**: `MainActivity.kt` - обрабатывает UI, обработку интентов (ACTION_SEND/ACTION_SEND_MULTIPLE) и управление фоновыми службами
- **ViewModel**: `MainViewModel.kt` - управляет состоянием UI, настройками и оркестрацией обработки изображений

### Архитектура фоновой обработки

Приложение использует многоуровневую систему фоновой обработки:

1. **WorkManager**: `ImageCompressionWorker.kt` - выполняет фактическую работу по сжатию изображений
2. **Foreground Service**: `BackgroundMonitoringService.kt` - поддерживает приложение активным для непрерывного мониторинга
3. **JobService**: `ImageDetectionJobService.kt` - планирует периодическую работу
4. **Boot Receiver**: `BootCompletedReceiver.kt` - перезапускает службы при загрузке устройства

### Основные утилиты

- **Обработка изображений**: `ImageCompressionUtil.kt`, `ImageProcessingUtil.kt`, `SequentialImageProcessor.kt`
- **Файловые операции**: `FileOperationsUtil.kt`, `UriUtil.kt` - обрабатывает операции MediaStore и обработку URI
- **Управление настройками**: `SettingsManager.kt` - настройки на основе DataStore
- **Разрешения**: `PermissionsManager.kt` - обработка runtime разрешений
- **Логирование**: `LogUtil.kt` - централизованное логирование с Timber

### Ключевые паттерны

- Использует Hilt для внедрения зависимостей повсеместно
- Паттерн MVVM с ViewBinding
- Корутины + Flow для асинхронных операций
- MediaStore API для файловых операций (Android 10+ scoped storage)
- BroadcastReceiver для межкомпонентной коммуникации

### Обработка интентов

Приложение обрабатывает изображения, переданные из других приложений через интенты ACTION_SEND и ACTION_SEND_MULTIPLE, поддерживая пакетную обработку нескольких изображений.

## Зависимости

Используемые ключевые библиотеки:
- Dagger Hilt (внедрение зависимостей)
- WorkManager (фоновые задачи)
- Coil (загрузка изображений)
- Compressor (сжатие изображений)
- DataStore (настройки)
- Timber (логирование)
- ExifInterface (метаданные)

## Правила
- Отвечать на русском языке.
- Пользователь использует Linux Mint.
- Сборку производить с таймаутом 600 секунд.
- При серьезных изменениях в коде необходимо производить сборку проекта: `./gradlew assembleDebug`