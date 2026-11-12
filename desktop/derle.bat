@echo off
rem ============================================================
rem  ViDDoWN - Video Downloader - Windows icin tek tikla derleme
rem  viddown.py ve derle.py ayni klasorde olmalidir.
rem ============================================================
cd /d "%~dp0"
title ViDDoWN - Video Downloader - Derleme

set "PYCMD="
py -3 --version >nul 2>&1 && set "PYCMD=py -3"
if not defined PYCMD (
    python --version >nul 2>&1 && set "PYCMD=python"
)
if not defined PYCMD (
    echo.
    echo HATA: Python bulunamadi. https://www.python.org/downloads/ adresinden
    echo Python 3.10+ kurun ^(kurulumda "Add Python to PATH" kutusunu isaretleyin^).
    echo.
    pause
    exit /b 1
)

%PYCMD% derle.py
if errorlevel 1 (
    echo.
    echo Derleme basarisiz oldu.
    pause
    exit /b 1
)

echo.
echo Cikti klasoru aciliyor...
start "" "%~dp0derlenmis"
pause
