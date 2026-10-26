package com.pdfutil.pdf.dao;

import com.alibaba.fastjson.JSON;
import com.pdfutil.pdf.domain.DocFolder;
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
 * 目录文件夹 DAO 数据访问层
 * 支持 PostgreSQL 与本地 JSON 双轨运行（开发/单机无缝降级）
 *
 * @author Alika
 */
@Repository
public class DocFolderDao {

    private static final Logger log = LoggerFactory.getLogger(DocFolderDao.class);

    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    @Value("${pdfutil.pdf.dataDir:${user.home}/.pdfutil/data}")
    private String dataDir;

    private final Map<String, DocFolder> localCache = new ConcurrentHashMap<>();
    private File storageFile;

    @PostConstruct
    public void init() {
        try {
            File dir = new File(dataDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            storageFile = new File(dir, "doc_folders.json");
            if (storageFile.exists()) {
                String json = Files.readString(storageFile.toPath(), StandardCharsets.UTF_8);
                List<DocFolder> list = JSON.parseArray(json, DocFolder.class);
                if (list != null) {
                    for (DocFolder f : list) {
                        localCache.put(f.getId(), f);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("加载本地目录文件夹缓存失败: {}", e.getMessage());
        }
    }

    private synchronized void persistLocal() {
        if (storageFile == null) return;
        try {
            String json = JSON.toJSONString(new ArrayList<>(localCache.values()), true);
            Files.writeString(storageFile.toPath(), json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("持久化本地目录文件夹失败: {}", e.getMessage());
        }
    }

    public List<DocFolder> listAll() {
        if (jdbcTemplate != null) {
            try {
                String sql = "SELECT * FROM doc_folder ORDER BY create_time ASC";
                return jdbcTemplate.query(sql, new BeanPropertyRowMapper<>(DocFolder.class));
            } catch (Exception ignored) {
                // 若表未建立则回退至本地存储
            }
        }
        return localCache.values().stream()
                .sorted(Comparator.comparing(DocFolder::getCreateTime, Comparator.nullsLast(Date::compareTo)))
                .collect(Collectors.toList());
    }

    public int insert(DocFolder folder) {
        if (folder.getId() == null) {
            folder.setId(UUID.randomUUID().toString().replace("-", ""));
        }
        Date now = new Date();
        folder.setCreateTime(now);
        folder.setUpdateTime(now);

        localCache.put(folder.getId(), folder);
        persistLocal();

        if (jdbcTemplate != null) {
            try {
                String sql = "INSERT INTO doc_folder (id, name, parent_id, create_by, create_time, update_time) VALUES (?, ?, ?, ?, ?, ?)";
                return jdbcTemplate.update(sql, folder.getId(), folder.getName(), folder.getParentId(), folder.getCreateBy(), folder.getCreateTime(), folder.getUpdateTime());
            } catch (Exception ignored) {
            }
        }
        return 1;
    }

    public int update(DocFolder folder) {
        folder.setUpdateTime(new Date());
        localCache.put(folder.getId(), folder);
        persistLocal();

        if (jdbcTemplate != null) {
            try {
                String sql = "UPDATE doc_folder SET name = ?, parent_id = ?, update_time = ? WHERE id = ?";
                return jdbcTemplate.update(sql, folder.getName(), folder.getParentId(), folder.getUpdateTime(), folder.getId());
            } catch (Exception ignored) {
            }
        }
        return 1;
    }

    public int delete(String id) {
        Set<String> toDeleteIds = new HashSet<>();
        collectDescendantIds(id, toDeleteIds);
        toDeleteIds.add(id);

        for (String delId : toDeleteIds) {
            localCache.remove(delId);
        }
        persistLocal();

        if (jdbcTemplate != null) {
            try {
                for (String delId : toDeleteIds) {
                    jdbcTemplate.update("DELETE FROM doc_folder WHERE id = ?", delId);
                }
            } catch (Exception ignored) {
            }
        }
        return toDeleteIds.size();
    }

    private void collectDescendantIds(String parentId, Set<String> result) {
        for (DocFolder f : localCache.values()) {
            if (parentId.equals(f.getParentId()) && !result.contains(f.getId())) {
                result.add(f.getId());
                collectDescendantIds(f.getId(), result);
            }
        }
    }

    public DocFolder findById(String id) {
        if (jdbcTemplate != null) {
            try {
                return jdbcTemplate.queryForObject("SELECT * FROM doc_folder WHERE id = ?", new BeanPropertyRowMapper<>(DocFolder.class), id);
            } catch (Exception ignored) {
            }
        }
        return localCache.get(id);
    }
}
