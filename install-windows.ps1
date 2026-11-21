# Console Manager — PC edition installer for Windows (PowerShell + CMD).
# Install with ONE command (see README-PC.md):
#   PowerShell:  irm https://raw.githubusercontent.com/Nishgamerrz/Nish12345678910111.github.io/main/install-windows.ps1 -UseBasicParsing | iex
#   CMD:         powershell -NoProfile -ExecutionPolicy Bypass -Command "irm https://raw.githubusercontent.com/Nishgamerrz/Nish12345678910111.github.io/main/install-windows.ps1 -UseBasicParsing | iex"
$ErrorActionPreference = 'Stop'

$RawUrl = if ($env:CM_PC_URL) { $env:CM_PC_URL } else { 'https://raw.githubusercontent.com/Nishgamerrz/Nish12345678910111.github.io/main/console-manager-pc.py' }
$InstallDir = Join-Path $env:LOCALAPPDATA 'ConsoleManager'
$AppPath = Join-Path $InstallDir 'console-manager-pc.py'

Write-Host 'Console Manager (PC edition) - installing...' -ForegroundColor Magenta

function Find-Python {
    # Prefer the Python launcher (`py -3`), then `python`. The Microsoft Store
    # stub also answers to `python`, so verify `--version` really prints one.
    foreach ($candidate in @('py', 'python', 'python3')) {
        $cmd = Get-Command $candidate -ErrorAction SilentlyContinue
        if (-not $cmd) { continue }
        try {
            if ($candidate -eq 'py') { $v = & py -3 --version 2>&1 }
            else { $v = & $candidate --version 2>&1 }
            if ("$v" -match 'Python 3\.') {
                if ($candidate -eq 'py') { return @('py', '-3') }
                return @($candidate)
            }
        } catch { }
    }
    return $null
}

# 1) Make sure Python 3 exists; offer winget if it is missing.
$Python = Find-Python
if (-not $Python) {
    $winget = Get-Command winget -ErrorAction SilentlyContinue
    if ($winget) {
        Write-Host 'Setting up everything (installing Python)...' -ForegroundColor Yellow
        $wingetLog = Join-Path $env:TEMP 'console-manager-winget.log'
        winget install -e --id Python.Python.3.12 --scope user --accept-source-agreements --accept-package-agreements *> $wingetLog
        if ($LASTEXITCODE -ne 0) { Get-Content $wingetLog; throw "winget failed to install Python (log: $wingetLog)" }
        $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + [Environment]::GetEnvironmentVariable('Path', 'User')
        $Python = Find-Python
    }
}
if (-not $Python) {
    throw 'Python 3 is required. Install it from https://www.python.org/downloads/ (tick "Add python.exe to PATH"), then run this command again.'
}
$PythonCmd = $Python -join ' '
Write-Host "Using Python: $PythonCmd"

# 2) Download the single-file app and validate it before swapping it in.
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
$TmpPath = "$AppPath.tmp"
Invoke-WebRequest -Uri $RawUrl -OutFile $TmpPath -UseBasicParsing
if (-not (Select-String -Path $TmpPath -Pattern 'CONSOLE_MANAGER_PC_VERSION' -Quiet)) {
    Remove-Item $TmpPath -ErrorAction SilentlyContinue
    throw 'Download failed validation.'
}
Move-Item -Force $TmpPath $AppPath

# 3) The `ac` launchers (.cmd works in CMD + PowerShell, .ps1 in PowerShell).
$cmdContent = "@echo off`r`n$PythonCmd `"$AppPath`" %*`r`n"
Set-Content -Path (Join-Path $InstallDir 'ac.cmd') -Value $cmdContent -Encoding ASCII
$ps1Content = "& $PythonCmd `"$AppPath`" @args`r`n"
Set-Content -Path (Join-Path $InstallDir 'ac.ps1') -Value $ps1Content -Encoding ASCII

# Allow the locally-installed ac.ps1 to run in future PowerShell windows.
try {
    $policy = Get-ExecutionPolicy -Scope CurrentUser
    if ($policy -eq 'Restricted' -or $policy -eq 'Undefined') {
        Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned -Force
    }
} catch { }

# 4) Put the install folder on the *user* PATH (no admin needed).
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if (-not $userPath) { $userPath = '' }
if (($userPath -split ';') -notcontains $InstallDir) {
    [Environment]::SetEnvironmentVariable('Path', (($userPath.TrimEnd(';') + ';' + $InstallDir).Trim(';')), 'User')
}
if (($env:Path -split ';') -notcontains $InstallDir) { $env:Path = "$env:Path;$InstallDir" }

# 5) Start it now and show the URLs.
Write-Host ''
Write-Host 'Installed Console Manager.' -ForegroundColor Green
& (Join-Path $InstallDir 'ac.cmd') start
