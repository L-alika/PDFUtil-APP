package com.pdfutil.common.core.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pdfutil.common.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;

/**
 * 本地 PaddleOCR 客户端
 *
 * 调用本地 PaddleOCR Python 脚本获取文字识别结果和位置信息
 *
 * 功能特性：
 * - 支持配置 Python 路径
 * - 支持配置脚本路径（必须从 YML 配置）
 * - 完善的错误处理
 * - 并发控制
 * - 支持批量图片 OCR 识别
 *
 * 配置要求：
 * 必须在 application.yml 中配置 pdfutil.pdf.paddleOcrScriptPath 属性，
 * 不再使用硬编码的默认路径。
 *
 * 配置示例：
 * pdfutil:
 *   pdf:
 *     paddleOcrScriptPath: ${PADDLEOCR_SCRIPT:/root/paddleocr_scripts/paddleocr_wrapper.py}
 *
 * @author Alika
 * @date 2025-02-06
 * @updated 2025-02-09 移除硬编码路径，强制使用 YML 配置
 * @updated 2026-02-24 添加批量图片 OCR 识别支持
 */
public class LocalOcrClient {

    private static final Logger log = LoggerFactory.getLogger(LocalOcrClient.class);

    // 默认配置
    private static final String DEFAULT_PYTHON_PATH = "python";  // 或 python3

    // 默认超时配置
    private static final int DEFAULT_EXECUTE_TIMEOUT = 300000;   // 5 分钟

    // 智能并发控制配置
    /** 大图片阈值：12MP (1200万像素) */
    private static final long DEFAULT_LARGE_IMAGE_THRESHOLD = 12_000_000L;
    /** 超大图片阈值：24MP (2400万像素) */
    private static final long DEFAULT_VERY_LARGE_IMAGE_THRESHOLD = 24_000_000L;
    /** 默认最大并发数 */
    private static final int DEFAULT_MAX_CONCURRENT = 4;
    /** 常驻 OCR 服务池 */
    private static final ResidentOcrServicePool SERVICE_POOL = new ResidentOcrServicePool();

