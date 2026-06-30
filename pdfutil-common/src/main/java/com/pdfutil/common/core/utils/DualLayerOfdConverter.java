package com.pdfutil.common.core.utils;

import org.ofdrw.converter.export.ImageExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 双层 OFD 转换工具 - OFDRW 方案
 *
 * @author Alika
 * @date 2026-06-09
 */
public class DualLayerOfdConverter {
    private static final Logger log = LoggerFactory.getLogger(DualLayerOfdConverter.class);

    static {
        try {
            javax.imageio.ImageIO.scanForPlugins();
        } catch (Throwable e) {
            log.warn("Failed to scan ImageIO plugins in DualLayerOfdConverter: {}", e.getMessage());
        }
    }

    private static final int OFD_EXTRACT_DPI = 200; // 使用 200 DPI 平衡质量与性能
    private static final int PDF_EXTRACT_DPI = 200; // PDF 转 OFD 时也使用 200 DPI

    /**
     * 将 OFD 或图片转换为单层 OFD（无OCR文字层）
     *
     * @param inputPath  输入文件路径
     * @param outputPath 输出单层 OFD 路径
     */
    public static void convertToSingleLayerOfd(String inputPath, String outputPath) throws Exception {
        log.info("开始单层OFD转换: {} -> {}", inputPath, outputPath);

        if (inputPath == null || inputPath.trim().isEmpty()) {
            throw new IllegalArgumentException("输入文件路径不能为空");
        }
        if (outputPath == null || outputPath.trim().isEmpty()) {
            throw new IllegalArgumentException("输出文件路径不能为空");
        }

        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            throw new FileNotFoundException("输入文件不存在: " + inputPath + " (绝对路径: " + inputFile.getAbsolutePath() + ")");
        }
        if (!inputFile.canRead()) {
            throw new FileNotFoundException("无法读取输入文件，请检查文件权限: " + inputPath);
        }

        // 确保输出目录存在
        File outputFile = new File(outputPath);
        File outputDir = outputFile.getParentFile();
        if (outputDir != null && !outputDir.exists()) {
            outputDir.mkdirs();
        }

        // 创建唯一的临时目录存放拆分出的图片（使用UUID确保唯一性）
        String uniqueId = java.util.UUID.randomUUID().toString().replace("-", "");
        String tempDir = System.getProperty("java.io.tmpdir") + "/ofd_single_" + uniqueId;
        new File(tempDir).mkdirs();

        try {
            List<String> imagePaths = new ArrayList<>();
            String ext = getFileExtension(inputPath).toLowerCase();
            boolean isOfd = "ofd".equals(ext);
            boolean isPdf = "pdf".equals(ext);

            float sourceDpi = OFD_EXTRACT_DPI; // 默认使用 OFD 拆图 DPI

            if (isPdf) {
                log.info("输入为 PDF，开始拆分为图片, DPI={}", PDF_EXTRACT_DPI);
                sourceDpi = PDF_EXTRACT_DPI;
                // 使用 pdf2image 将 PDF 转换为图片
                convertPdfToImages(inputPath, tempDir, imagePaths);
                log.info("PDF 拆分完成，共得到 {} 页图片", imagePaths.size());
            } else if (isOfd) {
                log.info("输入为 OFD，开始拆分为图片, DPI={}", OFD_EXTRACT_DPI);
                // 拆分页面为图片
                double ppm = OFD_EXTRACT_DPI / 25.4;
                try (ImageExporter exporter = new ImageExporter(inputFile.toPath(), Paths.get(tempDir), "PNG", ppm)) {
                    exporter.export();
                }

                // 读取导出的页面图片（从 0 开始自增）
                int pageIndex = 0;
                while (true) {
                    File pageImg = new File(tempDir, pageIndex + ".png");
                    if (pageImg.exists()) {
                        imagePaths.add(pageImg.getAbsolutePath());
                        pageIndex++;
                    } else {
                        break;
                    }
                }
                log.info("OFD 拆分完成，共得到 {} 页图片", imagePaths.size());
            } else if (isImageFile(inputPath)) {
                log.info("输入为图片，直接使用进行单层 OFD 转化");
                imagePaths.add(inputPath);

                // 读取图片以获取像素宽高，用于推断 DPI
                int imgW = 0;
                int imgH = 0;
                try {
                    java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new File(inputPath));
                    if (img != null) {
                        imgW = img.getWidth();
                        imgH = img.getHeight();
                    }
                } catch (Exception e) {
                    log.warn("无法读取图片尺寸进行 DPI 推断: {}", inputPath, e);
                }

                // 检测/推断 DPI
                sourceDpi = DualLayerPdfConverter.detectImageDpi(inputPath, imgW, imgH);
                log.info("图片输入，检测/推断出的 DPI 为: {}", sourceDpi);
            } else {
                throw new IllegalArgumentException("不支持的输入文件类型: " + ext + "。仅支持 PDF、OFD 和图片文件");
            }

            if (imagePaths.isEmpty()) {
                throw new RuntimeException("无可用页面图片进行转换");
            }

