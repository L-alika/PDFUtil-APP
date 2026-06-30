#!/usr/bin/env node
/**
 * 简单图标生成脚本
 * 用于生成 Electron 应用所需的图标文件
 *
 * 使用方法:
 * 1. 安装依赖: npm install sharp
 * 2. 运行脚本: node generate-icon.js
 */

const fs = require('fs');
const path = require('path');

// 如果安装了 sharp，可以使用它来生成图标
// 否则，创建一个简单的占位提示文件

const assetsDir = path.join(__dirname, 'assets');

// 确保目录存在
if (!fs.existsSync(assetsDir)) {
    fs.mkdirSync(assetsDir, { recursive: true });
}

// 创建一个说明文件
const readme = `# 图标文件说明

## 所需图标文件

请将以下图标文件放置在此目录：

1. **icon.ico** - Windows 应用图标 (256x256 或更大，多尺寸 ICO 格式)
2. **icon.png** - Linux 应用图标 (512x512 PNG)
3. **icon.icns** - macOS 应用图标 (512x512 ICNS 格式)

## 推荐图标设计

- 主色调: 蓝色/紫色渐变 (#667eea → #764ba2)
- 图标元素: PDF 文档 + 转换箭头
- 风格: 扁平化、简洁

## 在线图标生成工具

- ICO 生成: https://icoconvert.com/
- ICNS 生成: https://cloudconvert.com/png-to-icns
- 图标设计: https://www.canva.com/

## 临时解决方案

如果没有图标文件，可以暂时将 package.json 中的 icon 配置注释掉。
`;

fs.writeFileSync(path.join(assetsDir, 'README.md'), readme, 'utf-8');

console.log('图标目录已准备就绪');
console.log('请参考 README.md 添加图标文件');