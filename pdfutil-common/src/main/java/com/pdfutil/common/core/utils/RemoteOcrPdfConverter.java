package com.pdfutil.common.core.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 远程OCR双层PDF转换器
 *
 * 使用远程OCR API + OCRmyPDF生成双层PDF
 *
 * 架构：
 * 输入文件 (PDF/图片)
 *    ↓
 * 提取页面为图片（如果是PDF）
 *    ↓
 * 远程OCR API（识字）→ 输出：文本 + 字符坐标
 *    ↓
 * 转换为hOCR格式
 *    ↓
 * OCRmyPDF（使用hOCR生成双层PDF）
 *    ↓
 * 最终 PDF
 *
 * @author Alika
 * @date 2025-02-05
 */
public class RemoteOcrPdfConverter {

    private static final Logger log = LoggerFactory.getLogger(RemoteOcrPdfConverter.class);

    private static final String DEFAULT_API_URL = "http://192.168.124.66:8090/api/ocr";

    // 支持的输入文件格式
    private static final String[] SUPPORTED_FORMATS = {
        "pdf", "jpg", "jpeg", "png", "tif", "tiff", "bmp", "gif"
    };

    /**
     * 创建配置好的RemoteOcrClient实例
     * 从系统属性读取超时和重试配置
     *
     * @param apiUrl OCR API地址
     * @return 配置好的RemoteOcrClient实例
     */
    private static RemoteOcrClient createConfiguredOcrClient(String apiUrl) {
        // 从系统属性读取配置（兼容Spring Boot的配置前缀）
        int connectTimeout = Integer.getInteger(
            "pdfutil.pdf.remoteOcrConnectTimeout",
            Integer.getInteger("ocr.connect.timeout", 60000)
        );
        int readTimeout = Integer.getInteger(
            "pdfutil.pdf.remoteOcrReadTimeout",
            Integer.getInteger("ocr.read.timeout", 300000)
        );
        int maxRetries = Integer.getInteger(
            "pdfutil.pdf.remoteOcrMaxRetries",
            Integer.getInteger("ocr.max.retries", 3)
        );
        int retryDelay = Integer.getInteger(
            "pdfutil.pdf.remoteOcrRetryDelay",
            Integer.getInteger("ocr.retry.delay", 2000)
        );

        log.debug("创建RemoteOcrClient - URL: {}, 连接超时: {}ms, 读取超时: {}ms, 最大重试: {}次",
            apiUrl, connectTimeout, readTimeout, maxRetries);

        return new RemoteOcrClient(apiUrl, connectTimeout, readTimeout, maxRetries, retryDelay);
    }

    /**
     * 将图片/PDF转换为双层PDF（主入口）
     *
     * @param inputPath 输入文件路径
     * @param outputPath 输出的双层PDF文件路径
     * @throws Exception 转换过程中可能抛出的异常
     */
    public static void convertToDualLayerPdf(String inputPath, String outputPath) throws Exception {
        convertToDualLayerPdf(inputPath, outputPath, DEFAULT_API_URL);
    }

    /**
     * 将图片/PDF转换为双层PDF（指定API URL）
     *
     * @param inputPath 输入文件路径
     * @param outputPath 输出的双层PDF文件路径
     * @param apiUrl 远程OCR API地址
     * @throws Exception 转换过程中可能抛出的异常
     */
    public static void convertToDualLayerPdf(String inputPath, String outputPath, String apiUrl) throws Exception {
        log.info("开始使用远程OCR生成双层PDF: {} -> {}", inputPath, outputPath);

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
                convertPdfWithRemoteOcr(inputPath, outputPath, apiUrl);
            } else {
                // 图片文件：直接调用OCR API
                convertImageWithRemoteOcr(inputPath, outputPath, apiUrl);
            }

            // 验证输出文件
            if (!outputFile.exists() || outputFile.length() == 0) {
                throw new RuntimeException("转换失败，未生成有效输出文件");
            }

