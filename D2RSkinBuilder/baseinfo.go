package main

// 기준 스킨이 지난번과 달라졌는지 — 새 버전 스킨을 받았을 때 무엇이 바뀌었는지 묶음별로 보여 줌

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

type baseRecord struct {
	Path  string            `json:"path"`
	Mod   string            `json:"mod"`
	Time  string            `json:"time"`
	Files map[string]string `json:"files"` // 소문자 경로 → 크기:압축크기
}

func baseRecordPath() string { return filepath.Join(dataDir, "스킨빌더_기준스킨.json") }

func recordOf(b *baseSkin) baseRecord {
	rec := baseRecord{Path: b.path, Mod: b.modName, Time: time.Now().Format("2006-01-02 15:04"), Files: map[string]string{}}
	for lp, name := range b.names {
		bi := b.r.find(name)
		if bi < 0 {
			continue
		}
		rec.Files[lp] = fmt.Sprintf("%d:%d", b.r.blocks[bi*4+2], b.r.blocks[bi*4+1])
	}
	return rec
}

func loadBaseRecord() *baseRecord {
	raw, err := os.ReadFile(baseRecordPath())
	if err != nil {
		return nil
	}
	var r baseRecord
	if json.Unmarshal(raw, &r) != nil {
		return nil
	}
	return &r
}

func saveBaseRecord(r baseRecord) {
	b, _ := json.Marshal(r)
	os.WriteFile(baseRecordPath(), b, 0644)
}

// 묶음 이름: 경로 앞부분을 사람이 읽는 말로
var groupKR = []struct{ prefix, name string }{
	{`data\global\ui\layouts\`, "화면 배치 (layouts)"}, {`data\local\lng\strings\`, "글자 (strings)"},
	{`data\global\excel\`, "게임 데이터 (excel)"}, {`data\hd\global\ui\items\`, "아이템 그림"},
	{`data\hd\global\ui\`, "화면 그림"}, {`data\hd\env\`, "지역·조명"}, {`data\hd\roomtiles\`, "출구 표시 (roomtiles)"},
	{`data\hd\vfx\`, "효과"}, {`data\hd\items\`, "아이템 모델"}, {`data\hd\character\`, "캐릭터·몬스터"},
	{`data\hd\global\sfx\`, "소리"}, {`data\hd\`, "기타 HD"},
}

func groupOf(lp string) string {
	for _, g := range groupKR {
		if strings.HasPrefix(lp, g.prefix) {
			return g.name
		}
	}
	return "기타"
}

func apiBaseDiff(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Cmd string `json:"cmd"`
	}
	json.NewDecoder(r.Body).Decode(&body)
	mu.Lock()
	b := base
	mu.Unlock()
	if b == nil {
		writeJSON(w, map[string]any{"has": false})
		return
	}
	cur := recordOf(b)
	prev := loadBaseRecord()
	if prev == nil || body.Cmd == "dismiss" {
		saveBaseRecord(cur)
		writeJSON(w, map[string]any{"has": false})
		return
	}
	type grp struct {
		Name                    string   `json:"name"`
		Added, Changed, Removed int      `json:"-"`
		Samples                 []string `json:"samples"`
	}
	groups := map[string]*grp{}
	add := func(lp, kind string) {
		g := groups[groupOf(lp)]
		if g == nil {
			g = &grp{Name: groupOf(lp)}
			groups[g.Name] = g
		}
		switch kind {
		case "a":
			g.Added++
		case "c":
			g.Changed++
		case "r":
			g.Removed++
		}
		if len(g.Samples) < 5 {
			g.Samples = append(g.Samples, lp[strings.LastIndex(lp, `\`)+1:])
		}
	}
	var na, nc, nr int
	for lp, v := range cur.Files {
		if pv, ok := prev.Files[lp]; !ok {
			na++
			add(lp, "a")
		} else if pv != v {
			nc++
			add(lp, "c")
		}
	}
	for lp := range prev.Files {
		if _, ok := cur.Files[lp]; !ok {
			nr++
			add(lp, "r")
		}
	}
	if na+nc+nr == 0 {
		writeJSON(w, map[string]any{"has": false})
		return
	}
	list := []map[string]any{}
	for _, g := range groups {
		list = append(list, map[string]any{"name": g.Name, "added": g.Added, "changed": g.Changed, "removed": g.Removed, "samples": g.Samples})
	}
	sort.Slice(list, func(i, j int) bool {
		ti := list[i]["added"].(int) + list[i]["changed"].(int) + list[i]["removed"].(int)
		tj := list[j]["added"].(int) + list[j]["changed"].(int) + list[j]["removed"].(int)
		return ti > tj
	})
	writeJSON(w, map[string]any{"has": true, "prevMod": prev.Mod, "prevTime": prev.Time, "prevPath": prev.Path,
		"added": na, "changed": nc, "removed": nr, "groups": list})
}
