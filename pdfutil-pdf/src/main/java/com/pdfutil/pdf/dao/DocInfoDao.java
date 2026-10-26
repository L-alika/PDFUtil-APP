package com.pdfutil.pdf.dao;

import com.alibaba.fastjson.JSON;
import com.pdfutil.pdf.domain.DocInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 文档信息 DAO 数据库访问层
 * 支持 PostgreSQL 与本地 JSON 自动降级双轨运行
 *
 * @author Alika
 */
@Repository
public class DocInfoDao {

    private static final Logger log = LoggerFactory.getLogger(DocInfoDao.class);

    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    @Value("${pdfutil.pdf.dataDir:${user.home}/.pdfutil/data}")
    private String dataDir;

    private final Map<String, DocInfo> localCache = new ConcurrentHashMap<>();
    private File storageFile;

    @PostConstruct
    public void init() {
        try {
            File dir = new File(dataDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            storageFile = new File(dir, "doc_infos.json");
            if (storageFile.exists()) {
                String json = Files.readString(storageFile.toPath(), StandardCharsets.UTF_8);
                List<DocInfo> list = JSON.parseArray(json, DocInfo.class);
                if (list != null) {
                    for (DocInfo d : list) {
                        localCache.put(d.getId(), d);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("加载本地文档元数据缓存失败: {}", e.getMessage());
        }
    }

    private synchronized void persistLocal() {
        if (storageFile == null) return;
        try {
            String json = JSON.toJSONString(new ArrayList<>(localCache.values()), true);
            Files.writeString(storageFile.toPath(), json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("持久化本地文档记录失败: {}", e.getMessage());
        }
    }

    public int insert(DocInfo doc) {
        if (doc.getId() == null) {
            doc.setId(UUID.randomUUID().toString().replace("-", ""));
        }
        Date now = new Date();
        doc.setCreateTime(now);
        doc.setUpdateTime(now);

        localCache.put(doc.getId(), doc);
        persistLocal();

        if (jdbcTemplate != null) {
            try {
                String sql = "INSERT INTO doc_info (id, file_name, archive_code, file_ext, file_size, page_count, " +
                        "raw_storage_path, preview_pdf_path, thumb_path, convert_type, status, ocr_status, " +
                        "dept_id, folder_id, category, security_level, tags, create_by, create_time, update_time) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                return jdbcTemplate.update(sql,
                        doc.getId(), doc.getFileName(), doc.getArchiveCode(), doc.getFileExt(),
                        doc.getFileSize(), doc.getPageCount(), doc.getRawStoragePath(), doc.getPreviewPdfPath(),
                        doc.getThumbPath(), doc.getConvertType(), doc.getStatus(), doc.getOcrStatus(),
                        doc.getDeptId(), doc.getFolderId(), doc.getCategory(), doc.getSecurityLevel(), doc.getTags(),
                        doc.getCreateBy(), doc.getCreateTime(), doc.getUpdateTime());
            } catch (Exception ignored) {
            }
        }
        return 1;
    }

    public int update(DocInfo doc) {
        doc.setUpdateTime(new Date());
        localCache.put(doc.getId(), doc);
        persistLocal();

        if (jdbcTemplate != null) {
            try {
                String sql = "UPDATE doc_info SET file_name = ?, archive_code = ?, page_count = ?, " +
                        "raw_storage_path = ?, preview_pdf_path = ?, thumb_path = ?, status = ?, " +
                        "ocr_status = ?, folder_id = ?, update_time = ? WHERE id = ?";
                return jdbcTemplate.update(sql,
                        doc.getFileName(), doc.getArchiveCode(), doc.getPageCount(),
                        doc.getRawStoragePath(), doc.getPreviewPdfPath(), doc.getThumbPath(),
                        doc.getStatus(), doc.getOcrStatus(), doc.getFolderId(), new Date(), doc.getId());
            } catch (Exception ignored) {
            }
        }
        return 1;
    }

    public DocInfo findById(String id) {
        if (jdbcTemplate != null) {
            try {
                String sql = "SELECT * FROM doc_info WHERE id = ?";
                return jdbcTemplate.queryForObject(sql, new BeanPropertyRowMapper<>(DocInfo.class), id);
            } catch (Exception ignored) {
            }
        }
        return localCache.get(id);
    }

    public List<DocInfo> findList(String keyword, String archiveCode, String status, String deptId, String folderId, int page, int pageSize) {
        if (jdbcTemplate != null) {
            try {
                StringBuilder sql = new StringBuilder("SELECT * FROM doc_info WHERE 1=1 ");
                List<Object> params = new ArrayList<>();

                if (keyword != null && !keyword.trim().isEmpty()) {
                    sql.append("AND file_name ILIKE ? ");
                    params.add("%" + keyword.trim() + "%");
                }
                if (archiveCode != null && !archiveCode.trim().isEmpty()) {
                    sql.append("AND archive_code ILIKE ? ");
                    params.add("%" + archiveCode.trim() + "%");
                }
                if (status != null && !status.trim().isEmpty()) {
                    sql.append("AND status = ? ");
                    params.add(status.trim());
                }
                if (deptId != null && !deptId.trim().isEmpty()) {
                    sql.append("AND dept_id = ? ");
                    params.add(deptId.trim());
                }
                if (folderId != null && !folderId.trim().isEmpty() && !"ALL".equalsIgnoreCase(folderId)) {
                    sql.append("AND folder_id = ? ");
                    params.add(folderId.trim());
                }

                sql.append("ORDER BY create_time DESC LIMIT ? OFFSET ?");
                params.add(pageSize);
                params.add((Math.max(1, page) - 1) * pageSize);

                return jdbcTemplate.query(sql.toString(), new BeanPropertyRowMapper<>(DocInfo.class), params.toArray());
            } catch (Exception ignored) {
            }
        }

        // 本地降级过滤
        return localCache.values().stream()
                .filter(d -> keyword == null || keyword.isEmpty() || (d.getFileName() != null && d.getFileName().toLowerCase().contains(keyword.toLowerCase())))
                .filter(d -> archiveCode == null || archiveCode.isEmpty() || (d.getArchiveCode() != null && d.getArchiveCode().contains(archiveCode)))
                .filter(d -> status == null || status.isEmpty() || status.equalsIgnoreCase(d.getStatus()))
                .filter(d -> folderId == null || folderId.isEmpty() || "ALL".equalsIgnoreCase(folderId) || Objects.equals(folderId, d.getFolderId()))
                .sorted((a, b) -> {
                    if (a.getCreateTime() == null || b.getCreateTime() == null) return 0;
                    return b.getCreateTime().compareTo(a.getCreateTime());
                })
                .skip((long) (Math.max(1, page) - 1) * pageSize)
                .limit(pageSize)
                .collect(Collectors.toList());
    }

    public int count(String keyword, String archiveCode, String status, String deptId, String folderId) {
        if (jdbcTemplate != null) {
            try {
                StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM doc_info WHERE 1=1 ");
                List<Object> params = new ArrayList<>();

                if (keyword != null && !keyword.trim().isEmpty()) {
                    sql.append("AND file_name ILIKE ? ");
                    params.add("%" + keyword.trim() + "%");
                }
                if (archiveCode != null && !archiveCode.trim().isEmpty()) {
                    sql.append("AND archive_code ILIKE ? ");
                    params.add("%" + archiveCode.trim() + "%");
                }
                if (status != null && !status.trim().isEmpty()) {
                    sql.append("AND status = ? ");
                    params.add(status.trim());
                }
                if (deptId != null && !deptId.trim().isEmpty()) {
                    sql.append("AND dept_id = ? ");
                    params.add(deptId.trim());
                }
                if (folderId != null && !folderId.trim().isEmpty() && !"ALL".equalsIgnoreCase(folderId)) {
                    sql.append("AND folder_id = ? ");
                    params.add(folderId.trim());
                }

                Integer total = jdbcTemplate.queryForObject(sql.toString(), Integer.class, params.toArray());
                if (total != null) return total;
            } catch (Exception ignored) {
            }
        }

        return (int) localCache.values().stream()
                .filter(d -> keyword == null || keyword.isEmpty() || (d.getFileName() != null && d.getFileName().toLowerCase().contains(keyword.toLowerCase())))
                .filter(d -> archiveCode == null || archiveCode.isEmpty() || (d.getArchiveCode() != null && d.getArchiveCode().contains(archiveCode)))
                .filter(d -> status == null || status.isEmpty() || status.equalsIgnoreCase(d.getStatus()))
                .filter(d -> folderId == null || folderId.isEmpty() || "ALL".equalsIgnoreCase(folderId) || Objects.equals(folderId, d.getFolderId()))
                .count();
    }

    public int deleteById(String id) {
        localCache.remove(id);
        persistLocal();

        if (jdbcTemplate != null) {
            try {
                return jdbcTemplate.update("DELETE FROM doc_info WHERE id = ?", id);
            } catch (Exception ignored) {
            }
        }
        return 1;
    }
}
