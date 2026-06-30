package com.pdfutil.common.core.utils;

import com.pdfutil.common.utils.CommandBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 双层格式转换工具 - PaddleOCR + OCRmyPDF 协同方案
 *
 * 架构设计：
 * 输入文件 (PDF/图片)
 *    ↓
 * PaddleOCR（识字）→ 输出：文本 + 字符坐标
 *    ↓
 * OCRmyPDF（生成双层PDF）→ 输出：双层PDF（原图 + 可搜索文本层）
 *    ↓
 * 最终 PDF
 *
 * @author Alika
 * @date 2025-01-28
 */
public class DualLayerPdfConverter {

    private static final Logger log = LoggerFactory.getLogger(DualLayerPdfConverter.class);

    static {
        try {
            javax.imageio.ImageIO.scanForPlugins();
        } catch (Throwable e) {
            log.warn("Failed to scan ImageIO plugins in DualLayerPdfConverter: {}", e.getMessage());
        }
    }

    // 支持的输入文件格式
    private static final String[] SUPPORTED_FORMATS = {
        "pdf", "jpg", "jpeg", "png", "tif", "tiff", "bmp", "gif"
    };

    // 【质量与性能平衡】PDF提取图片的DPI设置
    public static final int PDF_EXTRACT_DPI = getExtractDpi();
    private static final int PDF_EXTRACT_MAX_DPI = getExtractMaxDpi();
    public static final int PDF_EXTRACT_THREAD_COUNT = getPdfExtractThreadCount();
    private static final boolean ADAPTIVE_DPI_ENABLED = isAdaptiveDpiEnabled();

    // pdf2image 可用性缓存（null=未检测，true=可用，false=不可用）
    private static volatile Boolean pdf2imageAvailable = null;

    /**
     * 是否启用自适应DPI
     * 优先级：系统属性 > 环境变量 > 默认值(true)
     */
    private static boolean isAdaptiveDpiEnabled() {
        // 1. 检查系统属性
        String adaptiveProp = System.getProperty("pdf.adaptive.dpi");
        if (adaptiveProp != null) {
            boolean enabled = Boolean.parseBoolean(adaptiveProp);
            log.info("自适应DPI模式（系统属性）: {}", enabled ? "启用" : "禁用");
            return enabled;
        }

        // 2. 检查环境变量
        String adaptiveEnv = System.getenv("PDF_ADAPTIVE_DPI");
        if (adaptiveEnv != null) {
            boolean enabled = Boolean.parseBoolean(adaptiveEnv);
            log.info("自适应DPI模式（环境变量）: {}", enabled ? "启用" : "禁用");
            return enabled;
        }

        // 3. 默认启用自适应DPI
        log.info("自适应DPI模式（默认）: 启用");
        return true;
    }

    /**
     * 获取PDF提取DPI配置
     * 优先级：系统属性 > 环境变量 > 默认值(300 - 质量与性能平衡)
     */
    private static int getExtractDpi() {
        // 1. 检查系统属性
        String dpiProp = System.getProperty("pdf.extract.dpi");
        if (dpiProp != null && !dpiProp.isEmpty()) {
            try {
                int dpi = Integer.parseInt(dpiProp);
                if (dpi >= 72 && dpi <= 1200) {
                    log.info("使用系统属性配置的PDF提取DPI: {}", dpi);
                    return dpi;
                }
            } catch (NumberFormatException e) {
                log.warn("无效的PDF提取DPI配置: {}", dpiProp);
            }
        }

        // 2. 检查环境变量
        String dpiEnv = System.getenv("PDF_EXTRACT_DPI");
        if (dpiEnv != null && !dpiEnv.isEmpty()) {
            try {
                int dpi = Integer.parseInt(dpiEnv);
                if (dpi >= 72 && dpi <= 1200) {
                    log.info("使用环境变量配置的PDF提取DPI: {}", dpi);
                    return dpi;
                }
            } catch (NumberFormatException e) {
                log.warn("无效的PDF提取DPI环境变量: {}", dpiEnv);
            }
        }

        // 3. 默认使用200 DPI（兼顾OCR识别质量与处理性能，速度提升约30%）
        log.info("使用默认PDF提取DPI: 200 (质量与性能平衡)");
        return 200;
    }

    /**
     * 获取PDF提取DPI上限。
     * 对二次OCR来说，超过300 DPI通常只会显著增加渲染、OCR和PDF生成成本。
     */
    private static int getExtractMaxDpi() {
        int defaultMaxDpi = Math.max(300, PDF_EXTRACT_DPI);
        int maxDpi = getIntConfig("pdf.extract.maxDpi", "PDF_EXTRACT_MAX_DPI",
                defaultMaxDpi, 72, 1200, "PDF提取最大DPI");
        if (maxDpi < PDF_EXTRACT_DPI) {
            log.warn("PDF提取最大DPI({})低于基础DPI({})，使用基础DPI作为上限", maxDpi, PDF_EXTRACT_DPI);
            return PDF_EXTRACT_DPI;
        }
        return maxDpi;
    }

