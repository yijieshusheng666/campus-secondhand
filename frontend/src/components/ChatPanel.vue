<script setup>
import { ref, computed, onMounted, nextTick, watch } from 'vue'
import request from '../api'
import { avatarUrl } from '../avatars'
import { useCurrentUser } from '../composables/useCurrentUser'
import { getToken } from '../session'
import { clearPersonalContext, ensurePersonalContext, getPersonalContext, looksPersonal } from '../composables/usePersonalContext'

const currentUser = useCurrentUser()

const emit = defineEmits(['go-home', 'close'])

// 一条消息的完整形态：
// { type, text, goods, sources, mode, refused, confidence, hint, safetyTip, streaming }
const messages = ref([])
const input = ref('')
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


// -----------------------------------------------------------------------
// 「贴底跟随」：自动滚动只在用户本来就在底部时才生效
// -----------------------------------------------------------------------
// 问题：打字机每帧（60fps，长回答十几秒）都无条件 scrollTop = scrollHeight，
// 用户想往回看历史就被立刻拽回底部 —— 表现为「往上滑滑不动，要等一会儿」，
// 而且越到后面越难滑，因为帧率越高拽得越狠。
// 方案：滚之前先判断用户是否已经离开底部；离开了就停止跟随，
// 只在流式结束时（或用户点按钮）才把视角拉回来。
const STICK_THRESHOLD = 56   // 距底部小于这个像素就算「贴着底部」
const chatBodyRef = ref(null)
const stickToBottom = ref(true)      // 自动跟随开关
const showJumpBtn = computed(() => streaming.value && !stickToBottom.value)

function onBodyScroll() {
  const box = chatBodyRef.value
  if (!box) return
  stickToBottom.value =
    box.scrollHeight - box.scrollTop - box.clientHeight <= STICK_THRESHOLD
}

function getChatBody() {
  return chatBodyRef.value
}

// force=true 用于「用户主动发消息」：这时必须无条件跳到底，
// 哪怕他刚才在翻历史 —— 新问题当然要从最新位置开始看。
function scrollToBottom(force = false) {
  if (!force && !stickToBottom.value) return
  nextTick(() => {
    const box = getChatBody()
    if (!box) return
    box.scrollTop = box.scrollHeight
    stickToBottom.value = true
  })
}

// 来源卡片 / 安全提示是在 meta 事件时一次性插入的，会把正文顶出视口。
// delta 阶段的滚动发生在插入之前，所以 done 之后要再滚一次。
function scrollAfterRender() {
  if (!stickToBottom.value) return
  nextTick(() => {
    requestAnimationFrame(() => {
      const box = getChatBody()
      if (!box) return
      box.scrollTop = box.scrollHeight
      stickToBottom.value = true
    })
  })
}

// 流式期间每个 delta 都会请求滚到底。一条回答实测 104 个 delta，
// 也就是 104 次 nextTick + querySelector + 样式回写，跟主线程抢帧。
// 用 rAF 合并成「每帧最多一次」：视觉上一样（本来也看不见帧间跳变），但省掉九成开销。
let scrollRafPending = false
function scrollToBottomThrottled() {
  if (!stickToBottom.value) return   // 用户在翻历史，别抢方向盘
  if (scrollRafPending) return
  scrollRafPending = true
  requestAnimationFrame(() => {
    scrollRafPending = false
    if (!stickToBottom.value) return
    const box = getChatBody()
    if (!box) return
    box.scrollTop = box.scrollHeight
    stickToBottom.value = true
  })
}

// 「回到底部」按钮：点了才重新接管滚动
function jumpToLatest() {
  stickToBottom.value = true
  scrollToBottom(true)
}

