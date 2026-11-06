@echo off
chcp 65001 >nul
cd /d "%~dp0.."
python tools\mock_dav.py --port 8088
pause
