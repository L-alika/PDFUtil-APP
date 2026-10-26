<template>
  <div id="app" class="h-screen w-screen overflow-hidden bg-[#F5F3EF]">
    <router-view v-slot="{ Component }">
      <transition name="fade-slide" mode="out-in">
        <component :is="Component" />
      </transition>
    </router-view>
  </div>
</template>

<script setup>
import { onMounted } from 'vue'

onMounted(() => {
  console.log('PDFUtil 智能文档与全文检索系统已就绪')
})
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

html, body {
  width: 100%;
  height: 100%;
  margin: 0;
  padding: 0;
  background-color: #F5F3EF;
  color: #1C1917;
  font-family: "Inter", "Noto Sans SC", -apple-system, BlinkMacSystemFont, sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  font-feature-settings: "cv02", "cv03", "cv04", "cv11";
}

#app {
  width: 100%;
  height: 100%;
}

/* 优雅平滑页面过渡 */
.fade-slide-enter-active,
.fade-slide-leave-active {
  transition: opacity 0.15s cubic-bezier(0.16, 1, 0.3, 1), transform 0.15s cubic-bezier(0.16, 1, 0.3, 1);
}

.fade-slide-enter-from {
  opacity: 0;
  transform: translateY(3px);
}

.fade-slide-leave-to {
  opacity: 0;
  transform: translateY(-3px);
}

/* 微型圆润滚动条 */
::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}
::-webkit-scrollbar-track {
  background: transparent;
}
::-webkit-scrollbar-thumb {
  background: rgba(120, 113, 108, 0.22);
  border-radius: 9999px;
}
::-webkit-scrollbar-thumb:hover {
  background: rgba(120, 113, 108, 0.4);
}

.transition-craft {
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}

.ocr-bbox {
  transition: all 0.18s ease;
}
.ocr-bbox:hover {
  background-color: rgba(59, 91, 219, 0.12);
  outline: 1.5px solid rgba(59, 91, 219, 0.55);
  border-radius: 6px;
}
.ocr-highlight-pulse {
  animation: softPulse 2s infinite ease-in-out;
}
@keyframes softPulse {
  0%, 100% {
    box-shadow: 0 0 0 0 rgba(225, 29, 72, 0.25);
  }
  50% {
    box-shadow: 0 0 0 5px rgba(225, 29, 72, 0.08);
  }
}
mark.ocr-mark {
  background: #FFE4E6;
  color: #BE123C;
  padding: 1px 5px;
  border-radius: 4px;
  font-weight: 600;
  box-shadow: inset 0 0 0 1px rgba(225, 29, 72, 0.2);
}

/* Element Plus 质感微调 */
.el-dialog {
  border-radius: 16px !important;
  overflow: hidden;
  box-shadow: 0 12px 32px -4px rgba(28, 25, 23, 0.12), 0 2px 6px rgba(28, 25, 23, 0.06), 0 0 0 1px rgba(28, 25, 23, 0.08) !important;
}

.el-dialog__header {
  margin-right: 0 !important;
  padding: 20px 24px 14px 24px !important;
  border-bottom: 1px solid #E6E2DA;
}

.el-dialog__title {
  font-size: 16px !important;
  font-weight: 700 !important;
  color: #1C1917 !important;
}

.el-dialog__body {
  padding: 24px !important;
}

#nprogress .bar {
  background: #3B5BDB !important;
  height: 2.5px !important;
}

/* 模态弹窗平滑浮现动画 */
.modal-fade-enter-active,
.modal-fade-leave-active {
  transition: opacity 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}
.modal-fade-enter-from,
.modal-fade-leave-to {
  opacity: 0;
}
.modal-fade-enter-active .modal-card,
.modal-fade-leave-active .modal-card {
  transition: transform 0.2s cubic-bezier(0.16, 1, 0.3, 1), opacity 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}
.modal-fade-enter-from .modal-card {
  transform: scale(0.96) translateY(6px);
  opacity: 0;
}
.modal-fade-leave-to .modal-card {
  transform: scale(0.97) translateY(4px);
  opacity: 0;
}

/* Toast 右下角滑入淡出动画 */
.toast-slide-enter-active,
.toast-slide-leave-active {
  transition: all 0.25s cubic-bezier(0.16, 1, 0.3, 1);
}
.toast-slide-enter-from {
  opacity: 0;
  transform: translateY(12px) scale(0.96);
}
.toast-slide-leave-to {
  opacity: 0;
  transform: translateY(6px) scale(0.98);
}
</style>
