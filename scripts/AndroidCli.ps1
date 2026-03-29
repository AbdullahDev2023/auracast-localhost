Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-AuraCastProjectRoot {
    return (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
}

function Convert-AuraCastLocalPropertiesValue {
    param([Parameter(Mandatory = $true)][string]$Value)

    return $Value `
        -replace "\\:", ":" `
        -replace "\\\\", "\"
}

function Get-AuraCastAndroidSdkRoot {
    param([string]$ProjectRoot = (Get-AuraCastProjectRoot))

    foreach ($candidate in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if ($candidate -and (Test-Path $candidate)) {
            return (Resolve-Path $candidate).Path
        }
    }

    $localProperties = Join-Path $ProjectRoot "local.properties"
    if (Test-Path $localProperties) {
        foreach ($line in Get-Content -Path $localProperties) {
            if ($line -match "^sdk\.dir=(.+)$") {
                $sdkDir = Convert-AuraCastLocalPropertiesValue -Value $Matches[1]
                if (Test-Path $sdkDir) {
                    return (Resolve-Path $sdkDir).Path
                }
            }
        }
    }

    throw "Unable to resolve Android SDK root. Set ANDROID_SDK_ROOT or add sdk.dir to local.properties."
}

function Get-AuraCastJavaHome {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        return (Resolve-Path $env:JAVA_HOME).Path
    }

    $studioCandidates = @(
        "$env:ProgramFiles\Android\Android Studio1\jbr",
        "$env:ProgramFiles\Android\Android Studio\jbr",
        "$env:ProgramFiles\Android\Android Studio Preview\jbr"
    ) | Where-Object { $_ -and (Test-Path (Join-Path $_ "bin\java.exe")) }

    if ($studioCandidates.Count -gt 0) {
        $bestCandidate = $studioCandidates |
            Sort-Object -Descending -Property {
                $releaseFile = Join-Path $_ "release"
                if (Test-Path $releaseFile) {
                    $javaVersionLine = Get-Content -Path $releaseFile | Where-Object { $_ -match '^JAVA_VERSION=' } | Select-Object -First 1
                    if ($javaVersionLine -match '"(\d+)') {
                        return [int]$Matches[1]
                    }
                }
                return 0
            } |
            Select-Object -First 1

        return (Resolve-Path $bestCandidate).Path
    }

    throw "Unable to resolve JAVA_HOME. Set JAVA_HOME or install Android Studio with a bundled JBR."
}

function Use-AuraCastJavaHome {
    param([Parameter(Mandatory = $true)][string]$JavaHome)

    $env:JAVA_HOME = $JavaHome
    $javaBin = Join-Path $JavaHome "bin"
    if ($env:Path -notlike "$javaBin*") {
        $env:Path = "$javaBin;$env:Path"
    }
}

function Resolve-AuraCastToolPath {
    param(
        [Parameter(Mandatory = $true)][string]$SdkRoot,
        [Parameter(Mandatory = $true)][ValidateSet("sdkmanager", "avdmanager", "adb", "emulator")]
        [string]$ToolName
    )

    switch ($ToolName) {
        "sdkmanager" {
            $candidates = @(
                (Join-Path $SdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"),
                (Join-Path $SdkRoot "cmdline-tools\bin\sdkmanager.bat")
            )
            if (Test-Path (Join-Path $SdkRoot "cmdline-tools")) {
                $candidates += Get-ChildItem -Path (Join-Path $SdkRoot "cmdline-tools") -Directory -ErrorAction SilentlyContinue |
                    Sort-Object Name -Descending |
                    ForEach-Object { Join-Path $_.FullName "bin\sdkmanager.bat" }
            }
        }
        "avdmanager" {
            $candidates = @(
                (Join-Path $SdkRoot "cmdline-tools\latest\bin\avdmanager.bat"),
                (Join-Path $SdkRoot "cmdline-tools\bin\avdmanager.bat")
            )
            if (Test-Path (Join-Path $SdkRoot "cmdline-tools")) {
                $candidates += Get-ChildItem -Path (Join-Path $SdkRoot "cmdline-tools") -Directory -ErrorAction SilentlyContinue |
                    Sort-Object Name -Descending |
                    ForEach-Object { Join-Path $_.FullName "bin\avdmanager.bat" }
            }
        }
        "adb" {
            $candidates = @((Join-Path $SdkRoot "platform-tools\adb.exe"))
        }
        "emulator" {
            $candidates = @((Join-Path $SdkRoot "emulator\emulator.exe"))
        }
    }

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) {
            return (Resolve-Path $candidate).Path
        }
    }

    throw "Unable to resolve Android SDK tool '$ToolName' under $SdkRoot"
}

