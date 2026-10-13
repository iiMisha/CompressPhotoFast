#!/usr/bin/env python3
"""
log_receiver.py — Постоянный HTTP-приёмник дампов отладочных логов CompressPhotoFast.

Принимает POST /log с телом (text/plain) и заголовком X-Log-Token и АТОМАРНО
перезаписывает им файл log в каталоге проекта. Авторизация — общий секрет
(токен), который приложение и сервер должны знать одновременно.

Реализован как ПОСТОЯННЫЙ демон (без TTL): поднимается один раз и крутится, пока
его не остановят (--stop) или не перезагрузят VPS. Без root/nginx/systemd —
встроенный http.server Python на непривилегированном порту (8093 по умолчанию).

Конфиг: ~/.config/compressphotofast/log_receiver.json с ключами token/port/log_path.
При первом запуске (если конфига нет) генерируется случайный токен (32 hex),
конфиг создаётся, токен печатается — его надо вставить в приложение
(меню → «Сервер логов»).

Эндпоинты:
  POST /log   заголовок X-Log-Token + body -> атомарная перезапись log_path.
              200 {"ok":true,"bytes":N} | 401 (плохой токен) | 413 (слишком большой body).
  GET  /health -> 200 {"ok":true} (без авторизации; проверка живости).
  прочее -> 404/405.

Безопасность: фиксированный путь вывода (имя файла не от клиента), кап размера
body (MAX_BODY_BYTES), сравнение токена const-time (hmac.compare_digest),
без листингов, токен вне веб-корня.

Примеры:
    python3 tools/log_receiver.py              # запустить как демон (один раз)
    python3 tools/log_receiver.py --foreground # не отвязываться (для systemd)
    python3 tools/log_receiver.py --status     # PID/жив/порт/префикс токена
    python3 tools/log_receiver.py --stop       # остановить демон
    python3 tools/log_receiver.py --print-token# вывести токен (для приложения)
    python3 tools/log_receiver.py --port 9000  # сменить порт (перезапуск)

Пример systemd-unit (опц., для автозапуска после ребута):
    [Unit]
    Description=CompressPhotoFast log receiver
    After=network.target

    [Service]
    Type=simple
    ExecStart=/usr/bin/python3 /home/misha/CompressPhotoFast/tools/log_receiver.py --foreground
    Restart=always
    RestartSec=3
    User=misha

    [Install]
    WantedBy=default.target
"""

import argparse
import hmac
import http.server
import json
import os
import secrets
import signal
import socketserver
import subprocess
import sys
import threading
from datetime import datetime
from pathlib import Path

# --- Константы путей ---------------------------------------------------------
PROJECT_DIR = Path("/home/misha/CompressPhotoFast")
DEFAULT_LOG_PATH = PROJECT_DIR / "log"

# Всё в домашнем каталоге — не требует root.
CONFIG_DIR = Path(os.path.expanduser("~/.config/compressphotofast"))
CONFIG_PATH = CONFIG_DIR / "log_receiver.json"
STATE_DIR = Path(os.path.expanduser("~/.local/state/compressphotofast"))
PID_PATH = STATE_DIR / "log_receiver.pid"
SERVER_LOG_DIR = Path(os.path.expanduser("~/log_receiver"))
SERVER_LOG_PATH = SERVER_LOG_DIR / "compressphotofast.log"

DEFAULT_PORT = 8093
TOKEN_BYTES = 16  # 32 hex-символа
MAX_BODY_BYTES = 8 * 1024 * 1024  # 8 МБ кап тела запроса


# --- Базовые утилиты ---------------------------------------------------------
def die(msg, code=1):
    print(f"❌ {msg}", file=sys.stderr)
    sys.exit(code)


def info(msg):
    print(msg, flush=True)


def now_ts():
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def make_token():
    return secrets.token_hex(TOKEN_BYTES)


def pid_alive(pid):
    if not pid:
        return False
    try:
        os.kill(pid, 0)
        return True
    except (OSError, ProcessLookupError):
        return False


def read_pid():
    try:
        return int(PID_PATH.read_text().strip())
    except (OSError, ValueError):
        return None


def write_pid(pid):
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    PID_PATH.write_text(str(pid))


def clear_pid():
    try:
        PID_PATH.unlink()
    except FileNotFoundError:
        pass


# --- Конфиг ------------------------------------------------------------------
def default_config():
    return {
        "token": make_token(),
        "port": DEFAULT_PORT,
        "log_path": str(DEFAULT_LOG_PATH),
    }


