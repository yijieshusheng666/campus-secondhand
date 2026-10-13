<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../api'

const route = useRoute()
const router = useRouter()

// 分类胶囊（emoji 图标为自定义校园元素）
const categories = [
  { name: '全部', emoji: '✨' },
  { name: '电子产品', emoji: '📱' },
  { name: '图书教材', emoji: '📚' },
  { name: '生活用品', emoji: '🧺' },
  { name: '运动户外', emoji: '🏀' },
  { name: '二手家具', emoji: '🪑' },
  { name: '美妆护肤', emoji: '💄' }
]

const goodsList = ref([])
const activeCategory = ref('全部')
const keyword = ref('')
const loading = ref(false)
// 本次搜索实际走的链路：''（浏览）/ 'semantic'（语义召回）/ 'keyword'（关键词兜底）
const searchMode = ref('')

// 商品详情弹窗
const detailVisible = ref(false)
const detailGoods = ref(null)
const sellerName = ref('')
const sellerInfo = ref(null)

// 下单弹窗
const buyVisible = ref(false)
const buyGoods = ref(null)
const buyForm = ref({ receiver: '', phone: '', address: '' })
const addresses = ref([])
const selectedAddress = ref(null)

function getImageUrl(path) {
  if (!path) return ''
  return path.startsWith('http') ? path : path
}

async function loadGoods() {
  loading.value = true
  try {
    const params = {}
    if (activeCategory.value !== '全部') params.category = activeCategory.value
    if (keyword.value.trim()) params.keyword = keyword.value.trim()
    const { data } = await request.get('/goods', { params })
    goodsList.value = data
  } catch (e) {
    ElMessage.error('宝贝加载失败，请检查后端服务')
  } finally {
    loading.value = false
  }
}

function selectCategory(cate) {
  activeCategory.value = cate
  // 已有搜索词时按新分类重搜，否则只是切换浏览
  if (keyword.value.trim()) {
    doSearch()
  } else {
    searchMode.value = ''
    loadGoods()
  }
}

/**
 * 搜索：语义优先，关键词兜底。
 * 语义召回走 /api/ai/search（AI 侧只负责按意思找，价格/分类/在售由后端过滤），
 * AI 服务不可用或召回为空时，自动退回原来的关键词匹配，保证搜索永远可用。
 */
async function doSearch() {
  const kw = keyword.value.trim()
  if (!kw) {
    searchMode.value = ''
    return loadGoods()
  }

  loading.value = true
  let semanticHits = null
  try {
    const params = { keyword: kw, topK: 12 }
    if (activeCategory.value !== '全部') params.category = activeCategory.value
    const { data } = await request.get('/ai/search', { params })
    if (data.success && data.semantic && data.data.length > 0) {
      semanticHits = data.data
    }
  } catch (e) {
    // AI 服务不可用：静默回退关键词
  }

  if (semanticHits) {
    goodsList.value = semanticHits
    searchMode.value = 'semantic'
    loading.value = false
    return
  }

  await loadGoods()
  searchMode.value = 'keyword'
}

async function showDetail(goods) {
  detailGoods.value = goods
  detailVisible.value = true
  sellerName.value = ''
  sellerInfo.value = null
  groupBuys.value = []
  try {
    const { data } = await request.get(`/users/${goods.userId}`)
    sellerName.value = data.username
    // B1/B3：卖家实名标识与信用分（接口只返回脱敏姓名）
    sellerInfo.value = data
  } catch (e) {
    // 卖家信息拿不到不影响详情展示
  }
  // C3：这件宝贝上正在进行的宿舍楼拼单
  loadGroupBuys(goods.id)
}

// —— C3 宿舍楼拼单 ——
const groupBuys = ref([])
const buildings = ['1 栋', '2 栋', '3 栋', '5 栋', '6 栋', '7 栋', '8 栋',
                   '9 栋', '10 栋', '11 栋', '12 栋', '研究生楼']
const groupBuyVisible = ref(false)
const groupBuyForm = ref({ building: '', targetCount: 3 })
const groupBuySubmitting = ref(false)
const joinVisible = ref(false)
const joinForm = ref({ building: '' })
const activeGroupBuy = ref(null)
const joining = ref(false)

async function loadGroupBuys(goodsId) {
  try {
    const { data } = await request.get(`/groupbuy/goods/${goodsId}`)
    groupBuys.value = data.data || []
  } catch (e) {
    groupBuys.value = []
  }
}

