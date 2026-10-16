<script setup>
/**
 * 平台概览
 *
 * <p>数据来源全是已有接口，不新增后端：
 *   - GET /api/goods          → 在售商品总量、分类分布、价格区间
 *   - GET /api/ai/ops/stats/summary → 助手问答量、成功率、拒答率、知识条目数
 * AI 服务不可用时只降级 AI 那几张卡，商品指标照常展示，
 * 而不是整页报错 —— 运营打开管理端第一眼看到报错是最差的体验。</p>
 */
import { ref, computed, onMounted } from 'vue'
import request from '../../api'

const goods = ref([])
const ai = ref(null)
const aiOffline = ref(false)
const loading = ref(true)

async function load() {
  loading.value = true
  const [g, a] = await Promise.allSettled([
    request.get('/goods'),
    // 不写 /api 前缀：axios 实例的 baseURL 已经是 '/api'（见 src/api.js）
    request.get('/ai/ops/stats/summary')
  ])
  goods.value = g.status === 'fulfilled' && Array.isArray(g.value.data) ? g.value.data : []
  if (a.status === 'fulfilled' && a.value.data?.available !== false) {
    ai.value = a.value.data.data || null
    aiOffline.value = false
  } else {
    ai.value = null
    aiOffline.value = true
  }
  loading.value = false
}

const total = computed(() => goods.value.length)

const activeCount = computed(() => goods.value.filter(g => g.status !== '已售出' && g.status !== '已下架').length)

const priceStat = computed(() => {
  const ps = goods.value.map(g => Number(g.price)).filter(n => Number.isFinite(n) && n > 0).sort((x, y) => x - y)
  if (!ps.length) return null
  const sum = ps.reduce((a, b) => a + b, 0)
  return {
    min: ps[0],
    max: ps[ps.length - 1],
    avg: Math.round(sum / ps.length),
    median: ps[Math.floor(ps.length / 2)]
  }
})

/** 分类分布：取前 6 类，其余归入「其他」，避免长尾把图表撑爆 */
const byCategory = computed(() => {
  const m = new Map()
  goods.value.forEach(g => {
    const k = g.category || '未分类'
    m.set(k, (m.get(k) || 0) + 1)
  })
  const all = [...m.entries()].sort((a, b) => b[1] - a[1])
  const head = all.slice(0, 6)
  const rest = all.slice(6)
  const rows = head.map(([name, value]) => ({ name, value }))
  if (rest.length) rows.push({ name: `其他 ${rest.length} 类`, value: rest.reduce((s, r) => s + r[1], 0) })
  return rows
})

const maxCat = computed(() => Math.max(1, ...byCategory.value.map(r => r.value)))

const pct = v => `${Math.round((v || 0) * 100)}%`

const cards = computed(() => {
  const s = ai.value
  return [
    { label: '商品总数', value: total.value, hint: `在售 ${activeCount.value}` },
    { label: '均价', value: priceStat.value ? `¥${priceStat.value.avg}` : '—', hint: priceStat.value ? `¥${priceStat.value.min} ~ ¥${priceStat.value.max}` : '暂无数据' },
    { label: '助手问答', value: s ? s.total ?? 0 : '—', hint: s ? `成功率 ${pct(s.successRate)}` : aiOffline.value ? 'AI 服务未启动' : '加载中' },
    { label: '知识条目', value: s ? s.knowledgeCount ?? 0 : '—', hint: s ? `${s.embeddingMode || '-'} 索引` : aiOffline.value ? 'AI 服务未启动' : '加载中' }
  ]
})

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="stat-row">
      <el-card v-for="c in cards" :key="c.label" shadow="never" class="stat-card">
        <div class="stat-value">{{ c.value }}</div>
        <div class="stat-label">{{ c.label }}</div>
        <div class="stat-hint">{{ c.hint }}</div>
      </el-card>
    </div>

    <el-alert
      v-if="aiOffline" type="warning" show-icon :closable="false"
      title="AI 服务未运行，助手相关指标不可用"
      description="启动 ai-service（8000 端口）后刷新本页即可。商品指标不受影响。"
      class="mb"
    />

    <el-row :gutter="12">
      <el-col :xs="24" :lg="14">
        <el-card shadow="never" class="mb">
          <div class="card-head"><h3>商品分类分布</h3></div>
          <el-empty v-if="!byCategory.length" description="还没有商品" :image-size="70" />
          <div v-else class="bars">
            <div v-for="r in byCategory" :key="r.name" class="bar-row">
              <span class="bar-name">{{ r.name }}</span>
              <div class="bar-track">
                <div class="bar-fill" :style="{ width: (r.value / maxCat * 100) + '%' }" />
              </div>
              <span class="bar-val">{{ r.value }}</span>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="10">
        <el-card shadow="never" class="mb">
          <div class="card-head"><h3>价格区间</h3></div>
          <el-empty v-if="!priceStat" description="暂无有效价格" :image-size="70" />
          <el-descriptions v-else :column="1" size="small" border>
            <el-descriptions-item label="最低价">¥{{ priceStat.min }}</el-descriptions-item>
            <el-descriptions-item label="中位价">¥{{ priceStat.median }}</el-descriptions-item>
            <el-descriptions-item label="均价">¥{{ priceStat.avg }}</el-descriptions-item>
            <el-descriptions-item label="最高价">¥{{ priceStat.max }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.stat-row { display: flex; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.stat-card { flex: 1; min-width: 150px; }
.stat-value { font-size: 26px; font-weight: 700; color: var(--ink-color); line-height: 1.2; }
.stat-label { font-size: 13px; margin-top: 4px; color: #666; }
.stat-hint { font-size: 11px; margin-top: 2px; color: #bbb; }
.mb { margin-bottom: 12px; }
.card-head { margin-bottom: 12px; }
.card-head h3 { margin: 0; font-size: 15px; color: var(--ink-color); }
.bars { display: flex; flex-direction: column; gap: 9px; }
.bar-row { display: flex; align-items: center; gap: 10px; }
.bar-name { width: 92px; font-size: 12px; color: #666; flex-shrink: 0; }
.bar-track { flex: 1; height: 8px; background: #f2f3f5; border-radius: 4px; overflow: hidden; }
.bar-fill { height: 100%; background: #ffd21e; border-radius: 4px; transition: width .25s ease; }
.bar-val { width: 34px; text-align: right; font-size: 12px; color: #444; font-variant-numeric: tabular-nums; }
</style>
