# CLAUDE.md — D2RSkinBuilder 작업 안내

> 이 폴더는 포트폴리오 저장소 `windkwk-rgb/wk-s-lab`의 한 프로젝트예요. 경로는 모두 `D2RSkinBuilder/` 기준.

## 프로젝트 한 줄 요약

디아블로 II 레저렉션 스킨(mpq)에 원하는 기능만 골라 얹어 새 스킨(`mods\skinbuilder`)을 만드는 Windows 프로그램 (Go 서버 + HTML 화면, exe 하나).
- 사용자는 한국어 사용자이고 개발 초보. 화면 문구·설명은 **쉬운 한국어(해요체)**, 전문 용어는 풀어서.

## 폴더 구조

| 경로 | 내용 |
|---|---|
| `main.go` | 시작, 설정 저장, HTTP API (`/api/state` `/api/build` …), 앱 창 열기 |
| `features.go` | 기능 묶음 읽기, 기준 스킨 읽기, 작업 공간, 연산(op) 대부분, mpq 만들기 |
| `ops_layout.go` | 3.x 연산: `gen_file` `regex_sub` `append_in` `shift_y` `icon_shift` `fit_sprite` `copy_*`, 화면 배치 숫자(layoutTokens) |
| `mpq.go` · `mpqread.go` | MPQ v1 쓰기 · 읽기 (직접 구현) |
| `patch.go` · `mpqupdate.go` · `selfupdate.go` | 패치 파일, mpq 안 업그레이드, 새 exe 바꿔치기 |
| `profile.go` · `bisect.go` · `baseinfo.go` | 내 설정 · 튕김 원인 찾기 · 기준 스킨 바뀐 점 |
| `cli.go` | 개발용 명령줄 `--build` `--status` `--diff` |
| `install_windows.go` | 설치·제거. exe 이름에 `setup`/`설치`가 있으면 설치 프로그램(`%LOCALAPPDATA%\Programs\D2R스킨빌더`, 바로가기, HKCU 제거 등록), `--uninstall` 은 제거(내 설정은 남김) |
| `payload.go` | 설치 파일 끝에 붙인 파일(기준 스킨 mpq) — `[exe][파일…][목록 JSON][길이][SBSETUP1]`. 설치 때 프로그램 부분만 복사하고 붙은 파일은 `기준스킨\` 에 풀어 스킨 목록에 "함께 설치"로 보여 줌. 만들기 `--make-setup` |
| `rsrc_windows_amd64.syso` | exe 아이콘 (`tools/make_icon_syso.py` 로 다시 만듦) |
| `sys_windows.go` · `sys_other.go` | 창·파일 고르기·오류 창 (윈도우 / 그 외는 시험용) |
| `pack_*.go` · `blobs_*.go` | 기능 묶음 넣기. 빌드 태그 `private`(실제 묶음) · `slim`(데이터 없는 패치용 exe) |
| `pack/sample/` | 공개용 예제 기능 묶음 (다른 제작자 파일 없음) |
| `private/` | 실제 기능 묶음 — **커밋하지 않음** (`.gitignore`). `tools/extract_pack.py` 로 exe 에서 되살릴 수 있음 |
| `ui/index.html` | 화면 한 파일 (프레임워크 없음) |
| `tools/` | 예제 묶음 만들기, exe 에서 묶음 꺼내기, 스냅숏·패치 만들기 |
| `docs/ARCHITECTURE.md` | 동작 원리 |

## 빌드·실행

- Windows: `build.bat` (→ `build.ps1`, UTF‑8 BOM + CRLF). `private\features.json` 이 있으면 실제 묶음, 없으면 예제 묶음.
- 어디서나: `go test ./...`, `GOOS=windows go build -tags private -ldflags "-H windowsgui" .`
- 리눅스에서 화면 시험: `SKINBUILDER_DIR=<설정 폴더> go run -tags private .` → `http://127.0.0.1:47651/` (파일 고르기는 환경 변수 `SKINBUILDER_PICK` 등)
- 결과 확인: `go run -tags private . --build 기준.mpq preset out.mpq` 후 `--diff out.mpq 예전결과.mpq`
- 자동 빌드: 루트 `.github/workflows/d2rskinbuilder.yml` — 시험 + 예제 묶음 exe 빌드, `version.txt` 버전의 Release(태그 `d2rskinbuilder-v버전`)가 없으면
  예제판 설치 파일(`D2RSkinBuilder-setup-v버전.exe`)로 생성. 실제 묶음 설치 파일은 저장소 밖에서 `-tags private` 로 만들어 따로 전달

## 꼭 지킬 것

1. **다른 제작자의 스킨 파일(실제 기능 묶음, 미리보기 그림)을 커밋하지 않기.** `private/`, `release/`, `dist/` 는 `.gitignore`.
2. 버전: `version.txt`(예 `3.2.0`)와 `main.go` 의 `Version`(`3.2`, 패치 비교용)을 같이 올리기. 패치는 `verCmp` 로 비교.
3. 기능을 고친 뒤에는 **같은 기준 스킨 + 같은 설정으로 만든 mpq 를 이전 결과와 `--diff`** 해서 의도한 파일만 바뀌었는지 확인.
4. 연산은 기준 스킨을 덮어쓰지 않는 쪽으로 (덧붙이기·고치기). 모양이 다르면 `else` 로 통째 넣기.
5. 게임이 튕기는 원인이 됐던 것: 기준 스킨(yupgoolg_1)에 인벤토리 길찾기·득템 기준·퀘스트 안내 글자를 더하면 튕김 — 해당 기능은 기본에서 뺌.
6. 화면 JSON 에 덧붙일 때 마지막 줄 끝 `//` 주석 뒤에 쉼표가 붙지 않게 (`stripTrailingComments`).

## 남은 일

- 공개용 "스킨 짜깁기" 도구: 사용자가 가진 mpq 여러 개를 올려 기능 단위로 골라 합치기 (스킨 파일 없이 도구만 배포)
- GitHub Releases 로 업데이트 확인 (비공개 저장소 또는 데이터 없는 배포 방식 정하기)
- 몬스터 특성 줄 글자 크기별 내림 값(`fontShift`)과 버프 아이콘 이동 방향은 게임에서 다시 확인 필요 (원본 값이 사라져 다시 정함)