// ---------------------------------------------------------------------------
// 打字机缓冲层：SSE 之上的一层「匀速出字」队列
// ---------------------------------------------------------------------------
// 为什么需要它：GLM-4-flash 的实测时序是 2.9s 先吐 meta、3.0s 把全文一次性推完。
// 也就是说后端虽然逐块 flush，浏览器几乎在同一瞬间就拿到了全部内容，
// 直接贴到 DOM 的观感就是「整段闪现」——SSE 协议正确 != 用户看得到流式。
//
// 所以 delta 先入队，再由 requestAnimationFrame 按固定节奏吐字：
// 队列空就停下等上游，队列有货就匀速放。这才是「屏幕上字在浮现」的观感。
//
// 速率必须用时间累加器控制，这点很关键：
//   写成 Math.max(1, Math.round(rate * dt)) 是错的 —— rAF 每秒 60 帧，
//   rate=10 时 rate*dt 只有 0.16，四舍五入为 0，再被 max(1,…) 兜成 1，
//   于是实际速度变成「每帧必吐 1 字」= 60 字/秒，跟 rate 毫无关系，调速永远没效果。
//   累加器把秒数存起来、只取整数部分，平均速度才真正等于 rate。
const CHAR_RATE = 34       // 恒定出字速度（字/秒）
const charRate = CHAR_RATE

// 版本探针：模块每次被加载都会打这一行，用来确认浏览器跑的是不是最新代码。
// 只在 dev 输出：生产包里留一行 console.log 没有价值，还会被误当成调试残留。
if (import.meta.env.DEV) {
  console.log('[ChatPanel] build=tps-34cps loaded at', new Date().toLocaleTimeString())
}

let currentTw = null       // 同时只有一路流，用模块级变量，避免塞进响应式对象

function createTypewriter(target) {
  let queue = ''
  let raf = null
  let lastTs = 0
  let acc = 0              // 决定本帧吐几个字
  let settleTimer = null
  // 流是否已彻底结束。AI 挂掉时可能一个 delta 都没有，那种情况下收尾定时器
  // 没机会被安装（见 pump 注释），只能由调用方通过 endOfStream() 显式告知。
  let ended = false
  let resolveDone
  const done = new Promise(r => { resolveDone = r })

  function pump() {
    const now = performance.now()
    if (lastTs === 0) lastTs = now
    // dt 上限 120ms：切标签页回来时 dt 巨大，否则会一口气把队列喷完
    const dt = Math.min(now - lastTs, 120) / 1000
    lastTs = now

    if (queue) {
      acc += charRate * dt
      const n = Math.floor(acc)
      if (n > 0) {
        acc -= n
        if (n >= queue.length) {
          target.text += queue
          queue = ''
        } else {
          target.text += queue.slice(0, n)
          queue = queue.slice(n)
        }
        scrollToBottomThrottled()
      }
    }

    if (queue) {
      raf = requestAnimationFrame(pump)
    } else {
      raf = null
      lastTs = 0
      acc = 0
      if (ended) {
        // 调用方已声明流结束、且队列见底 → 立刻收尾
        finish()
      } else {
        // 队列见底不代表流结束：下一个 delta 可能 200ms 后才到。
        // 开一个短观察窗再判定收尾，避免「刚说完一句光标就闪一下消失」。
        settleTimer = setTimeout(finish, 160)
      }
    }
  }

  function schedule() {
    if (settleTimer) { clearTimeout(settleTimer); settleTimer = null }
    if (raf === null) raf = requestAnimationFrame(pump)
  }

  function finish() {
    if (settleTimer) { clearTimeout(settleTimer); settleTimer = null }
    if (raf !== null) { cancelAnimationFrame(raf); raf = null }
    resolveDone()
  }

  return {
    done,
    push(chunk) { if (chunk) { queue += chunk; schedule() } },
    pending() { return queue.length > 0 },
    // 流结束通知。sendMessage 必须在 await done 之前调用。
    //
    // ⚠️ 这里曾经只靠 pump() 装 settleTimer，而 pump() 只由 push() 触发：
    //   AI 挂掉 → Java 95ms 内返回 event:error → 前端零个 delta
    //   → push() 从没调用 → pump() 从没跑 → settleTimer 从没装上
    //   → done 永不 resolve → await 永久挂起 → finally 不执行
    //   → 骨架屏和「正在检索」无限转圈，发送按钮永远卡在「停止」。
    // 正常路径（至少有一个 delta）不会暴露，所以这个坑一直没被碰到。
    endOfStream() {
      ended = true
      if (settleTimer) { clearTimeout(settleTimer); settleTimer = null }
      // 队列空 → 直接收尾；非空 → pump 下一帧吐完后走 ended 分支
      if (!queue && raf === null) finish()
    },
    // 用户点「停止」：已收到的字要留下，剩下的不再吐
    flush() {
      if (queue) { target.text += queue; queue = '' }
      scrollToBottomThrottled()
      finish()
    },
    // 走错误/回退分支：丢弃未渲染的半截，避免和兜底文案混在一起
    clear() { queue = ''; finish() }
  }
}

