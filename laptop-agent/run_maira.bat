@echo off
title MYRA Laptop Agent
cd /d "%~dp0"

python --version >nul 2>&1
if errorlevel 1 (
  echo Python nahi mila!
  echo.
  echo Pehle python.org se Python install karo, aur install ke waqt
  echo "Add python to PATH" wali tick LAZMI lagana.
  echo Phir is file ko dobara double-click karo.
  echo.
  pause
  exit /b 1
)

python -c "import paho.mqtt.client, pyautogui, pygetwindow, PIL" >nul 2>&1
if errorlevel 1 (
  echo Zaroori packages install ho rahe hain — thora wait karo...
  python -m pip install --quiet paho-mqtt pyautogui pygetwindow pillow
)

echo MYRA Laptop Agent shuru ho raha hai...
python maira_agent.py
echo.
echo Agent band ho gaya.
pause