            // 调用 OfdTextLayerAdder 合成单层 OFD（无文字层）
            log.info("调用 OfdTextLayerAdder 生成单层OFD, sourceDpi=" + sourceDpi);
            try {
                OfdTextLayerAdder.createSingleLayerOfdFromImages(
                    imagePaths.toArray(new String[0]),
                    outputPath,
                    sourceDpi
                );
            } catch (Exception e) {
                log.error("调用 OfdTextLayerAdder 失败", e);
                String errorMessage = e.getMessage();
                if (errorMessage == null || errorMessage.isEmpty()) {
                    errorMessage = e.toString();
                }
                Throwable cause = e.getCause();
                if (cause != null && cause.getMessage() != null && !cause.getMessage().isEmpty()) {
                    errorMessage = cause.getMessage();
                }
                throw new RuntimeException("单层OFD合成过程失败：" + errorMessage, e);
            }

            log.info("单层 OFD 转换成功: {}", outputPath);

        } finally {
            // 清理临时目录
            DualLayerPdfConverter.deleteDirectory(new File(tempDir));
        }
    }

    /**
     * 将 OFD 或图片转换为双层 OFD
     *
     * @param inputPath  输入文件路径
     * @param outputPath 输出双层 OFD 路径
     */
    public static void convertToDualLayerOfd(String inputPath, String outputPath) throws Exception {
        log.info("开始双层OFD转换: {} -> {}", inputPath, outputPath);

        if (inputPath == null || inputPath.trim().isEmpty()) {
            throw new IllegalArgumentException("输入文件路径不能为空");
        }
        if (outputPath == null || outputPath.trim().isEmpty()) {
            throw new IllegalArgumentException("输出文件路径不能为空");
        }

        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            throw new FileNotFoundException("输入文件不存在: " + inputPath + " (绝对路径: " + inputFile.getAbsolutePath() + ")");
        }
        if (!inputFile.canRead()) {
            throw new FileNotFoundException("无法读取输入文件，请检查文件权限: " + inputPath);
        }

        // 确保输出目录存在
        File outputFile = new File(outputPath);
        File outputDir = outputFile.getParentFile();
        if (outputDir != null && !outputDir.exists()) {
            outputDir.mkdirs();
        }

        // 创建唯一的临时目录存放拆分出的图片（使用UUID确保唯一性）
        String uniqueId = java.util.UUID.randomUUID().toString().replace("-", "");
        String tempDir = System.getProperty("java.io.tmpdir") + "/ofd_convert_" + uniqueId;
        new File(tempDir).mkdirs();

        try {
            List<String> imagePaths = new ArrayList<>();
            String ext = getFileExtension(inputPath).toLowerCase();
            boolean isOfd = "ofd".equals(ext);
            boolean isPdf = "pdf".equals(ext);

            float sourceDpi = OFD_EXTRACT_DPI; // 默认使用 OFD 拆图 DPI
            boolean hasExistingText = false;
            String ocrResultStr = null;

            if (isPdf) {
                log.info("输入为 PDF，开始拆分为图片, DPI={}", PDF_EXTRACT_DPI);
                sourceDpi = PDF_EXTRACT_DPI;
                // 使用 pdf2image 将 PDF 转换为图片
                convertPdfToImages(inputPath, tempDir, imagePaths);
                log.info("PDF 拆分完成，共得到 {} 页图片", imagePaths.size());

                // 检查并提取已有文本层，避免 OCR
                hasExistingText = checkPdfHasTextLayer(inputPath);
                if (hasExistingText) {
                    log.info("检测到 PDF 原本含有可搜索文本层，跳过 OCR 识别，直接提取文本层坐标");
                    try {
                        ocrResultStr = extractPdfTextLayerToJson(inputPath, sourceDpi);
                        log.info("文本层坐标提取成功");
                    } catch (Exception e) {
                        log.error("直接提取 PDF 文本层坐标失败，将降级为 OCR 识别", e);
                        hasExistingText = false;
                    }
                }
            } else if (isOfd) {
                log.info("输入为 OFD，开始拆分为图片, DPI={}", OFD_EXTRACT_DPI);
                // 1. 拆分页面为图片
                double ppm = OFD_EXTRACT_DPI / 25.4;
                try (ImageExporter exporter = new ImageExporter(inputFile.toPath(), Paths.get(tempDir), "PNG", ppm)) {
                    exporter.export();
                }

                // 2. 依次读取导出的页面图片（从 0 开始自增）
                int pageIndex = 0;
                while (true) {
                    File pageImg = new File(tempDir, pageIndex + ".png");
                    if (pageImg.exists()) {
                        imagePaths.add(pageImg.getAbsolutePath());
                        pageIndex++;
                    } else {
                        break;
                    }
                }
                log.info("OFD 拆分完成，共得到 {} 页图片", imagePaths.size());
            } else if (isImageFile(inputPath)) {
                log.info("输入为图片，直接使用进行双层 OFD 转化");
                imagePaths.add(inputPath);

                // 读取图片以获取像素宽高，用于推断 DPI
                int imgW = 0;
                int imgH = 0;
                try {
                    java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new File(inputPath));
                    if (img != null) {
                        imgW = img.getWidth();
                        imgH = img.getHeight();
                    }
                } catch (Exception e) {
                    log.warn("无法读取图片尺寸进行 DPI 推断: {}", inputPath, e);
                }

                // 检测/推断 DPI
                sourceDpi = DualLayerPdfConverter.detectImageDpi(inputPath, imgW, imgH);
                log.info("图片输入，检测/推断出的 DPI 为: {}", sourceDpi);
            } else {
                throw new IllegalArgumentException("不支持的输入文件类型: " + ext + "。仅支持 PDF、OFD 和图片文件");
            }

            if (imagePaths.isEmpty()) {
                throw new RuntimeException("无可用页面图片进行转换");
            }

            // 3. 调用本地 OCR 识别或直接解析已有文本层
            com.fasterxml.jackson.databind.JsonNode ocrResult;
            if (hasExistingText && ocrResultStr != null) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                ocrResult = mapper.readTree(ocrResultStr);
            } else {
                log.info("调用本地 OCR 并发识别图片，页面总数={}", imagePaths.size());
                LocalOcrClient ocrClient = new LocalOcrClient();
                ocrResult = recognizeImagesWithConcurrency(ocrClient, imagePaths);
            }

            // 4. 调用 OfdTextLayerAdder 合成双层 OFD
            log.info("调用 OfdTextLayerAdder 写入透明文字层，生成双层OFD, sourceDpi=" + sourceDpi);
            try {
                OfdTextLayerAdder.createDualLayerOfdFromOcr(
                    imagePaths.toArray(new String[0]),
                    ocrResult.toString(),
                    outputPath,
                    sourceDpi
                );
            } catch (Exception e) {
                log.error("调用 OfdTextLayerAdder 失败", e);
                // Enhance error message: include exception type and message if cause is null
                String errorMessage = e.getMessage();
                if (errorMessage == null || errorMessage.isEmpty()) {
                    errorMessage = e.toString();
                }
                Throwable cause = e.getCause();
                if (cause != null && cause.getMessage() != null && !cause.getMessage().isEmpty()) {
                    errorMessage = cause.getMessage();
                }
                throw new RuntimeException("双层OFD合成过程失败：" + errorMessage, e);
            }


            log.info("双层 OFD 转换成功: {}", outputPath);

        } finally {
            // 清理临时目录
            DualLayerPdfConverter.deleteDirectory(new File(tempDir));
        }
    }

    private static String getFileExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "";
        }
        int lastDotIndex = fileName.lastIndexOf(".");
        if (lastDotIndex == -1 || lastDotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(lastDotIndex + 1);
    }

    private static boolean isImageFile(String path) {
        String ext = getFileExtension(path).toLowerCase();
        return "jpg".equals(ext) || "jpeg".equals(ext) || "png".equals(ext) || "bmp".equals(ext) || "tif".equals(ext) || "tiff".equals(ext);
    }

    /**
     * 使用 pdf2image 将 PDF 转换为图片
     *
     * @param pdfPath PDF 文件路径
     * @param outputDir 输出目录
     * @param imagePaths 输出图片路径列表（会被填充）
     * @throws Exception 转换失败时抛出异常
     */
    private static void convertPdfToImages(String pdfPath, String outputDir, List<String> imagePaths) throws Exception {
        log.info("开始 PDF 转图片: {} -> {}", pdfPath, outputDir);

        // 检查输入文件
        File pdfFile = new File(pdfPath);
        if (!pdfFile.exists()) {
            throw new FileNotFoundException("PDF文件不存在: " + pdfPath);
        }
        if (!pdfFile.canRead()) {
            throw new IOException("无法读取PDF文件，请检查文件权限: " + pdfPath);
        }

        String pythonPath = getPythonPath();

        // 检查是否安装了 pdf2image
        String[] checkCmd = {pythonPath, "-c", "import pdf2image; print('OK')"};
        ProcessBuilder checkPb = new ProcessBuilder(checkCmd);
        checkPb.redirectErrorStream(true);

        try {
            Process checkProcess = checkPb.start();
            int exitCode = waitForProcess(checkProcess, 10, "检查pdf2image安装");
            String output = readStream(checkProcess.getInputStream());

            if (exitCode != 0 || !output.contains("OK")) {
                throw new IOException("pdf2image 未安装。请运行: pip install pdf2image");
            }
        } catch (Exception e) {
            throw new IOException("检查 pdf2image 失败: " + e.getMessage());
        }

        // 路径处理：统一使用正斜杠（跨平台兼容）
        String normalizedPdfPath = pdfPath.replace("\\", "/");

        // 构建 pdf2image 命令
        List<String> command = new ArrayList<>();
        command.add(pythonPath);
        command.add("-c");
        command.add(String.format(
            "import pdf2image; " +
            "pdf2image.convert_from_path('%s', dpi=%d, output_folder='%s', fmt='png', paths_only=True)",
            normalizedPdfPath, PDF_EXTRACT_DPI, outputDir.replace("\\", "/")
        ));

        log.debug("PDF转图片命令: {}", command);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process = null;
        BufferedReader br = null;

        try {
            process = pb.start();
            br = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));

            String line;
            StringBuilder output = new StringBuilder();
            while ((line = br.readLine()) != null) {
                output.append(line).append("\n");
            }

            int exitCode = waitForProcess(process, 120, "PDF转图片");
            if (exitCode != 0) {
                log.error("PDF转图片失败，退出码: {}, 输出: {}", exitCode, output);
                throw new RuntimeException("PDF转图片失败，退出码: " + exitCode + ", 错误信息: " + output);
            }

            // 解析输出中的图片路径
            String[] lines = output.toString().split("[\\[\\]\\']");
            for (String lineItem : lines) {
                lineItem = lineItem.trim();
                if (lineItem.startsWith("'") && lineItem.endsWith("'")) {
                    String imagePath = lineItem.substring(1, lineItem.length() - 1);
                    if (!imagePath.isEmpty()) {
                        imagePaths.add(imagePath);
                    }
                }
            }

            // 如果没有解析到路径，尝试扫描目录
            if (imagePaths.isEmpty()) {
                File dir = new File(outputDir);
                File[] files = dir.listFiles((d, name) -> name.endsWith(".png"));
                if (files != null) {
                    for (File file : files) {
                        imagePaths.add(file.getAbsolutePath());
                    }
                }
            }

            if (imagePaths.isEmpty()) {
                throw new RuntimeException("PDF转图片失败：未能生成任何图片文件，请检查PDF文件是否损坏");
            }

            log.info("PDF 转 {} 页图片成功", imagePaths.size());

        } finally {
            if (br != null) {
                try { br.close(); } catch (IOException e) { /* ignore */ }
            }
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * 获取 Python 路径
     */
    private static String getPythonPath() throws IOException {
        // 1. 检查系统属性
        String pythonPath = System.getProperty("python.path");
        if (pythonPath != null && !pythonPath.isEmpty()) {
            return pythonPath;
        }

        // 2. 检查环境变量
        pythonPath = System.getenv("PYTHON_PATH");
        if (pythonPath != null && !pythonPath.isEmpty()) {
            return pythonPath;
        }

        // 3. 尝试常见的 Python 命令
        String[] pythonCommands = {"python", "python3"};
        for (String cmd : pythonCommands) {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd, "--version");
                pb.redirectErrorStream(true);
                Process process = pb.start();
                int exitCode = waitForProcess(process, 5, "检查Python版本");
                if (exitCode == 0) {
                    log.info("找到 Python: {}", cmd);
                    return cmd;
                }
            } catch (Exception e) {
                // 继续尝试下一个
            }
        }

        throw new IOException("未找到 Python。请设置 PYTHON_PATH 环境变量或 python.path 系统属性");
    }

    /**
     * 读取进程输出流
     */
    private static String readStream(java.io.InputStream stream) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
        StringBuilder output = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            output.append(line).append("\n");
        }
        return output.toString();
    }



    /**
     * 并发OCR识别多张图片，利用 max-concurrent 配置
     *
     * @param ocrClient OCR客户端
     * @param imagePaths 所有图片路径
     * @return 合并的OCR结果
     * @throws Exception 识别失败时抛出异常
     */
    private static com.fasterxml.jackson.databind.JsonNode recognizeImagesWithConcurrency(
            LocalOcrClient ocrClient, List<String> imagePaths) throws Exception {

        int totalImages = imagePaths.size();
        int maxConcurrent = Integer.getInteger("pdfutil.pdf.localOcrMaxConcurrent", 4);

        log.info("启用并发OCR识别: 总页数={}, 最大并发数={}", totalImages, maxConcurrent);

        // 如果图片数量少，直接批量处理
        if (totalImages <= maxConcurrent) {
            log.info("图片数量较少({})，直接批量处理", totalImages);
            return ocrClient.recognizeImages(imagePaths);
        }

        // 分批处理：每批大小 ≈ maxConcurrent，但不超过10张/批（避免单批时间过长）
        int batchSize = Math.min(maxConcurrent, Math.max(1, totalImages / 4));
        int batchCount = (totalImages + batchSize - 1) / batchSize;

        log.info("分批策略: 每批{}张图片，共{}批", batchSize, batchCount);

        // 创建专用线程池以控制并发数并避免干扰 ForkJoinPool.commonPool()
        int poolSize = Math.max(1, Math.min(maxConcurrent, batchCount));
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(
                poolSize,
                new java.util.concurrent.ThreadFactory() {
                    private final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger(1);
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread thread = new Thread(r);
                        thread.setName("ofd-ocr-pool-" + count.getAndIncrement());
                        thread.setDaemon(true);
                        return thread;
                    }
                }
        );

        try {
            // 使用并发工具分批处理
            java.util.concurrent.CompletableFuture<com.fasterxml.jackson.databind.JsonNode>[] futures =
                new java.util.concurrent.CompletableFuture[batchCount];

            for (int i = 0; i < batchCount; i++) {
                int startIdx = i * batchSize;
                int endIdx = Math.min(startIdx + batchSize, totalImages);
                List<String> batchImages = imagePaths.subList(startIdx, endIdx);
                int batchNum = i + 1;

                log.info("准备第{}/{}批: 图片{}-{}", batchNum, batchCount, startIdx + 1, endIdx);

                futures[i] = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        long startTime = System.currentTimeMillis();
                        log.info("开始处理第{}批: {}张图片", batchNum, batchImages.size());
                        com.fasterxml.jackson.databind.JsonNode batchResult = ocrClient.recognizeImages(batchImages);
                        long duration = System.currentTimeMillis() - startTime;
                        log.info("第{}批处理完成: 耗时{}ms", batchNum, duration);
                        return batchResult;
                    } catch (Exception e) {
                        log.error("第{}批处理失败: {}", batchNum, e.getMessage(), e);
                        throw new RuntimeException("第" + batchNum + "批OCR识别失败: " + e.getMessage(), e);
                    }
                }, executor);
            }

            // 等待所有批次完成并合并结果
            log.info("等待所有{}批OCR结果...", batchCount);

            // 收集所有批次的异常信息
            java.util.List<Exception> batchExceptions = new java.util.ArrayList<>();
            com.fasterxml.jackson.databind.JsonNode[] batchResults = new com.fasterxml.jackson.databind.JsonNode[batchCount];

            for (int idx = 0; idx < batchCount; idx++) {
                try {
                    batchResults[idx] = futures[idx].get();
                } catch (Exception e) {
                    String errorMsg = "获取第" + (idx + 1) + "批结果失败: " + e.getMessage();
                    log.error(errorMsg, e);
                    batchExceptions.add(new RuntimeException(errorMsg, e));
                }
            }

            // 如果有任何批次失败，抛出异常
            if (!batchExceptions.isEmpty()) {
                throw new RuntimeException(
                    "OCR识别失败: " + batchExceptions.size() + "个批次处理失败, " +
                    "失败批次: " + batchExceptions.stream()
                        .map(e -> e.getMessage().substring(0, Math.min(50, e.getMessage().length())))
                        .collect(java.util.stream.Collectors.joining("; ")),
                    batchExceptions.get(0)
                );
            }

            com.fasterxml.jackson.databind.JsonNode[] results = batchResults;

            // 合并所有批次的OCR结果
            return mergeOcrResults(results, totalImages);
        } finally {
            executor.shutdown();
        }
    }

    /**
     * 合并多个批次的OCR结果
     *
     * @param results 各批次的OCR结果
     * @param totalImages 总图片数
     * @return 合并后的OCR结果
     */
    private static com.fasterxml.jackson.databind.JsonNode mergeOcrResults(
            com.fasterxml.jackson.databind.JsonNode[] results, int totalImages) {

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ArrayNode mergedPages = mapper.createArrayNode();
        int totalRecognized = 0;

        for (com.fasterxml.jackson.databind.JsonNode batchResult : results) {
            if (batchResult != null && batchResult.has("pages")) {
                com.fasterxml.jackson.databind.JsonNode batchPages = batchResult.get("pages");
                if (batchPages.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode page : batchPages) {
                        mergedPages.add(page);
                    }
                    totalRecognized += batchPages.size();
                }
            }
        }

        // 构建合并后的结果
        com.fasterxml.jackson.databind.node.ObjectNode mergedResult = mapper.createObjectNode();
        mergedResult.set("pages", mergedPages);
        mergedResult.put("page_count", totalRecognized);

        log.info("合并OCR结果完成: 总页数={}, 识别页数={}", totalImages, totalRecognized);

        if (totalRecognized != totalImages) {
            log.warn("识别页数({})与输入页数({})不匹配", totalRecognized, totalImages);
        }

        return mergedResult;
    }

    /**
     * 检查 PDF 是否包含可搜索文本层
     *
     * @param pdfPath PDF 文件路径
     * @return true 表示包含可搜索文本
     */
    public static boolean checkPdfHasTextLayer(String pdfPath) {
        try {
            org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.pdmodel.PDDocument.load(new File(pdfPath));
            try {
                int pageCount = document.getNumberOfPages();
                if (pageCount == 0) {
                    return false;
                }
                org.apache.pdfbox.text.PDFTextStripper textStripper = new org.apache.pdfbox.text.PDFTextStripper();
                int pagesToCheck = Math.min(3, pageCount); // 检查前 3 页
                int totalTextLength = 0;
                for (int i = 1; i <= pagesToCheck; i++) {
                    textStripper.setStartPage(i);
                    textStripper.setEndPage(i);
                    String text = textStripper.getText(document);
                    totalTextLength += text != null ? text.trim().length() : 0;
                }
                log.info("PDF 文本层检查: 页数={}, 前 {} 页文本长度={}", pageCount, pagesToCheck, totalTextLength);
                return totalTextLength > 10; // 文本长度大于 10 则认为有文本层
            } finally {
                document.close();
            }
        } catch (Exception e) {
            log.warn("检查 PDF 文本层失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 直接从 PDF 提取文本层坐标并生成 OCR 格式的 JSON
     *
     * @param pdfPath PDF 文件路径
     * @param dpi     DPI 分辨率，用于计算像素坐标
     * @return 标准格式的 JSON 字符串
     */
    public static String extractPdfTextLayerToJson(String pdfPath, float dpi) throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.pdmodel.PDDocument.load(new File(pdfPath))) {
            PdfTextStripperWithCoordinates stripper = new PdfTextStripperWithCoordinates(dpi);
            List<List<java.util.Map<String, Object>>> pagesData = stripper.getPagesData(document);

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode root = mapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode pagesArray = mapper.createArrayNode();

            for (List<java.util.Map<String, Object>> page : pagesData) {
                com.fasterxml.jackson.databind.node.ArrayNode pageArray = mapper.createArrayNode();
                for (java.util.Map<String, Object> item : page) {
                    com.fasterxml.jackson.databind.node.ObjectNode itemNode = mapper.createObjectNode();
                    itemNode.put("text", (String) item.get("text"));
                    itemNode.put("confidence", (Double) item.get("confidence"));

                    com.fasterxml.jackson.databind.node.ArrayNode regionArray = mapper.createArrayNode();
                    List<List<Integer>> region = (List<List<Integer>>) item.get("text_region");
                    for (List<Integer> pt : region) {
                        com.fasterxml.jackson.databind.node.ArrayNode ptArray = mapper.createArrayNode();
                        ptArray.add(pt.get(0));
                        ptArray.add(pt.get(1));
                        regionArray.add(ptArray);
                    }
                    itemNode.set("text_region", regionArray);
                    pageArray.add(itemNode);
                }
                pagesArray.add(pageArray);
            }

            root.set("pages", pagesArray);
            root.put("page_count", pagesData.size());

            return mapper.writeValueAsString(root);
        }
    }

    /**
     * 自定义 PDF 文本提取器，用于捕获文本内容及其相对于指定 DPI 渲染图的像素坐标
     */
    private static class PdfTextStripperWithCoordinates extends org.apache.pdfbox.text.PDFTextStripper {
        private final List<List<java.util.Map<String, Object>>> pagesData = new java.util.ArrayList<>();
        private List<java.util.Map<String, Object>> currentPageData = null;
        private final float dpi;

        public PdfTextStripperWithCoordinates(float dpi) throws java.io.IOException {
            super();
            this.dpi = dpi;
            setSortByPosition(true); // 保证位置从上到下、从左到右排序
        }

        public List<List<java.util.Map<String, Object>>> getPagesData(org.apache.pdfbox.pdmodel.PDDocument document) throws java.io.IOException {
            pagesData.clear();
            writeText(document, new java.io.StringWriter());
            return pagesData;
        }

        @Override
        protected void startPage(org.apache.pdfbox.pdmodel.PDPage page) throws java.io.IOException {
            currentPageData = new java.util.ArrayList<>();
            pagesData.add(currentPageData);
            super.startPage(page);
        }

        @Override
        protected void writeString(String string, List<org.apache.pdfbox.text.TextPosition> textPositions) throws java.io.IOException {
            if (textPositions == null || textPositions.isEmpty() || string.trim().isEmpty()) {
                return;
            }

            float scale = dpi / 72.0f;
            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;

            StringBuilder sb = new StringBuilder();
            for (org.apache.pdfbox.text.TextPosition tp : textPositions) {
                sb.append(tp.getUnicode());

                float x1 = tp.getXDirAdj() * scale;
                // tp.getYDirAdj() 是基线，往上（在Y轴向下增长的空间里是减）是 top
                float y1 = (tp.getYDirAdj() - tp.getHeightDir()) * scale;
                float x2 = (tp.getXDirAdj() + tp.getWidthDirAdj()) * scale;
                float y2 = tp.getYDirAdj() * scale;

                minX = Math.min(minX, x1);
                minY = Math.min(minY, y1);
                maxX = Math.max(maxX, x2);
                maxY = Math.max(maxY, y2);
            }

            String txt = sb.toString().trim();
            if (txt.isEmpty()) {
                return;
            }

            // 限制坐标不为负数
            int ix1 = Math.max(0, Math.round(minX));
            int iy1 = Math.max(0, Math.round(minY));
            int ix2 = Math.max(0, Math.round(maxX));
            int iy2 = Math.max(0, Math.round(maxY));

            // 构建 OCR 格式区域坐标 [[x1, y1], [x2, y1], [x2, y2], [x1, y2]]
            List<List<Integer>> textRegion = new java.util.ArrayList<>();
            textRegion.add(java.util.Arrays.asList(ix1, iy1));
            textRegion.add(java.util.Arrays.asList(ix2, iy1));
            textRegion.add(java.util.Arrays.asList(ix2, iy2));
            textRegion.add(java.util.Arrays.asList(ix1, iy2));

            java.util.Map<String, Object> item = new java.util.HashMap<>();
            item.put("text", txt);
            item.put("text_region", textRegion);
            item.put("confidence", 1.0);

            if (currentPageData != null) {
                currentPageData.add(item);
            }
        }
    }

    /**
     * 将多个文件合并为一个双层OFD
     *
     * @param inputPaths  输入文件路径数组（支持图片、PDF、OFD）
     * @param outputPath 输出合并的双层 OFD 路径
     */
    public static void convertToMergedOfd(String[] inputPaths, String outputPath) throws Exception {
        log.info("开始合并OFD转换: 输入文件数={} -> {}", inputPaths.length, outputPath);

        if (inputPaths == null || inputPaths.length == 0) {
            throw new IllegalArgumentException("输入文件路径不能为空");
        }
        if (outputPath == null || outputPath.trim().isEmpty()) {
            throw new IllegalArgumentException("输出文件路径不能为空");
        }

        // 确保输出目录存在
        File outputFile = new File(outputPath);
        File outputDir = outputFile.getParentFile();
        if (outputDir != null && !outputDir.exists()) {
            outputDir.mkdirs();
        }

        // 创建唯一的临时目录存放拆分出的图片（使用UUID确保唯一性）
        String uniqueId = java.util.UUID.randomUUID().toString().replace("-", "");
        String tempDir = System.getProperty("java.io.tmpdir") + "/ofd_merge_" + uniqueId;
        new File(tempDir).mkdirs();

        try {
            List<String> allImagePaths = new ArrayList<>();
            List<Float> allDpis = new ArrayList<>();
            List<Boolean> hasExistingTextList = new ArrayList<>();
            List<com.fasterxml.jackson.databind.JsonNode> pageTextLayers = new ArrayList<>();

            // 第一步：将所有输入文件转换为图片
            int fileIndex = 0;
            for (String inputPath : inputPaths) {
                File inputFile = new File(inputPath);
                if (!inputFile.exists()) {
                    log.warn("输入文件不存在，跳过: {}", inputPath);
                    continue;
                }
                if (!inputFile.canRead()) {
                    log.warn("无法读取输入文件，跳过: {}", inputPath);
                    continue;
                }

                String ext = getFileExtension(inputPath).toLowerCase();
                boolean isOfd = "ofd".equals(ext);
                boolean isPdf = "pdf".equals(ext);
                boolean isImage = isImageFile(inputPath);

                float sourceDpi = OFD_EXTRACT_DPI;
                boolean hasExistingText = false;
                String ocrResultStr = null;

                        if (isPdf) {
                    log.info("处理文件 {}/{}: PDF -> 图片, DPI={}", fileIndex + 1, inputPaths.length, PDF_EXTRACT_DPI);
                    sourceDpi = PDF_EXTRACT_DPI;

                    // 检查并提取已有文本层，避免 OCR
                    hasExistingText = checkPdfHasTextLayer(inputPath);
                    com.fasterxml.jackson.databind.JsonNode pdfJsonRoot = null;
                    if (hasExistingText) {
                        log.info("检测到 PDF 原本含有可搜索文本层，跳过 OCR 识别，直接提取文本层坐标");
                        try {
                            ocrResultStr = extractPdfTextLayerToJson(inputPath, sourceDpi);
                            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                            pdfJsonRoot = mapper.readTree(ocrResultStr);
                            log.info("文本层坐标提取成功");
                        } catch (Exception e) {
                            log.error("直接提取 PDF 文本层坐标失败，将降级为 OCR 识别", e);
                            hasExistingText = false;
                        }
                    }

                    // 将 PDF 转换为图片
                    List<String> pdfImages = new ArrayList<>();
                    convertPdfToImages(inputPath, tempDir + "/pdf_" + fileIndex, pdfImages);

                    // 重命名为唯一名称以避免 OFDRW Canvas drawImage 资源命名冲突 (FileAlreadyExistsException)
                    List<String> uniquePdfImages = new ArrayList<>();
                    for (String imgPath : pdfImages) {
                        File srcFile = new File(imgPath);
                        File destFile = new File(tempDir, "merge_page_" + (allImagePaths.size() + uniquePdfImages.size()) + ".png");
                        if (srcFile.renameTo(destFile)) {
                            uniquePdfImages.add(destFile.getAbsolutePath());
                        } else {
                            uniquePdfImages.add(imgPath);
                        }
                    }
                    allImagePaths.addAll(uniquePdfImages);

                    // 为每一页记录 DPI 和文本层信息
                    for (int i = 0; i < pdfImages.size(); i++) {
                        allDpis.add(sourceDpi);
                        hasExistingTextList.add(hasExistingText);
                        if (hasExistingText && pdfJsonRoot != null && pdfJsonRoot.get("pages") != null) {
                            pageTextLayers.add(pdfJsonRoot.get("pages").get(i));
                        } else {
                            pageTextLayers.add(null);
                        }
                    }

                    log.info("PDF 拆分完成，共得到 {} 页图片", pdfImages.size());
                } else if (isOfd) {
                    log.info("处理文件 {}/{}: OFD -> 图片, DPI={}", fileIndex + 1, inputPaths.length, OFD_EXTRACT_DPI);

                    // 拆分 OFD 页面为图片
                    double ppm = OFD_EXTRACT_DPI / 25.4;
                    String ofdTempDir = tempDir + "/ofd_" + fileIndex;
                    new File(ofdTempDir).mkdirs();

                    try (ImageExporter exporter = new ImageExporter(inputFile.toPath(), Paths.get(ofdTempDir), "PNG", ppm)) {
                        exporter.export();
                    }

                    // 读取导出的页面图片并重命名为全局唯一名称，避免 OFDRW Canvas drawImage 资源冲突
                    int pageIndex = 0;
                    while (true) {
                        File pageImg = new File(ofdTempDir, pageIndex + ".png");
                        if (pageImg.exists()) {
                            File destFile = new File(tempDir, "merge_page_" + allImagePaths.size() + ".png");
                            if (pageImg.renameTo(destFile)) {
                                allImagePaths.add(destFile.getAbsolutePath());
                            } else {
                                allImagePaths.add(pageImg.getAbsolutePath());
                            }
                            allDpis.add((float) OFD_EXTRACT_DPI);
                            hasExistingTextList.add(false);
                            pageTextLayers.add(null);
                            pageIndex++;
                        } else {
                            break;
                        }
                    }

                    log.info("OFD 拆分完成，共得到 {} 页图片", pageIndex);
                } else if (isImage) {
                    log.info("处理文件 {}/{}: 图片直接使用", fileIndex + 1, inputPaths.length);
                    
                    // 复制并重命名到临时目录，避免 OFDRW Canvas drawImage 资源名称冲突
                    String imgExt = getFileExtension(inputPath);
                    if (imgExt.isEmpty()) {
                        imgExt = "png";
                    }
                    File destFile = new File(tempDir, "merge_page_" + allImagePaths.size() + "." + imgExt);
                    try {
                        java.nio.file.Files.copy(Paths.get(inputPath), Paths.get(destFile.getAbsolutePath()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        allImagePaths.add(destFile.getAbsolutePath());
                    } catch (Exception e) {
                        log.warn("复制图片失败，直接使用原图片路径: {}", inputPath, e);
                        allImagePaths.add(inputPath);
                    }

                    // 读取图片以获取像素宽高，用于推断 DPI
                    int imgW = 0;
                    int imgH = 0;
                    try {
                        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(inputFile);
                        if (img != null) {
                            imgW = img.getWidth();
                            imgH = img.getHeight();
                        }
                    } catch (Exception e) {
                        log.warn("无法读取图片尺寸进行 DPI 推断: {}", inputPath, e);
                    }

                    // 检测/推断 DPI
                    sourceDpi = DualLayerPdfConverter.detectImageDpi(inputPath, imgW, imgH);
                    log.info("图片输入，检测/推断出的 DPI 为: {}", sourceDpi);

                    allDpis.add(sourceDpi);
                    hasExistingTextList.add(false);
                    pageTextLayers.add(null);
                } else {
                    log.warn("不支持的输入文件类型，跳过: {}", inputPath);
                }

                fileIndex++;
            }

            if (allImagePaths.isEmpty()) {
                throw new RuntimeException("无可用页面图片进行合并转换");
            }

            log.info("所有输入文件处理完成，共得到 {} 页图片", allImagePaths.size());

            int totalImages = allImagePaths.size();

            // 第二步：OCR 识别没有文本层的页面，已包含文本层的直接合并
            List<String> imagesToOcr = new ArrayList<>();
            List<Integer> ocrImageIndices = new ArrayList<>();
            for (int i = 0; i < totalImages; i++) {
                if (!hasExistingTextList.get(i)) {
                    imagesToOcr.add(allImagePaths.get(i));
                    ocrImageIndices.add(i);
                }
            }

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.node.ArrayNode allPages = mapper.createArrayNode();

            // 初始化 allPages 数组大小，填充为 NullNode
            for (int i = 0; i < totalImages; i++) {
                allPages.add(com.fasterxml.jackson.databind.node.NullNode.getInstance());
            }

            // 填充已有文本层的页面
            for (int i = 0; i < totalImages; i++) {
                if (hasExistingTextList.get(i)) {
                    allPages.set(i, pageTextLayers.get(i));
                }
            }

            int ocrCount = imagesToOcr.size();
            if (ocrCount > 0) {
                LocalOcrClient ocrClient = new LocalOcrClient();
                int maxConcurrent = Integer.getInteger("pdfutil.pdf.localOcrMaxConcurrent", 4);
                int batchSize = Math.min(maxConcurrent, Math.max(1, ocrCount / 4));
                int batchCount = (ocrCount + batchSize - 1) / batchSize;

                log.info("启用并发OCR识别 (仅对未含文本层页面): 需要OCR页数={}, 最大并发数={}, 分{}批处理", ocrCount, maxConcurrent, batchCount);

                int ocrResultIdx = 0;
                for (int batchIdx = 0; batchIdx < batchCount; batchIdx++) {
                    int startIdx = batchIdx * batchSize;
                    int endIdx = Math.min(startIdx + batchSize, ocrCount);
                    List<String> batchImages = imagesToOcr.subList(startIdx, endIdx);

                    log.info("处理第{}/{}批: 图片{}-{}", batchIdx + 1, batchCount, startIdx + 1, endIdx);

                    try {
                        com.fasterxml.jackson.databind.JsonNode batchResult = ocrClient.recognizeImages(batchImages);
                        com.fasterxml.jackson.databind.JsonNode batchPages = batchResult.get("pages");

                        if (batchPages != null && batchPages.isArray()) {
                            for (com.fasterxml.jackson.databind.JsonNode page : batchPages) {
                                int originalIdx = ocrImageIndices.get(ocrResultIdx++);
                                allPages.set(originalIdx, page);
                            }
                        }
                    } catch (Exception e) {
                        log.error("第{}批OCR识别失败: {}", batchIdx + 1, e.getMessage(), e);
                        throw new RuntimeException("第" + (batchIdx + 1) + "批OCR识别失败: " + e.getMessage(), e);
                    }
                }
            }

            // 构建合并后的 OCR 结果
            com.fasterxml.jackson.databind.node.ObjectNode mergedResult = mapper.createObjectNode();
            mergedResult.set("pages", allPages);
            mergedResult.put("page_count", allPages.size());

            // 第三步：调用 OfdTextLayerAdder 合成双层 OFD（使用每页独立 DPI）
            log.info("调用 OfdTextLayerAdder 写入透明文字层，生成双层OFD，使用每页独立DPI");

            // 将 List<Float> 转换为 float[] 数组
            float[] dpisArray = new float[allDpis.size()];
            for (int i = 0; i < allDpis.size(); i++) {
                dpisArray[i] = allDpis.get(i);
            }

            try {
                OfdTextLayerAdder.createDualLayerOfdFromOcr(
                    allImagePaths.toArray(new String[0]),
                    mergedResult.toString(),
                    outputPath,
                    dpisArray
                );
            } catch (Exception e) {
                log.error("调用 OfdTextLayerAdder 失败", e);
                String errorMessage = e.getMessage();
                if (errorMessage == null || errorMessage.isEmpty()) {
                    errorMessage = e.toString();
                }
                Throwable cause = e.getCause();
                if (cause != null && cause.getMessage() != null && !cause.getMessage().isEmpty()) {
                    errorMessage = cause.getMessage();
                }
                throw new RuntimeException("合并OFD合成过程失败：" + errorMessage, e);
            }

            log.info("合并 OFD 转换成功: {} (共{}页)", outputPath, allImagePaths.size());

        } finally {
            // 清理临时目录
            DualLayerPdfConverter.deleteDirectory(new File(tempDir));
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
}
