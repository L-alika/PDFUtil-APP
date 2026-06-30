<template>
  <div class="dashboard-page">
    <el-card class="content-card">
      <template #header>
        <div class="card-header">
          <h2><el-icon><DataAnalysis /></el-icon> 系统概览</h2>
          <p class="subtitle">实时监控档案转换引擎与存储运行状态</p>
        </div>
      </template>

      <!-- 统计卡片 -->
      <el-row :gutter="20" class="stats-row">
        <el-col :span="12">
          <div class="stat-card stat-card-primary">
            <el-icon class="stat-icon"><Files /></el-icon>
            <div class="stat-content">
              <p>转换文件总数</p>
              <h3>{{ overview.totalCount }}</h3>
              <p class="stat-change">+12.5%</p>
            </div>
          </div>
        </el-col>
        <el-col :span="12">
          <div class="stat-card stat-card-success">
            <el-icon class="stat-icon"><SuccessFilled /></el-icon>
            <div class="stat-content">
              <p>转换成功率</p>
              <h3>{{ overview.successRate }}%</h3>
              <p class="stat-change">稳定运行</p>
            </div>
          </div>
        </el-col>
      </el-row>

      <!-- 主内容区 -->
      <el-row :gutter="20" style="margin-top: 20px">
        <el-col :span="16">
          <!-- 当前活动任务 -->
          <el-card shadow="never" class="sub-card">
            <template #header>
              <h4><el-icon><Loading /></el-icon> 当前活动任务</h4>
            </template>
            <div class="active-tasks">
              <h1 class="task-number">{{ overview.activeTasks }}</h1>
              <p class="task-label">运行中</p>
            </div>
          </el-card>

          <!-- 系统资源监控 -->
          <el-card shadow="never" class="sub-card" style="margin-top: 20px">
            <template #header>
              <h4><el-icon><TrendCharts /></el-icon> 系统资源监控</h4>
            </template>

            <div class="resource-item">
              <div class="resource-header">
                <span>转换引擎CPU使用率</span>
                <span class="resource-value">{{ overview.cpuUsage }}%</span>
              </div>
              <el-progress :percentage="parseFloat(overview.cpuUsage)" :color="cpuColor" />
              <p class="resource-tip">双核心处理运行正常</p>
            </div>

            <div class="resource-item">
              <div class="resource-header">
                <span>系统内存(RAM)</span>
                <span class="resource-value">{{ overview.memoryUsage }}%</span>
              </div>
              <el-progress :percentage="parseFloat(overview.memoryUsage)" :color="memoryColor" />
              <p class="resource-tip" :class="memoryTipClass">{{ memoryTip }}</p>
            </div>

            <div class="resource-item">
              <div class="resource-header">
                <span>磁盘空间</span>
                <span class="resource-value" style="font-size: 12px;">{{ overview.diskSpace }}</span>
              </div>
              <p class="resource-tip">系统所有磁盘分区的总使用情况</p>
            </div>
          </el-card>
        </el-col>

        <el-col :span="8">
          <!-- 最近转换任务 -->
          <el-card shadow="never" class="sub-card">
            <template #header>
              <div style="display: flex; justify-content: space-between; align-items: center;">
                <h4><el-icon><Clock /></el-icon> 最近转换任务</h4>
                <el-link type="primary" @click="$router.push('/pdf/tasks')">查看全部</el-link>
              </div>
            </template>

            <div v-loading="loading" class="recent-tasks">
              <div v-if="recentTasks.length === 0" class="text-muted text-center">
                暂无任务记录
              </div>
              <div v-for="task in recentTasks" :key="task.id" class="recent-task-item">
                <div class="task-header">
                  <span class="task-name">{{ task.sourceFileName }}</span>
                  <el-tag :type="getStatusType(task.status)" size="small">
                    {{ getStatusText(task.status) }}
                  </el-tag>
                </div>
                <div class="task-meta">
                  <el-icon><Document /></el-icon> {{ task.convertType }} |
                  <el-icon><Clock /></el-icon> {{ task.createTime }}
                </div>
                <el-progress
                  v-if="task.status === '1'"
                  :percentage="task.progress || 0"
                  :stroke-width="6"
                  style="margin-top: 5px"
                />
              </div>
            </div>
          </el-card>
        </el-col>
      </el-row>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getDashboardOverview, getRecentTasks } from '@/api/pdf'

const loading = ref(false)
const overview = ref({
  totalCount: 0,
  successCount: 0,
  failedCount: 0,
  activeTasks: 0,
  successRate: 0,
  cpuUsage: 0,
  memoryUsage: 0,
  diskSpace: '加载中...'
})

const recentTasks = ref([])

onMounted(() => {
  loadDashboardData()
  // 每10秒刷新仪表盘数据
  setInterval(loadDashboardData, 10000)
})

