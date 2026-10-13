<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import request from '../api'

const emit = defineEmits(['go-home', 'close'])

// 一条消息的完整形态：
// { type, text, goods, sources, mode, refused, confidence, hint, safetyTip, streaming }
const messages = ref([])
const input = ref('')
const typing = ref(false)
const streaming = ref(false)

const quickAsks = ['怎么退款', '怎么发布闲置', '中区在哪面交', '教材一般多少钱']

// 三层兜底里的最后一层：AI 服务与关键词推荐都不可用时的本地规则
const fallbackRules = [
  { keys: ['你好', '您好', 'hi', 'hello', '嗨'], reply: '你好呀！我是校园小助手，想买点什么？可以告诉我商品名称或类别，比如"买台灯"、"想要一本书"。' },
  { keys: ['怎么买', '如何购买', '购买流程', '下单'], reply: '购买流程很简单：在首页找到心仪的宝贝，点击"我想要"，选择或填写收货地址后确认付款即可。付款后订单会出现在"我的订单"里，等待卖家发货。' },
  { keys: ['发货', '多久到', '物流', '快递'], reply: '卖家确认发货后，订单状态会变为"已发货"。同校交易一般当面交接或宿舍楼下自取，很快就能收到哦。' },
  { keys: ['退款', '退货'], reply: '在"我的订单"中，待发货状态的订单可以申请退款，退款后宝贝会自动重新上架。' },
  { keys: ['发布', '卖东西', '上架', '闲置'], reply: '登录后点击"发布商品"，填写标题、分类、价格和描述，上传宝贝图片即可发布。发布后可在"个人中心"管理自己的宝贝。' },
  { keys: ['谢谢', '感谢'], reply: '不客气！祝你淘到心仪的好物～' }
]

// 可中止的流控制器：用户点"停止"时用它断开 fetch
let streamAbort = null

function getImageUrl(path) {
  if (!path) return ''
  return path.startsWith('http') ? path : path
}

function scrollToBottom() {
  nextTick(() => {
    const box = document.querySelector('.chat-body')
    if (box) box.scrollTop = box.scrollHeight
  })
}

// 来源卡片 / 安全提示是在 meta 事件时一次性插入的，会把正文顶出视口。
// delta 阶段的 scrollToBottom 发生在插入之前，所以 done 之后要再滚一次。
function scrollAfterRender() {
  nextTick(() => {
    requestAnimationFrame(() => {
      const box = document.querySelector('.chat-body')
      if (box) box.scrollTop = box.scrollHeight
    })
  })
}

function addMessage(type, text, extra = {}) {
  const msg = {
    type,
    text,
    goods: extra.goods || [],
    sources: extra.sources || [],
    mode: extra.mode || '',
    refused: extra.refused ?? false,
    confidence: extra.confidence ?? 0,
    hint: extra.hint || '',
    safetyTip: extra.safetyTip || '',
    traceId: extra.traceId || '',
    streaming: false
  }
  messages.value.push(msg)
  saveHistory()
  scrollToBottom()
  return msg
}

async function loadAllGoods() {
  try {
    const { data } = await request.get('/goods')
    return data
  } catch {
    return []
  }
}

// 只取最近几轮真实问答，别把"正在流式输出的半句话"也送进去
function buildHistory() {
  return messages.value
    .filter(m => m.text && !m.streaming)
    .slice(-6)
    .map(m => ({ role: m.type === 'ai' ? 'assistant' : 'user', content: m.text }))
}

// 后端 sources 的 type 是 "knowledge"（见 ai-service/app/routers/chat.py 的 _sources），
// 早期版本前端按 "faq" 过滤，导致依据卡片永远不显示
const KB_SOURCES = s => s && (s.type === 'knowledge' || s.type === 'faq')
const faqSources = sources => (sources || []).filter(KB_SOURCES)

// 第二层：关键词推荐（AI 服务不可用时的回退）
async function askRecommend(text) {
  const { data } = await request.post('/recommend', { keyword: text })
  if (!data.success || !data.data || data.data.length === 0) return null
  const allGoods = await loadAllGoods()
  const goods = data.data.map(item => allGoods.find(g => g.id === item.id)).filter(Boolean)
  return goods.length ? goods : null
}

