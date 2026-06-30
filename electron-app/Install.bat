@echo off
chcp 936 >nul
title PDF-APP - Install
color 0A
setlocal enabledelayedexpansion

:: ==================== Paths ====================
set "INSTALL_DIR=C:\Program Files\PDFUtil"
set "APP_DIR=PDF-APP"
set "DEPS_DIR=%~dp0embedded"
set "APP_SOURCE=%~dp0dist\win-unpacked"
set "OFFLINE_PACKAGES=%~dp0deploy-package"

:: ==================== Check admin ====================
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo [INFO] Requesting admin privileges...
    "%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

:: ==================== Welcome ====================
cls
echo ========================================
echo    PDF-APP - Installation
echo ========================================
echo.
echo This will install:
echo   1. Visual C++ Runtime (required for RapidOCR)
echo   2. Java 11
echo   3. Python 3.10
echo   4. Ghostscript
echo   5. Poppler
echo   6. LibreOffice
echo   7. Python packages (RapidOCR + PyMuPDF for adaptive DPI)
echo   8. PDF-APP application
echo.
echo [Note] Using RapidOCR for high-performance OCR
echo [Note] PyMuPDF enables adaptive DPI for professional archive quality
echo.
echo Install directory: %INSTALL_DIR%\%APP_DIR%
echo.
pause
echo [Checking prerequisites, please wait...]
echo.

goto :Main

:: ==================== Visual C++ Runtime Install ====================
:InstallVCRuntime
echo [1/9] Checking Visual C++ Runtime...

:: Check if VC++ 2015-2022 is installed (required by PaddlePaddle 3.x)
:: The Installed value is REG_DWORD, check if it equals 1
reg query "HKLM\SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64" /v Installed 2>nul | findstr "0x1" >nul
if %errorlevel% equ 0 (
    echo      [OK] Visual C++ Runtime already installed
    goto :EOF
)

:: Install from offline package
if exist "%DEPS_DIR%\06_VC_redist.x64.exe" (
    echo      Installing Visual C++ Runtime...
    start /wait "" "%DEPS_DIR%\06_VC_redist.x64.exe" /install /quiet /norestart
    echo      [OK] Visual C++ Runtime installed
) else (
    echo      [WARN] Visual C++ Runtime installer not found
    echo      PaddlePaddle may fail to run without VC++ Runtime
)
goto :EOF

:: ==================== Java Install ====================
:InstallJava
echo [2/9] Checking Java...

:: Java installation path
set "JAVA_HOME_DIR=%INSTALL_DIR%\jdk-11.0.30.7-hotspot"
set "JAVA_BIN=%JAVA_HOME_DIR%\bin"

:: Check if Java exists in PATH
java -version >nul 2>&1
if %errorlevel% equ 0 (
    echo      [OK] Java is available in PATH
    goto :EOF
)

:: Check if Java is already extracted at target location
if exist "%JAVA_BIN%\java.exe" (
    echo      Found Java at: %JAVA_BIN%
    call :AddToPath "%JAVA_BIN%"
    echo      [OK] Java added to PATH
    goto :EOF
)

:: Check for any jdk-* version directory
for /d %%D in ("%INSTALL_DIR%\jdk-*") do (
    if exist "%%D\bin\java.exe" (
        echo      Found Java at: %%D\bin
        call :AddToPath "%%D\bin"
        echo      [OK] Java added to PATH
        goto :EOF
    )
)

