package com.pdfutil.pdf.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Elasticsearch 全文检索配置属性
 *
 * @author Alika
 */
@Component
@ConfigurationProperties(prefix = "pdfutil.elasticsearch")
public class EsSearchProperties {

    /** 是否启用 Elasticsearch 全文检索 */
    private boolean enabled = true;

    /** ES 主机地址 */
    private String host = "127.0.0.1";

    /** ES 端口 */
    private int port = 9200;

    /** 协议: http 或 https */
    private String scheme = "http";

    /** 默认核心分页索引名称 */
    private String indexName = "pdfutil_doc_page";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getScheme() {
        return scheme;
    }

    public void setScheme(String scheme) {
        this.scheme = scheme;
    }

    public String getIndexName() {
        return indexName;
    }

    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }
}
