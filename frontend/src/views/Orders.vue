<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api'

const router = useRouter()
const orders = ref([])
const loading = ref(false)
const user = JSON.parse(localStorage.getItem('user') || 'null')

const statusTag = {
  '待付款': 'danger',
  '待发货': 'warning',
  '已发货': 'primary',
  '已完成': 'success',
  '退款中': 'info',
  '已退款': 'info',
  '已取消': 'info'
}

// 资金状态：担保交易的关键——钱在哪
const escrowTag = {
  '未付款': 'info',
  '平台托管': 'warning',
  '已放款': 'success',
  '已退回': 'info'
}

function getImageUrl(path) {
  if (!path) return ''
  return path.startsWith('http') ? path : path
}

function formatTime(t) {
  if (!t) return ''
  return String(t).replace('T', ' ').slice(0, 19)
}

async function loadOrders() {
  if (!user) return
  loading.value = true
  try {
    const { data } = await request.get('/orders', { params: { userId: user.id, role: 'buyer' } })
    orders.value = data
  } catch (e) {
    ElMessage.error('订单加载失败，请检查后端服务')
  } finally {
    loading.value = false
  }
}

// 我能对这笔订单做的操作：由后端状态机给出 nextActions，再按身份过滤
function myActions(order) {
  const role = order.buyerId === user.id ? 'buyer' : 'seller'
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

async function handleAction(order, action) {
  try {
    await ElMessageBox.confirm(ACTION_TIPS[action.status] || `确定要「${action.label}」吗？`,
      action.label, { type: 'info' })
  } catch {
    return
  }
  try {
    const { data } = await request.put(`/orders/${order.id}/status`, {
      status: action.status,
      userId: user.id
    })
    if (data.success) {
      ElMessage.success(data.message || '操作成功')
      await loadOrders()
      loadReviewedFlags()
    } else {
      ElMessage.error(data.message || '操作失败')
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '操作失败，请检查后端服务')
  }
}

// —— B3 双向评价 ——
const reviewVisible = ref(false)
const reviewOrder = ref(null)
const reviewForm = ref({ score: 5, content: '' })
const submitting = ref(false)
const reviewedOrders = ref(new Set())

async function loadReviewedFlags() {
  const flags = new Set()
  for (const order of orders.value) {
    if (order.status !== '已完成') continue
    try {
      const { data } = await request.get('/reviews/order', {
        params: { orderId: order.id, reviewerId: user.id }
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
  reviewForm.value = { score: 5, content: '' }
  reviewVisible.value = true
}

async function submitReview() {
  if (!reviewForm.value.score) {
    ElMessage.warning('请先给个星级')
    return
  }
  submitting.value = true
  try {
    const { data } = await request.post('/reviews', {
      orderId: reviewOrder.value.id,
      reviewerId: user.id,
      score: reviewForm.value.score,
      content: reviewForm.value.content
    })
    if (data.success) {
      ElMessage.success(data.message || '评价已提交')
      reviewedOrders.value = new Set([...reviewedOrders.value, reviewOrder.value.id])
      reviewVisible.value = false
    } else {
      ElMessage.error(data.message || '评价失败')
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '评价失败，请检查后端服务')
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  if (!user) return
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
              资金：{{ order.escrowStatus || '—' }}
            </el-tag>
            <el-tag :type="statusTag[order.status] || 'info'">{{ order.status }}</el-tag>
          </div>
        </div>
        <div class="order-content">
          <img class="order-img" :src="getImageUrl(order.goodsImage)" alt="商品">
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
              @click="handleAction(order, a)">{{ a.label }}</el-button>
            <span v-if="order.status === '退款中'" class="order-hint">等待卖家处理退款</span>
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

    <!-- B3 评价弹窗 -->
    <el-dialog v-model="reviewVisible" title="评价这位卖家" width="420px">
      <div class="review-body">
        <div class="review-goods">{{ reviewOrder?.goodsTitle }}</div>
        <div class="review-row">
          <span class="review-label">交易体验</span>
          <el-rate v-model="reviewForm.score" show-text
            :texts="['很差', '较差', '一般', '满意', '非常满意']" />
        </div>
        <el-input v-model="reviewForm.content" type="textarea" :rows="3" maxlength="200" show-word-limit
          placeholder="说说成色、沟通和发货速度吧（选填）" />
        <p class="review-note">评价会累计到对方的信用分，请如实反馈。</p>
      </div>
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitReview">提交评价</el-button>
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
.review-goods { font-weight: 600; margin-bottom: 12px; color: var(--ink-color); }
.review-row { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.review-label { color: #888; font-size: 13px; width: 64px; flex-shrink: 0; }
.review-note { font-size: 12px; color: #bbb; margin: 8px 0 0; }
</style>
