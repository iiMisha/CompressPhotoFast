#!/bin/bash

# CompressPhotoFast CLI Launcher for Linux

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV_DIR="$SCRIPT_DIR/venv"

if [ ! -d "$VENV_DIR" ]; then
    echo "Creating virtual environment..."
    python3 -m venv "$VENV_DIR"
    echo "Installing package and dependencies..."
    cd "$SCRIPT_DIR"
    "$VENV_DIR/bin/pip" install -q -e .
fi

exec "$VENV_DIR/bin/python" -m src.cli "$@"
