@echo off
chcp 65001 >nul
echo ==========================================
echo  离线安装 RapidOCR 依赖
echo ==========================================
echo.

set PYTHON=python
set PACKAGES_DIR=%~dp0

echo [1/5] 安装 rapidocr_onnxruntime...
%PYTHON% -m pip install --no-index --find-links %PACKAGES_DIR% rapidocr_onnxruntime

echo [2/5] 安装 onnxruntime...
%PYTHON% -m pip install --no-index --find-links %PACKAGES_DIR% onnxruntime

echo [3/5] 安装 opencv_python...
%PYTHON% -m pip install --no-index --find-links %PACKAGES_DIR% opencv_python

echo [4/5] 安装 numpy...
%PYTHON% -m pip install --no-index --find-links %PACKAGES_DIR% numpy

echo [5/5] 安装其他依赖 (pillow, pdf2image等)...
%PYTHON% -m pip install --no-index --find-links %PACKAGES_DIR% pillow pdf2image

echo.
echo ==========================================
echo  安装完成！
echo ==========================================
pause
