package main

// D2R 스킨 빌더 — 기준 스킨(mpq)에 원하는 기능만 얹어 새 스킨을 만드는 프로그램.
// 작은 웹 서버를 띄우고 브라우저(앱 창)로 화면을 보여 준다.

import (
	"embed"
	"encoding/json"
	"fmt"
	"io"
	"io/fs"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

//go:embed ui
var uiFS embed.FS

const (
	port    = 47651
	Version = "3.2"
	ModName = "skinbuilder"
)

type Settings struct {
	V        int                `json:"v"`
	GameDir  string             `json:"gameDir"`
	BasePath string             `json:"basePath"`
	Choices  map[string]string  `json:"choices"`
	Params   map[string]string  `json:"params"`
	Profiles map[string]Profile `json:"profiles,omitempty"`
}

// Profile — 이름 붙여 저장해 둔 기능·설정 묶음
type Profile struct {
	Choices map[string]string `json:"choices"`
	Params  map[string]string `json:"params"`
	Saved   string            `json:"saved"`
}

var (
	exeDir, dataDir, settingsPath string
	mu                            sync.Mutex
	settings                      = Settings{V: 4, Choices: map[string]string{}, Params: map[string]string{}}
	base                          *baseSkin
	baseErr                       string
	cmp                           = struct {
		Running bool                            `json:"running"`
		Done    int                             `json:"done"`
		Total   int                             `json:"total"`
		Status  map[string]map[string]optStatus `json:"-"`
		gen     int
	}{}
	job = struct {
		Running bool     `json:"running"`
		Done    bool     `json:"done"`
		Percent int      `json:"percent"`
		Step    string   `json:"step"`
		Error   string   `json:"error"`
		Result  string   `json:"result"`
		InGame  bool     `json:"inGame"`
		Log     []string `json:"log"`
	}{}
	lastPing = time.Now()
	sawPing  = false
	byeAt    time.Time
)

func main() {
	exe, _ := os.Executable()
	exeDir = filepath.Dir(exe)
	if runUninstall(exe) || runSetup(exe) { // setup 이름의 exe = 설치 프로그램
		return
	}
	if len(os.Args) == 1 && selfUpdate(exe) {
		return
	}
	if dev := os.Getenv("SKINBUILDER_DIR"); dev != "" {
		exeDir = dev
	}
	dataDir = exeDir
	if f, err := os.CreateTemp(exeDir, ".write-test-*"); err == nil {
		f.Close()
		os.Remove(f.Name())
	} else if d, err := os.UserConfigDir(); err == nil {
		dataDir = filepath.Join(d, "D2R스킨빌더")
		os.MkdirAll(dataDir, 0755)
	}
	settingsPath = filepath.Join(dataDir, "스킨빌더_설정.json")
	if len(os.Args) == 3 && os.Args[1] == "--readtest" {
		readTest(os.Args[2])
		return
	}
	if len(os.Args) == 3 && os.Args[1] == "--peek-update" { // 개발용: mpq 안 업그레이드 정보 확인
		u, err := peekMPQUpdate(os.Args[2])
		if err != nil {
			fmt.Println("오류:", err)
			os.Exit(1)
		}
		fmt.Printf("업그레이드 %s (기준 %s) %d바이트, 설정 %d개, 지금 프로그램 %s\n", u.manifest.Version, u.manifest.Base, len(u.patch), len(u.choices), appVersion())
		return
	}
	cli := len(os.Args) >= 3 && strings.HasPrefix(os.Args[1], "--") && os.Args[1] != "--after-patch"
	if cli && os.Args[1] == "--make-setup" { // 기능 묶음 없이도 되는 일
		runCLI(os.Args[1:])
		return
	}
	if !cli {
		if f, err := os.Create(filepath.Join(dataDir, "스킨빌더_기록.txt")); err == nil {
			log.SetOutput(f)
		}
	}
	log.SetFlags(log.Ltime)
	if len(os.Args) == 2 && os.Args[1] == "--after-patch" { // 패치 후 다시 켤 때: 옛 프로그램이 닫힐 때까지 기다림
		for i := 0; i < 50; i++ {
			c, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), 200*time.Millisecond)
			if err != nil {
				break
			}
			c.Close()
			time.Sleep(200 * time.Millisecond)
		}
	}
	if !cli {
		if c, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), 300*time.Millisecond); err == nil {
			c.Close()
			openWindow(fmt.Sprintf("http://127.0.0.1:%d/", port))
			return
		}
	}
	if len(os.Args) == 1 && applyPendingPatch(exe) {
		return
	}
	if err := loadPatchOverlay(); err != nil {
		fatal("패치 데이터를 읽지 못했습니다: " + err.Error())
	}
	if err := loadPack(packJSON); err != nil {
		fatal("데이터를 읽지 못했습니다: " + err.Error())
	}
	if cli && runCLI(os.Args[1:]) {
		return
	}

	loadSettings()
	if settings.GameDir == "" || !isGameDir(settings.GameDir) {
		if g := findGameDir(); g != "" {
			settings.GameDir = g
			saveSettings()
		}
	}
	if len(os.Args) <= 2 && autoUpgradeFromMPQ(exe) { // 최신 mpq 에 든 업그레이드를 넣고 다시 켬
		return
	}
	applyDefaultPreset()
	if settings.BasePath != "" {
		go loadBase(settings.BasePath, false)
	}

	ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		fatal("프로그램을 시작하지 못했습니다: " + err.Error())
	}
	http.Handle("/", http.FileServer(http.FS(mergedFS{})))
	http.HandleFunc("/api/pack", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Write(packJSON)
	})
	http.HandleFunc("/api/state", apiState)
	http.HandleFunc("/api/status", apiStatus)
	http.HandleFunc("/api/settings", apiSettings)
	http.HandleFunc("/api/build", apiBuild)
	http.HandleFunc("/api/pickbase", apiPickBase)
	http.HandleFunc("/api/pickgame", apiPickGame)
	http.HandleFunc("/api/open", func(w http.ResponseWriter, r *http.Request) {
		p, _ := modTarget()
		os.MkdirAll(filepath.Dir(p), 0755)
		openFolder(filepath.Dir(p))
		writeJSON(w, map[string]bool{"ok": true})
	})
	http.HandleFunc("/api/uninstall", apiUninstall)
	http.HandleFunc("/api/patch", apiPatch)
	http.HandleFunc("/api/bisect", apiBisect)
	http.HandleFunc("/api/basediff", apiBaseDiff)
	http.HandleFunc("/api/profile", apiProfile)
	http.HandleFunc("/api/sound", func(w http.ResponseWriter, r *http.Request) {
		i, err := strconv.Atoi(r.URL.Query().Get("i"))
		if err != nil || i < 0 || i >= len(pack.Blobs) {
			http.Error(w, "없음", 404)
			return
		}
		data, err := blobRaw(i)
		if err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
		ct := "audio/flac"
		if len(data) > 4 && (string(data[:4]) == "OTTO" || string(data[:4]) == "\x00\x01\x00\x00" || string(data[:4]) == "true") {
			ct = "font/ttf"
		}
		w.Header().Set("Content-Type", ct)
		w.Header().Set("Cache-Control", "max-age=3600")
		w.Write(data)
	})
	http.HandleFunc("/api/ping", func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		lastPing, sawPing, byeAt = time.Now(), true, time.Time{}
		mu.Unlock()
		w.Write([]byte("ok"))
	})
	http.HandleFunc("/api/bye", func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		byeAt = time.Now()
		mu.Unlock()
	})
	go watchdog()
	go openWindow(fmt.Sprintf("http://127.0.0.1:%d/", port))
	log.Println("시작 v" + appVersion())
	http.Serve(ln, nil)
}

