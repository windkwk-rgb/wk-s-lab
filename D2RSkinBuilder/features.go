package main

// 기능 팩(features.json)과 기준 스킨(mpq)으로 새 mpq를 만든다.
// 기준 스킨의 파일은 그대로 두고, 고른 기능의 연산(ops)만 덧붙인다.

import (
	"bytes"
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"unicode/utf8"
)

// ---------------------------------------------------------------- 기능 팩

type Op struct {
	T           string   `json:"t"`
	P           string   `json:"p"`
	B           *int     `json:"b"`
	First       string   `json:"first"`
	Last        string   `json:"last"`
	Widget      string   `json:"widget"`
	Text        string   `json:"text"`
	Mark        string   `json:"mark"`
	YFrom       string   `json:"yfrom"`
	YDef        int      `json:"ydef"`
	Key         string   `json:"key"`
	Param       string   `json:"param"`
	Ko          string   `json:"ko"`
	En          string   `json:"en"`
	Keys        []string `json:"keys"`
	Entries     []string `json:"entries"`
	Prefer      string   `json:"prefer"`
	Entities    []string `json:"entities"`
	Row         string   `json:"row"`
	Col         string   `json:"col"`
	Val         string   `json:"val"`
	IfEmpty     bool     `json:"ifempty"`
	Re          string   `json:"re"`
	OnlyMissing bool     `json:"onlymissing"`
	IfKeys      []string `json:"ifkeys"`
	SkipIf      *struct {
		P, Row, Col, Re string
	} `json:"skipif"`
	Else []Op `json:"else"`
}

// ["f", path, blob] / ["n", path, blob] / ["d", path] 도 Op 로
func (o *Op) UnmarshalJSON(b []byte) error {
	if len(b) > 0 && b[0] == '[' {
		var a []any
		if err := json.Unmarshal(b, &a); err != nil {
			return err
		}
		o.T = a[0].(string)
		o.P = a[1].(string)
		if len(a) > 2 {
			v := int(a[2].(float64))
			o.B = &v
		}
		return nil
	}
	type plain Op
	return json.Unmarshal(b, (*plain)(o))
}

type Option struct {
	Key   string `json:"key"`
	Label string `json:"label"`
	Ops   []Op   `json:"ops"`
}

type Param struct {
	ID      string   `json:"id"`
	Label   string   `json:"label"`
	Choices []string `json:"choices"`
	Default string   `json:"default"`
	Kind    string   `json:"kind"`
}

type Feature struct {
	ID       string   `json:"id"`
	Group    string   `json:"group"`
	Name     string   `json:"name"`
	Options  []Option `json:"options"`
	Params   []Param  `json:"params"`
	Requires []string `json:"requires"`
}

type Preset struct {
	Name    string            `json:"name"`
	Choices map[string]string `json:"choices"`
	Params  map[string]string `json:"params"`
}

type Pack struct {
	Presets  []Preset   `json:"presets"`
	Groups   []string   `json:"groups"`
	Features []Feature  `json:"features"`
	Blobs    [][3]int64 `json:"blobs"`
	Sha      []string   `json:"sha"`
}

var pack Pack
var featByID = map[string]*Feature{}

func loadPack(raw []byte) error {
	if err := json.Unmarshal(raw, &pack); err != nil {
		return err
	}
	for i := range pack.Features {
		featByID[pack.Features[i].ID] = &pack.Features[i]
	}
	return nil
}

// blobData — i번 데이터(조각 형태). 기본 데이터 길이 이상 위치는 패치의 extra.bin 안에 있음
func blobData(i int) []byte {
	b := pack.Blobs[i]
	if n := int64(len(blobsBin)); b[0] >= n {
		return extraBlobs[b[0]-n : b[0]-n+b[1]]
	}
	return blobsBin[b[0] : b[0]+b[1]]
}

func blobRaw(i int) ([]byte, error) {
	return unsectorize(blobData(i), int(pack.Blobs[i][2]))
}

// ---------------------------------------------------------------- 기준 스킨

type baseSkin struct {
	path    string
	r       *mpqReader
	names   map[string]string // 소문자 → 원래 이름
	modName string
	ours    bool // 스킨빌더가 만든 파일
	mu      sync.Mutex
	shaC    map[string]string
	textC   map[string][]byte
}

