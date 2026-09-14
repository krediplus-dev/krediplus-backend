@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title Kredi+ Backend 1.3.9 - Compilar
where java >nul 2>&1 || (echo ERROR: Java/JDK 21 no esta disponible en PATH.& pause & exit /b 1)
if not exist gradlew.bat (echo ERROR: falta gradlew.bat.& pause & exit /b 1)
echo ============================================================
echo KREDI+ BACKEND 1.3.9 - PRUEBAS + FAT JAR
echo ============================================================
call gradlew.bat --no-daemon clean test buildFatJar
if errorlevel 1 goto :error
if not exist build\libs\krediplus-server-all.jar goto :error
echo.
echo [OK] build\libs\krediplus-server-all.jar
start "" explorer.exe /select,"%CD%\build\libs\krediplus-server-all.jar"
pause
exit /b 0
:error
echo.
echo ERROR: la compilacion no termino correctamente.
pause
exit /b 1
