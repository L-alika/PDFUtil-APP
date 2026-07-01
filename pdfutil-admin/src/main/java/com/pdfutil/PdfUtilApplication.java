package com.pdfutil;

import com.pdfutil.common.core.utils.DualLayerPdfConverter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * 启动程序
 *
 * @author Alika
 */
@SpringBootApplication(
    exclude = {DataSourceAutoConfiguration.class},
    scanBasePackages = {
        "com.pdfutil.pdf",              // PDF转换模块（核心功能）
        "com.pdfutil.common",           // 公共工具
        "com.pdfutil.framework.config"  // 框架配置
    }
)
public class PdfUtilApplication {
    public static void main(String[] args) {
        long start = System.currentTimeMillis();

        SpringApplication app = new SpringApplication(PdfUtilApplication.class);
        ConfigurableApplicationContext context = app.run(args);

        // 初始化PDF转换配置（将Spring配置转换为系统属性）
        initPdfConfig(context.getEnvironment());

        long cost = System.currentTimeMillis() - start;

        Environment env = context.getEnvironment();
        String appName = env.getProperty("spring.application.name", "Application");
        String port = env.getProperty("server.port", "8080");
        String profile = String.join(",", env.getActiveProfiles());

        System.out.println();
        System.out.println("==============================================");
        System.out.println("🚀 Application Started Successfully");
        System.out.println("----------------------------------------------");
        System.out.println("📌 Name    : " + appName);
        System.out.println("🌐 Port    : " + port);
        System.out.println("⚙️  Profile : " + (profile.isEmpty() ? "default" : profile));
        System.out.println("⏱  Time    : " + cost + " ms");
        System.out.println("==============================================");
        System.out.println();
    }

    /**
     * 初始化PDF转换配置
     * 将Spring配置转换为系统属性，供DualLayerPdfConverter使用
     */
    private static void initPdfConfig(Environment env) {
        // OCR类型
        String ocrType = env.getProperty("pdfutil.pdf.ocrType", "rapid");

        // RapidOCR配置
        String rapidOcrScriptPath = env.getProperty("pdfutil.pdf.rapidOcrScriptPath", "");
        String rapidOcrServiceScriptPath = env.getProperty("pdfutil.pdf.rapidOcrServiceScriptPath", "");
        String rapidOcrModelDir = env.getProperty("pdfutil.pdf.rapidOcrModelDir", "");

        // PaddleOCR配置（兼容）
        String paddleOcrScriptPath = env.getProperty("pdfutil.pdf.paddleOcrScriptPath", "");
        String pythonPath = env.getProperty("pdfutil.pdf.pythonPath", "python");

        // 本地OCR配置
        int localOcrExecuteTimeout = env.getProperty("pdfutil.pdf.localOcrExecuteTimeout", Integer.class, 300000);
        int localOcrMaxConcurrent = env.getProperty("pdfutil.pdf.localOcrMaxConcurrent", Integer.class, 4);

        // OCRmyPDF配置
        String ocrmypdfPath = env.getProperty("pdfutil.pdf.ocrmypdfPath", "python -m ocrmypdf");
        String ocrmypdfLanguage = env.getProperty("pdfutil.pdf.ocrmypdfLanguage", "chi_sim+chi_tra+eng");

        // DPI配置 (支持从 pdfutil.pdf 和 pdf 前缀读取)
        boolean adaptiveDpi = env.getProperty("pdfutil.pdf.adaptiveDpi", Boolean.class, 
                              env.getProperty("pdf.adaptive-dpi", Boolean.class, true));
        int extractDpi = env.getProperty("pdfutil.pdf.extractDpi", Integer.class, 
                         env.getProperty("pdf.extract-dpi", Integer.class, 200));
        float imageForceDpi = env.getProperty("pdfutil.pdf.imageForceDpi", Float.class, 
                              env.getProperty("pdf.image-force-dpi", Float.class, 200.0f));

        // 设置系统属性
        System.setProperty("ocr.type", ocrType);
        System.setProperty("ocr.engine", ocrType);
        System.setProperty("paddleocr.script.path", paddleOcrScriptPath);
        System.setProperty("rapidocr.script.path", rapidOcrScriptPath);
        System.setProperty("rapidocr.service.script.path", rapidOcrServiceScriptPath);
        System.setProperty("rapidocr.model.dir", rapidOcrModelDir);
        System.setProperty("python.path", pythonPath);
        System.setProperty("ocrmypdf.path", ocrmypdfPath);
        System.setProperty("ocr.language", ocrmypdfLanguage);

        // 设置DPI系统属性
        System.setProperty("pdf.adaptive.dpi", String.valueOf(adaptiveDpi));
        System.setProperty("pdf.extract.dpi", String.valueOf(extractDpi));
        System.setProperty("image.force.dpi", String.valueOf(imageForceDpi));

        // 设置OCR引擎类型标志（确保与线程池配置一致）
        if ("rapid".equalsIgnoreCase(ocrType)) {
            System.setProperty("use.rapidocr", "true");
            System.setProperty("use.paddleocr", "false");
        } else if ("paddle".equalsIgnoreCase(ocrType) || "local_paddle".equalsIgnoreCase(ocrType)) {
            System.setProperty("use.paddleocr", "true");
            System.setProperty("use.rapidocr", "false");
        } else {
            // 默认使用 RapidOCR
            System.setProperty("use.rapidocr", "true");
            System.setProperty("use.paddleocr", "false");
        }

        // 设置本地OCR配置
        System.setProperty("pdfutil.pdf.localOcrExecuteTimeout", String.valueOf(localOcrExecuteTimeout));
        System.setProperty("pdfutil.pdf.localOcrMaxConcurrent", String.valueOf(localOcrMaxConcurrent));

        System.out.println();
        System.out.println("==============================================");
        System.out.println("📄 PDF转换配置已初始化");
        System.out.println("----------------------------------------------");

        // 显示OCR类型
        String ocrTypeDisplay;
        if ("rapid".equals(ocrType)) {
            ocrTypeDisplay = "RapidOCR (推荐，高性能)";
        } else if ("local_paddle".equals(ocrType) || "paddle".equals(ocrType)) {
            ocrTypeDisplay = "PaddleOCR (本地)";
        } else {
            ocrTypeDisplay = "本地OCR";
        }
        System.out.println("OCR类型       : " + ocrTypeDisplay);

        if ("rapid".equals(ocrType)) {
            System.out.println("RapidOCR脚本 : " + (rapidOcrScriptPath.isEmpty() ? "未配置" : rapidOcrScriptPath));
            System.out.println("常驻服务脚本  : " + (rapidOcrServiceScriptPath.isEmpty() ? "未配置" : rapidOcrServiceScriptPath));
            System.out.println("模型目录      : " + (rapidOcrModelDir.isEmpty() ? "未配置" : rapidOcrModelDir));
        } else if ("paddle".equals(ocrType) || "local_paddle".equals(ocrType)) {
            System.out.println("PaddleOCR脚本 : " + (paddleOcrScriptPath.isEmpty() ? "未配置" : paddleOcrScriptPath));
            System.out.println("Python路径    : " + pythonPath);
        }
        System.out.println("执行超时      : " + localOcrExecuteTimeout + " ms");
        System.out.println("最大并发      : " + localOcrMaxConcurrent);
        System.out.println("自适应DPI     : " + (adaptiveDpi ? "启用" : "禁用"));
        System.out.println("PDF提取DPI    : " + extractDpi + " DPI");
        System.out.println("强制图片DPI   : " + imageForceDpi + " DPI");
        System.out.println("==============================================");
        System.out.println();
    }

}
