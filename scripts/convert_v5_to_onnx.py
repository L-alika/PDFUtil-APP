#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PP-OCRv5 模型转换为 ONNX 格式脚本
用于将 PaddleOCRv5 模型转换为 RapidOCR 可用的 ONNX 格式

需要安装的依赖:
    pip install paddle2onnx onnx onnxruntime

使用方法:
    python convert_v5_to_onnx.py --input_dir ./PP-OCRv5_mobile_rec --output_dir ./v5_onnx
"""

import os
import sys
import argparse
import shutil
from pathlib import Path
from typing import List, Union

import onnx
import onnxruntime as ort
from onnx import ModelProto


def read_txt(txt_path: Union[Path, str]) -> List[str]:
    """读取字典文件"""
    with open(txt_path, "r", encoding="utf-8") as f:
        data = [v.rstrip("\n") for v in f]
    return data


class ONNXMetaOp:
    """ONNX 模型元数据操作类"""
    
    @classmethod
    def add_meta(
        cls,
        model_path: Union[str, Path],
        key: str,
        value: List[str],
        delimiter: str = "\n",
    ) -> ModelProto:
        """添加元数据到 ONNX 模型"""
        model = onnx.load_model(model_path)
        meta = model.metadata_props.add()
        meta.key = key
        meta.value = delimiter.join(value)
        return model

    @classmethod
    def get_meta(
        cls, model_path: Union[str, Path], key: str, split_sym: str = "\n"
    ) -> List[str]:
        """从 ONNX 模型获取元数据"""
        sess = ort.InferenceSession(model_path)
        meta_map = sess.get_modelmeta().custom_metadata_map
        key_content = meta_map.get(key)
        if key_content:
            key_list = key_content.split(split_sym)
            return key_list
        return []

    @classmethod
    def del_meta(cls, model_path: Union[str, Path]) -> ModelProto:
        """删除 ONNX 模型的所有元数据"""
        model = onnx.load_model(model_path)
        del model.metadata_props[:]
        return model

    @classmethod
    def save_model(cls, save_path: Union[str, Path], model: ModelProto):
        """保存 ONNX 模型"""
        onnx.save_model(model, save_path)


def convert_paddle_to_onnx(input_dir: str, output_path: str, model_type: str = "rec"):
    """
    使用 paddle2onnx 转换 Paddle 模型到 ONNX
    
    Args:
        input_dir: Paddle 模型目录（包含 inference.pdmodel 和 inference.pdiparams）
        output_path: 输出 ONNX 文件路径
        model_type: 模型类型 (det/rec/cls)
    """
    import subprocess
    
    # 检查输入文件
    pdmodel = os.path.join(input_dir, "inference.pdmodel")
    pdiparams = os.path.join(input_dir, "inference.pdiparams")
    
    if not os.path.exists(pdmodel):
        # 尝试其他命名
        pdmodel = os.path.join(input_dir, "model.pdmodel")
        pdiparams = os.path.join(input_dir, "model.pdiparams")
    
    if not os.path.exists(pdmodel):
        raise FileNotFoundError(f"找不到 Paddle 模型文件: {input_dir}")
    
    print(f"[INFO] 转换模型: {input_dir}")
    print(f"[INFO] 输出路径: {output_path}")
    
    # 构建 paddle2onnx 命令
    cmd = [
        "paddle2onnx",
        "--model_dir", input_dir,
        "--model_filename", os.path.basename(pdmodel),
        "--params_filename", os.path.basename(pdiparams),
        "--save_file", output_path,
        "--opset_version", "14",
        "--enable_onnx_checker", "True",
    ]
    
    # V5 检测模型需要特殊处理
    if model_type == "det":
        cmd.extend(["--input_shape_dict", "{'x':[1,3,640,640]}"])
    
    print(f"[INFO] 执行命令: {' '.join(cmd)}")
    
    result = subprocess.run(cmd, capture_output=True, text=True)
    
    if result.returncode != 0:
        print(f"[ERROR] 转换失败:\n{result.stderr}")
        return False
    
    print(f"[OK] 转换成功: {output_path}")
    return True


def add_dict_to_rec_model(onnx_path: str, dict_path: str):
    """
    将字典文件添加到识别模型的 ONNX metadata 中
    RapidOCR 需要从这个 metadata 中读取字典
    """
    print(f"[INFO] 添加字典到模型: {onnx_path}")
    
    if not os.path.exists(dict_path):
        raise FileNotFoundError(f"找不到字典文件: {dict_path}")
    
    # 读取字典
    dicts = read_txt(dict_path)
    print(f"[INFO] 字典包含 {len(dicts)} 个字符")
    
    # 添加到模型
    model = ONNXMetaOp.add_meta(onnx_path, key="character", value=dicts)
    ONNXMetaOp.save_model(onnx_path, model)
    
    # 验证
    verify_dicts = ONNXMetaOp.get_meta(onnx_path, key="character")
    print(f"[OK] 字典已嵌入模型，验证字符数: {len(verify_dicts)}")


def main():
    parser = argparse.ArgumentParser(description="PP-OCRv5 模型转换工具")
    parser.add_argument("--input_dir", required=True, help="Paddle 模型输入目录")
    parser.add_argument("--output_dir", default="./v5_onnx", help="ONNX 模型输出目录")
    parser.add_argument("--model_type", default="rec", choices=["det", "rec", "cls"], 
                        help="模型类型: det(检测), rec(识别), cls(分类)")
    parser.add_argument("--dict_path", default=None, help="识别模型的字典文件路径（仅 rec 类型需要）")
    parser.add_argument("--model_name", default=None, help="输出模型文件名（不含扩展名）")
    
    args = parser.parse_args()
    
    # 创建输出目录
    os.makedirs(args.output_dir, exist_ok=True)
    
    # 确定输出文件名
    if args.model_name:
        output_name = f"{args.model_name}.onnx"
    else:
        # 根据输入目录名生成
        dir_name = os.path.basename(os.path.normpath(args.input_dir))
        output_name = f"{dir_name}.onnx"
    
    output_path = os.path.join(args.output_dir, output_name)
    
    # 转换模型
    success = convert_paddle_to_onnx(args.input_dir, output_path, args.model_type)
    
    if not success:
        sys.exit(1)
    
    # 如果是识别模型，添加字典
    if args.model_type == "rec":
        if args.dict_path and os.path.exists(args.dict_path):
            add_dict_to_rec_model(output_path, args.dict_path)
        else:
            # 尝试自动查找字典文件
            auto_dict_paths = [
                os.path.join(args.input_dir, "ppocrv5_dict.txt"),
                os.path.join(args.input_dir, "ppocr_keys_v1.txt"),
                os.path.join(args.input_dir, "dict.txt"),
            ]
            for dict_path in auto_dict_paths:
                if os.path.exists(dict_path):
                    print(f"[INFO] 自动找到字典文件: {dict_path}")
                    add_dict_to_rec_model(output_path, dict_path)
                    break
            else:
                print(f"[WARNING] 未找到字典文件，请使用 --dict_path 指定")
    
    print(f"\n[OK] 转换完成！输出文件: {output_path}")


if __name__ == "__main__":
    main()
