<#
.SYNOPSIS
    Script tự động kiểm tra/cài đặt Android SDK Command-Line Tools (nếu máy chưa có) và đóng gói file APK (Debug + Signed Release) cho Locket Photobooth Native.
#>
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot

# 1. Xác định JAVA_HOME (ưu tiên JDK 17/21 có sẵn trên máy)
if (-not $env:JAVA_HOME) {
    $JdkCandidate = Get-ChildItem "C:\Program Files\Microsoft\jdk-*", "C:\Program Files\Eclipse Adoptium\jdk-*" -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($JdkCandidate) {
        $env:JAVA_HOME = $JdkCandidate.FullName
        Write-Host "[OK] JAVA_HOME = $env:JAVA_HOME" -ForegroundColor Green
    } else {
        throw "Không tìm thấy JDK 17 hoặc JDK 21. Vui lòng cài đặt OpenJDK 17+ trước."
    }
}

# 2. Xác định hoặc tự động tải Android SDK Command-Line Tools
$SdkDir = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "$env:LOCALAPPDATA\Android\Sdk" }
$Platform35 = Join-Path $SdkDir "platforms\android-35"

if (-not (Test-Path $Platform35)) {
    Write-Host "[INFO] Chưa thấy Android SDK Platform 35 tại $SdkDir. Đang tự động tải Android Command-Line Tools..." -ForegroundColor Cyan
    New-Item -ItemType Directory -Force -Path "$SdkDir\cmdline-tools" | Out-Null
    $ZipPath = Join-Path $env:TEMP "commandlinetools-win-latest.zip"
    if (-not (Test-Path $ZipPath)) {
        Invoke-WebRequest -Uri "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" -OutFile $ZipPath -UseBasicParsing
    }
    $ExtractTmp = Join-Path $env:TEMP "ag_cmdline_extract"
    if (Test-Path $ExtractTmp) { Remove-Item -Recurse -Force $ExtractTmp }
    Expand-Archive -Path $ZipPath -DestinationPath $ExtractTmp -Force
    $LatestDir = Join-Path $SdkDir "cmdline-tools\latest"
    if (Test-Path $LatestDir) { Remove-Item -Recurse -Force $LatestDir }
    Move-Item -Path (Join-Path $ExtractTmp "cmdline-tools") -Destination $LatestDir

    $SdkManager = Join-Path $LatestDir "bin\sdkmanager.bat"
    Write-Host "[INFO] Đang chấp nhận giấy phép và cài đặt platforms;android-35 & build-tools;35.0.0..." -ForegroundColor Cyan
    1..15 | ForEach-Object { "y" } | & $SdkManager "--sdk_root=$SdkDir" --licenses | Out-Null
    & $SdkManager "--sdk_root=$SdkDir" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
}

$env:ANDROID_HOME = $SdkDir
$EscapedSdk = $SdkDir.Replace("\", "\\")
[System.IO.File]::WriteAllText((Join-Path $ProjectRoot "local.properties"), "sdk.dir=$EscapedSdk`n", (New-Object System.Text.UTF8Encoding($false)))

# 3. Chạy Gradle build ra file APK đã ký số
Write-Host "[BUILD] Đang biên dịch Locket Photobooth Native (Debug & Signed Release APK)..." -ForegroundColor Yellow
& .\gradlew.bat assembleDebug assembleRelease --stacktrace
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host "`n[HOÀN TẤT] Các tệp APK đã sẵn sàng cài đặt lên máy thật:" -ForegroundColor Green
Get-ChildItem -Path "app\build\outputs\apk" -Recurse -Filter "*.apk" | ForEach-Object {
    Write-Host " -> $($_.FullName) ($([math]::Round($_.Length / 1MB, 2)) MB)" -ForegroundColor Cyan
}
