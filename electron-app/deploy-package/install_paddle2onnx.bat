@echo off
chcp 65001 >nul
echo ==========================================
echo  安装 paddle2onnx 转换工具
echo ==========================================
echo.

set PYTHON_PATH=%1
if "%PYTHON_PATH%"=="" (
    set PYTHON_PATH=python
)

cd /d "%~dp0"

echo [1/4] 安装 onnx...
%PYTHON_PATH% -m pip install --no-index --find-links="%~dp0" onnx-1.17.0-cp310-cp310-win_amd64.whl -q
if errorlevel 1 echo 警告: onnx 安装可能有问题

echo [2/4] 安装 onnxoptimizer...
%PYTHON_PATH% -m pip install --no-index --find-links="%~dp0" onnxoptimizer-0.3.13-cp310-cp310-win_amd64.whl -q

echo [3/4] 安装 polygraphy...
%PYTHON_PATH% -m pip install --no-index --find-links="%~dp0" polygraphy-0.49.26-py2.py3-none-any.whl -q

echo [4/4] 安装 paddle2onnx...
%PYTHON_PATH% -m pip install --no-index --find-links="%~dp0" paddle2onnx-2.1.0-cp310-cp310-win_amd64.whl -q

echo.
echo 验证安装...
%PYTHON_PATH% -c "import paddle2onnx; print('[OK] paddle2onnx 版本:', paddle2onnx.__version__)"
if errorlevel 1 (
    echo [错误] 安装失败
) else (
    echo [OK] paddle2onnx 安装成功！
    echo.
    echo 现在可以使用以下命令转换模型:
    echo   python scripts\setup_v5_models.py
)

echo.
pause
