package com.pdfutil.config;

import com.pdfutil.common.config.PdfOcrConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * OCR配置初始化器
 * 在应用启动时，将配置文件中的OCR路径设置到系统属性中
 *
 * 注意：当前使用 OCRmyPDF 方案，此类暂时禁用
 * 如果需要使用 PaddleOCR + Tesseract 方案，请取消注释并添加相应配置
 *
 * @author Alika
 * @date 2025-01-28
 */
@Component
public class OcrConfigInitializer implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(OcrConfigInitializer.class);

    @Autowired(required = false)
    private PdfOcrConfig pdfOcrConfig;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("初始化OCR配置...");

        if (pdfOcrConfig == null) {
            log.info("未配置PdfOcrConfig，使用默认OCRmyPDF方案");
            return;
        }

        // 仅在系统属性未设置时才设置（避免覆盖PdfUtilApplication中的配置）
        // 设置PaddleOCR路径到系统属性
        if (pdfOcrConfig.getPaddleOcrScriptPath() != null && System.getProperty("paddleocr.script.path") == null) {
            System.setProperty("paddleocr.path", pdfOcrConfig.getPaddleOcrScriptPath());
            System.setProperty("paddleocr.script.path", pdfOcrConfig.getPaddleOcrScriptPath());
            log.info("PaddleOCR路径: {}", pdfOcrConfig.getPaddleOcrScriptPath());
        }

        // 设置Python路径到系统属性
        if (pdfOcrConfig.getPythonPath() != null && System.getProperty("python.path") == null) {
            System.setProperty("python.path", pdfOcrConfig.getPythonPath());
            log.info("Python路径: {}", pdfOcrConfig.getPythonPath());
        }

        // 设置OCRmyPDF路径到系统属性
        if (pdfOcrConfig.getOcrmypdfPath() != null && System.getProperty("ocrmypdf.path") == null) {
            System.setProperty("ocrmypdf.path", pdfOcrConfig.getOcrmypdfPath());
            log.info("OCRmyPDF路径: {}", pdfOcrConfig.getOcrmypdfPath());
        }

        // 设置OCRmyPDF语言到系统属性
        if (pdfOcrConfig.getOcrmypdfLanguage() != null && System.getProperty("ocr.language") == null) {
            System.setProperty("ocr.language", pdfOcrConfig.getOcrmypdfLanguage());
            log.info("OCRmyPDF语言: {}", pdfOcrConfig.getOcrmypdfLanguage());
        }

        // 设置是否使用PaddleOCR方案
        if (System.getProperty("use.paddleocr") == null) {
            System.setProperty("use.paddleocr", String.valueOf(pdfOcrConfig.isUsePaddleOcr()));
            log.info("使用PaddleOCR方案: {}", pdfOcrConfig.isUsePaddleOcr());
        }

        // 设置OCR方案类型和远程API URL（仅在未设置时）
        if (pdfOcrConfig.getOcrType() != null && System.getProperty("ocr.type") == null) {
            System.setProperty("ocr.type", pdfOcrConfig.getOcrType());
            log.info("OCR方案类型: {}", pdfOcrConfig.getOcrType());

            if ("remote".equals(pdfOcrConfig.getOcrType())) {
                if (pdfOcrConfig.getRemoteOcrApiUrl() != null) {
                    System.setProperty("ocr.api.url", pdfOcrConfig.getRemoteOcrApiUrl());
                    log.info("远程OCR API URL: {}", pdfOcrConfig.getRemoteOcrApiUrl());
                }
            }
        }

        // 设置LibreOffice路径到系统属性
        if (pdfOcrConfig.getLibreofficePath() != null && System.getProperty("libreoffice.path") == null) {
            System.setProperty("libreoffice.path", pdfOcrConfig.getLibreofficePath());
            log.info("LibreOffice路径: {}", pdfOcrConfig.getLibreofficePath());
        }

        // 设置PaddleOCR智能并发控制配置
        if (System.getProperty("pdfutil.pdf.localOcrMaxConcurrent") == null) {
            System.setProperty("pdfutil.pdf.localOcrMaxConcurrent", String.valueOf(pdfOcrConfig.getLocalOcrMaxConcurrent()));
            System.setProperty("pdfutil.pdf.largeImageThreshold", String.valueOf(pdfOcrConfig.getLargeImageThreshold()));
            System.setProperty("pdfutil.pdf.veryLargeImageThreshold", String.valueOf(pdfOcrConfig.getVeryLargeImageThreshold()));
            System.setProperty("pdfutil.pdf.ocrBatchSize", String.valueOf(pdfOcrConfig.getOcrBatchSize()));
            log.info("PaddleOCR智能并发配置: 最大并发={}, 大图片阈值={}MP, 超大图片阈值={}MP, OCR分批大小={}",
                pdfOcrConfig.getLocalOcrMaxConcurrent(),
                pdfOcrConfig.getLargeImageThreshold() / 1_000_000,
                pdfOcrConfig.getVeryLargeImageThreshold() / 1_000_000,
                pdfOcrConfig.getOcrBatchSize());
        }

        log.info("OCR配置初始化完成");
    }
}
