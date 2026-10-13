<script setup>
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../api'

const route = useRoute()
const router = useRouter()

const user = JSON.parse(localStorage.getItem('user') || 'null')

const conversations = ref([])   // { goodsId, goodsTitle, peerId, peerName, lastContent, lastTime }
const active = ref(null)        // 当前正在沟通的会话
const messages = ref([])        // 当前会话的消息
const input = ref('')
const loading = ref(false)
const bodyRef = ref(null)

let timer = null

function formatTime(t) {
  if (!t) return ''
  return String(t).replace('T', ' ').slice(5, 16)
}

function isActive(c) {
  return active.value && active.value.goodsId === c.goodsId && active.value.peerId === c.peerId
}

function scrollToBottom() {
  nextTick(() => {
    if (bodyRef.value) bodyRef.value.scrollTop = bodyRef.value.scrollHeight
  })
}

async function loadConversations() {
  if (!user) return
  try {
    const { data } = await request.get('/messages/conversations', { params: { userId: user.id } })
    conversations.value = data
  } catch (e) {
    // 静默失败，避免轮询时反复弹提示
  }
}

async function loadMessages() {
  if (!active.value) return
  loading.value = true
  try {
    const { data } = await request.get('/messages', {
      params: { goodsId: active.value.goodsId, userId: user.id, peerId: active.value.peerId }
    })
    messages.value = data
    scrollToBottom()
  } catch (e) {
    ElMessage.error('消息加载失败，请检查后端服务')
  } finally {
    loading.value = false
  }
}

async function openConversation(conv) {
  active.value = { ...conv }
  await loadMessages()
}

// 从商品页「联系卖家」跳转过来时，会话可能还不存在，需要先补齐商品标题和卖家昵称
async function resolveFromQuery() {
  const goodsId = route.query.goodsId
  const peerId = route.query.peerId
  if (!goodsId || !peerId) return

  let goodsTitle = ''
  let peerName = ''
  try {
    const { data } = await request.get(`/goods/${goodsId}`)
    goodsTitle = data.title
  } catch (e) { /* 商品可能已被删除 */ }
  try {
    const { data } = await request.get(`/users/${peerId}`)
    peerName = data.username
  } catch (e) { /* 忽略 */ }

  active.value = {
    goodsId: Number(goodsId),
    goodsTitle,
    peerId: Number(peerId),
    peerName
  }
  await loadMessages()
}

async function sendMessage() {
  const text = input.value.trim()
  if (!text || !active.value) return
  if (!user) {
    ElMessage.warning('请先登录')
    return
  }
  const payload = {
    goodsId: active.value.goodsId,
    fromUserId: user.id,
    toUserId: active.value.peerId,
    content: text
  }
  input.value = ''
  try {
    const { data } = await request.post('/messages', payload)
    if (data.success) {
      await loadMessages()
      await loadConversations()
    } else {
      ElMessage.error(data.message || '发送失败')
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '发送失败，请检查后端服务')
  }
}

