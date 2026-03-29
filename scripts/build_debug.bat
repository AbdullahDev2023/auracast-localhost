@echo off
setlocal
set "JAVA_HOME=C:\jbr21"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
cd /d "%~dp0.."
echo JAVA_HOME=%JAVA_HOME%
java -version 2>&1
echo.
echo === Running assembleDebug ===
call gradlew.bat assembleDebug
echo.
echo === Exit code: %ERRORLEVEL% ===
if %ERRORLEVEL% NEQ 0 (
    echo BUILD FAILED — check output above.
    echo.
    set /p "dummy=Press Enter to close... "
    exit /b %ERRORLEVEL%
)
echo.
echo === APK: app\build\outputs\apk\debug\app-debug.apk ===
echo.
set /p "dummy=Press Enter to close... "
endlocal
