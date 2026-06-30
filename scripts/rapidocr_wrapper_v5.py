#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RapidOCR V5 版本包装脚本
兼容 paddleocr_wrapper.py 接口，使用 PP-OCRv5 模型

用法:
    python rapidocr_wrapper_v5.py <img1> [img2] ...
"""

import os
import sys
import json
import time

# V5 模型目录
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MODEL_DIR = os.path.join(SCRIPT_DIR, "..", "models", "rapidocr_v5")

# 如果 V5 目录不存在，提示用户
if not os.path.exists(MODEL_DIR):
    print(f"[ERROR] V5 模型目录不存在: {MODEL_DIR}", file=sys.stderr)
    print("[INFO] 请先运行 setup_v5_models.py 下载并转换 V5 模型", file=sys.stderr)
    print("[INFO] 或使用 rapidocr_wrapper.py (V4 版本)", file=sys.stderr)
    sys.exit(2)

# 禁用联网
os.environ['HF_HUB_OFFLINE'] = '1'
os.environ['TRANSFORMERS_OFFLINE'] = '1'

_ocr_engine = None


def get_ocr_engine():
    """初始化 RapidOCR V5"""
    global _ocr_engine
    if _ocr_engine is None:
        from rapidocr_onnxruntime import RapidOCR
        
        start_time = time.time()
        print(f"[PROGRESS] 初始化 RapidOCR V5...", file=sys.stderr, flush=True)
        print(f"[INFO] 模型目录: {MODEL_DIR}", file=sys.stderr, flush=True)
        
        # 检查模型文件
        required_models = [
            "ch_PP-OCRv5_mobile_det_infer.onnx",
            "ch_PP-OCRv5_mobile_rec_infer.onnx",
            "ch_ppocr_mobile_v2.0_cls_infer.onnx",
        ]
        
        for model in required_models:
            path = os.path.join(MODEL_DIR, model)
            if not os.path.exists(path):
                print(f"[ERROR] 缺少模型文件: {model}", file=sys.stderr)
                sys.exit(2)
        
        try:
            _ocr_engine = RapidOCR(
                det_model_path=os.path.join(MODEL_DIR, "ch_PP-OCRv5_mobile_det_infer.onnx"),
                rec_model_path=os.path.join(MODEL_DIR, "ch_PP-OCRv5_mobile_rec_infer.onnx"),
                cls_model_path=os.path.join(MODEL_DIR, "ch_ppocr_mobile_v2.0_cls_infer.onnx"),
                # V5 字典已嵌入模型，不需要额外指定
                rec_keys_path=None,
                thread_num=4,
            )
            
            elapsed = time.time() - start_time
            print(f"[PROGRESS] RapidOCR V5 初始化完成，耗时 {elapsed:.1f} 秒", file=sys.stderr, flush=True)
            
        except Exception as e:
            print(f"[ERROR] 初始化失败: {e}", file=sys.stderr)
            raise
    
    return _ocr_engine


def process_images(inputs):
    """处理图片列表"""
    ocr = get_ocr_engine()
    total = len(inputs)
    
    print(f"[PROGRESS] 开始识别 {total} 张图片...", file=sys.stderr, flush=True)
    
    start_time = time.time()
    pages = []
    
    for idx, img_path in enumerate(inputs):
        try:
            print(f"[PROGRESS] 处理第 {idx + 1}/{total} 页: {os.path.basename(img_path)}", file=sys.stderr, flush=True)
            
            if not os.path.exists(img_path):
                print(f"[WARNING] 文件不存在: {img_path}", file=sys.stderr)
                pages.append([])
                continue
            
            result, elapse = ocr(img_path)
            
            page_items = []
            for item in result:
                if len(item) >= 3:
                    box, text, score = item[0], item[1], item[2]
                    page_items.append({
                        "text": text,
                        "text_region": [[int(p[0]), int(p[1])] for p in box] if box else [],
                        "confidence": float(score) if isinstance(score, (int, float)) else 0.99
                    })
            
            pages.append(page_items)
            print(f"[PROGRESS] 第 {idx + 1}页完成，识别 {len(page_items)} 个文本块，耗时 {elapse:.2f}s", file=sys.stderr, flush=True)
            
        except Exception as e:
            print(f"[ERROR] 处理 {img_path} 失败: {e}", file=sys.stderr)
            pages.append([])
    
    elapsed = time.time() - start_time
    print(f"[PROGRESS] OCR 识别完成，总耗时 {elapsed:.1f} 秒", file=sys.stderr, flush=True)
    
    return {
        "pages": pages,
        "page_count": len(pages)
    }


def main():
    if len(sys.argv) < 2:
        print("Usage: python rapidocr_wrapper_v5.py <img1> [img2] ...", file=sys.stderr)
        print("       python rapidocr_wrapper_v5.py --test", file=sys.stderr)
        sys.exit(1)
    
    # 测试模式
    if sys.argv[1] == '--test':
        print("[TEST] 测试 RapidOCR V5 环境...", file=sys.stderr)
        try:
            ocr = get_ocr_engine()
            print("[TEST] RapidOCR V5 初始化成功！", file=sys.stderr)
            print(json.dumps({"status": "ok", "message": "RapidOCR V5 ready"}, ensure_ascii=False))
            sys.exit(0)
        except Exception as e:
            print(f"[TEST] 失败: {e}", file=sys.stderr)
            sys.exit(2)
    
    inputs = sys.argv[1:]
    
    missing = [f for f in inputs if not os.path.exists(f)]
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
