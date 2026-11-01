<#
.SYNOPSIS
    Tự động tăng số phiên bản (patch/minor/major), ghi nhận lịch sử sửa đổi vào CHANGELOG.md,
    tạo Git Commit + Git Tag (vX.Y.Z) và đẩy lên GitHub để xuất bản APK của đúng phiên bản đó.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\scripts\release-version.ps1 -Message "Tối ưu tốc độ lấy nét camera trước"
    powershell -ExecutionPolicy Bypass -File .\scripts\release-version.ps1 -Message "Thêm hiệu ứng chuyển trang" -Bump minor
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$Message,

    [ValidateSet("patch", "minor", "major")]
    [string]$Bump = "patch"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot

$VersionFile = Join-Path $ProjectRoot "VERSION.txt"
$CurrentVer = if (Test-Path $VersionFile) { (Get-Content $VersionFile -Raw).Trim() } else { "1.0.0" }
$Parts = $CurrentVer.Split(".")
$Major = [int]$Parts[0]
$Minor = [int]$Parts[1]
$Patch = [int]$Parts[2]

switch ($Bump) {
    "major" { $Major++; $Minor = 0; $Patch = 0 }
    "minor" { $Minor++; $Patch = 0 }
    "patch" { $Patch++ }
}

$NextVer = "$Major.$Minor.$Patch"
$Tag = "v$NextVer"
$DateStr = Get-Date -Format "yyyy-MM-dd HH:mm"

# Ghi nhận danh sách file vừa chỉnh sửa
$ChangedFiles = git status --porcelain | ForEach-Object { $_.Substring(3).Trim() } | Where-Object { $_ -ne "" }
if (-not $ChangedFiles) {
    Write-Host "[CẢNH BÁO] Không có tệp nào thay đổi để tạo phiên bản mới." -ForegroundColor Yellow
    exit 0
}

[System.IO.File]::WriteAllText($VersionFile, "$NextVer`n", (New-Object System.Text.UTF8Encoding($false)))

# Cập nhật CHANGELOG.md
$ChangelogPath = Join-Path $ProjectRoot "CHANGELOG.md"
$FileBullets = ($ChangedFiles | Select-Object -First 20 | ForEach-Object { "  - ``$_``" }) -join "`n"
$NewEntry = @"
## [$Tag] - $DateStr
### Nội dung cập nhật
- $Message
- **Tệp thay đổi**:
$FileBullets

"@

if (Test-Path $ChangelogPath) {
    $OldLog = Get-Content $ChangelogPath -Raw
    $SplitMarker = "---"
    if ($OldLog.Contains($SplitMarker)) {
        $Idx = $OldLog.IndexOf($SplitMarker) + $SplitMarker.Length
        $UpdatedLog = $OldLog.Substring(0, $Idx) + "`n`n" + $NewEntry + $OldLog.Substring($Idx).TrimStart()
    } else {
        $UpdatedLog = $NewEntry + "`n" + $OldLog
    }
    [System.IO.File]::WriteAllText($ChangelogPath, $UpdatedLog, (New-Object System.Text.UTF8Encoding($false)))
}

git add -A
git commit -m "release($Tag): $Message"
git tag -a $Tag -m "Phiên bản $Tag - $Message"
git push origin HEAD
git push origin $Tag

Write-Host "[HOÀN TẤT] Đã phát hành phiên bản $Tag lên GitHub! GitHub Actions sẽ tự động build và lưu trữ APK cho bản $Tag." -ForegroundColor Green

