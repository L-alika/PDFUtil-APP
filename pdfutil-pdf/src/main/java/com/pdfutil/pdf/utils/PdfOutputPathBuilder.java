package com.pdfutil.pdf.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PDF输出路径构建工具类
 * 根据文件夹创建规则和PDF命名来源构建输出路径
 *
 * 支持两种档号格式：
 * 格式1：J001-2013-Y-03-006（5部分，用-分隔）
 *        全宗号-年度-业务类别-案卷号-件号
 * 格式2：J001-WS·2016-Y-BGS-0005（6部分，混合分隔符）
 *        全宗号-门类代码·年度-保管期限-三位机构代码-件号
 *
 * 输出文件夹规则（0-8）：
 * 0. 不创建文件夹（默认）
 * 1. 按档号各部分单独创建文件夹层级，最后再创建完整档号文件夹
 * 2. 逐层累积创建文件夹，每层追加一个档号部分
 * 3. 路径层级累积+创建PDF名文件夹：基于输入目录层级累积创建，最后创建PDF名文件夹
 * 4. 路径层级累积：基于输入目录层级累积创建文件夹
 * 5. 保持输入目录结构
 * 6. 路径层级命名+保持原目录结构：从输入目录提取子目录层级作为PDF名，保持原目录结构
 * 7. 路径层级命名+去掉件号层：从输入目录提取子目录层级作为PDF名，输出时去掉件号层目录
 * 8. 保持输入目录结构+去掉件号层+以档号命名（仅合并模式）：去掉最后一级目录，使用该目录名作为PDF文件名
 *
 * @author Alika
 * @date 2025-03-06
 */
public class PdfOutputPathBuilder {

    private static final Logger log = LoggerFactory.getLogger(PdfOutputPathBuilder.class);

    /**
     * 档号格式类型
     */
    public enum ArchiveFormat {
        FORMAT_1,  // J001-2013-Y-03-006（5部分）
        FORMAT_2   // J001-WS·2016-Y-BGS-0005（6部分）
    }

