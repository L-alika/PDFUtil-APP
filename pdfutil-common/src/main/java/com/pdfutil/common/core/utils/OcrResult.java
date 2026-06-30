package com.pdfutil.common.core.utils;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * OCR识别结果封装类
 * 支持新旧两种OCR服务返回格式
 *
 * @author Alika
 * @date 2025-02-05
 */
public class OcrResult {
    private static final Logger log = LoggerFactory.getLogger(OcrResult.class);

    /**
     * pages 为识别页的列表，支持两种格式：
     * 1. 新格式：[{"text": "..."}]
     * 2. 旧格式：[[{"text": "...", "confidence": 0.99, "textRegion": [[x1,y1],[x2,y2]]}]]
     */
    private List<PageResult> pages;

    public OcrResult() {
    }

    public OcrResult(List<PageResult> pages) {
        this.pages = pages;
    }

    /**
     * 兼容性构造方法，支持新旧两种格式
     * Jackson会优先使用@JsonCreator标注的构造方法
     */
    @JsonCreator
    public OcrResult(@JsonProperty("pages") Object pagesObj) {
        this.pages = parsePages(pagesObj);
    }

    public List<PageResult> getPages() {
        return pages;
    }

    public void setPages(List<PageResult> pages) {
        this.pages = pages;
    }

    /**
     * 解析pages字段，支持新旧两种格式
     */
    private static List<PageResult> parsePages(Object pagesObj) {
        List<PageResult> pages = new ArrayList<>();

        if (pagesObj == null) {
            return pages;
        }

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode pagesNode = mapper.valueToTree(pagesObj);

            if (pagesNode.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode pageNode : pagesNode) {
                    if (pageNode.isObject() && pageNode.has("text")) {
                        // 新格式：{"text": "..."}
                        PageResult pageResult = new PageResult();
                        pageResult.setText(pageNode.get("text").asText());
                        pages.add(pageResult);
                    } else if (pageNode.isArray()) {
                        // 旧格式：[{"text": "...", "confidence": 0.99, ...}]
                        StringBuilder textBuilder = new StringBuilder();
                        for (com.fasterxml.jackson.databind.JsonNode itemNode : pageNode) {
                            if (itemNode.has("text")) {
                                textBuilder.append(itemNode.get("text").asText()).append(" ");
                            }
                        }
                        PageResult pageResult = new PageResult();
                        pageResult.setText(textBuilder.toString().trim());
                        pages.add(pageResult);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析OCR页面数据失败，使用空列表: {}", e.getMessage());
        }

        return pages;
    }

    /**
     * 页面结果
     */
    public static class PageResult {
        /** 识别文本 */
        private String text;

        public PageResult() {
        }

        public PageResult(String text) {
            this.text = text;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }

    /**
     * 文本项
     */
    public static class TextItem {
        /** 置信度 */
        private double confidence;
        /** 识别文本 */
        private String text;
        /** 文本在原图上的区域坐标，格式为 [[x1,y1],[x2,y2],...] */
        private List<List<Integer>> textRegion;

        public TextItem() {
        }

        public TextItem(double confidence, String text, List<List<Integer>> textRegion) {
            this.confidence = confidence;
            this.text = text;
            this.textRegion = textRegion;
        }

        public double getConfidence() {
            return confidence;
        }

        public void setConfidence(double confidence) {
            this.confidence = confidence;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public List<List<Integer>> getTextRegion() {
            return textRegion;
        }

        public void setTextRegion(List<List<Integer>> textRegion) {
            this.textRegion = textRegion;
        }
    }
}