// 14 字/秒下一段长回答要放十几秒，给个逃生舱：生成中点一下气泡立刻显示全部
function skipToEnd() {
  if (currentTw) currentTw.flush()
}

// 用户头像：优先用户自己选的那张，没选过就退回内置剪影。
// 兜底必须是真实存在的文件。之前这里写死 '/images/3.png'，
// 但 public/images 下只有 logo.png 和 avatars/ 目录，3.png 从来不存在，
// 于是「我」的气泡旁边全是裂图，看上去就是用户头像不见了。
const DEFAULT_AVATAR = '/images/avatars/avatar-1.png'
const myAvatar = computed(() => avatarUrl(currentUser.value) || DEFAULT_AVATAR)

// 只降级一次：换成 data URI 之后它再失败也不会触发 error 事件（现代浏览器行为），
// 也就不会出现「error → 换 src → 再 error」的死循环。
const FALLBACK_PIXEL = 'data:image/svg+xml;base64,' + btoa(
  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32"><circle cx="16" cy="16" r="16" fill="#dfe6ef"/>' +
  '<text x="16" y="22" font-size="15" text-anchor="middle" fill="#8892a4">?</text></svg>'
)
function onAvatarError(e) {
  if (e.target.src !== FALLBACK_PIXEL) e.target.src = FALLBACK_PIXEL
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
    // 必须是 extra.streaming：sendMessage 用 addMessage('ai', '', { streaming: true })
    // 占位后再往里填流式内容。这里原来硬编码 false，把传进来的 true 丢了，
    // 于是模板里骨架屏/跳动点/头像呼吸（条件都是 msg.streaming）永远不成立，
    // 表现就是「发问后只有一个空白气泡，什么动画都没有」。
    streaming: extra.streaming ?? false,
  }
  messages.value.push(msg)
  saveHistory()
  scrollToBottom()
  // 关键：必须返回「数组里那个响应式代理」，而不是刚 push 进去的裸对象。
  //
  // Vue 3 的 ref([]) 是深层响应式，但只有经由 messages.value[i] 访问才拿到 proxy；
  // 拿裸对象去写 target.text 会绕过 proxy 的 set 拦截器，dep 不被 trigger，
  // 于是所有 delta 全写进内存、界面一动不动，直到 finally 里 streaming.value = false
  // 触发一次整体重渲染，DOM 才被一次性 patch 成完整文本 —— 表现就是「流式变成整段闪现」。
  return messages.value[messages.value.length - 1]
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
  // 这条走裸 fetch 而不是 axios 实例，所以要自己带头。
  // 该端点对匿名访客开放（客服悬浮按钮人人可见），带头是为了登录用户也能被正确识别，
  // 免得以后把它收紧成需登录时又得回来补一刀。
  const headers = { 'Content-Type': 'application/json' }
  const authToken = getToken()
  if (authToken) headers.Authorization = `Bearer ${authToken}`

  const resp = await fetch('/api/ai/chat/stream', {
    method: 'POST',
    headers,
    body: JSON.stringify({
      question: text,
      history: buildHistory(),
      // 个人数据上下文：命中个人问法就先等它就绪，否则直接用缓存里的（可能是空串）。
      // 非个人问题不触发拉取，避免每条闲聊都打四个接口。
      personalContext: looksPersonal(text) ? await ensurePersonalContext() : getPersonalContext()
    }),
    signal: streamAbort.signal
  })

  if (!resp.ok || !resp.body) {
    const err = new Error(`HTTP ${resp.status}`)
    err.fallbackReason = 'http'
    throw err
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
      const lines = block.split('\n')
      const dataLine = lines.find(l => l.startsWith('data:'))
      if (!dataLine) continue
      let payload
      try {
        payload = JSON.parse(dataLine.slice(5).trim())
      } catch {
        continue                       // 半截 JSON，丢弃（下一块会补全）
      }
      // SSE 规范把事件类型放在独立的 `event:` 行上。Java 降级时发的是
      // `event: error` + 一个**不含 event 字段**的 JSON，而 handleEvent
      // 按 payload.event 分派 —— 不补这一刀，「AI 服务不可用」会被静默丢弃。
      if (!payload.event) {
        const eventLine = lines.find(l => l.startsWith('event:'))
        if (eventLine) payload.event = eventLine.slice(6).trim()
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
    // 交给打字机匀速吐出，而不是直接贴上去（见 createTypewriter 的说明）
    if (currentTw) currentTw.push(payload.text || '')
  } else if (kind === 'done') {
    target.mode = payload.mode || ''
    // 兜底：万一 delta 全丢了，用 done.answer 补上
    if (!target.text && !(currentTw && currentTw.pending()) && payload.answer) target.text = payload.answer
    // 正文长度在这之后还会变（补齐 / 兜底文案），必须再滚一次
    scrollAfterRender()
  } else if (kind === 'error') {
    // 先清空已渲染的增量：模型可能已经吐了半截又断流，那些字收不回去，
    // 只能整段替换（target.text 是 += 追加，不清就会粘在兜底文案后面）。
    if (currentTw) currentTw.clear()
    // 后端在「模型流到一半中断」时会带一份替代正文（抽取式答案），
    // 直接用它；整体不可用（Java 侧合成的 error）则没有 text，走原兜底文案。
    target.text = payload.text || 'AI 服务暂时不可用，我先用关键词帮你找找看。'
    target.mode = payload.text ? 'extractive' : 'unavailable'
  }
}

