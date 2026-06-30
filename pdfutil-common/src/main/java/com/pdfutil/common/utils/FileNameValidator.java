package com.pdfutil.common.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.regex.Pattern;

/**
 * 文件名安全验证工具类
 *
 * 防止路径遍历攻击和文件名注入
 *
 * @author Alika
 * @date 2025-02-06
 */
public class FileNameValidator {

    private static final Logger log = LoggerFactory.getLogger(FileNameValidator.class);

    // 危险字符模式：路径遍历、绝对路径、驱动器等
    private static final Pattern DANGEROUS_CHARS_PATTERN = Pattern.compile(
        ".*[\\\\/:*?\"<>|].*|" +                     // Windows/Linux路径分隔符和非法字符
        "..|\\.\\.|%2e%2e|" +                        // 路径遍历 (包括URL编码)
        "^/|" +                                      // 绝对路径开头
        "^[a-zA-Z]:|" +                              // Windows驱动器 (C:, D:)
        "^\\\\\\\\|" +                               // UNC路径 (\\server\share)
        "^~|" +                                      // Unix home目录
        "\\$|" +                                     // Unix环境变量
        "`|\\$\\(|\\$\\(|\\$\\{|"                   // 命令替换
    );

    // 安全文件名模式：只允许字母、数字、中文、下划线、短横线、点、括号（全角和半角）、间隔号（·）
    // 注意：· (U+00B7) 是档号格式中的合法分隔符，如 J001-WS·2016-Y-BGS-0005
    private static final Pattern SAFE_FILENAME_PATTERN = Pattern.compile(
        "^[\\w\\u4e00-\\u9fa5._\\-\\uFF08\\uFF09\\uFF5B\\uFF5D()\\[\\]\\u00B7 ]+$"
    );

    /**
     * 验证文件名是否安全（防止路径遍历注入）
     *
     * @param fileName 要验证的文件名
     * @return true 如果文件名安全
     */
    public static boolean isSafeFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            log.warn("文件名为空");
            return false;
        }

        // 检查文件名长度
        if (fileName.length() > 255) {
            log.warn("文件名过长: {} ({} 字符)", fileName, fileName.length());
            return false;
        }

        // 检查危险字符
        if (DANGEROUS_CHARS_PATTERN.matcher(fileName).matches()) {
            log.warn("文件名包含危险字符: {}", fileName);
            return false;
        }

        // 验证基本文件名格式
        String baseName = getBaseFileName(fileName);
        if (!SAFE_FILENAME_PATTERN.matcher(baseName).matches()) {
            log.warn("文件名包含不安全字符: {}", fileName);
            return false;
        }

        return true;
    }

    /**
     * 获取安全的文件名（移除危险字符）
     *
     * @param fileName 原始文件名
     * @return 安全的文件名，如果原文件名不安全则返回null
     */
    public static String getSafeFileName(String fileName) {
        if (!isSafeFileName(fileName)) {
            return null;
        }
        return fileName;
    }

    /**
     * 获取文件基础名称（不含扩展名）
     */
    private static String getBaseFileName(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex > 0) {
            return fileName.substring(0, lastDotIndex);
        }
        return fileName;
    }

    /**
     * 验证路径是否在允许的目录内
     *
     * @param filePath 要验证的文件路径
     * @param allowedDir 允许的目录
     * @return true 如果路径在允许的目录内
     */
    public static boolean isPathAllowed(String filePath, String allowedDir) {
        try {
            File file = new File(filePath);
            File allowed = new File(allowedDir);

            // 获取规范化路径
            String canonicalPath = file.getCanonicalPath();
            String canonicalAllowed = allowed.getCanonicalPath();

            // 检查文件路径是否以允许目录开头
            return canonicalPath.startsWith(canonicalAllowed + File.separator) ||
                   canonicalPath.equals(canonicalAllowed);
        } catch (Exception e) {
            log.error("路径验证失败: {}", filePath, e);
            return false;
        }
    }

    /**
     * 清理文件名，移除危险字符（用于生成新文件名）
     *
     * @param fileName 原始文件名
     * @return 清理后的安全文件名
     */
    public static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "unnamed";
        }

        // 移除危险字符
        String sanitized = fileName.replaceAll("[\\\\/:*?\"<>|]", "_")
                                   .replaceAll("\\.\\.", "_")
                                   .replaceAll("~", "_")
                                   .replaceAll("[`$]", "_");

        // 限制长度
        if (sanitized.length() > 255) {
            String baseName = getBaseFileName(sanitized);
            String extension = getFileExtension(sanitized);
            int maxBaseLength = 255 - extension.length() - 1;
            sanitized = baseName.substring(0, maxBaseLength) + "." + extension;
        }

        return sanitized.isEmpty() ? "unnamed" : sanitized;
    }

    /**
     * 获取文件扩展名
     */
    private static String getFileExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "";
        }
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(lastDotIndex + 1);
    }
}
