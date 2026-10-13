import axios from 'axios'

// 开发环境由 Vite 代理转发到 http://localhost:8080
const request = axios.create({
  baseURL: '/api',
  timeout: 10000
})

export default request
