package com.pdfutil.pdf.service;

import com.pdfutil.common.core.utils.DualLayerPdfConverter;
import com.pdfutil.common.core.utils.WordToPdfConverter;
import com.pdfutil.common.utils.StringUtils;
import com.pdfutil.pdf.domain.PdfConvertRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;

/**
 * PDF转换核心服务
 *
 * 使用 PaddleOCR + OCRmyPDF 协同方案进行双层PDF转换
 *
 * 架构：
 * 输入文件 → PaddleOCR（识别文本+坐标） → OCRmyPDF（生成双层PDF） → 输出
 *
 * @author Alika
 * @date 2025-01-27
 */
@Service
public class PdfConvertService {

    private static final Logger log = LoggerFactory.getLogger(PdfConvertService.class);

    /**
     * 单层PDF转双层PDF
     *
     * @param sourceFile 源文件
     * @param targetFile 目标文件
     * @return 是否成功
     */
    public boolean convertSingleToDoubleLayerPdf(File sourceFile, File targetFile) {
        try {
            log.info("开始单层PDF转双层PDF: {} -> {}", sourceFile.getPath(), targetFile.getPath());

            // 确保目标目录存在
            File targetDir = targetFile.getParentFile();
            if (targetDir != null && !targetDir.exists()) {
                targetDir.mkdirs();
            }

            // 使用 PaddleOCR + OCRmyPDF 协同方案进行双层PDF转换
            DualLayerPdfConverter.convertToDualLayerPdf(sourceFile.getPath(), targetFile.getPath());

            log.info("单层PDF转双层PDF成功: {}", targetFile.getPath());
            return true;

        } catch (Exception e) {
            log.error("单层PDF转双层PDF失败: {}", sourceFile.getPath(), e);
            return false;
        }
    }

    /**
     * JPEG/JPG转双层PDF
     *
     * @param sourceFile 源文件
     * @param targetFile 目标文件
     * @return 是否成功
     */
    public boolean convertJpegToDoubleLayerPdf(File sourceFile, File targetFile) {
        try {
            log.info("开始JPEG转双层PDF: {} -> {}", sourceFile.getPath(), targetFile.getPath());

            // 确保目标目录存在
            File targetDir = targetFile.getParentFile();
            if (targetDir != null && !targetDir.exists()) {
                targetDir.mkdirs();
            }

            // 使用 PaddleOCR + OCRmyPDF 协同方案进行双层PDF转换
            DualLayerPdfConverter.convertToDualLayerPdf(sourceFile.getPath(), targetFile.getPath());

            log.info("JPEG转双层PDF成功: {}", targetFile.getPath());
            return true;

        } catch (Exception e) {
            log.error("JPEG转双层PDF失败: {}", sourceFile.getPath(), e);
            return false;
        }
    }

    /**
     * TIF转双层PDF
     *
     * @param sourceFile 源文件
     * @param targetFile 目标文件
     * @return 是否成功
     */
    public boolean convertTifToDoubleLayerPdf(File sourceFile, File targetFile) {
        try {
            log.info("开始TIF转双层PDF: {} -> {}", sourceFile.getPath(), targetFile.getPath());

            // 确保目标目录存在
            File targetDir = targetFile.getParentFile();
            if (targetDir != null && !targetDir.exists()) {
                targetDir.mkdirs();
            }

            // 使用 PaddleOCR + OCRmyPDF 协同方案进行双层PDF转换
            DualLayerPdfConverter.convertToDualLayerPdf(sourceFile.getPath(), targetFile.getPath());

            log.info("TIF转双层PDF成功: {}", targetFile.getPath());
            return true;

        } catch (Exception e) {
            log.error("TIF转双层PDF失败: {}", sourceFile.getPath(), e);
            return false;
        }
    }

    /**
     * JPG转双层PDF
     *
     * @param sourceFile 源文件
     * @param targetFile 目标文件
     * @return 是否成功
     */
    public boolean convertJpgToDoubleLayerPdf(File sourceFile, File targetFile) {
        // JPEG和JPG格式处理相同
        return convertJpegToDoubleLayerPdf(sourceFile, targetFile);
    }

