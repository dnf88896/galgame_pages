@echo off
title KUN Gal Forum Stopper
setlocal
cd /d "%~dp0"

echo ============================================
echo   KUN Gal Forum - stop services
echo ============================================
echo.

echo Stopping frontend (5173) ...
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":5173 " ^| findstr "LISTENING"') do taskkill /F /T /PID %%p >nul 2>nul

echo Stopping backend (8080) ...
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":8080 " ^| findstr "LISTENING"') do taskkill /F /T /PID %%p >nul 2>nul

echo Stopping MySQL (3306-3310) ...
for /L %%p in (3306,1,3310) do (
    for /f "tokens=5" %%q in ('netstat -ano ^| findstr ":%%p " ^| findstr "LISTENING"') do taskkill /F /T /PID %%q >nul 2>nul
)

echo.
echo All services stopped.
pause
