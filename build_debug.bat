@echo off
set JAVA_HOME=C:\PROGRA~1\Android\ANDROI~1\jbr
set PATH=%JAVA_HOME%\bin;%PATH%
set ADB=C:\Users\marsh\AppData\Local\Android\Sdk\platform-tools\adb.exe
set APK=C:\Users\marsh\AndroidStudioProjects\MyWeather\app\build\outputs\apk\debug\app-debug.apk
set PHONE=R5CX131XXXF

cd /d C:\Users\marsh\AndroidStudioProjects\MyWeather

echo Building...
call C:\Users\marsh\AndroidStudioProjects\MyWeather\gradlew.bat assembleDebug
if %ERRORLEVEL% neq 0 (
    echo BUILD FAILED
    exit /b 1
)

echo Installing on phone...
%ADB% -s %PHONE% install -r -t "%APK%"
if %ERRORLEVEL% neq 0 (
    echo INSTALL FAILED - is your phone connected with USB debugging on?
    exit /b 1
)

echo Done! App installed successfully.
