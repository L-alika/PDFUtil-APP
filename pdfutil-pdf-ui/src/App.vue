<template>
  <div id="app">
    <router-view v-slot="{ Component }">
      <transition name="fade" mode="out-in">
        <component :is="Component" />
      </transition>
    </router-view>
  </div>
</template>

<script setup>
import { onMounted } from 'vue'

onMounted(() => {
  console.log('PDF转换系统启动成功')
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
  font-family: "Microsoft YaHei", Arial, sans-serif;
}

#app {
  width: 100%;
  height: 100%;
}

/* 页面切换过渡动画 */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

/* 防止Vue模板闪烁 */
[v-cloak] {
  display: none !important;
}

/* 自定义NProgress进度条样式 - 深蓝色主题 */
#nprogress .bar {
  background: #1e40af !important;
  height: 3px !important;
}

#nprogress .peg {
  box-shadow: 0 0 10px #1e40af, 0 0 5px #1e40af !important;
}

#nprogress .spinner-icon {
  border-top-color: #1e40af !important;
  border-left-color: #1e40af !important;
}
</style>