// 第三层：本地规则
function askLocalRule(text) {
  const lower = text.toLowerCase()
  const rule = fallbackRules.find(r => r.keys.some(k => lower.includes(k)))
  return rule
    ? rule.reply
    : '暂时没有找到相关资料，可以换个说法试试，比如"怎么退款"、"耳机"、"台灯"。'
}

/**
 * 第一层：SSE 流式问答。
 *
 * 用 fetch + ReadableStream 而不是 EventSource，原因有二：
 *  1. EventSource 只支持 GET，这里要 POST 带 question + history；
 *  2. 需要 AbortController 支持"停止生成"。
 *
 * 事件协议（见 ai-service/app/routers/chat_stream.py）：
 *   meta  → traceId / refused / confidence / sources / safetyTip / hint
 *   delta → text 增量
 *   done  → mode / answer / hitIds
 *   error → Java 代理在 AI 服务不可用时补发（AiClient.streamChat 的兜底）
 */
async function askStream(text, target) {
  const resp = await fetch('/api/ai/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ question: text, history: buildHistory() }),
    signal: streamAbort.signal
  })

  if (!resp.ok || !resp.body) {
    throw new Error(`HTTP ${resp.status}`)
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  // SSE 以空行分事件，这里按 \n\n 切块；最后一块可能不完整，留在 buffer 等下次
  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    const blocks = buffer.split('\n\n')
    buffer = blocks.pop() || ''      // 未闭合的尾巴留到下一轮

    for (const block of blocks) {
      const line = block.split('\n').find(l => l.startsWith('data:'))
      if (!line) continue
      let payload
      try {
        payload = JSON.parse(line.slice(5).trim())
      } catch {
        continue                       // 半截 JSON，丢弃（下一块会补全）
      }
      handleEvent(payload, target)
    }
  }
}

function handleEvent(payload, target) {
  const kind = payload.event
  if (kind === 'meta') {
    // meta 先到：先把"依据来源"渲染出来，不等正文
    target.sources = payload.sources || []
    target.refused = !!payload.refused
    target.confidence = payload.confidence || 0
    target.safetyTip = payload.safetyTip || ''
    target.hint = payload.hint || ''
    target.traceId = payload.traceId || ''
    scrollAfterRender()
  } else if (kind === 'delta') {
    target.text += payload.text || ''
    scrollToBottom()
  } else if (kind === 'done') {
    target.mode = payload.mode || ''
    // 兜底：万一 delta 全丢了，用 done.answer 补上
    if (!target.text && payload.answer) target.text = payload.answer
    // 正文长度在这之后还会变（补齐 / 兜底文案），必须再滚一次
    scrollAfterRender()
  } else if (kind === 'error') {
    target.text = 'AI 服务暂时不可用，我先用关键词帮你找找看。'
    target.mode = 'unavailable'
  }
}

async function sendMessage() {
  const text = input.value.trim()
  if (!text || streaming.value) return
  input.value = ''
  addMessage('user', text)

  typing.value = true
  scrollToBottom()

  // 先占位一条 AI 消息，流式往里填
  const target = addMessage('ai', '', { streaming: true })
  streamAbort = new AbortController()
  streaming.value = true

  try {
    await askStream(text, target)
    target.streaming = false

    // 流通了但一个字都没拿到（比如 AI 服务挂了只发了 error）→ 走兜底
    if (target.mode === 'unavailable' || !target.text) {
      target.text = ''
      target.sources = []
      await fallbackLayers(target, text)
    }
  } catch (e) {
    // fetch 本身失败 / 用户主动 abort
    target.streaming = false
    if (e.name === 'AbortError') {
      if (!target.text) target.text = '（已停止生成）'
    } else {
      target.text = ''
      target.sources = []
      await fallbackLayers(target, text)
    }
  } finally {
    streaming.value = false
    typing.value = false
    streamAbort = null
    saveHistory()
    scrollToBottom()
  }
}

// 第 2、3 层兜底：关键词推荐 → 本地规则
async function fallbackLayers(target, text) {
  try {
    const recommendGoods = await askRecommend(text)
    if (recommendGoods) {
      target.goods = recommendGoods
      target.text = 'AI 服务暂时不可用，我先用关键词帮你找到了这些宝贝，点击查看详情～'
      return
    }
  } catch {
    // 继续走本地规则
  }
  target.text = askLocalRule(text)
  target.mode = 'rule'
}

