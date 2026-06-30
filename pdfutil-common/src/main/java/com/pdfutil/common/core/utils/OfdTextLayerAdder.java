package com.pdfutil.common.core.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ofdrw.font.Font;
import org.ofdrw.layout.OFDDoc;
import org.ofdrw.layout.VirtualPage;
import org.ofdrw.layout.element.Position;
import org.ofdrw.layout.element.canvas.Canvas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;

/**
 * OFD 隐形文字层叠加器 - 使用 OFDRW 库
 *
 * @author Alika
 * @date 2026-06-09
 */
public class OfdTextLayerAdder {
    private static final Logger log = LoggerFactory.getLogger(OfdTextLayerAdder.class);

      static {
        try {
            javax.imageio.ImageIO.scanForPlugins();
        } catch (Throwable e) {
            log.warn("Failed to scan ImageIO plugins in OfdTextLayerAdder: {}", e.getMessage());
        }
    }

    /**
     * 从多张图片创建单层 OFD（无文字层）
     *
     * @param imagePaths   扫描图片路径数组
     * @param outputPath   单层 OFD 输出路径
     * @param sourceDpi    拆图所使用的 DPI 精度
     */
    public static void createSingleLayerOfdFromImages(String[] imagePaths, String outputPath, float sourceDpi) throws Exception {
        log.info("从图片创建单层OFD: pages={}, sourceDpi={}", imagePaths.length, sourceDpi);

        int pageCount = imagePaths.length;
        if (pageCount <= 0) {
            throw new IllegalArgumentException("没有可用于生成OFD的页面数据");
        }

        try (OFDDoc ofdDoc = new OFDDoc(Paths.get(outputPath))) {
            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                String imgPath = imagePaths[pageIndex];

                // 1. 获取图片尺寸
                java.awt.image.BufferedImage bufImg = null;
                try {
                    bufImg = javax.imageio.ImageIO.read(new File(imgPath));
                } catch (Exception e) {
                    throw new RuntimeException("无法读取图片: " + imgPath + "。错误: " + e.getMessage() + "。详细原因: " + getDetailedImageErrorInfo(new File(imgPath)), e);
                }
                if (bufImg == null) {
                    throw new RuntimeException("无法读取图片: " + imgPath + "。该图片格式不被支持，或已损坏。详细原因: " + getDetailedImageErrorInfo(new File(imgPath)));
                }
                int imgW = bufImg.getWidth();
                int imgH = bufImg.getHeight();

                // 2. 将像素尺寸转换到毫米 (mm)
                double pageW_mm = (imgW * 25.4) / sourceDpi;
                double pageH_mm = (imgH * 25.4) / sourceDpi;

                log.info("处理第 {} 页 OFD: 图片={}, 尺寸={}x{} 像素, OFD物理尺寸={}x{} 毫米",
                        pageIndex + 1, imgPath, imgW, imgH, pageW_mm, pageH_mm);

                // 创建虚拟页面，尺寸与底图匹配
                VirtualPage vPage = new VirtualPage(pageW_mm, pageH_mm);

                // 创建覆盖整页的 Canvas 进行底图绘制（无文字层）
                Canvas canvas = new Canvas(pageW_mm, pageH_mm);
                canvas.setPosition(Position.Absolute).setX(0d).setY(0d);
                canvas.setDrawer(ctx -> {
                    try {
                        // 绘制背景图片，原样绘制占满整页
                        ctx.drawImage(Paths.get(imgPath), 0, 0, pageW_mm, pageH_mm);
                    } catch (IOException e) {
                        throw new RuntimeException("Canvas 绘制失败: " + e.getMessage(), e);
                    } catch (Exception e) {
                        throw new RuntimeException("Canvas 绘制过程中发生错误: " + e.getMessage(), e);
                    }
                });

                vPage.add(canvas);
                ofdDoc.addVPage(vPage);
            }
        }

