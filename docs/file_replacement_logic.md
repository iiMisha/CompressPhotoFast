# Логика замены оригинальных файлов в Android

В данном документе описываются механизмы, которые использует приложение для обхода ограничений файловой системы Android и замены оригинальных файлов новыми, сжатыми версиями.

## 1. Использование ContentResolver и MediaStore API

Одним из основных механизмов является использование ContentResolver и MediaStore API, которые позволяют приложению взаимодействовать с системным хранилищем медиафайлов:

```java
ContentValues contentValues = new ContentValues(1);
contentValues.put("description", "Accusoft Cram");
if (getContentResolver().update(withAppendedId, contentValues, (String) null, (String[]) null) != 1) {
    Log.e("ReduceImageService", "Unable to update image description");
}
```

Этот код демонстрирует, как приложение обновляет метаданные файла через ContentResolver. После сжатия изображения обновляется его описание, что позволяет отметить его как обработанное.

## 2. Storage Access Framework (SAF)

Для работы с файлами, особенно на внешних хранилищах после Android 4.4, приложение использует Storage Access Framework:

```java
@TargetApi(21)
private void v() {
    if (w()) {
        getContentResolver().takePersistableUriPermission(this.P, 3);
        a(this.O);
        return;
    }
    new e().a(f(), "ExternalMediaRootNotAuthorizedDialogFragment");
}
```

Метод `takePersistableUriPermission` используется для получения долгосрочных разрешений на доступ к определенному URI, что позволяет приложению работать с файлами даже после перезапуска.

## 3. Оповещение системы о новых файлах

После создания новых файлов приложение уведомляет MediaScanner о необходимости проиндексировать их:

```java
MediaScannerConnection.scanFile(getApplicationContext(), 
    new String[]{file3.getAbsolutePath()}, 
    (String[]) null, 
    new MediaScannerConnection.OnScanCompletedListener() {
        public void onScanCompleted(String str, Uri uri) {
        }
    });
```

Это позволяет системе Android обнаружить новые файлы и добавить их в базу данных медиафайлов, что делает их видимыми для других приложений.

## 4. Нативные методы для обработки изображений

Приложение использует нативные (JNI) методы для фактической замены содержимого файлов:

```java
public native int AimToolsRequantNative(String str, String str2, int i2);
```

Этот метод принимает путь к исходному файлу и путь к целевому файлу, выполняя сжатие и замену на нативном уровне, что обеспечивает высокую производительность и обход некоторых ограничений Java API.

## 5. Работа с разрешениями

Приложение тщательно проверяет наличие необходимых разрешений в зависимости от версии Android:

```java
if (Build.VERSION.SDK_INT >= 23) {
    int a2 = android.support.v4.a.a.a(getApplicationContext(), 
        "android.permission.WRITE_EXTERNAL_STORAGE");
    if (a2 != 0) {
        android.support.v4.app.a.a(this, 
            new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"}, 2);
        return true;
    }
}
```

Для Android 6.0 и выше используется система запроса разрешений во время выполнения, что позволяет получить доступ к записи во внешнее хранилище.

## 6. Адаптация к разным версиям Android

Приложение адаптируется к различным версиям Android, используя совместимые API:

```java
if (Build.VERSION.SDK_INT >= 21) {
    startActivityForResult(new Intent("android.intent.action.OPEN_DOCUMENT_TREE"), 3);
}
```

Для Android 5.0+ используется новый интент `OPEN_DOCUMENT_TREE`, который позволяет пользователю выбрать директорию для работы с файлами.

## 7. Использование DocumentFile API

Для работы с файлами через SAF приложение использует DocumentFile API:

```java
android.support.v4.c.a a3 = android.support.v4.c.a.a(this, this.P);
```

Этот API предоставляет единый интерфейс для работы с файлами, независимо от того, находятся ли они на внутреннем или внешнем хранилище.

## Заключение

Комбинация этих подходов позволяет приложению эффективно обходить ограничения файловой системы Android и заменять оригинальные файлы при сжатии изображений. Приложение учитывает различные версии Android и адаптирует свое поведение соответствующим образом, что обеспечивает совместимость с широким спектром устройств.

Важным аспектом является также маркировка обработанных изображений через метаданные и SharedPreferences, что позволяет приложению отслеживать уже обработанные файлы и избегать повторной обработки. 