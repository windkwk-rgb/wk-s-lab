# CLAUDE.md — wk-s-lab

여러 프로젝트를 담는 포트폴리오 저장소예요. 작업하기 전에 해당 프로젝트 폴더의 `CLAUDE.md`를 먼저 읽어 주세요.

| 폴더 | 내용 | 안내 |
|---|---|---|
| `PenBridge/` | 안드로이드 태블릿 → Windows 펜 입력 브릿지 (C# + Kotlin) | `PenBridge/CLAUDE.md` |
| `SoftKVM/` | 키보드·마우스 하나로 PC·맥·태블릿·아이폰을 오가는 소프트웨어 KVM (Python + Kotlin) | `SoftKVM/CLAUDE.md` |
| `D2RSkinBuilder/` | 디아블로 II 레저렉션 스킨(mpq)에 원하는 기능만 골라 얹는 스킨 빌더 (Go + HTML) | `D2RSkinBuilder/CLAUDE.md` |

## 저장소 규칙

- 프로젝트마다 **자기 폴더 안에서 완결**: 코드, 문서(`docs/`), 빌드 스크립트, 버전 파일.
- 자동 빌드는 루트 `.github/workflows/<프로젝트>.yml`, `paths:`로 자기 폴더가 바뀔 때만 실행.
- Release 태그는 `<프로젝트 소문자>-v<버전>` (예: `penbridge-v1.4.2`) — 한 저장소에 여러 프로젝트의 Release가 섞여도 구분되게.
- 비밀값(서명 키 등)은 GitHub Secrets에만. 키 파일 · 빌드 결과물은 커밋하지 않음 (`.gitignore`).
- 사용자는 한국어 사용자이고 개발 초보: 설명과 UI 문구는 쉬운 한국어(해요체).
- 커밋 메시지는 한국어로 무엇이 왜 바뀌었는지 한두 줄. 프로젝트 이름을 앞에: `PenBridge: …`

## 새 프로젝트 추가 절차

다른 대화에서 "wk-s-lab에 이 프로젝트를 등록해줘"라고 하면 이 순서로 진행해요.

1. **폴더 이름 정하기**: 영문 PascalCase (예: `PenBridge`, `SetupHub`). 이미 있는 폴더와 겹치면 안 됨. 애매하면 사용자에게 한 번 물어보기.
2. **`<프로젝트>/` 안에 모두 넣기**: 코드, `README.md`, `CLAUDE.md`, `docs/`(스크린샷은 `docs/images/`), `version.txt`(예: `1.0.0`), 빌드 방법.
   - 다른 프로젝트 폴더는 건드리지 않기.
   - 키 파일·비밀번호·빌드 결과물·개인 정보(실제 IP, 이메일 등)는 넣지 않기.
3. **프로젝트 `CLAUDE.md`**: 한 줄 요약, 폴더 구조, 빌드·실행 방법, 꼭 지킬 규칙, 남은 일. (`PenBridge/CLAUDE.md` 형식 참고)
4. **프로젝트 `README.md`**: 포트폴리오용. 맨 위 한 줄 소개 + 대표 스크린샷, 주요 기능 표, 구조(mermaid 가능), 기술 스택, 사용 방법, 직접 빌드하기.
   저장소 내부 링크는 상대 경로, Release 링크는 `https://github.com/windkwk-rgb/wk-s-lab/releases?q=<프로젝트 소문자>&expanded=true`.
5. **자동 빌드가 필요하면** `.github/workflows/<프로젝트 소문자>.yml`:
   `paths: ['<프로젝트>/**', '.github/workflows/<프로젝트 소문자>.yml']`, Release 태그 `<프로젝트 소문자>-v<버전>`,
   이미 있는 태그면 Release를 만들지 않기 (`penbridge.yml` 참고). 필요한 Secret 이름은 `<프로젝트 대문자>_…`로 하고 사용자에게 등록 방법 안내.
6. **루트 문서 갱신**: 루트 `README.md`의 프로젝트 목록에 카드 추가(아이콘·한 줄 소개·기술 태그·링크), 이 파일 맨 위 표에 한 줄 추가, `구성` 트리에 폴더 추가.
7. **커밋 & push**: `main`에 바로. 메시지 `<프로젝트>: 첫 공개 — 한 줄 설명 (v1.0.0)`. push 전에 `git pull --rebase`.
8. **확인**: 워크플로가 있으면 Actions 결과를 확인하고, 실패하면 로그를 읽고 고치기. 끝나면 사용자에게 저장소 링크와 바뀐 점을 쉬운 말로 알려 주기.
