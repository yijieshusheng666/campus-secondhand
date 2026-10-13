<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../api'

const router = useRouter()
const categories = ['电子产品', '图书教材', '生活用品', '运动户外', '二手家具', '美妆护肤']
const form = reactive({ title: '', category: '电子产品', price: null, description: '' })
const fileList = ref([])
const uploadRef = ref(null)
const submitting = ref(false)

onMounted(() => {
  const user = JSON.parse(localStorage.getItem('user') || 'null')
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
  const user = JSON.parse(localStorage.getItem('user') || 'null')
  if (!user) {
    ElMessage.warning('请先登录')
    setTimeout(() => router.push('/login'), 800)
    return
  }

  const formData = new FormData()
  formData.append('title', form.title.trim())
  formData.append('category', form.category)
  formData.append('price', form.price)
  formData.append('description', form.description.trim())
  formData.append('userId', user.id)
  fileList.value.forEach((f, i) => {
    formData.append('images', f.raw, `image${i}.jpg`)
  })

  submitting.value = true
  try {
    const { data } = await request.post('/goods', formData)
    if (data.success) {
      ElMessage.success('发布成功')
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
          <el-input-number v-model="form.price" :min="0" :precision="2" :controls="false"
            placeholder="请输入出售价格" style="width: 100%" />
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
        </el-form-item>
        <el-button type="primary" class="submit-btn" :loading="submitting" @click="onPublish">提交发布</el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.publish-page { display: flex; justify-content: center; }
.publish-card { width: 100%; max-width: 640px; }
.submit-btn { width: 100%; }
</style>
