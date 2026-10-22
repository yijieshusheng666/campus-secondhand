<script setup>
/**
 * 管理端外壳：左侧导航 + 子路由出口。
 *
 * <p>为什么用嵌套路由而不是单页切换：管理台每个模块都要能被直接链接
 * （/admin/goods、/admin/kb），运营同学可以直接把某个页甩给同事，
 * 也方便答辩时精准跳到某一屏。技能库 UX 指南「Navigation Patterns」把
 * Deep linking 列为必做项。</p>
 *
 * <p>权限守卫只做「前端不渲染」这一层。这个项目没有服务端鉴权
 * （用户存在 localStorage），真正的权限必须在后端补；这里保证普通用户
 * 看不到入口而已，不要把它当成安全边界。</p>
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useCurrentUser } from '../../composables/useCurrentUser'
import { iconUrl } from '../../icons'

const router = useRouter()
const route = useRoute()
const user = useCurrentUser()

const allowed = computed(() => user.value?.role === 'admin')

// 图标复用现有素材，没有专门的图就用语义最接近的（与 icons.js 的「复用图标」约定一致）
const MENU = [
  { path: '/admin/overview', label: '平台概览', icon: 'home' },
  { path: '/admin/goods', label: '商品管理', icon: 'publish' },
  { path: '/admin/orders', label: '订单管理', icon: 'orders' },
  { path: '/admin/users', label: '用户管理', icon: 'profile' },
  { path: '/admin/disputes', label: '纠纷仲裁', icon: 'dispute' },
  { path: '/admin/kb', label: '知识库运营', icon: 'manage' }
]

const current = computed(() => route.path)
const title = computed(() => MENU.find(m => current.value.startsWith(m.path))?.label || '管理端')
</script>

<template>
  <div class="admin-shell">
    <div v-if="!allowed" class="deny">
      <el-empty description="该页面仅平台管理员可见">
        <el-button type="primary" @click="router.push('/')">回首页</el-button>
      </el-empty>
    </div>

    <template v-else>
      <aside class="side">
        <div class="side-brand">
          <img class="side-logo" src="/images/logo.png" alt="校园二手交易平台">
          <div>
            <div class="side-title">平台管理端</div>
            <div class="sub">Campus Admin</div>
          </div>
        </div>
        <nav class="side-nav">
          <RouterLink
            v-for="m in MENU" :key="m.path" :to="m.path" class="side-item"
            :class="{ active: current.startsWith(m.path) }"
          >
            <img v-if="iconUrl(m.icon)" class="side-ico" :src="iconUrl(m.icon)" alt="">
            <span>{{ m.label }}</span>
          </RouterLink>
        </nav>
      </aside>

      <main class="content">
        <header class="topbar">
          <h1 class="topbar-title">{{ title }}</h1>
          <div class="grow" />
          <el-button text @click="router.push('/')">回前台</el-button>
        </header>
        <div class="body">
          <RouterView />
        </div>
      </main>
    </template>
  </div>
</template>

<style scoped>
.admin-shell { display: flex; min-height: 100vh; background: #f7f8fa; }
.deny { flex: 1; display: flex; align-items: center; justify-content: center; background: #fff; }

.side {
  width: 208px; flex-shrink: 0;
  background: #fff; border-right: 1px solid #eee;
  display: flex; flex-direction: column;
  position: sticky; top: 0; height: 100vh;
}
.side-brand { display: flex; align-items: center; gap: 10px; padding: 16px 16px 14px; }
.side-logo { width: 30px; height: 30px; border-radius: 8px; object-fit: cover; }
.side-title { font-size: 14px; font-weight: 700; color: var(--ink-color); line-height: 1.2; }
.side-brand .sub { font-size: 11px; color: #bbb; }

.side-nav { display: flex; flex-direction: column; gap: 2px; padding: 4px 8px; }
.side-item {
  display: flex; align-items: center; gap: 9px;
  padding: 9px 12px; border-radius: 8px;
  font-size: 13px; color: #555; text-decoration: none;
  transition: background 0.18s ease, color 0.18s ease;
  min-height: 40px;
}
.side-item:hover { background: #fafafa; color: var(--ink-color); }
.side-item.active { background: #fff7d6; color: var(--ink-color); font-weight: 600; box-shadow: inset 3px 0 0 #ffd21e; }
.side-ico { width: 17px; height: 17px; object-fit: contain; opacity: .85; }
.side-item.active .side-ico { opacity: 1; }
.side-item:focus-visible { outline: 2px solid #e6b400; outline-offset: -2px; }

.content { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.topbar {
  display: flex; align-items: center; gap: 12px;
  padding: 14px 20px; background: #fff; border-bottom: 1px solid #eee;
  position: sticky; top: 0; z-index: 5;
}
.topbar-title { margin: 0; font-size: 17px; color: var(--ink-color); }
.grow { flex: 1; }
.body { padding: 16px 20px 28px; }

@media (max-width: 768px) {
  .admin-shell { flex-direction: column; }
  .side { width: 100%; height: auto; position: static; flex-direction: row; align-items: center; overflow-x: auto; }
  .side-brand { display: none; }
  .side-nav { flex-direction: row; padding: 4px 6px; gap: 2px; }

  /* ⚠️ 这两行是必须的：侧边栏从竖排改成横排后，每个 item 仍是 flex 容器，
     没有 nowrap 时「平台概览」会被压成一列一个字（实测 375px 屏上就是竖排的）。
     flex: 0 0 auto 保证不被压缩，多出来的宽度交给 .side 的横向滚动。 */
  .side-item { flex: 0 0 auto; white-space: nowrap; padding: 8px 10px; }
  .side-ico { width: 16px; height: 16px; }
  .side-item.active { box-shadow: inset 0 -3px 0 #ffd21e; }

  .body { padding: 12px 12px 24px; }
  .topbar { padding: 10px 12px; }
  .topbar-title { font-size: 16px; }
}

@media (max-width: 480px) {
  .side-item { padding: 7px 8px; font-size: 12.5px; }
}
</style>
