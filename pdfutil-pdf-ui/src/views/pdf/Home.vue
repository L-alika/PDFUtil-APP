<template>
  <div class="home-page">
    <el-card class="content-card">
      <template #header>
        <div class="card-header">
          <h2><el-icon><Plus /></el-icon> 新建转换任务</h2>
          <p class="subtitle">精简统一版：支持单文件与批量文件的高效转换</p>
        </div>
      </template>

      <!-- 转换模式切换 -->
      <el-tabs v-model="activeTab" class="convert-tabs">
        <!-- 单文件转换 -->
        <el-tab-pane label="单个文件转换" name="single">
          <div
            v-if="!singleFile"
            class="upload-area"
            :class="{ 'drag-over': isDragging }"
            @click="selectFile"
            @dragover.prevent="handleDragOver"
            @dragleave.prevent="handleDragLeave"
            @drop.prevent="handleSingleDrop"
          >
            <el-icon class="upload-icon"><UploadFilled /></el-icon>
            <h3>点击或拖拽文件至此上传</h3>
            <p class="text-muted">支持PDF、图片（JPG/JPEG、TIF、TIFF、BMP、PNG）、Office文档（DOC、DOCX、XLS、XLSX、PPT、PPTX）、OFD格式（注：仅支持RGB色彩空间的JPEG，CMYK格式不支持）</p>
            <el-button type="primary" round>选择本地文件</el-button>
          </div>

          <div v-else class="file-info">
            <div class="file-item">
              <el-icon class="file-icon" :style="{ color: getFileIconColor(singleFile.name) }">
                <Document v-if="isPdfOrDoc(singleFile.name)" />
                <Picture v-else-if="isImage(singleFile.name)" />
                <Tickets v-else-if="isExcel(singleFile.name)" />
                <NoteBook v-else-if="isPowerPoint(singleFile.name)" />
                <Document v-else />
              </el-icon>
              <div class="file-details">
                <h4>{{ singleFile.name }}</h4>
                <p class="text-muted">{{ formatFileSize(singleFile.size) }}</p>
              </div>
              <el-button type="danger" @click="clearSingleFile">移除</el-button>
            </div>
          </div>

          <!-- 配置项 -->
          <div v-if="singleFile" class="config-section">
            <el-form label-width="120px">
              <el-form-item label="操作人">
                <el-input
                  v-model="operatorName"
                  placeholder="请输入操作人名称"
                  style="width: 300px"
                  clearable
                />
              </el-form-item>
              <el-form-item label="OCR语言">
                <el-select v-model="ocrLanguage" style="width: 300px">
                  <el-option label="中英混合(自动检测)" value="chi_sim+chi_tra+eng" />
                  <el-option label="简体中文" value="chi_sim" />
                  <el-option label="繁体中文" value="chi_tra" />
                  <el-option label="英文" value="eng" />
                </el-select>
              </el-form-item>
            </el-form>

            <div class="action-buttons">
              <el-button @click="resetForm">取消</el-button>
              <el-button type="primary" :loading="converting" @click="startConvert">
                <el-icon><Setting /></el-icon> 开始转换
              </el-button>
            </div>
          </div>
        </el-tab-pane>

        <!-- 批量转换 -->
        <el-tab-pane label="批量文件转换" name="batch">
          <div
            v-if="batchFiles.length === 0"
            class="upload-area"
            :class="{ 'drag-over': isBatchDragging }"
            @click="selectFiles"
            @dragover.prevent="handleBatchDragOver"
            @dragleave.prevent="handleBatchDragLeave"
            @drop.prevent="handleBatchDrop"
          >
            <el-icon class="upload-icon"><UploadFilled /></el-icon>
            <h3>点击或拖拽多个文件至此上传</h3>
            <p class="text-muted">支持PDF、图片（JPG/JPEG、TIF、TIFF、BMP、PNG）、Office文档（DOC、DOCX）、OFD格式，可多选（注：仅支持RGB色彩空间的JPEG，CMYK格式不支持）</p>
            <el-button type="primary" round>选择本地文件</el-button>
          </div>

          <div v-else>
            <h4>已选择 {{ batchFiles.length }} 个文件</h4>
            <div class="file-list">
              <div v-for="(file, index) in batchFiles" :key="index" class="file-item">
                <el-icon class="file-icon" :style="{ color: getFileIconColor(file.name) }">
                  <Document v-if="isPdfOrDoc(file.name)" />
                  <Picture v-else-if="isImage(file.name)" />
                  <Tickets v-else-if="isExcel(file.name)" />
                  <Memo v-else-if="isPowerPoint(file.name)" />
                  <Document v-else />
                </el-icon>
                <div class="file-details">
                  <h4>{{ file.name }}</h4>
                  <p class="text-muted">{{ formatFileSize(file.size) }}</p>
                </div>
                <el-button type="danger" @click="removeBatchFile(index)">移除</el-button>
              </div>
            </div>

            <div class="config-section">
              <el-form label-width="120px">
                <el-form-item label="操作人">
                  <el-input
                    v-model="batchOperatorName"
                    placeholder="请输入操作人名称"
                    style="width: 300px"
                    clearable
                  />
                </el-form-item>
                <el-form-item label="OCR语言">
                  <el-select v-model="batchOcrLanguage" style="width: 300px">
                    <el-option label="中英混合(自动检测)" value="chi_sim+chi_tra+eng" />
                    <el-option label="简体中文" value="chi_sim" />
                    <el-option label="繁体中文" value="chi_tra" />
                    <el-option label="英文" value="eng" />
                  </el-select>
                </el-form-item>
              </el-form>

              <div class="action-buttons">
                <el-button @click="clearBatchFiles">清空</el-button>
                <el-button type="primary" :loading="converting" @click="startBatchConvert">
                  <el-icon><Setting /></el-icon> 开始批量转换
                </el-button>
              </div>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 隐藏的文件输入 -->
    <input
      ref="singleInput"
      type="file"
      style="display: none"
      accept=".pdf,.jpg,.jpeg,.tif,.tiff,.bmp,.png,.ofd"
      @change="handleSingleSelect"
    />
    <input
      ref="batchInput"
      type="file"
      multiple
      style="display: none"
      accept=".pdf,.jpg,.jpeg,.tif,.tiff,.bmp,.png,.ofd"
      @change="handleBatchSelect"
    />
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Document,
  Picture,
  Tickets,
  Memo
} from '@element-plus/icons-vue'
import { convertSingle, convertBatch } from '@/api/pdf'

