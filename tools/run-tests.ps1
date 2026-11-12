# 纯 Java 单元测试：只编译不依赖 Android 的那几个类，用桌面 JVM 直接跑。
#   powershell -ExecutionPolicy Bypass -File .\tools\run-tests.ps1
# 依赖：JDK 17（JAVA_HOME 或项目内 tools\jdk17）、org.json（tools\json.jar）
param(
    [switch]$Quiet
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path          # jingying-watch\tools
$Project = Split-Path $Root -Parent                              # jingying-watch
$SharedTools = Join-Path (Split-Path $Project -Parent) "tools"   # 上层工作区共享工具

# 优先用项目自带的 JDK，整个 jingying-watch 拷走也能单独跑测试
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

$Jdk = ""
foreach ($candidate in @((Join-Path $Root "jdk17"), $env:JAVA_HOME, (Join-Path $SharedTools "jdk17"))) {
    $found = Resolve-Jdk $candidate
    if ($found) { $Jdk = $found; break }
}
if (-not $Jdk) {
    throw "找不到 JDK 17：请设置 JAVA_HOME，或把 JDK 放到 $Root\jdk17"
}

$JsonJar = Join-Path $Root "json.jar"
if (-not (Test-Path $JsonJar)) { $JsonJar = Join-Path $SharedTools "json.jar" }
if (-not (Test-Path $JsonJar)) {
    throw "找不到 org.json 的 jar：把它放到 $Root\json.jar 即可"
}

$SrcDir = Join-Path $Project "android\app\src\main\java\com\jingying\watch"
$TestDir = Join-Path $Root "test\com\jingying\watch"
$Out = Join-Path $Project "android\build\tests"

# 参与测试的类必须只依赖 java.* / org.json（任何 android.* 依赖都会让桌面编译失败）
$Pure = @(
    "Work.java", "RemoteFile.java", "Mime.java",
    "Util.java", "Library.java", "Shuffles.java", "DouyinLinks.java",
    "TarReader.java", "TarWriter.java", "HttpLite.java", "AlbumNav.java", "LocalFiles.java",
    "TapNav.java", "PlayNav.java", "SpeedNav.java", "SearchNav.java"
)

$Files = @()
foreach ($name in $Pure) {
    $path = Join-Path $SrcDir $name
    if (-not (Test-Path $path)) { throw "缺少源文件：$path" }
    $Files += $path
}
$TestFiles = @(Get-ChildItem -Path $TestDir -Filter *.java -ErrorAction SilentlyContinue)
if ($TestFiles.Count -eq 0) { throw "缺少测试源码：$TestDir" }
$Files += ($TestFiles | ForEach-Object { $_.FullName })

if (Test-Path $Out) { Remove-Item $Out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $Out | Out-Null

if (-not $Quiet) { Write-Host "Compiling tests ($($Files.Count) files)..." -ForegroundColor Cyan }
& (Join-Path $Jdk "bin\javac.exe") `
    -encoding UTF-8 `
    -source 8 `
    -target 8 `
    -Xlint:-options `
    -classpath $JsonJar `
    -d $Out `
    $Files
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# WatchTests 里用了相对路径 android/build/tar-test-*：把 cwd 固定到项目根，
# 否则从别的目录（或 build.ps1 从 android\ 调进来）跑会在错误位置建目录、甚至直接失败
Push-Location $Project
try {
    & (Join-Path $Jdk "bin\java.exe") "-Dfile.encoding=UTF-8" -classpath "$Out;$JsonJar" com.jingying.watch.WatchTests
    $testExit = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $testExit
