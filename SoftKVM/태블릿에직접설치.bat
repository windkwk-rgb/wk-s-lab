@echo off
chcp 65001 >nul
cd /d "%~dp0"
title KVM 앱을 태블릿에 직접 설치
set "ADB=%LOCALAPPDATA%\KVM-build\sdk\platform-tools\adb.exe"
if not exist "%ADB%" (
  echo adb가 없습니다. 먼저 apk만들기.bat을 실행하세요.
  pause
  exit /b 1
)
if not exist "KVM-Android.apk" (
  echo KVM-Android.apk가 없습니다. 먼저 apk만들기.bat을 실행하세요.
  pause
  exit /b 1
)
echo ==============================================================
echo  태블릿 준비
echo   1. 설정 → 태블릿 정보 → 소프트웨어 버전 또는 빌드 번호를 7번 탭
echo   2. 설정 → 시스템 → 개발자 옵션 → 무선 디버깅 켜기
echo   3. 무선 디버깅 화면에서 "페어링 코드로 기기 페어링" 누르기
echo ==============================================================
echo.
echo 팝업에 보이는 IP 주소 및 포트와 6자리 코드를 입력하세요.
echo 이미 페어링한 적이 있으면 그냥 엔터.
set "PAIR="
set /p PAIR=  페어링 주소 예 192.168.0.5:37123 : 
if defined PAIR (
  set "CODE="
  set /p CODE=  페어링 코드 6자리 : 
  call "%ADB%" pair %%PAIR%% %%CODE%%
)
echo.
echo 이제 팝업을 닫고, 무선 디버깅 화면 위쪽의 "IP 주소 및 포트"를 입력하세요.
echo 페어링 주소와 포트 번호가 다릅니다.
set "CONN="
set /p CONN=  연결 주소 예 192.168.0.5:41234 : 
"%ADB%" connect %CONN%
echo.
echo 설치 중...
"%ADB%" -s %CONN% install -r "KVM-Android.apk"
if errorlevel 1 (
  echo.
  echo [실패] 위 메시지를 Claude에게 보내주세요.
) else (
  echo.
  echo 설치 완료. 태블릿에서 PC 마우스 공유 앱을 여세요.
  echo 무선 디버깅은 이제 꺼도 됩니다.
)
pause
