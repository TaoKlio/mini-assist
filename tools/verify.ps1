<#
.SYNOPSIS
    API contract verification for the drill-output mod.

.DESCRIPTION
    Reflects over every Mindustry / Arc member the mod references and asserts that
    it really exists with a matching signature. This is equivalent to the JVM's
    link-time checks: any missing member would surface in game as
    NoSuchFieldError / NoSuchMethodError. Run this to catch such problems early.

    Verifier source: tools\VerifyApi.java

.NOTES
    Keep this file ASCII-only. Windows PowerShell 5.1 parses .ps1 as ANSI unless the
    file carries a UTF-8 BOM, so non-ASCII text here can break parsing.
    Default execution policy blocks .ps1; pass -ExecutionPolicy Bypass (see .EXAMPLE).

.PARAMETER GradleHome
    GRADLE_USER_HOME. Used to locate core-v160.jar / arc-core-v160.jar and friends.

.PARAMETER JdkHome
    JDK installation containing bin\javac.exe and bin\java.exe.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File tools\verify.ps1
    powershell -NoProfile -ExecutionPolicy Bypass -File tools\verify.ps1 -GradleHome 'D:\path\to\gradle-home'
#>
param(
    [string]$GradleHome = 'D:\桌面\deepseek_home\构建工具\缓存\gradle-home',
    [string]$JdkHome = 'C:\Program Files\Java\jdk-21.0.12.1'
)

# Native tools (java/gradle) print harmless banners to stderr. Windows PowerShell 5.1
# turns any native stderr output into an ErrorRecord, and ErrorActionPreference=Stop
# would abort on it. So keep Continue here and check $LASTEXITCODE by hand instead.
$ErrorActionPreference = 'Continue'
$proj = Split-Path $PSScriptRoot -Parent
$javac = Join-Path $JdkHome 'bin\javac.exe'
$java  = Join-Path $JdkHome 'bin\java.exe'

if(-not (Test-Path -LiteralPath $javac)){ throw "javac not found: $javac (pass -JdkHome)" }

$m2 = Join-Path $GradleHome 'caches\modules-2\files-2.1'
if(-not (Test-Path -LiteralPath $m2)){ throw "dependency cache not found: $m2 (pass -GradleHome)" }

# Put every dependency jar on the classpath (core + arc + transitive deps)
$jars = (Get-ChildItem -Recurse -File $m2 -Filter '*.jar' | Select-Object -ExpandProperty FullName) -join ';'
Write-Host "dependency jars: $(($jars -split ';').Count)"

# Build the mod first so we verify the latest artifact.
# GRADLE_USER_HOME must be set explicitly: otherwise the wrapper tries to write the
# distribution into %USERPROFILE%\.gradle, which may not be writable here.
# JAVA_TOOL_OPTIONS is needed because the workspace path contains non-ASCII characters.
#
# The build goes through tools\build-and-log.cmd, which uses cmd's own file redirection.
# Capturing a native command via a PowerShell pipeline ($out = & gradlew ... 2>&1) hangs
# here: PS 5.1 waits on the inherited pipe handles, and a live Gradle daemon keeps them open.
Write-Host "building mod ..."
$env:GRADLE_USER_HOME = $GradleHome
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8'
$logDir = Join-Path $proj 'build\verify'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null
$buildLog = Join-Path $logDir 'build.log'
Remove-Item -LiteralPath $buildLog -Force -ErrorAction SilentlyContinue

& (Join-Path $PSScriptRoot 'build-and-log.cmd') $proj $buildLog
$buildCode = $LASTEXITCODE
if($buildCode -ne 0){
    Write-Host "--- build.log (tail) ---"
    if(Test-Path -LiteralPath $buildLog){ Get-Content -LiteralPath $buildLog -Tail 30 | ForEach-Object { Write-Host $_ } }
    throw "gradle build failed with exit code $buildCode"
}

$outDir = Join-Path $proj 'build\verify'
New-Item -ItemType Directory -Path $outDir -Force | Out-Null

Write-Host "compiling verifier ..."
$javacOutput = & $javac -encoding UTF-8 -cp $jars -d $outDir (Join-Path $PSScriptRoot 'VerifyApi.java') 2>&1
if($LASTEXITCODE -ne 0){
    $javacOutput | ForEach-Object { Write-Host $_ }
    throw "failed to compile VerifyApi.java"
}

Write-Host "running contract verification ..."
Write-Host ("=" * 56)
$libs = Join-Path $proj 'build\libs'
& $java -cp "$outDir;$libs;$jars" VerifyApi
$code = $LASTEXITCODE
Write-Host ("=" * 56)
exit $code
