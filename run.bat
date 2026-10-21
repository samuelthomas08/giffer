@echo off
rem Giffer - compiles the sources and starts the app. Needs a JDK 25+ on the PATH.
rem JavaFX is downloaded into .\lib on the first run (see scripts\download-javafx.ps1).
cd /d "%~dp0"

if not exist lib\javafx-controls-25.0.4-win.jar (
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\download-javafx.ps1 || (pause & exit /b 1)
)

if exist out rmdir /s /q out
mkdir out
javac -encoding UTF-8 --module-path lib --add-modules javafx.controls -d out src\giffer\*.java || (pause & exit /b 1)
copy /y res\app.css out\ >nul
start "" javaw --module-path lib --add-modules javafx.controls --enable-native-access=javafx.graphics -Djava.net.useSystemProxies=true -cp out giffer.Main
