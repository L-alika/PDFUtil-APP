#!/usr/bin/env python3
"""
PaddleOCR 3.x 高性能版本
支持批量图片识别
使用 PP-OCRv5 mobile 轻量模型
"""

import json
import sys
import os
import time
import shutil

# 禁止模型联网检查 - 离线环境必须
# 设置所有可能的版本变体名称，确保兼容不同 PaddleOCR 版本
os.environ['DISABLE_MODEL_SOURCE_CHECK'] = 'True'
os.environ['HUB_DISABLE_MODEL_SOURCE_CHECK'] = 'True'
os.environ['PADDLEX_DISABLE_MODEL_SOURCE_CHECK'] = 'True'
os.environ['PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK'] = 'True'
os.environ['PADDLEOCR_DISABLE_MODEL_SOURCE_CHECK'] = 'True'

# 强制使用 CPU
os.environ['CUDA_VISIBLE_DEVICES'] = ''

# 解决 OpenMP 多次加载问题
os.environ['KMP_DUPLICATE_LIB_OK'] = 'TRUE'


def setup_paddle_dll_path():
    """
    设置 PaddlePaddle DLL 搜索路径
    PaddlePaddle 3.x 的 libpaddle.pyd 依赖 paddle/libs 目录下的 DLL
    必须在 import paddle 之前设置
    """
    import sys

    # 可能的 PaddlePaddle 安装路径
    possible_paths = [
        # Python 3.10 常见安装路径
        r"C:\Program Files\Python310\Lib\site-packages\paddle\libs",
        r"C:\Python310\Lib\site-packages\paddle\libs",
        os.path.expandvars(r"%LOCALAPPDATA%\Programs\Python\Python310\Lib\site-packages\paddle\libs"),
        # 用户安装路径
        os.path.expandvars(r"%APPDATA%\Python\Python310\site-packages\paddle\libs"),
    ]

    for libs_path in possible_paths:
        if os.path.exists(libs_path):
            # 添加到 PATH 环境变量
            current_path = os.environ.get('PATH', '')
            if libs_path not in current_path:
                os.environ['PATH'] = libs_path + os.pathsep + current_path
                print(f"[INFO] Added paddle libs to PATH: {libs_path}", file=sys.stderr, flush=True)
            # 添加到 DLL 搜索路径 (Python 3.8+)
            if hasattr(os, 'add_dll_directory'):
                try:
                    os.add_dll_directory(libs_path)
                    print(f"[INFO] Added DLL directory: {libs_path}", file=sys.stderr, flush=True)
                except Exception as e:
                    print(f"[WARN] Failed to add DLL directory: {e}", file=sys.stderr, flush=True)
            return libs_path

    return None


# 在 import paddle 之前设置 DLL 路径
_dll_path = setup_paddle_dll_path()
if _dll_path:
    pass  # DLL path set successfully


def has_chinese(path):
    """检查路径是否包含中文字符"""
    for char in path:
        if '\u4e00' <= char <= '\u9fff':
            return True
    return False


def get_model_paths():
    """获取模型路径，处理中文路径问题"""
    script_dir = os.path.dirname(os.path.abspath(__file__))

    # 检查环境变量是否指定了模型目录
    env_model_dir = os.environ.get('PADDLE_MODEL_DIR')

    if env_model_dir and os.path.exists(env_model_dir):
        model_base = env_model_dir
    else:
        # 默认相对路径
        model_base = os.path.join(script_dir, "..", "models")

    det_model = os.path.join(model_base, "PP-OCRv5_mobile_det")
    rec_model = os.path.join(model_base, "PP-OCRv5_mobile_rec")

    # 如果路径包含中文，复制到临时目录
    if has_chinese(det_model) or has_chinese(rec_model):
        temp_base = os.path.join(os.environ.get('TEMP', 'C:\\Temp'), 'pdfutil_models')

        # 检查是否需要更新（通过检查标记文件）
        marker_file = os.path.join(temp_base, '.copied')
        need_copy = False

        if not os.path.exists(temp_base):
            need_copy = True
        elif not os.path.exists(marker_file):
            need_copy = True
        else:
            # 检查源模型是否更新
            try:
                with open(marker_file, 'r') as f:
                    copied_time = float(f.read().strip())
                src_time = os.path.getmtime(model_base)
                if src_time > copied_time:
                    need_copy = True
            except:
                need_copy = True

        if need_copy:
            print(f"[PROGRESS] 复制模型到临时目录（解决中文路径问题）...", file=sys.stderr, flush=True)
            # 清理旧目录
            if os.path.exists(temp_base):
                shutil.rmtree(temp_base, ignore_errors=True)
            os.makedirs(temp_base, exist_ok=True)

            # 复制模型
            src_det = os.path.join(model_base, "PP-OCRv5_mobile_det")
            src_rec = os.path.join(model_base, "PP-OCRv5_mobile_rec")

            if os.path.exists(src_det):
                shutil.copytree(src_det, os.path.join(temp_base, "PP-OCRv5_mobile_det"))
            if os.path.exists(src_rec):
                shutil.copytree(src_rec, os.path.join(temp_base, "PP-OCRv5_mobile_rec"))

            # 写入标记文件
            with open(marker_file, 'w') as f:
                f.write(str(time.time()))

            print(f"[PROGRESS] 模型复制完成", file=sys.stderr, flush=True)

        det_model = os.path.join(temp_base, "PP-OCRv5_mobile_det")
        rec_model = os.path.join(temp_base, "PP-OCRv5_mobile_rec")

    return det_model, rec_model


# 获取模型路径
DET_MODEL_DIR, REC_MODEL_DIR = get_model_paths()

