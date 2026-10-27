package com.pdfutil.pdf.service;

import org.apache.commons.io.FileUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Converts Office files to searchable preview PDFs without rasterising their content. */
@Service
public class OfficePreviewService {
    @Value("${pdfutil.pdf.libreofficePath:libreoffice}")
    private String executable;
    private final Semaphore slots = new Semaphore(2);

    public static boolean supports(String extension) {
        return extension != null && Arrays.asList("doc", "docx", "xls", "xlsx", "ppt", "pptx")
                .contains(extension.toLowerCase(Locale.ROOT));
    }

    /** Caller owns the returned temporary PDF and must delete it after storing it. */
    public File convert(File source, String extension) throws Exception {
        if (!supports(extension)) throw new IOException("不支持的 Office 格式");
        String format = extension.toLowerCase(Locale.ROOT);
        if (format.endsWith("x")) {
            String part = "docx".equals(format) ? "word/document.xml" :
                    "xlsx".equals(format) ? "xl/workbook.xml" : "ppt/presentation.xml";
            try (java.util.zip.ZipFile archive = new java.util.zip.ZipFile(source)) {
                if (archive.getEntry(part) == null) throw new IOException("Office 文件结构无效或扩展名不匹配");
            }
        } else {
            byte[] signature = new byte[8];
            try (java.io.InputStream input = Files.newInputStream(source.toPath())) {
                if (input.read(signature) != 8 || !Arrays.equals(signature,
                        new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1}))
                    throw new IOException("Office 文件损坏或格式不匹配");
            }
        }
        if (!slots.tryAcquire(30, TimeUnit.SECONDS)) throw new IOException("预览转换繁忙，请稍后重试");
        Path work = null;
        File result = null;
        Process process = null;
        try {
            work = Files.createTempDirectory("office_preview_");
            Path input = work.resolve("document." + extension.toLowerCase(Locale.ROOT));
            Files.copy(source.toPath(), input);
            // Independent profiles prevent concurrent LibreOffice instances from swallowing jobs.
            process = new ProcessBuilder(executable,
                    "-env:UserInstallation=" + work.resolve("profile").toUri(),
                    "--headless", "--nologo", "--nodefault", "--norestore",
                    "--convert-to", "pdf", "--outdir", work.toString(), input.toString())
                    .redirectErrorStream(true).redirectOutput(work.resolve("convert.log").toFile()).start();
            if (!process.waitFor(120, TimeUnit.SECONDS)) throw new IOException("Office 预览转换超时");
            File pdf = work.resolve("document.pdf").toFile();
            if (process.exitValue() != 0 || !pdf.isFile() || pdf.length() == 0)
                throw new IOException("Office 转 PDF 失败，请检查文档是否损坏、加密及 LibreOffice 安装情况");
            try (PDDocument document = PDDocument.load(pdf)) {
                if (document.getNumberOfPages() == 0) throw new IOException("预览文件没有页面");
            }
            result = File.createTempFile("office_preview_result_", ".pdf");
            Files.copy(pdf.toPath(), result.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return result;
        } catch (Exception ex) {
            if (result != null) Files.deleteIfExists(result.toPath());
            throw ex;
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                if (work != null) FileUtils.deleteDirectory(work.toFile());
            } finally {
                slots.release();
            }
        }
    }
}