onMounted(async () => {
  if (!user) return
  await loadConversations()
  await resolveFromQuery()
  // 轮询刷新，模拟实时对话（演示用，无需 WebSocket）
  timer = setInterval(() => {
    loadMessages()
    loadConversations()
  }, 4000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div class="msg-page">
    <div v-if="!user" class="empty-wrap">
      <el-empty description="请先登录后查看消息">
        <el-button type="primary" @click="router.push('/login')">去登录</el-button>
      </el-empty>
    </div>

    <div v-else class="msg-layout">
      <!-- 会话列表 -->
      <aside class="conv-list">
        <div class="conv-head">我的消息</div>
        <el-empty v-if="conversations.length === 0" description="还没有会话" :image-size="56" />
        <div v-for="c in conversations" :key="c.goodsId + '-' + c.peerId" class="conv-item"
          :class="{ active: isActive(c) }" @click="openConversation(c)">
          <div class="conv-title">{{ c.goodsTitle || '（商品信息缺失）' }}</div>
          <div class="conv-sub">{{ c.peerName }}：{{ c.lastContent }}</div>
          <div class="conv-time">{{ formatTime(c.lastTime) }}</div>
        </div>
      </aside>

      <!-- 聊天窗口 -->
      <section class="chat-area">
        <template v-if="active">
          <div class="chat-head">
            <div class="chat-peer">与「{{ active.peerName || '对方' }}」沟通中</div>
            <div class="chat-goods">宝贝：{{ active.goodsTitle || '—' }}</div>
          </div>

          <div class="chat-body" ref="bodyRef" v-loading="loading">
            <el-empty v-if="messages.length === 0" description="还没有消息，先打个招呼吧" :image-size="56" />
            <div v-for="m in messages" :key="m.id" class="msg-row" :class="{ mine: m.fromUserId === user.id }">
              <div class="msg-bubble">{{ m.content }}</div>
              <div class="msg-time">{{ formatTime(m.createdAt) }}</div>
            </div>
          </div>

          <div class="chat-input">
            <el-input v-model="input" placeholder="问一问成色、能不能便宜点…" @keyup.enter="sendMessage" />
            <el-button type="primary" @click="sendMessage">发送</el-button>
          </div>
        </template>

        <div v-else class="chat-placeholder">
          <el-empty description="从左侧选择一个会话开始沟通" />
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.msg-page { max-width: 1000px; margin: 0 auto; }
.empty-wrap { background: #fff; border-radius: 12px; padding: 40px; }

.msg-layout {
  display: flex;
  gap: 16px;
  height: calc(100vh - 140px);
  min-height: 420px;
}

/* 会话列表 */
.conv-list {
  width: 280px;
  flex-shrink: 0;
  background: #fff;
  border-radius: 12px;
  overflow-y: auto;
  border: 1px solid #f0f0f0;
}
.conv-head {
  padding: 14px 16px;
  font-weight: bold;
  font-size: 15px;
  border-bottom: 1px solid #f0f0f0;
  position: sticky;
  top: 0;
  background: #fff;
  color: var(--ink-color);
}
.conv-item {
  padding: 12px 14px;
  border-bottom: 1px solid #f7f7f7;
  cursor: pointer;
  transition: background 0.2s;
}
.conv-item:hover { background: #fffbea; }
.conv-item.active { background: #fff3c2; }
.conv-title {
  font-size: 14px;
  font-weight: 600;
  color: #222;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin-bottom: 4px;
}
.conv-sub {
  font-size: 12px;
  color: #888;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.conv-time { font-size: 11px; color: #bbb; margin-top: 4px; }

/* 聊天窗口 */
.chat-area {
  flex: 1;
  background: #fff;
  border-radius: 12px;
  border: 1px solid #f0f0f0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.chat-head {
  padding: 14px 18px;
  border-bottom: 1px solid #f0f0f0;
  background: linear-gradient(135deg, #fff8e0, #ffffff);
}
.chat-peer { font-weight: bold; font-size: 15px; color: var(--ink-color); }
.chat-goods { font-size: 12px; color: #8a6100; margin-top: 4px; }
.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px 18px;
  background: #f7f8fa;
}
.msg-row { display: flex; flex-direction: column; align-items: flex-start; margin-bottom: 14px; }
.msg-row.mine { align-items: flex-end; }
.msg-bubble {
  max-width: 72%;
  padding: 9px 14px;
  border-radius: 12px;
  background: #fff;
  border: 1px solid #e8e8e8;
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
}
.msg-row.mine .msg-bubble {
  background: #ffd21e;
  color: var(--ink-color);
  border: none;
  font-weight: 500;
}
.msg-time { font-size: 11px; color: #bbb; margin-top: 4px; }
.chat-input {
  display: flex;
  gap: 10px;
  padding: 12px 16px;
  border-top: 1px solid #f0f0f0;
}
.chat-placeholder {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>