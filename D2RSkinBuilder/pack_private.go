//go:build private

package main

// 개인용 실제 기능 묶음 (private/ — 저장소에 올리지 않음)

import "embed"

//go:embed private/features.json
var packJSON []byte

//go:embed private/img
var packFS embed.FS

const packDir = "private"