function requireLogin() {
  const current = JSON.parse(localStorage.getItem('user') || 'null')
  if (!current) {
    ElMessage.warning('请先登录')
    router.push('/login')
    return null
  }
  return current
}

function openGroupBuy() {
  const current = requireLogin()
  if (!current) return
  groupBuyForm.value = { building: '', targetCount: 3 }
  groupBuyVisible.value = true
}

async function submitGroupBuy() {
  const current = requireLogin()
  if (!current) return
  if (!groupBuyForm.value.building) {
    ElMessage.warning('请选择你所在的宿舍楼')
    return
  }
  groupBuySubmitting.value = true
  try {
    const { data } = await request.post('/groupbuy', {
      goodsId: detailGoods.value.id,
      initiatorId: current.id,
      building: groupBuyForm.value.building,
      targetCount: groupBuyForm.value.targetCount
    })
    if (data.success) {
      ElMessage.success(data.message)
      groupBuyVisible.value = false
      loadGroupBuys(detailGoods.value.id)
    } else {
      ElMessage.error(data.message || '发起失败')
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '发起失败，请检查后端服务')
  } finally {
    groupBuySubmitting.value = false
  }
}

function openJoin(gb) {
  const current = requireLogin()
  if (!current) return
  activeGroupBuy.value = gb
  joinForm.value.building = gb.building
  joinVisible.value = true
}

async function submitJoin() {
  const current = requireLogin()
  if (!current) return
  joining.value = true
  try {
    const { data } = await request.post(`/groupbuy/${activeGroupBuy.value.id}/join`, {
      userId: current.id,
      building: joinForm.value.building
    })
    if (data.success) {
      ElMessage.success(data.message)
      joinVisible.value = false
      loadGroupBuys(detailGoods.value.id)
    } else {
      ElMessage.error(data.message || '加入失败')
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '加入失败，请检查后端服务')
  } finally {
    joining.value = false
  }
}

// —— C4 以物换物 ——
const barterVisible = ref(false)
const barterForm = ref({ offerTitle: '', offerDesc: '' })
const barterSubmitting = ref(false)

function openBarter() {
  const current = requireLogin()
  if (!current) return
  if (detailGoods.value && current.id === detailGoods.value.userId) {
    ElMessage.warning('这是你自己发布的宝贝')
    return
  }
  barterForm.value = { offerTitle: '', offerDesc: '' }
  barterVisible.value = true
}

async function submitBarter() {
  const current = requireLogin()
  if (!current) return
  if (!barterForm.value.offerTitle.trim()) {
    ElMessage.warning('请填写你打算用什么来换')
    return
  }
  barterSubmitting.value = true
  try {
    const { data } = await request.post('/barter', {
      goodsId: detailGoods.value.id,
      requesterId: current.id,
      offerTitle: barterForm.value.offerTitle,
      offerDesc: barterForm.value.offerDesc
    })
    if (data.success) {
      ElMessage.success(data.message)
      barterVisible.value = false
    } else {
      ElMessage.error(data.message || '发起失败')
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '发起失败，请检查后端服务')
  } finally {
    barterSubmitting.value = false
  }
}

// 联系卖家：登录后才能聊，自己发布的宝贝不能聊自己
function contactSeller(goods) {
  const user = JSON.parse(localStorage.getItem('user') || 'null')
  if (!user) {
    ElMessage.warning('请先登录')
    setTimeout(() => router.push('/login'), 800)
    return
  }
  if (user.id === goods.userId) {
    ElMessage.warning('这是你自己发布的宝贝')
    return
  }
  detailVisible.value = false
  router.push({ path: '/messages', query: { goodsId: goods.id, peerId: goods.userId } })
}

function buyFromDetail() {
  detailVisible.value = false
  openBuy(detailGoods.value)
}

function openBuy(goods) {
  const user = JSON.parse(localStorage.getItem('user') || 'null')
  if (!user) {
    ElMessage.warning('请先登录')
    setTimeout(() => router.push('/login'), 800)
    return
  }
  buyGoods.value = goods
  selectedAddress.value = null
  buyForm.value = { receiver: '', phone: '', address: '' }
  addresses.value = JSON.parse(localStorage.getItem('addressList') || '[]')
  buyVisible.value = true
}

function chooseAddress(idx) {
  selectedAddress.value = idx
}

