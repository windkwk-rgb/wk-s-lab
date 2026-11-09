# PenBridge 자동 빌드 스크립트 (Windows)
# 필요한 도구(.NET SDK, JDK 17, Android SDK)가 없으면 자동으로 설치하고
# PenBridge.apk(태블릿용)와 PenBridge.exe(노트북용)를 만든다.
# PenBridge.exe 하나에 태블릿 앱과 설치 기능이 모두 들어 있다 (다른 PC에서는 이 파일만 실행하면 됨).
#
#   -OutDir  결과 폴더 (기본: 이 폴더 옆의 PenBridge-output)
#   -NoPause 끝나도 Enter를 기다리지 않음 (노트북 앱의 자동 업데이트가 사용)
# 기록: 이 폴더 옆의 PenBridge-build-log.txt

param(
    [string]$OutDir = '',
    [switch]$NoPause
)

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'   # 다운로드 속도 향상
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Src  = Split-Path -Parent $MyInvocation.MyCommand.Path      # ...\PenBridge
$Root = Split-Path -Parent $Src
$Out  = if ($OutDir) { $OutDir } else { Join-Path $Root 'PenBridge-output' }
$Log  = Join-Path $Root 'PenBridge-build-log.txt'

# 노트북 앱이 자동 업데이트로 실행했다면, 그 앱이 있는 폴더의 상위에도 기록을 남김 (확인하기 쉽게)
$ExtraLog = $null
try {
    $ppid = (Get-CimInstance Win32_Process -Filter "ProcessId=$PID").ParentProcessId
    $pexe = (Get-CimInstance Win32_Process -Filter "ProcessId=$ppid").ExecutablePath
    if ($pexe -and ((Split-Path $pexe -Leaf) -ieq 'PenBridge.exe')) {
        $ExtraLog = Join-Path (Split-Path (Split-Path $pexe -Parent) -Parent) 'PenBridge-build-log.txt'
    }
} catch { }
function Copy-Log { if ($ExtraLog) { Copy-Item $Log $ExtraLog -Force -ErrorAction SilentlyContinue } }
$Version = (Get-Content (Join-Path $Src 'version.txt') -Raw).Trim()

Set-Content -Path $Log -Value "PenBridge $Version build $(Get-Date -Format s)  src=$Src  out=$Out" -Encoding UTF8

function Say($msg, $color = 'Cyan') {
    Write-Host ""
    Write-Host "== $msg" -ForegroundColor $color
    Add-Content -Path $Log -Value "== $msg" -Encoding UTF8
}

# 출력 한 줄 기록 (stderr 줄은 PowerShell이 오류 개체로 감싸므로 원래 글자만 꺼냄)
function Log-Line($item) {
    if ($item -is [System.Management.Automation.ErrorRecord]) {
        $line = if ($null -ne $item.TargetObject) { "$($item.TargetObject)" } else { '' }
    } else {
        $line = "$item"
    }
    Write-Host $line
    Add-Content -Path $Log -Value $line -Encoding UTF8
}

# 외부 프로그램 실행 + 로그 기록. 성공 여부 반환
function Run($exe, [string[]]$argList) {
    Add-Content -Path $Log -Value "> $exe $($argList -join ' ')" -Encoding UTF8
    & $exe @argList 2>&1 | ForEach-Object { Log-Line $_ }
    $code = $LASTEXITCODE
    Add-Content -Path $Log -Value "(exit $code)" -Encoding UTF8
    return ($code -eq 0)
}

function Fail($msg) {
    Say "실패: $msg" 'Red'
    Write-Host ""
    Write-Host "기록 파일: $Log" -ForegroundColor Yellow
    Write-Host "Claude에게 '빌드 실패했어'라고 말해 주세요." -ForegroundColor Yellow
    Copy-Log
    if (-not $NoPause) { Read-Host "Enter를 누르면 닫혀요" }
    exit 1
}

function Refresh-Path {
    $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + [Environment]::GetEnvironmentVariable('Path', 'User')
}

function Winget-Install($id) {
    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
        Fail "winget이 없어요. Microsoft Store에서 '앱 설치 관리자'를 설치/업데이트해 주세요."
    }
    Run 'winget' @('install', '--id', $id, '-e', '--silent', '--accept-source-agreements', '--accept-package-agreements') | Out-Null
    Refresh-Path
}

