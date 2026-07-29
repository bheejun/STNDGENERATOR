@echo off
setlocal EnableExtensions DisableDelayedExpansion
chcp 65001 >nul

set "VERSION_FILE=C:\WDQ\ide\versionInfo.txt"
set "DB_BIN=C:\Program Files (x86)\WDQ\db\bin"
set "DATABASE=dqlite"
set "DB_USER=root"
set "DB_PASSWORD="
set "DB_HOST=127.0.0.1"
set "DB_PORT=43396"
set "SQL_FILE=%~dp0wdq_patch.sql"
set "LOG_FILE=%~dp0patch_result.log"

call :RUN > "%LOG_FILE%" 2>&1
set "RESULT=%ERRORLEVEL%"
type "%LOG_FILE%"
exit /b %RESULT%

:RUN
echo [%date% %time%] WDQ SQL patch started.
if exist "%VERSION_FILE%" goto VERSION_OK
echo [ERROR] WDQ version file not found: %VERSION_FILE%
exit /b 10
:VERSION_OK
findstr /c:"9.0" /c:"9.1" /c:"9.2" "%VERSION_FILE%" >nul
if not errorlevel 1 goto VERSION_SUPPORTED
echo [ERROR] This patch supports WDQ 9.0, 9.1, and 9.2 only.
exit /b 11
:VERSION_SUPPORTED
if exist "%DB_BIN%\mysql.exe" goto MYSQL_OK
echo [ERROR] mysql.exe not found.
exit /b 12
:MYSQL_OK
if exist "%SQL_FILE%" goto SQL_OK
echo [ERROR] SQL file not found: %SQL_FILE%
exit /b 14
:SQL_OK

echo Applying SQL to %DATABASE% ...
set "MYSQL_PWD=%DB_PASSWORD%"
"%DB_BIN%\mysql.exe" -h "%DB_HOST%" -P "%DB_PORT%" --protocol=TCP -u "%DB_USER%" "%DATABASE%" --default-character-set=utf8 < "%SQL_FILE%"
if not errorlevel 1 goto EXECUTION_OK
set "MYSQL_PWD="
echo [ERROR] SQL execution failed.
exit /b 21
:EXECUTION_OK
set "MYSQL_PWD="
echo [SUCCESS] WDQ SQL patch completed.
exit /b 0
