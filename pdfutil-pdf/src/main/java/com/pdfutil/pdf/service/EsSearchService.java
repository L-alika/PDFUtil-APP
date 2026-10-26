package com.pdfutil.pdf.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.pdfutil.pdf.config.EsSearchProperties;
import com.pdfutil.pdf.domain.DocPage;
import org.apache.http.HttpHost;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Elasticsearch 全文检索核心服务
 *
 * 负责分页正文索引写入、IK 中文分词搜索、高亮摘要抽取及档号/部门复合过滤。
 *
 * @author Alika
 */
@Service
public class EsSearchService {

    private static final Logger log = LoggerFactory.getLogger(EsSearchService.class);

    @Autowired
    private EsSearchProperties esProperties;

    private RestClient restClient;

    @PostConstruct
    public void init() {
        if (!esProperties.isEnabled()) {
            log.info("Elasticsearch 全文检索功能未开启。");
            return;
        }

        try {
            this.restClient = RestClient.builder(
                    new HttpHost(esProperties.getHost(), esProperties.getPort(), esProperties.getScheme())
            ).setRequestConfigCallback(requestConfigBuilder ->
                    requestConfigBuilder.setConnectTimeout(5000).setSocketTimeout(30000)
            ).build();

            log.info("Elasticsearch 客户端初始化成功: {}://{}:{}",
                    esProperties.getScheme(), esProperties.getHost(), esProperties.getPort());

            // 异步或安全检查核心索引
            ensureIndex();
        } catch (Exception e) {
            log.warn("Elasticsearch 连接异常 (若服务尚未就绪，将在后续检索时重试): {}", e.getMessage());
        }
    }

