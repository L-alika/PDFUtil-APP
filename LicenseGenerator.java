import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 格式转换工具 - 授权码生成器
 *
 * 使用方法:
 * 1. 编译: javac LicenseGenerator.java
 * 2. 运行: java LicenseGenerator
 *
 * 输入用户提供的MAC地址，生成授权码
 *
 * @author Alika
 * @date 2025-03-04
 */
public class LicenseGenerator {

    // AES加密密钥（必须与 LicenseUtils.java 中的密钥一致）
    private static final String SECRET_KEY = "PdfUtil2025@Key!";

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  格式转换工具 - 授权码生成器");
        System.out.println("========================================\n");

        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

            // 输入MAC地址
            System.out.print("请输入设备MAC地址 (格式: XX-XX-XX-XX-XX-XX): ");
            String macAddress = reader.readLine().trim();

            // 验证MAC地址格式
            if (!isValidMac(macAddress)) {
                System.err.println("错误: MAC地址格式不正确!");
                System.err.println("正确格式: XX-XX-XX-XX-XX-XX 或 XX:XX:XX:XX:XX:XX");
                return;
            }

            // 标准化MAC地址
            macAddress = macAddress.replace(":", "-").toUpperCase().trim();

            // 输入有效期
            System.out.print("请输入授权有效期 (天数，0表示永久): ");
            String daysStr = reader.readLine().trim();
            int expireDays;
            try {
                expireDays = Integer.parseInt(daysStr);
                if (expireDays < 0) {
                    expireDays = 0;
                }
            } catch (NumberFormatException e) {
                System.out.println("输入无效，使用默认值: 永久授权");
                expireDays = 0;
            }

            // 生成授权信息
            long bindDate = System.currentTimeMillis();
            long expireDate = expireDays > 0 ?
                bindDate + (expireDays * 24L * 60 * 60 * 1000) : 0;

            // 构建JSON
            String json = "{" +
                "\"macAddress\":\"" + macAddress + "\"," +
                "\"bindDate\":" + bindDate + "," +
                "\"expireDate\":" + expireDate +
                "}";

            // 加密
            String encrypted = encrypt(json);

            // 格式化授权码
            String licenseCode = formatLicenseCode(encrypted);

            // 输出结果
            System.out.println("\n----------------------------------------");
            System.out.println("  授权码生成成功!");
            System.out.println("----------------------------------------");
            System.out.println("\nMAC地址: " + macAddress);
            System.out.println("授权期限: " + (expireDays > 0 ? expireDays + " 天" : "永久授权"));
            if (expireDays > 0) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
                System.out.println("过期日期: " + sdf.format(new Date(expireDate)));
            }
            System.out.println("\n========== 授权码 (请完整复制) ==========");
            // 每50字符一行，便于复制
            for (int i = 0; i < licenseCode.length(); i += 50) {
                int end = Math.min(i + 50, licenseCode.length());
                System.out.println(licenseCode.substring(i, end));
            }
            System.out.println("========================================");
            System.out.println("长度: " + licenseCode.length() + " 字符\n");

            // 验证生成的授权码
            System.out.println("正在验证授权码...");
            String verifyResult = decrypt(unformatLicenseCode(licenseCode));
            System.out.println("验证成功! 授权信息: " + verifyResult);

        } catch (Exception e) {
            System.err.println("生成授权码失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 验证MAC地址格式
     */
    private static boolean isValidMac(String mac) {
        if (mac == null || mac.isEmpty()) {
            return false;
        }
        // 支持格式: XX-XX-XX-XX-XX-XX 或 XX:XX:XX:XX:XX:XX
        String regex = "^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$";
        return mac.matches(regex);
    }

    /**
     * 格式化授权码 - 使用标准Base64（不含-和_）
     */
    private static String formatLicenseCode(String encrypted) {
        // 直接使用原始加密字符串，不做额外格式化
        // 避免Base64 URL编码的 - 和 _ 字符造成混淆
        return encrypted;
    }

    /**
     * 去除格式化 - 只移除空格，不移除Base64中的 - 和 _
     */
    /**
     * 去除格式化 - 移除所有空白字符，不移除Base64中的 - 和 _
     */
    private static String unformatLicenseCode(String formatted) {
        if (formatted == null || formatted.isEmpty()) {
            return "";
        }
        // 移除所有空白字符
        return formatted.replaceAll("\\s+", "");
    }

    /**
     * AES加密
     */
    private static String encrypt(String plainText) throws Exception {
        SecretKeySpec key = new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
    }

    /**
     * AES解密
     */
    private static String decrypt(String encrypted) throws Exception {
        SecretKeySpec key = new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, key);
        byte[] decrypted = cipher.doFinal(Base64.getUrlDecoder().decode(encrypted));
        return new String(decrypted, StandardCharsets.UTF_8);
    }
}