async function confirmOrder() {
  const user = JSON.parse(localStorage.getItem('user') || 'null')
  let receiver, phone, address

  if (selectedAddress.value !== null) {
    const addr = addresses.value[selectedAddress.value]
    receiver = addr.receiver
    phone = addr.phone
    address = addr.address
  } else {
    receiver = buyForm.value.receiver.trim()
    phone = buyForm.value.phone.trim()
    address = buyForm.value.address.trim()
    if (!receiver || !phone || !address) {
      ElMessage.warning('请选择收货地址或填写新地址')
      return
    }
  }

  try {
    const { data } = await request.post('/orders', {
      goodsId: buyGoods.value.id,
      buyerId: user.id,
      receiver,
      phone,
      address
    })
    if (data.success) {
      ElMessage.success({ message: data.message || '下单成功，去「我的订单」完成付款', duration: 2500 })
      buyVisible.value = false
      loadGoods()
    } else {
      ElMessage.error(data.message || '下单失败')
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '下单失败，请检查后端服务')
  }
}

onMounted(() => {
  loadGoods()
  openFromQuery()
})

// 从别处带 goodsId 跳过来时（如消息页的宝贝卡片），直接把详情弹窗打开
async function openFromQuery() {
  const goodsId = route.query.goodsId
  if (!goodsId) return
  try {
    const { data } = await request.get(`/goods/${goodsId}`)
    if (data && data.id) {
      showDetail(data)
    }
  } catch (e) {
    ElMessage.info('这件宝贝可能已经下架了')
  }
}
</script>

