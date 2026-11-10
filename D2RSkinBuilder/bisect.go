package main

// 튕김 원인 찾기 — 고른 기능을 반씩 나눠 넣은 시험 스킨을 차례로 만들고,
// 사용자가 "켜졌어요 / 튕겼어요"를 알려 주면 후보를 좁혀 원인 기능 하나를 찾는다.
//
// 반쪽(A)만 넣었을 때 튕기면 후보 = A, 켜지면 나머지(B)를 시험.
// B도 켜지면 둘이 같이 있을 때만 튕기는 것이라 A를 "같이 넣는 기능(partners)"으로 두고 B 안에서 계속 찾는다.

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
)

type bisectState struct {
	Active   bool     `json:"active"`
	Phase    string   `json:"phase"` // building / waiting / done / error
	Round    int      `json:"round"`
	Total    int      `json:"total"`
	Cand     []string `json:"cand"`
	Testing  []string `json:"testing"`
	Partners []string `json:"partners"`
	Result   string   `json:"result"`
	Percent  int      `json:"percent"`
	Error    string   `json:"error"`
	other    []string // 앞 반쪽이 켜졌을 때 다음에 시험할 뒤 반쪽
	triedB   bool
}

var bis bisectState

func bisNames() map[string]string {
	m := map[string]string{}
	for _, f := range pack.Features {
		m[f.ID] = f.Name
	}
	return m
}

func apiBisect(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Cmd string `json:"cmd"`
	}
	json.NewDecoder(r.Body).Decode(&body)
	mu.Lock()
	switch body.Cmd {
	case "start":
		if base == nil {
			mu.Unlock()
			writeJSON(w, map[string]any{"ok": false, "error": "먼저 기준 스킨(mpq)을 골라 주세요"})
			return
		}
		if job.Running || (bis.Active && bis.Phase == "building") {
			mu.Unlock()
			writeJSON(w, map[string]any{"ok": false, "error": "지금 만드는 중이에요"})
			return
		}
		var cand []string
		for _, f := range pack.Features {
			if settings.Choices[f.ID] != "" {
				cand = append(cand, f.ID)
			}
		}
		if len(cand) < 2 {
			mu.Unlock()
			writeJSON(w, map[string]any{"ok": false, "error": "고른 기능이 2개 이상일 때 쓸 수 있어요"})
			return
		}
		bis = bisectState{Active: true, Total: len(cand), Cand: cand}
		bisNext(cand[:len(cand)/2], cand[len(cand)/2:])
	case "ok", "crash":
		if bis.Active && bis.Phase == "waiting" {
			bisAnswer(body.Cmd == "crash")
		}
	case "stop":
		bis = bisectState{}
	}
	st := bis
	mu.Unlock()
	writeJSON(w, map[string]any{"ok": true, "bisect": st, "names": bisNames()})
}

// bisNext — testing 을 넣은 시험 스킨을 만듦 (mu 잠긴 채로 부름)
func bisNext(testing, other []string) {
	bis.Round++
	bis.Testing, bis.other, bis.Phase, bis.Percent = testing, other, "building", 0
	choices := map[string]string{}
	for _, id := range append(append([]string{}, bis.Partners...), testing...) {
		choices[id] = settings.Choices[id]
	}
	params := copyMap(settings.Params)
	b := base
	go func() {
		target, _ := modTarget()
		os.MkdirAll(filepath.Dir(target), 0755)
		_, err := buildMPQ(b, choices, params, target, func(string) {}, func(p int, _ string) {
			mu.Lock()
			bis.Percent = p
			mu.Unlock()
		})
		mu.Lock()
		defer mu.Unlock()
		if !bis.Active {
			return
		}
		if err != nil {
			bis.Phase, bis.Error = "error", "시험 스킨을 만들지 못했어요: "+err.Error()
			return
		}
		bis.Phase = "waiting"
	}()
}

func bisAnswer(crashed bool) {
	switch {
	case crashed: // 이번에 넣은 것 안에 원인
		bis.Cand, bis.triedB = bis.Testing, false
	case !bis.triedB && len(bis.other) > 0: // 앞 반쪽은 괜찮음 → 뒤 반쪽 시험
		bis.triedB = true
		bisNext(bis.other, bis.Testing)
		return
	default: // 두 반쪽 다 켜짐 → 같이 있을 때만 튕김. 앞 반쪽을 같이 넣고 뒤 반쪽 안에서 찾기
		bis.Partners = append(bis.Partners, bis.other...)
		bis.Cand, bis.triedB = bis.Testing, false
	}
	if len(bis.Cand) == 1 {
		bis.Phase, bis.Result = "done", bis.Cand[0]
		if len(bis.Partners) == 0 && !crashed {
			bis.Error = "하나씩은 모두 켜졌어요. 아래 기능과 다른 기능이 함께 있을 때 튕기는 것 같아요"
		}
		return
	}
	half := len(bis.Cand) / 2
	bisNext(bis.Cand[:half], bis.Cand[half:])
}

var _ = fmt.Sprint
