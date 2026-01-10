@echo off
REM CompressPhotoFast CLI Launcher for Windows

set SCRIPT_DIR=%~dp0
set VENV_DIR=%SCRIPT_DIR%venv

if not exist "%VENV_DIR%" (
    echo Creating virtual environment...
    python -m venv "%VENV_DIR%"
    echo Installing package and dependencies...
    cd /d "%SCRIPT_DIR%"
    "%VENV_DIR%\Scripts\pip.exe" install -q -e .
)

"%VENV_DIR%\Scripts\python.exe" -m src.cli %*
