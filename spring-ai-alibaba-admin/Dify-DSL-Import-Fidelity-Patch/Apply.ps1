# 简单版 DSL 修复补丁

$patchRoot = $PSScriptRoot
$adminRoot = Split-Path -Parent $patchRoot
$files = Join-Path $patchRoot "files"

if (!(Test-Path "$adminRoot\pom.xml")) {
    throw "请把补丁放到 spring-ai-alibaba-admin 根目录下"
}

$targets = @(
"frontend\packages\main\src\pages\App\Workflow\hooks\useGlobalVariableList.tsx",
"frontend\packages\main\src\pages\App\Workflow\nodes\LLM\schema.tsx",
"frontend\packages\main\src\pages\App\Workflow\types\index.ts",
"frontend\packages\main\src\pages\App\Workflow\utils\index.ts",
"frontend\packages\main\src\pages\App\utils\importWorkflowDsl.ts",
"frontend\packages\main\src\types\appManage.ts",
"spring-ai-alibaba-admin-server-core\src\main\java\com\alibaba\cloud\ai\studio\core\workflow\processor\AbstractExecuteProcessor.java",
"spring-ai-alibaba-admin-server-core\src\main\java\com\alibaba\cloud\ai\studio\core\workflow\processor\impl\StartExecuteProcessor.java",
"spring-ai-alibaba-admin-server-core\src\main\java\com\alibaba\cloud\ai\studio\core\workflow\runtime\WorkflowExecuteManager.java",
"spring-ai-alibaba-admin-server-runtime\src\main\java\com\alibaba\cloud\ai\studio\runtime\domain\workflow\inner\ShortTermMemory.java"
)


foreach($file in $targets){

    $source = Join-Path $files $file
    $target = Join-Path $adminRoot $file

    if(!(Test-Path $source)){
        throw "补丁文件不存在: $file"
    }

    if(!(Test-Path $target)){
        throw "项目文件不存在: $file"
    }

    Copy-Item $source $target -Force

    Write-Host "已替换: $file"
}


Write-Host ""
Write-Host "补丁替换完成" -ForegroundColor Green