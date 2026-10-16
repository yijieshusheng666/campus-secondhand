<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useApiRequest } from '../composables/useApiRequest'
import { escrowTag, statusTag } from '../constants/order'
import { useCurrentUser } from '../composables/useCurrentUser'
import ReviewDialog from '../components/ReviewDialog.vue'
import { statusIcon, escrowIcon, actionIcon } from '../icons'

const router = useRouter()
const orders = ref([])
const loading = ref(false)
const user = useCurrentUser()

const ordersApi = useApiRequest('订单操作失败')
const disputeApi = useApiRequest('提交失败，请检查后端服务')
const actionApi = useApiRequest('操作失败，请检查后端服务')

// statusTag / escrowTag 来自 src/constants/order.js（曾与 User.vue 各抄一份导致配色漂移）


function formatTime(t) {
  if (!t) return ''
  return String(t).replace('T', ' ').slice(0, 19)
}

async function loadOrders() {
  if (!user.value) return
  loading.value = true
  try {
    const { data } = await ordersApi.get('/orders', { params: { userId: user.value.id, role: 'buyer' } })
    orders.value = data
  } catch (e) {
    // useApiRequest 已统一提示
  } finally {
    loading.value = false
  }
}

// 我能对这笔订单做的操作：由后端状态机给出 nextActions，再按身份过滤
function myActions(order) {
  const role = order.buyerId === user.value.id ? 'buyer' : 'seller'
  return (order.nextActions || []).filter(a => a.actor === role)
}

// 各流转的确认文案：担保交易的关键节点要说清楚钱去哪了
const ACTION_TIPS = {
  '待发货': '确认付款吗？付款后货款由平台托管，等你确认收货才会打给卖家。',
  '已取消': '确定取消这笔订单吗？商品会重新上架。',
  '已发货': '确认已经发货了吗？',
  '已完成': '确认已经收到宝贝了吗？确认后平台会把货款打给卖家。',
  '退款中': '确定申请退款吗？',
  '已退款': '同意买家的退款申请吗？货款将退回买家，商品重新上架。'
}

// —— B4 申请平台介入：需要填理由，所以不走通用的状态流转，单独走纠纷接口 ——
const disputeVisible = ref(false)
const disputeOrder = ref(null)
const disputeForm = ref({ reason: '货不对板', detail: '' })
const disputeSubmitting = ref(false)
const DISPUTE_REASONS = ['货不对板', '未收到货', '卖家拒绝退款', '其他']

function openDispute(order) {
  disputeOrder.value = order
  disputeForm.value = { reason: '货不对板', detail: '' }
  disputeVisible.value = true
}

async function submitDispute() {
  disputeSubmitting.value = true
  try {
    const { data } = await disputeApi.post('/disputes', {
      orderId: disputeOrder.value.id,
      initiatorId: user.value.id,
      reason: disputeForm.value.reason,
      detail: disputeForm.value.detail
    })
    if (data.success) {
      ElMessage.success(data.message || '已提交平台介入')
      disputeVisible.value = false
      await loadOrders()
      loadReviewedFlags()
    } else {
      ElMessage.error(data.message || '提交失败')
    }
  } catch (e) {
    // useApiRequest 已统一提示
  } finally {
    disputeSubmitting.value = false
  }
}

async function handleAction(order, action) {
  // 申请平台介入要带理由，交给纠纷弹窗处理
  if (action.status === '仲裁中') {
    openDispute(order)
    return
  }
  try {
    await ElMessageBox.confirm(ACTION_TIPS[action.status] || `确定要「${action.label}」吗？`,
      action.label, { type: 'info' })
  } catch {
    return
  }
  try {
    const { data } = await actionApi.put(`/orders/${order.id}/status`, {
      status: action.status,
      userId: user.value.id
    })
    if (data.success) {
      ElMessage.success(data.message || '操作成功')
      await loadOrders()
      loadReviewedFlags()
    } else {
      ElMessage.error(data.message || '操作失败')
    }
  } catch (e) {
    // useApiRequest 已统一提示
  }
}

// —— B3 双向评价：弹窗抽成共用组件（卖家侧在个人中心复用同一个） ——
const reviewVisible = ref(false)
const reviewOrder = ref(null)
const reviewedOrders = ref(new Set())

async function loadReviewedFlags() {
  const flags = new Set()
  for (const order of orders.value) {
    if (order.status !== '已完成') continue
    try {
      // silent：这是「已完成订单逐笔查评价标记」的循环，一笔失败就该当未评价处理；
      // 不 silent 的话 AI/后端抖动时会一次性弹出十几条错误提示
      const { data } = await ordersApi.get('/reviews/order', {
        params: { orderId: order.id, reviewerId: user.value.id },
        silent: true
      })
      if (data.reviewed) flags.add(order.id)
    } catch (e) {
      // 查不到就当作未评价，不影响页面
    }
  }
  reviewedOrders.value = flags
}

function openReview(order) {
  reviewOrder.value = order
  reviewVisible.value = true
}

function onReviewed() {
  if (reviewOrder.value) {
    reviewedOrders.value = new Set([...reviewedOrders.value, reviewOrder.value.id])
  }
}

onMounted(async () => {
  if (!user.value) return
  await loadOrders()
  loadReviewedFlags()
})
</script>

