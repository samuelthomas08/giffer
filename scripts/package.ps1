# Builds Giffer into a self-contained Windows app: dist\Giffer\Giffer.exe (with its own Java runtime and
# JavaFX) and dist\Giffer-windows-x64.zip. Needs a JDK 25+ (javac, jar, jpackage) on the PATH.
#
#   .\scripts\package.ps1                  # version 1.0.0
#   .\scripts\package.ps1 -Version 1.2.3   # the GitHub workflow passes 1.0.<run number>
param(
    [string]$Version = '1.0.0'
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $projectRoot

function Assert-Success([string]$step) {
    if ($LASTEXITCODE -ne 0) { throw "$step failed (exit code $LASTEXITCODE)" }
}

& (Join-Path $PSScriptRoot 'download-javafx.ps1')

$buildDirectory = Join-Path $projectRoot 'build'
$classesDirectory = Join-Path $buildDirectory 'classes'
$inputDirectory = Join-Path $buildDirectory 'input'
$distDirectory = Join-Path $projectRoot 'dist'
Remove-Item $buildDirectory, $distDirectory -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classesDirectory, $inputDirectory, $distDirectory | Out-Null

# 1) compile (javac reads the file list from an argument file; forward slashes avoid escaping problems)
$sourceListFile = Join-Path $buildDirectory 'sources.txt'
Get-ChildItem (Join-Path $projectRoot 'src') -Recurse -Filter '*.java' |
    ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' } |
    Set-Content -Path $sourceListFile -Encoding ASCII
javac -encoding UTF-8 --module-path lib --add-modules javafx.controls -d $classesDirectory "@$sourceListFile"
Assert-Success 'javac'
Copy-Item (Join-Path $projectRoot 'res\app.css') $classesDirectory

# 2) jar: the application classes plus the stylesheet
jar --create --file (Join-Path $inputDirectory 'giffer.jar') --main-class giffer.Main -C $classesDirectory .
Assert-Success 'jar'
Copy-Item (Join-Path $projectRoot 'lib\*.jar') $inputDirectory

# 3) jpackage: app image with a trimmed Java runtime (only the modules Giffer and JavaFX use)
jpackage --type app-image `
    --name Giffer `
    --app-version $Version `
    --vendor 'Giffer' `
    --description 'Collect GIFs, let Gemini describe them, find the right one by keyword.' `
    --icon (Join-Path $projectRoot 'packaging\giffer.ico') `
    --input $inputDirectory `
    --main-jar giffer.jar `
    --main-class giffer.Main `
    --add-modules java.desktop,java.logging,java.xml,java.naming,jdk.unsupported,jdk.jfr,jdk.crypto.mscapi `
    --java-options '--enable-native-access=ALL-UNNAMED' `
    --java-options '-Djava.net.useSystemProxies=true' `
    --dest $distDirectory
Assert-Success 'jpackage'

# 4) zip for download
$zipFile = Join-Path $distDirectory 'Giffer-windows-x64.zip'
Compress-Archive -Path (Join-Path $distDirectory 'Giffer') -DestinationPath $zipFile -Force
Write-Host ''
Write-Host "Done: $zipFile ($([math]::Round((Get-Item $zipFile).Length / 1MB, 1)) MB)"
