package main

// 스킨 새 버전 파일(mpq) 읽기 — 목록(listfile)으로 파일을 찾고, zlib/bzip2 압축과 암호화를 풀어 꺼낸다.

import (
	"bytes"
	"compress/bzip2"
	"compress/zlib"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
)

type mpqReader struct {
	f          *os.File
	base       int64
	sector     int
	hashSize   uint32
	hash       []uint32
	blocks     []uint32
	names      map[string]string // 소문자 → 원래 이름
	blockOfKey map[string]uint32
}

func decrypt(data []uint32, key uint32) {
	seed := uint32(0xEEEEEEEE)
	for i := range data {
		seed += cryptTable[0x400+(key&0xFF)]
		v := data[i] ^ (key + seed)
		key = ((^key << 0x15) + 0x11111111) | (key >> 0x0B)
		seed = v + seed + (seed << 5) + 3
		data[i] = v
	}
}

func decryptBytes(b []byte, key uint32) {
	n := len(b) / 4
	w := make([]uint32, n)
	for i := 0; i < n; i++ {
		w[i] = binary.LittleEndian.Uint32(b[4*i:])
	}
	decrypt(w, key)
	for i := 0; i < n; i++ {
		binary.LittleEndian.PutUint32(b[4*i:], w[i])
	}
}

func openMPQ(path string) (*mpqReader, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	r := &mpqReader{f: f, base: -1}
	buf := make([]byte, 32)
	for off := int64(0); off < 64<<20; off += 512 {
		if _, err := f.ReadAt(buf, off); err != nil {
			break
		}
		if string(buf[:4]) == "MPQ\x1a" {
			r.base = off
			break
		}
		if string(buf[:4]) == "MPQ\x1b" { // 사용자 데이터 머리 → 진짜 머리 위치
			off = off + int64(binary.LittleEndian.Uint32(buf[8:])) - 512
		}
	}
	if r.base < 0 {
		f.Close()
		return nil, errors.New("MPQ 파일이 아니에요")
	}
	r.sector = 512 << binary.LittleEndian.Uint16(buf[14:])
	hashPos := binary.LittleEndian.Uint32(buf[16:])
	blockPos := binary.LittleEndian.Uint32(buf[20:])
	r.hashSize = binary.LittleEndian.Uint32(buf[24:])
	blockCount := binary.LittleEndian.Uint32(buf[28:])
	readTable := func(pos uint32, n uint32, key string) ([]uint32, error) {
		raw := make([]byte, n*16)
		if _, err := f.ReadAt(raw, r.base+int64(pos)); err != nil {
			return nil, err
		}
		t := make([]uint32, n*4)
		for i := range t {
			t[i] = binary.LittleEndian.Uint32(raw[4*i:])
		}
		decrypt(t, hashString(key, 3))
		return t, nil
	}
	if r.hash, err = readTable(hashPos, r.hashSize, "(hash table)"); err != nil {
		f.Close()
		return nil, err
	}
	if r.blocks, err = readTable(blockPos, blockCount, "(block table)"); err != nil {
		f.Close()
		return nil, err
	}
	r.names = map[string]string{}
	if lst, err := r.read("(listfile)"); err == nil {
		for _, line := range strings.FieldsFunc(string(lst), func(c rune) bool { return c == '\n' || c == '\r' || c == ';' }) {
			n := strings.TrimSpace(line)
			if n != "" && r.find(n) >= 0 {
				r.names[strings.ToLower(n)] = n
			}
		}
	}
	if len(r.names) == 0 {
		f.Close()
		return nil, errors.New("파일 목록((listfile))이 없는 MPQ라 읽을 수 없어요")
	}
	return r, nil
}

func (r *mpqReader) Close() { r.f.Close() }

