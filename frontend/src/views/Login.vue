<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useApiRequest } from '../composables/useApiRequest'
import { setUser } from '../session'

const router = useRouter()
const activeTab = ref('login')
const loginForm = reactive({ stuid: '', password: '' })
const regForm = reactive({ username: '', stuid: '', password: '' })

// 统一错误提示口径：网络/HTTP 错误由 useApiRequest 自动 ElMessage.error
// 并抛异常，业务字段失败（data.success=false）由调用方处理
const loginApi = useApiRequest('登录失败，请重试')
const registerApi = useApiRequest('注册失败，请重试')

async function onLogin() {
  if (!loginForm.stuid.trim() || !loginForm.password.trim()) {
    ElMessage.warning('请输入账号和密码')
    return
  }
  const params = new URLSearchParams()
  params.append('stuid', loginForm.stuid.trim())
  params.append('password', loginForm.password.trim())
  try {
    const { data } = await loginApi.post('/login', params)
    if (!data.success) {
      ElMessage.error(data.msg || data.message || '登录失败')
      return
    }
    // 写进共享会话对象（同时落 localStorage），导航栏立刻显示新登录的用户和头像。
    // token 必须一起存：之后所有需要身份的请求都靠它，缺了它后端会一律判为未登录。
    setUser(data.user, data.token)
    ElMessage.success('登录成功')
    router.push('/')
  } catch {
    // useApiRequest 已统一提示过业务无关的失败，这里只需吞掉异常防止外层冒泡
  }
}

async function onRegister() {
  if (!regForm.username.trim() || !regForm.stuid.trim() || !regForm.password.trim()) {
    ElMessage.warning('请填写完整信息')
    return
  }
  const params = new URLSearchParams()
  params.append('username', regForm.username.trim())
  params.append('stuid', regForm.stuid.trim())
  params.append('password', regForm.password.trim())
  try {
    const { data } = await registerApi.post('/register', params)
    if (!data.success) {
      ElMessage.error(data.message || '注册失败')
      return
    }
    ElMessage.success('注册成功，请登录')
    activeTab.value = 'login'
    loginForm.stuid = regForm.stuid
  } catch {
    // 同上：网络/HTTP 错误已被 useApiRequest 处理
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
