package main

// 3.x 에서 더한 연산들 — 기준 스킨의 화면 배치에 맞춰 숫자를 채우는 새 화면 파일, 정규식 고치기,
// 위젯 안 children 에 덧붙이기, 몬스터 글자 줄 내리기, 버프 아이콘 자리 옮기기, 그림 크기 맞추기 등

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"math"
	"regexp"
	"strconv"
	"strings"
)

// patchMore — features.go 의 patch 가 모르는 연산. 처리했으면 true
func (w *workspace) patchMore(op Op, label string, res *opResult) (bool, error) {
	switch op.T {
	case "gen_file":
		// 기준 스킨의 화면 배치(경험치 바·파티 아이콘 위치)에 맞춰 숫자를 채운 새 화면 파일
		if w.exists(op.P) {
			raw, _ := w.get(op.P)
			if op.Mark != "" && strings.Contains(string(raw), op.Mark) {
				res.Already++
				return true, nil
			}
		}
		w.set(op.P, []byte(w.fillTokens(op.Text)), label)
		res.Add++

	case "regex_sub":
		if !w.exists(op.P) {
			res.Missing++
			return true, nil
		}
		raw, err := w.get(op.P)
		if err != nil {
			return true, err
		}
		re, err := regexp.Compile(op.Re)
		if err != nil {
			return true, fmt.Errorf("고칠 규칙이 잘못됐어요: %v", err)
		}
		out := re.ReplaceAll(raw, []byte(w.fillTokens(op.Val)))
		if bytes.Equal(out, raw) {
			res.Already++
			return true, nil
		}
		w.set(op.P, out, label)
		res.Done++

	case "append_in":
		// 이름이 widget 인 위젯의 children 끝에 덧붙이기 (맨 바깥이 아니라 그 위젯 안)
		if !w.exists(op.P) {
			res.Missing++
			res.note("기준 스킨에 " + niceName(op.P) + "이(가) 없어 넣지 못해요")
			return true, nil
		}
		raw, err := w.get(op.P)
		if err != nil {
			return true, err
		}
		txt, bom := splitBOM(raw)
		if op.Mark != "" && strings.Contains(txt, op.Mark) {
			res.Already++
			return true, nil
		}
		a, b, ok := objSpan(txt, op.Widget, 0)
		if !ok {
			res.Missing++
			res.note(niceName(op.P) + " 안에 " + op.Widget + " 위젯이 없어 넣지 못해요")
			return true, nil
		}
		out, ok := appendChildIn(txt, a, b, w.fillTokens(op.Text))
		if !ok {
			res.Missing++
			return true, nil
		}
		w.set(op.P, joinBOM(out, bom), label)
		res.Done++

	case "copy_in", "copy_over":
		// copy_in: 기준 스킨 안의 파일(val)을 새 이름(p)으로 복사, 이미 있으면 그대로
		// copy_over: 있어도 덮어씀 (예: 출구 표시 색 바꾸기)
		if op.T == "copy_in" && w.exists(op.P) {
			res.Same++
			return true, nil
		}
		if !w.exists(op.Val) {
			res.Missing++
			return true, nil
		}
		src := w.final[lower(op.Val)]
		if e, ok := w.final[lower(op.P)]; ok && e.from == src.from && e.blob == src.blob && e.from == 1 {
			res.Same++
			return true, nil
		}
		if src.from == 1 {
			w.final[lower(op.P)] = &fileEntry{name: op.P, from: 1, blob: src.blob, label: label}
		} else {
			raw, err := w.get(op.Val)
			if err != nil {
				return true, err
			}
			w.set(op.P, raw, label)
		}
		res.Done++

	case "fit_sprite":
		// 그림(sprite)을 넣되, 기준 스킨의 같은 그림과 크기가 다르면 화면 배치가 어긋나므로 기준 스킨 것을 둠
		if op.B == nil {
			return true, nil
		}
		if !w.exists(op.P) {
			w.final[lower(op.P)] = &fileEntry{name: op.P, from: 1, blob: *op.B, label: label}
			res.Add++
			return true, nil
		}
		raw, err := w.get(op.P)
		if err != nil {
			return true, err
		}
		nb, err := blobRaw(*op.B)
		if err != nil {
			return true, err
		}
		bw, bh, ok1 := spriteSize(raw)
		nw, nh, ok2 := spriteSize(nb)
		switch {
		case bytes.Equal(raw, nb):
			res.Same++
		case ok1 && ok2 && (bw != nw || bh != nh):
			res.Already++
			res.note(fmt.Sprintf("기준 스킨의 %s 크기(%dx%d)가 달라서 기준 스킨 것을 그대로 둬요", niceName(op.P), bw, bh))
		default:
			w.final[lower(op.P)] = &fileEntry{name: w.final[lower(op.P)].name, from: 1, blob: *op.B, label: label}
			res.Replace++
		}

	case "shift_y":
		// 몬스터 이름 아래 줄(면역 등)을 특성 줄 글자 크기·줄바꿈만큼 내림
		if !w.exists(op.P) {
			res.Missing++
			return true, nil
		}
		dy := monsterLineShift(w.params)
		if dy == 0 {
			res.Already++
			return true, nil
		}
		raw, err := w.get(op.P)
		if err != nil {
			return true, err
		}
		txt, bom := splitBOM(raw)
		a, b, ok := objSpan(txt, op.Widget, 0)
		if !ok {
			res.Missing++
			return true, nil
		}
		re := regexp.MustCompile(`("rect"\s*:\s*\{[^}]*?"y"\s*:\s*)(-?\d+)`)
		seg := txt[a:b]
		m := re.FindStringSubmatchIndex(seg)
		if m == nil {
			res.Missing++
			return true, nil
		}
		y, _ := strconv.Atoi(seg[m[4]:m[5]])
		seg = seg[:m[4]] + strconv.Itoa(y+dy) + seg[m[5]:]
		w.set(op.P, joinBOM(txt[:a]+seg+txt[b:], bom), label)
		res.Done++

	case "icon_shift":
		n, err := w.shiftBuffIcons(label)
		if err != nil {
			return true, err
		}
		if n > 0 {
			res.Done++
			res.note(fmt.Sprintf("버프 아이콘 %d개의 자리·크기를 옮겨요", n))
		} else {
			res.Already++
		}

	default:
		return false, nil
	}
	return true, nil
}

