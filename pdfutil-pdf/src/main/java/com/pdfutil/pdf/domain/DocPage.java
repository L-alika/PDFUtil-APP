package com.pdfutil.pdf.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.io.Serializable;
import java.util.Date;

/**
 * 文档分页正文及坐标信息实体类 (对应 doc_page 表)
 *
 * @author Alika
 */
public class DocPage implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 分页ID */
    private Long id;

    /** 所属文档ID */
    private String docId;

    /** 页码 (从1开始) */
    private Integer pageNum;

    /** 该页纯文本内容 */
    private String textContent;

    /** 坐标 JSON (RapidOCR 边界框和文字块信息) */
    private String bboxJson;

    /** 单页缩略图路径 */
    private String thumbPath;

    /** 是否经过 OCR 处理 */
    private Boolean isOcr;

    /** 字符数 */
    private Integer charCount;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDocId() {
        return docId;
    }

    public void setDocId(String docId) {
        this.docId = docId;
    }

    public Integer getPageNum() {
        return pageNum;
    }

    public void setPageNum(Integer pageNum) {
        this.pageNum = pageNum;
    }

    public String getTextContent() {
        return textContent;
    }

    public void setTextContent(String textContent) {
        this.textContent = textContent;
    }

    public String getBboxJson() {
        return bboxJson;
    }

    public void setBboxJson(String bboxJson) {
        this.bboxJson = bboxJson;
    }

    public String getThumbPath() {
        return thumbPath;
    }

    public void setThumbPath(String thumbPath) {
        this.thumbPath = thumbPath;
    }

    public Boolean getIsOcr() {
        return isOcr;
    }

    public void setIsOcr(Boolean ocr) {
        isOcr = ocr;
    }

    public Integer getCharCount() {
        return charCount;
    }

    public void setCharCount(Integer charCount) {
        this.charCount = charCount;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }
}
