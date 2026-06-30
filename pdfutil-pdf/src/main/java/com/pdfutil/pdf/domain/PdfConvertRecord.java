package com.pdfutil.pdf.domain;

import com.pdfutil.common.annotation.Excel;
import com.pdfutil.common.core.domain.BaseEntity;

/**
 * PDF转换记录对象 pdf_convert_record
 *
 * @author Alika
 * @date 2025-01-27
 */
public class PdfConvertRecord extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /** 转换记录ID */
    private Long id;

    /** 转换人用户ID */
    @Excel(name = "转换人ID")
    private Long userId;

    /** 转换人姓名 */
    @Excel(name = "转换人")
    private String userName;

    /** 源文件名 */
    @Excel(name = "源文件名")
    private String sourceFileName;

    /** 源文件路径 */
    @Excel(name = "源文件路径")
    private String sourceFilePath;

    /** 目标文件名 */
    @Excel(name = "目标文件名")
    private String targetFileName;

    /** 目标文件路径 */
    @Excel(name = "目标文件路径")
    private String targetFilePath;

    /** 转换类型（PDF-JPEG, PDF-TIF, JPEG-PDF, TIF-PDF, JPG-PDF） */
    @Excel(name = "转换类型")
    private String convertType;

    /** 转换状态（0-待转换, 1-转换中, 2-成功, 3-失败） */
    @Excel(name = "转换状态", readConverterExp = "0=待转换,1=转换中,2=成功,3=失败")
    private String status;

    /** 失败原因 */
    private String failReason;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 进度百分比 */
    @Excel(name = "进度")
    private Integer progress;

    /** 转换耗时（毫秒） */
    @Excel(name = "耗时(ms)")
    private Integer duration;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
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

    public String getConvertType() {
        return convertType;
    }

    public void setConvertType(String convertType) {
        this.convertType = convertType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public Integer getProgress() {
        return progress;
    }

    public void setProgress(Integer progress) {
        this.progress = progress;
    }

    public Integer getDuration() {
        return duration;
    }

    public void setDuration(Integer duration) {
        this.duration = duration;
    }
}
