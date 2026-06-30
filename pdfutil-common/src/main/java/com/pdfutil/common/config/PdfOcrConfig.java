package com.pdfutil.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * PDF OCR转换配置类
 *
 * @author Alika
 * @date 2025-01-28
 */
@Component
@ConfigurationProperties(prefix = "pdfutil.pdf")
public class PdfOcrConfig {

    /**
     * OCR方案类型: local(本地OCR), local_paddle(本地PaddleOCR), remote(远程OCR API)
     * 默认使用 local_paddle (PaddleOCR + HocrTextLayerAdder)
     */
    private String ocrType = "local_paddle";

    /**
     * 远程OCR API地址
     */
    private String remoteOcrApiUrl;

    /**
     * 是否启用 PaddleOCR + OCRmyPDF 协同方案（仅本地OCR有效）
     */
    private boolean usePaddleOcr;

    /**
     * PaddleOCR脚本路径
     */
    private String paddleOcrScriptPath;

    /**
     * Python解释器路径
     */
    private String pythonPath;

    /**
     * OCRmyPDF可执行文件路径
     */
    private String ocrmypdfPath;

    /**
     * OCRmyPDF语言配置
     */
    private String ocrmypdfLanguage;

    /**
     * LibreOffice 可执行文件路径（用于 DOC/DOCX 转换）
     */
    private String libreofficePath;

    /**
     * LibreOffice 配置（嵌套结构，用于 desktop 配置）
     */
    private LibreOfficeConfig libreoffice = new LibreOfficeConfig();

    /**
     * PaddleOCR 配置（嵌套结构）
     */
    private PaddleOcrConfig paddle = new PaddleOcrConfig();

    /**
     * 大图片阈值（像素数），超过此值并发减半
     * 默认 1200万像素 (12MP)
     * 使用 Long 对象类型，null 表示未设置
     */
    private Long largeImageThreshold = null;

    /**
     * 超大图片阈值（像素数），超过此值并发降为1/4
     * 默认 2400万像素 (24MP)
     * 使用 Long 对象类型，null 表示未设置
     */
    private Long veryLargeImageThreshold = null;

    /**
     * 本地OCR最大并发数
     * 使用 Integer 对象类型，null 表示未设置
     */
    private Integer localOcrMaxConcurrent = null;

    /**
     * 批量OCR每批处理的图片数量
     * 使用 Integer 对象类型，null 表示未设置
     */
    private Integer ocrBatchSize = null;

    public String getOcrType() {
        return ocrType;
    }

    public void setOcrType(String ocrType) {
        this.ocrType = ocrType;
    }

    public String getRemoteOcrApiUrl() {
        return remoteOcrApiUrl;
    }

    public void setRemoteOcrApiUrl(String remoteOcrApiUrl) {
        this.remoteOcrApiUrl = remoteOcrApiUrl;
    }

    public boolean isUsePaddleOcr() {
        return usePaddleOcr;
    }

    public void setUsePaddleOcr(boolean usePaddleOcr) {
        this.usePaddleOcr = usePaddleOcr;
    }

    public String getPaddleOcrScriptPath() {
        return paddleOcrScriptPath;
    }

    public void setPaddleOcrScriptPath(String paddleOcrScriptPath) {
        this.paddleOcrScriptPath = paddleOcrScriptPath;
    }

    public String getPythonPath() {
        return pythonPath;
    }

    public void setPythonPath(String pythonPath) {
        this.pythonPath = pythonPath;
    }

    public String getOcrmypdfPath() {
        return ocrmypdfPath;
    }

    public void setOcrmypdfPath(String ocrmypdfPath) {
        this.ocrmypdfPath = ocrmypdfPath;
    }

    public String getOcrmypdfLanguage() {
        return ocrmypdfLanguage;
    }

    public void setOcrmypdfLanguage(String ocrmypdfLanguage) {
        this.ocrmypdfLanguage = ocrmypdfLanguage;
    }

