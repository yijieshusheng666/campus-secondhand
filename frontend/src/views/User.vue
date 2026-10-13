<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api'
import ReviewDialog from '../components/ReviewDialog.vue'

const router = useRouter()
const user = ref(JSON.parse(localStorage.getItem('user') || 'null'))

// 我的商品
const myGoods = ref([])
const selectedIds = ref([])

// 售出记录
const sales = ref([])

// 昵称弹窗
const nameDialog = ref(false)
const newName = ref('')

// 地址弹窗
const addrDialog = ref(false)
const addresses = ref([])
const selectedAddrId = ref(null)
const addrForm = ref({ receiver: '', phone: '', address: '' })

// B1 学号实名认证
const verified = ref(false)
const realNameMasked = ref('')
const verifyVisible = ref(false)
const verifyForm = ref({ realName: '' })
const verifying = ref(false)

async function loadVerifyStatus() {
  if (!user.value) return
  try {
    const { data } = await request.get(`/users/${user.value.id}`)
    verified.value = !!data.verified
    realNameMasked.value = data.realNameMasked || ''
  } catch (e) {
    // 拿不到认证状态不影响页面其他部分
  }
}

// B3 信用分与收到的评价
const credit = ref({ creditScore: 60, avgScore: 0, reviewCount: 0, creditLevel: '新用户' })
const reviews = ref([])

// C3 我的拼单 / C4 换物请求
const myGroupBuys = ref([])
const barterReceived = ref([])
const barterSent = ref([])

async function loadGroupBuys() {
  if (!user.value) return
  try {
    const { data } = await request.get('/groupbuy', { params: { userId: user.value.id } })
    myGroupBuys.value = data.data || []
  } catch (e) {
    // 拼单加载失败不影响页面其他部分
  }
}

async function loadBarters() {
  if (!user.value) return
  try {
    const { data } = await request.get('/barter', { params: { userId: user.value.id } })
    barterReceived.value = data.received || []
    barterSent.value = data.sent || []
  } catch (e) {
    // 换物请求加载失败不影响页面其他部分
  }
}

async function quitGroupBuy(groupBuy) {
  const isInitiator = groupBuy.initiatorId === user.value.id
  try {
    await ElMessageBox.confirm(
      isInitiator ? '你是发起人，取消后整个拼单就结束了，确定吗？' : '确定退出这个拼单吗？',
      isInitiator ? '取消拼单' : '退出拼单', { type: 'warning' })
  } catch {
    return
  }
  try {
    const { data } = await request.post(`/groupbuy/${groupBuy.id}/cancel`, { userId: user.value.id })
    if (data.success) {
      ElMessage.success(data.message)
      loadGroupBuys()
    } else {
      ElMessage.error(data.message || '操作失败')
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '操作失败')
  }
}

async function respondBarter(barter, accept) {
  try {
    await ElMessageBox.confirm(
      accept ? '同意和对方交换吗？同意后你们自行约时间交接。' : '确定拒绝这条换物请求吗？',
      '回应换物请求', { type: accept ? 'info' : 'warning' })
  } catch {
    return
  }
  try {
    const { data } = await request.post(`/barter/${barter.id}/respond`, {
      userId: user.value.id, accept
    })
    if (data.success) {
      ElMessage.success(data.message)
      loadBarters()
    } else {
      ElMessage.error(data.message || '操作失败')
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '操作失败')
  }
}

function barterStatusType(status) {
  if (status === '已同意') return 'success'
  if (status === '已拒绝') return 'info'
  return 'warning'
}

async function loadReviews() {
  if (!user.value) return
  try {
    const { data } = await request.get('/reviews', { params: { userId: user.value.id } })
    reviews.value = data.data || []
    // 我发出的评价：用来判断某个已完成订单还能不能评价买家
    reviewedOrderIds.value = new Set((data.sent || []).map(r => r.orderId))
    if (data.credit) credit.value = data.credit
  } catch (e) {
    // 评价加载失败不影响页面其他部分
  }
}

// —— 评价买家（卖家侧）：复用 ReviewDialog，只有文案不同 ——
const saleReviewVisible = ref(false)
const saleReviewOrder = ref(null)
const reviewedOrderIds = ref(new Set())

function openSaleReview(order) {
  saleReviewOrder.value = order
  saleReviewVisible.value = true
}

function onSaleReviewed() {
  loadSales()
  loadReviews()
}