:: Extract from ZIP package
if exist "%DEPS_DIR%\01_jdk-11.0.30.7-hotspot.zip" (
    echo      Extracting Java 11...
    
    :: Create install directory if not exists
    if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"
    
    :: Extract ZIP file
    "%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -Command "Expand-Archive -Path '%DEPS_DIR%\01_jdk-11.0.30.7-hotspot.zip' -DestinationPath '%INSTALL_DIR%' -Force"
    
    :: Verify extraction and add to PATH
    if exist "%JAVA_BIN%\java.exe" (
        echo      Adding Java to PATH...
        call :AddToPath "%JAVA_BIN%"
        echo      [OK] Java installed and added to PATH
    ) else (
        :: Try to find any jdk directory
        for /d %%D in ("%INSTALL_DIR%\jdk-*") do (
            if exist "%%D\bin\java.exe" (
                echo      Found Java at: %%D\bin
                call :AddToPath "%%D\bin"
                echo      [OK] Java installed and added to PATH
                goto :EOF
            )
        )
        echo      [ERROR] Java extraction failed - java.exe not found
    )
) else (
    echo      [WARN] Java ZIP package not found: %DEPS_DIR%\01_jdk-11.0.30.7-hotspot.zip
    echo      Please put the JDK ZIP file in the embedded folder
)
goto :EOF

:: ==================== Refresh Environment Variables ====================
:RefreshEnv
:: Refresh environment variables from registry
for /f "tokens=2*" %%a in ('reg query "HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment" /v Path 2^>nul ^| findstr "Path"') do set "PATH=%%b"
goto :EOF

:: ==================== Python Install ====================
:InstallPython
echo [3/9] Checking Python...

:: Check if Python exists
set "PYTHON_EXE="
if exist "C:\Program Files\Python310\python.exe" (
    set "PYTHON_EXE=C:\Program Files\Python310\python.exe"
) else if exist "%LOCALAPPDATA%\Programs\Python\Python310\python.exe" (
    set "PYTHON_EXE=%LOCALAPPDATA%\Programs\Python\Python310\python.exe"
) else if exist "C:\Python310\python.exe" (
    set "PYTHON_EXE=C:\Python310\python.exe"
)

if defined PYTHON_EXE (
    echo      [OK] Found Python: !PYTHON_EXE!
    goto :EOF
)

:: Install from offline package
if exist "%DEPS_DIR%\02_python-3.10.9-amd64.exe" (
    echo      Installing Python 3.10...
    start /wait "" "%DEPS_DIR%\02_python-3.10.9-amd64.exe" /quiet InstallAllUsers=1 Include_pip=1 Include_test=0 PrependPath=1
    echo      [OK] Python 3.10 installed

    :: Add Python directories to PATH (main dir + Scripts)
    if exist "C:\Program Files\Python310\" (
        echo      Adding Python to PATH...
        call :AddToPath "C:\Program Files\Python310"
        if exist "C:\Program Files\Python310\Scripts" (
            call :AddToPath "C:\Program Files\Python310\Scripts"
        )
        echo      [OK] Python added to PATH
    )
) else (
    echo      [WARN] Python not found, please install manually
)
goto :EOF

:: ==================== Ghostscript Install ====================
:InstallGhostscript
echo [4/9] Checking Ghostscript...

:: Check if already installed
set "GS_FOUND="
for /f "delims=" %%i in ('dir /b /ad "C:\Program Files\gs\gs*" 2^>nul') do (
    if exist "C:\Program Files\gs\%%i\bin\gswin64c.exe" set "GS_FOUND=C:\Program Files\gs\%%i\bin\gswin64c.exe"
)

if defined GS_FOUND (
    echo      [OK] Found Ghostscript: !GS_FOUND!
    goto :EOF
)

:: Install from offline package
if exist "%DEPS_DIR%\03_gs10060w64.exe" (
    echo      Installing Ghostscript...
    start /wait "" "%DEPS_DIR%\03_gs10060w64.exe" /S
    echo      [OK] Ghostscript installed

    :: Add Ghostscript to PATH
    for /f "delims=" %%i in ('dir /b /ad "C:\Program Files\gs\gs*" 2^>nul') do (
        if exist "C:\Program Files\gs\%%i\bin" (
            call :AddToPath "C:\Program Files\gs\%%i\bin"
        )
    )
) else (
    echo      [WARN] Ghostscript not found, please install manually
)
goto :EOF

:: ==================== Poppler Install ====================
:InstallPoppler
echo [5/9] Checking Poppler...

