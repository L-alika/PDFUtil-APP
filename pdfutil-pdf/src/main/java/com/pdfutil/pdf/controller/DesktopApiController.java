package com.pdfutil.pdf.controller;

import com.pdfutil.common.annotation.Log;
import com.pdfutil.common.config.PdfUtilConfig;
import com.pdfutil.common.core.controller.BaseController;
import com.pdfutil.common.core.domain.AjaxResult;
import com.pdfutil.common.core.page.TableDataInfo;
import com.pdfutil.common.core.utils.DualLayerPdfConverter;
import com.pdfutil.common.core.utils.LocalOcrClient;
import com.pdfutil.common.enums.BusinessType;
import com.pdfutil.common.utils.FileNameValidator;
import com.pdfutil.common.utils.StringUtils;
import com.pdfutil.pdf.domain.PdfConvertRecord;
import com.pdfutil.pdf.service.FileStatsService;
import com.pdfutil.pdf.service.IPdfConvertRecordService;
import com.pdfutil.pdf.service.PdfConvertSimpleService;
import com.pdfutil.pdf.utils.PdfOutputPathBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.annotation.PreDestroy;
import javax.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.*;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 桌面应用API控制器
 * 提供无需认证的API接口供Electron应用调用
 *
 * @author Alika
 * @date 2025-02-27
 */
