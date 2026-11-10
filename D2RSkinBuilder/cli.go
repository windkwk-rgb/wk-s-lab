package main

// 개발·시험용 명령줄
//   --build  기준.mpq 설정.json|preset 결과.mpq   스킨 만들기 (preset = 추천 설정)
//   --status 기준.mpq 설정.json|preset            기능마다 기준 스킨과 비교

import (
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"time"
)

func cliSettings(arg string) Settings {
	var s Settings
	if arg == "preset" && len(pack.Presets) > 0 {
		s.Choices, s.Params = copyMap(pack.Presets[0].Choices), copyMap(pack.Presets[0].Params)
	} else {
		raw, err := os.ReadFile(arg)
		if err != nil {
			fmt.Println("설정 파일을 읽지 못했어요:", err)
			os.Exit(1)
		}
		json.Unmarshal(raw, &s)
	}
	fillParams(&s)
	return s
}

func runCLI(args []string) bool {
	switch {
	case len(args) == 4 && args[0] == "--build":
		b, err := openBase(args[1])
		if err != nil {
			fmt.Println("오류:", err)
			os.Exit(1)
		}
		s := cliSettings(args[2])
		if _, err = buildMPQ(b, s.Choices, s.Params, args[3], func(m string) { fmt.Println(m) }, func(int, string) {}); err != nil {
			fmt.Println("오류:", err)
			os.Exit(1)
		}
		return true
	case len(args) == 3 && args[0] == "--status":
		b, err := openBase(args[1])
		if err != nil {
			fmt.Println("오류:", err)
			os.Exit(1)
		}
		s := cliSettings(args[2])
		t0 := time.Now()
		for i := range pack.Features {
			f := &pack.Features[i]
			for j := range f.Options {
				st := statusOf(b, f, &f.Options[j], s.Params)
				fmt.Printf("%s\t%s\t%s\t+%d =%d ~%d -%d\t%s\n", f.ID, f.Options[j].Label, st.State, st.Add, st.Same, st.Replace, st.Remove, strings.Join(st.Notes, " / "))
			}
		}
		fmt.Println("걸린 시간", time.Since(t0))
		return true
	case len(args) == 3 && args[0] == "--diff": // 두 mpq 의 다른 파일 목록 (개발용)
		diffMPQ(args[1], args[2])
		return true
	}
	return false
}

func diffMPQ(a, b string) {
	ra, err := openMPQ(a)
	if err != nil {
		fmt.Println(err)
		return
	}
	rb, err := openMPQ(b)
	if err != nil {
		fmt.Println(err)
		return
	}
	for k, n := range ra.names {
		if _, ok := rb.names[k]; !ok {
			fmt.Println("A만\t" + n)
			continue
		}
		x, e1 := ra.read(n)
		y, e2 := rb.read(rb.names[k])
		if e1 != nil || e2 != nil {
			fmt.Println("읽기실패\t"+n, e1, e2)
			continue
		}
		if string(x) != string(y) {
			fmt.Printf("다름\t%s\t%d\t%d\n", n, len(x), len(y))
		}
	}
	for k, n := range rb.names {
		if _, ok := ra.names[k]; !ok {
			fmt.Println("B만\t" + n)
		}
	}
}
