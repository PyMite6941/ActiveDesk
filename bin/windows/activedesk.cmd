@echo off
rem Launcher for Windows. Optional JDBC drivers go in lib\.
java -cp "%~dp0dist\activedesk.jar;%~dp0lib\*" dev.activedesk.ActiveDesk %*
