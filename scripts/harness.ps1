[CmdletBinding()]
param([ValidateSet('Init', 'Check', 'Verify', 'Device')][string]$Mode = 'Init')

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$runId = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssfffZ') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$runDir = Join-Path $root ".harness/runs/$runId"
New-Item -ItemType Directory -Path $runDir -Force | Out-Null
$steps = [System.Collections.Generic.List[object]]::new()
$exitCode = 0
$failure = $null
$started = (Get-Date).ToUniversalTime().ToString('o')

function Invoke-Step([string]$Name, [string]$Command, [string[]]$Arguments) {
    Write-Host "Running $Name"
    & $Command @Arguments 2>&1 | Tee-Object -FilePath (Join-Path $runDir "$Name.log") | Out-Host
    $code = $LASTEXITCODE
    $steps.Add(@{ name = $Name; command = $Command; arguments = $Arguments; exit_code = $code })
    if ($code -ne 0) { throw "$Name failed (exit $code). See $runDir" }
}

Push-Location $root
try {
    foreach ($path in @('AGENTS.md', 'AGENT.md', 'TECHNOLOGIES.md', 'harness/STATE.md', 'harness/README.md', 'feature_list.json', 'gradlew', 'gradlew.bat', 'gradle/wrapper/gradle-wrapper.jar')) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing required file: $path" }
    }
    $state = Get-Content feature_list.json -Raw | ConvertFrom-Json
    if ($state.schema_version -ne 1 -or @($state.features).Count -eq 0) { throw 'Invalid feature list schema or empty list' }
    $ids = @{}
    foreach ($feature in $state.features) {
        if (-not $feature.id -or $ids.ContainsKey($feature.id)) { throw 'Missing or duplicate feature ID' }
        $ids[$feature.id] = $feature
        if ($feature.status -notin @('pending', 'in_progress', 'blocked', 'done')) { throw "Invalid status: $($feature.id)" }
        if (-not $feature.title -or @($feature.acceptance).Count -eq 0 -or -not ($feature.PSObject.Properties.Name -contains 'depends_on') -or -not ($feature.PSObject.Properties.Name -contains 'evidence')) { throw "Incomplete feature: $($feature.id)" }
        if ($feature.status -eq 'done' -and @($feature.evidence | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count -eq 0) { throw "Done without evidence: $($feature.id)" }
    }
    if (@($state.features | Where-Object status -eq 'in_progress').Count -gt 1) { throw 'Only one feature may be in_progress' }
    foreach ($feature in $state.features) {
        foreach ($dependency in $feature.depends_on) {
            if (-not $ids.ContainsKey($dependency) -or $dependency -eq $feature.id) { throw "Invalid dependency: $dependency" }
            if ($feature.status -in @('done', 'in_progress') -and $ids[$dependency].status -ne 'done') { throw "Unfinished dependency: $dependency" }
        }
    }
    $steps.Add(@{ name = 'structure'; exit_code = 0 })
    $wrapper = if ($env:OS -eq 'Windows_NT') { Join-Path $root 'gradlew.bat' } else { Join-Path $root 'gradlew' }
    if ($Mode -eq 'Init') {
        if (-not (Get-Command java -ErrorAction SilentlyContinue) -and -not (Test-Path "$env:JAVA_HOME/bin/java.exe")) { throw 'Java not found: configure JAVA_HOME or PATH' }
        if (-not (Test-Path local.properties) -and -not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) { throw 'Configure SDK via local.properties or ANDROID_HOME' }
        Invoke-Step 'gradle-version' $wrapper @('--version', '--console=plain')
    }
    if ($Mode -eq 'Verify') {
        foreach ($task in @('testDebugUnitTest', 'lintDebug', 'assembleDebug')) {
            Invoke-Step $task $wrapper @(((':app:' + $task)), '--console=plain')
        }
    }
    if ($Mode -eq 'Device') {
        $adb = (Get-Command adb -ErrorAction Stop).Source
        $devices = & $adb devices
        if ($LASTEXITCODE -ne 0) { throw 'adb devices failed' }
        $ready = @($devices | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
        if ($env:ANDROID_SERIAL) { $ready = @($ready | Where-Object { $_ -eq $env:ANDROID_SERIAL }) }
        if ($ready.Count -ne 1) { throw 'Exactly one ready device is required; use ANDROID_SERIAL to select one' }
        $previousSerial = $env:ANDROID_SERIAL
        try {
            $env:ANDROID_SERIAL = $ready[0]
            Invoke-Step 'connectedDebugAndroidTest' $wrapper @(':app:connectedDebugAndroidTest', '--console=plain')
        } finally { $env:ANDROID_SERIAL = $previousSerial }
    }
} catch {
    $exitCode = 1
    $failure = $_.Exception.Message
    Write-Host "FAILED: $failure" -ForegroundColor Red
} finally {
    Pop-Location
    @{ mode = $Mode; status = $(if ($exitCode -eq 0) { 'passed' } else { 'failed' }); started_utc = $started; finished_utc = (Get-Date).ToUniversalTime().ToString('o'); steps = @($steps.ToArray()); error = $failure } |
        ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $runDir 'result.json') -Encoding utf8
    Write-Host "Report: $runDir/result.json"
}
exit $exitCode
