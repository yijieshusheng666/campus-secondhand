<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useApiRequest } from '../composables/useApiRequest'
import request from '../api'
import { useCurrentUser } from '../composables/useCurrentUser'

const router = useRouter()

// 统一错误提示口径：网络/HTTP 错误由 useApiRequest 自动 ElMessage.error 并抛异常
const api = useApiRequest('操作失败，请检查后端服务')

const categories = ['电子产品', '图书教材', '生活用品', '运动户外', '二手家具', '美妆护肤']
const form = reactive({ title: '', category: '电子产品', price: null, description: '' })
const fileList = ref([])
const uploadRef = ref(null)
const submitting = ref(false)

// A4 智能定价建议
const priceHint = ref(null)
const priceLoading = ref(false)

// A3 图片识别
const recognizing = ref(false)

async function suggestPrice() {
  if (!form.title.trim()) {
    ElMessage.warning('先填商品标题，我才能找同类商品估价')
    return
  }
  priceLoading.value = true
  priceHint.value = null
  try {
    const { data } = await api.get('/ai/price-suggest', {
      params: { title: form.title, category: form.category, description: form.description }
    })
    if (data.available) {
      priceHint.value = data
    } else {
      ElMessage.info(data.message || '暂时估不出参考价')
    }
  } catch (e) {
    ElMessage.error('参考价获取失败，请检查后端服务')
  } finally {
    priceLoading.value = false
  }
}

function useSuggestedPrice() {
  if (priceHint.value) {
    form.price = Number(priceHint.value.suggested)
    ElMessage.success('已填入建议价')
  }
}

function fileToBase64(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result).split(',')[1] || '')
    reader.onerror = reject
    reader.readAsDataURL(file)
  })
}

// A3：上传图片后交给多模态模型预填字段（未配置模型时会如实提示）
async function recognizeImage() {
  if (!fileList.value.length) {
    ElMessage.warning('请先上传一张商品图片')
    return
  }
  const raw = fileList.value[0].raw
  recognizing.value = true
  try {
    const image = await fileToBase64(raw)
    const { data } = await api.post('/ai/vision', { image, mime: raw.type || 'image/jpeg' })
    if (!data.available) {
      ElMessage.info({ message: data.message || '图片识别暂不可用', duration: 4000 })
      return
    }
    if (data.title) form.title = data.title
    if (data.category) form.category = data.category
    const desc = [data.condition, data.description].filter(Boolean).join('，')
    if (desc) form.description = desc
    ElMessage.success(data.message || '已根据图片预填，请核对后再发布')
  } catch (e) {
    ElMessage.error('图片识别失败')
  } finally {
    recognizing.value = false
  }
}

// A6：发布前先过一遍内容风控，block 直接拦下，warn 让用户确认
async function checkContent() {
  try {
    const { data } = await api.post('/ai/moderate', {
      title: form.title, description: form.description
    })
    return data
  } catch (e) {
    return null   // 风控服务不可用时不阻塞发布，后端还有兜底
  }
}

onMounted(() => {
  const user = useCurrentUser()
  if (!user) {
    ElMessage.warning('请先登录')
    setTimeout(() => router.push('/login'), 800)
  }
})

function onChange(file, list) {
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.warning('单张图片不能超过5MB')
    fileList.value = list.filter(f => f.uid !== file.uid)
  }
}

function onExceed() {
  ElMessage.warning('最多上传6张图片')
}