# 延迟导入和初始化
_ocr_instance = None


def get_ocr_inst():
    """获取或创建 OCR 实例"""
    global _ocr_instance
    if _ocr_instance is not None:
        return _ocr_instance

    from paddleocr import PaddleOCR

    start_time = time.time()
    print(f"[PROGRESS] 正在初始化 OCR 引擎...", file=sys.stderr, flush=True)
    print(f"[INFO] 检测模型: {DET_MODEL_DIR}", file=sys.stderr, flush=True)
    print(f"[INFO] 识别模型: {REC_MODEL_DIR}", file=sys.stderr, flush=True)

    # 检查模型目录是否存在
    if not os.path.exists(DET_MODEL_DIR):
        print(f"[ERROR] 检测模型目录不存在: {DET_MODEL_DIR}", file=sys.stderr, flush=True)
        raise FileNotFoundError(f"检测模型目录不存在: {DET_MODEL_DIR}")
    if not os.path.exists(REC_MODEL_DIR):
        print(f"[ERROR] 识别模型目录不存在: {REC_MODEL_DIR}", file=sys.stderr, flush=True)
        raise FileNotFoundError(f"识别模型目录不存在: {REC_MODEL_DIR}")

    try:
        # PaddleOCR 3.3.0 API - 同时指定模型目录和名称
        _ocr_instance = PaddleOCR(
            text_detection_model_dir=DET_MODEL_DIR,
            text_recognition_model_dir=REC_MODEL_DIR,
            text_detection_model_name='PP-OCRv5_mobile_det',
            text_recognition_model_name='PP-OCRv5_mobile_rec',
            use_doc_orientation_classify=False,
            use_doc_unwarping=False,
            use_textline_orientation=False,
        )

        elapsed = time.time() - start_time
        print(f"[PROGRESS] OCR 引擎初始化完成，耗时 {elapsed:.1f} 秒", file=sys.stderr, flush=True)

    except TypeError:
        # 回退到旧版本参数 (PaddleOCR 2.x)
        print("[INFO] 使用旧版 API 参数...", file=sys.stderr, flush=True)
        try:
            _ocr_instance = PaddleOCR(
                det_model_dir=DET_MODEL_DIR,
                rec_model_dir=REC_MODEL_DIR,
                use_angle_cls=False,
                lang='ch',
                use_gpu=False,
                show_log=False
            )
            elapsed = time.time() - start_time
            print(f"[PROGRESS] OCR 引擎初始化完成，耗时 {elapsed:.1f} 秒", file=sys.stderr, flush=True)
        except Exception as e:
            print(f"[ERROR] OCR 初始化失败: {e}", file=sys.stderr, flush=True)
            raise
    except Exception as e:
        print(f"[ERROR] OCR 初始化失败: {e}", file=sys.stderr, flush=True)
        raise

    return _ocr_instance


def process_images(inputs):
    """处理图片列表，带进度输出"""
    ocr = get_ocr_inst()

    total = len(inputs)
    print(f"[PROGRESS] 开始识别 {total} 张图片...", file=sys.stderr, flush=True)

    start_time = time.time()
    pages = []

    try:
        # PaddleOCR 3.x 使用 predict 方法
        try:
            results = ocr.predict(input=inputs)

            for idx, res in enumerate(results):
                page_items = []

                rec_texts = res.get('rec_texts', [])
                rec_scores = res.get('rec_scores', [])
                rec_polys = res.get('rec_polys', [])

                for i, (text, box) in enumerate(zip(rec_texts, rec_polys)):
                    page_items.append({
                        "text": text,
                        "text_region": [[int(float(x)), int(float(y))] for x, y in box],
                        "confidence": float(rec_scores[i]) if i < len(rec_scores) else 1.0
                    })

                pages.append(page_items)
                print(f"[PROGRESS] 已处理 {idx + 1}/{total} 页", file=sys.stderr, flush=True)

        except AttributeError:
            # 旧版本使用 ocr 方法
            for idx, img_path in enumerate(inputs):
                print(f"[PROGRESS] 处理第 {idx + 1}/{total} 页...", file=sys.stderr, flush=True)

                result = ocr.ocr(img_path, cls=False)

                page_items = []
                if result and result[0]:
                    for line in result[0]:
                        if len(line) >= 2:
                            box = line[0]
                            text_info = line[1]
                            if isinstance(text_info, (list, tuple)) and len(text_info) >= 2:
                                text = text_info[0]
                                confidence = text_info[1]
                            else:
                                text = str(text_info)
                                confidence = 1.0

                            page_items.append({
                                "text": text,
                                "text_region": [[int(p[0]), int(p[1])] for p in box] if box else [],
                                "confidence": float(confidence)
                            })

                pages.append(page_items)

    except Exception as e:
        print(f"[ERROR] OCR 识别失败: {e}", file=sys.stderr, flush=True)
        raise

    elapsed = time.time() - start_time
    print(f"[PROGRESS] OCR 识别完成，耗时 {elapsed:.1f} 秒", file=sys.stderr, flush=True)

    return {
        "pages": pages,
        "page_count": len(pages)
    }


def main():
    if len(sys.argv) < 2:
        print("Usage: python paddleocr_wrapper.py <img1> [img2] ...", file=sys.stderr)
        sys.exit(1)

    inputs = sys.argv[1:]
    print(f"[PROGRESS] 接收到 {len(inputs)} 个输入文件", file=sys.stderr, flush=True)

    # 检查输入文件是否存在
    missing_files = [img for img in inputs if not os.path.exists(img)]

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
        print(json.dumps(error_details, ensure_ascii=False), file=sys.stderr)
        sys.exit(2)


if __name__ == '__main__':
    main()