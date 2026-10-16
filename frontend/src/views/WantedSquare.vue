<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useApiRequest } from '../composables/useApiRequest'
import { useCurrentUser } from '../composables/useCurrentUser'
import SectionTabs from '../components/SectionTabs.vue'

const router = useRouter()
const user = useCurrentUser()

// 统一错误提示口径：网络/HTTP 错误由 useApiRequest 自动 ElMessage.error 并抛异常
const squareApi = useApiRequest('求购广场加载失败，请重试')
const matchApi = useApiRequest('匹配失败，请重试')

const openList = ref([])
const loading = ref(false)
const loadingMatch = ref(0)
const matches = ref({})      // { [wantedId]: [goods...] }
const matchMeta = ref({})    // { [wantedId]: { scanned, semanticCount, reasons } }


function formatTime(t) {
  if (!t) return ''
  return String(t).replace('T', ' ').slice(0, 16)
}

async function loadSquare() {
  loading.value = true
  try {
    // 广场展示所有进行中的求购，自己发的也保留（并打标）
    const { data } = await squareApi.get('/wanted/open')
    openList.value = data.data || []
  } catch {
    // useApiRequest 已统一提示过网络/HTTP 错误
  } finally {
    loading.value = false
  }
}

async function findMatches(wanted) {
  loadingMatch.value = wanted.id
  try {
    const { data } = await matchApi.get(`/wanted/${wanted.id}/matches`)
    matches.value = { ...matches.value, [wanted.id]: data.data || [] }
    const reasons = {}
    for (const info of data.matchInfo || []) {
      reasons[info.goodsId] = info.reason
    }
    matchMeta.value = {
      ...matchMeta.value,
      [wanted.id]: { scanned: data.scanned, semanticCount: data.semanticCount, reasons }
    }
    if (!data.data?.length) {
      ElMessage.info(`已比对站上 ${data.scanned} 件商品，暂时没有能买的匹配项`)
    }
  } catch {
    // 同上
  } finally {
    loadingMatch.value = 0
  }
}

function chatWithSeller(goods) {
  if (!user.value) {
    ElMessage.warning('请先登录')
    router.push('/login')
    return
  }
  router.push({ path: '/messages', query: { goodsId: goods.id, peerId: goods.userId } })
}

onMounted(loadSquare)
</script>

<template>
  <div class="square-page">
    <SectionTabs active="square" />

    <el-card class="section-card" shadow="never" v-loading="loading">
      <div class="section-header">
        <h3>求购广场</h3>
        <span class="section-hint">
          同学们在找这些宝贝。手上有货，可以用「看看有哪些在售的」自检，或直接去「我要求购」发布自己的需求。
        </span>
      </div>

      <el-empty v-if="!loading && openList.length === 0" description="还没有人发布求购，去「我要求购」发一条吧" />

      <div v-for="w in openList" :key="w.id" class="wanted-item">
        <div class="wanted-head">
          <span class="wanted-title">{{ w.title }}</span>
          <div class="wanted-tags">
            <el-tag v-if="user && w.userId === user.id" size="small" type="success">我发布的</el-tag>
            <el-tag size="small" type="warning">{{ w.category || '不限' }}</el-tag>
          </div>
        </div>
        <div class="wanted-meta">
          {{ w.userName }} 求购 · 预算 {{ w.maxPrice ? '¥' + w.maxPrice : '不限' }}
          · {{ formatTime(w.createdAt) }}
        </div>
        <div class="wanted-desc" v-if="w.description">{{ w.description }}</div>
        <div class="wanted-actions">
          <el-button size="small" plain :loading="loadingMatch === w.id" @click="findMatches(w)">
            看看有哪些在售的
          </el-button>
        </div>

        <!-- 匹配依据：让他自己核对"是不是真的匹配了站上所有商品" -->
        <div v-if="matches[w.id]" class="match-meta">
          已比对站上 <b>{{ matchMeta[w.id]?.scanned }}</b> 件商品
          （语义候选 {{ matchMeta[w.id]?.semanticCount }} 件），
          其中 <b>{{ matches[w.id].length }}</b> 件可买并入选
        </div>
        <div v-if="matches[w.id]" class="goods-grid">
          <el-empty v-if="matches[w.id].length === 0" description="目前没有匹配且在售的商品" :image-size="60" />
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
.square-page { max-width: 900px; margin: 0 auto; }
.section-card { margin-bottom: 16px; }
.section-header { display: flex; align-items: baseline; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.section-header h3 { margin: 0; font-size: 15px; color: var(--ink-color); }
.section-hint { font-size: 12px; color: #bbb; }
.wanted-item {
  border: 1px solid #f0f0f0;
  border-radius: 10px;
  padding: 12px 14px;
  margin-bottom: 12px;
}
.wanted-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.wanted-title { font-weight: 600; color: var(--ink-color); }
.wanted-tags { display: flex; gap: 6px; }
.wanted-meta { font-size: 12px; color: #999; margin: 6px 0; }
.wanted-desc { font-size: 13px; color: #666; line-height: 1.6; margin-bottom: 8px; }
.wanted-actions { display: flex; gap: 8px; }
.match-meta {
  margin-top: 12px;
  font-size: 12px;
  color: #1a5fb4;
  background: #e8f3ff;
  border-radius: 8px;
  padding: 6px 10px;
}
.goods-grid { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 12px; }
.goods-card {
  width: 160px;
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
.gc-price { color: var(--price-color); font-weight: bold; margin-bottom: 4px; }
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
