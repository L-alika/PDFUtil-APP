package com.pdfutil.pdf.dao;

import com.alibaba.fastjson.JSON;
import com.pdfutil.pdf.domain.DocPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 文档分页正文 DAO 数据访问层
 * 支持 PostgreSQL 与本地磁盘 JSON 降级双轨运行
 *
 * @author Alika
 */
@Repository
public class DocPageDao {

    private static final Logger log = LoggerFactory.getLogger(DocPageDao.class);

    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    @Value("${pdfutil.pdf.dataDir:${user.home}/.pdfutil/data}")
    private String dataDir;

    private final Map<String, List<DocPage>> docPagesCache = new ConcurrentHashMap<>();
    private File storageFile;

    @PostConstruct
    public void init() {
        try {
            File dir = new File(dataDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            storageFile = new File(dir, "doc_pages.json");
            if (storageFile.exists()) {
                String json = Files.readString(storageFile.toPath(), StandardCharsets.UTF_8);
                List<DocPage> list = JSON.parseArray(json, DocPage.class);
                if (list != null) {
                    for (DocPage p : list) {
                        docPagesCache.computeIfAbsent(p.getDocId(), k -> new ArrayList<>()).add(p);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("加载本地分页文本缓存失败: {}", e.getMessage());
        }
    }

    private synchronized void persistLocal() {
        if (storageFile == null) return;
        try {
            List<DocPage> all = new ArrayList<>();
            for (List<DocPage> pages : docPagesCache.values()) {
                all.addAll(pages);
            }
            String json = JSON.toJSONString(all, false);
            Files.writeString(storageFile.toPath(), json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("持久化本地分页正文记录失败: {}", e.getMessage());
        }
    }

    /**
     * 批量插入分页正文与坐标
     */
    public int batchInsert(List<DocPage> pages) {
        if (pages == null || pages.isEmpty()) {
            return 0;
        }

        // 更新本地内存与磁盘
        for (DocPage p : pages) {
            List<DocPage> list = docPagesCache.computeIfAbsent(p.getDocId(), k -> new ArrayList<>());
            list.removeIf(existing -> existing.getPageNum() != null && existing.getPageNum().equals(p.getPageNum()));
            list.add(p);
        }
        persistLocal();

        // 尝试持久化到 PostgreSQL
        if (jdbcTemplate != null) {
            try {
                String sql = "INSERT INTO doc_page (doc_id, page_num, text_content, bbox_json, thumb_path, is_ocr, char_count, create_time) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
                Timestamp now = new Timestamp(System.currentTimeMillis());

                jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        DocPage page = pages.get(i);
                        ps.setString(1, page.getDocId());
                        ps.setInt(2, page.getPageNum() != null ? page.getPageNum() : (i + 1));
                        ps.setString(3, page.getTextContent());
                        ps.setString(4, page.getBboxJson());
                        ps.setString(5, page.getThumbPath());
                        ps.setBoolean(6, page.getIsOcr() != null ? page.getIsOcr() : false);
                        ps.setInt(7, page.getTextContent() != null ? page.getTextContent().length() : 0);
                        ps.setTimestamp(8, now);
                    }

                    @Override
                    public int getBatchSize() {
                        return pages.size();
                    }
                });
            } catch (Exception e) {
                log.warn("PostgreSQL 插入分页记录失败，已保留本地缓存: {}", e.getMessage());
            }
        }

        return pages.size();
    }

    /**
     * 根据文档ID查询所有分页文本
     */
    public List<DocPage> findByDocId(String docId) {
        if (jdbcTemplate != null) {
            try {
                String sql = "SELECT * FROM doc_page WHERE doc_id = ? ORDER BY page_num ASC";
                List<DocPage> list = jdbcTemplate.query(sql, new BeanPropertyRowMapper<>(DocPage.class), docId);
                if (list != null && !list.isEmpty()) {
                    return list;
                }
            } catch (Exception ignored) {
            }
        }

        List<DocPage> cached = docPagesCache.get(docId);
        if (cached != null) {
            List<DocPage> copy = new ArrayList<>(cached);
            copy.sort(Comparator.comparingInt(p -> p.getPageNum() != null ? p.getPageNum() : 0));
            return copy;
        }
        return new ArrayList<>();
    }

    /**
     * 查询指定文档的某特定页
     */
    public DocPage findByDocIdAndPageNum(String docId, int pageNum) {
        if (jdbcTemplate != null) {
            try {
                String sql = "SELECT * FROM doc_page WHERE doc_id = ? AND page_num = ?";
                return jdbcTemplate.queryForObject(sql, new BeanPropertyRowMapper<>(DocPage.class), docId, pageNum);
            } catch (Exception ignored) {
            }
        }

        List<DocPage> cached = docPagesCache.get(docId);
        if (cached != null) {
            for (DocPage p : cached) {
                if (p.getPageNum() != null && p.getPageNum() == pageNum) {
                    return p;
                }
            }
        }
        return null;
    }

    /**
     * 本地/数据库全文关键词检索降级方案
     */
    public List<DocPage> searchLocalPages(String keyword, int limit) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String lower = keyword.trim().toLowerCase();
        List<DocPage> matches = new ArrayList<>();

        for (List<DocPage> list : docPagesCache.values()) {
            for (DocPage p : list) {
                if (p.getTextContent() != null && p.getTextContent().toLowerCase().contains(lower)) {
                    matches.add(p);
                    if (matches.size() >= limit) {
                        return matches;
                    }
                }
            }
        }
        return matches;
    }

    /**
     * 删除文档关联的分页数据
     */
    public int deleteByDocId(String docId) {
        docPagesCache.remove(docId);
        persistLocal();

        if (jdbcTemplate != null) {
            try {
                return jdbcTemplate.update("DELETE FROM doc_page WHERE doc_id = ?", docId);
            } catch (Exception ignored) {
            }
        }
        return 1;
    }
}
