import { createRouter, createWebHistory } from 'vue-router'
import NProgress from 'nprogress'
import 'nprogress/nprogress.css'

// 配置NProgress
NProgress.configure({
  showSpinner: false,
  trickleSpeed: 200,
  minimum: 0.3,
  easing: 'ease',
  speed: 500
})

const routes = [
  {
    path: '/',
    redirect: '/pdf/dashboard'
  },
  {
    path: '/pdf',
    component: () => import('@/views/pdf/Layout.vue'),
    children: [
      {
        path: 'home',
        name: 'Home',
        component: () => import('@/views/pdf/Home.vue'),
        meta: { title: '新建转换任务' }
      },
      {
        path: 'tasks',
        name: 'Tasks',
        component: () => import('@/views/pdf/Tasks.vue'),
        meta: { title: '任务管理' }
      },
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/pdf/Dashboard.vue'),
        meta: { title: '系统概览' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 路由守卫
router.beforeEach((to, from, next) => {
  // 开始进度条
  NProgress.start()

  // 固定页面标题
  document.title = '电子档案管理格式转换工具'

  next()
})

router.afterEach(() => {
  // 结束进度条
  NProgress.done()
})

export default router
