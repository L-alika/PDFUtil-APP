package com.pdfutil.common.core.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.net.FileNameMap;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.concurrent.Semaphore;

/**
 * 远程OCR API客户端
 *
 * 调用远程OCR服务获取文字识别结果和位置信息
 *
 * 功能特性：
 * - 支持配置超时时间
 * - 支持自动重试（指数退避策略）
 * - 完善的错误处理
 *
 * @author Alika
 * @date 2025-02-05
 */
public class RemoteOcrClient {

    private static final Logger log = LoggerFactory.getLogger(RemoteOcrClient.class);

    private static final String DEFAULT_API_URL = "http://192.168.124.66:8090/api/ocr";

    // 默认超时配置
    private static final int DEFAULT_CONNECT_TIMEOUT = 60000; // 60秒
    private static final int DEFAULT_READ_TIMEOUT = 600000;   // 10分钟（大文件需要更长时间）

    // 默认重试配置
    private static final int DEFAULT_MAX_RETRIES = 2;         // 减少重试次数，避免过载
    private static final int DEFAULT_RETRY_DELAY = 3000;     // 3秒

    /**
     * 并发控制信号量
     * 限制同时发送到远程OCR API的请求数量，避免服务过载
     * 默认串行处理（1个并发），可通过配置调整
     */
    private static final Semaphore CONCURRENCY_LIMIT = new Semaphore(
        Integer.getInteger("pdfutil.pdf.remoteOcrMaxConcurrent", 1)
    );

    private final String apiUrl;
    private final ObjectMapper objectMapper;
    private final int connectTimeout;
    private final int readTimeout;
    private final int maxRetries;
    private final int retryDelay;

    /**
     * 默认构造函数，使用默认配置
     */
    public RemoteOcrClient() {
        this(DEFAULT_API_URL);
    }

    /**
     * 指定API URL的构造函数，使用默认超时配置
     */
    public RemoteOcrClient(String apiUrl) {
        this(apiUrl, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT,
             DEFAULT_MAX_RETRIES, DEFAULT_RETRY_DELAY);
    }

    /**
     * 完整参数构造函数（支持所有配置）
     *
     * @param apiUrl         OCR API地址
     * @param connectTimeout 连接超时时间（毫秒）
     * @param readTimeout    读取超时时间（毫秒）
     * @param maxRetries     最大重试次数
     * @param retryDelay     重试延迟（毫秒）
     */
    public RemoteOcrClient(String apiUrl, int connectTimeout, int readTimeout,
                          int maxRetries, int retryDelay) {
        this.apiUrl = apiUrl != null && !apiUrl.isEmpty() ? apiUrl : DEFAULT_API_URL;
        this.connectTimeout = connectTimeout > 0 ? connectTimeout : DEFAULT_CONNECT_TIMEOUT;
        this.readTimeout = readTimeout > 0 ? readTimeout : DEFAULT_READ_TIMEOUT;
        this.maxRetries = maxRetries >= 0 ? maxRetries : DEFAULT_MAX_RETRIES;
        this.retryDelay = retryDelay >= 0 ? retryDelay : DEFAULT_RETRY_DELAY;
        this.objectMapper = new ObjectMapper();

        log.info("初始化RemoteOcrClient - URL: {}, 连接超时: {}ms, 读取超时: {}ms, 最大重试: {}次",
            this.apiUrl, this.connectTimeout, this.readTimeout, this.maxRetries);
    }

    /**
     * 调用远程OCR API识别图片文件
     *
     * @param imageFilePath 图片文件路径
     * @return OCR识别结果（JSON格式）
     * @throws Exception 调用失败时抛出异常
     */
    public String recognizeImage(String imageFilePath) throws Exception {
        log.info("调用远程OCR API识别图片: {}", imageFilePath);

        File imageFile = new File(imageFilePath);
        if (!imageFile.exists()) {
            throw new FileNotFoundException("图片文件不存在: " + imageFilePath);
        }

        // 读取图片文件并转换为Base64
        byte[] imageBytes = Files.readAllBytes(imageFile.toPath());
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);

