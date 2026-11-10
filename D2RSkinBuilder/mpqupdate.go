package main

// 최신 mpq 로 업그레이드 — 배포한 skinbuilder.mpq 안에 업그레이드(패치)와 그 mpq 를 만든 설정이 들어 있음.
// 프로그램에 그 mpq 를 넣거나(패치 넣기 버튼), 게임 폴더에 설치해 두면 프로그램을 켤 때 스스로 새 버전이 됨.

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

const updateEntry = "skinbuilder_update.sbpatch"

type mpqUpdate struct {
	path     string
	patch    []byte
	manifest *patchManifest
	choices  map[string]string
	params   map[string]string
}

// peekMPQUpdate — mpq 안의 업그레이드 정보(없으면 오류)
func peekMPQUpdate(path string) (*mpqUpdate, error) {
	r, err := openMPQ(path)
	if err != nil {
		return nil, fmt.Errorf("mpq 파일을 열지 못했어요: %v", err)
	}
	defer r.Close()
	data, err := r.read(updateEntry)
	if err != nil || len(data) == 0 {
		return nil, errors.New("이 mpq 에는 스킨 빌더 업그레이드 정보가 없어요 (제가 보내 드린 최신 mpq 를 골라 주세요)")
	}
	zr, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		return nil, fmt.Errorf("업그레이드 정보가 망가졌어요: %v", err)
	}
	m, err := readManifest(zr)
	if err != nil {
		return nil, err
	}
	u := &mpqUpdate{path: path, patch: data, manifest: m}
	if raw, err := r.read("skinbuilder.json"); err == nil {
		var info struct {
			Features map[string]string `json:"features"`
			Params   map[string]string `json:"params"`
		}
		if json.Unmarshal(bytes.TrimPrefix(raw, []byte("\xef\xbb\xbf")), &info) == nil {
			u.choices, u.params = info.Features, info.Params
		}
	}
	return u, nil
}

// applyMPQUpdate — 업그레이드를 넣고, 그 mpq 를 만든 설정을 지금 설정으로 (전 설정은 이름 붙여 보관)
func applyMPQUpdate(u *mpqUpdate, exe string) (restart bool, err error) {
	tmp := filepath.Join(dataDir, ".mpq_업그레이드.sbpatch")
	if err := os.WriteFile(tmp, u.patch, 0644); err != nil {
		return false, err
	}
	defer os.Remove(tmp)
	restart, _, err = applyPatchFile(tmp, exe)
	if err != nil {
		return false, err
	}
	if len(u.choices) > 0 {
		if settings.Profiles == nil {
			settings.Profiles = map[string]Profile{}
		}
		now := time.Now().Format("2006-01-02 15:04")
		if len(settings.Choices) > 0 {
			settings.Profiles["업그레이드 전 설정 "+now] = Profile{copyMap(settings.Choices), copyMap(settings.Params), now}
		}
		settings.Choices, settings.Params = copyMap(u.choices), copyMap(u.params)
		if settings.Params == nil {
			settings.Params = map[string]string{}
		}
		settings.Profiles["최신 mpq 설정 "+u.manifest.Version] = Profile{copyMap(settings.Choices), copyMap(settings.Params), now}
		saveSettings()
	}
	patchLog("mpq 로 %s 업그레이드 (%s)", u.manifest.Version, filepath.Base(u.path))
	return restart, nil
}

// autoUpgradeFromMPQ — 켤 때: 게임에 설치된 skinbuilder.mpq 나 프로그램 옆 mpq 에 더 새 업그레이드가 있으면 넣고 다시 켬
func autoUpgradeFromMPQ(exe string) bool {
	var cands []string
	if settings.GameDir != "" {
		cands = append(cands, filepath.Join(settings.GameDir, "mods", ModName, ModName+".mpq"))
	}
	if m, _ := filepath.Glob(filepath.Join(exeDir, "*.mpq")); len(m) > 0 {
		cands = append(cands, m...)
	}
	skipFile := filepath.Join(dataDir, ".mpq_업그레이드_실패.txt")
	skip, _ := os.ReadFile(skipFile)
	var best *mpqUpdate
	for _, c := range cands {
		if st, err := os.Stat(c); err != nil || st.IsDir() {
			continue
		}
		u, err := peekMPQUpdate(c)
		if err != nil || verCmp(u.manifest.Version, appVersion()) <= 0 {
			continue
		}
		if strings.TrimSpace(string(skip)) == u.manifest.Version {
			continue // 전에 실패한 버전은 다시 시도하지 않음 (패치 넣기 버튼으로는 가능)
		}
		if best == nil || verCmp(u.manifest.Version, best.manifest.Version) > 0 {
			best = u
		}
	}
	if best == nil {
		return false
	}
	restart, err := applyMPQUpdate(best, exe)
	if err != nil {
		os.WriteFile(skipFile, []byte(best.manifest.Version), 0644)
		patchLog("mpq 업그레이드 %s 실패: %v", best.manifest.Version, err)
		return false
	}
	_ = restart
	exec.Command(exe, "--after-patch").Start()
	return true
}

// applyDefaultPreset — 처음 켤 때(고른 기능이 하나도 없을 때) 추천 설정으로 시작
func applyDefaultPreset() {
	if len(settings.Choices) > 0 || len(pack.Presets) == 0 {
		return
	}
	pr := pack.Presets[0]
	settings.Choices = copyMap(pr.Choices)
	for k, v := range pr.Params {
		settings.Params[k] = v
	}
	fillParams(&settings)
	saveSettings()
}
