param(
    [string]$Jdk = $env:JAVA_HOME,
    [string]$Engine = (Join-Path $PSScriptRoot 'libs/desktop.jar')
)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    $buildJava = if ($Jdk) { Join-Path $Jdk 'bin/java.exe' } else { (Get-Command java -ErrorAction Stop).Source }
    & $buildJava 'tools/Build.java' $Engine
    if ($LASTEXITCODE -ne 0) { throw "Build failed: $LASTEXITCODE" }
} finally { Pop-Location }
