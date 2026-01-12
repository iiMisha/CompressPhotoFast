#!/bin/bash

# CompressPhotoFast CLI Launcher for Linux/macOS

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV_DIR="$SCRIPT_DIR/venv"

# Проверка существования venv
if [ ! -d "$VENV_DIR" ]; then
    echo "🔧 Creating virtual environment..."
    python3 -m venv "$VENV_DIR" || {
        echo "❌ Failed to create virtual environment"
        echo "Please ensure Python 3.10+ is installed"
        exit 1
    }

    echo "📦 Installing CompressPhotoFast CLI and dependencies..."
    cd "$SCRIPT_DIR"

    # Обновляем pip
    "$VENV_DIR/bin/pip" install --upgrade pip -q

    # Устанавливаем пакет
    "$VENV_DIR/bin/pip" install -e . || {
        echo "❌ Failed to install package"
        exit 1
    }

    echo "✅ Installation complete!"
    echo ""
fi

# Запускаем CLI
exec "$VENV_DIR/bin/python" -m src.cli "$@"
