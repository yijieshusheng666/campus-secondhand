<script setup>
/**
 * 知识库运营台（RAG 自运营）
 *
 * <p>后端 /api/ai/ops/* 的 11 个代理接口（AiController）早就写好了，但前端一直没有入口，
 * 导致这套 RAG 只能"上线即冻结"——知识库里写错一条、用户问了新问题，都没人管。</p>
 *
 * <p>本页把闭环补上：
 *   miss 榜（用户问了但答不上来的）→ 一键转成待补录条目 → 补录 → 索引增量更新。
 * 这是让 RAG 越用越准的唯一途径，也是答辩时"知识库可自运营"的落点。</p>
 *
 * <p>全部走 Java 代理（/api/ai/ops/*），不在前端直连 8000，和其它业务接口口径一致。</p>
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../../api'

// 重建索引要重算全部条目的向量，是最慢的一个接口（条数多时可达分钟级），
// 默认 10s 必然超时，这里单独给足。
const REINDEX_TIMEOUT = 300000

// ⚠️ 两套路径，别混用：
//   OPS     —— 给 axios 用。api.js 里 axios 实例已经配了 baseURL: '/api'，
//              这里再写 '/api' 前缀，实际请求就变成 /api/api/ai/ops/... → 404。
//              （这个坑真实发生过：整个知识库运营台全部接口静默失效。）
//   OPS_ABS —— 给 window.open / <a href> 用，那些不吃 baseURL，必须写完整路径。
const OPS = '/ai/ops'
const OPS_ABS = '/api' + OPS

// ---- 概览 ----
const summary = ref(null)
const aiOffline = ref(false)

const statCards = computed(() => {
  const s = summary.value
  if (!s) return []
  const pct = v => `${Math.round((v || 0) * 100)}%`
  return [
    { label: '问答总量', value: s.total ?? 0, hint: '累计提问次数' },
    { label: '回答成功率', value: pct(s.successRate), hint: `已答 ${s.answered ?? 0} 次` },
    { label: '拒答次数', value: s.refused ?? 0, hint: `拒答率 ${pct(s.refuseRate)}` },
    { label: '知识条目', value: s.knowledgeCount ?? 0, hint: `${s.embeddingMode || '-'} 索引 · 阈值 ${s.refuseThreshold ?? '-'}` }
  ]
})

// ---- 分片 ----
const shards = ref([])
const shardName = computed(() => Object.fromEntries(shards.value.map(s => [s.shard, s.name])))

// 静态兜底分片。AI 服务不可用时 /kb/shards 拿不到列表，
// 但运营照样要能录知识 —— 这个下拉不能因此变空。
const SHARD_FALLBACK = [
  { shard: 'faq', name: '高频问答' },
  { shard: 'guides', name: '操作指引' },
  { shard: 'rules', name: '平台规则' },
  { shard: 'campus', name: '校园场景' },
  { shard: 'reference', name: '商品参考' }
]

// 有实时数据就用实时（带条数），没有就退回静态清单
const shardOptions = computed(() =>
  shards.value.length ? shards.value : SHARD_FALLBACK
)

// ---- 知识盲区 ----
const misses = ref([])
const topQuestions = ref([])
const MISSES_CSV = `${OPS_ABS}/stats/misses.csv`

// ---- 知识条目 ----
const entries = ref([])
const entriesLoading = ref(false)
const filterShard = ref('')
const filterKw = ref('')

async function loadSummary() {
  try {
    const { data } = await request.get(`${OPS}/stats/summary`)
    aiOffline.value = data.available === false
    summary.value = data.data || null
  } catch (e) { aiOffline.value = true }
}

async function loadShards() {
  try {
    const { data } = await request.get(`${OPS}/kb/shards`)
    shards.value = data.data || []
  } catch (e) { shards.value = [] }
}

async function loadMisses() {
  try {
    const { data } = await request.get(`${OPS}/stats/misses`, { params: { limit: 50 } })
    misses.value = data.data || []
  } catch (e) { misses.value = [] }
}

async function loadTopQuestions() {
  try {
    const { data } = await request.get(`${OPS}/stats/top-questions`, { params: { limit: 12 } })
    topQuestions.value = data.data || []
  } catch (e) { topQuestions.value = [] }
}

async function loadEntries() {
  entriesLoading.value = true
  try {
    const params = {}
    if (filterShard.value) params.shard = filterShard.value
    if (filterKw.value.trim()) params.q = filterKw.value.trim()
    const { data } = await request.get(`${OPS}/kb/entries`, { params })
    entries.value = data.data || []
  } catch (e) {
    entries.value = []
  } finally {
    entriesLoading.value = false
  }
}

async function refreshAll() {
  await Promise.allSettled([loadSummary(), loadShards(), loadMisses(), loadTopQuestions(), loadEntries()])
}

// ---- 增删改 ----
// 高级选项默认收起：ID / 标签 / 溯源名 都是低频字段，
// 平时摊开只会让人以为这三个都得填（其实都会自动兜底）。
const kbAdvanced = ref([])
const dialogVisible = ref(false)
const saving = ref(false)
const form = reactive({ id: '', shard: 'faq', title: '', content: '', tags: '', source: '', updated: '' })

function openCreate(seed = {}) {
  Object.assign(form, { id: '', shard: 'faq', title: '', content: '', tags: '', source: '', updated: '', ...seed })
  dialogVisible.value = true
}

function openEdit(row) {
  Object.assign(form, {
    id: row.id, shard: row.shard, title: row.title, content: row.content || '',
    tags: (row.tags || []).join('、'), source: row.source || '', updated: row.updated || ''
  })
  dialogVisible.value = true
}

/** miss 榜一键转成待补录条目：把问题填进标题，正文留空等人工写。
 *  刻意不自动生成正文 —— 编出来的话进知识库会污染检索，比没有更糟。 */
