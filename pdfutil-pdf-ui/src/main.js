import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

const app = createApp(App)

// 注册所有图标
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

// 应用挂载后移除加载遮罩
app.mount('#app')

// 等待DOM更新后移除加载动画
setTimeout(() => {
  const loadingEl = document.getElementById('app-loading')
  if (loadingEl) {
    loadingEl.classList.add('loaded')
    // 动画结束后移除元素
    setTimeout(() => {
      loadingEl.remove()
    }, 300)
  }
}, 100)
