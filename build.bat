@echo off
echo =========================================
echo Building NeoVoxy Client and Server Jars
echo =========================================
echo Cleaning project...
call gradlew.bat clean
if %ERRORLEVEL% neq 0 goto error

echo Building Unified/Client Jar...
call gradlew.bat build
if %ERRORLEVEL% neq 0 goto error

echo Building Server-Specific Jar...
call gradlew.bat build -PserverBuild
if %ERRORLEVEL% neq 0 goto error

echo =========================================
echo BUILD SUCCESSFUL!
echo Jars are in: build\libs\
echo =========================================
pause
exit /b 0

:error
echo =========================================
echo BUILD FAILED!
echo =========================================
pause
exit /b %ERRORLEVEL%