function draftFromMiss(m) {
  openCreate({ title: m.question })
  ElMessage.info('已把问题填进标题，正文请手动补充；只写确定的平台规则')
}

function handlePreviewEdit() {
  const row = previewRow.value
  previewVisible.value = false
  if (row) openEdit(row)
}

async function save() {
  if (!form.title.trim()) return ElMessage.warning('标题不能为空')
  saving.value = true
  try {
    const { data } = await request.post(`${OPS}/kb/entries`, {
      id: form.id || null,
      shard: form.shard,
      title: form.title,
      content: form.content,
      tags: form.tags ? String(form.tags).split(/[、,，\s]+/).filter(Boolean) : [],
      source: form.source,
      updated: form.updated
    })
    if (data.success) {
      ElMessage.success(data.message || '已保存，索引已增量更新')
      dialogVisible.value = false
      await refreshAll()
    } else {
      ElMessage.error(data.message || '保存失败')
    }
  } catch (e) {
    // 请求层已统一提示
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(`删除知识「${row.title}」？删除后索引会同步移除。`, '删除知识', { type: 'warning' })
  } catch { return }
  try {
    const { data } = await request.delete(`${OPS}/kb/entries/${row.id}`)
    if (data.success) {
      ElMessage.success(data.message || '已删除')
      await refreshAll()
    } else {
      ElMessage.error(data.message || '删除失败')
    }
  } catch (e) { /* 已提示 */ }
}

async function reindex() {
  try {
    const { data } = await request.post(`${OPS}/kb/reindex`, null, { timeout: REINDEX_TIMEOUT })
    if (data.success) ElMessage.success(data.message || '索引已重建')
    else ElMessage.error(data.message || '重建失败')
  } catch (e) { /* 已提示 */ }
}

function exportCsv() {
  window.open(`${OPS_ABS}/kb/entries.csv`, '_blank')
}

// 预览状态。和商品/订单详情同理：知识正文常常很长，用 ElMessageBox.alert 会被折成一整段，
// 在它自身的 translateY 入场动画 + vertical-align:middle 垂直居中下反复重新居中，
// 表现为弹窗抖一下。改用 el-dialog + 保留换行的正文区。
const previewVisible = ref(false)
const previewRow = ref(null)
function previewEntry(row) {
  previewRow.value = row
  previewVisible.value = true
}

onMounted(refreshAll)
</script>