async function sendMessage() {
  const text = input.value.trim()
  if (!text || streaming.value) return
  input.value = ''
  addMessage('user', text)

  // 新问题一律从最新位置开始看：即使用户刚才在翻历史，这里也要无条件跟到底
  scrollToBottom(true)

  // 先占位一条 AI 消息，流式往里填
  const target = addMessage('ai', '', { streaming: true })
  streamAbort = new AbortController()
  streaming.value = true
  currentTw = createTypewriter(target)

  try {
    await askStream(text, target)
    aiOnline.value = true
    // 先通知「流已结束」再等：即使全程零个 delta（AI 挂掉），
    // endOfStream() 也会直接 resolve done，不会永久卡在这里
    if (currentTw) currentTw.endOfStream()
    // 等打字机把队列里剩下的字吐完再熄灯，否则最后半句会凭空消失
    if (currentTw) await currentTw.done
    target.streaming = false

    // 流通了但一个字都没拿到（比如 AI 服务挂了只发了 error）→ 走兜底
    if (target.mode === 'unavailable' || !target.text) {
      target.text = ''
      target.sources = []
      aiOnline.value = false
      await fallbackLayers(target, text, target.mode === 'unavailable' ? 'unavailable' : 'empty')
    }
  } catch (e) {
    // fetch 本身失败 / 用户主动 abort
    if (e.name === 'AbortError') {
      // 用户主动停：把队列里已收到但还没显示的字补上
      if (currentTw) currentTw.flush()
      if (!target.text) target.text = '（已停止生成）'
    } else {
      if (currentTw) currentTw.clear()
      target.text = ''
      target.sources = []
      // 真实原因传下去：网络断了和非 200 是两回事，不该一律说「服务没启动」
      aiOnline.value = false
      await fallbackLayers(target, text, e.fallbackReason || 'network')
    }
    target.streaming = false
  } finally {
    if (currentTw) { currentTw.flush(); currentTw = null }
    streaming.value = false
    streamAbort = null
    saveHistory()
    scrollToBottom()
  }
}

