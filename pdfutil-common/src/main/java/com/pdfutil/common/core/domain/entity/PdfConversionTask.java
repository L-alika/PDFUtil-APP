package com.pdfutil.common.core.domain.entity;

import com.pdfutil.common.core.domain.BaseEntity;

/**
 * PDF转换任务实体
 *
 * @author Alika
 * @date 2025-01-28
 */
public class PdfConversionTask extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 任务ID */
    private Long taskId;

    /** 任务名称 */
    private String taskName;

    /** 源文件名 */
    private String sourceFileName;

    /** 源文件路径 */
    private String sourceFilePath;

    /** 目标文件名 */
    private String targetFileName;

    /** 目标文件路径 */
    private String targetFilePath;

    /** 转换类型 (PDF->双层PDF, JPG->双层PDF, TIF->双层PDF等) */
    private String conversionType;

    /** OCR语言 (chi_sim+chi_tra+eng) */
    private String ocrLanguage;

    /** 任务状态 (0-待处理, 1-转换中, 2-已完成, 3-已失败) */
    private Integer status;

    /** 进度百分比 (0-100) */
    private Integer progress;

    /** 错误信息 */
    private String errorMessage;

    /** 操作人 */
    private String operator;

    /** 文件大小 (字节) */
    private Long fileSize;

    /** 页数 */
    private Integer pageCount;

    /** 处理耗时 (毫秒) */
    private Long processingTime;

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public String getSourceFileName() {
        return sourceFileName;
    }

    public void setSourceFileName(String sourceFileName) {
        this.sourceFileName = sourceFileName;
    }

    public String getSourceFilePath() {
        return sourceFilePath;
    }

    public void setSourceFilePath(String sourceFilePath) {
        this.sourceFilePath = sourceFilePath;
    }

    public String getTargetFileName() {
        return targetFileName;
    }

    public void setTargetFileName(String targetFileName) {
        this.targetFileName = targetFileName;
    }

    public String getTargetFilePath() {
        return targetFilePath;
    }

    public void setTargetFilePath(String targetFilePath) {
        this.targetFilePath = targetFilePath;
    }

    public String getConversionType() {
        return conversionType;
    }

    public void setConversionType(String conversionType) {
        this.conversionType = conversionType;
    }

    public String getOcrLanguage() {
        return ocrLanguage;
    }

    public void setOcrLanguage(String ocrLanguage) {
        this.ocrLanguage = ocrLanguage;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getProgress() {
        return progress;
    }

    public void setProgress(Integer progress) {
        this.progress = progress;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public Integer getPageCount() {
        return pageCount;
    }

    public void setPageCount(Integer pageCount) {
        this.pageCount = pageCount;
    }

    public Long getProcessingTime() {
        return processingTime;
    }

    public void setProcessingTime(Long processingTime) {
        this.processingTime = processingTime;
    }

    /** 获取状态文本 */
    public String getStatusText() {
        if (status == null) {
            return "未知";
        }
        switch (status) {
            case 0:
                return "待处理";
            case 1:
                return "转换中";
            case 2:
                return "已完成";
            case 3:
                return "已失败";
            default:
                return "未知";
        }
    }
}
