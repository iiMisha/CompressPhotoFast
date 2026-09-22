# План: перенос улучшений скилла apk из RogaineHelper (докачка, многопоточный сервер, скан портов)

## Контекст

В RogaineHelper скилл `apk` новее: коммит `d3ef5967 fix(apk): многопоточный сервер и Range-докачка в share_nosudo` (+ последующие правки портов). Версия CompressPhotoFast этих улучшений не имеет.

Источник: `/home/misha/RogaineHelper/.agents/skills/apk/scripts/share_nosudo.py` (666 строк, ред. Sep 14).
Цель: `/home/misha/CompressPhotoFast/.agents/skills/apk/scripts/share_nosudo.py` (513 строк) + `SKILL.md` рядом.

### Чего нет в CPF-версии (переносим)

1. **Range-докачка** в `ApkHandler` (RH строки 481–578):
   - `timeout = 300` — обрыв «мёртвых» соединений по сокет-таймауту;
   - `guess_type()` override — явный MIME `application/vnd.android.package-archive`;
   - `send_head()` с разбором `Range: bytes=start-end` и `bytes=-suffix` (regex `^bytes=(\d*)-(\d*)$`): 206 + `Content-Range`, 416 + `Content-Range: bytes */size` при start ≥ size / start > end; multipart-диапазоны не матчатся → 200 целиком;
   - `copyfile()` — отдача ровно `end-start+1` байт чанками по 64 КБ (`self._range_bytes`).
2. **Многопоточный сервер** в `serve()`: `ReusableThreadingTCPServer = ThreadingMixIn + TCPServer` с `daemon_threads = True` (RH строки 594–597) — зависший клиент не блокирует новые запросы.
3. **Скан свободного порта**: `find_available_port(preferred_port)` (RH строки 207–227), `PORT_SCAN_LIMIT = 20`, диапазон от `--port` (по умолчанию 8080); предупреждение «⚠️ Порт N занят, использую…» в `publish()` (RH строки 315–318).
4. **URL для release**: `apk_filename = "app-release.apk" if release else APK_FILENAME` — в CPF сейчас симлинк и URL всегда `app-debug.apk`, даже для `--release` (баг; RH строки 284–285, 327, 333).

### Специфика CPF, которую СОХРАНЯЕМ (не трогаем при адаптации)

- Динамический `PROJECT_DIR = Path(__file__).resolve().parents[4]` (у RH хардкод `/home/misha/RogaineHelper`).
- Variant-based API: `build_apk(variant)`, `find_apk(variant)`, `apk_output_dir(variant)`, `publish(..., variant=...)`.
- Суффиксированные корни: `~/apk-share-compressphotofast` и `~/.local/share/apk-share-compressphotofast` — обязательно: оба скилла живут на одном VPS, общий корень означал бы взаимное «убийство» ссылок.
- Имена загрузки `CompressPhotoFast-{variant}-YYYYMMDD-HHMMSS.apk`.

### Чего из RH НЕ переносим (неприменимо к CPF)

- Compact arm64-билд `-PabiList=arm64-v8a -PcompressNativeLibs=true` и `is_arm64_only_compressed()` (+ `import zipfile`): CPF — чистый Kotlin/Java без `.so`, обычный `assembleDebug/Release`.
- Спец-разделы SKILL.md RogaineHelper: slim-ядро OpenCV, различия пакетов/keystore/Yandex OAuth, янтарная иконка debug.

## Изменения

### 1. `scripts/share_nosudo.py` (адаптированный перенос)

По блокам, сохраняя CPF-стиль (variant-API, комментарии на русском):

1. Импорты: добавить `import re` (для разбора Range). `zipfile` не добавлять.
2. Константы: добавить `PORT_SCAN_LIMIT = 20`.
3. Новая функция `find_available_port(preferred_port)` — копия RH 207–227 (probe-bind на `0.0.0.0` c `SO_REUSEADDR`, die при исчерпании диапазона).
4. `publish()`:
   - перед `SHARE_ROOT.mkdir`: `requested_port = port; port = find_available_port(port)` + предупреждение при смене порта;
   - `apk_filename = "app-release.apk" if variant == "release" else APK_FILENAME`; использовать его для симлинка `link = token_dir / apk_filename` и в `url`;
   - в `--serve` Popen порт уже передаётся фактический — после скана это выбранный порт (как в RH).
