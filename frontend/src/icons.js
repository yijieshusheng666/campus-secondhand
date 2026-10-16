/**
 * 界面图标统一登记表。
 *
 * 图片实体放在 frontend/public/images/icons/，页面里用 /images/icons/xxx.png 直接引用，
 * 不用打包器处理，换图/加图只改这一个文件。
 *
 * 命名约定：导航用功能名（home/publish/...），订单状态用 order-*，资金状态用 fund-*，
 * 操作按钮用 act-*，中文含义写在各组下面的注释里。
 *
 * ⚠️ 有 4 个位置素材里没有专属图标，先复用最接近的现有图标（都集中在下面「复用图标」一段）：
 *   1. messages   —— 顶部导航「消息」，复用求购的气泡图标（所以「求购」和「消息」现在图标一样）
 *   2. dispute    —— 「申请平台介入」，复用「仲裁中」的天平图标
 *   3. rule-refund / rule-release —— 管理端两个裁定按钮，复用「同意退款 / 拒绝退款」的硬币图标
 * 以后有了专属图，把新图按同名放进 public/images/icons/，只改这一段即可。
 */
const BASE = '/images/icons'

// 实际存在的图标清单（key 就是登记名）
export const ICONS = {
  // —— 顶部导航 ——
  home: `${BASE}/home.png`,
  wantlist: `${BASE}/wantlist.png`,
  publish: `${BASE}/publish.png`,
  orders: `${BASE}/orders.png`,
  profile: `${BASE}/profile.png`,
  manage: `${BASE}/manage.png`,
  // —— 订单状态 ——
  'order-pending-payment': `${BASE}/order-pending-payment.png`,
  'order-pending-shipment': `${BASE}/order-pending-shipment.png`,
  'order-shipped': `${BASE}/order-shipped.png`,
  'order-completed': `${BASE}/order-completed.png`,
  'order-refunding': `${BASE}/order-refunding.png`,
  'order-refunded': `${BASE}/order-refunded.png`,
  'order-cancelled': `${BASE}/order-cancelled.png`,
  'order-arbitrating': `${BASE}/order-arbitrating.png`,
  // —— 资金状态（担保交易：钱现在在哪） ——
  'fund-unpaid': `${BASE}/fund-unpaid.png`,
  'fund-escrow': `${BASE}/fund-escrow.png`,
  'fund-released': `${BASE}/fund-released.png`,
  'fund-returned': `${BASE}/fund-returned.png`,
  // —— 操作按钮 ——
  'act-pay': `${BASE}/act-pay.png`,
  'act-ship': `${BASE}/act-ship.png`,
  'act-confirm-receipt': `${BASE}/act-confirm-receipt.png`,
  'act-refund-apply': `${BASE}/act-refund-apply.png`,
  'act-agree-refund': `${BASE}/act-agree-refund.png`,
  'act-reject-refund': `${BASE}/act-reject-refund.png`,
  'act-cancel-order': `${BASE}/act-cancel-order.png`,

  // —— 复用图标：素材里没有专属图，先借最接近的用（界面上不会出现裂图） ——
  messages: `${BASE}/wantlist.png`, // 「消息」借求购的气泡
  dispute: `${BASE}/order-arbitrating.png`, // 「申请平台介入」借仲裁中的天平
  'rule-refund': `${BASE}/act-agree-refund.png`, // 裁定·支持买家退款
  'rule-release': `${BASE}/act-reject-refund.png`, // 裁定·驳回诉求放款

  // —— 首页分类胶囊（已裁掉原图白边并做成圆角，深色选中态也不露白角） ——
  'cate-electronics': `${BASE}/cate-electronics.png`,
  'cate-books': `${BASE}/cate-books.png`,
  'cate-daily': `${BASE}/cate-daily.png`,
  'cate-sports': `${BASE}/cate-sports.png`,
  'cate-furniture': `${BASE}/cate-furniture.png`,
  'cate-beauty': `${BASE}/cate-beauty.png`
}

/** 登记名 -> 图片地址；没登记的返回空串，调用方用 v-if 兜住即可。 */
export function iconUrl(name) {
  return (name && ICONS[name]) || ''
}

// 顶部导航路由 -> 图标
export const NAV_ICON = {
  '/': 'home',
  '/wanted': 'wantlist',
  '/messages': 'messages', // 复用求购的气泡图标
  '/publish': 'publish',
  '/orders': 'orders',
  '/user': 'profile',
  '/admin': 'manage'
}

// 订单状态 -> 图标
export const ORDER_STATUS_ICON = {
  '待付款': 'order-pending-payment',
  '待发货': 'order-pending-shipment',
  '已发货': 'order-shipped',
  '已完成': 'order-completed',
  '退款中': 'order-refunding',
  '已退款': 'order-refunded',
  '已取消': 'order-cancelled',
  '仲裁中': 'order-arbitrating'
}

// 资金状态 -> 图标
export const ESCROW_ICON = {
  '未付款': 'fund-unpaid',
  '平台托管': 'fund-escrow',
  '已放款': 'fund-released',
  '已退回': 'fund-returned'
}

// 操作按钮 -> 图标。按按钮文案匹配：同一个目标状态可能对应不同动作
// （「标记发货」和「拒绝退款」都指向状态「已发货」，图标必须不一样）。
export const ACTION_ICON = {
  '立即付款': 'act-pay',
  '取消订单': 'act-cancel-order',
  '标记发货': 'act-ship',
  '申请退款': 'act-refund-apply',
  '确认收货': 'act-confirm-receipt',
  '同意退款': 'act-agree-refund',
  '拒绝退款': 'act-reject-refund',
  '申请平台介入': 'dispute' // 复用仲裁中的天平图标
}

// 管理端裁定 -> 图标（按钮文案里带空格，所以按裁定结果匹配）
export const RULE_ICON = {
  '支持买家': 'rule-refund',
  '驳回买家': 'rule-release'
}

// 首页分类胶囊 -> 图标（「全部」没有专属图标，界面回退成原来的 emoji）
export const CATEGORY_ICON = {
  '电子产品': 'cate-electronics',
  '图书教材': 'cate-books',
  '生活用品': 'cate-daily',
  '运动户外': 'cate-sports',
  '二手家具': 'cate-furniture',
  '美妆护肤': 'cate-beauty'
}

// 个别入口的图标着色：「消息」复用求购的气泡图，靠色相偏移把它变成另一张「图」。
// 值直接当 CSS filter 用，想换颜色改这里的角度即可（角度越大偏得越远）。
export const NAV_ICON_TINT = {
  '/messages': 'hue-rotate(165deg) saturate(1.15)'
}

export const navIcon = path => iconUrl(NAV_ICON[path])
export const navTint = path => NAV_ICON_TINT[path] || ''
export const statusIcon = status => iconUrl(ORDER_STATUS_ICON[status])
export const escrowIcon = status => iconUrl(ESCROW_ICON[status])
export const actionIcon = label => iconUrl(ACTION_ICON[label])
export const ruleIcon = ruling => iconUrl(RULE_ICON[ruling])
export const categoryIcon = name => iconUrl(CATEGORY_ICON[name])
