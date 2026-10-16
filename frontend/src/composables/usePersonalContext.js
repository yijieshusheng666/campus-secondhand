/**
 * 「我的站内数据」上下文 —— 让智能助手能回答"我的"类问题。
 *
 * <p>为什么需要它：助手原本只能查静态知识库（rules/guides/faq/campus/reference），
 * 而用户真正高频的问题——「我有几件闲置」「我的求购有人接了吗」「我卖出去几本」——
 * 答案根本不在知识库里，检索必然低于拒答阈值，最后只能回一句"没找到依据"。
 * 这是能力边界问题，不是提示词能补的。</p>
 *
 * <p>做法：前端把当前登录用户的站内数据压成一小段文本，随问题一起发给 AI 服务；
 * AI 服务识别到第一人称 + "我的"就走个人通路（prompts.is_personal_question），
 * 不检索知识库、不判拒答，只基于这段真实数据作答。</p>
 *
 * <p>为什么不放在 Java 端拼：这个项目没有服务端登录态（用户存在 localStorage），
 * AiController 的 chatStream 是把 body 原样透传给 AI 服务的，
 * 所以在前端拼可以做到零 Java 改动。</p>
 *
 * <p>缓存：站内数据变化很慢（发布/下单是低频动作），默认缓存 60 秒，
 * 避免每条消息都打三个接口。</p>
 */
import { ref } from 'vue'
import request from '../api'
import { useCurrentUser } from './useCurrentUser'

const TTL_MS = 60 * 1000
const MAX_ROWS = 6

const contextText = ref('')
const loading = ref(false)
let fetchedAt = 0
let inflight = null
/** 缓存属于哪个用户。只按 TTL 判断的话，换账号后 60 秒内会直接命中上一个人的缓存。 */
let cachedUid = null
/** 代数计数器：每次清空 +1，让所有在途响应作废。 */
let generation = 0

/** 各条数据最多列几行 —— 全量塞进提示词既贵又会让模型挑花眼 */
function take(list, n = MAX_ROWS) {
  return (list || []).slice(0, n)
}

function yuan(v) {
  const n = Number(v)
  return Number.isFinite(n) ? `\u00a5${n}` : '\u2014'
}

/** 各接口的返回体包裹方式不统一（有的直接数组，有的 {data:[]}），这里统一收口 */
function unwrap(res, key) {
  const body = res && res.data
  if (Array.isArray(body)) return body
  if (body && Array.isArray(body[key])) return body[key]
  if (body && body.data && Array.isArray(body.data)) return body.data
  if (body && Array.isArray(body.data)) return body.data
  return []
}

/**
 * 拉取并拼装个人数据文本块。并发安全：重复调用会复用同一个 Promise。
 * 任何一个接口失败都只影响自己那一段，不会让整体拿不到上下文。
 */
