#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
离线安装所有 Python 依赖
"""
import subprocess
import sys
from pathlib import Path

# 当前目录（包含 whl 文件的目录）
PACKAGES_DIR = Path(__file__).parent

# 核心依赖列表（按顺序安装）
CORE_PACKAGES = [
    "numpy",
    "flatbuffers",
    "humanfriendly",
    "coloredlogs",
    "onnx",
    "onnxruntime",
    "opencv_python",
    "Pillow",
    "rapidocr_onnxruntime",
]

# 可选依赖（用于 PDF 处理）
OPTIONAL_PACKAGES = [
    "pdf2image",
    "pyreadline3",
]

def install_package(package_name):
    """安装单个包"""
    print(f"\n[安装] {package_name}...")
    cmd = [
        sys.executable, "-m", "pip", "install",
        "--no-index",
        "--find-links", str(PACKAGES_DIR),
        package_name
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode == 0:
        print(f"[OK] {package_name} 安装成功")
        return True
    else:
        print(f"[WARN] {package_name} 安装失败或已存在")
        if "already satisfied" in result.stdout:
            print(f"[OK] {package_name} 已安装")
            return True
        return False

def check_installed():
    """检查已安装的包"""
    print("=" * 50)
    print("  检查 RapidOCR 依赖")
    print("=" * 50)
    
    try:
        import rapidocr_onnxruntime
        print(f"[OK] rapidocr_onnxruntime 已安装")
    except ImportError:
        print("[MISSING] rapidocr_onnxruntime 未安装")
    
    try:
        import onnxruntime
        print(f"[OK] onnxruntime 已安装")
    except ImportError:
        print("[MISSING] onnxruntime 未安装")
    
    try:
        import cv2
        print(f"[OK] opencv_python 已安装")
    except ImportError:
        print("[MISSING] opencv_python 未安装")

def main():
    print("=" * 50)
    print("  离线安装 RapidOCR 依赖")
    print("=" * 50)
    print(f"安装包目录: {PACKAGES_DIR}")
    
    # 先检查当前状态
    check_installed()
    
    print("\n" + "=" * 50)
    print("  开始安装核心依赖")
    print("=" * 50)
    
    success_count = 0
    for pkg in CORE_PACKAGES:
        if install_package(pkg):
            success_count += 1
    
    print("\n" + "=" * 50)
    print("  开始安装可选依赖")
    print("=" * 50)
    
    for pkg in OPTIONAL_PACKAGES:
        install_package(pkg)
    
    print("\n" + "=" * 50)
    print(f"  安装完成: {success_count}/{len(CORE_PACKAGES)} 核心包")
    print("=" * 50)
    
    # 再次检查
    print("\n验证安装...")
    check_installed()

if __name__ == "__main__":
    main()
    input("\n按回车键退出...")
