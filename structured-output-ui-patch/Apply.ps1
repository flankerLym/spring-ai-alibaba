$ErrorActionPreference = "Stop"

$PatchRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $PatchRoot

$Files = @(
    "spring-ai-alibaba-admin/frontend/packages/main/src/pages/App/Workflow/nodes/LLM/panel.tsx",
    "spring-ai-alibaba-admin/frontend/packages/main/src/pages/App/Workflow/nodes/LLM/schema.tsx",
    "spring-ai-alibaba-admin/frontend/packages/main/src/pages/App/Workflow/nodes/LLM/structuredOutput.ts",
    "spring-ai-alibaba-admin/frontend/packages/main/src/pages/App/Workflow/components/StructuredOutputSchemaModal/index.tsx",
    "spring-ai-alibaba-admin/frontend/packages/main/src/pages/App/Workflow/components/StructuredOutputSchemaModal/index.module.less"
)

foreach ($Relative in $Files) {
    $Source = Join-Path $PatchRoot $Relative
    $Target = Join-Path $RepoRoot $Relative
    $TargetDir = Split-Path -Parent $Target

    if (!(Test-Path $Source)) {
        throw "Patch file missing: $Source"
    }

    if (!(Test-Path $TargetDir)) {
        New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null
    }

    if (Test-Path $Target) {
        Copy-Item $Target "$Target.bak" -Force
    }

    Copy-Item $Source $Target -Force
    Write-Host "Applied: $Relative"
}

Write-Host "Structured output UI patch applied successfully."
