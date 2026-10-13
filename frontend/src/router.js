import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', component: () => import('./views/Home.vue') },
  { path: '/login', component: () => import('./views/Login.vue') },
  { path: '/messages', component: () => import('./views/Messages.vue') },
  { path: '/publish', component: () => import('./views/Publish.vue') },
  { path: '/wanted', component: () => import('./views/Wanted.vue') },
  { path: '/orders', component: () => import('./views/Orders.vue') },
  { path: '/admin', component: () => import('./views/Admin.vue') },
  { path: '/user', component: () => import('./views/User.vue') }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
