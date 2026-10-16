import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', component: () => import('./views/Home.vue') },
  { path: '/login', component: () => import('./views/Login.vue') },
  { path: '/messages', component: () => import('./views/Messages.vue') },
  { path: '/publish', component: () => import('./views/Publish.vue') },
  { path: '/wanted', component: () => import('./views/Wanted.vue') },
  { path: '/wanted/square', component: () => import('./views/WantedSquare.vue') },
  { path: '/orders', component: () => import('./views/Orders.vue') },
  { path: '/user', component: () => import('./views/User.vue') },

  // 管理端：外壳 + 子路由。每个模块都能被直接链接（/admin/goods、/admin/kb），
  // 运营可以把某一屏直接甩给同事，答辩时也能精准跳转。
  // 权限守卫放在 AdminLayout 里，只控制「不渲染」，不是安全边界。
  {
    path: '/admin',
    component: () => import('./views/admin/AdminLayout.vue'),
    children: [
      { path: '', redirect: '/admin/overview' },
      { path: 'overview', component: () => import('./views/admin/Overview.vue') },
      { path: 'goods', component: () => import('./views/admin/GoodsManage.vue') },
      { path: 'orders', component: () => import('./views/admin/OrdersManage.vue') },
      { path: 'users', component: () => import('./views/admin/UsersManage.vue') },
      { path: 'disputes', component: () => import('./views/admin/Disputes.vue') },
      { path: 'kb', component: () => import('./views/admin/KbConsole.vue') }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
