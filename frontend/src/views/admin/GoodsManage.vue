<script setup>
/**
 * 商品管理
 *
 * <p>高密度表格（UX 技能建议 dashboard 用 density 8：12px 间距、小号表格）。
 * 批量下架用「勾选 + 顶部操作条」而不是每行一个按钮 —— 一条条点太折磨人，
 * 这是 UX 指南里 Bulk Actions 的明确要求。</p>
 *
 * <p>危险操作一律二次确认 + 成功提示，遵循「Feedback：不要静默成功」。</p>
 */
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../../api'

const goods = ref([])
const loading = ref(false)
const kw = ref('')
const cat = ref('')
const statusFilter = ref('')
const selected = ref([])
const detailVisible = ref(false)
const detailRow = ref(null)

const CATEGORIES = ['电子产品', '图书教材', '生活用品', '运动户外', '二手家具', '美妆个护']
const STATUSES = ['在售', '已售出', '已下架']

async function load() {
  loading.value = true
  try {
    const { data } = await request.get('/goods', { params: { kw: kw.value.trim() || undefined } })
    goods.value = Array.isArray(data) ? data : []
  } catch (e) {
    goods.value = []
  } finally {
    loading.value = false
  }
}

const filtered = computed(() => {
  const k = kw.value.trim().toLowerCase()
  return goods.value.filter(g => {
    if (cat.value && g.category !== cat.value) return false
    if (statusFilter.value && (g.status || '在售') !== statusFilter.value) return false
    if (k && !(`${g.title} ${g.description || ''}`.toLowerCase().includes(k))) return false
    return true
  })
})

const selectedIds = computed({
  get: () => selected.value,
  set: v => { selected.value = v }
})

function toggleAll(val) {
  selected.value = val ? filtered.value.map(g => g.id) : []
}

const allChecked = computed({
  get: () => filtered.value.length > 0 && filtered.value.every(g => selected.value.includes(g.id)),
  set: toggleAll
})

const someChecked = computed(
  () => filtered.value.some(g => selected.value.includes(g.id)) && !allChecked.value
)

async function removeOne(g) {
  try {
    await ElMessageBox.confirm(
      `下架后「${g.title}」将不再出现在前台，确定吗？`, '下架商品', { type: 'warning' })
  } catch { return }
  await doDelete([g.id], `「${g.title}」已下架`)
}

async function removeSelected() {
  if (!selected.value.length) return
  try {
    await ElMessageBox.confirm(
      `将下架选中的 ${selected.value.length} 件商品，下架后前台不可见。确定吗？`,
      '批量下架', { type: 'warning', type2: 'danger' })
  } catch { return }
  await doDelete(selected.value, `已下架 ${selected.value.length} 件商品`)
}

/** 逐个调删除接口：后端只有单条 DELETE，没有批量端点。
 *  串行而不是 Promise.all —— 失败时要能报出具体是哪几条，不静默吞掉。 */
async function doDelete(ids, okMsg) {
  loading.value = true
  const failed = []
  for (const id of ids) {
    try {
      const { data } = await request.delete(`/goods/${id}`)
      if (data && data.success === false) failed.push(id)
    } catch (e) { failed.push(id) }
  }
  loading.value = false
  if (failed.length) ElMessage.error(`${failed.length} 件下架失败，请稍后重试`)
  if (failed.length < ids.length) ElMessage.success(okMsg)
  selected.value = []
  await load()
}

// 详情改用 el-dialog + el-descriptions，而不是 ElMessageBox.alert。
// 两个原因：
//   1. MessageBox 的 .el-message-box__message 没有 white-space: pre-wrap，
//      换行符会被折叠成一整段，在 420px 宽度下 overflow-wrap 疯狂折行、
//      高度剧烈变化，叠加它自身的 translateY(-20px) 入场动画与 vertical-align:middle
//      垂直居中 hack —— 弹窗会肉眼可见地抖一下。
//   2. 描述是长文本，塞进 MessageBox 的段落里可读性很差。
// el-dialog 高度稳定、结构可控，和管理端其它卡片风格也统一。
function preview(g) {
  detailRow.value = g
  detailVisible.value = true
}

function fmt(t) {
  return t ? String(t).replace('T', ' ').slice(0, 16) : '—'
}

onMounted(load)
</script>

