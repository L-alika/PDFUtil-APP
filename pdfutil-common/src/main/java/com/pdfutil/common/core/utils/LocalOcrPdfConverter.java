package com.pdfutil.common.core.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地PaddleOCR双层PDF转换器
 *
 * 使用本地PaddleOCR + OCRmyPDF生成双层PDF
 *
 * 架构：
 * 输入文件 (PDF/图片)
 *    ↓
 * 提取页面为图片（如果是PDF）
 *    ↓
 * 本地PaddleOCR（识字）→ 输出：文本 + 字符坐标
 *    ↓
 * 转换为hOCR格式
 *    ↓
 * OCRmyPDF（使用hOCR生成双层PDF）
 *    ↓
 * 最终 PDF
 *
 * @author Alika
 * @date 2025-02-06
 */
public class LocalOcrPdfConverter {

    private static final Logger log = LoggerFactory.getLogger(LocalOcrPdfConverter.class);

    // 支持的输入文件格式
    private static final String[] SUPPORTED_FORMATS = {
        "pdf", "jpg", "jpeg", "png", "tif", "tiff", "bmp", "gif"
    };

    // 【性能优化】缓存反射方法，避免每次调用都进行反射查找
    private static java.lang.reflect.Method cachedCreateDualLayerPdfMethod = null;
    private static final Object methodLock = new Object();

    // 【性能优化】并行生成PDF的线程池
    private static volatile ExecutorService pdfGeneratorPool = null;
    private static final Object poolLock = new Object();

    // 默认并行线程数（CPU核心数，最多4个）
    private static final int DEFAULT_PARALLEL_THREADS = Math.min(Runtime.getRuntime().availableProcessors(), 4);

    /**
     * 获取并行PDF生成线程池（懒加载）
     */
    private static ExecutorService getPdfGeneratorPool() {
        if (pdfGeneratorPool == null || pdfGeneratorPool.isShutdown()) {
            synchronized (poolLock) {
                if (pdfGeneratorPool == null || pdfGeneratorPool.isShutdown()) {
                    pdfGeneratorPool = Executors.newFixedThreadPool(DEFAULT_PARALLEL_THREADS, r -> {
                        Thread t = new Thread(r, "PdfPageGenerator-" + System.currentTimeMillis());
                        t.setDaemon(true);
                        return t;
                    });
                    log.info("初始化PDF页面生成线程池，线程数: {}", DEFAULT_PARALLEL_THREADS);
                }
            }
        }
        return pdfGeneratorPool;
    }

