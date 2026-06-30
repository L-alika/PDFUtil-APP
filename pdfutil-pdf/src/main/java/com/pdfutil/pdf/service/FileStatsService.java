package com.pdfutil.pdf.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.util.*;

/**
 * 文件统计服务
 * 统计文件夹中图片按纸张规格分类（A1-A4）、PDF按纸张规格分类、及所有文件页数
 *
 * @author Alika
 * @date 2025-03-06
 */
@Service
public class FileStatsService {

    private static final Logger log = LoggerFactory.getLogger(FileStatsService.class);

    /**
     * 统计目录中的文件信息
     *
     * @param dirPath 目录路径
     * @return 统计结果
     */
    public Map<String, Object> getDirectoryStats(String dirPath) {
        Map<String, Object> result = new LinkedHashMap<>();

        File directory = new File(dirPath);
        if (!directory.exists() || !directory.isDirectory()) {
            result.put("error", "目录不存在或不是有效目录");
            return result;
        }

        // 初始化图片统计结果
        Map<String, Integer> imageStats = new LinkedHashMap<>();
        imageStats.put("大于A0", 0);
        imageStats.put("A0", 0);
        imageStats.put("A1", 0);
        imageStats.put("A2", 0);
        imageStats.put("A3", 0);
        imageStats.put("A4", 0);
        imageStats.put("other", 0);

        // 初始化PDF统计结果
        Map<String, Integer> pdfStats = new LinkedHashMap<>();
        pdfStats.put("大于A0", 0);
        pdfStats.put("A0", 0);
        pdfStats.put("A1", 0);
        pdfStats.put("A2", 0);
        pdfStats.put("A3", 0);
        pdfStats.put("A4", 0);
        pdfStats.put("other", 0);

        // 初始化OFD统计结果
        Map<String, Integer> ofdStats = new LinkedHashMap<>();
        ofdStats.put("大于A0", 0);
        ofdStats.put("A0", 0);
        ofdStats.put("A1", 0);
        ofdStats.put("A2", 0);
        ofdStats.put("A3", 0);
        ofdStats.put("A4", 0);
        ofdStats.put("other", 0);

        int pdfCount = 0;
        int pdfPages = 0;
        int imageCount = 0;  // 所有图片数量（包括jpg/jpeg/tif/png/bmp）
        int imagePages = 0;  // 图片页数（每个图片算1页）
        int officeCount = 0; // Office文档数量
        int officePages = 0; // Office文档页数（需要特殊处理）
        int ofdCount = 0;    // OFD文档数量
        int ofdPages = 0;    // OFD文档页数

        // 累积A4折合页数（用于加长纸张按比例折算）
        double totalA4Equivalent = 0;

        // 扫描目录
        File[] files = directory.listFiles();
        if (files == null) {
            result.put("error", "无法读取目录内容");
            return result;
        }

        log.info("开始统计目录: {}, 找到 {} 个文件", dirPath, files.length);
        for (File f : files) {
            log.debug("文件列表: {}, isFile={}, isDirectory={}", f.getName(), f.isFile(), f.isDirectory());
        }

        for (File file : files) {
            if (file.isFile()) {
                String fileName = file.getName().toLowerCase();
                log.debug("处理文件: {}, 原始文件名: {}", fileName, file.getName());

                if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") ||
                    fileName.endsWith(".tif") || fileName.endsWith(".tiff") ||
                    fileName.endsWith(".png") || fileName.endsWith(".bmp")) {
                    imageCount++;
                    imagePages++;
                    // 分析图片纸张规格
                    PaperSizeResult paperResult = analyzeImagePaperSize(file);
                    imageStats.put(paperResult.name, imageStats.getOrDefault(paperResult.name, 0) + 1);
                    // 累积A4折合页数
                    totalA4Equivalent += paperResult.a4Ratio;

                } else if (fileName.endsWith(".pdf")) {
                    if (isZipFile(file)) {
                        log.warn("文件 {} 扩展名为 .pdf，但头部特征检测为 OFD (ZIP)，将按 OFD 格式进行统计", file.getName());
                        ofdCount++;
                        ofdPages++;
                        ofdStats.put("A4", ofdStats.getOrDefault("A4", 0) + 1);
                        totalA4Equivalent += 1;
                    } else {
                        pdfCount++;
                        int pages = getPdfPageCount(file);
                        pdfPages += pages;
                        // 分析PDF纸张规格（取第一页）
                        PaperSizeResult paperResult = analyzePdfPaperSize(file);
                        pdfStats.put(paperResult.name, pdfStats.getOrDefault(paperResult.name, 0) + pages);
                        // 累积A4折合页数
                        totalA4Equivalent += paperResult.a4Ratio * pages;
                    }

                } else if (fileName.endsWith(".ofd")) {
                    log.info("识别到OFD文件: {}, fileName.toLower={}, endsWith(.ofd)={}", file.getName(), fileName, fileName.endsWith(".ofd"));
                    ofdCount++;
                    // OFD文档页数暂时按1计算（准确统计需要OFD解析库）
                    ofdPages++;
                    // OFD纸张规格暂时按A4计算（默认）
                    ofdStats.put("A4", ofdStats.getOrDefault("A4", 0) + 1);
                    // 累积A4折合页数
                    totalA4Equivalent += 1;
                    log.info("OFD统计更新: ofdCount={}, ofdPages={}, ofdStats[A4]={}", ofdCount, ofdPages, ofdStats.get("A4"));

                } else if (fileName.endsWith(".doc") || fileName.endsWith(".docx") ||
                           fileName.endsWith(".xls") || fileName.endsWith(".xlsx") ||
                           fileName.endsWith(".ppt") || fileName.endsWith(".pptx")) {
                    officeCount++;
                    // Office文档页数暂时按1计算（准确统计需要POI库）
                    officePages++;
                    // Office文档默认按A4（1张A4）计算，累积A4折合页数
                    totalA4Equivalent += 1;
                }
            }
        }

