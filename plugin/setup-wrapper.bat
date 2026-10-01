@echo off
echo === LogicTierPlugin - Gradle Wrapper Setup ===
echo.

REM Check if wrapper JAR already present
if exist "gradle\wrapper\gradle-wrapper.jar" (
    echo [OK] gradle-wrapper.jar already exists.
    goto build
)

REM Try to generate via local gradle
where gradle >nul 2>&1
if %ERRORLEVEL% equ 0 (
    echo [INFO] Found gradle on PATH, generating wrapper...
    gradle wrapper --gradle-version 8.8
    goto build
)

echo [ERROR] gradle not found on PATH and wrapper JAR is missing.
echo.
echo Please do ONE of the following:
echo   1. Install Gradle 8.8 from https://gradle.org/releases/
echo      then run: gradle wrapper --gradle-version 8.8
echo   2. Copy gradle-wrapper.jar from any Gradle project into:
echo      plugin\gradle\wrapper\gradle-wrapper.jar
echo.
pause
exit /b 1

:build
echo.
echo [INFO] Building plugin...
call gradlew.bat shadowJar
if %ERRORLEVEL% equ 0 (
    echo.
    echo [SUCCESS] Build complete!
    echo Output: build\libs\LogicTierPlugin-1.0.0-SNAPSHOT.jar
    echo.
    echo Copy the JAR to: ..\plugins\
    xcopy /Y "build\libs\LogicTierPlugin-1.0.0-SNAPSHOT.jar" "..\plugins\" >nul 2>&1
    echo [INFO] Copied to server plugins folder.
) else (
    echo [FAILED] Build failed. Check errors above.
)
pause