func openBase(path string) (*baseSkin, error) {
	r, err := openMPQ(path)
	if err != nil {
		return nil, err
	}
	b := &baseSkin{path: path, r: r, names: map[string]string{}, shaC: map[string]string{}, textC: map[string][]byte{}}
	for k, v := range r.names {
		if k == "(listfile)" || k == "(attributes)" || k == "(signature)" {
			continue
		}
		b.names[strings.ReplaceAll(k, "/", `\`)] = v
	}
	if raw, err := r.read("modinfo.json"); err == nil {
		var mi struct {
			Name string `json:"name"`
		}
		json.Unmarshal(bytes.TrimPrefix(raw, []byte("\xef\xbb\xbf")), &mi)
		b.modName = strings.TrimSpace(mi.Name)
	}
	if b.modName == "" {
		r.Close()
		return nil, errors.New("이 mpq 안에 modinfo.json(모드 이름)이 없어요. D2R 모드 mpq가 맞는지 확인해 주세요")
	}
	_, b.ours = b.names["skinbuilder.json"]
	return b, nil
}

func (b *baseSkin) close() {
	if b != nil && b.r != nil {
		b.r.Close()
	}
}

func (b *baseSkin) has(lp string) bool { _, ok := b.names[lp]; return ok }

func (b *baseSkin) size(lp string) int64 {
	bi := b.r.find(b.names[lp])
	if bi < 0 {
		return -1
	}
	return int64(b.r.blocks[bi*4+2])
}

func (b *baseSkin) read(lp string) ([]byte, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if t, ok := b.textC[lp]; ok {
		return t, nil
	}
	d, err := b.r.read(b.names[lp])
	if err != nil {
		return nil, err
	}
	if len(d) < 4<<20 && isTextPath(lp) {
		b.textC[lp] = d
	}
	return d, nil
}

func (b *baseSkin) sha(lp string) (string, error) {
	b.mu.Lock()
	if s, ok := b.shaC[lp]; ok {
		b.mu.Unlock()
		return s, nil
	}
	b.mu.Unlock()
	d, err := b.read(lp)
	if err != nil {
		return "", err
	}
	h := sha1.Sum(d)
	s := hex.EncodeToString(h[:])
	b.mu.Lock()
	b.shaC[lp] = s
	b.mu.Unlock()
	return s, nil
}

// 블록을 그대로 복사할 수 있으면 (원본 데이터, 플래그, 원래 크기)
func (b *baseSkin) rawBlock(lp string) ([]byte, uint32, uint32, bool) {
	bi := b.r.find(b.names[lp])
	if bi < 0 {
		return nil, 0, 0, false
	}
	pos, csize, fsize, flags := b.r.blocks[bi*4], b.r.blocks[bi*4+1], b.r.blocks[bi*4+2], b.r.blocks[bi*4+3]
	if flags&0x10000 != 0 || flags&0x80000000 == 0 { // 암호화된 파일은 다시 압축
		return nil, 0, 0, false
	}
	multi := flags&0x01000000 == 0 && flags&0x300 != 0 && fsize > 0
	if multi && b.r.sector != sectorSize {
		return nil, 0, 0, false
	}
	data := make([]byte, csize)
	if _, err := b.r.f.ReadAt(data, b.r.base+int64(pos)); err != nil {
		return nil, 0, 0, false
	}
	return data, flags, fsize, true
}

func isTextPath(lp string) bool {
	return strings.HasSuffix(lp, ".json") || strings.HasSuffix(lp, ".txt")
}

// ---------------------------------------------------------------- 만들기 상태

type fileEntry struct {
	name  string
	from  int // 0 기준 스킨, 1 블롭, 2 고친 내용
	blob  int
	data  []byte
	label string // 어느 기능에서
}

type workspace struct {
	base   *baseSkin
	final  map[string]*fileEntry
	params map[string]string
	log    func(string)
	notes  []string
	tokens map[string]string // 화면 배치 숫자 (layoutTokens)
	strID  map[string]int    // 글자 파일마다 다음에 줄 새 id
}

func newWorkspace(b *baseSkin, params map[string]string, log func(string)) *workspace {
	w := &workspace{base: b, final: map[string]*fileEntry{}, params: params, log: log}
	for lp, name := range b.names {
		w.final[lp] = &fileEntry{name: name, from: 0}
	}
	return w
}

func lower(p string) string { return strings.ToLower(strings.ReplaceAll(p, "/", `\`)) }

func (w *workspace) exists(p string) bool { _, ok := w.final[lower(p)]; return ok }

func (w *workspace) get(p string) ([]byte, error) {
	lp := lower(p)
	e, ok := w.final[lp]
	if !ok {
		return nil, errors.New("없는 파일: " + p)
	}
	switch e.from {
	case 0:
		return w.base.read(lp)
	case 1:
		return blobRaw(e.blob)
	}
	return e.data, nil
}

func (w *workspace) set(p string, data []byte, label string) {
	lp := lower(p)
	name := p
	if e, ok := w.final[lp]; ok {
		name = e.name
	}
	w.final[lp] = &fileEntry{name: name, from: 2, data: data, label: label}
}

func (w *workspace) param(id string) string { return strings.TrimSpace(w.params[id]) }

// ---------------------------------------------------------------- 연산 결과 (미리 보기용)

type opResult struct {
	Add, Same, Replace, Remove int
	Replaced                   []string
	Done, Already, Missing     int
	Notes                      []string
}

func (r *opResult) note(s string) {
	for _, n := range r.Notes {
		if n == s {
			return
		}
	}
	r.Notes = append(r.Notes, s)
}

// apply — 연산 하나. dry 이면 결과만 센다(내용은 바꾸지만 버리는 작업 공간에서)
func (w *workspace) apply(op Op, label string, res *opResult) error {
	switch op.T {
	case "f", "n":
		lp := lower(op.P)
		if e, ok := w.final[lp]; ok {
			if op.T == "n" {
				res.Same++
				return nil
			}
			same := false
			if e.from == 0 {
				if w.base.size(lp) == pack.Blobs[*op.B][2] {
					if s, err := w.base.sha(lp); err == nil && s == pack.Sha[*op.B] {
						same = true
					}
				}
			} else if e.from == 1 && pack.Sha[e.blob] == pack.Sha[*op.B] {
				same = true
			}
			if same {
				res.Same++
				return nil
			}
			res.Replace++
			if len(res.Replaced) < 200 {
				res.Replaced = append(res.Replaced, op.P)
			}
			w.final[lp] = &fileEntry{name: e.name, from: 1, blob: *op.B, label: label}
			return nil
		}
		res.Add++
		w.final[lp] = &fileEntry{name: op.P, from: 1, blob: *op.B, label: label}
	case "d":
		lp := lower(op.P)
		if _, ok := w.final[lp]; ok {
			delete(w.final, lp)
			res.Remove++
		} else {
			res.Same++
		}
	default:
		return w.patch(op, label, res)
	}
	return nil
}

func (w *workspace) runElse(op Op, label string, res *opResult, why string) error {
	if len(op.Else) == 0 {
		res.Missing++
		res.note(why)
		return nil
	}
	res.note(why)
	for _, e := range op.Else {
		if err := w.apply(e, label, res); err != nil {
			return err
		}
	}
	return nil
}

var screenKR = map[string]string{
	"mainmenupanelhd.json": "로비 화면", "pauselayouthd.json": "ESC 메뉴", "pauselayoutgardenhd.json": "워락 지역 ESC 메뉴",
	"bankexpansionlayouthd.json": "창고 화면", "vendorpanellayouthd.json": "상점 화면", "hudmonsterhealthhd.json": "몬스터 체력바 화면",
	"titlescreenpanelhd.json": "시작 화면", "states.txt": "게임 데이터(states.txt)",
}

func niceName(p string) string {
	b := p[strings.LastIndexAny(p, `\/`)+1:]
	if n, ok := screenKR[strings.ToLower(b)]; ok {
		return n
	}
	return b
}

func (w *workspace) patch(op Op, label string, res *opResult) error {
	switch op.T {
	case "replace_span", "insert_before", "append_child":
		if !w.exists(op.P) {
			return w.runElse(op, label, res, "기준 스킨에 "+niceName(op.P)+"이(가) 없어서 farming 것을 통째로 넣어요")
		}
		raw, err := w.get(op.P)
		if err != nil {
			return err
		}
		txt, bom := splitBOM(raw)
		if op.Mark != "" && strings.Contains(txt, op.Mark) {
			res.Already++
			res.note(niceName(op.P) + "에 이미 들어 있어요")
			return nil
		}
		var out string
		switch op.T {
		case "replace_span":
			a, _, ok1 := objSpan(txt, op.First, 0)
			_, b, ok2 := objSpan(txt, op.Last, 0)
			if !ok1 || !ok2 || b < a {
				return w.runElse(op, label, res, "기준 스킨의 "+niceName(op.P)+" 모양이 달라서 farming 화면으로 통째로 바꿔요")
			}
			out = txt[:a] + op.Text + txt[b:]
		case "insert_before":
			y := op.YDef
			if op.YFrom != "" {
				if a, b, ok := objSpan(txt, op.YFrom, 0); ok {
					if m := regexp.MustCompile(`"rect"\s*:\s*\{[^}]*?"y"\s*:\s*(-?\d+)`).FindStringSubmatch(txt[a:b]); m != nil {
						y, _ = strconv.Atoi(m[1])
					}
				}
			}
			text := strings.NewReplacer("{Y0}", strconv.Itoa(y), "{Y1}", strconv.Itoa(y+140), "{Y2}", strconv.Itoa(y+280)).Replace(op.Text)
			if a, _, ok := objSpan(txt, op.Widget, 0); ok {
				ls := strings.LastIndex(txt[:a], "\n") + 1
				out = txt[:ls] + text + txt[ls:]
			} else if o, ok := appendChild(txt, strings.TrimRight(text, ",\n ")); ok {
				out = o
			} else {
				return w.runElse(op, label, res, niceName(op.P)+" 모양을 알 수 없어 넣지 못했어요")
			}
		case "append_child":
			for _, k := range op.IfKeys { // 버튼이 쓰는 글자가 기준 스킨에 없으면 넣지 않음
				if lp, _ := w.findStringKey(k); lp == "" {
					res.Missing++
					res.note("기준 스킨에 " + k + " 글자가 없어 버튼을 넣지 않아요")
					return nil
				}
			}
			o, ok := appendChild(txt, w.fillTokens(op.Text))
			if !ok {
				return w.runElse(op, label, res, niceName(op.P)+" 모양을 알 수 없어 farming 화면으로 통째로 바꿔요")
			}
			out = o
		}
		w.set(op.P, joinBOM(out, bom), label)
		res.Done++
		res.note(niceName(op.P) + "에 덧붙여요 (화면 모양은 기준 스킨 그대로)")

	case "set_style":
		v, ok := fontSizes[w.param(op.Param)]
		if !ok {
			return nil
		}
		if !w.exists(op.P) {
			res.Missing++
			res.note("기준 스킨에 " + niceName(op.P) + "이(가) 없어 글자 크기는 그대로예요")
			return nil
		}
		raw, err := w.get(op.P)
		if err != nil {
			return err
		}
		txt, bom := splitBOM(raw)
		a, b, ok := objSpan(txt, op.Widget, 0)
		if !ok {
			res.Missing++
			return nil
		}
		re := regexp.MustCompile(`("` + regexp.QuoteMeta(op.Key) + `"\s*:\s*)"[^"]*"`)
		seg := txt[a:b]
		if m := re.FindStringSubmatchIndex(seg); m != nil {
			seg = seg[:m[0]] + seg[m[2]:m[3]] + `"` + v + `"` + seg[m[1]:]
			w.set(op.P, joinBOM(txt[:a]+seg+txt[b:], bom), label)
			res.Done++
		}

	case "monster_mods":
		lp := `data\local\lng\strings\monsters.json`
		if !w.exists(lp) {
			res.Missing++
			res.note("기준 스킨에 몬스터 글자 파일(monsters.json)이 없어 넣을 수 없어요")
			return nil
		}
		raw, err := w.get(lp)
		if err != nil {
			return err
		}
		out, n := patchMonsterMods(raw, monsterPlan(w.params))
		out = plainMonsterMods(out, w.params)
		if strings.TrimSpace(w.params["mm_res_nl"]) != "같은 줄로" {
			out = addResNewline(out)
		}
		w.set(lp, out, label)
		res.Done++
		res.note(fmt.Sprintf("특성 %d개를 강조하고, 나머지는 흰 글자로 둬요", n))

	case "glow":
		found := false
		for _, lp := range []string{`data\global\excel\states.txt`, `data\global\excel\base\states.txt`} {
			if !w.exists(lp) {
				continue
			}
			raw, err := w.get(lp)
			if err != nil {
				return err
			}
			if out, n := patchGlow(raw, w.param("exp_glow_color"), w.param("exp_glow_tint")); n > 0 {
				w.set(lp, out, label)
				found = true
			}
		}
		if found {
			res.Done++
		} else {
			res.Missing++
			res.note("기준 스킨에 게임 데이터(states.txt)가 없어 넣을 수 없어요")
		}

	case "regex_text":
		v := w.param(op.Param)
		if v == "" || !w.exists(op.P) {
			return nil
		}
		raw, err := w.get(op.P)
		if err != nil {
			return err
		}
		txt, bom := splitBOM(raw)
		q, _ := json.Marshal(v)
		if m := regexp.MustCompile(op.Re).FindStringSubmatchIndex(txt); m != nil {
			txt = txt[:m[0]] + txt[m[2]:m[3]] + string(q) + txt[m[4]:m[5]] + txt[m[1]:]
			w.set(op.P, joinBOM(txt, bom), label)
			res.Done++
		}

	case "str_set":
		lp, loc := w.findStringKey(op.Key)
		if lp == "" {
			res.Missing++
			return nil
		}
		raw, _ := w.get(lp)
		txt, bom := splitBOM(raw)
		block := txt[loc[0]:loc[1]]
		nb := block
		for _, lv := range [][2]string{{"koKR", op.Ko}, {"enUS", op.En}} {
			if lv[1] == "" {
				continue
			}
			re := regexp.MustCompile(`("` + lv[0] + `"\s*:\s*)"(?:[^"\\]|\\.)*"`)
			if m := re.FindStringSubmatchIndex(nb); m != nil {
				nb = nb[:m[0]] + nb[m[2]:m[3]] + lv[1] + nb[m[1]:]
			}
		}
		if nb == block {
			res.Already++
			return nil
		}
		w.set(lp, joinBOM(txt[:loc[0]]+nb+txt[loc[1]:], bom), label)
		res.Done++

	case "str_add":
		missing := []int{}
		for i, k := range op.Keys {
			if lp, loc := w.findStringKey(k); lp != "" {
				if op.OnlyMissing { // 기준 스킨에 이미 있는 글자는 그대로
					continue
				}
				// 이미 있는 글자는 내용만 바꾸기
				raw, _ := w.get(lp)
				txt, bom := splitBOM(raw)
				block := txt[loc[0]:loc[1]]
				nb := block
				for _, lang := range []string{"koKR", "enUS"} {
					re := regexp.MustCompile(`("` + lang + `"\s*:\s*)("(?:[^"\\]|\\.)*")`)
					sv := re.FindStringSubmatch(fixStar(op.Entries[i]))
					if m := re.FindStringSubmatchIndex(nb); m != nil && sv != nil && sv[2] != `""` {
						nb = nb[:m[4]] + sv[2] + nb[m[5]:]
					}
				}
				if nb != block {
					w.set(lp, joinBOM(txt[:loc[0]]+nb+txt[loc[1]:], bom), label)
				}
				continue
			}
			missing = append(missing, i)
		}
		if len(missing) == 0 {
			res.Already++
			return nil
		}
		target := ""
		dir := `data\local\lng\strings\`
		if w.exists(dir + op.Prefer) {
			target = dir + op.Prefer
		} else {
			for _, f := range []string{"ui.json", "item-modifiers.json", "item-names.json", "npcs.json", "bnet.json", "monsters.json"} {
				if w.exists(dir + f) {
					target = dir + f
					break
				}
			}
		}
		if target == "" {
			keys := []string{}
			for lp := range w.final {
				if strings.HasPrefix(lp, dir) && strings.HasSuffix(lp, ".json") {
					keys = append(keys, lp)
				}
			}
			sort.Strings(keys)
			if len(keys) > 0 {
				target = keys[0]
			}
		}
		if target == "" {
			res.Missing++
			res.note("기준 스킨에 글자 파일이 하나도 없어 도움말 글을 넣지 못해요 (버튼은 들어가요)")
			return nil
		}
		raw, err := w.get(target)
		if err != nil {
			return err
		}
		txt, bom := splitBOM(raw)
		// 새 글자의 id 는 30001 부터 차례로 (게임 기본 글자 id 와 겹치지 않게), 파일에 이미 있는 id 는 건너뜀
		used := map[int]bool{}
		idRe := regexp.MustCompile(`"id"\s*:\s*(\d+)`)
		for _, m := range idRe.FindAllStringSubmatch(txt, -1) {
			v, _ := strconv.Atoi(m[1])
			used[v] = true
		}
		if w.strID == nil {
			w.strID = map[string]int{}
		}
		next := w.strID[target]
		if next == 0 {
			next = 30001
		}
		var blocks []string
		for _, i := range missing {
			e := fixStar(op.Entries[i])
			for used[next] {
				next++
			}
			if m := idRe.FindStringSubmatchIndex(e); m != nil {
				e = e[:m[2]] + strconv.Itoa(next) + e[m[3]:]
			}
			used[next] = true
			next++
			blocks = append(blocks, e)
		}
		w.strID[target] = next
		end := strings.LastIndex(txt, "]")
		if end < 0 {
			res.Missing++
			return nil
		}
		body := strings.TrimRight(txt[:end], " \t\r\n")
		sep := ","
		if strings.HasSuffix(body, ",") || strings.HasSuffix(body, "[") {
			sep = ""
		}
		txt = body + sep + "\n  " + strings.Join(blocks, ",\n  ") + "\n" + txt[end:]
		w.set(target, joinBOM(txt, bom), label)
		res.Done++

	case "overlay_icon":
		lp := lower(op.P)
		if op.SkipIf != nil && w.tableMatch(op.SkipIf.P, op.SkipIf.Row, op.SkipIf.Col, op.SkipIf.Re) {
			res.Already++
			return nil
		}
		if !w.exists(lp) {
			res.Add++
			w.final[lp] = &fileEntry{name: op.P, from: 1, blob: *op.B, label: label}
			return nil
		}
		raw, err := w.get(lp)
		if err != nil {
			return err
		}
		txt, bom := splitBOM(raw)
		if hasIconEntity(txt) {
			res.Already++
			return nil
		}
		out, ok := appendToArray(txt, "entities", op.Entities)
		if !ok {
			res.Missing++
			return nil
		}
		w.set(lp, joinBOM(out, bom), label)
		res.Done++

	case "table_set":
		if !w.exists(op.P) {
			res.Missing++
			return nil
		}
		raw, err := w.get(op.P)
		if err != nil {
			return err
		}
		if out, changed := tableSet(raw, op.Row, op.Col, op.Val, op.IfEmpty); changed {
			w.set(op.P, out, label)
			res.Done++
		} else {
			res.Already++
		}
	default:
		if ok, err := w.patchMore(op, label, res); ok {
			return err
		}
		return errors.New("모르는 연산: " + op.T)
	}
	return nil
}

var iconEntityRe = regexp.MustCompile(`(?is)"filename"\s*:\s*"[^"]*icon[^"]*\.particles"`)

func hasIconEntity(txt string) bool {
	return iconEntityRe.MatchString(txt) || strings.Count(txt, "inheritOnlyPosition") > 0 && strings.Count(txt, `"type": "Entity"`)+strings.Count(txt, `"type":"Entity"`) > 1
}

// fixStar — "★ ÿc" 처럼 별 뒤에 빈칸이 있으면 게임에서 색 글자가 깨져 보여서 붙임
func fixStar(e string) string { return strings.ReplaceAll(e, "★ ÿc", "★ÿc") }

func (w *workspace) tableMatch(p, row, col, re string) bool {
	if !w.exists(p) {
		return false
	}
	raw, err := w.get(p)
	if err != nil {
		return false
	}
	v, ok := tableGet(raw, row, col)
	return ok && regexp.MustCompile(re).MatchString(v)
}

func (w *workspace) findStringKey(key string) (string, []int) {
	dir := `data\local\lng\strings\`
	keys := make([]string, 0)
	for lp := range w.final {
		if strings.HasPrefix(lp, dir) && strings.HasSuffix(lp, ".json") {
			keys = append(keys, lp)
		}
	}
	sort.Strings(keys)
	re := regexp.MustCompile(`\{[^{}]*"Key"\s*:\s*"` + regexp.QuoteMeta(key) + `"[^{}]*\}`)
	for _, lp := range keys {
		raw, err := w.get(lp)
		if err != nil {
			continue
		}
		txt, _ := splitBOM(raw)
		if loc := re.FindStringIndex(txt); loc != nil {
			return lp, loc
		}
	}
	return "", nil
}

// ---------------------------------------------------------------- 글자 다루기

// objSpan — "name": "X" 를 가진 객체 { ... } 의 범위
func objSpan(t, name string, from int) (int, int, bool) {
	re := regexp.MustCompile(`"name"\s*:\s*"` + regexp.QuoteMeta(name) + `"`)
	m := re.FindStringIndex(t[from:])
	if m == nil {
		return 0, 0, false
	}
	a := strings.LastIndex(t[:from+m[0]], "{")
	if a < 0 {
		return 0, 0, false
	}
	b, ok := matchBrace(t, a)
	return a, b, ok
}

// matchBrace — t[a] 가 { 또는 [ 일 때 짝 다음 위치 (문자열·주석 건너뜀)
func matchBrace(t string, a int) (int, bool) {
	d := 0
	inStr := false
	for i := a; i < len(t); i++ {
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
			inStr = true
		case '/':
			if i+1 < len(t) && t[i+1] == '/' {
				j := strings.IndexByte(t[i:], '\n')
				if j < 0 {
					return 0, false
				}
				i += j
			} else if i+1 < len(t) && t[i+1] == '*' {
				j := strings.Index(t[i+2:], "*/")
				if j < 0 {
					return 0, false
				}
				i += j + 3
			}
		case '{', '[':
			d++
		case '}', ']':
			d--
			if d == 0 {
				return i + 1, true
			}
		}
	}
	return 0, false
}

// appendChild — 맨 바깥 객체의 "children" 배열 끝에 붙이기
func appendChild(t, block string) (string, bool) {
	start := strings.IndexByte(t, '{')
	if start < 0 {
		return "", false
	}
	// 맨 바깥 수준의 "children" 찾기
	d := 0
	inStr := false
	for i := start; i < len(t); i++ {
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
			if i+1 < len(t) && t[i+1] == '/' {
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
	return "", false
}

func insertArrayEnd(t string, open int, block string) (string, bool) {
	end, ok := matchBrace(t, open)
	if !ok {
		return "", false
	}
	close := end - 1
	body := t[:close]
	// 맨 끝 주석·공백 뒤로 실제 마지막 글자
	trim := strings.TrimRight(stripTrailingComments(body), " \t\r\n")
	sep := ","
	if strings.HasSuffix(trim, ",") || strings.HasSuffix(trim, "[") {
		sep = ""
	}
	return t[:len(trim)] + sep + "\n" + block + "\n" + t[len(trim):], true
}

// 끝부분의 // 주석(줄 전체 또는 줄 끝)을 지우고 본 내용만 (위치 계산용 — 앞부분은 그대로)
func stripTrailingComments(s string) string {
	for {
		t := strings.TrimRight(s, " \t\r\n")
		ls := strings.LastIndex(t, "\n")
		line := t[ls+1:]
		if c := lineCommentAt(line); c >= 0 {
			s = t[:ls+1+c]
			continue
		}
		return t
	}
}

// lineCommentAt — 한 줄에서 문자열 밖의 // 위치 (없으면 -1)
func lineCommentAt(line string) int {
	inStr := false
	for i := 0; i < len(line); i++ {
		c := line[i]
		if inStr {
			if c == '\\' {
				i++
			} else if c == '"' {
				inStr = false
			}
			continue
		}
		if c == '"' {
			inStr = true
		} else if c == '/' && i+1 < len(line) && line[i+1] == '/' {
			return i
		}
	}
	return -1
}

func appendToArray(t, key string, items []string) (string, bool) {
	m := regexp.MustCompile(`"` + regexp.QuoteMeta(key) + `"\s*:\s*\[`).FindStringIndex(t)
	if m == nil {
		return "", false
	}
	return insertArrayEnd(t, m[1]-1, strings.Join(items, ",\n"))
}

// ---------------------------------------------------------------- 표(txt)

func tableGet(raw []byte, row, col string) (string, bool) {
	lines := strings.Split(string(raw), "\n")
	if len(lines) == 0 {
		return "", false
	}
	hdr := strings.Split(strings.TrimRight(lines[0], "\r"), "\t")
	ci := -1
	for i, h := range hdr {
		if h == col {
			ci = i
			break
		}
	}
	if ci < 0 {
		return "", false
	}
	for _, l := range lines[1:] {
		c := strings.Split(strings.TrimRight(l, "\r"), "\t")
		if len(c) > 0 && c[0] == row {
			if ci < len(c) {
				return c[ci], true
			}
			return "", true
		}
	}
	return "", false
}

func tableSet(raw []byte, row, col, val string, ifEmpty bool) ([]byte, bool) {
	lines := strings.Split(string(raw), "\n")
	hdr := strings.Split(strings.TrimRight(lines[0], "\r"), "\t")
	ci := -1
	for i, h := range hdr {
		if h == col {
			ci = i
			break
		}
	}
	if ci < 0 {
		return raw, false
	}
	for li, l := range lines[1:] {
		cr := strings.HasSuffix(l, "\r")
		c := strings.Split(strings.TrimRight(l, "\r"), "\t")
		if len(c) == 0 || c[0] != row {
			continue
		}
		for len(c) <= ci {
			c = append(c, "")
		}
		if c[ci] == val || (ifEmpty && c[ci] != "") {
			return raw, false
		}
		c[ci] = val
		nl := strings.Join(c, "\t")
		if cr {
			nl += "\r"
		}
		lines[li+1] = nl
		return []byte(strings.Join(lines, "\n")), true
	}
	return raw, false
}

// ---------------------------------------------------------------- 몬스터 특성 강조 · 발광 (v1과 같음)

var colorCodes = map[string]string{"노랑": "ÿc9", "빨강": "ÿc1", "주황": "ÿc8", "보라": "ÿc;",
	"파랑": "ÿc3", "초록": "ÿc2", "흰색": "ÿc0", "금색": "ÿc4"}

const resetColor = "ÿc5"

var markers = map[string][2]string{"★": {"★", "★"}, "!!": {"!!", "!!"}, "[ ]": {"[", "]"}, "없음": {"", ""}}

type monMod struct{ key, ko, en, def string }

var monsterMods = []monMod{
	{"monsteruniqueprop2", "번개 강화", "Lightning Enchanted", "노랑"},
	{"uniquecursed", "저주받음", "Cursed", "보라"},
	{"monsteruniqueprop6", "돌가죽", "Stone Skin", "주황"},
	{"monsteruniqueprop7", "다발 사격", "Multiple Shots", ""},
	{"uniquefireenchanted", "화염 강화", "Fire Enchanted", ""},
	{"monsteruniqueprop1", "냉기 강화", "Cold Enchanted", ""},
	{"monsteruniqueprop3", "마나 연소", "Mana Burn", ""},
	{"monsteruniqueprop4", "혼령 공격", "Spectral Hit", ""},
	{"monsteruniqueprop9", "오라 강화", "Aura Enchanted", ""},
	{"uniquextrastrong", "매우 강함", "Extra Strong", ""},
	{"uniqueextrafast", "매우 빠름", "Extra Fast", ""},
	{"uniquemagicresistance", "마법 저항", "Magic Resistant", ""},
	{"monsteruniqueprop8", "도둑", "Thief", ""},
}

type monPlan struct{ key, color, ko, en string }

func monsterPlan(params map[string]string) []monPlan {
	mk, ok := markers[strings.TrimSpace(params["mm_marker"])]
	if !ok {
		mk = markers["★"]
	}
	var plan []monPlan
	for _, m := range monsterMods {
		choice := m.def
		if v, ok := params["mm_color::"+m.key]; ok {
			choice = strings.TrimSpace(v)
		}
		if c, ok := colorCodes[choice]; ok {
			plan = append(plan, monPlan{m.key, c, mk[0] + m.ko + mk[1], mk[0] + m.en + mk[1]})
		}
	}
	return plan
}

func splitBOM(data []byte) (string, bool) {
	if len(data) >= 3 && data[0] == 0xEF && data[1] == 0xBB && data[2] == 0xBF {
		return string(data[3:]), true
	}
	return string(data), false
}

func joinBOM(s string, bom bool) []byte {
	if bom {
		return append([]byte{0xEF, 0xBB, 0xBF}, s...)
	}
	return []byte(s)
}

func patchMonsterMods(data []byte, plan []monPlan) ([]byte, int) {
	txt, bom := splitBOM(data)
	changed := 0
	for _, p := range plan {
		re := regexp.MustCompile(`\{[^{}]*"Key"\s*:\s*"` + regexp.QuoteMeta(p.key) + `"[^{}]*\}`)
		loc := re.FindStringIndex(txt)
		if loc == nil {
			continue
		}
		block := txt[loc[0]:loc[1]]
		nb := block
		for _, lv := range [][2]string{{"koKR", p.ko}, {"enUS", p.en}} {
			lre := regexp.MustCompile(`("` + lv[0] + `"\s*:\s*)"(?:[^"\\]|\\.)*"`)
			if m := lre.FindStringSubmatchIndex(nb); m != nil {
				nb = nb[:m[0]] + nb[m[2]:m[3]] + `"` + p.color + lv[1] + resetColor + `"` + nb[m[1]:]
			}
		}
		if nb != block {
			txt = txt[:loc[0]] + nb + txt[loc[1]:]
			changed++
		}
	}
	return joinBOM(txt, bom), changed
}

