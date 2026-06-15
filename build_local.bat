@echo off
setlocal enabledelayedexpansion

cd /d "%~dp0"

echo === HomeOS Android Local Builder ===

where java >nul 2>&1
if errorlevel 1 (
  echo ERROR: Java not found in PATH.
  exit /b 1
)

for /f "tokens=*" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set JAVA_VER_LINE=%%v
echo Detected: %JAVA_VER_LINE%

if not exist "local.properties" (
  echo.
  echo WARNING: local.properties missing. Create it from local.properties.example
  echo          and set sdk.dir to your Android SDK path.
  echo.
)

set "JDK17="
for %%d in (
  "C:\Program Files\Eclipse Adoptium\jdk-17*"
  "C:\Program Files\Java\jdk-17*"
  "C:\Program Files\Microsoft\jdk-17*"
  "%USERPROFILE%\.jdks\ms-17*"
  "%USERPROFILE%\.jdks\temurin-17*"
) do (
  for /f "delims=" %%p in ('dir /b /ad "%%~d" 2^>nul') do set "JDK17=%%~dp\%%p"
)

if defined JDK17 (
  echo Using JDK 17: !JDK17!
  set "JAVA_HOME=!JDK17!"
  set "PATH=!JDK17!\bin;!PATH!"
) else (
  echo WARNING: JDK 17 not found. Gradle may fail on Java 25+.
  echo Install: winget install EclipseAdoptium.Temurin.17.JDK
)

call gradlew.bat clean assembleDebug --no-daemon
if errorlevel 1 exit /b 1

if exist "app\build\outputs\apk\debug\app-debug.apk" (
  if not exist "outputs\apk" mkdir "outputs\apk"
  for /f "usebackq tokens=*" %%v in (`powershell -NoProfile -Command "(Select-String -Path 'app/build.gradle.kts' -Pattern 'versionName\s*=\s*\"([^\"]+)\"').Matches.Groups[1].Value"`) do set VERSION=%%v
  set TIMESTAMP=%date:~0,4%%date:~5,2%%date:~8,2%_%time:~0,2%%time:~3,2%%time:~6,2%
  set TIMESTAMP=!TIMESTAMP: =0!
  set OUT=outputs\apk\HomeOS-v!VERSION!-!TIMESTAMP!.apk
  copy /y "app\build\outputs\apk\debug\app-debug.apk" "!OUT!" >nul
  echo.
  echo Build success: !OUT!
)

endlocal
