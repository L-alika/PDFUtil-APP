package com.pdfutil.pdf.controller;

import com.pdfutil.common.core.domain.AjaxResult;
import com.pdfutil.common.utils.LicenseUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备授权控制器 - 离线授权码模式
 *
 * 授权流程：
 * 1. 用户获取MAC地址
 * 2. 管理员根据MAC生成授权码
 * 3. 用户输入授权码激活
 *
 * @author Alika
 * @date 2025-03-04
 */
@RestController
@RequestMapping("/api/license")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class LicenseController {

    private static final Logger log = LoggerFactory.getLogger(LicenseController.class);

    /**
     * 获取授权文件存储路径
     */
    private String getLicenseDir() {
        String configPath = System.getProperty("user.home") + "/.pdfutil/license";
        File dir = new File(configPath);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return configPath;
    }

    /**
     * 获取本机MAC地址列表
     */
    @GetMapping("/mac-addresses")
    public AjaxResult getMacAddresses() {
        try {
            List<String> macs = LicenseUtils.getMacAddresses();
            String primaryMac = LicenseUtils.getPrimaryMacAddress();

            Map<String, Object> result = new HashMap<>();
            result.put("macAddresses", macs);
            result.put("primaryMac", primaryMac);
            result.put("suggestMac", primaryMac); // 建议使用的MAC地址

            return AjaxResult.success(result);
        } catch (Exception e) {
            log.error("获取MAC地址失败", e);
            return AjaxResult.error("获取MAC地址失败: " + e.getMessage());
        }
    }

    /**
     * 使用授权码激活设备
     */
    @PostMapping("/activate")
    public AjaxResult activateDevice(@RequestBody Map<String, String> params) {
        String licenseCode = params.get("licenseCode");

        if (licenseCode == null || licenseCode.trim().isEmpty()) {
            return AjaxResult.error("授权码不能为空");
        }

        try {
            // 验证授权码
            LicenseUtils.LicenseResult result = LicenseUtils.validateLicenseCode(licenseCode.trim());

            if (!result.isValid()) {
                return AjaxResult.error(result.getMessage());
            }

            // 保存授权码到文件
            String licenseDir = getLicenseDir();
            boolean saved = LicenseUtils.saveLicense(licenseDir, licenseCode.trim());

            if (!saved) {
                return AjaxResult.error("激活失败：无法保存授权信息");
            }

            // 返回授权信息
            Map<String, Object> data = new HashMap<>();
            data.put("message", "设备激活成功");
            data.put("macAddress", result.getLicenseInfo().getMacAddress());
            data.put("expireDate", result.getLicenseInfo().getExpireDateString());

            log.info("设备激活成功，MAC: {}", result.getLicenseInfo().getMacAddress());
            return AjaxResult.success(data);

        } catch (Exception e) {
            log.error("激活设备失败", e);
            return AjaxResult.error("激活失败: " + e.getMessage());
        }
    }

    /**
     * 验证当前授权状态
     */
    @GetMapping("/validate")
    public AjaxResult validateLicense() {
        try {
            String licenseDir = getLicenseDir();
            LicenseUtils.LicenseResult result = LicenseUtils.validateLicense(licenseDir);

            Map<String, Object> data = new HashMap<>();
            data.put("valid", result.isValid());
            data.put("message", result.getMessage());

            if (result.isValid() && result.getLicenseInfo() != null) {
                data.put("macAddress", result.getLicenseInfo().getMacAddress());
                data.put("expireDate", result.getLicenseInfo().getExpireDateString());
            } else {
                // 未授权时返回当前MAC地址
                data.put("currentMac", LicenseUtils.getPrimaryMacAddress());
                data.put("allMacs", LicenseUtils.getMacAddresses());
            }

            return AjaxResult.success(data);

        } catch (Exception e) {
            log.error("验证授权失败", e);
            return AjaxResult.error("验证失败: " + e.getMessage());
        }
    }

    /**
     * 获取授权状态（简化接口）
     */
    @GetMapping("/status")
    public AjaxResult getLicenseStatus() {
        String licenseDir = getLicenseDir();
        boolean licensed = LicenseUtils.isLicensed(licenseDir);

        Map<String, Object> result = new HashMap<>();
        result.put("licensed", licensed);

        if (!licensed) {
            result.put("needActivate", true);
            result.put("currentMac", LicenseUtils.getPrimaryMacAddress());
        }

        return AjaxResult.success(result);
    }

    /**
     * 生成授权码（管理员接口）
     * 注意：生产环境应该添加管理员权限验证
     */
    @PostMapping("/generate")
    public AjaxResult generateLicenseCode(@RequestBody Map<String, Object> params) {
        String macAddress = (String) params.get("macAddress");
        Integer expireDays = (Integer) params.getOrDefault("expireDays", 0);

        if (macAddress == null || macAddress.trim().isEmpty()) {
            return AjaxResult.error("MAC地址不能为空");
        }

        try {
            // 生成授权码
            String licenseCode = LicenseUtils.generateLicenseCode(macAddress.trim(), expireDays);

            Map<String, Object> data = new HashMap<>();
            data.put("licenseCode", licenseCode);
            data.put("macAddress", macAddress.trim().toUpperCase());
            data.put("expireDays", expireDays);
            data.put("expireDate", expireDays > 0 ?
                new java.text.SimpleDateFormat("yyyy-MM-dd").format(
                    new java.util.Date(System.currentTimeMillis() + expireDays * 24L * 60 * 60 * 1000))
                : "永久授权");

            log.info("生成授权码成功，MAC: {}", macAddress);
            return AjaxResult.success(data);

        } catch (Exception e) {
            log.error("生成授权码失败", e);
            return AjaxResult.error("生成授权码失败: " + e.getMessage());
        }
    }

    /**
     * 删除授权（解绑设备）
     */
    @DeleteMapping("/unbind")
    public AjaxResult unbindDevice() {
        try {
            String licenseDir = getLicenseDir();
            File licenseFile = new File(licenseDir, "license.dat");

            if (licenseFile.exists()) {
                boolean deleted = licenseFile.delete();
                if (deleted) {
                    log.info("设备已解绑");
                    return AjaxResult.success("设备已解绑");
                } else {
                    return AjaxResult.error("解绑失败：无法删除授权文件");
                }
            } else {
                return AjaxResult.success("设备未绑定");
            }

        } catch (Exception e) {
            log.error("解绑失败", e);
            return AjaxResult.error("解绑失败: " + e.getMessage());
        }
    }
}
