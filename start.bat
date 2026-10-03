@echo off
REM Server 1.21 needs Java 21+. Uses JAVA_HOME if set, otherwise the JDK 21 provisioned by Gradle.
REM Always run from this script's folder, so it works from any terminal or shortcut.
cd /d "%~dp0"

if defined JAVA_HOME (
    set "JAVA=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA=%USERPROFILE%\.gradle\jdks\jetbrains_s_r_o_-21-amd64-windows.2\bin\java.exe"
)

if not exist "%JAVA%" (
    echo [ERROR] Java not found: %JAVA%
    echo Install Java 21+ and set JAVA_HOME, or build the plugin once with plugin\gradlew.bat to download it.
    pause
    exit /b 1
)
if not exist "server.jar" (
    echo [ERROR] server.jar not found in %CD%
    pause
    exit /b 1
)

"%JAVA%" -Xms4G -Xmx4G -jar server.jar nogui
echo.
echo Server stopped (exit code %ERRORLEVEL%).
pause
