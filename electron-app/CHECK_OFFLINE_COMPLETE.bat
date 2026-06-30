@echo off
chcp 65001 >nul
echo ========================================
echo    离线安装包完整性检查
echo ========================================
echo.

set "EMBEDDED_DIR=%~dp0embedded"
set "DEPS_DIR=%~dp0deploy-package"
set "MISSING_COUNT=0"

echo [1/2] 检查系统安装包...
echo.

:: Visual C++ Runtime
if exist "%EMBEDDED_DIR%\06_VC_redist.x64.exe" (
    echo [OK] Visual C++ Runtime
) else (
    echo [MISSING] Visual C++ Runtime
    set /a MISSING_COUNT+=1
)

:: Java 11
if exist "%EMBEDDED_DIR%\01_jdk-11.0.30.7-hotspot.zip" (
    echo [OK] Java 11 JDK
) else (
    echo [MISSING] Java 11 JDK
    set /a MISSING_COUNT+=1
)

:: Python 3.10
if exist "%EMBEDDED_DIR%\02_python-3.10.9-amd64.exe" (
    echo [OK] Python 3.10.9
) else (
    echo [MISSING] Python 3.10.9
    set /a MISSING_COUNT+=1
)

:: Ghostscript
if exist "%EMBEDDED_DIR%\03_gs10060w64.exe" (
    echo [OK] Ghostscript 10.06.0
) else (
    echo [MISSING] Ghostscript
    set /a MISSING_COUNT+=1
)

:: Poppler
if exist "%EMBEDDED_DIR%\04_Release-25.12.0-0.zip" (
    echo [OK] Poppler 25.12.0
) else (
    echo [MISSING] Poppler
    set /a MISSING_COUNT+=1
)

:: LibreOffice
if exist "%EMBEDDED_DIR%\05_LibreOffice_26.2.1_Win_x86-64.msi" (
    echo [OK] LibreOffice 26.2.1
) else (
    echo [MISSING] LibreOffice
    set /a MISSING_COUNT+=1
)

echo.
echo [2/2] 检查Python依赖包...
echo.

if exist "%DEPS_DIR%" (
    dir /b "%DEPS_DIR%\*.whl" 2>nul | find /c ".whl" >temp_count.txt
    set /p WHEEL_COUNT=<temp_count.txt
    del temp_count.txt

    if !WHEEL_COUNT! GEQ 100 (
        echo [OK] Python依赖包 (!WHEEL_COUNT! 个wheel文件)
    ) else (
        echo [WARN] Python依赖包数量较少 (!WHEEL_COUNT! 个)
    )

    :: 检查关键包
    echo.
    echo 关键Python包检查:

    if exist "%DEPS_DIR%\PyMuPDF-*.whl" (
        echo [OK] PyMuPDF (自适应DPI)
    ) else (
        echo [MISSING] PyMuPDF
        set /a MISSING_COUNT+=1
    )

    if exist "%DEPS_DIR%\rapidocr_onnxruntime-*.whl" (
        echo [OK] RapidOCR
    ) else (
        echo [MISSING] RapidOCR
        set /a MISSING_COUNT+=1
    )

    if exist "%DEPS_DIR%\pdf2image-*.whl" (
        echo [OK] pdf2image
    ) else (
        echo [MISSING] pdf2image
        set /a MISSING_COUNT+=1
    )
) else (
    echo [MISSING] Python依赖包目录不存在
    set /a MISSING_COUNT+=1
)

echo.
echo ========================================
echo    检查结果
echo ========================================
echo.

if %MISSING_COUNT% EQU 0 (
    echo [SUCCESS] 所有离线安装包完整！
    echo.
    echo 可以完全离线安装，无需联网。
    echo.
    echo 运行安装命令:
    echo   Install.bat
) else (
    echo [WARN] 发现 %MISSING_COUNT% 个缺失文件
    echo.
    echo 请确保以下目录包含所有必需的文件:
    echo   - electron-app\embedded\     (系统安装包)
    echo   - electron-app\deploy-package\ (Python依赖)
)

echo.
pause
