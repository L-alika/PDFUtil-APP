package com.pdfutil.common.core.utils;

import com.pdfutil.common.utils.CommandBuilder;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Word文档转PDF工具类
 *
 * 支持格式：DOC, DOCX, XLS, XLSX, PPT, PPTX 等 Office 文档
 *
 * 转换流程：
 * Office文档 → LibreOffice → 普通PDF → DualLayerPdfConverter → 双层PDF
 *
 * 【重要】DPI一致性说明：
 * - LibreOffice 生成的 PDF 不指定特定 DPI（使用默认输出）
 * - 后续的 DualLayerPdfConverter 统一使用 150 DPI 处理 PDF
 * - 这与 LocalOcrPdfConverter 和 RemoteOcrPdfConverter 的 DPI 设置一致
 *
 * @author Alika
 * @date 2025-01-30
 */
public class WordToPdfConverter {

    private static final Logger log = LoggerFactory.getLogger(WordToPdfConverter.class);

    // 支持的 Office 文件格式
    private static final String[] SUPPORTED_OFFICE_FORMATS = {
        "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "odt", "ods", "odp", "rtf"
    };

    /**
     * 将 Office 文档转换为单层 PDF（纯图片，无文字层）
     *
     * 实现方式：LibreOffice转PDF → PDF转图片 → 图片合成新PDF
     * 这样可以确保输出的是纯图片PDF，不包含任何可搜索文字层
     *
     * @param inputPath 输入的 Office 文档路径
     * @param outputPath 输出的单层 PDF 文件路径
     * @throws Exception 转换过程中可能抛出的异常
     */
    public static void convertToSingleLayerPdf(String inputPath, String outputPath) throws Exception {
        log.info("开始 Office 文档转单层 PDF(纯图片模式): {} -> {}", inputPath, outputPath);

        // 参数校验
        if (inputPath == null || inputPath.trim().isEmpty()) {
            throw new IllegalArgumentException("输入文件路径不能为空");
        }
        if (outputPath == null || outputPath.trim().isEmpty()) {
            throw new IllegalArgumentException("输出文件路径不能为空");
        }

        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            throw new FileNotFoundException("输入文件不存在: " + inputPath);
        }

        // 验证文件格式
        String fileExtension = getFileExtension(inputPath).toLowerCase();
        if (!isSupportedFormat(fileExtension)) {
            throw new IllegalArgumentException("不支持的文件格式: " + fileExtension +
                ", 仅支持: " + String.join(", ", SUPPORTED_OFFICE_FORMATS));
        }

        // 确保输出目录存在
        File outputFile = new File(outputPath);
        File outputDir = outputFile.getParentFile();
        if (outputDir != null && !outputDir.exists()) {
            outputDir.mkdirs();
        }

        PDDocument sourceDoc = null;
        PDDocument outputDoc = null;
        String tempPdfPath = null;

