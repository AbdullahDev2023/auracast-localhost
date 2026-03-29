@echo off
set "JAVA_HOME=C:\Program Files\Android\Android Studio1\jbr"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d C:\Users\lapto\StudioProjects\AuraCast
echo Stopping all Gradle daemons...
call gradlew.bat --stop
echo Done. Exit: %ERRORLEVEL%
