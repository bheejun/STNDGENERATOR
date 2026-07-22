@echo off
setlocal EnableExtensions DisableDelayedExpansion
chcp 65001 >nul

set "VERSION_FILE=C:\WDQ\ide\versionInfo.txt"
set "DB_BIN=C:\Program Files (x86)\WDQ\db\bin"
set "DATABASE=dqlite"
set "DB_USER=root"
set "SQL_FILE=%~dp0wdq_delete.sql"

if not exist "%VERSION_FILE%" exit /b 10
findstr /c:"9.0" /c:"9.1" /c:"9.2" "%VERSION_FILE%" >nul
if errorlevel 1 exit /b 11
if not exist "%DB_BIN%\mysql.exe" exit /b 12
if not exist "%SQL_FILE%" exit /b 14

set "DB_PASSWORD=%~1"
if not defined DB_PASSWORD set /p "DB_PASSWORD=Enter the WDQ MariaDB root password: "
if not defined DB_PASSWORD exit /b 15

set "MYSQL_PWD=%DB_PASSWORD%"
"%DB_BIN%\mysql.exe" -u "%DB_USER%" "%DATABASE%" --default-character-set=utf8 < "%SQL_FILE%"
if errorlevel 1 exit /b 21
set "MYSQL_PWD="
set "DB_PASSWORD="
echo [SUCCESS] WDQ data deletion completed.
exit /b 0
