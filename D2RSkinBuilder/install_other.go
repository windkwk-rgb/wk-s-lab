//go:build !windows

package main

// 설치·제거는 윈도우에서만
func runSetup(exe string) bool     { return false }
func runUninstall(exe string) bool { return false }
