<script setup>
import { ref, watch, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import ChatPanel from './components/ChatPanel.vue'

const route = useRoute()
const router = useRouter()

const navs = [
  { path: '/', label: '首页' },
  { path: '/messages', label: '消息' },
  { path: '/publish', label: '发布商品' },
  { path: '/orders', label: '我的订单' },
  { path: '/user', label: '个人中心' }
]

const user = ref(JSON.parse(localStorage.getItem('user') || 'null'))

// 路由切换时刷新登录状态（登录/退出后界面同步）
watch(() => route.path, () => {
  user.value = JSON.parse(localStorage.getItem('user') || 'null')
})

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

onMounted(() => document.addEventListener('click', onDocumentClick, true))
onUnmounted(() => document.removeEventListener('click', onDocumentClick, true))

function onGoHome() {
  chatVisible.value = false
  router.push('/')
}

function onLogout() {
  localStorage.removeItem('user')
  user.value = null
  ElMessage.success('已退出登录')
  router.push('/login')
}
</script>

<template>
  <div class="layout">
    <header class="header">
      <div class="header-inner">
        <div class="logo" @click="router.push('/')">
          <span class="logo-mark">🎓</span>
          <div class="logo-text-wrap">
            <span class="logo-text">校园二手交易平台</span>
            <span class="logo-slogan">淘点好物 · 校园面交更放心</span>
          </div>
        </div>

        <nav class="nav-links">
          <router-link v-for="n in navs" :key="n.path" :to="n.path" class="nav-link"
            :class="{ active: route.path === n.path }">
            {{ n.label }}
          </router-link>
        </nav>

        <div class="user-area">
          <template v-if="user">
            <el-avatar :size="30" class="avatar">{{ user.username?.charAt(0) }}</el-avatar>
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
        <img src="/images/机器人.png" alt="智能客服">
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
.logo-mark {
  width: 40px;
  height: 40px;
  background: linear-gradient(135deg, #ffd21e, #ffb800);
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  box-shadow: 0 2px 6px rgba(255, 184, 0, 0.4);
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
  padding: 8px 18px;
  font-size: 15px;
  color: #4a4a4a;
  text-decoration: none;
  border-radius: 18px;
  white-space: nowrap;
  transition: background 0.2s, color 0.2s;
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