function openVerifyDialog() {
  if (verified.value) {
    ElMessage.success(`该账号已实名认证：${realNameMasked.value}`)
    return
  }
  verifyForm.value.realName = ''
  verifyVisible.value = true
}

async function submitVerify() {
  const realName = verifyForm.value.realName.trim()
  if (!realName) {
    ElMessage.warning('请填写真实姓名')
    return
  }
  verifying.value = true
  try {
    const { data } = await request.post('/user/verify', { id: user.value.id, realName })
    if (data.success) {
      verified.value = true
      realNameMasked.value = data.realNameMasked || ''
      verifyVisible.value = false
      ElMessage.success(data.message || '实名认证通过')
    } else {
      ElMessage.error(data.message || '认证失败')
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '认证失败，请检查后端服务')
  } finally {
    verifying.value = false
  }
}

const statusTag = {
  '待付款': 'danger',
  '待发货': 'warning',
  '已发货': 'primary',
  '已完成': 'success',
  '退款中': 'info',
  '已退款': 'info',
  '已取消': 'info',
  '仲裁中': 'warning'
}

function formatTime(t) {
  if (!t) return ''
  return String(t).replace('T', ' ').slice(0, 19)
}

function toLogin() {
  if (!user.value) router.push('/login')
}

function onLogout() {
  localStorage.removeItem('user')
  ElMessage.success('已退出登录')
  router.push('/login')
}

// 修改昵称（保存到数据库）
function openNameDialog() {
  if (!user.value) return toLogin()
  newName.value = user.value.username || ''
  nameDialog.value = true
}

async function saveNickname() {
  if (!newName.value.trim()) {
    ElMessage.warning('请输入昵称')
    return
  }
  try {
    const { data } = await request.post('/user/update', { id: user.value.id, username: newName.value.trim() })
    if (data.success) {
      user.value.username = newName.value.trim()
      localStorage.setItem('user', JSON.stringify(user.value))
      nameDialog.value = false
      ElMessage.success('昵称修改成功')
    } else {
      ElMessage.error(data.message || '修改失败')
    }
  } catch (e) {
    ElMessage.error('网络错误，请检查后端服务')
  }
}

// 我的商品
async function loadMyGoods() {
  if (!user.value) return
  try {
    const { data } = await request.get('/goods')
    myGoods.value = data.filter(item => item.userId == user.value.id)
    selectedIds.value = []
  } catch (e) {
    ElMessage.error('商品加载失败')
  }
}

async function deleteGoods(goods) {
  try {
    await ElMessageBox.confirm('确定要删除该商品吗？删除后将从首页中移除。', '删除商品', { type: 'warning' })
  } catch {
    return
  }
  try {
    const { data } = await request.delete(`/goods/${goods.id}`)
    if (data.success) {
      ElMessage.success('商品删除成功')
      loadMyGoods()
    } else {
      ElMessage.error('删除失败')
    }
  } catch (e) {
    ElMessage.error('网络错误，请检查后端服务')
  }
}

async function batchDelete() {
  if (selectedIds.value.length === 0) {
    ElMessage.warning('请选择要删除的商品')
    return
  }
  try {
    await ElMessageBox.confirm(`确定要删除选中的 ${selectedIds.value.length} 个商品吗？`, '批量删除', { type: 'warning' })
  } catch {
    return
  }
  let successCount = 0
  for (const id of selectedIds.value) {
    try {
      const { data } = await request.delete(`/goods/${id}`)
      if (data.success) successCount++
    } catch { /* 忽略单个失败 */ }
  }
  if (successCount > 0) {
    ElMessage.success(`成功删除 ${successCount} 个商品`)
    loadMyGoods()
  } else {
    ElMessage.error('删除失败')
  }
}

// 售出记录（卖家视角）
async function loadSales() {
  if (!user.value) return
  try {
    const { data } = await request.get('/orders', { params: { userId: user.value.id, role: 'seller' } })
    sales.value = data
  } catch (e) {
    ElMessage.error('售出记录加载失败')
  }
}

// 卖家能做的操作同样来自后端状态机，前端不重复维护状态规则
function sellerActions(order) {
  return (order.nextActions || []).filter(a => a.actor === 'seller')
}

// 按操作名给文案：同一个目标状态可能对应不同动作（标记发货 / 拒绝退款都指向「已发货」）
const SALE_TIPS = {
  '标记发货': '确认已经发货了吗？',
  '同意退款': '同意买家的退款申请吗？货款将退回买家，商品重新上架。',
  '拒绝退款': '拒绝后订单退回「已发货」，买家不服可以申请平台介入。确定拒绝吗？'
}