// plainMonsterMods — 강조하지 않는 특성은 색 코드를 지워 흰 글자로 (기준 스킨이 색을 넣어 둔 경우)
var colorCodeRe = regexp.MustCompile(`ÿc.`)

func plainMonsterMods(data []byte, params map[string]string) []byte {
	txt, bom := splitBOM(data)
	for _, m := range monsterMods {
		choice := m.def
		if v, ok := params["mm_color::"+m.key]; ok {
			choice = strings.TrimSpace(v)
		}
		if _, ok := colorCodes[choice]; ok {
			continue
		}
		re := regexp.MustCompile(`\{[^{}]*"Key"\s*:\s*"` + regexp.QuoteMeta(m.key) + `"[^{}]*\}`)
		loc := re.FindStringIndex(txt)
		if loc == nil {
			continue
		}
		block := txt[loc[0]:loc[1]]
		nb := block
		for _, lang := range []string{"koKR", "enUS"} {
			lre := regexp.MustCompile(`("` + lang + `"\s*:\s*")((?:[^"\\]|\\.)*)"`)
			if sm := lre.FindStringSubmatchIndex(nb); sm != nil {
				nb = nb[:sm[4]] + colorCodeRe.ReplaceAllString(nb[sm[4]:sm[5]], "") + nb[sm[5]:]
			}
		}
		txt = txt[:loc[0]] + nb + txt[loc[1]:]
	}
	return joinBOM(txt, bom)
}

