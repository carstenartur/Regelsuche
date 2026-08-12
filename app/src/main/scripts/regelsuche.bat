@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
java %JAVA_OPTS% -cp "%SCRIPT_DIR%..\regelsuche.jar;%SCRIPT_DIR%..\lib\*" de.regelsuche.App %*