func (r *mpqReader) find(name string) int {
	a, b := hashString(name, 1), hashString(name, 2)
	start := hashString(name, 0) & (r.hashSize - 1)
	for i := uint32(0); i < r.hashSize; i++ {
		k := (start + i) & (r.hashSize - 1)
		bi := r.hash[k*4+3]
		if bi == 0xFFFFFFFF {
			return -1
		}
		if r.hash[k*4] == a && r.hash[k*4+1] == b && bi != 0xFFFFFFFE {
			return int(bi)
		}
	}
	return -1
}

func decompressSector(b []byte) ([]byte, error) {
	if len(b) == 0 {
		return b, nil
	}
	switch b[0] {
	case 0x02:
		zr, err := zlib.NewReader(bytes.NewReader(b[1:]))
		if err != nil {
			return nil, err
		}
		return io.ReadAll(zr)
	case 0x10:
		return io.ReadAll(bzip2.NewReader(bytes.NewReader(b[1:])))
	}
	return nil, fmt.Errorf("지원하지 않는 압축 방식(0x%02x)", b[0])
}

func (r *mpqReader) read(name string) ([]byte, error) {
	bi := r.find(name)
	if bi < 0 || bi*4+3 >= len(r.blocks) {
		return nil, errors.New("없는 파일: " + name)
	}
	pos, csize, fsize, flags := r.blocks[bi*4], r.blocks[bi*4+1], r.blocks[bi*4+2], r.blocks[bi*4+3]
	if flags&0x80000000 == 0 {
		return nil, errors.New("지워진 파일: " + name)
	}
	if fsize == 0 {
		return []byte{}, nil
	}
	if flags&0x100 != 0 {
		return nil, errors.New("지원하지 않는 옛 압축(implode): " + name)
	}
	raw := make([]byte, csize)
	if _, err := r.f.ReadAt(raw, r.base+int64(pos)); err != nil {
		return nil, err
	}
	var key uint32
	if flags&0x10000 != 0 {
		base := name
		if i := strings.LastIndexAny(base, `\/`); i >= 0 {
			base = base[i+1:]
		}
		key = hashString(base, 3)
		if flags&0x20000 != 0 {
			key = (key + pos) ^ fsize
		}
	}
	compressed := flags&0x200 != 0
	if flags&0x01000000 != 0 { // 한 덩어리 파일
		if flags&0x10000 != 0 {
			decryptBytes(raw, key)
		}
		if compressed && csize < fsize {
			return decompressSector(raw)
		}
		return raw, nil
	}
	n := (int(fsize) + r.sector - 1) / r.sector
	if !compressed {
		out := raw
		if flags&0x10000 != 0 {
			out = make([]byte, 0, fsize)
			for i := 0; i < n; i++ {
				end := (i + 1) * r.sector
				if end > len(raw) {
					end = len(raw)
				}
				part := append([]byte(nil), raw[i*r.sector:end]...)
				decryptBytes(part, key+uint32(i))
				out = append(out, part...)
			}
		}
		return out[:fsize], nil
	}
	entries := n + 1
	if flags&0x04000000 != 0 {
		entries++
	}
	tbl := append([]byte(nil), raw[:4*entries]...)
	if flags&0x10000 != 0 {
		decryptBytes(tbl, key-1)
	}
	out := make([]byte, 0, fsize)
	for i := 0; i < n; i++ {
		a := binary.LittleEndian.Uint32(tbl[4*i:])
		b := binary.LittleEndian.Uint32(tbl[4*i+4:])
		if int(b) > len(raw) || a > b {
			return nil, errors.New("손상된 파일: " + name)
		}
		part := append([]byte(nil), raw[a:b]...)
		if flags&0x10000 != 0 {
			decryptBytes(part, key+uint32(i))
		}
		want := r.sector
		if rem := int(fsize) - i*r.sector; rem < want {
			want = rem
		}
		if len(part) < want {
			d, err := decompressSector(part)
			if err != nil {
				return nil, fmt.Errorf("%s: %v", name, err)
			}
			part = d
		}
		out = append(out, part...)
	}
	return out, nil
}
