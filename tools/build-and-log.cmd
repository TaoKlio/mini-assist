@echo off
REM Builds the mod and redirects all output to the given log file.
REM Used by verify.ps1: cmd's own file redirection does not depend on inherited pipe
REM handles, so it cannot hang waiting on a Gradle daemon that keeps them open.
REM
REM Usage: build-and-log.cmd <project-dir> <log-file>
setlocal

set "PROJ=%~1"
set "LOG=%~2"

pushd "%PROJ%"
call gradlew.bat jar --console=plain > "%LOG%" 2>&1
set "CODE=%ERRORLEVEL%"
popd

exit /b %CODE%
