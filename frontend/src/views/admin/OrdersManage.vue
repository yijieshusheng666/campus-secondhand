<script setup>
/**
 * 订单管理
 *
 * <p>数据源 GET /api/admin/orders。和前台「我的订单」的区别：这里按全站维度查，
 * 不需要知道订单属于谁，因此后端另开了一个入口而不是复用 OrderController.list
 * —— 那个强制要求 userId。</p>
 *
 * <p>同样只读：状态流转交给买卖双方自己走，管理员强行改状态会绕过资金托管逻辑，
 * 风险比收益大得多。</p>
 */
import { ref, computed, onMounted } from 'vue'
import request from '../../api'
import { ORDER_STATUS_ICON, iconUrl } from '../../icons'

const STATUSES = ['待付款', '待发货', '已发货', '已完成', '退款中', '已退款', '已取消']

const rows = ref([])
const loading = ref(false)
const kw = ref('')
const status = ref('')
const detailVisible = ref(false)
const detailRow = ref(null)

async function load() {
  loading.value = true
  try {
    const params = {}
    if (kw.value.trim()) params.kw = kw.value.trim()
    if (status.value) params.status = status.value
    const { data } = await request.get('/admin/orders', { params })
    rows.value = data.data || []
  } catch (e) {
    rows.value = []
  } finally {
    loading.value = false
  }
}

/** 按状态分组计数：比再拉一堆统计接口省事，列表已经在内存里了 */
const statusCounts = computed(() => {
  const m = {}
  rows.value.forEach(o => { m[o.status] = (m[o.status] || 0) + 1 })
  return m
})

const amount = computed(() => {
  const sum = rows.value.reduce((s, o) => s + (Number(o.goodsPrice) || 0), 0)
  return sum
})

function statusType(s) {
  if (s === '已完成') return 'success'
  if (s === '退款中' || s === '已退款') return 'warning'
  if (s === '已取消') return 'info'
  return ''
}

function fmt(t) {
  return t ? String(t).replace('T', ' ').slice(0, 16) : '—'
}

// 见 GoodsManage 的 preview()：MessageBox 不处理换行，长文本会塌成一段并触发抖动，
// 结构化字段用 el-descriptions 呈现更稳也更好读。
function detail(row) {
  detailRow.value = row
  detailVisible.value = true
}

function reset() {
  kw.value = ''; status.value = ''
  load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="stat-row">
      <el-card shadow="never" class="stat-card">
        <div class="stat-value">{{ rows.length }}</div>
        <div class="stat-label">订单数</div>
        <div class="stat-hint">按当前筛选</div>
      </el-card>
      <el-card shadow="never" class="stat-card">
        <div class="stat-value">¥{{ amount.toLocaleString() }}</div>
        <div class="stat-label">订单总额</div>
        <div class="stat-hint">含未支付订单</div>
      </el-card>
      <el-card shadow="never" class="stat-card">
        <div class="stat-value">{{ statusCounts['已完成'] || 0 }}</div>
        <div class="stat-label">已完成</div>
        <div class="stat-hint">退款中 {{ statusCounts['退款中'] || 0 }} 单</div>
      </el-card>
    </div>

    <el-card shadow="never">
      <div class="toolbar">
        <el-input v-model="kw" placeholder="搜索商品名 / 买家 / 订单号" clearable style="width: 240px"
                  @keyup.enter="load" @clear="load" />
        <el-select v-model="status" placeholder="全部状态" clearable style="width: 130px" @change="load">
          <el-option v-for="s in STATUSES" :key="s" :label="`${s}（${statusCounts[s] || 0}）`" :value="s" />
        </el-select>
        <el-button @click="load">查询</el-button>
        <el-button text @click="reset">重置</el-button>
        <span class="grow" />
        <span class="count">共 {{ rows.length }} 条</span>
      </div>

      <div class="table-wrap">
        <el-table :data="rows" v-loading="loading" size="small" empty-text="没有匹配的订单">
          <el-table-column prop="id" label="单号" width="80" />
          <el-table-column prop="goodsTitle" label="商品" min-width="200" show-overflow-tooltip />
          <el-table-column label="金额" width="100" align="right">
            <template #default="{ row }"><span class="price">¥{{ row.goodsPrice }}</span></template>
          </el-table-column>
          <el-table-column label="买家" width="140" show-overflow-tooltip>
            <template #default="{ row }">
              <img v-if="iconUrl(ORDER_STATUS_ICON[row.status])" class="st-ico"
                   :src="iconUrl(ORDER_STATUS_ICON[row.status])" alt="">
              <span>{{ row.buyerName || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="卖家" width="90">
            <template #default="{ row }"><span class="mono">#{{ row.sellerId }}</span></template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="statusType(row.status)">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="托管" width="90">
            <template #default="{ row }">{{ row.escrowStatus || '—' }}</template>
          </el-table-column>
          <el-table-column label="下单时间" width="140">
            <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90" align="right" fixed="right">
            <template #default="{ row }">
              <el-button size="small" text @click="detail(row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-card>
  </div>

    <el-dialog v-model="detailVisible" :title="`订单 #${detailRow?.id ?? ''}`" width="520px">
      <el-descriptions v-if="detailRow" :column="1" border size="small">
        <el-descriptions-item label="商品">{{ detailRow.goodsTitle || '—' }}</el-descriptions-item>
        <el-descriptions-item label="金额">
          <span class="price">¥{{ detailRow.goodsPrice }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="状态">{{ detailRow.status || '—' }}</el-descriptions-item>
        <el-descriptions-item label="资金托管">{{ detailRow.escrowStatus || '—' }}</el-descriptions-item>
        <el-descriptions-item label="买家">
          {{ detailRow.buyerName || '—' }}（ID {{ detailRow.buyerId }}）
        </el-descriptions-item>
        <el-descriptions-item label="卖家">ID {{ detailRow.sellerId }}</el-descriptions-item>
        <el-descriptions-item label="下单时间">{{ fmt(detailRow.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="付款时间">{{ fmt(detailRow.paidAt) }}</el-descriptions-item>
        <el-descriptions-item label="结算时间">{{ fmt(detailRow.settledAt) }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
</template>

<style scoped>
.stat-row { display: flex; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.stat-card { flex: 1; min-width: 150px; }
.stat-value { font-size: 26px; font-weight: 700; color: var(--ink-color); line-height: 1.2; }
.stat-label { font-size: 13px; margin-top: 4px; color: #666; }
.stat-hint { font-size: 11px; margin-top: 2px; color: #bbb; }
.toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
.grow { flex: 1; }
.count { font-size: 12px; color: #999; }
.table-wrap { overflow-x: auto; }
.price { color: var(--price-color); font-weight: 600; font-variant-numeric: tabular-nums; }
.st-ico { width: 14px; height: 14px; object-fit: contain; margin-right: 5px; vertical-align: -2px; opacity: .8; }
.mono { font-size: 12px; color: #888; font-variant-numeric: tabular-nums; }
</style>