    /** 专用于读取进程输出的线程池，避免阻塞 ForkJoinPool.commonPool() */
    private static final java.util.concurrent.ExecutorService PROCESS_READER_EXECUTOR =
        java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "OcrProcessReader");
            t.setDaemon(true);
            return t;
        });

    /**
     * 并发控制信号量（延迟初始化）
     * 限制同时执行的 OCR 任务数量，避免系统过载
     */
    private static Semaphore CONCURRENCY_LIMIT = null;

    /**
     * 获取并发控制信号量
     */
    private static synchronized Semaphore getConcurrencyLimit() {
        if (CONCURRENCY_LIMIT == null) {
            int maxConcurrent = Integer.getInteger("pdfutil.pdf.localOcrMaxConcurrent", DEFAULT_MAX_CONCURRENT);
            // 【优化#8】添加边界值检查，确保并发数在合理范围内
            if (maxConcurrent < 1) {
                log.warn("配置的并发数 {} 无效，使用最小值 1", maxConcurrent);
                maxConcurrent = 1;
            } else if (maxConcurrent > 16) {
                log.warn("配置的并发数 {} 过大，限制为最大值 16", maxConcurrent);
                maxConcurrent = 16;
            }
            CONCURRENCY_LIMIT = new Semaphore(maxConcurrent, true); // 使用公平信号量，防止任务饥饿
            log.info("初始化 OCR 并发控制信号量: 最大许可数 = {}", maxConcurrent);
        }
        return CONCURRENCY_LIMIT;
    }

    private final String pythonPath;
    private final String scriptPath;
    private final String detModelDir;  // 检测模型路径
    private final String recModelDir;  // 识别模型路径
    private final ObjectMapper objectMapper;
    private final int executeTimeout;

    /**
     * 获取配置的 Python 路径
     * @return 配置的 Python 路径，如果未配置则返回默认值
     */
    private static String getConfiguredPythonPath() {
        String configuredPath = System.getProperty("python.path");
        return (configuredPath != null && !configuredPath.isEmpty()) ? configuredPath : DEFAULT_PYTHON_PATH;
    }

    /**
     * 获取配置的脚本路径
     * @return 配置的脚本路径，如果未配置则抛出异常
     */
    private static String getConfiguredScriptPath() {
        String rapidPath = System.getProperty("rapidocr.script.path");
        String ocrType = System.getProperty("ocr.type", System.getProperty("ocr.engine", ""));
        if (StringUtils.isNotEmpty(rapidPath) && ("rapid".equalsIgnoreCase(ocrType) || StringUtils.isEmpty(ocrType))) {
            return rapidPath;
        }

        // 优先从 paddleocr.script.path 系统属性获取（由 PdfUtilApplication 设置）
        String systemPath = System.getProperty("paddleocr.script.path");
        if (systemPath != null && !systemPath.isEmpty()) {
            return systemPath;
        }
        if (StringUtils.isNotEmpty(rapidPath)) {
            return rapidPath;
        }
        // 其次从 paddleocr.path 获取（兼容性）
        systemPath = System.getProperty("paddleocr.path");
        if (systemPath != null && !systemPath.isEmpty()) {
            return systemPath;
        }
        // 如果都没有配置，抛出异常而不是使用硬编码值
        throw new IllegalStateException("PaddleOCR 脚本路径未配置！请在 application.yml 中配置 pdfutil.pdf.paddleOcrScriptPath 属性");
    }

    /**
     * 默认构造函数，使用配置文件中的配置
     */
    public LocalOcrClient() {
        this(getConfiguredPythonPath(), getConfiguredScriptPath(), null, null, 
             Integer.getInteger("pdfutil.pdf.localOcrExecuteTimeout", DEFAULT_EXECUTE_TIMEOUT));
    }

    /**
     * 指定 Python 路径的构造函数
     */
    public LocalOcrClient(String pythonPath) {
        this(pythonPath, getConfiguredScriptPath(), null, null, 
             Integer.getInteger("pdfutil.pdf.localOcrExecuteTimeout", DEFAULT_EXECUTE_TIMEOUT));
    }

    /**
     * 完整参数构造函数
     *
     * @param pythonPath Python 可执行文件路径
     * @param scriptPath OCR 脚本路径
     * @param detModelDir 检测模型路径（可选）
     * @param recModelDir 识别模型路径（可选）
     * @param executeTimeout 执行超时时间（毫秒）
     */
    public LocalOcrClient(String pythonPath, String scriptPath, String detModelDir, String recModelDir, int executeTimeout) {
        this.pythonPath = (pythonPath != null && !pythonPath.isEmpty()) ? pythonPath : DEFAULT_PYTHON_PATH;
        // 如果传入的 scriptPath 为空，使用配置 of 路径
        if (scriptPath == null || scriptPath.isEmpty()) {
            this.scriptPath = getConfiguredScriptPath();
        } else {
            this.scriptPath = scriptPath;
        }
        this.detModelDir = detModelDir;
        this.recModelDir = recModelDir;
        this.executeTimeout = executeTimeout > 0 ? executeTimeout : DEFAULT_EXECUTE_TIMEOUT;
        this.objectMapper = new ObjectMapper();

        log.info("初始化 LocalOcrClient - Python: {}, 脚本：{}, 检测模型：{}, 识别模型：{}, 超时：{}ms",
            this.pythonPath, this.scriptPath, this.detModelDir, this.recModelDir, this.executeTimeout);
    }

    /**
     * 调用本地 PaddleOCR 识别图片文件（单张）
     *
     * @param imageFilePath 图片文件路径
     * @return OCR 识别结果（JsonNode 格式）
     * @throws Exception 调用失败时抛出异常
     */
    public JsonNode recognizeImage(String imageFilePath) throws Exception {
        return recognizeImages(java.util.Collections.singletonList(imageFilePath));
    }

    /**
     * 批量调用本地 PaddleOCR 识别多张图片文件
     *
     * @param imageFilePaths 图片文件路径列表
     * @return OCR 识别结果（JsonNode 格式），包含每页的识别结果
     * @throws Exception 调用失败时抛出异常
     */
    public JsonNode recognizeImages(List<String> imageFilePaths) throws Exception {
        if (imageFilePaths == null || imageFilePaths.isEmpty()) {
            throw new IllegalArgumentException("图片文件列表不能为空");
        }

        List<File> imageFiles = new ArrayList<>();
        for (String path : imageFilePaths) {
            File imageFile = new File(path);
            if (!imageFile.exists()) {
                throw new FileNotFoundException("图片文件不存在：" + path);
            }
            imageFiles.add(imageFile);
        }

        int maxConcurrent = getMaxConcurrent();
        int requiredPermits = Math.min(maxConcurrent, calculateRequiredPermits(imageFiles));

        boolean acquired = false;
        ResidentOcrService service = null;
        try {
            log.debug("等待获取本地 OCR 资源许可... (需要: {}, 当前可用: {})",
                requiredPermits, getConcurrencyLimit().availablePermits());

            // 由于队列积压可能较多，获取许可的等待时间应远大于单个任务的执行时间，以避免在高负载时频繁超时。
            // 这里使用 10 倍的 executeTimeout 作为等待时间（且至少 30 分钟）
            long acquireTimeout = Math.max(this.executeTimeout * 10L, 1800000L);
            if (!getConcurrencyLimit().tryAcquire(requiredPermits, acquireTimeout, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("获取 OCR 资源许可超时，系统负载过高，请稍后重试");
            }
            acquired = true;

            File scriptFile = resolveOcrScriptFile();
            JsonNode result;
            if (shouldUseResidentOcrService(scriptFile)) {
                service = SERVICE_POOL.borrowService(
                    pythonPath,
                    scriptFile,
                    resolveOcrServiceScriptFile(scriptFile),
                    buildOcrEnvironment()
                );

                String jsonResponse = service.recognize(imageFilePaths, executeTimeout);
                result = objectMapper.readTree(jsonResponse);

                if (result.has("error")) {
                    throw new RuntimeException("本地 OCR 识别失败：" + result.get("error").asText());
                }
            } else {
                String jsonResponse = executeOcrScriptLegacy(imageFilePaths);
                result = objectMapper.readTree(jsonResponse);
            }

            // 统一处理 input_path 字段
            if (result != null && result.isObject()) {
                ((ObjectNode) result).put("input_path", imageFilePaths.get(0).replace("\\", "/"));
            }

            return result;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待本地 OCR 许可被中断", e);
        } finally {
            if (service != null) {
                SERVICE_POOL.returnService(service);
            }
            if (acquired) {
                getConcurrencyLimit().release(requiredPermits);
            }
        }
    }

    /**
     * 兼容性接口：上传文件进行 OCR 识别
     *
     * @param file 要识别的文件（MultipartFile）
     * @return 识别后的结果封装为 OcrResult
     * @throws Exception 调用失败时抛出异常
     */
    public OcrResult ocr(org.springframework.web.multipart.MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }

        File tempFile = null;
        try {
            String suffix = getFileExtension(file.getOriginalFilename());
            tempFile = File.createTempFile("ocr_upload_", "." + suffix);
            file.transferTo(tempFile);

            JsonNode jsonResult = recognizeImage(tempFile.getPath());
            return convertToOcrResult(jsonResult);
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    private String executeOcrScriptLegacy(List<String> inputFilePaths) throws Exception {
        File scriptFile = resolveOcrScriptFile();
        List<String> command = new ArrayList<>();
        command.add(pythonPath);
        command.add(scriptFile.getCanonicalPath());
        command.addAll(inputFilePaths);

        Map<String, String> env = buildOcrEnvironment();
        ProcessManager.ProcessResult result = ProcessManager.execute(command, env, executeTimeout, false);
        if (!result.isSuccess()) {
            throw new IOException("OCR 脚本执行失败: " + result.getStderr());
        }
        return result.getStdout();
    }

    /**
     * 将 wrapper 脚本的输出转换为标准格式
     *
     * wrapper 脚本现在直接输出标准格式:
     * {"pages": [[{"text": "...", "text_region": [...], "confidence": 0.99}, ...], [...]], "page_count": N}
     *
     * 此方法保留用于兼容性和额外的字段处理
     *
     * @param wrapperJson wrapper 脚本的 JSON 输出
     * @param inputPath 输入文件路径
     * @return 标准格式的 JSON 字符串
     */
    private String convertWrapperFormatToStandard(String wrapperJson, String inputPath) {
        try {
            JsonNode rootNode = objectMapper.readTree(wrapperJson);

            // 检查是否已经是新的标准格式（包含 pages 数组）
            if (rootNode.has("pages") && rootNode.get("pages").isArray()) {
                // 已经是标准格式，直接添加 input_path 字段
                ObjectNode resultNode = (ObjectNode) rootNode;
                resultNode.put("input_path", inputPath.replace("\\", "/"));
                return objectMapper.writeValueAsString(resultNode);
            }

            // 兼容旧格式：扁平数组（所有页面合并在一起）
            if (rootNode.isArray()) {
                log.warn("检测到旧版 wrapper 输出格式（扁平数组），转换为单页标准格式");
                JsonNode jsonArray = rootNode;

                // 构建标准格式，同时转换字段名：box -> text_region
                StringBuilder standardJson = new StringBuilder();
                standardJson.append("{\n");
                standardJson.append("  \"pages\": [\n    [");

                // 转换每个元素
                for (int i = 0; i < jsonArray.size(); i++) {
                    JsonNode item = jsonArray.get(i);
                    if (i > 0) {
                        standardJson.append(",");
                    }
                    standardJson.append("\n      {");
                    standardJson.append("\"text\": ").append(item.get("text").toString()).append(", ");
                    // 兼容旧格式中的 box 字段
                    if (item.has("box")) {
                        standardJson.append("\"text_region\": ").append(item.get("box").toString()).append(", ");
                    } else if (item.has("text_region")) {
                        standardJson.append("\"text_region\": ").append(item.get("text_region").toString()).append(", ");
                    }
                    standardJson.append("\"confidence\": ").append(item.get("confidence").toString());
                    standardJson.append("}");
                }

                standardJson.append("\n    ]\n");
                standardJson.append("  ],\n");
                standardJson.append("  \"input_path\": \"").append(inputPath.replace("\\", "/")).append("\",\n");
                standardJson.append("  \"page_count\": 1\n");
                standardJson.append("}");

                return standardJson.toString();
            }

            // 其他未知格式，直接返回
            log.warn("未知的 wrapper 输出格式，直接返回原内容");
            return wrapperJson;

        } catch (Exception e) {
            log.error("转换 wrapper 格式失败，直接返回原内容：{}", e.getMessage());
            return wrapperJson;
        }
    }

    private boolean shouldUseResidentOcrService(File scriptFile) {
        boolean enabled = Boolean.parseBoolean(System.getProperty("pdfutil.pdf.residentOcrEnabled", "true"));
        if (!enabled || scriptFile == null) {
            return false;
        }
        String scriptName = scriptFile.getName().toLowerCase();
        return scriptName.contains("rapidocr_wrapper");
    }

    private File resolveOcrServiceScriptFile(File wrapperScriptFile) throws FileNotFoundException {
        String configuredPath = System.getProperty("rapidocr.service.script.path");
        List<File> candidates = new ArrayList<>();
        if (StringUtils.isNotEmpty(configuredPath)) {
            File configured = new File(configuredPath);
            candidates.add(configured);
            String userDir = System.getProperty("user.dir");
            addRelativeCandidates(candidates, new File(userDir), configuredPath);
            File userDirParent = new File(userDir).getParentFile();
            addRelativeCandidates(candidates, userDirParent, configuredPath);
            addRelativeCandidates(candidates, getJarDirectory(), configuredPath);
        }

        File scriptDir = wrapperScriptFile.getParentFile();
        addRelativeCandidates(candidates, scriptDir, "rapidocr_service.py");
        addRelativeCandidates(candidates, new File(System.getProperty("user.dir")), "scripts" + File.separator + "rapidocr_service.py");

        for (File candidate : candidates) {
            if (candidate != null && candidate.exists() && candidate.isFile()) {
                return candidate;
            }
        }

        String triedPaths = candidates.stream()
            .filter(Objects::nonNull)
            .map(File::getPath)
            .distinct()
            .collect(java.util.stream.Collectors.joining("; "));
        throw new FileNotFoundException("常驻 OCR 服务脚本不存在，已尝试：" + triedPaths);
    }

    private Map<String, String> buildOcrEnvironment() {
        Map<String, String> env = new java.util.HashMap<>();
        env.put("DISABLE_MODEL_SOURCE_CHECK", "True");
        env.put("HUB_DISABLE_MODEL_SOURCE_CHECK", "True");
        env.put("PADDLEX_DISABLE_MODEL_SOURCE_CHECK", "True");
        env.put("PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK", "True");
        env.put("PADDLEOCR_DISABLE_MODEL_SOURCE_CHECK", "True");
        env.put("HF_HUB_OFFLINE", "1");
        env.put("TRANSFORMERS_OFFLINE", "1");

        String paddleModelDir = System.getProperty("PADDLE_MODEL_DIR");
        if (StringUtils.isNotEmpty(paddleModelDir)) {
            env.put("PADDLE_MODEL_DIR", paddleModelDir);
        }

        String rapidModelDir = System.getProperty("rapidocr.model.dir");
        if (StringUtils.isNotEmpty(rapidModelDir)) {
            env.put("RAPIDOCR_MODEL_DIR", rapidModelDir);
        }

        String rapidThreadNum = System.getProperty("rapidocr.thread.num");
        if (StringUtils.isNotEmpty(rapidThreadNum)) {
            env.put("RAPIDOCR_THREAD_NUM", rapidThreadNum);
        }

        return env;
    }

    private File resolveOcrScriptFile() throws FileNotFoundException {
        List<File> candidates = buildScriptPathCandidates();
        for (File candidate : candidates) {
            if (candidate != null && candidate.exists() && candidate.isFile()) {
                return candidate;
            }
        }

        String triedPaths = candidates.stream()
            .filter(Objects::nonNull)
            .map(File::getPath)
            .distinct()
            .collect(java.util.stream.Collectors.joining("; "));
        throw new FileNotFoundException("OCR 脚本不存在：" + scriptPath + "，已尝试：" + triedPaths);
    }

    private List<File> buildScriptPathCandidates() {
        List<File> candidates = new ArrayList<>();
        File configured = new File(scriptPath);
        candidates.add(configured);

        String userDir = System.getProperty("user.dir");
        addRelativeCandidates(candidates, new File(userDir), scriptPath);

        File userDirParent = new File(userDir).getParentFile();
        addRelativeCandidates(candidates, userDirParent, scriptPath);

        File jarDir = getJarDirectory();
        addRelativeCandidates(candidates, jarDir, scriptPath);

        String scriptName = configured.getName();
        if (StringUtils.isNotEmpty(scriptName)) {
            addRelativeCandidates(candidates, new File(userDir), "scripts" + File.separator + scriptName);
            addRelativeCandidates(candidates, userDirParent, "scripts" + File.separator + scriptName);
            addRelativeCandidates(candidates, jarDir, "scripts" + File.separator + scriptName);
        }

        return candidates;
    }

    private void addRelativeCandidates(List<File> candidates, File baseDir, String relativePath) {
        if (baseDir == null || StringUtils.isEmpty(relativePath)) {
            return;
        }
        candidates.add(new File(baseDir, relativePath));
    }

    private File getJarDirectory() {
        try {
            File location = new File(LocalOcrClient.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return location.isFile() ? location.getParentFile() : location;
        } catch (Exception e) {
            log.debug("获取运行目录失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 递归删除目录
     */
    private void deleteDirectory(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        directory.delete();
    }

    /**
     * 将 JsonNode 转换为 OcrResult
     */
    private OcrResult convertToOcrResult(JsonNode jsonResult) {
        List<OcrResult.PageResult> pages = new ArrayList<>();

        JsonNode pagesNode = jsonResult.get("pages");
        if (pagesNode != null && pagesNode.isArray()) {
            for (JsonNode pageNode : pagesNode) {
                if (pageNode.isArray()) {
                    // 旧格式：页面是文本项数组
                    StringBuilder textBuilder = new StringBuilder();
                    for (JsonNode itemNode : pageNode) {
                        if (itemNode.has("text")) {
                            textBuilder.append(itemNode.get("text").asText()).append("\n");
                        }
                    }
                    OcrResult.PageResult pageResult = new OcrResult.PageResult();
                    pageResult.setText(textBuilder.toString().trim());
                    pages.add(pageResult);
                } else if (pageNode.isObject() && pageNode.has("text")) {
                    // 新格式：直接包含文本
                    OcrResult.PageResult pageResult = new OcrResult.PageResult();
                    pageResult.setText(pageNode.get("text").asText());
                    pages.add(pageResult);
                }
            }
        }

        return new OcrResult(pages);
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "tmp";
        }
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == fileName.length() - 1) {
            return "tmp";
        }
        return fileName.substring(lastDotIndex + 1);
    }

    /**
     * 测试本地 OCR 环境
     *
     * @return 测试是否成功
     */
    public boolean testEnvironment() {
        try {
            log.info("测试本地 OCR 环境");

            // 检查 Python 是否可用
            ProcessBuilder pb = new ProcessBuilder(pythonPath, "--version");
            Process process = pb.start();
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);

            if (!finished || process.exitValue() != 0) {
                log.error("Python 不可用或版本检查失败");
                return false;
            }

            // 检查脚本是否存在
            File scriptFile = new File(scriptPath);
            if (!scriptFile.exists()) {
                String projectRoot = System.getProperty("user.dir");
                scriptFile = new File(projectRoot, scriptPath);
            }

            if (!scriptFile.exists()) {
                log.error("OCR 脚本不存在：{}", scriptPath);
                return false;
            }

            log.info("本地 OCR 环境测试成功");
            return true;

        } catch (Exception e) {
            log.error("本地 OCR 环境测试失败", e);
            return false;
        }
    }

    // ==================== 智能并发控制方法 ====================

    /**
     * 计算处理图片所需的并发许可数
     * 根据图片大小动态调整：
     * - 普通图片（<12MP）：1个许可
     * - 大图片（12MP~24MP）：maxConcurrent/2 个许可（并发减半）
     * - 超大图片（>24MP）：maxConcurrent 个许可（独占所有资源，不并发）
     *
     * 【修复】确保许可数与最大并发数正确关联，避免死锁
     *
     * @param imageFiles 图片文件列表
     * @return 需要的许可数
     */
    private int calculateRequiredPermits(List<File> imageFiles) {
        long maxPixels = 0;
        File largestFile = null;

        for (File file : imageFiles) {
            try {
                long pixels = getImagePixels(file);
                if (pixels > maxPixels) {
                    maxPixels = pixels;
                    largestFile = file;
                }
            } catch (Exception e) {
                log.debug("无法读取图片尺寸: {}", file.getName());
            }
        }

        int maxConcurrent = getMaxConcurrent();
        long largeThreshold = getLargeImageThreshold();
        long veryLargeThreshold = getVeryLargeImageThreshold();

        // 根据图片大小计算需要的许可数
        int permits;
        if (maxPixels >= veryLargeThreshold) {
            // 超大图片：占用全部并发资源，不与其他任务并发执行
            permits = maxConcurrent;
            double megaPixels = maxPixels / 1_000_000.0;
            log.info("检测到超大图片 ({} 像素, {}MP)，独占并发资源 (需要 {} 个许可, 文件: {})",
                    maxPixels, String.format("%.1f", megaPixels), permits,
                    largestFile != null ? largestFile.getName() : "unknown");
        } else if (maxPixels >= largeThreshold) {
            // 大图片：占用一半并发资源
            // 【优化#4】修复奇数并发时的资源分配，向上取整确保足够资源
            permits = (maxConcurrent + 1) / 2;  // 等价于 Math.ceil(maxConcurrent / 2.0)
            double megaPixels = maxPixels / 1_000_000.0;
            log.info("检测到大图片 ({} 像素, {}MP)，需要 {} 个许可 (文件: {})",
                    maxPixels, String.format("%.1f", megaPixels), permits,
                    largestFile != null ? largestFile.getName() : "unknown");
        } else {
            // 普通图片：正常并发
            permits = 1;
        }

        return permits;
    }

    /**
     * 获取图片的像素数（宽 x 高）
     *
     * @param imageFile 图片文件
     * @return 像素数，如果无法读取则返回0
     */
    private long getImagePixels(File imageFile) {
        BufferedImage image = null;
        try {
            // 使用 ImageReader 以确保正确关闭文件句柄
            try (ImageInputStream in = ImageIO.createImageInputStream(imageFile)) {
                if (in != null) {
                    ImageReader reader = null;
                    try {
                        Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
                        if (readers.hasNext()) {
                            reader = readers.next();
                            reader.setInput(in);
                            int width = reader.getWidth(0);
                            int height = reader.getHeight(0);
                            return (long) width * height;
                        }
                    } finally {
                        if (reader != null) {
                            reader.dispose();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.trace("读取图片尺寸失败: {} - {}", imageFile.getName(), e.getMessage());
        } finally {
            // 确保 BufferedImage 被释放（如果使用了旧的 ImageIO.read 方式）
            if (image != null) {
                image.flush();
            }
        }
        return 0;
    }

    /**
     * 获取最大并发数配置
     *
     * @return 最大并发数
     */
    private int getMaxConcurrent() {
        int configured = Integer.getInteger("pdfutil.pdf.localOcrMaxConcurrent", DEFAULT_MAX_CONCURRENT);
        if (configured <= 0) {
            log.warn("配置的 OCR 最大并发数 {} 无效，使用默认值 {}", configured, DEFAULT_MAX_CONCURRENT);
            return DEFAULT_MAX_CONCURRENT;
        }
        // 【优化#9】添加上限检查，防止配置值过大
        if (configured > 16) {
            log.warn("配置的 OCR 最大并发数 {} 过大，限制为 16", configured);
            return 16;
        }
        return configured;
    }

    /**
     * 获取大图片阈值配置
     *
     * @return 大图片阈值（像素数）
     */
    private long getLargeImageThreshold() {
        String thresholdStr = System.getProperty("pdfutil.pdf.largeImageThreshold");
        if (thresholdStr != null && !thresholdStr.isEmpty()) {
            try {
                long threshold = Long.parseLong(thresholdStr);
                // 【优化#10】添加边界值检查
                if (threshold < 1_000_000) {  // 最小1MP
                    log.warn("大图片阈值 {} 过小，使用最小值 1MP", threshold);
                    return 1_000_000;
                } else if (threshold > 100_000_000) {  // 最大100MP
                    log.warn("大图片阈值 {} 过大，使用最大值 100MP", threshold);
                    return 100_000_000;
                }
                return threshold;
            } catch (NumberFormatException e) {
                log.warn("无效的大图片阈值配置: {}, 使用默认值", thresholdStr);
            }
        }
        return DEFAULT_LARGE_IMAGE_THRESHOLD;
    }

    /**
     * 获取超大图片阈值配置
     *
     * @return 超大图片阈值（像素数）
     */
    private long getVeryLargeImageThreshold() {
        String thresholdStr = System.getProperty("pdfutil.pdf.veryLargeImageThreshold");
        if (thresholdStr != null && !thresholdStr.isEmpty()) {
            try {
                long threshold = Long.parseLong(thresholdStr);
                // 【优化#10】添加边界值检查和逻辑验证
                if (threshold < 2_000_000) {  // 最小2MP
                    log.warn("超大图片阈值 {} 过小，使用最小值 2MP", threshold);
                    return 2_000_000;
                } else if (threshold > 200_000_000) {  // 最大200MP
                    log.warn("超大图片阈值 {} 过大，使用最大值 200MP", threshold);
                    return 200_000_000;
                }

                // 确保超大图片阈值大于大图片阈值
                long largeThreshold = getLargeImageThreshold();
                if (threshold <= largeThreshold) {
                    log.warn("超大图片阈值 {} 应大于大图片阈值 {}，调整为 {}", threshold, largeThreshold, largeThreshold * 2);
                    return largeThreshold * 2;
                }
                return threshold;
            } catch (NumberFormatException e) {
                log.warn("无效的超大图片阈值配置: {}, 使用默认值", thresholdStr);
            }
        }
        return DEFAULT_VERY_LARGE_IMAGE_THRESHOLD;
    }

    private static class ResidentOcrServicePool {
        private final List<ResidentOcrService> idleServices = new ArrayList<>();
        private final List<ResidentOcrService> activeServices = new ArrayList<>();
        private int maxPoolSize = -1;

        private synchronized int getMaxPoolSize() {
            if (maxPoolSize == -1) {
                maxPoolSize = Integer.getInteger("pdfutil.pdf.localOcrMaxConcurrent", 4);
            }
            return maxPoolSize;
        }

        public synchronized ResidentOcrService borrowService(String pythonPath, File wrapperScript, File serviceScript, Map<String, String> env) throws IOException {
            // 尝试寻找匹配且空闲的服务
            String key = ResidentOcrService.calculateKey(pythonPath, wrapperScript, serviceScript);

            // 【修复14】在迭代时进行健康检查，避免借用不健康的服务
            for (int i = 0; i < idleServices.size(); i++) {
                ResidentOcrService service = idleServices.get(i);
                if (service.isAlive() && service.healthy && service.getKey().equals(key)) {
                    idleServices.remove(i);
                    activeServices.add(service);
                    log.debug("从空闲池借用 OCR 服务，当前空闲: {}, 活跃: {}", idleServices.size(), activeServices.size());
                    return service;
                } else if (!service.isAlive() || !service.healthy) {
                    // 发现不健康的服务，移除并清理
                    log.warn("发现不健康的 OCR 服务，移除并清理");
                    idleServices.remove(i);
                    service.shutdown();
                    i--; // 调整索引
                }
            }

            // 如果没有匹配的空闲服务，且池未满，则创建新服务
            if (activeServices.size() + idleServices.size() < getMaxPoolSize()) {
                ResidentOcrService service = new ResidentOcrService(pythonPath, wrapperScript, serviceScript, env);
                activeServices.add(service);
                log.debug("创建新的 OCR 服务，当前空闲: {}, 活跃: {}", idleServices.size(), activeServices.size());
                return service;
            }

            // 如果池已满，强制从空闲列表中拿一个
            if (!idleServices.isEmpty()) {
                ResidentOcrService service = idleServices.remove(0);
                // 如果 Key 不匹配，必须先关闭并重新配置
                if (!service.getKey().equals(key)) {
                    log.debug("OCR 服务 Key 不匹配，重新配置服务");
                    service.shutdown();
                    service = new ResidentOcrService(pythonPath, wrapperScript, serviceScript, env);
                } else if (!service.isAlive() || !service.healthy) {
                    // 服务不健康，重新创建
                    log.debug("OCR 服务不健康，重新创建");
                    service.shutdown();
                    service = new ResidentOcrService(pythonPath, wrapperScript, serviceScript, env);
                }
                activeServices.add(service);
                return service;
            }

            // 理论上信号量会阻止进入这里，但为了安全抛出异常
            throw new RuntimeException("OCR 服务池已耗尽，无法获取可用实例");
        }

        public synchronized void returnService(ResidentOcrService service) {
            activeServices.remove(service);
            if (service != null) {
                // 【修复15】添加健康状态检查，只归还健康的服务
                if (service.isAlive() && service.healthy) {
                    idleServices.add(service);
                    log.debug("归还健康的 OCR 服务到空闲池，当前空闲: {}", idleServices.size());
                } else {
                    log.warn("归还的 OCR 服务不健康或已停止，执行清理");
                    service.shutdown();
                }
            }
        }

        public synchronized void shutdownAll() {
            for (ResidentOcrService s : idleServices) s.shutdown();
            for (ResidentOcrService s : activeServices) s.shutdown();
            idleServices.clear();
            activeServices.clear();
        }
    }

    private static class ResidentOcrService {
        private final ObjectMapper mapper = new ObjectMapper();
        private final String pythonPath;
        private final File wrapperScript;
        private final File serviceScript;
        private final Map<String, String> env;
        private final String key;
        private final Thread shutdownHook;

        private Process process;
        private BufferedWriter writer;
        private BufferedReader reader;
        private Thread stderrThread;
        private volatile boolean healthy = true;  // 添加健康状态标志
        private volatile long lastRequestTime = 0;  // 添加最后请求时间

        public ResidentOcrService(String pythonPath, File wrapperScript, File serviceScript, Map<String, String> env) {
            this.pythonPath = pythonPath;
            this.wrapperScript = wrapperScript;
            this.serviceScript = serviceScript;
            this.env = env;
            this.key = calculateKey(pythonPath, wrapperScript, serviceScript);

            this.shutdownHook = new Thread(this::shutdown, "ResidentOcrShutdown-" + UUID.randomUUID().toString().substring(0, 8));
            Runtime.getRuntime().addShutdownHook(this.shutdownHook);
        }

        public static String calculateKey(String pythonPath, File wrapperScript, File serviceScript) {
            try {
                return pythonPath + "|" + wrapperScript.getCanonicalPath() + "|" + serviceScript.getCanonicalPath();
            } catch (IOException e) {
                return pythonPath + "|" + wrapperScript.getPath() + "|" + serviceScript.getPath();
            }
        }

        public String getKey() { return key; }

        public boolean isAlive() {
            // 【修复13】增强存活检查，包含进程和I/O流状态
            if (process == null || !process.isAlive()) {
                return false;
            }

            // 检查 I/O 流是否可用
            if (writer == null || reader == null) {
                return false;
            }

            // 检查健康状态
            if (!healthy) {
                return false;
            }

            return true;
        }

        public synchronized String recognize(List<String> imagePaths, int timeoutMs) throws Exception {
            // 【修复1】在开始处理前检查服务健康状态
            if (!healthy) {
                log.warn("OCR 服务状态异常，准备重启");
                shutdownInternal();
            }

            ensureStarted();

            // 【修复2】添加操作前验证，确保I/O流可用
            if (writer == null || reader == null) {
                throw new IOException("OCR 服务的 I/O 流未正确初始化");
            }

            String requestId = UUID.randomUUID().toString();
            ObjectNode request = mapper.createObjectNode();
            request.put("id", requestId);
            request.put("action", "ocr");
            com.fasterxml.jackson.databind.node.ArrayNode images = request.putArray("images");
            for (String imagePath : imagePaths) {
                images.add(imagePath);
            }

            String requestLine = mapper.writeValueAsString(request);
            try {
                // 【修复3】添加写入前检查和更完善的异常处理
                synchronized (writer) {
                    writer.write(requestLine);
                    writer.newLine();
                    writer.flush();
                }
                lastRequestTime = System.currentTimeMillis();
            } catch (IOException e) {
                log.warn("发送 OCR 请求失败，尝试重启服务: {}", e.getMessage());
                healthy = false; // 标记为不健康

                // 【修复4】在重启前确保资源完全释放
                shutdownInternal();

                // 等待一小段时间确保进程完全终止
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }

                ensureStarted();
                healthy = true; // 标记为健康

                // 【修复5】重启后重试，设置最大重试次数
                try {
                    synchronized (writer) {
                        writer.write(requestLine);
                        writer.newLine();
                        writer.flush();
                    }
                    lastRequestTime = System.currentTimeMillis();
                } catch (IOException retryException) {
                    // 重试仍然失败，标记为不健康并抛出异常
                    healthy = false;
                    shutdownInternal();
                    throw new IOException("OCR 服务重启后重试仍然失败: " + retryException.getMessage(), retryException);
                }
            }

            // 【修复6】添加读取超时保护，避免无限等待
            long readStartTime = System.currentTimeMillis();
            String responseLine = readResponseLine(timeoutMs);
            long readElapsed = System.currentTimeMillis() - readStartTime;

            if (StringUtils.isEmpty(responseLine)) {
                healthy = false;
                shutdownInternal();
                throw new IOException("常驻 OCR 服务返回空响应 (可能已崩溃)");
            }

            JsonNode response = mapper.readTree(responseLine);

            // 【关键修复】校验响应 ID 是否匹配
            if (response.has("id")) {
                String respId = response.get("id").asText();
                if (!requestId.equals(respId)) {
                    log.error("OCR 响应 ID 不匹配! 期望: {}, 实际: {}, 读取耗时: {}ms。这可能导致识别结果映射错位。",
                            requestId, respId, readElapsed);
                    // 如果 ID 不匹配，说明读取到了之前的陈旧响应，必须关停进程以清理管道
                    healthy = false;
                    shutdownInternal();
                    throw new IOException("OCR 响应 ID 不匹配，识别结果可能已乱序");
                }
            }

            if (response.has("ok") && !response.get("ok").asBoolean()) {
                String errorMsg = response.has("error") ? response.get("error").asText() : "常驻 OCR 服务返回失败";
                // 【修复7】某些错误情况下需要重启服务
                if (errorMsg.contains("崩溃") || errorMsg.contains("异常")) {
                    healthy = false;
                    shutdownInternal();
                }
                throw new IOException(errorMsg);
            }

            return mapper.writeValueAsString(response);
        }

        private void ensureStarted() throws IOException {
            // 【修复8】双重检查锁定模式，避免竞态条件
            if (isAlive()) {
                // 进一步检查 I/O 流状态
                if (writer == null || reader == null) {
                    log.warn("进程存在但 I/O 流异常，强制重启");
                    shutdownInternal();
                } else {
                    return;
                }
            }

            // 【修复9】添加启动前延迟，避免快速重启导致端口冲突
            if (process != null) {
                try {
                    Thread.sleep(50);  // 短暂等待确保资源释放
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            shutdownInternal();

            List<String> command = new ArrayList<>();
            command.add(pythonPath);
            command.add(serviceScript.getCanonicalPath());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            pb.directory(serviceScript.getParentFile());
            if (env != null) pb.environment().putAll(env);

            try {
                process = pb.start();
                writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
                reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                startStderrThread(process);
                healthy = true;
                log.info("常驻 OCR 进程已启动 (PID: {}): {}", getPid(process), String.join(" ", command));
            } catch (IOException e) {
                // 【修复10】启动失败时清理资源并抛出异常
                shutdownInternal();
                healthy = false;
                throw new IOException("启动 OCR 服务失败: " + e.getMessage(), e);
            }
        }

        private long getPid(Process p) {
            if (p == null) return -1;
            // 尝试 Java 9+ 的 pid() 方法
            try {
                java.lang.reflect.Method method = p.getClass().getDeclaredMethod("pid");
                method.setAccessible(true);
                return (long) method.invoke(p);
            } catch (NoSuchMethodException e) {
                // Java 8 兼容处理
                try {
                    if (p.getClass().getName().equals("java.lang.ProcessImpl") ||
                        p.getClass().getName().equals("java.lang.Win32ProcessImpl")) {
                        java.lang.reflect.Field f = p.getClass().getDeclaredField("handle");
                        f.setAccessible(true);
                        // Windows 下 handle 不是 PID，需要额外工具，但在 Java 8 下暂不深入实现
                        return -1;
                    }
                    // Unix 类系统 Java 8
                    java.lang.reflect.Field f = p.getClass().getDeclaredField("pid");
                    f.setAccessible(true);
                    return (int) f.get(p);
                } catch (Exception ignored) {}
            } catch (Exception ignored) {}
            return -1;
        }

        private String readResponseLine(int timeoutMs) throws Exception {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return reader.readLine();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }, PROCESS_READER_EXECUTOR);

            try {
                return future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                shutdownInternal();
                throw new IOException("读取 OCR 响应超时或服务异常", e);
            }
        }

        private void startStderrThread(Process targetProcess) {
            stderrThread = new Thread(() -> {
                try (BufferedReader stderrReader = new BufferedReader(
                        new InputStreamReader(targetProcess.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = stderrReader.readLine()) != null) {
                        if (line.contains("[PROGRESS]")) {
                            log.info("[OCR进程-{}] {}", getPid(targetProcess), line.replaceAll("\\[PROGRESS\\]\\s*", ""));
                        } else {
                            log.debug("[OCR进程-{}] {}", getPid(targetProcess), line);
                        }
                    }
                } catch (IOException ignored) {}
            }, "ResidentOcrStderr-" + UUID.randomUUID().toString().substring(0, 8));
            stderrThread.setDaemon(true);
            stderrThread.start();
        }

        /**
         * 外部调用的销毁方法，会移除 ShutdownHook
         */
        public synchronized void shutdown() {
            try {
                Runtime.getRuntime().removeShutdownHook(this.shutdownHook);
            } catch (Exception ignored) {}
            shutdownInternal();
        }

        /**
         * 内部销毁逻辑
         */
        private synchronized void shutdownInternal() {
            // 【修复11】添加状态检查，避免重复清理
            if (writer == null && reader == null && process == null) {
                return;  // 已经清理过了
            }

            // 【修复12】按顺序安全关闭资源，避免资源泄漏
            // 1. 先关闭输出流，停止发送新数据
            closeQuietly(writer);
            writer = null;

            // 2. 再关闭输入流，停止接收数据
            closeQuietly(reader);
            reader = null;

            // 3. 最后终止进程
            if (process != null) {
                if (process.isAlive()) {
                    log.debug("终止 OCR 进程 (PID: {})", getPid(process));
                    process.destroy();

                    try {
                        // 等待最多1秒让进程自行退出
                        if (!process.waitFor(1, TimeUnit.SECONDS)) {
                            log.warn("OCR 进程未响应 destroy()，执行 destroyForcibly()");
                            process.destroyForcibly();

                            // 再等待1秒
                            process.waitFor(1, TimeUnit.SECONDS);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("终止 OCR 进程时被中断");
                        process.destroyForcibly();
                    }
                }
                process = null;
            }

            // 标记为不健康
            healthy = false;

            log.debug("OCR 服务资源清理完成");
        }

        private void closeQuietly(Closeable closeable) {
            if (closeable != null) {
                try { closeable.close(); } catch (IOException ignored) {}
            }
        }

        /**
         * 【新增】执行健康检查
         * @return 服务是否健康
         */
        public boolean isHealthy() {
            if (!isAlive()) {
                return false;
            }

            // 检查最后一次请求时间，如果超过5分钟没有请求，可能已僵死
            if (lastRequestTime > 0) {
                long idleTime = System.currentTimeMillis() - lastRequestTime;
                if (idleTime > 5 * 60 * 1000) {  // 5分钟
                    log.warn("OCR 服务空闲时间过长 ({}ms)，可能已僵死", idleTime);
                    return false;
                }
            }

            return healthy;
        }
    }
}
