@echo off
chcp 65001 >nul
echo ==========================================
echo  RapidOCR 离线安装脚本
echo ==========================================
echo.

set PYTHON_PATH=%1
if "%PYTHON_PATH%"=="" (
    set PYTHON_PATH=python
    echo 使用默认Python: python
) else (
    echo 使用指定Python: %PYTHON_PATH%
)

echo.
echo 检查Python环境...
%PYTHON_PATH% --version >nul 2>&1
if errorlevel 1 (
    echo [错误] 找不到Python，请指定正确的Python路径
    echo 用法: install_rapidocr_offline.bat [python路径]
    pause
    exit /b 1
)

echo [OK] Python已找到
echo.
echo 开始离线安装RapidOCR及其依赖...
echo 安装路径: %~dp0
echo.

cd /d "%~dp0"

REM 安装依赖包（按顺序）
echo [1/8] 安装 numpy...
%PYTHON_PATH% -m pip install --no-index --find-links="%~dp0" numpy-2.2.6-cp310-cp310-win_amd64.whl -q
if errorlevel 1 echo 警告: numpy安装可能有问题

echo [2/8] 安装 opencv-python...
%PYTHON_PATH% -m pip install --no-index --find-links="%~dp0" opencv_python-4.13.0.92-cp37-abi3-win_amd64.whl -q
if errorlevel 1 echo 警告: opencv安装可能有问题

echo [3/8] 安装 onnxruntime...
%PYTHON_PATH% -m pip install --no-index --find-links="%~dp0" onnxruntime-1.23.2-cp310-cp310-win_amd64.whl -q
if errorlevel 1 echo 警告: onnxruntime安装可能有问题

echo [4/8] 安装 pyclipper...
%PYTHON_PATH% -m pip install --no-index --find-links="%~dp0" pyclipper-1.4.0-cp310-cp310-win_amd64.whl -q

echo [5/8] 安装 shapely...
%PYTHON_PATH% -m pip install --no-index --find-links="%~dp0" shapely-2.1.2-cp310-cp310-win_amd64.whl -q

echo [6/8] 安装 Pillow...
%PYTHON_PATH% -m pip install --no-index --find-links="%~dp0" pillow-12.1.1-cp310-cp310-win_amd64.whl -q

echo [7/8] 安装 PyYAML...
%PYTHON_PATH% -m pip install --no-index --find-links="%~dp0" pyyaml-6.0.3-cp310-cp310-win_amd64.whl -q

echo [8/8] 安装 rapidocr_onnxruntime...
%PYTHON_PATH% -m pip install --no-index --find-links="%~dp0" rapidocr_onnxruntime-1.4.4-py3-none-any.whl -q

echo.
echo ==========================================
echo  安装完成！
echo ==========================================
echo.
echo 测试安装...
%PYTHON_PATH% -c "from rapidocr_onnxruntime import RapidOCR; print('[OK] RapidOCR导入成功')"
if errorlevel 1 (
    echo [错误] 安装可能不完整，请检查错误信息
) else (
    echo [OK] RapidOCR安装成功！
)

echo.
pause