    /**
     * 获取PDF拆图线程数。
     */
    private static int getPdfExtractThreadCount() {
        int defaultThreads = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), 4));
        return getIntConfig("pdf.extract.threadCount", "PDF_EXTRACT_THREAD_COUNT",
                defaultThreads, 1, 16, "PDF拆图线程数");
    }

    private static int getIntConfig(String propertyName, String envName, int defaultValue,
                                    int min, int max, String description) {
        String propValue = System.getProperty(propertyName);
        if (propValue != null && !propValue.isEmpty()) {
            try {
                int value = Integer.parseInt(propValue);
                if (value >= min && value <= max) {
                    log.info("使用系统属性配置的{}: {}", description, value);
                    return value;
                }
                log.warn("{}系统属性超出范围({}-{}): {}", description, min, max, propValue);
            } catch (NumberFormatException e) {
                log.warn("无效的{}系统属性: {}", description, propValue);
            }
        }

        String envValue = System.getenv(envName);
        if (envValue != null && !envValue.isEmpty()) {
            try {
                int value = Integer.parseInt(envValue);
                if (value >= min && value <= max) {
                    log.info("使用环境变量配置的{}: {}", description, value);
                    return value;
                }
                log.warn("{}环境变量超出范围({}-{}): {}", description, min, max, envValue);
            } catch (NumberFormatException e) {
                log.warn("无效的{}环境变量: {}", description, envValue);
            }
        }

        log.info("使用默认{}: {}", description, defaultValue);
        return defaultValue;
    }

    // OCR方案类型
    private static final String OCR_TYPE_LOCAL = "local";
    private static final String OCR_TYPE_LOCAL_PADDLE = "local_paddle";  // 本地PaddleOCR
    private static final String OCR_TYPE_RAPID = "rapid";  // RapidOCR（推荐）

    /**
     * 将图片/PDF转换为单层PDF（无OCR文字层）
     *
     * @param inputPath 输入文件路径
     * @param outputPath 输出的单层PDF文件路径
     * @throws Exception 转换过程中可能抛出的异常
     */
    public static void convertToSingleLayerPdf(String inputPath, String outputPath) throws Exception {
        log.info("开始单层PDF转换: {} -> {}", inputPath, outputPath);

        // 参数校验
        if (inputPath == null || inputPath.trim().isEmpty()) {
            throw new IllegalArgumentException("输入文件路径不能为空");
        }
        if (outputPath == null || outputPath.trim().isEmpty()) {
            throw new IllegalArgumentException("输出文件路径不能为空");
        }

        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            throw new FileNotFoundException("输入文件不存在: " + inputPath);
        }

        // 确保输出目录存在
        File outputFile = new File(outputPath);
        File outputDir = outputFile.getParentFile();
        if (outputDir != null && !outputDir.exists()) {
            outputDir.mkdirs();
        }

        try {
            // 如果是图片，直接转换为PDF
            if (isImageFile(inputPath)) {
                convertImageToSingleLayerPdf(inputPath, outputPath);
            } else if (isPdfFile(inputPath)) {
                // 输入是PDF，已经是单层，直接复制（带重试机制）
                copyFileWithRetry(inputFile, outputFile);
                log.info("PDF文件已复制: {}", outputPath);
            } else {
                throw new IllegalArgumentException("不支持的文件类型: " + getFileExtension(inputPath));
            }

            log.info("单层PDF转换成功: {}", outputPath);

        } catch (Exception e) {
            log.error("单层PDF转换失败: {}", inputPath, e);
            throw e;
        }
    }

    /**
     * 将图片转换为单层PDF（无OCR文字层）
     *
     * @param imagePath 图片文件路径
     * @param pdfPath 输出PDF路径
     * @throws Exception 转换失败时抛出异常
     */
    public static void convertImageToSingleLayerPdf(String imagePath, String pdfPath) throws Exception {
        log.info("将图片转换为单层PDF: {} -> {}", imagePath, pdfPath);

        // 使用 Python PIL 库将图片转换为 PDF
        String pythonPath = getPythonPath();
        String normalizedImagePath = imagePath.replace("\\", "/");
        String normalizedPdfPath = pdfPath.replace("\\", "/");

        // 使用配置的 DPI
        String[] command = {
            pythonPath, "-c",
            String.format(
                "from PIL import Image\n" +
                "img = Image.open('%s')\n" +
                "if img.mode != 'RGB':\n" +
                "    img = img.convert('RGB')\n" +
                "img.save('%s', 'PDF', resolution=%.1f, save_all=True)",
                normalizedImagePath,
                normalizedPdfPath,
                (float)PDF_EXTRACT_DPI
            )
        };

        log.debug("图片转单层PDF命令: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process = null;
        BufferedReader br = null;

        try {
            process = pb.start();

            br = new BufferedReader(
                new InputStreamReader(process.getInputStream(), "UTF-8")
            );

            String line;
            StringBuilder output = new StringBuilder();
            while ((line = br.readLine()) != null) {
                output.append(line).append("\n");
                log.debug("[IMG2PDF-SINGLE-LOG] {}", line);
            }

            int exitCode = waitForProcess(process, 60, "图片转单层PDF");

            if (exitCode != 0) {
                throw new IOException("图片转单层PDF失败，exitCode=" + exitCode +
                    ", output=" + output);
            }

            log.info("图片转单层PDF成功: {}", pdfPath);

        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    log.warn("关闭BufferedReader失败", e);
                }
            }
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * 将图片/PDF转换为双层PDF（主入口）
     *
     * @param inputPath 输入文件路径
     * @param outputPath 输出的双层PDF文件路径
     * @throws Exception 转换过程中可能抛出的异常
     */
    public static void convertToDualLayerPdf(String inputPath, String outputPath) throws Exception {
        log.info("开始双层PDF转换: {} -> {}", inputPath, outputPath);

        // 参数校验
        if (inputPath == null || inputPath.trim().isEmpty()) {
            throw new IllegalArgumentException("输入文件路径不能为空");
        }
        if (outputPath == null || outputPath.trim().isEmpty()) {
            throw new IllegalArgumentException("输出文件路径不能为空");
        }

        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            throw new FileNotFoundException("输入文件不存在: " + inputPath);
        }

        // 确保输出目录存在
        File outputFile = new File(outputPath);
        File outputDir = outputFile.getParentFile();
        if (outputDir != null && !outputDir.exists()) {
            outputDir.mkdirs();
        }

        // PDF 文件即使已有可搜索文本层，也必须重新 OCR。
        // 后续流程会先按页面渲染为图片，再写入新识别的文本层，避免保留旧文本层。

        try {
            // 方案选择：根据配置选择使用哪种OCR方案
            String ocrType = System.getProperty("ocr.type", OCR_TYPE_RAPID);  // 默认使用RapidOCR

            if (OCR_TYPE_RAPID.equals(ocrType) || "true".equals(System.getProperty("use.rapidocr"))) {
                // 【推荐】方案R：使用RapidOCR（高性能，完全离线）
                log.info("使用 RapidOCR 方案（推荐，高性能）");
                convertWithRapidOcr(inputPath, outputPath);
            } else if (OCR_TYPE_LOCAL_PADDLE.equals(ocrType)) {
                // 方案D：使用本地PaddleOCR 3.3
                log.info("使用本地PaddleOCR 3.3方案");
                LocalOcrPdfConverter.convertToDualLayerPdf(inputPath, outputPath);
            } else {
                // 方案A/B：使用本地OCR
                String usePaddleOcr = System.getProperty("use.paddleocr", "false");

                if ("true".equals(usePaddleOcr)) {
                    // 方案A：使用 PaddleOCR + OCRmyPDF
                    log.info("使用 PaddleOCR + OCRmyPDF 方案");
                    convertWithPaddleOcrAndOcrmypdf(inputPath, outputPath);
                } else {
                    // 方案B：直接使用 OCRmyPDF（默认，更简单）
                    log.info("使用 OCRmyPDF 直接方案");
                    convertWithOcrmypdf(inputPath, outputPath);
                }
            }

            // 验证输出文件
            if (!outputFile.exists() || outputFile.length() == 0) {
                throw new RuntimeException("转换失败，未生成有效输出文件");
            }

            log.info("双层PDF转换成功: {}", outputPath);

        } catch (Exception e) {
            log.error("双层PDF转换失败: {}", inputPath, e);
            throw e;
        }
    }

    /**
     * 方案A：PaddleOCR + OCRmyPDF 协同方案
     *
     * 步骤：
     * 1. 判断输入类型（图片或PDF）
     * 2. 如果是图片，先转换为临时PDF
     * 3. 将PDF转换为图片（用于背景层）
     * 4. 使用PaddleOCR识别每张图片
     * 5. 使用OCRmyPDF强制重新识别并生成双层PDF
     *
     * @param inputPath 输入文件路径
     * @param outputPath 输出文件路径
     * @throws Exception 转换失败时抛出异常
     */
    private static void convertWithPaddleOcrAndOcrmypdf(String inputPath, String outputPath) throws Exception {
        // 【修复】创建唯一的临时目录，避免并发冲突（使用UUID确保唯一性）
        String uniqueId = java.util.UUID.randomUUID().toString().replace("-", "");
        String tempDir = System.getProperty("java.io.tmpdir") + "/pdf_convert_" + uniqueId;
        new File(tempDir).mkdirs();

        // 统一处理：确保使用PDF进行后续流程
        String pdfPath = inputPath;  // 默认使用原始路径
        String tempPdfPath = null;   // 临时PDF路径（如果需要转换图片）
        boolean needCleanupTempPdf = false;

        try {
            // 步骤1: 判断输入类型并转换为PDF（如果需要）
            if (isImageFile(inputPath)) {
                log.info("检测到输入文件为图片，先转换为临时PDF");
                tempPdfPath = tempDir + "/temp_" + System.currentTimeMillis() + ".pdf";
                convertImageToPdf(inputPath, tempPdfPath);
                pdfPath = tempPdfPath;
                needCleanupTempPdf = true;
                log.info("图片已转换为临时PDF: {}", tempPdfPath);
            } else if (isPdfFile(inputPath)) {
                log.info("检测到输入文件为PDF，直接使用");
            } else {
                throw new IllegalArgumentException("不支持的文件类型: " + getFileExtension(inputPath));
            }

            // 步骤2: 使用OCRmyPDF强制重新识别，去除已有文本层并生成新的文本层
            log.info("使用OCRmyPDF强制重新识别并生成双层PDF");
            performOcrmypdfConversion(pdfPath, outputPath);

            log.info("OCRmyPDF 转换完成");

        } finally {
            // 清理临时PDF文件
            if (needCleanupTempPdf && tempPdfPath != null) {
                File tempFile = new File(tempPdfPath);
                if (tempFile.exists()) {
                    boolean deleted = tempFile.delete();
                    if (deleted) {
                        log.info("临时PDF文件已清理: {}", tempPdfPath);
                    } else {
                        log.warn("临时PDF文件清理失败: {}", tempPdfPath);
                    }
                }
            }

            // 【修复】清理整个临时目录，避免残留文件
            deleteDirectory(new File(tempDir));
        }
    }

    /**
     * 方案B：直接使用 OCRmyPDF（推荐方案）
     *
     * OCRmyPDF 内部架构：
     * - 使用 Tesseract 进行OCR识别
     * - 自动生成双层PDF
     * - 支持图像优化和旋转
     *
     * @param inputPath 输入文件路径
     * @param outputPath 输出文件路径
     * @throws Exception 转换失败时抛出异常
     */
    private static void convertWithOcrmypdf(String inputPath, String outputPath) throws Exception {
        log.info("使用 OCRmyPDF 直接生成双层PDF");

        // 直接调用 OCRmyPDF
        performOcrmypdfConversion(inputPath, outputPath);
    }

    /**
     * 方案R：使用 RapidOCR 生成双层PDF（推荐方案）
     *
     * RapidOCR 优势：
     * - 基于ONNX Runtime，启动速度快（<1秒）
     * - 内存占用低（约300MB/任务）
     * - 完全离线，无需联网
     * - 支持PP-OCRv4/v5模型
     *
     * 流程：
     * 1. PDF转为图片（如需要）- 使用无损PNG格式
     * 2. RapidOCR识别文字和坐标
     * 3. 使用HocrTextLayerAdder生成双层PDF
     *
     * @param inputPath 输入文件路径
     * @param outputPath 输出文件路径
     * @throws Exception 转换失败时抛出异常
     */
    private static void convertWithRapidOcr(String inputPath, String outputPath) throws Exception {
        log.info("使用 RapidOCR 生成双层PDF (DPI={})", PDF_EXTRACT_DPI);

        String pythonPath = getPythonPath();
        String rapidOcrScript = getRapidOcrScriptPath();

        if (rapidOcrScript == null || !new File(rapidOcrScript).exists()) {
            throw new FileNotFoundException("RapidOCR 脚本不存在: " + rapidOcrScript);
        }

        // 创建唯一的临时目录（使用UUID确保唯一性）
        String uniqueId = java.util.UUID.randomUUID().toString().replace("-", "");
        String tempDir = System.getProperty("java.io.tmpdir") + "/rapid_ocr_" + uniqueId;
        new File(tempDir).mkdirs();

        try {
            // 步骤1: 准备输入图片
            String imagePath;
            int imageWidth, imageHeight;
            float sourceDpi;
            String[] imagePaths = null;
            int[] imageWidths = null;
            int[] imageHeights = null;
            boolean pdfInput = false;

            if (isImageFile(inputPath)) {
                // 【无损】图片输入：直接使用原图
                imagePath = inputPath;
                log.info("图片输入（无损）: {}", imagePath);

                // 获取图片尺寸
                java.awt.image.BufferedImage image = null;
                try {
                    image = javax.imageio.ImageIO.read(new File(imagePath));
                } catch (Exception e) {
                    throw new RuntimeException("无法读取图片: " + imagePath + "。错误: " + e.getMessage() + "。详细原因: " + getDetailedImageErrorInfo(new File(imagePath)), e);
                }
                if (image == null) {
                    throw new RuntimeException("无法读取图片: " + imagePath + "。该图片格式不被支持，或已损坏。详细原因: " + getDetailedImageErrorInfo(new File(imagePath)));
                }
                try {
                    imageWidth = image.getWidth();
                    imageHeight = image.getHeight();
                } finally {
                    // 【内存优化】立即释放图片像素数据
                    if (image != null) {
                        image.flush();
                        image = null;
                    }
                }

                log.info("原始图片尺寸: {}x{} 像素", imageWidth, imageHeight);

                // 【可配置】检查是否强制指定图片DPI
                String forcedDpi = System.getProperty("image.force.dpi");
                if (forcedDpi != null && !forcedDpi.isEmpty()) {
                    try {
                        sourceDpi = Float.parseFloat(forcedDpi);
                        log.info("使用强制指定的图片DPI: {} (通过 -Dimage.force.dpi={})", sourceDpi, forcedDpi);
                    } catch (NumberFormatException e) {
                        log.warn("无效的强制DPI值: {}，将自动检测", forcedDpi);
                        sourceDpi = detectImageDpi(inputPath, imageWidth, imageHeight);
                    }
                } else {
                    sourceDpi = detectImageDpi(inputPath, imageWidth, imageHeight);
                }
            } else if (isPdfFile(inputPath)) {
                pdfInput = true;
                // PDF需要先转换为图片 - 使用自适应DPI和无损PNG（质量与性能平衡）
                log.info("PDF输入：转换为无损PNG图片 (自适应DPI，默认300 DPI - 质量与性能平衡)");
                PdfImageExtractionResult extractionResult = convertPdfToImagesWithDpi(inputPath, tempDir);
                if (extractionResult.images.length == 0) {
                    throw new RuntimeException("PDF转图片失败");
                }
                imagePaths = extractionResult.images;
                imagePath = extractionResult.images[0];  // 处理第一页
                sourceDpi = extractionResult.actualDpi;

                imageWidths = new int[imagePaths.length];
                imageHeights = new int[imagePaths.length];
                for (int pageIndex = 0; pageIndex < imagePaths.length; pageIndex++) {
                    java.awt.image.BufferedImage image = null;
                    try {
                        image = javax.imageio.ImageIO.read(new File(imagePaths[pageIndex]));
                    } catch (Exception e) {
                        throw new RuntimeException("无法读取转换后的图片: " + imagePaths[pageIndex] + "。错误: " + e.getMessage() + "。详细原因: " + getDetailedImageErrorInfo(new File(imagePaths[pageIndex])), e);
                    }
                    if (image == null) {
                        throw new RuntimeException("无法读取转换后的图片: " + imagePaths[pageIndex] + "。该图片格式不被支持，或已损坏。详细原因: " + getDetailedImageErrorInfo(new File(imagePaths[pageIndex])));
                    }
                    try {
                        imageWidths[pageIndex] = image.getWidth();
                        imageHeights[pageIndex] = image.getHeight();
                    } finally {
                        // 【内存优化】立即释放图片像素数据
                        if (image != null) {
                            image.flush();
                        }
                    }
                }
                imageWidth = imageWidths[0];
                imageHeight = imageHeights[0];

                log.info("PDF转换后共 {} 页，第一页尺寸: {}x{} 像素 (实际DPI={})",
                        imagePaths.length, imageWidth, imageHeight, sourceDpi);
            } else {
                throw new IllegalArgumentException("不支持的文件类型: " + getFileExtension(inputPath));
            }

            // 步骤2: 使用RapidOCR识别
            String ocrResult;
            if (pdfInput) {
                log.info("使用RapidOCR批量识别 PDF 提取图片，共 {} 页", imagePaths.length);
                ocrResult = performRapidOcrRecognition(imagePaths);
            } else {
                log.info("使用RapidOCR识别: {}", imagePath);
                ocrResult = performRapidOcrRecognition(imagePath);
            }

            // 步骤3: 使用HocrTextLayerAdder生成双层PDF（使用实际的图片DPI）
            if (sourceDpi < 72 || sourceDpi > 1200) {
                log.warn("DPI {} 不合理，使用推断值", sourceDpi);
                sourceDpi = inferDpiFromImageSize(imageWidth, imageHeight);
            }
            log.info("用于文字层映射的最终DPI: {}", sourceDpi);
            log.info("生成双层PDF: 图片尺寸={}x{}, 源DPI={}", imageWidth, imageHeight, sourceDpi);
            if (pdfInput) {
                generateDualLayerPdfWithRapidOcrResult(imagePaths, outputPath, ocrResult, imageWidths, imageHeights, sourceDpi);
            } else {
                generateDualLayerPdfWithRapidOcrResult(imagePath, outputPath, ocrResult, imageWidth, imageHeight, sourceDpi);
            }

            log.info("RapidOCR 转换完成: {}", outputPath);

        } finally {
            // 清理临时目录
            deleteDirectory(new File(tempDir));
        }
    }

    /**
     * 使用 RapidOCR 结果生成双层PDF
     *
     * @param imagePath 源图片路径
     * @param outputPath 输出PDF路径
     * @param ocrResult RapidOCR返回的JSON结果
     * @param width 图片宽度
     * @param height 图片高度
     * @throws Exception 生成失败时抛出异常
     */
    private static void generateDualLayerPdfWithRapidOcrResult(String imagePath, String outputPath,
                                                                String ocrResult, int width, int height) throws Exception {
        // 使用配置的 DPI (默认 300)
        generateDualLayerPdfWithRapidOcrResult(imagePath, outputPath, ocrResult, width, height, (float)PDF_EXTRACT_DPI);
    }

    /**
     * 使用 RapidOCR 结果生成双层PDF（指定源DPI）
     *
     * @param imagePath 源图片路径
     * @param outputPath 输出PDF路径
     * @param ocrResult RapidOCR返回的JSON结果
     * @param width 图片宽度
     * @param height 图片高度
     * @param sourceDpi 源图片DPI（影响PDF页面尺寸计算）
     * @throws Exception 生成失败时抛出异常
     */
    private static void generateDualLayerPdfWithRapidOcrResult(String imagePath, String outputPath,
                                                                String ocrResult, int width, int height,
                                                                float sourceDpi) throws Exception {
        log.info("使用RapidOCR结果生成双层PDF: {} -> {}, 源DPI={}", imagePath, outputPath, sourceDpi);

        // 确保输出目录存在
        File outputFile = new File(outputPath);
        File outputDir = outputFile.getParentFile();
        if (outputDir != null && !outputDir.exists()) {
            outputDir.mkdirs();
        }

        // 使用反射调用 HocrTextLayerAdder
        try {
            java.lang.reflect.Method method = getHocrTextLayerAdderMethod();

            // 【无损】使用实际的源DPI，确保PDF页面尺寸正确
            method.invoke(null, imagePath, ocrResult, width, height, outputPath, sourceDpi);

            log.info("双层PDF生成成功: {}", outputPath);

        } catch (ClassNotFoundException e) {
            log.error("找不到 HocrTextLayerAdder 类", e);
            throw new RuntimeException("双层PDF生成失败：缺少依赖类", e);
        } catch (NoSuchMethodException e) {
            log.error("HocrTextLayerAdder 方法不存在", e);
            throw new RuntimeException("双层PDF生成失败：方法签名不匹配", e);
        } catch (IllegalAccessException e) {
            log.error("无法访问方法", e);
            throw new RuntimeException("双层PDF生成失败：权限不足", e);
        } catch (java.lang.reflect.InvocationTargetException e) {
            log.error("调用方法失败", e);
            throw new RuntimeException("双层PDF生成失败：" + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()), e);
        }
    }

    private static void generateDualLayerPdfWithRapidOcrResult(String[] imagePaths, String outputPath,
                                                               String ocrResult, int[] widths, int[] heights,
                                                               float sourceDpi) throws Exception {
        log.info("使用RapidOCR结果生成多页双层PDF: 页数={}, 源DPI={}", imagePaths.length, sourceDpi);

        File outputFile = new File(outputPath);
        File outputDir = outputFile.getParentFile();
        if (outputDir != null && !outputDir.exists()) {
            outputDir.mkdirs();
        }

        try {
            java.lang.reflect.Method method = getHocrTextLayerAdderBatchMethod();
            method.invoke(null, imagePaths, ocrResult, widths, heights, outputPath, sourceDpi);
            log.info("多页双层PDF生成成功: {}", outputPath);
        } catch (ClassNotFoundException e) {
            log.error("找不到 HocrTextLayerAdder 类", e);
            throw new RuntimeException("双层PDF生成失败：缺少依赖类", e);
        } catch (NoSuchMethodException e) {
            log.error("HocrTextLayerAdder 批量方法不存在", e);
            throw new RuntimeException("双层PDF生成失败：方法签名不匹配", e);
        } catch (IllegalAccessException e) {
            log.error("无法访问批量方法", e);
            throw new RuntimeException("双层PDF生成失败：权限不足", e);
        } catch (java.lang.reflect.InvocationTargetException e) {
            log.error("调用批量方法失败", e);
            throw new RuntimeException("双层PDF生成失败：" + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()), e);
        }
    }

    /**
     * 获取 HocrTextLayerAdder.createDualLayerPdfFromOcr 方法（带缓存）
     */
    private static volatile java.lang.reflect.Method hocrTextLayerAdderMethod;
    private static volatile java.lang.reflect.Method hocrTextLayerAdderBatchMethod;

    private static java.lang.reflect.Method getHocrTextLayerAdderMethod() throws ClassNotFoundException, NoSuchMethodException {
        if (hocrTextLayerAdderMethod == null) {
            synchronized (DualLayerPdfConverter.class) {
                if (hocrTextLayerAdderMethod == null) {
                    Class<?> clazz = Class.forName("com.pdfutil.pdf.utils.HocrTextLayerAdder");
                    hocrTextLayerAdderMethod = clazz.getMethod("createDualLayerPdfFromOcr",
                        String.class, String.class, int.class, int.class, String.class, float.class);
                }
            }
        }
        return hocrTextLayerAdderMethod;
    }

    private static java.lang.reflect.Method getHocrTextLayerAdderBatchMethod() throws ClassNotFoundException, NoSuchMethodException {
        if (hocrTextLayerAdderBatchMethod == null) {
            synchronized (DualLayerPdfConverter.class) {
                if (hocrTextLayerAdderBatchMethod == null) {
                    Class<?> clazz = Class.forName("com.pdfutil.pdf.utils.HocrTextLayerAdder");
                    hocrTextLayerAdderBatchMethod = clazz.getMethod("createDualLayerPdfFromOcr",
                        String[].class, String.class, int[].class, int[].class, String.class, float.class);
                }
            }
        }
        return hocrTextLayerAdderBatchMethod;
    }

    /**
     * 执行 RapidOCR 文本识别
     *
     * @param inputPath 输入图片路径
     * @return 识别结果（JSON格式）
     * @throws Exception 识别失败时抛出异常
     */
    private static String performRapidOcrRecognition(String inputPath) throws Exception {
        return performRapidOcrRecognition(new String[]{inputPath});
    }

    private static String performRapidOcrRecognition(String[] inputPaths) throws Exception {
        String pythonPath = getPythonPath();
        String rapidOcrScript = getRapidOcrScriptPath();

        // 构建命令
        List<String> commandList = new ArrayList<>();
        commandList.add(pythonPath);
        commandList.add(rapidOcrScript);
        for (String inputPath : inputPaths) {
            commandList.add(inputPath);
        }
        String[] command = commandList.toArray(new String[0]);

        log.info("RapidOCR 命令: {} (共 {} 张图片)", String.join(" ", command), inputPaths.length);

        // 【修复】合并错误流，避免 stderr 缓冲区填满导致进程阻塞
        // RapidOCR 的进度信息输出到 stderr，必须读取否则会阻塞
        // 【超时优化】批量OCR识别需要更长时间（105张图片可能需要10-20分钟）
        int timeoutSeconds = Math.max(300, inputPaths.length * 10);  // 每张图片最多10秒
        String output = execCommandWithOutput(command, true, timeoutSeconds);

        // 验证输出是有效的JSON
        if (output == null || output.trim().isEmpty()) {
            throw new RuntimeException("RapidOCR 返回空结果");
        }

        // 【修复】从混合输出中提取JSON（RapidOCR的进度信息在stderr，JSON结果在stdout最后一行）
        String jsonOutput = output.trim();
        if (jsonOutput.contains("\n")) {
            // JSON 是最后一行（进度信息在前面的行中）
            String[] lines = jsonOutput.split("\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                String line = lines[i].trim();
                if (line.startsWith("{")) {
                    jsonOutput = line;
                    break;
                }
            }
        }

        // 验证是有效JSON
        if (!jsonOutput.startsWith("{")) {
            throw new RuntimeException("RapidOCR 返回非JSON格式: " + jsonOutput.substring(0, Math.min(100, jsonOutput.length())));
        }

        log.info("RapidOCR 识别成功");
        return jsonOutput;
    }

    /**
     * 获取 RapidOCR 脚本路径
     */
    private static String getRapidOcrScriptPath() {
        // 从系统属性获取
        String scriptPath = System.getProperty("rapidocr.script.path");
        if (scriptPath != null && !scriptPath.isEmpty()) {
            return scriptPath;
        }

        // 默认路径
        String defaultPath = System.getProperty("user.dir") + "/scripts/rapidocr_wrapper.py";
        if (new File(defaultPath).exists()) {
            return defaultPath;
        }

        // 尝试其他可能的路径
        String[] possiblePaths = {
            "scripts/rapidocr_wrapper.py",
            "../scripts/rapidocr_wrapper.py",
            "./rapidocr_wrapper.py"
        };

        for (String path : possiblePaths) {
            if (new File(path).exists()) {
                return path;
            }
        }

        return null;
    }

    /**
     * 执行 PaddleOCR 文本识别
     *
     * @param inputPath 输入文件路径
     * @return 识别结果（JSON格式）
     * @throws Exception 识别失败时抛出异常
     */
    private static String performPaddleOcrRecognition(String inputPath) throws Exception {
        String pythonPath = getPythonPath();
        String paddleOcrScript = getPaddleOcrScriptPath();

        if (paddleOcrScript == null || !new File(paddleOcrScript).exists()) {
            log.warn("PaddleOCR 脚本不存在，跳过 PaddleOCR 预处理");
            return "{\"message\": \"PaddleOCR not configured\"}";
        }

        // 【修复】创建唯一的临时输出目录，避免并发冲突（使用UUID确保唯一性）
        String uniqueId = java.util.UUID.randomUUID().toString().replace("-", "");
        String tempDir = System.getProperty("java.io.tmpdir") + "/paddle_ocr_" + uniqueId;
        new File(tempDir).mkdirs();

        // 构建命令（使用简单格式）
        String[] command = {
            pythonPath,
            paddleOcrScript,
            inputPath,
            tempDir
        };

        log.debug("PaddleOCR 命令: {}", String.join(" ", command));

        // 执行命令并获取输出
        String output = execCommandWithOutput(command);

        // 脚本会输出生成的JSON文件路径
        if (output != null && !output.trim().isEmpty()) {
            String resultFile = output.trim();
            File file = new File(resultFile);
            if (file.exists()) {
                // 读取JSON文件内容
                String jsonContent = new String(Files.readAllBytes(file.toPath()));
                log.info("PaddleOCR 识别成功，结果文件: {}", resultFile);
                return jsonContent;
            }
        }

        log.warn("PaddleOCR 未生成结果文件");
        return "{\"message\": \"No output\"}";
    }

    /**
     * 执行 OCRmyPDF 转换
     *
     * 【优化】使用自适应DPI设置以平衡图片质量与处理性能
     *
     * @param inputPath 输入文件路径
     * @param outputPath 输出文件路径
     * @throws Exception 转换失败时抛出异常
     */
    private static void performOcrmypdfConversion(String inputPath, String outputPath) throws Exception {
        String ocrmypdfPath = getOcrmypdfPath();
        String lang = getLanguage();

        // 【安全修复】使用安全的命令构建器
        List<String> cmdList = CommandBuilder.buildCommand(ocrmypdfPath);

        if (cmdList.isEmpty()) {
            throw new IllegalArgumentException("OCRmyPDF 命令配置为空或不安全");
        }

        // 添加 OCRmyPDF 参数
        cmdList.add("--pdf-renderer");
        cmdList.add("sandwich");  // 双层PDF模式
        cmdList.add("--language");
        cmdList.add(lang);
        // 【版式对齐修复】禁用自动旋转，保持原始版式（竖版/横版）
        // 竖版原图保持竖版，横版原图保持横版，不强制统一
        cmdList.add("--deskew");  // 自动校正倾斜（保持启用，用于修正拍摄角度）

        // 【质量与性能平衡】计算图片DPI
        int imageDpi = PDF_EXTRACT_DPI;  // 默认使用配置的DPI
        if (ADAPTIVE_DPI_ENABLED && isPdfFile(inputPath)) {
            try {
                int originalDpi = detectPdfOriginalDpi(inputPath);
                if (originalDpi > 0) {
                    imageDpi = originalDpi >= 300 ? originalDpi : 300;
                    log.info("【自适应DPI】OCRmyPDF使用DPI={} (原始DPI={})", imageDpi, originalDpi);
                } else {
                    log.info("【自适应DPI】OCRmyPDF使用默认DPI={} (无法检测原始DPI)", imageDpi);
                }
            } catch (Exception e) {
                log.debug("【自适应DPI】检测失败，使用默认DPI: {}, 错误: {}", imageDpi, e.getMessage());
            }
        }

        // 【质量与性能平衡】使用自适应计算的DPI（默认300），保持良好的图片质量
        cmdList.add("--image-dpi");
        cmdList.add(String.valueOf(imageDpi));
        cmdList.add("-O");
        cmdList.add("0");  // 禁用优化（保持原图质量）
        cmdList.add("--output-type");
        cmdList.add("pdf");
        cmdList.add("--continue-on-soft-render-error");
        cmdList.add("--force-ocr");  // 强制进行OCR（用于已有文本层的PDF）

        // 【安全修复】验证文件路径安全性
        if (!CommandBuilder.isSafeFilePath(inputPath)) {
            throw new IllegalArgumentException("输入文件路径不安全: " + inputPath);
        }
        if (!CommandBuilder.isSafeFilePath(outputPath)) {
            throw new IllegalArgumentException("输出文件路径不安全: " + outputPath);
        }

        cmdList.add(inputPath);
        cmdList.add(outputPath);

        String[] command = cmdList.toArray(new String[0]);

        log.debug("OCRmyPDF 命令: {}", String.join(" ", command));

        // 执行命令
        execCommand(command);
    }

    /**
     * 执行命令（数组形式）
     */
    private static void execCommand(String[] command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        // 设置环境变量（禁用PaddleOCR模型源检查）
        Map<String, String> env = pb.environment();
        env.put("DISABLE_MODEL_SOURCE_CHECK", "True");

        Process process = null;
        BufferedReader br = null;

        try {
            process = pb.start();

            // 读取输出
            br = new BufferedReader(
                new InputStreamReader(process.getInputStream(), "UTF-8")
            );

            String line;
            StringBuilder output = new StringBuilder();
            while ((line = br.readLine()) != null) {
                output.append(line).append("\n");
                log.debug("[OCR-LOG] {}", line);
            }

            int exitCode = waitForProcess(process, 300, "执行OCR命令");

            if (exitCode != 0) {
                throw new IOException("命令执行失败，exitCode=" + exitCode +
                    ", command=" + String.join(" ", command) +
                    ", output=" + output);
            }
        } finally {
            // 确保资源被正确关闭
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    log.warn("关闭BufferedReader失败", e);
                }
            }
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * 执行命令并返回标准输出
     */
    private static String execCommandWithOutput(String[] command) throws IOException, InterruptedException {
        return execCommandWithOutput(command, true, 300);
    }

    /**
     * 执行命令并返回输出
     *
     * @param command 命令数组
     * @param mergeErrorStream 是否合并错误流到输出
     * @return 命令输出
     */
    private static String execCommandWithOutput(String[] command, boolean mergeErrorStream) throws IOException, InterruptedException {
        return execCommandWithOutput(command, mergeErrorStream, 300);
    }

    /**
     * 执行命令并返回输出（带自定义超时）
     *
     * @param command 命令数组
     * @param mergeErrorStream 是否合并错误流到输出
     * @param timeoutSeconds 超时时间（秒）
     * @return 命令输出
     */
    private static String execCommandWithOutput(String[] command, boolean mergeErrorStream, int timeoutSeconds) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(mergeErrorStream);

        // 设置环境变量（禁用PaddleOCR模型源检查）
        Map<String, String> env = pb.environment();
        env.put("DISABLE_MODEL_SOURCE_CHECK", "True");

        Process process = null;
        BufferedReader stdoutReader = null;
        BufferedReader stderrReader = null;

        try {
            process = pb.start();

            // 分别读取stdout和stderr
            stdoutReader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), "UTF-8")
            );

            if (!mergeErrorStream) {
                stderrReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), "UTF-8")
                );
            }

            String line;
            StringBuilder output = new StringBuilder();
            StringBuilder errorOutput = new StringBuilder();

            // 读取stdout
            while ((line = stdoutReader.readLine()) != null) {
                output.append(line).append("\n");
                log.debug("[OCR-STDOUT] {}", line);
            }

            // 读取stderr（如果不合并）
            if (stderrReader != null) {
                while ((line = stderrReader.readLine()) != null) {
                    errorOutput.append(line).append("\n");
                    log.debug("[OCR-STDERR] {}", line);
                }
            }

            int exitCode = waitForProcess(process, timeoutSeconds, "执行命令并返回输出");

            if (exitCode != 0) {
                throw new IOException("命令执行失败，exitCode=" + exitCode +
                    ", command=" + String.join(" ", command) +
                    ", output=" + output);
            }

            return output.toString();
        } finally {
            // 确保资源被正确关闭
            if (stdoutReader != null) {
                try {
                    stdoutReader.close();
                } catch (IOException e) {
                    log.warn("关闭stdoutReader失败", e);
                }
            }
            if (stderrReader != null) {
                try {
                    stderrReader.close();
                } catch (IOException e) {
                    log.warn("关闭stderrReader失败", e);
                }
            }
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * 获取 Python 路径
     */
    private static String getPythonPath() {
        String path = System.getProperty("python.path");
        return path != null && !path.isEmpty() ? path : (isWindows() ? "python" : "python3");
    }

    /**
     * 获取 PaddleOCR 脚本路径
     */
    private static String getPaddleOcrScriptPath() {
        return System.getProperty("paddleocr.script.path");
    }

    /**
     * 获取 OCRmyPDF 路径
     */
    private static String getOcrmypdfPath() {
        String path = System.getProperty("ocrmypdf.path");
        return path != null && !path.isEmpty() ? path : "ocrmypdf";
    }

    /**
     * 将PDF转换为图片
     *
     * 【重要】DPI一致性说明：
     * - 此方法用于方案A（PaddleOCR + OCRmyPDF）
     * - 提取图片的DPI必须与后续双层PDF生成的DPI一致
     * - 【质量与性能平衡】检测PDF原始DPI，智能选择最佳DPI：
     *   - 原始DPI >= 300：默认限制到300 DPI（可通过 pdf.extract.maxDpi 调整）
     *   - 原始DPI < 300：使用配置DPI补足清晰度（质量与性能平衡）
     *   - 无法检测：使用300 DPI（确保速度和质量平衡）
     * - 使用PNG格式保持无损质量
     *
     * @param pdfPath PDF文件路径
     * @param outputDir 输出目录
     * @return 图片文件路径数组
     * @throws Exception 转换失败时抛出异常
     */
    private static String[] convertPdfToImages(String pdfPath, String outputDir) throws Exception {
        return convertPdfToImagesWithDpi(pdfPath, outputDir).images;
    }

    private static PdfImageExtractionResult convertPdfToImagesWithDpi(String pdfPath, String outputDir) throws Exception {
        String pythonPath = getPythonPath();

        // 检查是否安装了pdf2image（使用缓存，仅首次检测）
        Boolean available = pdf2imageAvailable;
        if (available == null) {
            synchronized (DualLayerPdfConverter.class) {
                available = pdf2imageAvailable;
                if (available == null) {
                    String[] checkCmd = {pythonPath, "-c", "import pdf2image; print('OK')"};
                    ProcessBuilder checkPb = new ProcessBuilder(checkCmd);
                    checkPb.redirectErrorStream(true);

                    Process checkProcess = null;
                    try {
                        checkProcess = checkPb.start();
                        int exitCode = waitForProcess(checkProcess, 10, "检查pdf2image安装");
                        String output = readStream(checkProcess.getInputStream());

                        available = (exitCode == 0 && output.contains("OK"));
                        pdf2imageAvailable = available;

                        if (available) {
                            log.info("pdf2image 可用性检查: OK（已缓存）");
                        } else {
                            log.warn("pdf2image 检查失败，exitCode={}, output={}", exitCode, output);
                            throw new IOException("pdf2image 未安装。请运行: pip install pdf2image");
                        }
                    } catch (Exception e) {
                        log.error("检查 pdf2image 失败", e);
                        throw new IOException("检查 pdf2image 失败: " + e.getMessage());
                    } finally {
                        if (checkProcess != null && checkProcess.isAlive()) {
                            checkProcess.destroyForcibly();
                        }
                    }
                }
            }
        }
        // 使用缓存结果
        if (!available) {
            throw new IOException("pdf2image 未安装（缓存状态）。请运行: pip install pdf2image");
        }

        // 【质量与性能平衡】智能选择DPI
        int actualDpi = PDF_EXTRACT_DPI;  // 默认使用配置的DPI
        if (ADAPTIVE_DPI_ENABLED) {
            try {
                int originalDpi = detectPdfOriginalDpi(pdfPath);
                if (originalDpi > 0) {
                    if (originalDpi >= 300) {
                        actualDpi = Math.min(originalDpi, PDF_EXTRACT_MAX_DPI);
                        if (actualDpi < originalDpi) {
                            log.info("【自适应DPI】PDF原始DPI={}，按上限限制为{} DPI（降低二次OCR渲染成本）",
                                    originalDpi, actualDpi);
                        } else {
                            log.info("【自适应DPI】PDF原始DPI={} (>=300)，保持原始质量", originalDpi);
                        }
                    } else {
                        actualDpi = Math.min(Math.max(originalDpi, PDF_EXTRACT_DPI), PDF_EXTRACT_MAX_DPI);
                        log.info("【自适应DPI】PDF原始DPI={} (<300)，使用{} DPI (质量与性能平衡)",
                                originalDpi, actualDpi);
                    }
                } else {
                    log.info("【自适应DPI】无法检测PDF原始DPI，使用{} DPI (质量与性能平衡)", PDF_EXTRACT_DPI);
                }
            } catch (Exception e) {
                log.warn("【自适应DPI】检测失败，使用默认DPI: {}, 错误: {}", PDF_EXTRACT_DPI, e.getMessage());
            }
        }

        // 路径处理：统一使用正斜杠（跨平台兼容）
        String normalizedPdfPath = pdfPath.replace("\\", "/");
        String normalizedOutputDir = outputDir.replace("\\", "/");

        // 【质量与性能平衡】使用智能选择的DPI（默认300），使用PNG格式保持无损质量。
        // paths_only + output_folder 让底层Poppler直接落盘，避免Python端持有所有PIL图片并再次保存。
        String[] command = {
            pythonPath, "-c",
            String.format(
                "from pdf2image import convert_from_path\n" +
                "import os\n" +
                "os.makedirs(r'%s', exist_ok=True)\n" +
                "paths = convert_from_path(r'%s', dpi=%d, fmt='png', use_cropbox=True, output_folder=r'%s', output_file='page', paths_only=True, thread_count=%d)\n" +
                "for img_path in paths:\n" +
                "    print(os.path.abspath(img_path))",
                normalizedOutputDir,
                normalizedPdfPath,
                actualDpi,  // 使用自适应计算的DPI
                normalizedOutputDir,
                PDF_EXTRACT_THREAD_COUNT
            )
        };

        log.info("【质量与性能平衡】PDF转图片: DPI={} ({}), maxDpi={}, threadCount={}, 格式=PNG (无损), 路径: {}",
                actualDpi, ADAPTIVE_DPI_ENABLED ? "自适应" : "固定",
                PDF_EXTRACT_MAX_DPI, PDF_EXTRACT_THREAD_COUNT, pdfPath);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process = pb.start();

        // 读取输出的图片路径
        java.io.BufferedReader reader = new java.io.BufferedReader(
            new java.io.InputStreamReader(process.getInputStream(), "UTF-8")
        );

        List<String> imageList = new ArrayList<>();
        StringBuilder processOutput = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                processOutput.append(trimmed).append('\n');
                File generatedFile = new File(trimmed);
                if (generatedFile.exists() && isImageFile(trimmed)) {
                    imageList.add(generatedFile.getAbsolutePath());
                    log.debug("生成图片: {}", generatedFile.getAbsolutePath());
                } else {
                    log.debug("[PDF2IMAGE-LOG] {}", trimmed);
                }
            }
        }

        int exitCode = waitForProcess(process, 120, "PDF转图片");

        if (exitCode != 0) {
            throw new IOException("PDF转图片失败: " + processOutput);
        }

        if (imageList.isEmpty()) {
            throw new IOException("PDF转图片失败：未生成任何图片，输出=" + processOutput);
        }

        imageList.sort((a, b) -> {
            int pageA = extractPageNumber(a);
            int pageB = extractPageNumber(b);
            if (pageA != pageB) {
                return Integer.compare(pageA, pageB);
            }
            return a.compareTo(b);
        });

        log.info("PDF转图片完成: 共 {} 页, DPI={}", imageList.size(), actualDpi);
        return new PdfImageExtractionResult(imageList.toArray(new String[0]), actualDpi);
    }

    private static int extractPageNumber(String path) {
        String name = new File(path).getName();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)(?=\\.[^.]+$)").matcher(name);
        int page = -1;
        while (matcher.find()) {
            try {
                page = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                page = -1;
            }
        }
        return page;
    }

    private static class PdfImageExtractionResult {
        private final String[] images;
        private final int actualDpi;

        private PdfImageExtractionResult(String[] images, int actualDpi) {
            this.images = images;
            this.actualDpi = actualDpi;
        }
    }

    /**
     * 检测PDF的原始DPI（自适应DPI）
     *
     * 使用PyMuPDF（fitz）库检测PDF第一页的DPI信息
     * 如果PDF包含高分辨率图片，会根据图片尺寸推断DPI
     *
     * @param pdfPath PDF文件路径
     * @return 检测到的DPI，如果无法检测则返回0
     */
    private static int detectPdfOriginalDpi(String pdfPath) {
        String pythonPath = getPythonPath();
        String normalizedPath = pdfPath.replace("\\", "/");

        try {
            // 使用PyMuPDF检测PDF的原始DPI
            String pythonScript =
                "import sys\n" +
                "try:\n" +
                "    import fitz  # PyMuPDF\n" +
                "    doc = fitz.open('" + normalizedPath + "')\n" +
                "    if len(doc) == 0:\n" +
                "        print(0)\n" +
                "        sys.exit(0)\n" +
                "    page = doc[0]\n" +
                "    # 获取页面尺寸（点）\n" +
                "    rect = page.rect\n" +
                "    width_pt = rect.width\n" +
                "    height_pt = rect.height\n" +
                "    # 获取页面中的图片\n" +
                "    image_list = page.get_images()\n" +
                "    if image_list:\n" +
                "        # 检查第一张图片的尺寸\n" +
                "        xref = image_list[0][0]\n" +
                "        base_image = doc.extract_image(xref)\n" +
                "        img_width = base_image.get('width', 0)\n" +
                "        img_height = base_image.get('height', 0)\n" +
                "        if img_width > 0 and width_pt > 0:\n" +
                "            # 推断DPI: 图片像素宽度 / 页面点宽度 * 72\n" +
                "            dpi = int(img_width / width_pt * 72)\n" +
                "            print(dpi)\n" +
                "        else:\n" +
                "            print(0)\n" +
                "    else:\n" +
                "        # 没有图片，可能是纯文本PDF，返回0表示无法检测\n" +
                "        print(0)\n" +
                "    doc.close()\n" +
                "except ImportError:\n" +
                "    # PyMuPDF未安装，返回0\n" +
                "    print(0)\n" +
                "except Exception as e:\n" +
                "    # 检测失败，返回0\n" +
                "    print(0)\n";

            String[] command = {pythonPath, "-c", pythonScript};

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = waitForProcess(process, 10, "检测PDF原始DPI");

            if (exitCode == 0) {
                String result = output.toString().trim();
                if (!result.isEmpty() && !result.equals("0")) {
                    try {
                        int detectedDpi = Integer.parseInt(result);
                        if (detectedDpi >= 72 && detectedDpi <= 1200) {
                            log.info("检测到PDF原始DPI: {}", detectedDpi);
                            return detectedDpi;
                        }
                    } catch (NumberFormatException e) {
                        log.debug("解析DPI失败: '{}'", result);
                    }
                }
            } else {
                log.debug("PDF DPI检测失败，exitCode={}", exitCode);
            }

        } catch (Exception e) {
            log.debug("检测PDF原始DPI异常: {}", e.getMessage());
        }

        return 0;  // 无法检测
    }

    /**
     * 读取输入流内容
     */
    private static String readStream(java.io.InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }

        StringBuilder response = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }
        return response.toString();
    }

    /**
     * 获取语言配置
     */
    private static String getLanguage() {
        String lang = System.getProperty("ocr.language");
        return lang != null && !lang.isEmpty() ? lang : "chi_sim+chi_tra+eng";
    }

    /**
     * 带重试机制的文件复制
     *
     * @param sourceFile 源文件
     * @param destFile 目标文件
     * @throws Exception 复制失败时抛出异常
     */
    private static void copyFileWithRetry(File sourceFile, File destFile) throws Exception {
        int maxRetries = 3;
        int retryDelayMs = 1000;

        for (int i = 0; i < maxRetries; i++) {
            try {
                // 确保源文件可读
                if (!sourceFile.canRead()) {
                    Thread.sleep(500);
                }

                Files.copy(sourceFile.toPath(), destFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log.debug("文件复制成功: {} -> {}", sourceFile.getPath(), destFile.getPath());
                return;
            } catch (Exception e) {
                if (i == maxRetries - 1) {
                    throw e;
                }
                log.warn("文件复制失败，{}/{} 秒后重试: {}", (i + 1), maxRetries, e.getMessage());
                Thread.sleep(retryDelayMs);
            }
        }
    }

    /**
     * 判断是否为 Windows
     */
    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }

    /**
     * 获取文件扩展名
     */
    private static String getFileExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "";
        }
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(lastDotIndex + 1).toLowerCase();
    }

    /**
     * 判断文件是否为图片
     */
    private static boolean isImageFile(String filePath) {
        String extension = getFileExtension(filePath);
        return extension.equals("jpg") || extension.equals("jpeg") ||
               extension.equals("png") || extension.equals("tif") ||
               extension.equals("tiff") || extension.equals("bmp") ||
               extension.equals("gif");
    }

    /**
     * 判断文件是否为PDF
     */
    private static boolean isPdfFile(String filePath) {
        return getFileExtension(filePath).equals("pdf");
    }

    /**
     * 【新增】自动检测图片的 DPI
     *
     * 使用 Python PIL 库检测 DPI（更准确可靠）
     * 支持的格式：JPEG, PNG, TIFF 等
     *
     * @param imagePath 图片文件路径
     * @param imageWidth 图片宽度（像素），用于无法检测时的推断
     * @param imageHeight 图片高度（像素），用于无法检测时的推断
     * @return 检测到的 DPI，如果无法检测则根据尺寸推断
     */
    public static float detectImageDpi(String imagePath, int imageWidth, int imageHeight) {
        try {
            File file = new File(imagePath);
            if (!file.exists()) {
                log.warn("图片文件不存在，无法检测DPI: {}", imagePath);
                return 300.0f;
            }

            // 使用 Python PIL 检测 DPI（更准确）
            String pythonPath = getPythonPath();
            String normalizedPath = imagePath.replace("\\", "/");

            // 修正 Python 命令 - 使用更可靠的字符串拼接方式
            String pythonScript =
                "from PIL import Image; " +
                "img = Image.open('" + normalizedPath + "'); " +
                "dpi = img.info.get('dpi', (0, 0)); " +
                "print(dpi[0] if dpi[0] else 0)";

            String[] command = {pythonPath, "-c", pythonScript};

            log.debug("检测图片DPI命令: {}", String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            StringBuilder errorOutput = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    log.debug("PIL DPI检测输出: {}", line);
                }
            }

            int exitCode = waitForProcess(process, 10, "检测图片DPI");

            if (exitCode == 0) {
                String result = output.toString().trim();
                if (!result.isEmpty() && !result.equals("0")) {
                    try {
                        float dpi = Float.parseFloat(result);
                        if (dpi >= 72 && dpi <= 2400) {
                            log.info("检测到图片DPI: {} ({})", dpi, imagePath);
                            return dpi;
                        } else if (dpi > 0) {
                            log.warn("检测到异常的DPI值: {}，使用默认值", dpi);
                        }
                    } catch (NumberFormatException e) {
                        log.debug("解析DPI失败: '{}'", result);
                    }
                } else {
                    log.debug("图片没有DPI元数据: {}", imagePath);
                }
            } else {
                log.warn("PIL DPI检测失败，exitCode={}", exitCode);
            }

        } catch (Exception e) {
            log.warn("检测图片DPI失败: {}, 错误: {}", imagePath, e.getMessage());
            log.debug("DPI检测异常详情", e);
        }

        // 无法检测，根据图片尺寸推断 DPI
        float inferredDpi = inferDpiFromImageSize(imageWidth, imageHeight);
        log.info("无法从元数据检测DPI，根据尺寸{}x{}推断: {} DPI",
                imageWidth, imageHeight, inferredDpi);
        return inferredDpi;
    }

    /**
     * 【新增】根据图片像素尺寸推断 DPI
     *
     * 常见的标准尺寸：
     * - A4 纸 (300 DPI): 2480 x 3508 像素
     * - A4 纸 (150 DPI): 1240 x 1754 像素
     * - A4 纸 (72 DPI):  595 x 842 像素
     * - 屏幕截图 (96 DPI): 常见 1920x1080 等
     *
     * 推断逻辑：
     * - 如果接近 A4 @ 300 DPI → 返回 300
     * - 如果接近 A4 @ 150 DPI → 返回 150
     * - 如果宽度 > 2000 像素 → 可能是 300 DPI
     * - 如果宽度 1000-2000 像素 → 可能是 150 DPI
     * - 否则 → 默认 96 DPI (屏幕截图)
     */
    public static float inferDpiFromImageSize(int width, int height) {
        // A4 纸在不同 DPI 下的像素尺寸
        int[][] a4Sizes = {
            {300, 2480, 3508},  // A4 @ 300 DPI
            {300, 3508, 2480},  // A4 横向 @ 300 DPI
            {150, 1240, 1754},  // A4 @ 150 DPI
            {150, 1754, 1240},  // A4 横向 @ 150 DPI
            {72, 595, 842},     // A4 @ 72 DPI
            {72, 842, 595},     // A4 横向 @ 72 DPI
        };

        // 检查是否接近标准 A4 尺寸
        for (int[] size : a4Sizes) {
            int dpi = size[0];
            int expectedW = size[1];
            int expectedH = size[2];

            // 允许 10% 的误差
            if (Math.abs(width - expectedW) < expectedW * 0.1 &&
                Math.abs(height - expectedH) < expectedH * 0.1) {
                log.debug("图片尺寸接近 A4 @ {} DPI: {}x{}", dpi, width, height);
                return dpi;
            }
        }

        // 根据像素宽度推断 DPI
        int maxDimension = Math.max(width, height);
        if (maxDimension >= 2000) {
            // 高分辨率图片，可能是 300 DPI
            return 300.0f;
        } else if (maxDimension >= 1200) {
            // 中等分辨率，可能是 150 DPI
            return 150.0f;
        } else if (maxDimension >= 600) {
            // 低分辨率，可能是 96 DPI (屏幕截图)
            return 96.0f;
        } else {
            // 很小的图片，默认 72 DPI
            return 72.0f;
        }
    }

    /**
     * 递归删除目录及其所有内容（增强版，带重试和Windows特殊处理）
     *
     * @param directory 要删除的目录
     */
    public static void deleteDirectory(File directory) {
        if (directory == null || !directory.exists()) {
            return;
        }

        // 重试配置
        final int MAX_RETRIES = 3;
        final long RETRY_DELAY_MS = 500;

        boolean success = false;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                deleteDirectoryInternal(directory);
                success = true;
                log.info("✓ 临时目录已清理: {} (尝试 {} 次)", directory.getPath(), attempt);
                break;
            } catch (Exception e) {
                log.warn("删除临时目录失败（尝试 {}/{}）: {}", attempt, MAX_RETRIES, directory.getPath());

                if (attempt < MAX_RETRIES) {
                    try {
                        // Windows下等待文件句柄释放
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    // 最后一次尝试失败，使用Windows强制删除命令
                    if (System.getProperty("os.name").toLowerCase().contains("win")) {
                        log.warn("尝试使用Windows强制删除命令: {}", directory.getPath());
                        boolean forceDeleted = forceDeleteOnWindows(directory);
                        if (forceDeleted) {
                            log.info("✓ Windows强制删除成功: {}", directory.getPath());
                            success = true;
                        } else {
                            log.error("✗ 所有尝试均失败，临时目录未清理: {}", directory.getPath());
                        }
                    } else {
                        log.error("✗ 所有尝试均失败，临时目录未清理: {}", directory.getPath());
                    }
                }
            }
        }

        // 记录失败统计
        if (!success && log.isWarnEnabled()) {
            log.warn("【警告】临时文件可能残留，请手动清理: {}", directory.getPath());
        }
    }

    /**
     * 递归删除目录的内部实现
     */
    private static void deleteDirectoryInternal(File directory) throws Exception {
        if (!directory.exists()) {
            return;
        }

        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectoryInternal(file);
                } else {
                    if (!file.delete()) {
                        throw new Exception("删除文件失败: " + file.getPath());
                    }
                }
            }
        }

        if (!directory.delete()) {
            throw new Exception("删除目录失败: " + directory.getPath());
        }
    }

    /**
     * Windows下使用系统命令强制删除目录
     *
     * @param directory 要删除的目录
     * @return 是否删除成功
     */
    private static boolean forceDeleteOnWindows(File directory) {
        try {
            String dirPath = directory.getAbsolutePath();
            final long commandTimeoutSeconds = 10;

            // 方法1：使用 rmdir 命令
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "rmdir", "/s", "/q", dirPath);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            Integer exitCode = waitForCleanupProcess(process, "cmd rmdir", commandTimeoutSeconds);
            if (exitCode != null && exitCode == 0 && !directory.exists()) {
                return true;
            }
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }

            // 方法2：使用 PowerShell 删除
            ProcessBuilder psPb = new ProcessBuilder(
                "powershell",
                "-Command",
                "Remove-Item -Path '" + dirPath.replace("'", "''") + "' -Recurse -Force -ErrorAction SilentlyContinue"
            );
            psPb.redirectErrorStream(true);
            Process psProcess = psPb.start();

            Integer psExitCode = waitForCleanupProcess(psProcess, "powershell Remove-Item", commandTimeoutSeconds);
            if (psExitCode != null && psExitCode == 0 && !directory.exists()) {
                return true;
            }

            return !directory.exists();

        } catch (Exception e) {
            log.warn("Windows强制删除命令执行失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 等待清理进程完成，避免外部命令卡住导致转换流程无法返回
     */
    private static Integer waitForCleanupProcess(Process process, String commandName, long timeoutSeconds) {
        try {
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Windows强制删除命令超时，已终止: {} ({}秒)", commandName, timeoutSeconds);
                return null;
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            log.warn("Windows强制删除命令被中断，已终止: {}", commandName);
            return null;
        }
    }

    /**
     * 将图片转换为单页PDF
     *
     * 【重要】DPI一致性说明：
     * - 此方法用于将图片转换为临时PDF以便后续处理
     * - 图片转PDF的DPI应与图片转双层PDF时使用的DPI一致（统一使用150 DPI）
     *
     * @param imagePath 图片文件路径
     * @param pdfPath 输出PDF路径
     * @throws Exception 转换失败时抛出异常
     */
    private static void convertImageToPdf(String imagePath, String pdfPath) throws Exception {
        log.info("将图片转换为PDF: {} -> {}", imagePath, pdfPath);

        // 使用 Python PIL 库将图片转换为 PDF
        String pythonPath = getPythonPath();
        String normalizedImagePath = imagePath.replace("\\", "/");
        String normalizedPdfPath = pdfPath.replace("\\", "/");

        // 【修复】使用配置的 DPI，与其他转换保持一致
        String[] command = {
            pythonPath, "-c",
            String.format(
                "from PIL import Image\n" +
                "img = Image.open('%s')\n" +
                "if img.mode != 'RGB':\n" +
                "    img = img.convert('RGB')\n" +
                "img.save('%s', 'PDF', resolution=%.1f, save_all=True)",
                normalizedImagePath,
                normalizedPdfPath,
                (float)PDF_EXTRACT_DPI
            )
        };

        log.debug("图片转PDF命令: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process = null;
        BufferedReader br = null;

        try {
            process = pb.start();

            br = new BufferedReader(
                new InputStreamReader(process.getInputStream(), "UTF-8")
            );

            String line;
            StringBuilder output = new StringBuilder();
            while ((line = br.readLine()) != null) {
                output.append(line).append("\n");
                log.debug("[IMG2PDF-LOG] {}", line);
            }

            int exitCode = waitForProcess(process, 60, "图片转PDF");

            if (exitCode != 0) {
                throw new IOException("图片转PDF失败，exitCode=" + exitCode +
                    ", output=" + output);
            }

            log.info("图片转PDF成功: {}", pdfPath);

        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    log.warn("关闭BufferedReader失败", e);
                }
            }
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String getDetailedImageErrorInfo(File file) {
        if (!file.exists()) {
            return "文件不存在";
        }
        if (!file.canRead()) {
            return "文件不可读，请检查权限";
        }
        long len = file.length();
        if (len == 0) {
            return "文件大小为0字节";
        }

        // 读取前 12 个字节以识别真实格式
        byte[] header = new byte[12];
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            int read = fis.read(header);
            if (read < 4) {
                return "文件长度不足 (实际大小: " + len + " 字节)";
            }

            // 转换为十六进制字符串
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < Math.min(read, 8); i++) {
                hex.append(String.format("%02X ", header[i]));
            }

            // 检查常见的魔数
            if (header[0] == (byte) 0xFF && header[1] == (byte) 0xD8) {
                return "格式为 JPEG (可能包含不支持的色彩空间，如 CMYK), 大小: " + len + " 字节, 魔数: " + hex.toString().trim();
            } else if (header[0] == (byte) 0x89 && header[1] == (byte) 0x50 && header[2] == (byte) 0x4E && header[3] == (byte) 0x47) {
                return "格式为 PNG, 大小: " + len + " 字节, 魔数: " + hex.toString().trim();
            } else if (header[0] == (byte) 0x47 && header[1] == (byte) 0x49 && header[2] == (byte) 0x46) {
                return "格式为 GIF, 大小: " + len + " 字节, 魔数: " + hex.toString().trim();
            } else if (header[0] == (byte) 0x42 && header[1] == (byte) 0x4D) {
                return "格式为 BMP, 大小: " + len + " 字节, 魔数: " + hex.toString().trim();
            } else if (header[0] == (byte) 0x52 && header[1] == (byte) 0x49 && header[2] == (byte) 0x46 && header[3] == (byte) 0x46) { // RIFF
                if (read >= 12 && header[8] == (byte) 0x57 && header[9] == (byte) 0x45 && header[10] == (byte) 0x42 && header[11] == (byte) 0x50) { // WEBP
                    return "格式为 WebP (Java ImageIO 默认不支持 WebP 格式，建议使用 PNG 或 RGB JPG), 大小: " + len + " 字节";
                }
                return "格式为 RIFF 容器 (可能为 WebP), 大小: " + len + " 字节, 魔数: " + hex.toString().trim();
            } else if (header[0] == (byte) 0x25 && header[1] == (byte) 0x50 && header[2] == (byte) 0x44 && header[3] == (byte) 0x46) { // %PDF
                return "格式为 PDF (不是图片文件，请勿将 PDF 文件后缀直接修改为图片后缀), 大小: " + len + " 字节";
            } else if (header[0] == (byte) 0x50 && header[1] == (byte) 0x4B && header[2] == (byte) 0x03 && header[3] == (byte) 0x04) { // PK.. (ZIP)
                return "格式为 ZIP / OFD 容器 (不是图片文件), 大小: " + len + " 字节";
            }

            return "未知格式, 大小: " + len + " 字节, 魔数前缀: " + hex.toString().trim();
        } catch (Exception e) {
            return "无法读取文件内容 (" + e.getMessage() + "), 大小: " + len + " 字节";
        }
    }

    /**
     * 等待进程结束（带超时保护）
     *
     * @param process 进程对象
     * @param timeoutSeconds 超时时间（秒）
     * @param operation 操作描述（用于日志）
     * @return 进程退出码
     * @throws IOException 超时或进程异常时抛出
     */
    private static int waitForProcess(Process process, int timeoutSeconds, String operation) throws IOException, InterruptedException {
        if (process == null) {
            throw new IllegalArgumentException("进程对象不能为空");
        }

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            // 超时，强制终止进程
            log.warn("进程超时（{}秒），强制终止: {}", timeoutSeconds, operation);
            process.destroyForcibly();
            // 等待进程完全终止
            try {
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("进程执行超时（超过" + timeoutSeconds + "秒）: " + operation);
        }

        return process.exitValue();
    }
}