<template>
  <div class="orders-page">
    <div v-if="!user" class="empty-wrap">
      <el-empty description="请先登录">
        <el-button type="primary" @click="router.push('/login')">去登录</el-button>
      </el-empty>
    </div>

    <div v-else v-loading="loading">
      <el-empty v-if="!loading && orders.length === 0" description="暂无订单" />
      <el-card v-for="order in orders" :key="order.id" class="order-card" shadow="never">
        <div class="order-header">
          <span class="order-id">订单号：{{ order.id }}</span>
          <div class="order-tags">
            <el-tag size="small" :type="escrowTag[order.escrowStatus] || 'info'">
              <img  loading="lazy" v-if="escrowIcon(order.escrowStatus)" class="tag-icon" :src="escrowIcon(order.escrowStatus)" alt="">
              资金：{{ order.escrowStatus || '—' }}
            </el-tag>
            <el-tag :type="statusTag[order.status] || 'info'">
              <img  loading="lazy" v-if="statusIcon(order.status)" class="tag-icon" :src="statusIcon(order.status)" alt="">
              {{ order.status }}
            </el-tag>
          </div>
        </div>
        <div class="order-content">
          <img v-img-fallback loading="lazy" class="order-img" :src="order.goodsImage" alt="商品">
          <div class="order-info">
            <div class="order-title">{{ order.goodsTitle }}</div>
            <div class="order-price">¥{{ order.goodsPrice }}</div>
          </div>
        </div>
        <div class="order-address">
          <div><strong>收货人：</strong>{{ order.receiver }} {{ order.phone }}</div>
          <div><strong>地址：</strong>{{ order.address }}</div>
        </div>
        <div class="order-footer">
          <span class="order-time">下单时间：{{ formatTime(order.createdAt) }}</span>
          <div class="order-actions">
            <!-- 状态机给什么按钮就渲染什么：非法操作在界面上根本不会出现 -->
            <el-button v-for="a in myActions(order)" :key="a.status" size="small"
              :type="a.status === '已取消' || a.status === '退款中' ? 'danger' : 'primary'"
              :plain="a.status === '已取消' || a.status === '退款中'"
              @click="handleAction(order, a)">
              <img  loading="lazy" v-if="actionIcon(a.label)" class="btn-icon" :src="actionIcon(a.label)" alt="">{{ a.label }}
            </el-button>
            <span v-if="order.status === '退款中'" class="order-hint">等待卖家处理退款</span>
            <span v-if="order.status === '仲裁中'" class="order-hint">平台处理中，货款已冻结</span>
            <template v-if="order.status === '已完成'">
              <el-tag v-if="reviewedOrders.has(order.id)" size="small" type="success">已评价</el-tag>
              <el-button v-else size="small" type="primary" plain @click="openReview(order)">
                评价卖家
              </el-button>
            </template>
          </div>
        </div>
      </el-card>
    </div>

    <!-- B3 评价卖家（共用组件）：未登录时不渲染，否则 :user-id="user.id" 会在 user 为 null 时抛错 -->
    <ReviewDialog v-if="user" v-model="reviewVisible" :order="reviewOrder" :user-id="user.id"
      target="卖家" @submitted="onReviewed" />

    <!-- B4 申请平台介入 -->
    <el-dialog v-model="disputeVisible" title="申请平台介入" width="440px">
      <div class="dispute-body">
        <div class="dispute-goods">{{ disputeOrder?.goodsTitle }}</div>
        <div class="dispute-row">
          <span class="dispute-label">申请理由</span>
          <el-select v-model="disputeForm.reason" style="flex: 1">
            <el-option v-for="r in DISPUTE_REASONS" :key="r" :label="r" :value="r" />
          </el-select>
        </div>
        <el-input v-model="disputeForm.detail" type="textarea" :rows="3" maxlength="500" show-word-limit
          placeholder="说明一下具体情况，比如货物与描述不符的地方（选填）" />
        <p class="dispute-note">
          提交后订单进入「仲裁中」，货款冻结在平台，由平台管理员裁定。
        </p>
      </div>
      <template #footer>
        <el-button @click="disputeVisible = false">取消</el-button>
        <el-button type="primary" :loading="disputeSubmitting" @click="submitDispute">提交申请</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.orders-page { max-width: 800px; margin: 0 auto; }
.empty-wrap { background: #fff; border-radius: 8px; padding: 40px; }
.order-card { margin-bottom: 16px; }
.order-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 10px;
  margin-bottom: 12px;
  border-bottom: 1px solid #f0f0f0;
}
.order-id { font-size: 13px; color: #666; }
.order-tags { display: flex; align-items: center; gap: 6px; }
/* 标签 / 按钮里的小图标：统一 15px，靠 baseline 微调对齐文字 */
.tag-icon {
  width: 15px;
  height: 15px;
  border-radius: 4px;
  object-fit: cover;
  vertical-align: -3px;
  margin-right: 3px;
}
.btn-icon {
  width: 15px;
  height: 15px;
  border-radius: 4px;
  object-fit: cover;
  vertical-align: -3px;
  margin-right: 4px;
}
.order-hint { font-size: 12px; color: #999; }
.order-content { display: flex; gap: 14px; }
.order-img { width: 80px; height: 80px; object-fit: cover; border-radius: 4px; background: #eee; }
.order-title { font-weight: bold; margin-bottom: 6px; }
.order-price { color: var(--price-color); font-size: 16px; font-weight: bold; }
.order-address {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid #f0f0f0;
  font-size: 13px;
  color: #666;
  line-height: 1.8;
}
.order-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 10px;
}
.order-time { font-size: 12px; color: #999; }
.order-actions { display: flex; align-items: center; gap: 8px; }
.dispute-goods { font-weight: 600; margin-bottom: 12px; color: var(--ink-color); }
.dispute-row { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.dispute-label { color: #888; font-size: 13px; width: 64px; flex-shrink: 0; }
.dispute-note { font-size: 12px; color: #bbb; margin: 8px 0 0; line-height: 1.6; }
</style>