function Get-AuraCastCompileSdk {
    param([string]$ProjectRoot = (Get-AuraCastProjectRoot))

    $buildFile = Join-Path $ProjectRoot "app\build.gradle.kts"
    foreach ($line in Get-Content -Path $buildFile) {
        if ($line -match "compileSdk\s*=\s*(\d+)") {
            return [int]$Matches[1]
        }
    }

    throw "Unable to determine compileSdk from $buildFile"
}

function Get-AuraCastAndroidEnvironment {
    param([string]$ProjectRoot = (Get-AuraCastProjectRoot))

    $sdkRoot = Get-AuraCastAndroidSdkRoot -ProjectRoot $ProjectRoot
    $javaHome = Get-AuraCastJavaHome
    Use-AuraCastJavaHome -JavaHome $javaHome

    return [pscustomobject]@{
        ProjectRoot = $ProjectRoot
        SdkRoot = $sdkRoot
        JavaHome = $javaHome
        CompileSdk = Get-AuraCastCompileSdk -ProjectRoot $ProjectRoot
        Tools = [pscustomobject]@{
            sdkmanager = Resolve-AuraCastToolPath -SdkRoot $sdkRoot -ToolName "sdkmanager"
            avdmanager = Resolve-AuraCastToolPath -SdkRoot $sdkRoot -ToolName "avdmanager"
            adb = Resolve-AuraCastToolPath -SdkRoot $sdkRoot -ToolName "adb"
            emulator = Resolve-AuraCastToolPath -SdkRoot $sdkRoot -ToolName "emulator"
        }
    }
}

function Invoke-AuraCastTool {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @(),
        [switch]$AllowFailure,
        [switch]$CaptureOutput
    )

    $quoteArgument = {
        param([string]$Argument)

        if ($Argument -match '[\s"]') {
            return '"' + ($Argument -replace '"', '\"') + '"'
        }
        return $Argument
    }

    $isBatchFile = $FilePath.EndsWith(".bat", [System.StringComparison]::OrdinalIgnoreCase) -or
        $FilePath.EndsWith(".cmd", [System.StringComparison]::OrdinalIgnoreCase)

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.CreateNoWindow = $true

    if ($isBatchFile) {
        $startInfo.FileName = "cmd.exe"
        $quotedCommand = '"' + $FilePath + '"'
        $quotedArgs = ($Arguments | ForEach-Object { & $quoteArgument $_ }) -join " "
        $startInfo.Arguments = "/d /c $quotedCommand $quotedArgs"
    } else {
        $startInfo.FileName = $FilePath
        $startInfo.Arguments = ($Arguments | ForEach-Object { & $quoteArgument $_ }) -join " "
    }

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    $null = $process.Start()

    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()

    $output = (@($stdout, $stderr) | Where-Object { $_ } | ForEach-Object { $_.TrimEnd() }) -join [Environment]::NewLine

    if ($process.ExitCode -ne 0 -and -not $AllowFailure) {
        throw "Command failed: $FilePath $($Arguments -join ' ')`n$output"
    }

    if ($CaptureOutput) {
        return $output.Trim()
    }

    if ($output) {
        Write-Host $output
    }
}

function Get-AuraCastSdkPackages {
    param([Parameter(Mandatory = $true)][string]$SdkRoot)

    return [pscustomobject]@{
        Platforms = @(Get-ChildItem -Path (Join-Path $SdkRoot "platforms") -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name)
        BuildTools = @(Get-ChildItem -Path (Join-Path $SdkRoot "build-tools") -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name)
        SystemImages = @(Get-ChildItem -Path (Join-Path $SdkRoot "system-images") -Recurse -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
    }
}

function Get-AuraCastSdkBackupEntries {
    param([Parameter(Mandatory = $true)][string]$SdkRoot)

    return ,@(Get-ChildItem -Path (Join-Path $SdkRoot "platforms") -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "*.bak" } |
        Select-Object -ExpandProperty Name)
}

function Wait-AuraCastDeviceBoot {
    param(
        [Parameter(Mandatory = $true)][string]$AdbPath,
        [int]$TimeoutSeconds = 240
    )

    Invoke-AuraCastTool -FilePath $AdbPath -Arguments @("wait-for-device")

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $bootCompleted = Invoke-AuraCastTool -FilePath $AdbPath -Arguments @("shell", "getprop", "sys.boot_completed") -AllowFailure -CaptureOutput
        if ($bootCompleted.Trim() -eq "1") {
            return
        }
        Start-Sleep -Seconds 5
    }

    throw "Timed out waiting for an Android device/emulator to finish booting."
}