// appendChildIn — t[a:b] 객체 바로 아래 "children" 배열 끝에 붙이기
func appendChildIn(t string, a, b int, block string) (string, bool) {
	d := 0
	inStr := false
	for i := a; i < b; i++ {
		c := t[i]
		if inStr {
			if c == '\\' {
				i++
			} else if c == '"' {
				inStr = false
			}
			continue
		}
		switch c {
		case '"':
			if d == 1 && strings.HasPrefix(t[i:], `"children"`) {
				j := strings.IndexByte(t[i:], '[')
				if j < 0 {
					return "", false
				}
				return insertArrayEnd(t, i+j, block)
			}
			inStr = true
		case '/':
			if i+1 < b && t[i+1] == '/' {
				if j := strings.IndexByte(t[i:], '\n'); j >= 0 {
					i += j
				}
			}
		case '{', '[':
			d++
		case '}', ']':
			d--
		}
	}
	// children 이 없으면 객체 끝에 새로 만듦
	end := strings.LastIndex(t[a:b], "}") + a
	body := strings.TrimRight(stripTrailingComments(t[:end]), " \t\r\n")
	return body + ",\n\"children\": [\n" + block + "\n]\n" + t[end:], true
}

// ---------------------------------------------------------------- 화면 배치 숫자 채우기

var lvFonts = map[string]string{"작게": "$SmallFontSize", "보통": "$MediumFontSize", "크게": "$LargeFontSize", "아주 크게": "$ExtraLargeFontSize"}