// addResNewline — 몬스터 저항 줄(res_ 로 시작하는 글자) 끝에 줄바꿈을 붙여 특성이 다음 줄에 나오게
var resBlockRe = regexp.MustCompile(`\{[^{}]*"Key"\s*:\s*"res_[^"]*"[^{}]*\}`)
var koValRe = regexp.MustCompile(`("koKR"\s*:\s*")((?:[^"\\]|\\.)*)"`)

func addResNewline(data []byte) []byte {
	txt, bom := splitBOM(data)
	txt = resBlockRe.ReplaceAllStringFunc(txt, func(b string) string {
		m := koValRe.FindStringSubmatchIndex(b)
		if m == nil || strings.HasSuffix(b[m[4]:m[5]], `\n`) {
			return b
		}
		return b[:m[5]] + `\n` + b[m[5]:]
	})
	return joinBOM(txt, bom)
}

var fontSizes = map[string]string{"크게": "$LargeFontSize", "아주 크게": "$ExtraLargeFontSize", "최대": "$HugeFontSize"}

var glowColors = map[string][3]string{"노랑": {"255", "255", "64"}, "파랑": {"100", "160", "255"}, "빨강": {"255", "60", "60"},
	"초록": {"90", "255", "90"}, "보라": {"200", "90", "255"}, "흰색": {"255", "255", "255"}}