const router = useRouter()
const activeTab = ref('single')
const singleFile = ref(null)
const batchFiles = ref([])
const ocrLanguage = ref('chi_sim+chi_tra+eng')

// 监听标签切换，自动过滤已选文件，仅保留支持 OFD 的类型
watch(activeTab, (newTab) => {
  if (newTab === 'single') {
    if (singleFile.value) {
      const filtered = filterSupportedFiles([singleFile.value])
      singleFile.value = filtered.length ? filtered[0] : null
    }
  } else if (newTab === 'batch') {
    batchFiles.value = filterSupportedFiles(batchFiles.value)
  }
})
const batchOcrLanguage = ref('chi_sim+chi_tra+eng')

// 从 localStorage 读取操作人名称
const savedOperator = localStorage.getItem('pdf_operator_name') || ''
const operatorName = ref(savedOperator)
const batchOperatorName = ref(savedOperator)

// 监听操作人名称变化，自动保存到 localStorage
watch(operatorName, (newVal) => {
  localStorage.setItem('pdf_operator_name', newVal)
})

watch(batchOperatorName, (newVal) => {
  localStorage.setItem('pdf_operator_name', newVal)
})

const converting = ref(false)

// 拖拽状态
const isDragging = ref(false)
const isBatchDragging = ref(false)

const singleInput = ref(null)
const batchInput = ref(null)

// 判断是否为PDF或Word文档
const isPdfOrDoc = (fileName) => {
  const ext = fileName.split('.').pop().toLowerCase()
  return ['pdf', 'doc', 'docx'].includes(ext)
}

// 判断是否为图片
const isImage = (fileName) => {
  const ext = fileName.split('.').pop().toLowerCase()
  return ['jpg', 'jpeg', 'tif', 'tiff'].includes(ext)
}

// 判断是否为Excel
const isExcel = (fileName) => {
  const ext = fileName.split('.').pop().toLowerCase()
  return ['xls', 'xlsx'].includes(ext)
}

// 判断是否为PowerPoint
const isPowerPoint = (fileName) => {
  const ext = fileName.split('.').pop().toLowerCase()
  return ['ppt', 'pptx'].includes(ext)
}