func (w *workspace) num(id string, def float64) float64 {
	if v, err := strconv.ParseFloat(w.param(id), 64); err == nil {
		return v
	}
	return def
}

func it(v int) string { return strconv.Itoa(v) }

func (w *workspace) fillTokens(text string) string {
	if !strings.Contains(text, "{") {
		return text
	}
	for k, v := range w.layoutTokens() {
		text = strings.ReplaceAll(text, "{"+k+"}", v)
	}
	return text
}

// layoutTokens — 화면 파일 글 안의 {이름} 자리에 넣을 값.
// 기준 스킨의 화면 배치(경험치 바·파티 아이콘 자리)를 읽어 거기에 맞추고, 사용자가 고른 숫자만큼 옮김
func (w *workspace) layoutTokens() map[string]string {
	if w.tokens != nil {
		return w.tokens
	}
	t := map[string]string{}
	fl := w.num

	// 파티원 레벨 (partyhd): farming 은 파티 아이콘 기준 (95,100), 간격 130 에 맞춰 만든 배치
	hx, hy, gap := 95, 100, 130
	if raw, err := w.get(`data\global\ui\layouts\hireablespanelhd.json`); err == nil {
		txt, _ := splitBOM(raw)
		if m := regexp.MustCompile(`"fields"\s*:\s*\{[^{}]*?"rect"\s*:\s*\{\s*"x"\s*:\s*(-?\d+)\s*,\s*"y"\s*:\s*(-?\d+)`).FindStringSubmatch(txt); m != nil {
			hx, _ = strconv.Atoi(m[1])
			hy, _ = strconv.Atoi(m[2])
		}
		if m := regexp.MustCompile(`"nextIconOffset"\s*:\s*\{\s*"y"\s*:\s*(-?\d+)`).FindStringSubmatch(txt); m != nil {
			gap, _ = strconv.Atoi(m[1])
		}
	}
	t["LX"], t["LY"], t["IH"] = it(-200+hx-95), it(-130+hy-100), it(gap)
	t["PL_X"] = it(560 + int(fl("pl_x", 0)))
	t["PL_Y"] = it(72 + int(fl("pl_y", 0)))
	t["PL_FONT"] = fontOr(lvFonts, w.param("pl_size"), "$SmallFontSize")

	// 내 레벨·경험치 (myinfohd): 기준 스킨 하단 바의 경험치 바 가운데에 맞춤
	// 기능이 하단 바를 통째로 바꿔도, 사용자가 익숙한 기준 스킨의 원래 경험치 바 자리를 기준으로 삼음
	cx, top := 4, -251 // yupgoolg_1 기준값 (하단 바 -1454,-412 + 경험치 바 1078,161)
	if raw, err := w.baseRead(`data\global\ui\layouts\hudpanelhd.json`); err == nil {
		txt, _ := splitBOM(raw)
		px, py := -1454, -412
		if m := regexp.MustCompile(`"rect"\s*:\s*\{\s*"x"\s*:\s*(-?\d+)\s*,\s*"y"\s*:\s*(-?\d+)`).FindStringSubmatch(txt); m != nil {
			px, _ = strconv.Atoi(m[1])
			py, _ = strconv.Atoi(m[2])
		}
		if a, b, ok := objSpan(txt, "ExpBar", 0); ok {
			if m := regexp.MustCompile(`"rect"\s*:\s*\{\s*"x"\s*:\s*(-?\d+)\s*,\s*"y"\s*:\s*(-?\d+)`).FindStringSubmatch(txt[a:b]); m != nil {
				bx, _ := strconv.Atoi(m[1])
				by, _ := strconv.Atoi(m[2])
				cx, top = px+bx+380, py+by
			}
		}
	}
	dx, dy, lgap := int(fl("lx_dx", 0)), int(fl("lx_dy", 0)), int(fl("lx_gap", 52))
	y := top - 169 + dy
	t["LV_FONT"] = fontOr(lvFonts, w.param("lx_size"), "$SmallFontSize")
	switch w.param("lx_line") {
	case "한 줄 (레벨 | 경험치)":
		t["FC_X"], t["FC_Y"], t["FC_W"], t["FC_AL"] = it(cx-764+dx), it(y), "754", "right"
		t["XP_X"], t["XP_Y"], t["XP_W"], t["XP_AL"] = it(cx+10+dx), it(y), "754", "left"
	case "양 끝":
		t["FC_X"], t["FC_Y"], t["FC_W"], t["FC_AL"] = it(cx-380+dx), it(y), "760", "left"
		t["XP_X"], t["XP_Y"], t["XP_W"], t["XP_AL"] = it(cx-380+dx), it(y), "760", "right"
	default: // 두 줄 (가운데)
		t["FC_X"], t["FC_Y"], t["FC_W"], t["FC_AL"] = it(cx-764+dx), it(y), "1528", "center"
		t["XP_X"], t["XP_Y"], t["XP_W"], t["XP_AL"] = it(cx-764+dx), it(y+lgap), "1528", "center"
	}

	// 미니맵 (miniautomaphd) 과 그 아래 게임 정보 글자 (hudwarningshd)
	mx, my, size := int(fl("mp_x", -10)), int(fl("mp_y", 10)), fl("mp_size", 23)
	t["MP_X"], t["MP_Y"] = it(mx), it(my)
	t["MP_FRAC"] = fmt.Sprintf("%.3f", size/100)
	t["MP_ZOOM"] = fmt.Sprintf("%.3f", fl("mp_zoom", 0.45))
	t["MP_OP"] = fmt.Sprintf("%.3f", fl("mp_alpha", 100)/100)
	t["MI_X"] = it(mx - 10 + int(fl("mi_dx", 0)))
	t["MI_Y"] = it(my + int(size*440/23) + int(fl("mi_dy", 0)))

	// 대기실 버튼 (hudpanelhd)
	t["QL_X"], t["QL_Y"] = it(int(fl("ql_x", 300))), it(int(fl("ql_y", -130)))
	t["QL_SCALE"] = strconv.FormatFloat(math.Round(fl("ql_size", 60))/100, 'f', -1, 64)

	w.tokens = t
	return t
}

