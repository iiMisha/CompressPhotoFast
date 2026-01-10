@echo off
REM CompressPhotoFast CLI Launcher for Windows

set SCRIPT_DIR=%~dp0
set VENV_DIR=%SCRIPT_DIR%venv

if not exist "%VENV_DIR%" (
    echo Creating virtual environment...
    python -m venv "%VENV_DIR%"
    echo Installing dependencies...
    "%VENV_DIR%\Scripts\pip.exe" install -q -r "%SCRIPT_DIR%requirements.txt"
)

"%VENV_DIR%\Scripts\python.exe" -m src.cli %*
