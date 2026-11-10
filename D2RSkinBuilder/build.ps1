# D2R 스킨 빌더 빌드 (Windows PowerShell)
#   private\features.json 이 있으면 실제 기능 묶음으로, 없으면 예제 기능 묶음(pack\sample)으로 만들어요.
#   결과: dist\D2R스킨빌더.exe  (가벼운 판: dist\D2R스킨빌더_slim.exe — 패치용 program.exe)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not (Get-Command go -ErrorAction SilentlyContinue)) {
    Write-Host 'Go 가 없어서 설치해요 (winget)...'
    winget install -e --id GoLang.Go --accept-source-agreements --accept-package-agreements
    $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + [Environment]::GetEnvironmentVariable('Path', 'User')
}
$tags = ''
if (Test-Path 'private\features.json') { $tags = 'private'; Write-Host '실제 기능 묶음(private)으로 만들어요' } else { Write-Host '예제 기능 묶음(pack\sample)으로 만들어요' }
New-Item -ItemType Directory -Force dist | Out-Null
$env:GOOS = 'windows'; $env:GOARCH = 'amd64'
go build -trimpath -tags "$tags" -ldflags '-H windowsgui -s -w' -o 'dist\D2R스킨빌더.exe' .
go build -trimpath -tags "$tags,slim" -ldflags '-H windowsgui -s -w' -o 'dist\D2R스킨빌더_slim.exe' .
Write-Host '완성: dist\D2R스킨빌더.exe'
