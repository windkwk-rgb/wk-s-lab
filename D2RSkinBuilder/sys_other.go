//go:build !windows

package main

// 윈도우가 아닐 때(개발·시험용): 창 대신 주소만 알려 주고, 고르기는 환경 변수로

import (
	"fmt"
	"os"
)

func openWindow(url string)                        { fmt.Println("열기:", url) }
func openFolder(dir string)                        { fmt.Println("폴더:", dir) }
func showError(msg string)                         { fmt.Fprintln(os.Stderr, msg) }
func findGameDir() string                          { return os.Getenv("SKINBUILDER_GAME") }
func pickFolder() (string, error)                  { return os.Getenv("SKINBUILDER_GAME"), nil }
func pickFile(initDir string) (string, error)      { return os.Getenv("SKINBUILDER_PICK"), nil }
func pickPatchFile(initDir string) (string, error) { return os.Getenv("SKINBUILDER_PATCH"), nil }
