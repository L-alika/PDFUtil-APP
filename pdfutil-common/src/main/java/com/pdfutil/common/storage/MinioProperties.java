package com.pdfutil.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MinIO 对象存储配置属性
 *
 * @author Alika
 */
@Component
@ConfigurationProperties(prefix = "pdfutil.minio")
public class MinioProperties {

    /** 是否启用 MinIO 存储（false 时降级为本地存储） */
    private boolean enabled = true;

    /** MinIO 服务端点 (API地址) */
    private String endpoint = "http://127.0.0.1:9000";

    /** Access Key (用户名) */
    private String accessKey = "pdfutil";

    /** Secret Key (密码) */
    private String secretKey = "pdfutil123456";

    /** 原始文件存储桶 */
    private String bucketRaw = "pdfutil-raw";

    /** 双层预览 PDF 存储桶 */
    private String bucketPreview = "pdfutil-preview";

    /** 缩略图与元数据存储桶 */
    private String bucketThumb = "pdfutil-thumb";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getBucketRaw() {
        return bucketRaw;
    }

    public void setBucketRaw(String bucketRaw) {
        this.bucketRaw = bucketRaw;
    }

    public String getBucketPreview() {
        return bucketPreview;
    }

    public void setBucketPreview(String bucketPreview) {
        this.bucketPreview = bucketPreview;
    }

    public String getBucketThumb() {
        return bucketThumb;
    }

    public void setBucketThumb(String bucketThumb) {
        this.bucketThumb = bucketThumb;
    }
}
