package com.pdfutil.common.core.utils;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * @Project dev-pdfUtil
 * @Author Alika
 * @Date 2025/11/27 15:11
 * @Description OCR工具类，提供图片/PDF转双层PDF功能
 *              支持输入格式：PDF, JPG, JPEG, PNG, TIF, TIFF, BMP, GIF
 */
public class OcrUtil {

    private static final String LANG = "chi_sim+chi_tra+eng";

    // OCR工具路径配置，可通过系统属性覆盖默认值
    private static final String OCRMYPDF_PATH =
            System.getProperty("ocrmypdf.path", "ocrmypdf");

    private static final String PDFTOTEXT_PATH =
            System.getProperty("pdftotext.path", "pdftotext");

    // 支持的输入文件格式
    private static final String[] SUPPORTED_FORMATS = {
        "pdf", "jpg", "jpeg", "png", "tif", "tiff", "bmp", "gif"
    };

    /**
     * 主入口：OCR PDF → 返回文本
     *
     * @param inputPdfPath 输入PDF文件路径
     * @return OCR识别后的文本内容
     * @throws Exception 执行过程中可能抛出的异常
     */
    public static String ocrPdfToText(String inputPdfPath) throws Exception {

        String workDir = System.getProperty("java.io.tmpdir") + "/ocr_local";
        File dir = new File(workDir);
        if (!dir.exists())
            dir.mkdirs();

        String outputPdf = workDir + "/ocr_output.pdf";
        String outputTxt = workDir + "/ocr_output.txt";

        // 1. 执行 ocrmypdf 进行PDF OCR处理
        execCommand(buildOcrmypdfCommand(inputPdfPath, outputPdf));

        // 2. 提取PDF中的文本内容
        execCommand(buildPdftotextCommand(outputPdf, outputTxt));

        // 3. 读取并返回提取的文本
        return new String(Files.readAllBytes(Paths.get(outputTxt)), "UTF-8");
    }


    /**
     * 对指定PDF文件执行OCR识别并覆盖原文件
     *
     * @param pdfPath 需要进行OCR处理的PDF文件路径
     * @throws Exception 当OCR处理失败或文件操作异常时抛出
     */
    public static void ocrPdfAndOverwrite(String pdfPath) throws Exception {
        String workDir = System.getProperty("java.io.tmpdir") + "/ocr_local";
        File dir = new File(workDir);
        if (!dir.exists())
            dir.mkdirs();

        // 生成一个临时输出 PDF
        String tmpOutput = workDir + "/ocr_tmp_" + System.currentTimeMillis() + ".pdf";

        try {
            // 1. 执行 OCR 生成双层 PDF
            execCommand(buildOcrmypdfCommand(pdfPath, tmpOutput));

            // 2. 核验输出是否存在
            File tmpFile = new File(tmpOutput);
            if (!tmpFile.exists() || tmpFile.length() == 0) {
                throw new RuntimeException("OCR 失败：未生成有效输出文件");
            }

            // 3. 覆盖原文件（安全替换）
            Files.copy(Paths.get(tmpOutput), Paths.get(pdfPath), StandardCopyOption.REPLACE_EXISTING);

            System.out.println("[OCR] 成功覆盖原 PDF → " + pdfPath);

        } finally {
            // 4. 清理临时文件（不重要失败也不会影响主流程）
            try {
                Files.deleteIfExists(Paths.get(tmpOutput));
            } catch (Exception ignore) {
            }
        }
    }

