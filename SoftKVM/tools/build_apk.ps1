param([string]$Target)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'      # 다운로드 속도 개선
[Console]::OutputEncoding = [Text.Encoding]::UTF8
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Target = (Resolve-Path -LiteralPath $Target).Path.TrimEnd('\')
$base = Join-Path $env:LOCALAPPDATA 'KVM-build'   # 빌드 도구 설치 위치 (KVM 폴더와 분리)
New-Item -ItemType Directory -Force -Path $base | Out-Null

function Step($n, $msg) { Write-Host ""; Write-Host "[$n/5] $msg" -ForegroundColor Cyan }
function Fetch($url, $name) {
    $zip = Join-Path $base $name
    if (-not (Test-Path -LiteralPath $zip)) {
        Write-Host "  내려받는 중: $name"
        Invoke-WebRequest -Uri $url -OutFile "$zip.part" -UseBasicParsing
        Move-Item -LiteralPath "$zip.part" -Destination $zip -Force
    }
    return $zip
}

# 1. JDK 17
Step 1 'Java(JDK 17) 준비'
$jdkDir = Join-Path $base 'jdk'
if (-not (Get-ChildItem -LiteralPath $jdkDir -Directory -ErrorAction SilentlyContinue)) {
    $zip = Fetch 'https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip' 'jdk17.zip'
    Expand-Archive -LiteralPath $zip -DestinationPath $jdkDir -Force
}
$env:JAVA_HOME = (Get-ChildItem -LiteralPath $jdkDir -Directory | Select-Object -First 1).FullName
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Write-Host "  완료: $env:JAVA_HOME"

# 2. Android SDK 명령줄 도구
Step 2 'Android SDK 준비'
$sdk = Join-Path $base 'sdk'
$sdkmanager = Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path -LiteralPath $sdkmanager)) {
    $zip = Fetch 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' 'cmdline-tools.zip'
    $tmp = Join-Path $base 'ct_tmp'
    Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    Expand-Archive -LiteralPath $zip -DestinationPath $tmp -Force
    New-Item -ItemType Directory -Force -Path (Join-Path $sdk 'cmdline-tools') | Out-Null
    Move-Item -LiteralPath (Join-Path $tmp 'cmdline-tools') -Destination (Join-Path $sdk 'cmdline-tools\latest')
    Remove-Item -LiteralPath $tmp -Recurse -Force
}

# 3. SDK 구성요소 + 라이선스 동의
Step 3 'Android 플랫폼·빌드 도구 설치 (처음 한 번, 몇 분 걸려요)'
# 라이선스 동의 파일을 직접 기록 (자동 동의가 PC 환경에 따라 실패하는 문제 방지)
$lic = Join-Path $sdk 'licenses'
New-Item -ItemType Directory -Force -Path $lic | Out-Null
Set-Content -LiteralPath (Join-Path $lic 'android-sdk-license') -Encoding ASCII -Value @(
    '', '8933bad161af4178b1185d1a37fbf41ea5269c55', 'd56f5187479451eabf01fb78af6dfcb131a6481e',
    '24333f8a63b6825ea9c5514f83c2829b004d1fee')
Set-Content -LiteralPath (Join-Path $lic 'android-sdk-preview-license') -Encoding ASCII -Value @(
    '', '84831b9409646a918e30573bab4c9c91346d8abd')
cmd /c "(for /l %i in (1,1,40) do @echo y) | `"$sdkmanager`" --sdk_root=`"$sdk`" --licenses" | Out-Null
Write-Host '  필요한 부품 확인·설치 중...'
& $sdkmanager "--sdk_root=$sdk" 'platforms;android-35' 'build-tools;35.0.0' 'build-tools;34.0.0' 'platform-tools' | Out-Null
foreach ($need in 'platforms\android-35', 'build-tools\35.0.0') {
    if (-not (Test-Path -LiteralPath (Join-Path $sdk $need))) { throw "SDK 부품 설치 실패: $need (인터넷 연결 확인 후 다시 실행해 주세요)" }
}
$env:ANDROID_HOME = $sdk
Write-Host '  완료'

# 4. Gradle
Step 4 'Gradle 준비'
$gradleDir = Join-Path $base 'gradle'
$gradle = Join-Path $gradleDir 'gradle-8.10.2\bin\gradle.bat'
if (-not (Test-Path -LiteralPath $gradle)) {
    $zip = Fetch 'https://services.gradle.org/distributions/gradle-8.10.2-bin.zip' 'gradle-8.10.2.zip'
    Expand-Archive -LiteralPath $zip -DestinationPath $gradleDir -Force
}
Write-Host '  완료'

# 5. 빌드
Step 5 'APK 빌드'
$proj = Join-Path $Target 'android'
$sdkProp = ($sdk -replace '\\', '\\') -replace ':', '\:'
Set-Content -LiteralPath (Join-Path $proj 'local.properties') -Value "sdk.dir=$sdkProp" -Encoding ASCII
& $gradle -p $proj assembleDebug --no-daemon --console=plain --warning-mode=none
if ($LASTEXITCODE -ne 0) { throw '빌드 오류가 났습니다. 위 오류 메시지를 Claude에게 보내주세요.' }
$apk = Join-Path $proj 'app\build\outputs\apk\debug\app-debug.apk'
Copy-Item -LiteralPath $apk -Destination (Join-Path $Target 'KVM-Android.apk') -Force
# 서버가 태블릿에 업데이트를 알릴 때 쓰는 버전 정보
$ver = if (Test-Path -LiteralPath (Join-Path $Target 'VERSION.txt')) { (Get-Content -LiteralPath (Join-Path $Target 'VERSION.txt') -Raw).Trim() } else { '1.0.0' }
Set-Content -LiteralPath (Join-Path $Target 'KVM-Android.version') -Value $ver -Encoding ASCII
Write-Host ""
Write-Host "완료: KVM 폴더에 KVM-Android.apk 가 생겼습니다." -ForegroundColor Green
