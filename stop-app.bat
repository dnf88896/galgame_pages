@echo off
title KUN Gal Forum - Stop APP only (keep MySQL running)
setlocal
cd /d "%~dp0"

echo ============================================
echo   Stop frontend + backend only.
echo   MySQL (3306) is KEPT running.
echo ============================================
echo.

echo Stopping frontend (5173) ...
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":5173 " ^| findstr "LISTENING"') do taskkill /F /T /PID %%p >nul 2>nul

echo Stopping backend (8080) ...
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":8080 " ^| findstr "LISTENING"') do taskkill /F /T /PID %%p >nul 2>nul

echo.
echo Done. APP stopped. MySQL is still running.
pause