async function handleSaleAction(order, action) {
  try {
    await ElMessageBox.confirm(SALE_TIPS[action.label] || `确定要「${action.label}」吗？`,
      action.label, { type: action.label === '拒绝退款' ? 'warning' : 'info' })
  } catch {
    return
  }
  try {
    const { data } = await request.put(`/orders/${order.id}/status`, {
      status: action.status,
      userId: user.value.id
    })
    if (data.success) {
      ElMessage.success(data.message || '操作成功')
      loadSales()
    } else {
      ElMessage.error(data.message || '操作失败')
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '操作失败，请检查后端服务')
  }
}

// 收货地址管理（本地存储）
function openAddrDialog() {
  if (!user.value) return toLogin()
  addresses.value = JSON.parse(localStorage.getItem('addressList') || '[]')
  selectedAddrId.value = null
  addrForm.value = { receiver: '', phone: '', address: '' }
  addrDialog.value = true
}

function selectAddr(idx) {
  selectedAddrId.value = idx
  const addr = addresses.value[idx]
  addrForm.value = { receiver: addr.receiver, phone: addr.phone, address: addr.address }
}

function saveAddress() {
  const { receiver, phone, address } = addrForm.value
  if (!receiver.trim() || !phone.trim() || !address.trim()) {
    ElMessage.warning('请填写完整')
    return
  }
  if (selectedAddrId.value !== null) {
    addresses.value[selectedAddrId.value] = { receiver: receiver.trim(), phone: phone.trim(), address: address.trim() }
  } else {
    addresses.value.push({ receiver: receiver.trim(), phone: phone.trim(), address: address.trim() })
  }
  localStorage.setItem('addressList', JSON.stringify(addresses.value))
  ElMessage.success('保存成功')
}

async function deleteAddr(idx) {
  try {
    await ElMessageBox.confirm('确定要删除该地址吗？', '删除地址', { type: 'warning' })
  } catch {
    return
  }
  addresses.value.splice(idx, 1)
  localStorage.setItem('addressList', JSON.stringify(addresses.value))
  if (selectedAddrId.value === idx) {
    selectedAddrId.value = null
    addrForm.value = { receiver: '', phone: '', address: '' }
  }
  ElMessage.success('地址已删除')
}

onMounted(() => {
  loadMyGoods()
  loadSales()
  loadVerifyStatus()
  loadReviews()
  loadGroupBuys()
  loadBarters()
})
</script>

