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
  '待发货': 'warning',
  '已发货': 'primary',
  '已完成': 'success',
  '已退款': 'info'
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

async function refundOrder(order) {
  try {
    await ElMessageBox.confirm('确定要申请退款吗？退款后商品将重新上架。', '申请退款', { type: 'warning' })
  } catch {
    return
  }
  try {
    const { data } = await request.put(`/orders/${order.id}/status`, { status: '已退款' })
    if (data.success) {
      ElMessage.success('退款成功，商品已重新上架')
      loadOrders()
    }
  } catch (e) {
    ElMessage.error('退款失败，请检查后端服务')
  }
}

// B3 确认收货：只有「已完成」的订单才能评价，所以这里补上状态流转入口
async function confirmReceive(order) {
  try {
    await ElMessageBox.confirm('确认已经收到宝贝了吗？确认后订单完成，可以评价对方。', '确认收货', { type: 'info' })
  } catch {
    return
  }
  try {
    const { data } = await request.put(`/orders/${order.id}/status`, { status: '已完成' })
    if (data.success) {
      ElMessage.success('订单已完成，去评价一下卖家吧')
      loadOrders()
    }
  } catch (e) {
    ElMessage.error('操作失败，请检查后端服务')
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
          <el-tag :type="statusTag[order.status] || 'info'">{{ order.status }}</el-tag>
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
            <el-button v-if="order.status === '待发货'" size="small" type="danger" plain
              @click="refundOrder(order)">申请退款</el-button>
            <el-button v-if="order.status === '已发货'" size="small" type="primary"
              @click="confirmReceive(order)">确认收货</el-button>
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
