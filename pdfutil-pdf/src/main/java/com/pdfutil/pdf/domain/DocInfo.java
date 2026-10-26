package com.pdfutil.pdf.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.io.Serializable;
import java.util.Date;

/**
 * 文档信息实体类 (对应 doc_info 表)
 *
 * @author Alika
 */
public class DocInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 文档主键 (UUID) */
    private String id;

    /** 原始文件名 */
    private String fileName;

    /** 档号/案卷号 (如: J001-2024-017) */
    private String archiveCode;

    /** 文件后缀 */
    private String fileExt;

    /** 原始文件大小 (字节) */
    private Long fileSize;

    /** 文档总页数 */
    private Integer pageCount;

    /** MinIO 原始文件存储路径 */
    private String rawStoragePath;

    /** MinIO 转换后的双层预览PDF路径 */
    private String previewPdfPath;

    /** 首页缩略图路径 */
    private String thumbPath;

    /** 转换类型 (PDF-DOUBLE, DOC-PDF, JPG-PDF等) */
    private String convertType;

    /** 文档处理状态: WAITING(排队), PROCESSING(处理中), SUCCESS(已完成), FAILED(失败) */
    private String status;

    /** OCR状态: NONE, PENDING, DONE, FAILED */
    private String ocrStatus;

    /** 归属部门ID */
    private String deptId;

    /** 所属文件夹目录ID (根目录为 "0") */
    private String folderId = "0";

    /** 文档门类/分类 */
    private String category;

    /** 密级 (PUBLIC, INTERNAL, SECRET) */
    private String securityLevel;

    /** 标签(逗号分隔) */
    private String tags;

    /** 上传人/操作人 */
    private String createBy;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date createTime;

    /** 更新时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date updateTime;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getArchiveCode() {
        return archiveCode;
    }

    public void setArchiveCode(String archiveCode) {
        this.archiveCode = archiveCode;
    }

    public String getFileExt() {
        return fileExt;
    }

    public void setFileExt(String fileExt) {
        this.fileExt = fileExt;
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

    public String getRawStoragePath() {
        return rawStoragePath;
    }

    public void setRawStoragePath(String rawStoragePath) {
        this.rawStoragePath = rawStoragePath;
    }

    public String getPreviewPdfPath() {
        return previewPdfPath;
    }

    public void setPreviewPdfPath(String previewPdfPath) {
        this.previewPdfPath = previewPdfPath;
    }

    public String getThumbPath() {
        return thumbPath;
    }

    public void setThumbPath(String thumbPath) {
        this.thumbPath = thumbPath;
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

    public String getOcrStatus() {
        return ocrStatus;
    }

    public void setOcrStatus(String ocrStatus) {
        this.ocrStatus = ocrStatus;
    }

    public String getDeptId() {
        return deptId;
    }

    public void setDeptId(String deptId) {
        this.deptId = deptId;
    }

    public String getFolderId() {
        return folderId != null ? folderId : "0";
    }

    public void setFolderId(String folderId) {
        this.folderId = folderId;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getSecurityLevel() {
        return securityLevel;
    }

    public void setSecurityLevel(String securityLevel) {
        this.securityLevel = securityLevel;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public String getCreateBy() {
        return createBy;
    }

    public void setCreateBy(String createBy) {
        this.createBy = createBy;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }
}