<template>
  <div class="user-page">
    <div v-if="!user" class="empty-wrap">
      <el-empty description="未登录">
        <el-button type="primary" @click="router.push('/login')">去登录</el-button>
      </el-empty>
    </div>

    <div v-else>
      <!-- 用户信息 -->
      <el-card class="user-card" shadow="never">
        <div class="user-info" @click="toLogin">
          <el-avatar :size="72" class="user-avatar">{{ user.username?.charAt(0) }}</el-avatar>
          <h3>
            {{ user.username }}
            <el-tag v-if="verified" size="small" type="success" class="verify-tag">✅ 已实名</el-tag>
            <el-tag v-else size="small" type="warning" class="verify-tag">未实名</el-tag>
          </h3>
          <p class="stuid">学号：{{ user.stuid }}</p>
          <p v-if="verified" class="stuid">实名：{{ realNameMasked }}（已通过学号认证）</p>
        </div>
        <div class="menu-row">
          <el-button type="primary" plain @click="openVerifyDialog">
            {{ verified ? '查看实名认证' : '实名认证' }}
          </el-button>
          <el-button @click="openNameDialog">修改昵称</el-button>
          <el-button @click="openAddrDialog">收货地址管理</el-button>
          <el-button type="danger" plain @click="onLogout">退出登录</el-button>
        </div>
      </el-card>

      <!-- B3 信用分与收到的评价 -->
      <el-card class="section-card" shadow="never">
        <div class="section-header">
          <h3>我的信用</h3>
          <el-tag size="small" :type="credit.creditScore >= 75 ? 'success' : 'info'">
            {{ credit.creditLevel }}
          </el-tag>
        </div>
        <div class="credit-row">
          <div class="credit-score">{{ credit.creditScore }}<span class="credit-unit">分</span></div>
          <div class="credit-meta">
            <div>平均星级：{{ credit.reviewCount ? credit.avgScore + ' 星' : '暂无评价' }}</div>
            <div>收到评价：{{ credit.reviewCount }} 条</div>
            <div class="credit-formula">基准 60 + (平均星级-3)×15 + min(评价数,10)</div>
          </div>
        </div>
        <el-empty v-if="reviews.length === 0" description="还没有收到评价" :image-size="60" />
        <div v-for="r in reviews" :key="r.id" class="review-item">
          <div class="review-item-head">
            <span class="review-from">
              {{ r.reviewerName }}（{{ r.role === 'buyer' ? '买家评我' : '卖家评我' }}）
            </span>
            <el-rate :model-value="r.score" disabled size="small" />
          </div>
          <div class="review-text">{{ r.content || '未填写评价内容' }}</div>
          <div class="review-sub">{{ r.goodsTitle }} · {{ formatTime(r.createdAt) }}</div>
        </div>
      </el-card>

      <!-- C3 我的拼单 -->
      <el-card v-if="myGroupBuys.length" class="section-card" shadow="never">
        <div class="section-header"><h3>我的拼单</h3></div>
        <div v-for="gb in myGroupBuys" :key="gb.id" class="gb-row">
          <div class="gb-row-head">
            <span class="gb-row-title">{{ gb.goodsTitle }}</span>
            <el-tag size="small" :type="gb.status === '拼单中' ? 'warning' : 'success'">{{ gb.status }}</el-tag>
          </div>
          <div class="gb-row-meta">
            🏢 {{ gb.building }} · 已参与 {{ gb.joinedCount }}/{{ gb.targetCount }} 人
            · 发起人 {{ gb.initiatorName }}
          </div>
          <div class="gb-row-members">成员：{{ gb.members.map(m => m.name).join('、') }}</div>
          <el-button v-if="gb.status === '拼单中'" size="small" @click="quitGroupBuy(gb)">
            {{ gb.initiatorId === user.id ? '取消拼单' : '退出拼单' }}
          </el-button>
        </div>
      </el-card>

      <!-- C4 换物请求 -->
      <el-card class="section-card" shadow="never">
        <div class="section-header">
          <h3>换物请求</h3>
          <span class="section-hint">别人想用东西换你的宝贝，或你想换别人的</span>
        </div>
        <el-empty v-if="barterReceived.length === 0 && barterSent.length === 0"
          description="还没有换物请求" :image-size="60" />

        <template v-if="barterReceived.length">
          <div class="barter-sub">收到的（{{ barterReceived.length }}）</div>
          <div v-for="b in barterReceived" :key="'r' + b.id" class="barter-row">
            <div class="barter-head">
              <span class="barter-title">
                <b>{{ b.requesterName }}</b> 想用「{{ b.offerTitle }}」换你的「{{ b.goodsTitle }}」
              </span>
              <el-tag size="small" :type="barterStatusType(b.status)">{{ b.status }}</el-tag>
            </div>
            <div class="barter-desc" v-if="b.offerDesc">{{ b.offerDesc }}</div>
            <div class="barter-actions" v-if="b.status === '待回应'">
              <el-button size="small" type="primary" @click="respondBarter(b, true)">同意换物</el-button>
              <el-button size="small" @click="respondBarter(b, false)">拒绝</el-button>
            </div>
          </div>
        </template>

        <template v-if="barterSent.length">
          <div class="barter-sub">我发出的（{{ barterSent.length }}）</div>
          <div v-for="b in barterSent" :key="'s' + b.id" class="barter-row">
            <div class="barter-head">
              <span class="barter-title">
                我想用「{{ b.offerTitle }}」换「{{ b.goodsTitle }}」
              </span>
              <el-tag size="small" :type="barterStatusType(b.status)">{{ b.status }}</el-tag>
            </div>
            <div class="barter-desc" v-if="b.offerDesc">{{ b.offerDesc }}</div>
          </div>
        </template>
      </el-card>

      <!-- 我的商品 -->
      <el-card class="section-card" shadow="never">
        <div class="section-header">
          <h3>我发布的商品</h3>
          <el-button size="small" type="danger" plain @click="batchDelete">批量删除</el-button>
        </div>
        <el-empty v-if="myGoods.length === 0" description="暂无发布商品" :image-size="80" />
        <div v-for="item in myGoods" :key="item.id" class="goods-row">
          <el-checkbox :value="item.id" v-model="selectedIds" />
          <div class="goods-row-main">
            <div class="goods-row-title">{{ item.title }}</div>
            <div class="goods-row-price">¥{{ item.price }} <el-tag size="small" type="info">{{ item.category }}</el-tag></div>
            <div class="goods-row-desc">{{ item.description }}</div>
          </div>
          <el-button size="small" type="danger" plain @click="deleteGoods(item)">删除</el-button>
        </div>
      </el-card>

      <!-- 售出记录 -->
      <el-card class="section-card" shadow="never">
        <div class="section-header">
          <h3>售出记录</h3>
        </div>
        <el-empty v-if="sales.length === 0" description="暂无售出记录" :image-size="80" />
        <div v-for="order in sales" :key="order.id" class="sale-row">
          <div class="sale-header">
            <span class="sale-buyer">买家：{{ order.buyerName }}</span>
            <div class="sale-tags">
              <el-tag size="small" type="warning">资金：{{ order.escrowStatus || '—' }}</el-tag>
              <el-tag size="small" :type="statusTag[order.status] || 'info'">{{ order.status }}</el-tag>
            </div>
          </div>
          <div class="sale-line">商品：{{ order.goodsTitle }}</div>
          <div class="sale-price">¥{{ order.goodsPrice }}</div>
          <div class="sale-line">收货人：{{ order.receiver }} {{ order.phone }} / {{ order.address }}</div>
          <div class="sale-time">下单时间：{{ formatTime(order.createdAt) }}</div>
          <div class="sale-actions" v-if="sellerActions(order).length || order.status === '已完成'">
            <el-tag v-if="order.status === '已完成' && reviewedOrderIds.has(order.id)"
              size="small" type="success">已评价买家</el-tag>
            <el-button v-else-if="order.status === '已完成'" size="small" type="primary" plain
              @click="openSaleReview(order)">评价买家</el-button>
            <el-button v-for="a in sellerActions(order)" :key="a.status" size="small"
              :type="a.status === '已退款' ? 'danger' : 'primary'" :plain="a.status === '已退款'"
              @click="handleSaleAction(order, a)">{{ a.label }}</el-button>
          </div>
        </div>
      </el-card>
    </div>
  </div>

  <!-- 昵称弹窗 -->
  <el-dialog v-model="nameDialog" title="修改昵称" width="360px">
    <el-input v-model="newName" placeholder="请输入新昵称" @keyup.enter="saveNickname" />
    <template #footer>
      <el-button @click="nameDialog = false">取消</el-button>
      <el-button type="primary" @click="saveNickname">保存</el-button>
    </template>
  </el-dialog>

  <!-- 地址弹窗 -->
  <el-dialog v-model="addrDialog" title="收货地址管理" width="440px">
    <div class="addr-list">
      <div v-for="(addr, idx) in addresses" :key="idx" class="addr-item" :class="{ active: selectedAddrId === idx }"
        @click="selectAddr(idx)">
        <div>
          <div>{{ addr.receiver }} {{ addr.phone }}</div>
          <div class="addr-detail">{{ addr.address }}</div>
        </div>
        <el-button size="small" type="danger" link @click.stop="deleteAddr(idx)">删除</el-button>
      </div>
      <div v-if="addresses.length === 0" class="addr-empty">暂无地址，请在下方添加</div>
    </div>
    <el-input v-model="addrForm.receiver" placeholder="收货人" style="margin-bottom: 10px" />
    <el-input v-model="addrForm.phone" placeholder="联系电话" style="margin-bottom: 10px" />
    <el-input v-model="addrForm.address" type="textarea" placeholder="详细地址" />
    <template #footer>
      <el-button @click="addrDialog = false">关闭</el-button>
      <el-button type="primary" @click="saveAddress">保存地址</el-button>
    </template>
  </el-dialog>

  <!-- B1 学号实名认证 -->
  <el-dialog v-model="verifyVisible" title="学号实名认证" width="420px">
    <div class="verify-body">
      <p class="verify-tip">实名后商品会显示「已实名」标识，交易更放心。</p>
      <div class="verify-row">
        <span class="verify-label">学号</span>
        <span class="verify-value">{{ user.stuid }}</span>
      </div>
      <div class="verify-row">
        <span class="verify-label">真实姓名</span>
        <el-input v-model="verifyForm.realName" placeholder="请输入 2-10 位中文真实姓名" />
      </div>
      <p class="verify-note">说明：本项目只做学号格式与唯一性校验，真正的实名核验需对接学校教务系统。</p>
    </div>
    <template #footer>
      <el-button @click="verifyVisible = false">取消</el-button>
      <el-button type="primary" :loading="verifying" @click="submitVerify">提交认证</el-button>
    </template>
  </el-dialog>

  <!-- B3 评价买家：与 Orders.vue 共用同一个 ReviewDialog 组件 -->
  <ReviewDialog v-model="saleReviewVisible" :order="saleReviewOrder" :user-id="user.id"
    target="买家" @submitted="onSaleReviewed" />
