<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api'

const router = useRouter()
const user = JSON.parse(localStorage.getItem('user') || 'null')

const categories = ['不限', '电子产品', '图书教材', '生活用品', '运动户外', '二手家具', '美妆护肤']

const form = ref({ title: '', category: '不限', maxPrice: '', description: '' })
const publishing = ref(false)
// 发布后立刻展示首轮匹配结果：求购的价值就在"当场看到有没有货"
const justMatched = ref([])
const justMessage = ref('')

const openList = ref([])
const myList = ref([])
const loading = ref(false)
const matches = ref({})      // { [wantedId]: [goods...] }
const loadingMatch = ref(0)

function getImageUrl(path) {
  if (!path) return ''
  return path.startsWith('http') ? path : path
}

function formatTime(t) {
  if (!t) return ''
  return String(t).replace('T', ' ').slice(0, 16)
}

async function loadWanted() {
  loading.value = true
  try {
    const [openResp, mineResp] = await Promise.all([
      request.get('/wanted/open'),
      user ? request.get('/wanted', { params: { userId: user.id } }) : Promise.resolve({ data: { data: [] } })
    ])
    // 广场里去掉自己发的，避免重复展示
    openList.value = (openResp.data.data || []).filter(w => w.userId !== user?.id)
    myList.value = mineResp.data.data || []
  } catch (e) {
    ElMessage.error('求购列表加载失败')
  } finally {
    loading.value = false
  }
}

async function publish() {
  if (!user) {
    ElMessage.warning('请先登录再发布求购')
    router.push('/login')
    return
  }
  if (!form.value.title.trim()) {
    ElMessage.warning('请填写想求购什么')
    return
  }
  publishing.value = true
  try {
    const { data } = await request.post('/wanted', {
      userId: user.id,
      title: form.value.title,
      category: form.value.category,
      maxPrice: form.value.maxPrice,
      description: form.value.description
    })
    if (data.success) {
      ElMessage.success(data.message)
      justMessage.value = data.message
      justMatched.value = data.matches || []
      form.value = { title: '', category: '不限', maxPrice: '', description: '' }
      await loadWanted()
    } else {
      ElMessage.error(data.message || '发布失败')
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '发布失败，请检查后端服务')
  } finally {
    publishing.value = false
  }
}

async function findMatches(wanted) {
  loadingMatch.value = wanted.id
  try {
    const { data } = await request.get(`/wanted/${wanted.id}/matches`)
    matches.value = { ...matches.value, [wanted.id]: data.data || [] }
    if (!data.data?.length) {
      ElMessage.info('暂时没有匹配的在售商品，等有新货上架再来看看')
    }
  } catch (e) {
    ElMessage.error('匹配失败，请检查后端服务')
  } finally {
    loadingMatch.value = 0
  }
}

async function closeWanted(wanted) {
  try {
    await ElMessageBox.confirm('关闭后不再参与撮合，确定吗？', '关闭求购', { type: 'warning' })
  } catch {
    return
  }
  try {
    const { data } = await request.post(`/wanted/${wanted.id}/close`, { userId: user.id })
    if (data.success) {
      ElMessage.success(data.message)
      await loadWanted()
    } else {
      ElMessage.error(data.message || '关闭失败')
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '关闭失败')
  }
}

// 匹配到商品后直接找卖家聊，撮合闭环
function chatWithSeller(goods) {
  if (!user) {
    ElMessage.warning('请先登录')
    router.push('/login')
    return
  }
  router.push({ path: '/messages', query: { goodsId: goods.id, peerId: goods.userId } })
}

onMounted(loadWanted)
</script>

