package com.pdfutil.pdf.controller;

import com.pdfutil.common.core.controller.BaseController;
import com.pdfutil.common.core.domain.AjaxResult;
import com.pdfutil.common.storage.MinioStorageService;
import com.pdfutil.pdf.dao.DocFolderDao;
import com.pdfutil.pdf.dao.DocInfoDao;
import com.pdfutil.pdf.dao.DocPageDao;
import com.pdfutil.pdf.dao.DocTaskDao;
import com.pdfutil.pdf.domain.DocFolder;
import com.pdfutil.pdf.domain.DocInfo;
import com.pdfutil.pdf.domain.DocPage;
import com.pdfutil.pdf.domain.DocTask;
import com.pdfutil.pdf.service.DocManagementService;
import com.pdfutil.pdf.service.EsSearchService;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务器文档管理与全文检索 REST 接口控制器
 *
 * 提供目录文件夹管理、文档上传、状态查询、MinIO 安全预览/下载链接获取，以及 Elasticsearch 全文高亮检索。
 *
 * @author Alika
 */
@RestController
@RequestMapping("/api/server/doc")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
public class DocManagementController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(DocManagementController.class);

    @Autowired
    private DocManagementService docManagementService;

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

    // ==========================================
    // 目录文件夹管理接口
    // ==========================================

    /**
     * 获取所有文件夹目录列表
     */
    @GetMapping("/folders")
    public AjaxResult listFolders() {
        return AjaxResult.success(docFolderDao.listAll());
    }

    /**
     * 新建文件夹目录
     */
    @PostMapping("/folders")
    public AjaxResult createFolder(@RequestBody DocFolder folder) {
        if (folder.getName() == null || folder.getName().trim().isEmpty()) {
            return AjaxResult.error("文件夹名称不能为空");
        }
        if (folder.getParentId() == null || folder.getParentId().trim().isEmpty()) {
            folder.setParentId("0");
        }
        docFolderDao.insert(folder);
        return AjaxResult.success("目录创建成功", folder);
    }

    /**
     * 重命名/更新文件夹
     */
    @PutMapping("/folders/{folderId}")
    public AjaxResult updateFolder(@PathVariable("folderId") String folderId, @RequestBody DocFolder folder) {
        DocFolder existing = docFolderDao.findById(folderId);
        if (existing == null) {
            return AjaxResult.error("文件夹不存在");
        }
        if (folder.getName() != null && !folder.getName().trim().isEmpty()) {
            existing.setName(folder.getName().trim());
        }
        docFolderDao.update(existing);
        return AjaxResult.success("重命名成功", existing);
    }

    /**
     * 删除文件夹
     */
    @DeleteMapping("/folders/{folderId}")
    public AjaxResult deleteFolder(@PathVariable("folderId") String folderId) {
        docFolderDao.delete(folderId);
        return AjaxResult.success("目录删除成功");
    }

    // ==========================================
    // 文档管理接口
    // ==========================================

    /**
     * 上传文档至指定文件夹，并创建后台异步解析与 OCR 索引任务
     */
    @PostMapping("/upload")
    public AjaxResult uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "archiveCode", required = false) String archiveCode,
            @RequestParam(value = "deptId", required = false, defaultValue = "0") String deptId,
            @RequestParam(value = "folderId", required = false, defaultValue = "0") String folderId,
            @RequestParam(value = "operator", required = false, defaultValue = "admin") String operator) {
        if (file == null || file.isEmpty()) {
            return AjaxResult.error("上传文件不能为空");
        }
        try {
            DocInfo doc = docManagementService.uploadDocument(file, archiveCode, deptId, folderId, operator);
            return AjaxResult.success("文档上传成功，已提交后台进行全文解析与索引", doc);
        } catch (Exception e) {
            log.error("文档上传处理失败", e);
            return AjaxResult.error("文档上传失败: " + e.getMessage());
        }
    }

    /**
     * 查询文档列表 (支持按目录、关键词、档号过滤及分页)
     */
    @GetMapping("/list")
    public AjaxResult listDocuments(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "archiveCode", required = false) String archiveCode,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "deptId", required = false) String deptId,
            @RequestParam(value = "folderId", required = false, defaultValue = "ALL") String folderId,
            @RequestParam(value = "pageNum", required = false, defaultValue = "1") int pageNum,
            @RequestParam(value = "pageSize", required = false, defaultValue = "12") int pageSize) {
        List<DocInfo> list = docInfoDao.findList(keyword, archiveCode, status, deptId, folderId, pageNum, pageSize);
        int total = docInfoDao.count(keyword, archiveCode, status, deptId, folderId);

        Map<String, Object> data = new HashMap<>();
        data.put("rows", list);
        data.put("total", total);
        return AjaxResult.success(data);
    }

    /**
     * 删除文档
     */
    @DeleteMapping("/{docId}")
    public AjaxResult deleteDocument(@PathVariable("docId") String docId) {
        docInfoDao.deleteById(docId);
        docPageDao.deleteByDocId(docId);
        return AjaxResult.success("文档删除成功");
    }

    /**
     * 查询文档详情及分页文本信息
     */
    @GetMapping("/detail/{docId}")
    public AjaxResult getDocumentDetail(@PathVariable("docId") String docId) {
        DocInfo doc = docInfoDao.findById(docId);
        if (doc == null) {
            return AjaxResult.error("文档不存在");
        }
        List<DocPage> pages = docPageDao.findByDocId(docId);

        Map<String, Object> data = new HashMap<>();
        data.put("doc", doc);
        data.put("pages", pages);
        return AjaxResult.success(data);
    }

    /**
     * 获取 PDF.js 在线安全流式预览 URL
     */
    @PostMapping("/prepare-preview/{docId}")
    public AjaxResult preparePreview(@PathVariable("docId") String docId) {
        try {
            docManagementService.preparePreview(docId);
            return AjaxResult.success("预览任务已提交");
        } catch (Exception ex) {
            return AjaxResult.error(ex.getMessage());
        }
    }

    @GetMapping("/preview-url/{docId}")
    public AjaxResult getPreviewUrl(@PathVariable("docId") String docId) {
        String previewUrl = docManagementService.getPreviewUrl(docId);
        if (previewUrl == null) {
            return AjaxResult.error("未找到可供预览的文档文件");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("previewUrl", previewUrl);
        return AjaxResult.success(data);
    }

    /**
     * 获取原件直接下载 URL
     */
    @GetMapping("/download-url/{docId}")
    public AjaxResult getDownloadUrl(@PathVariable("docId") String docId) {
        String downloadUrl = docManagementService.getDownloadUrl(docId);
        if (downloadUrl == null) {
            return AjaxResult.error("未找到原始文档文件");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("downloadUrl", downloadUrl);
        return AjaxResult.success(data);
    }

    /**
     * 流式输出双层预览 PDF (供 PDF.js 直连渲染)
     */
    @GetMapping("/stream-preview/{docId}")
    public void streamPreviewPdf(@PathVariable("docId") String docId, HttpServletResponse response) {
        DocInfo doc = docInfoDao.findById(docId);
        if (doc == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        InputStream is = minioStorageService.getObjectStream(
                minioStorageService.getMinioProperties().getBucketPreview(),
                doc.getPreviewPdfPath()
        );
        if (is == null && "pdf".equalsIgnoreCase(doc.getFileExt())) {
            is = minioStorageService.getObjectStream(
                    minioStorageService.getMinioProperties().getBucketRaw(),
                    doc.getRawStoragePath()
            );
        }
        if (is == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        try {
            response.setContentType("application/pdf");
            response.setHeader("Content-Disposition", "inline; filename=\"" + URLEncoder.encode(doc.getFileName(), StandardCharsets.UTF_8.name()) + "\"");
            IOUtils.copy(is, response.getOutputStream());
            response.flushBuffer();
        } catch (Exception e) {
            log.error("流式输出预览文件异常: {}", e.getMessage());
        } finally {
            try { is.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * 流式下载原始文件
     */
    @GetMapping("/stream-raw/{docId}")
    public void streamRawFile(@PathVariable("docId") String docId, HttpServletResponse response) {
        DocInfo doc = docInfoDao.findById(docId);
        if (doc == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        InputStream is = minioStorageService.getObjectStream(
                minioStorageService.getMinioProperties().getBucketRaw(),
                doc.getRawStoragePath()
        );
        if (is == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        try {
            response.setContentType("application/octet-stream");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + URLEncoder.encode(doc.getFileName(), StandardCharsets.UTF_8.name()) + "\"");
            IOUtils.copy(is, response.getOutputStream());
            response.flushBuffer();
        } catch (Exception e) {
            log.error("流式下载原始文件异常: {}", e.getMessage());
        } finally {
            try { is.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * 查询后台任务进度与状态
     */
    @GetMapping("/task/{taskId}")
    public AjaxResult getTaskStatus(@PathVariable("taskId") String taskId) {
        DocTask task = docTaskDao.findById(taskId);
        if (task == null) {
            return AjaxResult.error("任务不存在");
        }
        return AjaxResult.success(task);
    }

    /**
     * 全文检索接口 (优先走 Elasticsearch，支持本地全文高亮降级)
     */
    @PostMapping("/search")
    public AjaxResult searchFulltext(@RequestBody Map<String, Object> params) {
        String keyword = (String) params.get("keyword");
        String archiveCode = (String) params.get("archiveCode");
        String folderId = (String) params.get("folderId");
        int pageNum = params.containsKey("pageNum") ? ((Number) params.get("pageNum")).intValue() : 1;
        int pageSize = params.containsKey("pageSize") ? ((Number) params.get("pageSize")).intValue() : 10;

        Map<String, Object> searchResult = docManagementService.searchFulltext(keyword, archiveCode, folderId, pageNum, pageSize);
        return AjaxResult.success(searchResult);
    }
}
