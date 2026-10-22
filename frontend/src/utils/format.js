/**
 * 展示层格式化工具。
 *
 * 为什么集中到一处：`formatTime` 在 Home / Messages / Orders / User / Disputes
 * 五个文件里各写了一份，`pct` 在 KbConsole / Overview 各写了一份。
 * 拆开看每份都只有三四行，但它们对精度的处理是**不一样的**
 * （订单要秒、首页评价要分、消息列表省年份），改的时候很容易只改一处，
 * 于是在不同页面上看到同一条数据格式不同。
 */

/** 后端返回的时间是 ISO 字符串（2026-10-13T18:40:00），这里按展示场景裁长度。 */
export const TIME_PRECISION = {
  /** 2026-10-13 18:40:00 — 订单、纠纷这类要精确到秒的 */
  second: 'second',
  /** 2026-10-13 18:40 — 列表/评价这类到分钟就够的 */
  minute: 'minute',
  /** 10-13 18:40 — 消息列表省掉年份，省横向空间 */
  short: 'short'
}

export function formatTime(t, precision = TIME_PRECISION.second) {
  if (!t) return ''
  const s = String(t).replace('T', ' ')
  if (precision === TIME_PRECISION.short) return s.slice(5, 16)
  if (precision === TIME_PRECISION.minute) return s.slice(0, 16)
  return s.slice(0, 19)
}

/** 0.4615 → "46%"，管理端统计用。 */
export const pct = v => `${Math.round((v || 0) * 100)}%`
