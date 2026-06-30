package com.pdfutil.pdf.controller;

import com.pdfutil.common.annotation.Log;
import com.pdfutil.common.config.PdfUtilConfig;
import com.pdfutil.common.core.controller.BaseController;
import com.pdfutil.common.core.domain.AjaxResult;
import com.pdfutil.common.core.page.TableDataInfo;
import com.pdfutil.common.enums.BusinessType;
import com.pdfutil.common.utils.FileNameValidator;
import com.pdfutil.common.utils.StringUtils;
import com.pdfutil.pdf.domain.PdfConvertRecord;
import com.pdfutil.pdf.service.IPdfConvertRecordService;
import com.pdfutil.pdf.service.PdfConvertSimpleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.util.*;

/**
 * PDF转换简化接口Controller - 供其他系统调用
 *
 * 特点：
 * 1. 无进度更新（无进度条干扰）
 * 2. 只更新状态：等待中 -> 转换中 -> 成功/失败
 * 3. 适合后台任务和系统集成
 *
 * @author Alika
 * @date 2025-01-30
 */
@RestController
@RequestMapping("/api/pdf/convert/simple")
public class PdfConvertSimpleController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(PdfConvertSimpleController.class);

    @Autowired
    private PdfConvertSimpleService pdfConvertSimpleService;

    @Autowired
    private IPdfConvertRecordService pdfConvertRecordService;

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
     * 单个文件转换（简化版）
     */
    @Log(title = "PDF转换（简化版）", businessType = BusinessType.OTHER)
    @PostMapping("/single")
    public AjaxResult convertSingle(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "ocrLanguage", defaultValue = "chi_sim+chi_tra+eng") String ocrLanguage,
            @RequestParam(value = "operator", required = false, defaultValue = "system") String operator,
            @RequestParam(value = "originalFilename", required = false) String originalFilename) {

        if (file.isEmpty()) {
            return AjaxResult.error("请选择要上传的文件");
        }

        try {
            // 验证文件格式
            // 优先使用前端传递的原始文件名（解决批量脚本中文件名被修改的问题）
            String fileName = StringUtils.isNotEmpty(originalFilename) ? originalFilename : file.getOriginalFilename();
            String extension = getFileExtension(fileName).toLowerCase();

            if (!Arrays.asList("pdf", "jpg", "jpeg", "tif", "tiff", "doc", "docx", "xls", "xlsx", "ppt", "pptx").contains(extension)) {
                return AjaxResult.error("不支持的文件格式，仅支持PDF、JPG、JPEG、TIF、DOC、DOCX格式");
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
            final String finalOutputDir = getOutputDir();

            // 创建转换记录
            final PdfConvertRecord record = new PdfConvertRecord();
            record.setSourceFileName(fileName);
            record.setSourceFilePath(sourcePath);

            // 生成输出文件名：将原扩展名改为.pdf（基于原始文件名，避免_tid后缀）
            String originalFileNameForTarget = StringUtils.isNotEmpty(originalFilename) ? originalFilename : fileName;
            String baseFileName = FileNameValidator.sanitizeFileName(
                originalFileNameForTarget.substring(0, originalFileNameForTarget.lastIndexOf('.'))
            );
            String targetFileName = baseFileName + ".pdf";
            String targetFilePath = finalOutputDir + File.separator + targetFileName;

            record.setTargetFileName(targetFileName);
            record.setTargetFilePath(targetFilePath);
            record.setConvertType(getConvertType(extension));
            record.setStatus("0"); // 待处理
            record.setProgress(0); // 简化版不更新进度
            // 优先使用前端传递的操作人，其次使用登录用户，最后使用 system
            String createBy = StringUtils.isNotEmpty(operator) ? operator :
                (getSysUser() != null ? getSysUser().getLoginName() : "system");
            record.setCreateBy(createBy);

            pdfConvertRecordService.insertPdfConvertRecord(record);

            // 异步执行转换（无进度更新）
            final PdfConvertRecord finalRecord = record;
            new Thread(() -> {
                try {
                    log.info("开始转换文件（简化版）: {}", finalRecord.getSourceFileName());

                    // 更新状态为转换中
                    finalRecord.setStatus("1"); // 转换中
                    pdfConvertRecordService.updatePdfConvertRecord(finalRecord);

                    // 执行转换（不更新进度）
                    long startTime = System.currentTimeMillis();
                    pdfConvertSimpleService.convertToDirectory(
                            finalRecord.getSourceFilePath(),
                            finalOutputDir,
                            finalRecord.getSourceFileName()  // 传递原始文件名，确保输出文件名正确
                    );
                    long endTime = System.currentTimeMillis();
                    log.info("文件转换完成（简化版）: {}, 耗时: {} ms",
                            finalRecord.getSourceFileName(), (endTime - startTime));

                    // 转换成功
                    finalRecord.setStatus("2"); // 已完成
                    finalRecord.setProgress(100); // 完成时设置为100
                    pdfConvertRecordService.updatePdfConvertRecord(finalRecord);

                } catch (Exception e) {
                    log.error("文件转换失败（简化版）: {}", finalRecord.getSourceFileName(), e);
                    finalRecord.setStatus("3"); // 已失败
                    finalRecord.setFailReason(truncateErrorMessage(e.getMessage()));
                    finalRecord.setProgress(0); // 失败时重置进度
                    pdfConvertRecordService.updatePdfConvertRecord(finalRecord);
                }
            }).start();

            // 立即返回任务ID
            Map<String, Object> result = new HashMap<>();
            result.put("taskId", record.getId());
            result.put("message", "转换任务已提交");

            return AjaxResult.success(result);

        } catch (Exception e) {
            log.error("文件上传失败", e);
            return AjaxResult.error("文件上传失败：" + e.getMessage());
        }
    }

    /**
     * 批量文件转换（简化版）
     */
    @Log(title = "批量PDF转换（简化版）", businessType = BusinessType.OTHER)
    @PostMapping("/batch")
    public AjaxResult convertBatch(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "ocrLanguage", defaultValue = "chi_sim+chi_tra+eng") String ocrLanguage,
            @RequestParam(value = "operator", required = false, defaultValue = "system") String operator) {

        if (files == null || files.length == 0) {
            return AjaxResult.error("请选择要上传的文件");
        }

        try {
            // 设置输出目录
            final String finalOutputDir = getOutputDir();

            final List<PdfConvertRecord> records = new ArrayList<>();
            int successCount = 0;
            int failCount = 0;
            final List<Long> taskIds = new ArrayList<>();

            for (MultipartFile file : files) {
                if (file.isEmpty()) {
                    failCount++;
                    continue;
                }

                try {
                    String fileName = file.getOriginalFilename();
                    String extension = getFileExtension(fileName).toLowerCase();

                    if (!Arrays.asList("pdf", "jpg", "jpeg", "tif", "tiff", "doc", "docx", "xls", "xlsx", "ppt", "pptx").contains(extension)) {
                        failCount++;
                        continue;
                    }

                    // 【安全修复】验证文件名安全性，防止路径遍历注入
                    if (!FileNameValidator.isSafeFileName(fileName)) {
                        log.warn("批量转换检测到不安全的文件名: {}", fileName);
                        failCount++;
                        continue;
                    }

                    // 保存上传文件
                    String uploadDir = getFileUploadDir();
                    String sourcePath = uploadDir + File.separator + fileName;

                    // 【安全修复】验证最终路径是否在允许的目录内
                    if (!FileNameValidator.isPathAllowed(sourcePath, uploadDir)) {
                        log.warn("批量转换文件路径不在允许的目录内: {}", sourcePath);
                        failCount++;
                        continue;
                    }

                    File destFile = new File(sourcePath);
                    file.transferTo(destFile);

                    // 创建转换记录（基于原始文件名，避免_tid后缀）
                    String baseFileName = FileNameValidator.sanitizeFileName(
                        fileName.substring(0, fileName.lastIndexOf('.'))
                    );
                    String targetFileName = baseFileName + ".pdf";
                    String targetFilePath = finalOutputDir + File.separator + targetFileName;

                    PdfConvertRecord record = new PdfConvertRecord();
                    record.setSourceFileName(fileName);
                    record.setSourceFilePath(sourcePath);
                    record.setTargetFileName(targetFileName);
                    record.setTargetFilePath(targetFilePath);
                    record.setConvertType(getConvertType(extension));
                    record.setStatus("0");
                    record.setProgress(0);
                    // 优先使用前端传递的操作人，其次使用登录用户，最后使用 system
                    String createBy = StringUtils.isNotEmpty(operator) ? operator :
                        (getSysUser() != null ? getSysUser().getLoginName() : "system");
                    record.setCreateBy(createBy);

                    pdfConvertRecordService.insertPdfConvertRecord(record);
                    records.add(record);
                    taskIds.add(record.getId());
                    successCount++;

                } catch (Exception e) {
                    log.error("文件处理失败: {}", file.getOriginalFilename(), e);
                    failCount++;
                }
            }

            // 异步批量转换（无进度更新）
            if (!records.isEmpty()) {
                final int totalRecords = records.size();
                log.info("开始批量转换（简化版） {} 个文件", totalRecords);

                for (int i = 0; i < totalRecords; i++) {
                    final PdfConvertRecord finalBatchRecord = records.get(i);

                    // 为每个文件创建独立的转换线程
                    new Thread(() -> {
                        try {
                            log.info("开始转换文件（简化版）: {}", finalBatchRecord.getSourceFileName());

                            // 更新状态为转换中
                            finalBatchRecord.setStatus("1");
                            pdfConvertRecordService.updatePdfConvertRecord(finalBatchRecord);

                            // 执行转换（不更新进度）
                            long startTime = System.currentTimeMillis();
                            pdfConvertSimpleService.convertToDirectory(
                                    finalBatchRecord.getSourceFilePath(),
                                    finalOutputDir,
                                    finalBatchRecord.getSourceFileName()  // 传递原始文件名，确保输出文件名正确
                            );
                            long endTime = System.currentTimeMillis();

                            log.info("批量转换完成（简化版）: {}, 耗时: {} ms",
                                    finalBatchRecord.getSourceFileName(), (endTime - startTime));

                            // 转换成功
                            finalBatchRecord.setStatus("2");
                            finalBatchRecord.setProgress(100);
                            pdfConvertRecordService.updatePdfConvertRecord(finalBatchRecord);

                        } catch (Exception e) {
                            log.error("批量转换失败（简化版）: {}", finalBatchRecord.getSourceFileName(), e);
                            finalBatchRecord.setStatus("3");
                            finalBatchRecord.setFailReason(truncateErrorMessage(e.getMessage()));
                            finalBatchRecord.setProgress(0);
                            pdfConvertRecordService.updatePdfConvertRecord(finalBatchRecord);
                        }
                    }).start();
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("total", files.length);
            result.put("success", successCount);
            result.put("failed", failCount);
            result.put("taskIds", taskIds);
            result.put("message", String.format("成功提交 %d 个转换任务", successCount));

            return AjaxResult.success(result);

        } catch (Exception e) {
            log.error("批量转换失败", e);
            return AjaxResult.error("批量转换失败：" + e.getMessage());
        }
    }

    /**
     * 获取文件上传目录
     */
    private String getFileUploadDir() {
        String uploadDir = PdfUtilConfig.getPdfUploadDir();
        if (StringUtils.isEmpty(uploadDir)) {
            uploadDir = System.getProperty("java.io.tmpdir") + "/pdf_uploads";
        }
        File dir = new File(uploadDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return uploadDir;
    }

    /**
     * 获取输出目录
     */
    private String getOutputDir() {
        String outputDir = PdfUtilConfig.getPdfOutputDir();
        if (StringUtils.isEmpty(outputDir)) {
            outputDir = System.getProperty("java.io.tmpdir") + "/pdf_output";
        }
        File dir = new File(outputDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return outputDir;
    }

    /**
     * 获取转换类型
     */
    private String getConvertType(String extension) {
        switch (extension) {
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
}
