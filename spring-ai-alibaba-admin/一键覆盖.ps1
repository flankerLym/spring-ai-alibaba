$ErrorActionPreference = "Stop"

# 本脚本必须放在 spring-ai-alibaba-admin 目录下
$AdminRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $AdminRoot

$PatchDir = Join-Path $AdminRoot "patch_files"

Write-Host "Admin 目录: $AdminRoot" -ForegroundColor Cyan

# 校验当前目录确实是 spring-ai-alibaba-admin
$ServerStart = Join-Path $AdminRoot "spring-ai-alibaba-admin-server-start"
$Frontend = Join-Path $AdminRoot "frontend"

if (!(Test-Path $ServerStart)) {
    Write-Host "错误：当前目录不是 spring-ai-alibaba-admin 根目录。" -ForegroundColor Red
    Write-Host "请把压缩包解压到：" -ForegroundColor Yellow
    Write-Host "spring-ai-alibaba\spring-ai-alibaba-admin\" -ForegroundColor Yellow
    exit 1
}

if (!(Test-Path $PatchDir)) {
    Write-Host "错误：找不到 patch_files 目录。" -ForegroundColor Red
    exit 1
}

Write-Host "[1/3] 覆盖本次修改文件..." -ForegroundColor Cyan

Get-ChildItem -Path $PatchDir -Recurse -File | ForEach-Object {
    $relative = $_.FullName.Substring($PatchDir.Length).TrimStart('\')
    $target = Join-Path $AdminRoot $relative
    $targetParent = Split-Path -Parent $target

    if (!(Test-Path $targetParent)) {
        New-Item -ItemType Directory -Path $targetParent -Force | Out-Null
    }

    Copy-Item $_.FullName $target -Force
    Write-Host "覆盖: $relative"
}

Write-Host "[2/3] 校验关键模板..." -ForegroundColor Cyan

$GraphTemplate = Join-Path $AdminRoot "spring-ai-alibaba-admin-server-start\src\main\resources\templates\GraphBuilder.java.mustache"

if (!(Test-Path $GraphTemplate)) {
    Write-Host "错误：GraphBuilder.java.mustache 不存在。" -ForegroundColor Red
    exit 1
}

$Content = Get-Content $GraphTemplate -Raw

if ($Content -notmatch "ADMIN_DIFY_FIX_V4") {
    Write-Host "错误：GraphBuilder 模板没有正确覆盖。" -ForegroundColor Red
    exit 1
}

if ($Content -notmatch "NodeChatModelRegistry") {
    Write-Host "错误：NodeChatModelRegistry 未写入模板。" -ForegroundColor Red
    exit 1
}

Write-Host "[3/3] 删除 Admin 旧 target 缓存..." -ForegroundColor Cyan

$TargetCache = Join-Path $AdminRoot "spring-ai-alibaba-admin-server-start\target"

if (Test-Path $TargetCache) {
    Remove-Item $TargetCache -Recurse -Force
    Write-Host "已删除: $TargetCache"
}
else {
    Write-Host "target 不存在，无需删除。"
}

Write-Host ""
Write-Host "覆盖完成。" -ForegroundColor Green
Write-Host "接下来重新编译并重启 Admin，然后重新上传 DSL 生成 demo。" -ForegroundColor Green
