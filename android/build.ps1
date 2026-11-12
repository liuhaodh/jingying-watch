param(
    [string]$Configuration = "release",
    [switch]$NoBump,
    [switch]$Test
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path $Root -Parent                            # jingying-watch
$LocalTools = Join-Path $ProjectRoot "tools"
$SharedTools = Join-Path (Split-Path $ProjectRoot -Parent) "tools"

# 优先用项目自带的工具链，整个 jingying-watch 目录拷走也能直接构建；
# 找不到再依次回退到环境变量、上层工作区共享的 tools。
function Resolve-Jdk {
    param([string]$Base)
    if (-not $Base -or -not (Test-Path $Base)) { return "" }
    if (Test-Path (Join-Path $Base "bin\javac.exe")) { return $Base }
    $child = Get-ChildItem $Base -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName "bin\javac.exe") } |
        Select-Object -First 1
    if ($child) { return $child.FullName }
    return ""
}

$Sdk = ""
foreach ($candidate in @(
        (Join-Path $LocalTools "android-sdk"),
        $env:ANDROID_HOME,
        (Join-Path $SharedTools "android-sdk"))) {
    if ($candidate -and (Test-Path (Join-Path $candidate "platforms\android-35\android.jar"))) {
        $Sdk = $candidate
        break
    }
}
if (-not $Sdk) { $Sdk = Join-Path $LocalTools "android-sdk" }

$Jdk = ""
foreach ($candidate in @(
        (Join-Path $LocalTools "jdk17"),
        $env:JAVA_HOME,
        (Join-Path $SharedTools "jdk17"))) {
    $found = Resolve-Jdk $candidate
    if ($found) { $Jdk = $found; break }
}
if (-not $Jdk) { $Jdk = $env:JAVA_HOME }
$BuildTools = Join-Path $Sdk "build-tools\35.0.0"
$Platform = Join-Path $Sdk "platforms\android-35\android.jar"

if (-not $Jdk -or -not (Test-Path (Join-Path $Jdk "bin\javac.exe"))) { throw "JDK 17 not found. Set JAVA_HOME or place it in tools\jdk17." }
if (-not (Test-Path $Platform)) { throw "Android platform 35 not found. Expected: $Platform" }
if (-not (Test-Path (Join-Path $BuildTools "aapt2.exe"))) { throw "Android build-tools 35.0.0 not found." }

$env:JAVA_HOME = $Jdk
$env:PATH = (Join-Path $Jdk "bin") + ";" + $BuildTools + ";" + $env:PATH

$App = Join-Path $Root "app\src\main"
$Manifest = Join-Path $App "AndroidManifest.xml"
$Res = Join-Path $App "res"
$Src = Join-Path $App "java"
$Build = Join-Path $Root "build"
$Compiled = Join-Path $Build "compiled.zip"
$Gen = Join-Path $Build "gen"
$Classes = Join-Path $Build "classes"
$DexDir = Join-Path $Build "dex"
$Unsigned = Join-Path $Build "app-unsigned.apk"
$Aligned = Join-Path $Build "app-aligned.apk"
$Keystore = Join-Path $Root "jingying-watch-dev.keystore"
$VersionFile = Join-Path $Root "version.properties"

