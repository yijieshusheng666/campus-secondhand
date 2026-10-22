<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useApiRequest } from '../composables/useApiRequest'
import request from '../api'
import { CATEGORIES } from '../constants/goods'
import { useCurrentUser } from '../composables/useCurrentUser'
import { categoryIcon } from '../icons'
import { formatTime } from '../utils/format'

const route = useRoute()
const router = useRouter()

// 统一错误提示口径：网络/HTTP 错误由 useApiRequest 自动 ElMessage.error 并抛异常
const api = useApiRequest('操作失败，请检查后端服务')

// 分类胶囊（图标见 icons.js；某个分类还没图时回退成 CATEGORIES 里的 emoji）
const categories = [{ name: '全部', emoji: '✨' }, ...CATEGORIES]

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


// —— B3 商品评价：详情页展示这件宝贝收到的评分与评价 ——
const EMPTY_REVIEW_SUMMARY = { count: 0, avgScore: 0, fromBuyers: 0, fromSellers: 0 }
const goodsReviews = ref({ summary: { ...EMPTY_REVIEW_SUMMARY }, data: [] })

async function loadGoodsReviews(goodsId) {
  try {
    // silent：拿不到评价不影响详情展示，不该为此弹一条错误提示
    const { data } = await api.get(`/reviews/goods/${goodsId}`, { silent: true })
    goodsReviews.value = {
      summary: data.summary || { ...EMPTY_REVIEW_SUMMARY },
      data: data.data || []
    }
  } catch (e) {
    goodsReviews.value = { summary: { ...EMPTY_REVIEW_SUMMARY }, data: [] }
  }
}

