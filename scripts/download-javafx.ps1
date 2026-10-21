# Downloads the three JavaFX jars Giffer needs into .\lib (skips files that are already there and intact).
#
# Source: Maven Central (org.openjfx). Every file is checked against a pinned SHA-1 checksum, so a
# tampered or truncated download is rejected. To update JavaFX, change $version and the checksums
# (Maven Central publishes them next to each jar as <file>.sha1).
param(
    [string]$Destination = (Join-Path $PSScriptRoot '..\lib')
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # the progress bar makes Invoke-WebRequest very slow on Windows PowerShell 5.1

$version = '25.0.4'
$artifacts = @(
    @{ Name = 'javafx-base';     Sha1 = '791f6ac6c29cfed6e7f9c70d6a245cfbd9df97eb' },
    @{ Name = 'javafx-graphics'; Sha1 = '835409bcf9590b87b62f6e5de0201b862bafb117' },
    @{ Name = 'javafx-controls'; Sha1 = 'caabf8244ed697e47c5489d585dd621f96aa3de0' }
)

New-Item -ItemType Directory -Force -Path $Destination | Out-Null

foreach ($artifact in $artifacts) {
    $fileName = "$($artifact.Name)-$version-win.jar"
    $targetFile = Join-Path $Destination $fileName

    if ((Test-Path $targetFile) -and ((Get-FileHash $targetFile -Algorithm SHA1).Hash -eq $artifact.Sha1)) {
        Write-Host "ok          $fileName"
        continue
    }

    $url = "https://repo1.maven.org/maven2/org/openjfx/$($artifact.Name)/$version/$fileName"
    Write-Host "downloading $url"
    Invoke-WebRequest -Uri $url -OutFile $targetFile -UseBasicParsing

    $actualSha1 = (Get-FileHash $targetFile -Algorithm SHA1).Hash
    if ($actualSha1 -ne $artifact.Sha1) {
        Remove-Item $targetFile -Force
        throw "Checksum mismatch for $fileName (expected $($artifact.Sha1), got $actualSha1). The file was deleted."
    }
}