    /**
     * 构建输出目录路径
     *
     * @param baseOutputDir    基础输出目录
     * @param sourceFilePath   源文件路径
     * @param folderCreateRule 文件夹创建规则(0-7)
     * @param preservePath     保持目录结构的相对路径（规则5/6/7使用）
     * @return 构建后的输出目录路径
     */
    public static String buildOutputDir(String baseOutputDir, String sourceFilePath,
                                        int folderCreateRule, String preservePath) {
        if (baseOutputDir == null || baseOutputDir.isEmpty()) {
            baseOutputDir = ".";
        }

        // 默认规则0：不创建文件夹
        if (folderCreateRule < 0 || folderCreateRule > 7) {
            folderCreateRule = 0;
        }

        File sourceFile = new File(sourceFilePath);
        String sourceFileName = sourceFile.getName();

        // 获取不含扩展名的文件名（档号）
        String archiveNumber = sourceFileName;
        int dotIndex = sourceFileName.lastIndexOf('.');
        if (dotIndex > 0) {
            archiveNumber = sourceFileName.substring(0, dotIndex);
        }

        StringBuilder pathBuilder = new StringBuilder(baseOutputDir);

        switch (folderCreateRule) {
            case 0:
                // 规则0：不创建文件夹，直接输出到基础目录
                // 示例：D:\输出\J001-WS-2016-Y-BGS-0005.pdf
                break;

            case 1:
                // 规则1：按档号各部分单独创建文件夹层级，最后再创建完整档号文件夹
                // 示例：JG02/2025/YJ03/012/JG02-2025-YJ03-012-005/
                List<String> parts1 = getArchiveParts(archiveNumber);
                for (String part : parts1) {
                    pathBuilder.append(File.separator).append(part);
                }
                // 最后再创建一个以完整档号命名的文件夹
                pathBuilder.append(File.separator).append(archiveNumber);
                break;

            case 2:
                // 规则2：逐层累积创建文件夹
                // 示例：JG02/JG02-2025/JG02-2025-YJ03/JG02-2025-YJ03-012/JG02-2025-YJ03-012-005/
                List<String> parts2 = getArchiveParts(archiveNumber);
                StringBuilder cumulative = new StringBuilder();
                for (int i = 0; i < parts2.size(); i++) {
                    if (i > 0) {
                        cumulative.append("-");
                    }
                    cumulative.append(parts2.get(i));
                    pathBuilder.append(File.separator).append(cumulative.toString());
                }
                break;

            case 3:
                // 规则3：路径层级累积+去掉件号层
                // 累积路径去掉最后一级（件号层），直接输出PDF
                // 示例：D:\cs\J001\WS\2016\Y\BGS\0005\xxx.jpg
                //       preservePath = J001/WS/2016/Y/BGS/0005
                //       去掉件号层后 = J001/WS/2016/Y/BGS
                //       累积路径 = J001\J001-WS\J001-WS-2016\J001-WS-2016-Y\J001-WS-2016-Y-BGS
                //       → D:\输出\J001\J001-WS\J001-WS-2016\J001-WS-2016-Y\J001-WS-2016-Y-BGS\J001-WS-2016-Y-BGS-0005.pdf
                if (preservePath != null && !preservePath.isEmpty()) {
                    // 去掉最后一级目录（件号层）
                    String pathWithoutLastLevel = removeLastLevel(preservePath);
                    String cumulativePath = buildCumulativePathFromPreservePath(pathWithoutLastLevel);
                    pathBuilder.append(File.separator).append(cumulativePath);
                }
                break;

            case 4:
                // 规则4：路径层级累积
                // 基于输入目录层级累积创建文件夹
                // 示例：D:\cs\J001\WS\2016\Y\BGS\0005\xxx.jpg
                //       → D:\输出\J001\J001-WS\J001-WS-2016\J001-WS-2016-Y\J001-WS-2016-Y-BGS\J001-WS-2016-Y-BGS-0005.pdf
                if (preservePath != null && !preservePath.isEmpty()) {
                    String cumulativePath = buildCumulativePathFromPreservePath(preservePath);
                    pathBuilder.append(File.separator).append(cumulativePath);
                }
                break;

            case 5:
                // 规则5：保持输入目录结构
                // preservePath 是相对于输入目录的相对路径
                if (preservePath != null && !preservePath.isEmpty()) {
                    pathBuilder.append(File.separator).append(preservePath);
                }
                break;

            case 6:
                // 规则6：路径层级命名 + 保持原目录结构
                // preservePath 是相对于输入目录的相对路径，保持完整目录结构
                // 示例：D:\cs\J001\WS\2016\Y\BGS\0005\8931.jpg → D:\输出\J001\WS\2016\Y\BGS\0005\J001-WS-2016-Y-BGS-0005.pdf
                if (preservePath != null && !preservePath.isEmpty()) {
                    pathBuilder.append(File.separator).append(preservePath);
                }
                break;

            case 7:
                // 规则7：路径层级命名 + 去掉件号层
                // preservePath 去掉最后一级目录（件号层）
                // 示例：D:\cs\J001\WS\2016\Y\BGS\0005\8931.jpg → D:\输出\J001\WS\2016\Y\BGS\J001-WS-2016-Y-BGS-0005.pdf
                if (preservePath != null && !preservePath.isEmpty()) {
                    // 去掉最后一级目录
                    int lastSep = preservePath.lastIndexOf('/');
                    if (lastSep == -1) {
                        lastSep = preservePath.lastIndexOf('\\');
                    }
                    if (lastSep > 0) {
                        pathBuilder.append(File.separator).append(preservePath.substring(0, lastSep));
                    }
                }
                break;

            case 8:
                // 规则8：保持输入目录结构 + 去掉件号层 + 以档号命名（仅合并模式）
                // preservePath 去掉最后一级目录（件号层）
                // 示例：D:/输入/1983/永久/szy-01-0001/szy-01-0001-0001/xxx.jpg
                //       preservePath = 1983/永久/szy-01-0001/szy-01-0001-0001
                //       去掉件号层后 = 1983/永久/szy-01-0001
                //       → D:/输出/1983/永久/szy-01-0001/szy-01-0001-0001.pdf
                if (preservePath != null && !preservePath.isEmpty()) {
                    // 去掉最后一级目录（件号层）
                    int lastSep = preservePath.lastIndexOf('/');
                    if (lastSep == -1) {
                        lastSep = preservePath.lastIndexOf('\\');
                    }
                    if (lastSep > 0) {
                        pathBuilder.append(File.separator).append(preservePath.substring(0, lastSep));
                    }
                }
                break;
        }

        String resultPath = pathBuilder.toString();
        log.info("构建输出目录: 规则={}, 源路径={}, 结果={}", folderCreateRule, sourceFilePath, resultPath);

        return resultPath;
    }

