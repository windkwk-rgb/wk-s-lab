# PenBridge 통신 규격 (v1)

- TCP 포트 **47100** (펜 데이터), UDP 포트 **47101** (Wi‑Fi 자동 검색)
- 모든 TCP 패킷은 **24바이트 고정**, 리틀엔디언
- USB 연결은 `adb reverse tcp:47100 tcp:47100`으로 태블릿의 127.0.0.1:47100 → PC로 전달

## 태블릿 → PC

| 종류 | [0] | [1] | [2] | [4..7] | [8..11] | [12..15] | [16..19] | [20..23] |
|---|---|---|---|---|---|---|---|---|
| HELLO | 1 | 버전(1) | – | PIN (int32) | 화면 폭 | 화면 높이 | 앱 versionCode | 플래그 (1=자동 업데이트) |
| UPDATE_REQUEST | 5 | 버전 | – | PIN (int32) | – | – | – | – |
| DISCOVER | 6 | 버전 | – | – | – | – | – | – |
| PEN | 2 | 상태 | 버튼 | x (0~1) | y (0~1) | 필압 (0~1) | 기울기X (도) | 기울기Y (도) |
| PING | 3 | 버전 | – | – | – | – | – | – |
| ACTION | 7 | 동작 번호 | 1 누름 / 0 뗌 | – | – | – | – | – |
| GESTURE | 8 | 1 이동 / 2 확대 | – | dx(dp) 또는 배율 | dy(dp) | – | – | – |
| STREAM | 9 | 버전 | – | PIN | 최대 폭(px) | – | – | – |
| FRAME_ACK | 20 | 버전 | – | – | – | – | – | – |

- 상태: 0 = 호버(떠 있음), 1 = 접촉, 2 = 범위 밖
- 버튼 비트: 1 = 사이드 버튼, 2 = 지우개 끝, 4 = 두 번째 버튼
- 기울기: Windows 규칙. X는 오른쪽으로 기울면 +, Y는 사용자 쪽(아래)으로 기울면 +
- 태블릿은 1초마다 PING을 보낸다. PC는 6초간 아무것도 받지 못하면 연결을 끊는다.

## PC → 태블릿

| 종류 | [0] | [1] | [4..7] |
|---|---|---|---|
| PONG | 4 | 버전 | – |
| CONFIG | 10 | 버전 | 화면 비율 (가로/세로, float). 0이면 태블릿 전체 사용 |
| REJECT | 11 | 버전 | – (PIN 틀림) |
| ACCEPT | 12 | 버전 | PC 이름 길이, [8..23] PC 이름 (UTF‑8, 1.2 이상) |
| DISCOVER_REPLY | 15 | 버전 | PC 이름 길이, [8..23] PC 이름 |
| FRAME | 16 | 버전 | JPEG 길이, [8] 폭, [12] 높이, 이어서 JPEG |
| TOOL_STATE | 17 | [1] 지우개 켜짐 | – |
| UPDATE_OFFER | 13 | 버전 | 새 versionCode, [8..15] 크기(int64) |
| UPDATE_DATA | 14 | 버전 | versionCode, [8..15] 크기(int64), 이어서 APK 내용 |

## 연결 순서

1. 태블릿 → HELLO
2. PC: USB(루프백)면 PIN 확인 생략. Wi‑Fi면 PIN 확인 → 틀리면 REJECT 후 종료
3. PC → ACCEPT, CONFIG
4. 태블릿 → PEN/PING 반복. 설정이 바뀌면 PC가 CONFIG를 다시 보냄
5. 새 태블릿이 연결되면 이전 연결은 끊김

## Wi‑Fi 자동 검색

두 방법을 동시에 사용:
1. 태블릿이 UDP 47101로 `PENBRIDGE?`를 브로드캐스트 → PC가 `PENBRIDGE!|PC이름|47100`으로 응답
2. 같은 /24 네트워크의 모든 주소에 TCP 47100으로 접속해 DISCOVER → PC가 DISCOVER_REPLY (1.1 이하 PC는 그냥 끊음 → 주소로 표시)

태블릿은 연결했던 PC를 이름과 함께 저장하고, 접속이 두 번 실패하면 같은 이름의 PC를 다시 찾아 새 주소로 이어 붙는다.

## 태블릿 앱 자동 업데이트

- versionCode = 주\*10000 + 부\*100 + 수 (version.txt 1.2.3 → 10203)
- 연결(HELLO) 때 PC가 보관 중인 APK가 더 새것이면:
  - USB: PC가 `adb install -r` 후 `am start -n com.penbridge/.MainActivity --ez autoconnect true`
  - Wi‑Fi: PC → UPDATE_OFFER. 태블릿이 새 TCP 연결로 UPDATE_REQUEST → PC가 UPDATE_DATA + 파일 전송
- 1.0 앱(versionCode 0)은 USB로만 업데이트 가능

## 단축키 · 제스처 (1.4)

동작 번호: 1 실행 취소, 2 다시 실행, 3 지우개 토글, 4 브러시 작게, 5 브러시 크게, 6 손 도구(누르는 동안), 7 확대, 8 축소, 9 화면 맞춤, 10 저장.
노트북이 프리셋(Windows 기본/클립 스튜디오/포토샵/Krita)과 사용자 지정에 따라 키 입력(SendInput)으로 바꾼다.
제스처: 이동은 휠/가로 휠, 확대·축소는 (Ctrl 등)+휠.

## 액정 타블렛 모드 (1.4)

태블릿이 새 연결로 STREAM → PC가 ACCEPT(꺼져 있으면 REJECT) → FRAME을 보내고, 태블릿이 FRAME_ACK을 보내야 다음 FRAME.
화면이 바뀌지 않으면 보내지 않고, 2초마다 한 장은 보낸다. 최대 초당 30장.