export function ensurePersonalContext({ force = false } = {}) {
  const user = useCurrentUser()
  if (!user.value) {
    contextText.value = ''
    return Promise.resolve('')
  }
  // 缓存必须同时绑定「仍是同一个人」：只按 TTL 判断的话，同一台电脑换账号登录后
  // 60 秒内会直接把上一位用户的闲置/订单/求购文本发给 AI 服务。
  if (!force && contextText.value && cachedUid === user.value.id
      && Date.now() - fetchedAt < TTL_MS) {
    return Promise.resolve(contextText.value)
  }
  if (inflight) return inflight

  const uid = user.value.id
  const myGen = ++generation
  loading.value = true
  inflight = Promise.allSettled([
    request.get('/goods'),
    request.get('/orders', { params: { userId: uid, role: 'seller' } }),
    request.get('/orders', { params: { userId: uid, role: 'buyer' } }),
    request.get('/wanted', { params: { userId: uid } })
  ]).then(([goodsRes, sellRes, buyRes, wantRes]) => {
    // 这期间退出登录或换了账号：这批数据属于上一个人，直接丢弃。
    // 少了这道判断，退出瞬间在途的请求落地后会把旧数据又写回缓存。
    if (myGen !== generation) return ''

    const goods = unwrap(goodsRes, 'goods').filter(g => g.userId == uid)
    const sold = unwrap(sellRes, 'orders')
    const bought = unwrap(buyRes, 'orders')
    const wanted = unwrap(wantRes, 'wanted')

    const lines = []
    lines.push(`\u7528\u6237\uff1a${user.value.username || ''}\uff08\u5b66\u53f7 ${user.value.stuid || '\u672a\u63d0\u4f9b'}\uff09`)

    lines.push('', `\u3010\u6211\u7684\u95f2\u7f6e\u3011\u5171 ${goods.length} \u4ef6`)
    if (goods.length) {
      for (const g of take(goods)) {
        lines.push(`- ${g.title} \u00b7 ${yuan(g.price)} \u00b7 ${g.category || '\u672a\u5206\u7c7b'} \u00b7 ${g.status || '\u5728\u552e'}`)
      }
      if (goods.length > MAX_ROWS) lines.push(`- \u5176\u4f59 ${goods.length - MAX_ROWS} \u4ef6\u7701\u7565`)
    } else {
      lines.push('- \u5c1a\u672a\u53d1\u5e03\u4efb\u4f55\u5546\u54c1')
    }

    lines.push('', `\u3010\u6211\u7684\u8ba2\u5355\u3011\u5356\u51fa ${sold.length} \u5355\uff0c\u4e70\u5165 ${bought.length} \u5355`)
    for (const o of [...take(sold, 3), ...take(bought, 3)]) {
      lines.push(`- ${o.goodsTitle || '\u5546\u54c1'} \u00b7 ${yuan(o.goodsPrice)} \u00b7 ${o.status || '\u672a\u77e5\u72b6\u6001'}`)
    }

    lines.push('', `\u3010\u6211\u7684\u6c42\u8d2d\u3011\u5171 ${wanted.length} \u6761`)
    if (wanted.length) {
      for (const w of take(wanted)) {
        lines.push(`- ${w.title} \u00b7 \u9884\u7b97\u4e0a\u9650 ${yuan(w.maxPrice)} \u00b7 ${w.status || '\u5f00\u653e\u4e2d'}`)
      }
    } else {
      lines.push('- \u6ca1\u6709\u53d1\u5e03\u8fc7\u6c42\u8d2d')
    }

    const text = lines.join('\n')
    // 三段全空就别把空壳塞进提示词，白白占 token 还可能诱导模型编造
    const hasAny = goods.length || sold.length || bought.length || wanted.length
    contextText.value = hasAny ? text : ''
    cachedUid = uid
    fetchedAt = Date.now()
    return contextText.value
  }).finally(() => {
    loading.value = false
    // 只在还是同一代时才清 inflight，否则会把后来那次请求的 Promise 抹掉
    if (myGen === generation) inflight = null
  })

  return inflight
}

/**
 * 前端侧的意图预判：只用来决定"要不要等这一次拉取"，不决定最终走哪条通路
 * （真正的判定在 AI 服务 prompts.is_personal_question，两边都留着是刻意的 ——
 * 前端少一次无谓的等待，后端仍然独立兜底，两边不会因为规则漂移而互相污染）。
 */
const FRONTEND_MARKERS = ['我的', '我卖', '我买', '我发布', '我求购', '我下过', '我在售', '我有几件', '我出了', '我有多少']

export function looksPersonal(question) {
  return FRONTEND_MARKERS.some(m => (question || '').includes(m))
}

/** 读取当前的个人数据文本（一个普通字符串）。
 *
 *  ⚠️ 这里必须返回 `.value` 而不是 ref 本身。
 *  返回 ref 的话，JSON.stringify 出来是 {"_rawValue":"","_value":""} 这种 Vue 内部结构，
 *  FastAPI 收到后直接 422（"Input should be a valid string"），
 *  Java 捕获异常后下发 event: error，前端就显示「离线兜底」。
 *  这个 bug 只在**非个人类问题**上出现（那条分支才调 getPersonalContext），
 *  而个人类问题走的是 ensurePersonalContext()，返回的是 Promise<string>，所以是对的 ——
 *  于是现象变成「大部分问题都失败、偶尔正常」，极难定位。
 */
export function getPersonalContext() {
  return contextText.value
}

/** 退出登录 / 切换账号时必须清空，否则会把上一个用户的私有数据发给 AI 服务 */
export function clearPersonalContext() {
  generation++      // 让所有在途响应作废：光清缓存挡不住已经发出去、正在返回的请求
  cachedUid = null
  contextText.value = ''
  fetchedAt = 0
  inflight = null
}

// 注：这里原本还有一个 usePersonalContext() 导出，全仓库无人调用（ChatPanel 直接
// import 具名函数），已经删掉——留着只会让人以为还有另一套用法。
