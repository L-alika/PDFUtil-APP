# -*- coding: utf-8 -*-
"""
离线安装脚本 - 用于无网络环境
运行方式: python offline_install.py
"""

import os
import subprocess
import sys

# 获取脚本所在目录
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
OFFLINE_PACKAGES = os.path.join(SCRIPT_DIR, 'deploy-package')

def check_python_version():
    """检查 Python 版本"""
    version = sys.version_info
    print(f"Python 版本: {version.major}.{version.minor}.{version.micro}")

    if version.major != 3 or version.minor < 8 or version.minor > 12:
        print("错误: PaddlePaddle 需要 Python 3.8-3.12")
        print(f"当前版本 {version.major}.{version.minor} 不兼容")
        return False
    return True

def install_offline():
    """离线安装所有依赖"""
    print("\n=== 开始离线安装 ===\n")

    # 检查离线包目录
    if not os.path.exists(OFFLINE_PACKAGES):
        print(f"错误: 找不到离线包目录: {OFFLINE_PACKAGES}")
        return False

    # 获取所有 wheel 文件
    whl_files = [f for f in os.listdir(OFFLINE_PACKAGES) if f.endswith('.whl')]
    print(f"找到 {len(whl_files)} 个离线包文件")

    if len(whl_files) == 0:
        print("错误: 离线包目录为空")
        return False

    # 主要包列表（按安装顺序）
    main_packages = [
        'numpy',
        'Pillow',
        'pdf2image',
        'paddlepaddle',
        'paddleocr',
    ]

    # 安装所有 wheel 文件
    print("\n正在安装所有依赖包...")
    cmd = [
        sys.executable, '-m', 'pip', 'install',
        '--no-index',
        '--no-deps',  # 不检查依赖，直接安装
        '--find-links', OFFLINE_PACKAGES,
    ]

    # 添加所有 wheel 文件
    for whl in whl_files:
        cmd.append(os.path.join(OFFLINE_PACKAGES, whl))

    print(f"执行命令: pip install --no-index --no-deps --find-links {OFFLINE_PACKAGES} [所有wheel文件]")

    try:
        result = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace')
        print(result.stdout)
        if result.stderr:
            print("警告/错误:")
            print(result.stderr)

        if result.returncode != 0:
            print(f"\n安装返回码: {result.returncode}")
            return False

    except Exception as e:
        print(f"安装失败: {e}")
        return False

    return True

def verify_installation():
    """验证安装"""
    print("\n=== 验证安装 ===\n")

    packages = ['paddle', 'paddleocr', 'pdf2image', 'PIL', 'numpy']
    failed = []

    for pkg in packages:
        try:
            __import__(pkg)
            print(f"[OK] {pkg}")
        except ImportError as e:
            print(f"[失败] {pkg}: {e}")
            failed.append(pkg)

    if failed:
        print(f"\n以下包安装失败: {failed}")
        return False

    print("\n所有包安装成功!")
    return True

def main():
    print("=" * 50)
    print("格式转换工具 - 离线依赖安装")
    print("=" * 50)

    # 检查 Python 版本
    if not check_python_version():
        input("\n按回车键退出...")
        return

    # 离线安装
    if not install_offline():
        print("\n离线安装失败!")
        input("\n按回车键退出...")
        return

    # 验证
    if verify_installation():
        print("\n" + "=" * 50)
        print("安装完成!")
        print("=" * 50)
    else:
        print("\n安装验证失败，请检查错误信息")

    input("\n按回车键退出...")

if __name__ == '__main__':
    main()
