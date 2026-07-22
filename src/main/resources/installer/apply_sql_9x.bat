@echo off
setlocal EnableExtensions DisableDelayedExpansion
chcp 65001 >nul

set "VERSION_FILE=C:\WDQ\ide\versionInfo.txt"
set "DB_BIN=C:\Program Files (x86)\WDQ\db\bin"
set "DATABASE=dqlite"
set "DB_USER=root"
set "SQL_FILE=%~dp0wdq_patch.sql"
set "BACKUP_DIR=C:\WDQ\db_backup"

if not exist "%VERSION_FILE%" (
    echo [ERROR] WDQ version file not found: %VERSION_FILE%
    exit /b 10
)

findstr /c:"9.0" /c:"9.1" /c:"9.2" "%VERSION_FILE%" >nul
if errorlevel 1 (
    echo [ERROR] This patch supports WDQ 9.0, 9.1, and 9.2 only.
    exit /b 11
)

if not exist "%DB_BIN%\mysql.exe" (
    echo [ERROR] mysql.exe not found: %DB_BIN%\mysql.exe
    exit /b 12
)
if not exist "%DB_BIN%\mysqldump.exe" (
    echo [ERROR] mysqldump.exe not found: %DB_BIN%\mysqldump.exe
    exit /b 13
)
if not exist "%SQL_FILE%" (
    echo [ERROR] SQL file not found: %SQL_FILE%
    exit /b 14
)

set "DB_PASSWORD=%~1"
if not defined DB_PASSWORD set /p "DB_PASSWORD=Enter the WDQ MariaDB root password: "
if not defined DB_PASSWORD (
    echo [ERROR] Database password is required.
    exit /b 15
)

if not exist "%BACKUP_DIR%" mkdir "%BACKUP_DIR%"
for /f %%I in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss"') do set "STAMP=%%I"
set "BACKUP_FILE=%BACKUP_DIR%\WDQ_9x_before_patch_%STAMP%.sql"

echo [1/2] Backing up %DATABASE% to %BACKUP_FILE% ...
set "MYSQL_PWD=%DB_PASSWORD%"
"%DB_BIN%\mysqldump.exe" --routines --triggers --single-transaction -u "%DB_USER%" "%DATABASE%" --default-character-set=utf8 > "%BACKUP_FILE%"
if errorlevel 1 (
    set "MYSQL_PWD="
    echo [ERROR] Database backup failed. SQL was not executed.
    exit /b 20
)

echo [2/2] Applying WDQ 9.x SQL patch ...
"%DB_BIN%\mysql.exe" -u "%DB_USER%" "%DATABASE%" --default-character-set=utf8 < "%SQL_FILE%"
if errorlevel 1 (
    set "MYSQL_PWD="
    echo [ERROR] SQL execution failed. Backup: %BACKUP_FILE%
    exit /b 21
)
set "MYSQL_PWD="
set "DB_PASSWORD="

echo.>> "%VERSION_FILE%"
echo [SQL patch / WDQ 9.0-9.2 / %date% %time:~0,8%]>> "%VERSION_FILE%"
echo [SUCCESS] SQL patch completed. Backup: %BACKUP_FILE%
exit /b 0
