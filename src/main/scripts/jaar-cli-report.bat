@echo off
REM ──────────────────────────────────────────────────────────────────
REM  JAAR — JTL AI Analysis & Reporting  (Windows)
REM
REM  Place this script in %JMETER_HOME%\bin\ alongside jmeter.bat.
REM  The plugin JAR must be in %JMETER_HOME%\lib\ext\.
REM
REM  Usage:
REM    jaar-cli-report.bat -i results.jtl --provider groq --config ai-reporter.properties
REM ──────────────────────────────────────────────────────────────────

setlocal

REM Resolve JMETER_HOME from this script's location (bin/)
set "SCRIPT_DIR=%~dp0"
set "JMETER_HOME=%SCRIPT_DIR%.."

REM Verify JMeter installation
if not exist "%JMETER_HOME%\lib" (
    echo ERROR: JMeter lib directory not found at %JMETER_HOME%\lib
    echo        This script must be placed in JMETER_HOME\bin\
    exit /b 1
)

REM Build classpath: plugin JAR + JMeter libs
set "CP=%JMETER_HOME%\lib\ext\*;%JMETER_HOME%\lib\*"

REM Launch CLI
java -cp "%CP%" com.personal.jmeter.cli.Main %*

exit /b %ERRORLEVEL%
