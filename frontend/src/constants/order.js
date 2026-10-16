/**
 * 订单状态 / 资金状态 → el-tag 配色的唯一映射表。
 *
 * 之前这张表在 Orders.vue 和 User.vue 里各抄了一份，抄歪的后果已经出现过：
 * User.vue 的「售出记录」里资金标签直接硬编码成 type="warning"，于是同一笔订单
 * 在订单页是绿色「已放款」、在个人中心却是橙色——同一份数据两种颜色。
 *
 * 这里保持与原变量同名的导出，视图侧只要把本地定义换成 import，模板不用动。
 */

/** 订单状态 → el-tag type */
export const statusTag = {
  '待付款': 'danger',
  '待发货': 'warning',
  '已发货': 'primary',
  '已完成': 'success',
  '退款中': 'info',
  '已退款': 'info',
  '已取消': 'info',
  '仲裁中': 'warning'
}

/** 资金状态 → el-tag type。担保交易的关键：钱现在在哪 */
export const escrowTag = {
  '未付款': 'info',
  '平台托管': 'warning',
  '已放款': 'success',
  '已退回': 'info'
}
