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
title KVM 서브 PC
"%PY%" -m pip install --quiet --disable-pip-version-check pyperclip pywebview pystray pillow
if errorlevel 1 ( echo [오류] 패키지 설치 실패 & pause & exit /b 1 )
:run
"%PY%" windows\client.py
if errorlevel 3 if not errorlevel 4 goto run
pause
