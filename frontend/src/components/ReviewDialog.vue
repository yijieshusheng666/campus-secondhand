<script setup>
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useApiRequest } from '../composables/useApiRequest'

/**
 * 评价弹窗（双向共用）。
 * 买家在「我的订单」评价卖家，卖家在「售出记录」评价买家，只有文案不同。
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  order: { type: Object, default: null },
  userId: { type: [Number, String], required: true },
  target: { type: String, default: '卖家' }   // '卖家' 或 '买家'
})
const emit = defineEmits(['update:modelValue', 'submitted'])

const reviewApi = useApiRequest('评价失败，请检查后端服务')

const form = ref({ score: 5, content: '' })
const submitting = ref(false)

watch(() => props.modelValue, (open) => {
  if (open) form.value = { score: 5, content: '' }
})

function close() {
  emit('update:modelValue', false)
}

async function submit() {
  if (!form.value.score) {
    ElMessage.warning('请先给个星级')
    return
  }
  submitting.value = true
  try {
    const { data } = await reviewApi.post('/reviews', {
      orderId: props.order?.id,
      reviewerId: props.userId,
      score: form.value.score,
      content: form.value.content
    })
    if (data.success) {
      ElMessage.success(data.message || '评价已提交')
      close()
      emit('submitted')
    } else {
      ElMessage.error(data.message || '评价失败')
    }
  } catch {
    // useApiRequest 已统一提示过网络/HTTP 错误
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog :model-value="modelValue" :title="`评价这位${target}`" width="420px"
    @update:model-value="close">
    <div class="review-body">
      <div class="review-goods">{{ order?.goodsTitle }}</div>
      <div class="review-row">
        <span class="review-label">交易体验</span>
        <el-rate v-model="form.score" show-text
          :texts="['很差', '较差', '一般', '满意', '非常满意']" />
      </div>
      <el-input v-model="form.content" type="textarea" :rows="3" maxlength="200" show-word-limit
        :placeholder="target === '卖家' ? '说说成色、沟通和发货速度吧（选填）' : '说说买家是否爽快、沟通是否顺畅（选填）'" />
      <p class="review-note">评价会累计到对方的信用分，请如实反馈。</p>
    </div>
    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">提交评价</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.review-goods { font-weight: 600; margin-bottom: 12px; color: var(--ink-color); }
.review-row { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.review-label { color: #888; font-size: 13px; width: 64px; flex-shrink: 0; }
.review-note { font-size: 12px; color: #bbb; margin: 8px 0 0; }
</style>
