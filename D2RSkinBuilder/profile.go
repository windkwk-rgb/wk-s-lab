package main

// 내 설정 저장·불러오기 — 고른 기능과 숫자 설정을 이름 붙여 여러 벌 보관

import (
	"encoding/json"
	"net/http"
	"strings"
	"time"
)

func copyMap(m map[string]string) map[string]string {
	out := map[string]string{}
	for k, v := range m {
		out[k] = v
	}
	return out
}

func apiProfile(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Cmd  string `json:"cmd"` // list / save / load / delete
		Name string `json:"name"`
	}
	json.NewDecoder(r.Body).Decode(&body)
	name := strings.TrimSpace(body.Name)
	mu.Lock()
	defer mu.Unlock()
	if settings.Profiles == nil {
		settings.Profiles = map[string]Profile{}
	}
	switch body.Cmd {
	case "save":
		if name == "" {
			writeJSON(w, map[string]any{"ok": false, "error": "이름을 넣어 주세요"})
			return
		}
		settings.Profiles[name] = Profile{copyMap(settings.Choices), copyMap(settings.Params), time.Now().Format("2006-01-02 15:04")}
		saveSettings()
	case "load":
		p, ok := settings.Profiles[name]
		if !ok {
			writeJSON(w, map[string]any{"ok": false, "error": "그 이름의 설정이 없어요"})
			return
		}
		settings.Choices, settings.Params = copyMap(p.Choices), copyMap(p.Params)
		fillParams(&settings)
		saveSettings()
	case "delete":
		delete(settings.Profiles, name)
		saveSettings()
	}
	list := []map[string]string{}
	for n, p := range settings.Profiles {
		list = append(list, map[string]string{"name": n, "saved": p.Saved})
	}
	writeJSON(w, map[string]any{"ok": true, "profiles": list, "choices": settings.Choices, "params": settings.Params})
}