// 몸 색 물들이기 (states.txt colorshift 값: 얼어붙음이 파랗게 보이는 것과 같은 방식)
var glowTints = map[string]string{"빨강 몸": "100", "파랑 몸": "108", "초록 몸": "104", "보라 몸": "130"}

func patchGlow(data []byte, color, tint string) ([]byte, int) {
	txt := string(data)
	nl := "\n"
	if strings.Contains(txt, "\r\n") {
		nl = "\r\n"
	}
	lines := strings.Split(txt, nl)
	hdr := strings.Split(lines[0], "\t")
	col := map[string]int{}
	for i, h := range hdr {
		if _, ok := col[h]; !ok {
			col[h] = i
		}
	}
	rgb, ok := glowColors[color]
	if !ok {
		rgb = glowColors["노랑"]
	}
	set := [][2]string{{"light-r", rgb[0]}, {"light-g", rgb[1]}, {"light-b", rgb[2]}, {"overlay1", "lightningenchant"}}
	if t, ok := glowTints[tint]; ok {
		set = append(set, [2]string{"colorpri", "60"}, [2]string{"colorshift", t})
	}
	n := 0
	for i := 1; i < len(lines); i++ {
		cols := strings.Split(lines[i], "\t")
		if len(cols) > 0 && cols[0] == "lightningenchant" {
			for len(cols) < len(hdr) {
				cols = append(cols, "")
			}
			for _, kv := range set {
				if c, ok := col[kv[0]]; ok {
					cols[c] = kv[1]
				}
			}
			lines[i] = strings.Join(cols, "\t")
			n++
		}
	}
	return []byte(strings.Join(lines, nl)), n
}

