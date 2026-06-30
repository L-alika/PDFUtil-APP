@echo off
chcp 65001 >nul
echo.
echo ========================================
echo   格式转换工具 - 授权码生成器
echo ========================================
echo.

REM 设置JDK 11路径
set "JAVA11_HOME=C:\Program Files\PDFUtil\jdk-11.0.30.7-hotspot"
set "JAVA11_BIN=%JAVA11_HOME%\bin"

REM 检查JDK 11是否存在
if not exist "%JAVA11_BIN%\java.exe" (
    echo [错误] 未找到JDK 11: %JAVA11_BIN%
    echo 请检查JDK 11安装路径
    pause
    exit /b 1
)

echo [信息] 使用JDK 11: %JAVA11_BIN%

REM 清理旧的编译文件（避免Java版本不兼容）
if exist "LicenseGenerator.class" (
    echo [信息] 检测到旧的编译文件，正在清理并重新编译...
    del "LicenseGenerator.class"
)

echo [信息] 正在编译授权码生成器...
"%JAVA11_BIN%\javac" -encoding UTF-8 LicenseGenerator.java
if errorlevel 1 (
    echo [错误] 编译失败
    pause
    exit /b 1
)
echo [成功] 编译完成
echo.

REM 运行授权码生成器
echo 正在启动授权码生成器...
echo.
"%JAVA11_BIN%\java" LicenseGenerator

echo.
pause