        try {
            // 步骤1: 使用 LibreOffice 将 Office 文档转为 PDF
            log.info("步骤1: LibreOffice转换为PDF");
            tempPdfPath = convertOfficeToPdf(inputPath);
            log.info("临时 PDF 文件已生成: {}", tempPdfPath);

            // 步骤2: 将 PDF 转为纯图片 PDF（去除文字层）
            log.info("步骤2: PDF转纯图片PDF（去除文字层）");
            sourceDoc = PDDocument.load(new File(tempPdfPath));
            outputDoc = new PDDocument();

            PDFRenderer renderer = new PDFRenderer(sourceDoc);
            int pageCount = sourceDoc.getNumberOfPages();

            for (int i = 0; i < pageCount; i++) {
                // 将PDF页面渲染为图片（150 DPI）
                BufferedImage image = renderer.renderImageWithDPI(i, 150);

                // 创建新页面
                PDPage page = new PDPage(PDRectangle.A4);
                outputDoc.addPage(page);

                // 将图片写入PDF页面
                PDImageXObject pdImage = JPEGFactory.createFromImage(outputDoc, image, 0.9f);
                PDPageContentStream contentStream = new PDPageContentStream(outputDoc, page);

                // 计算图片缩放以适应页面
                float pageWidth = page.getMediaBox().getWidth();
                float pageHeight = page.getMediaBox().getHeight();
                float imageWidth = pdImage.getWidth();
                float imageHeight = pdImage.getHeight();

                float scaleX = pageWidth / imageWidth;
                float scaleY = pageHeight / imageHeight;
                float scale = Math.min(scaleX, scaleY);

                float scaledWidth = imageWidth * scale;
                float scaledHeight = imageHeight * scale;
                float x = (pageWidth - scaledWidth) / 2;
                float y = (pageHeight - scaledHeight) / 2;

                contentStream.drawImage(pdImage, x, y, scaledWidth, scaledHeight);
                contentStream.close();

                log.debug("页面 {} 处理完成", i + 1);
            }

            // 保存输出文件
            outputDoc.save(outputPath);
            log.info("纯图片PDF保存成功: {}", outputPath);

            // 验证输出文件
            if (!outputFile.exists() || outputFile.length() == 0) {
                throw new RuntimeException("转换失败，未生成有效输出文件");
            }

            log.info("Office 文档转单层 PDF 成功: {}, 页数: {}, 文件大小: {} bytes",
                outputPath, pageCount, outputFile.length());

        } catch (Exception e) {
            log.error("Office 文档转单层 PDF 失败: {}", inputPath, e);
            throw e;
        } finally {
            // 关闭资源
            if (sourceDoc != null) {
                try {
                    sourceDoc.close();
                } catch (Exception e) {
                    log.warn("关闭源PDF文档失败", e);
                }
            }
            if (outputDoc != null) {
                try {
                    outputDoc.close();
                } catch (Exception e) {
                    log.warn("关闭输出PDF文档失败", e);
                }
            }
            // 清理临时文件
            if (tempPdfPath != null) {
                File tempPdf = new File(tempPdfPath);
                if (tempPdf.exists()) {
                    boolean deleted = tempPdf.delete();
                    log.info("临时 PDF 文件删除: {}", deleted ? "成功" : "失败");
                }
            }
        }
    }

    /**
     * 将 Office 文档转换为双层 PDF
     *
     * @param inputPath 输入的 Office 文档路径
     * @param outputPath 输出的双层 PDF 文件路径
     * @throws Exception 转换过程中可能抛出的异常
     */
    public static void convertToDualLayerPdf(String inputPath, String outputPath) throws Exception {
        log.info("开始 Office 文档转双层 PDF: {} -> {}", inputPath, outputPath);

        // 参数校验
        if (inputPath == null || inputPath.trim().isEmpty()) {
            throw new IllegalArgumentException("输入文件路径不能为空");
        }
        if (outputPath == null || outputPath.trim().isEmpty()) {
            throw new IllegalArgumentException("输出文件路径不能为空");
        }

        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            throw new FileNotFoundException("输入文件不存在: " + inputPath);
        }

        // 验证文件格式
        String fileExtension = getFileExtension(inputPath).toLowerCase();
        if (!isSupportedFormat(fileExtension)) {
            throw new IllegalArgumentException("不支持的文件格式: " + fileExtension +
                ", 仅支持: " + String.join(", ", SUPPORTED_OFFICE_FORMATS));
        }

        // 确保输出目录存在
        File outputFile = new File(outputPath);
        File outputDir = outputFile.getParentFile();
        if (outputDir != null && !outputDir.exists()) {
            outputDir.mkdirs();
        }

        try {
            // 步骤1: 使用 LibreOffice 将 Office 文档转为普通 PDF
            log.info("步骤1: 使用 LibreOffice 转换为普通 PDF");
            String tempPdfPath = convertOfficeToPdf(inputPath);
            log.info("临时 PDF 文件已生成: {}", tempPdfPath);

            // 步骤2: 使用 OCRmyPDF 将普通 PDF 转为双层 PDF
            log.info("步骤2: 使用 OCRmyPDF 生成双层 PDF");
            DualLayerPdfConverter.convertToDualLayerPdf(tempPdfPath, outputPath);

            // 步骤3: 清理临时文件
            File tempPdf = new File(tempPdfPath);
            if (tempPdf.exists()) {
                boolean deleted = tempPdf.delete();
                log.info("临时 PDF 文件删除: {}", deleted ? "成功" : "失败");
            }

            // 验证输出文件
            if (!outputFile.exists() || outputFile.length() == 0) {
                throw new RuntimeException("转换失败，未生成有效输出文件");
            }

            log.info("Office 文档转双层 PDF 成功: {}", outputPath);

        } catch (Exception e) {
            log.error("Office 文档转双层 PDF 失败: {}", inputPath, e);
            throw e;
        }
    }

    /**
     * 使用 LibreOffice 将 Office 文档转换为普通 PDF
     *
     * @param inputPath 输入文件路径
     * @return 临时 PDF 文件路径
     * @throws Exception 转换失败时抛出异常
     */
    private static String convertOfficeToPdf(String inputPath) throws Exception {
        String libreOfficePath = getLibreOfficePath();
        File inputFile = new File(inputPath);

        // 创建临时目录
        String tempDir = System.getProperty("java.io.tmpdir") + "/office_convert";
        new File(tempDir).mkdirs();

        // 【安全修复】使用安全的命令构建器
        List<String> cmdList = CommandBuilder.buildLibreOfficeCommand(libreOfficePath);

        if (cmdList.isEmpty()) {
            throw new IllegalArgumentException("LibreOffice 命令配置为空或不安全");
        }

        // 添加命令参数
        cmdList.add("--headless");           // 无界面模式
        cmdList.add("--convert-to");         // 转换格式
        cmdList.add("pdf");                  // 转换为 PDF
        cmdList.add("--outdir");             // 输出目录
        cmdList.add(tempDir);                // 临时目录

        // 【安全修复】验证文件路径安全性
        if (!CommandBuilder.isSafeFilePath(inputPath)) {
            throw new IllegalArgumentException("输入文件路径不安全: " + inputPath);
        }

        cmdList.add(inputPath);              // 输入文件

        String[] command = cmdList.toArray(new String[0]);

        log.debug("LibreOffice 命令: {}", String.join(" ", command));

        // 执行命令
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process = null;
        BufferedReader br = null;

        try {
            process = pb.start();

            // 读取输出
            br = new BufferedReader(
                new InputStreamReader(process.getInputStream(), "UTF-8")
            );

            String line;
            StringBuilder output = new StringBuilder();
            while ((line = br.readLine()) != null) {
                output.append(line).append("\n");
                log.debug("[LibreOffice-LOG] {}", line);
            }

            int exitCode = waitForProcess(process, 3600, "LibreOffice转换");

            if (exitCode != 0) {
                throw new IOException("LibreOffice 转换失败，exitCode=" + exitCode +
                    ", output=" + output);
            }

            // 查找生成的 PDF 文件
            String baseFileName = inputFile.getName();
            int lastDotIndex = baseFileName.lastIndexOf('.');
            if (lastDotIndex > 0) {
                baseFileName = baseFileName.substring(0, lastDotIndex);
            }
            String tempPdfPath = tempDir + File.separator + baseFileName + ".pdf";

            File tempPdf = new File(tempPdfPath);
            if (!tempPdf.exists()) {
                throw new FileNotFoundException("LibreOffice 转换失败，未找到生成的 PDF 文件: " + tempPdfPath);
            }

            return tempPdfPath;
        } finally {
            // 确保资源被正确关闭
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    log.warn("关闭BufferedReader失败", e);
                }
            }
            if (process != null) {
                process.destroy();
            }
        }
    }

    /**
     * 获取 LibreOffice 安装路径
     */
    private static String getLibreOfficePath() {
        // 优先从系统属性获取
        String path = System.getProperty("libreoffice.path");
        if (path != null && !path.isEmpty()) {
            return path;
        }

        // 根据操作系统返回默认路径
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("win")) {
            // Windows 常见安装路径
            String[] possiblePaths = {
                "C:\\Program Files\\LibreOffice\\program\\soffice.exe",
                "C:\\Program Files (x86)\\LibreOffice\\program\\soffice.exe",
                "C:\\Program Files\\LibreOffice\\program",
                "soffice"  // 如果在 PATH 中
            };
            return findFirstExistingPath(possiblePaths, "soffice");
        } else if (os.contains("mac")) {
            // macOS
            return "/Applications/LibreOffice.app/Contents/MacOS/soffice";
        } else {
            // Linux
            return "soffice";  // 通常在 PATH 中
        }
    }

    /**
     * 查找第一个存在的路径
     */
    private static String findFirstExistingPath(String[] paths, String fallback) {
        for (String path : paths) {
            if (new File(path).exists()) {
                return path;
            }
        }
        return fallback;
    }

    /**
     * 获取文件扩展名
     */
    private static String getFileExtension(String fileName) {
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
     * 判断是否为支持的格式
     */
    private static boolean isSupportedFormat(String extension) {
        for (String format : SUPPORTED_OFFICE_FORMATS) {
            if (format.equalsIgnoreCase(extension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否为 Windows
     */
    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }

    /**
     * 等待进程结束（带超时保护）
     *
     * @param process 进程对象
     * @param timeoutSeconds 超时时间（秒）
     * @param operation 操作描述（用于日志）
     * @return 进程退出码
     * @throws IOException 超时或进程异常时抛出
     */
    private static int waitForProcess(Process process, int timeoutSeconds, String operation) throws IOException, InterruptedException {
        if (process == null) {
            throw new IllegalArgumentException("进程对象不能为空");
        }

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            // 超时，强制终止进程
            log.warn("进程超时（{}秒），强制终止: {}", timeoutSeconds, operation);
            process.destroyForcibly();
            // 等待进程完全终止
            try {
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("进程执行超时（超过" + timeoutSeconds + "秒）: " + operation);
        }

        return process.exitValue();
    }

    /**
     * 检查PDF是否包含可搜索文本层
     * 注意：Office文档通过LibreOffice转换的PDF会保留原生的可搜索文本，这不等同于OCR添加的"双层"
     *
     * @param pdfPath PDF文件路径
     * @return true表示包含可搜索文本
     */
    private static boolean checkPdfHasTextLayer(String pdfPath) {
        try {
            org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.pdmodel.PDDocument.load(new File(pdfPath));
            try {
                // 获取PDF页数
                int pageCount = document.getNumberOfPages();
                if (pageCount == 0) {
                    return false;
                }

                // 检查前几页是否包含文本
                org.apache.pdfbox.text.PDFTextStripper textStripper = new org.apache.pdfbox.text.PDFTextStripper();
                int pagesToCheck = Math.min(3, pageCount); // 检查前3页
                int totalTextLength = 0;

                for (int i = 1; i <= pagesToCheck; i++) {
                    textStripper.setStartPage(i);
                    textStripper.setEndPage(i);
                    String text = textStripper.getText(document);
                    totalTextLength += text != null ? text.trim().length() : 0;
                }

                log.debug("PDF文本层检查: 页数={}, 前{}页文本长度={}", pageCount, pagesToCheck, totalTextLength);
                return totalTextLength > 10; // 如果有超过10个字符，认为有文本层

            } finally {
                document.close();
            }
        } catch (Exception e) {
            log.warn("检查PDF文本层失败: {}", e.getMessage());
            return false;
        }
    }
}
