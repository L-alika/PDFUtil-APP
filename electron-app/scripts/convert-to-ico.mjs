import pngToIco from 'png-to-ico';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const assetsDir = path.join(__dirname, '..', 'assets');

async function convertToIco() {
    try {
        // 包含多种尺寸的 ICO 文件
        const files = [
            path.join(assetsDir, 'icon-16.png'),
            path.join(assetsDir, 'icon-32.png'),
            path.join(assetsDir, 'icon-48.png'),
            path.join(assetsDir, 'icon-128.png'),
            path.join(assetsDir, 'icon-256.png'),
        ];

        const buf = await pngToIco(files);
        fs.writeFileSync(path.join(assetsDir, 'icon.ico'), buf);
        console.log('✓ Generated icon.ico with multiple sizes');
    } catch (err) {
        console.error('Error creating ICO:', err);

        // 备用方案：如果只使用 256x256
        try {
            const buf = await pngToIco([path.join(assetsDir, 'icon-256.png')]);
            fs.writeFileSync(path.join(assetsDir, 'icon.ico'), buf);
            console.log('✓ Generated icon.ico (256x256 only)');
        } catch (err2) {
            console.error('Failed to create ICO:', err2);
        }
    }
}

convertToIco();
