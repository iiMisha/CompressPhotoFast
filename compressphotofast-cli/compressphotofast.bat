@echo off
REM CompressPhotoFast CLI Launcher for Windows

setlocal EnableDelayedExpansion

set SCRIPT_DIR=%~dp0
set VENV_DIR=%SCRIPT_DIR%venv

if not exist "%VENV_DIR%" (
    echo 🔧 Creating virtual environment...
    python -m venv "%VENV_DIR%"

    if errorlevel 1 (
        echo ❌ Failed to create virtual environment
        echo Please ensure Python 3.10+ is installed
        exit /b 1
    )

    echo 📦 Installing CompressPhotoFast CLI and dependencies...
    cd /d "%SCRIPT_DIR%"

    REM Update pip
    "%VENV_DIR%\Scripts\pip.exe" install --upgrade pip -q

    REM Install package
    "%VENV_DIR%\Scripts\pip.exe" install -e .

    if errorlevel 1 (
        echo ❌ Failed to install package
        exit /b 1
    )

    echo ✅ Installation complete!
    echo.
)

REM Run CLI
"%VENV_DIR%\Scripts\python.exe" -m src.cli %*
