//go:build !slim && private

package main

import _ "embed"

//go:embed private/blobs.bin
var blobsBin []byte

const slimBuild = false