:: Poppler 解压后的目录名（zip文件名是 Release-xxx，但内部目录是 poppler-xxx）
set "POPPLER_PATH=%INSTALL_DIR%\poppler-25.12.0\Library\bin"

:: Check if already in PATH
pdftoppm -v >nul 2>&1
if %errorlevel% equ 0 (
    echo      [OK] Poppler is in PATH
    goto :EOF
)

:: Check if files already extracted
if exist "%POPPLER_PATH%\pdftoppm.exe" (
    echo      Found Poppler, adding to PATH...
    call :AddToPath "%POPPLER_PATH%"
    echo      [OK] Poppler added to PATH
    goto :EOF
)

:: Check for any poppler version directory
for /d %%D in ("%INSTALL_DIR%\poppler-*") do (
    if exist "%%D\Library\bin\pdftoppm.exe" (
        echo      Found Poppler at: %%D\Library\bin
        call :AddToPath "%%D\Library\bin"
        echo      [OK] Poppler added to PATH
        goto :EOF
    )
)

:: Extract from offline package
if exist "%DEPS_DIR%\04_Release-25.12.0-0.zip" (
    echo      Extracting Poppler...
    if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"
    "%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -Command "Expand-Archive -Path '%DEPS_DIR%\04_Release-25.12.0-0.zip' -DestinationPath '%INSTALL_DIR%' -Force"

    :: Verify extraction and add to PATH
    if exist "%POPPLER_PATH%\pdftoppm.exe" (
        echo      Adding Poppler to PATH...
        call :AddToPath "%POPPLER_PATH%"
        echo      [OK] Poppler installed and added to PATH
    ) else (
        :: Try to find any poppler directory
        for /d %%D in ("%INSTALL_DIR%\poppler-*") do (
            if exist "%%D\Library\bin\pdftoppm.exe" (
                echo      Adding Poppler to PATH...
                call :AddToPath "%%D\Library\bin"
                echo      [OK] Poppler installed and added to PATH
                goto :EOF
            )
        )
        echo      [ERROR] Poppler extraction failed - pdftoppm.exe not found
    )
) else (
    echo      [WARN] Poppler installer not found
)
goto :EOF

:: ==================== Add to System PATH ====================
:AddToPath
:: %1 = path to add
:: 使用临时文件避免管道卡死问题
set "TEMP_FILE=%TEMP%\path_check_%RANDOM%.txt"
reg query "HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment" /v Path >"%TEMP_FILE%" 2>nul
for /f "usebackq tokens=2*" %%a in ("%TEMP_FILE%") do set "CURRENT_PATH=%%b"
del "%TEMP_FILE%" 2>nul

:: 检查是否已存在（使用精确匹配，避免子串匹配问题）
echo ;%CURRENT_PATH%; | findstr /i /c:";%~1;" >nul
if %errorlevel% equ 0 goto :EOF

:: 添加到 PATH（使用 setx 更安全）
echo      添加到系统 PATH...
setx /M PATH "%~1;%CURRENT_PATH%" >nul 2>&1
if %errorlevel% equ 0 (
    set "PATH=%~1;%PATH%"
) else (
    :: 如果 setx 失败（PATH 太长），尝试用 reg add
    reg add "HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment" /v Path /t REG_EXPAND_SZ /d "%~1;%CURRENT_PATH%" /f >nul 2>&1
    set "PATH=%~1;%PATH%"
)
goto :EOF

:: ==================== LibreOffice Install ====================
:InstallLibreOffice
echo [6/9] Checking LibreOffice...

if exist "C:\Program Files\LibreOffice\program\soffice.exe" (
    echo      [OK] Found LibreOffice
    goto :EOF
)

if exist "%DEPS_DIR%\05_LibreOffice_26.2.1_Win_x86-64.msi" (
    echo      Installing LibreOffice...
    start /wait msiexec /i "%DEPS_DIR%\05_LibreOffice_26.2.1_Win_x86-64.msi" /qn /norestart
    echo      [OK] LibreOffice installed
) else (
    echo      [WARN] LibreOffice installer not found
)
goto :EOF