            log.info("远程OCR双层PDF转换成功: {}", outputPath);

        } catch (Exception e) {
            log.error("远程OCR双层PDF转换失败: {}", inputPath, e);
            throw e;
        }
    }

    /**
     * 使用远程OCR将图片转换为双层PDF
     *
     * 新流程：图片 → 临时PDF → OCR API → 双层PDF
     *
     * @param imagePath 图片文件路径
     * @param outputPath 输出PDF路径
     * @param apiUrl OCR API地址
     * @throws Exception 转换失败时抛出异常
     */
    private static void convertImageWithRemoteOcr(String imagePath, String outputPath, String apiUrl) throws Exception {
        String uniqueId = java.util.UUID.randomUUID().toString().replace("-", "");
        String tempDir = System.getProperty("java.io.tmpdir") + "/remote_ocr_img_" + uniqueId;
        new File(tempDir).mkdirs();

        String tempPdfPath = null;

        try {
            // 步骤1: 将图片转换为单页PDF（临时文件）
            log.info("步骤1: 将图片转换为临时PDF");
            File imageFile = new File(imagePath);
            BufferedImage image = ImageIO.read(imageFile);
            int imageWidth = image.getWidth();
            int imageHeight = image.getHeight();
            log.info("图片尺寸: {}x{}", imageWidth, imageHeight);

            tempPdfPath = tempDir + "/temp_" + System.currentTimeMillis() + ".pdf";
            convertImageToPdf(imagePath, tempPdfPath, imageWidth, imageHeight);
            log.info("临时PDF已创建: {}", tempPdfPath);

            // 步骤2: 对临时PDF调用OCR API
            log.info("步骤2: 调用远程OCR API处理临时PDF");
            RemoteOcrClient ocrClient = createConfiguredOcrClient(apiUrl);
            String ocrResult = ocrClient.recognizeImageMultipart(tempPdfPath).toString();
            log.info("OCR API调用成功，响应长度: {} chars", ocrResult.length());

            // 解析OCR结果
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(ocrResult);
            com.fasterxml.jackson.databind.JsonNode pagesNode = root.get("pages");
            int ocrPageCount = (pagesNode != null && pagesNode.isArray()) ? pagesNode.size() : 0;

            if (ocrPageCount == 0) {
                throw new RuntimeException("OCR API未返回任何有效结果，请检查图片文件或OCR服务");
            }

            // 步骤3: 生成双层PDF（使用原始图片，不是临时PDF）
            log.info("步骤3: 使用PDFBox生成双层PDF");

            // 【稳定性修复】添加反射调用异常处理
            try {
                Class<?> adderClass = Class.forName("com.pdfutil.pdf.utils.HocrTextLayerAdder");
                java.lang.reflect.Method method = adderClass.getMethod("createDualLayerPdfFromOcr",
                    String.class, String.class, int.class, int.class, String.class, float.class);

                // 提取第一页的OCR结果（图片只有一页）
                com.fasterxml.jackson.databind.JsonNode pageData = pagesNode.get(0);
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

        } finally {
            // 使用统一的、更健壮的临时目录清理逻辑，支持重试和 Windows 强制删除，防磁盘泄露
            DualLayerPdfConverter.deleteDirectory(new File(tempDir));
        }
    }

    /**
     * 将图片转换为单页PDF（仅包含图片作为背景）
     *
     * 关键：需要将像素尺寸转换为PDF的点尺寸
     * - pdftoppm 使用 -r 300 (300 DPI)
     * - PDF 默认 72 DPI (1点 = 1/72英寸)
     * - 转换公式: PDF点数 = 像素 / 300 * 72
     *
     * @param imagePath 图片文件路径
     * @param pdfPath 输出PDF路径
     * @param width 图片宽度（像素）
     * @param height 图片高度（像素）
     * @throws Exception 转换失败时抛出异常
     */
    private static void convertImageToPdf(String imagePath, String pdfPath, int width, int height) throws Exception {
        log.info("创建临时PDF: 图片尺寸={}x{} (像素)", width, height);

        // DPI转换：像素 → PDF点
        // 统一使用配置的 DPI
        final int IMAGE_DPI = DualLayerPdfConverter.PDF_EXTRACT_DPI;
        final int PDF_DPI = 72;

        float pdfWidth = width * (PDF_DPI / (float) IMAGE_DPI);
        float pdfHeight = height * (PDF_DPI / (float) IMAGE_DPI);

        log.info("转换后PDF尺寸: {}x{} (点, 1/72英寸)", pdfWidth, pdfHeight);

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
            Object appendEnum = appendModeClass.getMethod("valueOf", String.class).invoke(null, "APPEND");

            // 创建PDF文档 - 使用转换后的点尺寸
            Object document = documentClass.getDeclaredConstructor().newInstance();
            Object pageSize = rectClass.getConstructor(float.class, float.class).newInstance(pdfWidth, pdfHeight);
            Object page = pageClass.getConstructor(rectClass).newInstance(pageSize);

            // 添加页面
            documentClass.getMethod("addPage", pageClass).invoke(document, page);

            // 创建内容流
            Object contentStream = contentStreamClass.getConstructor(
                documentClass, pageClass, appendModeClass, boolean.class, boolean.class
            ).newInstance(document, page, appendEnum, true, true);

            // 加载图片
            Object pdImage = imageClass.getMethod("createFromFile", String.class, documentClass).invoke(null, imagePath, document);

            // 获取图片实际尺寸
            int imgWidth = (int) imageClass.getMethod("getWidth").invoke(pdImage);
            int imgHeight = (int) imageClass.getMethod("getHeight").invoke(pdImage);
            log.info("图片实际尺寸: {}x{} (像素)", imgWidth, imgHeight);

            // 绘制图片，填满整个页面
            contentStreamClass.getMethod("drawImage", imageClass, float.class, float.class, float.class, float.class)
                .invoke(contentStream, pdImage, 0f, 0f, pdfWidth, pdfHeight);

            // 关闭内容流
            contentStreamClass.getMethod("close").invoke(contentStream);

            // 保存PDF
            documentClass.getMethod("save", String.class).invoke(document, pdfPath);
            log.info("临时PDF已保存: {}", pdfPath);

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
     * 使用远程OCR将PDF转换为双层PDF
     *
     * 【关键修复】DPI一致性保证：
     * 1. 先用 pdftoppm 提取PDF页面为图片（150 DPI）
     * 2. 对提取的图片进行OCR（确保坐标基于相同的DPI）
     * 3. 使用相同的DPI生成双层PDF
     *
     * 之前的问题：远程OCR直接读取PDF时内部渲染DPI与pdftoppm不一致
     *
     * @param pdfPath PDF文件路径
     * @param outputPath 输出PDF路径
     * @param apiUrl OCR API地址
     * @throws Exception 转换失败时抛出异常
     */
    private static void convertPdfWithRemoteOcr(String pdfPath, String outputPath, String apiUrl) throws Exception {
        log.info("PDF文件处理：使用远程OCR处理PDF");

        String uniqueId = java.util.UUID.randomUUID().toString().replace("-", "");
        String tempDir = System.getProperty("java.io.tmpdir") + "/remote_ocr_pdf_" + uniqueId;
        new File(tempDir).mkdirs();

        List<String> imagePaths = new ArrayList<>();
        List<String> singlePagePdfs = new ArrayList<>();

        try {
            // 【关键修复】步骤1: 先提取PDF页面为图片
            // 这确保后续OCR和PDF生成使用完全相同的图片和DPI
            log.info("步骤1: 提取PDF页面为图片 ({} DPI)", DualLayerPdfConverter.PDF_EXTRACT_DPI);
            imagePaths = convertPdfToImages(pdfPath, tempDir);
            int pdfPageCount = imagePaths.size();
            log.info("共提取 {} 页图片", pdfPageCount);

            if (pdfPageCount == 0) {
                throw new RuntimeException("PDF页面提取失败，未生成任何图片");
            }

            // 步骤2: 对提取的图片进行OCR（确保坐标与图片像素完全一致）
            log.info("步骤2: 对提取的图片调用远程OCR API");
            RemoteOcrClient ocrClient = createConfiguredOcrClient(apiUrl);

            // 构建多页OCR结果
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.node.ArrayNode allPagesNode = mapper.createArrayNode();

            for (int i = 0; i < imagePaths.size(); i++) {
                String imagePath = imagePaths.get(i);
                log.info("OCR识别第 {}/{} 页: {}", i + 1, imagePaths.size(), imagePath);

                String pageResult = ocrClient.recognizeImageMultipart(imagePath).toString();
                com.fasterxml.jackson.databind.JsonNode pageData = mapper.readTree(pageResult).get("pages");

                if (pageData != null && pageData.isArray() && pageData.size() > 0) {
                    // 获取第一页的结果（单张图片只有一页）
                    allPagesNode.add(pageData.get(0));
                } else {
                    // 空页面，添加空数组
                    allPagesNode.add(mapper.createArrayNode());
                }
            }

            // 构建完整的OCR结果
            com.fasterxml.jackson.databind.node.ObjectNode fullResult = mapper.createObjectNode();
            fullResult.set("pages", allPagesNode);
            fullResult.put("page_count", allPagesNode.size());

            com.fasterxml.jackson.databind.JsonNode pagesNode = fullResult.get("pages");
            int ocrPageCount = pagesNode.size();
            log.info("远程OCR完成，共识别 {} 页", ocrPageCount);

            // 页数应该完全匹配，因为我们是先提取图片再OCR
            int processPages = ocrPageCount;
            if (ocrPageCount != pdfPageCount) {
                log.warn("警告：OCR返回的页数({})与提取的图片页数({})不匹配，处理 {} 页",
                    ocrPageCount, pdfPageCount, processPages);
            }

            if (processPages == 0) {
                throw new RuntimeException("OCR未返回任何有效结果，请检查PDF文件或OCR服务");
            }

            // 步骤3: 为每一页生成双层PDF
            log.info("步骤3: 为每一页生成双层PDF");

            // 【稳定性修复】添加反射调用异常处理
            Class<?> adderClass;
            java.lang.reflect.Method method;
            try {
                adderClass = Class.forName("com.pdfutil.pdf.utils.HocrTextLayerAdder");
                method = adderClass.getMethod("createDualLayerPdfFromOcr",
                    String.class, String.class, int.class, int.class, String.class, float.class);
            } catch (ClassNotFoundException e) {
                log.error("找不到 HocrTextLayerAdder 类，请确保 pdf 模块已正确部署", e);
                throw new RuntimeException("双层PDF生成失败：缺少必要的依赖类 HocrTextLayerAdder", e);
            } catch (NoSuchMethodException e) {
                log.error("HocrTextLayerAdder 类缺少 createDualLayerPdfFromOcr 方法", e);
                throw new RuntimeException("双层PDF生成失败：依赖类方法签名不匹配", e);
            }

            try {
                for (int i = 0; i < processPages; i++) {
                    String imagePath = imagePaths.get(i);
                    log.info("处理第 {} 页", i + 1);

                    // 获取图片尺寸
                    BufferedImage image = ImageIO.read(new File(imagePath));
                    int imageWidth = image.getWidth();
                    int imageHeight = image.getHeight();

                    // 提取第i页的OCR结果
                    com.fasterxml.jackson.databind.JsonNode pageData = pagesNode.get(i);
                    String pageOcrResult = "{\"pages\": [" + mapper.writeValueAsString(pageData) + "]}";

                    // 生成单页双层PDF
                    String singlePdfPath = tempDir + "/page_" + i + ".pdf";
                    // 【关键】PDF提取的图片使用配置的 DPI，与提取时一致
                    method.invoke(null, imagePath, pageOcrResult, imageWidth, imageHeight, singlePdfPath, (float)DualLayerPdfConverter.PDF_EXTRACT_DPI);
                    singlePagePdfs.add(singlePdfPath);
                }
            } catch (IllegalAccessException e) {
                log.error("无法访问 HocrTextLayerAdder 方法", e);
                throw new RuntimeException("双层PDF生成失败：权限不足", e);
            } catch (java.lang.reflect.InvocationTargetException e) {
                log.error("调用 HocrTextLayerAdder 方法失败", e);
                throw new RuntimeException("双层PDF生成失败：" + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()), e);
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
     * 将PDF转换为图片（提取每一页）
     * 【性能优化】使用 pdf2image 库替代 pdftoppm，支持多线程处理
     *
     * 统一使用配置的 DPI，与双层PDF生成时的DPI保持一致
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

            int exitCode = waitForProcess(process, 120, "执行命令");

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

        boolean finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            // 超时，强制终止进程
            log.warn("进程超时（{}秒），强制终止: {}", timeoutSeconds, operation);
            process.destroyForcibly();
            // 等待进程完全终止
            try {
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("进程执行超时（超过" + timeoutSeconds + "秒）: " + operation);
        }

        return process.exitValue();
    }

    /**
     * 获取OCRmyPDF路径
     */
    private static String getOcrmypdfPath() {
        String path = System.getProperty("ocrmypdf.path");
        return path != null && !path.isEmpty() ? path : "python -m ocrmypdf";
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
        return fileName.substring(lastDotIndex + 1);
    }
}
