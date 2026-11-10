//go:build !slim && !private

package main

import _ "embed"

// 전체판: 기능 데이터(스킨 파일 묶음)를 프로그램 안에 넣음
//
//go:embed pack/sample/blobs.bin
var blobsBin []byte

const slimBuild = false
