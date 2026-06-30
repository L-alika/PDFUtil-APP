package com.pdfutil.common.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;

/**
 * 设备授权工具类 - MAC地址绑定（离线授权码模式）
 *
 * 授权流程：
 * 1. 用户首次启动，获取MAC地址
 * 2. 用户提交MAC地址给管理员
 * 3. 管理员使用授权生成工具创建授权码
 * 4. 用户输入授权码完成激活
 *
 * @author Alika
 * @date 2025-03-04
 */
public class LicenseUtils {

    private static final Logger log = LoggerFactory.getLogger(LicenseUtils.class);

    // AES加密密钥（16位）- 生产环境请修改为自定义密钥
    private static final String SECRET_KEY = "PdfUtil2025@Key!";

    // 授权文件名称
    private static final String LICENSE_FILE = "license.dat";

    /**
     * 获取本机所有物理网卡的MAC地址
     */
    public static List<String> getMacAddresses() {
        List<String> macList = new ArrayList<>();
        List<String> virtualMacList = new ArrayList<>(); // 备选：虚拟网卡MAC
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();

                // 只排除回环接口（不检查isUp，因为MAC地址是硬件地址，与是否联网无关）
                if (ni.isLoopback()) {
                    continue;
                }

                byte[] mac = ni.getHardwareAddress();
                if (mac != null && mac.length == 6) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < mac.length; i++) {
                        sb.append(String.format("%02X", mac[i]));
                        if (i < mac.length - 1) {
                            sb.append("-");
                        }
                    }
                    String macAddress = sb.toString();
                    // 区分物理网卡和虚拟网卡
                    if (isVirtualMac(macAddress)) {
                        virtualMacList.add(macAddress);
                        log.info("检测到虚拟网卡: {} - {}", ni.getName(), macAddress);
                    } else {
                        macList.add(macAddress);
                        log.info("检测到物理网卡: {} - {}", ni.getName(), macAddress);
                    }
                }
            }

            // 如果没有找到物理网卡，使用虚拟网卡作为备选
            if (macList.isEmpty() && !virtualMacList.isEmpty()) {
                log.warn("未找到物理网卡，使用虚拟网卡MAC作为备选");
                macList.addAll(virtualMacList);
            }
        } catch (Exception e) {
            log.error("获取MAC地址失败", e);
        }
        return macList;
    }

    /**
     * 获取主MAC地址（第一个物理网卡）
     * 如果无法获取MAC地址，生成基于机器特征的唯一标识
     */
    public static String getPrimaryMacAddress() {
        List<String> macs = getMacAddresses();
        if (!macs.isEmpty()) {
            return macs.get(0);
        }

        // 完全没有网卡时的备选方案：生成机器唯一标识
        log.warn("无法获取任何网卡MAC地址，使用机器特征生成唯一标识");
        return generateMachineId();
    }

    /**
     * 基于机器特征生成唯一标识（作为无法获取MAC时的备选）
     */
    private static String generateMachineId() {
        try {
            StringBuilder sb = new StringBuilder();

            // 1. 机器名
            String hostname = System.getenv("COMPUTERNAME");
            if (hostname == null) {
                hostname = System.getenv("HOSTNAME");
            }
            if (hostname == null) {
                hostname = System.getProperty("user.name");
            }
            if (hostname != null) {
                sb.append(hostname);
            }

            // 2. 用户主目录路径
            String home = System.getProperty("user.home");
            if (home != null) {
                sb.append(home);
            }

            // 3. 操作系统信息
            sb.append(System.getProperty("os.name"));
            sb.append(System.getProperty("os.version"));

            // 生成哈希作为唯一标识
            String hash = String.format("%016X", Math.abs(sb.toString().hashCode()));

            // 格式化为类似MAC地址的格式：XX-XX-XX-XX-XX-XX
            StringBuilder macFormat = new StringBuilder();
            for (int i = 0; i < 12; i += 2) {
                if (i > 0) macFormat.append("-");
                macFormat.append(hash.substring(i, i + 2));
            }

            String machineId = macFormat.toString().toUpperCase();
            log.info("生成机器唯一标识: {}", machineId);
            return machineId;

        } catch (Exception e) {
            log.error("生成机器ID失败", e);
            // 最后的备选：固定格式 + 随机数
            return "FF-FF-FF-" + String.format("%02X-%02X-%02X",
                (int)(Math.random() * 256),
                (int)(Math.random() * 256),
                (int)(Math.random() * 256));
        }
    }

    /**
     * 检查是否为虚拟网卡MAC
     */
    private static boolean isVirtualMac(String mac) {
        // VMware MAC前缀
        if (mac.startsWith("00-50-56") || mac.startsWith("00-0C-29") ||
            mac.startsWith("00-05-69") || mac.startsWith("00-03-FF")) {
            return true;
        }
        // VirtualBox MAC前缀
        if (mac.startsWith("08-00-27")) {
            return true;
        }
        // Hyper-V MAC前缀
        if (mac.startsWith("00-15-5D")) {
            return true;
        }
        // 通用虚拟机
        if (mac.startsWith("00-00-5E") || mac.startsWith("52-54-00")) {
            return true;
        }
        return false;
    }

    /**
     * 生成授权码（管理员使用）
     *
     * @param macAddress 用户提交的MAC地址
     * @param expireDays 授权有效期（天数，0表示永久）
     * @return 授权码
     */
    public static String generateLicenseCode(String macAddress, int expireDays) {
        try {
            LicenseInfo info = new LicenseInfo();
            info.setMacAddress(macAddress.toUpperCase().trim());
            info.setBindDate(System.currentTimeMillis());
            info.setExpireDate(expireDays > 0 ?
                System.currentTimeMillis() + (expireDays * 24L * 60 * 60 * 1000) : 0);

            // 序列化并加密
            String json = toJson(info);
            String encrypted = encrypt(json);

            // 格式化授权码（每4个字符加横线，便于输入）
            return formatLicenseCode(encrypted);

        } catch (Exception e) {
            log.error("生成授权码失败", e);
            throw new RuntimeException("生成授权码失败: " + e.getMessage());
        }
    }

    /**
     * 验证授权码
     *
     * @param licenseCode 用户输入的授权码
     * @return 验证结果
     */
    public static LicenseResult validateLicenseCode(String licenseCode) {
        if (StringUtils.isEmpty(licenseCode)) {
            return LicenseResult.error("授权码不能为空");
        }

        try {
            // 去除格式化字符
            String encrypted = unformatLicenseCode(licenseCode);

            // 解密
            String json = decrypt(encrypted);
            LicenseInfo info = fromJson(json);

            // 检查是否过期
            if (info.getExpireDate() > 0 && System.currentTimeMillis() > info.getExpireDate()) {
                return LicenseResult.error("授权码已过期");
            }

            // 获取当前MAC地址
            List<String> currentMacs = getMacAddresses();

            // 验证MAC地址是否匹配
            String licensedMac = info.getMacAddress();
            boolean matched = false;

            for (String currentMac : currentMacs) {
                if (currentMac.equalsIgnoreCase(licensedMac)) {
                    matched = true;
                    break;
                }
            }

            if (!matched) {
                log.warn("MAC地址不匹配");
                log.warn("授权MAC: {}", licensedMac);
                log.warn("当前MAC: {}", currentMacs);
                return LicenseResult.error("授权码与当前设备不匹配");
            }

            return LicenseResult.success(info);

        } catch (Exception e) {
            log.error("验证授权码失败", e);
            return LicenseResult.error("授权码无效或已损坏");
        }
    }

    /**
     * 保存授权码到文件
     */
    public static boolean saveLicense(String licenseDir, String licenseCode) {
        try {
            File dir = new File(licenseDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            File licenseFile = new File(dir, LICENSE_FILE);
            try (FileWriter writer = new FileWriter(licenseFile)) {
                writer.write(licenseCode);
            }

            log.info("授权码已保存: {}", licenseFile.getAbsolutePath());
            return true;

        } catch (Exception e) {
            log.error("保存授权码失败", e);
            return false;
        }
    }

    /**
     * 从文件读取授权码
     */
    public static String loadLicenseFromFile(String licenseDir) {
        File licenseFile = new File(licenseDir, LICENSE_FILE);
        if (!licenseFile.exists()) {
            return null;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(licenseFile))) {
            return reader.readLine();
        } catch (Exception e) {
            log.error("读取授权码失败", e);
            return null;
        }
    }

    /**
     * 检查是否已授权
     */
    public static boolean isLicensed(String licenseDir) {
        String licenseCode = loadLicenseFromFile(licenseDir);
        if (StringUtils.isEmpty(licenseCode)) {
            return false;
        }
        return validateLicenseCode(licenseCode).isValid();
    }

    /**
     * 验证授权（从文件）
     */
    public static LicenseResult validateLicense(String licenseDir) {
        String licenseCode = loadLicenseFromFile(licenseDir);
        if (StringUtils.isEmpty(licenseCode)) {
            return LicenseResult.error("未找到授权文件");
        }
        return validateLicenseCode(licenseCode);
    }

    // ==================== 授权码格式化 ====================

    /**
     * 格式化授权码 - 使用原始加密字符串
     */
    private static String formatLicenseCode(String encrypted) {
        // 直接使用原始加密字符串，不做额外格式化
        return encrypted;
    }

    /**
     * 去除授权码格式 - 移除所有空白字符（空格、换行、回车、制表符），不移除Base64中的 - 和 _
     */
    private static String unformatLicenseCode(String formatted) {
        if (StringUtils.isEmpty(formatted)) {
            return "";
        }
        // 移除所有空白字符：空格、制表符、换行符、回车符
        return formatted.replaceAll("\\s+", "");
    }

    // ==================== 加密/解密方法 ====================

    private static String encrypt(String plainText) throws Exception {
        SecretKeySpec key = new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        // 使用URL安全的Base64编码
        return Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
    }

    private static String decrypt(String encrypted) throws Exception {
        SecretKeySpec key = new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, key);
        byte[] decrypted = cipher.doFinal(Base64.getUrlDecoder().decode(encrypted));
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    // ==================== JSON序列化（简化版） ====================

    private static String toJson(LicenseInfo info) {
        return "{" +
            "\"macAddress\":\"" + info.getMacAddress() + "\"," +
            "\"bindDate\":" + info.getBindDate() + "," +
            "\"expireDate\":" + info.getExpireDate() +
            "}";
    }

    private static LicenseInfo fromJson(String json) {
        LicenseInfo info = new LicenseInfo();
        try {
            json = json.replace("{", "").replace("}", "").replace("\"", "");
            String[] parts = json.split(",");

            for (String part : parts) {
                String[] kv = part.split(":", 2);
                if (kv.length == 2) {
                    String key = kv[0].trim();
                    String value = kv[1].trim();
                    switch (key) {
                        case "macAddress":
                            info.setMacAddress(value);
                            break;
                        case "bindDate":
                            info.setBindDate(Long.parseLong(value));
                            break;
                        case "expireDate":
                            info.setExpireDate(Long.parseLong(value));
                            break;
                    }
                }
            }
        } catch (Exception e) {
            log.error("解析授权信息失败", e);
        }
        return info;
    }

    // ==================== 内部类 ====================

    public static class LicenseInfo {
        private String macAddress;
        private long bindDate;
        private long expireDate;

        public String getMacAddress() { return macAddress; }
        public void setMacAddress(String macAddress) { this.macAddress = macAddress; }
        public long getBindDate() { return bindDate; }
        public void setBindDate(long bindDate) { this.bindDate = bindDate; }
        public long getExpireDate() { return expireDate; }
        public void setExpireDate(long expireDate) { this.expireDate = expireDate; }

        /**
         * 获取过期日期字符串
         */
        public String getExpireDateString() {
            if (expireDate == 0) {
                return "永久授权";
            }
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
            return sdf.format(new java.util.Date(expireDate));
        }
    }

    public static class LicenseResult {
        private boolean valid;
        private String message;
        private LicenseInfo licenseInfo;

        public static LicenseResult success(LicenseInfo info) {
            LicenseResult r = new LicenseResult();
            r.valid = true;
            r.message = "授权验证成功";
            r.licenseInfo = info;
            return r;
        }

        public static LicenseResult error(String msg) {
            LicenseResult r = new LicenseResult();
            r.valid = false;
            r.message = msg;
            return r;
        }

        public boolean isValid() { return valid; }
        public String getMessage() { return message; }
        public LicenseInfo getLicenseInfo() { return licenseInfo; }
    }
}