async function loadGoods() {
  loading.value = true
  try {
    const params = {}
    if (activeCategory.value !== '全部') params.category = activeCategory.value
    if (keyword.value.trim()) params.keyword = keyword.value.trim()
    // silent：下面 catch 里已经有更具体的中文提示，不 silent 会弹两次
    const { data } = await api.get('/goods', { params, silent: true })
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
    // silent：AI 服务不可用时本来就要静默回退关键词检索，
    // 不 silent 的话每次回退都会弹一条「操作失败」，而功能其实正常
    const { data } = await api.get('/ai/search', { params, silent: true })
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
  goodsReviews.value = { summary: { ...EMPTY_REVIEW_SUMMARY }, data: [] }
  try {
    const { data } = await request.get(`/users/${goods.userId}`)
    sellerName.value = data.username
    // B1/B3：卖家实名标识与信用分（接口只返回脱敏姓名）
    sellerInfo.value = data
  } catch (e) {
    // 卖家信息拿不到不影响详情展示
  }
  // B3：这件宝贝收到的评价
  loadGoodsReviews(goods.id)
}

// 联系卖家：登录后才能聊，自己发布的宝贝不能聊自己
function contactSeller(goods) {
  const user = useCurrentUser()
  if (!user.value) {
    ElMessage.warning('请先登录')
    setTimeout(() => router.push('/login'), 800)
    return
  }
  if (user.value.id === goods.userId) {
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
  const user = useCurrentUser()
  if (!user.value) {
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
  const user = useCurrentUser()
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
    const { data } = await api.post('/orders', {
      goodsId: buyGoods.value.id,
      buyerId: user.value.id,
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
    // useApiRequest 已统一提示（或当前是 request.* 直调，由后端 message 自动展示）
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
    const { data } = await api.get(`/goods/${goodsId}`, { silent: true })
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
        <img  loading="lazy" v-if="categoryIcon(c.name)" class="cate-icon" :src="categoryIcon(c.name)" :alt="c.name">
        <span v-else class="cate-emoji">{{ c.emoji }}</span>
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
          <img v-img-fallback loading="lazy" class="gc-img" :src="item.images?.[0]?.imageUrl" alt="宝贝" @click="showDetail(item)">
          <div class="gc-body">
            <div class="gc-title" @click="showDetail(item)">{{ item.title }}</div>
            <div class="gc-tags">
              <span class="gc-tag gc-tag-school">🎓 校园面交</span>
              <span class="gc-tag gc-tag-student">学生自用</span>
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
      <img v-img-fallback loading="lazy" class="detail-img" :src="detailGoods.images?.[0]?.imageUrl" alt="宝贝">
      <div class="detail-info">
        <div class="detail-price">¥<span>{{ detailGoods.price }}</span></div>
        <div class="detail-title">{{ detailGoods.title }}</div>
        <div class="detail-tags">
          <span class="gc-tag gc-tag-school">🎓 校园面交</span>
          <span class="gc-tag gc-tag-cate">{{ detailGoods.category }}</span>
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

        <el-collapse class="detail-extra">
        <!-- B3 商品评价：买家评卖家 + 卖家评买家，双向可见 -->
          <el-collapse-item name="review">
            <template #title>
              <span class="extra-title">💬 商品评价</span>
              <span v-if="goodsReviews.summary.count" class="extra-badge score">
                ★{{ goodsReviews.summary.avgScore }} · {{ goodsReviews.summary.count }} 条
              </span>
              <span v-else class="extra-badge">暂无评价</span>
            </template>

            <el-empty v-if="goodsReviews.data.length === 0"
              description="这件宝贝还没有人评价，交易完成后可在这里看到评价" :image-size="50" />

            <div v-for="r in goodsReviews.data" :key="r.id" class="rv-item">
              <div class="rv-head">
                <span class="rv-name">{{ r.reviewerName }}</span>
                <el-tag size="small" :type="r.role === 'buyer' ? 'success' : 'warning'">
                  {{ r.role === 'buyer' ? '买家评价卖家' : '卖家评价买家' }}
                </el-tag>
                <el-rate :model-value="r.score" disabled size="small" />
              </div>
              <div class="rv-text">{{ r.content || '这位同学没有填写评价内容' }}</div>
              <div class="rv-time">{{ formatTime(r.createdAt, 'minute') }}</div>
            </div>

            <div class="rv-foot">
              <span v-if="goodsReviews.summary.count" class="rv-stat">
                买家评价 {{ goodsReviews.summary.fromBuyers }} 条 ·
                卖家评价 {{ goodsReviews.summary.fromSellers }} 条
              </span>
              <el-button size="small" type="primary" plain @click="router.push('/orders')">
                去我的订单评价
              </el-button>
            </div>
          </el-collapse-item>
        </el-collapse>
      </div>
    </div>
    <template #footer>
      <el-button round @click="detailVisible = false">关闭</el-button>
      <el-button round @click="contactSeller(detailGoods)">联系卖家</el-button>
      <el-button type="primary" round @click="buyFromDetail">我想要</el-button>
    </template>
  </el-dialog>

  <!-- 确认订单 -->
  <el-dialog v-model="buyVisible" title="确认订单" width="520px">
    <div v-if="buyGoods" class="order-goods">
      <img v-img-fallback loading="lazy" :src="buyGoods.images?.[0]?.imageUrl" alt="宝贝">
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
/* B3 商品评价 */
.rv-item { padding: 10px 0; border-top: 1px solid #f0f0f0; }
.rv-item:first-of-type { border-top: none; }
.rv-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.rv-name { font-size: 13px; font-weight: 600; color: var(--ink-color); }
.rv-text { font-size: 13px; color: #555; line-height: 1.6; margin: 6px 0 4px; }
.rv-time { font-size: 11px; color: #bbb; }
.rv-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed #eee;
}
.rv-stat { font-size: 12px; color: #999; }
.extra-badge.score { background: #fff3c2; color: #8a6100; }
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
/* 分类图标：图片本身已带圆角透明，深浅底色都不露白角；高度和文字行高对齐，
   这样「全部」（emoji 兜底）和带图标的胶囊高度一致 */
.cate-icon {
  width: 20px;
  height: 20px;
  object-fit: cover;
  display: block;
  flex-shrink: 0;
}
.cate-emoji { font-size: 15px; line-height: 20px; }

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