func fillParams(s *Settings) {
	if s.Choices == nil {
		s.Choices = map[string]string{}
	}
	if s.Params == nil {
		s.Params = map[string]string{}
	}
	for _, f := range pack.Features {
		for _, p := range f.Params {
			if _, ok := s.Params[p.ID]; !ok {
				s.Params[p.ID] = p.Default
			}
		}
	}
}

// ---------------------------------------------------------------- 기준 스킨

func loadBase(path string, userPicked bool) error {
	b, err := openBase(path)
	mu.Lock()
	defer mu.Unlock()
	if err != nil {
		baseErr = err.Error()
		log.Println("기준 스킨 열기 실패:", path, err)
		return err
	}
	if b.ours {
		b.close()
		baseErr = "이 파일은 스킨빌더가 만든 결과 파일이에요. 원래 스킨 파일(예: mods\\yupgoolg\\yupgoolg.mpq)을 골라 주세요"
		return fmt.Errorf("%s", baseErr)
	}
	base.close()
	base, baseErr = b, ""
	if userPicked || settings.BasePath != path {
		settings.BasePath = path
		saveSettings()
	}
	log.Printf("기준 스킨: %s (%s, 파일 %d개)", path, b.modName, len(b.names))
	go compareAll()
	return nil
}

// compareAll — 기능마다 기준 스킨과 비교 (뒤에서)
func compareAll() {
	mu.Lock()
	cmp.gen++
	gen := cmp.gen
	b := base
	params := map[string]string{}
	for k, v := range settings.Params {
		params[k] = v
	}
	total := 0
	for _, f := range pack.Features {
		total += len(f.Options)
	}
	cmp.Running, cmp.Done, cmp.Total, cmp.Status = true, 0, total, map[string]map[string]optStatus{}
	mu.Unlock()
	s := Settings{Params: params}
	fillParams(&s)
	for i := range pack.Features {
		f := &pack.Features[i]
		m := map[string]optStatus{}
		for j := range f.Options {
			m[f.Options[j].Key] = statusOf(b, f, &f.Options[j], s.Params)
		}
		mu.Lock()
		if cmp.gen != gen {
			mu.Unlock()
			return
		}
		cmp.Status[f.ID] = m
		cmp.Done += len(f.Options)
		mu.Unlock()
	}
	mu.Lock()
	if cmp.gen == gen {
		cmp.Running = false
	}
	mu.Unlock()
}

