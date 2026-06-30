<template>
  <div class="pdf-layout">
    <!-- 顶部导航栏 -->
    <el-header class="header">
      <div class="header-content">
        <div class="logo">
          <el-icon><Document /></el-icon>
          <span>电子档案管理格式转换工具</span>
        </div>
        <el-menu
          :default-active="activeMenu"
          mode="horizontal"
          :ellipsis="false"
          background-color="transparent"
          text-color="#fff"
          active-text-color="#fff"
          class="nav-menu"
          @select="handleMenuSelect"
        >
          <el-menu-item index="/pdf/dashboard">
            <el-icon><DataAnalysis /></el-icon>
            <span>系统概览</span>
          </el-menu-item>
          <el-menu-item index="/pdf/home">
            <el-icon><Plus /></el-icon>
            <span>新建转换任务</span>
          </el-menu-item>
          <el-menu-item index="/pdf/tasks">
            <el-icon><List /></el-icon>
            <span>任务管理</span>
          </el-menu-item>
        </el-menu>
      </div>
    </el-header>

    <!-- 主内容区 -->
    <el-main class="main-content">
      <router-view />
    </el-main>

    <!-- 页脚 - 暂时隐藏 -->
    <!-- <el-footer class="footer">
      <p>© 2025 电子档案管理系统 - 高精度OCR双层PDF转换技术</p>
    </el-footer> -->
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

const activeMenu = computed(() => route.path)

// 手动处理菜单选择
const handleMenuSelect = (index) => {
  router.push(index)
}
</script>

<style scoped>
.pdf-layout {
  width: 100%;
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f5f7fa;
}

.header {
  background: linear-gradient(135deg, #1e40af 0%, #1d4ed8 100%);
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.1);
  padding: 0;
  height: 60px !important;
}

.header-content {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 100%;
  padding: 0 30px;
}

.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  color: white;
  font-size: 20px;
  font-weight: bold;
}

.logo .el-icon {
  font-size: 28px;
}

.nav-menu {
  border: none;
  background: transparent;
  flex: 1;
  justify-content: flex-end;
}

.nav-menu .el-menu-item {
  height: 60px;
  line-height: 60px;
  color: rgba(255, 255, 255, 0.8);
  border-bottom: none !important;
}

.nav-menu .el-menu-item:hover {
  background: rgba(255, 255, 255, 0.1);
  color: white;
}

.nav-menu .el-menu-item.is-active {
  background: rgba(255, 255, 255, 0.2);
  color: white;
  font-weight: bold;
}

.main-content {
  flex: 1;
  overflow: hidden; /* 防止出现滚动条 */
  padding: 0;
}

.footer {
  text-align: center;
  color: #999;
  height: 50px !important;
  line-height: 50px;
  background: white;
  border-top: 1px solid #e0e0e0;
}

.footer p {
  margin: 0;
}
</style>
