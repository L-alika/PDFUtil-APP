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
    redirect: '/pdf/files'
  },
  {
    path: '/pdf',
    component: () => import('@/views/pdf/Layout.vue'),
    redirect: '/pdf/files',
    children: [
      {
        path: 'files',
        name: 'Files',
        component: () => import('@/views/pdf/FileManager.vue'),
        meta: { title: '文档管理' }
      },
      {
        path: 'search',
        name: 'Search',
        component: () => import('@/views/pdf/Search.vue'),
        meta: { title: '全文检索' }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/pdf/files'
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
  document.title = (to.meta.title ? `${to.meta.title} - ` : '') + 'PDFUtil 文档管理与全文检索系统'

  next()
})

router.afterEach(() => {
  // 结束进度条
  NProgress.done()
})

export default router
