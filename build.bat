@echo off
REM ============================================================
REM  Plus Key 姿态自定义模块 —— 一键构建脚本 (Windows)
REM  用法：双击本文件，或在 CMD 中执行 build.bat
REM ============================================================
setlocal enabledelayedexpansion

cd /d "%~dp0"

echo.
echo [1/4] 定位 JDK...

set "JAVA_HOME_FOUND="
for %%P in (
    "C:\Program Files\Android\Android Studio\jbr"
    "%LOCALAPPDATA%\Programs\Android Studio\jbr"
    "C:\Program Files\Android\Android Studio1\jbr"
) do (
    if not defined JAVA_HOME_FOUND (
        if exist "%%~P\bin\java.exe" set "JAVA_HOME_FOUND=%%~P"
    )
)

if defined JAVA_HOME_FOUND (
    set "JAVA_HOME=!JAVA_HOME_FOUND!"
    echo       使用 Android Studio JBR: !JAVA_HOME!
) else (
    if defined JAVA_HOME (
        echo       使用已有 JAVA_HOME: %JAVA_HOME%
    ) else (
        where java >nul 2>nul
        if !errorlevel! equ 0 (
            echo       使用 PATH 中的 java
        ) else (
            echo   [错误] 未找到 JDK。请安装 JDK 21+ 或设置 JAVA_HOME。
            pause
            exit /b 1
        )
    )
)

set "PATH=%JAVA_HOME%\bin;%PATH%"

echo.
echo [2/4] 检查 Gradle Wrapper...
if exist "gradlew.bat" (
    set "GRADLE_CMD=gradlew.bat"
    echo       使用内置 wrapper（Gradle 9.1.0）
) else (
    where gradle >nul 2>nul
    if !errorlevel! equ 0 (
        set "GRADLE_CMD=gradle"
        echo       使用系统 Gradle
    ) else (
        echo   [错误] 既没有 gradlew.bat，也没有系统 gradle。
        pause
        exit /b 1
    )
)

echo.
echo [3/4] 开始构建 Debug APK...
call !GRADLE_CMD! :app:assembleDebug
if !errorlevel! neq 0 (
    echo.
    echo [错误] 构建失败，请检查上方日志。
    pause
    exit /b 1
)

echo.
echo [4/4] 构建完成！
set APK=app\build\outputs\apk\debug\app-debug.apk
if exist "%APK%" (
    echo.
    echo   APK 位置：%CD%\%APK%
    echo.
    echo   安装到手机（需先插上 USB 并授权调试）：
    echo     adb install -r "%APK%"
    echo.
    echo   然后记得在 LSPosed 里启用模块，作用域勾选「系统框架」，重启手机。
) else (
    echo   [警告] 未找到 APK 输出文件。
)

echo.
pause
endlocal
