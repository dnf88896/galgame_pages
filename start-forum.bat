@echo off
title KUN Gal Forum Launcher
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo ============================================
echo   KUN Gal Forum - one-click start
echo   MySQL / Backend / Frontend will start
echo ============================================
echo.

REM ============ 1. locate MySQL ============
set "MYSQLDIR="

REM 1a. cached location from last run (mysql-config.txt)
if exist "%~dp0mysql-config.txt" set /p MYSQLDIR=<"%~dp0mysql-config.txt"
if defined MYSQLDIR if not exist "%MYSQLDIR%\mysqld.exe" set "MYSQLDIR="

REM 1b. MYSQL_HOME environment variable
if not defined MYSQLDIR if defined MYSQL_HOME set "MYSQLDIR=%MYSQL_HOME%\bin"

REM 1c. search PATH
if not defined MYSQLDIR for /f "delims=" %%i in ('where mysqld 2^>nul') do if not defined MYSQLDIR set "MYSQLDIR=%%~dpi"

REM 1d. common install locations
if not defined MYSQLDIR for /d %%d in ("C:\Program Files\MySQL\*") do if not defined MYSQLDIR if exist "%%~d\bin\mysqld.exe" set "MYSQLDIR=%%~d\bin"
if not defined MYSQLDIR if exist "C:\mysql\bin\mysqld.exe" set "MYSQLDIR=C:\mysql\bin"
if not defined MYSQLDIR if exist "D:\mysql\bin\mysqld.exe" set "MYSQLDIR=D:\mysql\bin"
if not defined MYSQLDIR if exist "C:\tools\mysql\bin\mysqld.exe" set "MYSQLDIR=C:\tools\mysql\bin"

REM 1e. ask the user once
:ask_mysql
if defined MYSQLDIR goto mysql_found
echo.
echo [1/4] MySQL was not found automatically.
echo       Enter the folder that contains mysqld.exe
echo       for example: C:\mysql\bin
set /p "MYSQLDIR=  path: "
if not defined MYSQLDIR goto ask_mysql
if "%MYSQLDIR:~-1%"=="\" set "MYSQLDIR=%MYSQLDIR:~0,-1%"
if exist "%MYSQLDIR%\mysqld.exe" (
    > "%~dp0mysql-config.txt" echo %MYSQLDIR%
    echo       Saved to mysql-config.txt.
    goto mysql_found
)
echo       Not found: %MYSQLDIR%\mysqld.exe
set "MYSQLDIR="
goto ask_mysql

:mysql_found
if "%MYSQLDIR:~-1%"=="\" set "MYSQLDIR=%MYSQLDIR:~0,-1%"
for %%I in ("%MYSQLDIR%\..") do set "MYSQL_BASEDIR=%%~fI"
set "MYSQLD=%MYSQLDIR%\mysqld.exe"
set "MYSQLC=%MYSQLDIR%\mysql.exe"
echo MySQL found: %MYSQLD%

REM ============ 2. pick MySQL port 3306..3310 ============
set "DBPORT=3306"
:pick_port
"%MYSQLC%" -h127.0.0.1 -P !DBPORT! -ugalgame -pgalgame123 galgame -e "select 1" >nul 2>nul
if not errorlevel 1 (
    echo [1/4] MySQL already running on port !DBPORT!.
    goto mysql_ready
)
netstat -ano | findstr ":!DBPORT! " | findstr "LISTENING" >nul
if errorlevel 1 (
    echo [1/4] Starting MySQL on port !DBPORT! ...
    start "KUN-MySQL" /min "%MYSQLD%" --defaults-file="%~dp0database\my.ini" --basedir="%MYSQL_BASEDIR%" --datadir="%~dp0database\mysql-data" --port=!DBPORT! --console
    goto wait_mysql
)
set /a DBPORT+=1
if !DBPORT! gtr 3310 (
    echo [ERROR] No free port in 3306-3310. Close some MySQL first.
    pause
    exit /b 1
)
goto pick_port

:wait_mysql
set /a tries=0
:wait_mysql_loop
"%MYSQLC%" -h127.0.0.1 -P !DBPORT! -ugalgame -pgalgame123 galgame -e "select 1" >nul 2>nul
if not errorlevel 1 (
    echo       MySQL ready on port !DBPORT!.
    goto mysql_ready
)
set /a tries+=1
if !tries! geq 30 (
    echo [ERROR] MySQL startup timeout. Check database\my.ini.
    pause
    exit /b 1
)
ping -n 2 127.0.0.1 >nul
goto wait_mysql_loop

:mysql_ready

REM ============ 3. backend 8081 ============
echo [2/4] Check backend ...
netstat -ano | findstr ":8081 " | findstr "LISTENING" >nul
if errorlevel 1 (
    set "DB_PORT=!DBPORT!"
    if exist "%~dp0backend\target\galgame-backend-0.0.1-SNAPSHOT.jar" (
        echo     Not running. Starting backend - java -jar ...
        start "KUN-Backend" /d "%~dp0backend" /min cmd /c "java -jar target\galgame-backend-0.0.1-SNAPSHOT.jar --server.port=8081 > app.log 2>&1"
    ) else (
        echo     Not running. Starting backend - mvn spring-boot:run ...
        start "KUN-Backend" /d "%~dp0backend" /min cmd /c "mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081 > app.log 2>&1"
    )
) else (
    echo     Already running.
)
echo     Waiting for backend ...
call :wait_port 8081

REM ============ 4. frontend 5173 ============
echo [3/4] Check frontend ...
netstat -ano | findstr ":5173 " | findstr "LISTENING" >nul
if errorlevel 1 (
    echo     Not running. Starting frontend - npm run dev ...
    start "KUN-Frontend" /d "%~dp0frontend" /min cmd /c "npm run dev"
) else (
    echo     Already running.
)
echo     Waiting for frontend ...
call :wait_port 5173

REM ============ 5. open browser ============
echo [4/4] Opening browser ...
ping -n 2 127.0.0.1 >nul
start "" "http://localhost:5173"

echo.
echo ============================================
echo   All services started! Forum is up.
echo   MySQL port: !DBPORT!   Backend: 8081   Frontend: 5173
echo   They run in minimized windows in the background.
echo   To stop, double-click stop-forum.bat.
echo ============================================
pause
exit /b 0

REM ---- subroutine: wait for a port ----
:wait_port
set "port=%~1"
set /a tries=0
:wait_port_loop
netstat -ano | findstr ":%port% " | findstr "LISTENING" >nul
if not errorlevel 1 (
    echo     Port %port% ready.
    exit /b 0
)
set /a tries+=1
if %tries% geq 60 (
    echo     [ERROR] Port %port% timeout. Check backend\app.log.
    exit /b 1
)
ping -n 2 127.0.0.1 >nul
goto wait_port_loop
