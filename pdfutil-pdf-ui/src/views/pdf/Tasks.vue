<template>
  <div class="tasks-page">
    <el-card class="content-card">
      <template #header>
        <div class="card-header">
          <div>
            <h2><el-icon><List /></el-icon> 批次记录</h2>
            <p class="subtitle">查看并管理系统中所有的PDF双层化转换任务。</p>
          </div>
          <div>
            <el-button type="danger" :disabled="selectedTasks.length === 0" @click="handleBatchDelete">
              <el-icon><Delete /></el-icon> 批量删除 ({{ selectedTasks.length }})
            </el-button>
            <el-button type="success" :disabled="selectedTasks.length === 0" @click="batchDownload">
              <el-icon><Download /></el-icon> 批量下载 ({{ selectedTasks.length }})
            </el-button>
          </div>
        </div>
      </template>

      <!-- 搜索栏 -->
      <div class="search-bar">
        <el-input
          v-model="searchKeyword"
          placeholder="输入关键字搜索..."
          style="width: 300px"
          @keyup.enter="handleSearch"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>

        <el-select v-model="searchStatus" placeholder="全部状态" style="width: 150px" @change="loadTasks">
          <el-option label="全部状态" value="" />
          <el-option label="待处理" value="0" />
          <el-option label="转换中" value="1" />
          <el-option label="已完成" value="2" />
          <el-option label="已失败" value="3" />
        </el-select>

        <el-button type="primary" @click="handleSearch">
          <el-icon><Search /></el-icon> 查询
        </el-button>

        <el-button @click="refreshTasks">
          <el-icon><Refresh /></el-icon> 刷新
        </el-button>
      </div>

      <!-- 任务表格 -->
      <el-table
        ref="tableRef"
        :data="tasks"
        row-key="id"
        style="width: 100%; flex: 1;"
        v-loading="loading"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="55" />
        <el-table-column prop="createBy" label="操作人" width="120" />
        <el-table-column prop="sourceFileName" label="源文件名" min-width="200" />
        <el-table-column prop="targetFileName" label="目标文件" width="180">
          <template #default="{ row }">
            {{ row.targetFileName || '生成中...' }}
          </template>
        </el-table-column>
        <el-table-column prop="convertType" label="转换类型" width="150" />
        <el-table-column prop="createTime" label="转换时间" width="180" />
        <el-table-column prop="status" label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="viewDetail(row)">
              <el-icon><InfoFilled /></el-icon> 详情
            </el-button>
            <el-button
              v-if="row.status === '2'"
              link
              type="success"
              size="small"
              @click="downloadFile(row)"
            >
              <el-icon><Download /></el-icon> 下载
            </el-button>
            <el-button
              v-if="row.status === '3'"
              link
              type="warning"
              size="small"
              @click="retryTask(row)"
            >
              <el-icon><Refresh /></el-icon> 重试
            </el-button>
            <el-button
              v-if="row.status !== '1'"
              link
              type="danger"
              size="small"
              @click="handleDelete(row)"
            >
              <el-icon><Delete /></el-icon> 删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页组件 -->
      <div>
        <el-pagination
          class="pagination"
          :current-page="pagination.pageNum"
          :page-size="pagination.pageSize"
          :page-sizes="[10, 20, 50, 100]"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="handleSizeChange"
          @current-change="handleCurrentChange"
          :hide-on-single-page="false"
        />
      </div>
    </el-card>

    <!-- 详情对话框 -->
    <el-dialog v-model="detailVisible" title="任务详情" width="700px">
      <div v-if="currentTask" class="task-detail">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="任务ID">{{ currentTask.id }}</el-descriptions-item>
          <el-descriptions-item label="源文件名">{{ currentTask.sourceFileName }}</el-descriptions-item>
          <el-descriptions-item label="源文件路径">{{ currentTask.sourceFilePath }}</el-descriptions-item>
          <el-descriptions-item label="目标文件名">{{ currentTask.targetFileName || '生成中...' }}</el-descriptions-item>
          <el-descriptions-item label="目标文件路径">{{ currentTask.targetFilePath }}</el-descriptions-item>
          <el-descriptions-item label="转换类型">{{ currentTask.convertType }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="getStatusType(currentTask.status)">
              {{ getStatusText(currentTask.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ currentTask.createTime }}</el-descriptions-item>
          <el-descriptions-item label="创建人">{{ currentTask.createBy || 'anonymous' }}</el-descriptions-item>
          <el-descriptions-item v-if="currentTask.status === '3'" label="错误提示">
            <div class="error-message">
              <el-icon class="error-icon"><WarningFilled /></el-icon>
              <span>{{ getUserFriendlyError(currentTask.failReason) }}</span>
            </div>
          </el-descriptions-item>
        </el-descriptions>

        <div v-if="currentTask.status === '1'" class="progress-section">
          <p><strong>转换进度：</strong></p>
          <el-progress :percentage="currentTask.progress || 0" :status="currentTask.progress === 100 ? 'success' : undefined" />
        </div>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button v-if="currentTask && currentTask.status === '2'" type="primary" @click="downloadFile(currentTask)">
          <el-icon><Download /></el-icon> 下载文件
        </el-button>
        <el-button v-if="currentTask && currentTask.status === '3'" type="warning" @click="retryTask(currentTask)">
          <el-icon><Refresh /></el-icon> 重试转换
        </el-button>
        <el-button v-if="currentTask && currentTask.status !== '1'" type="danger" @click="handleDelete(currentTask)">
          <el-icon><Delete /></el-icon> 删除任务
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox, ElLoading } from 'element-plus'
import { getTasks, getTaskDetail, retryTask as retryTaskApi, deleteTask, deleteTasks, batchDownloadFiles } from '@/api/pdf'

const loading = ref(false)
const tasks = ref([])
const searchKeyword = ref('')
const searchStatus = ref('')  // 添加搜索状态变量
const detailVisible = ref(false)
const currentTask = ref(null)
const selectedTasks = ref([])
const tableRef = ref(null)

// 分页相关
const pagination = ref({
  pageNum: 1,
  pageSize: 20,
  total: 0
})

onMounted(() => {
  loadTasks() // 首次加载
  // 注释掉定时刷新功能，因为会导致分页被重置
  // setInterval(() => {
  //   loadTasks()
  // }, 10000)
})

const loadTasks = async () => {
  console.log(`请求第${pagination.value.pageNum}页，每页${pagination.value.pageSize}条`)
  console.log(`当前搜索关键词: ${searchKeyword.value}`)
  console.log(`当前搜索状态: ${searchStatus.value}`)
  loading.value = true
  try {
    // 构建请求参数
    const params = {
      pageNum: pagination.value.pageNum,
      pageSize: pagination.value.pageSize,
      sourceFileName: searchKeyword.value,
      status: searchStatus.value
    }
    console.log('发送的请求参数:', params)
    
    // 发送请求
    const res = await getTasks(params)

    console.log('完整的API响应:', JSON.stringify(res))

    // 检查响应结构是否正确
    if (!res || typeof res !== 'object') {
      console.error('无效的API响应:', res)
      ElMessage.error('获取任务列表失败：API响应格式错误')
      return
    }

    // 检查响应中是否有rows字段
    if (!res.rows) {
      console.error('API响应中缺少rows字段:', res)
      ElMessage.error('获取任务列表失败：API响应格式错误')
      return
    }

    // 更新分页信息
    const newTotal = res.total || 0
    console.log(`更新总数: ${pagination.value.total} -> ${newTotal}`)
    pagination.value.total = newTotal

    console.log(`收到${res.rows.length}条数据，总共${res.total || 0}条记录`)

    // 直接更新数据，不需要复杂的比较逻辑
    const oldCount = tasks.value.length
    tasks.value = res.rows
    console.log(`更新任务列表: ${oldCount} -> ${tasks.value.length} 条`)

    // 恢复选中状态
    await restoreSelectionState()
  } catch (error) {
    console.error('加载任务列表失败', error)
    ElMessage.error('加载任务列表失败: ' + (error.message || '网络错误'))
  } finally {
    loading.value = false
  }
}

// 恢复选中状态
const restoreSelectionState = async () => {
  await nextTick() // 等待DOM更新完成

  const savedIds = localStorage.getItem('selectedTaskIds')
  if (!savedIds || !tableRef.value) {
    return
  }

  try {
    const selectedIds = JSON.parse(savedIds)

    // 清除所有选中
    tableRef.value.clearSelection()

    // 选中之前保存的任务
    tasks.value.forEach(row => {
      if (selectedIds.includes(row.id)) {
        tableRef.value.toggleRowSelection(row, true)
      }
    })
  } catch (error) {
    console.error('恢复选中状态失败', error)
  }
}

// 分页大小改变
const handleSizeChange = (size) => {
  console.log(`分页大小从 ${pagination.value.pageSize} 改为 ${size}`)
  pagination.value.pageSize = size
  pagination.value.pageNum = 1 // 重置为第一页
  console.log(`重新加载任务列表，参数: 页码=${pagination.value.pageNum}, 每页=${pagination.value.pageSize}`)
  loadTasks()
}

// 当前页改变
const handleCurrentChange = (page) => {
  console.log(`切换到第${page}页，当前每页${pagination.value.pageSize}条，总共有${pagination.value.total}条`)
  console.log(`当前页码从 ${pagination.value.pageNum} 更新为 ${page}`)
  pagination.value.pageNum = page
  console.log(`重新加载任务列表，参数: 页码=${pagination.value.pageNum}, 每页=${pagination.value.pageSize}`)
  loadTasks()
}

// 处理搜索
const handleSearch = () => {
  pagination.value.pageNum = 1 // 搜索时重置到第一页
  loadTasks()
}

// 刷新任务列表（保持当前页）
const refreshTasks = () => {
  loadTasks()
}

const getStatusType = (status) => {
  const types = {
    '0': 'info',
    '1': 'warning',
    '2': 'success',
    '3': 'danger'
  }
  return types[status] || 'info'
}

const getStatusText = (status) => {
  const texts = {
    '0': '待处理',
    '1': '转换中',
    '2': '已完成',
    '3': '已失败'
  }
  return texts[status] || '未知'
}

/**
 * 将技术错误信息转换为用户友好的提示
 */
const getUserFriendlyError = (error) => {
  if (!error) {
    return '未知错误，请联系管理员'
  }

  // 常见错误映射
  const errorMap = {
    // LibreOffice 相关错误
    'Cannot run program "soffice"': 'LibreOffice 未安装或未正确配置',
    'CreateProcess error=2': 'LibreOffice 程序找不到，请检查安装路径',
    'LibreOffice 转换失败': 'Office 文档转换失败，请检查文件是否损坏',

    // OCRmyPDF 相关错误
    'TaggedPDFError': '文件已包含文本层，但系统无法处理此格式',
    'UnsupportedImageFormatError': '不支持的图片格式',
    'cannot identify image file': '无法识别图片文件，请确认文件格式',
    '命令执行失败': 'PDF 转换命令执行失败',

    // 文件相关错误
    'FileNotFoundException': '文件不存在或已被删除',
    '输入文件不存在': '源文件不存在',
    '不支持的文件格式': '文件格式不支持，请上传 PDF、JPG/JPEG（RGB色彩空间）、TIF、PNG、BMP 或 OFD 文件',

    // 通用错误
    'RuntimeException': '系统处理异常',
    'Exception': '系统错误'
  }

  // 查找匹配的错误类型
  for (const [key, friendlyMessage] of Object.entries(errorMap)) {
    if (error.includes(key)) {
      return friendlyMessage
    }
  }

  // 如果是包含 exitCode 的错误，提取关键信息
  if (error.includes('exitCode=2')) {
    if (error.includes('TaggedPDFError')) {
      return '文件格式问题：此文件已经包含文本层，无法进行 OCR 处理'
    }
    if (error.includes('UnsupportedImageFormatError')) {
      return '不支持的文件格式，请确认文件类型'
    }
    return '转换命令执行失败，请检查文件格式是否正确'
  }

  // 默认返回简化后的错误信息（去除 Java 堆栈）
  const lines = error.split('\n')
  const firstLine = lines[0]

  // 如果第一行太长，截断它
  if (firstLine.length > 100) {
    return firstLine.substring(0, 100) + '...'
  }

  return firstLine
}

const viewDetail = async (row) => {
  try {
    const res = await getTaskDetail(row.id)
    currentTask.value = res.data
    detailVisible.value = true
  } catch (error) {
    ElMessage.error('获取任务详情失败')
  }
}

const retryTask = async (row) => {
  try {
    await ElMessageBox.confirm('确定要重试此任务吗？', '提示', {
      type: 'warning'
    })
    const res = await retryTaskApi(row.id)
    ElMessage.success('重试任务已提交')
    loadTasks()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('重试失败')
    }
  }
}

