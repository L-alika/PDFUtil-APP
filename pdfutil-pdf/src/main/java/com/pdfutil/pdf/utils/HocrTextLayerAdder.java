package com.pdfutil.pdf.utils;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 使用hOCR文件在PDF上添加文本层
 *
 * @author Alika
 * @date 2025-02-05
 */
public class HocrTextLayerAdder {

    private static final Logger log = LoggerFactory.getLogger(HocrTextLayerAdder.class);

    /**
     * 字体支持字符缓存
     * Key: 字体名称 (font.getName())
     * Value: 该字体支持的字符集合
     * 使用 null 表示"不支持任何字符"的特殊情况
     */
    private static volatile Map<String, Set<Character>> fontSupportedCharsCache;
    private static final Object cacheLock = new Object();

    /**
     * 使用hOCR文件在PDF上添加文本层
     *
     * @param inputPdfPath 输入PDF文件路径
     * @param hocrPath hOCR文件路径
     * @param outputPdfPath 输出PDF文件路径
     * @throws Exception 处理失败时抛出异常
     */
    /**
     * 从图片和OCR结果创建双层PDF（默认150 DPI，适用于PDF提取的图片）
     *
     * @param imagePath   输入图片路径
     * @param ocrResult   OCR API返回的JSON结果字符串
     * @param outputPath  输出PDF路径
     * @param imageWidth  图片宽度
     * @param imageHeight 图片高度
     */
    public static void createDualLayerPdfFromOcr(String imagePath, String ocrResult,
                                                 int imageWidth, int imageHeight, String outputPath) throws Exception {
        createDualLayerPdfFromOcr(imagePath, ocrResult, imageWidth, imageHeight, outputPath, 300.0f);
    }

    /**
     * 从图片和OCR结果创建双层PDF（指定源DPI）
     *
     * 【重要】DPI一致性说明：
     * - 建议统一使用 300 DPI 以保证识别精度和图层对齐
     * - 必须确保 OCR 识别时的 DPI 与生成双层 PDF 时的 sourceDpi 参数一致
     *
     * @param imagePath   输入图片路径
     * @param ocrResult   OCR API返回的JSON结果字符串
     * @param outputPath  输出PDF路径
     * @param imageWidth  图片宽度
     * @param imageHeight 图片高度
     * @param sourceDpi   图片源DPI（统一使用300）
     */
    public static void createDualLayerPdfFromOcr(String imagePath, String ocrResult,
                                                 int imageWidth, int imageHeight, String outputPath,
                                                 float sourceDpi) throws Exception {
        createDualLayerPdfFromOcr(new String[]{imagePath}, ocrResult, new int[]{imageWidth}, new int[]{imageHeight}, outputPath, sourceDpi);
    }

