package com.pdfutil.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 全局配置类
 *
 * @author Alika
 */
@Component
@ConfigurationProperties(prefix = "pdfutil")
public class PdfUtilConfig
{
    /** 项目名称 */
    private static String name;

    /** 版本 */
    private static String version;

    /** 版权年份 */
    private static String copyrightYear;

    /** 实例演示开关 */
    private static boolean demoEnabled;

    /** 上传路径 */
    private static String profile;

    /** 获取地址开关 */
    private static boolean addressEnabled;

    /** PDF文件上传目录 */
    private static String pdfUploadDir;

    /** PDF转换输出目录 */
    private static String pdfOutputDir;

    /** PDF数据存储目录 */
    private static String pdfDataDir;

    public static String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        PdfUtilConfig.name = name;
    }

    public static String getVersion()
    {
        return version;
    }

    public void setVersion(String version)
    {
        PdfUtilConfig.version = version;
    }

    public static String getCopyrightYear()
    {
        return copyrightYear;
    }

    public void setCopyrightYear(String copyrightYear)
    {
        PdfUtilConfig.copyrightYear = copyrightYear;
    }

    public static boolean isDemoEnabled()
    {
        return demoEnabled;
    }

    public void setDemoEnabled(boolean demoEnabled)
    {
        PdfUtilConfig.demoEnabled = demoEnabled;
    }

    public static String getProfile()
    {
        return profile;
    }

    public void setProfile(String profile)
    {
        PdfUtilConfig.profile = profile;
    }

    public static boolean isAddressEnabled()
    {
        return addressEnabled;
    }

    public void setAddressEnabled(boolean addressEnabled)
    {
        PdfUtilConfig.addressEnabled = addressEnabled;
    }

    /**
     * 获取导入上传路径
     */
    public static String getImportPath()
    {
        return getProfile() + "/import";
    }

    /**
     * 获取头像上传路径
     */
    public static String getAvatarPath()
    {
        return getProfile() + "/avatar";
    }

    /**
     * 获取下载路径
     */
    public static String getDownloadPath()
    {
        return getProfile() + "/download/";
    }

    /**
     * 获取上传路径
     */
    public static String getUploadPath()
    {
        return getProfile() + "/upload";
    }

    /**
     * 获取PDF上传目录
     */
    public static String getPdfUploadDir()
    {
        return pdfUploadDir;
    }

    public void setPdfUploadDir(String pdfUploadDir)
    {
        PdfUtilConfig.pdfUploadDir = pdfUploadDir;
    }

    /**
     * 获取PDF输出目录
     */
    public static String getPdfOutputDir()
    {
        return pdfOutputDir;
    }

    public void setPdfOutputDir(String pdfOutputDir)
    {
        PdfUtilConfig.pdfOutputDir = pdfOutputDir;
    }

    /**
     * 获取PDF数据存储目录
     */
    public static String getPdfDataDir()
    {
        return pdfDataDir;
    }

    public void setPdfDataDir(String pdfDataDir)
    {
        PdfUtilConfig.pdfDataDir = pdfDataDir;
    }
}
