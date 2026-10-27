package com.pdfutil.pdf.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.pdfutil.common.core.utils.LocalOcrClient;
import com.pdfutil.common.storage.MinioStorageService;
import com.pdfutil.common.utils.file.FileUploadUtils;
import com.pdfutil.pdf.dao.DocFolderDao;
import com.pdfutil.pdf.dao.DocInfoDao;
import com.pdfutil.pdf.dao.DocPageDao;
import com.pdfutil.pdf.dao.DocTaskDao;
import com.pdfutil.pdf.domain.DocFolder;
import com.pdfutil.pdf.domain.DocInfo;
import com.pdfutil.pdf.domain.DocPage;
import com.pdfutil.pdf.domain.DocTask;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/**
 * 服务器文档管理与全文检索核心业务处理服务
 *
 * 整合 MinIO、PostgreSQL、Elasticsearch 与 RapidOCR 识别管道，
 * 提供文档上传、异步解析抽取、索引同步与预览链接生成。
 *
 * @author Alika
 */
@Service
public class DocManagementService {

    private static final Logger log = LoggerFactory.getLogger(DocManagementService.class);

    @Autowired
    private DocFolderDao docFolderDao;

    @Autowired
    private DocInfoDao docInfoDao;

    @Autowired
    private DocPageDao docPageDao;

    @Autowired
    private DocTaskDao docTaskDao;

    @Autowired
    private MinioStorageService minioStorageService;

    @Autowired
    private EsSearchService esSearchService;

    @Autowired
    private OfficePreviewService officePreviewService;