# 결과 파일 복사. 대상이 실행 중이라 잠겨 있으면 이름을 바꿔 두고 복사
# (실행 중인 PenBridge가 새 파일을 발견해서 스스로 업데이트함)
function Put-File($from, $to) {
    if (Test-Path $to) {
        try {
            Remove-Item $to -Force -ErrorAction Stop
        } catch {
            $old = "$to.$(Get-Random).old"
            Rename-Item $to (Split-Path $old -Leaf) -Force
        }
    }
    Copy-Item $from $to -Force
}

New-Item -ItemType Directory -Force -Path $Out | Out-Null
Say "PenBridge v$Version 빌드 시작"

# ───────────────────────── 1. 태블릿 앱 (APK) ─────────────────────────
Say "1/2  태블릿 앱(PenBridge.apk) 빌드"

# JDK 17 이상 찾기: JAVA_HOME → Android Studio 내장 → Microsoft OpenJDK → 설치
function Find-Jdk {
    $c = @()
    if ($env:JAVA_HOME) { $c += $env:JAVA_HOME }
    $c += 'C:\Program Files\Android\Android Studio\jbr'
    $c += Get-ChildItem 'C:\Program Files\Microsoft' -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue | Sort-Object Name -Descending | ForEach-Object FullName
    $c += Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue | Sort-Object Name -Descending | ForEach-Object FullName
    foreach ($d in $c) {
        $java = Join-Path $d 'bin\java.exe'
        if (-not (Test-Path $java)) { continue }
        $v = (& $java -version 2>&1 | Select-Object -First 1) -as [string]
        if ($v -match 'version "(\d+)') { if ([int]$Matches[1] -ge 17) { return $d } }
    }
    return $null
}
$jdk = Find-Jdk
if (-not $jdk) {
    Say "JDK 17 설치 중"
    Winget-Install 'Microsoft.OpenJDK.17'
    $jdk = Find-Jdk
    if (-not $jdk) { Fail "JDK 설치 후에도 찾지 못했어요" }
}
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"
Add-Content -Path $Log -Value "JAVA_HOME=$jdk" -Encoding UTF8

# Android SDK
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$env:ANDROID_HOME = $sdk
Add-Content -Path $Log -Value "ANDROID_HOME=$sdk" -Encoding UTF8

$sdkmanager = Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path $sdkmanager)) {
    Say "Android 명령줄 도구 내려받는 중 (약 150MB)"
    New-Item -ItemType Directory -Force -Path (Join-Path $sdk 'cmdline-tools') | Out-Null
    $zip = Join-Path $env:TEMP 'android-cmdline-tools.zip'
    try {
        Invoke-WebRequest 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' -OutFile $zip -UseBasicParsing
    } catch { Fail "Android 도구 다운로드: $_" }
    $tmp = Join-Path $env:TEMP 'android-cmdline-tools'
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    Expand-Archive $zip -DestinationPath $tmp -Force
    $latest = Join-Path $sdk 'cmdline-tools\latest'
    if (Test-Path $latest) { Remove-Item $latest -Recurse -Force }
    Move-Item (Join-Path $tmp 'cmdline-tools') $latest
    Remove-Item $zip -Force
}