    /**
     * 图片/PDF转双层PDF（生成新文件）
     *
     * 双层PDF说明：
     * - 底层：保留原始扫描图像，确保视觉效果不变
     * - 顶层：添加OCR识别的透明文本层，支持文本选择和搜索
     * - 使用OCRmyPDF的sandwich渲染器生成
     *
     * @param inputPdfPath  输入文件路径（支持PDF或图片格式：JPG、PNG、TIF等）
     * @param outputPdfPath 输出的双层PDF文件路径
     * @throws Exception 当OCR处理失败或文件操作异常时抛出
     */
    public static void convertToDualLayerPdf(String inputPdfPath, String outputPdfPath) throws Exception {
        // 参数校验
        if (inputPdfPath == null || inputPdfPath.trim().isEmpty()) {
            throw new IllegalArgumentException("输入PDF路径不能为空");
        }
        if (outputPdfPath == null || outputPdfPath.trim().isEmpty()) {
            throw new IllegalArgumentException("输出PDF路径不能为空");
        }

        File inputFile = new File(inputPdfPath);
        if (!inputFile.exists()) {
            throw new FileNotFoundException("输入文件不存在: " + inputPdfPath);
        }

        // 检查文件格式是否支持
        String fileName = inputFile.getName().toLowerCase();
        String fileExtension = fileName.substring(fileName.lastIndexOf('.') + 1);
        boolean isSupported = false;
        for (String format : SUPPORTED_FORMATS) {
            if (format.equals(fileExtension)) {
                isSupported = true;
                break;
            }
        }

        if (!isSupported) {
            throw new IllegalArgumentException("不支持的文件格式: " + fileExtension +
                "。支持的格式: PDF, JPG, JPEG, PNG, TIF, TIFF, BMP, GIF");
        }

        // 确保输出目录存在
        File outputFile = new File(outputPdfPath);
        File outputDir = outputFile.getParentFile();
        if (outputDir != null && !outputDir.exists()) {
            outputDir.mkdirs();
        }

        System.out.println("[双层PDF转换] 开始处理...");
        System.out.println("[双层PDF转换] 输入文件: " + inputPdfPath);
        System.out.println("[双层PDF转换] 输出文件: " + outputPdfPath);

        try {
            // 执行OCR生成双层PDF
            // buildOcrmypdfCommand 使用 --pdf-renderer sandwich 参数生成双层PDF
            execCommand(buildOcrmypdfCommand(inputPdfPath, outputPdfPath));

            // 验证输出文件
            if (!outputFile.exists() || outputFile.length() == 0) {
                throw new RuntimeException("双层PDF转换失败：未生成有效输出文件");
            }

            System.out.println("[双层PDF转换] 转换成功! 输出文件大小: " + outputFile.length() + " 字节");
            System.out.println("[双层PDF转换] 双层PDF特点: 保留原始图像 + 添加可搜索文本层");

        } catch (Exception e) {
            System.err.println("[双层PDF转换] 转换失败: " + e.getMessage());
            throw e;
        }
    }

