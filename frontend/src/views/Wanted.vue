<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useApiRequest } from '../composables/useApiRequest'
import { CATEGORY_NAMES } from '../constants/goods'
import { useCurrentUser } from '../composables/useCurrentUser'
import SectionTabs from '../components/SectionTabs.vue'
import { formatTime } from '../utils/format'

const router = useRouter()
const user = useCurrentUser()

// 统一错误提示口径：网络/HTTP 错误由 useApiRequest 自动 ElMessage.error 并抛异常
const wantedApi = useApiRequest('求购操作失败')

const categories = ['不限', ...CATEGORY_NAMES]

const form = ref({ title: '', category: '不限', maxPrice: '', description: '' })
const publishing = ref(false)
// 发布后立刻展示首轮匹配结果：求购的价值就在"当场看到有没有货"
const justMatched = ref([])
const justMessage = ref('')
const justMeta = ref(null)

const myList = ref([])
const loading = ref(false)
const matches = ref({})      // { [wantedId]: [goods...] }
const matchMeta = ref({})    // { [wantedId]: { scanned, semanticCount, reasons } }
const loadingMatch = ref(0)


// 把后端返回的匹配依据整理成 { goodsId: reason }，供卡片展示
function collectMeta(data) {
  const reasons = {}
  for (const info of data.matchInfo || []) {
    reasons[info.goodsId] = info.reason
  }
  return { scanned: data.scanned, semanticCount: data.semanticCount, reasons }
}

async function loadWanted() {
  loading.value = true
  try {
    const mineResp = user.value
      ? await wantedApi.get('/wanted', { params: { userId: user.value.id } })
      : { data: { data: [] } }
    myList.value = mineResp.data.data || []
  } catch (e) {
    // useApiRequest 已统一提示
  } finally {
    loading.value = false
  }
}

async function publish() {
  if (!user.value) {
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
    const { data } = await wantedApi.post('/wanted', {
      userId: user.value.id,
      title: form.value.title,
      category: form.value.category,
      maxPrice: form.value.maxPrice,
      description: form.value.description
    })
    if (data.success) {
      ElMessage.success(data.message)
      justMessage.value = data.message
      justMatched.value = data.matches || []
      justMeta.value = collectMeta(data)
      form.value = { title: '', category: '不限', maxPrice: '', description: '' }
      await loadWanted()
    } else {
      ElMessage.error(data.message || '发布失败')
    }
  } catch (e) {
    // useApiRequest 已统一提示
  } finally {
    publishing.value = false
  }
}

async function findMatches(wanted) {
  loadingMatch.value = wanted.id
  try {
    const { data } = await wantedApi.get(`/wanted/${wanted.id}/matches`)
    matches.value = { ...matches.value, [wanted.id]: data.data || [] }
    matchMeta.value = { ...matchMeta.value, [wanted.id]: collectMeta(data) }
    if (!data.data?.length) {
      ElMessage.info(`已比对站上 ${data.scanned} 件商品，暂时没有能买的匹配项`)
    }
  } catch (e) {
    // useApiRequest 已统一提示
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
    const { data } = await wantedApi.post(`/wanted/${wanted.id}/close`, { userId: user.value.id })
    if (data.success) {
      ElMessage.success(data.message)
      await loadWanted()
    } else {
      ElMessage.error(data.message || '关闭失败')
    }
  } catch (e) {
    // useApiRequest 已统一提示
  }
}

// 匹配到商品后直接找卖家聊，撮合闭环
function chatWithSeller(goods) {
  if (!user.value) {
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
    <SectionTabs active="mine" />

    <!-- 发布求购 -->
    <el-card class="post-card" shadow="never">
      <h2 class="post-title">我要求购</h2>
      <p class="post-sub">写下你想要的东西，系统会拿它去比对站上全部商品，把能买的挑出来。</p>
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
        <div v-if="justMeta" class="match-meta">
          比对范围：站上 <b>{{ justMeta.scanned }}</b> 件商品（语义候选
          {{ justMeta.semanticCount }} 件），其中 <b>{{ justMatched.length }}</b> 件在售且满足分类/预算
        </div>
        <div v-if="justMatched.length" class="goods-grid">
          <div v-for="g in justMatched" :key="g.id" class="goods-card">
            <img v-img-fallback loading="lazy" :src="g.images?.[0]?.imageUrl" alt="宝贝">
            <div class="gc-title">{{ g.title }}</div>
            <div class="gc-price">¥{{ g.price }}</div>
            <div v-if="justMeta?.reasons?.[g.id]" class="gc-reason">{{ justMeta.reasons[g.id] }}</div>
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
          · {{ formatTime(w.createdAt, 'minute') }}
        </div>
        <div class="wanted-desc" v-if="w.description">{{ w.description }}</div>
        <div class="wanted-actions">
          <el-button size="small" type="primary" plain :loading="loadingMatch === w.id"
            @click="findMatches(w)">找找看匹配的宝贝</el-button>
          <el-button v-if="w.status === '进行中'" size="small" @click="closeWanted(w)">关闭求购</el-button>
        </div>
        <div v-if="matches[w.id]" class="match-meta">
          比对范围：站上 <b>{{ matchMeta[w.id]?.scanned }}</b> 件商品（语义候选
          {{ matchMeta[w.id]?.semanticCount }} 件），其中 <b>{{ matches[w.id].length }}</b> 件入选
        </div>
        <div v-if="matches[w.id]" class="goods-grid">
          <el-empty v-if="matches[w.id].length === 0" description="暂无匹配且在售的宝贝" :image-size="60" />
          <div v-for="g in matches[w.id]" :key="g.id" class="goods-card">
            <img v-img-fallback loading="lazy" :src="g.images?.[0]?.imageUrl" alt="宝贝">
            <div class="gc-title">{{ g.title }}</div>
            <div class="gc-price">¥{{ g.price }}</div>
            <div v-if="matchMeta[w.id]?.reasons?.[g.id]" class="gc-reason">
              {{ matchMeta[w.id].reasons[g.id] }}
            </div>
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
.match-meta {
  margin-top: 12px;
  font-size: 12px;
  color: #1a5fb4;
  background: #e8f3ff;
  border-radius: 8px;
  padding: 6px 10px;
}
.gc-reason {
  font-size: 11px;
  color: #1a5fb4;
  background: #f2f7ff;
  border-radius: 6px;
  padding: 3px 6px;
  margin-bottom: 6px;
  line-height: 1.5;
  text-align: left;
}
</style>
