@echo off
REM ============================================================
REM  Run ALL backend tests (unit + integration)
REM  115 tests total: 55 pure unit + 60 Spring integration
REM
REM  Integration tests connect to an ISOLATED database
REM  "galgame_test" (Spring profile: test) -- never the dev DB.
REM  MySQL must be running before you start this script.
REM ============================================================

cd /d "%~dp0"

echo.
echo === Running backend tests ===
echo.

call mvn -B test -Dfile.encoding=UTF-8

echo.
echo === Done. Scroll up for "Tests run: N, Failures: F, Errors: E" ===
echo.
pause
