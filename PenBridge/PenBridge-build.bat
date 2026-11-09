@echo off
rem PenBridge one-click build (output: PenBridge-output next to this folder)
chcp 65001 >nul
cd /d "%~dp0"
if exist "%~dp0build.ps1" (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1"
  exit /b
)
if exist "%~dp0PenBridge-source.zip" if not exist "%~dp0PenBridge\build.ps1" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Path '%~dp0PenBridge-source.zip' -DestinationPath '%~dp0' -Force"
)
if not exist "%~dp0PenBridge\build.ps1" (
  echo PenBridge folder not found. Extract the zip first.
  pause
  exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0PenBridge\build.ps1"
