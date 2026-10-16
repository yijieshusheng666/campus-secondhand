<script setup>
import { ref, watch, onMounted, onUnmounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from './api'
import ChatPanel from './components/ChatPanel.vue'
import { navIcon, navTint } from './icons'
import { avatarUrl } from './avatars'
import { currentUser, patchUser, reloadUser, setUser } from './session'

const route = useRoute()
const router = useRouter()

const navs = [
  { path: '/', label: '首页' },
  { path: '/wanted', label: '求购' },
  { path: '/messages', label: '消息' },
  { path: '/publish', label: '发布商品' },
  { path: '/orders', label: '我的订单' },
  { path: '/user', label: '个人中心' }
]

// 当前用户来自共享会话对象：个人中心改昵称/换头像后，这里（含右上角头像）会立刻跟着变
const user = currentUser

// B4：管理端入口只对 role=admin 的账号显示
const visibleNavs = computed(() => (user.value?.role === 'admin'
  ? [...navs, { path: '/admin', label: '管理端' }]
  : navs))

// 兼容改动前登录的老会话：localStorage 里可能没有 role，补查一次并回写
async function syncUserRole() {
  const cached = user.value
  if (!cached || cached.role || !cached.id) return
  try {
    const { data } = await request.get(`/users/${cached.id}`)
    if (data?.role) {
      patchUser({ role: data.role })
    }
  } catch (e) {
    // 查不到就按普通用户处理
  }
}

// 路由切换时刷新登录状态（登录/退出后界面同步）。
// reloadUser 内部只在真的变了才替换对象，避免每次切页都把整个头部（含导航）重渲染。
watch(() => route.path, reloadUser)

// 悬浮智能客服：纯自控面板，不依赖任何 UI 库组件
const chatVisible = ref(false)
const fabTipVisible = ref(!localStorage.getItem('fabTipClosed'))
const fabRef = ref(null)

function toggleChat() {
  chatVisible.value = !chatVisible.value
  if (chatVisible.value) {
    fabTipVisible.value = false
    localStorage.setItem('fabTipClosed', '1')
  }
}

// 在 document 捕获阶段判断点击是否落在客服按钮范围：
// 即使按钮被其它元素（如浏览器插件浮层）覆盖、或事件被 stopPropagation，也能正常打开
function onDocumentClick(e) {
  const el = fabRef.value
  if (!el) return
  const r = el.getBoundingClientRect()
  if (e.clientX >= r.left && e.clientX <= r.right && e.clientY >= r.top && e.clientY <= r.bottom) {
    toggleChat()
  }
}

onMounted(() => {
  document.addEventListener('click', onDocumentClick, true)
  syncUserRole()
})
onUnmounted(() => document.removeEventListener('click', onDocumentClick, true))

function onGoHome() {
  chatVisible.value = false
  router.push('/')
}

// 退出登录先二次确认，避免误点直接把登录态清了
async function onLogout() {
  try {
    await ElMessageBox.confirm('退出后需要重新登录才能发布商品、下单和查看订单。确定退出吗？', '退出登录', {
      type: 'warning',
      confirmButtonText: '确定退出',
      cancelButtonText: '再想想'
    })
  } catch {
    return
  }
  setUser(null)
  ElMessage.success('已退出登录')
  router.push('/login')
}
</script>

<template>
  <div class="layout">
    <header class="header">
      <div class="header-inner">
        <div class="logo" @click="router.push('/')">
          <!-- 品牌 logo（书 + 双向箭头）：public/images/logo.png，原图白底已抠成透明 -->
          <img class="logo-mark" src="/images/logo.png" alt="校园二手交易平台">
          <div class="logo-text-wrap">
            <span class="logo-text">校园二手交易平台</span>
            <span class="logo-slogan">淘点好物 · 校园面交更放心</span>
          </div>
        </div>

        <nav class="nav-links">
          <router-link v-for="n in visibleNavs" :key="n.path" :to="n.path" class="nav-link"
            :class="{ active: route.path === n.path }">
            <!-- 图标统一走 icons.js；万一某个入口没登记图标，这里就退回纯文字。
                 「消息」复用了求购的气泡图，用 navTint 给的色相偏移区分成两张图 -->
            <img v-if="navIcon(n.path)" class="nav-icon" :src="navIcon(n.path)"
              :style="{ filter: navTint(n.path) || 'none' }" alt="">
            {{ n.label }}
          </router-link>
        </nav>

        <div class="user-area">
          <template v-if="user">
            <!-- 选过头像就显示头像，没选过 el-avatar 会自动回退到下面的昵称首字 -->
            <el-avatar :size="30" class="avatar" :src="avatarUrl(user)">
              {{ user.username?.charAt(0) }}
            </el-avatar>
            <span class="username">{{ user.username }}</span>
            <el-button link type="danger" @click="onLogout">退出</el-button>
          </template>
          <el-button v-else type="primary" round @click="router.push('/login')">登录 / 注册</el-button>
        </div>
      </div>
    </header>

    <main class="main">
      <router-view />
    </main>

    <!-- 悬浮智能客服：传送到 body，彻底脱离页面布局与层叠上下文影响 -->
    <Teleport to="body">
      <button ref="fabRef" class="chat-fab" type="button" data-version="v3" title="智能客服 (v3)">
        <img class="chat-fab-icon" src="/images/icons/bot.svg" alt="智能客服" />
        <transition name="fade">
          <span v-if="fabTipVisible" class="fab-tip">有问题？问一问</span>
        </transition>
      </button>

      <transition name="slide">
        <div v-show="chatVisible" class="chat-widget">
          <ChatPanel @close="chatVisible = false" @go-home="onGoHome" />
        </div>
      </transition>
    </Teleport>
  </div>
</template>

<style>
/* ===== 全局闲鱼风格主题（校园黄 + 好物价红） ===== */
:root {
  --el-color-primary: #ffd21e;
  --el-color-primary-light-3: #ffe27a;
  --el-color-primary-light-5: #ffeb9f;
  --el-color-primary-light-7: #fff3c2;
  --el-color-primary-light-8: #fff7d6;
  --el-color-primary-light-9: #fffbea;
  --el-color-primary-dark-2: #e6b400;
  --price-color: #ff4400;
  --ink-color: #23201a;
}
html, body { margin: 0; padding: 0; }
/* 滚动条位置常驻：否则在"长页面 ↔ 短页面"之间切换时，滚动条出现/消失会让
   文档宽度变化约 15px，整个头部（含导航栏）跟着横向平移，看起来像抖了一下。
   overflow-y: scroll 兜底所有浏览器，scrollbar-gutter 在支持的浏览器上更干净。 */
html { overflow-y: scroll; scrollbar-gutter: stable; }

/* 打开弹窗时不要横向位移。
   Element Plus 的 useLockscreen 会给 body 加上 .el-popup-parent--hidden，并把 body 的
   宽度内联设成 calc(100% - 滚动条宽度)，用来补偿「滚动条被隐藏后多出来的那点宽度」。
   但本项目 html 本来就常驻滚动条（上一行的 overflow-y: scroll），滚动条并不会消失，
   这份补偿纯属多余：整页会向左平移约 8px，关闭弹窗 200ms 后再移回来 ——
   表现就是点「实名认证 / 修改昵称 / 更换头像 / 收货地址管理」这类按钮时界面抖一下。
   这里把它的内联宽度抵消掉；弹窗遮罩、点击外部关闭等行为都不受影响。 */
body.el-popup-parent--hidden {
  width: 100% !important;
  padding-right: 0 !important;
}
body {
  background: #f6f7f9;
  font-family: 'Microsoft YaHei', 'PingFang SC', sans-serif;
}

/* 黄色主按钮配深色文字（闲鱼风） */
.el-button--primary { color: var(--ink-color); font-weight: 600; }
.el-button--primary:hover, .el-button--primary:focus { color: var(--ink-color); }

/* 客服面板：铺满自控容器 */
.chat-widget .chat-panel {
  height: 100%;
  width: 100%;
}

.fade-enter-active, .fade-leave-active { transition: opacity 0.3s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }

.slide-enter-active, .slide-leave-active { transition: opacity 0.25s; }
.slide-enter-from, .slide-leave-to { opacity: 0; }
</style>

<style scoped>
.layout { min-height: 100vh; }

/* ===== 顶部导航：三段式布局，留足呼吸感 ===== */
.header {
  background: #fff;
  position: sticky;
  top: 0;
  z-index: 100;
  border-bottom: 2px solid #ffd21e;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
}
.header-inner {
  max-width: 1200px;
  margin: 0 auto;
  height: 64px;
  padding: 0 20px;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 32px;
}
.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  flex-shrink: 0;
}
/* 品牌 logo：素材已抠成透明底，直接贴在白色头部上，不用外框 */
.logo-mark {
  height: 40px;
  width: auto;
  display: block;
}
.logo-text-wrap { display: flex; flex-direction: column; line-height: 1.25; }
.logo-text { font-size: 16px; font-weight: bold; color: var(--ink-color); white-space: nowrap; }
.logo-slogan { font-size: 11px; color: #b08b00; white-space: nowrap; }

/* 导航链接：胶囊样式，间距充足 */
.nav-links {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  justify-content: center;
}
.nav-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 18px;
  font-size: 15px;
  color: #4a4a4a;
  text-decoration: none;
  border-radius: 18px;
  white-space: nowrap;
  transition: background 0.2s, color 0.2s;
}
/* 导航图标：素材自带圆角方块底，统一按 20px 显示 */
.nav-icon {
  width: 20px;
  height: 20px;
  border-radius: 6px;
  object-fit: cover;
  flex-shrink: 0;
}
.nav-link:hover { background: #fffbea; color: var(--ink-color); }
.nav-link.active { background: #fff3c2; color: var(--ink-color); font-weight: 700; }

.user-area {
  display: flex;
  align-items: center;
  gap: 10px;
  white-space: nowrap;
  flex-shrink: 0;
}
.avatar { background: #ffd21e; color: var(--ink-color); font-size: 14px; font-weight: 600; }
.username { font-size: 14px; }

.main {
  max-width: 1200px;
  margin: 0 auto;
  width: 100%;
  padding: 16px 20px 60px;
  box-sizing: border-box;
}

/* ===== 悬浮客服按钮（右侧垂直居中，避免落在底部任务栏遮挡区） ===== */
.chat-fab {
  position: fixed;
  right: 24px;
  top: calc(50% - 30px);
  width: 60px;
  height: 60px;
  padding: 0;
  border-radius: 50%;
  background: #fff;
  border: 2px solid #ffd21e;
  box-shadow: 0 4px 16px rgba(255, 184, 0, 0.45);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2147483000;
  transition: transform 0.2s;
  outline: none;
}
.chat-fab:hover { transform: scale(1.08); }

/* ===== 智能客服面板（悬浮气泡，位于按钮左侧并垂直居中） ===== */
.chat-widget {
  position: fixed;
  right: 100px;
  top: 50%;
  transform: translateY(-50%);
  width: 380px;
  height: min(560px, calc(100vh - 40px));
  max-width: calc(100vw - 32px);
  background: #fff;
  border-radius: 16px;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.18);
  overflow: hidden;
  z-index: 2147483000;
  display: flex;
  flex-direction: column;
}
.chat-fab img { width: 40px; height: 40px; border-radius: 50%; object-fit: cover; }
.fab-tip {
  position: absolute;
  right: 70px;
  top: 50%;
  transform: translateY(-50%);
  background: var(--ink-color);
  color: #ffd21e;
  font-size: 13px;
  padding: 6px 12px;
  border-radius: 8px;
  white-space: nowrap;
}
.fab-tip::after {
  content: '';
  position: absolute;
  right: -5px;
  top: 50%;
  transform: translateY(-50%);
  border-left: 6px solid var(--ink-color);
  border-top: 5px solid transparent;
  border-bottom: 5px solid transparent;
}
</style>