// 第 2、3 层兜底：关键词推荐 → 本地规则
async function fallbackLayers(target, text, reason = 'empty') {
  // 走到这里只有两种可能：AI 服务不可用，或者流式一个字符都没返回。
  // 无论哪种，答案都不是「AI 检索增强生成」的产物 —— 标签必须说实话，
  // 否则用户看到「本地规则」会以为 AI 整个没在工作。
  try {
    const recommendGoods = await askRecommend(text)
    if (recommendGoods) {
      target.goods = recommendGoods
      target.text = '我用关键词帮你找到了这些宝贝，点击查看详情～'
      target.mode = OFFLINE_MODE
      target.hint = offlineHint(reason)
      return
    }
  } catch {
    // AI 搜索也不可用，继续走本地规则
  }
  target.text = askLocalRule(text)
  target.mode = OFFLINE_MODE
  target.hint = offlineHint(reason)
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

// 聊天记录按用户隔离：原来所有账号共用 'chatHistory' 一个键，同一台电脑换人登录后，
// 上一位用户问过的订单金额、地址相关问答会直接显示在客服面板里。
const HISTORY_PREFIX = 'chatHistory'

function historyKey() {
  const id = currentUser.value?.id
  return id ? `${HISTORY_PREFIX}:${id}` : `${HISTORY_PREFIX}:anon`
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
  localStorage.setItem(historyKey(), JSON.stringify(history.slice(-50)))
}

async function loadHistory() {
  const saved = localStorage.getItem(historyKey())
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
  localStorage.removeItem(historyKey())
  addMessage('ai', '对话已清空，有什么可以帮你的吗？')
}

// mode 是后端的生成方式，不是质量分：api=大模型生成 / extractive=资料摘录
// 离线兜底的标识与文案。
// 单独一个 mode（而不是复用 unavailable / rule）是有意的：
//   - 'rule' 是"本地规则库本来就有这条答案"，和 AI 通不通无关；
//   - 'unavailable' 会被下面的分支覆盖掉，信息就丢了。
// 混用两者的直接后果：AI 挂掉时界面显示「本地规则」，
// 用户以为 AI 没在工作，实际只是没连上。
const OFFLINE_MODE = 'offline'
/**
 * 按真实原因生成提示文案。
 *
 * 之前这里是写死的一句「AI 助手当前未接入」—— 那是在**猜**。
 * 实际进兜底有四条路径（网络失败 / 非 200 / 流返回空 / 显式 unavailable），
 * 原因不同该提示的内容也不同。现在如实告知，不再一律往「服务没启动」上归。
 */
function offlineHint(reason) {
  // 必须带时间戳。这条提示会被写进 localStorage 永久保存 ——
  // 几小时前 AI 停机时产生的提示，今天再看和刚生成的一模一样，
  // 用户根本分不清这是"刚才就这样"还是"历史遗留"，白白排查半天。
  const at = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  const tail = `（${at} 生成，AI 助手当时未接入）`
  switch (reason) {
    case 'http':
      return '这次请求没拿到正常响应（后端异常或 AI 服务未启动）。回答来自本地兜底规则。' + tail
    case 'network':
      return '这次请求没能连上后端。回答来自本地兜底规则，请确认 Spring Boot 与 ai-service 都在运行。' + tail
    case 'unavailable':
      return 'AI 服务当时不可用。回答来自本地兜底规则，启动 ai-service 后可以问到更完整的回答。' + tail
    default:
      return '这次没有得到 AI 的回答。回答来自本地兜底规则。' + tail
  }
}

function modeLabel(mode) {
  if (mode === OFFLINE_MODE) return '离线兜底'
  if (mode === 'personal') return '基于我的数据'
  if (mode === 'api') return 'AI 生成'
  if (mode === 'extractive') return '资料摘录'
  if (mode === 'rule') return '本地规则'
  return ''
}

// 分片 → 中文名，界面上比 shard 字段（rules/guides/faq）好懂
// AI 助手在线状态。管理端能连上不代表聊天能连上（两跳路径不同：
// ops 走普通 RestTemplate，流式走 streamRestTemplate 且 readTimeout=ZERO）。
// 所以聊天面板自己探一次，比让人对着历史消息猜靠谱。
const aiOnline = ref(true)
// Java 侧捕获到的真实异常（见 AiClient.streamChat 的 reason 字段）。
// 有了它，「连不上 AI」「AI 返回 500」「响应格式不对」在界面上就是三件不同的事。
const aiReason = ref('')

async function probeAi() {
  try {
    // 用一句必定被拒答的短问：只验证「Java→AI 的流式这一跳通不通」。
    // 拒答走抽取式分支、不调大模型，探活几乎零成本。
    const r = await fetch('/api/ai/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question: '个啌品' })
    })
    const t = await r.text()
    const m = t.match(/"reason"\s*:\s*"([^"]*)"/)
    aiReason.value = m ? m[1] : ''
    aiOnline.value = !t.includes('event: error')
  } catch (e) {
    aiReason.value = e && e.message ? String(e.message) : '网络请求失败'
    aiOnline.value = false
  }
}

