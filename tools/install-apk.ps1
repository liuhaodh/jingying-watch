$ErrorActionPreference = "Stop"
$Root = Split-Path $PSScriptRoot -Parent
$Workspace = Split-Path $Root -Parent
$Apk = Join-Path $Root "android\JingYing-Watch-latest.apk"
if (-not (Test-Path $Apk)) {
    # 兼容旧布局：没有 latest 就挑版本号最大的那个
    $candidates = @(Get-ChildItem (Join-Path $Root "android") -Filter "JingYing-Watch-v*.apk" -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending)
    if ($candidates.Count -gt 0) { $Apk = $candidates[0].FullName }
}
if (-not (Test-Path $Apk)) { throw "APK 不存在，请先运行 构建APK.bat" }
Write-Host "安装：$Apk"

$Adb = $null
foreach ($candidate in @(
    $(if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "platform-tools\adb.exe" }),
    (Join-Path $Workspace "tools\android-sdk\platform-tools\adb.exe"),
    "adb"
)) {
    if (-not $candidate) { continue }
    if ($candidate -eq "adb") {
        $found = Get-Command adb -ErrorAction SilentlyContinue
        if ($found) { $Adb = $found.Source; break }
    } elseif (Test-Path $candidate) { $Adb = $candidate; break }
}
if (-not $Adb) { throw "找不到 adb：请安装 Android platform-tools，或设置 ANDROID_HOME" }

Write-Host "使用 adb：$Adb"
& $Adb install -r $Apk
& $Adb shell am start -n com.jingying.watch/.HomeActivity