    /**
     * 合并模式专用：构建输出目录路径
     * 对于规则1/2，使用合并文件名作为档号构建路径
     *
     * @param baseOutputDir    基础输出目录
     * @param mergeFilename    合并文件名（不含.pdf扩展名）
     * @param folderCreateRule 文件夹创建规则(0-8)
     * @param preservePath     保持目录结构的相对路径（规则3/4/5/6/7/8使用）
     * @return 构建后的输出目录路径
     */
    public static String buildOutputDirForMerge(String baseOutputDir, String mergeFilename,
                                                 int folderCreateRule, String preservePath) {
        if (baseOutputDir == null || baseOutputDir.isEmpty()) {
            baseOutputDir = ".";
        }

        // 默认规则0：不创建文件夹
        if (folderCreateRule < 0 || folderCreateRule > 8) {
            folderCreateRule = 0;
        }

        StringBuilder pathBuilder = new StringBuilder(baseOutputDir);

        // 使用合并文件名作为档号（规则8时不使用）
        String archiveNumber = (mergeFilename != null && folderCreateRule != 8) ? mergeFilename : "merged";

        switch (folderCreateRule) {
            case 0:
                // 规则0：不创建文件夹，直接输出到基础目录
                break;

            case 1:
                // 规则1：按档号各部分单独创建文件夹层级，最后再创建完整档号文件夹
                List<String> parts1 = getArchiveParts(archiveNumber);
                for (String part : parts1) {
                    pathBuilder.append(File.separator).append(part);
                }
                pathBuilder.append(File.separator).append(archiveNumber);
                break;

            case 2:
                // 规则2：逐层累积创建文件夹
                List<String> parts2 = getArchiveParts(archiveNumber);
                StringBuilder cumulative = new StringBuilder();
                for (int i = 0; i < parts2.size(); i++) {
                    if (i > 0) {
                        cumulative.append("-");
                    }
                    cumulative.append(parts2.get(i));
                    pathBuilder.append(File.separator).append(cumulative.toString());
                }
                break;

            case 3:
                // 规则3：路径层级累积+去掉件号层
                if (preservePath != null && !preservePath.isEmpty()) {
                    String pathWithoutLastLevel = removeLastLevel(preservePath);
                    String cumulativePath = buildCumulativePathFromPreservePath(pathWithoutLastLevel);
                    pathBuilder.append(File.separator).append(cumulativePath);
                }
                break;

            case 4:
                // 规则4：路径层级累积
                if (preservePath != null && !preservePath.isEmpty()) {
                    String cumulativePath = buildCumulativePathFromPreservePath(preservePath);
                    pathBuilder.append(File.separator).append(cumulativePath);
                }
                break;

            case 5:
                // 规则5：保持输入目录结构
                if (preservePath != null && !preservePath.isEmpty()) {
                    pathBuilder.append(File.separator).append(preservePath);
                }
                break;

            case 6:
                // 规则6：路径层级命名 + 保持原目录结构
                if (preservePath != null && !preservePath.isEmpty()) {
                    pathBuilder.append(File.separator).append(preservePath);
                }
                break;

            case 7:
                // 规则7：路径层级命名 + 去掉件号层
                if (preservePath != null && !preservePath.isEmpty()) {
                    int lastSep = preservePath.lastIndexOf('/');
                    if (lastSep == -1) {
                        lastSep = preservePath.lastIndexOf('\\');
                    }
                    if (lastSep > 0) {
                        pathBuilder.append(File.separator).append(preservePath.substring(0, lastSep));
                    }
                }
                break;

            case 8:
                // 规则8：保持输入目录结构 + 去掉件号层 + 以档号命名（仅合并模式）
                // preservePath 去掉最后一级目录（件号层）
                if (preservePath != null && !preservePath.isEmpty()) {
                    // 去掉最后一级目录（件号层）
                    int lastSep = preservePath.lastIndexOf('/');
                    if (lastSep == -1) {
                        lastSep = preservePath.lastIndexOf('\\');
                    }
                    if (lastSep > 0) {
                        pathBuilder.append(File.separator).append(preservePath.substring(0, lastSep));
                    }
                }
                break;
        }

        String resultPath = pathBuilder.toString();
        log.info("合并模式构建输出目录: 规则={}, 合并文件名={}, preservePath={}, 结果={}",
                folderCreateRule, mergeFilename, preservePath, resultPath);

        return resultPath;
    }

