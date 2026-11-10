//go:build windows

package main

// 설치·제거 — 파일 이름에 setup(또는 "설치")이 들어간 exe 를 실행하면 설치 프로그램으로 동작해요.
//   설치 위치: %LOCALAPPDATA%\Programs\D2R스킨빌더 (관리자 권한 필요 없음)
//   바로가기: 시작 메뉴 · 바탕 화면, 제거 등록: 설정 → 앱 (HKCU)
//   제거: 설정 → 앱에서 "D2R 스킨 빌더" 제거 (= D2R스킨빌더.exe --uninstall). 내 설정 파일은 남겨 둬요.

import (
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"unsafe"
)

const (
	appTitle   = "D2R 스킨 빌더"
	installExe = "D2R스킨빌더.exe"
	uninstKey  = `HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\D2RSkinBuilder`
)

func installDir() string {
	return filepath.Join(os.Getenv("LOCALAPPDATA"), "Programs", "D2R스킨빌더")
}

// isSetupExe — 설치 프로그램으로 실행됐는지 (이미 설치 폴더 안의 exe 면 아님)
func isSetupExe(exe string) bool {
	n := strings.ToLower(filepath.Base(exe))
	if !strings.Contains(n, "setup") && !strings.Contains(n, "설치") {
		return false
	}
	return !strings.EqualFold(filepath.Dir(exe), installDir())
}

func messageBox(msg string, flags uintptr) int {
	box := syscall.NewLazyDLL("user32.dll").NewProc("MessageBoxW")
	t, _ := syscall.UTF16PtrFromString(msg)
	c, _ := syscall.UTF16PtrFromString(appTitle)
	r, _, _ := box.Call(0, uintptr(unsafe.Pointer(t)), uintptr(unsafe.Pointer(c)), flags)
	return int(r)
}

// powershell — 경로는 환경 변수로 넘겨서 한글·빈칸·따옴표가 깨지지 않게
func powershell(script string, env ...string) error {
	c := hidden(exec.Command("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script))
	c.Env = append(os.Environ(), env...)
	return c.Run()
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	tmp := dst + ".설치중"
	out, err := os.Create(tmp)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		os.Remove(tmp)
		return err
	}
	out.Close()
	os.Remove(dst + ".old")
	if _, err := os.Stat(dst); err == nil {
		if err := os.Rename(dst, dst+".old"); err != nil { // 켜져 있으면 바꿀 수 없음
			os.Remove(tmp)
			return err
		}
	}
	return os.Rename(tmp, dst)
}

// runSetup — 설치하고 설치된 프로그램을 켬. 처리했으면 true
func runSetup(exe string) bool {
	if !isSetupExe(exe) && !(len(os.Args) == 2 && os.Args[1] == "--install") {
		return false
	}
	dir := installDir()
	target := filepath.Join(dir, installExe)
	if messageBox(fmt.Sprintf("%s %s 을(를) 설치할까요?\n\n설치 위치: %s\n시작 메뉴와 바탕 화면에 바로가기를 만들어요.", appTitle, Version, dir), 0x4|0x20) != 6 {
		return true
	}
	if err := os.MkdirAll(dir, 0755); err != nil {
		messageBox("설치 폴더를 만들지 못했어요: "+err.Error(), 0x10)
		return true
	}
	if err := copyFile(exe, target); err != nil {
		messageBox("프로그램을 복사하지 못했어요. 스킨 빌더가 켜져 있으면 창을 닫고 다시 실행해 주세요.\n\n"+err.Error(), 0x10)
		return true
	}
	// 예전에 쓰던 설정(설치 파일과 같은 폴더)이 있으면 옮겨 오기
	for _, n := range []string{"스킨빌더_설정.json", "스킨빌더_기준스킨.json"} {
		src, dst := filepath.Join(filepath.Dir(exe), n), filepath.Join(dir, n)
		if _, err := os.Stat(dst); err != nil {
			if _, err := os.Stat(src); err == nil {
				copyFile(src, dst)
			}
		}
	}
	// 바로가기 (시작 메뉴 · 바탕 화면)
	powershell(`$w = New-Object -ComObject WScript.Shell; `+
		`foreach ($f in @([Environment]::GetFolderPath('Programs'), [Environment]::GetFolderPath('Desktop'))) { `+
		`$s = $w.CreateShortcut((Join-Path $f ($env:SB_NAME + '.lnk'))); $s.TargetPath = $env:SB_EXE; `+
		`$s.WorkingDirectory = $env:SB_DIR; $s.IconLocation = $env:SB_EXE + ',0'; $s.Description = $env:SB_NAME; $s.Save() }`,
		"SB_NAME="+appTitle, "SB_EXE="+target, "SB_DIR="+dir)
	// 설정 → 앱 에 제거 등록
	size := int64(0)
	if st, err := os.Stat(target); err == nil {
		size = st.Size() / 1024
	}
	for _, kv := range [][3]string{
		{"DisplayName", "REG_SZ", appTitle}, {"DisplayVersion", "REG_SZ", Version}, {"Publisher", "REG_SZ", "wk's lab"},
		{"DisplayIcon", "REG_SZ", target}, {"InstallLocation", "REG_SZ", dir},
		{"UninstallString", "REG_SZ", `"` + target + `" --uninstall`},
		{"NoModify", "REG_DWORD", "1"}, {"NoRepair", "REG_DWORD", "1"}, {"EstimatedSize", "REG_DWORD", fmt.Sprint(size)},
	} {
		hidden(exec.Command("reg", "add", uninstKey, "/v", kv[0], "/t", kv[1], "/d", kv[2], "/f")).Run()
	}
	messageBox("설치했어요.\n바탕 화면이나 시작 메뉴의 \""+appTitle+"\"로 실행할 수 있어요.\n지금 실행할게요.", 0x40)
	startSelf(target)
	return true
}

// runUninstall — --uninstall: 바로가기·제거 등록·프로그램을 지움 (내 설정 파일은 남김)
func runUninstall(exe string) bool {
	if !(len(os.Args) == 2 && os.Args[1] == "--uninstall") {
		return false
	}
	if messageBox(appTitle+"을(를) 제거할까요?\n\n만든 스킨(게임 폴더 mods\\skinbuilder)과 내 설정 파일은 남겨 둬요.", 0x4|0x20) != 6 {
		return true
	}
	dir := filepath.Dir(exe)
	powershell(`foreach ($f in @([Environment]::GetFolderPath('Programs'), [Environment]::GetFolderPath('Desktop'))) { `+
		`Remove-Item -Force -ErrorAction SilentlyContinue (Join-Path $f ($env:SB_NAME + '.lnk')) }`, "SB_NAME="+appTitle)
	hidden(exec.Command("reg", "delete", uninstKey, "/f")).Run()
	// 켜져 있는 자기 자신은 끝난 뒤에 지움
	c := hidden(exec.Command("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
		`Start-Sleep -Seconds 2; Get-ChildItem -LiteralPath $env:SB_DIR | Where-Object { $_.Name -notlike '스킨빌더_설정*' -and $_.Name -ne '스킨빌더_기준스킨.json' } | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue`))
	c.Env = append(os.Environ(), "SB_DIR="+dir)
	c.Start()
	messageBox("제거했어요.", 0x40)
	return true
}
