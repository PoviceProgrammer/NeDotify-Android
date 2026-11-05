@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-emulator.ps1"
if errorlevel 1 pause
