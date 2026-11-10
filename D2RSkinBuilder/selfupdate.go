package main

// 프로그램 자기 교체 정리 · 읽기 시험

import (
	"fmt"
	"os"
	"path/filepath"
	"time"
)

// selfUpdate — 같은 폴더에 "D2R스킨빌더_새버전.exe" 가 있으면 그걸로 바꾸고 다시 켬 (패치 없이 새 전체판을 받았을 때)
func selfUpdate(exe string) bool {
	os.Remove(exe + ".old")
	nw := filepath.Join(filepath.Dir(exe), "D2R스킨빌더_새버전.exe")
	if st, err := os.Stat(nw); err != nil || st.Size() < 1<<20 {
		return false
	}
	time.Sleep(300 * time.Millisecond)
	if err := os.Rename(exe, exe+".old"); err != nil {
		return false
	}
	if err := os.Rename(nw, exe); err != nil {
		os.Rename(exe+".old", exe)
		return false
	}
	startSelf(exe)
	return true
}

// readTest — mpq 를 끝까지 읽어 보는 시험 (--readtest 파일.mpq)
func readTest(path string) {
	r, err := openMPQ(path)
	if err != nil {
		fmt.Println("열기 실패:", err)
		os.Exit(1)
	}
	defer r.Close()
	bad := 0
	for _, n := range r.names {
		if _, err := r.read(n); err != nil {
			bad++
			fmt.Println("읽기 실패:", n, err)
		}
	}
	fmt.Printf("파일 %d개, 실패 %d개\n", len(r.names), bad)
	if bad > 0 {
		os.Exit(1)
	}
}

func startSelf(exe string, args ...string) {
	p, err := os.StartProcess(exe, append([]string{exe}, args...), &os.ProcAttr{Files: []*os.File{nil, nil, nil}})
	if err == nil {
		p.Release()
	}
}
