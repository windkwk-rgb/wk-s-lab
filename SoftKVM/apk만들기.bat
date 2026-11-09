@echo off
chcp 65001 >nul
cd /d "%~dp0"
title KVM 안드로이드 앱 만들기
echo ==============================================
echo   태블릿용 앱 apk 만들기
echo   처음엔 빌드 도구 약 1GB를 내려받아 10~20분 걸립니다.
echo   다음부터는 1~2분이면 끝나요.
echo ==============================================
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\build_apk.ps1" -Target "%~dp0."
if errorlevel 1 (echo. & echo [실패] 위 메시지를 확인해 주세요. & pause & exit /b 1)
echo.
choice /c YN /m "지금 태블릿으로 보낼까요"
if errorlevel 2 (pause & exit /b 0)
call "%~dp0태블릿으로보내기.bat"
