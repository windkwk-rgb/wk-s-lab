@echo off
chcp 65001 >nul
net session >nul 2>&1
if errorlevel 1 (
  echo 관리자 권한으로 다시 실행합니다. 허용 창에서 예를 눌러주세요.
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)
title KVM 방화벽 허용
echo 다른 기기가 이 PC의 KVM 서버에 연결할 수 있도록 방화벽을 엽니다.
rem 방화벽 질문 창에서 취소를 누르면 생기는 '차단' 규칙 제거 (허용보다 우선이라 연결을 막음)
netsh advfirewall firewall delete rule name="kvm-server.exe" >nul 2>&1
netsh advfirewall firewall delete rule name="KVM-Server.exe" >nul 2>&1
netsh advfirewall firewall delete rule name="KVM-Server" >nul 2>&1
netsh advfirewall firewall delete rule name="python.exe" dir=in action=block >nul 2>&1
netsh advfirewall firewall delete rule name="pythonw.exe" dir=in action=block >nul 2>&1
netsh advfirewall firewall delete rule name="KVM Server App" >nul 2>&1
netsh advfirewall firewall add rule name="KVM Server App" dir=in action=allow program="%~dp0KVM-Server.exe" enable=yes profile=any >nul
netsh advfirewall firewall delete rule name="KVM Server 24800" >nul 2>&1
netsh advfirewall firewall delete rule name="KVM App Send 8765" >nul 2>&1
netsh advfirewall firewall delete rule name="KVM Discover 24800" >nul 2>&1
netsh advfirewall firewall add rule name="KVM Server 24800" dir=in action=allow protocol=TCP localport=24800 profile=any >nul
netsh advfirewall firewall add rule name="KVM App Send 8765" dir=in action=allow protocol=TCP localport=8765 profile=any >nul
netsh advfirewall firewall add rule name="KVM Discover 24800" dir=in action=allow protocol=UDP localport=24800 profile=any >nul
if errorlevel 1 (
  echo [실패] 방화벽 규칙을 추가하지 못했습니다.
) else (
  echo 완료: 24800, 8765 포트를 허용했습니다. 서버를 켠 상태에서 태블릿 앱의 저장하고 연결을 다시 누르세요.
)
pause
