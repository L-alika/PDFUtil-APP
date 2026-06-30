package com.pdfutil.pdf.service;

import com.pdfutil.common.core.utils.DualLayerPdfConverter;
import com.pdfutil.common.core.utils.WordToPdfConverter;
import com.pdfutil.common.core.utils.LocalOcrClient;
import com.pdfutil.common.config.PdfUtilConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdfutil.common.utils.StringUtils;
import com.pdfutil.pdf.domain.PdfConvertRecord;
import com.pdfutil.pdf.utils.PdfOutputPathBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.multipdf.Splitter;

/**
 * PDF转换简化服务 - 供其他系统调用
 *
 * 特点：
 * 1. 无进度更新（无进度条干扰）
 * 2. 只更新状态：等待中 -> 转换中 -> 成功/失败
 * 3. 适合后台任务和系统集成
 *
 * @author Alika
 * @date 2025-01-30
 */
@Service
public class PdfConvertSimpleService {

    private static final Logger log = LoggerFactory.getLogger(PdfConvertSimpleService.class);

    /**
     * 根据转换类型执行转换（简化版，无进度更新）
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
        try {
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
                    log.info("开始双层OFD转换");
                    com.pdfutil.common.core.utils.DualLayerOfdConverter.convertToDualLayerOfd(sourceFile.getPath(), targetFile.getPath());
                    success = true;
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
        } catch (Exception e) {
            log.error("转换失败: {}", sourceFile.getName(), e);
            return false;
        }

        return success;
    }

    /**
     * 异步转换文件到指定目录（简化版，无进度更新）
     *
     * @param sourcePath 源文件路径
     * @param targetDir  目标目录
     * @throws Exception 转换失败时抛出异常，包含详细错误信息
     */
    public void convertToDirectory(String sourcePath, String targetDir) throws Exception {
        convertToDirectory(sourcePath, targetDir, null, "double");
    }

    /**
     * 异步转换文件到指定目录（简化版，无进度更新），使用指定的原始文件名作为输出文件名
     *
     * @param sourcePath       源文件路径（上传时的临时文件路径，可能包含_tid后缀）
     * @param targetDir        目标目录
     * @param originalFileName 原始文件名（不含_tid后缀的真实文件名），为null时从sourcePath提取
     * @throws Exception 转换失败时抛出异常，包含详细错误信息
     */
    public void convertToDirectory(String sourcePath, String targetDir, String originalFileName) throws Exception {
        convertToDirectory(sourcePath, targetDir, originalFileName, "double");
    }

    /**
     * 异步转换文件到指定目录（简化版，无进度更新），支持输出模式选择
     *
     * @param sourcePath       源文件路径（上传时的临时文件路径，可能包含_tid后缀）
     * @param targetDir        目标目录
     * @param originalFileName 原始文件名（不含_tid后缀的真实文件名），为null时从sourcePath提取
     * @param outputMode       输出模式：double(双层PDF)、single(单层PDF)、merge(合并PDF)
     * @throws Exception 转换失败时抛出异常，包含详细错误信息
     */
    public void convertToDirectory(String sourcePath, String targetDir, String originalFileName, String outputMode) throws Exception {
        convertToDirectory(sourcePath, targetDir, originalFileName, outputMode, null, null);
    }

    public void convertToDirectory(String sourcePath, String targetDir, String originalFileName, String outputMode, String filenamePrefix, String filenameSuffix) throws Exception {
        convertToDirectory(sourcePath, targetDir, originalFileName, outputMode, filenamePrefix, filenameSuffix, 4, 1);
    }

    /**
     * 异步转换文件到指定目录（简化版，无进度更新），支持输出模式选择和文件夹创建规则
     *
     * @param sourcePath       源文件路径（上传时的临时文件路径，可能包含_tid后缀）
     * @param targetDir        目标目录
     * @param originalFileName 原始文件名（不含_tid后缀的真实文件名），为null时从sourcePath提取
     * @param outputMode       输出模式：double(双层PDF)、single(单层PDF)、merge(合并PDF)
     * @param filenamePrefix   文件名前缀
     * @param filenameSuffix   文件名后缀
     * @param folderCreateRule 文件夹创建规则(1-6)，默认4
     * @param pdfNameSource    PDF命名来源(1-2)，默认1
     * @throws Exception 转换失败时抛出异常，包含详细错误信息
     */
    public void convertToDirectory(String sourcePath, String targetDir, String originalFileName,
                                   String outputMode, String filenamePrefix, String filenameSuffix,
                                   int folderCreateRule, int pdfNameSource) throws Exception {
        convertToDirectory(sourcePath, targetDir, originalFileName, outputMode, filenamePrefix, filenameSuffix,
                folderCreateRule, pdfNameSource, null);
    }

