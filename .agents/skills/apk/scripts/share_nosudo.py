#!/usr/bin/env python3
"""
share_nosudo.py — Собрать APK CompressPhotoFast (по умолчанию debug, --release —
release-вариант) и опубликовать временную ссылку на скачивание БЕЗ
root/nginx/systemd.

Режим поднимает встроенный Python HTTP-сервер на непривилегированном порту
(8080 по умолчанию), БЕЗ root/nginx/systemd. APK отдаётся по неугадываемому
одноразовому URL:
    http://<host>:<port>/<token>/app-debug.apk
где <token> = 32 hex-символа. Листинг директорий запрещён, чужие пути → 404.
APK отдаётся с корректным MIME и Content-Disposition: attachment.

Реализован TTL: серверный процесс сам завершается и чистит каталог по
истечении срока (поток-таймер внутри процесса; systemd НЕ используется).

CompressPhotoFast — чистое Kotlin/Java приложение без нативных библиотек
(нет NDK/OpenCV/.so), поэтому compact-билд (arm64-only + сжатые .so) здесь
не нужен: собирается обычный `:app:assembleDebug`, APK получается небольшим.

Требования:
  * НЕ нужен root.
  * Непривилегированный порт (по умолчанию 8080) должен быть свободен локально
    и ОТКРЫТ снаружи (файрвол/группа безопасности хостера). Порт 80 не трогаем.
  * Публичный IPv4 определяется автоматически (VPS без NAT) или задаётся --host.

Примеры:
    ./share_nosudo.py                  # собрать + опубликовать debug, TTL 1ч
    ./share_nosudo.py --release        # release-вариант вместо debug
    ./share_nosudo.py --no-build       # переиспользовать свежий APK
    ./share_nosudo.py --ttl 6h         # ссылка живёт 6 часов
    ./share_nosudo.py --port 9000      # другой порт
    ./share_nosudo.py --host build.example.com
    ./share_nosudo.py --list           # показать активные ссылки
    ./share_nosudo.py --stop <token>   # остановить конкретную ссылку
    ./share_nosudo.py --stop-all       # остановить все ссылки
"""

import argparse
import http.server
import json
import os
import shutil
import signal
import socket
import socketserver
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

# --- Константы путей ---------------------------------------------------------
PROJECT_DIR = Path("/home/misha/CompressPhotoFast")


def apk_output_dir(variant):
    return PROJECT_DIR / f"app/build/outputs/apk/{variant}"

# Всё в домашнем каталоге — не требует root.
# Имена суффиксированы проектом, чтобы не конфликтовать с одноимённым скиллом
# соседних проектов (оба скилла иначе делили бы один веб-корень/метаданные и
# убивали ссылки друг друга: «одна активная ссылка за раз»).
SHARE_ROOT = Path(os.path.expanduser("~/apk-share-compressphotofast"))  # веб-корень (сервер)
META_DIR = Path(os.path.expanduser("~/.local/share/apk-share-compressphotofast"))  # метаданные

DEFAULT_PORT = 8080
DEFAULT_TTL = "1h"
TOKEN_BYTES = 16  # 32 hex-символа
APK_FILENAME = "app-debug.apk"          # имя в URL (как в sudo-режиме)


# --- Базовые утилиты ---------------------------------------------------------
def run(cmd, check=True, capture=True, cwd=None):
    return subprocess.run(
        cmd, check=check,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
        text=True, cwd=cwd,
    )


def die(msg, code=1):
    print(f"❌ {msg}", file=sys.stderr)
    sys.exit(code)


def info(msg):
    print(msg, flush=True)


# --- Сборка и поиск APK ------------------------------------------------------
def build_apk(variant):
    task = f":app:assemble{'Debug' if variant == 'debug' else 'Release'}"
    info(f"🔨 Сборка {task} ...")
    run(["./gradlew", task],
        cwd=str(PROJECT_DIR), capture=False)
    info("✅ Сборка завершена")


def find_apk(variant):
    out_dir = apk_output_dir(variant)
    apks = sorted(out_dir.glob("*.apk"), key=lambda p: p.stat().st_mtime, reverse=True)
    if not apks:
        die(f"APK не найден в {out_dir}. Запустите без --no-build.")
    return apks[0]


def cleanup_old_apks(keep_path, variant):
    """Удалить все APK в каталоге вывода, КРОМЕ keep_path (самого свежего).

    Gradle обычно перезаписывает тот же файл, но при смене versionName/timestamp
    имени старые APK могут накапливаться и засорять каталог вывода — чистим их."""
    removed = []
    keep = keep_path.resolve() if keep_path else None
    for p in apk_output_dir(variant).glob("*.apk"):
        try:
            if keep is not None and p.resolve() == keep:
                continue
            p.unlink()
            removed.append(p.name)
        except OSError:
            pass
    return removed