// 게임 폴더 mods 아래의 스킨 mpq 목록
func gameMods() []map[string]any {
	var out []map[string]any
	var dirs []os.DirEntry
	if settings.GameDir != "" {
		dirs, _ = os.ReadDir(filepath.Join(settings.GameDir, "mods"))
	}
	for _, d := range dirs {
		if !d.IsDir() || strings.EqualFold(d.Name(), ModName) {
			continue
		}
		p := filepath.Join(settings.GameDir, "mods", d.Name(), d.Name()+".mpq")
		if st, err := os.Stat(p); err == nil && !st.IsDir() {
			out = append(out, map[string]any{"name": d.Name(), "path": p, "size": st.Size(), "time": st.ModTime().Format("2006-01-02 15:04")})
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i]["name"].(string) < out[j]["name"].(string) })
	// 설치 파일에 같이 들어 있던 기준 스킨 (스킨빌더 폴더\기준스킨)
	files, _ := filepath.Glob(filepath.Join(bundledDir(exeDir), "*.mpq"))
	sort.Strings(files)
	for _, p := range files {
		if st, err := os.Stat(p); err == nil {
			n := strings.TrimSuffix(filepath.Base(p), filepath.Ext(p))
			out = append(out, map[string]any{"name": n + " (함께 설치)", "path": p, "size": st.Size(), "time": st.ModTime().Format("2006-01-02 15:04"), "bundled": true})
		}
	}
	return out
}

// ---------------------------------------------------------------- API

