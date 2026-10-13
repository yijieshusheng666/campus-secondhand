<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api'

const router = useRouter()
const user = ref(JSON.parse(localStorage.getItem('user') || 'null'))

const pending = ref([])
const handled = ref([])
const loading = ref(false)
const rulingNote = ref({})     // { [disputeId]: 裁定说明 }
const submitting = ref(0)

async function loadDisputes() {
  if (!user.value) return
  loading.value = true
  try {
    const { data } = await request.get('/disputes/pending', {
      params: { handlerId: user.value.id }
    })
    if (!data.success) {
      ElMessage.error(data.message || '无权查看')
      router.push('/')
      return
    }
    pending.value = data.data || []
    handled.value = data.handledList || []
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '纠纷列表加载失败')
  } finally {
    loading.value = false
  }
}

// 裁定：支持买家（退款）或驳回诉求（放款给卖家）
async function rule(dispute, ruling) {
  const label = ruling === '支持买家' ? '支持买家·退款' : '驳回诉求·放款'
  try {
    await ElMessageBox.confirm(
      ruling === '支持买家'
        ? '裁定后货款将退回买家，商品重新上架。确定吗？'
        : '裁定后视为交易完成，货款放给卖家。确定吗？',
      label, { type: 'warning' })
  } catch {
    return
  }
  submitting.value = dispute.id
  try {
    const { data } = await request.post(`/disputes/${dispute.id}/rule`, {
      handlerId: user.value.id,
      ruling,
      note: rulingNote.value[dispute.id] || ''
    })
    if (data.success) {
      ElMessage.success(data.message)
      rulingNote.value[dispute.id] = ''
      await loadDisputes()
    } else {
      ElMessage.error(data.message || '裁定失败')
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '裁定失败')
  } finally {
    submitting.value = 0
  }
}

function formatTime(t) {
  if (!t) return ''
  return String(t).replace('T', ' ').slice(0, 19)
}

onMounted(loadDisputes)
</script>

<template>
  <div class="admin-page">
    <div v-if="!user || user.role !== 'admin'" class="empty-wrap">
      <el-empty description="该页面仅平台管理员可见">
        <el-button type="primary" @click="router.push('/')">回首页</el-button>
      </el-empty>
    </div>

    <div v-else>
      <el-card class="head-card" shadow="never">
        <div class="head-row">
          <div>
            <h2 class="head-title">纠纷仲裁工作台</h2>
            <p class="head-sub">
              买家申请平台介入后，订单进入「仲裁中」，货款冻结在平台直至裁定。
            </p>
          </div>
          <div class="stat">
            <div class="stat-num">{{ pending.length }}</div>
            <div class="stat-label">待处理</div>
          </div>
          <div class="stat">
            <div class="stat-num done">{{ handled.length }}</div>
            <div class="stat-label">已裁定</div>
          </div>
        </div>
      </el-card>

      <el-card class="section-card" shadow="never" v-loading="loading">
        <div class="section-header">
          <h3>待处理纠纷</h3>
        </div>
        <el-empty v-if="!loading && pending.length === 0" description="没有待处理的纠纷" />

        <div v-for="d in pending" :key="d.id" class="dispute-card">
          <div class="dispute-head">
            <span class="dispute-goods">{{ d.goodsTitle }}</span>
            <el-tag size="small" type="danger">{{ d.reason }}</el-tag>
          </div>
          <div class="dispute-line">
            订单号 {{ d.orderId }} ｜ 申请人（买家）：{{ d.initiatorName }}
            ｜ 被申请方（卖家）：{{ d.respondentName }}
          </div>
          <div class="dispute-detail">{{ d.detail || '（申请人未填写补充说明）' }}</div>
          <div class="dispute-time">申请时间：{{ formatTime(d.createdAt) }}</div>
          <el-input v-model="rulingNote[d.id]" class="ruling-input" maxlength="200" show-word-limit
            placeholder="裁定说明（选填，会记录在处理结果里）" />
          <div class="dispute-actions">
            <el-button type="danger" :loading="submitting === d.id"
              @click="rule(d, '支持买家')">支持买家 · 退款</el-button>
            <el-button type="primary" :loading="submitting === d.id"
              @click="rule(d, '驳回买家')">驳回诉求 · 放款给卖家</el-button>
          </div>
        </div>
      </el-card>

      <el-card class="section-card" shadow="never">
        <div class="section-header">
          <h3>已裁定</h3>
        </div>
        <el-empty v-if="handled.length === 0" description="还没有裁定记录" :image-size="70" />
        <div v-for="d in handled" :key="d.id" class="dispute-card done-card">
          <div class="dispute-head">
            <span class="dispute-goods">{{ d.goodsTitle }}</span>
            <el-tag size="small" :type="d.ruling === '支持买家' ? 'success' : 'info'">
              {{ d.ruling }}
            </el-tag>
          </div>
          <div class="dispute-line">
            订单号 {{ d.orderId }} ｜ {{ d.initiatorName }} vs {{ d.respondentName }}
          </div>
          <div class="dispute-detail" v-if="d.rulingNote">裁定说明：{{ d.rulingNote }}</div>
          <div class="dispute-time">
            裁定时间：{{ formatTime(d.handledAt) }} ｜ 处理人：{{ d.handlerName || '平台' }}
          </div>
        </div>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.admin-page { max-width: 860px; margin: 0 auto; }
.empty-wrap { background: #fff; border-radius: 12px; padding: 40px; }
.head-card { margin-bottom: 16px; }
.head-row { display: flex; align-items: center; gap: 24px; }
.head-title { margin: 0 0 6px; font-size: 18px; color: var(--ink-color); }
.head-sub { margin: 0; font-size: 12px; color: #999; flex: 1; }
.stat { text-align: center; min-width: 64px; }
.stat-num { font-size: 26px; font-weight: bold; color: var(--price-color); line-height: 1.1; }
.stat-num.done { color: #67c23a; }
.stat-label { font-size: 12px; color: #999; }
.section-card { margin-bottom: 16px; }
.section-header { margin-bottom: 10px; }
.section-header h3 { margin: 0; font-size: 15px; color: var(--ink-color); }
.dispute-card {
  border: 1px solid #f0f0f0;
  border-radius: 10px;
  padding: 14px;
  margin-bottom: 12px;
}
.done-card { background: #fbfbfb; }
.dispute-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.dispute-goods { font-weight: 600; color: var(--ink-color); }
.dispute-line { font-size: 12px; color: #888; margin: 6px 0; line-height: 1.7; }
.dispute-detail { font-size: 13px; color: #555; line-height: 1.6; margin-bottom: 6px; }
.dispute-time { font-size: 11px; color: #bbb; }
.ruling-input { margin: 10px 0; }
.dispute-actions { display: flex; gap: 10px; }
</style>