        // 组装结果
        result.put("imageStats", imageStats);
        result.put("pdfStats", pdfStats);
        result.put("ofdStats", ofdStats);
        result.put("pdfCount", pdfCount);
        result.put("pdfPages", pdfPages);
        result.put("imageCount", imageCount);
        result.put("imagePages", imagePages);
        result.put("officeCount", officeCount);
        result.put("officePages", officePages);
        result.put("ofdCount", ofdCount);
        result.put("ofdPages", ofdPages);
        // 文件统计：包含所有文件类型
        result.put("totalFiles", pdfCount + imageCount + officeCount + ofdCount);
        result.put("totalPages", pdfPages + imagePages + officePages + ofdPages);
        // A4折合页数（四舍五入）
        result.put("a4Equivalent", (int) Math.round(totalA4Equivalent));

        log.info("统计完成: pdfCount={}, pdfPages={}, imageCount={}, imagePages={}, ofdCount={}, ofdPages={}, officeCount={}, officePages={}, totalFiles={}, totalPages={}",
                pdfCount, pdfPages, imageCount, imagePages, ofdCount, ofdPages, officeCount, officePages, result.get("totalFiles"), result.get("totalPages"));
        log.info("OFD详细统计: ofdStats={}", ofdStats);

        return result;
    }

    /**
     * 分析图片的纸张规格
     * 使用标准A系列尺寸进行匹配，支持多种DPI
     *
     * A系列标准尺寸（单位：mm）：
     * A0: 841×1189, A1: 594×841, A2: 420×594, A3: 297×420, A4: 210×297
     * 宽高比：√2 ≈ 1.414
     *
     * @param imageFile 图片文件
     * @return 纸张规格：A1, A2, A3, A4, other
     */
    private PaperSizeResult analyzeImagePaperSize(File imageFile) {
        try {
            BufferedImage image = ImageIO.read(imageFile);
            if (image == null) {
                log.warn("无法读取图片: {}", imageFile.getName());
                return new PaperSizeResult("other", 1.0);
            }

            int width = image.getWidth();
            int height = image.getHeight();

            // 取长边和短边
            int longSide = Math.max(width, height);
            int shortSide = Math.min(width, height);

            if (longSide == 0 || shortSide == 0) {
                return new PaperSizeResult("other", 1.0);
            }

            // 计算宽高比
            double ratio = (double) longSide / shortSide;

            log.debug("图片 {} 尺寸: {}x{}, 长边: {}, 短边: {}, 宽高比: {:.2f}",
                    imageFile.getName(), width, height, longSide, shortSide, ratio);

            // 标准A系列尺寸（长边，单位：mm）
            // A4: 297mm, A3: 420mm, A2: 594mm, A1: 841mm
            // 常见DPI: 72, 96, 150, 200, 300, 600
            // 1 inch = 25.4mm

            // 尝试匹配标准DPI下的尺寸
            // 计算像素对应的物理尺寸（假设不同DPI）
            double minError = Double.MAX_VALUE;
            String bestPaper = "other";
            double bestRatio = 1.0;

            // 标准纸张长边尺寸（mm）
            double[] standardLongSides = {1189, 841, 594, 420, 297}; // A0, A1, A2, A3, A4
            String[] paperNames = {"A0", "A1", "A2", "A3", "A4"};
            double[] a4Multiples = {16.0, 8.0, 4.0, 2.0, 1.0};

            // 常见DPI值
            int[] commonDpis = {72, 96, 120, 150, 200, 300, 400, 600};

            for (int dpi : commonDpis) {
                // 将像素转换为mm
                double longSideMm = longSide * 25.4 / dpi;
                double shortSideMm = shortSide * 25.4 / dpi;

                for (int i = 0; i < standardLongSides.length; i++) {
                    double standardLong = standardLongSides[i];
                    // A系列短边 = 长边 / √2
                    double standardShort = standardLong / Math.sqrt(2);

                    // 计算尺寸误差（百分比）
                    double longError = Math.abs(longSideMm - standardLong) / standardLong;
                    double shortError = Math.abs(shortSideMm - standardShort) / standardShort;
                    double totalError = longError + shortError;

                    // 宽高比误差
                    double ratioError = Math.abs(ratio - Math.sqrt(2)) / Math.sqrt(2);

                    // 综合误差（尺寸误差占70%，宽高比误差占30%）
                    double combinedError = totalError * 0.7 + ratioError * 0.3;

                    // 允许15%的误差容忍
                    if (combinedError < 0.15 && combinedError < minError) {
                        minError = combinedError;
                        bestPaper = paperNames[i];
                        bestRatio = a4Multiples[i];
                    }
                }
            }

            // 如果没有匹配到标准尺寸，按长边像素范围粗略判断
            if ("other".equals(bestPaper)) {
                // 基于300dpi作为参考
                // 大于A0@300dpi: 长边大于14043px, A0@300dpi: 长边约14043px, A1@300dpi: 长边约9933px
                // A2@300dpi: 长边约7016px, A3@300dpi: 长边约4961px, A4@300dpi: 长边约3508px
                if (longSide > 15000) {
                    bestPaper = "大于A0";
                    // 大于A0按实际面积计算
                    // 折合A4页数 = (实际长 * 实际宽) / (210 * 297)
                    double areaMm2 = (double) longSide * shortSide * (25.4 * 25.4) / (300.0 * 300.0);
                    bestRatio = areaMm2 / 62370.0;
                } else if (longSide >= 9500) {
                    bestPaper = "A0";
                    bestRatio = 16.0;
                } else if (longSide >= 6500) {
                    bestPaper = "A1";
                    bestRatio = 8.0;
                } else if (longSide >= 4600) {
                    bestPaper = "A2";
                    bestRatio = 4.0;
                } else if (longSide >= 3200) {
                    bestPaper = "A3";
                    bestRatio = 2.0;
                } else {
                    // 小于A3的都归为A4（包括小于A4的小图，因为都可以用A4纸打印）
                    bestPaper = "A4";
                    bestRatio = 1.0;
                }
            }

            log.debug("图片 {} 判定纸张规格: {}, A4折合系数: {}", imageFile.getName(), bestPaper, bestRatio);
            return new PaperSizeResult(bestPaper, bestRatio);

        } catch (Exception e) {
            log.error("分析图片尺寸失败: {}", imageFile.getName(), e);
            return new PaperSizeResult("other", 1.0);
        }
    }

    /**
     * 分析PDF的纸张规格（取第一页）
     * 使用标准A系列尺寸进行精确匹配
     *
     * @param pdfFile PDF文件
     * @return 纸张规格：A1, A2, A3, A4, other
     */
    /**
     * 分析PDF的纸张规格（取第一页）
     * 使用标准A系列尺寸进行精确匹配
     *
     * @param pdfFile PDF文件
     * @return 纸张规格分析结果
     */
    private PaperSizeResult analyzePdfPaperSize(File pdfFile) {
        try (PDDocument document = PDDocument.load(pdfFile)) {
            if (document.getNumberOfPages() == 0) {
                return new PaperSizeResult("other", 1.0);
            }
            // 获取第一页的媒体框尺寸（单位：点，1点=1/72英寸）
            org.apache.pdfbox.pdmodel.common.PDRectangle mediaBox =
                document.getPage(0).getMediaBox();
            float width = mediaBox.getWidth();
            float height = mediaBox.getHeight();

            return analyzePdfPageSize(width, height, pdfFile.getName());

        } catch (Exception e) {
            log.error("分析PDF尺寸失败: {}", pdfFile.getName(), e);
            return new PaperSizeResult("other", 1.0);
        }
    }

    /**
     * 分析PDF所有页的纸张规格分布
     * 遍历所有页面，统计每种纸张规格的页数
     *
     * @param pdfFile PDF文件
     * @return 纸张规格分布Map，包含各规格页数及主要规格
     */
    public Map<String, Object> analyzePdfPaperSizeDistribution(File pdfFile) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 初始化各规格计数
        Map<String, Integer> distribution = new LinkedHashMap<>();
        distribution.put("大于A0", 0);
        distribution.put("A0", 0);
        distribution.put("A1", 0);
        distribution.put("A2", 0);
        distribution.put("A3", 0);
        distribution.put("A4", 0);
        distribution.put("other", 0);

        try (PDDocument document = PDDocument.load(pdfFile)) {
            int totalPages = document.getNumberOfPages();
            if (totalPages == 0) {
                result.put("error", "PDF文件为空");
                return result;
            }

            // 遍历所有页面
            for (int i = 0; i < totalPages; i++) {
                org.apache.pdfbox.pdmodel.common.PDRectangle mediaBox = document.getPage(i).getMediaBox();
                float width = mediaBox.getWidth();
                float height = mediaBox.getHeight();

                PaperSizeResult paperResult = analyzePdfPageSize(width, height, pdfFile.getName() + "[页" + (i + 1) + "]");
                distribution.put(paperResult.name, distribution.getOrDefault(paperResult.name, 0) + 1);
            }

            // 找出页数最多的规格作为"主要规格"
            String dominantSize = "other";
            int maxCount = 0;
            for (Map.Entry<String, Integer> entry : distribution.entrySet()) {
                if (entry.getValue() > maxCount) {
                    maxCount = entry.getValue();
                    dominantSize = entry.getKey();
                }
            }

            result.put("totalPages", totalPages);
            result.put("distribution", distribution);
            result.put("dominantSize", dominantSize);
            result.put("dominantSizeCount", maxCount);

            log.info("PDF {} 纸张规格分布分析完成，共{}页，主要规格: {} ({}页)",
                    pdfFile.getName(), totalPages, dominantSize, maxCount);

        } catch (Exception e) {
            log.error("分析PDF纸张规格分布失败: {}", pdfFile.getName(), e);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * 根据PDF页面宽高分析纸张规格
     *
     * @param width 页面宽度（点）
     * @param height 页面高度（点）
     * @param fileName 文件名（用于日志）
     * @return 纸张规格分析结果
     */
    private PaperSizeResult analyzePdfPageSize(float width, float height, String fileName) {

            // 取长边和短边
            float longSide = Math.max(width, height);
            float shortSide = Math.min(width, height);

            if (longSide == 0 || shortSide == 0) {
                return new PaperSizeResult("other", 1.0);
            }

            // 计算宽高比
            double ratio = (double) longSide / shortSide;

            log.debug("PDF {} 尺寸: {:.0f}x{:.0f}pt, 长边: {:.0f}, 短边: {:.0f}, 宽高比: {:.2f}",
                    fileName, width, height, longSide, shortSide, ratio);

            // 标准A系列纸张尺寸（单位：点，72dpi）
            // 使用精确的标准尺寸，允许±5%误差
            // A0: 2384 × 3370 pt (长边: 3370)
            // A1: 1684 × 2384 pt (长边: 2384)
            // A2: 1191 × 1684 pt (长边: 1684)
            // A3: 842 × 1191 pt (长边: 1191)
            // A4: 595 × 842 pt (长边: 842)

            double[] stdLongSides = {3370, 2384, 1684, 1191, 842};  // 长边
            double[] stdShortSides = {2384, 1684, 1191, 842, 595};  // 短边
            String[] paperNames = {"A0", "A1", "A2", "A3", "A4"};
            double[] a4Multiples = {16.0, 8.0, 4.0, 2.0, 1.0};

            String bestMatch = "other";
            double bestRatio = 1.0;
            double minError = Double.MAX_VALUE;

            for (int i = 0; i < stdLongSides.length; i++) {
                double stdLong = stdLongSides[i];
                double stdShort = stdShortSides[i];
                String paperName = paperNames[i];

                // 计算尺寸误差
                double longError = Math.abs(longSide - stdLong) / stdLong;
                double shortError = Math.abs(shortSide - stdShort) / stdShort;

                // 宽高比误差（A系列标准宽高比 = √2 ≈ 1.414）
                double ratioError = Math.abs(ratio - Math.sqrt(2)) / Math.sqrt(2);

                // 综合误差
                double combinedError = (longError + shortError) * 0.5 + ratioError * 0.5;

                // 允许10%的误差容忍
                if (combinedError < 0.10 && combinedError < minError) {
                    minError = combinedError;
                    bestMatch = paperName;
                    bestRatio = a4Multiples[i];
                }
            }

            // 如果没有精确匹配，使用范围判断（处理非标准尺寸）
            if ("other".equals(bestMatch)) {
                // 按长边范围判断（边界取两个标准尺寸的中点）
                if (longSide > 3500) {
                    bestMatch = "大于A0";  // 大于A0（3370是A0的标准长边）
                    // 面积计算：(长 * 宽 * (25.4/72.0)^2) / 62370.0
                    double areaMm2 = (double) longSide * shortSide * (25.4 * 25.4) / (72.0 * 72.0);
                    bestRatio = areaMm2 / 62370.0;
                } else if (longSide >= 2877) {
                    bestMatch = "A0";  // >= (3370+2384)/2
                    bestRatio = 16.0;
                } else if (longSide >= 2034) {
                    bestMatch = "A1";  // >= (2384+1684)/2
                    bestRatio = 8.0;
                } else if (longSide >= 1438) {
                    bestMatch = "A2";  // >= (1684+1191)/2
                    bestRatio = 4.0;
                } else if (longSide >= 1017) {
                    bestMatch = "A3";  // >= (1191+842)/2
                    bestRatio = 2.0;
                } else {
                    // 小于A3的都归为A4（包括小于A4的小尺寸，都可以用A4纸打印）
                    bestMatch = "A4";
                    bestRatio = 1.0;
                }
            }

            log.debug("PDF {} 判定纸张规格: {}, A4折合系数: {}", fileName, bestMatch, bestRatio);
            return new PaperSizeResult(bestMatch, bestRatio);
    }

    /**
     * 获取PDF文件页数
     *
     * @param pdfFile PDF文件
     * @return 页数
     */
    private int getPdfPageCount(File pdfFile) {
        try (PDDocument document = PDDocument.load(pdfFile)) {
            return document.getNumberOfPages();
        } catch (Exception e) {
            log.error("读取PDF页数失败: {}", pdfFile.getName(), e);
            return 1; // 失败时默认返回1页
        }
    }

    /**
     * 递归统计目录中的文件信息（包括子目录）
     *
     * @param dirPath 目录路径
     * @return 统计结果
     */
    public Map<String, Object> getDirectoryStatsRecursive(String dirPath) {
        Map<String, Object> result = new LinkedHashMap<>();

        File directory = new File(dirPath);
        if (!directory.exists() || !directory.isDirectory()) {
            result.put("error", "目录不存在或不是有效目录");
            return result;
        }

        // 初始化图片统计结果
        Map<String, Integer> imageStats = new LinkedHashMap<>();
        imageStats.put("大于A0", 0);
        imageStats.put("A0", 0);
        imageStats.put("A1", 0);
        imageStats.put("A2", 0);
        imageStats.put("A3", 0);
        imageStats.put("A4", 0);
        imageStats.put("other", 0);

        // 初始化PDF统计结果
        Map<String, Integer> pdfStats = new LinkedHashMap<>();
        pdfStats.put("大于A0", 0);
        pdfStats.put("A0", 0);
        pdfStats.put("A1", 0);
        pdfStats.put("A2", 0);
        pdfStats.put("A3", 0);
        pdfStats.put("A4", 0);
        pdfStats.put("other", 0);

        // 初始化OFD统计结果
        Map<String, Integer> ofdStats = new LinkedHashMap<>();
        ofdStats.put("大于A0", 0);
        ofdStats.put("A0", 0);
        ofdStats.put("A1", 0);
        ofdStats.put("A2", 0);
        ofdStats.put("A3", 0);
        ofdStats.put("A4", 0);
        ofdStats.put("other", 0);

        int pdfCount = 0;
        int pdfPages = 0;
        int imageCount = 0;
        int imagePages = 0;
        int officeCount = 0;
        int officePages = 0;
        int ofdCount = 0;
        int ofdPages = 0;

        // 累积A4折合页数（用于加长纸张按比例折算）
        double totalA4Equivalent = 0;

        // 递归扫描
        List<File> allFiles = new ArrayList<>();
        collectFiles(directory, allFiles);

        for (File file : allFiles) {
            String fileName = file.getName().toLowerCase();

            if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") ||
                fileName.endsWith(".tif") || fileName.endsWith(".tiff") ||
                fileName.endsWith(".png") || fileName.endsWith(".bmp")) {
                imageCount++;
                imagePages++;
                PaperSizeResult paperResult = analyzeImagePaperSize(file);
                imageStats.put(paperResult.name, imageStats.getOrDefault(paperResult.name, 0) + 1);
                // 累积A4折合页数
                totalA4Equivalent += paperResult.a4Ratio;

            } else if (fileName.endsWith(".pdf")) {
                if (isZipFile(file)) {
                    log.warn("文件 {} 扩展名为 .pdf，但头部特征检测为 OFD (ZIP)，将按 OFD 格式进行统计", file.getName());
                    ofdCount++;
                    ofdPages++;
                    ofdStats.put("A4", ofdStats.getOrDefault("A4", 0) + 1);
                    totalA4Equivalent += 1;
                } else {
                    pdfCount++;
                    int pages = getPdfPageCount(file);
                    pdfPages += pages;
                    PaperSizeResult paperResult = analyzePdfPaperSize(file);
                    pdfStats.put(paperResult.name, pdfStats.getOrDefault(paperResult.name, 0) + pages);
                    // 累积A4折合页数
                    totalA4Equivalent += paperResult.a4Ratio * pages;
                }

            } else if (fileName.endsWith(".ofd")) {
                ofdCount++;
                ofdPages++;
                // OFD暂时按A4计算
                ofdStats.put("A4", ofdStats.getOrDefault("A4", 0) + 1);
                // 累积A4折合页数
                totalA4Equivalent += 1;

            } else if (fileName.endsWith(".doc") || fileName.endsWith(".docx") ||
                       fileName.endsWith(".xls") || fileName.endsWith(".xlsx") ||
                       fileName.endsWith(".ppt") || fileName.endsWith(".pptx")) {
                officeCount++;
                officePages++;
                // Office文档默认按A4（1张A4）计算，累积A4折合页数
                totalA4Equivalent += 1;
            }
        }

        // 组装结果
        result.put("imageStats", imageStats);
        result.put("pdfStats", pdfStats);
        result.put("ofdStats", ofdStats);
        result.put("pdfCount", pdfCount);
        result.put("pdfPages", pdfPages);
        result.put("imageCount", imageCount);
        result.put("imagePages", imagePages);
        result.put("officeCount", officeCount);
        result.put("officePages", officePages);
        result.put("ofdCount", ofdCount);
        result.put("ofdPages", ofdPages);
        // 文件列表统计：包含所有文件类型
        result.put("totalFiles", pdfCount + imageCount + officeCount + ofdCount);
        result.put("totalPages", pdfPages + imagePages + officePages + ofdPages);
        // A4折合页数（四舍五入）
        result.put("a4Equivalent", (int) Math.round(totalA4Equivalent));

        return result;
    }

    /**
     * 递归收集目录中的所有文件
     */
    private void collectFiles(File directory, List<File> fileList) {
        File[] files = directory.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isFile()) {
                fileList.add(file);
            } else if (file.isDirectory()) {
                collectFiles(file, fileList);
            }
        }
    }

    /**
     * 统计文件列表的统计信息
     *
     * @param filePaths 文件路径列表
     * @return 统计结果
     */
    public Map<String, Object> getFileListStats(List<String> filePaths) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (filePaths == null || filePaths.isEmpty()) {
            result.put("error", "文件列表为空");
            return result;
        }

        // 初始化图片统计结果
        Map<String, Integer> imageStats = new LinkedHashMap<>();
        imageStats.put("大于A0", 0);
        imageStats.put("A0", 0);
        imageStats.put("A1", 0);
        imageStats.put("A2", 0);
        imageStats.put("A3", 0);
        imageStats.put("A4", 0);
        imageStats.put("other", 0);

        // 初始化PDF统计结果
        Map<String, Integer> pdfStats = new LinkedHashMap<>();
        pdfStats.put("大于A0", 0);
        pdfStats.put("A0", 0);
        pdfStats.put("A1", 0);
        pdfStats.put("A2", 0);
        pdfStats.put("A3", 0);
        pdfStats.put("A4", 0);
        pdfStats.put("other", 0);

        // 初始化OFD统计结果
        Map<String, Integer> ofdStats = new LinkedHashMap<>();
        ofdStats.put("大于A0", 0);
        ofdStats.put("A0", 0);
        ofdStats.put("A1", 0);
        ofdStats.put("A2", 0);
        ofdStats.put("A3", 0);
        ofdStats.put("A4", 0);
        ofdStats.put("other", 0);

        int pdfCount = 0;
        int pdfPages = 0;
        int imageCount = 0;
        int imagePages = 0;
        int officeCount = 0;
        int officePages = 0;
        int ofdCount = 0;
        int ofdPages = 0;
        double totalA4Equivalent = 0;

        for (String filePath : filePaths) {
            File file = new File(filePath);
            if (!file.exists() || !file.isFile()) {
                continue;
            }

            String fileName = file.getName().toLowerCase();

            if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") ||
                fileName.endsWith(".tif") || fileName.endsWith(".tiff") ||
                fileName.endsWith(".png") || fileName.endsWith(".bmp")) {
                imageCount++;
                imagePages++;
                PaperSizeResult paperResult = analyzeImagePaperSize(file);
                imageStats.put(paperResult.name, imageStats.getOrDefault(paperResult.name, 0) + 1);
                // 累积A4折合页数
                totalA4Equivalent += paperResult.a4Ratio;

            } else if (fileName.endsWith(".pdf")) {
                if (isZipFile(file)) {
                    log.warn("文件 {} 扩展名为 .pdf，但头部特征检测为 OFD (ZIP)，将按 OFD 格式进行统计", file.getName());
                    ofdCount++;
                    ofdPages++;
                    ofdStats.put("A4", ofdStats.getOrDefault("A4", 0) + 1);
                    totalA4Equivalent += 1;
                } else {
                    pdfCount++;
                    int pages = getPdfPageCount(file);
                    pdfPages += pages;
                    PaperSizeResult paperResult = analyzePdfPaperSize(file);
                    pdfStats.put(paperResult.name, pdfStats.getOrDefault(paperResult.name, 0) + pages);
                    // 累积A4折合页数
                    totalA4Equivalent += paperResult.a4Ratio * pages;
                }

            } else if (fileName.endsWith(".ofd")) {
                ofdCount++;
                ofdPages++;
                // OFD暂时按A4计算
                ofdStats.put("A4", ofdStats.getOrDefault("A4", 0) + 1);
                // 累积A4折合页数
                totalA4Equivalent += 1;

            } else if (fileName.endsWith(".doc") || fileName.endsWith(".docx") ||
                       fileName.endsWith(".xls") || fileName.endsWith(".xlsx") ||
                       fileName.endsWith(".ppt") || fileName.endsWith(".pptx")) {
                officeCount++;
                officePages++;
                // Office文档默认按A4（1张A4）计算，累积A4折合页数
                totalA4Equivalent += 1;
            }
        }

        // 组装结果
        result.put("imageStats", imageStats);
        result.put("pdfStats", pdfStats);
        result.put("ofdStats", ofdStats);
        result.put("pdfCount", pdfCount);
        result.put("pdfPages", pdfPages);
        result.put("imageCount", imageCount);
        result.put("imagePages", imagePages);
        result.put("officeCount", officeCount);
        result.put("officePages", officePages);
        result.put("ofdCount", ofdCount);
        result.put("ofdPages", ofdPages);
        // totalFiles包含PDF、图片、Office和OFD，与totalPages保持一致
        result.put("totalFiles", pdfCount + imageCount + officeCount + ofdCount);
        result.put("totalPages", pdfPages + imagePages + officePages + ofdPages);
        // A4折合页数（四舍五入）
        result.put("a4Equivalent", (int) Math.round(totalA4Equivalent));

        return result;
    }

    /**
     * 获取子文件夹统计数据（用于导出Excel）
     * 遍历第一层子文件夹，统计每个子文件夹的纸张规格
     *
     * @param dirPath 父目录路径
     * @return 子文件夹统计列表
     */
    public List<Map<String, Object>> getSubfolderStats(String dirPath) {
        List<Map<String, Object>> result = new ArrayList<>();

        File directory = new File(dirPath);
        if (!directory.exists() || !directory.isDirectory()) {
            return result;
        }

        // 获取第一层子文件夹
        File[] subdirs = directory.listFiles(File::isDirectory);
        if (subdirs == null || subdirs.length == 0) {
            return result;
        }

        // 按名称排序
        Arrays.sort(subdirs, Comparator.comparing(File::getName));

        for (File subdir : subdirs) {
            Map<String, Object> subfolderData = new LinkedHashMap<>();
            subfolderData.put("folderName", subdir.getName());
            subfolderData.put("folderPath", subdir.getAbsolutePath());

            // 统计该子文件夹（递归）
            Map<String, Object> stats = getDirectoryStatsRecursive(subdir.getAbsolutePath());

            // 图片纸张统计
            Map<String, Integer> imageStats = (Map<String, Integer>) stats.get("imageStats");
            if (imageStats == null) {
                imageStats = new HashMap<>();
            }
            subfolderData.put("imageLargerThanA0", imageStats.getOrDefault("大于A0", 0));
            subfolderData.put("imageA0", imageStats.getOrDefault("A0", 0));
            subfolderData.put("imageA1", imageStats.getOrDefault("A1", 0));
            subfolderData.put("imageA2", imageStats.getOrDefault("A2", 0));
            subfolderData.put("imageA3", imageStats.getOrDefault("A3", 0));
            subfolderData.put("imageA4", imageStats.getOrDefault("A4", 0));
            subfolderData.put("imageOther", imageStats.getOrDefault("other", 0));

            // PDF纸张统计
            Map<String, Integer> pdfStats = (Map<String, Integer>) stats.get("pdfStats");
            if (pdfStats == null) {
                pdfStats = new HashMap<>();
            }
            subfolderData.put("pdfLargerThanA0", pdfStats.getOrDefault("大于A0", 0));
            subfolderData.put("pdfA0", pdfStats.getOrDefault("A0", 0));
            subfolderData.put("pdfA1", pdfStats.getOrDefault("A1", 0));
            subfolderData.put("pdfA2", pdfStats.getOrDefault("A2", 0));
            subfolderData.put("pdfA3", pdfStats.getOrDefault("A3", 0));
            subfolderData.put("pdfA4", pdfStats.getOrDefault("A4", 0));
            subfolderData.put("pdfOther", pdfStats.getOrDefault("other", 0));

            // OFD纸张统计
            Map<String, Integer> ofdStats = (Map<String, Integer>) stats.get("ofdStats");
            if (ofdStats == null) {
                ofdStats = new HashMap<>();
            }
            subfolderData.put("ofdLargerThanA0", ofdStats.getOrDefault("大于A0", 0));
            subfolderData.put("ofdA0", ofdStats.getOrDefault("A0", 0));
            subfolderData.put("ofdA1", ofdStats.getOrDefault("A1", 0));
            subfolderData.put("ofdA2", ofdStats.getOrDefault("A2", 0));
            subfolderData.put("ofdA3", ofdStats.getOrDefault("A3", 0));
            subfolderData.put("ofdA4", ofdStats.getOrDefault("A4", 0));
            subfolderData.put("ofdOther", ofdStats.getOrDefault("other", 0));

            // 合计（图片+PDF+OFD）
            int totalLargerThanA0 = imageStats.getOrDefault("大于A0", 0) + pdfStats.getOrDefault("大于A0", 0) + ofdStats.getOrDefault("大于A0", 0);
            int totalA0 = imageStats.getOrDefault("A0", 0) + pdfStats.getOrDefault("A0", 0) + ofdStats.getOrDefault("A0", 0);
            int totalA1 = imageStats.getOrDefault("A1", 0) + pdfStats.getOrDefault("A1", 0) + ofdStats.getOrDefault("A1", 0);
            int totalA2 = imageStats.getOrDefault("A2", 0) + pdfStats.getOrDefault("A2", 0) + ofdStats.getOrDefault("A2", 0);
            int totalA3 = imageStats.getOrDefault("A3", 0) + pdfStats.getOrDefault("A3", 0) + ofdStats.getOrDefault("A3", 0);
            int totalA4 = imageStats.getOrDefault("A4", 0) + pdfStats.getOrDefault("A4", 0) + ofdStats.getOrDefault("A4", 0);
            int totalOther = imageStats.getOrDefault("other", 0) + pdfStats.getOrDefault("other", 0) + ofdStats.getOrDefault("other", 0);

            subfolderData.put("totalLargerThanA0", totalLargerThanA0);
            subfolderData.put("totalA0", totalA0);
            subfolderData.put("totalA1", totalA1);
            subfolderData.put("totalA2", totalA2);
            subfolderData.put("totalA3", totalA3);
            subfolderData.put("totalA4", totalA4);
            subfolderData.put("totalOther", totalOther);

            // 计算A4折合页数（包括大于A0和Office文档）
            // 大于A0按32张A4计算，A0=16张A4, A1=8张A4, A2=4张A4, A3=2张A4, A4=1张A4，Office文档默认按A4（1张A4）计算
            int a4Equivalent = stats.containsKey("a4Equivalent") ? (Integer) stats.get("a4Equivalent") : 0;
            subfolderData.put("a4Equivalent", a4Equivalent);

            // 文件总数 = PDF + 图片 + Office + OFD
            int pdfCnt = stats.containsKey("pdfCount") ? (Integer) stats.get("pdfCount") : 0;
            int imgCnt = stats.containsKey("imageCount") ? (Integer) stats.get("imageCount") : 0;
            int officeCnt = stats.containsKey("officeCount") ? (Integer) stats.get("officeCount") : 0;
            int ofdCnt = stats.containsKey("ofdCount") ? (Integer) stats.get("ofdCount") : 0;
            subfolderData.put("totalFiles", pdfCnt + imgCnt + officeCnt + ofdCnt);
            subfolderData.put("totalPages", stats.containsKey("totalPages") ? stats.get("totalPages") : 0);

            result.add(subfolderData);
        }

        return result;
    }

    /**
     * 检测文件是否是ZIP格式（用于兼容后缀为.pdf但实际是OFD格式的文件）
     */
    private boolean isZipFile(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[2];
            if (fis.read(header) == 2) {
                return header[0] == 0x50 && header[1] == 0x4B; // 'P' and 'K'
            }
        } catch (Exception e) {
            // 忽略读取错误，默认不为zip
        }
        return false;
    }

    /**
     * 计算A4折合页数
     * 大于A0=32张A4, A0=16张A4, A1=8张A4, A2=4张A4, A3=2张A4, A4=1张A4
     *
     * @param largerThanA0 大于A0数量
     * @param a0 A0数量
     * @param a1 A1数量
     * @param a2 A2数量
     * @param a3 A3数量
     * @param a4 A4数量
     * @return A4折合页数
     */
    public static int calculateA4Equivalent(int largerThanA0, int a0, int a1, int a2, int a3, int a4) {
        return largerThanA0 * 32 + a0 * 16 + a1 * 8 + a2 * 4 + a3 * 2 + a4;
    }

    /**
     * 纸张规格分析结果包装类
     */
    public static class PaperSizeResult {
        public final String name;
        public final double a4Ratio;

        public PaperSizeResult(String name, double a4Ratio) {
            this.name = name;
            this.a4Ratio = a4Ratio;
        }
    }
}