func apiState(w http.ResponseWriter, r *http.Request) {
	mu.Lock()
	defer mu.Unlock()
	target, inGame := modTarget()
	_, installed := os.Stat(target)
	bi := map[string]any(nil)
	if base != nil {
		st, _ := os.Stat(base.path)
		size := int64(0)
		tm := ""
		if st != nil {
			size, tm = st.Size(), st.ModTime().Format("2006-01-02 15:04")
		}
		bi = map[string]any{"path": base.path, "mod": base.modName, "files": len(base.names), "size": size, "time": tm}
	}
	writeJSON(w, map[string]any{
		"version": appVersion(), "gameDir": settings.GameDir, "gameFound": isGameDir(settings.GameDir),
		"base": bi, "baseErr": baseErr, "basePath": settings.BasePath, "mods": gameMods(),
		"settings": settings, "job": job, "cmp": cmp, "target": target, "inGame": inGame,
		"installed": installed == nil, "modName": ModName,
	})
}

func apiStatus(w http.ResponseWriter, r *http.Request) {
	mu.Lock()
	defer mu.Unlock()
	writeJSON(w, map[string]any{"cmp": cmp, "status": cmp.Status})
}

func apiSettings(w http.ResponseWriter, r *http.Request) {
	var s Settings
	if err := json.NewDecoder(r.Body).Decode(&s); err != nil {
		writeJSON(w, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	mu.Lock()
	paramsChanged := fmt.Sprint(s.Params) != fmt.Sprint(settings.Params)
	if s.Choices != nil {
		settings.Choices = s.Choices
	}
	if s.Params != nil {
		settings.Params = s.Params
	}
	saveSettings()
	mu.Unlock()
	if paramsChanged && base != nil {
		go compareAll()
	}
	writeJSON(w, map[string]bool{"ok": true})
}

func apiPickBase(w http.ResponseWriter, r *http.Request) {
	var body struct{ Path string }
	json.NewDecoder(r.Body).Decode(&body)
	p := body.Path
	if p == "" {
		init := ""
		if settings.GameDir != "" {
			init = filepath.Join(settings.GameDir, "mods")
		}
		var err error
		p, err = pickFile(init)
		if err != nil || p == "" {
			writeJSON(w, map[string]any{"ok": false})
			return
		}
	}
	if err := loadBase(p, true); err != nil {
		writeJSON(w, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	writeJSON(w, map[string]any{"ok": true})
}

func apiPickGame(w http.ResponseWriter, r *http.Request) {
	dir, err := pickFolder()
	if err != nil || dir == "" {
		writeJSON(w, map[string]any{"ok": false})
		return
	}
	if !isGameDir(dir) {
		writeJSON(w, map[string]any{"ok": false, "error": "그 폴더에 D2R.exe가 없어요. 디아블로 II 레저렉션이 설치된 폴더를 골라 주세요"})
		return
	}
	mu.Lock()
	settings.GameDir = dir
	saveSettings()
	mu.Unlock()
	writeJSON(w, map[string]any{"ok": true})
}

func apiUninstall(w http.ResponseWriter, r *http.Request) {
	p, _ := modTarget()
	err := os.RemoveAll(filepath.Dir(p))
	if err != nil {
		writeJSON(w, map[string]any{"ok": false, "error": "지우지 못했어요 (게임이 켜져 있으면 끄고 다시 해 주세요): " + err.Error()})
		return
	}
	writeJSON(w, map[string]any{"ok": true})
}

func jobLog(s string) {
	mu.Lock()
	job.Log = append(job.Log, s)
	mu.Unlock()
	log.Println(s)
}

func apiBuild(w http.ResponseWriter, r *http.Request) {
	mu.Lock()
	if job.Running {
		mu.Unlock()
		writeJSON(w, map[string]any{"ok": false, "error": "이미 만드는 중이에요"})
		return
	}
	if base == nil {
		mu.Unlock()
		writeJSON(w, map[string]any{"ok": false, "error": "먼저 기준 스킨(mpq)을 골라 주세요"})
		return
	}
	job.Running, job.Done, job.Percent, job.Step, job.Error, job.Result, job.Log = true, false, 0, "준비 중", "", "", nil
	s := Settings{Choices: map[string]string{}, Params: map[string]string{}}
	for k, v := range settings.Choices {
		s.Choices[k] = v
	}
	for k, v := range settings.Params {
		s.Params[k] = v
	}
	b := base
	mu.Unlock()
	fillParams(&s)
	go func() {
		target, inGame := modTarget()
		os.MkdirAll(filepath.Dir(target), 0755)
		jobLog(fmt.Sprintf("기준 스킨: %s (%s)", b.path, b.modName))
		_, err := buildMPQ(b, s.Choices, s.Params, target, jobLog, func(p int, step string) {
			mu.Lock()
			job.Percent, job.Step = p, step
			mu.Unlock()
		})
		mu.Lock()
		job.Running, job.Done, job.Percent = false, true, 100
		if err != nil {
			job.Error = err.Error()
			log.Println("실패:", err)
		} else {
			job.Result, job.InGame = target, inGame
		}
		mu.Unlock()
	}()
	writeJSON(w, map[string]any{"ok": true})
}

// ---------------------------------------------------------------- 도우미

func modTarget() (string, bool) {
	if isGameDir(settings.GameDir) {
		return filepath.Join(settings.GameDir, "mods", ModName, ModName+".mpq"), true
	}
	return filepath.Join(dataDir, "mods", ModName, ModName+".mpq"), false
}

func replaceFile(tmp, dst string) error {
	if _, err := os.Stat(dst); err == nil {
		if err := os.Remove(dst); err != nil {
			os.Remove(tmp)
			return fmt.Errorf("이전 스킨 파일을 바꿀 수 없어요. 게임이 켜져 있으면 끄고 다시 만들어 주세요 (%v)", err)
		}
	}
	if err := os.Rename(tmp, dst); err != nil {
		// 다른 드라이브면 복사
		in, e1 := os.Open(tmp)
		if e1 != nil {
			return err
		}
		defer in.Close()
		out, e2 := os.Create(dst)
		if e2 != nil {
			return e2
		}
		if _, e3 := io.Copy(out, in); e3 != nil {
			out.Close()
			return e3
		}
		out.Close()
		in.Close()
		os.Remove(tmp)
	}
	return nil
}

func watchdog() {
	for range time.Tick(time.Second) {
		mu.Lock()
		running := job.Running
		bye := !byeAt.IsZero() && time.Since(byeAt) > 4*time.Second
		silent := sawPing && time.Since(lastPing) > 10*time.Minute
		never := !sawPing && time.Since(lastPing) > 3*time.Minute
		mu.Unlock()
		if !running && (bye || silent || never) {
			log.Println("창이 닫혀 종료")
			os.Exit(0)
		}
	}
}

type mergedFS struct{}

func (mergedFS) Open(name string) (fs.File, error) {
	if f, err := patchFileOpen(name); err == nil { // 패치에 든 화면 파일이 먼저
		return f, nil
	}
	if name == "." {
		return uiFS.Open("ui")
	}
	if strings.HasPrefix(name, "img/") {
		return packFS.Open(packDir + "/" + name)
	}
	return uiFS.Open("ui/" + name)
}

func writeJSON(w http.ResponseWriter, v interface{}) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	json.NewEncoder(w).Encode(v)
}

func loadSettings() {
	b, err := os.ReadFile(settingsPath)
	if err != nil {
		return
	}
	var s Settings
	if json.Unmarshal(b, &s) != nil {
		return
	}
	settings.GameDir = s.GameDir
	if s.V >= 4 { // v1 설정(스킨 섞기)은 새 구조와 달라서 게임 폴더만 가져옴
		settings = s
	}
	fillParams(&settings)
	settings.V = 4
}

func saveSettings() {
	b, _ := json.MarshalIndent(settings, "", " ")
	if err := os.WriteFile(settingsPath, b, 0644); err != nil {
		log.Println("설정 저장 실패:", err)
	}
}

func isGameDir(dir string) bool {
	if dir == "" {
		return false
	}
	for _, n := range []string{"D2R.exe", "d2r.exe"} {
		if st, err := os.Stat(filepath.Join(dir, n)); err == nil && !st.IsDir() {
			return true
		}
	}
	return false
}

func fatal(msg string) {
	log.Println(msg)
	showError(msg)
	os.Exit(1)
}