<template>
  <div class="home">
    <!-- 顶部黄色横幅 + 搜索（自定义校园元素） -->
    <section class="hero">
      <div class="hero-info">
        <h2 class="hero-title">同学，淘点好物再走吧 🎓</h2>
        <p class="hero-sub">校园里的二手宝贝 · 学长学姐放心转</p>
      </div>
      <div class="hero-search">
        <el-input v-model="keyword" placeholder="搜校园好物，比如 MacBook、高数书" size="large" clearable
          @keyup.enter="doSearch" @clear="doSearch" />
        <el-button type="primary" size="large" round @click="doSearch">搜一搜</el-button>
      </div>
    </section>

    <!-- 分类胶囊 -->
    <div class="cate-bar">
      <div v-for="c in categories" :key="c.name" class="cate-chip" :class="{ active: activeCategory === c.name }"
        @click="selectCategory(c.name)">
        <span class="cate-emoji">{{ c.emoji }}</span>
        <span>{{ c.name }}</span>
      </div>
    </div>

    <!-- 搜索链路提示：让"这次是怎么找到的"可见 -->
    <div v-if="keyword.trim() && searchMode" class="search-mode">
      <span class="sm-tag" :class="searchMode">
        {{ searchMode === 'semantic' ? '🤖 语义匹配 · 按意思找货' : '🔤 关键词匹配' }}
      </span>
      <span class="sm-count">共 {{ goodsList.length }} 件</span>
    </div>

    <!-- 宝贝卡片流 -->
    <div class="feed" v-loading="loading">
      <el-empty v-if="!loading && goodsList.length === 0"
        :description="keyword.trim() ? '没找到匹配的宝贝，换个说法试试' : '这个分类暂无宝贝，去看看别的吧'" />
      <div class="feed-grid">
        <div v-for="item in goodsList" :key="item.id" class="goods-card">
          <img class="gc-img" :src="getImageUrl(item.images?.[0]?.imageUrl)" alt="宝贝" @click="showDetail(item)">
          <div class="gc-body">
            <div class="gc-title" @click="showDetail(item)">{{ item.title }}</div>
            <div class="gc-tags">
              <span class="gc-tag gc-tag-school">🎓 校园面交</span>
              <span v-if="item.courseCode" class="gc-tag gc-tag-course">📚 {{ item.courseCode }}</span>
              <span v-else class="gc-tag gc-tag-student">学生自用</span>
            </div>
            <div class="gc-bottom">
              <div class="gc-price">¥<span class="gc-price-num">{{ item.price }}</span></div>
              <div class="gc-actions">
                <el-button size="small" round class="chat-btn" @click="contactSeller(item)">聊一聊</el-button>
                <el-button type="primary" size="small" round class="want-btn" @click="openBuy(item)">我想要</el-button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>

  <!-- 宝贝详情 -->
  <el-dialog v-model="detailVisible" title="宝贝详情" width="560px" round>
    <div v-if="detailGoods" class="detail">
      <img class="detail-img" :src="getImageUrl(detailGoods.images?.[0]?.imageUrl)" alt="宝贝">
      <div class="detail-info">
        <div class="detail-price">¥<span>{{ detailGoods.price }}</span></div>
        <div class="detail-title">{{ detailGoods.title }}</div>
        <div class="detail-tags">
          <span class="gc-tag gc-tag-school">🎓 校园面交</span>
          <span class="gc-tag gc-tag-cate">{{ detailGoods.category }}</span>
          <span v-if="detailGoods.courseCode" class="gc-tag gc-tag-course">📚 {{ detailGoods.courseCode }}</span>
        </div>
        <div class="detail-seller">
          卖家：{{ sellerName || '—' }}
          <el-tag v-if="sellerInfo?.verified" size="small" type="success" class="seller-tag">已实名</el-tag>
          <el-tag size="small" type="info" class="seller-tag">
            信用 {{ sellerInfo?.creditScore ?? 60 }} 分 · {{ sellerInfo?.creditLevel || '新用户' }}
          </el-tag>
        </div>
        <div class="detail-desc-title">宝贝描述</div>
        <p class="detail-desc">{{ detailGoods.description || '暂无描述' }}</p>

        <!-- C3 宿舍楼拼单：同一栋楼的同学凑人数 -->
        <el-collapse class="detail-extra">
          <el-collapse-item name="group">
            <template #title>
              <span class="extra-title">🏠 宿舍楼拼单</span>
              <span v-if="groupBuys.length" class="extra-badge">{{ groupBuys.length }} 个进行中</span>
            </template>
            <el-empty v-if="groupBuys.length === 0" description="这件宝贝还没有人发起拼单" :image-size="50" />
            <div v-for="gb in groupBuys" :key="gb.id" class="gb-item">
              <div class="gb-head">
                <span class="gb-building">🏢 {{ gb.building }}</span>
                <el-tag size="small" :type="gb.status === '拼单中' ? 'warning' : 'success'">{{ gb.status }}</el-tag>
              </div>
              <div class="gb-meta">
                发起人 {{ gb.initiatorName }} · 已参与 <b>{{ gb.joinedCount }}</b>/{{ gb.targetCount }} 人
              </div>
              <div class="gb-members">成员：{{ gb.members.map(m => m.name).join('、') }}</div>
              <el-button v-if="gb.status === '拼单中'" size="small" type="primary" plain
                @click="openJoin(gb)">我也要拼</el-button>
            </div>
            <el-button size="small" class="gb-create" @click="openGroupBuy">＋ 发起拼单</el-button>
          </el-collapse-item>
        </el-collapse>
      </div>
    </div>
    <template #footer>
      <el-button round @click="detailVisible = false">关闭</el-button>
      <el-button round @click="openBarter">🔄 换一换</el-button>
      <el-button round @click="contactSeller(detailGoods)">联系卖家</el-button>
      <el-button type="primary" round @click="buyFromDetail">我想要</el-button>
    </template>
  </el-dialog>

  <!-- C3 发起拼单 -->
  <el-dialog v-model="groupBuyVisible" title="发起宿舍楼拼单" width="420px">
    <div class="gb-form">
      <div class="gb-form-row">
        <span class="gb-label">我的楼栋</span>
        <el-select v-model="groupBuyForm.building" placeholder="选择你所在的宿舍楼" style="flex: 1">
          <el-option v-for="b in buildings" :key="b" :label="b" :value="b" />
        </el-select>
      </div>
      <div class="gb-form-row">
        <span class="gb-label">成团人数</span>
        <el-input-number v-model="groupBuyForm.targetCount" :min="2" :max="20" />
      </div>
      <p class="gb-note">拼单只对本栋楼的同学开放，人满自动成团。</p>
    </div>
    <template #footer>
      <el-button @click="groupBuyVisible = false">取消</el-button>
      <el-button type="primary" :loading="groupBuySubmitting" @click="submitGroupBuy">发起拼单</el-button>
    </template>
  </el-dialog>

  <!-- C3 参与拼单 -->
  <el-dialog v-model="joinVisible" title="参与拼单" width="400px">
    <div class="gb-form">
      <div class="gb-form-row">
        <span class="gb-label">我的楼栋</span>
        <el-select v-model="joinForm.building" placeholder="选择你所在的宿舍楼" style="flex: 1">
          <el-option v-for="b in buildings" :key="b" :label="b" :value="b" />
        </el-select>
      </div>
      <p class="gb-note">
        这个拼单限定「{{ activeGroupBuy?.building }}」，只能同栋楼的同学加入。
      </p>
    </div>
    <template #footer>
      <el-button @click="joinVisible = false">取消</el-button>
      <el-button type="primary" :loading="joining" @click="submitJoin">确认加入</el-button>
    </template>
  </el-dialog>

  <!-- C4 发起换物 -->
  <el-dialog v-model="barterVisible" title="以物换物" width="440px">
    <div class="gb-form">
      <div class="gb-form-row">
        <span class="gb-label">我拿什么换</span>
        <el-input v-model="barterForm.offerTitle" maxlength="40" show-word-limit
          placeholder="例如：九成新的 Kindle Paperwhite" style="flex: 1" />
      </div>
      <el-input v-model="barterForm.offerDesc" type="textarea" :rows="3" maxlength="300" show-word-limit
        placeholder="补充说明（选填）：成色、配件、为什么想换…" />
      <p class="gb-note">换物不涉及付款，对方同意后你们自行约时间交接。</p>
    </div>
    <template #footer>
      <el-button @click="barterVisible = false">取消</el-button>
      <el-button type="primary" :loading="barterSubmitting" @click="submitBarter">发出换物请求</el-button>
    </template>
  </el-dialog>

  <!-- 确认订单 -->
  <el-dialog v-model="buyVisible" title="确认订单" width="520px">
    <div v-if="buyGoods" class="order-goods">
      <img :src="getImageUrl(buyGoods.images?.[0]?.imageUrl)" alt="宝贝">
      <div class="order-goods-info">
        <div class="order-goods-title">{{ buyGoods.title }}</div>
        <div class="order-goods-price">¥{{ buyGoods.price }}</div>
      </div>
    </div>

    <h4 class="block-title">选择收货地址</h4>
    <div v-if="addresses.length === 0" class="addr-empty">暂无地址，请填写新地址</div>
    <div v-for="(addr, idx) in addresses" :key="idx" class="addr-item" :class="{ selected: selectedAddress === idx }"
      @click="chooseAddress(idx)">
      <div class="addr-name">{{ addr.receiver }} {{ addr.phone }}</div>
      <div class="addr-detail">{{ addr.address }}</div>
    </div>

    <h4 class="block-title">或输入新地址</h4>
    <el-input v-model="buyForm.receiver" placeholder="收货人" style="margin-bottom: 10px" />
    <el-input v-model="buyForm.phone" placeholder="联系电话" style="margin-bottom: 10px" />
    <el-input v-model="buyForm.address" type="textarea" placeholder="详细地址" />

    <template #footer>
      <el-button round @click="buyVisible = false">取消</el-button>
      <el-button type="primary" round @click="confirmOrder">确认付款</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.home { display: block; }