    /**
     * DOC/DOCX转双层PDF
     *
     * @param sourceFile 源文件
     * @param targetFile 目标文件
     * @return 是否成功
     */
    public boolean convertDocToDoubleLayerPdf(File sourceFile, File targetFile) {
        try {
            log.info("开始DOC/DOCX转双层PDF: {} -> {}", sourceFile.getPath(), targetFile.getPath());

            // 确保目标目录存在
            File targetDir = targetFile.getParentFile();
            if (targetDir != null && !targetDir.exists()) {
                targetDir.mkdirs();
            }

            // 使用 LibreOffice + OCRmyPDF 方案进行双层PDF转换
            WordToPdfConverter.convertToDualLayerPdf(sourceFile.getPath(), targetFile.getPath());

            log.info("DOC/DOCX转双层PDF成功: {}", targetFile.getPath());
            return true;

        } catch (Exception e) {
            log.error("DOC/DOCX转双层PDF失败: {}", sourceFile.getPath(), e);
            return false;
        }
    }

    /**
     * XLS/XLSX转双层PDF
     *
     * @param sourceFile 源文件
     * @param targetFile 目标文件
     * @return 是否成功
     */
    public boolean convertExcelToDoubleLayerPdf(File sourceFile, File targetFile) {
        try {
            log.info("开始XLS/XLSX转双层PDF: {} -> {}", sourceFile.getPath(), targetFile.getPath());

            // 确保目标目录存在
            File targetDir = targetFile.getParentFile();
            if (targetDir != null && !targetDir.exists()) {
                targetDir.mkdirs();
            }

            // 使用 LibreOffice + OCRmyPDF 方案进行双层PDF转换
            WordToPdfConverter.convertToDualLayerPdf(sourceFile.getPath(), targetFile.getPath());

            log.info("XLS/XLSX转双层PDF成功: {}", targetFile.getPath());
            return true;

        } catch (Exception e) {
            log.error("XLS/XLSX转双层PDF失败: {}", sourceFile.getPath(), e);
            return false;
        }
    }

    /**
     * PPT/PPTX转双层PDF
     *
     * @param sourceFile 源文件
     * @param targetFile 目标文件
     * @return 是否成功
     */
    public boolean convertPptToDoubleLayerPdf(File sourceFile, File targetFile) {
        try {
            log.info("开始PPT/PPTX转双层PDF: {} -> {}", sourceFile.getPath(), targetFile.getPath());

            // 确保目标目录存在
            File targetDir = targetFile.getParentFile();
            if (targetDir != null && !targetDir.exists()) {
                targetDir.mkdirs();
            }

            // 使用 LibreOffice + OCRmyPDF 方案进行双层PDF转换
            WordToPdfConverter.convertToDualLayerPdf(sourceFile.getPath(), targetFile.getPath());

            log.info("PPT/PPTX转双层PDF成功: {}", targetFile.getPath());
            return true;

        } catch (Exception e) {
            log.error("PPT/PPTX转双层PDF失败: {}", sourceFile.getPath(), e);
            return false;
        }
    }

    /**
     * 根据转换类型执行转换
     *
     * @param record 转换记录
     * @return 是否成功
     */
    public boolean convert(PdfConvertRecord record) {
        File sourceFile = new File(record.getSourceFilePath());
        File targetFile = new File(record.getTargetFilePath());

        if (!sourceFile.exists()) {
            log.error("源文件不存在: {}", record.getSourceFilePath());
            return false;
        }

        String convertType = record.getConvertType();
        log.info("执行转换任务: 类型={}, 源文件={}", convertType, sourceFile.getName());

        boolean success;
        switch (convertType) {
            case "PDF-DOUBLE":
                success = convertSingleToDoubleLayerPdf(sourceFile, targetFile);
                break;
            case "OFD-DOUBLE":
            case "JPG-OFD":
            case "JPEG-OFD":
            case "PNG-OFD":
            case "BMP-OFD":
            case "TIF-OFD":
            case "TIFF-OFD":
                try {
                    log.info("开始双层OFD转换");
                    com.pdfutil.common.core.utils.DualLayerOfdConverter.convertToDualLayerOfd(sourceFile.getPath(), targetFile.getPath());
                    success = true;
                } catch (Exception e) {
                    log.error("OFD双层转换失败", e);
                    success = false;
                }
                break;
            case "JPEG-PDF":
            case "JPG-PDF":
                success = convertJpegToDoubleLayerPdf(sourceFile, targetFile);
                break;
            case "TIF-PDF":
                success = convertTifToDoubleLayerPdf(sourceFile, targetFile);
                break;
            case "DOC-PDF":
            case "DOCX-PDF":
                success = convertDocToDoubleLayerPdf(sourceFile, targetFile);
                break;
            case "XLS-PDF":
            case "XLSX-PDF":
                success = convertExcelToDoubleLayerPdf(sourceFile, targetFile);
                break;
            case "PPT-PDF":
            case "PPTX-PDF":
                success = convertPptToDoubleLayerPdf(sourceFile, targetFile);
                break;
            default:
                log.error("不支持的转换类型: {}", convertType);
                return false;
        }

        return success;
    }