<template>
  <div class="wanted-page">
    <!-- 发布求购 -->
    <el-card class="post-card" shadow="never">
      <h2 class="post-title">我要求购 🎯</h2>
      <p class="post-sub">写下你想要的东西，系统会反向去在售的宝贝里帮你找。</p>
      <div class="form-row">
        <el-input v-model="form.title" maxlength="40" show-word-limit
          placeholder="想求购什么，例如：能降噪的耳机" />
      </div>
      <div class="form-row">
        <el-select v-model="form.category" style="width: 160px">
          <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
        </el-select>
        <el-input v-model="form.maxPrice" style="width: 180px" placeholder="预算上限（选填）">
          <template #prepend>¥</template>
        </el-input>
      </div>
      <div class="form-row">
        <el-input v-model="form.description" type="textarea" :rows="2" maxlength="300" show-word-limit
          placeholder="补充说明（选填），例如：成色九五新以上，能当面交易" />
      </div>
      <el-button type="primary" :loading="publishing" @click="publish">发布求购</el-button>

      <div v-if="justMessage" class="matched-box">
        <div class="matched-head">🤖 {{ justMessage }}</div>
        <div v-if="justMatched.length" class="goods-grid">
          <div v-for="g in justMatched" :key="g.id" class="goods-card">
            <img :src="getImageUrl(g.images?.[0]?.imageUrl)" alt="宝贝">
            <div class="gc-title">{{ g.title }}</div>
            <div class="gc-price">¥{{ g.price }}</div>
            <el-button size="small" round @click="chatWithSeller(g)">聊一聊</el-button>
          </div>
        </div>
      </div>
    </el-card>

    <!-- 我的求购 -->
    <el-card v-if="myList.length" class="section-card" shadow="never">
      <div class="section-header"><h3>我的求购</h3></div>
      <div v-for="w in myList" :key="w.id" class="wanted-item">
        <div class="wanted-head">
          <span class="wanted-title">{{ w.title }}</span>
          <el-tag size="small" :type="w.status === '进行中' ? 'success' : 'info'">{{ w.status }}</el-tag>
        </div>
        <div class="wanted-meta">
          {{ w.category || '不限分类' }} · 预算 {{ w.maxPrice ? '¥' + w.maxPrice : '不限' }}
          · {{ formatTime(w.createdAt) }}
        </div>
        <div class="wanted-desc" v-if="w.description">{{ w.description }}</div>
        <div class="wanted-actions">
          <el-button size="small" type="primary" plain :loading="loadingMatch === w.id"
            @click="findMatches(w)">找找看匹配的宝贝</el-button>
          <el-button v-if="w.status === '进行中'" size="small" @click="closeWanted(w)">关闭求购</el-button>
        </div>
        <div v-if="matches[w.id]" class="goods-grid">
          <el-empty v-if="matches[w.id].length === 0" description="暂无匹配的宝贝" :image-size="60" />
          <div v-for="g in matches[w.id]" :key="g.id" class="goods-card">
            <img :src="getImageUrl(g.images?.[0]?.imageUrl)" alt="宝贝">
            <div class="gc-title">{{ g.title }}</div>
            <div class="gc-price">¥{{ g.price }}</div>
            <el-button size="small" round @click="chatWithSeller(g)">聊一聊</el-button>
          </div>
        </div>
      </div>
    </el-card>

    <!-- 求购广场 -->
    <el-card class="section-card" shadow="never" v-loading="loading">
      <div class="section-header">
        <h3>求购广场</h3>
        <span class="section-hint">同学在找这些宝贝，手上有货可以直接去找他聊</span>
      </div>
      <el-empty v-if="!loading && openList.length === 0" description="还没有别人发布的求购" />
      <div v-for="w in openList" :key="w.id" class="wanted-item">
        <div class="wanted-head">
          <span class="wanted-title">{{ w.title }}</span>
          <el-tag size="small" type="warning">{{ w.category || '不限' }}</el-tag>
        </div>
        <div class="wanted-meta">
          {{ w.userName }} 求购 · 预算 {{ w.maxPrice ? '¥' + w.maxPrice : '不限' }}
          · {{ formatTime(w.createdAt) }}
        </div>
        <div class="wanted-desc" v-if="w.description">{{ w.description }}</div>
        <div class="wanted-actions">
          <el-button size="small" plain :loading="loadingMatch === w.id"
            @click="findMatches(w)">看看有哪些在售的</el-button>
        </div>
        <div v-if="matches[w.id]" class="goods-grid">
          <el-empty v-if="matches[w.id].length === 0" description="目前没有匹配的在售商品" :image-size="60" />
          <div v-for="g in matches[w.id]" :key="g.id" class="goods-card">
            <img :src="getImageUrl(g.images?.[0]?.imageUrl)" alt="宝贝">
            <div class="gc-title">{{ g.title }}</div>
            <div class="gc-price">¥{{ g.price }}</div>
            <el-button size="small" round @click="chatWithSeller(g)">聊一聊</el-button>
          </div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.wanted-page { max-width: 900px; margin: 0 auto; }
.post-card { margin-bottom: 16px; }
.post-title { margin: 0 0 6px; font-size: 18px; color: var(--ink-color); }
.post-sub { margin: 0 0 14px; font-size: 12px; color: #999; }
.form-row { display: flex; gap: 10px; margin-bottom: 10px; }
.form-row :deep(.el-input) { flex: 1; }
.section-card { margin-bottom: 16px; }
.section-header { display: flex; align-items: baseline; gap: 10px; margin-bottom: 12px; }
.section-header h3 { margin: 0; font-size: 15px; color: var(--ink-color); }
.section-hint { font-size: 12px; color: #bbb; }
.matched-box {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed #eee;
}
.matched-head { font-size: 13px; color: #1a5fb4; margin-bottom: 10px; }
.wanted-item {
  border: 1px solid #f0f0f0;
  border-radius: 10px;
  padding: 12px 14px;
  margin-bottom: 12px;
}
.wanted-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.wanted-title { font-weight: 600; color: var(--ink-color); }
.wanted-meta { font-size: 12px; color: #999; margin: 6px 0; }
.wanted-desc { font-size: 13px; color: #666; line-height: 1.6; margin-bottom: 8px; }
.wanted-actions { display: flex; gap: 8px; }
.goods-grid { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 12px; }
.goods-card {
  width: 150px;
  border: 1px solid #f0f0f0;
  border-radius: 10px;
  padding: 8px;
  text-align: center;
}
.goods-card > img { width: 100%; height: 96px; object-fit: cover; border-radius: 6px; margin-bottom: 6px; }
.gc-title {
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.gc-price { color: var(--price-color); font-weight: bold; margin-bottom: 6px; }
</style>