# --- Токен, IP, TTL ----------------------------------------------------------
def make_token():
    return os.urandom(TOKEN_BYTES).hex()


def public_ip():
    """IP исходящего интерфейса (VPS без NAT = публичный). Fallback на ifconfig.me."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        if ip and not ip.startswith("127."):
            return ip
    except OSError:
        pass
    # Fallback: внешний echo-сервис.
    try:
        return run(["curl", "-s", "--max-time", "5", "ifconfig.me"]).stdout.strip()
    except Exception:
        die("Не удалось определить публичный IP. Задайте --host вручную.")


def parse_ttl(ttl):
    """'6h' -> 21600 (секунды). Суффиксы s/m/h/d."""
    m = ttl.strip().lower()
    mult = {"s": 1, "m": 60, "h": 3600, "d": 86400}
    if not m or m[-1] not in mult:
        die(f"Неверный TTL '{ttl}'. Формат: <число>[s|m|h|d], напр. 30m, 6h, 2d.")
    try:
        n = int(m[:-1])
    except ValueError:
        die(f"Неверный TTL '{ttl}'. Формат: <число>[s|m|h|d].")
    if n <= 0:
        die(f"TTL должен быть положительным (получено {ttl}).")
    return n * mult[m[-1]]


def fmt_ttl(seconds):
    if seconds % 86400 == 0:
        return f"{seconds // 86400}д"
    if seconds % 3600 == 0:
        return f"{seconds // 3600}ч"
    if seconds % 60 == 0:
        return f"{seconds // 60}мин"
    return f"{seconds}с"


def make_download_name(apk_path, variant):
    """Имя скачиваемого файла с датой/временем сборки APK (по mtime файла).

    Берётся mtime самого APK — соответствует моменту сборки, а не публикации:
    несколько переиспользований одного APK (--no-build) дают одинаковое имя,
    что честно (та же сборка). Время локальное (time.localtime)."""
    try:
        ts = apk_path.stat().st_mtime
    except OSError:
        ts = time.time()
    stamp = datetime.fromtimestamp(ts).strftime("%Y%m%d-%H%M%S")
    return f"CompressPhotoFast-{variant}-{stamp}.apk"


# --- Метаданные --------------------------------------------------------------
def now_iso():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def meta_path(token):
    return META_DIR / f"{token}.json"


def load_meta(token):
    p = meta_path(token)
    if not p.exists():
        return None
    return json.loads(p.read_text())


def save_meta(data):
    META_DIR.mkdir(parents=True, exist_ok=True)
    meta_path(data["token"]).write_text(json.dumps(data, indent=2, ensure_ascii=False))


def delete_meta(token):
    p = meta_path(token)
    if p.exists():
        p.unlink()


def pid_alive(pid):
    if not pid:
        return False
    try:
        os.kill(pid, 0)
        return True
    except (OSError, ProcessLookupError):
        return False


# --- Публикация --------------------------------------------------------------
def publish(apk_path, ttl_seconds, host, port, no_build, keep_old, variant="debug"):
    if not no_build:
        build_apk(variant)
        apk_path = find_apk(variant)
    else:
        if apk_path is None:
            apk_path = find_apk(variant)

    # По умолчанию при новом запуске убиваем все старые ссылки (одна активная
    # за раз — чтобы не плодить дубли/мёртвые ссылки на тот же билд) и чистим
    # каталог вывода от устаревших APK (оставляем самый свежий). Сначала purge
    # ссылок — их симлинки могут указывать на старые APK, которые затем удаляем.
    # Порядок build → purge: если сборка упадёт, старая рабочая ссылка сохранится.
    if not keep_old:
        n = purge_all_links()
        if n:
            info(f"🧹 Удалено старых ссылок: {n}")
        removed = cleanup_old_apks(apk_path, variant)
        if removed:
            info(f"🧹 Удалено старых APK: {len(removed)}")
            for name in removed:
                info(f"     - {name}")

    SHARE_ROOT.mkdir(parents=True, exist_ok=True)
    META_DIR.mkdir(parents=True, exist_ok=True)

    token = make_token()
    token_dir = SHARE_ROOT / token
    token_dir.mkdir(parents=True, exist_ok=True)
    # Симлинк (не копируем APK).
    link = token_dir / APK_FILENAME
    if link.exists() or link.is_symlink():
        link.unlink()
    link.symlink_to(apk_path.resolve())

    download_name = make_download_name(apk_path, variant)
    url = f"http://{host}:{port}/{token}/{APK_FILENAME}"
    meta = {
        "token": token,
        "pid": None,
        "port": port,
        "host": host,
        "url": url,
        "apk_path": str(apk_path),
        "download_name": download_name,
        "created": now_iso(),
        "ttl_seconds": ttl_seconds,
        "expires_at": datetime.fromtimestamp(
            time.time() + ttl_seconds, tz=timezone.utc
        ).isoformat(timespec="seconds"),
    }

    # Сохраним метаданные ДО запуска subprocess, чтобы дочерний процесс при
    # чтении meta-файла гарантированно видел корректный download_name.
    save_meta(meta)

    # Запускаем сервер отдельным процессом, отвязанным от терминала
    # (эквивалент nohup ... &) — переживает завершение этого скрипта/сессии агента.
    log_file = token_dir / "server.log"
    log = open(log_file, "w")
    # --serve принимает (TOKEN, TTL_SECONDS); порт и имя файла — отдельными флагами.
    # download_name (с датой/временем сборки) передаём явно через CLI, чтобы убрать
    # гонку между запуском subprocess и записью meta-файла на диск.
    proc = subprocess.Popen(
        [sys.executable, __file__, "--serve", token, str(ttl_seconds),
         "--port", str(port), "--download-name", download_name],
        cwd=str(SHARE_ROOT),
        stdout=log, stderr=log,
        start_new_session=True,  # detach от управляющего терминала
    )
    meta["pid"] = proc.pid
    save_meta(meta)

    time.sleep(0.6)  # дать серверу подняться
    ok = verify_url(url)
    size_mb = apk_path.stat().st_size / (1024 * 1024)

    print("=" * 60)
    print("📱 Ссылка на скачивание APK (без sudo, порт %d):" % port)
    print()
    print("    " + url)
    print()
    print(f"    Размер: {size_mb:.0f} МБ · TTL: {fmt_ttl(ttl_seconds)} · файл: {download_name}")
    print(f"    PID сервера: {proc.pid} (жив: {'да' if pid_alive(proc.pid) else 'НЕТ'})")
    print(f"    Лог: {log_file}")
    print("=" * 60)
    if not ok:
        print("⚠️  Локальная проверка URL не дала 2xx. Возможно, порт занят или")
        print("    закрыт снаружи. Проверьте с телефона; для удаления — --stop-all.")
    print("Команды:")
    print(f"    {sys.argv[0]} --list")
    print(f"    {sys.argv[0]} --stop {token}")
    print(f"    {sys.argv[0]} --stop-all")


def verify_url(url):
    """Локальный HTTP-запрос (диапазон 1 байт) — статус 2xx/3xx = OK."""
    try:
        r = run(["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}",
                 "--range", "0-0", "--max-time", "5", url])
        code = r.stdout.strip()
        return code and code[0] in ("2", "3")
    except Exception:
        return False


# --- Управление ссылками -----------------------------------------------------
def list_shares():
    if not META_DIR.exists():
        info("Активных ссылок нет.")
        return
    metas = []
    for p in sorted(META_DIR.glob("*.json")):
        try:
            metas.append(json.loads(p.read_text()))
        except Exception:
            continue
    if not metas:
        info("Активных ссылок нет.")
        return
    print(f"Активных ссылок: {len(metas)}")
    print("-" * 60)
    for m in metas:
        alive = pid_alive(m.get("pid"))
        print(f"  token: {m['token']}")
        print(f"    url : {m.get('url', '(нет)')}  [{'жив' if alive else 'МЁРТВ'}]")
        print(f"    pid : {m.get('pid')} · port {m.get('port')} · TTL {fmt_ttl(m.get('ttl_seconds', 0))}")
        if m.get('download_name'):
            print(f"    файл: {m['download_name']}")
        print(f"    создан: {m.get('created')} · истекает: {m.get('expires_at')}")
        print()


def stop_token(token):
    m = load_meta(token)
    if not m:
        die(f"Ссылка {token} не найдена в метаданных.")
    kill_pid(m.get("pid"))
    shutil.rmtree(SHARE_ROOT / token, ignore_errors=True)
    delete_meta(token)
    info(f"✅ Остановлено: {token}")


def purge_all_links():
    """Остановить ВСЕ активные ссылки: завершить серверы, удалить их каталоги и
    метаданные. Возвращает количество удалённых (без печати в вывод)."""
    if not META_DIR.exists():
        return 0
    n = 0
    for p in list(META_DIR.glob("*.json")):
        try:
            m = json.loads(p.read_text())
        except Exception:
            p.unlink(missing_ok=True)
            continue
        kill_pid(m.get("pid"))
        shutil.rmtree(SHARE_ROOT / m["token"], ignore_errors=True)
        p.unlink()
        n += 1
    return n


def stop_all():
    n = purge_all_links()
    if n == 0:
        info("Нечего останавливать.")
    else:
        info(f"✅ Остановлено ссылок: {n}")


def kill_pid(pid):
    if not pid or not pid_alive(pid):
        return
    try:
        os.killpg(os.getpgid(pid), signal.SIGTERM)
    except (OSError, ProcessLookupError):
        try:
            os.kill(pid, signal.SIGTERM)
        except (OSError, ProcessLookupError):
            pass


# --- Серверный режим (внутренний, --serve) ----------------------------------
class ApkHandler(http.server.SimpleHTTPRequestHandler):
    """Отдаёт APK с корректным MIME и force-download; без листинга директорий."""

    def __init__(self, *a, directory, download_name="CompressPhotoFast-debug.apk", **kw):
        self.download_name = download_name
        super().__init__(*a, directory=str(directory), **kw)

    def list_directory(self, path):
        # Запрет листинга любых директорий.
        self.send_error(404, "No listing")
        return None

    def end_headers(self):
        # Content-Type для .apk базовый guess_type() уже выставляет верно
        # (application/vnd.android.package-archive) — добавляем только форс-скачивание.
        if self.path.endswith(".apk"):
            self.send_header("Content-Disposition", f'attachment; filename="{self.download_name}"')
        self.send_header("Access-Control-Allow-Origin", "*")
        super().end_headers()

    def log_message(self, fmt, *args):
        # Краткий лог в server.log.
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))


def serve(token, port, ttl_seconds, download_name=None):
    """Целевой режим дочернего процесса. Крутит HTTP-сервер + TTL-поток."""
    token_dir = SHARE_ROOT / token
    if not token_dir.exists():
        die(f"[serve] каталог {token_dir} не существует")
    meta = load_meta(token) or {}
    # Имя файла приходит из publish через CLI (гарантированно, без гонки с
    # чтением meta-файла); fallback на meta — для надёжности.
    download_name = download_name or meta.get("download_name") or "CompressPhotoFast-debug.apk"

    class ReusableTCPServer(socketserver.TCPServer):
        allow_reuse_address = True

    httpd = ReusableTCPServer(
        ("0.0.0.0", port),
        lambda *a, **kw: ApkHandler(*a, directory=SHARE_ROOT, download_name=download_name, **kw),
    )

    def ttl_killer():
        time.sleep(ttl_seconds)
        sys.stderr.write(f"[serve] TTL {fmt_ttl(ttl_seconds)} истёк — завершаюсь и очищаю {token}\n")
        try:
            httpd.shutdown()
        except Exception:
            pass
        shutil.rmtree(token_dir, ignore_errors=True)
        delete_meta(token)
        os._exit(0)

    threading.Thread(target=ttl_killer, daemon=True).start()
    sys.stderr.write(f"[serve] {token_dir} на :{port}, TTL {fmt_ttl(ttl_seconds)}\n")
    sys.stderr.flush()
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass


# --- CLI ---------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser(
        description="Опубликовать APK CompressPhotoFast (debug по умолчанию, --release) без sudo (HTTP на неприв. порту).",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("--release", action="store_true",
                    help="собрать/опубликовать release-вариант (assembleRelease, apk/release/); по умолчанию debug")
    ap.add_argument("--no-build", action="store_true", help="не собирать, взять свежий APK выбранного варианта")
    ap.add_argument("--keep", action="store_true",
                    help="сохранить старые ссылки и старые APK (по умолчанию удаляются)")
    ap.add_argument("--ttl", default=DEFAULT_TTL, help="срок жизни (напр. 30m, 6h, 2d). По умолчанию 1h")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"порт HTTP (по умолчанию {DEFAULT_PORT})")
    ap.add_argument("--host", default=None, help="хост/домен в URL (по умолчанию авто-определение IP)")
    ap.add_argument("--list", action="store_true", help="показать активные ссылки и выйти")
    ap.add_argument("--stop", metavar="TOKEN", help="остановить конкретную ссылку")
    ap.add_argument("--stop-all", action="store_true", help="остановить все ссылки")
    ap.add_argument("--serve", nargs=2, metavar=("TOKEN", "TTL_SECONDS"),
                    help=argparse.SUPPRESS)  # внутренний режим дочернего процесса
    ap.add_argument("--download-name", default=None, help=argparse.SUPPRESS)
    args = ap.parse_args()

    if args.serve:
        serve(args.serve[0], args.port, int(args.serve[1]), args.download_name)
        return
    if args.stop_all:
        stop_all(); return
    if args.stop:
        stop_token(args.stop); return
    if args.list:
        list_shares(); return

    host = args.host or public_ip()
    ttl_seconds = parse_ttl(args.ttl)
    variant = "release" if args.release else "debug"
    publish(None, ttl_seconds, host, args.port, args.no_build, args.keep, variant)


if __name__ == "__main__":
    main()
