package com.pdfutil.common.storage;

import io.minio.*;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 对象存储服务封装类
 *
 * 支持 MinIO 分布式对象存储与本地磁盘持久化双轨自适应运行：
 * 当 MinIO 在线时自动接入对象存储；当 MinIO 未启动时自动优雅降级至本地磁盘存储，确保系统在任何环境下均可正常上传、处理与预览。
 *
 * @author Alika
 */
@Service
public class MinioStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);

    @Autowired
    private MinioProperties minioProperties;

    private MinioClient minioClient;
    private final String localStorageBase = System.getProperty("user.home") + File.separator + ".pdfutil" + File.separator + "storage";

    @PostConstruct
    public void init() {
        // 确保存储目录存在
        File baseDir = new File(localStorageBase);
        if (!baseDir.exists()) {
            baseDir.mkdirs();
        }

        if (!minioProperties.isEnabled()) {
            log.info("MinIO 对象存储未启用，使用本地文件存储: {}", localStorageBase);
            return;
        }

        try {
            this.minioClient = MinioClient.builder()
                    .endpoint(minioProperties.getEndpoint())
                    .credentials(minioProperties.getAccessKey(), minioProperties.getSecretKey())
                    .build();

            // 自动初始化存储桶
            ensureBucket(minioProperties.getBucketRaw());
            ensureBucket(minioProperties.getBucketPreview());
            ensureBucket(minioProperties.getBucketThumb());
            log.info("MinIO 客户端初始化成功，服务端点: {}", minioProperties.getEndpoint());
        } catch (Exception e) {
            log.warn("MinIO 初始化连接异常 (服务尚未就绪，将自动降级为本地磁盘存储): {}", e.getMessage());
        }
    }

    /**
     * 获取本地磁盘存储文件句柄
     */
    public File getLocalFile(String bucketName, String objectName) {
        String safePath = objectName.replace('/', File.separatorChar).replace('\\', File.separatorChar);
        File target = new File(localStorageBase + File.separator + bucketName + File.separator + safePath);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        return target;
    }

    /**
     * 确保存储桶存在，若不存在则创建
     */
    public boolean ensureBucket(String bucketName) {
        if (minioClient == null) {
            return false;
        }
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                log.info("创建 MinIO 存储桶: {}", bucketName);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 上传本地文件到存储系统 (MinIO + 本地磁盘双轨)
     */
    public boolean uploadFile(String bucketName, String objectName, File file, String contentType) {
        if (file == null || !file.exists()) {
            return false;
        }

        boolean localSaved = false;
        try {
            File localTarget = getLocalFile(bucketName, objectName);
            Files.copy(file.toPath(), localTarget.toPath(), StandardCopyOption.REPLACE_EXISTING);
            localSaved = true;
        } catch (Exception e) {
            log.warn("写入本地磁盘副本失败: {}/{}, 原因: {}", bucketName, objectName, e.getMessage());
        }

        boolean minioSaved = false;
        if (minioClient != null) {
            try (InputStream stream = new FileInputStream(file)) {
                ensureBucket(bucketName);
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(bucketName)
                                .object(objectName)
                                .stream(stream, file.length(), -1)
                                .contentType(contentType != null ? contentType : "application/octet-stream")
                                .build()
                );
                minioSaved = true;
                log.info("成功上传文件至 MinIO: {}/{}", bucketName, objectName);
            } catch (Exception e) {
                log.warn("上传 MinIO 失败 (已使用本地存储): {}/{}, 原因: {}", bucketName, objectName, e.getMessage());
            }
        }

        return localSaved || minioSaved;
    }

    /**
     * 上传流数据至存储系统
     */
    public boolean uploadStream(String bucketName, String objectName, InputStream stream, long size, String contentType) {
        if (stream == null) {
            return false;
        }

        try {
            File localTarget = getLocalFile(bucketName, objectName);
            try (FileOutputStream fos = new FileOutputStream(localTarget)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = stream.read(buf)) != -1) {
                    fos.write(buf, 0, len);
                }
            }
            // 如果 MinIO 可用，再上传到 MinIO
            if (minioClient != null && localTarget.exists()) {
                try (InputStream fis = new FileInputStream(localTarget)) {
                    ensureBucket(bucketName);
                    minioClient.putObject(
                            PutObjectArgs.builder()
                                    .bucket(bucketName)
                                    .object(objectName)
                                    .stream(fis, localTarget.length(), -1)
                                    .contentType(contentType != null ? contentType : "application/octet-stream")
                                    .build()
                    );
                }
            }
            return true;
        } catch (Exception e) {
            log.error("保存流数据失败: {}/{}, 原因: {}", bucketName, objectName, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 获取存储对象的输入流 (优先从 MinIO 获取，失败时自动从本地磁盘读取)
     */
    public InputStream getObjectStream(String bucketName, String objectName) {
        if (minioClient != null) {
            try {
                return minioClient.getObject(
                        GetObjectArgs.builder()
                                .bucket(bucketName)
                                .object(objectName)
                                .build()
                );
            } catch (Exception e) {
                log.debug("从 MinIO 读取流失败，尝试本地读取: {}/{}", bucketName, objectName);
            }
        }

        // 本地磁盘降级读取
        File localFile = getLocalFile(bucketName, objectName);
        if (localFile.exists() && localFile.length() > 0) {
            try {
                return new FileInputStream(localFile);
            } catch (Exception e) {
                log.error("从本地读取文件流失败: {}", localFile.getAbsolutePath(), e);
            }
        }

        return null;
    }

    /**
     * 获取带有过期时间的预签名 URL（MinIO 在线时直接生成；离线时返回 null 引导走系统后端流式分发）
     */
    public String getPresignedUrl(String bucketName, String objectName, int expiryHours) {
        if (minioClient != null) {
            try {
                return minioClient.getPresignedObjectUrl(
                        GetPresignedObjectUrlArgs.builder()
                                .method(Method.GET)
                                .bucket(bucketName)
                                .object(objectName)
                                .expiry(Math.max(1, expiryHours), TimeUnit.HOURS)
                                .build()
                );
            } catch (Exception e) {
                log.debug("生成 MinIO 预签名链接失败，将转由后端直连流分发: {}", e.getMessage());
            }
        }
        return null;
    }

    /**
     * 删除存储对象
     */
    public boolean removeObject(String bucketName, String objectName) {
        boolean removed = false;
        if (minioClient != null) {
            try {
                minioClient.removeObject(
                        RemoveObjectArgs.builder()
                                .bucket(bucketName)
                                .object(objectName)
                                .build()
                );
                removed = true;
            } catch (Exception ignored) {
            }
        }

        File localFile = getLocalFile(bucketName, objectName);
        if (localFile.exists()) {
            localFile.delete();
            removed = true;
        }
        return removed;
    }

    /**
     * 判断对象是否存在
     */
    public boolean exists(String bucketName, String objectName) {
        if (minioClient != null) {
            try {
                minioClient.statObject(
                        StatObjectArgs.builder()
                                .bucket(bucketName)
                                .object(objectName)
                                .build()
                );
                return true;
            } catch (Exception ignored) {
            }
        }

        File localFile = getLocalFile(bucketName, objectName);
        return localFile.exists() && localFile.length() > 0;
    }

    public MinioProperties getMinioProperties() {
        return minioProperties;
    }
}
