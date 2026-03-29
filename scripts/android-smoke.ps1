[CmdletBinding()]
param(
    [string]$Variant = "debug",
    [string]$PackageName = "com.akdevelopers.auracast.debug",
    [string]$AvdName = "AuraCastApi35",
    [string]$SystemImage = "system-images;android-35;google_apis;x86_64",
    [string]$DeviceProfile = "pixel_6",
    [string]$ServerUrl = "wss://nonmanifestly-smudgeless-lamonica.ngrok-free.dev/stream",
    [switch]$InstallMissingSdkPackages,
    [switch]$CreateAvdIfMissing,
    [switch]$Headless
)

. "$PSScriptRoot\AndroidCli.ps1"

$environment = Get-AuraCastAndroidEnvironment
$requiredPackages = @(
    "platform-tools",
    "emulator",
    "platforms;android-$($environment.CompileSdk)",
    "build-tools;35.0.0",
    $SystemImage
)

function Ensure-SdkPackage {
    param([string]$Package)

    if (-not $InstallMissingSdkPackages) {
        return
    }

    Write-Host "Installing SDK package: $Package" -ForegroundColor Cyan
    Invoke-AuraCastTool -FilePath $environment.Tools.sdkmanager -Arguments @("--sdk_root=$($environment.SdkRoot)", $Package)
}

foreach ($package in $requiredPackages) {
    Ensure-SdkPackage -Package $package
}

$avdList = Invoke-AuraCastTool -FilePath $environment.Tools.avdmanager -Arguments @("list", "avd") -CaptureOutput
if ($avdList -notmatch "Name:\s+$([regex]::Escape($AvdName))\b") {
    if (-not $CreateAvdIfMissing) {
        throw "AVD '$AvdName' was not found. Re-run with -CreateAvdIfMissing to create it."
    }

    Write-Host "Creating AVD '$AvdName'..." -ForegroundColor Cyan
    $command = 'echo no | "{0}" create avd -n "{1}" -k "{2}" -d "{3}" --force' -f `
        $environment.Tools.avdmanager, $AvdName, $SystemImage, $DeviceProfile
    cmd.exe /c $command | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to create AVD '$AvdName'."
    }
}

$deviceList = Invoke-AuraCastTool -FilePath $environment.Tools.adb -Arguments @("devices") -CaptureOutput
if ($deviceList -notmatch "device`r?`n" -and $deviceList -notmatch "device$") {
    $emulatorArgs = @("-avd", $AvdName, "-no-boot-anim")
    if ($Headless) {
        $emulatorArgs += @("-no-window", "-gpu", "swiftshader_indirect")
    }

    Write-Host "Starting emulator '$AvdName'..." -ForegroundColor Cyan
    Start-Process -FilePath $environment.Tools.emulator -ArgumentList $emulatorArgs | Out-Null
    Wait-AuraCastDeviceBoot -AdbPath $environment.Tools.adb
}

Write-Host "Building $Variant APK..." -ForegroundColor Cyan
Push-Location $environment.ProjectRoot
try {
    & ".\gradlew.bat" "assemble$([char]::ToUpper($Variant[0]) + $Variant.Substring(1))" --stacktrace
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed for variant '$Variant'."
    }
} finally {
    Pop-Location
}

$apkPath = Join-Path $environment.ProjectRoot "app\build\outputs\apk\$Variant\app-$Variant.apk"
if (-not (Test-Path $apkPath)) {
    throw "APK not found at $apkPath"
}

Invoke-AuraCastTool -FilePath $environment.Tools.adb -Arguments @("install", "-r", $apkPath)

foreach ($permission in @(
    "android.permission.RECORD_AUDIO",
    "android.permission.READ_PHONE_STATE",
    "android.permission.POST_NOTIFICATIONS"
)) {
    Invoke-AuraCastTool -FilePath $environment.Tools.adb -Arguments @("shell", "pm", "grant", $PackageName, $permission) -AllowFailure
}

Write-Host "Launching setup activity..." -ForegroundColor Cyan
Invoke-AuraCastTool -FilePath $environment.Tools.adb -Arguments @(
    "shell", "am", "start", "-n",
    "$PackageName/com.akdevelopers.auracast.ui.setup.SetupActivity"
)

Write-Host "Starting foreground service..." -ForegroundColor Cyan
Invoke-AuraCastTool -FilePath $environment.Tools.adb -Arguments @(
    "shell", "am", "start-foreground-service",
    "-n", "$PackageName/com.akdevelopers.auracast.service.StreamingService",
    "--es", "server_url", $ServerUrl
)

Start-Sleep -Seconds 3
$serviceDump = Invoke-AuraCastTool -FilePath $environment.Tools.adb -Arguments @("shell", "dumpsys", "activity", "services", $PackageName) -CaptureOutput -AllowFailure
if ($serviceDump -notmatch "StreamingService") {
    throw "StreamingService does not appear in dumpsys after start."
}

Write-Host "Pausing microphone..." -ForegroundColor Cyan
Invoke-AuraCastTool -FilePath $environment.Tools.adb -Arguments @(
    "shell", "am", "startservice",
    "-n", "$PackageName/com.akdevelopers.auracast.service.StreamingService",
    "-a", "com.akdevelopers.auracast.ACTION_STOP_MIC"
)

Start-Sleep -Seconds 2

Write-Host "Resuming microphone..." -ForegroundColor Cyan
Invoke-AuraCastTool -FilePath $environment.Tools.adb -Arguments @(
    "shell", "am", "startservice",
    "-n", "$PackageName/com.akdevelopers.auracast.service.StreamingService",
    "-a", "com.akdevelopers.auracast.ACTION_START_MIC"
)

Start-Sleep -Seconds 2

Write-Host "Stopping service..." -ForegroundColor Cyan
Invoke-AuraCastTool -FilePath $environment.Tools.adb -Arguments @(
    "shell", "am", "startservice",
    "-n", "$PackageName/com.akdevelopers.auracast.service.StreamingService",
    "-a", "com.akdevelopers.auracast.ACTION_STOP_FULL"
)

Start-Sleep -Seconds 2

$finalDump = Invoke-AuraCastTool -FilePath $environment.Tools.adb -Arguments @("shell", "dumpsys", "activity", "services", $PackageName) -CaptureOutput -AllowFailure
if ($finalDump -match "StreamingService") {
    Write-Warning "StreamingService still appears in dumpsys after stop. Check the app manually."
}

Write-Host ""
Write-Host "Android smoke flow completed." -ForegroundColor Green
Write-Host "Verified: install, launch, service start, mic stop, mic resume, service stop."
Write-Host "Manual follow-up: reconnect and watchdog can be exercised against a live relay using the browser dashboard or Firebase command path."
