[CmdletBinding()]
param(
    [string]$SourceRoot = ".secrets",
    [switch]$SkipAndroid,
    [switch]$SkipServer
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$secretRoot = if ([System.IO.Path]::IsPathRooted($SourceRoot)) {
    $SourceRoot
} else {
    Join-Path $projectRoot $SourceRoot
}

if (-not (Test-Path $secretRoot)) {
    throw "Secret source directory not found: $secretRoot"
}

function Copy-SecretFile {
    param(
        [string]$SourcePath,
        [string]$DestinationPath
    )

    if (-not (Test-Path $SourcePath)) {
        Write-Warning "Missing secret file: $SourcePath"
        return
    }

    $destinationDir = Split-Path -Path $DestinationPath -Parent
    if (-not (Test-Path $destinationDir)) {
        New-Item -ItemType Directory -Path $destinationDir -Force | Out-Null
    }

    Copy-Item -Path $SourcePath -Destination $DestinationPath -Force
    Write-Host "Synced $(Split-Path $DestinationPath -Leaf)" -ForegroundColor Green
}

if (-not $SkipAndroid) {
    Copy-SecretFile `
        -SourcePath (Join-Path $secretRoot "android\google-services.release.json") `
        -DestinationPath (Join-Path $projectRoot "app\google-services.json")

    Copy-SecretFile `
        -SourcePath (Join-Path $secretRoot "android\google-services.debug.json") `
        -DestinationPath (Join-Path $projectRoot "app\src\debug\google-services.json")
}

if (-not $SkipServer) {
    Copy-SecretFile `
        -SourcePath (Join-Path $secretRoot "server\serviceAccount.json") `
        -DestinationPath (Join-Path $projectRoot "server\.local\serviceAccount.json")
}