onMounted(async () => {
  probeAi()
  // 预热个人数据上下文。聊天面板是常驻挂载的，用户从别处切进来时上下文多半还是冷的，
  // 这里 fire-and-forget 先拉一次；第一条个人问题就能直接命中缓存，不再等网络。
  // 未登录时 composable 直接返回空串，这里不用判断。
  ensurePersonalContext().catch(() => {})
  if (localStorage.getItem(historyKey())) {
    await loadHistory()
  } else {
    addMessage('ai', '你好，我是校园小助手 🎓 想找什么宝贝？也可以问我平台规则，比如"怎么退款"，我会附上依据来源。')
  }
})

// 切换账号（登录 / 退出 / 令牌过期被踢）时清空会话并重载：
// 面板是常驻挂载（v-show）的，内存里的 messages 不会自己复位，
// 不清就会把上一位用户的提问、以及带着他数据的个人化上下文继续用下去。
watch(() => currentUser.value?.id, async () => {
  clearPersonalContext()
  messages.value = []
  if (localStorage.getItem(historyKey())) {
    await loadHistory()
  } else {
    addMessage('ai', '你好，我是校园小助手 🎓 想找什么宝贝？也可以问我平台规则，比如"怎么退款"，我会附上依据来源。')
  }
})
</script>

<template>
  <div class="chat-panel">
    <div class="panel-header">
      <img class="bot-avatar" src="/images/icons/bot.svg" alt="客服">
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

    <!-- 只在离线时出现的一条极细状态条。历史消息里的离线提示会长期留在
     localStorage，光看那些没法判断「现在还能不能用」，所以给一个实时信号。 -->
    <div v-if="!aiOnline" class="ai-offline-bar">
      <span>AI 服务离线，问答走本地兜底</span>
      <el-tooltip v-if="aiReason" :content="aiReason" placement="bottom">
        <span class="ai-offline-reason">{{ aiReason }}</span>
      </el-tooltip>
      <el-button text size="small" @click="probeAi">重新检测</el-button>
    </div>

    <div ref="chatBodyRef" class="chat-body" @scroll.passive="onBodyScroll">
      <div v-for="(msg, idx) in messages" :key="idx" class="message" :class="msg.type">
        <img
          v-if="msg.type === 'ai'"
          class="msg-avatar"
          :class="{ 'is-thinking': msg.streaming && !msg.text }"
          src="/images/icons/bot.svg"
          alt="AI"
        >
        <div class="bubble-wrap">
          <div
            class="message-content"
            :class="{ 'is-refused': msg.refused, 'is-skippable': msg.streaming && msg.text }"
            :title="msg.streaming && msg.text ? '点击立即显示全部内容' : ''"
            @click="skipToEnd"
          >
            <!-- 等首个 delta 的「思考中」气泡。
                 为什么放气泡里而不是面板底部另起一行：首个 delta 要等 2~3 秒，
                 这段时间界面完全静止会被当成卡死；配合骨架行预留高度，
                 首字到达时下方内容不会被顶走（无布局跳动）。 -->
            <div v-if="msg.streaming && !msg.text" class="thinking-bubble">
              <div class="thinking-dots" aria-hidden="true"><span></span><span></span><span></span></div>
              <span class="thinking-text">正在检索知识库并生成回答</span>
              <div class="skeleton-lines" aria-hidden="true"><i></i><i></i><i></i></div>
            </div>

            <span v-else class="msg-text">{{ msg.text }}</span>
            <span v-if="msg.streaming && msg.text" class="caret"></span>
            <span v-if="msg.type === 'ai' && modeLabel(msg.mode)" class="mode-tag">{{ modeLabel(msg.mode) }}</span>
            <span v-if="msg.refused" class="refuse-tag">未找到依据</span>
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
              <img :src="g.images?.[0]?.imageUrl" alt="宝贝">
              <div class="goods-card-title">{{ g.title }}</div>
              <div class="goods-card-price">¥{{ g.price }}</div>
              <div class="goods-card-btn">去看看</div>
            </div>
          </div>
        </div>
        <!-- @error 兜底：头像 URL 万一失效（历史脏数据、后端换成自定义图），
             自动降级，绝不给用户看裂图。 -->
        <img
          v-if="msg.type === 'user'"
          class="msg-avatar user-avatar"
          :src="myAvatar"
          alt="我"
          @error="onAvatarError"
        >
      </div>

    </div>

    <!-- 生成过程中用户往上翻历史时出现；点了才把视角拉回最新 -->
    <button v-if="showJumpBtn" class="jump-latest" @click="jumpToLatest">
      <span>回到底部</span><i>↓</i>
    </button>

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
.ai-offline-reason {
  max-width: 320px; overflow: hidden; text-overflow: ellipsis;
  white-space: nowrap; opacity: .75; cursor: help;
}

