<script setup>
import { ref, onMounted, nextTick } from 'vue'
import request from '../api'

const emit = defineEmits(['go-home', 'close'])

const messages = ref([])   // { type: 'ai'|'user', text, goods: [...] }
const input = ref('')
const typing = ref(false)

// 快捷提问（自定义元素：常见问题一键发送）
const quickAsks = ['怎么下单', '能退款吗', '怎么发布闲置']

const fallbackRules = [
  { keys: ['你好', '您好', 'hi', 'hello', '嗨'], reply: '你好呀！我是校园小助手，想买点什么？可以告诉我商品名称或类别，比如"买台灯"、"想要一本书"。' },
  { keys: ['怎么买', '如何购买', '购买流程', '下单'], reply: '购买流程很简单：在首页找到心仪的宝贝，点击"我想要"，选择或填写收货地址后确认付款即可。付款后订单会出现在"我的订单"里，等待卖家发货。' },
  { keys: ['发货', '多久到', '物流', '快递'], reply: '卖家确认发货后，订单状态会变为"已发货"。同校交易一般当面交接或宿舍楼下自取，很快就能收到哦。' },
  { keys: ['退款', '退货'], reply: '在"我的订单"中，待发货状态的订单可以申请退款，退款后宝贝会自动重新上架。' },
  { keys: ['发布', '卖东西', '上架', '闲置'], reply: '登录后点击"发布商品"，填写标题、分类、价格和描述，上传宝贝图片即可发布。发布后可在"个人中心"管理自己的宝贝。' },
  { keys: ['谢谢', '感谢'], reply: '不客气！祝你淘到心仪的好物～' }
]

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

function addMessage(type, text, goods = []) {
  messages.value.push({ type, text, goods })
  saveHistory()
  scrollToBottom()
}

async function loadAllGoods() {
  try {
    const { data } = await request.get('/goods')
    return data
  } catch {
    return []
  }
}

async function sendMessage() {
  const text = input.value.trim()
  if (!text) return
  input.value = ''
  addMessage('user', text)

  typing.value = true
  scrollToBottom()

  // 优先调用后端推荐接口
  let reply = null
  let recommendGoods = []
  try {
    const { data } = await request.post('/recommend', { keyword: text })
    if (data.success && data.data && data.data.length > 0) {
      const allGoods = await loadAllGoods()
      recommendGoods = data.data
        .map(item => allGoods.find(g => g.id === item.id))
        .filter(Boolean)
      reply = '为你找到了以下宝贝，点击查看详情～'
    }
  } catch (e) {
    // 推荐接口失败时走本地规则
  }

  if (!reply) {
    const lower = text.toLowerCase()
    const rule = fallbackRules.find(r => r.keys.some(k => lower.includes(k)))
    reply = rule ? rule.reply : '暂时没有找到相关宝贝，可以换个关键词试试，比如"耳机"、"台灯"、"高数书"。'
  }

  // 模拟思考时间，提升对话体验
  await new Promise(resolve => setTimeout(resolve, 500))
  typing.value = false
  addMessage('ai', reply, recommendGoods)
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
    .map(m => ({ type: m.type, text: m.text, goods: m.goods.map(g => g.id) }))
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
        type: m.type,
        text: m.text,
        goods: (m.goods || []).map(id => allGoods.find(g => g.id === id)).filter(Boolean)
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

onMounted(() => {
  if (messages.value.length === 0) {
    addMessage('ai', '你好，我是校园小助手 🎓 想找什么宝贝？告诉我关键词，我帮你找！')
  } else {
    loadHistory()
  }
})
</script>

<template>
  <div class="chat-panel">
    <div class="panel-header">
      <img class="bot-avatar" src="/images/机器人.png" alt="客服">
      <div>
        <div class="bot-name">校园小助手</div>
        <div class="bot-status">在线 · 关键词帮你找宝贝</div>
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
          <div class="message-content">{{ msg.text }}</div>
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

      <div v-if="typing" class="typing">
        <span></span><span></span><span></span>
      </div>
    </div>

    <div class="quick-row">
      <span v-for="q in quickAsks" :key="q" class="quick-chip" @click="sendQuick(q)">{{ q }}</span>
    </div>

    <div class="chat-input">
      <el-input v-model="input" placeholder="输入你的问题或想要的宝贝…" @keyup.enter="sendMessage" />
      <el-button type="primary" @click="sendMessage">发送</el-button>
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
.bot-status { font-size: 12px; color: #999; }
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
.message.user .message-content { background: #ffd21e; color: #23201a; border: none; font-weight: 500; }
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
