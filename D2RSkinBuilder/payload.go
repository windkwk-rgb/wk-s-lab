package main

// 설치 파일에 같이 넣는 파일(기준 스킨 mpq 등) — 프로그램 exe 뒤에 붙여 둠
//
//   [프로그램 exe][파일1][파일2]...[목록 JSON][목록 길이 8바이트][표시 "SBSETUP1"]
//
// 설치할 때 앞부분(프로그램)만 D2R스킨빌더.exe 로 복사하고, 붙은 파일은 설치 폴더의 기준스킨\ 에 풀어요.
// 만들기: D2R스킨빌더 --make-setup 프로그램.exe 결과_setup.exe 스킨1.mpq 스킨2.mpq ...

import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
)

const payloadMagic = "SBSETUP1"

type payloadFile struct {
	Name   string `json:"name"`
	Offset int64  `json:"offset"`
	Size   int64  `json:"size"`
}

type payloadIndex struct {
	ExeSize int64         `json:"exeSize"`
	Files   []payloadFile `json:"files"`
}

func bundledDir(dir string) string { return filepath.Join(dir, "기준스킨") }

// readPayload — 설치 파일 끝에 붙은 목록 (없으면 nil)
func readPayload(path string) *payloadIndex {
	f, err := os.Open(path)
	if err != nil {
		return nil
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil || st.Size() < 16 {
		return nil
	}
	tail := make([]byte, 16)
	if _, err := f.ReadAt(tail, st.Size()-16); err != nil || string(tail[8:]) != payloadMagic {
		return nil
	}
	n := int64(binary.LittleEndian.Uint64(tail[:8]))
	if n <= 0 || n > st.Size()-16 {
		return nil
	}
	raw := make([]byte, n)
	if _, err := f.ReadAt(raw, st.Size()-16-n); err != nil {
		return nil
	}
	var idx payloadIndex
	if json.Unmarshal(raw, &idx) != nil || idx.ExeSize <= 0 {
		return nil
	}
	return &idx
}

// copyRange — src 의 [off, off+size) 를 dst 로
func copyRange(src *os.File, off, size int64, dst string) error {
	tmp := dst + ".설치중"
	out, err := os.Create(tmp)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, io.NewSectionReader(src, off, size)); err != nil {
		out.Close()
		os.Remove(tmp)
		return err
	}
	out.Close()
	os.Remove(dst)
	return os.Rename(tmp, dst)
}

// extractPayload — 붙은 파일을 dir\기준스킨\ 에 풀기 (같은 크기 파일이 이미 있으면 건너뜀)
func extractPayload(setup string, idx *payloadIndex, dir string) (int, error) {
	f, err := os.Open(setup)
	if err != nil {
		return 0, err
	}
	defer f.Close()
	bd := bundledDir(dir)
	if err := os.MkdirAll(bd, 0755); err != nil {
		return 0, err
	}
	n := 0
	for _, p := range idx.Files {
		dst := filepath.Join(bd, filepath.Base(p.Name))
		if st, err := os.Stat(dst); err == nil && st.Size() == p.Size {
			continue
		}
		if err := copyRange(f, p.Offset, p.Size, dst); err != nil {
			return n, fmt.Errorf("%s 를 풀지 못했어요: %v", p.Name, err)
		}
		n++
	}
	return n, nil
}

// makeSetup — 프로그램 exe 뒤에 파일들을 붙여 설치 파일을 만듦
func makeSetup(exe, out string, files []string) error {
	o, err := os.Create(out)
	if err != nil {
		return err
	}
	defer o.Close()
	in, err := os.Open(exe)
	if err != nil {
		return err
	}
	n, err := io.Copy(o, in)
	in.Close()
	if err != nil {
		return err
	}
	if readPayload(exe) != nil {
		return errors.New("프로그램 exe 에 이미 붙은 파일이 있어요. 원래 프로그램 exe 를 주세요")
	}
	idx := payloadIndex{ExeSize: n}
	off := n
	for _, p := range files {
		in, err := os.Open(p)
		if err != nil {
			return err
		}
		sz, err := io.Copy(o, in)
		in.Close()
		if err != nil {
			return err
		}
		idx.Files = append(idx.Files, payloadFile{Name: filepath.Base(p), Offset: off, Size: sz})
		off += sz
		fmt.Printf("붙임: %s (%d MB)\n", filepath.Base(p), sz>>20)
	}
	raw, _ := json.Marshal(idx)
	o.Write(raw)
	var tail [16]byte
	binary.LittleEndian.PutUint64(tail[:8], uint64(len(raw)))
	copy(tail[8:], payloadMagic)
	_, err = o.Write(tail[:])
	return err
}
