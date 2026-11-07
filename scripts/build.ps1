# Builds the release APK inside Docker and copies it to dist\tv-ambient-<version>.apk (Windows PowerShell).
#
#   .\scripts\build.ps1
#   .\scripts\build.ps1 -KeystoreDir C:\path\to\keys     # sign with keys\keystore.properties (see README)
param(
    [string]$KeystoreDir = "",
    [string]$Image = "tv-ambient-build",
    [string]$Volume = "tv-ambient-gradle"
)
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw "Docker is not installed or not on PATH." }

docker image inspect $Image *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host "Building the $Image image once..."
    docker build --platform linux/amd64 -t $Image (Join-Path $Root "docker")
    if ($LASTEXITCODE -ne 0) { throw "docker build failed" }
}

$dockerArgs = @("run", "--rm", "--platform", "linux/amd64", "-v", "${Root}:/src", "-v", "${Volume}:/gradle",
    "-w", "/src", "-e", "ANDROID_USER_HOME=/gradle/.android")
$gradleArgs = @("--no-daemon", "--console=plain", "assembleRelease")
if ($KeystoreDir) {
    if (-not (Test-Path (Join-Path $KeystoreDir "keystore.properties"))) { throw "No keystore.properties in $KeystoreDir" }
    $dockerArgs += @("-v", "$((Resolve-Path $KeystoreDir).Path):/keys:ro")
    $gradleArgs += "-PkeystoreProperties=/keys/keystore.properties"
    Write-Host "Signing with $KeystoreDir\keystore.properties"
} else {
    Write-Host "No -KeystoreDir: the APK is signed with the build container's debug key."
}

& docker @dockerArgs $Image sh ./gradlew @gradleArgs
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }

$version = (Select-String -Path (Join-Path $Root "app\build.gradle") -Pattern "versionName '(.+)'").Matches[0].Groups[1].Value
New-Item -ItemType Directory -Force (Join-Path $Root "dist") | Out-Null
Copy-Item (Join-Path $Root "app\build\outputs\apk\release\app-release.apk") (Join-Path $Root "dist\tv-ambient-$version.apk") -Force
Write-Host "Built dist\tv-ambient-$version.apk"
