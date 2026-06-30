#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RapidOCR 高性能OCR包装脚本
支持PP-OCRv4和PP-OCRv5模型自动切换
完全离线运行，无需PaddlePaddle

用法:
    python rapidocr_wrapper.py <img1> [img2] ...
    python rapidocr_wrapper.py --test              # 测试模式
    python rapidocr_wrapper.py --version           # 显示版本信息

环境变量:
    RAPIDOCR_MODEL_DIR: 自定义模型目录
    RAPIDOCR_MODEL_VERSION: 强制使用 v4 或 v5
    RAPIDOCR_THREAD_NUM: 推理线程数（默认4）

输出格式（与paddleocr_wrapper.py兼容）:
    {
        "pages": [
            [{"text": "...", "text_region": [[x,y],...], "confidence": 0.99}, ...]
        ],
        "page_count": N,
        "model_version": "v4|v5",
        "total_time": 1.23
    }
"""

import os
import sys
import json
import time
import glob
from pathlib import Path

# 性能优化：设置ONNX Runtime线程数
os.environ['OMP_NUM_THREADS'] = '4'
os.environ['ONNXRUNTIME_THREADS'] = '4'

# 完全离线模式
os.environ['HF_HUB_OFFLINE'] = '1'
os.environ['TRANSFORMERS_OFFLINE'] = '1'
os.environ['DISABLE_MODEL_SOURCE_CHECK'] = 'True'

# 获取脚本所在目录
SCRIPT_DIR = Path(__file__).parent.absolute()
PROJECT_ROOT = SCRIPT_DIR.parent

# 模型目录配置
DEFAULT_MODEL_DIR = PROJECT_ROOT / "models" / "rapidocr"
CUSTOM_MODEL_DIR = os.environ.get('RAPIDOCR_MODEL_DIR')
MODEL_DIR = Path(CUSTOM_MODEL_DIR) if CUSTOM_MODEL_DIR else DEFAULT_MODEL_DIR

# 模型版本配置
FORCE_VERSION = os.environ.get('RAPIDOCR_MODEL_VERSION')
THREAD_NUM = int(os.environ.get('RAPIDOCR_THREAD_NUM', '4'))

# OCR引擎单例
_ocr_engine = None
_model_version = None


def detect_model_version():
    """
    自动检测模型版本
    优先检测V5，如果没有则使用V4
    """
    global _model_version
    
    if FORCE_VERSION:
        return FORCE_VERSION
    
    if _model_version:
        return _model_version
    
    # 检测V5模型
    v5_files = [
        MODEL_DIR / "ch_PP-OCRv5_mobile_det_infer.onnx",
        MODEL_DIR / "ch_PP-OCRv5_mobile_rec_infer.onnx",
    ]
    
    if all(f.exists() for f in v5_files):
        _model_version = "v5"
        return "v5"
    
    # 检测V4模型
    v4_files = [
        MODEL_DIR / "ch_PP-OCRv4_det_infer.onnx",
        MODEL_DIR / "ch_PP-OCRv4_rec_infer.onnx",
    ]
    
    if all(f.exists() for f in v4_files):
        _model_version = "v4"
        return "v4"
    
    # 默认使用V4路径
    return "v4"


def get_model_paths(version=None):
    """
    获取模型路径
    
    Args:
        version: 'v4' 或 'v5'，None则自动检测
        
    Returns:
        dict: 包含 det/rec/cls 模型路径的字典
    """
    if version is None:
        version = detect_model_version()
    
    if version == "v5":
        return {
            "det": MODEL_DIR / "ch_PP-OCRv5_mobile_det_infer.onnx",
            "rec": MODEL_DIR / "ch_PP-OCRv5_mobile_rec_infer.onnx",
            "cls": MODEL_DIR / "ch_ppocr_mobile_v2.0_cls_infer.onnx",
            "dict": None,  # V5字典已嵌入模型
        }
    else:  # v4
        return {
            "det": MODEL_DIR / "ch_PP-OCRv4_det_infer.onnx",
            "rec": MODEL_DIR / "ch_PP-OCRv4_rec_infer.onnx",
            "cls": MODEL_DIR / "ch_ppocr_mobile_v2.0_cls_infer.onnx",
            "dict": MODEL_DIR / "ppocr_keys_v1.txt",
        }


def check_models():
    """检查模型文件是否存在"""
    version = detect_model_version()
    paths = get_model_paths(version)
    
    missing = []
    for key, path in paths.items():
        if key == "dict":
            continue
        if path and not path.exists():
            missing.append(path.name)
    
    if missing:
        print(f"[ERROR] 缺少模型文件: {missing}", file=sys.stderr)
        print(f"[INFO] 模型目录: {MODEL_DIR}", file=sys.stderr)
        print(f"[INFO] 请运行以下命令下载模型:", file=sys.stderr)
        print(f"       python scripts/setup_v5_models.py", file=sys.stderr)
        return False
    
    return True


def get_ocr_engine():
    """获取或初始化OCR引擎（单例模式）"""
    global _ocr_engine
    
    if _ocr_engine is not None:
        return _ocr_engine
    
    try:
        from rapidocr_onnxruntime import RapidOCR
    except ImportError as e:
        print(f"[ERROR] 未安装 rapidocr_onnxruntime: {e}", file=sys.stderr)
        print("[INFO] 请运行: pip install rapidocr_onnxruntime", file=sys.stderr)
        sys.exit(2)
    
    version = detect_model_version()
    paths = get_model_paths(version)
    
    start_time = time.time()
    print(f"[PROGRESS] 正在初始化 RapidOCR {version.upper()}...", file=sys.stderr, flush=True)
    print(f"[INFO] 模型目录: {MODEL_DIR}", file=sys.stderr, flush=True)
    print(f"[INFO] 推理线程: {THREAD_NUM}", file=sys.stderr, flush=True)
    
    try:
        # 构建初始化参数
        init_params = {
            "det_model_path": str(paths["det"]),
            "rec_model_path": str(paths["rec"]),
            "cls_model_path": str(paths["cls"]) if paths["cls"].exists() else None,
            "thread_num": THREAD_NUM,
        }

        # V4需要指定字典路径，V5字典已嵌入模型
        if version == "v4" and paths["dict"] and paths["dict"].exists():
            init_params["rec_keys_path"] = str(paths["dict"])

        _ocr_engine = RapidOCR(**init_params)
        
        elapsed = time.time() - start_time
        print(f"[PROGRESS] RapidOCR {version.upper()} 初始化完成，耗时 {elapsed:.1f} 秒", file=sys.stderr, flush=True)
        
    except Exception as e:
        print(f"[ERROR] OCR引擎初始化失败: {e}", file=sys.stderr)
        raise
    
    return _ocr_engine


def process_single_image(ocr_engine, img_path):
    """
    处理单张图片
    
    Returns:
        tuple: (page_items列表, 耗时秒数)
    """
    result, elapse = ocr_engine(img_path)
    
    page_items = []
    for item in result:
        if len(item) >= 3:
            box, text, score = item[0], item[1], item[2]
            page_items.append({
                "text": text,
                "text_region": [[int(p[0]), int(p[1])] for p in box] if box else [],
                "confidence": float(score) if isinstance(score, (int, float)) else 0.99
            })
    
    # 确保 elapse 是数字（RapidOCR 可能返回列表）
    if isinstance(elapse, (list, tuple)):
        elapse = sum(elapse) if elapse else 0.0
    elif not isinstance(elapse, (int, float)):
        elapse = 0.0
    
    return page_items, float(elapse)


def process_images(inputs):
    """
    批量处理图片
    
    Args:
        inputs: 图片路径列表
        
    Returns:
        dict: 包含pages和统计信息的结果字典
    """
    ocr = get_ocr_engine()
    version = detect_model_version()
    total = len(inputs)
    
    print(f"[PROGRESS] 开始识别 {total} 张图片...", file=sys.stderr, flush=True)
    
    start_time = time.time()
    pages = []
    total_elapse = 0
    
    for idx, img_path in enumerate(inputs):
        print(f"[PROGRESS] 处理第 {idx + 1}/{total} 页: {Path(img_path).name}", file=sys.stderr, flush=True)
        
        try:
            if not Path(img_path).exists():
                print(f"[WARNING] 文件不存在: {img_path}", file=sys.stderr)
                pages.append([])
                continue
            
            page_items, elapse = process_single_image(ocr, img_path)
            pages.append(page_items)
            total_elapse += elapse
            
            print(f"[PROGRESS] 识别 {len(page_items)} 个文本块，耗时 {elapse:.2f}s", file=sys.stderr, flush=True)
            
        except Exception as e:
            print(f"[ERROR] 处理 {img_path} 失败: {e}", file=sys.stderr)
            pages.append([])
    
    total_time = time.time() - start_time
    
    print(f"[PROGRESS] OCR 识别完成，总耗时 {total_time:.1f} 秒", file=sys.stderr, flush=True)
    
    return {
        "pages": pages,
        "page_count": len(pages),
        "model_version": version,
        "total_time": round(total_time, 3),
        "avg_time_per_page": round(total_time / total, 3) if total > 0 else 0
    }


def test_mode():
    """测试模式"""
    print("[TEST] RapidOCR 测试模式", file=sys.stderr)
    
    # 检查模型
    if not check_models():
        return 1
    
    # 测试初始化
    try:
        version = detect_model_version()
        ocr = get_ocr_engine()
        print(f"[TEST] RapidOCR {version.upper()} 初始化成功！", file=sys.stderr)
        
        # 输出状态信息
        result = {
            "status": "ok",
            "model_version": version,
            "model_dir": str(MODEL_DIR),
            "thread_num": THREAD_NUM
        }
        print(json.dumps(result, ensure_ascii=False))
        return 0
        
    except Exception as e:
        print(f"[TEST] 失败: {e}", file=sys.stderr)
        return 2


def version_mode():
    """版本信息模式"""
    try:
        from rapidocr_onnxruntime import RapidOCR
        import rapidocr_onnxruntime
        version_info = {
            "rapidocr_version": getattr(rapidocr_onnxruntime, "__version__", "unknown"),
            "model_version": detect_model_version(),
            "model_dir": str(MODEL_DIR),
            "thread_num": THREAD_NUM,
            "python_version": sys.version
        }
        print(json.dumps(version_info, ensure_ascii=False, indent=2))
    except ImportError:
        print("[ERROR] rapidocr_onnxruntime 未安装", file=sys.stderr)
        return 1
    return 0


def main():
    """主函数"""
    if len(sys.argv) < 2:
        print("Usage: python rapidocr_wrapper.py <img1> [img2] ...", file=sys.stderr)
        print("       python rapidocr_wrapper.py --test", file=sys.stderr)
        print("       python rapidocr_wrapper.py --version", file=sys.stderr)
        print("", file=sys.stderr)
        print("Environment Variables:", file=sys.stderr)
        print("       RAPIDOCR_MODEL_DIR: 模型目录", file=sys.stderr)
        print("       RAPIDOCR_MODEL_VERSION: v4 或 v5", file=sys.stderr)
        print("       RAPIDOCR_THREAD_NUM: 推理线程数", file=sys.stderr)
        sys.exit(1)
    
    # 测试模式
    if sys.argv[1] == '--test':
        sys.exit(test_mode())
    
    # 版本模式
    if sys.argv[1] == '--version':
        sys.exit(version_mode())
    
    # 正常OCR模式
    inputs = sys.argv[1:]
    
    # 检查模型
    if not check_models():
        sys.exit(2)
    
    # 检查输入文件
    missing = [f for f in inputs if not Path(f).exists()]
    if missing:
        print(f"[ERROR] 文件不存在: {missing}", file=sys.stderr)
        sys.exit(2)
    
    try:
        result = process_images(inputs)
        print(json.dumps(result, ensure_ascii=False))
    except Exception as e:
        import traceback
        error_details = {
            "error": str(e),
            "traceback": traceback.format_exc()
        }
        print(json.dumps(error_details, ensure_ascii=False), file=sys.stderr)
        sys.exit(2)


if __name__ == '__main__':
    main()
