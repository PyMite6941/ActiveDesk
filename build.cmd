@echo off
rem Builds dist\activedesk.jar with only the JDK (17+). No Maven/Gradle needed.
setlocal
cd /d "%~dp0"
if exist build rmdir /s /q build
if exist dist rmdir /s /q dist
mkdir build\classes dist
dir /s /b src\main\java\*.java > build\sources.txt
javac --release 17 -encoding UTF-8 -Xlint:all,-serial -d build\classes @build\sources.txt || exit /b 1
xcopy /e /q /y src\main\resources build\classes\ >nul
(echo Main-Class: dev.activedesk.ActiveDesk)> build\manifest.txt
jar --create --file dist\activedesk.jar --manifest build\manifest.txt -C build\classes . || exit /b 1
echo built dist\activedesk.jar