        // 构建请求体
        String jsonRequestBody = buildJsonRequestBody(base64Image, imageFile.getName());

        // 发送HTTP POST请求
        String response = sendPostRequest(jsonRequestBody);

        log.info("远程OCR API调用成功");
        return response;
    }

    /**
     * 调用远程OCR API识别图片文件（通过Multipart上传）
     *
     * @param imageFilePath 图片文件路径
     * @return OCR识别结果（JsonNode格式）
     * @throws Exception 调用失败时抛出异常
     */
    public JsonNode recognizeImageMultipart(String imageFilePath) throws Exception {
        log.info("调用远程OCR API识别图片（Multipart方式）: {}", imageFilePath);

        File imageFile = new File(imageFilePath);
        if (!imageFile.exists()) {
            throw new FileNotFoundException("图片文件不存在: " + imageFilePath);
        }

        // 发送Multipart请求
        String response = sendMultipartRequest(imageFile);

        // 解析JSON响应
        JsonNode jsonResponse = objectMapper.readTree(response);

        log.info("远程OCR API调用成功，识别到 {} 页", jsonResponse.size());
        return jsonResponse;
    }

    /**
     * 兼容dev-prearchiving项目的OCR接口
     * 上传文件进行 OCR 识别
     *
     * @param file 要识别的文件（MultipartFile）
     * @return 识别后的结果封装为 OcrResult
     * @throws Exception 调用失败时抛出异常
     */
    public OcrResult ocr(MultipartFile file) throws Exception {
        log.info("调用远程OCR API识别文件（兼容接口）: {}", file.getOriginalFilename());

        if (file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }

        // 将MultipartFile转换为临时文件
        File tempFile = null;
        try {
            // 创建临时文件
            String suffix = getFileExtension(file.getOriginalFilename());
            tempFile = File.createTempFile("ocr_upload_", "_" + suffix);
            file.transferTo(tempFile);

            // 发送Multipart请求
            String response = sendMultipartRequest(tempFile);

            // 解析JSON响应为OcrResult
            OcrResult result = objectMapper.readValue(response, OcrResult.class);

            log.info("远程OCR API调用成功，识别到 {} 页", result.getPages() != null ? result.getPages().size() : 0);
            return result;

        } finally {
            // 清理临时文件
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "tmp";
        }
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == fileName.length() - 1) {
            return "tmp";
        }
        return fileName.substring(lastDotIndex + 1);
    }

    /**
     * 构建JSON请求体（Base64方式）
     */
    private String buildJsonRequestBody(String base64Image, String fileName) {
        return String.format("{\"image\":\"%s\",\"fileName\":\"%s\"}", base64Image, fileName);
    }

    /**
     * 发送HTTP POST请求（JSON格式）
     */
    private String sendPostRequest(String jsonBody) throws IOException {
        URL url = new URL(apiUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            // 设置请求方法和超时
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);
            conn.setDoOutput(true);
            conn.setDoInput(true);

            // 设置请求头
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");

            // 发送请求体
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            // 读取响应
            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                String errorResponse = readStream(conn.getErrorStream());
                throw new IOException("OCR API调用失败，状态码: " + responseCode + ", 错误: " + errorResponse);
            }

            return readStream(conn.getInputStream());

        } finally {
            // 关闭连接（这会关闭相关的流）
            conn.disconnect();
        }
    }

    /**
     * 发送Multipart/form-data请求（适用于大文件）
     *
     * 带有重试机制和超时控制
     *
     * @param imageFile 要识别的图片文件
     * @return OCR识别结果（JSON字符串）
     * @throws IOException 所有重试失败后抛出异常
     */
    private String sendMultipartRequest(File imageFile) throws IOException {
        int attempt = 0;
        IOException lastException = null;

        // 获取并发许可（如果当前并发数已达到上限，会阻塞等待）
        boolean acquired = false;
        try {
            log.debug("等待获取OCR API并发许可... (当前可用: {})", CONCURRENCY_LIMIT.availablePermits());
            CONCURRENCY_LIMIT.acquire();
            acquired = true;
            log.debug("已获取OCR API并发许可，开始处理文件: {}", imageFile.getName());

            while (attempt <= maxRetries) {
                try {
                    if (attempt > 0) {
                        // 计算退避延迟（指数退避：3秒、6秒、12秒...）
                        long delay = retryDelay * (1L << (attempt - 1));
                        log.warn("OCR API调用失败，{} ms后进行第{}次重试...", delay, attempt);
                        Thread.sleep(delay);
                    }

                    log.info("发送OCR请求（尝试 {}/{}）: 文件={}, 大小={} bytes, 当前并发许可={}",
                        attempt + 1, maxRetries + 1, imageFile.getName(), imageFile.length(),
                        CONCURRENCY_LIMIT.availablePermits());

                    String response = sendMultipartRequestInternal(imageFile);
                    log.info("OCR API调用成功，响应长度: {} chars", response.length());
                    return response;

                } catch (SocketTimeoutException e) {
                    lastException = e;
                    attempt++;

                    // 更精确地区分连接超时和读取超时
                    String timeoutType;
                    String exceptionClass = e.getClass().getSimpleName();
                    if (exceptionClass.contains("Connect") ||
                        (e.getMessage() != null && e.getMessage().toLowerCase().contains("connect"))) {
                        timeoutType = "连接";
                        log.warn("OCR API连接超时（尝试 {}/{}）：{} - 可能原因：网络不稳定、服务器无响应",
                            attempt, maxRetries + 1, e.getMessage());
                    } else {
                        timeoutType = "读取";
                        log.warn("OCR API读取超时（尝试 {}/{}）：{} - 可能原因：文件过大、服务器处理慢",
                            attempt, maxRetries + 1, e.getMessage());
                    }

                    // 如果已达到最大重试次数，不再重试
                    if (attempt > maxRetries) {
                        break;
                    }

                } catch (IOException e) {
                    lastException = e;
                    attempt++;

                    log.warn("OCR API调用失败（尝试 {}/{}）：{}",
                        attempt, maxRetries + 1, e.getMessage());

                    // 如果是服务器错误（5xx）或网络问题，继续重试
                    // 如果是客户端错误（4xx），不重试
                    if (e.getMessage() != null && e.getMessage().contains("状态码: 4")) {
                        log.error("客户端错误，不重试");
                        throw e;
                    }

                    // 如果已达到最大重试次数，不再重试
                    if (attempt > maxRetries) {
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("重试被中断", e);
                }
            }

            // 所有重试都失败
            String errorMsg = String.format(
                "OCR API调用失败，已重试%d次。最后错误: %s。建议：1) 检查文件大小是否过大 2) 检查网络连接 3) 稍后重试",
                maxRetries, lastException != null ? lastException.getMessage() : "未知错误");
            log.error(errorMsg);

            if (lastException != null) {
                throw new IOException(errorMsg, lastException);
            } else {
                throw new IOException(errorMsg);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待OCR API许可被中断", e);
        } finally {
            // 释放并发许可
            if (acquired) {
                CONCURRENCY_LIMIT.release();
                log.debug("已释放OCR API并发许可 (当前可用: {})", CONCURRENCY_LIMIT.availablePermits());
            }
        }
    }

    /**
     * 内部方法：执行单次Multipart请求（不含重试逻辑）
     */
    private String sendMultipartRequestInternal(File imageFile) throws IOException {
        String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
        URL url = new URL(apiUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            // 设置请求方法和超时
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);
            conn.setDoOutput(true);
            conn.setDoInput(true);

            // 禁用分块传输编码
            conn.setChunkedStreamingMode(0);

            // 设置请求头（完全匹配Postman的格式）
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            // 构建Multipart请求体
            String lineEnd = "\r\n";
            String twoHyphens = "--";

            // 使用ByteArrayOutputStream来构建完整请求体
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

            // 添加文件部分头部（使用application/octet-stream，与Postman一致）
            StringBuilder sb = new StringBuilder();
            sb.append(twoHyphens).append(boundary).append(lineEnd);
            sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
              .append(imageFile.getName()).append("\"").append(lineEnd);
            sb.append("Content-Type: application/octet-stream").append(lineEnd);
            sb.append(lineEnd);

            byteArrayOutputStream.write(sb.toString().getBytes(StandardCharsets.UTF_8));

            log.debug("上传文件: {}, 文件大小: {} bytes", imageFile.getName(), imageFile.length());

            // 写入文件内容
            try (FileInputStream fis = new FileInputStream(imageFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    byteArrayOutputStream.write(buffer, 0, bytesRead);
                }
            }

            // 添加结束边界
            byteArrayOutputStream.write((lineEnd + twoHyphens + boundary + twoHyphens + lineEnd).getBytes(StandardCharsets.UTF_8));

            // 获取完整的请求体字节数组
            byte[] requestBody = byteArrayOutputStream.toByteArray();
            log.debug("请求体总大小: {} bytes", requestBody.length);

            // 设置Content-Length
            conn.setRequestProperty("Content-Length", String.valueOf(requestBody.length));

            // 一次性写入请求体
            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody);
                os.flush();
            }

            log.debug("请求已发送到: {}", apiUrl);

            // 读取响应
            int responseCode = conn.getResponseCode();
            log.debug("收到响应，状态码: {}", responseCode);

            if (responseCode != HttpURLConnection.HTTP_OK) {
                String errorResponse = readStream(conn.getErrorStream());
                log.error("OCR API错误响应: {}", errorResponse);
                throw new IOException("OCR API调用失败，状态码: " + responseCode + ", 错误: " + errorResponse);
            }

            String response = readStream(conn.getInputStream());
            return response;

        } finally {
            // 关闭连接（这会关闭相关的流）
            conn.disconnect();
        }
    }

    /**
     * 读取输入流内容
     */
    private String readStream(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }

        // 使用try-with-resources确保BufferedReader被自动关闭
        // 注意：这里不会关闭传入的InputStream，因为它由HttpURLConnection管理
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }
        return response.toString();
    }

    /**
     * 获取文件的MIME类型
     */
    private String getMimeType(String fileName) {
        // 首先尝试使用URLConnection
        FileNameMap fileNameMap = URLConnection.getFileNameMap();
        String mimeType = fileNameMap.getContentTypeFor(fileName);

        if (mimeType != null && !mimeType.isEmpty()) {
            return mimeType;
        }

        // 如果URLConnection无法识别，使用自定义映射
        String extension = getFileExtension(fileName).toLowerCase();
        switch (extension) {
            case "png":
                return "image/png";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "gif":
                return "image/gif";
            case "bmp":
                return "image/bmp";
            case "tiff":
            case "tif":
                return "image/tiff";
            case "webp":
                return "image/webp";
            case "pdf":
                return "application/pdf";
            default:
                return "application/octet-stream";
        }
    }

    /**
     * 测试API连接
     */
    public boolean testConnection() {
        try {
            log.info("测试远程OCR API连接: {}", apiUrl);
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int responseCode = conn.getResponseCode();
            conn.disconnect();

            boolean success = (responseCode == HttpURLConnection.HTTP_OK);
            log.info("API连接测试{}成功", success ? "" : "未");
            return success;

        } catch (Exception e) {
            log.error("API连接测试失败", e);
            return false;
        }
    }
}
