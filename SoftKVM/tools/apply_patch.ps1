param([string]$Zip, [string]$Target)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Fail($msg) { Write-Host ""; Write-Host "[실패] $msg" -ForegroundColor Red; exit 1 }

$Target = (Resolve-Path -LiteralPath $Target).Path.TrimEnd('\')
if (-not (Test-Path -LiteralPath $Zip)) { Fail "패치 파일이 없습니다: $Zip" }

$tmp = Join-Path $env:TEMP ("kvm_patch_" + [guid]::NewGuid().ToString('N'))
try {
    [IO.Compression.ZipFile]::ExtractToDirectory($Zip, $tmp, [Text.Encoding]::UTF8)
} catch { Fail "zip을 열 수 없습니다. 파일이 손상됐을 수 있어요. ($($_.Exception.Message))" }

try {
    # zip 안에 폴더가 한 겹 더 있어도(kvm\, KVM-patch\ 등) 알아서 안으로 들어감
    $known = @('windows', 'mac', 'android', 'tools', 'VERSION.txt', 'README.md', '업데이트.bat', '서버실행.bat', 'exe만들기.bat')
    $root = $tmp
    for ($i = 0; $i -lt 3; $i++) {
        $items = @(Get-ChildItem -LiteralPath $root -Force)
        if ($items.Count -eq 1 -and $items[0].PSIsContainer -and ($known -notcontains $items[0].Name)) { $root = $items[0].FullName } else { break }
    }
    $hit = @(Get-ChildItem -LiteralPath $root -Force | Where-Object { $known -contains $_.Name })
    if ($hit.Count -eq 0) { Fail "KVM 패치 파일이 아닌 것 같습니다 (windows / mac / android 폴더가 없음)." }

    $oldVer = if (Test-Path -LiteralPath "$Target\VERSION.txt") { (Get-Content -LiteralPath "$Target\VERSION.txt" -Raw).Trim() } else { '?' }
    $newVer = if (Test-Path -LiteralPath "$root\VERSION.txt") { (Get-Content -LiteralPath "$root\VERSION.txt" -Raw).Trim() } else { $oldVer }
    Write-Host "버전: $oldVer  ->  $newVer"
    Write-Host ""

    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $backup = Join-Path $Target "backup\$stamp"
    $changed = New-Object System.Collections.Generic.List[string]

    Get-ChildItem -LiteralPath $root -Recurse -File -Force | ForEach-Object {
        $rel = $_.FullName.Substring($root.Length).TrimStart('\')
        if ($_.Name -eq 'config.json' -or $rel -like 'backup\*') { return }      # 사용자 설정은 절대 덮어쓰지 않음
        $dest = Join-Path $Target $rel
        if (Test-Path -LiteralPath $dest) {
            if ((Get-FileHash -LiteralPath $dest).Hash -eq (Get-FileHash -LiteralPath $_.FullName).Hash) { return }
            $b = Join-Path $backup $rel
            New-Item -ItemType Directory -Force -Path (Split-Path $b) | Out-Null
            Copy-Item -LiteralPath $dest -Destination $b -Force
        }
        New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
        Copy-Item -LiteralPath $_.FullName -Destination $dest -Force
        $changed.Add($rel)
        Write-Host "  갱신  $rel"
    }

    Write-Host ""
    if ($changed.Count -eq 0) { Write-Host "이미 최신 상태입니다. 바뀐 파일이 없어요." -ForegroundColor Green; exit 0 }
    Write-Host "완료: $($changed.Count)개 파일을 업데이트했습니다." -ForegroundColor Green
    if (Test-Path -LiteralPath $backup) { Write-Host "이전 파일 백업: backup\$stamp" }

    if (($changed | Where-Object { $_ -like 'windows\*' }) -and (Test-Path -LiteralPath "$Target\KVM-Server.exe")) {
        Write-Host ""
        Write-Host "* 서버 코드가 바뀌었습니다. 'exe만들기.bat'을 다시 실행해야 KVM-Server.exe에 반영됩니다." -ForegroundColor Yellow
    }
    if ($changed | Where-Object { $_ -like 'mac\*' }) {
        Write-Host "* 맥 코드가 바뀌었습니다. mac 폴더의 client.py를 맥에도 복사해 주세요." -ForegroundColor Yellow
    }
    if ($changed | Where-Object { $_ -like 'android\*' }) {
        Write-Host "* 안드로이드 앱이 바뀌었습니다. apk를 다시 빌드해서 태블릿에 설치해야 합니다." -ForegroundColor Yellow
    }
} finally {
    Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
}
