@echo off
setlocal DisableDelayedExpansion
cd /d "%~dp0"
set "RECORDER_PY=%~dp0.tools\backup-venv\Scripts\python.exe"
if not exist "%RECORDER_PY%" set "RECORDER_PY=%USERPROFILE%\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"
if not exist "%RECORDER_PY%" (
  echo Python runtime missing. See docs\STANDALONE_BACKUP.md.
  if not defined RECORDER_NO_PAUSE pause
  exit /b 1
)
"%RECORDER_PY%" -u "%~dp0tools\recorder_backup.py" %*
set "RECORDER_EXIT=%ERRORLEVEL%"
echo.
if "%RECORDER_EXIT%"=="0" (echo Backup check finished.) else (echo Some work is pending. The reason is shown above; rerun this file to resume.)
if not defined RECORDER_NO_PAUSE pause
exit /b %RECORDER_EXIT%