// ---------------------------------------------------------------- 고른 기능 → 순서

type pick struct {
	f   *Feature
	opt *Option
}

// selectedPicks — 고른 기능 (필요한 기능 자동 포함), 팩 순서대로
func selectedPicks(choices map[string]string) ([]pick, []string) {
	want := map[string]string{}
	for id, k := range choices {
		if k != "" && featByID[id] != nil {
			want[id] = k
		}
	}
	var auto []string
	for changed := true; changed; {
		changed = false
		for id := range want {
			for _, r := range featByID[id].Requires {
				if _, ok := want[r]; !ok && featByID[r] != nil {
					want[r] = featByID[r].Options[0].Key
					auto = append(auto, featByID[r].Name)
					changed = true
				}
			}
		}
	}
	var out []pick
	for i := range pack.Features {
		f := &pack.Features[i]
		k, ok := want[f.ID]
		if !ok {
			continue
		}
		for j := range f.Options {
			if f.Options[j].Key == k {
				out = append(out, pick{f, &f.Options[j]})
			}
		}
	}
	return out, auto
}

// runPicks — 파일 연산(넣기·빼기)을 먼저 모두 하고, 그다음 패치
func (w *workspace) runPicks(picks []pick, results map[string]*opResult) error {
	for pass := 0; pass < 2; pass++ {
		for _, p := range picks {
			res := results[p.f.ID]
			if res == nil {
				res = &opResult{}
				results[p.f.ID] = res
			}
			for _, op := range p.opt.Ops {
				isFile := op.T == "f" || op.T == "n" || op.T == "d"
				if isFile != (pass == 0) {
					continue
				}
				if err := w.apply(op, p.f.Name, res); err != nil {
					return fmt.Errorf("%s: %v", p.f.Name, err)
				}
			}
		}
	}
	return nil
}

