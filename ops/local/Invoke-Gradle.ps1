#Requires -Version 7
<#
.SYNOPSIS
Runs the Gradle wrapper with the JDK's Unix-domain sockets in a short directory.

.DESCRIPTION
On Windows, Java 25 creates Unix-domain sockets under %TEMP%, which fails in some agent shells:
"A new daemon was started but could not be connected to." This sets JAVA_TOOL_OPTIONS for the
Gradle child process only, so the daemon and its forked test JVMs inherit it. The directory is
fixed so later runs reuse compatible daemons. The caller's environment is unchanged.

.EXAMPLE
.\ops\local\Invoke-Gradle.ps1 :website:check :cbell-lib:check :website:bootJar
#>
param([Parameter(ValueFromRemainingArguments)][string[]]$GradleArguments)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'CandidateRuntime.psm1') -Force

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$socketDirectory = Join-Path $env:SystemDrive 'Temp\jdk-unix-sockets'
New-Item -ItemType Directory -Path $socketDirectory -Force | Out-Null
$toolOptions = Get-JdkSocketToolOptions -ExistingOptions $env:JAVA_TOOL_OPTIONS -SocketDirectory $socketDirectory

Push-Location $repositoryRoot
try {
    Invoke-WithEnvironment -Variables @{ JAVA_TOOL_OPTIONS = $toolOptions } -Action {
        & (Join-Path $repositoryRoot 'gradlew.bat') @GradleArguments
    }
} finally {
    Pop-Location
}
exit $LASTEXITCODE
