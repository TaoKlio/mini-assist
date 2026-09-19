@echo off
REM API contract verification launcher for the drill-output mod.
REM All defaults live in verify.ps1 (which is UTF-8 BOM encoded);
REM this launcher stays pure ASCII so cmd.exe can parse it safely.
REM
REM Optional overrides:  tools\verify.cmd "<GRADLE_USER_HOME>" "<JDK_HOME>"
setlocal

if "%~1"=="" (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0verify.ps1"
) else (
    if "%~2"=="" (
        powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0verify.ps1" -GradleHome "%~1"
    ) else (
        powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0verify.ps1" -GradleHome "%~1" -JdkHome "%~2"
    )
)
exit /b %ERRORLEVEL%