</template>

<style scoped>
.user-page { max-width: 800px; margin: 0 auto; }
.empty-wrap { background: #fff; border-radius: 8px; padding: 40px; }
.user-card { margin-bottom: 16px; }
.verify-tag { margin-left: 8px; vertical-align: middle; }
.verify-body { font-size: 13px; }
.verify-tip { color: #666; margin: 0 0 12px; }
.verify-row { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.verify-label { width: 64px; color: #888; flex-shrink: 0; }
.verify-value { color: var(--ink-color); font-weight: 600; }
.verify-note { color: #bbb; font-size: 12px; line-height: 1.6; margin: 4px 0 0; }

/* B3 信用分 */
.credit-row { display: flex; align-items: center; gap: 20px; padding: 6px 0 14px; }
.credit-score { font-size: 40px; font-weight: bold; color: var(--price-color); line-height: 1; }
.credit-unit { font-size: 14px; margin-left: 2px; color: #999; }
.credit-meta { font-size: 13px; color: #666; line-height: 1.9; }
.credit-formula { font-size: 11px; color: #bbb; }
.review-item { padding: 10px 0; border-top: 1px solid #f0f0f0; }
.review-item-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.review-from { font-size: 13px; font-weight: 600; color: var(--ink-color); }
.review-text { font-size: 13px; color: #555; line-height: 1.6; margin: 4px 0; }
.review-sub { font-size: 11px; color: #bbb; }
.sale-tags { display: flex; align-items: center; gap: 6px; }

/* C3 我的拼单 / C4 换物请求 */
.gb-row { padding: 10px 0; border-top: 1px solid #f0f0f0; }
.gb-row-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.gb-row-title { font-size: 13px; font-weight: 600; color: var(--ink-color); }
.gb-row-meta { font-size: 12px; color: #888; margin: 5px 0 3px; }
.gb-row-members { font-size: 12px; color: #aaa; margin-bottom: 8px; }
.section-hint { font-size: 12px; color: #bbb; }
.barter-sub { font-size: 12px; color: #999; margin: 10px 0 4px; }
.barter-row { padding: 10px 0; border-top: 1px solid #f0f0f0; }
.barter-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
.barter-title { font-size: 13px; color: #555; line-height: 1.6; }
.barter-desc { font-size: 12px; color: #999; margin: 6px 0; line-height: 1.6; }
.barter-actions { display: flex; gap: 8px; }
.user-info { text-align: center; cursor: default; }
.user-avatar { background: #ffd21e; color: #23201a; font-size: 28px; font-weight: 600; margin-bottom: 8px; }
.stuid { color: #999; font-size: 13px; margin-top: 4px; }
.menu-row { display: flex; justify-content: center; margin-top: 16px; }
.section-card { margin-bottom: 16px; }
.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.section-header h3 { font-size: 16px; }
.goods-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 0;
  border-bottom: 1px solid #f0f0f0;
}
.goods-row:last-child { border-bottom: none; }
.goods-row-main { flex: 1; }
.goods-row-title { font-weight: bold; margin-bottom: 4px; }
.goods-row-price { color: var(--price-color); margin-bottom: 4px; }
.goods-row-desc { font-size: 13px; color: #666; }
.sale-row { padding: 12px 0; border-bottom: 1px solid #f0f0f0; }
.sale-row:last-child { border-bottom: none; }
.sale-header { display: flex; justify-content: space-between; margin-bottom: 8px; }
.sale-buyer { font-weight: bold; }
.sale-line { font-size: 13px; color: #333; margin-bottom: 4px; }
.sale-price { color: var(--price-color); font-weight: bold; margin-bottom: 4px; }
.sale-time { font-size: 12px; color: #999; }
.sale-actions { margin-top: 8px; text-align: right; }
.addr-list { margin-bottom: 14px; max-height: 240px; overflow-y: auto; }
.addr-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px;
  border: 1px solid #ddd;
  border-radius: 8px;
  margin-bottom: 8px;
  cursor: pointer;
  font-size: 14px;
}
.addr-item.active { border-color: #ffd21e; background: #fffbea; }
.addr-detail { font-size: 13px; color: #666; margin-top: 2px; }
.addr-empty { color: #999; font-size: 13px; padding: 8px 0; }
</style>
