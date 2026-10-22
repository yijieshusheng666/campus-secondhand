<script setup>
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useApiRequest } from '../composables/useApiRequest'
import request, { AI_TIMEOUT } from '../api'
import { useCurrentUser } from '../composables/useCurrentUser'
import { formatTime } from '../utils/format'

const route = useRoute()
const router = useRouter()

// 统一错误提示口径：网络/HTTP 错误由 useApiRequest 自动 ElMessage.error 并抛异常
const api = useApiRequest('操作失败，请检查后端服务')

const user = useCurrentUser()

const conversations = ref([])   // { goodsId, goodsTitle, peerId, peerName, lastContent, lastTime }
const active = ref(null)        // 当前正在沟通的会话
const messages = ref([])        // 当前会话的消息
const input = ref('')
const loading = ref(false)
const bodyRef = ref(null)

// A5 助手：对话摘要 / 议价话术
const assistVisible = ref(false)
const assistLoading = ref(false)
const assistType = ref('')      // 'summary' | 'negotiate'
const assistMode = ref('')
const assistSummary = ref('')
const assistPoints = ref([])
const assistScripts = ref([])

let timer = null

function isActive(c) {
  return active.value && active.value.goodsId === c.goodsId && active.value.peerId === c.peerId
}

function scrollToBottom() {
  nextTick(() => {
    if (bodyRef.value) bodyRef.value.scrollTop = bodyRef.value.scrollHeight
  })
}

async function loadConversations() {
  if (!user.value) return
  try {
    const { data } = await api.get('/messages/conversations', { params: { userId: user.value.id } })
    conversations.value = data
  } catch (e) {
    // 静默失败，避免轮询时反复弹提示
  }
}