    @PreDestroy
    public void close() {
        if (restClient != null) {
            try {
                restClient.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 确保索引存在，并配置 IK 分词与字段 Mapping
     */
    public synchronized boolean ensureIndex() {
        if (restClient == null) {
            return false;
        }

        String indexName = esProperties.getIndexName();
        try {
            Request headRequest = new Request("HEAD", "/" + indexName);
            Response headResponse = restClient.performRequest(headRequest);
            if (headResponse.getStatusLine().getStatusCode() == 200) {
                return true;
            }
        } catch (Exception ignored) {
            // 索引不存在时会抛出异常，继续执行创建
        }

        try {
            Request putRequest = new Request("PUT", "/" + indexName);
            // 默认采用 ik_max_word，若用户未装 ik 分词则自动优雅降级为 standard
            JSONObject mappingJson = new JSONObject();
            JSONObject properties = new JSONObject();

            properties.put("doc_id", Collections.singletonMap("type", "keyword"));
            properties.put("page_num", Collections.singletonMap("type", "integer"));
            properties.put("archive_code", Collections.singletonMap("type", "keyword"));
            properties.put("dept_id", Collections.singletonMap("type", "keyword"));
            properties.put("create_time", Collections.singletonMap("type", "date"));

            Map<String, Object> textType = new HashMap<>();
            textType.put("type", "text");
            textType.put("analyzer", "ik_max_word");
            textType.put("search_analyzer", "ik_smart");

            properties.put("file_name", textType);
            properties.put("content", textType);

            mappingJson.put("mappings", Collections.singletonMap("properties", properties));

            putRequest.setEntity(new NStringEntity(mappingJson.toJSONString(), ContentType.APPLICATION_JSON));
            Response putResponse = restClient.performRequest(putRequest);
            log.info("成功创建 Elasticsearch 索引: {}, 状态码: {}", indexName, putResponse.getStatusLine().getStatusCode());
            return true;
        } catch (Exception e) {
            log.warn("创建 Elasticsearch 索引失败 (若 IK 分词器未安装可尝试标准分词): {}", e.getMessage());
            return false;
        }
    }

    /**
     * 批量向 Elasticsearch 写入文档分页正文索引
     */
    public boolean bulkIndexDocPages(String docId, String fileName, String archiveCode, String deptId, List<DocPage> pages) {
        if (restClient == null || pages == null || pages.isEmpty()) {
            return false;
        }

        ensureIndex();

        StringBuilder bulkBody = new StringBuilder();
        String indexName = esProperties.getIndexName();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

        for (DocPage page : pages) {
            if (page.getTextContent() == null || page.getTextContent().trim().isEmpty()) {
                continue;
            }

            String docIndexId = docId + "_" + page.getPageNum();
            JSONObject action = new JSONObject();
            action.put("index", Collections.singletonMap("_id", docIndexId));
            bulkBody.append(action.toJSONString()).append("\n");

            JSONObject source = new JSONObject();
            source.put("doc_id", docId);
            source.put("page_num", page.getPageNum());
            source.put("file_name", fileName);
            source.put("archive_code", archiveCode);
            source.put("dept_id", deptId);
            source.put("content", page.getTextContent());
            source.put("create_time", sdf.format(new Date()));

            bulkBody.append(source.toJSONString()).append("\n");
        }

        if (bulkBody.length() == 0) {
            return true;
        }

        try {
            Request bulkRequest = new Request("POST", "/" + indexName + "/_bulk");
            bulkRequest.setEntity(new NStringEntity(bulkBody.toString(), ContentType.APPLICATION_JSON));
            Response response = restClient.performRequest(bulkRequest);
            return response.getStatusLine().getStatusCode() == 200;
        } catch (Exception e) {
            log.error("批量推送分页数据至 Elasticsearch 失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 执行全文检索，带高亮标红、档号与部门权限过滤
     *
     * @param keyword 检索正文或文件名关键词
     * @param archiveCode 档号过滤
     * @param deptId 部门过滤
     * @param page 分页页码
     * @param pageSize 每页条数
     * @return 包含命中分页列表与高亮摘要的结果集
     */
    public Map<String, Object> search(String keyword, String archiveCode, String deptId, int page, int pageSize) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> hitsList = new ArrayList<>();
        result.put("total", 0);
        result.put("items", hitsList);

        if (restClient == null || ((keyword == null || keyword.trim().isEmpty())
                && (archiveCode == null || archiveCode.trim().isEmpty()))) {
            return result;
        }

        try {
            JSONObject queryObj = new JSONObject();
            JSONObject boolObj = new JSONObject();
            JSONArray mustArray = new JSONArray();
            JSONArray filterArray = new JSONArray();

            if (keyword != null && !keyword.trim().isEmpty()) {
                JSONObject multiMatch = new JSONObject();
                multiMatch.put("query", keyword.trim());
                multiMatch.put("fields", Arrays.asList("content^2", "file_name^3", "archive_code^4"));
                mustArray.add(Collections.singletonMap("multi_match", multiMatch));
            } else {
                mustArray.add(Collections.singletonMap("match_all", new JSONObject()));
            }

            if (archiveCode != null && !archiveCode.trim().isEmpty()) {
                filterArray.add(Collections.singletonMap("term", Collections.singletonMap("archive_code", archiveCode.trim())));
            }
            if (deptId != null && !deptId.trim().isEmpty()) {
                filterArray.add(Collections.singletonMap("term", Collections.singletonMap("dept_id", deptId.trim())));
            }

            boolObj.put("must", mustArray);
            if (!filterArray.isEmpty()) {
                boolObj.put("filter", filterArray);
            }
            queryObj.put("query", Collections.singletonMap("bool", boolObj));

            // 分页
            queryObj.put("from", (Math.max(1, page) - 1) * pageSize);
            queryObj.put("size", pageSize);

            // 高亮
            JSONObject highlightObj = new JSONObject();
            highlightObj.put("pre_tags", Collections.singletonList("<span class=\"search-hit-keyword\">"));
            highlightObj.put("post_tags", Collections.singletonList("</span>"));
            Map<String, Object> contentField = new HashMap<>();
            contentField.put("fragment_size", 120);
            contentField.put("number_of_fragments", 2);
            highlightObj.put("fields", Collections.singletonMap("content", contentField));
            queryObj.put("highlight", highlightObj);

            Request request = new Request("POST", "/" + esProperties.getIndexName() + "/_search");
            request.setEntity(new NStringEntity(queryObj.toJSONString(), ContentType.APPLICATION_JSON));

            Response response = restClient.performRequest(request);
            String responseBody = EntityUtils.toString(response.getEntity());
            JSONObject esResponse = JSON.parseObject(responseBody);

            JSONObject hitsRoot = esResponse.getJSONObject("hits");
            if (hitsRoot != null) {
                int total = hitsRoot.getJSONObject("total").getIntValue("value");
                result.put("total", total);

                JSONArray hits = hitsRoot.getJSONArray("hits");
                for (int i = 0; i < hits.size(); i++) {
                    JSONObject hitItem = hits.getJSONObject(i);
                    JSONObject source = hitItem.getJSONObject("_source");
                    JSONObject highlight = hitItem.getJSONObject("highlight");

                    Map<String, Object> itemMap = new HashMap<>();
                    itemMap.put("docId", source.getString("doc_id"));
                    itemMap.put("pageNum", source.getInteger("page_num"));
                    itemMap.put("fileName", source.getString("file_name"));
                    itemMap.put("archiveCode", source.getString("archive_code"));
                    itemMap.put("deptId", source.getString("dept_id"));

                    if (highlight != null && highlight.containsKey("content")) {
                        JSONArray snippets = highlight.getJSONArray("content");
                        itemMap.put("snippet", String.join(" ... ", snippets.toJavaList(String.class)));
                    } else {
                        String rawContent = source.getString("content");
                        itemMap.put("snippet", rawContent != null && rawContent.length() > 100
                                ? rawContent.substring(0, 100) + "..." : rawContent);
                    }
                    hitsList.add(itemMap);
                }
            }
        } catch (Exception e) {
            log.error("执行 Elasticsearch 全文检索失败: {}", e.getMessage());
        }

        return result;
    }
}
