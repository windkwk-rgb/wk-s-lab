# CLAUDE.md — PenBridge 작업 안내

AI 코딩 도우미(Claude 등)가 이 저장소에서 작업할 때 먼저 읽는 문서예요.

> 이 폴더는 포트폴리오 저장소 `windkwk-rgb/wk-s-lab`의 한 프로젝트예요. 경로는 모두 `PenBridge/` 기준.

## 프로젝트 한 줄 요약

안드로이드 태블릿을 Windows 노트북의 펜 태블릿/액정 타블렛으로 쓰는 앱 한 쌍.
- `android/` 태블릿 앱 (Kotlin, Compose + 커스텀 View) — 패키지 `com.penbridge`
- `windows/` 노트북 앱 (C# .NET 10, WinForms 창 + WebView2 HTML UI) — 단일 exe
- 사용자는 한국어 사용자이고 개발 초보. UI 문구·설명은 **쉬운 한국어 존댓말(해요체)**, 전문 용어는 풀어서

## 꼭 지킬 것

1. **버전은 `version.txt` 하나**로 관리. 노트북 csproj와 Gradle이 모두 여기서 읽음 (`1.2.3` → versionCode `10203`).
   기능을 바꾸면 버전을 올릴 것 — 자동 업데이트가 버전 비교로 동작함.
2. **통신 규격을 바꾸면 `PROTOCOL.md`도 고칠 것.** 패킷은 24바이트 고정, 리틀엔디언.
   새 패킷 타입은 기존 번호와 겹치지 않게 (사용 중: 1~17, 20). 예전 버전과 섞여도 동작하게(모르는 타입은 무시).
3. **태블릿 동작 번호**(`Protocol.kt` `A_*`)와 노트북 `Proto.ActionIds` 순서는 항상 같아야 함.
4. **APK 서명 키를 바꾸지 말 것.** 노트북 빌드(기본 debug 키)와 GitHub Actions(`PENBRIDGE_KEYSTORE_B64` 비밀값)가
   같은 키여야 기존 태블릿 앱 위에 업데이트됨. 키 파일을 저장소에 올리지 말 것.
5. `build.ps1`은 **UTF‑8 BOM + CRLF**로 저장 (Windows PowerShell 5.1이 한글을 깨뜨리지 않게).
6. 노트북 UI는 `windows/ui/index.html` 한 파일 (QR 라이브러리는 `qrcode.js`, 실행 시 `<!--QRLIB-->` 자리에 끼워 넣음).
   브라우저에서 바로 열면 가짜 상태로 미리보기가 됨 (`#home`, `#share`, `#keys`, `#pen`, `#devices`, `#update`, `#settings`, `#idle`).

## 빌드

- Windows: `PenBridge-build.bat` (→ `build.ps1`). 도구가 없으면 winget/구글 공식 배포본으로 자동 설치.
  APK를 먼저 만들고 `windows/tablet/PenBridge.apk`에 복사한 뒤 EXE에 포함시킴. 기록: `PenBridge-build-log.txt`
- GitHub Actions: 저장소 루트의 `.github/workflows/penbridge.yml` — `PenBridge/**`가 바뀐 push마다 빌드,
  `version.txt` 버전의 Release(태그 `penbridge-v버전`)가 없으면 생성. 앱은 이 태그 접두사로 자기 Release만 골라 업데이트
  (`windows/UpdateSource.cs`). 서명 키 비밀값: `PENBRIDGE_KEYSTORE_B64`
- 이 저장소만으로는 Linux에서 빌드 확인이 어려움 (Android SDK·.NET이 필요). 문법은 신중히, 빌드 결과는 Actions 로그로 확인.

## 주요 파일

| 파일 | 역할 |
|---|---|
| `windows/PenServer.cs` | TCP 서버. 연결 종류(HELLO/STREAM/DISCOVER/UPDATE_REQUEST) 분기, 펜·동작·제스처 처리 |
| `windows/PenInjector.cs` | Windows 합성 펜 입력 (구조체 레이아웃은 `Native.cs`, 64비트 크기 검증 있음) |
| `windows/Shortcuts.cs` | 단축키 프리셋, SendInput 키보드/휠, 지우개 토글·손 도구 상태 |
| `windows/ScreenStreamer.cs` | 액정 타블렛 모드 화면 캡처·JPEG·한 장씩 전송 |
| `windows/AdbHelper.cs` | adb 기기 목록·reverse·앱 설치. adb 없으면 자동 다운로드 |
| `windows/Updater.cs` | 패치 파일 감지, exe 교체·재시작, 소스 zip 빌드, GitHub Releases 확인 |
| `windows/Installer.cs` | exe 자체 설치/제거, 바로가기, 방화벽 규칙 |
| `windows/ShareServer.cs` | '기기 추가' 웹 서버 (:8765, 30분 자동 종료) |
| `windows/MainForm.cs` | 창·트레이, WebView2 ↔ C# 메시지(명령 `cmd`, 상태 JSON) |
| `android/.../PenPadView.kt` | 입력판: 펜 → 패킷, 손가락 제스처, 화면 프레임 그리기 |
| `android/.../PadActivity.kt` | 입력판 화면: 연결·툴바·화면 보기·자동 업데이트 |
| `android/.../PadToolbar.kt` | 세로 툴바 (Compose) |
| `android/.../MainActivity.kt` | 첫 화면 (저장된 노트북, 새 연결, 설정) |
| `android/.../Discovery.kt` | Wi‑Fi 노트북 찾기 (UDP + TCP 탐색) |

## 설정 저장 위치

- 노트북: `%LOCALAPPDATA%\PenBridge\settings.json` (보관 중인 태블릿 앱: `tablet\PenBridge.apk`)
- 태블릿: SharedPreferences `penbridge` (`Prefs.kt`)

## 디자인 원칙

- 브랜드 색: 보라 그라데이션 `#9474FF → #4B37DF`, 강조 `#5B45E6`(라이트) / `#A08CFF`(다크)
- 아이콘: 태블릿 + 스타일러스 (시안 B). 원본 SVG는 `docs/images/icon.png`와 각 플랫폼 리소스
- 노트북: 왼쪽 메뉴 + 카드형, 스크롤 최소화, 알림은 오른쪽 아래 토스트
- 태블릿: Material 3, 버튼 48dp 이상, 그리는 화면은 항상 어두운 배경

## 남은 일 (로드맵)

- 액정 타블렛 모드 H.264(하드웨어 인코딩/디코딩) 옵션
- 실제 사용 피드백 반영 (필압 느낌, 제스처 오작동 등)
- 태블릿 배터리 표시