// ---------------------------------------------------------------- 기준 스킨과 비교 (기능마다 따로)

type optStatus struct {
	State    string   `json:"state"` // same new replace patch cant
	Add      int      `json:"add"`
	Same     int      `json:"same"`
	Replace  int      `json:"replace"`
	Remove   int      `json:"remove"`
	Replaced []string `json:"replaced"`
	Notes    []string `json:"notes"`
}

func statusOf(b *baseSkin, f *Feature, o *Option, params map[string]string) optStatus {
	w := newWorkspace(b, params, func(string) {})
	res := &opResult{}
	w.runPicks([]pick{{f, o}}, map[string]*opResult{f.ID: res})
	st := optStatus{Add: res.Add, Same: res.Same, Replace: res.Replace, Remove: res.Remove, Replaced: res.Replaced, Notes: res.Notes}
	if len(st.Replaced) > 6 {
		st.Replaced = st.Replaced[:6]
	}
	files := res.Add + res.Same + res.Replace + res.Remove
	patches := res.Done + res.Already + res.Missing
	switch {
	case patches > 0 && res.Done == 0 && res.Already == 0 && res.Add+res.Replace == 0:
		st.State = "cant"
	case res.Add+res.Replace+res.Remove+res.Done == 0 && (files+patches) > 0:
		st.State = "same"
	case res.Replace > 0 || res.Remove > 0:
		st.State = "replace"
	case res.Done > 0:
		st.State = "patch"
	default:
		st.State = "new"
	}
	return st
}

