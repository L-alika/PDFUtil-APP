package com.pdfutil.common.core.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * OCR结果转换为hOCR格式
 *
 * hOCR是OCR软件使用的HTML格式，用于存储文本和位置信息
 * OCRmyPDF可以使用hOCR文件生成双层PDF
 *
 * @author Alika
 * @date 2025-02-05
 */
public class HocrConverter {

    private static final Logger log = LoggerFactory.getLogger(HocrConverter.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 将远程OCR API返回的JSON转换为hOCR格式
     *
     * @param ocrJsonResult OCR API返回的JSON结果
     * @param imageWidth 图片宽度（像素）
     * @param imageHeight 图片高度（像素）
     * @param imageName 图片文件名
     * @return hOCR格式的HTML字符串
     * @throws Exception 转换失败时抛出异常
     */
    public static String convertToHocr(String ocrJsonResult, int imageWidth, int imageHeight, String imageName) throws Exception {
        log.info("开始将OCR结果转换为hOCR格式");

        JsonNode root = objectMapper.readTree(ocrJsonResult);
        JsonNode pages = root.get("pages");

        if (pages == null || !pages.isArray() || pages.size() == 0) {
            throw new IllegalArgumentException("OCR结果中没有找到pages数据");
        }

        StringBuilder hocr = new StringBuilder();
        hocr.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        hocr.append("<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Transitional//EN\" \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd\">\n");
        hocr.append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"en\" lang=\"en\">\n");
        hocr.append("  <head>\n");
        hocr.append("    <title>OCR Output</title>\n");
        hocr.append("    <meta http-equiv=\"Content-Type\" content=\"text/html;charset=utf-8\" />\n");
        hocr.append("  </head>\n");
        hocr.append("  <body>\n");

        // 处理每一页（通常只有一页，除非是PDF）
        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            JsonNode page = pages.get(pageIndex);
            if (!page.isArray() || page.size() == 0) {
                continue;
            }

            // 创建page div
            hocr.append("    <div class='ocr_page' id='page_").append(pageIndex + 1).append("' ")
                  .append("title='image \"").append(imageName).append("\"; bbox 0 0 ")
                  .append(imageWidth).append(" ").append(imageHeight).append("; ppageno ")
                  .append(pageIndex).append("'>\n");

            // 按照文本块分组（可以根据位置信息将相邻的文本组成paragraph）
            List<TextBlock> textBlocks = groupTextBlocks(page);

            // 为每个文本块创建paragraph和line
            int blockIndex = 0;
            for (TextBlock block : textBlocks) {
                // 创建paragraph
                int[] bbox = calculateBoundingBox(block.textRegions);
                hocr.append("      <div class='ocr_par' id='par_").append(pageIndex + 1).append("_")
                      .append(blockIndex).append("' title=\"bbox ")
                      .append(bbox[0]).append(" ").append(bbox[1]).append(" ")
                      .append(bbox[2]).append(" ").append(bbox[3]).append("\">\n");

                // 创建line（每个文本块作为一个line）
                hocr.append("        <span class='ocr_line' id='line_").append(pageIndex + 1).append("_")
                      .append(blockIndex).append("' title=\"bbox ")
                      .append(bbox[0]).append(" ").append(bbox[1]).append(" ")
                      .append(bbox[2]).append(" ").append(bbox[3]).append("; ")
                      .append("baseline 0.0 0\">\n");

                // 添加文本内容（包含x_word）
                for (TextRegion region : block.textRegions) {
                    int[] wordBbox = region.bbox;
                    hocr.append("          <span class='ocrx_word' id='word_")
                          .append(pageIndex + 1).append("_").append(blockIndex).append("_")
                          .append(region.index).append("' title=\"bbox ")
                          .append(wordBbox[0]).append(" ").append(wordBbox[1]).append(" ")
                          .append(wordBbox[2]).append(" ").append(wordBbox[3])
                          .append("; x_wconf ").append((int)(region.confidence * 100)).append("\">")
                          .append(escapeHtml(region.text))
                          .append("</span>\n");
                }

                hocr.append("        </span>\n");
                hocr.append("      </div>\n");
                blockIndex++;
            }

            hocr.append("    </div>\n");
        }

        hocr.append("  </body>\n");
        hocr.append("</html>\n");