@RestController
@RequestMapping("/api/pdf")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.DELETE})
public class DesktopApiController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(DesktopApiController.class);

    @Autowired
    private PdfConvertSimpleService pdfConvertSimpleService;

    @Autowired
    private IPdfConvertRecordService pdfConvertRecordService;

    @Autowired
    private FileStatsService fileStatsService;

    private static final int MAX_FAIL_REASON_LENGTH = 490;
    private static final int ARCHIVE_PREVIEW_DPI = 300;
    private static final int ARCHIVE_OCR_CACHE_MAX_SIZE = 32;
    private static final int ARCHIVE_OCR_PADDING = 4; // 增加 4 像素边距确保文字完整性
    /** 任务取消标志：key为任务ID（使用输入目录路径），value为是否取消 */
    private static final ConcurrentHashMap<String, Boolean> taskCancelFlags = new ConcurrentHashMap<>();

    /** 任务线程映射：key为记录ID，value为对应的线程 */
    private static final ConcurrentHashMap<String, Thread> taskThreads = new ConcurrentHashMap<>();

    /** 档案著录页整页 OCR 缓存，避免同一页多次框选反复初始化 OCR 引擎 */
    private static final ConcurrentHashMap<String, ArchiveOcrCacheEntry> archiveOcrCache = new ConcurrentHashMap<>();

    /** 当前活动的批量转换任务ID */
    private static volatile String currentBatchTaskId = null;

    /** 后台任务线程池 */
    // 改为单线程队列处理，避免并发问题
    private static final java.util.concurrent.ExecutorService taskExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
                private final java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger(0);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "PdfConvert-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            }
        );

    /**
     * 应用关闭时清理线程池资源
     */
    @PreDestroy
    public void shutdown() {
        log.info("开始关闭 PdfConvert 线程池...");
        taskExecutor.shutdown();
        try {
            // 等待最多30秒让正在执行的任务完成
            if (!taskExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("线程池未能在30秒内正常关闭，执行强制关闭");
                taskExecutor.shutdownNow();
                // 再等待10秒
                if (!taskExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.error("线程池强制关闭后仍有任务未结束");
                }
            }
        } catch (InterruptedException e) {
            log.warn("等待线程池关闭被中断，执行强制关闭");
            taskExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("PdfConvert 线程池已关闭");
    }

    /**
     * 文件上传和转换
     */
    @PostMapping("/upload")
    public AjaxResult uploadAndConvert(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "ocrType", required = false, defaultValue = "local_paddle") String ocrType,
            @RequestParam(value = "outputMode", required = false, defaultValue = "double") String outputMode,
            @RequestParam(value = "outputDir", required = false) String customOutputDir,
            @RequestParam(value = "filenamePrefix", required = false) String filenamePrefix,
            @RequestParam(value = "filenameSuffix", required = false) String filenameSuffix,
            @RequestParam(value = "folderCreateRule", required = false, defaultValue = "4") int folderCreateRule,
            @RequestParam(value = "pdfNameSource", required = false, defaultValue = "1") int pdfNameSource,
            @RequestParam(value = "preserveInputDir", required = false) String preserveInputDir,
            @RequestParam(value = "splitType", required = false) String splitType,
            @RequestParam(value = "splitStartPage", required = false) Integer splitStartPage,
            @RequestParam(value = "splitEndPage", required = false) Integer splitEndPage) {

        if (file.isEmpty()) {
            return AjaxResult.error("请选择要上传的文件");
        }

        try {
            String fileName = file.getOriginalFilename();
            String extension = getFileExtension(fileName).toLowerCase();

            if (!Arrays.asList("pdf", "jpg", "jpeg", "tif", "tiff", "png", "bmp",
                    "doc", "docx", "xls", "xlsx", "ppt", "pptx", "ofd").contains(extension)) {
                return AjaxResult.error("不支持的文件格式");
            }

            if (!FileNameValidator.isSafeFileName(fileName)) {
                return AjaxResult.error("文件名包含非法字符");
            }

            // 保存上传文件
            String uploadDir = getFileUploadDir();
            File destDir = new File(uploadDir);
            if (!destDir.exists()) {
                destDir.mkdirs();
            }

            // 【修复】生成唯一的临时文件名，避免同名文件互相覆盖
            // 使用 UUID + 时间戳 + 原子计数器确保唯一性
            String uniqueFileName = UUID.randomUUID().toString().substring(0, 8) + "_" + fileName;
            String sourcePath = uploadDir + File.separator + uniqueFileName;
            File destFile = new File(sourcePath);
            file.transferTo(destFile);

            // 创建转换记录 - 优先使用前端指定的输出目录
            String baseOutputDir = StringUtils.isNotEmpty(customOutputDir) ? customOutputDir : getOutputDir();

            // 使用原始文件名作为命名依据，避免 UUID 临时前缀进入输出文件名和目录名
            String namingSourcePath = uploadDir + File.separator + fileName;

            // 对于规则1和规则2，不使用buildOutputDir预先构建路径
            // 这些规则需要根据源文件的父目录路径结构来构建档号，由convertToDirectory方法处理
            // 对于其他规则（0, 3-7），使用PdfOutputPathBuilder构建输出路径
            String actualOutputDir;
            if (folderCreateRule == 1 || folderCreateRule == 2) {
                // 规则1 and 2：直接使用基础输出目录，让convertToDirectory处理档号构建
                actualOutputDir = baseOutputDir;
            } else {
                // 其他规则：使用PdfOutputPathBuilder构建输出路径
                actualOutputDir = PdfOutputPathBuilder.buildOutputDir(
                        baseOutputDir, namingSourcePath, folderCreateRule, (String) null);
            }

            // 确保输出目录存在
            File outDir = new File(actualOutputDir);
            if (!outDir.exists()) {
                outDir.mkdirs();
            }

            // 使用PdfOutputPathBuilder获取PDF文件名（传入输出目录路径）
            String pdfBaseName = PdfOutputPathBuilder.getPdfBaseName(actualOutputDir, namingSourcePath, pdfNameSource);

            // 应用前缀和后缀
            StringBuilder targetFileNameBuilder = new StringBuilder();
            if (StringUtils.isNotEmpty(filenamePrefix)) {
                targetFileNameBuilder.append(FileNameValidator.sanitizeFileName(filenamePrefix));
            }
            targetFileNameBuilder.append(FileNameValidator.sanitizeFileName(pdfBaseName));
            if (StringUtils.isNotEmpty(filenameSuffix)) {
                targetFileNameBuilder.append(FileNameValidator.sanitizeFileName(filenameSuffix));
            }
            String targetExt = ".pdf";
            if ("ofd".equals(extension) || (outputMode != null && outputMode.toLowerCase().contains("ofd"))) {
                targetExt = ".ofd";
            }
            targetFileNameBuilder.append(targetExt);
            String targetFileName = targetFileNameBuilder.toString();

            PdfConvertRecord record = new PdfConvertRecord();
            record.setSourceFileName(fileName);
            record.setSourceFilePath(sourcePath);
            record.setTargetFileName(targetFileName);
            record.setTargetFilePath(actualOutputDir + File.separator + targetFileName);

            String convertType = getConvertType(extension);
            if (".ofd".equals(targetExt)) {
                if ("PDF-OCR".equals(convertType)) {
                    convertType = "OFD-DOUBLE";
                } else if (convertType.endsWith("-PDF")) {
                    convertType = convertType.substring(0, convertType.length() - 4) + "-OFD";
                } else if ("UNKNOWN".equals(convertType)) {
                    convertType = "OFD-DOUBLE";
                }
            }
            record.setConvertType(convertType);
            record.setStatus("0");
            record.setProgress(0);
            record.setCreateBy("desktop");

            pdfConvertRecordService.insertPdfConvertRecord(record);

            // 使用线程池异步转换
            final PdfConvertRecord finalRecord = record;
            final String finalOutputDir = actualOutputDir;
            final String finalOutputMode = outputMode;
            final int finalFolderCreateRule = folderCreateRule;
            final int finalPdfNameSource = pdfNameSource;
            final String tempSourcePath = sourcePath;
            final String finalTargetFileName = targetFileName;
            // 设置取消标志
            taskCancelFlags.put(String.valueOf(record.getId()), false);

            taskExecutor.submit(() -> {
                // 保存线程引用
                Thread currentThread = Thread.currentThread();
                taskThreads.put(String.valueOf(record.getId()), currentThread);

                try {
                    log.info("开始转换: {}, 输出模式: {}, 文件夹规则: {}, 命名来源: {}",
                            finalRecord.getSourceFileName(), finalOutputMode, finalFolderCreateRule, finalPdfNameSource);
                    finalRecord.setStatus("1");
                    pdfConvertRecordService.updatePdfConvertRecord(finalRecord);

                    long startTime = System.currentTimeMillis();

                    // 检查是否被取消
                    if (Boolean.TRUE.equals(taskCancelFlags.get(String.valueOf(finalRecord.getId())))) {
                        log.info("任务被取消，停止转换: {}", finalRecord.getSourceFileName());
                        finalRecord.setStatus("4"); // 4表示已取消
                        finalRecord.setFailReason("用户取消");
                        pdfConvertRecordService.updatePdfConvertRecord(finalRecord);
                        return;
                    }

                    // 检查线程中断状态
                    if (Thread.currentThread().isInterrupted()) {
                        log.info("线程被中断，停止转换: {}", finalRecord.getSourceFileName());
                        finalRecord.setStatus("4");
                        finalRecord.setFailReason("用户取消");
                        pdfConvertRecordService.updatePdfConvertRecord(finalRecord);
                        return;
                    }

                    // 规则1和2不传递outputFileName，让convertToDirectory根据档号结构构建路径
                    if (finalFolderCreateRule == 1 || finalFolderCreateRule == 2) {
                        pdfConvertSimpleService.convertToDirectory(
                                finalRecord.getSourceFilePath(),
                                finalOutputDir,
                                finalRecord.getSourceFileName(),
                                finalOutputMode,
                                filenamePrefix,
                                filenameSuffix,
                                finalFolderCreateRule,
                                finalPdfNameSource,
                                null,
                                splitType,
                                splitStartPage,
                                splitEndPage
                        );
                    } else {
                        pdfConvertSimpleService.convertToDirectory(
                                finalRecord.getSourceFilePath(),
                                finalOutputDir,
                                finalRecord.getSourceFileName(),
                                finalOutputMode,
                                filenamePrefix,
                                filenameSuffix,
                                finalFolderCreateRule,
                                finalPdfNameSource,
                                finalTargetFileName,
                                splitType,
                                splitStartPage,
                                splitEndPage
                        );
                    }
                    long duration = System.currentTimeMillis() - startTime;

                    log.info("转换完成: {}, 耗时: {}ms", finalRecord.getSourceFileName(), duration);
                    finalRecord.setStatus("2");
                    finalRecord.setProgress(100);
                    pdfConvertRecordService.updatePdfConvertRecord(finalRecord);

                } catch (Exception e) {
                    log.error("转换失败: {}", finalRecord.getSourceFileName(), e);
                    finalRecord.setStatus("3");
                    String errorMsg = e.getMessage();
                    if (errorMsg != null && errorMsg.length() > MAX_FAIL_REASON_LENGTH) {
                        errorMsg = errorMsg.substring(0, MAX_FAIL_REASON_LENGTH) + "...";
                    }
                    finalRecord.setFailReason(errorMsg);
                    pdfConvertRecordService.updatePdfConvertRecord(finalRecord);
                } finally {
                    // 清理线程引用
                    taskThreads.remove(String.valueOf(finalRecord.getId()));

                    // 清理临时上传文件
                    try {
                        File tempFile = new File(tempSourcePath);
                        if (tempFile.exists()) {
                            if (tempFile.delete()) {
                                log.debug("已清理临时上传文件: {}", tempSourcePath);
                            } else {
                                log.warn("清理临时上传文件失败: {}", tempSourcePath);
                            }
                        }
                    } catch (Exception ex) {
                        log.warn("清理临时上传文件异常: {}", tempSourcePath, ex);
                    }
                }
            });

            Map<String, Object> result = new HashMap<>();
            result.put("taskId", record.getId());
            result.put("message", "转换任务已提交");
            return AjaxResult.success(result);

        } catch (Exception e) {
            log.error("上传失败", e);
            return AjaxResult.error("上传失败: " + e.getMessage());
        }
    }

    /**
     * 获取转换记录列表
     */
    @GetMapping("/records")
    public TableDataInfo listRecords(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        startPage();
        List<PdfConvertRecord> list = pdfConvertRecordService.selectPdfConvertRecordList(new PdfConvertRecord());
        return getDataTable(list);
    }

    /**
     * 获取单条记录
     */
    @GetMapping("/records/{id}")
    public AjaxResult getRecord(@PathVariable Long id) {
        return AjaxResult.success(pdfConvertRecordService.selectPdfConvertRecordById(id));
    }

    /**
     * 删除记录
     */
    @DeleteMapping("/records/{id}")
    public AjaxResult deleteRecord(@PathVariable Long id) {
        return toAjax(pdfConvertRecordService.deletePdfConvertRecordById(id));
    }

    /**
     * 下载转换后的文件
     */
    @GetMapping("/download/{id}")
    public void downloadFile(@PathVariable Long id, HttpServletResponse response) {
        PdfConvertRecord record = pdfConvertRecordService.selectPdfConvertRecordById(id);
        if (record == null || StringUtils.isEmpty(record.getTargetFilePath())) {
            try {
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":500,\"msg\":\"记录不存在\"}");
            } catch (IOException e) {
                log.error("响应失败", e);
            }
            return;
        }

        File file = new File(record.getTargetFilePath());
        if (!file.exists()) {
            try {
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":500,\"msg\":\"文件不存在\"}");
            } catch (IOException e) {
                log.error("响应失败", e);
            }
            return;
        }

        try {
            response.setContentType("application/octet-stream");
            String encodedFileName = URLEncoder.encode(record.getTargetFileName(), StandardCharsets.UTF_8.name())
                    .replace("+", "%20");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + encodedFileName + "\"");
            response.setContentLengthLong(file.length());

            try (InputStream is = new FileInputStream(file);
                 OutputStream os = response.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
                os.flush();
            }
        } catch (IOException e) {
            log.error("下载失败", e);
        }
    }

    /**
     * 获取系统状态
     */
    @GetMapping("/status")
    public AjaxResult getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("uploadDir", getFileUploadDir());
        status.put("outputDir", getOutputDir());
        status.put("recordCount", pdfConvertRecordService.selectPdfConvertRecordList(new PdfConvertRecord()).size());
        return AjaxResult.success(status);
    }

    /**
     * 渲染本地 PDF/图片文件为预览图，供桌面端档案著录框选使用。
     */
    @GetMapping("/archive-preview")
    public void archivePreview(@RequestParam("path") String path,
                               @RequestParam(value = "page", defaultValue = "1") int page,
                               HttpServletResponse response) throws IOException {
        File source = new File(path);
        if (!source.exists() || !source.isFile()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "文件不存在");
            return;
        }

        BufferedImage image = renderFilePage(source, Math.max(1, page));
        if (image == null) {
            response.sendError(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "不支持预览该文件");
            return;
        }

        response.setContentType("image/png");
        response.setHeader("Cache-Control", "no-store");
        ImageIO.write(image, "png", response.getOutputStream());
    }

    @GetMapping("/archive-page-count")
    public AjaxResult archivePageCount(@RequestParam("path") String path) {
        File source = new File(path);
        if (!source.exists() || !source.isFile()) {
            return AjaxResult.error("文件不存在");
        }

        try {
            Map<String, Object> data = new HashMap<>();
            data.put("pageCount", getArchivePageCount(source));
            return AjaxResult.success(data);
        } catch (Exception e) {
            log.error("读取文件页数失败: {}", path, e);
            return AjaxResult.error("读取页数失败: " + e.getMessage());
        }
    }

    /**
     * 对本地文件预览坐标中的矩形区域执行 OCR。
     */
    @PostMapping("/archive-ocr-region")
    public AjaxResult archiveOcrRegion(@RequestParam("path") String path,
                                       @RequestParam(value = "page", defaultValue = "1") int page,
                                       @RequestParam("x") double x,
                                       @RequestParam("y") double y,
                                       @RequestParam("width") double width,
                                       @RequestParam("height") double height,
                                       @RequestParam("displayWidth") double displayWidth,
                                       @RequestParam("displayHeight") double displayHeight) {
        File source = new File(path);
        if (!source.exists() || !source.isFile()) {
            return AjaxResult.error("文件不存在");
        }
        if (width < 4 || height < 4 || displayWidth <= 0 || displayHeight <= 0) {
            return AjaxResult.error("请选择有效的文字区域");
        }

        try {
            BufferedImage pageImage = renderFilePage(source, Math.max(1, page));
            if (pageImage == null) {
                return AjaxResult.error("不支持对该文件进行区域识别");
            }

            // 将前端传来的 CSS 坐标换算为 300 DPI 预览图的实际像素坐标
            int regionX = clamp((int) Math.round(x * pageImage.getWidth() / displayWidth) - ARCHIVE_OCR_PADDING, 0, pageImage.getWidth() - 1);
            int regionY = clamp((int) Math.round(y * pageImage.getHeight() / displayHeight) - ARCHIVE_OCR_PADDING, 0, pageImage.getHeight() - 1);
            int regionW = clamp((int) Math.round(width * pageImage.getWidth() / displayWidth) + 2 * ARCHIVE_OCR_PADDING, 1, pageImage.getWidth() - regionX);
            int regionH = clamp((int) Math.round(height * pageImage.getHeight() / displayHeight) + 2 * ARCHIVE_OCR_PADDING, 1, pageImage.getHeight() - regionY);

            JsonNode pageOcrResult = getCachedArchivePageOcr(source, Math.max(1, page), pageImage);
            String text = extractOcrTextInRegion(pageOcrResult, regionX, regionY, regionW, regionH);
            if (shouldUsePreciseRegionOcr(text, regionW, regionH, pageImage)) {
                String preciseText = recognizeArchiveCroppedRegion(pageImage, regionX, regionY, regionW, regionH);
                if (StringUtils.isNotEmpty(preciseText)) {
                    text = preciseText;
                }
            }

            Map<String, Object> data = new HashMap<>();
            data.put("text", text);
            return AjaxResult.success(data);
        } catch (Exception e) {
            log.error("区域 OCR 失败: {}", path, e);
            return AjaxResult.error("区域识别失败: " + e.getMessage());
        }
    }

    /**
     * 对前端上传的裁剪图片执行 OCR，支持未落盘的拖拽图片文件。
     */
    @PostMapping("/archive-ocr-image")
    public AjaxResult archiveOcrImage(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return AjaxResult.error("裁剪图片为空");
        }

        File tempFile = null;
        try {
            tempFile = File.createTempFile("archive_ocr_upload_", ".png");
            file.transferTo(tempFile);

            LocalOcrClient ocrClient = new LocalOcrClient();
            JsonNode result = ocrClient.recognizeImage(tempFile.getAbsolutePath());

            // 添加详细调试日志
            log.info("裁剪图片OCR原始结果: {}", result.toString());
            String text = normalizeArchiveOcrText(extractOcrText(result));
            log.info("裁剪图片OCR提取文本: '{}', 长度: {}", text, text.length());

            Map<String, Object> data = new HashMap<>();
            data.put("text", text);
            return AjaxResult.success(data);
        } catch (Exception e) {
            log.error("上传区域 OCR 失败", e);
            return AjaxResult.error("区域识别失败: " + e.getMessage());
        } finally {
            if (tempFile != null && tempFile.exists() && !tempFile.delete()) {
                log.debug("临时上传 OCR 文件删除失败: {}", tempFile.getAbsolutePath());
            }
        }
    }

    /**
     * 直接转换输入目录中的文件（无需上传）
     * 适用于Electron桌面端，直接读取本地文件系统
     */
    @PostMapping("/convert-directory")
    public AjaxResult convertDirectory(
            @RequestParam("inputDir") String inputDir,
            @RequestParam(value = "outputDir", required = false) String outputDir,
            @RequestParam(value = "outputMode", required = false, defaultValue = "double") String outputMode,
            @RequestParam(value = "filenamePrefix", required = false) String filenamePrefix,
            @RequestParam(value = "filenameSuffix", required = false) String filenameSuffix,
            @RequestParam(value = "sortRule", required = false, defaultValue = "3") int sortRule,
            @RequestParam(value = "mergeFilename", required = false) String mergeFilename,
            @RequestParam(value = "folderCreateRule", required = false, defaultValue = "4") int folderCreateRule,
            @RequestParam(value = "pdfNameSource", required = false, defaultValue = "1") int pdfNameSource,
            @RequestParam(value = "preserveInputDir", required = false) String preserveInputDir,
            @RequestParam(value = "splitType", required = false) String splitType,
            @RequestParam(value = "splitStartPage", required = false) Integer splitStartPage,
            @RequestParam(value = "splitEndPage", required = false) Integer splitEndPage) {

        log.info("收到目录转换请求: inputDir={}, outputDir={}, outputMode={}, prefix={}, suffix={}, sortRule={}, mergeFilename={}, folderCreateRule={}, pdfNameSource={}, preserveInputDir={}",
                inputDir, outputDir, outputMode, filenamePrefix, filenameSuffix, sortRule, mergeFilename, folderCreateRule, pdfNameSource, preserveInputDir);

        try {
            if (StringUtils.isEmpty(inputDir)) {
                log.warn("输入目录为空");
                return AjaxResult.error("输入目录不能为空");
            }

            File inputDirectory = new File(inputDir);
            if (!inputDirectory.exists()) {
                log.warn("输入目录不存在: {}", inputDir);
                return AjaxResult.error("输入目录不存在: " + inputDir);
            }
            if (!inputDirectory.isDirectory()) {
                log.warn("输入路径不是目录: {}", inputDir);
                return AjaxResult.error("输入路径不是有效目录: " + inputDir);
            }

            // 检查目录是否可读
            if (!inputDirectory.canRead()) {
                log.warn("无法读取输入目录: {}", inputDir);
                return AjaxResult.error("无法读取输入目录，请检查权限: " + inputDir);
            }

        // 确定输出目录
        String finalOutputDir = StringUtils.isNotEmpty(outputDir) ? outputDir : getOutputDir();
        File outDir = new File(finalOutputDir);
        if (!outDir.exists()) {
            outDir.mkdirs();
        }

        // 支持的文件扩展名
        List<String> supportedExts = Arrays.asList(".pdf", ".jpg", ".jpeg", ".tif", ".tiff", ".png", ".bmp",
                ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".ofd");

        // 扫描目录中的文件
        // 所有规则都递归扫描子目录，只是输出目录结构不同
        List<File> fileList = new ArrayList<>();
        File excludedScanDir = isChildPath(outDir, inputDirectory) ? outDir : null;
        scanDirectoryRecursive(inputDirectory, supportedExts, fileList, excludedScanDir);
        log.info("递归扫描目录完成，共找到 {} 个文件", fileList.size());

        if (fileList.isEmpty()) {
            return AjaxResult.error("目录中没有支持的文件");
        }

        log.info("开始批量转换目录: {}, 文件数: {}, 输出目录: {}, 模式: {}, 规则: {}",
                inputDir, fileList.size(), finalOutputDir, outputMode, folderCreateRule);

        // 【新增】处理合并模式：先排序文件，然后逐个转换，最后合并
        // 支持 merge (PDF) 和 merge_ofd (OFD) 两种合并模式
        final boolean isMergeMode = "merge".equals(outputMode) || "merge_ofd".equals(outputMode);
        final List<File> sortedFiles = new ArrayList<>(fileList);

        // 根据排序规则对文件进行排序
        sortFiles(sortedFiles, sortRule);
        log.info("文件排序完成，规则: {}，共 {} 个文件", sortRule, sortedFiles.size());

        // 使用线程池异步处理所有文件
        final String finalOutputDirPath = finalOutputDir;

        // 规则5：使用输入目录作为基准目录来计算相对路径
        final String finalPreserveInputDir = inputDir;
        final String finalInputDir = inputDir;
        final int finalFolderCreateRule = folderCreateRule;
        final int finalPdfNameSource = pdfNameSource;

        // 生成任务ID并设置取消标志
        final String taskId = inputDir + "_" + System.currentTimeMillis();
        currentBatchTaskId = taskId;
        taskCancelFlags.put(taskId, false);
        log.info("开始批量转换任务: taskId={}", taskId);

        taskExecutor.submit(() -> {
            try {
            // 【修改】合并模式下按件号层（最后一级目录）分组处理
            if (isMergeMode) {
                processMergeByLastLevelDirectory(sortedFiles, finalOutputDirPath, finalPreserveInputDir,
                        finalInputDir, filenamePrefix, filenameSuffix, finalFolderCreateRule,
                        sortRule, taskId, outputMode);
            } else {
                // 非合并模式：逐个转换
                processIndividualFiles(sortedFiles, finalOutputDirPath, finalPreserveInputDir,
                        finalInputDir, filenamePrefix, filenameSuffix, finalFolderCreateRule,
                        finalPdfNameSource, outputMode, taskId, splitType, splitStartPage, splitEndPage);
            }

            // 检查是否因取消而结束
            boolean wasCancelled = Boolean.TRUE.equals(taskCancelFlags.get(taskId));
            if (wasCancelled) {
                log.info("任务已取消: taskId={}", taskId);
            } else {
                log.info("目录转换完成: {}", inputDir);
            }
            } finally {
                // 清除任务状态
                taskCancelFlags.remove(taskId);
                if (taskId.equals(currentBatchTaskId)) {
                    currentBatchTaskId = null;
                }
                log.debug("任务结束，清除状态: taskId={}", taskId);
            }
        });

        Map<String, Object> result = new HashMap<>();
        result.put("fileCount", sortedFiles.size());
        result.put("message", "已开始处理 " + sortedFiles.size() + " 个文件");
        return AjaxResult.success(result);
        } catch (Exception e) {
            log.error("目录转换请求处理失败: {}", inputDir, e);
            return AjaxResult.error("处理失败: " + e.getMessage());
        }
    }

    // ============ 辅助方法 ============

    private String getFileUploadDir() {
        String uploadDir = PdfUtilConfig.getPdfUploadDir();
        if (StringUtils.isEmpty(uploadDir)) {
            uploadDir = System.getProperty("user.home") + "/.pdfutil/upload";
        }
        File dir = new File(uploadDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return uploadDir;
    }

    private String getOutputDir() {
        String outputDir = PdfUtilConfig.getPdfOutputDir();
        if (StringUtils.isEmpty(outputDir)) {
            outputDir = System.getProperty("user.home") + "/.pdfutil/output";
        }
        File dir = new File(outputDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return outputDir;
    }

    private BufferedImage renderFilePage(File source, int page) throws IOException {
        String extension = getFileExtension(source.getName()).toLowerCase();
        if ("pdf".equals(extension)) {
            try (PDDocument document = PDDocument.load(source)) {
                int pageIndex = Math.min(Math.max(page - 1, 0), document.getNumberOfPages() - 1);
                PDFRenderer renderer = new PDFRenderer(document);
                return renderer.renderImageWithDPI(pageIndex, ARCHIVE_PREVIEW_DPI, ImageType.RGB);
            }
        }
        if ("ofd".equals(extension)) {
            try {
                // 使用单页渲染，避免全量导出所有页面
                int pageIndex = Math.max(page - 1, 0);
                return com.pdfutil.common.core.utils.OfdSinglePageRenderer.renderPage(
                    source.getAbsolutePath(),
                    pageIndex,
                    ARCHIVE_PREVIEW_DPI
                );
            } catch (Exception e) {
                log.error("渲染OFD页面失败: file={}, page={}", source.getAbsolutePath(), page, e);
                throw new IOException("渲染OFD页面失败: " + e.getMessage(), e);
            }
        }

        if (Arrays.asList("jpg", "jpeg", "png", "bmp", "tif", "tiff").contains(extension)) {
            return ImageIO.read(source);
        }

        return null;
    }

    private int getArchivePageCount(File source) throws IOException {
        String extension = getFileExtension(source.getName()).toLowerCase();
        if ("pdf".equals(extension)) {
            try (PDDocument document = PDDocument.load(source)) {
                return Math.max(1, document.getNumberOfPages());
            }
        }
        if ("ofd".equals(extension)) {
            try (org.ofdrw.reader.OFDReader reader = new org.ofdrw.reader.OFDReader(source.toPath())) {
                return reader.getNumberOfPages();
            } catch (Exception e) {
                log.error("读取OFD页数失败", e);
                return 1;
            }
        }
        return 1;
    }

    private JsonNode getCachedArchivePageOcr(File source, int page, BufferedImage pageImage) throws Exception {
        String cacheKey = source.getAbsolutePath() + "|" + source.lastModified() + "|" + source.length() + "|" + page;
        ArchiveOcrCacheEntry cached = archiveOcrCache.get(cacheKey);
        if (cached != null) {
            cached.lastAccessTime = System.currentTimeMillis();
            log.debug("命中档案著录 OCR 缓存: {}", cacheKey);
            return cached.result;
        }

        synchronized (archiveOcrCache) {
            cached = archiveOcrCache.get(cacheKey);
            if (cached != null) {
                cached.lastAccessTime = System.currentTimeMillis();
                return cached.result;
            }

            File ocrImageFile = null;
            try {
                String extension = getFileExtension(source.getName()).toLowerCase();
                if ("pdf".equals(extension)) {
                    ocrImageFile = File.createTempFile("archive_ocr_page_", ".png");
                    ImageIO.write(pageImage, "png", ocrImageFile);
                } else {
                    ocrImageFile = source;
                }

                LocalOcrClient ocrClient = new LocalOcrClient();
                JsonNode result = ocrClient.recognizeImage(ocrImageFile.getAbsolutePath());
                archiveOcrCache.put(cacheKey, new ArchiveOcrCacheEntry(result));
                trimArchiveOcrCache();
                return result;
            } finally {
                if (ocrImageFile != null && !ocrImageFile.equals(source)
                        && ocrImageFile.exists() && !ocrImageFile.delete()) {
                    log.debug("临时整页 OCR 图片删除失败: {}", ocrImageFile.getAbsolutePath());
                }
            }
        }
    }

    private void trimArchiveOcrCache() {
        if (archiveOcrCache.size() <= ARCHIVE_OCR_CACHE_MAX_SIZE) {
            return;
        }

        List<Map.Entry<String, ArchiveOcrCacheEntry>> entries = new ArrayList<>(archiveOcrCache.entrySet());
        entries.sort(Comparator.comparingLong(entry -> entry.getValue().lastAccessTime));
        int removeCount = archiveOcrCache.size() - ARCHIVE_OCR_CACHE_MAX_SIZE;
        for (int i = 0; i < removeCount && i < entries.size(); i++) {
            archiveOcrCache.remove(entries.get(i).getKey());
        }
    }

    private String extractOcrTextInRegion(JsonNode result, int x, int y, int width, int height) {
        if (result == null) {
            return "";
        }

        List<OcrTextItem> matchedItems = new ArrayList<>();
        JsonNode pages = result.get("pages");
        if (pages != null && pages.isArray()) {
            for (JsonNode pageNode : pages) {
                collectOcrTextItemsInRegion(pageNode, x, y, width, height, matchedItems);
            }
        } else {
            collectOcrTextItemsInRegion(result, x, y, width, height, matchedItems);
        }

        matchedItems = sortArchiveOcrItemsByReadingOrder(matchedItems);

        StringBuilder text = new StringBuilder();
        int previousCenterY = -1;
        for (OcrTextItem item : matchedItems) {
            if (StringUtils.isEmpty(item.text)) {
                continue;
            }
            if (text.length() > 0) {
                text.append(shouldJoinWithoutSpace(text, item.text, previousCenterY, item.centerY) ? "" : " ");
            }
            text.append(item.text);
            previousCenterY = item.centerY;
        }

        return normalizeArchiveOcrText(text.toString());
    }

    private boolean shouldUsePreciseRegionOcr(String cachedText, int regionW, int regionH, BufferedImage pageImage) {
        // 小区域默认走整页 OCR 缓存；只有缓存没有命中文本时才补一次裁剪 OCR。
        return StringUtils.isEmpty(cachedText);
    }

    private String recognizeArchiveCroppedRegion(BufferedImage pageImage, int x, int y, int width, int height) throws Exception {
        File cropFile = null;
        try {
            BufferedImage cropped = pageImage.getSubimage(x, y, width, height);
            cropFile = File.createTempFile("archive_ocr_region_precise_", ".png");
            ImageIO.write(cropped, "png", cropFile);

            LocalOcrClient ocrClient = new LocalOcrClient();
            JsonNode result = ocrClient.recognizeImage(cropFile.getAbsolutePath());
            return normalizeArchiveOcrText(extractOcrText(result));
        } finally {
            if (cropFile != null && cropFile.exists() && !cropFile.delete()) {
                log.debug("临时精确 OCR 裁剪文件删除失败: {}", cropFile.getAbsolutePath());
            }
        }
    }

    private void collectOcrTextItemsInRegion(JsonNode node, int x, int y, int width, int height, List<OcrTextItem> items) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                collectOcrTextItemsInRegion(item, x, y, width, height, items);
            }
            return;
        }
        if (!node.isObject() || !node.has("text")) {
            return;
        }

        int[] box = extractOcrTextBox(node);
        if (box == null || intersectsRegion(box, x, y, width, height)) {
            String value = node.get("text").asText("");
            if (StringUtils.isNotEmpty(value)) {
                int left = box == null ? x : box[0];
                int top = box == null ? y : box[1];
                int right = box == null ? left : box[2];
                int bottom = box == null ? top : box[3];
                items.add(new OcrTextItem(value, left, top, right, bottom));
            }
        }
    }

    private void collectOcrTextItems(JsonNode node, List<OcrTextItem> items) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                collectOcrTextItems(item, items);
            }
            return;
        }
        if (!node.isObject() || !node.has("text")) {
            return;
        }

        String value = node.get("text").asText("");
        if (StringUtils.isEmpty(value)) {
            return;
        }

        int[] box = extractOcrTextBox(node);
        int left = box == null ? 0 : box[0];
        int top = box == null ? 0 : box[1];
        int right = box == null ? left : box[2];
        int bottom = box == null ? top : box[3];
        items.add(new OcrTextItem(value, left, top, right, bottom));
    }

    private List<OcrTextItem> sortArchiveOcrItemsByReadingOrder(List<OcrTextItem> items) {
        if (items == null || items.isEmpty()) {
            return items;
        }

        List<OcrTextItem> sorted = new ArrayList<>(items);
        sorted.sort(Comparator
                .comparingInt((OcrTextItem item) -> item.centerY)
                .thenComparingInt(item -> item.left));

        List<List<OcrTextItem>> lines = new ArrayList<>();
        List<OcrTextItem> currentLine = new ArrayList<>();
        int lineCenterY = Integer.MIN_VALUE;
        int lineHeight = 0;

        for (OcrTextItem item : sorted) {
            if (currentLine.isEmpty()) {
                currentLine.add(item);
                lineCenterY = item.centerY;
                lineHeight = Math.max(1, item.height);
                continue;
            }

            // 【优化】300 DPI 下，最小容差从 12 提高到 20，以适应更高的像素密度
            int tolerance = Math.max(20, (int) Math.round(Math.max(lineHeight, item.height) * 0.6));
            if (Math.abs(item.centerY - lineCenterY) <= tolerance) {
                currentLine.add(item);
                lineCenterY = (lineCenterY * (currentLine.size() - 1) + item.centerY) / currentLine.size();
                lineHeight = Math.max(lineHeight, item.height);
            } else {
                currentLine.sort(Comparator.comparingInt(i -> i.left));
                lines.add(currentLine);
                currentLine = new ArrayList<>();
                currentLine.add(item);
                lineCenterY = item.centerY;
                lineHeight = Math.max(1, item.height);
            }
        }

        if (!currentLine.isEmpty()) {
            currentLine.sort(Comparator.comparingInt(i -> i.left));
            lines.add(currentLine);
        }

        List<OcrTextItem> ordered = new ArrayList<>();
        for (List<OcrTextItem> line : lines) {
            ordered.addAll(line);
        }
        return ordered;
    }

    private int[] extractOcrTextBox(JsonNode node) {
        JsonNode region = node.has("text_region") ? node.get("text_region") : node.get("box");
        if (region == null || !region.isArray() || region.size() == 0) {
            return null;
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (JsonNode point : region) {
            if (!point.isArray() || point.size() < 2) {
                continue;
            }
            int px = (int) Math.round(point.get(0).asDouble());
            int py = (int) Math.round(point.get(1).asDouble());
            minX = Math.min(minX, px);
            minY = Math.min(minY, py);
            maxX = Math.max(maxX, px);
            maxY = Math.max(maxY, py);
        }

        if (minX == Integer.MAX_VALUE) {
            return null;
        }
        return new int[]{minX, minY, maxX, maxY};
    }

    private boolean intersectsRegion(int[] box, int x, int y, int width, int height) {
        int regionRight = x + width;
        int regionBottom = y + height;
        int overlapLeft = Math.max(box[0], x);
        int overlapTop = Math.max(box[1], y);
        int overlapRight = Math.min(box[2], regionRight);
        int overlapBottom = Math.min(box[3], regionBottom);
        if (overlapRight <= overlapLeft || overlapBottom <= overlapTop) {
            return false;
        }

        int boxArea = Math.max(1, (box[2] - box[0]) * (box[3] - box[1]));
        int overlapArea = (overlapRight - overlapLeft) * (overlapBottom - overlapTop);
        int centerX = (box[0] + box[2]) / 2;
        int centerY = (box[1] + box[3]) / 2;
        boolean centerInside = centerX >= x && centerX <= regionRight && centerY >= y && centerY <= regionBottom;
        
        // 【优化】300 DPI 下，重叠面积达到 10% 或中心点在区域内则认为命中
        // 避免 > 0 导致的邻近干扰
        return centerInside || (overlapArea >= boxArea * 0.1);
    }

    private boolean shouldJoinWithoutSpace(StringBuilder currentText, String nextText, int previousCenterY, int nextCenterY) {
        if (currentText.length() == 0 || StringUtils.isEmpty(nextText)) {
            return false;
        }

        char previousChar = currentText.charAt(currentText.length() - 1);
        char nextChar = nextText.charAt(0);
        
        // 【优化】300 DPI 下，垂直中心间距阈值从 24 提高到 40，以适应更高的像素密度
        if (Math.abs(nextCenterY - previousCenterY) > 40) {
            return false;
        }
        return isCjk(previousChar) && isCjk(nextChar);
    }

    private String extractOcrText(JsonNode result) {
        if (result == null) {
            return "";
        }

        List<OcrTextItem> items = new ArrayList<>();
        JsonNode pages = result.get("pages");
        if (pages != null && pages.isArray()) {
            for (JsonNode page : pages) {
                collectOcrTextItems(page, items);
            }
        } else {
            collectOcrTextItems(result, items);
        }
        items = sortArchiveOcrItemsByReadingOrder(items);

        StringBuilder text = new StringBuilder();
        int previousCenterY = -1;
        for (OcrTextItem item : items) {
            if (StringUtils.isEmpty(item.text)) {
                continue;
            }
            if (text.length() > 0) {
                text.append(shouldJoinWithoutSpace(text, item.text, previousCenterY, item.centerY) ? "" : " ");
            }
            text.append(item.text);
            previousCenterY = item.centerY;
        }

        return normalizeArchiveOcrText(text.toString());
    }

    private String normalizeArchiveOcrText(String rawText) {
        if (rawText == null) {
            return "";
        }

        String text = rawText
                .replace('\u00A0', ' ')
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s+", " ")
                .trim();

        // 中文之间不保留空格：中 华 人 民 -> 中华人民
        text = text.replaceAll("([\\p{IsHan}])\\s+([\\p{IsHan}])", "$1$2");
        // 中文和常见中文标点之间不保留空格
        text = text.replaceAll("([\\p{IsHan}])\\s+([，。；：、！？）】》])", "$1$2");
        text = text.replaceAll("([（【《])\\s+([\\p{IsHan}])", "$1$2");
        // 数字和中文量词/年月日之间不保留空格
        text = text.replaceAll("([0-9])\\s+([年月日号页件卷])", "$1$2");
        text = text.replaceAll("([第])\\s+([0-9一二三四五六七八九十百千万])", "$1$2");
        // 保留英文单词之间空格，但压紧常见文件名、版本号、路径符号周围空格
        text = text.replaceAll("\\s*([_./\\\\:-])\\s*", "$1");
        text = text.replaceAll("([A-Za-z])\\s+([0-9])", "$1 $2");
        return text.trim();
    }

    private boolean isCjk(char value) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(value);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String getFileExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "";
        }
        int lastDotIndex = fileName.lastIndexOf(".");
        if (lastDotIndex == -1 || lastDotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(lastDotIndex + 1);
    }

    private String getFileNameWithoutExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "unnamed";
        }
        int lastDotIndex = fileName.lastIndexOf(".");
        if (lastDotIndex == -1) {
            return fileName;
        }
        return fileName.substring(0, lastDotIndex);
    }

    /**
     * 根据输入目录路径生成合并文件名
     * 例如：D:\J001\WS\BGS\0005 → J001-WS-BGS-0005
     *
     * @param inputDir 输入目录路径
     * @return 合并文件名（不含.pdf扩展名）
     */
    private String buildMergeNameFromInputDir(String inputDir) {
        if (StringUtils.isEmpty(inputDir)) {
            return "merged";
        }

        // 标准化路径
        String normalizedPath = inputDir.replace("\\", "/");
        // 去掉末尾的斜杠
        while (normalizedPath.endsWith("/")) {
            normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
        }

        // 去掉盘符（如 D:/）
        int colonIndex = normalizedPath.indexOf(':');
        if (colonIndex >= 0 && colonIndex < normalizedPath.length() - 1) {
            normalizedPath = normalizedPath.substring(colonIndex + 1);
        }
        // 去掉开头的斜杠
        while (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }

        // 获取路径的各个目录名
        String[] parts = normalizedPath.split("/");

        // 用"-"连接所有目录名
        StringBuilder nameBuilder = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (nameBuilder.length() > 0) {
                nameBuilder.append("-");
            }
            nameBuilder.append(part);
        }

        String result = nameBuilder.toString();
        log.debug("根据输入目录生成合并文件名: inputDir={}, result={}", inputDir, result);
        return result.isEmpty() ? "merged" : result;
    }

    private String getConvertType(String extension) {
        switch (extension) {
            case "ofd": return "OFD-DOUBLE";
            case "pdf": return "PDF-OCR";
            case "jpg": return "JPG-PDF";
            case "jpeg": return "JPEG-PDF";
            case "tif":
            case "tiff": return "TIF-PDF";
            case "png": return "PNG-PDF";
            case "bmp": return "BMP-PDF";
            case "doc": return "DOC-PDF";
            case "docx": return "DOCX-PDF";
            case "xls": return "XLS-PDF";
            case "xlsx": return "XLSX-PDF";
            case "ppt": return "PPT-PDF";
            case "pptx": return "PPTX-PDF";
            default: return "UNKNOWN";
        }
    }

    /**
     * 递归扫描目录中的所有文件
     *
     * @param directory 要扫描的目录
     * @param supportedExts 支持的文件扩展名列表
     * @param fileList 收集的文件列表
     */
    private void scanDirectoryRecursive(File directory, List<String> supportedExts, List<File> fileList, File excludedDirectory) {
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            return;
        }
        if (isSameOrChildPath(directory, excludedDirectory)) {
            log.debug("跳过输出目录扫描: {}", directory.getAbsolutePath());
            return;
        }

        File[] items = directory.listFiles();
        if (items == null) {
            return;
        }
        Arrays.sort(items, this::compareFilesByNameNatural);

        for (File item : items) {
            if (item.isDirectory()) {
                // 递归扫描子目录
                scanDirectoryRecursive(item, supportedExts, fileList, excludedDirectory);
            } else {
                // 检查文件扩展名
                String ext = getFileExtension(item.getName()).toLowerCase();
                if (supportedExts.contains("." + ext)) {
                    fileList.add(item);
                }
            }
        }
    }

    /**
     * 根据排序规则对文件列表进行排序
     *
     * 【修复】针对档号格式优化排序，确保序号部分按数字排序
     * 档号格式：WS·2025*D10·A01-0002，最后部分是序号
     *
     * @param files 文件列表
     * @param sortRule 排序规则：1-日期升序，2-日期降序，3-文件名升序（默认），4-文件名降序
     */
    private void sortFiles(List<File> files, int sortRule) {
        switch (sortRule) {
            case 1:
                // 按照原始图片文件的日期升序
                files.sort(Comparator.comparingLong(File::lastModified)
                        .thenComparing(this::compareFilesByNameNatural));
                log.debug("按文件日期升序排序");
                break;
            case 2:
                // 按照原始图片文件的日期降序
                files.sort((f1, f2) -> {
                    int dateCompare = Long.compare(f2.lastModified(), f1.lastModified());
                    return dateCompare != 0 ? dateCompare : compareFilesByNameNatural(f1, f2);
                });
                log.debug("按文件日期降序排序");
                break;
            case 3:
            default:
                // 【修复】按文件名自然升序排序，针对档号格式优化
                files.sort(this::compareFilesByNameNatural);
                log.info("按文件名称自然升序排序完成，文件数: {}", files.size());
                // 输出排序结果用于调试
                if (log.isDebugEnabled()) {
                    for (int i = 0; i < Math.min(10, files.size()); i++) {
                        log.debug("排序后第{}个文件: {}", i+1, files.get(i).getName());
                    }
                }
                break;
            case 4:
                // 按文件名自然降序排序，适合需要从大到小合并页序的场景
                files.sort((f1, f2) -> compareFilesByNameNatural(f2, f1));
                log.debug("按文件名称自然降序排序");
                break;
        }
    }

    private boolean isSameOrChildPath(File path, File parentPath) {
        if (path == null || parentPath == null) {
            return false;
        }
        try {
            String child = path.getCanonicalPath();
            String parent = parentPath.getCanonicalPath();
            return child.equals(parent) || child.startsWith(parent + File.separator);
        } catch (IOException e) {
            log.warn("路径比较失败: path={}, parent={}", path, parentPath, e);
            return false;
        }
    }

    private boolean isChildPath(File path, File parentPath) {
        if (path == null || parentPath == null) {
            return false;
        }
        try {
            String child = path.getCanonicalPath();
            String parent = parentPath.getCanonicalPath();
            return !child.equals(parent) && child.startsWith(parent + File.separator);
        } catch (IOException e) {
            log.warn("路径父子关系比较失败: path={}, parent={}", path, parentPath, e);
            return false;
        }
    }

    /**
     * 比较文件名的自然排序
     * 【修复】针对档号格式优化，确保序号按数字排序
     * 【关键】支持不同分隔符顺序：WS·2025*D10·A01-0002 和 WS*2025·D10·A01-0003
     *
     * @param f1 第一个文件
     * @param f2 第二个文件
     * @return 比较结果
     */
    private int compareFilesByNameNatural(File f1, File f2) {
        String name1 = f1.getName();
        String name2 = f2.getName();

        // 【修复】优先提取序号进行数字排序
        Integer seq1 = extractArchiveSequenceNumber(name1);
        Integer seq2 = extractArchiveSequenceNumber(name2);

        if (seq1 != null && seq2 != null) {
            // 两个都是档号文件，按序号数字排序
            int seqCompare = Integer.compare(seq1, seq2);
            if (seqCompare != 0) {
                log.info("档号序号比较: {} (序号:{}) vs {} (序号:{}) = 按序号排序:{}",
                         name1, seq1, name2, seq2, seqCompare > 0 ? "前者大" : "后者大");
                return seqCompare;
            }
            // 序号相同时，按文件名字典序（作为第二排序条件）
            log.debug("档号序号相同({})，按文件名字典序排序", seq1);
            return name1.compareTo(name2);
        }

        // 【修复】只有一个能提取序号的情况
        if (seq1 != null) {
            log.info("只有前者是档号格式(序号:{})，优先排序", seq1);
            return -1;  // 档号文件排前面
        }
        if (seq2 != null) {
            log.info("只有后者是档号格式(序号:{})，优先排序", seq2);
            return 1;   // 档号文件排前面
        }

        // 都不是档号文件，使用常规自然排序
        log.debug("都不是档号格式，使用自然排序");
        return compareNatural(name1, name2);
    }

    /**
     * 【新增】从档号文件名中提取序号
     * 【修复】支持任意分隔符组合：·、*、-，甚至没有分隔符的情况
     * 档号格式示例：
     * - WS·2025*D10·A01-0002 → 提取 0002 (序号 2)
     * - 档号1 → 提取 1 (序号 1)
     *
     * @param fileName 文件名
     * @return 提取的序号，如果不是档号格式返回null
     */
    private Integer extractArchiveSequenceNumber(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return null;
        }

        // 去掉文件扩展名
        String name = fileName;
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            name = fileName.substring(0, dotIndex);
        }

        log.debug("开始提取序号: {}", name);

        // 【修复】模式1：尝试匹配带有分隔符的最后数字（如 WS·2025*D10·A01-0002）
        java.util.regex.Pattern pattern1 = java.util.regex.Pattern.compile("[-*·_](\\d+)$");
        java.util.regex.Matcher matcher1 = pattern1.matcher(name);

        if (matcher1.find()) {
            try {
                String seqStr = matcher1.group(1);
                Integer seq = Integer.parseInt(seqStr);
                log.info("成功提取序号: {} → {}", name, seq);
                return seq;
            } catch (NumberFormatException e) {
                log.debug("解析序号失败: {}", name);
            }
        }

        // 【新增】模式2：提取字符串末尾的连续数字（容错处理，例如"档号1"）
        java.util.regex.Pattern pattern2 = java.util.regex.Pattern.compile("(\\d+)$");
        java.util.regex.Matcher matcher2 = pattern2.matcher(name);

        if (matcher2.find()) {
            try {
                String seqStr = matcher2.group(1);
                Integer seq = Integer.parseInt(seqStr);
                log.info("容错提取序号: {} → {}", name, seq);
                return seq;
            } catch (NumberFormatException e) {
                log.debug("容错解析序号失败: {}", name);
            }
        }

        log.debug("未能提取序号: {}", name);
        return null;
    }

    private int compareNatural(String s1, String s2) {
        if (s1 == null && s2 == null) {
            return 0;
        }
        if (s1 == null) {
            return -1;
        }
        if (s2 == null) {
            return 1;
        }

        int i = 0;
        int j = 0;
        int n1 = s1.length();
        int n2 = s2.length();

        while (i < n1 && j < n2) {
            char c1 = s1.charAt(i);
            char c2 = s2.charAt(j);

            if (Character.isDigit(c1) && Character.isDigit(c2)) {
                int start1 = i;
                int start2 = j;
                while (i < n1 && Character.isDigit(s1.charAt(i))) {
                    i++;
                }
                while (j < n2 && Character.isDigit(s2.charAt(j))) {
                    j++;
                }

                String num1 = s1.substring(start1, i);
                String num2 = s2.substring(start2, j);
                String normalizedNum1 = stripLeadingZeros(num1);
                String normalizedNum2 = stripLeadingZeros(num2);

                int lengthCompare = Integer.compare(normalizedNum1.length(), normalizedNum2.length());
                if (lengthCompare != 0) {
                    return lengthCompare;
                }

                int valueCompare = normalizedNum1.compareTo(normalizedNum2);
                if (valueCompare != 0) {
                    return valueCompare;
                }

                int rawLengthCompare = Integer.compare(num1.length(), num2.length());
                if (rawLengthCompare != 0) {
                    return rawLengthCompare;
                }
            } else {
                int charCompare = Character.compare(
                        Character.toLowerCase(c1),
                        Character.toLowerCase(c2));
                if (charCompare != 0) {
                    return charCompare;
                }
                i++;
                j++;
            }
        }

        return Integer.compare(n1 - i, n2 - j);
    }

    private String stripLeadingZeros(String value) {
        int index = 0;
        while (index < value.length() - 1 && value.charAt(index) == '0') {
            index++;
        }
        return value.substring(index);
    }

    /**
     * 合并多个PDF文件为一个PDF
     * 使用PDFBox的PDFMergerUtility（通过反射调用，避免直接依赖）
     *
     * @param pdfPaths 要合并的PDF文件路径列表
     * @param outputPath 合并后的PDF输出路径
     * @throws Exception 合并失败时抛出异常
     */
    private void mergePdfFiles(List<String> pdfPaths, String outputPath) throws Exception {
        log.info("开始合并PDF文件，共 {} 个文件", pdfPaths.size());

        try {
            // 使用反射调用PDFBox的PDFMergerUtility
            Class<?> mergerClass = Class.forName("org.apache.pdfbox.multipdf.PDFMergerUtility");
            Object merger = mergerClass.getDeclaredConstructor().newInstance();

            java.lang.reflect.Method setDestinationFileNameMethod = mergerClass.getMethod("setDestinationFileName", String.class);
            java.lang.reflect.Method addSourceMethod = mergerClass.getMethod("addSource", File.class);
            // PDFBox 2.x 使用无参数的 mergeDocuments() 方法
            java.lang.reflect.Method mergeDocumentsMethod = mergerClass.getMethod("mergeDocuments");

            setDestinationFileNameMethod.invoke(merger, outputPath);

            for (String pdfPath : pdfPaths) {
                File pdfFile = new File(pdfPath);
                if (pdfFile.exists()) {
                    addSourceMethod.invoke(merger, pdfFile);
                    log.debug("添加PDF到合并列表: {}", pdfPath);
                } else {
                    log.warn("PDF文件不存在，跳过: {}", pdfPath);
                }
            }

            // 执行合并
            mergeDocumentsMethod.invoke(merger);

            log.info("PDF合并成功: {}", outputPath);
        } catch (ClassNotFoundException e) {
            log.error("找不到PDFBox的PDFMergerUtility类，请确保PDFBox依赖已正确添加", e);
            throw new RuntimeException("PDF合并失败：缺少PDFBox依赖库", e);
        } catch (Exception e) {
            log.error("PDF合并失败", e);
            throw e;
        }
    }

    /**
     * 获取目录文件统计信息
     * 统计JPG按纸张规格分类（A1-A4）、PDF数量及所有文件页数
     *
     * @param path 目录路径
     * @param recursive 是否递归扫描子目录（默认false）
     * @return 统计结果
     */
    @GetMapping("/directory-stats")
    public AjaxResult getDirectoryStats(
            @RequestParam("path") String path,
            @RequestParam(value = "recursive", required = false, defaultValue = "false") boolean recursive) {
        try {
            log.info("获取目录统计信息: path={}, recursive={}", path, recursive);

            if (StringUtils.isEmpty(path)) {
                return AjaxResult.error("目录路径不能为空");
            }

            File directory = new File(path);
            if (!directory.exists()) {
                return AjaxResult.error("目录不存在: " + path);
            }
            if (!directory.isDirectory()) {
                return AjaxResult.error("路径不是有效目录: " + path);
            }

            Map<String, Object> stats;
            if (recursive) {
                stats = fileStatsService.getDirectoryStatsRecursive(path);
            } else {
                stats = fileStatsService.getDirectoryStats(path);
            }

            if (stats.containsKey("error")) {
                return AjaxResult.error((String) stats.get("error"));
            }

            log.info("目录统计完成: {}", stats);
            return AjaxResult.success(stats);

        } catch (Exception e) {
            log.error("获取目录统计信息失败", e);
            return AjaxResult.error("获取目录统计信息失败: " + e.getMessage());
        }
    }

    /**
     * 获取文件列表统计信息
     * 根据传入的文件路径列表统计JPG纸张规格、PDF页数等
     *
     * @param filePaths 文件路径列表（JSON数组格式）
     * @return 统计结果
     */
    @PostMapping("/file-list-stats")
    public AjaxResult getFileListStats(@RequestBody List<String> filePaths) {
        try {
            log.info("获取文件列表统计信息，文件数: {}", filePaths != null ? filePaths.size() : 0);

            if (filePaths == null || filePaths.isEmpty()) {
                return AjaxResult.error("文件列表不能为空");
            }

            Map<String, Object> stats = fileStatsService.getFileListStats(filePaths);

            if (stats.containsKey("error")) {
                return AjaxResult.error((String) stats.get("error"));
            }

            log.info("文件列表统计完成: {}", stats);
            return AjaxResult.success(stats);

        } catch (Exception e) {
            log.error("获取文件列表统计信息失败", e);
            return AjaxResult.error("获取文件列表统计信息失败: " + e.getMessage());
        }
    }

    /**
     * 获取PDF纸张规格分布分析
     * 分析PDF所有页的纸张规格分布，不再只检测第一页
     *
     * @param path PDF文件路径
     * @return 纸张规格分布结果
     */
    @GetMapping("/pdf-paper-size-distribution")
    public AjaxResult getPdfPaperSizeDistribution(@RequestParam("path") String path) {
        try {
            log.info("获取PDF纸张规格分布: path={}", path);

            if (StringUtils.isEmpty(path)) {
                return AjaxResult.error("PDF文件路径不能为空");
            }

            File pdfFile = new File(path);
            if (!pdfFile.exists()) {
                return AjaxResult.error("PDF文件不存在: " + path);
            }
            if (!pdfFile.isFile()) {
                return AjaxResult.error("路径不是有效文件: " + path);
            }

            String fileName = pdfFile.getName().toLowerCase();
            if (!fileName.endsWith(".pdf")) {
                return AjaxResult.error("文件不是PDF格式");
            }

            Map<String, Object> distribution = fileStatsService.analyzePdfPaperSizeDistribution(pdfFile);

            if (distribution.containsKey("error")) {
                return AjaxResult.error((String) distribution.get("error"));
            }

            log.info("PDF纸张规格分布分析完成: {}", distribution);
            return AjaxResult.success(distribution);

        } catch (Exception e) {
            log.error("获取PDF纸张规格分布失败", e);
            return AjaxResult.error("获取PDF纸张规格分布失败: " + e.getMessage());
        }
    }

    /**
     * 导出文件夹统计Excel
     * 导出子文件夹的纸张规格统计信息
     *
     * @param path 文件夹路径
     * @param response HTTP响应
     */
    @GetMapping("/export-folder-stats")
    public void exportFolderStats(
            @RequestParam("path") String path,
            HttpServletResponse response) {
        try {
            log.info("导出文件夹统计Excel: path={}", path);

            if (StringUtils.isEmpty(path)) {
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":500,\"msg\":\"目录路径不能为空\"}");
                return;
            }

            File directory = new File(path);
            if (!directory.exists() || !directory.isDirectory()) {
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":500,\"msg\":\"目录不存在或不是有效目录\"}");
                return;
            }

            // 获取子文件夹统计
            List<Map<String, Object>> subfolderStats = fileStatsService.getSubfolderStats(path);

            if (subfolderStats.isEmpty()) {
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":500,\"msg\":\"该目录下没有子文件夹\"}");
                return;
            }

            // 生成Excel
            String folderName = directory.getName();
            String fileName = folderName + "文件夹统计清单.xlsx";

            // 设置响应头（使用RFC 5987格式支持中文文件名）
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment;filename*=UTF-8''" +
                    URLEncoder.encode(fileName, "UTF-8"));

            // 使用Apache POI生成Excel
            org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet(folderName + "统计清单");

            // 创建样式
            org.apache.poi.ss.usermodel.CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
            headerStyle.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            headerStyle.setBorderTop(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            headerStyle.setBorderLeft(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            headerStyle.setBorderRight(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            org.apache.poi.ss.usermodel.Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            org.apache.poi.ss.usermodel.CellStyle dataStyle = workbook.createCellStyle();
            dataStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
            dataStyle.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            dataStyle.setBorderTop(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            dataStyle.setBorderLeft(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            dataStyle.setBorderRight(org.apache.poi.ss.usermodel.BorderStyle.THIN);

            // 标题行
            org.apache.poi.ss.usermodel.Row titleRow = sheet.createRow(0);
            org.apache.poi.ss.usermodel.Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue(folderName + "文件夹统计清单");
            org.apache.poi.ss.usermodel.CellStyle titleStyle = workbook.createCellStyle();
            titleStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
            org.apache.poi.ss.usermodel.Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            titleStyle.setFont(titleFont);
            titleCell.setCellStyle(titleStyle);
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 8));

            // 表头
            String[] headers = {"文件夹名称", "大于A0数量", "A0数量", "A1数量", "A2数量", "A3数量", "A4数量", "A4折合页数", "文件总数"};
            org.apache.poi.ss.usermodel.Row headerRow = sheet.createRow(1);
            for (int i = 0; i < headers.length; i++) {
                org.apache.poi.ss.usermodel.Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // 数据行
            int rowNum = 2;
            int totalLargerThanA0 = 0, totalA0 = 0, totalA1 = 0, totalA2 = 0, totalA3 = 0, totalA4 = 0, totalA4Equivalent = 0, totalFiles = 0;

            for (Map<String, Object> data : subfolderStats) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(rowNum++);

                row.createCell(0).setCellValue((String) data.get("folderName"));
                int largerThanA0 = data.containsKey("totalLargerThanA0") ? (Integer) data.get("totalLargerThanA0") : 0;
                int a0 = (Integer) data.get("totalA0");
                int a1 = (Integer) data.get("totalA1");
                int a2 = (Integer) data.get("totalA2");
                int a3 = (Integer) data.get("totalA3");
                int a4 = (Integer) data.get("totalA4");
                int a4Equiv = (Integer) data.get("a4Equivalent");
                int files = (Integer) data.get("totalFiles");

                row.createCell(1).setCellValue(largerThanA0);
                row.createCell(2).setCellValue(a0);
                row.createCell(3).setCellValue(a1);
                row.createCell(4).setCellValue(a2);
                row.createCell(5).setCellValue(a3);
                row.createCell(6).setCellValue(a4);
                row.createCell(7).setCellValue(a4Equiv);
                row.createCell(8).setCellValue(files);

                // 应用样式
                for (int i = 0; i < 9; i++) {
                    row.getCell(i).setCellStyle(dataStyle);
                }

                // 累计
                totalLargerThanA0 += largerThanA0;
                totalA0 += a0;
                totalA1 += a1;
                totalA2 += a2;
                totalA3 += a3;
                totalA4 += a4;
                totalA4Equivalent += a4Equiv;
                totalFiles += files;
            }

            // 合计行
            org.apache.poi.ss.usermodel.Row totalRow = sheet.createRow(rowNum);
            org.apache.poi.ss.usermodel.CellStyle totalStyle = workbook.createCellStyle();
            totalStyle.cloneStyleFrom(dataStyle);
            totalStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.LIGHT_YELLOW.getIndex());
            totalStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            org.apache.poi.ss.usermodel.Font totalFont = workbook.createFont();
            totalFont.setBold(true);
            totalStyle.setFont(totalFont);

            totalRow.createCell(0).setCellValue("合计");
            totalRow.createCell(1).setCellValue(totalLargerThanA0);
            totalRow.createCell(2).setCellValue(totalA0);
            totalRow.createCell(3).setCellValue(totalA1);
            totalRow.createCell(4).setCellValue(totalA2);
            totalRow.createCell(5).setCellValue(totalA3);
            totalRow.createCell(6).setCellValue(totalA4);
            totalRow.createCell(7).setCellValue(totalA4Equivalent);
            totalRow.createCell(8).setCellValue(totalFiles);

            for (int i = 0; i < 9; i++) {
                totalRow.getCell(i).setCellStyle(totalStyle);
            }

            // 设置列宽
            sheet.setColumnWidth(0, 20 * 256);
            for (int i = 1; i < 9; i++) {
                sheet.setColumnWidth(i, 12 * 256);
            }

            // 写入响应
            workbook.write(response.getOutputStream());
            workbook.close();

            log.info("导出文件夹统计Excel完成: {}", fileName);

        } catch (Exception e) {
            log.error("导出文件夹统计Excel失败", e);
            if (!response.isCommitted()) {
                try {
                    response.setContentType("application/json;charset=UTF-8");
                    byte[] errorBytes = ("{\"code\":500,\"msg\":\"导出失败: " + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
                    response.setContentLengthLong(errorBytes.length);
                    try (OutputStream os = response.getOutputStream()) {
                        os.write(errorBytes);
                        os.flush();
                    }
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 获取转换错误汇总
     */
    @GetMapping("/error-summary")
    public AjaxResult getErrorSummary() {
        try {
            List<PdfConvertRecord> allRecords = pdfConvertRecordService.selectPdfConvertRecordList(new PdfConvertRecord());
            List<PdfConvertRecord> failedRecords = allRecords.stream()
                .filter(r -> "3".equals(r.getStatus()))
                .collect(Collectors.toList());

            Map<String, Object> summary = new HashMap<>();
            summary.put("totalCount", allRecords.size());
            summary.put("failedCount", failedRecords.size());
            summary.put("successCount", allRecords.size() - failedRecords.size());

            List<Map<String, Object>> errors = failedRecords.stream().map(r -> {
                Map<String, Object> error = new HashMap<>();
                error.put("id", r.getId());
                error.put("sourceFileName", r.getSourceFileName());
                error.put("sourceFilePath", r.getSourceFilePath());
                error.put("targetFileName", r.getTargetFileName());
                error.put("convertType", r.getConvertType());
                error.put("failReason", r.getFailReason());
                error.put("createTime", r.getCreateTime());
                return error;
            }).collect(Collectors.toList());

            summary.put("errors", errors);
            return AjaxResult.success(summary);
        } catch (Exception e) {
            log.error("获取错误汇总失败", e);
            return AjaxResult.error("获取错误汇总失败: " + e.getMessage());
        }
    }

    /**
     * 导出错误报告
     */
    @GetMapping("/export-error-report")
    public void exportErrorReport(HttpServletResponse response) {
        try {
            List<PdfConvertRecord> allRecords = pdfConvertRecordService.selectPdfConvertRecordList(new PdfConvertRecord());
            List<PdfConvertRecord> failedRecords = allRecords.stream()
                .filter(r -> "3".equals(r.getStatus()))
                .collect(Collectors.toList());

            // 生成错误报告内容
            StringBuilder report = new StringBuilder();
            report.append("格式转换工具 - 错误报告\n");
            report.append("========================\n\n");
            report.append("生成时间: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append("\n");
            report.append("总任务数: ").append(allRecords.size()).append("\n");
            report.append("成功: ").append(allRecords.size() - failedRecords.size()).append("\n");
            report.append("失败: ").append(failedRecords.size()).append("\n");
            report.append("成功率: ").append(allRecords.size() > 0 ?
                String.format("%.1f%%", (allRecords.size() - failedRecords.size()) * 100.0 / allRecords.size()) : "N/A").append("\n");
            report.append("\n========================\n\n");

            if (failedRecords.isEmpty()) {
                report.append("无错误记录\n");
            } else {
                report.append("失败详情:\n");
                report.append("------------------------\n\n");
                for (int i = 0; i < failedRecords.size(); i++) {
                    PdfConvertRecord r = failedRecords.get(i);
                    report.append("[").append(i + 1).append("] ").append(r.getSourceFileName()).append("\n");
                    report.append("   源文件路径: ").append(r.getSourceFilePath()).append("\n");
                    report.append("   目标文件名: ").append(r.getTargetFileName()).append("\n");
                    report.append("   转换类型: ").append(r.getConvertType()).append("\n");
                    report.append("   错误信息: ").append(r.getFailReason() != null ? r.getFailReason() : "未知错误").append("\n");
                    report.append("   转换时间: ").append(r.getCreateTime()).append("\n");
                    report.append("\n");
                }
            }

            // 设置响应头（在写入内容之前设置）
            response.setContentType("text/plain;charset=UTF-8");
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String encodedFileName = URLEncoder.encode("PDF转换错误报告_" + timestamp + ".txt", StandardCharsets.UTF_8.name())
                    .replace("+", "%20");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + encodedFileName + "\"");

            byte[] reportBytes = report.toString().getBytes(StandardCharsets.UTF_8);
            response.setContentLengthLong(reportBytes.length);

            // 写入响应
            try (OutputStream os = response.getOutputStream()) {
                os.write(reportBytes);
                os.flush();
            }

            log.info("导出错误报告成功，共 {} 条失败记录", failedRecords.size());
        } catch (Exception e) {
            log.error("导出错误报告失败", e);
            // 只有在响应未提交时才写入错误信息
            if (!response.isCommitted()) {
                try {
                    response.setContentType("application/json;charset=UTF-8");
                    byte[] errorBytes = ("{\"code\":500,\"msg\":\"导出失败: " + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
                    response.setContentLengthLong(errorBytes.length);
                    try (OutputStream os = response.getOutputStream()) {
                        os.write(errorBytes);
                        os.flush();
                    }
                } catch (IOException ex) {
                    log.error("响应失败", ex);
                }
            }
        }
    }

    /**
     * 取消正在进行的转换任务
     *
     * @return 取消结果
     */
    @PostMapping("/cancel-convert")
    public AjaxResult cancelConvert() {
        log.info("收到取消转换请求");

        if (currentBatchTaskId == null) {
            return AjaxResult.error("当前没有正在进行的转换任务");
        }

        // 设置取消标志
        taskCancelFlags.put(currentBatchTaskId, true);
        log.info("已设置取消标志: taskId={}", currentBatchTaskId);

        Map<String, Object> result = new HashMap<>();
        result.put("taskId", currentBatchTaskId);
        result.put("message", "已发送取消信号，正在停止转换...");

        return AjaxResult.success(result);
    }

    /**
     * 取消指定的转换记录（上传模式使用）
     *
     * @param ids 记录ID列表（逗号分隔）
     * @return 取消结果
     */
    @PostMapping("/cancel-records")
    public AjaxResult cancelRecords(@RequestParam("ids") String ids) {
        log.info("收到取消记录请求: ids={}", ids);

        if (StringUtils.isEmpty(ids)) {
            return AjaxResult.error("记录ID不能为空");
        }

        String[] idArray = ids.split(",");
        int cancelledCount = 0;

        for (String idStr : idArray) {
            try {
                Long id = Long.parseLong(idStr.trim());
                PdfConvertRecord record = pdfConvertRecordService.selectPdfConvertRecordById(id);
                if (record != null && "1".equals(record.getStatus())) {
                    // 设置取消标志，让转换线程能够检测到
                    taskCancelFlags.put(String.valueOf(id), true);

                    // 中断转换线程
                    Thread thread = taskThreads.get(String.valueOf(id));
                    if (thread != null && thread.isAlive()) {
                        thread.interrupt();
                        log.info("已中断转换线程: id={}, thread={}", id, thread.getName());
                    }

                    // 更新状态为已取消（使用状态4表示已取消）
                    record.setStatus("4");
                    record.setFailReason("用户取消");
                    pdfConvertRecordService.updatePdfConvertRecord(record);
                    cancelledCount++;
                    log.info("已取消记录: id={}", id);
                }
            } catch (NumberFormatException e) {
                log.warn("无效的记录ID: {}", idStr);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("cancelledCount", cancelledCount);
        result.put("message", "已取消 " + cancelledCount + " 个任务");

        return AjaxResult.success(result);
    }

    /**
     * 检查是否有正在进行的转换任务
     *
     * @return 任务状态
     */
    @GetMapping("/convert-status")
    public AjaxResult getConvertStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("isConverting", currentBatchTaskId != null);
        status.put("taskId", currentBatchTaskId);
        return AjaxResult.success(status);
    }

    /**
     * 批量复制文件
     * 将输入目录中的图片文件复制多份，按序号命名
     *
     * @param inputDir        输入目录
     * @param outputDir       输出目录
     * @param filenamePrefix  文件名前缀
     * @param copyCount       每个文件复制份数
     * @return 复制结果
     */
    @PostMapping("/batch-copy")
    public AjaxResult batchCopyFiles(
            @RequestParam("inputDir") String inputDir,
            @RequestParam("outputDir") String outputDir,
            @RequestParam("filenamePrefix") String filenamePrefix,
            @RequestParam("copyCount") int copyCount) {

        log.info("批量复制请求: inputDir={}, outputDir={}, prefix={}, copyCount={}",
                inputDir, outputDir, filenamePrefix, copyCount);

        // 参数校验
        if (StringUtils.isEmpty(inputDir)) {
            return AjaxResult.error("输入目录不能为空");
        }
        if (StringUtils.isEmpty(outputDir)) {
            return AjaxResult.error("输出目录不能为空");
        }
        if (StringUtils.isEmpty(filenamePrefix)) {
            return AjaxResult.error("文件名前缀不能为空");
        }
        if (copyCount < 1 || copyCount > 999) {
            return AjaxResult.error("复制份数必须在1-999之间");
        }

        // 验证目录
        File inputDirectory = new File(inputDir);
        if (!inputDirectory.exists() || !inputDirectory.isDirectory()) {
            return AjaxResult.error("输入目录不存在或不是有效目录");
        }

        File outputDirectory = new File(outputDir);
        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs();
        }

        // 支持的图片扩展名
        List<String> imageExts = Arrays.asList(".jpg", ".jpeg", ".png", ".tif", ".tiff", ".bmp");

        // 扫描输入目录中的图片文件
        List<File> sourceFiles = new ArrayList<>();
        scanImageFiles(inputDirectory, imageExts, sourceFiles);

        if (sourceFiles.isEmpty()) {
            return AjaxResult.error("输入目录中没有找到图片文件");
        }

        // 按文件名排序
        sourceFiles.sort(Comparator.comparing(File::getName));

        log.info("找到 {} 个源图片文件", sourceFiles.size());

        // 执行复制
        int successCount = 0;
        int failCount = 0;
        int fileIndex = 1; // 全局序号

        for (File sourceFile : sourceFiles) {
            String extension = getFileExtension(sourceFile.getName());
            // 扩展名需要加点号
            String extensionWithDot = extension.isEmpty() ? "" : "." + extension;

            // 每个源文件复制copyCount份
            for (int i = 0; i < copyCount; i++) {
                // 生成目标文件名: 前缀 + 3位序号 + 扩展名
                String targetFileName = filenamePrefix + String.format("%03d", fileIndex) + extensionWithDot;
                File targetFile = new File(outputDir, targetFileName);

                try {
                    java.nio.file.Files.copy(sourceFile.toPath(), targetFile.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    successCount++;
                    fileIndex++;
                } catch (IOException e) {
                    log.error("复制文件失败: {} -> {}", sourceFile.getName(), targetFileName, e);
                    failCount++;
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("sourceFileCount", sourceFiles.size());
        result.put("copyCount", copyCount);
        result.put("totalFiles", sourceFiles.size() * copyCount);
        result.put("successCount", successCount);
        result.put("failCount", failCount);

        log.info("批量复制完成: 源文件={}, 每份复制={}, 总文件={}, 成功={}, 失败={}",
                sourceFiles.size(), copyCount, sourceFiles.size() * copyCount, successCount, failCount);

        return AjaxResult.success(result);
    }

    /**
     * 递归扫描图片文件
     */
    private void scanImageFiles(File directory, List<String> extensions, List<File> result) {
        File[] files = directory.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                scanImageFiles(file, extensions, result);
            } else {
                String name = file.getName().toLowerCase();
                for (String ext : extensions) {
                    if (name.endsWith(ext)) {
                        result.add(file);
                        break;
                    }
                }
            }
        }
    }

    /**
     * 【新增】按件号层（最后一级目录）分组处理合并
     * 每个件号层目录下的文件合并成一个PDF
     *
     * @param sortedFiles 已排序的文件列表
     * @param outputDir 输出目录
     * @param preserveInputDir 保持输入目录
     * @param inputDir 输入目录
     * @param filenamePrefix 文件名前缀
     * @param filenameSuffix 文件名后缀
     * @param folderCreateRule 文件夹创建规则
     * @param taskId 任务ID
     * @param outputMode 输出模式
     */
    private void processMergeByLastLevelDirectory(List<File> sortedFiles, String outputDir,
                                                   String preserveInputDir, String inputDir,
                                                   String filenamePrefix, String filenameSuffix,
                                                   int folderCreateRule, int sortRule,
                                                   String taskId, String outputMode) {
        log.info("开始按件号层分组合并处理，共 {} 个文件", sortedFiles.size());

        // 【修复】添加排序结果日志，便于调试文件映射问题
        log.info("【调试】排序后的文件列表（前10个）:");
        for (int i = 0; i < Math.min(10, sortedFiles.size()); i++) {
            File file = sortedFiles.get(i);
            Integer seqNum = extractArchiveSequenceNumber(file.getName());
            log.info("  第{}个文件: {}, 序号: {}", i+1, file.getName(), seqNum);
        }

        // 1. 按完整父目录路径分组（避免不同路径下同名目录被错误合并）
        // 例如：D10\0001 和 Y\0001 应该是不同的组
        Map<String, List<File>> filesByParentPath = new LinkedHashMap<>();

        for (File file : sortedFiles) {
            // 使用完整父目录路径作为key，确保不同路径的同名目录分开处理
            String parentPath = file.getParent();

            if (!filesByParentPath.containsKey(parentPath)) {
                filesByParentPath.put(parentPath, new ArrayList<>());
            }
            filesByParentPath.get(parentPath).add(file);
        }

        log.info("按件号层分组完成，共 {} 个组", filesByParentPath.size());

        // 2. 每组分别处理
        int groupIndex = 0;
        int totalGroups = filesByParentPath.size();
        int totalSuccess = 0;
        int totalFail = 0;

        for (Map.Entry<String, List<File>> entry : filesByParentPath.entrySet()) {
            String parentPath = entry.getKey();
            List<File> groupFiles = entry.getValue();
            groupIndex++;

            // 获取最后一级目录名（用于日志显示）
            String lastDirName = new File(parentPath).getName();

            // 检查是否被取消
            if (Boolean.TRUE.equals(taskCancelFlags.get(taskId))) {
                log.info("任务被取消，停止处理: taskId={}, 已处理 {}/{} 组", taskId, groupIndex - 1, totalGroups);
                break;
            }

            log.info("处理第 {}/{} 组: 目录={}, 件号层={}, 文件数={}", groupIndex, totalGroups, parentPath, lastDirName, groupFiles.size());

            try {
                // 处理这一组的合并
                MergeResult result = processSingleMergeGroup(groupFiles, outputDir, preserveInputDir, inputDir,
                        filenamePrefix, filenameSuffix, folderCreateRule, sortRule, lastDirName, taskId, outputMode);

                totalSuccess += result.successCount;
                totalFail += result.failCount;

            } catch (Exception e) {
                log.error("处理件号层 {} 合并失败", lastDirName, e);
                totalFail += groupFiles.size();
            }
        }

        log.info("按件号层合并完成，共 {} 组，成功 {} 个文件，失败 {} 个文件", totalGroups, totalSuccess, totalFail);
    }

    /**
     * 处理单个合并组（一个件号层下的所有文件）
     */
    private MergeResult processSingleMergeGroup(List<File> groupFiles, String outputDir,
                                                 String preserveInputDir, String inputDir,
                                                 String filenamePrefix, String filenameSuffix,
                                                 int folderCreateRule, int sortRule, String lastDirName,
                                                 String taskId, String outputMode) {
        MergeResult result = new MergeResult();
        List<String> tempPdfPaths = new ArrayList<>();
        sortFiles(groupFiles, sortRule);
        log.info("件号层 {} 组内排序完成，规则: {}，顺序: {}",
                lastDirName, sortRule,
                groupFiles.stream().map(File::getName).collect(Collectors.joining(", ")));

        // 获取第一个文件用于构建路径
        File firstFile = groupFiles.get(0);

        // 构建合并文件名：基于完整路径层级，包含件号层
        // 规则8特殊处理：使用最后一层文件夹名（件号层）作为PDF文件名
        String mergeBaseName;
        if (folderCreateRule == 8) {
            // 规则8：使用preservePath的最后一层文件夹名（档号）作为PDF文件名
            String preservePath = "";
            if (StringUtils.isNotEmpty(preserveInputDir)) {
                preservePath = PdfOutputPathBuilder.buildPreservePath(preserveInputDir, firstFile.getAbsolutePath());
            }
            if (!preservePath.isEmpty()) {
                String[] pathParts = preservePath.split("[/\\\\]");
                mergeBaseName = pathParts[pathParts.length - 1]; // 使用最后一层文件夹名
            } else {
                mergeBaseName = lastDirName; // 回退到件号层目录名
            }
        } else {
            // 其他规则：使用路径层级累积
            mergeBaseName = buildMergeNameFromFile(firstFile, inputDir);
        }
        log.info("件号层 {} 的合并文件名: {}", lastDirName, mergeBaseName);

        // 构建输出目录（规则3去掉件号层，规则8去掉件号层并以档号命名）
        String mergeOutputDir;
        String preservePathForDir = "";
        if (StringUtils.isNotEmpty(preserveInputDir)) {
            preservePathForDir = PdfOutputPathBuilder.buildPreservePath(preserveInputDir, firstFile.getAbsolutePath());
        }

        if (folderCreateRule == 0 || folderCreateRule == 1 || folderCreateRule == 2) {
            mergeOutputDir = PdfOutputPathBuilder.buildOutputDirForMerge(
                    outputDir, mergeBaseName, folderCreateRule, null);
        } else if (folderCreateRule == 8) {
            // 规则8：使用preservePath构建输出目录，mergeBaseName作为文件名
            mergeOutputDir = PdfOutputPathBuilder.buildOutputDirForMerge(
                    outputDir, null, folderCreateRule, preservePathForDir);
            log.info("规则8：使用preservePath构建输出目录，preservePath={}, mergeOutputDir={}", preservePathForDir, mergeOutputDir);
        } else {
            // 规则3/4/5/6/7
            mergeOutputDir = PdfOutputPathBuilder.buildOutputDirForMerge(
                    outputDir, mergeBaseName, folderCreateRule, preservePathForDir);
        }

        // 确保输出目录存在
        File mergeOutputDirFile = new File(mergeOutputDir);
        if (!mergeOutputDirFile.exists()) {
            mergeOutputDirFile.mkdirs();
        }

        // 根据输出模式确定文件扩展名和转换类型
        boolean isOfdMode = "merge_ofd".equals(outputMode);
        String fileExtension = isOfdMode ? ".ofd" : ".pdf";
        String convertType = isOfdMode ? "MERGE-OFD" : "MERGE-PDF";

        String mergedFileName = (StringUtils.isNotEmpty(filenamePrefix) ? FileNameValidator.sanitizeFileName(filenamePrefix) : "")
                + FileNameValidator.sanitizeFileName(mergeBaseName)
                + (StringUtils.isNotEmpty(filenameSuffix) ? FileNameValidator.sanitizeFileName(filenameSuffix) : "")
                + fileExtension;
        String mergedFilePath = mergeOutputDir + File.separator + mergedFileName;

        // 创建合并记录
        PdfConvertRecord mergeRecord = new PdfConvertRecord();
        mergeRecord.setSourceFileName(lastDirName + " (合并" + groupFiles.size() + "个文件)");
        mergeRecord.setSourceFilePath(firstFile.getParent());
        mergeRecord.setTargetFileName(mergedFileName);
        mergeRecord.setTargetFilePath(mergedFilePath);
        mergeRecord.setConvertType(convertType);
        mergeRecord.setStatus("1");
        mergeRecord.setProgress(0);
        mergeRecord.setCreateBy("desktop");
        pdfConvertRecordService.insertPdfConvertRecord(mergeRecord);

        // 逐个转换文件
        int fileIndex = 0;
        for (File file : groupFiles) {
            if (Boolean.TRUE.equals(taskCancelFlags.get(taskId))) {
                log.info("任务被取消，停止转换文件: {}", file.getName());
                break;
            }
            fileIndex++;

            try {
                String fileName = file.getName();
                Integer seqNum = extractArchiveSequenceNumber(fileName);

                // 【修复】添加详细的文件映射日志，便于调试档号映射问题
                log.info("【文件映射】第{}/{}个文件: 原始文件名={}, 提取序号={}, 完整路径={}",
                         fileIndex, groupFiles.size(), fileName, seqNum, file.getAbsolutePath());

                // 构建输出路径
                String preservePath = "";
                if ((folderCreateRule >= 3 && folderCreateRule <= 8) && StringUtils.isNotEmpty(preserveInputDir)) {
                    preservePath = PdfOutputPathBuilder.buildPreservePath(preserveInputDir, file.getAbsolutePath());
                }

                String actualOutputDir = PdfOutputPathBuilder.buildOutputDir(
                        outputDir, file.getAbsolutePath(), folderCreateRule, preservePath);

                File actualOutputDirFile = new File(actualOutputDir);
                if (!actualOutputDirFile.exists()) {
                    actualOutputDirFile.mkdirs();
                }

                // 根据输出模式确定临时文件扩展名
                String tempFileExtension = isOfdMode ? ".ofd" : ".pdf";
                String tempFileType = isOfdMode ? "OFD" : "PDF";

                // 生成临时文件名
                String baseFileName = getFileNameWithoutExtension(fileName);
                String tempFileName = FileNameValidator.sanitizeFileName(baseFileName)
                        + "_" + String.format("%05d", fileIndex) + "_temp" + tempFileExtension;
                String tempFilePath = actualOutputDir + File.separator + tempFileName;

                // 【修复】添加档号到文件名的映射日志
                log.info("【档号映射】原始档号={} -> 临时{}文件名={} (序号: {})",
                         baseFileName, tempFileType, tempFileName, fileIndex);

                // 【修复】使用原始 outputMode 转换，保持输出格式一致性
                pdfConvertSimpleService.convertToDirectory(
                        file.getAbsolutePath(),
                        actualOutputDir,
                        fileName,
                        outputMode,  // 使用原始 outputMode，支持 merge 和 merge_ofd
                        null, null,
                        folderCreateRule, 1,
                        tempFileName
                );

                if (new File(tempFilePath).exists()) {
                    tempPdfPaths.add(tempFilePath);
                    result.successCount++;

                    // 【新增】验证文件名与源文件的对应关系
                    log.info("【验证成功】源文件:{} → 临时{}:{} (序号:{}, 第{}个文件)",
                             fileName, tempFileType, tempFileName, seqNum, fileIndex);
                } else {
                    result.failCount++;
                    log.error("【验证失败】转换失败，源文件:{} → 临时{}:{} (序号:{}, 第{}个文件)",
                             fileName, tempFileType, tempFileName, seqNum, fileIndex);
                }

            } catch (Exception e) {
                log.error("转换文件失败: {}", file.getName(), e);
                result.failCount++;
            }
        }

        boolean cancelled = Boolean.TRUE.equals(taskCancelFlags.get(taskId));

        // 根据输出模式确定合并类型
        String fileType = isOfdMode ? "OFD" : "PDF";

        // 合并这一组的文件
        if (!tempPdfPaths.isEmpty() && !cancelled) {
            try {
                log.info("开始合并件号层 {} 的{}，共 {} 个文件", lastDirName, fileType, tempPdfPaths.size());

                // 【新增】合并前验证文件顺序
                log.info("【合并前验证】即将合并的{}文件列表:", fileType);
                for (int i = 0; i < tempPdfPaths.size(); i++) {
                    String filePath = tempPdfPaths.get(i);
                    File file = new File(filePath);
                    String fileName = file.getName();
                    log.info("  第{}个{}: {}", i+1, fileType, fileName);
                }

                mergeRecord.setProgress(90);
                pdfConvertRecordService.updatePdfConvertRecord(mergeRecord);

                // 根据输出模式选择合并方法
                if (isOfdMode) {
                    // 使用 DualLayerOfdConverter 合并 OFD
                    com.pdfutil.common.core.utils.DualLayerOfdConverter.convertToMergedOfd(
                            tempPdfPaths.toArray(new String[0]), mergedFilePath);
                    log.info("件号层 {} OFD合并完成: {}", lastDirName, mergedFilePath);
                } else {
                    // 使用原有方法合并 PDF
                    mergePdfFiles(tempPdfPaths, mergedFilePath);
                    log.info("件号层 {} PDF合并完成: {}", lastDirName, mergedFilePath);
                }

                // 更新合并记录。只要有文件失败，就不能静默标记为完全成功，避免合并文件缺页不被发现。
                if (result.failCount > 0) {
                    mergeRecord.setStatus("3");
                    mergeRecord.setFailReason(truncateFailReason(
                            "部分文件转换失败，已合并成功 " + result.successCount + " 个，失败 " + result.failCount + " 个"));
                } else {
                    mergeRecord.setStatus("2");
                }
                mergeRecord.setProgress(100);
                pdfConvertRecordService.updatePdfConvertRecord(mergeRecord);

                cleanupTempFiles(tempPdfPaths, mergedFilePath);

            } catch (Exception e) {
                log.error("合并{}失败: 件号层={}", fileType, lastDirName, e);
                mergeRecord.setStatus("3");
                mergeRecord.setFailReason(truncateFailReason("合并失败: " + e.getMessage()));
                pdfConvertRecordService.updatePdfConvertRecord(mergeRecord);
            }
        } else {
            mergeRecord.setStatus("3");
            mergeRecord.setProgress(cancelled ? mergeRecord.getProgress() : 100);
            mergeRecord.setFailReason(cancelled
                    ? "任务已取消，未执行最终合并"
                    : String.format("没有可合并的%s文件，成功 0 个，失败 %d 个", fileType, result.failCount));
            pdfConvertRecordService.updatePdfConvertRecord(mergeRecord);
            cleanupTempFiles(tempPdfPaths, mergedFilePath);
        }

        return result;
    }

    /**
     * 清理临时文件
     * @param tempPaths 临时文件路径列表
     * @param mergedFilePath 合并后的文件路径（不应删除）
     */
    private void cleanupTempFiles(List<String> tempPaths, String mergedFilePath) {
        for (String tempPath : tempPaths) {
            File tempFile = new File(tempPath);
            if (tempFile.exists() && !tempPath.equals(mergedFilePath)) {
                if (tempFile.delete()) {
                    log.debug("删除临时文件: {}", tempPath);
                } else {
                    log.warn("删除临时文件失败: {}", tempPath);
                }
            }
        }
    }

    private String truncateFailReason(String reason) {
        if (reason != null && reason.length() > MAX_FAIL_REASON_LENGTH) {
            return reason.substring(0, MAX_FAIL_REASON_LENGTH) + "...";
        }
        return reason;
    }

    /**
     * 处理非合并模式：逐个转换文件
     */
    private void processIndividualFiles(List<File> sortedFiles, String outputDir,
                                         String preserveInputDir, String inputDir,
                                         String filenamePrefix, String filenameSuffix,
                                         int folderCreateRule, int pdfNameSource,
                                         String outputMode, String taskId,
                                         String splitType, Integer splitStartPage, Integer splitEndPage) {
        int successCount = 0;
        int failCount = 0;

        for (File file : sortedFiles) {
            if (Boolean.TRUE.equals(taskCancelFlags.get(taskId))) {
                log.info("任务被取消，停止处理: taskId={}, 已处理: {}", taskId, successCount + failCount);
                break;
            }

            PdfConvertRecord record = null;
            String fileName = file.getName();
            try {
                String extension = getFileExtension(fileName).toLowerCase();
                log.info("处理文件: {}", fileName);

                // 计算相对路径
                String preservePath = "";
                if ((folderCreateRule >= 3 && folderCreateRule <= 7) && StringUtils.isNotEmpty(preserveInputDir)) {
                    preservePath = PdfOutputPathBuilder.buildPreservePath(preserveInputDir, file.getAbsolutePath());
                }

                // 构建输出路径
                // 对于规则1和规则2，不使用buildOutputDir预先构建路径
                // 这些规则需要根据源文件的父目录路径结构来构建档号，由convertToDirectory方法处理
                String actualOutputDir;
                if (folderCreateRule == 1 || folderCreateRule == 2) {
                    // 规则1和2：直接使用输出目录，让convertToDirectory处理档号构建
                    actualOutputDir = outputDir;
                } else {
                    // 其他规则：使用PdfOutputPathBuilder构建输出路径
                    actualOutputDir = PdfOutputPathBuilder.buildOutputDir(
                            outputDir, file.getAbsolutePath(), folderCreateRule, preservePath);
                }

                // 获取PDF文件名
                String pdfBaseName;
                if ((folderCreateRule == 3 || folderCreateRule == 4
                        || folderCreateRule == 6 || folderCreateRule == 7)
                        && StringUtils.isNotEmpty(inputDir)) {
                    pdfBaseName = PdfOutputPathBuilder.buildPdfNameFromPath(inputDir, file.getAbsolutePath());
                } else if (folderCreateRule == 5) {
                    // 规则5：保持输入目录结构，使用源文件名（避免同一文件夹下的文件覆盖）
                    pdfBaseName = getFileNameWithoutExtension(fileName);
                } else {
                    pdfBaseName = PdfOutputPathBuilder.getPdfBaseName(actualOutputDir, file.getAbsolutePath(), pdfNameSource);
                }

                // 确保输出目录存在
                File actualOutputDirFile = new File(actualOutputDir);
                if (!actualOutputDirFile.exists()) {
                    actualOutputDirFile.mkdirs();
                }

                // 构建目标文件名
                StringBuilder targetFileNameBuilder = new StringBuilder();
                if (StringUtils.isNotEmpty(filenamePrefix)) {
                    targetFileNameBuilder.append(FileNameValidator.sanitizeFileName(filenamePrefix));
                }
                targetFileNameBuilder.append(FileNameValidator.sanitizeFileName(pdfBaseName));
                if (StringUtils.isNotEmpty(filenameSuffix)) {
                    targetFileNameBuilder.append(FileNameValidator.sanitizeFileName(filenameSuffix));
                }
                String targetExt = ".pdf";
                if ("ofd".equals(extension) || (outputMode != null && outputMode.toLowerCase().contains("ofd"))) {
                    targetExt = ".ofd";
                }
                targetFileNameBuilder.append(targetExt);
                String targetFileName = targetFileNameBuilder.toString();

                record = new PdfConvertRecord();
                record.setSourceFileName(fileName);
                record.setSourceFilePath(file.getAbsolutePath());
                record.setTargetFileName(targetFileName);
                record.setTargetFilePath(actualOutputDir + File.separator + targetFileName);

                String convertType = getConvertType(extension);
                if (".ofd".equals(targetExt)) {
                    if ("PDF-OCR".equals(convertType)) {
                        convertType = "OFD-DOUBLE";
                    } else if (convertType.endsWith("-PDF")) {
                        convertType = convertType.substring(0, convertType.length() - 4) + "-OFD";
                    } else if ("UNKNOWN".equals(convertType)) {
                        convertType = "OFD-DOUBLE";
                    }
                }
                record.setConvertType(convertType);
                record.setStatus("1");
                record.setProgress(0);
                record.setCreateBy("desktop");

                pdfConvertRecordService.insertPdfConvertRecord(record);

                // 执行转换
                long startTime = System.currentTimeMillis();
                if (folderCreateRule == 1 || folderCreateRule == 2) {
                    // 规则1/2：不传递targetFileName，让convertToDirectory根据档号结构和前缀后缀构建完整路径
                    pdfConvertSimpleService.convertToDirectory(
                            file.getAbsolutePath(),
                            actualOutputDir,
                            fileName,
                            outputMode,
                            filenamePrefix,
                            filenameSuffix,
                            folderCreateRule,
                            pdfNameSource,
                            null,
                            splitType,
                            splitStartPage,
                            splitEndPage
                    );
                } else if (folderCreateRule == 3 || folderCreateRule == 4
                        || folderCreateRule == 6 || folderCreateRule == 7) {
                    // 规则3/4/6/7：传递targetFileName给convertToDirectory
                    pdfConvertSimpleService.convertToDirectory(
                            file.getAbsolutePath(),
                            actualOutputDir,
                            fileName,
                            outputMode,
                            filenamePrefix,
                            filenameSuffix,
                            folderCreateRule,
                            pdfNameSource,
                            targetFileName,
                            splitType,
                            splitStartPage,
                            splitEndPage
                    );
                } else {
                    // 规则0/5：不传递targetFileName，让convertToDirectory自己决定文件名
                    pdfConvertSimpleService.convertToDirectory(
                            file.getAbsolutePath(),
                            actualOutputDir,
                            fileName,
                            outputMode,
                            filenamePrefix,
                            filenameSuffix,
                            folderCreateRule,
                            pdfNameSource,
                            null,
                            splitType,
                            splitStartPage,
                            splitEndPage
                    );
                }
                long duration = System.currentTimeMillis() - startTime;

                // 更新记录为成功
                record.setStatus("2");
                record.setProgress(100);
                record.setDuration((int) duration);
                pdfConvertRecordService.updatePdfConvertRecord(record);

                successCount++;
                log.info("转换完成: {}, 耗时: {}ms", fileName, duration);

            } catch (Exception e) {
                log.error("转换失败: {}", fileName, e);
                failCount++;
                try {
                    if (record != null && record.getId() != null) {
                        record.setStatus("3");
                        String errorMsg = e.getMessage();
                        if (errorMsg != null && errorMsg.length() > MAX_FAIL_REASON_LENGTH) {
                            errorMsg = errorMsg.substring(0, MAX_FAIL_REASON_LENGTH) + "...";
                        }
                        record.setFailReason(errorMsg);
                        pdfConvertRecordService.updatePdfConvertRecord(record);
                    }
                } catch (Exception ex) {
                    log.error("记录失败信息失败", ex);
                }
            }
        }

        log.info("非合并模式处理完成，成功: {}, 失败: {}", successCount, failCount);
    }

    /**
     * 根据文件路径构建合并文件名
     * 基于文件所在的完整目录层级生成名称
     */
    private String buildMergeNameFromFile(File file, String inputDir) {
        if (file == null || StringUtils.isEmpty(inputDir)) {
            return "merged";
        }

        String parentPath = file.getParent();
        String normalizedInputDir = inputDir.replace("\\", "/");
        String normalizedParent = parentPath.replace("\\", "/");

        if (!normalizedInputDir.endsWith("/")) {
            normalizedInputDir = normalizedInputDir + "/";
        }

        if (normalizedParent.startsWith(normalizedInputDir)) {
            String relativePath = normalizedParent.substring(normalizedInputDir.length());
            String[] parts = relativePath.split("/");
            StringBuilder nameBuilder = new StringBuilder();
            for (String part : parts) {
                if (part.isEmpty()) continue;
                if (nameBuilder.length() > 0) {
                    nameBuilder.append("-");
                }
                nameBuilder.append(part);
            }
            return nameBuilder.toString();
        }

        // 如果无法计算相对路径，使用最后一级目录名
        return new File(parentPath).getName();
    }

    /**
     * 合并结果内部类
     */
    private static class MergeResult {
        int successCount = 0;
        int failCount = 0;
    }

    private static class ArchiveOcrCacheEntry {
        private final JsonNode result;
        private volatile long lastAccessTime;

        private ArchiveOcrCacheEntry(JsonNode result) {
            this.result = result;
            this.lastAccessTime = System.currentTimeMillis();
        }
    }

    private static class OcrTextItem {
        private final String text;
        private final int left;
        private final int top;
        private final int right;
        private final int bottom;
        private final int centerY;
        private final int height;

        private OcrTextItem(String text, int left, int top, int right, int bottom) {
            this.text = text;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.height = Math.max(1, bottom - top);
            this.centerY = top + (height / 2);
        }
    }

    /**
     * 批量上传图片并转换为PDF（性能优化版）
     * 一次性对所有图片进行OCR识别，减少Python进程启动开销
     *
     * 性能提升：
     * - 传统方式：N个图片 = 启动N次Python进程
     * - 优化方式：N个图片 = 启动1次Python进程
     *
     * @param files 上传的图片文件数组
     * @param outputMode 输出模式：double(双层PDF)、single(单层PDF)
     * @param filenamePrefix 文件名前缀
     * @param filenameSuffix 文件名后缀
     * @return 转换结果
     */
    @PostMapping("/upload-images-batch")
    public AjaxResult uploadImagesBatch(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "outputDir", required = false) String customOutputDir,
            @RequestParam(value = "outputMode", required = false, defaultValue = "double") String outputMode,
            @RequestParam(value = "filenamePrefix", required = false) String filenamePrefix,
            @RequestParam(value = "filenameSuffix", required = false) String filenameSuffix) {

        log.info("收到批量图片上传请求: 文件数={}, 输出模式={}", files != null ? files.length : 0, outputMode);

        if (files == null || files.length == 0) {
            return AjaxResult.error("请选择要上传的图片文件");
        }

        try {
            // 支持的图片扩展名
            List<String> imageExts = Arrays.asList("jpg", "jpeg", "png", "tif", "tiff", "bmp");

            // 验证并保存图片文件
            String uploadDir = getFileUploadDir();
            File uploadDirectory = new File(uploadDir);
            if (!uploadDirectory.exists()) {
                uploadDirectory.mkdirs();
            }

            List<String> imagePaths = new ArrayList<>();
            List<String> fileNames = new ArrayList<>();
            int skipCount = 0;
            // 用于跟踪已使用的文件名，避免同名覆盖
            Map<String, Integer> fileNameCounter = new HashMap<>();

            for (MultipartFile file : files) {
                // 检查空元素
                if (file == null || file.isEmpty()) {
                    log.debug("跳过空的文件元素");
                    skipCount++;
                    continue;
                }

                String originalFileName = file.getOriginalFilename();
                String ext = getFileExtension(originalFileName).toLowerCase();

                // 只处理图片文件
                if (!imageExts.contains(ext)) {
                    log.debug("跳过非图片文件: {}", originalFileName);
                    skipCount++;
                    continue;
                }

                // 安全检查
                if (!FileNameValidator.isSafeFileName(originalFileName)) {
                    log.warn("文件名不安全，跳过: {}", originalFileName);
                    skipCount++;
                    continue;
                }

                // 处理同名文件：自动重命名
                String fileName = originalFileName;
                if (fileNameCounter.containsKey(originalFileName)) {
                    int count = fileNameCounter.get(originalFileName) + 1;
                    fileNameCounter.put(originalFileName, count);
                    // 在扩展名前添加序号
                    int dotIndex = originalFileName.lastIndexOf('.');
                    if (dotIndex > 0) {
                        fileName = originalFileName.substring(0, dotIndex) + "_" + count + originalFileName.substring(dotIndex);
                    } else {
                        fileName = originalFileName + "_" + count;
                    }
                    log.info("检测到同名文件，重命名: {} -> {}", originalFileName, fileName);
                } else {
                    fileNameCounter.put(originalFileName, 1);
                }

                // 保存文件
                String sourcePath = uploadDir + File.separator + fileName;
                File destFile = new File(sourcePath);
                file.transferTo(destFile);

                imagePaths.add(sourcePath);
                fileNames.add(fileName);
            }

            if (imagePaths.isEmpty()) {
                return AjaxResult.error("没有有效的图片文件，跳过了 " + skipCount + " 个非图片文件");
            }

            log.info("保存了 {} 个图片文件，跳过 {} 个非图片文件", imagePaths.size(), skipCount);

            // 确定输出目录
            String finalOutputDir = StringUtils.isNotEmpty(customOutputDir) ? customOutputDir : getOutputDir();
            File outDir = new File(finalOutputDir);
            if (!outDir.exists()) {
                outDir.mkdirs();
            }

            // 异步执行批量转换（使用优化后的批量OCR）
            final List<String> finalImagePaths = imagePaths;
            final List<String> finalFileNames = fileNames;
            final String outputDirPath = finalOutputDir;
            final String finalOutputMode = outputMode;
            final String finalPrefix = filenamePrefix;
            final String finalSuffix = filenameSuffix;

            // 生成任务ID
            final String batchTaskId = "batch_" + System.currentTimeMillis();
            currentBatchTaskId = batchTaskId;
            taskCancelFlags.put(batchTaskId, false);

            taskExecutor.submit(() -> {
                try {
                    log.info("开始批量转换 {} 个图片文件，输出模式: {}", finalImagePaths.size(), finalOutputMode);

                    // 使用批量转换方法（一次性OCR）
                    Map<String, String> results = pdfConvertSimpleService.convertImagesBatch(
                            finalImagePaths, outputDirPath, finalOutputMode, finalPrefix, finalSuffix);

                    // 更新数据库记录
                    int successCount = 0;
                    int failCount = 0;
                    for (int i = 0; i < finalImagePaths.size(); i++) {
                        String imagePath = finalImagePaths.get(i);
                        String fileName = finalFileNames.get(i);
                        String outputPath = results.get(imagePath);

                        PdfConvertRecord record = new PdfConvertRecord();
                        record.setSourceFileName(fileName);
                        record.setSourceFilePath(imagePath);

                        if (outputPath != null) {
                            record.setTargetFileName(new File(outputPath).getName());
                            record.setTargetFilePath(outputPath);
                            record.setStatus("2"); // 成功
                            record.setProgress(100);
                            successCount++;
                        } else {
                            record.setStatus("3"); // 失败
                            record.setFailReason("转换失败");
                            failCount++;
                        }

                        record.setConvertType(getConvertType(getFileExtension(fileName)));
                        record.setCreateBy("desktop-batch");
                        pdfConvertRecordService.insertPdfConvertRecord(record);
                    }

                    log.info("批量图片转换完成: 成功 {}, 失败 {}", successCount, failCount);

                } catch (Exception e) {
                    log.error("批量图片转换失败", e);
                } finally {
                    // 清理临时上传的文件
                    for (String imagePath : finalImagePaths) {
                        try {
                            File tempFile = new File(imagePath);
                            if (tempFile.exists()) {
                                if (tempFile.delete()) {
                                    log.debug("已清理临时文件: {}", imagePath);
                                } else {
                                    log.warn("清理临时文件失败: {}", imagePath);
                                }
                            }
                        } catch (Exception ex) {
                            log.warn("清理临时文件异常: {}", imagePath, ex);
                        }
                    }
                    taskCancelFlags.remove(batchTaskId);
                    if (batchTaskId.equals(currentBatchTaskId)) {
                        currentBatchTaskId = null;
                    }
                }
            });

            Map<String, Object> result = new HashMap<>();
            result.put("imageCount", imagePaths.size());
            result.put("skipCount", skipCount);
            result.put("taskId", batchTaskId);
            result.put("message", "已提交 " + imagePaths.size() + " 个图片的批量转换任务");

            return AjaxResult.success(result);

        } catch (Exception e) {
            log.error("批量图片上传失败", e);
            return AjaxResult.error("批量上传失败: " + e.getMessage());
        }
    }

    /**
     * 获取后台日志
     *
     * @param lines 获取最近多少行日志
     * @return 日志内容
     */
    @GetMapping("/logs")
    public AjaxResult getLogs(@RequestParam(value = "lines", defaultValue = "500") int lines) {
        try {
            List<String> logLines = new ArrayList<>();

            // 主日志文件路径
            String logFilePath = System.getProperty("user.home") + "/.pdfutil/logs/java.log";
            File logFile = new File(logFilePath);

            // 确保日志目录存在
            File logDir = new File(System.getProperty("user.home") + "/.pdfutil/logs");
            if (!logDir.exists()) {
                logDir.mkdirs();
            }

            // 如果主日志文件不存在，尝试查找其他可能的日志文件
            if (!logFile.exists()) {
                File logsDir = new File(System.getProperty("user.home") + "/.pdfutil/logs");
                if (logsDir.exists() && logsDir.isDirectory()) {
                    File[] logFiles = logsDir.listFiles((dir, name) ->
                        name.endsWith(".log")
                    );

                    // 找最新的日志文件
                    if (logFiles != null && logFiles.length > 0) {
                        Arrays.sort(logFiles, Comparator.comparingLong(File::lastModified).reversed());
                        logFile = logFiles[0];
                    }
                }
            }

            if (logFile.exists() && logFile.canRead() && logFile.length() > 0) {
                // 使用UTF-8编码读取日志文件
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(
                            new java.io.FileInputStream(logFile),
                            java.nio.charset.StandardCharsets.UTF_8
                        )
                    )) {
                    // 读取所有行到列表
                    List<String> allLines = new ArrayList<>();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        allLines.add(line);
                    }

                    // 获取最后N行
                    int startIndex = Math.max(0, allLines.size() - lines);
                    logLines = allLines.subList(startIndex, allLines.size());
                }
            }

            // 如果日志文件不存在或为空，返回提示信息
            if (logLines.isEmpty()) {
                logLines.add("暂无日志输出");
                logLines.add("日志文件路径: " + logFilePath);
                logLines.add("当前时间: " + new Date());
                logLines.add("");
                logLines.add("可能的原因：");
                logLines.add("1. 应用刚启动，还未产生日志");
                logLines.add("2. 需要重启应用以应用新的日志配置");
                logLines.add("");
                logLines.add("提示：日志配置文件已创建，请重启应用后重试");
            }

            // 限制返回的行数
            if (logLines.size() > lines) {
                logLines = logLines.subList(0, lines);
            }

            return AjaxResult.success(logLines);
        } catch (Exception e) {
            log.error("读取日志失败", e);
            List<String> errorLines = new ArrayList<>();
            errorLines.add("读取日志失败: " + e.getMessage());
            errorLines.add("当前时间: " + new Date());
            return AjaxResult.error("读取日志失败: " + e.getMessage());
        }
    }
}
