//go:build slim

package main

// 가벼운 판(패치용 program.exe): 기능 데이터는 처음 전체판이 꺼내 둔 스킨빌더_데이터.bin 을 읽음
var blobsBin []byte

const slimBuild = true
