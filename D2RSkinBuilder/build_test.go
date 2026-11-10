package main

// 자동 빌드에서 돌리는 시험: 작은 기준 스킨 mpq 를 직접 만들고, 예제 기능을 얹어 새 mpq 를 만든 뒤 다시 읽어 확인

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const testMonsters = "\ufeff[\r\n  {\r\n    \"id\": 3200,\r\n    \"Key\": \"monsteruniqueprop2\",\r\n    \"enUS\": \"Lightning Enchanted\",\r\n    \"koKR\": \"번개 강화\"\r\n  },\r\n  {\r\n    \"id\": 3209,\r\n    \"Key\": \"uniquefireenchanted\",\r\n    \"enUS\": \"Fire Enchanted\",\r\n    \"koKR\": \"ÿc1화염 강화ÿc5\"\r\n  },\r\n  {\r\n    \"id\": 60018,\r\n    \"Key\": \"res_skeleton1\",\r\n    \"enUS\": \"\",\r\n    \"koKR\": \"ÿc10ÿc5\"\r\n  }\r\n]\r\n"

func writeTestBase(t *testing.T, dir string) string {
	files := map[string]string{
		"modinfo.json":                                   `{"name":"testskin","savepath":"../"}`,
		`data\local\lng\strings\monsters.json`:           testMonsters,
		`data\global\ui\layouts\hudmonsterhealthhd.json`: "{\n  \"children\": [\n    { \"type\": \"TextBoxWidget\", \"name\": \"Additional\", \"fields\": { \"rect\": { \"x\": 500, \"y\": 150 } } }\n  ]\n}\n",
	}
	var list []mpqFile
	for n, c := range files {
		list = append(list, mpqFile{name: n, data: sectorizeData([]byte(c)), usize: len(c)})
	}
	p := filepath.Join(dir, "testskin.mpq")
	if err := writeMPQ(p, list, sectorSize, nil); err != nil {
		t.Fatal(err)
	}
	return p
}

func TestBuildWithSamplePack(t *testing.T) {
	if err := loadPack(packJSON); err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	dataDir = dir
	b, err := openBase(writeTestBase(t, dir))
	if err != nil {
		t.Fatal(err)
	}
	defer b.close()
	s := Settings{Choices: map[string]string{"monster_mods": "on"}}
	fillParams(&s)
	out := filepath.Join(dir, "out.mpq")
	if _, err := buildMPQ(b, s.Choices, s.Params, out, func(string) {}, func(int, string) {}); err != nil {
		t.Fatal(err)
	}
	r, err := openMPQ(out)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	mon, err := r.read(`data\local\lng\strings\monsters.json`)
	if err != nil {
		t.Fatal(err)
	}
	txt := string(mon)
	for _, want := range []string{"ÿc9★번개 강화★ÿc5", `"koKR": "화염 강화"`, `ÿc10ÿc5\n"`} {
		if !strings.Contains(txt, want) {
			t.Errorf("monsters.json 에 %q 가 없어요:\n%s", want, txt)
		}
	}
	hud, _ := r.read(`data\global\ui\layouts\hudmonsterhealthhd.json`)
	if !strings.Contains(string(hud), `"y": 240`) {
		t.Errorf("면역 줄이 내려가지 않았어요: %s", hud)
	}
	mi, _ := r.read("modinfo.json")
	if !strings.Contains(string(mi), `"name": "skinbuilder"`) {
		t.Errorf("모드 이름이 바뀌지 않았어요: %s", mi)
	}
	os.Remove(out)
}

func TestSetupPayload(t *testing.T) {
	dir := t.TempDir()
	exe := filepath.Join(dir, "prog.exe")
	os.WriteFile(exe, []byte("MZ-프로그램"), 0644)
	skin := filepath.Join(dir, "skin_a.mpq")
	os.WriteFile(skin, []byte("스킨 내용"), 0644)
	setup := filepath.Join(dir, "x_setup.exe")
	if err := makeSetup(exe, setup, []string{skin}); err != nil {
		t.Fatal(err)
	}
	idx := readPayload(setup)
	if idx == nil || idx.ExeSize != int64(len("MZ-프로그램")) || len(idx.Files) != 1 {
		t.Fatalf("목록이 이상해요: %+v", idx)
	}
	inst := filepath.Join(dir, "inst")
	if n, err := extractPayload(setup, idx, inst); err != nil || n != 1 {
		t.Fatal(n, err)
	}
	got, _ := os.ReadFile(filepath.Join(bundledDir(inst), "skin_a.mpq"))
	if string(got) != "스킨 내용" {
		t.Errorf("풀린 내용이 달라요: %q", got)
	}
	if readPayload(exe) != nil {
		t.Error("붙은 파일이 없는 exe 를 설치 파일로 봤어요")
	}
}