    /**
     * 构建输出目录路径（兼容旧接口）
     *
     * @param baseOutputDir    基础输出目录
     * @param sourceFilePath   源文件路径
     * @param folderCreateRule 文件夹创建规则(1-5)
     * @param quanzongHao      全宗号（已废弃）
     * @param muLuHao          目录号（已废弃）
     * @param nianDu           年度（已废弃）
     * @return 构建后的输出目录路径
     */
    public static String buildOutputDir(String baseOutputDir, String sourceFilePath,
                                        int folderCreateRule, String quanzongHao,
                                        String muLuHao, String nianDu) {
        // 兼容旧调用，忽略废弃参数
        return buildOutputDir(baseOutputDir, sourceFilePath, folderCreateRule, (String) null);
    }

    /**
     * 规则3/4：从 preservePath 构建累积路径
     * 将 "J001/WS/2016/Y/BGS/0005" 转换为累积路径结构
     *
     * 示例：
     * 输入：J001/WS/2016/Y/BGS/0005
     * 输出：J001/J001-WS/J001-WS-2016/J001-WS-2016-Y/J001-WS-2016-Y-BGS/J001-WS-2016-Y-BGS-0005
     *
     * @param preservePath 相对路径（如 J001/WS/2016/Y/BGS/0005）
     * @return 累积路径字符串
     */
    private static String buildCumulativePathFromPreservePath(String preservePath) {
        if (preservePath == null || preservePath.isEmpty()) {
            return "";
        }

        // 标准化路径分隔符
        String normalizedPath = preservePath.replace("\\", "/");
        String[] dirNames = normalizedPath.split("/");

        StringBuilder result = new StringBuilder();
        StringBuilder cumulative = new StringBuilder();

        for (int i = 0; i < dirNames.length; i++) {
            String dirName = dirNames[i];
            if (dirName.isEmpty()) {
                continue;
            }

            if (i > 0) {
                cumulative.append("-");
            }
            cumulative.append(dirName);

            if (i > 0) {
                result.append(File.separator);
            }
            result.append(cumulative.toString());
        }

        return result.toString();
    }

    /**
     * 去掉路径的最后一级目录
     * 例如：J001/WS/2016/Y/BGS/0005 → J001/WS/2016/Y/BGS
     *
     * @param path 相对路径
     * @return 去掉最后一级的路径
     */
    private static String removeLastLevel(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }

        // 标准化路径分隔符
        String normalizedPath = path.replace("\\", "/");

        // 找到最后一个分隔符
        int lastSep = normalizedPath.lastIndexOf('/');
        if (lastSep > 0) {
            return normalizedPath.substring(0, lastSep);
        }

