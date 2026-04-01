@echo off
chcp 65001 >nul
title EEG-AI Platform Starter
color 0B

echo ==========================================
echo       EEG-AI PLATFORM ONE-CLICK START
echo ==========================================
echo.

set "JAVA_HOME=D:\JAVA\openjdk21\jdk-21"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo [Check] Java Version:
java -version
if errorlevel 1 (
    echo [ERROR] JDK not found in %JAVA_HOME%
    pause
    exit
)
echo [OK] Java detected.
echo.

:: Use relative path to avoid encoding issues
set "PRJ_ROOT=%~dp0"
set "INF_DIR=%PRJ_ROOT%InfluxDB Core 3.2.1\InfluxDB Core 3.2.1\influxdb-3.2.1"
set "BAK_DIR=%PRJ_ROOT%ai_eeg-main\ai_eeg-main\source\eeg-fileData"

echo [Step 1] Initializing InfluxDB...
if exist "%INF_DIR%" (
    start "InfluxDB Server" /d "%INF_DIR%" cmd /k "influxdb3.exe serve --node-id eeg-node --object-store file --data-dir .\influxdb-data --without-auth"
) else (
    echo [ERROR] InfluxDB path missing: %INF_DIR%
)

echo [Step 2] Starting Backend...
if exist "%BAK_DIR%" (
    start "Backend Server" /d "%BAK_DIR%" cmd /k "mvnw.cmd spring-boot:run"
) else (
    echo [ERROR] Backend path missing: %BAK_DIR%
)

echo [Step 3] Waiting for initialization (30s)...
timeout /t 30 /nobreak
start http://localhost:8080/index.html

echo ==========================================
echo ALL SYSTEMS STARTED
echo ==========================================
pause