<template>
  <div>
    <el-card shadow="never">
      <div class="toolbar">
        <el-input v-model="kw" placeholder="搜索标题 / 描述" clearable style="width: 220px"
                  @keyup.enter="load" @clear="load" />
        <el-select v-model="cat" placeholder="全部分类" clearable style="width: 140px">
          <el-option v-for="c in CATEGORIES" :key="c" :label="c" :value="c" />
        </el-select>
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 130px">
          <el-option v-for="s in STATUSES" :key="s" :label="s" :value="s" />
        </el-select>
        <el-button @click="load">查询</el-button>
        <el-button text @click="kw = ''; cat = ''; statusFilter = ''; load()">重置</el-button>
        <span class="grow" />
        <span class="count">共 {{ filtered.length }} 条</span>
      </div>

      <!-- 批量操作条：只在有勾选时出现，避免常驻占空间 -->
      <transition name="slide">
        <div v-if="selected.length" class="action-bar">
          <span>已选 <b>{{ selected.length }}</b> 项</span>
          <el-button size="small" type="danger" plain @click="removeSelected">批量下架</el-button>
          <el-button size="small" text @click="selected = []">取消选择</el-button>
        </div>
      </transition>

      <div class="table-wrap">
        <el-table :data="filtered" v-loading="loading" size="small" empty-text="没有匹配的商品">
          <el-table-column width="42">
            <template #header>
              <el-checkbox :model-value="allChecked" :indeterminate="someChecked" @change="toggleAll" />
            </template>
            <template #default="{ row }">
              <el-checkbox :value="row.id" v-model="selectedIds" />
            </template>
          </el-table-column>
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column label="商品" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">
              <div class="g-name">{{ row.title }}</div>
              <div class="g-desc">{{ row.description || '（无描述）' }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="category" label="分类" width="100" />
          <el-table-column prop="price" label="价格" width="90" align="right">
            <template #default="{ row }"><span class="price">¥{{ row.price }}</span></template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === '在售' ? 'success' : 'info'">
                {{ row.status || '在售' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="发布时间" width="140">
            <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="120" align="right" fixed="right">
            <template #default="{ row }">
              <el-button size="small" text @click="preview(row)">详情</el-button>
              <el-button size="small" text type="danger" @click="removeOne(row)">下架</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-card>
  </div>

    <el-dialog v-model="detailVisible" :title="detailRow?.title || '商品详情'" width="560px">
      <el-descriptions v-if="detailRow" :column="1" border size="small">
        <el-descriptions-item label="商品 ID">{{ detailRow.id }}</el-descriptions-item>
        <el-descriptions-item label="分类">{{ detailRow.category || '未分类' }}</el-descriptions-item>
        <el-descriptions-item label="价格">
          <span class="price">¥{{ detailRow.price }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="状态">{{ detailRow.status || '在售' }}</el-descriptions-item>
        <el-descriptions-item label="卖家 ID">#{{ detailRow.userId }}</el-descriptions-item>
        <el-descriptions-item label="发布时间">{{ fmt(detailRow.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="描述">
          <span class="desc-text">{{ detailRow.description || '（卖家未填写描述）' }}</span>
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
</template>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
.grow { flex: 1; }
.count { font-size: 12px; color: #999; }
.action-bar {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 12px; margin-bottom: 10px;
  background: #fff7d6; border-radius: 8px;
  font-size: 13px; color: var(--ink-color);
}
.slide-enter-active, .slide-leave-active { transition: opacity .18s ease, transform .18s ease; }
.slide-enter-from, .slide-leave-to { opacity: 0; transform: translateY(-4px); }
/* 表格横向滚动兜底，避免窄屏把布局撑破 */
.table-wrap { overflow-x: auto; }
.g-name { font-size: 13px; color: var(--ink-color); font-weight: 500; }
.g-desc { font-size: 11px; color: #aaa; margin-top: 2px; }
.price { color: var(--price-color); font-weight: 600; font-variant-numeric: tabular-nums; }
/* 商品描述是多行文本，必须保留换行并给一个最大高度，
   否则超长描述会把弹窗撑得很高、滚动条忽隐忽现，那又会变成另一种抖动 */
.desc-text { white-space: pre-wrap; line-height: 1.6; display: block;
  max-height: 180px; overflow-y: auto; }
</style>