    /** 异步任务调度线程池（隔离控制，默认最大并发数4，防止算力雪崩） */
    private final ExecutorService workerPool = new ThreadPoolExecutor(
            2, 4, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(200),
            new ThreadFactory() {
                private int count = 0;
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "DocWorker-" + (++count));
                    t.setDaemon(true);
                    return t;
                }
            }
    );

    @PostConstruct
    public void init() {
        // 服务启动时自愈：扫描并恢复异常中断的 PROCESSING 任务
        workerPool.submit(this::recoverOrphanTasks);
    }

    /**
     * 服务重启自愈机制：恢复因服务重启而中断的任务
     */
    private void recoverOrphanTasks() {
        try {
            Thread.sleep(3000); // 等待数据库与依赖中间件初始化就绪
            List<DocTask> recoverableTasks = docTaskDao.findRecoverableTasks();
            if (recoverableTasks != null && !recoverableTasks.isEmpty()) {
                log.info("发现 {} 个服务重启前未完成的异常中断任务，正在自动重新入队执行...", recoverableTasks.size());
                for (DocTask task : recoverableTasks) {
                    task.setStatus("WAITING");
                    task.setRetryCount(task.getRetryCount() + 1);
                    task.setErrorMsg("服务重启自动恢复执行");
                    docTaskDao.update(task);
                    submitTask(task.getTaskId());
                }
            }
        } catch (Exception e) {
            log.warn("自愈扫描孤儿任务异常: {}", e.getMessage());
        }
    }

    /**
     * 接收用户上传文件，持久化至存储系统与元数据，并创建后台异步提取任务
     */
    public DocInfo uploadDocument(MultipartFile file, String archiveCode, String deptId, String folderId, String operator) throws IOException {
        String originalFileName = file.getOriginalFilename();
        String fileExt = FileUploadUtils.getExtension(file);
        String docId = UUID.randomUUID().toString().replace("-", "");
        String dateDir = new SimpleDateFormat("yyyy/MM/dd").format(new Date());

        String rawObjectName = "raw/" + dateDir + "/" + docId + "_" + originalFileName;

        // 1. 本地创建临时文件上传至 MinIO / 本地持久存储
        File tempFile = File.createTempFile("upload_" + docId + "_", "." + fileExt);
        try {
            file.transferTo(tempFile);
            minioStorageService.uploadFile(
                    minioStorageService.getMinioProperties().getBucketRaw(),
                    rawObjectName, tempFile, file.getContentType()
            );
        } finally {
            if (tempFile.exists() && !tempFile.delete()) {
                log.debug("临时文件清理: {}", tempFile.getAbsolutePath());
            }
        }

        // 2. 插入 doc_info 记录
        DocInfo docInfo = new DocInfo();
        docInfo.setId(docId);
        docInfo.setFileName(originalFileName);
        docInfo.setArchiveCode(archiveCode);
        docInfo.setFileExt(fileExt);
        docInfo.setFileSize(file.getSize());
        docInfo.setRawStoragePath(rawObjectName);
        docInfo.setPreviewPdfPath("preview/" + docId + ".pdf");
        docInfo.setDeptId(deptId != null && !deptId.isEmpty() ? deptId : "0");
        docInfo.setFolderId(folderId != null && !folderId.isEmpty() ? folderId : "0");
        docInfo.setCreateBy(operator != null && !operator.isEmpty() ? operator : "admin");
        docInfo.setStatus("WAITING");
        docInfo.setOcrStatus("PENDING");

        docInfoDao.insert(docInfo);

        // 3. 插入 doc_task 记录
        String taskId = UUID.randomUUID().toString().replace("-", "");
        DocTask task = new DocTask();
        task.setTaskId(taskId);
        task.setDocId(docId);
        task.setTaskType("CONVERT_AND_INDEX");
        task.setStatus("WAITING");
        task.setProgress(0);

        docTaskDao.insert(task);

        // 4. 提交后台 Worker 执行
        submitTask(taskId);

        return docInfo;
    }

    /**
     * 提交任务到后台 Worker 队列
     */
    public synchronized void preparePreview(String docId) throws IOException {
        DocInfo doc = docInfoDao.findById(docId);
        if (doc == null) throw new IOException("文档不存在");
        if (!OfficePreviewService.supports(doc.getFileExt()) && !"pdf".equalsIgnoreCase(doc.getFileExt()))
            throw new IOException("此格式暂不支持 PDF 预览");
        if ("WAITING".equals(doc.getStatus()) || "PROCESSING".equals(doc.getStatus())) return;
        DocTask task = new DocTask();
        task.setTaskId(UUID.randomUUID().toString().replace("-", ""));
        task.setDocId(docId);
        task.setTaskType("CONVERT_AND_INDEX");
        task.setStatus("WAITING");
        task.setProgress(0);
        doc.setStatus("WAITING");
        docInfoDao.update(doc);
        docTaskDao.insert(task);
        try {
            submitTask(task.getTaskId());
        } catch (RejectedExecutionException ex) {
            doc.setStatus("FAILED");
            docInfoDao.update(doc);
            task.setStatus("FAILED");
            task.setErrorMsg("预览任务队列已满，请稍后重试");
            docTaskDao.update(task);
            throw new IOException("预览任务队列已满，请稍后重试", ex);
        }
    }

    public void submitTask(String taskId) {
        workerPool.submit(() -> processTask(taskId));
    }

    /**
     * 异步处理任务：提取分页正文、OCR 补全、存入数据库与 ES
     */
    private void processTask(String taskId) {
        DocTask task = docTaskDao.findById(taskId);
        if (task == null) {
            return;
        }

        DocInfo doc = docInfoDao.findById(task.getDocId());
        if (doc == null) {
            task.setStatus("FAILED");
            task.setErrorMsg("关联文档不存在");
            docTaskDao.update(task);
            return;
        }

        task.setStatus("PROCESSING");
        task.setStartTime(new Date());
        task.setProgress(10);
        docTaskDao.update(task);

        doc.setStatus("PROCESSING");
        docInfoDao.update(doc);

        File downloadedRaw = null;
        File convertedPreview = null;
        try {
            // 下载原始文件流到临时目录进行处理
            downloadedRaw = File.createTempFile("process_" + doc.getId() + "_", "." + doc.getFileExt());
            InputStream stream = minioStorageService.getObjectStream(
                    minioStorageService.getMinioProperties().getBucketRaw(),
                    doc.getRawStoragePath()
            );
            if (stream != null) {
                org.apache.commons.io.FileUtils.copyInputStreamToFile(stream, downloadedRaw);
            }

            if (downloadedRaw.length() == 0) throw new IOException("原始文件不存在或为空");
            File previewSource = null;
            if ("pdf".equalsIgnoreCase(doc.getFileExt())) {
                previewSource = downloadedRaw;
            } else if (OfficePreviewService.supports(doc.getFileExt())) {
                convertedPreview = officePreviewService.convert(downloadedRaw, doc.getFileExt());
                previewSource = convertedPreview;
            }

            List<DocPage> docPages = new ArrayList<>();
            int totalPages = 0;

            // 智能分流解析：如果是 PDF
            if (previewSource != null) {
                try (PDDocument pdDocument = PDDocument.load(previewSource)) {
                    totalPages = pdDocument.getNumberOfPages();
                    PDFTextStripper stripper = new PDFTextStripper();
                    PDFRenderer renderer = new PDFRenderer(pdDocument);
                    LocalOcrClient ocrClient = new LocalOcrClient();

                    for (int page = 1; page <= totalPages; page++) {
                        stripper.setStartPage(page);
                        stripper.setEndPage(page);
                        String nativeText = stripper.getText(pdDocument);

                        DocPage pageRecord = new DocPage();
                        pageRecord.setDocId(doc.getId());
                        pageRecord.setPageNum(page);

                        // 混合判定策略：有效字符 > 20 为原生文字页，否则进行 RapidOCR 图像识别
                        if (nativeText != null && nativeText.trim().length() > 20) {
                            pageRecord.setTextContent(nativeText.trim());
                            pageRecord.setIsOcr(false);
                            pageRecord.setCharCount(nativeText.trim().length());
                        } else {
                            // 扫描件页进行 OCR
                            try {
                                BufferedImage pageImg = renderer.renderImageWithDPI(page - 1, 150);
                                File imgTemp = File.createTempFile("ocr_page_" + page + "_", ".png");
                                try {
                                    ImageIO.write(pageImg, "PNG", imgTemp);
                                    JsonNode ocrResult = ocrClient.recognizeImage(imgTemp.getAbsolutePath());
                                    String ocrText = extractTextFromJsonNode(ocrResult);
                                    pageRecord.setTextContent(ocrText);
                                    pageRecord.setBboxJson(ocrResult != null ? ocrResult.toString() : null);
                                    pageRecord.setIsOcr(true);
                                    pageRecord.setCharCount(ocrText != null ? ocrText.length() : 0);
                                } finally {
                                    if (imgTemp.exists() && !imgTemp.delete()) {
                                        log.debug("清理单页OCR临时图: {}", imgTemp.getName());
                                    }
                                }
                            } catch (Exception ocrEx) {
                                log.warn("单页 OCR 处理异常: page={}, doc={}, err={}", page, doc.getId(), ocrEx.getMessage());
                                pageRecord.setTextContent("");
                                pageRecord.setIsOcr(false);
                                pageRecord.setCharCount(0);
                            }
                        }
                        docPages.add(pageRecord);

                        // 更新任务进度 (20% - 80%)
                        int progress = 20 + (int) ((double) page / totalPages * 60);
                        task.setProgress(progress);
                        docTaskDao.update(task);
                    }
                }
            } else if (("md".equalsIgnoreCase(doc.getFileExt()) || "txt".equalsIgnoreCase(doc.getFileExt())) && downloadedRaw != null && downloadedRaw.exists()) {
                String text = org.apache.commons.io.FileUtils.readFileToString(downloadedRaw, java.nio.charset.StandardCharsets.UTF_8);
                totalPages = 1;
                DocPage pageRecord = new DocPage();
                pageRecord.setDocId(doc.getId());
                pageRecord.setPageNum(1);
                pageRecord.setTextContent(text);
                pageRecord.setIsOcr(false);
                pageRecord.setCharCount(text.length());
                docPages.add(pageRecord);
            }

            // 保存到分页表 / 本地缓存
            docPageDao.deleteByDocId(doc.getId());
            docPageDao.batchInsert(docPages);

            // 批量同步索引至 Elasticsearch
            try {
                esSearchService.bulkIndexDocPages(doc.getId(), doc.getFileName(), doc.getArchiveCode(), doc.getDeptId(), docPages);
            } catch (Exception esEx) {
                log.warn("同步至 ES 索引告警 (已保存在本地数据库): {}", esEx.getMessage());
            }

            // 如果原本是 PDF，直接复用原件为预览件；若是其他类型，则上传转换好的双层件
            if (previewSource != null) {
                if (!minioStorageService.uploadFile(
                        minioStorageService.getMinioProperties().getBucketPreview(),
                        doc.getPreviewPdfPath(), previewSource, "application/pdf"
                )) throw new IOException("预览 PDF 保存失败");
            }

            // 更新文档与任务状态为成功
            doc.setPageCount(totalPages);
            doc.setStatus("SUCCESS");
            doc.setOcrStatus("DONE");
            docInfoDao.update(doc);

            task.setStatus("SUCCESS");
            task.setProgress(100);
            task.setEndTime(new Date());
            docTaskDao.update(task);
            log.info("文档处理与全文索引完成: {}, 共 {} 页", doc.getFileName(), totalPages);

        } catch (Exception e) {
            log.error("文档异步处理失败: docId={}, error={}", doc.getId(), e.getMessage(), e);
            task.setStatus("FAILED");
            task.setErrorMsg(e.getMessage());
            task.setEndTime(new Date());
            docTaskDao.update(task);

            doc.setStatus("FAILED");
            docInfoDao.update(doc);
        } finally {
            if (convertedPreview != null) convertedPreview.delete();
            if (downloadedRaw != null && downloadedRaw.exists() && !downloadedRaw.delete()) {
                log.debug("清理下载临时文件: {}", downloadedRaw.getName());
            }
        }
    }

    /**
     * 全文关键词检索（优先走 Elasticsearch 毫秒级分词引擎，自动降级支持本地正文精准检索）
     */
    public Map<String, Object> searchFulltext(String keyword, String archiveCode, String folderId, int pageNum, int pageSize) {
        // 1. 尝试 ES 检索
        try {
            Map<String, Object> esResult = esSearchService.search(keyword, archiveCode, null, pageNum, pageSize);
            if (esResult != null && esResult.containsKey("total") && ((Number) esResult.get("total")).intValue() > 0) {
                return esResult;
            }
        } catch (Exception e) {
            log.debug("ES 检索异常，使用本地检索降级: {}", e.getMessage());
        }

        // 2. 本地降级检索
        Map<String, Object> fallbackResult = new HashMap<>();
        List<Map<String, Object>> hitList = new ArrayList<>();
        fallbackResult.put("total", 0);
        fallbackResult.put("items", hitList);

        if ((keyword == null || keyword.trim().isEmpty()) && (archiveCode == null || archiveCode.trim().isEmpty())) {
            return fallbackResult;
        }

        List<DocPage> matchedPages = docPageDao.searchLocalPages(keyword, 100);
        String kw = keyword != null ? keyword.trim() : "";

        for (DocPage p : matchedPages) {
            DocInfo doc = docInfoDao.findById(p.getDocId());
            if (doc == null) continue;

            if (folderId != null && !folderId.trim().isEmpty() && !"ALL".equalsIgnoreCase(folderId.trim())) {
                if (doc.getFolderId() == null || !doc.getFolderId().equals(folderId.trim())) {
                    continue;
                }
            }
            if (archiveCode != null && !archiveCode.trim().isEmpty()) {
                if (doc.getArchiveCode() == null || !doc.getArchiveCode().contains(archiveCode.trim())) {
                    continue;
                }
            }

            DocFolder folder = doc.getFolderId() != null ? docFolderDao.findById(doc.getFolderId()) : null;

            Map<String, Object> item = new HashMap<>();
            item.put("docId", doc.getId());
            item.put("pageNum", p.getPageNum());
            item.put("fileName", doc.getFileName());
            item.put("archiveCode", doc.getArchiveCode());
            item.put("folderId", doc.getFolderId());
            item.put("folderName", folder != null ? folder.getName() : "全部文件");

            // 构建高亮片段
            String raw = p.getTextContent();
            if (raw != null && !kw.isEmpty()) {
                int idx = raw.toLowerCase().indexOf(kw.toLowerCase());
                if (idx != -1) {
                    int start = Math.max(0, idx - 40);
                    int end = Math.min(raw.length(), idx + kw.length() + 60);
                    String before = raw.substring(start, idx);
                    String match = raw.substring(idx, idx + kw.length());
                    String after = raw.substring(idx + kw.length(), end);
                    item.put("snippet", (start > 0 ? "..." : "") + before + "<span class=\"search-hit-keyword\">" + match + "</span>" + after + (end < raw.length() ? "..." : ""));
                } else {
                    item.put("snippet", raw.length() > 100 ? raw.substring(0, 100) + "..." : raw);
                }
            } else {
                item.put("snippet", raw != null && raw.length() > 100 ? raw.substring(0, 100) + "..." : raw);
            }
            hitList.add(item);
        }

        int total = hitList.size();
        fallbackResult.put("total", total);

        int fromIndex = (Math.max(1, pageNum) - 1) * pageSize;
        if (fromIndex >= total) {
            fallbackResult.put("items", Collections.emptyList());
        } else {
            int toIndex = Math.min(fromIndex + pageSize, total);
            fallbackResult.put("items", hitList.subList(fromIndex, toIndex));
        }

        return fallbackResult;
    }

    /**
     * 从 RapidOCR 返回的 JsonNode 中抽取识别出的纯文本
     */
    private String extractTextFromJsonNode(JsonNode node) {
        if (node == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (node.has("data") && node.get("data").isArray()) {
            for (JsonNode item : node.get("data")) {
                if (item.has("text")) {
                    sb.append(item.get("text").asText()).append(" ");
                }
            }
        } else if (node.has("ocr_result") && node.get("ocr_result").isArray()) {
            for (JsonNode item : node.get("ocr_result")) {
                if (item.has("transcription")) {
                    sb.append(item.get("transcription").asText()).append(" ");
                }
            }
        }
        return sb.toString().trim();
    }

    /**
     * 获取供 PDF.js 前端安全流式访问的双层预览 PDF URL
     */
    public String getPreviewUrl(String docId) {
        DocInfo doc = docInfoDao.findById(docId);
        if (doc == null) {
            return null;
        }
        String presigned = minioStorageService.getPresignedUrl(
                minioStorageService.getMinioProperties().getBucketPreview(),
                doc.getPreviewPdfPath(), 2
        );
        if (presigned != null && presigned.startsWith("http")) {
            return presigned;
        }
        // 离线/本地模式，走后端直连预览接口
        return "/api/server/doc/stream-preview/" + docId;
    }

    /**
     * 获取原始文档下载 URL
     */
    public String getDownloadUrl(String docId) {
        DocInfo doc = docInfoDao.findById(docId);
        if (doc == null) {
            return null;
        }
        String presigned = minioStorageService.getPresignedUrl(
                minioStorageService.getMinioProperties().getBucketRaw(),
                doc.getRawStoragePath(), 1
        );
        if (presigned != null && presigned.startsWith("http")) {
            return presigned;
        }
        return "/api/server/doc/stream-raw/" + docId;
    }

    /**
     * 过滤并提取 Word 97-2003 (.doc) 二进制 OLE 复合流中的有效正文文本
     * 彻底剔除 OLE/WPS 结构元数据、字体表、样式表与乱码字符
     */
    private List<String> extractCleanDocLines(byte[] bytes) {
        List<String> cleanList = new ArrayList<>();
        if (bytes == null || bytes.length == 0) return cleanList;

        String raw = new String(bytes, java.nio.charset.StandardCharsets.UTF_16LE);
        String[] rawLines = raw.split("[\r\n]+");

        java.util.regex.Pattern oleNoisePattern = java.util.regex.Pattern.compile(
                "WordDocument|SummaryInformation|DocumentSummaryInformation|Root Entry|WpsCustomData|Normal\\.dot|userNormal|KSOProduct|TemplateDocer|commondata|eyJo|Table Paragraph|Times New Roman|宋体|仿宋|黑体|Cambria",
                java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Pattern chinesePattern = java.util.regex.Pattern.compile("[\u4e00-\u9fa5]");

        for (String rawLine : rawLines) {
            String line = rawLine.replaceAll("[\u0000-\u001f\u007f-\u009f]", "").trim();
            if (line.length() < 2) continue;
            if (oleNoisePattern.matcher(line).find()) continue;

            int readableCount = 0;
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                if ((ch >= 0x4e00 && ch <= 0x9fa5) ||
                    (ch >= '0' && ch <= '9') ||
                    (ch >= 'a' && ch <= 'z') ||
                    (ch >= 'A' && ch <= 'Z') ||
                    "，。、：；！？（）《》【】“”‘’—…、□■／/·%-+=\\s".indexOf(ch) >= 0 ||
                    Character.isWhitespace(ch)) {
                    readableCount++;
                }
            }
            double ratio = (double) readableCount / line.length();
            if (ratio >= 0.70 && chinesePattern.matcher(line).find()) {
                cleanList.add(line);
            }
        }
        return cleanList;
    }
}
