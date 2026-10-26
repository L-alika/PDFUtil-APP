import request from '@/utils/request'

/**
 * 获取所有目录文件夹列表
 */
export function getFolders() {
  return request({
    url: '/server/doc/folders',
    method: 'get'
  })
}

/**
 * 新建文件夹
 */
export function createFolder(data) {
  return request({
    url: '/server/doc/folders',
    method: 'post',
    data: data
  })
}

/**
 * 重命名文件夹
 */
export function updateFolder(folderId, data) {
  return request({
    url: `/server/doc/folders/${folderId}`,
    method: 'put',
    data: data
  })
}

/**
 * 删除文件夹
 */
export function deleteFolder(folderId) {
  return request({
    url: `/server/doc/folders/${folderId}`,
    method: 'delete'
  })
}

/**
 * 上传文档至指定文件夹并触发全文解析与 OCR 索引
 */
export function uploadServerDoc(formData, onProgress) {
  return request({
    url: '/server/doc/upload',
    method: 'post',
    data: formData,
    headers: {
      'Content-Type': 'multipart/form-data'
    },
    timeout: 300000,
    onUploadProgress: onProgress
  })
}

/**
 * 查询文档列表 (支持按目录、关键词、档号过滤及分页)
 */
export function getServerDocList(params) {
  return request({
    url: '/server/doc/list',
    method: 'get',
    params: params
  })
}

/**
 * 删除文档
 */
export function deleteDoc(docId) {
  return request({
    url: `/server/doc/${docId}`,
    method: 'delete'
  })
}

/**
 * 查询文档详情及分页文本
 */
export function getServerDocDetail(docId) {
  return request({
    url: `/server/doc/detail/${docId}`,
    method: 'get'
  })
}

/**
 * 获取 PDF.js 在线安全流式预览 URL
 */
export function getPreviewPdfUrl(docId) {
  return request({
    url: `/server/doc/preview-url/${docId}`,
    method: 'get'
  })
}

/**
 * 获取原始文档直接下载 URL
 */
export function getDownloadRawUrl(docId) {
  return request({
    url: `/server/doc/download-url/${docId}`,
    method: 'get'
  })
}

/**
 * 查询后台任务执行进度与状态
 */
export function getTaskProgress(taskId) {
  return request({
    url: `/server/doc/task/${taskId}`,
    method: 'get'
  })
}

/**
 * 执行 Elasticsearch 全文检索
 */
export function searchFulltext(data) {
  return request({
    url: '/server/doc/search',
    method: 'post',
    data: data
  })
}
