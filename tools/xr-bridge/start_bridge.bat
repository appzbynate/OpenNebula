@echo off
title OpenNebula XR Bridge
cd /d "%~dp0"
echo.
echo  OpenNebula XR Bridge
echo  ====================
echo.

REM Use local venv so mss/pillow are always available
if not exist "%~dp0.venv\Scripts\python.exe" (
  echo Creating .venv …
  where py >nul 2>&1 && py -3 -m venv "%~dp0.venv"
  if not exist "%~dp0.venv\Scripts\python.exe" if exist "%USERPROFILE%\.local\bin\python3.11.exe" "%USERPROFILE%\.local\bin\python3.11.exe" -m venv "%~dp0.venv"
  if not exist "%~dp0.venv\Scripts\python.exe" (
    echo ERROR: Could not create venv. Install Python 3 from python.org.
    pause
    exit /b 1
  )
)

set PY="%~dp0.venv\Scripts\python.exe"
echo Using: %PY%
%PY% -m pip install -q mss pillow
%PY% -c "import mss,PIL; print('  desktop capture: OK')" 2>nul || echo   desktop capture: FAILED

echo.
echo  Close any old bridge window first, then wait for "TCP listening".
echo.
%PY% "%~dp0xr_bridge.py" %*
echo.
echo Bridge stopped.
pause
