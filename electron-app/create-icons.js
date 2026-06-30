#!/usr/bin/env node
/**
 * 使用纯 Node.js 生成简单的 PNG 图标
 * 不依赖外部库
 */

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const assetsDir = path.join(__dirname, 'assets');

// 确保目录存在
if (!fs.existsSync(assetsDir)) {
    fs.mkdirSync(assetsDir, { recursive: true });
}

// 创建简单的 PNG 图标
function createSimplePNG(width, height, filename) {
    // PNG 文件结构
    const signature = Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]);

    // 创建 IHDR chunk
    function createIHDR(width, height) {
        const data = Buffer.alloc(13);
        data.writeUInt32BE(width, 0);
        data.writeUInt32BE(height, 4);
        data[8] = 8;  // bit depth
        data[9] = 2;  // color type (RGB)
        data[10] = 0; // compression
        data[11] = 0; // filter
        data[12] = 0; // interlace
        return createChunk('IHDR', data);
    }

    // 创建 chunk
    function createChunk(type, data) {
        const length = Buffer.alloc(4);
        length.writeUInt32BE(data.length, 0);

        const typeBuffer = Buffer.from(type);
        const crcData = Buffer.concat([typeBuffer, data]);
        const crc = crc32(crcData);

        const crcBuffer = Buffer.alloc(4);
        crcBuffer.writeUInt32BE(crc >>> 0, 0);

        return Buffer.concat([length, typeBuffer, data, crcBuffer]);
    }

    // CRC32 计算
    function crc32(buf) {
        let crc = 0xFFFFFFFF;
        const table = [];
        for (let i = 0; i < 256; i++) {
            let c = i;
            for (let j = 0; j < 8; j++) {
                c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
            }
            table[i] = c;
        }
        for (let i = 0; i < buf.length; i++) {
            crc = table[(crc ^ buf[i]) & 0xFF] ^ (crc >>> 8);
        }
        return crc ^ 0xFFFFFFFF;
    }

    // 创建 IDAT chunk (图像数据)
    function createIDAT(width, height) {
        const rawData = [];

        for (let y = 0; y < height; y++) {
            rawData.push(0); // filter type: none
            for (let x = 0; x < width; x++) {
                // 计算渐变色
                const cx = x / width;
                const cy = y / height;

                // 检查是否在圆角矩形内
                const cornerRadius = width * 0.15;
                const inRect = isInRoundedRect(x, y, width, height, cornerRadius);

                if (inRect) {
                    // 红色背景: #dc2626 (220, 38, 38)
                    const bgR = 220;
                    const bgG = 38;
                    const bgB = 38;

                    // 检查是否需要绘制白色 PDF 图标
                    const iconArea = isInPDFIcon(x, y, width, height);
                    if (iconArea.inIcon) {
                        // 绘制白色 PDF 图标
                        rawData.push(255, 255, 255);
                    } else {
                        // 红色背景
                        rawData.push(bgR, bgG, bgB);
                    }
                } else {
                    // 透明区域 (使用白色作为简化)
                    rawData.push(255, 255, 255);
                }
            }
        }

        const compressed = zlib.deflateSync(Buffer.from(rawData));
        return createChunk('IDAT', compressed);
    }

    // 检查点是否在圆角矩形内
    function isInRoundedRect(x, y, w, h, r) {
        // 简化版：假设整个区域都在内
        return true;
    }

    // 检查是否在 PDF 图标区域内
    // 绘制一个简洁的 PDF 文档图标样式
    function isInPDFIcon(x, y, w, h) {
        const result = { inIcon: false };

        // 图标居中，占整体 60% 大小
        const iconSize = Math.min(w, h) * 0.5;
        const centerX = w / 2;
        const centerY = h / 2;
        const iconLeft = centerX - iconSize / 2;
        const iconRight = centerX + iconSize / 2;
        const iconTop = centerY - iconSize / 2;
        const iconBottom = centerY + iconSize / 2;

        // 文档主体区域 (带圆角)
        const cornerRadius = iconSize * 0.1;
        const docLeft = iconLeft + iconSize * 0.1;
        const docRight = iconRight - iconSize * 0.1;
        const docTop = iconTop + iconSize * 0.1;
        const docBottom = iconBottom - iconSize * 0.1;

        // 折角大小
        const foldSize = iconSize * 0.25;

        // 检查是否在文档轮廓内
        if (x >= docLeft && x <= docRight && y >= docTop && y <= docBottom) {
            // 圆角裁剪 - 简单近似
            const isInCorner = (x < docLeft + cornerRadius && y < docTop + cornerRadius) ||
                               (x > docRight - cornerRadius && y < docTop + cornerRadius) ||
                               (x < docLeft + cornerRadius && y > docBottom - cornerRadius) ||
                               (x > docRight - cornerRadius && y > docBottom - cornerRadius);

            if (!isInCorner) {
                // 文档边框线条
                const borderWidth = Math.max(2, iconSize * 0.06);
                const isTopBorder = y < docTop + borderWidth && x < docRight - foldSize;
                const isBottomBorder = y > docBottom - borderWidth;
                const isLeftBorder = x < docLeft + borderWidth;
                const isRightBorder = x > docRight - borderWidth && y > docTop + foldSize;

                // 折角斜线
                const foldX = docRight - foldSize;
                const foldY = docTop + foldSize;
                const isFoldLine = x > foldX && y < foldY &&
                    Math.abs((x - foldX) - (foldY - y)) < borderWidth * 1.5;

                // 折角顶部和右侧边
                const isFoldTop = x > foldX && y < docTop + borderWidth;
                const isFoldRight = x > docRight - borderWidth && y < foldY;

                // PDF 文字 "PDF" 简化为三条横线
                const lineWidth = (docRight - docLeft) * 0.5;
                const lineLeft = docLeft + (docRight - docLeft) * 0.25;
                const lineRight = lineLeft + lineWidth;
                const line1Y = docTop + (docBottom - docTop) * 0.35;
                const line2Y = docTop + (docBottom - docTop) * 0.5;
                const line3Y = docTop + (docBottom - docTop) * 0.65;
                const lineHeight = Math.max(2, iconSize * 0.05);

                const isLine1 = y >= line1Y - lineHeight/2 && y <= line1Y + lineHeight/2 &&
                                x >= lineLeft && x <= lineRight;
                const isLine2 = y >= line2Y - lineHeight/2 && y <= line2Y + lineHeight/2 &&
                                x >= lineLeft && x <= lineRight;
                const isLine3 = y >= line3Y - lineHeight/2 && y <= line3Y + lineHeight/2 &&
                                x >= lineLeft && x <= lineRight * 0.7; // 第三条线短一点

                if (isTopBorder || isBottomBorder || isLeftBorder || isRightBorder ||
                    isFoldLine || isFoldTop || isFoldRight || isLine1 || isLine2 || isLine3) {
                    result.inIcon = true;
                }
            }
        }

        return result;
    }

    // 创建 IEND chunk
    const iend = createChunk('IEND', Buffer.alloc(0));

    // 组合 PNG 文件
    const png = Buffer.concat([
        signature,
        createIHDR(width, height),
        createIDAT(width, height),
        iend
    ]);

    fs.writeFileSync(path.join(assetsDir, filename), png);
    console.log(`Created: ${filename} (${width}x${height})`);
}

// 生成不同尺寸的图标
console.log('Generating PNG icons...');
createSimplePNG(256, 256, 'icon.png');
createSimplePNG(512, 512, 'icon-512.png');

console.log('\nPNG icons generated successfully!');
console.log('\nFor Windows .ico file, you can:');
console.log('1. Use online tool: https://icoconvert.com/');
console.log('2. Upload icon-512.png and convert to .ico');
console.log('3. Save as icon.ico in the assets folder');