const downloadFile = (row) => {
  window.location.href = `/api/pdf/convert/download/${row.id}`
}

const handleSelectionChange = (selection) => {
  selectedTasks.value = selection

  // 保存选中状态到 localStorage（包括恢复过程中的选中）
  const selectedIds = selection.map(task => task.id)
  localStorage.setItem('selectedTaskIds', JSON.stringify(selectedIds))
}

const handleDelete = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定要删除任务 "${row.sourceFileName}" 吗？删除后将同时删除源文件和转换后的文件，此操作不可恢复！`,
      '删除确认',
      {
        confirmButtonText: '确定删除',
        cancelButtonText: '取消',
        type: 'warning',
        distinguishCancelAndClose: true
      }
    )

    const res = await deleteTask(row.id)
    ElMessage.success(res.msg || '删除成功')

    // 清除localStorage中的选中状态
    localStorage.removeItem('selectedTaskIds')

    loadTasks()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error('删除失败')
    }
  }
}

const handleBatchDelete = async () => {
  if (selectedTasks.value.length === 0) {
    ElMessage.warning('请先选择要删除的任务')
    return
  }

  try {
    await ElMessageBox.confirm(
      `确定要删除选中的 ${selectedTasks.value.length} 个任务吗？删除后将同时删除源文件和转换后的文件，此操作不可恢复！`,
      '批量删除确认',
      {
        confirmButtonText: '确定删除',
        cancelButtonText: '取消',
        type: 'warning',
        distinguishCancelAndClose: true
      }
    )

    const ids = selectedTasks.value.map(task => task.id)
    const res = await deleteTasks(ids)
    ElMessage.success(res.msg || '批量删除成功')

    // 清除localStorage中的选中状态
    localStorage.removeItem('selectedTaskIds')
    selectedTasks.value = []

    loadTasks()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error('批量删除失败')
    }
  }
}

const batchRetryFailed = () => {
  ElMessage.info('批量重试功能开发中...')
}

const batchDownload = async () => {
  if (selectedTasks.value.length === 0) {
    ElMessage.warning('请先选择要下载的任务')
    return
  }

  // 检查是否有未完成的任务
  const hasUnfinished = selectedTasks.value.some(task => task.status !== '2')
  if (hasUnfinished) {
    try {
      await ElMessageBox.confirm(
        '选中的任务包含未完成的任务，只能下载已完成的任务，是否继续？',
        '提示',
        {
          confirmButtonText: '继续',
          cancelButtonText: '取消',
          type: 'warning'
        }
      )
    } catch {
      return
    }
  }

  // 显示loading
  const loading = ElLoading.service({
    lock: true,
    text: '正在打包文件，请稍候...',
    background: 'rgba(0, 0, 0, 0.7)'
  })

  try {
    // 调用批量下载接口，获取blob数据
    const ids = selectedTasks.value.map(task => task.id)
    const response = await batchDownloadFiles(ids)

    // 检查响应类型，判断是否是错误
    const contentType = response.headers?.['content-type'] || ''
    if (contentType.includes('application/json')) {
      // 如果返回的是JSON，说明是错误信息
      const reader = new FileReader()
      reader.onload = () => {
        try {
          const errorMsg = JSON.parse(reader.result)
          ElMessage.error(errorMsg.msg || '批量下载失败')
        } catch {
          ElMessage.error('批量下载失败')
        }
      }
      reader.readAsText(response.data)
      loading.close()
      return
    }

    // 获取blob数据
    const blob = response.data

    // 检查blob类型
    if (blob.type === 'application/json') {
      // 可能是错误信息
      const reader = new FileReader()
      reader.onload = () => {
        try {
          const errorMsg = JSON.parse(reader.result)
          ElMessage.error(errorMsg.msg || '批量下载失败')
        } catch {
          ElMessage.error('批量下载失败')
        }
      }
      reader.readAsText(blob)
      loading.close()
      return
    }

    // 创建下载链接
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url

    // 生成文件名（带时间戳）
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-').substring(0, 19)
    link.setAttribute('download', `pdf_export_${timestamp}.zip`)

    document.body.appendChild(link)
    link.click()

    // 清理
    document.body.removeChild(link)
    window.URL.revokeObjectURL(url)

    loading.close()
    ElMessage.success('批量下载成功！')
  } catch (error) {
    loading.close()
    console.error('批量下载失败:', error)
    ElMessage.error('批量下载失败：' + (error.message || '未知错误'))
  }
}
</script>

<style scoped>
.tasks-page {
  padding: 20px;
  max-width: 1400px;
  margin: 0 auto;
  height: calc(100vh - 40px); /* 减少一些高度以适应浏览器UI */
  overflow: auto; /* 改为auto而不是hidden */
  display: flex;
  flex-direction: column;
}

.content-card {
  box-shadow: 0 2px 12px 0 rgba(0,0,0,0.1);
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-radius: 8px;
}

.content-card :deep(.el-card__body) {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 20px;
}

/* 表格样式 */
.content-card :deep(.el-table) {
  flex: 1;
  min-height: 300px; /* 设置最小高度 */
  overflow: auto;
  margin-bottom: 0;
}

/* 分页组件样式 */
.pagination {
  margin-top: 20px;
  text-align: center;
  padding: 20px 0 0;
  border-top: 1px solid #f0f0f0;
  background: white;
  position: relative; /* 改为相对定位 */
  z-index: 10;
  flex-shrink: 0; /* 防止分页组件被压缩 */
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  flex-shrink: 0; /* 防止头部被压缩 */
}

.card-header h2 {
  margin: 0 0 10px 0;
  color: #333;
  display: flex;
  align-items: center;
  gap: 10px;
}

.subtitle {
  margin: 0;
  color: #999;
}

.search-bar {
  display: flex;
  gap: 10px;
  margin-bottom: 20px;
  padding: 20px;
  background: #f8f9fa;
  border-radius: 6px;
  flex-shrink: 0;
}

.task-detail p {
  line-height: 2;
  margin: 10px 0;
}

.error-message {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #f56c6c;
  font-weight: 500;
}

.error-icon {
  font-size: 18px;
  color: #f56c6c;
}

.progress-section {
  margin-top: 20px;
  padding-top: 20px;
  border-top: 1px solid #e0e0e0;
}
</style>
