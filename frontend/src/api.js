import axios from 'axios'

import { getToken, setUser } from './session'

// 开发环境由 Vite 代理转发到 http://localhost:8080
const request = axios.create({
  baseURL: '/api',
  timeout: 10000
})

/**
 * 慢接口的超时（毫秒）。走大模型的接口用默认的 10s 必超时：
 * 实测对话摘要、议价话术、图片识别、配了 LLM key 后的内容风控，常态都在 10s 以上，
 * 用户看到的是「操作失败」。本地快接口（/ai/search、/ai/price-suggest）不要用它——
 * 那些接口本来就该秒回，快速失败比让用户干等两分钟更合适。
 *
 * 用法：`api.post('/ai/summarize', body, { timeout: AI_TIMEOUT })`
 */
export const AI_TIMEOUT = 120000

// 每个请求带上登录令牌：服务端据此判定调用方身份，不再信任请求体里的 userId
request.interceptors.request.use((config) => {
  const authToken = getToken()
  if (authToken) {
    config.headers = config.headers || {}
    config.headers.Authorization = `Bearer ${authToken}`
  }
  return config
})

// 令牌过期/无效（401）时清掉本地登录态并回登录页，
// 否则页面会一直停在「看似已登录、实际每个请求都失败」的状态。
request.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      setUser(null)
      if (!window.location.pathname.startsWith('/login')) {
        window.location.replace('/login')
      }
    }
    return Promise.reject(error)
  }
)

export default request