// 支持转换为 OFD 的文件后缀
const SUPPORTED_OFD_FORMATS = [
  'pdf',
  // 图片
  'jpg', 'jpeg', 'tif', 'tiff', 'bmp', 'png',
  // OFD 文件（如果需要直接上传 OFD）
  'ofd'
]

/** 过滤仅保留支持 OFD 的文件 */
const filterSupportedFiles = (files) => {
  return files.filter(f => {
    const ext = f.name.split('.').pop().toLowerCase()
    return SUPPORTED_OFD_FORMATS.includes(ext)
  })
}

// 根据文件类型获取图标颜色
const getFileIconColor = (fileName) => {
  const ext = fileName.split('.').pop().toLowerCase()
  const colorMap = {
    'pdf': '#dc2626',      // 红色
    'jpg': '#2563eb',      // 蓝色
    'jpeg': '#2563eb',
    'tif': '#2563eb',
    'tiff': '#2563eb',
    'doc': '#1e40af',      // 深蓝色
    'docx': '#1e40af',
    'xls': '#16a34a',      // 绿色
    'xlsx': '#16a34a',
    'ppt': '#ea580c',      // 橙色
    'pptx': '#ea580c'
  }
  return colorMap[ext] || '#6b7280'
}

const selectFile = () => {
  singleInput.value.click()
}

const selectFiles = () => {
  batchInput.value.click()
}

const handleSingleSelect = (event) => {
  const file = event.target.files[0]
  if (file) {
    const filtered = filterSupportedFiles([file])
    singleFile.value = filtered.length ? filtered[0] : null
    if (!filtered.length) {
      ElMessage.warning('不支持的文件格式，已被过滤')
    }
  }
}

const handleBatchSelect = (event) => {
  const files = Array.from(event.target.files)
  const merged = [...batchFiles.value, ...files]
  batchFiles.value = filterSupportedFiles(merged)
  if (merged.length !== batchFiles.value.length) {
    ElMessage.warning('已过滤不支持的文件格式')
  }
}

const clearSingleFile = () => {
  singleFile.value = null
  singleInput.value.value = ''
}

const removeBatchFile = (index) => {
  batchFiles.value.splice(index, 1)
}

const clearBatchFiles = () => {
  batchFiles.value = []
  batchInput.value.value = ''
}

const resetForm = () => {
  singleFile.value = null
  batchFiles.value = []
}

// 单文件拖拽处理
const handleDragOver = () => {
  isDragging.value = true
}

const handleDragLeave = () => {
  isDragging.value = false
}

const handleSingleDrop = (event) => {
  isDragging.value = false
  const files = event.dataTransfer.files
  if (files.length > 0) {
    const file = files[0]
    const filtered = filterSupportedFiles([file])
    if (filtered.length) {
      singleFile.value = filtered[0]
    } else {
      ElMessage.warning('不支持的文件格式，请上传PDF、图片或Office文档')
    }
  }
}

// 批量文件拖拽处理
const handleBatchDragOver = () => {
  isBatchDragging.value = true
}

const handleBatchDragLeave = () => {
  isBatchDragging.value = false
}

const handleBatchDrop = (event) => {
  isBatchDragging.value = false
  const files = Array.from(event.dataTransfer.files)
  const filtered = filterSupportedFiles(files)

  if (filtered.length === 0) {
    ElMessage.warning('没有支持的文件格式')
    return
  }

  if (filtered.length < files.length) {
    ElMessage.warning(`已过滤 ${files.length - filtered.length} 个不支持的文件`)
  }

  batchFiles.value = [...batchFiles.value, ...filtered]
}

const formatFileSize = (bytes) => {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return (bytes / Math.pow(k, i)).toFixed(2) + ' ' + sizes[i]
}

const startConvert = async () => {
  if (!singleFile.value) {
    ElMessage.warning('请先选择文件')
    return
  }

  converting.value = true
  const controller = new AbortController()
  convertController.value = controller
  try {
    const formData = new FormData()
    formData.append('file', singleFile.value)
    formData.append('ocrLanguage', ocrLanguage.value)
    const operator = operatorName.value.trim() || 'anonymous'
    formData.append('operator', operator)
    // 通过 signal 传递取消控制
    const res = await convertSingle(formData, { signal: controller.signal })
    ElMessage.success('转换任务已提交，正在后台处理')
    clearSingleFile()
  } catch (error) {
    if (error.name === 'AbortError') {
      // 用户手动取消
      ElMessage.info('转换已被用户取消')
    } else {
      console.error('转换出错', error)
      ElMessage.error('转换失败，请检查网络或文件格式')
    }
  } finally {
    converting.value = false
    convertController.value = null
    // 统一跳转到任务列表，用户可自行查看状态
    router.push('/pdf/tasks')
  }
}