    public static void createDualLayerPdfFromOcr(String[] imagePaths, String ocrResult,
                                                 int[] imageWidths, int[] imageHeights, String outputPath,
                                                 float sourceDpi) throws Exception {
        log.info("从图片和OCR结果创建多页双层PDF: pages={}, sourceDpi={}", imagePaths.length, sourceDpi);

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(ocrResult);
        com.fasterxml.jackson.databind.JsonNode pages = root.get("pages");

        int imagePageCount = Math.min(imagePaths.length, Math.min(imageWidths.length, imageHeights.length));
        int ocrPageCount = (pages != null && pages.isArray()) ? pages.size() : 0;
        if (ocrPageCount != imagePageCount) {
            log.warn("OCR页数与图片页数不一致: OCR pages={}, image pages={}", ocrPageCount, imagePageCount);
        }

        int pageCount = imagePageCount;
        if (pageCount <= 0) {
            throw new IllegalArgumentException("没有可用于生成PDF的页面数据");
        }

        try (PDDocument document = new PDDocument()) {
            PDFont font = loadChineseFont(document);
            if (font == null) {
                log.error("无法加载中文字体，回退到 Helvetica");
                font = PDType1Font.HELVETICA;
            } else {
                log.info("中文字体加载成功: {}", font.getName());
            }

            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                com.fasterxml.jackson.databind.JsonNode pageData =
                        (pages != null && pages.isArray() && pageIndex < pages.size()) ? pages.get(pageIndex) : null;
                addOcrPageToDocument(document, imagePaths[pageIndex], pageData,
                        imageWidths[pageIndex], imageHeights[pageIndex], sourceDpi, font, pageIndex);
            }

            log.info("正在保存PDF文件到: {}", outputPath);
            document.save(outputPath);
            log.info("多页双层PDF生成成功: {}", outputPath);
        }
    }

    private static void addOcrPageToDocument(PDDocument document, String imagePath,
                                             com.fasterxml.jackson.databind.JsonNode pageData,
                                             int imageWidth, int imageHeight,
                                             float sourceDpi, PDFont font, int pageIndex) throws Exception {
        float pdfUnitWidth = imageWidth / sourceDpi * 72.0f;
        float pdfUnitHeight = imageHeight / sourceDpi * 72.0f;
        float scaleX = pdfUnitWidth / imageWidth;
        float scaleY = pdfUnitHeight / imageHeight;
        float expectedScale = 72.0f / sourceDpi;

        log.info("处理第 {} 页: 图片={} 尺寸={}x{} 源DPI={} PDF尺寸={}x{}",
                pageIndex + 1, imagePath, imageWidth, imageHeight, sourceDpi, pdfUnitWidth, pdfUnitHeight);
        if (Math.abs(scaleX - expectedScale) > 0.001f || Math.abs(scaleY - expectedScale) > 0.001f) {
            log.warn("第 {} 页缩放比例异常: expectedScale={}, scaleX={}, scaleY={}",
                    pageIndex + 1, expectedScale, scaleX, scaleY);
        }

        PDPage page = new PDPage(new PDRectangle(pdfUnitWidth, pdfUnitHeight));
        document.addPage(page);

        if (pageData != null && pageData.isArray()) {
            int totalBlocks = pageData.size();
            try (PDPageContentStream contentStream = new PDPageContentStream(
                    document, page, PDPageContentStream.AppendMode.PREPEND, false, false)) {
                for (int i = 0; i < totalBlocks; i++) {
                    com.fasterxml.jackson.databind.JsonNode item = pageData.get(i);
                    String text = item.get("text").asText();
                    com.fasterxml.jackson.databind.JsonNode regionNode = item.get("text_region");

                    if (regionNode == null || !regionNode.isArray() || regionNode.size() < 4) {
                        continue;
                    }

                    int[] bbox = parseBoundingBox(regionNode);
                    float pdfX = bbox[0] * scaleX;
                    float pdfYTop = pdfUnitHeight - (bbox[1] * scaleY);
                    float pdfYBottom = pdfUnitHeight - (bbox[3] * scaleY);
                    float actualTextHeight = pdfYTop - pdfYBottom;
                    float actualTextWidth = (bbox[2] - bbox[0]) * scaleX;

                    if (actualTextHeight <= 0 || actualTextWidth <= 0) {
                        continue;
                    }

                    String filteredText = filterUnsupportedChars(font, text);
                    if (filteredText.isEmpty()) {
                        // 【优化#7】记录跳过的文本块信息，便于调试
                        if (log.isDebugEnabled() && i < 3) {
                            log.debug("第 {} 页第 {} 个文本块被字体过滤后为空，原文: {}",
                                    pageIndex + 1, i + 1,
                                    text.length() > 20 ? text.substring(0, 20) + "..." : text);
                        }
                        continue;
                    }

                    PDFontDescriptor fontDescriptor = font.getFontDescriptor();
                    float ascent = fontDescriptor != null ? fontDescriptor.getAscent() : 880f;
                    float descent = fontDescriptor != null ? fontDescriptor.getDescent() : -120f;
                    float normalizedFontHeight = (ascent - descent) / 1000f;
                    if (normalizedFontHeight <= 0) {
                        normalizedFontHeight = 1.0f;
                    }

                    boolean isVertical = isVerticalText(actualTextWidth, actualTextHeight, filteredText);
                    if (isVertical) {
                        int N = filteredText.length();
                        if (N == 1) {
                            // 单个竖版字符：基于高度计算字号（竖版字符的高度是主要尺寸）
                            float charFontSize = Math.max(actualTextHeight / normalizedFontHeight, 1.0f);
                            charFontSize = Math.min(charFontSize, actualTextHeight * 1.5f);
                            charFontSize = Math.min(charFontSize, actualTextWidth * 1.5f);
                            float charFontDescent = Math.abs(descent / 1000f) * charFontSize;

                            float charTextWidth;
                            try {
                                charTextWidth = font.getStringWidth(filteredText) / 1000f * charFontSize;
                            } catch (IOException e) {
                                continue;
                            }

                            if (charTextWidth <= 0) {
                                continue;
                            }

                            float horizontalScaling = (actualTextWidth / charTextWidth) * 100.0f;
                            horizontalScaling = Math.max(10.0f, Math.min(horizontalScaling, 150.0f)); // 允许适度拉伸以填满框宽

                            // 单个竖版字符垂直与水平双向居中对齐
                            float cx = pdfX + Math.max(0.0f, (actualTextWidth - charTextWidth * (horizontalScaling / 100.0f)) / 2.0f);
                            float cy = pdfYBottom + (actualTextHeight - charFontSize) / 2.0f + charFontDescent + (charFontSize * 0.05f);

                            contentStream.beginText();
                            contentStream.setFont(font, charFontSize);
                            contentStream.setHorizontalScaling(horizontalScaling);
                            contentStream.setRenderingMode(RenderingMode.NEITHER);
                            contentStream.newLineAtOffset(cx, cy);
                            contentStream.showText(filteredText);
                            contentStream.endText();
                        } else {
                            // 多字符竖版文字：逐个字符绘制
                            // 统一处理逻辑：保留空格字符但不绘制，避免数据不一致
                            int totalChars = filteredText.length();

                            // 计算有效字符数（排除空格），用于槽高计算
                            int effectiveCharCount = 0;
                            for (int charIdx = 0; charIdx < totalChars; charIdx++) {
                                if (!filteredText.substring(charIdx, charIdx + 1).trim().isEmpty()) {
                                    effectiveCharCount++;
                                }
                            }

                            if (effectiveCharCount == 0) continue;

                            float charH = actualTextHeight / effectiveCharCount;
                            // 字体大小主要由单字高度和宽度决定，使其尽量居中填充
                            float charFontSize = Math.max(charH / normalizedFontHeight, 1.0f);
                            charFontSize = Math.min(charFontSize, charH * 1.5f);
                            charFontSize = Math.min(charFontSize, actualTextWidth * 1.5f);

                            float charFontDescent = Math.abs(descent / 1000f) * charFontSize;

                            // 遍历原始文本，保持字符索引一致性
                            int effectiveIndex = 0;  // 有效字符索引（用于位置计算）
                            for (int charIdx = 0; charIdx < totalChars; charIdx++) {
                                String charStr = String.valueOf(filteredText.charAt(charIdx));

                                // 跳过空格字符（不绘制但保留位置）
                                if (charStr.trim().isEmpty()) {
                                    continue;
                                }

                                float charYTop = pdfYTop - effectiveIndex * charH;
                                float charYBottom = pdfYTop - (effectiveIndex + 1) * charH;

                                float charTextWidth;
                                try {
                                    charTextWidth = font.getStringWidth(charStr) / 1000f * charFontSize;
                                } catch (IOException e) {
                                    effectiveIndex++;  // 即使失败也要递增索引
                                    continue;
                                }

                                if (charTextWidth <= 0) {
                                    effectiveIndex++;  // 即使失败也要递增索引
                                    continue;
                                }

                                float horizontalScaling = (actualTextWidth / charTextWidth) * 100.0f;
                                horizontalScaling = Math.max(10.0f, Math.min(horizontalScaling, 150.0f)); // 允许适度拉伸以填满框宽

                                // 垂直与水平双向居中对齐当前字符的位置
                                float cx = pdfX + Math.max(0.0f, (actualTextWidth - charTextWidth * (horizontalScaling / 100.0f)) / 2.0f);
                                float cy = charYBottom + (charH - charFontSize) / 2.0f + charFontDescent + (charFontSize * 0.05f);

                                contentStream.beginText();
                                contentStream.setFont(font, charFontSize);
                                contentStream.setHorizontalScaling(horizontalScaling);
                                contentStream.setRenderingMode(RenderingMode.NEITHER);
                                contentStream.newLineAtOffset(cx, cy);
                                contentStream.showText(charStr);
                                contentStream.endText();

                                effectiveIndex++;  // 只绘制了有效字符后才递增索引
                            }
                        }
                    } else {
                        float fontSize = Math.max(actualTextHeight / normalizedFontHeight, 1.0f);
                        // 限制最大字体大小，避免因为某些异常大的 box 导致字体溢出
                        fontSize = Math.min(fontSize, actualTextHeight * 1.5f);

                        float fontAscent = (ascent / 1000f) * fontSize;
                        float fontDescent = Math.abs(descent / 1000f) * fontSize;
                        float x = pdfX;
                        
                        // 【优化】垂直对齐策略：微调基准线 (Baseline) 位置
                        // 默认情况下 OCR 框底部的 y 坐标是 pdfYBottom，
                        // 我们将基准线定位在底部稍微往上 0.12 * fontSize 的位置（通用 descent 比例）
                        // 增加 1 像素左右的向上偏移补偿，解决大多数 PDF 阅读器选择文字偏下的问题
                        float y = pdfYBottom + fontDescent + (fontSize * 0.05f); 

                        float textWidth;
                        try {
                            textWidth = font.getStringWidth(filteredText) / 1000f * fontSize;
                        } catch (IOException e) {
                            log.debug("第 {} 页计算文本宽度失败，跳过文本块: {}", pageIndex + 1, filteredText, e);
                            continue;
                        }

                        if (textWidth <= 0) {
                            continue;
                        }

                        float horizontalScaling = (actualTextWidth / textWidth) * 100.0f;
                        int N = filteredText.length();
                        
                        // 先计算如果均匀分布的话，每个字之间的间距 gap
                        float gap = 0;
                        float sumWidths = 0;
                        float[] charWidths = new float[N];
                        if (N > 1) {
                            for (int charIdx = 0; charIdx < N; charIdx++) {
                                String charStr = String.valueOf(filteredText.charAt(charIdx));
                                if (charStr.trim().isEmpty()) {
                                    // 优化空格的宽度估算，避免使用高宽度的默认回退值导致间距计算错误
                                    charWidths[charIdx] = charStr.equals("　") ? fontSize : fontSize * 0.3f;
                                } else {
                                    try {
                                        charWidths[charIdx] = font.getStringWidth(charStr) / 1000f * fontSize;
                                    } catch (IOException e) {
                                        charWidths[charIdx] = fontSize;
                                    }
                                }
                                sumWidths += charWidths[charIdx];
                            }
                            if (sumWidths > 0) {
                                gap = (actualTextWidth - sumWidths) / (N - 1);
                            }
                        }

                        // 只有当拉伸比例过大且计算出的字间距较大（大于字号的15%）时，才逐字对齐绘制。
                        // 避免普通段落被拆成单字，导致在PDF阅读器中无法连续选择或识别。
                        if (horizontalScaling > 130.0f && N > 1 && gap > 0.15f * fontSize) {
                            if (sumWidths > 0) {
                                float currentX = x;
                                for (int charIdx = 0; charIdx < N; charIdx++) {
                                    String charStr = String.valueOf(filteredText.charAt(charIdx));
                                    // 过滤并跳过空格的物理绘制（不输出空格的 TextObject），
                                    // 以消除在PDF阅读器中选择文字时在空格位置出现细线等选择框异常。
                                    if (!charStr.trim().isEmpty()) {
                                        contentStream.beginText();
                                        contentStream.setFont(font, fontSize);
                                        contentStream.setHorizontalScaling(100.0f);
                                        contentStream.setRenderingMode(RenderingMode.NEITHER);
                                        contentStream.newLineAtOffset(currentX, y);
                                        contentStream.showText(charStr);
                                        contentStream.endText();
                                    }
                                    currentX += charWidths[charIdx] + gap;
                                }
                            }
                        } else {
                            horizontalScaling = Math.max(10.0f, Math.min(horizontalScaling, 1000.0f));
                            contentStream.beginText();
                            contentStream.setFont(font, fontSize);
                            contentStream.setHorizontalScaling(horizontalScaling);
                            contentStream.setRenderingMode(RenderingMode.NEITHER);
                            contentStream.newLineAtOffset(x, y);
                            if (i < 2) {
                                log.debug("页{} 文本块{}: text='{}', bbox=[{},{},{},{}], fontSize={}, ascent={}, descent={}, y={}, textWidth={}, boxWidth={}, hScale={}",
                                        pageIndex + 1, i, text.substring(0, Math.min(10, text.length())),
                                        bbox[0], bbox[1], bbox[2], bbox[3],
                                        String.format("%.2f", fontSize),
                                        String.format("%.2f", fontAscent),
                                        String.format("%.2f", fontDescent),
                                        String.format("%.2f", y),
                                        String.format("%.2f", textWidth),
                                        String.format("%.2f", actualTextWidth),
                                        String.format("%.2f", horizontalScaling));
                            }
                            contentStream.showText(filteredText);
                            contentStream.endText();
                        }
                    }
                }
            }
        } else {
            log.warn("第 {} 页没有可用的OCR文本块，将仅写入图片层", pageIndex + 1);
        }

        PDImageXObject pdImage = PDImageXObject.createFromFile(imagePath, document);
        try (PDPageContentStream contentStream = new PDPageContentStream(
                document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
            contentStream.drawImage(pdImage, 0, 0, pdfUnitWidth, pdfUnitHeight);
        }
    }

    /**
     * 过滤字体不支持的字符
     * 逐个检查字符，只保留字体能够编码的字符
     *
     * 【缓存优化】使用字体级别的支持字符缓存，避免重复编码测试
     *
     * @param font 字体对象
     * @param text 原始文本
     * @return 过滤后的文本
     */
    private static String filterUnsupportedChars(PDFont font, String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        // 如果是标准字体，跳过过滤检查（提高性能）
        if (font instanceof PDType1Font) {
            return text; // 标准字体通常支持基本的字符集
        }

        String fontName = font.getName();
        Set<Character> supportedChars = null;

        // 获取缓存的字体支持字符集合
        if (fontSupportedCharsCache != null) {
            supportedChars = fontSupportedCharsCache.get(fontName);
        }

        // 如果缓存中没有该字体，需要检测当前文本中的所有字符
        if (supportedChars == null) {
            synchronized (cacheLock) {
                // 双重检查锁
                if (fontSupportedCharsCache != null) {
                    supportedChars = fontSupportedCharsCache.get(fontName);
                }

                if (supportedChars == null) {
                    // 首次检测：构建该字体的支持字符缓存
                    Set<Character> newCache = new java.util.HashSet<>();
                    int newSupported = 0;

                    for (int i = 0; i < text.length(); i++) {
                        char c = text.charAt(i);
                        // 跳过已缓存的字符
                        if (newCache.contains(c)) {
                            continue;
                        }
                        String ch = String.valueOf(c);
                        try {
                            font.encode(ch);
                            newCache.add(c);
                            newSupported++;
                        } catch (IllegalArgumentException | IOException e) {
                            // 字体不支持该字符，不加入缓存
                            if (log.isDebugEnabled() && newCache.size() < 5) {
                                log.debug("字体 {} 不支持字符: U+{:04X}", fontName, (int) c);
                            }
                        }
                    }

                    // 初始化缓存（如果尚未初始化）
                    if (fontSupportedCharsCache == null) {
                        fontSupportedCharsCache = new ConcurrentHashMap<>();
                    }
                    // 将新检测的字符集合存入缓存
                    fontSupportedCharsCache.put(fontName, newCache);
                    supportedChars = newCache;

                    log.debug("字体 {} 首次缓存：检测 {} 个字符，支持 {} 个",
                            fontName, text.length(), newSupported);
                }
            }
        }

        // 使用缓存快速过滤文本
        StringBuilder result = new StringBuilder();
        int skippedCount = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // 检查字符是否在缓存的支持集合中
            if (supportedChars.contains(c)) {
                result.append(c);
            } else {
                skippedCount++;
                // 如果缓存中没有该字符，可能是之前没检测过的字符，需要实时检查
                String ch = String.valueOf(c);
                try {
                    font.encode(ch);
                    // 动态添加到缓存
                    supportedChars.add(c);
                    result.append(c);
                } catch (IllegalArgumentException | IOException e) {
                    // 确实不支持
                    if (skippedCount <= 3) {
                        log.debug("字体 {} 不支持字符: U+{:04X}", fontName, (int) c);
                    }
                }
            }
        }

        if (skippedCount > 3) {
            log.debug("字体 {} 跳过了 {} 个不支持的字符", fontName, skippedCount);
        }
        // 【优化#6】改进字体过滤的fallback逻辑，避免编码异常
        if (result.length() == 0 && text.length() > 0) {
            // 所有字符都不被支持的情况
            log.warn("字体 {} 不支持任何字符，使用替代字符避免编码异常", fontName);
            // 返回空字符串而不是原文，避免后续PDF生成时抛出编码异常
            // 调用方应该检查空字符串并采取适当措施（如跳过该文本块或使用占位符）
            return "";
        }

        // 如果大部分字符被过滤（超过50%），记录警告
        if (skippedCount > text.length() / 2) {
            log.warn("字体 {} 不支持大部分字符 ({}/{} 被过滤)，文本可能显示不完整",
                    fontName, skippedCount, text.length());
        }

        return result.toString();
    }

    /**
     * 加载中文字体（跨平台支持）
     * 按优先级尝试从项目资源、系统常见位置加载中文字体
     *
     * @param document PDF文档对象
     * @return 加载的字体，如果都失败则返回null
     */
    private static PDFont loadChineseFont(PDDocument document) {
        // 优先级1: 从项目 resources/fonts 目录加载（打包在JAR内）
        // 注意：simsunb.ttf 内部引用了扩展字体，会导致 SimSun-ExtB 错误，因此放在后面
        String[] resourceFonts = {
                "fonts/simsun.ttc",        // 宋体常规（首选，最稳定）
                "fonts/simhei.ttf",        // 黑体
                "fonts/simsunb.ttf",       // 宋体粗体（可能有问题，放在最后）
                "fonts/NotoSansCJKsc-Regular.otf"  // Noto Sans CJK
        };

        // 尝试从 classpath 加载字体
        for (String fontPath : resourceFonts) {
            try {
                InputStream fontStream = Thread.currentThread()
                        .getContextClassLoader()
                        .getResourceAsStream(fontPath);
                if (fontStream != null) {
                    log.info("从项目资源加载中文字体: {}", fontPath);
                    PDFont font = PDType0Font.load(document, fontStream);
                    fontStream.close();
                    return font;
                }
            } catch (Exception e) {
                log.warn("从项目资源加载字体失败: {}, 原因: {}", fontPath, e.getMessage());
            }
        }

        // 优先级2: 从外部 fonts/ 目录加载（部署时放置）
        String[] externalFonts = {
                "fonts/simsun.ttc",
                "fonts/simhei.ttf",
                "fonts/simsunb.ttf",
                "./fonts/simsun.ttc",
                "./fonts/simhei.ttf"
        };

        for (String fontPath : externalFonts) {
            File fontFile = new File(fontPath);
            if (fontFile.exists()) {
                try {
                    log.info("从外部目录加载中文字体: {}", fontPath);
                    return PDType0Font.load(document, fontFile);
                } catch (Exception e) {
                    log.warn("外部字体加载失败: {}, 原因: {}", fontPath, e.getMessage());
                }
            }
        }

        // 优先级3: 系统字体路径
        String[] systemFontPaths = {
                // Windows
                "C:/Windows/Fonts/simhei.ttf",
                "C:/Windows/Fonts/simsun.ttc",
                // Linux 常见中文字体
                "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
                "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
                "/usr/share/fonts/opentype/noto/NotoSansCJKsc-Regular.otf",
                "/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf",
                "/usr/share/fonts/truetype/arphic/uming.ttc",
                // Mac
                "/System/Library/Fonts/PingFang.ttc",
                "/System/Library/Fonts/STHeiti Light.ttc",
                "/Library/Fonts/Arial Unicode.ttf"
        };

        for (String fontPath : systemFontPaths) {
            File fontFile = new File(fontPath);
            if (fontFile.exists()) {
                try {
                    log.info("从系统路径加载中文字体: {}", fontPath);
                    return PDType0Font.load(document, fontFile);
                } catch (Exception e) {
                    log.debug("系统字体加载失败: {}, 原因: {}", fontPath, e.getMessage());
                }
            } else {
                log.debug("系统字体文件不存在: {}", fontPath);
            }
        }

        log.error("无法找到任何可用的中文字体");
        return null;
    }

    /**
     * 解析边界框（四个坐标点）
     */
    private static int[] parseBoundingBox(com.fasterxml.jackson.databind.JsonNode regionNode) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (int i = 0; i < regionNode.size(); i++) {
            com.fasterxml.jackson.databind.JsonNode point = regionNode.get(i);
            int x = point.get(0).asInt();
            int y = point.get(1).asInt();

            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }

        return new int[]{minX, minY, maxX, maxY};
    }

    /**
     * 从图片和hOCR创建新PDF（带文本层）
     */
    private static void createPdfFromImageAndHocr(String imagePath, List<TextBlock> textBlocks, String outputPath) throws Exception {
        log.info("从图片创建PDF: {}", imagePath);

        // 加载图片
        BufferedImage bufferedImage = ImageIO.read(new File(imagePath));
        float imageWidth = bufferedImage.getWidth();
        float imageHeight = bufferedImage.getHeight();

        log.info("图片尺寸: {} x {}", imageWidth, imageHeight);

        // 计算正确的PDF页面尺寸
        // 统一使用150 DPI
        float imageDpi = 150.0f;
        float pdfUnitWidth = imageWidth / imageDpi * 72.0f;
        float pdfUnitHeight = imageHeight / imageDpi * 72.0f;

        // 创建新PDF
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(pdfUnitWidth, pdfUnitHeight));
            document.addPage(page);

            // 计算缩放比例
            float scaleX = pdfUnitWidth / imageWidth;
            float scaleY = pdfUnitHeight / imageHeight;

            // 先添加图片作为背景
            // 注意：这里简化处理，直接创建空白PDF并添加文本层
            // 完整实现需要将图片绘制到PDF上作为背景层

            // 添加文本层
            PDFont font = PDType1Font.HELVETICA;

            try (PDPageContentStream contentStream = new PDPageContentStream(
                    document,
                    page,
                    PDPageContentStream.AppendMode.APPEND,
                    true,
                    true)) {

                contentStream.setFont(font, 12);
                // 设置文本为白色（不可见）
                contentStream.setNonStrokingColor(1, 1, 1);

                // 添加每个文本块
                for (TextBlock block : textBlocks) {
                    for (TextLine line : block.lines) {
                        for (TextWord word : line.words) {
                            // 转换坐标系并缩放到PDF单位
                            float x = word.x * scaleX;
                            float y = pdfUnitHeight - (word.y * scaleY) - (word.height * scaleY);

                            log.debug("添加文本: '{}' at ({}, {})", word.text, x, y);

                            // 添加不可见的文本
                            contentStream.beginText();
                            contentStream.newLineAtOffset(x, y);
                            contentStream.showText(word.text);
                            contentStream.endText();
                        }
                    }
                }
            }

            // 保存PDF
            document.save(outputPath);
            log.info("从图片创建的PDF已保存: {}", outputPath);
        }
    }

    /**
     * 在现有PDF上添加文本层
     */
    private static void addTextLayerToPdf(String inputPdfPath, List<TextBlock> textBlocks, String outputPath) throws Exception {
        // 加载PDF
        try (PDDocument document = PDDocument.load(new File(inputPdfPath))) {
            PDPage page = document.getPage(0);
            PDRectangle pageSize = page.getMediaBox();
            float pageHeight = pageSize.getHeight();

            log.info("PDF页面尺寸: {} x {}", pageSize.getWidth(), pageHeight);

            // 创建内容流添加文本
            PDFont font = PDType1Font.HELVETICA;

            try (PDPageContentStream contentStream = new PDPageContentStream(
                    document,
                    page,
                    PDPageContentStream.AppendMode.APPEND,
                    true,
                    true)) {

                contentStream.setFont(font, 12);
                // 设置文本为透明（白色，完全不透明但与背景同色）
                contentStream.setNonStrokingColor(1, 1, 1);

                // 添加每个文本块
                for (TextBlock block : textBlocks) {
                    for (TextLine line : block.lines) {
                        for (TextWord word : line.words) {
                            // 转换坐标系：hOCR使用左上角为原点，PDF使用左下角
                            float x = word.x;
                            float y = pageHeight - word.y - word.height;

                            // 添加不可见的文本
                            contentStream.beginText();
                            contentStream.newLineAtOffset(x, y);
                            contentStream.showText(word.text);
                            contentStream.endText();
                        }
                    }
                }
            }

            // 保存PDF
            document.save(outputPath);
            log.info("双层PDF生成成功: {}", outputPath);
        }
    }

    /**
     * 从hOCR文档中提取文本块
     */
    private static List<TextBlock> extractTextBlocks(Document doc) {
        List<TextBlock> blocks = new ArrayList<>();

        // 查找所有ocr_line元素
        NodeList lines = doc.getElementsByTagName("span");
        for (int i = 0; i < lines.getLength(); i++) {
            Element lineElement = (Element) lines.item(i);
            String className = lineElement.getAttribute("class");

            if ("ocr_line".equals(className)) {
                TextLine line = extractTextLine(lineElement);
                if (line != null && !line.words.isEmpty()) {
                    TextBlock block = new TextBlock();
                    block.lines.add(line);
                    blocks.add(block);
                }
            }
        }

        return blocks;
    }

    /**
     * 从ocr_line元素中提取文本行
     */
    private static TextLine extractTextLine(Element lineElement) {
        TextLine line = new TextLine();

        // 获取title属性中的bbox信息
        String title = lineElement.getAttribute("title");
        float[] bbox = parseBbox(title);

        // 查找所有ocrx_word元素
        NodeList words = lineElement.getElementsByTagName("span");
        for (int i = 0; i < words.getLength(); i++) {
            Element wordElement = (Element) words.item(i);
            String wordClass = wordElement.getAttribute("class");

            if ("ocrx_word".equals(wordClass)) {
                TextWord word = extractTextWord(wordElement);
                if (word != null) {
                    line.words.add(word);
                }
            }
        }

        return line.words.isEmpty() ? null : line;
    }

    /**
     * 从ocrx_word元素中提取单词
     */
    private static TextWord extractTextWord(Element wordElement) {
        String text = wordElement.getTextContent().trim();
        if (text.isEmpty()) {
            return null;
        }

        TextWord word = new TextWord();
        word.text = text;

        // 解析bbox
        String title = wordElement.getAttribute("title");
        float[] bbox = parseBbox(title);

        if (bbox != null) {
            word.x = bbox[0];
            word.y = bbox[1];
            word.width = bbox[2] - bbox[0];
            word.height = bbox[3] - bbox[1];
        }

        return word;
    }

    /**
     * 解析bbox字符串 "bbox x0 y0 x1 y1"
     */
    private static float[] parseBbox(String title) {
        if (title == null || !title.contains("bbox")) {
            return null;
        }

        try {
            int bboxIndex = title.indexOf("bbox");
            String bboxStr = title.substring(bboxIndex + 5).trim();
            String[] parts = bboxStr.split("\\s+");

            if (parts.length >= 4) {
                float[] bbox = new float[4];
                bbox[0] = Float.parseFloat(parts[0]); // x0
                bbox[1] = Float.parseFloat(parts[1]); // y0
                bbox[2] = Float.parseFloat(parts[2]); // x1
                bbox[3] = Float.parseFloat(parts[3]); // y1
                return bbox;
            }
        } catch (Exception e) {
            log.debug("解析bbox失败: {}", title, e);
        }

        return null;
    }

    /**
     * 文本块
     */
    private static class TextBlock {
        List<TextLine> lines = new ArrayList<>();
    }

    /**
     * 文本行
     */
    private static class TextLine {
        List<TextWord> words = new ArrayList<>();
    }

    /**
     * 单词
     */
    private static class TextWord {
        String text;
        float x;
        float y;
        float width;
        float height;
    }

    private static boolean isVerticalText(float width, float height, String text) {
        if (text == null || text.isEmpty() || width <= 0f || height <= 0f) {
            return false;
        }

        // 1. 第一层：排除数字和英文 (以及主要由数字和英文组成的内容)
        String clean = text.replaceAll("\\s+", "");
        if (clean.isEmpty()) {
            return false;
        }
        int alphaNumCount = 0;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if ((c >= '0' && c <= '9') || 
                (c >= 'a' && c <= 'z') || 
                (c >= 'A' && c <= 'Z') || 
                c == '.' || c == '-' || c == '/' || c == ':' || c == ',' || 
                c == '(' || c == ')' || c == '[' || c == ']' || c == '#' || c == '%') {
                alphaNumCount++;
            }
        }
        double alphaNumRatio = (double) alphaNumCount / clean.length();
        if (alphaNumRatio >= 0.7) {
            return false; // 主要由英数字组成，按横排处理
        }

        // 2. 第二层和第三层：根据字符数和宽高比判定
        float ratio = height / width;
        int len = clean.length();

        if (len == 1) {
            return ratio > 2.0f;
        } else if (len >= 2 && len <= 3) {
            return ratio > 2.5f;
        } else {
            // 【优化#5】修复长竖排文本识别阈值，避免阈值过高导致无法识别
            // len >= 4：使用更合理的阈值计算公式
            // 基础阈值2.0，每增加1个字符增加0.15，上限2.8
            // 这样4字符阈值为2.6，10字符阈值为2.8（更宽松）
            float dynamicThreshold = Math.min(2.0f + len * 0.15f, 2.8f);
            return ratio > dynamicThreshold;
        }
    }
}