:: ==================== Python Dependencies Install ====================
:InstallPythonDeps
echo [7/9] Installing Python dependencies (RapidOCR + PDF)...

:: Find Python
set "PYTHON_EXE=python"
if exist "C:\Program Files\Python310\python.exe" set "PYTHON_EXE=C:\Program Files\Python310\python.exe"
if exist "%LOCALAPPDATA%\Programs\Python\Python310\python.exe" set "PYTHON_EXE=%LOCALAPPDATA%\Programs\Python\Python310\python.exe"
if exist "C:\Python310\python.exe" set "PYTHON_EXE=C:\Python310\python.exe"

echo      Using: %PYTHON_EXE%
"%PYTHON_EXE%" --version

:: Check if RapidOCR already installed
"%PYTHON_EXE%" -c "import rapidocr_onnxruntime" >nul 2>&1
if %errorlevel% equ 0 (
    echo      [OK] RapidOCR already installed
    goto :CopyModels
)

:: Check offline package directory
if not exist "%OFFLINE_PACKAGES%" (
    echo      [WARN] Cannot find offline package directory: %OFFLINE_PACKAGES%
    goto :EOF
)

echo      [Offline Mode] Installing RapidOCR from local packages...
echo.

echo      Step 1: Installing base dependencies (numpy, Pillow)...
"%PYTHON_EXE%" -m pip install --no-index --find-links="%OFFLINE_PACKAGES%" numpy Pillow --quiet

echo      Step 2: Installing OpenCV...
"%PYTHON_EXE%" -m pip install --no-index --find-links="%OFFLINE_PACKAGES%" opencv-python --quiet

echo      Step 3: Installing ONNX Runtime...
"%PYTHON_EXE%" -m pip install --no-index --find-links="%OFFLINE_PACKAGES%" onnxruntime --quiet

echo      Step 4: Installing RapidOCR (High Performance)...
"%PYTHON_EXE%" -m pip install --no-index --find-links="%OFFLINE_PACKAGES%" rapidocr-onnxruntime

echo      Step 5: Installing PDF processing dependencies...
"%PYTHON_EXE%" -m pip install --no-index --find-links="%OFFLINE_PACKAGES%" pdf2image --quiet 2>nul

echo      Step 6: Installing PyMuPDF for adaptive DPI (professional archive)...
"%PYTHON_EXE%" -m pip install --no-index --find-links="%OFFLINE_PACKAGES%" pymupdf --quiet 2>nul
if %errorlevel% equ 0 (
    echo      [OK] PyMuPDF installed successfully
) else (
    echo      [WARN] PyMuPDF installation failed, adaptive DPI may not work
)

echo      Step 7: Installing other dependencies...
"%PYTHON_EXE%" -m pip install --no-index --find-links="%OFFLINE_PACKAGES%" shapely pyclipper PyYAML tqdm --quiet 2>nul

:: Verify
echo.
echo      Verifying installation...
"%PYTHON_EXE%" -c "import rapidocr_onnxruntime; print('      RapidOCR: OK')" 2>nul
"%PYTHON_EXE%" -c "import fitz; print('      PyMuPDF (adaptive DPI): OK')" 2>nul
if %errorlevel% neq 0 (
    echo      [WARN] PyMuPDF verification failed, adaptive DPI feature may not work
)

echo.
echo      [OK] Python dependencies installed successfully

:: Copy OCR Model Files
:CopyModels
echo.
echo      Copying OCR model files...

:: Create models directory
if not exist "%INSTALL_DIR%\models" mkdir "%INSTALL_DIR%\models"

