//go:build windows

package main

// 윈도우 전용: 앱 창 열기, 폴더·파일 고르기, 오류 창, 게임 폴더 찾기

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"unsafe"
)

func hidden(c *exec.Cmd) *exec.Cmd {
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	return c
}

// openWindow — Edge(없으면 Chrome)의 앱 모드로 주소 창 없이 열고, 둘 다 없으면 기본 브라우저
func openWindow(url string) {
	for _, p := range []string{
		filepath.Join(os.Getenv("ProgramFiles(x86)"), `Microsoft\Edge\Application\msedge.exe`),
		filepath.Join(os.Getenv("ProgramFiles"), `Microsoft\Edge\Application\msedge.exe`),
		filepath.Join(os.Getenv("ProgramFiles"), `Google\Chrome\Application\chrome.exe`),
		filepath.Join(os.Getenv("LocalAppData"), `Google\Chrome\Application\chrome.exe`),
	} {
		if _, err := os.Stat(p); err == nil {
			if exec.Command(p, "--app="+url, "--window-size=1180,860").Start() == nil {
				return
			}
		}
	}
	hidden(exec.Command("rundll32", "url.dll,FileProtocolHandler", url)).Start()
}

func openFolder(dir string) { exec.Command("explorer", dir).Start() }

func showError(msg string) {
	box := syscall.NewLazyDLL("user32.dll").NewProc("MessageBoxW")
	t, _ := syscall.UTF16PtrFromString(msg)
	c, _ := syscall.UTF16PtrFromString("스킨 빌더")
	box.Call(0, uintptr(unsafe.Pointer(t)), uintptr(unsafe.Pointer(c)), 0x10)
}

func psDialog(ps string) (string, error) {
	out, err := hidden(exec.Command("powershell", "-NoProfile", "-STA", "-Command", ps)).Output()
	return strings.TrimSpace(string(out)), err
}

func pickFolder() (string, error) {
	return psDialog(`Add-Type -AssemblyName System.Windows.Forms; $f = New-Object System.Windows.Forms.FolderBrowserDialog; ` +
		`$f.Description = '디아블로 II 레저렉션이 설치된 폴더(D2R.exe 가 있는 곳)를 골라 주세요'; ` +
		`$w = New-Object System.Windows.Forms.Form; $w.TopMost = $true; ` +
		`if ($f.ShowDialog($w) -eq 'OK') { [Console]::OutputEncoding = [Text.Encoding]::UTF8; Write-Output $f.SelectedPath }`)
}

func fileDialog(title, filter, initDir string) (string, error) {
	return psDialog(`Add-Type -AssemblyName System.Windows.Forms; $f = New-Object System.Windows.Forms.OpenFileDialog; ` +
		`$f.Title = '` + title + `'; $f.Filter = '` + filter + `'; ` +
		`$f.InitialDirectory = '` + strings.ReplaceAll(initDir, "'", "''") + `'; ` +
		`$w = New-Object System.Windows.Forms.Form; $w.TopMost = $true; ` +
		`if ($f.ShowDialog($w) -eq 'OK') { [Console]::OutputEncoding = [Text.Encoding]::UTF8; Write-Output $f.FileName }`)
}

func pickFile(initDir string) (string, error) {
	return fileDialog("기준으로 쓸 스킨 파일(mpq)을 골라 주세요", "스킨 파일 (*.mpq)|*.mpq", initDir)
}

func pickPatchFile(initDir string) (string, error) {
	return fileDialog("최신 skinbuilder.mpq 나 패치 파일(sbpatch)을 골라 주세요", "최신 mpq 또는 패치 (*.mpq;*.sbpatch)|*.mpq;*.sbpatch", initDir)
}

// findGameDir — 배틀넷 설치 정보(레지스트리)와 흔한 위치에서 D2R 폴더 찾기
func findGameDir() string {
	for _, key := range []string{
		`HKLM\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\Diablo II Resurrected`,
		`HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\Diablo II Resurrected`,
		`HKCU\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\Diablo II Resurrected`,
	} {
		out, err := hidden(exec.Command("reg", "query", key, "/v", "InstallLocation")).Output()
		if err != nil {
			continue
		}
		for _, l := range strings.Split(string(out), "\n") {
			if i := strings.Index(l, "REG_SZ"); i >= 0 {
				if v := strings.TrimSpace(l[i+6:]); isGameDir(v) {
					return v
				}
			}
		}
	}
	for _, d := range []string{"C", "D", "E", "F"} {
		for _, p := range []string{`\Program Files (x86)\Diablo II Resurrected`, `\Program Files\Diablo II Resurrected`, `\Games\Diablo II Resurrected`, `\Diablo II Resurrected`} {
			if isGameDir(d + ":" + p) {
				return d + ":" + p
			}
		}
	}
	return ""
}
