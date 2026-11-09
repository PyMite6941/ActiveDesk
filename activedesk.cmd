@echo off
rem Launcher for Windows (run from the repo after building). Optional JDBC drivers go in lib\.
rem %~sdp0 = this folder's short (8.3) path: plain ASCII, so a folder name in Japanese,
rem Cyrillic etc. can't garble the classpath (Java reads arguments in the ANSI code page).
set "D=%~sdp0"
set "JAR=%D%activedesk.jar"
if not exist "%JAR%" set "JAR=%D%dist\activedesk.jar"
java -cp "%JAR%;%D%lib\*" dev.activedesk.ActiveDesk %*