# ---------- 版本号：每次构建自动 +1（-NoBump 可关闭） ----------
$VersionCode = 10
$VersionName = "1.0.0"
if (Test-Path $VersionFile) {
    foreach ($line in (Get-Content $VersionFile)) {
        if ($line -match '^\s*versionCode\s*=\s*(\d+)\s*$') { $VersionCode = [int]$Matches[1] }
        if ($line -match '^\s*versionName\s*=\s*([0-9][0-9.]*)\s*$') { $VersionName = $Matches[1] }
    }
}
if (-not $NoBump) {
    $VersionCode = $VersionCode + 1
    $parts = @($VersionName.Split('.'))
    while ($parts.Count -lt 3) { $parts += "0" }
    $patch = 0
    if (-not [int]::TryParse($parts[2], [ref]$patch)) { $patch = 0 }
    $parts[2] = [string]($patch + 1)
    $VersionName = $parts[0] + "." + $parts[1] + "." + $parts[2]
    # -NoBump 时保持 version.properties 完全不动，调试构建不该留下文件改动
    Set-Content -Path $VersionFile -Encoding ASCII `
        -Value @("versionCode=$VersionCode", "versionName=$VersionName")
}
Write-Host "Version: $VersionName (code $VersionCode)" -ForegroundColor Yellow

$Apk = Join-Path $Root ("JingYing-Watch-v" + $VersionName + ".apk")
$ApkLatest = Join-Path $Root "JingYing-Watch-latest.apk"

if (Test-Path $Build) {
    try {
        Remove-Item $Build -Recurse -Force -ErrorAction Stop
    } catch {
        # 目录被系统/杀软占用时改名绕开；统一收进 build-stale/（已被 .gitignore 忽略），
        # 别在 android/ 根下堆一堆 build-<时间戳> 目录
        $staleRoot = Join-Path $Root "build-stale"
        New-Item -ItemType Directory -Force -Path $staleRoot | Out-Null
        $stale = Join-Path $staleRoot ("build-" + (Get-Date -Format "yyyyMMddHHmmss"))
        Rename-Item $Build $stale -ErrorAction Stop
        Write-Host "build 目录被占用，已移到 $(Split-Path $stale -Leaf)" -ForegroundColor Yellow
    }
}
New-Item -ItemType Directory -Force -Path $Build, $Gen, $Classes, $DexDir | Out-Null

if ($Test) {
    Write-Host "[0/6] Running unit tests..." -ForegroundColor Cyan
    $TestScript = Join-Path (Split-Path $Root -Parent) "tools\run-tests.ps1"
    & powershell -ExecutionPolicy Bypass -File $TestScript -Quiet
    if ($LASTEXITCODE -ne 0) { throw "unit tests failed" }
}

Write-Host "[1/6] Compiling resources..." -ForegroundColor Cyan
& (Join-Path $BuildTools "aapt2.exe") compile --dir $Res -o $Compiled
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "[2/6] Linking resources..." -ForegroundColor Cyan
& (Join-Path $BuildTools "aapt2.exe") link `
    -o $Unsigned `
    -I $Platform `
    --manifest $Manifest `
    -R $Compiled `
    --java $Gen `
    --auto-add-overlay `
    --min-sdk-version 23 `
    --target-sdk-version 35 `
    --version-code $VersionCode `
    --version-name $VersionName
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[3/6] Compiling Java..." -ForegroundColor Cyan
$JavaFiles = @(Get-ChildItem -Path $Src, $Gen -Recurse -Filter *.java | ForEach-Object { $_.FullName })
# javac 会把「过时 API」提示写到 stderr，PowerShell Stop 模式会误判为失败
$ErrorActionPreference = "Continue"
& (Join-Path $Jdk "bin\javac.exe") `
    -encoding UTF-8 `
    -source 8 `
    -target 8 `
    -Xlint:-options `
    -nowarn `
    -classpath $Platform `
    -d $Classes `
    $JavaFiles
$ErrorActionPreference = "Stop"
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[4/6] Dexing..." -ForegroundColor Cyan
$ClassesJar = Join-Path $Build "classes.jar"
& (Join-Path $Jdk "bin\jar.exe") cf $ClassesJar -C $Classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
& (Join-Path $BuildTools "d8.bat") --release --min-api 23 --lib $Platform --output $DexDir $ClassesJar
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[5/6] Packaging..." -ForegroundColor Cyan
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($Unsigned, 'Update')
try {
    $dex = Join-Path $DexDir "classes.dex"
    $entry = $zip.CreateEntry("classes.dex")
    $entryStream = $entry.Open()
    $fileStream = [System.IO.File]::OpenRead($dex)
    try {
        $fileStream.CopyTo($entryStream)
    } finally {
        $fileStream.Dispose()
        $entryStream.Dispose()
    }
} finally {
    $zip.Dispose()
}
& (Join-Path $BuildTools "zipalign.exe") -f 4 $Unsigned $Aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "[6/6] Signing..." -ForegroundColor Cyan
if (-not (Test-Path $Keystore)) {
    & (Join-Path $Jdk "bin\keytool.exe") -genkeypair `
        -keystore $Keystore `
        -storepass "jingying2026" `
        -keypass "jingying2026" `
        -alias "jingying-watch" `
        -keyalg RSA `
        -keysize 2048 `
        -validity 10000 `
        -dname "CN=JingYingWatch, OU=Android, O=JingYing, L=Shanghai, ST=Shanghai, C=CN"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}
& (Join-Path $BuildTools "apksigner.bat") sign `
    --ks $Keystore `
    --ks-pass "pass:jingying2026" `
    --key-pass "pass:jingying2026" `
    --out $Apk `
    $Aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

& (Join-Path $BuildTools "apksigner.bat") verify --print-certs $Apk
if ($LASTEXITCODE -ne 0) { throw "APK verification failed" }

$size = [math]::Round((Get-Item $Apk).Length / 1MB, 2)
Copy-Item $Apk $ApkLatest -Force
$ProjectRoot = Split-Path $Root -Parent
try { Copy-Item $Apk (Join-Path $ProjectRoot "JingYing-Watch-latest.apk") -Force } catch { }
Write-Host ""
Write-Host "APK: $Apk ($size MB)" -ForegroundColor Green
Write-Host "Latest: $ApkLatest" -ForegroundColor Green
Write-Host "Package: com.jingying.watch  |  versionName: $VersionName (code $VersionCode)  |  minSdk: 23  |  targetSdk: 35" -ForegroundColor Gray
exit 0
