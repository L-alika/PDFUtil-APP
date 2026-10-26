-- ====================================================================
-- PDFUtil 服务器文档管理与全文检索系统 - 数据库初始化脚本
-- 数据库: PostgreSQL 15+
-- ====================================================================

-- 1. 创建文档信息主表 (doc_info)
CREATE TABLE IF NOT EXISTS doc_info (
    id VARCHAR(64) PRIMARY KEY,                         -- 文档唯一ID (UUID)
    file_name VARCHAR(255) NOT NULL,                    -- 原始文件名
    archive_code VARCHAR(100),                          -- 档号/案卷号 (如: J001-2024-017)
    file_ext VARCHAR(20),                               -- 文件后缀 (pdf, docx, jpg 等)
    file_size BIGINT DEFAULT 0,                         -- 原始文件大小 (字节)
    page_count INT DEFAULT 0,                           -- 文档总页数
    raw_storage_path VARCHAR(500),                      -- MinIO 原始文件存储路径
    preview_pdf_path VARCHAR(500),                      -- MinIO 转换后的双层预览PDF路径
    thumb_path VARCHAR(500),                            -- MinIO 首页缩略图路径
    convert_type VARCHAR(50),                           -- 转换类型 (PDF-DOUBLE, DOC-PDF, JPG-PDF等)
    status VARCHAR(20) DEFAULT 'WAITING',               -- 状态: WAITING(排队), PROCESSING(处理中), SUCCESS(已完成), FAILED(失败)
    ocr_status VARCHAR(20) DEFAULT 'NONE',              -- OCR状态: NONE(无), PENDING(待OCR), DONE(完成), FAILED(失败)
    dept_id VARCHAR(64) DEFAULT '0',                    -- 归属部门ID (用于权限隔离)
    category VARCHAR(100),                              -- 文档门类/分类
    security_level VARCHAR(20) DEFAULT 'PUBLIC',        -- 密级/保密程度 (PUBLIC, INTERNAL, SECRET)
    tags TEXT,                                          -- 标签(逗号分隔)
    create_by VARCHAR(64),                              -- 上传人/操作人
    create_time TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP, -- 创建时间
    update_time TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP  -- 更新时间
);

COMMENT ON TABLE doc_info IS '文档主信息表';
COMMENT ON COLUMN doc_info.id IS '文档唯一UUID';
COMMENT ON COLUMN doc_info.file_name IS '原始文件名';
COMMENT ON COLUMN doc_info.archive_code IS '档号/案卷号';
COMMENT ON COLUMN doc_info.raw_storage_path IS 'MinIO原始文件路径';
COMMENT ON COLUMN doc_info.preview_pdf_path IS 'MinIO双层预览PDF路径';

CREATE INDEX IF NOT EXISTS idx_doc_info_archive ON doc_info(archive_code);
CREATE INDEX IF NOT EXISTS idx_doc_info_status ON doc_info(status);
CREATE INDEX IF NOT EXISTS idx_doc_info_dept ON doc_info(dept_id);
CREATE INDEX IF NOT EXISTS idx_doc_info_create_time ON doc_info(create_time);


-- 2. 创建文档分页正文表 (doc_page)
CREATE TABLE IF NOT EXISTS doc_page (
    id BIGSERIAL PRIMARY KEY,                           -- 分页自增主键
    doc_id VARCHAR(64) NOT NULL,                        -- 关联的文档ID
    page_num INT NOT NULL,                              -- 页码 (从1开始)
    text_content TEXT,                                  -- 提取或OCR识别出的正文文本
    bbox_json TEXT,                                     -- 字符/段落坐标 JSON (用于精确高亮)
    thumb_path VARCHAR(500),                            -- 单页缩略图存储路径
    is_ocr BOOLEAN DEFAULT FALSE,                       -- 是否通过OCR识别得到
    char_count INT DEFAULT 0,                           -- 该页字符数
    create_time TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_doc_page_doc FOREIGN KEY (doc_id) REFERENCES doc_info(id) ON DELETE CASCADE
);

COMMENT ON TABLE doc_page IS '文档分页正文及坐标详情表';
COMMENT ON COLUMN doc_page.doc_id IS '关联文档ID';
COMMENT ON COLUMN doc_page.page_num IS '页码';
COMMENT ON COLUMN doc_page.text_content IS '本页正文全文';
COMMENT ON COLUMN doc_page.bbox_json IS '本页文字识别坐标信息';

CREATE INDEX IF NOT EXISTS idx_doc_page_doc_id ON doc_page(doc_id);
CREATE INDEX IF NOT EXISTS idx_doc_page_doc_page ON doc_page(doc_id, page_num);


-- 3. 创建异步后台任务状态表 (doc_task)
CREATE TABLE IF NOT EXISTS doc_task (
    task_id VARCHAR(64) PRIMARY KEY,                    -- 任务ID
    doc_id VARCHAR(64) NOT NULL,                        -- 关联文档ID
    task_type VARCHAR(50) NOT NULL,                     -- 任务类型 (CONVERT, OCR, INDEX_ES, BATCH_IMPORT)
    status VARCHAR(20) DEFAULT 'WAITING',               -- 任务状态: WAITING, PROCESSING, SUCCESS, FAILED, RETRYING
    progress INT DEFAULT 0,                             -- 处理进度 (0-100)
    retry_count INT DEFAULT 0,                          -- 已重试次数
    max_retries INT DEFAULT 3,                          -- 最大允许重试次数
    error_msg TEXT,                                     -- 异常/失败原因详细信息
    start_time TIMESTAMP WITHOUT TIME ZONE,             -- 任务开始时间
    end_time TIMESTAMP WITHOUT TIME ZONE,               -- 任务结束时间
    create_time TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE doc_task IS '文档异步转换与处理任务表';
CREATE INDEX IF NOT EXISTS idx_doc_task_status ON doc_task(status);
CREATE INDEX IF NOT EXISTS idx_doc_task_doc_id ON doc_task(doc_id);


-- 4. 创建系统基础用户表 (sys_user)
CREATE TABLE IF NOT EXISTS sys_user (
    user_id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(100) NOT NULL,                     -- 密码哈希
    nick_name VARCHAR(50),
    dept_id VARCHAR(64) DEFAULT '0',
    role_key VARCHAR(50) DEFAULT 'viewer',              -- 角色: admin(超级管理员), editor(录入员), viewer(查阅员)
    status CHAR(1) DEFAULT '0',                         -- 0正常 1停用
    create_time TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 默认插入管理员账号 (用户名: admin, 初始密码明文: admin123)
INSERT INTO sys_user (username, password, nick_name, role_key, status)
VALUES ('admin', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '系统管理员', 'admin', '0')
ON CONFLICT (username) DO NOTHING;