function stopStreaming() {
  if (streamAbort) {
    streamAbort.abort()
    streaming.value = false
  }
}

function sendQuick(q) {
  input.value = q
  sendMessage()
}

function viewGoods() {
  emit('go-home')
}

function saveHistory() {
  const history = messages.value
    .filter(m => m.text)
    .map(m => ({
      type: m.type,
      text: m.text,
      mode: m.mode,
      refused: m.refused,
      confidence: m.confidence,
      hint: m.hint,
      safetyTip: m.safetyTip,
      goods: m.goods.map(g => g.id),
      sources: m.sources
    }))
  localStorage.setItem('chatHistory', JSON.stringify(history.slice(-50)))
}

async function loadHistory() {
  const saved = localStorage.getItem('chatHistory')
  if (!saved) return
  try {
    const history = JSON.parse(saved)
    const allGoods = await loadAllGoods()
    messages.value = history
      .filter(m => m.text)
      .map(m => ({
        ...m,
        goods: (m.goods || []).map(id => allGoods.find(g => g.id === id)).filter(Boolean),
        sources: m.sources || [],
        streaming: false
      }))
  } catch {
    messages.value = []
  }
}

function clearHistory() {
  messages.value = []
  localStorage.removeItem('chatHistory')
  addMessage('ai', '对话已清空，有什么可以帮你的吗？')
}

// mode 是后端的生成方式，不是质量分：api=大模型生成 / extractive=资料摘录
function modeLabel(mode) {
  if (mode === 'api') return 'AI 生成'
  if (mode === 'extractive') return '资料摘录'
  if (mode === 'rule') return '本地规则'
  return ''
}

// 分片 → 中文名，界面上比 shard 字段（rules/guides/faq）好懂
const SHARD_NAMES = {
  rules: '平台规则',
  guides: '操作指引',
  faq: '高频问答',
  campus: '校园场景',
  reference: '商品参考'
}
const shardName = s => SHARD_NAMES[s] || s

const confidencePct = computed(() => c => `${Math.round((c || 0) * 100)}%`)

onMounted(async () => {
  if (localStorage.getItem('chatHistory')) {
    await loadHistory()
  } else {
    addMessage('ai', '你好，我是校园小助手 🎓 想找什么宝贝？也可以问我平台规则，比如"怎么退款"，我会附上依据来源。')
  }
})
</script>