    /**
     * 关闭线程池（应用退出时调用）
     */
    public static void shutdown() {
        synchronized (poolLock) {
            if (pdfGeneratorPool != null && !pdfGeneratorPool.isShutdown()) {
                log.info("关闭PDF页面生成线程池...");
                pdfGeneratorPool.shutdown();
                try {
                    if (!pdfGeneratorPool.awaitTermination(30, TimeUnit.SECONDS)) {
                        pdfGeneratorPool.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    pdfGeneratorPool.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * 获取缓存的双层PDF生成方法（懒加载）
     */
    private static java.lang.reflect.Method getCreateDualLayerPdfMethod() throws ClassNotFoundException, NoSuchMethodException {
        if (cachedCreateDualLayerPdfMethod == null) {
            synchronized (methodLock) {
                if (cachedCreateDualLayerPdfMethod == null) {
                    Class<?> adderClass = Class.forName("com.pdfutil.pdf.utils.HocrTextLayerAdder");
                    cachedCreateDualLayerPdfMethod = adderClass.getMethod("createDualLayerPdfFromOcr",
                        String.class, String.class, int.class, int.class, String.class, float.class);
                    log.info("已缓存 HocrTextLayerAdder.createDualLayerPdfFromOcr 方法");
                }
            }
        }
        return cachedCreateDualLayerPdfMethod;
    }

    /**
     * 创建配置好的LocalOcrClient实例
     * 从系统属性读取配置
     *
     * @return 配置好的LocalOcrClient实例
     */
    private static LocalOcrClient createConfiguredOcrClient() {
        // 从系统属性读取配置
        String pythonPath = System.getProperty("python.path", "python");
        String scriptPath = System.getProperty("paddleocr.script.path", "scripts/paddle_ocr_local.py");

        // 读取模型路径配置（可选）
        String detModelDir = System.getProperty("paddleocr.det.model.dir");
        String recModelDir = System.getProperty("paddleocr.rec.model.dir");

        int executeTimeout = Integer.getInteger(
            "pdfutil.pdf.localOcrExecuteTimeout",
            300000  // 默认5分钟
        );

        log.debug("创建LocalOcrClient - Python: {}, 脚本: {}, 检测模型: {}, 识别模型: {}, 超时: {}ms",
            pythonPath, scriptPath, detModelDir, recModelDir, executeTimeout);

        return new LocalOcrClient(pythonPath, scriptPath, detModelDir, recModelDir, executeTimeout);
    }

    /**
     * 将图片/PDF转换为双层PDF（主入口）
     *
     * @param inputPath 输入文件路径
     * @param outputPath 输出的双层PDF文件路径
     * @throws Exception 转换过程中可能抛出的异常
     */
    public static void convertToDualLayerPdf(String inputPath, String outputPath) throws Exception {
        log.info("开始使用本地PaddleOCR生成双层PDF: {} -> {}", inputPath, outputPath);

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

        // 检查文件格式
        String extension = getFileExtension(inputFile.getName()).toLowerCase();
        boolean isSupported = false;
        for (String format : SUPPORTED_FORMATS) {
            if (format.equals(extension)) {
                isSupported = true;
                break;
            }
        }

        if (!isSupported) {
            throw new IllegalArgumentException("不支持的文件格式: " + extension);
        }

        // 确保输出目录存在
        File outputFile = new File(outputPath);
        File outputDir = outputFile.getParentFile();
        if (outputDir != null && !outputDir.exists()) {
            outputDir.mkdirs();
        }

        try {
            if ("pdf".equals(extension)) {
                // PDF文件：需要先将每一页转换为图片
                convertPdfWithLocalOcr(inputPath, outputPath);
            } else {
                // 图片文件：直接调用OCR
                convertImageWithLocalOcr(inputPath, outputPath);
            }

            // 验证输出文件
            if (!outputFile.exists() || outputFile.length() == 0) {
                throw new RuntimeException("转换失败，未生成有效输出文件");
            }

            log.info("本地OCR双层PDF转换成功: {}", outputPath);

        } catch (Exception e) {
            log.error("本地OCR双层PDF转换失败: {}", inputPath, e);
            throw e;
        }
    }

    /**
     * 使用本地PaddleOCR将图片转换为双层PDF
     *
     * 优化流程：图片 → 本地OCR → 双层PDF（不创建临时PDF）
     *
     * @param imagePath 图片文件路径
     * @param outputPath 输出PDF路径
     * @throws Exception 转换失败时抛出异常
     */
    private static void convertImageWithLocalOcr(String imagePath, String outputPath) throws Exception {
        log.info("图片转双层PDF流程开始");

        // 步骤1: 读取图片尺寸
        File imageFile = new File(imagePath);
        BufferedImage image = ImageIO.read(imageFile);

        // 检查图片是否成功读取
        if (image == null) {
            throw new IOException("无法读取图片文件，可能文件格式不支持或文件已损坏: " + imagePath);
        }

        int imageWidth;
        int imageHeight;
        try {
            imageWidth = image.getWidth();
            imageHeight = image.getHeight();
            log.info("图片尺寸: {}x{}", imageWidth, imageHeight);
        } finally {
            // 【修复】确保图片资源被释放
            image.flush();
        }

        // 步骤2: 直接对原始图片调用本地OCR（不创建临时PDF）
        log.info("步骤1: 调用本地PaddleOCR处理原始图片");
        LocalOcrClient ocrClient = createConfiguredOcrClient();
        JsonNode jsonResult = ocrClient.recognizeImage(imagePath);
        log.info("本地OCR调用成功");

        // 解析OCR结果
        JsonNode pagesNode = jsonResult.get("pages");
        int ocrPageCount = (pagesNode != null && pagesNode.isArray()) ? pagesNode.size() : 0;

        if (ocrPageCount == 0) {
            throw new RuntimeException("本地OCR未返回任何有效结果，请检查图片文件或OCR环境");
        }

        // 步骤3: 使用原始图片和OCR结果生成双层PDF
        log.info("步骤2: 使用PDFBox生成双层PDF");

        // 【稳定性修复】使用缓存的反射方法
        try {
            java.lang.reflect.Method method = getCreateDualLayerPdfMethod();

            // 提取第一页的OCR结果（图片只有一页）
            JsonNode pageData = pagesNode.get(0);
            ObjectMapper mapper = new ObjectMapper();
            String pageOcrResult = "{\"pages\": [" + mapper.writeValueAsString(pageData) + "]}";
            // 统一使用配置的 DPI，与其他转换保持一致
            method.invoke(null, imagePath, pageOcrResult, imageWidth, imageHeight, outputPath, (float)DualLayerPdfConverter.PDF_EXTRACT_DPI);

            log.info("图片双层PDF生成成功");

        } catch (ClassNotFoundException e) {
            log.error("找不到 HocrTextLayerAdder 类，请确保 pdf 模块已正确部署", e);
            throw new RuntimeException("双层PDF生成失败：缺少必要的依赖类 HocrTextLayerAdder", e);
        } catch (NoSuchMethodException e) {
            log.error("HocrTextLayerAdder 类缺少 createDualLayerPdfFromOcr 方法", e);
            throw new RuntimeException("双层PDF生成失败：依赖类方法签名不匹配", e);
        } catch (IllegalAccessException e) {
            log.error("无法访问 HocrTextLayerAdder 方法", e);
            throw new RuntimeException("双层PDF生成失败：权限不足", e);
        } catch (java.lang.reflect.InvocationTargetException e) {
            log.error("调用 HocrTextLayerAdder 方法失败", e);
            throw new RuntimeException("双层PDF生成失败：" + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()), e);
        }
    }

    /**
     * 将图片转换为单页PDF（仅包含图片作为背景）
     *
     * @param imagePath 图片文件路径
     * @param pdfPath 输出PDF路径
     * @param width 图片宽度（像素）
     * @param height 图片高度（像素）
     * @throws Exception 转换失败时抛出异常
     */
    private static void convertImageToPdf(String imagePath, String pdfPath, int width, int height) throws Exception {
        log.info("创建临时PDF(无损): 图片尺寸={}x{} (像素)", width, height);

        // 【无损】直接使用原始图片，不进行压缩

        // 统一使用配置的 DPI，与其他转换保持一致
        final float SOURCE_DPI = (float)DualLayerPdfConverter.PDF_EXTRACT_DPI;
        final float PDF_DPI = 72.0f;
        float pdfWidth = width * (PDF_DPI / SOURCE_DPI);
        float pdfHeight = height * (PDF_DPI / SOURCE_DPI);

        log.info("PDF页面尺寸: {}x{} (点, 1/72英寸, 源DPI={})", pdfWidth, pdfHeight, SOURCE_DPI);

        // 【稳定性修复】添加反射调用异常处理
        try {
            // 使用反射调用PDFBox创建PDF
            Class<?> documentClass = Class.forName("org.apache.pdfbox.pdmodel.PDDocument");
            Class<?> pageClass = Class.forName("org.apache.pdfbox.pdmodel.PDPage");
            Class<?> rectClass = Class.forName("org.apache.pdfbox.pdmodel.common.PDRectangle");
            Class<?> appendModeClass = Class.forName("org.apache.pdfbox.pdmodel.PDPageContentStream$AppendMode");
            Class<?> contentStreamClass = Class.forName("org.apache.pdfbox.pdmodel.PDPageContentStream");
            Class<?> imageClass = Class.forName("org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject");

            // 获取APPEND枚举常量
            @SuppressWarnings("unchecked")
            Class<? extends Enum> enumClass = (Class<? extends Enum>) appendModeClass;
            Object appendEnum = Enum.valueOf(enumClass, "APPEND");

            // 创建PDF文档
            Object document = documentClass.getDeclaredConstructor().newInstance();
            Object pageSize = rectClass.getConstructor(float.class, float.class).newInstance(pdfWidth, pdfHeight);
            Object page = pageClass.getConstructor(rectClass).newInstance(pageSize);

            // 添加页面
            documentClass.getMethod("addPage", pageClass).invoke(document, page);

            // 创建内容流
            Object contentStream = contentStreamClass.getConstructor(
                documentClass, pageClass, appendModeClass, boolean.class, boolean.class
            ).newInstance(document, page, appendEnum, true, true);

            // 【无损】直接使用原始图片
            Object pdImage = imageClass.getMethod("createFromFile", String.class, documentClass).invoke(null, imagePath, document);

            // 绘制图片，填满整个页面（1:1像素映射）
            contentStreamClass.getMethod("drawImage", imageClass, float.class, float.class, float.class, float.class)
                .invoke(contentStream, pdImage, 0f, 0f, pdfWidth, pdfHeight);

            // 关闭内容流
            contentStreamClass.getMethod("close").invoke(contentStream);

            // 保存PDF
            documentClass.getMethod("save", String.class).invoke(document, pdfPath);
            log.info("临时PDF已保存(无损): {}", pdfPath);

            // 关闭文档
            documentClass.getMethod("close").invoke(document);

        } catch (ClassNotFoundException e) {
            log.error("找不到 PDFBox 类，请确保 PDFBox 依赖已正确添加", e);
            throw new RuntimeException("图片转PDF失败：缺少 PDFBox 依赖库", e);
        } catch (NoSuchMethodException e) {
            log.error("PDFBox 类缺少必要的方法", e);
            throw new RuntimeException("图片转PDF失败：PDFBox 版本不兼容", e);
        } catch (InstantiationException e) {
            log.error("PDFBox 类实例化失败", e);
            throw new RuntimeException("图片转PDF失败：无法创建 PDFBox 对象", e);
        } catch (IllegalAccessException e) {
            log.error("无法访问 PDFBox 方法", e);
            throw new RuntimeException("图片转PDF失败：权限不足", e);
        } catch (java.lang.reflect.InvocationTargetException e) {
            log.error("调用 PDFBox 方法失败", e);
            throw new RuntimeException("图片转PDF失败：" + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()), e);
        }
    }

    /**
     * 使用本地PaddleOCR将PDF转换为双层PDF
     *
     * 【关键修复】DPI一致性保证：
     * 1. 先用 pdftoppm 提取PDF页面为图片（150 DPI）
     * 2. 对提取的图片进行OCR（确保坐标基于相同的DPI）
     * 3. 使用相同的DPI生成双层PDF
     *
     * 之前的问题：PaddleOCR直接读取PDF时内部渲染DPI与pdftoppm不一致
     *
     * @param pdfPath PDF文件路径
     * @param outputPath 输出PDF路径
     * @throws Exception 转换失败时抛出异常
     */
    private static void convertPdfWithLocalOcr(String pdfPath, String outputPath) throws Exception {
        log.info("PDF文件处理：使用本地PaddleOCR处理PDF");

        // 创建唯一的临时目录，避免并发冲突（使用UUID确保唯一性）
        String uniqueId = java.util.UUID.randomUUID().toString().replace("-", "");
        String tempDir = System.getProperty("java.io.tmpdir") + "/local_ocr_pdf_" + uniqueId;
        new File(tempDir).mkdirs();

        List<String> imagePaths = new ArrayList<>();
        List<String> singlePagePdfs = new ArrayList<>();

        // 读取原始PDF页面尺寸用于验证
        float[] originalPageSizes = getPdfPageSizes(pdfPath);
        log.info("原始PDF共 {} 页", originalPageSizes.length / 2);

        try {
            // 【关键修复】步骤1: 先提取PDF页面为图片
            // 这确保后续OCR和PDF生成使用完全相同的图片和DPI
            log.info("步骤1: 提取PDF页面为图片 ({} DPI)", DualLayerPdfConverter.PDF_EXTRACT_DPI);
            imagePaths = convertPdfToImages(pdfPath, tempDir);
            int pdfPageCount = imagePaths.size();
            log.info("共提取 {} 页图片", pdfPageCount);

            // 【DPI一致性验证】检查提取的图片尺寸是否与PDF页面尺寸匹配
            for (int i = 0; i < Math.min(imagePaths.size(), originalPageSizes.length / 2); i++) {
                File imgFile = new File(imagePaths.get(i));
                BufferedImage img = ImageIO.read(imgFile);
                if (img != null) {
                    float pdfWidth = originalPageSizes[i * 2];
                    float pdfHeight = originalPageSizes[i * 2 + 1];
                    // 计算期望的图片尺寸（使用配置的 DPI）
                    float expectedWidth = pdfWidth * DualLayerPdfConverter.PDF_EXTRACT_DPI / 72;
                    float expectedHeight = pdfHeight * DualLayerPdfConverter.PDF_EXTRACT_DPI / 72;
                    log.debug("第{}页尺寸验证: PDF={}x{}点, 期望图片={}x{}像素, 实际图片={}x{}像素",
                            i + 1, pdfWidth, pdfHeight, (int) expectedWidth, (int) expectedHeight,
                            img.getWidth(), img.getHeight());

                    // 检查尺寸是否匹配（允许1像素的舍入误差）
                    if (Math.abs(img.getWidth() - expectedWidth) > 2 || Math.abs(img.getHeight() - expectedHeight) > 2) {
                        log.warn("第{}页尺寸不匹配! 期望={}x{}, 实际={}x{}。这可能导致文本层偏移。",
                                i + 1, (int) expectedWidth, (int) expectedHeight, img.getWidth(), img.getHeight());
                    }
                }
            }

            if (pdfPageCount == 0) {
                throw new RuntimeException("PDF页面提取失败，未生成任何图片");
            }


            // 步骤 2: 对提取的图片进行 OCR（批量识别，性能优化）
            log.info("步骤 2: 对提取的 {} 张图片调用本地 PaddleOCR 批量识别", imagePaths.size());
            LocalOcrClient ocrClient = createConfiguredOcrClient();

            // 【性能优化】使用批量识别接口，一次性处理所有图片
            // 避免逐张调用 OCR 导致的多次 Python 进程启动开销
            JsonNode batchResult = ocrClient.recognizeImages(imagePaths);
            JsonNode pagesNode = batchResult.get("pages");
            int ocrPageCount = pagesNode.size();
            log.info("本地 OCR 批量识别完成，共识别 {} 页", ocrPageCount);

            // 【关键修复】页数应该完全匹配，因为我们是先提取图片再 OCR
            int processPages = ocrPageCount;
            if (ocrPageCount != pdfPageCount) {
                log.warn("警告：OCR 返回的页数 ({}) 与提取的图片页数 ({}) 不匹配，处理 {} 页",
                    ocrPageCount, pdfPageCount, processPages);
            }

            if (processPages == 0) {
                throw new RuntimeException("OCR 未返回任何有效结果，请检查 PDF 文件或 OCR 环境");
            }

            // 步骤 3: 为每一页生成双层 PDF（并行处理）
            log.info("步骤 3: 为每一页生成双层 PDF（并行，线程数: {}）", DEFAULT_PARALLEL_THREADS);

            // 【性能优化】使用缓存的反射方法
            java.lang.reflect.Method method;
            try {
                method = getCreateDualLayerPdfMethod();
            } catch (ClassNotFoundException e) {
                log.error("找不到 HocrTextLayerAdder 类，请确保 pdf 模块已正确部署", e);
                throw new RuntimeException("双层 PDF 生成失败：缺少必要的依赖类 HocrTextLayerAdder", e);
            } catch (NoSuchMethodException e) {
                log.error("HocrTextLayerAdder 类缺少 createDualLayerPdfFromOcr 方法", e);
                throw new RuntimeException("双层 PDF 生成失败：依赖类方法签名不匹配", e);
            }

            // 需要 mapper 来序列化 OCR 结果
            ObjectMapper mapper = new ObjectMapper();

            // 【性能优化】使用线程安全的集合存储结果
            ConcurrentMap<Integer, String> pagePdfMap = new ConcurrentHashMap<>();
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);

            final java.lang.reflect.Method finalMethod = method;
            final int finalProcessPages = processPages;

            // 并行提交所有页面生成任务
            for (int i = 0; i < processPages; i++) {
                final int pageIndex = i;
                final String imagePath = imagePaths.get(i);

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        log.debug("并行处理第 {} 页", pageIndex + 1);

                        // 获取图片尺寸
                        File imageFile = new File(imagePath);
                        BufferedImage image = ImageIO.read(imageFile);

                        // 检查图片是否成功读取
                        if (image == null) {
                            throw new IOException("无法读取图片文件，可能文件格式不支持或文件已损坏：" + imagePath);
                        }

                        int imageWidth;
                        int imageHeight;
                        try {
                            imageWidth = image.getWidth();
                            imageHeight = image.getHeight();
                        } finally {
                            image.flush();
                        }

                        // 提取第 pageIndex 页的 OCR 结果
                        JsonNode pageData = pagesNode.get(pageIndex);
                        String pageOcrResult = "{\"pages\": [" + mapper.writeValueAsString(pageData) + "]}";

                        // 生成单页双层 PDF
                        String singlePdfPath = tempDir + "/page_" + pageIndex + ".pdf";
                        // 【关键】PDF 提取的图片使用配置的 DPI，与提取时一致
                        finalMethod.invoke(null, imagePath, pageOcrResult, imageWidth, imageHeight, singlePdfPath, (float)DualLayerPdfConverter.PDF_EXTRACT_DPI);

                        pagePdfMap.put(pageIndex, singlePdfPath);
                        successCount.incrementAndGet();

                    } catch (Exception e) {
                        log.error("并行生成第 {} 页PDF失败: {}", pageIndex + 1, e.getMessage());
                        failCount.incrementAndGet();
                        throw new RuntimeException("并行生成第 " + (pageIndex + 1) + " 页PDF失败: " + e.getMessage(), e);
                    }
                }, getPdfGeneratorPool());

                futures.add(future);
            }

            // 等待所有任务完成
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("PDF生成被中断", e);
            } catch (ExecutionException e) {
                log.error("并行PDF生成过程中发生错误", e);
                throw new RuntimeException("PDF生成失败: " + e.getMessage(), e);
            }

