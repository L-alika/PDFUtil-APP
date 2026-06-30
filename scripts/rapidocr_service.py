#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RapidOCR 常驻服务。

协议：
  Java 通过 stdin 每行发送一个 JSON 请求，服务通过 stdout 每行返回一个 JSON 响应。

请求示例：
  {"id":"1","action":"ocr","images":["D:/tmp/a.png"]}
  {"id":"2","action":"ping"}
  {"id":"3","action":"shutdown"}
"""

import json
import sys
import traceback

from rapidocr_wrapper import check_models, detect_model_version, process_images


def write_response(response):
    print(json.dumps(response, ensure_ascii=False), flush=True)


def handle_request(request):
    request_id = request.get("id")
    action = request.get("action", "ocr")

    if action == "ping":
        return {
            "id": request_id,
            "ok": True,
            "status": "ready",
            "model_version": detect_model_version(),
        }

    if action == "shutdown":
        return {
            "id": request_id,
            "ok": True,
            "shutdown": True,
        }

    if action != "ocr":
        return {
            "id": request_id,
            "ok": False,
            "error": f"不支持的 action: {action}",
        }

    images = request.get("images") or []
    if isinstance(images, str):
        images = [images]
    if not images:
        return {
            "id": request_id,
            "ok": False,
            "error": "images 不能为空",
        }

    result = process_images(images)
    result["id"] = request_id
    result["ok"] = True
    return result


def main():
    if not check_models():
        write_response({
            "ok": False,
            "error": "RapidOCR 模型文件不完整",
        })
        return 2

    # 先初始化一次，避免第一条 OCR 请求才触发大量 stderr 初始化日志。
    try:
        process_images([])
    except ZeroDivisionError:
        pass
    except Exception:
        # 空数组初始化在部分版本可能不支持，真实请求里仍会初始化。
        pass

    print("[SERVICE] RapidOCR service ready", file=sys.stderr, flush=True)

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue

        try:
            request = json.loads(line)
            response = handle_request(request)
            write_response(response)
            if response.get("shutdown"):
                break
        except Exception as exc:
            request_id = None
            try:
                request_id = json.loads(line).get("id")
            except Exception:
                pass
            write_response({
                "id": request_id,
                "ok": False,
                "error": str(exc),
                "traceback": traceback.format_exc(),
            })

    return 0


if __name__ == "__main__":
    sys.exit(main())
