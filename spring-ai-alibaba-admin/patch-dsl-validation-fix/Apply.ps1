$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# Run after extracting patch-dsl-validation-fix into spring-ai-alibaba-admin.
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$payload = Join-Path $PSScriptRoot 'payload'
$manifestPath = Join-Path $PSScriptRoot 'manifest.json'

if (-not (Test-Path -LiteralPath (Join-Path $root 'pom.xml'))) {
    throw 'Extract patch-dsl-validation-fix into spring-ai-alibaba-admin first.'
}

if (-not (Test-Path -LiteralPath $manifestPath)) {
    throw 'manifest.json not found.'
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json

function Get-TextHash([string]$path) {
    $text = [IO.File]::ReadAllText($path).
        Replace("`r`n", "`n").
        TrimEnd([char[]]"`r`n")

    $sha = [Security.Cryptography.SHA256]::Create()

    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes($text)
        return ([BitConverter]::ToString(
            $sha.ComputeHash($bytes)
        )).Replace('-', '').ToLowerInvariant()
    }
    finally {
        $sha.Dispose()
    }
}

# Validate all files before replacing anything.
foreach ($entry in $manifest.files) {
    $source = Join-Path $payload $entry.path
    $target = Join-Path $root $entry.path

    if (-not (Test-Path -LiteralPath $source)) {
        throw "Missing payload file: $($entry.path)"
    }

    if ((Get-TextHash $source) -ne $entry.updated) {
        throw "Damaged payload: $($entry.path)"
    }

    if (Test-Path -LiteralPath $target) {
        $hash = Get-TextHash $target

        if ($hash -ne $entry.updated -and $hash -ne $entry.original) {
            throw "Local file differs; no files replaced: $($entry.path)"
        }
    }
    elseif ($entry.original) {
        throw "Missing original file: $($entry.path)"
    }
}

# Replace validated files.
foreach ($entry in $manifest.files) {
    $source = Join-Path $payload $entry.path
    $target = Join-Path $root $entry.path

    if (
        (Test-Path -LiteralPath $target) -and
        (Get-TextHash $target) -eq $entry.updated
    ) {
        continue
    }

    $parent = Split-Path -Parent $target
    [IO.Directory]::CreateDirectory($parent) | Out-Null

    $temporary = Join-Path $parent (
        '.dsl-validation-fix-' + [Guid]::NewGuid().ToString('N') + '.tmp'
    )

    try {
        Copy-Item -LiteralPath $source -Destination $temporary -Force

        Move-Item `
            -LiteralPath $temporary `
            -Destination $target `
            -Force
    }
    finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force
        }
    }
}

Write-Host 'DSL validation fix applied. No install, build, startup or configuration changes performed.'