            log.info("并行PDF生成完成: 成功 {} 页, 失败 {} 页", successCount.get(), failCount.get());

            // 按页码顺序收集生成的PDF路径
            for (int i = 0; i < finalProcessPages; i++) {
                String singlePagePdfPath = pagePdfMap.get(i);
                if (singlePagePdfPath != null) {
                    singlePagePdfs.add(singlePagePdfPath);
                }
            }

            if (singlePagePdfs.isEmpty()) {
                throw new RuntimeException("所有页面PDF生成失败");
            }

            // 步骤4: 合并所有页面为最终PDF
            log.info("步骤4: 合并所有页面为最终PDF");
            if (singlePagePdfs.size() == 1) {
                // 只有一页，直接移动
                Files.move(Paths.get(singlePagePdfs.get(0)), Paths.get(outputPath), StandardCopyOption.REPLACE_EXISTING);
            } else {
                // 多页需要合并
                mergePdfFiles(singlePagePdfs, outputPath);
            }

            log.info("PDF双层PDF生成成功");

        } finally {
            // 使用统一的、更健壮的临时目录清理逻辑，支持重试和 Windows 强制删除，防磁盘泄露
            DualLayerPdfConverter.deleteDirectory(new File(tempDir));
        }
    }

    /**
     * 合并多个PDF文件（使用PDFBox的PDFMergerUtility，通过反射调用）
     */
    private static void mergePdfFiles(List<String> pdfPaths, String outputPath) throws Exception {
        log.info("合并 {} 个PDF文件", pdfPaths.size());

        // 【稳定性修复】添加反射调用异常处理
        try {
            // 使用PDFMergerUtility来合并PDF
            Class<?> mergerClass = Class.forName("org.apache.pdfbox.multipdf.PDFMergerUtility");
            Object merger = mergerClass.getDeclaredConstructor().newInstance();

            java.lang.reflect.Method setDestinationFileNameMethod = mergerClass.getMethod("setDestinationFileName", String.class);
            java.lang.reflect.Method addSourceMethod = mergerClass.getMethod("addSource", File.class);
            java.lang.reflect.Method mergeDocumentsMethod = mergerClass.getMethod("mergeDocuments");

            setDestinationFileNameMethod.invoke(merger, outputPath);

            for (String pdfPath : pdfPaths) {
                addSourceMethod.invoke(merger, new File(pdfPath));
            }

            mergeDocumentsMethod.invoke(merger);

            log.info("PDF合并成功: {}", outputPath);

        } catch (ClassNotFoundException e) {
            log.error("找不到 PDFBox PDFMergerUtility 类，请确保 PDFBox 依赖已正确添加", e);
            throw new RuntimeException("PDF合并失败：缺少 PDFBox 依赖库", e);
        } catch (NoSuchMethodException e) {
            log.error("PDFBox PDFMergerUtility 类缺少必要的方法", e);
            throw new RuntimeException("PDF合并失败：PDFBox 版本不兼容", e);
        } catch (InstantiationException e) {
            log.error("PDFBox PDFMergerUtility 类实例化失败", e);
            throw new RuntimeException("PDF合并失败：无法创建 PDFBox 对象", e);
        } catch (IllegalAccessException e) {
            log.error("无法访问 PDFBox 方法", e);
            throw new RuntimeException("PDF合并失败：权限不足", e);
        } catch (java.lang.reflect.InvocationTargetException e) {
            log.error("调用 PDFBox 方法失败", e);
            throw new RuntimeException("PDF合并失败：" + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()), e);
        }
    }

    /**
     * 获取PDF每页的页面尺寸（点）
     * 使用PDFBox通过反射调用，避免直接依赖
     *
     * @param pdfPath PDF文件路径
     * @return 页面尺寸数组，每页两个元素：[width1, height1, width2, height2, ...]
     */
    private static float[] getPdfPageSizes(String pdfPath) {
        List<Float> sizes = new ArrayList<>();
        try {
            Class<?> documentClass = Class.forName("org.apache.pdfbox.pdmodel.PDDocument");
            Class<?> pageClass = Class.forName("org.apache.pdfbox.pdmodel.PDPage");
            Class<?> rectClass = Class.forName("org.apache.pdfbox.pdmodel.common.PDRectangle");

            java.lang.reflect.Method loadMethod = documentClass.getMethod("load", File.class);
            java.lang.reflect.Method getPagesMethod = documentClass.getMethod("getPages");
            java.lang.reflect.Method getMethod = pageClass.getMethod("getMediaBox");
            java.lang.reflect.Method getWidthMethod = rectClass.getMethod("getWidth");
            java.lang.reflect.Method getHeightMethod = rectClass.getMethod("getHeight");
            java.lang.reflect.Method closeMethod = documentClass.getMethod("close");

            Object document = loadMethod.invoke(null, new File(pdfPath));
            Object pages = getPagesMethod.invoke(document);

            // 获取页面数量 - 尝试不同的方法名
            int pageCount = 0;
            try {
                // 先尝试 getCount() 方法 (PDFBox 2.x)
                java.lang.reflect.Method countMethod = pages.getClass().getMethod("getCount");
                pageCount = (int) countMethod.invoke(pages);
            } catch (NoSuchMethodException e) {
                try {
                    // 再尝试 size() 方法 (List 接口)
                    java.lang.reflect.Method sizeMethod = pages.getClass().getMethod("size");
                    pageCount = (int) sizeMethod.invoke(pages);
                } catch (NoSuchMethodException e2) {
                    log.warn("无法获取PDF页数方法");
                }
            }

            // 遍历页面 - 使用迭代器方式
            try {
                java.lang.reflect.Method iteratorMethod = pages.getClass().getMethod("iterator");
                java.util.Iterator<?> pageIterator = (java.util.Iterator<?>) iteratorMethod.invoke(pages);
                int pageIndex = 0;
                while (pageIterator.hasNext() && (pageCount == 0 || pageIndex < pageCount)) {
                    Object page = pageIterator.next();
                    Object mediaBox = getMethod.invoke(page);
                    float width = (float) getWidthMethod.invoke(mediaBox);
                    float height = (float) getHeightMethod.invoke(mediaBox);
                    sizes.add(width);
                    sizes.add(height);
                    pageIndex++;
                }
            } catch (NoSuchMethodException e) {
                // 回退到 get(index) 方式
                java.lang.reflect.Method getPageMethod = pages.getClass().getMethod("get", int.class);
                for (int i = 0; i < pageCount; i++) {
                    Object page = getPageMethod.invoke(pages, i);
                    Object mediaBox = getMethod.invoke(page);
                    float width = (float) getWidthMethod.invoke(mediaBox);
                    float height = (float) getHeightMethod.invoke(mediaBox);
                    sizes.add(width);
                    sizes.add(height);
                }
            }

            closeMethod.invoke(document);
        } catch (Exception e) {
            log.warn("无法读取PDF页面尺寸: {}", e.getMessage());
        }

        // 转换为float数组
        float[] result = new float[sizes.size()];
        for (int i = 0; i < sizes.size(); i++) {
            result[i] = sizes.get(i);
        }
        return result;
    }

    /**
     * 将PDF转换为图片（提取每一页）
     * 【性能优化】使用 pdf2image 库替代 pdftoppm，支持多线程处理
     * 使用PNG格式和配置的DPI以保持图片质量
     */
    private static List<String> convertPdfToImages(String pdfPath, String outputDir) throws Exception {
        String pythonPath = System.getProperty("python.path", "python");

        // 路径处理：统一使用正斜杠（跨平台兼容）
        String normalizedPdfPath = pdfPath.replace("\\", "/");
        String normalizedOutputDir = outputDir.replace("\\", "/");

        // 使用配置的DPI（默认200 DPI）
        int actualDpi = DualLayerPdfConverter.PDF_EXTRACT_DPI;

        // 获取线程数配置（默认CPU核心数，最多4个）
        int threadCount = DualLayerPdfConverter.PDF_EXTRACT_THREAD_COUNT;

        // 使用 pdf2image 库进行多线程转换
        // paths_only + output_folder 让底层直接落盘，避免Python端持有所有PIL图片
        String[] command = {
            pythonPath, "-c",
            String.format(
                "from pdf2image import convert_from_path\n" +
                "import os\n" +
                "os.makedirs(r'%s', exist_ok=True)\n" +
                "paths = convert_from_path(r'%s', dpi=%d, fmt='png', use_cropbox=True, " +
                "output_folder=r'%s', output_file='page', paths_only=True, thread_count=%d)\n" +
                "for img_path in paths:\n" +
                "    print(os.path.abspath(img_path))",
                normalizedOutputDir,
                normalizedPdfPath,
                actualDpi,
                normalizedOutputDir,
                threadCount
            )
        };

        log.info("【多线程优化】PDF转图片: DPI={}, threadCount={}, 格式=PNG, 路径: {}",
                actualDpi, threadCount, pdfPath);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process = null;
        BufferedReader reader = null;

        try {
            process = pb.start();

            reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), "UTF-8")
            );

            List<String> imageList = new ArrayList<>();
            StringBuilder processOutput = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    processOutput.append(trimmed).append('\n');
                    File generatedFile = new File(trimmed);
                    if (generatedFile.exists() && trimmed.endsWith(".png")) {
                        imageList.add(generatedFile.getAbsolutePath());
                        log.debug("生成图片: {}", generatedFile.getAbsolutePath());
                    } else {
                        log.debug("[PDF2IMAGE-LOG] {}", trimmed);
                    }
                }
            }

            int exitCode = waitForProcess(process, 120, "PDF转图片（多线程）");

            if (exitCode != 0) {
                throw new IOException("PDF转图片失败: " + processOutput);
            }

            if (imageList.isEmpty()) {
                throw new IOException("PDF转图片失败：未生成任何图片，输出=" + processOutput);
            }

            // 按页码排序
            imageList.sort((a, b) -> {
                int pageA = extractPageNumber(a);
                int pageB = extractPageNumber(b);
                if (pageA != pageB) {
                    return Integer.compare(pageA, pageB);
                }
                return a.compareTo(b);
            });

            log.info("【多线程优化】PDF转图片完成: 共 {} 页, DPI={}, 线程数={}",
                    imageList.size(), actualDpi, threadCount);
            return imageList;

        } finally {
            if (reader != null) {
                try {
                    reader.close();
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
     * 从文件路径中提取页码
     */
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

    /**
     * 执行命令
     */
    private static void execCommand(String[] command) throws IOException, InterruptedException {
        log.info("执行命令: {}", String.join(" ", command));

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
                log.debug("[CMD-LOG] {}", line);
            }

            int exitCode = waitForProcess(process, 3600, "执行命令");

            if (exitCode != 0) {
                String errorMsg = String.format("命令执行失败，exitCode=%d, 命令=%s, 输出=%s",
                    exitCode, String.join(" ", command), output);
                log.error(errorMsg);

                // 检查常见的错误原因
                String outputStr = output.toString().toLowerCase();
                if (outputStr.contains("command not found") || outputStr.contains("not recognized")) {
                    throw new IOException("pdftoppm命令未找到，请确保已安装poppler-utils包");
                } else if (outputStr.contains("permission denied")) {
                    throw new IOException("权限不足，无法执行pdftoppm命令");
                } else if (outputStr.contains("no such file")) {
                    throw new IOException("PDF文件不存在或无法访问");
                }

                throw new IOException(errorMsg);
            }

            log.info("命令执行成功，输出长度: {} 字符", output.length());

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

    /**
     * 递归删除目录及其所有内容
     *
     * @param directory 要删除的目录
     */


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
        return fileName.substring(lastDotIndex + 1);
    }

    /**
     * 使用已有的OCR结果生成双层PDF（批量转换优化用）
     * 避免重复调用OCR，直接使用已有结果生成PDF
     *
     * @param imagePath 源图片路径
     * @param outputPath 输出PDF路径
     * @param ocrResult OCR识别结果（单个页面的JsonNode）
     * @param width 图片宽度
     * @param height 图片高度
     * @throws Exception 生成失败时抛出异常
     */
    public static void generateDualLayerPdfWithOcrResult(String imagePath, String outputPath,
                                                          JsonNode ocrResult, int width, int height) throws Exception {
        log.debug("使用已有OCR结果生成双层PDF: {} -> {}", imagePath, outputPath);

        // 确保输出目录存在
        File outputFile = new File(outputPath);
        File outputDir = outputFile.getParentFile();
        if (outputDir != null && !outputDir.exists()) {
            outputDir.mkdirs();
        }

        // 【性能优化】使用缓存的反射方法
        try {
            java.lang.reflect.Method method = getCreateDualLayerPdfMethod();

            ObjectMapper mapper = new ObjectMapper();
            String pageOcrResult = "{\"pages\": [" + mapper.writeValueAsString(ocrResult) + "]}";

            // 统一使用配置的 DPI
            method.invoke(null, imagePath, pageOcrResult, width, height, outputPath, (float)DualLayerPdfConverter.PDF_EXTRACT_DPI);

            log.debug("双层PDF生成成功: {}", outputPath);

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
}
