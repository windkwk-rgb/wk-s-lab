# CLAUDE.md — SoftKVM 작업 안내

AI 코딩 도우미(Claude 등)가 이 폴더에서 작업할 때 먼저 읽는 문서예요.

> 이 폴더는 포트폴리오 저장소 `windkwk-rgb/wk-s-lab`의 한 프로젝트예요. 경로는 모두 `SoftKVM/` 기준.

## 프로젝트 한 줄 요약

윈도우 PC에 꽂힌 키보드·마우스 하나로 **서브 윈도우 PC · 맥 · 안드로이드 태블릿 · 아이폰**을 오가며 쓰는 소프트웨어 KVM.
매크로 녹화·실행, 배터리 표시, 연결 프로필, 설치 파일 나눠 주기, 자동 업데이트까지.

- `windows/server.py` 메인 PC (Python · pynput 훅 · pywebview UI `ui.html` · pystray 트레이) → `KVM-Server.exe`
- `windows/client.py` 서브 윈도우 PC (UI `client_ui.html`) → `KVM-Client.exe`. 화면에서 메인으로 역할 전환 가능
- `windows/kvm_common.py` 메인·서브 공통 (연결 프로필, 와이파이 이름, 메인 PC 찾기, 화면 위 표시줄 `MacroOverlay`)
- `mac/client.py` 맥 (Quartz 이벤트 주입)
- `android/` 태블릿 앱 (Kotlin) — 패키지 `com.example.kvm`
- 사용자는 한국어 사용자이고 개발 초보. UI 문구·설명은 **쉬운 한국어 존댓말(해요체)**

## 폴더 구조

```
VERSION.txt            버전 (서버·클라이언트·apk가 모두 여기서 읽음)
windows/               server.py · client.py · kvm_common.py · ui.html · client_ui.html · 아이콘
mac/                   client.py · 'KVM 맥 실행.command'
android/app/src/main/java/com/example/kvm/
  KvmAccessibilityService.kt  연결·입력 주입·화면 위 배지(매크로·배터리)·한글 IME 전환
  UhidLink.kt / UhidHelper.kt  'uhid' 가상 키보드(8바이트 표준)·마우스(report id 2) — 진짜 마우스 모드
  KvmIme.kt / HangulComposer.kt 한글 입력 (조합·commitText)
  IphoneRelay.kt / BleHid.kt   태블릿 → 아이폰 블루투스 HID 중계
  MainActivity.kt              앱 화면 (탭: 연결 · 매크로 · 설정)
  NetClient.kt / Prefs.kt      서버 연결 · 설정 저장
tools/                 rebuild_restart.bat(패치 후 exe 다시 만들기) · build_apk.ps1 · apply_patch.ps1
*.bat                  사용자용 실행·빌드 도우미 (한글 이름)
docs/                  USER-GUIDE.md(자세한 사용 설명) · images/(README 스크린샷)
```

## 꼭 지킬 것

1. **버전은 `VERSION.txt` 하나**. 기능을 바꾸면 올릴 것 — 서브 PC·태블릿·맥 자동 업데이트가 버전 비교로 동작함.
   apk versionCode = `1.28.1` → `12801`.
2. **통신**: TCP 24800, 한 줄에 JSON 하나(`{"t": ...}`). 새 메시지 종류는 예전 버전이 모르면 무시하게. 기기 찾기는 UDP 방송 `KVM-DISCOVER`.
   설치 파일 나눠 주기는 HTTP 8765 (`ShareServer`).
3. **안드로이드 Esc**: 그냥 Esc는 '뒤로 가기'가 되므로 Win+Esc를 **한 번에 눌렀다 떼기**(`UhidLink.tapMetaEsc`)로 보냄. Win을 따로 누른 채 두면 앱 서랍이 열림.
4. **키는 누를 때 간 경로로 뗄 것** (uhid/IME 섞이면 키가 눌린 채 남아 'gggg'가 반복됨 — `keyToIme`).
5. **매크로는 기기별 저장**: 서브 PC·태블릿 매크로는 그 기기에 저장(`macro_store`/`macro_lib`), 메인은 `macros.json`. 실행은 `only_device`로 그 기기에만.
6. **한글 파일 이름**은 패치 zip에 넣지 않기 (윈도우 압축 해제에서 깨짐). zip을 만들 땐 UTF-8 플래그(0x800).
7. 개인 설정 파일(`config.json`, `profiles.json`, `macros*.json`, `*.log`)과 빌드 결과물은 커밋하지 않기 (`.gitignore`).
8. 이 폴더만으로는 Linux에서 exe·apk 빌드 확인이 어려움. Python은 `py_compile`, UI는 `node --check`로 확인하고 빌드 결과는 Actions 로그로 확인.

## 빌드 · 실행

- 메인 PC 개발 실행: `서버실행.bat` (Python 필요) · 서브 PC: `서브PC실행.bat`
- exe: `exe만들기.bat` → `KVM-Server.exe`, `KVM-Client.exe`
- apk: `apk만들기.bat` (→ `tools/build_apk.ps1`, JDK·SDK·Gradle 자동 설치) → `KVM-Android.apk`
- 패치: zip을 다운로드 폴더나 이 폴더에 두면 메인 PC가 적용 → `tools/rebuild_restart.bat`가 새 exe를 만들고 바꿔 끼움 → 서브 PC·태블릿에 자동 배포
- GitHub Actions: 루트 `.github/workflows/softkvm.yml` — `SoftKVM/**`가 바뀐 push마다 빌드,
  `VERSION.txt` 버전의 Release(태그 `softkvm-v버전`)가 없으면 생성. 선택 Secret `SOFTKVM_DEBUG_KEYSTORE_B64`(태블릿 앱 덮어 설치용)

## 설정 저장 위치

- 메인 PC: 프로그램 폴더의 `config.json`, `macros.json`, `profiles.json`, 기록 `kvm-server.log`
- 서브 PC: `%LOCALAPPDATA%\KVM\client_config.json`, `client_profiles.json`
- 태블릿: SharedPreferences (`Prefs.kt`)

## 남은 일

- 디아블로2 테러존 표시 (d2emu는 허락받은 API만 가능 · d2runewizard 공개 API는 현재 존만) — 방식 결정 대기
- 태블릿 배터리 값이 오락가락하는 문제: `BatteryManager` 우선 + 불일치 기록 추가함, 실제 기록 확인 필요
- 통신 암호화 (지금은 비밀번호 확인만, 공용 와이파이 비권장)
