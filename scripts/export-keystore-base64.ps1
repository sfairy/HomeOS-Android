# 导出 keystore 的 Base64，用于配置 GitHub Secret: KEYSTORE_BASE64
# 用法: .\scripts\export-keystore-base64.ps1
#       .\scripts\export-keystore-base64.ps1 -SetGitHubSecret  # 需已安装并登录 gh CLI

param(
    [switch]$SetGitHubSecret,
    [string]$KeystorePath = "$PSScriptRoot\..\app\homeos-release-key.jks",
    [string]$Repo = "sfairy/homeos-android"
)

if (-not (Test-Path $KeystorePath)) {
    Write-Error "找不到 keystore: $KeystorePath"
    exit 1
}

$bytes = [IO.File]::ReadAllBytes((Resolve-Path $KeystorePath))
$base64 = [Convert]::ToBase64String($bytes)
$outFile = Join-Path $env:TEMP "homeos-keystore-base64.txt"
Set-Content -Path $outFile -Value $base64 -NoNewline -Encoding ASCII

Write-Host "Keystore: $KeystorePath ($($bytes.Length) bytes)"
Write-Host "Base64 已写入: $outFile"
Write-Host ""
Write-Host "GitHub 配置步骤:"
Write-Host "  1. 打开 https://github.com/$Repo/settings/secrets/actions"
Write-Host "  2. New repository secret"
Write-Host "  3. Name: KEYSTORE_BASE64"
Write-Host "  4. Value: 粘贴 $outFile 中的全部内容"
Write-Host ""
Write-Host "可选 Secrets（密码非 homeos123 时配置）:"
Write-Host "  KEYSTORE_PASSWORD / KEY_PASSWORD / KEY_ALIAS"

if ($SetGitHubSecret) {
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
        Write-Error "未安装 gh CLI，请手动配置 Secret 或安装: winget install GitHub.cli"
        exit 1
    }
    gh secret set KEYSTORE_BASE64 --repo $Repo --body $base64
    Write-Host "已设置 KEYSTORE_BASE64 到 $Repo"
}