    /**
     * 异步转换文件到指定目录（简化版，无进度更新），支持输出模式选择和文件夹创建规则
     *
     * @param sourcePath        源文件路径（上传时的临时文件路径，可能包含_tid后缀）
     * @param targetDir         目标目录
     * @param originalFileName  原始文件名（不含_tid后缀的真实文件名），为null时从sourcePath提取
     * @param outputMode        输出模式：double(双层PDF)、single(单层PDF)、merge(合并PDF)
     * @param filenamePrefix    文件名前缀
     * @param filenameSuffix    文件名后缀
     * @param folderCreateRule  文件夹创建规则
     * @param pdfNameSource     PDF命名来源
     * @param outputFileName    指定的输出文件名
     * @throws Exception 转换失败时抛出异常
     */
    public void convertToDirectory(String sourcePath, String targetDir, String originalFileName,
                                   String outputMode, String filenamePrefix, String filenameSuffix,
                                   int folderCreateRule, int pdfNameSource, String outputFileName) throws Exception {
        convertToDirectory(sourcePath, targetDir, originalFileName, outputMode, filenamePrefix, filenameSuffix,
                folderCreateRule, pdfNameSource, outputFileName, null, null, null);
    }

    public void convertToDirectory(String sourcePath, String targetDir, String originalFileName,
                                   String outputMode, String filenamePrefix, String filenameSuffix,
                                   int folderCreateRule, int pdfNameSource, String outputFileName,
                                   String splitType, Integer splitStartPage, Integer splitEndPage) throws Exception {
        // 确保基础输出目录存在
        File outputDirFile = new File(targetDir);
        if (!outputDirFile.exists()) {
            outputDirFile.mkdirs();
        }

        String outputPdfPath;
        String outputExt = ".pdf";
        String extension = getFileExtension(sourcePath).toLowerCase();
        if ("ofd".equals(extension) || (outputMode != null && outputMode.toLowerCase().contains("ofd"))) {
            outputExt = ".ofd";
        }

        if (StringUtils.isNotEmpty(outputFileName)) {
            // 如果指定了输出文件名，直接使用
            outputPdfPath = targetDir + File.separator + outputFileName;
            log.info("使用指定的输出文件名: {}", outputPdfPath);
        } else if (folderCreateRule == 1) {
            // 【修复】规则1：按档号分层+完整档号文件夹
            // 档号提取优先级：1. 从原始文件名提取（如果文件名包含档号格式） 2. 从父目录路径提取
            String archiveNumber;
            // 优先使用原始文件名（去除上传时添加的UUID前缀）
            String fileNameToCheck;
            if (StringUtils.isNotEmpty(originalFileName)) {
                fileNameToCheck = originalFileName;
            } else {
                fileNameToCheck = new File(sourcePath).getName();
            }
            String fileNameWithoutExt = fileNameToCheck;
            int dotIndex = fileNameToCheck.lastIndexOf('.');
            if (dotIndex > 0) {
                fileNameWithoutExt = fileNameToCheck.substring(0, dotIndex);
            }

            // 检查文件名是否包含档号格式（包含连字符且有多部分）
            if (fileNameWithoutExt.contains("-") && fileNameWithoutExt.split("-").length >= 2) {
                // 从文件名提取档号（统一转换为大写）
                archiveNumber = fileNameWithoutExt.toUpperCase().replace("_", "-");
                log.info("从文件名提取档号: {}", archiveNumber);
            } else {
                // 从父目录路径提取档号
                archiveNumber = buildArchiveNumberFromParentPath(sourcePath, targetDir);
            }

            // 构建输出目录：在targetDir下按档号各部分创建文件夹层级，最后创建完整档号文件夹
            String outputDir = targetDir;
            List<String> parts = PdfOutputPathBuilder.getArchiveParts(archiveNumber);
            for (String part : parts) {
                outputDir = outputDir + File.separator + part;
            }
            // 最后再创建一个以完整档号命名的文件夹
            outputDir = outputDir + File.separator + archiveNumber;

            // 确保输出目录存在
            outputDirFile = new File(outputDir);
            if (!outputDirFile.exists()) {
                outputDirFile.mkdirs();
            }

            // 构建PDF文件名
            String pdfBaseName;
            if (pdfNameSource == 1) {
                // 规则1：最后一层文件夹名（使用档号的最后一部分）
                if (!parts.isEmpty()) {
                    pdfBaseName = parts.get(parts.size() - 1);
                } else {
                    // 如果档号没有部分，使用完整档号
                    pdfBaseName = archiveNumber;
                }
            } else {
                // 规则2：原始文件名
                if (StringUtils.isNotEmpty(originalFileName)) {
                    int origDotIndex = originalFileName.lastIndexOf('.');
                    pdfBaseName = origDotIndex > 0 ? originalFileName.substring(0, origDotIndex) : originalFileName;
                } else {
                    pdfBaseName = new File(sourcePath).getName();
                    int baseDotIndex = pdfBaseName.lastIndexOf('.');
                    if (baseDotIndex > 0) {
                        pdfBaseName = pdfBaseName.substring(0, baseDotIndex);
                    }
                }
            }

            // 应用前缀和后缀
            StringBuilder fileNameBuilder = new StringBuilder();
            if (StringUtils.isNotEmpty(filenamePrefix)) {
                fileNameBuilder.append(filenamePrefix);
            }
            fileNameBuilder.append(pdfBaseName);
            if (StringUtils.isNotEmpty(filenameSuffix)) {
                fileNameBuilder.append(filenameSuffix);
            }
            fileNameBuilder.append(outputExt);
            outputPdfPath = outputDir + File.separator + fileNameBuilder.toString();
            log.info("规则1构建输出路径: 档号={}, 输出路径={}", archiveNumber, outputPdfPath);
        } else if (folderCreateRule == 2) {
            // 【修复】规则2：逐层累积创建文件夹
            // 档号提取优先级：1. 从原始文件名提取（如果文件名包含档号格式） 2. 从父目录路径提取
            String archiveNumber;
            // 优先使用原始文件名（去除上传时添加的UUID前缀）
            String fileNameToCheck;
            if (StringUtils.isNotEmpty(originalFileName)) {
                fileNameToCheck = originalFileName;
            } else {
                fileNameToCheck = new File(sourcePath).getName();
            }
            String fileNameWithoutExt = fileNameToCheck;
            int dotIndex = fileNameToCheck.lastIndexOf('.');
            if (dotIndex > 0) {
                fileNameWithoutExt = fileNameToCheck.substring(0, dotIndex);
            }

            // 检查文件名是否包含档号格式（包含连字符且有多部分）
            if (fileNameWithoutExt.contains("-") && fileNameWithoutExt.split("-").length >= 2) {
                // 从文件名提取档号（统一转换为大写）
                archiveNumber = fileNameWithoutExt.toUpperCase().replace("_", "-");
                log.info("从文件名提取档号: {}", archiveNumber);
            } else {
                // 从父目录路径提取档号
                archiveNumber = buildArchiveNumberFromParentPath(sourcePath, targetDir);
            }

            // 构建输出目录：在targetDir下逐层累积创建文件夹
            String outputDir = targetDir;
            List<String> parts = PdfOutputPathBuilder.getArchiveParts(archiveNumber);
            StringBuilder cumulative = new StringBuilder();
            for (String part : parts) {
                if (cumulative.length() > 0) {
                    cumulative.append("-");
                }
                cumulative.append(part);
                outputDir = outputDir + File.separator + cumulative.toString();
            }

            // 确保输出目录存在
            outputDirFile = new File(outputDir);
            if (!outputDirFile.exists()) {
                outputDirFile.mkdirs();
            }

            // 构建PDF文件名
            String pdfBaseName;
            if (pdfNameSource == 1) {
                // 规则1：最后一层文件夹名（使用档号的最后一部分）
                if (!parts.isEmpty()) {
                    pdfBaseName = parts.get(parts.size() - 1);
                } else {
                    // 如果档号没有部分，使用完整档号
                    pdfBaseName = archiveNumber;
                }
            } else {
                // 规则2：原始文件名
                if (StringUtils.isNotEmpty(originalFileName)) {
                    int nameDotIndex = originalFileName.lastIndexOf('.');
                    pdfBaseName = nameDotIndex > 0 ? originalFileName.substring(0, nameDotIndex) : originalFileName;
                } else {
                    pdfBaseName = new File(sourcePath).getName();
                    int baseDotIndex = pdfBaseName.lastIndexOf('.');
                    if (baseDotIndex > 0) {
                        pdfBaseName = pdfBaseName.substring(0, baseDotIndex);
                    }
                }
            }

            // 应用前缀和后缀
            StringBuilder fileNameBuilder = new StringBuilder();
            if (StringUtils.isNotEmpty(filenamePrefix)) {
                fileNameBuilder.append(filenamePrefix);
            }
            fileNameBuilder.append(pdfBaseName);
            if (StringUtils.isNotEmpty(filenameSuffix)) {
                fileNameBuilder.append(filenameSuffix);
            }
            fileNameBuilder.append(outputExt);
            outputPdfPath = outputDir + File.separator + fileNameBuilder.toString();
            log.info("规则2构建输出路径: 档号={}, 输出路径={}", archiveNumber, outputPdfPath);
        } else {
            // 【修复】使用PdfOutputPathBuilder构建输出路径，正确应用folderCreateRule
            // 对于规则5/8，使用originalFileName作为源文件名（避免UUID前缀）
            // 对于规则3/4/6/7，由调用方传入targetFileName
            String effectiveSourcePath = sourcePath;
            if (StringUtils.isNotEmpty(originalFileName) &&
                (folderCreateRule == 5 || folderCreateRule == 8)) {
                // 规则5/8：使用原始文件名（不含扩展名）构造档号路径
                effectiveSourcePath = originalFileName;
            }

            // 使用PdfOutputPathBuilder构建完整输出路径
            outputPdfPath = PdfOutputPathBuilder.buildOutputPdfPath(
                    targetDir,           // 基础输出目录
                    effectiveSourcePath, // 源文件路径
                    folderCreateRule,    // 文件夹创建规则
                    pdfNameSource,       // PDF命名来源
                    null,                // preservePath（规则3/4/6/7使用，此处为null）
                    null,                // inputDir（规则3/4/6/7使用，此处为null）
                    filenamePrefix,      // 文件名前缀
                    filenameSuffix       // 文件名后缀
            );
            // 替换扩展名为所需的格式
            if (outputExt.equals(".ofd")) {
                outputPdfPath = outputPdfPath.substring(0, outputPdfPath.lastIndexOf('.')) + ".ofd";
            }
            log.info("使用PdfOutputPathBuilder构建输出路径: folderCreateRule={}, pdfNameSource={}, outputPath={}",
                    folderCreateRule, pdfNameSource, outputPdfPath);
        }

        // 确保输出目录的父目录存在（PdfOutputPathBuilder构建的路径可能包含多层目录）
        File outputFile = new File(outputPdfPath);
        File parentDir = outputFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
            log.info("创建输出目录: {}", parentDir.getAbsolutePath());
        }