function Accept-Licenses {
    # 1) sdkmanager --licenses 에 y를 계속 입력 (PowerShell 파이프로는 전달이 안 돼서 cmd 사용)
    cmd /c "(for /l %i in (1,1,40) do @echo y) | `"$sdkmanager`" --licenses" 2>&1 | Out-Null
    # 2) 그래도 안 되면 Android SDK 표준 라이선스 동의 파일을 직접 기록
    $lic = Join-Path $sdk 'licenses'
    New-Item -ItemType Directory -Force -Path $lic | Out-Null
    $f = Join-Path $lic 'android-sdk-license'
    $known = @('8933bad161af4178b1185d1a37fbf41ea5269c55', 'd56f5187479451eabf01fb78af6dfcb131a6481e', '24333f8a63b6825ea9c5514f83c2829b004d1fee')
    $have = if (Test-Path $f) { Get-Content $f } else { @() }
    $add = $known | Where-Object { $have -notcontains $_ }
    if ($add) { Add-Content -Path $f -Value ("`n" + ($add -join "`n")) -Encoding ASCII }
}

$need = @('platforms;android-35', 'build-tools;35.0.0', 'build-tools;34.0.0', 'platform-tools')
$missing = $need | Where-Object { -not (Test-Path (Join-Path $sdk ($_ -replace ';', '\'))) }
Accept-Licenses
if ($missing) {
    Say "Android SDK 구성요소 설치 중: $($missing -join ', ')"
    $list = ($missing | ForEach-Object { "`"$_`"" }) -join ' '
    Add-Content -Path $Log -Value "> sdkmanager $list" -Encoding UTF8
    cmd /c "(for /l %i in (1,1,40) do @echo y) | `"$sdkmanager`" $list" 2>&1 | ForEach-Object { Log-Line $_ }
    $still = $missing | Where-Object { -not (Test-Path (Join-Path $sdk ($_ -replace ';', '\'))) }
    if ($still) { Fail "Android SDK 구성요소 설치 ($($still -join ', '))" }
}

# local.properties
$andDir = Join-Path $Src 'android'
Set-Content -Path (Join-Path $andDir 'local.properties') -Value ("sdk.dir=" + ($sdk -replace '\\', '/')) -Encoding ASCII

Say "APK 빌드 중 (처음엔 필요한 파일을 받느라 5~10분 걸릴 수 있어요)"
Push-Location $andDir
$ok = Run (Join-Path $andDir 'gradlew.bat') @('assembleRelease', '--no-daemon', '--console=plain')
Pop-Location
if (-not $ok) { Fail "태블릿 앱 빌드" }

$apk = Get-ChildItem (Join-Path $andDir 'app\build\outputs\apk\release') -Filter '*.apk' | Select-Object -First 1
if (-not $apk) { Fail "APK 파일을 찾지 못했어요" }
Put-File $apk.FullName (Join-Path $Out 'PenBridge.apk')

# 노트북 앱 안에 넣을 수 있게 복사
$winDir = Join-Path $Src 'windows'
New-Item -ItemType Directory -Force -Path (Join-Path $winDir 'tablet') | Out-Null
Copy-Item $apk.FullName (Join-Path $winDir 'tablet\PenBridge.apk') -Force
Say "PenBridge.apk 완성" 'Green'

# USB 연결용 adb도 함께 넣어 둠 (노트북 앱이 자동으로 찾음)
$pt = Join-Path $sdk 'platform-tools'
if (Test-Path (Join-Path $pt 'adb.exe')) {
    $dest = Join-Path $env:LOCALAPPDATA 'PenBridge\platform-tools'
    if (-not (Test-Path (Join-Path $dest 'adb.exe'))) {
        New-Item -ItemType Directory -Force -Path $dest | Out-Null
        Copy-Item "$pt\*" $dest -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# ───────────────────────── 2. 노트북 앱 (EXE) ─────────────────────────
Say "2/2  노트북 앱(PenBridge.exe) 빌드"

$dotnetOk = $false
if (Get-Command dotnet -ErrorAction SilentlyContinue) {
    $sdks = (& dotnet --list-sdks 2>$null) -join "`n"
    if ($sdks -match '(?m)^10\.') { $dotnetOk = $true }
}
if (-not $dotnetOk) {
    Say ".NET 10 SDK 설치 중 (몇 분 걸려요, 관리자 확인 창이 뜨면 '예')"
    Winget-Install 'Microsoft.DotNet.SDK.10'
    if (Test-Path 'C:\Program Files\dotnet') { $env:Path = "C:\Program Files\dotnet;$env:Path" }
}

$winOut = Join-Path $winDir 'out'
if (-not (Run 'dotnet' @('publish', $winDir, '-c', 'Release', '-o', $winOut, '--nologo'))) { Fail "노트북 앱 빌드" }
Put-File (Join-Path $winOut 'PenBridge.exe') (Join-Path $Out 'PenBridge.exe')
Say "PenBridge.exe 완성" 'Green'


Say "모두 완성! v$Version  결과 폴더: $Out" 'Green'
Add-Content -Path $Log -Value "BUILD OK" -Encoding UTF8
Copy-Log
if (-not $NoPause) {
    Start-Process explorer.exe $Out
    Read-Host "Enter를 누르면 닫혀요"
}
exit 0
