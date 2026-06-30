package com.pdfutil.common.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 命令构建工具类
 *
 * 安全地构建系统命令，防止命令注入攻击
 *
 * @author Alika
 * @date 2025-02-06
 */
public class CommandBuilder {

    private static final Logger log = LoggerFactory.getLogger(CommandBuilder.class);

    /**
     * 安全地构建命令列表
     *
     * @param commandString 命令字符串（可能包含路径和参数）
     * @param args 额外的命令参数
     * @return 命令列表
     */
    public static List<String> buildCommand(String commandString, String... args) {
        List<String> command = new ArrayList<>();

        if (commandString == null || commandString.trim().isEmpty()) {
            log.error("命令字符串为空");
            return command;
        }

        // 检测是否是 Python 模块命令 (如: "python -m ocrmypdf")
        if (commandString.contains("-m")) {
            // 安全地分割 Python 模块命令
            String[] parts = commandString.split("\\s+");
            for (String part : parts) {
                if (!part.trim().isEmpty()) {
                    // 验证每个部分是否安全
                    if (isSafeCommandPart(part)) {
                        command.add(part.trim());
                    } else {
                        log.warn("命令参数包含不安全字符，已跳过: {}", part);
                    }
                }
            }
        } else {
            // 普通命令：验证路径安全性
            if (isSafePath(commandString)) {
                command.add(commandString);
            } else {
                log.warn("命令路径不安全: {}", commandString);
            }
        }

        // 添加额外参数
        for (String arg : args) {
            if (arg != null && !arg.trim().isEmpty()) {
                command.add(arg);
            }
        }

        return command;
    }

    /**
     * 验证命令路径部分是否安全
     *
     * @param path 命令路径
     * @return true 如果路径安全
     */
    private static boolean isSafePath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return false;
        }

        // 检查危险的命令注入字符
        String[] dangerousChars = {"|", "&", ";", "$", "(", ")", "`", "\n", "\r", "\t",
                                   "<", ">", "*", "?", "[", "]", "{", "}"};

        for (String dangerous : dangerousChars) {
            if (path.contains(dangerous)) {
                log.warn("路径包含危险字符 '{}': {}", dangerous, path);
                return false;
            }
        }

        // 验证路径是否存在（可选，因为有些系统命令可能在PATH中）
        // File file = new File(path);
        // return file.exists();

        return true;
    }

    /**
     * 验证命令参数部分是否安全
     *
     * @param part 命令参数部分
     * @return true 如果参数安全
     */
    private static boolean isSafeCommandPart(String part) {
        if (part == null || part.trim().isEmpty()) {
            return false;
        }

        // 检查危险的命令注入字符
        String[] dangerousChars = {"|", "&", ";", "$", "(", ")", "`", "\n", "\r", "\t",
                                   "<", ">", "*", "?", "[", "]", "{", "}"};

        for (String dangerous : dangerousChars) {
            if (part.contains(dangerous)) {
                return false;
            }
        }

        return true;
    }

    /**
     * 验证文件路径是否安全（用于作为命令参数）
     *
     * @param filePath 文件路径
     * @return true 如果路径安全
     */
    public static boolean isSafeFilePath(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return false;
        }

        try {
            File file = new File(filePath);

            // 获取规范化路径（解析 .. 和 .）
            String canonicalPath = file.getCanonicalPath();

            // 检查是否包含危险字符
            String[] dangerousChars = {"|", "&", ";", "$", "(", ")", "`", "\n", "\r"};
            for (String dangerous : dangerousChars) {
                if (canonicalPath.contains(dangerous)) {
                    log.warn("文件路径包含危险字符 '{}': {}", dangerous, canonicalPath);
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            log.error("验证文件路径失败: {}", filePath, e);
            return false;
        }
    }

    /**
     * 从配置路径获取 LibreOffice 命令
     *
     * @param configuredPath 配置的路径
     * @return 安全的命令列表
     */
    public static List<String> buildLibreOfficeCommand(String configuredPath) {
        List<String> command = new ArrayList<>();

        if (configuredPath == null || configuredPath.trim().isEmpty()) {
            log.error("LibreOffice 路径为空");
            return command;
        }

        String path = configuredPath.trim();

        // 检查路径是否安全
        if (!isSafePath(path)) {
            log.error("LibreOffice 路径不安全: {}", path);
            return command;
        }

        // 判断是直接命令还是目录
        File file = new File(path);

        if (file.exists() && file.isFile()) {
            // 直接命令（可执行文件）
            command.add(path);
            log.info("使用 LibreOffice 命令: {}", path);
        } else if (file.exists() && file.isDirectory()) {
            // 目录，需要添加 soffice
            String sofficePath = path + File.separator + "soffice";
            command.add(sofficePath);
            log.info("使用 LibreOffice 目录，拼接命令: {}", sofficePath);
        } else {
            // 文件不存在，假设是命令名称（如 soffice、libreoffice）
            // 在 Linux 上 /usr/bin/libreoffice 是符号链接，可以直接执行
            command.add(path);
            log.info("LibreOffice 路径不存在或为符号链接，直接使用: {}", path);
        }

        return command;
    }
}
