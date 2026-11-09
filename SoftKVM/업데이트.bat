@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion
cd /d "%~dp0"
title KVM 업데이트
echo ==============================================
echo   KVM 업데이트
echo   사용법: 패치 zip을 이 파일 위로 끌어다 놓기
echo           또는 그냥 더블클릭 (자동으로 찾음)
echo ==============================================
echo.
set "ZIP=%~1"
if not defined ZIP for /f "delims=" %%F in ('dir /b /o-d /a-d "KVM-patch*.zip" 2^>nul') do if not defined ZIP set "ZIP=%~dp0%%F"
if not defined ZIP for /f "delims=" %%F in ('dir /b /o-d /a-d "%USERPROFILE%\Downloads\KVM-patch*.zip" 2^>nul') do if not defined ZIP set "ZIP=%USERPROFILE%\Downloads\%%F"
if not defined ZIP for /f "delims=" %%F in ('dir /b /o-d /a-d "%USERPROFILE%\OneDrive\다운로드\KVM-patch*.zip" 2^>nul') do if not defined ZIP set "ZIP=%USERPROFILE%\OneDrive\다운로드\%%F"
if not defined ZIP (
  echo [실패] 패치 파일을 찾지 못했습니다.
  echo KVM-patch로 시작하는 zip을 이 폴더나 다운로드 폴더에 두거나,
  echo zip 파일을 이 파일 위로 끌어다 놓으세요.
  pause
  exit /b 1
)
echo 패치 파일: !ZIP!
tasklist /fi "imagename eq KVM-Server.exe" 2>nul | find /i "KVM-Server.exe" >nul && (echo 실행 중인 KVM 서버를 종료합니다... & taskkill /im KVM-Server.exe /f >nul & timeout /t 1 /nobreak >nul)
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\apply_patch.ps1" -Zip "!ZIP!" -Target "%~dp0." & echo. & echo 서버를 켜 둔 상태였다면 다시 실행해 주세요. & pause & exit /b
