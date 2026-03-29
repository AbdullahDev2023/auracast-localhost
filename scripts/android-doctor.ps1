[CmdletBinding()]
param(
    [switch]$Json
)

. "$PSScriptRoot\AndroidCli.ps1"

$environment = Get-AuraCastAndroidEnvironment
$packages = Get-AuraCastSdkPackages -SdkRoot $environment.SdkRoot
$backupEntries = Get-AuraCastSdkBackupEntries -SdkRoot $environment.SdkRoot

$checks = New-Object System.Collections.Generic.List[object]

function Add-Check {
    param(
        [string]$Name,
        [string]$Status,
        [string]$Details
    )

    $checks.Add([pscustomobject]@{
        name = $Name
        status = $Status
        details = $Details
    }) | Out-Null
}

Add-Check -Name "projectRoot" -Status "ok" -Details $environment.ProjectRoot
Add-Check -Name "sdkRoot" -Status "ok" -Details $environment.SdkRoot
Add-Check -Name "javaHome" -Status "ok" -Details $environment.JavaHome
Add-Check -Name "compileSdk" -Status "ok" -Details "android-$($environment.CompileSdk)"
Add-Check -Name "sdkmanager" -Status "ok" -Details (Invoke-AuraCastTool -FilePath $environment.Tools.sdkmanager -Arguments @("--version") -CaptureOutput)
Add-Check -Name "adb" -Status "ok" -Details (Invoke-AuraCastTool -FilePath $environment.Tools.adb -Arguments @("version") -CaptureOutput)
Add-Check -Name "avdmanager" -Status "ok" -Details "resolved: $($environment.Tools.avdmanager)"

$requiredPlatform = "android-$($environment.CompileSdk)"
if ($packages.Platforms -contains $requiredPlatform) {
    Add-Check -Name "platforms" -Status "ok" -Details (($packages.Platforms | Sort-Object) -join ", ")
} else {
    Add-Check -Name "platforms" -Status "error" -Details "Missing $requiredPlatform. Installed: $(($packages.Platforms | Sort-Object) -join ', ')"
}

if (@($packages.BuildTools).Count -gt 0) {
    Add-Check -Name "buildTools" -Status "ok" -Details (($packages.BuildTools | Sort-Object) -join ", ")
} else {
    Add-Check -Name "buildTools" -Status "error" -Details "No Android build-tools packages were found."
}

if (@($backupEntries).Count -gt 0) {
    Add-Check -Name "backupEntries" -Status "warn" -Details (($backupEntries | Sort-Object) -join ", ")
} else {
    Add-Check -Name "backupEntries" -Status "ok" -Details "No *.bak platform folders detected."
}

$adbDevices = Invoke-AuraCastTool -FilePath $environment.Tools.adb -Arguments @("devices") -CaptureOutput
Add-Check -Name "adbDevices" -Status "ok" -Details ($adbDevices -replace "\r", "")

if ($Json) {
    $checks | ConvertTo-Json -Depth 4
    exit 0
}

Write-Host ""
Write-Host "AuraCast Android Doctor" -ForegroundColor Cyan
Write-Host "Project: $($environment.ProjectRoot)"
Write-Host ""

foreach ($check in $checks) {
    $color = switch ($check.status) {
        "ok" { "Green" }
        "warn" { "Yellow" }
        default { "Red" }
    }
    Write-Host ("[{0}] {1}" -f $check.status.ToUpper(), $check.name) -ForegroundColor $color
    Write-Host ("  {0}" -f $check.details)
}

$errors = @($checks | Where-Object { $_.status -eq "error" }).Count
if ($errors -gt 0) {
    Write-Error "Android doctor found $errors blocking issue(s)."
    exit 1
}
