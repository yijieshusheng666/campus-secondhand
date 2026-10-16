<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useApiRequest } from '../../composables/useApiRequest'
import { useCurrentUser } from '../../composables/useCurrentUser'
import { ruleIcon } from '../../icons'

const user = useCurrentUser()

// 统一错误提示口径：网络/HTTP 错误由 useApiRequest 自动 ElMessage.error
const disputeApi = useApiRequest('纠纷列表加载失败')
const ruleApi = useApiRequest('裁定失败')

const pending = ref([])
const handled = ref([])
const loading = ref(false)
const rulingNote = ref({})     // { [disputeId]: 裁定说明 }
const submitting = ref(0)

async function loadDisputes() {
  if (!user.value) return
  loading.value = true
  try {
    const { data } = await disputeApi.get('/disputes/pending', {
      params: { handlerId: user.value.id }
    })
    if (!data.success) {
      ElMessage.error(data.message || '无权查看')
      return
    }
    pending.value = data.data || []
    handled.value = data.handledList || []
  } catch {
    // useApiRequest 已统一提示过网络/HTTP 错误
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
    const { data } = await ruleApi.post(`/disputes/${dispute.id}/rule`, {
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
  } catch {
    // 同上
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

    <el-card class="section-card" shadow="never" v-loading="loading">
      <div class="section-header">
        <h3>待处理纠纷</h3>
        <el-tag v-if="pending.length" type="danger" size="small">{{ pending.length }} 条待裁</el-tag>
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
            @click="rule(d, '支持买家')">
            <img  loading="lazy" v-if="ruleIcon('支持买家')" class="btn-icon" :src="ruleIcon('支持买家')" alt="">支持买家 · 退款
          </el-button>
          <el-button type="primary" :loading="submitting === d.id"
            @click="rule(d, '驳回买家')">
            <img  loading="lazy" v-if="ruleIcon('驳回买家')" class="btn-icon" :src="ruleIcon('驳回买家')" alt="">驳回诉求 · 放款给卖家
          </el-button>
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
</template>

<style scoped>
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
/* 按钮里的小图标（与订单页保持一致） */
.btn-icon {
  width: 15px;
  height: 15px;
  border-radius: 4px;
  object-fit: cover;
  vertical-align: -3px;
  margin-right: 4px;
}
</style>
