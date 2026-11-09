# wk's lab

직접 기획하고 만든 프로젝트를 모아 두는 저장소예요.
각 프로젝트는 자기 폴더 안에 코드 · 문서 · 빌드 방법을 모두 담고 있어요.

## 프로젝트

<table>
<tr>
<td width="96" valign="top"><img src="PenBridge/docs/images/icon.png" width="80" alt="PenBridge"></td>
<td valign="top">

### [PenBridge](PenBridge/)
안드로이드 태블릿을 Windows 노트북의 **펜 태블릿 · 액정 타블렛**으로.
필압 · 기울기 · 펜 버튼을 Windows 실제 펜 입력으로 전달하고, 단축키 툴바 · 손가락 제스처 · 노트북 화면 미러링 · 자동 업데이트까지.

`C# .NET 10` `WebView2` `Win32 Pointer Injection` `Kotlin` `Jetpack Compose` `GitHub Actions`

[README](PenBridge/README.md) · [설계 문서](PenBridge/docs/ARCHITECTURE.md) · [내려받기](https://github.com/windkwk-rgb/wk-s-lab/releases?q=penbridge&expanded=true)

</td>
</tr>
</table>

<img src="PenBridge/docs/images/laptop-home.png" alt="PenBridge 노트북 앱">

## 구성

```
PenBridge/            태블릿 ↔ 노트북 펜 입력 브릿지
.github/workflows/    프로젝트별 자동 빌드 · 릴리스 (태그: <프로젝트>-v<버전>)
```

## 라이선스

[MIT](LICENSE)