    /**
     * 图片/PDF转双层PDF（另存为指定目录，文件名保持不变）
     *
     * @param inputPdfPath 输入文件路径（支持PDF或图片格式：JPG、PNG、TIF等）
     * @param outputDir    输出目录路径（如果为null，则默认在输入文件同目录下生成）
     * @return 生成的双层PDF文件路径
     * @throws Exception 当OCR处理失败或文件操作异常时抛出
     */
    public static String convertToDualLayerPdfToDir(String inputPdfPath, String outputDir) throws Exception {
        File inputFile = new File(inputPdfPath);
        String fileName = inputFile.getName();  // 保持原文件名不变

        String targetDir;
        if (outputDir == null || outputDir.trim().isEmpty()) {
            // 如果未指定输出目录，使用输入文件同目录
            targetDir = inputFile.getParent();
        } else {
            targetDir = outputDir;
        }

        // 确保输出目录存在
        File dir = new File(targetDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // 生成输出文件路径：输出目录 + 原文件名（不修改文件名）
        String outputPdfPath = targetDir + File.separator + fileName;

        System.out.println("[双层PDF转换] 输入文件: " + inputPdfPath);
        System.out.println("[双层PDF转换] 输出目录: " + targetDir);
        System.out.println("[双层PDF转换] 输出文件: " + outputPdfPath);

        convertToDualLayerPdf(inputPdfPath, outputPdfPath);

        return outputPdfPath;
    }

    // 执行命令并打印 stdout/stderr
    private static void execCommandWithLog(String command) throws Exception {
        System.out.println("[SSH-CMD] " + command);

        Process process = Runtime.getRuntime().exec(command);

        String stdout = streamToString(process.getInputStream());
        String stderr = streamToString(process.getErrorStream());
        int exitCode = waitForProcess(process, 300, "远程SSH命令");

        System.out.println("[SSH-OUT] " + stdout);
        System.out.println("[SSH-ERR] " + stderr);

        if (exitCode != 0) {
            throw new RuntimeException("远程命令失败，exitCode=" + exitCode + ", err=" + stderr);
        }
    }

    private static String streamToString(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    /**
     * 根据系统构造不同的 OCR 命令
     *
     * @param in  输入文件路径
     * @param out 输出文件路径
     * @return 构造完成的OCR命令字符串
     */
    private static String buildOcrmypdfCommand(String in, String out) {
        if (isWindows()) {
            // Windows 环境下调用 python 模块，使用 sandwich 渲染器生成双层PDF
            // --pdf-renderer sandwich: 生成双层PDF（图像层+文本层）
            // 【版式对齐】禁用 --rotate-pages，保持原始版式（竖版/横版）
            // --deskew: 自动校正倾斜（仅修正拍摄角度，不改变版式方向）
            // --image-dpi: 为没有DPI信息的图片指定默认DPI（300）
            // -O 0: 禁用优化（0=不优化，避免截断图片处理失败）
            // --output-type pdf: 使用普通PDF格式，不强制PDF/A验证（更宽松）
            // --continue-on-soft-render-error: 遇到软渲染错误时继续处理
            return String.format(
                    "python -m ocrmypdf --pdf-renderer sandwich --language %s --deskew --image-dpi 300 -O 0 --output-type pdf --continue-on-soft-render-error \"%s\" \"%s\"",
                    LANG, in, out
            );
        } else {
            // Linux 环境下使用可配置路径的 ocrmypdf 命令
            // 【版式对齐】禁用 --rotate-pages，保持原始版式
            return String.format(
                    "%s -l %s --pdf-renderer sandwich --deskew --image-dpi 300 -O 0 --output-type pdf --continue-on-soft-render-error \"%s\" \"%s\"",
                    OCRMYPDF_PATH, LANG, in, out
            );
        }
    }


    /**
     * 构建pdftotext命令字符串，用于将PDF文件转换为文本文件
     *
     * @param inPdf  输入的PDF文件路径
     * @param outTxt 输出的文本文件路径
     * @return 格式化后的pdftotext命令字符串
     */
    private static String buildPdftotextCommand(String inPdf, String outTxt) {
        return String.format("%s -layout \"%s\" \"%s\"", PDFTOTEXT_PATH, inPdf, outTxt);
    }


    /**
     * 自动执行命令
     *
     * @param cmd 要执行的命令字符串
     * @throws IOException 当命令执行失败或出现IO异常时抛出
     * @throws InterruptedException 当等待进程完成被中断时抛出
     */
    private static void execCommand(String cmd) throws IOException, InterruptedException {
        ProcessBuilder pb;

        // 根据操作系统类型选择合适的命令解释器
        if (isWindows()) {
            pb = new ProcessBuilder("cmd", "/c", cmd);
        } else {
            pb = new ProcessBuilder("bash", "-c", cmd);
        }

        pb.redirectErrorStream(true);

        Process process = null;
        BufferedReader br = null;

        try {
            process = pb.start();

            // 读取命令执行的输出结果
            br = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8")
            );

            String line;
            StringBuilder output = new StringBuilder();
            while ((line = br.readLine()) != null) {
                output.append(line).append("\n");
                System.out.println("[OCR-LOG] " + line);
            }

            int exitCode = waitForProcess(process, 300, "OCR命令");

            if (exitCode != 0) {
                throw new IOException("命令执行失败，exitCode=" + exitCode +
                    ", command=" + cmd +
                    ", output=" + output);
            }
        } finally {
            // 【安全修复】确保资源被正确关闭
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    System.err.println("关闭BufferedReader失败: " + e.getMessage());
                }
            }
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
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

        boolean finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            // 超时，强制终止进程
            System.err.println("进程超时（" + timeoutSeconds + "秒），强制终止: " + operation);
            process.destroyForcibly();
            // 等待进程完全终止
            try {
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("进程执行超时（超过" + timeoutSeconds + "秒）: " + operation);
        }

        return process.exitValue();
    }

    /**
     * 判断当前操作系统是否为Windows
     *
     * @return true-是Windows系统，false-非Windows系统
     */
    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }
}