// baseRead — 기능을 얹기 전 기준 스킨의 원래 파일
func (w *workspace) baseRead(p string) ([]byte, error) {
	lp := lower(p)
	if !w.base.has(lp) {
		return nil, fmt.Errorf("없음")
	}
	return w.base.read(lp)
}

func fontOr(m map[string]string, k, def string) string {
	if v, ok := m[k]; ok {
		return v
	}
	return def
}

// monsterLineShift — 특성 줄이 커지거나 줄이 늘어난 만큼 아래 줄(면역 등)을 내릴 값
var fontShift = map[string]int{"크게": 40, "아주 크게": 55, "최대": 75}

func monsterLineShift(params map[string]string) int {
	dy := fontShift[strings.TrimSpace(params["mm_font_size"])]
	if strings.TrimSpace(params["mm_res_nl"]) != "같은 줄로" {
		dy += 50
	}
	if g, err := strconv.Atoi(strings.TrimSpace(params["mm_gap"])); err == nil {
		dy += g
	}
	return dy
}

// ---------------------------------------------------------------- 버프 아이콘 자리·크기

var (
	entityRe   = regexp.MustCompile(`\{\s*"type"\s*:\s*"Entity"`)
	iconFileRe = regexp.MustCompile(`(?i)"filename"\s*:\s*"[^"]*skill(?:off)?icon/[^"]*\.particles"`)
	posXYZRe   = regexp.MustCompile(`("position"\s*:\s*\{\s*"x"\s*:\s*)(-?[\d.]+)(\s*,\s*"y"\s*:\s*)(-?[\d.]+)([^}]*?"z"\s*:\s*)(-?[\d.]+)`)
	scaleRe    = regexp.MustCompile(`("scale"\s*:\s*\{\s*"x"\s*:\s*)(-?[\d.]+)(\s*,\s*"y"\s*:\s*)(-?[\d.]+)(\s*,\s*"z"\s*:\s*)(-?[\d.]+)`)
)

