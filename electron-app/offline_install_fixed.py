# -*- coding: utf-8 -*-
"""
离线依赖安装 - 改进版
按正确的依赖顺序安装 wheel 文件
"""

import os
import subprocess
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
OFFLINE_PACKAGES = os.path.join(SCRIPT_DIR, 'deploy-package')

# 按依赖顺序定义安装包
INSTALL_ORDER = [
    # 基础依赖
    ('setuptools', 'setuptools-82.0.1-py3-none-any.whl'),
    ('wheel', None),  # 通常已包含在 setuptools

    # 核心依赖
    ('numpy', 'numpy-1.26.4-cp310-cp310-win_amd64.whl'),
    ('pillow', 'pillow-12.1.1-cp310-cp310-win_amd64.whl'),
    ('pdf2image', 'pdf2image-1.17.0-py3-none-any.whl'),

    # PaddlePaddle 核心依赖
    ('protobuf', 'protobuf-7.34.0-cp310-abi3-win_amd64.whl'),
    ('opencv_contrib_python', 'opencv_contrib_python-4.10.0.84-cp37-abi3-win_amd64.whl'),

    # PaddlePaddle 和 PaddleOCR
    ('paddlepaddle', 'paddlepaddle-3.2.0-cp310-cp310-win_amd64.whl'),
    ('paddleocr', 'paddleocr-3.3.0-py3-none-any.whl'),
]

def check_python_version():
    """检查 Python 版本"""
    version = sys.version_info
    print(f"Python 版本: {version.major}.{version.minor}.{version.micro}")

    if version.major != 3:
        print(f"错误: 需要 Python 3.x，当前是 {version.major}.x")
        return False

    if version.minor < 8 or version.minor > 12:
        print(f"错误: PaddlePaddle 需要 Python 3.8-3.12")
        print(f"当前版本 3.{version.minor} 不兼容")
        return False

    return True

def install_wheel(wheel_path, verbose=True):
    """安装单个 wheel 文件"""
    cmd = [sys.executable, '-m', 'pip', 'install', '--no-deps', wheel_path]
    if verbose:
        print(f"  安装: {os.path.basename(wheel_path)}")

    result = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace')

    if result.returncode != 0:
        if verbose:
            print(f"  [警告] 安装失败: {result.stderr[:200]}")
        return False
    return True

def install_with_deps(package_name, offline_dir):
    """尝试带依赖安装"""
    cmd = [
        sys.executable, '-m', 'pip', 'install',
        '--no-index',
        '--find-links', offline_dir,
        package_name
    ]

    result = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace')
    return result.returncode == 0

def verify_installation():
    """验证安装"""
    print("\n=== 验证安装 ===\n")

    packages = [
        ('numpy', 'numpy'),
        ('PIL', 'pillow'),
        ('pdf2image', 'pdf2image'),
        ('cv2', 'opencv'),
        ('paddle', 'paddlepaddle'),
        ('paddleocr', 'paddleocr'),
    ]

    failed = []
    for import_name, display_name in packages:
        try:
            __import__(import_name)
            print(f"  [OK] {display_name}")
        except ImportError as e:
            print(f"  [失败] {display_name}: {e}")
            failed.append(display_name)

    return failed

def main():
    print("=" * 60)
    print("格式转换工具 - 离线依赖安装 (改进版)")
    print("=" * 60)

    # 检查 Python 版本
    if not check_python_version():
        input("\n按回车键退出...")
        return

    # 检查离线包目录
    if not os.path.exists(OFFLINE_PACKAGES):
        print(f"\n错误: 找不到离线包目录: {OFFLINE_PACKAGES}")
        input("\n按回车键退出...")
        return

    # 统计 wheel 文件
    whl_files = [f for f in os.listdir(OFFLINE_PACKAGES) if f.endswith('.whl')]
    print(f"\n找到 {len(whl_files)} 个离线包文件")

    # 方法1: 按顺序安装关键包
    print("\n[方法1] 按依赖顺序安装关键包...")

    success_count = 0
    for pkg_name, wheel_file in INSTALL_ORDER:
        if wheel_file is None:
            continue

        wheel_path = os.path.join(OFFLINE_PACKAGES, wheel_file)
        if os.path.exists(wheel_path):
            if install_wheel(wheel_path):
                success_count += 1
        else:
            print(f"  [跳过] 找不到: {wheel_file}")

    print(f"\n成功安装 {success_count} 个关键包")

    # 方法2: 安装所有剩余的 wheel 文件
    print("\n[方法2] 安装所有剩余依赖...")

    installed_wheels = set(wheel_file for _, wheel_file in INSTALL_ORDER if wheel_file)
    remaining = [f for f in whl_files if f not in installed_wheels]

    for wheel_file in remaining:
        wheel_path = os.path.join(OFFLINE_PACKAGES, wheel_file)
        install_wheel(wheel_path, verbose=False)

    # 方法3: 修复依赖关系
    print("\n[方法3] 修复依赖关系...")

    for pkg_name, _ in INSTALL_ORDER:
        if pkg_name in ['wheel']:
            continue
        install_with_deps(pkg_name, OFFLINE_PACKAGES)

    # 验证安装
    failed = verify_installation()

    print("\n" + "=" * 60)
    if failed:
        print(f"安装完成，但有 {len(failed)} 个包失败: {failed}")
        print("\n建议:")
        print("1. 确认 Python 版本是 3.10 (pip --version)")
        print("2. 检查离线包是否完整")
        print("3. 手动运行: pip install --no-index --find-links=deploy-package paddlepaddle")
    else:
        print("所有依赖安装成功!")
    print("=" * 60)

    input("\n按回车键退出...")

if __name__ == '__main__':
    main()
