# Общие функции для установщика CompressPhotoFast CLI (Windows)
# Используется в install.ps1, detect_python.ps1, install_deps.ps1

#Requires -Version 5.1

# ============================================
# Логирование
# ============================================
function Show-Info {
    param([string]$Message)
    Write-Host "[i] $Message" -ForegroundColor Cyan
}

function Show-Success {
    param([string]$Message)
    Write-Host "[+] $Message" -ForegroundColor Green
}

function Show-Warning {
    param([string]$Message)
    Write-Host "[!] $Message" -ForegroundColor Yellow
}

function Show-Error {
    param([string]$Message)
    Write-Host "[x] $Message" -ForegroundColor Red
}

function Show-Step {
    param([string]$Message)
    Write-Host "[*] $Message" -ForegroundColor Cyan
}

# ============================================
# Баннер
# ============================================
function Show-Banner {
    Write-Host @"
╔══════════════════════════════════════════════════════════╗
║  CompressPhotoFast CLI - Installer                       ║
║  Version 1.0.0                                           ║
╚══════════════════════════════════════════════════════════╝
"@ -ForegroundColor Cyan
}

# ============================================
# Progress bar
# ============================================
function Show-Progress {
    param(
        [string]$Activity,
        [string]$Status,
        [int]$PercentComplete
    )

    Write-Progress -Activity $Activity -Status $Status -PercentComplete $PercentComplete
}

# ============================================
# Проверка наличия команды
# ============================================
function Test-Command {
    param([string]$Command)

    $null = Get-Command $Command -ErrorAction SilentlyContinue
    return $?
}

# ============================================
# Определение версии Windows
# ============================================
function Get-WindowsVersion {
    $os = Get-CimInstance -ClassName Win32_OperatingSystem
    return [version]$os.Version
}

# ============================================
# Проверка прав администратора
# ============================================
function Test-Administrator {
    $currentUser = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($currentUser)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

# ============================================
# Запрос прав администратора
# ============================================
function Request-Administrator {
    param([string]$Reason)

    if (-not (Test-Administrator)) {
        Show-Warning "Требуются права администратора для: $Reason"
        Show-Info "Пожалуйста, перезапустите PowerShell от имени администратора"
        return $false
    }
    return $true
}

# ============================================
# Cleanup при прерывании
# ============================================
$script:cleanupHandlers = @()

function Register-Cleanup {
    param([scriptblock]$Handler)

    $script:cleanupHandlers += $Handler
}

function Invoke-Cleanup {
    foreach ($handler in $script:cleanupHandlers) {
        try {
            & $handler
        } catch {
            Show-Error "Ошибка при cleanup: $_"
        }
    }
}

# Регистрация cleanup для Ctrl+C
$null = Register-EngineEvent -SourceIdentifier PowerShell.Exiting -Action {
    Invoke-Cleanup
}

# ============================================
# Создание лог-файла
# ============================================
$script:installLog = $null

function Initialize-Logging {
    $logDir = Join-Path $env:TEMP "compressphotofast"
    if (-not (Test-Path $logDir)) {
        New-Item -ItemType Directory -Path $logDir -Force | Out-Null
    }

    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $script:installLog = Join-Path $logDir "install-$timestamp.log"

    # Создать файл
    New-Item -ItemType File -Path $script:installLog -Force | Out-Null
}

function Get-InstallLog {
    return $script:installLog
}

# ============================================
# Проверка интернет-соединения
# ============================================
function Test-InternetConnection {
    try {
        $response = Invoke-WebRequest -Uri "https://www.google.com" -Method Head -TimeoutSec 5 -UseBasicParsing
        return $response.StatusCode -eq 200
    } catch {
        Show-Warning "Не удалось проверить интернет-соединение"
        return $true  # Предполагаем, что соединение есть
    }
}

# ============================================
# Запуск процесса с ожиданием
# ============================================
function Start-ProcessWait {
    param(
        [string]$FilePath,
        [string[]]$ArgumentList,
        [switch]$NoNewWindow
    )

    $process = Start-Process -FilePath $FilePath -ArgumentList $ArgumentList -PassThru -NoNewWindow:$NoNewWindow
    $process.WaitForExit()
    return $process.ExitCode
}

# ============================================
# Скачивание файла с прогрессом
# ============================================
function Download-FileProgress {
    param(
        [string]$Url,
        [string]$OutputPath
    )

    try {
        $webClient = New-Object System.Net.WebClient
        $webClient.DownloadFileAsync($Url, $OutputPath)

        $prevPercent = 0
        while ($webClient.IsBusy) {
            # Проверить размер файла
            if (Test-Path $OutputPath) {
                $fileSize = (Get-Item $OutputPath).Length
                # Не можем получить общий размер без Content-Length header
                # Показываем просто анимацию
                $percent = ($prevPercent + 1) % 100
                Write-Progress -Activity "Скачивание" -Status "$Url" -PercentComplete $percent
                $prevPercent = $percent
            }
            Start-Sleep -Milliseconds 200
        }

        Write-Progress -Activity "Скачивание" -Completed
        $webClient.Dispose()
        return $true
    } catch {
        Show-Error "Ошибка скачивания: $_"
        return $false
    }
}

# ============================================
# Экспорт функций
# ============================================
Export-ModuleMember -Function @(
    'Show-Info', 'Show-Success', 'Show-Warning', 'Show-Error', 'Show-Step',
    'Show-Banner', 'Show-Progress',
    'Test-Command',
    'Get-WindowsVersion',
    'Test-Administrator', 'Request-Administrator',
    'Register-Cleanup', 'Invoke-Cleanup',
    'Initialize-Logging', 'Get-InstallLog',
    'Test-InternetConnection',
    'Start-ProcessWait',
    'Download-FileProgress'
)
