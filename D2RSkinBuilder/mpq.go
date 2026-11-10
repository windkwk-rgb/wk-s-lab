package main

// MPQ(버전 1) 파일 쓰기 — 외부 DLL 없이 직접 만든다.
// 파일 내용은 데이터 팩에 이미 "조각 오프셋 표 + zlib 조각" 형태로 들어 있어 그대로 복사한다.

import (
	"bufio"
	"bytes"
	"compress/zlib"
	"encoding/binary"
	"fmt"
	"io"
	"os"
	"strings"
)

const (
	sectorSize    = 0x4000
	flagExists    = 0x80000000
	flagCompress  = 0x00000200
	hashEmpty     = 0xFFFFFFFF
	mpqHeaderSize = 32
)

var cryptTable [0x500]uint32

func init() {
	seed := uint32(0x00100001)
	for i := 0; i < 0x100; i++ {
		idx := i
		for j := 0; j < 5; j++ {
			seed = (seed*125 + 3) % 0x2AAAAB
			t1 := (seed & 0xFFFF) << 16
			seed = (seed*125 + 3) % 0x2AAAAB
			t2 := seed & 0xFFFF
			cryptTable[idx] = t1 | t2
			idx += 0x100
		}
	}
}

func hashString(s string, typ uint32) uint32 {
	seed1, seed2 := uint32(0x7FED7FED), uint32(0xEEEEEEEE)
	for i := 0; i < len(s); i++ {
		ch := uint32(s[i])
		if ch >= 'a' && ch <= 'z' {
			ch -= 'a' - 'A'
		} else if ch == '/' {
			ch = '\\'
		}
		seed1 = cryptTable[typ*0x100+ch] ^ (seed1 + seed2)
		seed2 = ch + seed1 + seed2 + (seed2 << 5) + 3
	}
	return seed1
}

func encrypt(data []uint32, key uint32) {
	seed := uint32(0xEEEEEEEE)
	for i, v := range data {
		seed += cryptTable[0x400+(key&0xFF)]
		data[i] = v ^ (key + seed)
		key = ((^key << 0x15) + 0x11111111) | (key >> 0x0B)
		seed = v + seed + (seed << 5) + 3
	}
}

// sectorizeData — 일반 바이트를 MPQ 압축 파일 데이터로 (패치한 파일·목록 파일용)
func sectorizeData(data []byte) []byte { return sectorizeWith(data, sectorSize) }

func sectorizeWith(data []byte, sectorSize int) []byte {
	if len(data) == 0 {
		return nil
	}
	n := (len(data) + sectorSize - 1) / sectorSize
	parts := make([][]byte, n)
	for i := 0; i < n; i++ {
		end := (i + 1) * sectorSize
		if end > len(data) {
			end = len(data)
		}
		raw := data[i*sectorSize : end]
		var b bytes.Buffer
		b.WriteByte(0x02)
		w, _ := zlib.NewWriterLevel(&b, 9)
		w.Write(raw)
		w.Close()
		if b.Len() < len(raw) {
			parts[i] = b.Bytes()
		} else {
			parts[i] = raw
		}
	}
	out := make([]byte, 4*(n+1))
	off := uint32(4 * (n + 1))
	for i, p := range parts {
		binary.LittleEndian.PutUint32(out[4*i:], off)
		off += uint32(len(p))
	}
	binary.LittleEndian.PutUint32(out[4*n:], off)
	for _, p := range parts {
		out = append(out, p...)
	}
	return out
}

// unsectorize — 조각 형태 데이터를 원래 바이트로 (패치할 때 원본 읽기용)
func unsectorize(data []byte, usize int) ([]byte, error) {
	if usize == 0 {
		return []byte{}, nil
	}
	n := (usize + sectorSize - 1) / sectorSize
	out := make([]byte, 0, usize)
	for i := 0; i < n; i++ {
		a := binary.LittleEndian.Uint32(data[4*i:])
		b := binary.LittleEndian.Uint32(data[4*i+4:])
		part := data[a:b]
		want := sectorSize
		if rem := usize - i*sectorSize; rem < want {
			want = rem
		}
		if len(part) == want {
			out = append(out, part...)
			continue
		}
		if part[0] != 0x02 {
			return nil, fmt.Errorf("알 수 없는 압축 방식 %x", part[0])
		}
		r, err := zlib.NewReader(bytes.NewReader(part[1:]))
		if err != nil {
			return nil, err
		}
		chunk, err := io.ReadAll(r)
		if err != nil {
			return nil, err
		}
		out = append(out, chunk...)
	}
	return out, nil
}

