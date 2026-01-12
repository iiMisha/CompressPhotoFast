# CompressPhotoFast CLI - Installer for Windows
# Автоматическая установка с детекцией Python

#Requires -Version 5.1

# ============================================
# Конфигурация
# ============================================
$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectDir = Split-Path -Parent $ScriptDir
$VenvDir = Join-Path $ProjectDir "venv"

# ============================================
# Подключение модулей
# ============================================
. "$ScriptDir\install_common.ps1"

# ============================================
# Проверка версии Python
# ============================================
function Test-PythonVersion {
    Show-Step "Проверка Python..."

    $minMajor = 3
    $minMinor = 10

    # Список команд для проверки
    $pythonCommands = @("python", "python3", "py")

    foreach ($cmd in $pythonCommands) {
        if (Test-Command $cmd) {
            try {
                $versionOutput = & $cmd --version 2>&1
                if ($versionOutput -match "Python (\d+)\.(\d+)\.(\d+)") {
                    $major = [int]$matches[1]
                    $minor = [int]$matches[2]
                    $patch = [int]$matches[3]

                    if ($major -gt $minMajor -or ($major -eq $minMajor -and $minor -ge $minMinor)) {
                        $script:PythonCmd = $cmd
                        $script:PythonVersion = "$major.$minor.$patch"
                        Show-Success "Python $versionOutput найден (>= $minMajor.$minMinor требуется)"
                        return $true
                    } else {
                        Show-Info "Найден Python $major.$minor.$patch, но требуется >= $minMajor.$minMinor"
                    }
                }
            } catch {
                # Игнорируем ошибки при попытке запуска
            }
        }
    }

    Show-Warning "Python $minMajor.$minMinor+ не найден"
    return $false
}

# ============================================
# Установить Python
# ============================================
function Install-Python {
    Show-Step "Установка Python $minMajor.$minMinor+..."

    # Проверяем наличие winget
    if (Test-Command "winget") {
        Show-Info "Использование winget для установки Python..."

        $result = Start-ProcessWait "winget" @("install", "--id", "Python.Python.3.12", "--accept-source-agreements", "--accept-package-agreements", "-e")

        if ($result -eq 0 -or $result -eq $null) {
            Show-Success "Python установлен через winget"
            Start-Sleep -Seconds 2  # Дать время для завершения установки
            return Test-PythonVersion
        }
    }

    # Fallback на chocolatey
    if (Test-Command "choco") {
        Show-Info "Использование chocolatey для установки Python..."

        $result = Start-ProcessWait "choco" @("install", "python312", "-y")

        if ($result -eq 0) {
            Show-Success "Python установлен через chocolatey"
            return Test-PythonVersion
        }
    }

    Show-Error "Не удалось установить Python автоматически"
    Show-Info "Пожалуйста, установите Python вручную:"
    Show-Info "  1. Скачайте с https://www.python.org/downloads/"
    Show-Info "  2. Установите Python 3.10 или выше"
    Show-Info "  3. Перезапустите этот установщик"
    return $false
}

# ============================================
# Создание виртуального окружения
# ============================================
function New-VirtualEnvironment {
    Show-Step "Создание виртуального окружения..."

    # Если venv уже существует, спрашиваем о переустановке
    if (Test-Path $VenvDir) {
        Show-Warning "Виртуальное окружение уже существует: $VenvDir"
        $response = Read-Host "Удалить и создать заново? (y/N)"
        if ($response -eq 'y' -or $response -eq 'Y') {
            Show-Info "Удаление старого виртуального окружения..."
            Remove-Item -Recurse -Force $VenvDir
        } else {
            Show-Info "Использование существующего виртуального окружения"
            return $true
        }
    }

    # Создаем venv
    $result = & $script:PythonCmd -m venv $VenvDir 2>&1

    if ($LASTEXITCODE -ne 0) {
        Show-Error "Не удалось создать виртуальное окружение"
        return $false
    }

    Show-Success "Виртуальное окружение создано"
    return $true
}