async function loadMessages() {
  if (!active.value) return
  loading.value = true
  try {
    const { data } = await api.get('/messages', {
      params: { goodsId: active.value.goodsId, userId: user.value.id, peerId: active.value.peerId }
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
  loadActiveGoods()
}

/**
 * 手机上「返回会话列表」。
 * 桌面是左右两栏，两栏同时可见；手机上只能显示一栏，所以需要一个显式出口。
 * 不传参直接清空 active：4 秒轮询里的 loadMessages 有 `if (!active.value) return` 守卫，
 * 清空后不会继续拉消息，安全。
 */
function backToList() {
  active.value = null
  messages.value = []
  activeGoods.value = null
}

// 会话对应的商品详情：既给头部卡片用（图片/价格），也给 AI 摘要/议价提供商品信息
const activeGoods = ref(null)

async function loadActiveGoods() {
  activeGoods.value = null
  if (!active.value?.goodsId) return null
  try {
    const { data } = await api.get(`/goods/${active.value.goodsId}`)
    activeGoods.value = data
    return data
  } catch (e) {
    return null
  }
}

function goGoods() {
  if (!active.value?.goodsId) {
    ElMessage.info('这个会话没有关联的宝贝')
    return
  }
  router.push({ path: '/', query: { goodsId: active.value.goodsId } })
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
    const { data } = await api.get(`/users/${peerId}`)
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
  if (!user.value) {
    ElMessage.warning('请先登录')
    return
  }
  const payload = {
    goodsId: active.value.goodsId,
    fromUserId: user.value.id,
    toUserId: active.value.peerId,
    content: text
  }
  input.value = ''
  try {
    const { data } = await api.post('/messages', payload)
    if (data.success) {
      await loadMessages()
      await loadConversations()
    } else {
      ElMessage.error(data.message || '发送失败')
    }
  } catch (e) {
    // useApiRequest 已统一提示（或当前是 request.* 直调，由后端 message 自动展示）
  }
}

// 把当前会话整理成 AI 服务认识的对话记录（mine = 我发的）
function currentTranscript() {
  return messages.value
    .filter(m => m.content)
    .map(m => ({ role: m.fromUserId === user.value.id ? 'mine' : 'peer', content: m.content }))
}

// A5-1 对话摘要：总结与卖家的长对话，抽价格/见面约定/待确认事项
async function doSummarize() {
  if (!messages.value.length) {
    ElMessage.warning('这个会话还没有消息，先聊两句吧')
    return
  }
  assistType.value = 'summary'
  assistVisible.value = true
  assistLoading.value = true
  assistSummary.value = ''
  assistPoints.value = []
  assistMode.value = ''
  try {
    const goods = await loadActiveGoods()
    const { data } = await api.post('/ai/summarize', {
      messages: currentTranscript(),
      goodsTitle: goods?.title || active.value.goodsTitle || '',
      goodsPrice: goods?.price ?? null
    }, { timeout: AI_TIMEOUT })
    if (!data.available) {
      ElMessage.error('AI 服务未启动，摘要暂时不可用')
      assistVisible.value = false
      return
    }
    assistSummary.value = data.summary
    assistPoints.value = data.points || []
    assistMode.value = data.mode
  } catch (e) {
    ElMessage.error('摘要生成失败')
    assistVisible.value = false
  } finally {
    assistLoading.value = false
  }
}

// A5-2 议价话术：按商品价格给三档可直接发送的说法
async function doNegotiate() {
  assistType.value = 'negotiate'
  assistVisible.value = true
  assistLoading.value = true
  assistScripts.value = []
  assistMode.value = ''
  try {
    const goods = await loadActiveGoods()
    const { data } = await api.post('/ai/negotiate', {
      goodsTitle: goods?.title || active.value.goodsTitle || '',
      goodsPrice: goods?.price ?? null,
      description: goods?.description || ''
    }, { timeout: AI_TIMEOUT })
    if (!data.available) {
      ElMessage.error('AI 服务未启动，话术暂时不可用')
      assistVisible.value = false
      return
    }
    assistScripts.value = data.scripts || []
    assistMode.value = data.mode
  } catch (e) {
    ElMessage.error('话术生成失败')
    assistVisible.value = false
  } finally {
    assistLoading.value = false
  }
}

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制，去发给卖家吧')
  } catch (e) {
    ElMessage.warning('复制失败，请手动选中文字')
  }
}

function modeText(mode) {
  return mode === 'api' ? 'AI 生成' : '规则模板'
}

onMounted(async () => {
  if (!user.value) return
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

    <div v-else class="msg-layout" :class="{ 'has-active': !!active }">
      <!-- 会话列表 -->
      <aside class="conv-list">
        <div class="conv-head">我的消息</div>
        <el-empty v-if="conversations.length === 0" description="还没有会话" :image-size="56" />
        <div v-for="c in conversations" :key="c.goodsId + '-' + c.peerId" class="conv-item"
          :class="{ active: isActive(c) }" @click="openConversation(c)">
          <div class="conv-title">{{ c.goodsTitle || '（商品信息缺失）' }}</div>
          <div class="conv-sub">{{ c.peerName }}：{{ c.lastContent }}</div>
          <div class="conv-time">{{ formatTime(c.lastTime, 'short') }}</div>
        </div>
      </aside>

      <!-- 聊天窗口 -->
      <section class="chat-area">
        <template v-if="active">
          <div class="chat-head">
            <div class="chat-head-main">
              <!-- 只在小屏显示：桌面两栏并排，不需要返回 -->
              <button type="button" class="chat-back" @click="backToList">‹ 会话列表</button>
              <div class="chat-peer">与「{{ active.peerName || '对方' }}」沟通中</div>
              <!-- 点卡片直接回首页看这件宝贝的详情 -->
              <div class="chat-goods-card" :class="{ empty: !active.goodsId }" @click="goGoods">
                <img  loading="lazy" v-if="activeGoods?.images?.length" class="cg-img" :src="activeGoods.images[0].imageUrl" alt="宝贝">
                <div v-else class="cg-img cg-img-empty">🎁</div>
                <div class="cg-body">
                  <div class="cg-title">{{ active.goodsTitle || '（商品信息缺失）' }}</div>
                  <div class="cg-sub">
                    <span v-if="activeGoods" class="cg-price">¥{{ activeGoods.price }}</span>
                    <span v-if="activeGoods && !activeGoods.status" class="cg-price">—</span>
                    <span class="cg-link">查看宝贝详情 ›</span>
                  </div>
                </div>
              </div>
            </div>
            <div class="chat-actions">
              <el-button size="small" round @click="doSummarize">AI 摘要</el-button>
              <el-button size="small" round type="primary" plain @click="doNegotiate">帮我砍价</el-button>
            </div>
          </div>

          <div class="chat-body" ref="bodyRef" v-loading="loading">
            <el-empty v-if="messages.length === 0" description="还没有消息，先打个招呼吧" :image-size="56" />
            <div v-for="m in messages" :key="m.id" class="msg-row" :class="{ mine: m.fromUserId === user.id }">
              <div class="msg-bubble">{{ m.content }}</div>
              <div class="msg-time">{{ formatTime(m.createdAt, 'short') }}</div>
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

    <!-- A5 助手结果：对话摘要 / 议价话术 -->
    <el-dialog v-model="assistVisible" :title="assistType === 'summary' ? '对话摘要' : '议价话术'"
      width="520px" round>
      <div v-loading="assistLoading" class="assist-body">
        <template v-if="assistType === 'summary'">
          <p class="assist-summary">{{ assistSummary }}</p>
          <ul class="assist-points">
            <li v-for="(p, i) in assistPoints" :key="i">{{ p }}</li>
          </ul>
        </template>
        <template v-else>
          <div class="assist-tip">按价格递进的三档话术，点「复制」直接发给卖家：</div>
          <div v-for="(s, i) in assistScripts" :key="i" class="script-item">
            <div class="script-label">{{ s.label }}</div>
            <div class="script-text">{{ s.text }}</div>
            <el-button size="small" round @click="copyText(s.text)">复制</el-button>
          </div>
        </template>
      </div>
      <template #footer>
        <span v-if="assistMode" class="assist-mode">{{ modeText(assistMode) }}</span>
        <el-button @click="assistVisible = false">关闭</el-button>
      </template>
    </el-dialog>
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
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.chat-head-main { min-width: 0; }
.chat-actions { flex-shrink: 0; display: flex; gap: 8px; }
.chat-peer { font-weight: bold; font-size: 15px; color: var(--ink-color); }

/* A5 助手结果面板 */
.assist-body { min-height: 60px; }
.assist-summary { font-size: 14px; line-height: 1.7; color: var(--ink-color); margin: 0 0 10px; }
.assist-points { margin: 0; padding-left: 18px; font-size: 13px; color: #666; line-height: 1.9; }
.assist-tip { font-size: 12px; color: #999; margin-bottom: 10px; }
.script-item {
  position: relative;
  padding: 10px 12px;
  border: 1px solid #f0f0f0;
  border-radius: 10px;
  margin-bottom: 10px;
  background: #fbfbfb;
}
.script-label { font-size: 12px; font-weight: 600; color: #8a6100; margin-bottom: 4px; }
.script-text { font-size: 13px; line-height: 1.6; color: #444; margin-bottom: 8px; }
.assist-mode { font-size: 12px; color: #bbb; margin-right: 10px; }
/* 会话头部：可点击的宝贝卡片 */
.chat-goods-card {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
  padding: 6px 10px 6px 6px;
  background: #fff;
  border: 1px solid #f0e2b0;
  border-radius: 10px;
  max-width: 340px;
  cursor: pointer;
  transition: all 0.2s;
}
.chat-goods-card:hover { border-color: #ffd21e; box-shadow: 0 2px 8px rgba(255, 210, 30, 0.3); }
.chat-goods-card.empty { cursor: default; opacity: 0.6; }
.cg-img { width: 42px; height: 42px; border-radius: 8px; object-fit: cover; flex-shrink: 0; }
.cg-img-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f7f8fa;
  font-size: 18px;
}
.cg-body { min-width: 0; }
.cg-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-color);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cg-sub { display: flex; align-items: center; gap: 8px; margin-top: 2px; }
.cg-price { font-size: 13px; font-weight: bold; color: var(--price-color); }
.cg-link { font-size: 12px; color: #1a5fb4; }
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

/* 返回按钮：桌面不显示（两栏并排，不存在"返回"这个概念） */
.chat-back { display: none; }

/* ============================================================
   移动端适配（≤768px）
   桌面是「左会话列表 280px + 右聊天窗」两栏并排。
   手机上两栏各占一半都放不下一句话，所以改成**主从切换**：
   没选会话时只显示列表，选了之后只显示聊天窗，用「‹ 会话列表」返回。
   ============================================================ */
@media (max-width: 768px) {
  .msg-layout {
    /* 140px 是按桌面单行头部算的；手机头部是两行、高度不固定，
       用 dvh 让聊天区自动吃满剩余高度（地址栏收起/展开时也不会跳动） */
    height: calc(100dvh - 132px);
    min-height: 380px;
    gap: 0;
  }

  .conv-list {
    width: 100%;
    border-radius: 12px;
  }

  /* 主从切换的两个方向 */
  .msg-layout.has-active .conv-list { display: none; }
  .msg-layout:not(.has-active) .chat-area { display: none; }

  .chat-back {
    display: inline-flex;
    align-items: center;
    gap: 2px;
    margin: 0 0 6px -4px;
    padding: 2px 6px;
    border: none;
    background: transparent;
    color: #1a5fb4;
    font-size: 13px;
    font-family: inherit;
    cursor: pointer;
  }

  /* 头部：标题 + 两个 AI 按钮在 375px 上会挤成两行并顶出宝贝卡片，
     让操作区换到下一行、右对齐 */
  .chat-head {
    padding: 10px 12px;
    flex-wrap: wrap;
    gap: 8px;
  }
  .chat-head-main { flex: 1 1 100%; }
  .chat-actions { flex: 1 1 100%; justify-content: flex-end; }
  .chat-peer { font-size: 14px; }
  .chat-goods-card { max-width: 100%; }

  .chat-body { padding: 12px; }
  /* 气泡占宽从 72% 放到 82%：手机上一句话本来就短，太窄会折成三段 */
  .msg-bubble { max-width: 82%; font-size: 14px; }
  .chat-input { padding: 8px 10px; gap: 8px; }
  .chat-input .el-button { flex: 0 0 auto; }

  /* 会话列表项：加大点击高度，手指点得准 */
  .conv-item { padding: 12px; }
  .conv-head { padding: 12px 14px; font-size: 14px; }
}

@media (max-width: 480px) {
  .msg-layout { height: calc(100dvh - 124px); }
  .chat-actions :deep(.el-button) { padding: 6px 10px; font-size: 12px; }
}
</style>