def load_config():
    """Читает конфиг; если отсутствует — создаёт с дефолтами и печатает токен.

    Возвращает (config, created). created=True означает, что токен свежесгенерирован
    и его надо показать пользователю для вставки в приложение."""
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    if not CONFIG_PATH.exists():
        cfg = default_config()
        save_config(cfg)
        return cfg, True
    try:
        cfg = json.loads(CONFIG_PATH.read_text())
    except Exception as e:
        die(f"Конфиг {CONFIG_PATH} повреждён ({e}). Удалите его и запустите снова.")
    # Добиваем отсутствующие ключи дефолтами (миграция старых/неполных конфигов).
    changed = False
    for k, v in default_config().items():
        if k == "token":
            continue
        if k not in cfg:
            cfg[k] = v
            changed = True
    if not cfg.get("token"):
        cfg["token"] = make_token()
        changed = True
    if changed:
        save_config(cfg)
    return cfg, False


def save_config(cfg):
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    # Атомарная запись конфига (temp+rename), чтобы не потерять при падении.
    tmp = CONFIG_PATH.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(cfg, indent=2, ensure_ascii=False))
    os.replace(tmp, CONFIG_PATH)


# --- HTTP-обработчик ---------------------------------------------------------
class LogReceiverHandler(http.server.BaseHTTPRequestHandler):
    server_version = "CompressPhotoFastLogReceiver/1.0"
    protocol_version = "HTTP/1.1"

    # token/log_path прокидываются фабрикой при создании сервера.
    expected_token = ""
    log_path = DEFAULT_LOG_PATH

    # ---- ответы -------------------------------------------------------------
    def _respond(self, code, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def _log_req(self, status, nbytes=0):
        sys.stderr.write(
            f"{now_ts()} {self.client_address[0]} "
            f"{self.command} {self.path} -> {status} ({nbytes} bytes)\n"
        )
        sys.stderr.flush()

    # ---- маршрутизация ------------------------------------------------------
    def do_GET(self):
        if self.path == "/health":
            self._respond(200, {"ok": True})
            self._log_req(200)
            return
        self._respond(404, {"error": "not found"})
        self._log_req(404)

    def do_POST(self):
        if self.path != "/log":
            self._respond(404, {"error": "not found"})
            self._log_req(404)
            return

        # Авторизация: токен в заголовке, const-time сравнение.
        token = self.headers.get("X-Log-Token", "")
        if not token or not hmac.compare_digest(token, self.expected_token):
            self._drain_body()
            self._respond(401, {"error": "bad token"})
            self._log_req(401)
            return

        # Чтение тела с капом размера.
        try:
            length = int(self.headers.get("Content-Length", "0") or "0")
        except ValueError:
            length = 0
        if length < 0 or length > MAX_BODY_BYTES:
            self._drain_body()
            self._respond(413, {"error": "payload too large"})
            self._log_req(413)
            return
        body = self.rfile.read(length) if length > 0 else b""

        # Атомарная перезапись log_path (temp+rename).
        log_file = Path(self.log_path)
        try:
            log_file.parent.mkdir(parents=True, exist_ok=True)
            tmp = log_file.with_name(log_file.name + ".tmp")
            with open(tmp, "wb") as f:
                f.write(body)
            os.replace(tmp, log_file)
        except OSError as e:
            self._respond(500, {"error": f"write failed: {e}"})
            self._log_req(500, len(body))
            return

        self._respond(200, {"ok": True, "bytes": len(body), "path": str(log_file)})
        self._log_req(200, len(body))

    def _drain_body(self):
        """Считать и отбросить тело, чтобы клиент не получил RST на непрочитанном сокете."""
        try:
            length = int(self.headers.get("Content-Length", "0") or "0")
        except ValueError:
            return
        if length > 0:
            try:
                self.rfile.read(min(length, MAX_BODY_BYTES))
            except OSError:
                pass

    def log_message(self, fmt, *args):
        # Тихо: лог запросов пишем сами в _log_req.
        pass


class ReusableThreadingHTTPServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


# --- Серверный режим (foreground) --------------------------------------------
def run_forever(cfg):
    LogReceiverHandler.expected_token = cfg["token"]
    LogReceiverHandler.log_path = Path(cfg["log_path"])
    port = int(cfg["port"])

    httpd = ReusableThreadingHTTPServer(("0.0.0.0", port), LogReceiverHandler)
    write_pid(os.getpid())

    # Аккуратное завершение по SIGTERM/SIGINT (systemd / --stop через killpg).
    # ВАЖНО: httpd.shutdown() из того же потока, где крутится serve_forever(),
    # дедлокит процесс (документированное требование вызывать shutdown() из
    # другого потока) — порт оставался занят после --stop. Вместо этого
    # выбрасываем SystemExit: он прерывает serve_forever и заходит в finally
    # (clear_pid), завершая процесс чисто.
    def _shutdown(signum, frame):
        raise SystemExit(0)

    signal.signal(signal.SIGTERM, _shutdown)
    signal.signal(signal.SIGINT, _shutdown)

    log_file = Path(cfg["log_path"])
    sys.stderr.write(
        f"[log_receiver] слушаю 0.0.0.0:{port} -> {log_file} "
        f"(токен: {cfg['token'][:8]}…)\n"
    )
    sys.stderr.flush()
    try:
        httpd.serve_forever()
    finally:
        clear_pid()


# --- Управление демоном ------------------------------------------------------
def start_daemon(cfg, force=False):
    existing = read_pid()
    if existing and pid_alive(existing) and not force:
        info(f"⚠️  Демон уже запущен (PID {existing}). Используйте --stop или --force.")
        return

    SERVER_LOG_DIR.mkdir(parents=True, exist_ok=True)
    log = open(SERVER_LOG_PATH, "a")
    # Отвязываемся от терминала (эквивалент nohup) — переживает завершение
    # этого скрипта/сессии агента/SSH.
    proc = subprocess.Popen(
        [sys.executable, __file__, "--foreground"],
        cwd=str(PROJECT_DIR),
        stdout=log, stderr=log,
        start_new_session=True,
    )
    # Даём форграунд-процессу записать свой PID-файл.
    import time
    for _ in range(20):
        pid = read_pid()
        if pid and pid_alive(pid):
            break
        time.sleep(0.1)

    pid = read_pid() or proc.pid
    info("=" * 60)
    info(f"✅ Демон-приёмник логов запущен (PID {pid})")
    info(f"   Порт: {cfg['port']}    Файл: {cfg['log_path']}")
    info(f"   Токен: {cfg['token']}")
    info(f"   Лог сервера: {SERVER_LOG_PATH}")
    info(f"   URL для приложения: http://<IP-VPS>:{cfg['port']}")
    info("   Управление: --status / --stop / --print-token")
    info("=" * 60)


def stop_daemon():
    pid = read_pid()
    if not pid or not pid_alive(pid):
        clear_pid()
        info("Демон не запущен.")
        return
    try:
        os.killpg(os.getpgid(pid), signal.SIGTERM)
    except (OSError, ProcessLookupError):
        try:
            os.kill(pid, signal.SIGTERM)
        except (OSError, ProcessLookupError):
            pass
    clear_pid()
    info(f"✅ Демон остановлен (PID {pid}).")


def show_status(cfg):
    pid = read_pid()
    alive = bool(pid and pid_alive(pid))
    print(f"PID: {pid}  [{'жив' if alive else 'МЁРТВ/не запущен'}]")
    print(f"Порт: {cfg['port']}")
    print(f"Файл: {cfg['log_path']}")
    print(f"Токен: {cfg['token']}  (префикс {cfg['token'][:8]}…)")
    print(f"URL приложения: http://<IP-VPS>:{cfg['port']}")


# --- CLI ---------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser(
        description="Постоянный HTTP-приёмник дампов логов CompressPhotoFast (без sudo).",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("--foreground", action="store_true",
                    help="не отвязываться от терминала (для systemd)")
    ap.add_argument("--port", type=int, default=None,
                    help=f"порт HTTP (перезаписывает конфиг; по умолчанию {DEFAULT_PORT})")
    ap.add_argument("--log-path", default=None,
                    help=f"путь к log (перезаписывает конфиг; по умолчанию {DEFAULT_LOG_PATH})")
    ap.add_argument("--reset-token", action="store_true",
                    help="сгенерировать новый токен (старый перестанет работать)")
    ap.add_argument("--status", action="store_true", help="статус демона и выйти")
    ap.add_argument("--stop", action="store_true", help="остановить демон и выйти")
    ap.add_argument("--print-token", action="store_true",
                    help="вывести токен (для вставки в приложение) и выйти")
    ap.add_argument("--force", action="store_true",
                    help="перезапустить, даже если уже запущен")
    args = ap.parse_args()

    cfg, created = load_config()

    # Применение переопределений к конфигу (перед любым запуском/выводом).
    if args.port is not None:
        cfg["port"] = args.port
        save_config(cfg)
    if args.log_path is not None:
        cfg["log_path"] = str(Path(args.log_path).expanduser())
        save_config(cfg)
    if args.reset_token:
        cfg["token"] = make_token()
        save_config(cfg)
        info(f"🔑 Новый токен: {cfg['token']}")

    if args.status:
        show_status(cfg)
        return
    if args.stop:
        stop_daemon()
        return
    if args.print_token:
        info(cfg["token"])
        return

    if args.foreground:
        run_forever(cfg)
        return

    if created:
        info("🔑 Сгенерирован токен (новая установка). Скопируйте его в приложение:")
        info(f"   {cfg['token']}")
        info("   (меню → «Сервер логов»). Повторно: --print-token")

    start_daemon(cfg, force=args.force)


if __name__ == "__main__":
    main()