        log.info("hOCR转换完成");
        return hocr.toString();
    }

    /**
     * 将OCR结果转换为hOCR并保存到文件
     *
     * @param ocrJsonResult OCR API返回的JSON结果
     * @param imageWidth 图片宽度
     * @param imageHeight 图片高度
     * @param imageName 图片文件名
     * @param hocrOutputPath hOCR输出文件路径
     * @throws Exception 转换失败时抛出异常
     */
    public static void convertToHocrFile(String ocrJsonResult, int imageWidth, int imageHeight,
                                        String imageName, String hocrOutputPath) throws Exception {
        log.info("开始转换为hOCR文件: 图片尺寸={}x{}, 文件名={}", imageWidth, imageHeight, imageName);
        String hocrContent = convertToHocr(ocrJsonResult, imageWidth, imageHeight, imageName);
        Files.write(Paths.get(hocrOutputPath), hocrContent.getBytes("UTF-8"));
        log.info("hOCR文件已保存: {}", hocrOutputPath);
        log.info("hOCR文件大小: {} 字符", hocrContent.length());
        log.info("hOCR内容预览（前500字符）:\n{}", hocrContent.substring(0, Math.min(500, hocrContent.length())));
    }

    /**
     * 将OCR结果的指定页转换为hOCR并保存到文件
     *
     * @param ocrJsonResult OCR API返回的JSON结果（包含所有页）
     * @param pageIndex 要转换的页码（从0开始）
     * @param imageWidth 图片宽度
     * @param imageHeight 图片高度
     * @param imageName 图片文件名
     * @param hocrOutputPath hOCR输出文件路径
     * @throws Exception 转换失败时抛出异常
     */
    public static void convertToHocrFile(String ocrJsonResult, int pageIndex, int imageWidth, int imageHeight,
                                        String imageName, String hocrOutputPath) throws Exception {
        // 解析OCR结果
        JsonNode root = objectMapper.readTree(ocrJsonResult);
        JsonNode pages = root.get("pages");

        if (pages == null || !pages.isArray()) {
            throw new IllegalArgumentException("OCR结果中没有找到pages数据");
        }

        int totalPages = pages.size();
        log.debug("OCR结果共 {} 页，请求第 {} 页（索引从0开始）", totalPages, pageIndex);

        if (totalPages <= pageIndex) {
            log.error("页码超出范围：请求第{}页，但OCR结果只有{}页。原始结果: {}", pageIndex + 1, totalPages, ocrJsonResult);
            throw new IllegalArgumentException("OCR结果中没有找到页码 " + pageIndex + "（共 " + totalPages + " 页）");
        }

        // 提取指定页的数据，构建单页的JSON
        JsonNode singlePage = pages.get(pageIndex);
        StringBuilder singlePageJson = new StringBuilder();
        singlePageJson.append("{\"pages\": [");

        // 将单页数据转换为JSON字符串
        singlePageJson.append(objectMapper.writeValueAsString(singlePage));

        singlePageJson.append("]}");

        // 转换为hOCR
        String hocrContent = convertToHocr(singlePageJson.toString(), imageWidth, imageHeight, imageName);
        Files.write(Paths.get(hocrOutputPath), hocrContent.getBytes("UTF-8"));
        log.info("hOCR文件已保存（第{}页）: {}", pageIndex + 1, hocrOutputPath);
    }

    /**
     * 将相邻的文本区域分组为文本块
     */
    private static List<TextBlock> groupTextBlocks(JsonNode pageData) {
        List<TextBlock> blocks = new ArrayList<>();
        TextBlock currentBlock = new TextBlock();

        for (int i = 0; i < pageData.size(); i++) {
            JsonNode item = pageData.get(i);
            String text = item.get("text").asText();
            double confidence = item.get("confidence").asDouble();
            JsonNode regionNode = item.get("textRegion");

            if (regionNode == null || !regionNode.isArray() || regionNode.size() < 4) {
                continue;
            }

            // 解析四个坐标点
            int[] bbox = parseBoundingBox(regionNode);
            TextRegion textRegion = new TextRegion(i, text, confidence, bbox);

            // 简单策略：如果当前文本块已经有内容，且新文本距离较远，则创建新块
            if (currentBlock.textRegions.isEmpty()) {
                currentBlock.textRegions.add(textRegion);
            } else {
                // 检查是否应该开始新的文本块
                TextRegion lastRegion = currentBlock.textRegions.get(currentBlock.textRegions.size() - 1);
                if (shouldStartNewBlock(lastRegion, textRegion)) {
                    blocks.add(currentBlock);
                    currentBlock = new TextBlock();
                }
                currentBlock.textRegions.add(textRegion);
            }
        }

        if (!currentBlock.textRegions.isEmpty()) {
            blocks.add(currentBlock);
        }

        return blocks;
    }

    /**
     * 判断是否应该开始新的文本块
     */
    private static boolean shouldStartNewBlock(TextRegion region1, TextRegion region2) {
        int distanceThreshold = 50; // 像素距离阈值
        int verticalThreshold = 30;  // 垂直距离阈值

        // 检查垂直距离
        int y1 = region1.bbox[3]; // bottom
        int y2 = region2.bbox[1]; // top
        if (Math.abs(y2 - y1) > verticalThreshold) {
            return true;
        }

        // 检查水平距离
        int x1 = region1.bbox[2]; // right
        int x2 = region2.bbox[0]; // left
        if (x2 - x1 > distanceThreshold) {
            return true;
        }

        return false;
    }

    /**
     * 解析边界框
     */
    private static int[] parseBoundingBox(JsonNode regionNode) {
        // regionNode格式: [[x1,y1],[x2,y2],[x3,y3],[x4,y4]]
        // 获取四个点中的最小x, 最小y, 最大x, 最大y
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (int i = 0; i < regionNode.size(); i++) {
            JsonNode point = regionNode.get(i);
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
     * 计算多个文本区域的边界框
     */
    private static int[] calculateBoundingBox(List<TextRegion> regions) {
        if (regions.isEmpty()) {
            return new int[]{0, 0, 0, 0};
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (TextRegion region : regions) {
            minX = Math.min(minX, region.bbox[0]);
            minY = Math.min(minY, region.bbox[1]);
            maxX = Math.max(maxX, region.bbox[2]);
            maxY = Math.max(maxY, region.bbox[3]);
        }

        return new int[]{minX, minY, maxX, maxY};
    }

    /**
     * 转义HTML特殊字符
     */
    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    /**
     * 文本块
     */
    private static class TextBlock {
        List<TextRegion> textRegions = new ArrayList<>();
    }

    /**
     * 文本区域
     */
    private static class TextRegion {
        int index;
        String text;
        double confidence;
        int[] bbox; // [minX, minY, maxX, maxY]

        TextRegion(int index, String text, double confidence, int[] bbox) {
            this.index = index;
            this.text = text;
            this.confidence = confidence;
            this.bbox = bbox;
        }
    }
}
