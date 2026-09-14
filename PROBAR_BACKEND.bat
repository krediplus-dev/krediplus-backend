@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title Kredi+ Backend 1.3.9 - Pruebas
where java >nul 2>&1 || (echo ERROR: Java/JDK 21 no esta disponible en PATH.& pause & exit /b 1)
call gradlew.bat --no-daemon test
if errorlevel 1 (echo ERROR: hay pruebas fallidas.& pause & exit /b 1)
echo [OK] Todas las pruebas terminaron correctamente.
pause
