package com.pdfutil.pdf.dao;

import com.alibaba.fastjson.JSON;
import com.pdfutil.pdf.domain.DocTask;
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

/**
 * 异步处理任务 DAO 数据访问层
 * 支持 PostgreSQL 与本地磁盘 JSON 降级双轨自愈运行
 *
 * @author Alika
 */
@Repository
public class DocTaskDao {

    private static final Logger log = LoggerFactory.getLogger(DocTaskDao.class);

    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    @Value("${pdfutil.pdf.dataDir:${user.home}/.pdfutil/data}")
    private String dataDir;

    private final Map<String, DocTask> localCache = new ConcurrentHashMap<>();
    private File storageFile;

    @PostConstruct
    public void init() {
        try {
            File dir = new File(dataDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            storageFile = new File(dir, "doc_tasks.json");
            if (storageFile.exists()) {
                String json = Files.readString(storageFile.toPath(), StandardCharsets.UTF_8);
                List<DocTask> list = JSON.parseArray(json, DocTask.class);
                if (list != null) {
                    for (DocTask t : list) {
                        localCache.put(t.getTaskId(), t);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("加载本地任务缓存失败: {}", e.getMessage());
        }
    }

    private synchronized void persistLocal() {
        if (storageFile == null) return;
        try {
            String json = JSON.toJSONString(new ArrayList<>(localCache.values()), true);
            Files.writeString(storageFile.toPath(), json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("持久化本地任务记录失败: {}", e.getMessage());
        }
    }

    public int insert(DocTask task) {
        Date now = new Date();
        if (task.getCreateTime() == null) {
            task.setCreateTime(now);
        }
        task.setUpdateTime(now);

        localCache.put(task.getTaskId(), task);
        persistLocal();

        if (jdbcTemplate != null) {
            try {
                String sql = "INSERT INTO doc_task (task_id, doc_id, task_type, status, progress, retry_count, " +
                        "max_retries, error_msg, start_time, end_time, create_time, update_time) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                return jdbcTemplate.update(sql,
                        task.getTaskId(), task.getDocId(), task.getTaskType(), task.getStatus(),
                        task.getProgress() != null ? task.getProgress() : 0,
                        task.getRetryCount() != null ? task.getRetryCount() : 0,
                        task.getMaxRetries() != null ? task.getMaxRetries() : 3,
                        task.getErrorMsg(), task.getStartTime(), task.getEndTime(),
                        task.getCreateTime(), task.getUpdateTime());
            } catch (Exception e) {
                log.warn("PostgreSQL 插入任务记录失败，已保留本地缓存: {}", e.getMessage());
            }
        }

        return 1;
    }

    public int update(DocTask task) {
        task.setUpdateTime(new Date());
        localCache.put(task.getTaskId(), task);
        persistLocal();

        if (jdbcTemplate != null) {
            try {
                String sql = "UPDATE doc_task SET status = ?, progress = ?, retry_count = ?, " +
                        "error_msg = ?, start_time = ?, end_time = ?, update_time = ? WHERE task_id = ?";
                return jdbcTemplate.update(sql,
                        task.getStatus(), task.getProgress(), task.getRetryCount(),
                        task.getErrorMsg(), task.getStartTime(), task.getEndTime(),
                        task.getUpdateTime(), task.getTaskId());
            } catch (Exception e) {
                log.warn("PostgreSQL 更新任务记录失败，已保留本地缓存: {}", e.getMessage());
            }
        }

        return 1;
    }

    public DocTask findById(String taskId) {
        if (jdbcTemplate != null) {
            try {
                String sql = "SELECT * FROM doc_task WHERE task_id = ?";
                return jdbcTemplate.queryForObject(sql, new BeanPropertyRowMapper<>(DocTask.class), taskId);
            } catch (Exception ignored) {
            }
        }

        return localCache.get(taskId);
    }

    public DocTask findByDocId(String docId) {
        if (docId == null) return null;
        for (DocTask t : localCache.values()) {
            if (docId.equals(t.getDocId())) {
                return t;
            }
        }
        return null;
    }

    /**
     * 查询服务重启时需要恢复的孤儿任务（状态处于 PROCESSING 的任务）
     */
    public List<DocTask> findRecoverableTasks() {
        if (jdbcTemplate != null) {
            try {
                String sql = "SELECT * FROM doc_task WHERE status = 'PROCESSING' ORDER BY create_time ASC";
                List<DocTask> list = jdbcTemplate.query(sql, new BeanPropertyRowMapper<>(DocTask.class));
                if (list != null && !list.isEmpty()) {
                    return list;
                }
            } catch (Exception ignored) {
            }
        }

        List<DocTask> recoverable = new ArrayList<>();
        for (DocTask t : localCache.values()) {
            if ("PROCESSING".equals(t.getStatus()) || "WAITING".equals(t.getStatus())) {
                recoverable.add(t);
            }
        }
        return recoverable;
    }

    /**
     * 查询排队中的任务
     */
    public List<DocTask> findWaitingTasks(int limit) {
        List<DocTask> waiting = new ArrayList<>();
        for (DocTask t : localCache.values()) {
            if ("WAITING".equals(t.getStatus())) {
                waiting.add(t);
                if (waiting.size() >= limit) break;
            }
        }
        return waiting;
    }
}
