<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useApiRequest } from '../composables/useApiRequest'
import request from '../api'
import { clearPersonalContext } from '../composables/usePersonalContext'
import ReviewDialog from '../components/ReviewDialog.vue'
import { statusIcon, escrowIcon, actionIcon } from '../icons'
import { escrowTag, statusTag } from '../constants/order'
import { AVATARS, avatarUrl } from '../avatars'
import { formatTime } from '../utils/format'
import { currentUser as user, patchUser, setUser } from '../session'

const router = useRouter()

// 统一错误提示口径：网络/HTTP 错误由 useApiRequest 自动 ElMessage.error 并抛异常
const api = useApiRequest('操作失败，请检查后端服务')

// user 来自共享会话对象（见 src/session.js）：这里改了昵称/头像，导航栏会同步更新

// 我的商品
const myGoods = ref([])
const selectedIds = ref([])

// 售出记录
const sales = ref([])

// 昵称弹窗
const nameDialog = ref(false)
const newName = ref('')

// 头像弹窗（只能在内置的 4 张里选）
const avatarDialog = ref(false)
const selectedAvatar = ref('')
const savingAvatar = ref(false)

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
    // silent：拿不到认证状态不影响页面其他部分，不该为此弹错误提示
    const { data } = await api.get(`/users/${user.value.id}`, { silent: true })
    verified.value = !!data.verified
    realNameMasked.value = data.realNameMasked || ''
  } catch (e) {
    // 拿不到认证状态不影响页面其他部分
  }
}

// B3 信用分与收到的评价
const credit = ref({ creditScore: 60, avgScore: 0, reviewCount: 0, creditLevel: '新用户' })
const reviews = ref([])

async function loadReviews() {
  if (!user.value) return
  try {
    // silent：同上，评价加载失败不影响页面其他部分
    const { data } = await api.get('/reviews', { params: { userId: user.value.id }, silent: true })
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
    const { data } = await api.post('/user/verify', { id: user.value.id, realName })
    if (data.success) {
      verified.value = true
      realNameMasked.value = data.realNameMasked || ''
      verifyVisible.value = false
      ElMessage.success(data.message || '实名认证通过')
    } else {
      ElMessage.error(data.message || '认证失败')
    }
  } catch (e) {
    // useApiRequest 已统一提示（或当前是 request.* 直调，由后端 message 自动展示）
  } finally {
    verifying.value = false
  }
}

// statusTag / escrowTag 来自 src/constants/order.js，不要再本地抄一份
// formatTime 来自 src/utils/format.js

function toLogin() {
  if (!user.value) router.push('/login')
}

async function onLogout() {
  // 退出后必须清空助手那份个人数据快照，否则下一个登录的人提问时，
  // 会被当成上一个用户的私有数据一起发给 AI 服务。
  clearPersonalContext()

  try {
    await ElMessageBox.confirm('退出后需要重新登录才能发布商品、下单和查看订单。确定退出吗？', '退出登录', {
      type: 'warning',
      confirmButtonText: '确定退出',
      cancelButtonText: '再想想'
    })
  } catch {
    return
  }
  setUser(null)
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
    const { data } = await api.post('/user/update', { id: user.value.id, username: newName.value.trim() })
    if (data.success) {
      patchUser({ username: newName.value.trim() })
      nameDialog.value = false
      ElMessage.success('昵称修改成功')
    } else {
      ElMessage.error(data.message || '修改失败')
    }
  } catch (e) {
    ElMessage.error('网络错误，请检查后端服务')
  }
}

// 更换头像：只在内置的 4 张之间选，选中项存到后端 users.avatar，前端写进共享会话对象
function openAvatarDialog() {
  if (!user.value) return toLogin()
  selectedAvatar.value = user.value.avatar || ''
  avatarDialog.value = true
}