<template>
  <div class="kb-page">
    <el-alert
      v-if="aiOffline"
      type="warning"
      show-icon
      :closable="false"
      title="AI 服务不可用"
      description="请先启动 ai-service（8000 端口），本页所有数据都来自 AI 服务。"
      class="mb"
    />

    <div class="stat-row">
      <el-card v-for="c in statCards" :key="c.label" shadow="never" class="stat-card">
        <div class="stat-value">{{ c.value }}</div>
        <div class="stat-label">{{ c.label }}</div>
        <div class="stat-hint">{{ c.hint }}</div>
      </el-card>
    </div>

    <el-card shadow="never">
      <el-tabs>
        <!-- 1. 知识盲区：这张表就是这套 RAG 的迭代清单 -->
        <el-tab-pane name="misses">
          <template #label>
            知识盲区
            <el-badge v-if="misses.length" :value="misses.length" class="tab-badge" />
          </template>
          <div class="bar">
            <span class="bar-hint">用户问过但知识库里没有的问题。点「补录」直接进新增表单。</span>
            <a :href="MISSES_CSV" target="_blank" class="link">导出 CSV</a>
          </div>
          <el-table :data="misses" size="small" empty-text="暂无未命中问题，说明目前答得上来的都答上来了">
            <el-table-column prop="at" label="时间" width="160" />
            <el-table-column prop="question" label="未命中问题" min-width="260" />
            <el-table-column prop="confidence" label="最高置信度" width="110">
              <template #default="{ row }">{{ row.confidence?.toFixed?.(3) ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="90" align="right">
              <template #default="{ row }">
                <el-button size="small" text type="primary" @click="draftFromMiss(row)">补录</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 2. 高频问题：看哪块需求最密集 -->
        <el-tab-pane label="高频问题" name="top">
          <el-table :data="topQuestions" size="small" empty-text="暂无数据">
            <el-table-column type="index" label="#" width="50" />
            <el-table-column prop="question" label="问题" min-width="260" />
            <el-table-column prop="count" label="次数" width="80" />
            <el-table-column label="占比" width="160">
              <template #default="{ row }">
                <el-progress :percentage="Math.round((row.ratio || 0) * 100)" :stroke-width="10" />
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 3. 知识库条目 -->
        <el-tab-pane label="知识库" name="entries">
          <div class="bar">
            <el-select v-model="filterShard" placeholder="全部分片" clearable style="width: 150px" @change="loadEntries">
              <el-option v-for="s in shardOptions" :key="s.shard"
                       :label="s.count === undefined ? s.name : `${s.name} (${s.count})`" :value="s.shard" />
            </el-select>
            <el-input v-model="filterKw" placeholder="搜索标题 / 正文 / 标签" clearable style="width: 240px" @keyup.enter="loadEntries" @clear="loadEntries" />
            <el-button @click="loadEntries">查询</el-button>
            <span class="grow" />
            <el-button type="primary" @click="openCreate()">＋ 新增知识</el-button>
            <el-button @click="exportCsv">导出 CSV</el-button>
            <el-button @click="reindex">重建索引</el-button>
          </div>
          <el-table :data="entries" v-loading="entriesLoading" size="small" empty-text="没有匹配的知识条目">
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column label="分片" width="100">
              <template #default="{ row }">{{ shardName[row.shard] || row.shard }}</template>
            </el-table-column>
            <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
            <el-table-column prop="updated" label="更新" width="110" />
            <el-table-column label="操作" width="130" align="right">
              <template #default="{ row }">
                <el-button size="small" text @click="previewEntry(row)">查看</el-button>
                <el-button size="small" text type="primary" @click="openEdit(row)">编辑</el-button>
                <el-button size="small" text type="danger" @click="remove(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-dialog v-model="previewVisible" :title="previewRow?.title || '知识详情'" width="620px">
      <div v-if="previewRow" class="kb-preview-meta">
        <el-tag size="small">{{ previewRow.id }}</el-tag>
        <el-tag size="small" type="info">{{ previewRow.shard_name || previewRow.shard }}</el-tag>
        <span v-if="previewRow.source" class="kb-preview-src">{{ previewRow.source }}</span>
      </div>
      <!-- pre-wrap 保留知识正文里的分点换行；max-height 封顶并内部滚动，
           防止超长条目把弹窗撑出屏幕（那又会变成另一种抖动） -->
      <div v-if="previewRow" class="kb-preview-body">{{ previewRow.content || '（无正文）' }}</div>
      <template #footer>
        <el-button @click="previewVisible = false">关闭</el-button>
        <el-button type="primary" @click="handlePreviewEdit">去编辑</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑知识' : '新增知识'" width="520px">
      <!--
        字段收敛：原来 6 个字段一次性全摊开，是 UX 指南里典型的 "overwhelm upfront"。
        实际只有 标题 / 正文 / 分片 是必填的（后端 KnowledgeUpsert 只强校验 title 非空，
        但没有正文的条目对 RAG 毫无用处，等于白录一条污染召回）：
          - ID      留空自动生成，只有迁移旧条目时才需要手动指定 → 收进高级项
          - 标签    正文里本来就有这些词，对 BM25 命中增益有限 → 收进高级项
          - 溯源名  后端会兜底成「{分片} 分片」 → 收进高级项
        高频字段用无 label 的紧凑布局（placeholder 当标签），把空间让给真正要填的正文。
      -->
      <div class="kb-form">
        <el-input v-model="form.title" placeholder="标题：用户会怎么问，就怎么写" maxlength="60" />

        <el-select v-model="form.shard" placeholder="选择分片" class="kb-shard">
          <el-option v-for="s in shardOptions" :key="s.shard"
                     :label="s.count === undefined ? s.name : `${s.name}（${s.count}）`"
                     :value="s.shard" />
        </el-select>

        <el-input v-model="form.content" type="textarea" :rows="7"
                  placeholder="正文：只写确定的平台规则，一步一行。&#10;写不确定的宁可不写 —— 编错的内容会污染检索，比没有更糟。" />

        <el-collapse v-model="kbAdvanced" class="kb-advanced">
          <el-collapse-item title="高级选项" name="adv">
            <el-form label-width="66px" size="small">
              <el-form-item label="ID">
                <el-input v-model="form.id" placeholder="留空自动生成" />
              </el-form-item>
              <el-form-item label="标签">
                <el-input v-model="form.tags" placeholder="顿号分隔，例如：退款、订单" />
              </el-form-item>
              <el-form-item label="溯源名">
                <el-input v-model="form.source" placeholder="留空则自动填「{分片} 分片」" />
              </el-form-item>
            </el-form>
          </el-collapse-item>
        </el-collapse>
      </div>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!form.title.trim()" @click="save">
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.kb-page { max-width: none; }
.mb { margin-bottom: 12px; }
.stat-row { display: flex; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.stat-card { flex: 1; min-width: 150px; }
.stat-value { font-size: 26px; font-weight: 700; color: var(--ink-color); line-height: 1.2; }
.stat-label { font-size: 13px; margin-top: 4px; color: #666; }
.stat-hint { font-size: 11px; margin-top: 2px; color: #bbb; }
.bar { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
.bar-hint { font-size: 12px; color: #999; }
.grow { flex: 1; }
.link { font-size: 12px; color: #409eff; text-decoration: none; }
.kb-form { display: flex; flex-direction: column; gap: 10px; }
.kb-shard { width: 100%; }
.kb-advanced :deep(.el-collapse-item__header) {
  font-size: 12px; color: #999; height: 32px; line-height: 32px;
}
.kb-advanced :deep(.el-collapse-item__wrap) { border-bottom: none; }
.kb-advanced :deep(.el-collapse-item__content) { padding-bottom: 0; }
.kb-preview-meta { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.kb-preview-src { font-size: 12px; color: #999; }
.kb-preview-body {
  white-space: pre-wrap; line-height: 1.7; font-size: 13px; color: #444;
  background: #fafbfc; border: 1px solid #eef0f3; border-radius: 8px;
  padding: 12px 14px; max-height: 380px; overflow-y: auto;
}
.tab-badge :deep(.el-badge__content) { transform: translateY(-10px) translateX(100%); }
</style>
