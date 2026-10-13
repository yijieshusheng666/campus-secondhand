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

onMounted(() => {
  if (!user) return
  loadOrders()
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
          <el-button v-if="order.status === '待发货'" size="small" type="danger" plain
            @click="refundOrder(order)">申请退款</el-button>
        </div>
      </el-card>
    </div>
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
</style>
