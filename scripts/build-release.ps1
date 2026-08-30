[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^\d+\.\d+\.\d+(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$')]
    [string]$Version,
    [Parameter(Mandatory)]
    [ValidatePattern('^https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$')]
    [string]$RepositoryUrl,
    [string]$SignCertificateThumbprint,
    [string]$TimestampUrl
)

$arguments = @('clean', 'test', 'buildWindowsInstaller', "-Pversion=$Version", "-PappfleetRepositoryUrl=$RepositoryUrl")
if ($SignCertificateThumbprint) { $arguments += "-PsignCertificateThumbprint=$SignCertificateThumbprint" }
if ($TimestampUrl) { $arguments += "-PtimestampUrl=$TimestampUrl" }

& "$PSScriptRoot\..\gradlew.bat" @arguments
if ($LASTEXITCODE -ne 0) { throw "Windows release build failed with exit code $LASTEXITCODE." }

Write-Host "Release artifacts: $PSScriptRoot\..\dist\release\$Version"

