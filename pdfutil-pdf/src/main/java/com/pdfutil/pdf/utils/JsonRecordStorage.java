package com.pdfutil.pdf.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.pdfutil.common.utils.StringUtils;
import com.pdfutil.pdf.domain.PdfConvertRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * JSON文件存储工具类
 * 用于替代数据库存储PDF转换记录
 *
 * @author Alika
 * @date 2025-02-27
 */
@Component
public class JsonRecordStorage {

    private static final Logger log = LoggerFactory.getLogger(JsonRecordStorage.class);

    private static final String RECORDS_FILE = "records.json";

    @Value("${pdfutil.pdf.dataDir:${user.home}/.pdfutil/data}")
    private String dataDir;

    private Path recordsFilePath;
    private AtomicLong idGenerator = new AtomicLong(0);
    private final Object lock = new Object();
    // 增加内存缓存以极大提高高并发读取性能并避免高频磁盘I/O与CPU消耗
    private final List<PdfConvertRecord> cache = new java.util.concurrent.CopyOnWriteArrayList<>();

    @PostConstruct
    public void init() {
        try {
            // 确保数据目录存在
            Path dataPath = Paths.get(dataDir);
            if (!Files.exists(dataPath)) {
                Files.createDirectories(dataPath);
                log.info("创建数据目录: {}", dataPath.toAbsolutePath());
            }

            recordsFilePath = dataPath.resolve(RECORDS_FILE);

            // 如果文件不存在，创建空文件并写入空数组
            if (!Files.exists(recordsFilePath)) {
                Files.write(recordsFilePath, "[]".getBytes(StandardCharsets.UTF_8));
                log.info("创建记录文件: {}", recordsFilePath.toAbsolutePath());
            }

            // 初始化时从磁盘加载数据到内存缓存中
            String content = new String(Files.readAllBytes(recordsFilePath), StandardCharsets.UTF_8);
            if (StringUtils.isNotEmpty(content) && !content.trim().isEmpty()) {
                JSONArray array = JSON.parseArray(content);
                List<PdfConvertRecord> records = new ArrayList<>();
                for (int i = 0; i < array.size(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    PdfConvertRecord record = obj.toJavaObject(PdfConvertRecord.class);
                    records.add(record);
                }
                cache.addAll(records);
            }

            // 初始化ID生成器
            if (!cache.isEmpty()) {
                Long maxId = cache.stream()
                        .map(PdfConvertRecord::getId)
                        .max(Comparator.naturalOrder())
                        .orElse(0L);
                idGenerator.set(maxId + 1);
            }

            log.info("JSON存储初始化完成，当前记录数: {}", cache.size());
        } catch (Exception e) {
            log.error("初始化JSON存储失败", e);
            throw new RuntimeException("初始化JSON存储失败", e);
        }
    }

    /**
     * 加载所有记录
     * 从内存缓存直接读取，无需加锁，支持高并发并发读取
     */
    public List<PdfConvertRecord> loadAll() {
        return new ArrayList<>(cache);
    }

    /**
     * 保存所有记录
     * 同步更新内存缓存并持久化到磁盘中
     */
    private void saveRecords(List<PdfConvertRecord> records) {
        synchronized (lock) {
            try {
                cache.clear();
                if (records != null) {
                    cache.addAll(records);
                }
                String json = JSON.toJSONString(records, true);
                Files.write(recordsFilePath, json.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                log.error("保存记录失败", e);
                throw new RuntimeException("保存记录失败", e);
            }
        }
    }

    /**
     * 生成新ID
     */
    public Long generateId() {
        return idGenerator.getAndIncrement();
    }

    /**
     * 根据ID查询记录
     */
    public PdfConvertRecord findById(Long id) {
        if (id == null) {
            return null;
        }
        return loadAll().stream()
                .filter(r -> id.equals(r.getId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 查询记录列表
     */
    public List<PdfConvertRecord> findList(PdfConvertRecord query) {
        List<PdfConvertRecord> records = loadAll();

        if (query == null) {
            return records;
        }

        return records.stream()
                .filter(r -> {
                    if (StringUtils.isNotEmpty(query.getSourceFileName()) &&
                            !r.getSourceFileName().contains(query.getSourceFileName())) {
                        return false;
                    }
                    if (StringUtils.isNotEmpty(query.getStatus()) &&
                            !query.getStatus().equals(r.getStatus())) {
                        return false;
                    }
                    if (StringUtils.isNotEmpty(query.getConvertType()) &&
                            !query.getConvertType().equals(r.getConvertType())) {
                        return false;
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }

    /**
     * 插入记录
     */
    public int insert(PdfConvertRecord record) {
        if (record == null) {
            return 0;
        }

        synchronized (lock) {
            List<PdfConvertRecord> records = loadAll();

            if (record.getId() == null) {
                record.setId(generateId());
            }

            records.add(0, record); // 新记录放在最前面
            saveRecords(records);

            log.debug("插入记录: id={}, fileName={}", record.getId(), record.getSourceFileName());
            return 1;
        }
    }

    /**
     * 更新记录
     */
    public int update(PdfConvertRecord record) {
        if (record == null || record.getId() == null) {
            return 0;
        }

        synchronized (lock) {
            List<PdfConvertRecord> records = loadAll();

            for (int i = 0; i < records.size(); i++) {
                if (record.getId().equals(records.get(i).getId())) {
                    records.set(i, record);
                    saveRecords(records);
                    log.debug("更新记录: id={}", record.getId());
                    return 1;
                }
            }

            return 0;
        }
    }

    /**
     * 根据ID删除记录
     */
    public int deleteById(Long id) {
        if (id == null) {
            return 0;
        }

        synchronized (lock) {
            List<PdfConvertRecord> records = loadAll();
            int originalSize = records.size();

            records = records.stream()
                    .filter(r -> !id.equals(r.getId()))
                    .collect(Collectors.toList());

            if (records.size() < originalSize) {
                saveRecords(records);
                log.debug("删除记录: id={}", id);
                return 1;
            }

            return 0;
        }
    }

    /**
     * 批量删除记录
     */
    public int deleteByIds(Long[] ids) {
        if (ids == null || ids.length == 0) {
            return 0;
        }

        synchronized (lock) {
            List<PdfConvertRecord> records = loadAll();
            int originalSize = records.size();

            List<Long> idList = new ArrayList<>();
            for (Long id : ids) {
                idList.add(id);
            }

            records = records.stream()
                    .filter(r -> !idList.contains(r.getId()))
                    .collect(Collectors.toList());

            int deleted = originalSize - records.size();
            if (deleted > 0) {
                saveRecords(records);
                log.debug("批量删除记录: count={}", deleted);
            }

            return deleted;
        }
    }

    /**
     * 获取记录总数
     */
    public long count() {
        return loadAll().size();
    }

    /**
     * 分页查询
     */
    public List<PdfConvertRecord> findByPage(int pageNum, int pageSize) {
        List<PdfConvertRecord> records = loadAll();

        int start = (pageNum - 1) * pageSize;
        int end = Math.min(start + pageSize, records.size());

        if (start >= records.size()) {
            return new ArrayList<>();
        }

        return records.subList(start, end);
    }

    /**
     * 清空所有记录
     */
    public int clearAll() {
        synchronized (lock) {
            try {
                int count = loadAll().size();
                saveRecords(new ArrayList<>());
                // 重置ID生成器
                idGenerator.set(0);
                log.info("清空所有记录: count={}", count);
                return count;
            } catch (Exception e) {
                log.error("清空记录失败", e);
                throw new RuntimeException("清空记录失败", e);
            }
        }
    }
}