        // 根据文件扩展名和输出模式选择转换方法
        log.info("文件扩展名: {}, 输出模式: {}", extension, outputMode);

        try {
            if ("ofd".equals(extension) || "double_ofd".equals(outputMode) || "single_ofd".equals(outputMode) || "merge_ofd".equals(outputMode)) {
                // OFD 格式处理
                if ("single_ofd".equals(outputMode)) {
                    log.info("使用 DualLayerOfdConverter 转换单层 OFD");
                    com.pdfutil.common.core.utils.DualLayerOfdConverter.convertToSingleLayerOfd(sourcePath, outputPdfPath);
                } else if ("merge_ofd".equals(outputMode)) {
                    log.info("使用 DualLayerOfdConverter 转换单层 OFD（合并模式中间格式，后续合并时统一OCR）");
                    // 合并模式下，单个文件转为单层OFD（无OCR），后续合并时统一进行OCR生成双层OFD
                    // 这样可以避免每个文件单独OCR后再合并时重复OCR的问题
                    com.pdfutil.common.core.utils.DualLayerOfdConverter.convertToSingleLayerOfd(sourcePath, outputPdfPath);
                } else {
                    // 默认双层OFD
                    log.info("使用 DualLayerOfdConverter 转换双层 OFD");
                    com.pdfutil.common.core.utils.DualLayerOfdConverter.convertToDualLayerOfd(sourcePath, outputPdfPath);
                }
            } else {
                switch (outputMode) {
                    case "split":
                        log.info("进入PDF拆分模式分支, 扩展名: {}, 拆分类型: {}", extension, splitType);
                        String actualSourcePath = sourcePath;
                        String tempPdfPath = null;
                        
                        try {
                            if (!"pdf".equals(extension)) {
                                // 如果输入不是PDF，首先转换为临时的PDF文件
                                tempPdfPath = getFileUploadDir() + File.separator + UUID.randomUUID().toString() + ".pdf";
                                log.info("输入不是PDF，先转换为临时PDF: {}", tempPdfPath);
                                if ("doc".equals(extension) || "docx".equals(extension) ||
                                    "xls".equals(extension) || "xlsx".equals(extension) ||
                                    "ppt".equals(extension) || "pptx".equals(extension)) {
                                    WordToPdfConverter.convertToDualLayerPdf(sourcePath, tempPdfPath);
                                } else {
                                    DualLayerPdfConverter.convertToDualLayerPdf(sourcePath, tempPdfPath);
                                }
                                actualSourcePath = tempPdfPath;
                            }
                            
                            File sourceFile = new File(actualSourcePath);
                            if (!sourceFile.exists()) {
                                throw new IOException("待拆分的源PDF文件不存在: " + actualSourcePath);
                            }
                            
                            try (PDDocument document = PDDocument.load(sourceFile)) {
                                int totalPages = document.getNumberOfPages();
                                String outName = new File(outputPdfPath).getName();
                                String baseName = outName;
                                int dotIdx = outName.lastIndexOf('.');
                                if (dotIdx > 0) {
                                    baseName = outName.substring(0, dotIdx);
                                }
                                
                                if ("range".equals(splitType)) {
                                    int start = Math.max(1, splitStartPage != null ? splitStartPage : 1);
                                    int end = Math.min(totalPages, splitEndPage != null ? splitEndPage : totalPages);
                                    if (start > end) {
                                        throw new IllegalArgumentException("起始页码不能大于结束页码");
                                    }
                                    try (PDDocument rangeDoc = new PDDocument()) {
                                        for (int i = start - 1; i < end; i++) {
                                            rangeDoc.addPage(document.getPage(i));
                                        }
                                        rangeDoc.save(outputPdfPath);
                                        log.info("PDF范围拆分成功: {} 到 {}, 输出路径: {}", start, end, outputPdfPath);
                                    }
                                } else {
                                    // 拆分每一页为单独的PDF
                                    org.apache.pdfbox.multipdf.Splitter splitter = new org.apache.pdfbox.multipdf.Splitter();
                                    List<PDDocument> pages = splitter.split(document);
                                    for (int i = 0; i < pages.size(); i++) {
                                        try (PDDocument pageDoc = pages.get(i)) {
                                            String formattedNum = String.format("%03d", i + 1);
                                            String pagePdfPath = new File(outputPdfPath).getParent() + File.separator + baseName + "_" + formattedNum + ".pdf";
                                            pageDoc.save(new File(pagePdfPath));
                                        }
                                    }
                                    log.info("PDF单页拆分成功，共拆分 {} 页，输出目录: {}", pages.size(), new File(outputPdfPath).getParent());
                                }
                            }
                        } finally {
                            // 清理临时PDF文件
                            if (tempPdfPath != null) {
                                File tempFile = new File(tempPdfPath);
                                if (tempFile.exists()) {
                                    tempFile.delete();
                                }
                            }
                        }
                        break;

                    case "single":
                        // 单层PDF模式：仅图片，无OCR文字层
                        log.info("进入单层PDF模式分支, 扩展名: {}", extension);
                        if ("doc".equals(extension) || "docx".equals(extension) ||
                            "xls".equals(extension) || "xlsx".equals(extension) ||
                            "ppt".equals(extension) || "pptx".equals(extension)) {
                            // Office文档使用LibreOffice转PDF（单层）
                            WordToPdfConverter.convertToSingleLayerPdf(sourcePath, outputPdfPath);
                        } else if ("pdf".equals(extension)) {
                            // 输入是PDF，保持原样或转换为单层
                            DualLayerPdfConverter.convertToSingleLayerPdf(sourcePath, outputPdfPath);
                        } else {
                            // 图片直接转PDF（单层）
                            DualLayerPdfConverter.convertImageToSingleLayerPdf(sourcePath, outputPdfPath);
                        }
                        break;

                    case "merge":
                        // 【修改】合并PDF模式：所有文件先转为双层PDF，再合并
                        // 统一为双层PDF，保持合并后的PDF所有页面都有可搜索的文本层
                        log.info("使用合并PDF模式转换（双层PDF）");
                        if ("doc".equals(extension) || "docx".equals(extension) ||
                            "xls".equals(extension) || "xlsx".equals(extension) ||
                            "ppt".equals(extension) || "pptx".equals(extension)) {
                            // Office文档转双层PDF
                            WordToPdfConverter.convertToDualLayerPdf(sourcePath, outputPdfPath);
                        } else if ("pdf".equals(extension)) {
                            // PDF文件转双层PDF（始终重新OCR，替换已有文本层）
                            DualLayerPdfConverter.convertToDualLayerPdf(sourcePath, outputPdfPath);
                        } else {
                            // 图片转双层PDF
                            DualLayerPdfConverter.convertToDualLayerPdf(sourcePath, outputPdfPath);
                        }
                        break;

                    case "double":
                    default:
                        // 双层PDF模式（默认）：图片层+OCR文字层
                        log.info("进入双层PDF模式分支, 扩展名: {}", extension);
                        if ("pdf".equals(extension)) {
                            DualLayerPdfConverter.convertToDualLayerPdf(sourcePath, outputPdfPath);
                        } else if ("doc".equals(extension) || "docx".equals(extension) ||
                                   "xls".equals(extension) || "xlsx".equals(extension) ||
                                   "ppt".equals(extension) || "pptx".equals(extension)) {
                            WordToPdfConverter.convertToDualLayerPdf(sourcePath, outputPdfPath);
                        } else {
                            DualLayerPdfConverter.convertToDualLayerPdf(sourcePath, outputPdfPath);
                        }
                        break;
                }
            }

            log.info("文件转换成功: {}", outputPdfPath);

        } catch (Exception e) {
            log.error("文件转换失败: {}", sourcePath, e);
            throw e;
        }
    }

    /**
     * 单层PDF转双层PDF
     *
     * @param sourceFile 源文件
     * @param targetFile 目标文件
     * @return 是否成功
     */
    private boolean convertSingleToDoubleLayerPdf(File sourceFile, File targetFile) {
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
    private boolean convertJpegToDoubleLayerPdf(File sourceFile, File targetFile) {
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
    private boolean convertTifToDoubleLayerPdf(File sourceFile, File targetFile) {
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
     * DOC/DOCX转双层PDF
     *
     * @param sourceFile 源文件
     * @param targetFile 目标文件
     * @return 是否成功
     */
    private boolean convertDocToDoubleLayerPdf(File sourceFile, File targetFile) {
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
    private boolean convertExcelToDoubleLayerPdf(File sourceFile, File targetFile) {
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
    private boolean convertPptToDoubleLayerPdf(File sourceFile, File targetFile) {
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

    // ==================== 批量图片优化转换方法 ====================

    /** 默认每批处理的图片数量 */
    private static final int DEFAULT_BATCH_SIZE = 20;

    /**
     * 获取OCR分批大小配置
     * 优先读取系统属性，否则使用默认值
     */
    private int getOcrBatchSize() {
        String batchSizeStr = System.getProperty("pdfutil.pdf.ocrBatchSize");
        if (batchSizeStr != null && !batchSizeStr.isEmpty()) {
            try {
                int batchSize = Integer.parseInt(batchSizeStr);
                if (batchSize > 0) {
                    return batchSize;
                }
            } catch (NumberFormatException e) {
                log.warn("OCR分批大小配置无效: {}，使用默认值 {}", batchSizeStr, DEFAULT_BATCH_SIZE);
            }
        }
        return DEFAULT_BATCH_SIZE;
    }

    /**
     * 批量转换图片文件到双层PDF（性能优化版，分批处理）
     * 将多个图片文件分批传递给OCR进行识别，减少Python进程启动开销
     *
     * 性能提升：
     * - 传统方式：N个图片 = 启动N次Python进程
     * - 优化方式：N个图片 = 启动 ceil(N/20) 次Python进程
     *
     * @param imageFiles 图片文件路径列表
     * @param outputDir 输出目录
     * @param outputMode 输出模式：double(双层PDF)、single(单层PDF)
     * @return 转换结果Map：源文件路径 -> 输出PDF路径（成功）或 null（失败）
     */
    public Map<String, String> convertImagesBatch(List<String> imageFiles, String outputDir, String outputMode) {
        return convertImagesBatch(imageFiles, outputDir, outputMode, null, null, getOcrBatchSize());
    }

    /**
     * 批量转换图片文件到双层PDF（性能优化版，分批处理）
     *
     * @param imageFiles 图片文件路径列表
     * @param outputDir 输出目录
     * @param outputMode 输出模式
     * @param filenamePrefix 文件名前缀
     * @param filenameSuffix 文件名后缀
     * @return 转换结果Map
     */
    public Map<String, String> convertImagesBatch(List<String> imageFiles, String outputDir, String outputMode,
                                                   String filenamePrefix, String filenameSuffix) {
        return convertImagesBatch(imageFiles, outputDir, outputMode, filenamePrefix, filenameSuffix, getOcrBatchSize());
    }

    /**
     * 批量转换图片文件到双层PDF（性能优化版，分批处理）
     *
     * @param imageFiles 图片文件路径列表
     * @param outputDir 输出目录
     * @param outputMode 输出模式
     * @param filenamePrefix 文件名前缀
     * @param filenameSuffix 文件名后缀
     * @param batchSize 每批处理的图片数量（默认20）
     * @return 转换结果Map
     */
    public Map<String, String> convertImagesBatch(List<String> imageFiles, String outputDir, String outputMode,
                                                   String filenamePrefix, String filenameSuffix, int batchSize) {
        Map<String, String> results = new ConcurrentHashMap<>();

        if (imageFiles == null || imageFiles.isEmpty()) {
            log.warn("图片文件列表为空");
            return results;
        }

        // 批次大小验证
        if (batchSize <= 0) {
            batchSize = DEFAULT_BATCH_SIZE;
        }

        int totalFiles = imageFiles.size();
        int totalBatches = (int) Math.ceil((double) totalFiles / batchSize);

        log.info("开始批量转换 {} 个图片文件，输出模式: {}，分 {} 批处理（每批 {} 个）",
            totalFiles, outputMode, totalBatches, batchSize);
        long startTime = System.currentTimeMillis();

        // 确保输出目录存在
        File outputDirFile = new File(outputDir);
        if (!outputDirFile.exists()) {
            outputDirFile.mkdirs();
        }

        // 单层PDF模式：无需OCR，逐个转换即可
        if ("single".equals(outputMode)) {
            for (String imagePath : imageFiles) {
                try {
                    String baseName = getBaseName(imagePath);
                    String outputFileName = buildOutputFileName(baseName, filenamePrefix, filenameSuffix, ".pdf");
                    String outputPath = outputDir + File.separator + outputFileName;

                    DualLayerPdfConverter.convertImageToSingleLayerPdf(imagePath, outputPath);
                    results.put(imagePath, outputPath);
                    log.debug("单层PDF转换成功: {} -> {}", imagePath, outputPath);
                } catch (Exception e) {
                    log.error("单层PDF转换失败: {}", imagePath, e);
                    results.put(imagePath, null);
                }
            }
            return results;
        }

        // 双层PDF模式：分批OCR识别
        try {
            LocalOcrClient ocrClient = new LocalOcrClient();
            ObjectMapper objectMapper = new ObjectMapper();

            // 分批处理
            for (int batchIndex = 0; batchIndex < totalBatches; batchIndex++) {
                int fromIndex = batchIndex * batchSize;
                int toIndex = Math.min(fromIndex + batchSize, totalFiles);
                List<String> batchFiles = imageFiles.subList(fromIndex, toIndex);

                log.info("处理第 {}/{} 批，共 {} 个图片 (索引 {}-{})",
                    batchIndex + 1, totalBatches, batchFiles.size(), fromIndex + 1, toIndex);

                try {
                    // Step 1: 批量OCR识别当前批次的图片
                    log.info("  Step 1: 批量OCR识别 {} 张图片", batchFiles.size());
                    JsonNode batchOcrResult = ocrClient.recognizeImages(batchFiles);

                    JsonNode pagesNode = batchOcrResult.get("pages");
                    if (pagesNode == null || !pagesNode.isArray()) {
                        throw new RuntimeException("OCR未返回有效的识别结果");
                    }

                    int pageCount = pagesNode.size();
                    log.info("  OCR识别完成，共 {} 页", pageCount);

                    // 【修复】检查OCR返回页数与图片数量是否匹配
                    if (pageCount < batchFiles.size()) {
                        log.warn("  OCR返回页数({})少于图片数量({})，部分图片可能无法处理", pageCount, batchFiles.size());
                    }

                    // Step 2: 为每个图片生成双层PDF
                    for (int i = 0; i < batchFiles.size(); i++) {
                        String imagePath = batchFiles.get(i);
                        try {
                            String baseName = getBaseName(imagePath);
                            String outputFileName = buildOutputFileName(baseName, filenamePrefix, filenameSuffix, ".pdf");
                            String outputPath = outputDir + File.separator + outputFileName;

                            // 【修复】检查是否有对应的OCR结果
                            if (i >= pageCount) {
                                log.warn("  图片 {} 没有对应的OCR结果，跳过", imagePath);
                                results.put(imagePath, null);
                                continue;
                            }

                            // 获取该图片对应的OCR结果
                            JsonNode pageResult = pagesNode.get(i);

                            // 获取图片尺寸
                            BufferedImage image = ImageIO.read(new File(imagePath));
                            if (image == null) {
                                throw new IOException("无法读取图片: " + imagePath);
                            }
                            int width;
                            int height;
                            try {
                                width = image.getWidth();
                                height = image.getHeight();
                            } finally {
                                // 【修复】确保图片资源被释放
                                image.flush();
                            }

                            // 使用OCR结果生成双层PDF
                            generateDualLayerPdf(imagePath, outputPath, pageResult, width, height);

                            results.put(imagePath, outputPath);
                            log.debug("  双层PDF生成成功: {} -> {}", imagePath, outputPath);

                        } catch (Exception e) {
                            log.error("  双层PDF生成失败: {}", imagePath, e);
                            results.put(imagePath, null);
                        }
                    }

                    // 批次间短暂休息，释放资源
                    if (batchIndex < totalBatches - 1) {
                        try {
                            Thread.sleep(500);
                            log.debug("批次间休息 500ms");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }

                } catch (Exception e) {
                    log.error("第 {} 批OCR识别失败", batchIndex + 1, e);
                    // 当前批次所有文件标记为失败
                    for (String imagePath : batchFiles) {
                        results.put(imagePath, null);
                    }
                }
            }

        } catch (Exception e) {
            log.error("批量转换过程失败", e);
        }

        long endTime = System.currentTimeMillis();
        long successCount = results.values().stream().filter(v -> v != null).count();
        log.info("批量转换完成: 成功 {}/{} 个，耗时 {} ms，平均 {} ms/个",
            successCount, totalFiles, (endTime - startTime),
            totalFiles > 0 ? (endTime - startTime) / totalFiles : 0);

        return results;
    }

    /**
     * 生成双层PDF（使用已有OCR结果）
     *
     * @param imagePath 源图片路径
     * @param outputPath 输出PDF路径
     * @param ocrResult OCR识别结果（单个页面的结果）
     * @param width 图片宽度
     * @param height 图片高度
     */
    private void generateDualLayerPdf(String imagePath, String outputPath, JsonNode ocrResult,
                                       int width, int height) throws Exception {
        // 调用 LocalOcrPdfConverter 的方法，传入已有的OCR结果
        // 这里需要创建一个临时文件保存OCR结果，然后调用现有的双层PDF生成逻辑
        com.pdfutil.common.core.utils.LocalOcrPdfConverter.generateDualLayerPdfWithOcrResult(
            imagePath, outputPath, ocrResult, width, height
        );
    }

    /**
     * 获取文件基础名称（不含扩展名）
     */
    private String getBaseName(String filePath) {
        String fileName = new File(filePath).getName();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            return fileName.substring(0, dotIndex);
        }
        return fileName;
    }

    /**
     * 构建输出文件名
     */
    private String buildOutputFileName(String baseName, String prefix, String suffix, String extension) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.isNotEmpty(prefix)) {
            sb.append(prefix);
        }
        sb.append(baseName);
        if (StringUtils.isNotEmpty(suffix)) {
            sb.append(suffix);
        }
        sb.append(extension);
        return sb.toString();
    }

    /**
     * 从源文件的父目录路径构建档号
     * 用于规则1：按档号分层+完整档号文件夹
     *
     * 例如：D:/input/J001/WS/2016/Y/BGS/0005/file.jpg -> 档号：J001-WS-2016-Y-BGS-0005
     *
     * @param sourcePath 源文件路径
     * @param targetDir 目标目录（用于确定输入目录的起点）
     * @return 档号字符串
     */
    private String buildArchiveNumberFromParentPath(String sourcePath, String targetDir) {
        try {
            File sourceFile = new File(sourcePath);
            File parentFile = sourceFile.getParentFile();

            if (parentFile == null) {
                log.warn("无法获取父目录，使用文件名作为档号: {}", sourcePath);
                String fileName = sourceFile.getName();
                int dotIndex = fileName.lastIndexOf('.');
                return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
            }

            // 检测是否来自上传目录，如果是则只使用直接父目录名作为档号
            String normalizedSourcePath = new File(sourcePath).getCanonicalPath();
            String os = System.getProperty("os.name", "").toLowerCase();
            String uploadPathMarker;

            if (os.contains("win")) {
                uploadPathMarker = System.getProperty("user.home").replace("\\", "/") + "/.pdfutil/upload";
            } else {
                uploadPathMarker = System.getProperty("user.home") + "/.pdfutil/upload";
            }

            // 标准化路径进行比较
            String normalizedSourcePathForCompare = normalizedSourcePath.replace("\\", "/");
            if (normalizedSourcePathForCompare.contains(uploadPathMarker.replace("\\", "/")) ||
                normalizedSourcePath.contains(".pdfutil") && normalizedSourcePath.contains("upload")) {
                // 来自上传目录，只使用直接父目录名
                String parentDirName = parentFile.getName();
                log.info("检测到源文件来自上传目录，使用父目录名作为档号: {}", parentDirName);
                return parentDirName;
            }

            // 标准化路径用于比较
            String normalizedTargetDir = new File(targetDir).getCanonicalPath();

            // 检查targetDir是否是sourcePath的父目录
            boolean targetDirIsParent = normalizedSourcePath.startsWith(normalizedTargetDir);

            // 从父目录开始向上遍历，构建档号
            // 如果targetDir是sourcePath的父目录，则遍历到targetDir为止
            // 否则，限制遍历深度为6层（典型的档号层级）
            java.util.List<String> dirParts = new java.util.ArrayList<>();
            File currentDir = parentFile;
            int maxDepth = targetDirIsParent ? Integer.MAX_VALUE : 6;

            // 向上遍历，收集所有目录名
            while (currentDir != null && dirParts.size() < maxDepth) {
                // 检查是否到达根目录（Windows: 盘符根目录如D:\，Unix: /）
                String currentPath = currentDir.getCanonicalPath();
                if (isRootDirectory(currentPath)) {
                    break;
                }

                String dirName = currentDir.getName();
                if (dirName.isEmpty()) {
                    break;
                }

                // 检查是否到达targetDir
                String currentDirPath = currentDir.getCanonicalPath();
                if (targetDirIsParent && currentDirPath.equals(normalizedTargetDir)) {
                    break;
                }

                dirParts.add(0, dirName); // 添加到列表开头，保持从上到下的顺序
                currentDir = currentDir.getParentFile();
            }

            // 如果没有收集到目录部分，使用文件名
            if (dirParts.isEmpty()) {
                log.warn("未找到目录层级，使用文件名作为档号");
                String fileName = sourceFile.getName();
                int dotIndex = fileName.lastIndexOf('.');
                return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
            }

            // 用"-"连接目录部分，形成档号
            StringBuilder archiveNumber = new StringBuilder();
            for (int i = 0; i < dirParts.size(); i++) {
                if (i > 0) {
                    archiveNumber.append("-");
                }
                archiveNumber.append(dirParts.get(i));
            }

            String result = archiveNumber.toString();
            log.info("从目录路径构建档号: 源路径={}, 档号={}", sourcePath, result);
            return result;

        } catch (Exception e) {
            log.error("构建档号失败，使用文件名作为档号", e);
            String fileName = new File(sourcePath).getName();
            int dotIndex = fileName.lastIndexOf('.');
            return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        }
    }

    /**
     * 检查路径是否是根目录
     * Windows: 盘符根目录如 D:\
     * Unix: /
     */
    private boolean isRootDirectory(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        // Windows根目录：如 D:\ 或 C:\
        if (path.matches("^[A-Za-z]:\\$")) {
            return true;
        }
        // Unix根目录：/
        if (path.equals("/") || path.equals(File.separator)) {
            return true;
        }
        return false;
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
}