async function onPublish() {
  if (!form.title.trim() || !form.price || !form.description.trim()) {
    ElMessage.warning('请填写完整信息')
    return
  }
  const user = useCurrentUser()
  if (!user) {
    ElMessage.warning('请先登录')
    setTimeout(() => router.push('/login'), 800)
    return
  }

  // A6 内容风控
  const risk = await checkContent()
  if (risk && risk.level === 'block') {
    ElMessage.error({ message: risk.message, duration: 5000 })
    return
  }
  if (risk && risk.level === 'warn') {
    try {
      await ElMessageBox.confirm(risk.message, '内容提醒', {
        type: 'warning', confirmButtonText: '我知道了，继续发布', cancelButtonText: '我去改改'
      })
    } catch {
      return
    }
  }

  const formData = new FormData()
  formData.append('title', form.title.trim())
  formData.append('category', form.category)
  formData.append('price', form.price)
  formData.append('description', form.description.trim())
  formData.append('userId', user.value.id)
  fileList.value.forEach((f, i) => {
    formData.append('images', f.raw, `image${i}.jpg`)
  })

  submitting.value = true
  try {
    const { data } = await api.post('/goods', formData)
    if (data.success) {
      // C1：发布后立刻反向撮合——看看有没有同学正在求购这件东西
      let hint = '发布成功'
      try {
        const matched = await request.get('/wanted/match-for-goods', {
          params: { goodsId: data.goodsId }
        })
        const count = matched.data?.data?.length || 0
        if (count > 0) {
          hint = `发布成功！有 ${count} 条求购需求正在找类似的宝贝，去「求购」页看看`
        }
      } catch (e) {
        // 撮合查询失败不影响发布结果
      }
      ElMessage.success({ message: hint, duration: hint.length > 6 ? 3500 : 2000 })
      router.push('/user')
    } else {
      ElMessage.error('发布失败')
    }
  } catch (e) {
    ElMessage.error('网络错误，请检查后端服务是否启动')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="publish-page">
    <el-card class="publish-card">
      <el-form label-position="top" @submit.prevent="onPublish">
        <el-form-item label="商品标题">
          <el-input v-model="form.title" placeholder="请输入商品名称" />
        </el-form-item>
        <el-form-item label="商品分类">
          <el-select v-model="form.category" style="width: 100%">
            <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="商品价格（元）">
          <div class="price-row">
            <el-input-number v-model="form.price" :min="0" :precision="2" :controls="false"
              placeholder="请输入出售价格" style="flex: 1" />
            <el-button :loading="priceLoading" @click="suggestPrice">🤖 参考价</el-button>
          </div>
          <div v-if="priceHint" class="price-hint" :class="{ coarse: priceHint.coarse }">
            {{ priceHint.message }}
            ｜区间 ¥{{ priceHint.low }} ~ ¥{{ priceHint.high }}
            <el-button size="small" text type="primary" @click="useSuggestedPrice">用这个价</el-button>
          </div>
          <div v-else class="field-tip">填好标题后点「参考价」，我按同类在售商品帮你估个价</div>
        </el-form-item>
        <el-form-item label="商品描述">
          <el-input v-model="form.description" type="textarea" :rows="4"
            placeholder="详细描述商品成色、使用情况等" />
        </el-form-item>
        <el-form-item label="商品图片（最多6张）">
          <el-upload
            ref="uploadRef"
            v-model:file-list="fileList"
            list-type="picture-card"
            :auto-upload="false"
            accept="image/*"
            :limit="6"
            :on-change="onChange"
            :on-exceed="onExceed"
          >
            <el-icon size="20"><span style="font-size: 22px">+</span></el-icon>
          </el-upload>
          <!-- A3：交给多模态模型看图预填标题/分类/成色 -->
          <div class="ai-upload-row">
            <el-button size="small" :loading="recognizing" @click="recognizeImage">
              🤖 AI 识别图片并预填
            </el-button>
            <span class="field-tip" style="margin: 0">先用第一张图识别，结果请自行核对</span>
          </div>
        </el-form-item>
        <el-button type="primary" class="submit-btn" :loading="submitting" @click="onPublish">提交发布</el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.field-tip { font-size: 12px; color: #bbb; margin-top: 4px; line-height: 1.5; }
.price-row { display: flex; gap: 10px; width: 100%; align-items: center; }
.price-hint { font-size: 12px; color: #1a5fb4; margin-top: 6px; line-height: 1.6; }
.price-hint.coarse { color: #b8860b; }
.ai-upload-row { display: flex; align-items: center; gap: 10px; margin-top: 8px; }
.publish-page { display: flex; justify-content: center; }
.publish-card { width: 100%; max-width: 640px; }
.submit-btn { width: 100%; }
</style>
