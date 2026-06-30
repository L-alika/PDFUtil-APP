#!/usr/bin/env node
/**
 * 从 PNG 创建 ICO 文件
 * ICO 格式支持嵌入 PNG 数据
 */

const fs = require('fs');
const path = require('path');

const assetsDir = path.join(__dirname, 'assets');
const pngPath = path.join(assetsDir, 'icon.png');
const icoPath = path.join(assetsDir, 'icon.ico');

// 读取 PNG 文件
const pngData = fs.readFileSync(pngPath);

// ICO 文件结构
// ICONDIR header (6 bytes)
// + ICONDIRENTRY (16 bytes per image)
// + Image data

// ICONDIR
const iconDir = Buffer.alloc(6);
iconDir.writeUInt16LE(0, 0);    // Reserved, must be 0
iconDir.writeUInt16LE(1, 2);    // Image type: 1 = ICO
iconDir.writeUInt16LE(1, 4);    // Number of images

// ICONDIRENTRY
const iconDirEntry = Buffer.alloc(16);
iconDirEntry.writeUInt8(0, 0);        // Width (0 = 256)
iconDirEntry.writeUInt8(0, 1);        // Height (0 = 256)
iconDirEntry.writeUInt8(0, 2);        // Color palette
iconDirEntry.writeUInt8(0, 3);        // Reserved
iconDirEntry.writeUInt16LE(1, 4);     // Color planes
iconDirEntry.writeUInt16LE(32, 6);    // Bits per pixel
iconDirEntry.writeUInt32LE(pngData.length, 8);  // Size of image data
iconDirEntry.writeUInt32LE(22, 12);   // Offset to image data (6 + 16 = 22)

// 组合 ICO 文件
const ico = Buffer.concat([iconDir, iconDirEntry, pngData]);

fs.writeFileSync(icoPath, ico);
console.log(`Created: icon.ico (${ico.length} bytes)`);
console.log('\nIcon files ready for packaging!');