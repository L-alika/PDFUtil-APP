@echo off
chcp 936 >nul
title PDF-APP - 卸载程序
color 0C
setlocal enabledelayedexpansion

:: ==================== 变量 ====================
set "INSTALL_DIR=C:\Program Files\PDFUtil"
set "APP_DIR=PDF-APP"
set "APP_EXE=PDF-APP.exe"
set "LOG_FILE=%TEMP%\PDFUtil_Uninstall_%RANDOM%.log"

:: ==================== 检查管理员权限 ====================
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo ========================================
    echo    PDF-APP - 卸载程序
    echo ========================================
    echo.
    echo [提示] 需要管理员权限，正在请求...
    echo.
    "%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -Command "Start-Process '%~f0' -Verb runAs"
    if %errorlevel% equ 0 (
        exit /b
    ) else (
        echo.
        echo [错误] 权限请求失败
        echo 请右键点击此脚本，选择"以管理员身份运行"
        echo.
        pause
        exit /b 1
    )
)

:: ==================== 日志 ====================
echo 卸载日志: %LOG_FILE% > "%LOG_FILE%"
echo [%date% %time%] 开始卸载 >> "%LOG_FILE%"

:: ==================== 检查安装目录 ====================
if not exist "%INSTALL_DIR%" (
    echo ========================================
    echo    PDF-APP - 卸载程序
    echo ========================================
    echo.
    echo [信息] 未找到安装目录: %INSTALL_DIR%
    echo 应用已被卸载或未安装
    echo.
    pause
    exit /b 0
)

echo ========================================
echo    PDF-APP - 卸载程序
echo ========================================
echo.

:: ==================== 停止进程 ====================
echo [1/5] 检查停止进程...

:: 尝试停止 PDF-APP.exe
tasklist /FI "IMAGENAME eq %APP_EXE%" 2>nul | find /i "%APP_EXE%" >nul
if %errorlevel% equ 0 (
    taskkill /f /im "%APP_EXE%" >nul 2>&1
    echo      已停止应用进程 >> "%LOG_FILE%"
)

:: 尝试停止 Java 后端进程
tasklist /FI "IMAGENAME eq java.exe" 2>nul | find /i "java.exe" >nul
if %errorlevel% equ 0 (
    for /f "tokens=2" %%p in ('tasklist /FI "IMAGENAME eq java.exe" /FO LIST ^| findstr "PID:"') do (
        taskkill /f /pid %%p >nul 2>&1
    )
    echo      已停止 Java 进程 >> "%LOG_FILE%"
)

echo      进程检查完成 >> "%LOG_FILE%"

:: ==================== 删除应用 ====================
echo [2/5] 正在删除应用文件...
if exist "%INSTALL_DIR%\%APP_DIR%" (
    rmdir /s /q "%INSTALL_DIR%\%APP_DIR%" 2>nul
    if exist "%INSTALL_DIR%\%APP_DIR%" (
        echo      应用目录删除失败，可能有文件被占用 >> "%LOG_FILE%"
        echo      [警告] 应用目录删除失败，请关闭程序后重试
    ) else (
        echo      已删除应用 >> "%LOG_FILE%"
    )
) else (
    echo      应用文件不存在 >> "%LOG_FILE%"
)

:: ==================== 删除快捷方式 ====================
echo [3/5] 正在删除快捷方式...
if exist "%PUBLIC%\Desktop\PDFUtil.lnk" del /f /q "%PUBLIC%\Desktop\PDFUtil.lnk" >nul 2>&1
if exist "%USERPROFILE%\Desktop\PDFUtil.lnk" del /f /q "%USERPROFILE%\Desktop\PDFUtil.lnk" >nul 2>&1
if exist "%ProgramData%\Microsoft\Windows\Start Menu\Programs\PDFUtil" rmdir /s /q "%ProgramData%\Microsoft\Windows\Start Menu\Programs\PDFUtil" >nul 2>&1
echo      快捷方式已删除 >> "%LOG_FILE%"

:: ==================== 删除依赖和安装目录 ====================
echo [4/5] 正在删除依赖目录...

:: 删除 Poppler (解压后的目录名是 Release-xxx)
for /d %%P in ("%INSTALL_DIR%\Release-*") do (
    rmdir /s /q "%%P" 2>nul
    echo      已删除 %%~nxP >> "%LOG_FILE%"
)
:: 兼容旧版本的 poppler 目录名
for /d %%P in ("%INSTALL_DIR%\poppler-*") do (
    rmdir /s /q "%%P" 2>nul
    echo      已删除 %%~nxP >> "%LOG_FILE%"
)

:: 删除安装目录
if exist "%INSTALL_DIR%" rmdir "%INSTALL_DIR%" 2>nul
if exist "%INSTALL_DIR%" (
    echo      安装目录未完全删除 >> "%LOG_FILE%"
) else (
    echo      已删除安装目录 >> "%LOG_FILE%"
)

:: 删除用户数据
set "APP_DATA=%APPDATA%\pdfutil-desktop"
if exist "%APP_DATA%" (
    rmdir /s /q "%APP_DATA%" 2>nul
    echo      已删除用户数据 >> "%LOG_FILE%"
)

:: ==================== 清理 PATH ====================
echo [5/5] 正在清理系统 PATH...

:: 使用临时文件获取 PATH
set "TEMP_FILE=%TEMP%\path_old_%RANDOM%.txt"
reg query "HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment" /v Path >"%TEMP_FILE%" 2>nul

:: 读取 PATH（跳过前两行）
set "SYS_PATH="
for /f "skip=2 tokens=2*" %%a in ('type "%TEMP_FILE%"') do set "SYS_PATH=%%b"
del "%TEMP_FILE%" 2>nul

:: 简单清理：移除包含 PDFUtil 的路径项
if defined SYS_PATH (
    set "NEW_PATH="
    for %%A in ("%SYS_PATH:;=" "%") do (
        echo %%~A | findstr /i "PDFUtil" >nul
        if !errorlevel! neq 0 (
            if defined NEW_PATH (
                set "NEW_PATH=!NEW_PATH!;%%~A"
            ) else (
                set "NEW_PATH=%%~A"
            )
        )
    )

    if defined NEW_PATH if not "!NEW_PATH!"=="%SYS_PATH%" (
        reg add "HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment" /v Path /t REG_EXPAND_SZ /d "!NEW_PATH!" /f >nul 2>&1
        echo      已从 PATH 移除 PDFUtil 相关项 >> "%LOG_FILE%"
    ) else (
        echo      PATH 无需更改 >> "%LOG_FILE%"
    )
)

:: ==================== 完成 ====================
echo.
echo ========================================
echo    卸载完成!
echo ========================================
echo.
echo 日志文件: %LOG_FILE%
echo.
echo [提示] 以下系统软件未被卸载:
echo   - Java
echo   - Python
echo   - Ghostscript
echo   - LibreOffice
echo.
echo 如需卸载，请通过 Windows 设置进行操作。
echo.

echo [%date% %time%] 卸载完成 >> "%LOG_FILE%"
pause