        log.info("单层OFD创建成功: {}", outputPath);
    }

    /**
     * 从多张图片与 OCR 结果创建双层 OFD（每页使用对应的 DPI）
     *
     * @param imagePaths   扫描图片路径数组
     * @param ocrResult    OCR 识别 JSON 字符串
     * @param outputPath   双层 OFD 输出路径
     * @param sourceDpis  每页对应的 DPI 数组（长度必须与 imagePaths 一致）
     */
    public static void createDualLayerOfdFromOcr(String[] imagePaths, String ocrResult, String outputPath, float[] sourceDpis) throws Exception {
        log.info("从图片和OCR结果创建多页双层OFD: pages={}, 使用每页独立DPI", imagePaths.length);

        if (imagePaths.length != sourceDpis.length) {
            throw new IllegalArgumentException("图片路径数量与 DPI 数组长度不一致: " + imagePaths.length + " vs " + sourceDpis.length);
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(ocrResult);
        JsonNode pages = root.get("pages");

        int pageCount = imagePaths.length;
        if (pageCount <= 0) {
            throw new IllegalArgumentException("没有可用于生成OFD的页面数据");
        }

        try (OFDDoc ofdDoc = new OFDDoc(Paths.get(outputPath))) {
            // 设置中文宋体
            Font font = new Font("SimSun", "宋体");

            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                String imgPath = imagePaths[pageIndex];
                float rawDpi = sourceDpis[pageIndex];
                final float pageDpi = rawDpi <= 0 ? 200.0f : rawDpi;
                JsonNode pageData = (pages != null && pages.isArray() && pageIndex < pages.size()) ? pages.get(pageIndex) : null;

                // 1. 获取图片尺寸
                java.awt.image.BufferedImage bufImg = null;
                try {
                    bufImg = javax.imageio.ImageIO.read(new File(imgPath));
                } catch (Exception e) {
                    throw new RuntimeException("无法读取图片: " + imgPath + "。错误: " + e.getMessage() + "。详细原因: " + getDetailedImageErrorInfo(new File(imgPath)), e);
                }
                if (bufImg == null) {
                    throw new RuntimeException("无法读取图片: " + imgPath + "。该图片格式不被支持，或已损坏。详细原因: " + getDetailedImageErrorInfo(new File(imgPath)));
                }
                int imgW = bufImg.getWidth();
                int imgH = bufImg.getHeight();

                // 2. 将像素尺寸转换到毫米 (mm) - 使用当前页的 DPI
                double pageW_mm = (imgW * 25.4) / pageDpi;
                double pageH_mm = (imgH * 25.4) / pageDpi;

                log.info("处理第 {} 页 OFD: 图片={}, 尺寸={}x{} 像素, DPI={}, OFD物理尺寸={}x{} 毫米",
                        pageIndex + 1, imgPath, imgW, imgH, pageDpi, pageW_mm, pageH_mm);

                // 创建虚拟页面，尺寸与底图匹配
                VirtualPage vPage = new VirtualPage(pageW_mm, pageH_mm);

                // 创建覆盖整页的 Canvas 进行底图与透明文字层绘制
                Canvas canvas = new Canvas(pageW_mm, pageH_mm);
                canvas.setPosition(Position.Absolute).setX(0d).setY(0d);
                canvas.setDrawer(ctx -> {
                    try {
                        // A. 绘制背景图片，原样绘制占满整页
                        ctx.drawImage(Paths.get(imgPath), 0, 0, pageW_mm, pageH_mm);

                        // B. 绘制透明文字层 - 使用当前页的 DPI 进行坐标转换
                        ctx.fillStyle = "rgba(0, 0, 0, 0)"; // 填充颜色设置为完全透明

                        if (pageData != null && pageData.isArray()) {
                            for (JsonNode item : pageData) {
                                if (item == null || item.get("text") == null) {
                                    continue;
                                }
                                String text = item.get("text").asText();
                                if (text == null || text.trim().isEmpty()) {
                                    continue;
                                }
                                JsonNode regionNode = item.get("text_region");
                                if (regionNode == null || !regionNode.isArray() || regionNode.size() < 4) {
                                    continue;
                                }

                                // 解析边界框 (四个端点计算 min/max)
                                int[] bbox = parseBoundingBox(regionNode);
                                int boxX = bbox[0];
                                int boxY = bbox[1];
                                int boxW = bbox[2] - bbox[0];
                                int boxH = bbox[3] - bbox[1];

                                // 转换像素单位为毫米 (mm) - 使用当前页的 DPI
                                double x_mm = (boxX * 25.4) / pageDpi;
                                double y_mm = (boxY * 25.4) / pageDpi;
                                double w_mm = (boxW * 25.4) / pageDpi;
                                double h_mm = (boxH * 25.4) / pageDpi;

                                if (w_mm <= 0 || h_mm <= 0) {
                                    continue;
                                }

                                // 使用与双层PDF相同的字号计算算法
                                // 宋体的标准字体度量（ascent=1360, descent=280, 单位为/1000em）
                                float ascent = 1360f;
                                float descent = 280f;
                                float normalizedFontHeight = (ascent - descent) / 1000f;

                                boolean isVertical = isVerticalText(w_mm, h_mm, text);
                                if (isVertical) {
                                    int N = text.length();
                                    if (N == 1) {
                                        // 单个竖版字符：基于高度计算字号（竖版字符的高度是主要尺寸）
                                        double charFontSize_mm = h_mm / normalizedFontHeight;
                                        charFontSize_mm = Math.min(charFontSize_mm, h_mm * 1.5);
                                        charFontSize_mm = Math.min(charFontSize_mm, w_mm * 1.5);
                                        double charFontSize_pt = charFontSize_mm * 2.8346;
                                        double charFontDescent_mm = Math.abs(descent / 1000f) * charFontSize_mm;

                                        ctx.font = String.format(java.util.Locale.US, "%.2fpt 宋体", charFontSize_pt);

                                        double charTextWidth_mm = 0.1;
                                        try {
                                            charTextWidth_mm = ctx.measureText(text).width;
                                        } catch (Exception e) {
                                            charTextWidth_mm = charFontSize_mm;
                                        }
                                        if (charTextWidth_mm <= 0) {
                                            charTextWidth_mm = 0.1;
                                        }

                                        // 单个竖版字符垂直与水平双向居中对齐
                                        double scaleX = Math.min(1.5, w_mm / charTextWidth_mm); // 允许适度拉伸以填满框宽
                                        double cy = y_mm + (h_mm + charFontSize_mm) / 2.0 - charFontDescent_mm - (charFontSize_mm * 0.05);
                                        double cx = x_mm + (w_mm - charTextWidth_mm * scaleX) / 2.0;

                                        ctx.save();
                                        ctx.translate(cx, cy);
                                        ctx.scale(scaleX, 1.0);
                                        ctx.fillText(text, 0, 0);
                                        ctx.restore();
                                    } else {
                                        // 多字符竖版文字：逐个字符绘制
                                        // 统一处理逻辑：保留空格字符但不绘制，避免数据不一致
                                        int totalChars = text.length();

                                        // 计算有效字符数（排除空格），用于槽高计算
                                        int effectiveCharCount = 0;
                                        for (int charIdx = 0; charIdx < totalChars; charIdx++) {
                                            if (!text.substring(charIdx, charIdx + 1).trim().isEmpty()) {
                                                effectiveCharCount++;
                                            }
                                        }

                                        if (effectiveCharCount == 0) continue;

                                        double charH_mm = h_mm / effectiveCharCount;
                                        // 字体大小主要由单字高度和宽度决定，使其尽量居中填充
                                        double charFontSize_mm = charH_mm / normalizedFontHeight;
                                        charFontSize_mm = Math.min(charFontSize_mm, charH_mm * 1.5);
                                        charFontSize_mm = Math.min(charFontSize_mm, w_mm * 1.5);
                                        double charFontSize_pt = charFontSize_mm * 2.8346;

                                        double charFontDescent_mm = Math.abs(descent / 1000f) * charFontSize_mm;

                                        // 遍历原始文本，保持字符索引一致性
                                        int effectiveIndex = 0;  // 有效字符索引（用于位置计算）
                                        for (int charIdx = 0; charIdx < totalChars; charIdx++) {
                                            String charStr = String.valueOf(text.charAt(charIdx));

                                            // 跳过空格字符（不绘制但保留位置）
                                            if (charStr.trim().isEmpty()) {
                                                continue;
                                            }

                                            double charY_mm = y_mm + effectiveIndex * charH_mm;

                                            ctx.font = String.format(java.util.Locale.US, "%.2fpt 宋体", charFontSize_pt);

                                            double charTextWidth_mm = 0.1;
                                            try {
                                                charTextWidth_mm = ctx.measureText(charStr).width;
                                            } catch (Exception e) {
                                                charTextWidth_mm = charFontSize_mm;
                                            }
                                            if (charTextWidth_mm <= 0) {
                                                charTextWidth_mm = 0.1;
                                            }

                                            double scaleX = Math.min(1.5, w_mm / charTextWidth_mm); // 允许适度拉伸以填满框宽
                                            double scaleY = 1.0;

                                            // 垂直与水平双向居中对齐当前字符的位置
                                            double cy = charY_mm + (charH_mm + charFontSize_mm) / 2.0 - charFontDescent_mm - (charFontSize_mm * 0.05);
                                            double cx = x_mm + (w_mm - charTextWidth_mm * scaleX) / 2.0;

                                            ctx.save();
                                            ctx.translate(cx, cy);
                                            ctx.scale(scaleX, scaleY);
                                            ctx.fillText(charStr, 0, 0);
                                            ctx.restore();

                                            effectiveIndex++;  // 只绘制了有效字符后才递增索引
                                        }
                                    }
                                } else {
                                    // 计算字号（基于包围框高度和字体度量）
                                    double fontSize_mm = h_mm / normalizedFontHeight;
                                    // 限制最大字号，避免异常包围框导致溢出
                                    fontSize_mm = Math.min(fontSize_mm, h_mm * 1.5);

                                    // 将毫米转换为点 (1mm ≈ 2.8346pt)
                                    double fontSize_pt = fontSize_mm * 2.8346;

                                    // 动态设置当前文字的实际字号，保证生成的 OFD 文本对象 Size 属性与实际大小一致
                                    ctx.font = String.format(java.util.Locale.US, "%.2fpt 宋体", fontSize_pt);

                                    // 计算字体的升部和降部（毫米单位）
                                    double fontDescent_mm = Math.abs(descent / 1000f) * fontSize_mm;

                                    // 测量当前字号下的文本物理宽度（毫米单位）
                                    double textWidth_mm = 0.1;
                                    try {
                                        textWidth_mm = ctx.measureText(text).width;
                                    } catch (Exception e) {
                                        // 降级计算：估算宽度
                                        textWidth_mm = text.length() * (fontSize_pt / 2.8346);
                                    }
                                    if (textWidth_mm <= 0) {
                                        textWidth_mm = 0.1;
                                    }

                                    // 计算缩放比例
                                    // Y 轴缩放：由于直接使用了 fontSize_pt 设定字号，无需 Y 轴缩放，设为 1.0
                                    double scaleY = 1.0;
                                    // X 轴缩放：将当前字号下文本的物理宽度拉伸到 OCR 识别框宽度 w_mm
                                    double scaleX = w_mm / textWidth_mm;

                                    int N = text.length();

                                    // 先计算如果均匀分布的话，每个字之间的间距 gap
                                    double gap = 0;
                                    double sumWidths = 0;
                                    double[] charWidths = new double[N];
                                    if (N > 1) {
                                        for (int charIdx = 0; charIdx < N; charIdx++) {
                                            String charStr = String.valueOf(text.charAt(charIdx));
                                            if (charStr.trim().isEmpty()) {
                                                // 优化空格的宽度估算，避免使用高宽度的默认回退值导致间距计算错误
                                                charWidths[charIdx] = charStr.equals("　") ? fontSize_mm : fontSize_mm * 0.3;
                                            } else {
                                                try {
                                                    charWidths[charIdx] = ctx.measureText(charStr).width;
                                                } catch (Exception e) {
                                                    charWidths[charIdx] = fontSize_mm; // 回退值
                                                }
                                            }
                                            sumWidths += charWidths[charIdx];
                                        }
                                        if (sumWidths > 0) {
                                            gap = (w_mm - sumWidths) / (N - 1);
                                        }
                                    }

                                    // 只有当拉伸比例过大且计算出的字间距较大（大于字号的15%）时，才逐字对齐绘制。
                                    // 避免普通段落被拆成单字，导致在OFD阅读器中无法连续选择或识别。
                                    if (scaleX > 1.30 && N > 1 && gap > 0.15 * fontSize_mm) {
                                        if (sumWidths > 0) {
                                            double currentX = x_mm;
                                            for (int charIdx = 0; charIdx < N; charIdx++) {
                                                String charStr = String.valueOf(text.charAt(charIdx));
                                                // 精确的垂直与水平对齐
                                                double cy = y_mm + h_mm - fontDescent_mm - (fontSize_mm * 0.05);

                                                // 过滤并跳过空格的物理绘制（不输出空格的 TextObject），
                                                // 以消除在OFD阅读器中选择文字时在空格位置出现细线或错位等选择框异常。
                                                if (!charStr.trim().isEmpty()) {
                                                    ctx.save();
                                                    ctx.translate(currentX, cy);
                                                    ctx.scale(1.0, 1.0); // 逐字绘制时保持正常比例
                                                    ctx.fillText(charStr, 0, 0);
                                                    ctx.restore();
                                                }

                                                currentX += charWidths[charIdx] + gap;
                                            }
                                        }
                                    } else {
                                        // 精确的垂直与水平对齐
                                        // 因为 OFD Y轴向下增加，所以向上偏移需要用减法
                                        double y = y_mm + h_mm - fontDescent_mm - (fontSize_mm * 0.05);
                                        double x = x_mm;

                                        // 绘制文字，应用双轴缩放以精确匹配包围框宽度与高度
                                        ctx.save();
                                        ctx.translate(x, y);
                                        ctx.scale(scaleX, scaleY);
                                        ctx.fillText(text, 0, 0);
                                        ctx.restore();
                                    }
                                }
                            }
                        }
                    } catch (IOException e) {
                        throw new RuntimeException("Canvas 绘制失败: " + e.getMessage(), e);
                    } catch (Exception e) {
                        throw new RuntimeException("Canvas 绘制过程中发生错误: " + e.getMessage(), e);
                    }
                });

                vPage.add(canvas);
                ofdDoc.addVPage(vPage);
            }
        }
    }

    /**
     * 从多张图片与 OCR 结果创建双层 OFD
     *
     * @param imagePaths   扫描图片路径数组
     * @param ocrResult    OCR 识别 JSON 字符串
     * @param outputPath   双层 OFD 输出路径
     * @param sourceDpi    拆图/OCR 所使用的 DPI 精度
     */
    public static void createDualLayerOfdFromOcr(String[] imagePaths, String ocrResult, String outputPath, float sourceDpi) throws Exception {
        final float finalSourceDpi = sourceDpi <= 0 ? 200.0f : sourceDpi;
        log.info("从图片和OCR结果创建多页双层OFD: pages={}, sourceDpi={}", imagePaths.length, finalSourceDpi);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(ocrResult);
        JsonNode pages = root.get("pages");

        int pageCount = imagePaths.length;
        if (pageCount <= 0) {
            throw new IllegalArgumentException("没有可用于生成OFD的页面数据");
        }

        try (OFDDoc ofdDoc = new OFDDoc(Paths.get(outputPath))) {
            // 设置中文宋体
            Font font = new Font("SimSun", "宋体");

            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                String imgPath = imagePaths[pageIndex];
                JsonNode pageData = (pages != null && pages.isArray() && pageIndex < pages.size()) ? pages.get(pageIndex) : null;

                // 1. 获取图片尺寸
                java.awt.image.BufferedImage bufImg = null;
                try {
                    bufImg = javax.imageio.ImageIO.read(new File(imgPath));
                } catch (Exception e) {
                    throw new RuntimeException("无法读取图片: " + imgPath + "。错误: " + e.getMessage() + "。详细原因: " + getDetailedImageErrorInfo(new File(imgPath)), e);
                }
                if (bufImg == null) {
                    throw new RuntimeException("无法读取图片: " + imgPath + "。该图片格式不被支持，或已损坏。详细原因: " + getDetailedImageErrorInfo(new File(imgPath)));
                }
                int imgW = bufImg.getWidth();
                int imgH = bufImg.getHeight();

                // 2. 将像素尺寸转换到毫米 (mm)
                double pageW_mm = (imgW * 25.4) / finalSourceDpi;
                double pageH_mm = (imgH * 25.4) / finalSourceDpi;

                log.info("处理第 {} 页 OFD: 图片={}, 尺寸={}x{} 像素, OFD物理尺寸={}x{} 毫米",
                        pageIndex + 1, imgPath, imgW, imgH, pageW_mm, pageH_mm);

                // 创建虚拟页面，尺寸与底图匹配
                VirtualPage vPage = new VirtualPage(pageW_mm, pageH_mm);

                // 创建覆盖整页的 Canvas 进行底图与透明文字层绘制
                Canvas canvas = new Canvas(pageW_mm, pageH_mm);
                canvas.setPosition(Position.Absolute).setX(0d).setY(0d);
                canvas.setDrawer(ctx -> {
                    try {
                        // A. 绘制背景图片，原样绘制占满整页
                        ctx.drawImage(Paths.get(imgPath), 0, 0, pageW_mm, pageH_mm);

                        // B. 绘制透明文字层
                        ctx.fillStyle = "rgba(0, 0, 0, 0)"; // 填充颜色设置为完全透明

                        if (pageData != null && pageData.isArray()) {
                            for (JsonNode item : pageData) {
                                if (item == null || item.get("text") == null) {
                                    continue;
                                }
                                String text = item.get("text").asText();
                                if (text == null || text.trim().isEmpty()) {
                                    continue;
                                }
                                JsonNode regionNode = item.get("text_region");
                                if (regionNode == null || !regionNode.isArray() || regionNode.size() < 4) {
                                    continue;
                                }

                                // 解析边界框 (四个端点计算 min/max)
                                int[] bbox = parseBoundingBox(regionNode);
                                int boxX = bbox[0];
                                int boxY = bbox[1];
                                int boxW = bbox[2] - bbox[0];
                                int boxH = bbox[3] - bbox[1];

                                // 转换像素单位为毫米 (mm)
                                double x_mm = (boxX * 25.4) / finalSourceDpi;
                                double y_mm = (boxY * 25.4) / finalSourceDpi;
                                double w_mm = (boxW * 25.4) / finalSourceDpi;
                                double h_mm = (boxH * 25.4) / finalSourceDpi;

                                if (w_mm <= 0 || h_mm <= 0) {
                                    continue;
                                }

                                // 使用与双层PDF相同的字号计算算法
                                // 宋体的标准字体度量（ascent=1360, descent=280, 单位为/1000em）
                                float ascent = 1360f;
                                float descent = 280f;
                                float normalizedFontHeight = (ascent - descent) / 1000f;

                                boolean isVertical = isVerticalText(w_mm, h_mm, text);
                                if (isVertical) {
                                    int N = text.length();
                                    if (N == 1) {
                                        // 单个竖版字符：基于高度计算字号（竖版字符的高度是主要尺寸）
                                        double charFontSize_mm = h_mm / normalizedFontHeight;
                                        charFontSize_mm = Math.min(charFontSize_mm, h_mm * 1.5);
                                        charFontSize_mm = Math.min(charFontSize_mm, w_mm * 1.5);
                                        double charFontSize_pt = charFontSize_mm * 2.8346;
                                        double charFontDescent_mm = Math.abs(descent / 1000f) * charFontSize_mm;

                                        ctx.font = String.format(java.util.Locale.US, "%.2fpt 宋体", charFontSize_pt);

                                        double charTextWidth_mm = 0.1;
                                        try {
                                            charTextWidth_mm = ctx.measureText(text).width;
                                        } catch (Exception e) {
                                            charTextWidth_mm = charFontSize_mm;
                                        }
                                        if (charTextWidth_mm <= 0) {
                                            charTextWidth_mm = 0.1;
                                        }

                                        // 单个竖版字符垂直与水平双向居中对齐
                                        double scaleX = Math.min(1.5, w_mm / charTextWidth_mm); // 允许适度拉伸以填满框宽
                                        double cy = y_mm + (h_mm + charFontSize_mm) / 2.0 - charFontDescent_mm - (charFontSize_mm * 0.05);
                                        double cx = x_mm + (w_mm - charTextWidth_mm * scaleX) / 2.0;

                                        ctx.save();
                                        ctx.translate(cx, cy);
                                        ctx.scale(scaleX, 1.0);
                                        ctx.fillText(text, 0, 0);
                                        ctx.restore();
                                    } else {
                                        // 多字符竖版文字：逐个字符绘制
                                        // 统一处理逻辑：保留空格字符但不绘制，避免数据不一致
                                        int totalChars = text.length();

                                        // 计算有效字符数（排除空格），用于槽高计算
                                        int effectiveCharCount = 0;
                                        for (int charIdx = 0; charIdx < totalChars; charIdx++) {
                                            if (!text.substring(charIdx, charIdx + 1).trim().isEmpty()) {
                                                effectiveCharCount++;
                                            }
                                        }

                                        if (effectiveCharCount == 0) continue;

                                        double charH_mm = h_mm / effectiveCharCount;
                                        // 字体大小主要由单字高度和宽度决定，使其尽量居中填充
                                        double charFontSize_mm = charH_mm / normalizedFontHeight;
                                        charFontSize_mm = Math.min(charFontSize_mm, charH_mm * 1.5);
                                        charFontSize_mm = Math.min(charFontSize_mm, w_mm * 1.5);
                                        double charFontSize_pt = charFontSize_mm * 2.8346;

                                        double charFontDescent_mm = Math.abs(descent / 1000f) * charFontSize_mm;

                                        // 遍历原始文本，保持字符索引一致性
                                        int effectiveIndex = 0;  // 有效字符索引（用于位置计算）
                                        for (int charIdx = 0; charIdx < totalChars; charIdx++) {
                                            String charStr = String.valueOf(text.charAt(charIdx));

                                            // 跳过空格字符（不绘制但保留位置）
                                            if (charStr.trim().isEmpty()) {
                                                continue;
                                            }

                                            double charY_mm = y_mm + effectiveIndex * charH_mm;

                                            ctx.font = String.format(java.util.Locale.US, "%.2fpt 宋体", charFontSize_pt);

                                            double charTextWidth_mm = 0.1;
                                            try {
                                                charTextWidth_mm = ctx.measureText(charStr).width;
                                            } catch (Exception e) {
                                                charTextWidth_mm = charFontSize_mm;
                                            }
                                            if (charTextWidth_mm <= 0) {
                                                charTextWidth_mm = 0.1;
                                            }

                                            double scaleX = Math.min(1.5, w_mm / charTextWidth_mm); // 允许适度拉伸以填满框宽
                                            double scaleY = 1.0;

                                            // 垂直与水平双向居中对齐当前字符的位置
                                            double cy = charY_mm + (charH_mm + charFontSize_mm) / 2.0 - charFontDescent_mm - (charFontSize_mm * 0.05);
                                            double cx = x_mm + (w_mm - charTextWidth_mm * scaleX) / 2.0;

                                            ctx.save();
                                            ctx.translate(cx, cy);
                                            ctx.scale(scaleX, scaleY);
                                            ctx.fillText(charStr, 0, 0);
                                            ctx.restore();

                                            effectiveIndex++;  // 只绘制了有效字符后才递增索引
                                        }
                                    }
                                } else {
                                    // 计算字号（基于包围框高度和字体度量）
                                    double fontSize_mm = h_mm / normalizedFontHeight;
                                    // 限制最大字号，避免异常包围框导致溢出
                                    fontSize_mm = Math.min(fontSize_mm, h_mm * 1.5);

                                    // 将毫米转换为点 (1mm ≈ 2.8346pt)
                                    double fontSize_pt = fontSize_mm * 2.8346;

                                    // 动态设置当前文字的实际字号，保证生成的 OFD 文本对象 Size 属性与实际大小一致
                                    ctx.font = String.format(java.util.Locale.US, "%.2fpt 宋体", fontSize_pt);

                                    // 计算字体的升部和降部（毫米单位）
                                    double fontDescent_mm = Math.abs(descent / 1000f) * fontSize_mm;

                                    // 测量当前字号下的文本物理宽度（毫米单位）
                                    double textWidth_mm = 0.1;
                                    try {
                                        textWidth_mm = ctx.measureText(text).width;
                                    } catch (Exception e) {
                                        // 降级计算：估算宽度
                                        textWidth_mm = text.length() * (fontSize_pt / 2.8346);
                                    }
                                    if (textWidth_mm <= 0) {
                                        textWidth_mm = 0.1;
                                    }

                                    // 计算缩放比例
                                    // Y 轴缩放：由于直接使用了 fontSize_pt 设定字号，无需 Y 轴缩放，设为 1.0
                                    double scaleY = 1.0;
                                    // X 轴缩放：将当前字号下文本的物理宽度拉伸到 OCR 识别框宽度 w_mm
                                    double scaleX = w_mm / textWidth_mm;

                                    int N = text.length();
                                    
                                    // 先计算如果均匀分布的话，每个字之间的间距 gap
                                    double gap = 0;
                                    double sumWidths = 0;
                                    double[] charWidths = new double[N];
                                    if (N > 1) {
                                        for (int charIdx = 0; charIdx < N; charIdx++) {
                                            String charStr = String.valueOf(text.charAt(charIdx));
                                            if (charStr.trim().isEmpty()) {
                                                // 优化空格的宽度估算，避免使用高宽度的默认回退值导致间距计算错误
                                                charWidths[charIdx] = charStr.equals("　") ? fontSize_mm : fontSize_mm * 0.3;
                                            } else {
                                                try {
                                                    charWidths[charIdx] = ctx.measureText(charStr).width;
                                                } catch (Exception e) {
                                                    charWidths[charIdx] = fontSize_mm; // 回退值
                                                }
                                            }
                                            sumWidths += charWidths[charIdx];
                                        }
                                        if (sumWidths > 0) {
                                            gap = (w_mm - sumWidths) / (N - 1);
                                        }
                                    }

                                    // 只有当拉伸比例过大且计算出的字间距较大（大于字号的15%）时，才逐字对齐绘制。
                                    // 避免普通段落被拆成单字，导致在OFD阅读器中无法连续选择或识别。
                                    if (scaleX > 1.30 && N > 1 && gap > 0.15 * fontSize_mm) {
                                        if (sumWidths > 0) {
                                            double currentX = x_mm;
                                            for (int charIdx = 0; charIdx < N; charIdx++) {
                                                String charStr = String.valueOf(text.charAt(charIdx));
                                                // 精确的垂直与水平对齐
                                                double cy = y_mm + h_mm - fontDescent_mm - (fontSize_mm * 0.05);

                                                // 过滤并跳过空格的物理绘制（不输出空格的 TextObject），
                                                // 以消除在OFD阅读器中选择文字时在空格位置出现细线或错位等选择框异常。
                                                if (!charStr.trim().isEmpty()) {
                                                    ctx.save();
                                                    ctx.translate(currentX, cy);
                                                    ctx.scale(1.0, 1.0); // 逐字绘制时保持正常比例
                                                    ctx.fillText(charStr, 0, 0);
                                                    ctx.restore();
                                                }

                                                currentX += charWidths[charIdx] + gap;
                                            }
                                        }
                                    } else {
                                        // 精确的垂直与水平对齐
                                        // 因为 OFD Y轴向下增加，所以向上偏移需要用减法
                                        double y = y_mm + h_mm - fontDescent_mm - (fontSize_mm * 0.05);
                                        double x = x_mm;

                                        // 绘制文字，应用双轴缩放以精确匹配包围框宽度与高度
                                        ctx.save();
                                        ctx.translate(x, y);
                                        ctx.scale(scaleX, scaleY);
                                        ctx.fillText(text, 0, 0);
                                        ctx.restore();
                                    }
                                }
                            }
                        }
                    } catch (IOException e) {
                        throw new RuntimeException("Canvas 绘制失败: " + e.getMessage(), e);
                    } catch (Exception e) {
                        throw new RuntimeException("Canvas 绘制过程中发生错误: " + e.getMessage(), e);
                    }
                });

                vPage.add(canvas);
                ofdDoc.addVPage(vPage);
            }
        }
    }

    private static int[] parseBoundingBox(JsonNode regionNode) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (JsonNode point : regionNode) {
            int x = point.get(0).asInt();
            int y = point.get(1).asInt();
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        return new int[]{minX, minY, maxX, maxY};
    }

    private static String getDetailedImageErrorInfo(File file) {
        if (!file.exists()) {
            return "文件不存在";
        }
        if (!file.canRead()) {
            return "文件不可读，请检查权限";
        }
        long len = file.length();
        if (len == 0) {
            return "文件大小为0字节";
        }

        // 读取前 12 个字节以识别真实格式
        byte[] header = new byte[12];
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            int read = fis.read(header);
            if (read < 4) {
                return "文件长度不足 (实际大小: " + len + " 字节)";
            }

            // 转换为十六进制字符串
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < Math.min(read, 8); i++) {
                hex.append(String.format("%02X ", header[i]));
            }

            // 检查常见的魔数
            if (header[0] == (byte) 0xFF && header[1] == (byte) 0xD8) {
                return "格式为 JPEG (可能包含不支持的色彩空间，如 CMYK), 大小: " + len + " 字节, 魔数: " + hex.toString().trim();
            } else if (header[0] == (byte) 0x89 && header[1] == (byte) 0x50 && header[2] == (byte) 0x4E && header[3] == (byte) 0x47) {
                return "格式为 PNG, 大小: " + len + " 字节, 魔数: " + hex.toString().trim();
            } else if (header[0] == (byte) 0x47 && header[1] == (byte) 0x49 && header[2] == (byte) 0x46) {
                return "格式为 GIF, 大小: " + len + " 字节, 魔数: " + hex.toString().trim();
            } else if (header[0] == (byte) 0x42 && header[1] == (byte) 0x4D) {
                return "格式为 BMP, 大小: " + len + " 字节, 魔数: " + hex.toString().trim();
            } else if (header[0] == (byte) 0x52 && header[1] == (byte) 0x49 && header[2] == (byte) 0x46 && header[3] == (byte) 0x46) { // RIFF
                if (read >= 12 && header[8] == (byte) 0x57 && header[9] == (byte) 0x45 && header[10] == (byte) 0x42 && header[11] == (byte) 0x50) { // WEBP
                    return "格式为 WebP (Java ImageIO 默认不支持 WebP 格式，建议使用 PNG 或 RGB JPG), 大小: " + len + " 字节";
                }
                return "格式为 RIFF 容器 (可能为 WebP), 大小: " + len + " 字节, 魔数: " + hex.toString().trim();
            } else if (header[0] == (byte) 0x25 && header[1] == (byte) 0x50 && header[2] == (byte) 0x44 && header[3] == (byte) 0x46) { // %PDF
                return "格式为 PDF (不是图片文件，请勿将 PDF 文件后缀直接修改为图片后缀), 大小: " + len + " 字节";
            } else if (header[0] == (byte) 0x50 && header[1] == (byte) 0x4B && header[2] == (byte) 0x03 && header[3] == (byte) 0x04) { // PK.. (ZIP)
                return "格式为 ZIP / OFD 容器 (不是图片文件), 大小: " + len + " 字节";
            }

            return "未知格式, 大小: " + len + " 字节, 魔数前缀: " + hex.toString().trim();
        } catch (Exception e) {
            return "无法读取文件内容 (" + e.getMessage() + "), 大小: " + len + " 字节";
        }
    }

    private static boolean isVerticalText(double width, double height, String text) {
        if (text == null || text.isEmpty() || width <= 0 || height <= 0) {
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
        double ratio = height / width;
        int len = clean.length();

        if (len == 1) {
            return ratio > 2.0;
        } else if (len >= 2 && len <= 3) {
            return ratio > 2.5;
        } else {
            // 【优化#5】修复长竖排文本识别阈值，避免阈值过高导致无法识别
            // len >= 4：使用更合理的阈值计算公式
            // 基础阈值2.0，每增加1个字符增加0.15，上限2.8
            // 这样4字符阈值为2.6，10字符阈值为2.8（更宽松）
            double dynamicThreshold = Math.min(2.0 + len * 0.15, 2.8);
            return ratio > dynamicThreshold;
        }
    }
}