<template>
  <div class="chat-panel">
    <div class="panel-header">
      <img class="bot-avatar" src="/images/机器人.png" alt="客服">
      <div>
        <div class="bot-name">校园小助手</div>
        <div class="bot-status">
          在线 · 检索增强问答（RAG）
          <span class="stream-badge">SSE 流式</span>
        </div>
      </div>
      <div class="header-actions">
        <el-button size="small" text class="clear-btn" @click="clearHistory">清空</el-button>
        <span class="close-btn" title="关闭" @click="emit('close')">×</span>
      </div>
    </div>

    <div class="chat-body">
      <div v-for="(msg, idx) in messages" :key="idx" class="message" :class="msg.type">
        <img v-if="msg.type === 'ai'" class="msg-avatar" src="/images/机器人.png" alt="AI">
        <div class="bubble-wrap">
          <div class="message-content" :class="{ 'is-refused': msg.refused }">
            <span class="msg-text">{{ msg.text }}</span>
            <span v-if="msg.streaming" class="caret"></span>
            <span v-if="msg.type === 'ai' && modeLabel(msg.mode)" class="mode-tag">{{ modeLabel(msg.mode) }}</span>
            <span v-if="msg.refused" class="refuse-tag">未找到依据</span>
          </div>

          <!-- 依据来源：知识库条目，带分片与置信度 -->
          <div v-if="faqSources(msg.sources).length" class="source-box">
            <div class="source-header">
              依据来源
              <span v-if="msg.confidence" class="conf">置信度 {{ confidencePct(msg.confidence) }}</span>
            </div>
            <div v-for="(s, si) in faqSources(msg.sources)" :key="si" class="source-item">
              <div class="source-title">
                <span class="shard-chip">{{ shardName(s.shard) }}</span>
                {{ s.title }}
                <span v-if="s.score" class="score">{{ Math.round((s.score || 0) * 100) }}%</span>
              </div>
              <div class="source-snippet">{{ s.snippet }}</div>
              <div class="source-meta">
                <span>ID {{ s.id }}</span>
                <span v-if="s.source">{{ s.source }}</span>
                <span v-if="s.updated">{{ s.updated }}</span>
              </div>
            </div>
          </div>

          <!-- 风险提示：转账/微信/面交/退款/定金场景自动追加 -->
          <div v-if="msg.safetyTip" class="safety-tip">🛡 {{ msg.safetyTip }}</div>

          <!-- 模糊引导：给可点击的追问建议。
               ⚠️ 后端已把 hint 拼进 answer 末尾（与非流式接口保持一致），所以正文里
               已经有一份；这里只在"流式中途就已渲染完整"时才有意义 —— 实际做法是
               检测正文是否已含 hint，避免同一个提示显示两遍（实测确实重复了）。 -->
          <div v-if="msg.hint && !msg.text.includes(msg.hint)" class="hint-box">💡 {{ msg.hint }}</div>

          <div v-if="msg.goods && msg.goods.length" class="goods-cards">
            <div v-for="g in msg.goods" :key="g.id" class="goods-card" @click="viewGoods(g)">
              <img :src="getImageUrl(g.images?.[0]?.imageUrl)" alt="宝贝">
              <div class="goods-card-title">{{ g.title }}</div>
              <div class="goods-card-price">¥{{ g.price }}</div>
              <div class="goods-card-btn">去看看</div>
            </div>
          </div>
        </div>
        <img v-if="msg.type === 'user'" class="msg-avatar user-avatar" src="/images/3.png" alt="我">
      </div>

      <div v-if="typing && !streaming" class="typing">
        <span></span><span></span><span></span>
      </div>
    </div>

    <div class="quick-row">
      <span v-for="q in quickAsks" :key="q" class="quick-chip" @click="sendQuick(q)">{{ q }}</span>
    </div>

    <div class="chat-input">
      <el-input
        v-model="input"
        placeholder="输入你的问题或想要的宝贝…"
        @keyup.enter="sendMessage"
      />
      <el-button v-if="streaming" type="danger" @click="stopStreaming">停止</el-button>
      <el-button v-else type="primary" @click="sendMessage">发送</el-button>
    </div>
  </div>
</template>

