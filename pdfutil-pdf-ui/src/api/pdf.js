import request from '@/utils/request'

/**
 * 单文件转换
 */
export function convertSingle(data, config = {}) {
  return request({
    url: '/pdf/convert/single',
    method: 'post',
    data: data,
    headers: {
      'Content-Type': 'multipart/form-data'
    },
    timeout: 120000,
    ...config
  })
}

/**
 * 批量文件转换
 */
export function convertBatch(data) {
  return request({
    url: '/pdf/convert/batch',
    method: 'post',
    data: data,
    headers: {
      'Content-Type': 'multipart/form-data'
    },
    timeout: 180000  // 批量上传超时设置为3分钟
  })
}

/**
 * 查询任务列表
 */
export function getTasks(params) {
  return request({
    url: '/pdf/convert/tasks',
    method: 'get',
    params: params
  })
}

/**
 * 获取任务详情
 */
export function getTaskDetail(id) {
  return request({
    url: `/pdf/convert/task/${id}`,
    method: 'get'
  })
}

/**
 * 重试任务
 */
export function retryTask(id) {
  return request({
    url: `/pdf/convert/task/${id}/retry`,
    method: 'post'
  })
}

/**
 * 删除任务
 */
export function deleteTask(id) {
  return request({
    url: `/pdf/convert/task/${id}`,
    method: 'delete'
  })
}

/**
 * 批量删除任务
 */
export function deleteTasks(ids) {
  return request({
    url: `/pdf/convert/tasks/${ids.join(',')}`,
    method: 'delete'
  })
}

/**
 * 下载文件
 */
export function downloadFile(id) {
  return `/api/pdf/convert/download/${id}`
}

/**
 * 获取系统概览统计
 */
export function getDashboardOverview() {
  return request({
    url: '/pdf/convert/dashboard/overview',
    method: 'get'
  })
}

/**
 * 获取最近任务
 */
export function getRecentTasks(params) {
  return request({
    url: '/pdf/convert/dashboard/recentTasks',
    method: 'get',
    params: params
  })
}

/**
 * 获取文件类型分布
 */
export function getFileTypeDistribution() {
  return request({
    url: '/pdf/convert/dashboard/fileTypeDistribution',
    method: 'get'
  })
}

/**
 * 批量下载文件（返回blob）
 */
export function batchDownloadFiles(ids) {
  return request({
    url: '/pdf/convert/batchExport',
    method: 'post',
    params: { ids: ids.join(',') }, // 将数组转为逗号分隔的字符串
    responseType: 'blob'
  })
}