        // 只有一级目录，返回空
        return "";
    }

    /**
     * 构建保持目录结构的相对路径
     * 根据输入目录和文件路径，计算文件相对于输入目录的相对路径
     *
     * @param inputDir  输入目录
     * @param filePath  文件完整路径
     * @return 相对路径（不含文件名）
     */
    public static String buildPreservePath(String inputDir, String filePath) {
        if (inputDir == null || filePath == null) {
            return "";
        }

        // 标准化路径
        String normalizedInputDir = inputDir.replace("\\", "/");
        String normalizedFilePath = filePath.replace("\\", "/");

        // 确保输入目录以/结尾
        if (!normalizedInputDir.endsWith("/")) {
            normalizedInputDir = normalizedInputDir + "/";
        }

        // 计算相对路径
        if (normalizedFilePath.startsWith(normalizedInputDir)) {
            String relativePath = normalizedFilePath.substring(normalizedInputDir.length());
            // 去掉文件名，只保留目录路径
            int lastSlash = relativePath.lastIndexOf('/');
            if (lastSlash > 0) {
                return relativePath.substring(0, lastSlash);
            }
        }

        return "";
    }

    /**
     * 获取PDF文件名（根据输出目录路径）
     *
     * @param outputDirPath   输出目录路径（已构建好的）
     * @param sourceFilePath  源文件路径
     * @param pdfNameSource   命名来源(1-2)
     * @return PDF文件名(不含.pdf扩展名)
     */
    public static String getPdfBaseName(String outputDirPath, String sourceFilePath, int pdfNameSource) {
        // 默认规则1：最后一层文件夹名
        if (pdfNameSource < 1 || pdfNameSource > 2) {
            pdfNameSource = 1;
        }

        File sourceFile = new File(sourceFilePath);
        String sourceFileName = sourceFile.getName();

        // 获取不含扩展名的文件名
        String fileNameWithoutExt = sourceFileName;
        int dotIndex = sourceFileName.lastIndexOf('.');
        if (dotIndex > 0) {
            fileNameWithoutExt = sourceFileName.substring(0, dotIndex);
        }

        switch (pdfNameSource) {
            case 1:
                // 规则1：最后一层文件夹名（从输出目录路径获取）
                File outputDir = new File(outputDirPath);
                String folderName = outputDir.getName();

                // 【修复】检查文件夹名是否有效
                // 1. 文件夹名不能为空
                // 2. 文件夹名长度必须大于2（排除盘符如"D:"）
                // 3. 文件夹名不能与源文件名相同（规则4下输出目录就是基础目录，文件夹名不是档号）
                // 4. 文件夹名不能是通用名称（如"output"、"cs"等非档号名称）
                // 如果文件夹名无效，则使用源文件名
                if (folderName != null && !folderName.isEmpty() && folderName.length() > 2) {
                    // 【关键修复】检查文件夹名是否像档号（包含档号特征）
                    // 档号特征：包含-分隔符，或者包含数字
                    boolean isArchiveLike = folderName.contains("-") ||
                                            folderName.contains("·") ||
                                            folderName.matches(".*\\d+.*");

                    if (isArchiveLike) {
                        // 文件夹名像档号，使用文件夹名
                        return folderName;
                    } else {
                        // 文件夹名不像档号（如"output"、"cs"等），使用源文件名
                        log.debug("文件夹名[{}]不是档号格式，使用源文件名[{}]", folderName, fileNameWithoutExt);
                        return fileNameWithoutExt;
                    }
                } else if (folderName != null && !folderName.isEmpty()) {
                    return fileNameWithoutExt;
                } else {
                    return fileNameWithoutExt;
                }

            case 2:
                // 规则2：图片文件自己的名称
                return fileNameWithoutExt;

            default:
                return fileNameWithoutExt;
        }
    }

    /**
     * 获取PDF文件名（旧方法，保持兼容）
     *
     * @param sourceFilePath 源文件路径
     * @param pdfNameSource  命名来源(1-2)
     * @return PDF文件名(不含.pdf扩展名)
     */
    public static String getPdfBaseName(String sourceFilePath, int pdfNameSource) {
        return getPdfBaseName(sourceFilePath, sourceFilePath, pdfNameSource);
    }

    /**
     * 检测档号格式类型
     * 格式1：J001-2013-Y-03-006（5部分，纯-分隔）
     * 格式2：J001-WS·2016-Y-BGS-0005（包含·分隔符）
     *
     * @param archiveNumber 档号字符串
     * @return 格式类型
     */
    public static ArchiveFormat detectFormat(String archiveNumber) {
        if (archiveNumber == null || archiveNumber.isEmpty()) {
            return ArchiveFormat.FORMAT_1; // 默认格式1
        }

        // 检查是否包含·分隔符
        if (archiveNumber.contains("·")) {
            return ArchiveFormat.FORMAT_2;
        }

        return ArchiveFormat.FORMAT_1;
    }

    /**
     * 解析档号组成部分（自动检测格式）
     *
     * 【修复】支持更多分隔符组合，包括 ·、*、-
     *
     * 格式1：J001-2013-Y-03-006
     *        全宗号-年度-业务类别-案卷号-件号
     *
     * 格式2：J001-WS·2016-Y-BGS-0005
     *        全宗号-门类代码·年度-保管期限-三位机构代码-件号
     *
     * 格式3：WS·2025*D10·A01-0002 （用户实际格式）
     *        门类代码·年度*机构代码·保管期限-件号
     *
     * @param archiveNumber 档号字符串
     * @return 档号组成部分Map
     */
    public static Map<String, String> parseArchiveNumber(String archiveNumber) {
        Map<String, String> parts = new HashMap<>();

        if (archiveNumber == null || archiveNumber.isEmpty()) {
            return parts;
        }

        log.debug("开始解析档号: {}", archiveNumber);

        ArchiveFormat format = detectFormat(archiveNumber);

        if (format == ArchiveFormat.FORMAT_2) {
            // 【修复】格式2和格式3：包含 · 或 * 分隔符
            // 统一处理：先按 - 分隔，再处理 · 和 *

            String[] mainParts = archiveNumber.split("-");

            if (mainParts.length >= 1) {
                parts.put("quanzongHao", mainParts[0]);  // 全宗号或门类代码: J001 或 WS
                log.debug("第1部分(全宗号/门类): {}", mainParts[0]);
            }
            if (mainParts.length >= 2) {
                // 第二部分可能包含门类代码、年度等，如 "WS·2016" 或 "2025*D10"
                String secondPart = mainParts[1];
                if (secondPart.contains("·") || secondPart.contains("*")) {
                    // 【修复】同时处理 · 和 * 分隔符
                    String normalizedPart = secondPart.replace("·", "*");
                    String[] subParts = normalizedPart.split("\\*");
                    parts.put("menLeiDaiMa", subParts[0]);  // 门类代码: WS
                    log.debug("第2部分-1(门类): {}", subParts[0]);
                    if (subParts.length >= 2) {
                        parts.put("niandu", subParts[1]);  // 年度或机构: 2016 或 D10
                        log.debug("第2部分-2(年度/机构): {}", subParts[1]);
                    }
                    if (subParts.length >= 3) {
                        parts.put("jiGouDaiMa", subParts[2]);  // 机构代码: A01
                        log.debug("第2部分-3(机构): {}", subParts[2]);
                    }
                } else {
                    parts.put("menLeiDaiMa", secondPart);
                    log.debug("第2部分(门类): {}", secondPart);
                }
            }
            if (mainParts.length >= 3) {
                parts.put("baoGuanQiXian", mainParts[2]);  // 保管期限: Y
                log.debug("第3部分(保管期限): {}", mainParts[2]);
            }
            if (mainParts.length >= 4) {
                parts.put("jiGouDaiMa", mainParts[3]);  // 三位机构代码: BGS
                log.debug("第4部分(机构): {}", mainParts[3]);
            }
            if (mainParts.length >= 5) {
                parts.put("jianHao", mainParts[4]);  // 件号: 0005
                log.debug("第5部分(件号): {}", mainParts[4]);
            }
        } else {
            // 格式1：J001-2013-Y-03-006
            String[] segments = archiveNumber.split("-");

            if (segments.length >= 1) {
                parts.put("quanzongHao", segments[0]);  // 全宗号: J001
            }
            if (segments.length >= 2) {
                parts.put("niandu", segments[1]);  // 年度: 2013
            }
            if (segments.length >= 3) {
                parts.put("yeWuLeiBie", segments[2]);  // 业务类别: Y
            }
            if (segments.length >= 4) {
                parts.put("anJuanHao", segments[3]);  // 案卷号: 03
            }
            if (segments.length >= 5) {
                parts.put("jianHao", segments[4]);  // 件号: 006
            }
        }

        log.info("解析档号完成: {} -> {}", archiveNumber, parts);
        return parts;
    }

    /**
     * 获取档号各部分列表（按顺序）
     * 用于构建累积路径
     *
     * 【修复】支持多种分隔符：·、*、-
     * 处理格式如：WS·2025*D10·A01-0002
     *
     * @param archiveNumber 档号字符串
     * @return 档号各部分列表
     */
    public static List<String> getArchiveParts(String archiveNumber) {
        List<String> parts = new ArrayList<>();

        if (archiveNumber == null || archiveNumber.isEmpty()) {
            return parts;
        }

        log.debug("开始解析档号: {}", archiveNumber);

        // 【修复】统一处理多种分隔符：·、*、-
        // 先用 - 分隔，然后对每个部分进一步处理 · 和 *
        String[] mainParts = archiveNumber.split("-");

        for (String part : mainParts) {
            if (part.contains("·") || part.contains("*")) {
                // 【修复】同时处理 · 和 * 分隔符
                // 将 · 替换为 * 后统一处理
                String normalizedPart = part.replace("·", "*");
                String[] subParts = normalizedPart.split("\\*");  // 转义 * 字符
                for (String subPart : subParts) {
                    if (!subPart.isEmpty()) {
                        parts.add(subPart);
                        log.debug("添加档号部分: {}", subPart);
                    }
                }
            } else {
                if (!part.isEmpty()) {
                    parts.add(part);
                    log.debug("添加档号部分: {}", part);
                }
            }
        }

        log.debug("档号解析结果: {} -> {}", archiveNumber, parts);
        return parts;
    }

    /**
     * 获取不含扩展名的文件名
     */
    private static String getFileNameWithoutExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "unnamed";
        }
        int lastDotIndex = fileName.lastIndexOf(".");
        if (lastDotIndex == -1) {
            return fileName;
        }
        return fileName.substring(0, lastDotIndex);
    }

    /**
     * 构建完整的输出PDF路径
     *
     * @param baseOutputDir    基础输出目录
     * @param sourceFilePath   源文件路径
     * @param folderCreateRule 文件夹创建规则(0-8)
     * @param pdfNameSource    命名来源(1-2)
     * @param preservePath     保持目录结构的相对路径（规则5/6/7/8使用）
     * @param inputDir         输入目录（规则3/4/6/7/8使用）
     * @param filenamePrefix   文件名前缀
     * @param filenameSuffix   文件名后缀
     * @return 完整的输出PDF路径
     */
    public static String buildOutputPdfPath(String baseOutputDir, String sourceFilePath,
                                            int folderCreateRule, int pdfNameSource,
                                            String preservePath,
                                            String filenamePrefix, String filenameSuffix) {
        return buildOutputPdfPath(baseOutputDir, sourceFilePath, folderCreateRule, pdfNameSource,
                preservePath, null, filenamePrefix, filenameSuffix);
    }

    /**
     * 构建完整的输出PDF路径（支持规则3/4/6/7）
     *
     * @param baseOutputDir    基础输出目录
     * @param sourceFilePath   源文件路径
     * @param folderCreateRule 文件夹创建规则(0-8)
     * @param pdfNameSource    命名来源(1-2)
     * @param preservePath     保持目录结构的相对路径（规则3/4/5/6/7/8使用）
     * @param inputDir         输入目录（规则3/4/6/7/8使用）
     * @param filenamePrefix   文件名前缀
     * @param filenameSuffix   文件名后缀
     * @return 完整的输出PDF路径
     */
    public static String buildOutputPdfPath(String baseOutputDir, String sourceFilePath,
                                            int folderCreateRule, int pdfNameSource,
                                            String preservePath, String inputDir,
                                            String filenamePrefix, String filenameSuffix) {
        // 构建输出目录
        String outputDir = buildOutputDir(baseOutputDir, sourceFilePath, folderCreateRule, preservePath);

        // 获取PDF基础文件名
        String baseName;
        if ((folderCreateRule == 3 || folderCreateRule == 4 || folderCreateRule == 6 || folderCreateRule == 7)
                && inputDir != null && !inputDir.isEmpty()) {
            // 规则3/4/6/7：使用路径层级命名（不去前导零）
            baseName = buildPdfNameFromPath(inputDir, sourceFilePath);
        } else if (folderCreateRule == 8 && preservePath != null && !preservePath.isEmpty()) {
            // 规则8：使用preservePath的最后一层文件夹名（档号）作为PDF文件名
            // 示例：preservePath = "1983/永久/szy-01-0001/szy-01-0001-0001"
            //       提取最后一层：szy-01-0001-0001
            String[] pathParts = preservePath.split("[/\\\\]");
            if (pathParts.length > 0) {
                baseName = pathParts[pathParts.length - 1]; // 使用最后一层文件夹名
            } else {
                baseName = getFileNameWithoutExtension(new File(sourceFilePath).getName());
            }
        } else if (folderCreateRule == 5) {
            // 规则5：保持输入目录结构，使用源文件名（避免同一文件夹下的文件覆盖）
            baseName = getFileNameWithoutExtension(new File(sourceFilePath).getName());
        } else {
            baseName = getPdfBaseName(outputDir, sourceFilePath, pdfNameSource);
        }

        // 规则3：不再额外创建文件夹，已在buildOutputDir中去掉件号层

        // 应用前缀和后缀
        StringBuilder fileNameBuilder = new StringBuilder();
        if (filenamePrefix != null && !filenamePrefix.isEmpty()) {
            fileNameBuilder.append(filenamePrefix);
        }
        fileNameBuilder.append(baseName);
        if (filenameSuffix != null && !filenameSuffix.isEmpty()) {
            fileNameBuilder.append(filenameSuffix);
        }
        fileNameBuilder.append(".pdf");

        String outputPdfPath = outputDir + File.separator + fileNameBuilder.toString();
        log.info("构建输出PDF路径: {}", outputPdfPath);

        return outputPdfPath;
    }

    /**
     * 构建完整的输出PDF路径（兼容旧接口）
     *
     * @param baseOutputDir    基础输出目录
     * @param sourceFilePath   源文件路径
     * @param folderCreateRule 文件夹创建规则(1-5)
     * @param pdfNameSource    命名来源(1-2)
     * @param quanzongHao      全宗号（已废弃）
     * @param muLuHao          目录号（已废弃）
     * @param nianDu           年度（已废弃）
     * @param filenamePrefix   文件名前缀
     * @param filenameSuffix   文件名后缀
     * @return 完整的输出PDF路径
     */
    public static String buildOutputPdfPath(String baseOutputDir, String sourceFilePath,
                                            int folderCreateRule, int pdfNameSource,
                                            String quanzongHao, String muLuHao, String nianDu,
                                            String filenamePrefix, String filenameSuffix) {
        // 兼容旧调用，忽略废弃参数
        return buildOutputPdfPath(baseOutputDir, sourceFilePath, folderCreateRule, pdfNameSource,
                (String) null, filenamePrefix, filenameSuffix);
    }

    /**
     * 规则6/7：根据输入目录路径构建PDF名称
     * 从输入目录提取子目录层级，用"-"连接（不去前导零）
     *
     * 示例：
     * 输入目录：D:\cs
     * 源文件：D:\cs\J001\WS\2016\Y\BGS\0005\8931.jpg
     * 输出PDF名：J001-WS-2016-Y-BGS-0005
     *
     * @param inputDir      输入目录（选择的文件夹）
     * @param sourceFilePath 源文件完整路径
     * @return PDF基础文件名（不含.pdf扩展名）
     */
    public static String buildPdfNameFromPath(String inputDir, String sourceFilePath) {
        log.info("buildPdfNameFromPath调用: inputDir={}, sourceFilePath={}", inputDir, sourceFilePath);

        if (inputDir == null || inputDir.isEmpty() || sourceFilePath == null || sourceFilePath.isEmpty()) {
            log.warn("buildPdfNameFromPath: 参数为空，返回unnamed");
            return "unnamed";
        }

        // 标准化路径（统一使用/分隔符）
        String normalizedInputDir = inputDir.replace("\\", "/");
        String normalizedFilePath = sourceFilePath.replace("\\", "/");

        // 确保输入目录以/结尾
        if (!normalizedInputDir.endsWith("/")) {
            normalizedInputDir = normalizedInputDir + "/";
        }

        log.debug("标准化后: inputDir={}, filePath={}", normalizedInputDir, normalizedFilePath);

        // 计算相对路径
        if (!normalizedFilePath.startsWith(normalizedInputDir)) {
            log.warn("源文件路径[{}]不在输入目录[{}]下，使用原文件名", sourceFilePath, inputDir);
            File sourceFile = new File(sourceFilePath);
            return getFileNameWithoutExtension(sourceFile.getName());
        }

        String relativePath = normalizedFilePath.substring(normalizedInputDir.length());
        log.info("相对路径: {}", relativePath);

        // 去掉文件名，只保留目录路径
        int lastSlash = relativePath.lastIndexOf('/');
        if (lastSlash <= 0) {
            log.warn("没有子目录层级，使用原文件名");
            return getFileNameWithoutExtension(new File(sourceFilePath).getName());
        }

        String dirPath = relativePath.substring(0, lastSlash);
        String[] dirNames = dirPath.split("/");

        // 构建PDF名称：用"-"连接各层级（不去前导零）
        StringBuilder nameBuilder = new StringBuilder();
        for (int i = 0; i < dirNames.length; i++) {
            String dirName = dirNames[i];
            if (i > 0) {
                nameBuilder.append("-");
            }
            nameBuilder.append(dirName);
        }

        String result = nameBuilder.toString();
        log.info("规则3/4/6/7构建PDF名称: 输入目录={}, 源文件={}, 结果={}", inputDir, sourceFilePath, result);
        return result;
    }

    }