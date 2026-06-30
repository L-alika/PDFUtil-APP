package com.pdfutil.pdf.controller;

import com.pdfutil.common.annotation.Log;
import com.pdfutil.common.config.PdfUtilConfig;
import com.pdfutil.common.core.controller.BaseController;
import com.pdfutil.common.core.domain.AjaxResult;
import com.pdfutil.common.core.page.TableDataInfo;
import com.pdfutil.common.enums.BusinessType;
import com.pdfutil.common.utils.FileNameValidator;
import com.pdfutil.common.utils.StringUtils;
import com.pdfutil.common.utils.file.FileUtils;
import com.pdfutil.pdf.domain.PdfConvertRecord;
import com.pdfutil.pdf.service.IPdfConvertRecordService;
import com.pdfutil.pdf.service.PdfConvertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.concurrent.Executor;

import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * PDF转换Controller
 *
 * @author Alika
 * @date 2025-01-28
 */
@RestController
@RequestMapping("/api/pdf/convert")
public class PdfConvertController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(PdfConvertController.class);

    // 【并发安全修复】为每个转换记录提供独立的锁对象
    private static final java.util.concurrent.ConcurrentMap<Long, Object> RECORD_LOCKS =
        new java.util.concurrent.ConcurrentHashMap<>();

    // 【并发控制】追踪正在运行的进度更新 Future，以便在转换完成/失败时及时取消，防止阻塞核心线程与数据库状态覆盖
    private static final java.util.concurrent.ConcurrentMap<Long, java.util.concurrent.Future<?>> PROGRESS_FUTURES =
        new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    private PdfConvertService pdfConvertService;

    @Autowired
    private IPdfConvertRecordService pdfConvertRecordService;

    @Autowired
    @Qualifier("pdfConvertExecutor")
    private Executor pdfConvertExecutor;

    @Autowired
    @Qualifier("progressUpdateExecutor")
    private Executor progressUpdateExecutor;

    // 失败原因最大长度（对应数据库字段varchar(500)）
    private static final int MAX_FAIL_REASON_LENGTH = 490;

    /**
     * 截断错误信息以适应数据库字段长度
     *
     * @param error 原始错误信息
     * @return 截断后的错误信息
     */
    private String truncateErrorMessage(String error) {
        if (error == null) {
            return null;
        }
        if (error.length() <= MAX_FAIL_REASON_LENGTH) {
            return error;
        }
        // 截断并添加省略号
        return error.substring(0, MAX_FAIL_REASON_LENGTH) + "...";
    }

    /**
     * 系统概览统计信息
     */
    @GetMapping("/dashboard/overview")
    public AjaxResult getDashboardOverview() {
        Map<String, Object> data = new HashMap<>();

        // 统计信息
        List<PdfConvertRecord> allRecords = pdfConvertRecordService.selectPdfConvertRecordList(new PdfConvertRecord());

        long totalCount = allRecords.size();
        long successCount = allRecords.stream().filter(r -> "2".equals(r.getStatus())).count();
        long failedCount = allRecords.stream().filter(r -> "3".equals(r.getStatus())).count();
        long processingCount = allRecords.stream().filter(r -> "1".equals(r.getStatus())).count();

        double successRate = totalCount > 0 ? (successCount * 100.0 / totalCount) : 0;

        data.put("totalCount", totalCount);
        data.put("successCount", successCount);
        data.put("failedCount", failedCount);
        data.put("processingCount", processingCount);
        data.put("successRate", String.format("%.1f", successRate));

        // 活动任务数
        data.put("activeTasks", processingCount);

        // 系统资源监控
        data.put("cpuUsage", getCpuUsage());
        data.put("memoryUsage", getMemoryUsage());
        data.put("diskSpace", getDiskSpace());

        return AjaxResult.success(data);
    }

    /**
     * 30天转换趋势
     */
    @GetMapping("/dashboard/trend")
    public AjaxResult getConversionTrend() {
        // TODO: 实现最近30天的转换趋势统计
        Map<String, Object> data = new HashMap<>();
        List<Map<String, Object>> trendData = new ArrayList<>();

        // 模拟数据
        Calendar cal = Calendar.getInstance();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

        for (int i = 29; i >= 0; i--) {
            cal.add(Calendar.DAY_OF_MONTH, -1);
            Map<String, Object> dayData = new HashMap<>();
            dayData.put("date", sdf.format(cal.getTime()));
            dayData.put("success", (int) (Math.random() * 50) + 10);
            dayData.put("failed", (int) (Math.random() * 5));
            trendData.add(dayData);
            cal.add(Calendar.DAY_OF_MONTH, 1); // 恢复
        }

        data.put("trend", trendData);
        return AjaxResult.success(data);
    }

    /**
     * 源文件类型分布
     */
    @GetMapping("/dashboard/fileTypeDistribution")
    public AjaxResult getFileTypeDistribution() {
        List<PdfConvertRecord> allRecords = pdfConvertRecordService.selectPdfConvertRecordList(new PdfConvertRecord());

        Map<String, Integer> typeCount = new HashMap<>();
        typeCount.put("PDF", 0);
        typeCount.put("JPEG", 0);
        typeCount.put("TIF", 0);
        typeCount.put("JPG", 0);
        typeCount.put("DOC", 0);
        typeCount.put("DOCX", 0);
        typeCount.put("XLS", 0);
        typeCount.put("XLSX", 0);
        typeCount.put("PPT", 0);
        typeCount.put("PPTX", 0);

        for (PdfConvertRecord record : allRecords) {
            String fileName = record.getSourceFileName();
            if (fileName != null) {
                String ext = getFileExtension(fileName).toUpperCase();
                typeCount.put(ext, typeCount.getOrDefault(ext, 0) + 1);
            }
        }

        int total = allRecords.size();
        List<Map<String, Object>> distribution = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : typeCount.entrySet()) {
            Map<String, Object> item = new HashMap<>();
            item.put("type", entry.getKey());
            item.put("count", entry.getValue());
            item.put("percentage", total > 0 ? String.format("%.1f", entry.getValue() * 100.0 / total) : "0");
            distribution.add(item);
        }

        return AjaxResult.success(distribution);
    }

    /**
     * 最近转换任务列表
     */
    @GetMapping("/dashboard/recentTasks")
    public TableDataInfo getRecentTasks() {
        // 使用PageUtils设置分页参数（内存分页）
        startPage();
        PdfConvertRecord query = new PdfConvertRecord();
        List<PdfConvertRecord> list = pdfConvertRecordService.selectPdfConvertRecordList(query);

        // 按时间倒序
        list.sort((a, b) -> {
            if (a.getCreateTime() == null) return 1;
            if (b.getCreateTime() == null) return -1;
            return b.getCreateTime().compareTo(a.getCreateTime());
        });

        return getDataTable(list);
    }

    /**
     * 单个文件转换
     */
    @Log(title = "PDF转换", businessType = BusinessType.OTHER)
    @PostMapping("/single")
    public AjaxResult convertSingle(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "outputDir", required = false) String outputDir,
            @RequestParam(value = "ocrLanguage", defaultValue = "chi_sim+chi_tra+eng") String ocrLanguage,
            @RequestParam(value = "operator", required = false, defaultValue = "anonymous") String operator,
            @RequestParam(value = "originalFilename", required = false) String originalFilename) {

        if (file.isEmpty()) {
            return AjaxResult.error("请选择要上传的文件");
        }

        try {
            // 验证文件格式
            // 优先使用前端传递的原始文件名（解决批量脚本中文件名被修改的问题）
            String fileName = StringUtils.isNotEmpty(originalFilename) ? originalFilename : file.getOriginalFilename();
            String extension = getFileExtension(fileName).toLowerCase();

            if (!Arrays.asList("pdf", "jpg", "jpeg", "tif", "tiff", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "ofd").contains(extension)) {
                return AjaxResult.error("不支持的文件格式，仅支持PDF、JPG、JPEG、TIF、DOC、DOCX、OFD格式");
            }

            // 【安全修复】验证文件名安全性，防止路径遍历注入
            if (!FileNameValidator.isSafeFileName(fileName)) {
                log.warn("检测到不安全的文件名: {}", fileName);
                return AjaxResult.error("文件名包含非法字符，请重命名后重新上传");
            }

            // 保存上传文件
            String uploadDir = getFileUploadDir();
            File destDir = new File(uploadDir);
            if (!destDir.exists()) {
                destDir.mkdirs();
            }

            // 【安全修复】使用安全的文件名构建路径
            final String sourcePath = uploadDir + File.separator + fileName;

            // 再次验证最终路径是否在允许的目录内
            if (!FileNameValidator.isPathAllowed(sourcePath, uploadDir)) {
                log.warn("文件路径不在允许的目录内: {}", sourcePath);
                return AjaxResult.error("文件路径验证失败");
            }

            File destFile = new File(sourcePath);
            file.transferTo(destFile);

            // 设置输出目录
            final String finalOutputDir;
            if (StringUtils.isEmpty(outputDir)) {
                finalOutputDir = getOutputDir();
            } else {
                // 【安全修复】验证自定义输出目录
                if (!FileNameValidator.isPathAllowed(outputDir, outputDir)) {
                    return AjaxResult.error("输出目录路径不安全");
                }
                finalOutputDir = outputDir;
            }

            // 创建转换记录
            final PdfConvertRecord record = new PdfConvertRecord();
            record.setSourceFileName(fileName);
            record.setSourceFilePath(sourcePath);

            // 【安全修复】生成安全的输出文件名（基于原始文件名，避免_tid后缀）
            String originalFileNameForTarget = StringUtils.isNotEmpty(originalFilename) ? originalFilename : fileName;
            String baseFileName = FileNameValidator.sanitizeFileName(
                originalFileNameForTarget.substring(0, originalFileNameForTarget.lastIndexOf('.'))
            );
            String targetExt = "ofd".equals(extension) ? ".ofd" : ".pdf";
            String targetFileName = baseFileName + targetExt;
            String targetFilePath = finalOutputDir + File.separator + targetFileName;

            // 验证输出文件路径是否在允许的目录内
            if (!FileNameValidator.isPathAllowed(targetFilePath, finalOutputDir)) {
                log.warn("输出文件路径不在允许的目录内: {}", targetFilePath);
                return AjaxResult.error("输出文件路径验证失败");
            }

            record.setTargetFileName(targetFileName);
            record.setTargetFilePath(targetFilePath);
            record.setConvertType(getConvertType(extension));
            record.setStatus("0"); // 待处理
            // 优先使用前端传递的操作人，其次使用登录用户，最后使用 anonymous
            String createBy = StringUtils.isNotEmpty(operator) ? operator :
                (getSysUser() != null ? getSysUser().getLoginName() : "anonymous");
            record.setCreateBy(createBy);

            pdfConvertRecordService.insertPdfConvertRecord(record);

            // 【稳定性修复】使用线程池异步执行转换
            final String finalFileName = fileName;
            final PdfConvertRecord finalRecord = record;
            pdfConvertExecutor.execute(() -> {
                try {
                    log.info("开始转换文件: {}", finalFileName);

                    finalRecord.setStatus("1"); // 转换中
                    finalRecord.setProgress(0); // 初始进度
                    pdfConvertRecordService.updatePdfConvertRecord(finalRecord);

                    // 阶段1：文件验证和准备（0% -> 15%）
                    updateProgressWithSteps(finalRecord, 0, 15, 5, 500, pdfConvertRecordService);

                    // 阶段2：OCR预处理准备（15% -> 25%）
                    updateProgressWithSteps(finalRecord, 15, 25, 4, 400, pdfConvertRecordService);

                    // 执行转换（失败时会抛出异常）
                    long startTime = System.currentTimeMillis();

                    // 【稳定性修复】使用线程池执行进度更新（25% -> 85%）
                    if (progressUpdateExecutor instanceof org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor) {
                        java.util.concurrent.Future<?> future = ((org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor) progressUpdateExecutor).submit(() -> {
                            try {
                                // 模拟转换过程中的进度，从25%逐步增长到85%
                                updateProgressWithSteps(finalRecord, 25, 85, 12, 800, pdfConvertRecordService);
                            } catch (Exception e) {
                                log.warn("进度更新异常: {}", e.getMessage());
                            }
                        });
                        PROGRESS_FUTURES.put(finalRecord.getId(), future);
                    }

                    // 实际执行转换
                    pdfConvertService.convertToDirectory(
                            sourcePath,
                            finalOutputDir,
                            finalRecord.getSourceFileName()  // 传递原始文件名，确保输出文件名正确
                    );

                    long endTime = System.currentTimeMillis();
                    log.info("文件转换完成: {}, 耗时: {} ms", finalFileName, (endTime - startTime));

                    // 取消进度更新任务，避免阻塞与状态覆写
                    cancelProgressFuture(finalRecord.getId());

                    // 阶段4：转换后处理和验证（85% -> 95%）
                    updateProgressWithSteps(finalRecord, 85, 95, 4, 300, pdfConvertRecordService);

                    // 阶段5：保存结果（95% -> 100%）
                    updateProgressWithSteps(finalRecord, 95, 100, 3, 200, pdfConvertRecordService);

                    // 转换成功
                    finalRecord.setStatus("2"); // 已完成
                    finalRecord.setProgress(100); // 完成
                    pdfConvertRecordService.updatePdfConvertRecord(finalRecord);
                    // 清理锁资源
                    cleanupRecordLock(finalRecord.getId());

                } catch (Exception e) {
                    log.error("文件转换失败: {}", finalFileName, e);
                    // 取消进度更新任务，避免阻塞与状态覆写
                    cancelProgressFuture(finalRecord.getId());

                    finalRecord.setStatus("3"); // 已失败
                    finalRecord.setFailReason(truncateErrorMessage(e.getMessage()));
                    finalRecord.setProgress(0); // 失败时重置进度
                    pdfConvertRecordService.updatePdfConvertRecord(finalRecord);
                    // 清理锁资源
                    cleanupRecordLock(finalRecord.getId());
                }
            });

            return AjaxResult.success("转换任务已提交，正在后台处理", record);

        } catch (Exception e) {
            log.error("文件上传失败", e);
            return AjaxResult.error("文件上传失败：" + e.getMessage());
        }
    }

    /**
     * 批量文件转换
     */
    @Log(title = "批量PDF转换", businessType = BusinessType.OTHER)
    @PostMapping("/batch")
    public AjaxResult convertBatch(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "outputDir", required = false) String outputDir,
            @RequestParam(value = "ocrLanguage", defaultValue = "chi_sim+chi_tra+eng") String ocrLanguage,
            @RequestParam(value = "operator", required = false, defaultValue = "anonymous") String operator) {

        if (files == null || files.length == 0) {
            return AjaxResult.error("请选择要上传的文件");
        }

        try {
            // 设置输出目录
            final String finalOutputDir;
            if (StringUtils.isEmpty(outputDir)) {
                finalOutputDir = getOutputDir();
            } else {
                // 【安全修复】验证自定义输出目录
                if (!FileNameValidator.isPathAllowed(outputDir, outputDir)) {
                    return AjaxResult.error("输出目录路径不安全");
                }
                finalOutputDir = outputDir;
            }

            final List<PdfConvertRecord> records = new ArrayList<>();
            int successCount = 0;
            int failCount = 0;

            for (MultipartFile file : files) {
                if (file.isEmpty()) {
                    failCount++;
                    continue;
                }

                try {
                    String fileName = file.getOriginalFilename();
                    String extension = getFileExtension(fileName).toLowerCase();

                    // 批量上传支持 Office 文档格式和 OFD
                    if (!Arrays.asList("pdf", "jpg", "jpeg", "tif", "tiff",
                                      "doc", "docx", "xls", "xlsx", "ppt", "pptx",
                                      "odt", "ods", "odp", "rtf", "ofd").contains(extension)) {
                        log.warn("批量上传中不支持的文件格式: {} (扩展名: {})", fileName, extension);
                        failCount++;
                        continue;
                    }

                    // 【安全修复】验证文件名安全性，防止路径遍历注入
                    if (!FileNameValidator.isSafeFileName(fileName)) {
                        log.warn("批量上传中检测到不安全的文件名: {}", fileName);
                        failCount++;
                        continue;
                    }

                    // 保存上传文件
                    String uploadDir = getFileUploadDir();
                    String sourcePath = uploadDir + File.separator + fileName;

                    // 【安全修复】验证路径是否在允许的目录内
                    if (!FileNameValidator.isPathAllowed(sourcePath, uploadDir)) {
                        log.warn("批量上传中文件路径不在允许的目录内: {}", sourcePath);
                        failCount++;
                        continue;
                    }

                    File destFile = new File(sourcePath);
                    file.transferTo(destFile);

                    // 创建转换记录
                    // 【安全修复】生成安全的输出文件名（基于原始文件名，避免_tid后缀）
                    String originalFileNameForTarget = fileName; // 批量转换没有originalFilename参数，直接使用fileName
                    String baseFileName = FileNameValidator.sanitizeFileName(
                        originalFileNameForTarget.substring(0, originalFileNameForTarget.lastIndexOf('.'))
                    );
                    String targetExt = "ofd".equals(extension) ? ".ofd" : ".pdf";
                    String targetFileName = baseFileName + targetExt;
                    String targetFilePath = finalOutputDir + File.separator + targetFileName;

                    // 【安全修复】验证输出文件路径是否在允许的目录内
                    if (!FileNameValidator.isPathAllowed(targetFilePath, finalOutputDir)) {
                        log.warn("批量上传中输出文件路径不在允许的目录内: {}", targetFilePath);
                        failCount++;
                        continue;
                    }

                    PdfConvertRecord record = new PdfConvertRecord();
                    record.setSourceFileName(fileName);
                    record.setSourceFilePath(sourcePath);
                    record.setTargetFileName(targetFileName);
                    record.setTargetFilePath(targetFilePath);
                    record.setConvertType(getConvertType(extension));
                    record.setStatus("0");
                    // 优先使用前端传递的操作人，其次使用登录用户，最后使用 anonymous
                    String createBy = StringUtils.isNotEmpty(operator) ? operator :
                        (getSysUser() != null ? getSysUser().getLoginName() : "anonymous");
                    record.setCreateBy(createBy);

                    pdfConvertRecordService.insertPdfConvertRecord(record);
                    records.add(record);
                    successCount++;

                } catch (Exception e) {
                    log.error("文件处理失败: {}", file.getOriginalFilename(), e);
                    failCount++;
                }
            }

            // 异步批量转换 - 每个任务独立线程并发执行，但错开启动时间
            if (!records.isEmpty()) {
                final int totalRecords = records.size();
                log.info("开始批量转换 {} 个文件", totalRecords);

                for (int i = 0; i < totalRecords; i++) {
                    final PdfConvertRecord finalBatchRecord = records.get(i);
                    final int taskIndex = i;

                    // 【稳定性修复】使用线程池执行转换任务，添加随机延迟避免进度同步
                    pdfConvertExecutor.execute(() -> {
                        try {
                            // 根据任务索引添加初始延迟（每批次错开500ms-2秒）
                            long initialDelay = 500 + (taskIndex * 300) + (long)(Math.random() * 1000);
                            Thread.sleep(initialDelay);

                            finalBatchRecord.setStatus("1");
                            finalBatchRecord.setProgress(0);
                            pdfConvertRecordService.updatePdfConvertRecord(finalBatchRecord);

                            // 阶段1：文件验证和准备（0% -> 15%）
                            updateProgressWithSteps(finalBatchRecord, 0, 15, 5, 400, pdfConvertRecordService);

                            // 阶段2：预处理准备（15% -> 25%）
                            updateProgressWithSteps(finalBatchRecord, 15, 25, 4, 300, pdfConvertRecordService);

                            // 【稳定性修复】使用线程池执行进度更新（25% -> 85%）
                            if (progressUpdateExecutor instanceof org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor) {
                                java.util.concurrent.Future<?> future = ((org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor) progressUpdateExecutor).submit(() -> {
                                    try {
                                        // 添加随机性到进度更新间隔
                                        long randomInterval = 600 + (long)(Math.random() * 400);
                                        updateProgressWithSteps(finalBatchRecord, 25, 85, 10, randomInterval, pdfConvertRecordService);
                                    } catch (Exception e) {
                                        log.warn("进度更新异常: {}", e.getMessage());
                                    }
                                });
                                PROGRESS_FUTURES.put(finalBatchRecord.getId(), future);
                            }

                            // 执行转换
                            long startTime = System.currentTimeMillis();
                            pdfConvertService.convertToDirectory(
                                    finalBatchRecord.getSourceFilePath(),
                                    finalOutputDir,
                                    finalBatchRecord.getSourceFileName()  // 传递原始文件名，确保输出文件名正确
                            );

                            long endTime = System.currentTimeMillis();

                            // 取消进度更新任务，避免阻塞与状态覆写
                            cancelProgressFuture(finalBatchRecord.getId());

                            // 阶段4：转换后处理（85% -> 95%）
                            updateProgressWithSteps(finalBatchRecord, 85, 95, 4, 200, pdfConvertRecordService);

                            // 阶段5：完成（95% -> 100%）
                            updateProgressWithSteps(finalBatchRecord, 95, 100, 3, 150, pdfConvertRecordService);

                            // 转换成功
                            finalBatchRecord.setStatus("2");
                            finalBatchRecord.setProgress(100);
                            pdfConvertRecordService.updatePdfConvertRecord(finalBatchRecord);
                            // 清理锁资源
                            cleanupRecordLock(finalBatchRecord.getId());

                            log.info("批量转换 [{}/{}] 完成: {}, 耗时: {} ms",
                                taskIndex + 1, totalRecords,
                                finalBatchRecord.getSourceFileName(),
                                (endTime - startTime));

                        } catch (Exception e) {
                            log.error("批量转换失败: {}", finalBatchRecord.getSourceFileName(), e);
                            // 取消进度更新任务，避免阻塞与状态覆写
                            cancelProgressFuture(finalBatchRecord.getId());

                            finalBatchRecord.setStatus("3");
                            finalBatchRecord.setFailReason(truncateErrorMessage(e.getMessage()));
                            finalBatchRecord.setProgress(0);
                            pdfConvertRecordService.updatePdfConvertRecord(finalBatchRecord);
                            // 清理锁资源
                            cleanupRecordLock(finalBatchRecord.getId());
                        }
                    });
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("total", files.length);
            result.put("success", successCount);
            result.put("failed", failCount);
            result.put("message", String.format("成功提交 %d 个转换任务", successCount));

            // 【Bug修复】返回创建的任务记录列表，便于前端显示任务列表
            List<Map<String, Object>> taskList = new ArrayList<>();
            for (PdfConvertRecord record : records) {
                Map<String, Object> taskInfo = new HashMap<>();
                taskInfo.put("id", record.getId());
                taskInfo.put("sourceFileName", record.getSourceFileName());
                taskInfo.put("convertType", record.getConvertType());
                taskInfo.put("status", record.getStatus());
                taskInfo.put("progress", record.getProgress());
                taskInfo.put("createTime", record.getCreateTime());
                taskList.add(taskInfo);
            }
            result.put("tasks", taskList);

            log.info("批量上传返回任务列表: {} 个任务", taskList.size());

            return AjaxResult.success(result);

        } catch (Exception e) {
            log.error("批量转换失败", e);
            return AjaxResult.error("批量转换失败：" + e.getMessage());
        }
    }

    /**
     * 查询转换任务列表
     */
    @GetMapping("/tasks")
    public TableDataInfo listTasks(
            @RequestParam(value = "pageNum", defaultValue = "1") Integer pageNum,
            @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize,
            @RequestParam(value = "sourceFileName", required = false) String sourceFileName,
            @RequestParam(value = "status", required = false) String status) {
        // 直接使用@RequestParam获取分页参数
        System.out.println("接收到的分页参数: pageNum=" + pageNum + ", pageSize=" + pageSize);
        System.out.println("接收到的查询参数: sourceFileName=" + sourceFileName + ", status=" + status);
        
        // 构建查询条件
        PdfConvertRecord record = new PdfConvertRecord();
        record.setSourceFileName(sourceFileName);
        record.setStatus(status);
        
        // 查询所有数据（用于获取总记录数）
        System.out.println("开始查询所有数据（用于获取总记录数）");
        List<PdfConvertRecord> allList = pdfConvertRecordService.selectPdfConvertRecordList(record);
        int total = allList.size();
        System.out.println("总记录数: " + total);
        
        // 计算分页参数
        int startIndex = (pageNum - 1) * pageSize;
        int endIndex = Math.min(startIndex + pageSize, total);
        System.out.println("分页参数: startIndex=" + startIndex + ", endIndex=" + endIndex);
        
        // 手动分页
        List<PdfConvertRecord> list;
        if (startIndex >= total) {
            list = new ArrayList<>();
        } else {
            list = allList.subList(startIndex, endIndex);
        }
        System.out.println("查询结果数量: " + list.size());
        
        // 构建响应
        TableDataInfo rspData = new TableDataInfo();
        rspData.setCode(0);
        rspData.setRows(list);
        rspData.setTotal(total);
        System.out.println("返回的总记录数: " + rspData.getTotal());
        return rspData;
    }

    /**
     * 获取任务详情
     */
    @GetMapping("/task/{id}")
    public AjaxResult getTaskDetail(@PathVariable("id") Long id) {
        PdfConvertRecord record = pdfConvertRecordService.selectPdfConvertRecordById(id);
        return AjaxResult.success(record);
    }

    /**
     * 重试失败任务
     */
    @Log(title = "重试转换任务", businessType = BusinessType.OTHER)
    @PostMapping("/task/{id}/retry")
    public AjaxResult retryTask(@PathVariable("id") Long id) {
        PdfConvertRecord record = pdfConvertRecordService.selectPdfConvertRecordById(id);

        if (record == null) {
            return AjaxResult.error("任务不存在");
        }

        if (!"3".equals(record.getStatus())) {
            return AjaxResult.error("只能重试失败的任务");
        }

        // 检查源文件是否存在
        File sourceFile = new File(record.getSourceFilePath());
        if (!sourceFile.exists()) {
            log.warn("重试任务失败，源文件不存在: {}", record.getSourceFilePath());
            return AjaxResult.error("源文件不存在，无法重试。请重新上传文件。");
        }

        // 【稳定性修复】使用线程池异步重试
        final PdfConvertRecord finalRecord = record;
        final File finalSourceFile = sourceFile;  // 传递已检查的文件对象
        pdfConvertExecutor.execute(() -> {
            try {
                log.info("开始重试任务: {}", finalRecord.getSourceFileName());

                finalRecord.setStatus("1");
                finalRecord.setProgress(0);
                finalRecord.setFailReason(null);
                pdfConvertRecordService.updatePdfConvertRecord(finalRecord);

                // 阶段1：准备阶段（0% -> 20%）
                updateProgressWithSteps(finalRecord, 0, 20, 5, 400, pdfConvertRecordService);

                // 【稳定性修复】使用线程池执行进度更新（20% -> 80%）
                if (progressUpdateExecutor instanceof org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor) {
                    java.util.concurrent.Future<?> future = ((org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor) progressUpdateExecutor).submit(() -> {
                        try {
                            updateProgressWithSteps(finalRecord, 20, 80, 10, 600, pdfConvertRecordService);
                        } catch (Exception e) {
                            log.warn("进度更新异常: {}", e.getMessage());
                        }
                    });
                    PROGRESS_FUTURES.put(finalRecord.getId(), future);
                }

                // 使用convertToDirectory方法进行转换
                File targetFile = new File(finalRecord.getTargetFilePath());
                String outputDir = targetFile.getParent();

                long startTime = System.currentTimeMillis();

                // 执行转换（失败时会抛出异常）
                pdfConvertService.convertToDirectory(
                        finalSourceFile.getPath(),
                        outputDir,
                        finalRecord.getSourceFileName()  // 传递原始文件名，确保输出文件名正确
                );

                long endTime = System.currentTimeMillis();

                // 取消进度更新任务，避免阻塞与状态覆写
                cancelProgressFuture(finalRecord.getId());

                // 阶段4：完成处理（80% -> 100%）
                updateProgressWithSteps(finalRecord, 80, 100, 6, 250, pdfConvertRecordService);

                // 转换成功
                finalRecord.setStatus("2");
                finalRecord.setProgress(100);
                pdfConvertRecordService.updatePdfConvertRecord(finalRecord);
                // 清理锁资源
                cleanupRecordLock(finalRecord.getId());

                log.info("任务重试成功: {}, 耗时: {} ms", finalRecord.getSourceFileName(), (endTime - startTime));

            } catch (Exception e) {
                log.error("任务重试失败: {}", finalRecord.getSourceFileName(), e);
                // 取消进度更新任务，避免阻塞与状态覆写
                cancelProgressFuture(finalRecord.getId());

                finalRecord.setStatus("3");
                finalRecord.setFailReason(truncateErrorMessage(e.getMessage()));
                finalRecord.setProgress(0);
                pdfConvertRecordService.updatePdfConvertRecord(finalRecord);
                // 清理锁资源
                cleanupRecordLock(finalRecord.getId());
            }
        });

        return AjaxResult.success("重试任务已提交");
    }

    /**
     * 下载转换后的文件
     */
    @GetMapping("/download/{id}")
    public void downloadFile(@PathVariable("id") Long id, HttpServletResponse response) throws IOException {
        PdfConvertRecord record = pdfConvertRecordService.selectPdfConvertRecordById(id);

        if (record == null) {
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":500,\"msg\":\"任务不存在\"}");
            return;
        }

        if (!"2".equals(record.getStatus())) {
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":500,\"msg\":\"任务未完成，无法下载\"}");
            return;
        }

        File file = new File(record.getTargetFilePath());
        if (!file.exists()) {
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":500,\"msg\":\"文件不存在\"}");
            return;
        }

        // 设置下载响应头
        response.setContentType("application/octet-stream");
        response.setHeader("Content-Disposition",
                "attachment; filename=" + new String(record.getTargetFileName().getBytes("UTF-8"), "ISO-8859-1"));
        response.setContentLengthLong(file.length());

        // 复制文件到响应流
        org.apache.commons.io.IOUtils.copy(new java.io.FileInputStream(file), response.getOutputStream());
        response.flushBuffer();
    }

    /**
     * 批量导出转换后的文件（打包成ZIP）
     */
    @Log(title = "批量导出PDF文件", businessType = BusinessType.EXPORT)
    @PostMapping("/batchExport")
    public void batchExportFiles(@RequestParam("ids") String idsStr, HttpServletResponse response) throws IOException {
        log.info("批量导出请求, ids参数: {}", idsStr);

        if (StringUtils.isEmpty(idsStr)) {
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":500,\"msg\":\"请选择要导出的任务\"}");
            return;
        }

        // 解析ids参数，支持逗号分隔或数组格式
        Long[] ids;
        try {
            if (idsStr.contains("[")) {
                // 处理前端axios发送的数组格式: [1,2,3] 或 ids[]=1&ids[]=2
                String cleaned = idsStr.replaceAll("\\[", "").replaceAll("\\]", "").replaceAll("ids=", "");
                String[] idArray = cleaned.split(",");
                ids = new Long[idArray.length];
                for (int i = 0; i < idArray.length; i++) {
                    ids[i] = Long.parseLong(idArray[i].trim());
                }
            } else {
                // 处理逗号分隔的格式: 1,2,3
                String[] idArray = idsStr.split(",");
                ids = new Long[idArray.length];
                for (int i = 0; i < idArray.length; i++) {
                    ids[i] = Long.parseLong(idArray[i].trim());
                }
            }
            log.info("解析后的ids: {}", (Object) ids);
        } catch (NumberFormatException e) {
            log.error("ID格式错误: {}", idsStr, e);
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().write("{\"code\":500,\"msg\":\"ID格式错误\"}");
            return;
        }

        if (ids.length == 0) {
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":500,\"msg\":\"请选择要导出的任务\"}");
            return;
        }

        // 收集所有有效的PDF文件
        List<File> pdfFiles = new ArrayList<>();
        List<String> fileNames = new ArrayList<>();
        List<String> errorMessages = new ArrayList<>();

        for (Long id : ids) {
            try {
                PdfConvertRecord record = pdfConvertRecordService.selectPdfConvertRecordById(id);

                if (record == null) {
                    errorMessages.add("任务ID " + id + " 不存在");
                    continue;
                }

                if (!"2".equals(record.getStatus())) {
                    errorMessages.add("任务 " + record.getSourceFileName() + " 未完成");
                    continue;
                }

                File file = new File(record.getTargetFilePath());
                if (!file.exists()) {
                    errorMessages.add("任务 " + record.getSourceFileName() + " 的文件不存在");
                    continue;
                }

                pdfFiles.add(file);
                fileNames.add(record.getTargetFileName());
                log.info("添加文件到ZIP: {}", file.getName());
            } catch (Exception e) {
                log.error("处理任务ID {} 时出错", id, e);
                errorMessages.add("任务ID " + id + " 处理失败: " + e.getMessage());
            }
        }

        // 如果没有有效文件
        if (pdfFiles.isEmpty()) {
            log.warn("没有可导出的文件: {}", errorMessages);
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().write("{\"code\":500,\"msg\":\"没有可导出的文件：" + String.join("; ", errorMessages) + "\"}");
            return;
        }

        // 创建ZIP文件名
        String timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        String zipFileName = "pdf_export_" + timestamp + ".zip";

        log.info("开始创建ZIP文件: {}, 包含 {} 个文件", zipFileName, pdfFiles.size());

        // 设置响应头
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition",
                "attachment; filename=" + new String(zipFileName.getBytes("UTF-8"), "ISO-8859-1"));

        java.util.zip.ZipOutputStream zos = null;
        java.io.FileInputStream fis = null;

        try {
            // 直接创建输出流到响应
            zos = new java.util.zip.ZipOutputStream(response.getOutputStream());

            // 添加文件到ZIP
            for (int i = 0; i < pdfFiles.size(); i++) {
                File file = pdfFiles.get(i);
                String fileName = fileNames.get(i);

                log.info("添加文件到ZIP [{}]: {} ({})", i + 1, fileName, file.length());

                java.util.zip.ZipEntry zipEntry = new java.util.zip.ZipEntry(fileName);
                zos.putNextEntry(zipEntry);

                fis = new java.io.FileInputStream(file);
                byte[] buffer = new byte[8192]; // 使用更大的缓冲区
                int len;
                while ((len = fis.read(buffer)) > 0) {
                    zos.write(buffer, 0, len);
                }
                fis.close();
                fis = null;
                zos.closeEntry();
            }

            // 完成ZIP文件
            zos.finish();
            zos.close();
            zos = null;

            response.flushBuffer();

            log.info("批量导出成功: 共 {} 个文件, {} 个失败", pdfFiles.size(), errorMessages.size());

        } catch (Exception e) {
            log.error("批量导出失败", e);
            // 如果还没有提交响应，可以发送错误信息
            if (!response.isCommitted()) {
                response.setContentType("application/json;charset=UTF-8");
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.getWriter().write("{\"code\":500,\"msg\":\"批量导出失败：" + e.getMessage() + "\"}");
            }
        } finally {
            // 确保所有流都关闭
            if (fis != null) {
                try {
                    fis.close();
                } catch (Exception e) {
                    log.warn("关闭文件输入流失败", e);
                }
            }
            if (zos != null) {
                try {
                    zos.close();
                } catch (Exception e) {
                    log.warn("关闭ZIP输出流失败", e);
                }
            }
        }
    }

    /**
     * 删除转换任务
     */
    @Log(title = "删除转换任务", businessType = BusinessType.DELETE)
    @DeleteMapping("/task/{id}")
    public AjaxResult deleteTask(@PathVariable("id") Long id) {
        PdfConvertRecord record = pdfConvertRecordService.selectPdfConvertRecordById(id);

        if (record == null) {
            return AjaxResult.error("任务不存在");
        }

        // 检查任务是否正在处理中
        if ("1".equals(record.getStatus())) {
            return AjaxResult.error("任务正在处理中，无法删除");
        }

        try {
            // 删除源文件
            if (record.getSourceFilePath() != null) {
                File sourceFile = new File(record.getSourceFilePath());
                if (sourceFile.exists()) {
                    sourceFile.delete();
                }
            }

            // 删除目标文件
            if (record.getTargetFilePath() != null) {
                File targetFile = new File(record.getTargetFilePath());
                if (targetFile.exists()) {
                    targetFile.delete();
                }
            }

            // 删除数据库记录
            pdfConvertRecordService.deletePdfConvertRecordById(id);

            return AjaxResult.success("删除成功");
        } catch (Exception e) {
            log.error("删除任务失败: {}", record.getSourceFileName(), e);
            return AjaxResult.error("删除失败：" + e.getMessage());
        }
    }

    /**
     * 批量删除转换任务
     */
    @Log(title = "批量删除转换任务", businessType = BusinessType.DELETE)
    @DeleteMapping("/tasks/{ids}")
    public AjaxResult deleteTasks(@PathVariable Long[] ids) {
        int successCount = 0;
        int failCount = 0;

        for (Long id : ids) {
            PdfConvertRecord record = pdfConvertRecordService.selectPdfConvertRecordById(id);
            if (record == null) {
                failCount++;
                continue;
            }

            if ("1".equals(record.getStatus())) {
                failCount++;
                continue;
            }

            try {
                // 删除源文件
                if (record.getSourceFilePath() != null) {
                    File sourceFile = new File(record.getSourceFilePath());
                    if (sourceFile.exists()) {
                        sourceFile.delete();
                    }
                }

                // 删除目标文件
                if (record.getTargetFilePath() != null) {
                    File targetFile = new File(record.getTargetFilePath());
                    if (targetFile.exists()) {
                        targetFile.delete();
                    }
                }

                pdfConvertRecordService.deletePdfConvertRecordById(id);
                successCount++;
            } catch (Exception e) {
                log.error("删除任务失败: {}", record.getSourceFileName(), e);
                failCount++;
            }
        }

        if (successCount == 0) {
            return AjaxResult.error("删除失败，没有任务被删除");
        }

        String message = String.format("成功删除 %d 个任务", successCount);
        if (failCount > 0) {
            message += String.format("，失败 %d 个", failCount);
        }

        return AjaxResult.success(message);
    }

    /**
     * 获取系统资源使用情况
     */
    private Map<String, Object> getSystemResources() {
        Map<String, Object> resources = new HashMap<>();

        // CPU使用率
        resources.put("cpuUsage", getCpuUsage());

        // 内存使用率
        resources.put("memoryUsage", getMemoryUsage());

        // 磁盘空间
        resources.put("diskSpace", getDiskSpace());

        return resources;
    }

    private String getCpuUsage() {
        try {
            // 获取操作系统的MXBean
            com.sun.management.OperatingSystemMXBean osBean =
                (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();

            // 获取CPU使用率（已使用的CPU时间 / 可用的CPU时间）
            double cpuUsage = osBean.getSystemCpuLoad() * 100;

            // 如果返回值为-1表示不支持，返回0
            if (cpuUsage < 0) {
                cpuUsage = 0;
            }

            return String.format("%.1f", cpuUsage);
        } catch (Exception e) {
            log.error("获取CPU使用率失败", e);
            return "0.0";
        }
    }

    private String getMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        double usage = (usedMemory * 100.0) / totalMemory;
        return String.format("%.1f", usage);
    }

    private String getDiskSpace() {
        try {
            // 获取系统所有根分区
            java.io.File[] roots = java.io.File.listRoots();

            // 计算总空间和可用空间
            long totalSpace = 0;
            long freeSpace = 0;

            for (java.io.File root : roots) {
                totalSpace += root.getTotalSpace();
                freeSpace += root.getUsableSpace();
            }

            // 格式化为可读的字符串
            long usedSpace = totalSpace - freeSpace;
            double usedTB = usedSpace / (1024.0 * 1024 * 1024 * 1024);
            double totalTB = totalSpace / (1024.0 * 1024 * 1024 * 1024);
            double usagePercent = (usedSpace * 100.0) / totalSpace;

            if (totalTB >= 1) {
                return String.format("%.1f TB / %.1f TB (已用%.1f%%)", usedTB, totalTB, usagePercent);
            } else {
                double usedGB = usedSpace / (1024.0 * 1024 * 1024);
                double totalGB = totalSpace / (1024.0 * 1024 * 1024);
                return String.format("%.1f GB / %.1f GB (已用%.1f%%)", usedGB, totalGB, usagePercent);
            }
        } catch (Exception e) {
            log.error("获取磁盘空间失败", e);
            return "未知";
        }
    }

    private String getFileUploadDir() {
        String uploadDir = PdfUtilConfig.getPdfUploadDir();
        if (StringUtils.isEmpty(uploadDir)) {
            // 如果配置为空，使用默认路径
            uploadDir = System.getProperty("java.io.tmpdir") + "/pdf_uploads";
        }
        // 确保目录存在
        File dir = new File(uploadDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return uploadDir;
    }

    private String getOutputDir() {
        String outputDir = PdfUtilConfig.getPdfOutputDir();
        if (StringUtils.isEmpty(outputDir)) {
            // 如果配置为空，使用默认路径
            outputDir = System.getProperty("java.io.tmpdir") + "/pdf_output";
        }
        // 确保目录存在
        File dir = new File(outputDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return outputDir;
    }

    private String getConvertType(String extension) {
        switch (extension) {
            case "ofd":
                return "OFD-DOUBLE";
            case "pdf":
                return "PDF-DOUBLE";
            case "jpg":
                return "JPG-PDF";
            case "jpeg":
                return "JPEG-PDF";
            case "tif":
            case "tiff":
                return "TIF-PDF";
            case "doc":
                return "DOC-PDF";
            case "docx":
                return "DOCX-PDF";
            case "xls":
                return "XLS-PDF";
            case "xlsx":
                return "XLSX-PDF";
            case "ppt":
                return "PPT-PDF";
            case "pptx":
                return "PPTX-PDF";
            default:
                return "UNKNOWN";
        }
    }

    /**
     * 获取文件扩展名
     */
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

    /**
     * 模拟平滑的进度更新
     *
     * 【并发安全修复】使用record ID作为锁，确保同一record的更新是串行的
     *
     * @param record 转换记录
     * @param startProgress 起始进度
     * @param endProgress 结束进度
     * @param steps 进度步数
     * @param stepInterval 每步间隔（毫秒）
     * @param service 服务接口
     */
    private void updateProgressWithSteps(PdfConvertRecord record, int startProgress,
                                         int endProgress, int steps, long stepInterval,
                                         IPdfConvertRecordService service) {
        // 获取或创建该record的锁对象
        Object lock = RECORD_LOCKS.computeIfAbsent(record.getId(), k -> new Object());

        synchronized (lock) {
            try {
                if (steps <= 0) steps = 1;
                int progressIncrement = (endProgress - startProgress) / steps;

                for (int i = 0; i < steps; i++) {
                    int currentProgress = startProgress + (progressIncrement * i);
                    // 确保不超过目标进度
                    if (currentProgress > endProgress) {
                        currentProgress = endProgress;
                    }
                    record.setProgress(currentProgress);
                    service.updatePdfConvertRecord(record);

                    // 最后一步不需要等待
                    if (i < steps - 1) {
                        Thread.sleep(stepInterval);
                    }
                }

                // 确保最终达到目标进度
                if (record.getProgress() < endProgress) {
                    record.setProgress(endProgress);
                    service.updatePdfConvertRecord(record);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("进度更新被中断");
            } catch (Exception e) {
                log.error("进度更新失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 清理已完成的record锁（可选，用于释放内存）
     *
     * @param recordId 转换记录ID
     */
    private void cleanupRecordLock(Long recordId) {
        if (recordId != null) {
            RECORD_LOCKS.remove(recordId);
            log.debug("清理record锁: {}", recordId);
        }
    }

    /**
     * 取消并中断指定记录的进度更新任务
     *
     * @param recordId 转换记录ID
     */
    private void cancelProgressFuture(Long recordId) {
        if (recordId != null) {
            java.util.concurrent.Future<?> future = PROGRESS_FUTURES.remove(recordId);
            if (future != null) {
                future.cancel(true); // 传入true以发送线程中断信号
                log.debug("已中断并取消 record {} 的进度模拟更新", recordId);
            }
        }
    }
}