5. `ApkHandler` — расширить до RH-версии 481–578: docstring «Отдаёт APK с корректным MIME и force-download; без листинга директорий. Поддержка Range (bytes=start-end / bytes=-suffix, одиночный диапазон) — докачка после сбоя сети вместо перезапуска с нуля; multipart игнорируем (отдаём 200 целиком)»; `timeout = 300`; `self._range_bytes = None` в `__init__`; `guess_type()`; `send_head()` (200/206/416, `Content-Length`, `Last-Modified`, `f.seek(start)`); `copyfile()` c `remaining`-циклом. CPF-специфика: дефолт `download_name="CompressPhotoFast-debug.apk"` как сейчас.
6. `serve()`: заменить `ReusableTCPServer` на `ReusableThreadingTCPServer` (`ThreadingMixIn`, `TCPServer`, `allow_reuse_address = True`, `daemon_threads = True`) c комментарием «поток на соединение: зависший клиент не блокирует другие запросы».

`verify_url()` уже использует `--range 0-0` — при 206 считается OK, не трогаем.

### 2. `SKILL.md`

- Frontmatter `description`: добавить «Поддержка докачки (HTTP Range)» после слов про одноразовую ссылку.
- Таблица режима: «Порт | первый свободный начиная с 8080 (`--port` меняет начало диапазона)».
- Абзац про порт: «поднимает … на первом свободном непривилегированном порту, начиная с 8080. Если 8080 занят, скрипт автоматически переключается на следующий свободный порт (диапазон из 20) и печатает его в URL».
- URL: добавить «(для `--release` — `app-release.apk`)».
- Секцию «Поведение и гарантии» дополнить пунктами:
  - «**Докачка (Range):** одиночные диапазоны `bytes=start-end` / `bytes=-N` → 206 + `Content-Range`; невалидный диапазон → 416; multipart → 200 целиком. Обрыв связи не заставляет качать с нуля.»
  - «**Занятый порт:** авто-выбор следующего свободного из диапазона `--port..+19`; URL и метаданные используют фактический порт.»
  - «**Многопоточный сервер:** поток на соединение; зависший клиент (сокет-таймаут 300 с) не блокирует параллельные запросы.»
- «Требования к окружению»: исправить устаревшее «Корень проекта ожидается в /home/misha/Документы/...» на «Корень проекта определяется автоматически по расположению скрипта (`parents[4]`); запускать из checkout проекта». Порт: «свободный порт из диапазона 8080..8099 (или от `--port`), открытый снаружи».
- Troubleshooting: добавить «Если автоматически выбранный порт закрыт внешним файрволом, открыть диапазон 8080..8099 или задать разрешённый порт через `--port`».

## Валидация

1. `python3 -m py_compile .agents/skills/apk/scripts/share_nosudo.py`
2. `.agents/skills/apk/scripts/share_nosudo.py --list` — не падает (пусто или активные ссылки CPF).
3. Энд-ту-энд с `--no-build` (или полной сборкой debug):
   - ссылка поднялась, `verify_url` OK;
   - `curl -s -o /dev/null -w '%{http_code}' -r 0-0 <url>` → **206**;
   - `curl -s -r 100-199 <url> | cmp - <(dd if=<apk> bs=1 skip=100 count=100 2>/dev/null)` — байты совпадают;
   - `curl -s -o /dev/null -w '%{http_code}' -H 'Range: bytes=999999999-' <url>` → **416**;
   - `curl -s -o /dev/null -w '%{http_code}' <url>` (без Range) → **200**;
   - полный `curl <url>` побайтово равен APK (`cmp`);
   - повторное открытие URL при активной недокачанной загрузке не блокируется (threading).
4. `--stop-all` убирает ссылку; каталоги/метаданные RogaineHelper (`~/apk-share`, `~/.local/share/apk-share`) не затронуты (суффиксированные корни).

## Вне scope

- Изменения `AGENTS.md` (интерфейс скилла не меняется).
- Compact-билды, slim-OpenCV, что-либо из RH-специфики.
- Коммит/пуш (только по явному запросу пользователя).
