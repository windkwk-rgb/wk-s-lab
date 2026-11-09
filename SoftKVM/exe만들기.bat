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
  pause
  exit /b 1
)
echo [Python] %PY%
echo exe 빌드 도구 설치 중...
"%PY%" -m pip install --quiet --disable-pip-version-check pynput pyperclip pystray pillow pywebview pyinstaller qrcode
if errorlevel 1 ( echo [오류] 패키지 설치 실패 & pause & exit /b 1 )
tasklist /fi "imagename eq KVM-Server.exe" 2>nul | find /i "KVM-Server.exe" >nul && taskkill /im KVM-Server.exe /f >nul
echo [1/2] 메인용 KVM-Server.exe
"%PY%" -m PyInstaller --noconfirm --onefile --noconsole --name KVM-Server --icon "%~dp0windows\kvm-server.ico" --add-data "%~dp0windows\kvm-server.ico;." --add-data "%~dp0windows\ui.html;." --add-data "%~dp0windows\client_ui.html;." --add-data "%~dp0windows\kvm-client.ico;." --add-data "%~dp0VERSION.txt;." --distpath . --workpath build --specpath build windows\server.py
if errorlevel 1 ( echo [오류] 서버 빌드 실패 & pause & exit /b 1 )
echo [2/2] 서브 PC용 KVM-Client.exe
"%PY%" -m PyInstaller --noconfirm --onefile --noconsole --name KVM-Client --icon "%~dp0windows\kvm-client.ico" --add-data "%~dp0windows\kvm-client.ico;." --add-data "%~dp0windows\client_ui.html;." --add-data "%~dp0VERSION.txt;." --add-data "%~dp0windows\ui.html;." --add-data "%~dp0windows\kvm-server.ico;." --distpath . --workpath build --specpath build windows\client.py
if not errorlevel 1 copy /y VERSION.txt KVM-Client.version >nul
if errorlevel 1 ( echo [오류] 클라이언트 빌드 실패 & pause & exit /b 1 )
rmdir /s /q build 2>nul
echo.
echo 완료: KVM-Server.exe 메인용, KVM-Client.exe 서브 PC용
echo KVM-Client.exe를 서브 PC로 옮겨 실행하면 됩니다. 이후 업데이트는 자동입니다.
pause
