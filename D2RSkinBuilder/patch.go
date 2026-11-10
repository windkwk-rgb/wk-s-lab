package main

// 패치 파일(.sbpatch) — 큰 프로그램을 다시 받지 않고 기능·화면·프로그램을 새 버전으로
//
// 패치 파일은 zip 이고 안에:
//   manifest.json   {"version":"3.1","base":"3.1","notes":"..."}
//   features.json   새 기능 목록 (데이터 위치: 기본 데이터 길이 이상이면 extra.bin 안)
//   extra.bin       새로 더해진 기능 데이터
//   ui/..., img/... 바뀐 화면 파일 (있으면)
//   program.exe     새 프로그램 (가벼운 판, 있으면)

import (
	"archive/zip"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

type patchManifest struct {
	Version string `json:"version"`
	Base    string `json:"base"`
	Notes   string `json:"notes"`
}

var (
	extraBlobs []byte
	patchInfo  *patchManifest
)

func patchDir() string     { return filepath.Join(dataDir, "스킨빌더_패치") }
func baseDataPath() string { return filepath.Join(dataDir, "스킨빌더_데이터.bin") }

func appVersion() string {
	if patchInfo != nil && verCmp(patchInfo.Version, Version) > 0 {
		return patchInfo.Version
	}
	return Version
}

// verCmp — "3.10" > "3.9"
func verCmp(a, b string) int {
	pa, pb := strings.Split(a, "."), strings.Split(b, ".")
	for i := 0; i < len(pa) || i < len(pb); i++ {
		var x, y int
		if i < len(pa) {
			x, _ = strconv.Atoi(pa[i])
		}
		if i < len(pb) {
			y, _ = strconv.Atoi(pb[i])
		}
		if x != y {
			if x < y {
				return -1
			}
			return 1
		}
	}
	return 0
}

func patchLog(f string, a ...interface{}) {
	fh, err := os.OpenFile(filepath.Join(dataDir, "업데이트_기록.txt"), os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
	if err == nil {
		fmt.Fprintf(fh, time.Now().Format("01-02 15:04:05 ")+f+"\r\n", a...)
		fh.Close()
	}
}

func readManifest(zr *zip.Reader) (*patchManifest, error) {
	for _, f := range zr.File {
		if f.Name == "manifest.json" {
			rc, err := f.Open()
			if err != nil {
				return nil, err
			}
			defer rc.Close()
			var m patchManifest
			if err := json.NewDecoder(rc).Decode(&m); err != nil {
				return nil, err
			}
			if m.Version == "" {
				return nil, errors.New("버전이 없는 패치")
			}
			return &m, nil
		}
	}
	return nil, errors.New("manifest.json 이 없는 패치")
}

func installedPatch() *patchManifest {
	b, err := os.ReadFile(filepath.Join(patchDir(), "manifest.json"))
	if err != nil {
		return nil
	}
	var m patchManifest
	if json.Unmarshal(b, &m) != nil {
		return nil
	}
	return &m
}

// applyPatchFile — 패치 파일을 풀어 넣음. 새 프로그램이 들어 있으면 바꾸고 다시 켜야 함(restart=true)
func applyPatchFile(path, exe string) (restart bool, ver string, err error) {
	zf, err := zip.OpenReader(path)
	if err != nil {
		return false, "", fmt.Errorf("패치 파일을 열지 못했어요: %v", err)
	}
	defer zf.Close()
	m, err := readManifest(&zf.Reader)
	if err != nil {
		return false, "", err
	}
	if verCmp(m.Version, appVersion()) <= 0 {
		return false, m.Version, fmt.Errorf("이미 %s 버전이에요 (패치 %s)", appVersion(), m.Version)
	}
	if m.Base != "" && verCmp(baseDataVersion(), m.Base) != 0 {
		return false, m.Version, fmt.Errorf("이 패치는 %s 전체판을 기준으로 만든 거예요 (지금 기준 데이터 %s). 맞는 전체 프로그램을 먼저 받아 주세요", m.Base, baseDataVersion())
	}
	var hasExe bool
	for _, f := range zf.File {
		if f.Name == "program.exe" {
			hasExe = true
		}
	}
	// 가벼운 프로그램으로 바꾸기 전에, 지금 프로그램 안의 기능 데이터를 파일로 꺼내 둠 (한 번만)
	if hasExe && len(blobsBin) > 0 {
		if st, e := os.Stat(baseDataPath()); e != nil || st.Size() != int64(len(blobsBin)) {
			if e := os.WriteFile(baseDataPath()+".tmp", blobsBin, 0644); e != nil {
				return false, "", fmt.Errorf("기능 데이터를 꺼내 두지 못했어요: %v", e)
			}
			os.Rename(baseDataPath()+".tmp", baseDataPath())
			os.WriteFile(baseDataPath()+".ver", []byte(Version), 0644)
		}
	}
	tmp := patchDir() + ".new"
	os.RemoveAll(tmp)
	for _, f := range zf.File {
		if f.FileInfo().IsDir() || strings.Contains(f.Name, "..") {
			continue
		}
		dst := filepath.Join(tmp, filepath.FromSlash(f.Name))
		if f.Name == "program.exe" {
			dst = exe + ".new"
		}
		os.MkdirAll(filepath.Dir(dst), 0755)
		rc, e := f.Open()
		if e != nil {
			return false, "", e
		}
		out, e := os.OpenFile(dst, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0755)
		if e != nil {
			rc.Close()
			return false, "", e
		}
		_, e = io.Copy(out, rc)
		rc.Close()
		out.Close()
		if e != nil {
			return false, "", e
		}
	}
	os.RemoveAll(patchDir())
	if e := os.Rename(tmp, patchDir()); e != nil {
		return false, "", e
	}
	patchLog("패치 %s 를 넣었어요 (%s)", m.Version, filepath.Base(path))
	if hasExe {
		os.Remove(exe + ".old")
		if e := os.Rename(exe, exe+".old"); e != nil {
			return false, m.Version, fmt.Errorf("프로그램을 바꾸지 못했어요: %v", e)
		}
		if e := os.Rename(exe+".new", exe); e != nil {
			os.Rename(exe+".old", exe)
			return false, m.Version, fmt.Errorf("프로그램을 바꾸지 못했어요: %v", e)
		}
		return true, m.Version, nil
	}
	return false, m.Version, nil
}

// applyPendingPatch — 프로그램 옆(또는 설정 폴더)에 둔 .sbpatch 를 켤 때 자동으로 넣음. 다시 켜야 하면 true
func applyPendingPatch(exe string) bool {
	os.Remove(exe + ".old")
	var files []string
	for _, d := range []string{exeDir, dataDir} {
		m, _ := filepath.Glob(filepath.Join(d, "*.sbpatch"))
		files = append(files, m...)
	}
	if len(files) == 0 {
		return false
	}
	// 버전이 가장 높은 것 하나만 (패치는 앞 패치 내용을 모두 담고 있음)
	type pv struct {
		path, ver string
	}
	var list []pv
	for _, f := range files {
		zf, err := zip.OpenReader(f)
		if err != nil {
			continue
		}
		if m, err := readManifest(&zf.Reader); err == nil {
			list = append(list, pv{f, m.Version})
		}
		zf.Close()
	}
	if len(list) == 0 {
		return false
	}
	sort.Slice(list, func(i, j int) bool { return verCmp(list[i].ver, list[j].ver) > 0 })
	_ = loadPatchOverlay() // 지금 들어 있는 패치 버전을 알아야 비교함
	restart, ver, err := applyPatchFile(list[0].path, exe)
	for _, x := range list {
		os.Rename(x.path, x.path+".적용됨")
	}
	if err != nil {
		patchLog("패치 %s 를 넣지 못함: %v", ver, err)
		return false
	}
	if restart {
		exec.Command(exe).Start()
		return true
	}
	patchInfo = nil
	extraBlobs = nil
	return false
}

// loadPatchOverlay — 넣어 둔 패치(기능 목록·데이터)를 씀
func loadPatchOverlay() error {
	if slimBuild && len(blobsBin) == 0 {
		b, err := os.ReadFile(baseDataPath())
		if err != nil {
			return fmt.Errorf("기능 데이터 파일(스킨빌더_데이터.bin)이 없어요. 전체 프로그램을 다시 받아 주세요")
		}
		blobsBin = b
	}
	if !slimBuild { // 전체판: 옛 기준 데이터·맞지 않는 패치 정리
		if b, err := os.ReadFile(baseDataPath() + ".ver"); err == nil && strings.TrimSpace(string(b)) != Version {
			os.Remove(baseDataPath())
			os.Remove(baseDataPath() + ".ver")
			patchLog("옛 기능 데이터 파일(%s판)을 지웠어요", strings.TrimSpace(string(b)))
		}
		if m := installedPatch(); m != nil && (verCmp(m.Version, Version) <= 0 || (m.Base != "" && m.Base != Version)) {
			os.RemoveAll(patchDir())
			patchLog("이 프로그램보다 옛 패치(%s)를 정리했어요", m.Version)
		}
	}
	m := installedPatch()
	if m == nil || verCmp(m.Version, Version) < 0 {
		return nil // 패치가 없거나 프로그램이 더 새것
	}
	if m.Base != "" && verCmp(baseDataVersion(), m.Base) != 0 {
		return nil // 다른 기준 데이터로 만든 패치
	}
	fj, err := os.ReadFile(filepath.Join(patchDir(), "features.json"))
	if err != nil {
		patchInfo = m
		return nil // 화면만 바꾸는 패치
	}
	eb, _ := os.ReadFile(filepath.Join(patchDir(), "extra.bin"))
	packJSON, extraBlobs, patchInfo = fj, eb, m
	return nil
}

// patchFileOpen — 패치에 든 화면 파일이 있으면 그것을 먼저
func patchFileOpen(name string) (fs.File, error) {
	if patchInfo == nil {
		return nil, fs.ErrNotExist
	}
	sub := "ui/" + name
	if strings.HasPrefix(name, "img/") {
		sub = name
	}
	return os.DirFS(patchDir()).Open(sub)
}

func apiPatch(w http.ResponseWriter, r *http.Request) {
	p, err := pickPatchFile(exeDir)
	if err != nil || p == "" {
		writeJSON(w, map[string]any{"ok": false})
		return
	}
	exe, _ := os.Executable()
	mu.Lock()
	var restart bool
	var ver string
	if strings.HasSuffix(strings.ToLower(p), ".mpq") { // 최신 mpq 안에 든 업그레이드
		u, e := peekMPQUpdate(p)
		if e == nil && verCmp(u.manifest.Version, appVersion()) <= 0 {
			e = fmt.Errorf("이미 %s 버전이에요 (이 mpq 는 %s)", appVersion(), u.manifest.Version)
		}
		if e == nil {
			ver = u.manifest.Version
			restart, e = applyMPQUpdate(u, exe)
			os.Remove(filepath.Join(dataDir, ".mpq_업그레이드_실패.txt"))
		}
		err = e
	} else {
		restart, ver, err = applyPatchFile(p, exe)
	}
	mu.Unlock()
	if err != nil {
		writeJSON(w, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	writeJSON(w, map[string]any{"ok": true, "version": ver, "restart": true})
	_ = restart
	go func() { // 새 기능 목록·프로그램으로 다시 켬
		time.Sleep(800 * time.Millisecond)
		exec.Command(exe, "--after-patch").Start()
		os.Exit(0)
	}()
}

// baseDataVersion — 지금 쓰는 기본 기능 데이터가 어느 전체판 것인지
func baseDataVersion() string {
	if !slimBuild {
		return Version
	}
	b, err := os.ReadFile(baseDataPath() + ".ver")
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(b))
}