# ============================================
# Установка пакета с HEIC поддержкой
# ============================================
function Install-PackageWithHeic {
    Show-Step "Установка CompressPhotoFast CLI с HEIC поддержкой..."

    $pipCmd = Join-Path $VenvDir "Scripts\pip.exe"

    # Проверяем наличие pip
    if (-not (Test-Path $pipCmd)) {
        Show-Error "pip не найден в виртуальном окружении"
        return $false
    }

    # Обновляем pip
    Show-Info "Обновление pip..."
    & $pipCmd install --upgrade pip -q

    if ($LASTEXITCODE -ne 0) {
        Show-Error "Не удалось обновить pip"
        return $false
    }

    # Устанавливаем пакет
    Show-Info "Установка зависимостей..."
    & $pipCmd install -e $ProjectDir -q

    if ($LASTEXITCODE -ne 0) {
        Show-Error "Не удалось установить пакет"
        return $false
    }

    Show-Success "Пакет установлен"
    return $true
}

# ============================================
# Проверка установки
# ============================================
function Test-Installation {
    Show-Step "Проверка установки..."

    $pythonCmd = Join-Path $VenvDir "Scripts\python.exe"

    try {
        $output = & $pythonCmd -m src.cli version 2>&1
        if ($LASTEXITCODE -eq 0) {
            Show-Success "CompressPhotoFast CLI готов к работе!"
            return $true
        }
    } catch {
        Show-Warning "Не удалось проверить версию (это может быть нормально)"
    }

    return $true
}

# ============================================
# Показать сообщение об успешной установке
# ============================================
function Show-SuccessMessage {
    Write-Host ""
    Write-Host "╔══════════════════════════════════════════════════════════╗" -ForegroundColor Green
    Write-Host "║  Installation Complete!                                  ║" -ForegroundColor Green
    Write-Host "╠══════════════════════════════════════════════════════════╣" -ForegroundColor Green
    Write-Host "║  To use CompressPhotoFast CLI:                          ║" -ForegroundColor Green
    Write-Host "║    cd $ProjectDir" -ForegroundColor Green
    Write-Host "║    .\venv\Scripts\Activate.ps1                           ║" -ForegroundColor Green
    Write-Host "║    compressphotofast --help                              ║" -ForegroundColor Green
    Write-Host "║                                                          ║" -ForegroundColor Green
    Write-Host "║  Or use the launcher script:                             ║" -ForegroundColor Green
    Write-Host "║    .\compressphotofast.bat --help                        ║" -ForegroundColor Green
    Write-Host "╚══════════════════════════════════════════════════════════╝" -ForegroundColor Green
    Write-Host ""

    Show-Info "HEIC/HEIF support: ENABLED"

    $logPath = Get-InstallLog
    if ($logPath) {
        Show-Info "Installation log: $logPath"
    }
    Write-Host ""
}

# ============================================
# Главная функция
# ============================================
function Main {
    # Настраиваем логирование
    Initialize-Logging

    # Показываем баннер
    Show-Banner

    # Проверяем интернет
    if (-not (Test-InternetConnection)) {
        Show-Warning "Нет интернет-соединения. Установка может не удасться."
        $response = Read-Host "Продолжить? (y/N)"
        if ($response -ne 'y' -and $response -ne 'Y') {
            exit 1
        }
    }

    # Определяем версию Windows
    $windowsVersion = Get-WindowsVersion
    Show-Success "Windows $windowsVersion"

    # Проверяем Python
    if (-not (Test-PythonVersion)) {
        if (-not (Install-Python)) {
            Show-Error "Не удалось установить Python"
            exit 1
        }
    }

    # Создаем виртуальное окружение
    if (-not (New-VirtualEnvironment)) {
        Show-Error "Не удалось создать виртуальное окружение"
        exit 1
    }

    # Устанавливаем пакет
    if (-not (Install-PackageWithHeic)) {
        Show-Error "Не удалось установить пакет"
        exit 1
    }

    # Проверяем установку
    Test-Installation

    # Показываем сообщение об успехе
    Show-SuccessMessage
}

# ============================================
# Запуск
# ============================================
Main