const loadDashboardData = async () => {
  try {
    const [overviewRes, tasksRes] = await Promise.all([
      getDashboardOverview(),
      getRecentTasks({ pageNum: 1, pageSize: 10 })
    ])

    if (overviewRes.code === 0) {
      overview.value = overviewRes.data
    }

    if (tasksRes.code === 0) {
      recentTasks.value = tasksRes.rows || []
    }
  } catch (error) {
    ElMessage.error('加载仪表盘数据失败')
  }
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
    '0': '等待中',
    '1': '进行中',
    '2': '成功',
    '3': '失败'
  }
  return texts[status] || '未知'
}

const cpuColor = computed(() => {
  const usage = parseFloat(overview.value.cpuUsage)
  if (usage > 80) return '#f56c6c'
  if (usage > 60) return '#e6a23c'
  return '#67c23a'
})

const memoryColor = computed(() => {
  const usage = parseFloat(overview.value.memoryUsage)
  if (usage > 80) return '#f56c6c'
  if (usage > 60) return '#e6a23c'
  return '#67c23a'
})

const memoryTip = computed(() => {
  const usage = parseFloat(overview.value.memoryUsage)
  if (usage > 80) return '内存使用率较高，建议加大物理内存或清理缓存'
  if (usage > 60) return '内存使用正常，注意监控'
  return '内存使用率正常'
})

const memoryTipClass = computed(() => {
  const usage = parseFloat(overview.value.memoryUsage)
  if (usage > 80) return 'text-danger'
  if (usage > 60) return 'text-warning'
  return 'text-success'
})
</script>

<style scoped>
.dashboard-page {
  padding: 20px;
  max-width: 1400px;
  margin: 0 auto;
  height: calc(100vh - 110px); /* 减去header(60px)和footer(50px)的高度 */
  overflow-y: auto; /* 允许内容滚动 */
}

/* 隐藏滚动条但保留滚动功能 */
.dashboard-page::-webkit-scrollbar {
  width: 0px;
  background: transparent;
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

.stats-row {
  margin-bottom: 20px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 25px;
  border-radius: 8px;
  color: white;
  box-shadow: 0 4px 6px rgba(0,0,0,0.1);
}

.stat-card-primary {
  background: linear-gradient(135deg, #1e40af 0%, #1d4ed8 100%);
}

.stat-card-success {
  background: linear-gradient(135deg, #22c55e 0%, #10b981 100%);
}

.stat-icon {
  font-size: 48px;
  opacity: 0.8;
}

.stat-content p {
  margin: 0 0 10px 0;
  opacity: 0.9;
}

.stat-content h3 {
  margin: 10px 0;
  font-size: 36px;
  font-weight: bold;
}

.stat-change {
  margin: 0;
  font-size: 12px;
  opacity: 0.8;
}

.sub-card {
  box-shadow: 0 2px 8px rgba(0,0,0,0.05);
}

.sub-card h4 {
  margin: 0;
  color: #333;
  display: flex;
  align-items: center;
  gap: 8px;
}

.active-tasks {
  text-align: center;
}

.task-number {
  font-size: 48px;
  color: #1e40af;
  margin: 0;
}

.task-label {
  color: #999;
  margin: 5px 0 0 0;
}

.resource-item {
  margin-bottom: 20px;
}

.resource-header {
  display: flex;
  justify-content: space-between;
  margin-bottom: 8px;
}

.resource-value {
  font-weight: bold;
}

.resource-tip {
  margin: 5px 0 0 0;
  font-size: 12px;
  color: #999;
}

.text-danger {
  color: #f56c6c;
}

.text-warning {
  color: #e6a23c;
}

.text-success {
  color: #67c23a;
}

.recent-tasks {
  margin-top: 20px;
  max-height: 400px; /* 限制最大高度 */
  overflow-y: auto; /* 超出时滚动 */
}

/* 隐藏最近任务区域的滚动条 */
.recent-tasks::-webkit-scrollbar {
  width: 4px;
}

.recent-tasks::-webkit-scrollbar-thumb {
  background: #dcdfe6;
  border-radius: 2px;
}

.recent-tasks::-webkit-scrollbar-thumb:hover {
  background: #c0c4cc;
}

.recent-task-item {
  padding: 15px 0;
  border-bottom: 1px solid #f0f0f0;
}

.recent-task-item:last-child {
  border-bottom: none;
}

.task-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.task-name {
  font-weight: bold;
  color: #333;
}

.task-meta {
  display: flex;
  gap: 15px;
  color: #999;
  font-size: 12px;
  align-items: center;
}

.task-meta .el-icon {
  margin-right: 4px;
}

.text-center {
  text-align: center;
  padding: 40px 0;
}

.text-muted {
  color: #999;
}
</style>