async function saveAvatar() {
  if (!selectedAvatar.value) {
    ElMessage.warning('请先选一张头像')
    return
  }
  savingAvatar.value = true
  try {
    const { data } = await api.post('/user/update', { id: user.value.id, avatar: selectedAvatar.value })
    if (data.success) {
      // 写进共享会话对象：导航栏右上角、聊天面板里的头像会立刻一起变
      patchUser({ avatar: selectedAvatar.value })
      avatarDialog.value = false
      ElMessage.success('头像已更换')
    } else {
      ElMessage.error(data.message || '更换失败')
    }
  } catch (e) {
    // useApiRequest 已统一提示（或当前是 request.* 直调，由后端 message 自动展示）
  } finally {
    savingAvatar.value = false
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
    const { data } = await api.delete(`/goods/${goods.id}`)
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
      // silent：批量删除里单条失败只跳过（下面 catch 已注明），不该每条弹一次
      const { data } = await api.delete(`/goods/${id}`, { silent: true })
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
    // silent：下面 catch 里已有更具体的中文提示
    const { data } = await api.get('/orders',
      { params: { userId: user.value.id, role: 'seller' }, silent: true })
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
    const { data } = await api.put(`/orders/${order.id}/status`, {
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
    // useApiRequest 已统一提示（或当前是 request.* 直调，由后端 message 自动展示）
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

// 首屏骨架屏的开关。个人中心一进来就并发打 4 个接口（我的商品 / 售出记录 /
// 实名状态 / 收到的评价），最坏要 500ms 才回。在这之前下面全是空的，
// 用户看到的就是「只有头像和名字、底下光秃秃一片」，然后内容整块弹出来。
// 用骨架块填掉这段空窗，高度贴近真实卡片，数据到位后原地替换不产生跳动。
const ready = ref(false)

onMounted(() => {
  // allSettled 而不是 all：任意一个接口挂了都不该让整页永远卡在骨架屏上
  Promise.allSettled([
    loadMyGoods(),
    loadSales(),
    loadVerifyStatus(),
    loadReviews()
  ]).finally(() => { ready.value = true })
})
</script>

<template>
  <div class="user-page">
    <!-- 未登录也能进个人中心：给一张明确的提示卡，而不是空白或报错 -->
    <div v-if="!user" class="guest-card">
      <img class="guest-logo" src="/images/logo.png" alt="校园二手交易平台">
      <h3 class="guest-title">未登录</h3>
      <p class="guest-hint">
        你还没有登录，登录后才能发布闲置、管理商品、查看订单和信用分。<br>
        新同学用学号注册即可，全程免费。
      </p>
      <el-button type="primary" round size="large" @click="router.push('/login')">
        去登录 / 注册
      </el-button>
    </div>

    <div v-else>
      <!-- 用户信息 -->
      <el-card class="user-card" shadow="never">
        <div class="user-info" @click="toLogin">
          <el-avatar :size="72" class="user-avatar" :src="avatarUrl(user)">
            {{ user.username?.charAt(0) }}
          </el-avatar>
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
          <el-button @click="openAvatarDialog">更换头像</el-button>
          <el-button @click="openAddrDialog">收货地址管理</el-button>
          <el-button type="danger" plain @click="onLogout">退出登录</el-button>
        </div>
      </el-card>

      <!-- 首屏空窗：骨架块高度贴近真实卡片，数据到位后原地替换 -->
      <div v-if="!ready" class="user-skeleton">
        <el-card v-for="n in 3" :key="n" class="section-card" shadow="never">
          <el-skeleton :rows="n === 1 ? 2 : 3" animated />
        </el-card>
      </div>

      <template v-else>

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
              <el-tag size="small" :type="escrowTag[order.escrowStatus] || 'info'">
                <img v-if="escrowIcon(order.escrowStatus)" loading="lazy" class="tag-icon" :src="escrowIcon(order.escrowStatus)" alt="">
                资金：{{ order.escrowStatus || '—' }}
              </el-tag>
              <el-tag size="small" :type="statusTag[order.status] || 'info'">
                <img  loading="lazy" v-if="statusIcon(order.status)" class="tag-icon" :src="statusIcon(order.status)" alt="">
                {{ order.status }}
              </el-tag>
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
              @click="handleSaleAction(order, a)">
              <img  loading="lazy" v-if="actionIcon(a.label)" class="btn-icon" :src="actionIcon(a.label)" alt="">{{ a.label }}
            </el-button>
          </div>
        </div>
      </el-card>
    </template>
    </div>
  </div>

  <!-- 下面这些弹窗都跟「当前登录用户」有关：未登录时整个不渲染，
       否则 :user-id="user.id" 这类绑定会在 user 为 null 时直接抛错 -->
  <template v-if="user">
  <!-- 头像弹窗：只给内置的 4 张，点一下即选中 -->
  <el-dialog v-model="avatarDialog" title="更换头像" width="400px">
    <div class="avatar-picker">
      <div v-for="a in AVATARS" :key="a.key" class="avatar-option"
        :class="{ active: selectedAvatar === a.key }" :title="a.name" @click="selectedAvatar = a.key">
        <img  loading="lazy" :src="a.url" :alt="a.name">
        <span class="avatar-name">{{ a.name }}</span>
      </div>
    </div>
    <p class="avatar-note">头像只能从上面这几张里选，保存后全站（导航、聊天、个人中心）都会同步。</p>
    <template #footer>
      <el-button @click="avatarDialog = false">取消</el-button>
      <el-button type="primary" :loading="savingAvatar" @click="saveAvatar">保存</el-button>
    </template>
  </el-dialog>

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
</template>

<style scoped>
.user-page { max-width: 800px; margin: 0 auto; }
/* 首屏骨架：只在数据回来前出现，占位高度贴近真实卡片 */
.user-skeleton .section-card :deep(.el-skeleton__item) { border-radius: 6px; }
/* 未登录提示卡 */
.guest-card { background: #fff; border-radius: 12px; padding: 48px 24px; text-align: center; }
.guest-logo { height: 56px; width: auto; display: block; margin: 0 auto 14px; }
.guest-title { font-size: 18px; color: var(--ink-color); margin: 0 0 8px; }
.guest-hint { font-size: 13px; color: #999; line-height: 1.8; margin: 0 0 22px; }
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

/* 头像选盘：4 张内置头像，选中项描边高亮 */
.avatar-picker { display: flex; gap: 14px; justify-content: center; }
.avatar-option {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 8px;
  border: 2px solid transparent;
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.2s;
}
.avatar-option:hover { background: #fffbea; }
.avatar-option.active { border-color: var(--el-color-primary-dark-2); background: #fffbea; }
.avatar-option img { width: 56px; height: 56px; border-radius: 50%; display: block; }
.avatar-name { font-size: 12px; color: #666; }
.avatar-note { font-size: 12px; color: #bbb; text-align: center; margin: 12px 0 0; line-height: 1.6; }
/* 标签 / 按钮里的小图标（与订单页保持一致） */
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

.section-hint { font-size: 12px; color: #bbb; }
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