const startBatchConvert = async () => {
  if (batchFiles.value.length === 0) {
    ElMessage.warning('请先选择文件')
    return
  }

  converting.value = true
  try {
    const formData = new FormData()
    batchFiles.value.forEach(file => {
      formData.append('files', file)
    })
    formData.append('ocrLanguage', batchOcrLanguage.value)

    // 添加操作人信息
    const operator = batchOperatorName.value.trim() || 'anonymous'
    formData.append('operator', operator)

    const res = await convertBatch(formData)

    ElMessage.success(`批量转换任务已提交，${batchFiles.value.length}个文件正在转换中`)
    clearBatchFiles()
  } catch (error) {
    // 【修复】即使请求失败，也跳转到任务列表（后台可能已经开始处理）
    ElMessage.warning('请求异常，请检查任务列表确认状态')
  } finally {
    converting.value = false
    // 【修复】无论成功失败，都跳转到任务列表
    router.push('/pdf/tasks')
  }
}
</script>

<style scoped>
.home-page {
  padding: 20px;
  max-width: 1200px;
  margin: 0 auto;
}

.content-card {
  box-shadow: 0 2px 12px 0 rgba(0,0,0,0.1);
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

.upload-area {
  border: 2px dashed #1e40af;
  border-radius: 8px;
  padding: 60px;
  text-align: center;
  background: #f8f9fa;
  cursor: pointer;
  transition: all 0.3s;
  margin: 20px 0;
}

.upload-area:hover {
  border-color: #1d4ed8;
  background: #f0f2f5;
}

.upload-area.drag-over {
  border-color: #1e40af;
  background: #e8ebf0;
  transform: scale(1.02);
  box-shadow: 0 4px 12px rgba(30, 64, 175, 0.3);
}

.upload-icon {
  font-size: 48px;
  color: #1e40af;
  margin-bottom: 20px;
}

.text-muted {
  color: #999;
  margin: 10px 0;
}

.file-info {
  margin: 20px 0;
}

.file-list {
  margin: 20px 0;
  max-height: 300px;
  overflow-y: auto;
  padding-right: 10px;
}

/* 美化滚动条 */
.file-list::-webkit-scrollbar {
  width: 8px;
}

.file-list::-webkit-scrollbar-track {
  background: #f1f1f1;
  border-radius: 4px;
}

.file-list::-webkit-scrollbar-thumb {
  background: #1e40af;
  border-radius: 4px;
}

.file-list::-webkit-scrollbar-thumb:hover {
  background: #1d4ed8;
}

.file-item {
  display: flex;
  align-items: center;
  gap: 15px;
  padding: 15px;
  background: #f8f9fa;
  border-radius: 6px;
  margin-bottom: 10px;
}

.file-icon {
  font-size: 32px;
  color: #1e40af;
}

.file-details {
  flex: 1;
}

.file-details h4 {
  margin: 0 0 5px 0;
}

.config-section {
  margin: 30px 0;
  padding-top: 30px;
  border-top: 1px solid #e0e0e0;
}

.action-buttons {
  margin-top: 30px;
}

.form-tip {
  margin-top: 5px;
  font-size: 12px;
  color: #e6a23c;
  line-height: 1.5;
}

/* 确保批量转换标签页内容有最大高度并可滚动 */
.convert-tabs :deep(.el-tab-pane) {
  max-height: 70vh;
  overflow-y: auto;
  padding-right: 10px;
}

/* 美化标签页滚动条 */
.convert-tabs :deep(.el-tab-pane)::-webkit-scrollbar {
  width: 8px;
}

.convert-tabs :deep(.el-tab-pane)::-webkit-scrollbar-track {
  background: #f1f1f1;
  border-radius: 4px;
}

.convert-tabs :deep(.el-tab-pane)::-webkit-scrollbar-thumb {
  background: #1e40af;
  border-radius: 4px;
}

.convert-tabs :deep(.el-tab-pane)::-webkit-scrollbar-thumb:hover {
  background: #1d4ed8;
}
</style>
