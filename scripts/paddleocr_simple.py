#!/usr/bin/env python3
"""
PaddleOCR 简化版本 - 解决兼容性问题
"""

import json
import sys
import os

# 设置环境变量
os.environ['HUB_DISABLE_MODEL_SOURCE_CHECK'] = 'True'
os.environ['PADDLEX_DISABLE_MODEL_SOURCE_CHECK'] = 'True'

def process_images(inputs):
    """处理图片列表"""
    try:
        # 使用默认模型创建OCR实例
        from paddleocr import PaddleOCR

        # 使用更简单的配置
        ocr = PaddleOCR(
            use_angle_cls=True,
            lang='ch',
            use_gpu=False,
            show_log=False
        )

        results = []
        for img_path in inputs:
            result = ocr.ocr(img_path, cls=True)

            # 处理结果
            page_items = []
            if result and result[0]:
                for line in result[0]:
                    if len(line) >= 2:
                        text = line[1][0]
                        confidence = line[1][1] if len(line[1]) > 1 else 1.0
                        box = line[0]

                        page_items.append({
                            "text": text,
                            "text_region": box,
                            "confidence": float(confidence)
                        })

            results.append(page_items)

        return {
            "pages": results,
            "page_count": len(results)
        }

    except Exception as e:
        import traceback
        error_details = {
            "error": str(e),
            "traceback": traceback.format_exc(),
            "inputs": inputs
        }
        print(json.dumps(error_details), file=sys.stderr)
        sys.exit(2)

def main():
    if len(sys.argv) < 2:
        print("Usage: python paddleocr_simple.py <img1> <img2> ...", file=sys.stderr)
        sys.exit(1)

    inputs = sys.argv[1:]

    # 检查输入文件是否存在
    missing_files = []
    for img_path in inputs:
        if not os.path.exists(img_path):
            missing_files.append(img_path)

    if missing_files:
        error_msg = f"文件不存在: {', '.join(missing_files)}"
        print(json.dumps({"error": error_msg}), file=sys.stderr)
        sys.exit(2)

    try:
        result = process_images(inputs)
        print(json.dumps(result, ensure_ascii=False))
    except Exception as e:
        import traceback
        error_details = {
            "error": str(e),
            "traceback": traceback.format_exc(),
            "inputs": inputs
        }
        print(json.dumps(error_details), file=sys.stderr)
        sys.exit(2)

if __name__ == '__main__':
    main()