.ai-offline-bar {
  display: flex; align-items: center; gap: 6px;
  padding: 5px 16px;
  background: #fff4d6; border-bottom: 1px solid #ffe4a3;
  font-size: 12px; color: #8a6100;
}

.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  background: #f7f8fa;
}
/* 「回到底部」浮动按钮：贴在消息区右下角，不随内容滚动 */
.jump-latest {
  align-self: center;
  margin: -6px 16px 4px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 12px;
  border: none;
  border-radius: 14px;
  background: #23201a;
  color: #ffd21e;
  font-size: 12px;
  cursor: pointer;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.18);
}
.jump-latest i { font-style: normal; animation: jump-hint 1.2s ease-in-out infinite; }
.jump-latest:hover { background: #35302a; }
@keyframes jump-hint {
  0%, 100% { transform: translateY(0); }
  50%      { transform: translateY(2px); }
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
/* 生成中可点击「跳过」：手型 + 极淡悬停，不破坏气泡观感 */
.message-content.is-skippable { cursor: pointer; }
.message-content.is-skippable:hover { background: rgba(0, 0, 0, 0.012); }

/* ---------- 思考中：跳动点 + 骨架 + 头像呼吸 ---------- */
.thinking-bubble { padding: 2px 0; }
.thinking-dots { display: inline-flex; gap: 4px; vertical-align: middle; }
.thinking-dots span {
  width: 6px; height: 6px; border-radius: 50%;
  background: #f0b429;
  animation: dot-bounce 1.2s ease-in-out infinite;
}
.thinking-dots span:nth-child(2) { animation-delay: .15s; }
.thinking-dots span:nth-child(3) { animation-delay: .3s; }
@keyframes dot-bounce {
  0%, 60%, 100% { transform: translateY(0); opacity: .45; }
  30%           { transform: translateY(-5px); opacity: 1; }
}
.thinking-text {
  margin-left: 8px; font-size: 12px; color: #9aa4b8;
  animation: text-pulse 1.6s ease-in-out infinite;
}
@keyframes text-pulse { 0%,100% { opacity: .6; } 50% { opacity: 1; } }

.skeleton-lines { display: flex; flex-direction: column; gap: 7px; margin-top: 8px; }
.skeleton-lines i {
  height: 8px; border-radius: 4px;
  background: linear-gradient(90deg, #eef1f6 25%, #f7f9fc 50%, #eef1f6 75%);
  background-size: 200% 100%;
  animation: skeleton-shimmer 1.4s ease-in-out infinite;
}
.skeleton-lines i:nth-child(1) { width: 92%; }
.skeleton-lines i:nth-child(2) { width: 78%; }
.skeleton-lines i:nth-child(3) { width: 54%; }
@keyframes skeleton-shimmer {
  0%   { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}
.msg-avatar.is-thinking { animation: avatar-breathe 1.8s ease-in-out infinite; }
@keyframes avatar-breathe {
  0%,100% { transform: scale(1); opacity: .85; }
  50%     { transform: scale(1.08); opacity: 1; }
}
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