    /**
     * 异步转换文件到指定目录
     *
     * @param sourcePath 源文件路径
     * @param targetDir  目标目录
     * @throws Exception 转换失败时抛出异常，包含详细错误信息
     */
    public void convertToDirectory(String sourcePath, String targetDir) throws Exception {
        convertToDirectory(sourcePath, targetDir, null);
    }

    /**
     * 异步转换文件到指定目录，使用指定的原始文件名作为输出文件名
     *
     * @param sourcePath       源文件路径（上传时的临时文件路径，可能包含_tid后缀）
     * @param targetDir        目标目录
     * @param originalFileName 原始文件名（不含_tid后缀的真实文件名），为null时从sourcePath提取
     * @throws Exception 转换失败时抛出异常，包含详细错误信息
     */
    public void convertToDirectory(String sourcePath, String targetDir, String originalFileName) throws Exception {
        log.info("开始转换文件到指定目录: {} -> {}, 原始文件名: {}", sourcePath, targetDir, originalFileName);

        // 构建输出文件路径（保持原文件名，仅更改扩展名为.pdf）
        String baseFileName;
        if (StringUtils.isNotEmpty(originalFileName)) {
            // 使用传入的原始文件名（去除扩展名）
            int lastDotIndex = originalFileName.lastIndexOf('.');
            baseFileName = lastDotIndex > 0 ? originalFileName.substring(0, lastDotIndex) : originalFileName;
        } else {
            // 从sourcePath提取文件名（兼容旧调用）
            File sourceFile = new File(sourcePath);
            baseFileName = sourceFile.getName();
            int lastDotIndex = baseFileName.lastIndexOf('.');
            if (lastDotIndex > 0) {
                baseFileName = baseFileName.substring(0, lastDotIndex);
            }
        }

        // 根据文件扩展名选择转换方法
        String extension = getFileExtension(sourcePath).toLowerCase();

        String outputExt = ".pdf";
        if ("ofd".equals(extension)) {
            outputExt = ".ofd";
        }

        // 输出文件件名与输入文件名一致（仅扩展名不同）
        String outputPdfPath = targetDir + File.separator + baseFileName + outputExt;

        // 确保输出目录存在
        File outputDirFile = new File(targetDir);
        if (!outputDirFile.exists()) {
            outputDirFile.mkdirs();
        }

        if ("ofd".equals(extension)) {
            log.info("使用 DualLayerOfdConverter 转换双层 OFD");
            com.pdfutil.common.core.utils.DualLayerOfdConverter.convertToDualLayerOfd(sourcePath, outputPdfPath);
        } else if ("pdf".equals(extension) || "jpg".equals(extension) || "jpeg".equals(extension) ||
            "tif".equals(extension) || "tiff".equals(extension)) {
            // PDF 和图片：使用 DualLayerPdfConverter
            log.info("使用 DualLayerPdfConverter 转换 PDF/图片文件");
            DualLayerPdfConverter.convertToDualLayerPdf(sourcePath, outputPdfPath);
        } else if ("doc".equals(extension) || "docx".equals(extension) ||
                   "xls".equals(extension) || "xlsx".equals(extension) ||
                   "ppt".equals(extension) || "pptx".equals(extension)) {
            // Office 文档：使用 WordToPdfConverter
            log.info("使用 WordToPdfConverter 转换 Office 文档");
            WordToPdfConverter.convertToDualLayerPdf(sourcePath, outputPdfPath);
        } else {
            throw new IllegalArgumentException("不支持的文件格式: " + extension);
        }

        log.info("文件转换成功: {}", outputPdfPath);
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
     * 获取文件转换进度
     *
     * @param recordId 记录ID
     * @return 进度百分比
     */
    public int getProgress(Long recordId) {
        // TODO: 实现获取转换进度的逻辑
        // 可以通过任务队列或缓存存储进度信息
        return 0;
    }
}