// shiftBuffIcons — 버프 아이콘(farming) 위치를 bi_dx·bi_dy 만큼, 간격을 bi_gap %, 크기를 bi_size % 로
func (w *workspace) shiftBuffIcons(label string) (int, error) {
	dx, dy := w.num("bi_dx", 0), w.num("bi_dy", 0)
	gap, size := w.num("bi_gap", 100)/100, w.num("bi_size", 100)/100
	if dx == 0 && dy == 0 && gap == 1 && size == 1 {
		return 0, nil
	}
	const cx, cz = 122.0, 124.0 // 아이콘 줄의 가운데
	f := func(v float64) string { return strconv.FormatFloat(math.Round(v*100)/100, 'f', -1, 64) }
	n := 0
	for lp := range w.final {
		if !strings.HasPrefix(lp, `data\hd\overlays\`) || !strings.HasSuffix(lp, ".json") {
			continue
		}
		raw, err := w.get(lp)
		if err != nil {
			return n, err
		}
		txt, bom := splitBOM(raw)
		if !iconFileRe.MatchString(txt) {
			continue
		}
		locs := entityRe.FindAllStringIndex(txt, -1)
		changed := false
		for i := len(locs) - 1; i >= 0; i-- {
			a := locs[i][0]
			b, ok := matchBrace(txt, a)
			if !ok {
				continue
			}
			seg := txt[a:b]
			if !iconFileRe.MatchString(seg) {
				continue
			}
			seg = posXYZRe.ReplaceAllStringFunc(seg, func(m string) string {
				p := posXYZRe.FindStringSubmatch(m)
				x, _ := strconv.ParseFloat(p[2], 64)
				y, _ := strconv.ParseFloat(p[4], 64)
				z, _ := strconv.ParseFloat(p[6], 64)
				x = cx + (x-cx)*gap + dx
				z = cz + (z-cz)*gap - dx
				y -= dy
				return p[1] + f(x) + p[3] + f(y) + p[5] + f(z)
			})
			if size != 1 {
				if scaleRe.MatchString(seg) {
					seg = scaleRe.ReplaceAllStringFunc(seg, func(m string) string {
						p := scaleRe.FindStringSubmatch(m)
						sx, _ := strconv.ParseFloat(p[2], 64)
						sy, _ := strconv.ParseFloat(p[4], 64)
						sz, _ := strconv.ParseFloat(p[6], 64)
						return p[1] + f(sx*size) + p[3] + f(sy*size) + p[5] + f(sz*size)
					})
				} else if j := strings.Index(seg, `"position"`); j >= 0 {
					if k := strings.IndexByte(seg[j:], '}'); k >= 0 {
						k += j + 1
						seg = seg[:k] + `, "scale": { "x": ` + f(size) + `, "y": ` + f(size) + `, "z": ` + f(size) + ` }` + seg[k:]
					}
				}
			}
			txt = txt[:a] + seg + txt[b:]
			changed = true
		}
		if changed {
			w.set(lp, joinBOM(txt, bom), label)
			n++
		}
	}
	return n, nil
}

// ---------------------------------------------------------------- sprite 크기

// spriteSize — D2R .sprite (SpA1) 그림의 가로·세로
func spriteSize(b []byte) (int, int, bool) {
	if len(b) < 16 || string(b[:4]) != "SpA1" && string(b[:4]) != "SPa1" && !strings.EqualFold(string(b[:4]), "spa1") {
		return 0, 0, false
	}
	return int(binary.LittleEndian.Uint16(b[6:])), int(binary.LittleEndian.Uint16(b[12:])), true
}
