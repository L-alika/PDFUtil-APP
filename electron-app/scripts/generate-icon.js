const sharp = require('sharp');
const path = require('path');
const fs = require('fs');

const assetsDir = path.join(__dirname, '..', 'assets');
const svgPath = path.join(assetsDir, 'icon.svg');
const appIconPngPath = path.join(assetsDir, 'icon-source-app.png');

// 生成不同尺寸的 PNG
const sizes = [16, 32, 48, 64, 128, 256];

function resolveIconSource() {
    if (fs.existsSync(appIconPngPath)) {
        console.log(`Using app icon source PNG: ${appIconPngPath}`);
        return { input: fs.readFileSync(appIconPngPath), label: 'PNG' };
    }

    if (fs.existsSync(svgPath)) {
        console.log(`Using fallback SVG icon source: ${svgPath}`);
        return { input: fs.readFileSync(svgPath), label: 'SVG' };
    }

    throw new Error(`No icon source found. Expected ${appIconPngPath} or ${svgPath}`);
}

async function generateIcons() {
    console.log('Generating icon files...');

    // 确保 assets 目录存在
    if (!fs.existsSync(assetsDir)) {
        fs.mkdirSync(assetsDir, { recursive: true });
    }

    const iconSource = resolveIconSource();
    const sourceBuffer = iconSource.input;

    // 生成 256x256 PNG (用于 Linux 和 Mac)
    await sharp(sourceBuffer)
        .resize(256, 256)
        .png()
        .toFile(path.join(assetsDir, 'icon.png'));
    console.log(`✓ Generated icon.png (256x256) from ${iconSource.label}`);

    // 生成 Mac 图标 (需要 icns 格式，这里先生成高分辨率 PNG)
    await sharp(sourceBuffer)
        .resize(512, 512)
        .png()
        .toFile(path.join(assetsDir, 'icon-512.png'));
    console.log(`✓ Generated icon-512.png (512x512) from ${iconSource.label}`);

    // 生成 Windows 图标所需的各种尺寸
    for (const size of sizes) {
        await sharp(sourceBuffer)
            .resize(size, size)
            .png()
            .toFile(path.join(assetsDir, `icon-${size}.png`));
        console.log(`✓ Generated icon-${size}.png from ${iconSource.label}`);
    }

    console.log('\n注意: Windows .ico 文件需要使用在线工具或额外工具转换');
    console.log('推荐使用: https://convertio.co/zh/png-ico/');
    console.log('将生成的 icon-256.png 转换为 icon.ico');
}

generateIcons().catch(err => {
    console.error('Error generating icons:', err);
    process.exit(1);
});