// ---------------------------------------------------------------- mpq 만들기

func buildMPQ(b *baseSkin, choices, params map[string]string, outPath string, log func(string), progress func(int, string)) (map[string]*opResult, error) {
	picks, auto := selectedPicks(choices)
	for _, a := range auto {
		log("함께 필요한 기능 자동 포함: " + a)
	}
	w := newWorkspace(b, params, log)
	results := map[string]*opResult{}
	progress(2, "기능 덧붙이는 중")
	if err := w.runPicks(picks, results); err != nil {
		return nil, err
	}
	for _, p := range picks {
		r := results[p.f.ID]
		log(fmt.Sprintf("· %s [%s]: 새 파일 %d, 바꾼 파일 %d, 같은 파일 %d, 고친 곳 %d, 이미 있음 %d", p.f.Name, p.opt.Label, r.Add, r.Replace, r.Same, r.Done, r.Already))
		for _, n := range r.Notes {
			log("    " + n)
		}
	}
	// 모드 이름을 skinbuilder 로 (게임은 -mod skinbuilder 로 켬. 기준 스킨 폴더와 따로 저장됨)
	if raw, err := w.get("modinfo.json"); err == nil {
		mi := map[string]any{}
		json.Unmarshal(bytes.TrimPrefix(raw, []byte("\xef\xbb\xbf")), &mi)
		mi["name"] = ModName
		if _, ok := mi["savepath"]; !ok {
			mi["savepath"] = "../"
		}
		mb, _ := json.MarshalIndent(mi, "", "  ")
		w.set("modinfo.json", mb, "")
	}
	// 표시 파일 (스킨빌더가 만든 mpq 라는 표시)
	info := map[string]any{"base": b.path, "mod": b.modName, "features": choices, "params": params, "version": appVersion()}
	ib, _ := json.MarshalIndent(info, "", "  ")
	w.set("skinbuilder.json", ib, "")
	// 배포용: 스킨 빌더 업그레이드(패치)를 mpq 안에 같이 넣음 → 이 mpq 를 프로그램에 넣으면 프로그램이 새 버전이 됨
	if pp := os.Getenv("SB_EMBED_PATCH"); pp != "" {
		if data, err := os.ReadFile(pp); err == nil {
			w.set(updateEntry, data, "")
			log("스킨 빌더 업그레이드 정보를 넣었어요")
		}
	}

	keys := make([]string, 0, len(w.final))
	for k := range w.final {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	files := make([]mpqFile, len(keys))
	// 고친 파일은 여러 개를 동시에 압축
	var wg sync.WaitGroup
	sem := make(chan struct{}, 4)
	var firstErr error
	var emu sync.Mutex
	for i, k := range keys {
		e := w.final[k]
		files[i].name = e.name
		switch e.from {
		case 0:
			if data, flags, fsize, ok := b.rawBlock(k); ok {
				files[i].data, files[i].flags, files[i].usize = data, flags, int(fsize)
				continue
			}
			wg.Add(1)
			go func(i int, k string) {
				defer wg.Done()
				sem <- struct{}{}
				defer func() { <-sem }()
				d, err := b.r.read(b.names[k])
				if err != nil {
					emu.Lock()
					if firstErr == nil {
						firstErr = fmt.Errorf("기준 스킨 파일을 읽지 못함: %s (%v)", b.names[k], err)
					}
					emu.Unlock()
					return
				}
				files[i].data, files[i].usize = sectorizeData(d), len(d)
			}(i, k)
		case 1:
			files[i].data, files[i].usize = blobData(e.blob), int(pack.Blobs[e.blob][2])
		case 2:
			wg.Add(1)
			go func(i int, d []byte) {
				defer wg.Done()
				sem <- struct{}{}
				defer func() { <-sem }()
				files[i].data, files[i].usize = sectorizeData(d), len(d)
			}(i, e.data)
		}
	}
	progress(10, "압축하는 중")
	wg.Wait()
	if firstErr != nil {
		return nil, firstErr
	}
	progress(20, "mpq 파일 쓰는 중")
	tmp := outPath + ".만드는중"
	err := writeMPQ(tmp, files, sectorSize, func(d, t int) { progress(20+75*d/t, "mpq 파일 쓰는 중") })
	if err != nil {
		return nil, err
	}
	// 다시 열어서 확인
	progress(96, "만든 파일 확인하는 중")
	if err := verifyMPQ(tmp, len(files)); err != nil {
		return nil, err
	}
	if err := replaceFile(tmp, outPath); err != nil {
		return nil, err
	}
	log(fmt.Sprintf("완성: %s (파일 %d개)", outPath, len(files)))
	return results, nil
}

func verifyMPQ(path string, want int) error {
	r, err := openMPQ(path)
	if err != nil {
		return fmt.Errorf("만든 파일을 다시 열 수 없어요: %v", err)
	}
	defer r.Close()
	if len(r.names) < want {
		return fmt.Errorf("만든 파일의 목록 수가 달라요 (%d / %d)", len(r.names), want)
	}
	for _, n := range []string{"modinfo.json", "skinbuilder.json"} {
		if _, err := r.read(n); err != nil {
			return fmt.Errorf("만든 파일 확인 실패: %s (%v)", n, err)
		}
	}
	return nil
}

var _ = utf8.RuneLen
