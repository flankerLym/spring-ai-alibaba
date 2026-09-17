param([string]$AdminRoot = $PSScriptRoot)
$ErrorActionPreference = "Stop"

$umirc = Join-Path $AdminRoot "frontend\packages\main\.umirc.ts"
$menu = Join-Path $AdminRoot "frontend\packages\main\src\layouts\SideMenuLayout.tsx"

if (!(Test-Path $umirc)) { throw "Not found: $umirc" }
if (!(Test-Path $menu)) { throw "Not found: $menu" }

Copy-Item $umirc "$umirc.bak-workflow-trace" -Force
Copy-Item $menu "$menu.bak-workflow-trace" -Force

$u = Get-Content $umirc -Raw -Encoding UTF8
if ($u -notmatch "/admin/workflow-tracing") {
    $needle = "        { path: '/admin/tracing', component: '@/legacy/pages/tracing/tracing' },"
    if (!$u.Contains($needle)) { throw "Route insertion point not found" }
    $u = $u.Replace($needle, $needle + "`r`n        { path: '/admin/workflow-tracing', component: 'Observability/WorkflowTrace' },")
    Set-Content $umirc $u -Encoding UTF8
}

$m = Get-Content $menu -Raw -Encoding UTF8
if ($m -notmatch "ApartmentOutlined") {
    $needle = "  NodeIndexOutlined,"
    if (!$m.Contains($needle)) { throw "Icon insertion point not found" }
    $m = $m.Replace($needle, $needle + "`r`n  ApartmentOutlined,")
}

if ($m -notmatch "pathname\.startsWith\('/admin/workflow-tracing'\)") {
    $needle = "  // Tracing 页面"
    if (!$m.Contains($needle)) { throw "Selected menu insertion point not found" }
    $insert = "  // Workflow Trace 页面`r`n  if (pathname.startsWith('/admin/workflow-tracing')) {`r`n    return '/admin/workflow-tracing';`r`n  }`r`n`r`n"
    $m = $m.Replace($needle, $insert + $needle)
}

if ($m -notmatch "key: '/admin/workflow-tracing'") {
    $needle = @"
          {
            key: '/admin/tracing',
            label: 'Tracing',
            icon: <NodeIndexOutlined />,
          },
"@
    $needleLf = $needle -replace "`r`n", "`n"
    if ($m.Contains($needle)) { $actual = $needle }
    elseif ($m.Contains($needleLf)) { $actual = $needleLf }
    else { throw "Observability menu insertion point not found" }

    $addition = @"
          {
            key: '/admin/workflow-tracing',
            label: '工作流链路',
            icon: <ApartmentOutlined />,
          },
"@
    $m = $m.Replace($actual, $actual + $addition)
}

Set-Content $menu $m -Encoding UTF8
Write-Host "Workflow Trace UI integrated."
Write-Host "Backups: $umirc.bak-workflow-trace, $menu.bak-workflow-trace"