    public String getLibreofficePath() {
        // 优先使用直接的 libreofficePath 配置
        if (libreofficePath != null && !libreofficePath.isEmpty()) {
            return libreofficePath;
        }
        // 如果没有配置，则使用嵌套的 libreoffice.path
        return libreoffice.getPath();
    }

    public void setLibreofficePath(String libreofficePath) {
        this.libreofficePath = libreofficePath;
    }

    public LibreOfficeConfig getLibreoffice() {
        return libreoffice;
    }

    public void setLibreoffice(LibreOfficeConfig libreoffice) {
        this.libreoffice = libreoffice;
    }

    public PaddleOcrConfig getPaddle() {
        return paddle;
    }

    public void setPaddle(PaddleOcrConfig paddle) {
        this.paddle = paddle;
    }

    public long getLargeImageThreshold() {
        // 优先使用直接配置，其次使用嵌套配置，最后使用默认值
        if (largeImageThreshold != null) {
            return largeImageThreshold;
        }
        return paddle.getLargeImageThreshold();
    }

    public void setLargeImageThreshold(long largeImageThreshold) {
        this.largeImageThreshold = largeImageThreshold;
    }

    public long getVeryLargeImageThreshold() {
        // 优先使用直接配置，其次使用嵌套配置，最后使用默认值
        if (veryLargeImageThreshold != null) {
            return veryLargeImageThreshold;
        }
        return paddle.getVeryLargeImageThreshold();
    }

    public void setVeryLargeImageThreshold(long veryLargeImageThreshold) {
        this.veryLargeImageThreshold = veryLargeImageThreshold;
    }

    public int getLocalOcrMaxConcurrent() {
        // 优先使用直接配置，其次使用嵌套配置，最后使用默认值
        if (localOcrMaxConcurrent != null) {
            return localOcrMaxConcurrent;
        }
        return paddle.getMaxConcurrent();
    }

    public void setLocalOcrMaxConcurrent(int localOcrMaxConcurrent) {
        this.localOcrMaxConcurrent = localOcrMaxConcurrent;
    }

    public int getOcrBatchSize() {
        // 优先使用直接配置，其次使用嵌套配置，最后使用默认值
        if (ocrBatchSize != null) {
            return ocrBatchSize;
        }
        return paddle.getBatchSize();
    }

    public void setOcrBatchSize(int ocrBatchSize) {
        this.ocrBatchSize = ocrBatchSize;
    }

    /**
     * LibreOffice 嵌套配置类
     */
    public static class LibreOfficeConfig {
        private String path = "soffice";
        private int timeout = 120000;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public int getTimeout() {
            return timeout;
        }

        public void setTimeout(int timeout) {
            this.timeout = timeout;
        }
    }

    /**
     * PaddleOCR 嵌套配置类
     * 用于智能并发控制
     */
    public static class PaddleOcrConfig {
        /** 最大并发数，默认4 */
        private int maxConcurrent = 4;
        /** 大图片阈值（像素数），默认12MP */
        private long largeImageThreshold = 12_000_000L;
        /** 超大图片阈值（像素数），默认24MP */
        private long veryLargeImageThreshold = 24_000_000L;
        /** 批量OCR每批处理的图片数量，默认20 */
        private int batchSize = 20;

        public int getMaxConcurrent() {
            return maxConcurrent;
        }

        public void setMaxConcurrent(int maxConcurrent) {
            this.maxConcurrent = maxConcurrent;
        }

        public long getLargeImageThreshold() {
            return largeImageThreshold;
        }

        public void setLargeImageThreshold(long largeImageThreshold) {
            this.largeImageThreshold = largeImageThreshold;
        }

        public long getVeryLargeImageThreshold() {
            return veryLargeImageThreshold;
        }

        public void setVeryLargeImageThreshold(long veryLargeImageThreshold) {
            this.veryLargeImageThreshold = veryLargeImageThreshold;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }
}