type mpqFile struct {
	name  string
	data  []byte // 조각 형태(압축) 데이터
	usize int
	key   string // (쓰지 않음)
	flags uint32 // 0 이면 보통(압축). 기준 스킨 블록을 그대로 복사할 때는 원래 플래그
}

// writeMPQ — files를 MPQ로 저장. progress(done,total) 호출.
func writeMPQ(path string, files []mpqFile, ss int, progress func(int, int)) error {
	names := make([]string, 0, len(files)+1)
	for _, f := range files {
		names = append(names, f.name)
	}
	listfile := []byte(strings.Join(names, "\r\n") + "\r\n")
	files = append(files, mpqFile{name: "(listfile)", data: sectorizeWith(listfile, ss), usize: len(listfile), key: "(listfile)"})

	hashSize := uint32(16)
	for hashSize < uint32(len(files))*2 {
		hashSize <<= 1
	}

	out, err := os.Create(path)
	if err != nil {
		return err
	}
	defer out.Close()
	w := bufio.NewWriterSize(out, 4<<20)
	w.Write(make([]byte, mpqHeaderSize))
	pos := uint32(mpqHeaderSize)

	type block struct{ pos, csize, fsize, flags uint32 }
	// 파일마다 따로 저장 (같은 내용을 공유하면 일부 프로그램이 파일을 못 찾음)
	blocks := make([]block, 0, len(files))
	fileBlock := make([]uint32, len(files))
	for i, f := range files {
		flags := f.flags
		if flags == 0 {
			flags = flagExists
			if f.usize > 0 {
				flags |= flagCompress
			}
		}
		blocks = append(blocks, block{pos, uint32(len(f.data)), uint32(f.usize), flags})
		fileBlock[i] = uint32(i)
		if _, err := w.Write(f.data); err != nil {
			return err
		}
		pos += uint32(len(f.data))
		if progress != nil && (i%100 == 0 || i == len(files)-1) {
			progress(i+1, len(files))
		}
	}

	// 해시 표
	ht := make([]uint32, hashSize*4)
	for i := range ht {
		ht[i] = hashEmpty
	}
	for i, f := range files {
		idx := hashString(f.name, 0) & (hashSize - 1)
		for ht[idx*4+3] != hashEmpty {
			idx = (idx + 1) & (hashSize - 1)
		}
		ht[idx*4] = hashString(f.name, 1)
		ht[idx*4+1] = hashString(f.name, 2)
		ht[idx*4+2] = 0 // 언어 0, 플랫폼 0
		ht[idx*4+3] = fileBlock[i]
	}
	encrypt(ht, hashString("(hash table)", 3))
	hashPos := pos
	if err := binary.Write(w, binary.LittleEndian, ht); err != nil {
		return err
	}
	pos += uint32(len(ht) * 4)

	bt := make([]uint32, 0, len(blocks)*4)
	for _, b := range blocks {
		bt = append(bt, b.pos, b.csize, b.fsize, b.flags)
	}
	encrypt(bt, hashString("(block table)", 3))
	blockPos := pos
	if err := binary.Write(w, binary.LittleEndian, bt); err != nil {
		return err
	}
	pos += uint32(len(bt) * 4)
	if err := w.Flush(); err != nil {
		return err
	}

	hdr := make([]byte, mpqHeaderSize)
	copy(hdr, "MPQ\x1a")
	binary.LittleEndian.PutUint32(hdr[4:], mpqHeaderSize)
	binary.LittleEndian.PutUint32(hdr[8:], pos)
	binary.LittleEndian.PutUint16(hdr[12:], 0) // 버전 1
	shift := uint16(0)
	for 512<<shift < ss {
		shift++
	}
	binary.LittleEndian.PutUint16(hdr[14:], shift) // 512<<shift = 조각 크기
	binary.LittleEndian.PutUint32(hdr[16:], hashPos)
	binary.LittleEndian.PutUint32(hdr[20:], blockPos)
	binary.LittleEndian.PutUint32(hdr[24:], hashSize)
	binary.LittleEndian.PutUint32(hdr[28:], uint32(len(blocks)))
	if _, err := out.WriteAt(hdr, 0); err != nil {
		return err
	}
	return out.Sync()
}
