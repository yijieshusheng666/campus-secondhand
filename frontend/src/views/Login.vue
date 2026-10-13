<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../api'

const router = useRouter()
const activeTab = ref('login')
const loginForm = reactive({ stuid: '', password: '' })
const regForm = reactive({ username: '', stuid: '', password: '' })

async function onLogin() {
  if (!loginForm.stuid.trim() || !loginForm.password.trim()) {
    ElMessage.warning('请输入账号和密码')
    return
  }
  try {
    const params = new URLSearchParams()
    params.append('stuid', loginForm.stuid.trim())
    params.append('password', loginForm.password.trim())
    const { data } = await request.post('/login', params)
    if (data.success) {
      localStorage.setItem('user', JSON.stringify(data.user))
      ElMessage.success('登录成功')
      router.push('/')
    } else {
      ElMessage.error(data.msg || data.message || '登录失败')
    }
  } catch (e) {
    ElMessage.error('网络错误，请检查后端服务')
  }
}

async function onRegister() {
  if (!regForm.username.trim() || !regForm.stuid.trim() || !regForm.password.trim()) {
    ElMessage.warning('请填写完整信息')
    return
  }
  try {
    const params = new URLSearchParams()
    params.append('username', regForm.username.trim())
    params.append('stuid', regForm.stuid.trim())
    params.append('password', regForm.password.trim())
    const { data } = await request.post('/register', params)
    if (data.success) {
      ElMessage.success('注册成功，请登录')
      activeTab.value = 'login'
      loginForm.stuid = regForm.stuid
    } else {
      ElMessage.error(data.message || '注册失败')
    }
  } catch (e) {
    ElMessage.error('网络错误，请检查后端服务')
  }
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="用户登录" name="login">
          <el-form label-position="top" @submit.prevent="onLogin">
            <el-form-item label="账号">
              <el-input v-model="loginForm.stuid" placeholder="请输入学号" />
            </el-form-item>
            <el-form-item label="密码">
              <el-input v-model="loginForm.password" type="password" placeholder="请输入密码" show-password
                @keyup.enter="onLogin" />
            </el-form-item>
            <el-button type="primary" class="submit-btn" @click="onLogin">登录</el-button>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="用户注册" name="register">
          <el-form label-position="top" @submit.prevent="onRegister">
            <el-form-item label="用户名">
              <el-input v-model="regForm.username" placeholder="请输入用户名" />
            </el-form-item>
            <el-form-item label="学号">
              <el-input v-model="regForm.stuid" placeholder="请输入学号" />
            </el-form-item>
            <el-form-item label="密码">
              <el-input v-model="regForm.password" type="password" placeholder="请输入密码" show-password
                @keyup.enter="onRegister" />
            </el-form-item>
            <el-button type="primary" class="submit-btn" @click="onRegister">注册</el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  justify-content: center;
  padding: 60px 0;
}
.login-card { width: 100%; max-width: 400px; }
.submit-btn { width: 100%; margin-top: 8px; }
</style>
