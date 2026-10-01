@echo off
REM Server 1.21 needs Java 21+. Uses JAVA_HOME if set, otherwise the JDK 21 provisioned by Gradle.
if defined JAVA_HOME (
    set "JAVA=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA=%USERPROFILE%\.gradle\jdks\jetbrains_s_r_o_-21-amd64-windows.2\bin\java.exe"
)
"%JAVA%" -Xms4G -Xmx4G -jar server.jar nogui
pause