:: Copy RapidOCR models if they exist in deploy-package
if exist "%~dp0..\models\rapidocr" (
    echo      Copying RapidOCR models...
    xcopy /s /e /i /q "%~dp0..\models\rapidocr" "%INSTALL_DIR%\models\rapidocr\" 2>nul
    echo      [OK] Models copied
) else (
    echo      [WARN] Model source not found at %~dp0..\models\rapidocr
    echo      Please ensure models are in the models\rapidocr directory
)

goto :EOF

:: ==================== Application Install ====================
:InstallApp
echo [8/9] Installing application...

if not exist "%APP_SOURCE%\PDF-APP.exe" (
    echo      [ERROR] Cannot find application: %APP_SOURCE%\PDF-APP.exe
    echo      Please run: cd electron-app && npm run build:win
    pause
    exit /b 1
)

:: Remove old version
if exist "%INSTALL_DIR%\%APP_DIR%" (
    echo      Removing old version...
    rmdir /s /q "%INSTALL_DIR%\%APP_DIR%" 2>nul
)

:: Copy new version
echo      Copying files...
%SystemRoot%\System32\xcopy.exe /s /e /i /q "%APP_SOURCE%" "%INSTALL_DIR%\%APP_DIR%\"

:: Copy scripts (RapidOCR wrapper)
echo      Copying RapidOCR scripts...
if exist "%~dp0..\scripts" (
    xcopy /s /e /i /q "%~dp0..\scripts" "%INSTALL_DIR%\scripts\" 2>nul
)

:: Create desktop shortcut
echo      Creating desktop shortcut...
%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe -Command "$ws = New-Object -ComObject WScript.Shell; $s = $ws.CreateShortcut([Environment]::GetFolderPath('Desktop') + '\PDFUtil.lnk'); $s.TargetPath = 'C:\Program Files\PDFUtil\%APP_DIR%\PDF-APP.exe'; $s.WorkingDirectory = 'C:\Program Files\PDFUtil\%APP_DIR%'; $s.Save()"

echo      [OK] Application installed
exit /b 0

:: ==================== Step 9: Verify Installation ====================
:VerifyInstall
echo [9/9] Verifying installation...

:: Find Python
set "PYTHON_EXE=python"
if exist "C:\Program Files\Python310\python.exe" set "PYTHON_EXE=C:\Program Files\Python310\python.exe"
if exist "%LOCALAPPDATA%\Programs\Python\Python310\python.exe" set "PYTHON_EXE=%LOCALAPPDATA%\Programs\Python\Python310\python.exe"
if exist "C:\Python310\python.exe" set "PYTHON_EXE=C:\Python310\python.exe"

:: Test RapidOCR
echo      Testing RapidOCR...
"%PYTHON_EXE%" -c "from rapidocr_onnxruntime import RapidOCR; print('      RapidOCR import: OK')" 2>nul
if %errorlevel% neq 0 (
    echo      [WARN] RapidOCR test failed
) else (
    echo      [OK] RapidOCR ready
)

:: Check model files
if exist "%INSTALL_DIR%\models\rapidocr\*.onnx" (
    echo      [OK] OCR models found
) else (
    echo      [WARN] OCR models not found in %INSTALL_DIR%\models\rapidocr\
)

goto :EOF

:: ==================== Finish ====================
:Finish
echo.
echo ========================================
echo    Installation Complete!
echo ========================================
echo.
echo Install directory: %INSTALL_DIR%\%APP_DIR%
echo.
echo OCR Engine: RapidOCR (High Performance)
echo Model Dir: %INSTALL_DIR%\models\rapidocr\
echo.
if exist "%INSTALL_DIR%\%APP_DIR%\PDF-APP.exe" (
    echo Starting application...
    start "" "%INSTALL_DIR%\%APP_DIR%\PDF-APP.exe"
)
exit 0

:: ==================== Main ====================
:Main
call :InstallVCRuntime
call :InstallJava
call :InstallPython
call :InstallGhostscript
call :InstallPoppler
call :InstallLibreOffice
call :InstallPythonDeps
call :InstallApp
if %errorlevel% neq 0 pause
call :VerifyInstall
call :Finish