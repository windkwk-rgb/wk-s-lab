@echo off
chcp 65001 >nul
cd /d "%~dp0"
set "PY="
python -c "import sys" >nul 2>nul && set "PY=python"
if not defined PY py -c "import sys" >nul 2>nul && set "PY=py"
for %%P in ("%USERPROFILE%\anaconda3\python.exe" "%USERPROFILE%\miniconda3\python.exe" "%ProgramData%\anaconda3\python.exe" "%ProgramData%\miniconda3\python.exe" "%LOCALAPPDATA%\anaconda3\python.exe" "%LOCALAPPDATA%\miniconda3\python.exe" "%LOCALAPPDATA%\Programs\Python\Python313\python.exe" "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" "%LOCALAPPDATA%\Programs\Python\Python311\python.exe") do (
  if not defined PY if exist %%P set "PY=%%~P"
)
if not defined PY (
  echo [오류] Python을 찾지 못했습니다.
  echo https://www.python.org/downloads/ 에서 설치할 때 "Add python.exe to PATH"를 체크한 뒤 다시 실행하세요.
  rem
  explorer.exe "%~dp0..\KVM-Server.exe" & exit /b 1
)
echo [Python] %PY%
cd /d "%~dp0.."
rem 이전 exe의 임시 폴더 정보를 지워야 새 exe가 정상적으로 켜짐
set "PYINSTALLER_RESET_ENVIRONMENT=1"
set "_MEIPASS2="
for /f "delims==" %%V in ('set _PYI 2^>nul') do set "%%V="
rem 태블릿 앱 빌드가 kvm-update.log를 쓰고 있으면 같이 못 써서 빌드가 통째로 실패했음 → 따로 기록
set "LOG=%~dp0..\kvm-rebuild.log"
echo [%date% %time%] 자동 업데이트: 새 exe 만들기 (서버는 그동안 계속 동작) >> "%LOG%"
"%PY%" -m pip install --quiet --disable-pip-version-check pynput pyperclip pystray pillow pywebview pyinstaller qrcode >> "%LOG%" 2>&1
rmdir /s /q build\new 2>nul
echo %date% %time% > "%~dp0..\rebuild.lock"
rem 1) 새 서버 exe를 따로 만들어 둠 (실행 중인 서버는 끄지 않음)
call :server || (timeout /t 5 /nobreak >nul & call :server)
if errorlevel 1 (
  echo [실패] 서버 빌드 - 지금 버전을 계속 사용합니다 >> "%LOG%"
  taskkill /im KVM-Server.exe /f >nul 2>nul
  timeout /t 2 /nobreak >nul
  explorer.exe "%~dp0..\KVM-Server.exe"
  goto client_build
)
echo [완료] 서버 빌드 >> "%LOG%"
rem 2) 다 만들어지면 그때 잠깐 끄고 바꿔 끼운 뒤 바로 다시 켬 (멈추는 시간 몇 초)
taskkill /im KVM-Server.exe /f >nul 2>nul
for /l %%i in (1,1,20) do (
  tasklist /fi "imagename eq KVM-Server.exe" 2>nul | find /i "KVM-Server.exe" >nul && timeout /t 1 /nobreak >nul
)
for /l %%i in (1,1,10) do (
  move /y "build\new\KVM-Server.exe" "KVM-Server.exe" >nul 2>&1 && goto swapped
  timeout /t 1 /nobreak >nul
)
echo [실패] exe 교체 - 파일이 잠겨 있음 >> "%LOG%"
:swapped
explorer.exe "%~dp0..\KVM-Server.exe"
echo [%date% %time%] 서버 다시 켬 >> "%LOG%"
:client_build
rem 3) 서브 PC용 exe는 서버가 켜진 뒤에 천천히 만듦 (완성되면 서버가 서브 PC에 알림)
call :client || (timeout /t 5 /nobreak >nul & call :client)
if errorlevel 1 (echo [실패] 서브 PC 빌드 >> "%LOG%") else (
  move /y "build\new\KVM-Client.exe" "KVM-Client.exe" >nul 2>&1 && copy /y VERSION.txt KVM-Client.version >nul & echo [완료] 서브 PC >> "%LOG%"
)
rmdir /s /q build 2>nul
del "%~dp0..\rebuild.lock" 2>nul
exit /b 0

:server
"%PY%" -m PyInstaller --noconfirm --onefile --noconsole --name KVM-Server --icon "%~dp0..\windows\kvm-server.ico" --add-data "%~dp0..\windows\kvm-server.ico;." --add-data "%~dp0..\windows\ui.html;." --add-data "%~dp0..\windows\client_ui.html;." --add-data "%~dp0..\windows\kvm-client.ico;." --add-data "%~dp0..\VERSION.txt;." --distpath build\new --workpath build --specpath build windows\server.py >> "%LOG%" 2>&1
exit /b %errorlevel%

:client
"%PY%" -m PyInstaller --noconfirm --onefile --noconsole --name KVM-Client --icon "%~dp0..\windows\kvm-client.ico" --add-data "%~dp0..\windows\kvm-client.ico;." --add-data "%~dp0..\windows\client_ui.html;." --add-data "%~dp0..\VERSION.txt;." --add-data "%~dp0..\windows\ui.html;." --add-data "%~dp0..\windows\kvm-server.ico;." --distpath build\new --workpath build --specpath build windows\client.py >> "%LOG%" 2>&1
exit /b %errorlevel%
