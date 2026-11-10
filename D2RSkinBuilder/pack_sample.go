//go:build !private

package main

// 공개 저장소용 예제 기능 묶음 (pack/sample). 다른 제작자의 스킨 파일은 들어 있지 않아요.
// 실제 기능 묶음으로 만들려면 private/ 에 넣고 -tags private 로 빌드 (docs/ARCHITECTURE.md)

import "embed"

//go:embed pack/sample/features.json
var packJSON []byte

//go:embed pack/sample/img
var packFS embed.FS

const packDir = "pack/sample"