/* 顶部横幅 */
.hero {
  background: linear-gradient(120deg, #ffd21e 0%, #ffc400 60%, #ffb800 100%);
  border-radius: 16px;
  padding: 22px 26px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  flex-wrap: wrap;
  box-shadow: 0 4px 14px rgba(255, 184, 0, 0.3);
}
.hero-title { font-size: 22px; color: var(--ink-color); font-weight: bold; }
.hero-sub { font-size: 13px; color: #6b5400; margin-top: 6px; }
.hero-search { display: flex; gap: 10px; flex: 1; max-width: 460px; min-width: 280px; }
.search-mode {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 4px 0;
}
.sm-tag {
  font-size: 12px;
  padding: 3px 10px;
  border-radius: 12px;
  background: #fff3c2;
  color: #8a6100;
}
.sm-tag.semantic { background: #e8f3ff; color: #1a5fb4; }
.sm-count { font-size: 12px; color: #999; }
.gc-tag-course { background: #eaf7ea; color: #2e7d32; }

/* C3/C4 详情弹窗里的附加区 */
.detail-extra { margin-top: 12px; }
.extra-title { font-size: 14px; font-weight: 600; color: var(--ink-color); }
.extra-badge {
  margin-left: 8px;
  font-size: 11px;
  color: #8a6100;
  background: #fff3c2;
  border-radius: 8px;
  padding: 1px 8px;
}
.gb-item {
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 8px;
}
.gb-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.gb-building { font-weight: 600; font-size: 13px; color: var(--ink-color); }
.gb-meta { font-size: 12px; color: #888; margin: 5px 0 3px; }
.gb-members { font-size: 12px; color: #aaa; margin-bottom: 8px; }
.gb-create { margin-top: 4px; }
.gb-form-row { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.gb-label { width: 72px; font-size: 13px; color: #888; flex-shrink: 0; }
.gb-note { font-size: 12px; color: #bbb; line-height: 1.6; margin: 4px 0 0; }
.seller-tag { margin-left: 6px; }
.hero-search :deep(.el-input__wrapper) { border-radius: 20px; }

/* 分类胶囊 */
.cate-bar {
  display: flex;
  gap: 10px;
  margin: 16px 0 18px;
  flex-wrap: wrap;
}
.cate-chip {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  background: #fff;
  border-radius: 20px;
  font-size: 14px;
  color: #333;
  cursor: pointer;
  border: 1px solid #f0f0f0;
  transition: all 0.2s;
  user-select: none;
}
.cate-chip:hover { border-color: #ffd21e; background: #fffbea; }
.cate-chip.active {
  background: var(--ink-color);
  color: #ffd21e;
  border-color: var(--ink-color);
  font-weight: 600;
}
.cate-emoji { font-size: 15px; }

/* 宝贝卡片流 */
.feed { min-height: 300px; }
.feed-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(216px, 1fr));
  gap: 14px;
}
.goods-card {
  background: #fff;
  border-radius: 12px;
  overflow: hidden;
  cursor: pointer;
  transition: transform 0.2s, box-shadow 0.2s;
  border: 1px solid #f2f2f2;
}
.goods-card:hover {
  transform: translateY(-3px);
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.08);
}
.gc-img {
  width: 100%;
  height: 175px;
  object-fit: cover;
  display: block;
  background: #eee;
}
.gc-body { padding: 10px 12px 12px; }
.gc-title {
  font-size: 14px;
  color: #222;
  line-height: 1.4;
  height: 40px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  margin-bottom: 8px;
}
.gc-tags { display: flex; gap: 6px; margin-bottom: 8px; flex-wrap: wrap; }
.gc-tag {
  font-size: 11px;
  padding: 1px 7px;
  border-radius: 4px;
  line-height: 18px;
}
.gc-tag-school { background: #fff3c2; color: #8a6100; }
.gc-tag-student { background: #e8f7ee; color: #1a9e5c; }
.gc-tag-cate { background: #eef4ff; color: #3a6fd8; }
.gc-bottom { display: flex; justify-content: space-between; align-items: center; gap: 6px; }
.gc-actions { display: flex; gap: 6px; flex-shrink: 0; }
.gc-price { color: var(--price-color); font-weight: bold; font-size: 14px; white-space: nowrap; }
.gc-price-num { font-size: 19px; margin-left: 1px; }
.want-btn { padding: 6px 12px; }
.chat-btn { padding: 6px 10px; font-size: 12px; }

/* 详情弹窗 */
.detail { display: flex; gap: 16px; }
.detail-img { width: 200px; height: 200px; object-fit: cover; border-radius: 12px; flex-shrink: 0; background: #eee; }
.detail-info { flex: 1; }
.detail-price { color: var(--price-color); font-size: 16px; font-weight: bold; margin-bottom: 8px; }
.detail-price span { font-size: 24px; }
.detail-title { font-size: 17px; font-weight: bold; margin-bottom: 10px; line-height: 1.4; }
.detail-tags { display: flex; gap: 6px; margin-bottom: 10px; }
.detail-seller { font-size: 13px; color: #666; margin-bottom: 12px; }
.detail-desc-title { font-size: 13px; color: #666; margin-bottom: 6px; }
.detail-desc { font-size: 14px; line-height: 1.6; color: #333; }

/* 确认订单弹窗 */
.order-goods {
  background: #fffbea;
  padding: 12px;
  border-radius: 10px;
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}
.order-goods img { width: 72px; height: 72px; object-fit: cover; border-radius: 8px; }
.order-goods-title { font-weight: bold; margin-bottom: 6px; }
.order-goods-price { color: var(--price-color); font-size: 17px; font-weight: bold; }
.block-title { margin: 14px 0 8px; font-size: 14px; }
.addr-empty { color: #999; font-size: 13px; margin-bottom: 8px; }
.addr-item {
  padding: 10px;
  border: 1px solid #ddd;
  border-radius: 10px;
  margin-bottom: 8px;
  cursor: pointer;
}
.addr-item:hover { border-color: #ffd21e; }
.addr-item.selected { border-color: #ffd21e; background: #fffbea; }
.addr-name { font-weight: bold; }
.addr-detail { font-size: 13px; color: #666; margin-top: 4px; }
</style>