<style scoped>
.chat-panel {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #fff;
}
.panel-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  border-bottom: 1px solid #f0f0f0;
  background: linear-gradient(135deg, #fff8e0, #ffffff);
}
.bot-avatar { width: 38px; height: 38px; border-radius: 50%; object-fit: cover; }
.bot-name { font-weight: bold; font-size: 15px; }
.bot-status { font-size: 12px; color: #999; display: flex; align-items: center; gap: 6px; }
.stream-badge {
  font-size: 10px;
  color: #2e7d32;
  background: #e8f5e9;
  border-radius: 8px;
  padding: 1px 6px;
}
.header-actions { margin-left: auto; display: flex; align-items: center; gap: 4px; }
.close-btn {
  width: 26px;
  height: 26px;
  line-height: 24px;
  text-align: center;
  font-size: 20px;
  color: #999;
  border-radius: 50%;
  cursor: pointer;
  user-select: none;
}
.close-btn:hover { background: #f2f2f2; color: #333; }
.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  background: #f7f8fa;
}
.message { display: flex; gap: 10px; margin-bottom: 16px; }
.message.user { flex-direction: row-reverse; }
.msg-avatar { width: 32px; height: 32px; border-radius: 50%; object-fit: cover; flex-shrink: 0; }
.bubble-wrap { max-width: 78%; }
.message-content {
  padding: 10px 14px;
  border-radius: 12px;
  background: #fff;
  border: 1px solid #e8e8e8;
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
}
/* 拒答态：灰底 + 左边框，和正常回答视觉上区分开 */
.message-content.is-refused {
  background: #fafafa;
  border-left: 3px solid #bbb;
  color: #555;
}
.msg-text { white-space: pre-wrap; }
/* 流式光标 */
.caret {
  display: inline-block;
  width: 2px;
  height: 14px;
  background: #ffd21e;
  vertical-align: text-bottom;
  margin-left: 2px;
  animation: blink 0.9s steps(2, start) infinite;
}
@keyframes blink { 50% { opacity: 0; } }
.message.user .message-content { background: #ffd21e; color: #23201a; border: none; font-weight: 500; }
.mode-tag {
  display: inline-block;
  margin-left: 6px;
  padding: 1px 6px;
  font-size: 11px;
  color: #8a6100;
  background: #fff3c2;
  border-radius: 8px;
  vertical-align: middle;
}
.refuse-tag {
  display: inline-block;
  margin-left: 6px;
  padding: 1px 6px;
  font-size: 11px;
  color: #666;
  background: #eee;
  border-radius: 8px;
  vertical-align: middle;
}
.source-box {
  margin-top: 8px;
  padding: 8px 10px;
  background: #fbfbfb;
  border: 1px dashed #e0e0e0;
  border-radius: 10px;
}
.source-header {
  font-size: 11px;
  color: #999;
  margin-bottom: 6px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.source-header .conf { color: #2e7d32; }
.source-item + .source-item { margin-top: 8px; padding-top: 8px; border-top: 1px solid #f0f0f0; }
.source-title {
  font-size: 12px;
  font-weight: 600;
  color: #444;
  margin-bottom: 3px;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.shard-chip {
  font-size: 10px;
  font-weight: normal;
  color: #8a6100;
  background: #fff3c2;
  border-radius: 6px;
  padding: 1px 5px;
}
.source-title .score {
  font-size: 10px;
  font-weight: normal;
  color: #999;
  background: #f2f2f2;
  border-radius: 6px;
  padding: 1px 5px;
}
.source-snippet {
  font-size: 12px;
  color: #777;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.source-meta {
  margin-top: 4px;
  font-size: 10px;
  color: #bbb;
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
.safety-tip {
  margin-top: 8px;
  padding: 8px 10px;
  font-size: 12px;
  line-height: 1.6;
  color: #8a5a00;
  background: #fff8e1;
  border: 1px solid #ffe9a8;
  border-radius: 10px;
}
.hint-box {
  margin-top: 8px;
  padding: 8px 10px;
  font-size: 12px;
  line-height: 1.6;
  color: #555;
  background: #f6f8ff;
  border: 1px solid #dde5ff;
  border-radius: 10px;
}
.goods-cards { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 10px; }
.goods-card {
  background: #fff;
  border: 1px solid #e8e8e8;
  border-radius: 10px;
  padding: 8px;
  width: 140px;
  cursor: pointer;
  transition: all 0.3s;
}
.goods-card:hover { border-color: #ffd21e; box-shadow: 0 2px 10px rgba(255, 210, 30, 0.35); }
.goods-card > img { width: 100%; height: 96px; object-fit: cover; border-radius: 6px; margin-bottom: 6px; }
.goods-card-title {
  font-size: 13px;
  font-weight: bold;
  margin-bottom: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.goods-card-price { color: var(--price-color); font-size: 15px; font-weight: bold; }
.goods-card-btn {
  text-align: center;
  background: #ffd21e;
  color: #23201a;
  border-radius: 14px;
  padding: 5px 0;
  margin-top: 6px;
  font-size: 12px;
  font-weight: 600;
}
.typing {
  display: inline-flex;
  gap: 4px;
  padding: 12px 16px;
  background: #fff;
  border: 1px solid #e8e8e8;
  border-radius: 12px;
}
.typing span {
  width: 8px;
  height: 8px;
  background: #999;
  border-radius: 50%;
  animation: typing 1.4s infinite;
}
.typing span:nth-child(2) { animation-delay: 0.2s; }
.typing span:nth-child(3) { animation-delay: 0.4s; }
@keyframes typing {
  0%, 60%, 100% { transform: translateY(0); opacity: 0.5; }
  30% { transform: translateY(-4px); opacity: 1; }
}
.quick-row {
  display: flex;
  gap: 8px;
  padding: 10px 16px 0;
  flex-wrap: wrap;
}
.quick-chip {
  font-size: 12px;
  color: #8a6100;
  background: #fff3c2;
  border-radius: 12px;
  padding: 4px 12px;
  cursor: pointer;
}
.quick-chip:hover { background: #ffe89a; }
.chat-input {
  display: flex;
  gap: 10px;
  padding: 12px 16px 14px;
}
</style>